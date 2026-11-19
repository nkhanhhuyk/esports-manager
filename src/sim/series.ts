/**
 * Series runner for the two engine families: best-of-n MOBA games and best-of-n shooter maps.
 * Handles map vetoes (shooters), side choice and per-game drafts (fearless only when a named
 * preset is enabled), and stops the series as soon as a team reaches the required game wins.
 * A shooter round is never a series win; one map is one game.
 *
 * The runner is incremental (`createSeries` + `playNextGame`) so the UI can play a series game
 * by game with interactive drafts, while `simulateFixture` runs the same code in one call.
 */
import { rivalryPressure } from '../systems/rivalries';
import { RNG } from '../core/rng';
import { getTitle } from '../data/titles/catalogue';
import type { MapDef, TitleDef } from '../data/titles/types';
import type { DraftRecord, Fixture, FormatKey, GameEvent, GameResult, LineupSlot, MatchRecord, Team, UserMatchPlan, World } from '../model/types';
import { currentPatch } from '../systems/patches';
import { isUserTeam } from '../systems/news';
import { ensureLineup, validateLineup } from '../systems/lineup';
import { registeredLineup } from '../systems/registration';
import { evaluateComp, newDraft, runAiDraft, toRecord, validateDraft, type CompEval, type DraftState } from './common/draft';
import type { GameContext } from './common/engine';
import { buildTeamCtx, type TeamCtx } from './common/team';
import { simulateMoba } from './moba/moba';
import { simulateTactical } from './shooter/tactical';

export interface SeriesState {
  fixtureId: string;
  titleId: string;
  games: GameResult[];
  wins: [number, number];
  vetoes: NonNullable<MatchRecord['vetoes']>;
  slots: Array<{ mapId?: string; modeId?: string; firstSide: number }>;
  fearless: [string[], string[]];
  lineups: [LineupSlot[], LineupSlot[]];
  done: boolean;
  winner: number;
  lastLoser: number;
  detail: boolean;
}

export function pressureFor(world: World, f: Fixture): number {
  const comp = world.comps[f.compId];
  const base = f.formatKey === 'final' ? 1 : f.formatKey === 'playoff' ? 0.5 : 0;
  // Rivalry meetings add pressure (composure matters more); they never change ability.
  return base + (comp && (comp.tier === 'worlds' || comp.tier === 'international') ? 0.3 : 0) + rivalryPressure(world, f.teams);
}

function needWins(f: Fixture): number {
  return Math.floor(f.format.n / 2) + 1;
}

// ---------------------------------------------------------------------------------------------
// Map / mode selection
// ---------------------------------------------------------------------------------------------

function mapScore(world: World, team: Team, lineup: LineupSlot[], t: TitleDef, mapId: string): number {
  let s = 0;
  for (const sl of lineup) s += world.players[sl.playerId]?.prof.maps[t.id]?.[mapId] ?? 40;
  return s / Math.max(1, lineup.length) + (team.prefs.mapPrefs[mapId] ?? 0) * 6;
}

function tacticalVeto(world: World, t: TitleDef, teams: [Team, Team], lineups: [LineupSlot[], LineupSlot[]], n: number, rng: RNG, plans: [UserMatchPlan | undefined, UserMatchPlan | undefined]): { maps: string[]; vetoes: SeriesState['vetoes']; firstSides: number[] } {
  const patch = currentPatch(world, t.id);
  let pool = [...patch.mapPool];
  if (pool.length < n) pool = t.maps.map((m) => m.id);
  const vetoes: SeriesState['vetoes'] = [];
  const picks: string[] = [];
  const pickers: number[] = [];
  const total = pool.length;
  const bans = Math.max(0, total - n);
  // sequence: 2 bans, (n-1) picks, remaining bans, decider
  const seq: Array<'ban' | 'pick'> = [];
  const firstBans = Math.min(2, bans);
  for (let i = 0; i < firstBans; i++) seq.push('ban');
  for (let i = 0; i < n - 1; i++) seq.push('pick');
  for (let i = 0; i < bans - firstBans; i++) seq.push('ban');
  const userBans = plans.map((p) => [...(p?.mapBans ?? [])]);
  const userPicks = plans.map((p) => [...(p?.mapPicks ?? [])]);
  seq.forEach((action, i) => {
    const team = i % 2;
    const avail = pool.filter((m) => !vetoes.some((v) => v.mapId === m));
    if (avail.length <= 1) return;
    const diff = (m: string) => mapScore(world, teams[team], lineups[team], t, m) - mapScore(world, teams[1 - team], lineups[1 - team], t, m) + rng.normal(0, 4);
    let choice: string | undefined;
    const wish = action === 'ban' ? userBans[team] : userPicks[team];
    while (wish.length && !choice) {
      const w = wish.shift()!;
      if (avail.includes(w)) choice = w;
    }
    if (!choice) choice = [...avail].sort((a, b) => (action === 'ban' ? diff(a) - diff(b) : diff(b) - diff(a)))[0];
    vetoes.push({ team, action, mapId: choice });
    if (action === 'pick') {
      picks.push(choice);
      pickers.push(team);
    }
  });
  const left = pool.filter((m) => !vetoes.some((v) => v.mapId === m));
  if (picks.length < n && left.length) {
    picks.push(left[0]);
    pickers.push(-1);
    vetoes.push({ team: -1 as unknown as number, action: 'decider', mapId: left[0] });
  }
  while (picks.length < n) picks.push(rng.pick(pool));
  const firstSides = picks.map((m, i) => {
    const map = t.maps.find((x) => x.id === m);
    const chooser = pickers[i] === -1 ? (rng.chance(0.5) ? 0 : 1) : 1 - pickers[i];
    const attackFirst = (map?.sideBias ?? 0) > 0;
    return attackFirst ? chooser : 1 - chooser;
  });
  return { maps: picks, vetoes, firstSides };
}

// ---------------------------------------------------------------------------------------------
// Series lifecycle
// ---------------------------------------------------------------------------------------------

export function getPlans(world: World, f: Fixture): [UserMatchPlan | undefined, UserMatchPlan | undefined] {
  const plan = world.overrides[f.id];
  return f.teams.map((tid) => (plan && isUserTeam(world, tid) ? plan : undefined)) as [UserMatchPlan | undefined, UserMatchPlan | undefined];
}

export function createSeries(world: World, f: Fixture, forceDetail = false): SeriesState {
  const t = getTitle(f.titleId);
  const teams = f.teams.map((id) => world.teams[id]) as [Team, Team];
  const plans = getPlans(world, f);
  const comp = world.comps[f.compId];
  const lineups = teams.map((team, i) => {
    const plan = plans[i];
    const preferred = plan?.lineup && validateLineup(world, team, plan.lineup).ok ? plan.lineup : ensureLineup(world, team);
    // Only players registered for this competition may play.
    return registeredLineup(world, comp, team, preferred);
  }) as [LineupSlot[], LineupSlot[]];
  const vrng = RNG.derive(world.meta.seed, f.id, 'veto');
  const n = f.format.n;
  let slots: SeriesState['slots'] = [];
  let vetoes: SeriesState['vetoes'] = [];
  if (t.mapVeto === 'tactical') {
    const v = tacticalVeto(world, t, teams, lineups, n, vrng, plans);
    slots = v.maps.map((m, i) => ({ mapId: m, firstSide: v.firstSides[i] }));
    vetoes = v.vetoes;
  } else {
    for (let i = 0; i < n; i++) slots.push({ firstSide: vrng.chance(0.5) ? 0 : 1 });
  }
  return {
    fixtureId: f.id,
    titleId: t.id,
    games: [],
    wins: [0, 0],
    vetoes,
    slots,
    fearless: [[], []],
    lineups,
    done: false,
    winner: -1,
    lastLoser: 1,
    detail: forceDetail || f.detail,
  };
}

/** MOBA titles play their single battlefield every game (shooter maps come from the veto). */
function nextMap(t: TitleDef): string | undefined {
  return t.maps[0]?.id;
}

/** Fearless rule in force for a title: the title default unless a named world preset is enabled. */
export function fearlessFor(world: World, titleId: string): 'none' | 'team' | 'global' {
  return world.rulePresets?.[titleId]?.fearless ?? getTitle(titleId).draft.fearless;
}

/**
 * MOBA side choice. Game 1 by a seeded coin toss; afterwards the previous game's loser chooses
 * and takes the first-pick side (MLBB preset, applied to all five MOBAs). Deterministic, so the
 * live draft screen and the simulation agree on who picks first.
 */
export function mobaFirstSide(world: World, f: Fixture, st: SeriesState): 0 | 1 {
  if (!st.games.length) return RNG.derive(world.meta.seed, f.id, 'side').chance(0.5) ? 0 : 1;
  return (st.lastLoser === 0 ? 0 : 1) as 0 | 1;
}

export function draftStateFor(world: World, f: Fixture, st: SeriesState): DraftState {
  const t = getTitle(f.titleId);
  const patch = currentPatch(world, t.id);
  const teams = f.teams.map((id) => world.teams[id]) as [Team, Team];
  let fearless: [Set<string>, Set<string>] = [new Set(), new Set()];
  const fl = fearlessFor(world, t.id);
  if (fl === 'team') fearless = [new Set(st.fearless[0]), new Set(st.fearless[1])];
  else if (fl === 'global') {
    const all = new Set([...st.fearless[0], ...st.fearless[1]]);
    fearless = [all, new Set(all)];
  }
  return newDraft(
    world,
    t,
    patch,
    [0, 1].map((i) => ({ team: teams[i], lineup: st.lineups[i], players: st.lineups[i].map((s) => world.players[s.playerId]).filter(Boolean) })) as never,
    fearless,
    t.engine === 'moba' ? mobaFirstSide(world, f, st) : 0,
  );
}

function compsFor(world: World, t: TitleDef, f: Fixture, st: SeriesState, draft: DraftRecord | undefined): [CompEval, CompEval] {
  const patch = currentPatch(world, t.id);
  const teams = f.teams.map((id) => world.teams[id]);
  return [0, 1].map((i) => {
    const players = st.lineups[i].map((s) => world.players[s.playerId]).filter(Boolean);
    if (!draft || !t.characters.length) return evaluateComp(t, patch, [], [], players, teams[i].prefs.style);
    const chars = st.lineups[i].map((s) => t.characters.find((c) => c.id === draft.assign[i][s.playerId])).filter(Boolean) as never[];
    const enemy = draft.picks[1 - i].map((id) => t.characters.find((c) => c.id === id)).filter(Boolean) as never[];
    return evaluateComp(t, patch, chars, enemy, players, teams[i].prefs.style);
  }) as [CompEval, CompEval];
}

/** Play the next game of a (non-BR) series. `userDraft` overrides the draft for this game. */
export function playNextGame(world: World, f: Fixture, st: SeriesState, userDraft?: DraftRecord): GameResult {
  if (st.done) throw new Error('Series already finished');
  const t = getTitle(f.titleId);
  const gi = st.games.length;
  const rng = RNG.derive(world.meta.seed, f.id, 'g', gi);
  const crng = RNG.derive(world.meta.seed, f.id, 'c', gi);
  const drng = RNG.derive(world.meta.seed, f.id, 'd', gi);
  const mrng = RNG.derive(world.meta.seed, f.id, 'm', gi);
  const teams = f.teams.map((id) => world.teams[id]) as [Team, Team];
  // Map and side: shooters use the veto (map + starting side); MOBAs use the side policy.
  let mapId: string | undefined;
  const modeId: string | undefined = undefined;
  let firstSide = 0;
  if (t.mapVeto === 'tactical') {
    const slot = st.slots[gi] ?? st.slots[st.slots.length - 1];
    mapId = slot.mapId;
    firstSide = slot.firstSide;
  } else {
    mapId = nextMap(t);
    firstSide = t.engine === 'moba' ? mobaFirstSide(world, f, st) : mrng.chance(0.5) ? 0 : 1;
  }
  // Draft
  let draft: DraftRecord | undefined;
  if (t.characters.length && t.draft.mode !== 'none') {
    const plans = getPlans(world, f);
    const planned = userDraft ?? plans.map((p) => p?.drafts?.[gi]).find(Boolean);
    if (planned && validateDraft(t, planned).length === 0) draft = planned;
    else {
      const ds = draftStateFor(world, f, st);
      runAiDraft(world, ds, drng);
      draft = toRecord(ds, [0, 0]);
    }
  }
  const comps = compsFor(world, t, f, st, draft);
  if (draft) draft.compScore = [Math.round(comps[0].total * 100) / 100, Math.round(comps[1].total * 100) / 100];
  const pressure = pressureFor(world, f);
  const ctxs = [0, 1].map((i) =>
    buildTeamCtx(world, teams[i], st.lineups[i], i, rng, { mapId, pressure, opponent: teams[1 - i], chars: draft?.assign[i] }),
  ) as [TeamCtx, TeamCtx];
  const events: GameEvent[] | null = st.detail ? [] : null;
  const g: GameContext = { world, title: t, rng, crng, events, teams: ctxs, gameIdx: gi, mapId, modeId, draft, comp: comps, patch: currentPatch(world, t.id), firstSide, pressure };
  let res: GameResult;
  switch (t.engine) {
    case 'moba':
      res = simulateMoba(g);
      break;
    case 'tactical':
      res = simulateTactical(g);
      break;
    default:
      throw new Error(`Unknown engine ${String(t.engine)}`);
  }
  if (t.engine === 'moba') {
    // Record the side choice so the match report can explain who picked first and why.
    res.sides = [`${teams[firstSide].short} took first pick (${gi === 0 ? 'coin toss' : 'chosen by the previous game\'s loser'})`];
  }
  st.games.push(res);
  // fearless bookkeeping
  if (draft) {
    st.fearless[0].push(...draft.picks[0]);
    st.fearless[1].push(...draft.picks[1]);
  }
  st.lastLoser = 1 - res.winner;
  st.wins[res.winner]++;
  const need = needWins(f);
  if (st.wins[0] >= need || st.wins[1] >= need) {
    st.done = true;
    st.winner = st.wins[0] >= need ? 0 : 1;
  }
  if (st.games.length > 40) {
    st.done = true;
    st.winner = st.wins[0] >= st.wins[1] ? 0 : 1;
  }
  return res;
}

/** Simulate a complete fixture. */
export function runSeries(world: World, f: Fixture, forceDetail = false): SeriesState {
  const st = createSeries(world, f, forceDetail);
  while (!st.done) playNextGame(world, f, st);
  return st;
}

export function formatKeyLabel(k: FormatKey): string {
  return k === 'final' ? 'Final' : k === 'playoff' ? 'Playoff' : 'Standard';
}

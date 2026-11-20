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
import { isFollowed } from '../systems/follow';
import { ensureLineup, validateLineup } from '../systems/lineup';
import { registeredLineup } from '../systems/registration';
import { evaluateComp, newDraft, runAiDraft, toRecord, validateDraft, type CompEval, type DraftState } from './common/draft';
import type { GameContext } from './common/engine';
import { buildTeamCtx, type TeamCtx } from './common/team';
import { simulateMoba } from './moba/moba';
import { simulateMobaSpatial } from './moba/spatial';
import { simulateTactical } from './shooter/tactical';
import { simulateTacticalSpatial } from './shooter/spatialRounds';

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
  /** Record a spatial replay of each game (live viewing). Never changes results. */
  record?: boolean;
  /** MOBA side/pick-order selections made by the user, per game index (AI choices are recomputed). */
  sel?: Array<SideSel | undefined>;
}

/**
 * MOBA side and pick order of one game. `blue` is the series index of the team on side 0 (Blue /
 * Radiant, bottom-left); `firstPick` the series index that drafts first.
 */
export interface SideSel {
  chooser: 0 | 1;
  /** What the chooser took: a side (opponent then picks the draft order) or a draft order (opponent picks the side). */
  choice: 'side' | 'pick';
  blue: 0 | 1;
  firstPick: 0 | 1;
  by: 'coin' | 'loser';
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
  // Lock fidelity before anything is simulated (instant and live paths both pass here).
  if (f.spatial === undefined) f.spatial = f.teams.some((id) => isUserTeam(world, id) || isFollowed(world, id));
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
    record: !!f.spatial,
  };
}

/** MOBA titles play their single battlefield every game (shooter maps come from the veto). */
function nextMap(t: TitleDef): string | undefined {
  return t.maps[0]?.id;
}

/**
 * Fearless rule in force: the competition's own rule (First Stand: hard Fearless — every champion
 * either team has played is unavailable to both teams for the rest of the series), else a named
 * world preset, else the title default.
 */
export function fearlessFor(world: World, titleId: string, compId?: string): 'none' | 'team' | 'global' {
  const comp = compId ? world.comps[compId] : undefined;
  return comp?.rules?.fearless ?? world.rulePresets?.[titleId]?.fearless ?? getTitle(titleId).draft.fearless;
}

/**
 * Titles whose side and draft order are chosen separately ("first selection"): League of Legends
 * (2026 rule) and Dota 2 (Captains Mode). The chooser — a seeded coin toss in game 1, afterwards the
 * previous game's loser — takes either a side or a draft order; the opponent then gets the other
 * choice. In MLBB, Honor of Kings and Wild Rift the chooser takes the Blue side, which drafts first.
 */
export function splitSelection(titleId: string): boolean {
  return titleId === 'lol' || titleId === 'dota2';
}

/** How much a team values the first pick (>0) over the last pick / counter-pick (<0). */
export function firstPickWant(world: World, team: Team, titleId: string): number {
  const vals = Object.values(currentPatch(world, titleId).charMeta).sort((a, b) => b - a);
  const standout = vals.length >= 6 ? (vals[0] - vals[5]) / 4 : 0;
  const pref = ({ meta: 0.4, comfort: 0.2, balanced: 0, counter: -0.5 } as Record<string, number>)[team.prefs.draftPriority] ?? 0;
  return standout - 0.25 + pref;
}

/** Value of Blue / Radiant side relative to the pick-order scale above (side win-rate edge in pro play). */
const SIDE_VALUE: Record<string, number> = { lol: 0.55, dota2: 0.3 };

export function sideChooser(world: World, f: Fixture, st: SeriesState): { chooser: 0 | 1; by: 'coin' | 'loser' } {
  if (!st.games.length) return { chooser: RNG.derive(world.meta.seed, f.id, 'side').chance(0.5) ? 0 : 1, by: 'coin' };
  return { chooser: (st.lastLoser === 0 ? 0 : 1) as 0 | 1, by: 'loser' };
}

/** Complete a selection from the chooser's choice (the other team answers with its preference). */
export function completeSelection(world: World, f: Fixture, chooser: 0 | 1, by: 'coin' | 'loser', pick: { side?: 0 | 1; order?: 'first' | 'second' }, gi = 0): SideSel {
  const other = (1 - chooser) as 0 | 1;
  const noise = RNG.derive(world.meta.seed, f.id, 'sel', gi).normal(0, 0.15);
  if (pick.side !== undefined) {
    // Chooser took a side (0 = Blue, 1 = Red); the opponent picks the draft order.
    const blue = (pick.side === 0 ? chooser : other) as 0 | 1;
    const want = firstPickWant(world, world.teams[f.teams[other]], f.titleId) + noise;
    return { chooser, choice: 'side', blue, firstPick: want > 0 ? other : chooser, by };
  }
  const firstPick = (pick.order === 'first' ? chooser : other) as 0 | 1;
  // The opponent picks the side: Blue / Radiant unless it has no edge in this title.
  return { chooser, choice: 'pick', blue: (SIDE_VALUE[f.titleId] ?? 0.3) + noise > 0 ? other : chooser, firstPick, by };
}

/** AI selection for the chooser. */
export function aiSelection(world: World, f: Fixture, chooser: 0 | 1, by: 'coin' | 'loser', gi = 0): SideSel {
  const want = firstPickWant(world, world.teams[f.teams[chooser]], f.titleId) + RNG.derive(world.meta.seed, f.id, 'selc', gi).normal(0, 0.2);
  const sideValue = SIDE_VALUE[f.titleId] ?? 0.3;
  if (Math.abs(want) > sideValue) return completeSelection(world, f, chooser, by, { order: want > 0 ? 'first' : 'second' }, gi);
  return completeSelection(world, f, chooser, by, { side: 0 }, gi);
}

/**
 * Side and draft order of the next game. Deterministic, so the live draft screen and the
 * simulation agree; a choice the user made on the live screen (`st.sel`) is used as is.
 */
export function gameSides(world: World, f: Fixture, st: SeriesState): SideSel {
  const gi = st.games.length;
  const own = st.sel?.[gi];
  if (own) return own;
  const { chooser, by } = sideChooser(world, f, st);
  if (!splitSelection(f.titleId)) return { chooser, choice: 'side', blue: chooser, firstPick: chooser, by };
  return aiSelection(world, f, chooser, by, gi);
}

/** Series index of the team that drafts first in the next game (kept for callers of the old API). */
export function mobaFirstSide(world: World, f: Fixture, st: SeriesState): 0 | 1 {
  return gameSides(world, f, st).firstPick;
}

/** Describe a selection for the match report. */
export function describeSelection(world: World, f: Fixture, sel: SideSel, sideNames: [string, string] = ['Blue', 'Red']): string {
  const n = (i: number) => world.teams[f.teams[i]]?.short ?? `Team ${i + 1}`;
  const how = sel.by === 'coin' ? 'won the coin toss' : 'lost the previous game';
  const other = 1 - sel.chooser;
  const side = (i: number) => (sel.blue === i ? sideNames[0] : sideNames[1]);
  const order = (i: number) => (sel.firstPick === i ? 'first pick' : 'second pick');
  if (!splitSelection(f.titleId)) return `${n(sel.chooser)} (${how}) took ${side(sel.chooser)} side and first pick`;
  return sel.choice === 'side'
    ? `${n(sel.chooser)} (${how}) chose ${side(sel.chooser)} side; ${n(other)} chose ${order(other)}`
    : `${n(sel.chooser)} (${how}) chose ${order(sel.chooser)}; ${n(other)} chose ${side(other)} side`;
}

export function draftStateFor(world: World, f: Fixture, st: SeriesState): DraftState {
  const t = getTitle(f.titleId);
  const patch = currentPatch(world, t.id);
  const teams = f.teams.map((id) => world.teams[id]) as [Team, Team];
  let fearless: [Set<string>, Set<string>] = [new Set(), new Set()];
  const fl = fearlessFor(world, t.id, f.compId);
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
    t.engine === 'moba' ? gameSides(world, f, st).firstPick : 0,
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
    firstSide = t.engine === 'moba' ? 0 : mrng.chance(0.5) ? 0 : 1;
  }
  const sel = t.engine === 'moba' ? gameSides(world, f, st) : undefined;
  if (sel) firstSide = sel.firstPick;
  // Draft
  let draft: DraftRecord | undefined;
  if (t.characters.length && t.draft.mode !== 'none') {
    const plans = getPlans(world, f);
    const planned = userDraft ?? plans.map((p) => p?.drafts?.[gi]).find(Boolean);
    const ds = draftStateFor(world, f, st);
    if (planned && validateDraft(t, planned, ds.unavailable).length === 0) draft = { ...planned, firstPick: sel?.firstPick ?? planned.firstPick };
    else {
      runAiDraft(world, ds, drng);
      draft = toRecord(ds, [0, 0]);
    }
  }
  const comps = compsFor(world, t, f, st, draft);
  if (draft) draft.compScore = [Math.round(comps[0].total * 100) / 100, Math.round(comps[1].total * 100) / 100];
  const pressure = pressureFor(world, f);
  // MOBAs: the simulation runs in map-side order (index 0 = Blue / Radiant, bottom-left). When the
  // series' second team is on Blue the teams are swapped for the simulation and the result is
  // mapped back to series order afterwards; the spatial replay stays in side order (`res.blue`).
  const swap = !!sel && sel.blue === 1;
  const order = swap ? [1, 0] : [0, 1];
  const ctxs = order.map((fi, si) =>
    buildTeamCtx(world, teams[fi], st.lineups[fi], si, rng, { mapId, pressure, opponent: teams[1 - fi], chars: draft?.assign[fi] }),
  ) as [TeamCtx, TeamCtx];
  const events: GameEvent[] | null = st.detail ? [] : null;
  const simDraft = draft && swap ? swapDraft(draft) : draft;
  const simComps = (swap ? [comps[1], comps[0]] : comps) as [CompEval, CompEval];
  const g: GameContext = { world, title: t, rng, crng, events, teams: ctxs, gameIdx: gi, mapId, modeId, draft: simDraft, comp: simComps, patch: currentPatch(world, t.id), firstSide: swap ? 1 - firstSide : firstSide, pressure };
  let res: GameResult;
  const emptySim = ctxs.map((c) => c.players.length === 0);
  const empty = swap ? [emptySim[1], emptySim[0]] : emptySim;
  let mapped = !swap;
  if (empty[0] || empty[1]) {
    mapped = true;
    // A side with nobody available (injuries, breaks, no registrations) forfeits the game.
    const w = empty[0] && !empty[1] ? 1 : empty[1] && !empty[0] ? 0 : mrng.chance(0.5) ? 0 : 1;
    res = { idx: gi, winner: w, mapId, modeId, duration: 0, score: [w === 0 ? 1 : 0, w === 1 ? 1 : 0], teamStats: [{}, {}], players: [], summary: `${teams[1 - w].name} could not field a lineup — walkover to ${teams[w].name}.` };
  } else switch (t.engine) {
    case 'moba':
      // Fidelity is locked with the fixture before it is played: full-detail fixtures (your and
      // followed teams, internationals, anything opened live) run the spatial battlefield
      // simulation; other background fixtures use the fast aggregate engine. Watching never
      // switches engines for a fixture, so live, text and instant results are identical.
      if (f.spatial) {
        res = simulateMobaSpatial(g, !!st.record);
        res.engine = 'spatial';
      } else {
        res = simulateMoba(g);
        res.engine = 'fast';
      }
      break;
    case 'tactical':
      // Same fidelity lock as MOBAs: the spatial round simulation on the real map, or the fast engine.
      if (f.spatial) res = simulateTacticalSpatial(g, !!st.record);
      else {
        res = simulateTactical(g);
        res.engine = 'fast';
      }
      break;
    default:
      throw new Error(`Unknown engine ${String(t.engine)}`);
  }
  if (!mapped) {
    unswapResult(res);
    if (draft) res.draft = draft;
  }
  if (sel) {
    // Record the side and draft order so the match report can show who was on which side and why.
    res.blue = sel.blue;
    res.sides = [describeSelection(world, f, sel, (t.id === 'dota2' ? ['Radiant', 'Dire'] : ['Blue', 'Red']) as [string, string])];
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

/** Swap the two teams of a draft record (series order ↔ map-side order). */
function swapDraft(d: DraftRecord): DraftRecord {
  return {
    ...d,
    bans: [d.bans[1], d.bans[0]],
    picks: [d.picks[1], d.picks[0]],
    assign: [d.assign[1], d.assign[0]],
    compScore: [d.compScore[1], d.compScore[0]],
    order: d.order.map((o) => ({ ...o, team: 1 - o.team })),
    ...(d.firstPick !== undefined ? { firstPick: (1 - d.firstPick) as 0 | 1 } : {}),
  };
}

/** Map a game simulated in side order back to series order (the replay keeps side order). */
function unswapResult(res: GameResult): void {
  if (res.winner >= 0) res.winner = 1 - res.winner;
  res.score = [res.score[1], res.score[0]];
  res.teamStats = [res.teamStats[1] ?? {}, res.teamStats[0] ?? {}];
  for (const l of res.players) l.teamIdx = 1 - l.teamIdx;
  if (res.events) for (const e of res.events) if (e.team === 0 || e.team === 1) e.team = (1 - e.team) as 0 | 1;
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

/**
 * Series runner. Handles map vetoes / map & mode rotation, per-game drafts (with fearless
 * restrictions), Brawl Stars sets (first to N game wins per set), best-of logic and BR
 * multi-game events (fixed game count or match-point finals).
 *
 * The runner is incremental (`createSeries` + `playNextGame`) so the UI can play a series game
 * by game with interactive drafts, while `simulateFixture` runs the same code in one call.
 */
import { RNG } from '../core/rng';
import { getTitle } from '../data/titles/catalogue';
import type { MapDef, TitleDef } from '../data/titles/types';
import type { DraftRecord, Fixture, FormatKey, GameEvent, GameResult, LineupSlot, MatchRecord, Team, UserMatchPlan, World } from '../model/types';
import { currentPatch } from '../systems/patches';
import { isUserTeam } from '../systems/news';
import { ensureLineup, validateLineup } from '../systems/lineup';
import { evaluateComp, NEUTRAL_COMP, newDraft, runAiDraft, toRecord, validateDraft, type CompEval, type DraftState } from './common/draft';
import type { GameContext } from './common/engine';
import { buildTeamCtx, type TeamCtx } from './common/team';
import { simulateMoba } from './moba/moba';
import { simulateBrawl } from './moba/brawl';
import { simulateTactical } from './shooter/tactical';
import { simulateHero } from './shooter/hero';
import { simulateArena } from './shooter/arena';
import { brPoints, simulateBr } from './shooter/br';

export interface SeriesState {
  fixtureId: string;
  titleId: string;
  games: GameResult[];
  wins: [number, number];
  setWins: [number, number];
  setIndex: number;
  setDraft: DraftRecord | null;
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
  return base + (comp && (comp.tier === 'worlds' || comp.tier === 'international') ? 0.3 : 0);
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

function mapsForMode(t: TitleDef, modeId: string | undefined, pool: string[]): MapDef[] {
  const ms = t.maps.filter((m) => pool.includes(m.id) && (!modeId || !m.modes || m.modes.includes(modeId)));
  return ms.length ? ms : t.maps.filter((m) => !modeId || !m.modes || m.modes.includes(modeId));
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
  const lineups = teams.map((team, i) => {
    const plan = plans[i];
    if (plan?.lineup && validateLineup(world, team, plan.lineup).ok) return plan.lineup;
    return ensureLineup(world, team);
  }) as [LineupSlot[], LineupSlot[]];
  const vrng = RNG.derive(world.meta.seed, f.id, 'veto');
  const n = f.format.kind === 'sets' ? f.format.n : f.format.n;
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
    setWins: [0, 0],
    setIndex: 0,
    setDraft: null,
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

/** Choose map/mode for the next game (non-tactical titles) given previous results. */
function nextMapMode(world: World, t: TitleDef, f: Fixture, st: SeriesState, rng: RNG): { mapId?: string; modeId?: string } {
  const patch = currentPatch(world, t.id);
  const pool = patch.mapPool.length ? patch.mapPool : t.maps.map((m) => m.id);
  const played = st.games.map((g) => g.mapId);
  const gi = f.format.kind === 'sets' ? st.setIndex : st.games.length;
  const teams = f.teams.map((id) => world.teams[id]) as [Team, Team];
  if (t.mapVeto === 'none') return { mapId: t.maps[0]?.id };
  if (t.mapVeto === 'loser-pick') {
    const rotation = t.hero?.rotation ?? [];
    const modeId = rotation.length ? rotation[gi % rotation.length] : undefined;
    const picker = gi === 0 ? 0 : st.lastLoser;
    const cands = mapsForMode(t, modeId, pool).filter((m) => !played.includes(m.id));
    const list = cands.length ? cands : mapsForMode(t, modeId, pool);
    const best = [...list].sort((a, b) => mapScore(world, teams[picker], st.lineups[picker], t, b.id) - mapScore(world, teams[picker], st.lineups[picker], t, a.id) + rng.normal(0, 3))[0];
    return { mapId: best?.id, modeId };
  }
  // mode-rotation (CoD / CoD Mobile / Brawl Stars)
  const rotation = t.arena?.rotation ?? t.brawl?.modes.map((m) => m.id) ?? [];
  let modeId: string | undefined;
  if (t.brawl) {
    const offset = Math.floor(RNG.derive(world.meta.seed, f.id, 'modes').next() * rotation.length);
    modeId = rotation[(gi + offset) % rotation.length];
  } else modeId = rotation[gi % rotation.length];
  const picker = gi % 2;
  const cands = mapsForMode(t, modeId, pool).filter((m) => !played.includes(m.id));
  const list = cands.length ? cands : mapsForMode(t, modeId, pool);
  const best = [...list].sort((a, b) => mapScore(world, teams[picker], st.lineups[picker], t, b.id) - mapScore(world, teams[picker], st.lineups[picker], t, a.id) + rng.normal(0, 3))[0];
  return { mapId: best?.id, modeId };
}

export function draftStateFor(world: World, f: Fixture, st: SeriesState): DraftState {
  const t = getTitle(f.titleId);
  const patch = currentPatch(world, t.id);
  const teams = f.teams.map((id) => world.teams[id]) as [Team, Team];
  let fearless: [Set<string>, Set<string>] = [new Set(), new Set()];
  if (t.draft.fearless === 'team') fearless = [new Set(st.fearless[0]), new Set(st.fearless[1])];
  else if (t.draft.fearless === 'global') {
    const all = new Set([...st.fearless[0], ...st.fearless[1]]);
    fearless = [all, new Set(all)];
  }
  return newDraft(
    world,
    t,
    patch,
    [0, 1].map((i) => ({ team: teams[i], lineup: st.lineups[i], players: st.lineups[i].map((s) => world.players[s.playerId]).filter(Boolean) })) as never,
    fearless,
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
  const isSets = f.format.kind === 'sets';
  // Map & mode
  let mapId: string | undefined;
  let modeId: string | undefined;
  let firstSide = 0;
  if (t.mapVeto === 'tactical') {
    const slot = st.slots[gi] ?? st.slots[st.slots.length - 1];
    mapId = slot.mapId;
    firstSide = slot.firstSide;
  } else if (isSets && st.setWins[0] + st.setWins[1] > 0 && st.games.length) {
    const last = st.games[st.games.length - 1];
    mapId = last.mapId;
    modeId = last.modeId;
    firstSide = mrng.chance(0.5) ? 0 : 1;
  } else {
    const mm = nextMapMode(world, t, f, st, mrng);
    mapId = mm.mapId;
    modeId = mm.modeId;
    firstSide = mrng.chance(0.5) ? 0 : 1;
  }
  // Draft
  let draft: DraftRecord | undefined;
  if (t.characters.length && t.draft.mode !== 'none') {
    const plans = getPlans(world, f);
    const planned = userDraft ?? plans.map((p) => p?.drafts?.[gi]).find(Boolean);
    if (isSets && st.setDraft && (st.setWins[0] > 0 || st.setWins[1] > 0)) draft = st.setDraft;
    else if (planned && validateDraft(t, planned).length === 0) draft = planned;
    else {
      const ds = draftStateFor(world, f, st);
      runAiDraft(world, ds, drng);
      draft = toRecord(ds, [0, 0]);
    }
    if (isSets) st.setDraft = draft;
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
    case 'hybrid':
      res = simulateMoba(g);
      break;
    case 'brawl':
      res = simulateBrawl(g);
      break;
    case 'tactical':
      res = simulateTactical(g);
      break;
    case 'hero':
      res = simulateHero(g);
      break;
    case 'arena':
      res = simulateArena(g);
      break;
    default:
      throw new Error(`Engine ${t.engine} is not a two-team engine`);
  }
  st.games.push(res);
  // fearless bookkeeping
  if (draft) {
    st.fearless[0].push(...draft.picks[0]);
    st.fearless[1].push(...draft.picks[1]);
  }
  st.lastLoser = 1 - res.winner;
  if (isSets) {
    st.setWins[res.winner]++;
    const setNeed = f.format.setWins ?? 2;
    if (st.setWins[res.winner] >= setNeed) {
      st.wins[res.winner]++;
      st.setWins = [0, 0];
      st.setIndex++;
      st.setDraft = null;
    }
  } else st.wins[res.winner]++;
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

/** Simulate a complete non-BR fixture. */
export function runSeries(world: World, f: Fixture, forceDetail = false): SeriesState {
  const st = createSeries(world, f, forceDetail);
  while (!st.done) playNextGame(world, f, st);
  return st;
}

// ---------------------------------------------------------------------------------------------
// Battle royale events
// ---------------------------------------------------------------------------------------------

export interface BrSeriesResult {
  games: GameResult[];
  points: Record<string, number>;
  kills: Record<string, number>;
  wins: Record<string, number>;
  winner: string | null;
}

export function runBrSeries(world: World, f: Fixture, forceDetail = false): BrSeriesResult {
  const t = getTitle(f.titleId);
  const patch = currentPatch(world, t.id);
  const pool = patch.mapPool.length ? patch.mapPool : t.maps.map((m) => m.id);
  const teams = f.teams.map((id) => world.teams[id]).filter(Boolean);
  const lineups = teams.map((team) => ensureLineup(world, team));
  const points: Record<string, number> = {};
  const kills: Record<string, number> = {};
  const wins: Record<string, number> = {};
  for (const team of teams) {
    points[team.id] = 0;
    kills[team.id] = 0;
    wins[team.id] = 0;
  }
  const games: GameResult[] = [];
  const mp = f.format.matchPoint;
  const maxGames = mp ? f.format.n + 8 : f.format.n;
  let mpWinner: string | null = null;
  const pressure = pressureFor(world, f);
  for (let gi = 0; gi < maxGames; gi++) {
    if (!mp && gi >= f.format.n) break;
    const rng = RNG.derive(world.meta.seed, f.id, 'g', gi);
    const crng = RNG.derive(world.meta.seed, f.id, 'c', gi);
    const drng = RNG.derive(world.meta.seed, f.id, 'd', gi);
    const mapId = pool[gi % pool.length];
    // Legend selection (unique within team) for character-based battle royales.
    const comps: CompEval[] = [];
    const ctxs = teams.map((team, i) => {
      let chars: Record<string, string> | undefined;
      if (t.characters.length) {
        chars = {};
        const used = new Set<string>();
        for (const sl of lineups[i]) {
          const p = world.players[sl.playerId];
          const role = t.roles.find((r) => r.id === sl.roleId)!;
          const opts = t.characters.filter((c) => !used.has(c.id));
          const best = [...opts].sort((a, b) => {
            const sa = (p?.prof.chars[t.id]?.[a.id] ?? 20) / 100 + (patch.charMeta[a.id] ?? 0) / 20 + (role.classes.includes(a.cls) ? 0.3 : 0) + drng.normal(0, 0.05);
            const sb = (p?.prof.chars[t.id]?.[b.id] ?? 20) / 100 + (patch.charMeta[b.id] ?? 0) / 20 + (role.classes.includes(b.cls) ? 0.3 : 0) + drng.normal(0, 0.05);
            return sb - sa;
          })[0];
          if (best) {
            chars[sl.playerId] = best.id;
            used.add(best.id);
          }
        }
        const ps = lineups[i].map((s) => world.players[s.playerId]).filter(Boolean);
        comps.push(evaluateComp(t, patch, lineups[i].map((s) => t.characters.find((c) => c.id === chars![s.playerId])!).filter(Boolean), [], ps, team.prefs.style));
      } else comps.push(evaluateComp(t, patch, [], [], [], team.prefs.style));
      return buildTeamCtx(world, team, lineups[i], i, rng, { mapId, pressure, chars });
    });
    const events: GameEvent[] | null = forceDetail || f.detail ? [] : null;
    const leadersAtStart = mp ? Object.entries(points).filter(([, v]) => v >= mp).map(([k]) => k) : [];
    const res = simulateBr({ world, title: t, rng, crng, events, teams: ctxs, comps, gameIdx: gi, mapId, patch, pressure });
    games.push(res);
    res.placements!.forEach((tid, place) => {
      const k = res.brKills?.[tid] ?? 0;
      points[tid] += brPoints(t, place + 1, k);
      kills[tid] += k;
      if (place === 0) wins[tid]++;
    });
    if (mp && gi >= f.format.n - 1) {
      const champ = res.placements![0];
      if (leadersAtStart.includes(champ)) {
        mpWinner = champ;
        break;
      }
    }
    if (mp && gi >= maxGames - 1) break;
  }
  const winner = mpWinner ?? Object.entries(points).sort((a, b) => b[1] - a[1] || (wins[b[0]] ?? 0) - (wins[a[0]] ?? 0))[0]?.[0] ?? null;
  void NEUTRAL_COMP;
  return { games, points, kills, wins, winner };
}

export function formatKeyLabel(k: FormatKey): string {
  return k === 'final' ? 'Final' : k === 'playoff' ? 'Playoff' : 'Standard';
}

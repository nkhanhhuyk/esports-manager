/**
 * Stage format engines: scheduling, bracket graphs, Swiss pairing and standings.
 * All formats are data-driven (Stage.type + Stage.config) so leagues can be edited without
 * touching simulation code.
 */
import { dayOfWeek } from '../core/date';
import type { BracketNode, BracketSource, Competition, Fixture, FormatKey, SeriesFormat, Stage, StandingRow, World } from '../model/types';
import { nextId } from '../model/ids';
import { getTitle } from '../data/titles/catalogue';

// ---------------------------------------------------------------------------------------------
// Utilities
// ---------------------------------------------------------------------------------------------

export function formatFor(world: World, comp: Competition, key: FormatKey): SeriesFormat {
  return comp.formatOverrides?.[key] ?? world.customFormats?.[comp.titleId]?.[key] ?? getTitle(comp.titleId).formats[key];
}

/** List of the next `count` match days on or after `from` whose weekday is in `dows`. */
export function matchDays(from: number, count: number, dows: number[] = [5, 6]): number[] {
  const out: number[] = [];
  let d = from;
  while (out.length < count) {
    if (dows.includes(dayOfWeek(d))) out.push(d);
    d++;
  }
  return out;
}

export function addFixture(world: World, f: Omit<Fixture, 'id'>): Fixture {
  const fx: Fixture = { id: nextId(world, 'f'), ...f };
  world.fixtures[fx.id] = fx;
  (world.schedule[fx.day] ||= []).push(fx.id);
  return fx;
}

function isDetail(world: World, comp: Competition, teams: string[]): boolean {
  if (comp.fullDetail) return true;
  if (world.meta.detail.fullDetailTitles.includes(comp.titleId)) return true;
  if (world.meta.detail.internationals && (comp.tier === 'international' || comp.tier === 'worlds')) return true;
  if (world.meta.detail.userMatches && world.meta.userOrgId) {
    for (const tid of teams) if (world.teams[tid]?.orgId === world.meta.userOrgId) return true;
  }
  return false;
}

function emptyRow(teamId: string, group?: number): StandingRow {
  return { teamId, w: 0, l: 0, gw: 0, gl: 0, pts: 0, kills: 0, wins: 0, group, diff: 0 };
}

// ---------------------------------------------------------------------------------------------
// Round robin
// ---------------------------------------------------------------------------------------------

/** Circle-method round robin. Returns rounds of [a, b] pairs (byes removed). */
export function roundRobinRounds(teams: string[]): Array<Array<[string, string]>> {
  const list = [...teams];
  if (list.length % 2 === 1) list.push('__BYE__');
  const n = list.length;
  const rounds: Array<Array<[string, string]>> = [];
  const arr = list.slice();
  for (let r = 0; r < n - 1; r++) {
    const pairs: Array<[string, string]> = [];
    for (let i = 0; i < n / 2; i++) {
      const a = arr[i];
      const b = arr[n - 1 - i];
      if (a !== '__BYE__' && b !== '__BYE__') pairs.push(r % 2 === 0 ? [a, b] : [b, a]);
    }
    rounds.push(pairs);
    // rotate all but first
    arr.splice(1, 0, arr.pop()!);
  }
  return rounds;
}

function scheduleRoundRobin(world: World, comp: Competition, stage: Stage, double: boolean): void {
  let rounds = roundRobinRounds(stage.teamIds);
  if (double) rounds = [...rounds, ...rounds.map((r) => r.map(([a, b]) => [b, a] as [string, string]))];
  const days = matchDays(stage.startDay, rounds.length, stage.config.matchDaysOfWeek ?? [5, 6]);
  const fmtKey = stage.config.format ?? 'standard';
  rounds.forEach((pairs, ri) => {
    for (const [a, b] of pairs) {
      const f = addFixture(world, {
        compId: comp.id,
        stageId: stage.id,
        titleId: comp.titleId,
        day: days[ri],
        teams: [a, b],
        format: formatFor(world, comp, fmtKey),
        formatKey: fmtKey,
        status: 'scheduled',
        round: ri,
        detail: isDetail(world, comp, [a, b]),
      });
      stage.fixtureIds.push(f.id);
    }
  });
}

function scheduleGroups(world: World, comp: Competition, stage: Stage): void {
  const g = Math.max(1, stage.config.groups ?? 2);
  const groups: string[][] = Array.from({ length: g }, () => []);
  stage.teamIds.forEach((tid, i) => {
    const cycle = Math.floor(i / g);
    const gi = cycle % 2 === 0 ? i % g : g - 1 - (i % g);
    groups[gi].push(tid);
  });
  stage.standings = [];
  groups.forEach((teams, gi) => teams.forEach((t) => stage.standings.push(emptyRow(t, gi))));
  const fmtKey = stage.config.format ?? 'standard';
  const dows = stage.config.matchDaysOfWeek ?? [0, 1, 2, 3, 4, 5, 6];
  groups.forEach((teams, gi) => {
    const rounds = roundRobinRounds(teams);
    const days = matchDays(stage.startDay, rounds.length, dows);
    rounds.forEach((pairs, ri) => {
      for (const [a, b] of pairs) {
        const f = addFixture(world, {
          compId: comp.id,
          stageId: stage.id,
          titleId: comp.titleId,
          day: days[ri],
          teams: [a, b],
          format: formatFor(world, comp, fmtKey),
          formatKey: fmtKey,
          status: 'scheduled',
          round: ri,
          group: gi,
          detail: isDetail(world, comp, [a, b]),
        });
        stage.fixtureIds.push(f.id);
      }
    });
  });
}

// ---------------------------------------------------------------------------------------------
// Bracket graphs (single elimination, double elimination, page playoff)
// ---------------------------------------------------------------------------------------------

export function bracketSeeding(size: number): number[] {
  let order = [1, 2];
  while (order.length < size) {
    const n = order.length * 2;
    const next: number[] = [];
    for (const s of order) next.push(s, n + 1 - s);
    order = next;
  }
  return order;
}

const node = (id: string, round: number, label: string, a: BracketSource, b: BracketSource, elimPlace: number, format: FormatKey = 'playoff', isFinal = false): BracketNode => ({
  id,
  round,
  label,
  a,
  b,
  teamA: undefined,
  teamB: undefined,
  fixtureId: null,
  winner: null,
  loser: null,
  format,
  elimPlace,
  isFinal,
});

export function buildSingleElim(n: number): BracketNode[] {
  const size = Math.max(2, Math.pow(2, Math.ceil(Math.log2(Math.max(2, n)))));
  const rounds = Math.log2(size);
  const seeds = bracketSeeding(size);
  const nodes: BracketNode[] = [];
  const label = (r: number): string => {
    const left = size / Math.pow(2, r);
    if (left === 2) return 'Grand Final';
    if (left === 4) return 'Semifinal';
    if (left === 8) return 'Quarterfinal';
    return `Round of ${left}`;
  };
  for (let i = 0; i < size / 2; i++) {
    nodes.push(node(`r0m${i}`, 0, label(0), { seed: seeds[2 * i] }, { seed: seeds[2 * i + 1] }, size / 2 + 1, rounds === 1 ? 'final' : 'playoff', rounds === 1));
  }
  for (let r = 1; r < rounds; r++) {
    const count = size / Math.pow(2, r + 1);
    for (let i = 0; i < count; i++) {
      const isFinal = r === rounds - 1;
      nodes.push(node(`r${r}m${i}`, r, label(r), { winnerOf: `r${r - 1}m${2 * i}` }, { winnerOf: `r${r - 1}m${2 * i + 1}` }, count + 1, isFinal ? 'final' : 'playoff', isFinal));
    }
  }
  return nodes;
}

export function buildDoubleElim(n: number): BracketNode[] {
  if (n <= 4) {
    return [
      node('u1', 0, 'Upper Semifinal', { seed: 1 }, { seed: 4 }, 0),
      node('u2', 0, 'Upper Semifinal', { seed: 2 }, { seed: 3 }, 0),
      node('uf', 1, 'Upper Final', { winnerOf: 'u1' }, { winnerOf: 'u2' }, 0),
      node('l1', 1, 'Lower Round 1', { loserOf: 'u1' }, { loserOf: 'u2' }, 4),
      node('lf', 2, 'Lower Final', { winnerOf: 'l1' }, { loserOf: 'uf' }, 3),
      node('gf', 3, 'Grand Final', { winnerOf: 'uf' }, { winnerOf: 'lf' }, 2, 'final', true),
    ];
  }
  return [
    node('u1', 0, 'Upper Quarterfinal', { seed: 1 }, { seed: 8 }, 0),
    node('u2', 0, 'Upper Quarterfinal', { seed: 4 }, { seed: 5 }, 0),
    node('u3', 0, 'Upper Quarterfinal', { seed: 2 }, { seed: 7 }, 0),
    node('u4', 0, 'Upper Quarterfinal', { seed: 3 }, { seed: 6 }, 0),
    node('u5', 1, 'Upper Semifinal', { winnerOf: 'u1' }, { winnerOf: 'u2' }, 0),
    node('u6', 1, 'Upper Semifinal', { winnerOf: 'u3' }, { winnerOf: 'u4' }, 0),
    node('l1', 1, 'Lower Round 1', { loserOf: 'u1' }, { loserOf: 'u2' }, 7),
    node('l2', 1, 'Lower Round 1', { loserOf: 'u3' }, { loserOf: 'u4' }, 7),
    node('u7', 2, 'Upper Final', { winnerOf: 'u5' }, { winnerOf: 'u6' }, 0),
    node('l3', 2, 'Lower Round 2', { winnerOf: 'l1' }, { loserOf: 'u6' }, 5),
    node('l4', 2, 'Lower Round 2', { winnerOf: 'l2' }, { loserOf: 'u5' }, 5),
    node('l5', 3, 'Lower Round 3', { winnerOf: 'l3' }, { winnerOf: 'l4' }, 4),
    node('l6', 4, 'Lower Final', { winnerOf: 'l5' }, { loserOf: 'u7' }, 3),
    node('gf', 5, 'Grand Final', { winnerOf: 'u7' }, { winnerOf: 'l6' }, 2, 'final', true),
  ];
}

export function buildPagePlayoff(): BracketNode[] {
  return [
    node('q1', 0, 'Qualifier 1 (1v2)', { seed: 1 }, { seed: 2 }, 0),
    node('e1', 0, 'Eliminator (3v4)', { seed: 3 }, { seed: 4 }, 4),
    node('sf', 1, 'Qualifier 2', { loserOf: 'q1' }, { winnerOf: 'e1' }, 3),
    node('gf', 2, 'Grand Final', { winnerOf: 'q1' }, { winnerOf: 'sf' }, 2, 'final', true),
  ];
}

function resolveSource(stage: Stage, src: BracketSource): string | null | undefined {
  if ('seed' in src) return stage.teamIds[src.seed - 1] ?? null;
  const ref = stage.bracket.find((n) => n.id === ('winnerOf' in src ? src.winnerOf : src.loserOf));
  if (!ref) return null;
  const decided = ref.winner !== null || ref.loser !== null || (ref.teamA === null && ref.teamB === null);
  if (!decided) return undefined;
  return 'winnerOf' in src ? ref.winner : ref.loser;
}

/** Resolve ready bracket nodes, auto-advance byes and create fixtures. Returns true if anything changed. */
export function progressBracket(world: World, comp: Competition, stage: Stage): boolean {
  let changed = true;
  let any = false;
  const step = stage.config.daysBetweenRounds ?? 2;
  while (changed) {
    changed = false;
    for (const nd of stage.bracket) {
      if (nd.fixtureId || nd.winner || nd.loser || (nd.teamA === null && nd.teamB === null)) continue;
      const a = resolveSource(stage, nd.a);
      const b = resolveSource(stage, nd.b);
      if (a === undefined || b === undefined) continue;
      nd.teamA = a;
      nd.teamB = b;
      if (a === null || b === null) {
        nd.winner = a ?? b ?? null;
        nd.loser = null;
        changed = true;
        any = true;
        continue;
      }
      const planned = stage.startDay + nd.round * step;
      const day = Math.max(planned, world.day + 1);
      const f = addFixture(world, {
        compId: comp.id,
        stageId: stage.id,
        titleId: comp.titleId,
        day,
        teams: [a, b],
        format: formatFor(world, comp, nd.format),
        formatKey: nd.format,
        status: 'scheduled',
        nodeId: nd.id,
        round: nd.round,
        detail: isDetail(world, comp, [a, b]),
      });
      nd.fixtureId = f.id;
      stage.fixtureIds.push(f.id);
      changed = true;
      any = true;
    }
  }
  return any;
}

// ---------------------------------------------------------------------------------------------
// Swiss
// ---------------------------------------------------------------------------------------------

function playedPairs(world: World, stage: Stage): Set<string> {
  const s = new Set<string>();
  for (const fid of stage.fixtureIds) {
    const f = world.fixtures[fid];
    if (f) s.add([...f.teams].sort().join('|'));
  }
  return s;
}

export function scheduleSwissRound(world: World, comp: Competition, stage: Stage): boolean {
  const W = stage.config.swissWins ?? 3;
  const L = stage.config.swissLosses ?? 3;
  const active = stage.standings.filter((r) => r.w < W && r.l < L);
  if (active.length < 2) return false;
  const played = playedPairs(world, stage);
  const pools = new Map<string, StandingRow[]>();
  for (const r of active) {
    const k = `${r.w}-${r.l}`;
    if (!pools.has(k)) pools.set(k, []);
    pools.get(k)!.push(r);
  }
  const keys = [...pools.keys()].sort((a, b) => {
    const [aw, al] = a.split('-').map(Number);
    const [bw, bl] = b.split('-').map(Number);
    return bw - aw || al - bl;
  });
  const seedIdx = (id: string) => stage.teamIds.indexOf(id);
  const pairs: Array<[string, string]> = [];
  let carry: StandingRow[] = [];
  for (const k of keys) {
    const pool = [...carry, ...pools.get(k)!].sort((a, b) => b.gw - b.gl - (a.gw - a.gl) || seedIdx(a.teamId) - seedIdx(b.teamId));
    carry = [];
    while (pool.length >= 2) {
      const a = pool.shift()!;
      let j = pool.length - 1;
      while (j > 0 && played.has([a.teamId, pool[j].teamId].sort().join('|'))) j--;
      const b = pool.splice(j, 1)[0];
      pairs.push([a.teamId, b.teamId]);
    }
    if (pool.length) carry = pool;
  }
  if (!pairs.length) return false;
  const day = Math.max(stage.startDay + stage.swissRound * (stage.config.daysBetweenRounds ?? 1), world.day + 1);
  const fmtKey = stage.config.format ?? 'standard';
  for (const [a, b] of pairs) {
    const f = addFixture(world, {
      compId: comp.id,
      stageId: stage.id,
      titleId: comp.titleId,
      day,
      teams: [a, b],
      format: formatFor(world, comp, fmtKey),
      formatKey: fmtKey,
      status: 'scheduled',
      round: stage.swissRound,
      detail: isDetail(world, comp, [a, b]),
    });
    stage.fixtureIds.push(f.id);
  }
  stage.swissRound++;
  return true;
}

// ---------------------------------------------------------------------------------------------
// Battle royale points stage
// ---------------------------------------------------------------------------------------------

function scheduleBr(world: World, comp: Competition, stage: Stage): void {
  const n = stage.config.matchdays ?? 1;
  const days = matchDays(stage.startDay, n, stage.config.matchDaysOfWeek ?? [6]);
  const fmtKey = stage.config.format ?? 'standard';
  const base = formatFor(world, comp, fmtKey);
  for (let i = 0; i < n; i++) {
    const format: SeriesFormat = { ...base, n: stage.config.gamesPerDay ?? base.n, matchPoint: stage.config.matchPoint ?? undefined };
    if (!stage.config.matchPoint) delete format.matchPoint;
    const f = addFixture(world, {
      compId: comp.id,
      stageId: stage.id,
      titleId: comp.titleId,
      day: days[i],
      teams: [...stage.teamIds],
      format,
      formatKey: fmtKey,
      status: 'scheduled',
      round: i,
      detail: isDetail(world, comp, stage.teamIds),
    });
    stage.fixtureIds.push(f.id);
  }
}

// ---------------------------------------------------------------------------------------------
// Stage lifecycle
// ---------------------------------------------------------------------------------------------

export function startStage(world: World, comp: Competition, stage: Stage, teamIds: string[]): void {
  stage.teamIds = [...teamIds];
  stage.status = 'active';
  stage.startDay = Math.max(stage.startDay, world.day + 1);
  stage.standings = teamIds.map((t) => emptyRow(t));
  stage.fixtureIds = [];
  stage.bracket = [];
  stage.swissRound = 0;
  switch (stage.type) {
    case 'round-robin':
      scheduleRoundRobin(world, comp, stage, false);
      break;
    case 'double-round-robin':
      scheduleRoundRobin(world, comp, stage, true);
      break;
    case 'groups':
      scheduleGroups(world, comp, stage);
      break;
    case 'swiss':
      scheduleSwissRound(world, comp, stage);
      break;
    case 'single-elim':
      stage.bracket = buildSingleElim(teamIds.length);
      progressBracket(world, comp, stage);
      break;
    case 'double-elim':
      stage.bracket = buildDoubleElim(teamIds.length);
      progressBracket(world, comp, stage);
      break;
    case 'page-playoff':
      stage.bracket = buildPagePlayoff();
      progressBracket(world, comp, stage);
      break;
    case 'br-points':
      scheduleBr(world, comp, stage);
      break;
  }
}

export function sortStandings(world: World, rows: StandingRow[], br: boolean): StandingRow[] {
  const elo = (id: string) => world.teams[id]?.elo ?? 0;
  return [...rows].sort((a, b) => {
    if (br) return b.pts - a.pts || (b.wins ?? 0) - (a.wins ?? 0) || (b.kills ?? 0) - (a.kills ?? 0) || elo(b.teamId) - elo(a.teamId);
    return b.w - a.w || b.gw - b.gl - (a.gw - a.gl) || b.gw - a.gw || a.l - b.l || elo(b.teamId) - elo(a.teamId) || (a.teamId < b.teamId ? -1 : 1);
  });
}

/** Apply a finished fixture to the stage standings / bracket. */
export function applyFixtureToStage(world: World, comp: Competition, stage: Stage, f: Fixture): void {
  if (f.format.kind === 'br') {
    for (const tid of f.teams) {
      const row = stage.standings.find((r) => r.teamId === tid);
      if (!row) continue;
      row.pts += f.brPoints?.[tid] ?? 0;
      row.kills = (row.kills ?? 0) + (f.brKills?.[tid] ?? 0);
      row.wins = (row.wins ?? 0) + (f.brWins?.[tid] ?? 0);
    }
    if (f.winner && stage.config.matchPoint) stage.brMatchPointWinner = f.winner;
    return;
  }
  const [a, b] = f.teams;
  const [sa, sb] = f.score ?? [0, 0];
  const ra = stage.standings.find((r) => r.teamId === a);
  const rb = stage.standings.find((r) => r.teamId === b);
  if (ra && rb && stage.type !== 'single-elim' && stage.type !== 'double-elim' && stage.type !== 'page-playoff') {
    ra.gw += sa;
    ra.gl += sb;
    rb.gw += sb;
    rb.gl += sa;
    if (f.winner === a) {
      ra.w++;
      rb.l++;
      ra.pts += 3;
    } else {
      rb.w++;
      ra.l++;
      rb.pts += 3;
    }
    ra.diff = ra.gw - ra.gl;
    rb.diff = rb.gw - rb.gl;
  }
  if (f.nodeId) {
    const nd = stage.bracket.find((n) => n.id === f.nodeId);
    if (nd) {
      nd.winner = f.winner ?? null;
      nd.loser = f.teams.find((t) => t !== f.winner) ?? null;
    }
  }
}

/** Is the stage complete? Generates follow-up rounds (bracket/swiss) when possible. */
export function checkStage(world: World, comp: Competition, stage: Stage): boolean {
  const pending = stage.fixtureIds.some((id) => world.fixtures[id]?.status !== 'done');
  if (stage.type === 'single-elim' || stage.type === 'double-elim' || stage.type === 'page-playoff') {
    progressBracket(world, comp, stage);
    const fin = stage.bracket.find((n) => n.isFinal);
    return !!fin && (fin.winner !== null || (fin.teamA === null && fin.teamB === null)) && !stage.fixtureIds.some((id) => world.fixtures[id]?.status !== 'done');
  }
  if (pending) return false;
  if (stage.type === 'swiss') {
    if (scheduleSwissRound(world, comp, stage)) return false;
    return true;
  }
  return true;
}

/** Final ordering of teams in a finished stage. */
export function stagePlacements(world: World, stage: Stage): string[] {
  const br = stage.type === 'br-points';
  if (stage.type === 'single-elim' || stage.type === 'double-elim' || stage.type === 'page-playoff') {
    const place = new Map<string, number>();
    for (const nd of stage.bracket) {
      if (nd.loser) place.set(nd.loser, nd.elimPlace || 99);
      if (nd.isFinal && nd.winner) place.set(nd.winner, 1);
    }
    return [...stage.teamIds].sort((a, b) => (place.get(a) ?? 99) - (place.get(b) ?? 99) || stage.teamIds.indexOf(a) - stage.teamIds.indexOf(b));
  }
  if (stage.type === 'groups') {
    const groups = new Map<number, StandingRow[]>();
    for (const r of stage.standings) {
      const g = r.group ?? 0;
      if (!groups.has(g)) groups.set(g, []);
      groups.get(g)!.push(r);
    }
    const sortedGroups = [...groups.values()].map((rows) => sortStandings(world, rows, false));
    const maxLen = Math.max(...sortedGroups.map((g) => g.length));
    const out: string[] = [];
    for (let rank = 0; rank < maxLen; rank++) {
      const tier = sortedGroups.map((g) => g[rank]).filter(Boolean) as StandingRow[];
      out.push(...sortStandings(world, tier, false).map((r) => r.teamId));
    }
    return out;
  }
  if (stage.type === 'swiss') {
    return [...stage.standings].sort((a, b) => b.w - a.w || a.l - b.l || b.gw - b.gl - (a.gw - a.gl) || stage.teamIds.indexOf(a.teamId) - stage.teamIds.indexOf(b.teamId)).map((r) => r.teamId);
  }
  const order = sortStandings(world, stage.standings, br).map((r) => r.teamId);
  if (br && stage.brMatchPointWinner) return [stage.brMatchPointWinner, ...order.filter((t) => t !== stage.brMatchPointWinner)];
  return order;
}

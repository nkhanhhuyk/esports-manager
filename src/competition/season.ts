/**
 * Season planner: builds every title's competitive calendar on the shared world calendar.
 *
 * Template per title (dates shift slightly per title so schedules interleave):
 *   Split 1 regular season (Jan) -> Split 1 playoffs (Mar)
 *   Mid-Season International (late Apr/May)
 *   Split 2 regular season (Jun) -> Split 2 playoffs (Aug)
 *   World Championship (late Sep - Oct)
 *   Season rollover (late Nov)
 * All stage types / sizes are data and can be edited per competition.
 */
import { dayFromYMD } from '../core/date';
import { getTitle } from '../data/titles/catalogue';
import { regionShort } from '../data/countries';
import type { Competition, CompTier, EntrantRule, League, Stage, StageConfig, StageType, World } from '../model/types';
import { nextId } from '../model/ids';
import { matchDays } from './stages';
import type { TitleDef } from '../data/titles/types';

export const SEASON_END = { m: 11, d: 22 };

export function seasonEndDay(season: number): number {
  return dayFromYMD(season, SEASON_END.m, SEASON_END.d);
}

const SPLIT_POINTS = [90, 70, 50, 40, 30, 30, 10, 10, 5, 5, 5, 5];
const MSI_POINTS = [100, 80, 60, 60, 40, 40, 40, 40, 20, 20, 20, 20, 10, 10, 10, 10];

function stage(name: string, type: StageType, startDay: number, config: StageConfig = {}): Stage {
  return { id: `${type}-${Math.random().toString(36).slice(2, 7)}`, name, type, config, startDay, teamIds: [], standings: [], fixtureIds: [], bracket: [], swissRound: 0, status: 'pending', placements: [] };
}

function mkComp(world: World, t: TitleDef, season: number, name: string, short: string, tier: CompTier, region: string | null, league: League | null, entrants: EntrantRule[], stages: Stage[], prize: number, prestige: number, circuit: number[], split?: number): Competition {
  // Stage ids must be deterministic for reproducible saves.
  const id = nextId(world, 'k');
  stages.forEach((s, i) => (s.id = `${id}-s${i}`));
  const comp: Competition = {
    id,
    name,
    short,
    titleId: t.id,
    season,
    tier,
    region,
    leagueId: league?.id ?? null,
    entrants,
    teamIds: [],
    stages,
    current: 0,
    status: 'pending',
    startDay: stages[0].startDay,
    prizePool: Math.round(prize / 1000) * 1000,
    prizeSplit: [],
    circuitPoints: circuit,
    placements: [],
    fullDetail: league?.fullDetail ?? false,
    prestige,
    split,
    pstats: {},
  };
  world.comps[id] = comp;
  return comp;
}

function regularStage(t: TitleDef, n: number, start: number): { st: Stage; end: number } {
  if (t.engine === 'br') {
    const days = matchDays(start, 6, [6]);
    return { st: stage('Regular Season', 'br-points', start, { matchdays: 6, format: 'standard', matchDaysOfWeek: [6] }), end: days[days.length - 1] };
  }
  const double = n <= 10;
  const rounds = (n % 2 === 0 ? n - 1 : n) * (double ? 2 : 1);
  const days = matchDays(start, rounds, [5, 6]);
  return { st: stage('Regular Season', double ? 'double-round-robin' : 'round-robin', start, { format: 'standard', matchDaysOfWeek: [5, 6] }), end: days[days.length - 1] };
}

function playoffStage(t: TitleDef, n: number, start: number, tier: 1 | 2): { st: Stage; take: number } {
  if (t.engine === 'br') {
    const take = Math.max(8, Math.round(n * 0.75));
    return { st: stage('Finals', 'br-points', start, { matchdays: 1, format: 'final', matchDaysOfWeek: [0, 1, 2, 3, 4, 5, 6], matchPoint: t.br?.matchPointFinal }), take };
  }
  if (tier === 2 || n < 8) return { st: stage('Playoffs', 'single-elim', start, { daysBetweenRounds: 3 }), take: 4 };
  if (t.engine === 'brawl') return { st: stage('Playoffs', 'page-playoff', start, { daysBetweenRounds: 3 }), take: 4 };
  if (t.family === 'SHOOTER') return { st: stage('Playoffs', 'double-elim', start, { daysBetweenRounds: 2 }), take: 8 };
  return { st: stage('Playoffs', 'single-elim', start, { daysBetweenRounds: 3 }), take: 6 };
}

/** International event stages for n entrants. */
function internationalStages(t: TitleDef, n: number, start: number, worlds: boolean): Stage[] {
  if (t.engine === 'br') {
    return [
      stage(worlds ? 'Group Stage' : 'Opening Stage', 'br-points', start, { matchdays: worlds ? 3 : 2, format: 'standard', matchDaysOfWeek: [0, 1, 2, 3, 4, 5, 6], advance: Math.max(8, Math.min(n, t.br!.lobby)) }),
      stage('Grand Finals', 'br-points', start + (worlds ? 6 : 4), { matchdays: 1, format: 'final', matchDaysOfWeek: [0, 1, 2, 3, 4, 5, 6], matchPoint: t.br?.matchPointFinal }),
    ];
  }
  if (n >= 16) {
    return [
      stage('Swiss Stage', 'swiss', start, { swissWins: 3, swissLosses: 3, daysBetweenRounds: 1, advance: 8 }),
      stage('Knockout Stage', 'single-elim', start + 9, { daysBetweenRounds: 3 }),
    ];
  }
  if (n >= 7) {
    const groups = Math.max(2, Math.round(n / 4));
    return [stage('Group Stage', 'groups', start, { groups, advance: 2 }), stage('Knockout Stage', 'single-elim', start + 6, { daysBetweenRounds: 3 })];
  }
  return [stage('Knockout Stage', 'single-elim', start, { daysBetweenRounds: 3 })];
}

function leagueStrength(world: World, l: League): number {
  const pop = world.titles[l.titleId].popularity;
  if (pop[l.region] !== undefined) return pop[l.region];
  const parts = l.region.split('-')[1]?.split('.') ?? [];
  return parts.length ? (Math.max(...parts.map((r) => pop[r] ?? 0)) * 0.85) : 30;
}

/** Distribute `total` slots between leagues proportionally to strength (min 1, max 4). */
export function allocateSlots(strengths: number[], total: number, maxPer = 4): number[] {
  const n = strengths.length;
  if (!n) return [];
  const slots = new Array(n).fill(1);
  let left = total - n;
  const w = strengths.map((s) => Math.pow(Math.max(1, s), 1.5));
  while (left > 0) {
    let best = -1;
    let bestScore = -1;
    for (let i = 0; i < n; i++) {
      if (slots[i] >= maxPer) continue;
      const score = w[i] / (slots[i] + 0.5);
      if (score > bestScore) {
        bestScore = score;
        best = i;
      }
    }
    if (best < 0) break;
    slots[best]++;
    left--;
  }
  return slots;
}

export function planSeason(world: World, season: number): void {
  const titleIds = world.meta.titlesEnabled.filter((id) => world.titles[id]?.active !== false);
  planNationsCup(world, season);
  titleIds.forEach((titleId, ti) => {
    const t = getTitle(titleId);
    const off = ti % 3;
    const D = (m: number, d: number) => dayFromYMD(season, m, d) + off;
    const tier1 = Object.values(world.leagues)
      .filter((l) => l.titleId === titleId && l.tier === 1)
      .sort((a, b) => leagueStrength(world, b) - leagueStrength(world, a));
    const tier2 = Object.values(world.leagues).filter((l) => l.titleId === titleId && l.tier === 2);
    const splitComps: Record<string, Competition[]> = {};
    for (const l of [...tier1, ...tier2]) {
      const strength = leagueStrength(world, l);
      const prizeBase = t.prizeScale * 1_000_000 * (l.tier === 1 ? 0.08 : 0.012) * (0.4 + strength / 100);
      splitComps[l.id] = [];
      for (const split of [1, 2]) {
        const start = split === 1 ? D(1, 16) : D(6, 5);
        const reg = regularStage(t, l.size, start);
        const po = playoffStage(t, l.size, reg.end + 5, l.tier);
        reg.st.config.advance = Math.min(po.take, l.size);
        const comp = mkComp(
          world,
          t,
          season,
          `${l.name} ${season} Split ${split}`,
          `${l.short} S${split}`,
          l.tier === 1 ? 'league' : 'challenger',
          l.region,
          l,
          [{ kind: 'league', leagueId: l.id }],
          [reg.st, po.st],
          prizeBase,
          l.tier === 1 ? Math.round(25 + strength * 0.3) : 12,
          l.tier === 1 ? SPLIT_POINTS : [],
          split,
        );
        splitComps[l.id].push(comp);
      }
    }
    if (!tier1.length) return;
    // Mid-Season International
    const msiEntrants: EntrantRule[] = [];
    tier1.forEach((l, i) => {
      const count = tier1.length <= 2 ? 2 : i < 2 ? 2 : 1;
      msiEntrants.push({ kind: 'top', compId: splitComps[l.id][0].id, count });
    });
    let msiN = msiEntrants.reduce((a, e) => a + (e.kind === 'top' ? e.count : 0), 0);
    if (t.engine === 'br') {
      // BR internationals fill a lobby.
      const lobby = Math.min(t.br!.lobby, tier1.reduce((a, l) => a + l.size, 0));
      const slots = allocateSlots(tier1.map((l) => leagueStrength(world, l)), lobby, Math.ceil(lobby / Math.max(1, tier1.length - 0.5)));
      msiEntrants.length = 0;
      tier1.forEach((l, i) => msiEntrants.push({ kind: 'top', compId: splitComps[l.id][0].id, count: Math.min(slots[i], l.size) }));
      msiN = msiEntrants.reduce((a, e) => a + (e.kind === 'top' ? e.count : 0), 0);
    }
    mkComp(world, t, season, `${t.short} Mid-Season Invitational ${season}`, `${t.short} MSI`, 'international', null, null, msiEntrants, internationalStages(t, msiN, D(4, 25), false), t.prizeScale * 400_000, 70, MSI_POINTS);

    // World Championship
    const totalLeagueTeams = tier1.reduce((a, l) => a + l.size, 0);
    let target: number;
    if (t.engine === 'br') target = Math.min(t.br!.lobby, totalLeagueTeams);
    else target = Math.max(4, Math.min(16, Math.floor((tier1.length * 4) / 4) * 4));
    const slots = allocateSlots(tier1.map((l) => leagueStrength(world, l)), target, t.engine === 'br' ? Math.ceil(target / Math.max(1, tier1.length - 0.5)) : 4);
    const wEntrants: EntrantRule[] = tier1.map((l, i) => ({ kind: 'circuit', leagueId: l.id, count: Math.min(slots[i], l.size) }));
    const wN = wEntrants.reduce((a, e) => a + (e.kind === 'circuit' ? e.count : 0), 0);
    mkComp(world, t, season, `${t.short} World Championship ${season}`, `${t.short} Worlds`, 'worlds', null, null, wEntrants, internationalStages(t, wN, D(9, 22), true), t.prizeScale * 1_000_000, 100, []);
  });
}

/**
 * Biennial Nations Cup (odd seasons) for the most popular non-BR titles, played in the December
 * off-season before the season's competitive calendar.
 */
export function planNationsCup(world: World, season: number): void {
  if (season % 2 !== 1) return;
  const titles = world.meta.titlesEnabled
    .filter((id) => getTitle(id).engine !== 'br' && world.titles[id]?.active !== false)
    .sort((a, b) => (world.titles[b]?.globalPop ?? 0) - (world.titles[a]?.globalPop ?? 0))
    .slice(0, 6);
  titles.forEach((titleId, i) => {
    const t = getTitle(titleId);
    const start = dayFromYMD(season - 1, 12, 3) + (i % 3);
    mkComp(world, t, season, `${t.short} Nations Cup ${season}`, `${t.short} NC`, 'cup', null, null, [{ kind: 'nations', titleId, count: 8 }], [stage('Group Stage', 'groups', start, { groups: 2, advance: 2 }), stage('Knockout Stage', 'single-elim', start + 6, { daysBetweenRounds: 2 })], t.prizeScale * 150_000, 45, []);
  });
}

export function regionLabel(region: string | null): string {
  return region ? regionShort(region) : 'International';
}

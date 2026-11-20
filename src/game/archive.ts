/**
 * Season archive: finished seasons' competitions, fixtures and match records (box scores included)
 * leave the live world at rollover and are kept in a separate store, loaded on demand when an old
 * edition or match is opened. The live world keeps a small index of every archived edition so the
 * competitions list, edition dropdowns and honours stay complete.
 *
 * The storage backend is pluggable: IndexedDB in the app (registered by src/persistence/db.ts in
 * both the UI thread and the simulation worker), an in-memory map elsewhere (tests, tools).
 */
import type { Competition, Fixture, MatchRecord, World } from '../model/types';

export interface SeasonArchive {
  season: number;
  comps: Competition[];
  fixtures: Fixture[];
  matches: MatchRecord[];
}

export interface ArchivedCompEntry {
  id: string;
  season: number;
  titleId: string;
  tier: Competition['tier'];
  name: string;
  short: string;
  region: string | null;
  leagueId: string | null;
  split?: number;
  startDay: number;
  champion: string | null;
  runnerUp: string | null;
  teams: number;
  prizePool: number;
}

type Sink = (careerId: string, a: SeasonArchive) => void;
type Source = (careerId: string, season: number) => Promise<SeasonArchive | undefined>;

const memory = new Map<string, SeasonArchive>();
const key = (careerId: string, season: number) => `${careerId}|${season}`;
let sink: Sink = (c, a) => void memory.set(key(c, a.season), a);
let source: Source = async (c, s) => memory.get(key(c, s));
const loaded = new Map<string, SeasonArchive>();

export function setArchiveBackend(s: Sink, src: Source): void {
  sink = (c, a) => {
    memory.set(key(c, a.season), a); // also readable at once in this thread
    s(c, a);
  };
  source = async (c, season) => memory.get(key(c, season)) ?? (await src(c, season));
}

export function indexEntry(world: World, c: Competition): ArchivedCompEntry {
  return {
    id: c.id,
    season: c.season,
    titleId: c.titleId,
    tier: c.tier,
    name: c.name,
    short: c.short,
    region: c.region,
    leagueId: c.leagueId,
    split: c.split,
    startDay: c.startDay,
    champion: c.status === 'done' ? c.placements[0] ?? null : null,
    runnerUp: c.status === 'done' ? c.placements[1] ?? null : null,
    teams: c.teamIds.length,
    prizePool: c.prizePool,
  };
  void world;
}

/** Move one finished season out of the live world into the archive. */
export function archiveSeason(world: World, season: number): number {
  const comps = Object.values(world.comps).filter((c) => c.season === season);
  if (!comps.length) return 0;
  const fixtures: Fixture[] = [];
  const matches: MatchRecord[] = [];
  for (const c of comps) {
    for (const st of c.stages)
      for (const fid of st.fixtureIds) {
        const f = world.fixtures[fid];
        if (f) fixtures.push(f);
        const m = world.matches[fid];
        if (m) matches.push(m);
      }
  }
  sink(world.meta.saveId, { season, comps, fixtures, matches });
  world.compIndex ||= [];
  for (const c of comps) if (!world.compIndex.some((e) => e.id === c.id)) world.compIndex.push(indexEntry(world, c));
  for (const c of comps) {
    for (const st of c.stages)
      for (const fid of st.fixtureIds) {
        delete world.fixtures[fid];
        delete world.matches[fid];
      }
    delete world.comps[c.id];
  }
  return comps.length;
}

/** Load (and cache) an archived season for viewing. */
export async function loadArchive(world: World, season: number): Promise<SeasonArchive | undefined> {
  const k = key(world.meta.saveId, season);
  const hit = loaded.get(k);
  if (hit) return hit;
  const a = await source(world.meta.saveId, season);
  if (a) loaded.set(k, a);
  return a;
}

export function cachedArchive(world: World, season: number): SeasonArchive | undefined {
  return loaded.get(key(world.meta.saveId, season)) ?? memory.get(key(world.meta.saveId, season));
}

export function isArchived(world: World, compId: string): ArchivedCompEntry | undefined {
  return world.compIndex?.find((e) => e.id === compId);
}

// ---------------------------------------------------------------------------------------------
// Competition series (one entry per competition across seasons)
// ---------------------------------------------------------------------------------------------

type CompLike = { titleId: string; tier: string; name: string; leagueId: string | null };

/** Stable identity of a competition across editions: the league, or the event name without its year. */
export function seriesKey(c: CompLike): string {
  if (c.leagueId) return `L:${c.leagueId}:${c.tier}`;
  return `E:${c.titleId}:${c.tier}:${seriesName(c)}`;
}

export function seriesName(c: CompLike, world?: World): string {
  if (c.leagueId && world?.leagues[c.leagueId]) return world.leagues[c.leagueId].name;
  return c.name
    .replace(/\b(19|20)\d{2}\b/g, '')
    .replace(/\bSplit \d\b/g, '')
    .replace(/\s{2,}/g, ' ')
    .trim();
}

export interface Edition {
  id: string;
  season: number;
  split?: number;
  label: string;
  archived: boolean;
  champion: string | null;
  status: string;
}

/** All editions of the series a competition belongs to (live and archived), newest first. */
export function editionsOf(world: World, c: CompLike): Edition[] {
  const k = seriesKey(c);
  const out: Edition[] = [];
  for (const x of Object.values(world.comps))
    if (seriesKey(x) === k) out.push({ id: x.id, season: x.season, split: x.split, label: `${x.season}${x.split ? ` · Split ${x.split}` : ''}`, archived: false, champion: x.status === 'done' ? x.placements[0] ?? null : null, status: x.status });
  for (const e of world.compIndex ?? [])
    if (seriesKey(e) === k && !out.some((o) => o.id === e.id)) out.push({ id: e.id, season: e.season, split: e.split, label: `${e.season}${e.split ? ` · Split ${e.split}` : ''}`, archived: true, champion: e.champion, status: 'done' });
  return out.sort((a, b) => b.season - a.season || (b.split ?? 0) - (a.split ?? 0));
}

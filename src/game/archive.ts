/**
 * Finished seasons. At rollover a season's competitions (stages, standings, brackets, placements,
 * player stats) and fixtures (results) move from the live maps into `world.past`, which is part of
 * the save — old competitions open instantly, without any loading. Only the per-game box scores of
 * old seasons' matches go to a separate store (compressed, one record per season) and are loaded
 * when an old match report is opened.
 *
 * The match store backend is pluggable: IndexedDB in the app (registered by src/persistence/db.ts),
 * an in-memory map elsewhere (tests, tools).
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
/** Match records of old seasons loaded this session. */
const loadedMatches = new Map<string, MatchRecord[]>();

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

/** Move one finished season out of the live maps: results into `world.past`, box scores to the match store. */
export function archiveSeason(world: World, season: number): number {
  const comps = Object.values(world.comps).filter((c) => c.season === season);
  if (!comps.length) return 0;
  const fixtures: Fixture[] = [];
  const matches: MatchRecord[] = [];
  for (const c of comps) {
    for (const st of c.stages)
      for (const fid of st.fixtureIds) {
        const f = world.fixtures[fid];
        if (f) {
          // Results only: the fidelity flags are not needed once a fixture is played.
          const { detail: _d, spatial: _s, ...rest } = f;
          void _d;
          void _s;
          fixtures.push({ ...rest, detail: false } as Fixture);
        }
        const m = world.matches[fid];
        if (m) matches.push(m);
      }
  }
  world.past ||= {};
  world.past[season] = { comps, fixtures };
  sink(world.meta.saveId, { season, comps: [], fixtures: [], matches });
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

/** Old season with its box scores (loads the match store once per session). */
export async function loadArchive(world: World, season: number): Promise<SeasonArchive | undefined> {
  const k = key(world.meta.saveId, season);
  const past = world.past?.[season];
  if (!loadedMatches.has(k)) {
    const a = await source(world.meta.saveId, season);
    loadedMatches.set(k, a?.matches ?? []);
    // Saves from the build that kept whole seasons in the store: their results move into the save.
    if (!past && a?.comps.length) {
      world.past ||= {};
      world.past[season] = { comps: a.comps, fixtures: a.fixtures };
    }
  }
  const p = world.past?.[season];
  return p ? { season, comps: p.comps, fixtures: p.fixtures, matches: loadedMatches.get(k) ?? [] } : undefined;
}

/** Old season, synchronously: results always (they are in the save), box scores once loaded. */
export function cachedArchive(world: World, season: number): SeasonArchive | undefined {
  const p = world.past?.[season];
  if (!p) return undefined;
  const k = key(world.meta.saveId, season);
  return { season, comps: p.comps, fixtures: p.fixtures, matches: loadedMatches.get(k) ?? memory.get(k)?.matches ?? [] };
}

export function boxScoresLoaded(world: World, season: number): boolean {
  const k = key(world.meta.saveId, season);
  return loadedMatches.has(k) || memory.has(k);
}

/**
 * Saves from the build that kept whole seasons (results included) in the separate store: bring the
 * results into the save. Called once when a save loads.
 */
export async function absorbLegacyArchives(world: World): Promise<number> {
  let n = 0;
  const seasons = [...new Set((world.compIndex ?? []).map((e) => e.season))].filter((s) => !world.past?.[s]);
  for (const season of seasons) {
    const a = await source(world.meta.saveId, season);
    if (!a?.comps.length) continue;
    world.past ||= {};
    world.past[season] = { comps: a.comps, fixtures: a.fixtures };
    loadedMatches.set(key(world.meta.saveId, season), a.matches);
    sink(world.meta.saveId, { season, comps: [], fixtures: [], matches: a.matches });
    n++;
  }
  return n;
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

/**
 * Box-score pages. Background matches' per-game box scores leave the live world once a week and are
 * written as compressed pages (one per week) to a separate store; each fixture remembers its page
 * (`Fixture.pg`). Results, standings, player and competition stats stay in the world — only the
 * per-game box scores move — so saves stay about half the size during a season. A match report loads
 * its page on demand. Your own and followed matches (detailed records) stay in the world.
 *
 * Storage is pluggable like the season archive: IndexedDB in the app (src/persistence/db.ts), an
 * in-memory map in tests and tools.
 */
import type { MatchRecord, World } from '../model/types';

export interface BoxPage {
  season: number;
  page: number;
  matches: MatchRecord[];
}
type Sink = (key: string, p: BoxPage) => void;
type Source = (key: string) => Promise<BoxPage | undefined>;

const memory = new Map<string, BoxPage>();
let sink: Sink = (k, p) => void memory.set(k, p);
let source: Source = async (k) => memory.get(k);
let persistent = false;
/** Pages loaded this session (small cache: recent pages only). */
const cache = new Map<string, BoxPage>();

export function setBoxPageBackend(s: Sink, src: Source): void {
  persistent = true;
  sink = s;
  source = async (k) => memory.get(k) ?? (await src(k));
}

const pageKey = (careerId: string, season: number, page: number) => `${careerId}|${season}|p${page}`;

/** Weekly: page out the box scores of background matches played at least two days ago. */
export function pageOutMatches(world: World): number {
  const cutoff = world.day - 2;
  // Grouped by the season of the match's competition (late-season matches can be paged after rollover).
  const bySeason = new Map<number, MatchRecord[]>();
  let total = 0;
  let stale = false;
  for (const fid in world.matches) {
    const m = world.matches[fid];
    if (m.detail || m.day > cutoff) continue;
    const f = world.fixtures[fid];
    if (!f) continue;
    const season = world.comps[f.compId]?.season ?? world.season;
    (bySeason.get(season) ?? bySeason.set(season, []).get(season)!).push(m);
    total++;
    if (m.day < world.day - 7) stale = true;
  }
  // Small batches wait for more matches, but never longer than a week (off-season leftovers).
  if (total === 0 || (total < 20 && !stale)) return 0;
  world.boxPages ||= {};
  for (const [season, out] of bySeason) {
    const page = (world.boxPages[season] ?? 0) + 1;
    world.boxPages[season] = page;
    const key = pageKey(world.meta.saveId, season, page);
    sink(key, { season, page, matches: out });
    // Without a persistent store (tests, tools) the page stays in memory; in the app it is written
    // out and not kept in memory.
    if (persistent) memory.delete(key);
    for (const m of out) {
      world.fixtures[m.fixtureId].pg = page;
      delete world.matches[m.fixtureId];
    }
  }
  return total;
}

/** The stored record of a played fixture: in the world, or from its box-score page. */
export async function loadMatchRecord(world: World, fixtureId: string, season: number, page: number | undefined): Promise<MatchRecord | undefined> {
  const live = world.matches[fixtureId];
  if (live) return live;
  if (page === undefined) return undefined;
  const key = pageKey(world.meta.saveId, season, page);
  let bp = cache.get(key);
  if (!bp) {
    bp = await source(key);
    if (!bp) return undefined;
    cache.set(key, bp);
    while (cache.size > 3) cache.delete(cache.keys().next().value as string);
  }
  return bp.matches.find((m) => m.fixtureId === fixtureId);
}

export function cachedMatchRecord(world: World, fixtureId: string, season: number, page: number | undefined): MatchRecord | undefined {
  const live = world.matches[fixtureId];
  if (live || page === undefined) return live;
  return cache.get(pageKey(world.meta.saveId, season, page))?.matches.find((m) => m.fixtureId === fixtureId) ?? memory.get(pageKey(world.meta.saveId, season, page))?.matches.find((m) => m.fixtureId === fixtureId);
}

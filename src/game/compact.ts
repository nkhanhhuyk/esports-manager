/**
 * Keeps the live world (and therefore every save, autosave and worker hand-off) small.
 *
 * Runs on fixed simulation ticks (weekly / monthly) rather than at save time, so results never
 * depend on when or how often the player saves.
 */
import type { World } from '../model/types';

/** Detailed box scores of other clubs' matches are kept this long before being slimmed. */
export const DETAIL_KEEP_DAYS = 21;

const r2 = (v: number) => Math.round(v * 100) / 100;

function roundMap(m: Record<string, number> | undefined): void {
  if (!m) return;
  for (const k in m) {
    const v = m[k];
    if (typeof v === 'number' && !Number.isInteger(v)) m[k] = r2(v);
  }
}

/**
 * Weekly: slim detailed records (commentary, player lines, team stats, draft log) of matches that
 * did not involve the user, are not finals and are older than DETAIL_KEEP_DAYS. Results, scores,
 * maps and summaries stay; season statistics were already accumulated when the match was played.
 */
export function compactMatches(world: World): number {
  const uid = world.meta.userOrgId;
  const cutoff = world.day - DETAIL_KEEP_DAYS;
  let n = 0;
  for (const fid in world.matches) {
    const m = world.matches[fid];
    if (!m.detail || m.day > cutoff) continue;
    const f = world.fixtures[fid];
    if (f?.formatKey === 'final') continue;
    if (uid && m.teams.some((t) => world.teams[t]?.orgId === uid)) continue;
    world.matches[fid] = { ...m, detail: false, games: m.games.map((g) => ({ ...g, events: undefined, draft: undefined, players: [], teamStats: [] })) };
    n++;
  }
  return n;
}

/** Monthly: round proficiency values (accumulated as long floats) to two decimals. */
export function roundProficiencies(world: World): void {
  for (const id in world.players) {
    const p = world.players[id];
    if (p.retired) continue;
    const prof = p.prof;
    roundMap(prof.titles);
    roundMap(prof.roles);
    for (const nested of [prof.chars, prof.maps, prof.weapons]) if (nested) for (const t in nested) roundMap(nested[t]);
  }
}

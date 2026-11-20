/**
 * One-time conversion of older saves from generated (fictional) shooter maps to the real maps:
 * every map id stored in the world is remapped, deterministically and in catalogue order, to a real
 * map of the same title; patch pools become the real competitive pool. Results already recorded keep
 * their scores; they simply show the real map's name.
 */
import { generateMaps } from '../data/titles/generate';
import { getTitle } from '../data/titles/catalogue';
import type { World } from '../model/types';

const OLD: Record<string, { n: number; spread: number }> = { valorant: { n: 10, spread: 0.08 }, cs2: { n: 9, spread: 0.1 } };

export function ensureRealMaps(world: World): boolean {
  if (world.meta.realMaps) return false;
  let changed = false;
  for (const titleId of Object.keys(OLD)) {
    if (!world.titles[titleId]) continue;
    const t = getTitle(titleId);
    const real = t.maps.map((m) => m.id);
    // Same generator options as the old catalogue, so the ids match exactly.
    const old = generateMaps(titleId, OLD[titleId].n, { sideBiasSpread: OLD[titleId].spread, style: 'compound' }).map((m) => m.id);
    const remap = new Map(old.map((id, i) => [id, real[i % real.length]]));
    const r = (id: string) => remap.get(id) ?? id;
    const keys = <T,>(rec: Record<string, T> | undefined): Record<string, T> | undefined => {
      if (!rec) return rec;
      const out: Record<string, T> = {};
      for (const [k, v] of Object.entries(rec)) out[r(k)] = v;
      return out;
    };
    for (const p of world.titles[titleId].patches) p.mapPool = t.activePool?.length ? [...t.activePool] : p.mapPool.map(r);
    for (const p of Object.values(world.players)) if (p.prof.maps?.[titleId]) p.prof.maps[titleId] = keys(p.prof.maps[titleId])!;
    for (const tm of Object.values(world.teams)) if (tm.titleId === titleId) tm.prefs.mapPrefs = keys(tm.prefs.mapPrefs) ?? {};
    for (const m of Object.values(world.matches)) {
      if (m.titleId !== titleId) continue;
      for (const g of m.games) if (g.mapId) g.mapId = r(g.mapId);
      if (m.vetoes) for (const v of m.vetoes) v.mapId = r(v.mapId);
    }
    for (const o of Object.values(world.overrides)) {
      if (o.mapBans) o.mapBans = o.mapBans.map(r);
      if (o.mapPicks) o.mapPicks = o.mapPicks.map(r);
    }
    changed = true;
  }
  world.meta.realMaps = true;
  return changed;
}

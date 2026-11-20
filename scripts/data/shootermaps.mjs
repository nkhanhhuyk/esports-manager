/**
 * Valorant map snapshot (development-time only):
 *   node scripts/data/shootermaps.mjs → src/data/real/valorantMaps.json
 *
 * Source: valorant-api.com /v1/maps (community mirror of the game's map data): competitive maps, their
 * callouts (region / super-region names with in-game coordinates) and the minimap transform. Callouts are
 * transformed to minimap space, rotated so the attacker spawn is at the bottom, and fitted to 0..1000.
 * No map images are stored.
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { getJSON, today } from './fetch.mjs';

const j = getJSON('https://valorant-api.com/v1/maps');
const maps = [];
for (const m of j.data) {
  if (!m.tacticalDescription || !m.callouts?.length || !m.xMultiplier) continue;
  // valorant-api.com transform: minimap x = gameY·xMultiplier + xScalarToAdd, y = gameX·yMultiplier + yScalarToAdd
  const pts = m.callouts.map((c) => ({
    name: c.regionName,
    sup: c.superRegionName,
    x: c.location.y * m.xMultiplier + m.xScalarToAdd,
    y: c.location.x * m.yMultiplier + m.yScalarToAdd,
  }));
  const atk = pts.find((p) => /attacker/i.test(p.sup) && /spawn/i.test(p.name));
  const def = pts.find((p) => /defender/i.test(p.sup) && /spawn/i.test(p.name));
  if (!atk || !def) continue;
  // Rotate so the defender→attacker direction points down (+y).
  const ang = Math.atan2(atk.y - def.y, atk.x - def.x);
  const rot = Math.PI / 2 - ang;
  const cx = (atk.x + def.x) / 2;
  const cy = (atk.y + def.y) / 2;
  const R = pts.map((p) => {
    const dx = p.x - cx;
    const dy = p.y - cy;
    return { ...p, x: dx * Math.cos(rot) - dy * Math.sin(rot), y: dx * Math.sin(rot) + dy * Math.cos(rot) };
  });
  const xs = R.map((p) => p.x);
  const ys = R.map((p) => p.y);
  const minX = Math.min(...xs);
  const maxX = Math.max(...xs);
  const minY = Math.min(...ys);
  const maxY = Math.max(...ys);
  const span = Math.max(maxX - minX, maxY - minY);
  const s = 840 / span;
  const ox = 500 - ((minX + maxX) / 2) * s;
  const oy = 500 - ((minY + maxY) / 2) * s;
  maps.push({
    id: m.mapUrl.split('/').pop(),
    name: m.displayName,
    sites: m.tacticalDescription,
    uuid: m.uuid,
    callouts: R.map((p) => ({ name: p.name, sup: p.sup, x: Math.round(p.x * s + ox), y: Math.round(p.y * s + oy) })),
  });
}
mkdirSync('src/data/real', { recursive: true });
writeFileSync('src/data/real/valorantMaps.json', JSON.stringify({ snapshot_id: `real-${today()}`, as_of: today(), source: 'https://valorant-api.com/v1/maps', maps }));
console.log(maps.map((m) => `${m.name}(${m.callouts.length})`).join(' '));

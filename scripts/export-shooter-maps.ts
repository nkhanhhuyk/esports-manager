/**
 * Exports every tactical-shooter map (the competitive pools and the maps outside them) to the native app's
 * maps.json, keeping the MOBA maps and the existing shooter entries as they are:
 *
 *   npx esbuild scripts/export-shooter-maps.ts --bundle --platform=node --outfile=/tmp/esm.cjs && node /tmp/esm.cjs
 */
import fs from 'node:fs';
import { shooterMap, VALORANT_MAP_SPECS } from '../src/sim/shooter/maps';
import { CS2_MAPS } from '../src/sim/shooter/cs2Maps';

const file = 'native-android/app/src/main/assets/maps.json';
const cur = JSON.parse(fs.readFileSync(file, 'utf8'));
const r = (n: number) => Math.round(n);
let added = 0;
for (const spec of [...VALORANT_MAP_SPECS, ...CS2_MAPS]) {
  if (cur.shooter[spec.id]) continue; // pool maps: unchanged
  const m = shooterMap(spec.id);
  if (!m) continue;
  cur.shooter[spec.id] = {
    name: m.name,
    nodes: m.nodes.map((n) => ({ name: n.name, sup: n.sup, x: r(n.x), y: r(n.y), kind: n.kind, site: n.site ?? null })),
    edges: m.edges,
    sites: m.sites.map((s) => ({ id: s.id, node: s.node, x: r(s.x), y: r(s.y), r: r(s.r) })),
    atk: m.atkSpawn,
    def: m.defSpawn,
    walls: m.walls.map((w) => w.map(r)),
  };
  added++;
}
fs.writeFileSync(file, JSON.stringify(cur));
console.log('shooter maps', Object.keys(cur.shooter).length, 'added', added);

/**
 * Exports the web version's static game tables (countries, regions, name pools) as one JSON asset
 * for the native Android app, so the data has a single source:
 *
 *   npx esbuild scripts/native-export.ts --bundle --platform=node --outfile=/tmp/ne.cjs && node /tmp/ne.cjs
 *     → native-android/app/src/main/assets/gamedata.json
 */
import fs from 'node:fs';
import { COUNTRIES, MACROS, REGIONS } from '../src/data/countries';
import { mobaMap, MOBA_MAP_TITLES } from '../src/sim/moba/maps';
import { shooterMap } from '../src/sim/shooter/maps';
import { CS2_POOL } from '../src/sim/shooter/cs2Maps';
import { VALORANT_POOL } from '../src/sim/shooter/maps';
import { COMPANY_TYPES, COMPANY_WORDS, CULTURES, HANDLE_PREFIX, HANDLE_ROOTS, HANDLE_SUFFIX, ORG_SUFFIX, ORG_WORDS_A, ORG_WORDS_B } from '../src/data/names';

const out = {
  regions: REGIONS,
  macros: MACROS,
  countries: COUNTRIES,
  cultures: CULTURES,
  handles: { roots: HANDLE_ROOTS, prefix: HANDLE_PREFIX, suffix: HANDLE_SUFFIX },
  orgWords: { a: ORG_WORDS_A, b: ORG_WORDS_B, suffix: ORG_SUFFIX },
  company: { words: COMPANY_WORDS, types: COMPANY_TYPES },
};
fs.writeFileSync('native-android/app/src/main/assets/gamedata.json', JSON.stringify(out));

// Map geometry for the native 2D match view (coordinates 0-1000; team 0 / attackers' side as in the web engines).
const r = (n: number) => Math.round(n);
const pt = (p: { x: number; y: number }) => [r(p.x), r(p.y)];
const moba = Object.fromEntries(
  MOBA_MAP_TITLES.map((id) => {
    const m = mobaMap(id);
    return [id, {
      name: m.name,
      lanes: Object.fromEntries(Object.entries(m.lanes).map(([k, v]) => [k, v.map(pt)])),
      cores: m.cores.map(pt),
      fountains: m.fountains.map(pt),
      structures: m.structures.map((s) => ({ team: s.team, kind: s.kind, lane: s.lane, tier: s.tier, name: s.name, pos: pt(s.pos) })),
      camps: m.camps.map((c) => ({ team: c.team, kind: c.kind, name: c.name, pos: pt(c.pos) })),
      pits: m.pits.map((p) => ({ name: p.name, pos: pt(p.pos), objectives: p.objectives })),
      river: m.river.map(pt),
      walls: m.walls.map((w) => w.map(r)),
    }];
  }),
);
const shooter = Object.fromEntries(
  [...VALORANT_POOL, ...CS2_POOL].map((id) => {
    const m = shooterMap(id);
    if (!m) return [id, null];
    return [id, {
      name: m.name,
      nodes: m.nodes.map((n) => ({ name: n.name, sup: n.sup, x: r(n.x), y: r(n.y), kind: n.kind, site: n.site ?? null })),
      edges: m.edges,
      sites: m.sites.map((s) => ({ id: s.id, node: s.node, x: r(s.x), y: r(s.y), r: r(s.r) })),
      atk: m.atkSpawn,
      def: m.defSpawn,
      walls: m.walls.map((w) => w.map(r)),
    }];
  }),
);
fs.writeFileSync('native-android/app/src/main/assets/maps.json', JSON.stringify({ moba, shooter }));
console.log('maps', Object.keys(moba).length, Object.keys(shooter).length);
console.log('countries', COUNTRIES.length, 'cultures', Object.keys(CULTURES).length);

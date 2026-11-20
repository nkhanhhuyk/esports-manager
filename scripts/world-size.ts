/** Measure a world's JSON size by top-level key after N seasons (development check). */
import { createWorld } from '../src/gen/world';
import { advance } from '../src/game/advance';
import { cachedArchive } from '../src/game/archive';
const size = (process.argv[2] ?? 'standard') as 'small' | 'standard';
const seasons = Number(process.argv[3] ?? 1);
const t0 = Date.now();
const w = createWorld({ name: 'ws', seed: 'ws', worldSize: size, startDay: 20000, dataMode: 'real' });
console.log('created', Date.now() - t0, 'ms; players', Object.keys(w.players).length, 'teams', Object.keys(w.teams).length);
for (let s = 0; s < seasons; s++) {
  const t1 = Date.now();
  advance(w, { kind: 'end-season' }, { stopForUserMatch: false });
  console.log(`season ${s + 1}: ${((Date.now() - t1) / 1000).toFixed(1)} s`);
}
const total = JSON.stringify(w).length;
console.log('TOTAL', (total / 1e6).toFixed(1), 'MB');
const rows = Object.entries(w).map(([k, v]) => [k, JSON.stringify(v)?.length ?? 0] as const).sort((a, b) => b[1] - a[1]);
for (const [k, n] of rows.slice(0, 14)) console.log(k.padEnd(14), (n / 1e6).toFixed(2), 'MB');
const p = Object.values(w.players)[0];
const pk = Object.entries(p).map(([k, v]) => [k, JSON.stringify(v)?.length ?? 0] as const).sort((a, b) => b[1] - a[1]).slice(0, 8);
console.log('one player', JSON.stringify(p).length, pk);
const arch = cachedArchive(w, w.season - 1);
console.log('archived season', arch ? (JSON.stringify(arch).length / 1e6).toFixed(2) + ' MB' : 'none in cache');
{
  const ms = Object.values(w.matches);
  const parts: Record<string, number> = {};
  let detail = 0;
  for (const m of ms) {
    if (m.detail) detail++;
    for (const g of m.games) for (const [k, v] of Object.entries(g)) parts[k] = (parts[k] ?? 0) + (JSON.stringify(v)?.length ?? 0);
  }
  console.log('matches', ms.length, 'detail', detail, Object.entries(parts).sort((a, b) => b[1] - a[1]).map(([k, n]) => `${k} ${(n / 1e6).toFixed(2)}MB`).join(' | '));
}

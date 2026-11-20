/**
 * Where and how the losing side dies in spatial MOBA games (development check).
 *   npx vite-node scripts/kill-anatomy.ts <title> [games]
 * Splits the loser's deaths into: isolated (no ally within 160 units), shortly after respawning
 * (< 45 s alive), near their own base (within 260 of their fountain), in fights by minute bucket.
 */
import { createWorld } from '../src/gen/world';
import { createSeries, playNextGame } from '../src/sim/series';
import { mobaMap } from '../src/sim/moba/maps';
import type { Fixture } from '../src/model/types';

const id = process.argv[2] ?? 'lol';
const N = Number(process.argv[3] ?? 30);
const w = createWorld({ name: 'ka', seed: `kr-${id}`, worldSize: 'small', titles: [id], startDay: 20000, dataMode: 'real' });
const teams = Object.values(w.teams).filter((t) => t.titleId === id && t.tier === 'main' && t.leagueId && t.active);
const map = mobaMap(id);
let deaths = 0, isolated = 0, afterRespawn = 0, nearBase = 0, solo = 0;
const byMin: Record<string, number> = {};
const blow: string[] = [];
for (let i = 0; i < N; i++) {
  const a = teams[(i * 7) % teams.length];
  const b = teams[(i * 11 + 3) % teams.length];
  if (a === b) continue;
  const f = { id: `kr-${i}`, compId: Object.keys(w.comps)[0], titleId: id, teams: [a.id, b.id], day: w.day, status: 'scheduled', format: { n: 1, label: 'Bo1' }, formatKey: 'standard', detail: false, spatial: true } as unknown as Fixture;
  const st = createSeries(w, f);
  st.record = true;
  const g = playNextGame(w, f, st);
  const r = g.replay!;
  const loserSide = g.blue === 1 ? g.winner : 1 - g.winner;
  const lastRespawn = new Array(10).fill(0);
  let lk = 0, wk = 0;
  for (const e of r.events) {
    if (e.k === 'respawn') lastRespawn[e.a!] = e.t;
    if (e.k !== 'kill') continue;
    const v = e.b!;
    if (r.heroes[v].side !== loserSide) {
      wk++;
      continue;
    }
    lk++;
    deaths++;
    const fi = Math.min(r.frames - 1, Math.floor(e.t / r.dt));
    const vx = r.hx[fi * 10 + v], vy = r.hy[fi * 10 + v];
    let ally = false;
    for (let h = 0; h < 10; h++) if (h !== v && r.heroes[h].side === loserSide && !(r.hst[fi * 10 + h] & 1) && Math.hypot(r.hx[fi * 10 + h] - vx, r.hy[fi * 10 + h] - vy) < 160) ally = true;
    if (!ally) isolated++;
    if (lastRespawn[v] > 0 && e.t - lastRespawn[v] < 45) afterRespawn++;
    const fp = map.fountains[loserSide];
    if (Math.hypot(fp.x - vx, fp.y - vy) < 260) nearBase++;
    if (!(e.as ?? []).length) solo++;
    const m = `${Math.floor(e.t / 300) * 5}`;
    byMin[m] = (byMin[m] ?? 0) + 1;
  }
  if (lk - 0 > 0) blow.push(`${wk}-${lk}`);
}
console.log(`${id}: loser deaths ${deaths} | isolated ${((100 * isolated) / deaths).toFixed(0)}% | <45s after respawn ${((100 * afterRespawn) / deaths).toFixed(0)}% | near own base ${((100 * nearBase) / deaths).toFixed(0)}% | no assist ${((100 * solo) / deaths).toFixed(0)}%`);
console.log('loser deaths by 5-min bucket', byMin);
console.log('kills winner-loser', blow.join(' '));

// Fight trades: kills within 15 s of each other form one fight; how one-sided are fights?
{
  const w2 = createWorld({ name: 'kb', seed: `kr-${id}`, worldSize: 'small', titles: [id], startDay: 20000, dataMode: 'real' });
  const t2 = Object.values(w2.teams).filter((t) => t.titleId === id && t.tier === 'main' && t.leagueId && t.active);
  const hist: Record<string, number> = {};
  let fights = 0, clean = 0;
  for (let i = 0; i < 20; i++) {
    const a = t2[(i * 7) % t2.length], b = t2[(i * 11 + 3) % t2.length];
    if (a === b) continue;
    const f = { id: `kb-${i}`, compId: Object.keys(w2.comps)[0], titleId: id, teams: [a.id, b.id], day: w2.day, status: 'scheduled', format: { n: 1, label: 'Bo1' }, formatKey: 'standard', detail: false, spatial: true } as unknown as Fixture;
    const st = createSeries(w2, f);
    st.record = true;
    const g = playNextGame(w2, f, st);
    const ks = g.replay!.events.filter((e) => e.k === 'kill');
    let cur: number[] = [0, 0];
    let last = -99;
    const flush = () => {
      const n = cur[0] + cur[1];
      if (n >= 3) {
        fights++;
        const key = `${Math.max(...cur)}-${Math.min(...cur)}`;
        hist[key] = (hist[key] ?? 0) + 1;
        if (Math.min(...cur) === 0) clean++;
      }
      cur = [0, 0];
    };
    for (const e of ks) {
      if (e.t - last > 15) flush();
      cur[e.s ?? 0]++;
      last = e.t;
    }
    flush();
  }
  console.log(`fights (3+ kills) ${fights}, one-sided ${((100 * clean) / fights).toFixed(0)}%`, Object.entries(hist).sort((x, y) => y[1] - x[1]).slice(0, 10));
}

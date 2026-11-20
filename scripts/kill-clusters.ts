/** Development aid: how kills happen in spatial games — fight sizes, picks, lane kills, stomps. */
import { createWorld } from '../src/gen/world';
import { createSeries, playNextGame } from '../src/sim/series';
import type { Fixture } from '../src/model/types';
const id = process.argv[2] ?? 'lol';
const N = Number(process.argv[3] ?? 12);
const w = createWorld({ name: 'cal', seed: `cal-${id}`, worldSize: 'small', titles: [id], startDay: 20000 });
const teams = Object.values(w.teams).filter((t) => t.titleId === id && t.tier === 'main');
const sizes = new Map<number, number>();
let total = 0, games = 0, early = 0, margin = 0, dur = 0;
for (let i = 0; i < N; i++) {
  const f = { id: `kc-${i}`, compId: Object.keys(w.comps)[0], titleId: id, teams: [teams[i % teams.length].id, teams[(i + 3) % teams.length].id], day: w.day, status: 'scheduled', format: { n: 1, label: 'Bo1' }, formatKey: 'standard', detail: true, spatial: true } as unknown as Fixture;
  const st = createSeries(w, f, true);
  st.record = true;
  const r = playNextGame(w, f, st);
  games++;
  dur += r.duration;
  const kills = r.replay!.events.filter((e) => e.k === 'kill').map((e) => e.t);
  total += kills.length;
  margin += Math.abs(r.score[0] - r.score[1]);
  early += kills.filter((t) => t < 600).length;
  let c = 0, last = -999;
  for (const t of kills) {
    if (t - last > 15 && c) { sizes.set(c, (sizes.get(c) ?? 0) + 1); c = 0; }
    c++; last = t;
  }
  if (c) sizes.set(c, (sizes.get(c) ?? 0) + 1);
}
console.log(`${id}: ${(total / games).toFixed(1)} kills/game, ${(dur / games / 60).toFixed(1)} min, first 10 min ${(early / games).toFixed(1)}, avg kill margin ${(margin / games).toFixed(1)}`);
console.log('fight sizes (kills within 15 s):', [...sizes].sort((a, b) => a[0] - b[0]).map(([k, v]) => `${k}:${v}`).join(' '));

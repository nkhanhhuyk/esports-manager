/** Trace the losing side's deaths in one blowout game: what the victim was doing and who was near. */
import { createWorld } from '../src/gen/world';
import { createSeries, playNextGame } from '../src/sim/series';
import type { Fixture } from '../src/model/types';
const id = process.argv[2] ?? 'lol';
const want = Number(process.argv[3] ?? 30);
const w = createWorld({ name: 'ka', seed: `kr-${id}`, worldSize: 'small', titles: [id], startDay: 20000, dataMode: 'real' });
const teams = Object.values(w.teams).filter((t) => t.titleId === id && t.tier === 'main' && t.leagueId && t.active);
const ACT = ['', 'Lane', 'Jungle', 'Roam', 'Objective', 'Fight', 'Retreat', 'Recall', 'Siege', 'Defend', 'Group'];
for (let i = 0; i < 40; i++) {
  const a = teams[(i * 7) % teams.length];
  const b = teams[(i * 11 + 3) % teams.length];
  if (a === b) continue;
  const f = { id: `kr-${i}`, compId: Object.keys(w.comps)[0], titleId: id, teams: [a.id, b.id], day: w.day, status: 'scheduled', format: { n: 1, label: 'Bo1' }, formatKey: 'standard', detail: false, spatial: true } as unknown as Fixture;
  const st = createSeries(w, f);
  st.record = true;
  const g = playNextGame(w, f, st);
  const r = g.replay!;
  const loserSide = g.blue === 1 ? g.winner : 1 - g.winner;
  const lk = r.events.filter((e) => e.k === 'kill' && r.heroes[e.b!].side === loserSide).length;
  if (lk < want) continue;
  console.log(`game ${i}: ${a.name} vs ${b.name} winner ${g.winner} loser deaths ${lk}`);
  for (const e of r.events) {
    if (e.k !== 'kill' || r.heroes[e.b!].side !== loserSide) continue;
    const fi = Math.max(0, Math.floor(e.t / r.dt) - 2);
    const v = e.b!;
    const vx = r.hx[fi * 10 + v], vy = r.hy[fi * 10 + v];
    const near = (side: number, rad: number) => [...Array(10).keys()].filter((h) => r.heroes[h].side === side && h !== v && !(r.hst[fi * 10 + h] & 1) && Math.hypot(r.hx[fi * 10 + h] - vx, r.hy[fi * 10 + h] - vy) < rad).length;
    const act = ACT[(r.hst[fi * 10 + v] >> 1) & 15];
    const lv = r.hlv[fi * 10 + v];
    const klv = r.hlv[fi * 10 + e.a!];
    console.log(`${(e.t / 60).toFixed(1)}m ${r.heroes[v].roleId} ${act} hp${r.hhp[fi * 10 + v]} lv${lv} vs lv${klv} allies${near(loserSide, 200)} enemies${near(1 - loserSide, 200)} assists${(e.as ?? []).length} at ${vx},${vy}`);
  }
  break;
}

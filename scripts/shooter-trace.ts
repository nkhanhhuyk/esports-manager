import { createWorld } from '../src/gen/world';
import { createSeries, playNextGame } from '../src/sim/series';
import { shooterMap } from '../src/sim/shooter/maps';
import type { Fixture } from '../src/model/types';
const id = process.argv[2] ?? 'valorant';
const w = createWorld({ name: 'cal', seed: `cals-${id}`, worldSize: 'small', titles: [id], startDay: 20000 });
const teams = Object.values(w.teams).filter((t) => t.titleId === id && t.tier === 'main');
const mapId = process.argv[3] ?? w.titles[id].patches[0].mapPool[0];
const f = { id: `tr-1`, compId: Object.keys(w.comps)[0], titleId: id, teams: [teams[0].id, teams[3].id], day: w.day, status: 'scheduled', format: { n: 1, label: 'Bo1' }, formatKey: 'standard', detail: true, spatial: true } as unknown as Fixture;
const st = createSeries(w, f, true);
st.slots[0].mapId = mapId;
st.record = true;
const r = playNextGame(w, f, st).sreplay!;
const m = shooterMap(mapId)!;
const near = (x: number, y: number) => { let b = m.nodes[0], bd = 1e9; for (const n of m.nodes) { const d = (n.x - x) ** 2 + (n.y - y) ** 2; if (d < bd) { bd = d; b = n; } } return `${b.sup}/${b.name}`; };
for (const rd of r.rounds.slice(0, 6)) {
  const t0 = rd.from * r.dt, t1 = rd.to * r.dt + r.dt;
  console.log(`--- round ${rd.idx + 1} attack=${rd.attack} winner=${rd.winner} ${rd.reason} buys ${rd.buys}`);
  for (const e of r.events.filter((e) => e.t >= t0 && e.t <= t1 && e.k !== 'round')) {
    const fr = Math.round(e.t / r.dt);
    const role = (h?: number) => (h === undefined ? '' : `${r.players[h].side === rd.attack ? 'ATK' : 'DEF'}:${r.players[h].handle}@${near(r.px[fr * 10 + h], r.py[fr * 10 + h])}${r.pst[fr * 10 + h] & 16 ? '(moving)' : ''}`);
    console.log(`  ${(e.t - t0).toFixed(1)}s ${e.k} ${role(e.a)} -> ${role(e.b)} ${e.n ?? ''}`);
  }
}

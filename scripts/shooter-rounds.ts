import { createWorld } from '../src/gen/world';
import { createSeries, playNextGame } from '../src/sim/series';
import type { Fixture } from '../src/model/types';
const id = process.argv[2] ?? 'valorant';
const w = createWorld({ name: 'cal', seed: `cals-${id}`, worldSize: 'small', titles: [id], startDay: 20000 });
const teams = Object.values(w.teams).filter((t) => t.titleId === id && t.tier === 'main');
const pool = w.titles[id].patches[0].mapPool;
let planted = 0, plantedAtk = 0, unplanted = 0, unplantedAtk = 0, firstKillAtk = 0, firstKillWin = 0, rounds = 0;
const aliveAtPlant: number[][] = [];
for (let i = 0; i < 10; i++) {
  const f = { id: `sr-${i}`, compId: Object.keys(w.comps)[0], titleId: id, teams: [teams[i % teams.length].id, teams[(i + 3) % teams.length].id], day: w.day, status: 'scheduled', format: { n: 1, label: 'Bo1' }, formatKey: 'standard', detail: true, spatial: true } as unknown as Fixture;
  const st = createSeries(w, f, true);
  st.slots[0].mapId = pool[i % pool.length];
  st.record = true;
  const r = playNextGame(w, f, st).sreplay!;
  for (const rd of r.rounds) {
    rounds++;
    const t0 = rd.from * r.dt, t1 = rd.to * r.dt + r.dt;
    const evs = r.events.filter((e) => e.t >= t0 - 0.01 && e.t <= t1 + 0.01);
    const plant = evs.find((e) => e.k === 'plant');
    const fk = evs.find((e) => e.k === 'kill');
    if (fk) { if (fk.s === rd.attack) firstKillAtk++; if (fk.s === rd.winner) firstKillWin++; }
    if (plant) {
      planted++;
      if (rd.winner === rd.attack) plantedAtk++;
      const fr = Math.round((plant.t) / r.dt);
      let a = 0, d = 0;
      for (let h = 0; h < 10; h++) if (r.php[fr * 10 + h] > 0) (r.players[h].side === rd.attack ? a++ : d++);
      aliveAtPlant.push([a, d]);
    } else { unplanted++; if (rd.winner === rd.attack) unplantedAtk++; }
  }
}
const avg = (k: number) => (aliveAtPlant.reduce((x, y) => x + y[k], 0) / Math.max(1, aliveAtPlant.length)).toFixed(1);
console.log(`${id}: rounds ${rounds}; planted ${planted} (attack wins ${(100 * plantedAtk / planted).toFixed(0)}%), not planted ${unplanted} (attack wins ${(100 * unplantedAtk / unplanted).toFixed(0)}%); first kill by attack ${(100 * firstKillAtk / rounds).toFixed(0)}%, first-kill team wins ${(100 * firstKillWin / rounds).toFixed(0)}%; alive at plant atk ${avg(0)} def ${avg(1)}`);

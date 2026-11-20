/** Calibration of the spatial shooter engine: map scores, rounds, kills, plants, side balance, speed. */
import { createWorld } from '../src/gen/world';
import { createSeries, playNextGame } from '../src/sim/series';
import { computeTeamStrength } from '../src/sim/common/team';
import type { Fixture } from '../src/model/types';
for (const id of (process.argv[2] ?? 'valorant,cs2').split(',')) {
  const N = Number(process.argv[3] ?? 16);
  const w = createWorld({ name: 'cal', seed: `cals-${id}`, worldSize: 'small', titles: [id], startDay: 20000 });
  const teams = Object.values(w.teams).filter((t) => t.titleId === id && t.tier === 'main');
  let rounds = 0, kills = 0, plants = 0, defuses = 0, ms = 0, fav = 0, ot = 0, maps = 0, atkWins = 0, totR = 0;
  const reasons = new Map<string, number>();
  const pool = w.titles[id].patches[0].mapPool;
  for (let i = 0; i < N; i++) {
    const a = teams[i % teams.length], b = teams[(i + 3) % teams.length];
    const f = { id: `cs-${i}`, compId: Object.keys(w.comps)[0], titleId: id, teams: [a.id, b.id], day: w.day, status: 'scheduled', format: { n: 1, label: 'Bo1' }, formatKey: 'standard', detail: true, spatial: true } as unknown as Fixture;
    const st = createSeries(w, f, true);
    st.slots[0].mapId = pool[i % pool.length];
    st.record = true;
    const t0 = performance.now();
    const r = playNextGame(w, f, st);
    ms += performance.now() - t0;
    maps++;
    rounds += r.score[0] + r.score[1];
    kills += r.players.reduce((x, l) => x + (l.s.k ?? 0), 0);
    plants += r.players.reduce((x, l) => x + (l.s.plant ?? 0), 0);
    defuses += r.players.reduce((x, l) => x + (l.s.defuse ?? 0), 0);
    if (r.summary.includes('OT')) ot++;
    for (const rd of r.sreplay!.rounds) {
      reasons.set(rd.reason, (reasons.get(rd.reason) ?? 0) + 1);
      totR++;
      if (rd.winner === rd.attack) atkWins++;
    }
    if ((computeTeamStrength(w, a) > computeTeamStrength(w, b)) === (r.winner === 0)) fav++;
    if (i < 3) console.log(`  ${r.summary}`);
  }
  console.log(`${id}: ${maps} maps | ${(rounds / maps).toFixed(1)} rounds | kills/round ${(kills / rounds).toFixed(2)} | plants/round ${(plants / rounds).toFixed(2)} | defuses ${(defuses / rounds).toFixed(2)} | attack win ${(100 * atkWins / totR).toFixed(0)}% | OT ${ot} | favourite ${(100 * fav / maps).toFixed(0)}% | ${(ms / maps).toFixed(0)} ms/map`);
  console.log('  round ends:', [...reasons].map(([k, v]) => `${k} ${(100 * v / totR).toFixed(0)}%`).join(', '));
}

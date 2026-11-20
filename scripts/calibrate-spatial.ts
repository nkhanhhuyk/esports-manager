/** Calibration report for the spatial MOBA engine: lengths, kills, structures, objectives, speed, upsets. */
import { createWorld } from '../src/gen/world';
import { createSeries, playNextGame } from '../src/sim/series';
import { computeTeamStrength } from '../src/sim/common/team';
import type { Fixture } from '../src/model/types';
const titles = (process.argv[2] ?? 'mlbb,wildrift,hok,lol,dota2').split(',');
const N = Number(process.argv[3] ?? 40);
for (const id of titles) {
  const w = createWorld({ name: 'cal', seed: `cal-${id}`, worldSize: 'small', titles: [id], startDay: 20000 });
  const teams = Object.values(w.teams).filter((t) => t.titleId === id && t.tier === 'main');
  let dur = 0, k = 0, st = 0, ob = 0, favWins = 0, games = 0, ms = 0, maxDur = 0, minDur = 1e9, gold = 0;
  const sideWins = [0, 0];
  for (let i = 0; i < N; i++) {
    const a = teams[(i * 7) % teams.length];
    const b = teams[(i * 7 + 3 + (i % 5)) % teams.length];
    if (a === b) continue;
    const f = { id: `cal-${i}`, compId: Object.keys(w.comps)[0], titleId: id, teams: [a.id, b.id], day: w.day, status: 'scheduled', format: { n: 1, label: 'Bo1' }, formatKey: 'standard', detail: false } as unknown as Fixture;
    const s = createSeries(w, f);
    const t0 = performance.now();
    const r = playNextGame(w, f, s);
    ms += performance.now() - t0;
    games++;
    dur += r.duration; maxDur = Math.max(maxDur, r.duration); minDur = Math.min(minDur, r.duration);
    k += r.score[0] + r.score[1];
    st += (r.teamStats[0].structures ?? 0) + (r.teamStats[1].structures ?? 0);
    ob += (r.teamStats[0].objectives ?? 0) + (r.teamStats[1].objectives ?? 0);
    gold += (r.teamStats[0].gold ?? 0) + (r.teamStats[1].gold ?? 0);
    sideWins[r.winner]++;
    const sa = computeTeamStrength(w, a), sb = computeTeamStrength(w, b);
    if ((sa > sb) === (r.winner === 0)) favWins++;
  }
  console.log(`${id.padEnd(9)} games ${games} | avg ${(dur / games / 60).toFixed(1)} min (${(minDur / 60).toFixed(1)}–${(maxDur / 60).toFixed(1)}) | kills ${(k / games).toFixed(1)} | structures ${(st / games).toFixed(1)} | objectives ${(ob / games).toFixed(1)} | gold/team/min ${(gold / 2 / (dur / 60)).toFixed(0)} | favourite wins ${((100 * favWins) / games).toFixed(0)}% | side0 ${sideWins[0]} | ${(ms / games).toFixed(1)} ms/game`);
}

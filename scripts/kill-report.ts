/**
 * Kill-distribution report for both MOBA engines (development check against pro ranges).
 *   npx vite-node scripts/kill-report.ts [titles] [games] [spatial|fast] [real|fictional]
 * Prints per title: total kills (mean, p90, max), winner–loser kill gap (mean, p90, max),
 * the biggest single-team kill count, deaths of the worst player (mean, p90, max) and how often a
 * losing team has 2+ players with ≥ 7 deaths ("multiple feeders").
 */
import { createWorld } from '../src/gen/world';
import { createSeries, playNextGame } from '../src/sim/series';
import { clearRatingCache } from '../src/sim/common/ratings';
import type { Fixture } from '../src/model/types';
import { computeTeamStrength } from '../src/sim/common/team';

const titles = (process.argv[2] ?? 'mlbb,wildrift,hok,lol,dota2').split(',');
const N = Number(process.argv[3] ?? 60);
const spatial = (process.argv[4] ?? 'spatial') !== 'fast';
const mode = (process.argv[5] ?? 'real') as 'real' | 'fictional';
const pct = (xs: number[], p: number) => [...xs].sort((a, b) => a - b)[Math.min(xs.length - 1, Math.floor(xs.length * p))];
const mean = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / Math.max(1, xs.length);
for (const id of titles) {
  clearRatingCache();
  const w = createWorld({ name: 'kr', seed: `kr-${id}`, worldSize: 'small', titles: [id], startDay: 20000, dataMode: mode });
  const teams = Object.values(w.teams).filter((t) => t.titleId === id && t.tier === 'main' && t.leagueId && t.active);
  const total: number[] = [];
  const gap: number[] = [];
  const top: number[] = [];
  const worst: number[] = [];
  let feeders = 0;
  let fav = 0;
  let dur = 0;
  for (let i = 0; i < N; i++) {
    const a = teams[(i * 7) % teams.length];
    const b = teams[(i * 11 + 3) % teams.length];
    if (a === b) continue;
    const f = { id: `kr-${i}`, compId: Object.keys(w.comps)[0], titleId: id, teams: [a.id, b.id], day: w.day, status: 'scheduled', format: { n: 1, label: 'Bo1' }, formatKey: 'standard', detail: false, spatial } as unknown as Fixture;
    const g = playNextGame(w, f, createSeries(w, f));
    const k = [0, 1].map((s) => g.players.filter((l) => l.teamIdx === s).reduce((x, l) => x + (l.s.k ?? 0), 0));
    total.push(k[0] + k[1]);
    gap.push(k[g.winner] - k[1 - g.winner]);
    top.push(Math.max(...k));
    worst.push(Math.max(...g.players.map((l) => l.s.d ?? 0)));
    const loserDeaths = g.players.filter((l) => l.teamIdx !== g.winner).map((l) => l.s.d ?? 0);
    if (loserDeaths.filter((d) => d >= 7).length >= 2) feeders++;
    dur += g.duration;
    if ((computeTeamStrength(w, a) > computeTeamStrength(w, b)) === (g.winner === 0)) fav++;
  }
  const n = total.length;
  console.log(
    `${id.padEnd(8)} ${spatial ? 'spatial' : 'fast   '} n=${n} ${(dur / n / 60).toFixed(1)}min | kills ${mean(total).toFixed(1)} p90 ${pct(total, 0.9)} max ${Math.max(...total)} | gap ${mean(gap).toFixed(1)} p90 ${pct(gap, 0.9)} max ${Math.max(...gap)} | top team ${pct(top, 0.9)}/${Math.max(...top)} | worst deaths ${mean(worst).toFixed(1)} p90 ${pct(worst, 0.9)} max ${Math.max(...worst)} | multi-feeder ${((100 * feeders) / n).toFixed(0)}% | winner share ${((100 * (mean(total) + mean(gap)) / 2) / mean(total)).toFixed(0)}% | fav ${((100 * fav) / n).toFixed(0)}%`,
  );
}

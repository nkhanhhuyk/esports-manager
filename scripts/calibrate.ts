import { createWorld } from '../src/gen/world';
import { dayFromYMD } from '../src/core/date';
import { computeTeamStrength } from '../src/sim/common/team';
import { createSeries, playNextGame } from '../src/sim/series';
import type { Fixture } from '../src/model/types';
import { getTitle } from '../src/data/titles/catalogue';
import { runBrSeries } from '../src/sim/series';

const titles = (process.env.TITLES ?? 'lol,mlbb,dota2,unite,hots,valorant,cs2,r6,overwatch,paladins,brawlstars,deadlock,cod').split(',');
for (const tid of titles) {
  const w = createWorld({ name: 'C', seed: 'cal-' + tid, worldSize: 'tiny', titles: [tid], startDay: dayFromYMD(2026, 11, 19) });
  const t = getTitle(tid);
  const teams = Object.values(w.teams).filter((x) => x.titleId === tid && x.tier === 'main');
  const str = new Map(teams.map((x) => [x.id, computeTeamStrength(w, x)]));
  const buckets: Record<string, [number, number]> = {};
  let n = 0;
  const durations: number[] = [];
  if (t.engine === 'br') {
    // correlation between strength rank and avg placement
    const f: Fixture = { id: 'cal-br', compId: '', stageId: '', titleId: tid, day: w.day, teams: teams.map((x) => x.id), format: { kind: 'br', n: 30, label: '' }, formatKey: 'standard', status: 'scheduled', detail: false };
    const r = runBrSeries(w, f);
    const sorted = [...teams].sort((a, b) => str.get(b.id)! - str.get(a.id)!);
    const top = sorted.slice(0, 3).map((x) => r.points[x.id]);
    const bot = sorted.slice(-3).map((x) => r.points[x.id]);
    const wins = sorted.map((x) => r.wins[x.id]);
    console.log(tid, 'strengths', sorted.map((x) => str.get(x.id)!.toFixed(1)).join(' '), '\n  pts top3', top, 'bottom3', bot, 'wins by rank', wins.join(','));
    continue;
  }
  for (let i = 0; i < teams.length; i++) for (let j = i + 1; j < teams.length; j++) {
    for (let k = 0; k < 6; k++) {
      const f: Fixture = { id: `cal-${i}-${j}-${k}`, compId: '', stageId: '', titleId: tid, day: w.day, teams: [teams[i].id, teams[j].id], format: { kind: 'bo', n: 1, label: '' }, formatKey: 'standard', status: 'scheduled', detail: false };
      const st = createSeries(w, f);
      const g = playNextGame(w, f, st);
      durations.push(g.duration);
      const d = str.get(teams[i].id)! - str.get(teams[j].id)!;
      const fav = d >= 0 ? 0 : 1;
      const ad = Math.abs(d);
      const b = ad < 1 ? '0-1' : ad < 2 ? '1-2' : ad < 3 ? '2-3' : ad < 5 ? '3-5' : '5+';
      buckets[b] ||= [0, 0];
      buckets[b][0] += g.winner === fav ? 1 : 0;
      buckets[b][1]++;
      n++;
    }
  }
  const sv = [...str.values()];
  console.log(tid.padEnd(10), 'str range', Math.min(...sv).toFixed(1), '-', Math.max(...sv).toFixed(1), 'avg dur', (durations.reduce((a, b) => a + b, 0) / durations.length / 60).toFixed(1), 'min', Object.entries(buckets).sort().map(([k, [a, b]]) => `${k}: ${(100 * a / b).toFixed(0)}% (${b})`).join('  '));
}

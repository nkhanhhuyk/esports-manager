/** Development aid: one recorded spatial game per title, kills by cause/phase and hero activity mix. */
import { createWorld } from '../src/gen/world';
import { createSeries, playNextGame } from '../src/sim/series';
import type { Fixture } from '../src/model/types';
for (const id of (process.argv[2] ?? 'lol').split(',')) {
  const w = createWorld({ name: 'cal', seed: `cal-${id}`, worldSize: 'small', titles: [id], startDay: 20000 });
  const teams = Object.values(w.teams).filter((t) => t.titleId === id && t.tier === 'main');
  const f = { id: `ins-${id}`, compId: Object.keys(w.comps)[0], titleId: id, teams: [teams[0].id, teams[1].id], day: w.day, status: 'scheduled', format: { n: 1, label: 'Bo1' }, formatKey: 'standard', detail: true } as unknown as Fixture;
  const s = createSeries(w, f, true);
  s.record = true;
  const r = playNextGame(w, f, s);
  const rp = r.replay!;
  const kills = rp.events.filter((e) => e.k === 'kill');
  const byCause = new Map<string, number>();
  for (const k of kills) byCause.set(k.a === undefined ? `nonhero:${k.n}` : 'hero', (byCause.get(k.a === undefined ? `nonhero:${k.n}` : 'hero') ?? 0) + 1);
  const byPhase = new Map<number, number>();
  for (const k of kills) byPhase.set(Math.floor(k.t / 300) * 5, (byPhase.get(Math.floor(k.t / 300) * 5) ?? 0) + 1);
  const acts = new Map<number, number>();
  for (let i = 0; i < rp.hst.length; i++) if (!(rp.hst[i] & 1)) acts.set(rp.hst[i] >> 1 & 15, (acts.get(rp.hst[i] >> 1 & 15) ?? 0) + 1);
  const names = ['', 'lane', 'jungle', 'roam', 'objective', 'fight', 'retreat', 'recall', 'siege', 'defend', 'group'];
  const dead = rp.hst.filter((x) => x & 1).length / rp.hst.length;
  console.log(`${id}: ${(r.duration / 60).toFixed(1)} min, kills ${r.score.join('-')}, winner ${r.winner}, frames ${rp.frames}`);
  console.log('  causes', [...byCause].map(([k, v]) => `${k}=${v}`).join(' '));
  console.log('  per 5 min', [...byPhase].sort((a, b) => a[0] - b[0]).map(([k, v]) => `${k}:${v}`).join(' '));
  console.log('  activity', [...acts].sort((a, b) => b[1] - a[1]).map(([k, v]) => `${names[k]} ${(100 * v / rp.hst.length).toFixed(0)}%`).join(' '), `dead ${(dead * 100).toFixed(0)}%`);
  console.log('  structures', rp.events.filter((e) => e.k === 'structure').map((e) => `${Math.round(e.t / 60)}m:${e.s}:${e.n}`).join(', '));
  console.log('  objectives', rp.events.filter((e) => e.k === 'objective').map((e) => `${Math.round(e.t / 60)}m:${e.s}:${e.n}`).join(', '));
}
// Kill geography (second pass, LoL only for brevity)
import { mobaMap } from '../src/sim/moba/maps';
import { segDist } from '../src/sim/moba/map';
{
  const id = process.argv[3];
  if (id) {
    const w = createWorld({ name: 'cal', seed: `cal-${id}`, worldSize: 'small', titles: [id], startDay: 20000 });
    const teams = Object.values(w.teams).filter((t) => t.titleId === id && t.tier === 'main');
    const f = { id: `ins2-${id}`, compId: Object.keys(w.comps)[0], titleId: id, teams: [teams[2].id, teams[3].id], day: w.day, status: 'scheduled', format: { n: 1, label: 'Bo1' }, formatKey: 'standard', detail: true } as unknown as Fixture;
    const s = createSeries(w, f, true);
    s.record = true;
    const r = playNextGame(w, f, s);
    const m = mobaMap(id);
    const rp = r.replay!;
    const where = (x: number, y: number) => {
      for (const l of ['top', 'mid', 'bot'] as const) for (let i = 1; i < m.lanes[l].length; i++) if (segDist({ x, y }, m.lanes[l][i - 1], m.lanes[l][i]) < 40) return `lane-${l}`;
      for (let i = 1; i < m.river.length; i++) if (segDist({ x, y }, m.river[i - 1], m.river[i]) < 45) return 'river';
      return 'jungle';
    };
    const tally = new Map<string, number>();
    for (const e of rp.events.filter((e) => e.k === 'kill')) {
      const fr = Math.min(rp.frames - 1, Math.round(e.t / rp.dt));
      // attackers within 80 of the victim at that frame
      let near = 0;
      for (let h = 0; h < 10; h++) if (rp.heroes[h].side === e.s && !(rp.hst[fr * 10 + h] & 1) && Math.hypot(rp.hx[fr * 10 + h] - (e.x ?? 0), rp.hy[fr * 10 + h] - (e.y ?? 0)) < 90) near++;
      const k = `${e.t < 600 ? 'early' : 'late'} ${where(e.x ?? 0, e.y ?? 0)} ${near}v`;
      tally.set(k, (tally.get(k) ?? 0) + 1);
    }
    console.log(`${id} game2 ${(r.duration / 60).toFixed(1)}m kills ${r.score.join('-')}`);
    console.log([...tally].sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}=${v}`).join('  '));
  }
}

/** Fan & rivalry calibration: merchandise vs the old formula, follower drift, rivalry counts. */
import { createWorld } from '../src/gen/world';
import { advance } from '../src/game/advance';
import { dayFromYMD } from '../src/core/date';
import { regionEconomy } from '../src/data/countries';
import { merchandiseRevenue, overallMood, payingSupporters } from '../src/systems/fans';
import { rivalryLabel } from '../src/systems/rivalries';
const w = createWorld({ name: 'F', seed: process.argv[2] ?? 'fans', worldSize: 'small', titles: ['lol', 'valorant', 'mlbb', 'cs2', 'hok', 'dota2', 'wildrift'], startDay: dayFromYMD(2026, 11, 19) });
const orgs = () => Object.values(w.orgs).filter((o) => o.active && !o.federation);
function report(tag: string) {
  let oldM = 0, newM = 0, fans = 0, paying = 0;
  const moods: number[] = [];
  for (const o of orgs()) {
    const econ = regionEconomy(o.region);
    oldM += o.fans * 0.012 * econ;
    newM += merchandiseRevenue(o, econ);
    fans += o.fans;
    paying += payingSupporters(o.fanbase!);
    moods.push(overallMood(o.fanbase!));
  }
  moods.sort((a, b) => a - b);
  const riv = Object.values(w.rivalries ?? {});
  const by: Record<string, number> = {};
  for (const r of riv) { const l = rivalryLabel(r.intensity) ?? '<15'; by[l] = (by[l] ?? 0) + 1; }
  const segs: Record<string, number> = {};
  for (const o of orgs()) for (const [k, v] of Object.entries(o.fanbase!.seg)) segs[k] = (segs[k] ?? 0) + v.size;
  console.log(tag, Object.entries(segs).map(([k, v]) => `${k} ${(v / 1e6).toFixed(2)}M`).join(' '));
  console.log(`${tag}: orgs ${orgs().length} fans ${(fans / 1e6).toFixed(2)}M paying ${(paying / 1e3).toFixed(0)}K merch new/old ${(newM / oldM).toFixed(3)} mood p10/50/90 ${moods[Math.floor(moods.length * 0.1)].toFixed(0)}/${moods[Math.floor(moods.length / 2)].toFixed(0)}/${moods[Math.floor(moods.length * 0.9)].toFixed(0)} rivalries ${riv.length} ${JSON.stringify(by)} notable ${riv.filter((r) => r.since !== undefined).length}`);
}
report('start');
const t0 = Date.now();
for (let s = 1; s <= Number(process.argv[3] ?? 3); s++) {
  advance(w, { kind: 'end-season' });
  advance(w, { kind: 'days', n: 2 });
  report(`season ${s}`);
}
console.log('time', ((Date.now() - t0) / 1000).toFixed(1), 's', 'rivalries JSON', (JSON.stringify(w.rivalries).length / 1024).toFixed(0), 'KB', 'fanbases JSON', (JSON.stringify(orgs().map((o) => o.fanbase)).length / 1024).toFixed(0), 'KB');
const top = Object.values(w.rivalries ?? {}).sort((a, b) => b.intensity - a.intensity).slice(0, 5);
for (const r of top) console.log(r.titleId, w.teams[r.teams[0]]?.short, 'vs', w.teams[r.teams[1]]?.short, r.intensity, rivalryLabel(r.intensity), 'meet', r.meetings, 'finals', r.finals, 'po', r.playoffs, 'tr', r.transfers, 'derby', r.derby, 'h2h', r.wins.join('-'));

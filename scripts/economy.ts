import { createWorld } from '../src/gen/world';
import { advance } from '../src/game/advance';
import { dayFromYMD } from '../src/core/date';
import { monthlyBreakdown } from '../src/systems/finance';
const w = createWorld({ name: 'E', seed: 'econ', worldSize: 'small', titles: ['lol', 'valorant', 'mlbb', 'cs2', 'hok', 'dota2'], startDay: dayFromYMD(2026, 11, 19) });
const start = new Map(Object.values(w.orgs).map((o) => [o.id, o.balance]));
let net: number[] = [];
for (const o of Object.values(w.orgs)) { const m = monthlyBreakdown(w, o); const r = Object.values(m.revenue).reduce((a, b) => a + b, 0); const e = Object.values(m.expenses).reduce((a, b) => a + b, 0); net.push((r - e) / Math.max(1, e)); }
net.sort((a, b) => a - b);
console.log('orgs', net.length, 'net/expense ratio p10/p50/p90', net[Math.floor(net.length * 0.1)].toFixed(2), net[Math.floor(net.length / 2)].toFixed(2), net[Math.floor(net.length * 0.9)].toFixed(2));
advance(w, { kind: 'end-season' });
const deltas = Object.values(w.orgs).map((o) => (o.balance - (start.get(o.id) ?? 0)));
deltas.sort((a, b) => a - b);
console.log('season delta p10/p50/p90', Math.round(deltas[Math.floor(deltas.length * 0.1)]), Math.round(deltas[Math.floor(deltas.length / 2)]), Math.round(deltas[Math.floor(deltas.length * 0.9)]));
console.log('in debt', Object.values(w.orgs).filter((o) => o.balance < 0).length, 'of', Object.keys(w.orgs).length);
const transfers = w.transfers.filter((t) => t.type === 'transfer').length, frees = w.transfers.filter((t) => t.type === 'free').length;
console.log('transfers', transfers, 'free signings', frees, 'releases', w.transfers.filter((t) => t.type === 'release').length, 'retired', Object.values(w.players).filter(p=>p.retired).length);

import { createWorld } from '../src/gen/world';
import { advance } from '../src/game/advance';
const w = createWorld({ name: 'p', seed: 'prof', worldSize: 'standard', startDay: 20000, dataMode: 'real' });
const t = Date.now();
advance(w, { kind: 'days', n: Number(process.argv[2] ?? 90) }, { stopForUserMatch: false });
console.log('days', process.argv[2] ?? 90, 'ms', Date.now() - t);

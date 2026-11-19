import { makeWorld, withUser } from '../tests/helpers';
import { advance } from '../src/game/advance';
const w = withUser(makeWorld(['lol', 'cs2', 'mlbb'], 'long'));
const count = () => Object.values(w.players).filter((p) => !p.retired);
const start = count().length;
for (let s = 0; s < 10; s++) {
  advance(w, { kind: 'seasons', n: 1 });
  const act = count();
  const fa = act.filter((p) => !p.teamId).length;
  const byTitle: Record<string, number> = {};
  for (const p of act) byTitle[p.titleId] = (byTitle[p.titleId] ?? 0) + 1;
  console.log(w.season - 1, 'active', act.length, `(${(act.length / start).toFixed(2)}x)`, 'FA', fa, JSON.stringify(byTitle), 'retired so far', Object.values(w.players).filter((p) => p.retired).length + w.retired.length);
}

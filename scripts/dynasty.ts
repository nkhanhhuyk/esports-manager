import { makeWorld, withUser } from '../tests/helpers';
import { advance } from '../src/game/advance';
import { computeTeamStrength } from '../src/sim/common/team';
import { ageAt } from '../src/core/date';
const w = withUser(makeWorld(['lol', 'cs2', 'pubgm'], 'long'));
for (let s = 0; s < 8; s++) {
  const teams = Object.values(w.teams).filter((t) => t.titleId === 'lol' && t.active && t.tier === 'main').map((t) => ({ t, s: computeTeamStrength(w, t) })).sort((a, b) => b.s - a.s);
  const top = teams.slice(0, 4).map((x) => `${x.t.short}:${x.s.toFixed(1)}(age ${(x.t.lineup.reduce((a, l) => a + ageAt(w.players[l.playerId].birth, w.day), 0) / x.t.lineup.length).toFixed(1)}, chem ${x.t.chemistry.toFixed(0)})`).join(' ');
  advance(w, { kind: 'seasons', n: 1 });
  const h = w.history[w.history.length - 1];
  const worlds = h.champions.find((c) => c.titleId === 'lol' && c.tier === 'worlds');
  const splits = h.champions.filter((c) => c.titleId === 'lol' && c.tier === 'league').map((c) => w.teams[c.teamId].short).join(',');
  console.log(h.season, 'worlds:', w.teams[worlds!.teamId].short, '| splits', splits, '| pre-season top:', top);
}

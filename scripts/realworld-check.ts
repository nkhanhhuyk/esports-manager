/** Quick check of a real-world start: league/team counts and a few rosters. */
import { createWorld } from '../src/gen/world';
const t0 = Date.now();
const w = createWorld({ name: 'check', seed: 'real1', worldSize: (process.argv[2] as 'small') ?? 'small', dataMode: 'real' });
console.log('built in', Date.now() - t0, 'ms');
for (const l of Object.values(w.leagues).sort((a, b) => a.titleId.localeCompare(b.titleId))) {
  const teams = l.teamIds.map((id) => w.teams[id]);
  console.log(l.titleId.padEnd(9), l.tier, (l.real ? 'REAL ' : 'fict ') + l.name.padEnd(34), l.region.padEnd(24), teams.length, 'teams,', teams.filter((t) => t.real).length, 'real');
}
const show = (name: string) => {
  const t = Object.values(w.teams).find((x) => x.name === name);
  if (!t) return console.log('missing', name);
  const ps = t.roster.map((id) => w.players[id]);
  console.log(name, '| org', w.orgs[t.orgId].name, w.orgs[t.orgId].country, '| elo', t.elo, '|', t.lineup.map((s) => `${w.players[s.playerId].handle}:${s.roleId}:${w.players[s.playerId].ca}`).join(', '), '| bench', ps.filter((p) => !t.lineup.some((s) => s.playerId === p.id)).map((p) => p.handle).join(','));
};
for (const n of ['T1', 'Gen.G Esports', 'ONIC', 'Team Spirit', 'Sentinels', 'Vitality', 'Paper Rex', 'G2 Esports']) show(n);
const orgs = Object.values(w.orgs).filter((o) => o.teams.length > 1 && o.real);
console.log('multi-title orgs', orgs.length, orgs.slice(0, 20).map((o) => `${o.name}[${o.teams.map((id) => w.teams[id].titleId).join('/')}]`).join(' '));
console.log('players', Object.keys(w.players).length, 'real', Object.values(w.players).filter((p) => p.real).length, 'teams', Object.keys(w.teams).length);

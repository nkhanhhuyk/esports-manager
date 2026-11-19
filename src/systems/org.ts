/**
 * Organisation-level actions: expansion into new titles (buying league slots), academy
 * creation, division closure, rebranding.
 */
import { RNG } from '../core/rng';
import { regionEconomy } from '../data/countries';
import { getTitle } from '../data/titles/catalogue';
import type { League, Team, World } from '../model/types';
import { closeDivision } from '../game/rollover';
import { createTeam, populateTeam, staffTeam } from '../gen/world';
import type { GenCtx } from '../gen/player';
import { autoLineup } from './lineup';
import { ledger } from './finance';
import { addNews } from './news';
import { computeTeamStrength } from '../sim/common/team';
import { titlePopIn } from './contracts';

export function slotCost(world: World, league: League): number {
  const t = getTitle(league.titleId);
  const pop = titlePopIn(world, league.titleId, league.region);
  return Math.round((t.baseSalary * 6 * (0.3 + pop / 100) * regionEconomy(league.region) * (league.tier === 1 ? 1 : 0.25) * (league.franchised ? 2.5 : 1)) / 1000) * 1000;
}

function ctxFor(world: World, salt: string): GenCtx {
  return { world, rng: new RNG(`${world.meta.seed}|org|${salt}|${world.day}`), handles: new Set(Object.values(world.players).map((p) => p.handle.toLowerCase())) };
}

/** Buy the league slot of the weakest non-user team and enter with an expansion roster. */
export function buyLeagueSlot(world: World, orgId: string, leagueId: string): { ok: boolean; message: string; teamId?: string } {
  const org = world.orgs[orgId];
  const league = world.leagues[leagueId];
  if (!org || !league) return { ok: false, message: 'Invalid organisation or league.' };
  if (league.tier === 1 && org.teams.some((id) => world.teams[id]?.titleId === league.titleId && world.teams[id]?.tier === 'main' && world.teams[id]?.active)) return { ok: false, message: 'You already have a main team in this title.' };
  if (Object.values(world.comps).some((c) => c.leagueId === leagueId && c.season === world.season && c.status === 'active')) return { ok: false, message: 'Slots can only change hands between splits (no active split in this league).' };
  const cost = slotCost(world, league);
  if (org.balance < cost) return { ok: false, message: `The slot costs ${cost.toLocaleString()}; insufficient funds.` };
  const victims = league.teamIds.map((id) => world.teams[id]).filter((t) => t && t.orgId !== orgId && !world.orgs[t.orgId]?.user).sort((a, b) => a.elo - b.elo);
  const victim = victims[0];
  if (!victim) return { ok: false, message: 'No slot available for purchase.' };
  const ctx = ctxFor(world, leagueId);
  const seller = world.orgs[victim.orgId];
  ledger(world, org, 'slot-purchase', -cost, undefined, `${league.name} slot`);
  ledger(world, seller, 'slot-sale', cost, victim.id, `${league.name} slot sold`);
  // Release victim's players & mark division closed (do not create a replacement org).
  for (const pid of [...victim.roster]) {
    const p = world.players[pid];
    if (!p) continue;
    p.teamId = null;
    p.contract = null;
    p.career.push({ season: world.season, day: world.day, titleId: victim.titleId, teamId: null, orgId: null, type: 'released', note: 'Slot sold' });
  }
  victim.roster = [];
  victim.lineup = [];
  victim.active = false;
  victim.leagueId = null;
  seller.teams = seller.teams.filter((id) => id !== victim.id);
  seller.events.push({ season: world.season, day: world.day, type: 'division-closed', text: `${seller.name} sold its ${league.name} slot to ${org.name}.` });
  const tier = league.tier === 2 && org.teams.some((id) => world.teams[id]?.titleId === league.titleId && world.teams[id]?.tier === 'main') ? 'academy' : 'main';
  const parent = tier === 'academy' ? org.teams.map((id) => world.teams[id]).find((t) => t.titleId === league.titleId && t.tier === 'main') ?? null : null;
  const team = createTeam(ctx, org, league.titleId, league.region, tier, null, parent);
  team.leagueId = league.id;
  league.teamIds = league.teamIds.map((x) => (x === victim.id ? team.id : x));
  const others = league.teamIds.map((id) => world.teams[id]).filter((x) => x && x.id !== team.id);
  const avgCA = others.length ? others.reduce((a, x) => a + x.roster.reduce((s, pid) => s + (world.players[pid]?.ca ?? 60), 0) / Math.max(1, x.roster.length), 0) / others.length : 60;
  populateTeam(ctx, team, avgCA - 3, league.region, getTitle(league.titleId).family, 0.1);
  staffTeam(ctx, team, 50);
  team.lineup = autoLineup(world, team);
  team.elo = Math.round(1500 + (computeTeamStrength(world, team) - 65) * 18);
  for (const pid of team.roster) world.scouting[pid] = 100;
  org.events.push({ season: world.season, day: world.day, type: 'division-opened', text: `${org.name} enters ${getTitle(league.titleId).short} (${league.name}).` });
  addNews(world, { kind: 'org', title: `${org.name} buy into ${league.name}`, body: `${org.name} acquire the slot of ${victim.name} and enter ${getTitle(league.titleId).name}.`, titleId: league.titleId, refs: [{ type: 'org', id: org.id }, { type: 'team', id: team.id }], importance: 3 });
  return { ok: true, message: `Welcome to ${league.name}!`, teamId: team.id };
}

/** Create a development academy (no league slot) attached to a main team. */
export function createAcademy(world: World, parentTeamId: string): { ok: boolean; message: string } {
  const parent = world.teams[parentTeamId];
  if (!parent) return { ok: false, message: 'Invalid team.' };
  const org = world.orgs[parent.orgId];
  if (org.teams.some((id) => world.teams[id]?.parentTeamId === parent.id && world.teams[id].active)) return { ok: false, message: 'This team already has an academy.' };
  const cost = Math.round(60000 * (0.4 + regionEconomy(org.region)));
  if (org.balance < cost) return { ok: false, message: `Founding an academy costs ${cost.toLocaleString()}.` };
  ledger(world, org, 'academy', -cost, undefined, 'Academy founded');
  const ctx = ctxFor(world, parentTeamId);
  const team = createTeam(ctx, org, parent.titleId, parent.region, 'academy', null, parent);
  populateTeam(ctx, team, 50, parent.region, getTitle(parent.titleId).family, 0);
  staffTeam(ctx, team, 45);
  team.lineup = autoLineup(world, team);
  for (const pid of team.roster) world.scouting[pid] = 100;
  org.events.push({ season: world.season, day: world.day, type: 'division-opened', text: `${org.name} founds ${team.name}.` });
  return { ok: true, message: `${team.name} founded (development squad, no league slot).` };
}

export function closeUserDivision(world: World, teamId: string): { ok: boolean; message: string } {
  const team = world.teams[teamId];
  if (!team) return { ok: false, message: 'Invalid team.' };
  const ctx = ctxFor(world, `close-${teamId}`);
  closeDivision(world, team, ctx, ctx.rng);
  return { ok: true, message: `${team.name} division closed.` };
}

export function rebrandOrg(world: World, orgId: string, name: string, short: string): void {
  const org = world.orgs[orgId];
  const old = org.name;
  org.name = name;
  org.short = short.slice(0, 5);
  org.names.push({ season: world.season, name, short: org.short });
  for (const tid of org.teams) {
    const t: Team = world.teams[tid];
    t.name = t.tier === 'main' ? name : `${name} ${t.tier === 'academy' ? 'Academy' : 'Reserve'}`;
    t.short = org.short + (t.tier === 'academy' ? '.A' : '');
  }
  org.events.push({ season: world.season, day: world.day, type: 'rebrand', text: `${old} rebrands as ${name}.` });
}

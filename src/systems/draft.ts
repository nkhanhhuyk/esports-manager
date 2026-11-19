/**
 * Rookie drafts for leagues whose policy enables them (franchised leagues by default).
 * At the season rollover a prospect class is generated from the league's region; teams pick in
 * reverse order of the finished season's standings (worst first), two rounds. Drafted players sign
 * rookie contracts; picks that cannot fit on a roster become free agents with their rights noted.
 */
import { clamp } from '../core/math';
import type { RNG } from '../core/rng';
import { COUNTRY_BY_CODE, expandRegion } from '../data/countries';
import { getTitle } from '../data/titles/catalogue';
import type { League, Player, World } from '../model/types';
import { generatePlayer, pickNationality, type GenCtx } from '../gen/player';
import { makeContract } from './contracts';
import { rosterMax } from './transfers';
import { addInbox, addNews } from './news';
import { nextId } from '../model/ids';

export interface DraftPick {
  round: number;
  pick: number;
  teamId: string;
  playerId: string;
}

function draftOrder(world: World, league: League, season: number): string[] {
  const wins = new Map<string, number>();
  for (const c of Object.values(world.comps)) {
    if (c.season !== season || c.leagueId !== league.id) continue;
    for (const r of c.stages[0].standings) wins.set(r.teamId, (wins.get(r.teamId) ?? 0) + r.w * 10 + r.gw - r.gl + r.pts / 10);
  }
  return [...league.teamIds].sort((a, b) => (wins.get(a) ?? 0) - (wins.get(b) ?? 0));
}

export function runRookieDrafts(world: World, season: number, rng: RNG): DraftPick[] {
  const all: DraftPick[] = [];
  const ctx: GenCtx = { world, rng, handles: new Set(Object.values(world.players).map((p) => p.handle.toLowerCase())) };
  for (const league of Object.values(world.leagues)) {
    if (!league.policy.draft || league.tier !== 1) continue;
    const t = getTitle(league.titleId);
    const regs = expandRegion(league.region);
    const pop = world.titles[league.titleId]?.popularity ?? {};
    // The draft class is drawn from unsigned prospects already in the world (regional first);
    // only a shortfall is generated, so drafts do not inflate the player population.
    const n = league.teamIds.length * 2 + 2;
    const prospect = (p: Player) => !p.retired && !p.teamId && p.titleId === t.id && world.day - p.birth < 20.5 * 365.25 && world.day - p.birth >= league.policy.minAge * 365.25;
    const pool: Player[] = Object.values(world.players).filter((p) => prospect(p) && regs.some((r) => p.nat && expandRegion(r).length && (COUNTRY_BY_CODE[p.nat]?.region === r)));
    if (pool.length < n) pool.push(...Object.values(world.players).filter((p) => prospect(p) && !pool.includes(p)).slice(0, n - pool.length));
    for (let i = pool.length; i < n; i++) {
      const age = Math.max(league.policy.minAge, rng.int(17, 19));
      const p = generatePlayer(ctx, { titleId: t.id, nat: pickNationality(rng, regs, t.family, pop), age, targetCA: clamp(rng.normal(56, 5), 40, 72), potentialBoost: rng.normal(5, 5) });
      world.players[p.id] = p;
      pool.push(p);
    }
    const order = draftOrder(world, league, season);
    let pickNo = 0;
    for (let round = 1; round <= 2; round++) {
      for (const teamId of order) {
        const team = world.teams[teamId];
        if (!team || !team.active || !pool.length) continue;
        pickNo++;
        // Teams value upside: CA plus scouted headroom with some evaluation noise.
        pool.sort((a, b) => b.ca + (b.pa - b.ca) * 0.8 - (a.ca + (a.pa - a.ca) * 0.8));
        const idx = Math.min(pool.length - 1, Math.floor(Math.abs(rng.normal(0, 1.2))));
        const p = pool.splice(idx, 1)[0];
        all.push({ round, pick: pickNo, teamId, playerId: p.id });
        p.career.push({ season: season + 1, day: world.day, titleId: t.id, teamId, orgId: team.orgId, type: 'draft', note: `${league.short} draft R${round} #${pickNo}` });
        world.transfers.push({ id: nextId(world, 'x'), day: world.day, season: season + 1, playerId: p.id, fromTeamId: null, toTeamId: teamId, fee: 0, type: 'draft', titleId: t.id, note: `Round ${round}, pick ${pickNo}` });
        if (team.roster.length < rosterMax(world, team)) {
          p.contract = makeContract(world, team, Math.max(league.policy.minSalary * 1.5, 5000), 2, { buyout: null, option: 'team' });
          p.teamId = team.id;
          p.joinedDay = world.day;
          team.roster.push(p.id);
          if (team.orgId === world.meta.userOrgId) world.scouting[p.id] = 100;
        }
        if (team.orgId === world.meta.userOrgId) addInbox(world, { from: league.name, subject: `Draft: you selected ${p.handle}`, body: `Round ${round}, pick ${pickNo}: ${p.handle} (${Math.round(p.ca)} CA, age ${age(p, world)}). ${p.teamId ? 'Signed to a two-season rookie deal with a team option.' : 'Roster full — the pick remains unsigned and enters free agency.'}`, kind: 'draft' });
      }
    }
    const first = all.find((d) => d.pick === 1 && world.teams[d.teamId]?.leagueId === league.id);
    if (first) addNews(world, { kind: 'prospect', title: `${world.players[first.playerId].handle} goes #1 in the ${league.short} draft`, body: `${world.teams[first.teamId].name} select the top prospect of the ${season + 1} class.`, titleId: league.titleId, refs: [{ type: 'player', id: first.playerId }, { type: 'team', id: first.teamId }], importance: 2 });
  }
  return all;
}

function age(p: Player, world: World): number {
  return Math.floor((world.day - p.birth) / 365.25);
}

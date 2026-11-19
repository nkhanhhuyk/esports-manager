/**
 * Season rollover: awards, history snapshots, promotion/relegation, contract expiry,
 * retirements (incl. players becoming staff), youth regeneration, popularity & scene drift,
 * organisation evolution (rebrands, closures, new divisions, mergers), sponsors, pruning of
 * old detail data, and planning the next season. Idempotent per season.
 */
import { clamp } from '../core/math';
import { ageAt } from '../core/date';
import { RNG } from '../core/rng';
import { COUNTRIES, COUNTRY_BY_CODE, expandRegion } from '../data/countries';
import { getTitle } from '../data/titles/catalogue';
import type { League, Player, SeasonHistory, Team, World } from '../model/types';
import { planSeason } from '../competition/season';
import { hallOfFame, legacy, seasonAwards } from '../systems/awards';
import { expireContracts } from '../systems/transfers';
import { renewSponsors } from '../systems/finance';
import { addInbox, addNews } from '../systems/news';
import { yearlyDevelopment } from '../systems/training';
import { releasePatch } from '../systems/patches';
import { autoLineup } from '../systems/lineup';
import { generatePlayer, pickNationality, type GenCtx } from '../gen/player';
import { staffFromPlayer } from '../gen/staff';
import { createOrg, createTeam, populateTeam, staffTeam } from '../gen/world';
import { expectedStaffSalary, makeContract } from '../systems/contracts';
import { makeOrgName } from '../gen/names';
import { computeTeamStrength } from '../sim/common/team';

function leagueTable(world: World, league: League, season: number): Array<{ teamId: string; w: number; l: number; gd: number }> {
  const agg = new Map<string, { teamId: string; w: number; l: number; gd: number }>();
  for (const c of Object.values(world.comps)) {
    if (c.season !== season || c.leagueId !== league.id) continue;
    const reg = c.stages[0];
    for (const r of reg.standings) {
      const a = agg.get(r.teamId) ?? { teamId: r.teamId, w: 0, l: 0, gd: 0 };
      a.w += r.w + (reg.type === 'br-points' ? r.pts / 10 : 0);
      a.l += r.l;
      a.gd += r.gw - r.gl;
      agg.set(r.teamId, a);
    }
  }
  return [...agg.values()].sort((a, b) => b.w - a.w || b.gd - a.gd);
}

function snapshotHistory(world: World, season: number): void {
  const h: SeasonHistory = { season, champions: [], standings: {}, events: [], titlePop: {} };
  for (const c of Object.values(world.comps)) {
    if (c.season !== season || c.status !== 'done' || !c.placements[0]) continue;
    const team = world.teams[c.placements[0]];
    h.champions.push({ compId: c.id, compName: c.name, titleId: c.titleId, tier: c.tier, teamId: team.id, teamName: team.name, orgId: team.orgId, runnerUpId: c.placements[1], mvp: c.mvpPlayerId });
  }
  for (const l of Object.values(world.leagues)) {
    h.standings[l.id] = leagueTable(world, l, season).map((r) => ({ teamId: r.teamId, name: world.teams[r.teamId]?.name ?? '?', w: Math.round(r.w), l: r.l }));
  }
  for (const [id, ts] of Object.entries(world.titles)) h.titlePop[id] = Math.round(ts.globalPop);
  world.history.push(h);
  // Team season records
  for (const team of Object.values(world.teams)) {
    if (!team.active) continue;
    const league = team.leagueId ? world.leagues[team.leagueId] : null;
    const table = league ? leagueTable(world, league, season) : [];
    const row = table.find((r) => r.teamId === team.id);
    const results = Object.values(world.comps)
      .filter((c) => c.season === season && c.status === 'done' && c.placements.includes(team.id))
      .map((c) => ({ compId: c.id, compName: c.name, place: c.placements.indexOf(team.id) + 1, tier: c.tier }));
    team.history.push({ season, leagueId: league?.id ?? null, leagueName: league?.name ?? '—', w: Math.round(row?.w ?? 0), l: row?.l ?? 0, rank: row ? table.indexOf(row) + 1 : null, results });
  }
}

function promotionRelegation(world: World, season: number, rng: RNG): void {
  for (const upper of Object.values(world.leagues)) {
    if (upper.tier !== 1 || !upper.lowerId || upper.franchised || upper.promotion <= 0) continue;
    const lower = world.leagues[upper.lowerId];
    if (!lower) continue;
    const upTable = leagueTable(world, upper, season);
    const lowTable = leagueTable(world, lower, season);
    // Lower-league split 2 champion has priority, then the best regular-season record.
    const split2 = Object.values(world.comps).find((c) => c.season === season && c.leagueId === lower.id && c.split === 2);
    const order = [...(split2?.placements[0] ? [split2.placements[0]] : []), ...lowTable.map((r) => r.teamId)];
    for (let k = 0; k < upper.promotion; k++) {
      const down = upTable[upTable.length - 1 - k]?.teamId;
      const up = order.find((tid) => {
        const t = world.teams[tid];
        if (!t || !t.active) return false;
        // An academy cannot be promoted into its parent's league.
        if (t.parentTeamId && world.teams[t.parentTeamId]?.leagueId === upper.id) return false;
        return true;
      });
      if (!down || !up) continue;
      upper.teamIds = upper.teamIds.map((x) => (x === down ? up : x));
      lower.teamIds = lower.teamIds.filter((x) => x !== up);
      lower.teamIds.push(down);
      world.teams[down].leagueId = lower.id;
      world.teams[up].leagueId = upper.id;
      addNews(world, { kind: 'world', title: `${world.teams[up].name} promoted to ${upper.name}`, body: `${world.teams[down].name} drop to ${lower.name}.`, titleId: upper.titleId, refs: [{ type: 'team', id: up }, { type: 'team', id: down }], importance: 3 });
      order.splice(order.indexOf(up), 1);
    }
    void rng;
  }
}

function retirements(world: World, rng: RNG): void {
  const ctx: GenCtx = { world, rng, handles: new Set(Object.values(world.players).map((p) => p.handle.toLowerCase())) };
  for (const p of Object.values(world.players)) {
    if (p.retired) continue;
    const age = ageAt(p.birth, world.day);
    let chance = 0;
    if (age >= 23) chance = 0.015 + Math.max(0, age - p.dev.declineAge) * 0.045 + Math.max(0, age - 28) * 0.05;
    if (!p.teamId && age >= 21) chance += 0.08 + (p.ca < 55 ? 0.1 : 0);
    if (p.ca < 45 && age > 20) chance += 0.1;
    if (p.teamId && p.contract && p.contract.endSeason > world.season) chance *= 0.25;
    if (!rng.chance(clamp(chance, 0, 0.95))) continue;
    retirePlayer(world, p, ctx, rng);
  }
}

export function retirePlayer(world: World, p: Player, ctx: GenCtx, rng: RNG): void {
  const team = p.teamId ? world.teams[p.teamId] : null;
  if (team) {
    team.roster = team.roster.filter((id) => id !== p.id);
    team.lineup = team.lineup.filter((s) => s.playerId !== p.id);
  }
  p.teamId = null;
  p.contract = null;
  p.retired = { season: world.season, day: world.day };
  p.career.push({ season: world.season, day: world.day, titleId: p.titleId, teamId: null, orgId: null, type: 'retired' });
  const lg = legacy(p).total;
  if (lg > 40 || p.rep > 75) addNews(world, { kind: 'retirement', title: `${p.handle} retires`, body: `${p.first} ${p.last} calls time on a career with a legacy score of ${lg.toFixed(0)}.`, titleId: p.titleId, refs: [{ type: 'player', id: p.id }], importance: lg > 80 ? 4 : 2 });
  // Coaching / staff second careers
  if (p.coachApt > 55 && rng.chance(0.25 + (p.coachApt - 55) / 100)) {
    const role = rng.weighted(['head-coach', 'analyst', 'assistant-coach', 'scout'] as const, (r) => ({ 'head-coach': p.coachApt > 75 ? 3 : 1, analyst: 2, 'assistant-coach': 2, scout: 1 })[r]);
    const s = staffFromPlayer(ctx, p, role);
    world.staff[s.id] = s;
    s.career.push({ season: world.season, day: world.day, titleId: p.titleId, teamId: null, orgId: null, type: 'start', note: 'Former player' });
  }
}

function regenerate(world: World, rng: RNG): void {
  const ctx: GenCtx = { world, rng, handles: new Set(Object.values(world.players).map((p) => p.handle.toLowerCase())) };
  for (const titleId of world.meta.titlesEnabled) {
    const t = getTitle(titleId);
    const pop = world.titles[titleId].popularity;
    const leagues = Object.values(world.leagues).filter((l) => l.titleId === titleId);
    const regs = leagues.flatMap((l) => expandRegion(l.region));
    const active = Object.values(world.players).filter((p) => !p.retired && p.titleId === titleId).length;
    const target = leagues.reduce((a, l) => a + l.size, 0) * (t.teamSize + t.subs) * 1.38;
    const n = Math.max(4, Math.round((target - active) * 0.9 + target * 0.04));
    for (let i = 0; i < n; i++) {
      const age = rng.int(13, 17);
      const nat = pickNationality(rng, regs.length ? regs : ['NA'], t.family, pop);
      const scene = world.countryScene[nat]?.[t.family === 'MOBA' ? 'moba' : 'shooter'] ?? 40;
      const p = generatePlayer(ctx, { titleId, nat, age, targetCA: clamp(rng.normal(44 + scene * 0.06, 6), 28, 66), potentialBoost: rng.normal(3 + scene / 40, 6) });
      world.players[p.id] = p;
      if (p.pa > 80 && age <= 16) addNews(world, { kind: 'prospect', title: `Scouts buzz about ${p.handle}`, body: `A ${age}-year-old ${t.short} prospect from ${COUNTRY_BY_CODE[nat]?.name}.`, titleId, refs: [{ type: 'player', id: p.id }], importance: 1 });
    }
  }
}

function popularityDrift(world: World, rng: RNG): void {
  for (const [id, ts] of Object.entries(world.titles)) {
    const trend = rng.normal(0, 3) + (ts.globalPop < 25 ? 1.2 : 0) - (ts.globalPop > 75 ? 0.8 : 0);
    for (const r of Object.keys(ts.popularity)) ts.popularity[r] = Math.round(clamp(ts.popularity[r] + trend + rng.normal(0, 2.5), 2, 100));
    const vals = Object.values(ts.popularity);
    const before = ts.globalPop;
    ts.globalPop = vals.reduce((a, b) => a + b, 0) / vals.length;
    ts.prestige = Math.round(clamp(ts.prestige * 0.85 + ts.globalPop * 0.15, 1, 100));
    ts.popHistory.push({ season: world.season, pop: Math.round(ts.globalPop) });
    if (ts.popHistory.length > 200) ts.popHistory.shift();
    if (ts.globalPop - before > 6) {
      ts.revivalCount++;
      addNews(world, { kind: 'world', title: `${getTitle(id).short} esports boom`, body: `Viewership and sponsorship for ${getTitle(id).name} grew sharply this season.`, titleId: id, refs: [], importance: 2 });
    }
  }
  for (const [cc, sc] of Object.entries(world.countryScene)) {
    sc.moba = Math.round(clamp(sc.moba + rng.normal(0, 1.5), 5, 100));
    sc.shooter = Math.round(clamp(sc.shooter + rng.normal(0, 1.5), 5, 100));
    void cc;
  }
}

function orgEvolution(world: World, rng: RNG): void {
  const ctx: GenCtx = { world, rng, handles: new Set(Object.values(world.players).map((p) => p.handle.toLowerCase())) };
  const names = new Set(Object.values(world.orgs).map((o) => o.name.toLowerCase()));
  for (const org of Object.values(world.orgs)) {
    if (!org.active || org.user) continue;
    // Rebrand
    if (rng.chance(0.015)) {
      const old = org.name;
      const nn = makeOrgName(rng, names);
      org.name = nn.name;
      org.short = nn.short;
      org.names.push({ season: world.season + 1, name: nn.name, short: nn.short });
      for (const tid of org.teams) {
        const t = world.teams[tid];
        t.name = t.tier === 'main' ? nn.name : `${nn.name} ${t.tier === 'academy' ? 'Academy' : 'Reserve'}`;
        t.short = nn.short + (t.tier === 'academy' ? '.A' : '');
      }
      org.events.push({ season: world.season, day: world.day, type: 'rebrand', text: `${old} rebrands as ${nn.name}.` });
      addNews(world, { kind: 'org', title: `${old} rebrands as ${nn.name}`, body: 'History, trophies and records carry over under the new identity.', refs: [{ type: 'org', id: org.id }], importance: 2 });
    }
    // Relocation within the macro region
    if (rng.chance(0.006)) {
      const cands = COUNTRIES.filter((c) => c.region === org.region && c.code !== org.country);
      if (cands.length) {
        const c = rng.pick(cands);
        org.events.push({ season: world.season, day: world.day, type: 'relocation', text: `${org.name} relocates to ${c.name}.` });
        org.country = c.code;
      }
    }
    // Division closures for distressed organisations
    if (org.balance < 0 && org.teams.length > 1 && rng.chance(0.3)) {
      const weakest = org.teams.map((id) => world.teams[id]).filter((t) => t.active).sort((a, b) => a.elo - b.elo)[0];
      if (weakest) closeDivision(world, weakest, ctx, rng);
    }
  }
}

/** Close a division: release players, hand the league slot to a new or existing organisation. */
export function closeDivision(world: World, team: Team, ctx: GenCtx, rng: RNG): void {
  const org = world.orgs[team.orgId];
  team.active = false;
  for (const pid of [...team.roster]) {
    const p = world.players[pid];
    if (!p) continue;
    p.teamId = null;
    p.contract = null;
    p.career.push({ season: world.season, day: world.day, titleId: team.titleId, teamId: null, orgId: null, type: 'released', note: 'Division closed' });
  }
  team.roster = [];
  team.lineup = [];
  for (const sid of team.staff) {
    const s = world.staff[sid];
    if (s) {
      s.teamId = null;
      s.contract = null;
    }
  }
  team.staff = [];
  org.teams = org.teams.filter((id) => id !== team.id);
  org.events.push({ season: world.season, day: world.day, type: 'division-closed', text: `${org.name} closes its ${getTitle(team.titleId).short} division.` });
  addNews(world, { kind: 'org', title: `${org.name} exits ${getTitle(team.titleId).short}`, body: `Financial pressure forces the closure of the division. Its league slot is sold.`, titleId: team.titleId, refs: [{ type: 'org', id: org.id }], importance: 2 });
  const league = team.leagueId ? world.leagues[team.leagueId] : null;
  if (league) {
    const regions = expandRegion(league.region);
    const region = rng.pick(regions);
    const names = new Set(Object.values(world.orgs).map((o) => o.name.toLowerCase()));
    const existing = Object.values(world.orgs).filter((o) => o.active && !o.user && regions.includes(o.region) && o.balance > 0 && !o.teams.some((tid) => world.teams[tid]?.titleId === team.titleId));
    const buyer = existing.length && rng.chance(0.55) ? rng.weighted(existing, (o) => o.rep) : createOrg(ctx, region, names);
    const nt = createTeam(ctx, buyer, team.titleId, league.region, 'main', null, null);
    nt.leagueId = league.id;
    league.teamIds = league.teamIds.map((x) => (x === team.id ? nt.id : x));
    const others = league.teamIds.map((id) => world.teams[id]).filter((x) => x && x.id !== nt.id && x.active);
    const avgCA = others.length ? others.reduce((a, x) => a + x.roster.reduce((s, pid) => s + (world.players[pid]?.ca ?? 60), 0) / Math.max(1, x.roster.length), 0) / others.length : 62;
    populateTeam(ctx, nt, avgCA - 2, league.region, getTitle(team.titleId).family, 0.08);
    staffTeam(ctx, nt, 50);
    nt.lineup = autoLineup(world, nt);
    nt.elo = Math.round(1500 + (computeTeamStrength(world, nt) - 65) * 18);
    buyer.events.push({ season: world.season, day: world.day, type: 'division-opened', text: `${buyer.name} enters ${getTitle(team.titleId).short} by acquiring a ${league.name} slot.` });
  }
  team.leagueId = null;
}

function hireMissingStaff(world: World, rng: RNG): void {
  const pool = Object.values(world.staff).filter((s) => !s.retired && !s.teamId && !s.orgId);
  for (const team of Object.values(world.teams)) {
    if (!team.active) continue;
    const org = world.orgs[team.orgId];
    if (!org || org.user) continue;
    const hasCoach = team.staff.some((id) => ['head-coach', 'academy-coach'].includes(world.staff[id]?.role));
    if (hasCoach) continue;
    const cands = pool.filter((s) => (s.role === 'head-coach' || s.role === 'assistant-coach') && !s.teamId).sort((a, b) => (b.titleFam[team.titleId] ?? 0) + b.rep - ((a.titleFam[team.titleId] ?? 0) + a.rep));
    const s = cands[0];
    if (!s) continue;
    s.role = 'head-coach';
    s.teamId = team.id;
    s.orgId = team.orgId;
    s.titleId = team.titleId;
    s.titleFam[team.titleId] = Math.max(s.titleFam[team.titleId] ?? 0, 30);
    s.contract = makeContract(world, team, expectedStaffSalary(world, s, team.titleId, team.region), rng.int(1, 3));
    team.staff.push(s.id);
    s.career.push({ season: world.season, day: world.day, titleId: team.titleId, teamId: team.id, orgId: team.orgId, type: 'signed' });
    pool.splice(pool.indexOf(s), 1);
  }
}

function staffAging(world: World, rng: RNG): void {
  for (const s of Object.values(world.staff)) {
    if (s.retired) continue;
    const age = ageAt(s.birth, world.day);
    if (age > 58 && rng.chance((age - 58) * 0.08)) {
      s.retired = { season: world.season };
      if (s.teamId) {
        const t = world.teams[s.teamId];
        if (t) t.staff = t.staff.filter((x) => x !== s.id);
      }
      s.teamId = null;
      s.contract = null;
      continue;
    }
    // Staff development toward their potential.
    for (const k of Object.keys(s.attrs) as Array<keyof typeof s.attrs>) {
      const d = age < 45 ? rng.normal(0.6, 1) * (s.pa > 60 ? 1 : 0.5) : rng.normal(-0.3, 0.8);
      s.attrs[k] = Math.round(clamp(s.attrs[k] + d, 1, 99));
    }
    if (s.titleId) s.titleFam[s.titleId] = clamp((s.titleFam[s.titleId] ?? 0) + 3, 0, 100);
  }
}

/** Remove heavy, no-longer-needed detail data to keep very long saves fast and small. */
function prune(world: World, endedSeason: number): void {
  const keepSeason = endedSeason; // keep the season just finished for browsing; drop older ones
  for (const [id, c] of Object.entries(world.comps)) {
    if (c.season < keepSeason) {
      for (const st of c.stages) for (const fid of st.fixtureIds) {
        delete world.fixtures[fid];
        delete world.matches[fid];
      }
      delete world.comps[id];
    }
  }
  for (const k of Object.keys(world.schedule)) {
    const d = Number(k);
    const ids = world.schedule[d].filter((id) => world.fixtures[id]);
    if (!ids.length) delete world.schedule[d];
    else world.schedule[d] = ids;
  }
  // Compact detail of the finished season's non-user matches (keep finals).
  for (const [fid, m] of Object.entries(world.matches)) {
    const f = world.fixtures[fid];
    if (!f) {
      delete world.matches[fid];
      continue;
    }
    if (f.formatKey === 'final') continue;
    if (world.meta.userOrgId && f.teams.some((t) => world.teams[t]?.orgId === world.meta.userOrgId)) continue;
    if (m.detail) world.matches[fid] = { ...m, detail: false, games: m.games.map((g) => ({ ...g, events: undefined, players: [], teamStats: [] })) };
  }
  // Archive long-retired, low-legacy players to compact summaries.
  for (const p of Object.values(world.players)) {
    if (!p.retired || p.retired.season > endedSeason - 4) continue;
    const l = legacy(p);
    if (l.total >= 35 || world.hof.some((h) => h.playerId === p.id)) continue;
    world.retired.push({ id: p.id, handle: p.handle, name: `${p.first} ${p.last}`, nat: p.nat, titles: [...new Set(p.stats.map((s) => s.titleId))], retiredSeason: p.retired.season, legacy: l.total, trophies: p.awards.filter((a) => a.awardId.startsWith('champion')).length, awards: p.awards.length });
    delete world.players[p.id];
    delete world.scouting[p.id];
  }
  if (world.retired.length > 20000) world.retired.splice(0, world.retired.length - 20000);
  // Retired staff with no awards are removed after a while.
  for (const s of Object.values(world.staff)) if (s.retired && s.retired.season < endedSeason - 3 && !s.awards.length) delete world.staff[s.id];
  if (world.news.length > 400) world.news.splice(0, world.news.length - 400);
  if (world.transfers.length > 3000) world.transfers.splice(0, world.transfers.length - 3000);
  // Player stat lines: keep the last 25 seasons per player
  for (const p of Object.values(world.players)) if (p.stats.length > 60) p.stats.splice(0, p.stats.length - 60);
}

export function seasonRollover(world: World): void {
  const season = world.season;
  if (world.processed.seasonRolled.includes(season)) return;
  world.processed.seasonRolled.push(season);
  const rng = new RNG(`${world.meta.seed}|rollover|${season}`);
  // Any competition that somehow did not complete is closed with current standings.
  for (const c of Object.values(world.comps)) {
    if (c.season !== season || c.status === 'done') continue;
    c.status = 'done';
    c.placements = c.stages.find((s) => s.status !== 'pending')?.standings.map((r) => r.teamId) ?? c.teamIds;
  }
  seasonAwards(world, season);
  snapshotHistory(world, season);
  promotionRelegation(world, season, rng);
  yearlyDevelopment(world, rng);
  retirements(world, rng);
  expireContracts(world, season);
  staffAging(world, rng);
  popularityDrift(world, rng);
  orgEvolution(world, rng);
  for (const org of Object.values(world.orgs)) if (org.active) renewSponsors(world, org, rng);
  hallOfFame(world, season + 1);
  world.season = season + 1;
  regenerate(world, rng);
  hireMissingStaff(world, rng);
  // New-season resets
  for (const team of Object.values(world.teams)) {
    if (!team.active) continue;
    team.chemistry = clamp(team.chemistry * 0.85 + 8, 0, 100);
    const org = world.orgs[team.orgId];
    let pay = 0;
    for (const pid of team.roster) pay += world.players[pid]?.contract?.salary ?? 0;
    const growth = org && org.balance > 0 ? 1.15 + Math.min(0.3, org.balance / 5e6) : 0.95;
    team.salaryBudget = Math.round(Math.max(pay * growth, pay + 20000) / 1000) * 1000;
    team.lineup = autoLineup(world, team);
  }
  for (const p of Object.values(world.players)) {
    if (p.retired) continue;
    p.fatigue = Math.min(p.fatigue, 10);
    p.form = 0;
  }
  for (const id of world.meta.titlesEnabled) releasePatch(world, id, true);
  planSeason(world, world.season);
  prune(world, season);
  if (world.meta.userOrgId) {
    addInbox(world, { from: 'Board', subject: `Welcome to the ${world.season} season`, body: `The ${season} season is archived in History. The off-season transfer window is open until 12 January. Review contracts, recruit and set training before competition begins.`, kind: 'season' });
  }
}

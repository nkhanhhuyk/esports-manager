/**
 * Awards, trophies, competition rewards, legacy (GOAT) scores and Hall of Fame.
 *
 * Legacy points are stamped at the moment an achievement happens and include the title's
 * prestige *at that time*, so cross-title / cross-era greatness respects changing popularity
 * without equating raw statistics between games with different rules.
 */
import { clamp } from '../core/math';
import { getTitle } from '../data/titles/catalogue';
import type { AwardRecord, Competition, Player, SeasonAward, Staff, World } from '../model/types';
import { addInbox, addNews } from './news';
import { ledger } from './finance';
import { headCoach } from '../sim/common/team';

const PRIZE_WEIGHTS = [0.32, 0.18, 0.11, 0.09, 0.06, 0.06, 0.04, 0.04, 0.02, 0.02, 0.02, 0.02, 0.01, 0.01, 0.01, 0.01];

export function prizeFor(comp: Competition, place: number): number {
  const n = Math.min(comp.placements.length, PRIZE_WEIGHTS.length);
  const ws = PRIZE_WEIGHTS.slice(0, n);
  const tot = ws.reduce((a, b) => a + b, 0) || 1;
  return place < n ? Math.round((comp.prizePool * ws[place]) / tot) : 0;
}

function prestigeFactor(world: World, titleId: string): number {
  return 0.5 + (world.titles[titleId]?.prestige ?? 50) / 100;
}

const TROPHY_PTS: Record<string, number> = { worlds: 30, international: 15, league: 6, playoffs: 6, challenger: 1, qualifier: 0.5, cup: 3 };

export function giveAward(world: World, award: SeasonAward, pts: number): void {
  const exists = world.awards.some((a) => a.season === award.season && a.awardId === award.awardId && a.compId === award.compId && a.titleId === award.titleId && a.playerId === award.playerId && a.teamId === award.teamId && a.staffId === award.staffId);
  if (exists) return;
  world.awards.push(award);
  const rec: AwardRecord = { season: award.season, awardId: award.awardId, label: award.label, titleId: award.titleId ?? undefined, compId: award.compId, teamId: award.teamId, pts: Math.round(pts * 10) / 10 };
  if (award.playerId) world.players[award.playerId]?.awards.push(rec);
  if (award.staffId) world.staff[award.staffId]?.awards.push(rec);
}

/** Tournament MVP from in-competition aggregates. */
function compMvp(world: World, comp: Competition, topTeams?: string[]): string | undefined {
  let best: string | undefined;
  let bestScore = -Infinity;
  const maxGp = Math.max(1, ...Object.values(comp.pstats).map((s) => s.gp));
  for (const [pid, s] of Object.entries(comp.pstats)) {
    if (s.gp < maxGp * 0.5) continue;
    if (topTeams && !topTeams.includes(s.teamId)) continue;
    const place = comp.placements.indexOf(s.teamId);
    const teamBonus = place >= 0 ? 1.15 - Math.min(place, 8) * 0.03 : 0.9;
    const score = (s.r / s.gp) * teamBonus + s.m * 0.01;
    if (score > bestScore) {
      bestScore = score;
      best = pid;
    }
  }
  return best;
}

export function onCompetitionFinished(world: World, comp: Competition): void {
  if (comp.rewarded) return;
  comp.rewarded = true;
  const t = getTitle(comp.titleId);
  const pf = prestigeFactor(world, comp.titleId);
  // Prize money and reputation
  comp.placements.forEach((tid, i) => {
    const team = world.teams[tid];
    if (!team) return;
    const org = world.orgs[team.orgId];
    const prize = prizeFor(comp, i);
    if (prize > 0 && org) {
      const shares = team.roster.map((pid) => world.players[pid]?.contract?.prizeShare ?? 0.5);
      const share = shares.length ? shares.reduce((a, b) => a + b, 0) / shares.length : 0.5;
      ledger(world, org, 'prize', Math.round(prize * (1 - share * 0.6)), tid, comp.name);
    }
    if (org) {
      const repGain = (comp.prestige / 100) * (i === 0 ? 6 : i < 4 ? 2 : i < 8 ? 0.5 : -0.3);
      org.rep = clamp(org.rep + repGain, 1, 100);
      org.fans = Math.max(100, Math.round(org.fans * (1 + repGain / 60)));
    }
  });
  const champ = comp.placements[0];
  const champTeam = champ ? world.teams[champ] : null;
  if (champTeam) {
    champTeam.trophies.push({ season: comp.season, compId: comp.id, compName: comp.name, tier: comp.tier, titleId: comp.titleId });
    const org = world.orgs[champTeam.orgId];
    if (org) org.prestige += (comp.prestige / 10) * pf;
    const pts = (TROPHY_PTS[comp.tier] ?? 2) * pf;
    for (const pid of champTeam.roster) {
      const p = world.players[pid];
      if (!p) continue;
      giveAward(world, { season: comp.season, awardId: `champion-${comp.tier}`, label: `${comp.name} champion`, titleId: comp.titleId, compId: comp.id, playerId: pid, teamId: champ }, pts);
      p.morale = clamp(p.morale + 12, 0, 100);
      p.rep = clamp(p.rep + comp.prestige * 0.06, 0, 100);
      if (p.contract) {
        const bonus = p.contract.winBonus;
        if (bonus > 0 && world.orgs[champTeam.orgId]) ledger(world, world.orgs[champTeam.orgId], 'bonus', -bonus, champ, `${p.handle} trophy bonus`);
      }
    }
    const coach = headCoach(world, champTeam);
    if (coach) giveAward(world, { season: comp.season, awardId: `champion-${comp.tier}`, label: `${comp.name} champion (coach)`, titleId: comp.titleId, compId: comp.id, staffId: coach.id, teamId: champ }, pts * 0.5);
    champTeam.chemistry = clamp(champTeam.chemistry + 5, 0, 100);
  }
  // MVPs
  const mvp = compMvp(world, comp, comp.tier === 'league' || comp.tier === 'challenger' ? undefined : comp.placements.slice(0, 4));
  if (mvp) {
    comp.mvpPlayerId = mvp;
    const label = comp.tier === 'worlds' ? 'World Championship MVP' : comp.tier === 'international' ? 'Tournament MVP' : `${comp.short} MVP`;
    const pts = (comp.tier === 'worlds' ? 20 : comp.tier === 'international' ? 10 : comp.tier === 'league' ? 6 : 1.5) * pf;
    giveAward(world, { season: comp.season, awardId: comp.tier === 'worlds' ? 'worlds-mvp' : comp.tier === 'international' ? 'tournament-mvp' : 'split-mvp', label, titleId: comp.titleId, compId: comp.id, playerId: mvp, teamId: comp.pstats[mvp]?.teamId }, pts);
  }
  const finalStage = comp.stages[comp.stages.length - 1];
  const finalNode = finalStage.bracket.find((n) => n.isFinal);
  const finalFx = finalNode?.fixtureId ? world.fixtures[finalNode.fixtureId] : finalStage.type === 'br-points' ? world.fixtures[finalStage.fixtureIds[finalStage.fixtureIds.length - 1]] : undefined;
  if (finalFx?.mvp) {
    comp.finalsMvpPlayerId = finalFx.mvp;
    giveAward(world, { season: comp.season, awardId: 'finals-mvp', label: `${comp.short} Finals MVP`, titleId: comp.titleId, compId: comp.id, playerId: finalFx.mvp }, (comp.tier === 'worlds' ? 10 : comp.tier === 'international' ? 5 : 3) * pf);
  }
  // News
  if (champTeam) {
    const runner = comp.placements[1] ? world.teams[comp.placements[1]] : null;
    const big = comp.tier === 'worlds' || comp.tier === 'international';
    addNews(world, {
      kind: 'champion',
      title: `${champTeam.name} win ${comp.name}`,
      body: `${champTeam.name} claimed the title${runner ? `, beating ${runner.name} in the final` : ''}.${mvp ? ` ${world.players[mvp]?.handle} was named MVP.` : ''} Prize pool: $${comp.prizePool.toLocaleString()}.`,
      titleId: comp.titleId,
      refs: [{ type: 'team', id: champTeam.id }, { type: 'comp', id: comp.id }, ...(mvp ? [{ type: 'player' as const, id: mvp }] : [])],
      importance: big ? 5 : comp.tier === 'league' ? 3 : 2,
    });
    if (world.meta.userOrgId && comp.placements.slice(0, 8).some((tid) => world.teams[tid]?.orgId === world.meta.userOrgId)) {
      const ut = comp.placements.find((tid) => world.teams[tid]?.orgId === world.meta.userOrgId)!;
      const place = comp.placements.indexOf(ut) + 1;
      addInbox(world, { from: 'Board', subject: `${comp.name}: finished ${place === 1 ? 'as CHAMPIONS' : `#${place}`}`, body: `${world.teams[ut].name} finished ${place === 1 ? 'first' : `in position ${place}`} at ${comp.name}. Prize money: $${prizeFor(comp, place - 1).toLocaleString()}.`, kind: 'result' });
    }
  }
  void t;
}

// ---------------------------------------------------------------------------------------------
// Season awards
// ---------------------------------------------------------------------------------------------

function seasonLine(p: Player, season: number, titleId: string) {
  const lines = p.stats.filter((s) => s.season === season && s.titleId === titleId);
  if (!lines.length) return null;
  const gp = lines.reduce((a, l) => a + l.gp, 0);
  const w = lines.reduce((a, l) => a + l.w, 0);
  const rating = lines.reduce((a, l) => a + l.ratingSum, 0) / Math.max(1, gp);
  const s: Record<string, number> = {};
  for (const l of lines) for (const [k, v] of Object.entries(l.s)) s[k] = (s[k] ?? 0) + v;
  return { gp, w, rating, s };
}

export function seasonAwards(world: World, season: number): void {
  for (const titleId of world.meta.titlesEnabled) {
    const t = getTitle(titleId);
    const pf = prestigeFactor(world, titleId);
    const players = Object.values(world.players).filter((p) => !p.retired && p.stats.some((s) => s.season === season && s.titleId === titleId));
    const rows = players.map((p) => ({ p, line: seasonLine(p, season, titleId)! })).filter((r) => r.line && r.line.gp > 0);
    if (!rows.length) continue;
    const maxGp = Math.max(...rows.map((r) => r.line.gp));
    const eligible = rows.filter((r) => r.line.gp >= maxGp * 0.45);
    const poyScore = (r: (typeof rows)[number]) => r.line.rating * (0.8 + 0.4 * (r.line.w / Math.max(1, r.line.gp))) + r.p.awards.filter((a) => a.season === season && a.titleId === titleId).reduce((s, a) => s + (a.pts ?? 0), 0) * 0.004;
    const sorted = [...eligible].sort((a, b) => poyScore(b) - poyScore(a));
    if (sorted[0]) {
      const p = sorted[0].p;
      giveAward(world, { season, awardId: 'poy', label: `${t.short} Player of the Year`, titleId, playerId: p.id, teamId: p.teamId ?? undefined, value: Math.round(sorted[0].line.rating * 100) / 100 }, 15 * pf);
      addNews(world, { kind: 'award', title: `${p.handle} named ${t.short} Player of the Year`, body: `${p.handle} averaged a ${sorted[0].line.rating.toFixed(2)} rating across ${sorted[0].line.gp} games.`, titleId, refs: [{ type: 'player', id: p.id }], importance: 3 });
    }
    // Best by role (All-Pro team)
    for (const roleId of t.lineup) {
      const r = sorted.find((x) => x.p.roles[0] === roleId && x.p.titleId === titleId);
      if (r) giveAward(world, { season, awardId: `best-${roleId}`, label: `${t.short} Best ${t.roles.find((x) => x.id === roleId)?.name}`, titleId, playerId: r.p.id, teamId: r.p.teamId ?? undefined }, 5 * pf);
    }
    // Rookie of the year: first season with stats in this title
    const rookies = sorted.filter((r) => !r.p.stats.some((s) => s.titleId === titleId && s.season < season));
    if (rookies[0]) giveAward(world, { season, awardId: 'roy', label: `${t.short} Rookie of the Year`, titleId, playerId: rookies[0].p.id, teamId: rookies[0].p.teamId ?? undefined }, 3 * pf);
    // Most improved: CA delta over the season
    let mip: { p: Player; d: number } | null = null;
    for (const r of eligible) {
      const prev = [...r.p.caHistory].reverse().find((h) => h.season < season && h.titleId === titleId);
      if (!prev) continue;
      const d = r.p.ca - prev.ca;
      if (!mip || d > mip.d) mip = { p: r.p, d };
    }
    if (mip && mip.d > 1) giveAward(world, { season, awardId: 'mip', label: `${t.short} Most Improved Player`, titleId, playerId: mip.p.id, teamId: mip.p.teamId ?? undefined, value: Math.round(mip.d * 10) / 10 }, 2 * pf);
    // Title-specific statistical awards
    for (const st of t.stats.filter((x) => x.good).slice(0, 3)) {
      const leader = [...eligible].sort((a, b) => (b.line.s[st.key] ?? 0) - (a.line.s[st.key] ?? 0))[0];
      if (leader && (leader.line.s[st.key] ?? 0) > 0) giveAward(world, { season, awardId: `stat-${st.key}`, label: `${t.short} ${st.label} Leader`, titleId, playerId: leader.p.id, teamId: leader.p.teamId ?? undefined, value: Math.round(leader.line.s[st.key]) }, 1 * pf);
    }
    // Team of the year: world champion or best elo
    const worlds = Object.values(world.comps).find((c) => c.season === season && c.titleId === titleId && c.tier === 'worlds' && c.status === 'done');
    const teamOfYear = worlds?.placements[0] ?? Object.values(world.teams).filter((x) => x.titleId === titleId && x.active).sort((a, b) => b.elo - a.elo)[0]?.id;
    if (teamOfYear) {
      giveAward(world, { season, awardId: 'team-of-year', label: `${t.short} Team of the Year`, titleId, teamId: teamOfYear, orgId: world.teams[teamOfYear]?.orgId }, 0);
      const coach = headCoach(world, world.teams[teamOfYear]);
      if (coach) giveAward(world, { season, awardId: 'coach-of-year', label: `${t.short} Coach of the Year`, titleId, staffId: coach.id, teamId: teamOfYear }, 4 * pf);
    }
  }
  // Organisation of the year (multi-title)
  const orgScore: Record<string, number> = {};
  for (const c of Object.values(world.comps)) {
    if (c.season !== season || c.status !== 'done' || !c.placements[0]) continue;
    const team = world.teams[c.placements[0]];
    if (!team) continue;
    orgScore[team.orgId] = (orgScore[team.orgId] ?? 0) + c.prestige * prestigeFactor(world, c.titleId);
  }
  const bestOrg = Object.entries(orgScore).sort((a, b) => b[1] - a[1])[0];
  if (bestOrg) {
    giveAward(world, { season, awardId: 'org-of-year', label: 'Organisation of the Year', titleId: null, orgId: bestOrg[0] }, 0);
    addNews(world, { kind: 'award', title: `${world.orgs[bestOrg[0]]?.name} named Organisation of the Year`, body: `Multi-title success across the ${season} season.`, refs: [{ type: 'org', id: bestOrg[0] }], importance: 3 });
  }
}

// ---------------------------------------------------------------------------------------------
// Legacy / GOAT
// ---------------------------------------------------------------------------------------------

export interface LegacyBreakdown {
  total: number;
  achievements: number;
  peak: number;
  longevity: number;
  volume: number;
  byTitle: Record<string, number>;
}

export function legacy(p: Player, titleId?: string): LegacyBreakdown {
  const byTitle: Record<string, number> = {};
  let ach = 0;
  for (const a of p.awards) {
    if (titleId && a.titleId !== titleId) continue;
    const v = a.pts ?? 0;
    ach += v;
    if (a.titleId) byTitle[a.titleId] = (byTitle[a.titleId] ?? 0) + v;
  }
  const hist = titleId ? p.caHistory.filter((h) => h.titleId === titleId) : p.caHistory;
  const peakCa = hist.length ? Math.max(...hist.map((h) => h.ca)) : p.ca;
  const peak = Math.max(0, peakCa - 72) * 1.6;
  const seasons = new Set((titleId ? p.stats.filter((s) => s.titleId === titleId) : p.stats).filter((s) => s.gp > 0).map((s) => s.season)).size;
  const longevity = seasons * 1.2;
  const gp = (titleId ? p.stats.filter((s) => s.titleId === titleId) : p.stats).reduce((a, s) => a + s.gp, 0);
  const volume = Math.min(20, gp / 60);
  for (const s of p.stats) byTitle[s.titleId] = byTitle[s.titleId] ?? 0;
  return { total: Math.round((ach + peak + longevity + volume) * 10) / 10, achievements: ach, peak, longevity, volume, byTitle };
}

export function staffLegacy(s: Staff): number {
  return Math.round(s.awards.reduce((a, x) => a + (x.pts ?? 0), 0) * 10) / 10;
}

export const HOF_THRESHOLD = 110;

/** Induct retired players into the Hall of Fame (called at season rollover). */
export function hallOfFame(world: World, season: number): void {
  for (const p of Object.values(world.players)) {
    if (!p.retired || p.retired.season >= season) continue;
    if (world.hof.some((h) => h.playerId === p.id)) continue;
    const l = legacy(p);
    if (l.total < HOF_THRESHOLD) continue;
    const titles = [...new Set(p.stats.map((s) => s.titleId))];
    world.hof.push({ playerId: p.id, season, name: `${p.first} ${p.last}`, handle: p.handle, titles, legacy: l.total });
    giveAward(world, { season, awardId: 'hof', label: 'Hall of Fame inductee', titleId: p.titleId, playerId: p.id }, 0);
    addNews(world, { kind: 'award', title: `${p.handle} inducted into the Hall of Fame`, body: `A legacy score of ${l.total.toFixed(0)} across ${titles.length} title(s).`, titleId: p.titleId, refs: [{ type: 'player', id: p.id }], importance: 4 });
  }
}

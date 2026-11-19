/**
 * Career roles, permissions, board objectives and job security.
 *
 * Roles decide what the user controls; everything else is handled by AI staff (head coach for
 * lineups/drafts/training, GM for transfers/contracts). The board sets objectives each season per
 * division, tracks confidence and can dismiss the user (not owners/commissioners), after which the
 * user chooses a new job from organisations that are interested.
 */
import { clamp } from '../core/math';
import { RNG } from '../core/rng';
import type { BoardObjective, CareerRole, League, Team, World } from '../model/types';
import { getTitle } from '../data/titles/catalogue';
import { computeTeamStrength } from '../sim/common/team';
import { addInbox, addNews } from './news';
import { setUserOrg } from '../gen/world';

export type Permission = 'tactics' | 'transfers' | 'contracts' | 'finance' | 'staff' | 'expansion' | 'facilities' | 'editor';

export const ROLE_LABEL: Record<CareerRole, string> = {
  'coach-gm': 'Head Coach & General Manager',
  'head-coach': 'Head Coach',
  gm: 'General Manager',
  director: 'Sporting Director',
  owner: 'Owner',
  'national-coach': 'National Team Coach',
  commissioner: 'Commissioner (sandbox)',
};

const PERMS: Record<CareerRole, Permission[]> = {
  'coach-gm': ['tactics', 'transfers', 'contracts', 'staff', 'finance', 'facilities', 'editor'],
  'head-coach': ['tactics', 'editor'],
  gm: ['transfers', 'contracts', 'staff', 'finance', 'editor'],
  director: ['transfers', 'contracts', 'staff', 'expansion', 'editor'],
  owner: ['finance', 'facilities', 'expansion', 'staff', 'editor'],
  'national-coach': ['tactics', 'editor'],
  commissioner: ['tactics', 'transfers', 'contracts', 'staff', 'finance', 'facilities', 'expansion', 'editor'],
};

export const PERMISSION_LABEL: Record<Permission, string> = {
  tactics: 'Lineups, drafts, live matches & training',
  transfers: 'Transfers, free agents, loans & trades',
  contracts: 'Contract extensions & releases',
  finance: 'Budgets & finances',
  staff: 'Hiring & releasing staff',
  expansion: 'New divisions, academies & league slots',
  facilities: 'Facility upgrades',
  editor: 'Database editor',
};

export function can(world: World, perm: Permission): boolean {
  return PERMS[world.meta.careerRole ?? 'coach-gm']?.includes(perm) ?? true;
}

/** Explanation shown when an action is outside the user's role. */
export function denyReason(world: World, perm: Permission): string {
  const who = perm === 'tactics' ? 'the head coach' : perm === 'transfers' || perm === 'contracts' ? 'the general manager' : perm === 'finance' || perm === 'facilities' ? 'the owner' : 'the board';
  return `As ${ROLE_LABEL[world.meta.careerRole]} you don't control this — ${who} handles it.`;
}

export function hasBoard(world: World): boolean {
  return world.meta.careerRole !== 'commissioner' && world.meta.careerRole !== 'owner';
}

/** Apply role-driven delegation to the user's teams (AI staff take over what the user can't do). */
export function applyRoleDelegation(world: World): void {
  const uid = world.meta.userOrgId;
  if (!uid) return;
  if (!can(world, 'tactics')) {
    for (const tid of world.orgs[uid].teams) {
      const t = world.teams[tid];
      if (!t) continue;
      t.prefs.delegateLineup = true;
      t.prefs.delegateDraft = true;
      t.prefs.delegateTraining = true;
    }
  }
}

function leagueRankByStrength(world: World, team: Team, league: League): number {
  const sorted = league.teamIds.map((id) => world.teams[id]).filter(Boolean).sort((a, b) => computeTeamStrength(world, b) - computeTeamStrength(world, a));
  return Math.max(1, sorted.findIndex((t) => t.id === team.id) + 1);
}

/** Set the board's objectives for the user's divisions at the start of a season. */
export function setBoardObjectives(world: World): void {
  const uid = world.meta.userOrgId;
  if (!uid) return;
  world.meta.board ||= { confidence: 60, objectives: [], history: [] };
  const board = world.meta.board;
  board.objectives = board.objectives.filter((o) => o.season !== world.season);
  const org = world.orgs[uid];
  for (const tid of org.teams) {
    const team = world.teams[tid];
    if (!team?.active || !team.leagueId) continue;
    const league = world.leagues[team.leagueId];
    const rank = leagueRankByStrength(world, team, league);
    const ambition = org.strategy === 'win-now' || org.strategy === 'prestige' ? 0 : org.strategy === 'youth' || org.strategy === 'moneyball' ? 2 : 1;
    const target = clamp(rank + ambition, 1, league.teamIds.length);
    board.objectives.push({ season: world.season, teamId: tid, kind: 'league-rank', target, label: `${getTitle(team.titleId).short}: finish top ${target} in ${league.name}`, status: 'pending' });
    if (league.tier === 1 && rank <= 2) board.objectives.push({ season: world.season, teamId: tid, kind: 'qualify-worlds', target: 1, label: `${getTitle(team.titleId).short}: qualify for the World Championship`, status: 'pending' });
    if (league.tier === 2 && rank <= 2) board.objectives.push({ season: world.season, teamId: tid, kind: 'league-rank', target: 1, label: `${getTitle(team.titleId).short}: win promotion (finish 1st)`, status: 'pending' });
  }
  board.objectives.push({ season: world.season, teamId: null, kind: 'finance', target: Math.round(org.balance * 0.5), label: `Keep the balance above ${Math.round((org.balance * 0.5) / 1000)}K`, status: 'pending' });
  addInbox(world, { from: 'Board', subject: `Board objectives for ${world.season}`, body: board.objectives.filter((o) => o.season === world.season).map((o) => `• ${o.label}`).join('\n'), kind: 'board' });
}

function leaguePosition(world: World, teamId: string, season: number): number | null {
  const team = world.teams[teamId];
  if (!team?.leagueId) return null;
  const pts = new Map<string, number>();
  for (const c of Object.values(world.comps)) {
    if (c.season !== season || c.leagueId !== team.leagueId) continue;
    // Final placements of both splits (playoffs included) decide the season ranking.
    c.placements.forEach((tid, i) => pts.set(tid, (pts.get(tid) ?? 0) + i));
  }
  if (!pts.has(teamId)) return null;
  const order = [...pts.entries()].sort((a, b) => a[1] - b[1]).map(([t]) => t);
  return order.indexOf(teamId) + 1;
}

/** Evaluate objectives & update confidence at season end; may dismiss the user. Returns true if sacked. */
export function boardReview(world: World, season: number): boolean {
  const uid = world.meta.userOrgId;
  const board = world.meta.board;
  if (!uid || !board || !hasBoard(world)) return false;
  const org = world.orgs[uid];
  let delta = 0;
  const lines: string[] = [];
  for (const o of board.objectives.filter((x) => x.season === season)) {
    let met = false;
    if (o.kind === 'league-rank' && o.teamId) {
      const pos = leaguePosition(world, o.teamId, season);
      met = pos !== null && pos <= o.target;
      if (pos !== null) delta += met ? 10 + (o.target - pos) * 3 : -12 - (pos - o.target) * 2;
    } else if (o.kind === 'qualify-worlds' && o.teamId) {
      met = Object.values(world.comps).some((c) => c.season === season && c.tier === 'worlds' && c.teamIds.includes(o.teamId!));
      delta += met ? 10 : -10;
    } else if (o.kind === 'finance') {
      met = org.balance >= o.target;
      delta += met ? 6 : -15;
    }
    o.status = met ? 'met' : 'failed';
    lines.push(`${met ? '✓' : '✗'} ${o.label}`);
  }
  const trophies = Object.values(world.teams).filter((t) => t.orgId === uid).reduce((a, t) => a + t.trophies.filter((x) => x.season === season).length, 0);
  delta += trophies * 8;
  board.confidence = clamp(board.confidence * 0.7 + 18 + delta, 0, 100);
  const sacked = board.confidence < 20;
  board.history.push({ season, confidence: Math.round(board.confidence), verdict: sacked ? 'dismissed' : board.confidence > 70 ? 'delighted' : board.confidence > 45 ? 'satisfied' : 'concerned' });
  addInbox(world, {
    from: 'Board',
    subject: sacked ? 'You have been dismissed' : `Season review: board ${board.history[board.history.length - 1].verdict}`,
    body: `${lines.join('\n')}\nTrophies this season: ${trophies}. Board confidence: ${Math.round(board.confidence)}/100.${sacked ? '\n\nThe board has decided to part ways with you. Several organisations have expressed interest.' : ''}`,
    kind: 'board',
  });
  if (sacked) {
    world.meta.unemployed = true;
    addNews(world, { kind: 'org', title: `${org.name} part ways with ${world.meta.managerName}`, body: 'Missed objectives cost the manager their job.', refs: [{ type: 'org', id: org.id }], importance: 3 });
  }
  return sacked;
}

/** Organisations willing to hire the user (after dismissal or when looking for a move). */
export function jobOffers(world: World): string[] {
  const rng = new RNG(`${world.meta.seed}|jobs|${world.day}`);
  const prevRep = world.meta.userOrgId ? world.orgs[world.meta.userOrgId]?.rep ?? 40 : 40;
  const cands = Object.values(world.orgs).filter((o) => o.active && !o.federation && o.id !== world.meta.userOrgId && o.teams.length && o.rep <= prevRep + 5);
  return rng.sample(cands, Math.min(6, cands.length)).map((o) => o.id);
}

export function takeJob(world: World, orgId: string): void {
  const old = world.meta.userOrgId;
  if (old && world.orgs[old]) world.orgs[old].user = false;
  setUserOrg(world, orgId);
  world.meta.unemployed = false;
  world.meta.board = { confidence: 55, objectives: [], history: world.meta.board?.history ?? [] };
  applyRoleDelegation(world);
  setBoardObjectives(world);
  addNews(world, { kind: 'org', title: `${world.orgs[orgId].name} appoint ${world.meta.managerName}`, body: `New ${ROLE_LABEL[world.meta.careerRole]}.`, refs: [{ type: 'org', id: orgId }], importance: 2 });
}

export type { BoardObjective };

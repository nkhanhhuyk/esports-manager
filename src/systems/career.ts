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
import type { BoardObjective, CareerRole, Competition, JobApproach, League, Team, World } from '../model/types';
import { nextId } from '../model/ids';
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
  if (world.meta.managerContract?.orgId !== uid) world.meta.managerContract = { orgId: uid, endSeason: world.season + MANAGER_YEARS - 1, since: world.day };
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
  board.ultimatum = null;
  const sacked = board.confidence < 20;
  // The manager's contract: renewed when the board is happy enough, otherwise allowed to run out.
  const mc = world.meta.managerContract;
  const expiring = !sacked && !!mc && mc.orgId === uid && mc.endSeason <= season;
  const renewed = expiring && board.confidence >= RENEW_AT;
  const released = expiring && !renewed;
  if (renewed) mc!.endSeason = season + MANAGER_YEARS;
  const verdict = sacked ? 'dismissed' : released ? 'contract not renewed' : board.confidence > 70 ? 'delighted' : board.confidence > 45 ? 'satisfied' : 'concerned';
  board.history.push({ season, confidence: Math.round(board.confidence), verdict });
  const tail = sacked
    ? '\n\nThe board has decided to part ways with you. Several organisations have expressed interest.'
    : released
      ? `\n\nYour contract expires and the board has decided not to renew it (confidence below ${RENEW_AT}).`
      : renewed
        ? `\n\nThe board rewards you with a new contract until the end of season ${mc!.endSeason}.`
        : mc && mc.orgId === uid
          ? `\nYour contract runs until the end of season ${mc.endSeason}.`
          : '';
  addInbox(world, {
    from: 'Board',
    subject: sacked ? 'You have been dismissed' : released ? 'Your contract will not be renewed' : `Season review: board ${verdict}`,
    body: `${lines.join('\n')}\nTrophies this season: ${trophies}. Board confidence: ${Math.round(board.confidence)}/100.${tail}`,
    kind: 'board',
  });
  if (sacked || released) dismissUser(world, sacked ? 'Missed objectives cost the manager their job.' : 'The manager leaves as their contract runs out.');
  return sacked || released;
}

// ---------------------------------------------------------------------------------------------
// Mid-season board reactions, manager contract, job approaches
// ---------------------------------------------------------------------------------------------

export const MANAGER_YEARS = 2;
export const RENEW_AT = 40;
export const ULTIMATUM_AT = 30;
export const DISMISS_MIDSEASON = 12;

const ORD = (n: number) => `${n}${n % 100 >= 11 && n % 100 <= 13 ? 'th' : ['th', 'st', 'nd', 'rd'][n % 10] ?? 'th'}`;

function boardNote(world: World, text: string, delta: number): void {
  const board = world.meta.board!;
  board.notes ??= [];
  board.notes.push({ day: world.day, season: world.season, text, delta });
  if (board.notes.length > 30) board.notes.splice(0, board.notes.length - 30);
}

/** End the user's employment (season review or mid-season). The AI staff take the teams over. */
export function dismissUser(world: World, why: string): void {
  const uid = world.meta.userOrgId;
  if (!uid) return;
  const org = world.orgs[uid];
  world.meta.unemployed = true;
  world.pendingUserMatch = null;
  if (org) {
    org.user = false;
    for (const tid of org.teams) {
      const t = world.teams[tid];
      if (!t) continue;
      t.prefs.delegateLineup = true;
      t.prefs.delegateDraft = true;
      t.prefs.delegateTraining = true;
    }
    addNews(world, { kind: 'org', title: `${org.name} part ways with ${world.meta.managerName}`, body: why, refs: [{ type: 'org', id: org.id }], importance: 3 });
  }
  for (const a of world.meta.jobApproaches ?? []) if (a.status === 'pending') a.status = 'expired';
}

const TROPHY_DELTA: Partial<Record<Competition['tier'], number>> = { worlds: 10, international: 6, playoffs: 5, league: 4, cup: 3, challenger: 3, qualifier: 1 };

/**
 * The board reacts when a competition involving the user's teams finishes: trophies are praised,
 * league splits are judged against the season objective (the final split is left to the season
 * review). Poor runs bring warnings, then an ultimatum; failing after an ultimatum ends the job
 * mid-season. Excellent results attract approaches from bigger organisations.
 */
export function boardCompetitionReaction(world: World, comp: Competition): void {
  const uid = world.meta.userOrgId;
  const board = world.meta.board;
  if (!uid || !board || !hasBoard(world) || world.meta.unemployed || comp.season !== world.season) return;
  const mine = comp.teamIds.filter((tid) => world.teams[tid]?.orgId === uid);
  if (!mine.length) return;
  const lines: string[] = [];
  let delta = 0;
  // How the board judges the result; the final split's effect is left to the season review.
  let judged = 0;
  let excellent = false;
  const champ = comp.placements[0];
  if (champ && mine.includes(champ)) {
    delta += TROPHY_DELTA[comp.tier] ?? 3;
    judged += TROPHY_DELTA[comp.tier] ?? 3;
    excellent = comp.tier !== 'qualifier';
    lines.push(`🏆 ${world.teams[champ].name} won ${comp.name}`);
  }
  if (comp.leagueId) {
    const final = !Object.values(world.comps).some((c) => c.id !== comp.id && c.leagueId === comp.leagueId && c.season === comp.season && c.status !== 'done');
    for (const tid of mine) {
      const o = board.objectives.find((x) => x.season === comp.season && x.teamId === tid && x.kind === 'league-rank');
      const pos = comp.placements.indexOf(tid) + 1;
      if (!o || pos <= 0) continue;
      const met = pos <= o.target;
      const d = met ? 3 + Math.min(4, o.target - pos) : -(3 + 2 * Math.min(5, pos - o.target));
      judged += d;
      if (!final) delta += d;
      if (pos <= Math.max(1, o.target - 2)) excellent = true;
      lines.push(`${met ? '✓' : '✗'} ${world.teams[tid].name}: ${ORD(pos)} in ${comp.name} (target: top ${o.target})${final ? ' — the season review will judge the final standings' : ''}`);
    }
  }
  if (!lines.length) return;
  board.confidence = clamp(board.confidence + delta, 0, 100);
  boardNote(world, `${comp.name}: ${delta > 0 ? '+' : ''}${delta}`, delta);
  const conf = Math.round(board.confidence);
  if (delta < 0 && board.confidence < DISMISS_MIDSEASON && board.ultimatum?.season === comp.season && board.ultimatum.day < world.day) {
    addInbox(world, { from: 'Board', subject: 'You have been dismissed', body: `${lines.join('\n')}\n\nAfter our final warning, results have not improved. The board has terminated your contract with immediate effect (confidence ${conf}/100).`, kind: 'board' });
    board.history.push({ season: world.season, confidence: conf, verdict: 'dismissed (mid-season)' });
    dismissUser(world, 'Poor results after a board ultimatum cost the manager their job mid-season.');
    return;
  }
  let extra = '';
  if (delta < 0 && board.confidence < ULTIMATUM_AT && board.ultimatum?.season !== comp.season) {
    board.ultimatum = { season: comp.season, day: world.day };
    extra = '\n\n⚠ ULTIMATUM: the board expects an immediate improvement. Another poor result this season will end your tenure.';
  }
  const tone = judged >= 6 ? 'delighted' : judged >= 0 ? 'satisfied' : judged > -8 ? 'concerned' : 'alarmed';
  addInbox(world, { from: 'Board', subject: `Board ${tone}: ${comp.name}`, body: `${lines.join('\n')}\nBoard confidence: ${conf}/100${delta !== judged ? ' (final standings are judged at the season review)' : ` (${delta >= 0 ? '+' : ''}${delta})`}.${extra}`, kind: 'board' });
  if (excellent) maybeJobApproach(world, `impressed by ${comp.name}`);
}

/** Monthly: the board watches the bank balance against its finance objective. */
export function boardMonthly(world: World): void {
  const uid = world.meta.userOrgId;
  const board = world.meta.board;
  for (const a of world.meta.jobApproaches ?? []) if (a.status === 'pending' && a.expiresDay < world.day) a.status = 'expired';
  if (!uid || !board || !hasBoard(world) || world.meta.unemployed) return;
  const org = world.orgs[uid];
  const fin = board.objectives.find((o) => o.season === world.season && o.kind === 'finance');
  if (org.balance < 0) {
    board.confidence = clamp(board.confidence - 3, 0, 100);
    boardNote(world, 'Negative bank balance: -3', -3);
    if (board.financeWarned !== world.season) {
      board.financeWarned = world.season;
      addInbox(world, { from: 'Board', subject: 'Board alarmed by finances', body: `The organisation is overdrawn ($${Math.round(org.balance).toLocaleString()}). Every month in the red costs board confidence; cut costs or sell players.`, kind: 'board' });
    }
  } else if (fin && org.balance < fin.target && board.financeWarned !== world.season) {
    board.financeWarned = world.season;
    boardNote(world, 'Balance below the finance objective', 0);
    addInbox(world, { from: 'Board', subject: 'Board concerned about finances', body: `The balance ($${Math.round(org.balance).toLocaleString()}) is below the board's target of $${fin.target.toLocaleString()} for this season.`, kind: 'board' });
  }
}

/** A bigger organisation may approach the user after excellent results (at most one open approach, 60-day gap). */
export function maybeJobApproach(world: World, reason: string, force = false): JobApproach | null {
  const uid = world.meta.userOrgId;
  if (!uid || world.meta.unemployed || !hasBoard(world)) return null;
  const list = (world.meta.jobApproaches ??= []);
  if (list.some((a) => a.status === 'pending' && a.expiresDay >= world.day)) return null;
  const last = list.reduce((m, a) => Math.max(m, a.day), -Infinity);
  if (!force && last > world.day - 60) return null;
  const rng = RNG.derive(world.meta.seed, 'approach', world.day, uid, list.length);
  if (!force && !rng.chance(0.35)) return null;
  const rep = world.orgs[uid].rep;
  const pool = Object.values(world.orgs).filter((o) => o.active && !o.federation && !o.user && o.id !== uid && o.teams.some((t) => world.teams[t]?.active));
  const bigger = pool.filter((o) => o.rep > rep + 3 && o.rep <= rep + 30);
  const cands = bigger.length ? bigger : pool.filter((o) => o.rep > rep);
  if (!cands.length) return null;
  const org = rng.pick(cands);
  const a: JobApproach = { id: nextId(world, 'ja'), orgId: org.id, day: world.day, expiresDay: world.day + 10, years: rng.int(2, 3), reason, status: 'pending' };
  list.push(a);
  if (list.length > 20) list.splice(0, list.length - 20);
  addInbox(world, {
    from: org.name,
    subject: `Job offer from ${org.name}`,
    body: `${org.name} (reputation ${Math.round(org.rep)}) are ${reason} and offer you the ${ROLE_LABEL[world.meta.careerRole]} job on a ${a.years}-season contract. Accepting moves you immediately; the offer stands until the deadline.`,
    actions: [
      { id: 'accept', label: `Join ${org.name}` },
      { id: 'decline', label: 'Stay' },
    ],
    payload: { approachId: a.id },
    kind: 'board',
  });
  addNews(world, { kind: 'org', title: `${org.name} linked with ${world.meta.managerName}`, body: `Reports suggest ${org.name} want to appoint the ${world.orgs[uid].name} ${ROLE_LABEL[world.meta.careerRole].toLowerCase()}.`, refs: [{ type: 'org', id: org.id }, { type: 'org', id: uid }], importance: 1 });
  return a;
}

export function respondToApproach(world: World, approachId: string, accept: boolean): { ok: boolean; message: string } {
  const a = (world.meta.jobApproaches ?? []).find((x) => x.id === approachId);
  if (!a || a.status !== 'pending') return { ok: false, message: 'The offer is no longer available.' };
  if (a.expiresDay < world.day) {
    a.status = 'expired';
    return { ok: false, message: 'The offer has expired.' };
  }
  const org = world.orgs[a.orgId];
  if (!org?.active || org.user) {
    a.status = 'expired';
    return { ok: false, message: 'The offer has been withdrawn.' };
  }
  if (!accept) {
    a.status = 'declined';
    if (world.meta.board) {
      world.meta.board.confidence = clamp(world.meta.board.confidence + 2, 0, 100);
      boardNote(world, `Turned down ${org.name}: +2`, 2);
    }
    return { ok: true, message: `You turn down ${org.name}. The board appreciates your loyalty.` };
  }
  a.status = 'accepted';
  const from = world.meta.userOrgId ? world.orgs[world.meta.userOrgId]?.name : null;
  takeJob(world, a.orgId, a.years);
  return { ok: true, message: `You leave ${from ?? 'your club'} and join ${org.name}.` };
}

/** Organisations willing to hire the user (after dismissal or when looking for a move). */
export function jobOffers(world: World): string[] {
  const rng = new RNG(`${world.meta.seed}|jobs|${world.day}`);
  const prevRep = world.meta.userOrgId ? world.orgs[world.meta.userOrgId]?.rep ?? 40 : 40;
  const cands = Object.values(world.orgs).filter((o) => o.active && !o.federation && o.id !== world.meta.userOrgId && o.teams.length && o.rep <= prevRep + 5);
  return rng.sample(cands, Math.min(6, cands.length)).map((o) => o.id);
}

export function takeJob(world: World, orgId: string, years = MANAGER_YEARS): void {
  const old = world.meta.userOrgId;
  if (old && world.orgs[old]) {
    world.orgs[old].user = false;
    for (const tid of world.orgs[old].teams) {
      const t = world.teams[tid];
      if (!t) continue;
      t.prefs.delegateLineup = true;
      t.prefs.delegateDraft = true;
      t.prefs.delegateTraining = true;
    }
  }
  world.pendingUserMatch = null;
  setUserOrg(world, orgId);
  world.meta.unemployed = false;
  world.meta.managerContract = { orgId, endSeason: world.season + Math.max(1, years) - 1, since: world.day };
  world.meta.board = { confidence: 55, objectives: [], history: world.meta.board?.history ?? [], notes: [] };
  applyRoleDelegation(world);
  setBoardObjectives(world);
  addNews(world, { kind: 'org', title: `${world.orgs[orgId].name} appoint ${world.meta.managerName}`, body: `New ${ROLE_LABEL[world.meta.careerRole]}.`, refs: [{ type: 'org', id: orgId }], importance: 2 });
}

export type { BoardObjective };

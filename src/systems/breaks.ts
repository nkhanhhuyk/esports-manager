/**
 * Players taking a break mid-season (burnout, mental health, personal or family reasons) or
 * stepping away for the rest of the season. Proposed constants (editable):
 *
 *  weekly chance during the competitive season (mid-January to mid-November) =
 *    (0.0006 + 0.012·max(0, fatigue − 55)/45 + 0.008·max(0, 40 − morale)/40 + 0.003·[unhappy > 70])
 *    × (1.4 − 0.8 · mental resilience/100)
 *  — about 2–3% of players a season, far more for exhausted or miserable ones.
 *  Breaks last 1–6 weeks by reason; 15% (30% for personal/family reasons) are for the rest of the
 *  season. The player stays under contract (salary paid) but cannot play or train; fatigue and
 *  morale recover while away. Clubs replace long absentees through the normal market.
 *
 *  A player at the user's club asks first (inbox: grant / ask to stay; no answer in 7 days = he
 *  takes it). Refusing costs morale and goodwill, and an exhausted player (fatigue > 75) breaks
 *  down anyway. Managers can also rest a player for 1–4 weeks and bring him back early.
 */
import { clamp } from '../core/math';
import { RNG } from '../core/rng';
import { dayFromYMD, formatDate } from '../core/date';
import { seasonEndDay } from '../competition/season';
import type { Player, Team, World } from '../model/types';
import { autoLineup } from './lineup';
import { addInbox, addNews, isUserTeam } from './news';
import { can } from './career';
import { isFollowed } from './follow';
import { onLeave } from './availability';

const REASONS = {
  burnout: 'Burnout',
  mental: 'Mental health break',
  motivation: 'Recharging motivation',
  personal: 'Personal reasons',
  family: 'Family matters',
} as const;

export function inCompetitiveSeason(world: World): boolean {
  return world.day >= dayFromYMD(world.season, 1, 10) && world.day <= seasonEndDay(world.season) - 7;
}

/** Weekly probability that a player asks for a break. */
export function breakRisk(p: Player): number {
  let r = 0.0006;
  if (p.fatigue > 55) r += ((p.fatigue - 55) / 45) * 0.012;
  if (p.morale < 40) r += ((40 - p.morale) / 40) * 0.008;
  if (p.unhappy > 70) r += 0.003;
  return r * (1.4 - (0.8 * p.attrs[40]) / 100);
}

function leaveText(p: Player, until: number, kind: 'break' | 'hiatus'): string {
  return kind === 'hiatus' ? `${p.handle} steps away for the rest of the season` : `${p.handle} is back around ${formatDate(until)}`;
}

/** Start a break (or a hiatus until the season ends). */
export function startLeave(world: World, p: Player, kind: 'break' | 'hiatus', reason: string, days: number, byClub = false): void {
  const until = kind === 'hiatus' ? seasonEndDay(world.season) : world.day + days;
  p.leave = { kind, reason, fromDay: world.day, untilDay: until, byClub };
  p.leaveRequest = null;
  const team = p.teamId ? world.teams[p.teamId] : null;
  if (team) {
    team.lineup = team.lineup.filter((s) => s.playerId !== p.id);
    if (team.prefs.delegateLineup || !isUserTeam(world, team.id)) team.lineup = autoLineup(world, team);
  }
  const body = `${reason}. ${leaveText(p, until, kind)}. He stays under contract but will not play or train until then.`;
  if (team && (isUserTeam(world, team.id) || isFollowed(world, team.id)) && !byClub) addInbox(world, { from: team.short, subject: `${p.handle} ${kind === 'hiatus' ? 'steps away for the season' : 'takes a break'}`, body, kind: 'player' });
  if (p.rep >= 60 && !byClub) addNews(world, { kind: 'world', title: `${p.handle} ${kind === 'hiatus' ? 'steps away for the rest of the season' : 'takes a break from competition'}`, body: `${team ? `${team.name}'s ` : ''}${p.handle}: ${body}`, titleId: p.titleId, refs: [{ type: 'player', id: p.id }, ...(team ? [{ type: 'team' as const, id: team.id }] : [])], importance: p.rep >= 75 ? 3 : 2 });
}

function endLeave(world: World, p: Player): void {
  const was = p.leave!;
  p.leave = null;
  p.form = 0;
  p.morale = Math.max(p.morale, 62);
  const team = p.teamId ? world.teams[p.teamId] : null;
  if (team && (team.prefs.delegateLineup || !isUserTeam(world, team.id))) team.lineup = autoLineup(world, team);
  if (team && isUserTeam(world, team.id) && was.kind === 'break') addInbox(world, { from: team.short, subject: `${p.handle} is back`, body: `${p.handle} returns from the break (${was.reason.toLowerCase()}) refreshed and available for selection.`, kind: 'player' });
}

/** Daily: recovery while away; breaks end. */
export function breaksDaily(world: World): void {
  for (const p of Object.values(world.players)) {
    if (!p.leave) continue;
    if (p.retired) {
      p.leave = null;
      continue;
    }
    if (p.leave.untilDay <= world.day) {
      endLeave(world, p);
      continue;
    }
    if (!onLeave(p, world.day)) continue;
    p.fatigue = Math.max(0, p.fatigue - 3);
    p.morale = clamp(p.morale + (70 - p.morale) * 0.04, 0, 100);
    p.unhappy = Math.max(0, p.unhappy - 0.5);
  }
}

/** The user's club decides breaks unless the head-coach side is not the user's (role). */
function userDecides(world: World, team: Team): boolean {
  return isUserTeam(world, team.id) && can(world, 'tactics');
}

/** Weekly: some players ask for (or take) a break; unanswered requests start after 7 days. */
export function breaksWeekly(world: World): void {
  const rng = new RNG(`${world.meta.seed}|breaks|${world.day}`);
  const season = inCompetitiveSeason(world);
  for (const p of Object.values(world.players)) {
    if (p.retired || !p.teamId || !p.contract) continue;
    if (p.leaveRequest) {
      if (world.day - p.leaveRequest.day >= 7) {
        const r = p.leaveRequest;
        startLeave(world, p, r.kind, `${r.reason} (no answer from the club)`, r.days);
      }
      continue;
    }
    if (!season || p.leave || (p.injury && p.injury.untilDay > world.day)) continue;
    const team = world.teams[p.teamId];
    if (!team || team.national) continue;
    if (!rng.chance(breakRisk(p))) continue;
    const reason = p.fatigue > 65 ? REASONS.burnout : p.morale < 35 ? rng.pick([REASONS.mental, REASONS.motivation]) : rng.pick([REASONS.personal, REASONS.family, REASONS.mental]);
    const personal = reason === REASONS.personal || reason === REASONS.family;
    const kind: 'break' | 'hiatus' = rng.chance(personal ? 0.3 : 0.15) ? 'hiatus' : 'break';
    const weeks = reason === REASONS.burnout ? rng.int(3, 6) : reason === REASONS.personal || reason === REASONS.family ? rng.int(1, 4) : rng.int(2, 5);
    if (userDecides(world, team)) {
      p.leaveRequest = { kind, reason, days: weeks * 7, day: world.day };
      addInbox(world, {
        from: p.handle,
        subject: `${p.handle} asks for ${kind === 'hiatus' ? 'the rest of the season off' : 'a break'}`,
        body: `${reason}. ${p.handle} asks to ${kind === 'hiatus' ? 'step away for the rest of the season' : `take about ${weeks} week${weeks > 1 ? 's' : ''} off`} (fatigue ${Math.round(p.fatigue)}, morale ${Math.round(p.morale)}).\nGranting it keeps the relationship healthy; asking him to stay costs morale${p.fatigue > 75 ? ' — and he is close to breaking down' : ''}. Without an answer within 7 days he takes the break.`,
        kind: 'player',
        actions: [
          { id: 'grant', label: 'Grant the break' },
          { id: 'refuse', label: 'Ask him to stay' },
        ],
        payload: { breakPlayerId: p.id },
      });
    } else startLeave(world, p, kind, reason, weeks * 7);
  }
}

export interface BreakResult {
  ok: boolean;
  message: string;
}

/** The user's answer to a break request. */
export function decideBreakRequest(world: World, playerId: string, grant: boolean): BreakResult {
  const p = world.players[playerId];
  const r = p?.leaveRequest;
  if (!p || !r) return { ok: false, message: 'The request is no longer open.' };
  if (grant) {
    startLeave(world, p, r.kind, r.reason, r.days);
    p.morale = clamp(p.morale + 4, 0, 100);
    return { ok: true, message: `${p.handle} ${r.kind === 'hiatus' ? 'steps away for the rest of the season' : 'takes the break'}.` };
  }
  p.leaveRequest = null;
  if (r.reason === REASONS.burnout && p.fatigue > 75) {
    startLeave(world, p, 'break', 'Burnout — he could not continue', 14);
    p.unhappy = clamp(p.unhappy + 10, 0, 100);
    return { ok: true, message: `${p.handle} was in no state to continue and is out for two weeks anyway.` };
  }
  p.morale = clamp(p.morale - 8, 0, 100);
  p.unhappy = clamp(p.unhappy + (r.kind === 'hiatus' ? 25 : 12), 0, 100);
  p.form = clamp(p.form - 2, -10, 10);
  return { ok: true, message: `${p.handle} stays, but is not happy about it.` };
}

/** Manager rests a player for a number of weeks (club decision). */
export function restPlayer(world: World, playerId: string, weeks: number): BreakResult {
  const p = world.players[playerId];
  if (!p || !p.teamId || !isUserTeam(world, p.teamId)) return { ok: false, message: 'Only your own players.' };
  if (p.leave && p.leave.untilDay > world.day) return { ok: false, message: `${p.handle} is already away.` };
  const tired = p.fatigue > 50;
  startLeave(world, p, 'break', 'Rest (club decision)', Math.max(1, Math.round(weeks)) * 7, true);
  // Exhausted players appreciate it; ambitious players in form would rather play.
  p.morale = clamp(p.morale + (tired ? 3 : -(p.pers.ambition / 100) * 4), 0, 100);
  return { ok: true, message: `${p.handle} rests for ${weeks} week${weeks > 1 ? 's' : ''}.` };
}

/** Bring a player back early from a rest the club ordered. */
export function recallPlayer(world: World, playerId: string): BreakResult {
  const p = world.players[playerId];
  if (!p?.leave || !p.teamId || !isUserTeam(world, p.teamId)) return { ok: false, message: 'Not away.' };
  if (!p.leave.byClub) return { ok: false, message: `${p.handle} asked for this break; it ends ${p.leave.kind === 'hiatus' ? 'at the end of the season' : `on ${formatDate(p.leave.untilDay)}`}.` };
  p.leave.untilDay = world.day;
  endLeave(world, p);
  return { ok: true, message: `${p.handle} is back in the squad.` };
}

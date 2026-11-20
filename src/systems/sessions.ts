/**
 * Training sessions and scrims (master prompt §9 coaches/training; source §17).
 *
 * A week has 14 practice blocks (morning and afternoon, Monday–Sunday). Coaches build the
 * timetable from the development objective, the fixture calendar (match days, the opponent,
 * tapering before big events), fatigue and the patch; a manager who has not delegated training
 * uses their own weekly template instead. Practice is a scarce resource:
 *
 *  - Practice rooms: an organisation has ceil(training facility / 2) rooms shared by all its
 *    divisions in a block; main teams go first, then academies. Blocks without a room are
 *    cancelled. Reviews, opponent preparation and mental sessions use meeting space.
 *  - Coaching capacity: head/academy coach 10 sessions a week, assistant 8, analyst 6,
 *    psychologist and performance coach 4; organisation-level staff are shared between divisions.
 *    Coached sessions beyond capacity run unsupervised at half value.
 *  - Match days: the afternoon is the match; only light sessions (rest, preparation, review,
 *    mental) can run that morning.
 *  - Scrims are booked with real partner teams of the same title that want a scrim in the same
 *    block (similar strength first, same region preferred, upcoming opponents avoided by default).
 *    Partners can cancel at short notice; a team with an academy can fall back to an internal
 *    scrim. Scrimming leaks knowledge: both sides gain intel on each other for 35 days, a small
 *    preparation edge if they meet officially.
 *
 * Cancelled sessions grant nothing ("no phantom growth"): development, proficiencies, practice
 * sharpness, meta adaptation and chemistry are computed only from blocks that were held.
 */
import { clamp } from '../core/math';
import { RNG } from '../core/rng';
import { getTitle } from '../data/titles/catalogue';
import type { Organisation, SessionSlot, SessionType, Team, TrainingFocus, TrainingWeek, World } from '../model/types';
import { isWatched } from './follow';
import { isUserTeam } from './news';

export interface SessionDef {
  label: string;
  short: string;
  /** Training load (fatigue); rest is negative. */
  load: number;
  /** Development value of one held block. */
  dev: number;
  room: boolean;
  coached: boolean;
  desc: string;
}

export const SESSIONS: Record<SessionType, SessionDef> = {
  rest: { label: 'Rest & recovery', short: 'Rest', load: -1, dev: 0, room: false, coached: false, desc: 'Lowers fatigue; no development.' },
  individual: { label: 'Individual work', short: 'Indiv.', load: 1, dev: 1, room: true, coached: true, desc: "Each player's own focus (or the team objective) — the main source of attribute growth." },
  tactics: { label: 'Tactics & set plays', short: 'Tactics', load: 0.8, dev: 0.7, room: true, coached: true, desc: 'Tactical combinations; practice sharpness and chemistry.' },
  scrim: { label: 'Scrim', short: 'Scrim', load: 1, dev: 0.6, room: true, coached: false, desc: 'Practice match against another team: sharpness, meta knowledge, chemistry. Value depends on the partner. Leaks your strategies to that partner.' },
  review: { label: 'Scrim & VOD review', short: 'Review', load: 0.3, dev: 0.3, room: false, coached: true, desc: 'Multiplies what scrims teach (up to +50% with one review per two scrims).' },
  pool: { label: 'Hero / agent pool', short: 'Pool', load: 0.8, dev: 0.5, room: true, coached: true, desc: 'Character proficiency for each player’s role.' },
  maps: { label: 'Maps, sides & set-ups', short: 'Maps', load: 0.7, dev: 0.5, room: true, coached: true, desc: 'Map proficiency (shooters) and objective set-ups (MOBA).' },
  prep: { label: 'Opponent preparation', short: 'Prep', load: 0.4, dev: 0.2, room: false, coached: true, desc: 'Analyst-led preparation for this week’s opponents (two sessions = fully prepared).' },
  patch: { label: 'Patch adaptation', short: 'Patch', load: 0.5, dev: 0.3, room: true, coached: true, desc: 'Learns the current patch: meta adaptation.' },
  mental: { label: 'Mental skills & meetings', short: 'Mental', load: 0.2, dev: 0.2, room: false, coached: true, desc: 'Morale and composure work.' },
};
export const SESSION_TYPES = Object.keys(SESSIONS) as SessionType[];
export const DAY_LABEL = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
export const SLOTS = 14;
/** Development points of a typical in-season coach timetable (devFactor 1 at this level). */
export const DEV_REF = 6;
const DEV_CAP = 1.05;
const INTEL_DAYS = 35;
const PARTNER_CANCEL = 0.07;
const LIGHT: SessionType[] = ['rest', 'prep', 'review', 'mental'];

export function slotLabel(i: number): string {
  return `${DAY_LABEL[i >> 1]} ${i & 1 ? 'PM' : 'AM'}`;
}

/** Practice rooms shared by an organisation's divisions in one block. */
export function practiceRooms(org: Organisation | undefined): number {
  return Math.max(1, Math.ceil((org?.facilities.training ?? 2) / 2));
}

const CAPACITY: Record<string, number> = { 'head-coach': 10, 'academy-coach': 10, 'assistant-coach': 8, analyst: 6, psychologist: 4, 'performance-coach': 4 };

/** Coaching capacity of organisation-level staff (shared by its divisions), per organisation. */
function sharedCapacity(world: World): Map<string, number> {
  const m = new Map<string, number>();
  for (const s of Object.values(world.staff)) if (s.orgId && !s.teamId && !s.retired && s.contract && CAPACITY[s.role]) m.set(s.orgId, (m.get(s.orgId) ?? 0) + CAPACITY[s.role]);
  return m;
}

/** Coached sessions a team's staff can run per week. */
export function staffCapacity(world: World, team: Team, shared?: Map<string, number>): number {
  let cap = 0;
  for (const sid of team.staff) cap += CAPACITY[world.staff[sid]?.role ?? ''] ?? 0;
  const org = world.orgs[team.orgId];
  if (org) {
    const sh = (shared ?? sharedCapacity(world)).get(org.id) ?? 0;
    if (sh) cap += sh / Math.max(1, org.teams.filter((id) => world.teams[id]?.active).length);
  }
  return Math.round(cap);
}

export interface WeekCalendar {
  /** team → day offsets (0 = Monday) with a match this week */
  matchDays: Map<string, Set<number>>;
  /** team → opponents in the next 21 days */
  upcoming: Map<string, Set<string>>;
  /** team → a playoff/final/international match within 7 days */
  major: Set<string>;
}

export function weekCalendar(world: World, start = world.day): WeekCalendar {
  const matchDays = new Map<string, Set<number>>();
  const upcoming = new Map<string, Set<string>>();
  const major = new Set<string>();
  for (let d = 0; d < 21; d++) {
    for (const fid of world.schedule[start + d] ?? []) {
      const f = world.fixtures[fid];
      if (!f || f.status !== 'scheduled' || f.teams.length !== 2) continue;
      const comp = world.comps[f.compId];
      const big = f.formatKey !== 'standard' || comp?.tier === 'international' || comp?.tier === 'worlds';
      for (let i = 0; i < 2; i++) {
        const tid = f.teams[i];
        if (d < 7) {
          if (!matchDays.has(tid)) matchDays.set(tid, new Set());
          matchDays.get(tid)!.add(d);
          if (big) major.add(tid);
        }
        if (!upcoming.has(tid)) upcoming.set(tid, new Set());
        upcoming.get(tid)!.add(f.teams[1 - i]);
      }
    }
  }
  return { matchDays, upcoming, major };
}

const OBJECTIVE_MIX: Partial<Record<TrainingFocus, SessionType[]>> = {
  characters: ['pool', 'individual', 'pool', 'tactics', 'individual'],
  maps: ['maps', 'individual', 'maps', 'tactics', 'individual'],
  weapons: ['individual', 'maps', 'individual', 'tactics'],
  tactics: ['tactics', 'individual', 'tactics', 'maps', 'pool'],
  teamwork: ['tactics', 'individual', 'tactics', 'mental', 'pool'],
  mental: ['mental', 'individual', 'tactics', 'mental', 'individual'],
  knowledge: ['patch', 'individual', 'tactics', 'maps', 'individual'],
  recovery: ['rest', 'individual', 'rest', 'mental', 'tactics'],
};
const DEFAULT_MIX: SessionType[] = ['individual', 'tactics', 'individual', 'pool', 'maps', 'individual'];

/** The coaching staff's timetable for this week. */
export function coachPlan(world: World, team: Team, cal: WeekCalendar, shared?: Map<string, number>): SessionType[] {
  const tp = team.training;
  const md = cal.matchDays.get(team.id) ?? new Set<number>();
  const plan: Array<SessionType | null> = new Array(SLOTS).fill(null);
  const taper = cal.major.has(team.id);
  // Match days: light morning (preparation), the match in the afternoon.
  for (const d of md) {
    plan[d * 2] = tp.opponentPrep ? 'prep' : 'rest';
    plan[d * 2 + 1] = 'rest';
  }
  // One full rest day: Sunday unless it is a match day, else the first free day after a match.
  const free = [6, 5, 0, 1, 2, 3, 4].filter((d) => !md.has(d));
  const restDay = free.find((d) => d === 6 || md.has(d - 1)) ?? free[0];
  if (restDay !== undefined) plan[restDay * 2] = plan[restDay * 2 + 1] = 'rest';
  // Preparation the day before a match, review the morning after one.
  for (const d of md) {
    if (d > 0 && plan[(d - 1) * 2] === null && tp.opponentPrep) plan[(d - 1) * 2] = 'prep';
    if (d < 6 && plan[(d + 1) * 2] === null) plan[(d + 1) * 2] = 'review';
  }
  // Scrims in free afternoons (then mornings), fewer when tapering.
  let scrims = Math.min(tp.scrimsPerWeek, taper ? 2 : 7);
  for (const half of [1, 0])
    for (let d = 0; d < 7 && scrims > 0; d++) {
      const i = d * 2 + half;
      if (plan[i] === null) {
        plan[i] = 'scrim';
        scrims--;
      }
    }
  if (tp.scrimsPerWeek >= 2 && !plan.includes('review')) {
    const i = plan.findIndex((x) => x === null);
    if (i >= 0) plan[i] = 'review';
  }
  // Patch adaptation when the team is behind the current patch.
  if (team.metaAdapt < 55) {
    const i = plan.findIndex((x) => x === null);
    if (i >= 0) plan[i] = 'patch';
  }
  // Fill the rest from the development objective.
  const mix = OBJECTIVE_MIX[tp.focus] ?? DEFAULT_MIX;
  let k = 0;
  for (let i = 0; i < SLOTS; i++) if (plan[i] === null) plan[i] = mix[k++ % mix.length];
  // Fatigue and tapering: coaches swap heavy blocks for rest.
  const starters = team.lineup.map((s) => world.players[s.playerId]).filter(Boolean);
  const fat = starters.length ? starters.reduce((a, p) => a + p!.fatigue, 0) / starters.length : 0;
  let restSwaps = (fat > 65 ? 4 : fat > 50 ? 2 : 0) + (taper ? 2 : 0) + Math.round((60 - tp.intensity) / 25);
  for (let i = SLOTS - 1; i >= 0 && restSwaps > 0; i--) {
    if (plan[i] === 'individual' || plan[i] === 'pool' || plan[i] === 'maps') {
      plan[i] = 'rest';
      restSwaps--;
    }
  }
  // Staff capacity: coaches do not plan more coached sessions than they can run.
  const cap = staffCapacity(world, team, shared);
  let coached = plan.filter((x) => x && SESSIONS[x].coached).length;
  for (let i = SLOTS - 1; i >= 0 && coached > cap; i--) {
    const x = plan[i]!;
    if (SESSIONS[x].coached && x !== 'prep') {
      plan[i] = 'rest';
      coached--;
    }
  }
  return plan as SessionType[];
}

/** Whether the user plans this team's sessions (not delegated to the coaches). */
export function managerPlans(world: World, team: Team): boolean {
  return isUserTeam(world, team.id) && !team.prefs.delegateTraining && !!team.training.template;
}

export interface WeekSummary {
  devFactor: number;
  held: Record<SessionType, number>;
  /** Σ partner quality over held scrims (incl. internal). */
  scrimQ: number;
  unsupervised: number;
  fatigue: number;
  prep: number;
  scrimWins: number;
  scrimLosses: number;
}

interface Booking {
  team: Team;
  plan: SessionType[];
  slots: SessionSlot[];
  byManager: boolean;
}

function emptyCounts(): Record<SessionType, number> {
  return Object.fromEntries(SESSION_TYPES.map((t) => [t, 0])) as Record<SessionType, number>;
}

/**
 * Plan, book and run this week's sessions for every club team. Returns per-team summaries used
 * by weekly development; records the detailed timetable for the user's and followed teams.
 */
export function runTrainingWeek(world: World): Map<string, WeekSummary> {
  const cal = weekCalendar(world);
  const shared = sharedCapacity(world);
  const out = new Map<string, WeekSummary>();
  const bookings = new Map<string, Booking>();
  // 1. Timetables, then practice rooms per organisation (main teams first, then by strength).
  const byOrg = new Map<string, Team[]>();
  for (const team of Object.values(world.teams)) {
    if (!team.active || team.national || !team.roster.length) continue;
    if (!byOrg.has(team.orgId)) byOrg.set(team.orgId, []);
    byOrg.get(team.orgId)!.push(team);
  }
  for (const [orgId, teams] of byOrg) {
    const org = world.orgs[orgId];
    const rooms = practiceRooms(org);
    const used = new Array(SLOTS).fill(0);
    teams.sort((a, b) => (a.tier === 'main' ? 0 : 1) - (b.tier === 'main' ? 0 : 1) || b.elo - a.elo || (a.id < b.id ? -1 : 1));
    for (const team of teams) {
      const byManager = managerPlans(world, team);
      const plan = byManager ? [...team.training.template!] : coachPlan(world, team, cal, shared);
      const md = cal.matchDays.get(team.id) ?? new Set<number>();
      const slots: SessionSlot[] = plan.map((t, i) => {
        const day = i >> 1;
        if (md.has(day) && i & 1) return { t: 'match', s: 'match' };
        if (md.has(day) && !LIGHT.includes(t)) return { t, s: 'cancelled', r: 'Match day — only light sessions before a match' };
        if (SESSIONS[t].room) {
          if (used[i] >= rooms) {
            // Coaches move a blocked block to meeting-room work; a manager's block is cancelled.
            if (!byManager) return { t: 'review', s: 'held' };
            return { t, s: 'cancelled', r: `No free practice room (${rooms} room${rooms > 1 ? 's' : ''} for the organisation)` };
          }
          used[i]++;
        }
        return { t, s: 'held' };
      });
      // Coaching capacity: over-capacity coached blocks run unsupervised.
      const cap = staffCapacity(world, team, shared);
      let coached = 0;
      for (const sl of slots) {
        if (sl.s !== 'held' || sl.t === 'match' || !SESSIONS[sl.t].coached) continue;
        coached++;
        if (coached > cap) sl.s = 'unsupervised';
      }
      bookings.set(team.id, { team, plan, slots, byManager });
    }
  }
  // 2. Scrim matching per title and block.
  const byTitle = new Map<string, Booking[]>();
  for (const b of bookings.values()) {
    if (!byTitle.has(b.team.titleId)) byTitle.set(b.team.titleId, []);
    byTitle.get(b.team.titleId)!.push(b);
  }
  const scrimmed = new Map<string, Set<string>>();
  for (const [titleId, list] of byTitle) {
    for (let i = 0; i < SLOTS; i++) {
      const want = list.filter((b) => b.slots[i].t === 'scrim' && b.slots[i].s === 'held').sort((a, b) => b.team.elo - a.team.elo || (a.team.id < b.team.id ? -1 : 1));
      if (!want.length) continue;
      const rng = new RNG(`${world.meta.seed}|scrim|${world.day}|${titleId}|${i}`);
      const taken = new Set<string>();
      for (const a of want) {
        if (taken.has(a.team.id)) continue;
        taken.add(a.team.id);
        let best: Booking | null = null;
        let bestScore = -4;
        for (const b of want) {
          if (taken.has(b.team.id) || b.team.orgId === a.team.orgId) continue;
          let score = -Math.abs(a.team.elo - b.team.elo) / 100;
          if (a.team.region !== b.team.region) score -= 0.6;
          const soon = cal.upcoming.get(a.team.id)?.has(b.team.id);
          if (soon) score -= 3;
          const sameLeague = !!a.team.leagueId && a.team.leagueId === b.team.leagueId;
          if (sameLeague && (a.team.training.scrimPolicy === 'outside-league' || b.team.training.scrimPolicy === 'outside-league')) score -= 10;
          if (scrimmed.get(a.team.id)?.has(b.team.id)) score -= 0.8;
          if (score > bestScore) {
            bestScore = score;
            best = b;
          }
        }
        const sa = a.slots[i];
        if (!best) {
          internalScrim(world, a, sa);
          continue;
        }
        taken.add(best.team.id);
        const sb = best.slots[i];
        if (rng.chance(PARTNER_CANCEL)) {
          sa.s = sb.s = 'cancelled';
          sa.r = `${best.team.short} cancelled the scrim at short notice`;
          sb.r = `${a.team.short} cancelled the scrim at short notice`;
          sa.p = best.team.id;
          sb.p = a.team.id;
          continue;
        }
        const pa = 1 / (1 + Math.pow(10, (best.team.elo - a.team.elo) / 400));
        const aWon = rng.chance(pa);
        const region = a.team.region === best.team.region ? 1 : 0.85;
        sa.p = best.team.id;
        sb.p = a.team.id;
        sa.w = aWon ? 1 : 0;
        sb.w = aWon ? 0 : 1;
        sa.q = Math.round(clamp(0.75 + (best.team.elo - a.team.elo) / 500, 0.45, 1.25) * region * 100) / 100;
        sb.q = Math.round(clamp(0.75 + (a.team.elo - best.team.elo) / 500, 0.45, 1.25) * region * 100) / 100;
        for (const [x, y] of [[a.team, best.team], [best.team, a.team]] as const) {
          (x.training.intel ??= {})[y.id] = world.day + INTEL_DAYS;
          if (!scrimmed.has(x.id)) scrimmed.set(x.id, new Set());
          scrimmed.get(x.id)!.add(y.id);
        }
      }
    }
  }
  // 3. Summaries (and detailed timetables for watched teams).
  for (const b of bookings.values()) {
    const held = emptyCounts();
    let dev = 0;
    let scrimQ = 0;
    let unsup = 0;
    let load = 0;
    let wins = 0;
    let losses = 0;
    for (const sl of b.slots) {
      if (sl.t === 'match' || sl.s === 'cancelled') continue;
      const def = SESSIONS[sl.t];
      const half = sl.s === 'unsupervised' ? 0.5 : 1;
      if (sl.s === 'unsupervised') unsup++;
      held[sl.t]++;
      load += def.load;
      if (sl.t === 'scrim') {
        const q = sl.q ?? 0.6;
        scrimQ += q;
        dev += def.dev * q;
        if (sl.w === 1) wins++;
        else if (sl.w === 0) losses++;
      } else dev += def.dev * half;
    }
    const reviewBoost = held.scrim ? 1 + 0.5 * Math.min(1, (held.review * 2) / held.scrim) : 1;
    scrimQ *= reviewBoost;
    const intensity = b.team.training.intensity / 100;
    const devFactor = clamp(dev / DEV_REF, 0, DEV_CAP) * (0.6 + intensity * 0.6);
    const fatigue = Math.max(0, load) * 0.45 * (0.5 + intensity) - held.rest * 0.8 - 0.5;
    const prep = Math.min(1, held.prep / 2);
    b.team.training.prep = prep;
    // Expired scrim intel is forgotten.
    const intel = b.team.training.intel;
    if (intel) for (const [k, until] of Object.entries(intel)) if (until < world.day) delete intel[k];
    out.set(b.team.id, { devFactor, held, scrimQ, unsupervised: unsup, fatigue, prep, scrimWins: wins, scrimLosses: losses });
    if (isWatched(world, b.team.id)) {
      const week: TrainingWeek = {
        start: world.day,
        slots: b.slots,
        plannedBy: b.byManager ? 'manager' : 'coach',
        rooms: practiceRooms(world.orgs[b.team.orgId]),
        staffCap: staffCapacity(world, b.team, shared),
        coached: b.slots.filter((s) => s.t !== 'match' && s.s !== 'cancelled' && SESSIONS[s.t].coached).length,
        devFactor: Math.round(devFactor * 100) / 100,
        fatigue: Math.round(fatigue * 10) / 10,
        taper: cal.major.has(b.team.id),
      };
      b.team.training.week = week;
    } else delete b.team.training.week;
  }
  return out;
}

/** No outside partner: a team with an academy (or a parent team) in the same title scrims internally. */
function internalScrim(world: World, a: Booking, sl: SessionSlot): void {
  const team = a.team;
  const sibling = Object.values(world.teams).find((t) => t.active && t.id !== team.id && t.orgId === team.orgId && t.titleId === team.titleId && (t.parentTeamId === team.id || team.parentTeamId === t.id));
  if (!sibling) {
    sl.s = 'cancelled';
    sl.r = 'No scrim partner available in this block';
    return;
  }
  sl.p = sibling.id;
  sl.internal = true;
  sl.q = Math.round(clamp(0.6 + (sibling.elo - team.elo) / 600, 0.35, 0.9) * 100) / 100;
}

/** Preparation edge from scrim intel on an opponent (1 = none). */
export function intelEdge(world: World, team: Team, opponent: Team | undefined): number {
  const until = opponent ? team.training.intel?.[opponent.id] : undefined;
  return until !== undefined && until >= world.day ? 1.005 : 1;
}

/** Practice sharpness from recent scrims (±1% team performance). */
export function sharpness(team: Team): number {
  return 0.99 + 0.02 * clamp(team.scrimRating, 0, 100) / 100;
}

/** A default manager template (the coaches' plan for an empty calendar), for the editor. */
export function defaultTemplate(world: World, team: Team): SessionType[] {
  const empty: WeekCalendar = { matchDays: new Map(), upcoming: new Map(), major: new Set() };
  return coachPlan(world, team, empty);
}

export function titleHasMaps(titleId: string): boolean {
  return getTitle(titleId).maps.length > 1;
}

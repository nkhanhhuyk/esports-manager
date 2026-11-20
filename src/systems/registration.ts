/**
 * Competition registration — separate from employment (master prompt §6: "a human can be
 * employed but not registered").
 *
 * Each competition edition registers up to `maxRegistered` players per entrant when it locks
 * (three days before it starts, when entrants are resolved). Only registered players may play in
 * that competition. Rules (editable proposals, not claims about real leagues):
 *  - the team's standing registration plan is used if set (Team page), otherwise the coach
 *    registers the best starting five plus the most useful substitutes;
 *  - after the lock, up to AMENDMENTS changes per edition are allowed while a transfer window is
 *    open (a signing replacing a departed or released player, a swap);
 *  - an emergency registration is always allowed when injuries/departures leave fewer than a full
 *    lineup of eligible registered players (no forfeits, and it is recorded as an emergency);
 *  - a player can be registered for only one team in an edition.
 */
import { getTitle } from '../data/titles/catalogue';
import type { Competition, LineupSlot, Player, Team, World } from '../model/types';
import { effectiveness } from '../sim/common/ratings';
import { autoLineup } from './lineup';
import { addInbox, isUserTeam } from './news';
import { inTransferWindow, rosterMax } from './transfers';
import { userHandles } from './career';

export const AMENDMENTS = 2;
export const REGISTRATION_LOCK_DAYS = 3;

export function maxRegistered(world: World, team: Team): number {
  const t = getTitle(team.titleId);
  return Math.min(rosterMax(world, team), t.teamSize + 3);
}

/** Registered players of a team in a competition, or null when the edition has no registration. */
export function registrationOf(comp: Competition | undefined, teamId: string): Set<string> | null {
  const r = comp?.registrations?.[teamId];
  return r ? new Set(r) : null;
}

function available(world: World, p: Player | undefined, team: Team): p is Player {
  return !!p && !p.retired && p.teamId === team.id && !(p.injury && p.injury.untilDay > world.day);
}

/** Coach's registration: the best lineup first, then substitutes by role usefulness. */
export function coachRegistration(world: World, team: Team, max = maxRegistered(world, team)): string[] {
  const t = getTitle(team.titleId);
  const starters = autoLineup(world, team).map((s) => s.playerId);
  const rest = team.roster
    .filter((id) => !starters.includes(id))
    .map((id) => world.players[id])
    .filter((p): p is Player => !!p && !p.retired)
    .sort((a, b) => {
      const ea = Math.max(...t.roles.map((r) => effectiveness(a, t, r)));
      const eb = Math.max(...t.roles.map((r) => effectiveness(b, t, r)));
      return eb - ea;
    })
    .map((p) => p.id);
  return [...starters, ...rest].slice(0, max);
}

/** Players a team would register right now (plan if valid, else the coach's choice). */
export function plannedRegistration(world: World, team: Team): string[] {
  const max = maxRegistered(world, team);
  // A delegated registration ignores the manager's standing plan: the coach decides.
  const ownPlan = !world.orgs[team.orgId]?.user || userHandles(world, 'registration');
  const plan = ownPlan ? (team.prefs.registrationPlan ?? []).filter((id) => team.roster.includes(id)) : [];
  if (plan.length >= getTitle(team.titleId).teamSize) return plan.slice(0, max);
  return coachRegistration(world, team, max);
}

/** Freeze registrations for every entrant when a competition locks. */
export function lockRegistrations(world: World, comp: Competition): void {
  comp.registrations = {};
  comp.amendments = {};
  const taken = new Set<string>();
  for (const tid of comp.teamIds) {
    const team = world.teams[tid];
    if (!team) continue;
    const list = plannedRegistration(world, team).filter((id) => !taken.has(id));
    list.forEach((id) => taken.add(id));
    comp.registrations[tid] = list;
    if (isUserTeam(world, tid)) {
      const names = list.map((id) => world.players[id]?.handle).filter(Boolean).join(', ');
      addInbox(world, {
        from: comp.name,
        subject: `Registration locked: ${team.name} in ${comp.short}`,
        body: `Registered (${list.length}/${maxRegistered(world, team)}): ${names}.\nOnly these players can play in ${comp.name}. You may make up to ${AMENDMENTS} changes while a transfer window is open (Team page → Registration); emergency registrations are allowed if you cannot field a full lineup.`,
        kind: 'registration',
      });
    }
  }
}

/** Competitions (active, with registration) a team is entered in. */
export function activeRegistrations(world: World, teamId: string): Competition[] {
  return Object.values(world.comps).filter((c) => c.status === 'active' && c.registrations?.[teamId]);
}

export interface RegistrationResult {
  ok: boolean;
  message: string;
}

function eligibleRegistered(world: World, comp: Competition, team: Team): string[] {
  return (comp.registrations?.[team.id] ?? []).filter((id) => available(world, world.players[id], team));
}

/**
 * Register `add` for a team in a locked competition, optionally removing `remove` to make room.
 * Uses one amendment (inside a transfer window) or counts as an emergency registration when the
 * team cannot otherwise field a full lineup.
 */
export function amendRegistration(world: World, compId: string, teamId: string, add: string, remove: string | null = null): RegistrationResult {
  const comp = world.comps[compId];
  const team = world.teams[teamId];
  const p = world.players[add];
  if (!comp || !team || comp.status !== 'active' || !comp.registrations?.[teamId]) return { ok: false, message: 'This competition has no open registration for the team.' };
  const list = comp.registrations[teamId];
  if (!p || p.teamId !== team.id) return { ok: false, message: 'Only players on the roster can be registered.' };
  if (list.includes(add)) return { ok: false, message: `${p.handle} is already registered.` };
  for (const [tid, l] of Object.entries(comp.registrations)) if (tid !== teamId && l.includes(add)) return { ok: false, message: `${p.handle} is already registered for ${world.teams[tid]?.name ?? 'another team'} in this competition.` };
  const t = getTitle(team.titleId);
  const emergency = eligibleRegistered(world, comp, team).length < t.teamSize;
  const used = comp.amendments?.[teamId]?.filter((a) => !a.emergency).length ?? 0;
  if (!emergency) {
    if (!inTransferWindow(world)) return { ok: false, message: 'Registration changes are only possible while a transfer window is open (or as an emergency when you cannot field a full lineup).' };
    if (used >= AMENDMENTS) return { ok: false, message: `All ${AMENDMENTS} registration changes for ${comp.short} are used.` };
  }
  const max = maxRegistered(world, team);
  if (remove) {
    if (!list.includes(remove)) return { ok: false, message: 'The player to remove is not registered.' };
    list.splice(list.indexOf(remove), 1);
  } else if (list.length >= max) {
    // Departed players' places are freed automatically; otherwise a swap is required.
    const gone = list.find((id) => world.players[id]?.teamId !== team.id);
    if (gone) list.splice(list.indexOf(gone), 1);
    else return { ok: false, message: `Registration is full (${max}). Choose a registered player to replace.` };
  }
  list.push(add);
  ((comp.amendments ??= {})[teamId] ??= []).push({ day: world.day, added: add, removed: remove, emergency });
  return { ok: true, message: `${p.handle} registered for ${comp.short}${emergency ? ' (emergency registration)' : ` (${AMENDMENTS - used - 1} change(s) left)`}.` };
}

/**
 * Lineup for a fixture: only registered, available players. If the team cannot field a full
 * lineup from them, the best available rostered players are emergency-registered first.
 */
export function registeredLineup(world: World, comp: Competition | undefined, team: Team, preferred: LineupSlot[]): LineupSlot[] {
  const reg = registrationOf(comp, team.id);
  if (!reg || !comp) return preferred;
  const t = getTitle(team.titleId);
  if (preferred.length === t.teamSize && preferred.every((s) => reg.has(s.playerId))) return preferred;
  let eligible = new Set(eligibleRegistered(world, comp, team));
  if (eligible.size < t.teamSize) {
    const extra = team.roster.filter((id) => !eligible.has(id) && available(world, world.players[id], team) && !Object.entries(comp.registrations ?? {}).some(([tid, l]) => tid !== team.id && l.includes(id)));
    for (const id of extra) {
      if (eligible.size >= t.teamSize) break;
      const r = amendRegistration(world, comp.id, team.id, id);
      if (r.ok) eligible.add(id);
    }
    eligible = new Set([...eligible, ...eligibleRegistered(world, comp, team)]);
    if (isUserTeam(world, team.id)) addInbox(world, { from: comp.name, subject: `Emergency registration for ${team.short}`, body: `${team.name} could not field a full lineup of registered players for ${comp.name}; available players were emergency-registered.`, kind: 'registration' });
  }
  // Keep the preferred players who are eligible, fill the rest with the best eligible options.
  const keep = preferred.filter((s) => eligible.has(s.playerId));
  if (keep.length === t.teamSize) return keep;
  return autoLineup(world, team, eligible);
}

/**
 * A player just joined a team: AI clubs register him for their running competitions when an
 * amendment is available; the user is told where he is not yet registered.
 */
export function onPlayerJoined(world: World, team: Team, playerId: string): void {
  const comps = activeRegistrations(world, team.id);
  if (!comps.length) return;
  // The user decides unless registration is delegated to the coach.
  const user = isUserTeam(world, team.id) && userHandles(world, 'registration');
  const missing: string[] = [];
  for (const c of comps) {
    if (c.registrations![team.id].includes(playerId)) continue;
    if (!user) {
      const list = c.registrations![team.id];
      const t = getTitle(team.titleId);
      const gone = list.find((id) => world.players[id]?.teamId !== team.id);
      // AI: register a signing when a place is free or a departed player can be replaced.
      if (list.length < maxRegistered(world, team) || gone || eligibleRegistered(world, c, team).length < t.teamSize) amendRegistration(world, c.id, team.id, playerId, null);
    } else missing.push(c.short);
  }
  if (user && missing.length) {
    const p = world.players[playerId];
    addInbox(world, { from: 'Competitions', subject: `${p?.handle ?? 'New signing'} is not registered`, body: `${p?.handle} cannot play in ${missing.join(', ')} until registered (Team page → Registration; ${AMENDMENTS} changes per competition while a transfer window is open).`, kind: 'registration' });
  }
}

/** Daily: a week before a league competition locks, remind the user to review registration. */
export function registrationReminders(world: World): void {
  const uid = world.meta.userOrgId;
  if (!uid) return;
  for (const c of Object.values(world.comps)) {
    if (c.status !== 'pending' || !c.leagueId || c.startDay - REGISTRATION_LOCK_DAYS - world.day !== 7) continue;
    const league = world.leagues[c.leagueId];
    for (const tid of league?.teamIds ?? []) {
      const team = world.teams[tid];
      if (!team || team.orgId !== uid || !userHandles(world, 'registration')) continue;
      const plan = plannedRegistration(world, team);
      addInbox(world, {
        from: c.name,
        subject: `Registration for ${c.short} locks in 7 days`,
        body: `${team.name} will register ${plan.length} players (${team.prefs.registrationPlan?.length ? 'your registration plan' : "the coach's choice"}): ${plan.map((id) => world.players[id]?.handle).join(', ')}. Change the plan on the Team page → Registration.`,
        kind: 'registration',
      });
    }
  }
}

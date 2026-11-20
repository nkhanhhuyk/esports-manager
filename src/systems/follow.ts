/**
 * Game modes and followed teams.
 *
 * Manager mode: the user runs one organisation (isUserTeam). Spectator mode: nobody is
 * user-controlled — AI staff make every decision (lineups, drafts, training, signings) — and the
 * user follows any number of teams (or all of them). Followed teams get full match detail, match
 * notifications, training reports and optional live viewing.
 */
import type { Fixture, World } from '../model/types';
import { addInbox, isUserTeam } from './news';

export function isSpectator(world: World): boolean {
  return world.meta.mode === 'spectator';
}

export function isFollowed(world: World, teamId: string | null | undefined): boolean {
  return !!teamId && !!world.meta.followed?.includes(teamId);
}

/** Teams whose details the user sees closely: own teams (manager) and followed teams. */
export function isWatched(world: World, teamId: string | null | undefined): boolean {
  return isUserTeam(world, teamId) || isFollowed(world, teamId);
}

/** The teams shown on the dashboard: own teams in manager mode, followed teams in spectator mode. */
export function watchedTeamIds(world: World): string[] {
  const own = world.meta.userOrgId ? (world.orgs[world.meta.userOrgId]?.teams ?? []).filter((id) => world.teams[id]?.active) : [];
  const followed = (world.meta.followed ?? []).filter((id) => world.teams[id]?.active);
  return [...new Set([...own, ...followed])];
}

/**
 * Give up (or never take) a job and watch: nobody is user-controlled, AI staff decide everything.
 * Follows the given teams, else the previous organisation's divisions.
 */
export function becomeSpectator(world: World, followed?: string[]): void {
  const uid = world.meta.userOrgId;
  const prev = uid ? (world.orgs[uid]?.teams ?? []).filter((id) => world.teams[id]?.active) : [];
  if (uid && world.orgs[uid]) world.orgs[uid].user = false;
  for (const id of prev) {
    const t = world.teams[id];
    t.prefs.delegateLineup = true;
    t.prefs.delegateDraft = true;
    t.prefs.delegateTraining = true;
  }
  world.meta.userOrgId = null;
  world.meta.mode = 'spectator';
  world.meta.unemployed = false;
  world.meta.nationalJob = null;
  world.meta.jobApproaches = [];
  world.meta.managerContract = undefined;
  world.pendingUserMatch = null;
  world.meta.followed = followed ?? (world.meta.followed?.length ? world.meta.followed : prev);
  addInbox(world, {
    from: 'Esports Manager',
    subject: 'Spectator mode',
    body: `You are now watching the world. Every decision — lineups, drafts, training, scrims, signings, contracts and staff — is made by the clubs' own staff. You follow ${world.meta.followed.length} team(s); change them under Following. Settings → Game mode lets you take charge of an organisation again.`,
    kind: 'board',
  });
}

/** Set the followed teams (deduplicated, active only). */
export function setFollowed(world: World, ids: string[]): void {
  world.meta.followed = [...new Set(ids)].filter((id) => world.teams[id]?.active);
}

/** Fixtures on a day that involve the user's teams (manager) or followed teams (spectator). */
export function watchedFixturesOn(world: World, day: number): Fixture[] {
  const spectating = isSpectator(world);
  if (!world.meta.userOrgId && !spectating) return [];
  return (world.schedule[day] ?? [])
    .map((id) => world.fixtures[id])
    .filter((f) => f && f.status === 'scheduled' && f.teams.some((t) => (spectating ? isFollowed(world, t) : isUserTeam(world, t))));
}

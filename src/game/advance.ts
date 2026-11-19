/**
 * Central time advancement on the single shared world calendar.
 *
 * processDay() is idempotent: each calendar day is processed exactly once (guarded by
 * world.processed.lastDay), so repeated or interrupted advance commands never duplicate
 * fixtures, awards, transfers or money.
 */
import { dayOfWeek, isFirstOfMonth, monthKey, ymd } from '../core/date';
import { RNG } from '../core/rng';
import type { Fixture, World } from '../model/types';
import { startDueCompetitions } from '../competition/progress';
import { seasonEndDay } from '../competition/season';
import { playFixture } from '../sim/record';
import { dailyCondition, weeklyTraining } from '../systems/training';
import { processMonthlyFinances } from '../systems/finance';
import { aiContracts, aiMarketDay, processLoans } from '../systems/transfers';
import { negotiationsDay } from '../systems/negotiation';
import { boardMonthly } from '../systems/career';
import { registrationReminders } from '../systems/registration';
import { compactMatches, roundProficiencies } from './compact';
import { adaptToMeta, releasePatch } from '../systems/patches';
import { seasonRollover } from './rollover';
import { isUserTeam } from '../systems/news';
import { weeklyScouting } from '../systems/scouting';

export type AdvanceMode =
  | { kind: 'days'; n: number }
  | { kind: 'next-event' }
  | { kind: 'end-season' }
  | { kind: 'seasons'; n: number }
  | { kind: 'until'; day: number };

export interface AdvanceOptions {
  /** Stop before a day on which a user team plays (so the user can prepare / play live). */
  stopForUserMatch?: boolean;
  onProgress?: (done: number, total: number, day: number) => void;
  maxDays?: number;
}

export interface AdvanceResult {
  days: number;
  stoppedFor: 'user-match' | 'decision' | 'complete' | 'limit';
  fixtureId?: string;
}

export function userFixturesOn(world: World, day: number): Fixture[] {
  if (!world.meta.userOrgId) return [];
  return (world.schedule[day] ?? [])
    .map((id) => world.fixtures[id])
    .filter((f) => f && f.status === 'scheduled' && f.teams.some((t) => isUserTeam(world, t)));
}

/** Patch cadence: majors at season start & mid-season, minor balance patches ~every 3 weeks. */
function patchCheck(world: World, day: number): void {
  const { m, d } = ymd(day);
  for (const id of world.meta.titlesEnabled) {
    if (m === 5 && d === 20) {
      releasePatch(world, id, true);
      continue;
    }
    const rng = new RNG(`${world.meta.seed}|patchday|${id}|${day}`);
    if (rng.chance(1 / 21)) releasePatch(world, id, false);
  }
}

export function processDay(world: World): void {
  const day = world.day + 1;
  if (world.processed.lastDay >= day) {
    world.day = day;
    return;
  }
  world.day = day;
  startDueCompetitions(world, day);
  const todays = [...(world.schedule[day] ?? [])].sort();
  for (const fid of todays) {
    const f = world.fixtures[fid];
    if (f && f.status === 'scheduled') playFixture(world, f);
  }
  // Brackets can generate fixtures for the same day only if a format has 0-day gaps; play them too.
  for (const fid of world.schedule[day] ?? []) {
    const f = world.fixtures[fid];
    if (f && f.status === 'scheduled') playFixture(world, f);
  }
  dailyCondition(world);
  for (const team of Object.values(world.teams)) if (team.active) adaptToMeta(world, team.id);
  aiMarketDay(world);
  negotiationsDay(world);
  registrationReminders(world);
  if (dayOfWeek(day) === 1) {
    const wk = Math.floor(day / 7);
    if (world.processed.lastWeek !== wk) {
      world.processed.lastWeek = wk;
      weeklyTraining(world);
      weeklyScouting(world);
      processLoans(world);
      compactMatches(world);
    }
  }
  if (isFirstOfMonth(day) && world.processed.lastMonth !== monthKey(day)) {
    processMonthlyFinances(world);
    boardMonthly(world);
    roundProficiencies(world);
    aiContracts(world);
  }
  patchCheck(world, day);
  if (day === seasonEndDay(world.season)) seasonRollover(world);
  world.processed.lastDay = day;
  if (world.pendingUserMatch && world.fixtures[world.pendingUserMatch]?.status === 'done') world.pendingUserMatch = null;
}

export function advance(world: World, mode: AdvanceMode, opts: AdvanceOptions = {}): AdvanceResult {
  let target: number;
  const stopForUser = opts.stopForUserMatch ?? (mode.kind === 'days' || mode.kind === 'next-event');
  switch (mode.kind) {
    case 'days':
      target = world.day + mode.n;
      break;
    case 'until':
      target = mode.day;
      break;
    case 'end-season':
      target = seasonEndDay(world.season);
      break;
    case 'seasons':
      target = seasonEndDay(world.season + mode.n - 1);
      break;
    case 'next-event': {
      // Next day with a user fixture, or the next competition start/finish, capped at 60 days.
      target = world.day + 60;
      for (let d = world.day + 1; d <= world.day + 60; d++) {
        if (userFixturesOn(world, d).length) {
          target = d;
          break;
        }
      }
      break;
    }
  }
  const total = Math.max(0, target - world.day);
  let days = 0;
  const limit = opts.maxDays ?? Infinity;
  const employed = !!world.meta.userOrgId && !world.meta.unemployed;
  while (world.day < target) {
    if (stopForUser && world.meta.userOrgId) {
      const uf = userFixturesOn(world, world.day + 1);
      if (uf.length && (days > 0 || mode.kind === 'next-event' || world.pendingUserMatch !== uf[0].id)) {
        // Pause before the user's match day so they can prepare or play it live.
        if (world.pendingUserMatch !== uf[0].id) {
          world.pendingUserMatch = uf[0].id;
          return { days, stoppedFor: 'user-match', fixtureId: uf[0].id };
        }
      }
    }
    processDay(world);
    days++;
    opts.onProgress?.(days, total, world.day);
    // Losing the job (season review or mid-season) pauses time so the user can choose what next.
    if (employed && world.meta.unemployed) return { days, stoppedFor: 'decision' };
    if (days >= limit) return { days, stoppedFor: 'limit' };
  }
  return { days, stoppedFor: 'complete' };
}

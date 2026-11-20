/**
 * Who can play: injuries, breaks, and the one-game-per-season rule.
 *
 * One game per season (user rule, 20 Nov 2026): once a player has competed in a title this
 * season, he cannot join a team in another title until the next season. Moves within the same
 * title (transfers, trades, loans) stay possible in the windows; a pre-contract with a team of
 * another title for next season is allowed.
 */
import { getTitle } from '../data/titles/catalogue';
import type { Player, World } from '../model/types';

export function injured(p: Player, day: number): boolean {
  return !!p.injury && p.injury.untilDay > day;
}

export function onLeave(p: Player, day: number): boolean {
  return !!p.leave && p.leave.untilDay > day && p.leave.fromDay <= day;
}

/** Can be selected for a lineup today. */
export function isAvailable(p: Player | undefined, day: number): p is Player {
  return !!p && !p.retired && !injured(p, day) && !onLeave(p, day);
}

/** Unavailable for at least `days` more days (long injury or break): clubs plan without him. */
export function longTermOut(p: Player, day: number, days = 14): boolean {
  return (!!p.injury && p.injury.untilDay > day + days) || (!!p.leave && p.leave.untilDay > day + days);
}

/** The title a player has competed in this season (most games), or null if he has not played yet. */
export function seasonTitle(world: World, p: Player): string | null {
  const gp = new Map<string, number>();
  for (const s of p.stats) if (s.season === world.season && s.gp > 0) gp.set(s.titleId, (gp.get(s.titleId) ?? 0) + s.gp);
  let best: string | null = null;
  let n = 0;
  for (const [t, g] of gp) if (g > n) [best, n] = [t, g];
  return best;
}

/** Why a player cannot join a team of `titleId` this season (null = allowed). */
export function titleLockReason(world: World, p: Player, titleId: string): string | null {
  const st = seasonTitle(world, p);
  if (!st || st === titleId) return null;
  return `${p.handle} has already competed in ${getTitle(st).short} this season — players stick to one game per season (a move to ${getTitle(titleId).short} is possible from next season, e.g. with a pre-contract).`;
}

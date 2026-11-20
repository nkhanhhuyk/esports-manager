import { createWorld } from '../src/gen/world';
import { dayFromYMD } from '../src/core/date';
import type { World } from '../src/model/types';
import { setUserOrg } from '../src/gen/world';
import { clearRatingCache } from '../src/sim/common/ratings';

export const START = dayFromYMD(2026, 11, 19);

export function makeWorld(titles: string[], seed = 'test', size: 'tiny' | 'small' = 'tiny'): World {
  // Worlds reuse player ids: drop ratings cached for another test's world (the app does this on load).
  clearRatingCache();
  return createWorld({ name: 'Test', seed, worldSize: size, titles, startDay: START });
}

export function withUser(w: World): World {
  const org = Object.values(w.orgs).sort((a, b) => b.rep - a.rep)[0];
  setUserOrg(w, org.id);
  return w;
}

export function clone<T>(x: T): T {
  return JSON.parse(JSON.stringify(x));
}

/** Stable digest of the competitive state for determinism checks. */
export function digest(w: World): string {
  const fx = Object.values(w.fixtures)
    .filter((f) => f.status === 'done')
    .map((f) => `${f.id}:${f.winner}:${(f.score ?? []).join('-')}`)
    .sort()
    .join('|');
  const money = Object.values(w.orgs).map((o) => Math.round(o.balance)).join(',');
  const roster = Object.values(w.teams).map((t) => t.roster.join(',')).join('|');
  return `${w.day}#${fx.length}#${hash(fx)}#${hash(money)}#${hash(roster)}#${w.transfers.length}`;
}

function hash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0;
  return h >>> 0;
}

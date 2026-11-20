/**
 * Comebacks: real retired players (from the real-data snapshot) can come out of retirement.
 *
 * - The user can ask any real retired pro who stopped within the last COMEBACK_YEARS seasons to
 *   return; they become a free agent that anyone can sign through the normal transfer system.
 * - At each season rollover a few real retired pros return by themselves (at most 2 per title, each
 *   eligible pro with probability 4%), announced in the news.
 *
 * Ability on return is a game estimate (labelled in the provenance): the title's average current
 * ability among signed players − 6 − 2 per season away (± noise), clamped to 35–85. Real retired
 * records keep their snapshot provenance; the comeback is marked as a game event.
 */
import { clamp } from '../core/math';
import { RNG } from '../core/rng';
import { getTitle } from '../data/titles/catalogue';
import { generatePlayer, type GenCtx } from '../gen/player';
import type { RetiredSummary, World } from '../model/types';
import { addNews } from './news';

export const COMEBACK_YEARS = 6;

export function canComeBack(world: World, r: RetiredSummary): boolean {
  return !!r.real && !r.cameBack && !world.players[r.id] && world.season - r.retiredSeason <= COMEBACK_YEARS && r.titles.some((t) => world.titles[t]);
}

function titleAvg(world: World, titleId: string): number {
  let n = 0;
  let s = 0;
  for (const p of Object.values(world.players)) if (p.titleId === titleId && p.teamId && !p.retired) {
    n++;
    s += p.ca;
  }
  return n ? s / n : 62;
}

/** Turn a real retired record into a free agent again. */
export function arrangeComeback(world: World, id: string, by: 'user' | 'self', rngIn?: RNG): { ok: boolean; message: string; playerId?: string } {
  const r = world.retired.find((x) => x.id === id);
  if (!r) return { ok: false, message: 'Retired player not found.' };
  if (!canComeBack(world, r)) return { ok: false, message: `${r.handle} is not available for a comeback.` };
  const titleId = r.titles.find((t) => world.titles[t])!;
  const t = getTitle(titleId);
  const rng = rngIn ?? new RNG(`${world.meta.seed}|comeback|${r.id}|${world.season}`);
  const ctx: GenCtx = { world, rng, handles: new Set(Object.values(world.players).map((p) => p.handle.toLowerCase())) };
  const away = Math.max(0, world.season - r.retiredSeason);
  const roleId = r.roleId && t.roles.some((x) => x.id === r.roleId) ? r.roleId : rng.pick(t.lineup);
  const age = Math.round(clamp(rng.normal(27 + away, 2), 21, 38));
  const p = generatePlayer(ctx, { titleId, roleId, nat: r.nat, age, targetCA: clamp(titleAvg(world, titleId) - 6 - 2 * away + rng.normal(0, 3), 35, 85) });
  p.handle = r.handle;
  const parts = r.name.split(' ').filter(Boolean);
  if (parts.length) {
    p.last = parts.length > 1 ? parts[parts.length - 1] : '';
    p.first = parts.length > 1 ? parts.slice(0, -1).join(' ') : parts[0];
  }
  p.real = { ...r.real!, est: [...(r.real!.est ?? []), 'age', 'ability after the comeback (game)', `came out of retirement in ${world.season} (game)`] };
  if (r.lastTeam) p.career.push({ season: r.retiredSeason, day: world.day, titleId, teamId: null, orgId: null, type: 'retired', note: `Retired (last real team: ${r.lastTeam})` });
  p.career.push({ season: world.season, day: world.day, titleId, teamId: null, orgId: null, type: 'released', note: 'Came out of retirement' });
  world.players[p.id] = p;
  r.cameBack = true;
  r.cameBackAs = p.id;
  addNews(world, {
    kind: 'transfer',
    title: `${r.handle} comes out of retirement`,
    body: `${r.handle}${r.lastTeam ? ` (last real team: ${r.lastTeam})` : ''} is back in ${t.short} as a free agent${by === 'user' ? ' after talks with your organisation' : ''}.`,
    titleId,
    refs: [{ type: 'player', id: p.id }],
    importance: 3,
  });
  return { ok: true, message: `${r.handle} is back as a free agent — sign them from the transfer market.`, playerId: p.id };
}

/** Season rollover: a few real retired pros return on their own. */
export function selfComebacks(world: World, rng: RNG): number {
  const perTitle = new Map<string, number>();
  let n = 0;
  for (const r of world.retired) {
    if (!canComeBack(world, r)) continue;
    const tid = r.titles[0];
    if ((perTitle.get(tid) ?? 0) >= 2 || !rng.chance(0.04)) continue;
    if (arrangeComeback(world, r.id, 'self', rng).ok) {
      perTitle.set(tid, (perTitle.get(tid) ?? 0) + 1);
      n++;
    }
  }
  return n;
}

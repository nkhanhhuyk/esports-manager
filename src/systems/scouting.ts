/**
 * Scouting knowledge for the user's organisation. Potential ability is hidden: the UI shows a
 * range whose width depends on scouting knowledge and scout judgement.
 */
import { clamp } from '../core/math';
import { RNG } from '../core/rng';
import type { Player, World } from '../model/types';

export function userScouts(world: World) {
  const uid = world.meta.userOrgId;
  if (!uid) return [];
  return Object.values(world.staff).filter((s) => s.orgId === uid && s.role === 'scout' && !s.retired);
}

export function weeklyScouting(world: World): void {
  const uid = world.meta.userOrgId;
  if (!uid) return;
  const scouts = userScouts(world);
  const rng = new RNG(`${world.meta.seed}|scout|${world.day}`);
  const titles = new Set(world.orgs[uid].teams.map((t) => world.teams[t]?.titleId));
  const skill = scouts.length ? scouts.reduce((a, s) => a + (s.attrs.judgingAbility + s.attrs.judgingPotential) / 2, 0) / scouts.length : 25;
  const capacity = 6 + scouts.length * 10;
  // Focus list first
  for (const pid of world.scoutFocus) world.scouting[pid] = clamp((world.scouting[pid] ?? 0) + 8 + skill / 8, 0, 100);
  const pool = Object.values(world.players).filter((p) => !p.retired && titles.has(p.titleId) && (world.scouting[p.id] ?? 0) < 100);
  for (const p of rng.sample(pool, capacity)) world.scouting[p.id] = clamp((world.scouting[p.id] ?? 0) + 10 + skill / 10, 0, 100);
}

/** Displayed potential range for the user's view of a player. */
export function potentialRange(world: World, p: Player): [number, number] {
  const k = world.scouting[p.id] ?? 0;
  if (k >= 100) return [Math.round(p.pa), Math.round(p.pa)];
  const scouts = userScouts(world);
  const judge = scouts.length ? Math.max(...scouts.map((s) => s.attrs.judgingPotential)) : 30;
  const width = (100 - k) * 0.18 + (100 - judge) * 0.06;
  // Deterministic bias per player so the range does not jitter between renders.
  const bias = ((p.id.charCodeAt(p.id.length - 1) % 7) - 3) * (width / 8);
  const lo = clamp(Math.round(p.pa - width + bias), Math.round(p.ca), 99);
  const hi = clamp(Math.round(p.pa + width + bias), lo, 99);
  return [lo, hi];
}

export function scoutPlayer(world: World, playerId: string): void {
  if (!world.scoutFocus.includes(playerId)) world.scoutFocus.push(playerId);
  if (world.scoutFocus.length > 40) world.scoutFocus.shift();
}

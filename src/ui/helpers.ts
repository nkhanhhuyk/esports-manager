import type { Fixture, Player, StatLine, Team, World } from '../model/types';
import { getTitle } from '../data/titles/catalogue';
import { effectivenessLight } from '../sim/common/ratings';
import { isUserTeam } from '../systems/news';

export function userTeams(world: World): Team[] {
  const uid = world.meta.userOrgId;
  if (!uid) return [];
  return world.orgs[uid].teams.map((id) => world.teams[id]).filter((t) => t && t.active);
}

/** The user's national team (only while a squad has been named for a tournament). */
export function userNationalTeam(world: World): Team | undefined {
  const job = world.meta.nationalJob;
  if (!job) return undefined;
  return Object.values(world.teams).find((t) => t.national === job.country && t.titleId === job.titleId);
}

export function isUserTeamId(world: World, teamId: string | null | undefined): boolean {
  return isUserTeam(world, teamId);
}

export function rosterPlayers(world: World, team: Team): Player[] {
  return team.roster.map((id) => world.players[id]).filter(Boolean);
}

export function teamFixtures(world: World, teamId: string): Fixture[] {
  return Object.values(world.fixtures)
    .filter((f) => f.teams.includes(teamId))
    .sort((a, b) => a.day - b.day);
}

export function upcomingFixtures(world: World, teamIds: string[], n = 10): Fixture[] {
  const set = new Set(teamIds);
  return Object.values(world.fixtures)
    .filter((f) => f.status === 'scheduled' && f.teams.some((t) => set.has(t)))
    .sort((a, b) => a.day - b.day)
    .slice(0, n);
}

export function recentResults(world: World, teamIds: string[], n = 10): Fixture[] {
  const set = new Set(teamIds);
  return Object.values(world.fixtures)
    .filter((f) => f.status === 'done' && f.teams.some((t) => set.has(t)))
    .sort((a, b) => b.day - a.day)
    .slice(0, n);
}

export function fixtureLabel(world: World, f: Fixture): string {
  const a = world.teams[f.teams[0]];
  const b = world.teams[f.teams[1]];
  return `${a?.short ?? '?'} vs ${b?.short ?? '?'}`;
}

export function scoreLabel(f: Fixture): string {
  if (f.status !== 'done') return f.format.label;
  return `${f.score?.[0] ?? 0}–${f.score?.[1] ?? 0}`;
}

export interface Agg {
  gp: number;
  w: number;
  l: number;
  rating: number;
  mvps: number;
  s: Record<string, number>;
}

export function aggregate(lines: StatLine[]): Agg {
  const out: Agg = { gp: 0, w: 0, l: 0, rating: 0, mvps: 0, s: {} };
  let rs = 0;
  for (const l of lines) {
    out.gp += l.gp;
    out.w += l.w;
    out.l += l.l;
    out.mvps += l.mvps;
    rs += l.ratingSum;
    for (const [k, v] of Object.entries(l.s)) out.s[k] = (out.s[k] ?? 0) + v;
  }
  out.rating = out.gp ? rs / out.gp : 0;
  return out;
}

export function seasonAgg(p: Player, season: number, titleId?: string): Agg {
  return aggregate(p.stats.filter((s) => s.season === season && (!titleId || s.titleId === titleId)));
}

export function roleE(p: Player, titleId: string, roleId: string): number {
  const t = getTitle(titleId);
  const role = t.roles.find((r) => r.id === roleId) ?? t.roles[0];
  return effectivenessLight(p, t, role);
}

export function roleName(titleId: string, roleId: string): string {
  return getTitle(titleId).roles.find((r) => r.id === roleId)?.short ?? roleId;
}

export function kda(s: Record<string, number>): string {
  const k = s.k ?? s.elim ?? 0;
  const d = s.d ?? 0;
  const a = s.a ?? 0;
  return d ? ((k + a) / d).toFixed(2) : (k + a).toFixed(1);
}

export function pctFmt(a: number, b: number): string {
  return b ? `${Math.round((a / b) * 100)}%` : '—';
}

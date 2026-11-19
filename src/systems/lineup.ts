/**
 * Lineup selection and legality.
 */
import { getTitle } from '../data/titles/catalogue';
import { expandRegion } from '../data/countries';
import { COUNTRY_BY_CODE } from '../data/countries';
import { ageAt } from '../core/date';
import type { LineupSlot, Player, Team, World } from '../model/types';
import { effectivenessLight as effectiveness } from '../sim/common/ratings';

export function isLocal(p: Player, region: string): boolean {
  const regs = expandRegion(region);
  const r1 = COUNTRY_BY_CODE[p.nat]?.region;
  const r2 = p.nat2 ? COUNTRY_BY_CODE[p.nat2]?.region : undefined;
  return (!!r1 && regs.includes(r1)) || (!!r2 && regs.includes(r2));
}

export function availablePlayers(world: World, team: Team): Player[] {
  return team.roster
    .map((id) => world.players[id])
    .filter((p): p is Player => !!p && !p.retired && !(p.injury && p.injury.untilDay > world.day));
}

/** Greedy maximum-effectiveness assignment of players to the title's lineup positions. */
export function autoLineup(world: World, team: Team): LineupSlot[] {
  const t = getTitle(team.titleId);
  const league = team.leagueId ? world.leagues[team.leagueId] : null;
  const minAge = league?.policy.minAge ?? t.minAge;
  let players = availablePlayers(world, team).filter((p) => ageAt(p.birth, world.day) >= minAge);
  if (players.length < t.teamSize) players = team.roster.map((id) => world.players[id]).filter((p) => p && !p.retired) as Player[];
  const roles = t.lineup;
  const pairs: Array<{ p: Player; roleIdx: number; e: number }> = [];
  for (const p of players) {
    for (let i = 0; i < roles.length; i++) {
      const role = t.roles.find((r) => r.id === roles[i])!;
      // Slight preference for a player's natural role keeps assignments stable.
      const natural = p.roles[0] === role.id ? 1.5 : p.roles[1] === role.id ? 0.5 : 0;
      pairs.push({ p, roleIdx: i, e: effectiveness(p, t, role) + natural - p.fatigue * 0.03 });
    }
  }
  pairs.sort((a, b) => b.e - a.e);
  const usedP = new Set<string>();
  const slots: Array<LineupSlot | null> = roles.map(() => null);
  const foreignLimit = league?.policy.foreignLimit ?? -1;
  let foreign = 0;
  for (const pr of pairs) {
    if (slots[pr.roleIdx] || usedP.has(pr.p.id)) continue;
    const local = isLocal(pr.p, team.region);
    if (!local && foreignLimit >= 0 && foreign >= foreignLimit) continue;
    slots[pr.roleIdx] = { playerId: pr.p.id, roleId: roles[pr.roleIdx] };
    usedP.add(pr.p.id);
    if (!local) foreign++;
  }
  // Fill any gaps ignoring the import limit (better than forfeiting).
  for (let i = 0; i < slots.length; i++) {
    if (slots[i]) continue;
    const cand = pairs.find((pr) => pr.roleIdx === i && !usedP.has(pr.p.id));
    if (cand) {
      slots[i] = { playerId: cand.p.id, roleId: roles[i] };
      usedP.add(cand.p.id);
    }
  }
  return slots.filter((s): s is LineupSlot => !!s);
}

export interface LineupCheck {
  ok: boolean;
  errors: string[];
}

/** Validate that a lineup is legal for the title and league rules. */
export function validateLineup(world: World, team: Team, lineup: LineupSlot[]): LineupCheck {
  const t = getTitle(team.titleId);
  const errors: string[] = [];
  if (lineup.length !== t.teamSize) errors.push(`Lineup needs exactly ${t.teamSize} players (has ${lineup.length}).`);
  const ids = new Set<string>();
  const roles = new Set<string>();
  const league = team.leagueId ? world.leagues[team.leagueId] : null;
  let foreign = 0;
  for (const s of lineup) {
    if (ids.has(s.playerId)) errors.push('A player appears twice in the lineup.');
    ids.add(s.playerId);
    if (roles.has(s.roleId)) errors.push(`Position ${s.roleId} assigned twice.`);
    roles.add(s.roleId);
    if (!t.lineup.includes(s.roleId)) errors.push(`Position ${s.roleId} does not exist in ${t.short}.`);
    const p = world.players[s.playerId];
    if (!p) {
      errors.push('Unknown player in lineup.');
      continue;
    }
    if (!team.roster.includes(p.id)) errors.push(`${p.handle} is not on the roster.`);
    if (p.injury && p.injury.untilDay > world.day) errors.push(`${p.handle} is injured.`);
    if (league && ageAt(p.birth, world.day) < league.policy.minAge) errors.push(`${p.handle} is below the league minimum age.`);
    if (!isLocal(p, team.region)) foreign++;
  }
  if (league && league.policy.foreignLimit >= 0 && foreign > league.policy.foreignLimit) errors.push(`Too many import players (${foreign}/${league.policy.foreignLimit}).`);
  return { ok: errors.length === 0, errors };
}

/** Ensure a team has a legal-enough lineup; rebuild if broken. */
export function ensureLineup(world: World, team: Team): LineupSlot[] {
  const t = getTitle(team.titleId);
  const valid =
    team.lineup.length === t.teamSize &&
    team.lineup.every((s) => {
      const p = world.players[s.playerId];
      return p && team.roster.includes(s.playerId) && !(p.injury && p.injury.untilDay > world.day) && !p.retired;
    });
  if (!valid) {
    team.lineup = autoLineup(world, team);
    team.lineupSig = lineupSignature(world, team);
  } else if (team.prefs.delegateLineup) {
    const sig = lineupSignature(world, team);
    if (sig !== team.lineupSig) {
      team.lineup = autoLineup(world, team);
      team.lineupSig = sig;
    }
  }
  return team.lineup;
}

/** Changes whenever the coach's automatic lineup choice could change. */
function lineupSignature(world: World, team: Team): string {
  let s = '';
  for (const id of team.roster) {
    const p = world.players[id];
    if (!p) continue;
    s += `${id}.${p.attrVersion}.${p.injury && p.injury.untilDay > world.day ? 1 : 0}.${Math.round(p.fatigue / 25)};`;
  }
  return s;
}

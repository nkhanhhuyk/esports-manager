/**
 * Team match context: combines individual effectiveness with conditions, chemistry, coaching,
 * preparation and meta adaptation into per-phase team strengths consumed by the engines.
 */
import { intelEdge, sharpness } from '../../systems/sessions';
import { clamp } from '../../core/math';
import type { RNG } from '../../core/rng';
import { getTitle } from '../../data/titles/catalogue';
import type { Phase, RoleDef, TitleDef } from '../../data/titles/types';
import type { LineupSlot, Player, Team, World } from '../../model/types';
import { staffQuality } from '../../systems/contracts';
import { ALL_PHASES, baseRatings, charProf, conditionMultiplier, consistencySd, familiarity, phaseRating, type BaseRatings, E_WEIGHTS } from './ratings';

export interface PlayerCtx {
  p: Player;
  role: RoleDef;
  roleId: string;
  charId?: string;
  T: number;
  R: number;
  P: number;
  base: BaseRatings;
  E: number;
  mult: number;
  phase: Record<Phase, number>;
}

export interface TeamCtx {
  team: Team;
  idx: number;
  players: PlayerCtx[];
  chem: number;
  coach: number;
  meta: number;
  prep: number;
  phase: Record<Phase, number>;
  overall: number;
  style: string;
  aggression: number;
  iglId: string | null;
}

export interface CtxOptions {
  mapId?: string;
  pressure?: number; // 0 = regular, 1 = grand final
  opponent?: Team;
  noise?: boolean;
  chars?: Record<string, string>; // playerId -> charId
}

/** Tunable: standard deviation of the per-game team form multiplier. */
export const TEAM_FORM_SD = 0.03;

const TEAM_PHASES: Phase[] = ['teamfight', 'utility', 'support', 'objective', 'igl', 'macro'];

export function headCoach(world: World, team: Team) {
  for (const sid of team.staff) {
    const s = world.staff[sid];
    if (s && (s.role === 'head-coach' || s.role === 'academy-coach')) return s;
  }
  return null;
}

export function teamStaffOf(world: World, team: Team, role: string) {
  return team.staff.map((id) => world.staff[id]).filter((s) => s && s.role === role);
}

function playerCtx(world: World, t: TitleDef, slot: LineupSlot, opts: CtxOptions, rng: RNG | null): PlayerCtx | null {
  const p = world.players[slot.playerId];
  if (!p) return null;
  const role = t.roles.find((r) => r.id === slot.roleId) ?? t.roles[0];
  const base = baseRatings(p, t, role);
  const { T, R } = familiarity(p, t.id, role.id);
  const charId = opts.chars?.[p.id];
  const P = charProf(p, t, role, charId);
  let mult = conditionMultiplier(p);
  if (opts.mapId && t.maps.length > 1) {
    const mp = p.prof.maps[t.id]?.[opts.mapId] ?? 40;
    mult *= 0.965 + 0.06 * (mp / 100);
  }
  if (opts.pressure) {
    // Big-match temperament: composure (attr 34) and hidden pressure personality.
    const comp = (p.attrs[34] + p.pers.pressure) / 200;
    mult *= 1 + opts.pressure * (comp - 0.55) * 0.05;
  }
  if (rng && opts.noise !== false) mult *= 1 + rng.normal(0, consistencySd(p));
  const phase = {} as Record<Phase, number>;
  for (const ph of ALL_PHASES) phase[ph] = phaseRating(base, ph, T, R, P) * mult;
  const E = (E_WEIGHTS.C * base.C + E_WEIGHTS.S * base.S + E_WEIGHTS.T * T + E_WEIGHTS.R * R + E_WEIGHTS.P * P) * mult;
  return { p, role, roleId: role.id, charId, T, R, P, base, E, mult, phase };
}

export function buildTeamCtx(world: World, team: Team, lineup: LineupSlot[], idx: number, rng: RNG | null, opts: CtxOptions = {}): TeamCtx {
  const t = getTitle(team.titleId);
  const players = lineup.map((s) => playerCtx(world, t, s, opts, rng)).filter((x): x is PlayerCtx => !!x);
  const coachS = headCoach(world, team);
  const coachQ = coachS ? staffQuality(coachS) : 35;
  const coachFam = coachS ? (coachS.titleFam[t.id] ?? 30) : 30;
  const coach = 0.985 + 0.03 * ((coachQ * 0.7 + coachFam * 0.3) / 100);
  const chem = 0.95 + 0.1 * (team.chemistry / 100);
  const meta = 0.97 + 0.04 * (team.metaAdapt / 100);
  let prep = 1;
  if (opts.opponent) {
    // Preparation sessions held this week (older saves: the analyst-prep switch), scrim intel on
    // this opponent, and practice sharpness from recent scrims.
    const level = team.training.prep ?? (team.training.opponentPrep ? 1 : 0);
    if (level > 0) {
      const analyst = teamStaffOf(world, team, 'analyst')[0];
      const a = analyst ? analyst.attrs.analysis : 30;
      const fac = world.orgs[team.orgId]?.facilities.analytics ?? 3;
      prep = 1 + (0.008 * (a / 100) + 0.004 * (fac / 10)) * level;
    }
    prep *= intelEdge(world, team, opts.opponent);
  }
  if (!team.national) prep *= sharpness(team);
  // Team-level "day form": shared good/bad days create believable upsets without making
  // individual events random.
  const teamForm = rng && opts.noise !== false ? 1 + rng.normal(0, TEAM_FORM_SD) : 1;
  const phase = {} as Record<Phase, number>;
  for (const ph of ALL_PHASES) {
    let num = 0;
    let den = 0;
    for (const pc of players) {
      const inv = pc.role.phases[ph] ?? 0;
      num += inv * pc.phase[ph];
      den += inv;
    }
    let v = den > 0 ? num / den : players.reduce((s, pc) => s + pc.phase[ph], 0) / Math.max(1, players.length);
    if (ph === 'igl') {
      const best = Math.max(...players.map((pc) => pc.phase.igl));
      const avg = players.reduce((s, pc) => s + pc.phase.igl, 0) / Math.max(1, players.length);
      v = 0.6 * best + 0.4 * avg;
    }
    let m = coach * meta * prep * teamForm;
    if (TEAM_PHASES.includes(ph)) m *= chem;
    phase[ph] = v * m;
  }
  let overall = 0;
  let wsum = 0;
  for (const [ph, w] of Object.entries(t.phaseWeights)) {
    overall += (phase[ph as Phase] ?? 0) * (w ?? 0);
    wsum += w ?? 0;
  }
  overall = wsum ? overall / wsum : 0;
  // Penalise short-handed teams heavily.
  if (players.length < t.teamSize) overall *= players.length / t.teamSize;
  let iglId: string | null = null;
  let bestIgl = -1;
  for (const pc of players) {
    if (pc.phase.igl > bestIgl) {
      bestIgl = pc.phase.igl;
      iglId = pc.p.id;
    }
  }
  return { team, idx, players, chem: team.chemistry, coach, meta, prep, phase, overall, style: team.prefs.style, aggression: team.prefs.aggression, iglId };
}

/** Deterministic overall strength of a team's current lineup (no randomness). */
export function computeTeamStrength(world: World, team: Team): number {
  if (!team.lineup.length) return 0;
  const ctx = buildTeamCtx(world, team, team.lineup, 0, null, { noise: false });
  return Math.round(ctx.overall * 10) / 10;
}

/** Logistic win probability from a strength difference (rating points). */
export function winProb(diff: number, scale = 7.2): number {
  return 1 / (1 + Math.exp(-diff / scale));
}

export function clampRating(x: number): number {
  return clamp(x, 0, 120);
}

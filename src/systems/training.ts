/**
 * Training, progression, fatigue, injuries and aging.
 *
 * - Permanent attributes move slowly (weekly), driven by potential headroom, age curve,
 *   trajectory, work ethic / learning speed / coachability, coaching staff and facilities.
 * - Proficiencies (title, role, characters, maps, weapons, input) move faster.
 * - Form fluctuates quickly (per game), fatigue accumulates with games & training intensity.
 * - Repeating the same team focus has diminishing returns.
 */
import { clamp } from '../core/math';
import { ageFloat } from '../core/date';
import { RNG } from '../core/rng';
import { ATTRS, ATTR_COUNT, GROUP_INDICES, inputIndex, MOBA_INDICES, SHOOTER_INDICES } from '../model/attributes';
import type { Player, Team, TrainingFocus, World } from '../model/types';
import { getTitle, titleSimilarity } from '../data/titles/catalogue';
import { computeCA } from '../sim/common/ratings';
import { teamStaffOf, headCoach } from '../sim/common/team';
import { addInbox } from './news';

export const FOCUS_LABEL: Record<TrainingFocus, string> = {
  balanced: 'Balanced',
  mechanics: 'Core mechanics',
  moba: 'MOBA specialisation',
  shooter: 'Shooter specialisation',
  roles: 'Role training',
  characters: 'Character pool',
  weapons: 'Weapons & equipment',
  maps: 'Maps & modes',
  tactics: 'Tactics & scrims',
  knowledge: 'Game knowledge',
  mental: 'Mental skills',
  input: 'Input adaptation',
  recovery: 'Recovery',
  teamwork: 'Teamwork & comms',
};

const FOCUS_GROUPS: Partial<Record<TrainingFocus, number[]>> = {
  mechanics: [...GROUP_INDICES.Mechanical, ...GROUP_INDICES.Combat],
  moba: MOBA_INDICES,
  shooter: SHOOTER_INDICES,
  tactics: GROUP_INDICES.Tactical,
  knowledge: GROUP_INDICES.Knowledge,
  mental: GROUP_INDICES.Mental,
  teamwork: GROUP_INDICES.Teamwork,
};

function focusWeight(i: number, focus: TrainingFocus, familySpec: number[]): number {
  const a = ATTRS[i];
  if (a.group === 'Development') return 0.08;
  const grp = FOCUS_GROUPS[focus];
  let w = 1;
  if (grp) w = grp.includes(i) ? 3 : 0.5;
  // Specialist attributes from the other family barely develop without explicit focus.
  if ((a.group === 'MOBA' || a.group === 'Shooter') && !familySpec.includes(i) && focus !== 'moba' && focus !== 'shooter') w *= 0.15;
  return w * a.trainability;
}

export function trainingQuality(world: World, team: Team | null): number {
  if (!team) return 0.55;
  const org = world.orgs[team.orgId];
  const coach = headCoach(world, team);
  const asst = teamStaffOf(world, team, 'assistant-coach')[0] ?? teamStaffOf(world, team, 'academy-coach')[0];
  const dev = ((coach?.attrs.development ?? 35) + (asst?.attrs.development ?? 30) * 0.6) / 160;
  const fac = org ? (org.facilities.training + org.facilities.equipment + (team.tier !== 'main' ? org.facilities.academy : org.facilities.analytics)) / 30 : 0.3;
  return clamp(0.6 + dev * 0.5 + fac * 0.35, 0.5, 1.5);
}

function recoveryRate(world: World, p: Player, team: Team | null): number {
  const org = team ? world.orgs[team.orgId] : null;
  const perf = team ? teamStaffOf(world, team, 'performance-coach')[0] : undefined;
  return 1.6 + (p.attrs[61] / 100) * 2 + (org ? (org.facilities.housing + org.facilities.performance) / 20 : 0) + (perf ? perf.attrs.performance / 100 : 0);
}

/** Daily: fatigue recovery, injury healing and setbacks. */
export function dailyCondition(world: World): void {
  const rng = new RNG(`${world.meta.seed}|cond|${world.day}`);
  for (const p of Object.values(world.players)) {
    if (p.retired) continue;
    const team = p.teamId ? world.teams[p.teamId] : null;
    if (p.injury && p.injury.untilDay <= world.day) {
      p.injury = null;
    }
    if (p.fatigue > 0) p.fatigue = Math.max(0, p.fatigue - recoveryRate(world, p, team));
    if (!p.injury && p.fatigue > 55 && rng.chance(0.0015 * (p.fatigue / 50) * (1.3 - p.attrs[60] / 100))) {
      const days = rng.int(5, 35);
      p.injury = { label: rng.pick(['Wrist strain', 'Burnout', 'Back pain', 'Illness', 'Eye strain', 'Tendonitis']), untilDay: world.day + days };
      if (team && team.orgId === world.meta.userOrgId) addInbox(world, { from: 'Medical', subject: `${p.handle} sidelined (${p.injury.label})`, body: `${p.handle} will miss about ${days} days. High fatigue increases health setbacks — consider lower training intensity.`, kind: 'injury' });
    }
  }
}

function ageCurve(p: Player, age: number): { grow: number; decline: number } {
  const peak = p.dev.peakAge;
  const dAge = p.dev.declineAge;
  const grow = age < peak - 3 ? 1 : age < peak ? 0.7 : age < dAge ? 0.25 : 0.05;
  const decline = age > dAge ? (age - dAge + 0.5) * 0.028 * p.dev.declineRate : 0;
  return { grow, decline };
}

/** Weekly training & progression for every active player. */
export function weeklyTraining(world: World): void {
  const rng = new RNG(`${world.meta.seed}|train|${world.day}`);
  const teamQ = new Map<string, number>();
  const teamFocusMult = new Map<string, number>();
  for (const team of Object.values(world.teams)) {
    if (!team.active) continue;
    teamQ.set(team.id, trainingQuality(world, team));
    const tp = team.training;
    if (tp.lastFocus === tp.focus) tp.focusWeeks = (tp.focusWeeks ?? 0) + 1;
    else {
      tp.focusWeeks = 1;
      tp.lastFocus = tp.focus;
    }
    // Diminishing returns on repetitive training: opponents and metas move on.
    teamFocusMult.set(team.id, (tp.focusWeeks ?? 0) > 8 ? 0.75 : 1);
    // Scrims & tactics training improve short-term practice quality.
    const scrim = tp.focus === 'tactics' ? 6 : tp.scrimsPerWeek;
    team.scrimRating = clamp(team.scrimRating * 0.85 + scrim * 2.5 * (teamFocusMult.get(team.id) ?? 1), 0, 100);
    if (tp.focus === 'tactics' || tp.focus === 'knowledge') team.metaAdapt = clamp(team.metaAdapt + 1.2 * (teamFocusMult.get(team.id) ?? 1), 0, 100);
    if (tp.focus === 'teamwork') team.chemistry = clamp(team.chemistry + 0.8, 0, 100);
    team.chemistry = clamp(team.chemistry + 0.05 * (tp.scrimsPerWeek / 3) - (team.chemistry - 60) * 0.004, 0, 100);
  }
  for (const p of Object.values(world.players)) {
    if (p.retired) continue;
    const team = p.teamId ? world.teams[p.teamId] : null;
    const t = getTitle(team?.titleId ?? p.titleId);
    const age = ageFloat(p.birth, world.day);
    const q = team ? teamQ.get(team.id) ?? 0.8 : 0.55;
    const focus: TrainingFocus = team ? team.training.individual[p.id] ?? team.training.focus : 'balanced';
    const intensity = team ? team.training.intensity / 100 : 0.4;
    const mult = team ? teamFocusMult.get(team.id) ?? 1 : 1;
    const ethic = (p.attrs[55] + p.attrs[56] + p.attrs[57]) / 300; // learning, work ethic, coachability
    const { grow, decline } = ageCurve(p, age);
    const gap = Math.max(0, p.pa - p.ca);
    const overtrained = p.fatigue > 70 ? 0.4 : 1;
    const playing = team && team.lineup.some((s) => s.playerId === p.id) ? 1.1 : team ? 0.85 : 0.7;
    const budget = 0.062 * p.dev.growthRate * Math.pow(gap / 10, 0.85) * grow * q * (0.5 + ethic) * (0.6 + intensity * 0.6) * mult * overtrained * playing;
    const familySpec = t.family === 'MOBA' ? MOBA_INDICES : SHOOTER_INDICES;
    const famSet = t.engine === 'hybrid' ? [...MOBA_INDICES, ...SHOOTER_INDICES] : familySpec;
    let changed = false;
    if (budget > 0.0005 || decline > 0) {
      const points = budget * 110;
      const weights = new Array<number>(ATTR_COUNT);
      let wsum = 0;
      for (let i = 0; i < ATTR_COUNT; i++) {
        const room = (100 - p.attrs[i]) / 100;
        weights[i] = focusWeight(i, focus, famSet) * room;
        wsum += weights[i];
      }
      for (let i = 0; i < ATTR_COUNT; i++) {
        let d = wsum > 0 ? (points * weights[i]) / wsum : 0;
        const a = ATTRS[i];
        if (decline > 0) {
          if (a.curve === 'mech') d -= decline;
          else if (a.curve === 'combat') d -= decline * 0.5;
          else if (a.curve === 'physical' && (a.key === 'stamina' || a.key === 'recovery')) d -= decline * 0.4;
          else if (a.curve === 'mind' || a.curve === 'knowledge') d += age < 31 ? 0.01 : -decline * 0.1;
        }
        if (p.dev.trajectory === 'volatile') d += rng.normal(0, 0.04);
        if (d !== 0) {
          p.attrs[i] = Math.round(clamp(p.attrs[i] + d, 1, 100) * 100) / 100;
          changed = true;
        }
      }
    }
    // Proficiency training
    const learn = 0.5 + p.attrs[55] / 100;
    const tId = t.id;
    if (team) {
      const sim = titleSimilarity(p.titleId, tId);
      const titleRate = 0.12 * learn * (0.6 + (1 - sim) * 0.8) * q;
      p.prof.titles[tId] = clamp((p.prof.titles[tId] ?? 0) + titleRate * (1 - (p.prof.titles[tId] ?? 0) / 102), 0, 100);
      const ii = inputIndex(t.input);
      const inputRate = (focus === 'input' ? 0.9 : 0.12) * learn * q;
      p.input[ii] = Math.round(clamp(p.input[ii] + inputRate * (1 - p.input[ii] / 102), 0, 100) * 10) / 10;
      const slot = team.lineup.find((s) => s.playerId === p.id);
      const roleId = slot?.roleId ?? p.roles[0];
      const rk = `${tId}:${roleId}`;
      const roleRate = (focus === 'roles' ? 0.6 : 0.12) * learn * q;
      p.prof.roles[rk] = clamp((p.prof.roles[rk] ?? 0) + roleRate * (1 - (p.prof.roles[rk] ?? 0) / 102), 0, 100);
      if (focus === 'characters' && t.characters.length) {
        const role = t.roles.find((r) => r.id === roleId)!;
        const cm = (p.prof.chars[tId] ||= {});
        const pool = t.characters.filter((c) => role.classes.includes(c.cls)).slice(0, 10);
        for (const c of rng.sample(pool, 3)) cm[c.id] = clamp((cm[c.id] ?? 15) + 1.2 * learn * q, 0, 100);
      }
      if (focus === 'maps' && t.maps.length > 1) {
        const mm = (p.prof.maps[tId] ||= {});
        for (const m of rng.sample(t.maps, 3)) mm[m.id] = clamp((mm[m.id] ?? 20) + 1.2 * learn * q, 0, 100);
      }
      if ((focus === 'weapons' || focus === 'shooter') && t.weaponClasses.length) {
        const wm = (p.prof.weapons[tId] ||= {});
        for (const w of t.weaponClasses) wm[w] = clamp((wm[w] ?? 20) + 0.5 * learn * q, 0, 100);
      }
      // Fatigue from training load (recovery focus lowers it)
      p.fatigue = clamp(p.fatigue + (focus === 'recovery' ? -6 : intensity * 5 - 1), 0, 100);
      if (focus === 'mental') p.morale = clamp(p.morale + 0.6, 0, 100);
    }
    // Morale drift & playing-time satisfaction
    const starter = team?.lineup.some((s) => s.playerId === p.id);
    const benchPain = team && !starter ? (p.pers.ambition / 100) * 0.8 : 0;
    p.morale = clamp(p.morale + (62 - p.morale) * 0.05 - benchPain, 0, 100);
    p.unhappy = clamp(p.unhappy * 0.95 + benchPain * 2 + (p.morale < 35 ? 1 : 0), 0, 100);
    if (changed) {
      p.attrVersion++;
      p.ca = computeCA(p, getTitle(p.titleId));
    }
  }
}

/** Yearly hidden-potential revisions, resurgences and CA history snapshots. */
export function yearlyDevelopment(world: World, rng: RNG): void {
  for (const p of Object.values(world.players)) {
    if (p.retired) continue;
    const age = ageFloat(p.birth, world.day);
    // Unexpected development: potential is not fixed.
    if (age < 24) p.pa = clamp(p.pa + rng.normal(0, 1.8), p.ca, 99);
    if (p.dev.trajectory === 'resurgent' && !p.dev.resurgenceUsed && age > 25 && rng.chance(0.2)) {
      p.dev.resurgenceUsed = true;
      p.pa = clamp(Math.max(p.pa, p.ca) + rng.float(3, 8), 0, 99);
      p.dev.declineAge += rng.int(2, 3);
      p.dev.peakAge = Math.max(p.dev.peakAge, Math.floor(age) + 1);
    }
    if (age > p.dev.declineAge) p.pa = Math.max(p.ca, p.pa - rng.float(0.5, 2));
    p.caHistory.push({ season: world.season, ca: p.ca, titleId: p.titleId });
    if (p.caHistory.length > 30) p.caHistory.splice(0, p.caHistory.length - 30);
  }
}

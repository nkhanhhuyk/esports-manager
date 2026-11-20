/**
 * Training, progression, fatigue, injuries and aging.
 *
 * - Permanent attributes move slowly (weekly), driven by potential headroom, age curve,
 *   trajectory, work ethic / learning speed / coachability, coaching staff and facilities.
 * - Proficiencies (title, role, characters, maps, weapons, input) move faster.
 * - Form fluctuates quickly (per game), fatigue accumulates with games & training intensity.
 * - Repeating the same team focus has diminishing returns.
 */
import { onLeave } from './availability';
import { clamp } from '../core/math';
import { ageFloat } from '../core/date';
import { RNG } from '../core/rng';
import { ATTRS, ATTR_COUNT, GROUP_INDICES, inputIndex, MOBA_INDICES, SHOOTER_INDICES } from '../model/attributes';
import type { Player, Team, TrainingFocus, World } from '../model/types';
import { getTitle, titleSimilarity } from '../data/titles/catalogue';
import { computeCA } from '../sim/common/ratings';
import { teamStaffOf, headCoach } from '../sim/common/team';
import { addInbox } from './news';
import { runTrainingWeek, type WeekSummary } from './sessions';

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

const focusCache = new Map<string, Float64Array>();

/** Per-attribute base training weights for a focus and title family (cached). */
function focusWeights(focus: TrainingFocus, famKey: string, famSet: number[]): Float64Array {
  const key = `${focus}|${famKey}`;
  let w = focusCache.get(key);
  if (!w) {
    w = new Float64Array(ATTR_COUNT);
    for (let i = 0; i < ATTR_COUNT; i++) w[i] = focusWeight(i, focus, famSet);
    focusCache.set(key, w);
  }
  return w;
}

/** Decline multiplier per attribute: mechanics fade fastest, mind/knowledge barely. */
const DECLINE = Float64Array.from(ATTRS.map((a) => (a.curve === 'mech' ? 1 : a.curve === 'combat' ? 0.5 : a.curve === 'physical' && (a.key === 'stamina' || a.key === 'recovery') ? 0.4 : 0)));
const MIND = Uint8Array.from(ATTRS.map((a) => (a.curve === 'mind' || a.curve === 'knowledge' ? 1 : 0)));

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
  const teamRec = new Map<string, number>();
  for (const p of Object.values(world.players)) {
    if (p.retired) continue;
    if (p.fatigue <= 0 && !p.injury) continue;
    const team = p.teamId ? world.teams[p.teamId] : null;
    if (p.injury && p.injury.untilDay <= world.day) {
      p.injury = null;
    }
    if (p.fatigue > 0) {
      let base = team ? teamRec.get(team.id) : 1.6;
      if (base === undefined) {
        base = recoveryRate(world, { attrs: new Array(86).fill(0) } as unknown as Player, team);
        teamRec.set(team!.id, base);
      }
      p.fatigue = Math.max(0, p.fatigue - (base + (p.attrs[61] / 100) * 2));
    }
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
const HYBRID_SPEC = [...MOBA_INDICES, ...SHOOTER_INDICES];

export function weeklyTraining(world: World): void {
  const rng = new RNG(`${world.meta.seed}|train|${world.day}`);
  const progressWeek = Math.floor(world.day / 7) % 2 === 0;
  // Plan, book and hold this week's sessions; only held sessions count below.
  const week = runTrainingWeek(world);
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
    const fm = (tp.focusWeeks ?? 0) > 8 ? 0.75 : 1;
    teamFocusMult.set(team.id, fm);
    const ws = week.get(team.id);
    if (!ws) {
      team.chemistry = clamp(team.chemistry - (team.chemistry - 60) * 0.004, 0, 100);
      continue;
    }
    const h = ws.held;
    // Practice sharpness comes from scrims actually played (reviewed scrims teach more) and tactics.
    team.scrimRating = clamp(team.scrimRating * 0.75 + (ws.scrimQ * 3.5 + h.tactics * 0.8) * fm, 0, 100);
    const mapsMeta = getTitle(team.titleId).maps.length > 1 ? 0 : 0.2;
    team.metaAdapt = clamp(team.metaAdapt + (h.patch * 1 + h.scrim * 0.25 + h.tactics * 0.15 + h.maps * mapsMeta) * fm, 0, 100);
    team.chemistry = clamp(team.chemistry + h.tactics * 0.012 + h.scrim * 0.01 + h.mental * 0.015 + (tp.focus === 'teamwork' ? 0.6 : 0) - (team.chemistry - 60) * 0.004, 0, 100);
  }
  for (const p of Object.values(world.players)) {
    if (p.retired) continue;
    const team = p.teamId ? world.teams[p.teamId] : null;
    const t = getTitle(team?.titleId ?? p.titleId);
    const age = ageFloat(p.birth, world.day);
    const q = team ? teamQ.get(team.id) ?? 0.8 : 0.55;
    const focus: TrainingFocus = team ? team.training.individual[p.id] ?? team.training.focus : 'balanced';
    const intensity = team ? team.training.intensity / 100 : 0.4;
    const ws: WeekSummary | undefined = team ? week.get(team.id) : undefined;
    // Players away on a break do not train.
    const away = onLeave(p, world.day);
    // Development from the sessions held this week (no sessions → no training growth).
    const sessionDev = away ? 0 : ws ? ws.devFactor : 0.6 + intensity * 0.6;
    const sf = away ? 0 : ws ? clamp(ws.devFactor, 0, 1) : 1;
    const mult = team ? teamFocusMult.get(team.id) ?? 1 : 1;
    const ethic = (p.attrs[55] + p.attrs[56] + p.attrs[57]) / 300; // learning, work ethic, coachability
    const { grow, decline } = ageCurve(p, age);
    const gap = Math.max(0, p.pa - p.ca);
    const overtrained = p.fatigue > 70 ? 0.4 : 1;
    const playing = team && team.lineup.some((s) => s.playerId === p.id) ? 1.1 : team ? 0.85 : 0.7;
    // Permanent attributes progress every second week (double step) — same yearly rate,
    // half the computation and cache invalidation in very large worlds.
    const budget = (progressWeek ? 2 : 0) * 0.062 * p.dev.growthRate * Math.pow(gap / 10, 0.85) * grow * q * (0.5 + ethic) * sessionDev * mult * overtrained * playing;
    const famKey = t.family;
    const famSet = t.family === 'MOBA' ? MOBA_INDICES : SHOOTER_INDICES;
    let changed = false;
    const dec = progressWeek ? decline * 2 : 0;
    if (progressWeek && (budget > 0.0005 || dec > 0)) {
      const points = budget * 110;
      const base = focusWeights(focus, famKey, famSet);
      const weights = new Float64Array(ATTR_COUNT);
      let wsum = 0;
      for (let i = 0; i < ATTR_COUNT; i++) {
        const wi = base[i] * (100 - p.attrs[i]) * 0.01;
        weights[i] = wi;
        wsum += wi;
      }
      const volatile = p.dev.trajectory === 'volatile';
      for (let i = 0; i < ATTR_COUNT; i++) {
        let d = wsum > 0 ? (points * weights[i]) / wsum : 0;
        if (dec > 0) d -= dec * DECLINE[i];
        if (MIND[i]) d += age < 31 ? 0.02 : -dec * 0.1;
        if (volatile) d += rng.normal(0, 0.057);
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
      const titleRate = 0.12 * learn * (0.6 + (1 - sim) * 0.8) * q * sf;
      p.prof.titles[tId] = clamp((p.prof.titles[tId] ?? 0) + titleRate * (1 - (p.prof.titles[tId] ?? 0) / 102), 0, 100);
      const ii = inputIndex(t.input);
      const inputRate = (focus === 'input' ? 0.9 : 0.12) * learn * q * sf;
      p.input[ii] = Math.round(clamp(p.input[ii] + inputRate * (1 - p.input[ii] / 102), 0, 100) * 10) / 10;
      const slot = team.lineup.find((s) => s.playerId === p.id);
      const roleId = slot?.roleId ?? p.roles[0];
      const rk = `${tId}:${roleId}`;
      const roleRate = (focus === 'roles' ? 0.6 : 0.12) * learn * q * sf;
      p.prof.roles[rk] = clamp((p.prof.roles[rk] ?? 0) + roleRate * (1 - (p.prof.roles[rk] ?? 0) / 102), 0, 100);
      // Character pools and maps grow in the pool / maps sessions held (and individual work on them).
      const indiv = ws ? ws.held.individual : 3;
      const poolBlocks = away ? 0 : Math.min(6, Math.round((ws ? ws.held.pool : focus === 'characters' ? 2 : 0) + (focus === 'characters' ? indiv / 2 : 0)));
      if (poolBlocks > 0 && t.characters.length) {
        const role = t.roles.find((r) => r.id === roleId)!;
        const cm = (p.prof.chars[tId] ||= {});
        const pool = t.characters.filter((c) => role.classes.includes(c.cls)).slice(0, 10);
        for (let k = 0; k < poolBlocks; k++) for (const c of rng.sample(pool, 2)) cm[c.id] = clamp((cm[c.id] ?? 15) + 0.6 * learn * q, 0, 100);
      }
      const mapBlocks = away ? 0 : Math.min(6, Math.round((ws ? ws.held.maps : focus === 'maps' ? 2 : 0) + (focus === 'maps' ? indiv / 2 : 0)));
      if (mapBlocks > 0 && t.maps.length > 1) {
        const mm = (p.prof.maps[tId] ||= {});
        for (let k = 0; k < mapBlocks; k++) for (const m of rng.sample(t.maps, 2)) mm[m.id] = clamp((mm[m.id] ?? 20) + 0.6 * learn * q, 0, 100);
      }
      if ((focus === 'weapons' || focus === 'shooter') && t.weaponClasses.length) {
        const wm = (p.prof.weapons[tId] ||= {});
        for (const w of t.weaponClasses) wm[w] = clamp((wm[w] ?? 20) + 0.5 * learn * q * sf, 0, 100);
      }
      // Fatigue from the training load actually held (rest blocks and a recovery focus lower it).
      if (!away) p.fatigue = clamp(p.fatigue + (focus === 'recovery' ? -6 : ws ? ws.fatigue : intensity * 5 - 1), 0, 100);
      if (focus === 'mental') p.morale = clamp(p.morale + 0.6, 0, 100);
      if (ws?.held.mental) p.morale = clamp(p.morale + 0.3 * ws.held.mental, 0, 100);
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

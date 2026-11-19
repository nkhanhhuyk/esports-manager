/**
 * Player effectiveness model.
 *
 *   E = 0.40C + 0.30S + 0.15T + 0.10R + 0.05P
 *
 * C = weighted relevant core attributes (after input adaptation)
 * S = weighted relevant specialist attributes (after input adaptation)
 * T = title familiarity, R = role proficiency, P = character/equipment proficiency
 *
 * The same structure is applied per gameplay phase (laning, aim, utility, ...), using the
 * phase's attribute subset. Match engines consume phase ratings; E is the headline value used
 * for Current Ability and AI evaluation. Conditions (fatigue, form, morale, map familiarity,
 * day-to-day consistency noise) are applied separately as multipliers.
 */
import { ATTRS, ATTR_COUNT, inputIndex, MOBA_START } from '../../model/attributes';
import type { Player } from '../../model/types';
import { CORE_BASE_BY_GROUP, PERF_DEV_ATTRS, PHASE_ATTRS } from '../../data/titles/phases';
import type { Phase, RoleDef, TitleDef } from '../../data/titles/types';

export const E_WEIGHTS = { C: 0.4, S: 0.3, T: 0.15, R: 0.1, P: 0.05 };

export const ALL_PHASES = Object.keys(PHASE_ATTRS) as Phase[];

interface WeightSet {
  core: Float64Array;
  spec: Float64Array;
  coreSum: number;
  specSum: number;
}

interface RoleWeights {
  overall: WeightSet;
  phases: Record<Phase, WeightSet>;
}

const weightCache = new Map<string, RoleWeights>();

function specDefault(t: TitleDef, group: string): number {
  if (t.family === 'MOBA') return group === 'MOBA' ? 1 : 0;
  return group === 'Shooter' ? 1 : 0;
}

function relevance(t: TitleDef, idx: number): number {
  const a = ATTRS[idx];
  const r = t.specRelevance[a.key];
  return r === undefined ? specDefault(t, a.group) : r;
}

function makeSet(): WeightSet {
  return { core: new Float64Array(ATTR_COUNT), spec: new Float64Array(ATTR_COUNT), coreSum: 0, specSum: 0 };
}

function finish(ws: WeightSet): WeightSet {
  let c = 0;
  let s = 0;
  for (let i = 0; i < ATTR_COUNT; i++) {
    c += ws.core[i];
    s += ws.spec[i];
  }
  ws.coreSum = c;
  ws.specSum = s;
  return ws;
}

export function roleWeights(t: TitleDef, role: RoleDef): RoleWeights {
  const key = `${t.id}|${role.id}`;
  const cached = weightCache.get(key);
  if (cached) return cached;
  const overall = makeSet();
  for (const a of ATTRS) {
    const emph = role.emphasis[a.key] ?? 1;
    const tilt = t.coreTilt[a.key] ?? 1;
    if (a.index < MOBA_START) {
      let base = (CORE_BASE_BY_GROUP as Record<string, number>)[a.group] ?? 0;
      if (a.group === 'Development') base = PERF_DEV_ATTRS[a.key] ?? 0;
      overall.core[a.index] = base * emph * tilt;
    } else {
      overall.spec[a.index] = relevance(t, a.index) * emph;
    }
  }
  finish(overall);
  const phases = {} as Record<Phase, WeightSet>;
  for (const ph of Object.keys(PHASE_ATTRS) as Phase[]) {
    const ws = makeSet();
    const def = PHASE_ATTRS[ph];
    for (const [k, w] of Object.entries(def.core)) {
      const a = ATTRS.find((x) => x.key === k)!;
      const emph = Math.sqrt(role.emphasis[k] ?? 1);
      const tilt = t.coreTilt[k] ?? 1;
      if (a.index < MOBA_START) ws.core[a.index] += w * emph * tilt;
      else ws.spec[a.index] += w * emph * relevance(t, a.index);
    }
    for (const [k, w] of Object.entries(def.spec)) {
      const a = ATTRS.find((x) => x.key === k)!;
      const emph = Math.sqrt(role.emphasis[k] ?? 1);
      ws.spec[a.index] += w * emph * relevance(t, a.index);
    }
    phases[ph] = finish(ws);
  }
  const rw = { overall, phases };
  weightCache.set(key, rw);
  return rw;
}

/** Input proficiency for the title's required competitive input. */
export function inputProf(p: Player, t: TitleDef): number {
  return p.input[inputIndex(t.input)];
}

const SENS = Float64Array.from(ATTRS.map((a) => a.inputSensitivity));

function weightedEffective(attrs: readonly number[], w: Float64Array, sum: number, ip: number): number {
  if (sum <= 0) return 0;
  let s = 0;
  const pen = 0.35 * (1 - ip / 100);
  for (let i = 0; i < ATTR_COUNT; i++) {
    const wi = w[i];
    if (wi === 0) continue;
    s += wi * attrs[i] * (1 - SENS[i] * pen);
  }
  return s / sum;
}

export interface BaseRatings {
  C: number;
  S: number;
  phaseC: Record<string, number>;
  phaseS: Record<string, number | null>;
}

const baseCache = new Map<string, BaseRatings>();

export function clearRatingCache(): void {
  baseCache.clear();
  lightCache = new WeakMap();
}

/** Attribute-driven part of the ratings (cached per player attribute version). */
export function baseRatings(p: Player, t: TitleDef, role: RoleDef, ipOverride?: number): BaseRatings {
  const ip = ipOverride ?? inputProf(p, t);
  const key = `${p.id}|${p.attrVersion}|${t.id}|${role.id}|${Math.round(ip)}`;
  const c = baseCache.get(key);
  if (c) return c;
  const rw = roleWeights(t, role);
  const C = weightedEffective(p.attrs, rw.overall.core, rw.overall.coreSum, ip);
  const S = rw.overall.specSum > 0 ? weightedEffective(p.attrs, rw.overall.spec, rw.overall.specSum, ip) : C;
  const phaseC: Record<string, number> = {};
  const phaseS: Record<string, number | null> = {};
  for (const ph of ALL_PHASES) {
    const ws = rw.phases[ph];
    phaseC[ph] = weightedEffective(p.attrs, ws.core, ws.coreSum, ip);
    phaseS[ph] = ws.specSum > 0.5 ? weightedEffective(p.attrs, ws.spec, ws.specSum, ip) : null;
  }
  const out = { C, S, phaseC, phaseS };
  if (baseCache.size > 60000) baseCache.clear();
  baseCache.set(key, out);
  return out;
}

const clsCache = new WeakMap<TitleDef, Map<string, string>>();
function charClass(t: TitleDef, id: string): string | undefined {
  let m = clsCache.get(t);
  if (!m) {
    m = new Map(t.characters.map((c) => [c.id, c.cls]));
    clsCache.set(t, m);
  }
  return m.get(id);
}

/** Character / equipment proficiency P for a pick (or weapons for loadout titles). */
export function charProf(p: Player, t: TitleDef, role: RoleDef, charId?: string): number {
  if (t.characters.length > 0) {
    const map = p.prof.chars[t.id] ?? {};
    if (charId) {
      const v = map[charId];
      if (v !== undefined) return v;
      // Unfamiliar pick: some transfer from same-class experience.
      const cls = charClass(t, charId);
      let best = 0;
      for (const [cid, val] of Object.entries(map)) {
        if (val > best && charClass(t, cid) === cls) best = val;
      }
      return best * 0.45;
    }
    // Average of the three best comfort picks (single pass, no sort).
    let a = -1, b = -1, c = -1, n = 0;
    for (const k in map) {
      const v = map[k];
      n++;
      if (v > a) { c = b; b = a; a = v; } else if (v > b) { c = b; b = v; } else if (v > c) c = v;
    }
    if (!n) return 0;
    const k = Math.min(3, n);
    return ((a > 0 ? a : 0) + (k > 1 ? b : 0) + (k > 2 ? c : 0)) / k;
  }
  const wmap = p.prof.weapons[t.id] ?? {};
  const ws = role.weapons ?? t.weaponClasses.slice(0, 2);
  if (!ws.length) return p.prof.titles[t.id] ?? 0;
  return ws.reduce((s, w) => s + (wmap[w] ?? 0), 0) / ws.length;
}

export function familiarity(p: Player, titleId: string, roleId: string): { T: number; R: number } {
  return { T: p.prof.titles[titleId] ?? 0, R: p.prof.roles[`${titleId}:${roleId}`] ?? 0 };
}

/** Headline effectiveness E for a player in a title/role/character. */
export function effectiveness(p: Player, t: TitleDef, role: RoleDef, charId?: string, ipOverride?: number): number {
  const b = baseRatings(p, t, role, ipOverride);
  const { T, R } = familiarity(p, t.id, role.id);
  const P = charProf(p, t, role, charId);
  return E_WEIGHTS.C * b.C + E_WEIGHTS.S * b.S + E_WEIGHTS.T * T + E_WEIGHTS.R * R + E_WEIGHTS.P * P;
}

export function phaseRating(b: BaseRatings, ph: Phase, T: number, R: number, P: number): number {
  const C = b.phaseC[ph];
  const S = b.phaseS[ph] ?? C;
  return E_WEIGHTS.C * C + E_WEIGHTS.S * S + E_WEIGHTS.T * T + E_WEIGHTS.R * R + E_WEIGHTS.P * P;
}

/** Condition multiplier: fatigue, form, morale (applied per game). */
export function conditionMultiplier(p: Player): number {
  const fat = 1 - 0.12 * Math.pow(Math.max(0, p.fatigue) / 100, 1.5);
  const form = 1 + p.form * 0.003;
  const morale = 0.97 + 0.06 * (p.morale / 100);
  const injury = p.injury ? 0.88 : 1;
  return fat * form * morale * injury;
}

/** Standard deviation of day-to-day performance noise, driven by Consistency. */
export function consistencySd(p: Player): number {
  const cons = p.attrs[ATTRS.findIndex((a) => a.key === 'consistency')];
  const base = 0.035 * (1.4 - cons / 100);
  return p.dev.trajectory === 'volatile' ? base * 1.7 : base;
}

/** Best role for a player in a title by E (ignoring characters). */
export function bestRole(p: Player, t: TitleDef): { roleId: string; E: number } {
  let best = { roleId: t.roles[0].id, E: -1 };
  for (const r of t.roles) {
    const e = effectiveness(p, t, r);
    if (e > best.E) best = { roleId: r.id, E: e };
  }
  return best;
}

/** E without computing (or caching) phase ratings — cheap path for CA and AI scans. */
let lightCache = new WeakMap<Player, { v: number; m: Map<string, [number, number]> }>();

export function effectivenessLight(p: Player, t: TitleDef, role: RoleDef, charId?: string, ipOverride?: number): number {
  const ip = ipOverride ?? inputProf(p, t);
  let entry = lightCache.get(p);
  if (!entry || entry.v !== p.attrVersion) {
    entry = { v: p.attrVersion, m: new Map() };
    lightCache.set(p, entry);
  }
  const key = role.id + t.id + Math.round(ip * 10);
  let cs = entry.m.get(key);
  if (!cs) {
    const rw = roleWeights(t, role);
    const C0 = weightedEffective(p.attrs, rw.overall.core, rw.overall.coreSum, ip);
    cs = [C0, rw.overall.specSum > 0 ? weightedEffective(p.attrs, rw.overall.spec, rw.overall.specSum, ip) : C0];
    entry.m.set(key, cs);
  }
  const [C, S] = cs;
  const { T, R } = familiarity(p, t.id, role.id);
  const P = charProf(p, t, role, charId);
  return E_WEIGHTS.C * C + E_WEIGHTS.S * S + E_WEIGHTS.T * T + E_WEIGHTS.R * R + E_WEIGHTS.P * P;
}

/** Current Ability = E in primary title & primary role with comfort characters. */
export function computeCA(p: Player, t: TitleDef): number {
  const role = t.roles.find((r) => r.id === p.roles[0]) ?? t.roles[0];
  return Math.round(effectivenessLight(p, t, role) * 10) / 10;
}

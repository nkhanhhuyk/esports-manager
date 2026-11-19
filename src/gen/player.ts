import { clamp, clamp100 } from '../core/math';
import type { RNG } from '../core/rng';
import { ATTRS, ATTR_COUNT, INPUTS, inputIndex, type InputKind } from '../model/attributes';
import type { DevProfile, Personality, Player, Trajectory, World } from '../model/types';
import { nextId } from '../model/ids';
import { COUNTRY_BY_CODE, REGION_BY_ID } from '../data/countries';
import { getTitle, TITLES, titleSimilarity } from '../data/titles/catalogue';
import type { TitleDef } from '../data/titles/types';
import { effectivenessLight as effectiveness } from '../sim/common/ratings';
import { makeHandle, personName } from './names';

export interface GenCtx {
  world: World;
  rng: RNG;
  handles: Set<string>;
}

export interface PlayerSpec {
  titleId: string;
  roleId?: string;
  nat: string;
  age: number;
  targetCA: number;
  /** Extra potential headroom (prospects). */
  potentialBoost?: number;
}

const MOBILE_FIRST_REGIONS = new Set(['ID', 'PH', 'MYSG', 'VN', 'TH', 'SA', 'MENA', 'LATN', 'AFR']);

export function randomPersonality(rng: RNG): Personality {
  const r = () => Math.round(clamp(rng.normal(55, 18), 1, 99));
  return { ambition: r(), loyalty: r(), temperament: r(), ego: r(), greed: r(), controversy: Math.round(clamp(rng.normal(30, 18), 1, 99)), pressure: r() };
}

export function randomDev(rng: RNG): DevProfile {
  const traj = rng.weighted<Trajectory>(['steady', 'early', 'late', 'volatile', 'resurgent'], (t) => ({ steady: 45, early: 18, late: 17, volatile: 12, resurgent: 8 })[t]);
  let peak: number;
  let growth: number;
  switch (traj) {
    case 'early':
      peak = rng.int(19, 22);
      growth = rng.float(1.2, 1.5);
      break;
    case 'late':
      peak = rng.int(25, 28);
      growth = rng.float(0.65, 0.9);
      break;
    case 'volatile':
      peak = rng.int(21, 26);
      growth = rng.float(0.8, 1.3);
      break;
    case 'resurgent':
      peak = rng.int(22, 25);
      growth = rng.float(0.9, 1.1);
      break;
    default:
      peak = rng.int(22, 25);
      growth = rng.float(0.85, 1.15);
  }
  return { trajectory: traj, peakAge: peak, declineAge: peak + rng.int(1, traj === 'late' ? 5 : 4), declineRate: rng.float(0.7, 1.4), growthRate: growth };
}

/** Pick a nationality for a player whose league plays in the given region list. */
export function pickNationality(rng: RNG, regions: string[], family: 'MOBA' | 'SHOOTER', titlePop: Record<string, number>): string {
  const cands: Array<{ code: string; w: number }> = [];
  for (const r of regions) {
    for (const c of Object.values(COUNTRY_BY_CODE)) {
      if (c.region !== r) continue;
      const scene = family === 'MOBA' ? c.moba : c.shooter;
      const pop = titlePop[r] ?? 30;
      cands.push({ code: c.code, w: c.weight * Math.pow(scene / 60, 2) * (0.3 + pop / 100) });
    }
  }
  if (!cands.length) return 'US';
  return rng.weighted(cands, (c) => c.w).code;
}

function ageFactor(age: number, curve: string): number {
  // Young players: raw mechanics ahead of knowledge. Older: knowledge ahead of mechanics.
  if (curve === 'mech') return age < 20 ? 2 : age > 27 ? -(age - 27) * 1.6 : 0;
  if (curve === 'knowledge' || curve === 'mind') return age < 22 ? -(22 - age) * 1.4 : Math.min(4, (age - 22) * 0.6);
  return 0;
}

/** Generate the 86-attribute array shaped by title family, role and age. */
export function generateAttributes(rng: RNG, t: TitleDef, roleId: string | undefined, age: number, mean: number): number[] {
  const role = t.roles.find((r) => r.id === roleId) ?? t.roles[0];
  const groupOffset: Record<string, number> = {};
  for (const g of ['Mechanical', 'Combat', 'Tactical', 'Mental', 'Teamwork', 'Knowledge']) groupOffset[g] = rng.normal(0, 5);
  const otherFamilyGap = rng.normal(24, 8);
  const attrs = new Array<number>(ATTR_COUNT);
  for (const a of ATTRS) {
    let v: number;
    if (a.group === 'Development') {
      v = rng.normal(52 + (mean - 60) * 0.25, 15);
    } else {
      v = mean + (groupOffset[a.group] ?? 0) + ageFactor(age, a.curve) + rng.normal(0, 6.5);
      const emph = role.emphasis[a.key];
      if (emph !== undefined) v += (emph - 1) * 7;
      if (a.group === 'MOBA' && t.family !== 'MOBA') v -= otherFamilyGap;
      if (a.group === 'Shooter' && t.family === 'MOBA') {
        // MOBA players are not trained shooters, though aim-heavy MOBAs (skillshot / third person) keep more.
        const rel = t.specRelevance[a.key] ?? 0;
        v -= otherFamilyGap * (1 - Math.min(0.7, rel));
      }
    }
    attrs[a.index] = Math.round(clamp(v, 1, 99));
  }
  return attrs;
}

function inputProfs(rng: RNG, t: TitleDef, nat: string, experience: number): { input: [number, number, number]; pref: InputKind } {
  const region = COUNTRY_BY_CODE[nat]?.region ?? 'NA';
  const mobileFirst = MOBILE_FIRST_REGIONS.has(region);
  const base: Record<InputKind, number> = {
    touch: clamp(rng.normal(mobileFirst ? 55 : 35, 18), 5, 90),
    mkb: clamp(rng.normal(mobileFirst ? 35 : 50, 18), 5, 90),
    controller: clamp(rng.normal(38, 18), 5, 90),
  };
  base[t.input] = clamp(62 + 33 * experience + rng.normal(0, 5), 30, 99);
  const input = INPUTS.map((k) => Math.round(base[k])) as [number, number, number];
  return { input, pref: t.input };
}

export function generatePlayer(ctx: GenCtx, spec: PlayerSpec): Player {
  const { rng, world } = ctx;
  const t = getTitle(spec.titleId);
  const roleId = spec.roleId ?? rng.pick(t.roles).id;
  const id = nextId(world, 'p');
  const birth = world.day - Math.floor(spec.age * 365.25) - rng.int(0, 364);
  const { first, last } = personName(rng, spec.nat);
  const handle = makeHandle(rng, ctx.handles);
  const experience = clamp((spec.age - 14) / 9, 0, 1) * clamp(0.55 + (spec.targetCA - 50) / 60, 0.3, 1);

  // Languages
  const ctry = COUNTRY_BY_CODE[spec.nat];
  const langs = [...(ctry?.langs ?? ['en'])];
  if (!langs.includes('en') && rng.chance(0.35 + experience * 0.2)) langs.push('en');

  const { input, pref } = inputProfs(rng, t, spec.nat, experience);

  // Proficiencies
  // Established pros start near their familiarity ceiling so play does not inflate ratings.
  const T = Math.round(clamp(40 + 58 * Math.pow(experience, 0.6) + rng.normal(0, 4), 15, 99));
  const prof: Player['prof'] = { titles: { [t.id]: T }, roles: {}, chars: {}, maps: {}, weapons: {} };
  const secondRole = rng.pick(t.roles.filter((r) => r.id !== roleId)).id;
  for (const r of t.roles) {
    const v = r.id === roleId ? T - 4 + rng.normal(0, 5) : r.id === secondRole ? T * 0.65 + rng.normal(0, 8) : T * 0.3 + rng.normal(0, 8);
    prof.roles[`${t.id}:${r.id}`] = Math.round(clamp(v, 5, 99));
  }
  // Secondary title background (cross-title careers)
  if (rng.chance(0.22)) {
    const other = rng.weighted(TITLES.filter((x) => x.id !== t.id), (x) => Math.pow(titleSimilarity(t.id, x.id), 2));
    prof.titles[other.id] = Math.round(clamp(rng.normal(40, 15), 10, 80));
    const orole = rng.pick(other.roles);
    prof.roles[`${other.id}:${orole.id}`] = Math.round(clamp(prof.titles[other.id] - 5 + rng.normal(0, 6), 5, 90));
  }
  if (t.characters.length) {
    const role = t.roles.find((r) => r.id === roleId)!;
    const pool = t.characters.filter((c) => role.classes.includes(c.cls));
    const others = t.characters.filter((c) => !role.classes.includes(c.cls));
    const n = rng.int(5, 11);
    const chosen = rng.sample(pool.length ? pool : t.characters, Math.min(n, pool.length || n));
    const extra = rng.sample(others, rng.int(1, 4));
    const m: Record<string, number> = {};
    chosen.forEach((c, i) => (m[c.id] = Math.round(clamp(T + 2 - i * 3.5 + rng.normal(0, 5), 10, 99))));
    extra.forEach((c) => (m[c.id] = Math.round(clamp(T * 0.55 + rng.normal(0, 10), 5, 85))));
    prof.chars[t.id] = m;
  }
  if (t.maps.length > 1) {
    const m: Record<string, number> = {};
    for (const mp of t.maps) m[mp.id] = Math.round(clamp(T - 10 + rng.normal(0, 10), 5, 99));
    prof.maps[t.id] = m;
  }
  if (t.weaponClasses.length) {
    const role = t.roles.find((r) => r.id === roleId)!;
    const w: Record<string, number> = {};
    for (const wc of t.weaponClasses) {
      const main = role.weapons?.includes(wc);
      w[wc] = Math.round(clamp((main ? T : T * 0.6) + rng.normal(0, 7), 5, 99));
    }
    prof.weapons[t.id] = w;
  }

  const player: Player = {
    id,
    first,
    last,
    handle,
    birth,
    nat: spec.nat,
    nat2: rng.chance(0.04) ? rng.pick(Object.keys(COUNTRY_BY_CODE)) : undefined,
    langs,
    teamId: null,
    titleId: t.id,
    roles: [roleId, secondRole],
    prefInput: pref,
    attrs: [],
    input,
    prof,
    pa: 0,
    ca: 0,
    form: Math.round(rng.normal(0, 2)),
    morale: Math.round(clamp(rng.normal(65, 10), 30, 95)),
    fatigue: Math.round(rng.float(0, 15)),
    injury: null,
    pers: randomPersonality(rng),
    dev: randomDev(rng),
    contract: null,
    value: 0,
    rep: 0,
    career: [],
    stats: [],
    awards: [],
    rel: {},
    caHistory: [],
    coachApt: Math.round(clamp(rng.normal(45, 18), 1, 99)),
    joinedDay: world.day,
    listed: false,
    unhappy: 0,
    retired: null,
    attrVersion: 0,
  };
  if (player.nat2 === player.nat) player.nat2 = undefined;

  // Calibrate attributes so E lands near the target CA.
  let mean = spec.targetCA - 4;
  const role = t.roles.find((r) => r.id === roleId)!;
  player.attrs = generateAttributes(rng, t, roleId, spec.age, mean);
  for (let it = 0; it < 3; it++) {
    player.attrVersion++;
    const e = effectiveness(player, t, role);
    const delta = (spec.targetCA - e) / 0.66;
    if (Math.abs(delta) < 0.6) break;
    for (let i = 0; i < ATTR_COUNT; i++) {
      if (ATTRS[i].group === 'Development') continue;
      player.attrs[i] = Math.round(clamp(player.attrs[i] + delta, 1, 99));
    }
    mean += delta;
  }
  player.attrVersion++;
  player.ca = Math.round(effectiveness(player, t, role) * 10) / 10;

  // Hidden potential: youth carry more headroom; elite youth occasionally very high ceilings.
  const ageGap = spec.age <= 17 ? rng.normal(13, 7) : spec.age <= 20 ? rng.normal(6, 5) : spec.age <= 23 ? rng.normal(2.5, 3) : rng.normal(0.8, 1.5);
  player.pa = Math.round(clamp(player.ca + Math.max(0, ageGap + (spec.potentialBoost ?? 0)), player.ca, 99) * 10) / 10;
  player.rep = Math.round(clamp((player.ca - 50) * 2 + rng.normal(0, 6), 1, 99));
  player.caHistory.push({ season: world.season, ca: player.ca, titleId: t.id });
  return player;
}

/** Region-aware import probability helper used by the world generator. */
export function regionTalentHub(family: 'MOBA' | 'SHOOTER'): string[] {
  return family === 'MOBA' ? ['KR', 'CN', 'VN', 'PH', 'ID'] : ['EUE', 'CIS', 'BR', 'NA', 'KR'];
}

export function regionOf(nat: string): string {
  return COUNTRY_BY_CODE[nat]?.region ?? 'NA';
}

export function economyOf(nat: string): number {
  return REGION_BY_ID[regionOf(nat)]?.economy ?? 0.5;
}

export { inputIndex };

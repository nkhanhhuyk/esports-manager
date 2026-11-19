import { clamp } from '../core/math';
import { STAFF_ATTRS, type StaffAttrKey } from '../model/attributes';
import type { Player, Staff, StaffRole } from '../model/types';
import { nextId } from '../model/ids';
import { COUNTRY_BY_CODE } from '../data/countries';
import { personName } from './names';
import type { GenCtx } from './player';

const ROLE_FOCUS: Record<StaffRole, Partial<Record<StaffAttrKey, number>>> = {
  'head-coach': { tactics: 8, drafting: 8, motivation: 5, manManagement: 6, metaReading: 5, communication: 4 },
  'assistant-coach': { development: 7, tactics: 5, drafting: 4, motivation: 4 },
  analyst: { analysis: 9, metaReading: 7, drafting: 5, tactics: 3 },
  scout: { judgingAbility: 9, judgingPotential: 9, adaptability: 3 },
  gm: { negotiation: 8, judgingAbility: 5, judgingPotential: 5, manManagement: 5 },
  director: { negotiation: 7, manManagement: 7, judgingPotential: 4 },
  'academy-coach': { youth: 9, development: 7, motivation: 4 },
  psychologist: { mentalCoaching: 10, motivation: 6, manManagement: 5 },
  'performance-coach': { performance: 10, discipline: 6, motivation: 3 },
};

export const STAFF_ROLE_LABEL: Record<StaffRole, string> = {
  'head-coach': 'Head Coach',
  'assistant-coach': 'Assistant Coach',
  analyst: 'Analyst',
  scout: 'Scout',
  gm: 'General Manager',
  director: 'Sporting Director',
  'academy-coach': 'Academy Coach',
  psychologist: 'Psychologist',
  'performance-coach': 'Performance Coach',
};

export function generateStaff(ctx: GenCtx, role: StaffRole, nat: string, titleId: string | null, quality: number, age?: number): Staff {
  const { rng, world } = ctx;
  const a = age ?? Math.round(clamp(rng.normal(34, 7), 23, 62));
  const { first, last } = personName(rng, nat);
  const attrs = {} as Record<StaffAttrKey, number>;
  const focus = ROLE_FOCUS[role];
  for (const [k] of STAFF_ATTRS) {
    const f = focus[k] ?? 0;
    attrs[k] = Math.round(clamp(rng.normal(quality - 12 + f * 1.6, 9), 1, 99));
  }
  const titleFam: Record<string, number> = {};
  if (titleId) titleFam[titleId] = Math.round(clamp(rng.normal(70 + (quality - 55) * 0.4, 10), 25, 99));
  const ctry = COUNTRY_BY_CODE[nat];
  return {
    id: nextId(world, 's'),
    first,
    last,
    birth: world.day - Math.floor(a * 365.25) - rng.int(0, 364),
    nat,
    langs: [...(ctry?.langs ?? ['en']), ...(rng.chance(0.5) ? ['en'] : [])].filter((v, i, arr) => arr.indexOf(v) === i),
    role,
    titleId,
    teamId: null,
    orgId: null,
    attrs,
    titleFam,
    contract: null,
    rep: Math.round(clamp((quality - 40) * 1.6 + rng.normal(0, 8), 1, 99)),
    career: [],
    awards: [],
    retired: null,
    pa: Math.round(clamp(quality + rng.normal(6, 6), quality, 99)),
  };
}

/** Convert a retiring player into a staff member, transferring knowledge into staff attributes. */
export function staffFromPlayer(ctx: GenCtx, p: Player, role: StaffRole): Staff {
  const apt = p.coachApt;
  const s = generateStaff(ctx, role, p.nat, p.titleId, 35 + apt * 0.45, undefined);
  const at = p.attrs;
  // Knowledge & leadership attributes carry over (indices from the 86-attribute table).
  s.attrs.tactics = Math.round(clamp((at[49] + at[30] + at[43]) / 3 * 0.7 + apt * 0.3, 1, 99)); // draft understanding, win-con, shotcalling
  s.attrs.drafting = Math.round(clamp((at[49] + at[48] + at[51]) / 3 * 0.7 + apt * 0.3, 1, 99));
  s.attrs.metaReading = Math.round(clamp((at[51] + at[52]) / 2 * 0.75 + apt * 0.25, 1, 99));
  s.attrs.motivation = Math.round(clamp(at[46] * 0.6 + apt * 0.4, 1, 99));
  s.attrs.communication = Math.round(clamp(at[42] * 0.7 + apt * 0.3, 1, 99));
  s.handle = p.handle;
  s.first = p.first;
  s.last = p.last;
  s.birth = p.birth;
  s.formerPlayerId = p.id;
  s.titleFam = { ...p.prof.titles };
  s.rep = Math.round(clamp(p.rep * 0.8, 1, 99));
  return s;
}

/**
 * Salary, market value and contract helpers.
 */
import { clamp } from '../core/math';
import { ageAt } from '../core/date';
import { getTitle } from '../data/titles/catalogue';
import { COUNTRIES, expandRegion, regionEconomy } from '../data/countries';
import type { Contract, League, Player, Staff, Team, World } from '../model/types';
import { nextId } from '../model/ids';

export function titlePopIn(world: World, titleId: string, region: string): number {
  const ts = world.titles[titleId];
  if (!ts) return 40;
  if (ts.popularity[region] !== undefined) return ts.popularity[region];
  // macro region: average of members
  const vals = Object.entries(ts.popularity)
    .filter(([r]) => region.includes(r))
    .map(([, v]) => v);
  return vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : ts.globalPop;
}

const refCache = new Map<string, number>();

/**
 * Typical Current Ability of an established pro in a title/region scene. Salaries are anchored
 * to it, so a strong league is not automatically bankrupting and stars stand out relative to
 * their own scene.
 */
export function leagueRefCA(world: World, titleId: string, region: string): number {
  const key = `${world.season}|${titleId}|${region}`;
  const c = refCache.get(key);
  if (c !== undefined) return c;
  const t = getTitle(titleId);
  const pop = titlePopIn(world, titleId, region);
  const members = expandRegion(region);
  let scene = 0;
  let n = 0;
  for (const cc of COUNTRIES) {
    if (!members.includes(cc.region)) continue;
    const sc = world.countryScene[cc.code];
    scene += (t.family === 'MOBA' ? sc?.moba : sc?.shooter) ?? 40;
    n++;
  }
  const strength = 0.6 * pop + 0.4 * (n ? scene / n : 40);
  const ref = 57 + 19 * (strength / 100);
  if (refCache.size > 5000) refCache.clear();
  refCache.set(key, ref);
  return ref;
}

/** Salary a player expects (USD / season) given ability, title popularity and region economy. */
export function expectedSalary(world: World, p: Player, titleId: string, region: string): number {
  const t = getTitle(titleId);
  const pop = titlePopIn(world, titleId, region);
  const econ = regionEconomy(region);
  const ca = p.ca;
  const caFactor = Math.exp((ca - leagueRefCA(world, titleId, region) - 1) / 12);
  const repFactor = 0.9 + (p.rep / 100) * 0.25;
  const greed = 0.9 + (p.pers.greed / 100) * 0.25;
  const raw = t.baseSalary * (0.4 + 0.6 * pop / 100) * econ * caFactor * repFactor * greed;
  const floor = 4000 * econ + 2000;
  return Math.round(Math.max(floor, raw) / 500) * 500;
}

export function expectedStaffSalary(world: World, s: Staff, titleId: string | null, region: string): number {
  const base = titleId ? getTitle(titleId).baseSalary : 150000;
  const q = staffQuality(s);
  const econ = regionEconomy(region);
  return Math.round(Math.max(5000, base * 0.11 * (0.4 + econ * 0.6) * Math.exp((q - 55) / 15)) / 500) * 500;
}

export function staffQuality(s: Staff): number {
  const a = s.attrs;
  switch (s.role) {
    case 'head-coach':
      return (a.tactics * 1.3 + a.drafting * 1.2 + a.motivation + a.manManagement + a.development + a.metaReading) / 6.5;
    case 'assistant-coach':
      return (a.tactics + a.development * 1.3 + a.drafting + a.motivation) / 4.3;
    case 'analyst':
      return (a.analysis * 1.5 + a.metaReading * 1.2 + a.drafting + a.tactics * 0.8) / 4.5;
    case 'scout':
      return (a.judgingAbility * 1.3 + a.judgingPotential * 1.3 + a.adaptability * 0.4) / 3;
    case 'gm':
    case 'director':
      return (a.negotiation * 1.3 + a.judgingAbility + a.judgingPotential + a.manManagement) / 4.3;
    case 'academy-coach':
      return (a.youth * 1.5 + a.development * 1.3 + a.motivation) / 3.8;
    case 'psychologist':
      return (a.mentalCoaching * 1.5 + a.motivation + a.manManagement) / 3.5;
    case 'performance-coach':
      return (a.performance * 1.6 + a.discipline + a.motivation * 0.5) / 3.1;
  }
}

/** Market value (transfer valuation) of a player. */
export function marketValue(world: World, p: Player): number {
  if (p.retired) return 0;
  const age = ageAt(p.birth, world.day);
  const region = p.teamId ? world.teams[p.teamId]?.region ?? 'NA' : 'NA';
  const sal = expectedSalary(world, p, p.titleId, region);
  const headroom = Math.max(0, p.pa - p.ca);
  const youth = age <= 21 ? 1 + headroom / 12 : age <= 25 ? 1 + headroom / 25 : Math.max(0.35, 1 - (age - 25) * 0.08);
  const yearsLeft = p.contract ? Math.max(0, p.contract.endSeason - world.season) + 0.5 : 0;
  const contractFactor = p.contract ? 0.6 + Math.min(1.4, yearsLeft * 0.45) : 0.25;
  return Math.round((sal * 1.4 * youth * contractFactor) / 1000) * 1000;
}

export function makeContract(
  world: World,
  team: Team,
  salary: number,
  years: number,
  opts: Partial<Pick<Contract, 'signingBonus' | 'winBonus' | 'prizeShare' | 'buyout' | 'option' | 'terminationFee' | 'loan'>> = {},
): Contract {
  return {
    id: nextId(world, 'c'),
    teamId: team.id,
    orgId: team.orgId,
    salary: Math.round(salary),
    startDay: world.day,
    endSeason: world.season + Math.max(0, years - 1),
    signingBonus: opts.signingBonus ?? Math.round(salary * 0.05),
    winBonus: opts.winBonus ?? Math.round(salary * 0.03),
    prizeShare: opts.prizeShare ?? 0.5,
    buyout: opts.buyout === undefined ? Math.round(salary * (2 + years)) : opts.buyout,
    option: opts.option ?? 'none',
    terminationFee: opts.terminationFee ?? Math.round(salary * 0.5),
    loan: opts.loan,
  };
}

export interface ContractValidation {
  ok: boolean;
  errors: string[];
}

/** Validate a proposed contract against league policy and organisation finances. */
export function validateContract(world: World, team: Team, p: Player, c: Pick<Contract, 'salary' | 'endSeason'>, league?: League | null): ContractValidation {
  const errors: string[] = [];
  if (!(c.salary > 0) || !Number.isFinite(c.salary)) errors.push('Salary must be positive.');
  if (c.endSeason < world.season) errors.push('Contract must run at least until the end of the current season.');
  if (c.endSeason > world.season + 5) errors.push('Maximum contract length is 5 seasons.');
  const lg = league ?? (team.leagueId ? world.leagues[team.leagueId] : null);
  if (lg) {
    if (c.salary < lg.policy.minSalary) errors.push(`Salary below league minimum (${lg.policy.minSalary}).`);
    if (lg.policy.maxSalary > 0 && c.salary > lg.policy.maxSalary) errors.push(`Salary above league maximum (${lg.policy.maxSalary}).`);
    const age = ageAt(p.birth, world.day);
    if (age < lg.policy.minAge) errors.push(`Player is under the league minimum age (${lg.policy.minAge}); can only join academy/youth sides.`);
    if (lg.policy.cap.type === 'hard') {
      const payroll = teamPayroll(world, team) - (p.contract?.teamId === team.id ? p.contract.salary : 0);
      if (payroll + c.salary > lg.policy.cap.amount) errors.push('Signing would exceed the hard salary cap.');
    }
  }
  return { ok: errors.length === 0, errors };
}

export function teamPayroll(world: World, team: Team): number {
  let s = 0;
  for (const pid of team.roster) {
    const c = world.players[pid]?.contract;
    if (c) s += c.loan && c.teamId === team.id ? c.salary * (1 - c.loan.wageSplit) : c.salary;
  }
  return s;
}

export function staffPayroll(world: World, team: Team): number {
  let s = 0;
  for (const sid of team.staff) s += world.staff[sid]?.contract?.salary ?? 0;
  return s;
}

/** How attractive a club/offer is to a player (0..~1.5). Used for accept/reject decisions. */
export function offerAppeal(world: World, p: Player, team: Team, salary: number): number {
  const org = world.orgs[team.orgId];
  const expected = expectedSalary(world, p, team.titleId, team.region);
  const money = clamp(salary / Math.max(1, expected), 0, 2);
  const prestige = (org?.rep ?? 40) / 100;
  const ambition = p.pers.ambition / 100;
  const greed = p.pers.greed / 100;
  const tierPenalty = team.tier === 'main' ? 0 : 0.15;
  const loyalty = p.teamId && world.teams[p.teamId]?.orgId === team.orgId ? (p.pers.loyalty / 100) * 0.2 : 0;
  const sameTitle = team.titleId === p.titleId ? 0 : -0.1 * (1 - p.attrs[36] / 100);
  return money * (0.55 + greed * 0.3) + prestige * (0.25 + ambition * 0.25) + loyalty + sameTitle - tierPenalty;
}

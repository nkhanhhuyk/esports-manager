/**
 * Organisation finances: monthly revenue & expenditure, ledgers, facility upgrades,
 * sponsorship renewal and financial distress.
 */
import { clamp } from '../core/math';
import { monthKey } from '../core/date';
import { regionEconomy } from '../data/countries';
import { getTitle } from '../data/titles/catalogue';
import type { FacilityKey, Organisation, World } from '../model/types';
import { nextId } from '../model/ids';
import { titlePopIn } from './contracts';
import { addInbox, addNews } from './news';
import { RNG } from '../core/rng';
import { makeCompanyName } from '../gen/names';

const LEDGER_MAX = 250;

export function ledger(world: World, org: Organisation, kind: string, amount: number, teamId?: string, note?: string): void {
  if (!Number.isFinite(amount)) return;
  org.balance = Math.round(org.balance + amount);
  org.ledger.push({ day: world.day, kind, amount: Math.round(amount), teamId, note });
  if (org.ledger.length > LEDGER_MAX) org.ledger.splice(0, org.ledger.length - LEDGER_MAX);
}

/**
 * Commercial value of a division (USD/season): what the title + region + roster size can
 * support. Revenue streams are proportional to it, scaled by organisation reputation, so a
 * mid-reputation organisation roughly breaks even before prize money and transfers.
 */
export function divisionValue(world: World, teamId: string): number {
  const team = world.teams[teamId];
  if (!team || !team.active) return 0;
  const t = getTitle(team.titleId);
  const pop = titlePopIn(world, team.titleId, team.region);
  return t.baseSalary * (t.teamSize + t.subs) * regionEconomy(team.region) * (0.4 + 0.6 * pop / 100) * (team.tier === 'main' ? 1 : 0.25);
}

export function orgCommercialValue(world: World, org: Organisation): number {
  return org.teams.reduce((a, id) => a + divisionValue(world, id), 0);
}

/** Monthly value of one new sponsorship deal for an organisation. */
export function sponsorDealValue(world: World, org: Organisation, deals: number, rng: RNG): number {
  return Math.round((orgCommercialValue(world, org) * 0.065 * (0.5 + org.rep / 100) * rng.float(0.8, 1.2)) / Math.max(1, deals) / 100) * 100;
}

export const FACILITY_LABEL: Record<FacilityKey, string> = {
  training: 'Training Centre',
  analytics: 'Analytics Infrastructure',
  academy: 'Academy Development',
  equipment: 'Practice Equipment',
  housing: 'Team Housing',
  performance: 'Performance Support',
};

export function facilityUpgradeCost(org: Organisation, key: FacilityKey): number {
  const lvl = org.facilities[key];
  return Math.round((40000 * Math.pow(lvl + 1, 1.7) * (0.4 + regionEconomy(org.region))) / 1000) * 1000;
}

export function facilityUpkeep(org: Organisation): number {
  let s = 0;
  for (const v of Object.values(org.facilities)) s += Math.pow(v, 1.5) * 350;
  return Math.round(s * (0.4 + regionEconomy(org.region)));
}

export function upgradeFacility(world: World, org: Organisation, key: FacilityKey): { ok: boolean; reason?: string } {
  if (org.facilities[key] >= 10) return { ok: false, reason: 'Already at maximum level.' };
  const cost = facilityUpgradeCost(org, key);
  if (org.balance < cost) return { ok: false, reason: 'Insufficient funds.' };
  ledger(world, org, 'facility', -cost, undefined, `${FACILITY_LABEL[key]} upgraded to level ${org.facilities[key] + 1}`);
  org.facilities[key]++;
  return { ok: true };
}

export interface MonthlyBreakdown {
  revenue: Record<string, number>;
  expenses: Record<string, number>;
}

/** Projected monthly cash flow for an organisation (also used by the UI). */
export function monthlyBreakdown(world: World, org: Organisation): MonthlyBreakdown {
  const econ = regionEconomy(org.region);
  const revenue: Record<string, number> = { sponsorship: 0, media: 0, merchandise: 0, league: 0 };
  const expenses: Record<string, number> = { salaries: 0, staff: 0, facilities: facilityUpkeep(org), operations: 0 };
  for (const sp of org.sponsors) if (sp.untilSeason >= world.season) revenue.sponsorship += sp.perMonth;
  revenue.merchandise = Math.round(org.fans * 0.012 * econ);
  for (const tid of org.teams) {
    const team = world.teams[tid];
    if (!team || !team.active) continue;
    const t = getTitle(team.titleId);
    const v = divisionValue(world, tid);
    revenue.media += Math.round((v * 0.75 * (0.5 + org.rep / 100)) / 12);
    const league = team.leagueId ? world.leagues[team.leagueId] : null;
    if (league?.franchised && league.tier === 1) revenue.league += Math.round((v * 0.25) / 12);
    for (const pid of team.roster) {
      const c = world.players[pid]?.contract;
      if (!c) continue;
      const share = c.loan && c.teamId === team.id ? 1 - c.loan.wageSplit : 1;
      expenses.salaries += (c.salary * share) / 12;
    }
    for (const sid of team.staff) expenses.staff += (world.staff[sid]?.contract?.salary ?? 0) / 12;
    expenses.operations += Math.round((v * 0.06) / 12 + 1500 * (0.4 + regionEconomy(team.region)) * (team.tier === 'main' ? 1 : 0.5));
    void t;
  }
  for (const s of Object.values(world.staff)) if (s.orgId === org.id && !s.teamId && s.contract && !s.retired) expenses.staff += s.contract.salary / 12;
  for (const k of Object.keys(expenses)) expenses[k] = Math.round(expenses[k]);
  return { revenue, expenses };
}

export function processMonthlyFinances(world: World): void {
  const mk = monthKey(world.day);
  if (world.processed.lastMonth === mk) return;
  world.processed.lastMonth = mk;
  for (const org of Object.values(world.orgs)) {
    if (!org.active) continue;
    const { revenue, expenses } = monthlyBreakdown(world, org);
    for (const [k, v] of Object.entries(revenue)) if (v) ledger(world, org, k, v);
    for (const [k, v] of Object.entries(expenses)) if (v) ledger(world, org, k, -v);
    org.monthly.push({ month: mk, revenue, expenses, balance: org.balance });
    if (org.monthly.length > 36) org.monthly.shift();
    handleDistress(world, org);
  }
}

function handleDistress(world: World, org: Organisation): void {
  if (org.balance >= 0) {
    org.debtMonths = 0;
    return;
  }
  org.debtMonths++;
  const rng = new RNG(`${world.meta.seed}|distress|${org.id}|${world.day}`);
  if (org.user && org.debtMonths === 1) {
    addInbox(world, { from: 'Board', subject: 'Finances in the red', body: `${org.name} has a negative balance (${Math.round(org.balance).toLocaleString()}). Reduce wages, sell players or find sponsorship before the board intervenes.`, kind: 'finance' });
  }
  if (org.debtMonths >= 3 && rng.chance(org.owner.wealth / 140)) {
    const injection = Math.round(Math.abs(org.balance) * rng.float(1.1, 1.8) + 50000 * regionEconomy(org.region));
    ledger(world, org, 'owner', injection, undefined, 'Owner capital injection');
    org.owner.wealth = clamp(org.owner.wealth - 8, 1, 100);
    addNews(world, { kind: 'finance', title: `${org.name} owners inject fresh capital`, body: `The ownership group covered a deficit to keep the organisation running.`, refs: [{ type: 'org', id: org.id }], importance: 2 });
    org.debtMonths = 0;
    return;
  }
  if (org.debtMonths >= 7 && !org.user) {
    // Acquisition by a new investor (keeps the history, renames sometimes).
    const investor = makeCompanyName(rng) + ' Capital';
    org.owner = { name: investor, type: 'investor', wealth: rng.int(40, 90), patience: rng.int(30, 80) };
    ledger(world, org, 'acquisition', Math.round(Math.abs(org.balance) + 200000 * regionEconomy(org.region)), undefined, `Acquired by ${investor}`);
    org.events.push({ season: world.season, day: world.day, type: 'acquisition', text: `${org.name} acquired by ${investor} after financial distress.` });
    addNews(world, { kind: 'org', title: `${org.name} acquired by ${investor}`, body: `Months of losses ended with a takeover. The organisation's history and trophies remain intact.`, refs: [{ type: 'org', id: org.id }], importance: 3 });
    org.debtMonths = 0;
  }
}

/** Replace expired sponsorships (season rollover). */
export function renewSponsors(world: World, org: Organisation, rng: RNG): void {
  org.sponsors = org.sponsors.filter((s) => s.untilSeason >= world.season);
  const target = 1 + Math.floor(org.rep / 30);
  while (org.sponsors.length < target) {
    const per = sponsorDealValue(world, org, target, rng);
    org.sponsors.push({ id: nextId(world, 'sp'), name: makeCompanyName(rng), perMonth: per, untilSeason: world.season + rng.int(0, 2) });
    if (org.user) addInbox(world, { from: 'Commercial', subject: 'New sponsorship agreement', body: `A new partner will pay $${per.toLocaleString()} per month.`, kind: 'finance' });
  }
}

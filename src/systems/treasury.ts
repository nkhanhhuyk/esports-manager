/**
 * Organisation treasury (master prompt §6/§14; source pp. 20-21).
 *
 * Keeps four things apart: cash (the group bank balance), department budgets (spending
 * authority — not cash), contractual commitments (salaries owed until contracts end) and the cash
 * forecast. Rules (proposals, editable):
 *   fundable wage authority = 12-month projected revenue + 50% of positive cash
 *                             − 12 months of fixed costs (staff, facilities, operations)
 *   Σ department wage budgets may not exceed it; raising a budget draws on the unallocated
 *   reserve; lowering it cannot go below the department's contracted payroll; moving budget
 *   between departments nets to zero. Every change is logged. Budgets authorise signings; money
 *   only moves when salaries, fees and bonuses are actually paid.
 */
import { seasonEndDay } from '../competition/season';
import { dayFromYMD, ymd } from '../core/date';
import type { Organisation, Team, World } from '../model/types';
import { staffPayroll, teamPayroll } from './contracts';
import { facilityUpkeep, monthlyBreakdown } from './finance';
import { addInbox } from './news';

export const RESERVE_SHARE = 0.5;

export interface Department {
  team: Team;
  payroll: number;
  budget: number;
  headroom: number;
  /** Salaries owed to players until their contracts end. */
  commitment: number;
  staff: number;
}

export interface TreasurySummary {
  cash: number;
  revenue12: number;
  fixed12: number;
  fundable: number;
  allocated: number;
  unallocated: number;
  commitments: number;
  departments: Department[];
}

/** The season a day belongs to (seasons end on 22 November). */
export function seasonOfDay(day: number): number {
  const { y } = ymd(day);
  return day > seasonEndDay(y) ? y + 1 : y;
}

/** Months of salary still owed on a contract ending at the end of `endSeason` (from `day`). */
function monthsLeft(day: number, endSeason: number): number {
  const end = seasonEndDay(endSeason);
  return Math.max(0, (end - day) / 30.44);
}

export function departments(world: World, org: Organisation): Department[] {
  return org.teams
    .map((id) => world.teams[id])
    .filter((t): t is Team => !!t?.active && !t.national)
    .map((team) => {
      const payroll = teamPayroll(world, team);
      let commitment = 0;
      for (const pid of team.roster) {
        const c = world.players[pid]?.contract;
        if (!c) continue;
        const share = c.loan && c.teamId === team.id ? 1 - c.loan.wageSplit : 1;
        commitment += ((c.salary * share) / 12) * monthsLeft(world.day, c.endSeason);
      }
      return { team, payroll, budget: team.salaryBudget, headroom: team.salaryBudget - payroll, commitment: Math.round(commitment), staff: staffPayroll(world, team) };
    });
}

export function treasurySummary(world: World, org: Organisation): TreasurySummary {
  const mb = monthlyBreakdown(world, org);
  const f = cashForecast(world, org, 12);
  const revenue12 = f.reduce((a, m) => a + m.revenue, 0);
  const fixed12 = (mb.expenses.staff + mb.expenses.facilities + mb.expenses.operations) * 12;
  const fundable = Math.round(revenue12 + Math.max(0, org.balance) * RESERVE_SHARE - fixed12);
  const deps = departments(world, org);
  const allocated = deps.reduce((a, d) => a + d.budget, 0);
  return { cash: org.balance, revenue12: Math.round(revenue12), fixed12: Math.round(fixed12), fundable, allocated, unallocated: fundable - allocated, commitments: deps.reduce((a, d) => a + d.commitment, 0), departments: deps };
}

export interface ForecastMonth {
  day: number;
  label: string;
  revenue: number;
  expenses: number;
  net: number;
  cash: number;
}

/**
 * Month-by-month cash forecast from what is already agreed: sponsor deals until they end, media/
 * merchandise/league income at the current rate, player and staff salaries until their contracts
 * end, facilities and operations. Excludes prize money, transfers, bonuses and future signings.
 */
export function cashForecast(world: World, org: Organisation, months = 12): ForecastMonth[] {
  const mb = monthlyBreakdown(world, org);
  const out: ForecastMonth[] = [];
  let cash = org.balance;
  const start = ymd(world.day);
  for (let i = 1; i <= months; i++) {
    const m0 = start.m - 1 + i;
    const day = dayFromYMD(start.y + Math.floor(m0 / 12), (m0 % 12) + 1, 1);
    const season = seasonOfDay(day);
    let revenue = mb.revenue.media + mb.revenue.merchandise + mb.revenue.league;
    for (const sp of org.sponsors) if (sp.untilSeason >= season) revenue += sp.perMonth;
    let expenses = facilityUpkeep(org) + mb.expenses.operations;
    for (const tid of org.teams) {
      const team = world.teams[tid];
      if (!team?.active) continue;
      for (const pid of team.roster) {
        const c = world.players[pid]?.contract;
        if (!c || c.endSeason < season) continue;
        const share = c.loan && c.teamId === team.id ? 1 - c.loan.wageSplit : 1;
        expenses += (c.salary * share) / 12;
      }
    }
    for (const s of Object.values(world.staff)) if (s.orgId === org.id && s.contract && !s.retired && s.contract.endSeason >= season) expenses += s.contract.salary / 12;
    revenue = Math.round(revenue);
    expenses = Math.round(expenses);
    cash += revenue - expenses;
    const d = ymd(day);
    out.push({ day, label: `${d.y}-${String(d.m).padStart(2, '0')}`, revenue, expenses, net: revenue - expenses, cash: Math.round(cash) });
  }
  return out;
}

export interface BudgetResult {
  ok: boolean;
  message: string;
}

function log(world: World, org: Organisation, teamId: string | null, toTeamId: string | null, amount: number, note: string) {
  (org.budgetLog ??= []).push({ day: world.day, teamId, toTeamId, amount: Math.round(amount), note });
  if (org.budgetLog.length > 120) org.budgetLog.splice(0, org.budgetLog.length - 120);
}

/** Set a department's wage budget, drawing on (or returning to) the unallocated reserve. */
export function setDepartmentBudget(world: World, teamId: string, budget: number): BudgetResult {
  const team = world.teams[teamId];
  const org = team ? world.orgs[team.orgId] : null;
  if (!team || !org) return { ok: false, message: 'Unknown department.' };
  const target = Math.round(budget / 1000) * 1000;
  const payroll = teamPayroll(world, team);
  if (target < payroll) return { ok: false, message: `The budget cannot go below the contracted payroll (${payroll.toLocaleString()}).` };
  const delta = target - team.salaryBudget;
  if (delta > 0) {
    const s = treasurySummary(world, org);
    if (delta > s.unallocated) return { ok: false, message: `Not enough unallocated authority: the group can back ${Math.max(0, s.unallocated).toLocaleString()} more (fundable ${s.fundable.toLocaleString()}, allocated ${s.allocated.toLocaleString()}).` };
  }
  team.salaryBudget = target;
  log(world, org, delta > 0 ? null : team.id, delta > 0 ? team.id : null, Math.abs(delta), delta > 0 ? 'Allocated from the group reserve' : 'Returned to the group reserve');
  return { ok: true, message: `${team.name} wage budget set to ${target.toLocaleString()}.` };
}

/** Move wage budget between two departments of the same organisation (nets to zero). */
export function moveBudget(world: World, fromId: string, toId: string, amount: number): BudgetResult {
  const from = world.teams[fromId];
  const to = world.teams[toId];
  if (!from || !to || from.orgId !== to.orgId || from.id === to.id) return { ok: false, message: 'Choose two different departments of your organisation.' };
  const amt = Math.round(amount / 1000) * 1000;
  if (!(amt > 0)) return { ok: false, message: 'Enter a positive amount.' };
  const free = from.salaryBudget - teamPayroll(world, from);
  if (amt > free) return { ok: false, message: `${from.name} only has ${Math.max(0, free).toLocaleString()} of unused budget (its payroll is committed).` };
  from.salaryBudget -= amt;
  to.salaryBudget += amt;
  log(world, world.orgs[from.orgId], from.id, to.id, amt, 'Internal budget transfer');
  return { ok: true, message: `Moved ${amt.toLocaleString()} of wage budget from ${from.name} to ${to.name}.` };
}

/** A user signing must fit the department's wage budget (renewals count only the increase). */
export function budgetCheck(world: World, team: Team, playerId: string, salary: number): string | null {
  const org = world.orgs[team.orgId];
  if (!org?.user) return null;
  const current = world.players[playerId]?.contract;
  const existing = current && current.teamId === team.id ? current.salary : 0;
  const after = teamPayroll(world, team) - existing + salary;
  if (after <= team.salaryBudget) return null;
  return `Exceeds ${team.name}'s wage budget by ${Math.round(after - team.salaryBudget).toLocaleString()} (budget ${team.salaryBudget.toLocaleString()}). Raise or move budget in Finances → Treasury.`;
}

/** Monthly: warn the user when the committed-cash forecast turns negative within 3 months. */
export function treasuryMonthly(world: World): void {
  const uid = world.meta.userOrgId;
  const org = uid ? world.orgs[uid] : null;
  if (!org || world.meta.unemployed) return;
  const f = cashForecast(world, org, 3);
  const bad = f.find((m) => m.cash < 0);
  if (bad && org.balance >= 0) addInbox(world, { from: 'Finance', subject: `Cash forecast: shortfall by ${bad.label}`, body: `On current contracts and income the balance is projected to reach $${bad.cash.toLocaleString()} in ${bad.label} (excluding prize money and transfers). Consider selling players, cutting costs or waiting for sponsor income.`, kind: 'finance' });
}

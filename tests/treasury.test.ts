import { describe, expect, it } from 'vitest';
import { makeWorld } from './helpers';
import { setUserOrg } from '../src/gen/world';
import { setBoardObjectives } from '../src/systems/career';
import { budgetCheck, cashForecast, moveBudget, RESERVE_SHARE, seasonOfDay, setDepartmentBudget, treasurySummary } from '../src/systems/treasury';
import { offerFreeAgent } from '../src/systems/transfers';
import { teamPayroll } from '../src/systems/contracts';
import { seasonEndDay } from '../src/competition/season';
import { advance } from '../src/game/advance';

function setup(seed: string) {
  const w = makeWorld(['lol', 'valorant', 'cs2'], seed, 'small');
  // A multi-department organisation that includes a League of Legends team.
  const pick = Object.values(w.orgs)
    .filter((o) => o.teams.some((t) => w.teams[t]?.titleId === 'lol' && w.teams[t].active) && o.teams.filter((t) => w.teams[t]?.active).length >= 2)
    .sort((a, b) => b.teams.length - a.teams.length)[0];
  setUserOrg(w, pick.id);
  setBoardObjectives(w);
  const org = w.orgs[w.meta.userOrgId!];
  const teams = org.teams.map((id) => w.teams[id]).filter((t) => t.active && !t.national);
  return { w, org, teams };
}

describe('organisation treasury', () => {
  it('separates cash, spending authority and commitments', () => {
    const { w, org } = setup('tr-sum');
    const s = treasurySummary(w, org);
    expect(s.fundable).toBe(Math.round(s.revenue12 + Math.max(0, org.balance) * RESERVE_SHARE - s.fixed12));
    expect(s.allocated).toBe(s.departments.reduce((a, d) => a + d.budget, 0));
    expect(s.unallocated).toBe(s.fundable - s.allocated);
    for (const d of s.departments) {
      expect(d.payroll).toBe(teamPayroll(w, d.team));
      expect(d.commitment).toBeGreaterThan(0);
    }
  });

  it('allocations cannot exceed the fundable authority or drop below payroll; changes move no cash', () => {
    const { w, org, teams } = setup('tr-alloc');
    const t = teams[0];
    const cash = org.balance;
    const s0 = treasurySummary(w, org);
    expect(setDepartmentBudget(w, t.id, t.salaryBudget + Math.max(0, s0.unallocated) + 1_000_000).ok).toBe(false);
    expect(setDepartmentBudget(w, t.id, teamPayroll(w, t) - 1000).ok).toBe(false);
    const lower = Math.ceil(teamPayroll(w, t) / 1000) * 1000;
    const freed = t.salaryBudget - lower;
    expect(setDepartmentBudget(w, t.id, lower).ok).toBe(true);
    const s1 = treasurySummary(w, org);
    expect(s1.unallocated - s0.unallocated).toBeCloseTo(freed, -3);
    expect(setDepartmentBudget(w, t.id, lower + Math.floor(Math.max(0, s1.unallocated) / 2000) * 1000).ok).toBe(true);
    expect(org.balance).toBe(cash);
    expect(org.budgetLog!.length).toBe(2);
  });

  it('moving budget between departments nets to zero and keeps cash unchanged', () => {
    const { w, org, teams } = setup('tr-move');
    const [a, b] = teams;
    a.salaryBudget = teamPayroll(w, a) + 50000;
    const total = teams.reduce((x, t) => x + t.salaryBudget, 0);
    const cash = org.balance;
    expect(moveBudget(w, a.id, b.id, 60000).ok).toBe(false); // more than a's unused budget
    expect(moveBudget(w, a.id, b.id, 30000).ok).toBe(true);
    expect(teams.reduce((x, t) => x + t.salaryBudget, 0)).toBe(total);
    expect(org.balance).toBe(cash);
    expect(org.budgetLog!.at(-1)).toMatchObject({ teamId: a.id, toTeamId: b.id, amount: 30000 });
  });

  it("the user's signings must fit the department's wage budget", () => {
    const { w, teams } = setup('tr-sign');
    const team = teams.find((t) => t.titleId === 'lol')!;
    w.orgs[team.orgId].balance += 1e7;
    while (team.roster.length > 6) team.roster.pop();
    const fa = Object.values(w.players).filter((p) => !p.teamId && !p.retired && p.titleId === 'lol' && w.day - p.birth > 18 * 365.25).sort((x, y) => y.ca - x.ca)[0];
    team.salaryBudget = teamPayroll(w, team) + 10000;
    expect(budgetCheck(w, team, fa.id, 50000)).toMatch(/wage budget/);
    const r = offerFreeAgent(w, team.id, fa.id, 50000, 2);
    expect(r.ok).toBe(false);
    expect(r.message).toMatch(/wage budget/);
    team.salaryBudget += 100000;
    expect(budgetCheck(w, team, fa.id, 50000)).toBeNull();
  });

  it('forecasts salaries and sponsor income only while contracts and deals run', () => {
    const { w, org, teams } = setup('tr-fc');
    const team = teams[0];
    const p = w.players[team.roster[0]];
    p.contract!.endSeason = w.season;
    p.contract!.salary = 1_200_000;
    org.sponsors = [{ id: 'sp', name: 'Test', perMonth: 50000, untilSeason: w.season }];
    const f = cashForecast(w, org, 15);
    const end = seasonEndDay(w.season);
    const before = f.filter((m) => m.day <= end);
    const after = f.filter((m) => m.day > end);
    expect(before.length && after.length).toBeTruthy();
    expect(seasonOfDay(after[0].day)).toBe(w.season + 1);
    // The 100k/month salary and the 50k/month sponsorship both stop after the season ends.
    expect(before.at(-1)!.expenses - after[0].expenses).toBeGreaterThanOrEqual(100000 - 1);
    expect(before.at(-1)!.revenue - after[0].revenue).toBe(50000);
    for (let i = 1; i < f.length; i++) expect(f[i].cash).toBe(f[i - 1].cash + f[i].net);
  });

  it('the board sets budgets when the manager has no finance authority', () => {
    const w = makeWorld(['lol'], 'tr-board');
    w.meta.careerRole = 'head-coach';
    const org = Object.values(w.orgs).filter((o) => o.teams.length).sort((a, b) => b.rep - a.rep)[0];
    org.user = true;
    w.meta.userOrgId = org.id;
    const team = w.teams[org.teams[0]];
    team.salaryBudget = 1;
    advance(w, { kind: 'end-season' });
    expect(team.salaryBudget).toBeGreaterThan(1000);
  });
});

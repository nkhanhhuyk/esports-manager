import { describe, expect, it } from 'vitest';
import { makeWorld, withUser } from './helpers';
import { aiMarketDay, offerFreeAgent, releasePlayer, rosterMax, bidForPlayer } from '../src/systems/transfers';
import { makeContract, validateContract, expectedSalary } from '../src/systems/contracts';
import { ledger, monthlyBreakdown, upgradeFacility, facilityUpgradeCost, processMonthlyFinances } from '../src/systems/finance';
import { getTitle } from '../src/data/titles/catalogue';
import { weeklyTraining } from '../src/systems/training';
import { releasePatch } from '../src/systems/patches';
import { dayFromYMD } from '../src/core/date';

describe('contracts', () => {
  it('validates salary, length and league policy', () => {
    const w = makeWorld(['lol'], 'contracts');
    const team = Object.values(w.teams).find((t) => t.titleId === 'lol' && t.leagueId)!;
    const p = w.players[team.roster[0]];
    expect(validateContract(w, team, p, { salary: 50000, endSeason: w.season + 1 }).ok).toBe(true);
    expect(validateContract(w, team, p, { salary: -1, endSeason: w.season }).ok).toBe(false);
    expect(validateContract(w, team, p, { salary: 50000, endSeason: w.season + 9 }).ok).toBe(false);
    const league = w.leagues[team.leagueId!];
    league.policy.minAge = 99;
    expect(validateContract(w, team, p, { salary: 50000, endSeason: w.season }).errors.join()).toMatch(/minimum age/);
    league.policy.minAge = 16;
    league.policy.cap = { type: 'hard', amount: 1000, taxRate: 0 };
    expect(validateContract(w, team, p, { salary: 50000, endSeason: w.season }).errors.join()).toMatch(/hard salary cap/);
    const c = makeContract(w, team, 100000, 3);
    expect(c.endSeason).toBe(w.season + 2);
    expect(c.buyout).toBeGreaterThan(c.salary);
  });

  it('prices players by ability relative to their scene', () => {
    const w = makeWorld(['valorant'], 'salary');
    const ps = Object.values(w.players).filter((p) => p.titleId === 'valorant').sort((a, b) => a.ca - b.ca);
    expect(expectedSalary(w, ps[ps.length - 1], 'valorant', 'NA')).toBeGreaterThan(expectedSalary(w, ps[0], 'valorant', 'NA'));
  });
});

describe('finance', () => {
  it('records ledger entries and processes each month exactly once', () => {
    const w = makeWorld(['mlbb'], 'finance');
    const org = Object.values(w.orgs)[0];
    const b0 = org.balance;
    ledger(w, org, 'test', 1000);
    expect(org.balance).toBe(b0 + 1000);
    w.day = dayFromYMD(2027, 1, 1);
    processMonthlyFinances(w);
    const b1 = org.balance;
    const mb = monthlyBreakdown(w, org);
    const net = Object.values(mb.revenue).reduce((a, x) => a + x, 0) - Object.values(mb.expenses).reduce((a, x) => a + x, 0);
    expect(Math.abs(b1 - (b0 + 1000 + net))).toBeLessThan(10);
    processMonthlyFinances(w);
    expect(org.balance).toBe(b1);
  });

  it('upgrades facilities only when affordable', () => {
    const w = makeWorld(['lol'], 'fac');
    const org = Object.values(w.orgs)[0];
    org.facilities.training = 3;
    org.balance = facilityUpgradeCost(org, 'training') - 1;
    expect(upgradeFacility(w, org, 'training').ok).toBe(false);
    org.balance = facilityUpgradeCost(org, 'training') + 10;
    expect(upgradeFacility(w, org, 'training').ok).toBe(true);
    expect(org.facilities.training).toBe(4);
  });
});

describe('transfers & AI', () => {
  it('signs free agents within roster limits and releases cleanly', () => {
    const w = withUser(makeWorld(['cs2'], 'fa'));
    const team = Object.values(w.teams).find((t) => t.orgId === w.meta.userOrgId)!;
    const fa = Object.values(w.players).filter((p) => !p.teamId && p.titleId === 'cs2' && !p.retired).sort((a, b) => b.ca - a.ca)[0];
    const salary = expectedSalary(w, fa, 'cs2', team.region) * 1.5;
    while (team.roster.length >= rosterMax(w, team)) releasePlayer(w, team.roster[team.roster.length - 1]);
    const r = offerFreeAgent(w, team.id, fa.id, salary, 2);
    expect(r.ok).toBe(true);
    expect(fa.teamId).toBe(team.id);
    expect(team.roster).toContain(fa.id);
    expect(releasePlayer(w, fa.id)).toBe(true);
    expect(fa.teamId).toBeNull();
    expect(team.roster).not.toContain(fa.id);
  });

  it('AI clubs fill missing roles from the market and respect roster limits', () => {
    const w = withUser(makeWorld(['lol'], 'ai'));
    const ai = Object.values(w.teams).find((t) => t.orgId !== w.meta.userOrgId && t.active)!;
    const t = getTitle(ai.titleId);
    // Remove two starters.
    for (const pid of ai.lineup.slice(0, 2).map((s) => s.playerId)) releasePlayer(w, pid);
    expect(ai.roster.length).toBeLessThan(t.teamSize + t.subs);
    for (let i = 0; i < 12 && ai.roster.length < t.teamSize; i++) {
      w.day++;
      w.processed.lastDay = w.day;
      aiMarketDay(w);
    }
    expect(ai.roster.length).toBeGreaterThanOrEqual(t.teamSize);
    for (const team of Object.values(w.teams)) expect(team.roster.length).toBeLessThanOrEqual(rosterMax(w, team));
  });

  it('rejects bids outside the transfer window unless listed', () => {
    const w = withUser(makeWorld(['valorant'], 'window'));
    w.day = dayFromYMD(2027, 3, 1);
    const mine = Object.values(w.teams).find((t) => t.orgId === w.meta.userOrgId)!;
    const target = Object.values(w.players).find((p) => p.teamId && p.teamId !== mine.id && w.teams[p.teamId].titleId === 'valorant')!;
    target.listed = false;
    expect(bidForPlayer(w, mine.id, target.id, 1e7, 100000, 2).ok).toBe(false);
  });
});

describe('training, progression and patches', () => {
  it('young high-potential players develop with training; proficiencies move faster than attributes', () => {
    const w = makeWorld(['valmobile'], 'train');
    const p = Object.values(w.players).filter((x) => x.teamId && x.pa - x.ca > 8).sort((a, b) => b.pa - b.ca - (a.pa - a.ca))[0];
    const team = w.teams[p.teamId!];
    team.training.focus = 'input';
    const ca0 = p.ca;
    const input0 = p.input[0];
    for (let i = 0; i < 26; i++) {
      w.day += 7;
      weeklyTraining(w);
    }
    expect(p.ca).toBeGreaterThan(ca0);
    expect(p.input[0]).toBeGreaterThan(input0);
  });

  it('patches shift the meta and reduce team meta adaptation', () => {
    const w = makeWorld(['overwatch'], 'patch');
    const team = Object.values(w.teams)[0];
    team.metaAdapt = 90;
    const n = w.titles.overwatch.patches.length;
    const p = releasePatch(w, 'overwatch', true);
    expect(w.titles.overwatch.patches.length).toBe(n + 1);
    expect(team.metaAdapt).toBeLessThan(90);
    expect(Object.keys(p.charMeta).length).toBe(getTitle('overwatch').characters.length);
  });
});

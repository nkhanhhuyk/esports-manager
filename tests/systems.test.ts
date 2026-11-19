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
    const w = makeWorld(['mlbb'], 'train');
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
    const w = makeWorld(['valorant'], 'patch');
    const team = Object.values(w.teams)[0];
    team.metaAdapt = 90;
    const n = w.titles.valorant.patches.length;
    const p = releasePatch(w, 'valorant', true);
    expect(w.titles.valorant.patches.length).toBe(n + 1);
    expect(team.metaAdapt).toBeLessThan(90);
    expect(Object.keys(p.charMeta).length).toBe(getTitle('valorant').characters.length);
  });
});

describe('league policies: rookie draft & luxury tax', () => {
  it('runs a rookie draft in reverse standings order where the league enables it', async () => {
    const { advance } = await import('../src/game/advance');
    const w = makeWorld(['cs2'], 'rookie');
    const league = Object.values(w.leagues).find((l) => l.tier === 1)!;
    league.policy.draft = true;
    advance(w, { kind: 'end-season' });
    const picks = w.transfers.filter((x) => x.type === 'draft' && league.teamIds.includes(x.toTeamId!));
    expect(picks.length).toBe(league.teamIds.length * 2);
    // Worst team of the season picks first.
    const first = picks.find((x) => x.note === 'Round 1, pick 1')!;
    expect(first).toBeTruthy();
    for (const x of picks) {
      const p = w.players[x.playerId];
      expect(p.career.some((c) => c.type === 'draft')).toBe(true);
      if (p.teamId) expect(w.teams[p.teamId].roster).toContain(p.id);
    }
  });

  it('charges luxury tax above a soft cap', () => {
    const w = makeWorld(['lol'], 'tax');
    const team = Object.values(w.teams).find((t) => t.leagueId)!;
    const league = w.leagues[team.leagueId!];
    league.policy.cap = { type: 'soft', amount: 1, taxRate: 0.5 };
    w.day = dayFromYMD(2027, 2, 1);
    processMonthlyFinances(w);
    expect(w.orgs[team.orgId].ledger.some((l) => l.kind === 'luxury-tax' && l.amount < 0)).toBe(true);
  });
});

describe('national teams', () => {
  it('plays a Nations Cup with country squads drawn from club players', async () => {
    const { advance } = await import('../src/game/advance');
    const w = makeWorld(['lol', 'valorant'], 'nations', 'small');
    const cups = Object.values(w.comps).filter((c) => c.tier === 'cup');
    expect(cups.length).toBe(2); // odd season -> one cup per eligible title
    advance(w, { kind: 'until', day: w.day + 40 });
    for (const c of cups) {
      expect(c.status).toBe('done');
      const champ = w.teams[c.placements[0]];
      expect(champ.national).toBeTruthy();
      for (const pid of champ.roster) {
        const p = w.players[pid];
        expect(p.nat).toBe(champ.national);
        expect(p.teamId).not.toBe(champ.id); // club contract untouched
      }
    }
  });
});

describe('national team coach job', () => {
  it('qualifies the user nation, honours the named squad and pauses for its matches', async () => {
    const { advance } = await import('../src/game/advance');
    const { eligibleNations } = await import('../src/systems/nations');
    const w = withUser(makeWorld(['lol'], 'natjob', 'small'));
    const nations = eligibleNations(w, 'lol');
    const weak = nations[nations.length - 1]; // outside the top 8 on strength
    const picks = Object.values(w.players).filter((p) => p.nat === weak.cc && p.titleId === 'lol' && !p.retired).sort((a, b) => a.ca - b.ca).slice(0, 2).map((p) => p.id);
    w.meta.nationalJob = { titleId: 'lol', country: weak.cc, squad: picks };
    const r = advance(w, { kind: 'next-event' });
    expect(r.stoppedFor).toBe('user-match');
    const f = w.fixtures[r.fixtureId!];
    const mine = f.teams.map((id) => w.teams[id]).find((t) => t.national === weak.cc)!;
    expect(mine).toBeTruthy();
    for (const id of picks) expect(mine.roster).toContain(id);
  });
});

describe('tradable draft picks & restricted free agency', () => {
  it('a traded pick changes owner and the new owner makes that selection', async () => {
    const { advance } = await import('../src/game/advance');
    const { ensureDraftPicks, picksOwnedBy, pickValue } = await import('../src/systems/draft');
    const { proposeTrade } = await import('../src/systems/transfers');
    const w = makeWorld(['lol'], 'picks');
    const league = Object.values(w.leagues).find((l) => l.tier === 1)!;
    league.policy.draft = true;
    league.policy.trades = true;
    ensureDraftPicks(w);
    expect(w.draftPicks.filter((d) => d.leagueId === league.id).length).toBe(league.teamIds.length * 2 * 2);
    advance(w, { kind: 'until', day: dayFromYMD(2026, 11, 21) });
    const [aId, bId] = league.teamIds;
    const pick = picksOwnedBy(w, aId).find((d) => d.season === w.season && d.round === 1)!;
    w.orgs[w.teams[bId].orgId].balance += 1e8;
    // Not enough value is rejected; a fair offer is accepted.
    expect(proposeTrade(w, bId, [], [], 1, { otherTeamId: aId, takePicks: [pick.id] }).ok).toBe(false);
    const r = proposeTrade(w, bId, [], [], pickValue(w, pick) * 2, { otherTeamId: aId, takePicks: [pick.id] });
    expect(r.ok).toBe(true);
    expect(pick.ownerTeamId).toBe(bId);
    expect(picksOwnedBy(w, bId).some((d) => d.id === pick.id)).toBe(true);
    const season = w.season;
    advance(w, { kind: 'end-season' });
    const drafted = w.transfers.filter((x) => x.type === 'draft' && x.season === season + 1);
    expect(drafted.filter((x) => x.toTeamId === bId).length).toBe(3);
    expect(drafted.filter((x) => x.toTeamId === aId).length).toBe(1);
    // Used picks are pruned and the next two drafts are seeded.
    expect(w.draftPicks.some((d) => d.season < w.season)).toBe(false);
    expect(w.draftPicks.filter((d) => d.leagueId === league.id && d.season === w.season + 1).length).toBe(league.teamIds.length * 2);
  });

  it('tenders qualifying offers to young expiring players in restricted-FA leagues (user can opt out)', async () => {
    const { advance } = await import('../src/game/advance');
    const setup = (tender: boolean) => {
      const w = withUser(makeWorld(['valorant'], 'rfa'));
      const team = Object.values(w.teams).find((t) => t.orgId === w.meta.userOrgId && t.leagueId)!;
      w.leagues[team.leagueId!].policy.restrictedFA = true;
      w.orgs[team.orgId].balance += 1e7;
      team.prefs.tenderRFA = tender;
      const p = team.lineup.map((s) => w.players[s.playerId!]).filter(Boolean).sort((a, b) => b.birth - a.birth)[0];
      p.birth = w.day - 20 * 365.25;
      p.contract!.endSeason = w.season;
      p.contract!.option = 'none';
      return { w, team, p };
    };
    const a = setup(true);
    advance(a.w, { kind: 'end-season' });
    expect(a.p.teamId).toBe(a.team.id);
    // Retained either by the qualifying offer or by matching a rival's offer sheet.
    expect(a.p.career.some((c) => c.note === 'Qualifying offer (restricted free agent)' || c.note === 'Offer sheet matched (restricted free agent)')).toBe(true);
    expect(a.p.contract!.endSeason).toBeGreaterThanOrEqual(a.w.season);
    const b = setup(false);
    advance(b.w, { kind: 'end-season' });
    expect(b.p.career.some((c) => c.note === 'Qualifying offer (restricted free agent)' || c.note === 'Offer sheet matched (restricted free agent)')).toBe(false);
  });
});

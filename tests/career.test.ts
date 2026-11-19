import { describe, expect, it } from 'vitest';
import { makeWorld } from './helpers';
import { setUserOrg } from '../src/gen/world';
import { applyRoleDelegation, boardReview, can, jobOffers, setBoardObjectives, takeJob } from '../src/systems/career';
import { aiMarketDay, releasePlayer } from '../src/systems/transfers';
import { getTitle } from '../src/data/titles/catalogue';
import { advance } from '../src/game/advance';

function career(role: 'coach-gm' | 'head-coach' | 'gm' | 'owner' | 'commissioner', seed: string) {
  const w = makeWorld(['lol'], seed);
  w.meta.careerRole = role;
  const org = Object.values(w.orgs).filter((o) => o.teams.length).sort((a, b) => b.rep - a.rep)[0];
  setUserOrg(w, org.id);
  applyRoleDelegation(w);
  setBoardObjectives(w);
  return w;
}

describe('career roles', () => {
  it('grants role-specific permissions', () => {
    expect(can(career('coach-gm', 'r1'), 'transfers')).toBe(true);
    const hc = career('head-coach', 'r2');
    expect(can(hc, 'tactics')).toBe(true);
    expect(can(hc, 'transfers')).toBe(false);
    const gm = career('gm', 'r3');
    expect(can(gm, 'tactics')).toBe(false);
    expect(Object.values(gm.teams).filter((t) => t.orgId === gm.meta.userOrgId).every((t) => t.prefs.delegateLineup && t.prefs.delegateDraft)).toBe(true);
    expect(can(career('owner', 'r4'), 'facilities')).toBe(true);
  });

  it('lets the AI general manager run recruitment when the user is only head coach', () => {
    const w = career('head-coach', 'r5');
    const team = Object.values(w.teams).find((t) => t.orgId === w.meta.userOrgId)!;
    const t = getTitle(team.titleId);
    for (const s of team.lineup.slice(0, 2)) releasePlayer(w, s.playerId);
    for (let i = 0; i < 12 && team.roster.length < t.teamSize; i++) {
      w.day++;
      aiMarketDay(w);
    }
    expect(team.roster.length).toBeGreaterThanOrEqual(t.teamSize);
  });
});

describe('board & job security', () => {
  it('sets objectives and dismisses a failing manager, who can then take a new job', () => {
    const w = career('coach-gm', 'board');
    const objs = w.meta.board!.objectives.filter((o) => o.season === w.season);
    expect(objs.length).toBeGreaterThanOrEqual(2);
    // Force an impossible season: tank confidence and fail every objective.
    w.meta.board!.confidence = 10;
    for (const o of objs) if (o.kind === 'finance') o.target = Number.MAX_SAFE_INTEGER;
    const sacked = boardReview(w, w.season);
    expect(sacked).toBe(true);
    expect(w.meta.unemployed).toBe(true);
    const offers = jobOffers(w);
    expect(offers.length).toBeGreaterThan(0);
    const old = w.meta.userOrgId;
    takeJob(w, offers[0]);
    expect(w.meta.userOrgId).toBe(offers[0]);
    expect(w.meta.userOrgId).not.toBe(old);
    expect(w.meta.unemployed).toBe(false);
    expect(w.orgs[offers[0]].user).toBe(true);
  });

  it('reviews the season at rollover and sets new objectives', () => {
    const w = career('coach-gm', 'rollover-board');
    const season = w.season;
    advance(w, { kind: 'end-season' });
    const h = w.meta.board!.history.find((x) => x.season === season);
    expect(h).toBeTruthy();
    if (!w.meta.unemployed) expect(w.meta.board!.objectives.some((o) => o.season === season + 1)).toBe(true);
    expect(w.meta.board!.objectives.filter((o) => o.season === season).every((o) => o.status !== 'pending')).toBe(true);
  });

  it('has no board for commissioners', () => {
    const w = career('commissioner', 'sandbox');
    expect(boardReview(w, w.season)).toBe(false);
  });
});

describe('mid-season board, manager contract and job approaches', () => {
  async function mods() {
    return import('../src/systems/career');
  }
  function userLeagueTeam(w: ReturnType<typeof career>) {
    return Object.values(w.teams).find((t) => t.orgId === w.meta.userOrgId && t.leagueId && t.active)!;
  }
  function splits(w: ReturnType<typeof career>, leagueId: string) {
    return Object.values(w.comps).filter((c) => c.leagueId === leagueId && c.season === w.season).sort((a, b) => a.startDay - b.startDay);
  }
  function finish(w: ReturnType<typeof career>, compId: string, teamId: string, pos: 'first' | 'last') {
    const c = w.comps[compId];
    // Entrants are resolved when a competition starts; a finished split always has them.
    if (!c.teamIds.length) c.teamIds = [...w.leagues[c.leagueId!].teamIds];
    const others = c.teamIds.filter((t) => t !== teamId);
    c.placements = pos === 'first' ? [teamId, ...others] : [...others, teamId];
    return c;
  }

  it('reacts to a split against the season objective (praise or warning)', async () => {
    const { boardCompetitionReaction } = await mods();
    const w = career('coach-gm', 'split-react');
    const team = userLeagueTeam(w);
    const ss = splits(w, team.leagueId!);
    expect(ss.length).toBeGreaterThanOrEqual(2);
    const o = w.meta.board!.objectives.find((x) => x.teamId === team.id && x.kind === 'league-rank')!;
    o.target = 3;
    w.meta.board!.confidence = 60;
    boardCompetitionReaction(w, finish(w, ss[0].id, team.id, 'first'));
    expect(w.meta.board!.confidence).toBeGreaterThan(60);
    expect(w.inbox.some((m) => m.subject.startsWith('Board delighted'))).toBe(true);
    const before = w.meta.board!.confidence;
    boardCompetitionReaction(w, finish(w, ss[0].id, team.id, 'last'));
    expect(w.meta.board!.confidence).toBeLessThan(before);
    expect(w.meta.board!.notes!.length).toBe(2);
    expect(w.meta.unemployed).toBeFalsy();
  });

  it('issues an ultimatum, then dismisses mid-season if results stay poor', async () => {
    const { boardCompetitionReaction } = await mods();
    const w = career('coach-gm', 'ultimatum');
    const team = userLeagueTeam(w);
    const [s1] = splits(w, team.leagueId!);
    w.meta.board!.objectives.find((x) => x.teamId === team.id && x.kind === 'league-rank')!.target = 1;
    w.meta.board!.confidence = 25;
    boardCompetitionReaction(w, finish(w, s1.id, team.id, 'last'));
    expect(w.meta.board!.ultimatum?.season).toBe(w.season);
    expect(w.inbox.some((m) => m.body.includes('ULTIMATUM'))).toBe(true);
    expect(w.meta.unemployed).toBeFalsy();
    w.day += 1;
    boardCompetitionReaction(w, finish(w, s1.id, team.id, 'last'));
    expect(w.meta.unemployed).toBe(true);
    const org = w.orgs[w.meta.userOrgId!];
    expect(org.user).toBe(false);
    for (const tid of org.teams) expect(w.teams[tid].prefs.delegateLineup && w.teams[tid].prefs.delegateDraft).toBe(true);
    expect(w.meta.board!.history.at(-1)!.verdict).toBe('dismissed (mid-season)');
  });

  it('pauses the advance as soon as the job is lost mid-season', async () => {
    const { seasonEndDay } = await import('../src/competition/season');
    const w = makeWorld(['lol'], 'midseason-sack');
    w.meta.careerRole = 'coach-gm';
    const weakest = Object.values(w.orgs).filter((o) => o.teams.some((t) => w.teams[t].leagueId)).sort((a, b) => a.rep - b.rep)[0];
    setUserOrg(w, weakest.id);
    setBoardObjectives(w);
    for (const o of w.meta.board!.objectives) if (o.kind === 'league-rank') o.target = 1;
    w.meta.board!.confidence = 14;
    w.meta.board!.ultimatum = { season: w.season, day: w.day - 1 };
    const season = w.season;
    const r = advance(w, { kind: 'end-season' });
    expect(w.meta.unemployed).toBe(true);
    expect(r.stoppedFor).toBe('decision');
    expect(w.day).toBeLessThan(seasonEndDay(season));
  });

  it('rewards success with job approaches; accepting moves the manager, declining pleases the board', async () => {
    const { maybeJobApproach, respondToApproach, MANAGER_YEARS } = await mods();
    const w = makeWorld(['lol', 'valorant'], 'approach', 'small');
    w.meta.careerRole = 'coach-gm';
    const orgs = Object.values(w.orgs).filter((o) => o.teams.length).sort((a, b) => a.rep - b.rep);
    const mid = orgs[Math.floor(orgs.length / 3)];
    setUserOrg(w, mid.id);
    setBoardObjectives(w);
    expect(w.meta.managerContract).toMatchObject({ orgId: mid.id, endSeason: w.season + MANAGER_YEARS - 1 });
    const a = maybeJobApproach(w, 'impressed by your work', true)!;
    expect(a).toBeTruthy();
    expect(w.orgs[a.orgId].rep).toBeGreaterThan(mid.rep);
    expect(w.inbox.some((m) => m.payload?.approachId === a.id)).toBe(true);
    // Only one open approach at a time.
    expect(maybeJobApproach(w, 'again', true)).toBeNull();
    expect(respondToApproach(w, a.id, true).ok).toBe(true);
    expect(w.meta.userOrgId).toBe(a.orgId);
    expect(w.orgs[mid.id].user).toBe(false);
    expect(w.orgs[a.orgId].user).toBe(true);
    expect(w.meta.managerContract).toMatchObject({ orgId: a.orgId, endSeason: w.season + a.years - 1 });
    expect(w.meta.board!.objectives.some((o) => o.season === w.season && o.teamId && w.teams[o.teamId].orgId === a.orgId)).toBe(true);
    const b = maybeJobApproach(w, 'still impressed', true);
    if (b) {
      const conf = w.meta.board!.confidence;
      expect(respondToApproach(w, b.id, false).ok).toBe(true);
      expect(w.meta.board!.confidence).toBe(Math.min(100, conf + 2));
      expect(respondToApproach(w, b.id, true).ok).toBe(false);
    }
  });

  it('renews the manager contract when the board is happy and lets it run out otherwise', () => {
    const happy = career('coach-gm', 'renew');
    happy.meta.managerContract!.endSeason = happy.season;
    happy.meta.board!.confidence = 90;
    expect(boardReview(happy, happy.season)).toBe(false);
    expect(happy.meta.managerContract!.endSeason).toBe(happy.season + 2);
    const unhappy = career('coach-gm', 'renew2');
    unhappy.meta.managerContract!.endSeason = unhappy.season;
    unhappy.meta.board!.confidence = 15;
    expect(boardReview(unhappy, unhappy.season)).toBe(true);
    expect(unhappy.meta.unemployed).toBe(true);
    expect(unhappy.meta.board!.history.at(-1)!.verdict).toBe('contract not renewed');
  });

  it('warns about finances once per season and penalises months in the red', async () => {
    const { boardMonthly } = await mods();
    const w = career('coach-gm', 'money');
    w.orgs[w.meta.userOrgId!].balance = -5000;
    w.meta.board!.confidence = 50;
    boardMonthly(w);
    boardMonthly(w);
    expect(w.meta.board!.confidence).toBe(44);
    expect(w.inbox.filter((m) => m.subject === 'Board alarmed by finances').length).toBe(1);
  });
});

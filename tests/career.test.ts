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

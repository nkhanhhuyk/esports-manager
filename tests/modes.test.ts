import { describe, expect, it } from 'vitest';
import { makeWorld, withUser } from './helpers';
import type { Team, World } from '../src/model/types';
import { advance } from '../src/game/advance';
import { setUserOrg } from '../src/gen/world';
import { becomeSpectator, isSpectator, watchedFixturesOn } from '../src/systems/follow';
import { userHandles } from '../src/systems/career';
import { aiMarketDay, releasePlayer } from '../src/systems/transfers';
import { plannedRegistration } from '../src/systems/registration';
import { getTitle } from '../src/data/titles/catalogue';

function mainTeam(w: World): Team {
  return w.teams[w.orgs[w.meta.userOrgId!].teams.find((id) => w.teams[id].tier === 'main')!];
}
function shortHanded(w: World, t: Team) {
  const size = getTitle(t.titleId).teamSize;
  while (t.roster.length >= size) releasePlayer(w, t.roster[t.roster.length - 1]);
}

describe('spectator mode', () => {
  it('nobody is user-controlled; clubs run themselves and followed teams are watched closely', () => {
    const w = withUser(makeWorld(['lol', 'cs2'], 'spectate'));
    const formerOrg = w.meta.userOrgId!;
    const former = w.orgs[formerOrg].teams.filter((id) => w.teams[id].active);
    becomeSpectator(w);
    expect(isSpectator(w)).toBe(true);
    expect(w.meta.userOrgId).toBeNull();
    expect(Object.values(w.orgs).some((o) => o.user)).toBe(false);
    expect(w.meta.followed).toEqual(former);
    // Follow a dozen main teams (more than 20 turns per-match inbox reports off).
    w.meta.followed = Object.values(w.teams).filter((t) => t.active && !t.national && t.tier === 'main').map((t) => t.id).slice(0, 12);
    advance(w, { kind: 'end-season' }, { stopForUserMatch: false });
    // The former club's staff kept a full roster and nothing waits for a user decision.
    for (const id of former) expect(w.teams[id].roster.length).toBeGreaterThanOrEqual(getTitle(w.teams[id].titleId).teamSize);
    expect(w.offers.filter((o) => o.user && o.status === 'pending')).toHaveLength(0);
    // Followed teams: full-detail matches, match reports and training timetables.
    const followedDone = Object.values(w.fixtures).filter((f) => f.status === 'done' && f.teams.some((t) => w.meta.followed!.includes(t)));
    expect(followedDone.length).toBeGreaterThan(0);
    expect(followedDone.every((f) => f.detail)).toBe(true);
    expect(w.inbox.some((m) => m.from === 'Match Report')).toBe(true);
    expect(Object.values(w.teams).some((t) => t.training.week)).toBe(true);
  });

  it('can stop the day before a followed team plays, and take charge again', () => {
    const w = makeWorld(['lol'], 'spectate-stop');
    becomeSpectator(w, Object.values(w.teams).filter((t) => t.active && t.tier === 'main').map((t) => t.id));
    w.meta.stopForFollowed = true;
    const r = advance(w, { kind: 'days', n: 90 });
    expect(r.stoppedFor).toBe('user-match');
    expect(watchedFixturesOn(w, w.day + 1).map((f) => f.id)).toContain(r.fixtureId);
    const org = Object.values(w.orgs).find((o) => !o.federation && o.teams.length)!;
    setUserOrg(w, org.id);
    expect(w.meta.mode).toBe('manager');
    expect(w.orgs[org.id].user).toBe(true);
  });
});

describe('delegation', () => {
  it('delegated signings: the general manager fills a short roster; otherwise the manager must', () => {
    const run = (delegate: boolean) => {
      const w = withUser(makeWorld(['lol'], 'delegate-sign'));
      w.meta.delegation = { signings: delegate };
      expect(userHandles(w, 'signings')).toBe(!delegate);
      const t = mainTeam(w);
      shortHanded(w, t);
      const before = t.roster.length;
      for (let i = 0; i < 5; i++) {
        aiMarketDay(w);
        w.day++;
      }
      return { before, after: t.roster.length, size: getTitle(t.titleId).teamSize };
    };
    const own = run(false);
    expect(own.after).toBe(own.before);
    const staff = run(true);
    expect(staff.after).toBeGreaterThanOrEqual(staff.size);
  });

  it('delegated registration ignores the manager’s plan; the coach decides', () => {
    const w = withUser(makeWorld(['lol'], 'delegate-reg'));
    const t = mainTeam(w);
    const size = getTitle(t.titleId).teamSize;
    const bench = [...t.roster].reverse().slice(0, size);
    t.prefs.registrationPlan = bench;
    expect(plannedRegistration(w, t).slice(0, size)).toEqual(bench);
    w.meta.delegation = { registration: true };
    const coach = plannedRegistration(w, t);
    expect(coach.slice(0, size)).not.toEqual(bench);
  });

  it('a role without the permission always leaves the area to staff', () => {
    const w = withUser(makeWorld(['lol'], 'delegate-role'));
    w.meta.careerRole = 'head-coach';
    expect(userHandles(w, 'signings')).toBe(false);
    expect(userHandles(w, 'registration')).toBe(true);
    w.meta.delegation = { registration: true };
    expect(userHandles(w, 'registration')).toBe(false);
  });
});

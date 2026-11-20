import { dayFromYMD } from '../src/core/date';
import { describe, expect, it } from 'vitest';
import { clone, makeWorld, withUser } from './helpers';
import type { SessionType, Team, World } from '../src/model/types';
import { coachPlan, intelEdge, practiceRooms, runTrainingWeek, SESSIONS, staffCapacity, weekCalendar } from '../src/systems/sessions';
import { weeklyTraining } from '../src/systems/training';
import { advance } from '../src/game/advance';
import { setUserOrg } from '../src/gen/world';

function userTeam(w: World): Team {
  return w.teams[w.orgs[w.meta.userOrgId!].teams.find((id) => w.teams[id].tier === 'main')!];
}
function selfPlan(t: Team, tpl: SessionType[]) {
  t.prefs.delegateTraining = false;
  t.training.template = tpl;
}

describe('training sessions', () => {
  it('coaches plan around match days, rest, scrims and their own capacity', () => {
    const w = withUser(makeWorld(['lol'], 'sess-plan'));
    // A week in the split 2 regular season.
    advance(w, { kind: 'until', day: dayFromYMD(w.season, 4, 14) }, { stopForUserMatch: false });
    const cal = weekCalendar(w);
    const team = Object.values(w.teams).find((t) => t.active && !t.national && cal.matchDays.get(t.id)?.size)!;
    const plan = coachPlan(w, team, cal);
    expect(plan).toHaveLength(14);
    for (const d of cal.matchDays.get(team.id)!) expect(['rest', 'prep', 'review', 'mental']).toContain(plan[d * 2]);
    expect([0, 1, 2, 3, 4, 5, 6].some((d) => plan[d * 2] === 'rest' && plan[d * 2 + 1] === 'rest')).toBe(true);
    expect(plan.filter((x) => x === 'scrim').length).toBeLessThanOrEqual(team.training.scrimsPerWeek);
    expect(plan.filter((x) => SESSIONS[x].coached).length).toBeLessThanOrEqual(Math.max(staffCapacity(w, team), plan.filter((x) => x === 'prep').length));
  });

  it('cancelled sessions grant nothing: a week of rest develops no one, a full week does', () => {
    const base = withUser(makeWorld(['lol'], 'sess-phantom'));
    const run = (tpl: SessionType[]) => {
      const w = clone(base);
      const t = userTeam(w);
      selfPlan(t, tpl);
      const young = t.roster.map((id) => w.players[id]).sort((a, b) => b.birth - a.birth)[0];
      const before = { attrs: [...young.attrs], title: young.prof.titles.lol, role: { ...young.prof.roles } };
      // A progress week (attributes move every second week).
      if (Math.floor(w.day / 7) % 2 !== 0) w.day += 7;
      weeklyTraining(w);
      return { w, t, young, before };
    };
    const rest = run(new Array(14).fill('rest'));
    expect(rest.t.training.week!.devFactor).toBe(0);
    expect(rest.young.attrs).toEqual(rest.before.attrs);
    expect(rest.young.prof.titles.lol).toBe(rest.before.title);
    const full = run(['individual', 'scrim', 'individual', 'tactics', 'pool', 'scrim', 'individual', 'review', 'maps', 'scrim', 'individual', 'tactics', 'rest', 'rest']);
    expect(full.t.training.week!.devFactor).toBeGreaterThan(0.5);
    const gain = full.young.attrs.reduce((a, v, i) => a + v - full.before.attrs[i], 0);
    expect(gain).toBeGreaterThan(0);
  });

  it('match days cancel heavy sessions and practice rooms are shared by the organisation', () => {
    const w = makeWorld(['lol', 'cs2', 'valorant', 'mlbb'], 'sess-rooms', 'small');
    const multi = Object.values(w.orgs).filter((o) => o.teams.filter((id) => w.teams[id]?.active && w.teams[id].roster.length).length >= 2).sort((a, b) => b.teams.length - a.teams.length)[0];
    setUserOrg(w, multi.id);
    const org = w.orgs[w.meta.userOrgId!];
    const teams = org.teams.map((id) => w.teams[id]).filter((t) => t.active && t.roster.length);
    expect(teams.length).toBeGreaterThanOrEqual(2);
    org.facilities.training = 1;
    expect(practiceRooms(org)).toBe(1);
    for (const t of teams) selfPlan(t, new Array(14).fill('individual'));
    runTrainingWeek(w);
    const cancelledForRoom = teams.flatMap((t) => t.training.week!.slots.filter((s) => s.s === 'cancelled' && /practice room/.test(s.r ?? '')));
    // One room, several divisions wanting it in every block: all but one division lose their blocks.
    expect(cancelledForRoom.length).toBeGreaterThanOrEqual(14 * (teams.length - 1) - 14);
    // Match days: with a fixture this week the afternoon becomes the match.
    advance(w, { kind: 'until', day: w.day + 70 }, { stopForUserMatch: false });
    const cal = weekCalendar(w);
    const playing = teams.find((t) => cal.matchDays.get(t.id)?.size);
    if (playing) {
      org.facilities.training = 10;
      runTrainingWeek(w);
      const d = [...cal.matchDays.get(playing.id)!][0];
      const wk = playing.training.week!;
      expect(wk.slots[d * 2 + 1].t).toBe('match');
      expect(wk.slots[d * 2].s).toBe('cancelled');
      expect(wk.slots[d * 2].r).toMatch(/Match day/);
    }
  });

  it('without coaching staff, coached sessions run unsupervised', () => {
    const w = withUser(makeWorld(['lol'], 'sess-staff'));
    const t = userTeam(w);
    t.staff = [];
    for (const s of Object.values(w.staff)) if (s.orgId === t.orgId && !s.teamId) s.orgId = null;
    expect(staffCapacity(w, t)).toBe(0);
    selfPlan(t, ['individual', 'tactics', 'individual', 'pool', 'rest', 'rest', 'individual', 'maps', 'rest', 'rest', 'rest', 'rest', 'rest', 'rest']);
    w.orgs[t.orgId].facilities.training = 10;
    runTrainingWeek(w);
    const wk = t.training.week!;
    expect(wk.slots.filter((s) => s.s === 'unsupervised').length).toBe(6);
  });

  it('scrims are played against real partners, both sides record them, and both gain intel', () => {
    const w = withUser(makeWorld(['lol'], 'sess-scrims'));
    // Follow every team so each records its timetable.
    w.meta.followed = Object.values(w.teams).filter((t) => t.active && !t.national).map((t) => t.id);
    const sums = runTrainingWeek(w);
    let played = 0;
    for (const t of Object.values(w.teams)) {
      const wk = t.training.week;
      if (!wk) continue;
      wk.slots.forEach((s, i) => {
        if (s.t !== 'scrim' || s.s !== 'held' || !s.p || s.internal) return;
        played++;
        const partner = w.teams[s.p];
        expect(partner.titleId).toBe(t.titleId);
        expect(partner.orgId).not.toBe(t.orgId);
        const back = partner.training.week!.slots[i];
        expect(back.p).toBe(t.id);
        expect(back.w).toBe(s.w === 1 ? 0 : 1);
        expect(intelEdge(w, t, partner)).toBeGreaterThan(1);
        expect(intelEdge(w, partner, t)).toBeGreaterThan(1);
      });
    }
    expect(played).toBeGreaterThan(0);
    expect([...sums.values()].some((s) => s.scrimQ > 0)).toBe(true);
  });

  it('the outside-league policy never scrims a league rival', () => {
    const w = withUser(makeWorld(['lol'], 'sess-policy'));
    const t = userTeam(w);
    t.training.scrimPolicy = 'outside-league';
    selfPlan(t, ['scrim', 'scrim', 'scrim', 'scrim', 'rest', 'scrim', 'scrim', 'scrim', 'rest', 'rest', 'rest', 'rest', 'rest', 'rest']);
    w.orgs[t.orgId].facilities.training = 10;
    runTrainingWeek(w);
    for (const s of t.training.week!.slots) if (s.p && !s.internal && s.s !== 'cancelled') expect(w.teams[s.p].leagueId).not.toBe(t.leagueId);
  });
});

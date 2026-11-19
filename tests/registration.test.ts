import { describe, expect, it } from 'vitest';
import { makeWorld, withUser } from './helpers';
import { advance } from '../src/game/advance';
import { dayFromYMD } from '../src/core/date';
import { AMENDMENTS, amendRegistration, maxRegistered } from '../src/systems/registration';
import { releasePlayer } from '../src/systems/transfers';
import type { Competition, World } from '../src/model/types';

function firstLeagueSplit(w: World, teamId: string): Competition {
  const team = w.teams[teamId];
  return Object.values(w.comps).filter((c) => c.leagueId === team.leagueId && c.season === w.season).sort((a, b) => a.startDay - b.startDay)[0];
}

function lockedWorld(seed: string, plan?: (w: World, teamId: string) => void) {
  const w = withUser(makeWorld(['valorant'], seed));
  const mine = Object.values(w.teams).find((t) => t.orgId === w.meta.userOrgId && t.leagueId && t.tier === 'main')!;
  plan?.(w, mine.id);
  const comp = firstLeagueSplit(w, mine.id);
  advance(w, { kind: 'until', day: comp.startDay - 2 }, { stopForUserMatch: false });
  return { w, mine, comp: w.comps[comp.id] };
}

describe('competition registration (separate from contracts)', () => {
  it('locks a registration for every entrant: 5-8 rostered players, never shared between teams', () => {
    const { w, mine, comp } = lockedWorld('reg-lock');
    expect(comp.status).toBe('active');
    const seen = new Set<string>();
    for (const tid of comp.teamIds) {
      const reg = comp.registrations![tid];
      const team = w.teams[tid];
      expect(reg.length).toBeGreaterThanOrEqual(5);
      expect(reg.length).toBeLessThanOrEqual(maxRegistered(w, team));
      for (const pid of reg) {
        expect(team.roster).toContain(pid);
        expect(seen.has(pid)).toBe(false);
        seen.add(pid);
      }
    }
    expect(w.inbox.some((m) => m.subject === `Registration locked: ${mine.name} in ${comp.short}`)).toBe(true);
  });

  it("uses the user's standing registration plan", () => {
    let plan: string[] = [];
    const { comp, mine } = lockedWorld('reg-plan', (w, tid) => {
      const team = w.teams[tid];
      plan = team.roster.slice(0, 6);
      team.prefs.registrationPlan = plan;
    });
    expect(comp.registrations![mine.id]).toEqual(plan);
  });

  it('allows a limited number of changes in a transfer window, emergencies at any time', () => {
    const { w, mine, comp } = lockedWorld('reg-amend');
    const reg = comp.registrations![mine.id];
    // Make an unregistered rostered player available to register.
    let extra = mine.roster.find((id) => !reg.includes(id));
    if (!extra) {
      const fa = Object.values(w.players).find((p) => !p.teamId && !p.retired && p.titleId === 'valorant')!;
      fa.teamId = mine.id;
      mine.roster.push(fa.id);
      extra = fa.id;
    }
    // Outside a transfer window a normal change is refused.
    const saved = w.day;
    w.day = dayFromYMD(2027, 3, 1);
    expect(amendRegistration(w, comp.id, mine.id, extra, reg[reg.length - 1]).ok).toBe(false);
    // Inside a window: up to AMENDMENTS swaps.
    w.day = dayFromYMD(2027, 1, 5);
    const subs = reg.slice(5);
    let changes = 0;
    for (const out of [reg[reg.length - 1], reg[reg.length - 2]]) {
      const candidate = mine.roster.find((id) => !comp.registrations![mine.id].includes(id));
      if (!candidate || !subs.length) break;
      expect(amendRegistration(w, comp.id, mine.id, candidate, out).ok).toBe(true);
      changes++;
    }
    if (changes === AMENDMENTS) {
      const candidate = mine.roster.find((id) => !comp.registrations![mine.id].includes(id));
      if (candidate) expect(amendRegistration(w, comp.id, mine.id, candidate, comp.registrations![mine.id][0]).message).toMatch(/used/);
    }
    // A player registered for another team cannot be registered again.
    const other = comp.teamIds.find((t) => t !== mine.id)!;
    const theirs = comp.registrations![other][0];
    expect(amendRegistration(w, comp.id, mine.id, theirs).ok).toBe(false);
    // Emergency: if fewer than five registered players remain available, registration is allowed even outside windows.
    w.day = dayFromYMD(2027, 3, 1);
    const current = [...comp.registrations![mine.id]];
    for (const pid of current.slice(0, current.length - 4)) w.players[pid].injury = { label: 'test', untilDay: w.day + 30 };
    const fill = Object.values(w.players).find((p) => !p.teamId && !p.retired && p.titleId === 'valorant')!;
    fill.teamId = mine.id;
    mine.roster.push(fill.id);
    const r = amendRegistration(w, comp.id, mine.id, fill.id, comp.registrations![mine.id].find((id) => w.players[id].injury) ?? null);
    expect(r.ok).toBe(true);
    expect(comp.amendments![mine.id].at(-1)!.emergency).toBe(true);
    w.day = saved;
  });

  it('tells the user when a signing is not registered and lets AI clubs register theirs', async () => {
    const { offerFreeAgent } = await import('../src/systems/transfers');
    const { w, mine, comp } = lockedWorld('reg-sign');
    w.orgs[mine.orgId].balance += 1e7;
    while (mine.roster.length > 7) releasePlayer(w, mine.roster[mine.roster.length - 1]);
    const adult = (p: { birth: number }) => w.day - p.birth > 18 * 365.25;
    const fa = Object.values(w.players).filter((p) => !p.teamId && !p.retired && p.titleId === 'valorant' && adult(p)).sort((a, b) => a.ca - b.ca)[0];
    const r = offerFreeAgent(w, mine.id, fa.id, 400000, 2);
    expect(r.ok, r.message).toBe(true);
    expect(comp.registrations![mine.id]).not.toContain(fa.id);
    expect(w.inbox.some((m) => m.subject === `${fa.handle} is not registered`)).toBe(true);
    // AI: inside a transfer window, a club with a free registration place registers its signing.
    w.day = dayFromYMD(2027, 1, 10);
    const ai = comp.teamIds.map((t) => w.teams[t]).find((t) => t.orgId !== w.meta.userOrgId)!;
    const list = comp.registrations![ai.id];
    if (list.length >= maxRegistered(w, ai)) list.pop();
    const fa2 = Object.values(w.players).find((p) => !p.teamId && !p.retired && p.titleId === 'valorant' && adult(p))!;
    w.orgs[ai.orgId].balance += 1e7;
    const { movePlayer } = await import('../src/systems/transfers');
    while (ai.roster.length >= 9) releasePlayer(w, ai.roster[ai.roster.length - 1]);
    movePlayer(w, fa2, ai, 50000, 1, 'free');
    expect(comp.registrations![ai.id]).toContain(fa2.id);
  });

  it('only registered players appear in a competition (season simulation)', () => {
    const w = withUser(makeWorld(['lol', 'cs2'], 'reg-season'));
    advance(w, { kind: 'until', day: dayFromYMD(2027, 4, 20) }, { stopForUserMatch: false });
    let checked = 0;
    for (const c of Object.values(w.comps)) {
      if (!c.registrations) continue;
      for (const [pid, ps] of Object.entries(c.pstats)) {
        const allowed = new Set([...(c.registrations[ps.teamId] ?? []), ...(c.amendments?.[ps.teamId] ?? []).map((a) => a.added)]);
        expect(allowed.has(pid), `${pid} played ${c.short} unregistered`).toBe(true);
        checked++;
      }
    }
    expect(checked).toBeGreaterThan(50);
  });
});

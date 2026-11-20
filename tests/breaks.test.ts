import { describe, expect, it } from 'vitest';
import { clone, makeWorld, withUser } from './helpers';
import type { Player, Team, World } from '../src/model/types';
import { advance } from '../src/game/advance';
import { dayFromYMD } from '../src/core/date';
import { seasonEndDay } from '../src/competition/season';
import { getTitle } from '../src/data/titles/catalogue';
import { isAvailable, seasonTitle, titleLockReason } from '../src/systems/availability';
import { breakRisk, breaksDaily, breaksWeekly, decideBreakRequest, recallPlayer, restPlayer, startLeave } from '../src/systems/breaks';
import { autoLineup } from '../src/systems/lineup';
import { aiTradesWeek } from '../src/systems/trades';
import { aiMarketDay, offerFreeAgent, promotePlayer, releasePlayer, transferWindowNotices } from '../src/systems/transfers';
import { weeklyTraining } from '../src/systems/training';

function userMain(w: World): Team {
  return w.teams[w.orgs[w.meta.userOrgId!].teams.find((id) => w.teams[id].tier === 'main')!];
}
function inSeason(w: World) {
  advance(w, { kind: 'until', day: dayFromYMD(w.season, 3, 1) }, { stopForUserMatch: false });
}

describe('one game per season', () => {
  it('a player who has played one title this season cannot join another title until next season', () => {
    const w = withUser(makeWorld(['lol', 'cs2'], 'one-game'));
    inSeason(w);
    const lolTeam = Object.values(w.teams).find((t) => t.titleId === 'lol' && t.tier === 'main' && t.orgId !== w.meta.userOrgId)!;
    const p = lolTeam.lineup.map((s) => w.players[s.playerId]).find((x) => seasonTitle(w, x) === 'lol')!;
    expect(p).toBeDefined();
    releasePlayer(w, p.id);
    const cs2 = Object.values(w.teams).find((t) => t.titleId === 'cs2' && t.tier === 'main')!;
    const lock = titleLockReason(w, p, 'cs2');
    expect(lock).toMatch(/one game per season/);
    const r = offerFreeAgent(w, cs2.id, p.id, 100000, 1);
    expect(r.ok).toBe(false);
    expect(r.message).toMatch(/one game per season/);
    expect(titleLockReason(w, p, 'lol')).toBeNull();
    // Promotions inside an organisation follow the same rule.
    const multi = Object.values(w.orgs).find((o) => !o.federation && o.teams.some((id) => w.teams[id].titleId === 'lol') && o.teams.some((id) => w.teams[id].titleId === 'cs2'));
    if (multi) {
      const from = w.teams[multi.teams.find((id) => w.teams[id].titleId === 'lol')!];
      const to = w.teams[multi.teams.find((id) => w.teams[id].titleId === 'cs2')!];
      const q = from.lineup.map((s) => w.players[s.playerId]).find((x) => seasonTitle(w, x) === 'lol');
      if (q) expect(promotePlayer(w, q.id, to.id).message).toMatch(/one game per season/);
    }
    // Next season the lock lifts.
    advance(w, { kind: 'until', day: seasonEndDay(w.season) + 2 }, { stopForUserMatch: false });
    expect(titleLockReason(w, p, 'cs2')).toBeNull();
  });

  it('over a simulated season nobody competes in two titles', () => {
    const w = makeWorld(['lol', 'wildrift', 'valorant', 'cs2'], 'one-game-sim');
    advance(w, { kind: 'until', day: seasonEndDay(w.season) - 1 }, { stopForUserMatch: false });
    for (const p of Object.values(w.players)) {
      const titles = new Set(p.stats.filter((s) => s.season === w.season && s.gp > 0).map((s) => s.titleId));
      expect(titles.size).toBeLessThanOrEqual(1);
    }
  });
});

describe('breaks', () => {
  it('exhausted or miserable players are far more likely to ask for a break', () => {
    const w = makeWorld(['lol'], 'break-risk');
    const p = Object.values(w.players).find((x) => x.teamId)!;
    const calm = { ...p, fatigue: 10, morale: 70, unhappy: 0 } as Player;
    const worn = { ...p, fatigue: 95, morale: 15, unhappy: 80 } as Player;
    expect(breakRisk(worn)).toBeGreaterThan(breakRisk(calm) * 10);
  });

  it('a requested break: grant → away from lineups, training and registration, then back', () => {
    const w = withUser(makeWorld(['lol'], 'break-grant'));
    inSeason(w);
    const t = userMain(w);
    // The youngest starter: no age-related decline, so any attribute change would be training.
    const p = t.lineup.map((s) => w.players[s.playerId]).sort((a, b) => b.birth - a.birth)[0];
    p.leaveRequest = { kind: 'break', reason: 'Mental health break', days: 14, day: w.day };
    expect(decideBreakRequest(w, p.id, true).ok).toBe(true);
    expect(isAvailable(p, w.day)).toBe(false);
    expect(t.lineup.some((s) => s.playerId === p.id)).toBe(false);
    // Even short-handed, a player on a break is never fielded.
    const others = t.roster.filter((id) => id !== p.id).slice(getTitle(t.titleId).teamSize - 2);
    for (const id of others) w.players[id].injury = { label: 'x', untilDay: w.day + 30 };
    expect(autoLineup(w, t).some((s) => s.playerId === p.id)).toBe(false);
    for (const id of others) w.players[id].injury = null;
    // No training while away.
    const attrs = [...p.attrs];
    if (Math.floor(w.day / 7) % 2 !== 0) w.day += 7;
    weeklyTraining(w);
    expect(p.attrs).toEqual(attrs);
    // Back after the break.
    for (let d = 0; d < 15; d++) {
      w.day++;
      breaksDaily(w);
    }
    expect(p.leave).toBeNull();
    expect(isAvailable(p, w.day)).toBe(true);
    expect(w.inbox.some((m) => m.subject === `${p.handle} is back`)).toBe(true);
  });

  it('refusing costs morale; an exhausted player breaks down anyway; no answer in 7 days = he goes', () => {
    const w = withUser(makeWorld(['lol'], 'break-refuse'));
    inSeason(w);
    const t = userMain(w);
    const [a, b, c] = t.lineup.map((s) => w.players[s.playerId]);
    a.fatigue = 40;
    a.leaveRequest = { kind: 'break', reason: 'Family matters', days: 14, day: w.day };
    const morale = a.morale;
    decideBreakRequest(w, a.id, false);
    expect(a.leave ?? null).toBeNull();
    expect(a.morale).toBeLessThan(morale);
    b.fatigue = 90;
    b.leaveRequest = { kind: 'break', reason: 'Burnout', days: 28, day: w.day };
    decideBreakRequest(w, b.id, false);
    expect(b.leave?.untilDay).toBeGreaterThan(w.day);
    c.leaveRequest = { kind: 'hiatus', reason: 'Personal reasons', days: 0, day: w.day - 8 };
    breaksWeekly(w);
    expect(c.leave?.kind).toBe('hiatus');
    expect(c.leave?.untilDay).toBe(seasonEndDay(w.season));
  });

  it('managers can rest a player and bring him back early', () => {
    const w = withUser(makeWorld(['lol'], 'break-rest'));
    const p = w.players[userMain(w).lineup[0].playerId];
    expect(restPlayer(w, p.id, 2).ok).toBe(true);
    expect(isAvailable(p, w.day)).toBe(false);
    expect(recallPlayer(w, p.id).ok).toBe(true);
    expect(isAvailable(p, w.day)).toBe(true);
  });

  it('AI clubs replace a starter who steps away for the season', () => {
    const w = makeWorld(['lol'], 'break-replace');
    inSeason(w);
    const clubs = Object.values(w.teams).filter((x) => x.tier === 'main' && !x.national);
    const t = clubs.find((x) => x.roster.length === getTitle(x.titleId).teamSize) ?? clubs[0];
    while (t.roster.length > getTitle(t.titleId).teamSize) releasePlayer(w, t.roster[t.roster.length - 1]);
    const p = w.players[t.lineup[0].playerId];
    startLeave(w, p, 'hiatus', 'Personal reasons', 0);
    const before = t.roster.length;
    for (let i = 0; i < 5; i++) {
      aiMarketDay(w);
      w.day++;
    }
    expect(t.roster.length).toBeGreaterThan(before);
    expect(t.roster).toContain(p.id);
  });
});

describe('mid-season moves', () => {
  it('AI clubs trade within a title in the windows; windows are announced', () => {
    const w = makeWorld(['lol', 'valorant', 'cs2'], 'ai-trades', 'small');
    const base = clone(w);
    // Rosters wear down during the season (injuries, breaks, form): trade in the mid-season window.
    advance(w, { kind: 'until', day: dayFromYMD(w.season, 5, 10) }, { stopForUserMatch: false });
    const start = w.transfers.length;
    let trades = 0;
    for (let i = 0; i < 20 && trades === 0; i++) {
      w.day = dayFromYMD(w.season, 5, 10) + i;
      trades += aiTradesWeek(w);
    }
    expect(trades).toBeGreaterThan(0);
    const recs = w.transfers.slice(start).filter((t) => t.type === 'trade');
    // 1-for-1 (2 player moves), 2-for-1 (3) or player-for-picks (1).
    expect(recs.length).toBeGreaterThanOrEqual(trades);
    expect(recs.length).toBeLessThanOrEqual(trades * 3);
    for (const r of recs) expect(w.teams[r.fromTeamId!].titleId).toBe(w.teams[r.toTeamId!].titleId);
    // Outside the windows nothing is traded.
    base.day = dayFromYMD(2027, 3, 3);
    expect(aiTradesWeek(base)).toBe(0);
    base.day = dayFromYMD(2027, 5, 10);
    transferWindowNotices(base);
    expect(base.news.some((n) => n.title === 'Mid-season transfer window opens')).toBe(true);
  });
});

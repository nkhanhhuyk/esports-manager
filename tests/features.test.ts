import 'fake-indexeddb/auto';
import { describe, expect, it } from 'vitest';
import { clone, makeWorld, withUser } from './helpers';
import type { Team, World } from '../src/model/types';
import { advance } from '../src/game/advance';
import { setUserOrg, createWorld } from '../src/gen/world';
import { dayFromYMD } from '../src/core/date';
import { seasonEndDay } from '../src/competition/season';
import { monthlyBreakdown } from '../src/systems/finance';
import { boardDelta, PRESETS } from '../src/systems/difficulty';
import { dailyCondition } from '../src/systems/training';
import { sellerAsk, startTransferTalks } from '../src/systems/negotiation';
import { aiOffersToUser, respondToTradeOffer } from '../src/systems/trades';
import { releasePlayer, offerFreeAgent } from '../src/systems/transfers';
import { signProspect, startTrial, youthDaily } from '../src/systems/youth';
import { headToHead } from '../src/systems/seasonReport';
import { START } from './helpers';

function userMain(w: World): Team {
  return w.teams[w.orgs[w.meta.userOrgId!].teams.find((id) => w.teams[id].tier === 'main')!];
}
/** A user club in a league that permits trades (franchised). */
function tradeWorld(seed: string): World {
  const w = makeWorld(['lol', 'valorant', 'cs2'], seed, 'small');
  const t = Object.values(w.teams).find((x) => x.tier === 'main' && x.leagueId && w.leagues[x.leagueId].policy.trades)!;
  setUserOrg(w, t.orgId);
  return w;
}

describe('difficulty & sandbox', () => {
  it('presets change income, injuries, board losses and bargaining', () => {
    const relaxed = createWorld({ name: 'R', seed: 'diff', worldSize: 'tiny', titles: ['lol'], startDay: START, difficulty: 'relaxed' });
    const standard = createWorld({ name: 'S', seed: 'diff', worldSize: 'tiny', titles: ['lol'], startDay: START });
    expect(relaxed.meta.difficulty?.preset).toBe('relaxed');
    const org = Object.values(standard.orgs).find((o) => !o.federation && o.teams.length)!;
    setUserOrg(relaxed, org.id);
    setUserOrg(standard, org.id);
    const rev = (w: World) => Object.values(monthlyBreakdown(w, w.orgs[org.id]).revenue).reduce((a, b) => a + b, 0);
    expect(rev(relaxed)).toBeGreaterThan(rev(standard));
    expect(boardDelta(relaxed, -10)).toBeGreaterThan(boardDelta(standard, -10));
    expect(boardDelta(relaxed, 10)).toBe(10);
    // Injuries off: exhausted players never get hurt.
    const w = clone(standard);
    w.meta.difficulty = { ...PRESETS.standard, preset: 'custom', injuries: 0 };
    for (const p of Object.values(w.players)) p.fatigue = 95;
    for (let d = 0; d < 20; d++) {
      for (const p of Object.values(w.players)) p.fatigue = 95;
      dailyCondition(w);
      w.day++;
    }
    expect(Object.values(w.players).some((p) => p.injury)).toBe(false);
  });

  it('tougher bargaining raises the asking price you face', () => {
    const bid = (bargaining: number) => {
      const w = withUser(makeWorld(['lol'], 'diff-bargain'));
      w.meta.difficulty = { ...PRESETS.standard, preset: 'custom', bargaining };
      w.day = dayFromYMD(2026, 12, 1);
      const me = userMain(w);
      const other = Object.values(w.teams).find((t) => t.titleId === 'lol' && t.tier === 'main' && t.orgId !== me.orgId)!;
      const p = w.players[other.lineup[0].playerId];
      p.contract!.buyout = null;
      const { ask } = sellerAsk(w, p, other);
      return startTransferTalks(w, me.id, p.id, Math.round(ask * 1.08)).status;
    };
    expect(bid(0.9)).toBe('accepted');
    expect(bid(1.25)).not.toBe('accepted');
  });
});

describe('trades', () => {
  it('AI clubs send trade proposals to the user that can be accepted or rejected', () => {
    // Proposals need a mutually useful deal: try a few fixed worlds until one produces one.
    let w = tradeWorld('offers');
    for (const seed of ['offers', 'offers-2', 'offers-3', 'offers-4']) {
      w = tradeWorld(seed);
      advance(w, { kind: 'until', day: dayFromYMD(w.season, 7, 12) }, { stopForUserMatch: false });
      for (let i = 0; i < 12 && !(w.tradeOffers ?? []).some((o) => o.status === 'pending'); i++) {
        w.day = dayFromYMD(w.season, 7, 12) + i;
        aiOffersToUser(w);
      }
      if ((w.tradeOffers ?? []).some((o) => o.status === 'pending')) break;
    }
    const pending = (w.tradeOffers ?? []).filter((o) => o.status === 'pending');
    expect(pending.length).toBeGreaterThan(0);
    const o = pending[0];
    expect(w.inbox.some((m) => m.payload?.tradeOfferId === o.id)).toBe(true);
    const b = clone(w);
    // Accept: players change clubs.
    const r = respondToTradeOffer(w, o.id, true);
    expect(r.ok).toBe(true);
    for (const id of o.take) expect(w.players[id].teamId).toBe(o.fromTeamId);
    for (const id of o.give) expect(w.players[id].teamId).toBe(o.toTeamId);
    for (const id of o.givePicks) expect(w.draftPicks.find((d) => d.id === id)!.ownerTeamId).toBe(o.toTeamId);
    // Reject (on a copy): nothing moves.
    const r2 = respondToTradeOffer(b, o.id, false);
    expect(r2.ok).toBe(true);
    for (const id of o.take) expect(b.players[id].teamId).toBe(o.toTeamId);
    expect(b.tradeOffers!.find((x) => x.id === o.id)!.status).toBe('rejected');
  });

  it('a simulated season has 1-for-1, 2-for-1 and player-for-pick deals between AI clubs', () => {
    const w = makeWorld(['lol', 'valorant', 'cs2', 'mlbb'], 'trade-season', 'small');
    advance(w, { kind: 'until', day: seasonEndDay(w.season) - 1 }, { stopForUserMatch: false });
    const deals = w.news.filter((n) => n.title.startsWith('Trade:'));
    expect(deals.length).toBeGreaterThan(3);
    for (const t of w.transfers.filter((x) => x.type === 'trade')) expect(w.teams[t.fromTeamId!].titleId).toBe(w.teams[t.toTeamId!].titleId);
    const kinds = new Set(deals.map((n) => (n.title.includes('picks') ? 'picks' : n.title.includes('2-for-1') ? '2for1' : '1for1')));
    expect(kinds.size).toBeGreaterThanOrEqual(2);
  });
});

describe('youth intake & trials', () => {
  it('1 December brings an intake class; sign, release, deadline', () => {
    const w = withUser(makeWorld(['lol', 'cs2'], 'intake'));
    const before = Object.values(w.players).filter((p) => !p.retired).length;
    w.day = dayFromYMD(2026, 12, 1);
    youthDaily(w);
    const cls = w.youth!.classes;
    expect(cls.length).toBeGreaterThan(0);
    const c = cls[0];
    expect(c.prospects.length).toBeGreaterThanOrEqual(2);
    for (const id of c.prospects) {
      const p = w.players[id];
      expect(p.teamId).toBeNull();
      const age = (w.day - p.birth) / 365.25;
      expect(age).toBeGreaterThanOrEqual(15);
      expect(age).toBeLessThan(18.1);
      expect(w.scouting[id]).toBeGreaterThanOrEqual(45);
    }
    expect(w.inbox.some((m) => m.subject.startsWith('Youth intake'))).toBe(true);
    // Population grows only modestly.
    expect(Object.values(w.players).filter((p) => !p.retired).length - before).toBeLessThan(before * 0.1);
    // Running the same day again does nothing.
    youthDaily(w);
    expect(w.youth!.classes.length).toBe(cls.length);
    const team = w.teams[c.teamId];
    const lg = team.leagueId ? w.leagues[team.leagueId] : null;
    const old = c.prospects.map((id) => w.players[id]).find((p) => !lg || (w.day - p.birth) / 365.25 >= lg.policy.minAge);
    if (old) {
      while (team.roster.length >= 8) releasePlayer(w, team.roster[team.roster.length - 1]);
      expect(signProspect(w, old.id, team.id).ok).toBe(true);
      expect(old.teamId).toBe(team.id);
    }
    w.day = c.deadline;
    youthDaily(w);
    expect(c.closed).toBe(true);
    for (const id of c.prospects) if (id !== old?.id) expect(w.players[id].teamId).toBeNull();
  });

  it('a trial reserves a free agent, costs money and reveals him at the end', () => {
    const w = withUser(makeWorld(['lol'], 'trial'));
    const me = userMain(w);
    const fa = Object.values(w.players).find((p) => !p.teamId && !p.retired && p.titleId === 'lol')!;
    w.scouting[fa.id] = 0;
    const cash = w.orgs[me.orgId].balance;
    expect(startTrial(w, me.id, fa.id, 1).ok).toBe(true);
    expect(w.orgs[me.orgId].balance).toBeLessThan(cash);
    expect(w.scouting[fa.id]).toBeGreaterThanOrEqual(70);
    const other = Object.values(w.teams).find((t) => t.titleId === 'lol' && t.orgId !== me.orgId && t.tier === 'main')!;
    expect(offerFreeAgent(w, other.id, fa.id, 200000, 1).message).toMatch(/on trial/);
    for (let d = 0; d < 8; d++) {
      w.day++;
      youthDaily(w);
    }
    expect(fa.trial).toBeNull();
    expect(w.scouting[fa.id]).toBe(100);
    expect(w.inbox.some((m) => m.subject === `Trial complete: ${fa.handle}`)).toBe(true);
  });
});

describe('season preview, review and head-to-head', () => {
  it('a preview on 13 January, a review at the season end, and an all-time head-to-head', () => {
    const w = makeWorld(['lol', 'cs2'], 'preview');
    advance(w, { kind: 'until', day: dayFromYMD(w.season, 1, 14) }, { stopForUserMatch: false });
    const pv = w.previews?.[w.season];
    expect(pv).toBeDefined();
    for (const t of Object.values(pv!.titles)) {
      expect(t.worldsFavourites.length).toBeGreaterThan(0);
      for (const l of t.leagues) {
        const s = l.teams.map((x) => x.strength);
        expect([...s].sort((a, b) => b - a)).toEqual(s);
        expect(l.teams.map((x) => x.rank)).toEqual(l.teams.map((_, i) => i + 1));
      }
    }
    const season = w.season;
    advance(w, { kind: 'until', day: seasonEndDay(season) + 1 }, { stopForUserMatch: false });
    const rv = w.reviews?.[season];
    expect(rv).toBeDefined();
    expect(rv!.champions.length).toBeGreaterThan(0);
    expect(rv!.storylines.length).toBeGreaterThan(0);
    expect(rv!.predictions.length).toBeGreaterThan(0);
    for (const pr of rv!.predictions) {
      const n = pr.rows.length;
      expect(new Set(pr.rows.map((r) => r.actual)).size).toBe(n);
    }
    // Head-to-head ledger: consistent counts.
    const [key, rec] = Object.entries(w.h2h ?? {}).sort((a, b) => b[1][0] - a[1][0])[0];
    const [a, b] = key.split('|');
    const h = headToHead(w, a, b)!;
    expect(h.meetings).toBe(rec[0]);
    expect(h.wins[0] + h.wins[1]).toBe(h.meetings);
    const flip = headToHead(w, b, a)!;
    expect(flip.wins).toEqual([h.wins[1], h.wins[0]]);
  });
});

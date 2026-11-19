import { describe, expect, it } from 'vitest';
import { clone, makeWorld, withUser } from './helpers';
import { dayFromYMD } from '../src/core/date';
import { advance } from '../src/game/advance';
import { migrate } from '../src/persistence/migrate';
import { SAVE_VERSION } from '../src/persistence/version';
import {
  acceptClubCounter,
  CLUB_PATIENCE,
  offerTerms,
  sellerAsk,
  startPreContractTalks,
  startTransferTalks,
  withdrawNegotiation,
} from '../src/systems/negotiation';
import { counterOffer, decideOfferSheet, movePlayer, offerExtension, setTransferListing, shopPlayer, signPreContract } from '../src/systems/transfers';
import { expectedSalary } from '../src/systems/contracts';
import type { Player, Team, World } from '../src/model/types';

/** A user world in the November window with a rich user team and an AI target player. */
function setup(seed = 'nego', title = 'valorant') {
  const w = withUser(makeWorld([title], seed));
  advance(w, { kind: 'until', day: dayFromYMD(2026, 11, 21) });
  const mine = Object.values(w.teams).find((t) => t.orgId === w.meta.userOrgId && t.titleId === title && t.tier === 'main')!;
  w.orgs[mine.orgId].balance += 5e7;
  // Make room on the roster.
  while (mine.roster.length > 6) mine.roster.pop();
  for (const pid of mine.roster) w.players[pid].teamId = mine.id;
  const target = pickTarget(w, mine);
  return { w, mine, target };
}

function pickTarget(w: World, mine: Team): Player {
  const p = Object.values(w.players)
    .filter((x) => x.teamId && x.teamId !== mine.id && w.teams[x.teamId].orgId !== mine.orgId && w.teams[x.teamId].titleId === mine.titleId && !x.contract?.loan)
    .sort((a, b) => w.orgs[w.teams[a.teamId!].orgId].rep - w.orgs[w.teams[b.teamId!].orgId].rep || b.ca - a.ca)[0];
  p.joinedDay = w.day - 200;
  p.contract!.buyout = null;
  p.unhappy = 0;
  return p;
}

describe('transfer negotiations: club fee, then personal terms', () => {
  it('lowball → counter → accept counter → terms counter → agreed; money and records move', () => {
    const { w, mine, target } = setup();
    const seller = w.teams[target.teamId!];
    const sellerBal = w.orgs[seller.orgId].balance;
    const buyerBal = w.orgs[mine.orgId].balance;
    const { ask } = sellerAsk(w, target, seller);
    const low = startTransferTalks(w, mine.id, target.id, Math.round(ask * 0.3));
    expect(low.status).toBe('rejected');
    const mid = startTransferTalks(w, mine.id, target.id, Math.round(ask * 0.8));
    expect(mid.status).toBe('countered');
    expect(mid.counterFee).toBeGreaterThan(Math.round(ask * 0.8));
    const agreed = acceptClubCounter(w, mid.negId!);
    expect(agreed.ok).toBe(true);
    const n = w.negotiations.find((x) => x.id === mid.negId)!;
    expect(n.stage).toBe('terms');
    expect(n.playerCounter).toBeTruthy();
    // A stingy offer is countered with an honest demand; offering it completes the deal.
    const stingy = offerTerms(w, n.id, { salary: Math.round(n.playerCounter!.salary * 0.4), years: n.playerCounter!.years, signingBonus: 0, buyout: null });
    expect(stingy.status).toBe('countered');
    const done = offerTerms(w, n.id, { salary: stingy.counterSalary!, years: n.playerCounter!.years, signingBonus: 10000, buyout: 2_000_000 });
    expect(done.ok).toBe(true);
    expect(n.stage).toBe('completed');
    expect(target.teamId).toBe(mine.id);
    expect(mine.roster).toContain(target.id);
    expect(seller.roster).not.toContain(target.id);
    expect(target.contract!.salary).toBe(stingy.counterSalary);
    expect(target.contract!.signingBonus).toBe(10000);
    expect(target.contract!.buyout).toBe(2_000_000);
    expect(w.orgs[seller.orgId].balance - sellerBal).toBeGreaterThanOrEqual(n.fee - 1);
    expect(buyerBal - w.orgs[mine.orgId].balance).toBeGreaterThanOrEqual(n.fee + 10000 - 1);
    const rec = w.transfers[w.transfers.length - 1];
    expect(rec).toMatchObject({ playerId: target.id, toTeamId: mine.id, fromTeamId: seller.id, type: 'transfer', fee: n.fee });
  });

  it('clubs lose patience after repeated lowball bids and refuse new approaches for a while', () => {
    const { w, mine, target } = setup('patience');
    const { ask } = sellerAsk(w, target, w.teams[target.teamId!]);
    let r = startTransferTalks(w, mine.id, target.id, Math.round(ask * 0.2));
    for (let i = 1; i < CLUB_PATIENCE; i++) r = startTransferTalks(w, mine.id, target.id, Math.round(ask * 0.2));
    const n = w.negotiations.find((x) => x.id === r.negId)!;
    expect(n.stage).toBe('collapsed');
    expect(n.blockedUntil).toBeGreaterThan(w.day);
    const again = startTransferTalks(w, mine.id, target.id, ask * 3);
    expect(again.ok).toBe(false);
    expect(again.message).toMatch(/refuse to negotiate/);
  });

  it('a release clause cannot be refused; withdrawing ends talks', () => {
    const { w, mine, target } = setup('buyout');
    const { ask } = sellerAsk(w, target, w.teams[target.teamId!]);
    target.contract!.buyout = Math.round(ask * 0.5);
    const r = startTransferTalks(w, mine.id, target.id, target.contract!.buyout);
    expect(r.ok).toBe(true);
    expect(r.message).toMatch(/Release clause/);
    expect(withdrawNegotiation(w, r.negId!).ok).toBe(true);
    expect(w.negotiations.find((x) => x.id === r.negId)!.stage).toBe('withdrawn');
  });

  it('sell-on clauses pay the former club a share of the next fee', () => {
    const { w, mine, target } = setup('sellon');
    const seller = w.teams[target.teamId!];
    const { ask } = sellerAsk(w, target, seller);
    const r = startTransferTalks(w, mine.id, target.id, ask * 2, 0.2);
    expect(r.ok).toBe(true);
    const n = w.negotiations.find((x) => x.id === r.negId)!;
    const done = offerTerms(w, n.id, { salary: n.playerCounter!.salary, years: n.playerCounter!.years, signingBonus: 0, buyout: null });
    expect(done.ok).toBe(true);
    expect(target.sellOn).toMatchObject({ teamId: seller.id, pct: 0.2 });
    const third = Object.values(w.teams).find((t) => t.titleId === mine.titleId && t.id !== seller.id && t.orgId !== mine.orgId && t.active)!;
    const before = w.orgs[seller.orgId].balance;
    const mineBefore = w.orgs[mine.orgId].balance;
    movePlayer(w, target, third, 50000, 2, 'transfer', 400000);
    expect(w.orgs[seller.orgId].balance - before).toBe(80000);
    expect(w.orgs[mine.orgId].balance - mineBefore).toBe(400000 - 80000);
    expect(target.sellOn).toBeNull();
  });
});

describe('pre-contracts & offer sheets', () => {
  function lateSeason(seed: string) {
    const s = setup(seed, 'cs2');
    for (const l of Object.values(s.w.leagues)) l.policy.restrictedFA = false;
    advance(s.w, { kind: 'until', day: dayFromYMD(2027, 6, 15) });
    const p = pickTarget(s.w, s.mine);
    p.contract!.endSeason = s.w.season;
    p.contract!.option = 'none';
    p.birth = s.w.day - 27 * 365.25;
    return { ...s, target: p };
  }

  it('a player in the final six months agrees a free move that completes at the rollover', () => {
    const { w, mine, target } = lateSeason('precon');
    const from = w.teams[target.teamId!];
    const r = startPreContractTalks(w, mine.id, target.id);
    expect(r.ok).toBe(true);
    const n = w.negotiations.find((x) => x.id === r.negId)!;
    const done = offerTerms(w, n.id, { salary: n.playerCounter!.salary, years: 2, signingBonus: 0, buyout: null });
    expect(done.ok).toBe(true);
    expect(target.preContract).toMatchObject({ teamId: mine.id, offerSheet: false });
    expect(target.teamId).toBe(from.id); // still at the old club until the season ends
    expect(offerExtension(w, target.id, target.contract!.salary * 2, 2).ok).toBe(false);
    expect(startTransferTalks(w, from.id === mine.id ? '' : Object.values(w.teams).find((t) => t.titleId === mine.titleId && t.id !== mine.id && t.id !== from.id)!.id, target.id, 1e6).ok).toBe(false);
    const ended = w.season;
    advance(w, { kind: 'end-season' });
    expect(target.teamId).toBe(mine.id);
    expect(target.contract!.endSeason).toBe(ended + 2);
    expect(target.preContract).toBeNull();
    expect(w.transfers.some((x) => x.playerId === target.id && x.note === 'Pre-contract' && x.fee === 0)).toBe(true);
  });

  it('pre-contract talks are refused for players with longer contracts', () => {
    const { w, mine, target } = lateSeason('precon2');
    target.contract!.endSeason = w.season + 1;
    expect(startPreContractTalks(w, mine.id, target.id).ok).toBe(false);
  });

  it('restricted free agents sign offer sheets; the current club can match', () => {
    const { w, mine } = lateSeason('sheet');
    // A user player poached by an AI club via offer sheet; the user matches.
    const league = w.leagues[mine.leagueId!];
    league.policy.restrictedFA = true;
    const mp = w.players[mine.roster[0]];
    mp.contract!.endSeason = w.season;
    mp.contract!.option = 'none';
    mp.birth = w.day - 21 * 365.25;
    const rival = Object.values(w.teams).find((t) => t.titleId === mine.titleId && t.orgId !== mine.orgId && t.active)!;
    const sal = Math.round(expectedSalary(w, mp, rival.titleId, rival.region) * 1.3);
    const pc = signPreContract(w, mp, rival, { salary: sal, years: 2, signingBonus: 0 });
    expect(pc.offerSheet).toBe(true);
    expect(w.inbox.some((m) => m.payload?.matchPlayerId === mp.id)).toBe(true);
    expect(decideOfferSheet(w, mp.id, true).ok).toBe(true);
    const ended = w.season;
    advance(w, { kind: 'end-season' });
    expect(mp.teamId).toBe(mine.id);
    expect(mp.contract!.salary).toBe(sal);
    expect(mp.contract!.endSeason).toBe(ended + 2);
    expect(mp.career.some((c) => c.note === 'Offer sheet matched (restricted free agent)')).toBe(true);
  });
});

describe('selling: counters, listings, shopping a player', () => {
  it('counter-offers complete at the asking fee up to the bidder ceiling; one counter back; then they walk', () => {
    const { w, mine } = setup('counter');
    const buyer = Object.values(w.teams).find((t) => t.titleId === mine.titleId && t.orgId !== mine.orgId && t.active)!;
    w.orgs[buyer.orgId].balance += 1e7;
    const [a, b] = mine.roster.map((id) => w.players[id]);
    const mk = (p: Player, id: string) => w.offers.push({ id, day: w.day, playerId: p.id, fromTeamId: buyer.id, toTeamId: mine.id, fee: 100000, maxFee: 130000, salary: 40000, years: 2, status: 'pending', kind: 'transfer', user: true, expiresDay: w.day + 7 });
    mk(a, 'ofA');
    mk(b, 'ofB');
    const r1 = counterOffer(w, 'ofA', 120000);
    expect(r1.ok).toBe(true);
    expect(a.teamId).toBe(buyer.id);
    expect(w.transfers[w.transfers.length - 1].fee).toBe(120000);
    const r2 = counterOffer(w, 'ofB', 150000);
    expect(r2.status).toBe('countered');
    expect(r2.counterFee).toBe(130000);
    const r3 = counterOffer(w, 'ofB', 150000);
    expect(r3.status).toBe('rejected');
    expect(w.offers.find((o) => o.id === 'ofB')!.status).toBe('withdrawn');
    expect(b.teamId).toBe(mine.id);
  });

  it('asking prices anchor AI bids and shopping a player produces bids within their ceiling', () => {
    const { w, mine } = setup('shop');
    const p = w.players[mine.roster[0]];
    expect(setTransferListing(w, p.id, true, 250000).ok).toBe(true);
    expect(p.listed).toBe(true);
    expect(p.askingPrice).toBe(250000);
    const r = shopPlayer(w, p.id);
    expect(r.ok).toBe(true);
    const bids = w.offers.filter((o) => o.playerId === p.id && o.response === 'shopped');
    expect(bids.length).toBeLessThanOrEqual(3);
    for (const o of bids) {
      expect(o.fee).toBeGreaterThan(0);
      expect(o.fee).toBeLessThanOrEqual(250000);
      expect(o.maxFee!).toBeGreaterThanOrEqual(0);
    }
    expect(shopPlayer(w, p.id).ok).toBe(bids.length ? false : true);
    expect(setTransferListing(w, p.id, false).ok).toBe(true);
    expect(p.askingPrice).toBeNull();
  });

  it('negotiation outcomes are deterministic for the same world state', () => {
    const a = setup('det');
    const b = { w: clone(a.w) };
    const run = (w: World, mineId: string, pid: string) => {
      const ask = sellerAsk(w, w.players[pid], w.teams[w.players[pid].teamId!]).ask;
      const r = startTransferTalks(w, mineId, pid, Math.round(ask * 0.85));
      return `${r.status}:${r.counterFee ?? ''}`;
    };
    expect(run(a.w, a.mine.id, a.target.id)).toBe(run(b.w, a.mine.id, a.target.id));
  });

  it('migrates a v3 save by adding the negotiations list', () => {
    const w = makeWorld(['lol'], 'mig4');
    const raw = clone(w) as unknown as Record<string, any>;
    raw.meta.version = 3;
    delete raw.negotiations;
    const m = migrate(raw);
    expect(m.meta.version).toBe(SAVE_VERSION);
    expect(m.negotiations).toEqual([]);
  });
});

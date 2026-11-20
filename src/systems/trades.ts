/**
 * Trades between clubs (transfer windows, leagues that permit trades — franchised leagues by
 * default). Proposed rules (editable):
 *
 * Every candidate deal is judged by each club's best lineup value (sum of starters'
 * effectiveness, re-picked for every role) before and after, plus market value:
 *  - 1-for-1 swap: both lineups improve by ≥ 0.6. Values within 35% go straight; up to 70% the
 *    club receiving more value adds cash (80% of the gap, if it holds twice that) or, in the same
 *    league, a draft pick worth about the gap.
 *  - 2-for-1 consolidation: club A sends two depth players for one better player (A improves by
 *    ≥ 1.5); B's lineup must improve (≥ 0.3), it gets ≥ 110% of the value and needs roster room.
 *  - Veteran for picks: a rebuilding club (youth/moneyball strategy or bottom 40% by Elo) sends an
 *    established player (age ≥ 24) to a contender in the same league for draft picks worth
 *    ≥ 80% of his value (rebuilding clubs value picks ×1.3).
 * AI–AI: weekly, a club is considered with 25% chance, at most one deal per title per week and
 * three traded players per club per season (counted as six transfer records: in and out).
 * AI → user: weekly, each of the user's teams in such leagues gets at most one pending offer
 * (35% chance a week) when an AI club gains ≥ 1 and the user receives ≥ 97% of the value given;
 * offers expire after 7 days. When offers for your players are delegated, your general manager
 * accepts only deals that do not weaken your lineup and give fair value.
 */
import { RNG } from '../core/rng';
import { ageAt } from '../core/date';
import { getTitle } from '../data/titles/catalogue';
import type { DraftPickAsset, Player, Team, TradeOffer, World } from '../model/types';
import type { TitleDef } from '../data/titles/types';
import { nextId } from '../model/ids';
import { effectivenessLight } from '../sim/common/ratings';
import { marketValue, teamPayroll } from './contracts';
import { pickLabel, pickValue } from './draft';
import { ledger } from './finance';
import { addInbox, addNews } from './news';
import { userHandles } from './career';
import { isAvailable } from './availability';
import { difficulty } from './difficulty';
import { inMidSeasonWindow, inTransferWindow, movePlayer, rosterMax } from './transfers';

export interface Proposal {
  a: Team;
  b: Team;
  /** What A sends / receives. */
  aGives: Player[];
  bGives: Player[];
  aPicks: DraftPickAsset[];
  bPicks: DraftPickAsset[];
  /** Cash A pays B (negative: B pays A). */
  cash: number;
  gA: number;
  gB: number;
  kind: '1-for-1' | '2-for-1' | 'player-for-picks';
  score: number;
}

class TitleEval {
  private cache = new Map<string, number>();
  constructor(readonly title: TitleDef) {}
  eff(p: Player, roleId: string): number {
    const k = `${p.id}|${roleId}`;
    let v = this.cache.get(k);
    if (v === undefined) {
      v = effectivenessLight(p, this.title, this.title.roles.find((r) => r.id === roleId)!);
      this.cache.set(k, v);
    }
    return v;
  }
  /** Greedy best lineup: sum of the best (player, role) pairs, and each role's assigned value. */
  assign(ps: Player[]): { sum: number; roleE: number[] } {
    const roles = this.title.lineup;
    const pairs: Array<[number, number, number]> = [];
    ps.forEach((p, i) => roles.forEach((r, j) => pairs.push([this.eff(p, r), i, j])));
    pairs.sort((x, y) => y[0] - x[0]);
    const up = new Set<number>();
    const roleE = new Array(roles.length).fill(0);
    let filled = 0;
    let sum = 0;
    for (const [e, i, j] of pairs) {
      if (up.has(i) || roleE[j]) continue;
      up.add(i);
      roleE[j] = e;
      sum += e;
      if (++filled === roles.length) break;
    }
    return { sum, roleE };
  }
  lineupValue(ps: Player[]): number {
    return this.assign(ps).sum;
  }
  /** Upper bound of what adding `p` can add to a lineup with these role values. */
  upside(p: Player, roleE: number[]): number {
    let best = -Infinity;
    this.title.lineup.forEach((r, j) => (best = Math.max(best, this.eff(p, r) - roleE[j])));
    return best;
  }
}

function tradeable(world: World, p: Player | undefined): p is Player {
  return !!p && !p.retired && !!p.contract && !p.contract.loan && !p.preContract && world.day - p.joinedDay > 30 && isAvailable(p, world.day);
}

function eloRankShare(world: World, team: Team): number {
  const lg = team.leagueId ? world.leagues[team.leagueId] : null;
  if (!lg) return 0.5;
  const sorted = lg.teamIds.map((id) => world.teams[id]).filter(Boolean).sort((x, y) => y.elo - x.elo);
  return sorted.findIndex((t) => t.id === team.id) / Math.max(1, sorted.length - 1);
}

export function rebuilding(world: World, team: Team): boolean {
  const s = world.orgs[team.orgId]?.strategy;
  return s === 'youth' || s === 'moneyball' || eloRankShare(world, team) >= 0.6;
}

export function contender(world: World, team: Team): boolean {
  const s = world.orgs[team.orgId]?.strategy;
  return s === 'win-now' || s === 'prestige' || eloRankShare(world, team) <= 0.4;
}

function picksOf(world: World, team: Team): DraftPickAsset[] {
  return (world.draftPicks ?? []).filter((d) => !d.used && d.ownerTeamId === team.id && d.leagueId === team.leagueId);
}

function squad(world: World, team: Team): Player[] {
  return team.roster.map((id) => world.players[id]).filter((p): p is Player => isAvailable(p, world.day));
}

export interface SearchOpts {
  /** Minimum lineup gain for A (the proposer). */
  aMin: number;
  /** Minimum lineup gain for B in swaps. */
  bMin: number;
  /** B is the user: A must hand over ≥ this share of the value it takes. */
  fairToB?: number;
}

/** The best deal A can propose to B under the rules above (or null). */
export function bestProposal(world: World, ev: TitleEval, A: Team, B: Team, o: SearchOpts): Proposal | null {
  if (A.id === B.id || A.orgId === B.orgId) return null;
  const sqA = squad(world, A);
  const sqB = squad(world, B);
  const asA = ev.assign(sqA);
  const asB = ev.assign(sqB);
  const baseA = asA.sum;
  const baseB = asB.sum;
  const tA = sqA.filter((p) => tradeable(world, p));
  const tB = sqB.filter((p) => tradeable(world, p));
  // Cheap pruning: a player who cannot lift the other lineup by enough is never worth a look.
  const upA = new Map(tB.map((y) => [y.id, ev.upside(y, asA.roleE)]));
  const upB = new Map(tA.map((x) => [x.id, ev.upside(x, asB.roleE)]));
  const sameLeague = !!A.leagueId && A.leagueId === B.leagueId;
  const balA = world.orgs[A.orgId]?.balance ?? 0;
  const balB = world.orgs[B.orgId]?.balance ?? 0;
  const value = (p: Player) => marketValue(world, p);
  const size = getTitle(A.titleId).teamSize;
  let best: Proposal | null = null;
  const consider = (p: Proposal) => {
    if (!best || p.score > best.score) best = p;
  };
  // 1-for-1 swaps.
  for (const x of tA) {
    if (upB.get(x.id)! < o.bMin) continue;
    const vx = value(x);
    const restA = sqA.filter((p) => p !== x);
    for (const y of tB) {
      if (upA.get(y.id)! < o.aMin) continue;
      const vy = value(y);
      const gap = vy - vx; // > 0: A receives more value
      if (Math.abs(gap) > 0.7 * Math.max(vx, vy)) continue;
      const gA = ev.lineupValue([...restA, y]) - baseA;
      if (gA < o.aMin) continue;
      const gB = ev.lineupValue([...sqB.filter((p) => p !== y), x]) - baseB;
      if (gB < o.bMin) continue;
      let cash = 0;
      let aPicks: DraftPickAsset[] = [];
      let bPicks: DraftPickAsset[] = [];
      if (Math.abs(gap) > 0.35 * Math.max(vx, vy)) {
        const amount = Math.round((Math.abs(gap) * 0.8) / 1000) * 1000;
        const payer = gap > 0 ? A : B;
        const bal = gap > 0 ? balA : balB;
        if (bal >= amount * 2) cash = gap > 0 ? amount : -amount;
        else if (sameLeague) {
          const pick = picksOf(world, payer)
            .map((d) => ({ d, v: pickValue(world, d) }))
            .filter((x) => x.v >= Math.abs(gap) * 0.6 && x.v <= Math.abs(gap) * 1.6)
            .sort((p1, p2) => Math.abs(p1.v - Math.abs(gap)) - Math.abs(p2.v - Math.abs(gap)))[0];
          if (!pick) continue;
          if (gap > 0) aPicks = [pick.d];
          else bPicks = [pick.d];
        } else continue;
      }
      const aOut = vx + (gap > 0 ? Math.abs(cash) : 0) + aPicks.reduce((s, d) => s + pickValue(world, d), 0);
      const aIn = vy + (gap < 0 ? Math.abs(cash) : 0) + bPicks.reduce((s, d) => s + pickValue(world, d), 0);
      if (o.fairToB && aOut < aIn * o.fairToB) continue;
      consider({ a: A, b: B, aGives: [x], bGives: [y], aPicks, bPicks, cash, gA, gB, kind: '1-for-1', score: Math.min(gA, gB) + 0.1 * (gA + gB) - (cash || aPicks.length || bPicks.length ? 0.2 : 0) });
    }
  }
  // 2-for-1: A consolidates two depth players into one better player.
  if (sqA.length - 1 >= size && B.roster.length + 1 <= rosterMax(world, B)) {
    const depth = tA.filter((p) => !A.lineup.some((s) => s.playerId === p.id) || tA.length > size + 1);
    for (const y of tB) {
      if (upA.get(y.id)! < Math.max(1.5, o.aMin)) continue;
      const vy = value(y);
      for (let i = 0; i < depth.length; i++)
        for (let j = i + 1; j < depth.length; j++) {
          const [x1, x2] = [depth[i], depth[j]];
          const vIn = value(x1) + value(x2);
          if (vIn < vy * 1.1 || vIn > vy * 1.8) continue;
          const gA = ev.lineupValue([...sqA.filter((p) => p !== x1 && p !== x2), y]) - baseA;
          if (gA < Math.max(1.5, o.aMin)) continue;
          const gB = ev.lineupValue([...sqB.filter((p) => p !== y), x1, x2]) - baseB;
          if (gB < 0.3) continue;
          if (o.fairToB && vIn < vy * o.fairToB) continue;
          consider({ a: A, b: B, aGives: [x1, x2], bGives: [y], aPicks: [], bPicks: [], cash: 0, gA, gB, kind: '2-for-1', score: gA * 0.8 + Math.max(0, gB) + 0.1 });
        }
    }
  }
  // Veteran for picks: rebuilding B sends an established player to contender A.
  if (sameLeague && rebuilding(world, B) && contender(world, A)) {
    const picks = picksOf(world, A).map((d) => ({ d, v: pickValue(world, d) })).sort((p1, p2) => p2.v - p1.v);
    for (const y of tB) {
      if (ageAt(y.birth, world.day) < 24 || upA.get(y.id)! < Math.max(1.2, o.aMin)) continue;
      const gA = ev.lineupValue([...sqA, y]) - baseA;
      if (gA < Math.max(1.2, o.aMin) || A.roster.length + 1 > rosterMax(world, A)) continue;
      if (B.roster.length - 1 < size) continue;
      const vy = value(y);
      const chosen: DraftPickAsset[] = [];
      let worth = 0;
      for (const { d, v } of picks) {
        if (worth >= vy * 0.8 / 1.3) break;
        chosen.push(d);
        worth += v;
      }
      if (worth * 1.3 < vy * 0.8 || chosen.length > 2) continue;
      if (o.fairToB && worth < vy * o.fairToB) continue;
      const gB = ev.lineupValue(sqB.filter((p) => p !== y)) - baseB;
      consider({ a: A, b: B, aGives: [], bGives: [y], aPicks: chosen, bPicks: [], cash: 0, gA, gB, kind: 'player-for-picks', score: gA * 0.7 + 0.3 });
    }
  }
  return best;
}

function yearsLeft(world: World, p: Player): number {
  return Math.max(1, (p.contract?.endSeason ?? world.season) - world.season + 1);
}

/** Carry out a deal: players keep salary and contract length (no signing bonus); picks and cash move. */
export function executeTrade(world: World, prop: Pick<Proposal, 'a' | 'b' | 'aGives' | 'bGives' | 'aPicks' | 'bPicks' | 'cash'>): void {
  const { a, b, aGives, bGives, aPicks, bPicks, cash } = prop;
  if (cash) {
    const [from, to] = cash > 0 ? [a, b] : [b, a];
    ledger(world, world.orgs[from.orgId], 'trade-cash', -Math.abs(cash), from.id, `Trade with ${to.short}`);
    ledger(world, world.orgs[to.orgId], 'trade-cash', Math.abs(cash), to.id, `Trade with ${from.short}`);
  }
  for (const d of aPicks) d.ownerTeamId = b.id;
  for (const d of bPicks) d.ownerTeamId = a.id;
  const names = (ps: Player[]) => ps.map((p) => p.handle).join(' & ');
  const sx = aGives.map((p) => ({ p, s: p.contract!.salary, y: yearsLeft(world, p) }));
  const sy = bGives.map((p) => ({ p, s: p.contract!.salary, y: yearsLeft(world, p) }));
  for (const { p, s, y } of sx) movePlayer(world, p, b, s, y, 'trade', 0, undefined, { note: `Trade with ${a.short}${bGives.length ? ` for ${names(bGives)}` : ''}`, signingBonus: 0 });
  for (const { p, s, y } of sy) movePlayer(world, p, a, s, y, 'trade', 0, undefined, { note: `Trade with ${b.short}${aGives.length ? ` for ${names(aGives)}` : ''}`, signingBonus: 0 });
}

export function describeTrade(world: World, prop: Pick<Proposal, 'a' | 'b' | 'aGives' | 'bGives' | 'aPicks' | 'bPicks' | 'cash'>): string {
  const side = (ps: Player[], picks: DraftPickAsset[], cash: number) =>
    [...ps.map((p) => `${p.handle} (${Math.round(p.ca)} CA)`), ...picks.map((d) => pickLabel(world, d)), ...(cash > 0 ? [`$${Math.round(cash / 1000)}K`] : [])].join(', ') || 'nothing';
  return `${prop.a.name} send ${side(prop.aGives, prop.aPicks, prop.cash)}; ${prop.b.name} send ${side(prop.bGives, prop.bPicks, -prop.cash)}.`;
}

function eligibleTeams(world: World): Map<string, Team[]> {
  const byTitle = new Map<string, Team[]>();
  for (const t of Object.values(world.teams)) {
    if (!t.active || t.national || t.tier !== 'main' || !t.leagueId) continue;
    if (!world.leagues[t.leagueId]?.policy.trades) continue;
    const org = world.orgs[t.orgId];
    if (!org || org.federation) continue;
    if (!byTitle.has(t.titleId)) byTitle.set(t.titleId, []);
    byTitle.get(t.titleId)!.push(t);
  }
  return byTitle;
}

/** Weekly in windows: AI clubs trade among themselves. Returns the number of deals. */
export function aiTradesWeek(world: World): number {
  if (!inTransferWindow(world)) return 0;
  const rng = new RNG(`${world.meta.seed}|aitrade|${world.day}`);
  let total = 0;
  // Each club moves at most three players by trade per season.
  const moved = new Map<string, number>();
  for (const t of world.transfers) {
    if (t.type !== 'trade' || t.season !== world.season) continue;
    for (const id of [t.fromTeamId, t.toTeamId]) if (id) moved.set(id, (moved.get(id) ?? 0) + 1);
  }
  const capped = (t: Team) => (moved.get(t.id) ?? 0) >= 6;
  for (const [titleId, all] of eligibleTeams(world)) {
    const teams = all.filter((t) => !(world.orgs[t.orgId]?.user && userHandles(world, 'signings')) && !capped(t));
    if (teams.length < 2) continue;
    const ev = new TitleEval(getTitle(titleId));
    let deals = 0;
    const done = new Set<string>();
    for (const A of rng.shuffle([...teams])) {
      if (deals >= 1) break;
      if (done.has(A.id) || !rng.chance(0.25)) continue;
      let best: Proposal | null = null;
      for (const B of teams) {
        if (done.has(B.id) || world.orgs[B.orgId]?.user) continue;
        const p = bestProposal(world, ev, A, B, { aMin: 0.6, bMin: 0.6 });
        if (p && (!best || p.score > best.score)) best = p;
      }
      if (!best) continue;
      const text = describeTrade(world, best);
      executeTrade(world, best);
      const star = [...best.aGives, ...best.bGives].some((p) => p.rep > 65);
      addNews(world, {
        kind: 'transfer',
        title: `Trade: ${best.a.name} and ${best.b.name}${best.kind === 'player-for-picks' ? ' (player for picks)' : best.kind === '2-for-1' ? ' (2-for-1)' : ''}`,
        body: `${text}${inMidSeasonWindow(world) ? ' A mid-season move.' : ''}`,
        titleId,
        refs: [...[...best.aGives, ...best.bGives].map((p) => ({ type: 'player' as const, id: p.id })), { type: 'team', id: best.a.id }, { type: 'team', id: best.b.id }],
        importance: star ? 3 : 2,
      });
      done.add(best.a.id);
      done.add(best.b.id);
      deals++;
      total++;
    }
  }
  return total;
}

/** Weekly in windows: AI clubs propose trades to the user's teams. */
export function aiOffersToUser(world: World): number {
  const uid = world.meta.userOrgId;
  for (const o of world.tradeOffers ?? []) if (o.status === 'pending' && o.expiresDay < world.day) o.status = 'expired';
  if (world.tradeOffers && world.tradeOffers.length > 60) world.tradeOffers = world.tradeOffers.filter((o) => o.status === 'pending' || o.day > world.day - 120);
  if (!uid || !inTransferWindow(world)) return 0;
  const rng = new RNG(`${world.meta.seed}|tradeoffer|${world.day}`);
  const bargaining = difficulty(world).bargaining;
  let made = 0;
  for (const [titleId, teams] of eligibleTeams(world)) {
    const mine = teams.filter((t) => t.orgId === uid);
    if (!mine.length) continue;
    const ev = new TitleEval(getTitle(titleId));
    for (const U of mine) {
      if ((world.tradeOffers ?? []).some((o) => o.status === 'pending' && o.toTeamId === U.id) || !rng.chance(0.35)) continue;
      let best: Proposal | null = null;
      for (const A of teams) {
        if (A.orgId === uid) continue;
        // Tougher bargaining → the AI offers a little less of the value it takes.
        const p = bestProposal(world, ev, A, U, { aMin: 1, bMin: -1, fairToB: 0.97 / bargaining });
        if (p && (!best || p.gA > best.gA)) best = p;
      }
      if (!best) continue;
      const offer: TradeOffer = {
        id: nextId(world, 'tr'),
        day: world.day,
        expiresDay: world.day + 7,
        fromTeamId: best.a.id,
        toTeamId: U.id,
        give: best.aGives.map((p) => p.id),
        givePicks: best.aPicks.map((d) => d.id),
        take: best.bGives.map((p) => p.id),
        takePicks: best.bPicks.map((d) => d.id),
        cash: best.cash,
        kind: best.kind,
        status: 'pending',
      };
      (world.tradeOffers ??= []).push(offer);
      made++;
      const text = describeTrade(world, best);
      if (!userHandles(world, 'sales')) {
        // Delegated: the general manager decides.
        const ok = best.gB >= 0;
        const r = respondToTradeOffer(world, offer.id, ok);
        addInbox(world, { from: 'General manager', subject: `${ok && r.ok ? 'Accepted' : 'Declined'} a trade with ${best.a.name}`, body: `${text}\n${ok ? (r.ok ? 'It strengthens our lineup at fair value.' : r.message) : 'It would weaken our lineup.'}`, kind: 'transfer' });
        continue;
      }
      addInbox(world, {
        from: best.a.name,
        subject: `Trade proposal from ${best.a.name}`,
        body: `${text}\nThe offer stands for 7 days. Your best lineup would change by ${best.gB >= 0 ? '+' : ''}${best.gB.toFixed(1)} (best-lineup effectiveness).`,
        kind: 'transfer',
        actions: [
          { id: 'accept', label: 'Accept trade' },
          { id: 'reject', label: 'Reject' },
        ],
        payload: { tradeOfferId: offer.id },
      });
    }
  }
  return made;
}

export interface TradeResult {
  ok: boolean;
  message: string;
}

/** The user's answer to an AI trade proposal. */
export function respondToTradeOffer(world: World, offerId: string, accept: boolean): TradeResult {
  const o = (world.tradeOffers ?? []).find((x) => x.id === offerId);
  if (!o || o.status !== 'pending') return { ok: false, message: 'This proposal is no longer open.' };
  if (!accept) {
    o.status = 'rejected';
    return { ok: true, message: 'Trade proposal rejected.' };
  }
  const a = world.teams[o.fromTeamId];
  const b = world.teams[o.toTeamId];
  const aGives = o.give.map((id) => world.players[id]);
  const bGives = o.take.map((id) => world.players[id]);
  const picks = world.draftPicks ?? [];
  const aPicks = o.givePicks.map((id) => picks.find((d) => d.id === id));
  const bPicks = o.takePicks.map((id) => picks.find((d) => d.id === id));
  const stale =
    !a || !b || !inTransferWindow(world) ||
    aGives.some((p) => !p || p.teamId !== a.id) || bGives.some((p) => !p || p.teamId !== b.id) ||
    aPicks.some((d) => !d || d.used || d.ownerTeamId !== a.id) || bPicks.some((d) => !d || d.used || d.ownerTeamId !== b.id);
  if (stale) {
    o.status = 'withdrawn';
    return { ok: false, message: 'The situation changed (players, picks or the window) — the proposal is off.' };
  }
  if (b.roster.length - bGives.length + aGives.length > rosterMax(world, b) || a.roster.length - aGives.length + bGives.length > rosterMax(world, a)) return { ok: false, message: 'The trade would break a roster limit.' };
  const pay = (ps: Player[]) => ps.reduce((s, p) => s + (p.contract?.salary ?? 0), 0);
  if (world.orgs[b.orgId]?.user && pay(aGives) > pay(bGives) && teamPayroll(world, b) - pay(bGives) + pay(aGives) > b.salaryBudget) return { ok: false, message: `The trade would put ${b.name} over its wage budget. Raise or move budget in Finances → Treasury.` };
  if (o.cash < 0 && (world.orgs[b.orgId]?.balance ?? 0) < -o.cash) return { ok: false, message: 'Not enough cash for your part of the deal.' };
  executeTrade(world, { a, b, aGives: aGives as Player[], bGives: bGives as Player[], aPicks: aPicks as DraftPickAsset[], bPicks: bPicks as DraftPickAsset[], cash: o.cash });
  o.status = 'accepted';
  addNews(world, { kind: 'transfer', title: `Trade: ${a.name} and ${b.name}`, body: describeTrade(world, { a, b, aGives: aGives as Player[], bGives: bGives as Player[], aPicks: aPicks as DraftPickAsset[], bPicks: bPicks as DraftPickAsset[], cash: o.cash }), titleId: a.titleId, refs: [{ type: 'team', id: a.id }, { type: 'team', id: b.id }], importance: 2 });
  return { ok: true, message: `Trade with ${a.name} completed.` };
}

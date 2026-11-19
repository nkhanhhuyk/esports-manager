/**
 * Multi-step transfer negotiations (football-manager style):
 *
 *   1. Club stage — the buyer bids a fee (+ optional sell-on clause). The selling club accepts,
 *      counters with an asking fee, or rejects; patience runs out after CLUB_PATIENCE failed bids
 *      and the club refuses new approaches for BLOCK_DAYS. A release (buyout) clause cannot be
 *      refused.
 *   2. Terms stage — with the fee agreed (valid AGREED_DAYS), the buyer offers personal terms
 *      (salary, seasons, signing bonus, buyout clause). The player accepts or names their price;
 *      their counters are honest (offering the counter is accepted). After TERMS_PATIENCE failed
 *      offers the player turns the move down.
 *   3. Completion — fee and signing bonus are paid, the sell-on clause is attached to the player.
 *
 * Pre-contracts skip the club stage: players in the final six months of a contract can agree a
 * free move that completes at the season rollover (restricted free agents sign offer sheets the
 * current club may match). All decisions are deterministic per negotiation (seeded).
 */
import { clamp } from '../core/math';
import { ageAt, formatDate } from '../core/date';
import { RNG } from '../core/rng';
import { nextId } from '../model/ids';
import type { Negotiation, NegotiationTerms, Player, Team, World } from '../model/types';
import { expectedSalary, offerAppeal, validateContract } from './contracts';
import { addInbox } from './news';
import {
  checkPolicy,
  clubValuation,
  inTransferWindow,
  movePlayer,
  preContractBlock,
  rosterMax,
  signPreContract,
  type OfferResponse,
} from './transfers';

export const CLUB_PATIENCE = 3;
export const TERMS_PATIENCE = 4;
export const AGREED_DAYS = 14;
export const BLOCK_DAYS = 30;
export const MAX_SELL_ON = 0.3;

export type NegotiationResponse = OfferResponse & { negId?: string };

const ACTIVE: Negotiation['stage'][] = ['club', 'terms'];
const round1k = (x: number) => Math.round(x / 1000) * 1000;
const round500 = (x: number) => Math.ceil(x / 500) * 500;

export function isActive(n: Negotiation): boolean {
  return ACTIVE.includes(n.stage);
}

export function activeNegotiation(world: World, buyerTeamId: string, playerId: string): Negotiation | undefined {
  return (world.negotiations ?? []).find((n) => n.playerId === playerId && n.buyerTeamId === buyerTeamId && isActive(n));
}

/** Negotiations involving an organisation's teams (as buyer), newest first. */
export function negotiationsForOrg(world: World, orgId: string): Negotiation[] {
  return (world.negotiations ?? []).filter((n) => world.teams[n.buyerTeamId]?.orgId === orgId).sort((a, b) => b.day - a.day);
}

function note(world: World, n: Negotiation, text: string): void {
  n.log.push({ day: world.day, text });
}

function fmt(x: number): string {
  return `$${Math.round(x).toLocaleString()}`;
}

// ---------------------------------------------------------------------------------------------
// Club stage
// ---------------------------------------------------------------------------------------------

/** What the selling club holds out for, before sell-on credit. Key players of ambitious clubs cost more. */
export function sellerAsk(world: World, p: Player, owner: Team): { ask: number; buyout: number | null; key: boolean } {
  const org = world.orgs[owner.orgId];
  let ask = clubValuation(world, p, owner);
  const starters = owner.lineup.map((s) => (s.playerId ? world.players[s.playerId] : null)).filter(Boolean) as Player[];
  const rank = starters.filter((x) => x.ca > p.ca).length;
  const key = starters.includes(p) && rank < 2 && (org?.strategy === 'win-now' || org?.strategy === 'prestige');
  if (key) ask *= 1.4;
  return { ask: round1k(ask), buyout: p.contract?.buyout ?? null, key };
}

/** The selling club's value of a sell-on clause: an expected share of a future sale. */
export function sellOnCredit(world: World, p: Player, fee: number, pct: number): number {
  const age = ageAt(p.birth, world.day);
  return fee * pct * (age <= 22 ? 0.6 : age <= 25 ? 0.4 : 0.2);
}

function blockedUntil(world: World, buyerOrgId: string, playerId: string): number {
  let until = 0;
  for (const n of world.negotiations ?? []) {
    if (n.playerId !== playerId || !n.blockedUntil || world.teams[n.buyerTeamId]?.orgId !== buyerOrgId) continue;
    until = Math.max(until, n.blockedUntil);
  }
  return until;
}

/** Open (or continue) talks with a club over a fee. */
export function startTransferTalks(world: World, buyerTeamId: string, playerId: string, fee: number, sellOnPct = 0): NegotiationResponse {
  world.negotiations ??= [];
  const team = world.teams[buyerTeamId];
  const p = world.players[playerId];
  if (!team || !p || p.retired || !p.teamId) return { ok: false, status: 'invalid', message: 'Player is not under contract.' };
  const owner = world.teams[p.teamId];
  if (!owner || owner.orgId === team.orgId) return { ok: false, status: 'invalid', message: 'Use squad moves for players in your own organisation.' };
  const existing = activeNegotiation(world, buyerTeamId, playerId);
  if (existing?.stage === 'club') return reviseBid(world, existing.id, fee, sellOnPct);
  if (existing?.stage === 'terms') return { ok: false, status: 'invalid', message: 'The fee is already agreed — negotiate personal terms.', negId: existing.id };
  if (p.contract?.loan) return { ok: false, status: 'invalid', message: `${p.handle} is on loan at ${owner.name}.` };
  if (p.preContract) return { ok: false, status: 'invalid', message: `${p.handle} has agreed to join ${world.teams[p.preContract.teamId]?.name ?? 'another club'} at the end of the season.` };
  if (!inTransferWindow(world) && !p.listed) return { ok: false, status: 'invalid', message: 'The transfer window is closed (listed players and free agents are still available).' };
  if (world.day - p.joinedDay < 30) return { ok: false, status: 'rejected', message: `${p.handle} only just joined ${owner.name}.` };
  const blocked = blockedUntil(world, team.orgId, p.id);
  if (blocked > world.day) return { ok: false, status: 'rejected', message: `${owner.name} refuse to negotiate again before ${formatDate(blocked)}.` };
  const lg = team.leagueId ? world.leagues[team.leagueId] : null;
  if (lg && !lg.policy.transferFees) return { ok: false, status: 'invalid', message: 'Your league does not permit paid transfers.' };
  if (team.roster.length >= rosterMax(world, team)) return { ok: false, status: 'invalid', message: 'Roster is full (league roster limit).' };
  if (!(fee > 0)) return { ok: false, status: 'invalid', message: 'Enter a fee.' };
  if ((world.orgs[team.orgId]?.balance ?? 0) < fee) return { ok: false, status: 'invalid', message: 'Insufficient funds for this fee.' };
  const n: Negotiation = {
    id: nextId(world, 'ng'),
    day: world.day,
    kind: 'transfer',
    playerId: p.id,
    buyerTeamId: team.id,
    sellerTeamId: owner.id,
    stage: 'club',
    fee: Math.round(fee),
    sellOnPct: clamp(sellOnPct, 0, MAX_SELL_ON),
    clubCounter: null,
    clubRounds: 0,
    terms: null,
    playerCounter: null,
    termsRounds: 0,
    expiresDay: world.day + 14,
    user: !!world.orgs[team.orgId]?.user,
    log: [],
  };
  world.negotiations.push(n);
  return clubRespond(world, n);
}

/** Improve (or restate) the bid in an open club-stage negotiation. */
export function reviseBid(world: World, negId: string, fee: number, sellOnPct?: number): NegotiationResponse {
  const n = (world.negotiations ?? []).find((x) => x.id === negId);
  if (!n || n.stage !== 'club') return { ok: false, status: 'invalid', message: 'No open bid to revise.' };
  if (!(fee > 0)) return { ok: false, status: 'invalid', message: 'Enter a fee.', negId };
  const team = world.teams[n.buyerTeamId];
  if ((world.orgs[team.orgId]?.balance ?? 0) < fee) return { ok: false, status: 'invalid', message: 'Insufficient funds for this fee.', negId };
  n.fee = Math.round(fee);
  if (sellOnPct !== undefined) n.sellOnPct = clamp(sellOnPct, 0, MAX_SELL_ON);
  n.expiresDay = Math.max(n.expiresDay, world.day + 7);
  return clubRespond(world, n);
}

/** Accept the selling club's counter-demand. */
export function acceptClubCounter(world: World, negId: string): NegotiationResponse {
  const n = (world.negotiations ?? []).find((x) => x.id === negId);
  if (!n || n.stage !== 'club' || n.clubCounter === null) return { ok: false, status: 'invalid', message: 'There is no counter-offer to accept.' };
  const team = world.teams[n.buyerTeamId];
  if ((world.orgs[team.orgId]?.balance ?? 0) < n.clubCounter) return { ok: false, status: 'invalid', message: 'Insufficient funds for this fee.', negId };
  n.fee = n.clubCounter;
  note(world, n, `Accepted the asking price of ${fmt(n.fee)}`);
  return clubAccept(world, n, 'agree');
}

function clubRespond(world: World, n: Negotiation): NegotiationResponse {
  const p = world.players[n.playerId];
  const owner = n.sellerTeamId ? world.teams[n.sellerTeamId] : null;
  if (!p || !owner || p.teamId !== owner.id) return collapse(world, n, 'The player is no longer at the club.', false);
  const rng = RNG.derive(world.meta.seed, 'neg', n.id, n.clubRounds);
  const { ask, buyout, key } = sellerAsk(world, p, owner);
  note(world, n, `Bid ${fmt(n.fee)}${n.sellOnPct ? ` + ${Math.round(n.sellOnPct * 100)}% sell-on` : ''}`);
  if (buyout !== null && n.fee >= buyout) return clubAccept(world, n, 'buyout');
  const value = n.fee + sellOnCredit(world, p, n.fee, n.sellOnPct);
  if (value >= ask * (1 + rng.normal(0, 0.03))) return clubAccept(world, n, 'agree');
  n.clubRounds++;
  if (n.clubRounds >= CLUB_PATIENCE) {
    n.blockedUntil = world.day + BLOCK_DAYS;
    return collapse(world, n, `${owner.name} have lost patience and broken off talks.`, true);
  }
  if (value >= ask * 0.6) {
    const counter = round1k(Math.max(ask * (1.06 - 0.02 * (n.clubRounds - 1)) - sellOnCredit(world, p, ask, n.sellOnPct), n.fee + 1000));
    n.clubCounter = counter;
    note(world, n, `${owner.name} counter: ${fmt(counter)}`);
    return { ok: false, status: 'countered', message: `${owner.name} want ${fmt(counter)}${key ? ` — ${p.handle} is a key player` : ''}. ${CLUB_PATIENCE - n.clubRounds} more bid(s) before they lose patience.`, counterFee: counter, negId: n.id };
  }
  note(world, n, `${owner.name} reject the bid`);
  return { ok: false, status: 'rejected', message: `${owner.name} reject the bid out of hand — they value ${p.handle} far higher. ${CLUB_PATIENCE - n.clubRounds} more bid(s) before they lose patience.`, negId: n.id };
}

function clubAccept(world: World, n: Negotiation, how: 'agree' | 'buyout'): NegotiationResponse {
  const p = world.players[n.playerId];
  const owner = world.teams[n.sellerTeamId!];
  const buyer = world.teams[n.buyerTeamId];
  n.stage = 'terms';
  n.clubCounter = null;
  n.expiresDay = world.day + AGREED_DAYS;
  const demand = playerDemand(world, n, preferredYears(world, p), 0, null);
  n.playerCounter = demand ? { salary: round500(demand * (1 + p.pers.greed / 500)), years: preferredYears(world, p) } : null;
  note(world, n, how === 'buyout' ? `Release clause of ${fmt(n.fee)} triggered` : `${owner.name} accept ${fmt(n.fee)}`);
  const ask = n.playerCounter ? ` ${p.handle}'s agent asks for ~${fmt(n.playerCounter.salary)}/season over ${n.playerCounter.years} season(s).` : '';
  return { ok: true, status: 'accepted', message: `${how === 'buyout' ? `Release clause met — ${owner.name} must accept.` : `${owner.name} accept ${fmt(n.fee)} for ${p.handle}.`} Agree personal terms by ${formatDate(n.expiresDay)} to complete the move to ${buyer.name}.${ask}`, negId: n.id };
}

function collapse(world: World, n: Negotiation, why: string, block: boolean): NegotiationResponse {
  n.stage = 'collapsed';
  if (block) n.blockedUntil ??= world.day + BLOCK_DAYS;
  note(world, n, why);
  return { ok: false, status: 'rejected', message: why, negId: n.id };
}

export function withdrawNegotiation(world: World, negId: string): NegotiationResponse {
  const n = (world.negotiations ?? []).find((x) => x.id === negId);
  if (!n || !isActive(n)) return { ok: false, status: 'invalid', message: 'Nothing to withdraw.' };
  n.stage = 'withdrawn';
  note(world, n, 'Talks withdrawn');
  return { ok: true, status: 'accepted', message: 'You withdraw from talks.', negId };
}

// ---------------------------------------------------------------------------------------------
// Personal terms
// ---------------------------------------------------------------------------------------------

/** Seasons a player would like: young players keep their options open, veterans want security. */
export function preferredYears(world: World, p: Player): number {
  const age = ageAt(p.birth, world.day);
  if (age >= 26) return 3;
  if (age <= 21) return 2;
  return p.pers.loyalty > 60 ? 3 : 2;
}

/** Appeal of a package apart from the salary-driven money term. */
function packageAppeal(world: World, p: Player, buyer: Team, terms: Pick<NegotiationTerms, 'years' | 'buyout'>): number {
  const ambition = p.pers.ambition / 100;
  return -Math.abs(terms.years - preferredYears(world, p)) * 0.025 + (terms.buyout !== null ? 0.03 : -0.01) * ambition;
}

/** The bar a package has to clear, including a fixed hidden per-negotiation variance. */
function threshold(world: World, n: Negotiation, p: Player, buyer: Team): number {
  let th = 0.82 + (p.pers.ambition / 100) * 0.12;
  const cur = p.teamId ? world.teams[p.teamId] : null;
  if (cur && p.contract && cur.orgId !== buyer.orgId) {
    // An agreed sale makes staying less attractive than when the club still wants the player.
    const stay = offerAppeal(world, p, cur, p.contract.salary) + (p.pers.loyalty / 100) * 0.15 - p.unhappy / 200 - (n.kind === 'transfer' ? 0.05 : 0);
    th = Math.max(th, stay + 0.03);
  } else if (!cur) th -= 0.12;
  return th + RNG.derive(world.meta.seed, 'negp', n.id).normal(0, 0.03);
}

function salaryEquivalent(terms: Pick<NegotiationTerms, 'salary' | 'years' | 'signingBonus'>): number {
  return terms.salary + (terms.signingBonus / Math.max(1, terms.years)) * 0.9;
}

/** Lowest salary the player accepts for the given years/bonus/buyout (null = will not join). */
export function playerDemand(world: World, n: Negotiation, years: number, signingBonus: number, buyout: number | null): number | null {
  const p = world.players[n.playerId];
  const buyer = world.teams[n.buyerTeamId];
  const expected = expectedSalary(world, p, buyer.titleId, buyer.region);
  const coef = 0.55 + (p.pers.greed / 100) * 0.3;
  const base = offerAppeal(world, p, buyer, 0) + packageAppeal(world, p, buyer, { years, buyout });
  const need = threshold(world, n, p, buyer) + 0.004 - base;
  const ratio = need / coef;
  if (ratio > 2) return null; // no salary is enough: the move does not appeal
  const league = buyer.leagueId ? world.leagues[buyer.leagueId] : null;
  const equiv = Math.max(0, ratio) * expected;
  const salary = equiv - (signingBonus / Math.max(1, years)) * 0.9;
  return round500(Math.max(salary, league?.policy.minSalary ?? 0, expected * 0.5));
}

function playerAcceptsTerms(world: World, n: Negotiation, terms: NegotiationTerms): boolean {
  const p = world.players[n.playerId];
  const buyer = world.teams[n.buyerTeamId];
  const appeal = offerAppeal(world, p, buyer, salaryEquivalent(terms)) + packageAppeal(world, p, buyer, terms);
  return appeal >= threshold(world, n, p, buyer);
}

/** Offer personal terms (transfer after a fee is agreed, or a pre-contract). */
export function offerTerms(world: World, negId: string, terms: NegotiationTerms): NegotiationResponse {
  const n = (world.negotiations ?? []).find((x) => x.id === negId);
  if (!n || n.stage !== 'terms') return { ok: false, status: 'invalid', message: 'Agree a fee with the club first.' };
  if (n.expiresDay < world.day) return collapse(world, n, 'The agreement has lapsed.', false);
  const p = world.players[n.playerId];
  const buyer = world.teams[n.buyerTeamId];
  if (!p || !buyer) return collapse(world, n, 'The deal fell through.', false);
  if (p.teamId !== n.sellerTeamId) return collapse(world, n, `${p.handle} is no longer at the club.`, false);
  const years = Math.round(terms.years);
  if (!(years >= 1 && years <= 5)) return { ok: false, status: 'invalid', message: 'Contracts run 1–5 seasons.', negId };
  if (!(terms.signingBonus >= 0)) return { ok: false, status: 'invalid', message: 'Signing bonus cannot be negative.', negId };
  if (terms.buyout !== null && !(terms.buyout >= terms.salary)) return { ok: false, status: 'invalid', message: 'A release clause must be at least one season’s salary.', negId };
  const clean: NegotiationTerms = { salary: Math.round(terms.salary), years, signingBonus: Math.round(terms.signingBonus), buyout: terms.buyout === null ? null : Math.round(terms.buyout) };
  if (n.kind === 'transfer') {
    const err = checkPolicy(world, buyer, p, 'transfer', clean.salary, years);
    if (err) return { ok: false, status: 'invalid', message: err, negId };
    if ((world.orgs[buyer.orgId]?.balance ?? 0) < n.fee + clean.signingBonus) return { ok: false, status: 'invalid', message: 'Insufficient funds for the fee plus signing bonus.', negId };
  } else {
    const v = validateContract(world, buyer, p, { salary: clean.salary, endSeason: world.season + years }, buyer.leagueId ? world.leagues[buyer.leagueId] : null);
    if (!v.ok) return { ok: false, status: 'invalid', message: v.errors[0], negId };
  }
  n.terms = clean;
  note(world, n, `Terms offered: ${fmt(clean.salary)} × ${years}${clean.signingBonus ? `, ${fmt(clean.signingBonus)} bonus` : ''}${clean.buyout !== null ? `, release clause ${fmt(clean.buyout)}` : ''}`);
  if (playerAcceptsTerms(world, n, clean)) return complete(world, n);
  n.termsRounds++;
  const demand = playerDemand(world, n, years, clean.signingBonus, clean.buyout);
  if (n.termsRounds >= TERMS_PATIENCE || demand === null) {
    n.blockedUntil = world.day + BLOCK_DAYS;
    return collapse(world, n, `${p.handle} has decided not to join ${buyer.name}.`, true);
  }
  // Agents pad the ask a little; the honest floor is still accepted.
  const ask = round500(demand * (1 + p.pers.greed / 500));
  n.playerCounter = { salary: ask, years: preferredYears(world, p) };
  note(world, n, `${p.handle} asks for ${fmt(ask)}/season`);
  return { ok: false, status: 'countered', message: `${p.handle} wants ~${fmt(ask)}/season (${n.playerCounter.years} season(s) preferred). ${TERMS_PATIENCE - n.termsRounds} more offer(s) before talks end.`, counterSalary: ask, negId };
}

function complete(world: World, n: Negotiation): NegotiationResponse {
  const p = world.players[n.playerId];
  const buyer = world.teams[n.buyerTeamId];
  const seller = n.sellerTeamId ? world.teams[n.sellerTeamId] : null;
  const t = n.terms!;
  if (n.kind === 'precontract') {
    signPreContract(world, p, buyer, { salary: t.salary, years: t.years, signingBonus: t.signingBonus });
    n.stage = 'agreed';
    note(world, n, 'Pre-contract signed');
    const sheet = p.preContract?.offerSheet;
    return { ok: true, status: 'accepted', message: sheet ? `${p.handle} signs your offer sheet — ${seller?.name} may still match it before the season ends.` : `${p.handle} agrees to join ${buyer.name} on a free transfer at the end of the season.`, negId: n.id };
  }
  movePlayer(world, p, buyer, t.salary, t.years, 'transfer', n.fee, undefined, { signingBonus: t.signingBonus, buyout: t.buyout });
  if (n.sellOnPct > 0 && seller) p.sellOn = { teamId: seller.id, orgId: seller.orgId, pct: n.sellOnPct };
  n.stage = 'completed';
  note(world, n, `Completed: ${p.handle} joins ${buyer.name}`);
  return { ok: true, status: 'accepted', message: `${p.handle} joins ${buyer.name} for ${fmt(n.fee)}!`, negId: n.id };
}

// ---------------------------------------------------------------------------------------------
// Pre-contracts
// ---------------------------------------------------------------------------------------------

/** Approach a player in the final six months of their contract about a free move. */
export function startPreContractTalks(world: World, buyerTeamId: string, playerId: string): NegotiationResponse {
  world.negotiations ??= [];
  const team = world.teams[buyerTeamId];
  const p = world.players[playerId];
  if (!team || !p) return { ok: false, status: 'invalid', message: 'Invalid player or team.' };
  const existing = activeNegotiation(world, buyerTeamId, playerId);
  if (existing) return { ok: true, status: 'pending', message: 'Talks are already open.', negId: existing.id };
  const why = preContractBlock(world, p, team);
  if (why) return { ok: false, status: 'invalid', message: why };
  const blocked = blockedUntil(world, team.orgId, p.id);
  if (blocked > world.day) return { ok: false, status: 'rejected', message: `${p.handle} is not willing to talk again before ${formatDate(blocked)}.` };
  const n: Negotiation = {
    id: nextId(world, 'ng'),
    day: world.day,
    kind: 'precontract',
    playerId: p.id,
    buyerTeamId: team.id,
    sellerTeamId: p.teamId,
    stage: 'terms',
    fee: 0,
    sellOnPct: 0,
    clubCounter: null,
    clubRounds: 0,
    terms: null,
    playerCounter: null,
    termsRounds: 0,
    expiresDay: world.day + 21,
    user: !!world.orgs[team.orgId]?.user,
    log: [],
  };
  world.negotiations.push(n);
  note(world, n, 'Approached about a free move at the end of the season');
  const years = preferredYears(world, p);
  const demand = playerDemand(world, n, years, 0, null);
  if (demand === null) return collapse(world, n, `${p.handle} is not interested in joining ${team.name}.`, true);
  n.playerCounter = { salary: round500(demand * (1 + p.pers.greed / 500)), years };
  return { ok: true, status: 'pending', message: `${p.handle} is open to a free move. Their agent asks for ~${fmt(n.playerCounter.salary)}/season over ${years} season(s).`, negId: n.id };
}

// ---------------------------------------------------------------------------------------------
// Daily upkeep
// ---------------------------------------------------------------------------------------------

/** Lapse expired talks and prune old resolved negotiations. */
export function negotiationsDay(world: World): void {
  const list = world.negotiations;
  if (!list?.length) return;
  for (const n of list) {
    if (!isActive(n) || n.expiresDay >= world.day) continue;
    const wasTerms = n.stage === 'terms';
    n.stage = 'collapsed';
    note(world, n, wasTerms ? 'Personal terms not agreed in time' : 'Talks lapsed');
    if (n.user) {
      const p = world.players[n.playerId];
      addInbox(world, { from: 'Recruitment', subject: `Talks for ${p?.handle ?? 'player'} lapsed`, body: 'The negotiation expired without agreement. You can open new talks unless the club has refused further approaches.', kind: 'transfer' });
    }
  }
  if (list.length > 60) world.negotiations = list.filter((n) => isActive(n) || n.day > world.day - 120 || (n.blockedUntil ?? 0) > world.day);
}

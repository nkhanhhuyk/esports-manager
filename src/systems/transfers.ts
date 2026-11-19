/**
 * Transfer market, contracts in motion, cross-title switching and AI club behaviour.
 *
 * Mechanisms: free-agent signings, transfer bids & buyouts, trades, loans, academy promotions,
 * releases, contract extensions, expiries, and user-facing offers that wait for a decision.
 * League policy (roster limits, transfers, trades, loans, minimum age, caps) gates every move.
 */
import { clamp } from '../core/math';
import { ageAt, ymd } from '../core/date';
import { RNG } from '../core/rng';
import { getTitle, titleSimilarity } from '../data/titles/catalogue';
import type { DraftPickAsset, Offer, Organisation, Player, PreContract, Team, TransferRecord, World } from '../model/types';
import { seasonEndDay } from '../competition/season';
import { pickLabel, pickValue } from './draft';
import { nextId } from '../model/ids';
import { effectivenessLight, bestRole } from '../sim/common/ratings';
import { expectedSalary, makeContract, marketValue, offerAppeal, teamPayroll, validateContract } from './contracts';
import { ledger } from './finance';
import { addInbox, addNews, isUserTeam } from './news';
import { autoLineup } from './lineup';
import { can } from './career';
import { onPlayerJoined } from './registration';
import { budgetCheck } from './treasury';

export interface OfferResponse {
  ok: boolean;
  status: 'accepted' | 'rejected' | 'countered' | 'pending' | 'invalid';
  message: string;
  counterFee?: number;
  counterSalary?: number;
}

export function inTransferWindow(world: World): boolean {
  const { m, d } = ymd(world.day);
  if (m === 12 || (m === 11 && d >= 20) || (m === 1 && d <= 12)) return true;
  if ((m === 5 && d >= 10) || (m === 6 && d === 1)) return true;
  return false;
}

export function rosterMax(world: World, team: Team): number {
  const t = getTitle(team.titleId);
  const l = team.leagueId ? world.leagues[team.leagueId] : null;
  return l?.policy.rosterMax ?? t.teamSize + t.subs + 2;
}

function record(world: World, rec: Omit<TransferRecord, 'id' | 'day' | 'season'>): void {
  world.transfers.push({ id: nextId(world, 'x'), day: world.day, season: world.season, ...rec });
  if (world.transfers.length > 4000) world.transfers.splice(0, world.transfers.length - 4000);
}

/** Adapt a player to a new primary title. Permanent attributes are untouched. */
export function switchTitle(world: World, p: Player, newTitleId: string): void {
  if (p.titleId === newTitleId) return;
  const oldT = getTitle(p.titleId);
  const newT = getTitle(newTitleId);
  const sim = titleSimilarity(oldT.id, newT.id);
  const oldFam = p.prof.titles[oldT.id] ?? 0;
  p.prof.titles[newT.id] = Math.max(p.prof.titles[newT.id] ?? 0, Math.round(oldFam * sim * 0.7));
  for (const r of newT.roles) {
    const k = `${newT.id}:${r.id}`;
    if (p.prof.roles[k] !== undefined) continue;
    let best = 0;
    for (const or of oldT.roles) {
      const shared = or.fclass.some((c) => r.fclass.includes(c));
      if (!shared) continue;
      best = Math.max(best, (p.prof.roles[`${oldT.id}:${or.id}`] ?? 0) * sim * 0.6);
    }
    p.prof.roles[k] = Math.round(best);
  }
  if (newT.weaponClasses.length) {
    const wm = (p.prof.weapons[newT.id] ||= {});
    const ow = p.prof.weapons[oldT.id] ?? {};
    for (const w of newT.weaponClasses) wm[w] = Math.max(wm[w] ?? 0, Math.round((ow[w] ?? 0) * sim));
  }
  p.titleId = newT.id;
  const br = bestRole(p, newT);
  const second = newT.roles.filter((r) => r.id !== br.roleId).sort((a, b) => effectivenessLight(p, newT, b) - effectivenessLight(p, newT, a))[0];
  p.roles = [br.roleId, second?.id ?? br.roleId];
  p.career.push({ season: world.season, day: world.day, titleId: newT.id, teamId: p.teamId, orgId: p.teamId ? world.teams[p.teamId]?.orgId ?? null : null, type: 'title-switch', note: `${oldT.short} → ${newT.short} (${Math.round(sim * 100)}% similarity)` });
  p.attrVersion++;
  p.ca = Math.round(effectivenessLight(p, newT, newT.roles.find((r) => r.id === p.roles[0])!) * 10) / 10;
}

function removeFromTeam(world: World, p: Player): Team | null {
  if (!p.teamId) return null;
  const team = world.teams[p.teamId];
  if (team) {
    team.roster = team.roster.filter((id) => id !== p.id);
    team.lineup = team.lineup.filter((s) => s.playerId !== p.id);
    delete team.training.individual[p.id];
  }
  p.teamId = null;
  return team ?? null;
}

/** Move a player to a team under a new contract (core of every signing type). */
export function movePlayer(
  world: World,
  p: Player,
  to: Team,
  salary: number,
  years: number,
  type: TransferRecord['type'],
  fee = 0,
  loan?: { parentTeamId: string; untilDay: number; wageSplit: number },
  terms: { signingBonus?: number; buyout?: number | null; note?: string } = {},
): void {
  const from = removeFromTeam(world, p);
  if (fee > 0) {
    const buyer = world.orgs[to.orgId];
    if (buyer) ledger(world, buyer, 'transfer-out', -fee, to.id, `Signed ${p.handle}`);
    if (from) {
      const seller = world.orgs[from.orgId];
      if (seller) ledger(world, seller, 'transfer-in', fee, from.id, `Sold ${p.handle}`);
      // A former club's sell-on clause is paid out of the selling club's proceeds.
      const so = p.sellOn;
      const soOrg = so ? world.orgs[so.orgId] : null;
      if (so && soOrg && seller && soOrg.id !== seller.id) {
        const cut = Math.round(fee * so.pct);
        ledger(world, seller, 'sell-on', -cut, from.id, `${p.handle} sell-on clause`);
        ledger(world, soOrg, 'sell-on', cut, so.teamId, `${p.handle} sell-on clause`);
        if (soOrg.user) addInbox(world, { from: 'Finance', subject: `Sell-on income: ${p.handle}`, body: `${p.handle}'s move to ${to.name} triggers your ${Math.round(so.pct * 100)}% sell-on clause: $${cut.toLocaleString()}.`, kind: 'finance' });
      }
    }
    p.sellOn = null;
  }
  if (type !== 'loan') {
    p.preContract = null;
    p.askingPrice = null;
  }
  const opts: Parameters<typeof makeContract>[4] = loan ? { loan, buyout: null } : {};
  if (terms.signingBonus !== undefined) opts.signingBonus = terms.signingBonus;
  if (terms.buyout !== undefined && !loan) opts.buyout = terms.buyout;
  p.contract = makeContract(world, to, salary, years, opts);
  if (p.contract.signingBonus > 0 && world.orgs[to.orgId]) ledger(world, world.orgs[to.orgId], 'signing-bonus', -p.contract.signingBonus, to.id, `${p.handle} signing bonus`);
  p.teamId = to.id;
  p.joinedDay = world.day;
  p.listed = false;
  p.unhappy = 0;
  to.roster.push(p.id);
  if (to.titleId !== p.titleId) switchTitle(world, p, to.titleId);
  p.career.push({ season: world.season, day: world.day, titleId: to.titleId, teamId: to.id, orgId: to.orgId, roleId: p.roles[0], type: type === 'free' ? 'signed' : type === 'promotion' ? 'promoted' : (type as never), fee: fee || undefined });
  record(world, { playerId: p.id, fromTeamId: from?.id ?? null, toTeamId: to.id, fee, type, titleId: to.titleId, note: terms.note });
  if (world.meta.userOrgId === to.orgId) world.scouting[p.id] = 100;
  // Chemistry dips when rosters change.
  to.chemistry = clamp(to.chemistry - 4, 0, 100);
  if (from) from.chemistry = clamp(from.chemistry - 2, 0, 100);
  if (!to.prefs.delegateLineup && to.lineup.length < getTitle(to.titleId).teamSize) to.lineup = autoLineup(world, to);
  if (type !== 'loan-return') onPlayerJoined(world, to, p.id);
  if (to.prefs.delegateLineup) to.lineup = autoLineup(world, to);
  if (from?.prefs.delegateLineup) from.lineup = autoLineup(world, from);
  const big = fee > 200000 || p.rep > 70;
  if (big || isUserTeam(world, to.id) || isUserTeam(world, from?.id)) {
    addNews(world, {
      kind: 'transfer',
      title: `${p.handle} ${type === 'loan' ? 'loaned' : 'joins'} ${to.name}${fee ? ` for $${Math.round(fee / 1000)}K` : ''}`,
      body: `${p.first} ${p.last} (${Math.round(p.ca)} CA) moves ${from ? `from ${from.name}` : 'as a free agent'} to ${to.name} on a ${years}-season deal worth $${Math.round(salary / 1000)}K per season.`,
      titleId: to.titleId,
      refs: [{ type: 'player', id: p.id }, { type: 'team', id: to.id }, ...(from ? [{ type: 'team' as const, id: from.id }] : [])],
      importance: big ? 3 : 1,
    });
  }
}

export function releasePlayer(world: World, playerId: string, note = 'Released'): boolean {
  const p = world.players[playerId];
  if (!p || !p.teamId) return false;
  const team = world.teams[p.teamId];
  const org = team ? world.orgs[team.orgId] : null;
  if (p.contract && org) {
    const remaining = Math.max(0, p.contract.endSeason - world.season) + 0.5;
    ledger(world, org, 'termination', -Math.min(p.contract.terminationFee, p.contract.salary * remaining), team.id, `${p.handle} release`);
  }
  if (p.contract?.loan) {
    const parent = world.teams[p.contract.loan.parentTeamId];
    if (parent) {
      returnFromLoan(world, p);
      return true;
    }
  }
  removeFromTeam(world, p);
  p.contract = null;
  p.preContract = null;
  p.askingPrice = null;
  p.career.push({ season: world.season, day: world.day, titleId: p.titleId, teamId: null, orgId: null, type: 'released', note });
  record(world, { playerId: p.id, fromTeamId: team?.id ?? null, toTeamId: null, fee: 0, type: 'release', titleId: p.titleId, note });
  if (team?.prefs.delegateLineup) team.lineup = autoLineup(world, team);
  return true;
}

function returnFromLoan(world: World, p: Player): void {
  const loan = p.contract?.loan;
  if (!loan) return;
  const parent = world.teams[loan.parentTeamId];
  const from = removeFromTeam(world, p);
  if (!parent || !parent.active) {
    p.contract = null;
    return;
  }
  p.contract = { ...p.contract!, teamId: parent.id, orgId: parent.orgId, loan: undefined };
  p.teamId = parent.id;
  parent.roster.push(p.id);
  p.career.push({ season: world.season, day: world.day, titleId: parent.titleId, teamId: parent.id, orgId: parent.orgId, type: 'loan-return' });
  record(world, { playerId: p.id, fromTeamId: from?.id ?? null, toTeamId: parent.id, fee: 0, type: 'loan-return', titleId: parent.titleId });
  if (parent.prefs.delegateLineup) parent.lineup = autoLineup(world, parent);
}

export function processLoans(world: World): void {
  for (const p of Object.values(world.players)) {
    if (p.contract?.loan && p.contract.loan.untilDay <= world.day) returnFromLoan(world, p);
  }
}

// ---------------------------------------------------------------------------------------------
// Decisions
// ---------------------------------------------------------------------------------------------

function playerAccepts(world: World, p: Player, team: Team, salary: number, rng: RNG): { yes: boolean; demand: number } {
  const expected = expectedSalary(world, p, team.titleId, team.region);
  const appeal = offerAppeal(world, p, team, salary);
  let threshold = 0.82 + (p.pers.ambition / 100) * 0.12;
  if (p.teamId) {
    const cur = world.teams[p.teamId];
    const curAppeal = cur && p.contract ? offerAppeal(world, p, cur, p.contract.salary) + (p.pers.loyalty / 100) * 0.15 - p.unhappy / 200 : 0;
    threshold = Math.max(threshold, curAppeal + 0.03);
  } else {
    threshold -= 0.12; // free agents want a job
  }
  const demand = Math.round((expected * (1 + p.pers.greed / 250)) / 500) * 500;
  return { yes: appeal + rng.normal(0, 0.04) >= threshold, demand };
}

export function clubValuation(world: World, p: Player, owner: Team): number {
  const org = world.orgs[owner.orgId];
  if (org?.user && p.listed && p.askingPrice) return p.askingPrice;
  const starter = owner.lineup.some((s) => s.playerId === p.id);
  let v = marketValue(world, p) * (starter ? 1.35 : 1.0);
  if (org?.strategy === 'moneyball') v *= 0.85;
  if (org?.strategy === 'win-now' || org?.strategy === 'prestige') v *= 1.25;
  if (p.listed) v *= 0.8;
  return Math.round(v / 1000) * 1000;
}

export function checkPolicy(world: World, team: Team, p: Player, kind: Offer['kind'], salary: number, years: number): string | null {
  const league = team.leagueId ? world.leagues[team.leagueId] : null;
  if (team.roster.length >= rosterMax(world, team) && !team.roster.includes(p.id)) return 'Roster is full (league roster limit).';
  if (league && kind === 'trade' && !league.policy.trades) return 'This league does not permit trades.';
  if (league && kind === 'loan' && !league.policy.loans) return 'This league does not permit loans.';
  if (league && kind === 'transfer' && !league.policy.transferFees && p.teamId) return 'This league does not permit paid transfers.';
  const v = validateContract(world, team, p, { salary, endSeason: world.season + years - 1 }, league);
  if (!v.ok) return v.errors[0];
  // The user's signings must fit the department's wage budget (Finances → Treasury).
  return budgetCheck(world, team, p.id, salary);
}

// ---------------------------------------------------------------------------------------------
// User-facing actions
// ---------------------------------------------------------------------------------------------

export function offerFreeAgent(world: World, teamId: string, playerId: string, salary: number, years: number): OfferResponse {
  const team = world.teams[teamId];
  const p = world.players[playerId];
  if (!team || !p || p.retired) return { ok: false, status: 'invalid', message: 'Invalid player or team.' };
  if (p.teamId) return { ok: false, status: 'invalid', message: `${p.handle} is under contract — make a transfer bid instead.` };
  const err = checkPolicy(world, team, p, 'free', salary, years);
  if (err) return { ok: false, status: 'invalid', message: err };
  const rng = RNG.derive(world.meta.seed, 'fa', p.id, world.day, teamId, salary);
  const { yes, demand } = playerAccepts(world, p, team, salary, rng);
  if (!yes) return { ok: false, status: 'countered', message: `${p.handle} wants about $${demand.toLocaleString()} per season.`, counterSalary: demand };
  movePlayer(world, p, team, salary, years, 'free');
  return { ok: true, status: 'accepted', message: `${p.handle} signs for ${team.name}!` };
}

export function bidForPlayer(world: World, teamId: string, playerId: string, fee: number, salary: number, years: number): OfferResponse {
  const team = world.teams[teamId];
  const p = world.players[playerId];
  if (!team || !p || !p.teamId) return { ok: false, status: 'invalid', message: 'Player is not under contract.' };
  const owner = world.teams[p.teamId];
  if (owner.orgId === team.orgId) return promotePlayer(world, playerId, teamId);
  if (p.preContract) return { ok: false, status: 'invalid', message: `${p.handle} has agreed to join ${world.teams[p.preContract.teamId]?.name ?? 'another club'} at the end of the season.` };
  if (!inTransferWindow(world) && !p.listed) return { ok: false, status: 'invalid', message: 'The transfer window is closed (free agents can still be signed).' };
  if (world.day - p.joinedDay < 30) return { ok: false, status: 'rejected', message: `${p.handle} only just joined ${owner.name}.` };
  const err = checkPolicy(world, team, p, 'transfer', salary, years);
  if (err) return { ok: false, status: 'invalid', message: err };
  const buyer = world.orgs[team.orgId];
  if (buyer.balance < fee) return { ok: false, status: 'invalid', message: 'Insufficient funds for this fee.' };
  const rng = RNG.derive(world.meta.seed, 'bid', p.id, world.day, teamId, fee);
  const buyout = p.contract?.buyout ?? null;
  const valuation = clubValuation(world, p, owner);
  const clubOk = (buyout !== null && fee >= buyout) || fee >= valuation;
  if (!clubOk) {
    if (fee >= valuation * 0.7) return { ok: false, status: 'countered', message: `${owner.name} would accept around $${Math.round(valuation * 1.05).toLocaleString()}.`, counterFee: Math.round(valuation * 1.05) };
    return { ok: false, status: 'rejected', message: `${owner.name} reject the bid (valuation ~$${valuation.toLocaleString()}).` };
  }
  const { yes, demand } = playerAccepts(world, p, team, salary, rng);
  if (!yes) return { ok: false, status: 'countered', message: `${owner.name} accept, but ${p.handle} wants ~$${demand.toLocaleString()} per season.`, counterSalary: demand };
  movePlayer(world, p, team, salary, years, 'transfer', fee);
  return { ok: true, status: 'accepted', message: `${p.handle} joins ${team.name} for $${fee.toLocaleString()}.` };
}

// ---------------------------------------------------------------------------------------------
// Pre-contracts (free moves agreed in a player's final six months) & restricted-FA offer sheets
// ---------------------------------------------------------------------------------------------

export const PRECONTRACT_DAYS = 183;

/** Why a club cannot approach this player for a pre-contract (null = eligible). */
export function preContractBlock(world: World, p: Player, buyer: Team): string | null {
  if (!p.teamId || !p.contract || p.retired) return 'Only players under contract can sign pre-contracts (free agents can sign now).';
  const cur = world.teams[p.teamId];
  if (cur?.orgId === buyer.orgId) return 'The player already belongs to your organisation.';
  if (p.contract.loan) return 'The player is on loan.';
  if (p.preContract) return `${p.handle} has already agreed to join ${world.teams[p.preContract.teamId]?.name ?? 'another club'}.`;
  if (p.contract.endSeason !== world.season) return `${p.handle}'s contract runs beyond this season.`;
  if (p.contract.option === 'team') return `${cur?.name ?? 'The club'} holds an option to extend.`;
  const opens = seasonEndDay(world.season) - PRECONTRACT_DAYS;
  if (world.day < opens) return 'Pre-contract talks open six months before the contract expires.';
  return null;
}

/** Is the player a restricted free agent at the end of this season (current club holds matching rights)? */
export function isRestrictedFA(world: World, p: Player): boolean {
  const team = p.teamId ? world.teams[p.teamId] : null;
  const league = team?.leagueId ? world.leagues[team.leagueId] : null;
  return !!league?.policy.restrictedFA && ageAt(p.birth, seasonEndDay(world.season)) <= 24;
}

/** Record an agreed free move (user negotiation or AI). Restricted FAs become offer sheets. */
export function signPreContract(world: World, p: Player, buyer: Team, terms: { salary: number; years: number; signingBonus: number }): PreContract {
  const cur = world.teams[p.teamId!];
  const pc: PreContract = { teamId: buyer.id, orgId: buyer.orgId, salary: terms.salary, years: terms.years, signingBonus: terms.signingBonus, day: world.day, offerSheet: isRestrictedFA(world, p), matched: null };
  p.preContract = pc;
  const curOrg = cur ? world.orgs[cur.orgId] : null;
  addNews(world, {
    kind: 'transfer',
    title: pc.offerSheet ? `${p.handle} signs an offer sheet with ${buyer.name}` : `${p.handle} agrees to join ${buyer.name}`,
    body: pc.offerSheet
      ? `${cur?.name} hold matching rights on the restricted free agent (${pc.years} seasons at $${Math.round(pc.salary / 1000)}K).`
      : `${p.handle} will join ${buyer.name} on a free transfer when the contract with ${cur?.name} expires.`,
    titleId: buyer.titleId,
    refs: [{ type: 'player', id: p.id }, { type: 'team', id: buyer.id }, ...(cur ? [{ type: 'team' as const, id: cur.id }] : [])],
    importance: p.rep > 60 ? 2 : 1,
  });
  if (curOrg?.user) {
    if (pc.offerSheet)
      addInbox(world, {
        from: buyer.name,
        subject: `Offer sheet for ${p.handle}`,
        body: `${p.handle} (restricted free agent) has signed an offer sheet with ${buyer.name}: ${pc.years} seasons at $${pc.salary.toLocaleString()}/season. Match it to keep the player on those terms from next season.`,
        actions: [
          { id: 'match', label: 'Match offer' },
          { id: 'decline', label: 'Let them go' },
        ],
        payload: { matchPlayerId: p.id },
        kind: 'offer',
      });
    else addInbox(world, { from: 'Contracts', subject: `${p.handle} signs a pre-contract`, body: `${p.handle} has agreed to join ${buyer.name} when the current contract expires at the end of the season.`, kind: 'contract' });
  }
  return pc;
}

/** The current club's decision on a restricted-FA offer sheet. */
export function decideOfferSheet(world: World, playerId: string, match: boolean): OfferResponse {
  const p = world.players[playerId];
  if (!p?.preContract?.offerSheet) return { ok: false, status: 'invalid', message: 'No offer sheet to decide.' };
  p.preContract.matched = match;
  return { ok: true, status: 'accepted', message: match ? `You will match the offer sheet: ${p.handle} stays on the new terms next season.` : `${p.handle} will join ${world.teams[p.preContract.teamId]?.name} at the end of the season.` };
}

/** Season rollover: complete (or match) an agreed pre-contract. Returns true when handled. */
function executePreContract(world: World, p: Player, endedSeason: number): boolean {
  const pc = p.preContract;
  if (!pc) return false;
  p.preContract = null;
  const dest = world.teams[pc.teamId];
  const cur = p.teamId ? world.teams[p.teamId] : null;
  if (!dest || !dest.active) return false;
  if (pc.offerSheet && cur) {
    const curOrg = world.orgs[cur.orgId];
    let match = pc.matched;
    if (match === null) {
      if (curOrg?.user && can(world, 'contracts')) match = cur.prefs.tenderRFA !== false;
      else {
        const valued = cur.lineup.some((s) => s.playerId === p.id) || p.pa - p.ca > 5;
        match = valued && pc.salary <= expectedSalary(world, p, cur.titleId, cur.region) * 1.35;
      }
    }
    if (match && curOrg && curOrg.balance > 0) {
      p.contract = { ...makeContract(world, cur, pc.salary, pc.years, { signingBonus: pc.signingBonus }), endSeason: endedSeason + pc.years };
      if (pc.signingBonus > 0) ledger(world, curOrg, 'signing-bonus', -pc.signingBonus, cur.id, `${p.handle} signing bonus`);
      p.career.push({ season: endedSeason, day: world.day, titleId: cur.titleId, teamId: cur.id, orgId: cur.orgId, type: 'signed', note: 'Offer sheet matched (restricted free agent)' });
      addNews(world, { kind: 'transfer', title: `${cur.name} match ${dest.name}'s offer sheet for ${p.handle}`, body: `${p.handle} stays on ${pc.years} seasons at $${Math.round(pc.salary / 1000)}K.`, titleId: cur.titleId, refs: [{ type: 'player', id: p.id }, { type: 'team', id: cur.id }, { type: 'team', id: dest.id }], importance: 1 });
      return true;
    }
  }
  movePlayer(world, p, dest, pc.salary, pc.years, 'free', 0, undefined, { signingBonus: pc.signingBonus, note: pc.offerSheet ? 'Offer sheet (not matched)' : 'Pre-contract' });
  if (p.contract) p.contract.endSeason = endedSeason + pc.years;
  if (cur?.prefs.delegateLineup) cur.lineup = autoLineup(world, cur);
  return true;
}

export function offerExtension(world: World, playerId: string, salary: number, years: number): OfferResponse {
  const p = world.players[playerId];
  if (!p || !p.teamId || !p.contract) return { ok: false, status: 'invalid', message: 'No contract to extend.' };
  if (p.preContract) return { ok: false, status: 'invalid', message: `${p.handle} has signed a pre-contract with ${world.teams[p.preContract.teamId]?.name ?? 'another club'}.` };
  const team = world.teams[p.teamId];
  const v = validateContract(world, team, p, { salary, endSeason: world.season + years - 1 });
  if (!v.ok) return { ok: false, status: 'invalid', message: v.errors[0] };
  const over = budgetCheck(world, team, p.id, salary);
  if (over) return { ok: false, status: 'invalid', message: over };
  const rng = RNG.derive(world.meta.seed, 'ext', p.id, world.day, salary);
  const expected = expectedSalary(world, p, team.titleId, team.region);
  const appeal = offerAppeal(world, p, team, salary) + (p.pers.loyalty / 100) * 0.2 - p.unhappy / 150;
  if (appeal + rng.normal(0, 0.03) < 0.8 && salary < expected * 1.25) {
    const demand = Math.round((expected * (1.05 + p.pers.greed / 300)) / 500) * 500;
    return { ok: false, status: 'countered', message: `${p.handle} asks for ~$${demand.toLocaleString()} per season.`, counterSalary: demand };
  }
  p.contract = makeContract(world, team, salary, years, { buyout: p.contract.buyout });
  p.contract.startDay = world.day;
  p.unhappy = Math.max(0, p.unhappy - 20);
  return { ok: true, status: 'accepted', message: `${p.handle} extends until the end of ${p.contract.endSeason}.` };
}

export function promotePlayer(world: World, playerId: string, toTeamId: string): OfferResponse {
  const p = world.players[playerId];
  const to = world.teams[toTeamId];
  if (!p || !to || !p.teamId) return { ok: false, status: 'invalid', message: 'Invalid move.' };
  const from = world.teams[p.teamId];
  if (from.orgId !== to.orgId) return { ok: false, status: 'invalid', message: 'Promotions/demotions only within the organisation.' };
  if (to.roster.length >= rosterMax(world, to)) return { ok: false, status: 'invalid', message: `${to.name} roster is full.` };
  const league = to.leagueId ? world.leagues[to.leagueId] : null;
  if (league && ageAt(p.birth, world.day) < league.policy.minAge) return { ok: false, status: 'invalid', message: 'Below league minimum age.' };
  const salary = Math.max(p.contract?.salary ?? 0, to.tier === 'main' ? expectedSalary(world, p, to.titleId, to.region) * 0.8 : 0);
  const years = Math.max(1, (p.contract?.endSeason ?? world.season) - world.season + 1);
  movePlayer(world, p, to, salary || 5000, years, 'promotion');
  return { ok: true, status: 'accepted', message: `${p.handle} moves to ${to.name}.` };
}

export function loanPlayer(world: World, playerId: string, toTeamId: string, days: number, wageSplit = 0.5): OfferResponse {
  const p = world.players[playerId];
  const to = world.teams[toTeamId];
  if (!p || !to || !p.teamId || !p.contract) return { ok: false, status: 'invalid', message: 'Invalid loan.' };
  const from = world.teams[p.teamId];
  const err = checkPolicy(world, to, p, 'loan', p.contract.salary, 1);
  if (err) return { ok: false, status: 'invalid', message: err };
  if (to.titleId !== from.titleId && titleSimilarity(to.titleId, from.titleId) < 0.6) return { ok: false, status: 'rejected', message: `${to.name} want players from their own title.` };
  const salary = p.contract.salary;
  const end = p.contract.endSeason;
  removeFromTeam(world, p);
  p.contract = makeContract(world, to, salary, Math.max(1, end - world.season + 1), { loan: { parentTeamId: from.id, untilDay: world.day + days, wageSplit }, buyout: null });
  p.teamId = to.id;
  to.roster.push(p.id);
  p.joinedDay = world.day;
  p.career.push({ season: world.season, day: world.day, titleId: to.titleId, teamId: to.id, orgId: to.orgId, type: 'loan', note: `Loan from ${from.name}` });
  record(world, { playerId: p.id, fromTeamId: from.id, toTeamId: to.id, fee: 0, type: 'loan', titleId: to.titleId });
  if (to.prefs.delegateLineup) to.lineup = autoLineup(world, to);
  if (from.prefs.delegateLineup) from.lineup = autoLineup(world, from);
  return { ok: true, status: 'accepted', message: `${p.handle} loaned to ${to.name} for ${days} days.` };
}

/** Player-for-player trade (+ optional cash from the proposing team). */
export function proposeTrade(world: World, teamId: string, give: string[], take: string[], cash = 0, opts: { otherTeamId?: string; givePicks?: string[]; takePicks?: string[] } = {}): OfferResponse {
  const team = world.teams[teamId];
  const takeP = take.map((id) => world.players[id]).filter(Boolean);
  const picks = world.draftPicks ?? [];
  const givePk = (opts.givePicks ?? []).map((id) => picks.find((d) => d.id === id)).filter((d) => d && !d.used && d.ownerTeamId === teamId) as DraftPickAsset[];
  const otherId = opts.otherTeamId ?? takeP[0]?.teamId ?? picks.find((d) => d.id === opts.takePicks?.[0])?.ownerTeamId;
  const other = otherId ? world.teams[otherId] : undefined;
  if (!team || !other || other.id === team.id) return { ok: false, status: 'invalid', message: 'Invalid trade.' };
  const takePk = (opts.takePicks ?? []).map((id) => picks.find((d) => d.id === id)).filter((d) => d && !d.used && d.ownerTeamId === other.id) as DraftPickAsset[];
  if (!takeP.length && !takePk.length) return { ok: false, status: 'invalid', message: 'Ask for at least one player or pick.' };
  if (takeP.some((p) => p.teamId !== other.id)) return { ok: false, status: 'invalid', message: 'All requested players must come from one team.' };
  const giveP = give.map((id) => world.players[id]).filter((p) => p && p.teamId === team.id);
  const lg = team.leagueId ? world.leagues[team.leagueId] : null;
  const olg = other.leagueId ? world.leagues[other.leagueId] : null;
  if ((lg && !lg.policy.trades) || (olg && !olg.policy.trades)) return { ok: false, status: 'invalid', message: 'Trades are not permitted by league rules (franchised leagues only by default).' };
  if (!inTransferWindow(world)) return { ok: false, status: 'invalid', message: 'Trades are only processed in transfer windows.' };
  const valueIn = takeP.reduce((a, p) => a + clubValuation(world, p, other), 0) + takePk.reduce((a, d) => a + pickValue(world, d) * 1.1, 0);
  const valueOut = giveP.reduce((a, p) => a + marketValue(world, p) * (other.titleId === p.titleId ? 1 : 0.5), 0) + givePk.reduce((a, d) => a + pickValue(world, d), 0) + cash;
  if (takeP.length + giveP.length > 0 && (team.roster.length - giveP.length + takeP.length > rosterMax(world, team) || other.roster.length - takeP.length + giveP.length > rosterMax(world, other))) return { ok: false, status: 'invalid', message: 'The trade would break a roster limit.' };
  if (world.orgs[team.orgId]?.user) {
    const pay = (ps: Player[]) => ps.reduce((a, p) => a + (p.contract?.salary ?? 0), 0);
    const after = teamPayroll(world, team) - pay(giveP) + pay(takeP);
    if (after > team.salaryBudget && pay(takeP) > pay(giveP)) return { ok: false, status: 'invalid', message: `The trade would put ${team.name} ${Math.round(after - team.salaryBudget).toLocaleString()} over its wage budget. Raise or move budget in Finances → Treasury.` };
  }
  if (valueOut < valueIn * 1.05) return { ok: false, status: 'rejected', message: `${other.name} want more value (gap ~$${Math.round(valueIn * 1.05 - valueOut).toLocaleString()}).`, counterFee: Math.round(valueIn * 1.05 - valueOut) };
  if (cash > 0) {
    ledger(world, world.orgs[team.orgId], 'trade-cash', -cash, team.id, 'Trade consideration');
    ledger(world, world.orgs[other.orgId], 'trade-cash', cash, other.id, 'Trade consideration');
  }
  for (const d of takePk) d.ownerTeamId = team.id;
  for (const d of givePk) d.ownerTeamId = other.id;
  if (givePk.length || takePk.length) addNews(world, { kind: 'transfer', title: `${team.name} and ${other.name} trade draft picks`, body: [...takePk.map((d) => `${team.short} receive ${pickLabel(world, d)}`), ...givePk.map((d) => `${other.short} receive ${pickLabel(world, d)}`)].join('; '), titleId: team.titleId, refs: [{ type: 'team', id: team.id }, { type: 'team', id: other.id }], importance: 2 });
  for (const p of takeP) movePlayer(world, p, team, p.contract?.salary ?? expectedSalary(world, p, team.titleId, team.region), Math.max(1, (p.contract?.endSeason ?? world.season) - world.season + 1), 'trade');
  for (const p of giveP) movePlayer(world, p, other, p.contract?.salary ?? expectedSalary(world, p, other.titleId, other.region), Math.max(1, (p.contract?.endSeason ?? world.season) - world.season + 1), 'trade');
  return { ok: true, status: 'accepted', message: `Trade completed with ${other.name}.` };
}

/** User decision on an incoming AI offer for one of their players. */
export function respondToOffer(world: World, offerId: string, accept: boolean): OfferResponse {
  const o = world.offers.find((x) => x.id === offerId);
  if (!o || o.status !== 'pending') return { ok: false, status: 'invalid', message: 'Offer no longer available.' };
  const p = world.players[o.playerId];
  const buyer = world.teams[o.fromTeamId];
  if (!accept) {
    o.status = 'rejected';
    if (p) p.unhappy = clamp(p.unhappy + (p.pers.ambition > 65 ? 15 : 3), 0, 100);
    return { ok: true, status: 'rejected', message: 'Offer rejected.' };
  }
  if (!p || !buyer || p.teamId !== o.toTeamId) {
    o.status = 'withdrawn';
    return { ok: false, status: 'invalid', message: 'The situation changed; offer withdrawn.' };
  }
  if (world.orgs[buyer.orgId].balance < o.fee) {
    o.status = 'withdrawn';
    return { ok: false, status: 'invalid', message: `${buyer.name} can no longer afford the fee.` };
  }
  movePlayer(world, p, buyer, o.salary, o.years, 'transfer', o.fee);
  o.status = 'completed';
  return { ok: true, status: 'accepted', message: `${p.handle} sold to ${buyer.name} for $${o.fee.toLocaleString()}.` };
}

/**
 * Counter an incoming bid with an asking fee. Up to the bidder's hidden ceiling the sale goes
 * through at your price; modestly above it they come back once at their ceiling; far above it
 * they walk away.
 */
export function counterOffer(world: World, offerId: string, askFee: number): OfferResponse {
  const o = world.offers.find((x) => x.id === offerId);
  if (!o || o.status !== 'pending') return { ok: false, status: 'invalid', message: 'Offer no longer available.' };
  const buyer = world.teams[o.fromTeamId];
  const p = world.players[o.playerId];
  if (!buyer || !p || p.teamId !== o.toTeamId) {
    o.status = 'withdrawn';
    return { ok: false, status: 'invalid', message: 'The situation changed; offer withdrawn.' };
  }
  if (!(askFee > o.fee)) return respondToOffer(world, offerId, true);
  const ceiling = Math.min(o.maxFee ?? Math.round(o.fee * 1.1), world.orgs[buyer.orgId]?.balance ?? 0);
  if (askFee <= ceiling) {
    o.fee = Math.round(askFee);
    return respondToOffer(world, offerId, true);
  }
  if (o.counterFee === undefined && askFee <= ceiling * 1.2 && ceiling > o.fee) {
    o.fee = ceiling;
    o.counterFee = ceiling;
    o.expiresDay = Math.max(o.expiresDay, world.day + 3);
    return { ok: false, status: 'countered', message: `${buyer.name} can go to $${ceiling.toLocaleString()} but no further.`, counterFee: ceiling };
  }
  o.status = 'withdrawn';
  o.response = 'Asking price too high';
  return { ok: false, status: 'rejected', message: `${buyer.name} walk away from the deal.` };
}

/** List (or unlist) a player with an optional asking price. */
export function setTransferListing(world: World, playerId: string, listed: boolean, askingPrice: number | null = null): OfferResponse {
  const p = world.players[playerId];
  if (!p || !p.teamId) return { ok: false, status: 'invalid', message: 'Player is not under contract.' };
  p.listed = listed;
  p.askingPrice = listed && askingPrice && askingPrice > 0 ? Math.round(askingPrice) : null;
  return { ok: true, status: 'accepted', message: listed ? `${p.handle} transfer listed${p.askingPrice ? ` at $${p.askingPrice.toLocaleString()}` : ''}.` : `${p.handle} removed from the transfer list.` };
}

/**
 * Offer a player to clubs: the player is listed and clubs in the title that would improve by
 * signing them (and can afford it) send bids to your inbox. Once per player per fortnight.
 */
export function shopPlayer(world: World, playerId: string, askingPrice: number | null = null): OfferResponse {
  const p = world.players[playerId];
  const owner = p?.teamId ? world.teams[p.teamId] : null;
  if (!p || !owner) return { ok: false, status: 'invalid', message: 'Player is not under contract.' };
  if (p.preContract) return { ok: false, status: 'invalid', message: `${p.handle} has already agreed a pre-contract.` };
  if (world.offers.some((o) => o.playerId === p.id && o.day > world.day - 14 && o.response === 'shopped')) return { ok: false, status: 'invalid', message: 'Clubs were sounded out recently — wait for their replies.' };
  setTransferListing(world, p.id, true, askingPrice ?? p.askingPrice ?? null);
  const t = getTitle(owner.titleId);
  const rng = RNG.derive(world.meta.seed, 'shop', p.id, world.day);
  const ask = clubValuation(world, p, owner);
  const bids: Offer[] = [];
  for (const team of Object.values(world.teams)) {
    if (!team.active || team.national || team.titleId !== owner.titleId || team.orgId === owner.orgId) continue;
    const org = world.orgs[team.orgId];
    if (!org || org.user || org.federation || team.roster.length >= rosterMax(world, team)) continue;
    const lg = team.leagueId ? world.leagues[team.leagueId] : null;
    if (lg && !lg.policy.transferFees) continue;
    const role = t.roles.find((r) => r.id === p.roles[0]) ?? t.roles[0];
    const slot = team.lineup.find((s) => s.roleId === role.id);
    const cur = slot?.playerId ? world.players[slot.playerId] : null;
    const gain = effectivenessLight(p, t, role) - (cur ? effectivenessLight(cur, t, role) : 0);
    const salary = Math.round((expectedSalary(world, p, t.id, team.region) * 1.05) / 500) * 500;
    const budgetLeft = team.salaryBudget - teamPayroll(world, team) + Math.max(0, org.balance) * 0.02;
    if (gain < 1 || salary > budgetLeft * 1.2 || org.balance < ask * 0.5) continue;
    const fee = Math.round(Math.min(org.balance * 0.5, ask * rng.float(0.7, 1.0)) / 1000) * 1000;
    bids.push({ id: '', day: world.day, playerId: p.id, fromTeamId: team.id, toTeamId: owner.id, fee, salary, years: rng.int(1, 3), status: 'pending', kind: 'transfer', user: true, expiresDay: world.day + 10, response: 'shopped', maxFee: Math.round(Math.min(org.balance * 0.6, ask * rng.float(1.0, 1.25)) / 1000) * 1000 });
  }
  bids.sort((a, b) => b.fee - a.fee);
  for (const b of bids.slice(0, 3)) {
    b.id = nextId(world, 'of');
    world.offers.push(b);
    addInbox(world, { from: world.teams[b.fromTeamId].name, subject: `Bid for ${p.handle}: $${b.fee.toLocaleString()}`, body: `${world.teams[b.fromTeamId].name} respond to your approach with a bid of $${b.fee.toLocaleString()}. Accept, reject or counter from the Market's Offers tab.`, actions: [{ id: 'accept', label: 'Accept' }, { id: 'reject', label: 'Reject' }], payload: { offerId: b.id }, kind: 'offer' });
  }
  if (!bids.length) return { ok: true, status: 'pending', message: `${p.handle} is listed, but no club is interested at the moment.` };
  return { ok: true, status: 'accepted', message: `${Math.min(3, bids.length)} club(s) bid for ${p.handle} — see Market › Offers.` };
}

// ---------------------------------------------------------------------------------------------
// AI behaviour
// ---------------------------------------------------------------------------------------------

interface Need {
  roleId: string;
  currentE: number;
  urgent: boolean;
}

function teamNeeds(world: World, team: Team): Need[] {
  const t = getTitle(team.titleId);
  const players = team.roster.map((id) => world.players[id]).filter((p) => p && !p.retired);
  const needs: Need[] = [];
  for (const roleId of t.lineup) {
    const role = t.roles.find((r) => r.id === roleId)!;
    const slot = team.lineup.find((s) => s.roleId === roleId);
    const cur = slot ? world.players[slot.playerId] : undefined;
    const e = cur ? effectivenessLight(cur, t, role) : 0;
    needs.push({ roleId, currentE: e, urgent: !cur });
  }
  if (players.length < t.teamSize) for (const n of needs) n.urgent = n.urgent || n.currentE === 0;
  return needs.sort((a, b) => a.currentE - b.currentE);
}

function freeAgentPool(world: World, listed?: Map<string, Player[]>, pre?: Map<string, number>): Map<string, Player[]> {
  const m = new Map<string, Player[]>();
  for (const p of Object.values(world.players)) {
    if (p.retired) continue;
    if (pre && p.preContract) pre.set(p.preContract.teamId, (pre.get(p.preContract.teamId) ?? 0) + 1);
    if (p.teamId) {
      if (listed && p.listed && !p.preContract) {
        if (!listed.has(p.titleId)) listed.set(p.titleId, []);
        listed.get(p.titleId)!.push(p);
      }
      continue;
    }
    if (!m.has(p.titleId)) m.set(p.titleId, []);
    m.get(p.titleId)!.push(p);
  }
  for (const list of m.values()) list.sort((a, b) => b.ca - a.ca);
  return m;
}

function strategyScore(org: Organisation, p: Player, gain: number, salary: number, age: number): number {
  switch (org.strategy) {
    case 'youth':
      return gain + (age <= 21 ? (p.pa - p.ca) * 0.35 : -1);
    case 'moneyball':
      return gain + (gain / Math.max(1, salary / 20000)) * 0.8;
    case 'prestige':
      return gain + p.rep * 0.03;
    case 'win-now':
      return gain * 1.3 - (age <= 19 ? 1 : 0);
    default:
      return gain + (age <= 22 ? (p.pa - p.ca) * 0.12 : 0);
  }
}

interface MarketIndex {
  fa: Map<string, Player[]>;
  /** Transfer-listed players under contract, by title. */
  listed: Map<string, Player[]>;
  /** Pre-contracts already agreed, by destination team. */
  pre: Map<string, number>;
  byTitle: Map<string, Team[]>;
  academy: Map<string, Team>;
}

function buildIndex(world: World): MarketIndex {
  const byTitle = new Map<string, Team[]>();
  const academy = new Map<string, Team>();
  for (const t of Object.values(world.teams)) {
    if (!t.active) continue;
    if (!byTitle.has(t.titleId)) byTitle.set(t.titleId, []);
    byTitle.get(t.titleId)!.push(t);
    if (t.parentTeamId) academy.set(t.parentTeamId, t);
  }
  const listed = new Map<string, Player[]>();
  const pre = new Map<string, number>();
  return { fa: freeAgentPool(world, listed, pre), listed, pre, byTitle, academy };
}

function aiTeamMarket(world: World, team: Team, idx: MarketIndex, rng: RNG, emergency: boolean): void {
  const faPool = idx.fa;
  const t = getTitle(team.titleId);
  const org = world.orgs[team.orgId];
  if (!org || (org.user && can(world, 'transfers'))) return;
  const league = team.leagueId ? world.leagues[team.leagueId] : null;
  const maxR = rosterMax(world, team);
  // 1) Release surplus (never just-signed players)
  while (team.roster.length > maxR) {
    const worst = team.roster
      .map((id) => world.players[id])
      .filter((p) => p && world.day - p.joinedDay > 30 && !team.lineup.some((s) => s.playerId === p.id))
      .sort((a, b) => a.ca - b.ca)[0];
    if (!worst) break;
    releasePlayer(world, worst.id, 'Surplus to requirements');
  }
  const needs = teamNeeds(world, team);
  const need = needs.find((n) => n.urgent) ?? needs[0];
  if (!need) return;
  const role = t.roles.find((r) => r.id === need.roleId)!;
  const payroll = teamPayroll(world, team);
  const budgetLeft = team.salaryBudget - payroll + (org.balance > 0 ? org.balance * 0.02 : org.balance * 0.1);
  const wantSub = team.roster.length < t.teamSize + 1;
  if (!need.urgent && !wantSub && team.roster.length >= maxR) return;
  // 2) Academy promotion check
  const academy = idx.academy.get(team.id);
  let best: { p: Player; score: number; fee: number; salary: number; pre?: boolean } | null = null;
  if (academy) {
    for (const pid of academy.roster) {
      const p = world.players[pid];
      if (!p) continue;
      const e = effectivenessLight(p, t, role);
      if (e > need.currentE + 1.5 && (!league || ageAt(p.birth, world.day) >= league.policy.minAge)) {
        promotePlayer(world, p.id, team.id);
        return;
      }
    }
  }
  // 3) Free agents (including cross-title options from similar titles)
  const candidates: Player[] = [...(faPool.get(t.id) ?? []).slice(0, 60)];
  if (rng.chance(0.25)) {
    for (const [tid, list] of faPool) if (tid !== t.id && titleSimilarity(tid, t.id) >= 0.7) candidates.push(...list.slice(0, 10));
  }
  const minAge = league?.policy.minAge ?? t.minAge;
  for (const p of candidates) {
    if (p.teamId || p.retired) continue;
    const age = ageAt(p.birth, world.day);
    if (age < minAge) continue;
    const e = effectivenessLight(p, t, role);
    const gain = e - need.currentE;
    const sal = expectedSalary(world, p, t.id, team.region);
    if (sal > budgetLeft && !need.urgent) continue;
    const score = strategyScore(org, p, gain, sal, age);
    if ((gain > 1 || need.urgent || (wantSub && gain > -8)) && (!best || score > best.score)) best = { p, score, fee: 0, salary: sal };
  }
  // 4) Transfer targets from weaker clubs (windows only)
  if (!emergency && inTransferWindow(world) && rng.chance(0.45) && org.balance > 0) {
    const poacher = org.strategy === 'win-now' || org.strategy === 'prestige' || org.balance > team.salaryBudget * 2;
    const others = (idx.byTitle.get(t.id) ?? []).filter((x) => x.id !== team.id && x.active && x.orgId !== team.orgId && (poacher || x.elo < team.elo + 80));
    const pool = rng.sample(others, 8).flatMap((x) => x.roster.map((id) => world.players[id]));
    // Transfer-listed players are actively shopped, so clubs look at them first.
    pool.push(...rng.sample((idx.listed.get(t.id) ?? []).filter((p) => p.teamId && world.teams[p.teamId]?.orgId !== team.orgId), 6));
    const pendingPre = idx.pre.get(team.id) ?? 0;
    for (const p of pool) {
      if (!p || p.retired || !p.contract || p.contract.loan || p.preContract || p.teamId === team.id) continue;
      const pre = !need.urgent && pendingPre < 1 && preContractBlock(world, p, team) === null;
      if (!pre && (world.day - p.joinedDay < 120 && !p.listed)) continue;
      const owner = world.teams[p.teamId!];
      const e = effectivenessLight(p, t, role);
      const gain = e - need.currentE;
      if (gain < 3) continue;
      const fee = pre ? 0 : clubValuation(world, p, owner);
      const sal = expectedSalary(world, p, t.id, team.region) * 1.1;
      if (fee > org.balance * 0.5 || sal > budgetLeft * 1.2) continue;
      const score = strategyScore(org, p, gain, sal, ageAt(p.birth, world.day)) - fee / 200000 + (pre ? 0.5 : 0);
      if (!best || score > best.score) best = { p, score, fee, salary: sal, pre };
    }
  }
  if (!best) return;
  const years = rng.int(1, 3);
  const salary = Math.round((best.salary * rng.float(0.95, 1.15)) / 500) * 500;
  if (best.pre) {
    if (playerAccepts(world, best.p, team, salary, rng).yes) {
      signPreContract(world, best.p, team, { salary, years, signingBonus: Math.round((salary * 0.05) / 500) * 500 });
      idx.pre.set(team.id, (idx.pre.get(team.id) ?? 0) + 1);
    }
    return;
  }
  if (best.fee === 0) {
    const res = playerAccepts(world, best.p, team, salary, rng);
    if (res.yes || need.urgent) movePlayer(world, best.p, team, Math.max(salary, need.urgent ? res.demand : salary), years, 'free');
    return;
  }
  const owner = world.teams[best.p.teamId!];
  const ownerOrg = world.orgs[owner.orgId];
  if (ownerOrg.user && can(world, 'transfers')) {
    if (world.offers.some((o) => o.status === 'pending' && o.playerId === best!.p.id)) return;
    const offer: Offer = {
      id: nextId(world, 'of'),
      day: world.day,
      playerId: best.p.id,
      fromTeamId: team.id,
      toTeamId: owner.id,
      fee: Math.round(best.fee * rng.float(0.85, 1.1)),
      salary,
      years,
      status: 'pending',
      kind: 'transfer',
      user: true,
      expiresDay: world.day + 7,
    };
    offer.maxFee = Math.max(offer.fee, Math.round(Math.min(org.balance * 0.6, best.fee * rng.float(1.08, 1.3)) / 1000) * 1000);
    world.offers.push(offer);
    addInbox(world, {
      from: team.name,
      subject: `Transfer offer for ${best.p.handle}`,
      body: `${team.name} offer $${offer.fee.toLocaleString()} for ${best.p.handle} (${owner.name}). The player would earn $${salary.toLocaleString()}/season.`,
      actions: [
        { id: 'accept', label: 'Accept' },
        { id: 'reject', label: 'Reject' },
      ],
      payload: { offerId: offer.id },
      kind: 'offer',
    });
    return;
  }
  if (!playerAccepts(world, best.p, team, salary, rng).yes) return;
  movePlayer(world, best.p, team, salary, years, 'transfer', best.fee);
}

/** Daily AI market tick. Teams act roughly weekly during windows and immediately when short-handed. */
export function aiMarketDay(world: World): void {
  const rng = new RNG(`${world.meta.seed}|market|${world.day}`);
  const window = inTransferWindow(world);
  let index: MarketIndex | null = null;
  for (const team of Object.values(world.teams)) {
    if (!team.active || team.national) continue;
    const org = world.orgs[team.orgId];
    if (!org || (org.user && can(world, 'transfers')) || org.federation) continue;
    const t = getTitle(team.titleId);
    const short = team.roster.length < t.teamSize;
    if (!short && !(window && rng.chance(1 / 6))) continue;
    index ||= buildIndex(world);
    aiTeamMarket(world, team, index, rng, short);
  }
  // Expire user offers
  for (const o of world.offers) if (o.status === 'pending' && o.expiresDay < world.day) o.status = 'withdrawn';
  if (world.offers.length > 400) world.offers = world.offers.filter((o) => o.status === 'pending' || o.day > world.day - 120);
}

/** Monthly (late season): AI contract renewals and transfer listing of unhappy players. */
export function aiContracts(world: World): void {
  const rng = new RNG(`${world.meta.seed}|renew|${world.day}`);
  const { m } = ymd(world.day);
  for (const p of Object.values(world.players)) {
    if (p.retired || !p.teamId || !p.contract || p.contract.loan) continue;
    const team = world.teams[p.teamId];
    const org = world.orgs[team?.orgId];
    if (!team || !org) continue;
    if (org.user && can(world, 'contracts')) {
      if (p.contract.endSeason === world.season && m === 9 && !team.prefs.delegateTraining) {
        addInbox(world, { from: 'Contracts', subject: `${p.handle}'s contract expires this season`, body: `Offer an extension from the player's Contract tab or ${p.handle} will leave as a free agent at the end of the season.`, kind: 'contract' });
      }
      if (p.unhappy > 70 && !p.listed) {
        p.listed = true;
        addInbox(world, { from: p.handle, subject: 'Transfer request', body: `${p.handle} is unhappy with their situation and asks to be transfer-listed.`, kind: 'player' });
      }
      continue;
    }
    if (p.contract.endSeason === world.season && m >= 8) {
      const t = getTitle(team.titleId);
      const starter = team.lineup.some((s) => s.playerId === p.id);
      const age = ageAt(p.birth, world.day);
      const keep = starter || (age <= 21 && p.pa - p.ca > 6);
      if (keep && age < p.dev.declineAge + 3 && rng.chance(0.5)) {
        const sal = expectedSalary(world, p, t.id, team.region);
        offerExtension(world, p.id, Math.round(sal * rng.float(1.0, 1.15)), rng.int(1, 3));
      }
    }
    if (p.unhappy > 75) p.listed = true;
  }
}

/** Season rollover: expire contracts. */
export function expireContracts(world: World, endedSeason: number): void {
  for (const p of Object.values(world.players)) {
    if (p.retired || !p.contract) continue;
    if (p.contract.endSeason > endedSeason) continue;
    if (p.contract.loan) {
      returnFromLoan(world, p);
      if (!p.contract || p.contract.endSeason > endedSeason) continue;
    }
    if (p.preContract && executePreContract(world, p, endedSeason)) continue;
    const team = p.teamId ? world.teams[p.teamId] : null;
    if (p.contract.option === 'team' && team && team.lineup.some((s) => s.playerId === p.id)) {
      p.contract.endSeason += 1;
      p.contract.option = 'none';
      continue;
    }
    // Restricted free agency: young players' clubs may retain them with a qualifying offer.
    const league = team?.leagueId ? world.leagues[team.leagueId] : null;
    if (team && league?.policy.restrictedFA && ageAt(p.birth, world.day) <= 24) {
      const org = world.orgs[team.orgId];
      const valued = team.lineup.some((s) => s.playerId === p.id) || p.pa - p.ca > 5;
      const tender = org?.user && can(world, 'contracts') ? team.prefs.tenderRFA !== false : valued;
      if (tender && org && org.balance > 0) {
        const salary = Math.round(Math.max(p.contract.salary * 1.1, league.policy.minSalary) / 500) * 500;
        p.contract = { ...makeContract(world, team, salary, 1, { buyout: p.contract.buyout }), endSeason: endedSeason + 1 };
        p.career.push({ season: endedSeason, day: world.day, titleId: team.titleId, teamId: team.id, orgId: team.orgId, type: 'signed', note: 'Qualifying offer (restricted free agent)' });
        if (org.user) addInbox(world, { from: 'Contracts', subject: `${p.handle} retained as a restricted free agent`, body: `A one-season qualifying offer of $${salary.toLocaleString()} keeps ${p.handle} at ${team.name}.`, kind: 'contract' });
        continue;
      }
    }
    removeFromTeam(world, p);
    p.contract = null;
    p.career.push({ season: endedSeason, day: world.day, titleId: p.titleId, teamId: null, orgId: null, type: 'released', note: 'Contract expired' });
    record(world, { playerId: p.id, fromTeamId: team?.id ?? null, toTeamId: null, fee: 0, type: 'free', titleId: p.titleId, note: 'Contract expired' });
    if (team && team.orgId === world.meta.userOrgId) addInbox(world, { from: 'Contracts', subject: `${p.handle} leaves on a free`, body: `${p.handle}'s contract with ${team.name} has expired.`, kind: 'contract' });
    if (team?.prefs.delegateLineup) team.lineup = autoLineup(world, team);
  }
  for (const s of Object.values(world.staff)) {
    if (s.retired || !s.contract || s.contract.endSeason > endedSeason) continue;
    // Organisations renew their staff automatically (the user can release staff manually).
    const org = s.orgId ? world.orgs[s.orgId] : null;
    if (org && org.active) s.contract.endSeason = endedSeason + 2;
    else s.contract = null;
  }
}

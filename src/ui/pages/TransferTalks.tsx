/**
 * Transfer negotiation UI: bid → club response → personal terms, pre-contracts, and the selling
 * tools for your own players (asking price, offer to clubs, offer-sheet decisions).
 */
import { useState } from 'react';
import { useGame, useWorld } from '../store';
import { TeamLink } from '../components/common';
import { formatMoney } from '../../core/math';
import { formatDate } from '../../core/date';
import {
  acceptClubCounter,
  activeNegotiation,
  CLUB_PATIENCE,
  MAX_SELL_ON,
  offerTerms,
  reviseBid,
  startPreContractTalks,
  startTransferTalks,
  TERMS_PATIENCE,
  withdrawNegotiation,
  type NegotiationResponse,
} from '../../systems/negotiation';
import { decideOfferSheet, inTransferWindow, preContractBlock, setTransferListing, shopPlayer } from '../../systems/transfers';
import type { Negotiation, Player, Team, World } from '../../model/types';

type Res = { ok: boolean; status?: string; message: string; counterFee?: number; counterSalary?: number } | undefined;

function useRun() {
  const { act, notify } = useGame.getState();
  return (fn: (w: World) => Res, after?: (r: NonNullable<Res>) => void) => {
    const r = act(fn, { save: true });
    if (!r) return;
    notify(r.message, r.ok ? 'ok' : r.status === 'countered' || r.status === 'pending' ? 'info' : 'err');
    after?.(r);
  };
}

const SELL_ON = [0, 0.1, 0.15, 0.2, 0.25, MAX_SELL_ON];

/** Buying side for one of your teams and a player at another club. */
export function BuyPanel({ p, team }: { p: Player; team: Team }) {
  const world = useWorld();
  const run = useRun();
  const [fee, setFee] = useState(Math.round(p.value));
  const [sellOn, setSellOn] = useState(0);
  const owner = p.teamId ? world.teams[p.teamId] : null;
  if (!owner) return null;
  if (p.preContract) {
    const dest = world.teams[p.preContract.teamId];
    return (
      <div className="muted">
        {p.handle} has agreed {p.preContract.offerSheet ? 'an offer sheet' : 'a pre-contract'} with <TeamLink t={dest} /> and joins at the end of the season.
      </div>
    );
  }
  const n = activeNegotiation(world, team.id, p.id);
  if (n) return <NegotiationView key={`${n.id}-${n.stage}`} n={n} />;
  const pre = preContractBlock(world, p, team);
  const open = inTransferWindow(world) || p.listed;
  return (
    <div>
      <div className="steps small muted">
        <b>1. Fee with {owner.name}</b> → 2. Personal terms with {p.handle} → 3. Done
      </div>
      <div className="row" style={{ alignItems: 'flex-end', marginTop: 6 }}>
        <label className="field">
          <span>
            Transfer fee (value {formatMoney(p.value)}
            {p.contract?.buyout ? `, release clause ${formatMoney(p.contract.buyout)}` : ''}
            {p.listed ? `, listed${p.askingPrice ? ` at ${formatMoney(p.askingPrice)}` : ''}` : ''})
          </span>
          <input type="number" aria-label="Transfer fee" value={fee} onChange={(e) => setFee(Number(e.target.value))} />
        </label>
        <label className="field">
          <span>Sell-on clause</span>
          <select aria-label="Sell-on clause" value={sellOn} onChange={(e) => setSellOn(Number(e.target.value))}>
            {SELL_ON.map((x) => (
              <option key={x} value={x}>
                {x ? `${Math.round(x * 100)}% of next fee` : 'None'}
              </option>
            ))}
          </select>
        </label>
        <button className="btn" disabled={!open} onClick={() => run((w) => startTransferTalks(w, team.id, p.id, fee, sellOn), (r) => r.counterFee && setFee(r.counterFee))}>
          Submit bid
        </button>
        {pre === null && (
          <button className="btn ghost" onClick={() => run((w) => startPreContractTalks(w, team.id, p.id))}>
            Approach for pre-contract
          </button>
        )}
      </div>
      <div className="muted small" style={{ marginTop: 4 }}>
        {!open && 'The transfer window is closed (listed players can still be bought). '}
        {pre === null
          ? `${p.handle}'s contract expires this season: you can agree a free move for next season instead of paying a fee.`
          : 'A sell-on clause lowers the fee the club needs now: they value a share of a future sale, especially for young players.'}
      </div>
    </div>
  );
}

function Steps({ n }: { n: Negotiation }) {
  const items = n.kind === 'precontract' ? ['Personal terms', 'Joins at season end'] : ['Fee with club', 'Personal terms', 'Done'];
  const at = n.kind === 'precontract' ? 0 : n.stage === 'club' ? 0 : 1;
  return (
    <div className="steps small">
      {items.map((x, i) => (
        <span key={x} className={i === at ? '' : 'muted'} style={{ fontWeight: i === at ? 700 : 400 }}>
          {i > 0 && ' → '}
          {i + 1}. {x}
        </span>
      ))}
    </div>
  );
}

/** An open negotiation: club stage (bids/counters) or terms stage (salary, years, bonus, clause). */
export function NegotiationView({ n }: { n: Negotiation }) {
  const world = useWorld();
  const run = useRun();
  const p = world.players[n.playerId];
  const seller = n.sellerTeamId ? world.teams[n.sellerTeamId] : null;
  const [fee, setFee] = useState(n.clubCounter ?? n.fee);
  const [sellOn, setSellOn] = useState(n.sellOnPct);
  const [salary, setSalary] = useState(n.playerCounter?.salary ?? p.contract?.salary ?? 0);
  const [years, setYears] = useState(n.playerCounter?.years ?? 2);
  const [bonus, setBonus] = useState(0);
  const [clause, setClause] = useState(false);
  const [buyout, setBuyout] = useState(Math.round((n.playerCounter?.salary ?? 50000) * 4));
  const after = (r: NegotiationResponse | NonNullable<Res>) => {
    if (r.counterFee) setFee(r.counterFee);
    if (r.counterSalary) setSalary(r.counterSalary);
  };
  return (
    <div>
      <Steps n={n} />
      {n.stage === 'club' && seller && (
        <>
          <div style={{ marginTop: 6 }}>
            Your bid: <b>{formatMoney(n.fee)}</b>
            {n.sellOnPct > 0 && ` + ${Math.round(n.sellOnPct * 100)}% sell-on`} · {seller.name}{' '}
            {n.clubCounter !== null ? (
              <>
                ask <b>{formatMoney(n.clubCounter)}</b>{' '}
                <button className="btn sm" onClick={() => run((w) => acceptClubCounter(w, n.id))}>
                  Accept {formatMoney(n.clubCounter)}
                </button>
              </>
            ) : (
              'rejected the bid'
            )}
            <span className="muted small"> · {CLUB_PATIENCE - n.clubRounds} bid(s) left before they lose patience</span>
          </div>
          <div className="row" style={{ alignItems: 'flex-end', marginTop: 6 }}>
            <label className="field">
              <span>Revised fee</span>
              <input type="number" aria-label="Revised fee" value={fee} onChange={(e) => setFee(Number(e.target.value))} />
            </label>
            <label className="field">
              <span>Sell-on clause</span>
              <select value={sellOn} onChange={(e) => setSellOn(Number(e.target.value))}>
                {SELL_ON.map((x) => (
                  <option key={x} value={x}>
                    {x ? `${Math.round(x * 100)}%` : 'None'}
                  </option>
                ))}
              </select>
            </label>
            <button className="btn" onClick={() => run((w) => reviseBid(w, n.id, fee, sellOn), after)}>
              Improve bid
            </button>
            <button className="btn ghost" onClick={() => run((w) => withdrawNegotiation(w, n.id))}>
              Withdraw
            </button>
          </div>
        </>
      )}
      {n.stage === 'terms' && (
        <>
          <div style={{ marginTop: 6 }}>
            {n.kind === 'transfer' ? (
              <>
                Fee agreed with {seller?.name}: <b>{formatMoney(n.fee)}</b>
                {n.sellOnPct > 0 && ` + ${Math.round(n.sellOnPct * 100)}% sell-on`}.{' '}
              </>
            ) : (
              <>Free transfer at the end of the season. </>
            )}
            Agree terms by <b>{formatDate(n.expiresDay)}</b>.
            {n.playerCounter && (
              <span>
                {' '}
                {p.handle}'s agent asks for <b>{formatMoney(n.playerCounter.salary)}</b>/season over {n.playerCounter.years} season(s).
              </span>
            )}
            <span className="muted small"> · {TERMS_PATIENCE - n.termsRounds} offer(s) left</span>
          </div>
          <div className="row" style={{ alignItems: 'flex-end', marginTop: 6 }}>
            <label className="field">
              <span>Salary / season</span>
              <input type="number" aria-label="Offered salary" value={salary} onChange={(e) => setSalary(Number(e.target.value))} />
            </label>
            <label className="field">
              <span>Seasons</span>
              <select aria-label="Contract seasons" value={years} onChange={(e) => setYears(Number(e.target.value))}>
                {[1, 2, 3, 4, 5].map((y) => (
                  <option key={y}>{y}</option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>Signing bonus</span>
              <input type="number" value={bonus} onChange={(e) => setBonus(Number(e.target.value))} />
            </label>
            <label className="field">
              <span>
                <input type="checkbox" checked={clause} onChange={(e) => setClause(e.target.checked)} /> Release clause
              </span>
              <input type="number" disabled={!clause} value={buyout} onChange={(e) => setBuyout(Number(e.target.value))} />
            </label>
            <button className="btn" onClick={() => run((w) => offerTerms(w, n.id, { salary, years, signingBonus: bonus, buyout: clause ? buyout : null }), after)}>
              Offer terms
            </button>
            <button className="btn ghost" onClick={() => run((w) => withdrawNegotiation(w, n.id))}>
              Withdraw
            </button>
          </div>
          <div className="muted small" style={{ marginTop: 4 }}>
            Signing bonuses count towards the package; ambitious players like a release clause; seasons away from the player's preference cost appeal.
          </div>
        </>
      )}
      <NegotiationLog n={n} />
    </div>
  );
}

export function NegotiationLog({ n, max = 6 }: { n: Negotiation; max?: number }) {
  if (!n.log.length) return null;
  return (
    <ul className="small muted" style={{ margin: '6px 0 0', paddingLeft: 18 }}>
      {n.log.slice(-max).map((l, i) => (
        <li key={i}>
          {formatDate(l.day)} — {l.text}
        </li>
      ))}
    </ul>
  );
}

/** Selling tools for one of your contracted players. */
export function SellPanel({ p }: { p: Player }) {
  const world = useWorld();
  const run = useRun();
  const [ask, setAsk] = useState(p.askingPrice ?? Math.round(p.value));
  const pc = p.preContract;
  return (
    <div style={{ marginTop: 10 }}>
      {pc && (
        <div className="row" style={{ marginBottom: 6 }}>
          <span>
            {pc.offerSheet ? 'Offer sheet' : 'Pre-contract'}: {p.handle} → <TeamLink t={world.teams[pc.teamId]} /> ({pc.years} seasons at {formatMoney(pc.salary)})
            {pc.offerSheet && ` · ${pc.matched === null ? 'undecided (your RFA tender setting applies)' : pc.matched ? 'you will match' : 'you will let them go'}`}
          </span>
          {pc.offerSheet && (
            <>
              <button className="btn sm" onClick={() => run((w) => decideOfferSheet(w, p.id, true))}>
                Match
              </button>
              <button className="btn ghost sm" onClick={() => run((w) => decideOfferSheet(w, p.id, false))}>
                Let go
              </button>
            </>
          )}
        </div>
      )}
      <div className="row" style={{ alignItems: 'flex-end' }}>
        <label className="field">
          <span>Asking price (value {formatMoney(p.value)})</span>
          <input type="number" aria-label="Asking price" value={ask} onChange={(e) => setAsk(Number(e.target.value))} />
        </label>
        <button className="btn ghost sm" onClick={() => run((w) => setTransferListing(w, p.id, true, ask))}>
          {p.listed ? 'Update listing' : 'Transfer list'}
        </button>
        {p.listed && (
          <button className="btn ghost sm" onClick={() => run((w) => setTransferListing(w, p.id, false))}>
            Remove from list
          </button>
        )}
        <button className="btn ghost sm" disabled={!!pc} onClick={() => run((w) => shopPlayer(w, p.id, ask))}>
          Offer to clubs
        </button>
        {p.listed && <span className="muted small">Listed{p.askingPrice ? ` at ${formatMoney(p.askingPrice)}` : ''} — clubs can bid even outside the window.</span>}
      </div>
    </div>
  );
}

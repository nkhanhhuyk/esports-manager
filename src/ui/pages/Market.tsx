import { useMemo, useState } from 'react';
import { useGame, useWorld } from '../store';
import { Card, Flag, Gate, PageHeader, PlayerLink, Rating, TeamLink, TitleTag, age, Tabs } from '../components/common';
import { DataTable } from '../components/DataTable';
import { getTitle } from '../../data/titles/catalogue';
import { navigate, withQuery, type Route } from '../router';
import { potentialRange } from '../../systems/scouting';
import { formatMoney } from '../../core/math';
import { formatDate } from '../../core/date';
import { inTransferWindow, proposeTrade, respondToOffer } from '../../systems/transfers';
import { pickLabel, picksOwnedBy, pickValue } from '../../systems/draft';
import { isUserTeamId, roleName, userTeams } from '../helpers';
import type { Player } from '../../model/types';

export function MarketPage({ route }: { route: Route }) {
  const world = useWorld();
  const { act, notify } = useGame();
  const tab = route.query.tab ?? 'fa';
  const mine = userTeams(world);
  const [title, setTitle] = useState(mine[0]?.titleId ?? '');
  const rows = useMemo(() => {
    return Object.values(world.players).filter((p) => {
      if (p.retired) return false;
      if (title && p.titleId !== title) return false;
      if (tab === 'fa') return !p.teamId;
      if (tab === 'listed') return p.listed && !isUserTeamId(world, p.teamId);
      if (tab === 'shortlist') return world.shortlist.includes(p.id);
      return false;
    });
  }, [world, title, tab]);
  const offers = world.offers.filter((o) => o.user).reverse();
  const cols = [
    { key: 'handle', label: 'Player', value: (p: Player) => p.handle, render: (p: Player) => (<span><Flag cc={p.nat} /><PlayerLink p={p} /></span>) },
    { key: 'title', label: 'Title', value: (p: Player) => p.titleId, render: (p: Player) => <TitleTag id={p.titleId} link={false} /> },
    { key: 'role', label: 'Role', value: (p: Player) => p.roles[0], render: (p: Player) => roleName(p.titleId, p.roles[0]) },
    { key: 'age', label: 'Age', align: 'right' as const, value: (p: Player) => age(world, p.birth) },
    { key: 'team', label: 'Club', value: (p: Player) => (p.teamId ? world.teams[p.teamId]?.name ?? '' : ''), render: (p: Player) => <TeamLink t={p.teamId ? world.teams[p.teamId] : undefined} /> },
    { key: 'ca', label: 'CA', align: 'right' as const, value: (p: Player) => p.ca, render: (p: Player) => <Rating v={p.ca} /> },
    { key: 'pa', label: 'PA', align: 'right' as const, value: (p: Player) => potentialRange(world, p)[1], render: (p: Player) => { const [a, b] = potentialRange(world, p); return a === b ? a : `${a}-${b}`; } },
    { key: 'value', label: 'Value', align: 'right' as const, value: (p: Player) => p.value, render: (p: Player) => formatMoney(p.value) },
    { key: 'sal', label: 'Salary', align: 'right' as const, value: (p: Player) => p.contract?.salary ?? 0, render: (p: Player) => (p.contract ? formatMoney(p.contract.salary) : '—') },
  ];
  return (
    <div>
      <PageHeader title="Transfer market" sub={inTransferWindow(world) ? 'Transfer window is OPEN (closes 12 Jan / 1 Jun)' : 'Transfer window closed — free agents can still be signed; listed players accept bids'} />
      <Tabs
        tabs={[
          { id: 'fa', label: 'Free agents' },
          { id: 'listed', label: 'Transfer list' },
          { id: 'shortlist', label: `Shortlist (${world.shortlist.length})` },
          { id: 'offers', label: `Offers (${offers.filter((o) => o.status === 'pending').length})` },
          { id: 'trade', label: 'Trades' },
          { id: 'history', label: 'Transfer history' },
        ]}
        active={tab}
        onChange={(x) => navigate(withQuery(route, { tab: x }))}
      />
      {(tab === 'fa' || tab === 'listed' || tab === 'shortlist') && (
        <Card>
          <div className="row" style={{ marginBottom: 6 }}>
            <select value={title} onChange={(e) => setTitle(e.target.value)} aria-label="Title">
              <option value="">All titles (cross-title recruitment)</option>
              {world.meta.titlesEnabled.map((id) => (
                <option key={id} value={id}>
                  {getTitle(id).name}
                </option>
              ))}
            </select>
            <span className="muted small">Open a player to make an offer or bid.</span>
          </div>
          <DataTable id={`market-${tab}`} rows={rows} rowKey={(p) => p.id} columns={cols} initialSort={{ key: 'ca', desc: true }} filter={(p, q) => p.handle.toLowerCase().includes(q)} onRowClick={(p) => navigate(`/player/${p.id}?tab=contract`)} />
        </Card>
      )}
      {tab === 'offers' && (
        <Gate world={world} perm="transfers">
        <Card title="Offers for your players">
          <table className="table">
            <tbody>
              {offers.map((o) => (
                <tr key={o.id}>
                  <td>{formatDate(o.day)}</td>
                  <td>
                    <PlayerLink p={world.players[o.playerId]} />
                  </td>
                  <td>
                    <TeamLink t={world.teams[o.fromTeamId]} />
                  </td>
                  <td>{formatMoney(o.fee)}</td>
                  <td>{o.status}</td>
                  <td>
                    {o.status === 'pending' && (
                      <>
                        <button className="btn sm" onClick={() => { const r = act((w) => respondToOffer(w, o.id, true), { save: true }); if (r) notify(r.message, r.ok ? 'ok' : 'err'); }}>
                          Accept
                        </button>{' '}
                        <button className="btn ghost sm" onClick={() => { const r = act((w) => respondToOffer(w, o.id, false)); if (r) notify(r.message, 'info'); }}>
                          Reject
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!offers.length && <div className="muted">No offers yet. AI clubs bid for your players during transfer windows.</div>}
        </Card>
        </Gate>
      )}
      {tab === 'trade' && (
        <Gate world={world} perm="transfers">
          <TradeBuilder />
        </Gate>
      )}
      {tab === 'history' && (
        <Card title="Recent transfers">
          <DataTable
            id="transfers"
            rows={[...world.transfers].reverse()}
            rowKey={(x) => x.id}
            filter={(x, q) => (world.players[x.playerId]?.handle ?? '').toLowerCase().includes(q)}
            columns={[
              { key: 'day', label: 'Date', value: (x) => x.day, render: (x) => formatDate(x.day) },
              { key: 'p', label: 'Player', value: (x) => world.players[x.playerId]?.handle ?? '', render: (x) => <PlayerLink p={world.players[x.playerId]} /> },
              { key: 'title', label: 'Title', value: (x) => x.titleId, render: (x) => <TitleTag id={x.titleId} link={false} /> },
              { key: 'type', label: 'Type', value: (x) => x.type },
              { key: 'from', label: 'From', value: (x) => (x.fromTeamId ? world.teams[x.fromTeamId]?.name : '') ?? '', render: (x) => (x.fromTeamId ? <TeamLink t={world.teams[x.fromTeamId]} /> : '—') },
              { key: 'to', label: 'To', value: (x) => (x.toTeamId ? world.teams[x.toTeamId]?.name : '') ?? '', render: (x) => (x.toTeamId ? <TeamLink t={world.teams[x.toTeamId]} /> : '—') },
              { key: 'fee', label: 'Fee', align: 'right', value: (x) => x.fee, render: (x) => (x.fee ? formatMoney(x.fee) : '') },
            ]}
          />
        </Card>
      )}
    </div>
  );
}

function TradeBuilder() {
  const world = useWorld();
  const { act, notify } = useGame();
  const mine = userTeams(world);
  const [teamId, setTeamId] = useState(mine[0]?.id ?? '');
  const team = world.teams[teamId];
  const [otherId, setOtherId] = useState('');
  const [give, setGive] = useState<string[]>([]);
  const [take, setTake] = useState<string[]>([]);
  const [cash, setCash] = useState(0);
  const [givePicks, setGivePicks] = useState<string[]>([]);
  const [takePicks, setTakePicks] = useState<string[]>([]);
  if (!team) return null;
  const others = Object.values(world.teams).filter((t) => t.active && t.titleId === team.titleId && t.id !== team.id && !isUserTeamId(world, t.id));
  const other = world.teams[otherId];
  const league = team.leagueId ? world.leagues[team.leagueId] : null;
  return (
    <Card title="Trade proposal" right={<span className="muted small">{league?.policy.trades ? 'Trades permitted in your league' : 'Your league does not permit trades (franchised leagues allow them by default; edit in Database Editor)'}</span>}>
      <div className="row">
        <select value={teamId} onChange={(e) => { setTeamId(e.target.value); setGive([]); setGivePicks([]); }} aria-label="Your team">
          {mine.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>
        <span>⇄</span>
        <select value={otherId} onChange={(e) => { setOtherId(e.target.value); setTake([]); setTakePicks([]); }} aria-label="Other team">
          <option value="">Choose partner…</option>
          {others.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>
      </div>
      <div className="grid2" style={{ marginTop: 8 }}>
        <div>
          <b>You give</b>
          {team.roster.map((pid) => (
            <label key={pid} style={{ display: 'block' }}>
              <input type="checkbox" checked={give.includes(pid)} onChange={() => setGive((g) => (g.includes(pid) ? g.filter((x) => x !== pid) : [...g, pid]))} /> {world.players[pid].handle} ({world.players[pid].ca.toFixed(0)} CA, {formatMoney(world.players[pid].value)})
            </label>
          ))}
          <PickChecks world={world} teamId={team.id} sel={givePicks} set={setGivePicks} />
          <label className="field">
            <span>+ cash</span>
            <input type="number" value={cash} onChange={(e) => setCash(Number(e.target.value))} />
          </label>
        </div>
        <div>
          <b>You receive</b>
          {other?.roster.map((pid) => (
            <label key={pid} style={{ display: 'block' }}>
              <input type="checkbox" checked={take.includes(pid)} onChange={() => setTake((g) => (g.includes(pid) ? g.filter((x) => x !== pid) : [...g, pid]))} /> {world.players[pid].handle} ({world.players[pid].ca.toFixed(0)} CA, {formatMoney(world.players[pid].value)})
            </label>
          ))}
          {other && <PickChecks world={world} teamId={other.id} sel={takePicks} set={setTakePicks} />}
        </div>
      </div>
      <button
        className="btn"
        disabled={!other || (!take.length && !takePicks.length)}
        onClick={() => {
          const r = act((w) => proposeTrade(w, teamId, give, take, cash, { otherTeamId: otherId, givePicks, takePicks }), { save: true });
          if (r) notify(r.message, r.ok ? 'ok' : 'err');
          if (r?.ok) { setGive([]); setTake([]); setGivePicks([]); setTakePicks([]); setCash(0); }
        }}
      >
        Propose trade
      </button>
    </Card>
  );
}

function PickChecks({ world, teamId, sel, set }: { world: ReturnType<typeof useWorld>; teamId: string; sel: string[]; set: (f: (s: string[]) => string[]) => void }) {
  const picks = picksOwnedBy(world, teamId);
  if (!picks.length) return null;
  return (
    <div style={{ marginTop: 6 }}>
      <span className="muted small">Draft picks</span>
      {picks.map((d) => (
        <label key={d.id} style={{ display: 'block' }}>
          <input type="checkbox" checked={sel.includes(d.id)} onChange={() => set((s) => (s.includes(d.id) ? s.filter((x) => x !== d.id) : [...s, d.id]))} /> {pickLabel(world, d)} <span className="muted">≈{formatMoney(pickValue(world, d))}</span>
        </label>
      ))}
    </div>
  );
}

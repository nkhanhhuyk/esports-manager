import { useState } from 'react';
import { useGame, useWorld } from '../store';
import { Card, CompLink, Flag, Gate, Money, OrgBadge, PageHeader, Stat, TeamLink, TitleTag } from '../components/common';
import { DataTable } from '../components/DataTable';
import { regionName, COUNTRY_BY_CODE } from '../../data/countries';
import { getTitle } from '../../data/titles/catalogue';
import { computeTeamStrength } from '../../sim/common/team';
import { teamPayroll } from '../../systems/contracts';
import { buyLeagueSlot, closeUserDivision, createAcademy, rebrandOrg, slotCost } from '../../systems/org';
import { formatMoney } from '../../core/math';
import { formatDate } from '../../core/date';
import { navigate } from '../router';
import { FACILITY_LABEL } from '../../systems/finance';
import type { FacilityKey } from '../../model/types';
import { FansCard } from '../components/Fans';
import { overallMood } from '../../systems/fans';

export function OrgPage({ id }: { id?: string }) {
  const world = useWorld();
  const org = world.orgs[id ?? world.meta.userOrgId!];
  const { act, notify } = useGame();
  const [expand, setExpand] = useState('');
  const [rename, setRename] = useState('');
  if (!org) return <div>Organisation not found.</div>;
  const teams = org.teams.map((t) => world.teams[t]).filter(Boolean);
  const allTeams = Object.values(world.teams).filter((t) => t.orgId === org.id);
  const trophies = allTeams.flatMap((t) => t.trophies.map((tr) => ({ ...tr, team: t })));
  const isUser = org.id === world.meta.userOrgId;
  const leagues = Object.values(world.leagues).filter((l) => world.meta.titlesEnabled.includes(l.titleId));
  return (
    <div>
      <PageHeader
        badge={<OrgBadge org={org} size={44} />}
        title={org.name}
        sub={
          <span>
            <Flag cc={org.country} />
            {COUNTRY_BY_CODE[org.country]?.name} · {regionName(org.region)} · founded {org.founded} · owner {org.owner.name} ({org.owner.type}) · strategy: {org.strategy}
            {!org.active && ' · DEFUNCT'}
          </span>
        }
      />
      <div className="stats">
        <Stat label="Reputation" value={org.rep.toFixed(0)} />
        <Stat label="Prestige" value={org.prestige.toFixed(0)} />
        <Stat label="Followers" value={org.fans.toLocaleString()} sub={org.fanbase ? `supporter mood ${Math.round(overallMood(org.fanbase))}` : undefined} />
        <Stat label="Balance" value={<Money v={org.balance} />} />
        <Stat label="Divisions" value={teams.length} />
        <Stat label="Trophies" value={trophies.length} sub={`${trophies.filter((t) => t.tier === 'worlds').length} world titles`} />
      </div>
      <Card title="Esports divisions">
        <DataTable
          id="org-divs"
          rows={teams}
          rowKey={(t) => t.id}
          onRowClick={(t) => navigate(`/team/${t.id}`)}
          columns={[
            { key: 'team', label: 'Team', value: (t) => t.name, render: (t) => <TeamLink t={t} /> },
            { key: 'title', label: 'Title', value: (t) => t.titleId, render: (t) => <TitleTag id={t.titleId} /> },
            { key: 'tier', label: 'Tier', value: (t) => t.tier },
            { key: 'league', label: 'League', value: (t) => (t.leagueId ? world.leagues[t.leagueId]?.name : '—') ?? '' },
            { key: 'str', label: 'Strength', align: 'right', value: (t) => computeTeamStrength(world, t), render: (t) => computeTeamStrength(world, t).toFixed(1) },
            { key: 'elo', label: 'Elo', align: 'right', value: (t) => t.elo },
            { key: 'roster', label: 'Roster', align: 'right', value: (t) => t.roster.length },
            { key: 'pay', label: 'Payroll', align: 'right', value: (t) => teamPayroll(world, t), render: (t) => formatMoney(teamPayroll(world, t)) },
            { key: 'troph', label: 'Trophies', align: 'right', value: (t) => t.trophies.length },
          ]}
        />
      </Card>
      <FansCard world={world} org={org} />
      <div className="grid2">
        <Card title="Trophy cabinet (all divisions, all eras)">
          {!trophies.length && <div className="muted">No trophies yet.</div>}
          <table className="table">
            <tbody>
              {[...trophies].sort((a, b) => b.season - a.season).slice(0, 60).map((tr, i) => (
                <tr key={i}>
                  <td>{tr.season}</td>
                  <td>
                    <TitleTag id={tr.titleId} />
                  </td>
                  <td>{world.comps[tr.compId] ? <CompLink c={world.comps[tr.compId]} /> : tr.compName}</td>
                  <td className="muted">{tr.tier}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
        <Card title="Organisation history">
          {[...org.events].reverse().slice(0, 40).map((e, i) => (
            <div key={i} className="small" style={{ padding: '3px 0', borderBottom: '1px solid var(--border)' }}>
              <span className="muted">{formatDate(e.day)}</span> · <span className="tag">{e.type}</span> {e.text}
            </div>
          ))}
          {org.names.length > 1 && (
            <div style={{ marginTop: 8 }}>
              <b>Names:</b> {org.names.map((n) => `${n.name} (${n.season})`).join(' → ')}
            </div>
          )}
        </Card>
      </div>
      <div className="grid2">
        <Card title="Facilities">
          {(Object.keys(org.facilities) as FacilityKey[]).map((k) => (
            <div key={k} className="attr-row">
              <span>{FACILITY_LABEL[k]}</span>
              <b>{org.facilities[k]}/10</b>
            </div>
          ))}
        </Card>
        <Card title="Sponsors">
          {org.sponsors.map((s) => (
            <div key={s.id} className="attr-row">
              <span>{s.name}</span>
              <span>
                {formatMoney(s.perMonth)}/mo until {s.untilSeason}
              </span>
            </div>
          ))}
        </Card>
      </div>
      {isUser && (
        <Gate world={world} perm="expansion">
        <Card title="Expansion & structure">
          <div className="row" style={{ alignItems: 'flex-end' }}>
            <label className="field">
              <span>Buy a league slot (enter a new title or add a challenger team)</span>
              <select value={expand} onChange={(e) => setExpand(e.target.value)}>
                <option value="">Select league…</option>
                {leagues.map((l) => (
                  <option key={l.id} value={l.id}>
                    {getTitle(l.titleId).short} · {l.name} (T{l.tier}) — {formatMoney(slotCost(world, l))}
                  </option>
                ))}
              </select>
            </label>
            <button
              className="btn"
              disabled={!expand}
              onClick={() => {
                const r = act((w) => buyLeagueSlot(w, org.id, expand), { save: true });
                if (r) notify(r.message, r.ok ? 'ok' : 'err');
                if (r?.teamId) navigate(`/team/${r.teamId}`);
              }}
            >
              Buy slot
            </button>
          </div>
          <div className="row" style={{ marginTop: 10 }}>
            {teams
              .filter((t) => t.tier === 'main')
              .map((t) => (
                <button key={t.id} className="btn ghost sm" onClick={() => { const r = act((w) => createAcademy(w, t.id), { save: true }); if (r) notify(r.message, r.ok ? 'ok' : 'err'); }}>
                  Found {getTitle(t.titleId).short} academy
                </button>
              ))}
          </div>
          <div className="row" style={{ marginTop: 10 }}>
            {teams.map((t) => (
              <button
                key={t.id}
                className="btn ghost sm"
                onClick={() => {
                  if (!confirm(`Close ${t.name}? Players will be released and the slot sold.`)) return;
                  const r = act((w) => closeUserDivision(w, t.id), { save: true });
                  if (r) notify(r.message, r.ok ? 'ok' : 'err');
                }}
              >
                Close {t.name} ({getTitle(t.titleId).short})
              </button>
            ))}
          </div>
          <div className="row" style={{ marginTop: 10, alignItems: 'flex-end' }}>
            <label className="field">
              <span>Rebrand organisation (history is preserved)</span>
              <input value={rename} onChange={(e) => setRename(e.target.value)} placeholder="New name" />
            </label>
            <button className="btn ghost" disabled={rename.length < 3} onClick={() => { act((w) => rebrandOrg(w, org.id, rename, rename.split(' ').map((x) => x[0]).join('').toUpperCase()), { save: true }); setRename(''); }}>
              Rebrand
            </button>
          </div>
        </Card>
        </Gate>
      )}
      <Card title="All teams ever operated">
        <div className="row">
          {allTeams.map((t) => (
            <span key={t.id} className="chip">
              <TeamLink t={t} /> {getTitle(t.titleId).short} {t.active ? '' : '(closed)'}
            </span>
          ))}
        </div>
      </Card>
    </div>
  );
}

import { useMemo, useState } from 'react';
import { useWorld } from '../store';
import { Card, Flag, PageHeader, PlayerLink, Rating, TeamLink, TitleTag, age } from '../components/common';
import { DataTable } from '../components/DataTable';
import { getTitle } from '../../data/titles/catalogue';
import { COUNTRY_BY_CODE, REGIONS } from '../../data/countries';
import { navigate, withQuery, type Route } from '../router';
import { potentialRange } from '../../systems/scouting';
import { formatMoney } from '../../core/math';
import { ATTRS, ATTR_GROUPS, GROUP_INDICES, summaries, INPUT_LABEL, INPUTS } from '../../model/attributes';
import { roleName, seasonAgg } from '../helpers';
import type { Player } from '../../model/types';
import { LineChart, PALETTE } from '../components/Charts';

export function PlayersPage({ route }: { route: Route }) {
  const world = useWorld();
  const q = route.query;
  const [sel, setSel] = useState<string[]>([]);
  const title = q.title ?? '';
  const status = q.status ?? 'active';
  const region = q.region ?? '';
  const role = q.role ?? '';
  const maxAge = Number(q.maxAge ?? 99);
  const rows = useMemo(
    () =>
      Object.values(world.players).filter((p) => {
        if (status === 'retired') {
          if (!p.retired) return false;
        } else if (p.retired) return false;
        if (title && p.titleId !== title) return false;
        if (role && p.roles[0] !== role) return false;
        if (region && COUNTRY_BY_CODE[p.nat]?.region !== region) return false;
        if (status === 'fa' && p.teamId) return false;
        if (status === 'contracted' && !p.teamId) return false;
        if (status === 'expiring' && (!p.contract || p.contract.endSeason !== world.season)) return false;
        if (status === 'listed' && !p.listed) return false;
        if (maxAge < 99 && age(world, p.birth) > maxAge) return false;
        return true;
      }),
    [world, title, status, region, role, maxAge],
  );
  const set = (patch: Record<string, string | undefined>) => navigate(withQuery(route, patch));
  return (
    <div>
      <PageHeader title="Players" sub={`${rows.length.toLocaleString()} players match`} right={sel.length >= 2 ? <button className="btn" onClick={() => navigate(`/compare?ids=${sel.join(',')}`)}>Compare {sel.length}</button> : <span className="muted small">Tick players to compare</span>} />
      <Card>
        <div className="row" style={{ marginBottom: 8 }}>
          <select value={title} onChange={(e) => set({ title: e.target.value || undefined, role: undefined })} aria-label="Title">
            <option value="">All titles</option>
            {world.meta.titlesEnabled.map((id) => (
              <option key={id} value={id}>
                {getTitle(id).name}
              </option>
            ))}
          </select>
          {title && (
            <select value={role} onChange={(e) => set({ role: e.target.value || undefined })} aria-label="Role">
              <option value="">All roles</option>
              {getTitle(title).roles.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.name}
                </option>
              ))}
            </select>
          )}
          <select value={status} onChange={(e) => set({ status: e.target.value })} aria-label="Status">
            <option value="active">All active</option>
            <option value="fa">Free agents</option>
            <option value="contracted">Under contract</option>
            <option value="expiring">Contract expiring</option>
            <option value="listed">Transfer listed</option>
            <option value="retired">Retired</option>
          </select>
          <select value={region} onChange={(e) => set({ region: e.target.value || undefined })} aria-label="Region">
            <option value="">All nationalities</option>
            {REGIONS.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
          <label className="row">
            Max age <input type="number" style={{ width: 60 }} value={maxAge === 99 ? '' : maxAge} onChange={(e) => set({ maxAge: e.target.value || undefined })} />
          </label>
        </div>
        <DataTable
          id="players-dir"
          rows={rows}
          rowKey={(p) => p.id}
          initialSort={{ key: 'ca', desc: true }}
          filter={(p, s) => p.handle.toLowerCase().includes(s) || `${p.first} ${p.last}`.toLowerCase().includes(s)}
          onRowClick={(p) => navigate(`/player/${p.id}`)}
          pageSize={60}
          columns={[
            { key: 'sel', label: '✓', render: (p) => <input type="checkbox" checked={sel.includes(p.id)} onClick={(e) => e.stopPropagation()} onChange={() => setSel((s) => (s.includes(p.id) ? s.filter((x) => x !== p.id) : [...s, p.id].slice(-4)))} aria-label={`Select ${p.handle}`} /> },
            { key: 'handle', label: 'Player', value: (p) => p.handle, render: (p) => (<span><Flag cc={p.nat} /><PlayerLink p={p} /></span>) },
            { key: 'name', label: 'Name', value: (p) => `${p.first} ${p.last}`, hidden: true },
            { key: 'age', label: 'Age', align: 'right', value: (p) => age(world, p.birth) },
            { key: 'title', label: 'Title', value: (p) => p.titleId, render: (p) => <TitleTag id={p.titleId} link={false} /> },
            { key: 'role', label: 'Role', value: (p) => p.roles[0], render: (p) => roleName(p.titleId, p.roles[0]) },
            { key: 'team', label: 'Team', value: (p) => (p.teamId ? world.teams[p.teamId]?.name : '') ?? '', render: (p) => <TeamLink t={p.teamId ? world.teams[p.teamId] : undefined} /> },
            { key: 'ca', label: 'CA', align: 'right', value: (p) => p.ca, render: (p) => <Rating v={p.ca} /> },
            { key: 'pa', label: 'PA', align: 'right', value: (p) => potentialRange(world, p)[1], render: (p) => { const [a, b] = potentialRange(world, p); return a === b ? a : `${a}-${b}`; } },
            { key: 'value', label: 'Value', align: 'right', value: (p) => p.value, render: (p) => formatMoney(p.value) },
            { key: 'sal', label: 'Salary', align: 'right', value: (p) => p.contract?.salary ?? 0, render: (p) => (p.contract ? formatMoney(p.contract.salary) : '—') },
            { key: 'end', label: 'Until', align: 'right', value: (p) => p.contract?.endSeason ?? 0, render: (p) => p.contract?.endSeason ?? 'FA' },
            { key: 'rep', label: 'Fame', align: 'right', value: (p) => p.rep, render: (p) => p.rep.toFixed(0), hidden: true },
            { key: 'form', label: 'Form', align: 'right', value: (p) => p.form, render: (p) => p.form.toFixed(1), hidden: true },
            { key: 'gp', label: 'GP (season)', align: 'right', value: (p) => seasonAgg(p, world.season).gp, hidden: true },
            { key: 'rating', label: 'Rating (season)', align: 'right', value: (p) => seasonAgg(p, world.season).rating, render: (p) => seasonAgg(p, world.season).rating.toFixed(2), hidden: true },
          ]}
        />
      </Card>
    </div>
  );
}

export function ComparePage({ route }: { route: Route }) {
  const world = useWorld();
  const ids = (route.query.ids ?? '').split(',').filter((id) => world.players[id]);
  const ps: Player[] = ids.map((id) => world.players[id]);
  if (ps.length < 2) return <div>Select at least two players from the Players directory.</div>;
  const sums = ps.map((p) => summaries(p.attrs));
  return (
    <div>
      <PageHeader title="Player comparison" sub={ps.map((p) => p.handle).join(' vs ')} />
      <Card title="Development (Current Ability by season)">
        <LineChart series={ps.map((p, i) => ({ name: p.handle, color: PALETTE[i], points: p.caHistory.map((h) => ({ x: h.season, y: h.ca })) }))} />
      </Card>
      <Card title="Summary">
        <table className="table">
          <thead>
            <tr>
              <th></th>
              {ps.map((p) => (
                <th key={p.id}>
                  <PlayerLink p={p} />
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>Title / role</td>
              {ps.map((p) => (
                <td key={p.id}>
                  <TitleTag id={p.titleId} /> {roleName(p.titleId, p.roles[0])}
                </td>
              ))}
            </tr>
            <tr>
              <td>Age</td>
              {ps.map((p) => (
                <td key={p.id}>{age(world, p.birth)}</td>
              ))}
            </tr>
            <tr>
              <td>CA</td>
              {ps.map((p) => (
                <td key={p.id}>
                  <Rating v={p.ca} />
                </td>
              ))}
            </tr>
            {Object.keys(sums[0]).map((k) => (
              <tr key={k}>
                <td>{k}</td>
                {sums.map((s, i) => (
                  <td key={i}>
                    <Rating v={(s as Record<string, number>)[k]} />
                  </td>
                ))}
              </tr>
            ))}
            {INPUTS.map((inp, ii) => (
              <tr key={inp}>
                <td>{INPUT_LABEL[inp]}</td>
                {ps.map((p) => (
                  <td key={p.id}>{p.input[ii].toFixed(0)}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
      <Card title="All 86 attributes">
        <table className="table">
          <tbody>
            {ATTR_GROUPS.map((g) => (
              <>
                <tr key={g}>
                  <td colSpan={ps.length + 1}>
                    <b>{g}</b>
                  </td>
                </tr>
                {GROUP_INDICES[g].map((i) => (
                  <tr key={i}>
                    <td>{ATTRS[i].name}</td>
                    {ps.map((p) => {
                      const best = Math.max(...ps.map((x) => x.attrs[i]));
                      return (
                        <td key={p.id} style={{ fontWeight: p.attrs[i] === best ? 800 : 400 }}>
                          <Rating v={p.attrs[i]} />
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </>
            ))}
          </tbody>
        </table>
      </Card>
    </div>
  );
}

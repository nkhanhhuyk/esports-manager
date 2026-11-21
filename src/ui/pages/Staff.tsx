import { RichText } from '../components/RichText';
import { useMemo, useState } from 'react';
import { useGame, useWorld } from '../store';
import { Card, Flag, Gate, Money, PageHeader, Rating, Stat, StaffLink, TeamLink, TitleTag, age, OrgLink, PlayerLink, Provenance } from '../components/common';
import { DataTable } from '../components/DataTable';
import { STAFF_ROLE_LABEL } from '../../gen/staff';
import { STAFF_ATTRS } from '../../model/attributes';
import { expectedStaffSalary, staffQuality } from '../../systems/contracts';
import { hireStaff, releaseStaff } from '../../systems/staff';
import { navigate } from '../router';
import { userTeams } from '../helpers';
import { formatMoney } from '../../core/math';
import { formatDate } from '../../core/date';
import { staffLegacy } from '../../systems/awards';
import type { StaffRole } from '../../model/types';

export function StaffDirectory() {
  const world = useWorld();
  const [role, setRole] = useState('');
  const [onlyFree, setOnlyFree] = useState(false);
  const rows = useMemo(() => Object.values(world.staff).filter((s) => !s.retired && (!role || s.role === role) && (!onlyFree || (!s.teamId && !s.contract))), [world, role, onlyFree]);
  return (
    <div>
      <PageHeader title="Staff" sub="Coaches, analysts, scouts and support staff across the universe" />
      <Card>
        <div className="row" style={{ marginBottom: 6 }}>
          <select value={role} onChange={(e) => setRole(e.target.value)} aria-label="Role">
            <option value="">All roles</option>
            {Object.entries(STAFF_ROLE_LABEL).map(([k, v]) => (
              <option key={k} value={k}>
                {v}
              </option>
            ))}
          </select>
          <label>
            <input type="checkbox" checked={onlyFree} onChange={(e) => setOnlyFree(e.target.checked)} /> Unattached only
          </label>
        </div>
        <DataTable
          id="staff-dir"
          rows={rows}
          rowKey={(s) => s.id}
          initialSort={{ key: 'q', desc: true }}
          filter={(s, q) => `${s.first} ${s.last} ${s.handle ?? ''}`.toLowerCase().includes(q)}
          onRowClick={(s) => navigate(`/staff/${s.id}`)}
          columns={[
            { key: 'name', label: 'Name', value: (s) => `${s.first} ${s.last}`, render: (s) => (<span><Flag cc={s.nat} /><StaffLink s={s} /></span>) },
            { key: 'role', label: 'Role', value: (s) => STAFF_ROLE_LABEL[s.role] },
            { key: 'title', label: 'Title', value: (s) => s.titleId ?? '', render: (s) => (s.titleId ? <TitleTag id={s.titleId} link={false} /> : '—') },
            { key: 'team', label: 'Employer', value: (s) => (s.teamId ? world.teams[s.teamId]?.name : s.orgId ? world.orgs[s.orgId]?.name : '') ?? '', render: (s) => (s.teamId ? <TeamLink t={world.teams[s.teamId]} /> : s.orgId ? <OrgLink org={world.orgs[s.orgId]} /> : <span className="muted">Unattached</span>) },
            { key: 'age', label: 'Age', align: 'right', value: (s) => age(world, s.birth) },
            { key: 'q', label: 'Quality', align: 'right', value: (s) => staffQuality(s), render: (s) => <Rating v={staffQuality(s)} /> },
            { key: 'rep', label: 'Rep', align: 'right', value: (s) => s.rep, render: (s) => s.rep.toFixed(0) },
            { key: 'ex', label: 'Ex-pro', value: (s) => (s.formerPlayerId ? 1 : 0), render: (s) => (s.formerPlayerId ? '✓' : '') },
          ]}
        />
      </Card>
    </div>
  );
}

export function StaffPage({ id }: { id: string }) {
  const world = useWorld();
  const { act, notify } = useGame.getState();
  const s = world.staff[id];
  const mine = userTeams(world);
  const [teamId, setTeamId] = useState(mine[0]?.id ?? '');
  const [salary, setSalary] = useState(() => (s && mine[0] ? Math.round(expectedStaffSalary(world, s, mine[0].titleId, mine[0].region)) : 0));
  if (!s) return <div>Staff member not found.</div>;
  const isMine = s.orgId === world.meta.userOrgId;
  const free = !s.teamId && !s.contract && !s.retired;
  return (
    <div>
      <PageHeader
        title={<span><Flag cc={s.nat} />{s.handle && (s.first || s.last) ? `${s.handle} — ` : s.handle ?? ''}{s.first} {s.last}</span>}
        sub={
          <span>
            {STAFF_ROLE_LABEL[s.role as StaffRole]} · {s.titleId && <TitleTag id={s.titleId} />} · {s.teamId ? <TeamLink t={world.teams[s.teamId]} /> : s.orgId ? <OrgLink org={world.orgs[s.orgId]} /> : 'Unattached'} · age {age(world, s.birth)}
            {s.formerPlayerId && (<> · former pro <PlayerLink p={world.players[s.formerPlayerId]} /></>)}
            {s.retired && ' · retired'}
            <Provenance world={world} real={s.real} kind="staff member" />
          </span>
        }
      />
      <div className="stats">
        <Stat label="Quality" value={<Rating v={staffQuality(s)} />} />
        <Stat label="Reputation" value={s.rep.toFixed(0)} />
        <Stat label="Salary" value={s.contract ? <Money v={s.contract.salary} /> : '—'} sub={s.contract ? `until ${s.contract.endSeason}` : undefined} />
        <Stat label="Legacy" value={staffLegacy(s).toFixed(0)} />
      </div>
      <div className="grid2">
        <Card title="Attributes">
          {STAFF_ATTRS.map(([k, label]) => (
            <div key={k} className="attr-row">
              <span>{label}</span>
              <Rating v={s.attrs[k]} />
            </div>
          ))}
        </Card>
        <div>
          <Card title="Title familiarity">
            {Object.entries(s.titleFam).sort((a, b) => b[1] - a[1]).map(([t, v]) => (
              <div key={t} className="attr-row">
                <TitleTag id={t} />
                <Rating v={v} />
              </div>
            ))}
          </Card>
          {free && mine.length > 0 && (
            <Gate world={world} perm="staff">
            <Card title="Hire">
              <div className="row" style={{ alignItems: 'flex-end' }}>
                <label className="field">
                  <span>Team</span>
                  <select value={teamId} onChange={(e) => setTeamId(e.target.value)}>
                    {mine.map((t) => (
                      <option key={t.id} value={t.id}>
                        {t.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="field">
                  <span>Salary (expects ~{formatMoney(world.teams[teamId] ? expectedStaffSalary(world, s, world.teams[teamId].titleId, world.teams[teamId].region) : 0)})</span>
                  <input type="number" value={salary} onChange={(e) => setSalary(Number(e.target.value))} />
                </label>
                <button className="btn" onClick={() => { const r = act((w) => hireStaff(w, s.id, teamId, salary, 2), { save: true }); if (r) notify(r.message, r.ok ? 'ok' : 'err'); }}>
                  Offer 2-season contract
                </button>
              </div>
            </Card>
            </Gate>
          )}
          {isMine && (
            <Gate world={world} perm="staff">
            <Card title="Contract">
              <button className="btn ghost red" onClick={() => { if (confirm('Release this staff member?')) { const r = act((w) => releaseStaff(w, s.id), { save: true }); if (r) notify(r.message, r.ok ? 'ok' : 'err'); } }}>
                Release (50% salary compensation)
              </button>
            </Card>
            </Gate>
          )}
          <Card title="Career">
            {[...s.career].reverse().map((c, i) => (
              <div key={i} className="small attr-row">
                <span>{formatDate(c.day)} · {c.type}</span>
                <span>{c.teamId ? <TeamLink t={world.teams[c.teamId]} /> : c.note ? <RichText world={world} text={c.note} /> : ''}</span>
              </div>
            ))}
          </Card>
          <Card title="Honours">
            {s.awards.map((a, i) => (
              <div key={i} className="small">{a.season} · {a.label}</div>
            ))}
            {!s.awards.length && <div className="muted">None yet.</div>}
          </Card>
        </div>
      </div>
    </div>
  );
}

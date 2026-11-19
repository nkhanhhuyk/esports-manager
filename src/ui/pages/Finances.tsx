import { useGame, useWorld } from '../store';
import { Card, Gate, Money, PageHeader, Stat, TeamLink, TitleTag } from '../components/common';
import { DataTable } from '../components/DataTable';
import { BarChart, LineChart } from '../components/Charts';
import { FACILITY_LABEL, facilityUpgradeCost, monthlyBreakdown, upgradeFacility } from '../../systems/finance';
import { formatMoney } from '../../core/math';
import { formatDate } from '../../core/date';
import { userTeams } from '../helpers';
import { staffPayroll, teamPayroll } from '../../systems/contracts';
import type { FacilityKey } from '../../model/types';
import type { Route } from '../router';

export function FinancesPage({ route }: { route: Route }) {
  const world = useWorld();
  const { act } = useGame();
  const org = world.orgs[world.meta.userOrgId!];
  const mb = monthlyBreakdown(world, org);
  const rev = Object.values(mb.revenue).reduce((a, b) => a + b, 0);
  const exp = Object.values(mb.expenses).reduce((a, b) => a + b, 0);
  const prizes = org.ledger.filter((l) => l.kind === 'prize').reduce((a, l) => a + l.amount, 0);
  void route;
  return (
    <div>
      <PageHeader title="Finances" sub={`${org.name} — organisation-wide accounts with per-division budgets`} />
      <div className="stats">
        <Stat label="Balance" value={<Money v={org.balance} />} />
        <Stat label="Monthly revenue (proj.)" value={<Money v={rev} />} />
        <Stat label="Monthly costs (proj.)" value={<Money v={exp} />} />
        <Stat label="Net / month" value={<Money v={rev - exp} />} />
        <Stat label="Prize money (ledger)" value={<Money v={prizes} />} />
        <Stat label="Months in debt" value={org.debtMonths} />
      </div>
      <div className="grid2">
        <Card title="Revenue breakdown (projected month)">
          <BarChart items={Object.entries(mb.revenue).map(([k, v]) => ({ label: k, value: v, color: 'var(--accent2)' }))} format={(v) => formatMoney(v)} />
        </Card>
        <Card title="Expenditure breakdown (projected month)">
          <BarChart items={Object.entries(mb.expenses).map(([k, v]) => ({ label: k, value: v, color: 'var(--bad)' }))} format={(v) => formatMoney(v)} />
        </Card>
      </div>
      <Card title="Balance history">
        <LineChart series={[{ name: 'Balance', color: 'var(--accent)', points: org.monthly.map((m) => ({ x: m.month, y: m.balance })) }]} xLabel={(x) => `${Math.floor(x / 12)}-${String((x % 12) + 1).padStart(2, '0')}`} yFormat={(y) => formatMoney(y)} />
      </Card>
      <Gate world={world} perm="finance">
      <Card title="Division budgets">
        <table className="table">
          <thead>
            <tr>
              <th>Division</th>
              <th>Title</th>
              <th>Player payroll</th>
              <th>Salary budget</th>
              <th>Staff</th>
              <th>Adjust budget</th>
            </tr>
          </thead>
          <tbody>
            {userTeams(world).map((t) => (
              <tr key={t.id}>
                <td>
                  <TeamLink t={t} />
                </td>
                <td>
                  <TitleTag id={t.titleId} />
                </td>
                <td>{formatMoney(teamPayroll(world, t))}</td>
                <td>{formatMoney(t.salaryBudget)}</td>
                <td>{formatMoney(staffPayroll(world, t))}</td>
                <td>
                  <button className="btn ghost sm" onClick={() => act((w) => (w.teams[t.id].salaryBudget = Math.round(w.teams[t.id].salaryBudget * 0.9)))}>
                    −10%
                  </button>{' '}
                  <button className="btn ghost sm" onClick={() => act((w) => (w.teams[t.id].salaryBudget = Math.round(w.teams[t.id].salaryBudget * 1.1)))}>
                    +10%
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
      </Gate>
      <Card title="Ledger">
        <DataTable
          id="ledger"
          rows={[...org.ledger].reverse()}
          rowKey={(l) => `${l.day}-${l.kind}-${l.amount}-${l.note ?? ''}-${l.teamId ?? ''}`}
          columns={[
            { key: 'day', label: 'Date', value: (l) => l.day, render: (l) => formatDate(l.day) },
            { key: 'kind', label: 'Type', value: (l) => l.kind },
            { key: 'amount', label: 'Amount', align: 'right', value: (l) => l.amount, render: (l) => <Money v={l.amount} /> },
            { key: 'note', label: 'Note', value: (l) => l.note ?? '' },
          ]}
        />
      </Card>
    </div>
  );
}

const EFFECT: Record<FacilityKey, string> = {
  training: 'Faster attribute & proficiency development',
  analytics: 'Meta adaptation, opponent preparation',
  academy: 'Development of academy and youth players',
  equipment: 'Training quality, input adaptation',
  housing: 'Fatigue recovery, morale',
  performance: 'Recovery, injury and burnout prevention',
};

export function FacilitiesPage() {
  const world = useWorld();
  const { act, notify } = useGame();
  const org = world.orgs[world.meta.userOrgId!];
  return (
    <div>
      <PageHeader title="Facilities" sub={`Balance ${formatMoney(org.balance)} — upgrades raise monthly upkeep`} />
      <Gate world={world} perm="facilities">
      <div className="grid3">
        {(Object.keys(org.facilities) as FacilityKey[]).map((k) => (
          <Card key={k} title={FACILITY_LABEL[k]}>
            <div className="stat-value">{org.facilities[k]} / 10</div>
            <div className="muted small">{EFFECT[k]}</div>
            <button
              className="btn sm"
              style={{ marginTop: 8 }}
              disabled={org.facilities[k] >= 10}
              onClick={() => {
                const r = act((w) => upgradeFacility(w, w.orgs[org.id], k), { save: true });
                if (r) notify(r.ok ? `${FACILITY_LABEL[k]} upgraded.` : r.reason ?? 'Failed', r.ok ? 'ok' : 'err');
              }}
            >
              Upgrade ({formatMoney(facilityUpgradeCost(org, k))})
            </button>
          </Card>
        ))}
      </div>
      </Gate>
    </div>
  );
}

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
import { useState } from 'react';
import { cashForecast, moveBudget, setDepartmentBudget, treasurySummary } from '../../systems/treasury';
import { salaryBudgetFor } from '../../game/rollover';
import { can } from '../../systems/career';

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
      <TreasuryPanel />
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

/** Treasury: cash vs spending authority vs commitments, department budgets and the cash forecast. */
function TreasuryPanel() {
  const world = useWorld();
  const { act, notify } = useGame();
  const org = world.orgs[world.meta.userOrgId!];
  const t = treasurySummary(world, org);
  const forecast = cashForecast(world, org, 12);
  const shortfall = forecast.find((m) => m.cash < 0);
  const [edit, setEdit] = useState<Record<string, number>>({});
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState(10000);
  const canEdit = can(world, 'finance');
  const run = (fn: Parameters<typeof act>[0]) => {
    const r = act(fn, { save: true }) as { ok: boolean; message: string } | undefined;
    if (r) notify(r.message, r.ok ? 'ok' : 'err');
  };
  const deps = t.departments;
  return (
    <>
      <Card title="Treasury — cash, authority and commitments" right={<span className="muted small">budgets authorise spending; only payments move cash</span>}>
        <div className="stats">
          <Stat label="Cash (group)" value={<Money v={t.cash} />} />
          <Stat label="Revenue, next 12 months" value={<Money v={t.revenue12} />} sub="contracted sponsors + current media/merch" />
          <Stat label="Fixed costs, 12 months" value={<Money v={t.fixed12} />} sub="staff, facilities, operations" />
          <Stat label="Fundable wage authority" value={<Money v={t.fundable} />} sub="revenue + 50% of cash − fixed costs" />
          <Stat label="Allocated to departments" value={<Money v={t.allocated} />} />
          <Stat label="Unallocated reserve" value={<Money v={t.unallocated} />} sub={t.unallocated < 0 ? 'over-committed!' : 'available to allocate'} />
          <Stat label="Salary commitments" value={<Money v={t.commitments} />} sub="owed until contracts end" />
        </div>
        <table className="table" style={{ marginTop: 8 }}>
          <thead>
            <tr>
              <th>Department</th>
              <th>Title</th>
              <th className="num">Payroll</th>
              <th className="num">Wage budget</th>
              <th className="num">Headroom</th>
              <th className="num">Commitments</th>
              <th className="num">Board view</th>
              {canEdit && <th>Set budget</th>}
            </tr>
          </thead>
          <tbody>
            {deps.map((d) => (
              <tr key={d.team.id}>
                <td>
                  <TeamLink t={d.team} />
                </td>
                <td>
                  <TitleTag id={d.team.titleId} />
                </td>
                <td className="num">{formatMoney(d.payroll)}</td>
                <td className="num">{formatMoney(d.budget)}</td>
                <td className={`num ${d.headroom < 0 ? 'neg' : ''}`}>{formatMoney(d.headroom)}</td>
                <td className="num">{formatMoney(d.commitment)}</td>
                <td className="num muted">{formatMoney(salaryBudgetFor(world, d.team))}</td>
                {canEdit && (
                  <td>
                    <span className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
                      <input type="number" aria-label={`Budget for ${d.team.name}`} style={{ width: 110 }} value={edit[d.team.id] ?? d.budget} onChange={(e) => setEdit((x) => ({ ...x, [d.team.id]: Number(e.target.value) }))} />
                      <button className="btn ghost sm" onClick={() => run((w) => setDepartmentBudget(w, d.team.id, edit[d.team.id] ?? d.budget))}>
                        Set
                      </button>
                    </span>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
        {canEdit ? (
          deps.length > 1 && (
            <div className="row" style={{ marginTop: 8 }}>
              <span className="small">Move budget</span>
              <select aria-label="From department" value={from} onChange={(e) => setFrom(e.target.value)}>
                <option value="">from…</option>
                {deps.map((d) => (
                  <option key={d.team.id} value={d.team.id}>
                    {d.team.name}
                  </option>
                ))}
              </select>
              <select aria-label="To department" value={to} onChange={(e) => setTo(e.target.value)}>
                <option value="">to…</option>
                {deps.map((d) => (
                  <option key={d.team.id} value={d.team.id}>
                    {d.team.name}
                  </option>
                ))}
              </select>
              <input type="number" aria-label="Amount to move" style={{ width: 110 }} value={amount} onChange={(e) => setAmount(Number(e.target.value))} />
              <button className="btn ghost sm" disabled={!from || !to} onClick={() => run((w) => moveBudget(w, from, to, amount))}>
                Move
              </button>
              <span className="muted small">internal transfer — group cash is unchanged</span>
            </div>
          )
        ) : (
          <div className="muted small" style={{ marginTop: 6 }}>Your role has no finance authority: the board sets department budgets each season.</div>
        )}
      </Card>
      <Card title="Cash forecast (next 12 months)" right={shortfall ? <span className="neg small">shortfall projected in {shortfall.label}</span> : <span className="pos small">no shortfall projected</span>}>
        <LineChart series={[{ name: 'Projected cash', color: shortfall ? 'var(--bad)' : 'var(--accent)', points: forecast.map((m, i) => ({ x: i, y: m.cash })) }]} xLabel={(x) => forecast[x]?.label ?? ''} yFormat={(y) => formatMoney(y)} />
        <div className="dt-scroll">
          <table className="table small">
            <thead>
              <tr>
                <th>Month</th>
                <th className="num">Income</th>
                <th className="num">Committed costs</th>
                <th className="num">Net</th>
                <th className="num">Cash</th>
              </tr>
            </thead>
            <tbody>
              {forecast.map((m) => (
                <tr key={m.label}>
                  <td>{m.label}</td>
                  <td className="num">{formatMoney(m.revenue)}</td>
                  <td className="num">{formatMoney(m.expenses)}</td>
                  <td className={`num ${m.net < 0 ? 'neg' : 'pos'}`}>{formatMoney(m.net)}</td>
                  <td className={`num ${m.cash < 0 ? 'neg' : ''}`}>{formatMoney(m.cash)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="muted small">Committed contracts and sponsor deals only: excludes prize money, transfers, bonuses, renewals and new signings. Salaries drop out when contracts end; sponsor income when deals end.</div>
      </Card>
      {(org.budgetLog?.length ?? 0) > 0 && (
        <Card title="Budget changes (audit log)">
          <table className="table small">
            <tbody>
              {[...(org.budgetLog ?? [])].reverse().slice(0, 15).map((b, i) => (
                <tr key={i}>
                  <td>{formatDate(b.day)}</td>
                  <td>{b.teamId ? world.teams[b.teamId]?.name ?? '—' : 'Group reserve'}</td>
                  <td>→</td>
                  <td>{b.toTeamId ? world.teams[b.toTeamId]?.name ?? '—' : 'Group reserve'}</td>
                  <td className="num">{formatMoney(b.amount)}</td>
                  <td className="muted">{b.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </>
  );
}

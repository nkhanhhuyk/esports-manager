import { useWorld } from '../store';
import { Card, CompLink, PageHeader, PlayerLink, Stat, TeamLink, TitleTag, Tabs, Money, OrgBadge } from '../components/common';
import { DataTable } from '../components/DataTable';
import { getTitle } from '../../data/titles/catalogue';
import { regionName } from '../../data/countries';
import { formatDate, formatShort } from '../../core/date';
import { navigate, withQuery, type Route } from '../router';
import { formatFor, sortStandings } from '../../competition/stages';
import { prizeFor } from '../../systems/awards';
import { fixtureLabel, isUserTeamId, scoreLabel } from '../helpers';
import type { BracketNode, Competition, Stage } from '../../model/types';
import { formatMoney } from '../../core/math';

export function CompetitionsPage({ route }: { route: Route }) {
  const world = useWorld();
  const title = route.query.title ?? '';
  const tier = route.query.tier ?? '';
  const comps = Object.values(world.comps).filter((c) => (!title || c.titleId === title) && (!tier || c.tier === tier));
  return (
    <div>
      <PageHeader title="Competitions" sub="Regional leagues, challenger circuits, international events and world championships" />
      <div className="row" style={{ marginBottom: 8 }}>
        <select value={title} onChange={(e) => navigate(withQuery(route, { title: e.target.value || undefined }))} aria-label="Title">
          <option value="">All titles</option>
          {world.meta.titlesEnabled.map((id) => (
            <option key={id} value={id}>
              {getTitle(id).name}
            </option>
          ))}
        </select>
        <select value={tier} onChange={(e) => navigate(withQuery(route, { tier: e.target.value || undefined }))} aria-label="Tier">
          <option value="">All tiers</option>
          {['league', 'challenger', 'international', 'worlds'].map((t) => (
            <option key={t}>{t}</option>
          ))}
        </select>
      </div>
      <Card>
        <DataTable
          id="comps"
          rows={comps}
          rowKey={(c) => c.id}
          initialSort={{ key: 'start', desc: false }}
          filter={(c, q) => c.name.toLowerCase().includes(q)}
          onRowClick={(c) => navigate(`/comp/${c.id}`)}
          highlight={(c) => c.teamIds.some((t) => isUserTeamId(world, t))}
          columns={[
            { key: 'name', label: 'Competition', value: (c) => c.name, render: (c) => <CompLink c={c} /> },
            { key: 'title', label: 'Title', value: (c) => c.titleId, render: (c) => <TitleTag id={c.titleId} link={false} /> },
            { key: 'season', label: 'Season', align: 'right', value: (c) => c.season },
            { key: 'tier', label: 'Tier', value: (c) => c.tier },
            { key: 'region', label: 'Region', value: (c) => (c.region ? regionName(c.region) : 'International') },
            { key: 'start', label: 'Start', value: (c) => c.startDay, render: (c) => formatShort(c.startDay) },
            { key: 'status', label: 'Status', value: (c) => c.status },
            { key: 'teams', label: 'Teams', align: 'right', value: (c) => c.teamIds.length },
            { key: 'prize', label: 'Prize pool', align: 'right', value: (c) => c.prizePool, render: (c) => formatMoney(c.prizePool) },
            { key: 'champ', label: 'Champion', value: (c) => (c.status === 'done' ? world.teams[c.placements[0]]?.name : '') ?? '', render: (c) => (c.status === 'done' ? <TeamLink t={world.teams[c.placements[0]]} /> : '') },
          ]}
        />
      </Card>
    </div>
  );
}

function Standings({ stage, comp }: { stage: Stage; comp: Competition }) {
  const world = useWorld();
  const groups = stage.type === 'groups' ? [...new Set(stage.standings.map((r) => r.group ?? 0))] : [undefined];
  return (
    <>
      {groups.map((g) => {
        const rows = sortStandings(world, stage.standings.filter((r) => g === undefined || r.group === g));
        return (
          <div key={String(g)} style={{ marginBottom: 10 }}>
            {g !== undefined && <h4>Group {String.fromCharCode(65 + g)}</h4>}
            <table className="table">
              <thead>
                <tr>
                  <th>#</th>
                  <th>Team</th>
                  <th>W</th>
                  <th>L</th>
                  <th>{getTitle(comp.titleId).family === 'SHOOTER' ? 'Maps' : 'Games'}</th>
                  <th>Diff</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={r.teamId} className={isUserTeamId(world, r.teamId) ? 'hl' : ''}>
                    <td>{i + 1}</td>
                    <td>
                      <OrgBadge org={world.orgs[world.teams[r.teamId]?.orgId]} size={12} /> <TeamLink t={world.teams[r.teamId]} />
                    </td>
                    <td>{r.w}</td>
                    <td>{r.l}</td>
                    <td>
                      {r.gw}-{r.gl}
                    </td>
                    <td>{r.gw - r.gl > 0 ? '+' : ''}{r.gw - r.gl}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        );
      })}
      {stage.type === 'swiss' && <div className="muted small">Swiss: teams with {stage.config.swissWins ?? 3} wins advance, {stage.config.swissLosses ?? 3} losses are eliminated.</div>}
    </>
  );
}

function Bracket({ stage }: { stage: Stage }) {
  const world = useWorld();
  const rounds = [...new Set(stage.bracket.map((n) => n.round))].sort((a, b) => a - b);
  const cell = (n: BracketNode) => {
    const f = n.fixtureId ? world.fixtures[n.fixtureId] : null;
    const name = (id: string | null | undefined) => (id === null ? <span className="muted">bye</span> : id === undefined ? <span className="muted">TBD</span> : <TeamLink t={world.teams[id]} short />);
    return (
      <div key={n.id} className="bnode" onClick={() => f && navigate(`/match/${f.id}`)} style={{ cursor: f ? 'pointer' : 'default' }}>
        <div className="lbl">{n.label}</div>
        <div className={n.winner && n.winner === n.teamA ? 'w' : ''}>
          {name(n.teamA)} {f?.status === 'done' ? f.score?.[0] : ''}
        </div>
        <div className={n.winner && n.winner === n.teamB ? 'w' : ''}>
          {name(n.teamB)} {f?.status === 'done' ? f.score?.[1] : ''}
        </div>
      </div>
    );
  };
  return (
    <div className="bracket">
      {rounds.map((r) => (
        <div key={r} className="bracket-col">
          {stage.bracket.filter((n) => n.round === r).map(cell)}
        </div>
      ))}
    </div>
  );
}

export function CompetitionPage({ id, route }: { id: string; route: Route }) {
  const world = useWorld();
  const comp = world.comps[id];
  if (!comp) return <div>Competition not found (older seasons are archived in History).</div>;
  const t = getTitle(comp.titleId);
  const stageIdx = Number(route.query.stage ?? Math.min(comp.current, comp.stages.length - 1));
  const stage = comp.stages[stageIdx];
  const tab = route.query.tab ?? 'standings';
  const fixtures = stage.fixtureIds.map((f) => world.fixtures[f]).filter(Boolean).sort((a, b) => a.day - b.day);
  const leaders = Object.entries(comp.pstats)
    .map(([pid, s]) => ({ p: world.players[pid], s }))
    .filter((r) => r.p && r.s.gp > 0);
  return (
    <div>
      <PageHeader
        title={comp.name}
        sub={
          <span>
            <TitleTag id={comp.titleId} /> · {comp.tier} · {comp.region ? regionName(comp.region) : 'International'} · starts {formatDate(comp.startDay)} · {comp.status}
          </span>
        }
      />
      <div className="stats">
        <Stat label="Prize pool" value={<Money v={comp.prizePool} />} />
        <Stat label="Teams" value={comp.teamIds.length} />
        <Stat label="Formats" value={<span style={{ fontSize: 13 }}>{(['standard', 'playoff', 'final'] as const).map((k) => formatFor(world, comp, k).label).join(' / ')}</span>} />
        {comp.status === 'done' && <Stat label="Champion" value={<TeamLink t={world.teams[comp.placements[0]]} />} />}
        {comp.mvpPlayerId && <Stat label="MVP" value={<PlayerLink p={world.players[comp.mvpPlayerId]} />} />}
        {comp.finalsMvpPlayerId && <Stat label="Finals MVP" value={<PlayerLink p={world.players[comp.finalsMvpPlayerId]} />} />}
      </div>
      <div className="row" style={{ marginBottom: 8 }}>
        {comp.stages.map((s, i) => (
          <button key={s.id} className={`btn sm ${i === stageIdx ? '' : 'ghost'}`} onClick={() => navigate(withQuery(route, { stage: String(i) }))}>
            {s.name} ({s.type}) · {s.status}
          </button>
        ))}
      </div>
      <Tabs
        tabs={[
          { id: 'standings', label: stage.bracket.length ? 'Bracket' : 'Standings' },
          { id: 'matches', label: 'Matches' },
          { id: 'players', label: 'Player stats' },
          { id: 'placements', label: 'Placements & prizes' },
        ]}
        active={tab}
        onChange={(x) => navigate(withQuery(route, { tab: x }))}
      />
      {tab === 'standings' && <Card>{stage.status === 'pending' ? <div className="muted">Stage not started. Entrants are decided by: {comp.entrants.map((e) => e.kind).join(', ')}.</div> : stage.bracket.length ? <Bracket stage={stage} /> : <Standings stage={stage} comp={comp} />}</Card>}
      {tab === 'matches' && (
        <Card>
          <table className="table">
            <tbody>
              {fixtures.map((f) => (
                <tr key={f.id} className={`clickable ${f.teams.some((x) => isUserTeamId(world, x)) ? 'hl' : ''}`} onClick={() => navigate(`/match/${f.id}`)}>
                  <td>{formatShort(f.day)}</td>
                  <td>{f.group !== undefined ? `Group ${String.fromCharCode(65 + f.group)}` : f.nodeId ?? `Round ${(f.round ?? 0) + 1}`}</td>
                  <td>{fixtureLabel(world, f)}</td>
                  <td>
                    <b>{scoreLabel(f)}</b>
                  </td>
                  <td>{f.status === 'done' && f.winner ? <TeamLink t={world.teams[f.winner]} short /> : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!fixtures.length && <div className="muted">No matches scheduled yet.</div>}
        </Card>
      )}
      {tab === 'players' && (
        <Card>
          <DataTable
            id={`comp-players-${t.id}`}
            rows={leaders}
            rowKey={(r) => r.p.id}
            initialSort={{ key: 'r', desc: true }}
            columns={[
              { key: 'p', label: 'Player', value: (r) => r.p.handle, render: (r) => <PlayerLink p={r.p} /> },
              { key: 't', label: 'Team', value: (r) => world.teams[r.s.teamId]?.name ?? '', render: (r) => <TeamLink t={world.teams[r.s.teamId]} short /> },
              { key: 'gp', label: 'Games', align: 'right', value: (r) => r.s.gp },
              { key: 'r', label: 'Avg rating', align: 'right', value: (r) => r.s.r / r.s.gp, render: (r) => (r.s.r / r.s.gp).toFixed(2) },
              { key: 'm', label: 'Game MVPs', align: 'right', value: (r) => r.s.m },
            ]}
          />
        </Card>
      )}
      {tab === 'placements' && (
        <Card>
          <table className="table">
            <tbody>
              {(comp.status === 'done' ? comp.placements : comp.teamIds).map((tid, i) => (
                <tr key={tid} className={isUserTeamId(world, tid) ? 'hl' : ''}>
                  <td>{comp.status === 'done' ? i + 1 : ''}</td>
                  <td>
                    <TeamLink t={world.teams[tid]} />
                  </td>
                  <td>{comp.status === 'done' ? formatMoney(prizeFor(comp, i)) : ''}</td>
                  <td className="muted">{comp.status === 'done' && comp.circuitPoints[i] ? `${comp.circuitPoints[i]} circuit pts` : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  );
}

import { useEffect, useMemo, useState } from 'react';
import { useWorld } from '../store';
import { Link } from '../router';
import { boxScoresLoaded, cachedArchive, editionsOf, isArchived, loadArchive, seriesKey, seriesName, type Edition } from '../../game/archive';
import { Card, CompLink, PageHeader, Pic, PlayerLink, Stat, TeamLink, TitleTag, Tabs, Money, OrgBadge, Provenance } from '../components/common';
import { compPic } from '../pics';
import { DataTable } from '../components/DataTable';
import { getTitle } from '../../data/titles/catalogue';
import { regionName } from '../../data/countries';
import { formatDate, formatShort } from '../../core/date';
import { navigate, withQuery, type Route } from '../router';
import { formatFor, sortStandings } from '../../competition/stages';
import { prizeFor } from '../../systems/awards';
import { fixtureLabel, isUserTeamId, scoreLabel } from '../helpers';
import type { BracketNode, Competition, Stage, World } from '../../model/types';
import { formatMoney } from '../../core/math';

export function CompetitionsPage({ route }: { route: Route }) {
  const world = useWorld();
  const title = route.query.title ?? '';
  const tier = route.query.tier ?? '';
  // One row per competition (a league or a recurring event), across all its editions.
  const series = useMemo(() => {
    const map = new Map<string, { key: string; name: string; titleId: string; tier: string; region: string | null; editions: Edition[]; latest: Competition | null }>();
    const add = (c: { titleId: string; tier: string; name: string; leagueId: string | null; region: string | null }) => {
      const k = seriesKey(c);
      if (map.has(k)) return;
      map.set(k, { key: k, name: seriesName(c, world), titleId: c.titleId, tier: c.tier, region: c.region, editions: editionsOf(world, c), latest: null });
    };
    for (const c of Object.values(world.comps)) add(c);
    for (const e of world.compIndex ?? []) add(e);
    for (const sr of map.values()) {
      const live = sr.editions.find((e) => !e.archived && world.comps[e.id]?.status !== 'done') ?? sr.editions.find((e) => !e.archived);
      sr.latest = live ? world.comps[live.id] : null;
    }
    return [...map.values()].filter((sr) => (!title || sr.titleId === title) && (!tier || sr.tier === tier));
  }, [world, title, tier]);
  const mostTitles = (eds: Edition[]) => {
    const n = new Map<string, number>();
    for (const e of eds) if (e.champion) n.set(e.champion, (n.get(e.champion) ?? 0) + 1);
    const best = [...n].sort((a, b) => b[1] - a[1])[0];
    return best ? { team: best[0], n: best[1] } : null;
  };
  return (
    <div>
      <PageHeader title="Competitions" sub="Every league and event once — open one to browse its seasons with the dropdown" />
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
          {['league', 'challenger', 'international', 'worlds', 'cup'].map((t) => (
            <option key={t}>{t}</option>
          ))}
        </select>
      </div>
      <Card>
        <DataTable
          id="comp-series"
          rows={series}
          rowKey={(sr) => sr.key}
          initialSort={{ key: 'title', desc: false }}
          filter={(sr, q) => sr.name.toLowerCase().includes(q)}
          onRowClick={(sr) => navigate(`/comp/${(sr.latest ?? sr.editions[0]).id}`)}
          highlight={(sr) => !!sr.latest?.teamIds.some((t) => isUserTeamId(world, t))}
          columns={[
            { key: 'name', label: 'Competition', value: (sr) => sr.name, render: (sr) => <Link to={`/comp/${(sr.latest ?? sr.editions[0]).id}`} className="elink"><Pic src={sr.latest ? compPic(world, sr.latest) : undefined} size={16} /> {sr.name}</Link> },
            { key: 'title', label: 'Title', value: (sr) => sr.titleId, render: (sr) => <TitleTag id={sr.titleId} link={false} /> },
            { key: 'tier', label: 'Tier', value: (sr) => sr.tier },
            { key: 'region', label: 'Region', value: (sr) => (sr.region ? regionName(sr.region) : 'International') },
            { key: 'eds', label: 'Editions', align: 'right', value: (sr) => sr.editions.length },
            { key: 'now', label: 'Current', value: (sr) => (sr.latest ? `${sr.latest.season}${sr.latest.split ? ` S${sr.latest.split}` : ''} · ${sr.latest.status}` : 'archived') },
            {
              key: 'holder',
              label: 'Holder',
              value: (sr) => world.teams[sr.editions.find((e) => e.champion)?.champion ?? '']?.name ?? '',
              render: (sr) => {
                const e = sr.editions.find((x) => x.champion);
                return e ? <TeamLink t={world.teams[e.champion!]} /> : '';
              },
            },
            {
              key: 'most',
              label: 'Most titles',
              value: (sr) => mostTitles(sr.editions)?.n ?? 0,
              render: (sr) => {
                const m = mostTitles(sr.editions);
                return m ? (
                  <span>
                    <TeamLink t={world.teams[m.team]} short /> ×{m.n}
                  </span>
                ) : (
                  ''
                );
              },
            },
          ]}
        />
      </Card>
    </div>
  );
}

function Standings({ stage, comp, w }: { stage: Stage; comp: Competition; w: World }) {
  const world = w;
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

function Bracket({ stage, w, season }: { stage: Stage; w: World; season?: number }) {
  const world = w;
  const rounds = [...new Set(stage.bracket.map((n) => n.round))].sort((a, b) => a - b);
  const cell = (n: BracketNode) => {
    const f = n.fixtureId ? world.fixtures[n.fixtureId] : null;
    const name = (id: string | null | undefined) => (id === null ? <span className="muted">bye</span> : id === undefined ? <span className="muted">TBD</span> : <TeamLink t={world.teams[id]} short />);
    return (
      <div key={n.id} className="bnode" onClick={() => f && navigate(`/match/${f.id}${season ? `?season=${season}` : ''}`)} style={{ cursor: f ? 'pointer' : 'default' }}>
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

/**
 * The live world plus one past season: its competitions and results come straight from the save
 * (instant); its box scores are loaded from the match store only when `needMatches` (match pages).
 */
export function useArchivedView(live: World, season: number | null, needMatches = false): { world: World; loading: boolean; missing: boolean } {
  const [tick, setTick] = useState(0);
  const [missing, setMissing] = useState(false);
  const base = season !== null ? cachedArchive(live, season) : undefined;
  const wantLoad = season !== null && (!base || (needMatches && !boxScoresLoaded(live, season)));
  useEffect(() => {
    if (!wantLoad || season === null) return;
    let on = true;
    loadArchive(live, season).then((a) => {
      if (!on) return;
      setMissing(!a);
      setTick((x) => x + 1);
    });
    return () => {
      on = false;
    };
  }, [live, season, wantLoad]);
  const world = useMemo(() => {
    const arch = season !== null ? cachedArchive(live, season) : undefined;
    if (season === null || !arch) return live;
    return {
      ...live,
      comps: { ...Object.fromEntries(arch.comps.map((c) => [c.id, c])), ...live.comps },
      fixtures: { ...Object.fromEntries(arch.fixtures.map((f) => [f.id, f])), ...live.fixtures },
      matches: { ...Object.fromEntries(arch.matches.map((m) => [m.fixtureId, m])), ...live.matches },
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live, season, tick]);
  return { world, loading: wantLoad && !missing, missing };
}

export function CompetitionPage({ id, route }: { id: string; route: Route }) {
  const live = useWorld();
  const entry = live.comps[id] ? undefined : isArchived(live, id);
  const { world, loading, missing } = useArchivedView(live, entry ? entry.season : null);
  const comp = world.comps[id];
  if (!comp) {
    if (entry && loading) return <div className="muted">Loading {entry.name} from the season archive…</div>;
    if (entry && missing) return <div>{entry.name}: this edition's details are not on this device (archived by another copy of the save). Champion: {world.teams[entry.champion ?? '']?.name ?? '—'}.</div>;
    return <div>Competition not found.</div>;
  }
  const editions = editionsOf(world, comp);
  const archivedSeason = entry ? entry.season : undefined;
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
        badge={<Pic src={compPic(world, comp)} size={48} alt={comp.name} />}
        title={seriesName(comp, world)}
        right={
          editions.length > 1 ? (
            <select value={comp.id} onChange={(e) => navigate(`/comp/${e.target.value}`)} aria-label="Season">
              {editions.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.label}
                  {e.champion ? ` — ${world.teams[e.champion]?.short ?? ''}` : ''}
                </option>
              ))}
            </select>
          ) : undefined
        }
        sub={
          <span>
            <b>{comp.season}{comp.split ? ` · Split ${comp.split}` : ''}</b> · <TitleTag id={comp.titleId} /> · {comp.tier} · {comp.region ? regionName(comp.region) : 'International'} · starts {formatDate(comp.startDay)} · {comp.status}
            {entry && ' · archived season'}
            {comp.leagueId && world.leagues[comp.leagueId]?.real && <Provenance world={world} real={world.leagues[comp.leagueId].real} kind="league" />}
            {comp.rules?.fearless === 'global' && <div className="small"><span className="tag warn">Hard Fearless</span> every champion either team has played in a series is unavailable to both teams for the rest of that series.</div>}
            {comp.realName && <div className="small muted" data-testid="provenance"><span className="tag good">Real</span> event name; format, dates, entrants and prize pool are game simulation.</div>}
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
      {tab === 'standings' && <Card>{stage.status === 'pending' ? <div className="muted">Stage not started. Entrants are decided by: {comp.entrants.map((e) => e.kind).join(', ')}.</div> : stage.bracket.length ? <Bracket stage={stage} w={world} season={archivedSeason} /> : <Standings stage={stage} comp={comp} w={world} />}</Card>}
      {tab === 'matches' && (
        <Card>
          <table className="table">
            <tbody>
              {fixtures.map((f) => (
                <tr key={f.id} className={`clickable ${f.teams.some((x) => isUserTeamId(world, x)) ? 'hl' : ''}`} onClick={() => navigate(`/match/${f.id}${archivedSeason ? `?season=${archivedSeason}` : ''}`)}>
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

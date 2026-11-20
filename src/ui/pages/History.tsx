import { useMemo } from 'react';
import { useWorld } from '../store';
import { Card, CompLink, Flag, OrgLink, PageHeader, PlayerLink, TeamLink, TitleTag, Tabs, StaffLink, compRef } from '../components/common';
import { DataTable } from '../components/DataTable';
import { navigate, withQuery, type Route } from '../router';
import { legacy, HOF_THRESHOLD } from '../../systems/awards';
import { getTitle } from '../../data/titles/catalogue';
import type { Player } from '../../model/types';

export function HistoryPage({ route }: { route: Route }) {
  const world = useWorld();
  const tab = route.query.tab ?? 'seasons';
  const title = route.query.title ?? '';
  const goat = useMemo(
    () =>
      Object.values(world.players)
        .filter((p) => p.stats.length && (!title || p.stats.some((s) => s.titleId === title)))
        .map((p) => ({ p, l: legacy(p, title || undefined) }))
        .filter((r) => r.l.total > 5)
        .sort((a, b) => b.l.total - a.l.total)
        .slice(0, 200),
    [world, title],
  );
  const orgTrophies = useMemo(() => {
    const m = new Map<string, number>();
    for (const t of Object.values(world.teams)) for (const tr of t.trophies) if (!title || tr.titleId === title) m.set(t.orgId, (m.get(t.orgId) ?? 0) + (tr.tier === 'worlds' ? 3 : tr.tier === 'international' ? 2 : 1));
    return [...m.entries()].sort((a, b) => b[1] - a[1]).slice(0, 30);
  }, [world, title]);
  return (
    <div>
      <PageHeader title="History" sub={`${world.history.length} completed seasons archived`} />
      <div className="row" style={{ marginBottom: 8 }}>
        <select value={title} onChange={(e) => navigate(withQuery(route, { title: e.target.value || undefined }))} aria-label="Title">
          <option value="">All titles (cross-title)</option>
          {world.meta.titlesEnabled.map((id) => (
            <option key={id} value={id}>
              {getTitle(id).name}
            </option>
          ))}
        </select>
      </div>
      <Tabs
        tabs={[
          { id: 'seasons', label: 'Champions by season' },
          { id: 'goat', label: 'GOAT rankings' },
          { id: 'hof', label: `Hall of Fame (${world.hof.length})` },
          { id: 'dynasties', label: 'Dynasties' },
          { id: 'retired', label: 'Retired archive' },
        ]}
        active={tab}
        onChange={(x) => navigate(withQuery(route, { tab: x }))}
      />
      {tab === 'seasons' && (
        <>
          {[...world.history].reverse().map((h) => (
            <Card key={h.season} title={`Season ${h.season}`}>
              <table className="table">
                <tbody>
                  {h.champions
                    .filter((c) => (!title || c.titleId === title) && (c.tier === 'worlds' || c.tier === 'international' || title))
                    .map((c) => (
                      <tr key={c.compId}>
                        <td>
                          <TitleTag id={c.titleId} />
                        </td>
                        <td>{compRef(world, c.compId) ? <CompLink c={compRef(world, c.compId)} /> : c.compName}</td>
                        <td>
                          <b>
                            <TeamLink t={world.teams[c.teamId]} />
                          </b>
                        </td>
                        <td className="muted small">runner-up {c.runnerUpId && world.teams[c.runnerUpId] ? <TeamLink t={world.teams[c.runnerUpId]} /> : '—'}</td>
                        <td className="small">{c.mvp ? <>MVP <PlayerLink p={world.players[c.mvp]} /></> : ''}</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </Card>
          ))}
          {!world.history.length && <Card>No completed seasons yet — history is written at every season rollover (late November).</Card>}
        </>
      )}
      {tab === 'goat' && (
        <Card title={title ? `${getTitle(title).name} all-time greats` : 'Cross-title greatest of all time (prestige-weighted)'}>
          <DataTable
            id="goat"
            rows={goat}
            rowKey={(r) => r.p.id}
            initialSort={{ key: 'l', desc: true }}
            columns={[
              { key: 'p', label: 'Player', value: (r) => r.p.handle, render: (r) => (<span><Flag cc={r.p.nat} /><PlayerLink p={r.p} /></span>) },
              { key: 'titles', label: 'Titles played', value: (r) => [...new Set(r.p.stats.map((s) => s.titleId))].join(','), render: (r) => <span>{[...new Set(r.p.stats.map((s) => s.titleId))].map((t) => <TitleTag key={t} id={t} link={false} />)}</span> },
              { key: 'trophies', label: 'Trophies', align: 'right', value: (r) => r.p.awards.filter((a) => a.awardId.startsWith('champion') && (!title || a.titleId === title)).length },
              { key: 'mvp', label: 'MVP awards', align: 'right', value: (r) => r.p.awards.filter((a) => a.awardId.includes('mvp') && (!title || a.titleId === title)).length },
              { key: 'ach', label: 'Achievement pts', align: 'right', value: (r) => r.l.achievements, render: (r) => r.l.achievements.toFixed(1) },
              { key: 'peak', label: 'Peak', align: 'right', value: (r) => r.l.peak, render: (r) => r.l.peak.toFixed(1) },
              { key: 'long', label: 'Longevity', align: 'right', value: (r) => r.l.longevity, render: (r) => r.l.longevity.toFixed(1) },
              { key: 'l', label: 'Legacy', align: 'right', value: (r) => r.l.total, render: (r) => <b>{r.l.total.toFixed(1)}</b> },
              { key: 'status', label: 'Status', value: (r) => (r.p.retired ? `retired ${r.p.retired.season}` : 'active') },
            ]}
          />
          <div className="muted small">Legacy combines prestige-weighted achievements (stamped with the title&apos;s prestige in that era), peak ability, longevity and volume. Raw statistics are never compared across titles.</div>
        </Card>
      )}
      {tab === 'hof' && (
        <Card title={`Hall of Fame (retired, legacy ≥ ${HOF_THRESHOLD})`}>
          {world.hof.map((h) => (
            <div key={h.playerId} className="attr-row">
              <span>
                {h.season} · {world.players[h.playerId] ? <PlayerLink p={world.players[h.playerId] as Player} /> : h.handle} <span className="muted">{h.name}</span>
              </span>
              <span>
                {h.titles.map((t) => (
                  <TitleTag key={t} id={t} link={false} />
                ))}{' '}
                legacy {h.legacy.toFixed(0)}
              </span>
            </div>
          ))}
          {!world.hof.length && <div className="muted">No inductees yet.</div>}
        </Card>
      )}
      {tab === 'dynasties' && (
        <Card title="Most decorated organisations (worlds ×3, internationals ×2, leagues ×1)">
          {orgTrophies.map(([oid, pts], i) => (
            <div key={oid} className="attr-row">
              <span>
                {i + 1}. <OrgLink org={world.orgs[oid]} />
              </span>
              <b>{pts}</b>
            </div>
          ))}
        </Card>
      )}
      {tab === 'retired' && (
        <Card title="Archived retired players">
          <DataTable
            id="retired"
            rows={world.retired}
            rowKey={(r) => r.id}
            initialSort={{ key: 'legacy', desc: true }}
            filter={(r, q) => r.handle.toLowerCase().includes(q)}
            columns={[
              { key: 'handle', label: 'Player', value: (r) => r.handle, render: (r) => (<span><Flag cc={r.nat} />{world.players[r.id] ? <PlayerLink p={world.players[r.id]} /> : r.handle}</span>) },
              { key: 'name', label: 'Name', value: (r) => r.name },
              { key: 'season', label: 'Retired', align: 'right', value: (r) => r.retiredSeason },
              { key: 'titles', label: 'Titles', value: (r) => r.titles.join(', ') },
              { key: 'trophies', label: 'Trophies', align: 'right', value: (r) => r.trophies },
              { key: 'legacy', label: 'Legacy', align: 'right', value: (r) => r.legacy, render: (r) => r.legacy.toFixed(1) },
            ]}
          />
        </Card>
      )}
    </div>
  );
}

export function AwardsPage({ route }: { route: Route }) {
  const world = useWorld();
  const seasons = [...new Set(world.awards.map((a) => a.season))].sort((a, b) => b - a);
  const season = Number(route.query.season ?? seasons[0] ?? world.season);
  const title = route.query.title ?? '';
  const list = world.awards.filter((a) => a.season === season && (!title || a.titleId === title) && !a.awardId.startsWith('champion'));
  return (
    <div>
      <PageHeader title="Awards" sub="Season, tournament, finals, role and statistical honours" />
      <div className="row" style={{ marginBottom: 8 }}>
        <select value={season} onChange={(e) => navigate(withQuery(route, { season: e.target.value }))} aria-label="Season">
          {(seasons.length ? seasons : [world.season]).map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
        <select value={title} onChange={(e) => navigate(withQuery(route, { title: e.target.value || undefined }))} aria-label="Title">
          <option value="">All titles</option>
          {world.meta.titlesEnabled.map((id) => (
            <option key={id} value={id}>
              {getTitle(id).name}
            </option>
          ))}
        </select>
      </div>
      <Card>
        <table className="table">
          <tbody>
            {list.map((a, i) => (
              <tr key={i}>
                <td>{a.titleId ? <TitleTag id={a.titleId} /> : <span className="tag">All</span>}</td>
                <td>{a.label}</td>
                <td>
                  {a.playerId ? <PlayerLink p={world.players[a.playerId]} /> : a.staffId ? <StaffLink s={world.staff[a.staffId]} /> : a.orgId && !a.teamId ? <OrgLink org={world.orgs[a.orgId]} /> : a.teamId ? <TeamLink t={world.teams[a.teamId]} /> : ''}
                </td>
                <td className="muted small">{a.value !== undefined ? a.value : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {!list.length && <div className="muted">No awards for this selection yet (tournament awards appear as events finish; season awards at rollover).</div>}
      </Card>
    </div>
  );
}

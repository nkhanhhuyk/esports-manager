/**
 * Spectator mode: the followed-teams dashboard, choosing teams to follow (any number, or all),
 * and the "just watch" entry point on the new-career screen.
 */
import { RichText } from '../components/RichText';
import { useMemo, useState } from 'react';
import { useGame, useWorld } from '../store';
import { Card, CompLink, OrgBadge, PageHeader, Stat, TeamLink, TitleTag } from '../components/common';
import { DataTable } from '../components/DataTable';
import { RivalryBadge } from '../components/Fans';
import { Link, navigate } from '../router';
import { formatDate, formatShort } from '../../core/date';
import { getTitle } from '../../data/titles/catalogue';
import { regionName } from '../../data/countries';
import { computeTeamStrength } from '../../sim/common/team';
import { becomeSpectator, isFollowed, setFollowed, watchedFixturesOn } from '../../systems/follow';
import { recentResults, upcomingFixtures } from '../helpers';
import type { Team, World } from '../../model/types';

function mainTeams(world: World): Team[] {
  return Object.values(world.teams).filter((t) => t.active && !t.national && t.tier === 'main');
}

/** Card on the organisation picker: start watching instead of managing. */
export function SpectateCard() {
  const { act, save } = useGame.getState();
  const world = useWorld();
  const go = async (ids: string[]) => {
    act((w) => becomeSpectator(w, ids));
    await save(true);
    navigate('/following');
  };
  return (
    <Card title="Or just watch (spectator mode)">
      <div className="muted" style={{ marginBottom: 8 }}>
        Follow any teams — or every team — and simulate through the seasons. Every club&apos;s own coaches and general managers make all the decisions: lineups, drafts, training and scrims, signings, contracts and staff. You can watch any match live and take charge of an organisation later from Settings.
      </div>
      <div className="row">
        <button className="btn" onClick={() => go(mainTeams(world).map((t) => t.id))}>
          Follow every team
        </button>
        <button className="btn ghost" onClick={() => go([])}>
          Choose teams to follow…
        </button>
      </div>
    </Card>
  );
}

export function FollowingPage() {
  const world = useWorld();
  const { act } = useGame.getState();
  const [title, setTitle] = useState('');
  const [tier, setTier] = useState<'main' | 'all'>('main');
  const rows = useMemo(
    () =>
      Object.values(world.teams)
        .filter((t) => t.active && !t.national && (!title || t.titleId === title) && (tier === 'all' || t.tier === 'main'))
        .map((t) => ({ t, str: computeTeamStrength(world, t) })),
    [world, title, tier],
  );
  const followed = world.meta.followed ?? [];
  const shown = rows.map((r) => r.t.id);
  return (
    <div>
      <PageHeader title="Following" sub={`${followed.length} team(s) followed — their matches get full detail, match reports, training timetables and can be watched live.`} />
      <Card title="Options">
        <label style={{ display: 'block' }}>
          <input type="checkbox" checked={!!world.meta.stopForFollowed} onChange={(e) => act((w) => (w.meta.stopForFollowed = e.target.checked))} /> Stop “Continue” the day before a followed team plays (to watch it live)
        </label>
        <div className="row" style={{ marginTop: 8 }}>
          <label className="field">
            <span>Title</span>
            <select value={title} onChange={(e) => setTitle(e.target.value)}>
              <option value="">All titles</option>
              {world.meta.titlesEnabled.map((id) => (
                <option key={id} value={id}>
                  {getTitle(id).name}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Teams</span>
            <select value={tier} onChange={(e) => setTier(e.target.value as 'main' | 'all')}>
              <option value="main">Main teams</option>
              <option value="all">Including academies</option>
            </select>
          </label>
          <button className="btn sm" onClick={() => act((w) => setFollowed(w, [...(w.meta.followed ?? []), ...shown]))}>
            Follow all shown ({shown.length})
          </button>
          <button className="btn ghost sm" onClick={() => act((w) => setFollowed(w, (w.meta.followed ?? []).filter((id) => !shown.includes(id))))}>
            Unfollow all shown
          </button>
        </div>
      </Card>
      <Card title="Teams">
        <DataTable
          id="follow-teams"
          rows={rows}
          rowKey={(r) => r.t.id}
          initialSort={{ key: 'elo', desc: true }}
          filter={(r, q) => r.t.name.toLowerCase().includes(q) || r.t.short.toLowerCase().includes(q)}
          pageSize={40}
          columns={[
            {
              key: 'f',
              label: 'Follow',
              value: (r) => (isFollowed(world, r.t.id) ? 1 : 0),
              render: (r) => (
                <input
                  type="checkbox"
                  aria-label={`Follow ${r.t.name}`}
                  checked={isFollowed(world, r.t.id)}
                  onChange={(e) => act((w) => setFollowed(w, e.target.checked ? [...(w.meta.followed ?? []), r.t.id] : (w.meta.followed ?? []).filter((x) => x !== r.t.id)))}
                />
              ),
            },
            { key: 'name', label: 'Team', value: (r) => r.t.name, render: (r) => (<span><OrgBadge org={world.orgs[r.t.orgId]} size={14} /> <TeamLink t={r.t} /></span>) },
            { key: 'title', label: 'Title', value: (r) => r.t.titleId, render: (r) => <TitleTag id={r.t.titleId} /> },
            { key: 'league', label: 'League', value: (r) => (r.t.leagueId ? world.leagues[r.t.leagueId]?.short : '') ?? '' },
            { key: 'region', label: 'Region', value: (r) => regionName(r.t.region) },
            { key: 'elo', label: 'Elo', align: 'right', value: (r) => r.t.elo },
            { key: 'str', label: 'Strength', align: 'right', value: (r) => r.str, render: (r) => r.str.toFixed(1) },
            { key: 'tro', label: 'Trophies', align: 'right', value: (r) => r.t.trophies.length },
          ]}
        />
      </Card>
    </div>
  );
}

/** Dashboard in spectator mode. */
export function SpectatorHome() {
  const world = useWorld();
  const ids = (world.meta.followed ?? []).filter((id) => world.teams[id]?.active);
  const up = upcomingFixtures(world, ids, 10);
  const res = recentResults(world, ids, 10);
  const tomorrow = watchedFixturesOn(world, world.day + 1);
  const news = [...world.news].sort((a, b) => b.day - a.day || b.importance - a.importance).filter((n) => !ids.length || n.importance >= 3 || n.refs.some((r) => r.type === 'team' && ids.includes(r.id))).slice(0, 10);
  const teams = ids.map((id) => world.teams[id]).sort((a, b) => b.elo - a.elo);
  return (
    <div>
      <PageHeader
        title="Spectator"
        sub={`${world.meta.managerName} · watching ${ids.length} team(s) · ${formatDate(world.day, true)} · Season ${world.season}`}
        right={
          <>
            {tomorrow.length > 0 && (
              <Link to="/matchday">
                <span className="btn warn">Tomorrow: {tomorrow.length} followed match(es)</span>
              </Link>
            )}
            <button className="btn ghost sm" onClick={() => navigate('/following')}>
              Choose teams
            </button>
          </>
        }
      />
      <div className="stats">
        <Stat label="Followed teams" value={ids.length} sub={[...new Set(teams.map((t) => getTitle(t.titleId).short))].join(', ') || 'none yet'} />
        <Stat label="Trophies (followed)" value={teams.reduce((a, t) => a + t.trophies.length, 0)} />
        <Stat label="Unread inbox" value={world.inbox.filter((m) => !m.read).length} />
      </div>
      {!ids.length && (
        <Card>
          <div className="row">
            <span>You are not following any team yet.</span>
            <button className="btn" onClick={() => navigate('/following')}>
              Choose teams to follow
            </button>
          </div>
        </Card>
      )}
      <div className="grid2">
        <Card title="Upcoming matches (followed)" right={<Link to="/schedule">Schedule</Link>}>
          {!up.length && <div className="muted">No scheduled matches for followed teams.</div>}
          <table className="table">
            <tbody>
              {up.map((f) => (
                <tr key={f.id}>
                  <td>{formatShort(f.day)}</td>
                  <td>
                    <TitleTag id={f.titleId} link={false} />
                  </td>
                  <td>
                    <TeamLink t={world.teams[f.teams[0]]} short /> vs <TeamLink t={world.teams[f.teams[1]]} short /> <RivalryBadge world={world} a={f.teams[0]} b={f.teams[1]} />
                  </td>
                  <td>
                    <CompLink c={world.comps[f.compId]} />
                  </td>
                  <td>
                    {f.day === world.day + 1 ? (
                      <button className="btn sm" onClick={() => navigate(`/live/${f.id}`)}>
                        Watch live
                      </button>
                    ) : (
                      <Link to={`/match/${f.id}`}>{f.format.label}</Link>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
        <Card title="Recent results (followed)">
          {!res.length && <div className="muted">No results yet.</div>}
          <table className="table">
            <tbody>
              {res.map((f) => (
                <tr key={f.id} className="clickable" onClick={() => navigate(`/match/${f.id}`)}>
                  <td>{formatShort(f.day)}</td>
                  <td>
                    <TitleTag id={f.titleId} link={false} />
                  </td>
                  <td>
                    <span className={f.winner === f.teams[0] ? 'pos' : ''}>
                      <TeamLink t={world.teams[f.teams[0]]} short />
                    </span>{' '}
                    {f.score?.[0]}–{f.score?.[1]}{' '}
                    <span className={f.winner === f.teams[1] ? 'pos' : ''}>
                      <TeamLink t={world.teams[f.teams[1]]} short />
                    </span>
                  </td>
                  <td>
                    <CompLink c={world.comps[f.compId]} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      </div>
      <div className="grid2">
        <Card title="Followed teams">
          <table className="table">
            <tbody>
              {teams.slice(0, 30).map((t) => (
                <tr key={t.id}>
                  <td>
                    <OrgBadge org={world.orgs[t.orgId]} size={14} /> <TeamLink t={t} />
                  </td>
                  <td>
                    <TitleTag id={t.titleId} />
                  </td>
                  <td className="num">Elo {t.elo}</td>
                  <td className="num">{t.trophies.length} trophies</td>
                </tr>
              ))}
            </tbody>
          </table>
          {teams.length > 30 && <div className="muted small">…and {teams.length - 30} more (Following).</div>}
        </Card>
        <Card title="Latest news" right={<Link to="/news">All news</Link>}>
          {news.map((n) => (
            <div key={n.id} className="small" style={{ padding: '3px 0', borderBottom: '1px solid var(--border)' }}>
              <span className="muted">{formatShort(n.day)}</span> <b><RichText world={world} text={n.title} refs={n.refs} /></b>
            </div>
          ))}
        </Card>
      </div>
    </div>
  );
}

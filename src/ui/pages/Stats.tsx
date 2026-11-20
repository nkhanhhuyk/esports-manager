/**
 * Stats hub: leaderboards per title/season/stat, all-time records, and a head-to-head explorer.
 * Season page: preview (power rankings & predictions) and review (champions, awards, storylines,
 * predictions vs reality).
 */
import { useMemo, useState } from 'react';
import { useWorld } from '../store';
import { Card, CompLink, OrgLink, PageHeader, PlayerLink, StaffLink, TeamLink, TitleTag, Tabs, compRef } from '../components/common';
import { RichText } from '../components/RichText';
import { RivalryBadge } from '../components/Fans';
import { navigate, withQuery, type Route } from '../router';
import { formatShort } from '../../core/date';
import { formatMoney } from '../../core/math';
import { getTitle } from '../../data/titles/catalogue';
import { headToHead } from '../../systems/seasonReport';
import type { Player, StatLine, Team, World } from '../../model/types';

function TitleSelect({ world, value, onChange, all }: { world: World; value: string; onChange: (v: string) => void; all?: boolean }) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)} aria-label="Title">
      {all && <option value="">All titles</option>}
      {world.meta.titlesEnabled.map((id) => (
        <option key={id} value={id}>
          {getTitle(id).name}
        </option>
      ))}
    </select>
  );
}

// ---------------------------------------------------------------------------------------------
// Leaderboards
// ---------------------------------------------------------------------------------------------

function Leaderboards({ world, route }: { world: World; route: Route }) {
  const title = route.query.title || world.meta.titlesEnabled[0];
  const t = getTitle(title);
  const seasons = useMemo(() => {
    const s = new Set<number>();
    for (const p of Object.values(world.players)) for (const l of p.stats) if (l.titleId === title) s.add(l.season);
    return [...s].sort((a, b) => b - a);
  }, [world, title]);
  const season = Number(route.query.season || seasons[0] || world.season);
  const stat = route.query.stat || 'rating';
  const rows = useMemo(() => {
    const agg = new Map<string, { p: Player; teamId: string | null; gp: number; w: number; r: number; mvps: number; s: Record<string, number> }>();
    for (const p of Object.values(world.players)) {
      for (const l of p.stats) {
        if (l.titleId !== title || l.season !== season || l.gp === 0) continue;
        const a = agg.get(p.id) ?? { p, teamId: l.teamId, gp: 0, w: 0, r: 0, mvps: 0, s: {} };
        a.gp += l.gp;
        a.w += l.w;
        a.r += l.ratingSum;
        a.mvps += l.mvps;
        if (l.gp >= (agg.get(p.id)?.gp ?? 0)) a.teamId = l.teamId;
        for (const [k, v] of Object.entries(l.s)) a.s[k] = (a.s[k] ?? 0) + v;
        agg.set(p.id, a);
      }
    }
    const list = [...agg.values()];
    const maxGp = Math.max(1, ...list.map((x) => x.gp));
    const minGp = Math.ceil(maxGp * 0.3);
    const def = t.stats.find((s) => s.key === stat);
    const kdaOf = (x: (typeof list)[number]) => ((x.s.k ?? 0) + (x.s.a ?? 0)) / Math.max(1, x.s.d ?? 0);
    const value = (x: (typeof list)[number]) =>
      stat === 'rating' ? x.r / x.gp : stat === 'kda' ? kdaOf(x) : stat === 'mvps' ? x.mvps : stat === 'gp' ? x.gp : stat === 'winrate' ? x.w / x.gp : def?.avg ? (x.s[stat] ?? 0) / x.gp : (x.s[stat] ?? 0);
    const higher = def ? def.good !== false : true;
    return list
      .filter((x) => x.gp >= (stat === 'gp' || stat === 'mvps' ? 1 : minGp))
      .map((x) => ({ ...x, v: value(x) }))
      .sort((a, b) => (higher ? b.v - a.v : a.v - b.v))
      .slice(0, 50)
      .map((x) => ({ ...x, minGp }));
  }, [world, title, season, stat, t]);
  const def = t.stats.find((s) => s.key === stat);
  const hasKda = t.stats.some((s) => s.key === 'k') && t.stats.some((s) => s.key === 'd');
  const fmt = (v: number) => (stat === 'rating' || stat === 'kda' ? v.toFixed(2) : stat === 'winrate' ? `${Math.round(v * 100)}%` : def?.avg ? v.toFixed(1) : Math.round(v).toLocaleString());
  return (
    <Card
      title="Leaderboards"
      right={
        <span className="row">
          <TitleSelect world={world} value={title} onChange={(v) => navigate(withQuery(route, { title: v, stat: 'rating', season: '' }))} />
          <select value={season} onChange={(e) => navigate(withQuery(route, { season: e.target.value }))} aria-label="Season">
            {seasons.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
          <select value={stat} onChange={(e) => navigate(withQuery(route, { stat: e.target.value }))} aria-label="Statistic">
            <option value="rating">Average rating</option>
            {hasKda && <option value="kda">KDA ((K+A)/D)</option>}
            <option value="winrate">Win rate</option>
            <option value="mvps">Game MVPs</option>
            <option value="gp">Games played</option>
            {t.stats.map((s) => (
              <option key={s.key} value={s.key}>
                {s.label}
                {s.avg ? ' per game' : ''}
              </option>
            ))}
          </select>
        </span>
      }
    >
      {!rows.length && <div className="muted">No games recorded for this selection.</div>}
      {!!rows.length && <div className="muted small" style={{ marginBottom: 4 }}>Rates need at least {rows[0].minGp} games (30% of the most played).</div>}
      <div className="dt-scroll">
        <table className="table">
          <thead>
            <tr>
              <th>#</th>
              <th>Player</th>
              <th>Team</th>
              <th className="num">Games</th>
              {hasKda && <th className="num">K / D / A</th>}
              {hasKda && <th className="num" title="(Kills + Assists) / Deaths">KDA</th>}
              <th className="num">{stat === 'rating' ? 'Rating' : stat === 'kda' ? 'KDA' : stat === 'winrate' ? 'Win rate' : stat === 'mvps' ? 'MVPs' : stat === 'gp' ? 'Games' : def?.label}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((x, i) => (
              <tr key={x.p.id}>
                <td>{i + 1}</td>
                <td>
                  <PlayerLink p={x.p} />
                </td>
                <td>{x.teamId ? <TeamLink t={world.teams[x.teamId]} short /> : '—'}</td>
                <td className="num">{x.gp}</td>
                {hasKda && (
                  <td className="num">
                    {Math.round(x.s.k ?? 0)} / {Math.round(x.s.d ?? 0)} / {Math.round(x.s.a ?? 0)}
                  </td>
                )}
                {hasKda && <td className="num">{(((x.s.k ?? 0) + (x.s.a ?? 0)) / Math.max(1, x.s.d ?? 0)).toFixed(2)}</td>}
                <td className="num">
                  <b>{fmt(x.v)}</b>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
}

// ---------------------------------------------------------------------------------------------
// Records
// ---------------------------------------------------------------------------------------------

function RecordList<T>({ title, rows, render, value }: { title: string; rows: T[]; render: (r: T) => React.ReactNode; value: (r: T) => React.ReactNode }) {
  return (
    <Card title={title}>
      {!rows.length && <div className="muted">No records yet.</div>}
      {rows.map((r, i) => (
        <div key={i} className="attr-row">
          <span>
            {i + 1}. {render(r)}
          </span>
          <b>{value(r)}</b>
        </div>
      ))}
    </Card>
  );
}

function Records({ world, route }: { world: World; route: Route }) {
  const title = route.query.title ?? '';
  const inTitle = (id?: string | null) => !title || id === title;
  const data = useMemo(() => {
    const teams = Object.values(world.teams).filter((t) => !t.national && inTitle(t.titleId));
    const trophies = teams.map((t) => ({ t, n: t.trophies.length })).filter((x) => x.n).sort((a, b) => b.n - a.n).slice(0, 10);
    const worlds = teams.map((t) => ({ t, n: t.trophies.filter((x) => x.tier === 'worlds').length })).filter((x) => x.n).sort((a, b) => b.n - a.n).slice(0, 10);
    const orgWorlds = new Map<string, number>();
    for (const x of worlds) orgWorlds.set(x.t.orgId, (orgWorlds.get(x.t.orgId) ?? 0) + x.n);
    const orgs = [...orgWorlds.entries()].map(([id, n]) => ({ o: world.orgs[id], n })).filter((x) => x.o).sort((a, b) => b.n - a.n).slice(0, 10);
    const awardsBy = (pred: (id: string) => boolean) => {
      const m = new Map<string, number>();
      for (const a of world.awards) if (a.playerId && inTitle(a.titleId) && pred(a.awardId)) m.set(a.playerId, (m.get(a.playerId) ?? 0) + 1);
      return [...m.entries()].map(([id, n]) => ({ p: world.players[id], n })).filter((x) => x.p).sort((a, b) => b.n - a.n).slice(0, 10);
    };
    const mvps = awardsBy((id) => id.includes('mvp'));
    const poy = awardsBy((id) => id === 'poy');
    // Season and career records from stat lines (players still in the database).
    const seasonBest: Array<{ p: Player; l: StatLine; r: number }> = [];
    const career = new Map<string, { p: Player; gp: number; w: number }>();
    for (const p of Object.values(world.players)) {
      for (const l of p.stats) {
        if (!inTitle(l.titleId) || !l.teamId || world.teams[l.teamId]?.national) continue;
        if (l.gp >= 20) seasonBest.push({ p, l, r: l.ratingSum / l.gp });
        const c = career.get(p.id) ?? { p, gp: 0, w: 0 };
        c.gp += l.gp;
        c.w += l.w;
        career.set(p.id, c);
      }
    }
    seasonBest.sort((a, b) => b.r - a.r);
    const games = [...career.values()].sort((a, b) => b.gp - a.gp).slice(0, 10);
    const fees = world.transfers.filter((x) => x.fee > 0 && inTitle(x.titleId)).sort((a, b) => b.fee - a.fee).slice(0, 10);
    const legacy = world.retired.filter((r) => !title || r.titles.includes(title)).sort((a, b) => b.legacy - a.legacy).slice(0, 10);
    return { trophies, worlds, orgs, mvps, poy, seasonBest: seasonBest.slice(0, 10), games, fees, legacy };
  }, [world, title]);
  return (
    <>
      <div className="row" style={{ marginBottom: 8 }}>
        <TitleSelect world={world} value={title} all onChange={(v) => navigate(withQuery(route, { title: v }))} />
        <span className="muted small">Player records cover players still in the database; long-retired players are summarised in the legacy list.</span>
      </div>
      <div className="grid2">
        <RecordList title="Most world titles (team)" rows={data.worlds} render={(x) => <><TitleTag id={x.t.titleId} /> <TeamLink t={x.t} /></>} value={(x) => x.n} />
        <RecordList title="Most world titles (organisation)" rows={data.orgs} render={(x) => <OrgLink org={x.o} />} value={(x) => x.n} />
        <RecordList title="Most trophies (team)" rows={data.trophies} render={(x) => <><TitleTag id={x.t.titleId} /> <TeamLink t={x.t} /></>} value={(x) => x.n} />
        <RecordList title="Most MVP awards" rows={data.mvps} render={(x) => <PlayerLink p={x.p} />} value={(x) => x.n} />
        <RecordList title="Most Player of the Year awards" rows={data.poy} render={(x) => <PlayerLink p={x.p} />} value={(x) => x.n} />
        <RecordList title="Best season rating (min. 20 games)" rows={data.seasonBest} render={(x) => <><PlayerLink p={x.p} /> <span className="muted small">{x.l.season} · <TitleTag id={x.l.titleId} /></span></>} value={(x) => x.r.toFixed(2)} />
        <RecordList title="Most career games" rows={data.games} render={(x) => <PlayerLink p={x.p} />} value={(x) => `${x.gp} (${Math.round((x.w / Math.max(1, x.gp)) * 100)}% wins)`} />
        <RecordList title="Biggest transfer fees" rows={data.fees} render={(x) => <><PlayerLink p={world.players[x.playerId]} /> <span className="muted small">{x.season} → {x.toTeamId ? <TeamLink t={world.teams[x.toTeamId]} short /> : '—'}</span></>} value={(x) => formatMoney(x.fee)} />
        <RecordList title="Greatest legacies (retired)" rows={data.legacy} render={(x) => (world.players[x.id] ? <PlayerLink p={world.players[x.id]} /> : <span>{x.handle}</span>)} value={(x) => x.legacy.toFixed(0)} />
      </div>
    </>
  );
}

// ---------------------------------------------------------------------------------------------
// Head-to-head
// ---------------------------------------------------------------------------------------------

function HeadToHead({ world, route }: { world: World; route: Route }) {
  const title = route.query.title || world.meta.titlesEnabled[0];
  const teams = Object.values(world.teams).filter((t) => t.titleId === title && !t.national && (t.active || Object.keys(world.h2h ?? {}).some((k) => k.includes(t.id)))).sort((a, b) => a.name.localeCompare(b.name));
  const [a, setA] = useState(route.query.a ?? '');
  const [b, setB] = useState(route.query.b ?? '');
  const A = world.teams[a];
  const B = world.teams[b];
  const h = A && B ? headToHead(world, A.id, B.id) : null;
  const fixtures = A && B ? Object.values(world.fixtures).filter((f) => f.teams.includes(A.id) && f.teams.includes(B.id)).sort((x, y) => y.day - x.day) : [];
  const finals = A && B ? world.history.flatMap((hs) => hs.champions.filter((c) => (c.teamId === A.id && c.runnerUpId === B.id) || (c.teamId === B.id && c.runnerUpId === A.id)).map((c) => ({ ...c, season: hs.season }))) : [];
  const opponents = A ? Object.entries(world.h2h ?? {}).filter(([k]) => k.split('|').includes(A.id)).map(([k, r]) => ({ id: k.split('|').find((x) => x !== A.id)!, m: r[0] })).sort((x, y) => y.m - x.m).slice(0, 8) : [];
  const pick = (setter: (v: string) => void) => (e: React.ChangeEvent<HTMLSelectElement>) => setter(e.target.value);
  const teamSelect = (value: string, on: (e: React.ChangeEvent<HTMLSelectElement>) => void, label: string) => (
    <select value={value} onChange={on} aria-label={label}>
      <option value="">{label}…</option>
      {teams.map((t: Team) => (
        <option key={t.id} value={t.id}>
          {t.name}
          {t.active ? '' : ' (closed)'}
        </option>
      ))}
    </select>
  );
  return (
    <Card
      title="Head-to-head"
      right={
        <span className="row">
          <TitleSelect world={world} value={title} onChange={(v) => { setA(''); setB(''); navigate(withQuery(route, { title: v })); }} />
          {teamSelect(a, pick(setA), 'Team A')}
          {teamSelect(b, pick(setB), 'Team B')}
        </span>
      }
    >
      {!A && <div className="muted">Pick two teams. Every series between them is counted from the start of this career.</div>}
      {A && !B && (
        <div>
          <b className="small">Most frequent opponents of {A.name}</b>
          {opponents.map((o) => (
            <div key={o.id} className="attr-row">
              <a onClick={() => setB(o.id)} className="elink" style={{ cursor: 'pointer' }}>
                {world.teams[o.id]?.name ?? o.id}
              </a>
              <span>{o.m} meetings</span>
            </div>
          ))}
        </div>
      )}
      {A && B && (
        <>
          <div className="row" style={{ justifyContent: 'center', gap: 18, margin: '8px 0' }}>
            <TeamLink t={A} />
            <span className="scoreline" style={{ margin: 0 }}>
              {h ? `${h.wins[0]} – ${h.wins[1]}` : '0 – 0'}
            </span>
            <TeamLink t={B} />
          </div>
          <div className="center muted small">
            {h ? `${h.meetings} series · games ${h.games[0]}–${h.games[1]} · ${h.finals} final${h.finals === 1 ? '' : 's'} · last met in ${h.lastSeason}` : 'They have not met yet.'} <RivalryBadge world={world} a={A.id} b={B.id} />
          </div>
          {!!finals.length && (
            <div style={{ marginTop: 8 }}>
              <b className="small">Finals</b>
              {finals.map((c, i) => (
                <div key={i} className="attr-row small">
                  <span>
                    {c.season} · {compRef(world, c.compId) ? <CompLink c={compRef(world, c.compId)} /> : c.compName}
                  </span>
                  <span>
                    won by <TeamLink t={world.teams[c.teamId]} />
                  </span>
                </div>
              ))}
            </div>
          )}
          {!!fixtures.length && (
            <div style={{ marginTop: 8 }}>
              <b className="small">This season</b>
              {fixtures.map((f) => (
                <div key={f.id} className="attr-row small clickable" onClick={() => navigate(`/match/${f.id}`)}>
                  <span>
                    {formatShort(f.day)} · <CompLink c={world.comps[f.compId]} />
                  </span>
                  <span>{f.status === 'done' ? `${world.teams[f.teams[0]]?.short} ${f.score?.[0]}–${f.score?.[1]} ${world.teams[f.teams[1]]?.short}` : f.format.label}</span>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </Card>
  );
}

export function StatsPage({ route }: { route: Route }) {
  const world = useWorld();
  const tab = route.query.tab ?? 'leaders';
  return (
    <div>
      <PageHeader title="Stats hub" sub="Leaderboards, all-time records and head-to-head between any two teams." />
      <Tabs
        tabs={[
          { id: 'leaders', label: 'Leaderboards' },
          { id: 'records', label: 'Records' },
          { id: 'h2h', label: 'Head-to-head' },
        ]}
        active={tab}
        onChange={(x) => navigate(withQuery(route, { tab: x }))}
      />
      {tab === 'leaders' && <Leaderboards world={world} route={route} />}
      {tab === 'records' && <Records world={world} route={route} />}
      {tab === 'h2h' && <HeadToHead world={world} route={route} />}
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// Season preview & review
// ---------------------------------------------------------------------------------------------

export function SeasonPage({ route }: { route: Route }) {
  const world = useWorld();
  const seasons = [...new Set([...Object.keys(world.previews ?? {}), ...Object.keys(world.reviews ?? {})].map(Number))].sort((a, b) => b - a);
  const season = Number(route.query.s || seasons[0] || world.season);
  const tab = route.query.tab ?? (world.reviews?.[season] ? 'review' : 'preview');
  const pv = world.previews?.[season];
  const rv = world.reviews?.[season];
  const [title, setTitle] = useState(world.meta.titlesEnabled[0]);
  return (
    <div>
      <PageHeader
        title={`Season ${season}`}
        sub="Preview: published on 13 January (power rankings by squad strength). Review: published when the season ends."
        right={
          <select value={season} onChange={(e) => navigate(withQuery(route, { s: e.target.value }))} aria-label="Season">
            {(seasons.length ? seasons : [world.season]).map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        }
      />
      <Tabs
        tabs={[
          { id: 'preview', label: 'Preview' },
          { id: 'review', label: 'Review' },
        ]}
        active={tab}
        onChange={(x) => navigate(withQuery(route, { tab: x }))}
      />
      {tab === 'preview' && !pv && (
        <Card>
          <div className="muted">No preview for {season} yet — it is published on 13 January, after the pre-season transfer window.</div>
        </Card>
      )}
      {tab === 'preview' && pv && (
        <>
          <div className="row" style={{ marginBottom: 8 }}>
            <TitleSelect world={world} value={title} onChange={setTitle} />
          </div>
          {pv.titles[title] && (
            <>
              <div className="grid2">
                <Card title="World Championship favourites">
                  {pv.titles[title].worldsFavourites.map((id, i) => (
                    <div key={id} className="attr-row">
                      <span>
                        {i + 1}. <TeamLink t={world.teams[id]} />
                      </span>
                    </div>
                  ))}
                </Card>
                <Card title="Players to watch · breakout candidates">
                  {pv.titles[title].playersToWatch.map((id) => (
                    <div key={id} className="attr-row">
                      <PlayerLink p={world.players[id]} />
                      <span className="muted small">star</span>
                    </div>
                  ))}
                  {pv.titles[title].breakoutCandidates.map((id) => (
                    <div key={id} className="attr-row">
                      <PlayerLink p={world.players[id]} />
                      <span className="muted small">breakout candidate</span>
                    </div>
                  ))}
                </Card>
              </div>
              {!!pv.titles[title].biggestMoves.length && (
                <Card title="Biggest off-season moves">
                  {pv.titles[title].biggestMoves.map((m) => (
                    <div key={m.playerId + m.fee} className="attr-row">
                      <span>
                        <PlayerLink p={world.players[m.playerId]} /> {m.fromTeamId ? <TeamLink t={world.teams[m.fromTeamId]} short /> : 'free agency'} → {m.toTeamId ? <TeamLink t={world.teams[m.toTeamId]} short /> : '—'}
                      </span>
                      <b>{formatMoney(m.fee)}</b>
                    </div>
                  ))}
                </Card>
              )}
              {pv.titles[title].leagues.map((l) => (
                <Card key={l.leagueId} title={`Power rankings — ${world.leagues[l.leagueId]?.name ?? l.leagueId}`}>
                  <table className="table">
                    <thead>
                      <tr>
                        <th>#</th>
                        <th>Team</th>
                        <th className="num">Strength</th>
                        <th className="num">Elo</th>
                        <th>Off-season</th>
                      </tr>
                    </thead>
                    <tbody>
                      {l.teams.map((x) => (
                        <tr key={x.teamId}>
                          <td>{x.rank}</td>
                          <td>
                            <TeamLink t={world.teams[x.teamId]} />
                          </td>
                          <td className="num">{x.strength.toFixed(1)}</td>
                          <td className="num">{x.elo}</td>
                          <td className="muted small">{x.note ? <RichText world={world} text={x.note} /> : ''}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </Card>
              ))}
            </>
          )}
        </>
      )}
      {tab === 'review' && !rv && (
        <Card>
          <div className="muted">The {season} review is published when the season ends (22 November).</div>
        </Card>
      )}
      {tab === 'review' && rv && (
        <>
          <Card title="Storylines">
            {!rv.storylines.length && <div className="muted">A quiet season.</div>}
            {rv.storylines.map((s, i) => (
              <div key={i} style={{ padding: '4px 0', borderBottom: '1px solid var(--border)' }}>
                {s.titleId && <TitleTag id={s.titleId} />} <span className="tag">{s.kind}</span> <RichText world={world} text={s.text} refs={s.refs} />
              </div>
            ))}
          </Card>
          <div className="grid2">
            <Card title="Champions">
              {rv.champions
                .filter((c) => c.tier === 'worlds' || c.tier === 'international' || c.tier === 'playoffs')
                .map((c) => (
                  <div key={c.compId} className="attr-row">
                    <span>
                      <TitleTag id={c.titleId} /> {compRef(world, c.compId) ? <CompLink c={compRef(world, c.compId)} /> : c.compName}
                    </span>
                    <TeamLink t={world.teams[c.teamId]} />
                  </div>
                ))}
            </Card>
            <Card title="Season awards">
              {rv.awards.map((a, i) => (
                <div key={i} className="attr-row">
                  <span>
                    {a.titleId && <TitleTag id={a.titleId} />} {a.label}
                  </span>
                  <span>{a.playerId ? <PlayerLink p={world.players[a.playerId]} /> : a.staffId ? <StaffLink s={world.staff[a.staffId]} /> : a.teamId ? <TeamLink t={world.teams[a.teamId]} /> : ''}</span>
                </div>
              ))}
            </Card>
          </div>
          {!!rv.predictions.length && (
            <Card title="Preview vs reality">
              <div className="grid2">
                {rv.predictions.map((pr) => (
                  <div key={pr.leagueId}>
                    <b className="small">
                      <TitleTag id={pr.titleId} /> {world.leagues[pr.leagueId]?.name}
                    </b>
                    <table className="table small">
                      <thead>
                        <tr>
                          <th>Team</th>
                          <th className="num">Tipped</th>
                          <th className="num">Finished</th>
                        </tr>
                      </thead>
                      <tbody>
                        {[...pr.rows].sort((x, y) => x.actual - y.actual).map((r) => (
                          <tr key={r.teamId}>
                            <td>
                              <TeamLink t={world.teams[r.teamId]} />
                            </td>
                            <td className="num">{r.predicted}</td>
                            <td className={`num ${r.actual < r.predicted ? 'pos' : r.actual > r.predicted ? 'neg' : ''}`}>{r.actual}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ))}
              </div>
            </Card>
          )}
        </>
      )}
    </div>
  );
}

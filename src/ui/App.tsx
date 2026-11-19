import { useEffect, useMemo, useState } from 'react';
import { useGame } from './store';
import { useRoute, navigate, Link, type Route } from './router';
import { formatDate } from '../core/date';
import { getTitle } from '../data/titles/catalogue';
import { OrgBadge } from './components/common';
import { Welcome, JobOffers } from './pages/Welcome';
import { Home } from './pages/Home';
import { Inbox } from './pages/Inbox';
import { OrgPage } from './pages/OrgPage';
import { TeamsPage, TeamPage } from './pages/Teams';
import { PlayersPage, ComparePage } from './pages/Players';
import { PlayerPage } from './pages/PlayerPage';
import { StaffDirectory, StaffPage } from './pages/Staff';
import { CompetitionsPage, CompetitionPage } from './pages/Competitions';
import { SchedulePage } from './pages/Schedule';
import { MarketPage } from './pages/Market';
import { ScoutingPage } from './pages/Scouting';
import { TrainingPage } from './pages/Training';
import { TacticsPage } from './pages/Tactics';
import { MatchPage, MatchDayPage } from './pages/Match';
import { LiveMatchPage } from './pages/LiveMatch';
import { FinancesPage, FacilitiesPage } from './pages/Finances';
import { WorldPage, TitlePage } from './pages/World';
import { HistoryPage, AwardsPage } from './pages/History';
import { SettingsPage } from './pages/Settings';
import { EditorPage } from './pages/Editor';
import { NewsPage } from './pages/News';

const NAV: Array<{ sec?: string; page?: string; label?: string }> = [
  { page: 'home', label: 'Home' },
  { page: 'inbox', label: 'Inbox' },
  { page: 'news', label: 'News' },
  { sec: 'Organisation' },
  { page: 'org', label: 'Organisation' },
  { page: 'teams', label: 'Teams' },
  { page: 'players', label: 'Players' },
  { page: 'staff', label: 'Staff' },
  { sec: 'Competition' },
  { page: 'competitions', label: 'Competitions' },
  { page: 'schedule', label: 'Schedule' },
  { page: 'tactics', label: 'Tactics / Draft' },
  { page: 'training', label: 'Training' },
  { sec: 'Recruitment' },
  { page: 'market', label: 'Market' },
  { page: 'scouting', label: 'Scouting' },
  { sec: 'Business' },
  { page: 'finances', label: 'Finances' },
  { page: 'facilities', label: 'Facilities' },
  { sec: 'Universe' },
  { page: 'world', label: 'World' },
  { page: 'history', label: 'History' },
  { page: 'awards', label: 'Awards' },
  { sec: 'System' },
  { page: 'settings', label: 'Settings' },
  { page: 'editor', label: 'Database Editor' },
];

function Search() {
  const world = useGame((s) => s.world)!;
  useGame((s) => s.version);
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const results = useMemo(() => {
    const s = q.trim().toLowerCase();
    if (s.length < 2) return [];
    const out: Array<{ to: string; label: string; kind: string; sub?: string }> = [];
    for (const p of Object.values(world.players)) {
      if (out.length > 30) break;
      if (p.handle.toLowerCase().includes(s) || `${p.first} ${p.last}`.toLowerCase().includes(s)) out.push({ to: `/player/${p.id}`, label: p.handle, kind: 'Player', sub: `${p.first} ${p.last} · ${getTitle(p.titleId).short}${p.retired ? ' · retired' : ''}` });
    }
    for (const o of Object.values(world.orgs)) if (o.name.toLowerCase().includes(s) || o.short.toLowerCase() === s) out.push({ to: `/org/${o.id}`, label: o.name, kind: 'Organisation', sub: `${o.teams.length} divisions` });
    for (const t of Object.values(world.teams)) if (t.active && t.name.toLowerCase().includes(s)) out.push({ to: `/team/${t.id}`, label: t.name, kind: 'Team', sub: getTitle(t.titleId).short });
    for (const c of Object.values(world.comps)) if (c.name.toLowerCase().includes(s)) out.push({ to: `/comp/${c.id}`, label: c.name, kind: 'Competition' });
    for (const st of Object.values(world.staff)) if (`${st.first} ${st.last}`.toLowerCase().includes(s) || st.handle?.toLowerCase().includes(s)) out.push({ to: `/staff/${st.id}`, label: st.handle ?? `${st.first} ${st.last}`, kind: 'Staff' });
    return out.slice(0, 40);
  }, [q, world]);
  return (
    <div className="search">
      <input
        placeholder="Search players, teams, organisations, competitions…"
        value={q}
        onChange={(e) => {
          setQ(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 200)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && results[0]) {
            navigate(results[0].to);
            setOpen(false);
          }
        }}
        aria-label="Global search"
      />
      {open && results.length > 0 && (
        <div className="search-results">
          {results.map((r) => (
            <a key={r.kind + r.to} href={`#${r.to}`} onClick={() => setOpen(false)}>
              <span className="tag">{r.kind}</span>
              <span>{r.label}</span>
              <span className="muted small">{r.sub}</span>
            </a>
          ))}
        </div>
      )}
    </div>
  );
}

function AdvanceControls() {
  const advance = useGame((s) => s.advance);
  const busy = useGame((s) => s.busy);
  const world = useGame((s) => s.world)!;
  const [menu, setMenu] = useState(false);
  const run = async (mode: Parameters<typeof advance>[0], stop = true) => {
    setMenu(false);
    const r = await advance(mode, stop);
    if (r?.stoppedFor === 'user-match') navigate('/matchday');
  };
  return (
    <div className="row" style={{ position: 'relative' }}>
      <button className="btn green" disabled={!!busy} onClick={() => run({ kind: 'next-event' })} title="Advance to your next match or relevant event">
        {world.pendingUserMatch ? 'Continue ▸' : 'Continue ▸'}
      </button>
      <button className="btn ghost" disabled={!!busy} onClick={() => setMenu(!menu)} aria-label="More advance options">
        ▾
      </button>
      {menu && (
        <div className="search-results" style={{ top: 36, right: 0, left: 'auto', width: 220 }}>
          <a onClick={() => run({ kind: 'days', n: 1 })}>Advance 1 day</a>
          <a onClick={() => run({ kind: 'days', n: 7 })}>Advance 1 week</a>
          <a onClick={() => run({ kind: 'days', n: 30 })}>Advance 1 month</a>
          <a onClick={() => run({ kind: 'end-season' }, false)}>To end of season (auto-play)</a>
          <a onClick={() => run({ kind: 'seasons', n: 1 }, false)}>Simulate 1 full season</a>
          <a onClick={() => run({ kind: 'seasons', n: 5 }, false)}>Simulate 5 seasons</a>
          <a onClick={() => run({ kind: 'seasons', n: 10 }, false)}>Simulate 10 seasons</a>
        </div>
      )}
    </div>
  );
}

function crumbsFor(route: Route, world: NonNullable<ReturnType<typeof useGame.getState>['world']>): Array<{ label: string; to?: string }> {
  const out: Array<{ label: string; to?: string }> = [{ label: 'Home', to: '/' }];
  const id = route.id;
  switch (route.page) {
    case 'player': {
      const p = id ? world.players[id] : undefined;
      out.push({ label: 'Players', to: '/players' });
      const t = p?.teamId ? world.teams[p.teamId] : undefined;
      if (t) {
        out.push({ label: world.orgs[t.orgId]?.name ?? '', to: `/org/${t.orgId}` });
        out.push({ label: getTitle(t.titleId).short, to: `/team/${t.id}` });
      }
      if (p) out.push({ label: p.handle });
      break;
    }
    case 'team': {
      const t = id ? world.teams[id] : undefined;
      out.push({ label: 'Teams', to: '/teams' });
      if (t) {
        out.push({ label: world.orgs[t.orgId]?.name ?? '', to: `/org/${t.orgId}` });
        out.push({ label: `${t.name} (${getTitle(t.titleId).short})` });
      }
      break;
    }
    case 'org':
      out.push({ label: 'Organisations', to: '/world?tab=orgs' });
      if (id && world.orgs[id]) out.push({ label: world.orgs[id].name });
      break;
    case 'comp': {
      const c = id ? world.comps[id] : undefined;
      out.push({ label: 'Competitions', to: '/competitions' });
      if (c) {
        out.push({ label: getTitle(c.titleId).short, to: `/title/${c.titleId}` });
        out.push({ label: c.name });
      }
      break;
    }
    case 'match':
    case 'live': {
      const f = id ? world.fixtures[id] : undefined;
      const c = f ? world.comps[f.compId] : undefined;
      out.push({ label: 'Competitions', to: '/competitions' });
      if (c) out.push({ label: c.name, to: `/comp/${c.id}` });
      out.push({ label: route.page === 'live' ? 'Live match' : 'Match' });
      break;
    }
    case 'title':
      out.push({ label: 'World', to: '/world' });
      if (id) out.push({ label: getTitle(id).name });
      break;
    case 'staff':
      out.push({ label: 'Staff', to: '/staff' });
      if (id && world.staff[id]) out.push({ label: `${world.staff[id].first} ${world.staff[id].last}` });
      break;
    case 'home':
      break;
    default:
      out.push({ label: NAV.find((n) => n.page === route.page)?.label ?? route.page });
  }
  return out;
}

function Page({ route }: { route: Route }) {
  switch (route.page) {
    case 'home':
      return <Home />;
    case 'inbox':
      return <Inbox />;
    case 'news':
      return <NewsPage />;
    case 'org':
      return <OrgPage id={route.id} />;
    case 'teams':
      return <TeamsPage />;
    case 'team':
      return <TeamPage id={route.id!} route={route} />;
    case 'players':
      return <PlayersPage route={route} />;
    case 'compare':
      return <ComparePage route={route} />;
    case 'player':
      return <PlayerPage id={route.id!} route={route} />;
    case 'staff':
      return route.id ? <StaffPage id={route.id} /> : <StaffDirectory />;
    case 'competitions':
      return <CompetitionsPage route={route} />;
    case 'comp':
      return <CompetitionPage id={route.id!} route={route} />;
    case 'schedule':
      return <SchedulePage route={route} />;
    case 'market':
      return <MarketPage route={route} />;
    case 'scouting':
      return <ScoutingPage />;
    case 'training':
      return <TrainingPage route={route} />;
    case 'tactics':
      return <TacticsPage route={route} />;
    case 'match':
      return <MatchPage id={route.id!} route={route} />;
    case 'matchday':
      return <MatchDayPage />;
    case 'live':
      return <LiveMatchPage id={route.id!} />;
    case 'finances':
      return <FinancesPage route={route} />;
    case 'facilities':
      return <FacilitiesPage />;
    case 'world':
      return <WorldPage route={route} />;
    case 'title':
      return <TitlePage id={route.id!} route={route} />;
    case 'history':
      return <HistoryPage route={route} />;
    case 'awards':
      return <AwardsPage route={route} />;
    case 'settings':
      return <SettingsPage />;
    case 'editor':
      return <EditorPage route={route} />;
    default:
      return <Home />;
  }
}

export function App() {
  const world = useGame((s) => s.world);
  useGame((s) => s.version);
  const theme = useGame((s) => s.theme);
  const busy = useGame((s) => s.busy);
  const toast = useGame((s) => s.toast);
  const save = useGame((s) => s.save);
  const unsaved = useGame((s) => s.unsaved);
  const route = useRoute();
  const [navOpen, setNavOpen] = useState(false);

  // Warn before closing or reloading the tab with progress that has not been saved yet.
  useEffect(() => {
    if (!unsaved || !world) return;
    const onLeave = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', onLeave);
    return () => window.removeEventListener('beforeunload', onLeave);
  }, [unsaved, world]);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);
  useEffect(() => setNavOpen(false), [route.path]);
  useEffect(() => {
    const el = document.querySelector('.main');
    if (el) el.scrollTop = 0;
  }, [route.page, route.id]);

  const overlay = (
    <>
      {busy && (
        <div className="busy" role="status" aria-live="polite">
          <div className="busy-box">
            <div>{busy.label}</div>
            {busy.total > 0 && (
              <>
                <div className="progress">
                  <span style={{ width: `${(busy.done / busy.total) * 100}%` }} />
                </div>
                <div className="muted small" style={{ marginTop: 6 }}>
                  {busy.done} / {busy.total} days
                </div>
              </>
            )}
          </div>
        </div>
      )}
      {toast && <div className={`toast ${toast.kind}`}>{toast.text}</div>}
    </>
  );

  if (world?.meta.unemployed) {
    return (
      <>
        <JobOffers />
        {overlay}
      </>
    );
  }
  if (!world || !world.meta.userOrgId) {
    return (
      <>
        <Welcome />
        {overlay}
      </>
    );
  }
  const userOrg = world.orgs[world.meta.userOrgId];
  const unread = world.inbox.filter((m) => !m.read).length;
  const crumbs = crumbsFor(route, world);
  return (
    <div className="shell">
      <div className="topbar">
        <button className="btn ghost sm hamburger" onClick={() => setNavOpen(!navOpen)} aria-label="Menu">
          ☰
        </button>
        <span className="brand hide-sm">
          ULTIMATE <span>ESPORTS</span> MANAGER
        </span>
        <span className="row navbtns">
          <button className="btn ghost sm" onClick={() => history.back()} aria-label="Back">
            ←
          </button>
          <button className="btn ghost sm" onClick={() => history.forward()} aria-label="Forward">
            →
          </button>
        </span>
        <Search />
        <span className="date-pill" title={`Season ${world.season}`}>
          {formatDate(world.day, true)} · S{world.season}
        </span>
        <AdvanceControls />
        <button className="btn ghost sm hide-sm" onClick={() => save()} title={unsaved ? 'Unsaved changes' : 'All changes saved'}>
          Save{unsaved ? ' •' : ''}
        </button>
      </div>
      <nav className={`sidebar ${navOpen ? 'open' : ''}`} aria-label="Main navigation">
        <div className="row" style={{ padding: '6px 16px 10px' }}>
          <OrgBadge org={userOrg} size={22} />
          <Link to={`/org/${userOrg.id}`} className="elink">
            {userOrg.name}
          </Link>
        </div>
        {NAV.map((n, i) =>
          n.sec ? (
            <div key={i} className="sec">
              {n.sec}
            </div>
          ) : (
            <a key={n.page} href={`#/${n.page === 'home' ? '' : n.page}`} className={route.page === n.page || (n.page === 'home' && route.page === 'home') ? 'active' : ''}>
              {n.label}
              {n.page === 'inbox' && unread > 0 && <span className="badge">{unread}</span>}
            </a>
          ),
        )}
      </nav>
      <main className="main">
        <div className="crumbs" aria-label="Breadcrumbs">
          {crumbs.map((c, i) => (
            <span key={i}>
              {c.to ? <Link to={c.to}>{c.label}</Link> : <span>{c.label}</span>}
              {i < crumbs.length - 1 && <span> › </span>}
            </span>
          ))}
        </div>
        <Page route={route} />
      </main>
      {overlay}
    </div>
  );
}

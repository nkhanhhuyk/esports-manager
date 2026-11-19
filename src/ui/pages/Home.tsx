import { useWorld, useGame } from '../store';
import { Card, Money, OrgBadge, PageHeader, Stat, TeamLink, TitleTag, CompLink } from '../components/common';
import { Link, navigate } from '../router';
import { formatDate, formatShort } from '../../core/date';
import { fixtureLabel, recentResults, scoreLabel, upcomingFixtures, userTeams } from '../helpers';
import { monthlyBreakdown } from '../../systems/finance';
import { sortStandings } from '../../competition/stages';
import { getTitle } from '../../data/titles/catalogue';
import { userFixturesOn } from '../../game/advance';
import { computeTeamStrength } from '../../sim/common/team';

export function Home() {
  const world = useWorld();
  const org = world.orgs[world.meta.userOrgId!];
  const teams = userTeams(world);
  const ids = teams.map((t) => t.id);
  const up = upcomingFixtures(world, ids, 8);
  const res = recentResults(world, ids, 8);
  const mb = monthlyBreakdown(world, org);
  const rev = Object.values(mb.revenue).reduce((a, b) => a + b, 0);
  const exp = Object.values(mb.expenses).reduce((a, b) => a + b, 0);
  const pendingOffers = world.offers.filter((o) => o.user && o.status === 'pending');
  const todayMatches = userFixturesOn(world, world.day + 1);
  const news = [...world.news].sort((a, b) => b.day - a.day || b.importance - a.importance).slice(0, 8);
  return (
    <div>
      <PageHeader
        badge={<OrgBadge org={org} size={40} />}
        title={org.name}
        sub={`${world.meta.managerName} · Coach / General Manager · ${formatDate(world.day, true)} · Season ${world.season}`}
        right={<Link to="/matchday">{todayMatches.length ? <span className="btn warn">Match day: {todayMatches.length} match(es)</span> : null}</Link>}
      />
      <div className="stats">
        <Stat label="Balance" value={<Money v={org.balance} />} sub={`${rev - exp >= 0 ? '+' : ''}${Math.round((rev - exp) / 1000)}K / month`} />
        <Stat label="Reputation" value={org.rep.toFixed(0)} sub={`${org.fans.toLocaleString()} fans`} />
        <Stat label="Divisions" value={teams.length} sub={teams.map((t) => getTitle(t.titleId).short).join(', ')} />
        <Stat label="Trophies" value={teams.reduce((a, t) => a + t.trophies.length, 0)} sub={`prestige ${org.prestige.toFixed(0)}`} />
        <Stat label="Unread inbox" value={world.inbox.filter((m) => !m.read).length} sub={pendingOffers.length ? `${pendingOffers.length} offer(s) await you` : undefined} />
      </div>
      <div className="grid2">
        <Card title="Upcoming matches" right={<Link to="/schedule">Schedule</Link>}>
          {!up.length && <div className="muted">No scheduled matches. Competitions are generated as the season progresses.</div>}
          <table className="table">
            <tbody>
              {up.map((f) => (
                <tr key={f.id} className="clickable" onClick={() => navigate(`/match/${f.id}`)}>
                  <td>{formatShort(f.day)}</td>
                  <td>
                    <TitleTag id={f.titleId} link={false} />
                  </td>
                  <td>{fixtureLabel(world, f)}</td>
                  <td className="muted">{world.comps[f.compId]?.short}</td>
                  <td>{f.format.label}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
        <Card title="Recent results">
          {!res.length && <div className="muted">No results yet.</div>}
          <table className="table">
            <tbody>
              {res.map((f) => {
                const mine = f.teams.find((t) => ids.includes(t))!;
                const won = f.winner === mine;
                return (
                  <tr key={f.id} className="clickable" onClick={() => navigate(`/match/${f.id}`)}>
                    <td>{formatShort(f.day)}</td>
                    <td>
                      <TitleTag id={f.titleId} link={false} />
                    </td>
                    <td>{fixtureLabel(world, f)}</td>
                    <td>
                      <b className={won ? 'pos' : 'neg'}>{f.format.kind === 'br' ? `${f.brPoints?.[mine] ?? 0} pts` : won ? 'W' : 'L'}</b> {scoreLabel(f)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Card>
      </div>
      <div className="grid2">
        <Card title="Your divisions">
          <table className="table">
            <thead>
              <tr>
                <th>Team</th>
                <th>Title</th>
                <th>League position</th>
                <th>Strength</th>
                <th>Elo</th>
              </tr>
            </thead>
            <tbody>
              {teams.map((t) => {
                const comp = Object.values(world.comps).filter((c) => c.leagueId === t.leagueId && c.season === world.season && c.status !== 'pending').sort((a, b) => (b.split ?? 0) - (a.split ?? 0))[0];
                const stage = comp?.stages[0];
                const table = stage ? sortStandings(world, stage.standings, stage.type === 'br-points') : [];
                const pos = table.findIndex((r) => r.teamId === t.id);
                const row = table[pos];
                return (
                  <tr key={t.id}>
                    <td>
                      <TeamLink t={t} />
                    </td>
                    <td>
                      <TitleTag id={t.titleId} />
                    </td>
                    <td>{row ? <CompLink c={comp} /> : <span className="muted">{t.leagueId ? world.leagues[t.leagueId]?.name : 'No league'}</span>}{row ? ` · #${pos + 1} (${stage.type === 'br-points' ? `${row.pts} pts` : `${row.w}-${row.l}`})` : ''}</td>
                    <td>{computeTeamStrength(world, t).toFixed(1)}</td>
                    <td>{t.elo}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Card>
        <Card title="Latest news" right={<Link to="/news">All news</Link>}>
          {news.map((n) => (
            <div key={n.id} style={{ padding: '4px 0', borderBottom: '1px solid var(--border)' }}>
              <div>
                {n.titleId && <TitleTag id={n.titleId} link={false} />} <b>{n.title}</b>
              </div>
              <div className="muted small">
                {formatShort(n.day)} · {n.body}
              </div>
            </div>
          ))}
        </Card>
      </div>
      <Card title="Inbox" right={<Link to="/inbox">Open inbox</Link>}>
        {[...world.inbox]
          .reverse()
          .slice(0, 6)
          .map((m) => (
            <div key={m.id} className={`inbox-item ${m.read ? '' : 'unread'}`} onClick={() => navigate(`/inbox?m=${m.id}`)}>
              {m.subject} <span className="muted small">— {m.from}, {formatShort(m.day)}</span>
            </div>
          ))}
      </Card>
      <HelpCard />
    </div>
  );
}

function HelpCard() {
  const world = useWorld();
  const advance = useGame((s) => s.advance);
  if (world.history.length > 0) return null;
  return (
    <Card title="Getting started">
      <ul>
        <li>Use <b>Continue ▸</b> to advance to your next match day; the ▾ menu advances a day, week, month, season or several seasons.</li>
        <li>On match days choose <b>Play live</b> (lineup, map veto, draft and game-by-game commentary) or let your coaching staff simulate.</li>
        <li>Set lineups, playstyle and character pools under <Link to="/tactics">Tactics / Draft</Link>; training focus and intensity under <Link to="/training">Training</Link>.</li>
        <li>Recruit in the <Link to="/market">Market</Link> (free agents, bids, loans, trades). The off-season window closes on 12 January.</li>
        <li>Everything is clickable: players, teams, organisations and competitions all have profile pages.</li>
      </ul>
      <button className="btn ghost sm" onClick={() => advance({ kind: 'days', n: 1 })}>
        Advance one day
      </button>
    </Card>
  );
}

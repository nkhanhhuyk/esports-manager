import { WeekTimetable } from '../components/Sessions';
import { RivalriesCard } from '../components/Fans';
import { useMemo, useState } from 'react';
import { useGame, useWorld } from '../store';
import { eligibleNations } from '../../systems/nations';
import { COUNTRY_BY_CODE } from '../../data/countries';
import { userNationalTeam } from '../helpers';
import { Card, CompLink, Flag, Money, OrgLink, PageHeader, PlayerLink, Provenance, Rating, Stat, StaffLink, TeamLink, TitleTag, Tabs, OrgBadge, TeamBadge, age, compRef } from '../components/common';
import { DataTable } from '../components/DataTable';
import { getTitle } from '../../data/titles/catalogue';
import { computeTeamStrength } from '../../sim/common/team';
import { fixtureLabel, isUserTeamId, roleE, roleName, rosterPlayers, scoreLabel, seasonAgg, teamFixtures, userTeams, kda } from '../helpers';
import { formatShort } from '../../core/date';
import { Link, navigate, withQuery, type Route } from '../router';
import { teamPayroll, staffPayroll } from '../../systems/contracts';
import { STAFF_ROLE_LABEL } from '../../gen/staff';
import { potentialRange } from '../../systems/scouting';
import { regionName } from '../../data/countries';
import { formatMoney } from '../../core/math';
import { pickLabel, picksOwnedBy, pickValue } from '../../systems/draft';
import { activeRegistrations, AMENDMENTS, amendRegistration, maxRegistered, plannedRegistration } from '../../systems/registration';
import { inTransferWindow } from '../../systems/transfers';
import type { Team } from '../../model/types';

export function TeamsPage() {
  const world = useWorld();
  const mine = userTeams(world);
  const [title, setTitle] = useState('');
  const [dormant, setDormant] = useState(false);
  const hasDormant = useMemo(() => Object.values(world.teams).some((t) => !t.active && t.lastSeen), [world]);
  const all = Object.values(world.teams).filter((t) => (dormant ? !t.active && t.lastSeen : t.active) && (!title || t.titleId === title));
  return (
    <div>
      <PageHeader title="Teams" sub="Your divisions and every active team in the universe" />
      <div className="grid3">
        {mine.map((t) => (
          <Card key={t.id} title={<TeamLink t={t} />} right={<TitleTag id={t.titleId} />}>
            <div className="row">
              <Stat label="Strength" value={computeTeamStrength(world, t).toFixed(1)} />
              <Stat label="Elo" value={t.elo} />
              <Stat label="Chemistry" value={t.chemistry.toFixed(0)} />
            </div>
            <div className="muted small" style={{ marginTop: 6 }}>
              {t.leagueId ? world.leagues[t.leagueId]?.name : 'Development squad (no league)'} · {t.roster.length} players
            </div>
          </Card>
        ))}
      </div>
      <NationalJobCard />
      <Card title="Team directory">
        <div className="row" style={{ marginBottom: 6 }}>
          <select value={title} onChange={(e) => setTitle(e.target.value)} aria-label="Title filter">
            <option value="">All titles</option>
            {world.meta.titlesEnabled.map((id) => (
              <option key={id} value={id}>
                {getTitle(id).name}
              </option>
            ))}
          </select>
          {hasDormant && (
            <label className="small">
              <input type="checkbox" checked={dormant} onChange={(e) => setDormant(e.target.checked)} /> Dormant real teams (no league place)
            </label>
          )}
        </div>
        <DataTable
          id="team-dir"
          rows={all}
          rowKey={(t) => t.id}
          initialSort={{ key: 'elo', desc: true }}
          filter={(t, q) => t.name.toLowerCase().includes(q) || t.short.toLowerCase().includes(q)}
          onRowClick={(t) => navigate(`/team/${t.id}`)}
          highlight={(t) => isUserTeamId(world, t.id)}
          columns={[
            { key: 'name', label: 'Team', value: (t) => t.name, render: (t) => (<span><TeamBadge world={world} team={t} size={14} /> {t.name}</span>) },
            { key: 'title', label: 'Title', value: (t) => t.titleId, render: (t) => <TitleTag id={t.titleId} link={false} /> },
            { key: 'region', label: 'Region', value: (t) => regionName(t.region) },
            { key: 'league', label: 'League', value: (t) => (t.leagueId ? world.leagues[t.leagueId]?.short : '—') ?? '' },
            { key: 'tier', label: 'Tier', value: (t) => t.tier },
            { key: 'str', label: 'Strength', align: 'right', value: (t) => computeTeamStrength(world, t), render: (t) => computeTeamStrength(world, t).toFixed(1) },
            { key: 'elo', label: 'Elo', align: 'right', value: (t) => t.elo },
            { key: 'troph', label: 'Trophies', align: 'right', value: (t) => t.trophies.length },
          ]}
        />
      </Card>
    </div>
  );
}

function RosterTable({ team }: { team: Team }) {
  const world = useWorld();
  const t = getTitle(team.titleId);
  const players = rosterPlayers(world, team);
  const slot = (pid: string) => team.lineup.find((s) => s.playerId === pid);
  return (
    <DataTable
      id="roster"
      rows={players}
      rowKey={(p) => p.id}
      initialSort={{ key: 'slot', desc: false }}
      onRowClick={(p) => navigate(`/player/${p.id}`)}
      highlight={(p) => !!slot(p.id)}
      columns={[
        { key: 'slot', label: 'Pos', value: (p) => (slot(p.id) ? t.lineup.indexOf(slot(p.id)!.roleId) : 99), render: (p) => (slot(p.id) ? <b>{roleName(t.id, slot(p.id)!.roleId)}</b> : <span className="muted">SUB</span>) },
        { key: 'handle', label: 'Player', value: (p) => p.handle, render: (p) => (<span><Flag cc={p.nat} /><PlayerLink p={p} /></span>) },
        { key: 'role', label: 'Role', value: (p) => p.roles[0], render: (p) => roleName(p.titleId, p.roles[0]) },
        { key: 'age', label: 'Age', align: 'right', value: (p) => age(world, p.birth) },
        { key: 'e', label: 'E (pos)', title: 'Effectiveness in current/primary position', align: 'right', value: (p) => roleE(p, t.id, slot(p.id)?.roleId ?? p.roles[0]), render: (p) => <Rating v={roleE(p, t.id, slot(p.id)?.roleId ?? p.roles[0])} /> },
        { key: 'ca', label: 'CA', align: 'right', value: (p) => p.ca, render: (p) => <Rating v={p.ca} /> },
        { key: 'pa', label: 'PA', title: 'Scouted potential range', align: 'right', value: (p) => p.pa, render: (p) => { const [lo, hi] = potentialRange(world, p); return lo === hi ? `${lo}` : `${lo}-${hi}`; } },
        { key: 'input', label: 'Input', title: `Proficiency on ${t.input}`, align: 'right', value: (p) => p.input[t.input === 'touch' ? 0 : t.input === 'mkb' ? 1 : 2], render: (p) => p.input[t.input === 'touch' ? 0 : t.input === 'mkb' ? 1 : 2].toFixed(0) },
        { key: 'form', label: 'Form', align: 'right', value: (p) => p.form, render: (p) => <span className={p.form >= 0 ? 'pos' : 'neg'}>{p.form.toFixed(1)}</span> },
        { key: 'morale', label: 'Morale', align: 'right', value: (p) => p.morale, render: (p) => p.morale.toFixed(0) },
        { key: 'fat', label: 'Fatigue', align: 'right', value: (p) => p.fatigue, render: (p) => <span className={p.fatigue > 60 ? 'neg' : ''}>{p.fatigue.toFixed(0)}{p.injury && p.injury.untilDay > world.day ? ' 🩹' : ''}{p.leave && p.leave.untilDay > world.day ? ' · break' : ''}</span> },
        { key: 'sal', label: 'Salary', align: 'right', value: (p) => p.contract?.salary ?? 0, render: (p) => formatMoney(p.contract?.salary ?? 0) },
        { key: 'end', label: 'Until', align: 'right', value: (p) => p.contract?.endSeason ?? 0, render: (p) => (<span>{p.contract?.endSeason ?? '—'}{p.contract?.loan ? ' (loan)' : ''}</span>) },
      ]}
    />
  );
}

export function TeamPage({ id, route }: { id: string; route: Route }) {
  const world = useWorld();
  const team = world.teams[id];
  const tab = route.query.tab ?? 'roster';
  if (!team) return <div>Team not found.</div>;
  const t = getTitle(team.titleId);
  const org = world.orgs[team.orgId];
  const fixtures = teamFixtures(world, team.id);
  const staff = team.staff.map((s) => world.staff[s]).filter(Boolean);
  const academy = Object.values(world.teams).filter((x) => x.parentTeamId === team.id && x.active);
  const parent = team.parentTeamId ? world.teams[team.parentTeamId] : null;
  return (
    <div>
      <PageHeader
        badge={<TeamBadge world={world} team={team} size={48} />}
        title={team.name}
        sub={
          <span>
            <TitleTag id={team.titleId} /> · <OrgLink org={org} /> · {regionName(team.region)} · {team.tier}
            {team.leagueId && ` · ${world.leagues[team.leagueId]?.name}`}
            {parent && (<> · academy of <TeamLink t={parent} /></>)}
            {!team.active && (team.lastSeen ? ` · DORMANT (last seen ${team.lastSeen})` : ' · CLOSED')}
            <Provenance world={world} real={team.real} kind="team" />
          </span>
        }
        right={isUserTeamId(world, team.id) ? (<><button className="btn ghost sm" onClick={() => navigate(`/tactics?team=${team.id}`)}>Tactics</button><button className="btn ghost sm" onClick={() => navigate(`/training?team=${team.id}`)}>Training</button></>) : undefined}
      />
      <div className="stats">
        <Stat label="Strength" value={computeTeamStrength(world, team).toFixed(1)} />
        <Stat label="Elo" value={team.elo} />
        <Stat label="Chemistry" value={team.chemistry.toFixed(0)} />
        <Stat label="Meta adaptation" value={team.metaAdapt.toFixed(0)} />
        <Stat label="Payroll" value={formatMoney(teamPayroll(world, team))} sub={`budget ${formatMoney(team.salaryBudget)}`} />
        <Stat label="Staff cost" value={formatMoney(staffPayroll(world, team))} />
        <Stat label="Style" value={team.prefs.style} sub={`aggression ${team.prefs.aggression}`} />
      </div>
      <Tabs
        tabs={[
          { id: 'roster', label: 'Roster' },
          { id: 'schedule', label: 'Schedule & Results' },
          { id: 'stats', label: 'Statistics' },
          { id: 'staff', label: 'Staff' },
          { id: 'history', label: 'History' },
          ...(team.training.week ? [{ id: 'training', label: 'Training week' }] : []),
        ]}
        active={tab}
        onChange={(x) => navigate(withQuery(route, { tab: x }))}
      />
      {tab === 'roster' && (
        <>
          <Card title={`Roster (${team.roster.length}) — ${t.teamSize} starters`}>
            <RosterTable team={team} />
          </Card>
          <RegistrationCard team={team} />
          <TeamPicks team={team} />
          {academy.length > 0 && (
            <Card title="Academy / development teams">
              {academy.map((a) => (
                <div key={a.id}>
                  <TeamLink t={a} /> — {a.roster.length} players
                </div>
              ))}
            </Card>
          )}
        </>
      )}
      {tab === 'schedule' && (
        <Card title="Fixtures">
          <table className="table">
            <tbody>
              {fixtures.map((f) => (
                <tr key={f.id} className="clickable" onClick={() => navigate(`/match/${f.id}`)}>
                  <td>{formatShort(f.day)}</td>
                  <td>
                    <CompLink c={world.comps[f.compId]} />
                  </td>
                  <td>{fixtureLabel(world, f)}</td>
                  <td>{f.status === 'done' ? (f.winner === team.id ? 'W ' : 'L ') + scoreLabel(f) : f.format.label}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!fixtures.length && <div className="muted">No fixtures this season.</div>}
        </Card>
      )}
      {tab === 'stats' && (
        <Card title={`${world.season} season statistics`}>
          <DataTable
            id={`teamstats-${t.id}`}
            rows={rosterPlayers(world, team).map((p) => ({ p, a: seasonAgg(p, world.season, t.id) }))}
            rowKey={(r) => r.p.id}
            initialSort={{ key: 'rating', desc: true }}
            columns={[
              { key: 'p', label: 'Player', value: (r) => r.p.handle, render: (r) => <PlayerLink p={r.p} /> },
              { key: 'gp', label: 'GP', align: 'right', value: (r) => r.a.gp },
              { key: 'wr', label: 'Win%', align: 'right', value: (r) => (r.a.gp ? r.a.w / r.a.gp : 0), render: (r) => (r.a.gp ? `${Math.round((r.a.w / r.a.gp) * 100)}%` : '—') },
              { key: 'rating', label: 'Rating', align: 'right', value: (r) => r.a.rating, render: (r) => r.a.rating.toFixed(2) },
              { key: 'kda', label: 'KDA', align: 'right', value: (r) => Number(kda(r.a.s)) },
              ...t.stats.map((s) => ({ key: `s-${s.key}`, label: s.label, align: 'right' as const, value: (r: { a: { s: Record<string, number>; gp: number } }) => (r.a.gp ? (r.a.s[s.key] ?? 0) / r.a.gp : 0), render: (r: { a: { s: Record<string, number>; gp: number } }) => (r.a.gp ? ((r.a.s[s.key] ?? 0) / r.a.gp).toFixed(1) : '—') })),
              { key: 'mvp', label: 'MVPs', align: 'right', value: (r) => r.a.mvps },
            ]}
          />
        </Card>
      )}
      {tab === 'staff' && (
        <Card title="Staff">
          <table className="table">
            <tbody>
              {staff.map((s) => (
                <tr key={s.id}>
                  <td>
                    <StaffLink s={s} />
                  </td>
                  <td>{STAFF_ROLE_LABEL[s.role]}</td>
                  <td className="muted">{s.formerPlayerId ? 'former pro' : ''}</td>
                  <td>
                    <Money v={s.contract?.salary ?? 0} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
      {tab === 'training' && <WeekTimetable world={world} team={team} />}
      {tab === 'history' && (
        <>
          <RivalriesCard world={world} team={team} />
          <div className="grid2">
          <Card title="Season records">
            <table className="table">
              <thead>
                <tr>
                  <th>Season</th>
                  <th>League</th>
                  <th>W-L</th>
                  <th>Rank</th>
                  <th>Best results</th>
                </tr>
              </thead>
              <tbody>
                {[...team.history].reverse().map((h) => (
                  <tr key={h.season}>
                    <td>{h.season}</td>
                    <td>
                      {(() => {
                        const c = Object.values(world.comps).find((x) => x.leagueId === h.leagueId && x.season === h.season && x.tier === 'league');
                        return c ? <Link to={`/comp/${c.id}`}>{h.leagueName}</Link> : h.leagueName;
                      })()}
                    </td>
                    <td>
                      {h.w}-{h.l}
                    </td>
                    <td>{h.rank ?? '—'}</td>
                    <td className="small">
                      {h.results
                        .filter((r) => r.place <= 4)
                        .map((r, i) => (
                          <span key={r.compId}>
                            {i > 0 && ', '}
                            {world.comps[r.compId] ? <Link to={`/comp/${r.compId}`}>{r.compName.replace(/ \d{4}/, '')}</Link> : r.compName.replace(/ \d{4}/, '')}: #{r.place}
                          </span>
                        ))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!team.history.length && <div className="muted">First season in progress.</div>}
          </Card>
          <Card title="Trophies">
            {team.trophies.map((tr, i) => (
              <div key={i}>
                {tr.season} · {compRef(world, tr.compId) ? <CompLink c={compRef(world, tr.compId)} /> : tr.compName}
              </div>
            ))}
            {!team.trophies.length && <div className="muted">No trophies yet.</div>}
          </Card>
        </div>
        </>
      )}
    </div>
  );
}

/** Optional national-team coaching job held alongside the club role. */
function NationalJobCard() {
  const world = useWorld();
  const { act, notify } = useGame.getState();
  const job = world.meta.nationalJob;
  const titles = world.meta.titlesEnabled;
  const [title, setTitle] = useState(titles[0] ?? '');
  const nations = title ? eligibleNations(world, title).slice(0, 40) : [];
  const [country, setCountry] = useState('');
  if (!job) {
    return (
      <Card title="National team job" right={<span className="muted small">Optional — coach a country in the biennial Nations Cup</span>}>
        <div className="row" style={{ alignItems: 'flex-end' }}>
          <label className="field">
            <span>Title</span>
            <select value={title} onChange={(e) => { setTitle(e.target.value); setCountry(''); }}>
              {titles.map((id) => (
                <option key={id} value={id}>{getTitle(id).name}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Country (strength of top players)</span>
            <select value={country} onChange={(e) => setCountry(e.target.value)}>
              <option value="">Choose…</option>
              {nations.map((n) => (
                <option key={n.cc} value={n.cc}>{COUNTRY_BY_CODE[n.cc]?.name} — {n.score.toFixed(1)} ({n.players} players)</option>
              ))}
            </select>
          </label>
          <button className="btn" disabled={!country} onClick={() => { act((w) => (w.meta.nationalJob = { titleId: title, country, squad: [] }), { save: true }); notify(`You are now ${COUNTRY_BY_CODE[country]?.name} national coach for ${getTitle(title).short}.`, 'ok'); }}>
            Accept job
          </button>
        </div>
      </Card>
    );
  }
  const t = getTitle(job.titleId);
  const squadSize = t.teamSize + Math.min(2, t.subs);
  const pool = Object.values(world.players).filter((p) => !p.retired && p.nat === job.country && p.titleId === job.titleId).sort((a, b) => b.ca - a.ca).slice(0, 25);
  const team = userNationalTeam(world);
  const cup = Object.values(world.comps).find((c) => c.titleId === job.titleId && c.tier === 'cup' && c.status !== 'done');
  const toggle = (id: string) =>
    act((w) => {
      const j = w.meta.nationalJob!;
      j.squad = j.squad.includes(id) ? j.squad.filter((x) => x !== id) : [...j.squad, id].slice(-squadSize);
    });
  return (
    <Card
      title={<span>National coach: <Flag cc={job.country} />{COUNTRY_BY_CODE[job.country]?.name} · <TitleTag id={job.titleId} /></span>}
      right={
        <>
          {team && team.roster.length > 0 && <button className="btn ghost sm" onClick={() => navigate(`/tactics?team=${team.id}`)}>Tactics</button>}
          <button className="btn ghost sm" onClick={() => confirm('Resign as national coach?') && act((w) => (w.meta.nationalJob = null), { save: true })}>Resign</button>
        </>
      }
    >
      <div className="muted small" style={{ marginBottom: 6 }}>
        {cup ? <>Next tournament: <CompLink c={cup} /> ({cup.status}).</> : 'The Nations Cup is held every odd season in December.'} Name up to {squadSize} players ({job.squad.length} selected); empty places are filled with the best available. Club contracts are unaffected; call-ups add fatigue.
      </div>
      <table className="table">
        <tbody>
          {pool.map((p) => (
            <tr key={p.id}>
              <td><input type="checkbox" checked={job.squad.includes(p.id)} onChange={() => toggle(p.id)} aria-label={`Select ${p.handle}`} /></td>
              <td><PlayerLink p={p} /></td>
              <td>{roleName(p.titleId, p.roles[0])}</td>
              <td><TeamLink t={p.teamId ? world.teams[p.teamId] : undefined} /></td>
              <td><Rating v={p.ca} /></td>
              <td className="muted small">age {age(world, p.birth)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

/** Rookie-draft picks owned by a team, and the restricted-free-agency tender policy for user teams. */
function TeamPicks({ team }: { team: Team }) {
  const world = useWorld();
  const { act } = useGame.getState();
  const league = team.leagueId ? world.leagues[team.leagueId] : null;
  const picks = picksOwnedBy(world, team.id);
  const mine = isUserTeamId(world, team.id);
  if (!picks.length && !(mine && league?.policy.restrictedFA)) return null;
  return (
    <Card title="Draft picks & restricted free agency">
      {picks.length > 0 ? (
        <table className="table">
          <tbody>
            {picks.map((d) => (
              <tr key={d.id}>
                <td>{pickLabel(world, d)}</td>
                <td className="muted">{d.originalTeamId === team.id ? 'own pick' : <>acquired from <TeamLink t={world.teams[d.originalTeamId]} /></>}</td>
                <td className="num">≈{formatMoney(pickValue(world, d))}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="muted small">No draft picks held.</div>
      )}
      {mine && league?.policy.restrictedFA && (
        <label style={{ display: 'block', marginTop: 6 }}>
          <input type="checkbox" checked={team.prefs.tenderRFA !== false} onChange={(e) => act((w) => (w.teams[team.id].prefs.tenderRFA = e.target.checked), { save: true })} /> Tender qualifying offers to expiring players aged 24 or under (one season at +10% salary)
        </label>
      )}
    </Card>
  );
}

/** Competition registration: the standing plan (user teams) and live registrations with changes. */
function RegistrationCard({ team }: { team: Team }) {
  const world = useWorld();
  const { act, notify } = useGame.getState();
  const mine = isUserTeamId(world, team.id);
  const comps = activeRegistrations(world, team.id);
  const max = maxRegistered(world, team);
  const t = getTitle(team.titleId);
  const planned = plannedRegistration(world, team);
  const [swapOut, setSwapOut] = useState<Record<string, string>>({});
  if (!mine && !comps.length) return null;
  const name = (id: string) => world.players[id]?.handle ?? '—';
  const togglePlan = (pid: string) =>
    act((w) => {
      const tm = w.teams[team.id];
      const cur = tm.prefs.registrationPlan?.length ? [...tm.prefs.registrationPlan] : plannedRegistration(w, tm);
      const next = cur.includes(pid) ? cur.filter((x) => x !== pid) : cur.length < max ? [...cur, pid] : cur;
      if (!cur.includes(pid) && cur.length >= max) notify(`At most ${max} players can be registered.`, 'err');
      tm.prefs.registrationPlan = next;
    });
  return (
    <Card title="Competition registration" right={<span className="muted small">up to {max} players · only registered players can play · {AMENDMENTS} changes per competition in transfer windows</span>}>
      {mine && (
        <div style={{ marginBottom: 10 }}>
          <div className="row" style={{ marginBottom: 4 }}>
            <b className="small">Registration plan (used when a competition locks, 3 days before it starts)</b>
            <span className="muted small">{team.prefs.registrationPlan?.length ? `your plan · ${team.prefs.registrationPlan.length}/${max}` : "coach's choice"}</span>
            {team.prefs.registrationPlan?.length ? (
              <button className="btn ghost sm" onClick={() => act((w) => (w.teams[team.id].prefs.registrationPlan = undefined))}>
                Let the coach choose
              </button>
            ) : null}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(170px, 1fr))', gap: 2 }}>
            {team.roster.map((pid) => (
              <label key={pid}>
                <input type="checkbox" checked={planned.includes(pid)} onChange={() => togglePlan(pid)} /> {name(pid)} <span className="muted small">{roleName(team.titleId, world.players[pid]?.roles[0] ?? '')}</span>
              </label>
            ))}
          </div>
          {planned.length < t.teamSize && <div className="neg small">The plan needs at least {t.teamSize} players; the coach will fill it otherwise.</div>}
        </div>
      )}
      {comps.map((c) => {
        const reg = c.registrations![team.id];
        const used = c.amendments?.[team.id]?.filter((a) => !a.emergency).length ?? 0;
        const unregistered = team.roster.filter((id) => !reg.includes(id));
        return (
          <div key={c.id} style={{ borderTop: '1px solid var(--border)', paddingTop: 6, marginTop: 6 }}>
            <div className="row">
              <CompLink c={c} />
              <span className="muted small">
                {reg.length}/{max} registered · {AMENDMENTS - used} change(s) left · window {inTransferWindow(world) ? 'open' : 'closed'}
              </span>
            </div>
            <div className="small">
              {reg.map((id) => {
                const p = world.players[id];
                const status = !p || p.teamId !== team.id ? ' (left the team)' : p.injury && p.injury.untilDay > world.day ? ' (injured)' : p.leave && p.leave.untilDay > world.day ? ' (on a break)' : '';
                return (
                  <span key={id} className={`chip ${status ? 'ban' : 'pick'}`}>
                    {name(id)}
                    {status}
                  </span>
                );
              })}
            </div>
            {mine && unregistered.length > 0 && (
              <div style={{ marginTop: 4 }}>
                {unregistered.map((pid) => (
                  <div key={pid} className="row small">
                    <span>
                      <b>{name(pid)}</b> is not registered
                    </span>
                    {reg.length >= max && (
                      <select aria-label={`Replace for ${name(pid)}`} value={swapOut[pid] ?? ''} onChange={(e) => setSwapOut((o) => ({ ...o, [pid]: e.target.value }))}>
                        <option value="">replace…</option>
                        {reg.map((id) => (
                          <option key={id} value={id}>
                            {name(id)}
                          </option>
                        ))}
                      </select>
                    )}
                    <button
                      className="btn ghost sm"
                      onClick={() => {
                        const r = act((w) => amendRegistration(w, c.id, team.id, pid, reg.length >= max ? swapOut[pid] || null : null), { save: true });
                        if (r) notify(r.message, r.ok ? 'ok' : 'err');
                      }}
                    >
                      Register
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </Card>
  );
}

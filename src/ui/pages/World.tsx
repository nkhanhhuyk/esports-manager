import { useWorld } from '../store';
import { Card, CompLink, Flag, OrgBadge, OrgLink, PageHeader, PlayerLink, Rating, Stat, TeamLink, TitleTag, Tabs } from '../components/common';
import { DataTable } from '../components/DataTable';
import { getTitle, TITLES, similarityLabel, titleSimilarity } from '../../data/titles/catalogue';
import { COUNTRIES, REGIONS, regionName } from '../../data/countries';
import { INPUT_LABEL } from '../../model/attributes';
import { heatColor, LineChart, PALETTE } from '../components/Charts';
import { navigate, withQuery, type Route } from '../router';
import { computeTeamStrength } from '../../sim/common/team';
import { currentPatch } from '../../systems/patches';
import { formatDate } from '../../core/date';
import { formatMoney } from '../../core/math';

export function WorldPage({ route }: { route: Route }) {
  const world = useWorld();
  const tab = route.query.tab ?? 'titles';
  const titles = TITLES.filter((t) => world.meta.titlesEnabled.includes(t.id));
  return (
    <div>
      <PageHeader title="World" sub="Titles, regional popularity, national scenes, rankings and organisations" />
      <Tabs
        tabs={[
          { id: 'titles', label: 'Titles' },
          { id: 'popularity', label: 'Popularity map' },
          { id: 'countries', label: 'Countries & scenes' },
          { id: 'rankings', label: 'Global rankings' },
          { id: 'orgs', label: 'Organisations' },
        ]}
        active={tab}
        onChange={(x) => navigate(withQuery(route, { tab: x }))}
      />
      {tab === 'titles' && (
        <Card>
          <DataTable
            id="titles"
            rows={titles}
            rowKey={(t) => t.id}
            onRowClick={(t) => navigate(`/title/${t.id}`)}
            initialSort={{ key: 'pop', desc: true }}
            columns={[
              { key: 'name', label: 'Title', value: (t) => t.name, render: (t) => <b>{t.name}</b> },
              { key: 'family', label: 'Family', value: (t) => t.family, render: (t) => <TitleTag id={t.id} link={false} /> },
              { key: 'engine', label: 'Engine', value: (t) => t.engine },
              { key: 'input', label: 'Competitive input', value: (t) => INPUT_LABEL[t.input] },
              { key: 'size', label: 'Team', align: 'right', value: (t) => t.teamSize },
              { key: 'pop', label: 'Global popularity', align: 'right', value: (t) => world.titles[t.id].globalPop, render: (t) => world.titles[t.id].globalPop.toFixed(0) },
              { key: 'prestige', label: 'Prestige', align: 'right', value: (t) => world.titles[t.id].prestige },
              { key: 'leagues', label: 'Leagues', align: 'right', value: (t) => Object.values(world.leagues).filter((l) => l.titleId === t.id).length },
              { key: 'patch', label: 'Patch', value: (t) => currentPatch(world, t.id).version },
              { key: 'formats', label: 'Std/Playoff/Final', value: (t) => `${t.formats.standard.label} / ${t.formats.playoff.label} / ${t.formats.final.label}` },
            ]}
          />
        </Card>
      )}
      {tab === 'popularity' && (
        <Card title="Title popularity by region (0-100, evolves every season)">
          <div className="dt-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Title</th>
                  {REGIONS.map((r) => (
                    <th key={r.id} title={r.name}>
                      {r.short}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {titles.map((t) => (
                  <tr key={t.id}>
                    <td>
                      <TitleTag id={t.id} />
                    </td>
                    {REGIONS.map((r) => {
                      const v = world.titles[t.id].popularity[r.id] ?? 0;
                      return (
                        <td key={r.id} style={{ background: heatColor(v), color: '#fff', textAlign: 'center' }} title={`${t.name} in ${r.name}: ${v}`}>
                          {v}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {world.history.length > 0 && (
            <LineChart series={titles.slice(0, 8).map((t, i) => ({ name: t.short, color: PALETTE[i % PALETTE.length], points: world.titles[t.id].popHistory.map((h) => ({ x: h.season, y: h.pop })) }))} />
          )}
        </Card>
      )}
      {tab === 'countries' && (
        <Card>
          <DataTable
            id="countries"
            rows={COUNTRIES}
            rowKey={(c) => c.code}
            initialSort={{ key: 'players', desc: true }}
            filter={(c, q) => c.name.toLowerCase().includes(q)}
            columns={[
              { key: 'name', label: 'Country', value: (c) => c.name, render: (c) => (<span><Flag cc={c.code} />{c.name}</span>) },
              { key: 'region', label: 'Region', value: (c) => regionName(c.region) },
              { key: 'moba', label: 'MOBA scene', align: 'right', value: (c) => world.countryScene[c.code]?.moba ?? 0, render: (c) => <Rating v={world.countryScene[c.code]?.moba ?? 0} /> },
              { key: 'shooter', label: 'Shooter scene', align: 'right', value: (c) => world.countryScene[c.code]?.shooter ?? 0, render: (c) => <Rating v={world.countryScene[c.code]?.shooter ?? 0} /> },
              { key: 'players', label: 'Active players', align: 'right', value: (c) => Object.values(world.players).filter((p) => !p.retired && p.nat === c.code).length },
            ]}
          />
        </Card>
      )}
      {tab === 'rankings' && (
        <div className="grid2">
          {titles.map((t) => (
            <Card key={t.id} title={<span><TitleTag id={t.id} /> top teams</span>}>
              <table className="table">
                <tbody>
                  {Object.values(world.teams)
                    .filter((x) => x.active && x.titleId === t.id)
                    .sort((a, b) => b.elo - a.elo)
                    .slice(0, 8)
                    .map((x, i) => (
                      <tr key={x.id}>
                        <td>{i + 1}</td>
                        <td>
                          <OrgBadge org={world.orgs[x.orgId]} size={12} /> <TeamLink t={x} />
                        </td>
                        <td className="muted small">{regionName(x.region)}</td>
                        <td>{x.elo}</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </Card>
          ))}
        </div>
      )}
      {tab === 'orgs' && (
        <Card>
          <DataTable
            id="orgs"
            rows={Object.values(world.orgs)}
            rowKey={(o) => o.id}
            initialSort={{ key: 'prestige', desc: true }}
            filter={(o, q) => o.name.toLowerCase().includes(q)}
            onRowClick={(o) => navigate(`/org/${o.id}`)}
            columns={[
              { key: 'name', label: 'Organisation', value: (o) => o.name, render: (o) => <OrgLink org={o} /> },
              { key: 'country', label: 'Country', value: (o) => o.country, render: (o) => <Flag cc={o.country} /> },
              { key: 'divs', label: 'Divisions', align: 'right', value: (o) => o.teams.length },
              { key: 'rep', label: 'Reputation', align: 'right', value: (o) => o.rep, render: (o) => o.rep.toFixed(0) },
              { key: 'prestige', label: 'Prestige', align: 'right', value: (o) => o.prestige, render: (o) => o.prestige.toFixed(0) },
              { key: 'trophies', label: 'Trophies', align: 'right', value: (o) => Object.values(world.teams).filter((t) => t.orgId === o.id).reduce((a, t) => a + t.trophies.length, 0) },
              { key: 'bal', label: 'Balance', align: 'right', value: (o) => o.balance, render: (o) => formatMoney(o.balance) },
              { key: 'active', label: 'Status', value: (o) => (o.active ? 'active' : 'defunct') },
            ]}
          />
        </Card>
      )}
    </div>
  );
}

export function TitlePage({ id, route }: { id: string; route: Route }) {
  const world = useWorld();
  const t = getTitle(id);
  const ts = world.titles[id];
  const tab = route.query.tab ?? 'rules';
  if (!ts) return <div>This title is not part of this universe.</div>;
  const patch = currentPatch(world, id);
  const leagues = Object.values(world.leagues).filter((l) => l.titleId === id);
  const comps = Object.values(world.comps).filter((c) => c.titleId === id);
  const topPlayers = Object.values(world.players).filter((p) => !p.retired && p.titleId === id).sort((a, b) => b.ca - a.ca).slice(0, 15);
  const similar = TITLES.filter((x) => x.id !== id).map((x) => ({ x, s: titleSimilarity(id, x.id) })).sort((a, b) => b.s - a.s).slice(0, 6);
  return (
    <div>
      <PageHeader title={t.name} sub={<span><TitleTag id={id} link={false} /> · {t.subgenre} · {t.engine} engine · {INPUT_LABEL[t.input]} · {t.teamSize}v{t.teamSize}{t.br ? ` · lobby ${t.br.lobby}` : ''} · min age {t.minAge}</span>} />
      <div className="stats">
        <Stat label="Global popularity" value={ts.globalPop.toFixed(0)} />
        <Stat label="Prestige" value={ts.prestige} />
        <Stat label="Patch" value={patch.version} sub={patch.era} />
        <Stat label="Leagues" value={leagues.length} />
        <Stat label="Active players" value={Object.values(world.players).filter((p) => !p.retired && p.titleId === id).length} />
      </div>
      <Tabs
        tabs={[
          { id: 'rules', label: 'Ruleset' },
          { id: 'meta', label: `Meta & ${t.charLabel}s` },
          { id: 'patches', label: 'Patch history' },
          { id: 'competition', label: 'Competition' },
          { id: 'players', label: 'Top players' },
        ]}
        active={tab}
        onChange={(x) => navigate(withQuery(route, { tab: x }))}
      />
      {tab === 'rules' && (
        <div className="grid2">
          <Card title="Positions & roles">
            <table className="table">
              <tbody>
                {t.roles.map((r) => (
                  <tr key={r.id}>
                    <td>
                      <b>{r.name}</b> {t.lineup.includes(r.id) ? '' : <span className="tag">flex</span>}
                    </td>
                    <td className="muted small">{r.fclass.join(', ')}</td>
                    <td className="small">{r.classes.join(', ') || (r.weapons ?? []).join(', ')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
          <Card title="Format & draft rules">
            <div className="attr-row"><span>Standard / Playoff / Final</span><b>{t.formats.standard.label} / {t.formats.playoff.label} / {t.formats.final.label}</b></div>
            <div className="attr-row"><span>Draft</span><b>{t.draft.mode}{t.draft.mode !== 'none' ? ` · ${t.draft.bansPerTeam} bans · ${t.draft.uniqueAcross ? 'unique' : 'mirrors allowed'} · fearless ${t.draft.fearless}` : ''}</b></div>
            <div className="attr-row"><span>Map selection</span><b>{t.mapVeto}</b></div>
            <div className="attr-row"><span>Playstyles</span><b>{t.styles.join(', ')}</b></div>
            {t.moba && (
              <>
                <div className="attr-row"><span>Economy</span><b>{t.moba.economy}</b></div>
                <div className="attr-row"><span>Lanes</span><b>{t.moba.lanes.map((l) => l.name).join(', ')}</b></div>
                <div className="attr-row"><span>Objectives</span><b>{t.moba.objectives.map((o) => `${o.name} (${o.kind}, ${o.spawn}′)`).join(', ')}</b></div>
                <div className="attr-row"><span>Structures</span><b>{t.moba.structures.tierNames.join(' → ')}{t.moba.structures.base ? ` → ${t.moba.structures.baseName}` : ''} → {t.moba.structures.coreName}</b></div>
                <div className="attr-row"><span>Typical length</span><b>{t.moba.avgLength} min</b></div>
              </>
            )}
            {t.tactical && (
              <>
                <div className="attr-row"><span>Rounds</span><b>first to {t.tactical.roundsToWin}, halftime after {t.tactical.half}</b></div>
                <div className="attr-row"><span>Overtime</span><b>{t.tactical.overtime.kind === 'mr' ? `MR blocks of ${t.tactical.overtime.rounds}` : `win by two${t.tactical.overtime.cap ? `, cap ${t.tactical.overtime.cap}` : ''}`}</b></div>
                <div className="attr-row"><span>Economy</span><b>{t.tactical.economy}</b></div>
                <div className="attr-row"><span>Objective</span><b>{t.tactical.objectiveName} · sides {t.tactical.sides.join('/')}</b></div>
              </>
            )}
            {t.br && (
              <>
                <div className="attr-row"><span>Lobby</span><b>{t.br.lobby} teams · {t.br.zones} zones · {t.br.perspective.toUpperCase()}</b></div>
                <div className="attr-row"><span>Scoring</span><b>placement {t.br.placementPts.slice(0, 8).join('/')}… + {t.br.killPts}/kill</b></div>
                <div className="attr-row"><span>Respawn</span><b>{t.br.respawn}{t.br.build ? ' · building' : ''}{t.br.vehicles ? ' · vehicles' : ''}</b></div>
                {t.br.matchPointFinal && <div className="attr-row"><span>Finals</span><b>match point at {t.br.matchPointFinal}</b></div>}
              </>
            )}
            {t.modes.length > 0 && <div className="attr-row"><span>Modes</span><b>{t.modes.map((m) => m.name).join(', ')}</b></div>}
            <div className="muted small" style={{ marginTop: 6 }}>{t.notes}</div>
          </Card>
          <Card title="Maps">
            {t.maps.map((m) => (
              <span key={m.id} className={`chip ${patch.mapPool.includes(m.id) ? 'pick' : ''}`}>
                {m.name}
                {m.modes ? ` (${m.modes.join('/')})` : ''}
              </span>
            ))}
          </Card>
          <Card title="Closest titles (career switching)">
            {similar.map(({ x, s }) => (
              <div key={x.id} className="attr-row">
                <TitleTag id={x.id} />
                <span>
                  {Math.round(s * 100)}% · {similarityLabel(s)}
                </span>
              </div>
            ))}
          </Card>
        </div>
      )}
      {tab === 'meta' && (
        <Card title={`Current patch ${patch.version} — favoured styles: ${Object.entries(patch.styles).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k} ×${v}`).join(', ')}`}>
          {t.characters.length === 0 ? (
            <div className="muted">{t.name} has no character select; loadouts and weapons ({t.weaponClasses.join(', ')}) are the equipment layer.</div>
          ) : (
            <DataTable
              id={`meta-${id}`}
              rows={t.characters}
              rowKey={(c) => c.id}
              initialSort={{ key: 'meta', desc: true }}
              columns={[
                { key: 'name', label: t.charLabel, value: (c) => c.name, render: (c) => <b>{c.name}</b> },
                { key: 'cls', label: 'Class', value: (c) => c.cls },
                { key: 'meta', label: 'Meta strength', align: 'right', value: (c) => patch.charMeta[c.id] ?? 0, render: (c) => <span className={(patch.charMeta[c.id] ?? 0) >= 0 ? 'pos' : 'neg'}>{(patch.charMeta[c.id] ?? 0).toFixed(1)}</span> },
                { key: 'diff', label: 'Difficulty', align: 'right', value: (c) => c.difficulty },
                ...t.styles.map((s) => ({ key: `st-${s}`, label: s, align: 'right' as const, value: (c: (typeof t.characters)[number]) => c.styles[s] ?? 0 })),
              ]}
            />
          )}
        </Card>
      )}
      {tab === 'patches' && (
        <Card>
          {[...ts.patches].reverse().map((p) => (
            <div key={p.id} style={{ padding: '4px 0', borderBottom: '1px solid var(--border)' }}>
              <b>{p.version}</b> {p.major && <span className="tag">major</span>} <span className="muted small">{formatDate(p.day)} · {p.era}</span>
              <div className="small">{p.notes.join(' · ')}</div>
            </div>
          ))}
        </Card>
      )}
      {tab === 'competition' && (
        <div className="grid2">
          <Card title="Leagues">
            {leagues.map((l) => (
              <div key={l.id} className="attr-row">
                <span>
                  {l.name} <span className="muted small">T{l.tier} · {regionName(l.region)} · {l.franchised ? 'franchised' : 'promotion/relegation'}</span>
                </span>
                <span>{l.teamIds.length} teams</span>
              </div>
            ))}
          </Card>
          <Card title="This season's competitions">
            {comps.map((c) => (
              <div key={c.id} className="attr-row">
                <CompLink c={c} />
                <span className="muted small">{c.status}{c.status === 'done' && c.placements[0] ? ` · ${world.teams[c.placements[0]]?.short}` : ''}</span>
              </div>
            ))}
          </Card>
        </div>
      )}
      {tab === 'players' && (
        <Card>
          <table className="table">
            <tbody>
              {topPlayers.map((p, i) => (
                <tr key={p.id}>
                  <td>{i + 1}</td>
                  <td>
                    <Flag cc={p.nat} />
                    <PlayerLink p={p} />
                  </td>
                  <td>{t.roles.find((r) => r.id === p.roles[0])?.short}</td>
                  <td>
                    <TeamLink t={p.teamId ? world.teams[p.teamId] : undefined} />
                  </td>
                  <td>
                    <Rating v={p.ca} digits={1} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="muted small">Team strength leaders: {Object.values(world.teams).filter((x) => x.titleId === id && x.active).sort((a, b) => computeTeamStrength(world, b) - computeTeamStrength(world, a)).slice(0, 3).map((x) => x.name).join(', ')}</div>
        </Card>
      )}
    </div>
  );
}

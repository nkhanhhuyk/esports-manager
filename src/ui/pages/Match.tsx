import { useState } from 'react';
import { useGame, useWorld } from '../store';
import { Card, CompLink, PageHeader, PlayerLink, TeamLink, TitleTag, OrgBadge, Tabs } from '../components/common';
import { getTitle } from '../../data/titles/catalogue';
import { formatDate } from '../../core/date';
import { navigate, type Route } from '../router';
import { isUserTeamId, roleName } from '../helpers';
import { computeTeamStrength } from '../../sim/common/team';
import type { Fixture, GameResult, MatchRecord } from '../../model/types';
import { userFixturesOn } from '../../game/advance';
import { fmtTime } from '../../sim/common/engine';
import { can, denyReason } from '../../systems/career';

function DraftPanel({ g, f }: { g: GameResult; f: Fixture }) {
  const world = useWorld();
  const t = getTitle(f.titleId);
  if (!g.draft) return null;
  const name = (id: string) => t.characters.find((c) => c.id === id)?.name ?? id;
  return (
    <div className="draft-board">
      {[0, 1].map((i) => (
        <div key={i}>
          <b>{world.teams[f.teams[i]]?.short}</b> <span className="muted small">comp score {g.draft!.compScore[i]?.toFixed(2)}</span>
          <div>
            {g.draft!.bans[i].map((b) => (
              <span key={b} className="chip ban">
                {name(b)}
              </span>
            ))}
          </div>
          <div>
            {Object.entries(g.draft!.assign[i]).map(([pid, cid]) => (
              <span key={pid} className="chip pick">
                {world.players[pid]?.handle}: {name(cid)}
              </span>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

function Scoreboard({ g, f }: { g: GameResult; f: Fixture }) {
  const world = useWorld();
  const t = getTitle(f.titleId);
  if (!g.players.length) return <div className="muted small">Aggregated simulation — box score not retained for unobserved matches (enable full detail in Settings).</div>;
  const order = [0, 1];
  return (
    <div className="dt-scroll">
      {order.map((ti) => (
        <table key={ti} className="table" style={{ marginBottom: 8 }}>
          <thead>
            <tr>
              <th>
                {world.teams[f.teams[ti]]?.name} {g.winner === ti ? '🏆' : ''}
              </th>
              <th>Role</th>
              {t.characters.length > 0 && <th>{t.charLabel}</th>}
              {t.stats.map((s) => (
                <th key={s.key}>{s.label}</th>
              ))}
              <th>Rating</th>
            </tr>
          </thead>
          <tbody>
            {g.players
              .filter((l) => l.teamIdx === ti)
              .map((l) => (
                <tr key={l.playerId}>
                  <td>
                    <PlayerLink p={world.players[l.playerId]} />
                  </td>
                  <td>{roleName(f.titleId, l.roleId)}</td>
                  {t.characters.length > 0 && <td>{t.characters.find((c) => c.id === l.charId)?.name ?? ''}</td>}
                  {t.stats.map((s) => (
                    <td key={s.key}>{(l.s[s.key] ?? 0).toLocaleString()}</td>
                  ))}
                  <td>
                    <b>{l.rating.toFixed(2)}</b>
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      ))}
    </div>
  );
}

export function GameView({ g, f }: { g: GameResult; f: Fixture }) {
  const world = useWorld();
  const t = getTitle(f.titleId);
  const map = t.maps.find((m) => m.id === g.mapId);
  const mode = t.modes.find((m) => m.id === g.modeId);
  return (
    <>
      <Card title={`${t.family === 'SHOOTER' ? 'Map' : 'Game'} ${g.idx + 1}${map && t.maps.length > 1 ? ` — ${map.name}` : ''}${mode ? ` (${mode.name})` : ''}`} right={<span className="muted small">{fmtTime(g.duration)}</span>}>
        <div style={{ marginBottom: 8 }}>
          <b>{g.summary}</b>
        </div>
        {g.draft && <DraftPanel g={g} f={f} />}
        {g.sides && g.sides.length > 0 && <div className="muted small" style={{ marginBottom: 6 }}>{g.sides.join(' · ')}</div>}
        <Scoreboard g={g} f={f} />
      </Card>
      {g.events && g.events.length > 0 && (
        <Card title="Commentary">
          <div className="feed">
            {g.events.map((e, i) => (
              <div key={i} className={`${e.big ? 'big' : ''} ${e.team === 0 ? 't0' : e.team === 1 ? 't1' : ''}`}>
                {e.text}
              </div>
            ))}
          </div>
        </Card>
      )}
    </>
  );
}

export function SeriesView({ f, rec }: { f: Fixture; rec: MatchRecord }) {
  const world = useWorld();
  const t = getTitle(f.titleId);
  const [gi, setGi] = useState(0);
  const g = rec.games[Math.min(gi, rec.games.length - 1)];
  return (
    <>
      {rec.vetoes && rec.vetoes.length > 0 && (
        <Card title="Map veto">
          {rec.vetoes.map((v, i) => (
            <span key={i} className={`chip ${v.action === 'ban' ? 'ban' : 'pick'}`}>
              {v.action === 'decider' ? 'Decider' : `${world.teams[f.teams[v.team]]?.short} ${v.action}`}: {t.maps.find((m) => m.id === v.mapId)?.name}
            </span>
          ))}
        </Card>
      )}
      <Tabs tabs={rec.games.map((x, i) => ({ id: String(i), label: `${t.family === 'SHOOTER' ? 'Map' : 'Game'} ${i + 1}${x.winner >= 0 ? ` · ${world.teams[f.teams[x.winner]]?.short}` : ''}` }))} active={String(Math.min(gi, rec.games.length - 1))} onChange={(x) => setGi(Number(x))} />
      {g && <GameView g={g} f={f} />}
    </>
  );
}

export function MatchPage({ id }: { id: string; route: Route }) {
  const world = useWorld();
  const { act } = useGame();
  const f = world.fixtures[id];
  if (!f) return <div>Match not found (older seasons are summarised in History).</div>;
  const comp = world.comps[f.compId];
  const rec = world.matches[f.id];
  const t = getTitle(f.titleId);
  const isMine = f.teams.some((x) => isUserTeamId(world, x));
  const two = true;
  return (
    <div>
      <PageHeader
        title={two ? `${world.teams[f.teams[0]]?.name} vs ${world.teams[f.teams[1]]?.name}` : `${comp?.name} — lobby`}
        sub={
          <span>
            <TitleTag id={f.titleId} /> · <CompLink c={comp} /> · {formatDate(f.day, true)} · {f.format.label} ({f.formatKey})
          </span>
        }
      />
      {two && (
        <Card>
          <div className="row" style={{ justifyContent: 'center', gap: 24 }}>
            {[0, 1].map((i) => (
              <div key={i} style={{ textAlign: 'center', minWidth: 160 }}>
                <OrgBadge org={world.orgs[world.teams[f.teams[i]]?.orgId]} size={36} />
                <div>
                  <TeamLink t={world.teams[f.teams[i]]} />
                </div>
                <div className="muted small">strength {computeTeamStrength(world, world.teams[f.teams[i]]).toFixed(1)} · elo {world.teams[f.teams[i]]?.elo}</div>
              </div>
            ))}
          </div>
          {f.status === 'done' && (
            <div className="scoreline">
              {f.score?.[0]} – {f.score?.[1]}
            </div>
          )}
          {f.mvp && (
            <div className="center">
              Series MVP: <PlayerLink p={world.players[f.mvp]} />
            </div>
          )}
        </Card>
      )}
      {f.status === 'scheduled' && (
        <Card title="Preview">
          <div className="row">
            {isMine && world.day + 1 === f.day && (
              <button className="btn" onClick={() => navigate(`/live/${f.id}`)}>
                Play live (veto, draft, game by game)
              </button>
            )}
            {isMine && (
              <button className="btn ghost" onClick={() => navigate(`/tactics?team=${f.teams.find((x) => isUserTeamId(world, x))}`)}>
                Prepare tactics
              </button>
            )}
            {!f.detail && (
              <button className="btn ghost" onClick={() => act((w) => (w.fixtures[f.id].detail = true))}>
                Follow in full detail
              </button>
            )}
            {f.detail && <span className="tag">Full detail: box score & commentary will be kept</span>}
          </div>
          {two && (
            <div className="grid2" style={{ marginTop: 10 }}>
              {[0, 1].map((i) => {
                const team = world.teams[f.teams[i]];
                return (
                  <div key={i}>
                    <b>{team?.name}</b>
                    {team?.lineup.map((s) => (
                      <div key={s.playerId} className="attr-row">
                        <span>{t.roles.find((r) => r.id === s.roleId)?.name}</span>
                        <PlayerLink p={world.players[s.playerId]} />
                      </div>
                    ))}
                  </div>
                );
              })}
            </div>
          )}
        </Card>
      )}
      {rec && <SeriesView f={f} rec={rec} />}
    </div>
  );
}

export function MatchDayPage() {
  const world = useWorld();
  const advance = useGame((s) => s.advance);
  const act = useGame((s) => s.act);
  const fixtures = userFixturesOn(world, world.day + 1);
  return (
    <div>
      <PageHeader title="Match day" sub={`${formatDate(world.day + 1, true)} — your teams play ${fixtures.length} match(es).`} />
      {fixtures.map((f) => (
        <Card key={f.id} title={<span><TitleTag id={f.titleId} /> {world.comps[f.compId]?.name}</span>}>
          <div className="row">
            <b>{`${world.teams[f.teams[0]]?.name} vs ${world.teams[f.teams[1]]?.name}`}</b>
            <span className="muted">{f.format.label}</span>
            <span className="grow" />
            <button className="btn" disabled={!can(world, 'tactics')} title={can(world, 'tactics') ? undefined : denyReason(world, 'tactics')} onClick={() => navigate(`/live/${f.id}`)}>
              Play live
            </button>
            <button className="btn ghost" onClick={() => navigate(`/tactics?team=${f.teams.find((x) => isUserTeamId(world, x))}`)}>
              Tactics
            </button>
          </div>
        </Card>
      ))}
      {!fixtures.length && <div className="muted">No matches for your teams tomorrow.</div>}
      <Card>
        <div className="row">
          <span>Matches you do not play live are simulated by your coaching staff using your lineup, preferences and draft priorities.</span>
          <button className="btn green" onClick={async () => { act((w) => (w.pendingUserMatch = fixtures[0]?.id ?? null)); await advance({ kind: 'days', n: 1 }); navigate('/'); }}>
            Simulate match day
          </button>
        </div>
      </Card>
    </div>
  );
}

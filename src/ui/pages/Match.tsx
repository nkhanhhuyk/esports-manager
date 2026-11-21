import { RichText } from '../components/RichText';
import { watchedFixturesOn } from '../../systems/follow';
import { RivalryBadge } from '../components/Fans';
import { useState } from 'react';
import { useGame, useWorld } from '../store';
import { Card, CharIcon, CompLink, PageHeader, Pic, PlayerLink, TeamLink, TitleTag, OrgBadge, Tabs } from '../components/common';
import { playerPic } from '../pics';
import { getTitle } from '../../data/titles/catalogue';
import { getAnyReplay, getReplay, loadReplay, type AnyReplay } from '../../sim/moba/replayCache';
import { ShooterViewer } from '../components/ShooterViewer';
import type { ShooterReplay } from '../../model/types';
import { useArchivedView } from './Competitions';
import { useEffect } from 'react';
import { boxLines } from '../../sim/boxscore';
import { MobaViewer } from '../components/MobaViewer';
import { selectionText } from '../../sim/series';
import { cachedMatchRecord, loadMatchRecord } from '../../game/boxPages';
import { sideOrder } from '../helpers';
import { formatDate } from '../../core/date';
import { navigate, type Route } from '../router';
import { isUserTeamId, kda, roleName } from '../helpers';
import { computeTeamStrength } from '../../sim/common/team';
import type { Fixture, GameResult, MatchRecord, World } from '../../model/types';
import { userFixturesOn } from '../../game/advance';
import { fmtTime } from '../../sim/common/engine';
import { can, denyReason } from '../../systems/career';

/** "Blue" / "Red" (Radiant / Dire in Dota 2) badge for a MOBA team in one game. */
function SideTag({ g, f, ti }: { g: GameResult; f: Fixture; ti: number }) {
  const t = getTitle(f.titleId);
  if (t.engine !== 'moba') return null;
  const blue = (g.blue ?? 0) === ti;
  const names = f.titleId === 'dota2' ? ['Radiant', 'Dire'] : ['Blue', 'Red'];
  return (
    <span className="tag" style={{ background: blue ? 'rgba(63,167,255,0.25)' : 'rgba(255,93,93,0.25)', color: blue ? '#3fa7ff' : '#ff5d5d' }}>
      {blue ? names[0] : names[1]}
    </span>
  );
}

function DraftPanel({ g, f }: { g: GameResult; f: Fixture }) {
  const world = useWorld();
  const t = getTitle(f.titleId);
  if (!g.draft) return null;
  const name = (id: string) => t.characters.find((c) => c.id === id)?.name ?? id;
  return (
    <div className="draft-board">
      {sideOrder(t.engine === 'moba' ? g : undefined, [0, 1]).map((i) => (
        <div key={i}>
          <SideTag g={g} f={f} ti={i} /> <b>{world.teams[f.teams[i]]?.short}</b> {g.draft!.firstPick === i && <span className="tag">1st pick</span>} <span className="muted small">comp score {g.draft!.compScore[i]?.toFixed(2)}</span>
          <div>
            {g.draft!.bans[i].map((b) => (
              <span key={b} className="chip ban">
                <CharIcon id={b} size={16} /> {name(b)}
              </span>
            ))}
          </div>
          <div>
            {(Object.keys(g.draft!.assign[i] ?? {}).length ? Object.entries(g.draft!.assign[i]) : boxLines(g).filter((l) => l.teamIdx === i && l.charId).map((l) => [l.playerId, l.charId!] as [string, string])).map(([pid, cid]) => (
              <span key={pid} className="chip pick">
                <CharIcon id={cid} size={16} /> <PlayerLink p={world.players[pid]} />: {name(cid)}
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
  const lines = boxLines(g);
  if (!lines.length) return <div className="muted small">No box score was recorded for this game (played before box scores were kept for every match).</div>;
  // MOBAs: Blue / Radiant side on top, Red / Dire below (game by game).
  const order = t.engine === 'moba' ? sideOrder(g, [0, 1]) : [0, 1];
  const hasKda = t.stats.some((s) => s.key === 'k') && t.stats.some((s) => s.key === 'd');
  return (
    <div className="dt-scroll">
      {order.map((ti) => (
        <table key={ti} className="table" style={{ marginBottom: 8 }}>
          <thead>
            <tr>
              <th>
                <SideTag g={g} f={f} ti={ti} /> <TeamLink t={world.teams[f.teams[ti]]} /> {g.winner === ti ? '🏆' : ''}
              </th>
              <th>Role</th>
              {t.characters.length > 0 && <th>{t.charLabel}</th>}
              {t.stats.map((s) => (
                <th key={s.key}>{s.label}</th>
              ))}
              {hasKda && <th title="(Kills + Assists) / Deaths">KDA</th>}
              <th>Rating</th>
            </tr>
          </thead>
          <tbody>
            {lines
              .filter((l) => l.teamIdx === ti)
              .map((l) => (
                <tr key={l.playerId}>
                  <td>
                    <Pic src={playerPic(world.players[l.playerId])} size={20} round /> <PlayerLink p={world.players[l.playerId]} />
                  </td>
                  <td>{roleName(f.titleId, l.roleId)}</td>
                  {t.characters.length > 0 && <td><CharIcon id={l.charId} size={20} /> {t.characters.find((c) => c.id === l.charId)?.name ?? ''}</td>}
                  {t.stats.map((s) => (
                    <td key={s.key}>{Math.round(l.s[s.key] ?? 0).toLocaleString()}</td>
                  ))}
                  {hasKda && <td>{kda(l.s)}</td>}
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
  const [stored, setStored] = useState<AnyReplay | undefined>(undefined);
  useEffect(() => {
    if (g.replay || g.sreplay || getReplay(f.id, g.idx)) return;
    let on = true;
    loadReplay(world, f.id, g.idx).then((r) => on && setStored(r));
    return () => {
      on = false;
    };
  }, [world, f.id, g.idx, g.replay]);
  const replay = g.replay ?? getReplay(f.id, g.idx) ?? (stored?.kind === 'moba' ? stored.r : undefined);
  const sreplay = g.sreplay ?? (stored?.kind === 'shooter' ? stored.r : getAnyReplay(f.id, g.idx)?.kind === 'shooter' ? (getAnyReplay(f.id, g.idx)!.r as ShooterReplay) : undefined);
  const [watch, setWatch] = useState(false);
  const map = t.maps.find((m) => m.id === g.mapId);
  const mode = t.modes.find((m) => m.id === g.modeId);
  return (
    <>
      <Card title={`${t.family === 'SHOOTER' ? 'Map' : 'Game'} ${g.idx + 1}${map && t.maps.length > 1 ? ` — ${map.name}` : ''}${mode ? ` (${mode.name})` : ''}`} right={<span className="muted small">{fmtTime(g.duration)}</span>}>
        <div style={{ marginBottom: 8 }}>
          <b>
            <RichText world={world} text={g.summary} />
          </b>
        </div>
        {g.draft && <DraftPanel g={g} f={f} />}
        {selectionText(world, f, g) && <div className="muted small" style={{ marginBottom: 6 }}>{selectionText(world, f, g)}</div>}
        <Scoreboard g={g} f={f} />
        {g.engine === 'fast' && <div className="muted small">Background fixture simulated with the fast engine — no battlefield replay. Open a match before it is played (Watch live / Follow in full detail) to get the 2D simulation.</div>}
        {g.engine === 'spatial' && !replay && !sreplay && <div className="muted small">Replay no longer stored on this device (the 40 most recent battlefield games per career are kept).</div>}
        {((replay && !g.replay) || (sreplay && !g.sreplay)) && (
          <button className="btn ghost sm" style={{ marginTop: 6 }} onClick={() => setWatch(!watch)}>
            {watch ? 'Hide replay' : 'Watch 2D replay'}
          </button>
        )}
      </Card>
      {watch && replay && (
        <Card title={`Game ${g.idx + 1} replay`}>
          <MobaViewer replay={replay} teamNames={sideOrder(g, [world.teams[f.teams[0]]?.name ?? 'Team 1', world.teams[f.teams[1]]?.name ?? 'Team 2'])} autoplay={false} />
        </Card>
      )}
      {watch && sreplay && !replay && (
        <Card title={`Map ${g.idx + 1} replay`}>
          <ShooterViewer replay={sreplay} teamNames={[world.teams[f.teams[0]]?.name ?? 'Team 1', world.teams[f.teams[1]]?.name ?? 'Team 2']} autoplay={false} />
        </Card>
      )}
      {g.events && g.events.length > 0 && (
        <Card title="Commentary">
          <div className="feed">
            {g.events.map((e, i) => (
              <div key={i} className={`${e.big ? 'big' : ''} ${e.team === 0 ? 't0' : e.team === 1 ? 't1' : ''}`}>
                <RichText world={world} text={e.text} />
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

export function MatchPage({ id, route }: { id: string; route: Route }) {
  const live = useWorld();
  const { act, advance } = useGame.getState();
  const season = live.fixtures[id] ? null : route.query.season ? Number(route.query.season) : null;
  const { world, loading } = useArchivedView(live, season, true);
  const f = world.fixtures[id];
  const paged = usePagedRecord(world, f);
  if (!f) return <div>{loading ? 'Loading the match from the season archive…' : 'Match not found. Open it from its competition page (seasons are browsed with the dropdown there).'}</div>;
  const watchable = f.status === 'scheduled' && !!world.teams[f.teams[0]] && !!world.teams[f.teams[1]];
  const daysAway = f.day - world.day;
  const comp = world.comps[f.compId];
  const rec = paged ?? world.matches[f.id];
  const t = getTitle(f.titleId);
  const isMine = f.teams.some((x) => isUserTeamId(world, x));
  const two = true;
  return (
    <div>
      <PageHeader
        title={
          <>
            <TeamLink t={world.teams[f.teams[0]]} /> vs <TeamLink t={world.teams[f.teams[1]]} />
          </>
        }
        sub={
          <span>
            <TitleTag id={f.titleId} /> · <CompLink c={comp} /> · {formatDate(f.day, true)} · {f.format.label} ({f.formatKey})
            {f.teams.length === 2 && (
              <>
                {' '}
                <RivalryBadge world={world} a={f.teams[0]} b={f.teams[1]} />
              </>
            )}
            {rec?.audience ? ` · ~${rec.audience.toLocaleString()} peak viewers` : ''}
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
            {!isMine && watchable && daysAway === 1 && (
              <button className="btn" onClick={() => navigate(`/live/${f.id}`)}>
                Watch live
              </button>
            )}
            {watchable && daysAway > 1 && (
              <button
                className="btn"
                title="Simulates the days in between (stopping earlier if something needs your decision), then opens the live view"
                onClick={async () => {
                  act((w) => {
                    const fx = w.fixtures[f.id];
                    fx.detail = true;
                    fx.spatial = true;
                  });
                  await advance({ kind: 'days', n: daysAway - 1 });
                  navigate(`/live/${f.id}`);
                }}
              >
                Advance to match day & {isMine ? 'play' : 'watch'} live
              </button>
            )}
            {isMine && (
              <button className="btn ghost" onClick={() => navigate(`/tactics?team=${f.teams.find((x) => isUserTeamId(world, x))}`)}>
                Prepare tactics
              </button>
            )}
            {!f.detail && (
              <button className="btn ghost" onClick={() => act((w) => {
                const fx = w.fixtures[f.id];
                fx.detail = true;
                // MOBA: also lock the spatial battlefield simulation (replay available after play).
                fx.spatial = true;
              })}>
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
                    <b>
                      <TeamLink t={team} />
                    </b>
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
      {!rec && f.status === 'done' && f.pg !== undefined && <div className="muted">Loading box scores…</div>}
    </div>
  );
}

export function MatchDayPage() {
  const world = useWorld();
  const advance = useGame((s) => s.advance);
  const act = useGame((s) => s.act);
  const spectating = world.meta.mode === 'spectator';
  const fixtures = spectating ? watchedFixturesOn(world, world.day + 1) : userFixturesOn(world, world.day + 1);
  return (
    <div>
      <PageHeader title="Match day" sub={`${formatDate(world.day + 1, true)} — ${spectating ? 'followed teams' : 'your teams'} play ${fixtures.length} match(es).`} />
      {fixtures.map((f) => (
        <Card key={f.id} title={<span><TitleTag id={f.titleId} /> {world.comps[f.compId]?.name}</span>}>
          <div className="row">
            <b>
              <TeamLink t={world.teams[f.teams[0]]} /> vs <TeamLink t={world.teams[f.teams[1]]} />
            </b>
            <span className="muted">{f.format.label}</span>
            <span className="grow" />
            {spectating ? (
              <button className="btn" onClick={() => navigate(`/live/${f.id}`)}>
                Watch live
              </button>
            ) : (
              <>
                <button className="btn" disabled={!can(world, 'tactics')} title={can(world, 'tactics') ? undefined : denyReason(world, 'tactics')} onClick={() => navigate(`/live/${f.id}`)}>
                  Play live
                </button>
                <button className="btn ghost" onClick={() => navigate(`/tactics?team=${f.teams.find((x) => isUserTeamId(world, x))}`)}>
                  Tactics
                </button>
              </>
            )}
          </div>
        </Card>
      ))}
      {!fixtures.length && <div className="muted">No matches for {spectating ? 'followed' : 'your'} teams tomorrow.</div>}
      <Card>
        <div className="row">
          <span>{spectating ? 'Matches you do not watch are simulated with the same engine — results are identical.' : 'Matches you do not play live are simulated by your coaching staff using your lineup, preferences and draft priorities.'}</span>
          <button className="btn green" onClick={async () => { act((w) => (w.pendingUserMatch = fixtures[0]?.id ?? null)); await advance({ kind: 'days', n: 1 }); navigate('/'); }}>
            Simulate match day
          </button>
        </div>
      </Card>
    </div>
  );
}

/** A background match whose box scores were paged out of the save: load its page on demand. */
function usePagedRecord(world: World, f: Fixture | undefined): MatchRecord | undefined {
  const comp = f ? world.comps[f.compId] : undefined;
  const season = comp?.season ?? world.season;
  const [rec, setRec] = useState<MatchRecord | undefined>(() => (f ? cachedMatchRecord(world, f.id, season, f.pg) : undefined));
  useEffect(() => {
    if (!f || f.pg === undefined || world.matches[f.id]) return;
    let on = true;
    void loadMatchRecord(world, f.id, season, f.pg).then((r) => on && setRec(r));
    return () => {
      on = false;
    };
  }, [world, f, season]);
  return f && !world.matches[f.id] ? rec : undefined;
}

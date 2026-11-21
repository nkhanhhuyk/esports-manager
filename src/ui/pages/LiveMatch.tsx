/**
 * Live match screen: pre-match lineup check & map veto, interactive draft for every game,
 * game-by-game simulation with commentary, then the series is recorded exactly like an
 * instant simulation.
 */
import { useMemo, useRef, useState } from 'react';
import { useGame, useWorld } from '../store';
import { Card, CharIcon, CompLink, PageHeader, PlayerLink, TeamLink, TitleTag } from '../components/common';
import { getTitle } from '../../data/titles/catalogue';
import { formatDate } from '../../core/date';
import { navigate } from '../router';
import { isUserTeamId, roleE } from '../helpers';
import { aiSelection, completeSelection, createSeries, draftStateFor, gameSides, playNextGame, sideChooser, splitSelection, type SeriesState, type SideSel } from '../../sim/series';
import { aiDraftAction, applyDraftAction, currentStep, legalOptions, toRecord, type DraftState } from '../../sim/common/draft';
import { RNG } from '../../core/rng';
import { recordLiveSeries, playFixture } from '../../sim/record';
import { currentPatch } from '../../systems/patches';
import { GameView, SeriesView } from './Match';
import { validateLineup } from '../../systems/lineup';
import { can, denyReason } from '../../systems/career';
import { MobaViewer } from '../components/MobaViewer';
import { sideOrder } from '../helpers';
import { ShooterViewer } from '../components/ShooterViewer';

export function LiveMatchPage({ id }: { id: string }) {
  const world = useWorld();
  const { act, notify, save, advance: advanceGame } = useGame.getState();
  const f = world.fixtures[id];
  const [st, setSt] = useState<SeriesState | null>(null);
  const [ds, setDs] = useState<DraftState | null>(null);
  const drngRef = useRef<RNG | null>(null);
  const [tick, setTick] = useState(0);
  const [bans, setBans] = useState<string[]>([]);
  const [picks, setPicks] = useState<string[]>([]);
  const [assignTo, setAssignTo] = useState('');
  /** LoL / Dota 2: waiting for your side or draft-order choice before the draft. */
  const [choosing, setChoosing] = useState(false);
  /** Games whose result has been revealed (playback finished or skipped). */
  const [revealed, setRevealed] = useState(0);
  const t = f ? getTitle(f.titleId) : null;
  // Your side (−1 when watching; without tactical control your own matches are watched too).
  const ui = f && can(world, 'tactics') ? f.teams.findIndex((x) => isUserTeamId(world, x)) : -1;
  const pool = useMemo(() => (t ? currentPatch(world, t.id).mapPool : []), [world, t]);
  if (!f || !t) return <div>Match not found.</div>;
  if (f.status === 'done') {
    return (
      <div>
        <PageHeader title="Match finished" right={<button className="btn" onClick={() => navigate(`/match/${f.id}`)}>View report</button>} />
      </div>
    );
  }
  // Not your match: watch it — both coaching staffs handle the veto and the drafts.
  // Without tactical control (e.g. a GM-only role) your own matches are watched: staff handle veto and draft.
  const watching = ui < 0 || !can(world, 'tactics');
  void denyReason;
  if (f.day !== world.day + 1) {
    if (f.day <= world.day) return <div>This match has already been played. <button className="btn ghost sm" onClick={() => navigate(`/match/${f.id}`)}>Open the match report</button></div>;
    return (
      <div>
        <p>
          This match is on {formatDate(f.day, true)} — {f.day - world.day - 1} day(s) to simulate first.
        </p>
        <button
          className="btn"
          onClick={async () => {
            act((w) => {
              const fx = w.fixtures[f.id];
              fx.detail = true;
              fx.spatial = true;
            });
            await advanceGame({ kind: 'days', n: f.day - world.day - 1 });
          }}
        >
          Advance to match day
        </button>
        <p className="muted small">The advance stops earlier if something needs your decision (your own match days, offers, the board).</p>
      </div>
    );
  }

  const userTeam = watching ? null : world.teams[f.teams[ui]];
  const lineupCheck = userTeam ? validateLineup(world, userTeam, userTeam.lineup) : { ok: true, errors: [] as string[] };

  const startDraftFor = (state: SeriesState) => {
    if (!t.characters.length || t.draft.mode === 'none') {
      setDs(null);
      return;
    }
    const d = draftStateFor(world, f, state);
    drngRef.current = RNG.derive(world.meta.seed, f.id, 'd', state.games.length);
    runAiUntilUser(d);
    setDs(d);
  };

  const runAiUntilUser = (d: DraftState) => {
    let guard = 0;
    while (!d.done && guard++ < 100) {
      const step = currentStep(d);
      if (!step || step.team === ui) break;
      const a = aiDraftAction(world, d, drngRef.current!);
      if (!a) {
        d.done = true;
        break;
      }
      applyDraftAction(d, a.charId, a.playerId);
    }
  };

  const start = () => {
    if (t.mapVeto === 'tactical' && !watching) act((w) => (w.overrides[f.id] = { ...(w.overrides[f.id] ?? {}), mapBans: bans, mapPicks: picks }));
    // Lock the fixture to full detail before anything is simulated: MOBA games then run the
    // spatial battlefield simulation whether watched, read or skipped.
    if (!f.spatial) act((w) => (w.fixtures[f.id].spatial = true));
    const s = createSeries(world, f, true);
    s.record = true;
    setSt(s);
    beginGame(s);
  };

  /** Before each game's draft: your side / draft-order choice when the title separates them. */
  const beginGame = (state: SeriesState) => {
    if (!watching && t.engine === 'moba' && splitSelection(t.id)) {
      setChoosing(true);
      setDs(null);
      return;
    }
    startDraftFor(state);
  };

  const choose = (sel: SideSel) => {
    if (!st) return;
    st.sel = st.sel ?? [];
    st.sel[st.games.length] = sel;
    setChoosing(false);
    startDraftFor(st);
  };

  const userAct = (charId: string) => {
    if (!ds) return;
    const step = currentStep(ds);
    const r = applyDraftAction(ds, charId, step?.action === 'pick' ? assignTo || undefined : undefined);
    if (!r.ok) {
      notify(r.error ?? 'Illegal', 'err');
      return;
    }
    setAssignTo('');
    runAiUntilUser(ds);
    setTick(tick + 1);
  };

  const autoDraft = () => {
    if (!ds) return;
    let guard = 0;
    while (!ds.done && guard++ < 100) {
      const a = aiDraftAction(world, ds, drngRef.current!);
      if (!a) break;
      applyDraftAction(ds, a.charId, a.playerId);
    }
    setTick(tick + 1);
  };

  const playGame = () => {
    if (!st) return;
    const draft = ds && ds.done ? toRecord(ds, [0, 0]) : undefined;
    playNextGame(world, f, st, draft);
    setSt({ ...st });
    setDs(null);
    // Games without a recorded view are revealed at once; others when playback ends.
    const last = st.games[st.games.length - 1];
    if (!last?.replay && !last?.sreplay) setRevealed(st.games.length);
  };

  const nextGame = () => st && beginGame(st);

  const finish = async () => {
    if (!st) return;
    act((w) => recordLiveSeries(w, w.fixtures[f.id], st));
    await save(true);
    navigate(`/match/${f.id}`);
  };

  const step = ds ? currentStep(ds) : null;
  const opts = ds && step && step.team === ui ? legalOptions(ds, step.team, step.action) : [];
  const meta = currentPatch(world, t.id).charMeta;
  const lastGame = st?.games[st.games.length - 1];
  const hidden = !!st && st.games.length > revealed;
  const shownWins = st ? st.games.slice(0, revealed).reduce((a, g) => ((a[g.winner] += 1), a), [0, 0]) : [0, 0];
  const awaitingDraft = !!ds && !ds.done;
  const needsDraft = t.characters.length > 0 && t.draft.mode !== 'none';
  const canPlay = !!st && !st.done && !awaitingDraft && (!needsDraft || (!!ds && ds.done));

  return (
    <div>
      <PageHeader
        title={
          <>
            <TeamLink t={world.teams[f.teams[0]]} /> vs <TeamLink t={world.teams[f.teams[1]]} />
          </>
        }
        sub={<span><TitleTag id={t.id} /> · <CompLink c={world.comps[f.compId]} /> · {f.format.label}{st ? ` · series ${shownWins[0]}-${shownWins[1]}` : ''}</span>}
      />
      {!st && (
        <>
          <Card title="Pre-match">
            {watching && (
              <div className="grid2">
                {[0, 1].map((i) => {
                  const tm = world.teams[f.teams[i]];
                  return (
                    <div key={i}>
                      <b>
                        <TeamLink t={tm} />
                      </b>
                      {tm.lineup.map((s) => (
                        <div key={s.playerId} className="attr-row">
                          <span>{t.roles.find((r) => r.id === s.roleId)?.name}</span>
                          <span>
                            <PlayerLink p={world.players[s.playerId]} /> E {roleE(world.players[s.playerId], t.id, s.roleId).toFixed(1)}
                          </span>
                        </div>
                      ))}
                    </div>
                  );
                })}
              </div>
            )}
            {userTeam && (
            <div className="grid2">
              <div>
                <b>Your lineup ({userTeam.name})</b>
                {userTeam.lineup.map((s) => (
                  <div key={s.playerId} className="attr-row">
                    <span>{t.roles.find((r) => r.id === s.roleId)?.name}</span>
                    <span>
                      <PlayerLink p={world.players[s.playerId]} /> E {roleE(world.players[s.playerId], t.id, s.roleId).toFixed(1)}
                    </span>
                  </div>
                ))}
                {!lineupCheck.ok && <div className="neg small">{lineupCheck.errors.join(' ')} The coach will fix the lineup automatically.</div>}
                <button className="btn ghost sm" onClick={() => navigate(`/tactics?team=${userTeam.id}`)}>
                  Edit lineup & preferences
                </button>
              </div>
              {t.mapVeto === 'tactical' && (
                <div>
                  <b>Map veto plan</b> <span className="muted small">(first choices are used on your veto turns)</span>
                  <div className="row" style={{ marginTop: 6 }}>
                    <label className="field">
                      <span>Ban priority</span>
                      <select multiple size={Math.min(9, pool.length)} value={bans} onChange={(e) => setBans([...e.target.selectedOptions].map((o) => o.value))}>
                        {pool.map((m) => (
                          <option key={m} value={m}>
                            {t.maps.find((x) => x.id === m)?.name}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label className="field">
                      <span>Pick priority</span>
                      <select multiple size={Math.min(9, pool.length)} value={picks} onChange={(e) => setPicks([...e.target.selectedOptions].map((o) => o.value))}>
                        {pool.map((m) => (
                          <option key={m} value={m}>
                            {t.maps.find((x) => x.id === m)?.name}
                          </option>
                        ))}
                      </select>
                    </label>
                  </div>
                </div>
              )}
            </div>
            )}
            <button className="btn" style={{ marginTop: 10 }} onClick={start}>
              {watching ? 'Start watching' : 'Start series'}
            </button>
          </Card>
        </>
      )}
      {st && st.vetoes.length > 0 && st.games.length === 0 && (
        <Card title="Veto result">
          {st.vetoes.map((v, i) => (
            <span key={i} className={`chip ${v.action === 'ban' ? 'ban' : 'pick'}`}>
              {v.action === 'decider' ? 'Decider' : `${world.teams[f.teams[v.team]]?.short} ${v.action}`}: {t.maps.find((m) => m.id === v.mapId)?.name}
            </span>
          ))}
        </Card>
      )}
      {st && choosing && !st.done && (() => {
        const { chooser, by } = sideChooser(world, f, st);
        const sideNames = t.id === 'dota2' ? ['Radiant', 'Dire'] : ['Blue', 'Red'];
        const how = by === 'coin' ? 'won the coin toss' : 'lost the previous game';
        if (chooser === ui) {
          const opt = (label: string, pick: { side?: 0 | 1; order?: 'first' | 'second' }) => (
            <button className="btn" data-testid={`sel-${label.split(' ')[0].toLowerCase()}`} onClick={() => choose(completeSelection(world, f, chooser, by, pick, st.games.length))}>
              {label}
            </button>
          );
          return (
            <Card title={`Game ${st.games.length + 1} — first selection`}>
              <div style={{ marginBottom: 6 }}>You {how}: choose a side <i>or</i> a draft order. {world.teams[f.teams[1 - ui]].short} then makes the other choice.</div>
              <div className="row">
                {opt(`${sideNames[0]} side`, { side: 0 })}
                {opt(`${sideNames[1]} side`, { side: 1 })}
                {opt('First pick', { order: 'first' })}
                {opt('Second pick', { order: 'second' })}
              </div>
              <button className="btn ghost sm" style={{ marginTop: 6 }} onClick={() => choose(gameSides(world, f, st))}>Let the coach decide</button>
            </Card>
          );
        }
        const ai = aiSelection(world, f, chooser, by, st.games.length);
        const opp = world.teams[f.teams[chooser]].short;
        const mine = (patch: Partial<SideSel>) => choose({ ...ai, ...patch });
        return (
          <Card title={`Game ${st.games.length + 1} — first selection`}>
            {ai.choice === 'side' ? (
              <>
                <div style={{ marginBottom: 6 }}>{opp} ({how}) chose <b>{ai.blue === chooser ? sideNames[0] : sideNames[1]} side</b>. Choose your draft order:</div>
                <div className="row">
                  <button className="btn" data-testid="sel-first" onClick={() => mine({ firstPick: ui as 0 | 1 })}>First pick</button>
                  <button className="btn" data-testid="sel-second" onClick={() => mine({ firstPick: chooser })}>Second pick</button>
                </div>
              </>
            ) : (
              <>
                <div style={{ marginBottom: 6 }}>{opp} ({how}) chose <b>{ai.firstPick === chooser ? 'first pick' : 'second pick'}</b>. Choose your side:</div>
                <div className="row">
                  <button className="btn" data-testid="sel-blue" onClick={() => mine({ blue: ui as 0 | 1 })}>{sideNames[0]} side</button>
                  <button className="btn" data-testid="sel-red" onClick={() => mine({ blue: chooser })}>{sideNames[1]} side</button>
                </div>
              </>
            )}
            <button className="btn ghost sm" style={{ marginTop: 6 }} onClick={() => choose(ai)}>Let the coach decide</button>
          </Card>
        );
      })()}
      {st && ds && (
        <Card title={`Draft — game ${st.games.length + 1}${ds.unavailable[0].size ? ` · Fearless: ${ds.unavailable[0].size} champions used` : ''}`} right={<button className="btn ghost sm" onClick={autoDraft}>Let coach finish draft</button>}>
          <div className="draft-board">
            {[0, 1].map((i) => (
              <div key={i}>
                {t.engine === 'moba' && (() => {
                  const sel = gameSides(world, f, st);
                  const names = t.id === 'dota2' ? ['Radiant', 'Dire'] : ['Blue', 'Red'];
                  return <span className="tag">{sel.blue === i ? names[0] : names[1]}{sel.firstPick === i ? ' · 1st pick' : ''}</span>;
                })()}{' '}
                <b>{world.teams[f.teams[i]].short}</b> {i === ui && <span className="tag">you</span>}
                <div>
                  {ds.bans[i].map((b) => (
                    <span key={b} className="chip ban">
                      <CharIcon id={b} size={16} /> {t.characters.find((c) => c.id === b)?.name}
                    </span>
                  ))}
                </div>
                <div>
                  {Object.entries(ds.assign[i]).map(([pid, cid]) => (
                    <span key={pid} className="chip pick">
                      <CharIcon id={cid} size={16} /> <PlayerLink p={world.players[pid]} />: {t.characters.find((c) => c.id === cid)?.name}
                    </span>
                  ))}
                </div>
              </div>
            ))}
          </div>
          {awaitingDraft && step && step.team === ui && (
            <div style={{ marginTop: 8 }}>
              <div className="row" style={{ marginBottom: 6 }}>
                <b>Your {step.action}</b>
                {step.action === 'pick' && (
                  <select value={assignTo} onChange={(e) => setAssignTo(e.target.value)} aria-label="Assign to player">
                    <option value="">Assign to best-fitting player</option>
                    {ds.sides[ui].lineup
                      .filter((s) => !ds.assign[ui][s.playerId])
                      .map((s) => (
                        <option key={s.playerId} value={s.playerId}>
                          {world.players[s.playerId]?.handle} ({t.roles.find((r) => r.id === s.roleId)?.short})
                        </option>
                      ))}
                  </select>
                )}
                <span className="muted small">Number = current patch meta strength; ★ = a player's comfort pick.</span>
              </div>
              <div className="char-grid">
                {[...opts]
                  .sort((a, b) => (meta[b.id] ?? 0) - (meta[a.id] ?? 0))
                  .map((c) => {
                    const comfort = ds.sides[ui].players.some((p) => (p.prof.chars[t.id]?.[c.id] ?? 0) >= 75);
                    return (
                      <button key={c.id} onClick={() => userAct(c.id)}>
                        <CharIcon id={c.id} size={32} name={c.name} /> <b>{c.name}</b> {comfort ? '★' : ''}
                        <div className="muted small">
                          {c.cls} · <span className={(meta[c.id] ?? 0) >= 0 ? 'pos' : 'neg'}>{(meta[c.id] ?? 0).toFixed(1)}</span>
                        </div>
                      </button>
                    );
                  })}
              </div>
            </div>
          )}
        </Card>
      )}
      {st && hidden && lastGame?.sreplay && (
        <Card title={`Map ${st.games.length} — live`}>
          <ShooterViewer key={`s-${f.id}-${st.games.length}`} replay={lastGame.sreplay} teamNames={[world.teams[f.teams[0]].name, world.teams[f.teams[1]].name]} onEnd={() => setRevealed(st.games.length)} />
        </Card>
      )}
      {st && hidden && lastGame?.replay && (
        <Card title={`Game ${st.games.length} — live`}>
          <MobaViewer
            key={`${f.id}-${st.games.length}`}
            replay={lastGame.replay}
            teamNames={sideOrder(lastGame, [world.teams[f.teams[0]].name, world.teams[f.teams[1]].name])}
            onEnd={() => setRevealed(st.games.length)}
          />
        </Card>
      )}
      {st && !st.done && !hidden && (
        <Card>
          <div className="row">
            {canPlay && !awaitingDraft && (
              <button className="btn" onClick={playGame}>
                Play game {st.games.length + 1}
              </button>
            )}
            {!ds && !choosing && needsDraft && (
              <button className="btn" onClick={nextGame}>
                Draft game {st.games.length + 1}
              </button>
            )}
          </div>
        </Card>
      )}
      {lastGame && !hidden && lastGame.replay && (
        <Card title={`Game ${st!.games.length} replay`}>
          <MobaViewer key={`r-${f.id}-${st!.games.length}`} replay={lastGame.replay} teamNames={sideOrder(lastGame, [world.teams[f.teams[0]].name, world.teams[f.teams[1]].name])} autoplay={false} />
        </Card>
      )}
      {lastGame && !hidden && lastGame.sreplay && (
        <Card title={`Map ${st!.games.length} replay`}>
          <ShooterViewer key={`sr-${f.id}-${st!.games.length}`} replay={lastGame.sreplay} teamNames={[world.teams[f.teams[0]].name, world.teams[f.teams[1]].name]} autoplay={false} />
        </Card>
      )}
      {lastGame && !hidden && <GameView g={lastGame} f={f} />}
      {st?.done && !hidden && (
        <Card>
          <div className="scoreline">
            <TeamLink t={world.teams[f.teams[st.winner]]} /> win {Math.max(...st.wins)}-{Math.min(...st.wins)}
          </div>
          <div className="center">
            <button className="btn" onClick={finish}>
              Finish & record result
            </button>
          </div>
        </Card>
      )}
      {st && st.games.length > 1 && <SeriesView f={f} rec={{ fixtureId: f.id, titleId: f.titleId, day: f.day, teams: f.teams, games: st.games.slice(0, -1), vetoes: [], detail: true }} />}
    </div>
  );
}

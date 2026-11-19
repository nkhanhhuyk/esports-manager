/**
 * Live match screen: pre-match lineup check & map veto, interactive draft for every game,
 * game-by-game simulation with commentary, then the series is recorded exactly like an
 * instant simulation.
 */
import { useMemo, useRef, useState } from 'react';
import { useGame, useWorld } from '../store';
import { Card, PageHeader, PlayerLink, TitleTag } from '../components/common';
import { getTitle } from '../../data/titles/catalogue';
import { navigate } from '../router';
import { isUserTeamId, roleE } from '../helpers';
import { createSeries, draftStateFor, playNextGame, type SeriesState } from '../../sim/series';
import { aiDraftAction, applyDraftAction, currentStep, legalOptions, toRecord, type DraftState } from '../../sim/common/draft';
import { RNG } from '../../core/rng';
import { recordLiveSeries, playFixture } from '../../sim/record';
import { currentPatch } from '../../systems/patches';
import { GameView, SeriesView } from './Match';
import { validateLineup } from '../../systems/lineup';

export function LiveMatchPage({ id }: { id: string }) {
  const world = useWorld();
  const { act, notify, save } = useGame();
  const f = world.fixtures[id];
  const [st, setSt] = useState<SeriesState | null>(null);
  const [ds, setDs] = useState<DraftState | null>(null);
  const drngRef = useRef<RNG | null>(null);
  const [tick, setTick] = useState(0);
  const [bans, setBans] = useState<string[]>([]);
  const [picks, setPicks] = useState<string[]>([]);
  const [assignTo, setAssignTo] = useState('');
  const t = f ? getTitle(f.titleId) : null;
  const ui = f ? f.teams.findIndex((x) => isUserTeamId(world, x)) : -1;
  const pool = useMemo(() => (t ? currentPatch(world, t.id).mapPool : []), [world, t]);
  if (!f || !t) return <div>Match not found.</div>;
  if (f.status === 'done') {
    return (
      <div>
        <PageHeader title="Match finished" right={<button className="btn" onClick={() => navigate(`/match/${f.id}`)}>View report</button>} />
      </div>
    );
  }
  if (ui < 0) return <div>This is not your match.</div>;
  if (f.day !== world.day + 1) return <div>Live matches can be played on match day only ({f.day > world.day ? 'advance until the day before' : 'already past'}).</div>;

  // Battle royale: watch the whole event live with full detail.
  if (f.format.kind === 'br') {
    return (
      <div>
        <PageHeader title={`${world.comps[f.compId]?.name} — live lobby`} sub={`${f.teams.length} teams · ${f.format.label}`} />
        <Card>
          <p>Battle royale events are decided across {f.format.n} games. Your lineup and playstyle ({world.teams[f.teams[ui]].prefs.style}) are used; drops, rotations and fights are simulated with full commentary.</p>
          <button
            className="btn"
            onClick={() => {
              act((w) => playFixture(w, w.fixtures[f.id], true), { save: true });
              navigate(`/match/${f.id}`);
            }}
          >
            Play event
          </button>
        </Card>
      </div>
    );
  }

  const userTeam = world.teams[f.teams[ui]];
  const lineupCheck = validateLineup(world, userTeam, userTeam.lineup);

  const startDraftFor = (state: SeriesState) => {
    const isSetContinuation = f.format.kind === 'sets' && state.setDraft && (state.setWins[0] > 0 || state.setWins[1] > 0);
    if (!t.characters.length || t.draft.mode === 'none' || isSetContinuation) {
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
    if (t.mapVeto === 'tactical') act((w) => (w.overrides[f.id] = { ...(w.overrides[f.id] ?? {}), mapBans: bans, mapPicks: picks }));
    const s = createSeries(world, f, true);
    setSt(s);
    startDraftFor(s);
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
  };

  const nextGame = () => st && startDraftFor(st);

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
  const awaitingDraft = !!ds && !ds.done;
  const setContinuation = !!st && f.format.kind === 'sets' && !!st.setDraft && (st.setWins[0] > 0 || st.setWins[1] > 0);
  const needsDraft = t.characters.length > 0 && t.draft.mode !== 'none' && !setContinuation;
  const canPlay = !!st && !st.done && !awaitingDraft && (!needsDraft || (!!ds && ds.done));

  return (
    <div>
      <PageHeader
        title={`${world.teams[f.teams[0]].name} vs ${world.teams[f.teams[1]].name}`}
        sub={<span><TitleTag id={t.id} /> · {world.comps[f.compId]?.name} · {f.format.label}{st ? ` · series ${st.wins[0]}-${st.wins[1]}${f.format.kind === 'sets' ? ` (set ${st.setWins[0]}-${st.setWins[1]})` : ''}` : ''}</span>}
      />
      {!st && (
        <>
          <Card title="Pre-match">
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
            <button className="btn" style={{ marginTop: 10 }} onClick={start}>
              Start series
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
      {st && ds && (
        <Card title={`Draft — game ${st.games.length + 1}`} right={<button className="btn ghost sm" onClick={autoDraft}>Let coach finish draft</button>}>
          <div className="draft-board">
            {[0, 1].map((i) => (
              <div key={i}>
                <b>{world.teams[f.teams[i]].short}</b> {i === ui && <span className="tag">you</span>}
                <div>
                  {ds.bans[i].map((b) => (
                    <span key={b} className="chip ban">
                      {t.characters.find((c) => c.id === b)?.name}
                    </span>
                  ))}
                </div>
                <div>
                  {Object.entries(ds.assign[i]).map(([pid, cid]) => (
                    <span key={pid} className="chip pick">
                      {world.players[pid]?.handle}: {t.characters.find((c) => c.id === cid)?.name}
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
                        <b>{c.name}</b> {comfort ? '★' : ''}
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
      {st && !st.done && (
        <Card>
          <div className="row">
            {canPlay && !awaitingDraft && (
              <button className="btn" onClick={playGame}>
                Play game {st.games.length + 1}
              </button>
            )}
            {!ds && needsDraft && (
              <button className="btn" onClick={nextGame}>
                Draft game {st.games.length + 1}
              </button>
            )}
          </div>
        </Card>
      )}
      {lastGame && <GameView g={lastGame} f={f} />}
      {st?.done && (
        <Card>
          <div className="scoreline">
            {world.teams[f.teams[st.winner]].name} win {Math.max(...st.wins)}-{Math.min(...st.wins)}
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

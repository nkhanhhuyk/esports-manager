import { useGame, useWorld } from '../store';
import { Card, PageHeader, Rating, TitleTag } from '../components/common';
import { navigate, withQuery, type Route } from '../router';
import { roleE, rosterPlayers, userTeams } from '../helpers';
import { getTitle } from '../../data/titles/catalogue';
import { autoLineup, validateLineup } from '../../systems/lineup';
import { currentPatch } from '../../systems/patches';
import type { LineupSlot, TeamPrefs } from '../../model/types';
import { computeTeamStrength } from '../../sim/common/team';

export function TacticsPage({ route }: { route: Route }) {
  const world = useWorld();
  const { act, notify } = useGame();
  const mine = userTeams(world);
  const team = world.teams[route.query.team ?? mine[0]?.id];
  if (!team) return <div>No team.</div>;
  const t = getTitle(team.titleId);
  const players = rosterPlayers(world, team);
  const check = validateLineup(world, team, team.lineup);
  const patch = currentPatch(world, t.id);
  const setPref = <K extends keyof TeamPrefs>(k: K, v: TeamPrefs[K]) => act((w) => (w.teams[team.id].prefs[k] = v));
  const setSlot = (roleId: string, playerId: string) =>
    act((w) => {
      const tm = w.teams[team.id];
      let lineup: LineupSlot[] = tm.lineup.filter((s) => s.roleId !== roleId && s.playerId !== playerId);
      lineup.push({ roleId, playerId });
      lineup = t.lineup.map((r) => lineup.find((s) => s.roleId === r)).filter(Boolean) as LineupSlot[];
      tm.lineup = lineup;
      tm.prefs.delegateLineup = false;
    });
  const metaSorted = [...t.characters].sort((a, b) => (patch.charMeta[b.id] ?? 0) - (patch.charMeta[a.id] ?? 0));
  return (
    <div>
      <PageHeader
        title={`Tactics & Draft — ${team.name}`}
        sub={<span><TitleTag id={t.id} /> · strength {computeTeamStrength(world, team).toFixed(1)} · patch {patch.version} ({patch.era})</span>}
        right={
          <select value={team.id} onChange={(e) => navigate(withQuery(route, { team: e.target.value }))} aria-label="Team">
            {mine.map((x) => (
              <option key={x.id} value={x.id}>
                {x.name} ({getTitle(x.titleId).short})
              </option>
            ))}
          </select>
        }
      />
      <div className="grid2">
        <Card
          title="Starting lineup"
          right={
            <>
              <label className="small">
                <input type="checkbox" checked={team.prefs.delegateLineup} onChange={(e) => setPref('delegateLineup', e.target.checked)} /> Delegate to coach
              </label>
              <button className="btn ghost sm" onClick={() => act((w) => (w.teams[team.id].lineup = autoLineup(w, w.teams[team.id])))}>
                Auto-pick
              </button>
            </>
          }
        >
          <table className="table">
            <thead>
              <tr>
                <th>Position</th>
                <th>Player</th>
                <th>E</th>
              </tr>
            </thead>
            <tbody>
              {t.lineup.map((roleId) => {
                const slot = team.lineup.find((s) => s.roleId === roleId);
                const role = t.roles.find((r) => r.id === roleId)!;
                return (
                  <tr key={roleId}>
                    <td title={role.fclass.join(', ')}>
                      <b>{role.name}</b>
                    </td>
                    <td>
                      <select value={slot?.playerId ?? ''} onChange={(e) => setSlot(roleId, e.target.value)} aria-label={`${role.name} player`}>
                        <option value="">—</option>
                        {[...players]
                          .sort((a, b) => roleE(b, t.id, roleId) - roleE(a, t.id, roleId))
                          .map((p) => (
                            <option key={p.id} value={p.id}>
                              {p.handle} — E {roleE(p, t.id, roleId).toFixed(1)}
                              {p.injury ? ' (inj)' : ''}
                            </option>
                          ))}
                      </select>
                    </td>
                    <td>{slot ? <Rating v={roleE(world.players[slot.playerId], t.id, roleId)} digits={1} /> : '—'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {!check.ok && (
            <div className="neg small" style={{ marginTop: 6 }}>
              {check.errors.join(' ')}
            </div>
          )}
          {check.ok && <div className="pos small">Lineup is legal for {team.leagueId ? world.leagues[team.leagueId].name : t.short} rules.</div>}
        </Card>
        <Card title="Team preferences (pre-match)">
          <label className="field">
            <span>Playstyle</span>
            <select value={team.prefs.style} onChange={(e) => setPref('style', e.target.value)}>
              {t.styles.map((s) => (
                <option key={s} value={s}>
                  {s} {patch.styles[s] ? `(meta ×${patch.styles[s]})` : ''}
                </option>
              ))}
            </select>
          </label>
          <label className="field" style={{ marginTop: 8 }}>
            <span>Aggression: {team.prefs.aggression}</span>
            <input type="range" min={0} max={100} value={team.prefs.aggression} onChange={(e) => setPref('aggression', Number(e.target.value))} />
          </label>
          {t.characters.length > 0 && (
            <>
              <label className="field" style={{ marginTop: 8 }}>
                <span>Draft priority</span>
                <select value={team.prefs.draftPriority} onChange={(e) => setPref('draftPriority', e.target.value as TeamPrefs['draftPriority'])}>
                  <option value="balanced">Balanced</option>
                  <option value="comfort">Player comfort picks</option>
                  <option value="meta">Meta strength</option>
                  <option value="counter">Counter-picking</option>
                </select>
              </label>
              <label style={{ display: 'block', marginTop: 8 }}>
                <input type="checkbox" checked={team.prefs.delegateDraft} onChange={(e) => setPref('delegateDraft', e.target.checked)} /> Delegate drafts to the head coach when not playing live
              </label>
            </>
          )}
          <div className="muted small" style={{ marginTop: 8 }}>
            Draft rules: {t.draft.mode}
            {t.draft.mode !== 'none' ? `, ${t.draft.bansPerTeam} bans/team, ${t.draft.uniqueAcross ? 'no mirror picks' : 'mirror picks allowed'}, fearless: ${t.draft.fearless}` : ''}. Map selection: {t.mapVeto}.
          </div>
        </Card>
      </div>
      {t.characters.length > 0 && (
        <Card title={`${t.charLabel} pools & ban priorities (current meta order)`}>
          <div className="grid2">
            {t.lineup.map((roleId) => {
              const role = t.roles.find((r) => r.id === roleId)!;
              const pool = team.prefs.pool[roleId] ?? [];
              const cands = metaSorted.filter((c) => !role.classes.length || role.classes.includes(c.cls)).slice(0, 14);
              return (
                <div key={roleId}>
                  <b>{role.name}</b>
                  <div>
                    {cands.map((c) => (
                      <button key={c.id} className={`chip ${pool.includes(c.id) ? 'pick' : ''}`} onClick={() => act((w) => { const pr = w.teams[team.id].prefs; const cur = pr.pool[roleId] ?? []; pr.pool[roleId] = cur.includes(c.id) ? cur.filter((x) => x !== c.id) : [...cur, c.id]; })}>
                        {c.name} <span className={(patch.charMeta[c.id] ?? 0) >= 0 ? 'pos' : 'neg'}>{(patch.charMeta[c.id] ?? 0).toFixed(0)}</span>
                      </button>
                    ))}
                  </div>
                </div>
              );
            })}
            <div>
              <b>Ban priorities</b>
              <div>
                {metaSorted.slice(0, 20).map((c) => (
                  <button key={c.id} className={`chip ${team.prefs.bans.includes(c.id) ? 'ban' : ''}`} onClick={() => act((w) => { const pr = w.teams[team.id].prefs; pr.bans = pr.bans.includes(c.id) ? pr.bans.filter((x) => x !== c.id) : [...pr.bans, c.id]; })}>
                    {c.name}
                  </button>
                ))}
              </div>
            </div>
          </div>
        </Card>
      )}
      {t.maps.length > 1 && (
        <Card title="Map preferences (used in vetoes and loser picks)">
          <table className="table">
            <tbody>
              {t.maps.map((m) => (
                <tr key={m.id}>
                  <td>
                    {m.name} {patch.mapPool.includes(m.id) ? '' : <span className="tag">out of pool</span>}
                  </td>
                  <td className="muted small">{m.modes?.join(', ')}</td>
                  <td>
                    <input type="range" min={-2} max={2} value={team.prefs.mapPrefs[m.id] ?? 0} onChange={(e) => act((w) => (w.teams[team.id].prefs.mapPrefs[m.id] = Number(e.target.value)))} aria-label={`${m.name} preference`} />
                  </td>
                  <td>{team.prefs.mapPrefs[m.id] ?? 0}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}
      <button className="btn ghost" onClick={() => notify('Tactics saved with your next save/advance.', 'ok')}>
        Done
      </button>
    </div>
  );
}

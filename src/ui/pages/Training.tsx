import { useGame, useWorld } from '../store';
import { Card, Gate, PageHeader, PlayerLink, Rating, TitleTag } from '../components/common';
import { navigate, withQuery, type Route } from '../router';
import { userTeams, rosterPlayers, roleName } from '../helpers';
import { FOCUS_LABEL, trainingQuality } from '../../systems/training';
import type { TrainingFocus } from '../../model/types';
import { getTitle } from '../../data/titles/catalogue';
import { inputIndex, INPUT_LABEL } from '../../model/attributes';

const FOCI = Object.keys(FOCUS_LABEL) as TrainingFocus[];

export function TrainingPage({ route }: { route: Route }) {
  const world = useWorld();
  const { act } = useGame();
  const mine = userTeams(world);
  const team = world.teams[route.query.team ?? mine[0]?.id];
  if (!team) return <div>No team.</div>;
  const t = getTitle(team.titleId);
  const tp = team.training;
  const q = trainingQuality(world, team);
  return (
    <div>
      <PageHeader
        title={`Training — ${team.name}`}
        sub={<span><TitleTag id={team.titleId} /> · training quality ×{q.toFixed(2)} (coaching + facilities) · {tp.focusWeeks ?? 0} week(s) on current focus{(tp.focusWeeks ?? 0) > 8 ? ' — diminishing returns, vary the programme!' : ''}</span>}
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
      <Gate world={world} perm="tactics">
      <div className="grid2">
        <Card title="Team programme">
          <label className="field">
            <span>Team focus</span>
            <select value={tp.focus} onChange={(e) => act((w) => (w.teams[team.id].training.focus = e.target.value as TrainingFocus))}>
              {FOCI.map((f) => (
                <option key={f} value={f}>
                  {FOCUS_LABEL[f]}
                </option>
              ))}
            </select>
          </label>
          <label className="field" style={{ marginTop: 8 }}>
            <span>Intensity: {tp.intensity} (higher = faster development but more fatigue & health risk)</span>
            <input type="range" min={0} max={100} value={tp.intensity} onChange={(e) => act((w) => (w.teams[team.id].training.intensity = Number(e.target.value)))} />
          </label>
          <label className="field" style={{ marginTop: 8 }}>
            <span>Scrims per week: {tp.scrimsPerWeek}</span>
            <input type="range" min={0} max={7} value={tp.scrimsPerWeek} onChange={(e) => act((w) => (w.teams[team.id].training.scrimsPerWeek = Number(e.target.value)))} />
          </label>
          <label style={{ display: 'block', marginTop: 8 }}>
            <input type="checkbox" checked={tp.opponentPrep} onChange={(e) => act((w) => (w.teams[team.id].training.opponentPrep = e.target.checked))} /> Analyst opponent preparation before matches
          </label>
          <label style={{ display: 'block', marginTop: 4 }}>
            <input type="checkbox" checked={team.prefs.delegateTraining} onChange={(e) => act((w) => (w.teams[team.id].prefs.delegateTraining = e.target.checked))} /> Delegate contract reminders & routine training to staff
          </label>
        </Card>
        <Card title="Team condition">
          <div className="attr-row"><span>Chemistry</span><Rating v={team.chemistry} /></div>
          <div className="attr-row"><span>Meta adaptation (current patch)</span><Rating v={team.metaAdapt} /></div>
          <div className="attr-row"><span>Scrim / practice quality</span><Rating v={team.scrimRating} /></div>
          <div className="muted small" style={{ marginTop: 6 }}>Permanent attributes develop slowly; proficiencies (roles, {t.charLabel.toLowerCase()}s, maps, weapons, {INPUT_LABEL[t.input]}) respond faster. Form changes quickly with results.</div>
        </Card>
      </div>
      <Card title="Individual plans">
        <table className="table">
          <thead>
            <tr>
              <th>Player</th>
              <th>Role</th>
              <th>CA</th>
              <th>Fatigue</th>
              <th>Morale</th>
              <th>{INPUT_LABEL[t.input]}</th>
              <th>Individual focus</th>
            </tr>
          </thead>
          <tbody>
            {rosterPlayers(world, team).map((p) => (
              <tr key={p.id}>
                <td>
                  <PlayerLink p={p} />
                </td>
                <td>{roleName(t.id, p.roles[0])}</td>
                <td>
                  <Rating v={p.ca} />
                </td>
                <td className={p.fatigue > 60 ? 'neg' : ''}>{p.fatigue.toFixed(0)}</td>
                <td>{p.morale.toFixed(0)}</td>
                <td>{p.input[inputIndex(t.input)].toFixed(0)}</td>
                <td>
                  <select value={tp.individual[p.id] ?? ''} onChange={(e) => act((w) => { const ind = w.teams[team.id].training.individual; if (e.target.value) ind[p.id] = e.target.value as TrainingFocus; else delete ind[p.id]; })}>
                    <option value="">(team focus)</option>
                    {FOCI.map((f) => (
                      <option key={f} value={f}>
                        {FOCUS_LABEL[f]}
                      </option>
                    ))}
                  </select>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
      </Gate>
    </div>
  );
}

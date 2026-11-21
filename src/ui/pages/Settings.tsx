import { difficulty, DIFFICULTY_LABEL, PRESETS, type DifficultyPreset } from '../../systems/difficulty';
import { useRef, useState } from 'react';
import { onlinePictures, setOnlinePictures } from '../pics';
import { navigate } from '../router';
import { saveFile } from '../fileExport';
import { autosaveEvery, setAutosaveEvery, useGame, useWorld, type AutosaveEvery } from '../store';
import { Card, PageHeader } from '../components/common';
import { exportSave, importSave } from '../../persistence/db';
import { getTitle } from '../../data/titles/catalogue';
import { SAVE_VERSION } from '../../persistence/version';
import { AREA_PERM, can, DELEGATION_INFO, PERMISSION_LABEL, ROLE_LABEL, userHandles } from '../../systems/career';
import { becomeSpectator } from '../../systems/follow';
import { userTeams } from '../helpers';
import { OrgLink, TeamLink, TitleTag } from '../components/common';
import type { DelegationArea, World } from '../../model/types';

const AREAS = Object.keys(DELEGATION_INFO) as DelegationArea[];

function DifficultyCard({ world }: { world: World }) {
  const { act } = useGame.getState();
  const d = difficulty(world);
  const keys = Object.keys(DIFFICULTY_LABEL) as Array<keyof typeof DIFFICULTY_LABEL>;
  return (
    <Card title="Difficulty & sandbox" right={<span className="muted small">current: {d.preset}</span>}>
      <div className="row" style={{ marginBottom: 8 }}>
        {(['relaxed', 'standard', 'hard'] as DifficultyPreset[]).map((p) => (
          <button key={p} className={`btn sm ${d.preset === p ? '' : 'ghost'}`} onClick={() => act((w) => (w.meta.difficulty = { ...PRESETS[p] }))}>
            {p[0].toUpperCase() + p.slice(1)}
          </button>
        ))}
      </div>
      {keys.map((k) => {
        const info = DIFFICULTY_LABEL[k];
        return (
          <label key={k} className="field" style={{ marginTop: 6 }}>
            <span>
              {info.label}: ×{d[k].toFixed(2)} <span className="muted small">— {info.desc}</span>
            </span>
            <input
              type="range"
              aria-label={info.label}
              min={info.min}
              max={info.max}
              step={info.step}
              value={d[k]}
              onChange={(e) => act((w) => (w.meta.difficulty = { ...difficulty(w), [k]: Number(e.target.value), preset: 'custom' }))}
            />
          </label>
        );
      })}
    </Card>
  );
}

function GameModeCard({ world }: { world: World }) {
  const { act } = useGame.getState();
  const spectating = world.meta.mode === 'spectator';
  return (
    <Card title="Game mode">
      {spectating ? (
        <>
          <div>
            <b>Spectator</b> — following {world.meta.followed?.length ?? 0} team(s). Every club&apos;s staff make all decisions (lineups, drafts, training & scrims, signings, contracts, staff).
          </div>
          <label style={{ display: 'block', marginTop: 6 }}>
            <input type="checkbox" checked={!!world.meta.stopForFollowed} onChange={(e) => act((w) => (w.meta.stopForFollowed = e.target.checked))} /> Stop “Continue” the day before a followed team plays (watch live)
          </label>
          <div className="row" style={{ marginTop: 8 }}>
            <button className="btn ghost" onClick={() => navigate('/following')}>
              Choose followed teams…
            </button>
            <button className="btn" onClick={() => navigate('/take-charge')}>
              Take charge of an organisation…
            </button>
          </div>
        </>
      ) : (
        <>
          <div>
            <b>Manager</b> — {ROLE_LABEL[world.meta.careerRole]} of {world.meta.userOrgId ? <OrgLink org={world.orgs[world.meta.userOrgId]} /> : '—'}. You can also follow other teams (Following) and delegate areas to your staff below.
          </div>
          <div className="row" style={{ marginTop: 8 }}>
            <button className="btn ghost" onClick={() => navigate('/following')}>
              Follow other teams…
            </button>
            <button
              className="btn ghost"
              onClick={() => {
                if (!confirm('Become a spectator? You leave your job; the organisation’s own staff take over every decision. You can take charge of any organisation later.')) return;
                act((w) => becomeSpectator(w), { save: true });
                navigate('/');
              }}
            >
              Become a spectator…
            </button>
          </div>
        </>
      )}
    </Card>
  );
}

function DelegationCard({ world }: { world: World }) {
  const { act } = useGame.getState();
  const teams = userTeams(world);
  if (world.meta.mode === 'spectator' || !world.meta.userOrgId) return null;
  const setAll = (on: boolean) =>
    act((w) => {
      w.meta.delegation = Object.fromEntries(AREAS.map((a) => [a, on]));
      for (const t of userTeams(w)) {
        t.prefs.delegateLineup = on;
        t.prefs.delegateDraft = on;
        t.prefs.delegateTraining = on;
      }
    });
  return (
    <Card
      title="Delegation"
      right={
        <span className="row">
          <button className="btn ghost sm" onClick={() => setAll(true)}>
            Delegate everything
          </button>
          <button className="btn ghost sm" onClick={() => setAll(false)}>
            Take everything back
          </button>
        </span>
      }
    >
      <div className="muted small" style={{ marginBottom: 6 }}>
        Delegated areas are handled by your staff with their own judgement; you can still act manually at any time.
      </div>
      <table className="table small">
        <tbody>
          {AREAS.map((a) => {
            const info = DELEGATION_INFO[a];
            const roleAllows = can(world, AREA_PERM[a]);
            return (
              <tr key={a}>
                <td>
                  <label>
                    <input
                      type="checkbox"
                      disabled={!roleAllows}
                      checked={!userHandles(world, a)}
                      onChange={(e) => act((w) => ((w.meta.delegation ??= {})[a] = e.target.checked))}
                    />{' '}
                    <b>{info.label}</b>
                  </label>
                </td>
                <td className="muted">{info.desc}</td>
                <td>{roleAllows ? (userHandles(world, a) ? 'You' : info.staff) : `${info.staff} (your role: ${PERMISSION_LABEL[AREA_PERM[a]].toLowerCase()} is not yours)`}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <div style={{ marginTop: 8 }}>
        <b className="small">Per team (head coach)</b>
        <table className="table small">
          <thead>
            <tr>
              <th>Team</th>
              <th>Lineups</th>
              <th>Drafts & vetoes</th>
              <th>Training & scrims</th>
            </tr>
          </thead>
          <tbody>
            {teams.map((t) => (
              <tr key={t.id}>
                <td>
                  <TitleTag id={t.titleId} /> <TeamLink t={t} />
                </td>
                {(['delegateLineup', 'delegateDraft', 'delegateTraining'] as const).map((k) => (
                  <td key={k}>
                    <input type="checkbox" aria-label={`${t.short} ${k}`} checked={!!t.prefs[k]} onChange={(e) => act((w) => (w.teams[t.id].prefs[k] = e.target.checked))} /> coach
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
}

export function SettingsPage() {
  const world = useWorld();
  const theme = useGame((s) => s.theme);
  const { setTheme, save, act, closeGame, notify, refreshSaves } = useGame.getState();
  const fileRef = useRef<HTMLInputElement>(null);
  const d = world.meta.detail;
  return (
    <div>
      <PageHeader title="Settings" sub={`Save "${world.meta.name}" · seed ${world.meta.seed} · schema v${SAVE_VERSION} · world size ${world.meta.worldSize}`} />
      <GameModeCard world={world} />
      <DifficultyCard world={world} />
      <DelegationCard world={world} />
      <div className="grid2">
        <Card title="Display">
          <label>
            <input type="radio" checked={theme === 'dark'} onChange={() => setTheme('dark')} /> Dark
          </label>{' '}
          <label>
            <input type="radio" checked={theme === 'light'} onChange={() => setTheme('light')} /> Light
          </label>
          <OnlinePicturesToggle />
        </Card>
        <Card title="Saving">
          <label style={{ display: 'block' }}>
            <input type="checkbox" checked={world.meta.autosave} onChange={(e) => act((w) => (w.meta.autosave = e.target.checked))} /> Autosave (previous state kept as a backup)
          </label>
          <AutosaveCadence />
          <div className="row" style={{ marginTop: 8 }}>
            <button className="btn" onClick={() => navigate('/saves')}>
              Save / Load (5 slots)…
            </button>
            <button
              className="btn ghost"
              onClick={async () => {
                const r = await saveFile(exportSave(world), `${world.meta.name.replace(/\W+/g, '_')}-${world.season}.uem.json`);
                notify(r.message, r.ok ? 'ok' : 'err');
              }}
            >
              Export save file
            </button>
            <button className="btn ghost" onClick={() => fileRef.current?.click()}>
              Import save file
            </button>
            <input
              ref={fileRef}
              type="file"
              accept=".json,application/json"
              style={{ display: 'none' }}
              onChange={async (e) => {
                const f = e.target.files?.[0];
                if (!f) return;
                try {
                  const w = await importSave(await f.text());
                  await refreshSaves();
                  notify(`Imported "${w.meta.name}". Load it from the main menu.`, 'ok');
                } catch (err) {
                  notify(`Import failed: ${(err as Error).message}`, 'err');
                }
              }}
            />
            <button className="btn ghost" onClick={async () => { await save(true); closeGame(); }}>
              Save & exit to menu
            </button>
          </div>
        </Card>
        <Card title="Simulation detail">
          <div className="muted small" style={{ marginBottom: 6 }}>
            Every match is simulated with the same engine and seed, so results are identical at any detail level. Full detail keeps box scores and commentary (larger saves); other matches keep results and season statistics only.
          </div>
          <label style={{ display: 'block' }}>
            <input type="checkbox" checked={d.userMatches} onChange={(e) => act((w) => (w.meta.detail.userMatches = e.target.checked))} /> Full detail for my teams&apos; matches
          </label>
          <label style={{ display: 'block' }}>
            <input type="checkbox" checked={d.internationals} onChange={(e) => act((w) => (w.meta.detail.internationals = e.target.checked))} /> Full detail for international events & World Championships
          </label>
          <div style={{ marginTop: 6 }}>Full detail for every competition in these titles:</div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))' }}>
            {world.meta.titlesEnabled.map((id) => (
              <label key={id}>
                <input
                  type="checkbox"
                  checked={d.fullDetailTitles.includes(id)}
                  onChange={(e) => act((w) => (w.meta.detail.fullDetailTitles = e.target.checked ? [...w.meta.detail.fullDetailTitles, id] : w.meta.detail.fullDetailTitles.filter((x) => x !== id)))}
                />{' '}
                {getTitle(id).short}
              </label>
            ))}
          </div>
          <div className="muted small">Applies to fixtures scheduled from now on.</div>
        </Card>
        <Card title="Career">
          <div className="attr-row"><span>Manager</span><b>{world.meta.managerName}</b></div>
          <div className="attr-row"><span>Career role</span><b>{world.meta.mode === 'spectator' ? 'Spectator' : ROLE_LABEL[world.meta.careerRole]}</b></div>
        </Card>
      </div>
    </div>
  );
}

/** Real logos, photos, characters and weapons load from their owners' servers when online. */
function OnlinePicturesToggle() {
  const [on, setOn] = useState(onlinePictures());
  return (
    <div style={{ marginTop: 8 }}>
      <label>
        <input
          type="checkbox"
          checked={on}
          onChange={(e) => {
            setOnlinePictures(e.target.checked);
            setOn(e.target.checked);
          }}
        />{' '}
        Online pictures (characters, weapons, team &amp; competition logos, player photos)
      </label>
      <div className="muted small">Loaded from the official / source websites when this device is online; never stored in the game or the save. Offline or switched off, the game shows its own badges. Applies when a page is reopened.</div>
    </div>
  );
}

/** How often autosave runs (device setting). */
function AutosaveCadence() {
  const [v, setV] = useState(autosaveEvery());
  return (
    <div style={{ marginTop: 6 }}>
      <label className="field">
        <span>Autosave every</span>
        <select
          value={v}
          onChange={(e) => {
            setAutosaveEvery(e.target.value as AutosaveEvery);
            setV(e.target.value as AutosaveEvery);
          }}
        >
          <option value="advance">Advance (slowest)</option>
          <option value="week">Week of game time (recommended)</option>
          <option value="month">Month of game time (fastest)</option>
        </select>
      </label>
      <div className="muted small">The game also saves at each new season and whenever Android puts the app in the background. Use Save to save at any moment.</div>
    </div>
  );
}

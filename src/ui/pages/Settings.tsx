import { useRef } from 'react';
import { navigate } from '../router';
import { saveFile } from '../fileExport';
import { useGame, useWorld } from '../store';
import { Card, PageHeader } from '../components/common';
import { exportSave, importSave } from '../../persistence/db';
import { getTitle } from '../../data/titles/catalogue';
import { SAVE_VERSION } from '../../persistence/version';

export function SettingsPage() {
  const world = useWorld();
  const { theme, setTheme, save, act, closeGame, notify, refreshSaves } = useGame();
  const fileRef = useRef<HTMLInputElement>(null);
  const d = world.meta.detail;
  return (
    <div>
      <PageHeader title="Settings" sub={`Save "${world.meta.name}" · seed ${world.meta.seed} · schema v${SAVE_VERSION} · world size ${world.meta.worldSize}`} />
      <div className="grid2">
        <Card title="Display">
          <label>
            <input type="radio" checked={theme === 'dark'} onChange={() => setTheme('dark')} /> Dark
          </label>{' '}
          <label>
            <input type="radio" checked={theme === 'light'} onChange={() => setTheme('light')} /> Light
          </label>
        </Card>
        <Card title="Saving">
          <label style={{ display: 'block' }}>
            <input type="checkbox" checked={world.meta.autosave} onChange={(e) => act((w) => (w.meta.autosave = e.target.checked))} /> Autosave after every advance (previous state kept as a backup)
          </label>
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
          <div className="attr-row"><span>Career role</span><b>{world.meta.careerRole} (combined head coach & GM)</b></div>
          <div className="muted small">The data model supports head coach, GM, director, owner, national coach and commissioner permissions; this release plays as Coach/GM with delegation toggles on Tactics & Training.</div>
        </Card>
      </div>
    </div>
  );
}

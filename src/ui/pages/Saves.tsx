/**
 * Save / Load: five manual save slots plus one autosave per career, import/export, and the
 * pre-seven-title backups of converted old saves. Used in-game (#/saves) and on the Welcome page.
 */
import { useEffect, useRef } from 'react';
import { useGame } from '../store';
import { Card, PageHeader } from '../components/common';
import { deleteSave, exportLegacyBackup, exportSave, importSave, loadWorld, MANUAL_SLOTS, slotSaveId, type SaveMeta } from '../../persistence/db';
import { saveFile } from '../fileExport';


function fileName(s: SaveMeta, suffix = ''): string {
  return `${(s.manualSlot ? `slot${s.manualSlot}_` : '') + s.name.replace(/\W+/g, '_')}${suffix}.uem.json`;
}

function SaveInfo({ s }: { s: SaveMeta }) {
  return (
    <div className="grow" style={{ minWidth: 180 }}>
      <b>{s.name}</b>
      <div className="muted small">
        {s.orgName} · {s.dateLabel} · season {s.season} · {s.titles} title{s.titles === 1 ? '' : 's'} · saved {new Date(s.updated).toLocaleString()}
      </div>
    </div>
  );
}

function RowActions({ s, allowLoad }: { s: SaveMeta; allowLoad: boolean }) {
  const { load, refreshSaves, notify, unsaved, world } = useGame();
  return (
    <>
      {allowLoad && (
        <button
          className="btn sm"
          onClick={() => {
            if (world && unsaved && !confirm('Load this save? Unsaved progress in the current game will be lost.')) return;
            void load(s.id);
          }}
        >
          Load
        </button>
      )}
      <button
        className="btn ghost sm"
        onClick={async () => {
          try {
            const { world: w } = await loadWorld(s.id);
            const r = await saveFile(exportSave(w), fileName(s));
            notify(r.message, r.ok ? 'ok' : 'err');
          } catch (e) {
            notify(`Export failed: ${(e as Error).message}`, 'err');
          }
        }}
      >
        Export
      </button>
      {s.legacyBackup && (
        <button
          className="btn ghost sm"
          title="The untouched save from before the seven-title edition"
          onClick={async () => {
            const b = await exportLegacyBackup(s.id);
            if (!b) return notify('No original backup stored for this save.', 'err');
            const r = await saveFile(b, fileName(s, '_original'));
            notify(r.message, r.ok ? 'ok' : 'err');
          }}
        >
          Export original
        </button>
      )}
      <button
        className="btn ghost sm"
        onClick={async () => {
          if (!confirm(`Delete "${s.name}"${s.manualSlot ? ` from slot ${s.manualSlot}` : ''} permanently?`)) return;
          await deleteSave(s.id);
          await refreshSaves();
        }}
      >
        Delete
      </button>
    </>
  );
}

/** Slots 1-5, autosaves and import. `inGame` enables "Save here". */
export function SavesPanel({ inGame }: { inGame: boolean }) {
  const { world, saves, refreshSaves, saveSlot, notify } = useGame();
  const fileRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    void refreshSaves();
  }, [refreshSaves]);
  const slots = Array.from({ length: MANUAL_SLOTS }, (_, i) => i + 1).map((n) => ({ n, s: saves.find((x) => x.id === slotSaveId(n)) }));
  const autos = saves.filter((s) => !s.manualSlot && !s.id.startsWith('slot-'));
  const active = world?.meta.activeSlot ?? null;
  return (
    <>
      <Card title={`Save slots (${MANUAL_SLOTS})`} right={inGame && <span className="muted small">{active ? `The Save button writes to slot ${active}` : 'Pick a slot — the Save button then writes there'}</span>}>
        {slots.map(({ n, s }) => (
          <div key={n} className={`row save-row${active === n && s?.careerId === world?.meta.saveId ? ' hl' : ''}`} style={{ padding: '6px 0', borderBottom: '1px solid var(--border)' }}>
            <b style={{ width: 54 }}>Slot {n}</b>
            {s ? <SaveInfo s={s} /> : <div className="grow muted">Empty</div>}
            {inGame && world && (
              <button
                className="btn sm"
                aria-label={`Save to slot ${n}`}
                onClick={async () => {
                  if (s && s.careerId !== world.meta.saveId && !confirm(`Slot ${n} holds "${s.name}" (another career). Overwrite it?`)) return;
                  if (s && s.careerId === world.meta.saveId && active !== n && !confirm(`Overwrite slot ${n} (${s.dateLabel})?`)) return;
                  await saveSlot(n);
                }}
              >
                Save here
              </button>
            )}
            {s && <RowActions s={s} allowLoad />}
          </div>
        ))}
      </Card>
      <Card
        title="Autosaves (one per career)"
        right={
          <>
            <button className="btn ghost sm" onClick={() => fileRef.current?.click()}>
              Import…
            </button>
            <input
              ref={fileRef}
              type="file"
              accept="application/json,.json"
              style={{ display: 'none' }}
              onChange={async (e) => {
                const f = e.target.files?.[0];
                e.target.value = '';
                if (!f) return;
                try {
                  const w = await importSave(await f.text());
                  await refreshSaves();
                  notify(`Imported "${w.meta.name}" — it is listed under Autosaves.`, 'ok');
                } catch (err) {
                  notify(`Import failed: ${(err as Error).message}`, 'err');
                }
              }}
            />
          </>
        }
      >
        {!autos.length && <div className="muted">No autosaves yet. A career autosaves after every advance (Settings → Autosave).</div>}
        {autos.map((s) => (
          <div key={s.id} className="row save-row" style={{ padding: '6px 0', borderBottom: '1px solid var(--border)' }}>
            <SaveInfo s={s} />
            <RowActions s={s} allowLoad={!inGame || s.id !== world?.meta.saveId} />
          </div>
        ))}
      </Card>
    </>
  );
}

export function SavesPage() {
  const world = useGame((s) => s.world);
  return (
    <div>
      <PageHeader title="Save / Load" sub={world ? `${world.meta.name} · ${world.meta.autosave ? 'autosave on' : 'autosave off'}` : undefined} />
      <SavesPanel inGame />
    </div>
  );
}

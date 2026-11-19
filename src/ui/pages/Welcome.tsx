import { useEffect, useMemo, useRef, useState } from 'react';
import { useGame } from '../store';
import { TITLES, getTitle } from '../../data/titles/catalogue';
import { DataTable } from '../components/DataTable';
import { Card, Flag, OrgBadge, TitleTag } from '../components/common';
import { regionName } from '../../data/countries';
import { formatMoney } from '../../core/math';
import { deleteSave, exportSave, getSession, importSave } from '../../persistence/db';
import type { Organisation } from '../../model/types';
import { computeTeamStrength } from '../../sim/common/team';

const SIZES = [
  { id: 'tiny', label: 'Tiny — fastest (1-2 leagues per title)' },
  { id: 'small', label: 'Small — quick (2-3 leagues per title)' },
  { id: 'standard', label: 'Standard — recommended (up to 5 leagues + challenger tiers)' },
  { id: 'large', label: 'Large — deepest, slowest (up to 8 leagues + 2 tiers)' },
] as const;

function OrgPicker() {
  const world = useGame((s) => s.world)!;
  const chooseOrg = useGame((s) => s.chooseOrg);
  const [title, setTitle] = useState('');
  const rows = useMemo(
    () =>
      Object.values(world.orgs)
        .filter((o) => o.active && o.teams.length && (!title || o.teams.some((t) => world.teams[t].titleId === title)))
        .map((o) => ({ o, avgStr: o.teams.reduce((a, t) => a + computeTeamStrength(world, world.teams[t]), 0) / o.teams.length })),
    [world, title],
  );
  return (
    <Card title="Choose your organisation" right={<span className="muted small">You start as combined Coach / General Manager</span>}>
      <div className="row" style={{ marginBottom: 8 }}>
        <label className="field">
          <span>Has a division in</span>
          <select value={title} onChange={(e) => setTitle(e.target.value)}>
            <option value="">Any title</option>
            {world.meta.titlesEnabled.map((id) => (
              <option key={id} value={id}>
                {getTitle(id).name}
              </option>
            ))}
          </select>
        </label>
      </div>
      <DataTable
        id="org-picker"
        rows={rows}
        rowKey={(r) => r.o.id}
        initialSort={{ key: 'rep', desc: true }}
        filter={(r, q) => r.o.name.toLowerCase().includes(q)}
        pageSize={25}
        columns={[
          { key: 'name', label: 'Organisation', value: (r) => r.o.name, render: (r) => (<span><OrgBadge org={r.o} size={14} /> <b>{r.o.name}</b></span>) },
          { key: 'country', label: 'Base', value: (r) => r.o.country, render: (r) => (<span><Flag cc={r.o.country} />{regionName(r.o.region)}</span>) },
          { key: 'divs', label: 'Divisions', value: (r) => r.o.teams.length, render: (r) => (<span>{r.o.teams.map((t) => <TitleTag key={t} id={world.teams[t].titleId} link={false} />)}</span>) },
          { key: 'rep', label: 'Reputation', value: (r) => r.o.rep, align: 'right', render: (r) => r.o.rep.toFixed(0) },
          { key: 'str', label: 'Avg strength', value: (r) => r.avgStr, align: 'right', render: (r) => r.avgStr.toFixed(1) },
          { key: 'bal', label: 'Balance', value: (r) => r.o.balance, align: 'right', render: (r) => formatMoney(r.o.balance) },
          { key: 'strat', label: 'Board strategy', value: (r) => r.o.strategy },
          { key: 'go', label: '', render: (r) => <button className="btn sm" onClick={() => chooseOrg(r.o.id)}>Manage</button> },
        ]}
      />
    </Card>
  );
}

export function Welcome() {
  const { world, saves, refreshSaves, newGame, load, notify } = useGame();
  const [name, setName] = useState('My Career');
  const [seed, setSeed] = useState(() => Math.random().toString(36).slice(2, 8));
  const [size, setSize] = useState<'tiny' | 'small' | 'standard' | 'large'>('small');
  const [titles, setTitles] = useState<string[]>(TITLES.map((t) => t.id));
  const [manager, setManager] = useState('Manager');
  const [session, setSession] = useState<{ saveId: string; dirty: boolean } | undefined>();
  const fileRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    void refreshSaves();
    getSession().then(setSession).catch(() => undefined);
  }, [refreshSaves]);

  if (world && !world.meta.userOrgId) {
    return (
      <div className="welcome">
        <div className="hero">
          <h1>{world.meta.name}</h1>
          <p className="muted">
            {Object.keys(world.orgs).length} organisations · {Object.keys(world.teams).length} teams · {Object.keys(world.players).length.toLocaleString()} players across {world.meta.titlesEnabled.length} titles
          </p>
        </div>
        <OrgPicker />
      </div>
    );
  }

  const toggle = (id: string) => setTitles((ts) => (ts.includes(id) ? ts.filter((x) => x !== id) : [...ts, id]));
  return (
    <div className="welcome">
      <div className="hero">
        <h1>
          ULTIMATE <span style={{ color: 'var(--accent)' }}>ESPORTS</span> MANAGER
        </h1>
        <p className="muted">A living MOBA & shooter universe — 30 titles, fictional organisations and players, endless seasons.</p>
      </div>
      {session?.dirty && saves.some((s) => s.id === session.saveId) && (
        <Card title="Recover session">
          <div className="row">
            <span>The last session ended without saving. Resume from its most recent autosave?</span>
            <button className="btn" onClick={() => load(session.saveId)}>
              Recover
            </button>
          </div>
        </Card>
      )}
      <div className="grid2">
        <Card title="New career">
          <div className="row" style={{ alignItems: 'flex-end' }}>
            <label className="field">
              <span>Save name</span>
              <input value={name} onChange={(e) => setName(e.target.value)} />
            </label>
            <label className="field">
              <span>Manager name</span>
              <input value={manager} onChange={(e) => setManager(e.target.value)} />
            </label>
            <label className="field">
              <span>World seed</span>
              <input value={seed} onChange={(e) => setSeed(e.target.value)} />
            </label>
          </div>
          <label className="field" style={{ marginTop: 8 }}>
            <span>World size</span>
            <select value={size} onChange={(e) => setSize(e.target.value as typeof size)}>
              {SIZES.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.label}
                </option>
              ))}
            </select>
          </label>
          <div style={{ marginTop: 10 }}>
            <div className="row" style={{ marginBottom: 4 }}>
              <b>Titles in this universe ({titles.length}/30)</b>
              <button className="btn ghost sm" onClick={() => setTitles(TITLES.map((t) => t.id))}>
                All
              </button>
              <button className="btn ghost sm" onClick={() => setTitles(TITLES.filter((t) => t.family === 'MOBA').map((t) => t.id))}>
                MOBA only
              </button>
              <button className="btn ghost sm" onClick={() => setTitles(TITLES.filter((t) => t.family === 'SHOOTER').map((t) => t.id))}>
                Shooters only
              </button>
              <button className="btn ghost sm" onClick={() => setTitles(['mlbb', 'lol', 'valorant', 'cs2', 'pubgm', 'overwatch'])}>
                Starter six
              </button>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(190px, 1fr))', gap: 2 }}>
              {TITLES.map((t) => (
                <label key={t.id}>
                  <input type="checkbox" checked={titles.includes(t.id)} onChange={() => toggle(t.id)} /> {t.name}
                </label>
              ))}
            </div>
          </div>
          <div className="row" style={{ marginTop: 12 }}>
            <button
              className="btn"
              disabled={!titles.length}
              onClick={() => newGame({ name, seed, worldSize: size, titles, managerName: manager })}
            >
              Generate world
            </button>
            <span className="muted small">Starts on today&apos;s date, during the off-season before the new season.</span>
          </div>
        </Card>
        <Card
          title="Saved games"
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
                  if (!f) return;
                  try {
                    const w = await importSave(await f.text());
                    await refreshSaves();
                    notify(`Imported "${w.meta.name}".`, 'ok');
                  } catch (err) {
                    notify(`Import failed: ${(err as Error).message}`, 'err');
                  }
                }}
              />
            </>
          }
        >
          {!saves.length && <div className="muted">No saves yet.</div>}
          {saves.map((s) => (
            <div key={s.id} className="row" style={{ padding: '6px 0', borderBottom: '1px solid var(--border)' }}>
              <div className="grow">
                <b>{s.name}</b>
                <div className="muted small">
                  {s.orgName} · {s.dateLabel} · season {s.season} · {s.titles} titles · v{s.version}
                </div>
              </div>
              <button className="btn sm" onClick={() => load(s.id)}>
                Load
              </button>
              <button
                className="btn ghost sm"
                onClick={async () => {
                  const { loadWorld } = await import('../../persistence/db');
                  const { world: w } = await loadWorld(s.id);
                  const url = URL.createObjectURL(exportSave(w));
                  const a = document.createElement('a');
                  a.href = url;
                  a.download = `${s.name.replace(/\W+/g, '_')}.uem.json`;
                  a.click();
                  URL.revokeObjectURL(url);
                }}
              >
                Export
              </button>
              <button
                className="btn ghost sm"
                onClick={async () => {
                  if (!confirm(`Delete "${s.name}" permanently?`)) return;
                  await deleteSave(s.id);
                  await refreshSaves();
                }}
              >
                Delete
              </button>
            </div>
          ))}
        </Card>
      </div>
    </div>
  );
}

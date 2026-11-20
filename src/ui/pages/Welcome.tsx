import { navigate } from '../router';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useGame } from '../store';
import { TITLES, getTitle } from '../../data/titles/catalogue';
import { DataTable } from '../components/DataTable';
import { Card, Flag, OrgBadge, TitleTag } from '../components/common';
import { regionName } from '../../data/countries';
import { formatMoney } from '../../core/math';
import { getSession } from '../../persistence/db';
import { SavesPanel } from './Saves';
import type { CareerRole, Organisation } from '../../model/types';
import { INPUT_LABEL } from '../../model/attributes';
import { ROLE_LABEL, jobOffers, takeJob } from '../../systems/career';
import { becomeSpectator } from '../../systems/follow';
import { SpectateCard } from './Following';
import { computeTeamStrength } from '../../sim/common/team';

const SIZES = [
  { id: 'tiny', label: 'Tiny — fastest (1-2 leagues per title)' },
  { id: 'small', label: 'Small — quick (2-3 leagues per title)' },
  { id: 'standard', label: 'Standard — recommended (up to 5 leagues + challenger tiers)' },
  { id: 'large', label: 'Large — deepest, slowest (up to 8 leagues + 2 tiers)' },
] as const;

export function OrgPicker() {
  const world = useGame((s) => s.world)!;
  const chooseOrg = useGame((s) => s.chooseOrg);
  const [title, setTitle] = useState('');
  const rows = useMemo(
    () =>
      Object.values(world.orgs)
        .filter((o) => o.active && !o.federation && o.teams.length && (!title || o.teams.some((t) => world.teams[t].titleId === title)))
        .map((o) => ({ o, avgStr: o.teams.reduce((a, t) => a + computeTeamStrength(world, world.teams[t]), 0) / o.teams.length })),
    [world, title],
  );
  return (
    <Card title="Choose your organisation" right={<span className="muted small">You start as {ROLE_LABEL[world.meta.careerRole]}</span>}>
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
          { key: 'go', label: '', render: (r) => <button className="btn sm" onClick={() => void chooseOrg(r.o.id).then(() => navigate('/'))}>Manage</button> },
        ]}
      />
    </Card>
  );
}

export function Welcome() {
  const { world, saves, refreshSaves, newGame, load, notify, storageError } = useGame();
  const [name, setName] = useState('My Career');
  const [seed, setSeed] = useState(() => Math.random().toString(36).slice(2, 8));
  const [size, setSize] = useState<'tiny' | 'small' | 'standard' | 'large'>('small');
  const [titles, setTitles] = useState<string[]>(TITLES.map((t) => t.id));
  const [manager, setManager] = useState('Manager');
  const [role, setRole] = useState<CareerRole>('coach-gm');
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
        <SpectateCard />
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
        <p className="muted">Five MOBAs and two tactical shooters — Mobile Legends: Bang Bang, Wild Rift, Honor of Kings, League of Legends, Dota 2, Valorant and Counter-Strike 2 — with fictional organisations and players and endless seasons.</p>
      </div>
      {storageError && (
        <Card title="Saving is unavailable">
          <div className="neg">{storageError}</div>
        </Card>
      )}
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
            <span>Career role</span>
            <select value={role} onChange={(e) => setRole(e.target.value as CareerRole)}>
              {(['coach-gm', 'head-coach', 'gm', 'director', 'owner', 'commissioner'] as CareerRole[]).map((r) => (
                <option key={r} value={r}>
                  {ROLE_LABEL[r]}
                </option>
              ))}
            </select>
          </label>
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
              <b>Titles in this universe ({titles.length}/{TITLES.length})</b>
              <button className="btn ghost sm" onClick={() => setTitles(TITLES.map((t) => t.id))}>
                All
              </button>
              <button className="btn ghost sm" onClick={() => setTitles(TITLES.filter((t) => t.family === 'MOBA').map((t) => t.id))}>
                MOBA only
              </button>
              <button className="btn ghost sm" onClick={() => setTitles(TITLES.filter((t) => t.family === 'SHOOTER').map((t) => t.id))}>
                Shooters only
              </button>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(190px, 1fr))', gap: 2 }}>
              {TITLES.map((t) => (
                <div key={t.id}>
                  <label>
                    <input type="checkbox" checked={titles.includes(t.id)} onChange={() => toggle(t.id)} /> {t.name}
                  </label>{' '}
                  <span className="muted small" title={`Source ID ${t.sourceId}`}>
                    {t.family === 'MOBA' ? 'MOBA' : 'Shooter'} · {INPUT_LABEL[t.input]}
                  </span>
                </div>
              ))}
            </div>
          </div>
          <div className="row" style={{ marginTop: 12 }}>
            <button
              className="btn"
              disabled={!titles.length}
              onClick={() => newGame({ name, seed, worldSize: size, titles, managerName: manager, careerRole: role })}
            >
              Generate world
            </button>
            <span className="muted small">Starts on today&apos;s date, during the off-season before the new season.</span>
          </div>
        </Card>
        <div>
          <SavesPanel inGame={false} />
        </div>
      </div>
    </div>
  );
}

/** Shown after a dismissal: pick a new organisation from those interested. */
export function JobOffers() {
  const world = useGame((s) => s.world)!;
  const act = useGame((s) => s.act);
  const offers = useMemo(() => jobOffers(world), [world]);
  return (
    <div className="welcome">
      <div className="hero">
        <h1>Looking for a new job</h1>
        <p className="muted">The board of {world.meta.userOrgId ? world.orgs[world.meta.userOrgId]?.name : 'your club'} dismissed you. These organisations want to talk.</p>
      </div>
      <Card title="Job offers">
        {offers.map((id) => {
          const o = world.orgs[id];
          return (
            <div key={id} className="row" style={{ padding: '6px 0', borderBottom: '1px solid var(--border)' }}>
              <OrgBadge org={o} size={18} />
              <b className="grow">{o.name}</b>
              <span>{o.teams.map((t) => <TitleTag key={t} id={world.teams[t].titleId} link={false} />)}</span>
              <span className="muted small">rep {o.rep.toFixed(0)}</span>
              <button className="btn sm" onClick={() => act((w) => takeJob(w, id), { save: true })}>
                Accept
              </button>
            </div>
          );
        })}
        {!offers.length && <div className="muted">No offers right now.</div>}
      </Card>
      <Card title="Or step away and watch">
        <div className="row">
          <span className="grow muted">Become a spectator: follow your old divisions (or any teams) while every club&apos;s staff make the decisions.</span>
          <button className="btn ghost" onClick={() => act((w) => becomeSpectator(w), { save: true })}>
            Spectate
          </button>
        </div>
      </Card>
    </div>
  );
}

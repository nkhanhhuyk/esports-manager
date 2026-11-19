/**
 * Database editor: edit or create fictional players, organisations and teams; league policies
 * (age limits, roster limits, import limits, salary caps, trades/loans/fees, franchising);
 * series format presets per title; title popularity. Changes apply to the live save.
 */
import { useState } from 'react';
import { useGame, useWorld } from '../store';
import { Card, PageHeader, Tabs, TitleTag } from '../components/common';
import { navigate, withQuery, type Route } from '../router';
import { ATTRS, ATTR_GROUPS, GROUP_INDICES } from '../../model/attributes';
import { getTitle, TITLES } from '../../data/titles/catalogue';
import { COUNTRIES, REGIONS } from '../../data/countries';
import { generatePlayer } from '../../gen/player';
import { RNG } from '../../core/rng';
import { computeCA } from '../../sim/common/ratings';
import type { FormatKey, League, SeriesFormat } from '../../model/types';

export function EditorPage({ route }: { route: Route }) {
  const tab = route.query.tab ?? 'players';
  return (
    <div>
      <PageHeader title="Database editor" sub="Customise the fictional universe. Edits are saved with your career and can be exported as a mod-friendly JSON save." />
      <Tabs
        tabs={[
          { id: 'players', label: 'Players' },
          { id: 'orgs', label: 'Organisations & teams' },
          { id: 'leagues', label: 'League policies' },
          { id: 'formats', label: 'Match formats' },
          { id: 'titles', label: 'Title popularity' },
        ]}
        active={tab}
        onChange={(x) => navigate(withQuery(route, { tab: x }))}
      />
      {tab === 'players' && <PlayerEditor />}
      {tab === 'orgs' && <OrgEditor />}
      {tab === 'leagues' && <LeagueEditor />}
      {tab === 'formats' && <FormatEditor />}
      {tab === 'titles' && <PopularityEditor />}
    </div>
  );
}

function PlayerEditor() {
  const world = useWorld();
  const { act, notify } = useGame();
  const [q, setQ] = useState('');
  const [id, setId] = useState('');
  const p = world.players[id];
  const matches = q.length >= 2 ? Object.values(world.players).filter((x) => x.handle.toLowerCase().includes(q.toLowerCase())).slice(0, 12) : [];
  const [newTitle, setNewTitle] = useState(world.meta.titlesEnabled[0]);
  const [newNat, setNewNat] = useState('US');
  const set = (fn: (pp: typeof p) => void) =>
    act((w) => {
      const pp = w.players[id];
      fn(pp);
      pp.attrVersion++;
      pp.ca = computeCA(pp, getTitle(pp.titleId));
    });
  return (
    <div className="grid2">
      <Card title="Find player">
        <input placeholder="Handle…" value={q} onChange={(e) => setQ(e.target.value)} />
        {matches.map((m) => (
          <div key={m.id}>
            <button className="btn ghost sm" onClick={() => setId(m.id)}>
              {m.handle} ({getTitle(m.titleId).short})
            </button>
          </div>
        ))}
        <hr />
        <b>Create a new player (free agent)</b>
        <div className="row">
          <select value={newTitle} onChange={(e) => setNewTitle(e.target.value)} aria-label="Title">
            {world.meta.titlesEnabled.map((t) => (
              <option key={t} value={t}>
                {getTitle(t).short}
              </option>
            ))}
          </select>
          <select value={newNat} onChange={(e) => setNewNat(e.target.value)} aria-label="Nationality">
            {COUNTRIES.map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
          <button
            className="btn sm"
            onClick={() => {
              const created = act((w) => {
                const np = generatePlayer({ world: w, rng: new RNG(`${w.meta.seed}|editor|${w.counter}`), handles: new Set(Object.values(w.players).map((x) => x.handle.toLowerCase())) }, { titleId: newTitle, nat: newNat, age: 18, targetCA: 60 });
                w.players[np.id] = np;
                w.scouting[np.id] = 100;
                return np.id;
              }, { save: true });
              if (created) {
                setId(created);
                notify('Player created.', 'ok');
              }
            }}
          >
            Create
          </button>
        </div>
      </Card>
      {p && (
        <Card title={`Editing ${p.handle}`} right={<button className="btn ghost sm" onClick={() => navigate(`/player/${p.id}`)}>Open profile</button>}>
          <div className="row">
            <label className="field">
              <span>Handle</span>
              <input value={p.handle} onChange={(e) => set((pp) => (pp.handle = e.target.value))} />
            </label>
            <label className="field">
              <span>First</span>
              <input value={p.first} onChange={(e) => set((pp) => (pp.first = e.target.value))} />
            </label>
            <label className="field">
              <span>Last</span>
              <input value={p.last} onChange={(e) => set((pp) => (pp.last = e.target.value))} />
            </label>
            <label className="field">
              <span>Hidden potential</span>
              <input type="number" min={0} max={100} value={Math.round(p.pa)} onChange={(e) => set((pp) => (pp.pa = Number(e.target.value)))} />
            </label>
          </div>
          <div className="attr-groups" style={{ marginTop: 8 }}>
            {ATTR_GROUPS.map((g) => (
              <div key={g}>
                <h4>{g}</h4>
                {GROUP_INDICES[g].map((i) => (
                  <div key={i} className="attr-row">
                    <span>{ATTRS[i].name}</span>
                    <input type="number" min={0} max={100} style={{ width: 56 }} value={Math.round(p.attrs[i])} onChange={(e) => set((pp) => (pp.attrs[i] = Math.max(0, Math.min(100, Number(e.target.value)))))} />
                  </div>
                ))}
              </div>
            ))}
            <div>
              <h4>Input proficiencies</h4>
              {['Touchscreen', 'Mouse & Keyboard', 'Controller'].map((l, i) => (
                <div key={l} className="attr-row">
                  <span>{l}</span>
                  <input type="number" min={0} max={100} style={{ width: 56 }} value={Math.round(p.input[i])} onChange={(e) => set((pp) => (pp.input[i] = Math.max(0, Math.min(100, Number(e.target.value)))))} />
                </div>
              ))}
            </div>
          </div>
        </Card>
      )}
    </div>
  );
}

function OrgEditor() {
  const world = useWorld();
  const { act } = useGame();
  const [id, setId] = useState(world.meta.userOrgId ?? '');
  const org = world.orgs[id];
  return (
    <Card title="Organisation">
      <select value={id} onChange={(e) => setId(e.target.value)} aria-label="Organisation">
        {Object.values(world.orgs)
          .sort((a, b) => a.name.localeCompare(b.name))
          .map((o) => (
            <option key={o.id} value={o.id}>
              {o.name}
            </option>
          ))}
      </select>
      {org && (
        <div className="row" style={{ marginTop: 8, alignItems: 'flex-end' }}>
          <label className="field">
            <span>Name</span>
            <input value={org.name} onChange={(e) => act((w) => (w.orgs[id].name = e.target.value))} />
          </label>
          <label className="field">
            <span>Tag</span>
            <input value={org.short} onChange={(e) => act((w) => (w.orgs[id].short = e.target.value.slice(0, 5)))} />
          </label>
          <label className="field">
            <span>Primary colour</span>
            <input type="color" value={org.colors[0]} onChange={(e) => act((w) => (w.orgs[id].colors[0] = e.target.value))} />
          </label>
          <label className="field">
            <span>Secondary colour</span>
            <input type="color" value={org.colors[1]} onChange={(e) => act((w) => (w.orgs[id].colors[1] = e.target.value))} />
          </label>
          <label className="field">
            <span>Balance</span>
            <input type="number" value={Math.round(org.balance)} onChange={(e) => act((w) => (w.orgs[id].balance = Number(e.target.value)))} />
          </label>
          <label className="field">
            <span>Reputation</span>
            <input type="number" min={0} max={100} value={Math.round(org.rep)} onChange={(e) => act((w) => (w.orgs[id].rep = Number(e.target.value)))} />
          </label>
          <label className="field">
            <span>Strategy</span>
            <select value={org.strategy} onChange={(e) => act((w) => (w.orgs[id].strategy = e.target.value as typeof org.strategy))}>
              {['win-now', 'balanced', 'youth', 'moneyball', 'prestige', 'expansion'].map((s) => (
                <option key={s}>{s}</option>
              ))}
            </select>
          </label>
          <button className="btn ghost sm" onClick={() => act((w) => { for (const t of w.orgs[id].teams) { const tm = w.teams[t]; tm.name = tm.tier === 'main' ? w.orgs[id].name : `${w.orgs[id].name} Academy`; tm.short = w.orgs[id].short; } })}>
            Apply name to teams
          </button>
        </div>
      )}
    </Card>
  );
}

function LeagueEditor() {
  const world = useWorld();
  const { act } = useGame();
  const leagues = Object.values(world.leagues);
  const [id, setId] = useState(leagues[0]?.id ?? '');
  const l = world.leagues[id];
  const setP = (fn: (x: League) => void) => act((w) => fn(w.leagues[id]));
  return (
    <Card title="League rules (applied from the next transaction / season)">
      <select value={id} onChange={(e) => setId(e.target.value)} aria-label="League">
        {leagues.map((x) => (
          <option key={x.id} value={x.id}>
            {getTitle(x.titleId).short} · {x.name}
          </option>
        ))}
      </select>
      {l && (
        <div className="row" style={{ marginTop: 8, alignItems: 'flex-end' }}>
          <label className="field">
            <span>League name</span>
            <input value={l.name} onChange={(e) => setP((x) => (x.name = e.target.value))} />
          </label>
          <label className="field">
            <span>Minimum age</span>
            <input type="number" value={l.policy.minAge} onChange={(e) => setP((x) => (x.policy.minAge = Number(e.target.value)))} />
          </label>
          <label className="field">
            <span>Roster max</span>
            <input type="number" value={l.policy.rosterMax} onChange={(e) => setP((x) => (x.policy.rosterMax = Number(e.target.value)))} />
          </label>
          <label className="field">
            <span>Import limit (-1 = none)</span>
            <input type="number" value={l.policy.foreignLimit} onChange={(e) => setP((x) => (x.policy.foreignLimit = Number(e.target.value)))} />
          </label>
          <label className="field">
            <span>Salary cap</span>
            <select value={l.policy.cap.type} onChange={(e) => setP((x) => (x.policy.cap.type = e.target.value as 'none' | 'soft' | 'hard'))}>
              <option value="none">None</option>
              <option value="soft">Soft (luxury tax)</option>
              <option value="hard">Hard</option>
            </select>
          </label>
          <label className="field">
            <span>Cap amount</span>
            <input type="number" value={l.policy.cap.amount} onChange={(e) => setP((x) => (x.policy.cap.amount = Number(e.target.value)))} />
          </label>
          <label className="field">
            <span>Min salary</span>
            <input type="number" value={l.policy.minSalary} onChange={(e) => setP((x) => (x.policy.minSalary = Number(e.target.value)))} />
          </label>
          <label className="field">
            <span>Max salary (0 = none)</span>
            <input type="number" value={l.policy.maxSalary} onChange={(e) => setP((x) => (x.policy.maxSalary = Number(e.target.value)))} />
          </label>
          {(['trades', 'loans', 'transferFees', 'draft', 'restrictedFA'] as const).map((k) => (
            <label key={k}>
              <input type="checkbox" checked={l.policy[k]} onChange={(e) => setP((x) => (x.policy[k] = e.target.checked))} /> {k}
            </label>
          ))}
          <label>
            <input type="checkbox" checked={l.franchised} onChange={(e) => setP((x) => (x.franchised = e.target.checked))} /> franchised (no relegation)
          </label>
          <label>
            <input type="checkbox" checked={l.fullDetail} onChange={(e) => setP((x) => (x.fullDetail = e.target.checked))} /> full-detail simulation
          </label>
        </div>
      )}
    </Card>
  );
}

function FormatEditor() {
  const world = useWorld();
  const { act } = useGame();
  const [tid, setTid] = useState(world.meta.titlesEnabled[0]);
  const t = getTitle(tid);
  const cur = (k: FormatKey): SeriesFormat => world.customFormats[tid]?.[k] ?? t.formats[k];
  const setF = (k: FormatKey, n: number) =>
    act((w) => {
      const base = cur(k);
      const nf: SeriesFormat = { ...base, n, label: `Bo${n}` };
      w.customFormats[tid] = { ...(w.customFormats[tid] ?? {}), [k]: nf };
    });
  return (
    <Card title="Series format presets (affect newly scheduled fixtures)">
      <select value={tid} onChange={(e) => setTid(e.target.value)} aria-label="Title">
        {TITLES.filter((x) => world.meta.titlesEnabled.includes(x.id)).map((x) => (
          <option key={x.id} value={x.id}>
            {x.name}
          </option>
        ))}
      </select>
      <table className="table" style={{ marginTop: 8 }}>
        <tbody>
          {(['standard', 'playoff', 'final'] as FormatKey[]).map((k) => (
            <tr key={k}>
              <td>{k}</td>
              <td>{cur(k).label}</td>
              <td>
                <select value={cur(k).n} onChange={(e) => setF(k, Number(e.target.value))} aria-label={`${k} length`}>
                  {[1, 3, 5, 7, 9].map((n) => (
                    <option key={n} value={n}>
                      {n}
                    </option>
                  ))}
                </select>
              </td>
              <td className="muted small">default {t.formats[k].label}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <button className="btn ghost sm" onClick={() => act((w) => delete w.customFormats[tid])}>
        Reset to specification defaults
      </button>
    </Card>
  );
}

function PopularityEditor() {
  const world = useWorld();
  const { act } = useGame();
  const [tid, setTid] = useState(world.meta.titlesEnabled[0]);
  const ts = world.titles[tid];
  return (
    <Card title="Regional popularity (drives salaries, sponsorship, viewership and talent)">
      <select value={tid} onChange={(e) => setTid(e.target.value)} aria-label="Title">
        {world.meta.titlesEnabled.map((x) => (
          <option key={x} value={x}>
            {getTitle(x).name}
          </option>
        ))}
      </select>{' '}
      <TitleTag id={tid} />
      <div className="attr-groups" style={{ marginTop: 8 }}>
        {REGIONS.map((r) => (
          <div key={r.id} className="attr-row">
            <span>{r.name}</span>
            <input
              type="number"
              min={0}
              max={100}
              style={{ width: 60 }}
              value={ts.popularity[r.id] ?? 0}
              onChange={(e) =>
                act((w) => {
                  const t2 = w.titles[tid];
                  t2.popularity[r.id] = Math.max(0, Math.min(100, Number(e.target.value)));
                  const vals = Object.values(t2.popularity);
                  t2.globalPop = vals.reduce((a, b) => a + b, 0) / vals.length;
                })
              }
            />
          </div>
        ))}
      </div>
    </Card>
  );
}

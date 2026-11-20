/**
 * Stat groups for player tables (competition and player pages): pick a group and totals / per-game
 * with dropdowns. Values come from `CompPlayerStats.v` (the title's stat totals in stat order).
 */
import { getTitle } from '../../data/titles/catalogue';
import type { CompPlayerStats } from '../../model/types';

export interface StatGroup {
  id: string;
  label: string;
  keys: string[];
  kda?: boolean;
}

export function statGroups(titleId: string): StatGroup[] {
  const t = getTitle(titleId);
  const has = new Set(t.stats.map((s) => s.key));
  const g = (id: string, label: string, keys: string[], kda = false): StatGroup => ({ id, label, keys: keys.filter((k) => has.has(k)), kda });
  const groups =
    t.family === 'MOBA'
      ? [g('overview', 'Overview (K / D / A / KDA)', ['k', 'd', 'a'], true), g('combat', 'Combat', ['k', 'd', 'a', 'dmg', 'taken', 'heal'], true), g('farm', 'Farming & gold', ['gold', 'cs', 'dn']), g('map', 'Vision & objectives', ['vision', 'obj', 'towers', 'bb']), g('all', 'All stats', t.stats.map((s) => s.key), true)]
      : [g('overview', 'Overview (K / D / A / KDA)', ['k', 'd', 'a'], true), g('frag', 'Fragging', ['k', 'd', 'a', 'hs', 'mk', 'fk', 'fd'], true), g('impact', 'Impact', ['dmg', 'kast', 'clutch', 'util']), g('obj', 'Objective play', ['plant', 'defuse', 'fk', 'clutch']), g('all', 'All stats', t.stats.map((s) => s.key), true)];
  return groups.filter((x) => x.keys.length);
}

export function statValue(titleId: string, cs: CompPlayerStats | undefined, key: string): number {
  if (!cs?.v) return 0;
  const i = getTitle(titleId).stats.findIndex((s) => s.key === key);
  return i >= 0 ? cs.v[i] ?? 0 : 0;
}

export function kdaOf(titleId: string, cs: CompPlayerStats | undefined): number {
  const k = statValue(titleId, cs, 'k');
  const d = statValue(titleId, cs, 'd');
  const a = statValue(titleId, cs, 'a');
  return (k + a) / Math.max(1, d);
}

/** Column definitions (DataTable) for one stat group. */
export function statColumns<T>(titleId: string, group: StatGroup, perGame: boolean, get: (r: T) => CompPlayerStats | undefined) {
  const t = getTitle(titleId);
  const label = (k: string) => t.stats.find((s) => s.key === k)?.label ?? k;
  const cols = group.keys.map((k) => ({
    key: `st-${k}`,
    label: perGame ? `${label(k)}/g` : label(k),
    align: 'right' as const,
    value: (r: T) => {
      const cs = get(r);
      const v = statValue(titleId, cs, k);
      return perGame ? v / Math.max(1, cs?.gp ?? 1) : v;
    },
    render: (r: T) => {
      const cs = get(r);
      if (!cs?.v) return <span className="muted">—</span>;
      const v = statValue(titleId, cs, k);
      const x = perGame ? v / Math.max(1, cs.gp) : v;
      return x >= 1000 ? Math.round(x).toLocaleString() : x.toFixed(perGame ? 1 : 0);
    },
  }));
  if (group.kda)
    cols.push({
      key: 'st-kda',
      label: 'KDA',
      align: 'right' as const,
      value: (r: T) => kdaOf(titleId, get(r)),
      render: (r: T) => (get(r)?.v ? kdaOf(titleId, get(r)).toFixed(2) : '—'),
    });
  return cols;
}

/** Group + totals/per-game dropdowns. */
export function StatControls({ titleId, group, setGroup, perGame, setPerGame }: { titleId: string; group: string; setGroup: (g: string) => void; perGame: boolean; setPerGame: (v: boolean) => void }) {
  return (
    <>
      <select value={group} onChange={(e) => setGroup(e.target.value)} aria-label="Stat group">
        {statGroups(titleId).map((g) => (
          <option key={g.id} value={g.id}>
            {g.label}
          </option>
        ))}
      </select>
      <select value={perGame ? 'pg' : 'tot'} onChange={(e) => setPerGame(e.target.value === 'pg')} aria-label="Totals or per game">
        <option value="pg">Per game</option>
        <option value="tot">Totals</option>
      </select>
    </>
  );
}

/**
 * Dense, sortable, filterable table with column customisation (persisted per table id),
 * pagination and a horizontally scrollable container for small screens.
 */
import { useMemo, useState, type ReactNode } from 'react';

export interface Column<T> {
  key: string;
  label: string;
  render?: (row: T) => ReactNode;
  value?: (row: T) => number | string | null | undefined;
  title?: string;
  align?: 'left' | 'right' | 'center';
  width?: number;
  hidden?: boolean; // hidden by default (user can enable)
  sortDesc?: boolean; // default sort direction for this column
}

interface Props<T> {
  id: string;
  rows: T[];
  columns: Column<T>[];
  rowKey: (row: T) => string;
  initialSort?: { key: string; desc?: boolean };
  pageSize?: number;
  filter?: (row: T, q: string) => boolean;
  onRowClick?: (row: T) => void;
  dense?: boolean;
  empty?: string;
  toolbar?: ReactNode;
  highlight?: (row: T) => boolean;
}

function loadHidden(id: string): string[] | null {
  try {
    const v = localStorage.getItem(`uem-cols-${id}`);
    return v ? (JSON.parse(v) as string[]) : null;
  } catch {
    return null;
  }
}

function saveHidden(id: string, cols: string[]) {
  try {
    localStorage.setItem(`uem-cols-${id}`, JSON.stringify(cols));
  } catch {
    /* ignore */
  }
}

export function DataTable<T>({ id, rows, columns, rowKey, initialSort, pageSize = 50, filter, onRowClick, empty, toolbar, highlight }: Props<T>) {
  const [sort, setSort] = useState<{ key: string; desc: boolean } | null>(initialSort ? { key: initialSort.key, desc: initialSort.desc ?? true } : null);
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [hidden, setHidden] = useState<string[]>(() => loadHidden(id) ?? columns.filter((c) => c.hidden).map((c) => c.key));
  const [chooser, setChooser] = useState(false);
  const visible = columns.filter((c) => !hidden.includes(c.key));

  const filtered = useMemo(() => (q && filter ? rows.filter((r) => filter(r, q.toLowerCase())) : rows), [rows, q, filter]);
  const sorted = useMemo(() => {
    if (!sort) return filtered;
    const col = columns.find((c) => c.key === sort.key);
    if (!col) return filtered;
    const get = col.value ?? ((r: T) => (r as Record<string, unknown>)[col.key] as number | string);
    return [...filtered].sort((a, b) => {
      const va = get(a);
      const vb = get(b);
      if (va === vb) return 0;
      if (va === null || va === undefined) return 1;
      if (vb === null || vb === undefined) return -1;
      const r = typeof va === 'number' && typeof vb === 'number' ? va - vb : String(va).localeCompare(String(vb));
      return sort.desc ? -r : r;
    });
  }, [filtered, sort, columns]);
  const pages = Math.max(1, Math.ceil(sorted.length / pageSize));
  const pg = Math.min(page, pages - 1);
  const slice = sorted.slice(pg * pageSize, pg * pageSize + pageSize);

  const toggleCol = (k: string) => {
    const next = hidden.includes(k) ? hidden.filter((x) => x !== k) : [...hidden, k];
    setHidden(next);
    saveHidden(id, next);
  };

  return (
    <div className="dt">
      <div className="dt-toolbar">
        {filter && <input className="dt-filter" placeholder="Filter…" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} aria-label="Filter table" />}
        {toolbar}
        <span className="dt-count">{sorted.length.toLocaleString()} rows</span>
        <button className="btn ghost sm" onClick={() => setChooser(!chooser)} title="Choose columns">
          Columns
        </button>
      </div>
      {chooser && (
        <div className="dt-chooser">
          {columns.map((c) => (
            <label key={c.key}>
              <input type="checkbox" checked={!hidden.includes(c.key)} onChange={() => toggleCol(c.key)} /> {c.label}
            </label>
          ))}
        </div>
      )}
      <div className="dt-scroll">
        <table className="table">
          <thead>
            <tr>
              {visible.map((c) => (
                <th
                  key={c.key}
                  title={c.title ?? c.label}
                  style={{ textAlign: c.align ?? 'left', width: c.width }}
                  className={sort?.key === c.key ? 'sorted' : ''}
                  onClick={() => setSort(sort?.key === c.key ? { key: c.key, desc: !sort.desc } : { key: c.key, desc: c.sortDesc ?? true })}
                >
                  {c.label}
                  {sort?.key === c.key ? (sort.desc ? ' ▾' : ' ▴') : ''}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {slice.map((r) => (
              <tr key={rowKey(r)} onClick={onRowClick ? () => onRowClick(r) : undefined} className={`${onRowClick ? 'clickable' : ''} ${highlight?.(r) ? 'hl' : ''}`}>
                {visible.map((c) => (
                  <td key={c.key} style={{ textAlign: c.align ?? 'left' }}>
                    {c.render ? c.render(r) : String((c.value ? c.value(r) : (r as Record<string, unknown>)[c.key]) ?? '')}
                  </td>
                ))}
              </tr>
            ))}
            {!slice.length && (
              <tr>
                <td colSpan={visible.length} className="muted center">
                  {empty ?? 'Nothing to show.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {pages > 1 && (
        <div className="dt-pager">
          <button className="btn ghost sm" disabled={pg === 0} onClick={() => setPage(pg - 1)}>
            ‹ Prev
          </button>
          <span>
            Page {pg + 1} / {pages}
          </span>
          <button className="btn ghost sm" disabled={pg >= pages - 1} onClick={() => setPage(pg + 1)}>
            Next ›
          </button>
        </div>
      )}
    </div>
  );
}

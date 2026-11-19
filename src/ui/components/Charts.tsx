/**
 * Lightweight SVG charts (no dependencies): line chart, bar chart, heat cell.
 */

export interface Series {
  name: string;
  color: string;
  points: Array<{ x: number; y: number }>;
}

export function LineChart({ series, height = 180, yMin, yMax, xLabel, yFormat }: { series: Series[]; height?: number; yMin?: number; yMax?: number; xLabel?: (x: number) => string; yFormat?: (y: number) => string }) {
  const all = series.flatMap((s) => s.points);
  if (!all.length) return <div className="muted small">No data yet.</div>;
  const W = 600;
  const H = height;
  const pad = { l: 44, r: 10, t: 10, b: 22 };
  const xs = all.map((p) => p.x);
  const ys = all.map((p) => p.y);
  const x0 = Math.min(...xs);
  const x1 = Math.max(...xs) === x0 ? x0 + 1 : Math.max(...xs);
  const y0 = yMin ?? Math.min(...ys);
  const y1b = yMax ?? Math.max(...ys);
  const y1 = y1b === y0 ? y0 + 1 : y1b;
  const sx = (x: number) => pad.l + ((x - x0) / (x1 - x0)) * (W - pad.l - pad.r);
  const sy = (y: number) => H - pad.b - ((y - y0) / (y1 - y0)) * (H - pad.t - pad.b);
  const ticks = 4;
  const fmt = yFormat ?? ((y: number) => y.toFixed(0));
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="chart" role="img" aria-label={series.map((s) => s.name).join(', ')}>
      {Array.from({ length: ticks + 1 }, (_, i) => {
        const y = y0 + ((y1 - y0) * i) / ticks;
        return (
          <g key={i}>
            <line x1={pad.l} x2={W - pad.r} y1={sy(y)} y2={sy(y)} className="grid" />
            <text x={pad.l - 6} y={sy(y) + 4} textAnchor="end" className="axis">
              {fmt(y)}
            </text>
          </g>
        );
      })}
      {[x0, x1].map((x) => (
        <text key={x} x={sx(x)} y={H - 6} textAnchor={x === x0 ? 'start' : 'end'} className="axis">
          {xLabel ? xLabel(x) : x}
        </text>
      ))}
      {series.map((s) => (
        <g key={s.name}>
          <polyline fill="none" stroke={s.color} strokeWidth={2} points={s.points.map((p) => `${sx(p.x)},${sy(p.y)}`).join(' ')} />
          {s.points.map((p, i) => (
            <circle key={i} cx={sx(p.x)} cy={sy(p.y)} r={2.5} fill={s.color}>
              <title>{`${s.name}: ${fmt(p.y)} (${xLabel ? xLabel(p.x) : p.x})`}</title>
            </circle>
          ))}
        </g>
      ))}
    </svg>
  );
}

export function BarChart({ items, height = 160, format }: { items: Array<{ label: string; value: number; color?: string }>; height?: number; format?: (v: number) => string }) {
  if (!items.length) return <div className="muted small">No data.</div>;
  const W = 600;
  const H = height;
  const max = Math.max(...items.map((i) => Math.abs(i.value)), 1);
  const bw = (W - 20) / items.length;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="chart" role="img">
      {items.map((it, i) => {
        const h = (Math.abs(it.value) / max) * (H - 36);
        return (
          <g key={it.label}>
            <rect x={10 + i * bw + 4} y={H - 20 - h} width={Math.max(2, bw - 8)} height={h} fill={it.color ?? 'var(--accent)'} rx={2}>
              <title>{`${it.label}: ${format ? format(it.value) : it.value}`}</title>
            </rect>
            <text x={10 + i * bw + bw / 2} y={H - 6} textAnchor="middle" className="axis">
              {it.label.length > 10 ? it.label.slice(0, 9) + '…' : it.label}
            </text>
            <text x={10 + i * bw + bw / 2} y={H - 24 - h} textAnchor="middle" className="axis">
              {format ? format(it.value) : it.value.toFixed(0)}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

export function heatColor(v: number): string {
  // 0..100 -> cool to warm, works in both themes
  const h = 220 - (v / 100) * 200;
  return `hsl(${h}, 65%, ${38 + v * 0.12}%)`;
}

export const PALETTE = ['#4cc9f0', '#f72585', '#ffd166', '#06d6a0', '#8338ec', '#ff7f50', '#90be6d', '#e76f51'];

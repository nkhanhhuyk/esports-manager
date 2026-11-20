/**
 * Live 2D battlefield viewer for MOBA games. Read-only: it plays back the authoritative recorded
 * timeline (`MobaReplay`) produced by the spatial simulation, interpolating between frames.
 * Nothing here can change a result — pausing, speeding up, scrubbing or skipping only changes
 * what is shown.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import type { MobaReplay } from '../../model/types';
import { mobaMap } from '../../sim/moba/maps';
import type { MapDefinition } from '../../sim/moba/map';

const TEAM_COL = ['#3fa7ff', '#ff5d5d'];
const TEAM_DARK = ['#1d4f7a', '#7a2323'];
const SPEEDS = [1, 2, 4, 8, 16];
const ACT_NAMES = ['', 'laning', 'jungling', 'roaming', 'objective', 'fighting', 'retreating', 'recalling', 'sieging', 'defending', 'grouping'];

interface Props {
  replay: MobaReplay;
  teamNames: [string, string];
  /** Called once when playback reaches the end (or the user skips to it). */
  onEnd?: () => void;
  autoplay?: boolean;
}

interface View {
  zoom: number;
  cx: number;
  cy: number;
}

/** Static layer (terrain, river, lanes) drawn once per map into an offscreen canvas. */
const staticCache = new Map<string, HTMLCanvasElement>();
function staticLayer(m: MapDefinition): HTMLCanvasElement {
  const hit = staticCache.get(m.id);
  if (hit) return hit;
  const c = document.createElement('canvas');
  c.width = 1000;
  c.height = 1000;
  const x = c.getContext('2d')!;
  x.fillStyle = '#3d4a33';
  x.fillRect(0, 0, 1000, 1000);
  for (const a of m.areas) {
    x.fillStyle = a.kind === 'highground' ? (a.team ? 'rgba(122,35,35,0.28)' : 'rgba(29,79,122,0.28)') : 'rgba(80,140,80,0.3)';
    x.beginPath();
    a.poly.forEach((p, i) => (i ? x.lineTo(p.x, p.y) : x.moveTo(p.x, p.y)));
    x.closePath();
    x.fill();
  }
  // River
  x.strokeStyle = 'rgba(70,130,180,0.65)';
  x.lineWidth = 64;
  x.lineCap = 'round';
  x.lineJoin = 'round';
  x.beginPath();
  m.river.forEach((p, i) => (i ? x.lineTo(p.x, p.y) : x.moveTo(p.x, p.y)));
  x.stroke();
  // Lanes
  x.strokeStyle = '#8f7f5a';
  x.lineWidth = 34;
  for (const l of ['top', 'mid', 'bot'] as const) {
    x.beginPath();
    m.lanes[l].forEach((p, i) => (i ? x.lineTo(p.x, p.y) : x.moveTo(p.x, p.y)));
    x.stroke();
  }
  // Bases
  for (const side of [0, 1]) {
    x.fillStyle = side ? 'rgba(122,35,35,0.45)' : 'rgba(29,79,122,0.45)';
    x.beginPath();
    x.arc(m.cores[side].x, m.cores[side].y, 70, 0, Math.PI * 2);
    x.fill();
  }
  // Terrain walls (jungle) on top so corridors read clearly.
  x.fillStyle = '#1e2a1c';
  for (const [wx, wy, ww, wh] of m.walls) x.fillRect(wx, wy, ww, wh);
  // Pits
  x.strokeStyle = 'rgba(255,209,102,0.7)';
  x.lineWidth = 3;
  for (const p of m.pits) {
    x.beginPath();
    x.arc(p.pos.x, p.pos.y, p.radius, 0, Math.PI * 2);
    x.stroke();
  }
  staticCache.set(m.id, c);
  return c;
}

export function MobaViewer({ replay, teamNames, onEnd, autoplay = true }: Props) {
  const map = useMemo(() => mobaMap(replay.titleId), [replay.titleId]);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const endT = (replay.frames - 1) * replay.dt;
  const [t, setT] = useState(0);
  const [playing, setPlaying] = useState(autoplay);
  const [speed, setSpeed] = useState(4);
  const [follow, setFollow] = useState(-1);
  const [opts, setOpts] = useState({ names: true, health: true, vision: false, paths: false });
  const [view, setView] = useState<View>({ zoom: 1, cx: 500, cy: 500 });
  const tRef = useRef(0);
  const endedRef = useRef(false);
  tRef.current = t;

  // Playback clock (independent of the simulation, which is already complete).
  useEffect(() => {
    if (!playing) return;
    let raf = 0;
    let last = performance.now();
    const tick = (now: number) => {
      const dtReal = Math.min(0.25, (now - last) / 1000);
      last = now;
      const nt = Math.min(endT, tRef.current + dtReal * speed);
      setT(nt);
      if (nt >= endT) {
        setPlaying(false);
        return;
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [playing, speed, endT]);

  useEffect(() => {
    if (t >= endT && !endedRef.current) {
      endedRef.current = true;
      onEnd?.();
    }
  }, [t, endT, onEnd]);

  // Pause when the app goes to the background (Android) — resume is manual.
  useEffect(() => {
    const h = () => document.hidden && setPlaying(false);
    document.addEventListener('visibilitychange', h);
    return () => document.removeEventListener('visibilitychange', h);
  }, []);

  // Derived state at time t.
  const fi = Math.min(replay.frames - 1, Math.floor(t / replay.dt));
  const fj = Math.min(replay.frames - 1, fi + 1);
  const alpha = replay.dt > 0 ? Math.min(1, (t - fi * replay.dt) / replay.dt) : 0;
  const stats = useMemo(() => {
    const k = new Array(10).fill(0);
    const d = new Array(10).fill(0);
    const a = new Array(10).fill(0);
    const feed: MobaReplay['events'] = [];
    for (const e of replay.events) {
      if (e.t > t) break;
      if (e.k === 'kill') {
        if (e.a !== undefined) k[e.a]++;
        if (e.b !== undefined) d[e.b]++;
        for (const x of e.as ?? []) a[x]++;
      }
      if (e.k !== 'cast' && e.k !== 'respawn' && e.k !== 'camp' && e.k !== 'escape') feed.push(e);
    }
    return { k, d, a, feed: feed.slice(-6).reverse() };
  }, [replay, t]);

  // Draw.
  useEffect(() => {
    const cv = canvasRef.current;
    if (!cv) return;
    const ctx = cv.getContext('2d');
    if (!ctx) return;
    const W = cv.width;
    const H = cv.height;
    let { zoom, cx, cy } = view;
    if (follow >= 0) {
      cx = lerp(replay.hx[fi * 10 + follow], replay.hx[fj * 10 + follow], alpha);
      cy = lerp(replay.hy[fi * 10 + follow], replay.hy[fj * 10 + follow], alpha);
      zoom = Math.max(zoom, 2);
    }
    const scale = (Math.min(W, H) / 1000) * zoom;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#10161d';
    ctx.fillRect(0, 0, W, H);
    ctx.setTransform(scale, 0, 0, scale, W / 2 - cx * scale, H / 2 - cy * scale);
    ctx.drawImage(staticLayer(map), 0, 0);
    const fs = 1 / Math.sqrt(zoom); // keep labels readable when zoomed in

    if (opts.paths) {
      ctx.strokeStyle = 'rgba(255,255,200,0.25)';
      ctx.lineWidth = 1.5;
      for (const [a, list] of map.nav.adj.entries())
        for (const [b] of list)
          if (a < b) {
            ctx.beginPath();
            ctx.moveTo(map.nav.nodes[a].x, map.nav.nodes[a].y);
            ctx.lineTo(map.nav.nodes[b].x, map.nav.nodes[b].y);
            ctx.stroke();
          }
    }
    // Wards / vision
    if (opts.vision)
      for (const w of replay.wards) {
        if (w.from > t || w.to < t) continue;
        ctx.fillStyle = w.side ? 'rgba(255,93,93,0.12)' : 'rgba(63,167,255,0.12)';
        ctx.beginPath();
        ctx.arc(w.x, w.y, 115, 0, Math.PI * 2);
        ctx.fill();
        ctx.fillStyle = TEAM_COL[w.side];
        ctx.fillRect(w.x - 3, w.y - 3, 6, 6);
      }
    // Camps
    replay.camps.forEach((_, ci) => {
      const alive = replay.ca[fi * replay.camps.length + ci];
      const c = map.camps[ci];
      ctx.fillStyle = alive ? (c.kind === 'buff' ? '#b98cff' : c.kind === 'river' ? '#7fe6e6' : '#9fcf7f') : 'rgba(255,255,255,0.12)';
      ctx.beginPath();
      ctx.arc(c.pos.x, c.pos.y, c.kind === 'buff' ? 9 : 6, 0, Math.PI * 2);
      ctx.fill();
    });
    // Objectives in pits
    replay.pits.forEach((_, pi) => {
      const o = replay.po[fi * replay.pits.length + pi];
      if (o < 0) return;
      const p = map.pits[pi].pos;
      const hp = replay.ph[fi * replay.pits.length + pi];
      ctx.fillStyle = '#ffd166';
      ctx.beginPath();
      ctx.arc(p.x, p.y, 15, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = '#000';
      ctx.font = `bold ${14 * fs}px sans-serif`;
      ctx.textAlign = 'center';
      ctx.fillText(replay.objectives[o]?.[0] ?? '?', p.x, p.y + 5 * fs);
      bar(ctx, p.x - 18, p.y - 24, 36, 4, hp / 100, '#ffd166');
      if (opts.names) {
        ctx.fillStyle = '#ffd166';
        ctx.font = `${12 * fs}px sans-serif`;
        ctx.fillText(replay.objectives[o], p.x, p.y + 30 * fs);
      }
    });
    // Structures
    replay.structures.forEach((_, si) => {
      const s = map.structures[si];
      const hp = replay.sh[fi * replay.structures.length + si];
      const r = s.kind === 'core' ? 20 : s.kind === 'inhibitor' || s.kind === 'barracks' ? 9 : 11;
      ctx.fillStyle = hp > 0 ? TEAM_DARK[s.team] : 'rgba(0,0,0,0.35)';
      ctx.strokeStyle = hp > 0 ? TEAM_COL[s.team] : 'rgba(255,255,255,0.25)';
      ctx.lineWidth = 2.5;
      ctx.beginPath();
      if (s.kind === 'inhibitor' || s.kind === 'barracks') ctx.rect(s.pos.x - r, s.pos.y - r, r * 2, r * 2);
      else ctx.arc(s.pos.x, s.pos.y, r, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      if (hp > 0 && hp < 100 && opts.health) bar(ctx, s.pos.x - 16, s.pos.y - r - 8, 32, 4, hp / 100, TEAM_COL[s.team]);
      if (hp === 0) {
        ctx.strokeStyle = 'rgba(255,255,255,0.4)';
        ctx.beginPath();
        ctx.moveTo(s.pos.x - r, s.pos.y - r);
        ctx.lineTo(s.pos.x + r, s.pos.y + r);
        ctx.moveTo(s.pos.x + r, s.pos.y - r);
        ctx.lineTo(s.pos.x - r, s.pos.y + r);
        ctx.stroke();
      }
    });
    // Minion waves: front of each side's waves per lane, sized by minion count.
    (['top', 'mid', 'bot'] as const).forEach((lane, li) => {
      for (const side of [0, 1]) {
        const k = fi * 6 + li * 2 + side;
        const front = replay.wf[k];
        const n = replay.wn[k];
        if (front < 0 || n <= 0) continue;
        const k2 = fj * 6 + li * 2 + side;
        const f2 = replay.wf[k2] >= 0 ? replay.wf[k2] : front;
        const s = lerp(front, f2, alpha) / 1000;
        const pos = lanePoint(map, lane, side ? 1 - s : s);
        ctx.fillStyle = TEAM_COL[side];
        ctx.strokeStyle = '#0b0f14';
        ctx.lineWidth = 1.5;
        const dots = Math.min(8, Math.max(1, Math.round(n)));
        for (let i = 0; i < dots; i++) {
          const ang = (i / dots) * Math.PI * 2;
          ctx.beginPath();
          ctx.rect(pos.x + Math.cos(ang) * 8 - 3.5, pos.y + Math.sin(ang) * 8 - 3.5, 7, 7);
          ctx.fill();
          ctx.stroke();
        }
      }
    });
    // Attack lines and heroes.
    for (let h = 0; h < 10; h++) {
      const st = replay.hst[fi * 10 + h];
      if (st & 1) continue;
      const x = lerp(replay.hx[fi * 10 + h], replay.hx[fj * 10 + h], alpha);
      const y = lerp(replay.hy[fi * 10 + h], replay.hy[fj * 10 + h], alpha);
      const tg = replay.htg[fi * 10 + h];
      if (tg >= 0 && tg < 10 && !(replay.hst[fi * 10 + tg] & 1)) {
        const tx = lerp(replay.hx[fi * 10 + tg], replay.hx[fj * 10 + tg], alpha);
        const ty = lerp(replay.hy[fi * 10 + tg], replay.hy[fj * 10 + tg], alpha);
        ctx.strokeStyle = replay.heroes[h].side ? 'rgba(255,93,93,0.8)' : 'rgba(63,167,255,0.8)';
        ctx.lineWidth = 2;
        ctx.setLineDash([4, 4]);
        ctx.beginPath();
        ctx.moveTo(x, y);
        ctx.lineTo(tx, ty);
        ctx.stroke();
        ctx.setLineDash([]);
      } else if (tg >= 100 && tg < 200) {
        const s = map.structures[tg - 100];
        if (s) {
          ctx.strokeStyle = 'rgba(255,200,80,0.6)';
          ctx.lineWidth = 2;
          ctx.beginPath();
          ctx.moveTo(x, y);
          ctx.lineTo(s.pos.x, s.pos.y);
          ctx.stroke();
        }
      }
    }
    for (let h = 0; h < 10; h++) {
      const st = replay.hst[fi * 10 + h];
      if (st & 1) continue;
      const side = replay.heroes[h].side;
      const x = lerp(replay.hx[fi * 10 + h], replay.hx[fj * 10 + h], alpha);
      const y = lerp(replay.hy[fi * 10 + h], replay.hy[fj * 10 + h], alpha);
      const hp = lerp(replay.hhp[fi * 10 + h], replay.hhp[fj * 10 + h] || replay.hhp[fi * 10 + h], alpha);
      const r = 12;
      ctx.fillStyle = TEAM_COL[side];
      ctx.strokeStyle = h === follow ? '#fff' : '#0b0f14';
      ctx.lineWidth = h === follow ? 4 : 2;
      ctx.beginPath();
      ctx.arc(x, y, r, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      if (st & 64) {
        ctx.strokeStyle = '#ffd166';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(x, y, r + 4, 0, Math.PI * 2);
        ctx.stroke();
      }
      ctx.fillStyle = '#0b0f14';
      ctx.font = `bold ${11 * fs}px sans-serif`;
      ctx.textAlign = 'center';
      ctx.fillText(String(replay.hlv[fi * 10 + h]), x, y + 4 * fs);
      if (opts.health) bar(ctx, x - 14, y - r - 7, 28, 4, hp / 100, hp > 50 ? '#3ccf7a' : hp > 25 ? '#ffd166' : '#ff5d5d');
      if (opts.names) {
        ctx.fillStyle = '#fff';
        ctx.font = `${12 * fs}px sans-serif`;
        ctx.fillText(replay.heroes[h].handle, x, y + r + 13 * fs);
      }
    }
    // Recent spatial events: kill markers, casts, structure explosions, objective takes.
    for (const e of replay.events) {
      if (e.t > t) break;
      const age = t - e.t;
      if (age > 4 || e.x === undefined || e.y === undefined) continue;
      const a = 1 - age / 4;
      if (e.k === 'kill') {
        ctx.strokeStyle = `rgba(255,255,255,${a})`;
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.moveTo(e.x - 9, e.y - 9);
        ctx.lineTo(e.x + 9, e.y + 9);
        ctx.moveTo(e.x + 9, e.y - 9);
        ctx.lineTo(e.x - 9, e.y + 9);
        ctx.stroke();
      } else if (e.k === 'cast') {
        ctx.strokeStyle = `rgba(255,240,150,${a})`;
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(e.x, e.y, 18 + age * 12, 0, Math.PI * 2);
        ctx.stroke();
      } else if (e.k === 'structure' || e.k === 'core' || e.k === 'objective' || e.k === 'ace') {
        ctx.fillStyle = e.k === 'objective' ? `rgba(255,209,102,${a * 0.5})` : `rgba(255,140,60,${a * 0.5})`;
        ctx.beginPath();
        ctx.arc(e.x, e.y, 20 + age * 15, 0, Math.PI * 2);
        ctx.fill();
      }
    }
  }, [replay, map, fi, fj, alpha, t, view, follow, opts]);

  // Size the canvas to its container (device pixels, capped for low-end phones).
  useEffect(() => {
    const wrap = wrapRef.current;
    const cv = canvasRef.current;
    if (!wrap || !cv) return;
    const fit = () => {
      const w = wrap.clientWidth;
      const h = Math.min(w, Math.max(260, window.innerHeight * 0.62));
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      cv.width = Math.round(w * dpr);
      cv.height = Math.round(h * dpr);
      cv.style.width = `${w}px`;
      cv.style.height = `${h}px`;
      setView((v) => ({ ...v }));
    };
    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(wrap);
    return () => ro.disconnect();
  }, []);

  // Pan (drag) and zoom (wheel / pinch). Pointer events cover mouse and touch.
  const ptrs = useRef(new Map<number, { x: number; y: number }>());
  const pinch = useRef<{ d: number; zoom: number } | null>(null);
  const onDown = (e: React.PointerEvent) => {
    (e.target as Element).setPointerCapture(e.pointerId);
    ptrs.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (ptrs.current.size === 2) {
      const [a, b] = [...ptrs.current.values()];
      pinch.current = { d: Math.hypot(a.x - b.x, a.y - b.y), zoom: view.zoom };
    }
  };
  const onMove = (e: React.PointerEvent) => {
    const prev = ptrs.current.get(e.pointerId);
    if (!prev) return;
    ptrs.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const cv = canvasRef.current!;
    const scale = (Math.min(cv.clientWidth, cv.clientHeight) / 1000) * view.zoom;
    if (ptrs.current.size === 2 && pinch.current) {
      const [a, b] = [...ptrs.current.values()];
      const d = Math.hypot(a.x - b.x, a.y - b.y);
      setView((v) => ({ ...v, zoom: clampZoom((pinch.current!.zoom * d) / pinch.current!.d) }));
      return;
    }
    if (follow >= 0) setFollow(-1);
    setView((v) => ({ ...v, cx: clampC(v.cx - (e.clientX - prev.x) / scale), cy: clampC(v.cy - (e.clientY - prev.y) / scale) }));
  };
  const onUp = (e: React.PointerEvent) => {
    ptrs.current.delete(e.pointerId);
    if (ptrs.current.size < 2) pinch.current = null;
  };
  const onWheel = (e: React.WheelEvent) => {
    setView((v) => ({ ...v, zoom: clampZoom(v.zoom * (e.deltaY < 0 ? 1.15 : 1 / 1.15)) }));
  };

  const gold = [replay.tg[fi * 2], replay.tg[fi * 2 + 1]];
  const kills = [replay.tk[fi * 2], replay.tk[fi * 2 + 1]];
  const towers = [replay.ts[fi * 2], replay.ts[fi * 2 + 1]];
  const objCount = [0, 0];
  for (const e of replay.events) {
    if (e.t > t) break;
    if (e.k === 'objective' && e.s !== undefined) objCount[e.s]++;
  }
  const atEnd = t >= endT;

  return (
    <div className="moba-viewer" data-testid="moba-viewer">
      <div className="mv-score">
        <span className="mv-team" style={{ color: TEAM_COL[0] }}>{teamNames[0]}</span>
        <span className="mv-nums">
          <b>{kills[0]}</b> – <b>{kills[1]}</b>
        </span>
        <span className="mv-team right" style={{ color: TEAM_COL[1] }}>{teamNames[1]}</span>
        <span className="mv-clock">{fmt(t)}</span>
        <span className="mv-sub small muted">
          Gold {k1(gold[0])} / {k1(gold[1])} · {map.sideNames[0]} {towers[0]} – {towers[1]} {map.sideNames[1]} structures · objectives {objCount[0]}–{objCount[1]}
        </span>
      </div>
      <div ref={wrapRef} className="mv-canvas-wrap">
        <canvas
          ref={canvasRef}
          aria-label={`${map.name} battlefield`}
          onPointerDown={onDown}
          onPointerMove={onMove}
          onPointerUp={onUp}
          onPointerCancel={onUp}
          onWheel={onWheel}
          style={{ touchAction: 'none', display: 'block', borderRadius: 8 }}
        />
      </div>
      <div className="mv-controls row" style={{ flexWrap: 'wrap', gap: 6, marginTop: 6 }}>
        <button className="btn sm" onClick={() => (atEnd ? (setT(0), setPlaying(true)) : setPlaying(!playing))} data-testid="mv-play">
          {atEnd ? 'Replay' : playing ? 'Pause' : 'Play'}
        </button>
        {SPEEDS.map((s) => (
          <button key={s} className={`btn sm ${s === speed ? '' : 'ghost'}`} onClick={() => setSpeed(s)}>
            {s}×
          </button>
        ))}
        <button className="btn sm ghost" onClick={() => setT(endT)} data-testid="mv-skip">
          Instant finish
        </button>
        <button className="btn sm ghost" onClick={() => (setView({ zoom: 1, cx: 500, cy: 500 }), setFollow(-1))}>
          Fit map
        </button>
        <button className="btn sm ghost" onClick={() => setView((v) => ({ ...v, zoom: clampZoom(v.zoom * 1.3) }))} aria-label="Zoom in">
          +
        </button>
        <button className="btn sm ghost" onClick={() => setView((v) => ({ ...v, zoom: clampZoom(v.zoom / 1.3) }))} aria-label="Zoom out">
          −
        </button>
        <label className="small">
          <input type="checkbox" checked={opts.names} onChange={(e) => setOpts({ ...opts, names: e.target.checked })} /> names
        </label>
        <label className="small">
          <input type="checkbox" checked={opts.health} onChange={(e) => setOpts({ ...opts, health: e.target.checked })} /> health
        </label>
        <label className="small">
          <input type="checkbox" checked={opts.vision} onChange={(e) => setOpts({ ...opts, vision: e.target.checked })} /> wards
        </label>
        <label className="small">
          <input type="checkbox" checked={opts.paths} onChange={(e) => setOpts({ ...opts, paths: e.target.checked })} /> paths
        </label>
      </div>
      <input
        type="range"
        min={0}
        max={endT}
        step={1}
        value={Math.round(t)}
        onChange={(e) => setT(Number(e.target.value))}
        aria-label="Match time"
        style={{ width: '100%' }}
      />
      <div className="mv-bottom">
        <table className="table mv-table">
          <thead>
            <tr>
              <th>Player</th>
              <th>Lv</th>
              <th>K/D/A</th>
              <th>HP</th>
              <th>Now</th>
            </tr>
          </thead>
          <tbody>
            {replay.heroes.map((h, i) => {
              const st = replay.hst[fi * 10 + i];
              const dead = st & 1;
              return (
                <tr key={i} onClick={() => setFollow(follow === i ? -1 : i)} style={{ cursor: 'pointer', background: follow === i ? 'var(--panel2)' : undefined }}>
                  <td>
                    <span style={{ color: TEAM_COL[h.side] }}>■</span> {h.handle} <span className="muted small">{h.char}</span>
                  </td>
                  <td>{replay.hlv[fi * 10 + i]}</td>
                  <td>
                    {stats.k[i]}/{stats.d[i]}/{stats.a[i]}
                  </td>
                  <td>{dead ? <span className="muted">dead</span> : `${replay.hhp[fi * 10 + i]}%`}</td>
                  <td className="small muted">{dead ? 'respawning' : ACT_NAMES[(st >> 1) & 15]}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <ul className="mv-feed small">
          {stats.feed.map((e, i) => (
            <li key={i}>
              <span className="muted">{fmt(e.t)}</span> {describe(replay, e, teamNames)}
            </li>
          ))}
        </ul>
      </div>
      <p className="small muted" style={{ margin: '4px 0 0' }}>
        {map.name} · {map.version}. Tap a player to follow; drag to pan, pinch or scroll to zoom. Playback of the recorded simulation — speed and skipping never change the result.
      </p>
    </div>
  );
}

function describe(r: MobaReplay, e: MobaReplay['events'][number], teams: [string, string]): string {
  const h = (i?: number) => (i === undefined ? '' : r.heroes[i]?.handle ?? '');
  switch (e.k) {
    case 'kill':
      return `${e.a !== undefined ? h(e.a) : e.n ?? 'Structure'} killed ${h(e.b)}`;
    case 'structure':
      return `${teams[e.s ?? 0]} destroyed ${e.n}`;
    case 'core':
      return `${teams[e.s ?? 0]} destroyed the ${e.n} — victory`;
    case 'objective':
      return `${teams[e.s ?? 0]} took ${e.n}${e.a !== undefined ? ` (${h(e.a)})` : ''}`;
    case 'spawn':
      return `${e.n} spawned`;
    case 'summon':
      return `${teams[e.s ?? 0]} summoned ${e.n}`;
    case 'ace':
      return `ACE for ${teams[e.s ?? 0]}`;
    case 'buyback':
      return `${h(e.a)} bought back`;
    case 'aegis':
      return `${h(e.a)} revived by the Aegis`;
    case 'recall':
      return `${h(e.a)} recalled`;
    default:
      return e.k;
  }
}

function lanePoint(m: MapDefinition, lane: 'top' | 'mid' | 'bot', f: number) {
  const pts = m.lanes[lane];
  let left = Math.max(0, Math.min(1, f)) * m.laneLen[lane];
  for (let i = 1; i < pts.length; i++) {
    const d = Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y);
    if (left <= d) return { x: pts[i - 1].x + ((pts[i].x - pts[i - 1].x) * left) / d, y: pts[i - 1].y + ((pts[i].y - pts[i - 1].y) * left) / d };
    left -= d;
  }
  return pts[pts.length - 1];
}

function bar(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, f: number, col: string) {
  ctx.fillStyle = 'rgba(0,0,0,0.6)';
  ctx.fillRect(x, y, w, h);
  ctx.fillStyle = col;
  ctx.fillRect(x, y, w * Math.max(0, Math.min(1, f)), h);
}
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;
const clampZoom = (z: number) => Math.max(1, Math.min(5, z));
const clampC = (c: number) => Math.max(0, Math.min(1000, c));
const fmt = (s: number) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
const k1 = (n: number) => `${(n / 1000).toFixed(1)}k`;

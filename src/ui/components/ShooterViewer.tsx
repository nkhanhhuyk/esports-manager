/**
 * Live 2D tactical-shooter viewer (Valorant, CS2). Read-only playback of the recorded round-by-round
 * simulation: positions, health, weapons, spike/bomb, utility and kills. Pausing, speed, round
 * jumps and skipping never change a result.
 */
import { Pic } from './common';
import { weaponPic } from '../pics';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { ShooterReplay } from '../../model/types';
import { shooterMap } from '../../sim/shooter/maps';
import type { ShooterMap } from '../../sim/shooter/map';

const COL = ['#3fa7ff', '#ff5d5d'];
const SPEEDS = [1, 2, 4, 8];

const layerCache = new Map<string, HTMLCanvasElement>();
function mapLayer(m: ShooterMap, labels: boolean): HTMLCanvasElement {
  const k = `${m.id}|${labels}`;
  const hit = layerCache.get(k);
  if (hit) return hit;
  const c = document.createElement('canvas');
  c.width = 1000;
  c.height = 1000;
  const x = c.getContext('2d')!;
  x.fillStyle = '#20252d';
  x.fillRect(0, 0, 1000, 1000);
  // Walkable floor
  x.fillStyle = '#7b7568';
  const n = 1000 / m.cell;
  for (let gy = 0; gy < n; gy++) for (let gx = 0; gx < n; gx++) if (m.grid[gy * n + gx]) x.fillRect(gx * m.cell, gy * m.cell, m.cell, m.cell);
  // Sites
  for (const s of m.sites) {
    x.fillStyle = 'rgba(230,110,60,0.22)';
    x.beginPath();
    x.arc(s.x, s.y, s.r, 0, Math.PI * 2);
    x.fill();
    x.fillStyle = 'rgba(255,170,120,0.9)';
    x.font = 'bold 44px sans-serif';
    x.textAlign = 'center';
    x.fillText(s.id, s.x, s.y + 15);
  }
  // Spawns
  for (const [id, col] of [[m.atkSpawn, 'rgba(255,93,93,0.35)'], [m.defSpawn, 'rgba(63,167,255,0.35)']] as const) {
    const nd = m.nodes[id];
    x.fillStyle = col;
    x.beginPath();
    x.arc(nd.x, nd.y, 40, 0, Math.PI * 2);
    x.fill();
  }
  if (labels) {
    x.fillStyle = 'rgba(255,255,255,0.75)';
    x.font = '15px sans-serif';
    x.textAlign = 'center';
    for (const nd of m.nodes) if (nd.kind === 'area') x.fillText(nd.sup === 'Mid' || /side/i.test(nd.sup) ? nd.name : `${nd.sup} ${nd.name}`, nd.x, nd.y - 8);
  }
  layerCache.set(k, c);
  return c;
}

interface Props {
  replay: ShooterReplay;
  teamNames: [string, string];
  onEnd?: () => void;
  autoplay?: boolean;
}

export function ShooterViewer({ replay, teamNames, onEnd, autoplay = true }: Props) {
  const map = useMemo(() => shooterMap(replay.mapId), [replay.mapId]);
  const cv = useRef<HTMLCanvasElement | null>(null);
  const wrap = useRef<HTMLDivElement | null>(null);
  const endT = (replay.frames - 1) * replay.dt;
  const [t, setT] = useState(0);
  const [playing, setPlaying] = useState(autoplay);
  const [speed, setSpeed] = useState(2);
  const [labels, setLabels] = useState(true);
  const [follow, setFollow] = useState(-1);
  const [zoom, setZoom] = useState(1);
  const tRef = useRef(0);
  const ended = useRef(false);
  tRef.current = t;

  useEffect(() => {
    if (!playing) return;
    let raf = 0;
    let last = performance.now();
    const tick = (now: number) => {
      const d = Math.min(0.25, (now - last) / 1000);
      last = now;
      const nt = Math.min(endT, tRef.current + d * speed);
      setT(nt);
      if (nt >= endT) return setPlaying(false);
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [playing, speed, endT]);
  useEffect(() => {
    if (t >= endT && !ended.current) {
      ended.current = true;
      onEnd?.();
    }
  }, [t, endT, onEnd]);
  useEffect(() => {
    const h = () => document.hidden && setPlaying(false);
    document.addEventListener('visibilitychange', h);
    return () => document.removeEventListener('visibilitychange', h);
  }, []);

  const fi = Math.min(replay.frames - 1, Math.floor(t / replay.dt));
  const fj = Math.min(replay.frames - 1, fi + 1);
  const a = Math.min(1, (t - fi * replay.dt) / replay.dt);
  const roundIdx = replay.fr[fi];
  const rd = replay.rounds.find((r) => r.idx === roundIdx);
  const sameRound = replay.fr[fj] === roundIdx;
  const prevScore = rd ? (replay.rounds.find((r) => r.idx === roundIdx - 1)?.score ?? [0, 0]) : [0, 0];

  const derived = useMemo(() => {
    const k = new Array(10).fill(0);
    const d = new Array(10).fill(0);
    const feed: ShooterReplay['events'] = [];
    for (const e of replay.events) {
      if (e.t > t) break;
      if (e.k === 'kill') {
        if (e.a !== undefined) k[e.a]++;
        if (e.b !== undefined) d[e.b]++;
      }
      if (e.k !== 'round') feed.push(e);
    }
    return { k, d, feed: feed.slice(-7).reverse() };
  }, [replay, t]);

  useEffect(() => {
    const c = cv.current;
    if (!c || !map) return;
    const ctx = c.getContext('2d')!;
    const W = c.width;
    const H = c.height;
    let cx = 500;
    let cy = 500;
    let z = zoom;
    if (follow >= 0) {
      cx = lerp(replay.px[fi * 10 + follow], replay.px[fj * 10 + follow], sameRound ? a : 0);
      cy = lerp(replay.py[fi * 10 + follow], replay.py[fj * 10 + follow], sameRound ? a : 0);
      z = Math.max(z, 2);
    }
    const sc = (Math.min(W, H) / 1000) * z;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#14181e';
    ctx.fillRect(0, 0, W, H);
    ctx.setTransform(sc, 0, 0, sc, W / 2 - cx * sc, H / 2 - cy * sc);
    ctx.drawImage(mapLayer(map, labels), 0, 0);
    const fs = 1 / Math.sqrt(z);
    // Utility active now.
    for (const u of replay.utility) {
      if (u.t > t || u.until < t) continue;
      if (u.kind === 'smoke') {
        ctx.fillStyle = 'rgba(210,210,220,0.78)';
        ctx.beginPath();
        ctx.arc(u.x, u.y, u.r, 0, Math.PI * 2);
        ctx.fill();
      } else if (u.kind === 'molly') {
        ctx.fillStyle = 'rgba(255,120,40,0.55)';
        ctx.beginPath();
        ctx.arc(u.x, u.y, u.r, 0, Math.PI * 2);
        ctx.fill();
      } else if (u.kind === 'flash' && t - u.t < 0.6) {
        ctx.fillStyle = 'rgba(255,255,240,0.6)';
        ctx.beginPath();
        ctx.arc(u.x, u.y, 60, 0, Math.PI * 2);
        ctx.fill();
      } else if (u.kind === 'recon') {
        ctx.strokeStyle = 'rgba(120,255,200,0.6)';
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.arc(u.x, u.y, u.r, 0, Math.PI * 2);
        ctx.stroke();
      } else if (u.kind === 'trap') {
        ctx.fillStyle = COL[u.side];
        ctx.fillRect(u.x - 4, u.y - 4, 8, 8);
      }
    }
    // Spike / bomb.
    const ss = replay.fs[fi];
    if (ss === 1 || ss === 2) {
      ctx.fillStyle = ss === 2 ? '#ff3b3b' : '#ffcc33';
      ctx.beginPath();
      ctx.arc(replay.fx[fi], replay.fy[fi], ss === 2 ? 9 + Math.sin(t * 8) * 2 : 7, 0, Math.PI * 2);
      ctx.fill();
    }
    // Shot lines, then players.
    for (let p = 0; p < 10; p++) {
      const tg = replay.ptg[fi * 10 + p];
      if (tg < 0 || replay.php[fi * 10 + p] <= 0) continue;
      ctx.strokeStyle = replay.players[p].side ? 'rgba(255,93,93,0.85)' : 'rgba(63,167,255,0.85)';
      ctx.lineWidth = 1.8;
      ctx.beginPath();
      ctx.moveTo(replay.px[fi * 10 + p], replay.py[fi * 10 + p]);
      ctx.lineTo(replay.px[fi * 10 + tg], replay.py[fi * 10 + tg]);
      ctx.stroke();
    }
    for (let p = 0; p < 10; p++) {
      const hp = replay.php[fi * 10 + p];
      const x = lerp(replay.px[fi * 10 + p], replay.px[fj * 10 + p], sameRound && replay.php[fj * 10 + p] > 0 ? a : 0);
      const y = lerp(replay.py[fi * 10 + p], replay.py[fj * 10 + p], sameRound && replay.php[fj * 10 + p] > 0 ? a : 0);
      const side = replay.players[p].side;
      if (hp <= 0) {
        ctx.strokeStyle = 'rgba(255,255,255,0.55)';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(x - 6, y - 6);
        ctx.lineTo(x + 6, y + 6);
        ctx.moveTo(x + 6, y - 6);
        ctx.lineTo(x - 6, y + 6);
        ctx.stroke();
        continue;
      }
      const st = replay.pst[fi * 10 + p];
      ctx.fillStyle = COL[side];
      ctx.strokeStyle = p === follow ? '#fff' : '#0b0f14';
      ctx.lineWidth = p === follow ? 3 : 2;
      ctx.beginPath();
      ctx.arc(x, y, 10, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      if (st & 8) {
        ctx.strokeStyle = '#fffbe0';
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.arc(x, y, 14, 0, Math.PI * 2);
        ctx.stroke();
      }
      if (st & 1) {
        ctx.fillStyle = '#ffcc33';
        ctx.fillRect(x + 7, y - 13, 6, 6);
      }
      bar(ctx, x - 12, y - 18, 24, 3.5, hp / 100);
      ctx.fillStyle = '#fff';
      ctx.font = `${11 * fs}px sans-serif`;
      ctx.textAlign = 'center';
      ctx.fillText(replay.players[p].handle, x, y + 22 * fs);
      ctx.fillStyle = 'rgba(255,255,255,0.7)';
      ctx.font = `${9.5 * fs}px sans-serif`;
      ctx.fillText(replay.weapons[replay.pwp[fi * 10 + p]] ?? '', x, y + 33 * fs);
      if (st & 2 || st & 4) {
        ctx.strokeStyle = '#ffcc33';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(x, y, 16, -Math.PI / 2, -Math.PI / 2 + ((t * 2) % (Math.PI * 2)));
        ctx.stroke();
      }
    }
    // Recent kills.
    for (const e of replay.events) {
      if (e.t > t) break;
      if (e.k !== 'kill' || t - e.t > 2.5 || e.x === undefined) continue;
      ctx.fillStyle = `rgba(255,80,80,${1 - (t - e.t) / 2.5})`;
      ctx.beginPath();
      ctx.arc(e.x!, e.y!, 16, 0, Math.PI * 2);
      ctx.fill();
    }
  }, [replay, map, fi, fj, a, t, labels, follow, zoom, sameRound]);

  useEffect(() => {
    const w = wrap.current;
    const c = cv.current;
    if (!w || !c) return;
    const fit = () => {
      const width = w.clientWidth;
      const h = Math.min(width, Math.max(260, window.innerHeight * 0.62));
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      c.width = Math.round(width * dpr);
      c.height = Math.round(h * dpr);
      c.style.width = `${width}px`;
      c.style.height = `${h}px`;
      setZoom((z) => z);
    };
    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(w);
    return () => ro.disconnect();
  }, []);

  if (!map) return <div className="muted">Map data unavailable for {replay.mapId}.</div>;
  const alive = [0, 0];
  for (let p = 0; p < 10; p++) if (replay.php[fi * 10 + p] > 0) alive[replay.players[p].side]++;
  const timer = replay.ft[fi];
  const spikeState = replay.fs[fi];
  const jumpRound = (r: ShooterReplay['rounds'][number]) => {
    setT(r.from * replay.dt);
    ended.current = t >= endT;
  };
  return (
    <div className="moba-viewer" data-testid="shooter-viewer">
      <div className="mv-score">
        <span className="mv-team" style={{ color: COL[0] }}>{teamNames[0]}</span>
        <span className="mv-nums">
          <b>{prevScore[0]}</b> – <b>{prevScore[1]}</b>
        </span>
        <span className="mv-team right" style={{ color: COL[1] }}>{teamNames[1]}</span>
        <span className="mv-clock">
          {map.name} · Round {roundIdx + 1} · {spikeState === 2 ? `💣 ${timer}s` : `${Math.floor(timer / 60)}:${String(Math.max(0, timer % 60)).padStart(2, '0')}`}
        </span>
        <span className="mv-sub small muted">
          Alive {alive[0]} v {alive[1]} · {rd ? `${teamNames[rd.attack]} attacking · buys ${rd.buys[0]} / ${rd.buys[1]} · credits ${(rd.money[0] / 1000).toFixed(1)}k / ${(rd.money[1] / 1000).toFixed(1)}k` : ''}
        </span>
      </div>
      <div ref={wrap}>
        <canvas ref={cv} aria-label={`${map.name} map`} style={{ display: 'block', borderRadius: 8, touchAction: 'none' }} onWheel={(e) => setZoom((z) => Math.max(1, Math.min(4, z * (e.deltaY < 0 ? 1.15 : 1 / 1.15))))} />
      </div>
      <div className="row" style={{ flexWrap: 'wrap', gap: 6, marginTop: 6 }}>
        <button className="btn sm" onClick={() => (t >= endT ? (setT(0), setPlaying(true)) : setPlaying(!playing))} data-testid="sv-play">
          {t >= endT ? 'Replay' : playing ? 'Pause' : 'Play'}
        </button>
        {SPEEDS.map((s) => (
          <button key={s} className={`btn sm ${s === speed ? '' : 'ghost'}`} onClick={() => setSpeed(s)}>
            {s}×
          </button>
        ))}
        <button className="btn sm ghost" onClick={() => rd && jumpRound(replay.rounds.find((r) => r.idx === roundIdx + 1) ?? rd)}>
          Next round
        </button>
        <button className="btn sm ghost" onClick={() => setT(endT)} data-testid="mv-skip">
          Instant finish
        </button>
        <button className="btn sm ghost" onClick={() => (setZoom(1), setFollow(-1))}>
          Fit map
        </button>
        <button className="btn sm ghost" onClick={() => setZoom((z) => Math.min(4, z * 1.3))} aria-label="Zoom in">
          +
        </button>
        <button className="btn sm ghost" onClick={() => setZoom((z) => Math.max(1, z / 1.3))} aria-label="Zoom out">
          −
        </button>
        <label className="small">
          <input type="checkbox" checked={labels} onChange={(e) => setLabels(e.target.checked)} /> callouts
        </label>
      </div>
      <div className="row" style={{ flexWrap: 'wrap', gap: 3, margin: '6px 0' }} aria-label="Round history">
        {/* Only finished rounds show their winner (no spoilers); the rest are numbered. */}
        {replay.rounds.filter((r) => r.idx <= roundIdx).map((r) => {
          const done = r.to < fi || (r.idx < roundIdx);
          return (
            <button
              key={r.idx}
              className="btn sm ghost"
              title={done ? `Round ${r.idx + 1}: ${teamNames[r.winner]} (${r.reason}${r.site ? `, ${r.site}` : ''})` : `Round ${r.idx + 1}`}
              onClick={() => jumpRound(r)}
              style={{ minWidth: 22, padding: '1px 4px', borderColor: done ? COL[r.winner] : undefined, color: r.idx === roundIdx ? '#fff' : done ? COL[r.winner] : undefined, background: r.idx === roundIdx ? (done ? COL[r.winner] : 'var(--panel2)') : undefined }}
            >
              {!done ? r.idx + 1 : r.reason === 'spike' ? '💣' : r.reason === 'defuse' ? '✂' : r.reason === 'time' ? '⏱' : '•'}
            </button>
          );
        })}
      </div>
      <div className="mv-bottom">
        <table className="table mv-table">
          <thead>
            <tr>
              <th>Player</th>
              <th>K</th>
              <th>D</th>
              <th>HP</th>
              <th>Weapon</th>
            </tr>
          </thead>
          <tbody>
            {replay.players.map((p, i) => (
              <tr key={i} onClick={() => setFollow(follow === i ? -1 : i)} style={{ cursor: 'pointer', background: follow === i ? 'var(--panel2)' : undefined }}>
                <td>
                  <span style={{ color: COL[p.side] }}>■</span> {p.handle} <span className="muted small">{p.char}</span>
                </td>
                <td>{derived.k[i]}</td>
                <td>{derived.d[i]}</td>
                <td>{replay.php[fi * 10 + i] > 0 ? replay.php[fi * 10 + i] : <span className="muted">dead</span>}</td>
                <td className="small"><Pic src={weaponPic(replay.titleId, replay.weapons[replay.pwp[fi * 10 + i]])} size={16} /> {replay.weapons[replay.pwp[fi * 10 + i]]}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <ul className="mv-feed small">
          {derived.feed.map((e, i) => (
            <li key={i}>
              {e.k === 'kill' ? (
                <>
                  <span style={{ color: COL[replay.players[e.a!]?.side ?? 0] }}>{replay.players[e.a!]?.handle}</span> <Pic src={weaponPic(replay.titleId, e.n)} size={16} alt={e.n} title={e.n} />[{e.n}
                  {e.hs ? ' ⦿' : ''}] <span style={{ color: COL[replay.players[e.b!]?.side ?? 1] }}>{replay.players[e.b!]?.handle}</span>
                </>
              ) : e.k === 'plant' ? (
                <>{replay.players[e.a!]?.handle} planted on {e.n}</>
              ) : e.k === 'defuse' ? (
                <>{replay.players[e.a!]?.handle} defused</>
              ) : e.k === 'explode' ? (
                <>detonation</>
              ) : (
                e.k
              )}
            </li>
          ))}
        </ul>
      </div>
      <p className="small muted" style={{ margin: '4px 0 0' }}>
        {map.name} ({map.version}). Smokes block sight, walls block movement and sight. Tap a player to follow. Playback of the recorded simulation — speed and skipping never change the result.
      </p>
    </div>
  );
}

function bar(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, f: number) {
  ctx.fillStyle = 'rgba(0,0,0,0.6)';
  ctx.fillRect(x, y, w, h);
  ctx.fillStyle = f > 0.5 ? '#3ccf7a' : f > 0.25 ? '#ffd166' : '#ff5d5d';
  ctx.fillRect(x, y, w * Math.max(0, Math.min(1, f)), h);
}
const lerp = (x: number, y: number, k: number) => x + (y - x) * k;

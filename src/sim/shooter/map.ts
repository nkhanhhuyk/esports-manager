/**
 * Tactical-shooter battlefield definitions (Valorant, CS2).
 *
 * Normalised 1000 × 1000 square, attackers (Valorant Attack / CS2 T) spawn at the bottom, defenders at the
 * top. A map is a graph of named callout nodes (spawns, mains, mid, sites…) joined by walkable connections.
 * Impassable geometry is derived from those corridors on a 10-unit grid, and line of sight is tested
 * against the same grid (plus active smokes), so movement, sight and the drawn map always agree.
 */

export interface SPt {
  x: number;
  y: number;
}

export interface ShooterNode {
  id: number;
  name: string;
  /** Super-region (A, B, C, Mid, Attacker Side, Defender Side…). */
  sup: string;
  x: number;
  y: number;
  kind: 'atk-spawn' | 'def-spawn' | 'site' | 'area';
  site?: string;
}

export interface ShooterMapSpec {
  id: string;
  titleId: 'valorant' | 'cs2';
  name: string;
  version: string;
  sources: string[];
  notes: string[];
  nodes: Array<{ name: string; sup: string; x: number; y: number }>;
  /** Explicit connections (node names "sup/name"); when absent they are inferred from positions. */
  edges?: Array<[string, string]>;
  /** Corridor half-width and site radius in map units. */
  corridor?: number;
  siteRadius?: number;
}

export interface ShooterMap {
  id: string;
  titleId: 'valorant' | 'cs2';
  name: string;
  version: string;
  sources: string[];
  notes: string[];
  nodes: ShooterNode[];
  edges: Array<[number, number]>;
  adj: number[][];
  dist: Float32Array;
  next: Int16Array;
  sites: Array<{ id: string; node: number; x: number; y: number; r: number }>;
  atkSpawn: number;
  defSpawn: number;
  cell: number;
  grid: Uint8Array;
  walls: Array<[number, number, number, number]>;
  inferredEdges: boolean;
}

const CELL = 10;
const N = 1000 / CELL;
const d2 = (a: SPt, b: SPt) => (a.x - b.x) ** 2 + (a.y - b.y) ** 2;
export const sdist = (a: SPt, b: SPt) => Math.sqrt(d2(a, b));

function segDist(p: SPt, a: SPt, b: SPt): number {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const L = dx * dx + dy * dy;
  const t = L > 0 ? Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / L)) : 0;
  return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy));
}

export function compileShooterMap(spec: ShooterMapSpec): ShooterMap {
  const nodes: ShooterNode[] = spec.nodes.map((n, id) => {
    const atk = /attacker|^t side|t spawn/i.test(n.sup) && /spawn/i.test(n.name);
    const def = /defender|ct/i.test(n.sup) && /spawn/i.test(n.name);
    const site = /^site$/i.test(n.name) || /site$/i.test(n.name) ? n.sup.replace(/ .*/, '') : undefined;
    return { id, name: n.name, sup: n.sup, x: n.x, y: n.y, kind: atk ? 'atk-spawn' : def ? 'def-spawn' : site ? 'site' : 'area', site };
  });
  const key = (n: ShooterNode) => `${n.sup}/${n.name}`;
  const byKey = new Map(nodes.map((n) => [key(n), n.id]));
  let edges: Array<[number, number]> = [];
  const inferred = !spec.edges;
  if (spec.edges) {
    for (const [a, b] of spec.edges) {
      const ia = byKey.get(a);
      const ib = byKey.get(b);
      if (ia === undefined || ib === undefined) throw new Error(`${spec.id}: unknown node in edge ${a}–${b}`);
      edges.push([ia, ib]);
    }
  } else {
    // Relative neighbourhood graph: u–v is a corridor unless some w is closer to both of them.
    const maxLen = 330;
    for (let u = 0; u < nodes.length; u++)
      for (let v = u + 1; v < nodes.length; v++) {
        const duv = d2(nodes[u], nodes[v]);
        if (duv > maxLen * maxLen) continue;
        let blocked = false;
        for (let w = 0; w < nodes.length && !blocked; w++) if (w !== u && w !== v && Math.max(d2(nodes[u], nodes[w]), d2(nodes[v], nodes[w])) < duv) blocked = true;
        if (!blocked) edges.push([u, v]);
      }
  }
  // Inferred maps: every site gets a connection toward the defender side (rotations from defender spawn).
  if (!spec.edges) {
    const defIdx = nodes.findIndex((x) => /defender|ct/i.test(x.sup) && /spawn/i.test(x.name));
    for (const st of nodes.filter((x) => /^site$/i.test(x.name))) {
      if (defIdx < 0) break;
      const dSite = d2(st, nodes[defIdx]);
      const has = edges.some(([a, b]) => (a === st.id || b === st.id) && d2(nodes[a === st.id ? b : a], nodes[defIdx]) < dSite);
      if (has) continue;
      let best = -1;
      let bd = 280 * 280;
      for (const w of nodes) if (w.id !== st.id && d2(w, nodes[defIdx]) < dSite && d2(w, st) < bd) {
        bd = d2(w, st);
        best = w.id;
      }
      if (best >= 0) edges.push([st.id, best]);
    }
  }
  // Connectivity safeguard: join components by their closest pair.
  const comp = () => {
    const c = new Array(nodes.length).fill(-1);
    let k = 0;
    for (let s = 0; s < nodes.length; s++) {
      if (c[s] >= 0) continue;
      const st = [s];
      c[s] = k;
      while (st.length) {
        const u = st.pop()!;
        for (const [a, b] of edges) {
          const v = a === u ? b : b === u ? a : -1;
          if (v >= 0 && c[v] < 0) {
            c[v] = k;
            st.push(v);
          }
        }
      }
      k++;
    }
    return { c, k };
  };
  for (let guard = 0; guard < 20; guard++) {
    const { c, k } = comp();
    if (k <= 1) break;
    let best: [number, number] | null = null;
    let bd = Infinity;
    for (let u = 0; u < nodes.length; u++) for (let v = 0; v < nodes.length; v++) if (c[u] === 0 && c[v] !== 0 && d2(nodes[u], nodes[v]) < bd) {
      bd = d2(nodes[u], nodes[v]);
      best = [u, v];
    }
    if (best) edges.push(best);
  }
  edges = edges.filter(([a, b], i) => edges.findIndex(([x, y]) => (x === a && y === b) || (x === b && y === a)) === i);
  const adj: number[][] = nodes.map(() => []);
  for (const [a, b] of edges) {
    adj[a].push(b);
    adj[b].push(a);
  }
  // All-pairs shortest paths (Floyd–Warshall; ≤ 40 nodes).
  const n = nodes.length;
  const D = new Float32Array(n * n).fill(Infinity);
  const NX = new Int16Array(n * n).fill(-1);
  for (let i = 0; i < n; i++) {
    D[i * n + i] = 0;
    NX[i * n + i] = i;
  }
  for (const [a, b] of edges) {
    const w = sdist(nodes[a], nodes[b]);
    D[a * n + b] = D[b * n + a] = w;
    NX[a * n + b] = b;
    NX[b * n + a] = a;
  }
  for (let k = 0; k < n; k++)
    for (let i = 0; i < n; i++)
      for (let j = 0; j < n; j++) {
        const v = D[i * n + k] + D[k * n + j];
        if (v < D[i * n + j]) {
          D[i * n + j] = v;
          NX[i * n + j] = NX[i * n + k];
        }
      }
  // Terrain.
  const corridor = spec.corridor ?? 26;
  const siteR = spec.siteRadius ?? 62;
  const grid = new Uint8Array(N * N);
  for (let gy = 0; gy < N; gy++)
    for (let gx = 0; gx < N; gx++) {
      const p = { x: gx * CELL + CELL / 2, y: gy * CELL + CELL / 2 };
      let ok = false;
      for (const nd of nodes) if (d2(p, nd) <= (nd.kind === 'site' ? siteR : nd.kind === 'area' ? corridor + 14 : 55) ** 2) {
        ok = true;
        break;
      }
      if (!ok) for (const [a, b] of edges) if (segDist(p, nodes[a], nodes[b]) <= corridor) {
        ok = true;
        break;
      }
      grid[gy * N + gx] = ok ? 1 : 0;
    }
  const walls: Array<[number, number, number, number]> = [];
  for (let gy = 0; gy < N; gy++) {
    let gx = 0;
    while (gx < N) {
      if (grid[gy * N + gx]) {
        gx++;
        continue;
      }
      const s = gx;
      while (gx < N && !grid[gy * N + gx]) gx++;
      const last = walls[walls.length - 1];
      walls.push([s * CELL, gy * CELL, (gx - s) * CELL, CELL]);
      void last;
    }
  }
  const atkSpawn = nodes.find((x) => x.kind === 'atk-spawn')?.id ?? 0;
  const defSpawn = nodes.find((x) => x.kind === 'def-spawn')?.id ?? 1;
  const sites = nodes.filter((x) => x.kind === 'site').map((x) => ({ id: x.site!, node: x.id, x: x.x, y: x.y, r: siteR }));
  return { id: spec.id, titleId: spec.titleId, name: spec.name, version: spec.version, sources: spec.sources, notes: spec.notes, nodes, edges, adj, dist: D, next: NX, sites, atkSpawn, defSpawn, cell: CELL, grid, walls, inferredEdges: inferred };
}

export function walkableS(m: ShooterMap, p: SPt): boolean {
  const gx = Math.floor(p.x / CELL);
  const gy = Math.floor(p.y / CELL);
  return gx >= 0 && gy >= 0 && gx < N && gy < N && m.grid[gy * N + gx] === 1;
}

/** Line of sight: no wall cell and no active smoke between a and b. */
export function los(m: ShooterMap, a: SPt, b: SPt, smokes: Array<{ x: number; y: number; r: number }>): boolean {
  const d = sdist(a, b);
  const steps = Math.max(2, Math.ceil(d / (CELL * 0.8)));
  for (let i = 1; i < steps; i++) {
    const t = i / steps;
    const x = a.x + (b.x - a.x) * t;
    const y = a.y + (b.y - a.y) * t;
    const gx = Math.floor(x / CELL);
    const gy = Math.floor(y / CELL);
    if (gx < 0 || gy < 0 || gx >= N || gy >= N || !m.grid[gy * N + gx]) return false;
  }
  for (const s of smokes) if (segDist(s, a, b) < s.r) return false;
  return true;
}

export function routeLen(m: ShooterMap, a: number, b: number): number {
  return m.dist[a * m.nodes.length + b];
}
export function hop(m: ShooterMap, a: number, b: number): number {
  return m.next[a * m.nodes.length + b];
}

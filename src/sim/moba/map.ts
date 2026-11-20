/**
 * MOBA battlefield definitions (`MapDefinition`) and the navigation they imply.
 *
 * Coordinates are normalised to a 1000 × 1000 square, x to the right and y downwards, with team 0
 * (Blue / Radiant) based bottom-left and team 1 (Red / Dire) top-right — the orientation all five
 * games show in their minimaps. Each map is authored as a `MapSpec` (lanes, structures, camps,
 * objective pits, river and jungle paths for team 0; team 1's half is the point reflection) and
 * compiled once into:
 *  - a waypoint graph (lanes + river + jungle paths) with all-pairs shortest routes, so units move
 *    only along walkable corridors;
 *  - terrain walls: every grid cell farther than its corridor width from any walkable path, base,
 *    camp or pit is impassable jungle wall. Walls are derived from the same geometry the units walk
 *    on, so the drawn map and the simulation cannot disagree.
 */

export interface Pt {
  x: number;
  y: number;
}
export type LaneId = 'top' | 'mid' | 'bot';
export const LANES: LaneId[] = ['top', 'mid', 'bot'];

export type StructureKind = 'tower' | 'inhibitor' | 'barracks' | 'base-tower' | 'core';

export interface StructureDef {
  id: string;
  team: 0 | 1;
  kind: StructureKind;
  lane: LaneId | null;
  tier: number;
  name: string;
  pos: Pt;
  hp: number;
  /** Attack damage per second against units in range (0 = does not attack). */
  dps: number;
  range: number;
  /** Structure ids that must be destroyed before this one can be damaged. */
  requires: string[];
  /** Seconds after destruction until it rebuilds (LoL/Wild Rift inhibitors). */
  respawn?: number;
}

export interface CampDef {
  id: string;
  team: 0 | 1 | -1;
  name: string;
  kind: 'buff' | 'camp' | 'river';
  pos: Pt;
  hp: number;
  gold: number;
  xp: number;
  spawn: number; // seconds
  respawn: number; // seconds
}

export interface PitDef {
  id: string;
  name: string;
  pos: Pt;
  radius: number;
  /** Title objective ids that can appear in this pit. */
  objectives: string[];
}

export interface MapSpec {
  id: string;
  titleId: string;
  name: string;
  version: string;
  sideNames: [string, string];
  orientation: string;
  sources: string[];
  notes: string[];
  /** Fraction of real time a unit needs to cross the map (normalised speed scale). */
  moveSpeed: number;
  laneNames: Record<LaneId, string>;
  /** Lanes from team 0's base to team 1's base. `bot` and the second half of `mid` are mirrored if omitted. */
  lanes: { top: Pt[]; mid: Pt[] };
  bases: Pt; // team 0 core
  fountain: Pt; // team 0 fountain
  /** Team 0 structures; team 1 gets the reflection (top ↔ bot lanes). */
  structures: Array<Omit<StructureDef, 'team' | 'requires'> & { requires?: string[] }>;
  camps: Array<Omit<CampDef, 'team'>>;
  /** Shared neutral camps (river creatures) — authored as a team-0-side list, reflected. */
  riverCamps: Array<Omit<CampDef, 'team'>>;
  pits: PitDef[];
  /** River centreline (top-left → bottom-right). */
  river: Pt[];
  /** Team 0 jungle and base paths; reflected for team 1. */
  paths: Pt[][];
  /** Extra shared paths authored explicitly (not reflected). */
  sharedPaths?: Pt[][];
  /** Corridor half-widths for terrain generation. */
  widths: { lane: number; path: number; river: number; base: number };
  /** Optional drawn areas (Dota high ground, Wild Rift brush …), team 0 side, reflected. */
  areas?: Array<{ kind: 'highground' | 'brush' | 'pool'; poly: Pt[] }>;
}

export interface NavGraph {
  nodes: Pt[];
  /** adjacency list: node → [neighbour, length][] */
  adj: Array<Array<[number, number]>>;
  /** all-pairs distance (row-major N×N) */
  dist: Float32Array;
  /** next hop from i towards j (row-major N×N) */
  next: Int16Array;
}

export interface MapDefinition {
  id: string;
  titleId: string;
  name: string;
  version: string;
  sideNames: [string, string];
  orientation: string;
  sources: string[];
  notes: string[];
  bounds: { w: number; h: number };
  moveSpeed: number;
  laneNames: Record<LaneId, string>;
  lanes: Record<LaneId, Pt[]>;
  laneLen: Record<LaneId, number>;
  cores: [Pt, Pt];
  fountains: [Pt, Pt];
  structures: StructureDef[];
  camps: CampDef[];
  pits: PitDef[];
  river: Pt[];
  areas: Array<{ kind: 'highground' | 'brush' | 'pool'; team: 0 | 1; poly: Pt[] }>;
  nav: NavGraph;
  /** Impassable terrain as merged grid rectangles [x, y, w, h]. */
  walls: Array<[number, number, number, number]>;
  /** Walkable grid (cell size `cell`), 1 = walkable. */
  grid: Uint8Array;
  cell: number;
  /** Node id nearest to each team's fountain. */
  fountainNode: [number, number];
}

// ---------------------------------------------------------------------------------------------
// Geometry helpers
// ---------------------------------------------------------------------------------------------

export const mirror = (p: Pt): Pt => ({ x: 1000 - p.x, y: 1000 - p.y });
export const dist = (a: Pt, b: Pt): number => Math.hypot(a.x - b.x, a.y - b.y);
const swapLane = (l: LaneId | null): LaneId | null => (l === 'top' ? 'bot' : l === 'bot' ? 'top' : l);

export function segDist(p: Pt, a: Pt, b: Pt): number {
  const dx = b.x - a.x;
  const dy = b.y - a.y;
  const L = dx * dx + dy * dy;
  const t = L > 0 ? Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / L)) : 0;
  return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy));
}

export function polyLength(pts: Pt[]): number {
  let s = 0;
  for (let i = 1; i < pts.length; i++) s += dist(pts[i - 1], pts[i]);
  return s;
}

/** Point at arc-length fraction `f` (0..1) along a polyline. */
export function along(pts: Pt[], f: number, len = polyLength(pts)): Pt {
  let left = Math.max(0, Math.min(1, f)) * len;
  for (let i = 1; i < pts.length; i++) {
    const d = dist(pts[i - 1], pts[i]);
    if (left <= d || i === pts.length - 1) {
      const t = d > 0 ? Math.min(1, left / d) : 0;
      return { x: pts[i - 1].x + (pts[i].x - pts[i - 1].x) * t, y: pts[i - 1].y + (pts[i].y - pts[i - 1].y) * t };
    }
    left -= d;
  }
  return pts[pts.length - 1];
}

/** Arc-length fraction of the point of a polyline nearest to `p`. */
export function project(pts: Pt[], p: Pt, len = polyLength(pts)): number {
  let best = Infinity;
  let bestS = 0;
  let acc = 0;
  for (let i = 1; i < pts.length; i++) {
    const a = pts[i - 1];
    const b = pts[i];
    const d = dist(a, b);
    const dx = b.x - a.x;
    const dy = b.y - a.y;
    const t = d > 0 ? Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / (d * d))) : 0;
    const q = { x: a.x + t * dx, y: a.y + t * dy };
    const e = dist(p, q);
    if (e < best) {
      best = e;
      bestS = acc + t * d;
    }
    acc += d;
  }
  return len > 0 ? bestS / len : 0;
}

function segmentsCross(a: Pt, b: Pt, c: Pt, d: Pt): boolean {
  const o = (p: Pt, q: Pt, r: Pt) => Math.sign((q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y));
  return o(a, b, c) !== o(a, b, d) && o(c, d, a) !== o(c, d, b);
}

// ---------------------------------------------------------------------------------------------
// Compilation
// ---------------------------------------------------------------------------------------------

const MAX_EDGE = 60;
const CELL = 10;

export function compileMap(spec: MapSpec): MapDefinition {
  const R = mirror;
  const top = spec.lanes.top;
  const mid = spec.lanes.mid;
  const lanes: Record<LaneId, Pt[]> = {
    top,
    mid,
    // Team 0's bottom lane is the reflection of team 1's top lane: reverse(reflect(top)).
    bot: [...top].reverse().map(R),
  };
  const laneLen = { top: polyLength(lanes.top), mid: polyLength(lanes.mid), bot: polyLength(lanes.bot) };

  // Structures: team 0 as authored, team 1 reflected with top/bot swapped.
  const structures: StructureDef[] = [];
  const pre = (side: 'b' | 'r', r: string) => (r.startsWith('any:') ? r : `${side}-${r}`);
  for (const s of spec.structures) structures.push({ ...s, team: 0, id: `b-${s.id}`, requires: (s.requires ?? []).map((r) => pre('b', r)) });
  for (const s of spec.structures) {
    const lane = swapLane(s.lane);
    const id = `r-${s.id.replace(/^top/, 'TMP').replace(/^bot/, 'top').replace(/^TMP/, 'bot')}`;
    const req = (s.requires ?? []).map((r) => pre('r', r.replace(/^top/, 'TMP').replace(/^bot/, 'top').replace(/^TMP/, 'bot')));
    structures.push({ ...s, team: 1, lane, id, pos: R(s.pos), requires: req });
  }
  const camps: CampDef[] = [];
  for (const c of spec.camps) {
    camps.push({ ...c, team: 0, id: `b-${c.id}` });
    camps.push({ ...c, team: 1, id: `r-${c.id}`, pos: R(c.pos) });
  }
  spec.riverCamps.forEach((c, i) => {
    camps.push({ ...c, team: -1, id: `n-${c.id}-a` });
    camps.push({ ...c, team: -1, id: `n-${c.id}-b`, pos: R(c.pos), name: c.name });
    void i;
  });

  // Walkable polylines.
  const polys: Pt[][] = [lanes.top, lanes.mid, lanes.bot, spec.river];
  for (const p of spec.paths) {
    polys.push(p);
    polys.push(p.map(R));
  }
  for (const p of spec.sharedPaths ?? []) polys.push(p);
  // Bases: connect fountain to each lane start.
  const fountains: [Pt, Pt] = [spec.fountain, R(spec.fountain)];
  const cores: [Pt, Pt] = [spec.bases, R(spec.bases)];
  for (const side of [0, 1] as const) {
    const f = fountains[side];
    const c = cores[side];
    polys.push([f, c]);
    for (const l of [lanes.top, lanes.mid, lanes.bot]) polys.push([c, side === 0 ? l[0] : l[l.length - 1]]);
  }
  const nav = buildNav(polys);

  // Terrain: walkable where close to any corridor.
  const n = 1000 / CELL;
  const grid = new Uint8Array(n * n);
  const corridors: Array<{ pts: Pt[]; w: number }> = [
    ...[lanes.top, lanes.mid, lanes.bot].map((pts) => ({ pts, w: spec.widths.lane })),
    { pts: spec.river, w: spec.widths.river },
    ...polys.slice(4).map((pts) => ({ pts, w: spec.widths.path })),
  ];
  const blobs: Array<{ p: Pt; r: number }> = [
    ...cores.map((p) => ({ p, r: spec.widths.base })),
    ...fountains.map((p) => ({ p, r: spec.widths.base * 0.7 })),
    ...camps.map((c) => ({ p: c.pos, r: spec.widths.path + 8 })),
    ...spec.pits.flatMap((pt) => [{ p: pt.pos, r: pt.radius + 10 }]),
  ];
  for (let gy = 0; gy < n; gy++) {
    for (let gx = 0; gx < n; gx++) {
      const p = { x: gx * CELL + CELL / 2, y: gy * CELL + CELL / 2 };
      let ok = false;
      for (const b of blobs) if (dist(p, b.p) <= b.r) {
        ok = true;
        break;
      }
      if (!ok)
        outer: for (const c of corridors) {
          for (let i = 1; i < c.pts.length; i++)
            if (segDist(p, c.pts[i - 1], c.pts[i]) <= c.w) {
              ok = true;
              break outer;
            }
        }
      grid[gy * n + gx] = ok ? 1 : 0;
    }
  }
  // Merge blocked cells into rectangles (row runs, then vertical merge of identical runs).
  const walls: Array<[number, number, number, number]> = [];
  const open = new Map<string, number>(); // run key → wall index
  for (let gy = 0; gy < n; gy++) {
    const runs: Array<[number, number]> = [];
    let gx = 0;
    while (gx < n) {
      if (grid[gy * n + gx]) {
        gx++;
        continue;
      }
      const s = gx;
      while (gx < n && !grid[gy * n + gx]) gx++;
      runs.push([s, gx]);
    }
    const next = new Map<string, number>();
    for (const [a, b] of runs) {
      const k = `${a}:${b}`;
      const wi = open.get(k);
      if (wi !== undefined) {
        walls[wi][3] += CELL;
        next.set(k, wi);
      } else {
        walls.push([a * CELL, gy * CELL, (b - a) * CELL, CELL]);
        next.set(k, walls.length - 1);
      }
    }
    open.clear();
    for (const [k, v] of next) open.set(k, v);
  }

  const areas = (spec.areas ?? []).flatMap((a) => [
    { kind: a.kind, team: 0 as const, poly: a.poly },
    { kind: a.kind, team: 1 as const, poly: a.poly.map(R) },
  ]);
  return {
    id: spec.id,
    titleId: spec.titleId,
    name: spec.name,
    version: spec.version,
    sideNames: spec.sideNames,
    orientation: spec.orientation,
    sources: spec.sources,
    notes: spec.notes,
    bounds: { w: 1000, h: 1000 },
    moveSpeed: spec.moveSpeed,
    laneNames: spec.laneNames,
    lanes,
    laneLen,
    cores,
    fountains,
    structures,
    camps,
    pits: spec.pits,
    river: spec.river,
    areas,
    nav,
    walls,
    grid,
    cell: CELL,
    fountainNode: [nearestNode(nav, fountains[0]), nearestNode(nav, fountains[1])],
  };
}

/** Build the waypoint graph: split long edges, merge coincident points, attach path ends to the network. */
function buildNav(polys: Pt[][]): NavGraph {
  const nodes: Pt[] = [];
  const adj: Array<Array<[number, number]>> = [];
  const key = (p: Pt) => `${Math.round(p.x)}:${Math.round(p.y)}`;
  const index = new Map<string, number>();
  const nodeAt = (p: Pt): number => {
    const k = key(p);
    let i = index.get(k);
    if (i === undefined) {
      // Merge with any node within 4 units.
      for (let j = 0; j < nodes.length; j++)
        if (dist(nodes[j], p) < 4) {
          index.set(k, j);
          return j;
        }
      i = nodes.length;
      nodes.push({ x: Math.round(p.x), y: Math.round(p.y) });
      adj.push([]);
      index.set(k, i);
    }
    return i;
  };
  const link = (a: number, b: number) => {
    if (a === b) return;
    const d = dist(nodes[a], nodes[b]);
    if (!adj[a].some(([x]) => x === b)) adj[a].push([b, d]);
    if (!adj[b].some(([x]) => x === a)) adj[b].push([a, d]);
  };
  const edges: Array<[number, number]> = [];
  for (const poly of polys) {
    let prev = -1;
    for (let i = 0; i < poly.length; i++) {
      if (i > 0) {
        const a = poly[i - 1];
        const b = poly[i];
        const parts = Math.max(1, Math.ceil(dist(a, b) / MAX_EDGE));
        for (let k = 1; k <= parts; k++) {
          const q = { x: a.x + ((b.x - a.x) * k) / parts, y: a.y + ((b.y - a.y) * k) / parts };
          const ni = nodeAt(q);
          edges.push([prev, ni]);
          prev = ni;
        }
      } else prev = nodeAt(poly[0]);
    }
  }
  for (const [a, b] of edges) link(a, b);
  // Path ends that touch another corridor are spliced into the nearest edge (within 16 units).
  const unlink = (a: number, b: number) => {
    adj[a] = adj[a].filter(([x]) => x !== b);
    adj[b] = adj[b].filter(([x]) => x !== a);
  };
  for (let i = 0; i < nodes.length; i++) {
    if (adj[i].length > 1) continue;
    let best: [number, number] | null = null;
    let bd = 16;
    for (let a = 0; a < adj.length; a++)
      for (const [b] of adj[a]) {
        if (a >= b || a === i || b === i || adj[i].some(([x]) => x === a || x === b)) continue;
        const d = segDist(nodes[i], nodes[a], nodes[b]);
        if (d < bd) {
          bd = d;
          best = [a, b];
        }
      }
    if (best) {
      const [a, b] = best;
      const da = dist(nodes[i], nodes[a]);
      const db = dist(nodes[i], nodes[b]);
      if (da < 6) link(i, a);
      else if (db < 6) link(i, b);
      else {
        unlink(a, b);
        link(a, i);
        link(i, b);
      }
    }
  }
  // Crossing segments (lanes crossing the river, jungle paths crossing lanes) get a junction.
  const segs: Array<[number, number]> = [];
  for (let a = 0; a < adj.length; a++) for (const [b] of adj[a]) if (a < b) segs.push([a, b]);
  for (let i = 0; i < segs.length; i++)
    for (let j = i + 1; j < segs.length; j++) {
      const [a, b] = segs[i];
      const [c, d] = segs[j];
      if (a === c || a === d || b === c || b === d) continue;
      if (segmentsCross(nodes[a], nodes[b], nodes[c], nodes[d])) {
        // Connect the nearest endpoints of the two segments.
        const pairs: Array<[number, number]> = [[a, c], [a, d], [b, c], [b, d]];
        pairs.sort((p, q) => dist(nodes[p[0]], nodes[p[1]]) - dist(nodes[q[0]], nodes[q[1]]));
        link(pairs[0][0], pairs[0][1]);
      }
    }
  const N = nodes.length;
  const D = new Float32Array(N * N).fill(Infinity);
  const NX = new Int16Array(N * N).fill(-1);
  // Dijkstra from every node (small graphs; binary-heap-free O(N²) is fine).
  const done = new Uint8Array(N);
  for (let s = 0; s < N; s++) {
    done.fill(0);
    const row = s * N;
    D[row + s] = 0;
    NX[row + s] = s;
    const first = new Int16Array(N).fill(-1);
    for (;;) {
      let u = -1;
      let bu = Infinity;
      for (let i = 0; i < N; i++) if (!done[i] && D[row + i] < bu) {
        bu = D[row + i];
        u = i;
      }
      if (u < 0) break;
      done[u] = 1;
      for (const [v, w] of adj[u]) {
        const nd = bu + w;
        if (nd < D[row + v]) {
          D[row + v] = nd;
          first[v] = u === s ? v : first[u];
        }
      }
    }
    for (let i = 0; i < N; i++) NX[row + i] = i === s ? s : first[i];
  }
  return { nodes, adj, dist: D, next: NX };
}

export function nearestNode(nav: NavGraph, p: Pt): number {
  let best = 0;
  let bd = Infinity;
  for (let i = 0; i < nav.nodes.length; i++) {
    const n = nav.nodes[i];
    const d = (n.x - p.x) * (n.x - p.x) + (n.y - p.y) * (n.y - p.y);
    if (d < bd) {
      bd = d;
      best = i;
    }
  }
  return best;
}

export function routeDist(nav: NavGraph, a: number, b: number): number {
  return nav.dist[a * nav.nodes.length + b];
}

export function nextHop(nav: NavGraph, a: number, b: number): number {
  return nav.next[a * nav.nodes.length + b];
}

/** Is a point on walkable terrain? */
export function walkable(m: MapDefinition, p: Pt): boolean {
  const n = 1000 / m.cell;
  const gx = Math.floor(p.x / m.cell);
  const gy = Math.floor(p.y / m.cell);
  if (gx < 0 || gy < 0 || gx >= n || gy >= n) return false;
  return m.grid[gy * n + gx] === 1;
}

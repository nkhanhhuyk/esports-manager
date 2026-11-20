/**
 * Engine A (spatial): one authoritative, deterministic, map-aware simulation for all five MOBAs.
 *
 * Every second the simulation moves ten heroes along the battlefield's waypoint graph, spawns and
 * advances minion waves along the real lane geometry, lets towers fire at what is in range,
 * resolves engagements between heroes that are close enough to fight, clears jungle camps and
 * contests objectives in their pits. Results come only from that state: kills need heroes within
 * attack range, structures fall only to damage dealt next to them, and a game ends only when the
 * core (Base Crystal / Nexus / Crystal / Ancient) is destroyed.
 *
 * Who-does-what comes from the players' ratings (laning, jungle, skirmish, teamfight, macro,
 * vision, economy, clutch), their characters' classes, the team's coaching/aggression and
 * information (vision): heroes only react to enemies their team can see.
 *
 * Recording (`replay`) and commentary are passive sinks: they never draw from `rng`, so a game
 * watched live, read as text or simulated instantly produces the same result.
 */
import { clamp } from '../../core/math';
import type { RNG } from '../../core/rng';
import type { ObjectiveDef } from '../../data/titles/types';
import type { GameResult, MobaReplay } from '../../model/types';
import type { PlayerCtx } from '../common/team';
import { assignRatings, charName, dmgWeight, emit, fmtTime, healWeight, StatBook, tankWeight, teamName, type GameContext } from '../common/engine';
import { along, dist, LANES, nearestNode, nextHop, project, routeDist, walkable, type LaneId, type MapDefinition, type Pt, type StructureDef } from './map';
import { mobaMap } from './maps';

// ---------------------------------------------------------------------------------------------
// Per-title spatial tuning (rules that the maps don't carry)
// ---------------------------------------------------------------------------------------------

interface SpatialTuning {
  firstWave: number;
  waveEvery: number;
  minions: number;
  minionHp: number;
  minionDps: number;
  minionGold: number;
  minionXp: number;
  waveSpeed: number;
  levelCap: number;
  /** XP needed for level L is xpK × (L−1) × (L+2) / 2. */
  xpK: number;
  passiveGold: number; // per second per hero
  startGold: number;
  killGold: number;
  towerGold: number; // team gold per tower
  respawn: (level: number, minute: number) => number;
  /** Gold for one 1.0 item-power step. */
  itemScale: number;
  recallSec: number;
  heroHp: number;
  heroHpLv: number;
  heroDps: number;
  heroDpsLv: number;
  ultCd: number;
  wardLife: number;
  objHp: (o: ObjectiveDef, minute: number) => number;
  objDps: number;
  buyback?: boolean;
  /** Escape spell cooldown (Flash, blink …), seconds. */
  escapeCd: number;
  /** Structure durability multiplier on the map's base values. */
  structMult: number;
  /** Fraction of hero damage that applies to structures. */
  siegeMult: number;
  /** Multiplier on the power edge needed to start a fight (pro-scene caution). */
  caution: number;
}

const BASE: SpatialTuning = {
  firstWave: 30,
  waveEvery: 30,
  minions: 5,
  minionHp: 420,
  minionDps: 14,
  minionGold: 21,
  minionXp: 32,
  waveSpeed: 20,
  levelCap: 15,
  xpK: 75,
  passiveGold: 1.6,
  startGold: 300,
  killGold: 300,
  towerGold: 120,
  respawn: (lv, m) => Math.min(60, 8 + lv * 1.6 + m * 0.9),
  itemScale: 2600,
  recallSec: 6,
  heroHp: 1400,
  heroHpLv: 175,
  heroDps: 62,
  heroDpsLv: 9,
  ultCd: 55,
  wardLife: 90,
  objHp: (o, m) => (o.major ? 9000 : 4200) * (1 + m / 30),
  objDps: 140,
  escapeCd: 120,
  structMult: 1,
  siegeMult: 0.6,
  caution: 1,
};

const TUNING: Record<string, SpatialTuning> = {
  mlbb: { ...BASE, structMult: 1.25, siegeMult: 0.5, caution: 1.05, firstWave: 22, waveSpeed: 24, minionGold: 24, passiveGold: 2.2, xpK: 62, respawn: (lv, m) => Math.min(55, 6 + lv * 1.4 + m * 1.2), ultCd: 45, recallSec: 6 },
  wildrift: { ...BASE, escapeCd: 150, structMult: 1.6, siegeMult: 0.45, caution: 1.15, firstWave: 30, waveSpeed: 26, minionGold: 22, passiveGold: 1.9, xpK: 70, respawn: (lv, m) => Math.min(58, 6 + lv * 2 + m * 0.7), ultCd: 50, recallSec: 5 },
  hok: { ...BASE, structMult: 2.0, siegeMult: 0.42, caution: 1.3, firstWave: 30, waveSpeed: 23, minionGold: 25, passiveGold: 2.3, xpK: 66, respawn: (lv, m) => Math.min(55, 7 + lv * 1.5 + m * 1.0), ultCd: 45 },
  lol: {
    ...BASE,
    firstWave: 65,
    minions: 6,
    waveSpeed: 18,
    minionGold: 20,
    minionXp: 30,
    passiveGold: 2.04,
    startGold: 500,
    levelCap: 18,
    xpK: 105,
    itemScale: 3000,
    respawn: (lv, m) => Math.min(78, 6 + lv * 2.4 + Math.max(0, m - 15) * 0.9),
    recallSec: 8,
    ultCd: 80,
    escapeCd: 300,
    structMult: 2.1,
    siegeMult: 0.38,
    caution: 1.7,
    heroHp: 2000,
    heroHpLv: 230,
    wardLife: 150,
    objHp: (o, m) => (o.major ? 12000 : 5200) * (1 + m / 40),
  },
  dota2: {
    ...BASE,
    firstWave: 1,
    minions: 4,
    minionHp: 550,
    waveSpeed: 17,
    minionGold: 38,
    minionXp: 50,
    passiveGold: 1.5,
    startGold: 600,
    levelCap: 30,
    xpK: 95,
    killGold: 260,
    itemScale: 3500,
    respawn: (lv) => Math.min(100, 6 + lv * 3.1),
    escapeCd: 90,
    structMult: 1.15,
    siegeMult: 0.45,
    caution: 1.2,
    recallSec: 7,
    ultCd: 90,
    wardLife: 360,
    objHp: (o, m) => (o.id === 'roshan' ? 9000 : o.id === 'tormentor' ? 6000 : 1) * (1 + m / 30),
    buyback: true,
  },
};

// ---------------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------------

const enum Act {
  Lane = 1,
  Jungle = 2,
  Roam = 3,
  Objective = 4,
  Fight = 5,
  Retreat = 6,
  Recall = 7,
  Siege = 8,
  Defend = 9,
  Group = 10,
}

interface Hero {
  i: number;
  side: 0 | 1;
  pc: PlayerCtx;
  lane: LaneId | null;
  job: 'lane' | 'jungle' | 'roam';
  x: number;
  y: number;
  node: number;
  nxt: number;
  goal: number;
  goalPt: Pt | null;
  free: boolean;
  hp: number;
  maxHp: number;
  level: number;
  xp: number;
  gold: number;
  unspent: number;
  dead: boolean;
  respawnAt: number;
  act: Act;
  target: number;
  stunUntil: number;
  ultAt: number;
  recallUntil: number;
  lastHit: Float64Array;
  bbReadyAt: number;
  escapeAt: number;
  chaseFrom: number;
  aegis: boolean;
  // derived
  dmgW: number;
  tankW: number;
  healW: number;
  melee: boolean;
  skill: { lane: number; jungle: number; fight: number; skirm: number; macro: number; vision: number; econ: number; clutch: number };
  wardAt: number;
  campTarget: number;
  structTarget: number;
  /** Cached once per second. */
  dps: number;
  pow: number;
}

interface Wave {
  side: 0 | 1;
  lane: LaneId;
  /** Map position, refreshed once per tick. */
  x: number;
  y: number;
  s: number; // distance travelled from own base end (map units)
  n: number; // minions (float)
  hpEach: number;
  power: number; // damage multiplier (super / empowered minions)
  big: number; // summoned siege unit hp (Herald / Lord / Vanguard), 0 = none
  bigDps: number;
  until: number; // empowered until (s)
  moving: boolean;
}

interface Struct {
  d: StructureDef;
  hp: number;
  alive: boolean;
  laneS: number; // position along the owner's lane (distance from team 0 base end), −1 = base
  rebuildAt: number;
  aggro: number; // hero index targeted because it hit an allied hero, −1
}

interface Camp {
  i: number;
  d: MapDefinition['camps'][number];
  alive: boolean;
  hp: number;
  at: number; // next spawn time
}

interface Obj {
  def: ObjectiveDef;
  pit: number; // index into map.pits (−1 none)
  alive: boolean;
  hp: number;
  maxHp: number;
  nextAt: number;
  count: number;
}

// ---------------------------------------------------------------------------------------------

const AGGRO = 75; // engagement radius
const SIGHT = 120;
const WARD_SIGHT = 115;
const TOWER_SIGHT = 130;

export function simulateMobaSpatial(g: GameContext, record = false): GameResult {
  const t = g.title;
  const cfg = t.moba!;
  const T = TUNING[t.id] ?? BASE;
  const map = mobaMap(t.id);
  const nav = map.nav;
  const { rng } = g;
  const keys = t.stats.map((s) => s.key);
  const has = new Set(keys);
  const book = new StatBook(g.teams, keys);

  // ------------------------------------------------------------- heroes
  const laneOf = (side: 0 | 1, roleId: string): { lane: LaneId | null; job: Hero['job'] } => {
    if (cfg.junglers.includes(roleId) && !cfg.lanes.some((l) => l.roles.includes(roleId))) return { lane: null, job: 'jungle' };
    const li = cfg.lanes.findIndex((l) => l.roles.includes(roleId));
    if (li < 0) return { lane: side === 0 ? 'bot' : 'bot', job: 'roam' };
    let lane = LANES[li] as LaneId;
    // Dota: lanes are named by role (safe / mid / off) — Radiant's safe lane is the bottom one.
    if (t.id === 'dota2' && li !== 1) lane = (li === 0) === (side === 0) ? 'bot' : 'top';
    return { lane, job: 'lane' };
  };
  const heroes: Hero[] = [];
  g.teams.forEach((tc, side) => {
    tc.players.forEach((pc) => {
      const { lane, job } = laneOf(side as 0 | 1, pc.roleId);
      const cls = (t.characters.find((c) => c.id === pc.charId)?.cls ?? '').toLowerCase();
      const melee = !/marksman|mage|nuker|support|enchanter|ranged|intelligence/.test(cls);
      const ph = pc.phase;
      const f = map.fountains[side];
      const h: Hero = {
        i: heroes.length,
        side: side as 0 | 1,
        pc,
        lane,
        job,
        x: f.x,
        y: f.y,
        node: map.fountainNode[side],
        nxt: -1,
        goal: map.fountainNode[side],
        goalPt: null,
        free: false,
        hp: 1,
        maxHp: 1,
        level: 1,
        xp: 0,
        gold: T.startGold,
        unspent: 0,
        dead: false,
        respawnAt: 0,
        act: job === 'jungle' ? Act.Jungle : job === 'roam' ? Act.Roam : Act.Lane,
        target: -1,
        stunUntil: 0,
        ultAt: 60 + rng.int(0, 20),
        recallUntil: 0,
        lastHit: new Float64Array(10).fill(-999),
        bbReadyAt: 0,
        escapeAt: 0,
        chaseFrom: -1,
        aegis: false,
        dmgW: dmgWeight(g, pc),
        tankW: tankWeight(g, pc),
        healW: healWeight(g, pc),
        melee,
        skill: {
          lane: ph.laning ?? 60,
          jungle: ph.jungle ?? 60,
          fight: ph.teamfight ?? 60,
          skirm: ph.skirmish ?? 60,
          macro: ph.macro ?? 60,
          vision: ph.vision ?? 60,
          econ: pc.base.phaseC.economy ?? 60,
          clutch: pc.base.phaseC.clutch ?? 60,
        },
        wardAt: 40 + rng.int(0, 40),
        campTarget: -1,
        structTarget: -1,
        dps: 0,
        pow: 0,
      };
      h.maxHp = maxHp(h);
      h.hp = h.maxHp;
      heroes.push(h);
    });
  });
  const bySide: [Hero[], Hero[]] = [heroes.filter((h) => h.side === 0), heroes.filter((h) => h.side === 1)];

  /** Item power from gold already spent on items: diminishing returns (later items add less). */
  function itemPower(h: Hero): number {
    return 1 + 0.8 * Math.sqrt(Math.max(0, h.gold - h.unspent - T.startGold) / T.itemScale);
  }
  function maxHp(h: Hero): number {
    return (T.heroHp + T.heroHpLv * (h.level - 1)) * (0.75 + 0.25 * itemPower(h)) * (0.8 + 0.35 * h.tankW);
  }
  /** Damage per second against heroes (attributes act once: mechanics through the skill factor). */
  function heroDps(h: Hero, now: number): number {
    const sk = Math.pow((h.skill.skirm * 0.45 + h.skill.fight * 0.55) / 65, 1.3);
    const comp = g.comp[h.side];
    const phaseT = clamp(now / 60 / cfg.avgLength, 0, 1.4);
    const early = ((comp.profile.early ?? 5) - 5) * (1 - phaseT);
    const late = ((comp.profile.scaling ?? comp.profile.farm ?? 5) - 5) * phaseT;
    const compMod = 1 + comp.total * 0.06 + (early + late) * 0.012;
    const buffs = teamBuff[h.side] + (now < execUntil[h.side] ? execVal[h.side] : 0);
    return (T.heroDps + T.heroDpsLv * (h.level - 1)) * itemPower(h) * h.dmgW * sk * compMod * (1 + buffs) * (0.94 + 0.12 * (g.teams[h.side].chem / 100));
  }
  function speed(h: Hero): number {
    return map.moveSpeed * (1 + 0.04 * Math.min(itemPower(h) - 1, 3)) * (h.act === Act.Retreat ? 1.06 : 1);
  }

  // ------------------------------------------------------------- structures, camps, objectives
  const structs: Struct[] = map.structures.map((d) => {
    const lane = d.lane;
    let laneS = -1;
    if (lane) laneS = project(map.lanes[lane], d.pos, map.laneLen[lane]) * map.laneLen[lane];
    return { d: { ...d, hp: d.hp * T.structMult }, hp: d.hp * T.structMult, alive: true, laneS, rebuildAt: 0, aggro: -1 };
  });
  const structIdx = new Map(structs.map((s, i) => [s.d.id, i]));
  const camps: Camp[] = map.camps.map((d, i) => ({ i, d, alive: false, hp: d.hp, at: d.spawn }));
  const objs: Obj[] = cfg.objectives.map((def) => {
    const pits = map.pits.map((p, i) => ({ p, i })).filter((x) => x.p.objectives.includes(def.id));
    return { def, pit: pits[0]?.i ?? -1, alive: false, hp: 0, maxHp: 0, nextAt: def.spawn * 60, count: 0 };
  });
  const teamBuff = [0, 0];
  const execUntil = [0, 0];
  const execVal = [0, 0];
  const stacks: [Record<string, number>, Record<string, number>] = [{}, {}];
  const siegeBuff = [0, 0];
  const wards: Array<{ side: 0 | 1; x: number; y: number; until: number; from: number }> = [];
  const kills = [0, 0];
  const structsDown = [0, 0];
  const objTaken = [0, 0];
  const buybacks = [0, 0];

  // ------------------------------------------------------------- waves
  const waves: Wave[] = [];
  // Lane lookup tables: position every 4 map units along each lane (from team 0's end).
  const LUT_STEP = 4;
  const lut: Record<LaneId, Float32Array> = { top: new Float32Array(0), mid: new Float32Array(0), bot: new Float32Array(0) };
  for (const lane of LANES) {
    const L = map.laneLen[lane];
    const n = Math.ceil(L / LUT_STEP) + 1;
    const arr = new Float32Array(n * 2);
    for (let k = 0; k < n; k++) {
      const q = along(map.lanes[lane], Math.min(1, (k * LUT_STEP) / L), L);
      arr[k * 2] = q.x;
      arr[k * 2 + 1] = q.y;
    }
    lut[lane] = arr;
  }
  /** Point at distance `d` from team 0's end of a lane. */
  const laneAt = (lane: LaneId, d: number): Pt => {
    const arr = lut[lane];
    const k = Math.max(0, Math.min(arr.length / 2 - 1, Math.round(d / LUT_STEP)));
    return { x: arr[k * 2], y: arr[k * 2 + 1] };
  };
  /** Position of a wave in map space (cached per tick). */
  const wavePos = (w: Wave): Pt => ({ x: w.x, y: w.y });
  const placeWave = (w: Wave) => {
    const L = map.laneLen[w.lane];
    const arr = lut[w.lane];
    const d = w.side === 0 ? w.s : L - w.s;
    const k = Math.max(0, Math.min(arr.length / 2 - 1, Math.round(d / LUT_STEP)));
    w.x = arr[k * 2];
    w.y = arr[k * 2 + 1];
  };
  /** Distance along the lane from team 0's base end. */
  const waveAbs = (w: Wave): number => (w.side === 0 ? w.s : map.laneLen[w.lane] - w.s);

  // ------------------------------------------------------------- recorder
  const rec: MobaReplay | null = record
    ? {
        v: 1,
        mapId: map.id,
        titleId: t.id,
        dt: 2,
        frames: 0,
        heroes: heroes.map((h) => ({ side: h.side, playerId: h.pc.p.id, handle: h.pc.p.handle, char: charName(g, h.pc), roleId: h.pc.roleId })),
        structures: structs.map((s) => s.d.id),
        camps: camps.map((c) => c.d.id),
        pits: map.pits.map((p) => p.id),
        hx: [],
        hy: [],
        hhp: [],
        hlv: [],
        hst: [],
        htg: [],
        sh: [],
        wf: [],
        wn: [],
        po: [],
        ph: [],
        ca: [],
        tg: [],
        tk: [],
        ts: [],
        objectives: objs.map((o) => o.def.name),
        wards: [],
        events: [],
      }
    : null;
  const ev = (now: number, k: string, a?: number, b?: number, at?: Pt, s?: 0 | 1, n?: string) => {
    if (rec) rec.events.push({ t: now, k, a, b, x: at ? Math.round(at.x) : undefined, y: at ? Math.round(at.y) : undefined, s, n });
  };

  // ------------------------------------------------------------- movement
  const nodeCache = new Map<Pt, number>();
  /** Nearest graph node (cached for the map's static points: structures, camps, pits). */
  const nodeFor = (p: Pt): number => {
    let n = nodeCache.get(p);
    if (n === undefined) {
      n = nearestNode(nav, p);
      if (nodeCache.size < 400 && (map.structures.some((x) => x.pos === p) || map.camps.some((x) => x.pos === p) || map.pits.some((x) => x.pos === p))) nodeCache.set(p, n);
    }
    return n;
  };
  const setGoal = (h: Hero, p: Pt) => {
    if (h.goalPt && Math.abs(h.goalPt.x - p.x) + Math.abs(h.goalPt.y - p.y) < 12) {
      h.goalPt = p;
      return;
    }
    const n = nodeFor(p);
    h.goal = n;
    h.goalPt = p;
  };
  const move = (h: Hero, step: number) => {
    if (h.free) {
      // Leaving free (combat) movement: walk straight back to the nearest graph node.
      const n = nearestNode(nav, h);
      h.node = n;
      h.nxt = n;
      h.free = false;
    }
    let left = step;
    let guard = 0;
    while (left > 0 && guard++ < 12) {
      if (h.nxt < 0) {
        if (h.node === h.goal) {
          // Final approach to the exact goal point (within the corridor around the node).
          if (h.goalPt) {
            const d = dist(h, h.goalPt);
            if (d > 1 && walkable(map, h.goalPt)) {
              const k = Math.min(1, left / d);
              h.x += (h.goalPt.x - h.x) * k;
              h.y += (h.goalPt.y - h.y) * k;
            }
          }
          return;
        }
        h.nxt = nextHop(nav, h.node, h.goal);
        if (h.nxt < 0) return;
      }
      const n = nav.nodes[h.nxt];
      const d = Math.hypot(n.x - h.x, n.y - h.y);
      if (d <= left) {
        h.x = n.x;
        h.y = n.y;
        h.node = h.nxt;
        h.nxt = -1;
        left -= d;
      } else {
        h.x += ((n.x - h.x) / d) * left;
        h.y += ((n.y - h.y) / d) * left;
        left = 0;
      }
    }
  };
  /** Combat movement: straight toward a point when the straight line stays on walkable ground. */
  const moveDirect = (h: Hero, p: Pt, step: number): boolean => {
    const d = dist(h, p);
    if (d < 1) return true;
    const k = Math.min(1, step / d);
    const nx = h.x + (p.x - h.x) * k;
    const ny = h.y + (p.y - h.y) * k;
    const mid = { x: (h.x + nx) / 2, y: (h.y + ny) / 2 };
    if (!walkable(map, { x: nx, y: ny }) || !walkable(map, mid)) return false;
    h.x = nx;
    h.y = ny;
    h.free = true;
    return true;
  };

  // ------------------------------------------------------------- vision
  const visible: [Uint8Array, Uint8Array] = [new Uint8Array(10), new Uint8Array(10)];
  const lastSeen: [Float64Array, Float64Array] = [new Float64Array(10).fill(-999), new Float64Array(10).fill(-999)];
  const updateVision = (now: number) => {
    for (const side of [0, 1] as const) {
      const vis = visible[side];
      vis.fill(0);
      for (const e of bySide[1 - side]) {
        if (e.dead) continue;
        let seen = false;
        for (const a of bySide[side]) if (!a.dead && (a.x - e.x) ** 2 + (a.y - e.y) ** 2 < SIGHT * SIGHT) {
          seen = true;
          break;
        }
        if (!seen) for (const w of wards) if (w.side === side && (w.x - e.x) ** 2 + (w.y - e.y) ** 2 < WARD_SIGHT * WARD_SIGHT) {
          seen = true;
          break;
        }
        if (!seen) for (const s of structs) if (s.alive && s.d.team === side && s.d.kind !== 'inhibitor' && (s.d.pos.x - e.x) ** 2 + (s.d.pos.y - e.y) ** 2 < TOWER_SIGHT * TOWER_SIGHT) {
          seen = true;
          break;
        }
        if (seen) {
          vis[e.i] = 1;
          lastSeen[side][e.i] = now;
        }
      }
    }
  };

  // ------------------------------------------------------------- economy helpers
  const levelFor = (xp: number): number => {
    let L = 1;
    while (L < T.levelCap && xp >= T.xpK * L * (L + 3) * 0.5) L++;
    return L;
  };
  const giveXp = (h: Hero, x: number) => {
    h.xp += x;
    const L = levelFor(h.xp);
    if (L !== h.level) {
      const frac = h.hp / h.maxHp;
      h.level = L;
      h.maxHp = maxHp(h);
      h.hp = Math.max(h.hp, frac * h.maxHp);
    }
  };
  const giveGold = (h: Hero, x: number) => {
    h.gold += x;
    h.unspent += x;
  };
  const teamGold = (side: number) => bySide[side].reduce((a, h) => a + h.gold, 0);
  /** Gold lead per side, refreshed once per tick. */
  const leadNow = [0, 0];
  const goldLead = (side: number) => {
    const a = teamGold(side);
    const b = teamGold(1 - side);
    return (a - b) / Math.max(1, a + b);
  };

  // ------------------------------------------------------------- kills
  let firstBlood = false;
  const multi = new Map<number, { n: number; at: number }>();
  const killHero = (victim: Hero, killerIn: Hero | null, now: number, by: string) => {
    let killer = killerIn;
    if (victim.aegis) {
      victim.aegis = false;
      victim.hp = victim.maxHp;
      emit(g, now, victim.side, 'aegis', () => `${victim.pc.p.handle} is saved by the Aegis.`, true);
      ev(now, 'aegis', victim.i, undefined, victim);
      return;
    }
    // Executions (tower, monster) go to the enemy who damaged the victim most recently; with no
    // enemy involvement the death is an execution and nobody scores.
    if (!killer) {
      let best = -1;
      for (const e of bySide[1 - victim.side]) if (now - victim.lastHit[e.i] <= 10 && (best < 0 || victim.lastHit[e.i] > victim.lastHit[best])) best = e.i;
      if (best >= 0) killer = heroes[best];
    }
    victim.dead = true;
    victim.hp = 0;
    victim.target = -1;
    victim.act = Act.Lane;
    const minute = now / 60;
    victim.respawnAt = now + T.respawn(victim.level, minute) * (1 + Math.max(0, cfg.respawnGrowth - 1) * clamp(minute / cfg.avgLength - 0.6, 0, 1));
    const ks = 1 - victim.side;
    if (!killer) {
      victim.respawnAt = now + T.respawn(victim.level, minute);
      emit(g, now, victim.side, 'execute', () => `${fmtTime(now)} ${whoAs(victim)} is executed by the ${by}.`);
      ev(now, 'execute', undefined, victim.i, victim, ks as 0 | 1, by);
      return;
    }
    book.add(victim.pc.p.id, 'd', 1);
    kills[ks]++;
    // Assists: enemies who damaged the victim in the last 10 seconds.
    const helpers = bySide[ks].filter((h) => h !== killer && now - victim.lastHit[h.i] <= 10);
    const lead = goldLead(ks);
    const bounty = T.killGold * (1 + Math.max(0, -lead) * cfg.comeback * 3) * (1 - Math.max(0, lead) * cfg.comeback * 0.8);
    if (killer) {
      book.add(killer.pc.p.id, 'k', 1);
      giveGold(killer, bounty * 0.7);
      giveXp(killer, 120 + victim.level * 25);
      const m = multi.get(killer.i);
      const nm = m && now - m.at < 12 ? m.n + 1 : 1;
      multi.set(killer.i, { n: nm, at: now });
      if (nm >= 3) emit(g, now, ks as 0 | 1, 'multikill', () => `${killer.pc.p.handle} ${nm === 3 ? 'triple' : nm === 4 ? 'quadra' : 'penta'} kill!`, true);
    }
    for (const a of helpers) {
      book.add(a.pc.p.id, 'a', 1);
      giveGold(a, (bounty * 0.3) / Math.max(1, helpers.length));
      giveXp(a, 60 + victim.level * 12);
    }
    victim.unspent = 0; // shops while dead
    if (!firstBlood) {
      firstBlood = true;
      emit(g, now, ks as 0 | 1, 'first-blood', () => `${fmtTime(now)} First blood! ${killer ? whoAs(killer) : by} takes down ${whoAs(victim)}.`, true);
    } else if (g.events) emit(g, now, ks as 0 | 1, 'kill', () => `${fmtTime(now)} ${killer ? whoAs(killer) : by} kills ${whoAs(victim)}${helpers.length ? ` (${helpers.map((x) => x.pc.p.handle).join(', ')})` : ''}.`);
    ev(now, 'kill', killer?.i, victim.i, victim, ks as 0 | 1, by);
    if (rec && helpers.length) rec.events[rec.events.length - 1].as = helpers.map((x) => x.i);
    // Ace
    if (bySide[victim.side].every((h) => h.dead)) {
      emit(g, now, ks as 0 | 1, 'ace', () => `${fmtTime(now)} ACE — ${teamName(g, ks)} wipe ${teamName(g, victim.side)}!`, true);
      ev(now, 'ace', undefined, undefined, victim, ks as 0 | 1);
    }
  };
  const whoAs = (h: Hero) => {
    const c = charName(g, h.pc);
    return c ? `${h.pc.p.handle} (${c})` : h.pc.p.handle;
  };

  const damageHero = (src: Hero | null, dst: Hero, amount: number, now: number, by = 'tower') => {
    if (dst.dead || amount <= 0) return;
    const mitig = 1 / (1 + 0.25 * (dst.tankW - 1) + 0.08 * (itemPower(dst) - 1));
    const dmg = amount * mitig;
    dst.hp -= dmg;
    if (has.has('taken')) book.add(dst.pc.p.id, 'taken', dmg);
    if (src) {
      dst.lastHit[src.i] = now;
      book.add(src.pc.p.id, 'dmg', dmg);
    }
    if (dst.hp <= 0) killHero(dst, src, now, by);
  };

  // ------------------------------------------------------------- structures
  const canDamage = (s: Struct): boolean => {
    if (!s.alive) return false;
    for (const r of s.d.requires) {
      if (r.startsWith('any:')) {
        const kind = r.slice(4);
        const pool = structs.filter((x) => x.d.team === s.d.team && (kind === 't3' ? x.d.kind === 'tower' && x.d.tier === 3 : x.d.kind === kind));
        if (!pool.some((x) => !x.alive)) return false;
      } else {
        const i = structIdx.get(r);
        if (i !== undefined && structs[i].alive) return false;
      }
    }
    // MLBB / HoK crystals: one lane's base tower must be down.
    if (s.d.kind === 'core' && s.d.requires.length === 0) {
      const lastTier = structs.filter((x) => x.d.team === s.d.team && x.d.kind === 'tower' && x.d.tier === 3);
      if (lastTier.length && !lastTier.some((x) => !x.alive)) return false;
      const inhibs = structs.filter((x) => x.d.team === s.d.team && x.d.kind === 'inhibitor');
      if (inhibs.length && !inhibs.some((x) => !x.alive)) return false;
    }
    return true;
  };
  let winner = -1;
  let endT = 0;
  const damageStruct = (si: number, amount: number, now: number, by: Hero | null, attackerSide: 0 | 1) => {
    const s = structs[si];
    if (!canDamage(s) || amount <= 0) return;
    const dmg = amount * (1 + siegeBuff[attackerSide]);
    s.hp -= dmg;
    if (by && has.has('siege')) book.add(by.pc.p.id, 'siege', dmg);
    if (s.hp > 0) return;
    s.alive = false;
    s.hp = 0;
    frontCache.clear();
    structsDown[attackerSide]++;
    if (s.d.respawn) s.rebuildAt = now + s.d.respawn;
    const credit = by ?? nearestHero(attackerSide, s.d.pos, 160);
    if (credit) book.add(credit.pc.p.id, 'towers', 1);
    for (const h of bySide[attackerSide]) giveGold(h, T.towerGold * (s.d.kind === 'tower' ? 1 : 0.6) * (dist(h, s.d.pos) < 200 ? 1.4 : 0.6));
    if (s.d.kind === 'core') {
      winner = attackerSide;
      endT = now;
      emit(g, now, attackerSide, 'core', `${fmtTime(now)} ${teamName(g, attackerSide)} destroy the ${s.d.name} and win the game!`, true);
      ev(now, 'core', credit?.i, undefined, s.d.pos, attackerSide, s.d.name);
      return;
    }
    emit(g, now, attackerSide, 'structure', () => `${fmtTime(now)} ${teamName(g, attackerSide)} destroy the ${s.d.name}${credit ? ` (${credit.pc.p.handle})` : ''}.`, s.d.kind !== 'tower' || s.d.tier >= 3);
    ev(now, 'structure', credit?.i, si, s.d.pos, attackerSide, s.d.name);
  };
  const nearestHero = (side: number, p: Pt, within: number): Hero | null => {
    let best: Hero | null = null;
    let bd = within;
    for (const h of bySide[side]) {
      if (h.dead) continue;
      const d = dist(h, p);
      if (d < bd) {
        bd = d;
        best = h;
      }
    }
    return best;
  };
  /** Next alive enemy structure along a lane, as seen from `side` pushing toward the enemy base. */
  const frontCache = new Map<string, Struct | null>();
  const laneFront = (side: 0 | 1, lane: LaneId): Struct | null => {
    const key = side + lane;
    if (frontCache.has(key)) return frontCache.get(key)!;
    const r = laneFront0(side, lane);
    frontCache.set(key, r);
    return r;
  };
  const laneFront0 = (side: 0 | 1, lane: LaneId): Struct | null => {
    let best: Struct | null = null;
    for (const s of structs) {
      if (!s.alive || s.d.team === side || s.d.lane !== lane) continue;
      const along0 = side === 0 ? s.laneS : map.laneLen[lane] - s.laneS;
      if (!best) best = s;
      else {
        const b0 = side === 0 ? best.laneS : map.laneLen[lane] - best.laneS;
        if (along0 < b0) best = s;
      }
    }
    if (best) return best;
    // Lane cleared: base towers / core.
    let core: Struct | null = null;
    for (const s of structs) if (s.alive && s.d.team !== side && !s.d.lane && canDamage(s)) {
      if (!core || s.d.kind !== 'core') core = s;
    }
    return core ?? structs.find((s) => s.alive && s.d.team !== side && s.d.kind === 'core') ?? null;
  };

  // ------------------------------------------------------------- waves: spawn / move / fight
  let nextWave = T.firstWave;
  const laneSuper = (side: 0 | 1, lane: LaneId) =>
    structs.some((s) => s.d.team !== side && s.d.lane === lane && (s.d.kind === 'inhibitor' || s.d.kind === 'barracks') && !s.alive) ? 1.6 : 1;
  const spawnWaves = (now: number) => {
    for (const side of [0, 1] as const)
      for (const lane of LANES) {
        const sup = laneSuper(side, lane);
        const grow = 1 + now / 60 / 40;
        waves.push({ side, lane, x: 0, y: 0, s: 0, n: T.minions, hpEach: T.minionHp * grow * (sup > 1 ? 1.3 : 1), power: sup * grow, big: 0, bigDps: 0, until: 0, moving: true });
      }
  };
  const summonBig = (side: 0 | 1, lane: LaneId, hp: number, dps: number, now: number, name: string) => {
    // Start at the attacking side's foremost wave or own outer tower.
    const own = waves.filter((w) => w.side === side && w.lane === lane).sort((a, b) => b.s - a.s)[0];
    waves.push({ side, lane, x: 0, y: 0, s: own ? own.s : map.laneLen[lane] * 0.3, n: 0, hpEach: 1, power: 1, big: hp, bigDps: dps, until: now + 120, moving: true });
    emit(g, now, side, 'summon', () => `${fmtTime(now)} ${teamName(g, side)} send the ${name} down the ${map.laneNames[lane]}.`, true);
    placeWave(waves[waves.length - 1]);
    ev(now, 'summon', undefined, undefined, wavePos(waves[waves.length - 1]), side, name);
  };
  const stepWaves = (now: number, dt: number) => {
    const L = map.laneLen;
    for (const w of waves) w.moving = true;
    for (const lane of LANES) {
      const ws = waves.filter((w) => w.lane === lane && (w.n > 0.05 || w.big > 0));
      // Contact between opposing fronts.
      for (const a of ws) {
        if (a.side !== 0) continue;
        for (const b of ws) {
          if (b.side !== 1) continue;
          const gap = L[lane] - a.s - b.s;
          if (gap <= 18) {
            a.moving = false;
            b.moving = false;
            // Minions trade damage.
            const da = (a.n * T.minionDps * a.power + a.bigDps * 0.3) * dt;
            const db = (b.n * T.minionDps * b.power + b.bigDps * 0.3) * dt;
            hitWave(b, da, now, null);
            hitWave(a, db, now, null);
          }
        }
      }
      // Structures in range of a wave.
      for (const w of ws) {
        const tgt = laneFront(w.side, lane);
        if (!tgt) continue;
        const tAbs = tgt.d.lane ? (w.side === 0 ? tgt.laneS : L[lane] - tgt.laneS) : L[lane] + 40;
        if (w.s >= tAbs - 40) {
          w.moving = false;
          w.s = Math.min(w.s, tAbs - 30);
          const ti = structs.indexOf(tgt);
          const dmg = (w.n * T.minionDps * 1.4 * w.power + w.bigDps) * dt;
          damageStruct(ti, dmg, now, null, w.side);
        }
      }
      for (const w of ws) {
        if (!w.moving) continue;
        // Do not pass an own wave that is fighting ahead (queue behind it, then merge).
        const ahead = ws.find((o) => o !== w && o.side === w.side && !o.moving && o.s > w.s && o.s - w.s < 30);
        if (ahead) {
          ahead.n += w.n;
          ahead.big = Math.max(ahead.big, w.big);
          ahead.bigDps = Math.max(ahead.bigDps, w.bigDps);
          w.n = 0;
          w.big = 0;
          continue;
        }
        w.s += T.waveSpeed * (w.big > 0 ? 0.8 : 1) * dt;
      }
    }
    for (const w of waves) placeWave(w);
    // Towers shoot minions (when not aggroed on a hero).
    for (const s of structs) {
      if (!s.alive || s.d.dps <= 0 || s.aggro >= 0) continue;
      const enemyWave = s.d.lane ? waves.find((w) => w.side !== s.d.team && w.lane === s.d.lane && (w.n > 0.05 || w.big > 0) && Math.abs(waveAbs(w) - s.laneS) < s.d.range + 35) : null;
      if (enemyWave) hitWave(enemyWave, s.d.dps * dt * 1.2, now, null);
    }
    // Remove dead waves.
    for (let i = waves.length - 1; i >= 0; i--) if (waves[i].n <= 0.05 && waves[i].big <= 0) waves.splice(i, 1);
  };
  const nearBuf: Hero[] = [];
  const hitWave = (w: Wave, dmg: number, now: number, by: Hero | null) => {
    if (w.big > 0 && w.n <= 0.5) {
      w.big -= dmg;
      return;
    }
    const before = w.n;
    w.n = Math.max(0, w.n - dmg / w.hpEach);
    const died = before - w.n;
    if (died <= 0) return;
    // Gold and XP to enemy heroes nearby (last hits go mostly to the hero farming it).
    const p = wavePos(w);
    const near = nearBuf;
    near.length = 0;
    for (const h of bySide[1 - w.side]) if (!h.dead && (h.x - p.x) ** 2 + (h.y - p.y) ** 2 < 95 * 95) near.push(h);
    if (!near.length) return;
    const lh = by ?? near[0];
    for (const h of near) {
      const share = h === lh ? 0.8 : 0.2 / Math.max(1, near.length - 1);
      const eff = 0.55 + 0.45 * (h.skill.econ / 100);
      giveGold(h, died * T.minionGold * share * eff);
      giveXp(h, (died * T.minionXp) / near.length);
      if (h === lh && has.has('cs')) book.add(h.pc.p.id, 'cs', died * eff);
    }
    if (cfg.denies && has.has('dn')) {
      // Denies: the lane opponent's share of minions that died to the tower or the minions.
      const own = bySide[w.side].filter((h) => !h.dead && dist(h, p) < 95);
      for (const h of own) if (rng.chance(0.08 * died * (h.skill.econ / 80))) book.add(h.pc.p.id, 'dn', 1);
    }
    void now;
  };

  // ------------------------------------------------------------- objectives
  const objPos = (o: Obj): Pt => map.pits[o.pit]?.pos ?? { x: 500, y: 500 };
  const spawnObjectives = (now: number) => {
    const minute = now / 60;
    for (const o of objs) {
      if (o.alive || now < o.nextAt) continue;
      if (o.def.until !== undefined && minute > o.def.until) continue;
      // Pit choice: titles with alternating pits (Roshan day/night, Tempest Dragon, Turtle respawns).
      const pits = map.pits.map((p, i) => ({ p, i })).filter((x) => x.p.objectives.includes(o.def.id));
      if (!pits.length) continue;
      if (pits.length > 1) {
        if (t.id === 'dota2') {
          const day = Math.floor(minute / 5) % 2 === 0;
          o.pit = pits.filter((x) => x.p.id.startsWith(o.def.id === 'roshan' ? 'rosh' : 'tormentor'))[day ? 1 : 0]?.i ?? pits[0].i;
        } else o.pit = o.count === 0 ? pits[0].i : pits[rng.int(0, pits.length - 1)].i;
      }
      // Pit occupied by another live objective → wait.
      if (objs.some((x) => x !== o && x.alive && x.pit === o.pit)) continue;
      o.alive = true;
      o.maxHp = T.objHp(o.def, minute);
      o.hp = o.maxHp;
      emit(g, now, -1, 'spawn', () => `${fmtTime(now)} ${o.def.name} has spawned (${map.pits[o.pit].name}).`);
      ev(now, 'spawn', undefined, undefined, objPos(o), undefined, o.def.name);
    }
  };
  const takeObjective = (o: Obj, side: 0 | 1, now: number) => {
    o.alive = false;
    o.count++;
    o.nextAt = now + o.def.respawn * 60;
    const mod = g.patch.objectiveMods[o.def.id] ?? 1;
    objTaken[side]++;
    const at = objPos(o);
    const takers = bySide[side].filter((h) => !h.dead && dist(h, at) < 140);
    const jg = takers.find((h) => h.job === 'jungle') ?? takers[0] ?? bySide[side][0];
    book.add(jg.pc.p.id, 'obj', 1);
    const minute = now / 60;
    switch (o.def.kind) {
      case 'gold':
        for (const h of bySide[side]) giveGold(h, o.def.value * mod);
        for (const h of takers) giveXp(h, 150 + minute * 10);
        break;
      case 'stack': {
        const n = (stacks[side][o.def.id] = (stacks[side][o.def.id] ?? 0) + 1);
        teamBuff[side] += o.def.value * mod;
        if (o.def.maxStacks && n === o.def.maxStacks && o.def.soul) {
          teamBuff[side] += o.def.soul * mod;
          emit(g, now, side, 'soul', () => `${teamName(g, side)} complete their ${o.def.name} set — a permanent power spike.`, true);
        }
        break;
      }
      case 'push': {
        // Summoned pushers (Lord, Herald, Vanguards): the lane with the most enemy structures standing.
        const laneScore = (l: LaneId) => structs.filter((s) => s.alive && s.d.team !== side && s.d.lane === l).length + (l === 'mid' ? 0.5 : 0);
        const lanes = [...LANES].sort((a, b) => laneScore(b) - laneScore(a));
        const n = o.def.major ? (t.id === 'hok' ? 3 : 1) : 1;
        for (const l of lanes.slice(0, n)) summonBig(side, l, 5000 * (1 + minute / 15) * mod, 280 * (1 + minute / 20) * mod * (o.def.major ? 1.4 : 1), now, o.def.name);
        if (o.def.major) {
          teamBuff[side] += o.def.value * mod * 0.15;
          execUntil[side] = now + 150;
          execVal[side] = Math.max(execVal[side], o.def.value * mod * 0.2);
          for (const w of waves) if (w.side === side) w.power *= 1.25;
        }
        break;
      }
      case 'siege':
        siegeBuff[side] += o.def.value * mod;
        break;
      case 'aegis': {
        // The Aegis goes to the team's carry when alive and near, else to whoever took Roshan.
        const carry = takers.find((h) => h.pc.role.fclass.includes('Carry/Fragger')) ?? jg;
        carry.aegis = true;
        break;
      }
      case 'execute':
        execUntil[side] = now + 150;
        execVal[side] = o.def.value * mod;
        break;
      case 'xp':
        for (const h of bySide[side].slice(0, 2)) giveXp(h, 400 * (1 + minute / 10));
        break;
      case 'buff':
      case 'shield':
        teamBuff[side] += o.def.value * mod * 0.5;
        break;
      default:
        break;
    }
    emit(g, now, side, 'objective', () => `${fmtTime(now)} ${teamName(g, side)} secure the ${o.def.name}${jg ? ` (${jg.pc.p.handle})` : ''}.`, !!o.def.major);
    ev(now, 'objective', jg?.i, objs.indexOf(o), at, side, o.def.name);
  };

  // ------------------------------------------------------------- team AI ("coach & shot-caller")
  type Plan = { kind: 'lanes' | 'objective' | 'siege' | 'defend'; at?: Pt; obj?: number; lane?: LaneId; until: number; members: Set<number> };
  const plans: [Plan, Plan] = [
    { kind: 'lanes', until: 0, members: new Set() },
    { kind: 'lanes', until: 0, members: new Set() },
  ];
  const power = (h: Hero, _now: number) => (h.dead ? 0 : h.pow);
  const powerNear = (side: number, p: Pt, r: number, now: number, onlyVisibleTo?: number) => powerAt(side, p.x, p.y, r, onlyVisibleTo);
  const powerAt = (side: number, x: number, y: number, r: number, onlyVisibleTo?: number): number => {
    let a = 0;
    const r2 = r * r;
    for (const h of bySide[side]) {
      if (h.dead || (h.x - x) ** 2 + (h.y - y) ** 2 > r2) continue;
      if (onlyVisibleTo !== undefined && !visible[onlyVisibleTo][h.i]) continue;
      a += h.pow;
    }
    return a;
  };
  const teamPower = (side: number, now: number) => bySide[side].reduce((a, h) => a + power(h, now), 0);

  const DEBUG = typeof process !== 'undefined' && process.env?.DEBUG_SPATIAL === '1';
  const decide = (side: 0 | 1, now: number) => {
    decide0(side, now);
    if (DEBUG && now % 60 === 0) {
      const p = plans[side];
      console.log(`${fmtTime(now)} side${side} ${p.kind}${p.lane ? ':' + p.lane : ''}${p.obj !== undefined ? ':' + objs[p.obj].def.id : ''} members=${p.members.size} alive=${bySide[side].filter((h) => !h.dead).length} gold=${Math.round(teamGold(side))} pow=${Math.round(teamPower(side, now) / 1000)}k`);
    }
  };
  const decide0 = (side: 0 | 1, now: number) => {
    const tc = g.teams[side];
    const me = bySide[side];
    const alive = me.filter((h) => !h.dead);
    const minute = now / 60;
    const lanePhase = minute < cfg.laneEnd;
    const macro = (tc.phase.macro ?? 60) / 100;
    const plan = plans[side];
    // 1. Defend a structure under siege.
    for (const s of structs) {
      if (!s.alive || s.d.team !== side || !canDamage(s)) continue;
      const enemies = bySide[1 - side].filter((h) => !h.dead && dist(h, s.d.pos) < s.d.range + 40);
      if (enemies.length >= 2 || (enemies.length >= 1 && s.d.tier >= 3)) {
        const defenders = alive.sort((a, b) => dist(a, s.d.pos) - dist(b, s.d.pos)).slice(0, Math.min(alive.length, enemies.length + 1));
        const mine = defenders.reduce((a, h) => a + power(h, now), 0);
        const theirs = powerNear(1 - side, s.d.pos, 200, now, side);
        if (mine > theirs * (1.0 - macro * 0.15) || s.d.kind === 'core' || (s.d.tier >= 4 && mine > theirs * 0.6)) {
          plans[side] = { kind: 'defend', at: s.d.pos, until: now + 12, members: new Set(defenders.map((h) => h.i)) };
          return;
        }
      }
    }
    // 2. Objectives that are up or about to spawn.
    let best: { o: Obj; score: number } | null = null;
    for (const o of objs) {
      const soon = !o.alive && o.nextAt - now < 35 && o.nextAt - now > 0 && (o.def.until === undefined || o.nextAt / 60 <= o.def.until);
      if (!o.alive && !soon) continue;
      if (o.pit < 0) continue;
      const at = objPos(o);
      const value = (o.def.major ? 3 : 1) * (lanePhase ? 0.8 : 1.2) + (o.def.kind === 'stack' ? 0.6 : 0);
      const enemyThere = powerNear(1 - side, at, 260, now, side);
      const ours = alive.reduce((a, h) => a + power(h, now) / (1 + dist(h, at) / 400), 0);
      // Conceded when the enemy is known to be there in force, or we are behind and they are all alive.
      if (enemyThere > ours * 0.8) continue;
      const enemiesAlive = bySide[1 - side].filter((h) => !h.dead).length;
      if (leadNow[side] < -0.08 && enemiesAlive >= alive.length && o.def.major) continue;
      const score = value * (ours / Math.max(1, ours + enemyThere)) * (0.7 + macro * 0.6);
      if (score > 0.55 && (!best || score > best.score)) best = { o, score };
    }
    if (best) {
      const at = objPos(best.o);
      const need = best.o.def.major || !lanePhase ? alive.length : Math.min(alive.length, 2);
      const sorted = [...alive].sort((a, b) => (b.job === 'jungle' ? 1 : 0) - (a.job === 'jungle' ? 1 : 0) || dist(a, at) - dist(b, at));
      plans[side] = { kind: 'objective', obj: objs.indexOf(best.o), at, until: now + 20, members: new Set(sorted.slice(0, need).map((h) => h.i)) };
      return;
    }
    // 3. After laning: group and siege the most promising lane (one split pusher by style).
    if (!lanePhase && alive.length >= 3) {
      const lead = goldLead(side);
      const ourPow = teamPower(side, now);
      const theirPow = teamPower(1 - side, now) || 1;
      const deadThem = bySide[1 - side].filter((h) => h.dead && h.respawnAt - now > 12).length;
      if (ourPow > theirPow * (0.85 - lead) || plan.kind === 'siege' || deadThem >= 2) {
        let laneBest: LaneId = 'mid';
        let sc = -Infinity;
        for (const lane of LANES) {
          const front = laneFront(side, lane);
          if (!front) continue;
          const ownWave = waves.filter((w) => w.side === side && w.lane === lane).reduce((a, w) => Math.max(a, w.s), 0) / map.laneLen[lane];
          const s = ownWave * 2 + (front.d.kind === 'tower' ? 0 : 1) - front.hp / front.d.hp + (lane === plan.lane ? 0.4 : 0);
          if (s > sc) {
            sc = s;
            laneBest = lane;
          }
        }
        const front = laneFront(side, laneBest);
        if (front) {
          const splitter = (g.comp[side].profile.split ?? 5) > 6 ? alive.find((h) => h.job === 'lane' && h.lane !== laneBest && h.lane !== 'mid') : undefined;
          plans[side] = { kind: 'siege', lane: laneBest, at: front.d.pos, until: now + 25, members: new Set(alive.filter((h) => h !== splitter).map((h) => h.i)) };
          return;
        }
      }
    }
    plans[side] = { kind: 'lanes', until: now + 6, members: new Set() };
  };

  // ------------------------------------------------------------- individual behaviour
  const fountainPt = (side: number) => map.fountains[side];
  const laneSpot = (h: Hero, laneOverride?: LaneId): Pt => {
    const lane = laneOverride ?? h.lane ?? 'mid';
    const L = map.laneLen[lane];
    // Behind the front-most own wave, otherwise near the own front tower.
    let s = 0;
    for (const w of waves) if (w.side === h.side && w.lane === lane && w.s > s) s = w.s;
    if (s === 0) {
      const own = structs.filter((x) => x.alive && x.d.team === h.side && x.d.lane === lane).map((x) => (h.side === 0 ? x.laneS : L - x.laneS));
      s = own.length ? Math.max(...own) + 20 : L * 0.3;
    }
    s = Math.max(0, s - 28);
    return laneAt(lane, h.side === 0 ? s : L - s);
  };
  const jungleNext = (h: Hero, now: number): Pt | null => {
    let best: Camp | null = null;
    let bd = Infinity;
    for (const c of camps) {
      if (!c.alive || (c.d.team !== h.side && !(c.d.team === -1) && now / 60 < cfg.laneEnd * 0.6)) continue;
      if (c.d.team === 1 - h.side && visible[1 - h.side][h.i]) continue;
      const d = routeDist(nav, h.node, nodeFor(c.d.pos)) + (c.d.team === h.side ? 0 : 120);
      if (d < bd) {
        bd = d;
        best = c;
      }
    }
    h.campTarget = best ? best.i : -1;
    return best ? best.d.pos : null;
  };

  /** Should `h` take this fight? Local power (as seen) and tower safety. */
  /**
   * Engagement decision against a visible enemy: 0 = avoid, 1 = trade (poke within range, no
   * chase — laning), 2 = commit. Based on visible local power, tower cover, HP and team style.
   */
  const engage = (h: Hero, e: Hero, now: number): 0 | 1 | 2 => {
    const mx = (h.x + e.x) / 2;
    const my = (h.y + e.y) / 2;
    const ours = powerAt(h.side, mx, my, 170);
    const theirs = Math.max(1, powerAt(e.side, mx, my, 170, h.side));
    let towerRisk = 1;
    for (const s of structs) if (s.alive && s.d.team === e.side && s.d.dps > 0 && (s.d.pos.x - e.x) ** 2 + (s.d.pos.y - e.y) ** 2 < (s.d.range + 15) ** 2) {
      towerRisk = 0.5;
      break;
    }
    const aggr = g.teams[h.side].aggression / 100;
    const ratio = (ours * towerRisk * (0.85 + aggr * 0.35)) / theirs;
    const myHp = h.hp / h.maxHp;
    const theirHp = e.hp / e.maxHp;
    // Fight back when committed on (cannot simply walk away from a melee range engage).
    let lastHitAt = -999;
    for (const x of bySide[e.side]) if (h.lastHit[x.i] > lastHitAt) lastHitAt = h.lastHit[x.i];
    const underAttack = now - lastHitAt <= 2;
    const fighting = h.target === e.i;
    if (myHp < 0.22) return 0;
    const laning = now / 60 < cfg.laneEnd && h.job === 'lane' && e.job === 'lane' && h.lane === e.lane;
    // Early 1-on-1 all-ins need a clear edge (summoner spells, tower, minions make them risky);
    // ganks (more allies than enemies close by) lower the bar.
    let alliesNear = 0;
    let enemiesNear = 0;
    for (const x of bySide[h.side]) if (!x.dead && (x.x - mx) ** 2 + (x.y - my) ** 2 < 150 * 150) alliesNear++;
    for (const x of bySide[e.side]) if (!x.dead && visible[h.side][x.i] && (x.x - mx) ** 2 + (x.y - my) ** 2 < 150 * 150) enemiesNear++;
    // Teams that are behind play safer; teams ahead press their advantage.
    const behind = Math.max(0, -leadNow[h.side]);
    const startBar = (laning && alliesNear <= enemiesNear ? 1.7 : 1.3) * T.caution * (1 + behind * 2.5);
    if (ratio > (fighting ? 0.95 : startBar) || (theirHp < 0.2 && ratio > 1.0) || (underAttack && ratio > 1.05)) return 2;
    if (laning && ratio > 0.75 && myHp > 0.5 && myHp >= theirHp - 0.15) return 1;
    return 0;
  };

  const stepHero = (h: Hero, now: number, dt: number) => {
    if (h.dead) {
      if (now >= h.respawnAt) {
        h.dead = false;
        h.maxHp = maxHp(h);
        h.hp = h.maxHp;
        h.x = fountainPt(h.side).x;
        h.y = fountainPt(h.side).y;
        h.node = map.fountainNode[h.side];
        h.nxt = -1;
        h.free = false;
        ev(now, 'respawn', h.i, undefined, h, h.side);
      } else if (T.buyback && now >= h.bbReadyAt) {
        // Buyback when the base is under threat late in the game.
        const threatened = structs.some((s) => s.d.team === h.side && (s.d.kind === 'base-tower' || s.d.kind === 'barracks' || s.d.kind === 'core') && canDamage(s) && bySide[1 - h.side].some((e) => !e.dead && dist(e, s.d.pos) < 160));
        const cost = (cfg.buyback?.baseCost ?? 200) + (cfg.buyback?.perMinute ?? 60) * (now / 60);
        // Teams keep buyback gold in reserve once net worth allows it.
        if (threatened && h.gold > cost * 4 && rng.chance(0.25 + h.skill.clutch / 300)) {
          h.gold -= cost;
          h.bbReadyAt = now + (cfg.buyback?.cooldownMin ?? 8) * 60;
          h.respawnAt = now;
          buybacks[h.side]++;
          if (has.has('bb')) book.add(h.pc.p.id, 'bb', 1);
          emit(g, now, h.side, 'buyback', () => `${fmtTime(now)} ${whoAs(h)} buys back to defend.`, true);
          ev(now, 'buyback', h.i, undefined, h, h.side);
        }
      }
      return;
    }
    if (now < h.stunUntil) return;
    // Recall channel.
    if (h.act === Act.Recall) {
      if (h.target >= 0 || now >= h.recallUntil) {
        if (now >= h.recallUntil && h.target < 0) {
          h.x = fountainPt(h.side).x;
          h.y = fountainPt(h.side).y;
          h.node = map.fountainNode[h.side];
          h.nxt = -1;
          h.free = false;
          h.unspent = 0;
          h.maxHp = maxHp(h);
          h.hp = h.maxHp;
          ev(now, 'recall', h.i, undefined, h, h.side);
        }
        h.act = Act.Lane;
      } else return;
    }
    // Fountain heals.
    if (dist(h, fountainPt(h.side)) < 60) {
      h.unspent = 0;
      h.maxHp = maxHp(h);
      h.hp = Math.min(h.maxHp, h.hp + h.maxHp * 0.12 * dt);
    }
    const sp = speed(h) * dt;
    // ---- combat target selection (only enemies this team can see)
    let tgt: Hero | null = h.target >= 0 ? heroes[h.target] : null;
    let mode: 0 | 1 | 2 = 0;
    if (tgt && (tgt.dead || !visible[h.side][tgt.i] || dist(h, tgt) > AGGRO * 1.6)) tgt = null;
    if (tgt) mode = engage(h, tgt, now);
    // Chase limits: give up a retreating target after a few seconds or at its tower.
    if (tgt && tgt.act === Act.Retreat) {
      if (h.chaseFrom < 0) h.chaseFrom = now;
      const underTower = structs.some((s2) => s2.alive && s2.d.team === tgt!.side && s2.d.dps > 0 && dist(s2.d.pos, tgt!) < s2.d.range);
      if (now - h.chaseFrom > 6 || underTower) {
        tgt = null;
        mode = 0;
        h.target = -1;
      }
    } else h.chaseFrom = -1;
    if (!tgt || mode === 0) {
      tgt = null;
      let bd = AGGRO;
      for (const e of bySide[1 - h.side]) {
        if (e.dead || !visible[h.side][e.i]) continue;
        const d = dist(h, e);
        if (d >= bd) continue;
        const m = engage(h, e, now);
        if (m > 0) {
          bd = d;
          tgt = e;
          mode = m;
        }
      }
    }
    // Retreat when low or when a committed fight turns.
    const low = h.hp / h.maxHp < 0.25 - (g.teams[h.side].aggression - 50) / 500;
    let startRetreat = false;
    if (!tgt && h.target >= 0 && heroes[h.target] && !heroes[h.target].dead) startRetreat = true;
    else if (tgt && low) {
      startRetreat = true;
      tgt = null;
    }
    if (startRetreat) {
      h.act = Act.Retreat;
      h.target = -1;
      // Escape spell (Flash / blink): a burst of distance toward safety when an enemy is close.
      if (now >= h.escapeAt && bySide[1 - h.side].some((e) => !e.dead && (e.x - h.x) ** 2 + (e.y - h.y) ** 2 < 60 * 60)) {
        h.escapeAt = now + T.escapeCd;
        setGoal(h, fountainPt(h.side));
        move(h, 55);
        ev(now, 'escape', h.i, undefined, h, h.side);
      }
    }
    if (tgt) {
      h.target = tgt.i;
      h.act = Act.Fight;
      const range = h.melee ? 16 : 46;
      const d = dist(h, tgt);
      if (d > range && mode === 2) {
        if (!moveDirect(h, tgt, sp)) {
          setGoal(h, tgt);
          move(h, sp);
        }
      } else if (d > range) {
        // Trading in lane: keep farming position, hit only when in range.
        const spot = laneSpot(h);
        setGoal(h, spot);
        move(h, sp);
      }
      if (dist(h, tgt) <= range + 2) {
        // Attack, abilities, crowd control, healing.
        let dmg = h.dps * dt * rng.float(0.85, 1.15) * (mode === 2 ? 1 : 0.2);
        if (mode === 2 && now >= h.ultAt) {
          h.ultAt = now + T.ultCd * rng.float(0.85, 1.15);
          dmg += h.dps * (2.2 + h.skill.clutch / 100);
          if (rng.chance(0.25 + 0.35 * (h.tankW > 1.2 ? 1 : 0.4))) tgt.stunUntil = now + 1.2;
          ev(now, 'cast', h.i, tgt.i, tgt, h.side);
        }
        if (now < execUntil[h.side] && tgt.hp / tgt.maxHp < 0.1 + execVal[h.side] * 0.2) dmg += tgt.hp;
        damageHero(h, tgt, dmg, now, 'champion');
        if (h.healW > 0.3) {
          let lowAlly: Hero | null = null;
          for (const a of bySide[h.side]) if (!a.dead && a !== h && dist(a, h) < 90 && (!lowAlly || a.hp / a.maxHp < lowAlly.hp / lowAlly.maxHp)) lowAlly = a;
          if (lowAlly && lowAlly.hp < lowAlly.maxHp) {
            const heal = Math.min(lowAlly.maxHp - lowAlly.hp, h.dps * 0.6 * h.healW * dt);
            lowAlly.hp += heal;
            book.add(h.pc.p.id, 'heal', heal);
          }
        }
      }
      return;
    }
    h.target = -1;
    h.structTarget = -1;
    if (h.act === Act.Retreat) {
      if (h.hp / h.maxHp > 0.6 || dist(h, fountainPt(h.side)) < 70) h.act = Act.Lane;
      else {
        const anyEnemy = bySide[1 - h.side].some((e) => !e.dead && visible[h.side][e.i] && dist(e, h) < 160);
        if (!anyEnemy && h.hp / h.maxHp < 0.45) {
          h.act = Act.Recall;
          h.recallUntil = now + T.recallSec;
          return;
        }
        setGoal(h, fountainPt(h.side));
        move(h, sp);
        return;
      }
    }
    // Low HP or rich: recall when nothing is around.
    if ((h.hp / h.maxHp < 0.4 || h.unspent > T.itemScale * 1.1) && !bySide[1 - h.side].some((e) => !e.dead && visible[h.side][e.i] && dist(e, h) < 110) && dist(h, fountainPt(h.side)) > 250) {
      h.act = Act.Recall;
      h.recallUntil = now + T.recallSec;
      return;
    }
    // Wards (supports and junglers).
    if (now >= h.wardAt && (h.healW > 0.3 || h.job === 'jungle' || h.job === 'roam')) {
      h.wardAt = now + 55 + rng.int(0, 30);
      const spots = [...map.pits.map((p) => p.pos), ...camps.filter((c) => c.d.team === -1).map((c) => c.d.pos)];
      const spot = spots.sort((a, b) => dist(a, h) - dist(b, h))[0];
      if (spot && dist(spot, h) < 300) {
        const w = { side: h.side, x: spot.x + rng.int(-20, 20), y: spot.y + rng.int(-20, 20), from: now, until: now + T.wardLife * (0.8 + h.skill.vision / 250) };
        if (walkable(map, w)) {
          wards.push(w);
          if (rec) rec.wards.push({ side: w.side, x: Math.round(w.x), y: Math.round(w.y), from: now, to: Math.round(w.until) });
          if (has.has('vision')) book.add(h.pc.p.id, 'vision', 1);
        }
      }
    }
    // ---- plan participation
    const plan = plans[h.side];
    if (plan.members.has(h.i) && plan.at) {
      h.act = plan.kind === 'objective' ? Act.Objective : plan.kind === 'defend' ? Act.Defend : Act.Siege;
      const at = plan.at;
      if (plan.kind === 'objective') {
        const o = objs[plan.obj!];
        setGoal(h, at);
        move(h, sp);
        if (o.alive && dist(h, at) < 70) {
          const d = h.dps * dt * (h.job === 'jungle' ? 1.4 : 1) * 1.1;
          o.hp -= d;
          h.hp -= T.objDps * dt * (1 + now / 1200) * 0.35 / (1 + 0.3 * (h.tankW - 1));
          if (h.hp <= 0) killHero(h, null, now, o.def.name);
          if (o.hp <= 0) {
            // Contested last hit: junglers' Smite timing / jungle skill decide.
            const contest = bySide[1 - h.side].filter((e) => !e.dead && dist(e, at) < 90);
            let side = h.side;
            if (contest.length) {
              const a = Math.max(...bySide[h.side].filter((x) => !x.dead && dist(x, at) < 90).map((x) => x.skill.jungle * (x.job === 'jungle' ? 1 : 0.5)), 1);
              const b = Math.max(...contest.map((x) => x.skill.jungle * (x.job === 'jungle' ? 1 : 0.5)), 1);
              if (rng.chance(clamp(0.2 * (b / (a + b)) * 2, 0.02, 0.45))) side = (1 - h.side) as 0 | 1;
            }
            takeObjective(o, side, now);
            plans[h.side] = { kind: 'lanes', until: now, members: new Set() };
          }
        }
        return;
      }
      if (plan.kind === 'siege' && plan.lane) {
        const front = laneFront(h.side, plan.lane);
        if (front) {
          const ownWaveNear = waves.some((w) => w.side === h.side && w.lane === plan.lane && (w.x - front.d.pos.x) ** 2 + (w.y - front.d.pos.y) ** 2 < (front.d.range + 60) ** 2);
          // Dive without minions only with a clear numbers advantage or late in the game.
          const aliveUs = bySide[h.side].filter((x) => !x.dead).length;
          const aliveThem = bySide[1 - h.side].filter((x) => !x.dead).length;
          const dive = aliveUs - aliveThem >= 2 || now / 60 > cfg.avgLength * 0.9;
          const stand = ownWaveNear || dive || front.d.dps === 0 ? front.d.pos : laneSpot(h, plan.lane);
          setGoal(h, stand);
          move(h, sp);
          h.structTarget = structs.indexOf(front);
          if (dist(h, front.d.pos) < Math.max(front.d.range, 45) + 20) {
            const si = h.structTarget;
            damageStruct(si, h.dps * dt * T.siegeMult, now, h, h.side);
            // Tower without minions to shoot targets the heroes.
            if (!ownWaveNear && front.d.dps > 0 && front.alive) damageHero(null, h, front.d.dps * dt * 0.6, now, front.d.name);
          }
        }
        return;
      }
      setGoal(h, at);
      move(h, sp);
      return;
    }
    // ---- default jobs
    if (h.job === 'jungle' && now / 60 < cfg.laneEnd * 1.4) {
      // Gank a visible, pushed-up enemy laner when the camp route is done or the chance is good.
      // About one gank attempt every two to three minutes, more for aggressive, skilled junglers.
      const gankChance = (0.004 + (h.skill.jungle / 100) * 0.005) * (0.7 + g.teams[h.side].aggression / 160) * dt;
      if (h.act !== Act.Roam && now > 150 && rng.chance(gankChance)) {
        const targets = bySide[1 - h.side].filter((e) => !e.dead && e.job === 'lane' && lastSeen[h.side][e.i] > now - 6);
        const prey = targets.sort((a, b) => a.hp / a.maxHp - b.hp / b.maxHp)[0];
        if (prey) {
          h.act = Act.Roam;
          h.goalPt = { x: prey.x, y: prey.y };
          h.goal = nodeFor(h.goalPt);
          emit(g, now, h.side, 'gank-start', () => `${fmtTime(now)} ${whoAs(h)} heads for the ${prey.lane ? map.laneNames[prey.lane] : 'lane'}.`);
        }
      }
      if (h.act === Act.Roam) {
        move(h, sp);
        if (h.node === h.goal) h.act = Act.Jungle;
        return;
      }
      h.act = Act.Jungle;
      const p = jungleNext(h, now);
      if (p) {
        setGoal(h, p);
        move(h, sp);
        const c = camps[h.campTarget];
        if (c && dist(h, c.d.pos) < 40) {
          c.hp -= h.dps * dt * (0.9 + h.skill.jungle / 200) * 1.6;
          h.hp -= 12 * dt * (1 + now / 900) / (1 + 0.3 * (h.tankW - 1));
          if (c.hp <= 0) {
            c.alive = false;
            c.at = now + c.d.respawn;
            giveGold(h, c.d.gold * (1 + now / 1800));
            giveXp(h, c.d.xp * (1 + now / 1800));
            if (c.d.kind === 'buff') teamBuff[h.side] += 0;
            if (has.has('cs')) book.add(h.pc.p.id, 'cs', c.d.kind === 'buff' ? 4 : 3);
            ev(now, 'camp', h.i, c.i, c.d.pos, h.side, c.d.name);
          }
        }
      } else {
        // Nothing up: shadow the mid lane.
        setGoal(h, laneSpot(h, 'mid'));
        move(h, sp);
      }
      return;
    }
    if (h.job === 'roam' && now / 60 < cfg.laneEnd) {
      // Roamers escort the carry's lane, then rotate.
      const carry = bySide[h.side].find((x) => x.job === 'lane' && x.pc.role.fclass.includes('Carry/Fragger')) ?? bySide[h.side][0];
      setGoal(h, carry.dead ? laneSpot(h, 'mid') : { x: carry.x, y: carry.y });
      h.act = Act.Roam;
      move(h, sp);
      return;
    }
    // Laners (and everyone after laning when not in a plan): farm the assigned lane; later the lane
    // with the most dangerous enemy wave.
    let lane = h.lane ?? 'mid';
    if (now / 60 >= cfg.laneEnd || h.job !== 'lane') {
      let worst = -1;
      for (const l of LANES) {
        let press = 0;
        for (const w of waves) if (w.side !== h.side && w.lane === l && w.s / map.laneLen[l] > press) press = w.s / map.laneLen[l];
        if (press > worst) {
          worst = press;
          lane = l;
        }
      }
    }
    h.act = Act.Lane;
    let spot = laneSpot(h, lane);
    if (h.hp / h.maxHp < 0.45) {
      // Hurt: play safe under the nearest own structure of the lane until the recall is safe.
      let safe: Struct | null = null;
      for (const s2 of structs) if (s2.alive && s2.d.team === h.side && s2.d.lane === lane && (!safe || dist(s2.d.pos, h) < dist(safe.d.pos, h))) safe = s2;
      if (safe) spot = safe.d.pos;
    }
    setGoal(h, spot);
    move(h, sp);
    // Farming: hit the nearest enemy wave in range.
    const enemyWave = waves.find((w) => w.side !== h.side && w.lane === lane && (w.n > 0.05 || w.big > 0) && (w.x - h.x) ** 2 + (w.y - h.y) ** 2 < 70 * 70);
    if (enemyWave) hitWave(enemyWave, h.dps * dt * 0.75 * (0.7 + h.skill.lane / 200), now, h);
    // Standing in front of an enemy tower without minions costs health.
    for (const s of structs) if (s.alive && s.d.team !== h.side && s.d.dps > 0 && (s.d.pos.x - h.x) ** 2 + (s.d.pos.y - h.y) ** 2 < s.d.range ** 2 && !waves.some((w) => w.side === h.side && w.lane === s.d.lane && (w.x - s.d.pos.x) ** 2 + (w.y - s.d.pos.y) ** 2 < (s.d.range + 20) ** 2)) damageHero(null, h, s.d.dps * dt * 0.5, now, s.d.name);
  };

  // ------------------------------------------------------------- towers vs heroes
  const towersFire = (now: number, dt: number) => {
    for (const s of structs) {
      if (!s.alive || s.d.dps <= 0) continue;
      // Aggro: a hero who attacks an allied hero inside tower range draws fire.
      let tgt: Hero | null = s.aggro >= 0 ? heroes[s.aggro] : null;
      if (tgt && (tgt.dead || dist(tgt, s.d.pos) > s.d.range)) {
        tgt = null;
        s.aggro = -1;
      }
      if (!tgt)
        for (const e of bySide[1 - s.d.team]) {
          if (e.dead || dist(e, s.d.pos) > s.d.range || e.target < 0) continue;
          const victim = heroes[e.target];
          if (victim.side === s.d.team && dist(victim, s.d.pos) < s.d.range + 20) {
            tgt = e;
            s.aggro = e.i;
            break;
          }
        }
      if (tgt) damageHero(null, tgt, s.d.dps * dt, now, s.d.name);
    }
  };

  // ------------------------------------------------------------- main loop
  /** Simulation step (seconds). Every rate below is per second × dt. */
  const dt = 2;
  const maxT = cfg.maxLength * 60;
  const reversed = [...heroes].reverse();
  for (const side of [0, 1] as const) decide(side, 0);
  for (let now = 0; winner < 0; now += dt) {
    endT = now;
    if (now >= nextWave) {
      spawnWaves(now);
      nextWave += T.waveEvery;
    }
    for (const c of camps) if (!c.alive && now >= c.at) {
      c.alive = true;
      c.hp = c.d.hp * (1 + now / 1800);
    }
    for (const s of structs) if (!s.alive && s.d.respawn && s.rebuildAt && now >= s.rebuildAt) {
      s.alive = true;
      s.hp = s.d.hp * 0.5;
      s.rebuildAt = 0;
      frontCache.clear();
    }
    spawnObjectives(now);
    for (let i = wards.length - 1; i >= 0; i--) if (wards[i].until < now) wards.splice(i, 1);
    // Passive income
    if (now >= T.firstWave) for (const h of heroes) if (!h.dead) giveGold(h, T.passiveGold * dt);
    updateVision(now);
    leadNow[0] = goldLead(0);
    leadNow[1] = -leadNow[0];
    for (const h of heroes) {
      h.dps = h.dead ? 0 : heroDps(h, now);
      h.pow = h.dead ? 0 : h.dps * (h.hp + h.maxHp * 0.3);
    }
    if (now % 4 === 0) for (const side of [0, 1] as const) if (now >= plans[side].until) decide(side, now);
    stepWaves(now, dt);
    if (winner >= 0) break;
    // Heroes act in an order that alternates sides each second (no first-mover bias).
    const order = (now / dt) % 2 === 0 ? heroes : reversed;
    for (const h of order) {
      stepHero(h, now, dt);
      if (winner >= 0) break;
    }
    if (winner >= 0) break;
    towersFire(now, dt);
    // Regeneration out of combat.
    for (const h of heroes) if (!h.dead && h.target < 0 && h.hp < h.maxHp) h.hp = Math.min(h.maxHp, h.hp + h.maxHp * 0.004 * dt);
    // Overtime safeguard: very long games end in a decisive base race.
    if (now >= maxT) {
      for (const side of [0, 1] as const) siegeBuff[side] += 0.02;
    }
    if (rec) recordFrame(now);
    if (now > maxT + 900) {
      // Unreachable in practice: force the stronger side to finish.
      winner = teamPower(0, now) >= teamPower(1, now) ? 0 : 1;
      endT = now;
    }
  }

  function recordFrame(now: number) {
    const r = rec!;
    r.frames++;
    for (const h of heroes) {
      r.hx.push(Math.round(h.x));
      r.hy.push(Math.round(h.y));
      r.hhp.push(h.dead ? 0 : Math.round((100 * h.hp) / h.maxHp));
      r.hlv.push(h.level);
      r.hst.push((h.dead ? 1 : 0) | (h.act << 1) | (h.aegis ? 64 : 0));
      r.htg.push(h.target >= 0 ? h.target : h.act === Act.Siege && h.structTarget >= 0 ? 100 + h.structTarget : -1);
    }
    for (const s of structs) r.sh.push(s.alive ? Math.max(1, Math.round((100 * s.hp) / s.d.hp)) : 0);
    for (const lane of LANES)
      for (const side of [0, 1] as const) {
        let best: Wave | null = null;
        let n = 0;
        for (const w of waves) if (w.lane === lane && w.side === side) {
          n += w.n + (w.big > 0 ? 1 : 0);
          if (!best || w.s > best.s) best = w;
        }
        r.wf.push(best ? Math.round((1000 * best.s) / map.laneLen[lane]) : -1);
        r.wn.push(Math.round(n));
      }
    for (let pi = 0; pi < map.pits.length; pi++) {
      const o = objs.find((x) => x.alive && x.pit === pi);
      r.po.push(o ? objs.indexOf(o) : -1);
      r.ph.push(o ? Math.round((100 * o.hp) / o.maxHp) : 0);
    }
    for (const c of camps) r.ca.push(c.alive ? 1 : 0);
    r.tg.push(Math.round(teamGold(0)), Math.round(teamGold(1)));
    r.tk.push(kills[0], kills[1]);
    r.ts.push(structsDown[0], structsDown[1]);
    void now;
  }

  // ------------------------------------------------------------- results
  for (const h of heroes) if (has.has('gold')) book.add(h.pc.p.id, 'gold', h.gold);
  book.finalise();
  assignRatings(
    book,
    (l) => {
      const s = l.s;
      const kda = (s.k * 3 + s.a * 1.5 + 1) / (s.d * 2 + 2);
      return kda + (s.gold ?? 0) / 4000 + (s.dmg ?? 0) / 20000 + (s.heal ?? 0) / 20000 + (s.obj ?? 0) * 0.4 + (s.towers ?? 0) * 0.3 + (s.vision ?? 0) / 25;
    },
    winner,
  );
  const goldA = teamGold(0);
  const goldB = teamGold(1);
  return {
    idx: g.gameIdx,
    winner,
    mapId: g.mapId ?? map.id,
    duration: Math.round(endT),
    score: [kills[0], kills[1]],
    teamStats: [0, 1].map((i) => ({ kills: kills[i], gold: Math.round(i === 0 ? goldA : goldB), objectives: objTaken[i], structures: structsDown[i], buybacks: buybacks[i] })),
    players: book.lines,
    draft: g.draft,
    events: g.events ?? undefined,
    replay: rec ?? undefined,
    summary: `${teamName(g, winner)} destroy the ${map.structures.find((s) => s.kind === 'core')?.name ?? 'core'} in ${fmtTime(endT)} (${kills[0]}-${kills[1]} kills)`,
  };
}

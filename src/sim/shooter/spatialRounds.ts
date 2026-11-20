/**
 * Engine B (spatial): round-based tactical shooter on real map layouts (Valorant, CS2).
 *
 * Each round is simulated in half-second steps on the map's callout graph: buy phase (economy),
 * attack plan (default / rush / split, lurker, spike carrier), defender setup and rotations driven by
 * what the defenders see, hear or learn from traps, movement along walkable routes, line-of-sight
 * duels (walls and active smokes block sight; holding an angle beats peeking; crossfires; flashes
 * blind; Valorant recon reveals), spike/bomb plant, post-plant, retake and defuse. A map ends after a
 * legal sequence of rounds with halftime and the title's overtime; the result is never chosen first.
 * Recording is a passive sink (no RNG draws), so watching, reading and instant results agree.
 */
import { shooterTimers, tacticalRules } from '../common/rules';
import { clamp } from '../../core/math';
import type { GameResult, ShooterReplay } from '../../model/types';
import type { PlayerCtx } from '../common/team';
import { assignRatings, charName, emit, StatBook, teamName, withChar, type GameContext } from '../common/engine';
import { hop, los, routeLen, sdist, walkableS, type ShooterMap, type SPt } from './map';
import { shooterMap } from './maps';
import { simulateTactical } from './tactical';

type Buy = 'pistol' | 'eco' | 'force' | 'full';
interface Wpn {
  code: number;
  name: string;
  power: number;
  range: number;
  sniper?: boolean;
}

const VAL_W: Record<string, Wpn> = {
  classic: { code: 0, name: 'Classic', power: 0.5, range: 0.5 },
  ghost: { code: 1, name: 'Ghost', power: 0.58, range: 0.6 },
  sheriff: { code: 2, name: 'Sheriff', power: 0.72, range: 0.8 },
  spectre: { code: 3, name: 'Spectre', power: 0.78, range: 0.55 },
  judge: { code: 4, name: 'Judge', power: 0.86, range: 0.3 },
  vandal: { code: 5, name: 'Vandal', power: 1, range: 1 },
  phantom: { code: 6, name: 'Phantom', power: 1, range: 0.92 },
  operator: { code: 7, name: 'Operator', power: 1.18, range: 1.35, sniper: true },
  bulldog: { code: 8, name: 'Bulldog', power: 0.86, range: 0.85 },
};
const CS_W: Record<string, Wpn> = {
  glock: { code: 10, name: 'Glock-18', power: 0.5, range: 0.5 },
  usp: { code: 11, name: 'USP-S', power: 0.56, range: 0.6 },
  deagle: { code: 12, name: 'Desert Eagle', power: 0.72, range: 0.8 },
  mac10: { code: 13, name: 'MAC-10', power: 0.76, range: 0.5 },
  mp9: { code: 14, name: 'MP9', power: 0.76, range: 0.52 },
  ak: { code: 15, name: 'AK-47', power: 1, range: 1 },
  m4: { code: 16, name: 'M4A1-S', power: 0.97, range: 0.95 },
  awp: { code: 17, name: 'AWP', power: 1.2, range: 1.4, sniper: true },
  galil: { code: 18, name: 'Galil AR', power: 0.86, range: 0.85 },
  famas: { code: 19, name: 'FAMAS', power: 0.86, range: 0.85 },
};
const WEAPON_NAMES: string[] = [];
for (const w of [...Object.values(VAL_W), ...Object.values(CS_W)]) WEAPON_NAMES[w.code] = w.name;

interface Rules {
  roundTime: number;
  plantTime: number;
  bombTime: number;
  defuseTime: number;
  smokeDur: number;
  smokeR: number;
  speed: number;
  abilities: boolean;
  /** Defender edge: set-ups, cover and utility lines the schematic layout does not draw (calibrated). */
  defEdge: number;
}
const RULES: Record<string, Rules> = {
  valorant: { roundTime: 100, plantTime: 4, bombTime: 45, defuseTime: 7, smokeDur: 15, smokeR: 40, speed: 42, abilities: true, defEdge: 1.4 },
  cs2: { roundTime: 115, plantTime: 3.2, bombTime: 40, defuseTime: 5, smokeDur: 18, smokeR: 42, speed: 42, abilities: false, defEdge: 1.3 },
};

const DT = 0.5;
/** Engagement range (map units): real sightlines are broken by doors, boxes and corners the schematic does not draw. */
const SIGHT = 380;
const DUEL_SCALE_S = 27;

interface SP {
  i: number;
  side: 0 | 1;
  pc: PlayerCtx;
  x: number;
  y: number;
  node: number;
  nxt: number;
  goal: number;
  goalPt: SPt | null;
  alive: boolean;
  hp: number;
  armor: number;
  w: Wpn;
  util: { smoke: number; flash: number; recon: number; molly: number; trap: number };
  blindUntil: number;
  revealedUntil: number;
  moving: boolean;
  task: 'hold' | 'move' | 'plant' | 'defuse' | 'post' | 'save';
  holdAt: number;
  plantUntil: number;
  defuseUntil: number;
  hasSpike: boolean;
  target: number;
  dmgBy: Float64Array;
  roundKills: number;
  diedAt: number;
  killedBy: number;
  tag: { entry: boolean; sniper: boolean; lurker: boolean; controller: boolean; initiator: boolean; sentinel: boolean };
  /** Trade window: teammates of a fresh victim are pre-aimed on this player until then. */
  tradeUntil: number;
  rating: number;
}

export function simulateTacticalSpatial(g: GameContext, record = false): GameResult {
  const map = g.mapId ? shooterMap(g.mapId) : null;
  if (!map) return simulateTactical(g);
  const cfg = tacticalRules(g.title, g.patch);
  // Round timers and the defender edge under the current patch.
  const pt = shooterTimers(g.patch);
  const R0 = RULES[g.title.id] ?? RULES.valorant;
  const R: Rules = { ...R0, roundTime: pt.roundTime ?? R0.roundTime, bombTime: pt.bombTime ?? R0.bombTime, plantTime: pt.plantTime ?? R0.plantTime, defuseTime: pt.defuseTime ?? R0.defuseTime, defEdge: R0.defEdge + (pt.defenderEdge ?? 0) * 0.6 };
  const W = g.title.id === 'cs2' ? CS_W : VAL_W;
  const { rng } = g;
  const keys = g.title.stats.map((s) => s.key);
  const book = new StatBook(g.teams, keys);
  const n = g.title.teamSize;

  // ------------------------------------------------------------------ static map analysis
  const dAtk = (v: number) => routeLen(map, map.atkSpawn, v);
  const dDef = (v: number) => routeLen(map, map.defSpawn, v);
  const sites = map.sites;
  const atkEntr = sites.map((s) => {
    const e = map.adj[s.node].filter((v) => dAtk(v) < dAtk(s.node));
    return e.length ? e : [hop(map, s.node, map.atkSpawn)];
  });
  const defEntr = sites.map((s) => {
    const e = map.adj[s.node].filter((v) => dDef(v) < dDef(s.node));
    return e.length ? e : [hop(map, s.node, map.defSpawn)];
  });
  const stageOf = (e: number) => {
    const p = hop(map, e, map.atkSpawn);
    return p >= 0 && p !== map.atkSpawn ? p : e;
  };
  const midNodes = map.nodes.filter((v) => v.kind === 'area' && /mid/i.test(v.sup)).map((v) => v.id);
  const toward = (a: SPt, b: SPt, d: number): SPt => {
    const L = sdist(a, b) || 1;
    return { x: a.x + ((b.x - a.x) / L) * d, y: a.y + ((b.y - a.y) / L) * d };
  };

  // ------------------------------------------------------------------ players
  const players: SP[] = [];
  g.teams.forEach((tc, side) => {
    tc.players.forEach((pc) => {
      const cls = (g.title.characters.find((c) => c.id === pc.charId)?.cls ?? '').toLowerCase();
      const role = pc.roleId;
      const ph = pc.phase;
      players.push({
        i: players.length,
        side: side as 0 | 1,
        pc,
        x: 0,
        y: 0,
        node: 0,
        nxt: -1,
        goal: 0,
        goalPt: null,
        alive: true,
        hp: 100,
        armor: 0,
        w: W.classic ?? W.glock,
        util: { smoke: 0, flash: 0, recon: 0, molly: 0, trap: 0 },
        blindUntil: 0,
        revealedUntil: 0,
        moving: false,
        task: 'move',
        holdAt: -1,
        plantUntil: 0,
        defuseUntil: 0,
        hasSpike: false,
        target: -1,
        dmgBy: new Float64Array(10),
        roundKills: 0,
        diedAt: -1,
        killedBy: -1,
        tag: {
          entry: role === 'duelist' || role === 'entry' || cls === 'duelist',
          sniper: role === 'awper' || (g.title.id === 'valorant' && (cls === 'sentinel' || cls === 'duelist') && (ph.aim ?? 0) >= (tc.players.reduce((a, x) => Math.max(a, x.phase.aim ?? 0), 0) - 0.01)),
          lurker: role === 'lurker' || (g.title.id === 'valorant' && role === 'flex'),
          controller: cls === 'controller' || role === 'controller' || (g.title.id === 'cs2' && role === 'support'),
          initiator: cls === 'initiator' || role === 'initiator' || (g.title.id === 'cs2' && role === 'rifler'),
          sentinel: cls === 'sentinel' || role === 'sentinel',
        },
        tradeUntil: 0,
        rating: (ph.aim ?? 60) * 0.68 + (ph.entry ?? 60) * 0.1 + (ph.clutch ?? 60) * 0.1 + (ph.movement ?? 60) * 0.12,
      });
    });
  });
  const bySide: [SP[], SP[]] = [players.filter((p) => p.side === 0), players.filter((p) => p.side === 1)];
  const skillMult = (p: SP) => Math.exp((p.rating - 65) / DUEL_SCALE_S);

  // ------------------------------------------------------------------ economy (as the round engine)
  const money: number[][] = [new Array(n).fill(cfg.startMoney), new Array(n).fill(cfg.startMoney)];
  const carried: Array<Array<Wpn | null>> = [new Array(n).fill(null), new Array(n).fill(null)];
  const streak = [0, 0];
  const score = [0, 0];
  const econSkill = g.teams.map((tc) => tc.phase.economy ?? 60);
  let attack = g.firstSide;
  let round = 0;
  const halfScore: number[][] = [];
  const sides: string[] = [];
  let otMode = false;
  let otBlockStart = [0, 0];
  let otRound = 0;

  const buyFor = (ti: number, pistol: boolean): Buy => {
    if (pistol) return 'pistol';
    const avg = money[ti].reduce((a, b) => a + b, 0) / n;
    if (avg >= cfg.fullBuy || (carried[ti].filter(Boolean).length >= 3 && avg >= cfg.fullBuy * 0.6)) return 'full';
    const smart = econSkill[ti] / 100;
    const forceChance = avg >= cfg.fullBuy * 0.55 ? 0.35 + (g.teams[ti].aggression / 100) * 0.3 - smart * 0.2 : 0.08 * (1 - smart);
    return rng.chance(clamp(forceChance, 0.02, 0.8)) ? 'force' : 'eco';
  };
  const equip = (side: 0 | 1, buy: Buy, isAttack: boolean) => {
    const team = bySide[side];
    const cs = g.title.id === 'cs2';
    team.forEach((p, k) => {
      const keep = carried[side][k];
      const cash = money[side][k];
      let w: Wpn;
      let armor = 0;
      if (buy === 'pistol') {
        w = cs ? (isAttack ? CS_W.glock : CS_W.usp) : VAL_W.classic;
        if (!cs && rng.chance(0.5)) w = VAL_W.ghost;
        armor = rng.chance(0.4) ? 25 : 0;
      } else if (keep && (buy === 'eco' || keep.power >= 0.9)) {
        w = keep;
        armor = 50;
      } else if (buy === 'full') {
        w = p.tag.sniper && cash >= cfg.fullBuy + 500 ? (cs ? CS_W.awp : VAL_W.operator) : cs ? (isAttack ? CS_W.ak : CS_W.m4) : rng.chance(0.55) ? VAL_W.vandal : VAL_W.phantom;
        armor = cs ? 100 : 50;
      } else if (buy === 'force') {
        w = cs ? (isAttack ? (rng.chance(0.5) ? CS_W.galil : CS_W.mac10) : rng.chance(0.5) ? CS_W.famas : CS_W.mp9) : rng.chance(0.5) ? VAL_W.spectre : VAL_W.bulldog;
        armor = 25;
      } else {
        w = cs ? (rng.chance(0.3) ? CS_W.deagle : isAttack ? CS_W.glock : CS_W.usp) : rng.chance(0.35) ? VAL_W.sheriff : VAL_W.classic;
        armor = 0;
      }
      const cost = buy === 'full' ? cfg.fullBuy : buy === 'force' ? cfg.fullBuy * 0.6 : buy === 'pistol' ? Math.min(cfg.startMoney, 800) : 300;
      money[side][k] = Math.max(0, money[side][k] - (keep && keep === w ? cost * 0.25 : cost));
      p.w = w;
      p.armor = armor;
      const full = buy === 'full' ? 1 : buy === 'force' ? 0.5 : buy === 'pistol' ? 0.3 : 0;
      if (R.abilities) {
        p.util = {
          smoke: p.tag.controller ? (full > 0 ? 2 : 1) : 0,
          flash: p.tag.initiator || p.tag.entry ? 1 : 0,
          recon: p.tag.initiator ? 1 : 0,
          molly: p.tag.sentinel || p.tag.controller ? 1 : 0,
          trap: p.tag.sentinel ? 1 : 0,
        };
      } else {
        p.util = {
          smoke: rng.chance(full) && (p.tag.controller || k < 2) ? 1 : 0,
          flash: rng.chance(full) ? 1 + (rng.chance(0.5) ? 1 : 0) : 0,
          recon: 0,
          molly: rng.chance(full * 0.7) ? 1 : 0,
          trap: 0,
        };
      }
    });
  };

  // ------------------------------------------------------------------ recorder
  const rec = record
    ? {
        px: [] as number[],
        py: [] as number[],
        php: [] as number[],
        pst: [] as number[],
        pwp: [] as number[],
        ptg: [] as number[],
        fr: [] as number[],
        ft: [] as number[],
        fs: [] as number[],
        fx: [] as number[],
        fy: [] as number[],
        rounds: [] as ShooterReplay['rounds'],
        utility: [] as ShooterReplay['utility'],
        events: [] as ShooterReplay['events'],
        frames: 0,
      }
    : null;
  let clock = 0; // replay time (s), continuous across rounds
  const ev = (k: string, a?: number, b?: number, at?: SPt, s?: 0 | 1, nm?: string, hs?: boolean) => {
    if (rec) rec.events.push({ t: Math.round(clock * 10) / 10, k, a, b, x: at ? Math.round(at.x) : undefined, y: at ? Math.round(at.y) : undefined, s, n: nm, hs });
  };

  // ------------------------------------------------------------------ movement
  const nearestNode = (p: SPt) => {
    let best = 0;
    let bd = Infinity;
    for (const v of map.nodes) {
      const d = (v.x - p.x) ** 2 + (v.y - p.y) ** 2;
      if (d < bd) {
        bd = d;
        best = v.id;
      }
    }
    return best;
  };
  const setGoal = (p: SP, node: number, pt?: SPt) => {
    // Standing off the route (a held spot, the spawn spread): walk back to the callout first.
    if (node !== p.goal && p.nxt < 0 && sdist(p, map.nodes[p.node]) > 3) p.nxt = p.node;
    p.goal = node;
    p.goalPt = pt && walkableS(map, pt) ? pt : null;
  };
  const step = (p: SP, dist: number) => {
    let left = dist;
    let guard = 0;
    p.moving = false;
    while (left > 0.01 && guard++ < 8) {
      if (p.nxt < 0) {
        if (p.node === p.goal) {
          const tgt = p.goalPt ?? map.nodes[p.node];
          const d = sdist(p, tgt);
          if (d > 1) {
            const k = Math.min(1, left / d);
            const nx = p.x + (tgt.x - p.x) * k;
            const ny = p.y + (tgt.y - p.y) * k;
            // Only along walkable ground (a standing spot is never reached through a wall).
            if (walkableS(map, { x: nx, y: ny }) && walkableS(map, { x: (p.x + nx) / 2, y: (p.y + ny) / 2 })) {
              p.x = nx;
              p.y = ny;
              p.moving = true;
            } else p.goalPt = null;
          }
          return;
        }
        p.nxt = hop(map, p.node, p.goal);
        if (p.nxt < 0) return;
      }
      const nd = map.nodes[p.nxt];
      const d = sdist(p, nd);
      p.moving = true;
      if (d <= left) {
        p.x = nd.x;
        p.y = nd.y;
        p.node = p.nxt;
        p.nxt = -1;
        left -= d;
      } else {
        p.x += ((nd.x - p.x) / d) * left;
        p.y += ((nd.y - p.y) / d) * left;
        left = 0;
      }
    }
  };
  const atGoal = (p: SP) => p.nxt < 0 && p.node === p.goal && (!p.goalPt || sdist(p, p.goalPt) < 3);

  // ------------------------------------------------------------------ main loop over rounds
  let mapOver = false;
  const utilUsed = { exec: false, defSmoke: false };
  let pendingFlash = false;
  while (!mapOver) {
    const def = (1 - attack) as 0 | 1;
    const atk = attack as 0 | 1;
    const pistol = cfg.economy !== 'none' && !otMode && (round === 0 || round === cfg.half);
    let bA: Buy = buyFor(atk, pistol);
    let bD: Buy = buyFor(def, pistol);
    if (otMode) bA = bD = 'full';
    equip(atk, bA, true);
    equip(def, bD, false);
    const roundStartClock = clock;
    const frameFrom = rec ? rec.frames : 0;

    // Reset players to their spawns (spread around the spawn callout).
    for (const p of players) {
      const sp = map.nodes[p.side === atk ? map.atkSpawn : map.defSpawn];
      const k = bySide[p.side].indexOf(p);
      const off = { x: sp.x + (k - 2) * 12, y: sp.y + ((k % 2) * 2 - 1) * 8 };
      const pos = walkableS(map, off) ? off : sp;
      Object.assign(p, { x: pos.x, y: pos.y, node: sp.id, nxt: -1, goal: sp.id, goalPt: null, alive: true, hp: 100, blindUntil: 0, revealedUntil: 0, tradeUntil: 0, task: 'move', holdAt: -1, plantUntil: 0, defuseUntil: 0, hasSpike: false, target: -1, roundKills: 0, diedAt: -1, killedBy: -1 });
      p.dmgBy.fill(0);
    }

    // ---- attack plan
    const tc = g.teams[atk];
    const siteIdx = rng.int(0, sites.length - 1);
    const site = sites[siteIdx];
    const entries = atkEntr[siteIdx];
    const styleAggro = tc.aggression / 100;
    const r0 = rng.next();
    const mode: 'rush' | 'default' | 'split' = r0 < 0.12 + styleAggro * 0.15 || bA === 'eco' ? 'rush' : entries.length >= 2 && r0 > 0.72 ? 'split' : 'default';
    const execAt = mode === 'rush' ? 0 : rng.float(28, 58);
    const A = bySide[atk];
    const D = bySide[def];
    const carrier = A.find((p) => !p.tag.entry && !p.tag.lurker) ?? A[0];
    carrier.hasSpike = true;
    const lurker = mode !== 'rush' ? A.find((p) => p.tag.lurker && p !== carrier) : undefined;
    const otherSite = sites.length > 1 ? sites[(siteIdx + 1) % sites.length] : site;
    const otherEntry = atkEntr[sites.indexOf(otherSite)][0];
    const planEntry = (p: SP, k: number) => (mode === 'split' ? entries[k % entries.length] : entries[0]);
    A.forEach((p, k) => {
      if (p === lurker) setGoal(p, stageOf(otherEntry));
      else if (mode === 'rush') setGoal(p, planEntry(p, k));
      else if (p.tag.entry && midNodes.length && mode === 'default') setGoal(p, midNodes[rng.int(0, midNodes.length - 1)]);
      else setGoal(p, stageOf(planEntry(p, k)));
    });
    let executing = mode === 'rush';
    // Round-level variance: strat reads, anti-strats, timing (applies to both sides' shooting).
    const swing = rng.normal(0, 0.18);
    const swingA = Math.exp(swing);
    const swingD = Math.exp(-swing);
    let spike: { state: 0 | 1 | 2 | 3 | 4; x: number; y: number; at: number; site: number } = { state: 0, x: carrier.x, y: carrier.y, at: 0, site: -1 };

    // ---- defender setup: one anchor per site, the rest on the IGL's read (mid / stack).
    const anchors = new Map<number, number>(); // site → player
    const order = [...D].sort((a, b) => Number(b.tag.sentinel) - Number(a.tag.sentinel));
    sites.forEach((s, si) => {
      const p = order.find((x) => ![...anchors.values()].includes(x.i));
      if (p) anchors.set(si, p.i);
    });
    const holdPos = (si: number, k: number): { node: number; pt: SPt } => {
      const s = sites[si];
      const e = atkEntr[si][k % atkEntr[si].length];
      return { node: s.node, pt: toward(s, map.nodes[e], 26 + (k % 2) * 14) };
    };
    const assignHold = (p: SP, si: number, k: number) => {
      const h = holdPos(si, k);
      setGoal(p, h.node, h.pt);
      p.holdAt = si;
      p.task = 'hold';
    };
    let extra = 0;
    for (const p of D) {
      const si = [...anchors.entries()].find(([, v]) => v === p.i)?.[0];
      if (si !== undefined) assignHold(p, si, 0);
      else if (midNodes.length && extra === 0) {
        // Mid / information player.
        const m = midNodes.filter((v) => dDef(v) <= dAtk(v)).sort((a, b) => dDef(a) - dDef(b))[0] ?? midNodes[0];
        setGoal(p, m);
        p.task = 'hold';
        p.holdAt = -1;
        extra++;
      } else {
        assignHold(p, rng.int(0, sites.length - 1), 1 + extra);
        extra++;
      }
    }
    // Sentinel trap on the other site's entrance; controller smoke late if attackers are seen.
    const traps: Array<{ x: number; y: number; side: 0 | 1; by: number }> = [];
    for (const p of D) if (p.util.trap > 0) {
      const si = p.holdAt >= 0 ? p.holdAt : 0;
      const e = map.nodes[atkEntr[si][0]];
      traps.push({ x: e.x, y: e.y, side: def, by: p.i });
      p.util.trap--;
      if (rec) rec.utility.push({ t: clock, kind: 'trap', side: def, x: e.x, y: e.y, r: 30, until: clock + R.roundTime, by: p.i });
    }

    const smokes: Array<{ x: number; y: number; r: number; until: number }> = [];
    const mollies: Array<{ x: number; y: number; r: number; until: number; side: 0 | 1 }> = [];
    let knownSite = -1; // defenders' read of where the attack is
    let knownAt = -1;
    let rw: 0 | 1 = def;
    let reason = 'time';
    let firstKill = true;
    let t = 0;
    const plantedSiteName = () => (spike.site >= 0 ? sites[spike.site].id : undefined);

    const kill = (killer: SP, victim: SP, hs: boolean) => {
      victim.alive = false;
      victim.hp = 0;
      victim.diedAt = t;
      victim.killedBy = killer.i;
      killer.roundKills++;
      book.add(killer.pc.p.id, 'k', 1);
      book.add(victim.pc.p.id, 'd', 1);
      if (hs) book.add(killer.pc.p.id, 'hs', 1);
      if (firstKill) {
        book.add(killer.pc.p.id, 'fk', 1);
        book.add(victim.pc.p.id, 'fd', 1);
        firstKill = false;
      }
      // Assists: teammates who damaged the victim this round (and flash assists).
      for (const q of bySide[killer.side]) if (q !== killer && victim.dmgBy[q.i] >= 25) book.add(q.pc.p.id, 'a', 1);
      if (cfg.economy !== 'none') money[killer.side][bySide[killer.side].indexOf(killer)] += cfg.killReward;
      if (victim.hasSpike) {
        victim.hasSpike = false;
        if (spike.state === 0) spike = { ...spike, state: 1, x: victim.x, y: victim.y };
      }
      // The shot gives the killer away: the victim's teammates nearby are ready to trade.
      killer.revealedUntil = clock + 2;
      killer.tradeUntil = clock + 2.5;
      ev('kill', killer.i, victim.i, victim, killer.side, killer.w.name, hs);
      emit(g, round + 1, killer.side, 'kill', () => `R${round + 1} ${Math.floor((R.roundTime - t) / 60)}:${String(Math.floor((R.roundTime - t) % 60)).padStart(2, '0')} ${withChar(g, killer.pc)} [${killer.w.name}] kills ${victim.pc.p.handle}${hs ? ' (headshot)' : ''}.`);
    };

    // ---- the round, in half-second steps
    let lastFrame = -1;
    const maxT = R.roundTime + R.bombTime + 15;
    while (t <= maxT) {
      const aliveA = A.filter((p) => p.alive);
      const aliveD = D.filter((p) => p.alive);
      // End conditions.
      if (spike.state === 2 && t >= spike.at + R.bombTime) {
        rw = atk;
        reason = 'spike';
        spike.state = 4;
        ev('explode', undefined, undefined, spike, atk, cfg.objectiveName);
        break;
      }
      if (spike.state === 3) {
        rw = def;
        reason = 'defuse';
        break;
      }
      if (!aliveD.length) {
        rw = atk;
        reason = 'elimination';
        break;
      }
      if (!aliveA.length && spike.state !== 2) {
        rw = def;
        reason = 'elimination';
        break;
      }
      if (spike.state !== 2 && t >= R.roundTime) {
        rw = def;
        reason = 'time';
        break;
      }

      // Smokes / mollies expire.
      for (let k = smokes.length - 1; k >= 0; k--) if (smokes[k].until <= clock) smokes.splice(k, 1);
      for (let k = mollies.length - 1; k >= 0; k--) if (mollies[k].until <= clock) mollies.splice(k, 1);

      // ---- attackers' plan progression
      if (!executing && t >= execAt) executing = true;
      if (executing && spike.state === 0) {
        A.forEach((p, k) => {
          if (!p.alive || p === lurker) return;
          if (p.task !== 'plant') setGoal(p, site.node, toward(site, map.nodes[planEntry(p, k)], (k % 3) * 12));
        });
        // Execute utility once: smokes on defender entrances, flash and recon on site.
        if (!utilUsed.exec) {
          utilUsed.exec = true;
          for (const p of A) {
            if (!p.alive) continue;
            if (p.util.smoke > 0) {
              for (const e of defEntr[siteIdx].slice(0, p.util.smoke)) {
                const at = toward(site, map.nodes[e], Math.min(60, sdist(site, map.nodes[e]) * 0.6));
                smokes.push({ x: at.x, y: at.y, r: R.smokeR, until: clock + R.smokeDur });
                if (rec) rec.utility.push({ t: clock, kind: 'smoke', side: atk, x: Math.round(at.x), y: Math.round(at.y), r: R.smokeR, until: clock + R.smokeDur, by: p.i });
                p.util.smoke--;
                book.add(p.pc.p.id, 'util', 1);
              }
            }
            if (p.util.recon > 0) {
              p.util.recon--;
              book.add(p.pc.p.id, 'util', 1);
              for (const d of D) if (d.alive && sdist(d, site) < 220) d.revealedUntil = clock + 4;
              if (rec) rec.utility.push({ t: clock, kind: 'recon', side: atk, x: site.x, y: site.y, r: 220, until: clock + 4, by: p.i });
            }
          }
          pendingFlash = true;
        }
      }
      if (lurker && lurker.alive && executing && t > execAt + 12) setGoal(lurker, otherSite.node);
      // Post-plant: attackers take holding positions around the site.
      if (spike.state === 2) {
        A.forEach((p, k) => {
          if (!p.alive || p.task === 'post') return;
          const e = [...atkEntr[spike.site], ...defEntr[spike.site]][k % (atkEntr[spike.site].length + defEntr[spike.site].length)];
          setGoal(p, e, toward(map.nodes[e], sites[spike.site], 15));
          p.task = 'post';
        });
      }
      // Dropped spike: nearest attacker picks it up.
      if (spike.state === 1) {
        const near = aliveA.sort((a, b) => sdist(a, spike) - sdist(b, spike))[0];
        if (near) {
          setGoal(near, nearestNode(spike), { x: spike.x, y: spike.y });
          if (sdist(near, spike) < 12) {
            near.hasSpike = true;
            spike.state = 0;
          }
        }
      }
      // Planting.
      const carrierNow = A.find((p) => p.alive && p.hasSpike);
      if (carrierNow && spike.state === 0) {
        const s = sites.find((x) => sdist(x, carrierNow) < x.r);
        const threatened = !!s && aliveD.some((d) => sdist(d, carrierNow) < s.r + 40 && los(map, d, carrierNow, smokes));
        if (s && !threatened && carrierNow.target < 0) {
          if (carrierNow.task !== 'plant') {
            carrierNow.task = 'plant';
            carrierNow.plantUntil = t + R.plantTime;
          } else if (t >= carrierNow.plantUntil) {
            spike = { state: 2, x: carrierNow.x, y: carrierNow.y, at: t, site: sites.indexOf(s) };
            carrierNow.hasSpike = false;
            carrierNow.task = 'post';
            book.add(carrierNow.pc.p.id, 'plant', 1);
            if (cfg.economy !== 'none') money[atk] = money[atk].map((m) => m + cfg.plantReward / (cfg.economy === 'money' ? n : 1));
            knownSite = spike.site;
            knownAt = t;
            ev('plant', carrierNow.i, undefined, spike, atk, s.id);
            emit(g, round + 1, atk, 'plant', () => `R${round + 1}: ${carrierNow.pc.p.handle} plants the ${cfg.objectiveName} on ${s.id}.`);
          }
        } else if (carrierNow.task === 'plant') carrierNow.task = 'move';
      }

      // ---- defenders: information → rotations; retake and defuse after the plant
      if (spike.state !== 2) {
        for (const a of aliveA) {
          for (let si = 0; si < sites.length; si++) {
            if (sdist(a, sites[si]) > 200) continue;
            const seen = aliveD.some((d) => sdist(d, a) < SIGHT && los(map, d, a, smokes));
            const heard = aliveD.some((d) => sdist(d, a) < 230) && a.moving;
            if ((seen || heard) && aliveA.filter((x) => sdist(x, sites[si]) < 260).length >= 2) {
              if (knownSite !== si) {
                knownSite = si;
                knownAt = t;
              }
            }
          }
          for (const tr of traps) if (sdist(a, tr) < 30) {
            a.revealedUntil = clock + 3;
            knownSite = sites.indexOf(sites.reduce((b, s) => (sdist(s, tr) < sdist(b, tr) ? s : b)));
            knownAt = t;
            tr.x = -999;
          }
        }
        if (knownSite >= 0) {
          const delay = 3 + (1 - (g.teams[def].phase.igl ?? 60) / 100) * 6;
          if (t - knownAt >= delay)
            for (const d of aliveD) {
              if (d.holdAt === knownSite || d.task !== 'hold') continue;
              // Anchors of other sites stay unless the read is strong; rotators and mid go.
              const isAnchor = [...anchors.values()].includes(d.i);
              if (isAnchor && rng.chance(0.85)) continue;
              assignHold(d, knownSite, 2);
            }
        }
        // Badly outnumbered on a site: give it up and play for the retake from the defender entrance.
        for (let si = 0; si < sites.length; si++) {
          const st = sites[si];
          const atkNear = aliveA.filter((a) => sdist(a, st) < 200).length;
          const defOn = aliveD.filter((d) => d.holdAt === si && sdist(d, st) < 150);
          if (atkNear - defOn.length >= 2 && defOn.length && aliveD.some((d) => los(map, d, aliveA.find((a) => sdist(a, st) < 200) ?? d, smokes))) {
            const e = defEntr[si][0];
            for (const d of defOn) if (d.target < 0 && d.task === 'hold') {
              setGoal(d, e, toward(map.nodes[e], st, -20));
              d.task = 'move';
            }
          }
        }
        // Defender controller smokes the attackers' entrance once they are known to be coming.
        if (knownSite >= 0 && !utilUsed.defSmoke) {
          const c = aliveD.find((d) => d.util.smoke > 0);
          if (c) {
            utilUsed.defSmoke = true;
            const e = map.nodes[atkEntr[knownSite][0]];
            smokes.push({ x: e.x, y: e.y, r: R.smokeR, until: clock + R.smokeDur * 0.7 });
            if (rec) rec.utility.push({ t: clock, kind: 'smoke', side: def, x: e.x, y: e.y, r: R.smokeR, until: clock + R.smokeDur * 0.7, by: c.i });
            c.util.smoke--;
            book.add(c.pc.p.id, 'util', 1);
          }
        }
      } else {
        const ps = sites[spike.site];
        const timeLeft = spike.at + R.bombTime - t;
        const grouped = aliveD.filter((d) => sdist(d, ps) < 260).length;
        for (const d of aliveD) {
          if (d.task === 'defuse' || d.task === 'save') continue;
          // Not enough time to get there and defuse: save the weapon.
          const far = routeLen(map, d.node, ps.node) / R.speed;
          if (far + R.defuseTime > timeLeft + 1) {
            d.task = 'save';
            setGoal(d, map.defSpawn);
            continue;
          }
          const e = defEntr[spike.site][0];
          if (grouped >= Math.min(2, aliveD.length) || timeLeft < R.defuseTime + 8) setGoal(d, ps.node, { x: spike.x, y: spike.y });
          else setGoal(d, e);
          d.task = 'move';
        }
        // A defuse can be heard: attackers close enough move in to stop it.
        if (aliveD.some((d) => d.task === 'defuse'))
          for (const a of aliveA) if (sdist(a, spike) < 380 && a.target < 0) setGoal(a, nearestNode(spike), { x: spike.x + 18, y: spike.y });
        // Defuse: on the spike, no attacker in sight.
        const defuser = aliveD.find((d) => sdist(d, spike) < 14 && d.target < 0);
        if (defuser) {
          if (defuser.task !== 'defuse') {
            defuser.task = 'defuse';
            defuser.defuseUntil = t + (g.title.id === 'cs2' && bD !== 'full' ? 10 : R.defuseTime);
            // Post-plant utility: a molly on the spike stops a defuse.
            const m = aliveA.find((a) => a.util.molly > 0 && sdist(a, spike) < 320);
            if (m) {
              m.util.molly--;
              book.add(m.pc.p.id, 'util', 1);
              mollies.push({ x: spike.x, y: spike.y, r: 28, until: clock + 5, side: atk });
              if (rec) rec.utility.push({ t: clock, kind: 'molly', side: atk, x: Math.round(spike.x), y: Math.round(spike.y), r: 28, until: clock + 5, by: m.i });
            }
          } else if (t >= defuser.defuseUntil) {
            spike.state = 3;
            book.add(defuser.pc.p.id, 'defuse', 1);
            ev('defuse', defuser.i, undefined, spike, def);
            emit(g, round + 1, def, 'defuse', () => `R${round + 1}: ${defuser.pc.p.handle} defuses the ${cfg.objectiveName}!`);
          }
        }
      }
      if (spike.state === 3) continue;

      // ---- flashes on the execute (blind defenders with sight of the flash point)
      if (pendingFlash) {
        pendingFlash = false;
        const f = A.find((p) => p.alive && p.util.flash > 0);
        if (f) {
          f.util.flash--;
          book.add(f.pc.p.id, 'util', 1);
          const fp = toward(site, map.nodes[entries[0]], 20);
          for (const d of D) if (d.alive && sdist(d, fp) < 170 && los(map, d, fp, []) && rng.chance(0.5)) {
            d.blindUntil = clock + 1.6;
            d.dmgBy[f.i] += 25; // flash assist credit
          }
          if (rec) rec.utility.push({ t: clock, kind: 'flash', side: atk, x: Math.round(fp.x), y: Math.round(fp.y), r: 170, until: clock + 1.6, by: f.i });
        }
      }

      // ---- movement (players in a fight stand and shoot; planters/defusers stay)
      for (const p of players) {
        if (!p.alive) continue;
        if (p.task === 'plant' || p.task === 'defuse') {
          p.moving = false;
          continue;
        }
        if (p.target >= 0 && !p.tag.entry) {
          p.moving = false;
          continue;
        }
        const inMolly = mollies.some((m) => m.side !== p.side && sdist(m, p) < m.r);
        if (inMolly) {
          p.hp -= 12 * DT;
          if (p.hp <= 0) {
            const owner = players.find((q) => q.side !== p.side && q.alive) ?? players[0];
            kill(owner, p, false);
            continue;
          }
        }
        if (atGoal(p)) {
          p.moving = false;
          continue;
        }
        step(p, R.speed * DT * (executing && p.side === atk ? 0.95 : 1));
      }

      // ---- duels: mutual line of sight within range
      for (const p of players) p.target = -1;
      for (const p of players) {
        // Planting or defusing players cannot shoot.
        if (!p.alive || clock < p.blindUntil || p.task === 'plant' || p.task === 'defuse') continue;
        let best = -1;
        let bd = SIGHT;
        for (const q of bySide[1 - p.side]) {
          if (!q.alive) continue;
          const d = sdist(p, q);
          if (d < bd && (los(map, p, q, smokes) || clock < q.revealedUntil && d < 120)) {
            bd = d;
            best = q.i;
          }
        }
        p.target = best;
      }
      const shots: Array<{ s: SP; v: SP; p: number }> = [];
      for (const s of players) {
        if (!s.alive || s.target < 0 || clock < s.blindUntil) continue;
        const v = players[s.target];
        const d = sdist(s, v);
        const eff = s.w.range * 360;
        let F = 0.9 * skillMult(s) * s.w.power * (d > eff ? (eff / d) ** 2 : 1) * (s.side === atk ? swingA : swingD * R.defEdge);
        if (!s.moving && v.moving) F *= 1.7; // holding an angle against a peek
        if (s.moving && !v.moving) F *= 0.7;
        if (s.side === def && v.side === atk && spike.state !== 2 && sites.some((x) => sdist(x, s) < x.r + 30)) F *= 1.12; // site cover and set-ups
        if (clock < v.blindUntil) F *= 2.2;
        if (clock < v.revealedUntil) F *= 1.15;
        if (s.w.sniper && d > 150) F *= 1.25;
        if (clock < v.tradeUntil) F *= 1.6; // trading a teammate: the enemy's position is known
        // Local numbers: crossfires, utility and refrags favour the side with more players close by.
        let mine = 0;
        let theirs = 0;
        for (const q of players) if (q.alive && sdist(q, v) < 260) q.side === s.side ? mine++ : theirs++;
        F *= clamp(1 + (mine - theirs) * 0.07, 0.85, 1.2);
        F *= 1 - v.armor / 400;
        F *= 0.7 + 0.6 * (1 - v.hp / 100) + 0.3; // wounded players fall faster
        shots.push({ s, v, p: 1 - Math.exp(-F * DT) });
      }
      // Resolve simultaneously: each victim dies at most once; the killer is the most decisive shot.
      const dead = new Map<number, { s: SP; m: number }>();
      for (const sh of shots) {
        const roll = rng.next();
        if (roll < sh.p) {
          const margin = sh.p - roll;
          const cur = dead.get(sh.v.i);
          if (!cur || margin > cur.m) dead.set(sh.v.i, { s: sh.s, m: margin });
        } else {
          // Chip damage from a duel that did not end.
          const dmg = Math.round(sh.p * 60 * rng.float(0.3, 1));
          sh.v.hp = Math.max(1, sh.v.hp - dmg);
          sh.v.dmgBy[sh.s.i] += dmg;
          if (keys.includes('dmg')) book.add(sh.s.pc.p.id, 'dmg', dmg);
        }
      }
      // A killer cannot die to its own victim in the same instant unless the shots were (nearly) simultaneous.
      for (const [vi, k] of dead) {
        const victim = players[vi];
        if (!k.s.alive) continue;
        const back = dead.get(k.s.i);
        if (back && back.s === victim && back.m > k.m && rng.chance(0.85)) continue;
        if (keys.includes('dmg')) book.add(k.s.pc.p.id, 'dmg', victim.hp + rng.int(0, 40));
        victim.dmgBy[k.s.i] += victim.hp;
        const hs = rng.chance(clamp(0.2 + ((k.s.pc.p.attrs[74] ?? 60) / 100) * 0.4, 0.15, 0.7));
        kill(k.s, victim, hs);
      }

      // ---- record
      if (rec && t !== lastFrame) {
        lastFrame = t;
        rec.frames++;
        for (const p of players) {
          rec.px.push(Math.round(p.x));
          rec.py.push(Math.round(p.y));
          rec.php.push(p.alive ? Math.max(1, Math.round(p.hp)) : 0);
          rec.pst.push((p.hasSpike ? 1 : 0) | (p.task === 'plant' ? 2 : 0) | (p.task === 'defuse' ? 4 : 0) | (clock < p.blindUntil ? 8 : 0) | (p.moving ? 16 : 0));
          rec.pwp.push(p.w.code);
          rec.ptg.push(p.target);
        }
        rec.fr.push(round);
        rec.ft.push(Math.round(spike.state === 2 ? spike.at + R.bombTime - t : R.roundTime - t));
        rec.fs.push(spike.state);
        const carrierP = players.find((p) => p.hasSpike && p.alive);
        rec.fx.push(Math.round(carrierP ? carrierP.x : spike.x));
        rec.fy.push(Math.round(carrierP ? carrierP.y : spike.y));
      }
      t += DT;
      clock += DT;
    }
    utilUsed.exec = false;
    utilUsed.defSmoke = false;
    pendingFlash = false;

    // ---- round result, stats, economy
    score[rw]++;
    const aliveW = bySide[rw].filter((p) => p.alive);
    if (aliveW.length === 1 && bySide[1 - rw].filter((p) => p.diedAt >= 0).length >= 2) {
      const c = aliveW[0];
      // Clutch: last one standing against two or more.
      const vs = bySide[1 - rw].length;
      const lastTwo = bySide[rw].filter((p) => !p.alive).length === n - 1;
      if (lastTwo) {
        book.add(c.pc.p.id, 'clutch', 1);
        emit(g, round + 1, rw, 'clutch', () => `Round ${round + 1}: ${withChar(g, c.pc)} wins the clutch for ${teamName(g, rw)}!`, vs >= 3);
      }
    }
    for (const p of players) {
      const traded = !p.alive && p.killedBy >= 0 && players[p.killedBy].diedAt >= 0 && players[p.killedBy].diedAt - p.diedAt <= 3;
      const assisted = bySide[1 - p.side].some((v) => v.dmgBy[p.i] >= 25 && !v.alive);
      if (p.roundKills > 0 || assisted || p.alive || traded) book.add(p.pc.p.id, 'kast', 1);
      if (p.roundKills >= 3) book.add(p.pc.p.id, 'mk', 1);
      if (p.roundKills >= n) emit(g, round + 1, p.side, 'ace', () => `ACE! ${withChar(g, p.pc)} wipes out the opposition in round ${round + 1}.`, true);
    }
    ev('round', undefined, undefined, undefined, rw, reason);
    if (rec)
      rec.rounds.push({
        idx: round,
        from: frameFrom,
        to: rec.frames - 1,
        attack: atk,
        buys: [atk === 0 ? bA : bD, atk === 0 ? bD : bA],
        winner: rw,
        reason,
        site: plantedSiteName() ?? site.id,
        score: [score[0], score[1]],
        money: [Math.round(money[0].reduce((a, b) => a + b, 0)), Math.round(money[1].reduce((a, b) => a + b, 0))],
      });
    if (round === 0 || (bA === 'eco' && rw === atk) || (bD === 'eco' && rw === def) || reason === 'defuse')
      emit(g, round + 1, rw, 'round', () => `Round ${round + 1}: ${teamName(g, rw)} win${round === 0 ? ' the pistol round' : reason === 'defuse' ? ' on the defuse' : ' on an eco'} (${score[0]}-${score[1]}).`);
    if (cfg.economy !== 'none') {
      const lr = 1 - rw;
      money[rw] = money[rw].map((m) => m + cfg.winReward);
      money[lr] = money[lr].map((m) => m + cfg.lossRewards[Math.min(streak[lr], cfg.lossRewards.length - 1)]);
      streak[rw] = 0;
      streak[lr]++;
      for (const s of [0, 1] as const) {
        bySide[s].forEach((p, k) => {
          carried[s][k] = p.alive ? p.w : null; // survivors keep their weapon
          money[s][k] = Math.min(cfg.maxMoney, money[s][k]);
        });
      }
    }
    void roundStartClock;
    round++;
    // Win / half / overtime (same rules as the round engine).
    const target = cfg.roundsToWin;
    if (!otMode) {
      if (score[0] >= target || score[1] >= target) mapOver = true;
      else {
        if (score[0] === target - 1 && score[1] === target - 1) {
          otMode = true;
          otBlockStart = [...score];
          otRound = 0;
          emit(g, round, -1, 'overtime', `Overtime at ${score[0]}-${score[1]}!`, true);
          if (cfg.economy !== 'none') {
            const otMoney = cfg.economy === 'money' ? 10000 : 5000;
            money[0] = money[0].map(() => otMoney);
            money[1] = money[1].map(() => otMoney);
          }
        }
        if (round === cfg.half) {
          halfScore.push([...score]);
          sides.push(`${g.teams[attack].team.short} ${cfg.sides[0].toLowerCase()} first`);
          attack = 1 - attack;
          if (cfg.economy !== 'none') {
            money[0] = money[0].map(() => cfg.startMoney);
            money[1] = money[1].map(() => cfg.startMoney);
            carried[0] = carried[0].map(() => null);
            carried[1] = carried[1].map(() => null);
            streak[0] = streak[1] = 0;
          }
          emit(g, round, -1, 'half', `Halftime: ${teamName(g, 0)} ${score[0]}-${score[1]} ${teamName(g, 1)}. Sides switch.`);
        }
      }
    } else {
      otRound++;
      if (cfg.overtime.kind === 'win-by-two') {
        attack = 1 - attack;
        if (Math.abs(score[0] - score[1]) >= 2) mapOver = true;
        if (cfg.overtime.cap && (score[0] >= cfg.overtime.cap || score[1] >= cfg.overtime.cap)) mapOver = true;
        if (otRound > 40) mapOver = true;
      } else {
        const block = cfg.overtime.rounds;
        const half = block / 2;
        if (score[0] - otBlockStart[0] >= half + 1 || score[1] - otBlockStart[1] >= half + 1) mapOver = true;
        else {
          if (otRound % half === 0) attack = 1 - attack;
          if (otRound % block === 0) {
            otBlockStart = [...score];
            emit(g, round, -1, 'overtime', `Another overtime at ${score[0]}-${score[1]}!`);
          }
          if (cfg.economy !== 'none' && otRound % half === 0) {
            money[0] = money[0].map(() => 10000);
            money[1] = money[1].map(() => 10000);
          }
        }
        if (otRound > 60) mapOver = true;
      }
    }
  }

  const winner = score[0] > score[1] ? 0 : 1;
  const totalRounds = score[0] + score[1];
  book.finalise();
  assignRatings(
    book,
    (l) => {
      const s = l.s;
      return 0.4 + s.k * 1.0 - s.d * 0.55 + s.a * 0.3 + (s.kast / Math.max(1, totalRounds)) * 2.2 + s.fk * 0.4 - s.fd * 0.2 + s.clutch * 0.9 + s.mk * 0.5 + (s.dmg ?? 0) / 1200;
    },
    winner,
  );
  const mapName = map.name;
  emit(g, totalRounds, winner as 0 | 1, 'end', `${teamName(g, winner)} take ${mapName} ${score[winner]}-${score[1 - winner]}.`, true);
  const sreplay: ShooterReplay | undefined = rec
    ? {
        v: 1,
        mapId: map.id,
        titleId: g.title.id,
        dt: DT,
        frames: rec.frames,
        players: players.map((p) => ({ side: p.side, playerId: p.pc.p.id, handle: p.pc.p.handle, char: charName(g, p.pc), roleId: p.pc.roleId })),
        px: Int16Array.from(rec.px),
        py: Int16Array.from(rec.py),
        php: Int16Array.from(rec.php),
        pst: Int16Array.from(rec.pst),
        pwp: Int16Array.from(rec.pwp),
        ptg: Int16Array.from(rec.ptg),
        fr: Int16Array.from(rec.fr),
        ft: Int16Array.from(rec.ft),
        fs: Int16Array.from(rec.fs),
        fx: Int16Array.from(rec.fx),
        fy: Int16Array.from(rec.fy),
        weapons: WEAPON_NAMES,
        rounds: rec.rounds,
        utility: rec.utility,
        events: rec.events,
      }
    : undefined;
  return {
    idx: g.gameIdx,
    winner,
    mapId: g.mapId,
    duration: Math.round(clock),
    score: [...score],
    teamStats: [0, 1].map((i) => ({ rounds: score[i], firstHalf: halfScore[0]?.[i] ?? score[i] })),
    players: book.lines,
    draft: g.draft,
    events: g.events ?? undefined,
    sides,
    engine: 'spatial',
    sreplay,
    summary: `${mapName}: ${score[0]}-${score[1]}${otMode ? ' (OT)' : ''}`,
  };
}

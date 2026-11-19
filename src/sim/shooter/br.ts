/**
 * Battle royale lobby engine (Apex Legends, PUBG, PUBG Mobile, Fortnite, Warzone, Warzone Mobile).
 *
 * One game = the whole lobby: drop-spot choice (contested drops cause early fights), loot
 * quality, shrinking zones with rotation timing, positional encounters, third-parties, respawn
 * systems (beacons / Gulag / reboots), building (Fortnite) and a final circle. Strong teams have
 * a lasting edge, but zone luck and positioning let weaker teams outlast them sometimes.
 */
import { clamp, logistic } from '../../core/math';
import { hash32, type RNG } from '../../core/rng';
import type { TitleDef } from '../../data/titles/types';
import type { GameEvent, GameResult, Patch, World } from '../../model/types';
import type { CompEval } from '../common/draft';
import { assignRatings, pickWeighted, StatBook } from '../common/engine';
import type { PlayerCtx, TeamCtx } from '../common/team';

export interface BrContext {
  world: World;
  title: TitleDef;
  rng: RNG;
  crng: RNG;
  events: GameEvent[] | null;
  teams: TeamCtx[];
  comps: CompEval[];
  gameIdx: number;
  mapId?: string;
  patch: Patch;
  pressure: number;
}

interface BrTeam {
  i: number;
  tc: TeamCtx;
  alive: PlayerCtx[];
  dead: PlayerCtx[];
  hp: number;
  loot: number;
  x: number;
  y: number;
  out: boolean;
  outAt: number;
  kills: number;
}

function poiPos(name: string): [number, number] {
  return [0.08 + (hash32(name) / 4294967296) * 0.84, 0.08 + (hash32(name + '#y') / 4294967296) * 0.84];
}

export function simulateBr(g: BrContext): GameResult {
  const cfg = g.title.br!;
  const { rng } = g;
  const keys = g.title.stats.map((s) => s.key);
  const book = new StatBook(g.teams, keys);
  const map = g.title.maps.find((m) => m.id === g.mapId) ?? g.title.maps[0];
  const pois = map?.pois ?? ['Centre'];
  const phaseSec = (cfg.avgLength * 60) / cfg.zones;
  const ev = (t: number, text: () => string, big = false) => {
    if (g.events) g.events.push({ t, team: -1, kind: 'br', text: text(), big });
  };
  const teams: BrTeam[] = g.teams.map((tc, i) => {
    // Stable preferred drop spot per team & map (teams learn "their" spots).
    let poi = hash32(tc.team.id + (map?.id ?? '')) % pois.length;
    if (tc.style === 'aggressive' && rng.chance(0.35)) poi = rng.int(0, Math.min(2, pois.length - 1));
    else if (rng.chance(0.12)) poi = rng.int(0, pois.length - 1);
    const [x, y] = poiPos(pois[poi]);
    const econ = tc.phase.economy;
    return {
      i,
      tc,
      alive: [...tc.players],
      dead: [],
      hp: 1,
      loot: clamp(0.82 + (econ / 100) * 0.16 + rng.normal(0, 0.05) + (poi < 3 ? 0.05 : 0), 0.65, 1.1),
      x: x + rng.normal(0, 0.01),
      y: y + rng.normal(0, 0.01),
      out: false,
      outAt: 0,
      kills: 0,
      poi,
    } as BrTeam & { poi: number };
  });
  const eliminationOrder: number[] = [];
  let clock = 0;

  const power = (t: BrTeam): number => {
    let s = 0;
    for (const pc of t.alive) {
      let r = pc.phase.aim * 0.52 + pc.phase.teamfight * 0.2 + pc.phase.movement * 0.14 + pc.phase.survival * 0.14;
      if (cfg.build) r = r * 0.8 + pc.phase.movement * 0.2;
      s += r;
    }
    return s * t.loot * (0.55 + 0.45 * t.hp) * (1 + g.comps[t.i].total * 0.08);
  };

  const killPlayers = (winner: BrTeam, loser: BrTeam, n: number) => {
    for (let k = 0; k < n && loser.alive.length; k++) {
      const victim = rng.pick(loser.alive);
      loser.alive = loser.alive.filter((x) => x !== victim);
      loser.dead.push(victim);
      const killer = winner.alive.length ? pickWeighted(rng, winner.alive, (pc) => pc.phase.aim) : null;
      if (killer) {
        book.add(killer.p.id, 'k', 1);
        book.add(killer.p.id, 'knocks', 1 + (rng.chance(0.3) ? 1 : 0));
        book.add(killer.p.id, 'dmg', 180 + rng.int(0, 160));
        winner.kills++;
        const helper = winner.alive.filter((x) => x !== killer);
        if (helper.length && rng.chance(0.55)) book.add(rng.pick(helper).p.id, 'a', 1);
      }
    }
    for (const pc of loser.alive) book.add(pc.p.id, 'dmg', rng.int(60, 220));
  };

  const eliminate = (t: BrTeam) => {
    if (t.out) return;
    t.out = true;
    t.outAt = clock;
    eliminationOrder.push(t.i);
    for (const pc of t.dead) if (!book.get(pc.p.id, 'surv')) book.add(pc.p.id, 'surv', clock);
  };

  let finalCircle = false;
  const fight = (a: BrTeam, b: BrTeam, edgeA: number, label: string) => {
    if (a.out || b.out || !a.alive.length || !b.alive.length) return null;
    const pa = power(a);
    const pb = power(b);
    const l = Math.log(Math.max(1, pa) / Math.max(1, pb)) * 3 + edgeA + rng.normal(0, 0.35);
    const pw = logistic(l);
    const [w, lo] = rng.chance(pw) ? [a, b] : [b, a];
    const dom = Math.abs(pw - 0.5) * 2;
    const loserLoss = finalCircle || lo.alive.length <= 1 || rng.chance(0.72 + dom * 0.2) ? lo.alive.length : Math.max(1, lo.alive.length - 1);
    const winnerLoss = Math.min(w.alive.length - 1, [0, 0].reduce((acc) => acc + (rng.chance(0.28 * (1 - dom * 0.6)) ? 1 : 0), 0));
    killPlayers(w, lo, loserLoss);
    killPlayers(lo, w, winnerLoss);
    w.hp = clamp(w.hp - rng.float(0.1, 0.35) * (1 - dom * 0.5), 0.2, 1);
    lo.hp = clamp(lo.hp - 0.4, 0.1, 1);
    if (!lo.alive.length) {
      eliminate(lo);
      ev(clock, () => `${w.tc.team.name} eliminate ${lo.tc.team.name}${label ? ` ${label}` : ''}.`, eliminationOrder.length >= g.teams.length - 4);
    }
    return w;
  };

  // ---------------------------------------------------------------- drop phase
  const byPoi = new Map<number, BrTeam[]>();
  for (const t of teams as Array<BrTeam & { poi: number }>) {
    if (!byPoi.has(t.poi)) byPoi.set(t.poi, []);
    byPoi.get(t.poi)!.push(t);
  }
  clock = 60;
  for (const [poi, ts] of byPoi) {
    if (ts.length < 2) continue;
    for (let i = 0; i + 1 < ts.length; i++) {
      if (rng.chance(0.65)) {
        const w = fight(ts[i], ts[i + 1], 0, `in a contested drop at ${pois[poi]}`);
        if (w) w.loot = Math.min(1.1, w.loot + 0.05);
      }
    }
  }

  // ---------------------------------------------------------------- zones
  let cx = 0.5 + rng.normal(0, 0.12);
  let cy = 0.5 + rng.normal(0, 0.12);
  let r = 0.6;
  const respawnPhases = cfg.respawn === 'gulag' ? 4 : cfg.respawn === 'none' ? 0 : 3;
  for (let z = 1; z <= cfg.zones; z++) {
    clock = z * phaseSec;
    const nr = 0.6 * Math.pow(0.62, z);
    cx = clamp(cx + rng.normal(0, (r - nr) * 0.6), nr, 1 - nr);
    cy = clamp(cy + rng.normal(0, (r - nr) * 0.6), nr, 1 - nr);
    r = nr;
    const alive = teams.filter((t) => !t.out);
    for (const t of alive) {
      const rot = (t.tc.phase.macro * 0.55 + t.tc.phase.igl * 0.45) / 100;
      let pull = clamp(0.35 + rot * 0.55 + (cfg.vehicles ? 0.08 : 0) + rng.normal(0, 0.12), 0.1, 1);
      if (t.tc.style === 'edge') pull *= 0.8;
      if (t.tc.style === 'center') pull = Math.min(1, pull * 1.2);
      t.x += (cx - t.x) * pull + rng.normal(0, 0.02);
      t.y += (cy - t.y) * pull + rng.normal(0, 0.02);
      const d = Math.hypot(t.x - cx, t.y - cy);
      if (d > r) {
        t.hp = clamp(t.hp - 0.12 * (d / Math.max(r, 0.02)) * (z / cfg.zones + 0.5), 0, 1);
        if (t.hp <= 0.05 && rng.chance(0.5 + z * 0.05)) {
          // Caught by the zone
          for (const pc of [...t.alive]) {
            t.alive = t.alive.filter((x) => x !== pc);
            t.dead.push(pc);
          }
          eliminate(t);
          ev(clock, () => `${t.tc.team.name} are caught by the zone.`);
        }
      } else t.hp = clamp(t.hp + 0.15, 0, 1);
    }
    // Encounters
    const live = teams.filter((t) => !t.out);
    const pairs: Array<[BrTeam, BrTeam, number]> = [];
    for (let i = 0; i < live.length; i++) {
      for (let j = i + 1; j < live.length; j++) {
        const d = Math.hypot(live[i].x - live[j].x, live[i].y - live[j].y);
        const aggr = (live[i].tc.aggression + live[j].tc.aggression) / 100;
        const p = 0.32 * Math.exp(-d / (0.08 + r * 0.5)) * aggr * (0.7 + z / cfg.zones);
        if (rng.chance(clamp(p, 0, 0.9))) pairs.push([live[i], live[j], d]);
      }
    }
    rng.shuffle(pairs);
    const fought = new Map<number, number>();
    for (const [a, b] of pairs) {
      if ((fought.get(a.i) ?? 0) >= 2 || (fought.get(b.i) ?? 0) >= 2) continue;
      const inA = Math.hypot(a.x - cx, a.y - cy) < r ? 0.2 : 0;
      const inB = Math.hypot(b.x - cx, b.y - cy) < r ? 0.2 : 0;
      const w = fight(a, b, inA - inB, '');
      fought.set(a.i, (fought.get(a.i) ?? 0) + 1);
      fought.set(b.i, (fought.get(b.i) ?? 0) + 1);
      // Third party
      if (w && !w.out) {
        const near = teams.filter((t) => !t.out && t !== w && Math.hypot(t.x - w.x, t.y - w.y) < 0.12 + r * 0.3);
        for (const tp of near) {
          if (rng.chance(0.28 * (tp.tc.aggression / 60))) {
            fight(tp, w, 0.25, 'with a third-party');
            break;
          }
        }
      }
    }
    // Respawns (beacons, Gulag, reboots)
    if (z <= respawnPhases) {
      for (const t of teams) {
        if (t.out || !t.dead.length || !t.alive.length) continue;
        for (const pc of [...t.dead]) {
          const chance = cfg.respawn === 'gulag' ? logistic((pc.phase.aim - 65) / 8) * 0.8 : 0.45;
          if (rng.chance(chance)) {
            t.dead = t.dead.filter((x) => x !== pc);
            t.alive.push(pc);
            const reviver = pickWeighted(rng, t.alive, (x) => (x.role.fclass.includes('Support/Utility') ? 2 : 1));
            if (cfg.respawn !== 'gulag') book.add(reviver.p.id, 'revives', 1);
            t.loot = Math.max(0.7, t.loot - 0.04);
          }
        }
      }
    }
    if (teams.filter((t) => !t.out).length <= 1) break;
  }
  // Final circle: last teams fight it out (no retreating any more).
  finalCircle = true;
  let guard = 0;
  while (teams.filter((t) => !t.out).length > 1 && guard++ < 400) {
    const live = teams.filter((t) => !t.out);
    rng.shuffle(live);
    clock += 30;
    fight(live[0], live[1], 0, 'in the final circle');
  }
  // Safety net: any unresolved survivors are eliminated weakest-first so placements are complete.
  const survivors = teams.filter((t) => !t.out).sort((x, y) => power(x) - power(y));
  for (const s of survivors.slice(0, -1)) {
    for (const pc of [...s.alive]) {
      s.alive = s.alive.filter((x) => x !== pc);
      s.dead.push(pc);
    }
    eliminate(s);
  }
  const champ = teams.find((t) => !t.out) ?? teams[eliminationOrder[eliminationOrder.length - 1]];
  if (champ && !champ.out) {
    champ.outAt = clock;
    for (const pc of champ.tc.players) book.add(pc.p.id, 'surv', clock);
    ev(clock, () => `${champ.tc.team.name} win the game with ${champ.kills} eliminations!`, true);
  }
  const champAlive = !!champ && !champ.out;
  const order = [...(champAlive ? [champ.i] : []), ...[...eliminationOrder].reverse().filter((i) => !champAlive || i !== champ.i)];
  const placements = order.map((i) => g.teams[i].team.id);
  const brKills: Record<string, number> = {};
  teams.forEach((t) => (brKills[t.tc.team.id] = t.kills));
  // Ensure every player has a survival time.
  for (const t of teams) for (const pc of t.tc.players) if (!book.get(pc.p.id, 'surv')) book.add(pc.p.id, 'surv', t.outAt || clock);
  book.finalise();
  assignRatings(book, (l) => {
    const place = order.indexOf(l.teamIdx);
    return 0.6 + l.s.k * 0.7 + l.s.a * 0.25 + l.s.dmg / 900 + (l.s.revives ?? 0) * 0.3 + (l.s.surv / Math.max(1, clock)) * 1.2 + Math.max(0, 6 - place) * 0.12;
  }, -1);
  return {
    idx: g.gameIdx,
    winner: -1,
    mapId: map?.id,
    duration: Math.round(clock),
    score: [],
    teamStats: teams.map((t) => ({ kills: t.kills, place: order.indexOf(t.i) + 1 })),
    players: book.lines,
    events: g.events ?? undefined,
    placements,
    brKills,
    summary: `${champ?.tc.team.name ?? '?'} win (${champ?.kills ?? 0} kills)`,
  };
}

export function brPoints(title: TitleDef, place: number, kills: number): number {
  const cfg = title.br!;
  const k = cfg.killCap ? Math.min(cfg.killCap, kills) : kills;
  return (cfg.placementPts[place - 1] ?? 0) + k * cfg.killPts;
}

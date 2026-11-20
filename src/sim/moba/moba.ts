/**
 * Engine A: title-aware 5v5 lane/objective MOBA with five parameterised adapters (MLBB, Wild
 * Rift, Honor of Kings, League of Legends, Dota 2) configured by `MobaConfig`: gold economy and
 * last hitting (denies for Dota), lanes/roles and junglers, per-title objectives with distinct
 * effects (stacks, pushes, executes, Aegis, XP runes), structure layout and core durability,
 * comeback strength, respawn growth and Dota's buyback. A game ends only when the core
 * (Base Crystal / Nexus / Ancient) falls; kills never decide a game by themselves.
 */
import { mobaMults, mobaRules } from '../common/rules';
import { clamp, logistic } from '../../core/math';
import type { RNG } from '../../core/rng';
import type { ObjectiveDef } from '../../data/titles/types';
import type { GameResult } from '../../model/types';
import type { PlayerCtx, TeamCtx } from '../common/team';
import { assignRatings, dmgWeight, emit, fmtTime, healWeight, pickWeighted, StatBook, tankWeight, teamName, withChar, type GameContext } from '../common/engine';

interface Side {
  tc: TeamCtx;
  gold: number[];
  xp: number;
  level: number;
  kills: number;
  laneDown: number[];
  baseDown: number;
  stacks: Record<string, number>;
  soul: number;
  pushUntil: number;
  pushValue: number;
  executeUntil: number;
  executeValue: number;
  aegis: boolean;
  buff: number;
  /** Minute at which each player's buyback is available again (Dota). */
  bbReadyAt: number[];
  buybacks: number;
  objectives: number;
  structures: number;
  coreDamage: number;
  dead: Set<string>;
}

const CS_VALUE = 22;

/**
 * Per-title calibration of the fast engine against the spatial battlefield simulation (and pro-scene
 * averages): `kill` scales lane kills, ganks, pick-offs and how often teamfights break out; `pace`
 * scales structure pressure (game length).
 */
const FAST_TUNE: Record<string, { kill: number; pace: number }> = {
  mlbb: { kill: 1.35, pace: 1 },
  wildrift: { kill: 1.3, pace: 1 },
  hok: { kill: 1.6, pace: 0.55 },
  lol: { kill: 0.7, pace: 1.15 },
  dota2: { kill: 0.9, pace: 1.8 },
};

export function simulateMoba(g: GameContext): GameResult {
  const cfg = mobaRules(g.title, g.patch);
  const structHp = mobaMults(g.patch).structHp;
  const { rng } = g;
  const keys = g.title.stats.map((s) => s.key);
  const book = new StatBook(g.teams, keys);
  const TUNE = FAST_TUNE[g.title.id] ?? { kill: 1, pace: 1 };
  const sides: [Side, Side] = g.teams.map((tc) => ({
    tc,
    gold: tc.players.map(() => 500),
    xp: 0,
    level: 1,
    kills: 0,
    laneDown: new Array(cfg.structures.lanes).fill(0),
    baseDown: 0,
    stacks: {},
    soul: 0,
    pushUntil: -1,
    pushValue: 0,
    executeUntil: -1,
    executeValue: 0,
    aegis: false,
    buff: 0,
    bbReadyAt: tc.players.map(() => 0),
    buybacks: 0,
    objectives: 0,
    structures: 0,
    coreDamage: 0,
    dead: new Set<string>(),
  })) as [Side, Side];

  const fightPower = (s: Side, minute: number): number => {
    const tc = s.tc;
    let base = tc.phase.teamfight * 0.6 + tc.phase.skirmish * 0.2 + tc.phase.clutch * 0.1 + tc.phase.macro * 0.1;
    const comp = g.comp[tc.idx];
    const phaseT = clamp(minute / cfg.avgLength, 0, 1.5);
    const early = (comp.profile.early ?? 5) - 5;
    const late = (comp.profile.scaling ?? comp.profile.farm ?? 5) - 5;
    const compMod = 1 + comp.total * 0.07 + (early * (1 - phaseT) + late * phaseT) * 0.008 + ((comp.profile.teamfight ?? comp.profile.fight ?? 5) - 5) * 0.004;
    let stackBonus = s.soul;
    for (const [id, n] of Object.entries(s.stacks)) {
      const od = cfg.objectives.find((o) => o.id === id);
      if (od) stackBonus += n * od.value * (g.patch.objectiveMods[id] ?? 1);
    }
    const exec = minute <= s.executeUntil ? s.executeValue : 0;
    return base * compMod * (1 + stackBonus + exec + s.buff);
  };

  const goldLead = (i: number): number => {
    const a = sides[i].gold.reduce((x, y) => x + y, 0);
    const b = sides[1 - i].gold.reduce((x, y) => x + y, 0);
    return (a - b) / Math.max(1, a + b);
  };

  // ---------------------------------------------------------------- fights
  const awardKill = (killerSide: number, victim: PlayerCtx, minute: number, participants: PlayerCtx[]) => {
    const ks = sides[killerSide];
    const vs = sides[1 - killerSide];
    const killer = pickWeighted(rng, participants, (pc) => dmgWeight(g, pc) * pc.phase.teamfight);
    book.add(killer.p.id, 'k', 1);
    for (const pc of participants) if (pc !== killer && rng.chance(0.65)) book.add(pc.p.id, 'a', 1);
    book.add(victim.p.id, 'd', 1);
    ks.kills++;
    const lead = goldLead(killerSide);
    const bounty = 300 * (1 + Math.max(0, -lead) * cfg.comeback * 4) * (1 - Math.max(0, lead) * cfg.comeback);
    const ki = ks.tc.players.indexOf(killer);
    ks.gold[ki] += bounty * 0.6;
    participants.forEach((pc) => (ks.gold[ks.tc.players.indexOf(pc)] += (bounty * 0.4) / participants.length));
    if (ks.kills + vs.kills === 1) emit(g, minute * 60, killerSide as 0 | 1, 'first-blood', () => `First blood! ${withChar(g, killer)} takes down ${withChar(g, victim)}.`);
  };

  const teamfight = (minute: number, reason: string): number => {
    const pa = fightPower(sides[0], minute) * (1 + goldLead(0) * (1.1 - cfg.comeback * 0.6));
    const pb = fightPower(sides[1], minute) * (1 + goldLead(1) * (1.1 - cfg.comeback * 0.6));
    const aggr = (sides[0].tc.aggression - sides[1].tc.aggression) / 400;
    const p = logistic(Math.log(pa / pb) * 4.5 + aggr);
    const winner = rng.chance(p) ? 0 : 1;
    const dom = Math.abs(p - 0.5) * 2;
    const w = sides[winner];
    const l = sides[1 - winner];
    const alive = (s: Side) => s.tc.players;
    let wKills = (rng.chance(Math.min(1, TUNE.kill)) ? 1 : 0) + [0, 0, 0].reduce((a) => a + (rng.chance(Math.min(0.95, (0.25 + 0.35 * dom) * TUNE.kill)) ? 1 : 0), 0);
    let lKills = [0, 0].reduce((a) => a + (rng.chance(Math.min(0.95, 0.3 * (1 - dom * 0.6) * TUNE.kill)) ? 1 : 0), 0);
    wKills = Math.min(wKills, l.tc.players.length);
    lKills = Math.min(lKills, w.tc.players.length - 1);
    if (l.aegis && wKills > 0) {
      wKills--;
      l.aegis = false;
      emit(g, minute * 60, (1 - winner) as 0 | 1, 'aegis', `${teamName(g, 1 - winner)} burn the extra life to survive a wipe.`);
    }
    const wPart = alive(w);
    const lPart = alive(l);
    const victimsL = rng.sample(lPart, wKills);
    for (const v of victimsL) awardKill(winner, v, minute, rng.sample(wPart, Math.min(wPart.length, rng.int(2, wPart.length))));
    const victimsW = rng.sample(wPart, lKills);
    for (const v of victimsW) awardKill(1 - winner, v, minute, rng.sample(lPart, Math.min(lPart.length, rng.int(1, lPart.length))));
    // damage, damage taken, healing
    for (const [s, mult] of [[w, 1.1], [l, 0.9]] as Array<[Side, number]>) {
      for (const pc of s.tc.players) {
        const base = 2200 * (0.6 + minute / cfg.avgLength);
        book.add(pc.p.id, 'dmg', base * dmgWeight(g, pc) * mult * rng.float(0.7, 1.3));
        if (keys.includes('taken')) book.add(pc.p.id, 'taken', base * 0.75 * tankWeight(g, pc) * rng.float(0.7, 1.3));
        book.add(pc.p.id, 'heal', base * 0.8 * healWeight(g, pc) * rng.float(0.6, 1.4));
      }
    }
    const ace = wKills >= l.tc.players.length;
    emit(g, minute * 60, winner as 0 | 1, 'teamfight', () => `${fmtTime(minute * 60)} ${reason ? reason + ': ' : ''}${teamName(g, winner)} win the fight ${wKills}-for-${lKills}${ace ? ' — ACE!' : ''}.`, ace || wKills >= 3);
    // Respawn windows grow later in the game -> convert fights into structures.
    let window = wKills - lKills + dom * 2 + (minute / cfg.avgLength) * cfg.respawnGrowth * 1.5 - 1;
    // Dota buyback: dead heroes of the losing side may pay to respawn when their high ground is
    // threatened or the game is late. It costs net worth, has a cooldown and blunts the push.
    const bb = cfg.buyback;
    if (bb && window > 1.5 && minute > cfg.laneEnd) {
      const threatened = l.laneDown.some((d) => d >= cfg.structures.perLane - 1) || minute > cfg.avgLength * 0.75;
      if (threatened) {
        for (const v of victimsL) {
          const vi = l.tc.players.indexOf(v);
          const cost = bb.baseCost + bb.perMinute * minute;
          if (vi < 0 || l.bbReadyAt[vi] > minute || l.gold[vi] < cost + 1500) continue;
          // Clutch judgement decides whether the buyback is spent now or saved for later.
          if (!rng.chance(0.35 + (v.base.phaseC.clutch ?? 50) / 250)) continue;
          l.gold[vi] -= cost;
          l.bbReadyAt[vi] = minute + bb.cooldownMin;
          l.buybacks++;
          book.add(v.p.id, 'bb', 1);
          window -= 0.9;
          emit(g, minute * 60, (1 - winner) as 0 | 1, 'buyback', () => `${withChar(g, v)} buys back (${Math.round(cost)} gold) to defend.`, true);
        }
      }
    }
    if (window > 1.5 && minute > cfg.laneEnd * 0.8) pushStructures(winner, minute, Math.round(window * 0.4 * TUNE.pace + (minute <= w.pushUntil ? 1 : 0)));
    return winner;
  };

  // ---------------------------------------------------------------- structures & scoring
  let winner = -1;
  let endT = 0;
  const pushStructures = (side: number, minute: number, n: number) => {
    const att = sides[side];
    const def = sides[1 - side];
    const st = cfg.structures;
    for (let k = 0; k < n && winner < 0; k++) {
      // Tougher structures under the patch (rules.moba.structHp > 1) survive some pushes.
      if (structHp !== 1 && rng.chance(Math.max(0, 1 - 1 / structHp))) continue;
      // Choose the lane with most progress (finish what you started) or weakest defence.
      const openLanes = def.laneDown.map((d, i) => ({ d, i })).filter((x) => x.d < st.perLane);
      if (openLanes.length && !(def.laneDown.some((d) => d >= st.perLane) && rng.chance(0.45))) {
        const lane = rng.weighted(openLanes, (x) => 1 + x.d * 1.5);
        def.laneDown[lane.i]++;
        att.structures++;
        const name = st.tierNames[def.laneDown[lane.i] - 1] ?? 'structure';
        const pusher = pickWeighted(rng, att.tc.players, (pc) => pc.base.phaseS.macro ?? pc.phase.macro);
        book.add(pusher.p.id, 'towers', 1);
        if (keys.includes('siege')) att.tc.players.forEach((pc) => book.add(pc.p.id, 'siege', rng.int(2500, 6000)));
        emit(g, minute * 60, side as 0 | 1, 'structure', () => `${teamName(g, side)} destroy the ${cfg.lanes[lane.i]?.name ?? 'lane'} ${name}.`, def.laneDown[lane.i] === st.perLane);
        continue;
      }
      if (def.laneDown.some((d) => d >= st.perLane)) {
        if (def.baseDown < st.base) {
          def.baseDown++;
          att.structures++;
          emit(g, minute * 60, side as 0 | 1, 'structure', `${teamName(g, side)} take down a ${st.baseName}.`, true);
        } else {
          def.coreDamage++;
          if (def.coreDamage >= (st.coreHits ?? 1)) {
            winner = side;
            endT = minute * 60;
            emit(g, minute * 60, side as 0 | 1, 'core', `${teamName(g, side)} destroy the ${st.coreName} and win the game!`, true);
          } else emit(g, minute * 60, side as 0 | 1, 'core-hit', `${teamName(g, side)} damage the ${st.coreName}!`);
        }
      }
    }
  };

  // ---------------------------------------------------------------- objectives
  const nextSpawn: Record<string, number> = {};
  const spawned: Record<string, number> = {};
  for (const o of cfg.objectives) nextSpawn[o.id] = o.spawn;

  const takeObjective = (o: ObjectiveDef, side: number, minute: number) => {
    const s = sides[side];
    const mod = g.patch.objectiveMods[o.id] ?? 1;
    s.objectives++;
    const jg = s.tc.players.find((pc) => cfg.junglers.includes(pc.roleId)) ?? s.tc.players[0];
    book.add(jg.p.id, 'obj', 1);
    switch (o.kind) {
      case 'gold':
        s.gold = s.gold.map((x) => x + o.value * mod);
        break;
      case 'stack': {
        s.stacks[o.id] = (s.stacks[o.id] ?? 0) + 1;
        if (o.maxStacks && s.stacks[o.id] === o.maxStacks && o.soul) {
          s.soul += o.soul * mod;
          emit(g, minute * 60, side as 0 | 1, 'soul', `${teamName(g, side)} complete their ${o.name} set — a permanent power spike.`, true);
        }
        break;
      }
      case 'push':
      case 'siege':
        s.pushUntil = minute + (o.kind === 'push' ? 3 : 1.5);
        s.pushValue = o.value * mod;
        s.buff = Math.max(s.buff, o.kind === 'push' ? o.value * mod * 0.15 : 0);
        pushStructures(side, minute, o.kind === 'push' ? 1 + Math.round(o.value * mod * 4 * rng.float(0.5, 1.3)) : minute > cfg.laneEnd && rng.chance(o.value * mod * 1.6) ? 1 : 0);
        break;
      case 'aegis':
        s.aegis = true;
        break;
      case 'execute':
        s.executeUntil = minute + 3;
        s.executeValue = o.value * mod;
        break;
      case 'xp':
        s.xp += 500 * (1 + minute / 10);
        s.gold = s.gold.map((x) => x + 120);
        break;
      case 'buff':
        s.buff += o.value * mod * 0.5;
        break;
      case 'shield':
        s.buff += o.value * 0.1;
        break;
    }
    emit(g, minute * 60, side as 0 | 1, 'objective', () => `${fmtTime(minute * 60)} ${teamName(g, side)} secure ${o.name}${jg ? ` (${jg.p.handle})` : ''}.`, !!o.major);
  };

  const contestObjective = (o: ObjectiveDef, minute: number) => {
    const ctrl = (s: Side) => s.tc.phase.jungle * 0.32 + s.tc.phase.macro * 0.25 + s.tc.phase.vision * 0.2 + s.tc.phase.skirmish * 0.23;
    const diff = (ctrl(sides[0]) - ctrl(sides[1])) / 10 + goldLead(0) * 2.2 + (g.comp[0].total - g.comp[1].total) * 0.5;
    const fightChance = (0.2 + (sides[0].tc.aggression + sides[1].tc.aggression) / 500 + (o.major ? 0.25 : 0) - Math.abs(diff) * 0.08) * Math.min(1, TUNE.kill);
    if (rng.chance(clamp(fightChance, 0.1, 0.85))) {
      const w = teamfight(minute, `Fight at ${o.name}`);
      if (winner >= 0) return;
      takeObjective(o, rng.chance(0.88) ? w : 1 - w, minute);
    } else {
      takeObjective(o, rng.chance(logistic(diff)) ? 0 : 1, minute);
    }
  };

  // ---------------------------------------------------------------- main loop
  const tickMin = cfg.tickSec / 60;
  const csValue = CS_VALUE;
  const laneRoles = cfg.lanes.map((l) => l.roles);
  for (let t = cfg.tickSec; winner < 0; t += cfg.tickSec) {
    const minute = t / 60;
    endT = t;
    // Income & laning
    for (let i = 0; i < 2; i++) {
      const s = sides[i];
      s.tc.players.forEach((pc, pi) => {
        const eff = 0.85 + 0.3 * ((pc.base.phaseC.economy ?? 50) / 100);
        const roleShare = pc.role.fclass.includes('Carry/Fragger') ? 1.25 : pc.role.fclass.includes('Support/Utility') ? 0.6 : 1;
        const inc = cfg.gpm * tickMin * eff * roleShare;
        s.gold[pi] += inc;
        if (keys.includes('cs')) book.add(pc.p.id, 'cs', (inc * 0.65) / csValue);
      });
      if (keys.includes('vision')) for (const pc of s.tc.players) book.add(pc.p.id, 'vision', tickMin * (pc.role.phases.vision ?? 0.05) * 6 * (pc.phase.vision / 70));
    }
    if (minute <= cfg.laneEnd) {
      laneRoles.forEach((roles, li) => {
        const la = sides[0].tc.players.filter((pc) => roles.includes(pc.roleId));
        const lb = sides[1].tc.players.filter((pc) => roles.includes(pc.roleId));
        if (!la.length || !lb.length) return;
        const ra = la.reduce((a, pc) => a + pc.phase.laning, 0) / la.length;
        const rb = lb.reduce((a, pc) => a + pc.phase.laning, 0) / lb.length;
        const jgA = sides[0].tc.phase.jungle;
        const jgB = sides[1].tc.phase.jungle;
        const d = (ra - rb) / 9 + (jgA - jgB) / 40 + (g.comp[0].counter - g.comp[1].counter) * 0.25;
        const shift = Math.tanh(d) * 0.14;
        for (const pc of la) {
          const i = sides[0].tc.players.indexOf(pc);
          sides[0].gold[i] += cfg.gpm * tickMin * shift;
          if (cfg.denies && keys.includes('dn') && rng.chance(0.5 + shift)) book.add(pc.p.id, 'dn', rng.int(0, 2));
        }
        for (const pc of lb) {
          const i = sides[1].tc.players.indexOf(pc);
          sides[1].gold[i] -= cfg.gpm * tickMin * shift;
          if (cfg.denies && keys.includes('dn') && rng.chance(0.5 - shift)) book.add(pc.p.id, 'dn', rng.int(0, 2));
        }
        // Solo kill chance
        const aggr = (sides[0].tc.aggression + sides[1].tc.aggression) / 100;
        if (rng.chance(0.018 * tickMin * 2 * (1 + Math.abs(d) * 1.5) * aggr * TUNE.kill)) {
          const ws = rng.chance(logistic(d * 1.4)) ? 0 : 1;
          const victim = rng.pick(ws === 0 ? lb : la);
          awardKill(ws, victim, minute, ws === 0 ? la : lb);
        }
      });
      // Ganks
      for (let i = 0; i < 2 && minute >= 2; i++) {
        const s = sides[i];
        const o = sides[1 - i];
        const jg = s.tc.players.find((pc) => cfg.junglers.includes(pc.roleId));
        if (!jg) continue;
        const gankRate = 0.08 * tickMin * 2 * (0.6 + (jg.base.phaseS.jungle ?? jg.phase.jungle) / 120) * (0.7 + s.tc.aggression / 160) * TUNE.kill;
        if (!rng.chance(gankRate)) continue;
        const targets = o.tc.players.filter((pc) => !cfg.junglers.includes(pc.roleId));
        if (!targets.length) continue;
        const victim = rng.pick(targets);
        const laner = s.tc.players.find((pc) => cfg.lanes.some((l) => l.roles.includes(pc.roleId) && l.roles.includes(victim.roleId))) ?? jg;
        const succ = logistic((jg.phase.jungle + laner.phase.laning - victim.phase.vision * 0.6 - o.tc.phase.vision * 0.4 - victim.phase.laning * 0.6) / 9 + g.comp[i].total * 0.4 + 0.15);
        if (rng.chance(succ)) {
          awardKill(i, victim, minute, [jg, laner]);
          emit(g, t, i as 0 | 1, 'gank', () => `${withChar(g, jg)} ganks ${victim.p.handle}.`);
        } else if (rng.chance(0.3)) {
          awardKill(1 - i, jg, minute, [victim]);
          emit(g, t, (1 - i) as 0 | 1, 'counter-gank', () => `${victim.p.handle} punishes a failed gank by ${jg.p.handle}.`);
        }
      }
    }
    // Objectives
    for (const o of cfg.objectives) {
      if (winner >= 0) break;
      if (o.until !== undefined && minute > o.until) continue;
      if (minute < nextSpawn[o.id]) continue;
      if (o.maxSpawns !== undefined && (spawned[o.id] ?? 0) >= o.maxSpawns) continue;
      if (!rng.chance(0.55)) continue;
      contestObjective(o, minute);
      spawned[o.id] = (spawned[o.id] ?? 0) + 1;
      nextSpawn[o.id] = minute + o.respawn;
    }
    if (winner >= 0) break;
    // Mid/late game skirmishes & fights
    if (minute > cfg.laneEnd) {
      const aggr = (sides[0].tc.aggression + sides[1].tc.aggression) / 100;
      if (rng.chance(0.17 * tickMin * 2 * aggr * TUNE.kill)) {
        // pick-off
        const sa = sides[0].tc.phase.skirmish * (1 + g.comp[0].total * 0.1) * (1 + ((g.comp[0].profile.pick ?? 5) - 5) * 0.01);
        const sb = sides[1].tc.phase.skirmish * (1 + g.comp[1].total * 0.1) * (1 + ((g.comp[1].profile.pick ?? 5) - 5) * 0.01);
        const w = rng.chance(logistic((sa - sb) / 9 + goldLead(0) * 1.6)) ? 0 : 1;
        const victim = rng.pick(sides[1 - w].tc.players);
        awardKill(w, victim, minute, rng.sample(sides[w].tc.players, 2));
        emit(g, t, w as 0 | 1, 'pick', () => `${fmtTime(t)} ${teamName(g, w)} catch ${victim.p.handle} out of position.`);
        if (rng.chance(0.25)) pushStructures(w, minute, 1);
      }
      if (winner < 0 && rng.chance(0.14 * tickMin * 2 * aggr * (1 + minute / cfg.avgLength) * TUNE.kill)) teamfight(minute, '');
      // Lane pressure / split pushing.
      for (let i = 0; i < 2 && winner < 0; i++) {
        const s = sides[i];
        const pressure = (s.tc.phase.macro - sides[1 - i].tc.phase.macro) / 25 + goldLead(i) * 2.5 + ((g.comp[i].profile.split ?? 5) - 5) * 0.05 + (minute <= s.pushUntil ? s.pushValue * 2 : 0);
        if (rng.chance(clamp((0.42 * tickMin / cfg.avgLength) * (1 + pressure) * TUNE.pace, 0, 0.6))) pushStructures(i, minute, 1);
      }
    }
    // Overtime safeguard: sudden-death fights decide very long games.
    if (minute >= cfg.maxLength && winner < 0) {
      const w = teamfight(minute, 'Decisive late-game fight');
      if (winner < 0) pushStructures(w, minute, 6);
    }
  }

  // Final gold / net worth
  sides.forEach((s) => {
    s.tc.players.forEach((pc, pi) => {
      if (keys.includes('gold')) book.add(pc.p.id, 'gold', s.gold[pi]);
    });
  });
  book.finalise();
  assignRatings(
    book,
    (l) => {
      const s = l.s;
      const kda = (s.k * 3 + s.a * 1.5 + 1) / (s.d * 2 + 2);
      const econ = (s.gold ?? 0) / 1000;
      return kda + econ * 0.25 + (s.dmg ?? 0) / 15000 + (s.heal ?? 0) / 20000 + (s.obj ?? 0) * 0.4 + (s.towers ?? 0) * 0.3 + (s.vision ?? 0) / 40;
    },
    winner,
  );
  const goldA = sides[0].gold.reduce((a, b) => a + b, 0);
  const goldB = sides[1].gold.reduce((a, b) => a + b, 0);
  const scoreArr = [sides[0].kills, sides[1].kills];
  return {
    idx: g.gameIdx,
    winner,
    mapId: g.mapId,
    duration: Math.round(endT),
    score: scoreArr,
    teamStats: sides.map((s, i) => ({ kills: s.kills, gold: Math.round(i === 0 ? goldA : goldB), objectives: s.objectives, structures: s.structures, buybacks: s.buybacks })),
    players: book.lines,
    draft: g.draft,
    events: g.events ?? undefined,
    summary: `${teamName(g, winner)} destroy the ${cfg.structures.coreName} in ${fmtTime(endT)} (${scoreArr[0]}-${scoreArr[1]} kills)`,
  };
}

export type { RNG };

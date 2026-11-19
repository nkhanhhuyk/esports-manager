/**
 * Arena / military shooter engine (Call of Duty, Call of Duty: Mobile).
 * Modes: Hardpoint (rotating hills, respawns, score target), Search & Destroy (one-life rounds),
 * Control (attack/defend with life pools) and Domination (three flags).
 */
import { clamp, logistic } from '../../core/math';
import type { GameResult } from '../../model/types';
import { assignRatings, emit, pickWeighted, StatBook, teamName, withChar, type GameContext } from '../common/engine';
import type { PlayerCtx } from '../common/team';

export function simulateArena(g: GameContext): GameResult {
  const { rng } = g;
  const mode = g.title.modes.find((m) => m.id === g.modeId) ?? g.title.modes[0];
  const keys = g.title.stats.map((s) => s.key);
  const book = new StatBook(g.teams, keys);
  const gun = g.teams.map((tc) => tc.phase.aim * 0.6 + tc.phase.movement * 0.2 + tc.phase.teamfight * 0.2);
  const obj = g.teams.map((tc) => tc.phase.objective * 0.5 + tc.phase.macro * 0.25 + tc.phase.igl * 0.25);
  const comp = [g.comp[0].total, g.comp[1].total];
  const gunEdge = (gun[0] - gun[1]) / 15 + (comp[0] - comp[1]) * 0.3;
  const objEdge = (obj[0] - obj[1]) / 16;

  const gunfights = (count: number, edge: number, t: number) => {
    for (let k = 0; k < count; k++) {
      const w = rng.chance(logistic(edge + rng.normal(0, 0.3))) ? 0 : 1;
      const killer = pickWeighted(rng, g.teams[w].players, (pc) => pc.phase.aim * (pc.role.fclass.includes('Carry/Fragger') ? 1.3 : 1));
      const victim = rng.pick(g.teams[1 - w].players);
      book.add(killer.p.id, 'k', 1);
      book.add(victim.p.id, 'd', 1);
      book.add(killer.p.id, 'dmg', 100 + rng.int(20, 90));
      if (rng.chance(0.5)) book.add(rng.pick(g.teams[1 - w].players).p.id, 'dmg', rng.int(20, 80));
    }
    void t;
  };

  const score = [0, 0];
  let t = 0;
  let summary = '';
  if (mode.kind === 'hardpoint') {
    const target = mode.params.target ?? 250;
    const hill = mode.params.hillSec ?? 60;
    let h = 0;
    while (score[0] < target && score[1] < target && h < 40) {
      h++;
      // Rotation advantage decides who sets up first on the new hill.
      const rot = logistic(objEdge + rng.normal(0, 0.4));
      const ctrl = clamp(logistic(gunEdge * 0.9 + (rot - 0.5) * 1.2 + rng.normal(0, 0.65)), 0.02, 0.98);
      const a = Math.round(hill * ctrl * 0.88);
      const b = Math.round(hill * (1 - ctrl) * 0.88);
      score[0] = Math.min(target, score[0] + a);
      score[1] = Math.min(target, score[1] + b);
      for (const i of [0, 1]) {
        const secs = i === 0 ? a : b;
        for (const pc of g.teams[i].players) book.add(pc.p.id, 'hptime', (secs * (pc.role.phases.objective ?? 0.1) * 4) / g.teams[i].players.length + secs / g.teams[i].players.length / 2);
      }
      gunfights(rng.int(7, 12), gunEdge, t);
      t += hill;
      if (h % 4 === 0) emit(g, t, (score[0] >= score[1] ? 0 : 1) as 0 | 1, 'hill', () => `Hill ${h}: ${teamName(g, 0)} ${score[0]} - ${score[1]} ${teamName(g, 1)}.`);
    }
    summary = `${mode.name}: ${score[0]}-${score[1]}`;
  } else if (mode.kind === 'snd') {
    const need = mode.params.roundsToWin ?? 6;
    let attack = g.firstSide;
    let round = 0;
    while (score[0] < need && score[1] < need && round < 2 * need + 2) {
      round++;
      const alive: PlayerCtx[][] = [[...g.teams[0].players], [...g.teams[1].players]];
      let planted = false;
      let first = true;
      while (alive[0].length && alive[1].length) {
        const a = pickWeighted(rng, alive[attack], (pc) => pc.phase.entry + pc.phase.aim);
        const d = pickWeighted(rng, alive[1 - attack], (pc) => pc.phase.aim + pc.phase.clutch);
        const l = (a.phase.aim * 0.7 + a.phase.entry * 0.3 - (d.phase.aim * 0.7 + d.phase.clutch * 0.3)) / 15 + (alive[attack].length - alive[1 - attack].length) * 0.18 - 0.08 + (planted ? 0.3 : 0);
        const aw = rng.chance(logistic(l));
        const [ks, killer, victim] = aw ? [attack, a, d] : [1 - attack, d, a];
        alive[1 - ks] = alive[1 - ks].filter((x) => x !== victim);
        book.add(killer.p.id, 'k', 1);
        book.add(victim.p.id, 'd', 1);
        book.add(killer.p.id, 'dmg', 150);
        if (first) {
          book.add(killer.p.id, 'fb', 1);
          first = false;
        }
        if (!planted && alive[attack].length && rng.chance(0.4)) {
          planted = true;
          book.add(pickWeighted(rng, alive[attack], (pc) => pc.phase.objective).p.id, 'plant', 1);
        }
      }
      let rw = alive[attack].length ? attack : 1 - attack;
      if (!alive[attack].length && planted && rng.chance(0.15)) rw = attack;
      else if (rw !== attack && planted && alive[1 - attack].length) book.add(rng.pick(alive[1 - attack]).p.id, 'defuse', 1);
      score[rw]++;
      if (alive[rw].length === 1) {
        const hero = alive[rw][0];
        emit(g, round, rw as 0 | 1, 'clutch', () => `Round ${round}: ${withChar(g, hero)} clutches it for ${teamName(g, rw)}.`);
      }
      attack = 1 - attack;
    }
    summary = `${mode.name}: ${score[0]}-${score[1]}`;
  } else if (mode.kind === 'ctrl') {
    const need = mode.params.roundsToWin ?? 3;
    let attack = g.firstSide;
    let round = 0;
    while (score[0] < need && score[1] < need && round < 2 * need + 1) {
      round++;
      const l = (attack === 0 ? 1 : -1) * (gunEdge * 0.8 + objEdge * 0.5) - 0.25 + rng.normal(0, 0.3);
      const aw = rng.chance(logistic(l));
      const rw = aw ? attack : 1 - attack;
      score[rw]++;
      gunfights(rng.int(25, 45), gunEdge, t);
      if (aw) for (const pc of g.teams[attack].players) if (rng.chance(0.4)) book.add(pc.p.id, 'caps', 1);
      emit(g, round, rw as 0 | 1, 'round', () => `Control round ${round}: ${teamName(g, rw)} ${aw ? 'capture both zones' : 'hold the defence'} (${score[0]}-${score[1]}).`);
      attack = 1 - attack;
    }
    summary = `${mode.name}: ${score[0]}-${score[1]}`;
  } else {
    // Domination
    const target = mode.params.target ?? 150;
    let tick = 0;
    while (score[0] < target && score[1] < target && tick < 80) {
      tick++;
      const flagsA = [0, 1, 2].filter(() => rng.chance(logistic(gunEdge * 0.7 + objEdge * 0.6 + rng.normal(0, 0.4)))).length;
      score[0] = Math.min(target, score[0] + flagsA * 2);
      score[1] = Math.min(target, score[1] + (3 - flagsA) * 2);
      if (rng.chance(0.25)) {
        const capSide = flagsA >= 2 ? 0 : 1;
        book.add(pickWeighted(rng, g.teams[capSide].players, (pc) => pc.phase.objective).p.id, 'caps', 1);
      }
      if (tick % 2 === 0) gunfights(rng.int(2, 5), gunEdge, t);
    }
    summary = `${mode.name}: ${score[0]}-${score[1]}`;
  }
  const winner = score[0] > score[1] ? 0 : score[1] > score[0] ? 1 : rng.chance(logistic(gunEdge)) ? 0 : 1;
  emit(g, t, winner as 0 | 1, 'end', `${teamName(g, winner)} win ${mode.name} on ${g.title.maps.find((m) => m.id === g.mapId)?.name ?? 'the map'} (${score[winner]}-${score[1 - winner]}).`, true);
  book.finalise();
  assignRatings(book, (l) => 0.5 + l.s.k * 0.9 - l.s.d * 0.6 + l.s.dmg / 1800 + (l.s.hptime ?? 0) / 60 + (l.s.caps ?? 0) * 0.4 + (l.s.plant ?? 0) * 0.5 + (l.s.defuse ?? 0) * 0.6 + (l.s.fb ?? 0) * 0.5, winner);
  return {
    idx: g.gameIdx,
    winner,
    mapId: g.mapId,
    modeId: mode.id,
    duration: Math.max(t, 600),
    score: [...score],
    teamStats: [0, 1].map((i) => ({ score: score[i] })),
    players: book.lines,
    draft: g.draft,
    events: g.events ?? undefined,
    summary,
  };
}

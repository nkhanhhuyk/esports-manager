/**
 * Round-based tactical shooter engine (Valorant, Valorant Mobile, CS2, Rainbow Six Siege).
 *
 * Each round: buy decisions (credits/money economies or none), utility & information battle,
 * a chain of duels with trades and man-advantage effects, objective plant / retake / defuse,
 * clutch tracking, economy update, halftime side swap and title-specific overtime.
 */
import { clamp, logistic } from '../../core/math';
import type { GameResult } from '../../model/types';
import { assignRatings, emit, pickWeighted, StatBook, teamName, withChar, type GameContext } from '../common/engine';
import type { PlayerCtx } from '../common/team';

type Buy = 'pistol' | 'eco' | 'force' | 'full';

/** Tunable: rating points per logit unit in a single duel. */
export const DUEL_SCALE = 26;

export function simulateTactical(g: GameContext): GameResult {
  const cfg = g.title.tactical!;
  const { rng } = g;
  const keys = g.title.stats.map((s) => s.key);
  const book = new StatBook(g.teams, keys);
  const map = g.title.maps.find((m) => m.id === g.mapId);
  const sideBias = map?.sideBias ?? 0;
  const n = g.title.teamSize;
  const money: number[][] = [new Array(n).fill(cfg.startMoney), new Array(n).fill(cfg.startMoney)];
  const carried: number[][] = [new Array(n).fill(0), new Array(n).fill(0)];
  const streak = [0, 0];
  const score = [0, 0];
  let attack = g.firstSide; // team index currently attacking
  let round = 0;
  const halfScore: number[][] = [];
  const sides: string[] = [];
  let otMode = false;
  let otBlockStart = [0, 0];
  let otRound = 0;

  const comp = [g.comp[0].total, g.comp[1].total];
  const teamUtil = g.teams.map((tc) => tc.phase.utility * cfg.utilityWeight);
  const teamInfo = g.teams.map((tc) => tc.phase.vision);
  const coord = g.teams.map((tc) => tc.players.reduce((a, pc) => a + pc.p.attrs[44], 0) / Math.max(1, tc.players.length));
  const igl = g.teams.map((tc) => tc.phase.igl);
  const econSkill = g.teams.map((tc) => tc.phase.economy);

  const buyFor = (ti: number, pistol: boolean): { q: number; buy: Buy } => {
    if (cfg.economy === 'none') return { q: 1, buy: 'full' };
    if (pistol) return { q: 0.72, buy: 'pistol' };
    const avg = money[ti].reduce((a, b) => a + b, 0) / n;
    if (avg + carried[ti].reduce((a, b) => a + b, 0) / n * 0.6 >= cfg.fullBuy) return { q: 1, buy: 'full' };
    const smart = econSkill[ti] / 100;
    const forceChance = avg >= cfg.fullBuy * 0.55 ? 0.35 + (g.teams[ti].aggression / 100) * 0.3 - smart * 0.2 : 0.08 * (1 - smart);
    if (rng.chance(clamp(forceChance, 0.02, 0.8))) return { q: 0.84, buy: 'force' };
    return { q: 0.58, buy: 'eco' };
  };

  const spend = (ti: number, buy: Buy) => {
    const cost = buy === 'full' ? cfg.fullBuy : buy === 'force' ? cfg.fullBuy * 0.6 : buy === 'pistol' ? Math.min(cfg.startMoney, 800) : 300;
    for (let i = 0; i < n; i++) {
      const c = Math.max(0, cost - carried[ti][i]);
      money[ti][i] = Math.max(0, money[ti][i] - c);
    }
  };

  const duelRating = (pc: PlayerCtx, initiating: boolean) => pc.phase.aim * 0.68 + (initiating ? pc.phase.entry : pc.phase.clutch) * 0.2 + pc.phase.movement * 0.12;

  const isPistol = (r: number) => cfg.economy !== 'none' && (r === 0 || r === cfg.half);

  while (true) {
    const def = 1 - attack;
    const pistol = isPistol(round) && !otMode;
    const bA = buyFor(attack, pistol);
    const bD = buyFor(def, pistol);
    if (cfg.economy !== 'none' && otMode) {
      bA.q = 1;
      bD.q = 1;
    }
    spend(attack, bA.buy);
    spend(def, bD.buy);
    const alive: PlayerCtx[][] = [[...g.teams[0].players], [...g.teams[1].players]];
    const roundKills = new Map<string, number>();
    const assisted = new Set<string>();
    const traded = new Set<string>();
    let planted = false;
    let first = true;
    let duels = 0;
    let clutchSide = -1;
    let clutchPlayer: PlayerCtx | null = null;
    let clutchVs = 0;
    const baseEdge =
      -cfg.defenderEdge +
      sideBias +
      (bA.q - bD.q) * 1.7 +
      (teamUtil[attack] - teamUtil[def]) / 60 +
      (teamInfo[attack] - teamInfo[def]) / 80 +
      (igl[attack] - igl[def]) / 80 +
      (comp[attack] - comp[def]) * 0.4 +
      (cfg.prepPhase ? -0.05 : 0);

    // Round-level variance: strat reads, anti-strats, utility timing luck.
    const roundSwing = rng.normal(0, 0.32);
    const kill = (killerSide: number, killer: PlayerCtx, victim: PlayerCtx) => {
      const vSide = 1 - killerSide;
      alive[vSide] = alive[vSide].filter((x) => x !== victim);
      book.add(killer.p.id, 'k', 1);
      book.add(victim.p.id, 'd', 1);
      book.add(killer.p.id, 'dmg', 100 + rng.int(0, 60));
      if (rng.chance(clamp(0.25 + (killer.p.attrs[74] / 100) * 0.45, 0.2, 0.75))) book.add(killer.p.id, 'hs', 1);
      roundKills.set(killer.p.id, (roundKills.get(killer.p.id) ?? 0) + 1);
      if (first) {
        book.add(killer.p.id, 'fk', 1);
        book.add(victim.p.id, 'fd', 1);
        first = false;
      }
      const mates = alive[killerSide].filter((x) => x !== killer);
      if (mates.length && rng.chance(0.42)) {
        const as = pickWeighted(rng, mates, (pc) => pc.phase.utility);
        book.add(as.p.id, 'a', 1);
        assisted.add(as.p.id);
        if (rng.chance(0.4)) book.add(as.p.id, 'util', 1);
      }
      if (cfg.economy !== 'none') {
        const ki = g.teams[killerSide].players.indexOf(killer);
        if (ki >= 0) money[killerSide][ki] += cfg.killReward;
      }
      // clutch tracking
      for (const s of [0, 1]) {
        if (alive[s].length === 1 && alive[1 - s].length >= 2 && clutchSide < 0) {
          clutchSide = s;
          clutchPlayer = alive[s][0];
          clutchVs = alive[1 - s].length;
        }
      }
    };

    while (alive[0].length && alive[1].length && duels < 14) {
      duels++;
      const A = alive[attack];
      const D = alive[def];
      const atk = planted ? pickWeighted(rng, A, (pc) => pc.phase.clutch + pc.phase.aim) : pickWeighted(rng, A, (pc) => (pc.role.phases.entry ?? 0.05) * 3 + pc.phase.entry / 100);
      const dfn = planted ? pickWeighted(rng, D, (pc) => pc.phase.entry + pc.phase.aim) : pickWeighted(rng, D, (pc) => (pc.role.fclass.includes('Controller/Anchor') ? 2 : 1) + pc.phase.vision / 100);
      const man = (A.length - D.length) * 0.17;
      const logit = (duelRating(atk, !planted) - duelRating(dfn, planted)) / DUEL_SCALE + baseEdge + roundSwing + man + (planted ? 0.3 : 0) + rng.normal(0, 0.12);
      const atkWins = rng.chance(logistic(logit));
      const [kSide, killer, victim] = atkWins ? [attack, atk, dfn] : [def, dfn, atk];
      kill(kSide, killer, victim);
      // Trade attempt
      const vSide = 1 - kSide;
      if (alive[vSide].length && rng.chance(0.22 + (coord[vSide] / 100) * 0.28)) {
        const trader = pickWeighted(rng, alive[vSide], (pc) => pc.phase.aim);
        const tl = (duelRating(trader, true) - duelRating(killer, false)) / DUEL_SCALE + 0.45;
        if (rng.chance(logistic(tl))) {
          kill(vSide, trader, killer);
          traded.add(victim.p.id);
        }
      }
      // Plant decision
      if (!planted && alive[attack].length && alive[def].length && (alive[attack].length >= alive[def].length || rng.chance(0.3)) && duels >= 1 && rng.chance(0.55)) {
        planted = true;
        const planter = pickWeighted(rng, alive[attack], (pc) => (pc.role.fclass.includes('Support/Utility') ? 2 : 1));
        book.add(planter.p.id, 'plant', 1);
        if (cfg.economy !== 'none') money[attack] = money[attack].map((m) => m + cfg.plantReward / (cfg.economy === 'money' ? n : 1));
      }
    }
    let rw: number;
    if (!alive[def].length) rw = attack;
    else if (!alive[attack].length) {
      if (planted) {
        const defuseChance = clamp(0.9 - (alive[def].length === 1 ? 0.12 : 0), 0.5, 0.97);
        if (rng.chance(defuseChance)) {
          rw = def;
          book.add(pickWeighted(rng, alive[def], (pc) => pc.phase.clutch).p.id, 'defuse', 1);
        } else rw = attack;
      } else rw = def;
    } else {
      // Time ran out in a stalemate: planted favours attackers, otherwise defenders hold.
      rw = planted ? attack : def;
    }
    score[rw]++;
    // Clutch
    if (clutchSide === rw && clutchPlayer) {
      book.add((clutchPlayer as PlayerCtx).p.id, 'clutch', 1);
      const cp = clutchPlayer as PlayerCtx;
      emit(g, round + 1, rw as 0 | 1, 'clutch', () => `Round ${round + 1}: ${withChar(g, cp)} wins a 1v${clutchVs} clutch for ${teamName(g, rw)}!`, clutchVs >= 3);
    }
    // KAST & multikills
    for (const s of [0, 1]) {
      for (const pc of g.teams[s].players) {
        const k = roundKills.get(pc.p.id) ?? 0;
        const survived = alive[s].includes(pc);
        if (k > 0 || assisted.has(pc.p.id) || survived || traded.has(pc.p.id)) book.add(pc.p.id, 'kast', 1);
        if (k >= 3) {
          book.add(pc.p.id, 'mk', 1);
          if (k >= n) emit(g, round + 1, s as 0 | 1, 'ace', () => `ACE! ${withChar(g, pc)} wipes out the opposition in round ${round + 1}.`, true);
        }
      }
    }
    if (round === 0 || (bA.buy === 'eco' && rw === attack) || (bD.buy === 'eco' && rw === def)) {
      emit(g, round + 1, rw as 0 | 1, 'round', () => `Round ${round + 1}: ${teamName(g, rw)} win${round === 0 ? ' the pistol round' : ' on an eco — big economic swing'} (${score[0]}-${score[1]}).`);
    }
    // Economy update
    if (cfg.economy !== 'none') {
      const lr = 1 - rw;
      money[rw] = money[rw].map((m) => m + cfg.winReward);
      money[lr] = money[lr].map((m) => m + cfg.lossRewards[Math.min(streak[lr], cfg.lossRewards.length - 1)]);
      streak[rw] = 0;
      streak[lr]++;
      for (const s of [0, 1]) {
        g.teams[s].players.forEach((pc, i) => {
          carried[s][i] = alive[s].includes(pc) ? (s === attack ? bA : bD).q * cfg.fullBuy * 0.8 : 0;
          money[s][i] = Math.min(cfg.maxMoney, money[s][i]);
        });
      }
    }
    round++;
    // Win / half / overtime checks
    const target = cfg.roundsToWin;
    if (!otMode) {
      if (score[0] >= target || score[1] >= target) break;
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
        sides.push(g.teams[attack].team.short + ' attack first');
        attack = 1 - attack;
        if (cfg.economy !== 'none') {
          money[0] = money[0].map(() => cfg.startMoney);
          money[1] = money[1].map(() => cfg.startMoney);
          carried[0] = carried[0].map(() => 0);
          carried[1] = carried[1].map(() => 0);
          streak[0] = streak[1] = 0;
        }
        emit(g, round, -1, 'half', `Halftime: ${teamName(g, 0)} ${score[0]}-${score[1]} ${teamName(g, 1)}. Sides switch.`);
      }
    } else {
      otRound++;
      if (cfg.overtime.kind === 'win-by-two') {
        attack = 1 - attack;
        if (Math.abs(score[0] - score[1]) >= 2) break;
        if (cfg.overtime.cap && (score[0] >= cfg.overtime.cap || score[1] >= cfg.overtime.cap)) break;
        if (otRound > 40) break;
      } else {
        const block = cfg.overtime.rounds;
        const half = block / 2;
        const need = half + 1;
        if (score[0] - otBlockStart[0] >= need || score[1] - otBlockStart[1] >= need) break;
        if (otRound % half === 0) attack = 1 - attack;
        if (otRound % block === 0) {
          otBlockStart = [...score];
          emit(g, round, -1, 'overtime', `Another overtime at ${score[0]}-${score[1]}!`);
        }
        if (cfg.economy !== 'none' && otRound % half === 0) {
          money[0] = money[0].map(() => 10000);
          money[1] = money[1].map(() => 10000);
        }
        if (otRound > 60) break;
      }
    }
  }
  const winner = score[0] > score[1] ? 0 : score[1] > score[0] ? 1 : rng.chance(0.5) ? 0 : 1;
  const totalRounds = score[0] + score[1];
  book.finalise();
  assignRatings(
    book,
    (l) => {
      const s = l.s;
      return 0.4 + s.k * 1.0 - s.d * 0.55 + s.a * 0.3 + (s.kast / Math.max(1, totalRounds)) * 2.2 + s.fk * 0.4 - s.fd * 0.2 + s.clutch * 0.9 + s.mk * 0.5 + s.dmg / 1200;
    },
    winner,
  );
  emit(g, totalRounds, winner as 0 | 1, 'end', `${teamName(g, winner)} take ${map?.name ?? 'the map'} ${score[winner]}-${score[1 - winner]}.`, true);
  return {
    idx: g.gameIdx,
    winner,
    mapId: g.mapId,
    duration: totalRounds * 105,
    score: [...score],
    teamStats: [0, 1].map((i) => ({ rounds: score[i], firstHalf: halfScore[0]?.[i] ?? score[i] })),
    players: book.lines,
    draft: g.draft,
    events: g.events ?? undefined,
    sides,
    summary: `${map?.name ?? 'Map'}: ${score[0]}-${score[1]}${otMode ? ' (OT)' : ''}`,
  };
}

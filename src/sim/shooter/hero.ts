/**
 * Hero shooter engine: fight-by-fight simulation with ultimate economy, composition balance,
 * hero swaps (where allowed) and mode logic: Control, Escort, Hybrid, Push, Flashpoint and
 * Paladins-style Siege. Overwatch Rush uses Control maps (first to two round wins).
 */
import { clamp, logistic } from '../../core/math';
import type { GameResult } from '../../model/types';
import { assignRatings, dmgWeight, emit, healWeight, pickWeighted, StatBook, tankWeight, teamName, withChar, type GameContext } from '../common/engine';

export function simulateHero(g: GameContext): GameResult {
  const { rng } = g;
  const cfg = g.title.hero!;
  const mode = g.title.modes.find((m) => m.id === g.modeId) ?? g.title.modes[0];
  const keys = g.title.stats.map((s) => s.key);
  const book = new StatBook(g.teams, keys);
  const ult = [0, 0];
  const compAdj = [g.comp[0].total, g.comp[1].total];
  let lossStreak = [0, 0];
  let fights = 0;
  let t = 0;

  const strength = (i: number) => {
    const ph = g.teams[i].phase;
    return ph.aim * 0.24 + ph.teamfight * 0.28 + ph.support * 0.14 + ph.entry * 0.1 + ph.survival * 0.08 + ph.macro * 0.08 + ph.igl * 0.04 + ph.utility * 0.04;
  };
  const base = [strength(0), strength(1)];

  /** Resolve one team fight. `edge` > 0 favours team 0 (positional advantage). */
  const fight = (edge: number, label: string): number => {
    fights++;
    t += rng.int(25, 70);
    let l = (base[0] - base[1]) / 10 + (compAdj[0] - compAdj[1]) * 0.6 + edge + rng.normal(0, 0.25);
    // Ultimates: teams spend banked ults on important fights.
    const use = [0, 1].map((i) => ult[i] >= 1 && (rng.chance(0.55) || label === 'key'));
    if (use[0]) l += 0.35;
    if (use[1]) l -= 0.35;
    const w = rng.chance(logistic(l)) ? 0 : 1;
    for (const i of [0, 1]) {
      if (use[i]) {
        ult[i] -= 1;
        const caster = pickWeighted(rng, g.teams[i].players, (pc) => pc.phase.teamfight);
        book.add(caster.p.id, 'ult', 1);
      }
      ult[i] += i === w ? 1 / cfg.ultCharge : 1.4 / cfg.ultCharge;
    }
    const dom = Math.abs(logistic(l) - 0.5) * 2;
    const wElims = Math.min(g.teams[1 - w].players.length, 2 + Math.round(dom * 2.5) + (rng.chance(0.4) ? 1 : 0));
    const lElims = Math.min(g.teams[w].players.length - 1, Math.round(rng.float(0, 2.2) * (1 - dom * 0.5)));
    const tally = (side: number, count: number) => {
      const ps = g.teams[side].players;
      for (let k = 0; k < count; k++) {
        const fb = pickWeighted(rng, ps, (pc) => dmgWeight(g, pc) * pc.phase.aim);
        book.add(fb.p.id, 'fb', 1);
        book.add(fb.p.id, 'elim', 1);
        for (const pc of ps) if (pc !== fb && rng.chance(0.6)) book.add(pc.p.id, 'elim', 1);
        const victim = rng.pick(g.teams[1 - side].players);
        book.add(victim.p.id, 'd', 1);
      }
    };
    tally(w, wElims);
    tally(1 - w, lElims);
    for (const i of [0, 1]) {
      for (const pc of g.teams[i].players) {
        const f = i === w ? 1.1 : 0.9;
        book.add(pc.p.id, 'dmg', rng.int(900, 1800) * dmgWeight(g, pc) * f);
        book.add(pc.p.id, 'heal', rng.int(700, 1500) * healWeight(g, pc) * 1.4);
        book.add(pc.p.id, 'mit', rng.int(600, 1600) * Math.max(0, tankWeight(g, pc) - 0.9) * 1.8);
      }
    }
    lossStreak[w] = 0;
    lossStreak[1 - w]++;
    // Hero swaps after repeated lost fights.
    if (cfg.swaps && lossStreak[1 - w] >= 3 && rng.chance(0.5)) {
      const loser = 1 - w;
      compAdj[loser] += rng.float(-0.03, 0.09);
      lossStreak[loser] = 0;
      const pc = rng.pick(g.teams[loser].players);
      emit(g, t, loser as 0 | 1, 'swap', () => `${teamName(g, loser)} make a hero swap — ${pc.p.handle} switches to adapt.`);
    }
    if (wElims >= g.teams[1 - w].players.length) emit(g, t, w as 0 | 1, 'teamwipe', () => `${teamName(g, w)} wipe ${teamName(g, 1 - w)}${label && label !== 'key' ? ` (${label})` : ''}.`, true);
    return w;
  };

  const score = [0, 0];
  let summary = '';
  const kind = mode.kind;
  if (kind === 'control') {
    const need = mode.params.roundsToWin ?? 2;
    let round = 0;
    while (score[0] < need && score[1] < need && round < 9) {
      round++;
      const prog = [0, 0];
      let holder = -1;
      let guard = 0;
      while (prog[0] < 100 && prog[1] < 100 && guard++ < 40) {
        const w = fight(holder < 0 ? 0 : holder === 0 ? 0.12 : -0.12, guard === 1 ? 'key' : '');
        holder = w;
        prog[w] = Math.min(100, prog[w] + rng.int(18, 38));
      }
      const rw = prog[0] >= 100 ? 0 : 1;
      score[rw]++;
      emit(g, t, rw as 0 | 1, 'round', () => `${teamName(g, rw)} take control round ${round} (${score[0]}-${score[1]}).`);
    }
    summary = `${mode.name}: ${score[0]}-${score[1]}`;
  } else if (kind === 'escort' || kind === 'hybrid') {
    // Each team attacks once; compare checkpoint progress (+ banked-time tiebreak).
    const dist = [0, 0];
    const timeLeft = [0, 0];
    for (const attacker of [g.firstSide, 1 - g.firstSide]) {
      let budget = 9 + rng.int(0, 2);
      let progress = 0;
      if (kind === 'hybrid') {
        // capture first point
        let capP = 0;
        while (budget > 0 && capP < 100) {
          budget--;
          const w = fight(attacker === 0 ? -0.15 : 0.15, budget === 8 ? 'key' : '');
          if (w === attacker) capP += rng.int(35, 70);
        }
        if (capP < 100) {
          dist[attacker] = capP / 100;
          continue;
        }
        progress = 1;
      }
      while (budget > 0 && progress < 3) {
        budget--;
        const w = fight(attacker === 0 ? -0.12 : 0.12, '');
        if (w === attacker) progress += rng.float(0.3, 0.6);
      }
      dist[attacker] = Math.min(3, progress);
      timeLeft[attacker] = budget;
    }
    score[0] = Math.round(dist[0] * 10) / 10;
    score[1] = Math.round(dist[1] * 10) / 10;
    if (Math.abs(dist[0] - dist[1]) < 0.05) {
      if (timeLeft[0] !== timeLeft[1]) score[timeLeft[0] > timeLeft[1] ? 0 : 1] += 0.1;
      else score[fight(0, 'key')] += 0.1;
    }
    summary = `${mode.name}: ${score[0].toFixed(1)}-${score[1].toFixed(1)} checkpoints`;
  } else if (kind === 'push') {
    let pos = 0; // positive = team 0 pushing toward team 1's end
    for (let f = 0; f < 14 && Math.abs(pos) < 100; f++) {
      const w = fight(0, f === 0 ? 'key' : '');
      pos += (w === 0 ? 1 : -1) * rng.int(8, 18);
    }
    if (pos === 0) pos = fight(0, 'key') === 0 ? 1 : -1;
    score[0] = Math.max(0, Math.round(pos));
    score[1] = Math.max(0, Math.round(-pos));
    summary = `${mode.name}: robot ${Math.abs(Math.round(pos))}m toward ${pos > 0 ? teamName(g, 1) : teamName(g, 0)}`;
  } else if (kind === 'flashpoint') {
    const need = mode.params.points ?? 3;
    while (score[0] < need && score[1] < need) {
      const prog = [0, 0];
      let guard = 0;
      while (prog[0] < 100 && prog[1] < 100 && guard++ < 30) prog[fight(0, guard === 1 ? 'key' : '')] += rng.int(22, 40);
      score[prog[0] >= 100 ? 0 : 1]++;
    }
    summary = `${mode.name}: ${score[0]}-${score[1]}`;
  } else if (kind === 'siege') {
    const need = mode.params.pointsToWin ?? 4;
    let guard = 0;
    while (score[0] < need && score[1] < need && guard++ < 12) {
      const prog = [0, 0];
      let g2 = 0;
      while (prog[0] < 100 && prog[1] < 100 && g2++ < 30) prog[fight(0, g2 === 1 ? 'key' : '')] += rng.int(20, 40);
      const cap = prog[0] >= 100 ? 0 : 1;
      score[cap]++;
      // Payload push: capturer escorts, defenders try to stop it (stopping earns a point).
      let pushed = 0;
      for (let f = 0; f < 5 && pushed < 100; f++) if (fight(cap === 0 ? -0.1 : 0.1, '') === cap) pushed += rng.int(25, 45);
      if (pushed >= 100) score[cap] = Math.min(need, score[cap] + 1);
      else score[1 - cap] = Math.min(need, score[1 - cap] + 1);
      emit(g, t, cap as 0 | 1, 'round', () => `${teamName(g, cap)} capture the point and ${pushed >= 100 ? 'deliver the payload' : 'are stopped short'} (${score[0]}-${score[1]}).`);
    }
    if (score[0] === score[1]) score[fight(0, 'key')]++;
    summary = `${mode.name}: ${score[0]}-${score[1]}`;
  }
  const winner = score[0] > score[1] ? 0 : 1;
  emit(g, t, winner as 0 | 1, 'end', `${teamName(g, winner)} win ${g.title.maps.find((m) => m.id === g.mapId)?.name ?? 'the map'}. ${summary}`, true);
  book.finalise();
  assignRatings(book, (l) => 0.5 + l.s.elim * 0.35 + l.s.fb * 0.5 - l.s.d * 0.4 + l.s.dmg / 9000 + l.s.heal / 7000 + l.s.mit / 9000 + l.s.ult * 0.2, winner);
  void clamp;
  void withChar;
  return {
    idx: g.gameIdx,
    winner,
    mapId: g.mapId,
    modeId: mode.id,
    duration: Math.round(t),
    score: [...score],
    teamStats: [0, 1].map((i) => ({ fights: fights, score: score[i] })),
    players: book.lines,
    draft: g.draft,
    events: g.events ?? undefined,
    summary,
  };
}

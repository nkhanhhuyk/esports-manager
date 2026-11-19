/**
 * Brawl Stars engine: short 3v3 games on mode-specific objectives.
 * A "set" is first to two game wins on the same mode/map (handled by the series runner).
 * Map openness and the mode change which brawler classes are strong.
 */
import { clamp, logistic } from '../../core/math';
import type { GameResult } from '../../model/types';
import { assignRatings, dmgWeight, emit, fmtTime, healWeight, pickWeighted, StatBook, teamName, withChar, type GameContext } from '../common/engine';
import type { TeamCtx } from '../common/team';

const MODE_CLASS: Record<string, RegExp> = {
  gem: /controller|tank|support/,
  ball: /assassin|tank|damage/,
  heist: /damage|assassin|sharpshooter/,
  zone: /controller|tank|artillery/,
  knockout: /sharpshooter|marksman|artillery/,
  bounty: /sharpshooter|marksman|support/,
};

function teamStrength(g: GameContext, tc: TeamCtx, kind: string, size: string): number {
  const ph = tc.phase;
  let s = ph.skirmish * 0.28 + ph.teamfight * 0.18 + ph.aim * 0.16 + ph.objective * 0.14 + ph.macro * 0.12 + ph.movement * 0.12;
  const classes = tc.players.map((pc) => (g.title.characters.find((c) => c.id === pc.charId)?.cls ?? '').toLowerCase());
  const modeFit = classes.filter((c) => MODE_CLASS[kind]?.test(c)).length;
  const open = classes.filter((c) => /sharpshooter|marksman|artillery/.test(c)).length;
  const closed = classes.filter((c) => /tank|assassin/.test(c)).length;
  const mapFit = size === 'open' ? open - closed : size === 'closed' ? closed - open : 0;
  return s * (1 + modeFit * 0.025 + mapFit * 0.02 + g.comp[tc.idx].total * 0.08);
}

export function simulateBrawl(g: GameContext): GameResult {
  const { rng } = g;
  const mode = g.title.modes.find((m) => m.id === g.modeId) ?? g.title.modes[0];
  const map = g.title.maps.find((m) => m.id === g.mapId);
  const keys = g.title.stats.map((s) => s.key);
  const book = new StatBook(g.teams, keys);
  const sA = teamStrength(g, g.teams[0], mode.kind, map?.size ?? 'mixed');
  const sB = teamStrength(g, g.teams[1], mode.kind, map?.size ?? 'mixed');
  const edge = (sA - sB) / 9;
  const pts = [0, 0];
  let winner = -1;
  let t = 0;
  const tick = 10;
  const maxT = mode.kind === 'knockout' ? 360 : 150;
  let gemTimer = [-1, -1];
  let roundWins = [0, 0];
  while (winner < 0 && t < maxT + 120) {
    t += tick;
    // Engagement
    const p = logistic(edge + rng.normal(0, 0.35));
    const w = rng.chance(p) ? 0 : 1;
    const ws = g.teams[w];
    const ls = g.teams[1 - w];
    const kills = rng.chance(0.55) ? 1 + (rng.chance(0.35) ? 1 : 0) : 0;
    for (let k = 0; k < kills; k++) {
      const killer = pickWeighted(rng, ws.players, (pc) => dmgWeight(g, pc) * pc.phase.aim);
      const victim = rng.pick(ls.players);
      book.add(killer.p.id, 'k', 1);
      book.add(victim.p.id, 'd', 1);
      if (mode.kind === 'bounty') {
        pts[w] += 1 + Math.min(4, book.get(victim.p.id, 'k'));
        book.add(killer.p.id, 'stars', 1);
      }
    }
    for (const pc of [...ws.players, ...ls.players]) {
      book.add(pc.p.id, 'dmg', rng.int(800, 2400) * dmgWeight(g, pc) * (pc.p.id && ws.players.includes(pc) ? 1.2 : 0.8));
      book.add(pc.p.id, 'heal', rng.int(200, 900) * healWeight(g, pc));
    }
    switch (mode.kind) {
      case 'gem': {
        const carrier = pickWeighted(rng, ws.players, (pc) => pc.phase.objective);
        const gain = rng.int(1, 3);
        pts[w] += gain;
        book.add(carrier.p.id, 'obj', gain);
        if (kills) pts[1 - w] = Math.max(0, pts[1 - w] - rng.int(1, 3));
        for (const i of [0, 1]) {
          if (pts[i] >= (mode.params.target ?? 10) && pts[i] > pts[1 - i]) {
            if (gemTimer[i] < 0) gemTimer[i] = t;
            if (t - gemTimer[i] >= (mode.params.countdown ?? 15)) winner = i;
          } else gemTimer[i] = -1;
        }
        break;
      }
      case 'ball':
        if (rng.chance(0.18 + (kills ? 0.12 : 0))) {
          pts[w]++;
          const scorer = pickWeighted(rng, ws.players, (pc) => pc.phase.objective + pc.phase.movement);
          book.add(scorer.p.id, 'obj', 1);
          emit(g, t, w as 0 | 1, 'goal', () => `${withChar(g, scorer)} scores! ${pts[0]}-${pts[1]}`, true);
          if (pts[w] >= (mode.params.goals ?? 2)) winner = w;
        }
        break;
      case 'heist': {
        const dmg = rng.int(4, 11) * (kills ? 1.6 : 1);
        pts[w] += dmg;
        const hitter = pickWeighted(rng, ws.players, (pc) => dmgWeight(g, pc));
        book.add(hitter.p.id, 'obj', dmg);
        if (pts[w] >= (mode.params.safeHp ?? 100)) winner = w;
        break;
      }
      case 'zone': {
        const cap = rng.int(5, 12);
        pts[w] += cap;
        const holder = pickWeighted(rng, ws.players, (pc) => pc.phase.objective);
        book.add(holder.p.id, 'obj', cap);
        if (pts[w] >= (mode.params.target ?? 100)) winner = w;
        break;
      }
      case 'knockout': {
        if (kills && rng.chance(0.4)) {
          roundWins[w]++;
          emit(g, t, w as 0 | 1, 'round', () => `${teamName(g, w)} win the knockout round (${roundWins[0]}-${roundWins[1]}).`);
          if (roundWins[w] >= (mode.params.rounds ?? 2)) winner = w;
        }
        pts[0] = roundWins[0];
        pts[1] = roundWins[1];
        break;
      }
      case 'bounty':
      default:
        break;
    }
    if (winner < 0 && t >= maxT) {
      if (pts[0] !== pts[1]) winner = pts[0] > pts[1] ? 0 : 1;
      else if (t >= maxT + 60) winner = rng.chance(logistic(edge)) ? 0 : 1; // overtime
    }
  }
  if (winner < 0) winner = pts[0] >= pts[1] ? 0 : 1;
  emit(g, t, winner as 0 | 1, 'end', `${teamName(g, winner)} take the ${mode.name} game at ${fmtTime(t)}.`, true);
  book.finalise();
  assignRatings(book, (l) => 1 + l.s.k * 1.2 - l.s.d * 0.6 + (l.s.dmg ?? 0) / 6000 + (l.s.heal ?? 0) / 5000 + (l.s.obj ?? 0) * 0.08 + (l.s.stars ?? 0) * 0.5, winner);
  void clamp;
  return {
    idx: g.gameIdx,
    winner,
    mapId: g.mapId,
    modeId: mode.id,
    duration: t,
    score: [Math.round(pts[0]), Math.round(pts[1])],
    teamStats: [0, 1].map((i) => ({ points: Math.round(pts[i]) })),
    players: book.lines,
    draft: g.draft,
    events: g.events ?? undefined,
    summary: `${mode.name}: ${teamName(g, winner)} win ${Math.round(pts[winner])}-${Math.round(pts[1 - winner])}`,
  };
}

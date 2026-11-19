import { describe, expect, it } from 'vitest';
import { buildDoubleElim, buildSingleElim, roundRobinRounds, startStage, checkStage, stagePlacements, applyFixtureToStage, bracketSeeding } from '../src/competition/stages';
import type { Competition, Stage } from '../src/model/types';
import { makeWorld } from './helpers';
import { allocateSlots } from '../src/competition/season';
import { playFixture } from '../src/sim/record';
import { getTitle } from '../src/data/titles/catalogue';

function fakeComp(world: ReturnType<typeof makeWorld>, titleId: string, type: Stage['type'], teamIds: string[], config: Stage['config'] = {}): { comp: Competition; stage: Stage } {
  const stage: Stage = { id: 's0', name: 'Test', type, config, startDay: world.day + 1, teamIds: [], standings: [], fixtureIds: [], bracket: [], swissRound: 0, status: 'pending', placements: [] };
  const comp: Competition = { id: `k-test-${type}`, name: 'Test', short: 'T', titleId, season: world.season, tier: 'cup', region: null, leagueId: null, entrants: [], teamIds, stages: [stage], current: 0, status: 'active', startDay: world.day + 1, prizePool: 0, prizeSplit: [], circuitPoints: [], placements: [], fullDetail: false, prestige: 10, pstats: {} };
  world.comps[comp.id] = comp;
  startStage(world, comp, stage, teamIds);
  return { comp, stage };
}

/** Play every fixture of a stage (including ones generated as rounds complete). */
function playStage(world: ReturnType<typeof makeWorld>, comp: Competition, stage: Stage) {
  for (let guard = 0; guard < 500; guard++) {
    const next = stage.fixtureIds.map((id) => world.fixtures[id]).find((f) => f.status === 'scheduled');
    if (!next) {
      if (checkStage(world, comp, stage)) break;
      continue;
    }
    // Run the series without the competition lifecycle hook; apply to stage directly.
    const savedComp = next.compId;
    next.compId = '__none__';
    playFixture(world, next);
    next.compId = savedComp;
    applyFixtureToStage(world, comp, stage, next);
  }
  expect(checkStage(world, comp, stage)).toBe(true);
}

describe('round robin scheduling', () => {
  it('pairs every team with every other exactly once per cycle and never twice per round', () => {
    for (const n of [4, 6, 7, 8, 10]) {
      const teams = Array.from({ length: n }, (_, i) => `t${i}`);
      const rounds = roundRobinRounds(teams);
      const seen = new Set<string>();
      for (const r of rounds) {
        const inRound = new Set<string>();
        for (const [a, b] of r) {
          expect(inRound.has(a) || inRound.has(b)).toBe(false);
          inRound.add(a);
          inRound.add(b);
          const k = [a, b].sort().join('-');
          expect(seen.has(k)).toBe(false);
          seen.add(k);
        }
      }
      expect(seen.size).toBe((n * (n - 1)) / 2);
    }
  });

  it('schedules double round robins on match days with no team playing twice on a day', () => {
    const w = makeWorld(['lol'], 'rr');
    const teams = Object.values(w.teams).filter((t) => t.titleId === 'lol').slice(0, 8).map((t) => t.id);
    const { stage } = fakeComp(w, 'lol', 'double-round-robin', teams);
    expect(stage.fixtureIds.length).toBe(8 * 7);
    const byDay = new Map<number, string[]>();
    for (const id of stage.fixtureIds) {
      const f = w.fixtures[id];
      const list = byDay.get(f.day) ?? [];
      for (const t of f.teams) {
        expect(list.includes(t)).toBe(false);
        list.push(t);
      }
      byDay.set(f.day, list);
    }
  });
});

describe('brackets', () => {
  it('seeds standard brackets', () => {
    expect(bracketSeeding(8)).toEqual([1, 8, 4, 5, 2, 7, 3, 6]);
  });

  it('single elimination handles byes and produces full placements', () => {
    const w = makeWorld(['cs2'], 'se');
    const teams = Object.values(w.teams).filter((t) => t.titleId === 'cs2').slice(0, 6).map((t) => t.id);
    const { comp, stage } = fakeComp(w, 'cs2', 'single-elim', teams, { daysBetweenRounds: 1 });
    expect(buildSingleElim(6).filter((n) => n.round === 0).length).toBe(4);
    playStage(w, comp, stage);
    const pl = stagePlacements(w, stage);
    expect(new Set(pl).size).toBe(6);
    const final = stage.bracket.find((n) => n.isFinal)!;
    expect(pl[0]).toBe(final.winner);
  });

  it('double elimination (8) gives every team two losses before elimination except the champion', () => {
    const w = makeWorld(['valorant'], 'de');
    const teams = Object.values(w.teams).filter((t) => t.titleId === 'valorant').slice(0, 8).map((t) => t.id);
    const { comp, stage } = fakeComp(w, 'valorant', 'double-elim', teams, { daysBetweenRounds: 1 });
    expect(buildDoubleElim(8).length).toBe(14);
    playStage(w, comp, stage);
    const losses = new Map<string, number>();
    for (const id of stage.fixtureIds) {
      const f = w.fixtures[id];
      const loser = f.teams.find((t) => t !== f.winner)!;
      losses.set(loser, (losses.get(loser) ?? 0) + 1);
    }
    const [champ, runnerUp] = stagePlacements(w, stage);
    for (const t of teams) {
      if (t === champ) expect(losses.get(t) ?? 0).toBeLessThanOrEqual(1);
      // No bracket reset: an upper-bracket finalist can be eliminated by one grand-final loss.
      else if (t === runnerUp) expect([1, 2]).toContain(losses.get(t));
      else expect(losses.get(t)).toBe(2);
    }
  });

  it('swiss stage avoids rematches and finishes with the right number of qualifiers', () => {
    const w = makeWorld(['lol', 'valorant', 'cs2'], 'swiss', 'small');
    const teams = Object.values(w.teams).filter((t) => t.titleId === 'cs2' && t.active).slice(0, 16).map((t) => t.id);
    expect(teams.length).toBe(16);
    const { comp, stage } = fakeComp(w, 'cs2', 'swiss', teams, { swissWins: 3, swissLosses: 3, daysBetweenRounds: 1 });
    playStage(w, comp, stage);
    const pairs = stage.fixtureIds.map((id) => w.fixtures[id].teams.slice().sort().join('-'));
    expect(new Set(pairs).size).toBe(pairs.length);
    expect(stage.standings.filter((r) => r.w === 3).length).toBe(8);
    expect(stage.standings.filter((r) => r.l === 3).length).toBe(8);
  });
});

describe('battle royale points stage', () => {
  it('aggregates placement + kill points across games', () => {
    const w = makeWorld(['pubgm'], 'br');
    const teams = Object.values(w.teams).filter((t) => t.titleId === 'pubgm').slice(0, 12).map((t) => t.id);
    const { comp, stage } = fakeComp(w, 'pubgm', 'br-points', teams, { matchdays: 2, gamesPerDay: 3, format: 'standard', matchDaysOfWeek: [0, 1, 2, 3, 4, 5, 6] });
    playStage(w, comp, stage);
    const fx = stage.fixtureIds.map((id) => w.fixtures[id]);
    const t = getTitle('pubgm');
    for (const tid of teams) {
      const expected = fx.reduce((a, f) => a + (f.brPoints?.[tid] ?? 0), 0);
      expect(stage.standings.find((r) => r.teamId === tid)!.pts).toBe(expected);
    }
    // Each game awards exactly one first place.
    for (const f of fx) {
      const rec = w.matches[f.id];
      expect(rec.games.length).toBe(3);
      for (const g of rec.games) {
        expect(new Set(g.placements).size).toBe(teams.length);
        const sumKills = Object.values(g.brKills!).reduce((a, b) => a + b, 0);
        expect(sumKills).toBeGreaterThan(0);
      }
    }
    expect(t.br!.placementPts[0]).toBe(10);
  });
});

describe('slot allocation', () => {
  it('distributes international slots by strength', () => {
    const s = allocateSlots([100, 80, 60, 40, 20], 16);
    expect(s.reduce((a, b) => a + b, 0)).toBe(16);
    expect(s[0]).toBeGreaterThanOrEqual(s[4]);
    expect(Math.min(...s)).toBeGreaterThanOrEqual(1);
  });
});

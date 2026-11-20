import { describe, expect, it } from 'vitest';
import { makeWorld } from './helpers';
import { completeSelection, createSeries, gameSides, playNextGame, sideChooser } from '../src/sim/series';
import type { Fixture, World } from '../src/model/types';

function fixture(w: World, id: string, titleId: string, n: number, spatial = false): Fixture {
  const teams = Object.values(w.teams).filter((t) => t.titleId === titleId && t.tier === 'main' && t.leagueId);
  return { id, compId: Object.values(w.comps).find((c) => c.titleId === titleId)!.id, titleId, teams: [teams[0].id, teams[1].id], day: w.day, status: 'scheduled', format: { n, label: `Bo${n}` }, formatKey: 'standard', detail: true, spatial } as unknown as Fixture;
}

describe('MOBA sides and draft order', () => {
  it('LoL first selection: the chooser takes a side or a draft order, the opponent the other; the previous loser chooses next', () => {
    const w = makeWorld(['lol'], 'sides-lol', 'small');
    let blueSecond = 0;
    let splitSidePick = 0;
    for (let k = 0; k < 12; k++) {
      const f = fixture(w, `sel-${k}`, 'lol', 5);
      const st = createSeries(w, f, false);
      while (!st.done) {
        const { chooser, by } = sideChooser(w, f, st);
        if (st.games.length) {
          expect(by).toBe('loser');
          expect(chooser).toBe(1 - st.games[st.games.length - 1].winner);
        }
        const sel = gameSides(w, f, st);
        expect(sel.chooser).toBe(chooser);
        const g = playNextGame(w, f, st);
        expect(g.blue).toBe(sel.blue);
        expect(g.draft!.firstPick).toBe(sel.firstPick);
        expect(g.sides![0]).toMatch(/chose/);
        if (sel.blue === 1) blueSecond++;
        if (sel.blue !== sel.firstPick) splitSidePick++;
      }
    }
    // Sides really change hands, and side and first pick are not always held by the same team.
    expect(blueSecond).toBeGreaterThan(0);
    expect(splitSidePick).toBeGreaterThan(0);
  });

  it("a user's choice is used as made; the opponent answers the other half", () => {
    const w = makeWorld(['lol'], 'sides-user', 'small');
    const f = fixture(w, 'sel-user', 'lol', 1);
    const st = createSeries(w, f, false);
    const { chooser, by } = sideChooser(w, f, st);
    const sel = completeSelection(w, f, chooser, by, { side: 1 });
    expect(sel.blue).toBe(1 - chooser);
    st.sel = [sel];
    const g = playNextGame(w, f, st);
    expect(g.blue).toBe(1 - chooser);
    const byPick = completeSelection(w, f, chooser, by, { order: 'second' });
    expect(byPick.firstPick).toBe(1 - chooser);
    expect(byPick.choice).toBe('pick');
  });

  it('MLBB: the chooser takes Blue side, which drafts first; box score lines stay in series order', () => {
    const w = makeWorld(['mlbb'], 'sides-mlbb', 'small');
    const f = fixture(w, 'sel-mlbb', 'mlbb', 3, true);
    const st = createSeries(w, f, false);
    while (!st.done) {
      const sel = gameSides(w, f, st);
      expect(sel.blue).toBe(sel.firstPick);
      const g = playNextGame(w, f, st);
      const home = new Set(w.teams[f.teams[0]].roster);
      for (const l of g.players) expect(l.teamIdx === 0, l.playerId).toBe(home.has(l.playerId));
      expect(g.score[g.winner]).toBeGreaterThanOrEqual(0);
    }
  });
});

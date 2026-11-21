import { describe, expect, it } from 'vitest';
import { makeWorld, withUser } from './helpers';
import { advance } from '../src/game/advance';
import { editionsOf, loadArchive, seriesKey } from '../src/game/archive';
import { boxLines } from '../src/sim/boxscore';
import { loadMatchRecord } from '../src/game/boxPages';

describe('history is kept: season archive, competition editions, box scores', () => {
  it('archives old seasons with every box score and groups editions per competition', async () => {
    const w = withUser(makeWorld(['lol', 'valorant'], 'archive'));
    const first = w.season;
    advance(w, { kind: 'seasons', n: 2 });
    // The first season left the live world but is indexed and fully loadable.
    expect(Object.values(w.comps).some((c) => c.season === first)).toBe(false);
    const idx = (w.compIndex ?? []).filter((e) => e.season === first);
    expect(idx.length).toBeGreaterThan(0);
    expect(idx.every((e) => e.champion)).toBe(true);
    const a = await loadArchive(w, first);
    expect(a).toBeTruthy();
    expect(a!.comps.length).toBe(idx.length);
    // Every played fixture of the old season still has its box scores: in the season record or in a
    // weekly box-score page (background matches are paged out of the save during the season).
    const played = a!.fixtures.filter((f) => f.status === 'done');
    expect(played.length).toBeGreaterThan(50);
    expect(played.some((f) => f.pg !== undefined)).toBe(true);
    for (const f of played.slice(0, 200)) {
      const m = a!.matches.find((x) => x.fixtureId === f.id) ?? (await loadMatchRecord(w, f.id, first, f.pg));
      expect(m, f.id).toBeTruthy();
      for (const g of m!.games) if (g.duration > 0) expect(boxLines(g).length, f.id).toBeGreaterThan(0);
    }
    // The save itself no longer carries background box scores of the live season.
    expect(Object.values(w.matches).every((m) => m.detail || m.day > w.day - 9)).toBe(true);
    // One competition, several editions: the league's splits of all seasons.
    const live = Object.values(w.comps).find((c) => c.tier === 'league')!;
    const eds = editionsOf(w, live);
    expect(eds.length).toBeGreaterThanOrEqual(4);
    expect(new Set(eds.map((e) => e.season)).size).toBeGreaterThanOrEqual(2);
    expect(eds.some((e) => e.archived)).toBe(true);
    expect(eds.every((e) => seriesKey(w.comps[e.id] ?? w.compIndex!.find((x) => x.id === e.id)!) === seriesKey(live))).toBe(true);
  }, 180_000);
});

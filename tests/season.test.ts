import { describe, expect, it } from 'vitest';
import { makeWorld, withUser } from './helpers';
import { advance } from '../src/game/advance';
import { TITLES } from '../src/data/titles/catalogue';
import type { World } from '../src/model/types';
import { getTitle } from '../src/data/titles/catalogue';
import { rosterMax } from '../src/systems/transfers';

function checkInvariants(w: World) {
  const seen = new Map<string, string>();
  for (const t of Object.values(w.teams)) {
    if (!t.active) {
      expect(t.roster.length).toBe(0);
      continue;
    }
    for (const pid of t.roster) {
      expect(seen.has(pid), `player ${pid} on two rosters`).toBe(false);
      seen.set(pid, t.id);
      const p = w.players[pid];
      expect(p, `missing player ${pid}`).toBeTruthy();
      expect(p.teamId).toBe(t.id);
      expect(p.retired).toBeNull();
    }
    expect(t.roster.length).toBeLessThanOrEqual(rosterMax(w, t) + 1);
    for (const s of t.lineup) expect(t.roster).toContain(s.playerId);
  }
  for (const p of Object.values(w.players)) {
    if (p.teamId) expect(w.teams[p.teamId].roster).toContain(p.id);
    for (const v of p.attrs) expect(v >= 0 && v <= 100 && Number.isFinite(v)).toBe(true);
    expect(Number.isFinite(p.ca)).toBe(true);
  }
  for (const o of Object.values(w.orgs)) expect(Number.isFinite(o.balance)).toBe(true);
  for (const l of Object.values(w.leagues)) {
    expect(new Set(l.teamIds).size).toBe(l.teamIds.length);
    for (const tid of l.teamIds) expect(w.teams[tid].leagueId).toBe(l.id);
  }
}

describe('every title completes a valid season under its own rules', () => {
  it('simulates all seven titles through a full season (and only those seven)', () => {
    const w = withUser(makeWorld(TITLES.map((t) => t.id), 'all-titles'));
    const allowed = new Set(['mlbb', 'wildrift', 'hok', 'lol', 'dota2', 'valorant', 'cs2']);
    for (const x of [...Object.values(w.teams), ...Object.values(w.leagues), ...Object.values(w.comps)]) expect(allowed.has(x.titleId), x.titleId).toBe(true);
    for (const p of Object.values(w.players)) expect(allowed.has(p.titleId)).toBe(true);
    const season = w.season;
    advance(w, { kind: 'end-season' });
    expect(w.season).toBe(season + 1);
    const h = w.history.find((x) => x.season === season)!;
    expect(h).toBeTruthy();
    for (const t of TITLES) {
      const comps = h.champions.filter((c) => c.titleId === t.id);
      // Split 1, Split 2 for each league + MSI + Worlds.
      expect(comps.length, t.id).toBeGreaterThanOrEqual(4);
      expect(comps.some((c) => c.tier === 'worlds'), `${t.id} worlds`).toBe(true);
    }
    // All of last season's fixtures were played.
    const old = Object.values(w.fixtures).filter((f) => w.comps[f.compId]?.season === season);
    expect(old.every((f) => f.status === 'done')).toBe(true);
    // Season awards exist per title.
    for (const t of TITLES) expect(w.awards.some((a) => a.season === season && a.titleId === t.id && a.awardId === 'poy'), `${t.id} POY`).toBe(true);
    checkInvariants(w);
  });
});

describe('multi-season world', () => {
  it('stays consistent over 3 seasons (transfers, retirements, regens, rollovers)', () => {
    const w = withUser(makeWorld(['lol', 'valorant', 'mlbb', 'cs2'], 'multi'));
    const firstSeason = w.season;
    const startPlayers = Object.keys(w.players).length;
    advance(w, { kind: 'seasons', n: 3 });
    expect(w.season).toBe(firstSeason + 3);
    expect(w.history.length).toBe(3);
    checkInvariants(w);
    expect(Object.values(w.players).some((p) => p.retired)).toBe(true);
    expect(w.transfers.length).toBeGreaterThan(20);
    const active = Object.values(w.players).filter((p) => !p.retired).length;
    expect(active).toBeGreaterThan(startPlayers * 0.6);
    expect(active).toBeLessThan(startPlayers * 1.8);
    // Players age and develop: some young players improved.
    const improved = Object.values(w.players).filter((p) => p.caHistory.length >= 3 && p.caHistory[p.caHistory.length - 1].ca > p.caHistory[0].ca + 2);
    expect(improved.length).toBeGreaterThan(5);
    // Old detail data is pruned.
    expect(Object.values(w.comps).every((c) => c.season >= w.season - 1)).toBe(true);
    // Patches accumulate per title.
    expect(w.titles.lol.patches.length).toBeGreaterThan(3);
    void getTitle;
  });
});

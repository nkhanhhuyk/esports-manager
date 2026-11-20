import { describe, expect, it } from 'vitest';
import { makeWorld } from './helpers';
import { advance } from '../src/game/advance';
import type { Competition, World } from '../src/model/types';

function compsOf(w: World, season: number, titleId: string): Competition[] {
  return Object.values(w.comps).filter((c) => c.season === season && c.titleId === titleId);
}

describe('three-split season', () => {
  it('splits 1–3, First Stand (hard Fearless), MSI (double elimination) and Worlds in order', () => {
    const w = makeWorld(['lol', 'valorant'], 'three-splits', 'small');
    const season = w.season;
    for (const titleId of ['lol', 'valorant']) {
      const cs = compsOf(w, season, titleId);
      const leagues = Object.values(w.leagues).filter((l) => l.titleId === titleId);
      for (const l of leagues) expect(cs.filter((c) => c.leagueId === l.id).map((c) => c.split).sort()).toEqual([1, 2, 3]);
      const intl = cs.filter((c) => c.tier === 'international').sort((a, b) => a.startDay - b.startDay);
      expect(intl.map((c) => c.short)).toEqual([`${titleId === 'lol' ? 'LoL' : 'VAL'} FST`, `${titleId === 'lol' ? 'LoL' : 'VAL'} MSI`].map((x) => expect.stringContaining(x.split(' ')[1])));
      expect(intl[1].stages[intl[1].stages.length - 1].type).toBe('double-elim');
      // Fearless only where the draft allows it (MOBA sequential drafts).
      expect(intl[0].rules?.fearless).toBe(titleId === 'lol' ? 'global' : undefined);
      const split = (n: number) => cs.filter((c) => c.split === n && c.tier === 'league');
      expect(Math.max(...split(1).map((c) => c.startDay))).toBeLessThan(intl[0].startDay);
      expect(intl[0].startDay).toBeLessThan(Math.min(...split(2).map((c) => c.startDay)));
      expect(Math.max(...split(2).map((c) => c.startDay))).toBeLessThan(intl[1].startDay);
      expect(intl[1].startDay).toBeLessThan(Math.min(...split(3).map((c) => c.startDay)));
    }
    advance(w, { kind: 'end-season' }, { stopForUserMatch: false });
    const cs = compsOf(w, season, 'lol');
    const fs = cs.find((c) => c.rules?.fearless === 'global')!;
    const msi = cs.filter((c) => c.tier === 'international').find((c) => c !== fs)!;
    const worlds = cs.find((c) => c.tier === 'worlds')!;
    for (const c of [fs, msi, worlds]) expect(c.status, c.name).toBe('done');
    // First Stand entrants are split 1 finalists; MSI entrants come from split 2.
    const s1 = cs.filter((c) => c.split === 1 && c.tier === 'league');
    for (const tid of fs.teamIds) expect(s1.some((c) => c.placements.slice(0, 2).includes(tid)), tid).toBe(true);
    // Hard Fearless: within a First Stand series no champion is played twice, by either team.
    let multi = 0;
    for (const f of Object.values(w.fixtures).filter((x) => x.compId === fs.id && x.status === 'done')) {
      const rec = w.matches[f.id];
      if (!rec || rec.games.length < 2) continue;
      multi++;
      const seen = new Set<string>();
      for (const g of rec.games) for (const side of [0, 1] as const) for (const c of g.draft?.picks[side] ?? []) {
        expect(seen.has(c), `${f.id}: ${c} played twice`).toBe(false);
        seen.add(c);
      }
    }
    expect(multi).toBeGreaterThan(0);
    // MSI bracket: a lower bracket was played.
    const msiFx = Object.values(w.fixtures).filter((x) => x.compId === msi.id && x.nodeId);
    expect(msiFx.some((x) => x.nodeId!.startsWith('l'))).toBe(true);
    expect(msi.startDay).toBeGreaterThan(fs.startDay);
    expect(worlds.startDay).toBeGreaterThan(msi.startDay);
  }, 180_000);
});

import { describe, expect, it } from 'vitest';
import { makeWorld } from './helpers';
import { advance } from '../src/game/advance';
import { currentPatch, releasePatch } from '../src/systems/patches';
import { mobaRules, tacticalRules } from '../src/sim/common/rules';
import { getTitle } from '../src/data/titles/catalogue';
import { createSeries, playNextGame } from '../src/sim/series';
import type { Fixture, World } from '../src/model/types';

function fixture(w: World, id: string, titleId: string): Fixture {
  const teams = Object.values(w.teams).filter((t) => t.titleId === titleId && t.tier === 'main' && t.leagueId);
  const pool = currentPatch(w, titleId).mapPool;
  void pool;
  return { id, compId: Object.values(w.comps).find((c) => c.titleId === titleId)!.id, titleId, teams: [teams[0].id, teams[1].id], day: w.day, status: 'scheduled', format: { n: 1, label: 'Bo1' }, formatKey: 'standard', detail: true, spatial: true } as unknown as Fixture;
}

describe('patch cycle', () => {
  it('minor patches in the splits, medium patches before each international, a major after Worlds', () => {
    const w = makeWorld(['lol', 'valorant'], 'patch-cycle', 'small');
    const season = w.season;
    const start = w.day;
    advance(w, { kind: 'end-season' }, { stopForUserMatch: false });
    for (const id of ['lol', 'valorant']) {
      const ps = w.titles[id].patches.filter((p) => p.day > start);
      const events = Object.values(w.comps).filter((c) => c.season === season && c.titleId === id && (c.tier === 'international' || c.tier === 'worlds'));
      expect(events.length).toBe(3);
      // One medium patch 10 days before First Stand, MSI and Worlds.
      for (const c of events) expect(ps.some((p) => p.kind === 'medium' && p.day === c.startDay - 10), `${id} ${c.name}`).toBe(true);
      expect(ps.filter((p) => p.kind === 'minor').length).toBeGreaterThanOrEqual(3);
      // The major patch follows the World Championship and opens next season's numbering, with gameplay changes.
      const worlds = events.find((c) => c.tier === 'worlds')!;
      const major = ps.find((p) => p.kind === 'major')!;
      expect(major).toBeTruthy();
      expect(major.season).toBe(season + 1);
      expect(major.version).toBe(`${(season + 1) % 100}.01`);
      const lastWorldsDay = Math.max(...Object.values(w.fixtures).filter((f) => f.compId === worlds.id).map((f) => f.day));
      expect(major.day).toBeGreaterThan(lastWorldsDay);
      expect(major.notes.filter((n) => n.startsWith('Gameplay:')).length).toBeGreaterThanOrEqual(2);
      expect(major.rules).toBeTruthy();
      // No patch lands while an international is being played.
      for (const p of ps) for (const c of events) {
        const days = Object.values(w.fixtures).filter((f) => f.compId === c.id).map((f) => f.day);
        if (p.kind === 'minor') expect(p.day > Math.max(...days) || p.day < Math.min(...days), `${id} ${p.version} during ${c.name}`).toBe(true);
      }
    }
    // Next season's first patch after the rollover is not a second major.
    expect(w.titles.lol.patches.filter((p) => p.kind === 'major' && p.season === season + 1).length).toBe(1);
  }, 180_000);

  it('major patches change the rules the simulations use, and they accumulate', () => {
    const w = makeWorld(['lol', 'mlbb', 'cs2'], 'patch-rules', 'small');
    const seen = new Set<string>();
    for (let i = 0; i < 6; i++) {
      w.day += 1;
      const p = releasePatch(w, 'lol', 'major');
      for (const n of p.notes.filter((x) => x.startsWith('Gameplay:'))) seen.add(n);
    }
    expect(seen.size).toBeGreaterThanOrEqual(6);
    const t = getTitle('lol');
    const cfg = mobaRules(t, currentPatch(w, 'lol'));
    const base = t.moba!.objectives;
    const changed = cfg.objectives.length !== base.length || cfg.objectives.some((o) => {
      const b = base.find((x) => x.id === o.id);
      return !b || b.spawn !== o.spawn || b.respawn !== o.respawn || b.kind !== o.kind || b.value !== o.value || b.maxSpawns !== o.maxSpawns;
    });
    expect(changed || JSON.stringify(currentPatch(w, 'lol').rules?.moba ?? {}) !== '{}').toBe(true);
    // Shooter rules too.
    w.day += 1;
    releasePatch(w, 'cs2', 'major');
    w.day += 1;
    releasePatch(w, 'cs2', 'major');
    expect(currentPatch(w, 'cs2').rules?.tactical).toBeTruthy();
    expect(tacticalRules(getTitle('cs2'), currentPatch(w, 'cs2'))).not.toBe(getTitle('cs2').tactical);
  });

  it('an added objective (Atakhan) and a twice-spawning Rift Herald appear on the battlefield', () => {
    const w = makeWorld(['lol'], 'patch-atakhan', 'small');
    const p = currentPatch(w, 'lol');
    p.rules = {
      added: [{ id: 'atakhan', name: 'Atakhan', kind: 'buff', value: 0.08, spawn: 6, respawn: 99, maxSpawns: 1, major: true, pitAs: 'drake' }],
      objectives: { herald: { spawn: 9, respawn: 3, maxSpawns: 2, until: 19.9 } },
    };
    const f = fixture(w, 'pa-1', 'lol');
    const st = createSeries(w, f, true);
    st.record = true;
    const g = playNextGame(w, f, st);
    const spawns = g.replay!.events.filter((e) => e.k === 'spawn');
    expect(spawns.filter((e) => e.n === 'Atakhan').length).toBe(1);
    expect(g.replay!.objectives).toContain('Atakhan');
    const heralds = spawns.filter((e) => e.n === 'Rift Herald').length;
    expect(heralds).toBeGreaterThanOrEqual(1);
    expect(heralds).toBeLessThanOrEqual(2);
  });

  it('MLBB: a second Turtle spawns alongside the first', () => {
    const w = makeWorld(['mlbb'], 'patch-turtles', 'small');
    currentPatch(w, 'mlbb').rules = { added: [{ id: 'turtle2', name: 'Second Turtle', kind: 'gold', value: 160, spawn: 2, respawn: 2, until: 7.9, pitAs: 'turtle' }] };
    const f = fixture(w, 'pt-1', 'mlbb');
    const st = createSeries(w, f, true);
    st.record = true;
    const g = playNextGame(w, f, st);
    const ev = g.replay!.events.filter((e) => e.k === 'spawn' && (e.n === 'Turtle' || e.n === 'Second Turtle'));
    const first = ev.filter((e) => e.t < 125);
    expect(first.map((e) => e.n).sort()).toEqual(['Second Turtle', 'Turtle']);
    // They use different pits.
    expect(first[0].x === first[1].x && first[0].y === first[1].y).toBe(false);
  });
});

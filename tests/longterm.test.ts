import { describe, expect, it } from 'vitest';
import { makeWorld, withUser } from './helpers';
import { advance } from '../src/game/advance';
import { legacy } from '../src/systems/awards';

/**
 * Very long-term consistency. Default: 10 seasons with 3 titles (~30s).
 * LONG_SIM=1 runs 30 seasons with 5 titles (several minutes).
 */
const LONG = !!process.env.LONG_SIM;
const SEASONS = LONG ? 30 : 10;
const TITLES = LONG ? ['lol', 'cs2', 'mlbb', 'valorant', 'dota2'] : ['lol', 'cs2', 'hok'];

describe(`long-term world (${SEASONS} seasons)`, () => {
  it('remains stable, bounded and historically rich', () => {
    const w = withUser(makeWorld(TITLES, 'long'));
    const startActive = Object.values(w.players).filter((p) => !p.retired).length;
    const sizes: number[] = [];
    const t0 = Date.now();
    for (let s = 0; s < SEASONS; s++) {
      advance(w, { kind: 'seasons', n: 1 });
      sizes.push(JSON.stringify(w).length);
      const active = Object.values(w.players).filter((p) => !p.retired).length;
      expect(active).toBeGreaterThan(startActive * 0.6);
      expect(active).toBeLessThan(startActive * 2);
      for (const o of Object.values(w.orgs)) expect(Number.isFinite(o.balance)).toBe(true);
    }
    const ms = Date.now() - t0;
    console.log(`${SEASONS} seasons in ${(ms / 1000).toFixed(1)}s; save size ${(sizes[sizes.length - 1] / 1e6).toFixed(1)}MB (first season ${(sizes[0] / 1e6).toFixed(1)}MB)`);
    expect(w.history.length).toBe(SEASONS);
    // Save size stays bounded (pruning works): no runaway growth.
    expect(sizes[sizes.length - 1]).toBeLessThan(sizes[0] * 3);
    // Competitive hierarchy changes: more than one org won Worlds per title over the period.
    for (const t of TITLES) {
      const winners = new Set(w.history.flatMap((h) => h.champions.filter((c) => c.titleId === t && c.tier === 'worlds').map((c) => c.orgId)));
      expect(winners.size, `${t} world champions`).toBeGreaterThan(1);
    }
    // Careers span seasons, legends emerge, the Hall of Fame/GOAT system works.
    const best = Object.values(w.players).map((p) => legacy(p).total).sort((a, b) => b - a)[0];
    expect(best).toBeGreaterThan(40);
    expect(w.retired.length + Object.values(w.players).filter((p) => p.retired).length).toBeGreaterThan(50);
  });
});

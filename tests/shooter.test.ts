import { describe, expect, it } from 'vitest';
import { makeWorld } from './helpers';
import { createSeries, playNextGame } from '../src/sim/series';
import { shooterMap, VALORANT_MAP_SPECS, VALORANT_POOL } from '../src/sim/shooter/maps';
import { CS2_MAPS, CS2_POOL } from '../src/sim/shooter/cs2Maps';
import { routeLen, walkableS } from '../src/sim/shooter/map';
import { getTitle } from '../src/data/titles/catalogue';
import type { Fixture, GameResult, World } from '../src/model/types';

function play(w: World, titleId: string, mapId: string, record: boolean, k = 0): GameResult {
  const teams = Object.values(w.teams).filter((t) => t.titleId === titleId && t.tier === 'main' && t.leagueId);
  const f = { id: `sh-${titleId}-${mapId}-${k}`, compId: Object.values(w.comps).find((c) => c.titleId === titleId)!.id, titleId, teams: [teams[k].id, teams[k + 1].id], day: w.day, status: 'scheduled', format: { n: 1, label: 'Bo1' }, formatKey: 'standard', detail: true, spatial: true } as unknown as Fixture;
  const st = createSeries(w, f, true);
  st.slots[0].mapId = mapId;
  st.record = record;
  return playNextGame(w, f, st);
}

describe('real shooter maps', () => {
  it('Valorant competitive maps from official callouts; CS2 active duty; all routes connected', () => {
    expect(VALORANT_MAP_SPECS.length).toBeGreaterThanOrEqual(13);
    expect(getTitle('valorant').maps.map((m) => m.name)).toContain('Ascent');
    expect(getTitle('cs2').maps.map((m) => m.name)).toEqual(expect.arrayContaining(['Dust II', 'Mirage', 'Inferno', 'Nuke', 'Ancient', 'Anubis']));
    for (const id of [...VALORANT_POOL, ...CS2_POOL]) expect(getTitle(id.split(':')[0]).maps.some((m) => m.id === id), id).toBe(true);
    for (const s of [...VALORANT_MAP_SPECS, ...CS2_MAPS]) {
      const m = shooterMap(s.id)!;
      expect(m.sites.length, s.name).toBeGreaterThanOrEqual(2);
      expect(m.nodes[m.atkSpawn].y, `${s.name}: attackers spawn at the bottom`).toBeGreaterThan(m.nodes[m.defSpawn].y);
      for (const n of m.nodes) expect(Number.isFinite(routeLen(m, m.atkSpawn, n.id)), `${s.name} ${n.name}`).toBe(true);
      for (const n of m.nodes) expect(walkableS(m, n), `${s.name} ${n.name} walkable`).toBe(true);
    }
    expect(shooterMap('valorant:haven')!.sites.map((x) => x.id).sort()).toEqual(['A', 'B', 'C']);
    expect(shooterMap('valorant:lotus')!.sites.length).toBe(3);
  });
});

describe('spatial shooter rounds', () => {
  for (const [titleId, mapId] of [
    ['valorant', 'valorant:ascent'],
    ['cs2', 'cs2:dust2'],
  ] as const) {
    it(`${titleId}: a legal map on ${mapId.split(':')[1]}`, () => {
      const w = makeWorld([titleId], `sh-${titleId}`, 'small');
      const g = play(w, titleId, mapId, true);
      const cfg = getTitle(titleId).tactical!;
      const [a, b] = g.score;
      const hi = Math.max(a, b);
      const lo = Math.min(a, b);
      if (lo <= cfg.roundsToWin - 2) expect(hi).toBe(cfg.roundsToWin);
      else if (titleId === 'valorant') expect(hi - lo).toBe(2);
      else expect(hi - lo).toBeGreaterThanOrEqual(1);
      expect(g.score[g.winner]).toBe(hi);
      const sum = (team: number, k: string) => g.players.filter((l) => l.teamIdx === team).reduce((x, l) => x + (l.s[k] ?? 0), 0);
      expect(sum(0, 'k')).toBe(sum(1, 'd'));
      expect(sum(1, 'k')).toBe(sum(0, 'd'));
      const r = g.sreplay!;
      expect(r.rounds.length).toBe(a + b);
      const m = shooterMap(mapId)!;
      for (const ev of r.events.filter((e) => e.k === 'plant')) expect(m.sites.some((s) => Math.hypot(s.x - ev.x!, s.y - ev.y!) <= s.r + 2), 'plant on a site').toBe(true);
      // Dead players stay put for the rest of the round; live players are on walkable ground.
      for (let f = 1; f < r.frames; f++)
        for (let p = 0; p < 10; p++) {
          const k = f * 10 + p;
          if (r.php[k] > 0) expect(walkableS(m, { x: r.px[k], y: r.py[k] }), `frame ${f} player ${p}`).toBe(true);
          if (r.fr[f] === r.fr[f - 1] && r.php[k - 10] === 0) {
            expect(r.php[k]).toBe(0);
            expect([r.px[k], r.py[k]]).toEqual([r.px[k - 10], r.py[k - 10]]);
          }
        }
      // Defuses only after a plant in the same round.
      for (const rd of r.rounds.filter((x) => x.reason === 'defuse')) {
        const t0 = rd.from * r.dt;
        const t1 = (rd.to + 1) * r.dt;
        expect(r.events.some((e) => e.k === 'plant' && e.t >= t0 && e.t <= t1)).toBe(true);
      }
    });
  }

  it('watching (recording) never changes a map result', () => {
    for (const [titleId, mapId] of [
      ['valorant', 'valorant:haven'],
      ['cs2', 'cs2:inferno'],
    ] as const) {
      const w = makeWorld([titleId], `sheq-${titleId}`, 'small');
      const x = play(w, titleId, mapId, true, 1);
      const y = play(w, titleId, mapId, false, 1);
      const strip = (g: GameResult) => JSON.stringify({ s: g.score, w: g.winner, p: g.players.map((l) => [l.playerId, l.s]) });
      expect(strip(x)).toBe(strip(y));
      expect(y.sreplay).toBeUndefined();
    }
  });
});

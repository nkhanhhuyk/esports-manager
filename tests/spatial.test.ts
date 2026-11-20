import { describe, expect, it } from 'vitest';
import { makeWorld } from './helpers';
import { createSeries, playNextGame } from '../src/sim/series';
import { mobaMap, MOBA_MAP_TITLES } from '../src/sim/moba/maps';
import { walkable, routeDist, dist } from '../src/sim/moba/map';
import type { Fixture, GameResult, World } from '../src/model/types';

const MOBAS = ['mlbb', 'wildrift', 'hok', 'lol', 'dota2'];

function fixture(w: World, id: string, titleId: string, a = 0, b = 1): Fixture {
  const teams = Object.values(w.teams).filter((t) => t.titleId === titleId && t.tier === 'main' && t.leagueId);
  return { id, compId: Object.values(w.comps).find((c) => c.titleId === titleId)!.id, titleId, teams: [teams[a].id, teams[b].id], day: w.day, status: 'scheduled', format: { n: 1, label: 'Bo1' }, formatKey: 'standard', detail: true, spatial: true } as unknown as Fixture;
}

function play(w: World, f: Fixture, record: boolean): GameResult {
  const st = createSeries(w, f, true);
  st.record = record;
  return playNextGame(w, f, st);
}

describe('MOBA battlefield maps', () => {
  it('all five maps compile with connected navigation and legal placements', () => {
    expect(MOBA_MAP_TITLES.sort()).toEqual([...MOBAS].sort());
    const names = new Set<string>();
    for (const id of MOBAS) {
      const m = mobaMap(id);
      names.add(m.name);
      expect(m.sources.length, id).toBeGreaterThan(0);
      const N = m.nav.nodes.length;
      for (let j = 0; j < N; j++) expect(Number.isFinite(routeDist(m.nav, 0, j)), `${id} node ${j} reachable`).toBe(true);
      for (const n of m.nav.nodes) expect(walkable(m, n), `${id} node on walkable ground`).toBe(true);
      for (const c of m.camps) expect(walkable(m, c.pos), `${id} camp ${c.id}`).toBe(true);
      for (const p of m.pits) expect(walkable(m, p.pos), `${id} pit ${p.id}`).toBe(true);
      // Each team has a core; team 1's structures mirror team 0's.
      for (const side of [0, 1]) expect(m.structures.filter((s) => s.team === side && s.kind === 'core').length).toBe(1);
      expect(m.structures.filter((s) => s.team === 0).length).toBe(m.structures.filter((s) => s.team === 1).length);
      // Lanes run from base to base.
      for (const lane of ['top', 'mid', 'bot'] as const) {
        expect(dist(m.lanes[lane][0], m.cores[0])).toBeLessThan(180);
        expect(dist(m.lanes[lane][m.lanes[lane].length - 1], m.cores[1])).toBeLessThan(180);
      }
    }
    expect(names.size).toBe(5); // five distinct battlefields, not one reskin
  });

  it('title-specific structures and objectives', () => {
    const lol = mobaMap('lol');
    expect(lol.structures.filter((s) => s.team === 0 && s.kind === 'inhibitor').length).toBe(3);
    expect(lol.structures.filter((s) => s.team === 0 && s.kind === 'base-tower').length).toBe(2);
    const dota = mobaMap('dota2');
    expect(dota.sideNames).toEqual(['Radiant', 'Dire']);
    expect(dota.structures.filter((s) => s.team === 0 && s.kind === 'barracks').length).toBe(3);
    expect(dota.pits.filter((p) => p.objectives.includes('roshan')).length).toBe(2);
    const mlbb = mobaMap('mlbb');
    expect(mlbb.laneNames.top).toBe('EXP Lane');
    // Turtle pit sits on the EXP-lane side, the Lord pit on the Gold-lane side.
    const turtle = mlbb.pits.find((p) => p.id === 'turtle-pit')!;
    const lord = mlbb.pits.find((p) => p.id === 'lord-pit')!;
    expect(turtle.pos.x + turtle.pos.y).toBeLessThan(lord.pos.x + lord.pos.y);
    const hok = mobaMap('hok');
    expect(hok.laneNames).toEqual({ top: 'Clash Lane', mid: 'Mid Lane', bot: 'Farm Lane' });
  });
});

describe('spatial MOBA simulation', () => {
  for (const id of MOBAS) {
    it(`${id}: a legal, consistent game decided by the core`, () => {
      const w = makeWorld([id], `spatial-${id}`, 'small');
      const f = fixture(w, `sp-${id}`, id);
      const g = play(w, f, true);
      const m = mobaMap(id);
      expect(g.engine).toBe('spatial');
      expect([0, 1]).toContain(g.winner);
      const rp = g.replay!;
      expect(rp.mapId).toBe(m.id);
      // The game ends with the winner destroying the core.
      const core = rp.events.filter((e) => e.k === 'core');
      expect(core.length).toBe(1);
      // The replay is in map-side order (0 = Blue / Radiant); g.blue maps it to series order.
      const series = (side: number) => (g.blue === 1 ? 1 - side : side);
      expect(series(core[0].s!)).toBe(g.winner);
      rp.heroes.forEach((h) => expect(g.players.find((l) => l.playerId === h.playerId)!.teamIdx, h.handle).toBe(series(h.side)));
      // Kills equal the other side's deaths; replay K/D/A equals the box score.
      const sum = (team: number, k: string) => g.players.filter((l) => l.teamIdx === team).reduce((a, l) => a + (l.s[k] ?? 0), 0);
      expect(sum(0, 'k')).toBe(sum(1, 'd'));
      expect(sum(1, 'k')).toBe(sum(0, 'd'));
      expect(g.score).toEqual([sum(0, 'k'), sum(1, 'k')]);
      const kills = new Array(10).fill(0);
      const deaths = new Array(10).fill(0);
      const assists = new Array(10).fill(0);
      for (const e of rp.events) if (e.k === 'kill') {
        kills[e.a!]++;
        deaths[e.b!]++;
        for (const x of e.as ?? []) assists[x]++;
      }
      rp.heroes.forEach((h, i) => {
        const line = g.players.find((l) => l.playerId === h.playerId)!;
        expect(line.s.k, `${h.handle} kills`).toBe(kills[i]);
        expect(line.s.d, `${h.handle} deaths`).toBe(deaths[i]);
        expect(line.s.a, `${h.handle} assists`).toBe(assists[i]);
      });
      // Heroes are always on walkable ground; dead heroes do not move.
      for (let f2 = 0; f2 < rp.frames; f2++)
        for (let h = 0; h < 10; h++) {
          const k = f2 * 10 + h;
          if (rp.hst[k] & 1) continue;
          expect(walkable(m, { x: rp.hx[k], y: rp.hy[k] }), `${id} frame ${f2} hero ${h} at ${rp.hx[k]},${rp.hy[k]}`).toBe(true);
        }
      // Structures fall only after their prerequisites.
      const fallen = new Set<string>();
      for (const e of rp.events) {
        if (e.k !== 'structure' && e.k !== 'core') continue;
        const s = m.structures[e.b ?? -1] ?? m.structures.find((x) => x.kind === 'core' && x.team !== e.s)!;
        for (const r of s.requires) if (!r.startsWith('any:')) expect(fallen.has(r), `${id}: ${s.id} before ${r}`).toBe(true);
        fallen.add(s.id);
      }
      // First spawns use the objective's home pit (e.g. MLBB Lord on the Gold side).
      for (const e of rp.events.filter((x) => x.k === 'spawn')) {
        const pit = m.pits.reduce((best, p) => (dist(p.pos, { x: e.x!, y: e.y! }) < dist(best.pos, { x: e.x!, y: e.y! }) ? p : best));
        expect(pit.objectives.some((o) => rp.objectives.includes(e.n!) || o)).toBe(true);
      }
      const firstLord = rp.events.find((x) => x.k === 'spawn' && x.n === 'Lord');
      if (firstLord) expect(dist({ x: firstLord.x!, y: firstLord.y! }, m.pits.find((p) => p.id === 'lord-pit')!.pos)).toBeLessThan(5);
      // Objectives are secured at their pit.
      for (const e of rp.events.filter((x) => x.k === 'objective')) {
        const pit = m.pits.reduce((best, p) => (dist(p.pos, { x: e.x!, y: e.y! }) < dist(best.pos, { x: e.x!, y: e.y! }) ? p : best));
        expect(dist(pit.pos, { x: e.x!, y: e.y! })).toBeLessThan(5);
      }
    });
  }

  it('watching (recording) never changes a result, and the same seed replays identically', () => {
    for (const id of MOBAS) {
      const w = makeWorld([id], `eq-${id}`, 'small');
      const a = play(w, fixture(w, `eq-${id}`, id, 1, 2), true);
      const b = play(w, fixture(w, `eq-${id}`, id, 1, 2), false);
      const c = play(w, fixture(w, `eq-${id}`, id, 1, 2), false);
      const strip = (g: GameResult) => JSON.stringify({ w: g.winner, d: g.duration, s: g.score, p: g.players.map((l) => [l.playerId, l.s, l.rating]), t: g.teamStats });
      expect(strip(a), id).toBe(strip(b));
      expect(strip(b), id).toBe(strip(c));
      expect(b.replay).toBeUndefined();
    }
  });

  it('fidelity is locked before play: your fixtures use the battlefield simulation', () => {
    const w = makeWorld(['mlbb'], 'lock', 'small');
    const f = fixture(w, 'lock-1', 'mlbb');
    delete (f as Partial<Fixture>).spatial;
    const st = createSeries(w, f);
    expect(f.spatial).toBe(false); // no user or followed team involved
    const g = playNextGame(w, f, st);
    expect(g.engine).toBe('fast');
  });
});

describe('kill realism (no runaway blowouts)', () => {
  for (const id of ['lol', 'mlbb', 'hok']) {
    it(`${id}: no 40-kill teams, few multi-feeder losers, gaps within pro ranges`, async () => {
      const { createWorld } = await import('../src/gen/world');
      const w = createWorld({ name: 'kr', seed: `krt-${id}`, worldSize: 'small', titles: [id], startDay: 20000, dataMode: 'real' });
      const teams = Object.values(w.teams).filter((t) => t.titleId === id && t.tier === 'main' && t.leagueId && t.active);
      let feeders = 0;
      const gaps: number[] = [];
      let n = 0;
      for (let i = 0; i < 24; i++) {
        const a = teams[(i * 7) % teams.length];
        const b = teams[(i * 11 + 3) % teams.length];
        if (a === b) continue;
        const f = { id: `krt-${i}`, compId: Object.keys(w.comps)[0], titleId: id, teams: [a.id, b.id], day: w.day, status: 'scheduled', format: { n: 1, label: 'Bo1' }, formatKey: 'standard', detail: false, spatial: true } as unknown as Fixture;
        const g = playNextGame(w, f, createSeries(w, f));
        const k = [0, 1].map((s) => g.players.filter((l) => l.teamIdx === s).reduce((x, l) => x + (l.s.k ?? 0), 0));
        expect(Math.max(...k), `${a.name} vs ${b.name}`).toBeLessThanOrEqual(42);
        gaps.push(k[g.winner] - k[1 - g.winner]);
        if (g.players.filter((l) => l.teamIdx !== g.winner && (l.s.d ?? 0) >= 7).length >= 2) feeders++;
        n++;
      }
      expect(feeders / n).toBeLessThanOrEqual(0.25);
      expect([...gaps].sort((x, y) => x - y)[Math.floor(n * 0.9)]).toBeLessThanOrEqual(32);
    }, 60_000);
  }
});

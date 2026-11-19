import { describe, expect, it } from 'vitest';
import { ATTRS, ATTR_COUNT, GROUP_INDICES, effectiveMechanic, effectiveAttr, summaries, INPUTS } from '../src/model/attributes';
import { TITLES, TITLE_BY_ID, titleSimilarity, MOBA_TITLES, SHOOTER_TITLES } from '../src/data/titles/catalogue';
import { generatePlayer } from '../src/gen/player';
import { RNG } from '../src/core/rng';
import { makeWorld } from './helpers';
import { effectiveness, E_WEIGHTS } from '../src/sim/common/ratings';
import { switchTitle } from '../src/systems/transfers';

describe('86-attribute framework', () => {
  it('has exactly 86 attributes in the specified groups', () => {
    expect(ATTRS.length).toBe(86);
    expect(ATTR_COUNT).toBe(86);
    const sizes = Object.fromEntries(Object.entries(GROUP_INDICES).map(([g, v]) => [g, v.length]));
    expect(sizes).toEqual({ Mechanical: 10, Combat: 10, Tactical: 12, Mental: 10, Teamwork: 6, Knowledge: 7, Development: 7, MOBA: 10, Shooter: 14 });
    expect(new Set(ATTRS.map((a) => a.key)).size).toBe(86);
  });

  it('applies the input adaptation formula Effective = M x (0.65 + 0.35 P/100)', () => {
    expect(effectiveMechanic(80, 0)).toBeCloseTo(52, 6);
    expect(effectiveMechanic(80, 100)).toBeCloseTo(80, 6);
    expect(effectiveMechanic(80, 50)).toBeCloseTo(80 * 0.825, 6);
    const attrs = new Array(86).fill(80);
    const decisionMaking = ATTRS.find((a) => a.key === 'decisionMaking')!.index;
    const micro = ATTRS.find((a) => a.key === 'microControl')!.index;
    // Mental attributes do not receive the input penalty; execution skills do.
    expect(effectiveAttr(attrs, decisionMaking, 0)).toBe(80);
    expect(effectiveAttr(attrs, micro, 0)).toBeCloseTo(52, 6);
  });

  it('derives summaries without storing them as attributes', () => {
    const s = summaries(new Array(86).fill(50));
    expect(Object.keys(s)).toContain('Mechanics');
    expect(Object.keys(s)).toContain('Strategic Intelligence');
    expect(s.Mechanics).toBe(50);
  });
});

describe('player generation', () => {
  const world = makeWorld(['lol', 'valorant'], 'gen');
  it('creates complete, valid players for every title', () => {
    const rng = new RNG('players');
    for (const t of TITLES) {
      const p = generatePlayer({ world, rng, handles: new Set() }, { titleId: t.id, nat: 'KR', age: 20, targetCA: 70 });
      expect(p.attrs.length).toBe(86);
      for (const v of p.attrs) expect(v >= 0 && v <= 100).toBe(true);
      expect(p.input.length).toBe(INPUTS.length);
      expect(p.prof.titles[t.id]).toBeGreaterThan(0);
      expect(p.prof.roles[`${t.id}:${p.roles[0]}`]).toBeGreaterThan(0);
      if (t.characters.length) expect(Object.keys(p.prof.chars[t.id] ?? {}).length).toBeGreaterThan(0);
      expect(Math.abs(p.ca - 70)).toBeLessThan(8);
      expect(p.pa).toBeGreaterThanOrEqual(p.ca);
      // Required input is the title's assigned competitive input.
      const idx = t.input === 'touch' ? 0 : t.input === 'mkb' ? 1 : 2;
      expect(p.input[idx]).toBeGreaterThanOrEqual(30);
    }
  });

  it('uses unique handles and culturally appropriate nationalities', () => {
    const ps = Object.values(world.players);
    const handles = new Set(ps.map((p) => p.handle.toLowerCase()));
    expect(handles.size).toBe(ps.length);
    expect(new Set(ps.map((p) => p.nat)).size).toBeGreaterThan(5);
  });

  it('computes E with the 0.40/0.30/0.15/0.10/0.05 weights', () => {
    expect(E_WEIGHTS).toEqual({ C: 0.4, S: 0.3, T: 0.15, R: 0.1, P: 0.05 });
    const p = Object.values(world.players).find((x) => x.titleId === 'lol')!;
    const t = TITLE_BY_ID.lol;
    const role = t.roles.find((r) => r.id === p.roles[0])!;
    const base = effectiveness(p, t, role);
    const fam = p.prof.titles.lol;
    p.prof.titles.lol = fam + 10;
    p.attrVersion++;
    expect(effectiveness(p, t, role) - base).toBeCloseTo(1.5, 5); // 0.15 x 10
    p.prof.titles.lol = fam;
  });

  it('penalises execution on an unfamiliar input', () => {
    const p = Object.values(world.players).find((x) => x.titleId === 'valorant')!;
    const t = TITLE_BY_ID.valorant;
    const role = t.roles.find((r) => r.id === p.roles[0])!;
    expect(effectiveness(p, t, role, undefined, 10)).toBeLessThan(effectiveness(p, t, role, undefined, 95));
  });
});

describe('title catalogue', () => {
  it('contains the 30 specified titles with the deliberate input assignments', () => {
    expect(TITLES.length).toBe(30);
    expect(MOBA_TITLES.length).toBe(13);
    expect(SHOOTER_TITLES.length).toBe(17);
    const expected: Record<string, string> = {
      mlbb: 'touch', wildrift: 'touch', hok: 'touch', lol: 'mkb', dota2: 'mkb', vainglory: 'touch', aov: 'touch', msw: 'touch', unite: 'controller', hots: 'mkb', smite2: 'controller', predecessor: 'controller', brawlstars: 'touch',
      valorant: 'mkb', valmobile: 'touch', cs2: 'mkb', rivals: 'mkb', overwatch: 'mkb', r6: 'controller', apex: 'controller', warzone: 'controller', fortnite: 'controller', pubg: 'mkb', pubgm: 'touch', paladins: 'mkb', deadlock: 'mkb', codm: 'touch', cod: 'controller', wzm: 'touch', owrush: 'touch',
    };
    for (const [id, input] of Object.entries(expected)) expect(TITLE_BY_ID[id].input, id).toBe(input);
    expect(TITLE_BY_ID.deadlock.family).toBe('SHOOTER');
    expect(TITLE_BY_ID.deadlock.engine).toBe('hybrid');
    expect(TITLE_BY_ID.brawlstars.family).toBe('MOBA');
    expect(TITLE_BY_ID.brawlstars.engine).toBe('brawl');
    expect(TITLE_BY_ID.owrush.id).not.toBe(TITLE_BY_ID.overwatch.id);
  });

  it('uses the specified series formats', () => {
    const f = (id: string) => ['standard', 'playoff', 'final'].map((k) => TITLE_BY_ID[id].formats[k as 'standard'].n);
    for (const id of ['mlbb', 'wildrift', 'hok', 'aov', 'hots']) expect(f(id)).toEqual([3, 5, 7]);
    for (const id of ['lol', 'vainglory', 'msw', 'unite', 'smite2', 'predecessor']) expect(f(id)).toEqual([3, 5, 5]);
    expect(f('dota2')).toEqual([3, 3, 5]);
    expect(f('brawlstars')).toEqual([3, 5, 7]);
    expect(TITLE_BY_ID.brawlstars.formats.standard.kind).toBe('sets');
    expect(TITLE_BY_ID.brawlstars.formats.standard.setWins).toBe(2);
    for (const id of ['valorant', 'valmobile', 'cs2', 'r6', 'deadlock']) expect(f(id)).toEqual([3, 3, 5]);
    for (const id of ['rivals', 'overwatch', 'paladins', 'codm', 'cod']) expect(f(id)).toEqual([5, 5, 7]);
    expect(f('owrush')).toEqual([3, 5, 7]);
    expect(TITLE_BY_ID.owrush.modes.map((m) => m.kind)).toEqual(['control']);
    expect(TITLE_BY_ID.owrush.modes[0].params.roundsToWin).toBe(2);
    for (const id of ['apex', 'pubg', 'pubgm', 'fortnite', 'warzone', 'wzm']) {
      expect(f(id)).toEqual([6, 6, 12]);
      expect(TITLE_BY_ID[id].formats.standard.kind).toBe('br');
    }
  });

  it('gives every title its own legal lineup, roles and engine configuration', () => {
    for (const t of TITLES) {
      expect(t.lineup.length, t.id).toBe(t.teamSize);
      for (const r of t.lineup) expect(t.roles.some((x) => x.id === r), `${t.id}:${r}`).toBe(true);
      const cfg = t.moba ?? t.tactical ?? t.hero ?? t.arena ?? t.br ?? t.brawl;
      expect(cfg, t.id).toBeTruthy();
    }
    // Objectives are title specific, not one generic set.
    const objSets = TITLES.filter((t) => t.moba).map((t) => t.moba!.objectives.map((o) => o.name).join(','));
    expect(new Set(objSets).size).toBe(objSets.length);
  });

  it('follows the similarity guidelines', () => {
    expect(titleSimilarity('lol', 'lol')).toBe(1);
    expect(titleSimilarity('lol', 'wildrift')).toBeGreaterThanOrEqual(0.8);
    expect(titleSimilarity('lol', 'wildrift')).toBeLessThanOrEqual(0.95);
    expect(titleSimilarity('valorant', 'cs2')).toBeGreaterThanOrEqual(0.65);
    const cross = titleSimilarity('mlbb', 'cs2');
    expect(cross).toBeGreaterThanOrEqual(0.1);
    expect(cross).toBeLessThanOrEqual(0.35);
  });
});

describe('cross-title switching', () => {
  it('keeps permanent attributes and transfers familiarity by similarity', () => {
    const world = makeWorld(['lol'], 'switch');
    const p = Object.values(world.players).find((x) => x.titleId === 'lol' && (x.prof.titles.lol ?? 0) > 70)!;
    const attrs = [...p.attrs];
    const a = JSON.parse(JSON.stringify(p));
    const b = JSON.parse(JSON.stringify(p));
    switchTitle(world, a, 'wildrift');
    switchTitle(world, b, 'cs2');
    expect(a.attrs).toEqual(attrs);
    expect(b.attrs).toEqual(attrs);
    expect(a.titleId).toBe('wildrift');
    expect(a.prof.titles.wildrift).toBeGreaterThan(b.prof.titles.cs2);
    expect(a.career.at(-1).type).toBe('title-switch');
  });
});

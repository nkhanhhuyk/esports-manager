import { describe, expect, it } from 'vitest';
import { clone, makeWorld } from './helpers';
import { getTitle, legacyCharacters, TITLES } from '../src/data/titles/catalogue';
import { CHARACTER_SNAPSHOT, fitsRole } from '../src/data/titles/realCharacters';
import { ensureRealCharacters } from '../src/game/characterMigration';

describe('real character catalogs', () => {
  it('every MOBA and Valorant uses real, sourced characters; CS2 has none', () => {
    const expect_ = { mlbb: ['Tigreal', 'Layla', 'Fanny', 'Chou'], wildrift: ['Ahri', 'Jinx', 'Lee Sin'], hok: ['Arthur', 'Li Bai', 'Hou Yi', 'Daji'], lol: ['Ahri', 'Azir', 'Thresh', "Kai'Sa"], dota2: ['Anti-Mage', 'Invoker', 'Pudge'], valorant: ['Jett', 'Sova', 'Omen', 'Killjoy'] } as Record<string, string[]>;
    for (const [id, names] of Object.entries(expect_)) {
      const t = getTitle(id);
      expect(t.characters.length).toBeGreaterThan(id === 'valorant' ? 25 : 100);
      expect(t.characters.every((c) => c.real)).toBe(true);
      for (const n of names) expect(t.characters.map((c) => c.name)).toContain(n);
      const ids = new Set(t.characters.map((c) => c.id));
      expect(ids.size).toBe(t.characters.length);
      for (const c of t.characters) {
        expect(t.charClasses).toContain(c.cls);
        for (const l of c.lanes ?? []) expect(t.roles.map((r) => r.id)).toContain(l);
      }
      // Every role has a real pool to draft from.
      for (const role of t.roles) expect(t.characters.filter((c) => fitsRole(c, role)).length).toBeGreaterThanOrEqual(id === 'valorant' ? 4 : 8);
    }
    expect(getTitle('cs2').characters).toHaveLength(0);
    // Wild Rift is its own roster, not PC League's.
    expect(getTitle('wildrift').characters.length).not.toBe(getTitle('lol').characters.length);
    expect(CHARACTER_SNAPSHOT.id).toMatch(/^real-\d{4}-\d{2}-\d{2}$/);
  });

  it('old saves with generated characters are remapped to real ones of the same class, once', () => {
    const w = makeWorld(['mlbb', 'lol'], 'chars-migrate');
    const t = getTitle('mlbb');
    const legacy = legacyCharacters(t);
    // Simulate an old save: players' pools use legacy ids.
    const old = clone(w);
    delete old.meta.characterSnapshot;
    const p = Object.values(old.players).find((x) => x.titleId === 'mlbb')!;
    p.prof.chars.mlbb = { [legacy[0].id]: 80, [legacy[1].id]: 60 };
    for (const ts of Object.values(old.titles)) for (const patch of ts.patches) patch.charMeta = Object.fromEntries(legacy.map((c) => [c.id, 1]));
    expect(ensureRealCharacters(old)).toBe(true);
    const ids = new Set(t.characters.map((c) => c.id));
    for (const k of Object.keys(p.prof.chars.mlbb)) expect(ids.has(k)).toBe(true);
    const mapped = t.characters.find((c) => c.id === Object.keys(p.prof.chars.mlbb)[0])!;
    expect(mapped.cls).toBe(legacy[0].cls);
    expect(old.meta.characterSnapshot).toBe(CHARACTER_SNAPSHOT.id);
    expect(ensureRealCharacters(old)).toBe(false);
    // A new world never needs it.
    expect(ensureRealCharacters(w)).toBe(false);
    expect(TITLES.length).toBe(7);
  });
});

import 'fake-indexeddb/auto';
import { describe, expect, it } from 'vitest';
import { clone, makeWorld, withUser } from './helpers';
import { deleteSave, exportSave, importSave, listSaves, loadWorld, saveWorld } from '../src/persistence/db';
import { migrate, validateWorld, SaveError } from '../src/persistence/migrate';
import { SAVE_VERSION } from '../src/persistence/version';
import { advance } from '../src/game/advance';

describe('save / load integrity', () => {
  it('round-trips a world through IndexedDB unchanged', async () => {
    const w = withUser(makeWorld(['lol', 'pubgm'], 'persist'));
    advance(w, { kind: 'until', day: w.day + 65 });
    await saveWorld(w);
    const { world, recovered } = await loadWorld(w.meta.saveId);
    expect(recovered).toBe(false);
    expect(JSON.stringify(world)).toBe(JSON.stringify(w));
    expect((await listSaves()).some((s) => s.id === w.meta.saveId)).toBe(true);
  });

  it('keeps the previous autosave as a backup and recovers from corruption', async () => {
    const w = withUser(makeWorld(['cs2'], 'backup'));
    await saveWorld(w);
    advance(w, { kind: 'days', n: 3 }, { stopForUserMatch: false });
    await saveWorld(w);
    // Corrupt the main record.
    const { openDB } = await import('idb');
    const db = await openDB('ultimate-esports-manager', 1);
    await db.put('worlds', { meta: { version: SAVE_VERSION } }, w.meta.saveId);
    const { world, recovered } = await loadWorld(w.meta.saveId);
    expect(recovered).toBe(true);
    expect(world.meta.saveId).toBe(w.meta.saveId);
    await deleteSave(w.meta.saveId);
    expect((await listSaves()).some((s) => s.id === w.meta.saveId)).toBe(false);
  });

  it('exports and imports JSON saves', async () => {
    const w = withUser(makeWorld(['brawlstars'], 'export'));
    const text = await exportSave(w).text();
    const imported = await importSave(text);
    expect(imported.meta.name).toBe(w.meta.name);
    expect(Object.keys(imported.players).length).toBe(Object.keys(w.players).length);
  });
});

describe('save migration', () => {
  it('migrates a v1 save to the current schema', () => {
    const w = makeWorld(['overwatch'], 'migrate');
    const v1 = clone(w) as unknown as Record<string, any>;
    v1.meta.version = 1;
    delete v1.customFormats;
    delete v1.scoutFocus;
    for (const c of Object.values<any>(v1.comps)) delete c.pstats;
    const m = migrate(v1);
    expect(m.meta.version).toBe(SAVE_VERSION);
    expect(m.customFormats).toEqual({});
    expect(m.scoutFocus).toEqual([]);
    for (const c of Object.values(m.comps)) expect(c.pstats).toEqual({});
    // A migrated save can keep simulating.
    advance(m, { kind: 'until', day: m.day + 70 });
    expect(Object.values(m.fixtures).some((f) => f.status === 'done')).toBe(true);
  });

  it('rejects malformed and future saves', () => {
    expect(() => migrate({} as never)).toThrow(SaveError);
    const w = clone(makeWorld(['lol'], 'bad')) as unknown as Record<string, any>;
    w.meta.version = SAVE_VERSION + 1;
    expect(() => migrate(w)).toThrow(/newer/);
    const broken = clone(makeWorld(['lol'], 'bad2')) as unknown as Record<string, any>;
    const pid = Object.keys(broken.players)[0];
    broken.players[pid].attrs = [1, 2, 3];
    expect(() => validateWorld(broken)).toThrow();
  });
});

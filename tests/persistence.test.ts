import 'fake-indexeddb/auto';
import { describe, expect, it } from 'vitest';
import { clone, makeWorld, withUser } from './helpers';
import { deleteSave, exportSave, importSave, listSaves, loadWorld, saveWorld } from '../src/persistence/db';
import { migrate, validateWorld, SaveError } from '../src/persistence/migrate';
import { SAVE_VERSION } from '../src/persistence/version';
import { advance } from '../src/game/advance';

describe('save / load integrity', () => {
  it('round-trips a world through IndexedDB unchanged', async () => {
    const w = withUser(makeWorld(['lol', 'valorant'], 'persist'));
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
    // Corrupt the newest copy: the other slot (previous save) is the backup.
    const { openDB } = await import('idb');
    const db = await openDB('ultimate-esports-manager', 2);
    const meta = (await listSaves()).find((s) => s.id === w.meta.saveId)!;
    expect(meta.slot).toBe('b');
    await db.put('worlds', { meta: { version: SAVE_VERSION } }, `${w.meta.saveId}#b`);
    const { world, recovered } = await loadWorld(w.meta.saveId);
    expect(recovered).toBe(true);
    expect(world.meta.saveId).toBe(w.meta.saveId);
    expect(world.day).toBe(w.day - 3);
    await deleteSave(w.meta.saveId);
    expect((await listSaves()).some((s) => s.id === w.meta.saveId)).toBe(false);
    for (const k of [w.meta.saveId, `${w.meta.saveId}#a`, `${w.meta.saveId}#b`]) expect(await db.get('worlds', k)).toBeUndefined();
  });

  it('alternates save slots without rewriting a backup, and serialises overlapping saves', async () => {
    const w = withUser(makeWorld(['valorant'], 'slots'));
    const { openDB } = await import('idb');
    const db = await openDB('ultimate-esports-manager', 2);
    const m1 = await saveWorld(w);
    advance(w, { kind: 'days', n: 2 }, { stopForUserMatch: false });
    const m2 = await saveWorld(w);
    expect([m1.slot, m2.slot]).toEqual(['a', 'b']);
    expect(await db.get('backups', w.meta.saveId)).toBeUndefined();
    expect(((await db.get('worlds', `${w.meta.saveId}#a`)) as any).day).toBe(w.day - 2);
    // Overlapping saves (e.g. an autosave racing a manual save) never interleave.
    advance(w, { kind: 'days', n: 1 }, { stopForUserMatch: false });
    const metas = await Promise.all([saveWorld(w), saveWorld(w), saveWorld(w)]);
    expect(metas.map((m) => m.slot)).toEqual(['a', 'b', 'a']);
    const { world, recovered } = await loadWorld(w.meta.saveId);
    expect(recovered).toBe(false);
    expect(world.day).toBe(w.day);
  });

  it('loads saves written in the old single-slot layout and upgrades them on later saves', async () => {
    const w = withUser(makeWorld(['lol'], 'legacy'));
    const { openDB } = await import('idb');
    const db = await openDB('ultimate-esports-manager', 2);
    const { metaFor } = await import('../src/persistence/db');
    const legacyMeta = metaFor(w);
    await db.put('worlds', clone(w), w.meta.saveId);
    await db.put('saves', legacyMeta);
    const first = await loadWorld(w.meta.saveId);
    expect(first.recovered).toBe(false);
    expect(first.world.day).toBe(w.day);
    await saveWorld(w);
    expect(await db.get('worlds', w.meta.saveId)).toBeTruthy(); // kept as a fallback for now
    await saveWorld(w);
    expect(await db.get('worlds', w.meta.saveId)).toBeUndefined();
    expect((await loadWorld(w.meta.saveId)).world.day).toBe(w.day);
  });

  it('explains storage failures in plain words', async () => {
    const { describeStorageError } = await import('../src/persistence/db');
    expect(describeStorageError({ name: 'QuotaExceededError', message: 'x' })).toMatch(/storage is full/);
    expect(describeStorageError({ name: 'SecurityError', message: 'x' })).toMatch(/does not allow saving/);
  });

  it('exports and imports JSON saves', async () => {
    const w = withUser(makeWorld(['wildrift'], 'export'));
    const text = await exportSave(w).text();
    const imported = await importSave(text);
    expect(imported.meta.name).toBe(w.meta.name);
    expect(Object.keys(imported.players).length).toBe(Object.keys(w.players).length);
  });
});

describe('save migration', () => {
  it('migrates a v1 save to the current schema', () => {
    const w = makeWorld(['valorant'], 'migrate');
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

  it('migrates a v2 save by seeding tradable draft picks', () => {
    const w = makeWorld(['lol'], 'migrate3');
    const lg = Object.values(w.leagues).find((l) => l.tier === 1)!;
    lg.policy.draft = true;
    const v2 = clone(w) as unknown as Record<string, any>;
    v2.meta.version = 2;
    delete v2.draftPicks;
    const m = migrate(v2);
    expect(m.meta.version).toBe(SAVE_VERSION);
    expect(m.draftPicks.length).toBe(lg.teamIds.length * 4);
    expect(new Set(m.draftPicks.map((d) => d.id)).size).toBe(m.draftPicks.length);
    expect(m.draftPicks.every((d) => d.ownerTeamId === d.originalTeamId && !d.used)).toBe(true);
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

describe('keeping saves small', () => {
  it('slims old detailed records of other clubs (keeps user matches and finals) and rounds proficiencies', async () => {
    const { compactMatches, roundProficiencies, DETAIL_KEEP_DAYS } = await import('../src/game/compact');
    const w = withUser(makeWorld(['lol', 'valorant'], 'compact'));
    advance(w, { kind: 'until', day: w.day + 120 }, { stopForUserMatch: false });
    // Force a few detailed records of other clubs, older than the keep window.
    const uid = w.meta.userOrgId!;
    const others = Object.values(w.matches).filter((m) => m.teams.every((t) => w.teams[t]?.orgId !== uid) && w.fixtures[m.fixtureId]?.formatKey !== 'final').slice(0, 5);
    for (const m of others) {
      m.detail = true;
      m.day = w.day - DETAIL_KEEP_DAYS - 1;
      m.games[0].events = [{ t: 1, text: 'x' } as never];
    }
    const mine = Object.values(w.matches).find((m) => m.teams.some((t) => w.teams[t]?.orgId === uid))!;
    mine.detail = true;
    mine.day = w.day - 60;
    expect(compactMatches(w)).toBeGreaterThanOrEqual(others.length);
    for (const m of others) expect(w.matches[m.fixtureId].detail).toBe(false);
    for (const m of others) expect(w.matches[m.fixtureId].games[0].events).toBeUndefined();
    expect(w.matches[mine.fixtureId].detail).toBe(true);
    const p = Object.values(w.players).find((x) => !x.retired)!;
    p.prof.titles[p.titleId] = 63.35306963200715;
    roundProficiencies(w);
    expect(p.prof.titles[p.titleId]).toBe(63.35);
  });
});

describe('manual save slots', () => {
  it('saves copies of a career into slots 1-5 that load independently', async () => {
    const { saveToSlot, slotSaveId, MANUAL_SLOTS } = await import('../src/persistence/db');
    expect(MANUAL_SLOTS).toBe(5);
    const w = withUser(makeWorld(['mlbb'], 'slots-ui'));
    await saveToSlot(w, 1);
    const day1 = w.day;
    advance(w, { kind: 'days', n: 5 }, { stopForUserMatch: false });
    await saveToSlot(w, 3);
    const metas = await listSaves();
    const s1 = metas.find((m) => m.id === slotSaveId(1))!;
    const s3 = metas.find((m) => m.id === slotSaveId(3))!;
    expect(s1).toMatchObject({ manualSlot: 1, careerId: w.meta.saveId, day: day1 });
    expect(s3).toMatchObject({ manualSlot: 3, careerId: w.meta.saveId, day: w.day });
    expect((await loadWorld(slotSaveId(1))).world.day).toBe(day1);
    const l3 = (await loadWorld(slotSaveId(3))).world;
    expect(l3.day).toBe(w.day);
    expect(l3.meta.activeSlot).toBe(3);
    await expect(saveToSlot(w, 6)).rejects.toThrow(/5 save slots/);
    await deleteSave(slotSaveId(1));
    expect((await listSaves()).some((m) => m.id === slotSaveId(1))).toBe(false);
  });
});

describe('converting saves from the wider (30-title) edition', () => {
  /** Build a v4 save in which the Valorant scene is labelled as a removed title (Overwatch). */
  function legacySave(seed: string, onlyRemoved = false) {
    const w = withUser(makeWorld(['lol', 'valorant'], seed));
    const raw = clone(w) as unknown as Record<string, any>;
    const relabel = (o: any) => {
      if (o && o.titleId === 'valorant') o.titleId = 'overwatch';
    };
    for (const coll of ['teams', 'players', 'leagues', 'comps', 'fixtures', 'matches']) for (const x of Object.values<any>(raw[coll])) relabel(x);
    for (const s of Object.values<any>(raw.staff)) relabel(s);
    raw.titles.overwatch = raw.titles.valorant;
    delete raw.titles.valorant;
    raw.meta.titlesEnabled = onlyRemoved ? ['overwatch'] : ['lol', 'overwatch'];
    if (onlyRemoved) {
      for (const coll of ['teams', 'players', 'leagues']) for (const x of Object.values<any>(raw[coll])) if (x.titleId === 'lol') x.titleId = 'overwatch';
    }
    raw.meta.version = 4;
    delete raw.negotiations;
    return raw;
  }

  async function putLegacy(raw: Record<string, any>) {
    const { openDB } = await import('idb');
    const db = await openDB('ultimate-esports-manager', 2);
    const { metaFor } = await import('../src/persistence/db');
    await db.put('worlds', raw, raw.meta.saveId);
    await db.put('saves', { ...metaFor(raw as never), version: 4 });
    return db;
  }

  it('keeps the original as a backup, removes the excluded title and keeps every person', async () => {
    const { exportLegacyBackup } = await import('../src/persistence/db');
    const raw = legacySave('legacy-conv');
    const id = raw.meta.saveId;
    const owPlayers = Object.values<any>(raw.players).filter((p) => p.titleId === 'overwatch').map((p) => p.id);
    expect(owPlayers.length).toBeGreaterThan(20);
    await putLegacy(raw);
    const { world, converted } = await loadWorld(id);
    expect(converted).toBe(true);
    expect(world.meta.version).toBe(SAVE_VERSION);
    expect(world.meta.titlesEnabled).toEqual(['lol']);
    for (const coll of [world.teams, world.leagues, world.comps, world.fixtures] as Array<Record<string, { titleId: string }>>) for (const x of Object.values(coll)) expect(x.titleId).toBe('lol');
    // Every person survives with the same ID, now in an allowed title, as a free agent.
    for (const pid of owPlayers) {
      const p = world.players[pid];
      expect(p).toBeTruthy();
      expect(p.titleId).toBe('valorant');
      expect(p.teamId).toBeNull();
      expect(p.career.some((c) => c.type === 'title-switch' && /no longer part of the game/.test(c.note ?? ''))).toBe(true);
      expect(p.prof.titles.overwatch).toBeUndefined();
    }
    expect(world.inbox.some((m) => m.subject === 'Save converted to the seven-title edition')).toBe(true);
    // The untouched original remains exportable.
    const backup = await exportLegacyBackup(id);
    expect(backup).toBeTruthy();
    expect(await backup!.text()).toContain('"overwatch"');
    expect((await listSaves()).find((m) => m.id === id)?.legacyBackup).toBe(true);
    // The converted world keeps simulating.
    advance(world, { kind: 'days', n: 30 }, { stopForUserMatch: false });
    expect(Object.values(world.fixtures).some((f) => f.status === 'done')).toBe(true);
    // History labels for the removed title still resolve (read-only, archived).
    const { getTitle, isPlayableTitle } = await import('../src/data/titles/catalogue');
    expect(isPlayableTitle('overwatch')).toBe(false);
    expect(getTitle('overwatch').archived).toBe(true);
  });

  it('refuses to convert a save that only has removed titles, keeping the backup', async () => {
    const { exportLegacyBackup } = await import('../src/persistence/db');
    const raw = legacySave('legacy-only', true);
    await putLegacy(raw);
    await expect(loadWorld(raw.meta.saveId)).rejects.toThrow(/cannot be converted/);
    expect(await exportLegacyBackup(raw.meta.saveId)).toBeTruthy();
  });
});

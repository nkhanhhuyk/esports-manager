/**
 * IndexedDB persistence (offline, no server). Multiple save slots, autosave with a rolling
 * backup of the previous state, crash-recovery session marker, JSON import/export.
 */
import { openDB, type IDBPDatabase } from 'idb';
import type { World } from '../model/types';
import { migrate, SaveError } from './migrate';
import { formatDate } from '../core/date';

export interface SaveMeta {
  id: string;
  name: string;
  orgName: string;
  season: number;
  day: number;
  dateLabel: string;
  version: number;
  updated: number;
  titles: number;
}

const DB_NAME = 'ultimate-esports-manager';
let dbp: Promise<IDBPDatabase> | null = null;

function db(): Promise<IDBPDatabase> {
  if (!dbp) {
    dbp = openDB(DB_NAME, 1, {
      upgrade(d) {
        if (!d.objectStoreNames.contains('saves')) d.createObjectStore('saves', { keyPath: 'id' });
        if (!d.objectStoreNames.contains('worlds')) d.createObjectStore('worlds');
        if (!d.objectStoreNames.contains('backups')) d.createObjectStore('backups');
        if (!d.objectStoreNames.contains('session')) d.createObjectStore('session');
      },
    });
  }
  return dbp;
}

export function metaFor(world: World): SaveMeta {
  return {
    id: world.meta.saveId,
    name: world.meta.name,
    orgName: world.meta.userOrgId ? world.orgs[world.meta.userOrgId]?.name ?? '—' : '—',
    season: world.season,
    day: world.day,
    dateLabel: formatDate(world.day),
    version: world.meta.version,
    updated: Date.now(),
    titles: world.meta.titlesEnabled.length,
  };
}

/** Atomic save: previous state moves to `backups`, new state + metadata written together. */
export async function saveWorld(world: World): Promise<SaveMeta> {
  world.meta.updated = Date.now();
  const d = await db();
  const tx = d.transaction(['saves', 'worlds', 'backups', 'session'], 'readwrite');
  const prev = await tx.objectStore('worlds').get(world.meta.saveId);
  if (prev) await tx.objectStore('backups').put(prev, world.meta.saveId);
  await tx.objectStore('worlds').put(world, world.meta.saveId);
  const meta = metaFor(world);
  await tx.objectStore('saves').put(meta);
  await tx.objectStore('session').put({ saveId: world.meta.saveId, dirty: false, at: Date.now() }, 'current');
  await tx.done;
  return meta;
}

export async function listSaves(): Promise<SaveMeta[]> {
  const d = await db();
  const all = (await d.getAll('saves')) as SaveMeta[];
  return all.sort((a, b) => b.updated - a.updated);
}

export async function loadWorld(id: string): Promise<{ world: World; recovered: boolean }> {
  const d = await db();
  const raw = await d.get('worlds', id);
  try {
    if (!raw) throw new SaveError('Save not found.');
    return { world: migrate(raw), recovered: false };
  } catch (e) {
    const backup = await d.get('backups', id);
    if (backup) return { world: migrate(backup), recovered: true };
    throw e;
  }
}

export async function deleteSave(id: string): Promise<void> {
  const d = await db();
  const tx = d.transaction(['saves', 'worlds', 'backups'], 'readwrite');
  await tx.objectStore('saves').delete(id);
  await tx.objectStore('worlds').delete(id);
  await tx.objectStore('backups').delete(id);
  await tx.done;
}

/** Marks that unsaved progress exists (used to offer recovery after a crash / closed tab). */
export async function markDirty(saveId: string): Promise<void> {
  const d = await db();
  await d.put('session', { saveId, dirty: true, at: Date.now() }, 'current');
}

export async function getSession(): Promise<{ saveId: string; dirty: boolean; at: number } | undefined> {
  const d = await db();
  return d.get('session', 'current');
}

export function exportSave(world: World): Blob {
  return new Blob([JSON.stringify(world)], { type: 'application/json' });
}

export async function importSave(text: string): Promise<World> {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    throw new SaveError('File is not valid JSON.');
  }
  const world = migrate(raw as Record<string, unknown>);
  const existing = await listSaves();
  if (existing.some((s) => s.id === world.meta.saveId)) world.meta.saveId = `${world.meta.saveId}-import-${Date.now().toString(36)}`;
  await saveWorld(world);
  return world;
}

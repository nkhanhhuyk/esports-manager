/**
 * IndexedDB persistence (offline, no server). Multiple save slots, autosave with a rolling
 * backup of the previous state, crash-recovery session marker, JSON import/export.
 *
 * Each save alternates between two physical slots (`<id>#a` / `<id>#b`); the metadata row points
 * at the newest. Writing never has to read the previous world back, and the other slot is the
 * backup. Saves made by older builds (`worlds[<id>]` + `backups[<id>]`) still load and are
 * migrated to the slot layout on the next saves.
 */
import { openDB, type IDBPDatabase } from 'idb';
import type { World } from '../model/types';
import { migrate, SaveError } from './migrate';
import { convertToSevenTitles, removedTitlesIn, ScopeConversionError } from '../game/scope';
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
  /** Slot holding the newest copy (absent for saves written by older builds). */
  slot?: 'a' | 'b';
  /** The untouched pre-seven-title original is kept and can be exported. */
  legacyBackup?: boolean;
  /** Manual save slot number (1-5); absent for a career's autosave. */
  manualSlot?: number;
  /** Career (autosave id) this copy belongs to. */
  careerId?: string;
}

export const MANUAL_SLOTS = 5;
export const slotSaveId = (n: number) => `slot-${n}`;

type Slot = 'a' | 'b';
const slotKey = (id: string, slot: Slot) => `${id}#${slot}`;
const legacyKey = (id: string) => `${id}#pre-7-title`;
const otherSlot = (s: Slot): Slot => (s === 'a' ? 'b' : 'a');

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

let chain: Promise<unknown> = Promise.resolve();

/**
 * Atomic save into the slot not holding the newest copy, then flip the metadata pointer (same
 * transaction). Saves are serialised so overlapping requests never interleave.
 */
export function saveWorld(world: World, id: string = world.meta.saveId, extra: Partial<SaveMeta> = {}): Promise<SaveMeta> {
  const run = chain.then(
    () => writeWorld(world, id, extra),
    () => writeWorld(world, id, extra),
  );
  chain = run.catch(() => undefined);
  return run;
}

/** Save a copy of the current career into manual slot n (1-5). */
export function saveToSlot(world: World, n: number): Promise<SaveMeta> {
  if (!(n >= 1 && n <= MANUAL_SLOTS)) return Promise.reject(new SaveError(`There are only ${MANUAL_SLOTS} save slots.`));
  world.meta.activeSlot = n;
  return saveWorld(world, slotSaveId(n), { manualSlot: n, careerId: world.meta.saveId });
}

async function writeWorld(world: World, id: string, extra: Partial<SaveMeta>): Promise<SaveMeta> {
  world.meta.updated = Date.now();
  const d = await db();
  const tx = d.transaction(['saves', 'worlds', 'backups', 'session'], 'readwrite');
  const prev = (await tx.objectStore('saves').get(id)) as SaveMeta | undefined;
  const slot: Slot = prev?.slot ? otherSlot(prev.slot) : 'a';
  await tx.objectStore('worlds').put(world, slotKey(id, slot));
  const meta: SaveMeta = { ...metaFor(world), id, careerId: world.meta.saveId, ...extra, slot, legacyBackup: prev?.legacyBackup };
  await tx.objectStore('saves').put(meta);
  // Once both slots exist, the old single-slot copies (older builds) are no longer needed.
  if (prev?.slot) {
    await tx.objectStore('worlds').delete(id);
    await tx.objectStore('backups').delete(id);
  }
  if (id === world.meta.saveId) await tx.objectStore('session').put({ saveId: id, dirty: false, at: Date.now() }, 'current');
  await tx.done;
  return meta;
}

/** A readable explanation for storage failures (quota, blocked storage, private windows). */
export function describeStorageError(e: unknown): string {
  const name = (e as { name?: string })?.name ?? '';
  const msg = (e as Error)?.message ?? String(e);
  if (name === 'QuotaExceededError' || /quota/i.test(msg)) return 'Browser storage is full. Delete old saves from the Load list, or export this career to a file (Settings → Export save file).';
  if (typeof indexedDB === 'undefined' || name === 'SecurityError' || name === 'InvalidStateError' || /indexeddb|not allowed|denied|security/i.test(msg))
    return 'This browser does not allow saving here (private window or local-file restrictions). Use Settings → Export save file, or open the game in Chrome, Edge or Firefox.';
  return msg;
}

/** Ask the browser not to evict saves under storage pressure (best effort, once per session). */
let persistAsked = false;
export function requestPersistentStorage(): void {
  if (persistAsked) return;
  persistAsked = true;
  try {
    void navigator.storage?.persist?.().catch(() => undefined);
  } catch {
    /* unsupported */
  }
}

export async function listSaves(): Promise<SaveMeta[]> {
  const d = await db();
  const all = (await d.getAll('saves')) as SaveMeta[];
  return all.sort((a, b) => b.updated - a.updated);
}

/**
 * Migrate a raw save and convert it to the seven-title scope if needed. Conversion failures are
 * reported as SaveErrors (the caller keeps any backup).
 */
function migrateAndConvert(raw: unknown): { world: World; converted: boolean } {
  const world = migrate(raw as Record<string, unknown>);
  try {
    return { world, converted: !!convertToSevenTitles(world) };
  } catch (e) {
    if (e instanceof ScopeConversionError) throw new SaveError(e.message);
    throw e;
  }
}

/** Load the newest copy; if it is missing or corrupt, fall back to the other slot / older layout. */
export async function loadWorld(id: string): Promise<{ world: World; recovered: boolean; converted?: boolean }> {
  const d = await db();
  const meta = (await d.get('saves', id)) as SaveMeta | undefined;
  const candidates: Array<[string, string]> = [];
  if (meta?.slot) candidates.push(['worlds', slotKey(id, meta.slot)], ['worlds', slotKey(id, otherSlot(meta.slot))]);
  candidates.push(['worlds', id], ['backups', id]);
  let error: unknown = null;
  for (let i = 0; i < candidates.length; i++) {
    const raw = (await d.get(candidates[i][0], candidates[i][1])) as Record<string, any> | undefined;
    if (!raw) continue;
    // Keep the untouched original before a save is converted to the seven-title scope.
    if (removedTitlesIn(raw).length && !(await d.get('backups', legacyKey(id)))) {
      await d.put('backups', structuredClone(raw), legacyKey(id));
      if (meta) await d.put('saves', { ...meta, legacyBackup: true });
    }
    try {
      const { world, converted } = migrateAndConvert(raw);
      return { world, recovered: i > 0, converted };
    } catch (e) {
      error ??= e;
    }
  }
  throw error ?? new SaveError('Save not found.');
}

/** Export the untouched pre-seven-title original of a converted save (JSON). */
export async function exportLegacyBackup(id: string): Promise<Blob | null> {
  const d = await db();
  const raw = await d.get('backups', legacyKey(id));
  return raw ? new Blob([JSON.stringify(raw)], { type: 'application/json' }) : null;
}

export async function deleteSave(id: string): Promise<void> {
  const d = await db();
  const tx = d.transaction(['saves', 'worlds', 'backups'], 'readwrite');
  await tx.objectStore('saves').delete(id);
  for (const k of [id, slotKey(id, 'a'), slotKey(id, 'b')]) await tx.objectStore('worlds').delete(k);
  await tx.objectStore('backups').delete(id);
  await tx.objectStore('backups').delete(legacyKey(id));
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
  const { world } = migrateAndConvert(raw);
  const existing = await listSaves();
  if (existing.some((s) => s.id === world.meta.saveId)) world.meta.saveId = `${world.meta.saveId}-import-${Date.now().toString(36)}`;
  await saveWorld(world);
  return world;
}

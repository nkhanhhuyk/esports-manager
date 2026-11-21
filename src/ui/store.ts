/**
 * Application state (Zustand). The World object is mutated by game systems; `version` is
 * bumped after every mutation so React re-renders. Persistence is explicit + autosave.
 */
import { create } from 'zustand';
import type { World } from '../model/types';
import type { AdvanceMode, AdvanceResult } from '../game/advance';
import { createWorldAsync } from '../worker/client';
import { advanceInSlices } from '../game/advance';
import { formatDate } from '../core/date';
import type { NewWorldOptions } from '../gen/world';
import { setUserOrg } from '../gen/world';
import { applyRoleDelegation, hasBoard, setBoardObjectives } from '../systems/career';
import { describeStorageError, listSaves, loadWorld, markDirty, requestPersistentStorage, saveToSlot, saveWorld, slotSaveId, type SaveMeta } from '../persistence/db';
import { clearRatingCache } from '../sim/common/ratings';

export interface Busy {
  label: string;
  done: number;
  total: number;
}

interface GameStore {
  world: World | null;
  version: number;
  busy: Busy | null;
  theme: 'dark' | 'light';
  toast: { text: string; kind: 'ok' | 'err' | 'info' } | null;
  saves: SaveMeta[];
  lastAdvance: AdvanceResult | null;
  /** Changes since the last successful save (warns before closing the tab). */
  unsaved: boolean;
  /** Set when browser storage is unavailable or failing. */
  storageError: string | null;
  refreshSaves: () => Promise<void>;
  newGame: (opts: NewWorldOptions) => Promise<void>;
  chooseOrg: (orgId: string) => Promise<void>;
  load: (id: string) => Promise<void>;
  save: (silent?: boolean) => Promise<void>;
  /** Save the current career into manual slot n (1-5). */
  saveSlot: (n: number) => Promise<boolean>;
  /** Header Save: write the active slot; 'choose' when the player must pick a slot first. */
  quickSave: () => Promise<'saved' | 'choose' | 'failed'>;
  closeGame: () => void;
  advance: (mode: AdvanceMode, stopForUserMatch?: boolean) => Promise<AdvanceResult | null>;
  /** Run a mutation against the world and re-render. */
  act: <T>(fn: (w: World) => T, opts?: { save?: boolean }) => T | undefined;
  touch: () => void;
  notify: (text: string, kind?: 'ok' | 'err' | 'info') => void;
  setTheme: (t: 'dark' | 'light') => void;
}

function initialTheme(): 'dark' | 'light' {
  try {
    const t = localStorage.getItem('uem-theme');
    if (t === 'light' || t === 'dark') return t;
  } catch {
    /* ignore */
  }
  return 'dark';
}

/**
 * Autosave cadence (device setting): after every advance, or once a week / month of game time, and
 * always at a new season and when Android puts the app in the background (src/ui/native.ts).
 */
export type AutosaveEvery = 'advance' | 'week' | 'month';
export function autosaveEvery(): AutosaveEvery {
  try {
    const v = localStorage.getItem('uem-autosave-every');
    return v === 'advance' || v === 'month' ? v : 'week';
  } catch {
    return 'week';
  }
}
export function setAutosaveEvery(v: AutosaveEvery): void {
  try {
    localStorage.setItem('uem-autosave-every', v);
  } catch {
    /* default */
  }
}
const lastAuto = new WeakMap<World, { day: number; season: number }>();
function autosaveDue(w: World): boolean {
  const last = lastAuto.get(w);
  if (!last || last.season !== w.season) return true;
  const every = autosaveEvery();
  return every === 'advance' || w.day - last.day >= (every === 'week' ? 7 : 30);
}
function markAutosaved(w: World): void {
  lastAuto.set(w, { day: w.day, season: w.season });
}

export const useGame = create<GameStore>((set, get) => ({
  world: null,
  version: 0,
  busy: null,
  theme: initialTheme(),
  toast: null,
  saves: [],
  lastAdvance: null,
  unsaved: false,
  storageError: null,
  refreshSaves: async () => {
    try {
      set({ saves: await listSaves(), storageError: null });
    } catch (e) {
      set({ saves: [], storageError: describeStorageError(e) });
    }
  },
  newGame: async (opts) => {
    set({ busy: { label: 'Generating world…', done: 0, total: 0 } });
    try {
      const world = await createWorldAsync(opts);
      clearRatingCache();
      set({ world, version: get().version + 1, busy: null });
    } catch (e) {
      set({ busy: null });
      get().notify(`World generation failed: ${(e as Error).message}`, 'err');
    }
  },
  chooseOrg: async (orgId) => {
    const w = get().world;
    if (!w) return;
    setUserOrg(w, orgId);
    applyRoleDelegation(w);
    if (hasBoard(w)) setBoardObjectives(w);
    set({ version: get().version + 1 });
    await get().save(true);
  },
  load: async (id) => {
    set({ busy: { label: 'Loading save…', done: 0, total: 0 } });
    try {
      const { world, recovered, converted } = await loadWorld(id);
      clearRatingCache();
      if (id.startsWith('slot-')) world.meta.activeSlot = Number(id.slice(5));
      set({ world, version: get().version + 1, busy: null, unsaved: false });
      if (recovered) get().notify('Main save was damaged — restored the previous autosave.', 'info');
      if (converted) {
        // Persist the converted world once (the original stays available as a backup).
        if (world.meta.activeSlot && id.startsWith('slot-')) await saveToSlot(world, world.meta.activeSlot);
        else await saveWorld(world);
        await get().refreshSaves();
        get().notify('This save was converted to the seven-title edition — see the Inbox. The original is kept as a backup (Load screen → Export original).', 'info');
      }
    } catch (e) {
      set({ busy: null });
      get().notify(`Could not load save: ${(e as Error).message}`, 'err');
    }
  },
  save: async (silent) => {
    const w = get().world;
    if (!w) return;
    try {
      await saveWorld(w);
      requestPersistentStorage();
      if (get().world === w) set({ unsaved: false, storageError: null });
      await get().refreshSaves();
      if (!silent) get().notify('Game saved.', 'ok');
    } catch (e) {
      const why = describeStorageError(e);
      set({ storageError: why });
      get().notify(`Save failed: ${why}`, 'err');
    }
  },
  saveSlot: async (n) => {
    const w = get().world;
    if (!w) return false;
    try {
      await saveToSlot(w, n);
      requestPersistentStorage();
      if (get().world === w) set({ unsaved: false, storageError: null, version: get().version + 1 });
      await get().refreshSaves();
      get().notify(`Saved to slot ${n}.`, 'ok');
      return true;
    } catch (e) {
      const why = describeStorageError(e);
      set({ storageError: why });
      get().notify(`Save failed: ${why}`, 'err');
      return false;
    }
  },
  quickSave: async () => {
    const w = get().world;
    if (!w) return 'failed';
    const n = w.meta.activeSlot;
    if (!n) return 'choose';
    // Never silently overwrite a slot that now holds a different career.
    const meta = get().saves.find((s) => s.id === slotSaveId(n));
    if (meta?.careerId && meta.careerId !== w.meta.saveId) return 'choose';
    return (await get().saveSlot(n)) ? 'saved' : 'failed';
  },
  closeGame: () => set({ world: null, version: get().version + 1 }),
  advance: async (mode, stopForUserMatch = true) => {
    const w = get().world;
    if (!w || get().busy) return null;
    set({ busy: { label: 'Simulating…', done: 0, total: 0 } });
    try {
      void markDirty(w.meta.saveId).catch(() => undefined);
      // Simulated in place in short slices (no copy of the world to a worker and back): steady on
      // phones with large real-data worlds, and faster because nothing is cloned.
      const result = await advanceInSlices(w, mode, stopForUserMatch, (done, total) => set({ busy: { label: 'Simulating…', done, total } }));
      set({ world: w, version: get().version + 1, busy: null, lastAdvance: result, unsaved: true });
      if (w.meta.autosave && autosaveDue(w)) {
        // After the screen has updated; compressed, and never twice at the same time.
        setTimeout(() => {
          if (get().world === w) void get().save(true).then(() => markAutosaved(w));
        }, 50);
      }
      return result;
    } catch (e) {
      set({ busy: null, version: get().version + 1 });
      get().notify(`Simulation error on ${formatDate(w.day + 1)}: ${(e as Error).message}. Progress up to ${formatDate(w.day)} is kept.`, 'err');
      return null;
    }
  },
  act: (fn, opts) => {
    const w = get().world;
    if (!w) return undefined;
    const r = fn(w);
    set({ version: get().version + 1, unsaved: true });
    if (opts?.save) void get().save(true);
    return r;
  },
  touch: () => set({ version: get().version + 1 }),
  notify: (text, kind = 'info') => {
    set({ toast: { text, kind } });
    setTimeout(() => {
      if (get().toast?.text === text) set({ toast: null });
    }, 4500);
  },
  setTheme: (t) => {
    try {
      localStorage.setItem('uem-theme', t);
    } catch {
      /* ignore */
    }
    set({ theme: t });
  },
}));

/** Subscribe a component to world changes. */
export function useWorld(): World {
  const world = useGame((s) => s.world);
  useGame((s) => s.version);
  if (!world) throw new Error('No world loaded');
  return world;
}

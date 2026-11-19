/**
 * Application state (Zustand). The World object is mutated by game systems; `version` is
 * bumped after every mutation so React re-renders. Persistence is explicit + autosave.
 */
import { create } from 'zustand';
import type { World } from '../model/types';
import type { AdvanceMode, AdvanceResult } from '../game/advance';
import { advanceAsync, createWorldAsync } from '../worker/client';
import type { NewWorldOptions } from '../gen/world';
import { setUserOrg } from '../gen/world';
import { listSaves, loadWorld, markDirty, saveWorld, type SaveMeta } from '../persistence/db';
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
  refreshSaves: () => Promise<void>;
  newGame: (opts: NewWorldOptions) => Promise<void>;
  chooseOrg: (orgId: string) => Promise<void>;
  load: (id: string) => Promise<void>;
  save: (silent?: boolean) => Promise<void>;
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

export const useGame = create<GameStore>((set, get) => ({
  world: null,
  version: 0,
  busy: null,
  theme: initialTheme(),
  toast: null,
  saves: [],
  lastAdvance: null,
  refreshSaves: async () => {
    try {
      set({ saves: await listSaves() });
    } catch {
      set({ saves: [] });
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
    set({ version: get().version + 1 });
    await get().save(true);
  },
  load: async (id) => {
    set({ busy: { label: 'Loading save…', done: 0, total: 0 } });
    try {
      const { world, recovered } = await loadWorld(id);
      clearRatingCache();
      set({ world, version: get().version + 1, busy: null });
      if (recovered) get().notify('Main save was damaged — restored the previous autosave.', 'info');
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
      await get().refreshSaves();
      if (!silent) get().notify('Game saved.', 'ok');
    } catch (e) {
      get().notify(`Save failed: ${(e as Error).message}`, 'err');
    }
  },
  closeGame: () => set({ world: null, version: get().version + 1 }),
  advance: async (mode, stopForUserMatch = true) => {
    const w = get().world;
    if (!w || get().busy) return null;
    set({ busy: { label: 'Simulating…', done: 0, total: 0 } });
    try {
      void markDirty(w.meta.saveId).catch(() => undefined);
      const { world, result } = await advanceAsync(w, mode, stopForUserMatch, (done, total) => set({ busy: { label: 'Simulating…', done, total } }));
      if (world !== w) clearRatingCache();
      set({ world, version: get().version + 1, busy: null, lastAdvance: result });
      if (world.meta.autosave) await get().save(true);
      return result;
    } catch (e) {
      set({ busy: null });
      get().notify(`Simulation error: ${(e as Error).message}`, 'err');
      return null;
    }
  },
  act: (fn, opts) => {
    const w = get().world;
    if (!w) return undefined;
    const r = fn(w);
    set({ version: get().version + 1 });
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

/**
 * Promise-based client for the simulation worker with a main-thread fallback.
 */
import { advance, type AdvanceMode, type AdvanceResult } from '../game/advance';
import { createWorld, type NewWorldOptions } from '../gen/world';
import type { World } from '../model/types';
import { makeWorker } from './factory';

type Progress = (done: number, total: number, day: number) => void;

let worker: Worker | null = null;
let seq = 0;
const pending = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void; onProgress?: Progress }>();

/** Set once the worker failed to load (e.g. blocked on file://); everything then runs on the main thread. */
let broken = false;

function getWorker(): Worker | null {
  if (typeof Worker === 'undefined' || broken) return null;
  if (!worker) {
    try {
      worker = makeWorker();
      worker.onmessage = (e) => {
        const m = e.data;
        const p = pending.get(m.id);
        if (!p) return;
        if (m.type === 'progress') p.onProgress?.(m.done, m.total, m.day);
        else if (m.type === 'error') {
          pending.delete(m.id);
          p.reject(new Error(m.message));
        } else {
          pending.delete(m.id);
          p.resolve(m);
        }
      };
      worker.onerror = (e) => {
        // A load/startup failure (or an uncaught crash): the request is retried on the main thread,
        // which is safe because the worker only ever received a structured clone of the world.
        e.preventDefault?.();
        console.warn(`[sim-worker] unavailable (${e.message || 'failed to load'}); simulating on the main thread.`);
        broken = true;
        for (const p of pending.values()) p.reject(new Error('no-worker'));
        pending.clear();
        worker?.terminate();
        worker = null;
      };
    } catch {
      worker = null;
      broken = true;
    }
  }
  return worker;
}

function call(payload: Record<string, unknown>, onProgress?: Progress): Promise<any> {
  const w = getWorker();
  if (!w) return Promise.reject(new Error('no-worker'));
  const id = ++seq;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject, onProgress });
    w.postMessage({ id, ...payload });
  });
}

export async function createWorldAsync(opts: NewWorldOptions): Promise<World> {
  try {
    const r = await call({ type: 'create', opts });
    return r.world as World;
  } catch {
    return createWorld(opts);
  }
}

/** Advance in the worker (large advances) or synchronously on the main thread (small ones). */
export async function advanceAsync(
  world: World,
  mode: AdvanceMode,
  stopForUserMatch: boolean,
  onProgress?: Progress,
  autosave = false,
): Promise<{ world: World; result: AdvanceResult; saved: boolean }> {
  const small = mode.kind === 'days' && mode.n <= 1;
  if (!small) {
    try {
      const r = await call({ type: 'advance', world, mode, stopForUserMatch, autosave }, onProgress);
      return { world: r.world as World, result: r.result as AdvanceResult, saved: !!r.saved };
    } catch (e) {
      if ((e as Error).message !== 'no-worker') throw e;
    }
  }
  const result = advance(world, mode, { stopForUserMatch, onProgress });
  return { world, result, saved: false };
}

/// <reference lib="webworker" />
/**
 * Simulation web worker: world generation and multi-day / multi-season advances run off the
 * UI thread. The world is structured-cloned in and out; results are identical to main-thread
 * simulation because all randomness is seeded.
 */
import { advance, type AdvanceMode } from '../game/advance';
import { createWorld, type NewWorldOptions } from '../gen/world';
import type { World } from '../model/types';
import { saveWorld } from '../persistence/db';

type Req =
  | { id: number; type: 'advance'; world: World; mode: AdvanceMode; stopForUserMatch?: boolean; autosave?: boolean }
  | { id: number; type: 'create'; opts: NewWorldOptions };

const ctx = self as unknown as DedicatedWorkerGlobalScope;

ctx.onmessage = async (e: MessageEvent<Req>) => {
  const msg = e.data;
  try {
    if (msg.type === 'create') {
      const world = createWorld(msg.opts);
      ctx.postMessage({ id: msg.id, type: 'done', world });
      return;
    }
    if (msg.type === 'advance') {
      let last = 0;
      const result = advance(msg.world, msg.mode, {
        stopForUserMatch: msg.stopForUserMatch,
        onProgress: (done, total, day) => {
          const now = Date.now();
          if (now - last > 120) {
            last = now;
            ctx.postMessage({ id: msg.id, type: 'progress', done, total, day });
          }
        },
      });
      // Autosave from the worker: the (large) structured clone into IndexedDB happens here instead
      // of blocking the UI thread. If storage is unavailable in the worker the UI saves instead.
      let saved = false;
      if (msg.autosave) {
        try {
          await saveWorld(msg.world);
          saved = true;
        } catch (err) {
          saved = false;
          console.warn(`[sim-worker] autosave unavailable in the worker (${err instanceof Error ? err.message : String(err)}); the page will save instead.`);
        }
      }
      ctx.postMessage({ id: msg.id, type: 'done', world: msg.world, result, saved });
    }
  } catch (err) {
    ctx.postMessage({ id: msg.id, type: 'error', message: err instanceof Error ? `${err.message}\n${err.stack}` : String(err) });
  }
};

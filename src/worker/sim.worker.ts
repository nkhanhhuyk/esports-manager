/// <reference lib="webworker" />
/**
 * Simulation web worker: world generation and multi-day / multi-season advances run off the
 * UI thread. The world is structured-cloned in and out; results are identical to main-thread
 * simulation because all randomness is seeded.
 */
import { advance, type AdvanceMode } from '../game/advance';
import { createWorld, type NewWorldOptions } from '../gen/world';
import type { World } from '../model/types';

type Req =
  | { id: number; type: 'advance'; world: World; mode: AdvanceMode; stopForUserMatch?: boolean }
  | { id: number; type: 'create'; opts: NewWorldOptions };

const ctx = self as unknown as DedicatedWorkerGlobalScope;

ctx.onmessage = (e: MessageEvent<Req>) => {
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
      ctx.postMessage({ id: msg.id, type: 'done', world: msg.world, result });
    }
  } catch (err) {
    ctx.postMessage({ id: msg.id, type: 'error', message: err instanceof Error ? `${err.message}\n${err.stack}` : String(err) });
  }
};

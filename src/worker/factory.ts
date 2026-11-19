/** Creates the simulation worker as a separate module file (standard web / Capacitor build). */
export function makeWorker(): Worker {
  return new Worker(new URL('./sim.worker.ts', import.meta.url), { type: 'module' });
}

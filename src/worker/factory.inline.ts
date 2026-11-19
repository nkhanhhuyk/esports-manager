/**
 * Portable single-file build: the worker is inlined into the bundle (Blob URL) so the game runs
 * from one HTML file opened straight from disk. If the browser refuses Blob workers on file://,
 * the client falls back to simulating on the main thread.
 */
import SimWorker from './sim.worker.ts?worker&inline';

export function makeWorker(): Worker {
  return new SimWorker();
}

import type { World } from './types';

/** Monotonic, save-persisted id allocation (never reuses ids, so history links stay valid). */
export function nextId(world: World, prefix: string): string {
  world.counter += 1;
  return `${prefix}${world.counter.toString(36)}`;
}

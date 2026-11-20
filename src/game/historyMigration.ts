/**
 * One-time addition of the real history snapshot (past teams, former pros, retired pros) to real-world
 * careers started before it existed. New real-world careers get it at creation. Nothing already in the
 * save changes: dormant teams are added outside every league and every former pro (long-inactive ones
 * included) joins the free-agent pool. Handles already in the save are skipped.
 */
import { RNG } from '../core/rng';
import { addRealHistory, orgKey } from '../gen/realWorld';
import { createOrg } from '../gen/world';
import { marketValue } from '../systems/contracts';
import { snapshotRetiredToFree } from '../systems/comeback';
import type { Organisation, World } from '../model/types';

export function ensureRealHistory(world: World): boolean {
  if (world.meta.realHistory) {
    // Saves from the build that archived long-inactive real pros as retired: make them free agents.
    if (!world.meta.realHistoryFree) {
      world.meta.realHistoryFree = true;
      return snapshotRetiredToFree(world) > 0;
    }
    return false;
  }
  world.meta.realHistoryFree = true;
  world.meta.realHistory = true;
  if (world.meta.dataMode !== 'real') return false;
  const rng = new RNG(`${world.meta.seed}|real-history`);
  const ctx = { world, rng, handles: new Set(Object.values(world.players).map((p) => p.handle.toLowerCase())) };
  const orgs = new Map<string, Organisation>();
  for (const o of Object.values(world.orgs)) if (o.real && !orgs.has(orgKey(o.name))) orgs.set(orgKey(o.name), o);
  const names = new Set(Object.values(world.orgs).map((o) => o.name.toLowerCase()));
  const before = new Set(Object.keys(world.orgs));
  const beforePlayers = new Set(Object.keys(world.players));
  addRealHistory(ctx, orgs, (region) => createOrg(ctx, region, names, world.season));
  for (const o of Object.values(world.orgs)) if (!before.has(o.id) && !o.teams.length) o.active = false;
  for (const p of Object.values(world.players)) if (!beforePlayers.has(p.id)) p.value = marketValue(world, p);
  return true;
}

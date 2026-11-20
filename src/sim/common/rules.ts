/**
 * Effective gameplay rules of a title under a patch. Major patches change objectives (spawn times,
 * number of spawns, what they grant, new objectives), MOBA economy/structure/death-timer multipliers
 * and shooter economy and timers. Both simulation engines of each family read these, so a patch
 * changes how games are actually played, not only the notes.
 */
import type { MobaConfig, ObjectiveDef, TacticalConfig, TitleDef } from '../../data/titles/types';
import type { Patch } from '../../model/types';

const mobaCache = new WeakMap<Patch, MobaConfig>();
const tacCache = new WeakMap<Patch, TacticalConfig>();

export function mobaRules(t: TitleDef, patch: Patch | undefined): MobaConfig {
  const base = t.moba!;
  const r = patch?.rules;
  if (!patch || !r) return base;
  const hit = mobaCache.get(patch);
  if (hit) return hit;
  const objectives: ObjectiveDef[] = base.objectives.map((o) => {
    const c = r.objectives?.[o.id];
    if (!c) return o;
    const out: ObjectiveDef = { ...o };
    if (c.spawn !== undefined) out.spawn = c.spawn;
    if (c.respawn !== undefined) out.respawn = c.respawn;
    if (c.until === null) delete out.until;
    else if (c.until !== undefined) out.until = c.until;
    if (c.kind) out.kind = c.kind;
    if (c.value !== undefined) out.value = c.value;
    if (c.maxSpawns !== undefined) out.maxSpawns = c.maxSpawns;
    return out;
  });
  for (const a of r.added ?? []) objectives.push({ ...a });
  const m = r.moba ?? {};
  const cfg: MobaConfig = { ...base, objectives, gpm: base.gpm * (m.gpm ?? 1), respawnGrowth: base.respawnGrowth * (m.respawn ?? 1) };
  mobaCache.set(patch, cfg);
  return cfg;
}

/** MOBA multipliers the spatial engine applies to its own tuning (gold, structure health, death timers). */
export function mobaMults(patch: Patch | undefined): { gpm: number; structHp: number; respawn: number } {
  const m = patch?.rules?.moba ?? {};
  return { gpm: m.gpm ?? 1, structHp: m.structHp ?? 1, respawn: m.respawn ?? 1 };
}

export function tacticalRules(t: TitleDef, patch: Patch | undefined): TacticalConfig {
  const base = t.tactical!;
  const r = patch?.rules?.tactical;
  if (!patch || !r) return base;
  const hit = tacCache.get(patch);
  if (hit) return hit;
  const cfg: TacticalConfig = {
    ...base,
    startMoney: r.startMoney ?? base.startMoney,
    winReward: r.winReward ?? base.winReward,
    lossRewards: base.lossRewards.map((x) => Math.round((x * (r.lossMult ?? 1)) / 50) * 50),
    killReward: r.killReward ?? base.killReward,
    plantReward: r.plantReward ?? base.plantReward,
    fullBuy: r.fullBuy ?? base.fullBuy,
    defenderEdge: base.defenderEdge + (r.defenderEdge ?? 0),
  };
  tacCache.set(patch, cfg);
  return cfg;
}

/** Shooter round timers changed by patches (seconds); absent fields keep the engine's defaults. */
export function shooterTimers(patch: Patch | undefined): { roundTime?: number; bombTime?: number; plantTime?: number; defuseTime?: number; defenderEdge?: number } {
  const r = patch?.rules?.tactical ?? {};
  return { roundTime: r.roundTime, bombTime: r.bombTime, plantTime: r.plantTime, defuseTime: r.defuseTime, defenderEdge: r.defenderEdge };
}

/**
 * Match replays (MOBA battlefield and shooter maps). Kept in memory for this session and, in the app,
 * persisted on the device (IndexedDB, the most recent games of each career) so played matches stay
 * watchable after a restart — including your games simulated in the background worker. Replays are
 * never part of save files; a missing replay only means "not available", results are unaffected.
 */
import type { GameResult, MobaReplay, ShooterReplay, World } from '../../model/types';

export type AnyReplay = { kind: 'moba'; r: MobaReplay } | { kind: 'shooter'; r: ShooterReplay };

const cache = new Map<string, AnyReplay>();
const MAX = 16;
type Put = (career: string, key: string, data: AnyReplay) => void;
type Get = (career: string, key: string) => Promise<unknown>;
let put: Put = () => undefined;
let get: Get = async () => undefined;

export function setReplayBackend(p: Put, g: Get): void {
  put = p;
  get = g;
}

export const replayKey = (fixtureId: string, gameIdx: number): string => `${fixtureId}#${gameIdx}`;

function remember(k: string, r: AnyReplay) {
  cache.set(k, r);
  while (cache.size > MAX) cache.delete(cache.keys().next().value as string);
}

/** Smaller storage: numeric arrays become typed arrays (indexing works the same for the viewers). */
function packed(r: AnyReplay): AnyReplay {
  if (r.kind !== 'moba') return r;
  const m = { ...r.r } as unknown as Record<string, unknown>;
  for (const [k, v] of Object.entries(m)) if (Array.isArray(v) && v.length > 64 && typeof v[0] === 'number') m[k] = Int32Array.from(v as number[]);
  return { kind: 'moba', r: m as unknown as MobaReplay };
}

export function storeReplay(careerId: string, fixtureId: string, g: GameResult): void {
  const r: AnyReplay | null = g.replay ? { kind: 'moba', r: g.replay } : g.sreplay ? { kind: 'shooter', r: g.sreplay } : null;
  if (!r) return;
  const k = replayKey(fixtureId, g.idx);
  remember(k, r);
  put(careerId, k, packed(r));
}

/** Replay already in memory (synchronous). */
export function getReplay(fixtureId: string, gameIdx: number): MobaReplay | undefined {
  const r = cache.get(replayKey(fixtureId, gameIdx));
  return r?.kind === 'moba' ? r.r : undefined;
}

export function getAnyReplay(fixtureId: string, gameIdx: number): AnyReplay | undefined {
  return cache.get(replayKey(fixtureId, gameIdx));
}

/** Replay from memory or the device store. */
export async function loadReplay(world: World, fixtureId: string, gameIdx: number): Promise<AnyReplay | undefined> {
  const k = replayKey(fixtureId, gameIdx);
  const hit = cache.get(k);
  if (hit) return hit;
  const data = (await get(world.meta.saveId, k)) as AnyReplay | undefined;
  if (data && (data.kind === 'moba' || data.kind === 'shooter')) {
    remember(k, data);
    return data;
  }
  return undefined;
}

/** Move replays out of results (before they are recorded into the world). */
export function detachReplays(world: World, fixtureId: string, games: GameResult[]): GameResult[] {
  return games.map((g) => {
    if (!g.replay && !g.sreplay) return g;
    storeReplay(world.meta.saveId, fixtureId, g);
    const { replay: _r, sreplay: _s, ...rest } = g;
    void _r;
    void _s;
    return rest;
  });
}

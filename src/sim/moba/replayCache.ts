/**
 * Session cache of spatial replays. Replays are large (one frame per two simulated seconds), so
 * they are kept in memory for the games watched or played live in this session and never written
 * into save files. A cleared cache only means "replay not available"; results are unaffected.
 */
import type { GameResult, MobaReplay } from '../../model/types';

const cache = new Map<string, MobaReplay>();
const MAX = 12;

export const replayKey = (fixtureId: string, gameIdx: number): string => `${fixtureId}#${gameIdx}`;

export function storeReplay(fixtureId: string, g: GameResult): void {
  if (!g.replay) return;
  cache.set(replayKey(fixtureId, g.idx), g.replay);
  while (cache.size > MAX) cache.delete(cache.keys().next().value as string);
}

export function getReplay(fixtureId: string, gameIdx: number): MobaReplay | undefined {
  return cache.get(replayKey(fixtureId, gameIdx));
}

/** Move replays out of results (before they are recorded into the world). */
export function detachReplays(fixtureId: string, games: GameResult[]): GameResult[] {
  return games.map((g) => {
    if (!g.replay) return g;
    storeReplay(fixtureId, g);
    const { replay: _r, ...rest } = g;
    void _r;
    return rest;
  });
}

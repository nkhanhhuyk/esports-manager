/**
 * Compact box scores. Every game keeps its box score: full detail games as player lines, others (and
 * older detail games) in this columnar form, which is several times smaller than the line objects.
 * Commentary and draft logs are what get dropped to save space — never the box score.
 */
import type { GameResult, PlayerGameLine } from '../model/types';

export interface CompactBox {
  ids: string[];
  ti: number[];
  ro: string[];
  ch: Array<string | null>;
  /** Rating × 100. */
  r: number[];
  /** Stat keys (shared order) and values, line-major. */
  k: string[];
  s: number[];
}

const r1 = (v: number) => (Number.isInteger(v) ? v : Math.round(v * 10) / 10);

export function encodeBox(lines: PlayerGameLine[]): CompactBox {
  const keys = [...new Set(lines.flatMap((l) => Object.keys(l.s)))];
  return {
    ids: lines.map((l) => l.playerId),
    ti: lines.map((l) => l.teamIdx),
    ro: lines.map((l) => l.roleId),
    ch: lines.map((l) => l.charId ?? null),
    r: lines.map((l) => Math.round(l.rating * 100)),
    k: keys,
    s: lines.flatMap((l) => keys.map((k) => r1(l.s[k] ?? 0))),
  };
}

export function decodeBox(b: CompactBox): PlayerGameLine[] {
  return b.ids.map((id, i) => {
    const s: Record<string, number> = {};
    b.k.forEach((k, j) => (s[k] = b.s[i * b.k.length + j]));
    return { playerId: id, teamIdx: b.ti[i], roleId: b.ro[i], charId: b.ch[i] ?? undefined, s, rating: b.r[i] / 100 };
  });
}

/** The box score of a game, whichever form it is stored in. */
export function boxLines(g: GameResult): PlayerGameLine[] {
  if (g.players.length) return g.players;
  return g.bx ? decodeBox(g.bx) : [];
}

/** Drop commentary, draft log and replays; keep result, team stats and the box score (compact). */
export function slimGame(g: GameResult): GameResult {
  const { replay: _r, sreplay: _s, events: _e, ...rest } = g;
  void _r;
  void _s;
  void _e;
  const lines = boxLines(g);
  return { ...rest, events: undefined, players: [], bx: lines.length ? encodeBox(lines) : g.bx };
}

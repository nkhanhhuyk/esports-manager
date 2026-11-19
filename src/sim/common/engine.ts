/**
 * Shared engine contract and helpers used by all title-specific engines.
 *
 * Determinism rule: engines draw every outcome from `rng`. Commentary text uses only `crng`
 * and already-decided outcomes, so enabling full detail never changes results.
 */
import { clamp, logistic } from '../../core/math';
import type { RNG } from '../../core/rng';
import type { TitleDef } from '../../data/titles/types';
import type { DraftRecord, GameEvent, GameResult, Patch, PlayerGameLine, World } from '../../model/types';
import type { CompEval } from './draft';
import type { PlayerCtx, TeamCtx } from './team';

export interface GameContext {
  world: World;
  title: TitleDef;
  rng: RNG;
  crng: RNG;
  events: GameEvent[] | null;
  teams: [TeamCtx, TeamCtx];
  gameIdx: number;
  mapId?: string;
  modeId?: string;
  draft?: DraftRecord;
  comp: [CompEval, CompEval];
  patch: Patch;
  /** Team index that starts on attack / first side. */
  firstSide: number;
  pressure: number;
}

export type GameEngine = (g: GameContext) => GameResult;

export function emit(g: GameContext, t: number, team: -1 | 0 | 1, kind: string, text: string | (() => string), big = false): void {
  if (!g.events) return;
  g.events.push({ t, team, kind, text: typeof text === 'function' ? text() : text, big });
}

export function teamName(g: GameContext, idx: number): string {
  return g.teams[idx].team.name;
}

export function handle(pc: PlayerCtx): string {
  return pc.p.handle;
}

export function charName(g: GameContext, pc: PlayerCtx): string {
  if (!pc.charId) return '';
  return g.title.characters.find((c) => c.id === pc.charId)?.name ?? '';
}

export function withChar(g: GameContext, pc: PlayerCtx): string {
  const c = charName(g, pc);
  return c ? `${pc.p.handle} (${c})` : pc.p.handle;
}

/** Stat accumulator for every player in a game. */
export class StatBook {
  lines: PlayerGameLine[] = [];
  private idx = new Map<string, PlayerGameLine>();
  constructor(teams: TeamCtx[], keys: string[]) {
    teams.forEach((tc, ti) => {
      for (const pc of tc.players) {
        const s: Record<string, number> = {};
        for (const k of keys) s[k] = 0;
        const line: PlayerGameLine = { playerId: pc.p.id, teamIdx: ti, roleId: pc.roleId, charId: pc.charId, s, rating: 1 };
        this.lines.push(line);
        this.idx.set(pc.p.id, line);
      }
    });
  }
  add(pid: string, key: string, v: number): void {
    const l = this.idx.get(pid);
    if (l) l.s[key] = (l.s[key] ?? 0) + v;
  }
  get(pid: string, key: string): number {
    return this.idx.get(pid)?.s[key] ?? 0;
  }
  line(pid: string): PlayerGameLine | undefined {
    return this.idx.get(pid);
  }
  /** Round all stats to integers (except explicitly fractional keys). */
  finalise(): void {
    for (const l of this.lines) for (const k of Object.keys(l.s)) l.s[k] = Math.round(l.s[k]);
  }
}

/**
 * Convert per-player impact values into ratings centred near 1.00 (HLTV-style scale)
 * so ratings are comparable inside a title regardless of stat volume.
 */
export function assignRatings(book: StatBook, impact: (line: PlayerGameLine) => number, winner: number): void {
  const vals = book.lines.map((l) => Math.max(0.01, impact(l)));
  const avg = vals.reduce((a, b) => a + b, 0) / Math.max(1, vals.length);
  book.lines.forEach((l, i) => {
    const winBonus = winner >= 0 ? (l.teamIdx === winner ? 0.06 : -0.06) : 0;
    l.rating = Math.round(clamp(0.35 + 0.65 * (vals[i] / avg) + winBonus, 0.15, 2.8) * 100) / 100;
  });
}

/** Weighted random pick of a player context. */
export function pickWeighted(rng: RNG, players: PlayerCtx[], w: (p: PlayerCtx) => number): PlayerCtx {
  return rng.weighted(players, (p) => Math.max(0.001, w(p)));
}

export function p2(diffLogit: number): number {
  return logistic(diffLogit);
}

/** Damage-dealer weighting of a player from functional class and character class. */
export function dmgWeight(g: GameContext, pc: PlayerCtx): number {
  let w = 1;
  const fc = pc.role.fclass;
  if (fc.includes('Carry/Fragger')) w *= 1.45;
  if (fc.includes('Support/Utility')) w *= 0.6;
  if (fc.includes('Controller/Anchor')) w *= 0.95;
  const cls = (g.title.characters.find((c) => c.id === pc.charId)?.cls ?? '').toLowerCase();
  if (/marksman|mage|sniper|hunter|sharpshooter|damage|duelist|attacker|carry|assassin|nuker/.test(cls)) w *= 1.2;
  if (/tank|guardian|vanguard|front|defender/.test(cls)) w *= 0.7;
  return w;
}

export function tankWeight(g: GameContext, pc: PlayerCtx): number {
  let w = 1;
  if (pc.role.fclass.includes('Controller/Anchor') || pc.role.fclass.includes('Entry/Initiator')) w *= 1.35;
  if (pc.role.fclass.includes('Carry/Fragger')) w *= 0.85;
  const cls = (g.title.characters.find((c) => c.id === pc.charId)?.cls ?? '').toLowerCase();
  if (/tank|guardian|vanguard|front|defender|warrior|fighter|bruiser|captain/.test(cls)) w *= 1.6;
  return w;
}

export function healWeight(g: GameContext, pc: PlayerCtx): number {
  const cls = (g.title.characters.find((c) => c.id === pc.charId)?.cls ?? '').toLowerCase();
  if (/support|enchanter|healer|supporter|strategist|captain/.test(cls)) return 1;
  if (pc.role.fclass.includes('Support/Utility')) return 0.35;
  return 0.03;
}

export function fmtTime(sec: number): string {
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${m}:${String(s).padStart(2, '0')}`;
}

/**
 * Drafting (bans & picks), composition evaluation and draft legality.
 *
 * Sequential drafts follow the title's step order; simultaneous drafts (agent/hero select)
 * apply bans then let each team pick independently (mirrors allowed where the title allows).
 * The same functions power the interactive draft UI, AI drafting and coach delegation.
 */
import { fitsRole } from '../../data/titles/realCharacters';
import { clamp } from '../../core/math';
import type { RNG } from '../../core/rng';
import type { CharDef, TitleDef } from '../../data/titles/types';
import type { DraftRecord, LineupSlot, Patch, Player, Team, World } from '../../model/types';
import { charProf } from './ratings';
import { headCoach } from './team';
import { staffQuality } from '../../systems/contracts';

export interface CompEval {
  total: number;
  meta: number;
  style: number;
  coverage: number;
  counter: number;
  comfort: number;
  profile: Record<string, number>; // 0..10 style averages
}

export const NEUTRAL_COMP: CompEval = { total: 0, meta: 0, style: 0, coverage: 0, counter: 0, comfort: 0.5, profile: {} };

// Generic class counter matrix (keyword-based so it works for every title's class names).
const COUNTERS: Array<[RegExp, RegExp, number]> = [
  [/assassin|flank|speedster|skirmisher/, /marksman|mage|sniper|sharpshooter|hunter|carry|artillery|support|healer|strategist|enchanter/, 0.5],
  [/tank|guardian|vanguard|front|defender|brawler/, /assassin|flank|speedster|fighter|warrior/, 0.4],
  [/marksman|hunter|sharpshooter|carry|damage|duelist/, /tank|guardian|vanguard|front|defender/, 0.45],
  [/mage|nuker|caster|controller|artillery/, /fighter|warrior|bruiser|all-rounder|tank/, 0.35],
  [/fighter|warrior|bruiser|all-rounder/, /marksman|mage|support|enchanter/, 0.3],
  [/initiator|intel|recon/, /sentinel|anchor|trapper|controller/, 0.3],
  [/sentinel|anchor|trapper/, /duelist|entry/, 0.35],
  [/duelist|entry/, /initiator|controller|intel/, 0.25],
];

const counterCache = new Map<string, Map<string, number>>();

/** Class-vs-class counter advantage (cached per class pair). */
export function counterScore(a: CharDef, b: CharDef): number {
  let inner = counterCache.get(a.cls);
  if (!inner) {
    inner = new Map();
    counterCache.set(a.cls, inner);
  }
  const cached = inner.get(b.cls);
  if (cached !== undefined) return cached;
  const ca = a.cls.toLowerCase();
  const cb = b.cls.toLowerCase();
  let s = 0;
  for (const [x, y, v] of COUNTERS) {
    if (x.test(ca) && y.test(cb)) s += v;
    if (x.test(cb) && y.test(ca)) s -= v;
  }
  inner.set(b.cls, s);
  return s;
}

const charMapCache = new WeakMap<TitleDef, Map<string, CharDef>>();
export function charMap(t: TitleDef): Map<string, CharDef> {
  let m = charMapCache.get(t);
  if (!m) {
    m = new Map(t.characters.map((c) => [c.id, c]));
    charMapCache.set(t, m);
  }
  return m;
}

/** Comfort lookup for a player: direct proficiency, else partial transfer from same-class experience. */
function comfortFn(t: TitleDef, p: Player | undefined): (c: CharDef) => number {
  if (!p) return () => 30;
  const prof = p.prof.chars[t.id] ?? {};
  const cm = charMap(t);
  const byClass: Record<string, number> = {};
  for (const [cid, v] of Object.entries(prof)) {
    const c = cm.get(cid);
    if (c && v > (byClass[c.cls] ?? 0)) byClass[c.cls] = v;
  }
  return (c) => prof[c.id] ?? (byClass[c.cls] ?? 0) * 0.45;
}

const FRONT = /tank|guardian|vanguard|front|defender|fighter|warrior|bruiser|captain/;
const DAMAGE = /marksman|mage|assassin|sniper|hunter|sharpshooter|damage|duelist|attacker|carry|nuker|caster|energy/;
const SUPPORT = /support|enchanter|healer|supporter|strategist|captain/;

/** Evaluate a composition. `chars` are the team's picks; `enemy` the opponent's picks. */
export function evaluateComp(t: TitleDef, patch: Patch, chars: CharDef[], enemy: CharDef[], players: Player[], teamStyle: string): CompEval {
  if (!chars.length) {
    const profile: Record<string, number> = {};
    for (const s of t.styles) profile[s] = s === teamStyle ? 7 : 5;
    const styleScore = ((patch.styles[teamStyle] ?? 1) - 1) * 0.8;
    return { ...NEUTRAL_COMP, style: styleScore, total: styleScore * 0.4, profile };
  }
  const meta = chars.reduce((a, c) => a + (patch.charMeta[c.id] ?? 0), 0) / chars.length / 10;
  const profile: Record<string, number> = {};
  for (const s of t.styles) profile[s] = chars.reduce((a, c) => a + (c.styles[s] ?? 5), 0) / chars.length;
  let wsum = 0;
  let ssum = 0;
  for (const s of t.styles) {
    const w = patch.styles[s] ?? 1;
    wsum += w;
    ssum += profile[s] * w;
  }
  const style = clamp((ssum / Math.max(0.01, wsum) - 5) / 3, -1, 1) * 0.6 + clamp(((profile[teamStyle] ?? 5) - 5) / 5, -1, 1) * 0.4;
  let coverage = 0;
  {
    const hasFront = chars.some((c) => FRONT.test(c.cls.toLowerCase()));
    const dmg = chars.filter((c) => DAMAGE.test(c.cls.toLowerCase())).length;
    const sup = chars.some((c) => SUPPORT.test(c.cls.toLowerCase()));
    coverage = (hasFront ? 0.25 : -0.35) + (dmg >= Math.min(2, chars.length - 1) ? 0.2 : -0.3) + (sup || t.teamSize <= 3 ? 0.1 : -0.15);
  }
  let counter = 0;
  if (enemy.length) {
    for (const c of chars) for (const e of enemy) counter += counterScore(c, e);
    counter = clamp(counter / (chars.length * enemy.length) * 3, -1, 1);
  }
  const comfort = players.length
    ? players.reduce((a, p, i) => {
        const c = chars[i];
        if (!c) return a;
        return a + (p.prof.chars[t.id]?.[c.id] ?? 25) / 100;
      }, 0) / players.length
    : 0.5;
  const total = 0.35 * meta + 0.22 * style + 0.18 * coverage + 0.13 * counter + 0.12 * (comfort - 0.6) * 2;
  return { total, meta, style, coverage, counter, comfort, profile };
}

// ---------------------------------------------------------------------------------------------
// Draft state machine
// ---------------------------------------------------------------------------------------------

export interface DraftSide {
  team: Team;
  lineup: LineupSlot[];
  players: Player[];
}

export interface DraftState {
  title: TitleDef;
  patch: Patch;
  sides: [DraftSide, DraftSide];
  step: number;
  bans: [string[], string[]];
  picks: [string[], string[]];
  assign: [Record<string, string>, Record<string, string>];
  order: DraftRecord['order'];
  unavailable: [Set<string>, Set<string>]; // fearless restrictions per team
  /** Series team index holding first pick (the side chosen for this game). */
  firstPick: 0 | 1;
  done: boolean;
  /** Runtime cache of per-player comfort lookups (not persisted). */
  comfortCache?: Map<string, (c: CharDef) => number>;
}

export function newDraft(world: World, t: TitleDef, patch: Patch, sides: [DraftSide, DraftSide], fearless: [Set<string>, Set<string>], firstPick: 0 | 1 = 0): DraftState {
  return {
    title: t,
    patch,
    sides,
    step: 0,
    bans: [[], []],
    picks: [[], []],
    assign: [{}, {}],
    order: [],
    unavailable: fearless,
    firstPick,
    done: t.draft.mode === 'none' || t.characters.length === 0,
  };
}

export function currentStep(ds: DraftState): { team: 0 | 1; action: 'ban' | 'pick' } | null {
  if (ds.done) return null;
  const t = ds.title;
  // Step tables are written from the first-pick side's view (team 0 = first pick).
  if (t.draft.mode === 'sequential') {
    const s = t.draft.steps[ds.step];
    if (!s) return null;
    return ds.firstPick ? { team: (1 - s.team) as 0 | 1, action: s.action } : s;
  }
  // Simultaneous: bans (alternating, first-pick side first), then picks for team 0 then team 1.
  const nb = t.draft.bansPerTeam * 2;
  if (ds.step < nb) return { team: ((ds.step + ds.firstPick) % 2) as 0 | 1, action: 'ban' };
  const k = ds.step - nb;
  const n = t.teamSize;
  if (k < n * 2) return { team: (k < n ? 0 : 1) as 0 | 1, action: 'pick' };
  return null;
}

/** Characters a team may legally choose at the current step. */
export function legalOptions(ds: DraftState, team: 0 | 1, action: 'ban' | 'pick', playerId?: string): CharDef[] {
  const t = ds.title;
  const allBans = new Set([...ds.bans[0], ...ds.bans[1]]);
  const all = new Set([...ds.picks[0], ...ds.picks[1]]);
  const own = new Set(ds.picks[team]);
  return t.characters.filter((c) => {
    if (allBans.has(c.id)) return false;
    if (action === 'ban') return !all.has(c.id);
    if (own.has(c.id)) return false;
    if (t.draft.uniqueAcross && all.has(c.id)) return false;
    if (ds.unavailable[team].has(c.id)) return false;
    void playerId;
    return true;
  });
}

function unfilledPlayers(ds: DraftState, team: 0 | 1): LineupSlot[] {
  return ds.sides[team].lineup.filter((s) => !ds.assign[team][s.playerId]);
}

/** Apply an action. For picks, `playerId` chooses who plays it (defaults to best fit). */
export function applyDraftAction(ds: DraftState, charId: string, playerId?: string, trusted = false): { ok: boolean; error?: string } {
  const step = currentStep(ds);
  if (!step) return { ok: false, error: 'Draft complete.' };
  // AI choices come from legalOptions already; user / imported choices are validated.
  if (!trusted && !legalOptions(ds, step.team, step.action).some((c) => c.id === charId)) return { ok: false, error: 'Illegal choice (banned, taken or restricted).' };
  if (step.action === 'ban') {
    ds.bans[step.team].push(charId);
  } else {
    const slots = unfilledPlayers(ds, step.team);
    if (!slots.length) return { ok: false, error: 'No player left to assign.' };
    let slot = playerId ? slots.find((s) => s.playerId === playerId) : undefined;
    if (!slot) slot = bestSlotFor(ds, step.team, charId, slots);
    ds.picks[step.team].push(charId);
    ds.assign[step.team][slot.playerId] = charId;
  }
  ds.order.push({ team: step.team, action: step.action, charId });
  ds.step++;
  if (!currentStep(ds)) ds.done = true;
  return { ok: true };
}

function roleOf(ds: DraftState, team: 0 | 1, slot: LineupSlot) {
  return ds.title.roles.find((r) => r.id === slot.roleId) ?? ds.title.roles[0];
}

function bestSlotFor(ds: DraftState, team: 0 | 1, charId: string, slots: LineupSlot[]): LineupSlot {
  const c = charMap(ds.title).get(charId)!;
  let best = slots[0];
  let bs = -Infinity;
  for (const s of slots) {
    const p = ds.sides[team].players.find((x) => x.id === s.playerId);
    const role = roleOf(ds, team, s);
    const fit = fitsRole(c, role) ? 30 : 0;
    const comfort = p ? charProf(p, ds.title, role, charId) : 0;
    if (fit + comfort > bs) {
      bs = fit + comfort;
      best = s;
    }
  }
  return best;
}

function draftSkill(world: World, team: Team): number {
  const coach = headCoach(world, team);
  return coach ? (coach.attrs.drafting * 0.6 + staffQuality(coach) * 0.4) / 100 : 0.35;
}

const fitApplies = new Map<string, boolean>();
function roleFitApplies(t: TitleDef): boolean {
  let v = fitApplies.get(t.id);
  if (v === undefined) {
    v = t.roles.some((r) => r.classes.length > 0) || t.characters.some((c) => c.lanes?.length);
    fitApplies.set(t.id, v);
  }
  return v;
}

/** AI choice for the current step. */
export function aiDraftAction(world: World, ds: DraftState, rng: RNG): { charId: string; playerId?: string } | null {
  const step = currentStep(ds);
  if (!step) return null;
  const team = step.team;
  const side = ds.sides[team];
  const enemy = (1 - team) as 0 | 1;
  const skill = draftSkill(world, side.team);
  const noise = (1 - skill) * 0.35 + 0.05;
  const prio = side.team.prefs.draftPriority;
  const wMeta = prio === 'meta' ? 1.4 : prio === 'comfort' ? 0.7 : 1;
  const wComfort = prio === 'comfort' ? 1.5 : prio === 'meta' ? 0.7 : 1;
  const wCounter = prio === 'counter' ? 1.6 : 1;
  const opts = legalOptions(ds, team, step.action);
  if (!opts.length) return null;
  const cmap = charMap(ds.title);
  const enemyChars = ds.picks[enemy].map((id) => cmap.get(id)!).filter(Boolean);
  if (step.action === 'ban') {
    // Ban the opponent's best comfort + meta options, plus team preference bans.
    let best: CharDef | null = null;
    let bs = -Infinity;
    const enemyPlayers = ds.sides[enemy].players;
    // Enemy comfort once (players' pools are small) instead of per option × player.
    const enemyComfort = new Map<string, number>();
    for (const p of enemyPlayers) for (const [id, v] of Object.entries(p.prof.chars[ds.title.id] ?? {})) if (v > (enemyComfort.get(id) ?? 0)) enemyComfort.set(id, v);
    for (const c of opts) {
      const comfort = enemyComfort.get(c.id) ?? 0;
      const pref = side.team.prefs.bans.includes(c.id) ? 0.6 : 0;
      const score = (ds.patch.charMeta[c.id] ?? 0) / 10 * wMeta + (comfort / 100) * 0.9 * wComfort + pref + (rng.next() - 0.5) * noise * 3.4;
      if (score > bs) {
        bs = score;
        best = c;
      }
    }
    return best ? { charId: best.id } : null;
  }
  const slots = unfilledPlayers(ds, team);
  let best: { c: CharDef; slot: LineupSlot } | null = null;
  let bs = -Infinity;
  // Shortlist: characters that fit a still-open role (real rosters have 100–170 characters).
  const openRoles = slots.map((s) => roleOf(ds, team, s));
  const anyFit = roleFitApplies(ds.title);
  const fitting = anyFit ? opts.filter((c) => openRoles.some((r) => fitsRole(c, r))) : opts;
  if (fitting.length >= 3) opts.splice(0, opts.length, ...fitting);
  // Option-level terms (independent of which player takes the pick) computed once per option.
  const style = side.team.prefs.style;
  const base = new Float64Array(opts.length);
  for (let i = 0; i < opts.length; i++) {
    const c = opts[i];
    let ctr = 0;
    for (const e of enemyChars) ctr += counterScore(c, e);
    base[i] = ((ds.patch.charMeta[c.id] ?? 0) / 10) * 0.8 * wMeta + ctr * 0.25 * wCounter + ((c.styles[style] ?? 5) - 5) / 10 + (rng.next() - 0.5) * noise * 3.4;
  }
  ds.comfortCache ||= new Map();
  for (const slot of slots) {
    const p = side.players.find((x) => x.id === slot.playerId);
    const role = roleOf(ds, team, slot);
    const pool = side.team.prefs.pool[role.id];
    const prefSet = pool && pool.length ? new Set(pool) : null;
    let comfortOf = p ? ds.comfortCache.get(p.id) : undefined;
    if (!comfortOf) {
      comfortOf = comfortFn(ds.title, p);
      if (p) ds.comfortCache.set(p.id, comfortOf);
    }
    for (let i = 0; i < opts.length; i++) {
      const c = opts[i];
      const fit = !(anyFit || role.classes.length > 0) || fitsRole(c, role) ? 0.5 : -0.4;
      const score = base[i] + fit + (comfortOf(c) / 100) * 1.1 * wComfort + (prefSet?.has(c.id) ? 0.35 : 0);
      if (score > bs) {
        bs = score;
        best = { c, slot };
      }
    }
  }
  return best ? { charId: best.c.id, playerId: best.slot.playerId } : null;
}

/** Run a whole draft with AI for both sides (or user-provided full draft). */
export function runAiDraft(world: World, ds: DraftState, rng: RNG): void {
  let guard = 0;
  while (!ds.done && guard++ < 200) {
    const act = aiDraftAction(world, ds, rng);
    if (!act) {
      ds.done = true;
      break;
    }
    const r = applyDraftAction(ds, act.charId, act.playerId, true);
    if (!r.ok) {
      ds.done = true;
      break;
    }
  }
}

export function toRecord(ds: DraftState, comp: [number, number]): DraftRecord {
  return { bans: [[...ds.bans[0]], [...ds.bans[1]]], picks: [[...ds.picks[0]], [...ds.picks[1]]], order: [...ds.order], compScore: comp, assign: [{ ...ds.assign[0] }, { ...ds.assign[1] }], firstPick: ds.firstPick };
}

/** Validate a finished draft record against title rules (used by tests and plan import). */
export function validateDraft(t: TitleDef, rec: DraftRecord, fearless: [Set<string>, Set<string>] = [new Set(), new Set()]): string[] {
  const errs: string[] = [];
  const ids = new Set(t.characters.map((c) => c.id));
  const bans = new Set([...rec.bans[0], ...rec.bans[1]]);
  for (const team of [0, 1] as const) {
    if (t.characters.length && t.draft.mode !== 'none' && rec.picks[team].length !== t.teamSize) errs.push(`Team ${team} has ${rec.picks[team].length} picks, expected ${t.teamSize}.`);
    const own = new Set<string>();
    for (const c of rec.picks[team]) {
      if (!ids.has(c)) errs.push(`Unknown character ${c}.`);
      if (bans.has(c)) errs.push(`Banned character ${c} was picked.`);
      if (own.has(c)) errs.push(`Duplicate pick ${c} within team.`);
      if (fearless[team].has(c)) errs.push(`Fearless-restricted pick ${c}.`);
      own.add(c);
    }
  }
  if (t.draft.uniqueAcross) {
    const both = rec.picks[0].filter((c) => rec.picks[1].includes(c));
    if (both.length) errs.push(`Mirror picks not allowed: ${both.join(', ')}.`);
  }
  if (t.draft.mode === 'sequential') {
    const expected = t.draft.steps;
    if (rec.order.length && rec.order.length !== expected.length) errs.push('Draft order length mismatch.');
    const fp = rec.firstPick ?? 0;
    rec.order.forEach((o, i) => {
      const e = expected[i];
      if (e && ((fp ? 1 - e.team : e.team) !== o.team || e.action !== o.action)) errs.push(`Step ${i + 1} out of order.`);
    });
  }
  return errs;
}

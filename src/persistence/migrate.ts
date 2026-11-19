/**
 * Versioned save schema: runtime validation + forward migrations.
 * Each migration upgrades a save from version N to N+1. Never edit a released migration;
 * add a new one and bump SAVE_VERSION.
 */
import { z } from 'zod';
import type { World } from '../model/types';
import { SAVE_VERSION } from './version';

type AnyWorld = Record<string, any>;

export const MIGRATIONS: Record<number, (w: AnyWorld) => AnyWorld> = {
  // v1 -> v2: editor format overrides, scouting focus list, per-competition player aggregates,
  // training focus tracking.
  1: (w) => {
    w.customFormats ??= {};
    w.scoutFocus ??= [];
    for (const c of Object.values<AnyWorld>(w.comps ?? {})) c.pstats ??= {};
    for (const t of Object.values<AnyWorld>(w.teams ?? {})) {
      t.training ??= { focus: 'balanced', intensity: 60, scrimsPerWeek: 3, individual: {}, opponentPrep: true };
      t.training.focusWeeks ??= 0;
    }
    return w;
  },
  // v2 -> v3: tradable draft picks. Self-contained (no game imports): seed this and next
  // season's picks for every top-tier league that runs a rookie draft.
  2: (w) => {
    if (!Array.isArray(w.draftPicks)) {
      w.draftPicks = [];
      for (const lg of Object.values<AnyWorld>(w.leagues ?? {})) {
        if (!lg.policy?.draft || lg.tier !== 1) continue;
        for (const season of [w.season, w.season + 1])
          for (const teamId of lg.teamIds ?? [])
            for (const round of [1, 2]) {
              w.counter += 1;
              w.draftPicks.push({ id: `dp${w.counter.toString(36)}`, leagueId: lg.id, season, round, originalTeamId: teamId, ownerTeamId: teamId, used: false });
            }
      }
    }
    return w;
  },
};

const metaSchema = z
  .object({
    version: z.number().int().nonnegative(),
    saveId: z.string().min(1),
    name: z.string(),
    seed: z.string(),
    startDay: z.number(),
    userOrgId: z.string().nullable(),
    titlesEnabled: z.array(z.string()).min(1),
  })
  .passthrough();

const worldSchema = z
  .object({
    meta: metaSchema,
    day: z.number().int(),
    season: z.number().int(),
    players: z.record(z.any()),
    teams: z.record(z.any()),
    orgs: z.record(z.any()),
    leagues: z.record(z.any()),
    comps: z.record(z.any()),
    fixtures: z.record(z.any()),
    schedule: z.record(z.any()),
    titles: z.record(z.any()),
    processed: z.object({ lastDay: z.number(), lastMonth: z.number(), lastWeek: z.number(), seasonRolled: z.array(z.number()) }).passthrough(),
    counter: z.number().int().nonnegative(),
  })
  .passthrough();

const playerSchema = z
  .object({
    id: z.string(),
    handle: z.string(),
    attrs: z.array(z.number().min(0).max(100)).length(86),
    input: z.array(z.number().min(0).max(100)).length(3),
    titleId: z.string(),
  })
  .passthrough();

export class SaveError extends Error {}

/** Validate structure (full top level + sampled entities for speed). */
export function validateWorld(raw: unknown): World {
  const r = worldSchema.safeParse(raw);
  if (!r.success) throw new SaveError(`Save failed validation: ${r.error.issues.slice(0, 3).map((i) => `${i.path.join('.')}: ${i.message}`).join('; ')}`);
  // Return the original object (zod re-creates objects and reorders keys; we only validate).
  const w = raw as World;
  const players = Object.values(w.players);
  const step = Math.max(1, Math.floor(players.length / 200));
  for (let i = 0; i < players.length; i += step) {
    const pr = playerSchema.safeParse(players[i]);
    if (!pr.success) throw new SaveError(`Corrupt player record: ${pr.error.issues[0]?.path.join('.')} ${pr.error.issues[0]?.message}`);
  }
  for (const t of Object.values(w.teams)) for (const pid of t.roster) if (!w.players[pid]) throw new SaveError(`Team ${t.name} references missing player ${pid}`);
  return w;
}

export function migrate(raw: AnyWorld): World {
  if (!raw || typeof raw !== 'object' || !raw.meta) throw new SaveError('Not a save file.');
  let v: number = raw.meta.version ?? 1;
  if (v > SAVE_VERSION) throw new SaveError(`Save version ${v} is newer than this game (${SAVE_VERSION}). Please update.`);
  while (v < SAVE_VERSION) {
    const m = MIGRATIONS[v];
    if (!m) throw new SaveError(`No migration from version ${v}.`);
    raw = m(raw);
    v++;
    raw.meta.version = v;
  }
  return validateWorld(raw);
}

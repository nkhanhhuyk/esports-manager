/**
 * Real playable characters (heroes / champions / agents) from the bundled offline snapshot
 * (src/data/real/characters.json, built by scripts/data/characters.mjs — sources in
 * DATA_SOURCES.md). Names, classes and lanes are real; play-style numbers are game tuning
 * derived from the real classes and published kit ratings, not official measurements.
 */
import data from '../real/characters.json';
import { RNG } from '../../core/rng';
import type { CharDef, RoleDef, TitleDef } from './types';
import { CLASS_STYLE } from './generate';

interface RawChar {
  character_id: string;
  name: string;
  classes: string[];
  lanes: string[];
  kit?: { difficulty?: number; durability?: number; offense?: number; control?: number };
}

const SNAP = data as unknown as { snapshot_id: string; as_of: string; titles: Record<string, { patch_id: string; characters: RawChar[] }> };

export const CHARACTER_SNAPSHOT = { id: SNAP.snapshot_id, asOf: SNAP.as_of };

export function characterPatch(titleId: string): string | null {
  return SNAP.titles[titleId]?.patch_id ?? null;
}

/** Difficulty to 1..10 from each source's scale. */
function difficulty(titleId: string, k: RawChar['kit']): number {
  const d = k?.difficulty;
  if (d === undefined || Number.isNaN(d)) return 5;
  const max = titleId === 'lol' ? 10 : titleId === 'wildrift' ? 9 : titleId === 'mlbb' ? 3 : 10;
  return Math.max(1, Math.min(10, Math.round((d / max) * 10)));
}

export function realCharacters(t: TitleDef): CharDef[] | null {
  const src = SNAP.titles[t.id];
  if (!src) return null;
  return src.characters.map((c) => {
    const classes = c.classes.filter((x) => t.charClasses.includes(x));
    const cls = classes[0] ?? t.charClasses[0];
    const rng = new RNG(`real|${c.character_id}`);
    const aff = CLASS_STYLE.filter(([k]) => classes.some((x) => x.toLowerCase().includes(k))).map(([, v]) => v);
    const styles: Record<string, number> = {};
    for (const s of t.styles) {
      let base = 3 + rng.float(0, 2.5);
      for (const a of aff) if (a[s] !== undefined) base = Math.max(base, a[s] + rng.float(-1, 1));
      // Published kit ratings nudge the matching styles.
      if (c.kit?.control !== undefined && /control|teamfight|pick/.test(s)) base += (c.kit.control - 5) * 0.25;
      if (c.kit?.durability !== undefined && /brawl|teamfight|front/.test(s)) base += (c.kit.durability - 5) * 0.2;
      if (c.kit?.offense !== undefined && /early|pick|dive|poke/.test(s)) base += (c.kit.offense - 5) * 0.2;
      styles[s] = Math.round(Math.max(0, Math.min(10, base)) * 10) / 10;
    }
    return { id: c.character_id, name: c.name, cls, classes, lanes: c.lanes.length ? c.lanes : undefined, styles, difficulty: difficulty(t.id, c.kit), real: true };
  });
}

/** Can this character be played in this role (real lanes when known, else class fit)? */
export function fitsRole(c: CharDef, role: RoleDef): boolean {
  if (c.lanes?.length) return c.lanes.includes(role.id);
  if (c.classes?.length) return c.classes.some((x) => role.classes.includes(x));
  return role.classes.includes(c.cls);
}

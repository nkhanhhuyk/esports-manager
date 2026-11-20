/**
 * Detailed patch notes. Every buff or nerf the simulation applies (a change of a character's meta
 * strength) is written out as concrete line items — ability damage, cooldowns, base stats, costs —
 * sized by how big the change is, so the notes read like real patch notes and always point the same
 * way as the effect in the games. The numbers are game-patch values (patches after the data snapshot
 * are game simulation), generated deterministically with the patch.
 */
import CHARS from '../data/real/characters.json';
import type { RNG } from '../core/rng';
import type { CharDef, TitleDef } from '../data/titles/types';
import type { PatchChange } from '../model/types';

const VAL_ABILITIES = new Map<string, Array<{ slot: string; name: string }>>();
for (const c of (CHARS as unknown as { titles: Record<string, { characters: Array<{ character_id: string; abilities?: Array<{ slot: string; name: string }> }> }> }).titles.valorant?.characters ?? []) VAL_ABILITIES.set(c.character_id, c.abilities ?? []);

const pick = <T,>(rng: RNG, xs: readonly T[]): T => xs[rng.int(0, xs.length - 1)];
const round = (v: number, step: number) => Math.round(v / step) * step;
const fmt = (v: number) => (Number.isInteger(v) ? String(v) : v.toFixed(1).replace(/\.0$/, ''));

/** A value line: `label old → new unit` where new moves by `pct` in the buff/nerf direction. */
function valueLine(rng: RNG, label: string, base: number, step: number, pct: number, better: 'up' | 'down', buff: boolean, unit = ''): string {
  const up = (better === 'up') === buff;
  const old = round(base, step);
  let next = round(old * (1 + (up ? 1 : -1) * pct), step);
  if (next === old) next = old + (up ? step : -step);
  return `${label} ${fmt(old)}${unit} → ${fmt(next)}${unit}`;
}

/** Ranked values (per ability rank): `40/65/90/115/140 → 45/70/95/120/145`. */
function rankLine(rng: RNG, label: string, start: number, per: number, ranks: number, step: number, pct: number, better: 'up' | 'down', buff: boolean, unit = ''): string {
  const up = (better === 'up') === buff;
  const olds = Array.from({ length: ranks }, (_, i) => round(start + per * i, step));
  const news = olds.map((o) => {
    let n = round(o * (1 + (up ? 1 : -1) * pct), step);
    if (n === o) n = o + (up ? step : -step);
    return n;
  });
  return `${label} ${olds.map(fmt).join('/')}${unit} → ${news.map(fmt).join('/')}${unit}`;
}

function mobaSlots(t: TitleDef): string[] {
  if (t.id === 'mlbb' || t.id === 'hok') return ['Passive', 'Skill 1', 'Skill 2', 'Ultimate'];
  if (t.id === 'dota2') return ['Q', 'W', 'E', 'R'];
  return ['Passive', 'Q', 'W', 'E', 'R'];
}

/** Concrete lines for one character change (2–4 lines, more for bigger changes). */
export function characterLines(t: TitleDef, c: CharDef, delta: number, rng: RNG): string[] {
  const buff = delta > 0;
  const mag = Math.min(1, Math.abs(delta) / 6);
  const pct = 0.04 + mag * 0.16;
  const n = 1 + (Math.abs(delta) >= 2 ? 1 : 0) + (Math.abs(delta) >= 4 ? 1 : 0) + (rng.chance(0.3) ? 1 : 0);
  const out: string[] = [];
  const used = new Set<string>();
  const cls = (c.cls ?? '').toLowerCase();
  for (let i = 0; i < n * 3 && out.length < n; i++) {
    let line = '';
    if (t.id === 'valorant') {
      const abs = VAL_ABILITIES.get(c.id) ?? [];
      const ab = abs.length ? pick(rng, abs) : { slot: 'Ability1', name: 'Ability' };
      const key = { Ability1: 'Q', Ability2: 'E', Grenade: 'C', Ultimate: 'X', Passive: 'Passive' }[ab.slot] ?? ab.slot;
      const kind = pick(rng, ab.slot === 'Ultimate' ? ['points', 'duration', 'windup'] : ab.slot === 'Passive' ? ['passive'] : ['cost', 'duration', 'cooldown', 'charges', 'windup']);
      if (used.has(`${ab.name}${kind}`)) continue;
      used.add(`${ab.name}${kind}`);
      line =
        kind === 'points'
          ? `${ab.name} (X): ultimate points ${buff ? '8 → 7' : '7 → 8'}`
          : kind === 'cost'
            ? valueLine(rng, `${ab.name} (${key}): cost`, pick(rng, [150, 200, 250, 300, 400]), 50, pct, 'down', buff, ' credits')
            : kind === 'duration'
              ? valueLine(rng, `${ab.name} (${key}): duration`, pick(rng, [4, 6, 8, 10, 12, 15]), 0.5, pct, 'up', buff, 's')
              : kind === 'cooldown'
                ? valueLine(rng, `${ab.name} (${key}): cooldown`, pick(rng, [30, 35, 40, 45, 60]), 1, pct, 'down', buff, 's')
                : kind === 'charges'
                  ? `${ab.name} (${key}): charges ${buff ? '1 → 2' : '2 → 1'}`
                  : kind === 'windup'
                    ? valueLine(rng, `${ab.name} (${key}): equip / windup time`, pick(rng, [0.6, 0.8, 1, 1.2]), 0.05, pct, 'down', buff, 's')
                    : `${ab.name} (passive): effect radius ${buff ? 'increased' : 'reduced'} by ${Math.round(pct * 100)}%`;
    } else {
      const slots = mobaSlots(t);
      const slot = pick(rng, slots);
      const kind = slot === 'Passive' ? pick(rng, ['passive', 'stat']) : pick(rng, ['damage', 'damage', 'cooldown', 'ratio', 'cc', 'stat']);
      if (used.has(`${slot}${kind}`)) continue;
      used.add(`${slot}${kind}`);
      const tank = /tank|guardian|vanguard|support/.test(cls);
      const ranks = t.id === 'dota2' ? 4 : slot === 'R' || slot === 'Ultimate' ? 3 : 5;
      switch (kind) {
        case 'damage':
          line = rankLine(rng, `${slot} damage`, pick(rng, tank ? [40, 60, 70] : [60, 75, 90, 110]), pick(rng, [20, 25, 30, 40]), ranks, 5, pct, 'up', buff);
          break;
        case 'cooldown':
          line = rankLine(rng, `${slot} cooldown`, pick(rng, slot === 'R' || slot === 'Ultimate' ? [120, 100, 80] : [14, 12, 10, 9]), slot === 'R' || slot === 'Ultimate' ? -20 : -1, ranks, slot === 'R' || slot === 'Ultimate' ? 5 : 0.5, pct, 'down', buff, 's');
          break;
        case 'ratio':
          line = valueLine(rng, `${slot} ${pick(rng, ['bonus AD', 'AP', 'magic', 'physical'])} ratio`, pick(rng, [40, 50, 60, 70, 80]), 5, pct, 'up', buff, '%');
          break;
        case 'cc':
          line = valueLine(rng, `${slot} ${pick(rng, ['stun', 'slow', 'root', 'knock-up'])} duration`, pick(rng, [0.75, 1, 1.25, 1.5]), 0.25, pct, 'up', buff, 's');
          break;
        case 'passive':
          line = valueLine(rng, `Passive ${pick(rng, ['bonus damage', 'shield', 'heal', 'movement speed'])}`, pick(rng, [10, 15, 20, 25, 30]), 1, pct, 'up', buff, '%');
          break;
        default: {
          const stat = pick(rng, t.id === 'dota2' ? ['Base armor', 'Strength gain', 'Base damage', 'Movement speed'] : t.id === 'mlbb' || t.id === 'hok' ? ['Base HP', 'Physical attack', 'Magic defense', 'HP growth'] : ['Base health', 'Base attack damage', 'Base armor', 'Health growth']);
          const base = { 'Base armor': 30, 'Strength gain': 2.6, 'Base damage': 50, 'Movement speed': 300, 'Base HP': 2600, 'Physical attack': 120, 'Magic defense': 15, 'HP growth': 190, 'Base health': 640, 'Base attack damage': 60, 'Health growth': 105 }[stat] ?? 50;
          line = valueLine(rng, stat, base * (0.9 + rng.next() * 0.2), base < 5 ? 0.1 : base < 100 ? 1 : 5, pct * 0.5, 'up', buff);
        }
      }
    }
    out.push(line);
  }
  return out;
}

/** Lines for a class-wide change: items (MOBAs) or utility (agents). */
export function classLines(t: TitleDef, cls: string, buff: boolean, rng: RNG): string[] {
  const pct = 0.05 + rng.next() * 0.08;
  if (!t.moba) return [`${cls} utility costs ${buff ? 'reduced' : 'increased'} by ${Math.round(pct * 100)}%`, `${cls} ability durations ${buff ? '+' : '−'}${(0.5 + rng.next()).toFixed(1)}s`];
  const kind = /marksman|carry|hunter|archer/i.test(cls) ? 'critical-strike and attack-speed items' : /mage|caster|nuker/i.test(cls) ? 'ability-power items' : /tank|guardian|vanguard/i.test(cls) ? 'health and resistance items' : /assassin/i.test(cls) ? 'lethality and burst items' : /support|enchanter/i.test(cls) ? 'support items' : 'bruiser items';
  return [
    `${cls} ${kind}: ${buff ? 'stronger' : 'weaker'} (${buff ? '+' : '−'}${Math.round(pct * 100)}% stats)`,
    valueLine(rng, `Core ${kind.split(' ')[0]} item cost`, pick(rng, [2800, 3000, 3100, 3200, 3400]), 50, pct * 0.6, 'down', buff, ' gold'),
  ];
}

/**
 * CS2 (no characters): weapon balance changes. Returns the notes and the average price change
 * (signed fraction), which the patch applies to the full-buy cost the round simulation uses.
 */
export function weaponChanges(rng: RNG, count: number): { changes: PatchChange[]; priceDelta: number } {
  const W: Array<[string, number, number]> = [
    ['AK-47', 2700, 36],
    ['M4A4', 3100, 33],
    ['M4A1-S', 2900, 38],
    ['AWP', 4750, 115],
    ['Galil AR', 1800, 30],
    ['FAMAS', 2050, 30],
    ['MP9', 1250, 26],
    ['MAC-10', 1050, 29],
    ['Desert Eagle', 700, 53],
    ['SSG 08', 1700, 88],
    ['UMP-45', 1200, 35],
  ];
  const out: PatchChange[] = [];
  const pool = [...W];
  let priceSum = 0;
  for (let i = 0; i < count && pool.length; i++) {
    const [name, price, dmg] = pool.splice(rng.int(0, pool.length - 1), 1)[0];
    const buff = rng.chance(0.5);
    const pct = 0.04 + rng.next() * 0.08;
    priceSum += buff ? -pct : pct;
    out.push({
      cat: 'weapon',
      name,
      dir: buff ? 'buff' : 'nerf',
      lines: [valueLine(rng, 'Price', price, 50, pct, 'down', buff).replace(/(\d+) → (\d+)/, '$$$1 → $$$2'), rng.chance(0.5) ? valueLine(rng, 'Base damage', dmg, 1, pct * 0.6, 'up', buff) : valueLine(rng, 'Moving inaccuracy', 100, 1, pct, 'down', buff, '%')],
    });
  }
  return { changes: out, priceDelta: count ? priceSum / count / 3 : 0 };
}

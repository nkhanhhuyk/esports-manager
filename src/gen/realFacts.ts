/**
 * Real birth dates, nationalities, names and listed roles (src/data/real/playerFacts.json, built by
 * scripts/data/player_facts.mjs from Leaguepedia, the VALORANT Esports Wiki and bo3.gg). Applied to
 * real players when a real-world career is created, and once to older real-world saves.
 * Facts replace estimates; anything still unknown stays an estimate and is labelled.
 */
import FACTS from '../data/real/playerFacts.json';
import { dayFromYMD } from '../core/date';
import { COUNTRIES, COUNTRY_BY_CODE } from '../data/countries';
import type { Player, World } from '../model/types';

interface Fact {
  name?: string;
  country?: string;
  countryCode?: string;
  birth?: string;
  birthYear?: number;
  role?: string;
  src: string;
}
const F = (FACTS as unknown as { players: Record<string, Fact> }).players;
const norm = (s: string) => s.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]/g, '');

const NAME_ALIAS: Record<string, string> = {
  'south korea': 'KR',
  korea: 'KR',
  'united states': 'US',
  usa: 'US',
  'united kingdom': 'GB',
  'czech republic': 'CZ',
  czechia: 'CZ',
  'hong kong': 'HK',
  taiwan: 'TW',
  vietnam: 'VN',
  russia: 'RU',
  turkey: 'TR',
  'united arab emirates': 'AE',
  palestine: 'PS',
  'dominican republic': 'DO',
};
let byName: Map<string, string> | null = null;
export function countryCode(name: string | undefined): string | null {
  if (!name) return null;
  const first = name.split(',')[0].trim().toLowerCase();
  if (!byName) {
    byName = new Map();
    for (const c of COUNTRIES) byName.set(c.name.toLowerCase(), c.code);
  }
  const code = NAME_ALIAS[first] ?? byName.get(first) ?? null;
  return code && COUNTRY_BY_CODE[code] ? code : null;
}

export function factFor(titleId: string, handle: string): Fact | undefined {
  return F[`${titleId}|${norm(handle)}`];
}

const LOL_ROLE: Record<string, string> = { top: 'top', jungle: 'jungle', mid: 'mid', bot: 'bot', adc: 'bot', support: 'support' };
const EASTERN = new Set(['KR', 'CN', 'TW', 'HK', 'VN', 'JP', 'MO']);

/**
 * Apply known facts to a real player. `roles` also corrects the listed main role (new careers
 * only — an ongoing career keeps its line-ups). Returns true when anything was applied.
 */
export function applyFacts(p: Player, opts: { roles?: boolean } = {}): boolean {
  const f = factFor(p.titleId, p.handle);
  if (!f || !p.real) return false;
  const est = new Set(p.real.est ?? []);
  let changed = false;
  if (f.birth) {
    const [y, m, d] = f.birth.split('-').map(Number);
    p.birth = dayFromYMD(y, m, d);
    est.delete('age');
    changed = true;
  } else if (f.birthYear) {
    p.birth = dayFromYMD(f.birthYear, 7, 1);
    est.delete('age');
    est.add('birthday (year known)');
    changed = true;
  }
  const cc = (f.countryCode && COUNTRY_BY_CODE[f.countryCode] ? f.countryCode : null) ?? countryCode(f.country);
  if (cc) {
    p.nat = cc;
    est.delete('nationality');
    changed = true;
  }
  if (f.name && !p.first && !p.last) {
    const parts = f.name.split(/\s+/).filter(Boolean);
    if (parts.length >= 2) {
      if (EASTERN.has(p.nat)) {
        p.last = parts[0];
        p.first = parts.slice(1).join(' ');
      } else {
        p.first = parts.slice(0, -1).join(' ');
        p.last = parts[parts.length - 1];
      }
      changed = true;
    }
  }
  if (opts.roles && p.titleId === 'lol' && f.role) {
    const r = LOL_ROLE[f.role.toLowerCase()];
    if (r && p.roles[0] !== r) {
      const old = p.roles[0];
      p.roles = [r, old === r ? p.roles[1] : old] as Player['roles'];
      est.delete('role');
      changed = true;
    }
  }
  if (changed) p.real = { ...p.real, est: [...est], facts: f.src };
  return changed;
}

/** Older real-world saves: apply birth dates, nationalities and names once. */
export function ensurePlayerFacts(world: World): number {
  if (world.meta.playerFacts || world.meta.dataMode !== 'real') {
    world.meta.playerFacts = true;
    return 0;
  }
  world.meta.playerFacts = true;
  let n = 0;
  for (const p of Object.values(world.players)) if (p.real && applyFacts(p)) n++;
  return n;
}

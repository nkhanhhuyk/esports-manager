import type { RNG } from '../core/rng';
import { COUNTRY_BY_CODE } from '../data/countries';
import { CULTURES, HANDLE_PREFIX, HANDLE_ROOTS, HANDLE_SUFFIX, ORG_SUFFIX, ORG_WORDS_A, ORG_WORDS_B, COMPANY_TYPES, COMPANY_WORDS } from '../data/names';

export function personName(rng: RNG, nat: string): { first: string; last: string } {
  const culture = CULTURES[COUNTRY_BY_CODE[nat]?.culture ?? 'english'] ?? CULTURES.english;
  return { first: rng.pick(culture.first), last: rng.pick(culture.last) };
}

export function isEastern(nat: string): boolean {
  return !!CULTURES[COUNTRY_BY_CODE[nat]?.culture ?? '']?.eastern;
}

export function displayName(first: string, last: string, nat: string): string {
  return isEastern(nat) ? `${last} ${first}` : `${first} ${last}`;
}

const LEET: Record<string, string> = { a: '4', e: '3', i: '1', o: '0', s: '5' };

export function makeHandle(rng: RNG, used: Set<string>): string {
  for (let tries = 0; tries < 40; tries++) {
    const root = rng.pick(HANDLE_ROOTS);
    const r = rng.next();
    let h: string;
    if (r < 0.4) h = root;
    else if (r < 0.52) h = root + rng.int(1, 99);
    else if (r < 0.62) h = rng.pick(HANDLE_PREFIX) + root;
    else if (r < 0.72) h = root + rng.pick(HANDLE_SUFFIX);
    else if (r < 0.8) h = root.toLowerCase();
    else if (r < 0.86) h = root.replace(/[aeios]/, (m) => LEET[m] ?? m);
    else if (r < 0.93) {
      const b = rng.pick(HANDLE_ROOTS);
      h = root.slice(0, Math.max(2, Math.ceil(root.length / 2))) + b.slice(Math.floor(b.length / 2)).toLowerCase();
    } else h = root.toUpperCase();
    if (h.length > 14) h = h.slice(0, 14);
    const key = h.toLowerCase();
    if (!used.has(key)) {
      used.add(key);
      return h;
    }
  }
  let n = 2;
  const base = rng.pick(HANDLE_ROOTS);
  while (used.has(`${base}${n}`.toLowerCase())) n++;
  used.add(`${base}${n}`.toLowerCase());
  return `${base}${n}`;
}

export function makeOrgName(rng: RNG, used: Set<string>): { name: string; short: string } {
  for (let tries = 0; tries < 60; tries++) {
    const a = rng.pick(ORG_WORDS_A);
    const b = rng.pick(ORG_WORDS_B);
    const r = rng.next();
    let name: string;
    if (r < 0.55) name = `${a} ${b}`;
    else if (r < 0.75) name = `Team ${a}`;
    else if (r < 0.9) name = `${b} ${rng.pick(ORG_SUFFIX)}`;
    else name = `${a}${b.toLowerCase()}`;
    if (used.has(name.toLowerCase())) continue;
    used.add(name.toLowerCase());
    const words = name.replace(/^Team /, '').split(' ');
    let short = words.length > 1 ? words.map((w) => w[0]).join('').toUpperCase() : name.replace(/^Team /, '').slice(0, 3).toUpperCase();
    if (short.length < 2) short = name.slice(0, 3).toUpperCase();
    return { name, short: short.slice(0, 4) };
  }
  const n = `Squad ${rng.int(100, 999)}`;
  used.add(n.toLowerCase());
  return { name: n, short: 'SQD' };
}

export function makeCompanyName(rng: RNG): string {
  return `${rng.pick(COMPANY_WORDS)} ${rng.pick(COMPANY_TYPES)}`;
}

const COLORS = ['#e63946', '#f1a208', '#2a9d8f', '#264653', '#e76f51', '#8338ec', '#3a86ff', '#ff006e', '#06d6a0', '#118ab2', '#073b4c', '#ffd166', '#ef476f', '#7209b7', '#4361ee', '#4cc9f0', '#f72585', '#2b9348', '#bc6c25', '#6c757d', '#d00000', '#0077b6', '#ffb703', '#023047'];

export function makeColors(rng: RNG): [string, string] {
  const a = rng.pick(COLORS);
  let b = rng.pick(COLORS);
  while (b === a) b = rng.pick(COLORS);
  return [a, b];
}

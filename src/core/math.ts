export const clamp = (x: number, lo: number, hi: number): number => (x < lo ? lo : x > hi ? hi : x);
export const clamp100 = (x: number): number => (x < 0 ? 0 : x > 100 ? 100 : x);
export const lerp = (a: number, b: number, t: number): number => a + (b - a) * t;
export const logistic = (x: number): number => 1 / (1 + Math.exp(-x));
export const round1 = (x: number): number => Math.round(x * 10) / 10;
export const round2 = (x: number): number => Math.round(x * 100) / 100;

export function sum(arr: readonly number[]): number {
  let s = 0;
  for (const x of arr) s += x;
  return s;
}

export function mean(arr: readonly number[]): number {
  return arr.length ? sum(arr) / arr.length : 0;
}

export function stdev(arr: readonly number[]): number {
  if (arr.length < 2) return 0;
  const m = mean(arr);
  let s = 0;
  for (const x of arr) s += (x - m) * (x - m);
  return Math.sqrt(s / (arr.length - 1));
}

export function argMax<T>(arr: readonly T[], f: (t: T) => number): T | undefined {
  let best: T | undefined;
  let bv = -Infinity;
  for (const x of arr) {
    const v = f(x);
    if (v > bv) {
      bv = v;
      best = x;
    }
  }
  return best;
}

export function groupBy<T, K extends string | number>(arr: readonly T[], key: (t: T) => K): Record<K, T[]> {
  const out = {} as Record<K, T[]>;
  for (const x of arr) {
    const k = key(x);
    (out[k] ||= []).push(x);
  }
  return out;
}

export function formatMoney(x: number): string {
  const sign = x < 0 ? '-' : '';
  const a = Math.abs(x);
  if (a >= 1e9) return `${sign}$${(a / 1e9).toFixed(2)}B`;
  if (a >= 1e6) return `${sign}$${(a / 1e6).toFixed(2)}M`;
  if (a >= 1e3) return `${sign}$${(a / 1e3).toFixed(0)}K`;
  return `${sign}$${a.toFixed(0)}`;
}

export function pct(x: number, digits = 0): string {
  return `${(x * 100).toFixed(digits)}%`;
}

export function ordinal(n: number): string {
  const s = ['th', 'st', 'nd', 'rd'];
  const v = n % 100;
  return n + (s[(v - 20) % 10] || s[v] || s[0]);
}

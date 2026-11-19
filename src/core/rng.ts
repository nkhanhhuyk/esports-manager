/**
 * Seeded, serialisable pseudo-random number generation.
 *
 * The simulation never uses Math.random(). Every random decision is drawn from an RNG whose
 * seed is derived from the world seed plus a stable "purpose key" (e.g. a fixture id, a day
 * number, an entity id). This makes results reproducible: the same world state + seed always
 * produces the same outcome, whether a match is watched in detail or simulated instantly.
 */

/** cyrb128 string hash -> four 32-bit seeds. */
export function hash128(str: string): [number, number, number, number] {
  let h1 = 1779033703, h2 = 3144134277, h3 = 1013904242, h4 = 2773480762;
  for (let i = 0; i < str.length; i++) {
    const k = str.charCodeAt(i);
    h1 = h2 ^ Math.imul(h1 ^ k, 597399067);
    h2 = h3 ^ Math.imul(h2 ^ k, 2869860233);
    h3 = h4 ^ Math.imul(h3 ^ k, 951274213);
    h4 = h1 ^ Math.imul(h4 ^ k, 2716044179);
  }
  h1 = Math.imul(h3 ^ (h1 >>> 18), 597399067);
  h2 = Math.imul(h4 ^ (h2 >>> 22), 2869860233);
  h3 = Math.imul(h1 ^ (h3 >>> 17), 951274213);
  h4 = Math.imul(h2 ^ (h4 >>> 19), 2716044179);
  return [(h1 ^ h2 ^ h3 ^ h4) >>> 0, (h2 ^ h1) >>> 0, (h3 ^ h1) >>> 0, (h4 ^ h1) >>> 0];
}

/** 32-bit FNV-1a hash, handy for quick stable integer keys. */
export function hash32(str: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

export type RngState = [number, number, number, number];

/** sfc32 generator: fast, good quality, 128-bit state, trivially serialisable. */
export class RNG {
  private a: number;
  private b: number;
  private c: number;
  private d: number;

  constructor(seed: string | RngState) {
    const s = typeof seed === 'string' ? hash128(seed) : seed;
    this.a = s[0] >>> 0;
    this.b = s[1] >>> 0;
    this.c = s[2] >>> 0;
    this.d = s[3] >>> 0;
    if ((this.a | this.b | this.c | this.d) === 0) this.d = 1;
    // Warm up so that similar seeds diverge quickly.
    for (let i = 0; i < 12; i++) this.next();
  }

  static derive(...parts: Array<string | number>): RNG {
    return new RNG(parts.join('|'));
  }

  state(): RngState {
    return [this.a, this.b, this.c, this.d];
  }

  /** Uniform float in [0, 1). */
  next(): number {
    const a = this.a, b = this.b, c = this.c, d = this.d;
    const t = (((a + b) | 0) + d) | 0;
    this.d = (d + 1) | 0;
    this.a = b ^ (b >>> 9);
    this.b = (c + (c << 3)) | 0;
    let cc = (c << 21) | (c >>> 11);
    cc = (cc + t) | 0;
    this.c = cc;
    return (t >>> 0) / 4294967296;
  }

  float(min = 0, max = 1): number {
    return min + (max - min) * this.next();
  }

  /** Integer in [min, max] inclusive. */
  int(min: number, max: number): number {
    return Math.floor(min + (max - min + 1) * this.next());
  }

  chance(p: number): boolean {
    return this.next() < p;
  }

  private spare: number | null = null;

  /** Standard normal via Box-Muller (both outputs used). */
  normal(mean = 0, sd = 1): number {
    if (this.spare !== null) {
      const z = this.spare;
      this.spare = null;
      return mean + sd * z;
    }
    let u = 0;
    while (u === 0) u = this.next();
    const v = this.next();
    const m = Math.sqrt(-2 * Math.log(u));
    this.spare = m * Math.sin(2 * Math.PI * v);
    return mean + sd * m * Math.cos(2 * Math.PI * v);
  }

  /** Normal clamped into [min, max]. */
  normalClamped(mean: number, sd: number, min: number, max: number): number {
    const x = this.normal(mean, sd);
    return x < min ? min : x > max ? max : x;
  }

  pick<T>(arr: readonly T[]): T {
    if (arr.length === 0) throw new Error('RNG.pick on empty array');
    return arr[Math.floor(this.next() * arr.length)];
  }

  /** Weighted choice. Weights <= 0 are never chosen (unless all are <= 0). */
  weighted<T>(items: readonly T[], weight: (t: T, i: number) => number): T {
    let total = 0;
    const ws = items.map((it, i) => {
      const w = Math.max(0, weight(it, i));
      total += w;
      return w;
    });
    if (total <= 0) return this.pick(items);
    let r = this.next() * total;
    for (let i = 0; i < items.length; i++) {
      r -= ws[i];
      if (r <= 0) return items[i];
    }
    return items[items.length - 1];
  }

  weightedIndex(weights: readonly number[]): number {
    let total = 0;
    for (const w of weights) total += Math.max(0, w);
    if (total <= 0) return Math.floor(this.next() * weights.length);
    let r = this.next() * total;
    for (let i = 0; i < weights.length; i++) {
      r -= Math.max(0, weights[i]);
      if (r <= 0) return i;
    }
    return weights.length - 1;
  }

  shuffle<T>(arr: T[]): T[] {
    for (let i = arr.length - 1; i > 0; i--) {
      const j = Math.floor(this.next() * (i + 1));
      const t = arr[i];
      arr[i] = arr[j];
      arr[j] = t;
    }
    return arr;
  }

  /** Sample k distinct items. */
  sample<T>(arr: readonly T[], k: number): T[] {
    return this.shuffle(arr.slice()).slice(0, Math.min(k, arr.length));
  }

  /** Create an independent child generator. Consumes one draw from this generator. */
  fork(label: string): RNG {
    return new RNG(`${this.next()}|${label}|${this.a}`);
  }
}

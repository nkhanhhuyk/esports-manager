/**
 * Polite fetch helper for building the offline real-world snapshot (development only; the game
 * never fetches at runtime). Liquipedia API terms: gzip, identifying User-Agent, ≤ 1 request / 2 s
 * (action=parse would be 1 / 30 s — we only use action=query). Responses are cached on disk.
 */
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { gunzipSync } from 'node:zlib';
import { execFileSync } from 'node:child_process';

export const CACHE = process.env.SNAPSHOT_CACHE ?? join(process.cwd(), '.snapshot-cache');
export const UA = 'EsportsManagerOfflineSnapshot/1.0 (personal non-commercial game; offline data snapshot)';
let last = 0;

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

/** GET a URL (cached). Uses curl so the environment proxy and CA bundle apply. */
export function get(url, { liquipedia = url.includes('liquipedia.net') } = {}) {
  mkdirSync(CACHE, { recursive: true });
  const key = createHash('sha1').update(url).digest('hex');
  const file = join(CACHE, key);
  if (existsSync(file)) return JSON.parse(readFileSync(file, 'utf8')).body;
  if (liquipedia) {
    const wait = 2100 - (Date.now() - last);
    if (wait > 0) sleep(wait);
    last = Date.now();
  }
  const out = execFileSync('curl', ['-sS', '-m', '60', '--compressed', '-A', UA, '-H', 'Accept-Encoding: gzip', url], { maxBuffer: 64 * 1024 * 1024 });
  const body = out.toString('utf8');
  writeFileSync(file, JSON.stringify({ url, fetchedAt: new Date().toISOString(), body }));
  return body;
}

export function getJSON(url) {
  return JSON.parse(get(url));
}

/** Wikitext of a Liquipedia page (null if missing). */
export function lpWikitext(wiki, title) {
  const url = `https://liquipedia.net/${wiki}/api.php?action=query&prop=revisions&rvprop=content|timestamp&rvslots=main&format=json&formatversion=2&redirects=1&titles=${encodeURIComponent(title)}`;
  const j = JSON.parse(get(url));
  const page = j?.query?.pages?.[0];
  if (!page || page.missing) return null;
  const rev = page.revisions?.[0];
  return { title: page.title, text: rev?.slots?.main?.content ?? '', timestamp: rev?.timestamp, url: `https://liquipedia.net/${wiki}/${encodeURIComponent(page.title.replace(/ /g, '_'))}` };
}

export function lpQuery(wiki, params) {
  const qs = new URLSearchParams({ action: 'query', format: 'json', formatversion: '2', ...params });
  return JSON.parse(get(`https://liquipedia.net/${wiki}/api.php?${qs}`));
}

export const today = () => new Date().toISOString().slice(0, 10);

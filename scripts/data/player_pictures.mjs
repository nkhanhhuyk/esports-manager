#!/usr/bin/env node
/**
 * More picture links, added to src/data/real/images.json after scripts/data/images.mjs
 * (development-time only):
 *
 *   node scripts/data/player_pictures.mjs <cache dir>
 *
 * Only LINKS are collected (nothing is copied into the repository or the APK):
 *  - LoL player photos missing from the LoL Esports gateway (mostly past players): Leaguepedia
 *    (lol.fandom.com, CC BY-SA 3.0) Cargo tables Players (ID → page) and PlayerImages (the newest
 *    team photo per player), resolved to a 160 px thumbnail on Fandom's image CDN.
 *  - Valorant player photos missing from vlr.gg team pages (past players, substitutes): the player's
 *    own vlr.gg page (found through the match and team pages the snapshot came from).
 *  - Honor of Kings heroes without an official CN hero id (global-only heroes): the official global
 *    site's icon (camp.honorofkings.com) where hokstats.gg links one, else hokstats.gg's own icon.
 * Requests are spaced (Fandom ≥ 2.1 s, vlr.gg ≥ 1.3 s) and cached.
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const UA = 'EsportsManagerOfflineSnapshot/1.0 (personal non-commercial game; offline data snapshot)';
const CACHE = process.argv[2] || '.cache/player-pictures';
fs.mkdirSync(CACHE, { recursive: true });
const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../..');
const read = (f) => JSON.parse(fs.readFileSync(path.join(ROOT, f), 'utf8'));
const rosters = read('src/data/real/rosters.json');
const history = read('src/data/real/history.json');
const chars = read('src/data/real/characters.json').titles;
const IMAGES = path.join(ROOT, 'src/data/real/images.json');
const out = JSON.parse(fs.readFileSync(IMAGES, 'utf8'));

const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const last = {};
function get(url, gap) {
  const f = path.join(CACHE, createHash('sha1').update(url).digest('hex').slice(0, 16));
  if (fs.existsSync(f)) return fs.readFileSync(f, 'utf8');
  const host = new URL(url).host;
  const wait = (last[host] ?? 0) + gap - Date.now();
  if (wait > 0) sleep(wait);
  last[host] = Date.now();
  let res;
  try {
    res = execFileSync('curl', ['-sS', '-m', '90', '--compressed', '-A', UA, '-w', '\n%{http_code}', url], { maxBuffer: 256 << 20 }).toString();
  } catch {
    console.warn('fetch failed', url.slice(0, 120));
    return null;
  }
  const code = res.slice(res.lastIndexOf('\n') + 1);
  const body = res.slice(0, res.lastIndexOf('\n'));
  if (code !== '200') {
    console.warn('HTTP', code, url.slice(0, 120));
    return null;
  }
  fs.writeFileSync(f, body);
  return body;
}
const json = (url, gap) => {
  const b = get(url, gap);
  try {
    return b ? JSON.parse(b) : null;
  } catch {
    return null;
  }
};
const norm = (s) => String(s).toLowerCase().normalize('NFKD').replace(/[^a-z0-9]/g, '');
const abs = (u) => (u.startsWith('//') ? `https:${u}` : u);
const handlesOf = (title) => [...new Set([...rosters.teams.filter((t) => t.title_id === title).flatMap((t) => t.roster.filter((p) => p.status !== 'staff').map((p) => p.handle)), ...history.players.filter((p) => p.title_id === title).map((p) => p.handle)])];
const before = Object.keys(out.players).length;

// ------------------------------------------------------------------ LoL: Leaguepedia
{
  const API = 'https://lol.fandom.com/api.php';
  const missing = handlesOf('lol').filter((h) => !out.players[`lol|${norm(h)}`]);
  const page = new Map(); // norm(handle) → overview page
  for (let i = 0; i < missing.length; i += 40) {
    const where = `ID IN (${missing.slice(i, i + 40).map((h) => `"${h.replace(/"/g, '')}"`).join(',')})`;
    const j = json(`${API}?action=cargoquery&tables=Players&fields=ID,OverviewPage&where=${encodeURIComponent(where)}&limit=500&format=json`, 2100);
    for (const r of j?.cargoquery ?? []) if (r.title.OverviewPage && !page.has(norm(r.title.ID))) page.set(norm(r.title.ID), r.title.OverviewPage);
  }
  // Newest photo per page: the highest year in the file name, later rows winning ties.
  const best = new Map();
  const pages = [...new Set(page.values())];
  for (let i = 0; i < pages.length; i += 15) {
    const where = `Link IN (${pages.slice(i, i + 15).map((p) => `"${p.replace(/"/g, '')}"`).join(',')})`;
    const j = json(`${API}?action=cargoquery&tables=PlayerImages&fields=FileName,Link&where=${encodeURIComponent(where)}&limit=500&format=json`, 2100);
    for (const r of j?.cargoquery ?? []) {
      const { FileName: file, Link: link } = r.title;
      if (!file || !link) continue;
      const year = Math.max(0, ...[...file.matchAll(/(?:^|\D)(20\d\d)(?:\D|$)/g)].map((m) => Number(m[1])));
      const cur = best.get(link);
      if (!cur || year >= cur.year) best.set(link, { file, year });
    }
  }
  const files = [...new Set([...best.values()].map((b) => b.file))];
  const url = new Map();
  for (let i = 0; i < files.length; i += 50) {
    const titles = files.slice(i, i + 50).map((f) => `File:${f}`).join('|');
    const j = json(`${API}?action=query&prop=imageinfo&iiprop=url&iiurlwidth=160&format=json&titles=${encodeURIComponent(titles)}`, 2100);
    const from = new Map((j?.query?.normalized ?? []).map((n) => [n.to, n.from]));
    for (const p of Object.values(j?.query?.pages ?? {})) {
      const ii = p.imageinfo?.[0];
      if (ii) url.set((from.get(p.title) ?? p.title).replace(/^File:/, ''), ii.thumburl || ii.url);
    }
  }
  let n = 0;
  for (const h of missing) {
    const p = page.get(norm(h));
    const f = p && best.get(p)?.file;
    const u = f && url.get(f);
    if (u && !out.players[`lol|${norm(h)}`]) {
      out.players[`lol|${norm(h)}`] = u;
      n++;
    }
  }
  out.sources.lolplayers = 'Leaguepedia PlayerImages (past players not on the LoL Esports gateway)';
  console.log('lol: missing', missing.length, 'pages', page.size, 'photos', n);
}

// ------------------------------------------------------------------ Valorant: vlr.gg player pages
{
  const missing = new Set(handlesOf('valorant').map(norm).filter((h) => !out.players[`valorant|${h}`]));
  const link = new Map(); // norm(handle) → /player/id/slug
  const sources = new Set([...rosters.teams.filter((t) => t.title_id === 'valorant').map((t) => t.source_url), ...history.players.filter((p) => p.title_id === 'valorant').map((p) => p.source_url)].filter((u) => /vlr\.gg/.test(u ?? '')));
  for (const src of sources) {
    const html = get(src, 1300);
    if (!html) continue;
    for (const m of html.matchAll(/href="(\/player\/\d+\/([^"/]+))"/g)) {
      const k = norm(m[2]);
      if (missing.has(k) && !link.has(k)) link.set(k, m[1]);
    }
    // Team pages show the alias next to the link; match pages show it in the link text.
    for (const m of html.matchAll(/href="(\/player\/\d+\/[^"]+)"[\s\S]{0,600}?(?:team-roster-item-name-alias">\s*(?:<i[^>]*><\/i>)?\s*|text-of">\s*)([^<\n]+)/g)) {
      const k = norm(m[2].trim());
      if (missing.has(k) && !link.has(k)) link.set(k, m[1]);
    }
  }
  let n = 0;
  for (const [k, href] of link) {
    const html = get(`https://www.vlr.gg${href}`, 1300);
    const m = html?.match(/wf-avatar mod-player[\s\S]{0,200}?<img src="([^"]+)"/);
    if (!m || /\/ph\/|sil\.png/.test(m[1])) continue;
    out.players[`valorant|${k}`] = abs(m[1]);
    n++;
  }
  out.sources.valorantplayers = 'vlr.gg player pages (players without a photo on their team page)';
  console.log('valorant: missing', missing.size, 'linked', link.size, 'photos', n);
}

// ------------------------------------------------------------------ HoK global-only heroes
{
  const home = get('https://hokstats.gg/', 1500) ?? '';
  let n = 0;
  for (const c of chars.hok.characters) {
    if (out.characters[c.character_id]) continue;
    const slug = c.name.toLowerCase().replace(/['’]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
    const m = home.match(new RegExp(`href="/heroes/${slug}/"[^>]*><img src="([^"]+)"`));
    if (!m) continue;
    out.characters[c.character_id] = m[1].startsWith('/') ? `https://hokstats.gg${m[1]}` : m[1];
    n++;
  }
  out.sources.hokglobal = 'Global-only HoK heroes: official camp.honorofkings.com icons linked by hokstats.gg, else hokstats.gg icons';
  console.log('hok heroes added', n);
}

for (const group of ['characters', 'players']) for (const [k, v] of Object.entries(out[group])) out[group][k] = String(v).replace(/^http:\/\//, 'https://');
// Links found dead when checked (20 Nov 2026): the game would only fall back to its own badge.
const DEAD = { teams: ['dota2|bbteam', 'dota2|perurejects', 'dota2|puckchamp', 'dota2|teamzero', 'dota2|teamaster', 'dota2|thedudleyboys'], players: ['lol|juice'] };
for (const [group, keys] of Object.entries(DEAD)) for (const k of keys) delete out[group][k];
fs.writeFileSync(IMAGES, JSON.stringify(out));
console.log('players', before, '→', Object.keys(out.players).length, 'characters', Object.keys(out.characters).length);

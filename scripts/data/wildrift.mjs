#!/usr/bin/env node
/**
 * Real Wild Rift teams (development-time only):
 *
 *   node scripts/data/wildrift.mjs <cache dir>   → src/data/real/wildrift.json
 *
 * Source: the Wild Rift Esports Wiki (wildrift-esports.fandom.com, CC BY-SA 3.0), team pages in
 * Category:Teams — the most recent data reachable from here (the wiki was last updated in early 2021;
 * newer Wild Rift esports data lives on Liquipedia, which blocks this environment). Each team's
 * "Active" roster: handle, country, real name, role. Teams are grouped into regional leagues named
 * after Riot's 2021 regional circuits (Icon Series). Requests are spaced ≥ 8 s (the wiki rate-limits).
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const UA = 'EsportsManagerOfflineSnapshot/1.0 (personal non-commercial game; offline data snapshot)';
const CACHE = process.argv[2] || '.cache/wildrift';
fs.mkdirSync(CACHE, { recursive: true });
const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../..');
const API = 'https://wildrift-esports.fandom.com/api.php';
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
let last = 0;
function get(url) {
  const f = path.join(CACHE, createHash('sha1').update(url).digest('hex').slice(0, 16));
  if (fs.existsSync(f)) return JSON.parse(fs.readFileSync(f, 'utf8'));
  for (let attempt = 0; attempt < 4; attempt++) {
    const wait = last + 8000 * (attempt + 1) - Date.now();
    if (wait > 0) sleep(wait);
    last = Date.now();
    const body = execFileSync('curl', ['-sS', '-m', '60', '--compressed', '-A', UA, url]).toString();
    const j = JSON.parse(body);
    if (j.error?.code === 'ratelimited') continue;
    fs.writeFileSync(f, body);
    return j;
  }
  throw new Error(`rate limited: ${url}`);
}

const cat = get(`${API}?action=query&list=categorymembers&cmtitle=Category:Teams&cmlimit=500&format=json`);
const titles = cat.query.categorymembers.map((m) => m.title);
const pages = [];
for (let i = 0; i < titles.length; i += 20) {
  const batch = titles.slice(i, i + 20);
  const j = get(`${API}?action=query&prop=revisions&rvprop=content&rvslots=main&format=json&titles=${encodeURIComponent(batch.join('|'))}`);
  for (const p of Object.values(j.query.pages)) {
    const text = p.revisions?.[0]?.slots?.main?.['*'];
    if (text) pages.push({ title: p.title, text });
  }
}

const field = (text, k) => (text.match(new RegExp(`\\|\\s*${k}\\s*=([^|\\n}]*)`, 'i'))?.[1] ?? '').trim();
const ROLE = { top: 'baron', t: 'baron', baron: 'baron', jungle: 'jungle', j: 'jungle', jungler: 'jungle', mid: 'mid', m: 'mid', adc: 'dragon', ad: 'dragon', a: 'dragon', bot: 'dragon', dragon: 'dragon', marksman: 'dragon', support: 'support', s: 'support', suport: 'support' };
// Wiki region labels → the game's league groups.
const GROUP = {
  SEA: ['SEA', 'Southeast Asia', 'Philippines', 'Indonesia', 'Thailand', 'Vietnam', 'Malaysia', 'Singapore', 'Oceania', 'OCE'],
  EMEA: ['EU', 'Europe', 'EMEA', 'CIS', 'Turkey', 'TR', 'MENA', 'Russia'],
  AMER: ['NA', 'North America', 'BR', 'Brazil', 'LATAM', 'Latin America', 'LAN', 'LAS'],
  APN: ['JP', 'Japan', 'KR', 'Korea', 'TW', 'Taiwan', 'PCS', 'Hong Kong', 'China', 'CN'],
};
const groupOf = (region, country) => {
  const r = `${region}`.trim();
  for (const [g, names] of Object.entries(GROUP)) if (names.some((n) => n.toLowerCase() === r.toLowerCase())) return g;
  for (const [g, names] of Object.entries(GROUP)) if (names.some((n) => n.toLowerCase() === `${country}`.trim().toLowerCase())) return g;
  return null;
};
// Southeast Asia (most teams) is split by country; Europe is one league; regions with too few teams
// for a league (Americas, Asia Pacific North) keep their teams outside the leagues (dormant).
const LEAGUES = {
  'SEA-PI': { league_id: 'wr-sea-pi', name: 'Wild Rift SEA Icon Series — Philippines & Indonesia', short: 'WR PH/ID' },
  'SEA-M': { league_id: 'wr-sea-m', name: 'Wild Rift SEA Icon Series — Mainland & Oceania', short: 'WR SEA' },
  EMEA: { league_id: 'wr-emea', name: 'Wild Rift Europe Icon Series', short: 'WR EU' },
  AMER: { league_id: null, name: 'Americas', short: 'AM' },
  APN: { league_id: null, name: 'Asia Pacific North', short: 'APN' },
};
const PI = new Set(['ph', 'philippines', 'id', 'indonesia']);

const teams = [];
for (const { title, text } of pages) {
  const name = field(text, 'name') || title;
  const region = field(text, 'region');
  const country = field(text, 'country');
  const g = groupOf(region, country);
  if (!g) continue;
  const active = text.split(/===\s*Former\s*===/i)[0];
  const roster = [];
  for (const m of active.matchAll(/\{\{listplayer\/Current\|([^|}]*)\|([^|}]*)\|([^|}]*)\|([^|}]*)/g)) {
    let handle = m[1].trim();
    if (!handle || /InGameName/i.test(handle)) continue;
    const sub = /\(substitute\)|\(sub\)/i.test(handle);
    handle = handle.replace(/\s*\((substitute|sub)\)\s*/i, '').trim();
    const role = ROLE[m[4].trim().toLowerCase()] ?? null;
    roster.push({ handle, real_name: m[3].trim() || null, nationality_name: m[2].trim() || null, role, role_source: role ? 'Wild Rift Esports Wiki roster' : null, status: 'player', starter: !sub, role_text: m[4].trim() });
  }
  const coach = field(text, 'headcoach');
  if (coach) roster.push({ handle: coach.replace(/\[\[|\]\]/g, ''), real_name: null, nationality_name: null, role: 'head-coach', status: 'staff', role_text: 'Head coach' });
  if (roster.filter((p) => p.status === 'player').length < 3) continue;
  let key = g;
  if (g === 'SEA') {
    const n = {};
    for (const p of roster) if (p.status === 'player') n[`${p.nationality_name}`.toLowerCase()] = (n[`${p.nationality_name}`.toLowerCase()] ?? 0) + 1;
    const top = Object.entries(n).sort((a, b) => b[1] - a[1])[0]?.[0];
    key = PI.has(top) ? 'SEA-PI' : 'SEA-M';
  }
  const logoFile = field(text, 'image');
  teams.push({
    logo_file: logoFile || null,
    team_id: `wildrift-${name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`,
    title_id: 'wildrift',
    league_id: LEAGUES[key].league_id,
    name,
    short: (field(text, 'short') || name).replace(/\s+/g, '').slice(0, 6).toUpperCase(),
    org: name,
    country_name: field(text, 'orgcountry') || country || null,
    region: key,
    source_url: `https://wildrift-esports.fandom.com/wiki/${encodeURIComponent(title.replace(/ /g, '_'))}`,
    collected_at: new Date().toISOString(),
    roster,
  });
}
// Team logos: resolve the wiki's file names to image URLs (batched).
{
  const files = teams.map((t) => t.logo_file).filter(Boolean);
  for (let i = 0; i < files.length; i += 40) {
    const batch = files.slice(i, i + 40).map((f) => `File:${f}`);
    const j = get(`${API}?action=query&prop=imageinfo&iiprop=url&format=json&titles=${encodeURIComponent(batch.join('|'))}`);
    const norm = new Map((j.query.normalized ?? []).map((n) => [n.to, n.from]));
    for (const p of Object.values(j.query.pages ?? {})) {
      const url = p.imageinfo?.[0]?.url;
      if (!url) continue;
      const asked = (norm.get(p.title) ?? p.title).replace(/^File:/, '');
      for (const t of teams) if (t.logo_file && (t.logo_file === asked || `File:${t.logo_file}`.replace(/_/g, ' ') === p.title)) t.logo_url = url;
    }
  }
}
// A player listed on two teams' active rosters keeps the first (league teams before dormant ones).
{
  const seen = new Set();
  for (const t of [...teams].sort((a, b) => (a.league_id ? 0 : 1) - (b.league_id ? 0 : 1)))
    t.roster = t.roster.filter((p) => {
      if (p.status !== 'player') return true;
      const k = p.handle.toLowerCase();
      if (seen.has(k)) return false;
      seen.add(k);
      return true;
    });
}
const used = new Set(teams.map((t) => t.league_id).filter(Boolean));
const leagues = Object.entries(LEAGUES)
  .filter(([, l]) => l.league_id && used.has(l.league_id))
  .map(([g, l]) => ({ ...l, title_id: 'wildrift', region: g, tier: 1, season: 'Wild Rift 2021 (wiki data as of early 2021)', source_url: 'https://wildrift-esports.fandom.com/wiki/Category:Teams' }));
fs.writeFileSync(path.join(ROOT, 'src/data/real/wildrift.json'), JSON.stringify({ snapshot_id: 'real-2026-11-20', as_of: '2021 (wiki)', leagues, teams }, null, 1));
const by = {};
for (const t of teams) by[t.league_id] = (by[t.league_id] ?? 0) + 1;
console.log('teams', teams.length, by, 'players', teams.reduce((a, t) => a + t.roster.filter((p) => p.status === 'player').length, 0));

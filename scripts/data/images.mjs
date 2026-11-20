#!/usr/bin/env node
/**
 * Build the image-link snapshot (development-time only):
 *
 *   node scripts/data/images.mjs <cache dir>   → src/data/real/images.json
 *
 * Only LINKS are collected: the game loads each picture from the owner's own server when the device
 * is online and shows its own original badge otherwise. No image is copied into the repository or
 * the APK. Sources (see DATA_SOURCES.md → Pictures):
 *  - LoL & Wild Rift champions: Riot Data Dragon (Riot's developer CDN; Wild Rift by champion name)
 *  - Valorant agents & weapons: valorant-api.com (community mirror of Riot game data, Riot CDN media)
 *  - Dota 2 heroes: OpenDota hero list → Valve's Dota 2 CDN (cdn.cloudflare.steamstatic.com)
 *  - Honor of Kings heroes: official hero id → Tencent's official CDN (game.gtimg.cn)
 *  - MLBB heroes: mlbbhub.com hero pages → Moonton's official CDN (akmweb.youngjoygame.com)
 *  - CS2 weapons: ByMykel/CSGO-API (Steam community economy images)
 *  - LoL team logos, player photos, league & event logos: LoL Esports gateway (getTeams, getLeagues)
 *  - Valorant team logos & player photos: vlr.gg team pages; VCT league logos: vlr.gg event pages
 *  - Dota 2 team logos: OpenDota team records
 *  - MLBB team logos: id-mpl.com / ph-mpl.com team pages (player photos there are expiring signed links — not used)
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const UA = 'EsportsManagerOfflineSnapshot/1.0 (personal non-commercial game; offline data snapshot)';
const CACHE = process.argv[2] || '.cache/images';
fs.mkdirSync(CACHE, { recursive: true });
const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../..');
const rosters = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/data/real/rosters.json'), 'utf8'));
const history = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/data/real/history.json'), 'utf8'));
const chars = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/data/real/characters.json'), 'utf8')).titles;

const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const lastHit = {};
function get(url, { headers = [], gap = 1000 } = {}) {
  const f = path.join(CACHE, createHash('sha1').update(url).digest('hex').slice(0, 16));
  if (fs.existsSync(f)) return fs.readFileSync(f, 'utf8');
  const host = new URL(url).host;
  const wait = (lastHit[host] ?? 0) + gap - Date.now();
  if (wait > 0) sleep(wait);
  lastHit[host] = Date.now();
  const args = ['-sS', '-m', '90', '--compressed', '-A', UA, '-w', '\n%{http_code}'];
  for (const h of headers) args.push('-H', h);
  args.push(url);
  let out;
  try {
    out = execFileSync('curl', args, { maxBuffer: 512 << 20 }).toString();
  } catch (e) {
    console.warn('fetch failed', url, String(e).slice(0, 120));
    return null;
  }
  const code = out.slice(out.lastIndexOf('\n') + 1);
  const body = out.slice(0, out.lastIndexOf('\n'));
  if (code !== '200') {
    console.warn('HTTP', code, url);
    return null;
  }
  fs.writeFileSync(f, body);
  return body;
}
const json = (url, o) => {
  const b = get(url, o);
  try {
    return b ? JSON.parse(b) : null;
  } catch {
    return null;
  }
};
const norm = (s) => String(s).toLowerCase().normalize('NFKD').replace(/[^a-z0-9]/g, '');
const abs = (u) => (u.startsWith('//') ? `https:${u}` : u);

const out = { snapshot_id: 'real-2026-11-20', as_of: '2026-11-20', characters: {}, weapons: {}, teams: {}, players: {}, leagues: {}, events: {}, sources: {} };
const charId = (title, name) => chars[title]?.characters.find((c) => norm(c.name) === norm(name))?.character_id;

// ---------------------------------------------------------------- characters
{
  const vers = json('https://ddragon.leagueoflegends.com/api/versions.json');
  const ver = vers[0];
  const champs = json(`https://ddragon.leagueoflegends.com/cdn/${ver}/data/en_US/champion.json`).data;
  const byName = new Map(Object.values(champs).map((c) => [norm(c.name), c.id]));
  for (const title of ['lol', 'wildrift'])
    for (const c of chars[title].characters) {
      const id = byName.get(norm(c.name)) ?? byName.get(norm(c.name.replace(/&.*/, '')));
      if (id) out.characters[c.character_id] = `https://ddragon.leagueoflegends.com/cdn/${ver}/img/champion/${id}.png`;
    }
  out.sources.lol = `Riot Data Dragon ${ver}`;
  out.sources.wildrift = `Riot Data Dragon ${ver} (League of Legends icons of the same champions)`;
}
{
  const agents = json('https://valorant-api.com/v1/agents?isPlayableCharacter=true').data;
  for (const a of agents) {
    const id = charId('valorant', a.displayName);
    if (id) out.characters[id] = a.displayIcon;
  }
  const weapons = json('https://valorant-api.com/v1/weapons').data;
  for (const w of weapons) out.weapons[`valorant|${norm(w.displayName)}`] = w.killStreamIcon || w.displayIcon;
  out.sources.valorant = 'valorant-api.com (agents, weapons)';
}
{
  const heroes = json('https://api.opendota.com/api/heroes', { gap: 1100 });
  const DOTA_ALIAS = { outworlddestroyer: 'Outworld Devourer' };
  for (const h of heroes) {
    const id = charId('dota2', h.localized_name) ?? charId('dota2', DOTA_ALIAS[norm(h.localized_name)] ?? '');
    if (id) out.characters[id] = `https://cdn.cloudflare.steamstatic.com/apps/dota2/images/dota_react/heroes/${h.name.replace('npc_dota_hero_', '')}.png`;
  }
  out.sources.dota2 = 'OpenDota hero list → Valve Dota 2 CDN';
}
for (const c of chars.hok.characters) if (c.official_id) out.characters[c.character_id] = `https://game.gtimg.cn/images/yxzj/img201606/heroimg/${c.official_id}/${c.official_id}.jpg`;
out.sources.hok = 'Tencent official hero icons (game.gtimg.cn) by official hero id';
for (const c of chars.mlbb.characters) {
  const slug = c.name.toLowerCase().replace(/['’]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
  const html = get(`https://mlbbhub.com/heroes/${slug}`, { gap: 1200 });
  if (!html) continue;
  const esc = c.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/'/g, "(?:'|&#x27;|&#39;)");
  const m = html.match(new RegExp(`<img alt="${esc} - [^"]*"[^>]*src="https://wsrv\\.nl/\\?url=([^&"]+)`));
  if (m) out.characters[c.character_id] = decodeURIComponent(m[1]);
}
out.sources.mlbb = 'mlbbhub.com hero pages → Moonton official CDN (akmweb.youngjoygame.com)';
{
  const cs = json('https://raw.githubusercontent.com/ByMykel/CSGO-API/main/public/api/en/base_weapons.json');
  for (const w of cs ?? []) if (w.image && w.name) out.weapons[`cs2|${norm(w.name)}`] = w.image;
  out.sources.cs2 = 'ByMykel/CSGO-API base weapons (Steam community images)';
}

// ---------------------------------------------------------------- LoL esports
{
  const KEY = 'x-api-key: 0TvQnueqKa5mxJntVWt0w4LpLfEkrV1Ta8rQBb9Z';
  const leagues = json('https://esports-api.lolesports.com/persisted/gw/getLeagues?hl=en-US', { headers: [KEY] })?.data?.leagues ?? [];
  for (const l of leagues) if (l.image) out.leagues[`lol|${norm(l.name)}`] = l.image;
  const find = (n) => leagues.find((l) => norm(l.name) === norm(n))?.image;
  if (find('First Stand')) out.events['lol|fs'] = find('First Stand');
  if (find('MSI')) out.events['lol|msi'] = find('MSI');
  if (find('Worlds')) out.events['lol|worlds'] = find('Worlds');
  const teams = json('https://esports-api.lolesports.com/persisted/gw/getTeams?hl=en-US', { headers: [KEY] })?.data?.teams ?? [];
  const ours = new Set([...rosters.teams, ...history.teams].filter((t) => t.title_id === 'lol').map((t) => norm(t.name)));
  const handles = new Set([...rosters.teams.filter((t) => t.title_id === 'lol').flatMap((t) => t.roster.map((p) => norm(p.handle))), ...history.players.filter((p) => p.title_id === 'lol').map((p) => norm(p.handle))]);
  for (const t of teams) {
    if (ours.has(norm(t.name)) && t.image && !/default/i.test(t.image)) out.teams[`lol|${norm(t.name)}`] = t.image;
    for (const p of t.players ?? []) if (p.image && handles.has(norm(p.summonerName)) && !/default|silhouette/i.test(p.image)) out.players[`lol|${norm(p.summonerName)}`] ??= p.image;
  }
}

// ---------------------------------------------------------------- vlr.gg (Valorant)
{
  const pages = new Set(rosters.teams.filter((t) => t.title_id === 'valorant' && /vlr\.gg\/team\//.test(t.source_url)).map((t) => t.source_url));
  for (const url of pages) {
    const html = get(url, { gap: 1200 });
    if (!html) continue;
    const team = rosters.teams.find((t) => t.source_url === url);
    const logo = html.match(/<img src="(\/\/owcdn\.net\/img\/[^"]+)" alt="[^"]*team logo">/);
    if (logo) out.teams[`valorant|${norm(team.name)}`] = abs(logo[1]);
    for (const m of html.matchAll(/team-roster-item-img">\s*<img src="([^"]+)">[\s\S]*?team-roster-item-name-alias">\s*(?:<i[^>]*><\/i>)?\s*([^<\n]+)/g)) {
      if (/\/ph\/|sil\.png/.test(m[1])) continue;
      out.players[`valorant|${norm(m[2].trim())}`] ??= abs(m[1]);
    }
  }
  const leagues = rosters.leagues.filter((l) => l.title_id === 'valorant' && /vlr\.gg/.test(l.source_url ?? ''));
  for (const l of leagues) {
    const html = get(l.source_url, { gap: 1200 });
    const m = html?.match(/event-header-thumb[\s\S]{0,200}?<img src="([^"]+)"/);
    if (m) out.leagues[`valorant|${norm(l.name)}`] = abs(m[1]);
  }
}

// ---------------------------------------------------------------- OpenDota (Dota 2 team logos)
{
  const ours = [...rosters.teams, ...history.teams].filter((t) => t.title_id === 'dota2');
  for (const t of ours) {
    const id = (t.source_url ?? '').match(/teams\/(\d+)/)?.[1];
    if (!id) continue;
    const rec = json(`https://api.opendota.com/api/teams/${id}`, { gap: 1100 });
    if (rec?.logo_url) out.teams[`dota2|${norm(t.name)}`] = rec.logo_url;
  }
}

// ---------------------------------------------------------------- MPL (MLBB team logos)
{
  const ours = rosters.teams.filter((t) => t.title_id === 'mlbb' && /mpl\.com\/team\//.test(t.source_url));
  for (const t of ours) {
    const html = get(t.source_url, { gap: 1200 });
    const m = html?.match(/<img src='(?:https:\/\/wsrv\.nl\/\?url=)?([^']+)' alt="[^"]*" class="team-logo"/);
    if (m) out.teams[`mlbb|${norm(t.name)}`] = m[1];
  }
  for (const l of rosters.leagues.filter((x) => x.title_id === 'mlbb')) {
    const site = /philippines/i.test(l.name) ? 'https://ph-mpl.com/' : 'https://id-mpl.com/';
    const html = get(site, { gap: 1200 });
    const m = html?.match(/<img src="([^"]+LOGO[^"]+)"\s+alt="MPL logo">/i);
    if (m) out.leagues[`mlbb|${norm(l.name)}`] = m[1];
  }
}

// Android WebViews block plain-http images inside the https app page.
for (const group of ['characters', 'weapons', 'teams', 'players', 'leagues', 'events']) for (const [k, v] of Object.entries(out[group])) out[group][k] = String(v).replace(/^http:\/\//, 'https://');
const count = (o) => Object.keys(o).length;
console.log(`characters ${count(out.characters)} weapons ${count(out.weapons)} teams ${count(out.teams)} players ${count(out.players)} leagues ${count(out.leagues)} events ${count(out.events)}`);
fs.writeFileSync(path.join(ROOT, 'src/data/real/images.json'), JSON.stringify(out));

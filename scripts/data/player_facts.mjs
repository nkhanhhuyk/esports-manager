#!/usr/bin/env node
/**
 * Build the player-facts snapshot (development-time only):
 *
 *   node scripts/data/player_facts.mjs <cache dir>   → src/data/real/playerFacts.json
 *
 * Birth dates, nationalities, real names and (LoL) listed roles of the real players in
 * rosters.json and history.json. Sources (Liquipedia could not be used — rate-limited / challenge):
 *  - League of Legends: Leaguepedia (lol.fandom.com) Cargo API, table Players
 *  - Valorant: VALORANT Esports Wiki (valorant-esports.fandom.com) Cargo API, table Players
 *  - CS2: bo3.gg public API (players: nickname, names, birthday, country; countries) — CS2 only
 *  - Dota 2, MLBB: no reachable source with birth dates / nationalities (they stay estimates)
 * Fandom wiki content is CC BY-SA 3.0. Requests are spaced (≥ 2 s for the wikis, ≥ 1.2 s for bo3.gg)
 * and cached. Only these public fields are kept (no contact details).
 */
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const UA = 'EsportsManagerOfflineSnapshot/1.0 (personal non-commercial game; offline data snapshot)';
const CACHE = process.argv[2] || '.cache/facts';
fs.mkdirSync(CACHE, { recursive: true });
const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../..');
const rosters = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/data/real/rosters.json'), 'utf8'));
const history = JSON.parse(fs.readFileSync(path.join(ROOT, 'src/data/real/history.json'), 'utf8'));
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const last = {};
function get(url, gap) {
  const f = path.join(CACHE, createHash('sha1').update(url).digest('hex').slice(0, 16));
  if (fs.existsSync(f)) return JSON.parse(fs.readFileSync(f, 'utf8'));
  const host = new URL(url).host;
  const wait = (last[host] ?? 0) + gap - Date.now();
  if (wait > 0) sleep(wait);
  last[host] = Date.now();
  let body;
  try {
    body = execFileSync('curl', ['-sS', '-m', '90', '--compressed', '-A', UA, url], { maxBuffer: 256 << 20 }).toString();
  } catch (e) {
    console.warn('fetch failed', url.slice(0, 120));
    return null;
  }
  let j;
  try {
    j = JSON.parse(body);
  } catch {
    console.warn('not JSON', url.slice(0, 120), body.slice(0, 80));
    return null;
  }
  fs.writeFileSync(f, body);
  return j;
}
const norm = (s) => String(s).toLowerCase().normalize('NFKD').replace(/[^a-z0-9]/g, '');
const handlesOf = (title) => [...new Set([...rosters.teams.filter((t) => t.title_id === title).flatMap((t) => t.roster.filter((p) => p.status !== 'staff').map((p) => p.handle)), ...history.players.filter((p) => p.title_id === title).map((p) => p.handle)])];

const out = { snapshot_id: 'real-2026-11-20', as_of: '2026-11-20', sources: {}, players: {} };

// ------------------------------------------------------------- Fandom Cargo (LoL, Valorant)
function cargo(base, title, handles) {
  const fields = 'ID,Name,NativeName,Country,Nationality,Birthdate,Role,Team';
  let n = 0;
  for (let i = 0; i < handles.length; i += 40) {
    const batch = handles.slice(i, i + 40);
    const where = `ID IN (${batch.map((h) => `"${h.replace(/"/g, '')}"`).join(',')})`;
    const url = `${base}/api.php?action=cargoquery&tables=Players&fields=${encodeURIComponent(fields)}&where=${encodeURIComponent(where)}&limit=500&format=json`;
    const j = get(url, 2100);
    for (const row of j?.cargoquery ?? []) {
      const r = row.title;
      const k = `${title}|${norm(r.ID)}`;
      if (out.players[k]) continue;
      const birth = /^\d{4}-\d{2}-\d{2}$/.test(r.Birthdate ?? '') && r.Birthdate__precision === '1' ? r.Birthdate : undefined;
      const year = /^\d{4}/.test(r.Birthdate ?? '') ? Number(r.Birthdate.slice(0, 4)) : undefined;
      out.players[k] = {
        name: r.Name || undefined,
        country: r.Country || undefined,
        birth,
        ...(birth ? {} : year && year > 1980 ? { birthYear: year } : {}),
        role: r.Role || undefined,
        src: base.replace('https://', ''),
      };
      n++;
    }
  }
  return n;
}
out.sources.lol = { url: 'https://lol.fandom.com', name: 'Leaguepedia (Cargo API)', matched: cargo('https://lol.fandom.com', 'lol', handlesOf('lol')) };
out.sources.valorant = { url: 'https://valorant-esports.fandom.com', name: 'VALORANT Esports Wiki (Cargo API)', matched: cargo('https://valorant-esports.fandom.com', 'valorant', handlesOf('valorant')) };

// ------------------------------------------------------------- bo3.gg (CS2, Dota 2)
{
  const countries = new Map();
  for (let off = 0; off < 300; off += 100) {
    const j = get(`https://api.bo3.gg/api/v1/countries?page%5Blimit%5D=100&page%5Boffset%5D=${off}`, 1200);
    for (const c of j?.results ?? []) countries.set(c.id, c.code);
    if (!j?.results?.length || j.results.length < 100) break;
  }
  const all = [];
  const first = get(`https://api.bo3.gg/api/v1/players?page%5Blimit%5D=100&page%5Boffset%5D=0`, 1200);
  const total = first?.total?.count ?? 0;
  all.push(...(first?.results ?? []));
  for (let off = 100; off < total; off += 100) {
    const j = get(`https://api.bo3.gg/api/v1/players?page%5Blimit%5D=100&page%5Boffset%5D=${off}`, 1200);
    all.push(...(j?.results ?? []));
  }
  // Which discipline id is which game: the one most of our CS2 / Dota 2 handles fall into.
  const byDisc = new Map();
  for (const p of all) {
    if (p.is_deleted || p.is_coach) continue;
    const k = `${p.discipline_id}|${norm(p.nickname)}`;
    if (!byDisc.has(k) || (p.updated_at ?? '') > (byDisc.get(k).updated_at ?? '')) byDisc.set(k, p);
  }
  const discs = [...new Set(all.map((p) => p.discipline_id))];
  // bo3.gg's player list is Counter-Strike only (discipline 1): other titles would only match by
  // coincidence of nicknames, so it is used for CS2 alone.
  for (const title of ['cs2']) {
    const hs = handlesOf(title);
    const scored = discs.map((d) => ({ d, n: hs.filter((h) => byDisc.has(`${d}|${norm(h)}`)).length })).sort((a, b) => b.n - a.n);
    const d = scored[0]?.d;
    let n = 0;
    for (const h of hs) {
      const p = byDisc.get(`${d}|${norm(h)}`);
      if (!p) continue;
      const birth = /^\d{4}-\d{2}-\d{2}$/.test(p.birthday ?? '') ? p.birthday : undefined;
      out.players[`${title}|${norm(h)}`] = {
        name: [p.first_name, p.last_name].filter(Boolean).join(' ') || undefined,
        countryCode: countries.get(p.country_id) || undefined,
        birth,
        role: p.role || undefined,
        src: 'bo3.gg',
      };
      n++;
    }
    out.sources[title] = { url: 'https://bo3.gg', name: `bo3.gg API (discipline ${d})`, matched: n };
  }
}

const count = (t) => Object.keys(out.players).filter((k) => k.startsWith(`${t}|`)).length;
const births = (t) => Object.entries(out.players).filter(([k, v]) => k.startsWith(`${t}|`) && v.birth).length;
for (const t of ['lol', 'valorant', 'cs2']) console.log(t, 'matched', count(t), 'with birth date', births(t), 'of', handlesOf(t).length);
fs.writeFileSync(path.join(ROOT, 'src/data/real/playerFacts.json'), JSON.stringify(out));

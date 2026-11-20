/**
 * Build the offline REAL team/player roster snapshot (development-time only).
 *
 *   node scripts/data/rosters.mjs   → src/data/real/rosters.json
 *
 * MLBB: official MPL Indonesia (id-mpl.com) and MPL Philippines (ph-mpl.com) team pages, Season 18.
 * LoL: Riot LoL Esports gateway (public website key) — leagues, schedules, teams; starters from
 *      the official live-stats feed of each team's most recent league game (feed.lolesports.com).
 * Dota 2: OpenDota team ratings and current team members (/api/teams, /api/teams/{id}/players,
 *      /api/proPlayers for countries and core/support).
 * Other titles are added as sources become available (see DATA_SOURCES.md).
 */
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { get, getJSON, today } from './fetch.mjs';

const KEY = 'x-api-key: 0TvQnueqKa5mxJntVWt0w4LpLfEkrV1Ta8rQBb9Z';
const lol = (path) => {
  const url = `https://esports-api.lolesports.com/persisted/gw/${path}`;
  return JSON.parse(execFileSync('curl', ['-sS', '-m', '90', '--compressed', '-H', KEY, url], { maxBuffer: 512 << 20 }).toString());
};
const pause = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const slug = (s) => s.toLowerCase().normalize('NFKD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const decode = (s) => s.replace(/&amp;/g, '&').replace(/&#039;|&#39;/g, "'").replace(/&quot;/g, '"').trim();
const now = () => new Date().toISOString();

const out = { snapshot_id: `real-${today()}`, as_of: today(), leagues: [], teams: [] };
// Keep the shooter part built by rosters_shooters.mjs (run that one after this one).
const KEEP = ['valorant', 'cs2'];
if (existsSync('src/data/real/rosters.json')) {
  const prev = JSON.parse(readFileSync('src/data/real/rosters.json', 'utf8'));
  out.leagues.push(...prev.leagues.filter((l) => KEEP.includes(l.title_id)));
  out.teams.push(...prev.teams.filter((t) => KEEP.includes(t.title_id)));
}

// ------------------------------------------------------------------------------------------
// MLBB — MPL ID and MPL PH (official sites)
// ------------------------------------------------------------------------------------------
const ML_ROLE = { 'exp lane': 'exp', exp: 'exp', 'gold lane': 'gold', gold: 'gold', 'mid lane': 'mid', midlane: 'mid', mid: 'mid', jungle: 'jungle', jungler: 'jungle', roam: 'roam', roamer: 'roam', roaming: 'roam' };
function mlRole(r) {
  const k = r.toLowerCase().trim();
  if (/coach|analyst|manager/.test(k)) return { status: 'staff', role: k.includes('analyst') ? 'analyst' : 'head-coach' };
  return { status: 'player', role: ML_ROLE[k] ?? null };
}
for (const L of [
  { id: 'mpl-id', name: 'MPL Indonesia', short: 'MPL ID', region: 'ID', country: 'ID', base: 'https://id-mpl.com', season: 'Season 18' },
  { id: 'mpl-ph', name: 'MPL Philippines', short: 'MPL PH', region: 'PH', country: 'PH', base: 'https://ph-mpl.com', season: 'Season 18' },
]) {
  out.leagues.push({ league_id: L.id, title_id: 'mlbb', name: L.name, short: L.short, region: L.region, tier: 1, source_url: `${L.base}/teams`, season: L.season });
  const list = get(`${L.base}/teams`);
  const links = [...new Set([...list.matchAll(new RegExp(`href="(${L.base.replace(/\./g, '\\.')}/team/[^"]+)"`, 'g'))].map((x) => x[1]))];
  for (const url of links) {
    const h = get(url);
    // ph-mpl.com: "<Team> - Mobile Legends…" title; id-mpl.com: the team logo header "<img … class="team-logo" /> ONIC </h4>".
    const name = decode(h.match(/class="team-logo"\s*\/>\s*([^<]+?)\s*<\/h\d>/)?.[1] ?? h.match(/<title>([^<]+?) [-—–] Mobile/)?.[1] ?? url.split('/').pop());
    const people = [];
    // id-mpl.com: visible cards; ph-mpl.com: hidden ign/role inputs.
    for (const m of h.matchAll(/<div class="player-name">\s*([^<]+?)\s*<\/div>\s*<div class="player-role[^"]*">\s*([^<]+?)\s*<\/div>/g)) people.push({ handle: decode(m[1]), roleText: decode(m[2]) });
    const igns = new Map();
    for (const m of h.matchAll(/id="player-(\d+)-(ign|role)" value="([^"]*)"/g)) {
      const e = igns.get(m[1]) ?? {};
      e[m[2]] = decode(m[3]);
      igns.set(m[1], e);
    }
    for (const e of igns.values()) if (e.ign) people.push({ handle: e.ign, roleText: e.role ?? '' });
    const seen = new Set();
    const roster = [];
    for (const p of people) {
      const key = p.handle.toLowerCase();
      if (seen.has(key)) continue;
      seen.add(key);
      const r = mlRole(p.roleText);
      roster.push({ handle: p.handle, real_name: null, nationality: null, nationality_estimate: L.country, role: r.role, status: r.status, role_text: p.roleText });
    }
    out.teams.push({ team_id: `mlbb-${slug(name)}`, title_id: 'mlbb', league_id: L.id, name, short: url.split('/').pop().toUpperCase(), org: name.replace(/\s+(ID|PH|Indonesia|Philippines)$/i, ''), country: L.country, region: L.region, source_url: url, collected_at: now(), roster });
    pause(400);
  }
}

// ------------------------------------------------------------------------------------------
// League of Legends — LoL Esports (official)
// ------------------------------------------------------------------------------------------
const LOL_LEAGUES = { LCK: 'KR', LPL: 'CN', LEC: 'EUW', LCS: 'NA', CBLOL: 'BR', LCP: 'PCS' };
const LOL_ROLE = { top: 'top', jungle: 'jungle', mid: 'mid', bottom: 'bot', support: 'support' };
const leagues = lol('getLeagues?hl=en-US').data.leagues;
const allTeams = lol('getTeams?hl=en-US').data.teams;
for (const [lname, region] of Object.entries(LOL_LEAGUES)) {
  const lg = leagues.find((l) => l.name === lname);
  if (!lg) continue;
  out.leagues.push({ league_id: `lol-${slug(lname)}`, title_id: 'lol', name: lname, short: lname, region, tier: 1, source_url: `https://lolesports.com/en-US/leagues/${lg.slug}`, official_id: lg.id });
  const sched = lol(`getSchedule?hl=en-US&leagueId=${lg.id}`).data.schedule;
  const matches = sched.events.filter((e) => e.type === 'match' && e.state === 'completed').sort((a, b) => b.startTime.localeCompare(a.startTime));
  // Teams of the latest split: those that played in the most recent 60 days of this league.
  const latest = matches[0] ? Date.parse(matches[0].startTime) : 0;
  const recent = matches.filter((e) => latest - Date.parse(e.startTime) < 120 * 86400000);
  const teamIds = [...new Set(recent.flatMap((e) => e.match.teams.map((t) => t.code)))];
  for (const code of teamIds) {
    const last = recent.find((e) => e.match.teams.some((t) => t.code === code));
    const tMeta = last.match.teams.find((t) => t.code === code);
    const full = allTeams.find((t) => t.code === code && t.name === tMeta.name) ?? allTeams.find((t) => t.name === tMeta.name);
    // Starters: the participants of this team's most recent completed game (official feed).
    let starters = [];
    try {
      const det = lol(`getEventDetails?hl=en-US&id=${last.match.id}`).data.event;
      const g = [...det.match.games].reverse().find((x) => x.state === 'completed');
      if (g) {
        const win = getJSON(`https://feed.lolesports.com/livestats/v1/window/${g.id}`);
        const md = win.gameMetadata;
        const side = [md.blueTeamMetadata, md.redTeamMetadata].find((s) => s.esportsTeamId === full?.id) ?? [md.blueTeamMetadata, md.redTeamMetadata].find((s) => s.participantMetadata.some((p) => p.summonerName.startsWith(code + ' ')));
        starters = (side?.participantMetadata ?? []).map((p) => ({ handle: p.summonerName.replace(new RegExp(`^${code}\\s*(?=\\S)`), '').trim(), role: LOL_ROLE[p.role] ?? null, champion: p.championId, esportsPlayerId: p.esportsPlayerId }));
      }
    } catch {
      /* feed unavailable for this game */
    }
    const roster = [];
    const fullPlayers = full?.players ?? [];
    for (const s of starters) {
      const info = fullPlayers.find((p) => p.id === s.esportsPlayerId) ?? fullPlayers.find((p) => p.summonerName.toLowerCase() === s.handle.toLowerCase());
      roster.push({ handle: (info?.summonerName ?? s.handle).trim(), real_name: info ? `${info.firstName ?? ''} ${info.lastName ?? ''}`.trim() || null : null, first_name: info?.firstName?.trim() || null, last_name: info?.lastName?.trim() || null, nationality: null, role: s.role, status: 'player', starter: true, last_champion: s.champion });
    }
    // Series record over the league's latest ~4 months (official schedule results).
    const record = { w: 0, l: 0 };
    for (const e of recent) {
      const me = e.match.teams.find((t) => t.code === code);
      if (me?.result?.outcome === 'win') record.w++;
      else if (me?.result?.outcome === 'loss') record.l++;
    }
    out.teams.push({ record, team_id: `lol-${slug(tMeta.name)}`, title_id: 'lol', league_id: `lol-${slug(lname)}`, name: tMeta.name, short: code, org: tMeta.name.replace(/\s+(Esports|Gaming|e-Sports Club)$/i, ''), country: null, region, source_url: `https://lolesports.com/en-US/leagues/${lg.slug}`, collected_at: now(), roster, starters_from: last.startTime });
    pause(250);
  }
}

// ------------------------------------------------------------------------------------------
// Dota 2 — OpenDota
// ------------------------------------------------------------------------------------------
// Positions are ESTIMATED from the team's most recent parsed pro matches: lane (safe/mid/off) and
// last hits within the lane (higher → core). Safe core = pos1, mid = pos2, off core = pos3,
// off support = pos4, safe support = pos5. Majority over up to three matches.
const pro = getJSON('https://api.opendota.com/api/proPlayers');
const proById = new Map(pro.map((p) => [p.account_id, p]));
const dTeams = getJSON('https://api.opendota.com/api/teams');
const cutoff = Date.now() / 1000 - 120 * 86400;
const active = dTeams.filter((t) => t.last_match_time > cutoff && t.name).sort((a, b) => b.rating - a.rating).slice(0, 30);
out.leagues.push({ league_id: 'dota2-pro', title_id: 'dota2', name: 'Dota 2 professional teams (OpenDota rating)', short: 'Dota Pro', region: null, tier: 1, source_url: 'https://api.opendota.com/api/teams' });
const CLUSTER = getJSON('https://api.opendota.com/api/constants/cluster');
const SERVER = getJSON('https://api.opendota.com/api/constants/region');
// Server region names → coarse competitive regions (estimate: where the team plays most online matches).
function serverRegion(name) {
  const n = (name ?? '').toUpperCase();
  if (n.startsWith('PW') || n.includes('CHINA')) return 'CN';
  if (/US |US$|NORTH AMERICA/.test(n + ' ')) return 'NA';
  if (/BRAZIL|CHILE|PERU|ARGENTINA|SOUTH AMERICA/.test(n)) return 'SA';
  if (/SINGAPORE|AUSTRALIA|JAPAN|TAIWAN|INDIA|SEA|SOUTHEAST/.test(n)) return 'SEA';
  if (/EUROPE|STOCKHOLM|AUSTRIA|RUSSIA|DUBAI|SOUTHAFRICA/.test(n)) return 'EU';
  return null;
}
function dotaRegion(matches) {
  const n = new Map();
  for (const m of matches) {
    const r = serverRegion(SERVER[CLUSTER[m.cluster]]);
    if (r) n.set(r, (n.get(r) ?? 0) + 1);
  }
  return [...n].sort((a, b) => b[1] - a[1])[0]?.[0] ?? null;
}
function dotaPositions(teamId, members) {
  const votes = new Map(); // account → Map(pos → n)
  let order = [];
  let latest = 0;
  let used = 0;
  const recentAll = getJSON(`https://api.opendota.com/api/teams/${teamId}/matches`)
    .filter((m) => m.start_time > cutoff)
    .sort((a, b) => b.start_time - a.start_time);
  const region = dotaRegion(recentAll);
  const ms = recentAll.slice(0, 6);
  for (const mm of ms) {
    if (used >= 3) break;
    pause(1100);
    let m;
    try {
      m = getJSON(`https://api.opendota.com/api/matches/${mm.match_id}`);
    } catch {
      continue;
    }
    const side = (m.players ?? []).filter((p) => p.isRadiant === mm.radiant);
    if (side.length !== 5 || side.some((p) => p.lane_role == null)) continue;
    used++;
    if (!order.length) {
      order = side.map((p) => p.account_id);
      latest = mm.start_time;
    }
    for (const lane of [1, 2, 3]) {
      const inLane = side.filter((p) => p.lane_role === lane).sort((a, b) => (b.last_hits ?? 0) - (a.last_hits ?? 0));
      inLane.forEach((p, k) => {
        const pos = lane === 2 ? (k === 0 ? 2 : 4) : lane === 1 ? (k === 0 ? 1 : 5) : k === 0 ? 3 : 4;
        const v = votes.get(p.account_id) ?? new Map();
        v.set(pos, (v.get(pos) ?? 0) + 1);
        votes.set(p.account_id, v);
      });
    }
  }
  // Assign distinct positions to the latest five by best total votes (5! permutations).
  const ids = order.filter((id) => members.has(id));
  if (order.length !== 5) return { ids, pos: new Map(), latest, region };
  let best = null;
  let bestScore = -1;
  const perm = (arr, k = 0) => {
    if (k === arr.length) {
      const sc = arr.reduce((a, pos, n) => a + (votes.get(order[n])?.get(pos) ?? 0), 0);
      if (sc > bestScore) (bestScore = sc), (best = [...arr]);
      return;
    }
    for (let n = k; n < arr.length; n++) {
      [arr[k], arr[n]] = [arr[n], arr[k]];
      perm(arr, k + 1);
      [arr[k], arr[n]] = [arr[n], arr[k]];
    }
  };
  perm([1, 2, 3, 4, 5]);
  return { ids, pos: new Map(order.map((id, n) => [id, best[n]])), latest, region };
}
// One entry per team name (highest rating), then players go to the team they appeared for most recently.
const byName = new Map();
for (const t of active) if (!byName.has(t.name.trim().toLowerCase())) byName.set(t.name.trim().toLowerCase(), t);
const cand = [];
for (const t of byName.values()) {
  const members = getJSON(`https://api.opendota.com/api/teams/${t.team_id}/players`).filter((p) => p.is_current_team_member);
  pause(1100);
  let est = { ids: [], pos: new Map(), latest: 0 };
  try {
    est = dotaPositions(t.team_id, new Set(members.map((p) => p.account_id)));
  } catch {
    /* positions unknown */
  }
  if (est.ids.length >= 4) cand.push({ t, members, est });
}
const claim = new Map(); // account → latest appearance time
for (const c of cand) for (const id of c.est.ids) claim.set(id, Math.max(claim.get(id) ?? 0, c.est.latest));
for (const { t, members, est } of cand) {
  const starters = est.ids.filter((id) => claim.get(id) === est.latest);
  if (starters.length < 4) continue;
  members.sort((a, b) => b.games_played - a.games_played);
  const extra = members.map((p) => p.account_id).filter((id) => !starters.includes(id) && !claim.has(id));
  const ids = [...starters, ...extra].slice(0, 6);
  const roster = ids.map((id) => {
    const pp = proById.get(id);
    const mem = members.find((p) => p.account_id === id);
    const pos = starters.includes(id) ? est.pos.get(id) : null;
    // Steam-profile country codes are self-reported and unreliable, so nationality stays unknown.
    return { handle: (pp?.name ?? mem?.name ?? `player-${id}`).trim(), real_name: null, nationality: null, role: pos ? `pos${pos}` : null, role_source: pos ? 'estimate: lanes & last hits in recent pro matches' : null, status: 'player', starter: starters.includes(id), account_id: id };
  });
  if (roster.length < 5) continue;
  out.teams.push({ team_id: `dota2-${slug(t.name)}`, title_id: 'dota2', league_id: 'dota2-pro', name: t.name.trim(), short: t.tag || t.name.slice(0, 4).toUpperCase(), org: t.name.trim(), country: null, region: est.region, region_source: 'estimate: game-server region of recent matches', rating: Math.round(t.rating), last_match: new Date(est.latest * 1000).toISOString().slice(0, 10), source_url: `https://www.opendota.com/teams/${t.team_id}`, collected_at: now(), roster });
}

for (const L of out.leagues) console.log(L.title_id, L.short, out.teams.filter((t) => t.league_id === L.league_id).length, 'teams');
console.log('players', out.teams.reduce((a, t) => a + t.roster.filter((r) => r.status === 'player').length, 0));
mkdirSync('src/data/real', { recursive: true });
writeFileSync('src/data/real/rosters.json', JSON.stringify(out));

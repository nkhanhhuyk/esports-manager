/**
 * Build the offline snapshot of PAST real teams and players (development-time only).
 *
 *   node scripts/data/history.mjs <clone of ValveSoftware/counter-strike_regional_standings>
 *     → src/data/real/history.json
 *
 * Old teams = teams that played in the covered top leagues/rankings in earlier seasons but are not in the
 * current snapshot (rosters.json). Old players = players seen on those leagues' rosters in earlier seasons who
 * are on no current roster: "free agent" when last seen recently, "retired" (an ESTIMATE, always labelled)
 * when not seen at the top level for a long time. Sources:
 *  - LoL: LoL Esports gateway — league schedules (2024–2026), one game per team per season via the official
 *    live-stats feed (participants), archived teams with their last listed rosters.
 *  - Valorant: vlr.gg — VCT league events 2023–2026, one match per team per season (players).
 *  - CS2: Valve Regional Standings — monthly global standings 2024–2026 (top 60 teams with rosters).
 *  - Dota 2: OpenDota — top-rated teams of the last three years and their all-time members.
 *  - MLBB: MPL Indonesia / MPL Philippines official team pages of earlier seasons.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { get, getJSON, today } from './fetch.mjs';

const VRS = process.argv[2];
const cur = JSON.parse(readFileSync('src/data/real/rosters.json', 'utf8'));
const now = () => new Date().toISOString();
const pause = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const slug = (s) => s.toLowerCase().normalize('NFKD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const text = (s) => s.replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').replace(/&#039;|&#39;/g, "'").replace(/&quot;/g, '"').replace(/\s+/g, ' ').trim();
const norm = (s) => s.toLowerCase().replace(/[^a-z0-9]/g, '');

const out = { snapshot_id: `real-${today()}`, as_of: today(), teams: [], players: [] };
const currentTeams = (title) => new Set(cur.teams.filter((t) => t.title_id === title).map((t) => norm(t.name)));
const currentHandles = (title) => new Set(cur.teams.filter((t) => t.title_id === title).flatMap((t) => t.roster.filter((p) => p.status === 'player').map((p) => norm(p.handle))));

/** Collector for one title. */
function collector(title) {
  const teams = new Map(); // norm name → team
  const players = new Map(); // norm handle → player
  const curT = currentTeams(title);
  const curH = currentHandles(title);
  return {
    team(name, data) {
      const k = norm(name);
      if (!k || curT.has(k)) return null;
      const t = teams.get(k) ?? { title_id: title, team_id: `${title}-${slug(name)}`, name, short: data.short ?? name.slice(0, 4).toUpperCase(), region: data.region ?? null, league: data.league ?? null, last_season: 0, source_url: data.source_url, roster: [] };
      if ((data.season ?? 0) >= t.last_season) {
        t.last_season = data.season ?? t.last_season;
        t.league = data.league ?? t.league;
        if (data.short) t.short = data.short;
      }
      teams.set(k, t);
      return t;
    },
    player(handle, data) {
      const k = norm(handle);
      if (!k || curH.has(k)) return;
      const p = players.get(k) ?? { title_id: title, handle, real_name: null, first_name: null, last_name: null, nationality: null, role: null, last_team: null, last_season: 0, source_url: data.source_url };
      if ((data.season ?? 0) >= p.last_season) {
        p.last_season = data.season ?? p.last_season;
        p.last_team = data.team ?? p.last_team;
        if (data.role) p.role = data.role;
        p.source_url = data.source_url ?? p.source_url;
      }
      for (const f of ['real_name', 'first_name', 'last_name', 'nationality']) if (data[f] && !p[f]) p[f] = data[f];
      players.set(k, p);
      // Old teams list the players seen with them.
      if (data.team) {
        const t = teams.get(norm(data.team));
        if (t && !t.roster.includes(handle) && (data.season ?? 0) >= t.last_season) t.roster.push(handle);
      }
    },
    finish(freeAgentFrom, rule) {
      for (const p of players.values()) {
        const fa = p.last_season >= freeAgentFrom;
        p.status = fa ? 'free-agent' : 'retired';
        p.status_source = fa ? `estimate: last seen ${p.last_season || 'unknown'} at the top level, on no current roster` : `estimate: ${rule.replace('{y}', p.last_season || 'an earlier season')}`;
        out.players.push(p);
      }
      for (const t of teams.values()) out.teams.push(t);
      console.log(title, 'old teams', teams.size, 'old players', players.size, 'FA', [...players.values()].filter((p) => p.status === 'free-agent').length);
    },
  };
}

// ------------------------------------------------------------------------------------------
// League of Legends
// ------------------------------------------------------------------------------------------
{
  const KEY = 'x-api-key: 0TvQnueqKa5mxJntVWt0w4LpLfEkrV1Ta8rQBb9Z';
  const lol = (path) => JSON.parse(execFileSync('curl', ['-sS', '-m', '90', '--compressed', '-H', KEY, `https://esports-api.lolesports.com/persisted/gw/${path}`], { maxBuffer: 512 << 20 }).toString());
  const C = collector('lol');
  const LEAGUES = { LCK: 'KR', LPL: 'CN', LEC: 'EUW', LCS: 'NA', CBLOL: 'BR', LCP: 'PCS', PCS: 'PCS', VCS: 'VN', LJL: 'JP', LLA: 'LATN', 'LTA North': 'NA', 'LTA South': 'BR' };
  const ROLE = { top: 'top', jungle: 'jungle', mid: 'mid', bottom: 'bot', support: 'support' };
  const leagues = lol('getLeagues?hl=en-US').data.leagues;
  const allTeams = lol('getTeams?hl=en-US').data.teams;
  const playerInfo = new Map();
  for (const t of allTeams) for (const p of t.players) playerInfo.set(p.id, p);
  for (const [lname, region] of Object.entries(LEAGUES)) {
    const lg = leagues.find((l) => l.name === lname);
    if (!lg) continue;
    let tok = null;
    const events = [];
    for (let i = 0; i < 12; i++) {
      const s = lol(`getSchedule?hl=en-US&leagueId=${lg.id}${tok ? `&pageToken=${encodeURIComponent(tok)}` : ''}`).data.schedule;
      events.push(...s.events.filter((e) => e.type === 'match' && e.state === 'completed'));
      tok = s.pages.older;
      pause(200);
      if (!tok) break;
    }
    // Latest completed match per team per year.
    const latest = new Map();
    for (const e of events) {
      const year = Number(e.startTime.slice(0, 4));
      for (const tm of e.match.teams) {
        const k = `${tm.name}|${year}`;
        const prev = latest.get(k);
        if (!prev || prev.startTime < e.startTime) latest.set(k, e);
      }
    }
    for (const [k, e] of latest) {
      const [name, y] = k.split('|');
      const year = Number(y);
      const tm = e.match.teams.find((x) => x.name === name);
      const team = C.team(name, { short: tm.code, region, league: lname, season: year, source_url: `https://lolesports.com/en-US/leagues/${lg.slug}` });
      const full = allTeams.find((t) => t.name === name);
      // Line-up of that game from the official live-stats feed.
      let parts = [];
      try {
        const det = lol(`getEventDetails?hl=en-US&id=${e.match.id}`).data.event;
        const g = [...det.match.games].reverse().find((x) => x.state === 'completed');
        if (g) {
          const win = getJSON(`https://feed.lolesports.com/livestats/v1/window/${g.id}`);
          const md = win.gameMetadata;
          const side = [md.blueTeamMetadata, md.redTeamMetadata].find((s) => s.esportsTeamId === full?.id) ?? [md.blueTeamMetadata, md.redTeamMetadata].find((s) => s.participantMetadata.some((p) => p.summonerName.startsWith(`${tm.code} `)));
          parts = side?.participantMetadata ?? [];
        }
      } catch {
        /* feed unavailable */
      }
      pause(150);
      for (const p of parts) {
        const info = playerInfo.get(p.esportsPlayerId);
        const handle = (info?.summonerName ?? p.summonerName.replace(new RegExp(`^${tm.code}\\s*(?=\\S)`), '')).trim();
        C.player(handle, { team: name, season: year, role: ROLE[p.role], first_name: info?.firstName?.trim(), last_name: info?.lastName?.trim(), source_url: `https://lolesports.com/en-US/leagues/${lg.slug}` });
      }
      void team;
    }
    console.log('lol', lname, events.length, 'matches');
  }
  // Archived teams of these leagues with their last listed rosters (season unknown → retired estimate).
  const leagueNames = new Set(Object.keys(LEAGUES));
  for (const t of allTeams) {
    if (t.status !== 'archived' || !t.players.length || !leagueNames.has(t.homeLeague?.name)) continue;
    C.team(t.name, { short: t.code, region: LEAGUES[t.homeLeague.name], league: t.homeLeague.name, season: 0, source_url: 'https://lolesports.com' });
    for (const p of t.players) C.player(p.summonerName, { team: t.name, season: 0, role: ROLE[p.role], first_name: p.firstName?.trim(), last_name: p.lastName?.trim(), source_url: 'https://lolesports.com' });
  }
  C.finish(2025, 'no top-league game in the official schedules since {y}');
}

// ------------------------------------------------------------------------------------------
// Valorant (vlr.gg)
// ------------------------------------------------------------------------------------------
{
  const C = collector('valorant');
  const VLR = 'https://www.vlr.gg';
  const REG = { americas: 'AMER', emea: 'EMEA', pacific: 'APN', china: 'CN' };
  for (const year of [2023, 2024, 2025, 2026]) {
    const hub = get(`${VLR}/vct-${year}`);
    const events = [...new Set([...hub.matchAll(/href="(\/event\/\d+\/vct-\d+-(americas|emea|pacific|china)-(league|kickoff|stage-1|stage-2)[^"]*)"/g)].map((m) => m[1]))];
    pause(800);
    for (const ev of events) {
      const region = REG[ev.match(/vct-\d+-(americas|emea|pacific|china)/)[1]];
      const id = ev.match(/\/event\/(\d+)\//)[1];
      const mp = get(`${VLR}/event/matches/${id}/?series_id=all`);
      pause(800);
      // Last listed match per team: match links with their two team names.
      const matches = [...mp.matchAll(/href="(\/\d+\/[^"]+)"[^>]*class="[^"]*match-item[^"]*"[\s\S]*?<div class="match-item-vs-team-name">\s*<div class="text-of">([\s\S]*?)<\/div>[\s\S]*?<div class="match-item-vs-team-name">\s*<div class="text-of">([\s\S]*?)<\/div>/g)].map((m) => ({ url: m[1], a: text(m[2]), b: text(m[3]) }));
      const lastFor = new Map();
      for (const m of matches) for (const n of [m.a, m.b]) if (n && n !== 'TBD') lastFor.set(n, m.url);
      const done = new Set();
      for (const [team, url] of lastFor) {
        if (done.has(url)) continue;
        done.add(url);
        const h = get(`${VLR}${url}`);
        pause(800);
        // Players: the overview's first ten rows (five per team, in header order).
        const names = [...h.matchAll(/<div class="wf-title-med[^"]*">([\s\S]*?)<\/div>/g)].map((m) => text(m[1])).slice(0, 2);
        const rows = [...h.matchAll(/<div class="ovw-player">\s*<i class="flag mod-([a-z]{2})"[^>]*><\/i>\s*<a href="\/player\/\d+\/[^"]+">\s*<div class="ovw-player-name text-of">([^<]+)<\/div>/g)].slice(0, 10);
        rows.forEach((m, i) => {
          const tn = names[i < 5 ? 0 : 1];
          if (!tn) return;
          if (i === 0 || i === 5) C.team(tn, { region, league: `VCT ${year}`, season: year, source_url: `${VLR}${url}` });
          C.player(m[2].trim(), { team: tn, season: year, nationality: m[1] === 'un' ? null : m[1].toUpperCase(), source_url: `${VLR}${url}` });
        });
      }
      console.log('valorant', ev, lastFor.size, 'teams');
    }
  }
  C.finish(2025, 'no VCT match since {y}');
}

// ------------------------------------------------------------------------------------------
// CS2 (Valve Regional Standings)
// ------------------------------------------------------------------------------------------
if (VRS && existsSync(VRS)) {
  const C = collector('cs2');
  for (const year of [2024, 2025, 2026]) {
    const dir = join(VRS, 'live', String(year));
    if (!existsSync(dir)) continue;
    const files = readdirSync(dir).filter((f) => /^standings_global_\d{4}_\d\d_\d\d\.md$/.test(f)).sort();
    for (const f of files) {
      const date = f.match(/(\d{4})_(\d\d)_(\d\d)/).slice(1).join('-');
      const season = Number(date.slice(0, 4)) + (Number(date.slice(5, 7)) >= 7 ? 0.5 : 0);
      const rows = readFileSync(join(dir, f), 'utf8').split('\n').filter((l) => /^\|\s*\d+\s*\|/.test(l)).slice(0, 60);
      for (const row of rows) {
        const [, , , name, rosterTxt] = row.split('|').map((c) => c.trim());
        C.team(name, { season, league: `VRS top 60 (${date})`, source_url: `https://github.com/ValveSoftware/counter-strike_regional_standings/blob/main/live/${year}/${f}` });
        for (const h of rosterTxt.split(',').map((s) => s.trim()).filter(Boolean)) C.player(h, { team: name, season, source_url: `https://github.com/ValveSoftware/counter-strike_regional_standings/blob/main/live/${year}/${f}` });
      }
    }
  }
  C.finish(2025.5, 'not on a top-60 ranked roster since {y}');
}

// ------------------------------------------------------------------------------------------
// Dota 2 (OpenDota)
// ------------------------------------------------------------------------------------------
{
  const C = collector('dota2');
  const pro = getJSON('https://api.opendota.com/api/proPlayers');
  const proById = new Map(pro.map((p) => [p.account_id, p]));
  const teams = getJSON('https://api.opendota.com/api/teams');
  const since = Date.now() / 1000 - 3 * 365 * 86400;
  const curIds = new Set(cur.teams.filter((t) => t.title_id === 'dota2').map((t) => Number(t.source_url.split('/').pop())));
  const pool = teams.filter((t) => t.name && t.last_match_time > since).sort((a, b) => b.rating - a.rating).slice(0, 70);
  const lastActive = new Map(); // account → [time, team]
  for (const t of pool) {
    const year = new Date(t.last_match_time * 1000).getUTCFullYear() + (new Date(t.last_match_time * 1000).getUTCMonth() >= 6 ? 0.5 : 0);
    if (!curIds.has(t.team_id)) C.team(t.name.trim(), { short: t.tag || undefined, season: year, league: 'OpenDota rated teams', source_url: `https://www.opendota.com/teams/${t.team_id}` });
    const members = getJSON(`https://api.opendota.com/api/teams/${t.team_id}/players`).filter((m) => m.games_played >= 20);
    pause(1100);
    for (const m of members) {
      // A former member's last time with this team is at most the team's last match.
      const when = m.is_current_team_member ? t.last_match_time : Math.min(t.last_match_time, t.last_match_time - 1);
      const prev = lastActive.get(m.account_id);
      if (!prev || prev[0] < when) lastActive.set(m.account_id, [when, t.name.trim()]);
    }
  }
  for (const [acc, [when, team]] of lastActive) {
    const pp = proById.get(acc);
    const name = (pp?.name ?? '').trim();
    if (!name) continue;
    const d = new Date(when * 1000);
    C.player(name, { team, season: d.getUTCFullYear() + (d.getUTCMonth() >= 6 ? 0.5 : 0), role: null, source_url: `https://www.opendota.com/players/${acc}` });
  }
  C.finish(2025.5, 'no rated-team match since {y}');
}

// ------------------------------------------------------------------------------------------
// MLBB (official MPL sites, earlier seasons)
// ------------------------------------------------------------------------------------------
{
  const C = collector('mlbb');
  const ML_ROLE = { 'exp lane': 'exp', exp: 'exp', 'gold lane': 'gold', gold: 'gold', 'mid lane': 'mid', midlane: 'mid', mid: 'mid', jungle: 'jungle', jungler: 'jungle', roam: 'roam', roamer: 'roam' };
  // MPL ID season n ≈ year: S13 2024 H1 … S17 2026 H1; MPL PH similar numbering minus one.
  for (const L of [
    { base: 'https://id-mpl.com', seasons: [13, 14, 15, 16, 17], region: 'ID', name: 'MPL Indonesia', year: (s) => 2017.5 + s / 2 },
    { base: 'https://ph-mpl.com', seasons: [12, 13, 14, 15, 16], region: 'PH', name: 'MPL Philippines', year: (s) => 2018 + s / 2 },
  ]) {
    for (const sn of L.seasons) {
      const list = get(`${L.base}/s${sn}/teams`);
      pause(500);
      const links = [...new Set([...list.matchAll(new RegExp(`href="(${L.base.replace(/\./g, '\\.')}/s${sn}/team/[^"]+)"`, 'g'))].map((x) => x[1]))];
      for (const url of links) {
        const h = get(url);
        pause(400);
        const name = text(h.match(/class="team-logo"\s*\/>\s*([^<]+?)\s*<\/h\d>/)?.[1] ?? h.match(/<title>([^<]+?) [-—–] Mobile/)?.[1] ?? url.split('/').pop());
        const season = L.year(sn);
        C.team(name, { short: url.split('/').pop().toUpperCase(), region: L.region, league: `${L.name} Season ${sn}`, season, source_url: url });
        const people = [];
        for (const m of h.matchAll(/<div class="player-name">\s*([^<]+?)\s*<\/div>\s*<div class="player-role[^"]*">\s*([^<]+?)\s*<\/div>/g)) people.push([text(m[1]), text(m[2])]);
        const igns = new Map();
        for (const m of h.matchAll(/id="player-(\d+)-(ign|role)" value="([^"]*)"/g)) {
          const e = igns.get(m[1]) ?? {};
          e[m[2]] = text(m[3]);
          igns.set(m[1], e);
        }
        for (const e of igns.values()) if (e.ign) people.push([e.ign, e.role ?? '']);
        for (const [handle, roleText] of people) {
          if (/coach|analyst|manager/i.test(roleText)) continue;
          C.player(handle, { team: name, season, role: ML_ROLE[roleText.toLowerCase()] ?? null, nationality: L.region, source_url: url });
        }
      }
      console.log('mlbb', L.name, sn, links.length, 'teams');
    }
  }
  C.finish(2025.5, 'not on an MPL ID/PH roster since {y}');
}

writeFileSync('src/data/real/history.json', JSON.stringify(out));
console.log('teams', out.teams.length, 'players', out.players.length, 'retired', out.players.filter((p) => p.status === 'retired').length);

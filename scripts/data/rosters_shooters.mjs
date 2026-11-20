/**
 * Valorant and Counter-Strike 2 parts of the offline roster snapshot (development-time only).
 *
 *   node scripts/data/rosters_shooters.mjs [path/to/counter-strike_regional_standings]
 *     → merges into src/data/real/rosters.json
 *
 * Valorant: VCT 2026 partner leagues (Americas, EMEA, Pacific, China) — participating teams from the
 *   vlr.gg event pages (Kickoff ∪ Stage 1 ∪ Stage 2), current rosters from vlr.gg team pages; player
 *   roles ESTIMATED from each player's agents over the last 90 days (agent roles: valorant-api.com).
 * CS2: Valve's official Regional Standings (github.com/ValveSoftware/counter-strike_regional_standings),
 *   latest regional files: team names, rank points and the five-player rosters. CS2 in-team roles are
 *   not published there and are left unknown (the game assigns them and labels them as assignments).
 */
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { get, today } from './fetch.mjs';

const OUT = 'src/data/real/rosters.json';
const out = existsSync(OUT) ? JSON.parse(readFileSync(OUT, 'utf8')) : { snapshot_id: `real-${today()}`, as_of: today(), leagues: [], teams: [] };
const now = () => new Date().toISOString();
const pause = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const slug = (s) => s.toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
const text = (s) => s.replace(/<[^>]+>/g, '').replace(/&amp;/g, '&').replace(/&#039;|&#39;/g, "'").replace(/&quot;/g, '"').replace(/\s+/g, ' ').trim();
const drop = (title) => {
  out.leagues = out.leagues.filter((l) => l.title_id !== title);
  out.teams = out.teams.filter((t) => t.title_id !== title);
};

// ------------------------------------------------------------------------------------------
// Valorant
// ------------------------------------------------------------------------------------------
{
  drop('valorant');
  const chars = JSON.parse(readFileSync('src/data/real/characters.json', 'utf8')).titles.valorant.characters;
  const agentRole = new Map(chars.map((c) => [c.name.toLowerCase().replace(/[^a-z0-9]/g, ''), c.classes[0].toLowerCase()]));
  const VLR = 'https://www.vlr.gg';
  const LEAGUES = [
    { id: 'vct-americas', name: 'VCT Americas', region: 'AMER', events: ['2682/vct-2026-americas-kickoff', '2860/vct-2026-americas-stage-1', '2977/vct-2026-americas-stage-2'] },
    { id: 'vct-emea', name: 'VCT EMEA', region: 'EMEA', events: ['2684/vct-2026-emea-kickoff', '2863/vct-2026-emea-stage-1', '2976/vct-2026-emea-stage-2'] },
    { id: 'vct-pacific', name: 'VCT Pacific', region: 'APN', events: ['2683/vct-2026-pacific-kickoff', '2775/vct-2026-pacific-stage-1', '2776/vct-2026-pacific-stage-2'] },
    { id: 'vct-china', name: 'VCT China', region: 'CN', events: ['2685/vct-2026-china-kickoff', '2864/vct-2026-china-stage-1', '2978/vct-2026-china-stage-2'] },
  ];
  const seenHandles = new Set();
  for (const L of LEAGUES) {
    out.leagues.push({ league_id: L.id, title_id: 'valorant', name: L.name, short: L.name, region: L.region, tier: 1, season: 'VCT 2026', source_url: `${VLR}/event/${L.events[0]}` });
    const teams = new Set();
    for (const e of L.events) {
      for (const m of get(`${VLR}/event/${e}`).matchAll(/class="event-team-name" href="(\/team\/\d+\/[^"]+)"/g)) teams.add(m[1]);
      for (const m of get(`${VLR}/event/${e}`).matchAll(/href="(\/team\/\d+\/[^"]+)"/g)) teams.add(m[1]);
      pause(800);
    }
    for (const path of teams) {
      const h = get(`${VLR}${path}`);
      pause(800);
      const name = text(h.match(/<h1 class="wf-title"[^>]*>([\s\S]*?)<\/h1>/)?.[1] ?? path.split('/').pop());
      const tag = text(h.match(/class="[^"]*team-header-tag"[^>]*>([^<]*)</)?.[1] ?? '');
      const country = h.match(/team-header-country">\s*<i class="flag mod-([a-z]{2})"/)?.[1]?.toUpperCase() ?? null;
      const roster = [];
      for (const it of h.split('class="team-roster-item"').slice(1)) {
        const handle = text(it.match(/team-roster-item-name-alias">([\s\S]*?)<\/div>/)?.[1] ?? '');
        if (!handle) continue;
        const flag = it.match(/flag mod-([a-z]{2})/)?.[1]?.toUpperCase() ?? null;
        const label = text(it.match(/team-roster-item-name-role">([\s\S]*?)<\/div>/)?.[1] ?? '').toLowerCase();
        const link = it.match(/href="(\/player\/\d+\/[^"]+)"/)?.[1];
        const staff = /coach|manager|analyst|performance|director|ceo|owner|founder|scout/.test(label);
        if (staff) {
          roster.push({ handle, real_name: null, nationality: flag, role: label.includes('head coach') ? 'head-coach' : label.includes('coach') ? 'coach' : 'staff', status: 'staff', role_text: label });
          continue;
        }
        if (/inactive|stand-?in/.test(label)) continue;
        // Role estimate from agents played over the last 90 days.
        let role = null;
        let share = 0;
        if (link) {
          const ph = get(`${VLR}${link}?timespan=90d`);
          pause(800);
          const i = ph.indexOf('Agents');
          const tb = i >= 0 ? ph.slice(ph.indexOf('<table', i), ph.indexOf('</table>', i)) : '';
          const byRole = new Map();
          let total = 0;
          for (const row of tb.split('<tr').slice(2)) {
            const ag = row.match(/agents\/([a-z0-9]+)\.png/)?.[1];
            const n = Number(row.match(/\((\d+)\)/)?.[1] ?? 0);
            const r = ag && agentRole.get(ag);
            if (!r || !n) continue;
            byRole.set(r, (byRole.get(r) ?? 0) + n);
            total += n;
          }
          const top = [...byRole].sort((a, b) => b[1] - a[1])[0];
          if (top && total >= 4) {
            share = top[1] / total;
            role = share >= 0.55 ? top[0] : 'flex';
          }
        }
        roster.push({ handle, real_name: null, nationality: flag, role, role_source: role ? 'estimate: agents played (last 90 days)' : null, role_share: share ? Math.round(share * 100) / 100 : null, status: 'player', starter: !label.includes('sub'), role_text: label || null });
      }
      // Off-season rosters can be incomplete; keep teams with at least three listed players (the game
      // fills the open slots with fictional players). A player listed by two teams stays with the first.
      for (let i = roster.length - 1; i >= 0; i--) if (roster[i].status === 'player' && seenHandles.has(roster[i].handle.toLowerCase())) roster.splice(i, 1);
      if (roster.filter((r) => r.status === 'player').length < 3) continue;
      for (const r of roster) if (r.status === 'player') seenHandles.add(r.handle.toLowerCase());
      out.teams.push({ team_id: `valorant-${slug(name)}`, title_id: 'valorant', league_id: L.id, name, short: tag || name.slice(0, 4).toUpperCase(), org: name, country, region: L.region, source_url: `${VLR}${path}`, collected_at: now(), roster });
    }
  }
}

// ------------------------------------------------------------------------------------------
// Counter-Strike 2 — Valve Regional Standings (official)
// ------------------------------------------------------------------------------------------
{
  const repo = process.argv[2] ?? process.env.VRS_REPO;
  if (repo && existsSync(repo)) {
    drop('cs2');
    const dir = join(repo, 'live', String(new Date().getUTCFullYear()));
    const files = readdirSync(dir).filter((f) => /^standings_(europe|americas|asia)_\d{4}_\d\d_\d\d\.md$/.test(f)).sort();
    const latest = (region) => files.filter((f) => f.includes(`_${region}_`)).pop();
    const TAKE = { europe: 24, americas: 12, asia: 10 };
    const REG = { europe: 'EMEA', americas: 'AMER', asia: 'APS' };
    for (const region of Object.keys(TAKE)) {
      const f = latest(region);
      if (!f) continue;
      const date = f.match(/(\d{4})_(\d\d)_(\d\d)/).slice(1).join('-');
      const lid = `cs2-vrs-${region}`;
      out.leagues.push({ league_id: lid, title_id: 'cs2', name: `Valve Regional Standings — ${region[0].toUpperCase()}${region.slice(1)}`, short: `VRS ${region[0].toUpperCase()}${region.slice(1)}`, region: REG[region], tier: 1, season: `as of ${date}`, source_url: `https://github.com/ValveSoftware/counter-strike_regional_standings/blob/main/live/${dir.split('/').pop()}/${f}` });
      const rows = readFileSync(join(dir, f), 'utf8').split('\n').filter((l) => /^\|\s*\d+\s*\|/.test(l));
      for (const row of rows.slice(0, TAKE[region])) {
        const cells = row.split('|').map((c) => c.trim());
        const [, rank, points, name, rosterTxt] = cells;
        const handles = rosterTxt.split(',').map((s) => s.trim()).filter(Boolean);
        if (handles.length < 5) continue;
        out.teams.push({
          team_id: `cs2-${slug(name)}`,
          title_id: 'cs2',
          league_id: lid,
          name,
          short: name.length <= 5 ? name.toUpperCase() : name.replace(/[^A-Za-z0-9]/g, '').slice(0, 4).toUpperCase(),
          org: name,
          country: null,
          region: REG[region],
          vrs_rank: Number(rank),
          vrs_points: Number(points),
          source_url: `https://github.com/ValveSoftware/counter-strike_regional_standings/blob/main/live/${dir.split('/').pop()}/${f}`,
          collected_at: now(),
          roster: handles.map((h) => ({ handle: h, real_name: null, nationality: null, role: null, role_source: null, status: 'player', starter: true })),
        });
      }
    }
  } else console.log('CS2 skipped: pass the path of a clone of ValveSoftware/counter-strike_regional_standings');
}

for (const L of out.leagues.filter((l) => l.title_id === 'valorant' || l.title_id === 'cs2')) console.log(L.title_id, L.short, out.teams.filter((t) => t.league_id === L.league_id).length, 'teams');
writeFileSync(OUT, JSON.stringify(out));

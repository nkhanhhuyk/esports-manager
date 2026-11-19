/**
 * Seven-title scope conversion for saves created by earlier, wider versions of the game.
 *
 * Saves from before the seven-title edition can contain leagues, teams and players of titles
 * that are no longer part of the game. Loading such a save (the persistence layer first keeps
 * the untouched original as an exportable backup):
 *   - deletes the removed titles' leagues, competitions, fixtures, match records, draft picks,
 *     negotiations and offers, and their divisions (teams) in every organisation;
 *   - keeps every person: players and staff of removed titles keep their identity, attributes,
 *     input proficiencies, contracts history and records, and move to the closest allowed title
 *     with low familiarity (players become free agents, the title switch is in their career);
 *   - keeps history: past stats, awards and trophies stay under the original title IDs, which
 *     display as read-only "(archived)" labels;
 *   - explains the conversion in the inbox. A save that contained only removed titles cannot be
 *     converted; the caller reports that and the backup remains exportable.
 */
import { getTitle, isPlayableTitle, TITLE_BY_ID } from '../data/titles/catalogue';
import type { Player, World } from '../model/types';
import { bestRole, effectivenessLight } from '../sim/common/ratings';
import { addInbox } from '../systems/news';

export const ALLOWED_TITLE_IDS = ['mlbb', 'wildrift', 'hok', 'lol', 'dota2', 'valorant', 'cs2'] as const;

/** Where people of a removed title go, and how much title knowledge carries over (proposal). */
export const REMOVED_TITLE_MAP: Record<string, { to: string; sim: number }> = {
  vainglory: { to: 'mlbb', sim: 0.75 },
  aov: { to: 'hok', sim: 0.9 },
  msw: { to: 'mlbb', sim: 0.7 },
  brawlstars: { to: 'mlbb', sim: 0.3 },
  unite: { to: 'wildrift', sim: 0.4 },
  hots: { to: 'dota2', sim: 0.55 },
  smite2: { to: 'lol', sim: 0.5 },
  predecessor: { to: 'lol', sim: 0.55 },
  valmobile: { to: 'valorant', sim: 0.9 },
  r6: { to: 'cs2', sim: 0.62 },
  rivals: { to: 'valorant', sim: 0.4 },
  overwatch: { to: 'valorant', sim: 0.45 },
  paladins: { to: 'valorant', sim: 0.4 },
  deadlock: { to: 'valorant', sim: 0.35 },
  owrush: { to: 'valorant', sim: 0.35 },
  apex: { to: 'cs2', sim: 0.35 },
  warzone: { to: 'cs2', sim: 0.4 },
  fortnite: { to: 'cs2', sim: 0.3 },
  pubg: { to: 'cs2', sim: 0.4 },
  pubgm: { to: 'cs2', sim: 0.35 },
  codm: { to: 'cs2', sim: 0.4 },
  cod: { to: 'cs2', sim: 0.45 },
  wzm: { to: 'cs2', sim: 0.35 },
};

export interface ScopeReport {
  removedTitles: string[];
  competitions: number;
  divisions: number;
  players: number;
  staff: number;
  userDivisions: string[];
  userLeftWithoutTeams: boolean;
}

/** Title IDs referenced by a (raw or migrated) save that are not part of the game. */
export function removedTitlesIn(w: { meta?: { titlesEnabled?: string[] }; teams?: Record<string, { titleId: string }>; players?: Record<string, { titleId: string }>; leagues?: Record<string, { titleId: string }> }): string[] {
  const ids = new Set<string>(w.meta?.titlesEnabled ?? []);
  for (const t of Object.values(w.teams ?? {})) ids.add(t.titleId);
  for (const l of Object.values(w.leagues ?? {})) ids.add(l.titleId);
  for (const p of Object.values(w.players ?? {})) ids.add(p.titleId);
  return [...ids].filter((id) => id && !isPlayableTitle(id)).sort();
}

export class ScopeConversionError extends Error {}

function moveToAllowedTitle(world: World, p: Player, removed: string): void {
  const map = REMOVED_TITLE_MAP[removed] ?? { to: getTitle(removed).family === 'MOBA' ? 'lol' : 'cs2', sim: 0.3 };
  const t = TITLE_BY_ID[map.to];
  const oldShort = getTitle(removed).short;
  p.prof.titles[t.id] = Math.max(p.prof.titles[t.id] ?? 0, Math.round(15 + 55 * map.sim));
  for (const key of [removed]) {
    delete p.prof.titles[key];
    delete p.prof.chars[key];
    delete p.prof.maps[key];
    delete p.prof.weapons[key];
  }
  for (const k of Object.keys(p.prof.roles)) if (k.startsWith(`${removed}:`)) delete p.prof.roles[k];
  p.titleId = t.id;
  const br = bestRole(p, t);
  const second = t.roles.filter((r) => r.id !== br.roleId).sort((a, b) => effectivenessLight(p, t, b) - effectivenessLight(p, t, a))[0];
  p.roles = [br.roleId, second?.id ?? br.roleId];
  for (const r of p.roles) p.prof.roles[`${t.id}:${r}`] = Math.max(p.prof.roles[`${t.id}:${r}`] ?? 0, Math.round(20 + 40 * map.sim));
  if ((p as { dualTitleId?: string | null }).dualTitleId && !isPlayableTitle((p as { dualTitleId?: string }).dualTitleId!)) (p as { dualTitleId?: string | null }).dualTitleId = null;
  p.career.push({ season: world.season, day: world.day, titleId: t.id, teamId: p.teamId, orgId: p.teamId ? world.teams[p.teamId]?.orgId ?? null : null, type: 'title-switch', note: `${oldShort} is no longer part of the game → ${t.short}` });
  p.attrVersion++;
  p.ca = Math.round(effectivenessLight(p, t, t.roles.find((r) => r.id === p.roles[0])!) * 10) / 10;
}

/**
 * Convert a migrated world to the seven-title scope. Returns null when nothing needed converting.
 * Throws ScopeConversionError when no allowed title would remain.
 */
export function convertToSevenTitles(world: World): ScopeReport | null {
  const removedList = removedTitlesIn(world);
  const pending = (world.meta as { scopeConversion?: unknown }).scopeConversion;
  if (!removedList.length) {
    if (pending) delete (world.meta as { scopeConversion?: unknown }).scopeConversion;
    return null;
  }
  const removed = new Set(removedList);
  const enabled = world.meta.titlesEnabled.filter((id) => isPlayableTitle(id));
  if (!enabled.length) throw new ScopeConversionError(`This save only contains games that are no longer part of Esports Manager (${removedList.map((id) => getTitle(id).name.replace(' (archived)', '')).join(', ')}). It cannot be converted; export the original backup or start a new career.`);
  const report: ScopeReport = { removedTitles: removedList, competitions: 0, divisions: 0, players: 0, staff: 0, userDivisions: [], userLeftWithoutTeams: false };
  world.meta.titlesEnabled = enabled;

  // Competitions, fixtures, match records and schedule entries.
  const deadFixtures = new Set<string>();
  for (const [id, c] of Object.entries(world.comps)) {
    if (!removed.has(c.titleId)) continue;
    for (const st of c.stages) for (const fid of st.fixtureIds) deadFixtures.add(fid);
    delete world.comps[id];
    report.competitions++;
  }
  for (const [fid, f] of Object.entries(world.fixtures)) if (removed.has(f.titleId)) deadFixtures.add(fid);
  for (const fid of deadFixtures) {
    delete world.fixtures[fid];
    delete world.matches[fid];
    delete world.overrides[fid];
  }
  for (const [fid, m] of Object.entries(world.matches)) if (removed.has(m.titleId)) delete world.matches[fid];
  for (const k of Object.keys(world.schedule)) {
    const d = Number(k);
    const ids = world.schedule[d].filter((id) => world.fixtures[id]);
    if (ids.length) world.schedule[d] = ids;
    else delete world.schedule[d];
  }
  if (world.pendingUserMatch && !world.fixtures[world.pendingUserMatch]) world.pendingUserMatch = null;
  for (const [id, l] of Object.entries(world.leagues)) if (removed.has(l.titleId)) delete world.leagues[id];

  // Divisions (teams) of removed titles are closed; their players become free agents.
  const deadTeams = new Set(Object.values(world.teams).filter((t) => removed.has(t.titleId)).map((t) => t.id));
  for (const tid of deadTeams) {
    const team = world.teams[tid];
    const org = world.orgs[team.orgId];
    if (org?.user) report.userDivisions.push(team.name);
    for (const pid of team.roster) {
      const p = world.players[pid];
      if (!p) continue;
      p.teamId = null;
      p.contract = null;
      p.listed = false;
      p.preContract = null;
      p.career.push({ season: world.season, day: world.day, titleId: p.titleId, teamId: null, orgId: null, type: 'released', note: 'Division closed (title no longer part of the game)' });
    }
    for (const sid of team.staff ?? []) {
      const s = world.staff[sid];
      if (s && s.teamId === tid) s.teamId = null;
    }
    if (org) org.teams = org.teams.filter((x) => x !== tid);
    delete world.teams[tid];
    report.divisions++;
  }
  // Anyone still pointing at a closed team (loans, stale links) is released too.
  for (const p of Object.values(world.players)) {
    if (p.teamId && !world.teams[p.teamId]) {
      p.teamId = null;
      p.contract = null;
    }
    if (p.preContract && !world.teams[p.preContract.teamId]) p.preContract = null;
    if (removed.has(p.titleId)) {
      moveToAllowedTitle(world, p, p.titleId);
      report.players++;
    }
  }
  for (const s of Object.values(world.staff)) {
    const st = s as { titleId?: string | null; teamId?: string | null; titleFam?: Record<string, number> };
    if (st.teamId && !world.teams[st.teamId]) st.teamId = null;
    if (st.titleId && removed.has(st.titleId)) {
      st.titleId = REMOVED_TITLE_MAP[st.titleId]?.to ?? null;
      report.staff++;
    }
    if (st.titleFam) for (const id of removed) delete st.titleFam[id];
  }
  for (const id of removed) {
    delete world.titles[id];
    delete world.customFormats?.[id];
    delete world.rulePresets?.[id];
  }
  world.draftPicks = (world.draftPicks ?? []).filter((d) => world.leagues[d.leagueId] && world.teams[d.ownerTeamId]);
  world.negotiations = (world.negotiations ?? []).filter((n) => world.teams[n.buyerTeamId] && world.players[n.playerId]);
  world.offers = world.offers.filter((o) => world.teams[o.fromTeamId] && (!o.toTeamId || world.teams[o.toTeamId]));
  if (world.meta.nationalJob && removed.has(world.meta.nationalJob.titleId)) world.meta.nationalJob = null;
  if (world.meta.board) world.meta.board.objectives = world.meta.board.objectives.filter((o) => !o.teamId || world.teams[o.teamId]);

  const uid = world.meta.userOrgId;
  if (uid && world.orgs[uid] && !world.orgs[uid].teams.some((t) => world.teams[t]?.active)) {
    report.userLeftWithoutTeams = true;
    world.orgs[uid].user = false;
    world.meta.unemployed = true;
  }
  delete (world.meta as { scopeConversion?: unknown }).scopeConversion;
  addInbox(world, {
    from: 'Esports Manager',
    subject: 'Save converted to the seven-title edition',
    body: [
      `The game now covers seven titles only. Removed from this save: ${removedList.map((id) => getTitle(id).name.replace(' (archived)', '')).join(', ')}.`,
      `${report.divisions} division(s) and ${report.competitions} competition(s) of those titles were closed; ${report.players} player(s) and ${report.staff} staff member(s) kept their identity and records and moved to the closest remaining title (players as free agents).`,
      report.userDivisions.length ? `Your organisation's closed divisions: ${report.userDivisions.join(', ')}.` : 'None of your divisions were affected.',
      report.userLeftWithoutTeams ? 'Your organisation has no remaining divisions, so you are free to choose a new job.' : '',
      'Past results, awards and trophies keep their original titles (shown as "archived"). The untouched original save is kept as a backup you can export from the Load screen.',
    ]
      .filter(Boolean)
      .join('\n'),
    kind: 'board',
  });
  return report;
}

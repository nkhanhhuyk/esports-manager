/**
 * Real-world start: builds leagues, organisations, teams, players and staff from the dated offline
 * roster snapshot (src/data/real/rosters.json, built by scripts/data/rosters*.mjs).
 *
 * What is real and what is not:
 *  - Real: league names (where the source is a league), team names, short codes, player handles,
 *    published roles, published real names and nationalities, staff handles.
 *  - Estimates (listed in each entity's `real.est`): roles derived from match data, nationalities
 *    and ages that the source does not publish, organisation countries, cross-title organisation
 *    grouping by name.
 *  - Game simulation (never claimed as real): every rating/attribute, personality, contract,
 *    salary, finance, sponsor, owner, facility and everything that happens after the snapshot date.
 * Regions without data (and Honor of Kings / Wild Rift, which have no accessible verifiable roster
 * source) are filled with fictional teams, which stay unmarked (`real` absent).
 */
import ROSTERS from '../data/real/rosters.json';
import { clamp } from '../core/math';
import type { RNG } from '../core/rng';
import { COUNTRIES, COUNTRY_BY_CODE, expandRegion, regionEconomy } from '../data/countries';
import { getTitle } from '../data/titles/catalogue';
import type { League, LeaguePolicy, Organisation, RealTag, StaffRole, Team } from '../model/types';
import { nextId } from '../model/ids';
import { generatePlayer, type GenCtx } from './player';
import { generateStaff } from './staff';
import { expectedSalary, expectedStaffSalary, makeContract } from '../systems/contracts';

interface SnapPerson {
  handle: string;
  real_name?: string | null;
  first_name?: string | null;
  last_name?: string | null;
  nationality?: string | null;
  nationality_estimate?: string | null;
  role?: string | null;
  role_source?: string | null;
  status: 'player' | 'staff';
  starter?: boolean;
}
interface SnapTeam {
  team_id: string;
  title_id: string;
  league_id: string;
  name: string;
  short: string;
  org: string;
  country?: string | null;
  region?: string | null;
  source_url: string;
  collected_at: string;
  rating?: number;
  vrs_points?: number;
  vrs_rank?: number;
  record?: { w: number; l: number };
  roster: SnapPerson[];
}
interface SnapLeague {
  league_id: string;
  title_id: string;
  name: string;
  short: string;
  region?: string | null;
  source_url: string;
  season?: string;
}
const SNAP = ROSTERS as unknown as { snapshot_id: string; as_of: string; leagues: SnapLeague[]; teams: SnapTeam[] };

export const ROSTER_SNAPSHOT = { id: SNAP.snapshot_id, asOf: SNAP.as_of };

/** Titles that have real teams in the snapshot. */
export function realTitles(): string[] {
  return [...new Set(SNAP.teams.map((t) => t.title_id))];
}

const AMER = 'AMER-NA.LATN.LATS.BR';
const EMEA = 'EMEA-EUW.EUE.CIS.MENA.AFR';
/** Game region for each league of the snapshot. */
const LEAGUE_REGION: Record<string, string> = {
  'mpl-id': 'ID',
  'mpl-ph': 'PH',
  'lol-lck': 'KR',
  'lol-lpl': 'CN',
  'lol-lec': 'EMEA-EUW.EUE.CIS.MENA',
  'lol-lcs': 'NA',
  'lol-cblol': 'BR',
  'lol-lcp': 'APN-PCS.JP.VN',
  'vct-americas': AMER,
  'vct-emea': EMEA,
  'vct-pacific': 'APN-KR.JP.PCS.VN.TH.ID.PH.MYSG.SA.OCE',
  'vct-china': 'CN',
  'cs2-vrs-europe': EMEA,
  'cs2-vrs-americas': AMER,
  'cs2-vrs-asia': 'APS-CN.KR.JP.PCS.MYSG.OCE.SA.ID.PH.TH.VN',
};
/** Dota 2 has no regional leagues in the source: teams are grouped by their estimated region. */
const DOTA_GROUP: Record<string, { region: string; name: string; short: string }> = {
  EU: { region: 'EMEA-EUW.EUE.CIS.MENA', name: 'Dota 2 Europe & CIS League', short: 'D2 EU' },
  CN: { region: 'CN', name: 'Dota 2 China League', short: 'D2 CN' },
  SEA: { region: 'APS-MYSG.PH.ID.TH.VN.OCE.SA', name: 'Dota 2 Southeast Asia League', short: 'D2 SEA' },
  AM: { region: AMER, name: 'Dota 2 Americas League', short: 'D2 AM' },
};
/** CS2 standings are rankings, not leagues: the game groups the ranked teams into leagues. */
const CS2_NAME: Record<string, { name: string; short: string }> = {
  'cs2-vrs-europe': { name: 'CS2 Europe League', short: 'CS EU' },
  'cs2-vrs-americas': { name: 'CS2 Americas League', short: 'CS AM' },
  'cs2-vrs-asia': { name: 'CS2 Asia-Pacific League', short: 'CS AP' },
};

const MIN_LEAGUE = 6;

function tag(src: string, est: string[] = []): RealTag {
  return est.length ? { src, snap: SNAP.snapshot_id, est } : { src, snap: SNAP.snapshot_id };
}

/** Organisation identity across titles (same real name ⇒ same organisation; documented estimate). */
const ORG_ALIAS: Record<string, string> = { navi: 'natusvincere', rrqhoshi: 'rrq', rexregumqeon: 'rrq', teamliquidph: 'liquid', teamliquidid: 'liquid' };
const ORG_NOISE = /\b(team|esports?|e-sports|gaming|club|pro|by vit|alienware|kia|movistar|beijing|shenzhen|suzhou|xi'an|shanghai|hangzhou|chengdu|wuhan|guangzhou|ph|id|indonesia|philippines)\b/g;
export function orgKey(name: string): string {
  const k = name.toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(ORG_NOISE, ' ').replace(/[^a-z0-9]/g, '');
  return ORG_ALIAS[k] ?? (k || name.toLowerCase());
}

function shortOf(name: string, fallback: string): string {
  return (fallback || name).replace(/\s+/g, '').slice(0, 6).toUpperCase();
}

function regionStrength(pop: Record<string, number>, region: string): number {
  if (pop[region] !== undefined) return pop[region];
  const parts = expandRegion(region);
  return parts.length ? Math.max(...parts.map((r) => pop[r] ?? 0)) * 0.85 : 30;
}

function policy(titleId: string, region: string, tier: 1 | 2, teamSize: number, subs: number): LeaguePolicy {
  const t = getTitle(titleId);
  return {
    minAge: t.minAge,
    rosterMin: teamSize,
    rosterMax: teamSize + subs + 4,
    foreignLimit: -1,
    cap: { type: 'none', amount: 0, taxRate: 0 },
    minSalary: Math.round((t.baseSalary * regionEconomy(region) * 0.06) / 100) * 100,
    maxSalary: 0,
    trades: false,
    draft: false,
    loans: true,
    transferFees: true,
    restrictedFA: false,
  };
}

/** Estimated country for a region: its main country for single-region leagues, else weighted. */
function pickCountry(rng: RNG, region: string): string {
  const regs = expandRegion(region);
  const list = COUNTRIES.filter((c) => regs.includes(c.region));
  if (!list.length) return 'US';
  if (regs.length === 1) return [...list].sort((a, b) => b.weight - a.weight)[0].code;
  return rng.weighted(list, (c) => c.weight).code;
}

/** Display name of an organisation: city prefixes of Chinese league franchises are dropped. */
const cleanOrg = (s: string): string => s.replace(/^(Beijing|Shenzhen|Suzhou|Xi'an|Shanghai|Hangzhou|Chengdu|Wuhan|Guangzhou|Chongqing|Shaanxi)\s+/i, '').trim();
const isCaps = (s: string): boolean => s === s.toUpperCase() && /[A-Z]{3}/.test(s);
const NAME_RANK: Record<string, number> = { valorant: 0, dota2: 1, lol: 2, mlbb: 3, cs2: 4 };
/** Best name-source rank seen per organisation (lower = better spelling). */
const nameRank = new Map<string, number>();

function validCountry(cc?: string | null): string | null {
  const c = cc?.toUpperCase();
  return c && COUNTRY_BY_CODE[c] ? c : null;
}

export interface RealBuild {
  /** Member regions covered by real leagues of this title. */
  covered: Set<string>;
  teams: Team[];
}

/**
 * Create this title's real leagues and teams. `orgs` is shared across titles (organisation key →
 * organisation) so the same real organisation owns its teams in several games.
 */
export function buildRealTitle(
  ctx: GenCtx,
  titleId: string,
  orgs: Map<string, Organisation>,
  makeOrg: (region: string) => Organisation,
  /** Creates and populates a fictional team in `league` around quality `mean`. */
  fill: (org: Organisation, league: League, mean: number) => Team,
): RealBuild {
  const { world, rng } = ctx;
  const t = getTitle(titleId);
  const pop = world.titles[titleId].popularity;
  const covered = new Set<string>();
  const built: Team[] = [];
  const snapTeams = SNAP.teams.filter((x) => x.title_id === titleId);
  if (!snapTeams.length) return { covered, teams: built };

  // Group the snapshot teams into game leagues.
  type Group = { key: string; region: string; name: string; short: string; src: string; season?: string; teams: SnapTeam[]; leagueIsReal: boolean };
  const groups: Group[] = [];
  if (titleId === 'dota2') {
    const by = new Map<string, SnapTeam[]>();
    for (const st of snapTeams) {
      const g = st.region === 'NA' || st.region === 'SA' ? 'AM' : st.region && DOTA_GROUP[st.region] ? st.region : 'EU';
      (by.get(g) ?? by.set(g, []).get(g)!).push(st);
    }
    for (const [g, list] of by) groups.push({ key: `dota2-${g}`, ...DOTA_GROUP[g], src: 'https://api.opendota.com/api/teams', teams: list, leagueIsReal: false });
  } else {
    for (const L of SNAP.leagues.filter((l) => l.title_id === titleId)) {
      const list = snapTeams.filter((x) => x.league_id === L.league_id);
      if (!list.length || !LEAGUE_REGION[L.league_id]) continue;
      const cs = CS2_NAME[L.league_id];
      groups.push({ key: L.league_id, region: LEAGUE_REGION[L.league_id], name: cs?.name ?? L.name, short: cs?.short ?? L.short, src: L.source_url, season: L.season, teams: list, leagueIsReal: !cs });
    }
  }

  for (const g of groups) {
    for (const r of expandRegion(g.region)) covered.add(r);
    const strength0 = regionStrength(pop, g.region);
    const scene = expandRegion(g.region)
      .flatMap((r) => COUNTRIES.filter((c) => c.region === r))
      .reduce((acc, c, _, arr) => acc + (t.family === 'MOBA' ? c.moba : c.shooter) / arr.length, 0);
    const strength = 0.6 * strength0 + 0.4 * scene;
    const leagueMean = 57 + 19 * (strength / 100);
    // Order teams by their published strength signal (rating / standings points / record).
    const signal = (st: SnapTeam): number => st.vrs_points ?? st.rating ?? (st.record ? (st.record.w + 1) / (st.record.w + st.record.l + 2) : 0.5);
    const ordered = [...g.teams].sort((a, b) => signal(b) - signal(a));
    const sigs = ordered.map(signal);
    const mean = sigs.reduce((a, b) => a + b, 0) / sigs.length;
    const sd = Math.sqrt(sigs.reduce((a, b) => a + (b - mean) ** 2, 0) / sigs.length) || 1;
    // Large ranked pools (CS2 Europe) split into a top league and a challenger league.
    const cut = ordered.length > 16 ? 12 : ordered.length;
    const tiers: Array<{ tier: 1 | 2; list: SnapTeam[] }> = [{ tier: 1, list: ordered.slice(0, cut) }];
    if (cut < ordered.length) tiers.push({ tier: 2, list: ordered.slice(cut) });
    let upper: League | null = null;
    for (const { tier, list } of tiers) {
      const league: League = {
        id: nextId(world, 'l'),
        titleId,
        region: g.region,
        tier,
        name: tier === 1 ? g.name : g.name.replace(/League$/, 'Challengers'),
        short: tier === 1 ? g.short : `${g.short} C`,
        teamIds: [],
        size: 0,
        franchised: false,
        policy: policy(titleId, g.region, tier, t.teamSize, t.subs),
        promotion: 0,
        upperId: upper?.id ?? null,
        lowerId: null,
        fullDetail: false,
        founded: world.season,
        real: tag(g.src, g.leagueIsReal ? [] : ['competition structure (game)']),
      };
      if (upper) {
        upper.lowerId = league.id;
        upper.promotion = 1;
        league.promotion = 1;
      }
      world.leagues[league.id] = league;
      for (const st of list) {
        const z = (signal(st) - mean) / sd;
        const teamMean = leagueMean + (tier === 2 ? -5 : 0) + clamp(z, -2.2, 2.2) * 1.8 + rng.normal(0, 0.8);
        built.push(createRealTeam(ctx, st, league, teamMean, orgs, makeOrg));
      }
      // Too few real teams for a playable league: fill with fictional teams (unmarked).
      for (let i = league.teamIds.length; i < MIN_LEAGUE; i++) {
        const org = makeOrg(expandRegion(g.region)[i % expandRegion(g.region).length]);
        built.push(fill(org, league, leagueMean - 3));
      }
      league.size = league.teamIds.length;
      upper = league;
    }
  }
  return { covered, teams: built };
}

function createRealTeam(ctx: GenCtx, st: SnapTeam, league: League, teamMean: number, orgs: Map<string, Organisation>, makeOrg: (region: string) => Organisation): Team {
  const { world, rng } = ctx;
  const t = getTitle(st.title_id);
  const regs = expandRegion(league.region);
  const players = st.roster.filter((p) => p.status === 'player');
  // Organisation country: published team country, else the most common published nationality.
  const nats = players.map((p) => validCountry(p.nationality)).filter((x): x is string => !!x);
  const natCount = new Map<string, number>();
  for (const n of nats) natCount.set(n, (natCount.get(n) ?? 0) + 1);
  const topNat = [...natCount].sort((a, b) => b[1] - a[1])[0]?.[0];
  const orgCountry = validCountry(st.country) ?? (topNat && natCount.get(topNat)! >= 3 ? topNat : null);
  const key = orgKey(st.org || st.name);
  let org = orgs.get(key);
  // One main team per title per organisation: a second team of the same title is its own org.
  if (org && org.teams.some((id) => world.teams[id]?.titleId === st.title_id)) org = undefined;
  if (!org) {
    const country = orgCountry ?? pickCountry(rng, league.region);
    const region = COUNTRY_BY_CODE[country]?.region ?? regs[0];
    org = makeOrg(region);
    const name = cleanOrg(st.org || st.name);
    org.name = name;
    org.short = shortOf(name, st.short);
    org.country = country;
    org.region = region;
    org.names = [{ season: world.season, name, short: org.short }];
    org.events = [{ season: world.season, day: world.day, type: 'founded', text: `${name} enters the game world (real organisation; history before the snapshot is not simulated).` }];
    org.real = tag(st.source_url, ['founded', ...(orgCountry ? [] : ['country']), 'finances, owner, sponsors and facilities (game)']);
    if (!orgs.has(key)) orgs.set(key, org);
    nameRank.set(org.id, (isCaps(name) ? 10 : 0) + (NAME_RANK[st.title_id] ?? 5));
  } else {
    if (org.real?.est && !org.real.est.includes('cross-title grouping by name')) org.real.est.push('cross-title grouping by name');
    // Prefer the source that spells the organisation's name in normal case.
    const name = cleanOrg(st.org || st.name);
    // Which source spells the organisation best: Valorant pages use full org names, Valve's
    // standings short ones, MLBB pages capitals.
    const rank = (n: string, title: string): number => (isCaps(n) ? 10 : 0) + (NAME_RANK[title] ?? 5);
    const better = rank(name, st.title_id) < (nameRank.get(org.id) ?? 99);
    if (better) nameRank.set(org.id, rank(name, st.title_id));
    if (better && orgKey(name) === orgKey(org.name)) {
      org.name = name;
      org.names = [{ season: world.season, name, short: org.short }];
    }
  }
  const team: Team = {
    id: nextId(world, 't'),
    orgId: org.id,
    titleId: st.title_id,
    name: st.name,
    short: st.short.slice(0, 6),
    tier: 'main',
    region: league.region,
    leagueId: league.id,
    parentTeamId: null,
    roster: [],
    staff: [],
    lineup: [],
    prefs: { style: rng.pick(t.styles), aggression: Math.round(clamp(rng.normal(50, 15), 10, 90)), draftPriority: 'balanced', pool: {}, bans: [], mapPrefs: {}, delegateLineup: true, delegateDraft: true, delegateTraining: true },
    training: { focus: 'balanced', intensity: 60, scrimsPerWeek: 3, individual: {}, opponentPrep: true },
    chemistry: Math.round(clamp(rng.normal(62, 10), 30, 90)),
    elo: 1500,
    metaAdapt: Math.round(clamp(rng.normal(72, 8), 45, 95)),
    salaryBudget: 0,
    history: [],
    trophies: [],
    founded: world.season,
    active: true,
    scrimRating: 50,
    real: tag(st.source_url),
  };
  world.teams[team.id] = team;
  org.teams.push(team.id);
  league.teamIds.push(team.id);
  org.rep = Math.max(org.real ? org.rep : 0, Math.round(clamp(42 + (teamMean - 64) * 3 + rng.normal(0, 4), 15, 95)));
  org.fans = Math.round(Math.pow(org.rep, 2.2) * 40 * regionEconomy(org.region) + rng.int(1000, 20000));
  org.prestige = Math.round(org.rep * 0.5);

  // Players: published roles first; the rest of the line-up roles go to players without a role.
  const lineupRoles = new Set(t.lineup);
  const usable = players.slice(0, t.teamSize + t.subs + 4);
  const known = usable.map((p) => (p.role && t.roles.some((r) => r.id === p.role) ? p.role : null));
  const missing = t.lineup.filter((r) => !known.some((k, i) => k === r && usable[i].starter !== false));
  // Starters: the source's starter flags when it has them, else the first listed player per role.
  const flagged = usable.some((p) => p.starter === true);
  const firstOfRole = new Set<number>();
  {
    const seen = new Set<string>();
    known.forEach((k, i) => {
      if (k && !seen.has(k)) {
        seen.add(k);
        firstOfRole.add(i);
      }
    });
  }
  usable.forEach((sp, i) => {
    let roleId = known[i];
    const est: string[] = [];
    if (sp.role_source) est.push('role');
    if (!roleId) {
      roleId = missing.shift() ?? rng.pick(t.lineup);
      est.push('role (game assignment)');
    }
    let nat = validCountry(sp.nationality);
    if (!nat) {
      nat = validCountry(sp.nationality_estimate) ?? (org.real?.est?.includes('country') ? pickCountry(rng, league.region) : org.country);
      est.push('nationality');
    }
    const age = Math.round(clamp(rng.normal(22, 2.4), 17, 30));
    est.push('age');
    const starter = lineupRoles.has(roleId) && (flagged ? sp.starter === true : firstOfRole.has(i) || !known[i]);
    const p = generatePlayer(ctx, { titleId: t.id, roleId, nat, age, targetCA: clamp(teamMean + (starter ? 1 : -4) + rng.normal(0, starter ? 1.6 : 2.2), 35, 95) });
    p.handle = sp.handle;
    p.first = sp.first_name ?? '';
    p.last = sp.last_name ?? '';
    if (!sp.first_name && sp.real_name) {
      const parts = sp.real_name.split(' ');
      p.first = parts.slice(0, -1).join(' ');
      p.last = parts[parts.length - 1];
    }
    if (!sp.first_name && !sp.real_name) {
      p.first = '';
      p.last = '';
    }
    p.real = tag(st.source_url, est);
    ctx.handles.add(sp.handle);
    world.players[p.id] = p;
    signReal(ctx, p.id, team);
  });

  // Incomplete or starters-only rosters: open line-up roles and one substitute are game-generated
  // fictional players (no `real` tag), so the team can field a legal line-up.
  const have = new Set(team.roster.map((id) => world.players[id].roles[0]));
  const open = t.lineup.filter((r) => !have.has(r));
  while (team.roster.length < t.teamSize + 1 || open.length) {
    const roleId = open.shift() ?? rng.pick(t.lineup);
    const nat = org.country;
    const p = generatePlayer(ctx, { titleId: t.id, roleId, nat, age: Math.round(clamp(rng.normal(20, 2), 17, 27)), targetCA: clamp(teamMean - 8 + rng.normal(0, 2), 35, 90) });
    world.players[p.id] = p;
    signReal(ctx, p.id, team);
  }
  if (team.real && players.length < t.teamSize) team.real.est = ['roster completed with fictional players'];

  // Staff: published coaches; missing core roles are game-generated.
  const staffSnap = st.roster.filter((p) => p.status === 'staff');
  const roles: StaffRole[] = [];
  for (const sp of staffSnap.slice(0, 3)) {
    const role: StaffRole = sp.role === 'head-coach' && !roles.includes('head-coach') ? 'head-coach' : sp.role === 'analyst' ? 'analyst' : roles.includes('head-coach') ? 'assistant-coach' : 'head-coach';
    roles.push(role);
    const s = generateStaff(ctx, role, validCountry(sp.nationality) ?? org.country, team.titleId, clamp(rng.normal(55 + (teamMean - 64) * 1.2, 7), 30, 92));
    s.handle = sp.handle;
    s.first = '';
    s.last = '';
    s.real = tag(st.source_url, ['age', ...(validCountry(sp.nationality) ? [] : ['nationality'])]);
    hireStaff(ctx, s.id, team, s);
  }
  for (const role of ['head-coach', 'analyst'] as StaffRole[]) {
    if (roles.includes(role)) continue;
    const s = generateStaff(ctx, role, org.country, team.titleId, clamp(rng.normal(55 + (teamMean - 64), 7), 30, 90));
    hireStaff(ctx, s.id, team, s);
  }
  return team;
}

function signReal(ctx: GenCtx, pid: string, team: Team): void {
  const { world, rng } = ctx;
  const p = world.players[pid];
  // Contracts are game simulation (real contract terms are private).
  const sal = expectedSalary(world, p, team.titleId, team.region) * clamp(rng.normal(1, 0.1), 0.75, 1.3);
  p.contract = makeContract(world, team, sal, rng.int(1, 3));
  p.teamId = team.id;
  p.joinedDay = world.day - rng.int(0, 500);
  team.roster.push(p.id);
  p.career.push({ season: world.season, day: world.day, titleId: team.titleId, teamId: team.id, orgId: team.orgId, roleId: p.roles[0], type: 'start' });
}

function hireStaff(ctx: GenCtx, sid: string, team: Team, s: ReturnType<typeof generateStaff>): void {
  const { world, rng } = ctx;
  s.teamId = team.id;
  s.orgId = team.orgId;
  s.contract = makeContract(world, team, expectedStaffSalary(world, s, team.titleId, team.region), rng.int(1, 3));
  world.staff[sid] = s;
  team.staff.push(sid);
  s.career.push({ season: world.season, day: world.day, titleId: team.titleId, teamId: team.id, orgId: team.orgId, type: 'start' });
}

// ---------------------------------------------------------------------------------------------
// History: dormant real teams, real free agents and retired real players
// ---------------------------------------------------------------------------------------------
import HISTORY from '../data/real/history.json';

interface HistTeam {
  title_id: string;
  team_id: string;
  name: string;
  short: string;
  region: string | null;
  league: string | null;
  last_season: number;
  source_url: string;
  roster: string[];
}
interface HistPlayer {
  title_id: string;
  handle: string;
  first_name: string | null;
  last_name: string | null;
  real_name: string | null;
  nationality: string | null;
  role: string | null;
  last_team: string | null;
  last_season: number;
  status: 'free-agent' | 'retired';
  status_source: string;
  source_url: string;
}
const HIST = HISTORY as unknown as { snapshot_id: string; teams: HistTeam[]; players: HistPlayer[] };

const HIST_REGION: Record<string, string> = {
  AMER: 'AMER-NA.LATN.LATS.BR',
  EMEA: 'EMEA-EUW.EUE.CIS.MENA.AFR',
  APN: 'APN-KR.JP.PCS.VN.TH.ID.PH.MYSG.SA.OCE',
};
const regionFor = (h: HistTeam): string => (h.region ? HIST_REGION[h.region] ?? h.region : h.title_id === 'cs2' || h.title_id === 'dota2' ? 'EMEA-EUW.EUE.CIS.MENA.AFR' : 'NA');
const seasonOf = (x: number) => Math.floor(x) || 0;

/**
 * Add the snapshot's history to a real-world start: teams that played in earlier seasons but hold no
 * league place now become dormant teams of their (shared) real organisations — they come back when a
 * league slot changes hands; past players on no current roster become free agents (or retired records
 * when they have not played at the top level for a long time — an estimate, always labelled).
 */
export function addRealHistory(ctx: GenCtx, orgs: Map<string, Organisation>, makeOrg: (region: string) => Organisation): { teams: number; freeAgents: number; retired: number } {
  const { world, rng } = ctx;
  const titles = new Set(world.meta.titlesEnabled);
  const liveHandles = new Map<string, Set<string>>();
  for (const p of Object.values(world.players)) {
    if (!p.real) continue;
    (liveHandles.get(p.titleId) ?? liveHandles.set(p.titleId, new Set()).get(p.titleId)!).add(p.handle.toLowerCase());
  }
  // Real pros who already retired in this career (older saves) are not added again.
  for (const r of world.retired) if (r.real) for (const tid of r.titles) (liveHandles.get(tid) ?? liveHandles.set(tid, new Set()).get(tid)!).add(r.handle.toLowerCase());
  const liveTeamNames = new Set(Object.values(world.teams).map((t) => `${t.titleId}|${t.name.toLowerCase()}`));
  // Average ability per title (free agents start somewhat below the leagues' level).
  const avg = new Map<string, number>();
  for (const id of titles) {
    const ps = Object.values(world.players).filter((p) => p.titleId === id && p.teamId);
    avg.set(id, ps.length ? ps.reduce((a, p) => a + p.ca, 0) / ps.length : 62);
  }
  let nTeams = 0;
  const dormantByName = new Map<string, Team>();
  // Organisations by name key (several can share one: one per team of the same game), and the games each has a team in.
  const orgTitles = new Map<string, Set<string>>();
  for (const x of Object.values(world.teams)) (orgTitles.get(x.orgId) ?? orgTitles.set(x.orgId, new Set()).get(x.orgId)!).add(x.titleId);
  const byKey = new Map<string, Organisation[]>();
  const addKey = (k: string, o: Organisation) => {
    const list = byKey.get(k) ?? byKey.set(k, []).get(k)!;
    if (!list.includes(o)) list.push(o);
  };
  for (const [k, o] of orgs) addKey(k, o);
  for (const o of Object.values(world.orgs)) if (o.real) addKey(orgKey(o.name), o);
  for (const h of HIST.teams) {
    if (!titles.has(h.title_id) || liveTeamNames.has(`${h.title_id}|${h.name.toLowerCase()}`)) continue;
    const t = getTitle(h.title_id);
    const region = regionFor(h);
    const key = orgKey(h.name);
    let org = byKey.get(key)?.find((o) => !orgTitles.get(o.id)?.has(h.title_id));
    if (!org) {
      const country = pickCountry(rng, region);
      org = makeOrg(COUNTRY_BY_CODE[country]?.region ?? expandRegion(region)[0]);
      org.name = cleanOrg(h.name);
      org.short = shortOf(h.name, h.short);
      org.country = country;
      org.region = COUNTRY_BY_CODE[country]?.region ?? org.region;
      org.names = [{ season: world.season, name: org.name, short: org.short }];
      org.events = [{ season: world.season, day: world.day, type: 'founded', text: `${org.name}: a real organisation without a league place in this snapshot.` }];
      org.real = tag(h.source_url, ['founded', 'country', 'finances, owner, sponsors and facilities (game)']);
      org.rep = Math.round(clamp(rng.normal(32, 8), 10, 60));
      org.fans = Math.round(Math.pow(org.rep, 2.2) * 30 * regionEconomy(org.region) + rng.int(1000, 10000));
      if (!orgs.has(key)) orgs.set(key, org);
      addKey(key, org);
    }
    (orgTitles.get(org.id) ?? orgTitles.set(org.id, new Set()).get(org.id)!).add(h.title_id);
    const team: Team = {
      id: nextId(world, 't'),
      orgId: org.id,
      titleId: h.title_id,
      name: h.name,
      short: (h.short || h.name).replace(/\s+/g, '').slice(0, 6).toUpperCase(),
      tier: 'main',
      region,
      leagueId: null,
      parentTeamId: null,
      roster: [],
      staff: [],
      lineup: [],
      prefs: { style: rng.pick(t.styles), aggression: Math.round(clamp(rng.normal(50, 15), 10, 90)), draftPriority: 'balanced', pool: {}, bans: [], mapPrefs: {}, delegateLineup: true, delegateDraft: true, delegateTraining: true },
      training: { focus: 'balanced', intensity: 60, scrimsPerWeek: 3, individual: {}, opponentPrep: true },
      chemistry: 50,
      elo: 1450,
      metaAdapt: 60,
      salaryBudget: 0,
      history: [],
      trophies: [],
      founded: world.season,
      lastSeen: seasonOf(h.last_season) || world.season - 2,
      // Dormant: not in a league and not part of the organisation's active divisions.
      active: false,
      scrimRating: 50,
      real: tag(h.source_url, [`dormant — last seen ${h.league ?? 'at the top level'}${h.last_season ? ` (${seasonOf(h.last_season)})` : ''}`]),
    };
    world.teams[team.id] = team;
    dormantByName.set(`${h.title_id}|${h.name.toLowerCase()}`, team);
    nTeams++;
  }
  // Players.
  let nFa = 0;
  for (const hp of HIST.players) {
    if (!titles.has(hp.title_id)) continue;
    const handles = liveHandles.get(hp.title_id) ?? new Set();
    if (handles.has(hp.handle.toLowerCase())) continue;
    handles.add(hp.handle.toLowerCase());
    liveHandles.set(hp.title_id, handles);
    const t = getTitle(hp.title_id);
    const roleId = hp.role && t.roles.some((r) => r.id === hp.role) ? hp.role : null;
    const team = hp.last_team ? dormantByName.get(`${hp.title_id}|${hp.last_team.toLowerCase()}`) : undefined;
    const liveTeam = hp.last_team ? Object.values(world.teams).find((x) => x.titleId === hp.title_id && x.name.toLowerCase() === hp.last_team!.toLowerCase()) : undefined;
    const nat = validCountry(hp.nationality) ?? (team ? pickCountry(rng, team.region) : 'US');
    // Every former pro is a free agent (long-inactive ones too — the user's choice; nobody is archived as retired).
    const longGone = hp.status === 'retired';
    const est: string[] = [longGone ? `free agent — ${hp.status_source.replace(/^estimate: /, '')}` : hp.status_source, 'age'];
    if (!roleId) est.push('role (game assignment)');
    if (!validCountry(hp.nationality)) est.push('nationality');
    const p = generatePlayer(ctx, {
      titleId: t.id,
      roleId: roleId ?? rng.pick(t.lineup),
      nat,
      age: Math.round(clamp(rng.normal(longGone ? 27 : 24, 2.5), 18, 33)),
      targetCA: clamp((avg.get(t.id) ?? 62) - (longGone ? 8 : 5) + rng.normal(0, 3), 35, 90),
    });
    p.handle = hp.handle;
    p.first = hp.first_name ?? '';
    p.last = hp.last_name ?? '';
    p.real = tag(hp.source_url, est);
    if (hp.last_team) p.career.push({ season: seasonOf(hp.last_season) || world.season - 1, day: world.day, titleId: t.id, teamId: (team ?? liveTeam)?.id ?? null, orgId: (team ?? liveTeam)?.orgId ?? null, type: 'released', note: `Last real team: ${hp.last_team}` });
    ctx.handles.add(hp.handle);
    world.players[p.id] = p;
    nFa++;
  }
  return { teams: nTeams, freeAgents: nFa, retired: 0 };
}

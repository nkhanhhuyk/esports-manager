/**
 * World generator: titles, countries, leagues, organisations, teams, players, staff, finances
 * and the first season's competition calendar.
 */
import { CHARACTER_SNAPSHOT } from '../data/titles/realCharacters';
import { PRESETS, type DifficultyPreset } from '../systems/difficulty';
import { clamp } from '../core/math';
import { RNG } from '../core/rng';
import { dayFromYMD, todayDayNum, ymd } from '../core/date';
import { COUNTRIES, COUNTRY_BY_CODE, MACROS, REGIONS, REGION_BY_ID, expandRegion, regionEconomy, regionShort, type MacroId } from '../data/countries';
import { getTitle, TITLES, TITLE_POPULARITY } from '../data/titles/catalogue';
import type { CareerRole, FacilityKey, League, LeaguePolicy, Organisation, OrgStrategy, Player, Team, TeamTier, World } from '../model/types';
import { nextId } from '../model/ids';
import { generatePlayer, pickNationality, type GenCtx } from './player';
import { generateStaff } from './staff';
import { makeColors, makeCompanyName, makeOrgName } from './names';
import { expectedSalary, expectedStaffSalary, makeContract, marketValue, titlePopIn } from '../systems/contracts';
import { sponsorDealValue } from '../systems/finance';
import { ensureAudience, initFanbase } from '../systems/fans';
import { ensureDraftPicks } from '../systems/draft';
import { autoLineup } from '../systems/lineup';
import { initialPatch } from '../systems/patches';
import { planSeason } from '../competition/season';
import { SAVE_VERSION } from '../persistence/version';
import { computeTeamStrength } from '../sim/common/team';
import { buildRealTitle, realTitles, ROSTER_SNAPSHOT } from './realWorld';

export type WorldSize = 'tiny' | 'small' | 'standard' | 'large';

export interface NewWorldOptions {
  name: string;
  seed: string;
  startDay?: number;
  worldSize: WorldSize;
  titles?: string[];
  managerName?: string;
  careerRole?: CareerRole;
  difficulty?: DifficultyPreset;
  /** 'real' starts from the dated real-world roster snapshot; default 'fictional'. */
  dataMode?: 'real' | 'fictional';
}

const SIZE: Record<WorldSize, { single: number; macro: number; tier2: number; teamScale: number; brScale: number }> = {
  tiny: { single: 1, macro: 1, tier2: 0, teamScale: 0.75, brScale: 0.6 },
  small: { single: 2, macro: 1, tier2: 0, teamScale: 1, brScale: 0.75 },
  standard: { single: 3, macro: 2, tier2: 1, teamScale: 1, brScale: 1 },
  large: { single: 5, macro: 3, tier2: 2, teamScale: 1.25, brScale: 1 },
};

/** Which regional leagues exist for a title, given its regional popularity. */
export function planLeagueRegions(titleId: string, size: WorldSize, pop: Record<string, number>): Array<{ region: string; strength: number }> {
  const cfg = SIZE[size];
  const sorted = Object.entries(pop).sort((a, b) => b[1] - a[1]);
  const singles = sorted.filter(([, v]) => v >= 50).slice(0, cfg.single);
  if (!singles.length) singles.push(sorted[0]);
  const used = new Set(singles.map(([r]) => r));
  const out = singles.map(([r, v]) => ({ region: r, strength: v }));
  const macroCands: Array<{ id: string; score: number; members: string[] }> = [];
  for (const m of Object.keys(MACROS) as MacroId[]) {
    const members = REGIONS.filter((r) => r.macro === m && !used.has(r.id) && (pop[r.id] ?? 0) >= 15).map((r) => r.id);
    if (!members.length) continue;
    const score = Math.max(...members.map((r) => pop[r] ?? 0));
    if (score < 20) continue;
    macroCands.push({ id: `${m}-${members.join('.')}`, score, members });
  }
  macroCands.sort((a, b) => b.score - a.score);
  for (const mc of macroCands.slice(0, cfg.macro)) out.push({ region: mc.id, strength: mc.score * 0.85 });
  return out;
}

function defaultPolicy(titleId: string, region: string, tier: 1 | 2, franchised: boolean, teamSize: number, subs: number): LeaguePolicy {
  const t = getTitle(titleId);
  const econ = regionEconomy(region);
  const single = !!REGION_BY_ID[region];
  return {
    minAge: t.minAge,
    rosterMin: teamSize,
    rosterMax: teamSize + subs + 2,
    foreignLimit: single && tier === 1 ? Math.max(1, Math.floor(teamSize / 2)) : -1,
    cap: franchised ? { type: 'soft', amount: Math.round(t.baseSalary * econ * teamSize * 2.6), taxRate: 0.5 } : { type: 'none', amount: 0, taxRate: 0 },
    minSalary: Math.round(t.baseSalary * econ * 0.06 / 100) * 100,
    maxSalary: 0,
    trades: franchised,
    draft: franchised,
    loans: true,
    transferFees: true,
    restrictedFA: franchised && t.family === 'SHOOTER',
  };
}

const LEAGUE_WORDS_T1 = ['Pro League', 'Premier League', 'Championship', 'Masters League', 'Elite Series', 'Major League'];

function leagueName(titleShort: string, region: string, tier: 1 | 2, rng: RNG): { name: string; short: string } {
  const rs = regionShort(region);
  if (tier === 2) return { name: `${titleShort} ${rs} Challengers`, short: `${rs}C` };
  const w = rng.pick(LEAGUE_WORDS_T1);
  return { name: `${titleShort} ${rs} ${w}`, short: `${rs}${w.split(' ').map((x) => x[0]).join('')}` };
}

function pickMemberRegion(rng: RNG, leagueRegion: string, pop: Record<string, number>): string {
  const members = expandRegion(leagueRegion);
  return rng.weighted(members, (r) => (pop[r] ?? 10) + 5);
}

function pickCountryIn(rng: RNG, region: string): string {
  const list = COUNTRIES.filter((c) => c.region === region);
  return rng.weighted(list, (c) => c.weight).code;
}

const STRATEGIES: OrgStrategy[] = ['win-now', 'balanced', 'youth', 'moneyball', 'prestige', 'expansion'];

export function createOrg(ctx: GenCtx, region: string, names: Set<string>, founded?: number): Organisation {
  const { rng, world } = ctx;
  const { name, short } = makeOrgName(rng, names);
  const country = pickCountryIn(rng, region);
  const rep = Math.round(clamp(rng.normal(38, 16), 5, 90));
  const fac = (): number => Math.round(clamp(1 + rep / 14 + rng.normal(0, 1.2), 1, 10));
  const facilities: Record<FacilityKey, number> = { training: fac(), analytics: fac(), academy: fac(), equipment: fac(), housing: fac(), performance: fac() };
  const org: Organisation = {
    id: nextId(world, 'o'),
    name,
    short,
    country,
    region,
    founded: founded ?? ymd(world.day).y - rng.int(0, 14),
    colors: makeColors(rng),
    rep,
    fans: Math.round(Math.pow(rep, 2.2) * 40 * regionEconomy(region) + rng.int(1000, 20000)),
    prestige: Math.round(rep * 0.5),
    owner: { name: makeCompanyName(rng) + ' Group', type: rng.pick(['founder', 'investor', 'public', 'conglomerate', 'club'] as const), wealth: Math.round(clamp(rng.normal(50, 22), 5, 99)), patience: Math.round(clamp(rng.normal(55, 20), 5, 99)) },
    strategy: rng.weighted(STRATEGIES, (s) => ({ 'win-now': 18, balanced: 34, youth: 18, moneyball: 14, prestige: 8, expansion: 8 })[s]),
    balance: 0,
    sponsors: [],
    ledger: [],
    monthly: [],
    debtMonths: 0,
    facilities,
    teams: [],
    partners: [],
    names: [{ season: world.season, name, short }],
    events: [],
    active: true,
    user: false,
  };
  org.events.push({ season: world.season, day: world.day, type: 'founded', text: `${name} founded in ${COUNTRY_BY_CODE[country]?.name ?? country}.` });
  org.fanbase = initFanbase(org, world.season);
  world.orgs[org.id] = org;
  return org;
}

export function createTeam(ctx: GenCtx, org: Organisation, titleId: string, region: string, tier: TeamTier, league: League | null, parent: Team | null): Team {
  const { rng, world } = ctx;
  const t = getTitle(titleId);
  const suffix = tier === 'academy' ? ' Academy' : tier === 'reserve' ? ' Reserve' : tier === 'youth' ? ' Youth' : '';
  const team: Team = {
    id: nextId(world, 't'),
    orgId: org.id,
    titleId,
    name: org.name + suffix,
    short: org.short + (tier === 'academy' ? '.A' : tier === 'reserve' ? '.R' : tier === 'youth' ? '.Y' : ''),
    tier,
    region,
    leagueId: league?.id ?? null,
    parentTeamId: parent?.id ?? null,
    roster: [],
    staff: [],
    lineup: [],
    prefs: { style: rng.pick(t.styles), aggression: Math.round(clamp(rng.normal(50, 15), 10, 90)), draftPriority: 'balanced', pool: {}, bans: [], mapPrefs: {}, delegateLineup: true, delegateDraft: true, delegateTraining: true },
    training: { focus: 'balanced', intensity: 60, scrimsPerWeek: 3, individual: {}, opponentPrep: true },
    chemistry: Math.round(clamp(rng.normal(55, 12), 20, 90)),
    elo: 1500,
    metaAdapt: Math.round(clamp(rng.normal(70, 10), 40, 95)),
    salaryBudget: 0,
    history: [],
    trophies: [],
    founded: world.season,
    active: true,
    scrimRating: 50,
  };
  world.teams[team.id] = team;
  org.teams.push(team.id);
  if (league) league.teamIds.push(team.id);
  return team;
}

export function signInitial(world: World, rng: RNG, p: Player, team: Team): void {
  const sal = expectedSalary(world, p, team.titleId, team.region) * clamp(rng.normal(1, 0.1), 0.75, 1.3);
  const years = rng.int(1, 3);
  p.contract = makeContract(world, team, sal, years);
  p.teamId = team.id;
  p.joinedDay = world.day - rng.int(0, 700);
  team.roster.push(p.id);
  p.career.push({ season: world.season, day: world.day, titleId: team.titleId, teamId: team.id, orgId: team.orgId, roleId: p.roles[0], type: 'start' });
}

/** Generate a full roster for a team around a target quality. */
export function populateTeam(ctx: GenCtx, team: Team, meanCA: number, leagueRegion: string, family: 'MOBA' | 'SHOOTER', importRate: number): void {
  const { rng, world } = ctx;
  const t = getTitle(team.titleId);
  const pop = world.titles[t.id].popularity;
  const regs = expandRegion(leagueRegion);
  const hubs = family === 'MOBA' ? ['KR', 'CN', 'VN', 'PH', 'ID', 'EUW'] : ['EUE', 'CIS', 'BR', 'NA', 'EUW', 'KR'];
  const pickNat = (): string => {
    if (rng.chance(importRate)) {
      const hubRegion = rng.weighted(hubs.filter((h) => !regs.includes(h)), (h) => pop[h] ?? 30);
      if (hubRegion) return pickNationality(rng, [hubRegion], family, pop);
    }
    return pickNationality(rng, regs, family, pop);
  };
  const youthBias = team.tier === 'academy' ? -3 : 0;
  for (const roleId of t.lineup) {
    const age = Math.round(clamp(rng.normal(22 + youthBias, 2.6), 16, 31));
    const p = generatePlayer(ctx, { titleId: t.id, roleId, nat: pickNat(), age, targetCA: clamp(rng.normal(meanCA, 2.8), 30, 95) });
    world.players[p.id] = p;
    signInitial(world, rng, p, team);
  }
  for (let i = 0; i < t.subs; i++) {
    const age = Math.round(clamp(rng.normal(19.5 + youthBias, 2.4), 15, 30));
    const roleId = rng.pick(t.lineup);
    const p = generatePlayer(ctx, { titleId: t.id, roleId, nat: pickNat(), age, targetCA: clamp(rng.normal(meanCA - 6, 3.5), 30, 90) });
    world.players[p.id] = p;
    signInitial(world, rng, p, team);
  }
}

export function staffTeam(ctx: GenCtx, team: Team, quality: number): void {
  const { rng, world } = ctx;
  const org = world.orgs[team.orgId];
  const roles: Array<'head-coach' | 'analyst' | 'assistant-coach' | 'academy-coach'> = team.tier === 'main' ? ['head-coach', 'analyst'] : ['academy-coach'];
  if (team.tier === 'main' && rng.chance(0.5)) roles.push('assistant-coach');
  for (const role of roles) {
    const nat = rng.chance(0.8) ? org.country : pickCountryIn(rng, REGIONS[rng.int(0, REGIONS.length - 1)].id);
    const s = generateStaff(ctx, role, nat, team.titleId, clamp(rng.normal(quality, 8), 25, 95));
    s.teamId = team.id;
    s.orgId = team.orgId;
    s.contract = makeContract(world, team, expectedStaffSalary(world, s, team.titleId, team.region), rng.int(1, 3));
    world.staff[s.id] = s;
    team.staff.push(s.id);
    s.career.push({ season: world.season, day: world.day, titleId: team.titleId, teamId: team.id, orgId: team.orgId, type: 'start' });
  }
}

function makeSponsors(ctx: GenCtx, org: Organisation): void {
  const { rng, world } = ctx;
  const n = 1 + Math.floor(org.rep / 35);
  for (let i = 0; i < n; i++) {
    const per = sponsorDealValue(world, org, n, rng);
    org.sponsors.push({ id: nextId(world, 'sp'), name: makeCompanyName(rng), perMonth: per, untilSeason: world.season + rng.int(0, 2) });
  }
}

export function createWorld(opts: NewWorldOptions): World {
  const today = opts.startDay ?? todayDayNum();
  const { y, m } = ymd(today);
  const season = m >= 11 ? y + 1 : y;
  const titleIds = opts.titles && opts.titles.length ? opts.titles : TITLES.map((t) => t.id);
  const world: World = {
    meta: {
      version: SAVE_VERSION,
      saveId: `save-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e6).toString(36)}`,
      name: opts.name,
      seed: opts.seed,
      created: Date.now(),
      updated: Date.now(),
      startDay: today,
      userOrgId: null,
      careerRole: opts.careerRole ?? 'coach-gm',
      difficulty: { ...PRESETS[opts.difficulty ?? 'standard'] },
      characterSnapshot: CHARACTER_SNAPSHOT.id,
      dataMode: opts.dataMode ?? 'fictional',
      ...(opts.dataMode === 'real' ? { rosterSnapshot: ROSTER_SNAPSHOT.id } : {}),
      detail: { fullDetailTitles: [], userMatches: true, internationals: true },
      worldSize: opts.worldSize,
      autosave: true,
      managerName: opts.managerName ?? 'Manager',
      titlesEnabled: titleIds,
    },
    day: today,
    season,
    titles: {},
    countryScene: {},
    players: {},
    staff: {},
    teams: {},
    orgs: {},
    leagues: {},
    comps: {},
    fixtures: {},
    schedule: {},
    matches: {},
    transfers: [],
    offers: [],
    news: [],
    inbox: [],
    awards: [],
    history: [],
    hof: [],
    retired: [],
    scouting: {},
    shortlist: [],
    counter: 0,
    processed: { lastDay: today, lastMonth: -1, lastWeek: -1, seasonRolled: [] },
    pendingUserMatch: null,
    scoutFocus: [],
    overrides: {},
    customFormats: {},
    draftPicks: [],
    negotiations: [],
    rivalries: {},
  };
  const rng = new RNG(`${opts.seed}|worldgen`);
  const ctx: GenCtx = { world, rng, handles: new Set() };
  const cfg = SIZE[opts.worldSize];

  for (const c of COUNTRIES) world.countryScene[c.code] = { moba: c.moba, shooter: c.shooter };

  for (const id of titleIds) {
    const pop = { ...TITLE_POPULARITY[id] };
    const globalPop = Object.values(pop).reduce((a, b) => a + b, 0) / Object.values(pop).length;
    world.titles[id] = { id, popularity: pop, globalPop, prestige: Math.round(globalPop), patches: [], active: true, revivalCount: 0, popHistory: [] };
    world.titles[id].patches.push(initialPatch(world, id));
  }

  // Leagues
  const orgNames = new Set<string>();
  const orgsByRegion: Record<string, Organisation[]> = {};
  const getOrg = (region: string, titleId: string, reuse: number): Organisation => {
    const pool = (orgsByRegion[region] ||= []).filter((o) => !o.teams.some((tid) => world.teams[tid].titleId === titleId && world.teams[tid].tier === 'main'));
    if (pool.length && rng.chance(reuse)) return rng.weighted(pool, (o) => Math.pow(o.rep, 2) * (1 + o.teams.length * 0.6));
    const o = createOrg(ctx, region, orgNames);
    orgsByRegion[region].push(o);
    return o;
  };

  const realMode = opts.dataMode === 'real';
  const realOrgs = new Map<string, Organisation>();
  const withReal = new Set(realMode ? realTitles() : []);
  for (const id of titleIds) {
    const t = getTitle(id);
    const pop = world.titles[id].popularity;
    let regions = planLeagueRegions(id, opts.worldSize, pop);
    if (withReal.has(id)) {
      // Real leagues and teams first; fictional leagues only for regions they do not cover
      // (and only in the larger world sizes, to keep the world a manageable size).
      const realOrg = (region: string): Organisation => createOrg(ctx, region, orgNames);
      const fill = (org: Organisation, league: League, mean: number): Team => {
        const team = createTeam(ctx, org, id, league.region, 'main', league, null);
        populateTeam(ctx, team, mean + rng.normal(0, 2), league.region, t.family, 0.1);
        staffTeam(ctx, team, 50 + rng.normal(0, 6));
        return team;
      };
      const { covered } = buildRealTitle(ctx, id, realOrgs, realOrg, fill);
      const keepFictional = opts.worldSize === 'standard' || opts.worldSize === 'large';
      regions = keepFictional ? regions.filter((lr) => !expandRegion(lr.region).some((r) => covered.has(r))).slice(0, 2) : [];
    }
    regions.forEach((lr, idx) => {
      const size = Math.max(6, Math.round(t.leagueSize * cfg.teamScale));
      const franchised = !!REGION_BY_ID[lr.region] && lr.strength >= 80 && t.family !== 'MOBA' ? rng.chance(0.6) : lr.strength >= 85 && rng.chance(0.4);
      const ln = leagueName(t.short, lr.region, 1, rng);
      const league: League = {
        id: nextId(world, 'l'),
        titleId: id,
        region: lr.region,
        tier: 1,
        name: ln.name,
        short: ln.short,
        teamIds: [],
        size,
        franchised,
        policy: defaultPolicy(id, lr.region, 1, franchised, t.teamSize, t.subs),
        promotion: 0,
        upperId: null,
        lowerId: null,
        fullDetail: false,
        founded: season - rng.int(0, 6),
      };
      world.leagues[league.id] = league;
      const scene = expandRegion(lr.region)
        .flatMap((r) => COUNTRIES.filter((c) => c.region === r))
        .reduce((acc, c, _, arr) => acc + (t.family === 'MOBA' ? c.moba : c.shooter) / arr.length, 0);
      const strength = 0.6 * lr.strength + 0.4 * scene;
      const leagueMean = 57 + 19 * (strength / 100);
      const importRate = REGION_BY_ID[lr.region] ? 0.08 : 0.15;
      const teamsT1: Team[] = [];
      for (let i = 0; i < size; i++) {
        const orgRegion = pickMemberRegion(rng, lr.region, pop);
        const org = getOrg(orgRegion, id, 0.5);
        const team = createTeam(ctx, org, id, lr.region, 'main', league, null);
        const teamMean = leagueMean + rng.normal(0, 2.4) + (org.rep - 40) * 0.04;
        populateTeam(ctx, team, teamMean, lr.region, t.family, importRate);
        staffTeam(ctx, team, 45 + strength * 0.25 + rng.normal(0, 6));
        teamsT1.push(team);
      }
      // Tier 2 for the strongest regions
      if (idx < cfg.tier2 && REGION_BY_ID[lr.region] && lr.strength >= 70) {
        const ln2 = leagueName(t.short, lr.region, 2, rng);
        const l2: League = {
          ...league,
          id: nextId(world, 'l'),
          tier: 2,
          name: ln2.name,
          short: ln2.short,
          teamIds: [],
          franchised: false,
          policy: defaultPolicy(id, lr.region, 2, false, t.teamSize, t.subs),
          promotion: franchised ? 0 : 1,
          upperId: league.id,
          lowerId: null,
        };
        league.lowerId = l2.id;
        league.promotion = franchised ? 0 : 1;
        world.leagues[l2.id] = l2;
        for (let i = 0; i < size; i++) {
          // Academy teams of tier-1 organisations or independent challengers.
          const parent = rng.chance(0.4) ? rng.pick(teamsT1.filter((x) => !world.orgs[x.orgId].teams.some((tid) => world.teams[tid].titleId === id && world.teams[tid].tier === 'academy'))) : undefined;
          let team: Team;
          if (parent) {
            team = createTeam(ctx, world.orgs[parent.orgId], id, lr.region, 'academy', l2, parent);
          } else {
            const org = getOrg(pickMemberRegion(rng, lr.region, pop), id, 0.35);
            team = createTeam(ctx, org, id, lr.region, 'main', l2, null);
          }
          populateTeam(ctx, team, leagueMean - 7 + rng.normal(0, 3), lr.region, t.family, importRate * 0.5);
          staffTeam(ctx, team, 40 + strength * 0.2 + rng.normal(0, 6));
        }
      }
    });
  }

  // Free agents and prospects per title
  for (const id of titleIds) {
    const t = getTitle(id);
    const pop = world.titles[id].popularity;
    const rostered = Object.values(world.teams).filter((x) => x.titleId === id).length * (t.teamSize + t.subs);
    const leagueRegions = Object.values(world.leagues).filter((l) => l.titleId === id).map((l) => l.region);
    const regs = leagueRegions.flatMap((r) => expandRegion(r));
    const meanLeague = 64;
    const fa = Math.round(rostered * 0.22);
    for (let i = 0; i < fa; i++) {
      const age = Math.round(clamp(rng.normal(22, 3.5), 16, 33));
      const p = generatePlayer(ctx, { titleId: id, nat: pickNationality(rng, regs.length ? regs : ['NA'], t.family, pop), age, targetCA: clamp(rng.normal(meanLeague - 6, 6), 35, 85) });
      world.players[p.id] = p;
    }
    const prospects = Math.round(rostered * 0.12);
    for (let i = 0; i < prospects; i++) {
      const age = rng.int(14, 17);
      const p = generatePlayer(ctx, { titleId: id, nat: pickNationality(rng, regs.length ? regs : ['NA'], t.family, pop), age, targetCA: clamp(rng.normal(47, 6), 30, 68), potentialBoost: rng.normal(4, 5) });
      world.players[p.id] = p;
    }
  }

  // Organisation-level staff, sponsors and finances
  for (const org of Object.values(world.orgs)) {
    const mainTitle = world.teams[org.teams[0]]?.titleId ?? null;
    for (const role of ['gm', 'scout'] as const) {
      const s = generateStaff(ctx, role, org.country, mainTitle, clamp(rng.normal(45 + org.rep * 0.3, 8), 25, 95));
      s.orgId = org.id;
      const team = world.teams[org.teams[0]];
      if (team) s.contract = makeContract(world, team, expectedStaffSalary(world, s, mainTitle, org.region), rng.int(1, 3));
      world.staff[s.id] = s;
    }
    makeSponsors(ctx, org);
    let monthly = 0;
    for (const tid of org.teams) {
      const team = world.teams[tid];
      let pay = 0;
      for (const pid of team.roster) pay += world.players[pid].contract?.salary ?? 0;
      for (const sid of team.staff) pay += world.staff[sid].contract?.salary ?? 0;
      team.salaryBudget = Math.round((pay * 1.18) / 1000) * 1000;
      monthly += pay / 12;
    }
    const income = org.sponsors.reduce((a, s) => a + s.perMonth, 0);
    org.balance = Math.round(monthly * rng.float(4, 10) + income * 3 + org.owner.wealth * 4000 * regionEconomy(org.region));
  }

  // Free-agent staff pool
  const staffPool = Math.round(Object.keys(world.teams).length * 0.25);
  for (let i = 0; i < staffPool; i++) {
    const tid = rng.pick(titleIds);
    const role = rng.pick(['head-coach', 'analyst', 'assistant-coach', 'scout', 'psychologist', 'performance-coach', 'academy-coach'] as const);
    const s = generateStaff(ctx, role, pickCountryIn(rng, rng.pick(REGIONS).id), tid, clamp(rng.normal(48, 10), 25, 85));
    world.staff[s.id] = s;
  }

  // Values, lineups, elo
  for (const p of Object.values(world.players)) p.value = marketValue(world, p);
  for (const team of Object.values(world.teams)) {
    team.lineup = autoLineup(world, team);
    const str = computeTeamStrength(world, team);
    team.elo = Math.round(1500 + (str - 65) * 18);
  }

  planSeason(world, world.season);
  ensureDraftPicks(world);
  ensureAudience(world);
  return world;
}

/** Make an organisation user-controlled and set up the career. */
export function setUserOrg(world: World, orgId: string): void {
  for (const o of Object.values(world.orgs)) o.user = false;
  const org = world.orgs[orgId];
  if (!org) throw new Error('Unknown organisation');
  org.user = true;
  world.meta.userOrgId = orgId;
  world.meta.mode = 'manager';
  for (const tid of org.teams) {
    const team = world.teams[tid];
    team.prefs.delegateLineup = true;
    team.prefs.delegateDraft = true;
    for (const pid of team.roster) world.scouting[pid] = 100;
  }
}

/** Convenience: first day of a season's competitive calendar. */
export function seasonStartDay(season: number): number {
  return dayFromYMD(season, 1, 15);
}

export { titlePopIn };

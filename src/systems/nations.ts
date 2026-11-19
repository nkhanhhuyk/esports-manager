/**
 * National teams and the biennial Nations Cup. Squads are selected from each country's best
 * players in the title at tournament start (club contracts are untouched); rosters are cleared
 * when the season rolls over. National teams live under a federation pseudo-organisation that
 * has no finances, market activity or league.
 */
import type { Organisation, Team, World } from '../model/types';
import { nextId } from '../model/ids';
import { COUNTRY_BY_CODE } from '../data/countries';
import { getTitle } from '../data/titles/catalogue';
import { autoLineup } from './lineup';
import { ageAt } from '../core/date';

export function federation(world: World): Organisation {
  let org = Object.values(world.orgs).find((o) => o.federation);
  if (org) return org;
  const id = nextId(world, 'o');
  org = {
    id,
    name: 'World Esports Federation',
    short: 'WEF',
    country: 'CH',
    region: 'EUW',
    founded: 2026,
    colors: ['#c9a227', '#1b2a41'],
    rep: 60,
    fans: 0,
    prestige: 0,
    owner: { name: 'Member nations', type: 'public', wealth: 50, patience: 99 },
    strategy: 'balanced',
    balance: 0,
    sponsors: [],
    ledger: [],
    monthly: [],
    debtMonths: 0,
    facilities: { training: 5, analytics: 5, academy: 1, equipment: 5, housing: 5, performance: 5 },
    teams: [],
    partners: [],
    names: [{ season: world.season, name: 'World Esports Federation', short: 'WEF' }],
    events: [],
    active: true,
    user: false,
    federation: true,
  };
  world.orgs[id] = org;
  return org;
}

/** Build (or refresh) national squads for the `count` strongest nations in a title. */
export function buildNationalTeams(world: World, titleId: string, count: number): string[] {
  const t = getTitle(titleId);
  const fed = federation(world);
  const byNat = new Map<string, Array<{ id: string; ca: number }>>();
  for (const p of Object.values(world.players)) {
    if (p.retired || p.titleId !== titleId || ageAt(p.birth, world.day) < t.minAge) continue;
    if (!byNat.has(p.nat)) byNat.set(p.nat, []);
    byNat.get(p.nat)!.push({ id: p.id, ca: p.ca });
  }
  const squadSize = t.teamSize + Math.min(2, t.subs);
  const job = world.meta.nationalJob;
  const ranked = [...byNat.entries()]
    .filter(([, ps]) => ps.length >= t.teamSize)
    .map(([cc, ps]) => {
      ps.sort((a, b) => b.ca - a.ca);
      const top = ps.slice(0, t.teamSize);
      return { cc, ps, score: top.reduce((a, x) => a + x.ca, 0) / top.length };
    })
    .sort((a, b) => b.score - a.score);
  const nations = ranked.slice(0, count);
  // The user's nation always qualifies (taking the last slot) if it can field a team.
  if (job && job.titleId === titleId && !nations.some((x) => x.cc === job.country)) {
    const mine = ranked.find((x) => x.cc === job.country);
    if (mine) nations[nations.length - 1] = mine;
  }
  const ids: string[] = [];
  for (const n of nations) {
    let team = Object.values(world.teams).find((x) => x.national === n.cc && x.titleId === titleId);
    if (!team) {
      const name = COUNTRY_BY_CODE[n.cc]?.name ?? n.cc;
      team = {
        id: nextId(world, 't'),
        orgId: fed.id,
        titleId,
        name: `${name}`,
        short: n.cc,
        tier: 'main',
        region: COUNTRY_BY_CODE[n.cc]?.region ?? 'NA',
        leagueId: null,
        parentTeamId: null,
        roster: [],
        staff: [],
        lineup: [],
        prefs: { style: t.styles[0], aggression: 55, draftPriority: 'comfort', pool: {}, bans: [], mapPrefs: {}, delegateLineup: true, delegateDraft: true, delegateTraining: true },
        training: { focus: 'teamwork', intensity: 40, scrimsPerWeek: 2, individual: {}, opponentPrep: true },
        chemistry: 45,
        elo: 1500,
        metaAdapt: 70,
        salaryBudget: 0,
        history: [],
        trophies: [],
        founded: world.season,
        active: true,
        scrimRating: 40,
        national: n.cc,
      } as Team;
      world.teams[team.id] = team;
      fed.teams.push(team.id);
    }
    const job = world.meta.nationalJob;
    const userJob = !!job && job.country === n.cc && job.titleId === titleId;
    if (userJob) {
      // The national coach's named squad first (if still eligible), topped up automatically.
      const eligible = new Set(n.ps.map((x) => x.id));
      const named = job.squad.filter((id) => eligible.has(id)).slice(0, squadSize);
      for (const x of n.ps) if (named.length < squadSize && !named.includes(x.id)) named.push(x.id);
      team.roster = named;
    } else team.roster = n.ps.slice(0, squadSize).map((x) => x.id);
    team.lineupSig = undefined;
    if (!userJob || team.prefs.delegateLineup || team.lineup.some((s) => !team.roster.includes(s.playerId)) || team.lineup.length !== t.teamSize) team.lineup = autoLineup(world, team);
    team.elo = Math.round(1500 + (n.score - 70) * 18);
    ids.push(team.id);
  }
  return ids;
}

/** Countries that can field a team in a title (for the national-coach job offer). */
export function eligibleNations(world: World, titleId: string): Array<{ cc: string; players: number; score: number }> {
  const t = getTitle(titleId);
  const byNat = new Map<string, number[]>();
  for (const p of Object.values(world.players)) {
    if (p.retired || p.titleId !== titleId || ageAt(p.birth, world.day) < t.minAge) continue;
    if (!byNat.has(p.nat)) byNat.set(p.nat, []);
    byNat.get(p.nat)!.push(p.ca);
  }
  return [...byNat.entries()]
    .filter(([, v]) => v.length >= t.teamSize)
    .map(([cc, v]) => {
      v.sort((a, b) => b - a);
      return { cc, players: v.length, score: v.slice(0, t.teamSize).reduce((a, b) => a + b, 0) / t.teamSize };
    })
    .sort((a, b) => b.score - a.score);
}

/** Clear national squads (called at rollover so rosters never reference stale players). */
export function clearNationalSquads(world: World): void {
  for (const t of Object.values(world.teams)) {
    if (!t.national) continue;
    t.roster = [];
    t.lineup = [];
    t.lineupSig = undefined;
  }
}

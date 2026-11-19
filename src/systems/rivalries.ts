/**
 * Rivalries and institutional memory (master prompt §9/§14; source "Rivalries and institutional
 * memory").
 *
 * A rivalry links two divisions of the same title and is built only from recorded events:
 * high-stakes series (playoffs, finals, international events), series that went the distance,
 * upsets, star transfers between the two clubs, and — for leagues spanning several countries —
 * two organisations from the same country (a derby). An ordinary league meeting never starts one.
 * Intensity fades when the teams stop meeting. Rivalry intensity raises audiences, fan reactions
 * and match pressure (which tests composure); it never changes a player's ability.
 *
 * Proposed constants (editable):
 *   encounter heat = stakes (league 1, playoff 4, final 9; ×1.4 at international events)
 *                    + decider (+2.5 in playoffs/finals, +1 otherwise) + playoff upset (+1.5)
 *                    + clash of contenders (both Elo > 1580, league only: +0.8), ×1.3 for a derby
 *   a rivalry record starts only when one encounter is worth ≥ 2.5 heat
 *   intensity += heat × (1 − intensity/125), capped at 100
 *   monthly fade ×0.985 while the teams keep meeting, ×0.94 after 120 days without contact
 *   labels: Emerging ≥ 15, Rivalry ≥ 30 (announced), Fierce ≥ 50, Historic ≥ 75 (announced)
 */
import { seasonEndDay } from '../competition/season';
import type { Fixture, Player, Rivalry, Team, World } from '../model/types';
import { addInbox, addNews, isUserTeam } from './news';

export const NOTABLE = 30;
export const HISTORIC = 75;
const START_HEAT = 2.5;
const MOMENTS_KEEP = 5;
/** Records that never became notable are forgotten below this intensity. */
const FORGET_BELOW = 6;
const IDLE_DAYS = 120;
const NOTABLE_KEEP_PER_TITLE = 40;

export const RIVALRY_LEVELS = [
  { min: HISTORIC, label: 'Historic' },
  { min: 50, label: 'Fierce' },
  { min: NOTABLE, label: 'Rivalry' },
  { min: 15, label: 'Emerging' },
] as const;

export function rivalryLabel(intensity: number): string | null {
  return RIVALRY_LEVELS.find((l) => intensity >= l.min)?.label ?? null;
}

export function rivalryKey(a: string, b: string): string {
  return a < b ? `${a}|${b}` : `${b}|${a}`;
}

export function getRivalry(world: World, a: string, b: string): Rivalry | undefined {
  return world.rivalries?.[rivalryKey(a, b)];
}

/** Rivalries of a team at or above `min` intensity, strongest first. */
export function rivalriesOf(world: World, teamId: string, min = 15): Rivalry[] {
  return Object.values(world.rivalries ?? {})
    .filter((r) => (r.teams[0] === teamId || r.teams[1] === teamId) && (r.intensity >= min || (min <= 0 && r.since !== undefined)))
    .sort((x, y) => y.intensity - x.intensity);
}

/** Extra big-match pressure for a meeting of rivals (0..0.4). Tests composure, not skill. */
export function rivalryPressure(world: World, teams: string[]): number {
  if (teams.length !== 2) return 0;
  const r = getRivalry(world, teams[0], teams[1]);
  return r && r.intensity >= 15 ? Math.min(0.4, r.intensity / 250) : 0;
}

function round1(x: number): number {
  return Math.round(x * 10) / 10;
}

function heat(r: Rivalry, amount: number): void {
  r.intensity = round1(Math.min(100, r.intensity + amount * (1 - r.intensity / 125)));
  r.peak = Math.max(r.peak, r.intensity);
}

/** Two organisations from the same country in a league that spans several countries. */
export function isDerby(world: World, ta: Team, tb: Team): boolean {
  if (!ta.leagueId || ta.leagueId !== tb.leagueId) return false;
  const oa = world.orgs[ta.orgId];
  const ob = world.orgs[tb.orgId];
  if (!oa || !ob || oa.id === ob.id || oa.country !== ob.country) return false;
  const league = world.leagues[ta.leagueId];
  const countries = new Set((league?.teamIds ?? []).map((id) => world.orgs[world.teams[id]?.orgId ?? '']?.country));
  return countries.size > 1;
}

function newRivalry(world: World, a: string, b: string, titleId: string, derby: boolean, day: number): Rivalry {
  const teams: [string, string] = a < b ? [a, b] : [b, a];
  const r: Rivalry = { id: rivalryKey(a, b), titleId, teams, intensity: 0, peak: 0, meetings: 0, wins: [0, 0], finals: 0, playoffs: 0, transfers: 0, derby, firstSeason: world.season, lastDay: day, moments: [] };
  (world.rivalries ??= {})[r.id] = r;
  return r;
}

function pushMoment(r: Rivalry, m: Rivalry['moments'][number]): void {
  r.moments.push(m);
  if (r.moments.length > MOMENTS_KEEP) r.moments.splice(0, r.moments.length - MOMENTS_KEEP);
}

function reasons(r: Rivalry): string {
  const parts = [
    r.finals ? `${r.finals} final${r.finals > 1 ? 's' : ''}` : '',
    r.playoffs ? `${r.playoffs} playoff series` : '',
    r.transfers ? `${r.transfers} high-profile transfer${r.transfers > 1 ? 's' : ''}` : '',
    r.derby ? 'a same-country derby in a multi-country league' : '',
  ].filter(Boolean);
  return parts.length ? parts.join(', ') : 'repeated hard-fought meetings';
}

function announce(world: World, r: Rivalry, kind: 'born' | 'historic'): void {
  const ta = world.teams[r.teams[0]];
  const tb = world.teams[r.teams[1]];
  if (!ta || !tb) return;
  const title = kind === 'born' ? `A rivalry is born: ${ta.name} vs ${tb.name}` : `${ta.name} vs ${tb.name} is now a historic rivalry`;
  const body = `Built on ${reasons(r)} — head-to-head ${ta.short} ${r.wins[0]}–${r.wins[1]} ${tb.short} in ${r.meetings} tracked meeting${r.meetings === 1 ? '' : 's'} since ${r.firstSeason}. Rivalry matches draw bigger audiences, move supporters more and add pressure on the players; they do not change anyone's ability.`;
  addNews(world, { kind: 'rivalry', title, body, titleId: r.titleId, refs: [{ type: 'team', id: ta.id }, { type: 'team', id: tb.id }], importance: kind === 'born' ? 3 : 4 });
  if (isUserTeam(world, ta.id) || isUserTeam(world, tb.id)) addInbox(world, { from: 'Media', subject: title, body, kind: 'rivalry' });
}

export interface EncounterInput {
  a: string;
  b: string;
  titleId: string;
  day: number;
  season: number;
  winner: string;
  /** Base stakes: league 1, playoff 4, final 9 (international ×1.4). */
  stakes: number;
  final: boolean;
  playoff: boolean;
  decider: boolean;
  upset: boolean;
  contenders: boolean;
  derby: boolean;
  text: string;
  fixtureId?: string;
}

/** Add one recorded encounter (also used to seed old saves from their history). */
export function addEncounter(world: World, e: EncounterInput, quiet = false): Rivalry | null {
  let amount = e.stakes + (e.decider ? (e.stakes >= 4 ? 2.5 : 1) : 0) + (e.upset && e.stakes >= 4 ? 1.5 : 0) + (e.contenders && e.stakes < 4 ? 0.8 : 0);
  if (e.derby) amount *= 1.3;
  let r = world.rivalries?.[rivalryKey(e.a, e.b)];
  if (!r) {
    // An ordinary meeting — a single routine defeat — does not start a rivalry.
    if (amount < START_HEAT) return null;
    r = newRivalry(world, e.a, e.b, e.titleId, e.derby, e.day);
  }
  r.meetings++;
  r.wins[r.teams[0] === e.winner ? 0 : 1]++;
  if (e.final) r.finals++;
  else if (e.playoff) r.playoffs++;
  r.derby ||= e.derby;
  r.lastDay = Math.max(r.lastDay, e.day);
  const before = r.intensity;
  const peakBefore = r.peak;
  heat(r, amount);
  if (e.final || e.playoff || (e.upset && e.decider)) pushMoment(r, { day: e.day, season: e.season, kind: e.final ? 'final' : e.playoff ? 'playoff' : 'upset', text: e.text, fixtureId: e.fixtureId });
  else if (e.derby && r.meetings === 1) pushMoment(r, { day: e.day, season: e.season, kind: 'derby', text: e.text, fixtureId: e.fixtureId });
  if (before < NOTABLE && r.intensity >= NOTABLE && r.since === undefined) {
    r.since = e.season;
    if (!quiet) announce(world, r, 'born');
  } else if (peakBefore < HISTORIC && r.intensity >= HISTORIC && !quiet) announce(world, r, 'historic');
  return r;
}

/** Record a finished two-team series. `preElo` is each side's rating before the result. */
export function recordEncounter(world: World, f: Fixture, winnerIdx: number, preElo: [number, number], quiet = false): Rivalry | null {
  if (f.teams.length !== 2) return null;
  const ta = world.teams[f.teams[0]];
  const tb = world.teams[f.teams[1]];
  if (!ta || !tb || ta.national || tb.national || ta.orgId === tb.orgId) return null;
  const comp = world.comps[f.compId];
  const intl = !!comp && (comp.tier === 'worlds' || comp.tier === 'international');
  const final = f.formatKey === 'final';
  const playoff = f.formatKey === 'playoff';
  const stakes = (final ? 9 : playoff ? 4 : 1) * (intl ? 1.4 : 1);
  const need = Math.floor(f.format.n / 2) + 1;
  const score = f.score ?? [0, 0];
  const decider = f.format.n >= 3 && Math.min(...score) === need - 1;
  const winner = f.teams[winnerIdx];
  const loser = f.teams[1 - winnerIdx];
  const upset = preElo[1 - winnerIdx] - preElo[winnerIdx] > 120;
  const W = world.teams[winner];
  const L = world.teams[loser];
  const where = comp ? `${comp.short}${final ? ' final' : playoff ? ' playoffs' : ''}` : '';
  return addEncounter(world, {
    a: ta.id,
    b: tb.id,
    titleId: f.titleId,
    day: f.day,
    season: world.season,
    winner,
    stakes,
    final,
    playoff,
    decider,
    upset,
    contenders: Math.min(...preElo) > 1580,
    derby: isDerby(world, ta, tb),
    text: `${W.short} ${Math.max(...score)}–${Math.min(...score)} ${L.short}${where ? ` · ${where}` : ''}${upset ? ' (upset)' : ''}`,
    fixtureId: f.id,
  }, quiet);
}

/** A star moving directly between two clubs of the same title adds heat (and can start a rivalry). */
export function rivalryTransfer(world: World, p: Player, from: Team | null, to: Team, fee: number): Rivalry | null {
  if (!from || from.titleId !== to.titleId || from.orgId === to.orgId || from.national || to.national) return null;
  let r = getRivalry(world, from.id, to.id);
  const star = p.rep >= 65 || fee >= 250000;
  if (!r && !star) return null;
  if (!r && p.rep < 65) return null;
  r ??= newRivalry(world, from.id, to.id, to.titleId, isDerby(world, from, to), world.day);
  const peakBefore = r.peak;
  const before = r.intensity;
  r.transfers++;
  heat(r, (star ? 5 : 2) * (r.intensity >= NOTABLE ? 1.4 : 1));
  pushMoment(r, { day: world.day, season: world.season, kind: 'transfer', text: `${p.handle} leaves ${from.short} for ${to.short}${fee ? ` ($${Math.round(fee / 1000)}K)` : ''}` });
  if (before < NOTABLE && r.intensity >= NOTABLE && r.since === undefined) {
    r.since = world.season;
    announce(world, r, 'born');
  } else if (peakBefore < HISTORIC && r.intensity >= HISTORIC) announce(world, r, 'historic');
  return r;
}

/** Monthly: rivalries fade, faster once the teams stop meeting; minor ones are forgotten. */
export function rivalriesMonthly(world: World): void {
  const all = world.rivalries;
  if (!all) return;
  const lowNotable: Record<string, Rivalry[]> = {};
  for (const [k, r] of Object.entries(all)) {
    const idle = world.day - r.lastDay > IDLE_DAYS;
    r.intensity = round1(r.intensity * (idle ? 0.94 : 0.985));
    const gone = !world.teams[r.teams[0]] || !world.teams[r.teams[1]];
    if (r.since === undefined && (r.intensity < FORGET_BELOW || gone)) {
      delete all[k];
      continue;
    }
    if (r.intensity < 5) (lowNotable[r.titleId] ??= []).push(r);
  }
  // Keep the most memorable faded rivalries per title (institutional memory) and drop the rest.
  for (const list of Object.values(lowNotable)) {
    if (list.length <= NOTABLE_KEEP_PER_TITLE) continue;
    list.sort((a, b) => b.peak - a.peak || b.lastDay - a.lastDay);
    for (const r of list.slice(NOTABLE_KEEP_PER_TITLE)) delete all[r.id];
  }
}

/**
 * Seed rivalries for a save created before they existed, from what the save recorded: past
 * finals (season history: champion vs runner-up) and this season's finished series. Quiet (no
 * news); older seasons are faded as if the months had passed.
 */
export function seedRivalries(world: World): void {
  world.rivalries ??= {};
  for (const h of [...world.history].sort((a, b) => a.season - b.season)) {
    for (const c of h.champions) {
      if (!c.runnerUpId || !world.teams[c.teamId] || !world.teams[c.runnerUpId]) continue;
      const ta = world.teams[c.teamId];
      const tb = world.teams[c.runnerUpId];
      if (ta.national || tb.national || ta.orgId === tb.orgId || ta.titleId !== tb.titleId) continue;
      const intl = c.tier === 'worlds' || c.tier === 'international';
      addEncounter(world, { a: ta.id, b: tb.id, titleId: c.titleId, day: Math.min(world.day, seasonEndDay(h.season)), season: h.season, winner: ta.id, stakes: 9 * (intl ? 1.4 : 1), final: true, playoff: false, decider: false, upset: false, contenders: false, derby: isDerby(world, ta, tb), text: `${ta.short} beat ${tb.short} · ${c.compName} final (${h.season})` }, true);
    }
    // Fade what came before this season's end, as if a year had passed.
    for (const r of Object.values(world.rivalries)) if (h.season < world.season) r.intensity = round1(r.intensity * Math.pow(0.985, 12));
  }
  const done = Object.values(world.fixtures)
    .filter((f) => f.status === 'done' && f.teams.length === 2 && f.winner && f.score)
    .sort((a, b) => a.day - b.day);
  for (const f of done) {
    const wi = f.teams.indexOf(f.winner!);
    if (wi < 0) continue;
    const elo: [number, number] = [world.teams[f.teams[0]]?.elo ?? 1500, world.teams[f.teams[1]]?.elo ?? 1500];
    recordEncounter(world, f, wi, elo, true);
  }
  for (const r of Object.values(world.rivalries)) r.lastDay = Math.min(r.lastDay, world.day);
}

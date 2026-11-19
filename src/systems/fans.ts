/**
 * Supporter segments (master prompt §9/§14; source "Fans have different reasons to care").
 *
 * Every organisation's followers are split into six segments that care about different things
 * and react only to relevant, recorded events (series results, trophies, signings, departures,
 * academy debuts, rivalry meetings). Reach (followers) and conversion (paying supporters) are
 * stored separately: a segment's paying supporters = size × conversion × (0.5 + mood/100).
 * Fans affect merchandise income, sponsorship deal values, audiences and (slightly) reputation;
 * they never change a player's ability.
 *
 * Proposed constants (editable): conversion per segment below; SPEND per paying supporter per
 * month (× region economy) is calibrated so a newly generated organisation earns about what the
 * previous single-number formula paid (followers × 0.012 × economy).
 */
import { clamp } from '../core/math';
import { monthKey } from '../core/date';
import type { Competition, FanSegmentId, Fanbase, Fixture, Organisation, Player, Rivalry, Team, World } from '../model/types';
import { addInbox } from './news';
import { getRivalry, NOTABLE, seedRivalries } from './rivalries';

export interface SegmentDef {
  id: FanSegmentId;
  label: string;
  cares: string;
  /** Share of the segment that pays (memberships, merchandise) at neutral mood. */
  conv: number;
}

export const FAN_SEGMENTS: SegmentDef[] = [
  { id: 'local', label: 'Local loyalists', cares: 'Identity, home-country players and rivalry results; wary of glamour signings and of favourites leaving', conv: 0.06 },
  { id: 'winning', label: 'Winning-focused', cares: 'Results and trophies; they arrive during success and drift away in slumps', conv: 0.025 },
  { id: 'player', label: 'Player fans', cares: 'Star players — they arrive and leave with them', conv: 0.035 },
  { id: 'style', label: 'Style enthusiasts', cares: 'Aggressive, entertaining play and convincing wins', conv: 0.03 },
  { id: 'community', label: 'Youth & community', cares: 'Academy graduates getting their chance; youth development', conv: 0.05 },
  { id: 'legacy', label: 'Legacy supporters', cares: 'Finals, trophies, history and rivalry meetings', conv: 0.08 },
];
const SEG_IDS = FAN_SEGMENTS.map((s) => s.id);
export const SEGMENT_BY_ID = Object.fromEntries(FAN_SEGMENTS.map((s) => [s.id, s])) as Record<FanSegmentId, SegmentDef>;
export const SPEND = 0.25;
const LOG_USER = 30;
const LOG_AI = 5;
const HISTORY_MONTHS = 24;

function round1(x: number): number {
  return Math.round(x * 10) / 10;
}

/** Initial segment mix from what the organisation is: age, reputation, prestige, academy, strategy. */
export function initFanbase(org: Organisation, year: number): Fanbase {
  const age = Math.max(0, year - org.founded);
  const w: Record<FanSegmentId, number> = {
    local: 0.26 + Math.min(0.08, age * 0.006),
    winning: 0.16 + org.rep / 400,
    player: 0.13,
    style: 0.1,
    community: 0.06 + (org.facilities?.academy ?? 3) / 120 + (org.strategy === 'youth' ? 0.04 : 0),
    legacy: 0.06 + Math.min(0.1, age * 0.008) + org.prestige / 600,
  };
  const tot = SEG_IDS.reduce((a, k) => a + w[k], 0);
  const mood = round1(clamp(50 + (org.rep - 40) / 8, 35, 65));
  const seg = {} as Fanbase['seg'];
  let assigned = 0;
  for (const k of SEG_IDS) {
    const size = Math.round((org.fans * w[k]) / tot);
    seg[k] = { size, mood };
    assigned += size;
  }
  seg.local.size += org.fans - assigned;
  return { seg, buzz: 20, log: [] };
}

/** The organisation's fanbase (created on first use for orgs that predate segments). */
export function fanbaseOf(world: World, org: Organisation): Fanbase | null {
  if (org.federation) return null;
  org.fanbase ??= initFanbase(org, world.season);
  return org.fanbase;
}

function total(fb: Fanbase): number {
  return SEG_IDS.reduce((a, k) => a + fb.seg[k].size, 0);
}

/** Paying supporters of a segment (conversion depends on mood). */
export function payingOf(fb: Fanbase, id: FanSegmentId): number {
  const s = fb.seg[id];
  return s.size * SEGMENT_BY_ID[id].conv * (0.5 + s.mood / 100);
}

export function payingSupporters(fb: Fanbase): number {
  return Math.round(SEG_IDS.reduce((a, k) => a + payingOf(fb, k), 0));
}

/** Follower-weighted mood 0..100. */
export function overallMood(fb: Fanbase): number {
  const t = total(fb);
  if (!t) return 50;
  return SEG_IDS.reduce((a, k) => a + fb.seg[k].mood * fb.seg[k].size, 0) / t;
}

/** Monthly merchandise & membership income. */
export function merchandiseRevenue(org: Organisation, econ: number): number {
  if (!org.fanbase) return Math.round(org.fans * 0.012 * econ);
  return Math.round(payingSupporters(org.fanbase) * SPEND * econ);
}

/** Sponsors pay more for an engaged audience: 0.85 (mood 0) … 1.0 (50) … 1.15 (100). */
export function fanCommercialFactor(org: Organisation): number {
  return org.fanbase ? 0.85 + (0.3 * overallMood(org.fanbase)) / 100 : 1;
}

function pushLog(world: World, org: Organisation, fb: Fanbase, seg: FanSegmentId, d: number, note: string): void {
  fb.log.push({ day: world.day, seg, d: round1(d), note });
  const keep = org.user ? LOG_USER : LOG_AI;
  if (fb.log.length > keep) fb.log.splice(0, fb.log.length - keep);
}

/** Move a segment's mood (diminishing near the extremes). Notable reactions are logged. */
export function nudge(world: World, org: Organisation, fb: Fanbase, seg: FanSegmentId, d: number, note?: string): void {
  if (!d) return;
  const s = fb.seg[seg];
  const k = d > 0 ? (100 - s.mood) / 50 : s.mood / 50;
  s.mood = round1(clamp(s.mood + d * k, 0, 100));
  if (note && Math.abs(d) >= 2.5) pushLog(world, org, fb, seg, d, note);
}

/** Add (or remove) followers in a segment; keeps `org.fans` equal to the segment total. */
export function grow(org: Organisation, fb: Fanbase, seg: FanSegmentId, delta: number): void {
  const s = fb.seg[seg];
  const nd = Math.max(10 - s.size, Math.round(delta));
  s.size += nd;
  org.fans = Math.max(100, total(fb));
}

function mainDivisions(world: World, org: Organisation): number {
  return Math.max(1, org.teams.filter((id) => world.teams[id]?.active && world.teams[id]?.tier === 'main').length);
}

/** Came through this organisation's academy (or its draft). */
export function isHomegrown(world: World, p: Player, orgId: string): boolean {
  return p.career.some((e) => e.orgId === orgId && (e.type === 'promoted' || e.type === 'draft' || (!!e.teamId && world.teams[e.teamId]?.tier !== 'main' && world.teams[e.teamId]?.orgId === orgId)));
}

/** Personal followers a player brings: (fame − 40)² × 15 (fame 50 → 1.5K, 70 → 13.5K, 90 → 37.5K). */
export function playerFollowing(p: Player): number {
  const f = Math.max(0, p.rep - 40);
  return Math.round(f * f * 15);
}

/** Estimated peak viewers of a series (interest only — not paying spectators). */
export function seriesAudience(world: World, f: Fixture, rivalry: Rivalry | null | undefined): number {
  const comp = world.comps[f.compId];
  const fans = f.teams.reduce((a, id) => a + (world.orgs[world.teams[id]?.orgId ?? '']?.fans ?? 0), 0);
  const stage = f.formatKey === 'final' ? 4 : f.formatKey === 'playoff' ? 2.2 : 1;
  const event = comp?.tier === 'worlds' ? 2.5 : comp?.tier === 'international' ? 1.8 : comp?.tier === 'challenger' ? 0.4 : 1;
  const pop = world.titles[f.titleId]?.globalPop ?? 50;
  const riv = 1 + (rivalry?.intensity ?? 0) / 80;
  return Math.round(((fans * 0.05 + 2000) * stage * event * riv * (0.4 + pop / 100)) / 100) * 100;
}

/**
 * React to a finished series: winning-focused fans follow results, local and legacy supporters
 * care most about rivalry meetings and finals, style enthusiasts about how the team plays, the
 * community about academy graduates' debuts.
 */
export function fansOnSeries(world: World, f: Fixture, winnerIdx: number, rivalry: Rivalry | null | undefined, debuts: Record<string, string[]>): void {
  const comp = world.comps[f.compId];
  const intl = !!comp && (comp.tier === 'worlds' || comp.tier === 'international');
  const stakes = (f.formatKey === 'final' ? 3 : f.formatKey === 'playoff' ? 1.6 : 0.6) * (intl ? 1.5 : 1);
  const score = f.score ?? [0, 0];
  const need = Math.floor(f.format.n / 2) + 1;
  const swept = f.format.n >= 3 && Math.min(...score) === 0;
  const decider = f.format.n >= 3 && Math.min(...score) === need - 1;
  const riv = rivalry && rivalry.intensity >= 15 ? 1 + rivalry.intensity / 50 : 1;
  for (let i = 0; i < f.teams.length && i < 2; i++) {
    const team = world.teams[f.teams[i]];
    const opp = world.teams[f.teams[1 - i]];
    if (!team || !opp || team.national) continue;
    const org = world.orgs[team.orgId];
    const fb = org && fanbaseOf(world, org);
    if (!org || !fb) continue;
    const w = (team.tier === 'main' ? 1 : 0.3) / Math.sqrt(mainDivisions(world, org));
    const won = i === winnerIdx;
    const sgn = won ? 1 : -1.1;
    const what = `${won ? 'Beat' : 'Lost to'} ${riv > 1 ? 'rivals ' : ''}${opp.short}${f.formatKey === 'final' ? ` in the ${comp?.short ?? ''} final` : f.formatKey === 'playoff' ? ' in the playoffs' : ''}`;
    nudge(world, org, fb, 'winning', sgn * (1 + stakes * 1.5) * w, what);
    nudge(world, org, fb, 'local', sgn * (0.4 + stakes * 0.4) * riv * w, what);
    nudge(world, org, fb, 'legacy', sgn * (0.2 + stakes * 0.8) * riv * w, what);
    nudge(world, org, fb, 'player', sgn * 0.3 * w);
    nudge(world, org, fb, 'community', sgn * 0.2 * w);
    const style = ((team.prefs.aggression - 50) / 50) * 0.5 + (won && swept ? 0.8 : 0) + (decider ? 0.5 : 0) - (!won && swept ? 0.8 : 0);
    nudge(world, org, fb, 'style', style * w, style > 0 ? 'Entertaining series' : 'Flat performance');
    for (const pid of debuts[team.id] ?? []) {
      const p = world.players[pid];
      if (!p) continue;
      nudge(world, org, fb, 'community', 4, `${p.handle} debuts for ${team.short} (academy graduate)`);
      nudge(world, org, fb, 'local', 1.2);
    }
    fb.buzz = round1(Math.min(100, fb.buzz + (stakes * 2 + (riv - 1) * 4) * w));
  }
}

/** Placement in a finished competition: followers grow or shrink with results; titles delight. */
export function fansOnPlacement(world: World, comp: Competition, teamId: string, place: number, repGain: number): void {
  const team = world.teams[teamId];
  const org = team ? world.orgs[team.orgId] : null;
  const fb = org && fanbaseOf(world, org);
  if (!team || !org || !fb || team.national) return;
  const change = (org.fans * repGain) / 60;
  if (change > 0) {
    grow(org, fb, 'winning', change * 0.55);
    grow(org, fb, 'legacy', change * 0.25);
    grow(org, fb, 'style', change * 0.2);
  } else if (change < 0) {
    grow(org, fb, 'winning', change * 0.75);
    grow(org, fb, 'style', change * 0.25);
  }
  if (place !== 0) return;
  const p = clamp(comp.prestige / 60, 0.3, 1.7);
  const what = `${team.short} won ${comp.name}`;
  nudge(world, org, fb, 'winning', 6 * p, what);
  nudge(world, org, fb, 'legacy', 5 * p, what);
  nudge(world, org, fb, 'local', 3 * p, what);
  nudge(world, org, fb, 'player', 2 * p);
  nudge(world, org, fb, 'style', 2 * p);
  nudge(world, org, fb, 'community', 1 * p);
  fb.buzz = round1(Math.min(100, fb.buzz + 10 * p));
}

/**
 * A player joins `to` and/or leaves `from`. Stars bring followers and excite winning-focused fans
 * but can frustrate identity-minded supporters; favourites who leave take followers with them;
 * moves between rivals sting most.
 */
export function fansOnTransfer(world: World, p: Player, from: Team | null, to: Team | null, type: string, fee: number, tenureDays: number): void {
  const internal = !!from && !!to && from.orgId === to.orgId;
  if (type === 'loan-return' || internal) {
    if (internal && type === 'promotion' && to && to.tier === 'main') {
      const org = world.orgs[to.orgId];
      const fb = org && fanbaseOf(world, org);
      if (org && fb) nudge(world, org, fb, 'community', 2.5, `${p.handle} promoted from the academy`);
    }
    return;
  }
  const rival = from && to ? getRivalry(world, from.id, to.id) : undefined;
  const bitter = !!rival && rival.intensity >= NOTABLE;
  if (to && !to.national) {
    const org = world.orgs[to.orgId];
    const fb = org && fanbaseOf(world, org);
    if (org && fb) {
      if (p.rep >= 55) {
        nudge(world, org, fb, 'winning', (p.rep - 50) / 8, `Signed ${p.handle}${fee ? ` for $${Math.round(fee / 1000)}K` : ''}`);
        fb.buzz = round1(Math.min(100, fb.buzz + (p.rep - 50) / 3));
        if (p.rep >= 65 && p.nat !== org.country) {
          nudge(world, org, fb, 'local', -1.5 - (org.strategy === 'youth' ? 1.5 : 0), `Glamour signing ${p.handle} worries identity-minded fans`);
          nudge(world, org, fb, 'community', -1);
        }
      }
      if (p.nat === org.country && p.rep >= 40) nudge(world, org, fb, 'local', 1.5, `Home-country signing ${p.handle}`);
      if (bitter && from) {
        nudge(world, org, fb, 'local', -3, `Signed ${p.handle} from rivals ${from.short}`);
        nudge(world, org, fb, 'legacy', -2);
      }
    }
  }
  if (from && !from.national) {
    const org = world.orgs[from.orgId];
    const fb = org && fanbaseOf(world, org);
    if (!org || !fb) return;
    const favourite = p.rep >= 55 || tenureDays >= 730;
    if (favourite) {
      nudge(world, org, fb, 'local', -(1 + Math.min(3, tenureDays / 365)), `${p.handle} left ${from.short}`);
      if (p.rep > 50) nudge(world, org, fb, 'winning', -(p.rep - 50) / 15);
    }
    if (isHomegrown(world, p, org.id)) nudge(world, org, fb, 'community', -2.5, `Academy graduate ${p.handle} left`);
    if (bitter && to) {
      nudge(world, org, fb, 'local', -4, `${p.handle} joined rivals ${to.short}`);
      nudge(world, org, fb, 'legacy', -3);
    }
  }
}

/** A retiring player: legacy supporters honour the career; his personal followers drift away. */
export function fansOnRetirement(world: World, p: Player, team: Team | null): void {
  if (!team || team.national) return;
  const org = world.orgs[team.orgId];
  const fb = org && fanbaseOf(world, org);
  if (!org || !fb) return;
  if (p.rep >= 55 || isHomegrown(world, p, org.id)) nudge(world, org, fb, 'legacy', 1.5, `${p.handle} retires`);
}

const DRIFT: Record<FanSegmentId, number> = { winning: 0.025, style: 0.01, local: 0.004, community: 0.006, legacy: 0.003, player: 0 };
const CHURN: Record<FanSegmentId, number> = { winning: 0.006, style: 0.005, local: 0.001, community: 0.001, legacy: 0.0005, player: 0 };

/** Followers the organisation's current players bring with them (main rosters in full, others 30%). */
export function starFollowing(world: World, org: Organisation): number {
  let s = 0;
  for (const tid of org.teams) {
    const team = world.teams[tid];
    if (!team?.active) continue;
    const w = team.tier === 'main' ? 0.5 : 0.15;
    for (const pid of team.roster) {
      const p = world.players[pid];
      if (p && p.rep > 40) s += playerFollowing(p) * w;
    }
  }
  return Math.round(s);
}

function sync(org: Organisation, fb: Fanbase): void {
  // The database editor may change org.fans directly: rescale the segments to match.
  const t = total(fb);
  if (t > 0 && Math.abs(t - org.fans) > Math.max(50, t * 0.01)) {
    const k = org.fans / t;
    for (const id of SEG_IDS) fb.seg[id].size = Math.max(10, Math.round(fb.seg[id].size * k));
  }
  org.fans = Math.max(100, total(fb));
}

/**
 * Monthly: moods drift back toward a reputation-based baseline, segment sizes follow their mood
 * (bandwagon fans fastest, loyalists slowest), attention converts into a few new followers and
 * decays, and overall sentiment nudges reputation slightly.
 */
export function fansMonthly(world: World): void {
  for (const org of Object.values(world.orgs)) {
    if (!org.active || org.federation) continue;
    const fb = fanbaseOf(world, org)!;
    sync(org, fb);
    const base = clamp(50 + (org.rep - 40) / 6, 35, 70);
    for (const id of SEG_IDS) {
      const s = fb.seg[id];
      s.mood = round1(s.mood + (base - s.mood) * 0.06);
    }
    // Sizes follow mood relative to what this club's supporters normally feel (big clubs' fans are
    // happier by default without growing forever); casual segments churn fastest.
    for (const id of SEG_IDS) {
      if (id === 'player') continue;
      grow(org, fb, id, fb.seg[id].size * (((fb.seg[id].mood - base) / 50) * DRIFT[id] - CHURN[id]));
    }
    // Player fans follow the stars on the roster: they arrive and leave with them (15%/month).
    const stars = starFollowing(world, org);
    fb.playerBase ??= Math.round(Math.max(fb.seg.player.size * 0.3, fb.seg.player.size - stars));
    grow(org, fb, 'player', (fb.playerBase * (0.5 + fb.seg.player.mood / 100) + stars - fb.seg.player.size) * 0.15);
    grow(org, fb, 'community', fb.seg.community.size * (((org.facilities.academy ?? 3) - 5) / 5) * 0.003);
    const newcomers = org.fans * (fb.buzz / 100) * 0.004;
    grow(org, fb, 'winning', newcomers * 0.65);
    grow(org, fb, 'style', newcomers * 0.35);
    fb.buzz = round1(fb.buzz * 0.75);
    const mood = overallMood(fb);
    org.rep = round1(clamp(org.rep + clamp((mood - 50) / 300, -0.08, 0.08), 1, 100));
    if (!org.user) {
      delete fb.history;
      delete fb.alerts;
      continue;
    }
    (fb.history ??= []).push({ month: monthKey(world.day), followers: org.fans, paying: payingSupporters(fb), mood: round1(mood) });
    if (fb.history.length > HISTORY_MONTHS) fb.history.splice(0, fb.history.length - HISTORY_MONTHS);
    fanAlerts(world, org, fb);
  }
}

function fanAlerts(world: World, org: Organisation, fb: Fanbase): void {
  fb.alerts ??= {};
  for (const def of FAN_SEGMENTS) {
    const s = fb.seg[def.id];
    const prev = fb.alerts[def.id];
    const recent = fb.log.filter((l) => l.seg === def.id && world.day - l.day <= 60).slice(-3).map((l) => `${l.note} (${l.d > 0 ? '+' : ''}${l.d})`);
    if (s.mood < 30 && prev !== 'low') {
      fb.alerts[def.id] = 'low';
      addInbox(world, { from: 'Supporter liaison', subject: `${def.label} are unhappy`, body: `${def.label} (${s.size.toLocaleString()} followers, mood ${Math.round(s.mood)}/100) care about: ${def.cares.toLowerCase()}.${recent.length ? `\nRecent reactions: ${recent.join('; ')}.` : ''}\nUnhappy supporters buy less merchandise; moods recover over time and with the things they care about.`, kind: 'fans' });
    } else if (s.mood > 75 && prev !== 'high') {
      fb.alerts[def.id] = 'high';
      addInbox(world, { from: 'Supporter liaison', subject: `${def.label} are delighted`, body: `${def.label} (${s.size.toLocaleString()} followers) are at ${Math.round(s.mood)}/100.${recent.length ? `\nRecent reactions: ${recent.join('; ')}.` : ''}`, kind: 'fans' });
    } else if (s.mood >= 38 && s.mood <= 67) delete fb.alerts[def.id];
  }
}

/**
 * Ensure every organisation has a fanbase and the world has rivalries. Saves from before fan
 * segments get segments from their follower count and rivalries seeded from their history.
 */
export function ensureAudience(world: World): void {
  for (const org of Object.values(world.orgs)) if (!org.federation) fanbaseOf(world, org);
  if (!world.rivalries) seedRivalries(world);
}

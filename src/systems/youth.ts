/**
 * Youth intake days and trials.
 *
 * Intake (every 1 December, pre-season): each organisation's academy team (or, without one, its
 * main team when the league's minimum age allows) receives a class of prospects aged 15–17:
 *   size = 2 + floor(academy facility / 4)  (2–4)
 *   60% from the organisation's own country, otherwise its region
 *   current ability  ~ N(42 + scene·0.06 + academy·0.6 + youthCoach·0.04, 5)
 *   potential boost  ~ N(3 + academy·0.7 + youthCoach·0.05, 6)
 * AI clubs sign the best one or two (by potential) on youth contracts if there is roster room —
 * without an academy team only a prospect with potential ≥ 78 — and the rest return to amateur play; the user gets 21 days on the Youth intake page to sign, trial
 * or release each prospect, and unsigned ones become free agents anyone can sign. The yearly
 * population top-up shrinks automatically as the player pool grows.
 *
 * Trials: a free agent (or an intake prospect) can be invited for 1–2 weeks. He is reserved for
 * that club meanwhile (others cannot sign him); scouting knowledge jumps immediately and is
 * complete at the end, when the staff give a verdict. Cost: $1,500 per week × region economy.
 */
import { clamp } from '../core/math';
import { RNG } from '../core/rng';
import { ageAt, ymd } from '../core/date';
import { getTitle } from '../data/titles/catalogue';
import { COUNTRY_BY_CODE, expandRegion, regionEconomy } from '../data/countries';
import type { Player, Team, World, YouthClass } from '../model/types';
import { generatePlayer, pickNationality, type GenCtx } from '../gen/player';
import { effectivenessLight } from '../sim/common/ratings';
import { expectedSalary } from './contracts';
import { ledger } from './finance';
import { addInbox, addNews } from './news';
import { movePlayer, rosterMax } from './transfers';
import { userHandles } from './career';

const INTAKE_DAYS = 21;

function academyTeam(world: World, mainTeam: Team): Team | null {
  return Object.values(world.teams).find((t) => t.active && t.parentTeamId === mainTeam.id) ?? null;
}

/** The team that receives a title's intake for an organisation (academy, else main team). */
function intakeTarget(world: World, main: Team): Team {
  return academyTeam(world, main) ?? main;
}

function youthCoachQuality(world: World, team: Team): number {
  let best = 0;
  for (const sid of team.staff) {
    const s = world.staff[sid];
    if (s && (s.role === 'academy-coach' || s.role === 'head-coach')) best = Math.max(best, s.attrs.youth ?? 0);
  }
  return best;
}

function youthSalary(world: World, p: Player, team: Team): number {
  const lg = team.leagueId ? world.leagues[team.leagueId] : null;
  return Math.round(Math.max(lg?.policy.minSalary ?? 0, expectedSalary(world, p, team.titleId, team.region) * 0.6, 3000) / 500) * 500;
}

function canSign(world: World, p: Player, team: Team): string | null {
  if (team.roster.length >= rosterMax(world, team)) return `${team.name}'s roster is full.`;
  const lg = team.leagueId ? world.leagues[team.leagueId] : null;
  if (lg && ageAt(p.birth, world.day) < lg.policy.minAge) return `${p.handle} is below ${lg.short}'s minimum age (${lg.policy.minAge}).`;
  return null;
}

/** Sign a prospect to a youth contract (3 seasons). */
export function signProspect(world: World, playerId: string, teamId: string): { ok: boolean; message: string } {
  const p = world.players[playerId];
  const team = world.teams[teamId];
  if (!p || !team || p.teamId) return { ok: false, message: 'The prospect is no longer available.' };
  const err = canSign(world, p, team);
  if (err) return { ok: false, message: err };
  movePlayer(world, p, team, youthSalary(world, p, team), 3, 'free', 0, undefined, { note: 'Youth intake', signingBonus: 0 });
  p.trial = null;
  for (const c of world.youth?.classes ?? []) if (c.prospects.includes(p.id)) c.decisions[p.id] = 'signed';
  return { ok: true, message: `${p.handle} signs a youth contract with ${team.name}.` };
}

export function releaseProspect(world: World, playerId: string): void {
  for (const c of world.youth?.classes ?? []) if (c.prospects.includes(playerId) && !c.decisions[playerId]) c.decisions[playerId] = 'released';
  const p = world.players[playerId];
  if (p?.trial) p.trial = null;
}

/** Daily: run the intake on 1 December; close the user's classes at their deadline; end trials. */
export function youthDaily(world: World): void {
  const { m, d } = ymd(world.day);
  world.youth ??= { lastSeason: 0, classes: [] };
  if (m === 12 && d === 1 && world.youth.lastSeason !== world.season) {
    world.youth.lastSeason = world.season;
    runIntake(world);
  }
  for (const c of world.youth.classes) {
    if (c.closed || world.day < c.deadline) continue;
    c.closed = true;
    const left = c.prospects.filter((id) => !c.decisions[id]);
    for (const id of left) releaseProspect(world, id);
    if (left.length) addInbox(world, { from: 'Academy', subject: 'Youth intake closed', body: `${left.map((id) => world.players[id]?.handle).join(', ')} were not signed and are now free agents.`, kind: 'youth' });
  }
  world.youth.classes = world.youth.classes.filter((c) => !c.closed || world.day - c.deadline < 60);
  trialsDaily(world);
}

function runIntake(world: World): void {
  const rng = new RNG(`${world.meta.seed}|intake|${world.season}`);
  const ctx: GenCtx = { world, rng, handles: new Set(Object.values(world.players).map((p) => p.handle.toLowerCase())) };
  let notable: Player | null = null;
  for (const org of Object.values(world.orgs)) {
    if (!org.active || org.federation) continue;
    for (const tid of org.teams) {
      const main = world.teams[tid];
      if (!main?.active || main.tier !== 'main' || main.national) continue;
      const user = org.user && userHandles(world, 'signings');
      const hasAcademy = !!academyTeam(world, main);
      const target = intakeTarget(world, main);
      const t = getTitle(main.titleId);
      const academy = org.facilities.academy ?? 3;
      const coach = youthCoachQuality(world, target);
      const size = 2 + Math.floor(academy / 4);
      const regs = expandRegion(org.region);
      const prospects: Player[] = [];
      for (let i = 0; i < size; i++) {
        const nat = rng.chance(0.6) ? org.country : pickNationality(rng, regs.length ? regs : [org.region], t.family, world.titles[t.id]?.popularity ?? {});
        const scene = world.countryScene[nat]?.[t.family === 'MOBA' ? 'moba' : 'shooter'] ?? 40;
        const p = generatePlayer(ctx, {
          titleId: t.id,
          nat,
          age: rng.int(15, 17),
          targetCA: clamp(rng.normal(42 + scene * 0.06 + academy * 0.6 + coach * 0.04, 5), 26, 68),
          potentialBoost: rng.normal(3 + academy * 0.7 + coach * 0.05, 6),
        });
        world.players[p.id] = p;
        p.career.push({ season: world.season, day: world.day, titleId: t.id, teamId: null, orgId: org.id, type: 'start', note: `${org.name} youth intake` });
        prospects.push(p);
        if (!notable || p.pa > notable.pa) notable = p;
      }
      if (user) {
        for (const p of prospects) world.scouting[p.id] = Math.max(world.scouting[p.id] ?? 0, 45);
        const cls: YouthClass = { season: world.season, day: world.day, deadline: world.day + INTAKE_DAYS, orgId: org.id, teamId: target.id, prospects: prospects.map((p) => p.id), decisions: {}, closed: false };
        world.youth!.classes.push(cls);
        const best = [...prospects].sort((a, b) => b.pa - a.pa)[0];
        addInbox(world, {
          from: 'Academy',
          subject: `Youth intake: ${prospects.length} ${t.short} prospects for ${target.name}`,
          body: `This year's intake has arrived (${prospects.map((p) => p.handle).join(', ')}). Our staff rate ${best.handle} highest. Sign, trial or release them on the Youth intake page within ${INTAKE_DAYS} days; unsigned prospects become free agents.`,
          kind: 'youth',
        });
        continue;
      }
      // AI clubs (and delegated user clubs): sign the best one or two prospects by potential.
      // Without an academy team only an exceptional prospect joins the main roster.
      const keep = [...prospects].sort((a, b) => b.pa - a.pa).slice(0, academy >= 6 ? 2 : 1).filter((p) => hasAcademy || p.pa >= 78);
      for (const p of keep) {
        const r = signProspect(world, p.id, target.id);
        if (r.ok && org.user) addInbox(world, { from: 'Academy', subject: `${p.handle} joins the academy`, body: `Our academy director signed ${p.handle} (${ageAt(p.birth, world.day)}) from this year's intake.`, kind: 'youth' });
      }
      // The rest of an AI club's class returns to amateur play (never enters the pool).
      for (const p of prospects) if (!p.teamId) delete world.players[p.id];
    }
  }
  if (notable && notable.pa >= 78) addNews(world, { kind: 'prospect', title: `Intake day: ${notable.handle} the pick of the class`, body: `Scouts rate the ${ageAt(notable.birth, world.day)}-year-old ${getTitle(notable.titleId).short} prospect from ${COUNTRY_BY_CODE[notable.nat]?.name} the most promising of this year's academy intakes.`, titleId: notable.titleId, refs: [{ type: 'player', id: notable.id }], importance: 2 });
}

// ---------------------------------------------------------------------------------------------
// Trials
// ---------------------------------------------------------------------------------------------

export function trialCost(world: World, team: Team, weeks: number): number {
  return Math.round(1500 * weeks * (0.4 + regionEconomy(team.region)));
}

/** Invite a free agent (or intake prospect) for a 1–2 week trial. */
export function startTrial(world: World, teamId: string, playerId: string, weeks: number): { ok: boolean; message: string } {
  const team = world.teams[teamId];
  const p = world.players[playerId];
  if (!team || !p || p.retired) return { ok: false, message: 'Invalid trial.' };
  if (p.teamId) return { ok: false, message: `${p.handle} is under contract — only free agents and intake prospects can be trialled.` };
  if (p.trial && p.trial.untilDay > world.day) return { ok: false, message: `${p.handle} is already on trial${p.trial.teamId === teamId ? ' with you' : ' elsewhere'}.` };
  const w = clamp(Math.round(weeks), 1, 2);
  const cost = trialCost(world, team, w);
  ledger(world, world.orgs[team.orgId], 'trial', -cost, team.id, `${p.handle} trial`);
  p.trial = { teamId, untilDay: world.day + w * 7 };
  world.scouting[p.id] = Math.max(world.scouting[p.id] ?? 0, 70);
  return { ok: true, message: `${p.handle} joins ${team.name} on a ${w}-week trial ($${cost.toLocaleString()}).` };
}

/** Is the player reserved by a trial with another club? */
export function onTrialElsewhere(world: World, p: Player, teamId: string): boolean {
  return !!p.trial && p.trial.untilDay > world.day && p.trial.teamId !== teamId;
}

function trialsDaily(world: World): void {
  for (const p of Object.values(world.players)) {
    const tr = p.trial;
    if (!tr || tr.untilDay > world.day) continue;
    p.trial = null;
    if (p.retired || p.teamId) continue;
    const team = world.teams[tr.teamId];
    if (!team) continue;
    world.scouting[p.id] = 100;
    if (world.orgs[team.orgId]?.user) {
      const t = getTitle(team.titleId);
      const best = t.roles.map((r) => ({ r, e: effectivenessLight(p, t, r) })).sort((a, b) => b.e - a.e)[0];
      const starters = team.lineup.map((s) => world.players[s.playerId]).filter(Boolean);
      const weakest = Math.min(...starters.map((x) => effectivenessLight(x!, t, t.roles.find((r) => r.id === team.lineup.find((s) => s.playerId === x!.id)!.roleId)!)));
      const verdict = best.e >= weakest ? 'would challenge for a starting place' : p.pa - p.ca >= 12 ? 'is raw but has a high ceiling' : best.e >= weakest - 6 ? 'would be useful depth' : 'is not at the level we need';
      addInbox(world, {
        from: 'Coaching staff',
        subject: `Trial complete: ${p.handle}`,
        body: `${p.handle} (${ageAt(p.birth, world.day)}) finished his trial with ${team.name}. Best role: ${best.r.name} (effectiveness ${best.e.toFixed(1)}); current ability ${Math.round(p.ca)}, potential ${Math.round(p.pa)}. The staff say he ${verdict}. Sign him from his profile or the Youth intake page.`,
        kind: 'youth',
        payload: { playerId: p.id },
      });
    }
  }
}

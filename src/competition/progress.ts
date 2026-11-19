/**
 * Competition lifecycle: starting competitions, resolving qualification, advancing stages and
 * finalising placements, prize money, trophies and circuit points.
 */
import type { Competition, Fixture, World } from '../model/types';
import { applyFixtureToStage, checkStage, stagePlacements, startStage } from './stages';
import { onCompetitionFinished } from '../systems/awards';
import { boardCompetitionReaction } from '../systems/career';
import { lockRegistrations } from '../systems/registration';
import { buildNationalTeams } from '../systems/nations';

function compDone(world: World, id: string): boolean {
  return world.comps[id]?.status === 'done';
}

/** Teams whose entrant sources are ready; null if a source competition is not finished yet. */
export function resolveEntrants(world: World, comp: Competition): string[] | null {
  const out: string[] = [];
  const add = (id: string) => {
    const t = world.teams[id];
    if (t && t.active && !out.includes(id)) out.push(id);
  };
  for (const rule of comp.entrants) {
    if (rule.kind === 'league') {
      const l = world.leagues[rule.leagueId];
      l?.teamIds.forEach(add);
    } else if (rule.kind === 'fixed') {
      rule.teamIds.forEach(add);
    } else if (rule.kind === 'nations') {
      buildNationalTeams(world, rule.titleId, rule.count).forEach(add);
    } else if (rule.kind === 'top') {
      if (!compDone(world, rule.compId)) return null;
      const src = world.comps[rule.compId];
      let taken = 0;
      for (const tid of src.placements.slice(rule.skip ?? 0)) {
        if (taken >= rule.count) break;
        if (!out.includes(tid) && world.teams[tid]?.active) {
          add(tid);
          taken++;
        }
      }
    } else if (rule.kind === 'circuit') {
      const league = world.leagues[rule.leagueId];
      if (!league) continue;
      const splits = Object.values(world.comps).filter((c) => c.season === comp.season && c.leagueId === league.id);
      if (splits.some((c) => c.status !== 'done')) return null;
      const intl = Object.values(world.comps).filter((c) => c.season === comp.season && c.titleId === comp.titleId && c.tier === 'international');
      if (intl.some((c) => c.status !== 'done')) return null;
      const pts = circuitPoints(world, comp.season, comp.titleId);
      const split2 = splits.find((c) => c.split === 2);
      const chosen: string[] = [];
      if (split2?.placements[0] && world.teams[split2.placements[0]]?.leagueId === league.id) chosen.push(split2.placements[0]);
      const rest = league.teamIds.filter((t) => !chosen.includes(t)).sort((a, b) => (pts[b] ?? 0) - (pts[a] ?? 0) || (world.teams[b]?.elo ?? 0) - (world.teams[a]?.elo ?? 0));
      for (const t of rest) {
        if (chosen.length >= rule.count) break;
        chosen.push(t);
      }
      chosen.slice(0, rule.count).forEach(add);
    }
  }
  // Seed by strength (elo) inside internationals for balanced brackets.
  if (comp.tier === 'international' || comp.tier === 'worlds' || comp.tier === 'cup') out.sort((a, b) => (world.teams[b]?.elo ?? 0) - (world.teams[a]?.elo ?? 0));
  return out;
}

export function circuitPoints(world: World, season: number, titleId: string): Record<string, number> {
  const pts: Record<string, number> = {};
  for (const c of Object.values(world.comps)) {
    if (c.season !== season || c.titleId !== titleId || c.status !== 'done' || !c.circuitPoints.length) continue;
    c.placements.forEach((tid, i) => {
      const mult = c.split === 2 ? 1.5 : 1;
      pts[tid] = (pts[tid] ?? 0) + (c.circuitPoints[i] ?? 0) * mult;
    });
  }
  return pts;
}

/** Start competitions whose start date is near. Idempotent. */
export function startDueCompetitions(world: World, day: number): void {
  for (const comp of Object.values(world.comps)) {
    if (comp.status !== 'pending' || comp.startDay > day + 3) continue;
    const teams = resolveEntrants(world, comp);
    if (!teams) continue;
    if (teams.length < 2) {
      comp.status = 'done';
      comp.placements = teams;
      continue;
    }
    comp.teamIds = teams;
    comp.status = 'active';
    comp.current = 0;
    lockRegistrations(world, comp);
    startStage(world, comp, comp.stages[0], teams);
  }
}

function advanceCount(stage: Competition['stages'][number]): number | undefined {
  if (stage.type === 'groups') return (stage.config.advance ?? 2) * (stage.config.groups ?? 2);
  return stage.config.advance;
}

/** Called after a fixture finishes: update stage, progress formats, finalise when done. */
export function onFixtureComplete(world: World, f: Fixture): void {
  const comp = world.comps[f.compId];
  if (!comp) return;
  const stage = comp.stages.find((s) => s.id === f.stageId);
  if (!stage) return;
  applyFixtureToStage(world, comp, stage, f);
  if (stage.status !== 'active') return;
  if (!checkStage(world, comp, stage)) return;
  stage.status = 'done';
  stage.placements = stagePlacements(world, stage);
  const idx = comp.stages.indexOf(stage);
  const next = comp.stages[idx + 1];
  if (next) {
    const n = advanceCount(stage) ?? stage.placements.length;
    comp.current = idx + 1;
    startStage(world, comp, next, stage.placements.slice(0, Math.min(n, stage.placements.length)));
    return;
  }
  finalizeCompetition(world, comp);
}

export function finalizeCompetition(world: World, comp: Competition): void {
  if (comp.status === 'done') return;
  const order: string[] = [];
  for (let i = comp.stages.length - 1; i >= 0; i--) {
    for (const t of comp.stages[i].placements) if (!order.includes(t)) order.push(t);
  }
  for (const t of comp.teamIds) if (!order.includes(t)) order.push(t);
  comp.placements = order;
  comp.status = 'done';
  onCompetitionFinished(world, comp);
  boardCompetitionReaction(world, comp);
}

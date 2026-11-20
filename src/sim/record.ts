/**
 * Applies simulated results to the world: fixture result, match record, player stat lines,
 * competition aggregates, Elo, morale/form/fatigue, proficiency growth from play, chemistry,
 * relationships and news. Idempotent per fixture (guarded by fixture.status).
 */
import { detachReplays } from './moba/replayCache';
import { slimGame } from './boxscore';
import { recordH2H } from '../systems/seasonReport';
import { isFollowed } from '../systems/follow';
import { clamp } from '../core/math';
import { getTitle } from '../data/titles/catalogue';
import type { Fixture, GameResult, MatchRecord, Player, StatLine, World } from '../model/types';
import { addNews, isUserTeam, addInbox } from '../systems/news';
import { onFixtureComplete } from '../competition/progress';
import { fansOnSeries, isHomegrown, seriesAudience } from '../systems/fans';
import { recordEncounter } from '../systems/rivalries';
import { runSeries, type SeriesState } from './series';

function statLine(p: Player, season: number, titleId: string, teamId: string | null): StatLine {
  let l = p.stats.find((s) => s.season === season && s.titleId === titleId && s.teamId === teamId);
  if (!l) {
    l = { season, titleId, teamId, gp: 0, w: 0, l: 0, ratingSum: 0, mvps: 0, s: {} };
    p.stats.push(l);
  }
  return l;
}

function applyGame(world: World, f: Fixture, game: GameResult, teamIds: string[], winnerTeam: string | null, mvp: string | undefined) {
  const comp = world.comps[f.compId];
  for (const line of game.players) {
    const p = world.players[line.playerId];
    if (!p) continue;
    const tid = teamIds[line.teamIdx];
    const sl = statLine(p, world.season, f.titleId, tid);
    sl.gp++;
    const won = line.teamIdx === game.winner;
    if (won) sl.w++;
    else sl.l++;
    sl.ratingSum += line.rating;
    if (mvp === p.id) sl.mvps++;
    for (const [k, v] of Object.entries(line.s)) sl.s[k] = (sl.s[k] ?? 0) + v;
    if (comp) {
      const cs = (comp.pstats[p.id] ||= { gp: 0, r: 0, m: 0, teamId: tid });
      cs.gp++;
      cs.r += line.rating;
      if (mvp === p.id) cs.m++;
      cs.teamId = tid;
    }
    // Learning by playing: title, role, character and map familiarity.
    const learn = 0.5 + p.attrs[55] / 100; // learning speed
    p.prof.titles[f.titleId] = clamp((p.prof.titles[f.titleId] ?? 0) + 0.12 * learn * (1 - (p.prof.titles[f.titleId] ?? 0) / 105), 0, 100);
    const rk = `${f.titleId}:${line.roleId}`;
    p.prof.roles[rk] = clamp((p.prof.roles[rk] ?? 0) + 0.15 * learn * (1 - (p.prof.roles[rk] ?? 0) / 105), 0, 100);
    if (line.charId) {
      const cm = (p.prof.chars[f.titleId] ||= {});
      cm[line.charId] = clamp((cm[line.charId] ?? 10) + 0.6 * learn * (1 - (cm[line.charId] ?? 10) / 105), 0, 100);
    }
    if (game.mapId) {
      const mm = (p.prof.maps[f.titleId] ||= {});
      if (getTitle(f.titleId).maps.length > 1) mm[game.mapId] = clamp((mm[game.mapId] ?? 20) + 0.4 * learn * (1 - (mm[game.mapId] ?? 20) / 105), 0, 100);
    }
    // Form: performance relative to expectation drifts quickly.
    p.form = clamp(p.form * 0.85 + (line.rating - 1) * 6, -10, 10);
    // Fatigue from playing (stamina reduces it)
    p.fatigue = clamp(p.fatigue + (4 - p.attrs[60] / 50) * (game.duration > 2400 ? 1.4 : 1), 0, 100);
  }
  void winnerTeam;
}

function eloUpdate(world: World, a: string, b: string, aWon: boolean, margin: number): number {
  const ta = world.teams[a];
  const tb = world.teams[b];
  if (!ta || !tb) return 0;
  const ea = 1 / (1 + Math.pow(10, (tb.elo - ta.elo) / 400));
  const k = 24 * (1 + margin * 0.3);
  const d = k * ((aWon ? 1 : 0) - ea);
  ta.elo = Math.round(ta.elo + d);
  tb.elo = Math.round(tb.elo - d);
  return ea;
}

function seriesMorale(world: World, teamId: string, won: boolean, importance: number) {
  const team = world.teams[teamId];
  if (!team) return;
  for (const pid of team.roster) {
    const p = world.players[pid];
    if (!p) continue;
    const resil = p.attrs[40] / 100;
    p.morale = clamp(p.morale + (won ? 3 + importance * 3 : -(3 + importance * 3) * (1.2 - resil)), 0, 100);
  }
  team.chemistry = clamp(team.chemistry + (won ? 0.25 : -0.15) + 0.08, 0, 100);
  // Relationships between starters who play together.
  const starters = team.lineup.map((s) => s.playerId);
  for (const a of starters) {
    const pa = world.players[a];
    if (!pa) continue;
    for (const b of starters) {
      if (a === b) continue;
      pa.rel[b] = clamp((pa.rel[b] ?? 0) + (won ? 0.6 : 0.25), -100, 100);
    }
  }
}

/** Academy graduates who played their first series for a main team in this fixture. */
function academyDebuts(world: World, f: Fixture, games: GameResult[]): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  f.teams.forEach((tid, i) => {
    const team = world.teams[tid];
    if (!team || team.tier !== 'main' || team.national) return;
    const played = new Map<string, number>();
    for (const g of games) for (const l of g.players) if (l.teamIdx === i) played.set(l.playerId, (played.get(l.playerId) ?? 0) + 1);
    for (const [pid, n] of played) {
      const p = world.players[pid];
      if (!p) continue;
      const gp = p.stats.reduce((a, s) => a + (s.teamId === tid ? s.gp : 0), 0);
      if (gp === n && isHomegrown(world, p, team.orgId)) (out[tid] ??= []).push(pid);
    }
  });
  return out;
}

/** Simulate and record one fixture. Safe to call twice (second call is a no-op). */
export function playFixture(world: World, f: Fixture, forceDetail = false): MatchRecord | null {
  if (f.status === 'done') return world.matches[f.id] ?? null;
  const t = getTitle(f.titleId);
  const comp = world.comps[f.compId];
  const importance = f.formatKey === 'final' ? 2 : f.formatKey === 'playoff' ? 1 : 0.3;
  let record: MatchRecord;
  const st = runSeries(world, f, forceDetail);
  record = applySeries(world, f, st, forceDetail, importance);
  f.status = 'done';
  // Keep detailed records; summary-only fixtures store a slim record (no events / player lines)
  if (!record.detail) {
    // Aggregated simulation for unobserved matches: results are identical, only the box score
    // detail is discarded (player season stats were already accumulated above).
    record = { ...record, games: record.games.map((g) => slimGame(g)) };
  }
  world.matches[f.id] = record;
  onFixtureComplete(world, f);
  return record;
}

/** Apply a finished two-team series (instant or live) to the world. */
function applySeries(world: World, f: Fixture, st: SeriesState, forceDetail: boolean, importance: number): MatchRecord {
  const t = getTitle(f.titleId);
  const comp = world.comps[f.compId];
  let record: MatchRecord;
    const winner = f.teams[st.winner];
  const loser = f.teams[1 - st.winner];
  f.winner = winner;
  f.score = [...st.wins];
  // Replays stay in the session cache (watchable now), never in the saved world.
  const games = detachReplays(world, f.id, st.games);
  for (const g of games) {
    const mvp = [...g.players].filter((l) => l.teamIdx === g.winner).sort((a, b) => b.rating - a.rating)[0]?.playerId;
    applyGame(world, f, g, f.teams, f.teams[g.winner], mvp);
  }
  const agg: Record<string, number> = {};
  for (const g of games) for (const l of g.players) if (l.teamIdx === st.winner) agg[l.playerId] = (agg[l.playerId] ?? 0) + l.rating;
  f.mvp = Object.entries(agg).sort((a, b) => b[1] - a[1])[0]?.[0];
  record = { fixtureId: f.id, titleId: f.titleId, day: f.day, teams: f.teams, games, vetoes: st.vetoes, mvp: f.mvp, detail: f.detail || forceDetail };
  const margin = Math.abs(st.wins[0] - st.wins[1]) / Math.max(1, Math.max(...st.wins));
  const preWinnerElo = world.teams[winner]?.elo ?? 1500;
  const preLoserElo = world.teams[loser]?.elo ?? 1500;
  const preElo: [number, number] = [world.teams[f.teams[0]]?.elo ?? 1500, world.teams[f.teams[1]]?.elo ?? 1500];
  eloUpdate(world, f.teams[0], f.teams[1], st.winner === 0, margin);
  // Rivalry memory and supporter reactions come only from this recorded result.
  const rivalry = recordEncounter(world, f, st.winner, preElo);
  recordH2H(world, f, st.winner, st.wins);
  fansOnSeries(world, f, st.winner, rivalry, academyDebuts(world, f, games));
  record.audience = seriesAudience(world, f, rivalry);
  seriesMorale(world, winner, true, importance);
  seriesMorale(world, loser, false, importance);
  if (preLoserElo - preWinnerElo > 160 && importance >= 0.3) {
    addNews(world, {
      kind: 'upset',
      title: `Upset! ${world.teams[winner].name} stun ${world.teams[loser].name}`,
      body: `${world.teams[winner].name} won ${Math.max(...st.wins)}-${Math.min(...st.wins)} in ${comp?.name ?? t.short} despite a ${preLoserElo - preWinnerElo}-point rating gap.`,
      titleId: f.titleId,
      refs: [{ type: 'team', id: winner }, { type: 'team', id: loser }],
      importance: importance >= 1 ? 4 : 2,
    });
  }
  const followedReport = world.meta.mode === 'spectator' && (world.meta.followed?.length ?? 0) <= 20 && (isFollowed(world, winner) || isFollowed(world, loser));
  if (isUserTeam(world, winner) || isUserTeam(world, loser) || followedReport) {
    const ut = isUserTeam(world, winner) || isFollowed(world, winner) ? winner : loser;
    const won = ut === winner;
    addInbox(world, {
      from: 'Match Report',
      subject: `${won ? 'Win' : 'Loss'}: ${world.teams[f.teams[0]].short} ${st.wins[0]}-${st.wins[1]} ${world.teams[f.teams[1]].short}`,
      body: `${comp?.name ?? ''} — ${games.map((g) => g.summary).join(' | ')}`,
      kind: 'match',
      payload: { fixtureId: f.id },
    });
  }
    return record;
}

/** Record a series that was played interactively (live match screen). */
export function recordLiveSeries(world: World, f: Fixture, st: SeriesState): MatchRecord | null {
  if (f.status === 'done' || !st.done) return world.matches[f.id] ?? null;
  const importance = f.formatKey === 'final' ? 2 : f.formatKey === 'playoff' ? 1 : 0.3;
  let record = applySeries(world, f, st, true, importance);
  f.status = 'done';
  world.matches[f.id] = record;
  delete world.overrides[f.id];
  onFixtureComplete(world, f);
  return record;
}

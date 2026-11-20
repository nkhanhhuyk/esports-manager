/**
 * Season previews (13 January, after the pre-season window: power rankings by squad strength,
 * World Championship favourites, players to watch, breakout candidates, biggest moves) and
 * season reviews (at the season's end: champions, awards, storylines drawn from what actually
 * happened, and the preview's predictions against the final league tables). Also the all-time
 * head-to-head ledger used by the stats hub.
 */
import { ageAt, ymd } from '../core/date';
import { getTitle } from '../data/titles/catalogue';
import type { Fixture, H2HRecord, PreviewTeam, SeasonPreview, SeasonReview, Storyline, World } from '../model/types';
import { computeTeamStrength } from '../sim/common/team';
import { addInbox, addNews } from './news';
import { rivalryKey } from './rivalries';

// ---------------------------------------------------------------------------------------------
// Head-to-head ledger
// ---------------------------------------------------------------------------------------------

/** Record a finished two-team series in the all-time head-to-head ledger. */
export function recordH2H(world: World, f: Fixture, winnerIdx: number, wins: number[]): void {
  if (f.teams.length !== 2) return;
  const [a, b] = f.teams;
  if (world.teams[a]?.national || world.teams[b]?.national) return;
  const key = rivalryKey(a, b);
  const r: H2HRecord = ((world.h2h ??= {})[key] ??= [0, 0, 0, 0, 0, 0, 0]);
  const first = a < b ? 0 : 1; // index in f.teams of the key's first team
  r[0]++;
  r[winnerIdx === first ? 1 : 2]++;
  r[3] += wins[first] ?? 0;
  r[4] += wins[1 - first] ?? 0;
  if (f.formatKey === 'final') r[5]++;
  r[6] = world.season;
}

export interface H2HView {
  meetings: number;
  wins: [number, number];
  games: [number, number];
  finals: number;
  lastSeason: number;
}

/** Head-to-head from team `a`'s point of view. */
export function headToHead(world: World, a: string, b: string): H2HView | null {
  const r = world.h2h?.[rivalryKey(a, b)];
  if (!r) return null;
  const flip = a > b;
  return { meetings: r[0], wins: flip ? [r[2], r[1]] : [r[1], r[2]], games: flip ? [r[4], r[3]] : [r[3], r[4]], finals: r[5], lastSeason: r[6] };
}

// ---------------------------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------------------------

export function buildPreview(world: World): SeasonPreview {
  const season = world.season;
  const preview: SeasonPreview = { season, day: world.day, titles: {} };
  const since = world.day - 60;
  for (const titleId of world.meta.titlesEnabled) {
    const leagues = Object.values(world.leagues).filter((l) => l.titleId === titleId).sort((x, y) => x.tier - y.tier || x.name.localeCompare(y.name));
    const strength = new Map<string, number>();
    const str = (id: string) => {
      let v = strength.get(id);
      if (v === undefined) {
        v = world.teams[id] ? computeTeamStrength(world, world.teams[id]) : 0;
        strength.set(id, v);
      }
      return v;
    };
    const moves = world.transfers.filter((t) => t.titleId === titleId && t.day >= since && (t.type === 'transfer' || t.type === 'free' || t.type === 'trade'));
    const lg = leagues.map((l) => {
      const teams: PreviewTeam[] = l.teamIds
        .map((id) => world.teams[id])
        .filter((t) => t?.active)
        .map((t) => ({ teamId: t.id, rank: 0, strength: Math.round(str(t.id) * 10) / 10, elo: t.elo }))
        .sort((x, y) => y.strength - x.strength || y.elo - x.elo);
      teams.forEach((t, i) => {
        t.rank = i + 1;
        const ins = moves.filter((m) => m.toTeamId === t.teamId).map((m) => world.players[m.playerId]).filter(Boolean).sort((p, q) => q!.ca - p!.ca);
        const outs = moves.filter((m) => m.fromTeamId === t.teamId).map((m) => world.players[m.playerId]).filter(Boolean).sort((p, q) => q!.ca - p!.ca);
        const notes = [ins[0] ? `signed ${ins[0].handle}` : '', outs[0] ? `lost ${outs[0].handle}` : ''].filter(Boolean);
        if (notes.length) t.note = notes.join(', ');
      });
      return { leagueId: l.id, teams };
    });
    const mains = Object.values(world.teams).filter((t) => t.active && !t.national && t.titleId === titleId && t.tier === 'main');
    const favourites = [...mains].sort((x, y) => str(y.id) - str(x.id)).slice(0, 5).map((t) => t.id);
    const rostered = mains.flatMap((t) => t.roster.map((id) => world.players[id])).filter((p) => p && !p.retired);
    const watch = [...rostered].sort((p, q) => q!.ca - p!.ca).slice(0, 5).map((p) => p!.id);
    const breakout = rostered.filter((p) => ageAt(p!.birth, world.day) <= 20 && p!.ca >= 50).sort((p, q) => q!.pa - q!.ca - (p!.pa - p!.ca)).slice(0, 5).map((p) => p!.id);
    const big = moves.filter((m) => m.fee > 0).sort((x, y) => y.fee - x.fee).slice(0, 5).map((m) => ({ playerId: m.playerId, fromTeamId: m.fromTeamId, toTeamId: m.toTeamId, fee: m.fee }));
    preview.titles[titleId] = { leagues: lg, worldsFavourites: favourites, playersToWatch: watch, breakoutCandidates: breakout, biggestMoves: big };
  }
  return preview;
}

/** Daily: publish the season preview on 13 January. */
export function previewDaily(world: World): void {
  const { m, d } = ymd(world.day);
  if (m !== 1 || d !== 13 || world.previews?.[world.season]) return;
  const pv = buildPreview(world);
  (world.previews ??= {})[world.season] = pv;
  const parts = Object.entries(pv.titles)
    .map(([tid, t]) => (t.worldsFavourites[0] ? `${getTitle(tid).short}: ${world.teams[t.worldsFavourites[0]]?.name}` : ''))
    .filter(Boolean);
  const body = `Power rankings, favourites, players to watch and the off-season's biggest moves are in Season → Preview. Title favourites — ${parts.join(' · ')}.`;
  addNews(world, { kind: 'world', title: `${world.season} season preview`, body, refs: [], importance: 3 });
  if (world.meta.userOrgId || world.meta.mode === 'spectator') addInbox(world, { from: 'Media', subject: `${world.season} season preview`, body, kind: 'season' });
  // Keep the last 12 seasons.
  for (const k of Object.keys(world.previews!)) if (Number(k) < world.season - 11) delete world.previews![Number(k)];
}

// ---------------------------------------------------------------------------------------------
// Review
// ---------------------------------------------------------------------------------------------

/** Final league table order for a season (both splits' placements summed, playoffs included). */
export function leagueFinish(world: World, leagueId: string, season: number): string[] {
  const pts = new Map<string, number>();
  for (const c of Object.values(world.comps)) {
    if (c.season !== season || c.leagueId !== leagueId) continue;
    c.placements.forEach((tid, i) => pts.set(tid, (pts.get(tid) ?? 0) + i));
  }
  return [...pts.entries()].sort((a, b) => a[1] - b[1]).map(([t]) => t);
}

const ORD = (n: number) => `${n}${n % 10 === 1 && n % 100 !== 11 ? 'st' : n % 10 === 2 && n % 100 !== 12 ? 'nd' : n % 10 === 3 && n % 100 !== 13 ? 'rd' : 'th'}`;

/** Build the season review (called at the season's end, before old data is pruned). */
export function buildReview(world: World, season: number): SeasonReview {
  const hist = world.history.find((h) => h.season === season);
  const review: SeasonReview = { season, day: world.day, champions: [], awards: [], storylines: [], predictions: [] };
  for (const c of hist?.champions ?? []) review.champions.push({ titleId: c.titleId, compId: c.compId, compName: c.compName, tier: c.tier, teamId: c.teamId, runnerUpId: c.runnerUpId });
  for (const a of world.awards) if (a.season === season && !a.compId && a.awardId !== 'hof') review.awards.push({ awardId: a.awardId, label: a.label, titleId: a.titleId ?? undefined, playerId: a.playerId, teamId: a.teamId, staffId: a.staffId });
  const st: Storyline[] = review.storylines;
  const team = (id?: string) => (id ? world.teams[id] : undefined);
  // World champions and dynasties.
  for (const c of review.champions.filter((x) => x.tier === 'worlds')) {
    const T = team(c.teamId);
    if (!T) continue;
    let streak = 1;
    for (let s = season - 1; s >= season - 10; s--) {
      const h = world.history.find((x) => x.season === s);
      if (h?.champions.some((x) => x.tier === 'worlds' && x.titleId === c.titleId && x.teamId === c.teamId)) streak++;
      else break;
    }
    st.push({ kind: streak >= 2 ? 'dynasty' : 'champion', titleId: c.titleId, text: streak >= 2 ? `${T.name} win ${streak} ${getTitle(c.titleId).short} world titles in a row — a dynasty.` : `${T.name} are ${getTitle(c.titleId).short} world champions${c.runnerUpId && team(c.runnerUpId) ? `, beating ${team(c.runnerUpId)!.name} in the final` : ''}.`, refs: [{ type: 'team', id: T.id }, ...(c.runnerUpId ? [{ type: 'team' as const, id: c.runnerUpId }] : [])] });
  }
  // Predictions vs reality.
  const pv = world.previews?.[season];
  let calls = 0;
  let leaguesJudged = 0;
  if (pv) {
    for (const [titleId, t] of Object.entries(pv.titles)) {
      for (const l of t.leagues) {
        const finish = leagueFinish(world, l.leagueId, season);
        if (!finish.length) continue;
        leaguesJudged++;
        const rows = l.teams.filter((x) => finish.includes(x.teamId)).map((x) => ({ teamId: x.teamId, predicted: x.rank, actual: finish.indexOf(x.teamId) + 1 }));
        review.predictions.push({ leagueId: l.leagueId, titleId, rows });
        const winner = rows.find((r) => r.actual === 1);
        if (winner?.predicted === 1) calls++;
        const league = world.leagues[l.leagueId];
        if (winner && winner.predicted >= 4) st.push({ kind: 'underdog', titleId, text: `${team(winner.teamId)?.name} were tipped ${ORD(winner.predicted)} in ${league?.name ?? 'their league'} and finished top.`, refs: [{ type: 'team', id: winner.teamId }] });
        for (const r of rows) if (r.predicted <= 2 && r.actual > Math.ceil(rows.length / 2)) st.push({ kind: 'collapse', titleId, text: `${team(r.teamId)?.name} were tipped ${ORD(r.predicted)} in ${league?.name ?? 'their league'} but finished ${ORD(r.actual)}.`, refs: [{ type: 'team', id: r.teamId }] });
      }
    }
    if (leaguesJudged) st.push({ kind: 'prediction', text: `The preview called ${calls} of ${leaguesJudged} league winners.`, refs: [] });
  }
  // Breakouts: biggest one-season rises among players aged 22 or under.
  const rises = Object.values(world.players)
    .filter((p) => !p.retired && p.teamId && ageAt(p.birth, world.day) <= 22)
    .map((p) => {
      const prev = [...p.caHistory].reverse().find((h) => h.season < season);
      return { p, gain: prev ? p.ca - prev.ca : 0 };
    })
    .filter((x) => x.gain >= 4)
    .sort((a, b) => b.gain - a.gain)
    .slice(0, 3);
  for (const { p, gain } of rises) st.push({ kind: 'breakout', titleId: p.titleId, text: `${p.handle} (${ageAt(p.birth, world.day)}) broke out, rising ${gain.toFixed(1)} points to ${Math.round(p.ca)} ability.`, refs: [{ type: 'player', id: p.id }] });
  // Rivalries born this season.
  for (const r of Object.values(world.rivalries ?? {})) if (r.since === season) st.push({ kind: 'rivalry', titleId: r.titleId, text: `A rivalry was born: ${team(r.teams[0])?.name} vs ${team(r.teams[1])?.name}.`, refs: [{ type: 'team', id: r.teams[0] }, { type: 'team', id: r.teams[1] }] });
  // Deal of the season.
  const deal = world.transfers.filter((t) => t.season === season && t.fee > 0).sort((a, b) => b.fee - a.fee)[0];
  if (deal && world.players[deal.playerId]) st.push({ kind: 'transfer', titleId: deal.titleId, text: `Deal of the season: ${world.players[deal.playerId].handle} to ${team(deal.toTeamId ?? undefined)?.name ?? '?'} for $${Math.round(deal.fee / 1000)}K.`, refs: [{ type: 'player', id: deal.playerId }] });
  // Notable retirements.
  for (const r of world.retired.filter((x) => x.retiredSeason === season && x.legacy >= 60).sort((a, b) => b.legacy - a.legacy).slice(0, 3)) st.push({ kind: 'retirement', text: `${r.handle} retired with a legacy score of ${r.legacy.toFixed(0)}.`, refs: world.players[r.id] ? [{ type: 'player', id: r.id }] : [] });
  return review;
}

/** Store the review and announce it (season end). */
export function publishReview(world: World, season: number): void {
  const rv = buildReview(world, season);
  (world.reviews ??= {})[season] = rv;
  for (const k of Object.keys(world.reviews)) if (Number(k) < season - 11) delete world.reviews[Number(k)];
  const top = rv.storylines.slice(0, 3).map((s) => s.text).join(' ');
  addNews(world, { kind: 'world', title: `${season} season review`, body: `${top} Full review: Season → Review.`, refs: [], importance: 3 });
}

import 'fake-indexeddb/auto';
import { describe, expect, it } from 'vitest';
import { clone, makeWorld, withUser } from './helpers';
import type { Fixture, FormatKey, World } from '../src/model/types';
import { advance } from '../src/game/advance';
import { importSave } from '../src/persistence/db';
import { pressureFor } from '../src/sim/series';
import { getRivalry, NOTABLE, recordEncounter, rivalriesMonthly, rivalryLabel, rivalryTransfer } from '../src/systems/rivalries';
import { FAN_SEGMENTS, fanbaseOf, fansMonthly, fansOnSeries, fansOnTransfer, merchandiseRevenue, payingSupporters, starFollowing } from '../src/systems/fans';

let n = 0;
function league(w: World, titleId = 'lol') {
  const lg = Object.values(w.leagues).find((l) => l.titleId === titleId && l.tier === 1 && l.teamIds.length >= 4)!;
  const comp = Object.values(w.comps).find((c) => c.leagueId === lg.id)!;
  return { lg, comp, a: lg.teamIds[0], b: lg.teamIds[1] };
}
function fixture(w: World, compId: string, a: string, b: string, key: FormatKey, bo: number, score: [number, number]): Fixture {
  const comp = w.comps[compId];
  return { id: `fxt${++n}`, compId, stageId: comp.stages[0].id, titleId: comp.titleId, day: w.day, teams: [a, b], format: { kind: 'bo', n: bo, label: `BO${bo}` }, formatKey: key, status: 'done', winner: score[0] > score[1] ? a : b, score, detail: false };
}
function resetMoods(w: World, orgId: string) {
  const fb = fanbaseOf(w, w.orgs[orgId])!;
  for (const s of Object.values(fb.seg)) s.mood = 50;
  return fb;
}

describe('rivalries from recorded encounters', () => {
  it('a single ordinary league defeat does not start a rivalry', () => {
    const w = makeWorld(['lol'], 'riv-ordinary');
    const { comp, a, b } = league(w);
    expect(recordEncounter(w, fixture(w, comp.id, a, b, 'standard', 3, [2, 0]), 0, [1500, 1500])).toBeNull();
    expect(getRivalry(w, a, b)).toBeUndefined();
  });

  it('repeated finals build an announced rivalry that fades once contact stops', () => {
    const w = withUser(makeWorld(['lol'], 'riv-finals'));
    const { comp, a, b } = league(w);
    for (let i = 0; i < 4; i++) recordEncounter(w, fixture(w, comp.id, a, b, 'final', 5, i % 2 ? [2, 3] : [3, 2]), i % 2 ? 1 : 0, [1500, 1500]);
    const r = getRivalry(w, a, b)!;
    expect(r.intensity).toBeGreaterThanOrEqual(NOTABLE);
    expect(rivalryLabel(r.intensity)).not.toBeNull();
    expect(r.finals).toBe(4);
    expect(r.meetings).toBe(4);
    expect(r.wins[0] + r.wins[1]).toBe(4);
    expect(r.since).toBe(w.season);
    expect(r.moments.length).toBeGreaterThan(0);
    expect(w.news.some((x) => x.kind === 'rivalry' && x.title.startsWith('A rivalry is born'))).toBe(true);
    const peak = r.intensity;
    // A year without meetings: it fades (faster than while the teams keep meeting) but is remembered.
    w.day += 400;
    for (let m = 0; m < 12; m++) rivalriesMonthly(w);
    expect(getRivalry(w, a, b)!.intensity).toBeLessThan(peak * 0.6);
    expect(getRivalry(w, a, b)!.peak).toBe(peak);
    // A minor (never notable) record is forgotten.
    const c = w.leagues[w.teams[a].leagueId!].teamIds[2];
    recordEncounter(w, fixture(w, comp.id, a, c, 'playoff', 5, [3, 0]), 0, [1500, 1500]);
    expect(getRivalry(w, a, c)).toBeDefined();
    w.day += 200;
    for (let m = 0; m < 6; m++) rivalriesMonthly(w);
    expect(getRivalry(w, a, c)).toBeUndefined();
  });

  it('a star moving between rivals adds heat; rivalry raises pressure but never changes ability', () => {
    const w = makeWorld(['lol'], 'riv-transfer');
    const { comp, a, b } = league(w);
    const f = fixture(w, comp.id, a, b, 'standard', 3, [2, 1]);
    const before = pressureFor(w, f);
    const attrs = clone(Object.fromEntries([...w.teams[a].roster, ...w.teams[b].roster].map((id) => [id, w.players[id].attrs])));
    for (let i = 0; i < 3; i++) recordEncounter(w, fixture(w, comp.id, a, b, 'final', 5, [3, 2]), 0, [1500, 1500]);
    const r = getRivalry(w, a, b)!;
    const heat = r.intensity;
    expect(pressureFor(w, f)).toBeGreaterThan(before);
    const star = w.players[w.teams[a].roster[0]];
    star.rep = 80;
    rivalryTransfer(w, star, w.teams[a], w.teams[b], 300000);
    expect(r.transfers).toBe(1);
    expect(r.intensity).toBeGreaterThan(heat);
    expect(r.moments.some((m) => m.kind === 'transfer')).toBe(true);
    for (const [id, at] of Object.entries(attrs)) expect(w.players[id].attrs).toEqual(at);
  });
});

describe('supporter segments', () => {
  it('segments react to what they care about', () => {
    const w = withUser(makeWorld(['lol'], 'fans-react'));
    const { comp, a, b } = league(w);
    const orgId = w.teams[a].orgId;
    // A final win moves winning-focused fans much more than youth & community followers.
    let fb = resetMoods(w, orgId);
    fansOnSeries(w, fixture(w, comp.id, a, b, 'final', 5, [3, 1]), 0, null, {});
    expect(fb.seg.winning.mood - 50).toBeGreaterThan(3 * (fb.seg.community.mood - 50));
    expect(fb.log.some((l) => l.seg === 'winning' && l.d > 0)).toBe(true);
    // An academy graduate's debut in a routine win matters more to the community than the win to winning fans.
    fb = resetMoods(w, orgId);
    const grad = w.teams[a].roster[0];
    fansOnSeries(w, fixture(w, comp.id, a, b, 'standard', 3, [2, 0]), 0, null, { [a]: [grad] });
    expect(fb.seg.community.mood - 50).toBeGreaterThan(fb.seg.winning.mood - 50);
    // A glamorous foreign signing excites winning fans but worries local loyalists.
    fb = resetMoods(w, orgId);
    const p = Object.values(w.players).find((x) => x.teamId && x.teamId !== a && x.nat !== w.orgs[orgId].country)!;
    p.rep = 80;
    fansOnTransfer(w, p, null, w.teams[a], 'transfer', 0, 0);
    expect(fb.seg.winning.mood).toBeGreaterThan(50);
    expect(fb.seg.local.mood).toBeLessThan(50);
    // A long-serving favourite leaving hurts local loyalists.
    fb = resetMoods(w, orgId);
    const fav = w.players[w.teams[a].roster[1]];
    fansOnTransfer(w, fav, w.teams[a], w.teams[b], 'transfer', 100000, 900);
    expect(fb.seg.local.mood).toBeLessThan(48);
  });

  it('signing from rivals stings both sets of supporters', () => {
    const w = makeWorld(['lol'], 'fans-rival-move');
    const { comp, a, b } = league(w);
    for (let i = 0; i < 4; i++) recordEncounter(w, fixture(w, comp.id, a, b, 'final', 5, [3, 2]), 0, [1500, 1500]);
    const seller = resetMoods(w, w.teams[a].orgId);
    const buyer = resetMoods(w, w.teams[b].orgId);
    const p = w.players[w.teams[a].roster[0]];
    p.rep = 50;
    fansOnTransfer(w, p, w.teams[a], w.teams[b], 'transfer', 50000, 100);
    expect(seller.seg.local.mood).toBeLessThan(47);
    expect(buyer.seg.local.mood).toBeLessThan(50);
  });

  it('followers (reach) and paying supporters (conversion) are separate; mood drives merchandise', () => {
    const w = makeWorld(['lol'], 'fans-merch');
    const org = Object.values(w.orgs).find((o) => !o.federation && o.teams.length)!;
    const fb = fanbaseOf(w, org)!;
    expect(Object.values(fb.seg).reduce((s, x) => s + x.size, 0)).toBe(org.fans);
    expect(FAN_SEGMENTS.map((s) => s.id).sort()).toEqual(['community', 'legacy', 'local', 'player', 'style', 'winning']);
    const paying = payingSupporters(fb);
    const merch = merchandiseRevenue(org, 1);
    expect(paying).toBeLessThan(org.fans * 0.1);
    for (const s of Object.values(fb.seg)) s.mood = 80;
    expect(payingSupporters(fb)).toBeGreaterThan(paying);
    expect(merchandiseRevenue(org, 1)).toBeGreaterThan(merch);
    expect(Object.values(fb.seg).reduce((s, x) => s + x.size, 0)).toBe(org.fans);
  });

  it('player fans arrive and leave with the stars on the roster', () => {
    const w = makeWorld(['lol'], 'fans-stars');
    const { a } = league(w);
    const org = w.orgs[w.teams[a].orgId];
    const fb = fanbaseOf(w, org)!;
    fansMonthly(w);
    const base = fb.seg.player.size;
    const star = Object.values(w.players).find((p) => !p.teamId && !p.retired)!;
    star.rep = 95;
    star.teamId = a;
    w.teams[a].roster.push(star.id);
    expect(starFollowing(w, org)).toBeGreaterThan(0);
    for (let m = 0; m < 8; m++) fansMonthly(w);
    const withStar = fb.seg.player.size;
    expect(withStar).toBeGreaterThan(base * 1.1);
    w.teams[a].roster = w.teams[a].roster.filter((id) => id !== star.id);
    star.teamId = null;
    for (let m = 0; m < 12; m++) fansMonthly(w);
    expect(fb.seg.player.size).toBeLessThan(withStar * 0.8);
    expect(org.fans).toBe(Object.values(fb.seg).reduce((s, x) => s + x.size, 0));
  });

  it('a simulated season: rivalries and fan moods come only from recorded results', () => {
    const w = withUser(makeWorld(['lol', 'cs2'], 'fans-season'));
    advance(w, { kind: 'end-season' }, { stopForUserMatch: false });
    const rivals = Object.values(w.rivalries ?? {});
    expect(rivals.length).toBeGreaterThan(0);
    for (const r of rivals) {
      expect(w.teams[r.teams[0]].titleId).toBe(r.titleId);
      expect(w.teams[r.teams[1]].titleId).toBe(r.titleId);
      expect(r.wins[0] + r.wins[1]).toBe(r.meetings);
      expect(r.meetings + r.transfers).toBeGreaterThan(0);
      for (const m of r.moments) {
        if (!m.fixtureId) continue;
        const f = w.fixtures[m.fixtureId];
        if (f) expect([...f.teams].sort()).toEqual([...r.teams].sort());
      }
    }
    for (const o of Object.values(w.orgs)) {
      if (o.federation || !o.active) continue;
      const fb = o.fanbase!;
      expect(Object.values(fb.seg).reduce((s, x) => s + x.size, 0)).toBe(o.fans);
      for (const s of Object.values(fb.seg)) {
        expect(s.mood).toBeGreaterThanOrEqual(0);
        expect(s.mood).toBeLessThanOrEqual(100);
        expect(Number.isFinite(s.size)).toBe(true);
      }
    }
    expect(Object.values(w.matches).some((m) => (m.audience ?? 0) > 0)).toBe(true);
    expect(w.orgs[w.meta.userOrgId!].fanbase!.history!.length).toBeGreaterThan(6);
  });

  it('a save from before fan segments gets segments with the same followers and rivalries from its history', async () => {
    const w = withUser(makeWorld(['lol'], 'fans-migrate'));
    advance(w, { kind: 'end-season' }, { stopForUserMatch: false });
    advance(w, { kind: 'days', n: 3 }, { stopForUserMatch: false });
    const old = clone(w) as unknown as Record<string, any>;
    old.meta.version = 5;
    old.meta.saveId = 'old-fans';
    delete old.rivalries;
    const fans: Record<string, number> = {};
    for (const o of Object.values<any>(old.orgs)) {
      delete o.fanbase;
      fans[o.id] = o.fans;
    }
    const loaded = await importSave(JSON.stringify(old));
    for (const o of Object.values(loaded.orgs)) {
      if (o.federation) continue;
      expect(o.fanbase).toBeDefined();
      expect(o.fans).toBe(fans[o.id]);
    }
    const finals = loaded.history.flatMap((h) => h.champions.filter((c) => c.runnerUpId));
    expect(finals.length).toBeGreaterThan(0);
    for (const c of finals) if (loaded.teams[c.teamId] && loaded.teams[c.runnerUpId!] && loaded.teams[c.teamId].orgId !== loaded.teams[c.runnerUpId!].orgId) expect(getRivalry(loaded, c.teamId, c.runnerUpId!)).toBeDefined();
    // Seeding is quiet: no rivalry news invented for the past.
    expect(loaded.news.filter((x) => x.kind === 'rivalry').length).toBe(old.news.filter((x: { kind: string }) => x.kind === 'rivalry').length);
  });
});

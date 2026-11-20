import { describe, expect, it } from 'vitest';
import { createWorld, setUserOrg } from '../src/gen/world';
import { advance } from '../src/game/advance';
import { clearRatingCache } from '../src/sim/common/ratings';
import { realTitles, ROSTER_SNAPSHOT } from '../src/gen/realWorld';
import { closeDivision } from '../src/game/rollover';
import { arrangeComeback, canComeBack } from '../src/systems/comeback';
import { RNG } from '../src/core/rng';
import { ensureRealHistory } from '../src/game/historyMigration';
import { validateLineup } from '../src/systems/lineup';
import { START } from './helpers';

function realWorld(titles?: string[]) {
  clearRatingCache();
  return createWorld({ name: 'Real', seed: 'real-test', worldSize: 'tiny', titles, startDay: START, dataMode: 'real' });
}

describe('real-world start (dated roster snapshot)', () => {
  it('builds real leagues, teams and players with provenance; HoK and Wild Rift stay fictional', () => {
    const w = realWorld();
    expect(w.meta.dataMode).toBe('real');
    expect(w.meta.rosterSnapshot).toBe(ROSTER_SNAPSHOT.id);
    expect(realTitles().sort()).toEqual(['cs2', 'dota2', 'lol', 'mlbb', 'valorant']);
    const leagues = Object.values(w.leagues);
    for (const name of ['LCK', 'LPL', 'LEC', 'MPL Indonesia', 'MPL Philippines', 'VCT Pacific']) expect(leagues.some((l) => l.name === name && l.real), name).toBe(true);
    for (const id of ['hok', 'wildrift']) {
      expect(Object.values(w.teams).filter((t) => t.titleId === id).length, id).toBeGreaterThan(0);
      expect(Object.values(w.teams).some((t) => t.titleId === id && t.real), id).toBe(false);
    }
    // Real players carry their source, the snapshot and the list of estimated fields.
    const t1 = Object.values(w.teams).find((t) => t.titleId === 'lol' && t.name === 'T1')!;
    expect(t1.real?.src).toContain('lolesports.com');
    const faker = t1.roster.map((id) => w.players[id]).find((p) => p.handle === 'Faker')!;
    expect(faker.real?.snap).toBe(ROSTER_SNAPSHOT.id);
    expect(faker.roles[0]).toBe('mid');
    expect(faker.real?.est).toContain('age');
    // Every team can field a legal line-up; no real handle is duplicated within a title.
    for (const team of Object.values(w.teams)) if (team.active) expect(validateLineup(w, team, team.lineup).ok, team.name).toBe(true);
    for (const id of realTitles()) {
      const handles = Object.values(w.players).filter((p) => p.titleId === id && p.real).map((p) => p.handle.toLowerCase());
      expect(new Set(handles).size, id).toBe(handles.length);
    }
    // The same real organisation owns its teams in several games.
    const multi = Object.values(w.orgs).filter((o) => o.real && new Set(o.teams.map((id) => w.teams[id].titleId)).size >= 3);
    expect(multi.length).toBeGreaterThan(0);
  });

  it('plays a full season with real leagues of uneven sizes', () => {
    const w = realWorld(['mlbb', 'lol', 'valorant']);
    const org = Object.values(w.orgs).find((o) => o.name === 'T1')!;
    setUserOrg(w, org.id);
    const season = w.season;
    advance(w, { kind: 'end-season' });
    expect(w.season).toBe(season + 1);
    const h = w.history.find((x) => x.season === season)!;
    for (const id of ['mlbb', 'lol', 'valorant']) expect(h.champions.some((c) => c.titleId === id && c.tier === 'worlds'), id).toBe(true);
  }, 120_000);

  it('keeps historical real teams dormant, former pros as free agents and retired pros on record', () => {
    const w = realWorld(['lol', 'mlbb', 'cs2']);
    const dormant = Object.values(w.teams).filter((t) => !t.active && t.real && t.lastSeen);
    expect(dormant.length).toBeGreaterThan(30);
    for (const t of dormant) {
      // Not in any league, not an active division of its organisation, no players.
      expect(t.leagueId, t.name).toBeNull();
      expect(Object.values(w.leagues).some((l) => l.teamIds.includes(t.id)), t.name).toBe(false);
      expect(w.orgs[t.orgId].teams.includes(t.id), t.name).toBe(false);
      expect(t.roster.length).toBe(0);
      expect(t.real!.est!.some((e) => e.startsWith('dormant')), t.name).toBe(true);
    }
    // Leagues contain only active teams with rosters.
    for (const l of Object.values(w.leagues)) for (const id of l.teamIds) expect(w.teams[id].active, w.teams[id].name).toBe(true);
    // Former real pros who are on no current roster are free agents with provenance; no live handle is duplicated.
    const fa = Object.values(w.players).filter((p) => p.real && !p.teamId);
    expect(fa.length).toBeGreaterThan(200);
    for (const p of fa.slice(0, 50)) expect(p.real!.est!.length).toBeGreaterThan(0);
    for (const id of ['lol', 'mlbb', 'cs2']) {
      const handles = Object.values(w.players).filter((p) => p.titleId === id && p.real).map((p) => p.handle.toLowerCase());
      expect(new Set(handles).size, id).toBe(handles.length);
    }
    // Nobody from the snapshot is archived as retired: long-inactive pros are free agents too.
    expect(w.retired.length).toBe(0);
    expect(fa.some((p) => p.real!.est!.some((e) => e.startsWith('free agent —')))).toBe(true);
  });

  it('a dormant real team takes over a league place that is sold; retired pros can come back', () => {
    const w = realWorld(['lol']);
    const lpl = Object.values(w.leagues).find((l) => l.name === 'LPL')!;
    const leaving = w.teams[lpl.teamIds[lpl.teamIds.length - 1]];
    const before = new Set(lpl.teamIds);
    const rng = new RNG('close');
    closeDivision(w, leaving, { world: w, rng, handles: new Set(Object.values(w.players).map((p) => p.handle.toLowerCase())) }, rng);
    expect(lpl.teamIds).not.toContain(leaving.id);
    const nt = w.teams[lpl.teamIds.find((id) => !before.has(id))!];
    expect(nt.real, 'a real team returns').toBeTruthy();
    expect(nt.active).toBe(true);
    expect(nt.leagueId).toBe(lpl.id);
    expect(w.orgs[nt.orgId].active).toBe(true);
    expect(w.orgs[nt.orgId].teams).toContain(nt.id);
    expect(validateLineup(w, nt, nt.lineup).ok).toBe(true);
    expect(nt.real!.est!.some((e) => e.startsWith('dormant'))).toBe(false);
    // Comeback: a real pro who retired during the career can return once, as a free agent.
    const pro = Object.values(w.players).find((p) => p.real && !p.teamId)!;
    w.retired.push({ id: 'p-gone', handle: pro.handle, name: 'Test Pro', nat: pro.nat, titles: ['lol'], retiredSeason: w.season - 1, legacy: 0, trophies: 0, awards: 0, real: pro.real, roleId: pro.roles[0] });
    delete w.players[pro.id];
    const r = w.retired.find((x) => x.id === 'p-gone')!;
    expect(canComeBack(w, r)).toBe(true);
    const res = arrangeComeback(w, r.id, 'user');
    expect(res.ok).toBe(true);
    const p = w.players[res.playerId!];
    expect(p.handle).toBe(r.handle);
    expect(p.teamId).toBeNull();
    expect(p.real?.est).toContain('ability after the comeback (game)');
    expect(arrangeComeback(w, r.id, 'user').ok).toBe(false);
  });

  it('uses the real circuits\' event names for internationals', () => {
    const w = realWorld(['lol', 'valorant', 'dota2']);
    const names = Object.values(w.comps).map((c) => c.name);
    for (const n of ['Mid-Season Invitational', 'League of Legends World Championship', 'VALORANT Masters', 'VALORANT Champions', 'The International']) expect(names.some((x) => x.startsWith(n)), n).toBe(true);
    expect(Object.values(w.comps).filter((c) => c.realName).every((c) => c.tier === 'international' || c.tier === 'worlds')).toBe(true);
  });

  it('adds the history snapshot once to real-world careers saved before it existed', () => {
    const w = realWorld(['lol']);
    const count = () => ({
      dormant: Object.values(w.teams).filter((t) => !t.active && t.lastSeen).length,
      fa: Object.values(w.players).filter((p) => p.real && !p.teamId).length,
    });
    const full = count();
    // Simulate an older save: no history entities.
    for (const t of Object.values(w.teams)) if (!t.active && t.lastSeen) delete w.teams[t.id];
    for (const p of Object.values(w.players)) if (p.real && !p.teamId) delete w.players[p.id];
    delete w.meta.realHistory;
    delete w.meta.realHistoryFree;
    const orgs = Object.keys(w.orgs).length;
    expect(ensureRealHistory(w)).toBe(true);
    expect(count()).toEqual(full);
    // Organisations are reused by name, not duplicated.
    expect(Object.keys(w.orgs).length).toBe(orgs);
    expect(ensureRealHistory(w)).toBe(false);
    expect(count()).toEqual(full);
  });

  it('saves that archived snapshot pros as retired turn them into free agents', () => {
    const w = realWorld(['lol']);
    const fa = Object.values(w.players).filter((p) => p.real && !p.teamId).slice(0, 5);
    fa.forEach((p, i) => {
      w.retired.push({ id: `rr-lol-${i}`, handle: p.handle, name: `${p.first} ${p.last}`, nat: p.nat, titles: ['lol'], retiredSeason: w.season - 4, legacy: 0, trophies: 0, awards: 0, lastTeam: 'Old Team', roleId: p.roles[0], real: { src: 'https://lolesports.com', snap: 'real-2026-11-20', est: ['estimate: no top-league game since 2022'] } });
      delete w.players[p.id];
    });
    delete w.meta.realHistoryFree;
    expect(ensureRealHistory(w)).toBe(true);
    expect(w.retired.filter((r) => r.id.startsWith('rr-')).length).toBe(0);
    for (const p of fa) expect(Object.values(w.players).some((x) => x.handle === p.handle && !x.teamId && !x.retired), p.handle).toBe(true);
  });
});

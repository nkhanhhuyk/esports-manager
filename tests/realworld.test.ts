import { describe, expect, it } from 'vitest';
import { createWorld, setUserOrg } from '../src/gen/world';
import { advance } from '../src/game/advance';
import { clearRatingCache } from '../src/sim/common/ratings';
import { realTitles, ROSTER_SNAPSHOT } from '../src/gen/realWorld';
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
    for (const team of Object.values(w.teams)) expect(validateLineup(w, team, team.lineup).ok, team.name).toBe(true);
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
});

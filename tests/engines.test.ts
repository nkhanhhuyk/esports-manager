import { describe, expect, it } from 'vitest';
import { makeWorld } from './helpers';
import { createSeries, playNextGame, draftStateFor, runSeries } from '../src/sim/series';
import type { Fixture, Team } from '../src/model/types';
import { getTitle, TITLES } from '../src/data/titles/catalogue';
import { computeTeamStrength } from '../src/sim/common/team';
import { runAiDraft, toRecord, validateDraft, applyDraftAction, legalOptions, currentStep } from '../src/sim/common/draft';
import { RNG } from '../src/core/rng';
import { validateLineup } from '../src/systems/lineup';

function fx(id: string, titleId: string, teams: string[], n = 1): Fixture {
  return { id, compId: '', stageId: '', titleId, day: 0, teams, format: { kind: 'bo', n, label: `Bo${n}` }, formatKey: 'standard', status: 'scheduled', detail: true };
}

const TWO_TEAM = TITLES;

describe('every two-team engine produces consistent games', () => {
  for (const t of TWO_TEAM) {
    it(`${t.id} (${t.engine})`, () => {
      const w = makeWorld([t.id], `eng-${t.id}`);
      const teams = Object.values(w.teams).filter((x) => x.titleId === t.id && x.tier === 'main');
      const [a, b] = teams;
      const f = fx(`f-${t.id}`, t.id, [a.id, b.id], t.formats.standard.n);
      const st = createSeries(w, f, true);
      while (!st.done) playNextGame(w, f, st);
      const need = Math.floor(f.format.n / 2) + 1;
      expect(Math.max(...st.wins)).toBe(need);
      for (const g of st.games) {
        expect([0, 1]).toContain(g.winner);
        expect(g.players.length).toBe(t.teamSize * 2);
        for (const l of g.players) {
          expect(Number.isFinite(l.rating)).toBe(true);
          for (const v of Object.values(l.s)) expect(Number.isFinite(v) && v >= 0).toBe(true);
        }
        const sum = (ti: number, k: string) => g.players.filter((l) => l.teamIdx === ti).reduce((acc, l) => acc + (l.s[k] ?? 0), 0);
        // Kills by one side equal deaths of the other (where the title records both).
        expect(sum(0, 'k')).toBe(sum(1, 'd'));
        expect(sum(1, 'k')).toBe(sum(0, 'd'));
        if (t.engine === 'moba') {
          // Only destroying the core wins a MOBA game (never a kill count).
          expect(g.events!.some((e) => e.kind === 'core' && e.team === g.winner)).toBe(true);
          expect(g.summary).toContain(t.moba!.structures.coreName);
        }
        if (t.engine === 'tactical') {
          const rounds = g.score[0] + g.score[1];
          expect(Math.max(...g.score)).toBeGreaterThanOrEqual(t.tactical!.roundsToWin);
          expect(g.score[g.winner]).toBeGreaterThan(g.score[1 - g.winner]);
          for (const l of g.players) expect(l.s.kast).toBeLessThanOrEqual(rounds);
        }
        if (t.draft.mode !== 'none' && t.characters.length) {
          expect(g.draft).toBeTruthy();
          expect(validateDraft(t, g.draft!)).toEqual([]);
        }
        expect(g.events && g.events.length).toBeTruthy();
      }
      // Fearless drafts (optional preset): no character reused across the series where restricted.
      if (t.draft.fearless === 'global') {
        const all = st.games.flatMap((g) => [...(g.draft?.picks[0] ?? []), ...(g.draft?.picks[1] ?? [])]);
        expect(new Set(all).size).toBe(all.length);
      }
      if (t.draft.fearless === 'team') {
        for (const ti of [0, 1]) {
          const all = st.games.flatMap((g) => g.draft?.picks[ti] ?? []);
          expect(new Set(all).size).toBe(all.length);
        }
      }
    });
  }
});

describe('shooter score arithmetic and overtime', () => {
  const maps = (id: string, n: number) => {
    const w = makeWorld([id], `ot-${id}`, 'small');
    const teams = Object.values(w.teams).filter((x) => x.titleId === id && x.tier === 'main');
    const out: number[][] = [];
    for (let i = 0; i < n; i++) {
      const a = teams[i % teams.length];
      const b = teams[(i + 1) % teams.length];
      const st = runSeries(w, fx(`ot-${id}-${i}`, id, [a.id, b.id], 1));
      out.push(st.games[0].score);
    }
    return out;
  };
  it('Valorant: first to 13; at 12-12 play continues until a two-round lead', () => {
    for (const sc of maps('valorant', 60)) {
      const [w, l] = [Math.max(...sc), Math.min(...sc)];
      if (l <= 11) expect(w).toBe(13);
      else expect(w - l).toBe(2);
    }
  });
  it('CS2: MR12, then 6-round blocks (3 per side): 4 wins take a block, 3-3 plays another', () => {
    for (const sc of maps('cs2', 60)) {
      const [w, l] = [Math.max(...sc), Math.min(...sc)];
      if (l <= 11) {
        expect(w).toBe(13);
        continue;
      }
      // w = 12 + 3k + 4 and l = 12 + 3k + x (x = 0..3) for k completed 3-3 blocks.
      expect((w - 16) % 3).toBe(0);
      expect(w - l).toBeGreaterThanOrEqual(1);
      expect(w - l).toBeLessThanOrEqual(4);
    }
  });
});

describe('MOBA series rules', () => {
  it('MLBB: the previous game\'s loser chooses the side and drafts first', () => {
    const w = makeWorld(['mlbb'], 'side');
    const [a, b] = Object.values(w.teams).filter((x) => x.titleId === 'mlbb' && x.tier === 'main');
    for (let k = 0; k < 6; k++) {
      const st = runSeries(w, fx(`side-${k}`, 'mlbb', [a.id, b.id], 7));
      for (let i = 1; i < st.games.length; i++) expect(st.games[i].draft!.firstPick).toBe(1 - st.games[i - 1].winner);
      expect(st.games[0].sides?.[0]).toContain('coin toss');
    }
  });

  it('LoL: champions may return in later games; Fearless only applies when the preset is enabled', () => {
    const w = makeWorld(['lol'], 'fearless');
    const [a, b] = Object.values(w.teams).filter((x) => x.titleId === 'lol' && x.tier === 'main');
    let repeats = 0;
    for (let k = 0; k < 6; k++) {
      const st = runSeries(w, fx(`std-${k}`, 'lol', [a.id, b.id], 5));
      const all = st.games.flatMap((g) => [...g.draft!.picks[0], ...g.draft!.picks[1]]);
      repeats += all.length - new Set(all).size;
    }
    expect(repeats).toBeGreaterThan(0);
    w.rulePresets = { lol: { fearless: 'global' } };
    for (let k = 0; k < 4; k++) {
      const st = runSeries(w, fx(`fl-${k}`, 'lol', [a.id, b.id], 5));
      const all = st.games.flatMap((g) => [...g.draft!.picks[0], ...g.draft!.picks[1]]);
      expect(new Set(all).size).toBe(all.length);
    }
  });

  it('Dota 2 uses buybacks (and no other MOBA does)', () => {
    const w = makeWorld(['dota2', 'lol'], 'bb');
    const [a, b] = Object.values(w.teams).filter((x) => x.titleId === 'dota2' && x.tier === 'main');
    let bb = 0;
    let events = 0;
    for (let k = 0; k < 12; k++) {
      const st = runSeries(w, fx(`bb-${k}`, 'dota2', [a.id, b.id], 1));
      for (const g of st.games) {
        bb += g.players.reduce((acc, l) => acc + (l.s.bb ?? 0), 0);
        events += g.events!.filter((e) => e.kind === 'buyback').length;
        expect(g.teamStats.reduce((acc, ts) => acc + (ts.buybacks ?? 0), 0)).toBe(g.players.reduce((acc, l) => acc + (l.s.bb ?? 0), 0));
      }
    }
    expect(bb).toBeGreaterThan(0);
    expect(events).toBe(bb);
    const [c, d] = Object.values(w.teams).filter((x) => x.titleId === 'lol' && x.tier === 'main');
    const st = runSeries(w, fx('bb-lol', 'lol', [c.id, d.id], 3));
    for (const g of st.games) expect(g.players.every((l) => !l.s.bb)).toBe(true);
  });
});

describe('drafts and lineups', () => {
  it('AI drafts are legal for every character title and respect bans', () => {
    for (const t of TITLES.filter((x) => x.characters.length && x.draft.mode !== 'none')) {
      const w = makeWorld([t.id], `draft-${t.id}`);
      const [a, b] = Object.values(w.teams).filter((x) => x.titleId === t.id);
      const f = fx(`d-${t.id}`, t.id, [a.id, b.id], 3);
      const st = createSeries(w, f);
      const ds = draftStateFor(w, f, st);
      runAiDraft(w, ds, new RNG('d'));
      const rec = toRecord(ds, [0, 0]);
      expect(validateDraft(t, rec), t.id).toEqual([]);
      expect(Object.keys(rec.assign[0]).length).toBe(t.teamSize);
    }
  });

  it('rejects illegal draft actions', () => {
    const w = makeWorld(['lol'], 'illegal');
    const [a, b] = Object.values(w.teams).filter((x) => x.titleId === 'lol');
    const f = fx('il', 'lol', [a.id, b.id], 3);
    const st = createSeries(w, f);
    const ds = draftStateFor(w, f, st);
    const step = currentStep(ds)!;
    const first = legalOptions(ds, step.team, step.action)[0];
    expect(applyDraftAction(ds, first.id).ok).toBe(true);
    // Banned character cannot be banned or picked again.
    expect(applyDraftAction(ds, first.id).ok).toBe(false);
    expect(applyDraftAction(ds, 'not-a-character').ok).toBe(false);
  });

  it('validates lineups against title positions and league rules', () => {
    const w = makeWorld(['valorant'], 'lineup');
    const team = Object.values(w.teams).find((x) => x.titleId === 'valorant')! as Team;
    expect(validateLineup(w, team, team.lineup).ok).toBe(true);
    const dup = [...team.lineup.slice(0, 4), { ...team.lineup[0], roleId: team.lineup[4].roleId }];
    expect(validateLineup(w, team, dup).ok).toBe(false);
    expect(validateLineup(w, team, team.lineup.slice(0, 3)).ok).toBe(false);
  });
});

describe('competitive balance', () => {
  for (const id of ['lol', 'mlbb', 'dota2', 'valorant', 'cs2']) {
    it(`${id}: stronger teams usually win, underdogs sometimes do`, () => {
      const w = makeWorld([id], `bal-${id}`, 'small');
      const teams = Object.values(w.teams).filter((x) => x.titleId === id && x.tier === 'main');
      const byStr = teams.map((x) => ({ x, s: computeTeamStrength(w, x) })).sort((p, q) => q.s - p.s);
      const strong = byStr[0].x;
      const weak = byStr[byStr.length - 1].x;
      expect(byStr[0].s - byStr[byStr.length - 1].s).toBeGreaterThan(3);
      let wins = 0;
      const N = 40;
      for (let i = 0; i < N; i++) {
        const f = fx(`bal-${i}`, id, i % 2 ? [strong.id, weak.id] : [weak.id, strong.id], 1);
        const st = createSeries(w, f);
        while (!st.done) playNextGame(w, f, st);
        if (f.teams[st.winner] === strong.id) wins++;
      }
      expect(wins / N).toBeGreaterThan(0.6);
      expect(wins / N).toBeLessThan(1.0001);
    });
  }
});

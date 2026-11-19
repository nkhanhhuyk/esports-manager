import { describe, expect, it } from 'vitest';
import { makeWorld } from './helpers';
import { createSeries, playNextGame, runBrSeries, draftStateFor } from '../src/sim/series';
import type { Fixture, Team } from '../src/model/types';
import { getTitle, TITLES } from '../src/data/titles/catalogue';
import { computeTeamStrength } from '../src/sim/common/team';
import { runAiDraft, toRecord, validateDraft, applyDraftAction, legalOptions, currentStep } from '../src/sim/common/draft';
import { RNG } from '../src/core/rng';
import { validateLineup } from '../src/systems/lineup';

function fx(id: string, titleId: string, teams: string[], n = 1, kind: 'bo' | 'sets' = 'bo'): Fixture {
  return { id, compId: '', stageId: '', titleId, day: 0, teams, format: { kind, n, setWins: kind === 'sets' ? 2 : undefined, label: '' }, formatKey: 'standard', status: 'scheduled', detail: true };
}

const TWO_TEAM = TITLES.filter((t) => t.engine !== 'br');

describe('every two-team engine produces consistent games', () => {
  for (const t of TWO_TEAM) {
    it(`${t.id} (${t.engine})`, () => {
      const w = makeWorld([t.id], `eng-${t.id}`);
      const teams = Object.values(w.teams).filter((x) => x.titleId === t.id && x.tier === 'main');
      const [a, b] = teams;
      const f = fx(`f-${t.id}`, t.id, [a.id, b.id], t.formats.standard.n, t.formats.standard.kind === 'sets' ? 'sets' : 'bo');
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
        if (t.engine === 'moba' || t.engine === 'hybrid' || t.engine === 'tactical' || t.engine === 'arena' || t.engine === 'brawl') {
          expect(sum(0, 'k')).toBe(sum(1, 'd'));
          expect(sum(1, 'k')).toBe(sum(0, 'd'));
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
      // Fearless drafts: no character reused across the series where restricted.
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

describe('battle royale lobbies', () => {
  for (const t of TITLES.filter((x) => x.engine === 'br')) {
    it(`${t.id} aggregates full-lobby games`, () => {
      const w = makeWorld([t.id], `br-${t.id}`);
      const teams = Object.values(w.teams).filter((x) => x.titleId === t.id);
      const f = fx(`f-${t.id}`, t.id, teams.map((x) => x.id), 6);
      f.format = { kind: 'br', n: 6, label: '6 games', matchPoint: t.br!.matchPointFinal };
      const r = runBrSeries(w, f, true);
      expect(r.games.length).toBeGreaterThanOrEqual(6);
      for (const g of r.games) {
        expect(g.placements!.length).toBe(teams.length);
        expect(new Set(g.placements).size).toBe(teams.length);
        const kills = g.players.reduce((a, l) => a + l.s.k, 0);
        expect(kills).toBe(Object.values(g.brKills!).reduce((a, b) => a + b, 0));
      }
      expect(r.winner).toBeTruthy();
    });
  }
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
  for (const id of ['lol', 'mlbb', 'valorant', 'overwatch', 'cod', 'deadlock', 'brawlstars']) {
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
        const f = fx(`bal-${i}`, id, i % 2 ? [strong.id, weak.id] : [weak.id, strong.id], 1, getTitle(id).formats.standard.kind === 'sets' ? 'sets' : 'bo');
        if (f.format.kind === 'sets') f.format.n = 1;
        const st = createSeries(w, f);
        while (!st.done) playNextGame(w, f, st);
        if (f.teams[st.winner] === strong.id) wins++;
      }
      expect(wins / N).toBeGreaterThan(0.6);
      expect(wins / N).toBeLessThan(1.0001);
    });
  }
});

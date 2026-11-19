import { describe, expect, it } from 'vitest';
import { advance, processDay } from '../src/game/advance';
import { clone, digest, makeWorld, withUser } from './helpers';
import { monthKey } from '../src/core/date';
import { processMonthlyFinances } from '../src/systems/finance';

describe('time progression', () => {
  it('processes each day once: repeated processing never duplicates fixtures, money or awards', () => {
    const w = makeWorld(['lol', 'valorant'], 'idem');
    advance(w, { kind: 'until', day: w.day + 70 });
    const before = digest(w);
    // Re-running the bookkeeping for an already-processed month is a no-op.
    const bal = Object.values(w.orgs).map((o) => o.balance);
    w.processed.lastMonth = monthKey(w.day);
    processMonthlyFinances(w);
    expect(Object.values(w.orgs).map((o) => o.balance)).toEqual(bal);
    // Rewinding the calendar pointer and re-processing a day must not replay it.
    const saved = w.day;
    w.day = saved - 1;
    processDay(w);
    expect(w.day).toBe(saved);
    expect(digest(w)).toBe(before);
  });

  it('one long advance equals many short advances (same seed and state)', () => {
    const a = makeWorld(['mlbb', 'cs2'], 'chunks');
    const b = clone(a);
    advance(a, { kind: 'until', day: a.day + 75 });
    for (let i = 0; i < 75; i++) advance(b, { kind: 'days', n: 1 }, { stopForUserMatch: false });
    expect(digest(b)).toBe(digest(a));
  });

  it('stops before user match days and resumes when acknowledged', () => {
    const w = withUser(makeWorld(['valorant'], 'stop'));
    const r = advance(w, { kind: 'next-event' });
    let res = r;
    for (let i = 0; i < 5 && res.stoppedFor !== 'user-match'; i++) res = advance(w, { kind: 'next-event' });
    expect(res.stoppedFor).toBe('user-match');
    const f = w.fixtures[res.fixtureId!];
    expect(f.day).toBe(w.day + 1);
    expect(f.status).toBe('scheduled');
    const r2 = advance(w, { kind: 'days', n: 1 });
    expect(r2.days).toBe(1);
    expect(w.fixtures[res.fixtureId!].status).toBe('done');
  });
});

describe('determinism & save/load consistency', () => {
  it('a saved world advanced after reload produces identical results', () => {
    const w = makeWorld(['hok', 'cs2', 'dota2'], 'det');
    advance(w, { kind: 'until', day: w.day + 60 });
    const saved = JSON.stringify(w);
    const a = JSON.parse(saved);
    const b = JSON.parse(saved);
    advance(a, { kind: 'until', day: a.day + 90 });
    advance(b, { kind: 'until', day: b.day + 90 });
    expect(digest(a)).toBe(digest(b));
    expect(JSON.stringify(a.matches)).toBe(JSON.stringify(b.matches));
  });

  it('watching a match in full detail does not change its result', () => {
    const base = makeWorld(['valorant'], 'detail');
    const a = clone(base);
    const b = clone(base);
    for (const f of Object.values(b.fixtures)) f.detail = true;
    advance(a, { kind: 'until', day: a.day + 70 });
    advance(b, { kind: 'until', day: b.day + 70 });
    const res = (w: typeof a) => Object.values(w.fixtures).filter((f) => f.status === 'done').map((f) => `${f.id}:${f.winner}:${f.score}`).sort().join('|');
    expect(res(b)).toBe(res(a));
  });
});

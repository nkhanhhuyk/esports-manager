/**
 * Turns names inside free text (inbox, news, commentary, history notes) into tappable links:
 * teams (name and short code), organisations, players (handle), staff, competitions, leagues and
 * game titles. Matching is exact and case-sensitive on whole words, longest name first, so
 * "Team Elite" links the team rather than a player called "Elite". Items with explicit refs (news)
 * link those first.
 */
import { Fragment, type ReactNode } from 'react';
import type { World } from '../../model/types';
import { TITLES } from '../../data/titles/catalogue';
import { Link } from '../router';

interface Entry {
  to: string;
  kind: string;
}

/** Words that are never linked even if a team code or handle happens to match them. */
const STOP = new Set(['MVP', 'KDA', 'ADR', 'BO1', 'BO3', 'BO5', 'BO7', 'GG', 'OK', 'AM', 'PM', 'VS', 'vs', 'The', 'Team', 'New', 'Win', 'Loss', 'Match', 'Game', 'Map', 'Round', 'Final', 'Finals', 'Season', 'Split', 'Board', 'Finance', 'Media', 'Medical', 'Contracts', 'Scouting', 'Commercial', 'Director', 'Coach']);
const MAX_WORDS = 7;

let cache: { key: string; map: Map<string, Entry> } | null = null;

function indexKey(world: World): string {
  return `${world.meta.saveId}|${world.day}|${world.counter}|${Object.keys(world.players).length}`;
}

function buildIndex(world: World): Map<string, Entry> {
  const m = new Map<string, Entry>();
  const add = (name: string | undefined, to: string, kind: string, minLen = 2) => {
    if (!name) return;
    const n = name.trim();
    if (n.length < minLen || STOP.has(n) || m.has(n)) return;
    m.set(n, { to, kind });
  };
  // Order = priority when two entities share a name.
  for (const t of TITLES) {
    add(t.name, `/title/${t.id}`, 'title');
    add(t.short, `/title/${t.id}`, 'title');
  }
  for (const c of Object.values(world.comps)) {
    add(c.name, `/comp/${c.id}`, 'comp');
    add(c.short, `/comp/${c.id}`, 'comp', 4);
  }
  for (const o of Object.values(world.orgs)) if (!o.federation) add(o.name, `/org/${o.id}`, 'org', 3);
  for (const t of Object.values(world.teams)) {
    add(t.name, `/team/${t.id}`, 'team', 3);
    if (/^[A-Z0-9]{2,5}$/.test(t.short) && t.active) add(t.short, `/team/${t.id}`, 'team', 2);
  }
  for (const p of Object.values(world.players)) if (!p.retired || p.rep >= 50) add(p.handle, `/player/${p.id}`, 'player', 3);
  for (const s of Object.values(world.staff)) if (!s.retired) add(`${s.first} ${s.last}`, `/staff/${s.id}`, 'staff', 5);
  return m;
}

function index(world: World): Map<string, Entry> {
  const key = indexKey(world);
  if (!cache || cache.key !== key) cache = { key, map: buildIndex(world) };
  return cache.map;
}

const WORD = /[\p{L}\p{N}][\p{L}\p{N}'’._-]*[\p{L}\p{N}]|[\p{L}\p{N}]/gu;

/** Split text into plain strings and linked names. */
export function linkify(world: World, text: string, extra?: Map<string, Entry>): ReactNode[] {
  const idx = index(world);
  const words: Array<{ s: number; e: number }> = [];
  for (const m of text.matchAll(WORD)) words.push({ s: m.index!, e: m.index! + m[0].length });
  const out: ReactNode[] = [];
  let pos = 0;
  let i = 0;
  let k = 0;
  while (i < words.length) {
    let hit: { end: number; entry: Entry; next: number } | null = null;
    for (let n = Math.min(MAX_WORDS, words.length - i); n >= 1; n--) {
      const s = words[i].s;
      const e = words[i + n - 1].e;
      const name = text.slice(s, e);
      const entry = extra?.get(name) ?? idx.get(name);
      if (entry) {
        hit = { end: e, entry, next: i + n };
        break;
      }
    }
    if (hit) {
      const s = words[i].s;
      if (s > pos) out.push(text.slice(pos, s));
      out.push(
        <Link key={k++} to={hit.entry.to} className="elink">
          {text.slice(s, hit.end)}
        </Link>,
      );
      pos = hit.end;
      i = hit.next;
    } else i++;
  }
  if (pos < text.length) out.push(text.slice(pos));
  return out;
}

/** Text with entity names linked. Line breaks are kept. */
export function RichText({ world, text, refs }: { world: World; text: string; refs?: Array<{ type: string; id: string }> }) {
  let extra: Map<string, Entry> | undefined;
  if (refs?.length) {
    extra = new Map();
    for (const r of refs) {
      if (r.type === 'player' && world.players[r.id]) extra.set(world.players[r.id].handle, { to: `/player/${r.id}`, kind: 'player' });
      else if (r.type === 'team' && world.teams[r.id]) {
        extra.set(world.teams[r.id].name, { to: `/team/${r.id}`, kind: 'team' });
        extra.set(world.teams[r.id].short, { to: `/team/${r.id}`, kind: 'team' });
      } else if (r.type === 'org' && world.orgs[r.id]) extra.set(world.orgs[r.id].name, { to: `/org/${r.id}`, kind: 'org' });
      else if (r.type === 'comp' && world.comps[r.id]) extra.set(world.comps[r.id].name, { to: `/comp/${r.id}`, kind: 'comp' });
      else if (r.type === 'staff' && world.staff[r.id]) extra.set(`${world.staff[r.id].first} ${world.staff[r.id].last}`, { to: `/staff/${r.id}`, kind: 'staff' });
    }
  }
  const lines = text.split('\n');
  return (
    <>
      {lines.map((line, i) => (
        <Fragment key={i}>
          {i > 0 && <br />}
          {linkify(world, line, extra)}
        </Fragment>
      ))}
    </>
  );
}

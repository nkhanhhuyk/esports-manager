/**
 * Saves created before the real-character snapshot used generated fictional heroes/champions/
 * agents. On load they are remapped, deterministically and once, to real characters of the same
 * class (proficiencies, hero pools, bans, patch meta and recorded drafts), so careers keep their
 * shape. The world records which snapshot it uses.
 */
import { legacyCharacters, TITLES } from '../data/titles/catalogue';
import { CHARACTER_SNAPSHOT } from '../data/titles/realCharacters';
import type { World } from '../model/types';
import { addInbox } from '../systems/news';

export function ensureRealCharacters(world: World): boolean {
  if (world.meta.characterSnapshot) return false;
  const remap = new Map<string, string>();
  for (const t of TITLES) {
    if (!t.characters.length || !t.characters[0].real) continue;
    const old = legacyCharacters(t);
    const byCls = new Map<string, string[]>();
    for (const c of t.characters) {
      if (!byCls.has(c.cls)) byCls.set(c.cls, []);
      byCls.get(c.cls)!.push(c.id);
    }
    const used = new Map<string, number>();
    for (const o of old) {
      const list = byCls.get(o.cls) ?? t.characters.map((c) => c.id);
      const k = used.get(o.cls) ?? 0;
      used.set(o.cls, k + 1);
      remap.set(o.id, list[k % list.length]);
    }
  }
  if (!remap.size) {
    world.meta.characterSnapshot = CHARACTER_SNAPSHOT.id;
    return false;
  }
  const r = (id: string) => remap.get(id) ?? id;
  const mergeMax = (rec: Record<string, number>) => {
    const out: Record<string, number> = {};
    for (const [k, v] of Object.entries(rec)) out[r(k)] = Math.max(out[r(k)] ?? 0, v);
    return out;
  };
  for (const p of Object.values(world.players)) for (const [tid, rec] of Object.entries(p.prof.chars)) p.prof.chars[tid] = mergeMax(rec);
  for (const team of Object.values(world.teams)) {
    for (const [role, ids] of Object.entries(team.prefs.pool ?? {})) team.prefs.pool[role] = [...new Set(ids.map(r))];
    team.prefs.bans = [...new Set((team.prefs.bans ?? []).map(r))];
  }
  for (const ts of Object.values(world.titles)) {
    for (const patch of ts.patches) {
      const meta: Record<string, number> = {};
      const n: Record<string, number> = {};
      for (const [k, v] of Object.entries(patch.charMeta ?? {})) {
        meta[r(k)] = (meta[r(k)] ?? 0) + v;
        n[r(k)] = (n[r(k)] ?? 0) + 1;
      }
      for (const k of Object.keys(meta)) meta[k] = Math.round((meta[k] / n[k]) * 10) / 10;
      patch.charMeta = meta;
    }
  }
  for (const m of Object.values(world.matches)) {
    for (const g of m.games) {
      for (const pl of g.players ?? []) if (pl.charId) pl.charId = r(pl.charId);
      const d = g.draft;
      if (!d) continue;
      d.bans = [d.bans[0].map(r), d.bans[1].map(r)];
      d.picks = [d.picks[0].map(r), d.picks[1].map(r)];
      for (const o of d.order) o.charId = r(o.charId);
      d.assign = [Object.fromEntries(Object.entries(d.assign[0]).map(([p, c]) => [p, r(c)])), Object.fromEntries(Object.entries(d.assign[1]).map(([p, c]) => [p, r(c)]))];
    }
  }
  for (const o of Object.values(world.overrides ?? {})) if (o.drafts) for (const d of Object.values(o.drafts)) for (const x of d.order) x.charId = r(x.charId);
  world.meta.characterSnapshot = CHARACTER_SNAPSHOT.id;
  addInbox(world, { from: 'Esports Manager', subject: 'Real heroes, champions and agents', body: `This save now uses the real character rosters of every game (snapshot ${CHARACTER_SNAPSHOT.asOf}). Players' hero/champion/agent experience, hero pools, bans and past drafts were carried over to real characters of the same class.`, kind: 'board' });
  return true;
}

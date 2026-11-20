/**
 * Patch & metagame evolution. Each title has its own version history and three kinds of patch:
 *
 * - **Minor** (random during the splits, at most one a week, never while an international is being
 *   played): a few champion/agent buffs and nerfs, sometimes an item (class-wide) adjustment.
 * - **Medium** (10 days before First Stand, the Mid-Season Invitational and the World Championship):
 *   many more buffs and nerfs, class/item changes, favoured styles shift, objective values and
 *   sometimes the shooter map pool — the meta moves.
 * - **Major** (right after the World Championship; it opens next season's numbering): everything a
 *   medium patch does plus 2–3 gameplay changes that the simulations read through
 *   src/sim/common/rules.ts — e.g. an objective spawning twice instead of once, a new objective
 *   (Atakhan, a second Turtle …), an objective granting a different reward, spawn and respawn timers,
 *   gold income, structure health, death timers; for shooters the bomb/spike and round timers,
 *   economy and the attack/defence balance. Rules accumulate, so every season plays differently.
 *
 * Teams' `metaAdapt` drops after a patch (minor 6, medium 16, major 30) and recovers at a rate driven
 * by players' Patch Adaptation / Meta Understanding and the coaching staff's Meta Reading.
 */
import { clamp } from '../core/math';
import { RNG } from '../core/rng';
import { getTitle } from '../data/titles/catalogue';
import type { ObjectiveDef, TitleDef } from '../data/titles/types';
import type { ObjectiveKind, Patch, PatchKind, PatchObjective, PatchRules, World } from '../model/types';
import { mobaRules, tacticalRules } from '../sim/common/rules';
import { addNews } from './news';

export function initialPatch(world: World, titleId: string): Patch {
  const t = getTitle(titleId);
  const rng = new RNG(`${world.meta.seed}|patch0|${titleId}`);
  const charMeta: Record<string, number> = {};
  for (const c of t.characters) charMeta[c.id] = Math.round(clamp(rng.normal(0, 2.5), -10, 10) * 10) / 10;
  const styles: Record<string, number> = {};
  for (const s of t.styles) styles[s] = Math.round(clamp(1 + rng.normal(0, 0.15), 0.6, 1.5) * 100) / 100;
  const pool = t.activePool?.length ? [...t.activePool] : t.maps.length > t.mapPool ? rng.sample(t.maps, t.mapPool).map((m) => m.id) : t.maps.map((m) => m.id);
  const yy = world.season % 100;
  return { id: `${titleId}-${yy}.01`, version: `${yy}.01`, day: world.day, season: world.season, major: true, kind: 'major', charMeta, styles, mapPool: pool, objectiveMods: {}, notes: ['Season opening patch.'], era: 'Modern era' };
}

export function currentPatch(world: World, titleId: string): Patch {
  const ps = world.titles[titleId].patches;
  return ps[ps.length - 1];
}

const KIND_PHRASE: Record<ObjectiveKind, string> = {
  gold: 'team gold',
  buff: 'a team-wide combat buff',
  shield: 'a team shield',
  xp: 'bonus experience',
  siege: 'a structure-damage buff',
  stack: 'a stacking bonus',
  push: 'a summoned pusher',
  execute: 'an execute buff',
  aegis: 'an extra life',
  score: 'points',
};
const KIND_VALUE: Partial<Record<ObjectiveKind, number>> = { gold: 150, buff: 0.06, shield: 0.08, xp: 0.03, siege: 0.05 };
const SWAPPABLE: ObjectiveKind[] = ['gold', 'buff', 'shield', 'xp', 'siege'];

/** New objectives a major patch can introduce (game patches, not real announcements). */
const NEW_OBJECTIVES: Record<string, PatchObjective[]> = {
  lol: [
    { id: 'atakhan', name: 'Atakhan', kind: 'buff', value: 0.08, spawn: 20, respawn: 99, maxSpawns: 1, major: true, pitAs: 'drake' },
    { id: 'scuttler', name: 'Rift Scuttler', kind: 'xp', value: 0.03, spawn: 3.5, respawn: 2.5, until: 14, pitAs: 'grubs' },
  ],
  wildrift: [
    { id: 'grubs', name: 'Void Grubs', kind: 'siege', value: 0.05, spawn: 5, respawn: 3, until: 9.9, pitAs: 'herald' },
    { id: 'atakhan', name: 'Atakhan', kind: 'buff', value: 0.08, spawn: 14, respawn: 99, maxSpawns: 1, major: true, pitAs: 'drake' },
  ],
  mlbb: [
    { id: 'turtle2', name: 'Second Turtle', kind: 'gold', value: 160, spawn: 2, respawn: 2, until: 7.9, pitAs: 'turtle' },
    { id: 'ancient-litho', name: 'Ancient Lithowanderer', kind: 'xp', value: 0.03, spawn: 5, respawn: 4, until: 14, pitAs: 'lord' },
  ],
  hok: [
    { id: 'storm-spirit', name: 'Storm Spirit', kind: 'shield', value: 0.08, spawn: 6, respawn: 5, until: 15, pitAs: 'tyrant' },
    { id: 'twin-overlord', name: 'Twin Overlord', kind: 'push', value: 0.18, spawn: 12, respawn: 99, maxSpawns: 1, pitAs: 'overlord' },
  ],
  dota2: [
    { id: 'lotus-guardian', name: 'Lotus Guardian', kind: 'shield', value: 0.08, spawn: 6, respawn: 6, pitAs: 'tormentor' },
    { id: 'twin-tormentor', name: 'Twin Tormentor', kind: 'buff', value: 0.04, spawn: 20, respawn: 10, pitAs: 'tormentor' },
  ],
};

const mmss = (min: number) => `${Math.floor(min)}:${String(Math.round((min % 1) * 60)).padStart(2, '0')}`;
const pct = (x: number) => `${x >= 1 ? '+' : '−'}${Math.round(Math.abs(x - 1) * 100)}%`;

/** One gameplay change for a MOBA (mutates `rules`); returns the patch note, or null if nothing applied. */
function mobaChange(t: TitleDef, prev: Patch, rules: PatchRules, rng: RNG, used: Set<string>): string | null {
  const cfg = mobaRules(t, { ...prev, rules });
  const objs = cfg.objectives;
  const base = (id: string) => t.moba!.objectives.find((o) => o.id === id);
  const set = (o: ObjectiveDef, c: NonNullable<PatchRules['objectives']>[string]) => {
    const added = rules.added?.find((a) => a.id === o.id);
    if (added) Object.assign(added, Object.fromEntries(Object.entries(c).filter(([, v]) => v !== null)));
    else rules.objectives = { ...(rules.objectives ?? {}), [o.id]: { ...(rules.objectives?.[o.id] ?? {}), ...c } };
    used.add(o.id);
  };
  const free = objs.filter((o) => !used.has(o.id));
  const pick = <T,>(xs: T[]) => (xs.length ? rng.pick(xs) : undefined);
  const options = ['spawns', 'timing', 'respawn', 'reward', 'value', 'add', 'economy'];
  for (let attempt = 0; attempt < 8; attempt++) {
    const what = rng.pick(options);
    if (what === 'spawns') {
      // Once → twice, or a repeating objective limited to a number of spawns.
      const once = pick(free.filter((o) => o.respawn >= 90 || o.maxSpawns === 1));
      if (once) {
        const rs = Math.round(rng.float(3.5, 5.5) * 2) / 2;
        set(once, { respawn: rs, maxSpawns: 2, ...(once.until !== undefined ? { until: Math.round((once.until + rs) * 10) / 10 } : {}) });
        return `${once.name} now spawns twice per game (again ${mmss(rs)} after the first is taken).`;
      }
      const rep = pick(free.filter((o) => o.respawn < 90 && !o.major && o.maxSpawns === undefined));
      if (rep) {
        const n = rng.int(2, 3);
        set(rep, { maxSpawns: n });
        return `${rep.name} now spawns at most ${n} times per game.`;
      }
    } else if (what === 'timing') {
      const o = pick(free);
      if (o) {
        const d = rng.pick([-2, -1.5, -1, 1, 1.5, 2]);
        const b = base(o.id);
        const spawn = clamp(Math.round((o.spawn + d) * 2) / 2, 1.5, (b?.spawn ?? o.spawn) + 4);
        if (spawn === o.spawn) continue;
        const shift = spawn - o.spawn;
        set(o, { spawn, ...(o.until !== undefined ? { until: Math.round((o.until + shift) * 10) / 10 } : {}) });
        return `${o.name} first spawns at ${mmss(spawn)} (was ${mmss(o.spawn)}).`;
      }
    } else if (what === 'respawn') {
      const o = pick(free.filter((x) => x.respawn < 90));
      if (o) {
        const f = rng.float(0.7, 1.35);
        const respawn = clamp(Math.round(o.respawn * f * 2) / 2, 1.5, 12);
        if (respawn === o.respawn) continue;
        set(o, { respawn });
        return `${o.name} respawn timer ${mmss(o.respawn)} → ${mmss(respawn)}.`;
      }
    } else if (what === 'reward') {
      const o = pick(free.filter((x) => !x.major && SWAPPABLE.includes(x.kind)));
      if (o) {
        const kind = rng.pick(SWAPPABLE.filter((k) => k !== o.kind));
        set(o, { kind, value: KIND_VALUE[kind] });
        return `${o.name} now grants ${KIND_PHRASE[kind]} instead of ${KIND_PHRASE[o.kind]}.`;
      }
    } else if (what === 'value') {
      const o = pick(free);
      if (o) {
        const f = rng.pick([rng.float(0.65, 0.85), rng.float(1.2, 1.5)]);
        const b = base(o.id);
        const value = Math.round(clamp(o.value * f, (b?.value ?? o.value) * 0.5, (b?.value ?? o.value) * 2) * 1000) / 1000;
        if (value === o.value) continue;
        set(o, { value });
        return `${o.name}: ${KIND_PHRASE[o.kind]} ${pct(value / o.value)}.`;
      }
    } else if (what === 'add') {
      // A new objective, or a patch-added one leaves the map again.
      const added = rules.added ?? [];
      if (added.length && rng.chance(0.3)) {
        const gone = rng.pick(added);
        rules.added = added.filter((a) => a !== gone);
        return `${gone.name} is removed from the map.`;
      }
      const cand = (NEW_OBJECTIVES[t.id] ?? []).filter((a) => !objs.some((o) => o.id === a.id) && objs.some((o) => o.id === a.pitAs));
      const a = pick(cand);
      if (a) {
        rules.added = [...added, { ...a }];
        used.add(a.id);
        const pitName = objs.find((o) => o.id === a.pitAs)?.name ?? a.pitAs;
        return `New objective: ${a.name} — ${KIND_PHRASE[a.kind]}, first at ${mmss(a.spawn)} (${pitName} area)${a.maxSpawns === 1 ? ', once per game' : ''}.`;
      }
    } else if (what === 'economy') {
      const m = { ...(rules.moba ?? {}) };
      const which = rng.pick(['gpm', 'structHp', 'respawn'] as const);
      const f = rng.pick([rng.float(0.86, 0.94), rng.float(1.06, 1.16)]);
      const lim = which === 'gpm' ? [0.8, 1.25] : which === 'structHp' ? [0.75, 1.4] : [0.75, 1.35];
      const was = m[which] ?? 1;
      const next = Math.round(clamp(was * f, lim[0], lim[1]) * 100) / 100;
      if (next === was) continue;
      m[which] = next;
      rules.moba = m;
      const label = which === 'gpm' ? 'Gold income' : which === 'structHp' ? `${t.moba!.structures.tierNames[0]} and structure health` : 'Death timers';
      return `${label} ${pct(next / was)}.`;
    }
  }
  return null;
}

/** One gameplay change for a tactical shooter (mutates `rules`). */
function shooterChange(t: TitleDef, prev: Patch, rules: PatchRules, rng: RNG, used: Set<string>): string | null {
  const cfg = tacticalRules(t, { ...prev, rules });
  const r = { ...(rules.tactical ?? {}) };
  const cs = t.id === 'cs2';
  const base = { roundTime: cs ? 115 : 100, bombTime: cs ? 40 : 45, plantTime: cs ? 3.2 : 4, defuseTime: cs ? 5 : 7 };
  const options = ['bomb', 'round', 'plant', 'economy', 'loss', 'edge'].filter((o) => !used.has(o));
  if (!options.length) return null;
  const what = rng.pick(options);
  used.add(what);
  let note: string;
  if (what === 'bomb') {
    const old = r.bombTime ?? base.bombTime;
    r.bombTime = clamp(old + rng.pick([-5, -3, 3, 5]), base.bombTime - 8, base.bombTime + 10);
    note = `${t.tactical!.objectiveName} timer ${old}s → ${r.bombTime}s.`;
  } else if (what === 'round') {
    const old = r.roundTime ?? base.roundTime;
    r.roundTime = clamp(old + rng.pick([-10, -5, 5, 10]), base.roundTime - 15, base.roundTime + 15);
    note = `Round timer ${mmss(old / 60)} → ${mmss(r.roundTime / 60)}.`;
  } else if (what === 'plant') {
    const oldP = r.plantTime ?? base.plantTime;
    const oldD = r.defuseTime ?? base.defuseTime;
    const d = rng.pick([-0.5, 0.5, 1]);
    r.plantTime = Math.round(clamp(oldP + d, base.plantTime - 1, base.plantTime + 1.5) * 10) / 10;
    r.defuseTime = Math.round(clamp(oldD + d, base.defuseTime - 1.5, base.defuseTime + 2) * 10) / 10;
    note = `Plant ${oldP}s → ${r.plantTime}s, defuse ${oldD}s → ${r.defuseTime}s.`;
  } else if (what === 'economy') {
    const old = cfg.winReward;
    r.winReward = Math.round(clamp(old + rng.pick([-300, -200, 200, 300]) * (cs ? 1 : 0.5), t.tactical!.winReward * 0.7, t.tactical!.winReward * 1.3) / 50) * 50;
    r.killReward = Math.round(clamp(cfg.killReward + rng.pick([-50, 50, 100]) * (cs ? 1 : 0.5), t.tactical!.killReward * 0.6, t.tactical!.killReward * 1.6) / 25) * 25;
    note = `Economy: round win ${old} → ${r.winReward}, kill reward ${cfg.killReward} → ${r.killReward}.`;
  } else if (what === 'loss') {
    const old = r.lossMult ?? 1;
    r.lossMult = Math.round(clamp(old * rng.pick([0.85, 0.9, 1.1, 1.15]), 0.75, 1.3) * 100) / 100;
    note = `Loss bonus ${pct(r.lossMult / old)}.`;
  } else {
    const old = r.defenderEdge ?? 0;
    const d = rng.pick([-0.12, -0.08, 0.08, 0.12]);
    r.defenderEdge = Math.round(clamp(old + d, -0.3, 0.3) * 100) / 100;
    note = d > 0 ? 'Utility and cover changes favour defenders.' : 'Utility and entry changes favour attackers.';
  }
  rules.tactical = r;
  return note;
}

const KIND_SETTINGS: Record<PatchKind, { decay: number; sd: number; count: [number, number]; mag: [number, number]; classes: [number, number]; classMag: [number, number]; styleSd: number; adapt: number }> = {
  minor: { decay: 0.95, sd: 0.5, count: [2, 3], mag: [1, 2.5], classes: [0, 1], classMag: [0.5, 1], styleSd: 0.04, adapt: 6 },
  medium: { decay: 0.85, sd: 1.5, count: [6, 9], mag: [2, 5], classes: [1, 2], classMag: [1, 2], styleSd: 0.12, adapt: 16 },
  major: { decay: 0.75, sd: 2.5, count: [8, 12], mag: [3, 6], classes: [2, 3], classMag: [1.5, 3], styleSd: 0.2, adapt: 30 },
};

/**
 * Release a patch. `kind` true/false (older callers) mean major/minor. A major patch released after
 * the World Championship belongs to the next season (`nextSeason`): it opens that season's numbering.
 */
export function releasePatch(world: World, titleId: string, kindIn: PatchKind | boolean, opts: { nextSeason?: boolean } = {}): Patch {
  const kind: PatchKind = kindIn === true ? 'major' : kindIn === false ? 'minor' : kindIn;
  const K = KIND_SETTINGS[kind];
  const t = getTitle(titleId);
  const prev = currentPatch(world, titleId);
  const rng = new RNG(`${world.meta.seed}|patch|${titleId}|${world.day}|${kind}`);
  const season = opts.nextSeason ? world.season + 1 : world.season;
  const yy = season % 100;
  const sameSeason = world.titles[titleId].patches.filter((p) => p.season === season).length;
  const version = `${yy}.${String(sameSeason + 1).padStart(2, '0')}`;
  const notes: string[] = [];
  const charMeta: Record<string, number> = {};
  for (const c of t.characters) {
    const old = prev.charMeta[c.id] ?? 0;
    charMeta[c.id] = Math.round(clamp(old * K.decay + rng.normal(0, K.sd), -10, 10) * 10) / 10;
  }
  const label = t.charLabel?.toLowerCase() ?? 'character';
  if (t.characters.length) {
    const n = rng.int(K.count[0], K.count[1]);
    const sorted = [...t.characters].sort((a, b) => (prev.charMeta[b.id] ?? 0) - (prev.charMeta[a.id] ?? 0));
    const nerfs = sorted.slice(0, n);
    const buffs = rng.sample(sorted.slice(Math.floor(sorted.length / 2)), n);
    for (const c of nerfs) charMeta[c.id] = Math.round(clamp(charMeta[c.id] - rng.float(K.mag[0], K.mag[1]), -10, 10) * 10) / 10;
    for (const c of buffs) charMeta[c.id] = Math.round(clamp(charMeta[c.id] + rng.float(K.mag[0], K.mag[1]), -10, 10) * 10) / 10;
    notes.push(`Nerfs: ${nerfs.map((c) => c.name).join(', ')}.`);
    notes.push(`Buffs: ${buffs.map((c) => c.name).join(', ')}.`);
    // Item (MOBA) / utility (agents) changes hit a whole class.
    const classes = [...new Set(t.characters.map((c) => c.cls).filter(Boolean))] as string[];
    const nc = Math.min(classes.length, rng.int(K.classes[0], K.classes[1]));
    for (const cls of rng.sample(classes, nc)) {
      const d = rng.pick([-1, 1]) * rng.float(K.classMag[0], K.classMag[1]);
      for (const c of t.characters) if (c.cls === cls) charMeta[c.id] = Math.round(clamp(charMeta[c.id] + d, -10, 10) * 10) / 10;
      notes.push(`${t.moba ? `${cls} items` : `${cls} ${label} utility`} ${d > 0 ? 'buffed' : 'nerfed'}.`);
    }
  }
  const styles: Record<string, number> = {};
  for (const s of t.styles) styles[s] = Math.round(clamp((prev.styles[s] ?? 1) * 0.9 + 0.1 + rng.normal(0, K.styleSd), 0.55, 1.6) * 100) / 100;
  const favoured = Object.entries(styles).sort((a, b) => b[1] - a[1])[0]?.[0];
  if (favoured && kind !== 'minor') notes.push(`Meta shifts toward ${favoured} play.`);
  let mapPool = [...prev.mapPool];
  if (kind !== 'minor' && t.maps.length > t.mapPool && rng.chance(kind === 'major' ? 0.6 : 0.25)) {
    const out = rng.pick(mapPool);
    const candidates = t.maps.filter((m) => !mapPool.includes(m.id));
    if (candidates.length) {
      const inn = rng.pick(candidates);
      mapPool = mapPool.map((m) => (m === out ? inn.id : m));
      notes.push(`Map pool: ${t.maps.find((m) => m.id === out)?.name} out, ${inn.name} in.`);
    }
  }
  const objectiveMods = { ...prev.objectiveMods };
  if (kind === 'medium' && t.moba) {
    const obj = rng.pick(mobaRules(t, prev).objectives);
    objectiveMods[obj.id] = Math.round(clamp((objectiveMods[obj.id] ?? 1) * rng.float(0.85, 1.2), 0.6, 1.6) * 100) / 100;
    notes.push(`${obj.name} value adjusted (x${objectiveMods[obj.id]}).`);
  }
  if (kind === 'medium' && t.tactical && !t.characters.length) {
    notes.push('Weapon prices and damage falloff adjusted.');
  }
  // Major: gameplay changes (cumulative rules).
  const rules: PatchRules = JSON.parse(JSON.stringify(prev.rules ?? {}));
  const changes: string[] = [];
  if (kind === 'major') {
    const used = new Set<string>();
    const n = rng.int(2, 3);
    for (let i = 0; i < n * 2 && changes.length < n; i++) {
      const c = t.moba ? mobaChange(t, prev, rules, rng, used) : t.tactical ? shooterChange(t, prev, rules, rng, used) : null;
      if (c) changes.push(c);
    }
    notes.unshift(...changes.map((c) => `Gameplay: ${c}`));
  }
  let era = prev.era;
  if (kind === 'major' && rng.chance(0.12)) {
    const nMajor = world.titles[titleId].patches.filter((p) => p.kind === 'major' || (p.kind === undefined && p.major)).length;
    era = `Era ${Math.floor(nMajor / 3) + 2}`;
    notes.push(`A sweeping update begins a new rule era (${era}).`);
  }
  const patch: Patch = {
    id: `${titleId}-${version}-${world.day}`,
    version,
    day: world.day,
    season,
    major: kind !== 'minor',
    kind,
    charMeta,
    styles,
    mapPool,
    objectiveMods,
    notes,
    era,
    ...(Object.keys(rules).length ? { rules } : {}),
  };
  world.titles[titleId].patches.push(patch);
  // Only recent patches keep per-character meta values (real rosters have 100–170 characters);
  // older patches keep their version, notes, styles, map pool and rules.
  const ps = world.titles[titleId].patches;
  for (let i = 0; i < ps.length - 6; i++) if (Object.keys(ps[i].charMeta).length) ps[i].charMeta = {};
  if (world.titles[titleId].patches.length > 60) {
    const keep = world.titles[titleId].patches;
    world.titles[titleId].patches = keep.slice(keep.length - 60);
  }
  for (const team of Object.values(world.teams)) {
    if (team.titleId !== titleId || !team.active) continue;
    team.metaAdapt = clamp(team.metaAdapt - K.adapt, 5, 100);
  }
  const head = kind === 'major' ? 'major update' : kind === 'medium' ? 'meta update' : 'balance patch';
  addNews(world, {
    kind: 'patch',
    title: `${t.short} patch ${version} (${head})${changes.length ? `: ${changes[0].replace(/\.$/, '')}` : favoured && kind === 'medium' ? `: ${favoured} meta` : ''}`,
    body: notes.slice(0, kind === 'minor' ? 3 : 8).join(' '),
    titleId,
    refs: [],
    importance: kind === 'major' ? 4 : kind === 'medium' ? 3 : 1,
  });
  return patch;
}

/**
 * Daily patch cadence for one title: medium patches 10 days before each international and the
 * World Championship, a major patch right after the World Championship, minor patches at random
 * during the splits (≈ every 2–3 weeks, never while an international is running).
 */
export function patchCheck(world: World, titleId: string, day: number): void {
  const ps = world.titles[titleId].patches;
  const last = ps[ps.length - 1];
  const events = Object.values(world.comps).filter((c) => c.season === world.season && c.titleId === titleId && (c.tier === 'international' || c.tier === 'worlds'));
  if (events.some((c) => c.startDay - 10 === day)) {
    releasePatch(world, titleId, 'medium');
    return;
  }
  const worlds = events.find((c) => c.tier === 'worlds');
  if (worlds?.status === 'done') {
    // The day after the final.
    const lastStage = worlds.stages[worlds.stages.length - 1];
    const finalDay = Math.max(0, ...lastStage.fixtureIds.map((id) => world.fixtures[id]?.day ?? 0));
    if (day > finalDay && !ps.some((p) => p.kind === 'major' && p.season === world.season + 1)) releasePatch(world, titleId, 'major', { nextSeason: true });
    return;
  }
  if (events.some((c) => c.status === 'active') || day - last.day < 7 || events.some((c) => c.startDay - 10 > day && c.startDay - 10 - day < 5)) return;
  const rng = new RNG(`${world.meta.seed}|patchday|${titleId}|${day}`);
  if (rng.chance(1 / 18)) releasePatch(world, titleId, 'minor');
}

/** Daily meta adaptation recovery for teams (called during day processing). */
export function adaptToMeta(world: World, teamId: string): void {
  const team = world.teams[teamId];
  if (!team || team.metaAdapt >= 100) return;
  let know = 0;
  let n = 0;
  for (const pid of team.roster) {
    const p = world.players[pid];
    if (!p) continue;
    know += (p.attrs[52] + p.attrs[51]) / 2; // patch adaptation + meta understanding
    n++;
  }
  const coach = team.staff.map((id) => world.staff[id]).find((s) => s?.role === 'head-coach');
  const analyst = team.staff.map((id) => world.staff[id]).find((s) => s?.role === 'analyst');
  const k = (n ? know / n : 50) / 100;
  const staffBoost = ((coach?.attrs.metaReading ?? 40) + (analyst?.attrs.metaReading ?? 30)) / 200;
  const facility = (world.orgs[team.orgId]?.facilities.analytics ?? 3) / 10;
  team.metaAdapt = clamp(team.metaAdapt + 0.6 + 1.6 * k + 1.0 * staffBoost + 0.6 * facility, 0, 100);
}

/**
 * Patch & metagame evolution. Each title has its own version history. Patches shift character
 * strength, favoured strategic styles, the competitive map pool and objective values. Teams'
 * `metaAdapt` drops after a patch and recovers at a rate driven by players' Patch Adaptation /
 * Meta Understanding and the coaching staff's Meta Reading.
 */
import { clamp } from '../core/math';
import { RNG } from '../core/rng';
import { getTitle } from '../data/titles/catalogue';
import type { Patch, World } from '../model/types';
import { addNews } from './news';

export function initialPatch(world: World, titleId: string): Patch {
  const t = getTitle(titleId);
  const rng = new RNG(`${world.meta.seed}|patch0|${titleId}`);
  const charMeta: Record<string, number> = {};
  for (const c of t.characters) charMeta[c.id] = Math.round(clamp(rng.normal(0, 2.5), -10, 10) * 10) / 10;
  const styles: Record<string, number> = {};
  for (const s of t.styles) styles[s] = Math.round(clamp(1 + rng.normal(0, 0.15), 0.6, 1.5) * 100) / 100;
  const pool = t.maps.length > t.mapPool ? rng.sample(t.maps, t.mapPool).map((m) => m.id) : t.maps.map((m) => m.id);
  const yy = world.season % 100;
  return { id: `${titleId}-${yy}.01`, version: `${yy}.01`, day: world.day, season: world.season, major: true, charMeta, styles, mapPool: pool, objectiveMods: {}, notes: ['Season opening patch.'], era: 'Modern era' };
}

export function currentPatch(world: World, titleId: string): Patch {
  const ps = world.titles[titleId].patches;
  return ps[ps.length - 1];
}

export function releasePatch(world: World, titleId: string, major: boolean): Patch {
  const t = getTitle(titleId);
  const prev = currentPatch(world, titleId);
  const rng = new RNG(`${world.meta.seed}|patch|${titleId}|${world.day}`);
  const yy = world.season % 100;
  const samSeason = world.titles[titleId].patches.filter((p) => p.season === world.season).length;
  const version = `${yy}.${String(samSeason + 1).padStart(2, '0')}`;
  const charMeta: Record<string, number> = {};
  const notes: string[] = [];
  const sd = major ? 3 : 1.2;
  for (const c of t.characters) {
    const old = prev.charMeta[c.id] ?? 0;
    charMeta[c.id] = Math.round(clamp(old * 0.8 + rng.normal(0, sd), -10, 10) * 10) / 10;
  }
  if (t.characters.length) {
    const sorted = [...t.characters].sort((a, b) => (prev.charMeta[b.id] ?? 0) - (prev.charMeta[a.id] ?? 0));
    for (const c of sorted.slice(0, major ? 4 : 2)) {
      charMeta[c.id] = Math.round((charMeta[c.id] - rng.float(2, 5)) * 10) / 10;
      notes.push(`${c.name} nerfed`);
    }
    for (const c of sorted.slice(-(major ? 4 : 2))) {
      charMeta[c.id] = Math.round((charMeta[c.id] + rng.float(2, 5)) * 10) / 10;
      notes.push(`${c.name} buffed`);
    }
  }
  const styles: Record<string, number> = {};
  for (const s of t.styles) styles[s] = Math.round(clamp((prev.styles[s] ?? 1) * 0.9 + 0.1 + rng.normal(0, major ? 0.2 : 0.06), 0.55, 1.6) * 100) / 100;
  const favoured = Object.entries(styles).sort((a, b) => b[1] - a[1])[0]?.[0];
  if (favoured && major) notes.push(`Meta shifts toward ${favoured} play.`);
  let mapPool = [...prev.mapPool];
  if (major && t.maps.length > t.mapPool && rng.chance(0.6)) {
    const out = rng.pick(mapPool);
    const candidates = t.maps.filter((m) => !mapPool.includes(m.id));
    if (candidates.length) {
      const inn = rng.pick(candidates);
      mapPool = mapPool.map((m) => (m === out ? inn.id : m));
      notes.push(`Map pool: ${t.maps.find((m) => m.id === out)?.name} out, ${inn.name} in.`);
    }
  }
  const objectiveMods = { ...prev.objectiveMods };
  if (major && t.moba) {
    const obj = rng.pick(t.moba.objectives);
    objectiveMods[obj.id] = Math.round(clamp((objectiveMods[obj.id] ?? 1) * rng.float(0.85, 1.2), 0.6, 1.6) * 100) / 100;
    notes.push(`${obj.name} value adjusted (x${objectiveMods[obj.id]}).`);
  }
  if (major && t.tactical && rng.chance(0.3)) notes.push('Economy and utility costs rebalanced.');
  let era = prev.era;
  if (major && rng.chance(0.08)) {
    const n = world.titles[titleId].patches.filter((p) => p.major).length;
    era = `Era ${Math.floor(n / 3) + 2}`;
    notes.push(`A sweeping update begins a new rule era (${era}).`);
  }
  const patch: Patch = { id: `${titleId}-${version}-${world.day}`, version, day: world.day, season: world.season, major, charMeta, styles, mapPool, objectiveMods, notes, era };
  world.titles[titleId].patches.push(patch);
  // Keep patch history bounded in very long saves (older patches summarised in history).
  if (world.titles[titleId].patches.length > 60) {
    const keep = world.titles[titleId].patches;
    world.titles[titleId].patches = keep.slice(keep.length - 60);
  }
  // Teams lose part of their meta alignment depending on adaptability.
  for (const team of Object.values(world.teams)) {
    if (team.titleId !== titleId || !team.active) continue;
    team.metaAdapt = clamp(team.metaAdapt - (major ? 30 : 8), 5, 100);
  }
  if (major) {
    addNews(world, {
      kind: 'patch',
      title: `${t.short} patch ${version}: ${favoured ? `${favoured} meta` : 'major update'}`,
      body: notes.slice(0, 6).join(' '),
      titleId,
      refs: [],
      importance: 2,
    });
  }
  return patch;
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

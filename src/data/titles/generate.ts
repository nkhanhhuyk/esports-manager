import { RNG } from '../../core/rng';
import type { CharDef, MapDef } from './types';

/**
 * Deterministic generators for fictional, editable character and map metadata.
 * No real character names, artwork or map names are used.
 */

const FLAVOUR: Record<string, { a: string[]; b: string[] }> = {
  fantasy: {
    a: ['Ka', 'Lor', 'Vex', 'Tha', 'Mor', 'Syl', 'Ar', 'Bel', 'Cor', 'Dra', 'El', 'Fen', 'Gal', 'Hel', 'Ish', 'Jor', 'Kel', 'Lun', 'Myr', 'Nar', 'Or', 'Pyr', 'Qua', 'Ryn', 'Sar', 'Tor', 'Ul', 'Val', 'Wyn', 'Xan', 'Yr', 'Zar', 'Aza', 'Bra', 'Cel', 'Dae', 'Esh', 'Fau', 'Gor', 'Hal'],
    b: ['a', 'is', 'on', 'ar', 'eth', 'ia', 'ix', 'or', 'us', 'yn', 'ra', 'ael', 'ion', 'ash', 'oth', 'ira', 'ane', 'ek', 'il', 'orn', 'uun', 'essa', 'ari', 'ok'],
  },
  mythic: {
    a: ['Ama', 'Bal', 'Cer', 'Dur', 'Eos', 'Fyr', 'Gai', 'Hor', 'Ilu', 'Jan', 'Kal', 'Lyc', 'Mne', 'Nyx', 'Ond', 'Pha', 'Rhe', 'Sel', 'Tyr', 'Ura', 'Vay', 'Xol', 'Yam', 'Zep'],
    b: ['ros', 'atl', 'ion', 'ara', 'ex', 'eus', 'ira', 'otl', 'una', 'aki', 'aru', 'ana', 'oth', 'ius', 'emi'],
  },
  scifi: {
    a: ['Ax', 'Bry', 'Cy', 'Dex', 'Ech', 'Fax', 'Gen', 'Hex', 'Io', 'Jax', 'Kor', 'Lyn', 'Max', 'Nyx', 'Ox', 'Pax', 'Rho', 'Syn', 'Tex', 'Uma', 'Vox', 'Wren', 'Xen', 'Yor', 'Zed', 'Kai', 'Rey', 'Sol', 'Tal', 'Vey'],
    b: ['', 'a', 'er', 'on', 'is', 'ix', 'o', 'ra', 'yn', 'el', 'en', 'us', 'ia', 'or', 'ek'],
  },
  creature: {
    a: ['Flor', 'Pyr', 'Aqu', 'Volt', 'Terr', 'Glac', 'Lum', 'Shad', 'Gal', 'Thorn', 'Bram', 'Cind', 'Drizz', 'Frost', 'Moss', 'Pebbl', 'Quill', 'Sparr', 'Tund', 'Zipp'],
    b: ['ling', 'fang', 'paw', 'wing', 'horn', 'tail', 'claw', 'mite', 'bloom', 'spark', 'puff', 'shell', 'whisk', 'burr'],
  },
};

const CALLSIGNS = [
  'Bastion', 'Cinder', 'Drift', 'Ember', 'Flint', 'Granite', 'Harbor', 'Iris', 'Jolt', 'Kestrel', 'Lattice', 'Mantle', 'Nettle', 'Onyx', 'Prism', 'Quarry', 'Rook', 'Sable',
  'Thistle', 'Umbra', 'Vesper', 'Warden', 'Yarrow', 'Zinc', 'Anvil', 'Bramble', 'Cobalt', 'Dusk', 'Eclipse', 'Fable', 'Gust', 'Halcyon', 'Ironwood', 'Jasper', 'Kiln', 'Lumen',
  'Marrow', 'Nimbus', 'Osprey', 'Pyre', 'Quill', 'Rampart', 'Solace', 'Tether', 'Vigil', 'Wick', 'Static', 'Glint', 'Hush', 'Breaker', 'Cipher', 'Dynamo', 'Ferro', 'Grit',
];

const MAP_A = ['Ash', 'Iron', 'Salt', 'Frost', 'Copper', 'Ember', 'Glass', 'Stone', 'Cedar', 'Amber', 'Raven', 'Silver', 'Crimson', 'Hollow', 'Storm', 'Sun', 'Moon', 'Thorn', 'Mist', 'Coral', 'Dune', 'Echo', 'Granite', 'Jade', 'Lantern', 'Marble', 'North', 'Oak', 'Pine', 'Quartz', 'Rust', 'Sable', 'Tide', 'Umber', 'Velvet', 'Willow', 'Zephyr', 'Cobalt', 'Harbor', 'Kite'];
const MAP_B = ['vale', 'haven', 'port', 'ridge', 'yard', 'reach', 'spire', 'hollow', 'crest', 'fall', 'gate', 'works', 'field', 'mere', 'wick', 'stead', 'moor', 'cliff', 'burg', 'mark'];
const MAP_C = ['Foundry', 'Market', 'Terminal', 'Gardens', 'Temple', 'Observatory', 'Refinery', 'Outpost', 'Citadel', 'District', 'Station', 'Quarter', 'Summit', 'Basin', 'Canyon', 'Bastion', 'Docks', 'Plaza', 'Archives', 'Labs', 'Depot', 'Sanctum', 'Bazaar', 'Arena', 'Reservoir'];

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

/** Class -> style affinities (keyword matched, lowercase). */
export const CLASS_STYLE: Array<[string, Record<string, number>]> = [
  ['assassin', { pick: 8, early: 6, dive: 8, aggro: 7, gank: 7, aggressive: 7, fight: 7 }],
  ['tank', { teamfight: 8, early: 4, brawl: 8, control: 6, fight: 6 }],
  ['guardian', { teamfight: 8, brawl: 7, control: 6 }],
  ['vanguard', { brawl: 8, dive: 6, teamfight: 7 }],
  ['front', { brawl: 8, teamfight: 7 }],
  ['marksman', { scaling: 8, teamfight: 6, poke: 6 }],
  ['hunter', { scaling: 8, poke: 6, teamfight: 5 }],
  ['carry', { scaling: 8, farm: 8, teamfight: 6 }],
  ['sharpshooter', { poke: 8, control: 5 }],
  ['sniper', { poke: 8, scaling: 6 }],
  ['mage', { poke: 7, teamfight: 7, early: 5 }],
  ['caster', { poke: 7, teamfight: 6 }],
  ['nuker', { poke: 7, pick: 6, early: 6 }],
  ['fighter', { split: 7, early: 6, skirmish: 7, fight: 6 }],
  ['warrior', { split: 7, early: 6 }],
  ['bruiser', { split: 7, brawl: 6, fight: 7 }],
  ['all-rounder', { split: 6, early: 6 }],
  ['speedster', { pick: 7, early: 7 }],
  ['attacker', { scaling: 7, poke: 6 }],
  ['defender', { teamfight: 7, control: 6 }],
  ['support', { teamfight: 6, scaling: 5, sustain: 7, retake: 6 }],
  ['healer', { sustain: 8, teamfight: 6 }],
  ['enchanter', { sustain: 7, scaling: 6 }],
  ['strategist', { sustain: 8, brawl: 5 }],
  ['damage', { poke: 6, dive: 5, aggro: 5 }],
  ['duelist', { dive: 6, poke: 6, aggressive: 7 }],
  ['flank', { dive: 8, pick: 7 }],
  ['controller', { control: 8, methodical: 7, utility: 6 }],
  ['initiator', { utility: 7, aggressive: 6, dive: 5 }],
  ['sentinel', { methodical: 7, retake: 7 }],
  ['entry', { aggressive: 8 }],
  ['breach', { methodical: 6, aggressive: 5, utility: 6 }],
  ['anchor', { methodical: 8, retake: 6 }],
  ['roamer', { aggressive: 6, pick: 7 }],
  ['trapper', { methodical: 7, utility: 7 }],
  ['intel', { utility: 8, methodical: 5 }],
  ['recon', { edge: 6, center: 5, passive: 5 }],
  ['skirmisher', { aggressive: 8, center: 5 }],
  ['assault', { aggressive: 8 }],
  ['artillery', { control: 8, poke: 6 }],
  ['jungler', { gank: 7, early: 6 }],
  ['captain', { teamfight: 7, sustain: 6 }],
  ['brawler', { fight: 8, brawl: 7 }],
  ['pusher', { split: 8, early: 6 }],
  ['initiat', { teamfight: 7, pick: 6 }],
];

export function generateCharacters(
  titleId: string,
  count: number,
  classes: string[],
  styles: string[],
  flavour: 'fantasy' | 'mythic' | 'scifi' | 'creature' | 'callsign',
  sides?: { atk: string[]; def: string[] },
): CharDef[] {
  if (count <= 0) return [];
  const rng = new RNG(`chars|${titleId}`);
  const used = new Set<string>();
  const out: CharDef[] = [];
  const nameFor = (): string => {
    for (let tries = 0; tries < 200; tries++) {
      let n: string;
      if (flavour === 'callsign') {
        n = rng.pick(CALLSIGNS);
        if (used.has(n)) n = n + ' ' + rng.pick(['II', 'Mk', 'Prime', 'Zero', 'Nova']);
      } else {
        const f = FLAVOUR[flavour];
        n = cap(rng.pick(f.a).toLowerCase() + rng.pick(f.b));
        if (rng.chance(0.25)) n = cap(rng.pick(f.a).toLowerCase() + rng.pick(f.b).toLowerCase() + rng.pick(f.b));
      }
      if (!used.has(n) && n.length >= 3) {
        used.add(n);
        return n;
      }
    }
    return `Unit-${out.length + 1}`;
  };
  for (let i = 0; i < count; i++) {
    let cls = classes[i % classes.length];
    let side: 'atk' | 'def' | undefined;
    if (sides) {
      side = i % 2 === 0 ? 'atk' : 'def';
      const list = side === 'atk' ? sides.atk : sides.def;
      cls = list[Math.floor(i / 2) % list.length];
    }
    const name = nameFor();
    const st: Record<string, number> = {};
    const aff = CLASS_STYLE.filter(([k]) => cls.toLowerCase().includes(k)).map(([, v]) => v);
    for (const s of styles) {
      let base = 3 + rng.float(0, 3);
      for (const a of aff) if (a[s] !== undefined) base = Math.max(base, a[s] + rng.float(-1.5, 1.5));
      st[s] = Math.round(Math.max(0, Math.min(10, base)) * 10) / 10;
    }
    out.push({
      id: `${titleId}:${name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`,
      name,
      cls,
      styles: st,
      difficulty: rng.int(1, 10),
      side,
    });
  }
  return out;
}

export function generateMaps(
  titleId: string,
  count: number,
  opts: { modes?: string[][]; sideBiasSpread?: number; pois?: number; style?: 'compound' | 'landmark'; objectives?: string[] } = {},
): MapDef[] {
  const rng = new RNG(`maps|${titleId}`);
  const used = new Set<string>();
  const maps: MapDef[] = [];
  for (let i = 0; i < count; i++) {
    let name = '';
    for (let t = 0; t < 100; t++) {
      name = (opts.style ?? 'compound') === 'compound' ? rng.pick(MAP_A) + rng.pick(MAP_B) : `${rng.pick(MAP_A)} ${rng.pick(MAP_C)}`;
      if (!used.has(name)) break;
    }
    used.add(name);
    const pois: string[] = [];
    if (opts.pois) {
      const pu = new Set<string>();
      while (pois.length < opts.pois) {
        const p = rng.chance(0.5) ? `${rng.pick(MAP_A)} ${rng.pick(MAP_C)}` : cap(rng.pick(MAP_A).toLowerCase() + rng.pick(MAP_B));
        if (!pu.has(p)) {
          pu.add(p);
          pois.push(p);
        }
      }
    }
    maps.push({
      id: `${titleId}:${name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`,
      name,
      modes: opts.modes ? opts.modes[i % opts.modes.length] : undefined,
      sideBias: opts.sideBiasSpread ? Math.round(rng.float(-opts.sideBiasSpread, opts.sideBiasSpread) * 100) / 100 : 0,
      size: rng.pick(['open', 'mixed', 'closed'] as const),
      pois: pois.length ? pois : undefined,
      objective: opts.objectives ? opts.objectives[i % opts.objectives.length] : undefined,
    });
  }
  return maps;
}

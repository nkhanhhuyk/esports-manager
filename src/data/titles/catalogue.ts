/**
 * The official 30-title catalogue (13 MOBA, 17 Shooter).
 *
 * IMPORTANT: Mechanics here are explicit, *editable* simplified presets for a fictional esports
 * universe. They are not claims about official real-world rules. Character, map and point-of-
 * interest names are generated fictional placeholders. Input assignments are deliberate design
 * decisions from the specification and must not be substituted.
 */
import type { SeriesFormat } from '../../model/types';
import { generateCharacters, generateMaps } from './generate';
import { MOBA_ROLE, SHOOTER_ROLE, SPEC, role } from './phases';
import type { DraftStep, ModeDef, StatDef, TitleDef } from './types';

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

const bo = (n: number, unit = ''): SeriesFormat => ({ kind: 'bo', n, label: `Bo${n}${unit}` });
const sets = (n: number): SeriesFormat => ({ kind: 'sets', n, setWins: 2, label: `Bo${n} sets` });
const brf = (n: number, mp?: number): SeriesFormat => ({ kind: 'br', n, matchPoint: mp, label: mp ? `${n} games + match point (${mp})` : `${n} games` });

function seq(code: string): DraftStep[] {
  return code
    .trim()
    .split(/\s+/)
    .map((t) => ({ action: t[0] === 'b' ? 'ban' : 'pick', team: t[1] === 'A' ? 0 : 1 }) as DraftStep);
}

const mode = (id: string, name: string, kind: string, params: Record<string, number> = {}): ModeDef => ({ id, name, kind, params });

const S = (key: string, label: string, good = true, avg = true): StatDef => ({ key, label, good, avg });

const MOBA_STATS: StatDef[] = [S('k', 'K'), S('d', 'D', false), S('a', 'A'), S('gold', 'Gold'), S('cs', 'CS'), S('dmg', 'Dmg'), S('taken', 'Taken'), S('heal', 'Heal'), S('vision', 'Vision'), S('obj', 'Obj'), S('towers', 'Struct')];
const DOTA_STATS: StatDef[] = [S('k', 'K'), S('d', 'D', false), S('a', 'A'), S('gold', 'Net Worth'), S('cs', 'LH'), S('dn', 'DN'), S('dmg', 'Hero Dmg'), S('heal', 'Heal'), S('vision', 'Wards'), S('obj', 'Obj'), S('towers', 'Struct')];
const HOTS_STATS: StatDef[] = [S('k', 'K'), S('d', 'D', false), S('a', 'A'), S('siege', 'Siege'), S('xp', 'XP'), S('dmg', 'Hero Dmg'), S('heal', 'Heal'), S('taken', 'Soaked'), S('obj', 'Obj')];
const UNITE_STATS: StatDef[] = [S('score', 'Score'), S('k', 'KO'), S('d', 'Fainted', false), S('a', 'Assists'), S('dmg', 'Dmg'), S('heal', 'Heal'), S('obj', 'Obj')];
const SOUL_STATS: StatDef[] = [S('k', 'K'), S('d', 'D', false), S('a', 'A'), S('souls', 'Souls'), S('cs', 'LH'), S('dn', 'DN'), S('dmg', 'Dmg'), S('heal', 'Heal'), S('obj', 'Obj'), S('towers', 'Struct')];
const TAC_STATS: StatDef[] = [S('k', 'K'), S('d', 'D', false), S('a', 'A'), S('dmg', 'Dmg'), S('fk', 'FK'), S('fd', 'FD', false), S('clutch', 'Clutch'), S('plant', 'Plants'), S('defuse', 'Defuses'), S('kast', 'KAST rds'), S('hs', 'HS'), S('mk', 'Multi'), S('util', 'Util')];
const HERO_STATS: StatDef[] = [S('elim', 'Elims'), S('fb', 'Final Blows'), S('d', 'Deaths', false), S('dmg', 'Dmg'), S('heal', 'Heal'), S('mit', 'Mitigated'), S('ult', 'Ults')];
const ARENA_STATS: StatDef[] = [S('k', 'K'), S('d', 'D', false), S('dmg', 'Dmg'), S('hptime', 'Hill s'), S('caps', 'Caps'), S('plant', 'Plants'), S('defuse', 'Defuses'), S('fb', 'First Bloods')];
const BR_STATS: StatDef[] = [S('k', 'Kills'), S('knocks', 'Knocks'), S('a', 'Assists'), S('dmg', 'Dmg'), S('revives', 'Revives'), S('surv', 'Survival s')];
const BRAWL_STATS: StatDef[] = [S('k', 'K'), S('d', 'D', false), S('dmg', 'Dmg'), S('heal', 'Heal'), S('obj', 'Obj'), S('stars', 'Stars')];

const MOBA_STYLES = ['early', 'scaling', 'teamfight', 'pick', 'split', 'poke'];
const TAC_STYLES = ['aggressive', 'methodical', 'utility', 'aim', 'retake'];
const HERO_STYLES = ['dive', 'brawl', 'poke', 'sustain'];
const ARENA_STYLES = ['slaying', 'objective', 'tempo'];
const BR_STYLES = ['aggressive', 'edge', 'center', 'passive'];
const BRAWL_STYLES = ['aggro', 'control', 'poke'];
const SOUL_STYLES = ['farm', 'fight', 'split', 'gank'];

const SHOOTER_SPEC_LOW_MOBA = { ...SPEC.noMoba };
const MOBA_SPEC_NO_SHOOTER = { ...SPEC.noShooter };

const MOBA_PHASES = { laning: 0.18, jungle: 0.12, skirmish: 0.15, teamfight: 0.25, macro: 0.18, vision: 0.07, clutch: 0.05 };
const TAC_PHASES = { aim: 0.32, entry: 0.12, utility: 0.14, igl: 0.12, clutch: 0.1, vision: 0.1, economy: 0.05, survival: 0.05 };
const HERO_PHASES = { aim: 0.22, teamfight: 0.25, support: 0.14, entry: 0.1, macro: 0.1, survival: 0.08, igl: 0.06, utility: 0.05 };
const ARENA_PHASES = { aim: 0.32, movement: 0.14, objective: 0.16, teamfight: 0.14, entry: 0.08, igl: 0.08, clutch: 0.08 };
const BR_PHASES = { aim: 0.24, macro: 0.2, survival: 0.16, teamfight: 0.14, igl: 0.1, movement: 0.08, economy: 0.08 };

const DEFAULT_TILT = {};

function mobaMap(id: string, name: string) {
  return [{ id: `${id}:battlefield`, name, size: 'mixed' as const }];
}

// ---------------------------------------------------------------------------------------------
// Draft presets (simplified, editable)
// ---------------------------------------------------------------------------------------------

const DRAFT_TOURNAMENT_20 = seq('bA bB bA bB bA bB pA pB pB pA pA pB bB bA bB bA pB pA pA pB');
const DRAFT_MLBB = seq('bA bB bA bB bA bB pA pB pB pA pA pB bB bA bB bA pB pA pA pB');
const DRAFT_DOTA_CM = seq('bA bB bA bB bA bB bA bB pA pB pB pA bA bB bA bB pA pB pB pA bB bA pA pB');
const DRAFT_HOK = seq('bA bB bA bB pA pB pB pA pA pB bB bA bB bA pB pA pA pB');
const DRAFT_HOTS = seq('bA bB bA bB pA pB pB pA pA bB bA pB pB pA pA pB');
const DRAFT_SNAKE10 = seq('bA bB bA bB pA pB pB pA pA pB pB pA pA pB');
const DRAFT_BRAWL = seq('bA bB bA bB bA bB pA pB pB pA pA pB');
const DRAFT_DEADLOCK = seq('bA bB bA bB pA pB pB pA pA pB pB pA pA pB pB pA');
const DRAFT_PALADINS = seq('bA bB bA bB pA pB pB pA pA pB pB pA pA pB');

// ---------------------------------------------------------------------------------------------
// MOBA titles
// ---------------------------------------------------------------------------------------------

const lolRoles = [
  role('top', 'Top', 'TOP', MOBA_ROLE.solo, { classes: ['Fighter', 'Tank'] }),
  role('jungle', 'Jungle', 'JNG', MOBA_ROLE.jungle, { classes: ['Fighter', 'Assassin', 'Tank'] }),
  role('mid', 'Mid', 'MID', MOBA_ROLE.mid, { classes: ['Mage', 'Assassin'] }),
  role('bot', 'Bot (ADC)', 'ADC', MOBA_ROLE.carry, { classes: ['Marksman'] }),
  role('support', 'Support', 'SUP', MOBA_ROLE.support, { classes: ['Enchanter', 'Tank'] }),
];

const MOBA_COMMON = {
  family: 'MOBA' as const,
  subs: 2,
  minAge: 16,
  modes: [] as ModeDef[],
  mapPool: 1,
  mapVeto: 'none' as const,
  styles: MOBA_STYLES,
  coreTilt: DEFAULT_TILT,
  weaponClasses: [] as string[],
  phaseWeights: MOBA_PHASES,
  leagueSize: 8,
};

export const TITLES: TitleDef[] = [
  {
    ...MOBA_COMMON,
    id: 'mlbb',
    name: 'Mobile Legends: Bang Bang',
    short: 'MLBB',
    engine: 'moba',
    subgenre: 'mobile-moba',
    input: 'touch',
    releaseYear: 2016,
    teamSize: 5,
    roles: [
      role('exp', 'EXP Lane', 'EXP', MOBA_ROLE.solo, { classes: ['Fighter', 'Tank'] }),
      role('jungle', 'Jungler', 'JNG', MOBA_ROLE.jungle, { classes: ['Assassin', 'Fighter', 'Marksman'] }),
      role('mid', 'Mid Lane', 'MID', MOBA_ROLE.mid, { classes: ['Mage'] }),
      role('gold', 'Gold Lane', 'GLD', MOBA_ROLE.carry, { classes: ['Marksman'] }),
      role('roam', 'Roamer', 'ROM', MOBA_ROLE.roam, { classes: ['Tank', 'Support'] }),
    ],
    lineup: ['exp', 'jungle', 'mid', 'gold', 'roam'],
    charLabel: 'Hero',
    charClasses: ['Tank', 'Fighter', 'Assassin', 'Mage', 'Marksman', 'Support'],
    charCount: 60,
    characters: [],
    maps: mobaMap('mlbb', 'Land of Dawn Arena (fictional preset)'),
    draft: { mode: 'sequential', steps: DRAFT_MLBB, bansPerTeam: 5, uniqueAcross: true, fearless: 'none' },
    formats: { standard: bo(3), playoff: bo(5), final: bo(7) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, lastHitting: 0.5, ganking: 1, creepControl: 1.1, accuracy: 0 },
    moba: {
      economy: 'gold', tickSec: 30, laneEnd: 5, avgLength: 15, maxLength: 30, gpm: 700, comeback: 0.45, respawnGrowth: 1.2, items: true,
      lanes: [{ name: 'EXP lane', roles: ['exp'] }, { name: 'Mid lane', roles: ['mid'] }, { name: 'Gold lane', roles: ['gold'] }],
      junglers: ['jungle'],
      objectives: [
        { id: 'turtle', name: 'Turtle', spawn: 2, respawn: 2.5, until: 8, kind: 'gold', value: 160 },
        { id: 'lord', name: 'Lord', spawn: 8, respawn: 3, kind: 'push', value: 0.32, major: true },
      ],
      structures: { lanes: 3, perLane: 3, base: 0, tierNames: ['Outer Turret', 'Inner Turret', 'Base Turret'], baseName: '', coreName: 'Base Crystal', coreHits: 2 },
    },
    stats: MOBA_STATS,
    baseSalary: 140000,
    prizeScale: 1.0,
    notes: 'Fast mobile MOBA: early Turtle gold/shields, Lord pushes decide games. Objective timings are editable presets.',
  },
  {
    ...MOBA_COMMON,
    id: 'wildrift',
    name: 'League of Legends: Wild Rift',
    short: 'Wild Rift',
    engine: 'moba',
    subgenre: 'mobile-moba',
    input: 'touch',
    releaseYear: 2020,
    teamSize: 5,
    roles: [
      role('baron', 'Baron Lane', 'BAR', MOBA_ROLE.solo, { classes: ['Fighter', 'Tank'] }),
      role('jungle', 'Jungle', 'JNG', MOBA_ROLE.jungle, { classes: ['Fighter', 'Assassin'] }),
      role('mid', 'Mid', 'MID', MOBA_ROLE.mid, { classes: ['Mage', 'Assassin'] }),
      role('dragon', 'Dragon Lane', 'DRG', MOBA_ROLE.carry, { classes: ['Marksman'] }),
      role('support', 'Support', 'SUP', MOBA_ROLE.support, { classes: ['Enchanter', 'Tank'] }),
    ],
    lineup: ['baron', 'jungle', 'mid', 'dragon', 'support'],
    charLabel: 'Champion',
    charClasses: ['Fighter', 'Tank', 'Mage', 'Assassin', 'Marksman', 'Enchanter'],
    charCount: 50,
    characters: [],
    maps: mobaMap('wildrift', 'Mobile Rift (fictional preset)'),
    draft: { mode: 'sequential', steps: DRAFT_TOURNAMENT_20, bansPerTeam: 5, uniqueAcross: true, fearless: 'none' },
    formats: { standard: bo(3), playoff: bo(5), final: bo(7) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, lastHitting: 0.8 },
    moba: {
      economy: 'gold', tickSec: 30, laneEnd: 7, avgLength: 18, maxLength: 35, gpm: 600, comeback: 0.35, respawnGrowth: 1.3, items: true,
      lanes: [{ name: 'Baron lane', roles: ['baron'] }, { name: 'Mid lane', roles: ['mid'] }, { name: 'Dragon lane', roles: ['dragon', 'support'] }],
      junglers: ['jungle'],
      objectives: [
        { id: 'drake', name: 'Elemental Dragon', spawn: 4, respawn: 4, kind: 'stack', value: 0.03, maxStacks: 3, soul: 0.07 },
        { id: 'herald', name: 'Rift Herald', spawn: 8, respawn: 99, until: 13, kind: 'push', value: 0.15 },
        { id: 'baron', name: 'Baron', spawn: 13, respawn: 5, kind: 'push', value: 0.3, major: true },
        { id: 'elder', name: 'Elder Dragon', spawn: 22, respawn: 5, kind: 'execute', value: 0.22, major: true },
      ],
      structures: { lanes: 3, perLane: 3, base: 1, tierNames: ['Outer Turret', 'Inner Turret', 'Inhibitor Turret'], baseName: 'Nexus Turret', coreName: 'Nexus', coreHits: 2 },
    },
    stats: MOBA_STATS,
    baseSalary: 100000,
    prizeScale: 0.8,
    notes: 'Mobile adaptation with shorter laning and faster objective cadence than PC League.',
  },
  {
    ...MOBA_COMMON,
    id: 'hok',
    name: 'Honor of Kings',
    short: 'HoK',
    engine: 'moba',
    subgenre: 'mobile-moba',
    input: 'touch',
    releaseYear: 2015,
    teamSize: 5,
    roles: [
      role('clash', 'Clash Lane', 'CLS', MOBA_ROLE.solo, { classes: ['Warrior', 'Tank'] }),
      role('jungle', 'Jungler', 'JNG', MOBA_ROLE.jungle, { classes: ['Assassin', 'Warrior'] }),
      role('mid', 'Mid Lane', 'MID', MOBA_ROLE.mid, { classes: ['Mage'] }),
      role('farm', 'Farm Lane', 'FRM', MOBA_ROLE.carry, { classes: ['Marksman'] }),
      role('roam', 'Roaming', 'ROM', MOBA_ROLE.roam, { classes: ['Support', 'Tank'] }),
    ],
    lineup: ['clash', 'jungle', 'mid', 'farm', 'roam'],
    charLabel: 'Hero',
    charClasses: ['Tank', 'Warrior', 'Assassin', 'Mage', 'Marksman', 'Support'],
    charCount: 56,
    characters: [],
    maps: mobaMap('hok', 'Canyon of Kings (fictional preset)'),
    draft: { mode: 'sequential', steps: DRAFT_HOK, bansPerTeam: 4, uniqueAcross: true, fearless: 'team' },
    formats: { standard: bo(3), playoff: bo(5), final: bo(7) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, lastHitting: 0.5, creepControl: 1.1 },
    moba: {
      economy: 'gold', tickSec: 30, laneEnd: 5, avgLength: 17, maxLength: 32, gpm: 750, comeback: 0.4, respawnGrowth: 1.25, items: true,
      lanes: [{ name: 'Clash lane', roles: ['clash'] }, { name: 'Mid lane', roles: ['mid'] }, { name: 'Farm lane', roles: ['farm'] }],
      junglers: ['jungle'],
      objectives: [
        { id: 'tyrant', name: 'Tyrant', spawn: 2, respawn: 3, until: 10, kind: 'gold', value: 150 },
        { id: 'overlord', name: 'Overlord', spawn: 8, respawn: 3, until: 14, kind: 'push', value: 0.28, major: true },
        { id: 'shadow', name: 'Shadow Overlord', spawn: 14, respawn: 3.5, kind: 'push', value: 0.4, major: true },
      ],
      structures: { lanes: 3, perLane: 3, base: 0, tierNames: ['Outer Tower', 'Inner Tower', 'High-ground Tower'], baseName: '', coreName: 'Crystal', coreHits: 2 },
    },
    stats: MOBA_STATS,
    baseSalary: 260000,
    prizeScale: 2.0,
    notes: 'Team-scoped "global ban/pick" preset: a team cannot reuse its own heroes within a series.',
  },
  {
    ...MOBA_COMMON,
    id: 'lol',
    name: 'League of Legends',
    short: 'LoL',
    engine: 'moba',
    subgenre: 'pc-moba',
    input: 'mkb',
    releaseYear: 2009,
    teamSize: 5,
    roles: lolRoles,
    lineup: ['top', 'jungle', 'mid', 'bot', 'support'],
    charLabel: 'Champion',
    charClasses: ['Fighter', 'Tank', 'Mage', 'Assassin', 'Marksman', 'Enchanter'],
    charCount: 64,
    characters: [],
    maps: mobaMap('lol', "Rift Battlefield (fictional preset)"),
    draft: { mode: 'sequential', steps: DRAFT_TOURNAMENT_20, bansPerTeam: 5, uniqueAcross: true, fearless: 'global' },
    formats: { standard: bo(3), playoff: bo(5), final: bo(5) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER },
    moba: {
      economy: 'gold', tickSec: 60, laneEnd: 14, avgLength: 31, maxLength: 55, gpm: 400, comeback: 0.3, respawnGrowth: 1.4, items: true,
      lanes: [{ name: 'Top lane', roles: ['top'] }, { name: 'Mid lane', roles: ['mid'] }, { name: 'Bot lane', roles: ['bot', 'support'] }],
      junglers: ['jungle'],
      objectives: [
        { id: 'drake', name: 'Elemental Drake', spawn: 5, respawn: 5, kind: 'stack', value: 0.03, maxStacks: 4, soul: 0.08 },
        { id: 'grubs', name: 'Void Grubs', spawn: 6, respawn: 4, until: 14, kind: 'siege', value: 0.05 },
        { id: 'herald', name: 'Rift Herald', spawn: 14, respawn: 99, until: 20, kind: 'push', value: 0.15 },
        { id: 'baron', name: 'Baron Nashor', spawn: 20, respawn: 6, kind: 'push', value: 0.3, major: true },
        { id: 'elder', name: 'Elder Dragon', spawn: 35, respawn: 6, kind: 'execute', value: 0.25, major: true },
      ],
      structures: { lanes: 3, perLane: 4, base: 2, tierNames: ['Outer Turret', 'Inner Turret', 'Inhibitor Turret', 'Inhibitor'], baseName: 'Nexus Turret', coreName: 'Nexus', coreHits: 2 },
    },
    stats: MOBA_STATS,
    baseSalary: 320000,
    prizeScale: 2.2,
    notes: 'Stacking drake souls, Baron pushes and Elder executes. Fearless (global) draft preset enabled for series.',
  },
  {
    ...MOBA_COMMON,
    id: 'dota2',
    name: 'Dota 2',
    short: 'Dota 2',
    engine: 'moba',
    subgenre: 'pc-moba',
    input: 'mkb',
    releaseYear: 2013,
    teamSize: 5,
    roles: [
      role('pos1', 'Safe Lane (Pos 1)', 'P1', MOBA_ROLE.carry, { classes: ['Agility Carry', 'Strength Core'], emphasis: { lastHitting: 2.6, creepControl: 1.8, resourceDenial: 1.6, resourceEfficiency: 1.8 } }),
      role('pos2', 'Mid (Pos 2)', 'P2', MOBA_ROLE.mid, { classes: ['Intelligence Nuker', 'Agility Carry'], emphasis: { resourceDenial: 1.6 } }),
      role('pos3', 'Offlane (Pos 3)', 'P3', MOBA_ROLE.solo, { classes: ['Initiator', 'Strength Core'], emphasis: { initiation: 2, spaceControl: 1.6 } }),
      role('pos4', 'Soft Support (Pos 4)', 'P4', MOBA_ROLE.roam, { classes: ['Initiator', 'Support'], emphasis: { ganking: 1.8, creepControl: 1.2 } }),
      role('pos5', 'Hard Support (Pos 5)', 'P5', MOBA_ROLE.support, { classes: ['Support'], emphasis: { informationControl: 2.4, resourceDenial: 1.2 } }),
    ],
    lineup: ['pos1', 'pos2', 'pos3', 'pos4', 'pos5'],
    charLabel: 'Hero',
    charClasses: ['Agility Carry', 'Strength Core', 'Intelligence Nuker', 'Initiator', 'Support', 'Pusher'],
    charCount: 64,
    characters: [],
    maps: mobaMap('dota2', 'Ancient Battlefield (fictional preset)'),
    draft: { mode: 'sequential', steps: DRAFT_DOTA_CM, bansPerTeam: 7, uniqueAcross: true, fearless: 'none' },
    formats: { standard: bo(3), playoff: bo(3), final: bo(5) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, lastHitting: 1.2, creepControl: 1.2 },
    moba: {
      economy: 'gold', tickSec: 60, laneEnd: 12, avgLength: 38, maxLength: 70, gpm: 520, comeback: 0.55, respawnGrowth: 1.5, items: true, denies: true,
      lanes: [{ name: 'Safe lane', roles: ['pos1', 'pos5'] }, { name: 'Mid lane', roles: ['pos2'] }, { name: 'Off lane', roles: ['pos3', 'pos4'] }],
      junglers: ['pos4'],
      objectives: [
        { id: 'wisdom', name: 'Wisdom Runes', spawn: 7, respawn: 7, kind: 'xp', value: 0.03 },
        { id: 'roshan', name: 'Roshan (Aegis)', spawn: 15, respawn: 9, kind: 'aegis', value: 1, major: true },
        { id: 'tormentor', name: 'Tormentor', spawn: 20, respawn: 10, kind: 'buff', value: 0.04 },
      ],
      structures: { lanes: 3, perLane: 4, base: 2, tierNames: ['Tier 1 Tower', 'Tier 2 Tower', 'Tier 3 Tower', 'Barracks'], baseName: 'Tier 4 Tower', coreName: 'Ancient', coreHits: 3 },
    },
    stats: DOTA_STATS,
    baseSalary: 220000,
    prizeScale: 3.0,
    notes: 'Strong comeback mechanics (buybacks, high-ground defence), Aegis extra life, denies. Captains Mode preset.',
  },
  {
    ...MOBA_COMMON,
    id: 'vainglory',
    name: 'Vainglory',
    short: 'Vainglory',
    engine: 'moba',
    subgenre: 'mobile-moba',
    input: 'touch',
    releaseYear: 2014,
    teamSize: 5,
    roles: [
      role('top', 'Top', 'TOP', MOBA_ROLE.solo, { classes: ['Warrior', 'Captain'] }),
      role('jungle', 'Jungle', 'JNG', MOBA_ROLE.jungle, { classes: ['Assassin', 'Warrior'] }),
      role('mid', 'Mid', 'MID', MOBA_ROLE.mid, { classes: ['Mage'] }),
      role('carry', 'Carry', 'CRY', MOBA_ROLE.carry, { classes: ['Sniper'] }),
      role('captain', 'Captain', 'CPT', MOBA_ROLE.support, { classes: ['Captain'] }),
    ],
    lineup: ['top', 'jungle', 'mid', 'carry', 'captain'],
    charLabel: 'Hero',
    charClasses: ['Captain', 'Warrior', 'Mage', 'Sniper', 'Assassin'],
    charCount: 38,
    characters: [],
    maps: mobaMap('vainglory', 'Sanctuary Arena (fictional preset)'),
    draft: { mode: 'sequential', steps: DRAFT_SNAKE10, bansPerTeam: 2, uniqueAcross: true, fearless: 'none' },
    formats: { standard: bo(3), playoff: bo(5), final: bo(5) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, lastHitting: 0.8 },
    moba: {
      economy: 'gold', tickSec: 45, laneEnd: 8, avgLength: 23, maxLength: 40, gpm: 450, comeback: 0.4, respawnGrowth: 1.3, items: true,
      lanes: [{ name: 'Top lane', roles: ['top'] }, { name: 'Mid lane', roles: ['mid'] }, { name: 'Bot lane', roles: ['carry', 'captain'] }],
      junglers: ['jungle'],
      objectives: [
        { id: 'guardian', name: 'Sanctum Guardian', spawn: 4, respawn: 4, kind: 'buff', value: 0.04 },
        { id: 'leviathan', name: 'Leviathan', spawn: 15, respawn: 5, kind: 'push', value: 0.3, major: true },
      ],
      structures: { lanes: 3, perLane: 3, base: 0, tierNames: ['Outer Turret', 'Inner Turret', 'Crystal Sentry'], baseName: '', coreName: 'Vain Crystal', coreHits: 2 },
    },
    stats: MOBA_STATS,
    baseSalary: 35000,
    prizeScale: 0.2,
    notes: 'Legacy title kept alive in this universe. Objective names are fictional placeholders (editable).',
  },
  {
    ...MOBA_COMMON,
    id: 'aov',
    name: 'Arena of Valor',
    short: 'AoV',
    engine: 'moba',
    subgenre: 'mobile-moba',
    input: 'touch',
    releaseYear: 2016,
    teamSize: 5,
    roles: [
      role('slayer', 'Dark Slayer Lane', 'DSL', MOBA_ROLE.solo, { classes: ['Warrior', 'Tank'] }),
      role('jungle', 'Jungler', 'JNG', MOBA_ROLE.jungle, { classes: ['Assassin', 'Warrior'] }),
      role('mid', 'Mid Lane', 'MID', MOBA_ROLE.mid, { classes: ['Mage'] }),
      role('abyssal', 'Abyssal Dragon Lane', 'ADL', MOBA_ROLE.carry, { classes: ['Marksman'] }),
      role('support', 'Support', 'SUP', MOBA_ROLE.roam, { classes: ['Support', 'Tank'] }),
    ],
    lineup: ['slayer', 'jungle', 'mid', 'abyssal', 'support'],
    charLabel: 'Hero',
    charClasses: ['Tank', 'Warrior', 'Assassin', 'Mage', 'Marksman', 'Support'],
    charCount: 52,
    characters: [],
    maps: mobaMap('aov', 'Antaris Battlefield (fictional preset)'),
    draft: { mode: 'sequential', steps: DRAFT_TOURNAMENT_20, bansPerTeam: 5, uniqueAcross: true, fearless: 'none' },
    formats: { standard: bo(3), playoff: bo(5), final: bo(7) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, lastHitting: 0.5 },
    moba: {
      economy: 'gold', tickSec: 30, laneEnd: 5, avgLength: 16, maxLength: 32, gpm: 700, comeback: 0.4, respawnGrowth: 1.25, items: true,
      lanes: [{ name: 'Dark Slayer lane', roles: ['slayer'] }, { name: 'Mid lane', roles: ['mid'] }, { name: 'Abyssal Dragon lane', roles: ['abyssal'] }],
      junglers: ['jungle'],
      objectives: [
        { id: 'abyssal', name: 'Abyssal Dragon', spawn: 2, respawn: 3, kind: 'gold', value: 140 },
        { id: 'darkslayer', name: 'Dark Slayer', spawn: 8, respawn: 3.5, kind: 'push', value: 0.32, major: true },
      ],
      structures: { lanes: 3, perLane: 3, base: 0, tierNames: ['Outer Turret', 'Inner Turret', 'Base Turret'], baseName: '', coreName: 'Core', coreHits: 2 },
    },
    stats: MOBA_STATS,
    baseSalary: 90000,
    prizeScale: 0.7,
    notes: 'Mobile MOBA with lanes named after its two neutral bosses.',
  },
  {
    ...MOBA_COMMON,
    id: 'msw',
    name: 'Marvel Super War',
    short: 'MSW',
    engine: 'moba',
    subgenre: 'mobile-moba',
    input: 'touch',
    releaseYear: 2019,
    teamSize: 5,
    roles: [
      role('top', 'Top', 'TOP', MOBA_ROLE.solo, { classes: ['Fighter', 'Tank'] }),
      role('jungle', 'Jungle', 'JNG', MOBA_ROLE.jungle, { classes: ['Assassin', 'Fighter'] }),
      role('mid', 'Mid', 'MID', MOBA_ROLE.mid, { classes: ['Energy'] }),
      role('bot', 'Bot', 'BOT', MOBA_ROLE.carry, { classes: ['Marksman'] }),
      role('support', 'Support', 'SUP', MOBA_ROLE.support, { classes: ['Support', 'Tank'] }),
    ],
    lineup: ['top', 'jungle', 'mid', 'bot', 'support'],
    charLabel: 'Hero',
    charClasses: ['Tank', 'Fighter', 'Assassin', 'Energy', 'Marksman', 'Support'],
    charCount: 44,
    characters: [],
    maps: mobaMap('msw', 'Hero Battlefield (fictional preset)'),
    draft: { mode: 'sequential', steps: DRAFT_TOURNAMENT_20, bansPerTeam: 5, uniqueAcross: true, fearless: 'none' },
    formats: { standard: bo(3), playoff: bo(5), final: bo(5) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, lastHitting: 0.6 },
    moba: {
      economy: 'gold', tickSec: 30, laneEnd: 6, avgLength: 17, maxLength: 32, gpm: 650, comeback: 0.4, respawnGrowth: 1.25, items: true,
      lanes: [{ name: 'Top lane', roles: ['top'] }, { name: 'Mid lane', roles: ['mid'] }, { name: 'Bot lane', roles: ['bot', 'support'] }],
      junglers: ['jungle'],
      objectives: [
        { id: 'core', name: 'Cosmic Core', spawn: 3, respawn: 3, kind: 'stack', value: 0.03, maxStacks: 3, soul: 0.05 },
        { id: 'titan', name: 'Herald Titan', spawn: 10, respawn: 4, kind: 'push', value: 0.3, major: true },
      ],
      structures: { lanes: 3, perLane: 3, base: 0, tierNames: ['Outer Tower', 'Inner Tower', 'Base Tower'], baseName: '', coreName: 'Core', coreHits: 2 },
    },
    stats: MOBA_STATS,
    baseSalary: 40000,
    prizeScale: 0.3,
    notes: 'Objective names are fictional placeholders (editable).',
  },
  {
    ...MOBA_COMMON,
    id: 'unite',
    name: 'Pokémon Unite',
    short: 'Unite',
    engine: 'moba',
    subgenre: 'score-moba',
    input: 'controller',
    releaseYear: 2021,
    teamSize: 5,
    roles: [
      role('topcarry', 'Top Carry', 'TPC', MOBA_ROLE.carry, { classes: ['Attacker', 'All-Rounder'], emphasis: { objectiveExecution: 1.8 } }),
      role('topsup', 'Top Support', 'TPS', MOBA_ROLE.support, { classes: ['Defender', 'Supporter'] }),
      role('jungle', 'Jungle', 'JNG', MOBA_ROLE.jungle, { classes: ['Speedster', 'All-Rounder'] }),
      role('botcarry', 'Bot Carry', 'BTC', MOBA_ROLE.carry, { classes: ['Attacker'], emphasis: { objectiveExecution: 1.8 } }),
      role('botsup', 'Bot Support', 'BTS', MOBA_ROLE.support, { classes: ['Supporter', 'Defender'] }),
    ],
    lineup: ['topcarry', 'topsup', 'jungle', 'botcarry', 'botsup'],
    charLabel: 'Pokémon',
    charClasses: ['Attacker', 'All-Rounder', 'Speedster', 'Defender', 'Supporter'],
    charCount: 44,
    characters: [],
    maps: mobaMap('unite', 'Score Stadium (fictional preset)'),
    draft: { mode: 'sequential', steps: DRAFT_SNAKE10, bansPerTeam: 2, uniqueAcross: false, fearless: 'none' },
    formats: { standard: bo(3), playoff: bo(5), final: bo(5) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, lastHitting: 0.6, creepControl: 1.2, structurePressure: 0.5, splitPushing: 0.6, resetTiming: 0.7 },
    moba: {
      economy: 'score', tickSec: 30, laneEnd: 4, avgLength: 10, maxLength: 10, gpm: 0, comeback: 0.5, respawnGrowth: 1.2, items: false, scoreGoal: 400,
      lanes: [{ name: 'Top lane', roles: ['topcarry', 'topsup'] }, { name: 'Bottom lane', roles: ['botcarry', 'botsup'] }],
      junglers: ['jungle'],
      objectives: [
        { id: 'midlegend', name: 'Lane Legendary', spawn: 3.5, respawn: 99, until: 6, kind: 'score', value: 40 },
        { id: 'finallegend', name: 'Final Legendary', spawn: 8, respawn: 99, kind: 'score', value: 140, major: true },
      ],
      structures: { lanes: 2, perLane: 2, base: 1, tierNames: ['First Goal Zone', 'Second Goal Zone'], baseName: 'Base Goal', coreName: 'Final score' },
    },
    stats: UNITE_STATS,
    baseSalary: 60000,
    prizeScale: 0.5,
    notes: 'Score-based victory over a fixed 10-minute clock. Late legendary objective swings the score.',
  },
  {
    ...MOBA_COMMON,
    id: 'hots',
    name: 'Heroes of the Storm',
    short: 'HotS',
    engine: 'moba',
    subgenre: 'pc-moba',
    input: 'mkb',
    releaseYear: 2015,
    teamSize: 5,
    mapPool: 7,
    mapVeto: 'loser-pick',
    roles: [
      role('tank', 'Tank', 'TNK', MOBA_ROLE.support, { classes: ['Tank'], fclass: ['Entry/Initiator', 'Controller/Anchor'], emphasis: { initiation: 2.2, survivability: 2 } }),
      role('bruiser', 'Bruiser', 'BRU', MOBA_ROLE.solo, { classes: ['Bruiser'], emphasis: { waveManagement: 2.2 } }),
      role('healer', 'Healer', 'HLR', MOBA_ROLE.support, { classes: ['Healer'], emphasis: { peeling: 2.2, selflessness: 2.2 } }),
      role('ranged', 'Ranged Assassin', 'RDP', MOBA_ROLE.carry, { classes: ['Ranged Assassin'] }),
      role('flex', 'Flex', 'FLX', MOBA_ROLE.mid, { classes: ['Melee Assassin', 'Support', 'Ranged Assassin'], fclass: ['Flex'] }),
    ],
    lineup: ['tank', 'bruiser', 'healer', 'ranged', 'flex'],
    charLabel: 'Hero',
    charClasses: ['Tank', 'Bruiser', 'Healer', 'Ranged Assassin', 'Melee Assassin', 'Support'],
    charCount: 52,
    characters: [],
    maps: [],
    draft: { mode: 'sequential', steps: DRAFT_HOTS, bansPerTeam: 3, uniqueAcross: true, fearless: 'none' },
    formats: { standard: bo(3), playoff: bo(5), final: bo(7) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, lastHitting: 0, creepControl: 1.2, waveManagement: 1.3, junglePathing: 0.6 },
    moba: {
      economy: 'shared-xp', tickSec: 60, laneEnd: 6, avgLength: 20, maxLength: 40, gpm: 0, comeback: 0.35, respawnGrowth: 1.5, items: false, talents: [4, 7, 10, 13, 16, 20],
      lanes: [{ name: 'Top lane', roles: ['bruiser'] }, { name: 'Mid lane', roles: ['tank', 'healer', 'ranged'] }, { name: 'Bot lane', roles: ['flex'] }],
      junglers: ['flex'],
      objectives: [
        { id: 'mercs', name: 'Mercenary Camps', spawn: 2, respawn: 3, kind: 'siege', value: 0.08 },
        { id: 'mapobj', name: 'Battleground Objective', spawn: 3, respawn: 3, kind: 'siege', value: 0.25, major: true },
        { id: 'boss', name: 'Boss Camp', spawn: 10, respawn: 5, kind: 'push', value: 0.3, major: true },
      ],
      structures: { lanes: 3, perLane: 2, base: 0, tierNames: ['Fort', 'Keep'], baseName: '', coreName: 'Core', coreHits: 4 },
    },
    stats: HOTS_STATS,
    baseSalary: 50000,
    prizeScale: 0.3,
    notes: 'No items; shared team experience and talent tiers. Multiple battlegrounds with distinct objectives.',
  },
  {
    ...MOBA_COMMON,
    id: 'smite2',
    name: 'SMITE 2',
    short: 'SMITE 2',
    engine: 'moba',
    subgenre: 'action-moba',
    input: 'controller',
    releaseYear: 2024,
    teamSize: 5,
    roles: [
      role('solo', 'Solo', 'SOL', MOBA_ROLE.solo, { classes: ['Warrior', 'Guardian'] }),
      role('jungle', 'Jungle', 'JNG', MOBA_ROLE.jungle, { classes: ['Assassin'] }),
      role('mid', 'Mid', 'MID', MOBA_ROLE.mid, { classes: ['Mage'] }),
      role('carry', 'Carry', 'ADC', MOBA_ROLE.carry, { classes: ['Hunter'] }),
      role('support', 'Support', 'SUP', MOBA_ROLE.support, { classes: ['Guardian'] }),
    ],
    lineup: ['solo', 'jungle', 'mid', 'carry', 'support'],
    charLabel: 'God',
    charClasses: ['Guardian', 'Warrior', 'Assassin', 'Mage', 'Hunter'],
    charCount: 50,
    characters: [],
    maps: mobaMap('smite2', 'Conquest Battleground (fictional preset)'),
    draft: { mode: 'sequential', steps: DRAFT_TOURNAMENT_20, bansPerTeam: 5, uniqueAcross: true, fearless: 'none' },
    formats: { standard: bo(3), playoff: bo(5), final: bo(5) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, projectileLeading: 0.6, trackingAim: 0.3 },
    coreTilt: { accuracy: 1.4, targeting: 1.3 },
    moba: {
      economy: 'gold', tickSec: 60, laneEnd: 10, avgLength: 32, maxLength: 55, gpm: 450, comeback: 0.35, respawnGrowth: 1.4, items: true,
      lanes: [{ name: 'Solo lane', roles: ['solo'] }, { name: 'Mid lane', roles: ['mid'] }, { name: 'Duo lane', roles: ['carry', 'support'] }],
      junglers: ['jungle'],
      objectives: [
        { id: 'goldfury', name: 'Gold Fury', spawn: 5, respawn: 5, kind: 'gold', value: 220 },
        { id: 'firegiant', name: 'Fire Giant', spawn: 15, respawn: 5, kind: 'push', value: 0.3, major: true },
      ],
      structures: { lanes: 3, perLane: 2, base: 0, tierNames: ['Tower', 'Phoenix'], baseName: '', coreName: 'Titan', coreHits: 3 },
    },
    stats: MOBA_STATS,
    baseSalary: 60000,
    prizeScale: 0.4,
    notes: 'Third-person action MOBA: skillshot aim (Accuracy, Projectile Leading) matters more than in top-down MOBAs.',
  },
  {
    ...MOBA_COMMON,
    id: 'predecessor',
    name: 'Predecessor',
    short: 'Predecessor',
    engine: 'moba',
    subgenre: 'action-moba',
    input: 'controller',
    releaseYear: 2022,
    teamSize: 5,
    roles: [
      role('offlane', 'Offlane', 'OFF', MOBA_ROLE.solo, { classes: ['Fighter', 'Tank'] }),
      role('jungle', 'Jungle', 'JNG', MOBA_ROLE.jungle, { classes: ['Assassin', 'Fighter'] }),
      role('mid', 'Midlane', 'MID', MOBA_ROLE.mid, { classes: ['Mage'] }),
      role('carry', 'Carry', 'CRY', MOBA_ROLE.carry, { classes: ['Sharpshooter'] }),
      role('support', 'Support', 'SUP', MOBA_ROLE.support, { classes: ['Support', 'Tank'] }),
    ],
    lineup: ['offlane', 'jungle', 'mid', 'carry', 'support'],
    charLabel: 'Hero',
    charClasses: ['Fighter', 'Tank', 'Mage', 'Assassin', 'Sharpshooter', 'Support'],
    charCount: 42,
    characters: [],
    maps: mobaMap('predecessor', 'Paragon Field (fictional preset)'),
    draft: { mode: 'sequential', steps: DRAFT_TOURNAMENT_20, bansPerTeam: 5, uniqueAcross: true, fearless: 'none' },
    formats: { standard: bo(3), playoff: bo(5), final: bo(5) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, projectileLeading: 0.5, trackingAim: 0.4 },
    coreTilt: { accuracy: 1.3, targeting: 1.2 },
    moba: {
      economy: 'gold', tickSec: 60, laneEnd: 12, avgLength: 30, maxLength: 52, gpm: 450, comeback: 0.35, respawnGrowth: 1.4, items: true,
      lanes: [{ name: 'Offlane', roles: ['offlane'] }, { name: 'Midlane', roles: ['mid'] }, { name: 'Duo lane', roles: ['carry', 'support'] }],
      junglers: ['jungle'],
      objectives: [
        { id: 'fangtooth', name: 'Fangtooth', spawn: 6, respawn: 5, kind: 'stack', value: 0.03, maxStacks: 3, soul: 0.05 },
        { id: 'orbprime', name: 'Orb Prime', spawn: 20, respawn: 6, kind: 'push', value: 0.32, major: true },
      ],
      structures: { lanes: 3, perLane: 3, base: 0, tierNames: ['Outer Tower', 'Inner Tower', 'Inhibitor'], baseName: '', coreName: 'Core', coreHits: 2 },
    },
    stats: MOBA_STATS,
    baseSalary: 50000,
    prizeScale: 0.3,
    notes: 'Third-person PC/console-style MOBA run on the controller input by design.',
  },
  {
    ...MOBA_COMMON,
    id: 'brawlstars',
    name: 'Brawl Stars',
    short: 'Brawl Stars',
    engine: 'brawl',
    subgenre: 'brawl-arena',
    input: 'touch',
    releaseYear: 2018,
    teamSize: 3,
    subs: 2,
    minAge: 15,
    mapPool: 18,
    mapVeto: 'mode-rotation',
    styles: BRAWL_STYLES,
    roles: [
      role('carry', 'Damage / Lane', 'DMG', MOBA_ROLE.carry, { classes: ['Damage Dealer', 'Marksman', 'Sharpshooter'], emphasis: { projectileLeading: 2, accuracy: 1.8 } }),
      role('control', 'Control / Mid', 'CTL', MOBA_ROLE.mid, { classes: ['Controller', 'Artillery', 'Tank'], fclass: ['Controller/Anchor'], emphasis: { spaceControl: 2 } }),
      role('support', 'Support / Flex', 'SUP', MOBA_ROLE.support, { classes: ['Support', 'Assassin'], emphasis: { objectiveExecution: 1.6 } }),
    ],
    lineup: ['carry', 'control', 'support'],
    charLabel: 'Brawler',
    charClasses: ['Tank', 'Assassin', 'Sharpshooter', 'Controller', 'Support', 'Damage Dealer', 'Artillery', 'Marksman'],
    charCount: 48,
    characters: [],
    maps: [],
    modes: [
      mode('gemgrab', 'Gem Grab', 'gem', { target: 10, countdown: 15 }),
      mode('brawlball', 'Brawl Ball', 'ball', { goals: 2 }),
      mode('heist', 'Heist', 'heist', { safeHp: 100 }),
      mode('hotzone', 'Hot Zone', 'zone', { target: 100 }),
      mode('knockout', 'Knockout', 'knockout', { rounds: 2 }),
      mode('bounty', 'Bounty', 'bounty', { time: 120 }),
    ],
    draft: { mode: 'sequential', steps: DRAFT_BRAWL, bansPerTeam: 3, uniqueAcross: true, fearless: 'none' },
    formats: { standard: sets(3), playoff: sets(5), final: sets(7) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, lastHitting: 0, waveManagement: 0, laneTrading: 0.8, junglePathing: 0, ganking: 0.4, creepControl: 0, splitPushing: 0.2, cooldownTracking: 0.8, structurePressure: 0.4, resetTiming: 0.5, projectileLeading: 0.9, trackingAim: 0.3 },
    coreTilt: { accuracy: 1.4, evasion: 1.4, movement: 1.3 },
    phaseWeights: { skirmish: 0.25, teamfight: 0.2, aim: 0.15, objective: 0.15, macro: 0.1, movement: 0.1, clutch: 0.05 },
    brawl: { modes: [], setWins: 2 },
    stats: BRAWL_STATS,
    baseSalary: 70000,
    prizeScale: 0.7,
    leagueSize: 8,
    notes: 'Dedicated mode-based engine. Each set is first to two game wins on one mode/map; series are Bo3/Bo5/Bo7 sets.',
  },

  // -------------------------------------------------------------------------------------------
  // Shooter titles
  // -------------------------------------------------------------------------------------------
  {
    id: 'valorant',
    name: 'Valorant',
    short: 'Valorant',
    family: 'SHOOTER',
    engine: 'tactical',
    subgenre: 'tactical-fps',
    input: 'mkb',
    releaseYear: 2020,
    teamSize: 5,
    subs: 2,
    minAge: 16,
    roles: [
      role('duelist', 'Duelist', 'DUE', SHOOTER_ROLE.entry, { classes: ['Duelist'] }),
      role('initiator', 'Initiator', 'INI', SHOOTER_ROLE.recon, { classes: ['Initiator'] }),
      role('controller', 'Controller', 'CTL', SHOOTER_ROLE.controller, { classes: ['Controller'] }),
      role('sentinel', 'Sentinel', 'SEN', SHOOTER_ROLE.anchor, { classes: ['Sentinel'] }),
      role('flex', 'Flex', 'FLX', SHOOTER_ROLE.flex, { classes: ['Initiator', 'Duelist', 'Sentinel', 'Controller'] }),
    ],
    lineup: ['duelist', 'initiator', 'controller', 'sentinel', 'flex'],
    charLabel: 'Agent',
    charClasses: ['Duelist', 'Initiator', 'Controller', 'Sentinel'],
    charCount: 24,
    characters: [],
    maps: [],
    modes: [],
    mapPool: 7,
    draft: { mode: 'simultaneous', steps: [], bansPerTeam: 0, uniqueAcross: false, fearless: 'none' },
    mapVeto: 'tactical',
    formats: { standard: bo(3), playoff: bo(3), final: bo(5) },
    styles: TAC_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, recoilControl: 0.8, burstSprayControl: 0.8, projectileLeading: 0.2, movementShooting: 0.3, cooldownTracking: 0.4 },
    coreTilt: {},
    weaponClasses: ['Rifle', 'Sniper', 'SMG', 'Sidearm', 'Shotgun', 'Machine Gun'],
    phaseWeights: TAC_PHASES,
    tactical: {
      roundsToWin: 13, half: 12, overtime: { kind: 'win-by-two', rounds: 2 }, economy: 'credits', startMoney: 800, winReward: 3000, lossRewards: [1900, 2400, 2900], killReward: 200,
      plantReward: 300, maxMoney: 9000, fullBuy: 3900, objectiveName: 'Spike', sides: ['Attack', 'Defense'], abilities: true, utilityWeight: 0.9, defenderEdge: 0.06,
    },
    stats: TAC_STATS,
    baseSalary: 260000,
    prizeScale: 2.25,
    leagueSize: 8,
    notes: 'Agent abilities and credit economy, MR12 halves (first to 13), win-by-two overtime.',
  },
  {
    id: 'valmobile',
    name: 'Valorant Mobile',
    short: 'Val Mobile',
    family: 'SHOOTER',
    engine: 'tactical',
    subgenre: 'tactical-mobile',
    input: 'touch',
    releaseYear: 2025,
    teamSize: 5,
    subs: 2,
    minAge: 16,
    roles: [
      role('duelist', 'Duelist', 'DUE', SHOOTER_ROLE.entry, { classes: ['Duelist'] }),
      role('initiator', 'Initiator', 'INI', SHOOTER_ROLE.recon, { classes: ['Initiator'] }),
      role('controller', 'Controller', 'CTL', SHOOTER_ROLE.controller, { classes: ['Controller'] }),
      role('sentinel', 'Sentinel', 'SEN', SHOOTER_ROLE.anchor, { classes: ['Sentinel'] }),
      role('flex', 'Flex', 'FLX', SHOOTER_ROLE.flex, { classes: ['Initiator', 'Duelist', 'Sentinel'] }),
    ],
    lineup: ['duelist', 'initiator', 'controller', 'sentinel', 'flex'],
    charLabel: 'Agent',
    charClasses: ['Duelist', 'Initiator', 'Controller', 'Sentinel'],
    charCount: 20,
    characters: [],
    maps: [],
    modes: [],
    mapPool: 7,
    draft: { mode: 'simultaneous', steps: [], bansPerTeam: 0, uniqueAcross: false, fearless: 'none' },
    mapVeto: 'tactical',
    formats: { standard: bo(3), playoff: bo(3), final: bo(5) },
    styles: TAC_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, flickAim: 0.8, recoilControl: 0.6, burstSprayControl: 0.7, crosshairPlacement: 1.1, projectileLeading: 0.2, movementShooting: 0.3, cooldownTracking: 0.4 },
    coreTilt: { multitasking: 1.3 },
    weaponClasses: ['Rifle', 'Sniper', 'SMG', 'Sidearm', 'Shotgun', 'Machine Gun'],
    phaseWeights: { ...TAC_PHASES, aim: 0.28, utility: 0.16 },
    tactical: {
      roundsToWin: 13, half: 12, overtime: { kind: 'win-by-two', rounds: 2 }, economy: 'credits', startMoney: 800, winReward: 3000, lossRewards: [1900, 2400, 2900], killReward: 200,
      plantReward: 300, maxMoney: 9000, fullBuy: 3900, objectiveName: 'Spike', sides: ['Attack', 'Defense'], abilities: true, utilityWeight: 1.0, defenderEdge: 0.05,
    },
    stats: TAC_STATS,
    baseSalary: 70000,
    prizeScale: 0.8,
    leagueSize: 8,
    notes: 'Separate title from PC Valorant: touchscreen input, its own agent and map pools (fictional presets).',
  },
  {
    id: 'cs2',
    name: 'Counter-Strike 2',
    short: 'CS2',
    family: 'SHOOTER',
    engine: 'tactical',
    subgenre: 'tactical-fps',
    input: 'mkb',
    releaseYear: 2023,
    teamSize: 5,
    subs: 2,
    minAge: 16,
    roles: [
      role('entry', 'Entry Fragger', 'ENT', SHOOTER_ROLE.entry, { classes: [], weapons: ['Rifle', 'SMG'] }),
      role('awper', 'AWPer', 'AWP', SHOOTER_ROLE.sniper, { classes: [], weapons: ['Sniper', 'Pistol'] }),
      role('rifler', 'Star Rifler', 'RIF', SHOOTER_ROLE.star, { classes: [], weapons: ['Rifle'] }),
      role('support', 'Support', 'SUP', SHOOTER_ROLE.support, { classes: [], weapons: ['Rifle', 'Pistol'] }),
      role('lurker', 'Lurker', 'LRK', SHOOTER_ROLE.lurker, { classes: [], weapons: ['Rifle', 'SMG'] }),
    ],
    lineup: ['entry', 'awper', 'rifler', 'support', 'lurker'],
    charLabel: 'Loadout',
    charClasses: [],
    charCount: 0,
    characters: [],
    maps: [],
    modes: [],
    mapPool: 7,
    draft: { mode: 'none', steps: [], bansPerTeam: 0, uniqueAcross: false, fearless: 'none' },
    mapVeto: 'tactical',
    formats: { standard: bo(3), playoff: bo(3), final: bo(5) },
    styles: TAC_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, recoilControl: 1.3, burstSprayControl: 1.3, projectileLeading: 0, movementShooting: 0.2, utilityPlacement: 0.9 },
    coreTilt: {},
    weaponClasses: ['Rifle', 'Sniper', 'SMG', 'Pistol', 'Shotgun', 'Heavy'],
    phaseWeights: { ...TAC_PHASES, aim: 0.36, utility: 0.1 },
    tactical: {
      roundsToWin: 13, half: 12, overtime: { kind: 'mr', rounds: 6 }, economy: 'money', startMoney: 800, winReward: 3250, lossRewards: [1400, 1900, 2400, 2900, 3400], killReward: 300,
      plantReward: 800, maxMoney: 16000, fullBuy: 4700, objectiveName: 'Bomb', sides: ['T', 'CT'], abilities: false, utilityWeight: 0.6, defenderEdge: 0.08,
    },
    stats: TAC_STATS,
    baseSalary: 290000,
    prizeScale: 1.25,
    leagueSize: 8,
    notes: 'No agent abilities: grenades, money economy, spray control and the AWP define the game. MR12 with MR3 overtime.',
  },
  {
    id: 'rivals',
    name: 'Marvel Rivals',
    short: 'Rivals',
    family: 'SHOOTER',
    engine: 'hero',
    subgenre: 'hero-shooter',
    input: 'mkb',
    releaseYear: 2024,
    teamSize: 6,
    subs: 2,
    minAge: 16,
    roles: [
      role('vanguard', 'Main Vanguard', 'MVG', SHOOTER_ROLE.tank, { classes: ['Vanguard'] }),
      role('offvanguard', 'Off Vanguard', 'OVG', SHOOTER_ROLE.tank, { classes: ['Vanguard'], fclass: ['Controller/Anchor', 'Flex'] }),
      role('hitscan', 'Hitscan Duelist', 'HDU', SHOOTER_ROLE.hitscan, { classes: ['Duelist'] }),
      role('flexdps', 'Flex Duelist', 'FDU', SHOOTER_ROLE.projectile, { classes: ['Duelist'] }),
      role('strategist', 'Main Strategist', 'MST', SHOOTER_ROLE.mainSupport, { classes: ['Strategist'] }),
      role('flexsup', 'Flex Strategist', 'FST', SHOOTER_ROLE.flexSupport, { classes: ['Strategist'] }),
    ],
    lineup: ['vanguard', 'offvanguard', 'hitscan', 'flexdps', 'strategist', 'flexsup'],
    charLabel: 'Hero',
    charClasses: ['Vanguard', 'Duelist', 'Strategist'],
    charCount: 36,
    characters: [],
    maps: [],
    modes: [
      mode('domination', 'Domination', 'control', { roundsToWin: 2 }),
      mode('convoy', 'Convoy', 'escort', { checkpoints: 3 }),
      mode('convergence', 'Convergence', 'hybrid', { checkpoints: 3 }),
    ],
    mapPool: 9,
    draft: { mode: 'simultaneous', steps: [], bansPerTeam: 2, uniqueAcross: false, fearless: 'none' },
    mapVeto: 'loser-pick',
    formats: { standard: bo(5), playoff: bo(5), final: bo(7) },
    styles: HERO_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, recoilControl: 0.2, burstSprayControl: 0.3, projectileLeading: 1, peekMechanics: 0.4, angleClearing: 0.3, ammoDiscipline: 0.3, utilityPlacement: 0.5, cooldownTracking: 0.6 },
    coreTilt: { teamfighting: 1.3, comboExecution: 1.2 },
    weaponClasses: [],
    phaseWeights: HERO_PHASES,
    hero: { modes: [], rotation: ['domination', 'convergence', 'convoy', 'domination', 'convergence', 'convoy', 'domination'], comp: { Vanguard: 2, Duelist: 2, Strategist: 2 }, swaps: true, ultCharge: 3 },
    stats: HERO_STATS,
    baseSalary: 130000,
    prizeScale: 1.2,
    leagueSize: 8,
    notes: '6v6 hero shooter with team-up synergies (modelled as composition synergy) and hero bans.',
  },
  {
    id: 'overwatch',
    name: 'Overwatch',
    short: 'Overwatch',
    family: 'SHOOTER',
    engine: 'hero',
    subgenre: 'hero-shooter',
    input: 'mkb',
    releaseYear: 2016,
    teamSize: 5,
    subs: 3,
    minAge: 18,
    roles: [
      role('tank', 'Tank', 'TNK', SHOOTER_ROLE.tank, { classes: ['Tank'], fclass: ['Entry/Initiator', 'Shotcaller/IGL'] }),
      role('hitscan', 'Hitscan DPS', 'HDP', SHOOTER_ROLE.hitscan, { classes: ['Damage'] }),
      role('flexdps', 'Flex DPS', 'FDP', SHOOTER_ROLE.projectile, { classes: ['Damage'] }),
      role('mainsup', 'Main Support', 'MSP', SHOOTER_ROLE.mainSupport, { classes: ['Support'] }),
      role('flexsup', 'Flex Support', 'FSP', SHOOTER_ROLE.flexSupport, { classes: ['Support'] }),
    ],
    lineup: ['tank', 'hitscan', 'flexdps', 'mainsup', 'flexsup'],
    charLabel: 'Hero',
    charClasses: ['Tank', 'Damage', 'Support'],
    charCount: 38,
    characters: [],
    maps: [],
    modes: [
      mode('control', 'Control', 'control', { roundsToWin: 2 }),
      mode('hybrid', 'Hybrid', 'hybrid', { checkpoints: 3 }),
      mode('escort', 'Escort', 'escort', { checkpoints: 3 }),
      mode('push', 'Push', 'push', { distance: 100 }),
      mode('flashpoint', 'Flashpoint', 'flashpoint', { points: 3 }),
    ],
    mapPool: 13,
    draft: { mode: 'simultaneous', steps: [], bansPerTeam: 1, uniqueAcross: false, fearless: 'none' },
    mapVeto: 'loser-pick',
    formats: { standard: bo(5), playoff: bo(5), final: bo(7) },
    styles: HERO_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, recoilControl: 0.3, burstSprayControl: 0.3, projectileLeading: 1, peekMechanics: 0.4, angleClearing: 0.3, ammoDiscipline: 0.4, utilityPlacement: 0.4, cooldownTracking: 0.7 },
    coreTilt: { teamfighting: 1.3 },
    weaponClasses: [],
    phaseWeights: HERO_PHASES,
    hero: { modes: [], rotation: ['control', 'hybrid', 'flashpoint', 'push', 'escort', 'control', 'hybrid'], comp: { Tank: 1, Damage: 2, Support: 2 }, swaps: true, ultCharge: 3 },
    stats: HERO_STATS,
    baseSalary: 150000,
    prizeScale: 1.0,
    leagueSize: 8,
    notes: '5v5 role queue, mid-map hero swaps, per-map hero bans. Mode rotation per map number.',
  },
  {
    id: 'r6',
    name: 'Rainbow Six Siege',
    short: 'R6 Siege',
    family: 'SHOOTER',
    engine: 'tactical',
    subgenre: 'tactical-fps',
    input: 'controller',
    releaseYear: 2015,
    teamSize: 5,
    subs: 2,
    minAge: 16,
    roles: [
      role('entry', 'Entry', 'ENT', SHOOTER_ROLE.entry, { classes: ['Entry', 'Roamer'] }),
      role('breach', 'Hard Breacher', 'BRH', SHOOTER_ROLE.support, { classes: ['Hard Breach', 'Anchor'], emphasis: { objectiveExecution: 1.8 } }),
      role('support', 'Support', 'SUP', SHOOTER_ROLE.support, { classes: ['Support (Atk)', 'Trapper'] }),
      role('intel', 'Intel', 'INT', SHOOTER_ROLE.recon, { classes: ['Intel (Atk)', 'Intel (Def)'] }),
      role('flex', 'Flex / IGL', 'FLX', SHOOTER_ROLE.igl, { classes: ['Entry', 'Anchor', 'Roamer'] }),
    ],
    lineup: ['entry', 'breach', 'support', 'intel', 'flex'],
    charLabel: 'Operator',
    charClasses: ['Entry', 'Hard Breach', 'Support (Atk)', 'Intel (Atk)', 'Anchor', 'Roamer', 'Trapper', 'Intel (Def)'],
    charCount: 36,
    characters: [],
    maps: [],
    modes: [],
    mapPool: 7,
    draft: { mode: 'simultaneous', steps: [], bansPerTeam: 2, uniqueAcross: false, fearless: 'none', sideBans: true },
    mapVeto: 'tactical',
    formats: { standard: bo(3), playoff: bo(3), final: bo(5) },
    styles: TAC_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, flickAim: 0.8, recoilControl: 1.1, burstSprayControl: 0.8, projectileLeading: 0, movementShooting: 0.2, soundAwareness: 1.4, utilityPlacement: 1.3, angleClearing: 1.4 },
    coreTilt: { informationControl: 1.4, patience: 1.3, creativity: 1.2 },
    weaponClasses: ['Assault Rifle', 'SMG', 'Shotgun', 'DMR', 'Handgun'],
    phaseWeights: { ...TAC_PHASES, utility: 0.18, vision: 0.14, aim: 0.26, economy: 0 },
    tactical: {
      roundsToWin: 7, half: 6, overtime: { kind: 'win-by-two', rounds: 2, cap: 9 }, economy: 'none', startMoney: 0, winReward: 0, lossRewards: [0], killReward: 0,
      plantReward: 0, maxMoney: 0, fullBuy: 0, objectiveName: 'Defuser', sides: ['Attack', 'Defense'], abilities: true, utilityWeight: 1.2, prepPhase: true, defenderEdge: 0.12,
    },
    stats: TAC_STATS,
    baseSalary: 130000,
    prizeScale: 1.5,
    leagueSize: 8,
    notes: 'No economy; operator bans per side, prep phase (drones/reinforcements), destructible environments boost utility and intel.',
  },
  {
    id: 'apex',
    name: 'Apex Legends',
    short: 'Apex',
    family: 'SHOOTER',
    engine: 'br',
    subgenre: 'battle-royale',
    input: 'controller',
    releaseYear: 2019,
    teamSize: 3,
    subs: 1,
    minAge: 16,
    roles: [
      role('fragger', 'Fragger', 'FRG', SHOOTER_ROLE.star, { classes: ['Assault', 'Skirmisher'], weapons: ['AR', 'SMG'] }),
      role('support', 'Support / Anchor', 'SUP', SHOOTER_ROLE.support, { classes: ['Support', 'Controller'], weapons: ['AR', 'Shotgun'] }),
      role('igl', 'IGL / Recon', 'IGL', SHOOTER_ROLE.igl, { classes: ['Recon', 'Controller'], weapons: ['AR', 'Marksman'] }),
    ],
    lineup: ['fragger', 'support', 'igl'],
    charLabel: 'Legend',
    charClasses: ['Assault', 'Skirmisher', 'Recon', 'Support', 'Controller'],
    charCount: 26,
    characters: [],
    maps: [],
    modes: [],
    mapPool: 2,
    draft: { mode: 'simultaneous', steps: [], bansPerTeam: 0, uniqueAcross: false, fearless: 'none' },
    mapVeto: 'none',
    formats: { standard: brf(6), playoff: brf(6), final: brf(12, 50) },
    styles: BR_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, trackingAim: 1.4, movementShooting: 1.3, recoilControl: 1.0, flickAim: 0.6, peekMechanics: 0.7, longRangePrecision: 0.6, projectileLeading: 0.6 },
    coreTilt: { movement: 1.3 },
    weaponClasses: ['AR', 'SMG', 'Shotgun', 'Marksman', 'Sniper', 'LMG'],
    phaseWeights: { ...BR_PHASES, movement: 0.1 },
    br: { lobby: 20, zones: 6, placementPts: [12, 9, 7, 5, 4, 3, 3, 2, 2, 2, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0], killPts: 1, respawn: 'beacon', build: false, perspective: 'fpp', matchPointFinal: 50, legends: true, vehicles: false, avgLength: 22 },
    stats: BR_STATS,
    baseSalary: 120000,
    prizeScale: 1.5,
    leagueSize: 20,
    notes: 'Trios, legend abilities, respawn beacons. Finals use a match-point format (preset threshold 50).',
  },
  {
    id: 'warzone',
    name: 'Call of Duty: Warzone',
    short: 'Warzone',
    family: 'SHOOTER',
    engine: 'br',
    subgenre: 'battle-royale',
    input: 'controller',
    releaseYear: 2020,
    teamSize: 4,
    subs: 1,
    minAge: 16,
    roles: [
      role('fragger', 'Fragger', 'FRG', SHOOTER_ROLE.star, { classes: [], weapons: ['AR', 'SMG'] }),
      role('sniper', 'Sniper', 'SNP', SHOOTER_ROLE.sniper, { classes: [], weapons: ['Sniper', 'Marksman'] }),
      role('support', 'Support', 'SUP', SHOOTER_ROLE.support, { classes: [], weapons: ['AR', 'LMG'] }),
      role('igl', 'IGL', 'IGL', SHOOTER_ROLE.igl, { classes: [], weapons: ['AR', 'SMG'] }),
    ],
    lineup: ['fragger', 'sniper', 'support', 'igl'],
    charLabel: 'Loadout',
    charClasses: [],
    charCount: 0,
    characters: [],
    maps: [],
    modes: [],
    mapPool: 2,
    draft: { mode: 'none', steps: [], bansPerTeam: 0, uniqueAcross: false, fearless: 'none' },
    mapVeto: 'none',
    formats: { standard: brf(6), playoff: brf(6), final: brf(12) },
    styles: BR_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, recoilControl: 1.2, longRangePrecision: 1.2, movementShooting: 1.0, projectileLeading: 0.3 },
    coreTilt: {},
    weaponClasses: ['AR', 'SMG', 'Sniper', 'Marksman', 'LMG', 'Shotgun'],
    phaseWeights: BR_PHASES,
    br: { lobby: 25, zones: 7, placementPts: [15, 12, 10, 8, 7, 6, 5, 4, 3, 2, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0], killPts: 1, respawn: 'gulag', build: false, perspective: 'fpp', legends: false, vehicles: true, avgLength: 25 },
    stats: BR_STATS,
    baseSalary: 80000,
    prizeScale: 0.8,
    leagueSize: 25,
    notes: 'Quads with loadout drops and Gulag redeploys (eliminated players can duel back in).',
  },
  {
    id: 'fortnite',
    name: 'Fortnite',
    short: 'Fortnite',
    family: 'SHOOTER',
    engine: 'br',
    subgenre: 'battle-royale',
    input: 'controller',
    releaseYear: 2017,
    teamSize: 3,
    subs: 1,
    minAge: 15,
    roles: [
      role('fragger', 'Fragger', 'FRG', SHOOTER_ROLE.star, { classes: [], weapons: ['AR', 'Shotgun'], emphasis: { multitasking: 2, movement: 1.6 } }),
      role('builder', 'Builder / Support', 'BLD', SHOOTER_ROLE.support, { classes: [], weapons: ['Shotgun', 'SMG'], emphasis: { multitasking: 2.2, creativity: 1.8, microControl: 1.6 } }),
      role('igl', 'IGL', 'IGL', SHOOTER_ROLE.igl, { classes: [], weapons: ['AR', 'Sniper'] }),
    ],
    lineup: ['fragger', 'builder', 'igl'],
    charLabel: 'Loadout',
    charClasses: [],
    charCount: 0,
    characters: [],
    maps: [],
    modes: [],
    mapPool: 1,
    draft: { mode: 'none', steps: [], bansPerTeam: 0, uniqueAcross: false, fearless: 'none' },
    mapVeto: 'none',
    formats: { standard: brf(6), playoff: brf(6), final: brf(12) },
    styles: BR_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, recoilControl: 0.4, burstSprayControl: 0.6, flickAim: 1.2, trackingAim: 1.0, projectileLeading: 0.6, movementShooting: 0.8, weaponSwitching: 1.3 },
    coreTilt: { multitasking: 1.6, creativity: 1.4, microControl: 1.3 },
    weaponClasses: ['AR', 'Shotgun', 'SMG', 'Sniper', 'Explosive'],
    phaseWeights: { ...BR_PHASES, movement: 0.14, aim: 0.22 },
    br: {
      lobby: 33, zones: 9, placementPts: [25, 20, 17, 15, 13, 12, 11, 10, 9, 8, 7, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0], killPts: 2, respawn: 'reboot', build: true, perspective: 'tpp', legends: false, vehicles: false, avgLength: 20,
    },
    stats: BR_STATS,
    baseSalary: 110000,
    prizeScale: 2.0,
    leagueSize: 33,
    notes: 'Building mechanics reward Multitasking/Creativity; trios preset with reboots.',
  },
  {
    id: 'pubg',
    name: 'PUBG',
    short: 'PUBG',
    family: 'SHOOTER',
    engine: 'br',
    subgenre: 'battle-royale',
    input: 'mkb',
    releaseYear: 2017,
    teamSize: 4,
    subs: 1,
    minAge: 16,
    roles: [
      role('fragger', 'Fragger', 'FRG', SHOOTER_ROLE.star, { classes: [], weapons: ['AR', 'SMG'] }),
      role('scout', 'Scout', 'SCT', SHOOTER_ROLE.recon, { classes: [], weapons: ['AR', 'Marksman'] }),
      role('support', 'Support', 'SUP', SHOOTER_ROLE.support, { classes: [], weapons: ['AR', 'Sniper'] }),
      role('igl', 'IGL', 'IGL', SHOOTER_ROLE.igl, { classes: [], weapons: ['AR', 'Marksman'] }),
    ],
    lineup: ['fragger', 'scout', 'support', 'igl'],
    charLabel: 'Loadout',
    charClasses: [],
    charCount: 0,
    characters: [],
    maps: [],
    modes: [],
    mapPool: 3,
    draft: { mode: 'none', steps: [], bansPerTeam: 0, uniqueAcross: false, fearless: 'none' },
    mapVeto: 'none',
    formats: { standard: brf(6), playoff: brf(6), final: brf(12) },
    styles: BR_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, recoilControl: 1.4, longRangePrecision: 1.4, projectileLeading: 0.8, movementShooting: 0.2, soundAwareness: 1.2 },
    coreTilt: { patience: 1.3 },
    weaponClasses: ['AR', 'Marksman', 'Sniper', 'SMG', 'Shotgun', 'LMG'],
    phaseWeights: { ...BR_PHASES, macro: 0.24, movement: 0.04 },
    br: { lobby: 16, zones: 8, placementPts: [10, 6, 5, 4, 3, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0], killPts: 1, respawn: 'none', build: false, perspective: 'tpp', legends: false, vehicles: true, avgLength: 30 },
    stats: BR_STATS,
    baseSalary: 100000,
    prizeScale: 1.0,
    leagueSize: 16,
    notes: 'Large maps, vehicles, slow zones and bullet drop. Long-range precision and rotations dominate.',
  },
  {
    id: 'pubgm',
    name: 'PUBG Mobile',
    short: 'PUBG Mobile',
    family: 'SHOOTER',
    engine: 'br',
    subgenre: 'battle-royale',
    input: 'touch',
    releaseYear: 2018,
    teamSize: 4,
    subs: 1,
    minAge: 16,
    roles: [
      role('fragger', 'Fragger', 'FRG', SHOOTER_ROLE.star, { classes: [], weapons: ['AR', 'SMG'] }),
      role('scout', 'Scout', 'SCT', SHOOTER_ROLE.recon, { classes: [], weapons: ['AR', 'Marksman'] }),
      role('support', 'Support', 'SUP', SHOOTER_ROLE.support, { classes: [], weapons: ['AR', 'Sniper'] }),
      role('igl', 'IGL', 'IGL', SHOOTER_ROLE.igl, { classes: [], weapons: ['AR', 'Marksman'] }),
    ],
    lineup: ['fragger', 'scout', 'support', 'igl'],
    charLabel: 'Loadout',
    charClasses: [],
    charCount: 0,
    characters: [],
    maps: [],
    modes: [],
    mapPool: 2,
    draft: { mode: 'none', steps: [], bansPerTeam: 0, uniqueAcross: false, fearless: 'none' },
    mapVeto: 'none',
    formats: { standard: brf(6), playoff: brf(6), final: brf(12) },
    styles: BR_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, recoilControl: 1.1, longRangePrecision: 1.1, projectileLeading: 0.5, movementShooting: 0.5 },
    coreTilt: { multitasking: 1.3 },
    weaponClasses: ['AR', 'Marksman', 'Sniper', 'SMG', 'Shotgun', 'LMG'],
    phaseWeights: BR_PHASES,
    br: { lobby: 16, zones: 8, placementPts: [10, 6, 5, 4, 3, 2, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0], killPts: 1, respawn: 'none', build: false, perspective: 'tpp', legends: false, vehicles: true, avgLength: 26 },
    stats: BR_STATS,
    baseSalary: 90000,
    prizeScale: 3.0,
    leagueSize: 16,
    notes: 'Mobile battle royale with faster engagements than PC PUBG; huge South Asian, MENA and SEA scenes.',
  },
  {
    id: 'paladins',
    name: 'Paladins',
    short: 'Paladins',
    family: 'SHOOTER',
    engine: 'hero',
    subgenre: 'hero-shooter',
    input: 'mkb',
    releaseYear: 2018,
    teamSize: 5,
    subs: 2,
    minAge: 16,
    roles: [
      role('frontline', 'Front Line', 'FL', SHOOTER_ROLE.tank, { classes: ['Front Line'] }),
      role('damage', 'Damage', 'DMG', SHOOTER_ROLE.hitscan, { classes: ['Damage'] }),
      role('flank', 'Flank', 'FLK', SHOOTER_ROLE.projectile, { classes: ['Flank'], fclass: ['Flanker/Lurker'] }),
      role('support', 'Support', 'SUP', SHOOTER_ROLE.mainSupport, { classes: ['Support'] }),
      role('flex', 'Flex (Support/Front)', 'FLX', SHOOTER_ROLE.flexSupport, { classes: ['Support', 'Front Line'], fclass: ['Flex'] }),
    ],
    lineup: ['frontline', 'damage', 'flank', 'support', 'flex'],
    charLabel: 'Champion',
    charClasses: ['Front Line', 'Damage', 'Flank', 'Support'],
    charCount: 42,
    characters: [],
    maps: [],
    modes: [mode('siege', 'Siege', 'siege', { pointsToWin: 4 })],
    mapPool: 6,
    draft: { mode: 'sequential', steps: DRAFT_PALADINS, bansPerTeam: 2, uniqueAcross: true, fearless: 'none' },
    mapVeto: 'loser-pick',
    formats: { standard: bo(5), playoff: bo(5), final: bo(7) },
    styles: HERO_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, recoilControl: 0.3, burstSprayControl: 0.4, projectileLeading: 0.9, cooldownTracking: 0.7, buildOptimisation: 1, utilityPlacement: 0.3 },
    coreTilt: { buildOptimisation: 1.5 },
    weaponClasses: [],
    phaseWeights: HERO_PHASES,
    hero: { modes: [], rotation: ['siege'], comp: { 'Front Line': 1, Damage: 1, Flank: 1, Support: 2 }, swaps: false, ultCharge: 3 },
    stats: HERO_STATS,
    baseSalary: 30000,
    prizeScale: 0.2,
    leagueSize: 8,
    notes: 'Sequential champion draft with bans, loadout cards (Build Optimisation) and Siege mode: capture, then push.',
  },
  {
    id: 'deadlock',
    name: 'Deadlock',
    short: 'Deadlock',
    family: 'SHOOTER',
    engine: 'hybrid',
    subgenre: 'hybrid-shooter-moba',
    input: 'mkb',
    releaseYear: 2024,
    teamSize: 6,
    subs: 2,
    minAge: 16,
    roles: [
      role('solo', 'Solo Lane', 'SOL', MOBA_ROLE.solo, { classes: ['Brawler', 'Carry'], emphasis: { trackingAim: 1.6 } }),
      role('soloflex', 'Solo Lane (Flex)', 'SFX', MOBA_ROLE.mid, { classes: ['Caster', 'Assassin'], emphasis: { projectileLeading: 1.6 } }),
      role('duocarry', 'Duo Carry A', 'DCA', MOBA_ROLE.carry, { classes: ['Carry'], emphasis: { trackingAim: 2, movementShooting: 1.6 } }),
      role('duosup', 'Duo Support A', 'DSA', MOBA_ROLE.support, { classes: ['Support', 'Caster'] }),
      role('duocarry2', 'Duo Carry B', 'DCB', MOBA_ROLE.carry, { classes: ['Carry', 'Assassin'], emphasis: { trackingAim: 1.8 } }),
      role('duosup2', 'Duo Support B / Roam', 'DSB', MOBA_ROLE.roam, { classes: ['Brawler', 'Support'] }),
    ],
    lineup: ['solo', 'soloflex', 'duocarry', 'duosup', 'duocarry2', 'duosup2'],
    charLabel: 'Hero',
    charClasses: ['Carry', 'Brawler', 'Support', 'Assassin', 'Caster'],
    charCount: 32,
    characters: [],
    maps: [{ id: 'deadlock:cursed-city', name: 'Cursed City (fictional preset)', size: 'mixed' }],
    modes: [],
    mapPool: 1,
    draft: { mode: 'sequential', steps: DRAFT_DEADLOCK, bansPerTeam: 2, uniqueAcross: true, fearless: 'none' },
    mapVeto: 'none',
    formats: { standard: bo(3), playoff: bo(3), final: bo(5) },
    styles: SOUL_STYLES,
    specRelevance: { ...SPEC.fullMoba, junglePathing: 0.6, ganking: 0.8, flickAim: 0.6, trackingAim: 1.2, crosshairPlacement: 0.6, recoilControl: 0.5, burstSprayControl: 0.6, projectileLeading: 0.9, peekMechanics: 0.3, movementShooting: 1.1, angleClearing: 0.2, utilityPlacement: 0.3, soundAwareness: 0.4, ammoDiscipline: 0.6, longRangePrecision: 0.5, weaponSwitching: 0.2 },
    coreTilt: { movement: 1.3 },
    weaponClasses: [],
    phaseWeights: { laning: 0.16, jungle: 0.06, skirmish: 0.14, teamfight: 0.2, macro: 0.16, aim: 0.18, vision: 0.05, clutch: 0.05 },
    moba: {
      economy: 'souls', tickSec: 60, laneEnd: 12, avgLength: 34, maxLength: 60, gpm: 1100, comeback: 0.45, respawnGrowth: 1.4, items: true, denies: true,
      lanes: [{ name: 'Lane 1 (solo)', roles: ['solo'] }, { name: 'Lane 2 (solo)', roles: ['soloflex'] }, { name: 'Lane 3 (duo)', roles: ['duocarry', 'duosup'] }, { name: 'Lane 4 (duo)', roles: ['duocarry2', 'duosup2'] }],
      junglers: ['duosup2'],
      objectives: [
        { id: 'urn', name: 'Soul Urn', spawn: 10, respawn: 5, kind: 'gold', value: 1500 },
        { id: 'midboss', name: 'Mid Boss (Rejuvenator)', spawn: 15, respawn: 7, kind: 'push', value: 0.35, major: true },
      ],
      structures: { lanes: 4, perLane: 2, base: 2, tierNames: ['Guardian', 'Walker'], baseName: 'Base Guardian / Shrine', coreName: 'Patron', coreHits: 3 },
    },
    stats: SOUL_STATS,
    baseSalary: 85000,
    prizeScale: 0.6,
    notes: 'Hybrid engine: MOBA lanes, souls and structures, but fights resolved with shooter aim and movement. 4-lane "launch era" preset.',
    leagueSize: 8,
  },
  {
    id: 'codm',
    name: 'Call of Duty: Mobile',
    short: 'CoD Mobile',
    family: 'SHOOTER',
    engine: 'arena',
    subgenre: 'arena-fps',
    input: 'touch',
    releaseYear: 2019,
    teamSize: 5,
    subs: 2,
    minAge: 16,
    roles: [
      role('slayer', 'Slayer', 'SLY', SHOOTER_ROLE.star, { classes: [], weapons: ['SMG', 'AR'] }),
      role('objective', 'Objective', 'OBJ', SHOOTER_ROLE.entry, { classes: [], weapons: ['SMG'], emphasis: { objectiveExecution: 2 } }),
      role('anchor', 'Anchor', 'ANC', SHOOTER_ROLE.anchor, { classes: [], weapons: ['AR', 'LMG'] }),
      role('sniper', 'Sniper / Support', 'SNP', SHOOTER_ROLE.sniper, { classes: [], weapons: ['Sniper', 'AR'] }),
      role('flex', 'Flex / IGL', 'FLX', SHOOTER_ROLE.igl, { classes: [], weapons: ['AR', 'SMG'] }),
    ],
    lineup: ['slayer', 'objective', 'anchor', 'sniper', 'flex'],
    charLabel: 'Loadout',
    charClasses: [],
    charCount: 0,
    characters: [],
    maps: [],
    modes: [
      mode('hardpoint', 'Hardpoint', 'hardpoint', { target: 150, hillSec: 60 }),
      mode('snd', 'Search & Destroy', 'snd', { roundsToWin: 6 }),
      mode('domination', 'Domination', 'domination', { target: 150 }),
    ],
    mapPool: 7,
    draft: { mode: 'none', steps: [], bansPerTeam: 0, uniqueAcross: false, fearless: 'none' },
    mapVeto: 'mode-rotation',
    formats: { standard: bo(5), playoff: bo(5), final: bo(7) },
    styles: ARENA_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, recoilControl: 0.6, projectileLeading: 0, utilityPlacement: 0.5, movementShooting: 1.0 },
    coreTilt: { multitasking: 1.3 },
    weaponClasses: ['AR', 'SMG', 'Sniper', 'LMG', 'Shotgun'],
    phaseWeights: ARENA_PHASES,
    arena: { modes: [], rotation: ['hardpoint', 'snd', 'domination', 'hardpoint', 'snd', 'domination', 'snd'] },
    stats: ARENA_STATS,
    baseSalary: 60000,
    prizeScale: 0.8,
    leagueSize: 8,
    notes: '5v5 mobile arena shooter rotating Hardpoint / Search & Destroy / Domination (preset targets).',
  },
  {
    id: 'cod',
    name: 'Call of Duty',
    short: 'CoD',
    family: 'SHOOTER',
    engine: 'arena',
    subgenre: 'arena-fps',
    input: 'controller',
    releaseYear: 2003,
    teamSize: 4,
    subs: 2,
    minAge: 18,
    roles: [
      role('ar', 'Main AR', 'AR', SHOOTER_ROLE.anchor, { classes: [], weapons: ['AR'], emphasis: { trackingAim: 2 } }),
      role('flexar', 'Flex / IGL', 'FLX', SHOOTER_ROLE.igl, { classes: [], weapons: ['AR', 'SMG'] }),
      role('smg', 'Entry SMG', 'SMG', SHOOTER_ROLE.entry, { classes: [], weapons: ['SMG'] }),
      role('subsmg', 'Support SMG', 'SSG', SHOOTER_ROLE.star, { classes: [], weapons: ['SMG'], emphasis: { objectiveExecution: 1.8 } }),
    ],
    lineup: ['ar', 'flexar', 'smg', 'subsmg'],
    charLabel: 'Loadout',
    charClasses: [],
    charCount: 0,
    characters: [],
    maps: [],
    modes: [
      mode('hardpoint', 'Hardpoint', 'hardpoint', { target: 250, hillSec: 60 }),
      mode('snd', 'Search & Destroy', 'snd', { roundsToWin: 6 }),
      mode('control', 'Control', 'ctrl', { roundsToWin: 3, lives: 30 }),
    ],
    mapPool: 6,
    draft: { mode: 'none', steps: [], bansPerTeam: 0, uniqueAcross: false, fearless: 'none' },
    mapVeto: 'mode-rotation',
    formats: { standard: bo(5), playoff: bo(5), final: bo(7) },
    styles: ARENA_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, recoilControl: 0.8, projectileLeading: 0, utilityPlacement: 0.6, movementShooting: 1.2 },
    coreTilt: {},
    weaponClasses: ['AR', 'SMG', 'Sniper', 'LMG', 'Shotgun'],
    phaseWeights: ARENA_PHASES,
    arena: { modes: [], rotation: ['hardpoint', 'snd', 'control', 'hardpoint', 'snd', 'control', 'snd'] },
    stats: ARENA_STATS,
    baseSalary: 160000,
    prizeScale: 1.2,
    leagueSize: 8,
    notes: '4v4 Hardpoint / Search & Destroy / Control rotation with respawn tempo and gunfight-heavy play.',
  },
  {
    id: 'wzm',
    name: 'Call of Duty: Warzone Mobile',
    short: 'Warzone Mobile',
    family: 'SHOOTER',
    engine: 'br',
    subgenre: 'battle-royale',
    input: 'touch',
    releaseYear: 2024,
    teamSize: 4,
    subs: 1,
    minAge: 16,
    roles: [
      role('fragger', 'Fragger', 'FRG', SHOOTER_ROLE.star, { classes: [], weapons: ['AR', 'SMG'] }),
      role('sniper', 'Sniper', 'SNP', SHOOTER_ROLE.sniper, { classes: [], weapons: ['Sniper', 'Marksman'] }),
      role('support', 'Support', 'SUP', SHOOTER_ROLE.support, { classes: [], weapons: ['AR', 'LMG'] }),
      role('igl', 'IGL', 'IGL', SHOOTER_ROLE.igl, { classes: [], weapons: ['AR', 'SMG'] }),
    ],
    lineup: ['fragger', 'sniper', 'support', 'igl'],
    charLabel: 'Loadout',
    charClasses: [],
    charCount: 0,
    characters: [],
    maps: [],
    modes: [],
    mapPool: 1,
    draft: { mode: 'none', steps: [], bansPerTeam: 0, uniqueAcross: false, fearless: 'none' },
    mapVeto: 'none',
    formats: { standard: brf(6), playoff: brf(6), final: brf(12) },
    styles: BR_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, recoilControl: 0.9, longRangePrecision: 1.0, movementShooting: 0.8, projectileLeading: 0.2 },
    coreTilt: { multitasking: 1.3 },
    weaponClasses: ['AR', 'SMG', 'Sniper', 'Marksman', 'LMG', 'Shotgun'],
    phaseWeights: BR_PHASES,
    br: { lobby: 20, zones: 6, placementPts: [15, 12, 10, 8, 7, 6, 5, 4, 3, 2, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0], killPts: 1, respawn: 'gulag', build: false, perspective: 'fpp', legends: false, vehicles: true, avgLength: 22 },
    stats: BR_STATS,
    baseSalary: 45000,
    prizeScale: 0.4,
    leagueSize: 20,
    notes: 'Mobile Warzone with smaller lobbies and faster circles (preset).',
  },
  {
    id: 'owrush',
    name: 'Overwatch Rush',
    short: 'OW Rush',
    family: 'SHOOTER',
    engine: 'hero',
    subgenre: 'hero-shooter-mobile',
    input: 'touch',
    releaseYear: 2026,
    teamSize: 5,
    subs: 2,
    minAge: 16,
    roles: [
      role('tank', 'Tank', 'TNK', SHOOTER_ROLE.tank, { classes: ['Tank'] }),
      role('hitscan', 'Damage A', 'DPA', SHOOTER_ROLE.hitscan, { classes: ['Damage'] }),
      role('flexdps', 'Damage B', 'DPB', SHOOTER_ROLE.projectile, { classes: ['Damage'] }),
      role('mainsup', 'Main Support', 'MSP', SHOOTER_ROLE.mainSupport, { classes: ['Support'] }),
      role('flexsup', 'Flex Support', 'FSP', SHOOTER_ROLE.flexSupport, { classes: ['Support'] }),
    ],
    lineup: ['tank', 'hitscan', 'flexdps', 'mainsup', 'flexsup'],
    charLabel: 'Hero',
    charClasses: ['Tank', 'Damage', 'Support'],
    charCount: 26,
    characters: [],
    maps: [],
    modes: [mode('control', 'Control', 'control', { roundsToWin: 2 })],
    mapPool: 5,
    draft: { mode: 'simultaneous', steps: [], bansPerTeam: 1, uniqueAcross: false, fearless: 'none' },
    mapVeto: 'loser-pick',
    formats: { standard: bo(3, ' maps'), playoff: bo(5, ' maps'), final: bo(7, ' maps') },
    styles: HERO_STYLES,
    specRelevance: { ...SHOOTER_SPEC_LOW_MOBA, recoilControl: 0.2, burstSprayControl: 0.3, projectileLeading: 0.8, peekMechanics: 0.3, angleClearing: 0.2, cooldownTracking: 0.7 },
    coreTilt: { multitasking: 1.3, teamfighting: 1.3 },
    weaponClasses: [],
    phaseWeights: { ...HERO_PHASES, teamfight: 0.3 },
    hero: { modes: [], rotation: ['control'], comp: { Tank: 1, Damage: 2, Support: 2 }, swaps: true, ultCharge: 2 },
    stats: HERO_STATS,
    baseSalary: 40000,
    prizeScale: 0.4,
    leagueSize: 8,
    notes: 'Distinct from Overwatch: touchscreen input, Control-only maps (first to two round wins per map), shorter fights. Fictional/editable preset.',
  },
];

// ---------------------------------------------------------------------------------------------
// Popularity matrix (fictional-universe starting values, editable). Region order:
// NA LATN LATS BR EUW EUE CIS MENA AFR KR CN PCS JP VN TH ID PH MYSG SA OCE
// ---------------------------------------------------------------------------------------------

export const POP_REGION_ORDER = ['NA', 'LATN', 'LATS', 'BR', 'EUW', 'EUE', 'CIS', 'MENA', 'AFR', 'KR', 'CN', 'PCS', 'JP', 'VN', 'TH', 'ID', 'PH', 'MYSG', 'SA', 'OCE'];

const POP: Record<string, string> = {
  mlbb: '15 45 30 25 10 15 30 45 25 5 10 10 10 20 25 100 100 85 40 10',
  wildrift: '20 35 30 40 30 30 25 30 15 40 70 55 40 75 60 35 50 45 25 15',
  hok: '10 25 20 45 10 10 10 25 10 10 100 40 15 25 30 40 30 50 15 5',
  lol: '70 60 55 70 85 70 45 50 15 100 95 70 50 85 40 20 25 30 20 40',
  dota2: '40 45 60 40 70 85 100 55 25 10 85 20 10 20 35 40 70 60 35 20',
  vainglory: '20 10 10 10 25 15 10 10 5 15 20 20 25 15 20 25 25 30 10 15',
  aov: '10 20 15 20 10 10 5 15 10 15 20 90 20 90 100 40 20 50 10 5',
  msw: '10 15 10 15 10 10 5 15 5 25 40 35 30 30 35 35 30 35 15 5',
  unite: '45 35 30 40 45 30 10 20 10 30 30 35 80 30 40 50 40 40 60 25',
  hots: '35 15 15 20 40 30 25 10 5 45 40 25 10 10 10 5 5 10 5 15',
  smite2: '60 30 25 40 50 35 15 10 10 5 20 10 5 5 10 10 10 15 10 30',
  predecessor: '55 25 25 35 50 40 25 15 10 10 15 10 10 10 15 10 15 15 10 30',
  brawlstars: '45 60 55 55 60 55 50 55 30 50 30 30 70 30 30 35 30 35 40 30',
  valorant: '90 70 65 85 90 75 60 70 25 75 80 60 90 50 60 55 50 55 50 50',
  valmobile: '25 35 30 45 25 20 20 35 20 35 90 50 45 50 50 60 55 50 50 15',
  cs2: '70 45 60 95 90 100 100 65 45 15 50 20 20 25 25 30 20 30 40 55',
  rivals: '80 50 40 50 75 60 45 50 20 60 85 55 55 40 40 35 40 45 35 45',
  overwatch: '75 40 35 40 70 55 30 50 15 85 65 40 60 20 30 20 25 30 25 45',
  r6: '70 60 55 100 75 65 40 55 25 30 25 25 70 15 20 20 15 25 20 50',
  apex: '90 50 45 50 75 60 40 45 20 40 30 35 85 20 30 25 25 30 30 60',
  warzone: '90 60 50 65 75 60 35 55 30 10 10 15 20 10 15 15 15 20 30 45',
  fortnite: '100 60 55 70 90 70 40 55 25 20 15 25 45 15 25 20 20 30 25 65',
  pubg: '45 30 35 35 60 60 60 35 15 90 85 55 45 40 75 20 15 25 25 30',
  pubgm: '25 40 35 55 25 30 45 90 50 35 70 30 50 60 75 85 50 75 100 15',
  paladins: '35 30 30 50 40 40 45 15 10 5 10 10 10 10 15 10 15 15 10 20',
  deadlock: '60 35 40 40 60 65 75 35 15 20 55 25 20 15 20 15 25 25 15 30',
  codm: '55 50 40 45 35 30 20 60 50 15 60 25 55 30 35 50 55 45 80 20',
  cod: '100 45 35 40 70 40 20 45 20 5 5 5 25 5 5 5 5 10 15 40',
  wzm: '40 40 35 40 30 25 20 50 35 10 20 15 25 20 25 40 40 35 55 15',
  owrush: '30 25 20 30 25 20 15 30 15 45 60 40 50 30 35 35 35 40 35 15',
};

export const TITLE_POPULARITY: Record<string, Record<string, number>> = Object.fromEntries(
  Object.entries(POP).map(([id, s]) => {
    const vals = s.split(/\s+/).map(Number);
    return [id, Object.fromEntries(POP_REGION_ORDER.map((r, i) => [r, vals[i]]))];
  }),
);

// ---------------------------------------------------------------------------------------------
// Finalise: generate fictional characters, maps and mode lists.
// ---------------------------------------------------------------------------------------------

const FLAVOUR: Record<string, 'fantasy' | 'mythic' | 'scifi' | 'creature' | 'callsign'> = {
  mlbb: 'fantasy', wildrift: 'fantasy', hok: 'mythic', lol: 'fantasy', dota2: 'fantasy', vainglory: 'fantasy', aov: 'mythic', msw: 'scifi', unite: 'creature',
  hots: 'fantasy', smite2: 'mythic', predecessor: 'scifi', brawlstars: 'creature', valorant: 'callsign', valmobile: 'callsign', rivals: 'scifi', overwatch: 'callsign',
  r6: 'callsign', apex: 'callsign', paladins: 'fantasy', deadlock: 'scifi', owrush: 'callsign',
};

function finalise(t: TitleDef): TitleDef {
  if (t.charCount > 0) {
    t.characters = generateCharacters(
      t.id,
      t.charCount,
      t.charClasses,
      t.styles,
      FLAVOUR[t.id] ?? 'scifi',
      t.id === 'r6' ? { atk: ['Entry', 'Hard Breach', 'Support (Atk)', 'Intel (Atk)'], def: ['Anchor', 'Roamer', 'Trapper', 'Intel (Def)'] } : undefined,
    );
  }
  switch (t.id) {
    case 'valorant':
      t.maps = generateMaps(t.id, 10, { sideBiasSpread: 0.08, style: 'compound' });
      break;
    case 'valmobile':
      t.maps = generateMaps(t.id, 8, { sideBiasSpread: 0.08, style: 'compound' });
      break;
    case 'cs2':
      t.maps = generateMaps(t.id, 9, { sideBiasSpread: 0.1, style: 'compound' });
      break;
    case 'r6':
      t.maps = generateMaps(t.id, 9, { sideBiasSpread: 0.12, style: 'landmark' });
      break;
    case 'overwatch':
      t.maps = generateMaps(t.id, 13, { modes: [['control'], ['hybrid'], ['escort'], ['push'], ['flashpoint'], ['control'], ['hybrid'], ['escort'], ['control'], ['hybrid'], ['escort'], ['push'], ['flashpoint']], style: 'landmark' });
      break;
    case 'rivals':
      t.maps = generateMaps(t.id, 9, { modes: [['domination'], ['convoy'], ['convergence']], style: 'landmark' });
      break;
    case 'paladins':
      t.maps = generateMaps(t.id, 7, { modes: [['siege']], style: 'landmark' });
      break;
    case 'owrush':
      t.maps = generateMaps(t.id, 6, { modes: [['control']], style: 'landmark' });
      break;
    case 'cod':
      t.maps = generateMaps(t.id, 7, { modes: [['hardpoint', 'snd', 'control'], ['hardpoint', 'snd'], ['hardpoint', 'control'], ['snd', 'control'], ['hardpoint', 'snd', 'control'], ['hardpoint'], ['snd']], style: 'landmark' });
      break;
    case 'codm':
      t.maps = generateMaps(t.id, 8, { modes: [['hardpoint', 'snd', 'domination'], ['hardpoint', 'domination'], ['snd', 'domination'], ['hardpoint', 'snd']], style: 'landmark' });
      break;
    case 'brawlstars': {
      const modeIds = t.modes.map((m) => m.id);
      t.maps = generateMaps(t.id, 18, { modes: modeIds.map((m) => [m]), style: 'compound' });
      break;
    }
    case 'hots':
      t.maps = generateMaps(t.id, 8, { style: 'landmark', objectives: ['Tribute shrines', 'Payload carts', 'Altar captures', 'Guardian spawns', 'Coin collection', 'Siege golems', 'Temple capture', 'Core cannons'] });
      break;
    case 'apex':
      t.maps = generateMaps(t.id, 2, { pois: 16, style: 'compound' });
      break;
    case 'pubg':
      t.maps = generateMaps(t.id, 3, { pois: 14, style: 'compound' });
      break;
    case 'pubgm':
      t.maps = generateMaps(t.id, 2, { pois: 14, style: 'compound' });
      break;
    case 'fortnite':
      t.maps = generateMaps(t.id, 1, { pois: 18, style: 'compound' });
      break;
    case 'warzone':
      t.maps = generateMaps(t.id, 2, { pois: 16, style: 'compound' });
      break;
    case 'wzm':
      t.maps = generateMaps(t.id, 1, { pois: 14, style: 'compound' });
      break;
  }
  if (t.hero) t.hero.modes = t.modes;
  if (t.arena) t.arena.modes = t.modes;
  if (t.brawl) t.brawl.modes = t.modes;
  return t;
}

TITLES.forEach(finalise);

export const TITLE_BY_ID: Record<string, TitleDef> = Object.fromEntries(TITLES.map((t) => [t.id, t]));
export const TITLE_IDS = TITLES.map((t) => t.id);
export const MOBA_TITLES = TITLES.filter((t) => t.family === 'MOBA').map((t) => t.id);
export const SHOOTER_TITLES = TITLES.filter((t) => t.family === 'SHOOTER').map((t) => t.id);

export function getTitle(id: string): TitleDef {
  const t = TITLE_BY_ID[id];
  if (!t) throw new Error(`Unknown title ${id}`);
  return t;
}

export function charById(titleId: string, charId: string) {
  return TITLE_BY_ID[titleId]?.characters.find((c) => c.id === charId);
}

export function roleById(titleId: string, roleId: string) {
  return TITLE_BY_ID[titleId]?.roles.find((r) => r.id === roleId);
}

// ---------------------------------------------------------------------------------------------
// Title similarity (guidelines from the specification: 100 / 80-95 / 65-85 / 35-65 / 10-35 %)
// ---------------------------------------------------------------------------------------------

const CLOSE: Array<[string, string, number]> = [
  ['lol', 'wildrift', 0.9],
  ['valorant', 'valmobile', 0.9],
  ['pubg', 'pubgm', 0.9],
  ['warzone', 'wzm', 0.9],
  ['cod', 'codm', 0.85],
  ['overwatch', 'owrush', 0.85],
  ['hok', 'aov', 0.9],
  ['mlbb', 'hok', 0.82],
  ['mlbb', 'aov', 0.82],
  ['mlbb', 'wildrift', 0.8],
  ['hok', 'wildrift', 0.8],
  ['smite2', 'predecessor', 0.8],
  ['overwatch', 'rivals', 0.8],
  ['overwatch', 'paladins', 0.78],
  ['rivals', 'paladins', 0.75],
  ['lol', 'dota2', 0.72],
  ['cs2', 'valorant', 0.72],
  ['cs2', 'r6', 0.62],
  ['valorant', 'r6', 0.6],
  ['warzone', 'cod', 0.75],
  ['wzm', 'codm', 0.75],
  ['apex', 'warzone', 0.7],
  ['deadlock', 'dota2', 0.5],
  ['deadlock', 'overwatch', 0.45],
  ['deadlock', 'smite2', 0.5],
  ['deadlock', 'predecessor', 0.5],
];

const SUBGROUP: Record<string, string> = {
  'pc-moba': 'moba-core',
  'mobile-moba': 'moba-core',
  'action-moba': 'moba-core',
  'score-moba': 'moba-alt',
  'brawl-arena': 'moba-alt',
  'tactical-fps': 'tactical',
  'tactical-mobile': 'tactical',
  'hero-shooter': 'hero',
  'hero-shooter-mobile': 'hero',
  'arena-fps': 'arena',
  'battle-royale': 'br',
  'hybrid-shooter-moba': 'hybrid',
};

/** Similarity 0..1 used for familiarity transfer and learning difficulty. */
export function titleSimilarity(a: string, b: string): number {
  if (a === b) return 1;
  for (const [x, y, s] of CLOSE) if ((x === a && y === b) || (x === b && y === a)) return s;
  const ta = TITLE_BY_ID[a];
  const tb = TITLE_BY_ID[b];
  if (!ta || !tb) return 0.1;
  if (ta.subgenre === tb.subgenre) return 0.75;
  if (SUBGROUP[ta.subgenre] === SUBGROUP[tb.subgenre]) return 0.68;
  if (ta.family === tb.family) {
    if (ta.engine === 'hybrid' || tb.engine === 'hybrid') return 0.42;
    return 0.5;
  }
  if (ta.engine === 'hybrid' || tb.engine === 'hybrid') return 0.4;
  if (ta.engine === 'brawl' || tb.engine === 'brawl') return 0.3;
  return 0.2;
}

export function similarityLabel(s: number): string {
  if (s >= 1) return 'Same title';
  if (s >= 0.8) return 'Very closely related';
  if (s >= 0.65) return 'Same genre, similar subgenre';
  if (s >= 0.35) return 'Same genre, different subgenre';
  return 'Different genres';
}

/**
 * The seven-title catalogue: five MOBAs (S220 MLBB, S221 Wild Rift, S222 Honor of Kings,
 * S223 League of Legends, S224 Dota 2) and two tactical shooters (S232 Valorant, S234 CS2).
 * No other titles exist in this game.
 *
 * IMPORTANT: Mechanics here are explicit, *editable* simplified presets for a fictional esports
 * universe. They are not claims about official real-world rules. Character, map and point-of-
 * interest names are generated fictional placeholders. Input assignments are deliberate design
 * decisions from the specification and must not be substituted.
 */
import { realCharacters } from './realCharacters';
import type { SeriesFormat } from '../../model/types';
import { generateCharacters, generateMaps } from './generate';
import { MOBA_ROLE, SHOOTER_ROLE, SPEC, role } from './phases';
import type { CharDef, DraftStep, ModeDef, StatDef, TitleDef } from './types';

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

const bo = (n: number, unit = ''): SeriesFormat => ({ kind: 'bo', n, label: `Bo${n}${unit}` });

function seq(code: string): DraftStep[] {
  return code
    .trim()
    .split(/\s+/)
    .map((t) => ({ action: t[0] === 'b' ? 'ban' : 'pick', team: t[1] === 'A' ? 0 : 1 }) as DraftStep);
}

const mode = (id: string, name: string, kind: string, params: Record<string, number> = {}): ModeDef => ({ id, name, kind, params });

const S = (key: string, label: string, good = true, avg = true): StatDef => ({ key, label, good, avg });

const MOBA_STATS: StatDef[] = [S('k', 'K'), S('d', 'D', false), S('a', 'A'), S('gold', 'Gold'), S('cs', 'CS'), S('dmg', 'Dmg'), S('taken', 'Taken'), S('heal', 'Heal'), S('vision', 'Vision'), S('obj', 'Obj'), S('towers', 'Struct')];
const DOTA_STATS: StatDef[] = [S('k', 'K'), S('d', 'D', false), S('a', 'A'), S('gold', 'Net Worth'), S('cs', 'LH'), S('dn', 'DN'), S('dmg', 'Hero Dmg'), S('heal', 'Heal'), S('vision', 'Wards'), S('obj', 'Obj'), S('towers', 'Struct'), S('bb', 'Buybacks', false)];
const TAC_STATS: StatDef[] = [S('k', 'K'), S('d', 'D', false), S('a', 'A'), S('dmg', 'Dmg'), S('fk', 'FK'), S('fd', 'FD', false), S('clutch', 'Clutch'), S('plant', 'Plants'), S('defuse', 'Defuses'), S('kast', 'KAST rds'), S('hs', 'HS'), S('mk', 'Multi'), S('util', 'Util')];

const MOBA_STYLES = ['early', 'scaling', 'teamfight', 'pick', 'split', 'poke'];
const TAC_STYLES = ['aggressive', 'methodical', 'utility', 'aim', 'retake'];

const SHOOTER_SPEC_LOW_MOBA = { ...SPEC.noMoba };
const MOBA_SPEC_NO_SHOOTER = { ...SPEC.noShooter };

const MOBA_PHASES = { laning: 0.18, jungle: 0.12, skirmish: 0.15, teamfight: 0.25, macro: 0.18, vision: 0.07, clutch: 0.05 };
const TAC_PHASES = { aim: 0.32, entry: 0.12, utility: 0.14, igl: 0.12, clutch: 0.1, vision: 0.1, economy: 0.05, survival: 0.05 };

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
    sourceId: 'S220',
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
    maps: mobaMap('mlbb', 'Land of Dawn'),
    draft: { mode: 'sequential', steps: DRAFT_MLBB, bansPerTeam: 5, uniqueAcross: true, fearless: 'none' },
    formats: { standard: bo(3), playoff: bo(5), final: bo(7) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, lastHitting: 0.5, ganking: 1, creepControl: 1.1, accuracy: 0 },
    moba: {
      economy: 'gold', tickSec: 30, laneEnd: 5, avgLength: 15, maxLength: 30, gpm: 700, comeback: 0.45, respawnGrowth: 1.2, items: true,
      lanes: [{ name: 'EXP lane', roles: ['exp'] }, { name: 'Mid lane', roles: ['mid'] }, { name: 'Gold lane', roles: ['gold'] }],
      junglers: ['jungle'],
      objectives: [
        // mlbbhub.com map guide: Turtle 2:00 every 2:00 until the Lord appears; Lord 8:00, respawn 3:00.
        { id: 'turtle', name: 'Turtle', spawn: 2, respawn: 2, until: 7.9, kind: 'gold', value: 160 },
        { id: 'lord', name: 'Lord', spawn: 8, respawn: 3, kind: 'push', value: 0.32, major: true },
      ],
      structures: { lanes: 3, perLane: 3, base: 0, tierNames: ['Outer Turret', 'Inner Turret', 'Base Turret'], baseName: '', coreName: 'Base Crystal', coreHits: 2 },
    },
    stats: MOBA_STATS,
    baseSalary: 140000,
    prizeScale: 1.0,
    notes: 'Fast mobile MOBA: early Turtle gold/shields, Lord pushes decide games. Fresh draft every game with ordinary bans; the previous game\'s loser chooses the side for the next game. Objective timings are editable presets.',
  },
  {
    ...MOBA_COMMON,
    id: 'wildrift',
    sourceId: 'S221',
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
    maps: mobaMap('wildrift', "Summoner's Rift (Wild Rift)"),
    draft: { mode: 'sequential', steps: DRAFT_TOURNAMENT_20, bansPerTeam: 5, uniqueAcross: true, fearless: 'none' },
    formats: { standard: bo(3), playoff: bo(5), final: bo(7) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, lastHitting: 0.8 },
    moba: {
      economy: 'gold', tickSec: 30, laneEnd: 7, avgLength: 18, maxLength: 35, gpm: 600, comeback: 0.35, respawnGrowth: 1.3, items: true,
      lanes: [{ name: 'Baron lane', roles: ['baron'] }, { name: 'Mid lane', roles: ['mid'] }, { name: 'Dragon lane', roles: ['dragon', 'support'] }],
      junglers: ['jungle'],
      objectives: [
        // Wild Rift patch history (riftpatchnotes.com): Baron 11:00 since 6.2, respawn 3:30; Elder 19:00 since 6.2.
        { id: 'drake', name: 'Elemental Dragon', spawn: 4, respawn: 4, kind: 'stack', value: 0.03, maxStacks: 3, soul: 0.07 },
        { id: 'herald', name: 'Rift Herald', spawn: 6, respawn: 99, until: 10.9, kind: 'push', value: 0.15 },
        { id: 'baron', name: 'Baron Nashor', spawn: 11, respawn: 3.5, kind: 'push', value: 0.3, major: true },
        { id: 'elder', name: 'Elder Dragon', spawn: 19, respawn: 5, kind: 'execute', value: 0.22, major: true },
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
    sourceId: 'S222',
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
    maps: mobaMap('hok', "Kings' Canyon"),
    draft: { mode: 'sequential', steps: DRAFT_HOK, bansPerTeam: 4, uniqueAcross: true, fearless: 'none' },
    formats: { standard: bo(3), playoff: bo(5), final: bo(7) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, lastHitting: 0.5, creepControl: 1.1 },
    moba: {
      economy: 'gold', tickSec: 30, laneEnd: 5, avgLength: 17, maxLength: 32, gpm: 750, comeback: 0.4, respawnGrowth: 1.25, items: true,
      lanes: [{ name: 'Clash lane', roles: ['clash'] }, { name: 'Mid lane', roles: ['mid'] }, { name: 'Farm lane', roles: ['farm'] }],
      junglers: ['jungle'],
      objectives: [
        // 2025–26 guides: Tyrant & Overlord 4:00 (every 4:00); Shadow forms from 10:00 (every 3:30);
        // Tempest Dragon from 20:00 (every 3:00) in either pit.
        { id: 'tyrant', name: 'Tyrant', spawn: 4, respawn: 4, until: 9.9, kind: 'gold', value: 150 },
        { id: 'overlord', name: 'Overlord', spawn: 4, respawn: 4, until: 9.9, kind: 'push', value: 0.2 },
        { id: 'shadowtyrant', name: 'Shadow Tyrant', spawn: 10, respawn: 3.5, until: 19.9, kind: 'buff', value: 0.06 },
        { id: 'shadow', name: 'Shadow Overlord', spawn: 10, respawn: 3.5, until: 19.9, kind: 'push', value: 0.32, major: true },
        { id: 'tempest', name: 'Tempest Dragon', spawn: 20, respawn: 3, kind: 'execute', value: 0.25, major: true },
      ],
      structures: { lanes: 3, perLane: 3, base: 0, tierNames: ['Outer Tower', 'Inner Tower', 'High-ground Tower'], baseName: '', coreName: 'Crystal', coreHits: 2 },
    },
    stats: MOBA_STATS,
    baseSalary: 260000,
    prizeScale: 2.0,
    notes: 'Clash/farm/mid lanes, jungle and roam. Ordinary per-game bans with a fresh draft every game; any global-pick variant would be a separately named competition rule.',
  },
  {
    ...MOBA_COMMON,
    id: 'lol',
    sourceId: 'S223',
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
    maps: mobaMap('lol', "Summoner's Rift"),
    draft: { mode: 'sequential', steps: DRAFT_TOURNAMENT_20, bansPerTeam: 5, uniqueAcross: true, fearless: 'none', fearlessPreset: 'global' },
    formats: { standard: bo(3), playoff: bo(5), final: bo(5) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER },
    moba: {
      economy: 'gold', tickSec: 60, laneEnd: 14, avgLength: 31, maxLength: 55, gpm: 400, comeback: 0.3, respawnGrowth: 1.4, items: true,
      lanes: [{ name: 'Top lane', roles: ['top'] }, { name: 'Mid lane', roles: ['mid'] }, { name: 'Bot lane', roles: ['bot', 'support'] }],
      junglers: ['jungle'],
      objectives: [
        { id: 'drake', name: 'Elemental Drake', spawn: 5, respawn: 5, kind: 'stack', value: 0.03, maxStacks: 4, soul: 0.08 },
        // 2026 Rift: Void Grubs 8:00, Rift Herald 15:00, Baron 20:00; Atakhan removed (Riot dev blog).
        { id: 'grubs', name: 'Void Grubs', spawn: 8, respawn: 4, until: 14, kind: 'siege', value: 0.05 },
        { id: 'herald', name: 'Rift Herald', spawn: 15, respawn: 99, until: 19.9, kind: 'push', value: 0.15 },
        { id: 'baron', name: 'Baron Nashor', spawn: 20, respawn: 6, kind: 'push', value: 0.3, major: true },
        { id: 'elder', name: 'Elder Dragon', spawn: 35, respawn: 6, kind: 'execute', value: 0.25, major: true },
      ],
      structures: { lanes: 3, perLane: 4, base: 2, tierNames: ['Outer Turret', 'Inner Turret', 'Inhibitor Turret', 'Inhibitor'], baseName: 'Nexus Turret', coreName: 'Nexus', coreHits: 2 },
    },
    stats: MOBA_STATS,
    baseSalary: 320000,
    prizeScale: 2.2,
    notes: 'Stacking drake souls, Baron pushes and Elder executes. Fresh draft every game; champions may return in later games. "Fearless" is an optional named preset (Database Editor → Rules).',
  },
  {
    ...MOBA_COMMON,
    id: 'dota2',
    sourceId: 'S224',
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
    maps: mobaMap('dota2', 'Dota 2 map (Radiant vs Dire)'),
    draft: { mode: 'sequential', steps: DRAFT_DOTA_CM, bansPerTeam: 7, uniqueAcross: true, fearless: 'none' },
    formats: { standard: bo(3), playoff: bo(3), final: bo(5) },
    specRelevance: { ...MOBA_SPEC_NO_SHOOTER, lastHitting: 1.2, creepControl: 1.2 },
    moba: {
      economy: 'gold', tickSec: 60, laneEnd: 12, avgLength: 38, maxLength: 70, gpm: 520, comeback: 0.55, respawnGrowth: 1.5, items: true, denies: true,
      buyback: { baseCost: 200, perMinute: 60, cooldownMin: 8 },
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
    notes: 'Independent economy: last hits and denies, net worth, buyback, Aegis extra life and high-ground defence. Captains Mode preset. Finals start 0-0 (no upper-bracket advantage).',
  },
  {
    id: 'valorant',
    sourceId: 'S232',
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
    notes: 'Agent abilities, Spike plant/defuse and credit economy. First to 13; at 12-12 sides alternate every overtime round until a team leads by two.',
  },
  {
    id: 'cs2',
    sourceId: 'S234',
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
    notes: 'No agents or abilities: grenades, money economy, spray control and the AWP define the game. MR12 (first to 13); at 12-12 overtime blocks of six rounds (three per side) - four wins take the block, 3-3 plays another.',
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
  valorant: '90 70 65 85 90 75 60 70 25 75 80 60 90 50 60 55 50 55 50 50',
  cs2: '70 45 60 95 90 100 100 65 45 15 50 20 20 25 25 30 20 30 40 55',
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
  mlbb: 'fantasy', wildrift: 'fantasy', hok: 'mythic', lol: 'fantasy', dota2: 'fantasy', valorant: 'callsign',
};

/** The fictional characters of saves made before the real-world snapshot (for migrating ids). */
export function legacyCharacters(t: TitleDef): CharDef[] {
  return t.charCount > 0 ? generateCharacters(t.id, t.charCount, t.charClasses, t.styles, FLAVOUR[t.id] ?? 'fantasy') : [];
}

function finalise(t: TitleDef): TitleDef {
  // Real heroes / champions / agents from the offline snapshot; CS2 has none.
  if (t.charCount > 0) t.characters = realCharacters(t) ?? legacyCharacters(t);
  if (t.id === 'valorant') t.maps = generateMaps(t.id, 10, { sideBiasSpread: 0.08, style: 'compound' });
  if (t.id === 'cs2') t.maps = generateMaps(t.id, 9, { sideBiasSpread: 0.1, style: 'compound' });
  return t;
}

TITLES.forEach(finalise);

export const TITLE_BY_ID: Record<string, TitleDef> = Object.fromEntries(TITLES.map((t) => [t.id, t]));
export const TITLE_IDS = TITLES.map((t) => t.id);
export const MOBA_TITLES = TITLES.filter((t) => t.family === 'MOBA').map((t) => t.id);
export const SHOOTER_TITLES = TITLES.filter((t) => t.family === 'SHOOTER').map((t) => t.id);

/**
 * Titles that existed in earlier versions of this game. They are not part of the game any more:
 * never selectable, generated or simulated. Converted old saves keep historical records (stats,
 * awards, trophies) under these IDs, so they resolve to a read-only "(archived)" label.
 */
export const ARCHIVED_TITLES: Record<string, { name: string; short: string; family: 'MOBA' | 'SHOOTER' }> = {
  vainglory: { name: 'Vainglory', short: 'VG', family: 'MOBA' },
  aov: { name: 'Arena of Valor', short: 'AoV', family: 'MOBA' },
  msw: { name: 'Marvel Super War', short: 'MSW', family: 'MOBA' },
  unite: { name: 'Pokémon Unite', short: 'Unite', family: 'MOBA' },
  hots: { name: 'Heroes of the Storm', short: 'HotS', family: 'MOBA' },
  smite2: { name: 'SMITE 2', short: 'SMITE 2', family: 'MOBA' },
  predecessor: { name: 'Predecessor', short: 'Pred', family: 'MOBA' },
  brawlstars: { name: 'Brawl Stars', short: 'Brawl', family: 'MOBA' },
  valmobile: { name: 'Valorant Mobile', short: 'Val Mobile', family: 'SHOOTER' },
  rivals: { name: 'Marvel Rivals', short: 'Rivals', family: 'SHOOTER' },
  overwatch: { name: 'Overwatch', short: 'OW', family: 'SHOOTER' },
  r6: { name: 'Rainbow Six Siege', short: 'R6', family: 'SHOOTER' },
  apex: { name: 'Apex Legends', short: 'Apex', family: 'SHOOTER' },
  warzone: { name: 'Call of Duty: Warzone', short: 'Warzone', family: 'SHOOTER' },
  fortnite: { name: 'Fortnite', short: 'Fortnite', family: 'SHOOTER' },
  pubg: { name: 'PUBG', short: 'PUBG', family: 'SHOOTER' },
  pubgm: { name: 'PUBG Mobile', short: 'PUBGM', family: 'SHOOTER' },
  paladins: { name: 'Paladins', short: 'Paladins', family: 'SHOOTER' },
  deadlock: { name: 'Deadlock', short: 'Deadlock', family: 'SHOOTER' },
  codm: { name: 'Call of Duty: Mobile', short: 'CODM', family: 'SHOOTER' },
  cod: { name: 'Call of Duty', short: 'CoD', family: 'SHOOTER' },
  wzm: { name: 'Call of Duty: Warzone Mobile', short: 'WZM', family: 'SHOOTER' },
  owrush: { name: 'Overwatch Rush', short: 'OW Rush', family: 'SHOOTER' },
};

const archivedCache: Record<string, TitleDef> = {};

/** Is this one of the seven titles in the game (as opposed to an archived historical label)? */
export function isPlayableTitle(id: string): boolean {
  return !!TITLE_BY_ID[id];
}

export function getTitle(id: string): TitleDef {
  const t = TITLE_BY_ID[id];
  if (t) return t;
  const a = ARCHIVED_TITLES[id];
  if (!a) throw new Error(`Unknown title ${id}`);
  // Display-only stub: shaped like a title so history screens render, flagged as archived.
  const base = a.family === 'MOBA' ? TITLE_BY_ID.lol : TITLE_BY_ID.cs2;
  return (archivedCache[id] ||= { ...base, id, sourceId: 'archived', name: `${a.name} (archived)`, short: a.short, archived: true, characters: [], maps: [], notes: 'No longer part of this game; shown for historical records only.' });
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

/** Explicit pairs (editable proposals within the source's bands). Unlisted MOBA pairs share the
 *  'moba-core' subgroup (0.68); MOBA <-> tactical shooter is a different genre (0.2). */
const CLOSE: Array<[string, string, number]> = [
  ['lol', 'wildrift', 0.9],
  ['mlbb', 'hok', 0.82],
  ['mlbb', 'wildrift', 0.8],
  ['hok', 'wildrift', 0.8],
  ['lol', 'dota2', 0.72],
  ['cs2', 'valorant', 0.72],
];

const SUBGROUP: Record<string, string> = {
  'pc-moba': 'moba-core',
  'mobile-moba': 'moba-core',
  'tactical-fps': 'tactical',
};

/** Similarity 0..1 used for familiarity transfer and learning difficulty. */
export function titleSimilarity(a: string, b: string): number {
  if (a === b) return 1;
  for (const [x, y, s] of CLOSE) if ((x === a && y === b) || (x === b && y === a)) return s;
  const ta = TITLE_BY_ID[a];
  const tb = TITLE_BY_ID[b];
  if (!ta || !tb) return 0.1;
  if (ta.subgenre === tb.subgenre) return 0.75;
  if (SUBGROUP[ta.subgenre] && SUBGROUP[ta.subgenre] === SUBGROUP[tb.subgenre]) return 0.68;
  if (ta.family === tb.family) return 0.5;
  return 0.2;
}

export function similarityLabel(s: number): string {
  if (s >= 1) return 'Same title';
  if (s >= 0.8) return 'Very closely related';
  if (s >= 0.65) return 'Same genre, similar subgenre';
  if (s >= 0.35) return 'Same genre, different subgenre';
  return 'Different genres';
}

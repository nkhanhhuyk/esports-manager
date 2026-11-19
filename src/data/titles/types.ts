import type { InputKind } from '../../model/attributes';
import type { SeriesFormat } from '../../model/types';

/** Exactly two engine families: title-aware 5v5 MOBA and round-based 5v5 tactical shooter. */
export type EngineKind = 'moba' | 'tactical';
export type Family = 'MOBA' | 'SHOOTER';

/** Cross-title functional classifications (shared vocabulary). */
export type FunctionalClass =
  | 'Carry/Fragger'
  | 'Entry/Initiator'
  | 'Support/Utility'
  | 'Controller/Anchor'
  | 'Flanker/Lurker'
  | 'Scout/Recon'
  | 'Flex'
  | 'Shotcaller/IGL';

export const FUNCTIONAL_CLASSES: FunctionalClass[] = [
  'Carry/Fragger',
  'Entry/Initiator',
  'Support/Utility',
  'Controller/Anchor',
  'Flanker/Lurker',
  'Scout/Recon',
  'Flex',
  'Shotcaller/IGL',
];

/** Gameplay phases used by the engines. Each phase is rated from a weighted attribute subset. */
export type Phase =
  | 'laning'
  | 'jungle'
  | 'skirmish'
  | 'teamfight'
  | 'macro'
  | 'vision'
  | 'aim'
  | 'entry'
  | 'utility'
  | 'igl'
  | 'clutch'
  | 'objective'
  | 'survival'
  | 'support'
  | 'movement'
  | 'economy';

export interface RoleDef {
  id: string;
  name: string;
  short: string;
  fclass: FunctionalClass[];
  /** Involvement of this role in each phase (relative). */
  phases: Partial<Record<Phase, number>>;
  /** Attribute emphasis multipliers (attribute key -> multiplier), default 1. */
  emphasis: Record<string, number>;
  /** Preferred character classes for this role. */
  classes: string[];
  /** Weapon classes typically used (shooters without characters). */
  weapons?: string[];
}

export interface CharDef {
  id: string;
  name: string;
  cls: string;
  styles: Record<string, number>; // 0..10
  difficulty: number; // 1..10
  side?: 'atk' | 'def';
}

export interface MapDef {
  id: string;
  name: string;
  modes?: string[];
  sideBias?: number; // + favours attack / first side
  size?: 'open' | 'mixed' | 'closed';
  pois?: string[];
  objective?: string; // HotS battleground objective flavour
}

export interface ModeDef {
  id: string;
  name: string;
  kind: string;
  params: Record<string, number>;
}

export interface ObjectiveDef {
  id: string;
  name: string;
  spawn: number; // minute of first spawn
  respawn: number; // minutes
  until?: number; // stops spawning after (minute)
  kind: 'stack' | 'gold' | 'push' | 'aegis' | 'execute' | 'xp' | 'score' | 'siege' | 'buff' | 'shield';
  value: number;
  maxStacks?: number;
  soul?: number; // bonus at maxStacks
  major?: boolean;
}

export interface StructureDef {
  lanes: number;
  perLane: number;
  base: number;
  tierNames: string[];
  baseName: string;
  coreName: string;
  /** Successful pushes needed on an exposed core (durability / defender advantage). */
  coreHits?: number;
}

/** Dota 2 buyback (versioned with the title preset): a dead hero may pay to respawn at once. */
export interface BuybackConfig {
  baseCost: number;
  perMinute: number;
  cooldownMin: number;
}

export interface MobaConfig {
  economy: 'gold';
  tickSec: number;
  laneEnd: number; // minute
  avgLength: number; // minutes
  maxLength: number;
  lanes: Array<{ name: string; roles: string[] }>;
  junglers: string[];
  objectives: ObjectiveDef[];
  structures: StructureDef;
  gpm: number;
  comeback: number; // 0..1
  respawnGrowth: number; // how much late deaths matter
  items: boolean;
  denies?: boolean;
  /** Present only for titles whose preset enables buyback (Dota 2). */
  buyback?: BuybackConfig;
}

export interface TacticalConfig {
  roundsToWin: number;
  half: number;
  overtime: { kind: 'win-by-two' | 'mr'; rounds: number; cap?: number };
  economy: 'credits' | 'money' | 'none';
  startMoney: number;
  winReward: number;
  lossRewards: number[];
  killReward: number;
  plantReward: number;
  maxMoney: number;
  fullBuy: number;
  objectiveName: string;
  sides: [string, string];
  abilities: boolean;
  utilityWeight: number;
  prepPhase?: boolean;
  defenderEdge: number; // base round-win edge for defenders (logit units)
}





export interface DraftStep {
  team: 0 | 1;
  action: 'ban' | 'pick';
}

export interface DraftRules {
  mode: 'sequential' | 'simultaneous' | 'none';
  steps: DraftStep[]; // sequential order (team 0 = first-pick side)
  bansPerTeam: number; // simultaneous mode bans
  uniqueAcross: boolean; // a character may only appear once in a game
  fearless: 'none' | 'team' | 'global'; // series-level reuse restriction (default for the title)
  /** An optional, separately named rules preset a competition may switch on (never silently). */
  fearlessPreset?: 'team' | 'global';
  sideBans?: boolean; // R6: one attack + one defence ban each
}

export interface StatDef {
  key: string;
  label: string;
  /** Higher is better (for leaderboards/awards). */
  good?: boolean;
  avg?: boolean; // show per game average
}

export interface TitleDef {
  id: string;
  /** Stable source-specification ID (S220 MLBB … S234 CS2). */
  sourceId: string;
  name: string;
  short: string;
  family: Family;
  engine: EngineKind;
  subgenre: string;
  input: InputKind;
  releaseYear: number;
  teamSize: number;
  subs: number;
  minAge: number;
  roles: RoleDef[];
  lineup: string[]; // role ids for starting positions
  charLabel: string;
  charClasses: string[];
  charCount: number;
  characters: CharDef[];
  maps: MapDef[];
  modes: ModeDef[];
  mapPool: number; // active competitive map pool size
  draft: DraftRules;
  mapVeto: 'none' | 'tactical' | 'loser-pick' | 'mode-rotation';
  formats: { standard: SeriesFormat; playoff: SeriesFormat; final: SeriesFormat };
  styles: string[];
  specRelevance: Record<string, number>; // specialist attribute key -> relevance 0..1
  coreTilt: Record<string, number>; // title-level core emphasis
  weaponClasses: string[];
  phaseWeights: Partial<Record<Phase, number>>; // team-level importance of each phase
  moba?: MobaConfig;
  tactical?: TacticalConfig;
  stats: StatDef[];
  baseSalary: number; // average established pro salary in a 1.0 economy (USD/season)
  prizeScale: number;
  leagueSize: number; // default teams per regional league
  notes: string;
}

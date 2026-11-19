import type { Phase, RoleDef, FunctionalClass } from './types';

/**
 * Attribute weights for each gameplay phase. `core` covers groups A-G, `spec` the MOBA/shooter
 * specialist attributes. A title's `specRelevance` further scales the specialist weights so
 * that, for example, Last Hitting does nothing in a title without last hitting.
 */
export const PHASE_ATTRS: Record<Phase, { core: Record<string, number>; spec: Record<string, number> }> = {
  laning: {
    core: { microControl: 1, spacing: 1.2, timing: 0.8, dueling: 1.2, comboExecution: 0.8, evasion: 0.6, accuracy: 0.5, matchupKnowledge: 1, discipline: 0.5, resourceDenial: 0.4 },
    spec: { lastHitting: 1.2, waveManagement: 1, laneTrading: 1.3, cooldownTracking: 0.8, trackingAim: 0.6, projectileLeading: 0.6 },
  },
  jungle: {
    core: { mapAwareness: 1, tempoControl: 1.2, objectivePrioritisation: 0.8, anticipation: 1, timing: 0.8, opponentTracking: 1, pressureCreation: 0.8 },
    spec: { junglePathing: 1.4, ganking: 1.3, creepControl: 1 },
  },
  skirmish: {
    core: { skirmishing: 1.4, dueling: 0.8, comboExecution: 1, reactionSpeed: 0.8, followUp: 0.8, targeting: 0.8, decisionMaking: 0.6, evasion: 0.5 },
    spec: { cooldownTracking: 0.6, trackingAim: 0.5, movementShooting: 0.4, projectileLeading: 0.4 },
  },
  teamfight: {
    core: { teamfighting: 1.5, initiation: 0.8, peeling: 0.8, followUp: 0.8, damageOptimisation: 1, survivability: 1, crowdControl: 0.8, spacing: 1, targeting: 1, coordination: 0.8, composure: 0.5 },
    spec: { cooldownTracking: 0.6, trackingAim: 0.6, projectileLeading: 0.5, burstSprayControl: 0.3 },
  },
  macro: {
    core: { rotations: 1.2, objectivePrioritisation: 1, objectiveExecution: 0.8, tempoControl: 1, winConditionRecognition: 1, decisionMaking: 1, mapAwareness: 0.8, shotcalling: 0.6, communication: 0.6, riskAssessment: 0.6, resourceEfficiency: 0.8, spaceControl: 0.6 },
    spec: { waveManagement: 0.8, splitPushing: 0.8, structurePressure: 1, resetTiming: 1 },
  },
  vision: {
    core: { informationControl: 1.5, mapAwareness: 1, opponentTracking: 1, anticipation: 0.8, spaceControl: 0.8, selflessness: 0.5 },
    spec: { soundAwareness: 0.8, angleClearing: 0.4 },
  },
  aim: {
    core: { reactionSpeed: 1, accuracy: 1, microControl: 0.4, targeting: 0.6, dueling: 0.6 },
    spec: { flickAim: 1.2, trackingAim: 0.9, crosshairPlacement: 1.3, recoilControl: 1, burstSprayControl: 1, peekMechanics: 0.8, longRangePrecision: 0.6, weaponSwitching: 0.4, movementShooting: 0.5, projectileLeading: 0.5 },
  },
  entry: {
    core: { initiation: 1.2, dueling: 1, movement: 0.8, reactionSpeed: 0.6, riskAssessment: 0.4, spaceControl: 0.6, pressureCreation: 0.6 },
    spec: { peekMechanics: 1, flickAim: 0.8, angleClearing: 1.2, movementShooting: 0.6 },
  },
  utility: {
    core: { coordination: 1, timing: 0.8, informationControl: 0.8, spaceControl: 1, crowdControl: 0.6, comboExecution: 0.5 },
    spec: { utilityPlacement: 1.5, soundAwareness: 0.3 },
  },
  igl: {
    core: { shotcalling: 1.5, decisionMaking: 1, leadership: 1, communication: 1, anticipation: 0.8, winConditionRecognition: 0.8, tempoControl: 0.6, preparation: 0.6, counterplay: 0.6, draftUnderstanding: 0.4 },
    spec: {},
  },
  clutch: {
    core: { composure: 1.5, decisionMaking: 1, patience: 0.8, concentration: 0.8, mentalResilience: 0.6, riskAssessment: 0.5 },
    spec: { soundAwareness: 0.8, ammoDiscipline: 0.4 },
  },
  objective: {
    core: { objectiveExecution: 1.3, objectivePrioritisation: 1, spaceControl: 1, coordination: 0.8, timing: 0.8, tempoControl: 0.6 },
    spec: { structurePressure: 0.6, creepControl: 0.5, utilityPlacement: 0.4 },
  },
  survival: {
    core: { survivability: 1.2, disengaging: 1, evasion: 0.8, riskAssessment: 1, patience: 0.8, spacing: 0.6, teamwork: 0.6 },
    spec: { movementShooting: 0.3, ammoDiscipline: 0.3 },
  },
  support: {
    core: { peeling: 1.3, selflessness: 1.2, survivability: 0.8, crowdControl: 0.8, teamwork: 1, coordination: 0.8, communication: 0.6 },
    spec: { utilityPlacement: 0.5 },
  },
  movement: {
    core: { movement: 1.5, evasion: 1, multitasking: 0.8, creativity: 0.6, reactionSpeed: 0.5 },
    spec: { movementShooting: 1.2, weaponSwitching: 0.5 },
  },
  economy: {
    core: { resourceEfficiency: 1.5, discipline: 0.8, decisionMaking: 0.8, patience: 0.4 },
    spec: { ammoDiscipline: 0.6 },
  },
};

/** Default core attribute weights for overall effectiveness (C) by engine. */
export const CORE_BASE_BY_GROUP = {
  Mechanical: 1.0,
  Combat: 1.0,
  Tactical: 0.95,
  Mental: 0.9,
  Teamwork: 0.8,
  Knowledge: 0.8,
  // Development attributes mostly drive progression, not in-game performance.
  Development: 0,
};

/** Development attributes that still feed match performance. */
export const PERF_DEV_ATTRS: Record<string, number> = { consistency: 0.6, stamina: 0.3 };

// ---------------------------------------------------------------------------------------------
// Role presets keep the catalogue compact. Each title can override fields.
// ---------------------------------------------------------------------------------------------

type RoleSeed = Omit<RoleDef, 'id' | 'name' | 'short'>;

export function role(id: string, name: string, short: string, seed: RoleSeed, extra?: Partial<RoleDef>): RoleDef {
  return { id, name, short, ...seed, ...extra, emphasis: { ...seed.emphasis, ...(extra?.emphasis ?? {}) } };
}

const fc = (...c: FunctionalClass[]): FunctionalClass[] => c;

export const MOBA_ROLE = {
  solo: {
    fclass: fc('Controller/Anchor', 'Flex'),
    phases: { laning: 0.35, skirmish: 0.15, teamfight: 0.2, macro: 0.2, vision: 0.05, clutch: 0.05 },
    emphasis: { dueling: 1.8, laneTrading: 1.8, splitPushing: 2, initiation: 1.3, survivability: 1.4, waveManagement: 1.4, mentalResilience: 1.2, patience: 1.2, junglePathing: 0.2, ganking: 0.3 },
    classes: [],
  } as RoleSeed,
  jungle: {
    fclass: fc('Flanker/Lurker', 'Shotcaller/IGL'),
    phases: { jungle: 0.4, skirmish: 0.2, teamfight: 0.15, macro: 0.15, vision: 0.1 },
    emphasis: { junglePathing: 2.5, ganking: 2.5, creepControl: 2, tempoControl: 1.8, mapAwareness: 1.6, objectivePrioritisation: 1.8, objectiveExecution: 1.6, anticipation: 1.4, opponentTracking: 1.6, pressureCreation: 1.5, lastHitting: 0.3, laneTrading: 0.3, waveManagement: 0.4 },
    classes: [],
  } as RoleSeed,
  mid: {
    fclass: fc('Carry/Fragger', 'Flex'),
    phases: { laning: 0.3, skirmish: 0.2, teamfight: 0.25, macro: 0.15, vision: 0.05, clutch: 0.05 },
    emphasis: { comboExecution: 1.8, microControl: 1.5, dueling: 1.4, damageOptimisation: 1.5, laneTrading: 1.6, lastHitting: 1.4, pressureCreation: 1.4, rotations: 1.3, cooldownTracking: 1.4, junglePathing: 0.2 },
    classes: [],
  } as RoleSeed,
  carry: {
    fclass: fc('Carry/Fragger'),
    phases: { laning: 0.3, teamfight: 0.35, skirmish: 0.1, macro: 0.1, clutch: 0.1, vision: 0.05 },
    emphasis: { lastHitting: 2.2, damageOptimisation: 2, spacing: 2, targeting: 1.8, survivability: 1.4, laneTrading: 1.4, accuracy: 1.4, buildOptimisation: 1.4, resetTiming: 1.4, junglePathing: 0.2, ganking: 0.3 },
    classes: [],
  } as RoleSeed,
  support: {
    fclass: fc('Support/Utility', 'Entry/Initiator'),
    phases: { laning: 0.15, teamfight: 0.3, vision: 0.3, skirmish: 0.1, macro: 0.1, clutch: 0.05 },
    emphasis: { peeling: 2, crowdControl: 1.8, initiation: 1.6, informationControl: 2, selflessness: 2, communication: 1.6, shotcalling: 1.4, mapAwareness: 1.4, lastHitting: 0.2, junglePathing: 0.3, damageOptimisation: 0.6 },
    classes: [],
  } as RoleSeed,
  roam: {
    fclass: fc('Support/Utility', 'Entry/Initiator', 'Shotcaller/IGL'),
    phases: { teamfight: 0.3, vision: 0.3, skirmish: 0.15, macro: 0.15, jungle: 0.1 },
    emphasis: { initiation: 2, crowdControl: 1.8, informationControl: 2, peeling: 1.6, selflessness: 1.8, shotcalling: 1.6, mapAwareness: 1.6, rotations: 1.5, lastHitting: 0.1, ganking: 1.2 },
    classes: [],
  } as RoleSeed,
};

export const SHOOTER_ROLE = {
  entry: {
    fclass: fc('Entry/Initiator', 'Carry/Fragger'),
    phases: { aim: 0.35, entry: 0.4, clutch: 0.05, utility: 0.1, vision: 0.1 },
    emphasis: { flickAim: 2, peekMechanics: 2, initiation: 2, movement: 1.6, reactionSpeed: 1.5, dueling: 1.8, movementShooting: 1.2, patience: 0.6 },
    classes: [],
  } as RoleSeed,
  star: {
    fclass: fc('Carry/Fragger', 'Flex'),
    phases: { aim: 0.5, entry: 0.15, clutch: 0.15, vision: 0.1, utility: 0.1 },
    emphasis: { flickAim: 1.6, trackingAim: 1.5, crosshairPlacement: 1.8, recoilControl: 1.6, burstSprayControl: 1.6, dueling: 1.6, damageOptimisation: 1.4 },
    classes: [],
  } as RoleSeed,
  sniper: {
    fclass: fc('Carry/Fragger', 'Controller/Anchor'),
    phases: { aim: 0.55, clutch: 0.15, vision: 0.15, entry: 0.05, utility: 0.1 },
    emphasis: { longRangePrecision: 2.5, flickAim: 2, reactionSpeed: 1.6, patience: 1.4, spaceControl: 1.6, weaponSwitching: 1.4, recoilControl: 0.5, burstSprayControl: 0.5 },
    classes: [],
  } as RoleSeed,
  support: {
    fclass: fc('Support/Utility', 'Scout/Recon'),
    phases: { utility: 0.4, aim: 0.25, vision: 0.15, support: 0.1, clutch: 0.1 },
    emphasis: { utilityPlacement: 2.2, selflessness: 1.8, coordination: 1.8, followUp: 1.6, informationControl: 1.5, teamwork: 1.5 },
    classes: [],
  } as RoleSeed,
  lurker: {
    fclass: fc('Flanker/Lurker'),
    phases: { aim: 0.35, vision: 0.25, clutch: 0.2, entry: 0.1, utility: 0.1 },
    emphasis: { soundAwareness: 2, patience: 1.8, anticipation: 1.8, opponentTracking: 1.8, creativity: 1.4, timing: 1.6, composure: 1.4 },
    classes: [],
  } as RoleSeed,
  anchor: {
    fclass: fc('Controller/Anchor'),
    phases: { aim: 0.35, clutch: 0.2, vision: 0.2, utility: 0.2, support: 0.05 },
    emphasis: { angleClearing: 1.6, soundAwareness: 1.6, patience: 1.8, anticipation: 1.6, utilityPlacement: 1.6, composure: 1.4, crosshairPlacement: 1.4, spaceControl: 1.5 },
    classes: [],
  } as RoleSeed,
  controller: {
    fclass: fc('Controller/Anchor', 'Shotcaller/IGL'),
    phases: { utility: 0.4, igl: 0.2, aim: 0.2, vision: 0.1, clutch: 0.1 },
    emphasis: { utilityPlacement: 2.2, spaceControl: 2, tempoControl: 1.6, shotcalling: 1.4, patience: 1.4, decisionMaking: 1.4, timing: 1.5 },
    classes: [],
  } as RoleSeed,
  recon: {
    fclass: fc('Scout/Recon', 'Support/Utility'),
    phases: { utility: 0.35, vision: 0.25, aim: 0.25, entry: 0.1, clutch: 0.05 },
    emphasis: { informationControl: 2.2, utilityPlacement: 2, coordination: 1.6, followUp: 1.6, timing: 1.4, soundAwareness: 1.2 },
    classes: [],
  } as RoleSeed,
  igl: {
    fclass: fc('Shotcaller/IGL', 'Flex'),
    phases: { igl: 0.45, aim: 0.25, utility: 0.15, clutch: 0.1, vision: 0.05 },
    emphasis: { shotcalling: 2.5, leadership: 2.2, communication: 2, decisionMaking: 1.8, preparation: 1.6, counterplay: 1.6, anticipation: 1.6, winConditionRecognition: 1.6 },
    classes: [],
  } as RoleSeed,
  flex: {
    fclass: fc('Flex'),
    phases: { aim: 0.35, utility: 0.2, entry: 0.15, clutch: 0.15, vision: 0.15 },
    emphasis: { adaptability: 1.8, creativity: 1.4, matchupKnowledge: 1.4, metaUnderstanding: 1.4 },
    classes: [],
  } as RoleSeed,
  // Hero shooter roles
  tank: {
    fclass: fc('Entry/Initiator', 'Controller/Anchor'),
    phases: { teamfight: 0.3, entry: 0.25, macro: 0.2, survival: 0.15, igl: 0.1 },
    emphasis: { initiation: 2.2, spaceControl: 2.2, survivability: 2, tempoControl: 1.6, crowdControl: 1.5, shotcalling: 1.4, accuracy: 0.8, flickAim: 0.4, crosshairPlacement: 0.6 },
    classes: [],
  } as RoleSeed,
  hitscan: {
    fclass: fc('Carry/Fragger'),
    phases: { aim: 0.5, teamfight: 0.2, survival: 0.15, clutch: 0.15 },
    emphasis: { trackingAim: 2, flickAim: 1.8, crosshairPlacement: 1.6, longRangePrecision: 1.4, targeting: 1.6, spacing: 1.4, recoilControl: 0.6 },
    classes: [],
  } as RoleSeed,
  projectile: {
    fclass: fc('Flanker/Lurker', 'Carry/Fragger'),
    phases: { aim: 0.35, skirmish: 0.25, teamfight: 0.2, movement: 0.1, clutch: 0.1 },
    emphasis: { projectileLeading: 2.2, movement: 1.6, comboExecution: 1.6, skirmishing: 1.6, pressureCreation: 1.4, creativity: 1.2, recoilControl: 0.3 },
    classes: [],
  } as RoleSeed,
  mainSupport: {
    fclass: fc('Support/Utility'),
    phases: { support: 0.45, teamfight: 0.2, survival: 0.2, vision: 0.15 },
    emphasis: { selflessness: 2, peeling: 1.8, survivability: 1.8, communication: 1.6, mapAwareness: 1.5, trackingAim: 1.2, projectileLeading: 1.2 },
    classes: [],
  } as RoleSeed,
  flexSupport: {
    fclass: fc('Support/Utility', 'Flex'),
    phases: { support: 0.3, aim: 0.25, teamfight: 0.25, utility: 0.2 },
    emphasis: { timing: 1.6, crowdControl: 1.5, followUp: 1.5, flickAim: 1.2, projectileLeading: 1.3, selflessness: 1.4 },
    classes: [],
  } as RoleSeed,
};

/** Shooter specialist relevance presets. */
export const SPEC = {
  noShooter: { flickAim: 0, trackingAim: 0, crosshairPlacement: 0, recoilControl: 0, burstSprayControl: 0, projectileLeading: 0, peekMechanics: 0, movementShooting: 0, angleClearing: 0, utilityPlacement: 0, soundAwareness: 0, ammoDiscipline: 0, longRangePrecision: 0, weaponSwitching: 0 },
  noMoba: { lastHitting: 0, waveManagement: 0, laneTrading: 0, junglePathing: 0, ganking: 0, creepControl: 0, splitPushing: 0, cooldownTracking: 0, structurePressure: 0, resetTiming: 0 },
  fullMoba: { lastHitting: 1, waveManagement: 1, laneTrading: 1, junglePathing: 1, ganking: 1, creepControl: 1, splitPushing: 1, cooldownTracking: 1, structurePressure: 1, resetTiming: 1 },
};

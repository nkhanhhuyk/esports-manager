/**
 * The universal 86-attribute framework shared by every esports player.
 *
 * 62 core attributes (groups A-G), 10 MOBA specialist attributes (H) and 14 shooter specialist
 * attributes (I). Every attribute is a 0-100 value stored in a fixed-order number[] on the
 * player (`player.attrs`), which keeps saves compact and lookups fast.
 *
 * `inputSensitivity` describes how strongly an attribute's *execution* depends on familiarity
 * with the competitive input device. 1.0 applies the full adaptation formula
 *   Effective = M x (0.65 + 0.35 x P / 100)
 * while 0 (e.g. Decision Making, Composure) applies no input penalty at all.
 */

export type AttrGroup =
  | 'Mechanical'
  | 'Combat'
  | 'Tactical'
  | 'Mental'
  | 'Teamwork'
  | 'Knowledge'
  | 'Development'
  | 'MOBA'
  | 'Shooter';

export interface AttrDef {
  index: number;
  key: string;
  name: string;
  group: AttrGroup;
  inputSensitivity: number;
  /** How quickly the attribute responds to training (relative). */
  trainability: number;
  /** Age-curve family: mechanical skills peak early, mental/knowledge peak late. */
  curve: 'mech' | 'combat' | 'mind' | 'knowledge' | 'physical';
  short: string;
}

const RAW: Array<[string, string, AttrGroup, number, number, AttrDef['curve'], string]> = [
  // A. Mechanical (10)
  ['microControl', 'Micro Control', 'Mechanical', 1.0, 0.8, 'mech', 'MIC'],
  ['reactionSpeed', 'Reaction Speed', 'Mechanical', 0.5, 0.4, 'mech', 'REA'],
  ['accuracy', 'Accuracy', 'Mechanical', 1.0, 0.8, 'mech', 'ACC'],
  ['evasion', 'Evasion', 'Mechanical', 0.8, 0.8, 'mech', 'EVA'],
  ['comboExecution', 'Combo Execution', 'Mechanical', 1.0, 0.9, 'mech', 'CMB'],
  ['movement', 'Movement', 'Mechanical', 1.0, 0.8, 'mech', 'MOV'],
  ['spacing', 'Spacing', 'Mechanical', 0.5, 0.9, 'combat', 'SPC'],
  ['timing', 'Timing', 'Mechanical', 0.3, 0.9, 'combat', 'TIM'],
  ['targeting', 'Targeting', 'Mechanical', 0.6, 0.9, 'combat', 'TGT'],
  ['multitasking', 'Multitasking', 'Mechanical', 0.6, 0.7, 'mech', 'MLT'],
  // B. Combat (10)
  ['dueling', 'Dueling', 'Combat', 0.3, 0.9, 'combat', 'DUE'],
  ['skirmishing', 'Skirmishing', 'Combat', 0.2, 0.9, 'combat', 'SKR'],
  ['teamfighting', 'Teamfighting', 'Combat', 0.15, 0.9, 'combat', 'TFT'],
  ['initiation', 'Initiation', 'Combat', 0.15, 0.9, 'combat', 'INI'],
  ['disengaging', 'Disengaging', 'Combat', 0.15, 0.9, 'combat', 'DIS'],
  ['followUp', 'Follow-up', 'Combat', 0.1, 0.9, 'combat', 'FUP'],
  ['peeling', 'Peeling', 'Combat', 0.1, 0.9, 'combat', 'PEE'],
  ['damageOptimisation', 'Damage Optimisation', 'Combat', 0.2, 0.9, 'combat', 'DMG'],
  ['survivability', 'Survivability', 'Combat', 0.2, 0.9, 'combat', 'SUR'],
  ['crowdControl', 'Crowd Control', 'Combat', 0.2, 0.9, 'combat', 'CC'],
  // C. Tactical & Macro (12)
  ['mapAwareness', 'Map Awareness', 'Tactical', 0, 1.0, 'mind', 'MAP'],
  ['rotations', 'Rotations', 'Tactical', 0, 1.0, 'mind', 'ROT'],
  ['objectivePrioritisation', 'Objective Prioritisation', 'Tactical', 0, 1.0, 'mind', 'OBP'],
  ['objectiveExecution', 'Objective Execution', 'Tactical', 0.1, 1.0, 'mind', 'OBX'],
  ['tempoControl', 'Tempo Control', 'Tactical', 0, 1.0, 'mind', 'TMP'],
  ['resourceEfficiency', 'Resource Efficiency', 'Tactical', 0, 1.0, 'mind', 'REF'],
  ['resourceDenial', 'Resource Denial', 'Tactical', 0.1, 1.0, 'mind', 'RDN'],
  ['spaceControl', 'Space Control', 'Tactical', 0, 1.0, 'mind', 'SPA'],
  ['pressureCreation', 'Pressure Creation', 'Tactical', 0, 1.0, 'mind', 'PRS'],
  ['informationControl', 'Information Control', 'Tactical', 0, 1.0, 'mind', 'INF'],
  ['winConditionRecognition', 'Win Condition Recognition', 'Tactical', 0, 1.0, 'mind', 'WCR'],
  ['opponentTracking', 'Opponent Tracking', 'Tactical', 0, 1.0, 'mind', 'OPT'],
  // D. Mental (10)
  ['decisionMaking', 'Decision Making', 'Mental', 0, 0.8, 'mind', 'DEC'],
  ['anticipation', 'Anticipation', 'Mental', 0, 0.8, 'mind', 'ANT'],
  ['composure', 'Composure', 'Mental', 0, 0.6, 'mind', 'COM'],
  ['concentration', 'Concentration', 'Mental', 0, 0.7, 'mind', 'CON'],
  ['adaptability', 'Adaptability', 'Mental', 0, 0.6, 'mind', 'ADA'],
  ['discipline', 'Discipline', 'Mental', 0, 0.7, 'mind', 'DSC'],
  ['creativity', 'Creativity', 'Mental', 0, 0.5, 'mind', 'CRE'],
  ['riskAssessment', 'Risk Assessment', 'Mental', 0, 0.8, 'mind', 'RSK'],
  ['mentalResilience', 'Mental Resilience', 'Mental', 0, 0.6, 'mind', 'RES'],
  ['patience', 'Patience', 'Mental', 0, 0.6, 'mind', 'PAT'],
  // E. Teamwork & Communication (6)
  ['communication', 'Communication', 'Teamwork', 0, 0.9, 'mind', 'CMM'],
  ['shotcalling', 'Shotcalling', 'Teamwork', 0, 0.8, 'mind', 'SHC'],
  ['coordination', 'Coordination', 'Teamwork', 0, 1.0, 'mind', 'CRD'],
  ['teamwork', 'Teamwork', 'Teamwork', 0, 0.8, 'mind', 'TWK'],
  ['leadership', 'Leadership', 'Teamwork', 0, 0.5, 'mind', 'LDR'],
  ['selflessness', 'Selflessness', 'Teamwork', 0, 0.5, 'mind', 'SEL'],
  // F. Knowledge & Strategy (7)
  ['matchupKnowledge', 'Matchup Knowledge', 'Knowledge', 0, 1.1, 'knowledge', 'MUK'],
  ['draftUnderstanding', 'Draft Understanding', 'Knowledge', 0, 1.0, 'knowledge', 'DRF'],
  ['counterplay', 'Counterplay', 'Knowledge', 0, 1.0, 'knowledge', 'CTP'],
  ['metaUnderstanding', 'Meta Understanding', 'Knowledge', 0, 1.1, 'knowledge', 'MET'],
  ['patchAdaptation', 'Patch Adaptation', 'Knowledge', 0, 0.8, 'knowledge', 'PAD'],
  ['buildOptimisation', 'Build Optimisation', 'Knowledge', 0, 1.1, 'knowledge', 'BLD'],
  ['preparation', 'Preparation', 'Knowledge', 0, 0.9, 'knowledge', 'PRP'],
  // G. Development & Professional (7)
  ['learningSpeed', 'Learning Speed', 'Development', 0, 0.2, 'physical', 'LRN'],
  ['workEthic', 'Work Ethic', 'Development', 0, 0.3, 'physical', 'WRK'],
  ['coachability', 'Coachability', 'Development', 0, 0.3, 'physical', 'CCH'],
  ['professionalism', 'Professionalism', 'Development', 0, 0.3, 'physical', 'PRO'],
  ['consistency', 'Consistency', 'Development', 0, 0.5, 'physical', 'CNS'],
  ['stamina', 'Stamina', 'Development', 0, 0.5, 'physical', 'STA'],
  ['recovery', 'Recovery', 'Development', 0, 0.4, 'physical', 'RCV'],
  // H. MOBA specialist (10)
  ['lastHitting', 'Last Hitting', 'MOBA', 0.7, 1.0, 'mech', 'LH'],
  ['waveManagement', 'Wave Management', 'MOBA', 0, 1.0, 'mind', 'WAV'],
  ['laneTrading', 'Lane Trading', 'MOBA', 0.4, 1.0, 'combat', 'LTR'],
  ['junglePathing', 'Jungle Pathing', 'MOBA', 0, 1.0, 'mind', 'JGP'],
  ['ganking', 'Ganking', 'MOBA', 0.2, 1.0, 'combat', 'GNK'],
  ['creepControl', 'Creep/Neutral Control', 'MOBA', 0.3, 1.0, 'mind', 'NEU'],
  ['splitPushing', 'Split Pushing', 'MOBA', 0.1, 1.0, 'mind', 'SPL'],
  ['cooldownTracking', 'Cooldown Tracking', 'MOBA', 0, 1.0, 'mind', 'CDT'],
  ['structurePressure', 'Structure Pressure', 'MOBA', 0, 1.0, 'mind', 'STR'],
  ['resetTiming', 'Reset Timing', 'MOBA', 0, 1.0, 'mind', 'RST'],
  // I. Shooter specialist (14)
  ['flickAim', 'Flick Aim', 'Shooter', 1.0, 0.7, 'mech', 'FLK'],
  ['trackingAim', 'Tracking Aim', 'Shooter', 1.0, 0.8, 'mech', 'TRK'],
  ['crosshairPlacement', 'Crosshair Placement', 'Shooter', 0.6, 1.0, 'combat', 'XHP'],
  ['recoilControl', 'Recoil Control', 'Shooter', 1.0, 1.0, 'mech', 'RCL'],
  ['burstSprayControl', 'Burst/Spray Control', 'Shooter', 0.8, 1.0, 'mech', 'SPR'],
  ['projectileLeading', 'Projectile Leading', 'Shooter', 0.8, 0.9, 'mech', 'PRJ'],
  ['peekMechanics', 'Peek Mechanics', 'Shooter', 0.8, 1.0, 'combat', 'PEK'],
  ['movementShooting', 'Movement Shooting', 'Shooter', 1.0, 0.9, 'mech', 'MVS'],
  ['angleClearing', 'Angle Clearing', 'Shooter', 0.4, 1.0, 'combat', 'ANG'],
  ['utilityPlacement', 'Utility Placement', 'Shooter', 0.3, 1.1, 'knowledge', 'UTL'],
  ['soundAwareness', 'Sound Awareness', 'Shooter', 0, 1.0, 'mind', 'SND'],
  ['ammoDiscipline', 'Ammo Discipline', 'Shooter', 0, 1.0, 'mind', 'AMM'],
  ['longRangePrecision', 'Long-range Precision', 'Shooter', 0.8, 0.9, 'mech', 'LRP'],
  ['weaponSwitching', 'Weapon Switching', 'Shooter', 0.9, 1.0, 'mech', 'WSW'],
];

export const ATTRS: AttrDef[] = RAW.map(([key, name, group, inputSensitivity, trainability, curve, short], index) => ({
  index,
  key,
  name,
  group,
  inputSensitivity,
  trainability,
  curve,
  short,
}));

export const ATTR_COUNT = 86;
export const CORE_COUNT = 62;
export const MOBA_START = 62;
export const SHOOTER_START = 72;

if (ATTRS.length !== ATTR_COUNT) throw new Error(`Attribute table has ${ATTRS.length} entries, expected 86`);

/** Attribute key -> index lookup, e.g. A.microControl === 0. */
export const A = Object.fromEntries(ATTRS.map((a) => [a.key, a.index])) as Record<string, number>;

export const ATTR_GROUPS: AttrGroup[] = ['Mechanical', 'Combat', 'Tactical', 'Mental', 'Teamwork', 'Knowledge', 'Development', 'MOBA', 'Shooter'];

export const GROUP_INDICES: Record<AttrGroup, number[]> = ATTR_GROUPS.reduce(
  (acc, g) => {
    acc[g] = ATTRS.filter((a) => a.group === g).map((a) => a.index);
    return acc;
  },
  {} as Record<AttrGroup, number[]>,
);

export const MOBA_INDICES = GROUP_INDICES.MOBA;
export const SHOOTER_INDICES = GROUP_INDICES.Shooter;
export const CORE_INDICES = ATTRS.filter((a) => a.index < CORE_COUNT).map((a) => a.index);

/** Rating interpretation bands from the design specification. */
export function ratingBand(v: number): { label: string; tier: number } {
  if (v >= 95) return { label: 'World-class', tier: 6 };
  if (v >= 85) return { label: 'Elite', tier: 5 };
  if (v >= 75) return { label: 'Excellent', tier: 4 };
  if (v >= 60) return { label: 'Good', tier: 3 };
  if (v >= 40) return { label: 'Average', tier: 2 };
  if (v >= 20) return { label: 'Below average', tier: 1 };
  return { label: 'Extremely poor', tier: 0 };
}

// ---------------------------------------------------------------------------------------------
// Input proficiencies (separate from the 86 attributes)
// ---------------------------------------------------------------------------------------------

export type InputKind = 'touch' | 'mkb' | 'controller';
export const INPUTS: InputKind[] = ['touch', 'mkb', 'controller'];
export const INPUT_LABEL: Record<InputKind, string> = { touch: 'Touchscreen', mkb: 'Mouse & Keyboard', controller: 'Controller' };
export const inputIndex = (k: InputKind): number => (k === 'touch' ? 0 : k === 'mkb' ? 1 : 2);

/** Base adaptation constants (tunable, see DECISIONS.md). */
export const INPUT_FLOOR = 0.65;
export const INPUT_SPAN = 0.35;

/**
 * Specification formula: Effective Mechanics = M x (0.65 + 0.35 x P / 100).
 * `sensitivity` scales the penalty for attributes that are only partly execution-bound.
 */
export function effectiveMechanic(m: number, p: number, sensitivity = 1): number {
  const penalty = sensitivity * INPUT_SPAN * (1 - p / 100);
  return m * (1 - penalty);
}

/** Effective value of attribute i for a player using an input with proficiency p. */
export function effectiveAttr(attrs: readonly number[], i: number, inputProf: number): number {
  return effectiveMechanic(attrs[i], inputProf, ATTRS[i].inputSensitivity);
}

// ---------------------------------------------------------------------------------------------
// Derived summaries (NOT permanent attributes)
// ---------------------------------------------------------------------------------------------

export type SummaryKey =
  | 'Mechanics'
  | 'Combat Ability'
  | 'Macro Ability'
  | 'Game Sense'
  | 'Mentality'
  | 'Teamplay'
  | 'Strategic Intelligence'
  | 'Development Potential'
  | 'MOBA Specialism'
  | 'Shooter Specialism';

const avgIdx = (attrs: readonly number[], idx: readonly number[]): number => {
  let s = 0;
  for (const i of idx) s += attrs[i];
  return idx.length ? s / idx.length : 0;
};

const GAME_SENSE = ['mapAwareness', 'anticipation', 'decisionMaking', 'opponentTracking', 'winConditionRecognition', 'riskAssessment', 'informationControl'].map((k) => A[k]);
const DEV_POT = ['learningSpeed', 'workEthic', 'coachability', 'professionalism', 'adaptability'].map((k) => A[k]);

export function summaries(attrs: readonly number[]): Record<SummaryKey, number> {
  return {
    Mechanics: avgIdx(attrs, GROUP_INDICES.Mechanical),
    'Combat Ability': avgIdx(attrs, GROUP_INDICES.Combat),
    'Macro Ability': avgIdx(attrs, GROUP_INDICES.Tactical),
    'Game Sense': avgIdx(attrs, GAME_SENSE),
    Mentality: avgIdx(attrs, GROUP_INDICES.Mental),
    Teamplay: avgIdx(attrs, GROUP_INDICES.Teamwork),
    'Strategic Intelligence': avgIdx(attrs, GROUP_INDICES.Knowledge),
    'Development Potential': avgIdx(attrs, DEV_POT),
    'MOBA Specialism': avgIdx(attrs, MOBA_INDICES),
    'Shooter Specialism': avgIdx(attrs, SHOOTER_INDICES),
  };
}

/** Helper to build a sparse weight vector from attribute keys. */
export function weights(spec: Record<string, number>): Array<[number, number]> {
  return Object.entries(spec).map(([k, w]) => {
    const i = A[k];
    if (i === undefined) throw new Error(`Unknown attribute key: ${k}`);
    return [i, w];
  });
}

// ---------------------------------------------------------------------------------------------
// Staff attributes
// ---------------------------------------------------------------------------------------------

export const STAFF_ATTRS = [
  ['tactics', 'Tactical Knowledge'],
  ['drafting', 'Drafting & Strategy'],
  ['development', 'Player Development'],
  ['youth', 'Youth Development'],
  ['motivation', 'Motivation'],
  ['manManagement', 'Man Management'],
  ['discipline', 'Discipline'],
  ['analysis', 'Analysis'],
  ['judgingAbility', 'Judging Ability'],
  ['judgingPotential', 'Judging Potential'],
  ['negotiation', 'Negotiation'],
  ['mentalCoaching', 'Mental Coaching'],
  ['performance', 'Performance Science'],
  ['metaReading', 'Meta Reading'],
  ['adaptability', 'Adaptability'],
  ['communication', 'Communication'],
] as const;

export type StaffAttrKey = (typeof STAFF_ATTRS)[number][0];

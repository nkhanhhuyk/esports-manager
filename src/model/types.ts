import type { DayNum } from '../core/date';
import type { InputKind, StaffAttrKey } from './attributes';

export type TitleId = string;
export type RegionId = string;
export type CountryCode = string;
export type ID = string;

// ---------------------------------------------------------------------------------------------
// Players
// ---------------------------------------------------------------------------------------------

export interface Personality {
  ambition: number;
  loyalty: number;
  temperament: number; // high = calm
  ego: number;
  greed: number;
  controversy: number;
  pressure: number; // big-match temperament (hidden)
}

export type Trajectory = 'early' | 'steady' | 'late' | 'volatile' | 'resurgent';

export interface DevProfile {
  trajectory: Trajectory;
  peakAge: number;
  declineAge: number;
  declineRate: number; // multiplier on decline speed
  growthRate: number; // multiplier on growth speed
  resurgenceUsed?: boolean;
}

export interface Proficiencies {
  titles: Record<TitleId, number>;
  roles: Record<string, number>; // key `${titleId}:${roleId}`
  chars: Record<TitleId, Record<string, number>>;
  maps: Record<TitleId, Record<string, number>>;
  weapons: Record<TitleId, Record<string, number>>;
}

export interface Contract {
  id: ID;
  teamId: ID;
  orgId: ID;
  salary: number; // per season (USD)
  startDay: DayNum;
  endSeason: number; // contract expires at the end of this season
  signingBonus: number;
  winBonus: number; // per trophy
  prizeShare: number; // 0..1 share of prize money distributed to players
  buyout: number | null;
  option: 'none' | 'team' | 'player';
  terminationFee: number;
  loan?: { parentTeamId: ID; untilDay: DayNum; wageSplit: number };
}

export interface StatLine {
  season: number;
  titleId: TitleId;
  teamId: ID | null;
  gp: number; // games played
  w: number;
  l: number;
  ratingSum: number;
  mvps: number;
  s: Record<string, number>; // title-specific stat totals
}

export interface CareerEntry {
  season: number;
  day: DayNum;
  titleId: TitleId;
  teamId: ID | null;
  orgId: ID | null;
  roleId?: string;
  type: 'start' | 'signed' | 'transfer' | 'loan' | 'loan-return' | 'promoted' | 'released' | 'draft' | 'trade' | 'retired' | 'title-switch' | 'role-switch';
  fee?: number;
  note?: string;
}

export interface AwardRecord {
  season: number;
  awardId: string;
  label: string;
  titleId?: TitleId;
  compId?: ID;
  teamId?: ID;
  /** Legacy points at the time of the award (includes title prestige of that era). */
  pts?: number;
}

/**
 * Provenance of an entity created from the real-world snapshot. Absent = fictional (game-generated).
 * `est` lists fields that are estimates rather than published facts. Ratings, attributes,
 * personalities, contracts and finances are always game simulation.
 */
export interface RealTag {
  src: string;
  snap: string;
  est?: string[];
}

export interface Player {
  /** Real-world provenance (absent = fictional). */
  real?: RealTag;
  id: ID;
  first: string;
  last: string;
  handle: string;
  birth: DayNum;
  nat: CountryCode;
  nat2?: CountryCode;
  langs: string[];
  teamId: ID | null;
  titleId: TitleId;
  dualTitleId?: TitleId; // optional temporary second competitive title
  roles: string[]; // primary first
  prefInput: InputKind;
  attrs: number[]; // 86 values
  input: [number, number, number]; // touch, mkb, controller
  prof: Proficiencies;
  pa: number; // hidden potential (CA scale)
  ca: number; // cached current ability for primary title/role
  form: number; // -10..+10
  morale: number; // 0..100
  fatigue: number; // 0..100 (0 = fresh)
  injury: { label: string; untilDay: DayNum } | null;
  /**
   * Time away from competition: a break of a few weeks, or stepping away for the rest of the
   * season (hiatus). The player stays under contract but cannot play, train or be fielded.
   */
  leave?: { kind: 'break' | 'hiatus'; reason: string; fromDay: DayNum; untilDay: DayNum; byClub?: boolean } | null;
  /** Trial with a club (free agents and intake prospects): reserved for that club until the end. */
  trial?: { teamId: ID; untilDay: DayNum } | null;
  /** A break the player has asked the user's club for (awaiting an answer in the inbox). */
  leaveRequest?: { kind: 'break' | 'hiatus'; reason: string; days: number; day: DayNum } | null;
  pers: Personality;
  dev: DevProfile;
  contract: Contract | null;
  value: number;
  rep: number; // fame 0..100
  career: CareerEntry[];
  stats: StatLine[];
  awards: AwardRecord[];
  rel: Record<ID, number>; // relationship with other players (-100..100)
  caHistory: Array<{ season: number; ca: number; titleId: TitleId }>;
  coachApt: number; // hidden aptitude for post-career staff roles
  joinedDay: DayNum;
  listed: boolean; // transfer listed
  /** Asking price when listed (user clubs); AI bids anchor on it. */
  askingPrice?: number | null;
  /** Sell-on clause owed to a former club: pct of the next transfer fee. */
  sellOn?: { teamId: ID; orgId: ID; pct: number } | null;
  /** Agreed free move at the end of the current contract. */
  preContract?: PreContract | null;
  unhappy: number; // 0..100
  retired: { season: number; day: DayNum } | null;
  attrVersion: number; // bumped when attrs change (rating cache invalidation)
}

// ---------------------------------------------------------------------------------------------
// Staff
// ---------------------------------------------------------------------------------------------

export type StaffRole =
  | 'head-coach'
  | 'assistant-coach'
  | 'analyst'
  | 'scout'
  | 'gm'
  | 'director'
  | 'academy-coach'
  | 'psychologist'
  | 'performance-coach';

export interface Staff {
  real?: RealTag;
  id: ID;
  first: string;
  last: string;
  handle?: string;
  birth: DayNum;
  nat: CountryCode;
  langs: string[];
  role: StaffRole;
  titleId: TitleId | null;
  teamId: ID | null;
  orgId: ID | null;
  attrs: Record<StaffAttrKey, number>;
  titleFam: Record<TitleId, number>;
  contract: Contract | null;
  rep: number;
  career: CareerEntry[];
  awards: AwardRecord[];
  formerPlayerId?: ID;
  retired: { season: number } | null;
  pa: number;
}

// ---------------------------------------------------------------------------------------------
// Teams & organisations
// ---------------------------------------------------------------------------------------------

export type TeamTier = 'main' | 'academy' | 'reserve' | 'youth';

export interface LineupSlot {
  playerId: ID;
  roleId: string;
}

export interface TeamPrefs {
  style: string;
  aggression: number; // 0..100
  draftPriority: 'comfort' | 'meta' | 'counter' | 'balanced';
  pool: Record<string, string[]>; // roleId -> priority character ids
  bans: string[]; // priority ban targets
  mapPrefs: Record<string, number>; // -2..+2
  delegateLineup: boolean;
  delegateDraft: boolean;
  delegateTraining: boolean;
  /** Tender qualifying offers to restricted free agents (leagues with RFA). Default on. */
  tenderRFA?: boolean;
  /** Standing registration plan: the players to register when a competition locks (else the coach picks). */
  registrationPlan?: ID[];
}

export type TrainingFocus =
  | 'balanced'
  | 'mechanics'
  | 'moba'
  | 'shooter'
  | 'roles'
  | 'characters'
  | 'weapons'
  | 'maps'
  | 'tactics'
  | 'knowledge'
  | 'mental'
  | 'input'
  | 'recovery'
  | 'teamwork';

/** One practice block (morning or afternoon). */
export type SessionType = 'rest' | 'individual' | 'tactics' | 'scrim' | 'review' | 'pool' | 'maps' | 'prep' | 'patch' | 'mental';

/** How a planned block went this week. */
export interface SessionSlot {
  /** Planned session, or 'match' when the team plays that block. */
  t: SessionType | 'match';
  /** held | cancelled | unsupervised (over staff capacity, half value) | match */
  s: 'held' | 'cancelled' | 'unsupervised' | 'match';
  /** Reason for a cancellation. */
  r?: string;
  /** Scrim partner (team id) and result (1 = won the scrim block). */
  p?: ID;
  w?: 0 | 1;
  /** Scrim learning quality (partner strength, region). */
  q?: number;
  /** Internal scrim against the organisation's own academy/parent team. */
  internal?: boolean;
}

export interface TrainingWeek {
  start: DayNum;
  slots: SessionSlot[]; // 14: Mon AM, Mon PM … Sun PM
  plannedBy: 'coach' | 'manager';
  rooms: number;
  staffCap: number;
  coached: number;
  devFactor: number;
  fatigue: number;
  taper: boolean;
}

export interface TrainingPlan {
  /** Development objective; the coaches build the weekly sessions around it. */
  focus: TrainingFocus;
  intensity: number; // 0..100
  /** Scrims the coaches aim to book per week. */
  scrimsPerWeek: number;
  individual: Record<ID, TrainingFocus>;
  /** Coaches schedule opponent-preparation sessions before matches. */
  opponentPrep: boolean;
  /** Consecutive weeks on the same team focus (diminishing returns). */
  focusWeeks?: number;
  lastFocus?: TrainingFocus;
  /** Manager's own weekly timetable (14 blocks), used when training is not delegated. */
  template?: SessionType[];
  /** Scrim partner policy: best available (avoids upcoming opponents) or only outside the league. */
  scrimPolicy?: 'best' | 'outside-league';
  /** This week's timetable as it actually happened (user and followed teams only). */
  week?: TrainingWeek;
  /** Opponent preparation level for this week's matches (0..1). */
  prep?: number;
  /** Scrim intel: teams this team has scrimmed recently → day the knowledge expires. */
  intel?: Record<ID, DayNum>;
}

export interface TeamSeasonRecord {
  season: number;
  leagueId: ID | null;
  leagueName: string;
  w: number;
  l: number;
  rank: number | null;
  results: Array<{ compId: ID; compName: string; place: number; tier: CompTier }>;
}

export interface TrophyRecord {
  season: number;
  compId: ID;
  compName: string;
  tier: CompTier;
  titleId: TitleId;
}

export interface Team {
  real?: RealTag;
  id: ID;
  orgId: ID;
  titleId: TitleId;
  name: string;
  short: string;
  tier: TeamTier;
  region: RegionId;
  leagueId: ID | null;
  parentTeamId: ID | null; // academy/reserve parent
  roster: ID[];
  staff: ID[];
  lineup: LineupSlot[];
  prefs: TeamPrefs;
  training: TrainingPlan;
  chemistry: number; // 0..100
  elo: number;
  metaAdapt: number; // 0..100 alignment with current patch
  salaryBudget: number;
  history: TeamSeasonRecord[];
  trophies: TrophyRecord[];
  founded: number;
  active: boolean;
  /** Real-world dormant teams: the last season the team was seen at the top level. */
  lastSeen?: number;
  scrimRating: number; // short-term practice quality
  /** Cache key of the last automatic lineup (avoids recomputation every match). */
  lineupSig?: string;
  /** Country code for national teams (players keep their club contracts). */
  national?: CountryCode;
}

export type OrgStrategy = 'win-now' | 'balanced' | 'youth' | 'moneyball' | 'prestige' | 'expansion';
export type FacilityKey = 'training' | 'analytics' | 'academy' | 'equipment' | 'housing' | 'performance';

export interface LedgerEntry {
  day: DayNum;
  kind: string;
  amount: number;
  teamId?: ID;
  note?: string;
}

export interface MonthlyFinance {
  month: number; // monthKey
  revenue: Record<string, number>;
  expenses: Record<string, number>;
  balance: number;
}

export interface Sponsor {
  id: ID;
  name: string;
  perMonth: number;
  untilSeason: number;
  titleId?: TitleId;
}

export interface OrgEvent {
  season: number;
  day: DayNum;
  type: 'founded' | 'rebrand' | 'merger' | 'acquisition' | 'relocation' | 'division-opened' | 'division-closed' | 'ownership' | 'bankruptcy' | 'partnership';
  text: string;
}

/** Supporter segments (master prompt §14 / source "Fans have different reasons to care"). */
export type FanSegmentId = 'local' | 'winning' | 'player' | 'style' | 'community' | 'legacy';

export interface FanSegment {
  /** Followers in the segment (reach / interest). */
  size: number;
  /** Sentiment 0..100 (50 = neutral). Paying conversion depends on it. */
  mood: number;
}

export interface Fanbase {
  seg: Record<FanSegmentId, FanSegment>;
  /** Recent attention 0..100 from big matches, rivalries and signings; decays monthly. */
  buzz: number;
  /** Player fans who stay regardless of the roster; stars' own followers come on top. */
  playerBase?: number;
  /** Recent fan reactions to real events (newest last). */
  log: Array<{ day: DayNum; seg: FanSegmentId; d: number; note: string }>;
  /** Monthly snapshots (user organisation only). */
  history?: Array<{ month: number; followers: number; paying: number; mood: number }>;
  /** Mood alerts already sent to the user (hysteresis). */
  alerts?: Partial<Record<FanSegmentId, 'low' | 'high'>>;
}

/** A rivalry between two divisions of the same title, built only from recorded encounters. */
export interface Rivalry {
  id: string;
  titleId: TitleId;
  teams: [ID, ID];
  intensity: number; // 0..100
  peak: number;
  meetings: number;
  wins: [number, number];
  finals: number;
  playoffs: number;
  transfers: number;
  derby: boolean;
  firstSeason: number;
  /** Season the rivalry first became notable (intensity ≥ 30). */
  since?: number;
  lastDay: DayNum;
  moments: Array<{ day: DayNum; season: number; kind: 'final' | 'playoff' | 'decider' | 'upset' | 'transfer' | 'derby'; text: string; fixtureId?: ID }>;
}

export interface Organisation {
  real?: RealTag;
  id: ID;
  name: string;
  short: string;
  country: CountryCode;
  region: RegionId;
  founded: number;
  colors: [string, string];
  rep: number; // 0..100
  fans: number; // followers (= sum of fan segment sizes)
  /** Supporter segments; absent for federations. */
  fanbase?: Fanbase;
  prestige: number; // accumulated historic prestige
  owner: { name: string; type: 'founder' | 'investor' | 'public' | 'conglomerate' | 'club'; wealth: number; patience: number };
  strategy: OrgStrategy;
  balance: number;
  sponsors: Sponsor[];
  /** Internal budget movements (authorisations, not cash) — audit trail. */
  budgetLog?: Array<{ day: DayNum; teamId: ID | null; toTeamId: ID | null; amount: number; note: string }>;
  ledger: LedgerEntry[];
  monthly: MonthlyFinance[];
  debtMonths: number;
  facilities: Record<FacilityKey, number>; // 1..10
  teams: ID[];
  partners: Array<{ orgId: ID; type: 'affiliate' | 'partner' | 'joint' }>;
  names: Array<{ season: number; name: string; short: string }>;
  events: OrgEvent[];
  active: boolean;
  user: boolean;
  /** Federation pseudo-organisation that owns national teams (no finances / market). */
  federation?: boolean;
}

// ---------------------------------------------------------------------------------------------
// Leagues, competitions and fixtures
// ---------------------------------------------------------------------------------------------

export interface SalaryCapPolicy {
  type: 'none' | 'soft' | 'hard';
  amount: number;
  taxRate: number;
}

export interface LeaguePolicy {
  minAge: number;
  rosterMin: number;
  rosterMax: number;
  foreignLimit: number; // max non-regional starters (-1 = unlimited)
  cap: SalaryCapPolicy;
  minSalary: number;
  maxSalary: number;
  trades: boolean;
  draft: boolean;
  loans: boolean;
  transferFees: boolean;
  restrictedFA: boolean;
}

export interface League {
  real?: RealTag;
  id: ID;
  titleId: TitleId;
  region: RegionId;
  tier: 1 | 2;
  name: string;
  short: string;
  teamIds: ID[];
  size: number;
  franchised: boolean;
  policy: LeaguePolicy;
  promotion: number; // slots exchanged with the tier below
  upperId: ID | null;
  lowerId: ID | null;
  fullDetail: boolean;
  founded: number;
}

export type CompTier = 'league' | 'playoffs' | 'challenger' | 'international' | 'worlds' | 'qualifier' | 'cup';

export type StageType = 'round-robin' | 'double-round-robin' | 'groups' | 'swiss' | 'single-elim' | 'double-elim' | 'page-playoff';

export type FormatKey = 'standard' | 'playoff' | 'final';

export interface StandingRow {
  teamId: ID;
  w: number;
  l: number;
  gw: number; // games/maps/sets won
  gl: number;
  pts: number; // league points or BR points
  kills?: number;
  wins?: number; // BR chicken dinners
  group?: number;
  diff?: number;
}

export type BracketSource = { seed: number } | { winnerOf: string } | { loserOf: string };

export interface BracketNode {
  id: string;
  round: number;
  label: string;
  a: BracketSource;
  b: BracketSource;
  teamA: ID | null | undefined; // undefined = unresolved, null = bye
  teamB: ID | null | undefined;
  fixtureId: ID | null;
  winner: ID | null;
  loser: ID | null;
  format: FormatKey;
  elimPlace: number; // placement for the loser
  isFinal?: boolean;
}

export interface StageConfig {
  groups?: number;
  advance?: number; // teams advancing (per group for groups)
  swissWins?: number;
  swissLosses?: number;
  matchdays?: number; // br-points
  gamesPerDay?: number;
  matchPoint?: number; // br match-point threshold
  daysBetweenRounds?: number;
  matchDaysOfWeek?: number[];
  format?: FormatKey;
}

export interface Stage {
  id: string;
  name: string;
  type: StageType;
  config: StageConfig;
  startDay: DayNum;
  teamIds: ID[];
  standings: StandingRow[];
  fixtureIds: ID[];
  bracket: BracketNode[];
  swissRound: number;
  status: 'pending' | 'active' | 'done';
  placements: ID[]; // final order
  brMatchPointWinner?: ID | null;
}

export type EntrantRule =
  | { kind: 'league'; leagueId: ID }
  | { kind: 'top'; compId: ID; count: number; skip?: number }
  | { kind: 'circuit'; leagueId: ID; count: number }
  | { kind: 'fixed'; teamIds: ID[] }
  | { kind: 'nations'; titleId: TitleId; count: number };

export interface Competition {
  id: ID;
  name: string;
  short: string;
  titleId: TitleId;
  season: number;
  tier: CompTier;
  region: RegionId | null;
  leagueId: ID | null;
  entrants: EntrantRule[];
  teamIds: ID[];
  stages: Stage[];
  current: number;
  status: 'pending' | 'active' | 'done';
  startDay: DayNum;
  prizePool: number;
  prizeSplit: number[];
  circuitPoints: number[];
  placements: ID[];
  fullDetail: boolean;
  mvpPlayerId?: ID;
  finalsMvpPlayerId?: ID;
  prestige: number; // 0..100 weight for history/GOAT
  formatOverrides?: Partial<Record<FormatKey, SeriesFormat>>;
  rewarded?: boolean;
  split?: number;
  /** Real-world careers: the event carries the real circuit's name (format, dates, entrants and prizes are game simulation). */
  realName?: boolean;
  /** Competition rules that override the title's defaults (e.g. First Stand's hard Fearless draft). */
  rules?: { fearless?: 'none' | 'team' | 'global' };
  /** Per-player aggregate inside this competition (MVP voting). */
  pstats: Record<ID, CompPlayerStats>;
  /**
   * Registered players per entrant, frozen when the competition locks (3 days before it starts).
   * Only registered players may play in this competition. Absent for editions created before
   * registration existed (then every rostered player is eligible).
   */
  registrations?: Record<ID, ID[]>;
  /** Registration changes made after the lock, per team (limited per edition). */
  amendments?: Record<ID, Array<{ day: DayNum; added: ID; removed: ID | null; emergency: boolean }>>;
}

/** Best-of-n series: first to ceil(n/2) game (MOBA) or map (shooter) wins. */
export interface SeriesFormat {
  kind: 'bo';
  n: number;
  label: string;
}

/**
 * A player's numbers in one competition: games, wins, rating sum, game MVPs, team, and the title's
 * stat totals as an array in the title's stat order (`getTitle(titleId).stats`). `v` is absent in
 * competitions recorded by older builds.
 */
export interface CompPlayerStats {
  gp: number;
  r: number;
  m: number;
  teamId: ID;
  w?: number;
  v?: number[];
}

export interface Fixture {
  id: ID;
  compId: ID;
  stageId: string;
  titleId: TitleId;
  day: DayNum;
  teams: ID[];
  format: SeriesFormat;
  formatKey: FormatKey;
  status: 'scheduled' | 'done';
  nodeId?: string;
  round?: number;
  group?: number;
  winner?: ID | null;
  score?: number[];
  detail: boolean;
  /**
   * MOBA fidelity lock, decided when the series starts (before any result exists): true = the
   * spatial battlefield simulation (your/followed teams, or opened live), false = fast engine.
   */
  spatial?: boolean;
  mvp?: ID;
}

// ---------------------------------------------------------------------------------------------
// Match results
// ---------------------------------------------------------------------------------------------

export interface GameEvent {
  t: number; // seconds or round number
  team: -1 | 0 | 1;
  kind: string;
  text: string;
  big?: boolean;
}

export interface PlayerGameLine {
  playerId: ID;
  teamIdx: number;
  roleId: string;
  charId?: string;
  s: Record<string, number>;
  rating: number;
}

export interface DraftRecord {
  bans: [string[], string[]];
  picks: [string[], string[]];
  order: Array<{ team: number; action: 'ban' | 'pick'; charId: string }>;
  compScore: [number, number];
  /** playerId -> charId for each team. */
  assign: [Record<string, string>, Record<string, string>];
  /** Series team index that held first pick (side choice) in this game. */
  firstPick?: 0 | 1;
}

/**
 * Recorded spatial timeline of one MOBA game (authoritative simulation output). Frames are taken
 * once per simulated second; per-frame arrays are flattened (frame-major). The renderer only
 * interpolates between frames — it never changes the simulation.
 */
export interface MobaReplay {
  v: 1;
  mapId: string;
  titleId: string;
  /** Seconds between frames. */
  dt: number;
  frames: number;
  heroes: Array<{ side: 0 | 1; playerId: ID; handle: string; char: string; roleId: string }>;
  structures: string[];
  camps: string[];
  pits: string[];
  /** Hero x, y (map units), hp % (0..100), level, state code (bit 0 = dead, bits 1..4 = activity), target (−1 none, 0..9 hero, 100+ structure, 200+ pit). */
  hx: number[];
  hy: number[];
  hhp: number[];
  hlv: number[];
  hst: number[];
  htg: number[];
  /** Structure hp % (0 = destroyed). */
  sh: number[];
  /** Per lane (top, mid, bot) × side: front wave position along that side's lane (0..1000‰, −1 none) and minion count. */
  wf: number[];
  wn: number[];
  /** Per pit: objective present (index into `objectives`, −1 none) and hp %. */
  po: number[];
  ph: number[];
  /** Camp alive (1/0). */
  ca: number[];
  /** Team gold (total), kills, structures destroyed, per frame × 2. */
  tg: number[];
  tk: number[];
  ts: number[];
  objectives: string[];
  wards: Array<{ side: 0 | 1; x: number; y: number; from: number; to: number }>;
  events: Array<{ t: number; k: string; a?: number; b?: number; x?: number; y?: number; s?: 0 | 1; n?: string; as?: number[] }>;
}

/** Recorded spatial timeline of one shooter map (frames every `dt` seconds, frame-major arrays). */
export interface ShooterReplay {
  v: 1;
  mapId: string;
  titleId: string;
  dt: number;
  frames: number;
  players: Array<{ side: 0 | 1; playerId: ID; handle: string; char: string; roleId: string }>;
  /** Per frame × 10: x, y, hp (0 = dead), state bits (1 spike, 2 planting, 4 defusing, 8 blinded, 16 moving), weapon code, target (−1 none). */
  px: Int16Array;
  py: Int16Array;
  php: Int16Array;
  pst: Int16Array;
  pwp: Int16Array;
  ptg: Int16Array;
  /** Per frame: round index, seconds left in the round (or on the spike), spike state (0 carried, 1 dropped, 2 planted, 3 defused, 4 exploded), spike x, y. */
  fr: Int16Array;
  ft: Int16Array;
  fs: Int16Array;
  fx: Int16Array;
  fy: Int16Array;
  weapons: string[];
  rounds: Array<{ idx: number; from: number; to: number; attack: 0 | 1; buys: [string, string]; winner: 0 | 1; reason: string; site?: string; score: [number, number]; money: [number, number] }>;
  utility: Array<{ t: number; kind: string; side: 0 | 1; x: number; y: number; r: number; until: number; by: number }>;
  events: Array<{ t: number; k: string; a?: number; b?: number; x?: number; y?: number; s?: 0 | 1; n?: string; hs?: boolean }>;
}

export interface GameResult {
  idx: number;
  /** Shooter map replay (session only, like `replay`). */
  sreplay?: ShooterReplay;
  /** Compact box score (when `players` was slimmed away). See src/sim/boxscore.ts. */
  bx?: { ids: string[]; ti: number[]; ro: string[]; ch: Array<string | null>; r: number[]; k: string[]; s: number[] };
  /** Spatial replay (kept in memory for games watched or played live; not stored in saves). */
  replay?: MobaReplay;
  /** Which MOBA engine produced the game: the spatial battlefield or the fast background engine. */
  engine?: 'spatial' | 'fast';
  winner: number; // team index, -1 for BR
  mapId?: string;
  modeId?: string;
  duration: number; // seconds
  score: number[]; // e.g. rounds 13-9, kills, points
  teamStats: Array<Record<string, number>>;
  players: PlayerGameLine[];
  draft?: DraftRecord;
  events?: GameEvent[];
  summary: string;
  sides?: string[];
  /** MOBAs: series index of the team on Blue / Radiant (map side 0). Absent in older results (= 0). */
  blue?: 0 | 1;
}

export interface MatchRecord {
  fixtureId: ID;
  titleId: TitleId;
  day: DayNum;
  teams: ID[];
  games: GameResult[];
  vetoes?: Array<{ team: number; action: 'ban' | 'pick' | 'decider'; mapId: string; modeId?: string }>;
  mvp?: ID;
  detail: boolean;
  /** Estimated peak viewers (interest, not paying spectators). */
  audience?: number;
}

// ---------------------------------------------------------------------------------------------
// Titles (dynamic world state per title)
// ---------------------------------------------------------------------------------------------

export type PatchKind = 'minor' | 'medium' | 'major';
export type ObjectiveKind = 'stack' | 'gold' | 'push' | 'aegis' | 'execute' | 'xp' | 'score' | 'siege' | 'buff' | 'shield';

/** An objective added by a major patch; it spawns in the pits of an existing objective (`pitAs`). */
export interface PatchObjective {
  id: string;
  name: string;
  spawn: number;
  respawn: number;
  until?: number;
  kind: ObjectiveKind;
  value: number;
  major?: boolean;
  maxSpawns?: number;
  pitAs: string;
}

/**
 * Gameplay rules changed by major patches (cumulative: each patch carries the full set in force).
 * The simulations read them through src/sim/common/rules.ts.
 */
export interface PatchRules {
  /** Changes to the title's own objectives (absolute values). `until: null` removes a cut-off. */
  objectives?: Record<string, { spawn?: number; respawn?: number; until?: number | null; kind?: ObjectiveKind; value?: number; maxSpawns?: number }>;
  /** New objectives. */
  added?: PatchObjective[];
  /** MOBA multipliers: gold income, structure health, death timers. */
  moba?: { gpm?: number; structHp?: number; respawn?: number };
  /** Shooter rules: economy (absolute values / loss-bonus multiplier), defender edge shift (logit), timers (seconds). */
  tactical?: { startMoney?: number; winReward?: number; lossMult?: number; killReward?: number; plantReward?: number; fullBuy?: number; defenderEdge?: number; roundTime?: number; bombTime?: number; plantTime?: number; defuseTime?: number };
}

export interface Patch {
  id: string;
  version: string;
  day: DayNum;
  season: number;
  /** True for medium and major patches (and the season-opening patch of older saves). */
  major: boolean;
  /** Minor (small balance changes during the season), medium (meta-shifting, before First Stand, MSI and Worlds) or major (after Worlds: gameplay changes). */
  kind?: PatchKind;
  rules?: PatchRules;
  charMeta: Record<string, number>; // -10..+10
  styles: Record<string, number>; // style -> weight multiplier
  mapPool: string[];
  objectiveMods: Record<string, number>;
  notes: string[];
  era: string;
}

export interface TitleState {
  id: TitleId;
  popularity: Record<RegionId, number>;
  globalPop: number;
  prestige: number;
  patches: Patch[];
  active: boolean;
  revivalCount: number;
  popHistory: Array<{ season: number; pop: number }>;
}

// ---------------------------------------------------------------------------------------------
// Market, news, inbox and history
// ---------------------------------------------------------------------------------------------

export interface TransferRecord {
  id: ID;
  day: DayNum;
  season: number;
  playerId: ID;
  fromTeamId: ID | null;
  toTeamId: ID | null;
  fee: number;
  type: 'transfer' | 'free' | 'loan' | 'release' | 'trade' | 'promotion' | 'draft' | 'retire' | 'loan-return';
  titleId: TitleId;
  note?: string;
}

export interface PreContract {
  teamId: ID;
  orgId: ID;
  salary: number;
  years: number;
  signingBonus: number;
  day: DayNum;
  /** Restricted free agency: the current club holds matching rights. */
  offerSheet: boolean;
  /** Current club's matching decision (null = undecided; AI decides at rollover). */
  matched: boolean | null;
}

export interface NegotiationTerms {
  salary: number;
  years: number;
  signingBonus: number;
  buyout: number | null;
}

/**
 * A multi-step transfer: club fee negotiation, then personal terms with the player.
 * Pre-contracts skip the club stage (no fee) and complete at the end of the season.
 */
export interface Negotiation {
  id: ID;
  day: DayNum;
  kind: 'transfer' | 'precontract';
  playerId: ID;
  buyerTeamId: ID;
  sellerTeamId: ID | null;
  stage: 'club' | 'terms' | 'agreed' | 'completed' | 'collapsed' | 'withdrawn';
  fee: number;
  sellOnPct: number;
  clubCounter: number | null;
  clubRounds: number;
  terms: NegotiationTerms | null;
  playerCounter: { salary: number; years: number } | null;
  termsRounds: number;
  expiresDay: DayNum;
  /** After a breakdown the selling club refuses new approaches until this day. */
  blockedUntil?: DayNum;
  user: boolean;
  log: Array<{ day: DayNum; text: string }>;
}

export interface Offer {
  id: ID;
  day: DayNum;
  playerId: ID;
  fromTeamId: ID; // bidding team
  toTeamId: ID | null; // current team (null = free agent)
  fee: number;
  salary: number;
  years: number;
  status: 'pending' | 'accepted' | 'rejected' | 'withdrawn' | 'completed' | 'countered';
  kind: 'transfer' | 'free' | 'loan' | 'trade' | 'extension';
  tradePlayerIds?: ID[];
  counterFee?: number;
  /** Hidden ceiling the bidding club would pay (counter-offers above it fail). */
  maxFee?: number;
  response?: string;
  user: boolean; // involves the user's organisation
  expiresDay: DayNum;
}

export interface NewsItem {
  id: ID;
  day: DayNum;
  kind: 'transfer' | 'result' | 'upset' | 'champion' | 'retirement' | 'prospect' | 'patch' | 'finance' | 'org' | 'award' | 'controversy' | 'record' | 'world' | 'rivalry' | 'fans';
  title: string;
  body: string;
  titleId?: TitleId;
  refs: Array<{ type: 'player' | 'team' | 'org' | 'comp' | 'staff'; id: ID }>;
  importance: number; // 1..5
}

export interface InboxMessage {
  id: ID;
  day: DayNum;
  from: string;
  subject: string;
  body: string;
  read: boolean;
  actions?: Array<{ id: string; label: string }>;
  resolved?: string;
  payload?: Record<string, string | number>;
  kind: string;
}

export interface SeasonAward {
  season: number;
  awardId: string;
  label: string;
  titleId: TitleId | null;
  compId?: ID;
  playerId?: ID;
  staffId?: ID;
  teamId?: ID;
  orgId?: ID;
  value?: number;
}

export interface SeasonHistory {
  season: number;
  champions: Array<{ compId: ID; compName: string; titleId: TitleId; tier: CompTier; teamId: ID; teamName: string; orgId: ID; runnerUpId?: ID; mvp?: ID }>;
  standings: Record<ID, Array<{ teamId: ID; name: string; w: number; l: number }>>; // league id -> table
  events: string[];
  titlePop: Record<TitleId, number>;
}

export interface HallOfFameEntry {
  playerId: ID;
  season: number;
  name: string;
  handle: string;
  titles: TitleId[];
  legacy: number;
}

export interface RetiredSummary {
  id: ID;
  handle: string;
  name: string;
  nat: CountryCode;
  titles: TitleId[];
  retiredSeason: number;
  legacy: number;
  trophies: number;
  awards: number;
  /** Real retired pro (snapshot): last team and role, provenance. */
  lastTeam?: string;
  roleId?: string;
  real?: RealTag;
  /** A comeback was arranged (the record became a player again). */
  cameBack?: boolean;
  /** The player record created by the comeback. */
  cameBackAs?: ID;
}

export interface BoardObjective {
  season: number;
  teamId: ID | null;
  kind: 'league-rank' | 'qualify-worlds' | 'finance';
  target: number;
  label: string;
  status: 'pending' | 'met' | 'failed';
}

export interface JobApproach {
  id: ID;
  orgId: ID;
  day: DayNum;
  expiresDay: DayNum;
  years: number;
  reason: string;
  status: 'pending' | 'accepted' | 'declined' | 'expired';
}

export type CareerRole = 'coach-gm' | 'head-coach' | 'gm' | 'director' | 'owner' | 'national-coach' | 'commissioner';

export interface DetailSettings {
  /** Competitions in these titles get full-detail box scores + commentary. */
  fullDetailTitles: TitleId[];
  /** Keep full detail for the user's own matches. */
  userMatches: boolean;
  /** Keep full detail for all international events. */
  internationals: boolean;
}

export interface WorldMeta {
  version: number;
  saveId: string;
  name: string;
  seed: string;
  created: number;
  updated: number;
  startDay: DayNum;
  userOrgId: ID | null;
  careerRole: CareerRole;
  detail: DetailSettings;
  worldSize: 'tiny' | 'small' | 'standard' | 'large';
  autosave: boolean;
  managerName: string;
  titlesEnabled: TitleId[];
  /** Board objectives & confidence (career job security). */
  board?: {
    confidence: number;
    objectives: BoardObjective[];
    history: Array<{ season: number; confidence: number; verdict: string }>;
    /** Mid-season reactions (splits, trophies, finances), newest last. */
    notes?: Array<{ day: DayNum; season: number; text: string; delta: number }>;
    /** Final warning: another poor result this season ends the job. */
    ultimatum?: { season: number; day: DayNum } | null;
    /** Season in which the board last warned about finances. */
    financeWarned?: number;
  };
  /** The manager's own contract with the current organisation (job security / renewals). */
  managerContract?: { orgId: ID; endSeason: number; since: DayNum };
  /** Approaches from other organisations while employed. */
  jobApproaches?: JobApproach[];
  /** Set after a dismissal until the user accepts a new job. */
  unemployed?: boolean;
  /** Manual save slot (1-5) the Save button writes to; unset until the player picks one. */
  activeSlot?: number | null;
  /** Optional national-team job held alongside the club role. */
  nationalJob?: { titleId: TitleId; country: CountryCode; squad: ID[] } | null;
  /** 'manager' (default) runs one organisation; 'spectator' follows teams while AI staff decide everything. */
  mode?: 'manager' | 'spectator';
  /** Teams followed (spectator mode, or extra teams to follow as a manager). */
  followed?: ID[];
  /** Spectator: stop the advance the day before a followed team plays (to watch it live). */
  stopForFollowed?: boolean;
  /** Real-character snapshot the world uses (absent = generated fictional characters, pre-v7). */
  characterSnapshot?: string;
  /** Shooter maps are the real maps (set for new worlds and after converting older saves). */
  /** Real-world careers: past teams, former pros and retired pros from the history snapshot were added. */
  realHistory?: boolean;
  /** Snapshot pros are free agents (never archived as retired). */
  realHistoryFree?: boolean;
  realMaps?: boolean;
  /** 'real' = started from the real-world team/player snapshot; 'fictional' (default) = generated. */
  dataMode?: 'real' | 'fictional';
  /** Roster snapshot id for real-world starts. */
  rosterSnapshot?: string;
  /** Difficulty & sandbox options (absent = standard). */
  difficulty?: { preset: 'relaxed' | 'standard' | 'hard' | 'custom'; income: number; board: number; injuries: number; breaks: number; bargaining: number };
  /** Areas the manager hands to AI staff even though the role could do them. */
  delegation?: Partial<Record<DelegationArea, boolean>>;
}

/** Areas of club management that can be delegated to AI staff. */
export type DelegationArea = 'signings' | 'sales' | 'contracts' | 'staff' | 'finance' | 'registration';

export interface World {
  /** Index of archived competition editions (their data lives in the season archive). */
  /** Finished seasons' competitions and fixtures (results); box scores live in the match store. */
  past?: Record<number, { comps: Competition[]; fixtures: Fixture[] }>;
  compIndex?: Array<{ id: string; season: number; titleId: string; tier: CompTier; name: string; short: string; region: string | null; leagueId: string | null; split?: number; startDay: number; champion: string | null; runnerUp: string | null; teams: number; prizePool: number }>;
  meta: WorldMeta;
  day: DayNum;
  season: number;
  titles: Record<TitleId, TitleState>;
  countryScene: Record<CountryCode, { moba: number; shooter: number }>;
  players: Record<ID, Player>;
  staff: Record<ID, Staff>;
  teams: Record<ID, Team>;
  orgs: Record<ID, Organisation>;
  leagues: Record<ID, League>;
  comps: Record<ID, Competition>;
  fixtures: Record<ID, Fixture>;
  schedule: Record<number, ID[]>;
  matches: Record<ID, MatchRecord>;
  transfers: TransferRecord[];
  offers: Offer[];
  news: NewsItem[];
  inbox: InboxMessage[];
  awards: SeasonAward[];
  history: SeasonHistory[];
  hof: HallOfFameEntry[];
  retired: RetiredSummary[];
  scouting: Record<ID, number>;
  shortlist: ID[];
  counter: number;
  processed: { lastDay: DayNum; lastMonth: number; lastWeek: number; seasonRolled: number[] };
  pendingUserMatch: ID | null;
  /** User scouting focus list (scouted faster). */
  scoutFocus: ID[];
  overrides: Record<ID, UserMatchPlan>;
  /** User-edited series format presets per title (database editor). */
  customFormats: Record<TitleId, Partial<Record<FormatKey, SeriesFormat>>>;
  /** Tradable rookie-draft picks (leagues with drafts enabled). */
  draftPicks: DraftPickAsset[];
  /** Transfer negotiations (user and AI-visible), pruned after resolution. */
  negotiations: Negotiation[];
  /** Optional, explicitly named rules presets per title (e.g. LoL "Fearless" draft). */
  rulePresets?: Record<string, { fearless?: 'none' | 'team' | 'global' }>;
  /** Rivalries keyed by sorted team-id pair. */
  rivalries?: Record<string, Rivalry>;
  /** Trade proposals AI clubs sent to the user's teams. */
  tradeOffers?: TradeOffer[];
  /** Youth intake: last season processed and the user's open intake classes. */
  youth?: { lastSeason: number; classes: YouthClass[] };
  /** Season previews (power rankings & predictions) and reviews, by season. */
  previews?: Record<number, SeasonPreview>;
  reviews?: Record<number, SeasonReview>;
  /** All-time head-to-head ledger between teams (sorted pair key). */
  h2h?: Record<string, H2HRecord>;
}

/** The user's youth intake class at one academy (AI clubs decide on the day). */
export interface YouthClass {
  season: number;
  day: DayNum;
  deadline: DayNum;
  orgId: ID;
  teamId: ID;
  prospects: ID[];
  /** Prospect id → signed | released | trial */
  decisions: Record<ID, 'signed' | 'released'>;
  closed: boolean;
}

export interface PreviewTeam {
  teamId: ID;
  rank: number;
  strength: number;
  elo: number;
  /** Short note: biggest arrival/departure, form. */
  note?: string;
}

export interface SeasonPreview {
  season: number;
  day: DayNum;
  titles: Record<TitleId, {
    leagues: Array<{ leagueId: ID; teams: PreviewTeam[] }>;
    worldsFavourites: ID[];
    playersToWatch: ID[];
    breakoutCandidates: ID[];
    biggestMoves: Array<{ playerId: ID; fromTeamId: ID | null; toTeamId: ID | null; fee: number }>;
  }>;
}

export interface Storyline {
  kind: 'champion' | 'underdog' | 'collapse' | 'dynasty' | 'breakout' | 'prediction' | 'record' | 'rivalry' | 'transfer' | 'retirement';
  titleId?: TitleId;
  text: string;
  refs: Array<{ type: 'player' | 'team' | 'org' | 'comp'; id: ID }>;
}

export interface SeasonReview {
  season: number;
  day: DayNum;
  champions: Array<{ titleId: TitleId; compId: ID; compName: string; tier: CompTier; teamId: ID; runnerUpId?: ID }>;
  awards: Array<{ awardId: string; label: string; titleId?: TitleId; playerId?: ID; teamId?: ID; staffId?: ID }>;
  storylines: Storyline[];
  /** Preview vs reality per league: predicted rank → actual rank. */
  predictions: Array<{ leagueId: ID; titleId: TitleId; rows: Array<{ teamId: ID; predicted: number; actual: number }> }>;
}

/**
 * Head-to-head between two teams (key = sorted team ids), stored compactly:
 * [meetings, series wins of the first team, of the second, games won by the first, by the second,
 *  finals, last season met].
 */
export type H2HRecord = number[];

/** A trade an AI club proposes to one of the user's teams. */
export interface TradeOffer {
  id: ID;
  day: DayNum;
  expiresDay: DayNum;
  /** The AI club making the offer. */
  fromTeamId: ID;
  /** The user's team. */
  toTeamId: ID;
  /** Players and picks the AI club sends. */
  give: ID[];
  givePicks: ID[];
  /** Players and picks it wants from the user. */
  take: ID[];
  takePicks: ID[];
  /** Cash the AI club adds (negative: it asks the user to add cash). */
  cash: number;
  kind: string;
  status: 'pending' | 'accepted' | 'rejected' | 'expired' | 'withdrawn';
}

export interface DraftPickAsset {
  id: ID;
  leagueId: ID;
  /** The draft is held at the rollover that ends this season. */
  season: number;
  round: number;
  originalTeamId: ID;
  ownerTeamId: ID;
  used: boolean;
}

/** Plan the user submits for a specific fixture (lineup / draft choices / map picks). */
export interface UserMatchPlan {
  lineup?: LineupSlot[];
  /** Completed interactive drafts keyed by game index. */
  drafts?: Record<number, DraftRecord>;
  mapPicks?: string[];
  mapBans?: string[];
  style?: string;
}

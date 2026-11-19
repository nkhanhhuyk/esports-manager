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

export interface Player {
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

export interface TrainingPlan {
  focus: TrainingFocus;
  intensity: number; // 0..100
  scrimsPerWeek: number;
  individual: Record<ID, TrainingFocus>;
  opponentPrep: boolean;
  /** Consecutive weeks on the same team focus (diminishing returns). */
  focusWeeks?: number;
  lastFocus?: TrainingFocus;
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

export interface Organisation {
  id: ID;
  name: string;
  short: string;
  country: CountryCode;
  region: RegionId;
  founded: number;
  colors: [string, string];
  rep: number; // 0..100
  fans: number; // followers
  prestige: number; // accumulated historic prestige
  owner: { name: string; type: 'founder' | 'investor' | 'public' | 'conglomerate' | 'club'; wealth: number; patience: number };
  strategy: OrgStrategy;
  balance: number;
  sponsors: Sponsor[];
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

export type StageType = 'round-robin' | 'double-round-robin' | 'groups' | 'swiss' | 'single-elim' | 'double-elim' | 'page-playoff' | 'br-points';

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
  /** Per-player aggregate inside this competition (MVP voting). */
  pstats: Record<ID, { gp: number; r: number; m: number; teamId: ID }>;
}

export interface SeriesFormat {
  kind: 'bo' | 'sets' | 'br';
  n: number; // best-of n (games/maps/sets) or number of BR games
  setWins?: number; // games needed to win one set (Brawl Stars)
  matchPoint?: number; // BR match point threshold
  label: string;
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
  brPoints?: Record<ID, number>;
  brKills?: Record<ID, number>;
  brWins?: Record<ID, number>;
  detail: boolean;
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
}

export interface GameResult {
  idx: number;
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
  placements?: ID[]; // BR placements (team ids, 1st first)
  brKills?: Record<ID, number>;
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
}

// ---------------------------------------------------------------------------------------------
// Titles (dynamic world state per title)
// ---------------------------------------------------------------------------------------------

export interface Patch {
  id: string;
  version: string;
  day: DayNum;
  season: number;
  major: boolean;
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
  response?: string;
  user: boolean; // involves the user's organisation
  expiresDay: DayNum;
}

export interface NewsItem {
  id: ID;
  day: DayNum;
  kind: 'transfer' | 'result' | 'upset' | 'champion' | 'retirement' | 'prospect' | 'patch' | 'finance' | 'org' | 'award' | 'controversy' | 'record' | 'world';
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
  /** Optional national-team job held alongside the club role. */
  nationalJob?: { titleId: TitleId; country: CountryCode; squad: ID[] } | null;
}

export interface World {
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

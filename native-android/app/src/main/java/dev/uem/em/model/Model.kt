package dev.uem.em.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoPacked

/**
 * The game world. Entities are addressed by integer ids that are their index in the world's lists
 * (no string-keyed maps on hot paths). Everything here is saved; derived caches live elsewhere.
 */
@Serializable
class World(
    val seed: String,
    var name: String,
    var day: Int,
    var season: Int,
    val titles: List<String>,
    val players: MutableList<Player> = ArrayList(),
    val teams: MutableList<Team> = ArrayList(),
    val orgs: MutableList<Org> = ArrayList(),
    val leagues: MutableList<League> = ArrayList(),
    val comps: MutableList<Competition> = ArrayList(),
    val fixtures: MutableList<Fixture> = ArrayList(),
    val news: MutableList<News> = ArrayList(),
    var userOrg: Int = -1,
    var boxPage: Int = 0,
    var version: Int = 1,
    val staff: MutableList<Staff> = ArrayList(),
    val inbox: MutableList<Message> = ArrayList(),
    val offers: MutableList<Offer> = ArrayList(),
    val transfers: MutableList<Transfer> = ArrayList(),
    var board: Board? = null,
    val awards: MutableList<Award> = ArrayList(),
    val patches: MutableList<PatchRec> = ArrayList(),
    var lastMonth: Int = 0,
    var sacked: Boolean = false,
    var managerName: String = "Manager",
    /** Scouting knowledge of players (0-100) beyond your own squad. */
    val scouting: MutableMap<Int, Int> = HashMap(),
    val scoutQueue: MutableList<Int> = ArrayList(),
    val followed: MutableList<Int> = ArrayList(),
    var difficulty: String = "normal",
    var autosave: String = "week",
    /** Rivalry intensity by team pair ("a|b", a < b). */
    val rivalries: MutableMap<String, Double> = HashMap(),
    var delegateBids: Boolean = false,
    var delegateRenewals: Boolean = false,
    /** Players the user has shortlisted. */
    val shortlist: MutableList<Int> = ArrayList(),
    /** Which background matches keep a 2D replay: important, mine, all, off. */
    var replays: String = "important",
    /** Box-score pages already stripped of old replays. */
    var replayPrunedPage: Int = 0,
    /** A series being played or watched live, kept so it can be resumed after the app closes. */
    var live: LiveState? = null,
    /** Spectator career: stop advancing before followed teams play. */
    var stopForFollowed: Boolean = true,
    /** Active players per game the youth intake keeps the world near (set at creation). */
    val poolTarget: MutableMap<String, Int> = HashMap(),
    /** Fearless rules per game and event type ("lol:msi" → "insane"); changed by major patches. */
    val fearlessRules: MutableMap<String, String> = HashMap(),
    /** Spectator career (no club). */
    var spectator: Boolean = false,
    /** Character usage per season: "season|charId" (and "season|map|charId" for shooters) → games, wins, bans. */
    val charStats: MutableMap<String, IntArray> = HashMap(),
    /** Fictional characters and weapons released by patches. */
    val customChars: MutableList<CustomChar> = ArrayList(),
    val customWeapons: MutableList<CustomWeapon> = ArrayList(),
    /** Your job: coach-gm, head-coach, gm, director, owner or commissioner. */
    var role: String = "coach-gm",
    /** A fully fictional world (no real teams or players). */
    var fictional: Boolean = false,
    /** Areas handed to your staff (lineups, training, signings, sales, renewals, staff, scouting, facilities). */
    val delegation: MutableMap<String, Boolean> = HashMap(),
    /** User-created competitions (drafts and published, with their editions). */
    val customComps: MutableList<CustomComp> = ArrayList(),
    /** Folder name of this save's box-score pages (blank: the world name). Each save file has its own. */
    var pagesKey: String = "",
    /** Sponsorship offers waiting for your answer. */
    val sponsorOffers: MutableList<SponsorOffer> = ArrayList(),
    var nextOfferId: Int = 1,
    /** League import limits changed by the commissioner (league id → imports allowed in a lineup, -1 none). */
    val importRules: MutableMap<Int, Int> = HashMap(),
    /** Games whose rules allow coach comms in fights (introduced by a patch). */
    val coachCommsTitles: MutableList<String> = ArrayList(),
    /** Picture links you set in the database editor: "player:12", "team:3", "org:5", "league:2", "comp:9", "char:lol-ahri". */
    val images: MutableMap<String, String> = HashMap(),
    /** Simulation detail: "full" (every match on the 2D engines) or "fast" (unwatched matches use a quick model). */
    var simDetail: String = "balanced",
    /** The season the career started in (no transfers during it; 0 for older saves). */
    var firstSeason: Int = 0,
    /** MOBA items added by patches (new releases). */
    val customItems: MutableList<CustomItem> = ArrayList(),
    /** Patch changes to items: item id → scale of its stats (1 = as released). */
    val itemMods: MutableMap<String, Double> = HashMap(),
    /** Kinds of neutral objectives added by patches (fictional). */
    val customNeutrals: MutableList<CustomNeutral> = ArrayList(),
    /** Patch changes to neutral objective buffs: kind id → scale of its numbers. */
    val neutralMods: MutableMap<String, Double> = HashMap(),
    /** Runes / emblems / arcana / talents added by patches (fictional). */
    val customRunes: MutableList<CustomRune> = ArrayList(),
    /** Patch changes to runes and the like: id → scale of its stats. */
    val runeMods: MutableMap<String, Double> = HashMap(),
    /** Spells added by patches (fictional). */
    val customSpells: MutableList<CustomSpell> = ArrayList(),
    /** Patch changes to spells: id → strength (cooldowns divided by it). */
    val spellMods: MutableMap<String, Double> = HashMap(),
    /** Spells a patch removed from the game. */
    val removedSpells: MutableList<String> = ArrayList(),
    /** Role metas per game (titleId → one option per group, comma-separated, see titles.RoleMeta); none = the game's starting meta. */
    val roleMetas: MutableMap<String, String> = HashMap(),
    /** Invitations to competitions (ranking-based invites; the team accepts or declines before the deadline). */
    val invites: MutableList<Invite> = ArrayList(),
    /** Mentor relationships (veterans or staff teaching less experienced players). */
    val mentorships: MutableList<Mentorship> = ArrayList(),
    /** Development plans and pathway stages of players (one per player at most). */
    val devPlans: MutableList<DevPlan> = ArrayList(),
    /** Pre-match preparation plans (one per team and fixture), locked when the match starts. */
    val preps: MutableList<PrepPlan> = ArrayList(),
    /** Staff system version (0: the first staff model; see mgmt.Staffing.migrate). */
    var staffVersion: Int = 0,
    /** Approaches for staff under contract (compensation offers between clubs). */
    val staffBids: MutableList<StaffBid> = ArrayList(),
    var nextStaffBid: Int = 1,
    /** National squad announcements (players with the selectors' reasons, the coach), kept for two seasons. */
    val squads: MutableList<SquadPick> = ArrayList(),
    /** 1: national eligibility by nationality only (residency no longer grants a second nationality). */
    var natFix: Int = 0,
    /** MOBA rating calibration per game and lineup slot (see sim.StatBook.calibrate). */
    val ratingCal: MutableMap<String, DoubleArray> = HashMap(),
) {
    /** Where this world's box-score pages live. */
    fun pagesName() = pagesKey.ifBlank { name }
    /** Multi-year simulation in progress: staff run every area (not saved). */
    @kotlinx.serialization.Transient
    var autopilot: Boolean = false

    /** Fixture ids by day (rebuilt after loading; not saved). */
    @kotlinx.serialization.Transient
    var schedule: HashMap<Int, MutableList<Int>> = HashMap()

    fun rebuildSchedule() {
        schedule = HashMap()
        for (f in fixtures) if (!f.done) schedule.getOrPut(f.day) { ArrayList() }.add(f.id)
    }

    fun isUserTeam(teamId: Int) = teamId >= 0 && teams[teamId].orgId == userOrg && userOrg >= 0
}

@Serializable
class Player(
    val id: Int,
    var first: String,
    var last: String,
    var handle: String,
    var birth: Int,
    var nat: String,
    var titleId: String,
    var roles: List<String>,
    /** 86 attributes, 1..99. */
    @ProtoPacked var attrs: DoubleArray,
    /** Input proficiency: touch, mouse & keyboard, controller. */
    @ProtoPacked var input: DoubleArray,
    var profTitle: MutableMap<String, Double> = HashMap(),
    var profRole: MutableMap<String, Double> = HashMap(),
    /** Character proficiency in the main title, by character id. */
    var profChar: MutableMap<String, Double> = HashMap(),
    var profMap: MutableMap<String, Double> = HashMap(),
    var profWeapon: MutableMap<String, Double> = HashMap(),
    var teamId: Int = -1,
    var pa: Double = 0.0,
    var ca: Double = 0.0,
    var form: Double = 0.0,
    var morale: Double = 65.0,
    var fatigue: Double = 0.0,
    var injuryUntil: Int = 0,
    var injury: String = "",
    var dev: Dev = Dev(),
    /** ambition, loyalty, temperament, ego, greed, controversy, pressure (1..99). */
    @ProtoPacked var pers: IntArray = IntArray(7) { 50 },
    var salary: Double = 0.0,
    var contractEnd: Int = 0,
    var value: Double = 0.0,
    var rep: Double = 0.0,
    var real: Boolean = false,
    var realSrc: String = "",
    var realEst: List<String> = emptyList(),
    var stats: MutableList<SeasonLine> = ArrayList(),
    var career: MutableList<CareerEntry> = ArrayList(),
    @ProtoPacked var caHistory: MutableList<Float> = ArrayList(),
    var retiredSeason: Int = 0,
    var attrVersion: Int = 0,
    var joinedDay: Int = 0,
    var listed: Boolean = false,
    var unhappy: Double = 0.0,
    var awards: MutableList<String> = ArrayList(),
    /** On loan: the parent team and the season the loan ends. */
    var loanFrom: Int = -1,
    var loanUntil: Int = 0,
    /** Pre-contract: joins this team when the season ends. */
    var preContractTeam: Int = -1,
    var preContractSalary: Double = 0.0,
    /** Solo queue: ladder rating (0 = not placed yet), this season's games / wins, season peak, last week's change. */
    var soloMmr: Double = 0.0,
    var soloGames: Int = 0,
    var soloWins: Int = 0,
    var soloPeak: Double = 0.0,
    var soloDelta: Double = 0.0,
    /** How much the player likes to grind solo queue (-1..1); set when first placed. */
    var soloGrind: Double = 0.0,
    /** Found on the ladder: an amateur who has never played professionally. */
    var amateur: Boolean = false,
    /** Past seasons on the ladder: "season|final rating|games|wins|peak". */
    var soloHistory: MutableList<String> = ArrayList(),
    /** An import waiting for a work visa cannot play until this day. */
    var visaUntil: Int = 0,
    /** Second nationality (from the data, or earned after three straight seasons at clubs of one country). */
    var nat2: String = "",
    /** Country of the player's club and the consecutive seasons spent there (toward a second nationality). */
    var resCountry: String = "",
    var resYears: Int = 0,
    /** University the player is enrolled at (collegiate eligibility), blank if none. */
    var enrolled: String = "",
    /** Specialized circuits the player is registered as eligible for ("gc": VALORANT Game Changers; fictional players only). */
    var circuits: String = "",
    /** What the player wants from a team, weights summing to 100 in [dev.uem.em.mgmt.Ambitions.KEYS] order (empty: not set yet). */
    @ProtoPacked var ambitions: IntArray = IntArray(0),
    /** Day the player asked to leave (0: settled). */
    var wantsOutDay: Int = 0,
    /** Country where three straight seasons made the player a local (not an import) — residency, not nationality. */
    var residency: String = "",
    /** Solo queue kills, deaths and assists this season (totals). */
    var soloK: Double = 0.0,
    var soloD: Double = 0.0,
    var soloA: Double = 0.0,
) {
    val retired get() = retiredSeason != 0
    /** Every nationality (the first is the main one). */
    fun nats(): List<String> = if (nat2.isNotEmpty() && nat2 != nat) listOf(nat, nat2) else listOf(nat)
    val mainRole get() = roles[0]
    /** Rating cache of this player object (not saved). Kept per object so two worlds in one process never share entries. */
    @kotlinx.serialization.Transient @JvmField var ratingCache: java.util.concurrent.ConcurrentHashMap<Long, Any>? = null
    fun name() = if (first.isBlank() && last.isBlank()) handle else "$first \"$handle\" $last".trim()
}

@Serializable
class Dev(var trajectory: String = "steady", var peakAge: Int = 23, var declineAge: Int = 26, var declineRate: Double = 1.0, var growthRate: Double = 1.0)

/** A player's season line in one title for one team (stats in the title's stat order). */
@Serializable
class SeasonLine(val season: Int, val titleId: String, val teamId: Int, var gp: Int = 0, var w: Int = 0, var l: Int = 0, var ratingSum: Double = 0.0, var mvps: Int = 0, @ProtoPacked var s: DoubleArray = DoubleArray(0))

@Serializable
class CareerEntry(val season: Int, val day: Int, val teamId: Int, val type: String)

@Serializable
class Team(
    val id: Int,
    var orgId: Int,
    val titleId: String,
    var name: String,
    var short: String,
    var region: String,
    var leagueId: Int = -1,
    val roster: MutableList<Int> = ArrayList(),
    /** Starting players in the title's lineup order (-1 = empty). */
    var lineup: MutableList<Int> = ArrayList(),
    var style: String = "",
    var aggression: Double = 50.0,
    var chemistry: Double = 60.0,
    var elo: Double = 1500.0,
    var metaAdapt: Double = 70.0,
    var coach: Double = 55.0,
    var real: Boolean = false,
    var realSrc: String = "",
    var active: Boolean = true,
    var lineupSig: Int = 0,
    val history: MutableList<TeamSeason> = ArrayList(),
    val trophies: MutableList<Trophy> = ArrayList(),
    val staffIds: MutableList<Int> = ArrayList(),
    var focus: String = "balanced",
    var intensity: Int = 60,
    var manualLineup: Boolean = false,
    /** Country code for a national team (Nations Cup), empty for clubs. */
    var nationalOf: String = "",
    /** Scrims per week (0-6): sharpness and chemistry, at the cost of fatigue. */
    var scrims: Int = 3,
    /** Where this team's data comes from (real snapshot, historical snapshot, fictional). */
    var dataNote: String = "",
    /** Main team this academy develops players for (-1 = not an academy). */
    var parentId: Int = -1,
    /** Solo queue practice the team asks of its players: 0 light, 1 normal, 2 heavy. */
    var soloFocus: Int = 1,
    /** Recent scrim blocks (newest last). */
    var scrimLog: MutableList<ScrimBlock> = ArrayList(),
    /** Scrim record on this patch by character (MOBAs, Valorant) or map (shooters): key → games, wins. */
    var scrimStats: MutableMap<String, ScrimStat> = HashMap(),
    /** Patch the scrim record belongs to (a new patch halves the old record). */
    var scrimPatch: Int = -1,
    /** "" professional / academy / national; "amateur" (community club), "collegiate" (university team), "gc" (VALORANT Game Changers). */
    var kind: String = "",
    /** A roster being rebuilt ("blown up") until this day: the club sells, releases and signs more than usual. */
    var rebuildUntil: Int = 0,
    /** Why the last rebuild began (for news and the team page). */
    var rebuildNote: String = "",
    /** Characters shown in recent official series (MOBAs; fades every series): opponents' drafts read it. */
    var shown: MutableMap<String, ShownStat> = HashMap(),
    /** National teams: the competition the current squad was selected for (-1: no squad; the team is empty between events). */
    var squadFor: Int = -1,
)

/** One scrim block against a partner: games won and lost, and (for teams you follow) the picks of every game. */
@Serializable
class ScrimBlock(val day: Int, val opp: Int, val won: Int, val lost: Int, val picks: List<String> = emptyList(), val oppPicks: List<String> = emptyList(), val maps: List<String> = emptyList(), val results: String = "")

@Serializable
class ScrimStat(var g: Int = 0, var w: Int = 0)

/** Games and wins with a character in a team's recent official series, fading with every series. */
@Serializable
class ShownStat(var g: Float = 0f, var w: Float = 0f)

@Serializable
class TeamSeason(val season: Int, val leagueId: Int, var position: Int, var w: Int, var l: Int, var elo: Double, @ProtoPacked val roster: IntArray = IntArray(0),
    /** National teams: the competition this squad played (-1 for a club season). */
    val compId: Int = -1)

/**
 * A national squad named for a competition: the players (positions, second-position picks), the
 * selectors' reason for each, the coaching staff, who came in and who dropped out.
 */
@Serializable
class SquadPick(val day: Int, val teamId: Int, val compId: Int, val titleId: String, val players: List<Int>, val roles: List<String>, val notes: List<String>,
    val coaches: List<Int>, val coachNotes: List<String>, val newIn: List<Int>, val dropped: List<Int>, val snubs: List<Int> = emptyList())

@Serializable
class Trophy(val season: Int, val compId: Int, val name: String)

@Serializable
class Org(
    val id: Int,
    var name: String,
    var short: String,
    var country: String,
    var region: String,
    var rep: Double = 40.0,
    var fans: Double = 10000.0,
    var balance: Double = 1_000_000.0,
    var prestige: Double = 0.0,
    val teams: MutableList<Int> = ArrayList(),
    var real: Boolean = false,
    var colors: List<String> = listOf("#3a86ff", "#ffd166"),
    var sponsors: MutableList<Sponsor> = ArrayList(),
    val ledger: MutableList<LedgerLine> = ArrayList(),
    val months: MutableList<MonthFin> = ArrayList(),
    var facilities: IntArray = intArrayOf(3, 3, 3),
    /** Supporters by segment: core, casual, local, international. */
    @ProtoPacked var fanSeg: DoubleArray = DoubleArray(0),
    /** Players' streaming: 0 none, 1 normal, 2 heavy (more money and fans, less practice). */
    var streamPolicy: Int = 1,
    /** Merchandise range: 0 basic, 1 standard, 2 premium, 3 global (costs to run, sells more). */
    var merchLevel: Int = 1,
    /** How the organisation builds teams (see [dev.uem.em.mgmt.Ambitions.STRATEGIES]); blank until set. */
    var strategy: String = "",
    /** The organisation's venue: 0 none, 1 studio & content house, 2 esports arena, 3 stadium (see Business). */
    var venue: Int = 0,
    /** Content creators signed to the organisation. */
    var creators: MutableList<CreatorDeal> = ArrayList(),
    /** Fan events held (the last two years). */
    var fanEvents: MutableList<FanEvent> = ArrayList(),
)

/** A content creator signed to an organisation: audience, monthly fee, until the end of a season. */
@Serializable
class CreatorDeal(val name: String, val platform: String, var followers: Double, val fee: Double, val untilSeason: Int, val titleId: String, val style: String, val signedDay: Int = 0)

/** A fan event an organisation held: what it cost and brought in. */
@Serializable
class FanEvent(val day: Int, val kind: String, val cost: Double, val revenue: Double, val fans: Double, val rep: Double, val note: String = "")

@Serializable
class League(
    val id: Int,
    val titleId: String,
    val region: String,
    val tier: Int,
    var name: String,
    var short: String,
    val teamIds: MutableList<Int> = ArrayList(),
    var upperId: Int = -1,
    var lowerId: Int = -1,
    var real: Boolean = false,
    var realSrc: String = "",
)

enum class CompTier { LEAGUE, CHALLENGER, INTERNATIONAL, WORLDS, CUSTOM }
enum class StageType { RR, DRR, GROUPS, SWISS, SINGLE_ELIM, DOUBLE_ELIM }

@Serializable
class Competition(
    val id: Int,
    var name: String,
    var short: String,
    val titleId: String,
    val season: Int,
    val tier: CompTier,
    val leagueId: Int = -1,
    val split: Int = 0,
    val region: String = "",
    /** How entrants are found: "league:<id>", "top:<compId>:<count>", "circuit:<leagueId>:<count>", ... (see comp.Ecosystem.describe). */
    var entrants: List<String>,
    val stages: MutableList<Stage>,
    var teamIds: MutableList<Int> = ArrayList(),
    var current: Int = 0,
    var status: Int = 0, // 0 pending, 1 active, 2 done
    var startDay: Int = 0,
    var placements: MutableList<Int> = ArrayList(),
    val circuitPoints: List<Int> = emptyList(),
    var prizePool: Double = 0.0,
    val prestige: Int = 50,
    var fearless: Boolean = false,
    var realName: Boolean = false,
    /** Per-player stats in this competition. */
    val pstats: MutableMap<Int, CompStats> = HashMap(),
    var mvp: Int = -1,
    /** Registered players per team (locked when the competition starts) and amendments used. */
    val registrations: MutableMap<Int, Registration> = HashMap(),
    /** Draft rule across a series: none, soft, hard or insane Fearless (blank: from `fearless`). */
    var fearlessMode: String = "",
    /** Best-of of the final (0 = the title's default). */
    var finalBo: Int = 0,
    /** User-created competition this edition belongs to (-1 = official calendar). */
    var customId: Int = -1,
    /** Coach comms in fights: -1 the game's rule, 0 not allowed, 1 allowed. */
    var coachComms: Int = -1,
    /** Tactical timeouts per team per map in shooters (-1: the game's default). */
    var timeouts: Int = -1,
    /** Promotion tournament: the league whose places it decides (-1: not one). */
    var promoLeague: Int = -1,
    /** The competition this edition belongs to across seasons (e.g. "lol:L17:split1", "valorant:champions"). */
    var defKey: String = "",
    /** Ruleset the edition was created with (comp.Rulesets id); kept after the season, never rewritten. */
    var rules: String = "",
    /** Qualification ledger: how every entrant got its place (route, source result, replacements). */
    val entries: MutableList<QualEntry> = ArrayList(),
    /** Circuit of a development event: "amateur", "collegiate", "gc" (blank: professional calendar). */
    var circuit: String = "",
    /** Eligibility rule, evaluated per player when registering and fielding ("amateur", "enrolled;age<=25", "circuit:gc"). */
    var elig: String = "",
) {
    val done get() = status == 2
}

@Serializable
class CompStats(var teamId: Int, var gp: Int = 0, var w: Int = 0, var r: Double = 0.0, var m: Int = 0, @ProtoPacked var v: DoubleArray = DoubleArray(0))

@Serializable
class Stage(
    val name: String,
    val type: StageType,
    var startDay: Int,
    val bestOf: Int,
    val dows: List<Int> = listOf(5, 6),
    val groups: Int = 0,
    val advance: Int = 0,
    val daysBetween: Int = 2,
    val swissWins: Int = 3,
    val swissLosses: Int = 3,
    var teamIds: MutableList<Int> = ArrayList(),
    var standings: MutableList<Standing> = ArrayList(),
    var fixtureIds: MutableList<Int> = ArrayList(),
    var bracket: MutableList<BracketNode> = ArrayList(),
    var swissRound: Int = 0,
    var status: Int = 0,
    var placements: MutableList<Int> = ArrayList(),
)

@Serializable
class Standing(val teamId: Int, var w: Int = 0, var l: Int = 0, var gw: Int = 0, var gl: Int = 0, val group: Int = 0)

/** Bracket node; sources are "s<seed>", "w<node>" (winner of) or "l<node>" (loser of). */
@Serializable
class BracketNode(
    val id: String,
    val round: Int,
    val label: String,
    val a: String,
    val b: String,
    val elimPlace: Int,
    val isFinal: Boolean,
    val bestOf: Int,
    var teamA: Int = -2,
    var teamB: Int = -2,
    var fixtureId: Int = -1,
    var winner: Int = -2,
    var loser: Int = -2,
)

@Serializable
class Fixture(
    val id: Int,
    val compId: Int,
    val stageIdx: Int,
    val titleId: String,
    var day: Int,
    val a: Int,
    val b: Int,
    val bestOf: Int,
    val round: Int = 0,
    val group: Int = 0,
    val nodeId: String = "",
    var done: Boolean = false,
    var winner: Int = -1,
    var scoreA: Int = 0,
    var scoreB: Int = 0,
    var mvp: Int = -1,
    /** Box-score page this fixture's record lives in (-1 = in memory / none). */
    var page: Int = -1,
) {
    val teams get() = intArrayOf(a, b)
}

@Serializable
class News(val day: Int, val title: String, val body: String, val teamIds: List<Int> = emptyList())

/** Box scores of one series. Not part of the world save: kept in weekly box-score pages. */
@Serializable
class MatchRecord(val fixtureId: Int, val day: Int, val games: List<GameRecord>)

@Serializable
class GameRecord(
    val winner: Int,
    val duration: Int,
    val mapId: String,
    /** Which series team played the first (blue / Radiant / attack-first) side. */
    val blue: Int = 0,
    /** Side choice: chooser, 0 side / 1 pick order, 0 coin / 1 previous loser. */
    @ProtoPacked val sel: IntArray = IntArray(0),
    val bans: List<String> = emptyList(),
    val picks: List<String> = emptyList(),
    @ProtoPacked val score: IntArray = IntArray(2),
    val lines: List<Line>,
    /** The game's authoritative event stream and state snapshots (null when not recorded). */
    var replay: Replay? = null,
    /** The draft step by step: "b|team|character" or "p|team|character|player" (team = series team). */
    val draftOrder: List<String> = emptyList(),
    /** Shooters: every round's buys (see sim.ShooterEconomy.Bank.econ). */
    @ProtoPacked val econ: IntArray = IntArray(0),
    /** MOBAs: every neutral objective taken, code * 2 + team (see ui.ObjTally): a kind (drake element...), an objective, a soul. */
    @ProtoPacked val objs: IntArray = IntArray(0),
    /** Why draft steps happened when earlier games shaped them: "step|kind|n|character" (see sim.DraftNotes). */
    val draftNotes: List<String> = emptyList(),
)

/**
 * A recorded game, exactly as the engine played it: events (9 ints each: time, kind, team, a, b,
 * x, m, n, 0; see sim/Replay.kt for the codes) and state snapshots for every player in `pids`
 * order (MOBA: gold, CS, level, dead-until second; shooters: credits, loadout, 0, 0 per round).
 */
@Serializable
class Replay(
    val v: Int = 1,
    @ProtoPacked val ev: IntArray = IntArray(0),
    @ProtoPacked val pids: IntArray = IntArray(0),
    @ProtoPacked val snapT: IntArray = IntArray(0),
    @ProtoPacked val snap: IntArray = IntArray(0),
    /** Snapshot values per player (4 in v1). */
    val per: Int = 4,
    /** Position sample interval in seconds (0 = no positions, v1). */
    val step: Int = 0,
    /** Ints per position sample. */
    val posN: Int = 0,
    @ProtoPacked val pos: IntArray = IntArray(0),
    /** Game second of each position sample (shooters; empty when samples are every `step` seconds). */
    @ProtoPacked val posT: IntArray = IntArray(0),
)

@Serializable
class Line(val playerId: Int, val team: Int, val roleId: String, val charId: String = "", var rating: Double = 1.0, @ProtoPacked val s: DoubleArray, val items: MutableList<String> = ArrayList(),
    /** Runes / emblems / arcana / talents taken into the game (MOBAs; ids in titles.Runes). */
    val runes: MutableList<String> = ArrayList(),
    /** Spells taken into the game (ids in titles.Spells). */
    val spells: MutableList<String> = ArrayList())

/** A spell a patch added (fictional). */
@Serializable
class CustomSpell(val id: String, val titleId: String, val name: String, val cd: Double, val desc: String, val effect: String, val season: Int = 0)

/** A rune, emblem talent or arcana a patch added (fictional). */
@Serializable
class CustomRune(val id: String, val titleId: String, val name: String, val group: String, val slot: String, val desc: String, val stats: Map<String, Double>, val season: Int = 0)

/** A kind of neutral objective a patch added (e.g. a new elemental drake). */
@Serializable
class CustomNeutral(val id: String, val titleId: String, val objId: String, val name: String, val stats: Map<String, Double>, val duration: Int = 0,
    val stacking: Boolean = false, val soulAt: Int = 0, val soulName: String = "", val soul: Map<String, Double> = emptyMap(), val text: String = "", val season: Int = 0)

/** A MOBA item a patch added to a game (fictional, with generated art). */
@Serializable
class CustomItem(val id: String, val titleId: String, val name: String, val cost: Int, val type: String, val stats: Map<String, Double>, val season: Int = 0)


// ------------------------------------------------------------------------------- management (stage 2)

/** Staff member: head coach, assistant coach, analyst, scout, psychologist or performance coach. */
@Serializable
class Staff(
    val id: Int,
    var first: String,
    var last: String,
    var nat: String,
    var birth: Int,
    var role: String,
    /** Ability in the current role (0-100), from the attributes (see mgmt.Staffing.quality). */
    var quality: Double,
    var titleId: String,
    var teamId: Int = -1,
    var orgId: Int = -1,
    var salary: Double = 0.0,
    var contractEnd: Int = 0,
    var retired: Boolean = false,
    /** The name the scene knows (real coaches, former players); empty for others. */
    var handle: String = "",
    /** Positional coaches: the lineup role they coach. */
    var spec: String = "",
    /** Attributes 0-100 in mgmt.Staffing.ATTRS order. */
    @ProtoPacked var attrs: IntArray = IntArray(0),
    /** Reputation 0-100: titles, clubs, years in the job. */
    var rep: Double = 0.0,
    var real: Boolean = false,
    var realSrc: String = "",
    /** A former player (id in World.players), or -1. */
    var playerId: Int = -1,
    /** Clubs and roles, oldest first. */
    val spells: MutableList<StaffSpell> = ArrayList(),
    /** Real honours from before the save: "year|event|team|tier|prestige|won(1/0)|head coach(1/0)". */
    val past: MutableList<String> = ArrayList(),
    var joinedDay: Int = 0,
    /** 0-100: how much a bigger club or a bigger role tempts them; loyalty keeps them. */
    var ambition: Int = 50,
    var loyalty: Int = 50,
    var retiredSeason: Int = 0,
    /** Weights (percent) of what the person wants from a job, like a player's ambitions (Staffing.AMB_KEYS). */
    @ProtoPacked var ambitions: IntArray = IntArray(0),
) {
    fun name() = handle.ifEmpty { "$first $last".trim() }
    fun fullName() = "$first $last".trim()
}

/** A stint at a club: team, role, from day to day (-1 while there), and how it ended. */
@Serializable
class StaffSpell(val teamId: Int, var role: String, val from: Int, var to: Int = -1, var why: String = "")

/** A club's approach for staff under contract elsewhere: compensation to the current club, the role offered. */
@Serializable
class StaffBid(val id: Int, val staffId: Int, val fromTeam: Int, val toTeam: Int, val fee: Double, val role: String, val day: Int, var status: String = "pending", val salary: Double = 0.0, val years: Int = 2)

@Serializable
class Sponsor(val name: String, val perMonth: Double, val untilSeason: Int, val bonus: Double = 0.0, val industry: String = "")

/** A sponsorship offer to your organisation: accept, reject or negotiate (ask for more). */
@Serializable
class SponsorOffer(val id: Int, val orgId: Int, val name: String, var perMonth: Double, var seasons: Int, var bonus: Double, val expires: Int, var round: Int = 0, val industry: String = "", var note: String = "")

@Serializable
class LedgerLine(val day: Int, val kind: String, val amount: Double, val note: String = "")

@Serializable
class MonthFin(val month: Int, val income: Double, val expenses: Double, val balance: Double, val detail: Map<String, Double> = emptyMap())

@Serializable
class Message(val day: Int, val from: String, val subject: String, val body: String, var read: Boolean = false, val offerId: Int = -1)

/** A transfer or contract offer. kind: bid (club fee for a contracted player), contract (personal terms). */
@Serializable
class Offer(
    val id: Int,
    val day: Int,
    val playerId: Int,
    val fromTeam: Int,
    val toTeam: Int,
    val kind: String,
    var fee: Double,
    var salary: Double,
    var years: Int,
    var status: String = "pending", // pending, accepted, rejected, withdrawn, done
    val expiresDay: Int = 0,
)

@Serializable
class Transfer(val day: Int, val season: Int, val playerId: Int, val fromTeam: Int, val toTeam: Int, val fee: Double, val kind: String)

/** The user's board: season objectives and confidence (0-100). */
@Serializable
class Board(var season: Int, var confidence: Double, val objectives: MutableList<BoardObjective>, val notes: MutableList<String> = ArrayList(), var contractEnd: Int = 0)

@Serializable
class BoardObjective(val text: String, val kind: String, val teamId: Int, val target: Int, var status: String = "pending")

@Serializable
class Award(val season: Int, val titleId: String, val name: String, val playerId: Int = -1, val teamId: Int = -1, val compId: Int = -1)

/** A balance patch: minor (random), medium (before First Stand / MSI / Worlds) or major (after Worlds). */
@Serializable
class PatchRec(
    val titleId: String,
    val version: String,
    val day: Int,
    val kind: String,
    val notes: List<String>,
    val charDelta: Map<String, Double> = emptyMap(),
    val styleDelta: Map<String, Double> = emptyMap(),
    val objMods: Map<String, Double> = emptyMap(),
)

@Serializable
class Registration(val players: MutableList<Int> = ArrayList(), var amendments: Int = 0)

/** An unfinished live series: the games already played and the draft history (for Fearless). */
@Serializable
class LiveState(
    val fixtureId: Int,
    val games: MutableList<GameRecord> = ArrayList(),
    val maps: List<String> = emptyList(),
    val fearless: List<String> = emptyList(),
    val spectator: Boolean = false,
)

/** A fictional champion / hero / agent released by a patch (drawn with generated art). */
@Serializable
class CustomChar(val titleId: String, val id: String, val name: String, val cls: String, val classes: List<String>, val lanes: List<String>, val season: Int, val epithet: String = "", @ProtoPacked val kit: IntArray = IntArray(0), val skills: List<String> = emptyList())

/** A fictional weapon released by a patch. */
@Serializable
class CustomWeapon(val titleId: String, val id: String, val name: String, val category: String, val cost: Int, val damage: Int, val fireRate: Int, val magazine: Int, val season: Int)

/** A user-created competition: a draft until published; published editions are normal competitions. */
@Serializable
class CustomComp(
    val id: Int,
    var name: String,
    var short: String,
    var titleId: String,
    var format: String = "groups-se",
    val teams: MutableList<Int> = ArrayList(),
    /** Extra entrant sources: "league:<id>" or "top:<compId>:<count>". */
    val sources: MutableList<String> = ArrayList(),
    var groups: Int = 2,
    var advance: Int = 2,
    var boGroup: Int = 1,
    var boPlayoff: Int = 3,
    var boFinal: Int = 5,
    var startMonth: Int = 9,
    var startDom: Int = 10,
    var daysBetween: Int = 1,
    var prize: Double = 250000.0,
    var entryFee: Double = 0.0,
    var lan: Boolean = true,
    var recurring: Boolean = true,
    var fearless: String = "none",
    var published: Boolean = false,
    var cancelled: Boolean = false,
    val editions: MutableList<Int> = ArrayList(),
    /** Coach comms in MOBA fights: -1 the game's rule, 0 not allowed, 1 allowed. */
    var coachComms: Int = -1,
    /** Tactical timeouts per team per map in shooters (-1: the game's default). */
    var timeouts: Int = -1,
)

/**
 * One entrant's place in a competition edition: the entry route (the competition's rule that gave it),
 * the source competition and placing, how it was earned, and what happened to it. Status: "in",
 * "declined", "withdrawn" (the place passed to [note]'s replacement) or "replacement".
 */
@Serializable
class QualEntry(val teamId: Int, val route: String, val src: Int = -1, val place: Int = -1, val how: String = "", val note: String = "", var status: String = "in", val day: Int = 0)

/** An invitation to a competition: issued from a ranking before the event, accepted or declined by the deadline. */
@Serializable
class Invite(val compId: Int, val teamId: Int, val route: String, val rank: Int, val day: Int, val deadline: Int, var status: String = "pending")

/**
 * A mentor (a player, or staff when [staffMentor]) teaching a mentee toward a goal. Progress comes only
 * from completed sessions: [sessions] held, [missed] when either was unavailable or the mentor was
 * overbooked; [gained] attribute points so far (capped); [relation] 0-100 grows with compatible
 * personalities and sessions, falls with clashes; [log] keeps the evidence.
 */
@Serializable
class Mentorship(val id: Int, val mentorId: Int, val staffMentor: Boolean, val menteeId: Int, val goal: String, val startDay: Int, var reviewDay: Int,
                 var sessions: Int = 0, var missed: Int = 0, var gained: Double = 0.0, var relation: Double = 50.0, var active: Boolean = true,
                 val log: MutableList<String> = ArrayList(), var endedDay: Int = 0)

/** A player's development plan: pathway stage, focus, playtime target per month and the next review. */
@Serializable
class DevPlan(val playerId: Int, var stage: String = "", var focus: String = "", var gamesTarget: Int = 0, var reviewDay: Int = 0, var note: String = "", var trialTeam: Int = -1, var trialUntil: Int = 0)

/**
 * A team's preparation for one fixture: emphasis ("balanced", "counter", "comfort", "map", "rest"),
 * composition identity (MOBA style) or map focus (shooters), who decided it, and — once the match
 * starts — the locked snapshot: [effect] is the share of the plan the staff could deliver that week.
 */
@Serializable
class PrepPlan(val fixtureId: Int, val teamId: Int, var emphasis: String = "balanced", var style: String = "", var map: String = "", var by: String = "user",
               var locked: Boolean = false, var lockedDay: Int = 0, var effect: Double = 1.0, var note: String = "")

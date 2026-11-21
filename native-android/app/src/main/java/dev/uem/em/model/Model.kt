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
) {
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
) {
    val retired get() = retiredSeason != 0
    val mainRole get() = roles[0]
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
)

@Serializable
class TeamSeason(val season: Int, val leagueId: Int, var position: Int, var w: Int, var l: Int, var elo: Double)

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
)

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

enum class CompTier { LEAGUE, CHALLENGER, INTERNATIONAL, WORLDS }
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
    /** How entrants are found: "league:<id>", "top:<compId>:<count>", "circuit:<leagueId>:<count>". */
    val entrants: List<String>,
    val stages: MutableList<Stage>,
    var teamIds: MutableList<Int> = ArrayList(),
    var current: Int = 0,
    var status: Int = 0, // 0 pending, 1 active, 2 done
    var startDay: Int = 0,
    var placements: MutableList<Int> = ArrayList(),
    val circuitPoints: List<Int> = emptyList(),
    val prizePool: Double = 0.0,
    val prestige: Int = 50,
    var fearless: Boolean = false,
    var realName: Boolean = false,
    /** Per-player stats in this competition. */
    val pstats: MutableMap<Int, CompStats> = HashMap(),
    var mvp: Int = -1,
    /** Registered players per team (locked when the competition starts) and amendments used. */
    val registrations: MutableMap<Int, Registration> = HashMap(),
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
)

@Serializable
class Line(val playerId: Int, val team: Int, val roleId: String, val charId: String = "", var rating: Double = 1.0, @ProtoPacked val s: DoubleArray)


// ------------------------------------------------------------------------------- management (stage 2)

/** Staff member: head coach, assistant coach, analyst, scout, psychologist or performance coach. */
@Serializable
class Staff(
    val id: Int,
    var first: String,
    var last: String,
    var nat: String,
    var birth: Int,
    val role: String,
    var quality: Double,
    var titleId: String,
    var teamId: Int = -1,
    var orgId: Int = -1,
    var salary: Double = 0.0,
    var contractEnd: Int = 0,
    var retired: Boolean = false,
) {
    fun name() = "$first $last"
}

@Serializable
class Sponsor(val name: String, val perMonth: Double, val untilSeason: Int)

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

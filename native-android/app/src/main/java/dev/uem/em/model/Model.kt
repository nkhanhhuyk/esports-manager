package dev.uem.em.model

import kotlinx.serialization.Serializable

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
    var attrs: DoubleArray,
    /** Input proficiency: touch, mouse & keyboard, controller. */
    var input: DoubleArray,
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
    var pers: IntArray = IntArray(7) { 50 },
    var salary: Double = 0.0,
    var contractEnd: Int = 0,
    var value: Double = 0.0,
    var rep: Double = 0.0,
    var real: Boolean = false,
    var realSrc: String = "",
    var realEst: List<String> = emptyList(),
    var stats: MutableList<SeasonLine> = ArrayList(),
    var career: MutableList<CareerEntry> = ArrayList(),
    var caHistory: MutableList<Float> = ArrayList(),
    var retiredSeason: Int = 0,
    var attrVersion: Int = 0,
    var joinedDay: Int = 0,
) {
    val retired get() = retiredSeason != 0
    val mainRole get() = roles[0]
    fun name() = if (first.isBlank() && last.isBlank()) handle else "$first \"$handle\" $last".trim()
}

@Serializable
class Dev(var trajectory: String = "steady", var peakAge: Int = 23, var declineAge: Int = 26, var declineRate: Double = 1.0, var growthRate: Double = 1.0)

/** A player's season line in one title for one team (stats in the title's stat order). */
@Serializable
class SeasonLine(val season: Int, val titleId: String, val teamId: Int, var gp: Int = 0, var w: Int = 0, var l: Int = 0, var ratingSum: Double = 0.0, var mvps: Int = 0, var s: DoubleArray = DoubleArray(0))

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
) {
    val done get() = status == 2
}

@Serializable
class CompStats(var teamId: Int, var gp: Int = 0, var w: Int = 0, var r: Double = 0.0, var m: Int = 0, var v: DoubleArray = DoubleArray(0))

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
    val sel: IntArray = IntArray(0),
    val bans: List<String> = emptyList(),
    val picks: List<String> = emptyList(),
    val score: IntArray = IntArray(2),
    val lines: List<Line>,
)

@Serializable
class Line(val playerId: Int, val team: Int, val roleId: String, val charId: String = "", var rating: Double = 1.0, val s: DoubleArray)

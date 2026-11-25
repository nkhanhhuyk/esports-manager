package dev.uem.em.mgmt

import dev.uem.em.data.DataSource
import dev.uem.em.model.Player
import dev.uem.em.model.World
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Real esports history from before the save begins (honours.json): every top-tier League of Legends
 * event since 2011 with its champion, runner-up and their players (Leaguepedia), CS Majors, The
 * International, VCT Masters / Champions and HLTV players of the year (compiled). Players are matched
 * by game and handle, so a real player's past titles count toward legacy, the GOAT ranking and the
 * Hall of Fame — legends who retired before the save are inducted on their real careers.
 */
object HistoryBook {
    class Event(val titleId: String, val name: String, val year: Int, val league: String, val tier: String, val prestige: Double,
                val winner: String, val runnerUp: String, val winners: List<String>, val runners: List<String>, val source: String,
                /** MVP of the final (Leaguepedia, where recorded), else empty. */
                val finalsMvp: String = "")
    class Award(val titleId: String, val year: Int, val name: String, val player: String, val source: String)
    /** One past honour of a player: a title won (or a final reached) or an award. */
    class Past(val year: Int, val titleId: String, val label: String, val team: String, val pts: Double, val trophy: Boolean, val final: Boolean)
    /** A real-history player (key "title|handle"), with the handle as written in the record. */
    class Legend(val titleId: String, val handle: String, val honours: List<Past>) {
        val points get() = honours.sumOf { it.pts }
        val titles get() = honours.count { it.trophy }
        val firstYear get() = honours.minOfOrNull { it.year } ?: 0
        val lastYear get() = honours.maxOfOrNull { it.year } ?: 0
    }

    var events: List<Event> = emptyList(); private set
    var awards: List<Award> = emptyList(); private set
    var coverage = ""; private set
    var sources: Map<String, String> = emptyMap(); private set
    private var byKey: Map<String, Legend> = emptyMap()

    fun norm(h: String) = h.lowercase().filter { it.isLetterOrDigit() }
    private fun key(titleId: String, handle: String) = "$titleId|${norm(handle)}"

    /** Points as the game scores its own honours: world titles most, then internationals, then leagues. A lost final counts 30%. */
    fun points(tier: String, prestige: Double, won: Boolean): Double {
        val win = when (tier) { "worlds" -> 12.0; "international" -> 5.0 + 4.0 * prestige; "league" -> 2.0 + 2.0 * prestige; else -> 1.5 }
        return if (won) win else win * 0.3
    }

    private fun coverageFactor(source: String) = if (source == "compiled") 2.0 else 1.0

    fun init(src: DataSource) {
        val text = runCatching { src.read("honours.json") }.getOrNull() ?: return
        val root = Json.parseToJsonElement(text).jsonObject
        fun s(o: kotlinx.serialization.json.JsonObject, k: String) = o[k]?.jsonPrimitive?.content ?: ""
        events = root["events"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            Event(s(o, "t"), s(o, "n"), o["y"]!!.jsonPrimitive.int, s(o, "lg"), s(o, "tier"), o["pr"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 1.0,
                s(o, "w"), s(o, "r"), o["wp"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(), o["rp"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(), s(o, "src"), s(o, "fm"))
        }
        awards = root["awards"]?.jsonArray?.map { a -> val o = a.jsonObject; Award(s(o, "t"), o["y"]!!.jsonPrimitive.int, s(o, "n"), s(o, "p"), s(o, "src")) } ?: emptyList()
        coverage = root["coverage"]?.jsonPrimitive?.content ?: ""
        sources = root["sources"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        val acc = LinkedHashMap<String, Pair<String, ArrayList<Past>>>()
        fun add(t: String, h: String, p: Past) { acc.getOrPut(key(t, h)) { h to ArrayList() }.second.add(p) }
        for (e in events) {
            // Games where only the biggest events are recorded (CS Majors, TI, VCT internationals) count them double,
            // so a career there compares with a League of Legends career that also lists every league title.
            val k = coverageFactor(e.source)
            for (h in e.winners) add(e.titleId, h, Past(e.year, e.titleId, e.name, e.winner, points(e.tier, e.prestige, true) * k, true, true))
            for (h in e.runners) add(e.titleId, h, Past(e.year, e.titleId, "${e.name} (runner-up)", e.runnerUp, points(e.tier, e.prestige, false) * k, false, true))
        }
        for (a in awards) add(a.titleId, a.player, Past(a.year, a.titleId, "${a.name} ${a.year}", "", 6.0 * coverageFactor(a.source), false, false))
        byKey = acc.entries.associate { (k, v) -> k to Legend(k.substringBefore('|'), v.first, v.second.sortedByDescending { it.year }) }
    }

    /** A player's real honours from before the save (empty for generated players and other games). */
    fun of(w: World, p: Player): List<Past> = if (w.fictional || !p.real) emptyList() else byKey[key(p.titleId, p.handle)]?.honours ?: emptyList()

    fun legend(titleId: String, handle: String): Legend? = byKey[key(titleId, handle)]

    /** Real-history players with no player in this world (retired before the save, or not in the database). */
    fun absent(w: World, titleId: String?): List<Legend> {
        val present = HashSet<String>()
        for (p in w.players) present.add(key(p.titleId, p.handle))
        return byKey.filter { (k, l) -> k !in present && (titleId == null || l.titleId == titleId) }.values.toList()
    }

    /** Champions of the real past (newest first) for a game, or all games. */
    fun champions(titleId: String?): List<Event> = events.filter { titleId == null || it.titleId == titleId }.sortedWith(compareByDescending<Event> { it.year }.thenBy { tierRank(it.tier) }.thenBy { it.name })

    fun tierRank(tier: String) = when (tier) { "worlds" -> 0; "international" -> 1; "league" -> 2; else -> 3 }

    /** A league's earlier names as the records write them (first word of the event name). */
    private val ALIAS = mapOf("LCK" to setOf("LCK", "CHAMPIONS"), "LEC" to setOf("LEC", "EU"), "LCS" to setOf("LCS", "NA", "LTA"), "LCP" to setOf("LCP", "PCS", "LMS"),
        "CBLOL" to setOf("CBLOL", "BRAZILIAN"))

    /** Real editions of a competition before the save: its league's titles, the same international event, the world championship. */
    fun forComp(c: dev.uem.em.model.Competition, leagueShort: String?): List<Event> {
        val evs = events.filter { it.titleId == c.titleId && it.winner.isNotBlank() }
        fun first(e: Event) = e.name.split(' ').first().uppercase()
        return when (c.tier) {
            dev.uem.em.model.CompTier.WORLDS -> evs.filter { it.tier == "worlds" }
            dev.uem.em.model.CompTier.INTERNATIONAL -> {
                val n = c.name.lowercase(); val sh = c.short.lowercase()
                val keys = when {
                    sh.startsWith("msi") || n.contains("mid-season invitational") -> setOf("MSI")
                    sh.startsWith("fst") || n.contains("first stand") -> setOf("FIRST")
                    n.startsWith("esports world cup") -> setOf("ESPORTS")
                    n.contains("masters") -> setOf("MASTERS", "LOCK//IN")
                    else -> emptySet()
                }
                evs.filter { it.tier == "international" && first(it) in keys }
            }
            dev.uem.em.model.CompTier.LEAGUE -> {
                val key = (leagueShort ?: c.short).split(' ').first().uppercase()
                val keys = ALIAS[key] ?: setOf(key)
                evs.filter { it.tier == "league" && first(it) in keys }
            }
            else -> emptyList()
        }.sortedWith(compareByDescending<Event> { it.year }.thenByDescending { it.name })
    }

    /** A club's earlier names in the records (renamed and merged organisations). */
    private val TEAM_ALIAS = mapOf("t1" to listOf("sktelecomt1", "skt1"), "geng" to listOf("samsunggalaxy", "ksv"), "dpluskia" to listOf("damwongaming", "dwgkia", "damwon"),
        "drx" to listOf("kingzonedragonx", "dragonx"), "beijingjdgesports" to listOf("jdgaming"), "kiwoomdrx" to listOf("drx", "kingzonedragonx", "dragonx"))

    /** A real team's titles (true) and lost finals (false) before the save, by its names. */
    fun forTeam(titleId: String, names: List<String>): List<Pair<Event, Boolean>> {
        val keys = names.map { norm(it) }.filter { it.length >= 2 }.toMutableSet()
        for (k in keys.toList()) TEAM_ALIAS[k]?.let { keys.addAll(it) }
        fun match(x: String): Boolean {
            val n = norm(x)
            if (n.isEmpty()) return false
            return keys.any { k -> n == k || (k.length >= 4 && n.length >= 4 && (n.contains(k) || k.contains(n))) }
        }
        return events.filter { it.titleId == titleId }.flatMap { e -> listOfNotNull(if (match(e.winner)) e to true else null, if (e.runnerUp.isNotBlank() && match(e.runnerUp)) e to false else null) }
            .sortedWith(compareByDescending<Pair<Event, Boolean>> { it.first.year }.thenBy { tierRank(it.first.tier) })
    }
}

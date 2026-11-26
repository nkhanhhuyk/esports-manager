package dev.uem.em.ui

import dev.uem.em.core.Dates
import dev.uem.em.model.Fixture
import dev.uem.em.model.Stage
import dev.uem.em.model.StageType
import dev.uem.em.model.World

/**
 * Fixture navigation inside one competition stage (the stages themselves stay separate: play-ins, league
 * stage, groups, Swiss, playoffs). League stages by matchweek, groups by group and matchday, Swiss stages
 * by the record both teams had going into the match (0-0, 1-0, 0-1, ...), brackets by round. The buckets
 * come from the stored fixtures (Swiss records are rebuilt from the earlier rounds' results, so a finished
 * match never moves); nothing is simulated or changed.
 */
object FixtureFilters {
    class Bucket(val key: String, val label: String, val fixtures: List<Fixture>, val note: String = "")

    /** The user's choice per competition stage and filter, kept while the app is open (back navigation keeps it). */
    val chosen = HashMap<String, String>()

    private fun monday(d: Int) = d - (Dates.dow(d) + 6) % 7

    /** League stage: Matchweek 1, 2, … (calendar weeks from the stage's first match day). */
    fun matchweeks(fx: List<Fixture>): List<Bucket> {
        if (fx.isEmpty()) return emptyList()
        val first = monday(fx.minOf { it.day })
        return fx.groupBy { (monday(it.day) - first) / 7 + 1 }.toSortedMap().map { (wk, list) -> Bucket("mw$wk", "Matchweek $wk", list.sortedBy { it.day }) }
    }

    /** Group stage: groups (A, B, …), then matchdays within a group. */
    fun groups(fx: List<Fixture>): List<Bucket> = fx.groupBy { it.group }.toSortedMap().map { (g, list) -> Bucket("g$g", "Group ${'A' + g}", list.sortedBy { it.day }) }
    fun matchdays(fx: List<Fixture>): List<Bucket> = fx.groupBy { it.round }.toSortedMap().map { (r, list) -> Bucket("md$r", "Matchday ${r + 1}", list.sortedBy { it.day }) }

    /** Brackets: the actual rounds (Quarterfinal, Upper Final, Lower Round 2, Grand Final…), in bracket order. */
    fun rounds(w: World, st: Stage, fx: List<Fixture>): List<Bucket> {
        val label = st.bracket.associate { it.id to it.label }
        val order = st.bracket.sortedBy { it.round }.map { it.label }.distinct()
        val by = fx.groupBy { label[it.nodeId] ?: "Other" }
        return (order + (by.keys - order.toSet())).filter { it in by }.map { l -> Bucket("r:$l", l, by[l]!!.sortedBy { it.day }) }
    }

    /** Each Swiss fixture's records going in (wins-losses of both teams), rebuilt from the earlier rounds. */
    fun swissRecords(w: World, st: Stage): Map<Int, Pair<IntArray, IntArray>> {
        val rec = HashMap<Int, IntArray>()
        val out = HashMap<Int, Pair<IntArray, IntArray>>()
        for ((_, list) in st.fixtureIds.map { w.fixtures[it] }.groupBy { it.round }.toSortedMap()) {
            for (f in list) out[f.id] = (rec[f.a]?.copyOf() ?: IntArray(2)) to (rec[f.b]?.copyOf() ?: IntArray(2))
            for (f in list) if (f.done && f.winner >= 0) {
                val lose = if (f.winner == f.a) f.b else f.a
                rec.getOrPut(f.winner) { IntArray(2) }[0]++
                rec.getOrPut(lose) { IntArray(2) }[1]++
            }
        }
        return out
    }

    private val SWISS_ORDER = listOf("0-0", "1-0", "0-1", "1-1", "0-2", "2-0", "2-1", "1-2", "2-2")

    /**
     * Swiss stage: "0-0 matches", "1-0 matches", … — every record the format can have (from the stage's own
     * win/loss limits; groups not reached yet are shown as upcoming), and cross-record pairings apart.
     */
    fun swiss(w: World, st: Stage, fx: List<Fixture>): List<Bucket> {
        val recs = swissRecords(w, st)
        fun key(r: IntArray) = "${r[0]}-${r[1]}"
        val possible = ArrayList<String>()
        for (n in 0 until st.swissWins + st.swissLosses - 1) for (wn in n downTo 0) { val l = n - wn; if (wn < st.swissWins && l < st.swissLosses) possible.add("$wn-$l") }
        val sorted = possible.sortedWith(compareBy<String> { SWISS_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }.thenBy { it.split('-').sumOf { p -> p.toInt() } })
        val out = ArrayList<Bucket>()
        for (k in sorted) {
            val list = fx.filter { f -> recs[f.id]?.let { (a, b) -> key(a) == k && key(b) == k } == true }.sortedBy { it.day }
            val (wn, l) = k.split('-').map { it.toInt() }
            val stakes = listOfNotNull(if (wn == st.swissWins - 1) "the winners advance" else null, if (l == st.swissLosses - 1) "the losers are out" else null).joinToString(", ")
            out.add(Bucket("s$k", "$k matches", list, stakes))
        }
        val cross = fx.filter { f -> recs[f.id]?.let { (a, b) -> key(a) != key(b) } == true }.sortedBy { it.day }
        if (cross.isNotEmpty()) out.add(Bucket("sx", "Cross-record pairings", cross, "teams on different records (the pairing rules ran out of same-record opponents)"))
        return out
    }

    /** "1-0 vs 1-0" for a Swiss fixture. */
    fun recordText(recs: Map<Int, Pair<IntArray, IntArray>>, f: Fixture): String = recs[f.id]?.let { (a, b) -> "${a[0]}-${a[1]} vs ${b[0]}-${b[1]}" } ?: ""

    /** The bucket to open by default: the one with the next match, else the last played. */
    fun defaultIndex(buckets: List<Bucket>): Int {
        val next = buckets.indexOfFirst { b -> b.fixtures.any { !it.done } }
        return if (next >= 0) next else buckets.indexOfLast { it.fixtures.isNotEmpty() }.coerceAtLeast(0)
    }

    /** "6 matches · 9–11 Jan · 4 played, 2 to come". */
    fun summary(b: Bucket): String {
        if (b.fixtures.isEmpty()) return "No matches yet — this group comes later in the stage."
        val d0 = b.fixtures.minOf { it.day }; val d1 = b.fixtures.maxOf { it.day }
        val played = b.fixtures.count { it.done }
        return "${b.fixtures.size} match${if (b.fixtures.size == 1) "" else "es"} · ${Dates.short(d0)}${if (d1 != d0) " – ${Dates.short(d1)}" else ""} · " +
            (if (played == b.fixtures.size) "all played" else if (played == 0) "to come" else "$played played, ${b.fixtures.size - played} to come") + (if (b.note.isNotBlank()) " · ${b.note}" else "")
    }

    /** Which filter a stage uses. */
    fun kind(st: Stage): String = when (st.type) {
        StageType.RR, StageType.DRR -> "matchweek"
        StageType.GROUPS -> "group"
        StageType.SWISS -> "swiss"
        StageType.SINGLE_ELIM, StageType.DOUBLE_ELIM -> "round"
    }
}

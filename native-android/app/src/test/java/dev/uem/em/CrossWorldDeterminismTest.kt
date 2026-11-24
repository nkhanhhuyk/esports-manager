package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.World
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Determinism across worlds in one process (UEM_DIVERGE=<days>): three same-seed worlds, then two single-threaded ones, must not differ; prints the first day and what differs (found the draft preference cache leak). */
class CrossWorldDeterminismTest {
    private var w0: World? = null
    private fun parts(w: World): List<Pair<String, Long>> {
        var fx = 17L
        for (f in w.fixtures) if (f.done) fx = fx * 31 + f.id * 7 + f.winner * 3 + f.scoreA * 11 + f.scoreB
        var pa = 17L
        for (p in w.players) { pa = pa * 31 + p.id; for (a in p.attrs) pa = pa * 31 + (a * 1000).toLong(); pa = pa * 31 + (p.fatigue * 1000).toLong() + p.teamId }
        var tm = 17L
        for (t in w.teams) { tm = tm * 31 + t.roster.hashCode() + t.lineup.hashCode() * 3 + (t.elo * 100).toLong() }
        var cm = 17L
        for (c in w.comps) cm = cm * 31 + c.teamIds.hashCode() + c.entries.size
        return listOf("fixtures" to fx, "players" to pa, "teams" to tm, "comps" to cm, "mentor" to w.mentorships.size.toLong(), "nplayers" to w.players.size.toLong(), "nteams" to w.teams.size.toLong())
    }

    @Test
    fun firstDivergence() {
        assumeTrue(System.getenv("UEM_DIVERGE") != null)
        val detail = ArrayList<List<String>>()
        val games = ArrayList<Map<Int, String>>()
        fun trace(): List<List<Pair<String, Long>>> {
            TestData.init(); BoxStore.clear()
            val w = WorldGen.create(TestData.source, "same", "Q", Dates.day(2026, 11, 21))
            w0 = w
            dev.uem.em.titles.Catalogue.applyCustom(w)
            val out = ArrayList<List<Pair<String, Long>>>()
            out.add(parts(w))
            val stop = System.getenv("UEM_DIVERGE").toIntOrNull() ?: 140
            val at = System.getenv("UEM_DETAIL")?.toIntOrNull() ?: stop
            repeat(stop) { i -> Advance.run(w, 1); out.add(parts(w)); if (i + 1 == at) { detail.add(snap(w)); games.add(w.fixtures.filter { it.done }.associate { f -> f.id to (dev.uem.em.game.BoxStore.get(w, f.id)?.games?.joinToString(";") { g -> "${g.duration}/${g.winner}/${g.lines.joinToString(",") { "%.3f".format(it.rating) }}" } ?: "?") }) } }
            return out
        }
        val a = trace(); val b = trace(); val c = trace()
        val single = System.getenv("UEM_SINGLE") != null
        if (single) Advance.speedMode = "single"
        val d1 = trace(); val d2 = trace()
        Advance.speedMode = "balanced"
        println("single-thread: d1-d2 first divergence after day ${d1.indices.firstOrNull { d1[it] != d2[it] }}")
        org.junit.Assert.assertTrue("same-seed worlds differ", a == b && b == c && d1 == d2)
        for ((i, j) in listOf(0 to 1, 1 to 2, 0 to 2)) {
            val diff = games[i].keys.filter { games[i][it] != games[j][it] }.sorted()
            println("games differing ($i vs $j): ${diff.size}" + diff.take(4).joinToString("") { id -> "\n  fixture $id ${w0!!.fixtures[id].titleId} ${w0!!.comps[w0!!.fixtures[id].compId].name} day ${w0!!.fixtures[id].day}\n   ${games[i][id]}\n   ${games[j][id]}" })
        }
        for ((name, x, y) in listOf(Triple("a-b", a, b), Triple("b-c", b, c), Triple("a-c", a, c))) {
            val d = x.indices.firstOrNull { x[it] != y[it] }
            println("$name first divergence after day $d: " + (d?.let { x[it].zip(y[it]).filter { p -> p.first != p.second }.joinToString { p -> p.first.first } } ?: "none"))
        }
        for ((i, j) in listOf(0 to 1, 1 to 2)) {
            val diffs = detail[i].zip(detail[j]).filter { it.first != it.second }
            println("players differing at the detail day ($i vs $j): ${diffs.size}")
            for ((x, y) in diffs.take(6)) println("A: $x\nB: $y")
        }
    }

    private fun snap(w: World) = w.players.map { p -> "${p.id} ${p.handle} team ${p.teamId} fat %.3f ca %.3f attrs ${p.attrs.joinToString(",") { "%.3f".format(it) }} ment ${w.mentorships.filter { it.menteeId == p.id || it.mentorId == p.id }.joinToString { "${it.mentorId}>${it.menteeId}:${it.sessions}/${it.missed}:%.3f".format(it.gained) }}".format(p.fatigue, p.ca) }
}

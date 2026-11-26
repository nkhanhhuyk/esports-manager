package dev.uem.em

import dev.uem.em.comp.Season
import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.persist.SaveStore
import dev.uem.em.model.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Long seeded run of the four MOBA ecosystems (UEM_LONG=1, UEM_SEASONS=20 by default): every season's events
 * finish, no duplicate entrants, one league per club, nobody plays twice on a day, prestige stays bounded,
 * reforms stay rare (and only at major patches), and the save survives a reload mid-season. Prints measured limits.
 */
class LongEcosystemTest {
    @Test
    fun manySeasons() {
        Assume.assumeTrue("long run: set UEM_LONG=1", System.getenv("UEM_LONG") != null)
        val seasons = System.getenv("UEM_SEASONS")?.toIntOrNull() ?: 20
        TestData.init()
        val dir = File("build/long-saves").apply { deleteRecursively(); mkdirs() }
        val store = SaveStore(dir)
        BoxStore.clear(); BoxStore.store = store.pages()
        var w: World = WorldGen.create(TestData.source, "long-eco", "Long", Dates.day(2026, 11, 21), titles = listOf("lol", "mlbb", "hok", "wildrift"))
        w.simDetail = "fast"
        val start = w.season
        val out = StringBuilder()
        var reloaded = false
        val t0 = System.currentTimeMillis()
        var maxPrestige = 0.0; var minPrestige = 100.0
        while (w.season < start + seasons) {
            val s = w.season
            while (w.season == s) {
                Advance.run(w, 30)
                // A reload in the middle of a season (mid-stage), then carry on with the loaded world.
                if (!reloaded && w.season == start + seasons / 2 && Dates.month(w.day) == 6) {
                    store.save("slot1", w)
                    val back = store.load("slot1")
                    assertEquals(w.day, back.day); assertEquals(w.comps.size, back.comps.size); assertEquals(w.formats.keys, back.formats.keys)
                    w = back; reloaded = true
                    out.appendLine("reloaded at ${Dates.format(w.day)}: save ${File(dir, "slot1.uem").length() / 1024} KB")
                }
            }
            // The season that just ended.
            val left = w.comps.filter { it.season == s && it.status != 2 && it.circuit.isBlank() && it.customId < 0 }
            assertTrue("season $s unfinished: ${left.take(5).map { it.name }}", left.isEmpty())
            for (c in w.comps.filter { it.season == s }) assertEquals("duplicate entrants in ${c.name}", c.teamIds.size, c.teamIds.toSet().size)
            val inLeagues = HashMap<Int, Int>()
            for (l in w.leagues) for (tid in l.teamIds) inLeagues.merge(tid, 1, Int::plus)
            assertTrue("a club in two leagues after $s", inLeagues.values.all { it == 1 })
            val byDay = HashMap<Long, Int>()
            for (f in w.fixtures) if (f.done && w.comps[f.compId].season == s) for (tm in intArrayOf(f.a, f.b)) byDay.merge(tm.toLong() * 100000 + f.day, 1, Int::plus)
            val clashes = byDay.filter { it.value > 1 }.keys.take(8).map { key ->
                val tid = (key / 100000).toInt(); val day = (key % 100000).toInt()
                "${w.teams[tid].name} ${Dates.format(day)}: " + w.fixtures.filter { it.done && it.day == day && (it.a == tid || it.b == tid) }.joinToString { "${w.comps[it.compId].name} [${w.comps[it.compId].stages[it.stageIdx].name}]" }
            }
            assertEquals("double bookings in $s: $clashes; reforms ${w.formats.values.flatMap { tf -> tf.log.filter { it.season >= s - 2 } }.map { it.id + " " + it.text }}", 0, byDay.count { it.value > 1 })
            for (v in w.prestige.values) { maxPrestige = maxOf(maxPrestige, v); minPrestige = minOf(minPrestige, v) }
            val reforms = w.formats.values.flatMap { tf -> tf.log.filter { it.season == s } }
            out.appendLine("season $s: ${w.comps.count { it.season == s }} events, ${reforms.size} reform(s) ${reforms.joinToString { it.id + " " + it.kind }}, " +
                "leagues ${w.leagues.count { it.tier == 1 && it.until == 0 }} top, ${(System.currentTimeMillis() - t0) / 1000}s")
        }
        // Reforms only at major patches, at most once every two seasons per game.
        for ((id, tf) in w.formats) {
            val seasonsWith = tf.log.map { it.season }.distinct().sorted()
            out.appendLine("$id: ${tf.log.size} changes in ${seasonsWith.size} reviews: $seasonsWith")
            for (i in 1 until seasonsWith.size) assertTrue("$id reviews ${seasonsWith[i - 1]} and ${seasonsWith[i]} too close", seasonsWith[i] - seasonsWith[i - 1] >= 2)
            for (c in tf.log) assertTrue("reformed at a major patch", w.patches.any { p -> p.titleId == id && p.kind == "major" && p.day == c.day })
        }
        assertTrue("prestige bounded: $minPrestige..$maxPrestige", maxPrestige <= 95.0 && minPrestige >= 5.0)
        val richest = w.orgs.maxOf { it.balance }
        out.appendLine("prestige $minPrestige..$maxPrestige; richest organisation ${"%.1f".format(richest / 1e6)}M; players ${w.players.size}, teams ${w.teams.size}, comps ${w.comps.size}, fixtures ${w.fixtures.size}, solo weeks ${w.soloWeeks.size}")
        store.save("slot1", w)
        out.appendLine("final save ${File(dir, "slot1.uem").length() / 1024} KB")
        println(out); File("build/long-eco.txt").writeText(out.toString())
        BoxStore.store = null
    }
}

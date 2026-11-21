package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertTrue
import org.junit.Test

class SimTest {
    @Test
    fun simulates120Days() {
        TestData.init()
        BoxStore.clear()
        val w = WorldGen.create(TestData.source, "sim", "Sim", Dates.day(2026, 11, 21))
        val t0 = System.currentTimeMillis()
        Advance.run(w, 120)
        val ms = System.currentTimeMillis() - t0
        val done = w.fixtures.filter { it.done }
        println("120 days in $ms ms (${Runtime.getRuntime().availableProcessors()} cores): series ${done.size}, date ${Dates.format(w.day)}")
        for (id in w.titles) {
            val t = Catalogue.get(id)
            val recs = done.filter { it.titleId == id }.mapNotNull { BoxStore.get(w, it.id) }
            val games = recs.flatMap { it.games }.filter { it.lines.isNotEmpty() }
            if (games.isEmpty()) continue
            val k = t.statIndex["k"]!!
            val kills = games.map { g -> g.lines.sumOf { it.s[k] } }
            val maxPlayerDeaths = games.maxOf { g -> g.lines.maxOf { it.s[t.statIndex["d"]!!] } }
            val dur = games.map { it.duration / 60.0 }.average()
            println("  $id games ${games.size} kills/game avg ${"%.1f".format(kills.average())} max ${kills.max().toInt()} max deaths by one player ${maxPlayerDeaths.toInt()} avg length ${"%.1f".format(dur)} min; score sample ${games.take(3).joinToString { it.score.joinToString("-") }}")
        }
        val champs = w.comps.filter { it.done }.take(6).joinToString { "${it.short}: ${w.teams[it.placements[0]].name}" }
        println("  champions: $champs")
        assertTrue(done.size > 1000)
    }
}

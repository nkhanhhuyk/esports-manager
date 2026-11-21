package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.CompTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonTest {
    @Test
    fun fullSeasonAndRollover() {
        TestData.init()
        BoxStore.clear()
        val w = WorldGen.create(TestData.source, "season", "Season", Dates.day(2026, 11, 21))
        val s0 = w.season
        val t0 = System.currentTimeMillis()
        Advance.run(w, 400)
        val ms = System.currentTimeMillis() - t0
        val prev = w.comps.filter { it.season == s0 }
        println("400 days in $ms ms: season $s0 comps ${prev.size}, done ${prev.count { it.done }}, series ${w.fixtures.count { it.done }}, now ${Dates.format(w.day)} season ${w.season}")
        for (c in prev.filter { it.tier == CompTier.WORLDS || it.tier == CompTier.INTERNATIONAL }) println("  ${c.name}: ${c.placements.take(2).joinToString(" > ") { w.teams[it].name }} (${c.teamIds.size} teams, mvp ${c.mvp.takeIf { it >= 0 }?.let { w.players[it].handle }})")
        val stuck = prev.filter { !it.done }
        for (c in stuck) println("  NOT DONE: ${c.name} status ${c.status} stage ${c.current} startDay ${Dates.format(c.startDay)} entrants ${c.entrants}")
        assertEquals(s0 + 1, w.season)
        assertTrue(stuck.isEmpty())
        assertTrue(w.comps.any { it.season == s0 + 1 })
    }
}

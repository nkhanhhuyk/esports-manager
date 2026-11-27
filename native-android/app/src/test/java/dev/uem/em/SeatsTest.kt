package dev.uem.em

import dev.uem.em.comp.Invariants
import dev.uem.em.core.Dates
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Circuits
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lower leagues refill their seats from their region's amateur circuit; a promoted club signs no retired player. */
class SeatsTest {
    @Test
    fun refillFromTheAmateurCircuit() {
        TestData.init(); BoxStore.clear(); BoxStore.store = null
        val w = WorldGen.create(TestData.source, "seats", "Seats", Dates.day(2026, 11, 21), titles = listOf("lol"))
        Circuits.ensure(w) // the amateur circuits form on the first day
        val lowers = w.leagues.filter { it.titleId == "lol" && it.tier == 2 && !it.academy && it.until == 0 }
        // Every amateur club has a player who retired today (as at the season's end).
        val size = dev.uem.em.titles.Catalogue.get("lol").teamSize
        for (t in w.teams.filter { it.titleId == "lol" && it.kind == Circuits.AMATEUR && it.active && it.roster.size > size }) w.players[t.roster[0]].retiredSeason = w.season
        val want = lowers.associate { it.id to it.teamIds.size + 1 }
        Circuits.rebalance(w, want)
        val filled = lowers.count { it.teamIds.size == want[it.id] }
        assertTrue("some lower leagues refilled ($filled of ${lowers.size})", filled > 0)
        for (l in lowers) for (tid in l.teamIds) assertTrue("retired player signed by ${w.teams[tid].name}", w.teams[tid].roster.none { w.players[it].retired })
        val r = Invariants.world(w)
        assertTrue(r.violations.take(5).joinToString(), r.violations.isEmpty())
    }
}

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

class EmptyStageTest {
    @Test
    fun aStageWithNothingToPlayCompletes() {
        TestData.init(); BoxStore.clear(); BoxStore.store = null
        val w = WorldGen.create(TestData.source, "empty-stage", "Empty", Dates.day(2026, 11, 21), titles = listOf("lol"))
        // Two clubs in a two-group stage (one each, no series), then a final four: the event must still finish.
        val two = w.teams.filter { it.titleId == "lol" && it.active && it.leagueId >= 0 && it.parentId < 0 }.take(2).map { it.id }
        val t = dev.uem.em.titles.Catalogue.get("lol")
        val stages = listOf(dev.uem.em.model.Stage("Groups", dev.uem.em.model.StageType.GROUPS, w.day + 2, 3, listOf(0, 1, 2, 3, 4, 5, 6), groups = 2, advance = 2, daysBetween = 1),
            dev.uem.em.model.Stage("Final Four", dev.uem.em.model.StageType.SINGLE_ELIM, w.day + 9, 3, listOf(0, 1, 2, 3, 4, 5, 6), daysBetween = 1))
        val c = dev.uem.em.comp.Season.mk(w, t, w.season, "Tiny Cup", "Tiny", dev.uem.em.model.CompTier.CHALLENGER, null, listOf("ids:${two.joinToString(",")}"), stages, 1000.0, 5, emptyList(), key = "lol:tiny")
        c.startDay = w.day + 1; w.rebuildSchedule()
        dev.uem.em.game.Advance.run(w, 20)
        assertTrue("finished: status ${c.status}, stages ${c.stages.map { it.status }}", c.done)
        assertTrue(c.placements.size == 2)
        val r = Invariants.season(w, w.season)
        assertTrue(r.violations.filter { it.contains("Tiny") }.joinToString(), r.violations.none { it.contains("Tiny") })
    }
}

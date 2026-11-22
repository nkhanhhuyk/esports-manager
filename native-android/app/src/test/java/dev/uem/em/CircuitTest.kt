package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Circuits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature E acceptance: development clubs of unsigned players; one person ID from the ladder through
 * amateur play to a professional contract; event eligibility per event; no player in two series on one
 * day; the Amateur Championship winner moves up without a duplicate entry; seasons archived without
 * duplicate generation.
 */
class CircuitTest {
    @Test
    fun circuitsRunAndConnect() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "circuits", "C", Dates.day(2026, 11, 21))
        Advance.run(w, 2)
        val dev0 = w.teams.filter { it.kind.isNotEmpty() }
        assertTrue("development teams exist", dev0.size > 50)
        Circuits.ensure(w)
        assertEquals("ensure does not create teams twice", dev0.size, w.teams.count { it.kind.isNotEmpty() })
        // Unsigned: development roster places are not contracts; nobody is on two development rosters.
        val placed = dev0.flatMap { it.roster }
        assertEquals(placed.size, placed.toSet().size)
        assertTrue(placed.all { w.players[it].teamId < 0 })
        assertTrue(dev0.filter { it.kind == "collegiate" }.flatMap { it.roster }.all { w.players[it].enrolled.isNotBlank() })
        assertTrue(dev0.filter { it.kind == "gc" }.flatMap { it.roster }.all { "gc" in w.players[it].circuits.split(',') && !w.players[it].real })
        // One person: an amateur signs for a professional team and leaves the club the next day (same Player id, career kept).
        val club = dev0.first { it.kind == "amateur" && it.titleId == "lol" }
        val amateur = w.players[club.roster.first()]
        val pro = w.teams.first { it.titleId == "lol" && it.leagueId >= 0 && it.kind.isEmpty() && it.parentId < 0 }
        amateur.teamId = pro.id; pro.roster.add(amateur.id); amateur.career.add(dev.uem.em.model.CareerEntry(w.season, w.day, pro.id, "signed"))
        Advance.run(w, 1)
        assertTrue(amateur.id !in club.roster)
        assertTrue(amateur.career.any { it.teamId == club.id } && amateur.career.any { it.teamId == pro.id })
        // A season of circuits.
        val start = w.season
        while (w.season == start) Advance.run(w, 30)
        val events = w.comps.filter { it.season == start && it.circuit.isNotBlank() }
        assertTrue(events.size > 40)
        assertTrue("circuit events finished: ${events.filter { !it.done }.take(5).map { it.name }}", events.all { it.done })
        // Eligibility was applied when rosters were registered.
        for (c in events) for ((tid, reg) in c.registrations) for (pid in reg.players) assertTrue("${w.players[pid].handle} eligible for ${c.name}", Circuits.eligible(w, c, w.players[pid]) == null || c.circuit == "amateur")
        // Promotions: a champion moved up into an independent lower league, as one entry.
        val promoted = events.flatMap { c -> c.entries.filter { it.route.startsWith("promotion:") } }
        val sb = StringBuilder("events ${events.size}, promotions ${promoted.size}\n")
        for (e in promoted) {
            val team = w.teams[e.teamId]
            val league = w.leagues[e.route.removePrefix("promotion:").toInt()]
            sb.appendLine("${team.name} → ${league.short}")
            assertTrue(team.kind.isEmpty() && team.leagueId == league.id)
            assertEquals(1, league.teamIds.count { it == team.id })
            assertTrue(team.roster.all { w.players[it].teamId == team.id || w.players[it].retired })
        }
        assertTrue("some region promoted its amateur champion", promoted.isNotEmpty())
        // No player in two series on the same day (recent box scores).
        val byDay = HashMap<Int, HashSet<Int>>()
        var clashes = 0
        for (f in w.fixtures) {
            if (!f.done || f.day < w.day - 60) continue
            val rec = BoxStore.get(w, f.id) ?: continue
            val seen = byDay.getOrPut(f.day) { HashSet() }
            for (pid in rec.games.flatMap { g -> g.lines.map { it.playerId } }.toSet()) if (!seen.add(pid)) clashes++
        }
        sb.appendLine("players in two series on one day (last 60 days): $clashes")
        // The archive keeps last season's circuit events; this season has its own, once.
        Advance.run(w, 20)
        assertEquals(events.size, w.comps.count { it.season == start && it.circuit.isNotBlank() })
        val keys = w.comps.filter { it.season == w.season && it.circuit.isNotBlank() }.map { it.defKey }
        assertEquals("no duplicate events this season", keys.size, keys.toSet().size)
        println(sb); java.io.File("build/circuits.txt").writeText(sb.toString())
        assertEquals(0, clashes)
    }
}

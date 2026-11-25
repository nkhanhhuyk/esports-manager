package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.gen.WorldGen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Teams added at the player's request (special.json): Los Ratones in the LEC with their 2025-26 line-up. */
class SpecialTeamsTest {
    @Test
    fun losRatonesPlayInTheLec() {
        TestData.init()
        val w = WorldGen.create(TestData.source, "lr", "LR", Dates.day(2026, 11, 21), titles = listOf("lol"))
        val lec = w.leagues.first { it.titleId == "lol" && it.short == "LEC" }
        val lr = w.teams.firstOrNull { it.name == "Los Ratones" }
        assertTrue("Los Ratones missing", lr != null)
        lr!!
        assertTrue("Los Ratones not in the LEC", lr.id in lec.teamIds && lr.leagueId == lec.id && lr.active)
        assertEquals("LEC teams", 11, lec.teamIds.size)
        // Only one Los Ratones (no dormant copy from the history pack).
        assertEquals(1, w.teams.count { it.titleId == "lol" && it.name == "Los Ratones" })
        val players = lr.roster.map { w.players[it] }
        val byRole = players.associate { it.roles[0] to it.handle }
        assertEquals(mapOf("top" to "Baus", "jungle" to "Velja", "mid" to "Nemesis", "bot" to "Crownie", "support" to "Rekkles"), byRole)
        assertTrue(players.all { it.real })
        assertEquals(setOf("SE", "RS", "SI"), players.map { it.nat }.toSet())
        // Each of them exists once (not also as a free agent).
        for (h in byRole.values) assertEquals(h, 1, w.players.count { it.titleId == "lol" && it.handle == h })
        val staff = w.staff.filter { it.teamId == lr.id }
        assertTrue("Caedrel coaches: ${staff.map { it.handle + " " + it.role }}", staff.any { it.handle == "Caedrel" && it.role == "head-coach" })
        assertTrue("Shaves: " + w.staff.filter { it.handle == "Shaves" }.joinToString { "${it.teamId} ${it.role} ${it.spells.size}" }, staff.any { it.handle == "Shaves" })
        assertTrue(lr.dataNote.startsWith("Special"))
        // The LEC season plays with eleven teams (a bye each round): into the first split, Los Ratones have played.
        dev.uem.em.game.Advance.run(w, 70)
        val played = w.fixtures.filter { it.done && (it.a == lr.id || it.b == lr.id) && w.comps.getOrNull(it.compId)?.leagueId == lec.id }
        assertTrue("Los Ratones played no LEC match", played.isNotEmpty())
        println("LR LEC matches: ${played.size}")
        println("LEC: " + lec.teamIds.joinToString { w.teams[it].short } + " · LR elo ${lr.elo.toInt()} · staff " + staff.joinToString { "${it.handle} (${it.role})" })
    }
}

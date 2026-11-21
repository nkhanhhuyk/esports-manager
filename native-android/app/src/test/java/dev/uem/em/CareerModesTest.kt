package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Academies
import dev.uem.em.mgmt.Career
import dev.uem.em.mgmt.Roles
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jobs and delegation, academies, fictional worlds and multi-season simulation. */
class CareerModesTest {
    @Test
    fun academiesExistAndWork() {
        TestData.init()
        BoxStore.clear()
        val w = WorldGen.create(TestData.source, "academies", "A", Dates.day(2026, 11, 21))
        val academies = w.teams.filter { it.parentId >= 0 }
        println("academies ${academies.size}: " + academies.groupBy { w.leagues.getOrNull(it.leagueId)?.short ?: "dev" }.mapValues { it.value.size })
        // Every LCK and MPL ID/PH team has an academy in LCK CL / MDL.
        for (short in listOf("LCK", "MPL ID", "MPL PH")) {
            val l = w.leagues.firstOrNull { it.tier == 1 && it.short == short } ?: continue
            assertTrue("$short has an academy league", l.lowerId >= 0)
            for (tid in l.teamIds) assertNotNull("${w.teams[tid].name} academy", Academies.of(w, w.teams[tid]))
        }
        for (a in academies) {
            val parent = w.teams[a.parentId]
            assertEquals(parent.orgId, a.orgId)
            assertTrue("academy never in its parent's league", a.leagueId != parent.leagueId)
            assertTrue(a.roster.size >= Catalogue.get(a.titleId).teamSize)
            assertTrue(a.dataNote.isNotBlank())
        }
        // Move a player down and back up.
        val t1 = w.teams.first { it.name == "T1" }
        val ac = Academies.of(w, t1)!!
        val p = w.players[t1.roster.last()]
        assertTrue(Academies.move(w, p).ok)
        assertEquals(ac.id, p.teamId)
        assertTrue(Academies.move(w, p).ok)
        assertEquals(t1.id, p.teamId)
        // An AI team missing a position promotes from its academy before signing anyone.
        val gen = w.teams.first { it.name.startsWith("Gen.G") && it.parentId < 0 }
        val jungler = gen.roster.map { w.players[it] }.filter { it.roles[0] == "jungle" }
        for (j in jungler) { gen.roster.remove(j.id); j.teamId = -1 }
        val acG = Academies.of(w, gen)!!
        val before = acG.roster.size
        assertTrue(Academies.aiPromote(w, gen))
        assertTrue(gen.roster.any { w.players[it].roles[0] == "jungle" })
        assertEquals(before - 1, acG.roster.size)
        // A season with academies: Challengers leagues play, intake fills academies.
        Advance.run(w, 370)
        val cl = w.comps.filter { it.season == 2027 && it.leagueId == w.leagues.first { l -> l.short == "LCK CL" }.id }
        assertTrue(cl.isNotEmpty() && cl.all { it.done })
        assertTrue(w.teams.filter { it.parentId >= 0 && it.active }.all { it.roster.size >= Catalogue.get(it.titleId).teamSize })
        println("after a season: academies ${w.teams.count { it.parentId >= 0 }}, academy players ${w.players.count { it.teamId >= 0 && w.teams[it.teamId].parentId >= 0 }}")
    }

    @Test
    fun fictionalWorldHasNoRealTeams() {
        TestData.init()
        BoxStore.clear()
        val w = WorldGen.create(TestData.source, "fic", "F", Dates.day(2026, 11, 21), fictional = true)
        assertTrue(w.fictional)
        assertTrue(w.teams.none { it.real })
        assertTrue(w.orgs.none { it.real })
        assertTrue(w.leagues.none { it.real })
        assertTrue(w.players.none { it.realSrc.isNotBlank() })
        for (id in w.titles) assertTrue("$id has leagues", w.leagues.any { it.titleId == id && it.tier == 1 })
        assertTrue(w.teams.any { it.parentId >= 0 })
        println("fictional: ${w.leagues.size} leagues, ${w.teams.size} teams, ${w.players.size} players, ${w.teams.count { it.parentId >= 0 }} academies")
        Advance.run(w, 60)
        assertTrue(w.fixtures.any { it.done })
    }

    @Test
    fun jobsAndDelegation() {
        TestData.init()
        BoxStore.clear()
        val w = WorldGen.create(TestData.source, "roles", "R", Dates.day(2026, 11, 21))
        val t1 = w.teams.first { it.name == "T1" }
        w.userOrg = t1.orgId
        // Owner: no board, cannot pick lineups or sign players; the staff do.
        w.role = "owner"
        Career.setupBoard(w)
        assertNull(w.board)
        assertTrue(!Roles.can(w, "tactics") && !Roles.can(w, "transfers") && Roles.can(w, "facilities"))
        val mid = t1.roster.map { w.players[it] }.filter { it.roles[0] == "mid" }
        for (p in mid) { t1.roster.remove(p.id); p.teamId = -1 }
        t1.lineup = t1.lineup.map { if (it in mid.map { m -> m.id }) -1 else it }.toMutableList()
        Advance.run(w, 3)
        assertTrue("GM signed a mid laner", t1.roster.any { w.players[it].roles[0] == "mid" })
        // Head coach with renewals delegated: October renewals happen without the user.
        w.role = "coach-gm"
        Career.setupBoard(w)
        assertNotNull(w.board)
        w.delegation["renewals"] = true
        assertTrue(Roles.delegated(w, "renewals") && !Roles.handles(w, "renewals") && Roles.handles(w, "signings"))
        val ending = t1.roster.map { w.players[it] }.filter { it.contractEnd == w.season && it.ca >= 58 && it.loanFrom < 0 }
        Advance.run(w, Dates.day(w.season, 10, 2) - w.day)
        if (ending.isNotEmpty()) assertTrue("renewed: ${ending.map { it.handle + " " + it.contractEnd }}", ending.any { it.contractEnd > w.season || it.teamId != t1.id })
        assertTrue(w.inbox.any { it.from == "General manager" })
    }

    @Test
    fun multiSeasonAutopilotKeepsTheClubRunning() {
        TestData.init()
        BoxStore.clear()
        val w = WorldGen.create(TestData.source, "years", "Y", Dates.day(2026, 11, 21), titles = listOf("lol", "valorant"))
        val t1 = w.teams.first { it.name == "T1" }
        w.userOrg = t1.orgId
        Career.setupBoard(w)
        w.autopilot = true
        val t0 = System.currentTimeMillis()
        Advance.run(w, dev.uem.em.comp.Season.endDay(w.season + 1) - w.day)
        w.autopilot = false
        println("two seasons (2 games) in ${System.currentTimeMillis() - t0} ms; now ${w.season}")
        assertEquals(2029, w.season)
        val t = Catalogue.get("lol")
        assertTrue(t1.roster.size >= t.teamSize)
        assertTrue(Roles.handles(w, "signings"))
        assertTrue(w.orgs[t1.orgId].teams.map { w.teams[it] }.filter { it.active }.all { tm -> Catalogue.get(tm.titleId).lineup.all { r -> tm.roster.any { w.players[it].roles.take(2).contains(r) } || Academies.isAcademy(tm) } })
    }
}

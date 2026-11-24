package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.World
import dev.uem.em.persist.SaveStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Headless soak (UEM_SOAK=<seasons>, 20 by default when set): a managed career on autopilot, saved and
 * reloaded every five seasons, with the five expansions' invariants checked at each season end —
 * every edition of the season finished, one ledger entry per entrant, no contract on two rosters, no
 * player in two series on a day, development circuits alive, mentoring and preparation lists bounded —
 * and the time, heap and save size per season reported.
 */
class SoakTest {
    private fun check(w: World, season: Int, out: StringBuilder) {
        val left = w.comps.filter { it.season == season && it.status != 2 }
        assertTrue("unfinished editions of $season: ${left.take(4).map { it.name }}", left.isEmpty())
        for (c in w.comps) {
            if (c.status == 0 || c.season != season) continue
            assertEquals("duplicate entrants in ${c.name}", c.teamIds.size, c.teamIds.toSet().size)
            if (c.entries.isNotEmpty()) for (tid in c.teamIds) assertEquals("ledger of ${w.teams[tid].name} in ${c.name}", 1, c.entries.count { it.teamId == tid && it.status == "in" })
        }
        val contracted = w.teams.filter { it.active && it.kind.isEmpty() }.flatMap { tm -> tm.roster.filter { w.players[it].teamId == tm.id } }
        assertEquals("a contract on two rosters", contracted.size, contracted.toSet().size)
        for (tm in w.teams) if (tm.active && tm.kind.isNotEmpty()) for (pid in tm.roster) assertTrue("${w.players[pid].handle} on ${tm.name} has a contract elsewhere", w.players[pid].teamId < 0 || w.players[pid].teamId == tm.id)
        val seen = HashSet<Long>()
        var twice = 0
        for (f in w.fixtures) if (f.done && f.day > w.day - 60) BoxStore.get(w, f.id)?.games?.firstOrNull()?.lines?.forEach { l -> if (!seen.add(l.playerId.toLong() * 100000 + f.day)) twice++ }
        assertEquals("players in two series on one day", 0, twice)
        val circuits = w.comps.count { it.season == season && it.circuit.isNotBlank() }
        assertTrue("development circuits run", circuits > 50)
        assertTrue("prep plans bounded (${w.preps.size})", w.preps.size < 4000)
        assertTrue("mentorships bounded (${w.mentorships.size})", w.mentorships.size < 6000)
        assertTrue("invitations bounded (${w.invites.size})", w.invites.size < 3000)
        out.append("ledger ${w.comps.filter { it.season == season }.sumOf { it.entries.size }}, circuits $circuits, mentorships ${w.mentorships.count { it.active }}/${w.mentorships.size}, preps ${w.preps.size}, invites ${w.invites.size}, dev clubs ${w.teams.count { it.active && it.kind.isNotEmpty() }}")
    }

    @Test
    fun twentySeasons() {
        val n = System.getenv("UEM_SOAK")?.toIntOrNull() ?: 0
        assumeTrue(n > 0)
        TestData.init()
        val dir = File("build/soak").apply { deleteRecursively(); mkdirs() }
        val store = SaveStore(dir)
        BoxStore.clear(); BoxStore.store = store.pages()
        var w = WorldGen.create(TestData.source, "soak", "Soak", Dates.day(2026, 11, 21))
        val team = w.teams.first { it.name == "T1" }
        w.userOrg = team.orgId
        dev.uem.em.mgmt.Career.setupBoard(w)
        w.autopilot = true
        val rt = Runtime.getRuntime()
        val out = StringBuilder()
        val first = w.season
        for (s in 0 until n) {
            val t0 = System.currentTimeMillis()
            val season = w.season
            while (w.season == season) Advance.run(w, 30)
            store.save("soak", w)
            if ((s + 1) % 5 == 0) { BoxStore.clear(); w = store.load("soak"); w.autopilot = true }
            System.gc()
            val mem = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
            out.append("season $season: ${(System.currentTimeMillis() - t0) / 1000}s, heap $mem MB, save ${File(dir, "soak.uem").length() / 1024} KB, players ${w.players.count { !it.retired }}, fixtures ${w.fixtures.size} · ")
            check(w, season, out)
            out.appendLine()
            println(out.lines().dropLast(1).last())
            File("build/soak.txt").writeText(out.toString())
        }
        assertEquals(first + n, w.season)
        BoxStore.store = null
    }
}

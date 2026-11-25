package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.HistoryBook
import dev.uem.em.mgmt.Legacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real history before the save: events load, real players carry their past titles into legacy, legends reach the Hall of Fame. */
class HistoryTest {
    @Test
    fun realCareersCount() {
        TestData.init(); BoxStore.clear()
        assertTrue("history loaded: ${HistoryBook.events.size}", HistoryBook.events.size > 450)
        // Every League of Legends World Championship from 2011 to 2025 is there, with its champion's players.
        val worlds = HistoryBook.events.filter { it.titleId == "lol" && it.tier == "worlds" }
        for (y in 2011..2025) assertTrue("LoL Worlds $y", worlds.any { it.year == y && it.winners.size >= 5 })
        assertEquals("SK Telecom T1", worlds.first { it.year == 2013 }.winner)
        assertTrue(HistoryBook.events.count { it.titleId == "cs2" } >= 20 && HistoryBook.events.count { it.titleId == "dota2" } >= 14)

        val w = WorldGen.create(TestData.source, "hist", "H", Dates.day(2026, 11, 21))
        val faker = w.players.first { it.titleId == "lol" && it.handle == "Faker" }
        val hs = Legacy.honours(w, faker)
        assertTrue("Faker's real titles: ${hs.count { it.past && it.trophy }}", hs.any { it.past && it.season == 2013 && it.label.contains("World") })
        val lg = Legacy.of(w, faker, hs)
        assertTrue("Faker's legacy counts the real career: ${lg.pastPts}", lg.pastPts > 100 && lg.total > lg.pastPts)
        // A fictional world has no real history.
        val fw = WorldGen.create(TestData.source, "histf", "F", Dates.day(2026, 11, 21), fictional = true)
        assertTrue(fw.players.none { p -> Legacy.honours(fw, p).any { it.past } })
        // Legends who retired before the save (not in the world) are inducted on their real records.
        val absent = HistoryBook.absent(w, null).map { it to Legacy.ofLegend(it) }.filter { it.second.total >= 60 }
        val sb = StringBuilder("legends not in the world with legacy ≥ 60: ${absent.size}\n")
        for ((l, s) in absent.sortedByDescending { it.second.total }.take(15)) sb.appendLine("  ${l.titleId} ${l.handle} %.1f (${l.titles} titles, ${l.firstYear}–${l.lastYear})".format(s.total))
        val top = w.players.filter { HistoryBook.of(w, it).isNotEmpty() }.map { it to Legacy.of(w, it) }.sortedByDescending { it.second.total }.take(15)
        sb.appendLine("players in the world with the most legacy:")
        for ((p, s) in top) sb.appendLine("  ${p.titleId} ${p.handle} %.1f (real %.1f)".format(s.total, s.pastPts))
        println(sb); java.io.File("build/history.txt").writeText(sb.toString())
        // Legends whose careers ended before the save are in the world now (every past-roster player is), unsigned and
        // without games in the save: the Hall of Fame inducts them on their real records, like the few still absent.
        val before = w.players.filter { it.teamId < 0 && it.stats.isEmpty() && HistoryBook.of(w, it).isNotEmpty() }.map { it to Legacy.of(w, it) }.filter { it.second.total >= 60 }
        println("inducted before the save: ${before.size} in the world (" + before.sortedByDescending { it.second.total }.take(8).joinToString { "${it.first.handle} %.0f".format(it.second.total) } + "), ${absent.size} absent")
        assertTrue("some legends are inducted before the save", absent.isNotEmpty() || before.isNotEmpty())
        assertTrue("Bengi is a legend in the world", before.any { it.first.titleId == "lol" && it.first.handle == "Bengi" })
    }
}

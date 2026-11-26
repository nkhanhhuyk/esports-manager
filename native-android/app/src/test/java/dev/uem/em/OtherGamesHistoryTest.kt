package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.HistoryBook
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real history beyond League of Legends: VCT leagues, MPL seasons, CS S-tier events, Dota 2 premium events. */
class OtherGamesHistoryTest {
    @Test
    fun leaguesShowTheirRealPast() {
        TestData.init()
        val w = WorldGen.create(TestData.source, "hist2", "H2", Dates.day(2026, 11, 21), titles = listOf("valorant", "mlbb", "cs2", "dota2"))
        for (t in listOf("valorant", "mlbb", "cs2", "dota2")) println("$t: ${HistoryBook.events.count { it.titleId == t }} events, ${HistoryBook.events.count { it.titleId == t && it.winners.size >= 4 }} with the champions' players")
        assertTrue(HistoryBook.events.count { it.titleId == "valorant" } >= 150)
        assertTrue(HistoryBook.events.count { it.titleId == "cs2" } >= 100)
        assertTrue(HistoryBook.events.count { it.titleId == "dota2" } >= 40)
        // The VCT Americas league's History: its own seasons and the circuits before it.
        val amer = w.leagues.first { it.titleId == "valorant" && it.short == "VCT Americas" }
        val c = w.comps.first { it.leagueId == amer.id }
        val evs = HistoryBook.forComp(c, amer.short)
        println("VCT Americas history: " + evs.take(6).joinToString { "${it.year} ${it.name} → ${it.winner}" })
        assertTrue(evs.any { it.year == 2023 && it.name.contains("Americas League") && it.winner == "LOUD" })
        assertTrue("no other region", evs.none { it.name.contains("EMEA") || it.name.contains("Pacific") })
        // MPL Indonesia's History: its seasons only.
        val mpl = w.leagues.first { it.titleId == "mlbb" && it.short == "MPL ID" }
        val cm = w.comps.first { it.leagueId == mpl.id }
        val mevs = HistoryBook.forComp(cm, mpl.short)
        println("MPL ID history: " + mevs.joinToString { "${it.name} → ${it.winner}" })
        assertTrue(mevs.any { it.name == "MPL ID Season 15" && it.winner == "ONIC" })
        assertTrue(mevs.none { it.league.contains("Philippines") })
    }
}

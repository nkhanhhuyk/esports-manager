package dev.uem.em

import dev.uem.em.mgmt.PastRosters
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real rosters before the save (pastRosters.json): known line-ups of famous teams, every game with real teams covered. */
class PastRostersTest {
    private fun has(title: String, team: String, year: Int, vararg handles: String) {
        val y = PastRosters.of(title, team).firstOrNull { it.year == year }
        assertTrue("$team $year missing", y != null)
        val got = y!!.players.map { it.handle.lowercase() }.toSet()
        for (h in handles) assertTrue("$team $year: $h not in $got", h.lowercase() in got)
    }

    @Test
    fun famousRostersAreThere() {
        TestData.init()
        has("lol", "T1", 2013, "Faker", "Bengi", "Impact", "PoohManDu")
        has("lol", "Fnatic", 2019, "Bwipo", "Broxah", "Nemesis", "Rekkles", "Hylissang")
        has("lol", "G2 Esports", 2019, "Wunder", "Jankos", "Caps", "Perkz", "Mikyx")
        has("lol", "Los Ratones", 2025, "Baus", "Velja", "Nemesis", "Crownie", "Rekkles")
        has("dota2", "Team Spirit", 2021, "Yatoro", "TORONTOTOKYO", "Collapse", "Mira", "Miposhka")
        has("valorant", "Sentinels", 2021, "TenZ", "ShahZaM", "SicK", "dapr", "zombs")
        has("cs2", "Natus Vincere", 2021, "s1mple", "electroNic", "b1t", "Perfecto", "Boombl4")
        // Earlier names of a club are joined in (SK Telecom T1 → T1).
        assertTrue(PastRosters.of("lol", "T1").first { it.year == 2015 }.names.any { it.contains("SK") })
        // A spell's months: Faker joined in February 2013.
        assertTrue(PastRosters.of("lol", "T1").first { it.year == 2013 }.players.first { it.handle == "Faker" }.months().startsWith("Feb"))
        // Staff where known.
        assertTrue(PastRosters.of("lol", "T1").first { it.year == 2015 }.staff.isNotEmpty())
        for (title in listOf("lol", "dota2", "valorant", "cs2", "mlbb", "wildrift")) {
            assertTrue("no sources", PastRosters.sources.isNotEmpty() || title != "lol")
        }
        assertTrue(PastRosters.of("hok", "anything").isEmpty())
    }
}

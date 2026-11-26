package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Academies
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every club of every game has a second team; MOBA top leagues' second teams play academy leagues, the others are development squads. */
class AcademiesTest {
    @Test
    fun everyClubHasAnAcademy() {
        TestData.init()
        val t0 = System.currentTimeMillis()
        val w = WorldGen.create(TestData.source, "acads", "A", Dates.day(2026, 11, 21))
        val ms = System.currentTimeMillis() - t0
        val clubs = w.teams.filter { it.active && it.parentId < 0 && it.nationalOf.isEmpty() && it.kind.isEmpty() && it.leagueId >= 0 }
        val without = clubs.filter { Academies.of(w, it) == null }
        println("clubs ${clubs.size}, academies ${w.teams.count { it.parentId >= 0 }}, without ${without.size}; players ${w.players.size}, teams ${w.teams.size}; world built in $ms ms")
        for (id in w.titles) println("  $id: ${clubs.count { it.titleId == id }} clubs, ${w.teams.count { it.titleId == id && it.parentId >= 0 && it.leagueId >= 0 }} academies in a league")
        assertTrue("clubs without an academy: ${without.take(5).map { it.name }}", without.isEmpty())
        // A second-division club's second team plays no league; no academy plays in its parent's league.
        for (a in w.teams.filter { it.parentId >= 0 }) {
            val parent = w.teams[a.parentId]
            assertTrue("${a.name} in its parent's league", a.leagueId < 0 || a.leagueId != parent.leagueId)
            if (w.leagues.getOrNull(parent.leagueId)?.tier != 1) assertTrue("${a.name}: a second-division club's second team plays no league", a.leagueId < 0)
            assertTrue("${a.name} has a roster", a.roster.size >= dev.uem.em.titles.Catalogue.get(a.titleId).teamSize)
        }
        // LoL, MLBB, HoK and Wild Rift: every top-league club's second team plays (an academy league or a Challengers division).
        for (a in w.teams.filter { it.parentId >= 0 && it.titleId in dev.uem.em.mgmt.Academies.LEAGUE_TITLES }) {
            if (w.leagues.getOrNull(w.teams[a.parentId].leagueId)?.tier == 1) assertTrue("${a.name} plays a league", a.leagueId >= 0)
        }
    }
}

package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Extras
import dev.uem.em.mgmt.Imports
import dev.uem.em.mgmt.Scrims
import dev.uem.em.model.Fixture
import dev.uem.em.model.SeasonLine
import dev.uem.em.sim.Lineups
import dev.uem.em.sim.SeriesSim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchFixesTest {
    private val w by lazy {
        TestData.init()
        BoxStore.clear()
        WorldGen.create(TestData.source, "batch", "Batch", Dates.day(2026, 11, 21))
    }

    @Test
    fun insaneFearlessNeverRepeatsOrWastesBans() {
        val league = w.comps.first { it.titleId == "lol" && it.leagueId >= 0 && it.season == w.season }
        league.fearlessMode = "insane"
        val a = w.teams.first { it.name == "T1" }
        val b = w.teams.first { it.name.startsWith("Gen.G") && it.parentId < 0 }
        for (k in 0 until 6) {
            val f = Fixture(100_000 + k, league.id, 0, "lol", w.day, a.id, b.id, 5)
            val out = SeriesSim.run(w, f, arrayOf(Lineups.auto(w, a), Lineups.auto(w, b)))
            val gone = HashSet<String>()
            for (g in out.record.games) {
                val picks = g.lines.map { it.charId }.filter { it.isNotEmpty() }
                assertTrue("a champion picked or banned earlier was played again: ${picks.filter { it in gone }}", picks.none { it in gone })
                assertTrue("a ban was spent on a champion already out: ${g.bans.filter { it in gone }}", g.bans.none { it in gone })
                gone.addAll(picks); gone.addAll(g.bans)
            }
        }
        // Soft Fearless: a ban on a champion the opponent already played (it cannot pick it) is wasted too.
        val s = dev.uem.em.sim.DraftSession(dev.uem.em.titles.Catalogue.get("lol"), dev.uem.em.sim.Patch.of(w, dev.uem.em.titles.Catalogue.get("lol")),
            arrayOf(Lineups.auto(w, a).map { w.players[it] }, Lineups.auto(w, b).map { w.players[it] }), arrayOf(a.style, b.style), 0,
            arrayOf(setOf("lol-ahri"), setOf("lol-azir")), dev.uem.em.core.Rng.of("fearless"))
        assertTrue(!s.legal(0, true, "lol-azir") && s.legal(0, true, "lol-ahri"))
    }

    @Test
    fun nationalitiesFromRealNamesAndResidency() {
        val viper = w.players.first { it.titleId == "lol" && it.handle == "Viper" && it.teamId >= 0 }
        val tarzan = w.players.first { it.titleId == "lol" && it.handle == "Tarzan" && it.teamId >= 0 }
        val knight = w.players.first { it.titleId == "lol" && it.handle == "Knight" && it.teamId >= 0 }
        assertEquals("KR", viper.nat); assertEquals("KR", tarzan.nat); assertEquals("CN", knight.nat)
        assertEquals(Dates.day(2000, 10, 19), viper.birth)
        // Three straight seasons at Chinese clubs: a second nationality, no longer an import in China.
        val team = w.teams[viper.teamId]
        assertTrue(Imports.isImport(w, viper, team))
        viper.resCountry = w.orgs[team.orgId].country; viper.resYears = 2
        Extras.residency(w)
        assertEquals(listOf("KR", w.orgs[team.orgId].country), viper.nats())
        assertTrue(!Imports.isImport(w, viper, team))
    }

    @Test
    fun callUpsFollowForm() {
        val kr = w.players.filter { it.titleId == "lol" && it.nat == "KR" && !it.retired && it.mainRole == "mid" }.sortedByDescending { it.ca }
        val star = kr[0]; val other = kr[3]
        // The lesser player has a great season, the star a poor one.
        star.stats.add(SeasonLine(w.season, "lol", star.teamId, gp = 30, ratingSum = 30 * 0.7))
        other.stats.add(SeasonLine(w.season, "lol", other.teamId, gp = 30, ratingSum = 30 * 1.6))
        assertTrue(Extras.selectionScore(w, other) > Extras.selectionScore(w, star))
        val team = Extras.callUp(w, Extras.nationalTeam(w, "lol", "KR"))
        assertTrue(other.id in team.roster)
    }

    @Test
    fun scrimsArePlayedAndRemembered() {
        Advance.run(w, 21)
        val played = w.teams.filter { it.active && it.scrimLog.isNotEmpty() }
        assertTrue(played.size > 100)
        val t1 = w.teams.first { it.name == "T1" }
        assertTrue(t1.scrimLog.isNotEmpty() && Scrims.bonus(w, t1).isNotEmpty())
        println("T1 scrims: " + t1.scrimLog.joinToString { "${w.teams[it.opp].short} ${it.won}-${it.lost}" })
    }
}

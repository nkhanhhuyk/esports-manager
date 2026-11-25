package dev.uem.em

import dev.uem.em.comp.Season
import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Economy
import dev.uem.em.persist.SaveStore
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Season reconciliation (results, stats, prizes, rollover add up), save export / import with
 * checksums, and an optional 20-season soak test (UEM_SOAK=1) with timings and sizes.
 */
class IntegrityTest {
    @Test
    fun seasonReconciles() {
        TestData.init()
        BoxStore.clear()
        BoxStore.store = null
        val w = WorldGen.create(TestData.source, "recon", "Recon", Dates.day(2026, 11, 21))
        val t1 = w.teams.first { it.name == "T1" }
        w.userOrg = t1.orgId
        dev.uem.em.mgmt.Career.setupBoard(w)
        val season = w.season
        Advance.run(w, Season.endDay(season) - w.day - 1)
        // 1. Every finished competition: the champion won its final; standings add up.
        var finals = 0
        for (c in w.comps.filter { it.season == season && it.done }) {
            val last = c.stages.last()
            val finalNode = last.bracket.firstOrNull { it.isFinal }
            if (finalNode != null && finalNode.fixtureId >= 0) {
                val f = w.fixtures[finalNode.fixtureId]
                assertEquals("${c.name}: champion is not the final's winner", f.winner, c.placements[0])
                finals++
            }
            for (st in c.stages) {
                val games = st.standings.sumOf { it.gw }
                val lost = st.standings.sumOf { it.gl }
                assertEquals("${c.name} ${st.name}: games won and lost differ", games, lost)
            }
        }
        // 2. Player stats in each competition equal the sum of its box scores; every result applied once.
        val t = Catalogue.get("lol")
        val k = t.statIndex["k"]!!
        for (c in w.comps.filter { it.season == season && it.titleId == "lol" && it.done }) {
            val fixtures = c.stages.flatMap { it.fixtureIds }.map { w.fixtures[it] }.filter { it.done }
            val recs = fixtures.map { BoxStore.get(w, it.id)!! }
            for ((f, r) in fixtures.zip(recs)) assertEquals("${c.name}: games in the box score", f.scoreA + f.scoreB, r.games.size)
            val boxKills = recs.sumOf { r -> r.games.sumOf { g -> g.lines.sumOf { it.s[k] } } }
            val statKills = c.pstats.values.sumOf { it.v[k] }
            assertEquals("${c.name}: competition stats differ from box scores", boxKills, statKills, 0.001)
            assertEquals(recs.sumOf { r -> r.games.sumOf { it.lines.size } }, c.pstats.values.sumOf { it.gp })
        }
        // 3. Prize money paid once per placing.
        for (org in w.orgs) {
            val prizes = org.ledger.filter { it.kind == "prize" }.groupBy { it.note }
            for ((note, lines) in prizes) assertEquals("${org.name}: prize paid twice for $note", 1, lines.size)
        }
        // 4. The season ends once.
        val seasonsBefore = w.teams.first().history.size
        Advance.run(w, 3)
        assertEquals(season + 1, w.season)
        assertEquals(seasonsBefore + 1, w.teams.first().history.size)
        // One season line per team and season (national teams also keep one line per event they played: compId >= 0).
        assertTrue(w.teams.all { tm -> tm.history.filter { it.compId < 0 }.groupBy { it.season }.values.all { it.size == 1 } })
        println("reconciled season $season: ${w.comps.count { it.season == season && it.done }} competitions, $finals finals")
    }

    @Test
    fun exportImportRoundTrip() {
        TestData.init()
        val dir = File("build/test-export").apply { deleteRecursively(); mkdirs() }
        val store = SaveStore(dir)
        BoxStore.clear()
        BoxStore.store = store.pages()
        val w = WorldGen.create(TestData.source, "exp", "Exported", Dates.day(2026, 11, 21))
        Advance.run(w, 60)
        store.save("Exported", w)
        val bytes = ByteArrayOutputStream().also { store.export("Exported", it) }.toByteArray()
        // Same name already present: imported under a new name, pages kept apart.
        val r = store.import(ByteArrayInputStream(bytes))
        assertTrue(r.renamed)
        val back = store.load(r.slot)
        assertEquals(w.day, back.day)
        assertEquals(w.fixtures.count { it.done }, back.fixtures.count { it.done })
        val paged = back.fixtures.first { it.done && it.page >= 0 }
        assertTrue(BoxStore.get(back, paged.id) != null)
        // A damaged file is refused.
        val bad = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val err = runCatching { store.import(ByteArrayInputStream(bad)) }.exceptionOrNull()
        assertTrue("damaged archive accepted", err != null)
        println("export ${bytes.size / 1024} KB; imported as ${r.name}; damaged file refused: ${err?.message}")
        BoxStore.store = null
    }

    @Test
    fun twentySeasonSoak() {
        assumeTrue("set UEM_SOAK=1 to run", System.getenv("UEM_SOAK") == "1")
        TestData.init()
        val dir = File("build/test-soak").apply { deleteRecursively(); mkdirs() }
        val store = SaveStore(dir)
        BoxStore.clear()
        BoxStore.store = store.pages()
        val w = WorldGen.create(TestData.source, "soak", "Soak", Dates.day(2026, 11, 21))
        w.userOrg = w.teams.first { it.name == "T1" }.orgId
        dev.uem.em.mgmt.Career.setupBoard(w)
        val start = w.season
        val rt = Runtime.getRuntime()
        // A recurring user competition must get one edition per season, every season.
        val custom = dev.uem.em.model.CustomComp(0, "Soak Invitational", "SOAK", "lol", "se", startMonth = 8, startDom = 1, recurring = true)
        custom.sources.add("league:${w.teams.first { it.name == "T1" }.leagueId}")
        w.customComps.add(custom)
        assertEquals(null, dev.uem.em.comp.CustomComps.publish(w, custom))
        val seasons = (System.getenv("UEM_SOAK_SEASONS") ?: "20").toInt()
        for (i in 1..seasons) {
            val t0 = System.currentTimeMillis()
            val s = w.season
            Advance.run(w, Season.endDay(s) - w.day + 1)
            val ms = System.currentTimeMillis() - t0
            assertEquals(s + 1, w.season)
            store.save("soak", w)
            System.gc()
            val mem = (rt.totalMemory() - rt.freeMemory()) / (1 shl 20)
            val save = File(dir, "soak.uem").length() / 1024
            val pages = File(dir, "Soak.pages").listFiles().orEmpty().sumOf { it.length() } / 1024
            val active = w.players.count { !it.retired }
            println("season ${s}: ${ms} ms, heap ${mem} MB, save ${save} KB, pages ${pages} KB, players ${w.players.size} (${active} active), teams ${w.teams.count { it.active }}, transfers ${w.transfers.size}")
            // Every active league team can field a full lineup.
            for (tm in w.teams.filter { it.active && it.leagueId >= 0 }) assertTrue("${tm.name} short of players in $s", tm.roster.size >= Catalogue.get(tm.titleId).teamSize)
            // The custom event was played this season, exactly once.
            assertEquals(1, custom.editions.count { w.comps[it].season == s && w.comps[it].done && w.comps[it].placements.isNotEmpty() })
            // Fixture ids are unique and every competition of the season finished once.
            assertEquals(w.fixtures.size, w.fixtures.map { it.id }.distinct().size)
        }
        assertEquals(start + seasons, w.season)
        assertFalse(w.comps.any { it.season < w.season && it.status != 2 && it.teamIds.isNotEmpty() })
        BoxStore.store = null
    }
}

package dev.uem.em

import dev.uem.em.comp.CustomComps
import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.CustomComp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** User-created competitions: formats, byes, validation, cancellation, recurring editions, saves. */
class CustomCompTest {
    @Test
    fun customCompetitions() {
        TestData.init()
        BoxStore.clear()
        val w = WorldGen.create(TestData.source, "custom", "C", Dates.day(2026, 11, 21), titles = listOf("lol", "valorant"))
        val lol = w.teams.filter { it.titleId == "lol" && it.active && it.leagueId >= 0 }.sortedByDescending { it.elo }
        val vt = w.teams.filter { it.titleId == "valorant" && it.active && it.leagueId >= 0 }.sortedByDescending { it.elo }
        fun mk(id: Int, title: String, format: String, n: Int, day: Int, groups: Int = 2, adv: Int = 2) = CustomComp(id, "Test $format $n", "T$id", title, format,
            (if (title == "lol") lol else vt).take(n).map { it.id }.toMutableList(), groups = groups, advance = adv, boGroup = 1, boPlayoff = 3, boFinal = if (title == "lol") 5 else 3, startMonth = 1, startDom = day, recurring = false)
        val cases = listOf(mk(0, "lol", "league", 6, 5), mk(1, "lol", "groups-se", 8, 8), mk(2, "lol", "se", 6, 12), mk(3, "valorant", "de", 8, 6), mk(4, "valorant", "swiss-se", 16, 9), mk(5, "lol", "groups-de", 16, 15, groups = 4, adv = 2))
        val before = w.comps.size
        for (c in cases) {
            w.customComps.add(c)
            val chk = CustomComps.validate(w, c)
            println("${c.name}: ${chk.errors} ${chk.preview}")
            assertTrue("${c.name}: ${chk.errors}", chk.errors.isEmpty())
            assertNull(CustomComps.publish(w, c))
        }
        assertEquals(before + cases.size, w.comps.size)
        // Invalid drafts create nothing.
        val bad = listOf(
            CustomComp(10, "One team", "X", "lol", "se", mutableListOf(lol[0].id)),
            CustomComp(11, "Swiss of 10", "X", "valorant", "swiss-se", vt.take(10).map { it.id }.toMutableList()),
            CustomComp(12, "Bo7 shooter", "X", "valorant", "se", vt.take(4).map { it.id }.toMutableList(), boPlayoff = 7),
            CustomComp(13, "Groups too small", "X", "lol", "groups-se", lol.take(5).map { it.id }.toMutableList(), groups = 4),
        )
        for (c in bad) { w.customComps.add(c); assertNotNull(c.name, CustomComps.publish(w, c)); assertTrue(c.editions.isEmpty()) }
        assertEquals(before + cases.size, w.comps.size)
        // Duplicates count once.
        val dup = CustomComp(20, "Dupes", "D", "lol", "se", mutableListOf(lol[0].id, lol[0].id, lol[1].id), sources = mutableListOf("league:${lol[0].leagueId}"))
        val (ents, _) = CustomComps.entrants(w, dup)
        assertEquals(ents.size, ents.distinct().size)
        // Cancel one before it starts.
        val cancel = mk(30, "lol", "se", 4, 25).also { w.customComps.add(it) }
        assertNull(CustomComps.publish(w, cancel))
        CustomComps.cancel(w, cancel)
        // Play until all have finished.
        Advance.run(w, Dates.day(2027, 3, 31) - w.day)
        for (c in cases) {
            val comp = w.comps[c.editions.single()]
            assertTrue("${comp.name} finished", comp.done)
            assertTrue("${comp.name} has a champion", comp.placements.isNotEmpty())
            val champ = w.teams[comp.placements[0]]
            assertTrue("${comp.name} prize paid", w.orgs[champ.orgId].ledger.any { it.kind == "prize" && it.note.contains(comp.short) })
            val fx = comp.stages.flatMap { it.fixtureIds }.map { w.fixtures[it] }
            assertTrue(fx.isNotEmpty() && fx.all { it.done })
            println("${comp.name}: ${fx.size} series, champion ${champ.name}")
        }
        val cc = w.comps[cancel.editions.single()]
        assertTrue(cc.done && cc.stages.all { it.fixtureIds.isEmpty() })
        // Recurring: next season gets a new edition, the old one keeps its result.
        val rec = mk(40, "lol", "se", 4, 20).also { it.recurring = true; it.startMonth = 6; w.customComps.add(it) }
        assertNull(CustomComps.publish(w, rec))
        Advance.run(w, dev.uem.em.comp.Season.endDay(w.season) - w.day + 2)
        assertEquals(2, rec.editions.size)
        val first = w.comps[rec.editions[0]]
        assertTrue(first.done && first.placements.isNotEmpty())
        assertEquals(w.season, w.comps[rec.editions[1]].season)
        // Saves keep drafts and editions.
        val dir = java.io.File("build/tmp/customsave").apply { deleteRecursively(); mkdirs() }
        val store = dev.uem.em.persist.SaveStore(dir)
        store.save("c", w)
        val back = store.load("c")
        assertEquals(w.customComps.size, back.customComps.size)
        assertEquals(rec.editions, back.customComps.first { it.id == 40 }.editions)
    }
}

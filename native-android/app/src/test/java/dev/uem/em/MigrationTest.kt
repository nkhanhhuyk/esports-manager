package dev.uem.em

import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.persist.SaveStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Old careers keep working: a save made before the five expansions (commit 203f341, see
 * `OldSaveFixtureTest`) loads with the current code, keeps its results, trophies, transfers and box
 * scores, advances 40 days (new systems start up from defaults), saves and loads again.
 */
class MigrationTest {
    @Test
    fun oldCareerLoadsAndAdvances() {
        TestData.init(); BoxStore.clear()
        val src = File("src/test/resources/oldsave")
        val dir = File("build/migration").apply { deleteRecursively(); mkdirs() }
        src.copyRecursively(dir, overwrite = true)
        val facts = File(src, "fixture.txt").readLines().filter { "=" in it }.associate { it.substringBefore("=") to it.substringAfter("=") }
        val store = SaveStore(dir)
        BoxStore.store = store.pages()
        val w = store.load("career")
        dev.uem.em.titles.Catalogue.applyCustom(w)
        // History is intact.
        assertEquals(facts["day"]!!.toInt(), w.day)
        assertEquals(facts["done"]!!.toInt(), w.fixtures.count { it.done })
        assertEquals(facts["trophies"]!!.toInt(), w.teams.sumOf { it.trophies.size })
        assertEquals(facts["transfers"]!!.toInt(), w.transfers.size)
        assertEquals(facts["players"]!!.toInt(), w.players.size)
        assertEquals(facts["userOrg"]!!.toInt(), w.userOrg)
        // Old box scores (in the save and in the pages) are readable.
        val first = w.fixtures.first { it.done }
        val last = w.fixtures.last { it.done }
        assertNotNull("old paged box score", BoxStore.get(w, first.id))
        assertNotNull("recent box score", BoxStore.get(w, last.id))
        val doneBefore = w.fixtures.filter { it.done }.map { it.id to (it.winner to it.scoreA * 10 + it.scoreB) }
        // Advance: matches are played, nothing completed before changes.
        Advance.run(w, 40)
        assertTrue("matches played after loading", w.fixtures.count { it.done } > doneBefore.size)
        for ((id, r) in doneBefore) { val f = w.fixtures[id]; assertTrue(f.done && f.winner == r.first && f.scoreA * 10 + f.scoreB == r.second) }
        // Save and load again with the current code.
        store.save("career", w)
        val w2 = store.load("career")
        assertEquals(w.day, w2.day)
        assertEquals(w.fixtures.count { it.done }, w2.fixtures.count { it.done })
        File("build/migration.txt").writeText("loaded day ${facts["day"]}, advanced to ${w.day}; done ${doneBefore.size} → ${w.fixtures.count { it.done }}\n")
    }
}

package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.persist.SaveStore
import org.junit.Test
import java.io.File

/**
 * Makes the old-save fixture (UEM_FIXTURE=1, run once with the code of commit 203f341, before the five
 * expansions changed the save schema): a T1 career 80 days in, saved with its box-score pages.
 * `MigrationTest` loads it with the current code.
 */
class OldSaveFixtureTest {
    @Test
    fun make() {
        if (System.getenv("UEM_FIXTURE") == null) return
        TestData.init(); BoxStore.clear()
        val dir = File("src/test/resources/oldsave").apply { deleteRecursively(); mkdirs() }
        val store = SaveStore(dir)
        BoxStore.store = store.pages()
        val w = WorldGen.create(TestData.source, "oldsave", "Old save", Dates.day(2026, 11, 21))
        val t1 = w.teams.first { it.name == "T1" }
        w.userOrg = t1.orgId
        dev.uem.em.mgmt.Career.setupBoard(w)
        Advance.run(w, 80)
        store.save("career", w, "Old career (203f341)")
        val f = File(dir, "career.uem")
        println("fixture: ${f.length()} bytes, day ${w.day}, fixtures done ${w.fixtures.count { it.done }}, pages ${File(dir, w.pagesName() + ".pages").listFiles()?.size}")
        File(dir, "fixture.txt").writeText("day=${w.day}\nseason=${w.season}\ndone=${w.fixtures.count { it.done }}\ncomps=${w.comps.size}\nplayers=${w.players.size}\nteams=${w.teams.size}\ntrophies=${w.teams.sumOf { it.trophies.size }}\ntransfers=${w.transfers.size}\nuserOrg=${w.userOrg}\n")
    }
}

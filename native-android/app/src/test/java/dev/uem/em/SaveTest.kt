package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.persist.SaveStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class SaveTest {
    @Test
    fun savesAndLoadsAfterASeason() {
        TestData.init()
        val dir = File("build/test-saves").apply { deleteRecursively(); mkdirs() }
        val store = SaveStore(dir)
        BoxStore.clear()
        BoxStore.store = store.pages()
        val w = WorldGen.create(TestData.source, "save", "Career1", Dates.day(2026, 11, 21))
        Advance.run(w, 380)
        var t = System.currentTimeMillis()
        store.save("slot1", w)
        val saveMs = System.currentTimeMillis() - t
        val size = File(dir, "slot1.uem").length()
        t = System.currentTimeMillis()
        val back = store.load("slot1")
        val loadMs = System.currentTimeMillis() - t
        val pages = File(dir, "Career1.pages").listFiles()?.size ?: 0
        println("save $saveMs ms, load $loadMs ms, size ${size / 1024} KB, players ${back.players.size}, fixtures ${back.fixtures.size}, box pages $pages")
        assertEquals(w.day, back.day)
        assertEquals(w.fixtures.count { it.done }, back.fixtures.count { it.done })
        val old = back.fixtures.first { it.done && it.page >= 0 }
        assertNotNull(BoxStore.get(back, old.id))
        // Simulation continues from a loaded save.
        Advance.run(back, 30)
        BoxStore.store = null
    }
}

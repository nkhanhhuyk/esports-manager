package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.persist.SaveStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Interrupted and damaged saves: a crash between the two renames, a truncated or corrupted save, a
 * half-written temporary file, a full disk (the write fails), a career whose latest file is missing. The
 * game always opens a complete copy (and says which), never a reset career; nothing is deleted.
 */
class SaveRecoveryTest {
    private fun setup(name: String): Pair<SaveStore, File> {
        TestData.init()
        val dir = File("build/recovery-$name").apply { deleteRecursively(); mkdirs() }
        val store = SaveStore(dir)
        BoxStore.clear(); BoxStore.store = store.pages()
        return store to dir
    }
    private fun world(store: SaveStore) = WorldGen.create(TestData.source, "recovery", "Recovery", Dates.day(2026, 11, 21), titles = listOf("lol", "valorant")).also { it.simDetail = "fast" }

    @Test
    fun damagedAndInterruptedSaves() {
        val (store, dir) = setup("a")
        val w = world(store)
        Advance.run(w, 10); val dayA = w.day
        store.save("slot1", w)
        Advance.run(w, 10); val dayB = w.day
        store.save("slot1", w)
        val main = File(dir, "slot1.uem"); val bak = File(dir, "slot1.bak"); val tmp = File(dir, "slot1.tmp")
        assertTrue(main.exists() && bak.exists() && !tmp.exists())
        assertEquals(dayB, store.load("slot1").day); assertEquals("save", store.lastLoad!!.source)

        // 1. The latest save truncated (power cut while the storage was flushing): the backup opens, with a note.
        val good = main.readBytes()
        RandomAccessFile(main, "rw").use { it.setLength(main.length() / 2) }
        val r1 = store.load("slot1")
        assertEquals(dayA, r1.day); assertEquals("backup", store.lastLoad!!.source)
        assertTrue(r1.inbox.any { it.subject == "Save recovered" })
        assertTrue("files are kept", main.exists() && bak.exists())

        // 2. A few bytes flipped in the middle: the gzip check rejects it, the backup opens.
        main.writeBytes(good); val bad = good.copyOf(); for (i in bad.size / 2 until bad.size / 2 + 64) bad[i] = (bad[i].toInt() xor 0x5a).toByte(); main.writeBytes(bad)
        assertEquals(dayA, store.load("slot1").day); assertEquals("backup", store.lastLoad!!.source)

        // 3. A crash between the two renames: the save was moved to the backup, the new one is still the complete .tmp.
        main.writeBytes(good)
        main.renameTo(tmp); File(dir, "slot1.bak").writeBytes(bak.readBytes())
        assertFalse(main.exists())
        assertEquals("the career is still listed", 1, store.list().count { it.slot == "slot1" })
        assertEquals(dayB, store.load("slot1").day); assertEquals("unfinished save", store.lastLoad!!.source)

        // 4. A half-written .tmp next to an intact save (a crash while writing): the save opens, the .tmp is ignored.
        tmp.renameTo(main); tmp.writeBytes(good.copyOf(good.size / 3))
        assertEquals(dayB, store.load("slot1").day); assertEquals("save", store.lastLoad!!.source)
        tmp.delete()

        // 5. Nothing readable: a clear error, and the files stay as they are.
        val keep = main.readBytes()
        main.writeBytes(ByteArray(100) { 7 }); bak.writeBytes(ByteArray(10))
        val err = runCatching { store.load("slot1") }.exceptionOrNull()
        assertTrue(err?.message ?: "", err != null && err.message!!.contains("No readable copy"))
        assertTrue(main.exists() && bak.exists())
        main.writeBytes(keep)
        BoxStore.store = null
    }

    @Test
    fun failedWriteKeepsThePreviousSave() {
        val (store, dir) = setup("b")
        val w = world(store)
        Advance.run(w, 5); store.save("slot1", w); val day = w.day
        Advance.run(w, 5)
        // The disk is full / the write fails: simulated by a directory where the temporary file would go.
        File(dir, "slot1.tmp").mkdirs()
        val err = runCatching { store.save("slot1", w) }.exceptionOrNull()
        assertTrue(err is java.io.IOException && err.message!!.contains("previous save is unchanged"))
        File(dir, "slot1.tmp").deleteRecursively()
        assertEquals(day, store.load("slot1").day)
        assertEquals("save", store.lastLoad!!.source)
        // The next save works and rotates the backup.
        store.save("slot1", w)
        assertEquals(w.day, store.load("slot1").day)
        BoxStore.store = null
    }

    @Test
    fun integrityCheckOnLoad() {
        val (store, _) = setup("c")
        val w = world(store)
        Advance.run(w, 20)
        store.save("slot1", w)
        store.load("slot1")
        assertTrue("integrity problems in a fresh save: ${store.lastLoad!!.problems.take(5)}", store.lastLoad!!.problems.isEmpty())
        BoxStore.store = null
    }
}

package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.persist.SaveStore
import org.junit.Test
import java.io.File

/** Several seasons in a row (UEM_LONG=1): errors, memory and save size per season. */
class LongRunTest {
    @Test
    fun seasons() {
        if (System.getenv("UEM_LONG") == null) return
        TestData.init()
        val dir = File("build/long-saves").apply { deleteRecursively(); mkdirs() }
        val store = SaveStore(dir)
        BoxStore.clear()
        BoxStore.store = store.pages()
        val w = WorldGen.create(TestData.source, "long", "Long", Dates.day(2026, 11, 21))
        w.spectator = true
        val rt = Runtime.getRuntime()
        repeat((System.getenv("UEM_SEASONS") ?: "4").toInt()) { s ->
            val t0 = System.currentTimeMillis()
            for (d in 0 until 365) {
                try { Advance.run(w, 1) } catch (e: Throwable) {
                    println("CRASH on ${Dates.format(w.day)}: $e"); e.printStackTrace(System.out); throw e
                }
            }
            System.gc()
            val mem = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
            store.save("slot1", w)
            if (System.getenv("UEM_HISTO") != null && s == 2) {
                val ph = Class.forName("java.lang.ProcessHandle"); val pid = ph.getMethod("pid").invoke(ph.getMethod("current").invoke(null))
                val jcmd = File(System.getProperty("java.home"), "bin/jcmd").path
                val out = ProcessBuilder(jcmd, "$pid", "GC.class_histogram").redirectErrorStream(true).start().inputStream.bufferedReader().readLines()
                println(out.take(45).joinToString("\n"))
            }
            println("season ${s + 1} to ${Dates.format(w.day)}: ${(System.currentTimeMillis() - t0) / 1000}s, heap ${mem} MB, save ${File(dir, "slot1.uem").length() / 1024} KB, players ${w.players.size} (${w.players.count { !it.retired }} active), teams ${w.teams.size}, fixtures ${w.fixtures.size}, comps ${w.comps.size}, news ${w.news.size}, transfers ${w.transfers.size}, inbox ${w.inbox.size}")
        }
        BoxStore.store = null
    }
}

package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import org.junit.Test

/** Profiling aid (UEM_SPEED=2): where fast-mode calendar simulation spends its time. */
class SpeedFastTest {
    @Test
    fun fastProfile() {
        if (System.getenv("UEM_SPEED") != "2") return
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "fastprof", "FP", Dates.day(2026, 11, 21))
        w.simDetail = "fast"; w.replays = "off"
        Advance.run(w, 10) // warm up
        val counts = HashMap<String, Int>()
        val run = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (run.get()) {
                for ((th, st) in Thread.getAllStackTraces()) {
                    if (th === Thread.currentThread() || th.state != Thread.State.RUNNABLE) continue
                    val frames = st.filter { it.className.startsWith("dev.uem.em") }.take(3)
                    if (frames.isEmpty()) continue
                    val k = frames.joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    counts.merge(k, 1, Int::plus)
                }
                Thread.sleep(1)
            }
        }.apply { isDaemon = true; start() }
        val t0 = System.currentTimeMillis()
        Advance.run(w, 40)
        run.set(false); sampler.join()
        val tot = counts.values.sum().coerceAtLeast(1)
        val sb = StringBuilder("40 days fast: ${System.currentTimeMillis() - t0} ms\n")
        for ((k, v) in counts.entries.sortedByDescending { it.value }.take(25)) sb.appendLine("  %5.1f%%  %s".format(v * 100.0 / tot, k))
        println(sb); java.io.File("build/speed-fast.txt").writeText(sb.toString())
    }
}

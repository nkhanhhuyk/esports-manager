package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import org.junit.Test

/** Profiling aid (UEM_SPEED=3): a stretch of a season with the default (full) match detail, where the time goes. */
class SeasonSpeedTest {
    @Test
    fun seasonProfile() {
        if (System.getenv("UEM_SPEED") != "3") return
        TestData.init(); BoxStore.clear()
        val days = (System.getenv("UEM_DAYS") ?: "60").toInt()
        // First the same stretch with every league on the full engines, for comparison.
        val head = StringBuilder()
        run {
            val wf = WorldGen.create(TestData.source, "seasonprof", "SP", Dates.day(2026, 11, 21))
            wf.simDetail = "full"
            Advance.run(wf, 5)
            val t0 = System.currentTimeMillis(); Advance.run(wf, days)
            head.appendLine("$days days, full detail: ${System.currentTimeMillis() - t0} ms")
            BoxStore.clear()
        }
        val w = WorldGen.create(TestData.source, "seasonprof", "SP", Dates.day(2026, 11, 21))
        // A one-game career: follow a LoL team (the balanced mode keeps the other games' leagues on the quick model).
        w.teams.firstOrNull { it.titleId == "lol" && it.active && it.leagueId >= 0 }?.let { w.followed.add(it.id) }
        head.append("detail: ${w.simDetail} (following a LoL team) · ")
        Advance.run(w, 5) // warm up
        Advance.timing.fill(0)
        val counts = HashMap<String, Int>()
        val tops = HashMap<String, Int>()
        val run = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (run.get()) {
                for ((th, st) in Thread.getAllStackTraces()) {
                    if (th === Thread.currentThread() || th.state != Thread.State.RUNNABLE) continue
                    val frames = st.filter { it.className.startsWith("dev.uem.em") }
                    if (frames.isEmpty()) continue
                    val k = frames.take(3).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    counts.merge(k, 1, Int::plus)
                    // Which top-level part of the day (the outermost engine / management call).
                    val outer = frames.lastOrNull { !it.className.contains("Advance") && !it.className.contains("Test") } ?: frames.last()
                    val inner = frames.firstOrNull { it.className.contains("MobaSim") || it.className.contains("QuickSim") || it.className.contains("Draft") || it.className.contains("Tactical") || it.className.contains("Shooter") }
                    tops.merge("${outer.className.substringAfterLast('.')}.${outer.methodName}" + (inner?.let { " / ${it.className.substringAfterLast('.')}" } ?: ""), 1, Int::plus)
                }
                Thread.sleep(1)
            }
        }.apply { isDaemon = true; start() }
        val t0 = System.currentTimeMillis()
        Advance.run(w, days)
        val ms = System.currentTimeMillis() - t0
        run.set(false); sampler.join()
        val games = w.fixtures.filter { it.done }.size
        val tot = counts.values.sum().coerceAtLeast(1)
        val sb = StringBuilder(head).append("$days days: $ms ms, $games series done; cores ${Runtime.getRuntime().availableProcessors()}\n")
        sb.appendLine("day parts (ms): " + listOf("start/patches", "fixtures", "daily", "weekly/monthly", "market/roles", "rollover").mapIndexed { i, n -> "$n ${Advance.timing[i] / 1_000_000}" }.joinToString(" · "))
        val tt = tops.values.sum().coerceAtLeast(1)
        sb.appendLine("by part:")
        for ((k, v) in tops.entries.sortedByDescending { it.value }.take(20)) sb.appendLine("  %5.1f%%  %s".format(v * 100.0 / tt, k))
        sb.appendLine("hot frames:")
        for ((k, v) in counts.entries.sortedByDescending { it.value }.take(40)) sb.appendLine("  %5.1f%%  %s".format(v * 100.0 / tot, k))
        println(sb); java.io.File("build/season-speed.txt").writeText(sb.toString())
    }
}

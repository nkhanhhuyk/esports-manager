package dev.uem.em

import dev.uem.em.comp.Invariants
import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import org.junit.Assume
import org.junit.Test

/** Exploratory: one season of all seven games, invariants printed (UEM_PROBE=1). */
class QaProbeTest {
    @Test
    fun probe() {
        Assume.assumeTrue(System.getenv("UEM_PROBE") != null)
        TestData.init()
        BoxStore.clear(); BoxStore.store = null
        val t0 = System.currentTimeMillis()
        val w = WorldGen.create(TestData.source, "qa-probe", "Probe", Dates.day(2026, 11, 21))
        w.simDetail = "fast"
        println("created in ${(System.currentTimeMillis() - t0) / 1000}s: titles ${w.titles}")
        val s = w.season
        while (w.season == s) {
            Advance.run(w, 30)
            val r = Invariants.world(w)
            Invariants.season(w, s, r = r)
            println("${Dates.format(w.day)} ${(System.currentTimeMillis() - t0) / 1000}s violations ${r.violations.size} warnings ${r.warnings.size}")
            for (v in r.violations.take(15)) println("  V $v")
            for (v in r.warnings.take(3)) println("  W $v")
        }
        val r = Invariants.season(w, s, boxScores = true)
        println("season end: ${r.counts}")
        for (v in r.violations.take(30)) println("  V $v")
    }
}

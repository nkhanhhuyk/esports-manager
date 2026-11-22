package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import org.junit.Test

/** Tuning aid (UEM_DUMP=1): leagues per title (tier, region, teams) and this season's competitions. */
class LeagueDumpTest {
    @Test
    fun dump() {
        if (System.getenv("UEM_DUMP") == null) return
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "dump", "D", Dates.day(2026, 11, 21))
        val sb = StringBuilder("season ${w.season}\n")
        for (l in w.leagues) sb.appendLine("${l.titleId} L${l.id} t${l.tier} ${l.short} '${l.name}' region=${l.region} teams=${l.teamIds.size} up=${l.upperId} low=${l.lowerId} real=${l.real}")
        for (c in w.comps) sb.appendLine("C${c.id} ${c.titleId} ${c.tier} ${c.short} '${c.name}' start=${Dates.short(c.startDay)} entrants=${c.entrants} stages=${c.stages.map { it.name + ":" + it.type }}")
        java.io.File("build/league-dump.txt").writeText(sb.toString())
    }
}

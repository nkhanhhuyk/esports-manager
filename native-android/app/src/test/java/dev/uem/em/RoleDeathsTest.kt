package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kills, deaths and assists by position over real league matches (full 2D engines). No position
 * may be much safer than the others: the top / EXP / Baron lane must die about as often as the rest.
 */
class RoleDeathsTest {
    @Test
    fun deathsSpreadOverPositions() {
        TestData.init()
        BoxStore.clear()
        val w = WorldGen.create(TestData.source, "roles", "Roles", Dates.day(2026, 11, 21))
        // Into the first split (mid-January): enough league games for every MOBA.
        Advance.run(w, 62)
        val out = StringBuilder()
        var worst = 0.0
        for (id in w.titles) {
            val t = Catalogue.get(id)
            if (!t.isMoba) continue
            val k = t.statIndex["k"] ?: continue
            val d = t.statIndex["d"] ?: continue
            val a = t.statIndex["a"] ?: continue
            val sums = HashMap<String, DoubleArray>()
            for (f in w.fixtures) {
                if (!f.done || f.titleId != id) continue
                val rec = BoxStore.get(w, f.id) ?: continue
                for (g in rec.games) for (l in g.lines) { val s = sums.getOrPut(l.roleId) { DoubleArray(4) }; s[0] += l.s[k]; s[1] += l.s[d]; s[2] += l.s[a]; s[3] += 1.0 }
            }
            if (sums.isEmpty()) continue
            val avgD = t.lineup.mapNotNull { sums[it] }.map { it[1] / it[3] }
            val mean = avgD.average()
            out.appendLine("${t.short} (${sums.values.first()[3].toInt()} player-games per role): " + t.lineup.joinToString(" | ") { r -> sums[r]?.let { s -> "%s K %.2f D %.2f A %.2f".format(t.role(r).short, s[0] / s[3], s[1] / s[3], s[2] / s[3]) } ?: "$r -" })
            // How far the safest non-support position is below the average deaths.
            for (r in t.lineup) {
                val s = sums[r] ?: continue
                if (t.role(r).isSupport) continue
                worst = maxOf(worst, (mean - s[1] / s[3]) / mean)
            }
        }
        println(out)
        java.io.File("build/role-deaths.txt").writeText(out.toString())
        assertTrue("a position dies far less than the average: ${"%.0f".format(worst * 100)} % under", worst < 0.30)
    }
}

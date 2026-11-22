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
        val topLow = ArrayList<String>()
        val othersHigh = ArrayList<String>()
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
            out.appendLine("${t.short} (${sums.values.first()[3].toInt()} player-games per role): " + t.lineup.joinToString(" | ") { r -> sums[r]?.let { s -> "%s K %.2f D %.2f A %.2f".format(t.role(r).short, s[0] / s[3], s[1] / s[3], s[2] / s[3]) } ?: "$r -" })
            // The top / EXP / Baron / Clash lane against the other non-support positions (Dota 2's
            // carry dying least is normal there, so Dota is reported only).
            if (id != "dota2") {
                val top = sums[t.lineup[0]] ?: continue
                val others = t.lineup.drop(1).filter { !t.role(it).isSupport }.mapNotNull { sums[it] }.map { it[1] / it[3] }
                // Relative to the other cores: before the first fix the top lane died about 30% as often as
                // them, after the second about 50%; solo laners contesting their wave, ganks and collapses that
                // finish a lone laner, and dives on low laners brought it to 70-85%.
                if (others.isNotEmpty() && (top[1] / top[3] < 0.6 * others.average() || top[1] / top[3] < 1.5)) topLow.add("${t.short} %.2f vs %.2f".format(top[1] / top[3], others.average()))
                if (others.isNotEmpty()) worst = maxOf(worst, (others.average() - top[1] / top[3]) / others.average())
                // The other positions must not die far more than real pro games show (about 2.5-3.5 a game).
                val all = t.lineup.mapNotNull { sums[it] }.map { it[1] / it[3] }
                if (all.any { it > 4.2 }) othersHigh.add("${t.short} " + all.joinToString("/") { "%.2f".format(it) })
            }
        }
        println(out)
        java.io.File("build/role-deaths.txt").writeText(out.toString())
        // Before the fix the top / EXP / Baron / Clash lane died 1.2-1.3 times a game in every MOBA (others
        // 3.5-5). Ganks spread over the lanes, laners join ganks, collapses on split-pushers, the top laner
        // grouping with the team and a front-line formation lifted it; it must not fall back.
        println("top lane vs other positions: ${"%.0f".format(worst * 100)} % fewer deaths")
        assertTrue("top-lane deaths per game fell back: $topLow", topLow.isEmpty())
        // Before the second fix mid, carries and supports died 4-5.4 times a game (LoL ~39 kills a game);
        // picking fights after laning, the front line shielding its carries and no tower dives brought it down.
        assertTrue("a position dies too often: $othersHigh", othersHigh.isEmpty())
    }
}

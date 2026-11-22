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
        w.simDetail = "full" // every league on the 2D engines
        // Into the first split (mid-January): enough league games for every MOBA.
        Advance.run(w, 62)
        val out = StringBuilder()
        var worst = 0.0
        val topLow = ArrayList<String>()
        val othersHigh = ArrayList<String>()
        val killOrder = ArrayList<String>()
        val mvpHeavy = ArrayList<String>()
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
            // Kills by position (starting meta: the bot-side carry): the carry most, mid second, the
            // support least (a few percent of slack: single seasons vary).
            run {
                fun kOf(pos: String) = t.lineup.filter { dev.uem.em.titles.RoleMeta.pos(it) == pos }.mapNotNull { sums[it] }.map { it[0] / it[3] }.maxOrNull() ?: 0.0
                val bot = kOf("bot"); val mid = kOf("mid"); val top = kOf("top"); val jng = kOf("jungle"); val sup = kOf("support")
                if (bot < mid * 0.95 || mid < maxOf(top, jng) || sup > minOf(top, jng)) killOrder.add("${t.short} bot %.2f mid %.2f top %.2f jng %.2f sup %.2f".format(bot, mid, top, jng, sup))
            }
            // MVPs and average game rating by position (who looks like they decide games).
            run {
                val mvps = HashMap<String, Int>(); val rating = HashMap<String, DoubleArray>()
                for (f in w.fixtures) {
                    if (!f.done || f.titleId != id) continue
                    val rec = BoxStore.get(w, f.id) ?: continue
                    for (g in rec.games) for (l in g.lines) rating.getOrPut(l.roleId) { DoubleArray(2) }.let { it[0] += l.rating; it[1] += 1.0 }
                    if (f.mvp >= 0) rec.games.flatMap { it.lines }.firstOrNull { it.playerId == f.mvp }?.let { mvps.merge(it.roleId, 1, Int::plus) }
                }
                val tm = mvps.values.sum().coerceAtLeast(1)
                // No position takes a third of the series MVPs (the top lane took 35% in LoL, 33% in Wild Rift).
                val most = t.lineup.maxByOrNull { mvps[it] ?: 0 }
                if (most != null && (mvps[most] ?: 0) * 1.0 / tm > 0.33 && tm >= 30) mvpHeavy.add("${t.short} ${t.role(most).short} ${(mvps[most] ?: 0) * 100 / tm}%")
                out.appendLine("  ${t.short} series MVP share: " + t.lineup.joinToString(" | ") { r -> "%s %.0f%%".format(t.role(r).short, (mvps[r] ?: 0) * 100.0 / tm) } +
                    " · rating: " + t.lineup.joinToString(" | ") { r -> rating[r]?.let { "%s %.2f".format(t.role(r).short, it[0] / it[1]) } ?: "" })
            }
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
        // Before the role metas the mid laner had the most kills everywhere (LoL ADC 2.0 vs mid 3.6).
        assertTrue("kills by position out of order: $killOrder", killOrder.isEmpty())
        assertTrue("one position takes most MVPs: $mvpHeavy", mvpHeavy.isEmpty())
    }
}

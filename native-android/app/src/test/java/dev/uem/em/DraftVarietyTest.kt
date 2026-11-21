package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.Draft
import dev.uem.em.sim.Patch
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI bans vary: overpowered picks only when the patch has them, comfort targeting, handshakes. */
class DraftVarietyTest {
    @Test
    fun bansVary() {
        val w = MobaBalanceTest.world()
        val sb = StringBuilder()
        for (id in listOf("mlbb", "wildrift", "hok", "lol", "dota2")) {
            val t = Catalogue.get(id)
            val patch = Patch.of(w, t)
            val teams = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }
            val count = HashMap<String, Int>()
            var drafts = 0
            for (i in 0 until 60) {
                val a = teams[i % teams.size]; val b = teams[(i * 7 + 3) % teams.size]
                if (a === b) continue
                val lineups = arrayOf(dev.uem.em.sim.Lineups.auto(w, a).filter { it >= 0 }.map { w.players[it] }, dev.uem.em.sim.Lineups.auto(w, b).filter { it >= 0 }.map { w.players[it] })
                val r = Draft.run(t, patch, lineups, arrayOf(a.style, b.style), i % 2, arrayOf(emptySet(), emptySet()), Rng.of("bans|$id|$i"))
                drafts++
                for (c in r.bans.toSet()) count.merge(c, 1, Int::plus)
            }
            val top = count.entries.sortedByDescending { it.value }.take(5)
            val line = "$id: $drafts drafts, ${count.size} different characters banned; most banned " + top.joinToString { "${t.charIndex[it.key]?.name}: ${it.value * 100 / drafts}%" }
            println(line); sb.appendLine(line)
            assertTrue("$id bans are too repetitive: $line", top.first().value * 100 / drafts < 96 && count.size >= 15 && top.getOrNull(4)?.let { it.value * 100 / drafts < 70 } != false)
        }
        java.io.File("build/draft-variety.txt").writeText(sb.toString())
    }
}

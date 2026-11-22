package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.PlayerCtx
import dev.uem.em.sim.TeamContexts
import dev.uem.em.sim.TeamCtx
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much each position decides a game: the same roster on both sides, one player made better
 * (+10 on every rating), and the win rate that gives — per position, per MOBA. No position should
 * decide games far more than the others (the top lane did: "it feels like top decides the matches").
 */
class PositionImpactTest {
    @Test
    fun positionsMatterAboutEqually() {
        val n = (System.getenv("UEM_IMPACT") ?: "0").toInt()
        if (n <= 0) return
        val w = MobaBalanceTest.world()
        val out = StringBuilder()
        val bad = ArrayList<String>()
        for (id in listOf("lol", "wildrift", "mlbb", "hok", "dota2")) {
            val t = Catalogue.get(id)
            val patch = Patch.of(w, t)
            val teams = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }.take(10)
            val rates = DoubleArray(5)
            for (pos in 0 until 5) {
                var wins = 0
                repeat(n) { i ->
                    val team = teams[i % teams.size]
                    val rng = Rng.of("impact|$id|$pos|$i")
                    val used = HashSet<String>()
                    val boosted = i % 2
                    val ctxs = (0..1).map { side ->
                        val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                        val chars = HashMap<Int, String>()
                        lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }; val c = opts[rng.int(0, opts.size - 1)]; used.add(c.id); chars[pid] = c.id } }
                        val c = TeamContexts.build(w, team, lineup, side, chars, null, 0.0, rng)
                        if (side != boosted) c else TeamCtx(c.team, c.idx, c.players.mapIndexed { k, pc ->
                            if (k != pos) pc else PlayerCtx(pc.p, pc.role, pc.charId, pc.char, pc.base, pc.mult, DoubleArray(pc.phase.size) { j -> (pc.phase[j] + 10).coerceAtMost(100.0) })
                        }, c.phase, c.overall, c.aggression)
                    }
                    val o = MobaEngine.simulate(t, ctxs, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), rng, patch, null, MobaEngine.Opts(world = w))
                    if (o.winner == boosted) wins++
                }
                rates[pos] = wins.toDouble() / n
            }
            out.appendLine("${t.short}: win rate with one player +10 — " + (0 until 5).joinToString(" | ") { "${t.role(t.lineup[it]).short} %.0f%%".format(rates[it] * 100) })
            // Edge over 50% by position: the top lane's must not be far above the average of the others.
            val edge = rates.map { it - 0.5 }
            val others = edge.drop(1).average()
            if (edge[0] > maxOf(0.08, others * 1.6)) bad.add("${t.short} top ${"%.2f".format(edge[0])} vs ${"%.2f".format(others)}")
        }
        println(out); java.io.File("build/position-impact.txt").writeText(out.toString())
        assertTrue("the top lane decides games far more than the others: $bad", bad.isEmpty())
    }
}

package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.Recorder
import dev.uem.em.sim.TacticalEngine
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue
import dev.uem.em.ui.Maps
import org.junit.Assume
import org.junit.Test

/** Where kills happen on one map (UEM_MAPTRACE=map id): killer and victim nearest callouts, by side. */
class MapTraceProbeTest {
    @Test
    fun trace() {
        val id = System.getenv("UEM_MAPTRACE"); Assume.assumeTrue(id != null)
        val w = MobaBalanceTest.world()
        val t = Catalogue.get(id!!.substringBefore(':'))
        val m = Maps.shooter(id)!!
        val all = w.teams.filter { it.titleId == t.id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { dev.uem.em.sim.Lineups.strength(w, it) }
        val where = HashMap<String, Int>()
        for (i in 0 until 6) {
            val rng = Rng.of("trace|$id|$i")
            val ctx = listOf(all[i % 8], all[(i + 3) % 8]).mapIndexed { s, team -> TeamContexts.build(w, team, dev.uem.em.sim.Lineups.auto(w, team), s, null, id, 0.0, rng) }
            val rec = Recorder()
            TacticalEngine.simulate(t, ctx, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), i % 2, rng, Patch.of(w, t), rec, id)
            val pids = rec.pids.toList(); val stride = pids.size + 1
            fun near(x: Float, y: Float) = m.nodes.minByOrNull { (it.pos.x - x) * (it.pos.x - x) + (it.pos.y - y) * (it.pos.y - y) }!!.let { "${it.sup} ${it.name}" }
            for (e in rec.events.filter { it.kind == "kill" }) {
                val st = rec.events.lastOrNull { it.kind == "round" && it.t <= e.t } ?: continue
                val k = rec.posT.indexOfLast { it <= e.t }.coerceAtLeast(0)
                fun at(pid: Int): String { val j = pids.indexOf(pid); if (j < 0) return "?"; val v = rec.pos[k * stride + j]; return near(MobaEngine.unitX(v) / 1000f, MobaEngine.unitY(v) / 1000f) }
                val side = if (e.team == st.team) "ATT kills" else "DEF kills"
                where.merge("$side: ${at(e.a)} -> ${at(e.b)} (t+${e.t - st.t}s)".replace(Regex("t\\+\\d+s"), "") , 1, Int::plus)
            }
        }
        for ((k, v) in where.entries.sortedByDescending { it.value }.take(25)) println("%4d  %s".format(v, k))
    }
}

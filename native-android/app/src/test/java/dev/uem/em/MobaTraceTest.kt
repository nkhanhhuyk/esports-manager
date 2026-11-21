package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.Recorder
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue
import org.junit.Test

/** Prints a minute-by-minute trace of one game per title (tuning aid; run with UEM_TRACE=1). */
class MobaTraceTest {
    @Test
    fun trace() {
        if (System.getenv("UEM_TRACE") == null) return
        val w = MobaBalanceTest.world()
        val out = StringBuilder()
        for (id in (System.getenv("UEM_TRACE_TITLES") ?: "mlbb").split(",")) {
            val t = Catalogue.get(id)
            val rng = Rng.of("trace|$id")
            val teams = (0..1).map { side ->
                val team = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }[side * 3]
                val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                val chars = HashMap<Int, String>(); val used = HashSet<String>()
                lineup.forEachIndexed { i, pid -> if (pid >= 0) { val c = t.characters.first { it.fits(t.lineupRoles[i]) && it.id !in used }; used.add(c.id); chars[pid] = c.id } }
                TeamContexts.build(w, team, lineup, side, chars, null, 0.0, rng)
            }
            val rec = Recorder()
            val dbg = StringBuilder()
            MobaEngine.debug = { dbg.appendLine("    $it") }
            val o = MobaEngine.simulate(t, teams, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), rng, Patch.of(w, t), rec)
            MobaEngine.debug = null
            out.append(dbg)
            out.appendLine("== $id winner ${o.winner} ${o.duration / 60}:${"%02d".format(o.duration % 60)} score ${o.score.toList()}")
            val byMin = rec.events.groupBy { it.t / 60 }
            for (m in 0..o.duration / 60) {
                val e = byMin[m] ?: emptyList()
                val k = e.filter { it.kind == "kill" }
                out.appendLine("  %2d' kills %d-%d (tower/obj kills %d, locs %s) fights %s towers %s objs %s casts %d recalls %d".format(m, k.count { it.team == 0 }, k.count { it.team == 1 }, k.count { it.a < 0 },
                    k.map { it.x }.groupingBy { it }.eachCount(), e.filter { it.kind == "fight" }.map { "${it.a}-${it.b}@${it.x}" }, e.filter { it.kind == "tower" || it.kind == "base" || it.kind == "core" }.map { "${it.team}:${it.kind}${it.x}/${it.a}" },
                    e.filter { it.kind == "obj" }.map { "${it.team}:${it.text}${it.x}" }, e.count { it.kind == "cast" }, e.count { it.kind == "recall" }))
            }
        }
        println(out)
        java.io.File("build/moba-trace.txt").writeText(out.toString())
    }
}

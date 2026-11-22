package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue
import org.junit.Test

/** Profiling aid (UEM_SPEED=1): where the MOBA engine spends its time, per title. */
class SpeedTest {
    @Test
    fun speed() {
        if (System.getenv("UEM_SPEED") == null) return
        val w = MobaBalanceTest.world()
        val sb = StringBuilder()
        for (id in listOf("mlbb", "wildrift", "hok", "lol", "dota2")) {
            val t = Catalogue.get(id)
            val teams = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }
            MobaEngine.profile = true
            MobaEngine.prof.fill(0)
            val t0 = System.nanoTime()
            val n = 40
            repeat(n) { i ->
                val rng = Rng.of("speed|$id|$i")
                val used = HashSet<String>()
                val ctxs = listOf(teams[i % teams.size], teams[(i + 1) % teams.size]).mapIndexed { side, team ->
                    val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                    val chars = HashMap<Int, String>()
                    lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }; val c = opts[rng.int(0, opts.size - 1)]; used.add(c.id); chars[pid] = c.id } }
                    TeamContexts.build(w, team, lineup, side, chars, null, 0.0, rng)
                }
                MobaEngine.simulate(t, ctxs, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), rng, Patch.of(w, t), null)
            }
            val total = (System.nanoTime() - t0) / 1e6
            val parts = MobaEngine.prof.sum().coerceAtLeast(1)
            sb.appendLine("%-8s %.1f ms/game · ".format(id, total / n) + MobaEngine.PROF_NAMES.indices.joinToString(" · ") { "${MobaEngine.PROF_NAMES[it]} ${MobaEngine.prof[it] * 100 / parts}%" })
            MobaEngine.profile = false
        }
        // Stack sampling: which engine lines are hot (LoL and Wild Rift).
        val me = Thread.currentThread()
        val counts = HashMap<String, Int>()
        val run = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (run.get()) {
                val st = me.stackTrace
                val f = st.firstOrNull { it.className.startsWith("dev.uem.em") }
                if (f != null) { val k = "${f.className.substringAfterLast('.')}.${f.methodName}:${f.lineNumber}"; synchronized(counts) { counts.merge(k, 1, Int::plus) } }
                Thread.sleep(0, 300_000)
            }
        }.apply { isDaemon = true; start() }
        for (id in listOf("wildrift", "lol")) {
            val t = Catalogue.get(id)
            val teams = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }
            repeat(40) { i ->
                val rng = Rng.of("speed2|$id|$i")
                val used = HashSet<String>()
                val ctxs = listOf(teams[i % teams.size], teams[(i + 1) % teams.size]).mapIndexed { side, team ->
                    val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                    val chars = HashMap<Int, String>()
                    lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }; val c = opts[rng.int(0, opts.size - 1)]; used.add(c.id); chars[pid] = c.id } }
                    TeamContexts.build(w, team, lineup, side, chars, null, 0.0, rng)
                }
                MobaEngine.simulate(t, ctxs, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), rng, Patch.of(w, t), null)
            }
        }
        run.set(false); sampler.join()
        val tot = counts.values.sum().coerceAtLeast(1)
        sb.appendLine("hot lines:")
        for ((k, v) in counts.entries.sortedByDescending { it.value }.take(30)) sb.appendLine("  %5.1f%%  %s".format(v * 100.0 / tot, k))
        println(sb); java.io.File("build/speed.txt").writeText(sb.toString())
    }
}

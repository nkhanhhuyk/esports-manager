package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue
import org.junit.Test

/** Sampling profile of the MOBA engine (tuning aid; run with UEM_PROFILE=1). */
class MobaProfileTest {
    @Test
    fun profile() {
        if (System.getenv("UEM_PROFILE") == null) return
        val w = MobaBalanceTest.world()
        val id = System.getenv("UEM_PROFILE_TITLE") ?: "mlbb"
        val t = Catalogue.get(id)
        val patch = Patch.of(w, t)
        val teamsAll = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }
        fun ctx(i: Int, side: Int, rng: Rng): dev.uem.em.sim.TeamCtx {
            val team = teamsAll[i]
            val lineup = dev.uem.em.sim.Lineups.auto(w, team)
            val chars = HashMap<Int, String>(); val used = HashSet<String>()
            lineup.forEachIndexed { k, pid -> if (pid >= 0) { val c = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }.let { it[(pid + k) % it.size] }; used.add(c.id); chars[pid] = c.id } }
            return TeamContexts.build(w, team, lineup, side, chars, null, 0.0, rng)
        }
        // Warm up (JIT, path fields).
        repeat(30) { val r = Rng.of("warm$it"); MobaEngine.simulate(t, listOf(ctx(it % 6, 0, r), ctx((it + 3) % 6, 1, r)), arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), r, patch) }
        val counts = HashMap<String, Int>()
        val main = Thread.currentThread()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                val st = main.stackTrace
                // Attribute the sample to the first engine method on the stack.
                val f = st.firstOrNull { it.className.contains("MobaSim") || it.className.contains("MobaMap") }
                if (f != null) counts.merge("${f.className.substringAfterLast('.')}.${f.methodName}", 1, Int::plus)
                Thread.sleep(1)
            }
        }
        sampler.start()
        val t0 = System.nanoTime()
        val n = 60
        repeat(n) { val r = Rng.of("prof$it"); MobaEngine.simulate(t, listOf(ctx(it % 6, 0, r), ctx((it + 3) % 6, 1, r)), arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), r, patch) }
        val ms = (System.nanoTime() - t0) / 1e6 / n
        running.set(false); sampler.join()
        val total = counts.values.sum()
        val out = StringBuilder("$id: %.1f ms/game after warm-up\n".format(ms))
        for ((k, v) in counts.entries.sortedByDescending { it.value }.take(30)) out.appendLine("%5.1f%%  %s".format(v * 100.0 / total, k))
        println(out); java.io.File("build/moba-profile.txt").writeText(out.toString())
    }
}

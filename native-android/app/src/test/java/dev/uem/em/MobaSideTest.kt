package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.Recorder
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue
import org.junit.Test

/**
 * Map-side balance (UEM_SIDE=mlbb,lol,… and UEM_SIDE_N to change the sample). Near-equal rosters
 * swap sides every game; reports how often each map side wins and where the sides differ (first
 * blood, first tower, towers per lane, objectives, gold at the end of laning), and fails when one
 * side wins far more than the other (it found red winning ~70 % before minion waves moved together).
 */
class MobaSideTest {
    @Test
    fun sides() {
        val ids = System.getenv("UEM_SIDE") ?: "mlbb,wildrift,hok,lol,dota2"
        val n = (System.getenv("UEM_SIDE_N") ?: "100").toInt()
        val fails = ArrayList<String>()
        val w = MobaBalanceTest.world()
        val sb = StringBuilder()
        for (id in ids.split(",")) {
            val t = Catalogue.get(id)
            val cfg = t.moba!!
            val teams = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { dev.uem.em.sim.Lineups.strength(w, it) }
            var side0 = 0; var fb0 = 0; var ft0 = 0; var fbN = 0; var ftN = 0; var fbWin = 0; var ftWin = 0
            val towers = Array(2) { IntArray(4) } // by lane of the destroyed tower: top/mid/bot/base
            val objs = Array(2) { HashMap<String, Int>() }
            val kills = IntArray(2); var lead0 = 0.0; var dur = 0.0
            repeat(n) { i ->
                val rng = Rng.of("side|$id|$i")
                val pair = listOf(teams[2 + (i / 2) % 4], teams[3 + (i / 2) % 4])
                val order = if (i % 2 == 0) pair else pair.reversed()
                val used = HashSet<String>()
                val ctxs = order.mapIndexed { side, team ->
                    val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                    val chars = HashMap<Int, String>()
                    lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }; val c = opts[rng.int(0, opts.size - 1)]; used.add(c.id); chars[pid] = c.id } }
                    TeamContexts.build(w, team, lineup, side, chars, null, 0.0, rng)
                }
                val rec = Recorder()
                val o = MobaEngine.simulate(t, ctxs, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), rng, Patch.of(w, t), rec)
                if (o.winner == 0) side0++
                dur += o.duration / 60.0
                kills[0] += o.score[0]; kills[1] += o.score[1]
                rec.events.firstOrNull { it.kind == "kill" && it.team >= 0 }?.let { fbN++; if (it.team == 0) fb0++; if (it.team == o.winner) fbWin++ }
                rec.events.firstOrNull { it.kind == "tower" }?.let { ftN++; if (it.team == 0) ft0++; if (it.team == o.winner) ftWin++ }
                for (e in rec.events) when (e.kind) {
                    "tower" -> towers[e.team.coerceIn(0, 1)][e.x.coerceIn(0, 2)]++
                    "base" -> towers[e.team.coerceIn(0, 1)][3]++
                    "obj" -> if (e.team in 0..1) { val name = cfg.objectives.getOrNull(e.x)?.id ?: "o${e.x}"; objs[e.team][name] = (objs[e.team][name] ?: 0) + 1 }
                }
                val le = (cfg.laneEnd * 60).toInt()
                val si = rec.snapT.indexOfLast { it <= le }
                if (si >= 0) { val np = rec.pids.size; var lead = 0; for (k in 0 until np) lead += rec.snap[(si * np + k) * rec.per] * (if (k < np / 2) 1 else -1); lead0 += lead }
            }
            sb.appendLine("$id  games $n  side 0 (blue) wins ${side0 * 100 / n}%%  length %.1f".format(dur / n))
            // Real games have a small side edge (Radiant in Dota 2, blue in LoL); 35–65 % is about 3 standard errors at 100 games.
            if (side0 * 100 / n !in 35..65) fails.add("$id: blue side wins ${side0 * 100 / n}%")
            sb.appendLine("   first blood by side 0 ${fb0 * 100 / maxOf(1, fbN)}% (FB team wins ${fbWin * 100 / maxOf(1, fbN)}%)  first tower by side 0 ${ft0 * 100 / maxOf(1, ftN)}% (FT team wins ${ftWin * 100 / maxOf(1, ftN)}%)")
            sb.appendLine("   kills  side0 %.1f  side1 %.1f   gold lead of side 0 at laning end %.0f".format(kills[0].toDouble() / n, kills[1].toDouble() / n, lead0 / n))
            for (s in 0..1) sb.appendLine("   side $s destroyed towers top/mid/bot/base: ${towers[s].joinToString("/") { "%.1f".format(it.toDouble() / n) }}   objectives: ${objs[s].toSortedMap().entries.joinToString { "${it.key} %.2f".format(it.value.toDouble() / n) }}")
        }
        println(sb); java.io.File("build/moba-side.txt").writeText(sb.toString())
        org.junit.Assert.assertTrue(fails.joinToString(), fails.isEmpty())
    }
}

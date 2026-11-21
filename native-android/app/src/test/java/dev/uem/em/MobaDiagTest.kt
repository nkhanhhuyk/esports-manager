package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.Recorder
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.Phase
import org.junit.Test

/** Tuning aid (UEM_DIAG=title): strong vs weak team details. */
class MobaDiagTest {
    @Test
    fun diag() {
        val id = System.getenv("UEM_DIAG") ?: return
        val w = MobaBalanceTest.world()
        val t = Catalogue.get(id)
        val teamsAll = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { dev.uem.em.sim.Lineups.strength(w, it) }
        val lo = teamsAll.size - 1
        val sb = StringBuilder()
        for (r in listOf(0, lo)) { val tm = teamsAll[r]; sb.appendLine("rank $r ${tm.name} elo ${tm.elo.toInt()} aggr ${tm.aggression} strength ${"%.1f".format(dev.uem.em.sim.Lineups.strength(w, tm))}") }
        var favWins = 0
        val agg = Array(2) { DoubleArray(6) }
        repeat(30) { i ->
            val rng = Rng.of("diag|$i")
            val ctxs = listOf(0, lo).mapIndexed { side, r ->
                val team = teamsAll[r]
                val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                val chars = HashMap<Int, String>(); val used = HashSet<String>()
                lineup.forEachIndexed { k, pid -> if (pid >= 0) { val c = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }.let { it[(i + k) % it.size] }; used.add(c.id); chars[pid] = c.id } }
                TeamContexts.build(w, team, lineup, side, chars, null, 0.0, rng)
            }
            if (i == 0) for ((s, c) in ctxs.withIndex()) for (pc in c.players) sb.appendLine("   side $s ${pc.roleId} ${pc.p.handle} ca ${pc.p.ca.toInt()} char ${pc.charId} cls ${pc.char?.cls} kit ${dev.uem.em.sim.Abilities.kit(pc.charId, pc.char).actives.joinToString("/") { it.name + ":" + dev.uem.em.sim.Abilities.AI[it.ai] }} LAN ${pc.phase[Phase.LANING.ordinal].toInt()} SKI ${pc.phase[Phase.SKIRMISH.ordinal].toInt()} TF ${pc.phase[Phase.TEAMFIGHT.ordinal].toInt()}")
            if (i == 0) for ((s, c) in ctxs.withIndex()) sb.appendLine("side $s overall ${"%.1f".format(c.overall)} " + listOf(Phase.LANING, Phase.SKIRMISH, Phase.TEAMFIGHT, Phase.MACRO, Phase.JUNGLE, Phase.CLUTCH).joinToString { "${it.name.take(4)} ${"%.0f".format(c.phase[it.ordinal])}" })
            val rec = Recorder()
            val o = MobaEngine.simulate(t, ctxs, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), rng, Patch.of(w, t), rec)
            if (o.winner == 0) favWins++
            for (s in 0..1) {
                val lines = o.book.lines.filter { it.team == s }
                agg[s][0] += o.score[s].toDouble()
                agg[s][1] += lines.sumOf { it.s[t.statIndex["gold"]!!] }
                agg[s][2] += lines.sumOf { it.s[t.statIndex["cs"]!!] }
                agg[s][3] += lines.sumOf { it.s[t.statIndex["dmg"]!!] }
                agg[s][4] += rec.events.count { it.kind == "tower" && it.team == s }.toDouble()
                agg[s][5] += rec.events.count { it.kind == "obj" && it.team == s }.toDouble()
            }
        }
        sb.appendLine("favourite (side 0) wins $favWins/30")
        for (s in 0..1) sb.appendLine("side $s avg kills %.1f gold %.0f cs %.0f dmg %.0f towers %.1f objs %.1f".format(agg[s][0] / 30, agg[s][1] / 30, agg[s][2] / 30, agg[s][3] / 30, agg[s][4] / 30, agg[s][5] / 30))
        println(sb); java.io.File("build/moba-diag.txt").writeText(sb.toString())
    }
}

package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.Patch
import dev.uem.em.sim.Recorder
import dev.uem.em.sim.TacticalEngine
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seeded checks of the positional shooter rounds (Valorant, CS2) on every map of the pool. */
class ShooterBalanceTest {
    @Test
    fun balance() {
        val w = MobaBalanceTest.world()
        val n = (System.getenv("UEM_BALANCE_N") ?: "40").toInt()
        val sb = StringBuilder(); val fails = ArrayList<String>()
        for (id in listOf("valorant", "cs2")) {
            val t = Catalogue.get(id)
            val all = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { dev.uem.em.sim.Lineups.strength(w, it) }
            var games = 0; var favWins = 0; var rounds = 0; var kills = 0; var atkWins = 0; var plants = 0; var defuses = 0; var util = 0; var ms = 0L; var ot = 0
            for (i in 0 until n) {
                val rng = Rng.of("shoot|$id|$i")
                val fav = i % 2
                val pair = if (fav == 0) listOf(all[0], all.last()) else listOf(all.last(), all[0])
                val ctx = pair.mapIndexed { s, team ->
                    val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                    val chars = HashMap<Int, String>(); val used = HashSet<String>()
                    if (t.characters.isNotEmpty()) lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }.ifEmpty { t.characters.filter { it.id !in used } }; val c = opts[rng.int(0, opts.size - 1)]; used.add(c.id); chars[pid] = c.id } }
                    TeamContexts.build(w, team, lineup, s, chars, null, 0.0, rng)
                }
                val map = t.maps[i % t.maps.size].id
                val rec = Recorder()
                val t0 = System.nanoTime()
                val out = TacticalEngine.simulate(t, ctx, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), i % 2, rng, Patch.of(w, t), rec, map)
                ms += (System.nanoTime() - t0) / 1_000_000
                games++; if (out.winner == fav) favWins++
                val r = out.score.sum(); rounds += r; if (r > 24) ot++
                kills += rec.events.count { it.kind == "kill" }
                assertEquals("$id kills in the record match the box score", out.book.lines.sumOf { it.s[t.statIndex["k"]!!] }.toInt(), rec.events.count { it.kind == "kill" })
                assertEquals(r, rec.events.count { it.kind == "roundEnd" })
                for (e in rec.events) if (e.kind == "roundEnd") { val start = rec.events.lastOrNull { it.kind == "round" && it.t <= e.t }; if (start != null && e.team == start.team) atkWins++ }
                plants += rec.events.count { it.kind == "plant" }; defuses += rec.events.count { it.kind == "defuse" }; util += rec.events.count { it.kind == "util" }
            }
            val line = "%-8s games %d  rounds/game %.1f (OT %d)  kills/round %.2f  attack wins %d%%  plants %d%% of rounds  defuses %d%% of plants  utility/round %.1f  favourite wins %d%%  %.1f ms/game".format(
                id, games, rounds.toDouble() / games, ot, kills.toDouble() / rounds, atkWins * 100 / rounds, plants * 100 / rounds, if (plants > 0) defuses * 100 / plants else 0, util.toDouble() / rounds, favWins * 100 / games, ms.toDouble() / games)
            println(line); sb.appendLine(line)
            if (kills.toDouble() / rounds !in 5.0..9.5) fails.add("$id kills per round")
            if (atkWins * 100 / rounds !in 38..60) fails.add("$id attack win rate")
            if (favWins * 100 / games < 65) fails.add("$id strongest roster should beat the weakest")
            if (plants * 100 / rounds !in 25..80) fails.add("$id plant rate")
        }
        java.io.File("build/shooter-balance.txt").writeText(sb.toString())
        assertTrue(fails.joinToString(), fails.isEmpty())
    }
}

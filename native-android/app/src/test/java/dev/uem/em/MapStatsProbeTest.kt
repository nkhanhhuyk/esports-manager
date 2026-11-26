package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.Patch
import dev.uem.em.sim.Recorder
import dev.uem.em.sim.TacticalEngine
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue
import org.junit.Assume
import org.junit.Test
import java.io.File

/** Per-map round statistics on fixed seeds (UEM_MAPSTATS=games per map): before/after comparisons of map changes. */
class MapStatsProbeTest {
    @Test
    fun perMap() {
        val n = System.getenv("UEM_MAPSTATS")?.toIntOrNull()
        Assume.assumeTrue(n != null)
        val w = MobaBalanceTest.world()
        val sb = StringBuilder()
        for (id in listOf("valorant", "cs2")) {
            val t = Catalogue.get(id)
            val all = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { dev.uem.em.sim.Lineups.strength(w, it) }
            for (md in t.maps) {
                var rounds = 0; var atk = 0; var plants = 0; var defuses = 0; var kills = 0; var ms = 0L; var timeouts = 0; var atkDeathsApproach = 0; var atkDeaths = 0; var endT = 0
                for (i in 0 until n!!) {
                    val rng = Rng.of("mapstats|${md.id}|$i")
                    val pair = listOf(all[i % 8], all[(i + 3) % 8])
                    val ctx = pair.mapIndexed { s, team ->
                        val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                        val chars = HashMap<Int, String>(); val used = HashSet<String>()
                        if (t.characters.isNotEmpty()) lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }.ifEmpty { t.characters.filter { it.id !in used } }; val c = opts[rng.int(0, opts.size - 1)]; used.add(c.id); chars[pid] = c.id } }
                        TeamContexts.build(w, team, lineup, s, chars, md.id, 0.0, rng)
                    }
                    val rec = Recorder()
                    val t0 = System.nanoTime()
                    val o = TacticalEngine.simulate(t, ctx, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), i % 2, rng, Patch.of(w, t), rec, md.id)
                    ms += (System.nanoTime() - t0) / 1_000_000
                    rounds += o.score.sum()
                    for (e in rec.events) if (e.kind == "roundEnd") {
                        val st = rec.events.lastOrNull { it.kind == "round" && it.t <= e.t } ?: continue
                        if (e.team == st.team) atk++
                        val inR = rec.events.filter { it.t >= st.t && it.t <= e.t }
                        val atkDead = inR.count { it.kind == "kill" && it.team != st.team }
                        atkDeaths += atkDead; atkDeathsApproach += inR.count { it.kind == "kill" && it.team != st.team && it.m == dev.uem.em.sim.ReplayCodec.LOC_APPROACH }
                        if (e.team != st.team && atkDead < 5 && inR.none { it.kind == "plant" }) timeouts++
                        endT += e.t - st.t
                    }
                    plants += rec.events.count { it.kind == "plant" }; defuses += rec.events.count { it.kind == "defuse" }; kills += rec.events.count { it.kind == "kill" }
                }
                sb.appendLine("%-16s rounds %4d  attack wins %2d%%  plants %2d%%  defuses %2d%% of plants  kills/round %.2f  lost on time %2d%%  attacker deaths on the approach %2d%%  round %ds  %.0f ms/game".format(md.id, rounds, atk * 100 / rounds, plants * 100 / rounds, if (plants > 0) defuses * 100 / plants else 0, kills.toDouble() / rounds, timeouts * 100 / rounds, if (atkDeaths > 0) atkDeathsApproach * 100 / atkDeaths else 0, endT / rounds, ms.toDouble() / n))
            }
        }
        println(sb); File("build/map-stats.txt").writeText(sb.toString())
    }
}

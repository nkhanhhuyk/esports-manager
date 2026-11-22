package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.Recorder
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue
import org.junit.Test
import kotlin.math.max

/** Tuning aid (UEM_LANES=title): where and when each position dies, ganks and collapses per lane. */
class LaneDiagTest {
    @Test
    fun lanes() {
        val id = System.getenv("UEM_LANES") ?: return
        val w = MobaBalanceTest.world()
        val t = Catalogue.get(id)
        val teams = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }
        MobaEngine.dbg = java.util.concurrent.atomic.AtomicIntegerArray(60)
        val laneEnd = 14.0
        val deaths = HashMap<String, IntArray>() // role -> [early, late, top, mid, bot, jungle/other, objective]
        val n = 60
        var fights = 0; var fightKills = 0; var bigFights = 0; var aces = 0; var totalKills = 0
        repeat(n) { i ->
            val rng = Rng.of("lanes|$i")
            val a = teams[i % teams.size]; val b = teams[(i * 7 + 3) % teams.size].let { if (it == a) teams[(i + 1) % teams.size] else it }
            val roleOf = HashMap<Int, String>()
            val patch = Patch.of(w, t)
            val lineups = listOf(a, b).map { dev.uem.em.sim.Lineups.auto(w, it) }
            for ((s2, lu) in lineups.withIndex()) lu.forEachIndexed { k, pid -> if (pid >= 0) roleOf[pid] = t.lineupRoles[k].id }
            val players = Array(2) { s2 -> lineups[s2].filter { it >= 0 }.map { w.players[it] } }
            val drafted = System.getenv("UEM_DRAFT") != null
            val d = if (drafted) dev.uem.em.sim.DraftSession(t, patch, players, arrayOf(a.style, b.style), 0, arrayOf(emptySet(), emptySet()), rng).runAll() else null
            val ctxs = listOf(a, b).mapIndexed { side, team ->
                val lineup = lineups[side]
                val chars = HashMap<Int, String>(); val used = HashSet<String>()
                if (d != null) chars.putAll(d.assign[side])
                else lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }; val c = opts[(i * 13 + k * 5 + side) % opts.size]; used.add(c.id); chars[pid] = c.id } }
                TeamContexts.build(w, team, lineup, side, chars, null, 0.0, rng)
            }
            val evals = if (d == null) arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL) else Array(2) { si -> dev.uem.em.sim.Draft.evaluate(t, patch, lineups[si].filter { it >= 0 }.mapNotNull { pid -> d.assign[si][pid]?.let { t.charIndex[it] } }, d.picks[1 - si].mapNotNull { t.charIndex[it] }, players[si], listOf(a, b)[si].style) }
            if (d != null && i < 3) println("comp split: ${evals[0].profile["split"]} / ${evals[1].profile["split"]}; top picks ${t.lineup.indices.firstOrNull()?.let { k -> lineups.map { lu -> d.assign.flatMap { it.entries }.firstOrNull { e -> e.key == lu[k] }?.value } }}")
            val rec = Recorder()
            MobaEngine.simulate(t, ctxs, evals, rng, patch, rec)
            for (e in rec.events) if (e.kind == "fight") { fights++; val k = e.a + e.b; fightKills += k; if (k >= 5) bigFights++; if (e.a >= 5) aces++ }
            for (e in rec.events) if (e.kind == "kill") totalKills++
            for (e in rec.events) if (e.kind == "kill") {
                val r = roleOf[e.b] ?: continue
                val d = deaths.getOrPut(r) { IntArray(7) }
                if (e.t / 60.0 < laneEnd) d[0]++ else d[1]++
                when { e.x >= 10 -> d[6]++; e.x in 0..2 -> d[2 + e.x]++; else -> d[5]++ }
            }
        }
        val g = MobaEngine.dbg!!
        val sb = StringBuilder("${t.short}: $n games; ganks top/mid/bot ${g[0]}/${g[1]}/${g[2]}; collapses top/bot ${g[3]}/${g[5]}; gank arrivals top/mid/bot ${g[6]}/${g[7]}/${g[8]}; laner-vs-laner ticks top/mid/bot ${g[9]}/${g[10]}/${g[11]}\n")
        sb.appendLine("  late deaths by heroes in combat nearby (1..9+): " + (13..21).joinToString(" ") { "${it - 12}:${g[it]}" } + "; victim task farm/-/push/objective/home/hunt: " + (22..27).joinToString("/") { "${g[it]}" } + "; hunted ${g[28]}; victim team plan farm/push/objective ${g[29]}/${g[30]}/${g[31]}")
        sb.appendLine("  early jungler deaths: near enemy tower ${g[32]}; ganking/jungling/other ${g[33]}/${g[34]}/${g[35]}; enemy heroes involved 0/1/2/3+ ${g[36]}/${g[37]}/${g[38]}/${g[39]}")
        sb.appendLine("  late deaths by lineup slot: alone ${(40..44).map { g[it] }}; 3+ enemies hitting ${(45..49).map { g[it] }}; while farming ${(50..54).map { g[it] }}")
        sb.appendLine("  collapses: hunted top laners killed ${g[56]}, other hunted killed ${g[57]}, hunters killed while hunting ${g[58]} (collapses top/bot ${g[3]}/${g[5]})")
        sb.appendLine("  kills/game %.1f; fights/game %.1f with %.1f kills each (%.1f per game in fights); fights with 5+ kills %d, aces %d".format(totalKills / n.toDouble(), fights / n.toDouble(), fightKills / max(1.0, fights.toDouble()), fightKills / n.toDouble(), bigFights, aces))
        for (r in t.lineup) { val d = deaths[r] ?: IntArray(7); sb.appendLine("  ${t.role(r).short}: deaths/game %.2f (early %.2f late %.2f) where top %d mid %d bot %d other %d objective %d".format((d[0] + d[1]) / n.toDouble() / 2, d[0] / n.toDouble() / 2, d[1] / n.toDouble() / 2, d[2], d[3], d[4], d[5], d[6])) }
        println(sb); java.io.File("build/lane-diag.txt").writeText(sb.toString())
        MobaEngine.dbg = null
    }
}

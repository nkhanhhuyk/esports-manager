package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.Abilities
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue
import org.junit.Test

/** Character win rates over random games (tuning aid; UEM_CHARS=title[,n]). */
class MobaCharBalanceTest {
    @Test
    fun chars() {
        val arg = System.getenv("UEM_CHARS") ?: return
        val id = arg.substringBefore(',')
        val n = arg.substringAfter(',', "300").toInt()
        val w = MobaBalanceTest.world()
        val t = Catalogue.get(id)
        val pool = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }
        val games = HashMap<String, Int>(); val wins = HashMap<String, Int>()
        val roleWins = HashMap<String, Int>()
        for (i in 0 until n) {
            val rng = Rng.of("chars|$id|$i")
            val ts = pool.shuffled(java.util.Random(i.toLong())).take(2)
            val used = HashSet<String>()
            val picks = Array(2) { HashMap<Int, String>() }
            val ctx = ts.mapIndexed { side, team ->
                val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }; val c = opts[rng.int(0, opts.size - 1)]; used.add(c.id); picks[side][pid] = c.id } }
                TeamContexts.build(w, team, lineup, side, picks[side], null, 0.0, rng)
            }
            val o = MobaEngine.simulate(t, ctx, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), rng, Patch.of(w, t))
            for (s in 0..1) for ((_, c) in picks[s]) { games.merge(c, 1, Int::plus); if (o.winner == s) wins.merge(c, 1, Int::plus) }
            for (s in 0..1) for (pc in ctx[s].players) if (o.winner == s) roleWins.merge(pc.roleId, 1, Int::plus)
        }
        val rows = games.entries.filter { it.value >= 8 }.map { (c, g) -> Triple(c, g, (wins[c] ?: 0) * 100 / g) }.sortedBy { it.third }
        val sb = StringBuilder("$id: $n games, ${rows.size} characters with 8+ games\n")
        fun line(r: Triple<String, Int, Int>): String {
            val ch = t.charIndex[r.first]
            val kit = Abilities.kit(r.first, ch)
            return "%3d%% (%3d) %-24s %-10s %s".format(r.third, r.second, ch?.name ?: r.first, ch?.cls ?: "", kit.actives.joinToString(" | ") { a -> a.name.take(14) + ":" + Abilities.AI[a.ai] + ":" + a.fx.joinToString(",") { Abilities.FX_NAMES[it.k] + (if (it.k == 0) "%.1f".format(it.p) else "") } })
        }
        sb.appendLine("-- lowest"); rows.take(12).forEach { sb.appendLine(line(it)) }
        sb.appendLine("-- highest"); rows.takeLast(12).forEach { sb.appendLine(line(it)) }
        val wr = rows.map { it.third }
        sb.appendLine("spread: p10 ${wr[wr.size / 10]} median ${wr[wr.size / 2]} p90 ${wr[wr.size * 9 / 10]}")
        println(sb); java.io.File("build/moba-chars-$id.txt").writeText(sb.toString())
    }
}

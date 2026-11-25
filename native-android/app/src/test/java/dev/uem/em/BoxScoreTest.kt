package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.CompTier
import dev.uem.em.model.Fixture
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.sim.SeriesSim
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pro-play box scores: kills follow what the champions are (a tank top laner does not farm kills even
 * in a top-lane carry meta), deaths spread over a losing team instead of piling on its support or carry,
 * and no position tops the ratings because of the meta.
 */
class BoxScoreTest {
    private class Agg { var g = 0; var k = 0.0; var d = 0.0; var r = 0.0; var big = 0 }

    private fun measure(w: World, id: String, games: Int, out: StringBuilder, problems: MutableList<String>, label: String) {
        val t = Catalogue.get(id)
        val comp = w.comps.first { it.titleId == id && it.tier == CompTier.LEAGUE && it.circuit.isBlank() }
        val teams = w.teams.filter { it.titleId == id && it.active && it.kind.isEmpty() && it.nationalOf.isEmpty() && it.parentId < 0 && it.roster.size >= t.teamSize }.sortedByDescending { it.elo }.take(16)
        val k = t.statIndex["k"]!!; val d = t.statIndex["d"]!!
        val win = HashMap<String, Agg>(); val lose = HashMap<String, Agg>()
        val tankTop = Agg(); val otherTop = Agg()
        var n = 0
        val warm = games
        for (i in 0 until warm + games) {
            val a = teams[i % teams.size]; val b = teams[(i * 7 + 3) % teams.size]
            if (a.id == b.id) continue
            val f = Fixture(-(5_000_000 + label.hashCode().and(0xffff) * 1000 + i), comp.id, 0, id, w.day, a.id, b.id, 1)
            val o = SeriesSim.run(w, f, arrayOf(Lineups.ensure(w, a).toList(), Lineups.ensure(w, b).toList()))
            // As a season applies results: the rating calibration follows (warm-up games only feed it).
            dev.uem.em.sim.StatBook.calibrate(w, t, o.record.games)
            if (i < warm) continue
            for (g in o.record.games) {
                n++
                for (l in g.lines) {
                    val m = (if (l.team == g.winner) win else lose).getOrPut(l.roleId) { Agg() }
                    m.g++; m.k += l.s[k]; m.d += l.s[d]; m.r += l.rating
                    if (l.s[d] >= (if (id == "dota2") 10 else 7)) m.big++
                    if (dev.uem.em.titles.RoleMeta.pos(l.roleId) == "top") {
                        val cls = t.charIndex[l.charId]?.cls?.lowercase() ?: ""
                        val tk = if (Regex("tank|guardian").containsMatchIn(cls)) tankTop else otherTop
                        tk.g++; tk.k += l.s[k]
                    }
                }
            }
        }
        out.appendLine("$id [$label] $n games")
        for (role in t.lineup) {
            val wa = win[role] ?: continue; val la = lose[role] ?: continue
            out.appendLine("  %-6s winners K %.1f D %.1f · losers K %.1f D %.1f · ${if (id == "dota2") 10 else 7}+ deaths in a game: winners %d%% losers %d%% · rating %.2f".format(
                t.role(role).short, wa.k / wa.g, wa.d / wa.g, la.k / la.g, la.d / la.g, wa.big * 100 / wa.g, la.big * 100 / la.g, (wa.r + la.r) / (wa.g + la.g)))
            // Dota 2 games run long with about 55 kills (as in pro Dota): 10+ deaths on a losing side is common there.
            // Supports die most in pro play too: a little more room for them than for the other positions.
            val cap = if (id == "dota2") 50 else if (t.role(role).isSupport) 16 else 14
            if (la.big * 100 / la.g > cap) problems.add("$id [$label] ${t.role(role).short}: ${la.big * 100 / la.g}% of losing games with many deaths")
        }
        val ratings = t.lineup.mapNotNull { r -> val wa = win[r]; val la = lose[r]; if (wa == null || la == null) null else r to (wa.r + la.r) / (wa.g + la.g) }
        val hi = ratings.maxBy { it.second }; val lo = ratings.minBy { it.second }
        if (hi.second - lo.second > 0.09) problems.add("$id [$label]: ${t.role(hi.first).short} rated %.2f, ${t.role(lo.first).short} %.2f".format(hi.second, lo.second))
        if (tankTop.g >= 20 && otherTop.g >= 20) {
            out.appendLine("  top lane kills: on tanks %.2f (%d games), on others %.2f (%d)".format(tankTop.k / tankTop.g, tankTop.g, otherTop.k / otherTop.g, otherTop.g))
            if (label == "top-carry" && tankTop.k / tankTop.g > otherTop.k / otherTop.g * 0.8) problems.add("$id [$label]: tank top laners take nearly as many kills as carries")
        }
    }

    @Test
    fun boxScoresLookLikeProPlay() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "boxscores", "B", Dates.day(2026, 11, 21))
        val out = StringBuilder(); val problems = ArrayList<String>()
        val n = System.getenv("UEM_BOX_GAMES")?.toIntOrNull() ?: 200
        for (id in listOf("mlbb", "wildrift", "hok", "lol", "dota2")) measure(w, id, n, out, problems, "start")
        // A top-lane carry meta: the kills go to top laners on carries, not to tanks.
        for (id in listOf("lol", "mlbb")) {
            val before = w.roleMetas[id]
            w.roleMetas[id] = "top-carry"
            try { measure(w, id, n, out, problems, "top-carry") } finally { if (before == null) w.roleMetas.remove(id) else w.roleMetas[id] = before; w.ratingCal.remove(id) }
        }
        println(out)
        java.io.File("build/box-scores.txt").writeText(out.toString())
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}

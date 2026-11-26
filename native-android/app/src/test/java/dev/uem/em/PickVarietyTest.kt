package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.Fixture
import dev.uem.em.sim.Lineups
import dev.uem.em.sim.SeriesSim
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picks change within a series: VALORANT agents follow the map (its compositions, the flex player's class),
 * MOBA picks follow bans, counters and what the earlier games showed. Measured as the share of later games
 * in which a player plays the same character as in game 1.
 */
class PickVarietyTest {
    @Test
    fun picksChangeAcrossASeries() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "variety", "V", Dates.day(2026, 11, 21))
        val out = StringBuilder()
        for (title in listOf("valorant", "lol", "mlbb", "hok", "wildrift", "dota2")) {
            val teams = w.teams.filter { it.titleId == title && it.active && it.parentId < 0 && it.nationalOf.isEmpty() && it.kind.isEmpty() && it.leagueId >= 0 }.sortedByDescending { it.elo }.take(16)
            var same = 0; var total = 0; var series = 0
            val perPlayer = ArrayList<Int>()
            for (k in 0 until 24) {
                val a = teams[k % teams.size]; val b = teams[(k * 7 + 3) % teams.size]
                if (a.id == b.id) continue
                val comp = w.comps.first { it.titleId == title && it.leagueId == a.leagueId }
                val f = Fixture(w.fixtures.size + 100_000 + k, comp.id, 0, title, w.day, a.id, b.id, 3)
                val o = SeriesSim.run(w, f, arrayOf(Lineups.ensure(w, a).toList(), Lineups.ensure(w, b).toList()))
                if (o.record.games.size < 2) continue
                series++
                val first = o.record.games[0].lines.associate { it.playerId to it.charId }
                val byPlayer = HashMap<Int, HashSet<String>>()
                for (g in o.record.games) for (l in g.lines) byPlayer.getOrPut(l.playerId) { HashSet() }.add(l.charId)
                perPlayer.addAll(byPlayer.values.map { it.size })
                for (g in o.record.games.drop(1)) for (l in g.lines) { total++; if (first[l.playerId] == l.charId) same++ }
            }
            val rate = same.toDouble() / maxOf(1, total)
            out.appendLine("$title: $series series, same character as game 1 in %.0f%% of later games, %.2f different characters per player per series".format(rate * 100, perPlayer.average()))
            if (title == "valorant") assertTrue("VALORANT agents follow the map: %.2f".format(rate), rate < 0.6)
        }
        println(out)
    }
}

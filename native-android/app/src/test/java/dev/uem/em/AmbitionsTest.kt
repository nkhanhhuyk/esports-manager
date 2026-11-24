package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Ambitions
import dev.uem.em.mgmt.Extras
import dev.uem.em.model.CompTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Player ambitions and the market they drive: weights sum to 100 and are reproducible; a team's fit
 * follows salary and a starting place; a poor fit is refused; nobody is sold on within months of
 * joining; players move (loyal ones stay longer); expensive failures are blown up; good second-division
 * players move up; national teams pick top-division players over comparable second-division ones; event
 * MVPs come from teams that went deep.
 */
class AmbitionsTest {
    @Test
    fun ambitionsDriveTheMarket() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "ambition", "A", Dates.day(2026, 11, 21))
        val out = StringBuilder()
        Ambitions.ensure(w)
        for (p in w.players) assertEquals(100, p.ambitions.sum())
        val p0 = w.players.first { it.teamId >= 0 }
        assertTrue("reproducible", Ambitions.generate(w, p0).contentEquals(p0.ambitions))
        assertTrue("organisations have a strategy", w.orgs.all { it.strategy in Ambitions.STRATEGIES })

        // Fit follows money (for a money-minded player) and a starting place.
        val moneyMan = w.players.filter { it.teamId >= 0 && !it.retired }.maxByOrNull { Ambitions.weight(w, it, "money") }!!
        val team = w.teams[moneyMan.teamId]
        val low = Ambitions.fit(w, moneyMan, team, moneyMan.salary * 0.5).total
        val high = Ambitions.fit(w, moneyMan, team, moneyMan.salary * 2.0).total
        assertTrue("more money fits a money-minded player better ($low → $high)", high > low + 5)
        val weak = w.teams.filter { it.titleId == moneyMan.titleId && it.active && it.kind.isEmpty() && it.nationalOf.isEmpty() }.minByOrNull { it.elo }!!
        val strong = w.teams.filter { it.titleId == moneyMan.titleId && it.active && it.kind.isEmpty() && it.nationalOf.isEmpty() }.maxByOrNull { it.elo }!!
        val winner = w.players.filter { it.titleId == moneyMan.titleId && !it.retired }.maxByOrNull { Ambitions.weight(w, it, "winning") }!!
        assertTrue("a winner prefers the strongest team", Ambitions.fit(w, winner, strong, 50_000.0).parts[0] > Ambitions.fit(w, winner, weak, 50_000.0).parts[0])
        // A poor fit with a token salary is refused; the same offer at a fitting team is not refused for money reasons alone.
        val star = w.players.filter { it.titleId == strong.titleId && it.teamId == strong.id }.maxByOrNull { it.ca }!!
        assertNotNull("a star turns down a weak team's token offer", Ambitions.refusal(w, star, weak, star.salary * 0.3, false))
        assertNull("…but stays happy enough to renew at his strong club on his salary", Ambitions.refusal(w, star, strong, star.salary * 1.1, true))

        // Three seasons of markets.
        val start = w.season
        while (w.season < start + 3) Advance.run(w, 30)
        // No flipping: nobody joined by a transfer and was transferred again within the protection period.
        val byPlayer = w.transfers.filter { it.kind == "transfer" }.groupBy { it.playerId }
        var flips = 0
        for ((_, list) in byPlayer) for (i in 1 until list.size) if (list[i].day - list[i - 1].day < 60) flips++
        out.appendLine("transfers ${w.transfers.count { it.kind == "transfer" }}, quick re-sales $flips")
        assertEquals("players sold on within two months of joining", 0, flips)
        // Movement and tenure.
        val pros = w.players.filter { !it.retired && it.teamId >= 0 && w.teams[it.teamId].kind.isEmpty() && w.teams[it.teamId].nationalOf.isEmpty() }
        fun tenure(p: dev.uem.em.model.Player) = (w.day - p.joinedDay) / 365.0
        val loyal = pros.filter { Ambitions.weight(w, it, "loyalty") >= 25 }; val fickle = pros.filter { Ambitions.weight(w, it, "loyalty") <= 6 }
        out.appendLine("tenure: loyal %.2f y (%d), fickle %.2f y (%d)".format(loyal.map(::tenure).average(), loyal.size, fickle.map(::tenure).average(), fickle.size))
        assertTrue("loyal players stay longer", loyal.map(::tenure).average() > fickle.map(::tenure).average())
        val moved = pros.count { p -> p.career.count { it.type in setOf("transferred", "signed", "traded") && it.season >= start } >= 1 }
        out.appendLine("players who moved since $start: $moved of ${pros.size}")
        assertTrue("players move", moved > pros.size / 5)
        // Blow-ups and second-division players moving up.
        val blowUps = w.news.count { it.title.endsWith("blow up the roster") }
        out.appendLine("roster blow-ups: $blowUps")
        assertTrue("some expensive failures are blown up", blowUps >= 1)
        fun tier(teamId: Int) = w.teams.getOrNull(teamId)?.let { t -> if (t.parentId >= 0) 3 else w.leagues.getOrNull(t.leagueId)?.tier ?: 3 } ?: 9
        val upward = w.transfers.count { it.kind == "transfer" && it.fromTeam >= 0 && tier(it.fromTeam) >= 2 && tier(it.toTeam) == 1 }
        out.appendLine("moves from second divisions / academies to top leagues: $upward")
        assertTrue("good tier-2 players move up", upward >= 5)

        // National selection: a comparable top-division player is preferred.
        var bad = 0; var checked = 0
        for (nt in w.teams.filter { it.nationalOf.isNotEmpty() && it.active }) {
            for (pid in nt.roster) {
                val p = w.players[pid]
                if (tier(p.teamId) < 2) continue
                checked++
                val better = w.players.any { o -> o.titleId == p.titleId && !o.retired && o.nat == p.nat && o.mainRole == p.mainRole && o.id !in nt.roster && tier(o.teamId) == 1 && o.ca >= p.ca - 2 && Dates.ageAt(o.birth, w.day) >= 16 }
                if (better) bad++
            }
        }
        out.appendLine("national squads: $checked second-division call-ups, $bad with a comparable top-division player left out")
        assertTrue("second-division players called up over comparable top-division ones: $bad", bad <= maxOf(3, checked / 10))

        // Event MVPs from teams that went deep.
        var early = 0; var events = 0
        for (c in w.comps) if (c.done && c.mvp >= 0 && c.placements.size >= 6 && c.tier != CompTier.CHALLENGER && c.circuit.isBlank()) {
            events++
            val teamOf = c.pstats[c.mvp]?.teamId ?: continue
            if (c.placements.indexOf(teamOf) >= 4) early++
        }
        out.appendLine("event MVPs: $events events, $early from a team outside the top four")
        assertEquals(0, early)
        Extras.toString()
        println(out); File("build/ambitions.txt").writeText(out.toString())
    }
}

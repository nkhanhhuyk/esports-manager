package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.LiveState
import dev.uem.em.sim.LiveSeries
import dev.uem.em.sim.Lineups
import dev.uem.em.sim.Subs
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Substitutes play their own positions: lineups, registration swaps and stand-ins never put a player out
 * of position while someone who plays it is free; coaches substitute between games; your substitutions
 * in a live series go into the next game and survive a resumed series.
 */
class SubsTest {
    @Test
    fun substitutesPlayTheirPositions() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "subs", "SD", Dates.day(2026, 11, 21))
        Advance.run(w, 90)
        var lines = 0; var oop = 0; var series = 0; var withSub = 0; var standIns = 0
        val reasons = HashMap<String, Int>()
        val oopEx = ArrayList<String>()
        for (r in BoxStore.unpaged()) {
            val f = w.fixtures[r.fixtureId]
            val t = Catalogue.get(f.titleId)
            series++
            if (r.games.any { g -> g.subs.any { Subs.parse(it)?.reason != "stand-in" } }) withSub++
            for (g in r.games) {
                for (n in g.subs.mapNotNull { Subs.parse(it) }) {
                    reasons[n.reason] = (reasons[n.reason] ?: 0) + 1
                    if (n.reason == "stand-in") standIns++
                    val role = t.lineupRoles[n.slot].id
                    assertTrue("${w.players[n.inPid].handle} subbed in at $role (${w.players[n.inPid].roles})", Subs.plays(w.players[n.inPid], role))
                    // The substitute played that game in that position.
                    assertTrue("sub in the box score", g.lines.any { it.playerId == n.inPid && it.team == n.side && it.roleId == role })
                }
                for (l in g.lines) {
                    lines++
                    val p = w.players[l.playerId]
                    if (Subs.plays(p, l.roleId)) continue
                    oop++
                    if (oopEx.size < 12) oopEx.add("${f.titleId} ${w.teams[if (l.team == 0) f.a else f.b].name}: ${p.handle} (${p.roles.joinToString("/")}) at ${l.roleId}")
                }
            }
        }
        println("series $series, with a substitution between games $withSub (${"%.1f".format(100.0 * withSub / series)}%), stand-ins $standIns, reasons $reasons")
        println("player-games $lines, out of position $oop"); oopEx.forEach { println("  $it") }
        assertTrue("coaches substitute between games", withSub > 0)
        assertTrue("substitutions stay occasional: $withSub of $series", withSub < series * 0.15)
        // Out of position only when nobody free plays the position at all (none expected in practice).
        assertTrue("out of position: $oop of $lines", oop <= lines / 5000)
    }

    @Test
    fun yourSubstitutionPlaysTheNextGame() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "subs2", "S2", Dates.day(2026, 11, 21), titles = listOf("lol"))
        var guard = 0
        while (w.fixtures.none { !it.done && it.titleId == "lol" && it.a >= 0 && it.b >= 0 && it.bestOf >= 3 } && guard++ < 120) Advance.run(w, 1)
        val f = w.fixtures.first { !it.done && it.titleId == "lol" && it.a >= 0 && it.b >= 0 && it.bestOf >= 3 }
        val t = Catalogue.get("lol")
        val a = w.teams[f.a]
        // Give side 0 a substitute for the jungle (a free agent jungler joins the roster).
        val fa = w.players.filter { it.titleId == "lol" && it.teamId < 0 && !it.retired && it.roles[0] == "jungle" && Lineups.available(w, it) && Dates.ageAt(it.birth, w.day) >= t.minAge }.maxBy { it.ca }
        fa.teamId = a.id; a.roster.add(fa.id)
        val notJungle = w.players.first { it.titleId == "lol" && it.teamId < 0 && !it.retired && "jungle" !in it.roles.take(2) && Lineups.available(w, it) && Dates.ageAt(it.birth, w.day) >= t.minAge }
        notJungle.teamId = a.id; a.roster.add(notJungle.id)
        w.comps[f.compId].registrations[a.id]?.players?.addAll(listOf(fa.id, notJungle.id))
        val lu = arrayOf(Lineups.auto(w, a).toList(), Lineups.auto(w, w.teams[f.b]).toList())
        val s = LiveSeries(w, f, lu)
        s.aiSides[0] = false
        assertTrue("bench has the jungler", fa.id in s.bench[0])
        val st = s.setup()
        s.play(st, s.draftSession(st).runAll())
        val slot = t.lineupRoles.indexOfFirst { it.id == "jungle" }
        val starter = s.lineups[0][slot]
        // Someone who does not play the jungle cannot be brought in there.
        assertFalse(s.substitute(0, slot, notJungle.id, "coach"))
        assertTrue(s.substitute(0, slot, fa.id, "coach"))
        assertEquals(fa.id, s.lineups[0][slot])
        assertTrue("the starter goes to the bench", starter in s.bench[0])
        // A saved series keeps the change.
        val saved: LiveState = s.liveState(false)
        val resumed = LiveSeries(w, f, lu)
        resumed.aiSides[0] = false
        resumed.restore(saved)
        assertEquals(fa.id, resumed.lineups[0][slot])
        assertEquals(1, resumed.pending().size)
        // Game 2 is played with the substitute, and the record says so.
        val st2 = resumed.setup()
        val g2 = resumed.play(st2, resumed.draftSession(st2).runAll())
        assertTrue("substitute played game 2", g2.lines.any { it.playerId == fa.id && it.team == 0 && it.roleId == "jungle" })
        assertTrue("starter sat out game 2", g2.lines.none { it.playerId == starter })
        assertEquals(1, g2.subs.size)
        val n = Subs.parse(g2.subs[0])!!
        assertEquals(starter, n.out); assertEquals(fa.id, n.inPid); assertEquals("coach", n.reason)
        assertTrue(resumed.pending().isEmpty())
        // Swapping straight back before a game cancels the change.
        assertTrue(resumed.substitute(0, slot, starter, "coach"))
        assertTrue(resumed.pending().size == 1)
        assertTrue(resumed.substitute(0, slot, fa.id, "coach"))
        assertTrue(resumed.pending().isEmpty())
        println("sub: " + Subs.describe(w, t, n))
    }
}

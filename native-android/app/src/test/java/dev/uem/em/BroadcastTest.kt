package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.CompTier
import dev.uem.em.model.Fixture
import dev.uem.em.sim.Broadcast
import dev.uem.em.sim.Lineups
import dev.uem.em.sim.ReplayCodec
import dev.uem.em.sim.SeriesSim
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Feature D acceptance: the broadcast layer is read from the same recorded events as the 2D viewer
 * and the box score. Kill feed totals equal the box-score kills; the first blood is the first kill;
 * the game-ending moment belongs to the winner; objective timers match the recorded spawns; the
 * director never cuts sooner than its cooldown unless the moment matters clearly more; shooter round
 * state ends on the recorded score, counts players alive from the kills and times the bomb from the
 * plant. Reading the presentation twice gives the same result (nothing is simulated).
 */
class BroadcastTest {
    @Test
    fun overlaysFollowTheEventStream() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "broadcast", "B", Dates.day(2026, 11, 21))
        val out = StringBuilder()
        // MLBB first, then the other MOBAs and the two shooters.
        for ((k, id) in listOf("mlbb", "lol", "wildrift", "hok", "dota2", "valorant", "cs2").withIndex()) {
            val t = Catalogue.get(id)
            val comp = w.comps.first { it.titleId == id && it.tier == CompTier.LEAGUE && it.circuit.isBlank() }
            val teams = w.teams.filter { it.titleId == id && it.active && it.kind.isEmpty() && it.nationalOf.isEmpty() && it.roster.size >= t.teamSize }.sortedByDescending { it.elo }.take(2)
            val f = Fixture(-(3_000_000 + k), comp.id, 0, id, w.day, teams[0].id, teams[1].id, 3)
            val o = SeriesSim.run(w, f, arrayOf(Lineups.ensure(w, teams[0]).toList(), Lineups.ensure(w, teams[1]).toList()), record = true)
            for ((gi, g) in o.record.games.withIndex()) {
                val rp = g.replay ?: continue
                val ev = ReplayCodec.events(rp, t, w)
                val kIdx = t.statIndex["k"] ?: 0
                val boxKills = IntArray(2) { s -> g.lines.filter { it.team == s }.sumOf { it.s[kIdx] }.toInt() }
                if (t.isMoba) {
                    val feed = Broadcast.mobaFeed(t, ev)
                    val byTeam = IntArray(2) { s -> feed.count { it.team == s } }
                    assertEquals("$id game ${gi + 1}: kill feed = box-score kills", boxKills.toList(), byTeam.toList())
                    val fb = feed.filter { it.tag == "First Blood" }
                    assertTrue(fb.size <= 1 && (fb.isEmpty() || fb[0] === feed.first { it.team >= 0 }))
                    val moments = Broadcast.mobaMoments(w, t, g, ev)
                    assertEquals(moments.sortedBy { it.t }.map { it.t }, moments.map { it.t })
                    assertTrue("$id: the core falls to the winner", moments.any { it.kind == "core" && it.team == g.winner } || ev.none { it.kind == "core" })
                    // Objective timers at the start: the next spawn is the first recorded one.
                    for (tm in Broadcast.mobaTimers(t, ev, 0)) if (!tm.alive) assertEquals(ev.first { it.kind == "spawn" && it.x == tm.objIndex }.t, tm.nextAt)
                    val shots = Broadcast.director(moments)
                    for (i in 1 until shots.size) assertTrue("cut cooldown", shots[i].from - shots[i - 1].from >= 8 || shots[i].moment.priority >= shots[i - 1].moment.priority + 3)
                    assertEquals(moments.size, Broadcast.mobaMoments(w, t, g, ev).size)
                    if (gi == 0) out.appendLine("$id: ${feed.size} kills (${feed.count { it.tag.isNotEmpty() && it.tag != "executed" }} tagged: ${feed.map { it.tag }.filter { it.isNotEmpty() }.distinct()}), ${moments.size} moments, ${shots.size} director shots, timers ${Broadcast.mobaTimers(t, ev, 300).joinToString { "${it.label}=${if (it.alive) "up" else it.nextAt}" }}")
                } else {
                    val need = t.tactical?.roundsToWin ?: 13
                    val end = Broadcast.shooterRound(t, g, ev, g.duration + 1, need)!!
                    assertEquals("$id map ${gi + 1}: HUD score = recorded score", g.score.toList(), end.score.toList())
                    val feed = Broadcast.shooterFeed(g, ev)
                    assertEquals("$id map ${gi + 1}: kill feed = box-score kills", boxKills.toList(), IntArray(2) { s -> feed.count { it.team == s } }.toList())
                    for (r in ev.filter { it.kind == "round" }) {
                        val st = Broadcast.shooterRound(t, g, ev, r.t, need)!!
                        assertEquals("everyone alive at the round start", g.lines.count { it.team == 0 }, st.alive[0])
                        assertEquals(g.lines.count { it.team == 1 }, st.alive[1])
                    }
                    val plant = ev.firstOrNull { it.kind == "plant" }
                    if (plant != null) {
                        val st = Broadcast.shooterRound(t, g, ev, plant.t + 5, need)!!
                        assertTrue(st.plantedAt == plant.t && (st.bombLeft == Broadcast.bombTime(id) - 5 || st.phase == "ended"))
                    }
                    val moments = Broadcast.shooterMoments(t, g, ev, need)
                    if (gi == 0) out.appendLine("$id: ${ev.count { it.kind == "round" }} rounds, ${feed.size} kills, moments ${moments.groupingBy { it.kind }.eachCount()}")
                }
            }
        }
        println(out); File("build/broadcast.txt").writeText(out.toString())
    }
}

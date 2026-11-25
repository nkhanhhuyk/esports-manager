package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Supporters
import dev.uem.em.model.Fixture
import dev.uem.em.model.GameRecord
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.sim.ReplayCodec
import dev.uem.em.sim.SeriesOutcome
import dev.uem.em.sim.SeriesSim
import dev.uem.em.titles.Catalogue
import dev.uem.em.ui.LiveMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live-match coherence: one authoritative simulation. Recording, watching live (spectator) and
 * background simulation give the same series; the replay's events and snapshots add up to the box
 * score; dead players never act; an interrupted live series resumes to the same result; a result
 * is applied only once.
 */
class ReplayTest {
    private fun world(): World {
        TestData.init()
        BoxStore.clear()
        val w = WorldGen.create(TestData.source, "replay", "Replay", Dates.day(2026, 11, 21))
        Advance.run(w, 50)
        return w
    }

    // As the calendar and the live screen pick them: registration, then stand-ins for seats nobody available plays.
    private fun lineups(w: World, f: Fixture) = Array(2) { si ->
        val team = w.teams[if (si == 0) f.a else f.b]
        val c = w.comps[f.compId]
        dev.uem.em.sim.Subs.standIns(w, c, team, Supporters.lineupFor(w, c, team, Lineups.ensure(w, team).toList()), emptySet(), si).first
    }

    /** The next series of a game within a week (the calendar moves on until there is one). */
    private fun next(w: World, title: String, pred: (Fixture) -> Boolean = { true }): Fixture {
        var guard = 0
        while (guard++ < 120) {
            w.fixtures.firstOrNull { !it.done && it.titleId == title && it.a >= 0 && it.b >= 0 && it.day <= w.day + 6 && pred(it) }?.let { return it }
            Advance.run(w, 1)
        }
        error("no $title series in 120 days")
    }

    private fun signature(o: SeriesOutcome) = o.wins.joinToString("-") + "|" + o.record.games.joinToString(";") { g ->
        "${g.winner},${g.duration},${g.mapId},${g.score.joinToString("/")}," + g.lines.joinToString(",") { l -> "${l.playerId}:${l.charId}:${l.rating}:${l.s.joinToString("/")}" }
    }

    @Test
    fun recordingAndWatchingDoNotChangeResults() {
        val w = world()
        for (title in listOf("lol", "mlbb", "dota2", "valorant", "cs2")) {
            val f = next(w, title)
            // Both sides' preparation is locked when the match starts, as the calendar and the live screen do.
            dev.uem.em.mgmt.Prep.lock(w, f)
            val lu = lineups(w, f)
            val plain = SeriesSim.run(w, f, lu)
            val recorded = SeriesSim.run(w, f, lu, record = true)
            assertEquals("$title: recording changed the result", signature(plain), signature(recorded))
            assertTrue(recorded.record.games.all { it.replay != null })
            // Spectator: the live path with both coaches deciding, step by step.
            val live = LiveMatch(w, f.id, spectatorWanted = true)
            var guard = 0
            while (live.stage != "result" && guard++ < 500) {
                when (live.stage) {
                    "veto", "draft" -> if (!live.aiTick()) live.startGame()
                    "watch" -> live.afterGame()
                }
            }
            assertTrue(f.done)
            val watched = BoxStore.get(w, f.id)!!
            assertEquals("$title: watching changed the result", signature(plain).substringAfter('|'), signature(SeriesOutcome(f, 0, intArrayOf(), watched, -1)).substringAfter('|'))
            assertEquals(plain.wins.toList(), listOf(f.scoreA, f.scoreB))
            println("$title ${w.teams[f.a].short} ${f.scoreA}-${f.scoreB} ${w.teams[f.b].short}: same result recorded, unrecorded and watched")
        }
    }

    private fun checkMoba(w: World, title: String, g: GameRecord) {
        val t = Catalogue.get(title)
        val rp = g.replay!!
        val ev = ReplayCodec.events(rp, t)
        val k = t.statIndex["k"]!!; val d = t.statIndex["d"]!!; val a = t.statIndex["a"]!!
        val half = rp.pids.size / 2
        val kills = HashMap<Int, Int>(); val deaths = HashMap<Int, Int>(); val assists = HashMap<Int, Int>()
        for (e in ev.filter { it.kind == "kill" }) {
            kills.merge(e.a, 1, Int::plus); deaths.merge(e.b, 1, Int::plus)
            val side = if (e.team == g.blue) 0 else 1
            for (i in 0 until half) if (e.m and (1 shl i) != 0) assists.merge(rp.pids[side * half + i], 1, Int::plus)
            // Nobody acts while dead (a death more than one tick earlier, not yet respawned).
            for (pid in listOf(e.a)) for (dd in ev) if (dd.kind == "kill" && dd.b == pid && dd.t + 60 < e.t && e.t < dd.n) {
                val bought = ev.any { it.kind == "buyback" && it.a == pid && it.t in dd.t..e.t }
                assertTrue("$title: ${w.players[pid].handle} got a kill at ${e.t}s while dead until ${dd.n}s", bought)
            }
        }
        val last = rp.snapT.size - 1
        for (l in g.lines) {
            assertEquals("$title kills ${l.playerId}", l.s[k].toInt(), kills[l.playerId] ?: 0)
            assertEquals("$title deaths ${l.playerId}", l.s[d].toInt(), deaths[l.playerId] ?: 0)
            assertEquals("$title assists ${l.playerId}", l.s[a].toInt(), assists[l.playerId] ?: 0)
            assertEquals("$title final gold ${l.playerId}", l.s[t.statIndex["gold"]!!].toInt(), ReplayCodec.value(rp, last, l.playerId, 0))
            assertEquals("$title final cs ${l.playerId}", l.s[t.statIndex["cs"]!!].toInt(), ReplayCodec.value(rp, last, l.playerId, 1))
        }
        for (team in 0..1) {
            val lines = g.lines.filter { it.team == team }
            assertEquals("$title objectives", lines.sumOf { it.s[t.statIndex["obj"]!!] }.toInt(), ev.count { it.kind == "obj" && it.team == team })
            assertEquals("$title towers", lines.sumOf { it.s[t.statIndex["towers"]!!] }.toInt(), ev.count { it.kind == "tower" && it.team == team })
        }
        assertEquals(g.winner, ev.last { it.kind == "end" }.team)
        assertEquals(g.duration, rp.snapT.last())
    }

    private fun checkShooter(w: World, title: String, g: GameRecord) {
        val t = Catalogue.get(title)
        val ev = ReplayCodec.events(g.replay!!, t)
        val k = t.statIndex["k"]!!; val d = t.statIndex["d"]!!; val a = t.statIndex["a"]!!
        for (l in g.lines) {
            assertEquals(l.s[k].toInt(), ev.count { it.kind == "kill" && it.a == l.playerId })
            assertEquals(l.s[d].toInt(), ev.count { it.kind == "kill" && it.b == l.playerId })
            assertEquals(l.s[a].toInt(), ev.count { it.kind == "kill" && it.n == l.playerId })
        }
        t.statIndex["plant"]?.let { p -> assertEquals(g.lines.sumOf { it.s[p] }.toInt(), ev.count { it.kind == "plant" }) }
        t.statIndex["defuse"]?.let { p -> assertEquals(g.lines.sumOf { it.s[p] }.toInt(), ev.count { it.kind == "defuse" }) }
        val ends = ev.filter { it.kind == "roundEnd" }
        assertEquals(g.score[0] + g.score[1], ends.size)
        assertEquals(g.score.toList(), listOf(ends.last().a, ends.last().b))
        assertEquals(ends.size, g.replay!!.snapT.size)
    }

    @Test
    fun replaysAddUpToTheBoxScore() {
        val w = world()
        var checked = 0
        for (title in listOf("lol", "mlbb", "hok", "wildrift", "dota2", "valorant", "cs2")) {
            val f = w.fixtures.firstOrNull { !it.done && it.titleId == title && it.a >= 0 && it.b >= 0 } ?: continue
            val o = SeriesSim.run(w, f, lineups(w, f), record = true)
            for (g in o.record.games) { if (Catalogue.get(title).isMoba) checkMoba(w, title, g) else checkShooter(w, title, g); checked++ }
        }
        println("replay reconciliation: $checked games")
        assertTrue(checked >= 7)
    }

    @Test
    fun interruptedLiveSeriesResumesToTheSameResult() {
        val w = world()
        for (title in listOf("lol", "valorant")) {
            val f = next(w, title) { it.bestOf >= 3 }
            dev.uem.em.mgmt.Prep.lock(w, f)
            val lu = lineups(w, f)
            val whole = SeriesSim.run(w, f, lu)
            // Watch the first game live, then "close the app": only World.live survives.
            val live = LiveMatch(w, f.id, spectatorWanted = true)
            var guard = 0
            while (live.stage != "watch" && guard++ < 300) if (!live.aiTick()) live.startGame()
            val saved = w.live
            assertNotNull(saved)
            assertEquals(1, saved!!.games.size)
            // The calendar continues: the coaches finish the series from the saved state.
            val resumed = SeriesSim.run(w, f, lu, resume = saved)
            assertEquals("$title: resumed series differs", signature(whole), signature(resumed))
        }
    }

    @Test
    fun resultsAreAppliedOnce() {
        val w = world()
        val f = next(w, "lol")
        val o = SeriesSim.run(w, f, lineups(w, f))
        Advance.apply(w, o)
        val c = w.comps[f.compId]
        val pid = o.record.games[0].lines[0].playerId
        val gp = c.pstats[pid]!!.gp
        val elo = w.teams[f.a].elo
        Advance.apply(w, o)
        assertEquals(gp, c.pstats[pid]!!.gp)
        assertEquals(elo, w.teams[f.a].elo, 0.0)
    }
}

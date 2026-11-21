package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.World
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.Ev
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.Recorder
import dev.uem.em.sim.TeamContexts
import dev.uem.em.sim.TeamCtx
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seeded large-sample checks of the positional MOBA engine for each of the five MOBAs: game length,
 * kills, gold, structures and objectives in plausible ranges, the stronger roster wins more often
 * without winning every game, every game ends by destroying a core, and the same seed gives the
 * same game with or without recording.
 */
class MobaBalanceTest {
    companion object {
        lateinit var w: World
        fun world(): World {
            if (!::w.isInitialized) { TestData.init(); BoxStore.clear(); w = WorldGen.create(TestData.source, "balance", "B", Dates.day(2026, 11, 21)) }
            // Other test classes play their own worlds, whose patches add characters to the shared
            // catalogue (the app re-applies the catalogue whenever a world is loaded; do the same).
            Catalogue.applyCustom(w)
            return w
        }
    }

    private fun ctx(w: World, titleId: String, teamRank: Int, side: Int, rng: Rng): TeamCtx {
        val t = Catalogue.get(titleId)
        val all = w.teams.filter { it.titleId == titleId && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { dev.uem.em.sim.Lineups.strength(w, it) }
        val team = if (teamRank < 0) all.last() else all[teamRank.coerceAtMost(all.size - 1)]
        val lineup = dev.uem.em.sim.Lineups.auto(w, team)
        val chars = HashMap<Int, String>()
        val used = HashSet<String>()
        lineup.forEachIndexed { i, pid ->
            if (pid < 0) return@forEachIndexed
            val role = t.lineupRoles[i]
            val p = w.players[pid]
            // Random fitting characters for both teams, so the comparison measures the players.
            val opts = t.characters.filter { it.fits(role) && it.id !in used }.ifEmpty { t.characters.filter { it.id !in used } }
            val c = opts[rng.int(0, opts.size - 1)]
            used.add(c.id); chars[pid] = c.id
        }
        return TeamContexts.build(w, team, lineup, side, chars, null, 0.0, rng)
    }

    private class Stat(var games: Int = 0, var dur: Double = 0.0, var kills: Double = 0.0, var gold: Double = 0.0, var towers: Double = 0.0, var objs: Double = 0.0, var favWins: Int = 0, var casts: Double = 0.0, var maxKills: Int = 0, var minDur: Int = 99999, var maxDur: Int = 0, var ms: Long = 0, var side0: Int = 0, var leadGames: Int = 0, var leaderWins: Int = 0)

    private fun run(titleId: String, n: Int, underdog: Int = -1): Stat {
        val w = world()
        val t = Catalogue.get(titleId)
        val patch = Patch.of(w, t)
        val s = Stat()
        for (i in 0 until n) {
            val rng = Rng.of("bal|$titleId|$i")
            val fav = i % 2
            // The strongest roster against the weakest of the title.
            val a = ctx(w, titleId, if (fav == 0) 0 else underdog, 0, rng)
            val b = ctx(w, titleId, if (fav == 1) 0 else underdog, 1, rng)
            val rec = Recorder()
            val t0 = System.nanoTime()
            val out = MobaEngine.simulate(t, listOf(a, b), arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), rng, patch, rec)
            s.ms += (System.nanoTime() - t0) / 1_000_000
            s.games++; s.dur += out.duration / 60.0; s.minDur = minOf(s.minDur, out.duration); s.maxDur = maxOf(s.maxDur, out.duration)
            val k = out.score.sum(); s.kills += k; s.maxKills = maxOf(s.maxKills, k)
            s.gold += out.book.lines.sumOf { it.s[t.statIndex["gold"]!!] } / 2
            s.towers += rec.events.count { it.kind == "tower" || it.kind == "base" }
            s.objs += rec.events.count { it.kind == "obj" }
            s.casts += rec.events.count { it.kind == "cast" }
            if (out.duration > Catalogue.get(titleId).moba!!.maxLength * 60 && System.getenv("UEM_DUMP") != null) {
                val sb = StringBuilder("LONG $titleId game $i ${out.duration / 60} min\n")
                MobaEngine.debug = null
                for ((m, es) in rec.events.groupBy { it.t / 60 }.toSortedMap()) if (m >= 20) sb.appendLine("  $m' " + es.filter { it.kind in setOf("kill", "tower", "base", "core", "obj", "fight", "shp") }.joinToString(" ") { "${it.kind}:${it.team}:${it.x}/${it.a}" })
                java.io.File("build/moba-long-$titleId-$i.txt").writeText(sb.toString())
            }
            if (out.winner == fav) s.favWins++
            if (out.winner == 0) s.side0++
            // Comebacks: does the team ahead in gold when laning ends win?
            val le = (Catalogue.get(titleId).moba!!.laneEnd * 60).toInt()
            val si = rec.snapT.indexOfLast { it <= le }
            if (si >= 0) {
                val n = rec.pids.size
                var lead = 0
                for (k in 0 until n) lead += rec.snap[(si * n + k) * rec.per] * (if (k < n / 2) 1 else -1)
                if (kotlin.math.abs(lead) > 500) { s.leadGames++; if ((lead > 0) == (out.winner == 0)) s.leaderWins++ }
            }
            // The game ends because a core fell.
            val core = rec.events.lastOrNull { it.kind == "core" }
            assertTrue("$titleId game $i ended without a core", core != null && core.team == out.winner)
            // Ability rules from the record: no recast before the shortest cooldown allows; nobody casts while dead.
            val kits = (a.players + b.players).associate { it.p.id to dev.uem.em.sim.Abilities.kit(it.charId, it.char) }
            val lastCast = HashMap<String, Int>()
            val deadUntil = HashMap<Int, IntRange>()
            for (e in rec.events) {
                if (e.kind == "kill") deadUntil[e.b] = e.t until e.n
                if (e.kind == "buyback") deadUntil.remove(e.a) // bought back: alive again
                if (e.kind != "cast" || e.x !in 0..3) continue
                val ab = kits[e.a]?.actives?.getOrNull(e.x) ?: continue
                val minCd = (ab.cd.minOrNull() ?: 0f) * 0.7f * (if (ab.ult) 1f else 1f)
                val key = "${e.a}|${e.x}"
                lastCast[key]?.let { prev -> assertTrue("$titleId game $i: ${ab.name} recast after ${e.t - prev}s (cooldown ≥ ${"%.1f".format(minCd)}s)", e.t - prev >= minCd - 2.01f) }
                lastCast[key] = e.t
                deadUntil[e.a]?.let { r -> assertTrue("$titleId game $i: a dead hero cast ${ab.name}", e.t !in (r.first + 1) until r.last) }
            }
            // Kills in the record add up to the score.
            for (side in 0..1) assertEquals(out.score[side], rec.events.count { it.kind == "kill" && it.team == side })
        }
        return s
    }

    @Test
    fun balancePerTitle() {
        val n = (System.getenv("UEM_BALANCE_N") ?: "40").toInt()
        val report = StringBuilder()
        val fails = ArrayList<String>()
        for (id in (System.getenv("UEM_BALANCE_TITLES") ?: "mlbb,wildrift,hok,lol,dota2").split(",")) {
            val s = run(id, n)
            val line = "%-8s games %d  length %.1f min (%d-%d)  kills %.1f (max %d)  team gold %.1fk  structures %.1f  objectives %.1f  casts %.0f  favourite wins %d%%  blue wins %d%%  laning gold leader wins %d%%  %.1f ms/game".format(
                id, s.games, s.dur / s.games, s.minDur / 60, s.maxDur / 60, s.kills / s.games, s.maxKills, s.gold / s.games / 1000, s.towers / s.games, s.objs / s.games, s.casts / s.games, s.favWins * 100 / s.games, s.side0 * 100 / s.games, if (s.leadGames > 0) s.leaderWins * 100 / s.leadGames else 0, s.ms.toDouble() / s.games)
            println(line); report.appendLine(line)
            val cfg = Catalogue.get(id).moba!!
            if (s.dur / s.games !in cfg.avgLength * 0.75..cfg.avgLength * 1.3) fails.add("$id length")
            if (s.kills / s.games !in 8.0..65.0) fails.add("$id kills")
            if (s.favWins * 100 / s.games < 65) fails.add("$id: the strongest roster should beat the weakest most of the time")
            // Against a near peer the result must stay open.
            val peer = run(id, maxOf(16, n / 2), 2)
            report.appendLine("         vs a near peer (3rd strongest): favourite wins ${peer.favWins * 100 / peer.games}%")
            if (peer.favWins * 100 / peer.games !in 30..88) fails.add("$id: near peers should both win games")
        }
        java.io.File("build/moba-balance.txt").writeText(report.toString())
        assertTrue(fails.joinToString(), fails.isEmpty())
    }

    @Test
    fun sameSeedSameGameRecordedOrNot() {
        val w = world()
        for (id in listOf("mlbb", "lol", "dota2")) {
            val t = Catalogue.get(id)
            fun once(record: Boolean): Pair<List<String>, String> {
                val rng = Rng.of("det|$id")
                val a = ctx(w, id, 0, 0, rng); val b = ctx(w, id, 1, 1, rng)
                val rec = if (record) Recorder() else null
                val out = MobaEngine.simulate(t, listOf(a, b), arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), rng, Patch.of(w, t), rec)
                return (rec?.events?.map { "${it.t}:${it.kind}:${it.team}:${it.a}:${it.b}" } ?: emptyList()) to "${out.winner}|${out.duration}|${out.score.toList()}|${out.book.lines.map { it.s.toList() }}"
            }
            val (_, plain) = once(false)
            val (ev1, rec1) = once(true)
            val (ev2, rec2) = once(true)
            assertEquals(plain, rec1)
            assertEquals(rec1, rec2)
            assertEquals(ev1, ev2)
        }
    }
}

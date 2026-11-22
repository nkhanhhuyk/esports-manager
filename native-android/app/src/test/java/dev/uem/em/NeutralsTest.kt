package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.Recorder
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.Neutrals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Neutral objectives: real kinds per game, League's drake rule and souls, patch changes and new fictional kinds. */
class NeutralsTest {
    @Test
    fun objectivesGiveTheirRealBuffs() {
        val w = MobaBalanceTest.world()
        val sb = StringBuilder()
        for (id in listOf("lol", "wildrift", "mlbb", "hok", "dota2")) {
            val t = Catalogue.get(id)
            val kinds = Neutrals.of(w, id)
            assertTrue("$id has objective kinds", kinds.isNotEmpty())
            // Every kind belongs to one of the map's objectives.
            assertTrue("$id kinds map to objectives", kinds.all { k -> t.moba!!.objectives.any { it.id == k.objId } })
        }
        assertEquals(6, Neutrals.of(w, "lol").count { it.objId == "drake" })
        // League of Legends drakes: two different elements, then the third's element for the rest of the game; soul at 4.
        val t = Catalogue.get("lol")
        val teams = w.teams.filter { it.titleId == "lol" && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }
        var souls = 0; var games = 0
        repeat(12) { i ->
            val rng = Rng.of("neutral|$i")
            val ctxs = listOf(teams[i % teams.size], teams[(i * 3 + 1) % teams.size].let { if (it == teams[i % teams.size]) teams[(i + 2) % teams.size] else it }).mapIndexed { side, team ->
                val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                val chars = HashMap<Int, String>(); val used = HashSet<String>()
                lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }; val c = opts[(i * 7 + k * 3 + side) % opts.size]; used.add(c.id); chars[pid] = c.id } }
                TeamContexts.build(w, team, lineup, side, chars, null, 0.0, rng)
            }
            val rec = Recorder()
            MobaEngine.simulate(t, ctxs, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), rng, Patch.of(w, t), rec, MobaEngine.Opts(world = w))
            games++
            val drakes = rec.events.filter { it.kind == "spawn" && it.text.endsWith("Drake") }.map { it.text }
            if (i < 3) sb.appendLine("game $i drakes spawned: $drakes; taken: ${rec.events.filter { it.kind == "obj" }.map { it.text }}; souls ${rec.events.filter { it.kind == "soul" }.map { it.text }}")
            if (drakes.size >= 2) assertTrue("first two drakes differ: $drakes", drakes[0] != drakes[1])
            if (drakes.size >= 4) assertTrue("third element stays: $drakes", drakes.drop(2).toSet().size == 1)
            souls += rec.events.count { it.kind == "soul" }
            assertTrue(rec.events.count { it.kind == "soul" } <= 2)
        }
        sb.appendLine("souls in $games games: $souls")
        // Patches: a kind's buff scaled, and a new fictional drake that then spawns.
        val w2 = MobaBalanceTest.world()
        val note = dev.uem.em.mgmt.Release.neutral(w2, t, Rng.of("new-drake"))
        sb.appendLine(note)
        assertTrue(note != null && w2.customNeutrals.size == 1 && Neutrals.of(w2, "lol").count { it.objId == "drake" } == 7)
        w2.neutralMods["lol-infernal"] = 1.2
        assertTrue(Neutrals.statText(Neutrals.get(w2, "lol-infernal")!!.stats, Neutrals.mod(w2, "lol-infernal")).contains("3.6"))
        println(sb); java.io.File("build/neutrals.txt").writeText(sb.toString())
    }
}

/** Runes, emblems, arcana and talents: every MOBA has them, heroes take a page that fits their class, the box score keeps it. */
class RunesTest {
    @Test
    fun pagesFitTheClass() {
        val w = MobaBalanceTest.world()
        val sb = StringBuilder()
        for (id in listOf("lol", "wildrift", "mlbb", "hok", "dota2")) {
            val t = Catalogue.get(id)
            assertTrue("$id has runes", dev.uem.em.titles.Runes.of(w, id).size >= 20)
            val teams = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }
            val rng = Rng.of("runes|$id")
            val ctxs = listOf(teams[0], teams[1]).mapIndexed { side, team ->
                val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                val chars = HashMap<Int, String>(); val used = HashSet<String>()
                lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }; val c = opts[(k * 3 + side) % opts.size]; used.add(c.id); chars[pid] = c.id } }
                TeamContexts.build(w, team, lineup, side, chars, null, 0.0, rng)
            }
            val o = MobaEngine.simulate(t, ctxs, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), rng, Patch.of(w, t), null, MobaEngine.Opts(world = w))
            assertTrue("$id: every player has a page", o.book.lines.all { it.runes.isNotEmpty() })
            for (l in o.book.lines.take(5)) sb.appendLine("  ${t.short} ${t.charIndex[l.charId]?.name} (${t.charIndex[l.charId]?.cls}): ${dev.uem.em.ui.runeLabel(w, t, l.runes)} — ${l.runes.mapNotNull { dev.uem.em.titles.Runes.get(w, it)?.name }.joinToString(", ")}")
            // A patch can add a fictional one.
            val note = dev.uem.em.mgmt.Release.rune(w, t, Rng.of("new-rune|$id"))
            sb.appendLine("  $note")
            assertTrue(note != null && dev.uem.em.titles.Runes.of(w, id).any { it.custom })
        }
        println(sb); java.io.File("build/runes.txt").writeText(sb.toString())
    }
}

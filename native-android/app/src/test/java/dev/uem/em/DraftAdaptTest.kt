package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.model.GameRecord
import dev.uem.em.model.Line
import dev.uem.em.model.Player
import dev.uem.em.model.ShownStat
import dev.uem.em.sim.Draft
import dev.uem.em.sim.DraftNotes
import dev.uem.em.sim.DraftSession
import dev.uem.em.sim.Patch
import dev.uem.em.sim.SeriesIntel
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.TitleDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drafts adapt to the earlier games of a series (no Fearless): bans go to what the other team won
 * with — above all a character it won with twice — earlier bans are dropped for them, the winner
 * keeps more of its picks than the loser. Measured against the same series drafted without memory.
 */
class DraftAdaptTest {
    private class Tally {
        var series = 0
        var banRepeat = 0; var banSlots = 0
        var twiceWon = 0; var twiceBanned = 0
        var wonOnce = 0; var wonOnceBanned = 0
        var winKeep = 0; var winSlots = 0
        var loseKeep = 0; var loseSlots = 0
        var notes = 0; var games = 0
        val kinds = java.util.TreeMap<String, Int>()
    }

    /** A game record from a draft: the winner by the draft evaluation plus chance, ratings around 1. */
    private fun record(t: TitleDef, patch: Patch, lu: Array<List<Player>>, d: Draft.Result, rng: Rng, styles: Array<String>): GameRecord {
        val ev = DoubleArray(2) { s ->
            Draft.evaluate(t, patch, lu[s].mapNotNull { p -> d.assign[s][p.id]?.let { t.charIndex[it] } }, d.picks[1 - s].mapNotNull { t.charIndex[it] }, lu[s], styles[s]).total
        }
        val winner = if (rng.chance((0.5 + (ev[0] - ev[1]) * 1.5).coerceIn(0.2, 0.8))) 0 else 1
        val lines = (0..1).flatMap { s -> lu[s].mapIndexed { i, p -> Line(p.id, s, t.lineupRoles[i].id, d.assign[s][p.id] ?: "", (if (s == winner) 1.15 else 0.85) + rng.normal(0.0, 0.2), DoubleArray(0)) } }
        return GameRecord(winner, 1800, t.maps[0].id, 0, IntArray(0), d.bans, d.picks[0] + d.picks[1], IntArray(2), lines, draftOrder = d.order, draftNotes = d.notes)
    }

    private fun bansOf(d: Draft.Result, team: Int) = d.order.filter { it.startsWith("b|$team|") }.map { it.split('|')[2] }

    private fun run(id: String, adaptive: Boolean): Tally {
        val w = MobaBalanceTest.world()
        val t = Catalogue.get(id)
        val patch = Patch.of(w, t)
        val teams = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }.take(24)
        val tl = Tally()
        for (k in 0 until 50) {
            val a = teams[k % teams.size]; val b = teams[(k * 7 + 5) % teams.size]
            if (a === b) continue
            val lu = arrayOf(dev.uem.em.sim.Lineups.auto(w, a).filter { it >= 0 }.map { w.players[it] }, dev.uem.em.sim.Lineups.auto(w, b).filter { it >= 0 }.map { w.players[it] })
            if (lu[0].size < t.lineupRoles.size || lu[1].size < t.lineupRoles.size) continue
            val styles = arrayOf(a.style, b.style)
            val games = ArrayList<GameRecord>()
            val drafts = ArrayList<Draft.Result>()
            tl.series++
            for (g in 0 until 4) {
                val intel = if (adaptive) SeriesIntel.of(games) else SeriesIntel.NONE
                val fp = if (g == 0) k % 2 else 1 - games.last().winner
                val d = DraftSession(t, patch, lu, styles, fp, arrayOf(emptySet(), emptySet()), Rng.of("adapt|$id|$k|$g"), intel = intel).runAll()
                val rec = record(t, patch, lu, d, Rng.of("adapt-res|$id|$k|$g"), styles)
                tl.games++
                if (g > 0) { tl.notes += d.notes.size; for (n in d.notes) tl.kinds.merge(n.split('|')[1], 1, Int::plus) }
                if (g > 0) {
                    val prev = drafts.last(); val pg = games.last()
                    for (s in 0..1) {
                        val before = bansOf(prev, s).toSet(); val now = bansOf(d, s)
                        tl.banSlots += now.size; tl.banRepeat += now.count { it in before }
                        // Picks kept by the winner / the loser of the last game (among those not banned now).
                        val had = pg.lines.filter { it.team == s }.map { it.charId }.filter { it !in d.bans }
                        val kept = had.count { it in d.picks[s] }
                        if (pg.winner == s) { tl.winKeep += kept; tl.winSlots += had.size } else { tl.loseKeep += kept; tl.loseSlots += had.size }
                    }
                    // Characters a side won with: does the other side ban them next game?
                    for (s in 0..1) {
                        val wonWith = HashMap<String, Int>()
                        for (gr in games) if (gr.winner == s) for (l in gr.lines) if (l.team == s) wonWith.merge(l.charId, 1, Int::plus)
                        val opBans = bansOf(d, 1 - s).toSet()
                        for ((c, n) in wonWith) {
                            if (n >= 2) { tl.twiceWon++; if (c in opBans) tl.twiceBanned++ }
                            else if (games.last().winner == s && games.last().lines.any { it.team == s && it.charId == c }) { tl.wonOnce++; if (c in opBans) tl.wonOnceBanned++ }
                        }
                    }
                }
                games.add(rec); drafts.add(d)
            }
        }
        return tl
    }

    private fun pct(a: Int, b: Int) = if (b == 0) 0 else a * 100 / b

    @Test
    fun draftsAdaptWithinASeries() {
        val sb = StringBuilder()
        val fails = ArrayList<String>()
        fun check(msg: String, ok: Boolean) { if (!ok) fails.add(msg) }
        for (id in listOf("mlbb", "wildrift", "hok", "lol", "dota2")) {
            val on = run(id, true); val off = run(id, false)
            val line = "$id (${on.series} series): bans repeated from last game ${pct(on.banRepeat, on.banSlots)}% (no memory ${pct(off.banRepeat, off.banSlots)}%), " +
                "won-with-twice banned next ${pct(on.twiceBanned, on.twiceWon)}% (${pct(off.twiceBanned, off.twiceWon)}%), " +
                "last game's winning picks banned ${pct(on.wonOnceBanned, on.wonOnce)}% (${pct(off.wonOnceBanned, off.wonOnce)}%), " +
                "picks kept: winner ${pct(on.winKeep, on.winSlots)}% / loser ${pct(on.loseKeep, on.loseSlots)}% (no memory ${pct(off.winKeep, off.winSlots)}% / ${pct(off.loseKeep, off.loseSlots)}%), " +
                "notes per game ${"%.1f".format(on.notes.toDouble() / maxOf(1, on.games - on.series))} ${on.kinds}"
            println(line); sb.appendLine(line)
            check("$id: bans should change between games: $line", pct(on.banRepeat, on.banSlots) <= pct(off.banRepeat, off.banSlots) - 5)
            check("$id: a character won with twice should usually be banned next: $line", pct(on.twiceBanned, on.twiceWon) >= 55 && pct(on.twiceBanned, on.twiceWon) >= pct(off.twiceBanned, off.twiceWon) + 20)
            check("$id: last game's winning picks are targeted more: $line", pct(on.wonOnceBanned, on.wonOnce) >= pct(off.wonOnceBanned, off.wonOnce) + 10)
            check("$id: the winner keeps more of its picks than the loser: $line", pct(on.winKeep, on.winSlots) >= pct(on.loseKeep, on.loseSlots) + 8)
            check("$id: the loser moves off more picks than without memory: $line", pct(on.loseKeep, on.loseSlots) <= pct(off.loseKeep, off.loseSlots) - 8)
            check("$id: adapted steps are explained: $line", on.notes > 0)
        }
        java.io.File("build/draft-adapt.txt").writeText(sb.toString())
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    /** A Bo1 league: with the memory of recent matches, bans follow what each opponent has been winning with. */
    @Test
    fun bansFollowTheOpponentAcrossMatches() {
        val w = MobaBalanceTest.world()
        val sb = StringBuilder()
        for (id in listOf("mlbb", "lol", "dota2")) {
            val t = Catalogue.get(id)
            val patch = Patch.of(w, t)
            val teams = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }.take(10)
            val lus = teams.map { tm -> dev.uem.em.sim.Lineups.auto(w, tm).filter { it >= 0 }.map { w.players[it] } }
            val res = IntArray(4).let { arrayOf(it, IntArray(4)) }
            for ((mode, on) in listOf(0 to false, 1 to true)) {
                val shown = teams.map { HashMap<String, ShownStat>() }
                val lastBans = HashMap<Int, Set<String>>()
                for (round in 0 until 3) for (i in teams.indices) for (j in teams.indices) {
                    if (i >= j || lus[i].size < t.lineupRoles.size || lus[j].size < t.lineupRoles.size) continue
                    val lu = arrayOf(lus[i], lus[j])
                    val styles = arrayOf(teams[i].style, teams[j].style)
                    val intel = if (on) SeriesIntel.of(emptyList(), arrayOf(shown[i], shown[j])) else SeriesIntel.NONE
                    val d = DraftSession(t, patch, lu, styles, (i + j + round) % 2, arrayOf(emptySet(), emptySet()), Rng.of("league|$id|$round|$i|$j"), intel = intel).runAll()
                    val rec = record(t, patch, lu, d, Rng.of("league-res|$id|$round|$i|$j"), styles)
                    for ((s, ti) in listOf(0 to i, 1 to j)) {
                        val mine = bansOf(d, s).toSet()
                        lastBans[ti]?.let { prev -> res[mode][0] += mine.count { it in prev }; res[mode][1] += mine.size }
                        lastBans[ti] = mine
                        // The opponent's best recent character (most wins in its memory): banned?
                        val opp = if (s == 0) j else i
                        shown[opp].entries.filter { it.value.w >= 1f }.maxWithOrNull(compareBy({ it.value.w }, { it.key }))?.let { top ->
                            if (top.key !in d.picks[1 - s] || top.key in mine) { res[mode][3]++; if (top.key in mine) res[mode][2]++ }
                        }
                    }
                    // Memory is kept the same way in both runs (it only changes the drafts when read).
                    SeriesIntel.remember(shown[i], listOf(rec), 0); SeriesIntel.remember(shown[j], listOf(rec), 1)
                }
            }
            val line = "$id league: a team's bans repeated from its last match ${pct(res[1][0], res[1][1])}% (no memory ${pct(res[0][0], res[0][1])}%), " +
                "opponent's best recent character banned ${pct(res[1][2], res[1][3])}% (${pct(res[0][2], res[0][3])}%)"
            println(line); sb.appendLine(line)
            assertTrue("$id: bans follow the opponent: $line", pct(res[1][2], res[1][3]) >= pct(res[0][2], res[0][3]) + 10)
            assertTrue("$id: bans vary from match to match: $line", pct(res[1][0], res[1][1]) <= pct(res[0][0], res[0][1]))
        }
        java.io.File("build/draft-adapt-league.txt").writeText(sb.toString())
    }

    /** Through the real series path (no Fearless): later games carry the notes, the match report can read them. */
    @Test
    fun seriesRecordsTheAdaptation() {
        val w = MobaBalanceTest.world()
        val sb = StringBuilder()
        for (id in listOf("mlbb", "lol")) {
            val comp = w.comps.first { it.titleId == id && it.stages.isNotEmpty() }
            val keep = comp.fearlessMode to comp.fearless
            comp.fearlessMode = "none"; comp.fearless = false
            var notes = 0; var later = 0
            try {
                val teams = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }.take(8)
                for (k in 0 until 8) {
                    val a = teams[k]; val b = teams[(k + 3) % teams.size]
                    val f = dev.uem.em.model.Fixture(900_000 + k, comp.id, 0, id, w.day, a.id, b.id, 5)
                    val s = dev.uem.em.sim.LiveSeries(w, f, arrayOf(dev.uem.em.sim.Lineups.auto(w, a), dev.uem.em.sim.Lineups.auto(w, b)))
                    s.quick = true
                    while (!s.done) { val st = s.setup(); s.play(st, s.draftSession(st).runAll()) }
                    for ((gi, g) in s.games.withIndex()) if (gi > 0) {
                        later++
                        notes += g.draftNotes.size
                        for (n in g.draftNotes) {
                            val p = DraftNotes.parse(n)!!
                            val e = g.draftOrder[p.step].split('|')
                            val txt = DraftNotes.text(Catalogue.get(id), p.kind, p.n, p.ref, w.teams[if (e[1] == "0") b.id else a.id].short)
                            if (sb.length < 4000) sb.appendLine("$id ${a.short}-${b.short} g${gi + 1} ${if (e[0] == "b") "ban" else "pick"} ${Catalogue.get(id).charIndex[e[2]]?.name} (${w.teams[if (e[1] == "0") a.id else b.id].short}): $txt")
                        }
                    }
                    assertTrue("game 1 has nothing to adapt to", s.games[0].draftNotes.isEmpty() || a.shown.isNotEmpty() || b.shown.isNotEmpty())
                }
            } finally { comp.fearlessMode = keep.first; comp.fearless = keep.second }
            println("$id: $notes notes over $later later games")
            assertTrue("$id: later games explain their adapted steps", later > 0 && notes >= later)
        }
        java.io.File("build/draft-adapt-notes.txt").writeText(sb.toString())
    }

    @Test
    fun notesReadAndDraftsRepeat() {
        val w = MobaBalanceTest.world()
        val t = Catalogue.get("lol")
        val patch = Patch.of(w, t)
        val teams = w.teams.filter { it.titleId == "lol" && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }
        val lu = arrayOf(dev.uem.em.sim.Lineups.auto(w, teams[0]).map { w.players[it] }, dev.uem.em.sim.Lineups.auto(w, teams[1]).map { w.players[it] })
        val styles = arrayOf(teams[0].style, teams[1].style)
        val g1 = DraftSession(t, patch, lu, styles, 0, arrayOf(emptySet(), emptySet()), Rng.of("n1")).runAll()
        val rec = record(t, patch, lu, g1, Rng.of("n1r"), styles)
        val intel = SeriesIntel.of(listOf(rec), arrayOf(mapOf(), mapOf("x" to ShownStat(2f, 1f))))
        val a = DraftSession(t, patch, lu, styles, 1, arrayOf(emptySet(), emptySet()), Rng.of("n2"), intel = intel).runAll()
        val b = DraftSession(t, patch, lu, styles, 1, arrayOf(emptySet(), emptySet()), Rng.of("n2"), intel = intel).runAll()
        assertEquals("same series, same draft", a.order, b.order)
        assertEquals(a.notes, b.notes)
        for (n in a.notes) {
            val p = DraftNotes.parse(n)!!
            assertTrue("note $n points at a step", p.step in a.order.indices)
            assertTrue("note $n reads", DraftNotes.text(t, p.kind, p.n, p.ref, "OPP").isNotEmpty())
        }
        // The team's memory of recent series fades and stays small.
        val shown = HashMap<String, ShownStat>()
        for (i in 0 until 8) SeriesIntel.remember(shown, listOf(rec), 0)
        assertTrue("memory capped", shown.size <= 12)
        assertTrue("memory fades to a steady level", shown.values.all { it.g < 3.5f })
    }
}

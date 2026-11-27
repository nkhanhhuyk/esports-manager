package dev.uem.em

import dev.uem.em.comp.Formats
import dev.uem.em.comp.Invariants
import dev.uem.em.comp.Season
import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.CompTier
import dev.uem.em.model.World
import dev.uem.em.persist.SaveStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Whole-world competition harness: every game at once (by default all seven), the app's own day advance and
 * competition code, fixed seeds. Every season it checks the [Invariants] (fixtures, results, standings, brackets,
 * Swiss records, the qualification ledger, LCQ / best-of-the-rest eligibility, academies, athletes in one series a
 * day from the box scores), that finished editions never change afterwards, that an edition's format is frozen
 * once it starts, and that league seats are conserved across promotion / relegation unless a reform says otherwise.
 * It saves and reloads at the start of a season, inside a running tournament, at the end of a season and after a
 * reform is announced, and carries on with the loaded world. Balance figures are written next to the log.
 *
 *   UEM_HARNESS=1 UEM_H_SEASONS=25 UEM_H_SEED=qa-1 [UEM_H_TITLES=lol,valorant] ./gradlew testDebugUnitTest --tests '*EcosystemHarnessTest*'
 *
 * The regression variant (always on) plays one season of all seven games with the same checks.
 */
class EcosystemHarnessTest {
    class Run(val seed: String, val seasons: Int, val titles: List<String>, val label: String)

    private fun signatureOf(w: World, c: dev.uem.em.model.Competition): Int {
        var h = c.teamIds.hashCode() * 31 + c.placements.hashCode()
        for (st in c.stages) {
            h = h * 31 + st.name.hashCode() + st.type.ordinal * 7 + st.bestOf * 13 + st.teamIds.hashCode()
            for (fid in st.fixtureIds) { val f = w.fixtures[fid]; h = h * 31 + f.a * 3 + f.b * 5 + f.winner * 7 + f.scoreA * 11 + f.scoreB * 13 + f.day }
        }
        return h
    }
    private fun formatOf(c: dev.uem.em.model.Competition) = c.rules + "|" + c.entrants.joinToString(",") + "|" + c.stages.joinToString(";") { "${it.name},${it.type},${it.bestOf},${it.groups},${it.advance},${it.swissWins},${it.swissLosses}" } + "|" + c.finalBo
    /** A digest of the state a reload must reproduce. */
    private fun stateOf(w: World): String {
        var h = 17L
        for (f in w.fixtures) h = h * 31 + (if (f.done) f.winner * 7 + f.scoreA * 3 + f.scoreB else -1) + f.day
        for (c in w.comps) h = h * 31 + c.status + c.teamIds.hashCode() + c.placements.hashCode()
        for (t in w.teams) h = h * 31 + t.roster.hashCode() + t.leagueId + t.elo.toInt()
        val money = w.orgs.sumOf { it.balance }
        return "day ${w.day} season ${w.season} comps ${w.comps.size} fixtures ${w.fixtures.size} players ${w.players.size} transfers ${w.transfers.size} awards ${w.awards.size} money ${"%.0f".format(money)} hash $h"
    }

    fun play(run: Run, out: StringBuilder, quick: Boolean): List<String> {
        TestData.init()
        val dir = File("build/harness-${run.label}").apply { deleteRecursively(); mkdirs() }
        val store = SaveStore(dir)
        BoxStore.clear(); BoxStore.store = store.pages()
        val t0 = System.currentTimeMillis()
        var w: World = WorldGen.create(TestData.source, run.seed, "Harness ${run.label}", Dates.day(2026, 11, 21), titles = run.titles)
        w.simDetail = "fast"
        val failures = ArrayList<String>()
        fun fail(s: String) { if (failures.size < 300) failures.add(s); out.appendLine("FAIL $s"); println("FAIL $s"); System.out.flush() }
        out.appendLine("seed ${run.seed}, titles ${w.titles}, ${run.seasons} season(s), created in ${(System.currentTimeMillis() - t0) / 1000}s")
        val start = w.season
        val history = HashMap<Int, Int>() // finished edition -> signature
        val frozen = HashMap<Int, String>() // running edition -> its format when first seen running
        val rt = Runtime.getRuntime()
        val reloads = ArrayList<String>()
        var announcedReload = false
        val eloAtStart = HashMap<Int, Double>()
        val upsets = IntArray(8); val series = IntArray(8)
        val balances = ArrayList<String>()
        val worldsWinners = HashMap<String, MutableList<String>>()
        val leagueChamps = HashMap<Int, MutableList<Int>>()
        val seedsLog = HashMap<String, MutableList<String>>()
        fun reload(why: String) {
            val before = stateOf(w)
            val t = System.currentTimeMillis()
            store.save("slot1", w)
            val size = File(dir, "slot1.uem").length() / 1024
            val back = store.load("slot1")
            val after = stateOf(back)
            if (before != after) fail("reload ($why) changed the world:\n  before $before\n  after  $after")
            reloads.add("$why at ${Dates.format(w.day)}: save $size KB, save+load ${System.currentTimeMillis() - t} ms")
            w = back
        }
        while (w.season < start + run.seasons) {
            val s = w.season
            val ts = System.currentTimeMillis()
            for (tm in w.teams) eloAtStart[tm.id] = tm.elo
            val idx = s - start
            if (!quick && idx == 3) reload("season start")
            // League seats at the start of the season.
            val seatsAt = w.leagues.filter { it.until == 0 }.associate { it.id to it.teamIds.toSet() }
            val proRelAt = w.leagues.filter { it.until == 0 && it.tier == 1 }.associate { it.id to Formats.hasProRel(w, it) }
            var midDone = false
            val end = Season.endDay(s)
            while (w.season == s) {
                val step = if (w.day >= end - 8) 1 else 7
                Advance.run(w, step)
                if (w.season != s) break
                // Editions freeze their format when they start.
                for (c in w.comps) if (c.season == s && c.status >= 1) {
                    val f = formatOf(c)
                    val was = frozen.putIfAbsent(c.id, f)
                    if (was != null && was != f) { fail("${c.name} changed its format while running: $was -> $f"); frozen[c.id] = f }
                }
                // A reload inside a running international (mid-series state, live bracket).
                if (!midDone && (quick || idx == 7) && w.comps.any { it.season == s && it.status == 1 && (it.tier == CompTier.WORLDS || it.tier == CompTier.INTERNATIONAL) && it.stages.any { st -> st.status == 1 } }) {
                    midDone = true; reload("inside a running international")
                }
                if (!announcedReload && w.formats.values.any { tf -> tf.pending.any { it.status == "announced" } }) { announcedReload = true; if (!quick) reload("after a reform was announced") }
                if (!quick && idx == 11 && w.day == end - 1) reload("season end")
                if (Dates.dom(w.day) <= step) {
                    val r = Invariants.world(w)
                    for (v in r.violations) fail("${Dates.format(w.day)} $v")
                }
            }
            // ---- the season that just ended
            val r = Invariants.world(w)
            Invariants.season(w, s, boxScores = true, r = r)
            for (v in r.violations) fail("season $s: $v")
            val comps = w.comps.filter { it.season == s }
            val left = comps.filter { !it.done && it.customId < 0 }
            if (left.isNotEmpty()) fail("season $s unfinished: ${left.take(6).map { it.name }}")
            for (c in comps) if (c.done) {
                history[c.id] = signatureOf(w, c)
                frozen[c.id]?.let { f -> if (f != formatOf(c)) fail("${c.name}: format changed after it started") }
            }
            // Finished editions never change afterwards (a later reform, a reload or a re-simulation).
            for ((id, sig) in history) if (signatureOf(w, w.comps[id]) != sig) fail("finished edition ${w.comps[id].name} (${w.comps[id].season}) changed")
            // Seats: conserved across the boundary unless a league reform or a pro/rel change applied this season.
            val reforms = w.formats.values.flatMap { tf -> tf.log.filter { (if (it.effective > 0) it.effective else it.season + 1) == s + 1 } }
            for ((lid, before) in seatsAt) {
                val l = w.leagues[lid]
                val now = l.teamIds.toSet()
                val reformed = reforms.any { ch -> ch.kind in Formats.LEAGUE_KINDS && (lid in ch.leagues || lid in ch.gain || ch.leagues.isEmpty()) && w.leagues[lid].titleId == l.titleId } || l.until > 0
                // Academy leagues follow their parents (a promoted club's second team joins): checked by Invariants instead.
                if (l.academy) continue
                val departed = before - now; val arrived = now - before
                val folded = departed.isNotEmpty() && departed.all { !w.teams[it].active } && arrived.isEmpty()
                if (before.size != now.size && !reformed && !folded) fail("${l.short} (${l.titleId}) went from ${before.size} to ${now.size} clubs with no reform (out ${departed.map { w.teams[it].short }}, in ${arrived.map { w.teams[it].short }})")
                if (l.tier == 1 && proRelAt[lid] == false && before != now && !reformed && !folded &&
                    reforms.none { it.kind == "prorel" && w.leagues[lid].titleId == l.titleId }) fail("franchised ${l.short} changed members without a reform: out ${(before - now).map { w.teams[it].short }}, in ${(now - before).map { w.teams[it].short }}")
            }
            // Balance: upsets (winner below the loser on Elo at the start of the season), champions.
            for (c in comps) if (c.circuit.isBlank() && c.customId < 0 && (c.tier != CompTier.CHALLENGER)) for (st in c.stages) for (fid in st.fixtureIds) {
                val f = w.fixtures[fid]; if (!f.done) continue
                val ea = eloAtStart[f.a] ?: continue; val eb = eloAtStart[f.b] ?: continue
                val gap = kotlin.math.abs(ea - eb); val b = when { gap < 50 -> 0; gap < 100 -> 1; gap < 200 -> 2; else -> 3 }
                series[b]++; if ((f.winner == f.a) != (ea >= eb)) upsets[b]++
            }
            // World championship places by league (main event and play-in), to see seeds move with results or reforms.
            for (c in comps.filter { it.tier == CompTier.WORLDS && !it.defKey.endsWith(":playin") }) {
                val field = c.teamIds + comps.filter { it.defKey == c.defKey + ":playin" }.flatMap { it.teamIds }.filter { it !in c.teamIds }
                seedsLog.getOrPut(c.titleId) { ArrayList() }.add("$s " + field.groupingBy { w.leagues.getOrNull(w.teams[it].leagueId)?.short ?: w.teams[it].region }.eachCount().entries.sortedByDescending { it.value }.joinToString(",") { "${it.key}${it.value}" })
            }
            if (idx == 0) out.appendLine("  events of no other kind: " + comps.filter { Invariants.kindOf(w, it) == "other" }.map { it.name }.distinct().take(12))
            for (c in comps.filter { it.done && it.tier == CompTier.WORLDS && !it.defKey.endsWith(":playin") && it.placements.isNotEmpty() })
                worldsWinners.getOrPut(c.titleId) { ArrayList() }.add("$s ${w.teams[c.placements[0]].short} (${w.teams[c.placements[0]].region}, org ${w.orgs[w.teams[c.placements[0]].orgId].short})")
            for (c in comps.filter { it.done && it.leagueId >= 0 && it.tier == CompTier.LEAGUE && it.placements.isNotEmpty() }) leagueChamps.getOrPut(c.leagueId) { ArrayList() }.add(c.placements[0])
            val bal = w.orgs.filter { it.teams.isNotEmpty() }.map { it.balance }.sorted()
            balances.add("$s: orgs ${bal.size}, min ${"%.1f".format(bal.first() / 1e6)}M, median ${"%.1f".format(bal[bal.size / 2] / 1e6)}M, max ${"%.1f".format(bal.last() / 1e6)}M, below zero ${bal.count { it < 0 }}")
            System.gc()
            val mem = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
            val kinds = r.counts.filterKeys { it.startsWith("kind: ") }.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key.removePrefix("kind: ")} ${it.value}" }
            out.appendLine("season $s: ${r.counts["competitions"]} events ($kinds); ${r.counts["series played"]} series, ${r.counts["games"]} games, ${r.counts["swiss rounds"] ?: 0} Swiss rounds, " +
                "${w.transfers.count { it.season == s }} transfers, ${reforms.size} reform(s) for next season, warnings ${r.warnings.size}, heap $mem MB, ${(System.currentTimeMillis() - ts) / 1000}s")
            for (x in r.warnings.take(3)) out.appendLine("  note: $x")
            println("progress ${run.seed}: season $s done, ${(System.currentTimeMillis() - t0) / 1000}s, heap $mem MB, failures ${failures.size}"); System.out.flush()
            File("build/harness-${run.seed}-partial.txt").writeText(out.toString())
        }
        // ---- summary
        out.appendLine("reloads: " + reloads.joinToString("; "))
        out.appendLine("upsets by Elo gap at season start (<50, 50-100, 100-200, 200+): " + (0..3).joinToString(", ") { "${if (series[it] > 0) upsets[it] * 100 / series[it] else 0}% of ${series[it]}" })
        for ((t, list) in worldsWinners) {
            val byTeam = list.groupingBy { it.substringAfter(' ').substringBefore(' ') }.eachCount()
            out.appendLine("$t world champions: ${list.joinToString("; ")} — ${byTeam.size} different, most ${byTeam.maxOf { it.value }}")
        }
        val leagueMax = leagueChamps.mapValues { (_, v) -> v.groupingBy { it }.eachCount().maxOf { it.value } to v.size }
        out.appendLine("league titles: most by one club per league: " + leagueMax.entries.sortedByDescending { it.value.first }.take(8).joinToString(", ") { "${w.leagues[it.key].short} ${it.value.first}/${it.value.second}" })
        out.appendLine("finances: " + balances.joinToString(" | "))
        for ((t, l) in seedsLog) out.appendLine("$t world championship places by league: " + (if (l.size > 6) l.take(3) + listOf("…") + l.takeLast(3) else l).joinToString(" / "))
        for (t in w.titles) {
            val picks = w.charStats.keys.filter { it.split('|').size == 2 }.groupBy { it.substringBefore('|') }
            val per = picks.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}:${it.value.size}" }
            if (t == w.titles.first()) out.appendLine("characters used per season (all games): $per")
        }
        store.save("slot1", w)
        out.appendLine("final: ${stateOf(w)}; save ${File(dir, "slot1.uem").length() / 1024} KB; ${(System.currentTimeMillis() - t0) / 1000}s")
        BoxStore.store = null
        return failures
    }

    @Test
    fun oneSeasonAllGames() {
        Assume.assumeTrue("the long run is on (UEM_HARNESS)", System.getenv("UEM_HARNESS") == null)
        val out = StringBuilder()
        val fails = play(Run("qa-regress", 1, dev.uem.em.titles.Catalogue.IDS, "regress"), out, quick = true)
        println(out); File("build/harness-regress.txt").writeText(out.toString())
        assertTrue(fails.take(10).joinToString("\n"), fails.isEmpty())
    }

    @Test
    fun determinism() {
        Assume.assumeTrue(System.getenv("UEM_HARNESS") == null)
        // The same seed twice: the same world after 40 days of every game (parallel series included).
        TestData.init()
        fun once(): String {
            BoxStore.clear(); BoxStore.store = null
            val w = WorldGen.create(TestData.source, "qa-det", "Det", Dates.day(2026, 11, 21)); w.simDetail = "fast"
            Advance.run(w, 40)
            return stateOf(w)
        }
        assertEquals(once(), once())
    }

    @Test
    fun manySeasons() {
        Assume.assumeTrue("set UEM_HARNESS=1", System.getenv("UEM_HARNESS") != null)
        val seasons = System.getenv("UEM_H_SEASONS")?.toIntOrNull() ?: 20
        val seed = System.getenv("UEM_H_SEED") ?: "qa-1"
        val titles = System.getenv("UEM_H_TITLES")?.split(',')?.filter { it.isNotBlank() } ?: dev.uem.em.titles.Catalogue.IDS
        val out = StringBuilder()
        val fails = play(Run(seed, seasons, titles, seed), out, quick = false)
        println(out); File("build/harness-$seed.txt").writeText(out.toString())
        assertTrue(fails.take(10).joinToString("\n"), fails.isEmpty())
    }
}

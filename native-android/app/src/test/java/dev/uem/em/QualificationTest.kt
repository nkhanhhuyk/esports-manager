package dev.uem.em

import dev.uem.em.comp.Ecosystem
import dev.uem.em.comp.Progress
import dev.uem.em.comp.Rulesets
import dev.uem.em.comp.Season
import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.CompTier
import dev.uem.em.model.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feature A acceptance: every entrant has a recorded source; two routes never make two entries; a
 * declined invitation or a withdrawn team passes its place on; slots are reproducible; repeating a
 * day's start-up changes nothing; VALORANT 2026 and 2027 use different structures; AI and user teams
 * follow the same rules; three annual cycles run without stuck events or double-booked teams.
 */
class QualificationTest {
    private fun world(seed: String = "qual"): World { TestData.init(); BoxStore.clear(); return WorldGen.create(TestData.source, seed, "Q", Dates.day(2026, 11, 21)) }

    private fun checkLedgers(w: World, out: StringBuilder) {
        for (c in w.comps) {
            if (c.status == 0) continue
            assertEquals("duplicate entrants in ${c.name}", c.teamIds.size, c.teamIds.toSet().size)
            val ins = c.entries.filter { it.status == "in" }.map { it.teamId }
            // Every entrant has exactly one recorded place with its route (older editions without a ledger: none here).
            for (tid in c.teamIds) assertEquals("ledger for ${w.teams[tid].name} in ${c.name}", 1, ins.count { it == tid })
            for (e in c.entries.filter { it.status == "in" }) assertTrue("route ${e.route} of ${c.name} is one of its rules", e.route in c.entrants)
        }
        out.appendLine("ledgers ok: ${w.comps.count { it.status != 0 }} editions, ${w.comps.sumOf { it.entries.size }} entries, " +
            "${w.comps.sumOf { c -> c.entries.count { it.note.contains("passed down") } }} places passed down, ${w.comps.sumOf { c -> c.entries.count { it.status == "withdrawn" } }} withdrawals, ${w.invites.size} invitations (${w.invites.count { it.status == "declined" }} declined)")
    }

    @Test
    fun routesLedgersInvitationsAndCycles() {
        val out = StringBuilder()
        val w = world()
        // Rulesets: dated, labelled, VALORANT 2026 ≠ 2027.
        assertTrue(Rulesets.of("valorant", 2026).id != Rulesets.of("valorant", 2027).id)
        assertTrue(Rulesets.of("lol", 2026).source.startsWith("https://lolesports.com"))
        assertTrue(w.comps.filter { it.season == w.season }.all { it.rules.isNotBlank() })
        // VALORANT 2027: open qualifiers → Kickoff / Cups → Masters → Champions; no partner places at internationals.
        val vct = w.comps.filter { it.titleId == "valorant" && it.season == 2027 }
        assertTrue(vct.any { it.name.contains("Masters Seoul") } && vct.any { it.name.contains("Champions Austin") } && vct.any { it.name.contains("Open Qualifier") })
        assertTrue("partners are not entered in internationals directly", vct.filter { it.tier != CompTier.LEAGUE && it.tier != CompTier.CHALLENGER }.none { c -> c.entrants.any { it.startsWith("league:") } })
        assertTrue("no league splits for VALORANT 2027", vct.none { it.name.contains("Split") })
        // A 2026-rules VALORANT season in the same world (as a 2026 save would have) keeps league splits; 2027 editions are untouched.
        val before = vct.map { it.id to it.entrants.toList() }
        val w26 = world("qual26")
        val firstNew = w26.comps.size
        Season.plan(w26, 2026)
        val v26 = w26.comps.drop(firstNew).filter { it.titleId == "valorant" }
        assertTrue(v26.any { it.name.contains("Split") } && v26.all { it.rules == "vct-2026" })
        for ((id, e) in before) assertEquals(e, w.comps[id].entrants)
        // LoL 2026 handbook: CBLOL sends one team to MSI, the other regions two; Worlds has the MSI winner route and the runner-up region seed.
        val msi = w.comps.first { it.titleId == "lol" && it.season == w.season && it.defKey == "lol:msi" }
        assertTrue(msi.entrants.any { it.endsWith(":1") } && msi.entrants.count { it.endsWith(":2") } >= 4)
        val worlds = w.comps.first { it.titleId == "lol" && it.season == w.season && it.defKey == "lol:worlds" }
        assertTrue(worlds.entrants.first().startsWith("msiwin:") && worlds.entrants.last().startsWith("msiseed:"))
        // CS2 Majors and The International: invitations plus open / regional qualifiers.
        val major = w.comps.first { it.titleId == "cs2" && it.defKey == "cs2:msi" && it.season == w.season }
        assertTrue(major.entrants.any { it.startsWith("invite:") } && major.entrants.any { it.startsWith("qual:") })
        val ti = w.comps.first { it.titleId == "dota2" && it.defKey == "dota2:worlds" && it.season == w.season }
        assertTrue(ti.entrants.any { it.startsWith("invite:") } && ti.entrants.count { it.startsWith("qual:") } >= 2)

        // The user runs a CS2 team: invited by the same standings as everyone else.
        val userCs = w.teams.filter { it.titleId == "cs2" && it.active && it.leagueId >= 0 && it.parentId < 0 }.maxByOrNull { it.elo }!!
        w.userOrg = userCs.orgId
        dev.uem.em.mgmt.Career.setupBoard(w)
        // A withdrawn team's place passes to the next one: a VALORANT Cup 1 winner leaves before Masters Valencia.
        val m2 = w.comps.first { it.titleId == "valorant" && it.defKey == "valorant:msi" && it.season == w.season }
        val cup1 = w.comps.first { it.titleId == "valorant" && it.season == w.season && it.name.contains("Cup 1") && !it.name.contains("Open") }
        var guard = 0
        while (!cup1.done && guard++ < 200) Advance.run(w, 3)
        assertTrue(cup1.done)
        assertTrue("Cup 1 ends before Masters Valencia's entrants are decided", w.day < m2.startDay - 3)
        val gone = cup1.placements[0]
        w.teams[gone].active = false
        // CS2 Major invitations go out (before Masters Valencia starts).
        Advance.run(w, maxOf(1, major.startDay - Ecosystem.INVITE_LEAD - w.day + 1))
        val mine = w.invites.filter { it.compId == major.id && it.teamId == userCs.id }
        assertEquals("the top CS2 team is invited", 1, mine.size)
        assertEquals("the user's invitation waits for an answer", "pending", mine[0].status)
        val askedBefore = w.invites.count { it.compId == major.id }
        Ecosystem.respond(w, mine[0], false, "test decline")
        assertEquals("a replacement is invited", askedBefore + 1, w.invites.count { it.compId == major.id })
        // Idempotent: the daily step does not add anything twice.
        val inv = w.invites.size
        Ecosystem.daily(w); Ecosystem.daily(w)
        assertEquals(inv, w.invites.size)
        Advance.run(w, m2.startDay - w.day + 1)
        assertFalse(gone in m2.teamIds)
        assertTrue(m2.entries.any { it.teamId == gone && it.status == "withdrawn" })
        assertTrue("place passed to the next team of ${cup1.short}", m2.teamIds.contains(cup1.placements[3]))
        w.teams[gone].active = true
        Advance.run(w, major.startDay - w.day + 1)
        assertFalse("the decliner is not in", userCs.id in major.teamIds)
        assertTrue(major.entries.any { it.teamId == userCs.id && it.status == "declined" })
        val entries = major.entries.size
        Progress.startDue(w, w.day); Progress.startDue(w, w.day)
        assertEquals(entries, major.entries.size)

        // Three complete annual cycles.
        while (w.season < 2030) Advance.run(w, 30)
        checkLedgers(w, out)
        for (season in 2027..2029) {
            val left = w.comps.filter { it.season == season && it.status != 2 }
            assertTrue("unfinished editions of $season: ${left.take(5).map { it.name }}", left.isEmpty())
            for (id in w.titles) assertTrue("$id world championship $season", w.comps.any { it.season == season && it.titleId == id && it.tier == CompTier.WORLDS && it.placements.isNotEmpty() })
        }
        // Worlds places come from the last split's playoffs: the champion (1 place), the finalists (2), the finalists and
        // one team on circuit points (3), the semi-finalists (4, an extra seed included); a place taken by a team already
        // in (the MSI winner) passes down the playoff order.
        for (season in 2027..2029) {
            val wc = w.comps.first { it.titleId == "lol" && it.season == season && it.defKey == "lol:worlds" }
            for (rule in wc.entrants.filter { it.startsWith("circuit:") }) {
                val league = w.leagues[rule.split(':')[1].toInt()]
                val inn = wc.entries.filter { it.route == rule && it.status == "in" }
                val last = w.comps.filter { it.season == season && it.leagueId == league.id }.maxBy { it.split }
                val fromPlayoffs = inn.filter { it.src == last.id }
                val pointsOnly = inn.filter { it.src != last.id }
                out.appendLine("Worlds $season ${league.short} (${inn.size}): " + inn.joinToString { "${w.teams[it.teamId].short} ${it.how}" })
                val total = inn.size + wc.entries.count { it.route.startsWith("msiwin") && it.status == "in" && w.teams[it.teamId].leagueId == league.id }
                when {
                    total <= 2 -> assertTrue("${league.short} $season: only playoff places", pointsOnly.isEmpty())
                    total == 3 -> assertTrue("${league.short} $season: at most one place on points", pointsOnly.size <= 1)
                    total >= 4 -> assertTrue("${league.short} $season: semi-finalists", fromPlayoffs.size >= minOf(inn.size, 4) - 1)
                }
                assertTrue("playoff places are top-four finishes", fromPlayoffs.all { it.place in 0..5 })
            }
        }
        // No team plays two series on the same day.
        val byTeamDay = HashMap<Long, Int>()
        for (f in w.fixtures) if (f.done) for (t in intArrayOf(f.a, f.b)) if (t >= 0) byTeamDay.merge(t.toLong() * 100000 + f.day, 1, Int::plus)
        val clashes = byTeamDay.count { it.value > 1 }
        out.appendLine("same-day double bookings: $clashes")
        for ((key, _) in byTeamDay.filter { it.value > 1 }.entries.take(6)) {
            val tid = (key / 100000).toInt(); val day = (key % 100000).toInt()
            out.appendLine("  ${w.teams[tid].name} (${w.teams[tid].kind.ifBlank { "pro" }}) ${Dates.format(day)}: " + w.fixtures.filter { it.done && it.day == day && (it.a == tid || it.b == tid) }.joinToString { "${w.comps[it.compId].name} [${w.comps[it.compId].stages[it.stageIdx].name}]" })
        }
        // Older editions keep their ruleset; later VALORANT seasons use the evolved structure.
        // (development circuits keep their own presets)
        val vct27 = w.comps.filter { it.titleId == "valorant" && it.season == 2027 && it.circuit.isBlank() }
        assertTrue("2027 VALORANT rules: ${vct27.filter { it.rules != "vct-2027" }.take(3).map { it.name + "=" + it.rules }}", vct27.all { it.rules == "vct-2027" })
        val vct29 = w.comps.filter { it.titleId == "valorant" && it.season == 2029 && it.circuit.isBlank() }
        assertTrue("2029 VALORANT rules: ${vct29.filter { it.rules != "vct-2027-evolved" }.take(3).map { it.name + "=" + it.rules }}", vct29.all { it.rules == "vct-2027-evolved" })
        println(out); java.io.File("build/qualification.txt").writeText(out.toString())
        assertEquals("teams booked twice on one day: ${out.lines().filter { it.startsWith("  ") }.take(3)}", 0, clashes)
    }

    @Test
    fun slotsAreReproducible() {
        // The character catalogue is shared by the process; the app re-applies a world's own when it loads it.
        val a = world("same"); dev.uem.em.titles.Catalogue.applyCustom(a); Advance.run(a, 140)
        val ea0 = a.comps.flatMap { c -> c.entries.map { "${c.id}:${it.teamId}:${it.route}:${it.status}" } }
        val b = world("same"); dev.uem.em.titles.Catalogue.applyCustom(b); Advance.run(b, 140)
        val ea = ea0
        val eb = b.comps.flatMap { c -> c.entries.map { "${c.id}:${it.teamId}:${it.route}:${it.status}" } }
        assertTrue(ea.isNotEmpty())
        assertEquals(ea, eb)
    }
}

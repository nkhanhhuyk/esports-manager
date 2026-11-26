package dev.uem.em

import dev.uem.em.comp.Formats
import dev.uem.em.comp.Leagues
import dev.uem.em.comp.OrgRankings
import dev.uem.em.comp.Prestige
import dev.uem.em.comp.Race
import dev.uem.em.comp.Season
import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.CompTier
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Connected competitions and their evolution, without simulating matches: the academy champions league,
 * rivalry cups and host cities in the calendar; promotion playoffs; a league merger and an expansion carried
 * out before their season (one membership per club, histories kept, seeds moved); a reform announced a season
 * ahead that waits for its season, with a read-only preview; the qualification race; prestige smoothing; and
 * the organisation ranking's weights.
 */
class EcosystemEvolutionTest {
    private val mobas = listOf("lol", "mlbb", "hok", "wildrift")
    private fun world(seed: String): World { TestData.init(); BoxStore.clear(); return WorldGen.create(TestData.source, seed, "E", Dates.day(2026, 11, 21), titles = mobas) }
    private fun comp(w: World, season: Int, key: String) = w.comps.firstOrNull { it.season == season && it.defKey == key }

    /** Every club is in exactly one league's list, and that is its league. */
    private fun membershipOk(w: World) {
        val count = HashMap<Int, Int>()
        for (l in w.leagues) for (tid in l.teamIds) count.merge(tid, 1, Int::plus)
        for ((tid, n) in count) { assertEquals("${w.teams[tid].name} in $n leagues", 1, n); assertTrue(w.leagues[w.teams[tid].leagueId].teamIds.contains(tid)) }
    }

    @Test
    fun calendarReformsRaceAndRankings() {
        val w = world("evo")
        val out = StringBuilder()
        // The calendar: academy champions league (academy teams only), a rivalry cup, hosts without double booking.
        for (id in mobas) {
            val cacl = comp(w, w.season, "$id:cacl")!!
            assertTrue(cacl.entrants.all { r -> w.leagues[w.comps[r.split(':')[1].toInt()].leagueId].academy })
            val riv = comp(w, w.season, "$id:rivalry")!!
            out.appendLine("$id: ${cacl.name} (${cacl.entrants.size} academy leagues), ${riv.name}, Worlds host ${comp(w, w.season, "$id:worlds")!!.host}")
        }
        val hosted = w.comps.filter { it.season == w.season && it.host.isNotEmpty() && !it.name.startsWith(Season.EWC) }
        assertTrue(hosted.size >= 12)
        for (a in hosted) for (b in hosted) if (a.id < b.id && a.host == b.host) assertTrue("${a.short} and ${b.short} both in ${a.host} at once", a.startDay + 21 <= b.startDay || b.startDay + 21 <= a.startDay)

        // Promotion playoffs: bottom two against the lower leagues' best two, a knockout.
        val lcp = w.leagues.first { it.titleId == "lol" && it.short == "LCP" }
        Formats.of(w, "lol").promo[lcp.id] = "playoff"
        val s1 = w.season + 3
        var first = w.comps.size
        Season.plan(w, s1)
        val pc = w.comps.drop(first).first { it.promoLeague == lcp.id }
        assertEquals("Promotion Playoffs", pc.stages[0].name)
        assertEquals(4, pc.entrants.sumOf { it.split(':')[2].toInt() })

        // Survival series: the second-last against the lower leagues' second-best (the ends go directly).
        Formats.of(w, "lol").promo[lcp.id] = "survival"
        first = w.comps.size
        Season.plan(w, s1 + 10)
        val sv = w.comps.drop(first).first { it.promoLeague == lcp.id }
        assertTrue(sv.name.contains("Survival Series") && sv.entrants.any { it.startsWith("bottomnth:") } && sv.entrants.any { it.startsWith("promonth:") })
        Formats.of(w, "lol").promo[lcp.id] = "playoff"
        // All-star showdown: representative squads (not clubs) of the two best-ranked leagues.
        val show = w.comps.drop(first).first { it.defKey == "lol:allstar" }
        val lck = w.leagues.first { it.short == "LCK" }
        val squad = dev.uem.em.comp.AllStars.select(w, lck, show.id)
        out.appendLine("${show.name}: ${squad.name} ${squad.roster.map { w.players[it].handle }}")
        assertTrue(squad.nationalOf.startsWith("*") && squad.roster.size == Catalogue.get("lol").teamSize + 2)
        assertTrue("players stay with their clubs", squad.roster.all { w.players[it].teamId != squad.id && w.teams[w.players[it].teamId].leagueId == lck.id })
        dev.uem.em.mgmt.Nations.stepDown(w, squad, -1)
        // Host policy: a permanent home keeps the last host city.
        val tfl = Formats.of(w, "lol")
        tfl.hosts["worlds"] = "fixed"
        first = w.comps.size
        Season.plan(w, s1 + 11)
        val fixedWorlds = w.comps.drop(first).first { it.defKey == "lol:worlds" }
        val prevHost = w.comps.filter { it.defKey == "lol:worlds" && it.season < s1 + 11 && it.host.isNotEmpty() }.maxBy { it.season }.host
        assertEquals("permanent home", prevHost, fixedWorlds.host)
        tfl.hosts.remove("worlds")

        // A merger and an expansion, announced and carried out before their season.
        val t = Catalogue.get("hok")
        val tf = Formats.of(w, "hok")
        val tier1 = Formats.tier1(w, "hok")
        val before = tier1.associate { it.id to it.teamIds.size }
        val merge = Leagues.propose(w, t, tf, tier1, "merge", Rng.of("m"), s1 + 1)
        assertTrue("a merger is proposed among small leagues", merge != null)
        val (a, b) = merge!!.third.map { w.leagues[it] }
        val seedsBefore = Formats.seeds(w, "hok", "worlds", tier1).let { s -> tier1.indices.associate { tier1[it].id to s[it] } }
        first = w.comps.size
        Season.plan(w, s1 + 1)
        val merged = w.leagues.last { it.titleId == "hok" && it.tier == 1 && it.predecessors.containsAll(listOf(a.id, b.id)) }
        out.appendLine("Merger: ${a.short} (${before[a.id]}) + ${b.short} (${before[b.id]}) → ${merged.name} (${merged.teamIds.size}); seeds ${seedsBefore[a.id]} + ${seedsBefore[b.id]} → ${tf.seeds["worlds:${merged.id}"]}")
        assertEquals(before[a.id]!! + before[b.id]!!, merged.teamIds.size)
        assertTrue(a.until == s1 && b.until == s1 && a.successors == listOf(merged.id))
        assertTrue("old leagues play no more", w.comps.drop(first).none { it.leagueId == a.id || it.leagueId == b.id })
        assertTrue("the merged league plays", w.comps.drop(first).any { it.leagueId == merged.id })
        assertTrue(tf.pending.single().status == "effective")
        membershipOk(w)
        val big = Formats.tier1(w, "hok").first { it.id != merged.id }
        val n0 = big.teamIds.size
        val exp = Leagues.propose(w, t, tf, Formats.tier1(w, "hok"), "expandleague", Rng.of("x"), s1 + 2)
        if (exp != null) {
            val target = w.leagues[exp.third.first()]
            val m0 = target.teamIds.size
            Season.plan(w, s1 + 2)
            out.appendLine("Expansion: ${target.short} $m0 → ${target.teamIds.size}")
            assertEquals(m0 + 2, target.teamIds.size)
            membershipOk(w)
        } else out.appendLine("No league could expand (${big.short} has $n0 clubs)")

        // Region recognition and associate status (Wild Rift): one membership per club after each.
        val wr = Catalogue.get("wildrift"); val wtf = Formats.of(w, "wildrift")
        val rec = Leagues.propose(w, wr, wtf, Formats.tier1(w, "wildrift"), "recognize", Rng.of("r"), s1 + 3)
        out.appendLine("Recognition: ${rec?.first ?: "no lower league qualifies"}")
        wtf.rank = Formats.tier1(w, "wildrift").sortedByDescending { Season.leagueStrength(it) }.map { it.id }
        val asc = Leagues.propose(w, wr, wtf, Formats.tier1(w, "wildrift"), "associate", Rng.of("a"), s1 + 3)
        out.appendLine("Associate: ${asc?.first ?: "none"}")
        val n1 = Formats.tier1(w, "wildrift").size
        Season.plan(w, s1 + 3)
        membershipOk(w)
        out.appendLine("Wild Rift top leagues: $n1 → ${Formats.tier1(w, "wildrift").size}: ${Formats.tier1(w, "wildrift").joinToString { it.short }}")
        if (asc != null) {
            val weak = w.leagues[asc.third[0]]
            assertTrue("${weak.short} ended", weak.until == s1 + 2)
            val assoc = w.leagues[weak.successors.single()]
            assertEquals(2, assoc.tier); assertEquals(asc.third[1], assoc.upperId)
            assertTrue("its seeds went to other leagues", (wtf.seeds["worlds:${weak.id}"] ?: 0) == 0)
        }

        // Announced a season ahead: the format waits for its season; the preview changes nothing.
        val lol = Catalogue.get("lol")
        val ltf = Formats.of(w, "lol")
        w.season += 3   // a review is due
        val sum0 = Formats.summary(w, "lol")
        val notes = Formats.evolve(w, lol, Rng.of("ahead"), force = 2, ahead = true)
        val pend = ltf.pending.firstOrNull { it.kind == "format" }
        out.appendLine("Announced ahead: $notes")
        if (pend != null) {
            assertEquals("the current format is unchanged until then", sum0, Formats.summary(w, "lol"))
            val next = Formats.pendingFormat(pend)!!
            val stateBefore = ltf.seeds.toMap() to ltf.events.keys.toSet()
            val compsBefore = w.comps.size
            val preview = Formats.preview(w, "lol", next)
            out.appendLine("Preview: $preview")
            assertEquals("preview is read-only", stateBefore, ltf.seeds.toMap() to ltf.events.keys.toSet()); assertEquals(compsBefore, w.comps.size)
            Formats.applyPending(w, "lol", w.season + 1)
            assertEquals("not yet", "announced", pend.status)
            Formats.applyPending(w, "lol", w.season + 2)
            assertEquals("effective", pend.status)
            assertTrue(ltf.log.filter { it.id in pend.ids }.all { it.status == "effective" })
        }
        assertTrue(ltf.log.all { it.id.isNotEmpty() })

        // Qualification race at the start of a season: projections, nothing locked yet.
        val wrld = World0.season(w)
        val race = Race.league(w, w.leagues.first { it.short == "LCK" }, wrld)
        out.appendLine("Race LCK $wrld: " + race.take(6).joinToString { "${w.teams[it.teamId].short} ${it.status}" })
        assertTrue(race.isNotEmpty() && race.none { it.status == "Locked" })

        // Prestige: smoothed, bounded; Worlds stays at 100.
        val worlds = comp(w, wrld, "lol:worlds")!!
        val fs = comp(w, wrld, "lol:fs")!!
        for (c in listOf(worlds, fs)) { c.teamIds = w.leagues.first { it.short == "LCK" }.teamIds.take(8).toMutableList(); c.placements = c.teamIds.toMutableList(); c.status = 2 }
        Prestige.update(w, wrld)
        assertEquals(100.0, w.prestige["lol:worlds"]!!, 0.0)
        assertTrue("bounded change", kotlin.math.abs(w.prestige["lol:fs"]!! - fs.prestige) <= 1.5)

        // Organisations: a world title is 100 points; academy results only in the development index.
        val rows = OrgRankings.table(w, wrld, wrld)
        val champ = rows.first { it.orgId == w.teams[worlds.placements[0]].orgId }
        assertTrue(champ.total >= 100.0)
        assertEquals(champ.total / maxOf(1, champ.titles.size), champ.perTitle, 1e-9)
        out.appendLine("Org ranking: " + OrgRankings.sorted(rows, 0).take(3).joinToString { "${w.orgs[it.orgId].name} ${it.total.toInt()}" })
        println(out)
    }

    private object World0 { fun season(w: World) = w.comps.filter { it.defKey == "lol:worlds" }.minOf { it.season } }
}

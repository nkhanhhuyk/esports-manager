package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.CompTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonTest {
    @Test
    fun fullSeasonAndRollover() {
        TestData.init()
        BoxStore.clear()
        val w = WorldGen.create(TestData.source, "season", "Season", Dates.day(2026, 11, 21))
        val s0 = w.season
        val t0 = System.currentTimeMillis()
        Advance.run(w, 400)
        val ms = System.currentTimeMillis() - t0
        val prev = w.comps.filter { it.season == s0 }
        println("400 days in $ms ms: season $s0 comps ${prev.size}, done ${prev.count { it.done }}, series ${w.fixtures.count { it.done }}, now ${Dates.format(w.day)} season ${w.season}")
        for (c in prev.filter { it.tier == CompTier.WORLDS || it.tier == CompTier.INTERNATIONAL }) println("  ${c.name}: ${c.placements.take(2).joinToString(" > ") { w.teams[it].name }} (${c.teamIds.size} teams, mvp ${c.mvp.takeIf { it >= 0 }?.let { w.players[it].handle }})")
        val stuck = prev.filter { !it.done }
        for (c in stuck) println("  NOT DONE: ${c.name} status ${c.status} stage ${c.current} startDay ${Dates.format(c.startDay)} entrants ${c.entrants}")
        // Nations Cup: six continental qualifiers per game, then twelve nations (the two finalists of each continent).
        val quals = prev.filter { dev.uem.em.comp.Season.isEncQualifier(it) }
        val encs = prev.filter { it.name.startsWith(dev.uem.em.comp.Season.ENC) && !dev.uem.em.comp.Season.isEncQualifier(it) }
        println("ENC qualifiers ${quals.size} (${quals.sumOf { it.teamIds.size }} nation entries, ${quals.sumOf { q -> q.stages.sumOf { it.fixtureIds.size } }} series); national amateurs ${w.players.count { p -> p.career.any { it.type.contains("national") } }}")
        for (q in quals.filter { it.titleId == "lol" }) println("  ${q.name}: ${q.teamIds.size} nations, final ${q.placements.take(2).joinToString(" > ") { w.teams[it].name }}")
        for (e in encs) println("  ${e.name}: ${e.placements.take(4).joinToString(" > ") { w.teams[it].name }} (${e.teamIds.size} teams: ${e.teamIds.groupBy { dev.uem.em.data.GameData.continentOf(w.teams[it].nationalOf) }.mapValues { it.value.size }})")
        assertEquals(w.titles.size * 6, quals.size)
        assertTrue(encs.all { e -> e.teamIds.size == 12 && e.stages[0].groups == 2 && e.stages[1].teamIds.size == 8 })
        assertTrue(encs.all { e -> e.teamIds.groupBy { dev.uem.em.data.GameData.continentOf(w.teams[it].nationalOf) }.values.all { it.size == 2 } })
        assertEquals(s0 + 1, w.season)
        assertTrue(stuck.isEmpty())
        // The LCP promotion tournament was played and its top two play the LCP this season.
        val promo = w.comps.first { it.season == s0 && it.promoLeague >= 0 }
        println("promotion: ${promo.entrants} -> ${promo.placements.map { w.teams[it].name + " (" + (w.leagues.getOrNull(w.teams[it].leagueId)?.short ?: "-") + ")" }}")
        assertTrue("promotion played", promo.done && promo.teamIds.size == 6)
        assertTrue(promo.placements.take(2).all { w.teams[it].leagueId == promo.promoLeague })
        assertEquals(8, w.leagues[promo.promoLeague].teamIds.size)
        // Relegated teams play the lower league of their region (Taiwan -> PCS, Japan -> LJL, Vietnam -> VCS).
        for (tid in promo.placements.drop(2)) {
            val team = w.teams[tid]; val l = w.leagues[team.leagueId]
            val reg = w.orgs[team.orgId].region
            if (reg in setOf("PCS", "JP", "VN")) assertTrue("${team.name} ($reg) in ${l.short}", reg in dev.uem.em.data.GameData.expand(l.region))
        }
        assertTrue(w.comps.any { it.season == s0 + 1 })
    }
}

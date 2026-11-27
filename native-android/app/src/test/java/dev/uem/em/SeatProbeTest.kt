package dev.uem.em

import dev.uem.em.comp.Season
import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import org.junit.Assume
import org.junit.Test

/** Which clubs change league at the season boundary, and why (UEM_SEATS=1). */
class SeatProbeTest {
    @Test
    fun seats() {
        Assume.assumeTrue(System.getenv("UEM_SEATS") != null)
        TestData.init(); BoxStore.clear(); BoxStore.store = null
        val w = WorldGen.create(TestData.source, "qa-regress", "Harness regress", Dates.day(2026, 11, 21))
        w.simDetail = "fast"
        val s = w.season
        Advance.run(w, Season.endDay(s) - w.day - 1)
        val before = w.leagues.associate { it.id to it.teamIds.toList() }
        val pcs = w.leagues.first { it.short == "PCS" && it.titleId == "lol" }
        for (l in w.leagues.filter { it.titleId == "lol" && it.upperId == pcs.upperId }) println("lower ${l.short} region=${l.region} expand=${dev.uem.em.data.GameData.expand(l.region)}")
        println("LCP region=${w.leagues[pcs.upperId].region} expand=${dev.uem.em.data.GameData.expand(w.leagues[pcs.upperId].region)}")
        println("amateur lol regions: " + w.teams.filter { it.titleId == "lol" && it.kind == "amateur" && it.active }.groupingBy { it.region }.eachCount())
        println("PCS before: ${pcs.teamIds.map { w.teams[it].short }} lower=${pcs.lowerId} upper=${pcs.upperId} tier=${pcs.tier}")
        println("promo comps: " + w.comps.filter { it.season == s && it.promoLeague >= 0 && it.titleId == "lol" }.map { "${it.name} -> L${it.promoLeague} ${it.placements.map { t -> w.teams[t].short }} entrants ${it.entrants}" })
        Advance.run(w, 1)
        for (l in w.leagues.filter { it.titleId == "lol" }) {
            val b = before[l.id].orEmpty().toSet(); val a = l.teamIds.toSet()
            if (a != b) println("${l.short} (tier ${l.tier}${if (l.academy) ", academy" else ""}): ${b.size} -> ${a.size}; out ${(b - a).map { "${w.teams[it].short}→${w.leagues.getOrNull(w.teams[it].leagueId)?.short ?: "none"} active=${w.teams[it].active}" }}; in ${(a - b).map { "${w.teams[it].short} from ${before.entries.firstOrNull { e -> it in e.value }?.let { e -> w.leagues[e.key].short } ?: "nowhere"}" }}")
        }
        for (n in w.news.filter { it.day >= w.day - 1 && (it.title.contains("PCS") || it.body.contains("PCS")) }.take(10)) println("news: ${n.title} — ${n.body.take(200)}")
    }
}

package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Extras
import dev.uem.em.mgmt.Nations
import dev.uem.em.mgmt.Social
import dev.uem.em.mgmt.Staffing
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * National teams: squads named in a window before each event, only players of the nationality, each
 * in a main or second position, coaches from the nation, empty again after the event; residency
 * never makes a player eligible; club lineups never put a player out of position when someone plays it.
 */
class NationsTest {
    @Test
    fun squadsNamedBeforeTheEventAndEmptyAfter() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "nations", "N", Dates.day(2026, 11, 21))
        w.simDetail = "fast"
        // Before any event: no national squads.
        assertTrue("no squads at the start", w.teams.filter { it.nationalOf.isNotEmpty() }.all { it.roster.isEmpty() })
        var guard = 0
        while (w.squads.isEmpty() && guard++ < 200) Advance.run(w, 1)
        assertTrue("a squad was named", w.squads.isNotEmpty())
        val first = w.squads.first()
        val c = w.comps[first.compId]
        println("first squads named on ${Dates.format(w.day)} for ${c.name} (starts ${Dates.format(c.startDay)}): ${w.squads.size} recorded")
        assertTrue("named before the event starts", c.startDay - w.day in 0..Nations.SELECT_DAYS && c.status == 0)
        val named = w.teams.filter { it.nationalOf.isNotEmpty() && it.squadFor >= 0 }
        var outOfPos = 0; var slots = 0; var foreignCoach = 0; var withCoach = 0
        for (team in named) {
            val t = Catalogue.get(team.titleId)
            for (pid in team.roster) {
                val p = w.players[pid]
                assertTrue("${p.handle} (${p.nat}/${p.nat2}) plays for ${team.name}", p.nat == team.nationalOf || p.nat2 == team.nationalOf)
            }
            for ((i, pid) in team.lineup.withIndex()) {
                if (pid < 0) continue
                slots++
                val p = w.players[pid]
                if (p.roles[0] != t.lineup[i] && p.roles.getOrNull(1) != t.lineup[i]) outOfPos++
            }
            Staffing.members(w, team).firstOrNull { it.role == "head-coach" }?.let { hc -> withCoach++; if (hc.nat != team.nationalOf) foreignCoach++ }
        }
        println("${named.size} squads: $outOfPos of $slots lineup places out of position; head coaches $withCoach ($foreignCoach foreign)")
        assertEquals("nobody out of position in a national lineup", 0, outOfPos)
        assertEquals("every nation has a head coach", named.size, withCoach)
        val coachIds = named.flatMap { tm -> Staffing.members(w, tm).filter { it.role == "head-coach" }.map { it.id } }
        assertEquals("one national job per coach", coachIds.size, coachIds.toSet().size)
        val sp = w.squads.first { it.notes.isNotEmpty() }
        println("${w.teams[sp.teamId].name}: " + sp.players.indices.joinToString("; ") { "${w.players[sp.players[it]].handle} — ${sp.notes[it]}" } + " | coach " + sp.coachNotes.joinToString())
        assertTrue("every pick has a reason", sp.notes.size == sp.players.size && sp.notes.all { it.isNotBlank() })
        assertTrue("in the news", w.news.any { it.title.contains("name their") })
        assertTrue("on social media", Social.feed(w, null).any { it.title?.startsWith("[Squad]") == true || it.text.contains(" name their ") })
        // Play the event out: the squads step down, the history keeps them.
        guard = 0
        while (!c.done && guard++ < 60) Advance.run(w, 1)
        val played = c.teamIds.map { w.teams[it] }.filter { it.nationalOf.isNotEmpty() }
        assertTrue("event over", c.done)
        assertTrue("squads step down after the event", played.all { it.roster.isEmpty() && it.squadFor == -1 })
        assertTrue("the history keeps the squads", played.all { tm -> tm.history.any { it.compId == c.id && it.roster.isNotEmpty() } })
        // Nobody of another nationality ever played for a national team (no day stand-ins from elsewhere either).
        val foreign = w.players.filter { p -> p.stats.any { s -> w.teams.getOrNull(s.teamId)?.nationalOf?.let { n -> n.isNotEmpty() && p.nat != n && p.nat2 != n } == true } }
        assertTrue("foreign players in national teams: " + foreign.take(5).joinToString { it.handle }, foreign.isEmpty())
    }

    @Test
    fun residencyIsNotNationality() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "residency", "R", Dates.day(2026, 11, 21))
        val lpl = w.teams.first { it.name == "BILIBILI GAMING" && it.titleId == "lol" }
        val kr = w.players.first { it.titleId == "lol" && it.nat == "KR" && it.nat2.isEmpty() && it.teamId >= 0 }
        kr.teamId.let { w.teams[it].roster.remove(kr.id) }
        kr.teamId = lpl.id; lpl.roster.add(kr.id)
        repeat(3) { Extras.residency(w) }
        assertTrue("three seasons in China: a local there, still only Korean", kr.residency == "CN" && kr.nat2.isEmpty())
        assertTrue("not an import in the LPL", !dev.uem.em.mgmt.Imports.isImport(w, kr, lpl))
        val china = Extras.nationalTeam(w, "lol", "CN")
        Nations.select(w, china, -1)
        assertTrue("not called up by China", kr.id !in china.roster)
        // An older save where residency had become a second nationality: back to residency.
        kr.residency = ""; kr.nat2 = "CN"; w.natFix = 0
        Nations.migrate(w)
        assertTrue("older saves: residency again", kr.nat2.isEmpty() && kr.residency == "CN")
    }

    @Test
    fun clubLineupsKeepPositions() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "lineups", "L", Dates.day(2026, 11, 21))
        var out = 0; var slots = 0; var avoidable = 0
        for (team in w.teams.filter { it.active && it.nationalOf.isEmpty() }) {
            val t = Catalogue.get(team.titleId)
            val lu = dev.uem.em.sim.Lineups.auto(w, team)
            for ((i, pid) in lu.withIndex()) {
                if (pid < 0) continue
                slots++
                val p = w.players[pid]
                if (p.roles[0] == t.lineup[i] || p.roles.getOrNull(1) == t.lineup[i]) continue
                out++
                // Out of position although someone on the roster (not in the lineup, not kept out by the import limit) plays it.
                val cap = dev.uem.em.mgmt.Imports.cap(w, team)
                val imports = lu.count { it >= 0 && dev.uem.em.mgmt.Imports.isImport(w, w.players[it], team) }
                if (team.roster.map { w.players[it] }.any { it.id !in lu && dev.uem.em.sim.Lineups.available(w, it) && (it.roles[0] == t.lineup[i] || it.roles.getOrNull(1) == t.lineup[i]) &&
                        !(cap >= 0 && imports >= cap && dev.uem.em.mgmt.Imports.isImport(w, it, team)) }) avoidable++
            }
        }
        println("club lineups: $out of $slots places out of position, $avoidable avoidable")
        assertEquals("never out of position when a roster player plays the position", 0, avoidable)
    }
}

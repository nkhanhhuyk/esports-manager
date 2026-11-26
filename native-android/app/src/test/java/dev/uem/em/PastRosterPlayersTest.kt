package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Careers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every player of the real past rosters is in the game (a free agent when not on a roster now), linked from the team pages. */
class PastRosterPlayersTest {
    @Test
    fun everyPastRosterPlayerIsInTheGame() {
        TestData.init()
        val w = WorldGen.create(TestData.source, "pastp", "PP", Dates.day(2026, 11, 21))
        val real = w.players.filter { it.real }.groupBy { "${it.titleId}|${Careers.norm(it.handle)}" }
        val root = Json.parseToJsonElement(TestData.source.read("pastRosters.json")).jsonObject
        var total = 0
        val missing = ArrayList<String>()
        val handles = HashSet<String>()
        for ((key, v) in root["teams"]!!.jsonObject) {
            val title = key.substringBefore('|')
            for ((_, y) in v.jsonObject["y"]!!.jsonObject) for (e in y.jsonObject["p"]!!.jsonArray) {
                val h = e.jsonArray[0].jsonPrimitive.content
                val k = "$title|${Careers.norm(h)}"
                if (Careers.norm(h).isEmpty() || !handles.add(k)) continue
                total++
                if (real[k] == null) missing.add(k)
            }
        }
        val added = w.players.count { it.realSrc.startsWith("Past rosters") }
        println("past-roster players $total, added now $added, missing ${missing.size}: ${missing.take(10)}")
        assertTrue("missing: ${missing.take(20)}", missing.isEmpty())
        // Retired legends from the T1 / SK Telecom rosters are free agents, at their positions, released by T1.
        val t1 = w.teams.first { it.titleId == "lol" && it.name == "T1" }
        for ((h, role) in listOf("Bengi" to "jungle", "Bang" to "bot", "Wolf" to "support", "PoohManDu" to "support", "Easyhoon" to "mid", "MaRin" to "top")) {
            val p = real["lol|${Careers.norm(h)}"]!!.single()
            assertTrue("$h is unsigned", p.teamId < 0)
            assertEquals("$h position", role, p.roles[0])
            assertTrue("$h was released by a real team", p.career.any { it.type == "released" && w.teams[it.teamId].real })
            println("$h: ${p.nat}, age ${Dates.ageAt(p.birth, w.day)}, CA %.1f, last ${w.teams[p.career.last().teamId].name}".format(p.ca))
        }
        assertTrue(real["lol|bengi"]!!.single().career.any { it.teamId == t1.id })
        // Other games: VALORANT (Sentinels 2021), CS2 (NaVi's Major rosters).
        for (k in listOf("valorant|shahzam", "valorant|sick", "cs2|zeus", "cs2|guardian", "cs2|flamie")) assertTrue(k, real[k] != null)
        // Nobody is in the game twice.
        val dup = real.filter { it.value.size > 1 && it.value.any { p -> p.realSrc.startsWith("Past rosters") } }.keys
        assertTrue("twice: $dup", dup.isEmpty())
    }

    @Test
    fun historicalTeamsAndChampions() {
        TestData.init()
        val w = WorldGen.create(TestData.source, "pastt", "PT", Dates.day(2026, 11, 21), titles = listOf("lol"))
        // Taipei Assassins (World champions 2012) are a dormant real team with their rosters, and every player has a page.
        val tpa = w.teams.single { it.titleId == "lol" && it.name == "Taipei Assassins" }
        assertTrue(tpa.real && !tpa.active)
        val years = dev.uem.em.mgmt.PastRosters.of("lol", tpa.name)
        assertTrue("TPA rosters: ${years.map { it.year }}", years.any { it.year == 2012 })
        val y2012 = years.first { it.year == 2012 }.players.map { Careers.norm(it.handle) }.toSet()
        for (h in listOf("bebe", "lilballz", "mistake", "stanley", "toyz")) {
            assertTrue("$h on the 2012 roster: $y2012", h in y2012)
            val p = w.players.firstOrNull { it.titleId == "lol" && Careers.norm(it.handle) == h }
            assertTrue("$h has a player page", p != null && p.real)
        }
        // Every player of every real champion and finalist roster is in the game.
        val root = Json.parseToJsonElement(TestData.source.read("honours.json")).jsonObject["events"]!!.jsonArray
        val have = w.players.filter { it.real && it.titleId == "lol" }.map { Careers.norm(it.handle) }.toSet()
        val missing = root.map { it.jsonObject }.filter { it["t"]!!.jsonPrimitive.content == "lol" }
            .flatMap { e -> listOf("wp", "rp").flatMap { k -> e[k]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList() } }
            .filter { Careers.norm(it).isNotEmpty() && Careers.norm(it) !in have }.distinct()
        println("LoL honours rosters missing: ${missing.size} ${missing.take(10)}")
        assertTrue("missing: $missing", missing.isEmpty())
        println("historical LoL teams: " + w.teams.count { it.titleId == "lol" && it.dataNote.startsWith("Historical team") })
    }
}

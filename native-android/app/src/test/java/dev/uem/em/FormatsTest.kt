package dev.uem.em

import dev.uem.em.comp.Formats
import dev.uem.em.comp.Season
import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Academies
import dev.uem.em.mgmt.Social
import dev.uem.em.model.CompTier
import dev.uem.em.model.Competition
import dev.uem.em.model.EventFormat
import dev.uem.em.model.StageType
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Academy leagues; regional finals for the Worlds places the playoffs do not decide; the best-of-the-rest
 * invitational; formats that change only at major patches (seeds by region results, play-ins of every
 * kind, stage formats, best-of, placings or points, franchising) planned and played through; social posts.
 */
class FormatsTest {
    private val mobas = listOf("lol", "mlbb", "hok", "wildrift")

    private fun world(seed: String, titles: List<String> = mobas): World { TestData.init(); BoxStore.clear(); return WorldGen.create(TestData.source, seed, "F", Dates.day(2026, 11, 21), titles = titles) }

    private fun comp(w: World, season: Int, key: String) = w.comps.firstOrNull { it.season == season && it.defKey == key }

    @Test
    fun academyLeaguesRegionalFinalsInvitationalAndEveryFormat() {
        val w = world("fmt")
        val out = StringBuilder()
        // Academy leagues: every top league of the four MOBAs; academies only, of that league's clubs; never in a promotion tournament.
        for (l in w.leagues.filter { it.tier == 1 && it.titleId in mobas }) {
            val al = Academies.academyLeague(w, l)
            out.appendLine("${l.titleId} ${l.short}: ${al?.let { "${it.name} (${it.teamIds.size})" } ?: "none"}")
            assertTrue("${l.short} has an academy league", al != null)
            for (tid in al!!.teamIds) assertEquals("${w.teams[tid].name} belongs to a ${l.short} club", l.id, w.teams[w.teams[tid].parentId].leagueId)
            assertTrue(w.comps.any { it.leagueId == al.id && it.season == w.season })
        }
        assertTrue("LCK CL is the LCK's academy league", w.leagues.any { it.short == "LCK CL" && it.academy })
        for (pc in w.comps.filter { it.promoLeague >= 0 }) for (r in pc.entrants.filter { it.startsWith("promo:") }) assertTrue(!w.leagues[w.comps[r.split(':')[1].toInt()].leagueId].academy)

        // Regional finals where a league has three (or five or more) Worlds places; the best-of-the-rest invitational.
        for (id in mobas) {
            val worlds = comp(w, w.season, "$id:worlds")!!
            val rfs = w.comps.filter { it.season == w.season && it.titleId == id && it.defKey.startsWith("$id:rf:") }
            out.appendLine("$id Worlds: ${worlds.entrants.joinToString()} · regional finals: ${rfs.joinToString { "${it.name} ${it.entrants}" }}")
            for (r in worlds.entrants.filter { it.startsWith("circuit:") }) {
                val places = r.split(':')[2].toInt()
                val hasRf = rfs.any { it.defKey.endsWith(":L${r.split(':')[1]}") }
                if (places == 3) assertTrue("$r: three places → regional finals", hasRf)
                if (places == 2 || places == 4) assertTrue("$r: no regional finals", !hasRf)
            }
            for (rf in rfs) { assertTrue(rf.startDay < worlds.startDay - 3); assertEquals(StageType.GROUPS, rf.stages[0].type) }
            val inv = comp(w, w.season, "$id:dcgi")!!
            out.appendLine("  ${inv.name}: ${inv.entrants.joinToString()}")
            assertTrue(inv.entrants.all { it.startsWith("rest:${worlds.id}:") && it.split(':')[3].toInt() in 2..4 })
        }
        assertEquals("Demacia Cup Global Invitational ${w.season}", comp(w, w.season, "lol:dcgi")!!.name)

        // Every format the patches can produce plans: play-ins of each kind, stage formats, Bo, points, no MSI winner spot.
        val lol = Catalogue.get("lol")
        val tf = Formats.of(w, "lol")
        var season = w.season + 4
        for (kind in listOf("decider", "group4", "group8", "swiss16", "knockout")) {
            tf.events.clear(); tf.seeds.clear()
            val shape = when (kind) { "decider" -> 2 to 1; "group4" -> 4 to 2; "group8" -> 8 to 2; "swiss16" -> 16 to 8; else -> 8 to 4 }
            // The main event keeps 16 places; the play-in adds (teams − places) seeds, given to the best-ranked leagues.
            val tier1 = Formats.tier1(w, "lol")
            val s = Formats.seeds(w, "lol", "worlds", tier1).copyOf()
            // (+1: with no MSI-winner spot, that place goes to a league seed.)
            var add = shape.first - shape.second + 1
            var guard = 0
            while (add > 0 && guard++ < 50) for (i in tier1.indices) if (add > 0 && s[i] < Formats.cap("worlds", tier1[i])) { s[i]++; add-- }
            tier1.forEachIndexed { i, l -> tf.seeds["worlds:${l.id}"] = s[i] }
            tf.events["worlds"] = EventFormat(group = if (kind == "swiss16") "groups" else "swiss", bracket = "double", bo = 3, groupBo = 1, playIn = kind, playInAdvance = shape.second, qual = "points", winner = 0, runner = 1, size = 16)
            tf.events["msi"] = EventFormat(group = "none", bracket = "single", playIn = "decider", playInAdvance = 1, size = 10)
            val first = w.comps.size
            Season.plan(w, season)
            val mine = w.comps.drop(first).filter { it.titleId == "lol" }
            val main = mine.first { it.defKey == "lol:worlds" }
            val pi = mine.firstOrNull { it.defKey == "lol:worlds:playin" } ?: error("$kind: no play-in; seeds ${s.toList()} main ${main.entrants} ${Formats.stagesText(main)}")
            val piSeeds = pi.entrants.size
            out.appendLine("$kind: main ${main.entrants.joinToString()} | ${Formats.stagesText(main)} || play-in ${pi.entrants.joinToString()} | ${Formats.stagesText(pi)}")
            assertTrue("main takes the play-in's top ${shape.second}", main.entrants.last() == "qual:${pi.id}:${shape.second}")
            assertTrue("no MSI winner spot", main.entrants.none { it.startsWith("msiwin:") })
            assertTrue("points mode", main.entrants.filter { it.startsWith("circuit:") }.all { it.endsWith(":points") })
            assertTrue(pi.startDay < main.startDay)
            assertTrue(piSeeds >= 1)
            assertEquals(StageType.DOUBLE_ELIM, main.stages.last().type)
            val msi = mine.first { it.defKey == "lol:msi" }
            val msiPi = mine.firstOrNull { it.defKey == "lol:msi:playin" } ?: error("$kind: no MSI play-in; ${msi.entrants}")
            assertTrue(msi.entrants.last() == "qual:${msiPi.id}:1" && msi.stages.size == 1 && msi.stages[0].type == StageType.SINGLE_ELIM)
            assertTrue("MSI play-in seeds start below the main seeds", msiPi.entrants.all { it.split(':').size == 4 })
            season++
        }
        tf.events.clear(); tf.seeds.clear()

        // Major-patch evolution: changes only every few seasons, and plans that always work.
        val mw = world("fmt-evolve", listOf("lol", "mlbb"))
        val t = Catalogue.get("lol")
        mw.firstSeason = mw.season
        assertTrue("no change in the first season", Formats.evolve(mw, t, Rng.of("a")).isEmpty())
        mw.season += 1
        assertTrue("nor the second", Formats.evolve(mw, t, Rng.of("b")).isEmpty())
        var made = 0
        var s2 = mw.season + 10
        for (i in 0 until 14) {
            val notes = Formats.evolve(mw, t, Rng.of("evolve$i"), force = 3)
            made += notes.size
            val before = mw.comps.size
            Season.plan(mw, s2)
            val ws = mw.comps.drop(before).filter { it.titleId == "lol" && (it.tier == CompTier.WORLDS || it.tier == CompTier.INTERNATIONAL) }
            assertTrue(ws.any { it.defKey == "lol:worlds" })
            for (n in notes) out.appendLine("  patch $i: $n")
            s2++
        }
        val kinds = Formats.of(mw, "lol").log.map { it.kind }.toSet()
        out.appendLine("change kinds seen: $kinds")
        assertTrue("several kinds of change", kinds.size >= 6)
        assertTrue(made >= 20)
        for (s in Formats.summary(mw, "lol")) out.appendLine("  · $s")
        // Every change is discussed.
        mw.day = Formats.of(mw, "lol").log.last().day
        val posts = Social.feed(mw, "lol")
        val fmt = posts.filter { it.key.startsWith("fm") || it.key.startsWith("fc") || it.key.startsWith("fq") }
        out.appendLine("social: ${fmt.size} posts about competition changes; e.g. ${fmt.firstOrNull()?.title} — ${fmt.firstOrNull()?.comments?.joinToString(" | ") { it.text }}")
        assertTrue(fmt.size >= 3)
        println(out)
    }

    /**
     * Two LoL seasons played through: 2027 with the calendar's formats (regional finals, the invitational), then 2028
     * with a play-in, groups and double elimination at Worlds and a franchise switch, all from a major patch.
     */
    @Test
    fun formatsPlayThrough() {
        val w = world("fmt-play", listOf("lol"))
        val out = StringBuilder()
        val s0 = w.season
        // Play the season up to its last days (after Worlds, the invitational and the Nations Cup).
        while (w.day < Season.endDay(s0) - 3) Advance.run(w, minOf(20, Season.endDay(s0) - 3 - w.day))
        check(w, s0, out)
        // A major patch's changes for next season (set before the new season's calendar is drawn up).
        val tf = Formats.of(w, "lol")
        val t = Catalogue.get("lol")
        tf.events["worlds"] = EventFormat(group = "groups", bracket = "double", playIn = "group8", playInAdvance = 2, size = 16)
        val tier1 = Formats.tier1(w, "lol")
        val s = Formats.seeds(w, "lol", "worlds", tier1).copyOf()
        var add = 6
        while (add > 0) for (i in tier1.indices) if (add > 0 && s[i] < Formats.cap("worlds", tier1[i])) { s[i]++; add-- }
        tier1.forEachIndexed { i, l -> tf.seeds["worlds:${l.id}"] = s[i] }
        Formats.force(w, t, "stage:msi", Rng.of("x"))
        val lcp = w.leagues.first { it.titleId == "lol" && it.short == "LCP" }
        tf.promo[lcp.id] = "playoff"
        val s1 = s0 + 1
        while (w.day < Season.endDay(s1) - 3) Advance.run(w, minOf(20, Season.endDay(s1) - 3 - w.day))
        check(w, s1, out)
        val pi = comp(w, s1, "lol:worlds:playin")!!
        val main = comp(w, s1, "lol:worlds")!!
        val promo = w.comps.first { it.season == s1 && it.promoLeague == lcp.id }
        assertTrue("promotion playoffs played", promo.done && promo.stages[0].type == dev.uem.em.model.StageType.SINGLE_ELIM && promo.teamIds.size == 4)
        out.appendLine("${promo.name}: ${promo.teamIds.map { w.teams[it].short }} → places to ${promo.placements.take(2).map { w.teams[it].short }}")
        assertTrue("play-in played", pi.done && pi.teamIds.size >= 6)
        assertTrue("its top two in the main event", pi.placements.take(2).all { it in main.teamIds })
        assertTrue("its others not", pi.placements.drop(2).none { it in main.teamIds })
        // The first season's awards (given when it ended): academy leagues have their own; senior awards go to first-team players.
        val academyAwards = w.awards.filter { it.season == s0 && it.name.contains("Academy MVP") }
        assertTrue("academy awards", academyAwards.isNotEmpty())
        val mvp = w.awards.firstOrNull { it.season == s0 && it.name == "LoL Season MVP" }
        out.appendLine("Awards $s0: ${academyAwards.joinToString { it.name + " " + w.players[it.playerId].handle }}; Season MVP ${mvp?.let { w.players[it.playerId].handle }}")
        println(out)
    }

    private fun check(w: World, season: Int, out: StringBuilder) {
        val left = w.comps.filter { it.season == season && it.titleId == "lol" && it.status != 2 && it.circuit.isBlank() && !it.name.startsWith(Season.ENC) }
        assertTrue("unfinished: ${left.map { it.name }}", left.isEmpty())
        val worlds = comp(w, season, "lol:worlds")!!
        val rfs = w.comps.filter { it.season == season && it.defKey.startsWith("lol:rf:") }
        for (rf in rfs) {
            out.appendLine("${rf.name}: ${rf.teamIds.map { w.teams[it].short }} → ${rf.placements.take(2).map { w.teams[it].short }}")
            if (!rf.name.endsWith("(cancelled)")) {
                assertTrue(rf.teamIds.size >= 3)
                assertTrue("${rf.short} winner at Worlds", rf.placements[0] in worlds.teamIds || rf.placements[0] in (comp(w, season, "lol:worlds:playin")?.teamIds ?: emptyList()))
                assertTrue("${rf.short} entrants not already at Worlds through the playoffs", rf.teamIds.none { tid -> worlds.entries.any { it.teamId == tid && it.src != rf.id && it.status == "in" } })
            }
        }
        val inv = comp(w, season, "lol:dcgi")!!
        val pi = comp(w, season, "lol:worlds:playin")
        out.appendLine("Worlds $season (${worlds.teamIds.size}): ${worlds.entries.filter { it.status == "in" }.joinToString { "${w.teams[it.teamId].short} [${it.how}]" }}")
        out.appendLine("${inv.name} (${inv.teamIds.size}): ${inv.teamIds.map { w.teams[it].short }} → ${w.teams[inv.placements[0]].name}")
        assertTrue(inv.teamIds.size >= 8)
        assertTrue("nobody at Worlds plays the invitational", inv.teamIds.none { it in worlds.teamIds || (pi != null && it in pi.teamIds) })
        // The academy champions league (second teams only), the rivalry cup, host cities, academy awards.
        val cacl = comp(w, season, "lol:cacl")
        if (cacl != null) {
            assertTrue("${cacl.name} played", cacl.done && cacl.teamIds.size >= 4)
            assertTrue("academy teams only", cacl.teamIds.all { w.teams[it].parentId >= 0 })
            out.appendLine("${cacl.name}: ${cacl.teamIds.size} academies → ${w.teams[cacl.placements[0]].name}")
        }
        val riv = comp(w, season, "lol:rivalry")!!
        assertTrue("${riv.name} played", riv.done && riv.teamIds.size == 8)
        out.appendLine("${riv.name}: ${dev.uem.em.comp.Rivalry.score(w, riv).entries.joinToString { "${w.leagues[it.key].short} ${it.value}" }} → ${w.teams[riv.placements[0]].name}")
        assertTrue("Worlds has a host", worlds.host.isNotEmpty())

        // No team plays two series on the same day.
        val byDay = HashMap<Long, Int>()
        for (f in w.fixtures) if (f.done && f.titleId == "lol" && w.comps[f.compId].season == season) for (tm in intArrayOf(f.a, f.b)) byDay.merge(tm.toLong() * 100000 + f.day, 1, Int::plus)
        assertEquals("same-day double bookings", 0, byDay.count { it.value > 1 })
    }
}

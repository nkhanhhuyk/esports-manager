package dev.uem.em

import dev.uem.em.comp.Progress
import dev.uem.em.comp.Stages
import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.PatchState
import dev.uem.em.mgmt.Patches
import dev.uem.em.mgmt.SoloQueue
import dev.uem.em.mgmt.TierList
import dev.uem.em.model.CompTier
import dev.uem.em.model.Competition
import dev.uem.em.model.Stage
import dev.uem.em.model.StageType
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import dev.uem.em.ui.FixtureFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Historical navigation: Swiss matches by the records both teams had going in (every fixture exactly once,
 * groups that never move), league matchweeks, bracket rounds, patch metas reproduced from the patch records
 * with what was played on each patch, and weekly solo queue ladders that stay as they were.
 */
class ArchiveNavTest {
    private fun world(): World { TestData.init(); BoxStore.clear(); return WorldGen.create(TestData.source, "archive", "AR", Dates.day(2026, 11, 21), titles = listOf("lol", "valorant")) }

    /** Play every scheduled match of a stage (the stronger team wins) until the stage is done. */
    private fun playStage(w: World, c: Competition, si: Int) {
        var guard = 0
        while (c.stages[si].status == 1 && guard++ < 400) {
            val f = c.stages[si].fixtureIds.map { w.fixtures[it] }.firstOrNull { !it.done } ?: break
            f.done = true
            val aWins = (w.teams[f.a].elo + (f.id % 7) * 15) >= w.teams[f.b].elo
            f.winner = if (aWins) f.a else f.b
            val need = f.bestOf / 2 + 1
            f.scoreA = if (aWins) need else 0; f.scoreB = if (aWins) 0 else need
            Progress.onFixtureDone(w, f)
        }
    }

    private fun comp(w: World, name: String, stages: List<Stage>, teams: List<Int>): Competition {
        val c = Competition(w.comps.size, name, name, "lol", w.season, CompTier.CUSTOM, entrants = listOf("ids:" + teams.joinToString(",")), stages = stages.toMutableList(), startDay = w.day + 1)
        w.comps.add(c)
        c.teamIds = teams.toMutableList(); c.status = 1
        Stages.start(w, c, 0, teams)
        return c
    }

    @Test
    fun swissRecordsMatchweeksRoundsPatchesAndLadders() {
        val w = world()
        val out = StringBuilder()
        val lol = w.teams.filter { it.titleId == "lol" && it.active && it.parentId < 0 && it.leagueId >= 0 }.sortedByDescending { it.elo }
        val all = (0..6).toList()

        // A 16-team Swiss (3 wins / 3 losses), then an 8-team bracket.
        val c = comp(w, "Swiss test", listOf(Stage("Swiss Stage", StageType.SWISS, w.day + 1, 1, all, advance = 8, daysBetween = 1), Stage("Knockout Stage", StageType.SINGLE_ELIM, w.day + 12, 3, all, daysBetween = 2)), lol.take(16).map { it.id })
        playStage(w, c, 0)
        val swiss = c.stages[0]
        val fx = swiss.fixtureIds.map { w.fixtures[it] }
        val buckets = FixtureFilters.swiss(w, swiss, fx)
        out.appendLine("Swiss: ${fx.size} matches in ${swiss.swissRound} rounds; " + buckets.joinToString { "${it.label}: ${it.fixtures.size}" })
        assertEquals(listOf("0-0", "1-0", "0-1", "1-1", "0-2", "2-0", "2-1", "1-2", "2-2"), buckets.filter { it.key != "sx" }.map { it.label.removeSuffix(" matches") })
        // Every match in exactly one group; the standard counts of a 16-team Swiss.
        val seen = buckets.flatMap { b -> b.fixtures.map { it.id } }
        assertEquals("every Swiss match once", fx.map { it.id }.sorted(), seen.sorted())
        val n = buckets.associate { it.label.removeSuffix(" matches") to it.fixtures.size }
        assertEquals(8, n["0-0"]); assertEquals(4, n["1-0"]); assertEquals(4, n["0-1"])
        assertEquals("2-2 deciders", 3, (n["2-2"] ?: 0) + 0)
        // Both teams entered on that record.
        val recs = FixtureFilters.swissRecords(w, swiss)
        for (b in buckets.filter { it.key != "sx" }) for (f in b.fixtures) assertEquals(b.label.removeSuffix(" matches"), FixtureFilters.recordText(recs, f).substringBefore(" vs"))
        // Groups never move: the same buckets after the bracket is played.
        playStage(w, c, 1)
        assertEquals(buckets.map { it.fixtures.map { f -> f.id } }, FixtureFilters.swiss(w, swiss, fx).map { it.fixtures.map { f -> f.id } })
        // The bracket by round.
        val ko = c.stages[1]
        val rounds = FixtureFilters.rounds(w, ko, ko.fixtureIds.map { w.fixtures[it] })
        out.appendLine("Bracket rounds: " + rounds.joinToString { "${it.label} (${it.fixtures.size})" })
        assertEquals(listOf("Quarterfinal" to 4, "Semifinal" to 2, "Grand Final" to 1), rounds.map { it.label to it.fixtures.size })

        // A league split by matchweek: every match once, a week's matches within seven days.
        val split = w.comps.first { it.titleId == "lol" && it.season == w.season && it.leagueId == lol[0].leagueId && it.split == 1 }
        Stages.start(w, split, 0, w.leagues[split.leagueId].teamIds)
        val sfx = split.stages[0].fixtureIds.map { w.fixtures[it] }
        val weeks = FixtureFilters.matchweeks(sfx)
        out.appendLine("${split.short}: ${sfx.size} matches in ${weeks.size} matchweeks; Matchweek 1: ${weeks[0].fixtures.size}")
        assertEquals(sfx.size, weeks.sumOf { it.fixtures.size })
        for (b in weeks) assertTrue(b.fixtures.maxOf { it.day } - b.fixtures.minOf { it.day } < 7)
        assertEquals("Matchweek 1", weeks[0].label)

        // Patches: each past patch's meta is reproduced from the records; what was played on it is archived.
        val t = Catalogue.get("lol")
        Patches.make(w, t, "minor", Rng.of("p1"))
        w.patchRun["lol|" + t.characters[0].id] = intArrayOf(12, 7, 3)
        val metaAfter1 = PatchState.metaOf(w, t)
        Patches.make(w, t, "major", Rng.of("p2"))
        val recs1 = w.patches.filter { it.titleId == "lol" }
        assertEquals(metaAfter1, PatchState.metaAt(w, t, 1))
        assertEquals(PatchState.metaOf(w, t), PatchState.metaAt(w, t, 2))
        assertTrue("usage archived into the patch it was played on", recs1[0].usage.startsWith(t.characters[0].id + ":12:7:3"))
        assertTrue("the new patch starts counting", w.patchRun.keys.none { it.startsWith("lol|") })
        assertEquals(w.season, PatchState.seasonOf(recs1[0]))
        assertTrue(recs1[1].roleMetas.isNotBlank())
        val past = TierList.view(w, t, recs1[0])!!
        assertTrue("the current patch is the live view", TierList.view(w, t, recs1[1]) == null)
        val pastTop = TierList.characters(w, t, null, at = past).first().second.map { it.id }
        val liveTop = TierList.characters(w, t, null).first().second.map { it.id }
        out.appendLine("S tier on ${recs1[0].version}: $pastTop · now (${recs1[1].version}): $liveTop")

        // Solo queue: weekly ladders kept as they were (the ladder runs on Mondays).
        w.day = Dates.nextWeekday(w.day + 1, 1)
        SoloQueue.weekly(w)
        val wk1 = w.soloWeeks.filter { it.titleId == "lol" }.single()
        val top1 = wk1.ids.take(5).toList(); val mmr1 = wk1.mmr.take(5).toList()
        w.day += 7
        SoloQueue.weekly(w)
        val weeks2 = w.soloWeeks.filter { it.titleId == "lol" }.sortedBy { it.week }
        out.appendLine("Solo queue: weeks ${weeks2.map { it.week }} of ${w.season}; week ${wk1.week} top ${top1.map { w.players[it].handle }}")
        assertEquals(2, weeks2.size)
        assertEquals(wk1.week + 1, weeks2[1].week)
        assertEquals("an old week is not rewritten", top1, weeks2[0].ids.take(5).toList()); assertEquals(mmr1, weeks2[0].mmr.take(5).toList())
        assertTrue(weeks2[0].ids.size >= 60)
        println(out)
    }
}

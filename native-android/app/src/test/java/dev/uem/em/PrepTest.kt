package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Prep
import dev.uem.em.model.Fixture
import dev.uem.em.model.PrepPlan
import dev.uem.em.model.World
import dev.uem.em.persist.SaveStore
import dev.uem.em.sim.LiveSeries
import dev.uem.em.sim.Lineups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Feature C acceptance: a plan changes the series inputs but guarantees nothing; the plan is locked
 * when the match starts and later edits do not count; preparation workload is charged once; the
 * opponent report never shows hidden ratings; the laboratory is seeded (same answer twice) and changes
 * nothing in the world; AI coaches prepare their own matches; plans survive saving.
 */
class PrepTest {
    private fun fingerprint(w: World): String {
        var h = 17L
        for (p in w.players) { for (a in p.attrs) h = h * 31 + (a * 1000).toLong(); h = h * 31 + (p.fatigue * 1000).toLong() + (p.form * 1000).toLong() + p.teamId + (p.ca * 1000).toLong() }
        for (t in w.teams) h = h * 31 + (t.elo * 1000).toLong() + t.roster.hashCode() + t.lineup.hashCode() + t.style.hashCode()
        for (o in w.orgs) h = h * 31 + (o.balance * 100).toLong()
        for (f in w.fixtures) h = h * 31 + (if (f.done) 1 else 0) + f.winner * 3 + f.scoreA * 7 + f.scoreB * 11
        return "h $h fixtures ${w.fixtures.size} preps ${w.preps.size} inbox ${w.inbox.size} transfers ${w.transfers.size} news ${w.news.size} day ${w.day} live ${w.live}"
    }

    @Test
    fun preparationLockedBoundedAndSandboxed() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "prep", "P", Dates.day(2026, 11, 21))
        val team = w.teams.first { it.name == "T1" }
        w.userOrg = team.orgId
        dev.uem.em.mgmt.Career.setupBoard(w)
        val out = StringBuilder()
        var f: Fixture? = null
        var guard = 0
        while (guard++ < 200) {
            f = w.fixtures.filter { !it.done && (it.a == team.id || it.b == team.id) && it.a >= 0 && it.b >= 0 && it.day > w.day }.minByOrNull { it.day }
            if (f != null) break
            Advance.run(w, 1)
        }
        assertNotNull("T1 has an upcoming match", f); f!!
        val side = if (f.a == team.id) 0 else 1
        val oppId = if (side == 0) f.b else f.a

        // The report is evidence with labels and a confidence, never hidden ratings.
        val report = Prep.opponentReport(w, f, team.id)
        for ((k, v) in report.lines) assertFalse("no hidden ratings: $k", (k + v).contains("potential", true) || (k + v).contains(" PA", false) || (k + v).contains("CA ", false))
        assertTrue(report.lines.any { it.first.contains("observed") })
        assertTrue(Prep.readiness(w, team).isNotEmpty())
        assertTrue(Prep.recommend(w, f, team).first.emphasis in Prep.EMPHASES)

        // A plan changes the series inputs.
        val lineups = arrayOf(Lineups.ensure(w, w.teams[f.a]).toList(), Lineups.ensure(w, w.teams[f.b]).toList())
        val base = LiveSeries(w, f, lineups, arrayOfNulls(2))
        val baseDraft = base.draftSession(base.setup())
        val counter = arrayOfNulls<PrepPlan>(2).also { it[side] = PrepPlan(f.id, team.id, "counter", locked = true, effect = 1.0) }
        val withCounter = LiveSeries(w, f, lineups, counter).let { it.draftSession(it.setup()) }
        assertTrue("counter study sharpens the draft read", withCounter.know[side] > baseDraft.know[side])
        assertEquals(baseDraft.know[1 - side], withCounter.know[1 - side], 1e-9)
        val comfort = arrayOfNulls<PrepPlan>(2).also { it[side] = PrepPlan(f.id, team.id, "comfort", locked = true, effect = 1.0) }
        val withComfort = LiveSeries(w, f, lineups, comfort).let { it.draftSession(it.setup()) }
        assertTrue("comfort preparation adds familiarity", withComfort.scrimBonus!![side] != baseDraft.scrimBonus!![side])
        val styled = arrayOfNulls<PrepPlan>(2).also { it[side] = PrepPlan(f.id, team.id, "balanced", style = "zz-test-style", locked = true) }
        assertEquals("zz-test-style", LiveSeries(w, f, lineups, styled).styleOf(side))

        // The laboratory: seeded, bounded, and nothing in the world changes.
        val before = fingerprint(w)
        val options = listOf("balanced", "counter", "comfort", "rest").map { e -> e to PrepPlan(f.id, team.id, e) }
        val r1 = Prep.laboratory(w, f, team.id, options, 12)
        assertEquals("the laboratory is a sandbox", before, fingerprint(w))
        val r2 = Prep.laboratory(w, f, team.id, options, 12)
        assertEquals("the same request gives the same answer", r1.map { it.wins }, r2.map { it.wins })
        assertTrue(r1.all { it.n == 12 && it.lo <= it.wins / 12.0 && it.hi >= it.wins / 12.0 })
        val bal = r1.first { it.label == "balanced" }.wins
        for (r in r1) assertTrue("a plan shifts the odds, it does not decide the match (${r.label} ${r.wins} vs balanced $bal)", abs(r.wins - bal) <= 7)
        out.appendLine("lab ${w.teams[f.a].short} v ${w.teams[f.b].short} Bo${f.bestOf}: " + r1.joinToString { "${it.label} ${it.wins}/${it.n} (%.0f-%.0f%%)".format(it.lo * 100, it.hi * 100) })
        var stopped = 0
        val partial = Prep.laboratory(w, f, team.id, options, 12, cancelled = { stopped++ > 5 })
        assertTrue("the laboratory can be cancelled", partial.sumOf { it.n } < 48)
        assertEquals(before, fingerprint(w))
        val (lo, hi) = Prep.interval(6, 12)
        assertTrue(lo < 0.5 && hi > 0.5)

        // Locking: the user's plan is fixed at the start, later edits are refused, workload charged once.
        val plan = Prep.edit(w, f.id, team.id)!!
        plan.emphasis = "counter"
        val starters = w.teams[team.id].lineup.filter { it >= 0 }.map { w.players[it] }
        val fat0 = starters.map { it.fatigue }
        Prep.lock(w, f)
        assertTrue(plan.locked)
        assertNull("a locked plan cannot be edited", Prep.edit(w, f.id, team.id))
        assertEquals("counter", Prep.effective(w, f, side)!!.emphasis)
        val fat1 = starters.map { it.fatigue }
        for (i in fat0.indices) assertEquals(minOf(100.0, fat0[i] + 3), fat1[i], 1e-9)
        Prep.lock(w, f)
        assertEquals("workload once", fat1, starters.map { it.fatigue })
        val opp = Prep.planFor(w, f.id, oppId)
        assertNotNull("the AI side gets its coach's plan", opp)
        assertTrue(opp!!.locked && opp.by == "coach")
        out.appendLine("opponent coach plan: ${opp.emphasis} (${opp.note}), effect %.2f; ours effect %.2f".format(opp.effect, plan.effect))

        // Play on: the match uses the locked plan; AI coaches prepare theirs.
        while (!f.done && guard++ < 400) Advance.run(w, 1)
        assertTrue(f.done)
        assertEquals("counter", Prep.planFor(w, f.id, team.id)!!.emphasis)
        Advance.run(w, 20)
        val coach = w.preps.filter { it.by == "coach" }
        out.appendLine("AI coach plans ${coach.size}: " + coach.groupingBy { it.emphasis }.eachCount())
        assertTrue("AI coaches prepare", coach.size > 20)
        assertTrue("AI coaches vary their plans", coach.map { it.emphasis }.toSet().size >= 2)

        // Saving keeps the plans.
        val dir = File("build/prep-save").apply { deleteRecursively(); mkdirs() }
        val store = SaveStore(dir); BoxStore.store = store.pages()
        store.save("p", w)
        val w2 = store.load("p")
        assertEquals(w.preps.size, w2.preps.size)
        assertEquals("counter", Prep.planFor(w2, f.id, team.id)!!.emphasis)
        println(out); File("build/prep.txt").writeText(out.toString())
    }
}

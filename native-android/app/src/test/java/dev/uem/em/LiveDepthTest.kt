package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Business
import dev.uem.em.sim.LiveSeries
import dev.uem.em.sim.ReplayCodec
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MOBA coach calls replay a game from the call (nothing before changes), the draft assistant, the business side. */
class LiveDepthTest {
    @Test
    fun coachCallsChangeOnlyWhatComesAfter() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "calls", "C", Dates.day(2026, 11, 21))
        val t = Catalogue.get("lol")
        var guard = 0
        while (w.fixtures.none { !it.done && it.titleId == "lol" && it.a >= 0 && it.b >= 0 } && guard++ < 60) Advance.run(w, 1)
        val f = w.fixtures.first { !it.done && it.titleId == "lol" && it.a >= 0 && it.b >= 0 }
        val lu = arrayOf(dev.uem.em.sim.Lineups.ensure(w, w.teams[f.a]).toList(), dev.uem.em.sim.Lineups.ensure(w, w.teams[f.b]).toList())
        val series = LiveSeries(w, f, lu)
        val s = series.setup()
        val session = series.draftSession(s)
        // The draft assistant has something to say at every step, with reasons.
        var steps = 0
        while (session.current() != null && steps++ < 40) {
            val (team, ban) = session.current()!!
            val tips = session.suggest(team, ban)
            assertTrue("assistant suggests at step $steps", tips.isNotEmpty() && tips.all { it.second.isNotEmpty() })
            session.aiStep()
        }
        val d = session.runAll()
        val base = series.play(s, d, record = true)
        val at = base.duration / 2
        series.undoLast()
        val called = series.play(s, d, record = true, mobaCalls = listOf(Triple(at, 0, "end")))
        val e0 = ReplayCodec.events(base.replay!!, t, w).filter { it.t < at - 1 }.map { "${it.t}|${it.kind}|${it.a}|${it.b}" }
        val e1 = ReplayCodec.events(called.replay!!, t, w).filter { it.t < at - 1 }.map { "${it.t}|${it.kind}|${it.a}|${it.b}" }
        assertEquals("everything before the call is unchanged", e0, e1)
        val after0 = ReplayCodec.events(base.replay!!, t, w).count { it.t >= at }; val after1 = ReplayCodec.events(called.replay!!, t, w).count { it.t >= at }
        println("events after the call: $after0 → $after1, duration ${base.duration} → ${called.duration}, winner ${base.winner} → ${called.winner}")
        assertTrue("the call changes the rest of the game", after0 != after1 || base.duration != called.duration)
    }

    @Test
    fun businessSide() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "biz", "B", Dates.day(2026, 11, 21))
        val org = w.orgs[w.teams.first { it.name == "T1" }.orgId]
        w.userOrg = org.id
        org.balance = 20_000_000.0
        // Venue, creators, events (two-week gap), and the money shows in the monthly breakdown.
        assertTrue(Business.buildVenue(w, org, 2).contains("built"))
        assertEquals(2, org.venue)
        val offer = Business.market(w, org).first()
        assertTrue(Business.sign(w, org, offer, 2).contains("joins"))
        val fans0 = org.fans
        assertTrue(Business.hold(w, org, "fest").contains("fans"))
        assertTrue("fans grew", org.fans > fans0)
        assertTrue("two weeks between events", Business.blocked(w, org, Business.EVENTS.first { it.id == "meet" }) != null)
        val b = dev.uem.em.mgmt.Economy.monthly(w, org)
        assertTrue(b.revenue.containsKey("Content creators") && b.expenses.containsKey("Venue upkeep"))
        // AI organisations run their business by the same rules.
        Advance.run(w, 95)
        val ai = w.orgs.filter { it.id != org.id }
        println("after 95 days: ${ai.count { it.creators.isNotEmpty() }} AI orgs with creators, ${ai.count { it.fanEvents.isNotEmpty() }} held fan events, ${ai.count { it.venue > 0 }} built a venue")
        assertTrue(ai.count { it.creators.isNotEmpty() } > 0 && ai.count { it.fanEvents.isNotEmpty() } > 0)
        // Saved and loaded with the new fields.
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val back = kotlinx.serialization.protobuf.ProtoBuf.decodeFromByteArray(dev.uem.em.model.World.serializer(), kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(dev.uem.em.model.World.serializer(), w))
        val o2 = back.orgs[org.id]
        assertEquals(org.venue, o2.venue); assertEquals(org.creators.size, o2.creators.size); assertEquals(org.fanEvents.size, o2.fanEvents.size)
    }
}

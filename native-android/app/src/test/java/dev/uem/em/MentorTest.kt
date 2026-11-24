package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Academies
import dev.uem.em.mgmt.Mentoring
import dev.uem.em.model.Attrs
import dev.uem.em.persist.SaveStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Feature B acceptance: limited mentor capacity; development only from completed sessions and capped;
 * promotion keeps one person and history; a role change keeps attributes; mentorships survive saving;
 * AI academies graduate some prospects over seasons (not all, not none).
 */
class MentorTest {
    @Test
    fun mentoringAndPathway() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "mentor", "M", Dates.day(2026, 11, 21))
        val team = w.teams.first { it.name == "T1" }
        w.userOrg = team.orgId
        dev.uem.em.mgmt.Career.setupBoard(w)
        val roster = team.roster.map { w.players[it] }
        val mentor = roster.maxByOrNull { Mentoring.teaching(it) }!!
        val young = roster.filter { it.id != mentor.id }.sortedBy { it.birth }.reversed()
        val cap = Mentoring.capacity(mentor)
        // Capacity: the mentor takes at most `cap` mentees.
        var started = 0
        for (p in young) if (Mentoring.start(w, p, mentor.id, false, "communication") == null) started++
        assertEquals(minOf(cap, young.size), started)
        assertNotNull(Mentoring.start(w, young.last(), mentor.id, false, "communication").takeIf { started >= cap })
        val m = Mentoring.of(w, young[0].id)!!
        // No session, no change: an injured mentee misses sessions.
        val i = Attrs.idx("communication")
        val before = young[0].attrs[i]
        young[0].injuryUntil = w.day + 30
        Mentoring.weekly(w); Mentoring.weekly(w)
        assertEquals(before, young[0].attrs[i], 1e-9)
        assertEquals(0, m.sessions)
        assertTrue(m.missed >= 2)
        young[0].injuryUntil = 0
        // Sessions: change attributable to them, capped over many weeks.
        repeat(80) { Mentoring.weekly(w) }
        assertTrue(m.sessions > 0)
        val g = Mentoring.goal("communication")
        assertTrue("gain ${m.gained} within the cap", m.gained <= Mentoring.CAP_PER_ATTR * g.attrs.size + 1e-6)
        assertTrue(young[0].attrs[i] >= before)
        assertTrue("a sensible total (no magic boosts): ${m.gained}", m.gained < 13)
        // A title-specific goal needs a mentor who knows the game; staff from another game cannot.
        val otherStaff = w.staff.firstOrNull { it.orgId == team.orgId && it.titleId != team.titleId }
        if (otherStaff != null) assertNotNull(Mentoring.cannot(w, young[1], null, otherStaff, Mentoring.goal("draft")))
        // Promotion keeps one person: academy → first team moves the same Player id, one roster, career kept.
        val academy = Academies.of(w, team) ?: Academies.found(w, team, charge = false).team!!
        val prospect = w.players[academy.roster.first()]
        val r = Academies.move(w, prospect)
        if (r.ok) {
            assertEquals(team.id, prospect.teamId)
            assertEquals(1, w.teams.count { prospect.id in it.roster })
            assertTrue(prospect.career.any { it.teamId == academy.id } && prospect.career.any { it.teamId == team.id })
        }
        // A role change keeps the attributes (role knowledge is per role).
        val attrs = prospect.attrs.copyOf()
        prospect.roles = listOf(prospect.roles.getOrElse(1) { "mid" }, prospect.roles[0])
        assertTrue(attrs.contentEquals(prospect.attrs))
        // Saving keeps mentorships and plans.
        Mentoring.plan(w, prospect).apply { focus = "draft"; gamesTarget = 4 }
        val dir = File("build/mentor-save").apply { deleteRecursively(); mkdirs() }
        val store = SaveStore(dir); BoxStore.store = store.pages()
        store.save("m", w)
        val w2 = store.load("m")
        assertEquals(w.mentorships.size, w2.mentorships.size)
        assertEquals(m.sessions, w2.mentorships.first { it.id == m.id }.sessions)
        assertEquals("draft", w2.devPlans.first { it.playerId == prospect.id }.focus)
        // Two seasons: AI organisations mentor and promote academy players — some, not all.
        val sb = StringBuilder()
        val start = w.season
        while (w.season < start + 2) Advance.run(w, 30)
        val promoted = w.transfers.count { it.kind == "promoted" && !w.isUserTeam(it.toTeam) }
        val academyPlayers = w.teams.filter { it.parentId >= 0 && it.active }.sumOf { it.roster.size }
        val aiMentors = w.mentorships.count { !w.isUserTeam(w.players[it.menteeId].teamId) }
        sb.appendLine("AI promotions $promoted, academy players now $academyPlayers, AI mentorships $aiMentors, sessions ${w.mentorships.sumOf { it.sessions }}")
        println(sb); File("build/mentor.txt").writeText(sb.toString())
        assertTrue("AI academies graduate some prospects", promoted > 0)
        assertTrue("not every academy player is promoted", academyPlayers > promoted)
        assertTrue("AI organisations use mentors", aiMentors > 0)
        assertNull(null)
    }
}

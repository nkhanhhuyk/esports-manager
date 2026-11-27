package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Market
import dev.uem.em.model.CareerEntry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Development clubs (amateur, university, Game Changers) and academies have no transfer window: when one of
 * their players moves up to a senior team they sign a replacement at that position (the next day at the
 * latest), also in the first season, when senior rosters are frozen.
 */
class ReplacementsTest {
    @Test
    fun clubsReplacePlayersWhoMoveUp() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "replace", "R", Dates.day(2026, 11, 21))
        Advance.run(w, 2)
        assertTrue("first season: senior rosters frozen", Market.frozen(w))
        // An amateur club's starter signs for a professional team.
        val club = w.teams.first { it.active && it.kind == "amateur" && it.titleId == "valorant" && it.roster.size == 5 }
        val leaving = w.players[club.roster.first()]
        val role = leaving.roles.first()
        val pro = w.teams.first { it.titleId == "valorant" && it.leagueId >= 0 && it.kind.isEmpty() && it.parentId < 0 }
        leaving.teamId = pro.id; pro.roster.add(leaving.id); leaving.career.add(CareerEntry(w.season, w.day, pro.id, "signed"))
        Advance.run(w, 1)
        assertTrue("left the club", leaving.id !in club.roster)
        assertTrue("the club has a ${role} again", club.roster.any { w.players[it].roles.first() == role })
        // An academy's starter is called up to the senior team (an academy has no window either).
        val academy = w.teams.first { it.active && it.parentId >= 0 && !w.isUserTeam(it.id) && it.titleId == "lol" && Market.missingRoles(w, it).isEmpty() }
        val parent = w.teams[academy.parentId]
        val starter = w.players[academy.lineup.first { it >= 0 }]
        val pos = starter.mainRole
        // Only the starter plays that position in the academy.
        for (pid in academy.roster.toList()) if (pid != starter.id && w.players[pid].mainRole == pos) { academy.roster.remove(pid); w.players[pid].teamId = -1 }
        academy.roster.remove(starter.id); academy.lineup.replaceAll { if (it == starter.id) -1 else it }
        starter.teamId = parent.id; parent.roster.add(starter.id)
        assertTrue(pos in Market.missingRoles(w, academy))
        Advance.run(w, 1)
        assertTrue("the academy signed a ${pos}: ${academy.roster.map { w.players[it].mainRole }}", pos !in Market.missingRoles(w, academy))
        val signed = academy.roster.map { w.players[it] }.first { it.mainRole == pos }
        assertTrue("signed by contract", signed.teamId == academy.id)
    }
}

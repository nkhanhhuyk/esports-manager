package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Ambitions
import dev.uem.em.mgmt.Extras
import dev.uem.em.mgmt.Market
import dev.uem.em.sim.Lineups
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clubs sign for the roster they have: a position nobody plays is filled first, by someone who plays it;
 * a good player does not join to sit behind a better one at the position (unless young, at an academy or
 * content to be a substitute).
 */
class TransferFitTest {
    @Test
    fun signingsFitTheRoster() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "fit", "F", Dates.day(2026, 11, 21))
        w.firstSeason = w.season - 1 // past the first-season freeze
        val clubs = w.teams.filter { it.active && it.kind.isEmpty() && it.nationalOf.isEmpty() && it.parentId < 0 && !w.isUserTeam(it.id) && it.roster.size >= Catalogue.get(it.titleId).teamSize }
        // Open a hole at 40 clubs: a starter leaves (to free agency).
        val holes = HashMap<Int, String>()
        for (team in clubs.shuffled(java.util.Random(7)).take(40)) {
            val t = Catalogue.get(team.titleId)
            val lu = Lineups.ensure(w, team)
            val i = (team.id % t.teamSize)
            val pid = lu.getOrNull(i)?.takeIf { it >= 0 } ?: continue
            val role = t.lineup[i]
            // Only a real hole: nobody else on the roster has the position as main role.
            if (team.roster.any { it != pid && w.players[it].mainRole == role }) continue
            team.roster.remove(pid); w.players[pid].teamId = -1
            holes[team.id] = role
        }
        val before = w.transfers.size
        repeat(3) { Market.daily(w) }
        val signed = w.transfers.drop(before)
        var filled = 0; var stillMissing = 0
        for ((tid, role) in holes) {
            val team = w.teams[tid]
            if (team.roster.any { w.players[it].mainRole == role || w.players[it].roles.getOrNull(1) == role }) filled++
            else if (w.players.any { it.teamId < 0 && !it.retired && it.titleId == team.titleId && it.mainRole == role }) stillMissing++
        }
        // Good players who joined to sit behind a better player at their position.
        var benchStars = 0
        val notes = ArrayList<String>()
        for (tr in signed) {
            val p = w.players[tr.playerId]
            val team = w.teams.getOrNull(tr.toTeam) ?: continue
            if (p.teamId != team.id || team.parentId >= 0) continue
            val age = Dates.ageAt(p.birth, w.day)
            if (p.ca < 62 || age <= 20) continue
            val lu = Lineups.ensure(w, team)
            if (p.id in lu) continue
            val better = team.roster.map { w.players[it] }.firstOrNull { it.id != p.id && it.id in lu && it.mainRole == p.mainRole && Extras.selectionScore(w, it) > Extras.selectionScore(w, p) + 3 }
            if (better != null && Ambitions.benchRefusal(w, p, team) != null) { benchStars++; if (notes.size < 5) notes.add("${p.handle} (${p.ca.toInt()}) behind ${better.handle} at ${team.name}") }
        }
        println("holes ${holes.size}: filled $filled, still missing with free agents available $stillMissing · signings ${signed.size}, bench stars $benchStars ${notes.joinToString("; ")}")
        assertTrue("positions missing after the market although free agents play them: $stillMissing", stillMissing == 0)
        assertTrue("good players signed to sit behind better ones: $notes", benchStars == 0)
        assertTrue("holes were filled", filled >= holes.size * 0.9)
    }
}

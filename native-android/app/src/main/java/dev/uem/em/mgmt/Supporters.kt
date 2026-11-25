package dev.uem.em.mgmt

import dev.uem.em.core.clamp
import dev.uem.em.model.CompTier
import dev.uem.em.model.Competition
import dev.uem.em.model.Message
import dev.uem.em.model.Org
import dev.uem.em.model.Registration
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue

/**
 * Supporters by segment (as in the web version's fan segments): core fans are loyal and buy
 * merchandise, casual fans follow results, local fans follow the league, international fans follow
 * international events. Also competition registration: rosters are registered when a competition
 * starts; later signings use one of two amendments per competition.
 */
object Supporters {
    val SEGMENTS = listOf("Core", "Casual", "Local", "International")

    fun ensure(org: Org) {
        if (org.fanSeg.size == 4) return
        val f = org.fans
        org.fanSeg = doubleArrayOf(f * 0.25, f * 0.5, f * 0.15, f * 0.10)
    }

    private fun sync(org: Org) { org.fans = org.fanSeg.sum() }

    /** Monthly: segments follow last month's results and reputation. */
    fun monthly(w: World) {
        val since = w.day - 31
        for (org in w.orgs) {
            if (org.teams.none { w.teams[it].active && w.teams[it].nationalOf.isEmpty() }) continue
            ensure(org)
            var wins = 0
            var games = 0
            for (f in w.fixtures) if (f.done && f.day > since && (w.teams[f.a].orgId == org.id || w.teams[f.b].orgId == org.id)) { games++; if (w.teams[f.winner].orgId == org.id) wins++ }
            val form = if (games == 0) 0.0 else wins.toDouble() / games - 0.5
            org.fanSeg[0] *= 1 + 0.004 + org.rep / 40000
            org.fanSeg[1] *= clamp(1 + form * 0.08, 0.9, 1.1)
            org.fanSeg[2] *= 1 + form * 0.03
            org.fanSeg[3] *= 0.995
            sync(org)
        }
    }

    fun onCompetitionFinished(w: World, c: Competition) {
        c.placements.forEachIndexed { i, tid ->
            if (i > 7) return@forEachIndexed
            val org = w.orgs[w.teams[tid].orgId]
            ensure(org)
            val boost = (if (i == 0) 1.0 else if (i < 4) 0.4 else 0.15)
            when (c.tier) {
                CompTier.WORLDS, CompTier.INTERNATIONAL -> org.fanSeg[3] *= 1 + 0.12 * boost
                CompTier.LEAGUE -> { org.fanSeg[2] *= 1 + 0.06 * boost; org.fanSeg[0] *= 1 + 0.02 * boost }
                else -> {}
            }
            sync(org)
        }
    }

    /** Merchandise per month from the segments. */
    fun merchandise(org: Org, econ: Double): Double {
        ensure(org)
        return (org.fanSeg[0] * 0.03 + org.fanSeg[1] * 0.006 + org.fanSeg[2] * 0.012 + org.fanSeg[3] * 0.008) * (0.4 + econ) * (0.6 + org.rep / 250)
    }

    // ------------------------------------------------------------------ registration

    fun register(w: World, c: Competition) {
        val t = Catalogue.get(c.titleId)
        for (tid in c.teamIds) {
            val team = w.teams[tid]
            // Only players eligible under the event's rules (amateur status, enrolment, age, specialized circuit) are registered.
            c.registrations[tid] = Registration(team.roster.filter { !w.players[it].retired && Circuits.eligible(w, c, w.players[it]) == null }.sortedByDescending { w.players[it].ca }.take(t.teamSize + 4).toMutableList())
        }
    }

    /** The lineup a team may field in a competition: unregistered players are replaced by registered ones, or registered with an amendment. */
    fun lineupFor(w: World, c: Competition, team: Team, lineup: List<Int>): List<Int> {
        val reg = c.registrations[team.id] ?: return lineup
        if (lineup.all { it < 0 || it in reg.players }) return lineup
        val t = Catalogue.get(team.titleId)
        val out = lineup.toMutableList()
        for (i in out.indices) {
            val pid = out[i]
            if (pid < 0 || pid in reg.players) continue
            // A registered substitute who plays the position (main, then second); one out of position only as a last resort.
            val role = t.lineupRoles[i]
            val subs = reg.players.filter { it in team.roster && it !in out && dev.uem.em.sim.Lineups.available(w, w.players[it]) && Circuits.eligible(w, c, w.players[it]) == null }
                .sortedWith(compareBy<Int>({ dev.uem.em.sim.Subs.tier(w.players[it], role.id) }, { -dev.uem.em.sim.Ratings.effectiveness(w.players[it], t, role) }))
            val inPos = subs.firstOrNull { dev.uem.em.sim.Subs.plays(w.players[it], role.id) }
            val sub = inPos ?: subs.firstOrNull()
            if (inPos != null && (reg.amendments >= 2 || w.players[inPos].ca >= w.players[pid].ca - 3 || Circuits.eligible(w, c, w.players[pid]) != null)) out[i] = inPos
            else if (reg.amendments < 2 && Circuits.eligible(w, c, w.players[pid]) == null) {
                reg.players.add(pid)
                reg.amendments++
                if (w.isUserTeam(team.id)) w.inbox.add(Message(w.day, c.short, "Registration amended: ${w.players[pid].handle}", "${w.players[pid].handle} was registered for ${c.name} (amendment ${reg.amendments} of 2)."))
            } else if (sub != null) out[i] = sub
        }
        return out
    }
}

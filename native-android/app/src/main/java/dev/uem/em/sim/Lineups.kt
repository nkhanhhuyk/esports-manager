package dev.uem.em.sim

import dev.uem.em.core.Dates
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue

/** Lineup selection: greedy maximum-effectiveness assignment of available players to positions. */
object Lineups {
    fun available(w: World, p: Player) = !p.retired && p.injuryUntil <= w.day && p.visaUntil <= w.day

    fun auto(w: World, team: Team): MutableList<Int> {
        val t = Catalogue.get(team.titleId)
        var players = team.roster.map { w.players[it] }.filter { available(w, it) && Dates.ageAt(it.birth, w.day) >= t.minAge }
        if (players.size < t.teamSize) players = team.roster.map { w.players[it] }.filter { !it.retired }
        val roles = t.lineupRoles
        class Pair(val p: Player, val ri: Int, val e: Double)
        val pairs = ArrayList<Pair>(players.size * roles.size)
        for (p in players) {
            val pp = if (t.characters.isNotEmpty()) Ratings.charProf(p, t, roles[0]) else null
            for (i in roles.indices) {
                val role = roles[i]
                val natural = if (p.roles[0] == role.id) 1.5 else if (p.roles.getOrNull(1) == role.id) 0.5 else 0.0
                pairs.add(Pair(p, i, Ratings.effectiveness(p, t, role, null, pp) + natural - p.fatigue * 0.03))
            }
        }
        pairs.sortByDescending { it.e }
        val slots = IntArray(roles.size) { -1 }
        val used = HashSet<Int>()
        // League import limit: once the lineup holds the allowed number of imports, other imports are skipped.
        val cap = dev.uem.em.mgmt.Imports.cap(w, team)
        var imports = 0
        for (pr in pairs) {
            if (slots[pr.ri] >= 0 || pr.p.id in used) continue
            val imp = cap >= 0 && dev.uem.em.mgmt.Imports.isImport(w, pr.p, team)
            if (imp && imports >= cap) continue
            slots[pr.ri] = pr.p.id
            used.add(pr.p.id)
            if (imp) imports++
        }
        // A slot nobody local can fill still gets the best remaining player (an empty seat is worse).
        for (i in slots.indices) if (slots[i] < 0) pairs.firstOrNull { it.ri == i && it.p.id !in used }?.let { slots[i] = it.p.id; used.add(it.p.id) }
        return slots.toMutableList()
    }

    /** Keep the lineup unless it is broken or the coach's automatic choice could have changed. */
    fun ensure(w: World, team: Team): List<Int> {
        val t = Catalogue.get(team.titleId)
        val valid = team.lineup.size == t.teamSize && team.lineup.all { it >= 0 && it in team.roster && available(w, w.players[it]) }
        var sig = 17
        for (id in team.roster) {
            val p = w.players[id]
            sig = sig * 31 + id
            sig = sig * 31 + p.attrVersion
            sig = sig * 31 + (if (available(w, p)) 0 else 1)
            sig = sig * 31 + (p.fatigue / 25).toInt()
        }
        if (team.manualLineup && valid) return team.lineup
        if (!valid || sig != team.lineupSig) {
            team.lineup = auto(w, team)
            team.lineupSig = sig
        }
        return team.lineup
    }

    /** Team strength: mean effectiveness of the lineup in its positions. */
    fun strength(w: World, team: Team): Double {
        val t = Catalogue.get(team.titleId)
        val ids = team.lineup.ifEmpty { auto(w, team) }
        val vals = ids.mapIndexedNotNull { i, id -> if (id >= 0) Ratings.effectiveness(w.players[id], t, t.lineupRoles[i]) else null }
        return if (vals.isEmpty()) 40.0 else vals.sum() / t.teamSize
    }
}

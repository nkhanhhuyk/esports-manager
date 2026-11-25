package dev.uem.em.sim

import dev.uem.em.core.Dates
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue

/** Lineup selection: greedy maximum-effectiveness assignment of available players to positions. */
object Lineups {
    fun available(w: World, p: Player) = !p.retired && p.injuryUntil <= w.day && p.visaUntil <= w.day

    /**
     * The coach's lineup: every position goes to a player whose main position it is, else one who lists
     * it as his second; a player out of position only when nobody available plays it at all. Within
     * those, the best fit for the position (ability there, character pool, freshness); the league's
     * import limit holds throughout.
     */
    fun auto(w: World, team: Team): MutableList<Int> {
        val t = Catalogue.get(team.titleId)
        var players = team.roster.map { w.players[it] }.filter { available(w, it) && Dates.ageAt(it.birth, w.day) >= t.minAge }
        if (players.size < t.teamSize) players = team.roster.map { w.players[it] }.filter { !it.retired }
        val roles = t.lineupRoles
        class Pair(val p: Player, val ri: Int, val e: Double, val tier: Int)
        val pairs = ArrayList<Pair>(players.size * roles.size)
        for (p in players) {
            val pp = if (t.characters.isNotEmpty()) Ratings.charProf(p, t, roles[0]) else null
            for (i in roles.indices) {
                val role = roles[i]
                // 0: main position, 1: second position, 2: out of position.
                val tier = if (p.roles[0] == role.id) 0 else if (p.roles.getOrNull(1) == role.id) 1 else 2
                pairs.add(Pair(p, i, Ratings.effectiveness(p, t, role, null, pp) - p.fatigue * 0.03, tier))
            }
        }
        val slots = IntArray(roles.size) { -1 }
        val used = HashSet<Int>()
        // League import limit: once the lineup holds the allowed number of imports, other imports are skipped.
        val cap = dev.uem.em.mgmt.Imports.cap(w, team)
        var imports = 0
        // Main positions first, then second positions, then (only for a slot nobody can play) anyone.
        fun pass(tier: Int) {
            for (pr in pairs.filter { it.tier <= tier }.sortedWith(compareBy<Pair> { it.tier }.thenByDescending { it.e })) {
                if (slots[pr.ri] >= 0 || pr.p.id in used) continue
                val imp = cap >= 0 && dev.uem.em.mgmt.Imports.isImport(w, pr.p, team)
                if (imp && imports >= cap) continue
                slots[pr.ri] = pr.p.id
                used.add(pr.p.id)
                if (imp) imports++
            }
        }
        pass(0); pass(1)
        // A slot still empty: move a placed player who also plays it (main or second position) and refill his
        // slot the same way, before anyone is put out of position (a second-position pick taking a main
        // player's slot left the main player's partner position to an out-of-position player).
        if (slots.any { it < 0 }) {
            val byId = players.associateBy { it.id }
            fun plays(p: Player, ri: Int) = p.roles[0] == roles[ri].id || p.roles.getOrNull(1) == roles[ri].id
            fun eff(p: Player, ri: Int) = pairs.firstOrNull { it.p.id == p.id && it.ri == ri }?.e ?: 0.0
            fun fill(ri: Int, seen: BooleanArray): Boolean {
                val free = players.filter { it.id !in used && plays(it, ri) && !(cap >= 0 && imports >= cap && dev.uem.em.mgmt.Imports.isImport(w, it, team)) }.maxByOrNull { eff(it, ri) }
                if (free != null) {
                    slots[ri] = free.id; used.add(free.id)
                    if (cap >= 0 && dev.uem.em.mgmt.Imports.isImport(w, free, team)) imports++
                    return true
                }
                for (j in slots.indices) {
                    val pid = slots[j]
                    if (j == ri || seen[j] || pid < 0 || !plays(byId[pid] ?: continue, ri)) continue
                    seen[j] = true
                    slots[ri] = pid; slots[j] = -1
                    if (fill(j, seen)) return true
                    slots[j] = pid; slots[ri] = -1
                }
                return false
            }
            for (i in slots.indices) if (slots[i] < 0) fill(i, BooleanArray(slots.size))
        }
        pass(2)
        // A slot nobody local can fill still gets the best remaining player (an empty seat is worse).
        for (i in slots.indices) if (slots[i] < 0) pairs.filter { it.ri == i && it.p.id !in used }.minWithOrNull(compareBy<Pair> { it.tier }.thenByDescending { it.e })?.let { slots[i] = it.p.id; used.add(it.p.id) }
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

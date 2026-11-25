package dev.uem.em.mgmt

import dev.uem.em.core.clamp
import dev.uem.em.data.GameData
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue

/**
 * Chemistry between teammates: time played together, a shared language (or a language barrier),
 * personalities (two big egos clash, loyal players bond), settled lane / site partners. A lineup's
 * chemistry mixes its pairs with the team's drilled cohesion (scrims, a settled roster) and changes
 * how well the team plays together in matches.
 */
object Chemistry {
    class Pair(val a: Int, val b: Int, val value: Double, val reasons: List<String>)

    private fun langs(nat: String) = GameData.countries[nat]?.langs ?: emptyList()

    fun pair(w: World, a: Player, b: Player): Pair {
        val reasons = ArrayList<String>()
        var v = 50.0
        if (a.teamId >= 0 && a.teamId == b.teamId) {
            val days = w.day - maxOf(a.joinedDay, b.joinedDay)
            v += minOf(22.0, days / 15.0)
            if (days > 300) reasons.add("a year together") else if (days < 45) reasons.add("new together")
        }
        val la = langs(a.nat); val lb = langs(b.nat)
        when {
            a.nat == b.nat -> { v += 12.0; reasons.add("same country") }
            la.any { it in lb } -> { v += 8.0; reasons.add("shared language") }
            "en" in la && "en" in lb -> v += 2.0
            else -> { v -= 12.0; reasons.add("language barrier") }
        }
        val egoA = a.pers.getOrElse(3) { 50 }; val egoB = b.pers.getOrElse(3) { 50 }
        if (egoA > 75 && egoB > 75) { v -= 10.0; reasons.add("big egos") }
        if (a.pers.getOrElse(2) { 50 } > 82 || b.pers.getOrElse(2) { 50 } > 82) { v -= 4.0; reasons.add("short temper") }
        if (a.pers.getOrElse(1) { 50 } > 70 && b.pers.getOrElse(1) { 50 } > 70) { v += 3.0; reasons.add("loyal pair") }
        // Settled duo (bot lane carry and support; in shooters any pair counts through the team value).
        val t = Catalogue.get(a.titleId)
        if (t.isMoba) {
            val duo = setOf(a.mainRole, b.mainRole)
            val lanes = t.moba?.lanes ?: emptyList()
            if (lanes.any { it.second.size > 1 && duo.containsAll(it.second.take(2)) } && w.day - maxOf(a.joinedDay, b.joinedDay) > 120) { v += 5.0; reasons.add("settled duo") }
        }
        return Pair(a.id, b.id, clamp(v, 10.0, 95.0), reasons)
    }

    /** Chemistry of a lineup (0..100): its pairs and the team's drilled cohesion. */
    fun lineup(w: World, team: Team, lineup: List<Int>): Double {
        val ps = lineup.filter { it >= 0 }.map { w.players[it] }
        if (ps.size < 2) return team.chemistry
        var sum = 0.0; var n = 0
        for (i in ps.indices) for (j in i + 1 until ps.size) { sum += pair(w, ps[i], ps[j]).value; n++ }
        return clamp(0.55 * (sum / n) + 0.45 * team.chemistry, 5.0, 99.0)
    }

    /** Best and worst pairs of the starting lineup (for the team page). */
    fun pairs(w: World, team: Team): List<Pair> {
        val ps = (team.lineup.filter { it >= 0 }.ifEmpty { team.roster }).map { w.players[it] }
        val out = ArrayList<Pair>()
        for (i in ps.indices) for (j in i + 1 until ps.size) out.add(pair(w, ps[i], ps[j]))
        return out.sortedByDescending { it.value }
    }
}

/**
 * League rules for imports: a league allows a number of players from outside its region in a
 * lineup (by game and region; the commissioner can change it). Imports need a work visa after they
 * join a team from another region, so they cannot play for the first weeks.
 */
object Imports {
    fun leagueOf(w: World, team: Team) = w.leagues.getOrNull(team.leagueId)

    /** Imports allowed in a starting lineup (-1: no limit). */
    fun cap(w: World, team: Team): Int {
        if (team.nationalOf.isNotEmpty()) return -1
        val lg = leagueOf(w, team) ?: return -1
        w.importRules[lg.id]?.let { return it }
        return defaultCap(lg.titleId, lg.region)
    }

    fun defaultCap(titleId: String, region: String): Int = when {
        titleId == "cs2" -> -1
        region.startsWith("INT") -> -1
        titleId == "lol" && (region.startsWith("EU") || region == "EUW") -> 2
        else -> 2
    }

    /** Whether a player counts as an import in this team's league. */
    fun isImport(w: World, p: Player, team: Team): Boolean {
        val lg = leagueOf(w, team) ?: return false
        val regions = GameData.expand(lg.region).ifEmpty { GameData.expand(team.region) }
        if (regions.isEmpty()) return false
        // Either nationality makes a player local, and so does residency (three straight seasons in the country).
        return p.nats().none { GameData.regionOf(it) in regions } && (p.residency.isEmpty() || GameData.regionOf(p.residency) !in regions)
    }

    /** Visa wait for a player joining a team (days; 0 when none is needed). */
    fun onJoin(w: World, p: Player, team: Team) {
        p.visaUntil = if (isImport(w, p, team) && GameData.regionOf(p.nat) != (GameData.expand(team.region).firstOrNull() ?: "")) w.day + 14 else 0
        // Joining a club ends any development-club registration the same day.
        Circuits.leaveClubs(w, p, team)
    }

    fun ruleText(w: World, team: Team): String {
        val c = cap(w, team)
        return if (c < 0) "no import limit" else "at most $c import${if (c == 1) "" else "s"} in the lineup"
    }
}

package dev.uem.em.mgmt

import dev.uem.em.data.GameData
import dev.uem.em.model.Competition
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue

/**
 * World rankings, from results only (like the real circuit rankings): every finished competition
 * gives points by placing, scaled by its prestige (World Championship 100, mid-season events 70,
 * top leagues 25-55, ...). Clubs count the last 12 months (the older half at half value); countries
 * and territories count their national team's Nations Cup and qualifier results over two years plus
 * a quarter of the club points their players earned. Until there are results, the order is by strength.
 */
object Rankings {
    class ClubRow(val teamId: Int, val points: Double, val best: List<Pair<Int, Double>>, val strength: Double)
    class OrgRow(val orgId: Int, val points: Double, val teams: List<Int>)
    class NationRow(val nat: String, val points: Double, val national: Double, val players: Double, val strength: Double, val teamId: Int, val pros: Int)

    /** Share of a competition's points by final placing. */
    fun share(place: Int): Double = when {
        place == 1 -> 1.0
        place == 2 -> 0.7
        place <= 4 -> 0.5
        place <= 8 -> 0.3
        place <= 16 -> 0.15
        else -> 0.05
    }

    /** Day of a competition's last series (its start when nothing was played). */
    fun endDay(w: World, c: Competition): Int {
        var d = c.startDay
        for (s in c.stages) for (fid in s.fixtureIds) d = maxOf(d, w.fixtures[fid].day)
        return d
    }

    fun isNational(w: World, c: Competition) = c.teamIds.any { w.teams[it].nationalOf.isNotEmpty() }

    private fun results(w: World, titleId: String?, national: Boolean, window: Int): Map<Int, MutableList<Pair<Int, Double>>> {
        val out = HashMap<Int, MutableList<Pair<Int, Double>>>()
        for (c in w.comps) {
            if (!c.done || c.placements.isEmpty() || (titleId != null && c.titleId != titleId)) continue
            if (isNational(w, c) != national) continue
            val age = w.day - endDay(w, c)
            if (age < 0 || age > window) continue
            val decay = if (age <= window / 2) 1.0 else 0.5
            c.placements.forEachIndexed { i, tid -> out.getOrPut(tid) { ArrayList() }.add(c.id to c.prestige * 10.0 * share(i + 1) * decay) }
        }
        return out
    }

    /** Club ranking of a game: every active club team, best first. */
    fun clubs(w: World, titleId: String): List<ClubRow> {
        val res = results(w, titleId, false, 365)
        return w.teams.filter { it.titleId == titleId && it.active && it.nationalOf.isEmpty() }.map { t ->
            val r = res[t.id].orEmpty()
            ClubRow(t.id, r.sumOf { it.second }, r.sortedByDescending { it.second }.take(3), t.elo)
        }.sortedWith(compareByDescending<ClubRow> { it.points }.thenByDescending { it.strength })
    }

    /** Organisations over every game (the sum of their teams' club points). */
    fun orgs(w: World, titles: List<String>): List<OrgRow> {
        val pts = HashMap<Int, Double>()
        val teams = HashMap<Int, MutableList<Int>>()
        for (id in titles) for (r in clubs(w, id)) {
            val org = w.teams[r.teamId].orgId
            pts[org] = (pts[org] ?: 0.0) + r.points
            teams.getOrPut(org) { ArrayList() }.add(r.teamId)
        }
        return pts.entries.map { OrgRow(it.key, it.value, teams[it.key].orEmpty()) }
            .sortedWith(compareByDescending<OrgRow> { it.points }.thenByDescending { r -> r.teams.maxOfOrNull { w.teams[it].elo } ?: 0.0 })
    }

    /** Countries and territories of a game (every inhabited one), best first. */
    fun nations(w: World, titleId: String): List<NationRow> {
        val t = Catalogue.get(titleId)
        val national = results(w, titleId, true, 730)
        val players = HashMap<String, Double>()
        for (r in clubs(w, titleId)) {
            if (r.points <= 0) continue
            val tm = w.teams[r.teamId]
            val starters = tm.lineup.filter { it >= 0 }.ifEmpty { tm.roster.take(t.teamSize) }
            for (pid in starters) { val nat = w.players[pid].nat; players[nat] = (players[nat] ?: 0.0) + r.points / t.teamSize * 0.25 }
        }
        val pros = w.players.filter { it.titleId == titleId && !it.retired && it.teamId >= 0 }.groupBy { it.nat }
        val pool = w.players.filter { it.titleId == titleId && !it.retired }.groupBy { it.nat }
        val teamOf = w.teams.filter { it.titleId == titleId && it.nationalOf.isNotEmpty() }.associateBy { it.nationalOf }
        return GameData.countries.values.filter { it.code !in Extras.UNINHABITED }.map { c ->
            val team = teamOf[c.code]
            val nat = team?.let { national[it.id] }.orEmpty().sumOf { it.second }
            val pl = players[c.code] ?: 0.0
            val strength = pool[c.code]?.sortedByDescending { it.ca }?.take(t.teamSize)?.map { it.ca }?.average() ?: 0.0
            NationRow(c.code, nat + pl, nat, pl, strength, team?.id ?: -1, pros[c.code]?.size ?: 0)
        }.sortedWith(compareByDescending<NationRow> { it.points }.thenByDescending { it.strength }.thenBy { GameData.countries[it.nat]?.name ?: it.nat })
    }

    /** Countries and territories over every game (the sum of their points in each game). */
    fun nationsAll(w: World, titles: List<String>): List<NationRow> {
        val sum = HashMap<String, DoubleArray>()
        for (id in titles) for (r in nations(w, id)) {
            val a = sum.getOrPut(r.nat) { DoubleArray(5) }
            a[0] += r.points; a[1] += r.national; a[2] += r.players; a[3] += r.strength / titles.size; a[4] += r.pros
        }
        return sum.entries.map { (nat, a) -> NationRow(nat, a[0], a[1], a[2], a[3], -1, a[4].toInt()) }
            .sortedWith(compareByDescending<NationRow> { it.points }.thenByDescending { it.strength }.thenBy { GameData.countries[it.nat]?.name ?: it.nat })
    }
}

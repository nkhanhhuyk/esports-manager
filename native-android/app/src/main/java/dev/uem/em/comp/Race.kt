package dev.uem.em.comp

import dev.uem.em.model.Competition
import dev.uem.em.model.League
import dev.uem.em.model.World

/**
 * The qualification race of a league toward its game's world championship, read from the season's actual
 * routes (the Worlds entry rules, regional finals, play-in and the best-of-the-rest invitational) and the
 * circuit points, not from a second set of standings. Statuses are distinct: a berth earned, a route still
 * open, a projection (labelled as one) or out. Points mode has a clinch / elimination check against the
 * most points still available; placings mode waits for the last split's playoffs.
 */
object Race {
    class Row(val teamId: Int, val points: Int, val status: String, val detail: String, val good: Boolean?)

    private fun comp(w: World, season: Int, key: String) = w.comps.firstOrNull { it.season == season && it.defKey == key }

    /** Most circuit points a team can still earn this season (events not finished yet). */
    fun maxRemaining(w: World, league: League, season: Int): Int {
        var r = 0.0
        for (c in w.comps) {
            if (c.season != season || c.titleId != league.titleId || c.done || c.circuitPoints.isEmpty()) continue
            if (c.leagueId >= 0 && c.leagueId != league.id) continue
            r += (c.circuitPoints.firstOrNull() ?: 0) * Season.splitWeight(c.split)
        }
        return r.toInt()
    }

    /** The next event that awards this league's teams circuit points, and its points for 1st, 2nd and last place. */
    fun nextEvent(w: World, league: League, season: Int): Triple<Competition, Double, Double>? {
        val c = w.comps.filter { it.season == season && it.titleId == league.titleId && !it.done && it.circuitPoints.isNotEmpty() && (it.leagueId == league.id || it.leagueId < 0) }.minByOrNull { it.startDay } ?: return null
        val wt = Season.splitWeight(c.split)
        return Triple(c, (c.circuitPoints.firstOrNull() ?: 0) * wt, (c.circuitPoints.getOrNull(1) ?: 0) * wt)
    }

    /**
     * The race, optionally under a what-if: [bonus] adds circuit points to teams (a hypothetical result, computed
     * here only — nothing is written to the world). Results already decided are not changed by it.
     */
    fun league(w: World, league: League, season: Int, bonus: Map<Int, Double> = emptyMap()): List<Row> {
        val t = league.titleId
        val worlds = comp(w, season, "$t:worlds") ?: return emptyList()
        val pi = comp(w, season, "$t:worlds:playin")
        val rf = comp(w, season, "$t:rf:L${league.id}")?.takeIf { !it.name.endsWith("(cancelled)") }
        val rest = comp(w, season, "$t:dcgi")
        val pts = HashMap(Progress.circuitPoints(w, season, t)).also { m -> for ((k, v) in bonus) m[k] = (m[k] ?: 0.0) + v }
        val teams = league.teamIds.filter { w.teams[it].active }.sortedWith(compareByDescending<Int> { pts[it] ?: 0.0 }.thenByDescending { w.teams[it].elo })
        val rule = worlds.entrants.firstOrNull { it.startsWith("circuit:${league.id}:") }
            ?: pi?.entrants?.firstOrNull { it.startsWith("pseed:${worlds.id}:${league.id}:") }?.split(':')?.let { "circuit:${league.id}:${it[4]}:0:99:${it.getOrElse(5) { "placings" }}" }
            ?: return teams.map { Row(it, (pts[it] ?: 0.0).toInt(), "No Worlds route", "This league has no place at ${worlds.short} this season", false) }
        val places = rule.split(':')[2].toInt()
        val points = rule.split(':').getOrNull(5) == "points"
        fun p(tid: Int) = (pts[tid] ?: 0.0).toInt()
        // Worlds (or its play-in) has its entrants: the ledger says it all.
        val started = worlds.status != 0 || (pi != null && pi.status != 0)
        if (started) return teams.map { tid ->
            val main = worlds.entries.firstOrNull { it.teamId == tid && it.status == "in" }
            val play = pi?.entries?.firstOrNull { it.teamId == tid && it.status == "in" }
            when {
                main != null -> Row(tid, p(tid), "Main-stage berth", Ecosystem.whyText(w, main), true)
                play != null -> Row(tid, p(tid), "Play-In berth", Ecosystem.whyText(w, play) + if (pi?.done == true) (if (tid in worlds.teamIds) " — through to the main event" else " — out in the play-in") else "", true)
                rest != null && tid in rest.teamIds -> Row(tid, p(tid), "Best of the Rest", "Plays ${rest.short}", null)
                rf != null && tid in rf.teamIds -> Row(tid, p(tid), "Eliminated", "Lost in ${rf.short}", false)
                rest != null && rest.status == 0 -> Row(tid, p(tid), "Best-of-the-Rest eligible", "Missed ${worlds.short}; ${rest.short} places go by circuit points", null)
                else -> Row(tid, p(tid), "Eliminated", "Did not reach ${worlds.short}", false)
            }
        }
        // Splits over: the direct places are known; regional finals may still be running.
        val order = Progress.leagueOrder(w, worlds, league, rule)
        val direct = order ?: Progress.leagueOrder(w, worlds, league, rule, directOnly = true)
        if (direct != null) return teams.map { tid ->
            val e = direct.firstOrNull { it.teamId == tid }
            when {
                e != null && (e.src < 0 || e.src != rf?.id) -> Row(tid, p(tid), "Locked", e.how, true)
                e != null -> Row(tid, p(tid), "LCQ-qualified", e.how, true)
                rf != null && tid in rf.teamIds -> Row(tid, p(tid), if (rf.done) "Eliminated" else "LCQ eligible", if (rf.done) "Out in ${rf.short}" else "Plays ${rf.short} for the remaining place(s)", if (rf.done) false else null)
                rf != null && rf.status == 0 -> Row(tid, p(tid), "Awaiting decisions", "${rf.short} entrants are the next six on points after the direct places", null)
                else -> Row(tid, p(tid), "Best-of-the-Rest eligible", "Missed the Worlds places", null)
            }
        }
        // Season running: projections from today's points table (labelled), and a clinch / elimination check in points mode.
        val rem = maxOf(0, maxRemaining(w, league, season) - (if (bonus.isNotEmpty()) (nextEvent(w, league, season)?.second ?: 0.0).toInt() else 0))
        val d = Formats.direct(places)
        val lcqN = if (rf != null || places - d > 0) minOf(6, teams.size - places) else 0
        return teams.mapIndexed { i, tid ->
            val mine = p(tid)
            val cut = teams.getOrNull(places)?.let { p(it) } ?: 0          // first team outside the places
            val lastIn = teams.getOrNull(places - 1)?.let { p(it) } ?: 0
            when {
                points && i < places && mine - cut > rem -> Row(tid, mine, "Locked", "Clinched on points: ${mine - cut} ahead of the cut with at most $rem still available", true)
                points && i >= places && lastIn - mine > rem && i >= places + lcqN -> Row(tid, mine, "Eliminated", "${lastIn - mine} behind the cut with at most $rem still available", false)
                !points && i < d -> Row(tid, mine, "Awaiting decisions", "The last split's playoffs decide the direct places (projected in the race)", null)
                i < places -> Row(tid, mine, if (points) "Direct berth (projected)" else "Conditional", if (points) "Inside the top $places on points today" else "In range for a place beyond the playoff ones", null)
                i < places + lcqN -> Row(tid, mine, "LCQ eligible (projected)", "Next ${lcqN} on points after the direct places play the regional finals", null)
                else -> Row(tid, mine, "Still mathematically alive", "$rem points still available; best of the rest if they miss out", null)
            }
        }
    }

    /** The league's world championship and its routes, for headings. */
    fun worldsOf(w: World, titleId: String, season: Int): Competition? = comp(w, season, "$titleId:worlds")
}

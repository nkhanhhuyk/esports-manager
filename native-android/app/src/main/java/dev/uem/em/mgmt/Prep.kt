package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.clamp
import dev.uem.em.model.Fixture
import dev.uem.em.model.PrepPlan
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.sim.SeriesSim
import dev.uem.em.titles.Catalogue
import kotlin.math.sqrt

/**
 * Pre-match preparation and the strategy laboratory.
 *
 * A plan chooses where the week's preparation goes — studying the opponent ("counter": better
 * counter-picks and bans in the draft, the opponent's best map avoided in the veto), deepening comfort
 * picks ("comfort": more scrim familiarity), a map ("map": shooters keep it in the veto), or rest
 * ("rest": the starters recover, no study) — plus the composition identity a MOBA team drafts toward.
 * The manager decides at this level; the coach still drafts and plays. The plan is locked when the
 * match starts (an AI or delegated team gets its coach's plan then), and only the share the analysts
 * and coaches can deliver that week counts. Preparation costs a little freshness (except rest).
 *
 * The laboratory replays the upcoming series many times on the real engines with different plans and
 * reports win ranges: sandbox only — nothing is recorded, paid or applied.
 */
object Prep {
    val EMPHASES = listOf("balanced", "counter", "comfort", "map", "rest")
    val LABEL = mapOf("balanced" to "Balanced (general practice)", "counter" to "Counter the opponent", "comfort" to "Comfort picks", "map" to "Map focus", "rest" to "Rest")

    fun planFor(w: World, fixtureId: Int, teamId: Int): PrepPlan? = w.preps.lastOrNull { it.fixtureId == fixtureId && it.teamId == teamId }

    fun edit(w: World, fixtureId: Int, teamId: Int): PrepPlan? {
        val existing = planFor(w, fixtureId, teamId)
        if (existing != null) return if (existing.locked) null else existing
        return PrepPlan(fixtureId, teamId).also { w.preps.add(it) }
    }

    /** The plan that drives a series: the locked plan of each side (nothing before the lock). */
    fun effective(w: World, f: Fixture, side: Int): PrepPlan? = planFor(w, f.id, if (side == 0) f.a else f.b)?.takeIf { it.locked }

    /** Preparation work a team's staff can deliver in a week (analysts and coaches). */
    fun capacity(w: World, team: Team): Double {
        val a = Economy.staffOf(w, team, "analyst").maxOfOrNull { it.quality } ?: 25.0
        val c = Economy.coachQuality(w, team)
        return 1.0 + (a + c) / 70
    }

    private val STUDY = setOf("counter", "comfort", "map")

    /** Share of a study plan the staff can deliver now: weekly capacity split over this week's prepared matches. */
    fun expectedEffect(w: World, team: Team, emphasis: String): Double {
        if (emphasis !in STUDY) return 1.0
        val busy = w.preps.count { it.teamId == team.id && it.locked && it.lockedDay >= w.day - 6 && it.emphasis in STUDY }
        return clamp(capacity(w, team) / (busy + 1), 0.25, 1.0)
    }

    fun explain(emphasis: String) = when (emphasis) {
        "counter" -> "The analysts study the opponent: the coach drafts and bans with a better read of their picks (MOBA), and their strongest map is avoided in the veto (shooters). Costs a little freshness."
        "comfort" -> "The team drills its own best characters: those picks and what scrims taught count for more in the draft. Costs a little freshness."
        "map" -> "Shooters: the chosen map is kept in the veto (picked, never banned). Costs a little freshness."
        "rest" -> "No study: the starters recover freshness before the match."
        else -> "General practice: nothing specific, no extra workload."
    }

    /** The coach's own plan: rest a tired lineup, study an opponent there is evidence on, otherwise comfort (shooters: the best map). */
    fun autoPlan(w: World, f: Fixture, team: Team): PrepPlan {
        val opp = w.teams[if (team.id == f.a) f.b else f.a]
        val t = Catalogue.get(team.titleId)
        val lineup = team.lineup.filter { it >= 0 }.map { w.players[it] }
        val fatigue = if (lineup.isEmpty()) 0.0 else lineup.map { it.fatigue }.average()
        val seen = recentSeries(w)[opp.id]
        val analyst = Economy.staffOf(w, team, "analyst").maxOfOrNull { it.quality } ?: 25.0
        val plan = PrepPlan(f.id, team.id, by = "coach")
        when {
            fatigue > 45 -> { plan.emphasis = "rest"; plan.note = "lineup tired (fatigue %.0f)".format(fatigue) }
            seen >= 4 && analyst >= 45 -> { plan.emphasis = "counter"; plan.note = "$seen recent series of ${opp.short} to study" }
            !t.isMoba -> { plan.emphasis = "map"; plan.map = bestMap(w, team) ?: ""; plan.note = "best map" }
            else -> { plan.emphasis = "comfort"; plan.note = "deepen comfort picks" }
        }
        return plan
    }

    private var recentFor: World? = null
    private var recentDay = -1
    private var recent = IntArray(0)

    /** Series each team played in the last 120 days (what the analysts could have studied), once a day. */
    @Synchronized fun recentSeries(w: World): IntArray {
        if (recentFor === w && recentDay == w.day && recent.size == w.teams.size) return recent
        val out = IntArray(w.teams.size)
        val from = w.day - 120
        for (f in w.fixtures) {
            if (!f.done || f.day < from) continue
            if (f.a in out.indices) out[f.a]++
            if (f.b in out.indices) out[f.b]++
        }
        recentFor = w; recentDay = w.day; recent = out
        return out
    }

    fun bestMap(w: World, team: Team): String? {
        val t = Catalogue.get(team.titleId)
        if (t.maps.size <= 1) return null
        val lineup = team.lineup.filter { it >= 0 }.ifEmpty { team.roster }
        return t.maps.maxByOrNull { m -> lineup.sumOf { w.players[it].profMap[m.id] ?: 40.0 } }?.id
    }

    /**
     * Lock both sides' plans when a series starts: an unplanned AI (or delegated) team gets its coach's
     * plan; the staff's weekly capacity sets how much of it counts; the workload is charged once.
     */
    fun lock(w: World, f: Fixture) {
        for (tid in intArrayOf(f.a, f.b)) {
            if (tid < 0) continue
            val team = w.teams[tid]
            var plan = planFor(w, f.id, tid)
            if (plan?.locked == true) continue
            if (plan == null) {
                val user = w.isUserTeam(tid) && !Roles.delegated(w, "lineups")
                // Development clubs and national teams have no analysts' week to plan: general practice.
                if (!user && (team.kind.isNotEmpty() || team.nationalOf.isNotEmpty())) continue
                plan = if (user) PrepPlan(f.id, tid, by = "user (no plan: general practice)") else autoPlan(w, f, team)
                w.preps.add(plan)
            }
            plan.effect = expectedEffect(w, team, plan.emphasis)
            val lineup = team.lineup.filter { it >= 0 }.map { w.players[it] }
            when (plan.emphasis) {
                "rest" -> for (p in lineup) p.fatigue = clamp(p.fatigue - 12, 0.0, 100.0)
                "counter", "comfort", "map" -> for (p in lineup) p.fatigue = clamp(p.fatigue + 3, 0.0, 100.0)
            }
            plan.locked = true; plan.lockedDay = w.day
        }
        // Old plans are dropped (the match records keep their results): other teams' after two weeks, yours after half a year.
        if (w.preps.size > 3000) w.preps.removeAll { it.locked && it.lockedDay < w.day - (if (w.isUserTeam(it.teamId)) 180 else 14) }
    }

    /** What a plan changes in the series (read by the draft and veto). */
    fun knowledgeBonus(p: PrepPlan?) = if (p?.emphasis == "counter") 0.15 * p.effect else 0.0
    fun comfortMul(p: PrepPlan?) = if (p?.emphasis == "comfort") 1.0 + 0.3 * p.effect else 1.0

    /** Comfort preparation: what scrims taught counts more, and each starter's best characters feel familiar. */
    fun comfort(p: PrepPlan?, players: List<dev.uem.em.model.Player>, scrim: Map<String, Double>): Map<String, Double> {
        if (p?.emphasis != "comfort") return scrim
        val m = comfortMul(p)
        val out = HashMap<String, Double>()
        for ((k, v) in scrim) out[k] = v * m
        for (pl in players) for ((id, v) in pl.profChar.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).take(3)) {
            if (v < 55) continue
            out[id] = ((out[id] ?: 0.0) + 0.12 * p.effect).coerceAtMost(0.6)
        }
        return out
    }

    // ------------------------------------------------------------------ reports

    class Report(val games: Int, val newest: Int, val analyst: Double, val confidence: String, val lines: List<Pair<String, String>>)

    /** The opponent as observed (recorded matches only), with how much the sample says. Never hidden ratings. */
    fun opponentReport(w: World, f: Fixture, teamId: Int): Report {
        val oppId = if (teamId == f.a) f.b else f.a
        val opp = w.teams[oppId]
        val team = w.teams[teamId]
        val r = Scouting.report(w, oppId, teamId)
        val recent = w.fixtures.filter { it.done && (it.a == oppId || it.b == oppId) }.maxOfOrNull { it.day }
        val age = recent?.let { w.day - it } ?: 999
        val analyst = Economy.staffOf(w, team, "analyst").maxOfOrNull { it.quality } ?: 25.0
        val score = r.games * (0.5 + analyst / 100) * (if (age > 60) 0.6 else 1.0)
        val conf = when { score >= 18 -> "good"; score >= 8 -> "fair"; r.games > 0 -> "low"; else -> "none (no recorded games)" }
        val t = Catalogue.get(opp.titleId)
        val lines = ArrayList<Pair<String, String>>()
        lines.add("Record (observed)" to "${r.wins}-${r.games - r.wins} in the last ${r.series} series (${r.games} games)")
        lines.add("Form (observed)" to r.form)
        lines.add("Public rating (results)" to "Elo ${opp.elo.toInt()} — from results only")
        if (t.isMoba) {
            if (r.bans.isNotEmpty()) lines.add("Bans (observed)" to r.bans.take(4).joinToString { "${t.charIndex[it.first]?.name ?: it.first} ×${it.second}" })
            if (r.firstPicks.isNotEmpty()) lines.add("First picks (observed)" to r.firstPicks.take(3).joinToString { "${t.charIndex[it.first]?.name ?: it.first} ×${it.second}" })
            lines.add("Ban approach (analyst's reading)" to r.banApproach)
            lines.add("Sides (observed)" to "blue ${r.sideWins[0]}/${r.sideGames[0]}, red ${r.sideWins[1]}/${r.sideGames[1]}")
        } else {
            if (r.maps.isNotEmpty()) lines.add("Maps (observed)" to r.maps.sortedByDescending { it.second }.take(4).joinToString { "${t.mapName(it.first)} ${it.third}/${it.second}" })
            lines.add("Sides (observed)" to "first side ${r.sideWins[0]}/${r.sideGames[0]}, second ${r.sideWins[1]}/${r.sideGames[1]}")
        }
        lines.add("Style (assumed from the team's profile)" to (opp.style.ifBlank { "balanced" }))
        if (r.ideas.isNotEmpty()) lines.add("Analyst ideas (estimated)" to r.ideas.take(2).joinToString("; "))
        return Report(r.games, age, analyst, conf, lines)
    }

    class Ready(val playerId: Int, val fatigue: Double, val form: Double, val injured: Boolean, val roleKnow: Double, val pool: Int)

    fun readiness(w: World, team: Team): List<Ready> {
        val t = Catalogue.get(team.titleId)
        return team.lineup.mapIndexed { i, pid ->
            if (pid < 0) return@mapIndexed null
            val p = w.players[pid]
            val role = t.lineupRoles.getOrNull(i)
            val pool = if (t.characters.isEmpty() || role == null) 0 else p.profChar.entries.count { (id, v) -> v >= 60 && t.charIndex[id]?.fits(role) == true }
            Ready(pid, p.fatigue, p.form, p.injuryUntil > w.day, p.profRole["${t.id}:${role?.id}"] ?: 0.0, pool)
        }.filterNotNull()
    }

    /** The staff's recommendation and the reason, from the same evidence the coach uses. */
    fun recommend(w: World, f: Fixture, team: Team): Pair<PrepPlan, String> {
        val p = autoPlan(w, f, team)
        val why = when (p.emphasis) {
            "rest" -> "The starters are tired; resting them is worth more than study this week."
            "counter" -> "There is enough recorded play of the opponent (${p.note}) for the analysts to prepare counters."
            "map" -> "Our best map in practice is ${Catalogue.get(team.titleId).mapName(p.map)}; keep it in the veto."
            else -> "Little to go on about the opponent: sharpen our own comfort picks."
        }
        return p to why
    }

    // ------------------------------------------------------------------ strategy laboratory

    class Trial(val label: String, val plan: PrepPlan, val wins: Int, val n: Int, val lo: Double, val hi: Double)

    /** Wilson interval for a win share (80%). */
    fun interval(wins: Int, n: Int): Pair<Double, Double> {
        if (n == 0) return 0.0 to 1.0
        val z = 1.2816; val ph = wins.toDouble() / n
        val d = 1 + z * z / n
        val c = (ph + z * z / (2 * n)) / d
        val m = z * sqrt(ph * (1 - ph) / n + z * z / (4.0 * n * n)) / d
        return (c - m).coerceAtLeast(0.0) to (c + m).coerceAtMost(1.0)
    }

    /**
     * Replay the upcoming series [n] times per plan on the real engines (seeded, so the same request
     * gives the same answer). Sandbox only: throwaway fixtures, no records, nothing applied.
     */
    fun laboratory(w: World, f: Fixture, teamId: Int, options: List<Pair<String, PrepPlan>>, n: Int, progress: (Int, Int) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): List<Trial> {
        val side = if (teamId == f.a) 0 else 1
        val oppTeam = w.teams[if (side == 0) f.b else f.a]
        val oppPlan = planFor(w, f.id, oppTeam.id)?.takeIf { it.locked }
            ?: autoPlan(w, f, oppTeam).also { it.locked = true; it.effect = expectedEffect(w, oppTeam, it.emphasis) }
        fun lineup(team: Team) = if (team.lineup.size == Catalogue.get(team.titleId).teamSize && team.lineup.all { it >= 0 }) team.lineup.toList() else Lineups.auto(w, team).toList()
        val lineups = arrayOf(lineup(w.teams[f.a]), lineup(w.teams[f.b]))
        val out = ArrayList<Trial>()
        var done = 0
        for (opt in options) {
            val plan = PrepPlan(f.id, teamId, opt.second.emphasis, opt.second.style, opt.second.map, "lab", locked = true, effect = expectedEffect(w, w.teams[teamId], opt.second.emphasis))
            var wins = 0; var played = 0
            for (k in 0 until n) {
                if (cancelled()) break
                // Same seeds for every option: the options face the same luck.
                val tf = Fixture(-(1_000_000 + k), f.compId, f.stageIdx, f.titleId, f.day, f.a, f.b, f.bestOf, f.round, f.group, f.nodeId)
                val prep = arrayOfNulls<PrepPlan>(2); prep[side] = plan; prep[1 - side] = oppPlan
                val o = SeriesSim.run(w, tf, lineups, record = false, quick = false, prep = prep)
                if (o.winner == side) wins++
                played++
                progress(++done, options.size * n)
            }
            val (lo, hi) = interval(wins, played)
            out.add(Trial(opt.first, plan, wins, played, lo, hi))
        }
        return out
    }

    fun dayText(d: Int) = Dates.format(d, true)
}

package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.data.GameData
import dev.uem.em.model.CompTier
import dev.uem.em.model.Message
import dev.uem.em.model.News
import dev.uem.em.model.Org
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.titles.Catalogue
import kotlin.math.roundToInt

/** Injuries and breaks, scouting, national teams, difficulty, season preview and review. */
object Extras {
    // ------------------------------------------------------------------ injuries and breaks

    private val INJURIES = listOf("Wrist strain" to 6..18, "Back pain" to 4..12, "Tendinitis" to 10..28, "Illness" to 3..8, "Shoulder strain" to 5..14, "Eye strain" to 3..7)

    /** After a series: players who played can pick up an injury (more likely when tired). */
    fun afterSeries(w: World, fixtureId: Int, playerIds: Collection<Int>) {
        val rng = Rng.derive(w.seed, "injury", fixtureId)
        for (pid in playerIds) {
            val p = w.players[pid]
            if (p.injuryUntil > w.day) continue
            if (!rng.chance(0.0025 * (1 + p.fatigue / 40))) continue
            val (name, range) = rng.pick(INJURIES)
            p.injury = name
            p.injuryUntil = w.day + rng.int(range.first, range.last)
            if (w.isUserTeam(p.teamId)) w.inbox.add(Message(w.day, "Medical staff", "${p.handle}: ${name.lowercase()}", "${p.handle} is out until ${Dates.format(p.injuryUntil)} (${name.lowercase()})."))
        }
    }

    /** Weekly: exhausted or unhappy players take a break (one to two weeks). */
    fun weeklyBreaks(w: World) {
        val rng = Rng.derive(w.seed, "breaks", w.day)
        for (p in w.players) {
            if (p.retired || p.teamId < 0 || p.injuryUntil > w.day) continue
            val strain = (p.fatigue - 80).coerceAtLeast(0.0) / 20 + (25 - p.morale).coerceAtLeast(0.0) / 25
            if (strain <= 0 || !rng.chance(0.12 * strain)) continue
            p.injury = if (p.fatigue > 80) "Break: burnout" else "Break: personal reasons"
            p.injuryUntil = w.day + rng.int(7, 14)
            p.fatigue *= 0.4
            p.morale = clamp(p.morale + 15, 0.0, 100.0)
            if (w.isUserTeam(p.teamId)) w.inbox.add(Message(w.day, p.handle, "${p.handle} takes a break", "${p.handle} needs time away (${p.injury.removePrefix("Break: ")}) and returns on ${Dates.format(p.injuryUntil)}."))
        }
    }

    // ------------------------------------------------------------------ scouting

    fun scoutQuality(w: World): Double = w.orgs.getOrNull(w.userOrg)?.teams?.flatMap { tid -> w.teams[tid].staffIds.map { w.staff[it] } }?.filter { it.role == "scout" }?.maxOfOrNull { it.quality } ?: 25.0

    /** How well the user knows a player (0-100): own players fully, others through scouting. */
    fun knowledge(w: World, p: Player): Int = when {
        w.isUserTeam(p.teamId) -> 100
        else -> maxOf(w.scouting[p.id] ?: 0, (15 + scoutQuality(w) * 0.25).roundToInt() + (if (p.rep > 70) 25 else 0))
    }

    /** Potential as the user sees it: exact when well known, else a range. */
    fun potentialText(w: World, p: Player): String {
        val k = knowledge(w, p)
        if (k >= 80) return "%.0f".format(p.pa)
        val spread = ((100 - k) / 8.0).roundToInt().coerceAtLeast(2)
        val mid = p.pa + ((p.id * 37 % 7) - 3) * (100 - k) / 100.0
        return "${(mid - spread).roundToInt()}–${(mid + spread).roundToInt().coerceAtMost(99)}"
    }

    fun scout(w: World, playerId: Int) { if (playerId !in w.scoutQueue) w.scoutQueue.add(playerId) }

    /** Weekly scouting progress on the queue (up to three players a week). */
    fun weeklyScouting(w: World) {
        if (w.scoutQueue.isEmpty()) return
        val q = scoutQuality(w)
        for (pid in w.scoutQueue.take(3)) {
            val k = minOf(100, maxOf(w.scouting[pid] ?: 0, knowledge(w, w.players[pid])) + 15 + (q / 5).roundToInt())
            w.scouting[pid] = k
            if (k >= 90) {
                w.scoutQueue.remove(pid)
                val p = w.players[pid]
                w.inbox.add(Message(w.day, "Scouting", "Report: ${p.handle}", "${p.handle} (${Catalogue.get(p.titleId).short} ${Catalogue.get(p.titleId).role(p.mainRole).short}, age ${Dates.ageAt(p.birth, w.day)}): current ability %.0f, potential %.0f, value ${dev.uem.em.core.formatMoney(Economy.marketValue(w, p))}.".format(p.ca, p.pa)))
            }
        }
    }

    // ------------------------------------------------------------------ national teams

    /** National teams of a title for the Nations Cup: the strongest players of each country. */
    fun nationalTeams(w: World, titleId: String, count: Int): List<Int> {
        val t = Catalogue.get(titleId)
        val byNat = w.players.filter { !it.retired && it.titleId == titleId && it.injuryUntil <= w.day }.groupBy { it.nat }
        val squads = byNat.mapNotNull { (nat, ps) ->
            val picked = ArrayList<Player>()
            for (role in t.lineup) ps.filter { it.mainRole == role && it !in picked }.maxByOrNull { it.ca }?.let { picked.add(it) }
            if (picked.size < t.teamSize) return@mapNotNull null
            ps.filter { it !in picked }.maxByOrNull { it.ca }?.let { picked.add(it) }
            Triple(nat, picked, picked.take(t.teamSize).sumOf { it.ca })
        }.sortedByDescending { it.third }.take(count)
        return squads.map { (nat, picked, _) ->
            val existing = w.teams.firstOrNull { it.nationalOf == nat && it.titleId == titleId }
            val team = existing ?: run {
                val country = GameData.countries[nat]
                val org = Org(w.orgs.size, "${country?.name ?: nat} national team", nat, nat, country?.region ?: "NA", rep = 60.0)
                w.orgs.add(org)
                Team(w.teams.size, org.id, titleId, country?.name ?: nat, nat, country?.region ?: "NA", nationalOf = nat).also { w.teams.add(it); org.teams.add(it.id) }
            }
            team.roster.clear()
            team.roster.addAll(picked.map { it.id })
            team.active = true
            team.manualLineup = false
            team.lineup = Lineups.auto(w, team)
            team.elo = 1500 + (picked.take(t.teamSize).map { it.ca }.average() - 65) * 18
            team.id
        }
    }

    fun isNational(t: Team) = t.nationalOf.isNotEmpty()

    /** National teams step down when their cup ends. */
    fun onCompetitionFinished(w: World, c: dev.uem.em.model.Competition) {
        if (c.entrants.any { it.startsWith("nations:") }) for (tid in c.teamIds) w.teams[tid].active = false
    }

    // ------------------------------------------------------------------ difficulty

    fun incomeFactor(w: World) = when (w.difficulty) { "easy" -> 1.25; "hard" -> 0.85; else -> 1.0 }
    fun boardPenaltyFactor(w: World) = when (w.difficulty) { "easy" -> 0.7; "hard" -> 1.3; else -> 1.0 }

    // ------------------------------------------------------------------ preview and review

    fun seasonPreview(w: World) {
        for (id in w.titles) {
            val t = Catalogue.get(id)
            val favs = w.leagues.filter { it.titleId == id && it.tier == 1 }.mapNotNull { l -> l.teamIds.maxByOrNull { w.teams[it].elo }?.let { "${w.teams[it].name} (${l.short})" } }
            if (favs.isNotEmpty()) w.news.add(News(w.day, "${t.short} ${w.season} preview", "Favourites: ${favs.joinToString(", ")}."))
        }
    }

    fun seasonReview(w: World) {
        val org = w.orgs.getOrNull(w.userOrg) ?: return
        val lines = ArrayList<String>()
        for (tid in org.teams) {
            val team = w.teams[tid]
            if (!team.active || isNational(team)) continue
            val t = Catalogue.get(team.titleId)
            val titles = team.trophies.filter { it.season == w.season }.map { it.name }
            val comps = w.comps.filter { it.season == w.season && tid in it.placements }.sortedBy { it.startDay }.joinToString { "${it.short} ${dev.uem.em.core.ordinal(it.placements.indexOf(tid) + 1)}" }
            val best = team.roster.map { w.players[it] }.maxByOrNull { p -> p.stats.filter { it.season == w.season }.let { s -> if (s.sumOf { it.gp } == 0) 0.0 else s.sumOf { it.ratingSum } / s.sumOf { it.gp } } }
            lines.add("${t.short} ${team.name}: $comps${if (titles.isNotEmpty()) " · trophies: ${titles.joinToString()}" else ""}${best?.let { " · best player ${it.handle}" } ?: ""}")
        }
        if (lines.isNotEmpty()) w.inbox.add(Message(w.day, "Board", "Season review ${w.season}", lines.joinToString("\n") + "\nBoard confidence: ${w.board?.confidence?.roundToInt() ?: "—"}/100."))
    }
}

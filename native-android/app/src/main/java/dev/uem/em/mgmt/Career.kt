package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.core.formatMoney
import dev.uem.em.core.ordinal
import dev.uem.em.data.GameData
import dev.uem.em.gen.GenCtx
import dev.uem.em.gen.PlayerGen
import dev.uem.em.model.Award
import dev.uem.em.model.Board
import dev.uem.em.model.BoardObjective
import dev.uem.em.model.CompTier
import dev.uem.em.model.Competition
import dev.uem.em.model.Message
import dev.uem.em.model.News
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import kotlin.math.roundToInt

/** The user's career (board, sacking, job offers), season awards and the youth intake. */
object Career {
    fun setupBoard(w: World) {
        if (w.userOrg < 0) return
        val org = w.orgs[w.userOrg]
        val objectives = ArrayList<BoardObjective>()
        for (tid in org.teams) {
            val team = w.teams[tid]
            if (!team.active || team.leagueId < 0) continue
            val league = w.leagues[team.leagueId]
            val rank = league.teamIds.sortedByDescending { w.teams[it].elo }.indexOf(team.id) + 1
            val target = (rank + 1).coerceAtMost(league.teamIds.size)
            val t = Catalogue.get(team.titleId)
            objectives.add(BoardObjective("${t.short}: finish top $target in ${league.short} (final split)", "league", tid, target))
            if (league.tier == 1 && rank <= 2) objectives.add(BoardObjective("${t.short}: qualify for the world championship", "worlds", tid, 1))
        }
        val floor = (org.balance * 0.5 / 10000).roundToInt() * 10000
        objectives.add(BoardObjective("Keep the balance above ${formatMoney(floor.toDouble())}", "balance", -1, floor))
        val prev = w.board
        w.board = Board(w.season, prev?.confidence?.let { clamp(it * 0.5 + 30, 30.0, 80.0) } ?: 60.0, objectives, contractEnd = prev?.contractEnd?.takeIf { it >= w.season } ?: (w.season + 1))
        w.inbox.add(Message(w.day, "Board", "Objectives for ${w.season}", objectives.joinToString("\n") { "• ${it.text}" }))
    }

    private fun conf(w: World, delta: Double, note: String) {
        val b = w.board ?: return
        b.confidence = clamp(b.confidence + (if (delta < 0) delta * Extras.boardPenaltyFactor(w) else delta), 0.0, 100.0)
        b.notes.add("${Dates.short(w.day)}: $note (${if (delta >= 0) "+" else ""}${delta.roundToInt()})")
        if (b.notes.size > 30) b.notes.removeAt(0)
    }

    fun onCompetitionFinished(w: World, c: Competition) {
        if (w.userOrg < 0) return
        for (tid in c.placements) {
            if (!w.isUserTeam(tid)) continue
            val place = c.placements.indexOf(tid) + 1
            val n = c.placements.size
            val delta = when (c.tier) {
                CompTier.WORLDS -> if (place == 1) 20.0 else if (place <= 4) 8.0 else 2.0
                CompTier.INTERNATIONAL -> if (place == 1) 10.0 else if (place <= 3) 4.0 else 0.0
                else -> {
                    val team = w.teams[tid]
                    val league = w.leagues.getOrNull(team.leagueId)
                    val expected = league?.teamIds?.sortedByDescending { w.teams[it].elo }?.indexOf(tid)?.plus(1) ?: (n / 2)
                    ((expected - place) * 2.5).coerceIn(-10.0, 10.0) + (if (place == 1) 5 else 0)
                }
            }
            conf(w, delta, "${w.teams[tid].short}: ${ordinal(place)} in ${c.short}")
            if (place == 1) w.inbox.add(Message(w.day, "Board", "Congratulations: ${c.name}", "The board congratulates you on winning ${c.name}."))
        }
    }

    /** Season end: objectives reviewed; a manager below 20 confidence is dismissed. */
    fun seasonEnd(w: World) {
        val b = w.board ?: return
        if (w.userOrg < 0) return
        val org = w.orgs[w.userOrg]
        for (o in b.objectives) {
            val met = when (o.kind) {
                "league" -> {
                    val team = w.teams[o.teamId]
                    val last = w.comps.lastOrNull { it.season == w.season && it.leagueId == team.leagueId && it.done }
                    val pos = last?.placements?.indexOf(o.teamId)?.plus(1) ?: 99
                    pos in 1..o.target
                }
                "worlds" -> w.comps.any { it.season == w.season && it.tier == CompTier.WORLDS && o.teamId in it.teamIds }
                else -> org.balance >= o.target
            }
            o.status = if (met) "met" else "missed"
            conf(w, if (met) 8.0 else -12.0, "${o.text}: ${o.status}")
        }
        if (b.confidence < 20) {
            w.sacked = true
            w.inbox.add(Message(w.day, "Board", "You have been dismissed", "The board has lost confidence (${b.confidence.roundToInt()}/100). Other organisations have approached you: see the job offers."))
        } else if (b.contractEnd <= w.season) {
            b.contractEnd = w.season + 2
            w.inbox.add(Message(w.day, "Board", "Contract renewed", "The board renews your contract until the end of ${b.contractEnd}."))
        }
    }

    fun jobOffers(w: World): List<Int> {
        val rep = w.orgs.getOrNull(w.userOrg)?.rep ?: 50.0
        return w.orgs.filter { o -> o.id != w.userOrg && o.teams.any { w.teams[it].active && w.teams[it].leagueId >= 0 } && o.rep <= rep + 8 }
            .sortedByDescending { it.rep }.take(6).map { it.id }
    }

    fun takeJob(w: World, orgId: Int) {
        w.userOrg = orgId
        w.sacked = false
        w.board = null
        setupBoard(w)
        w.news.add(News(w.day, "${w.managerName} takes over at ${w.orgs[orgId].name}", "A new manager arrives at ${w.orgs[orgId].name}."))
    }

    /** Season awards per title: MVP, Rookie of the Year and the All-Pro team (by position). */
    fun awards(w: World) {
        for (id in w.titles) {
            val t = Catalogue.get(id)
            val lines = w.players.mapNotNull { p -> p.stats.filter { it.season == w.season && it.titleId == id }.takeIf { it.isNotEmpty() }?.let { s -> Triple(p, s.sumOf { it.gp }, s.sumOf { it.ratingSum }) } }
            if (lines.isEmpty()) continue
            val maxGp = lines.maxOf { it.second }
            val eligible = lines.filter { it.second >= maxGp * 0.6 }
            fun avg(x: Triple<dev.uem.em.model.Player, Int, Double>) = x.third / x.second
            eligible.maxByOrNull { avg(it) }?.let { (p, _, _) -> give(w, id, "${t.short} Season MVP", p.id) }
            eligible.filter { Dates.ageAt(it.first.birth, w.day) <= 20 }.maxByOrNull { avg(it) }?.let { (p, _, _) -> give(w, id, "${t.short} Rookie of the Year", p.id) }
            for (role in t.lineup) eligible.filter { it.first.mainRole == role }.maxByOrNull { avg(it) }?.let { (p, _, _) -> give(w, id, "${t.short} All-Pro ${t.role(role).short}", p.id) }
            w.comps.firstOrNull { it.season == w.season && it.titleId == id && it.tier == CompTier.WORLDS && it.mvp >= 0 }?.let { give(w, id, "${it.short} MVP", it.mvp, it.id) }
        }
    }

    private fun give(w: World, titleId: String, name: String, playerId: Int, compId: Int = -1) {
        val p = w.players[playerId]
        w.awards.add(Award(w.season, titleId, name, playerId, p.teamId, compId))
        p.awards.add("${w.season} $name")
        p.rep = clamp(p.rep + 3, 1.0, 99.0)
        if (name.contains("MVP") || w.isUserTeam(p.teamId)) w.news.add(News(w.day, "$name: ${p.handle}", "${p.handle} (${w.teams.getOrNull(p.teamId)?.name ?: "free agent"}) wins $name ${w.season}."))
    }

    /** Youth intake (end of season): prospects in every title; the user's academy reports the best. */
    fun youthIntake(w: World) {
        val rng = Rng.derive(w.seed, "youth", w.season)
        val ctx = GenCtx(w, rng)
        for (p in w.players) ctx.handles.add(p.handle.lowercase())
        val userTitles = w.orgs.getOrNull(w.userOrg)?.teams?.map { w.teams[it].titleId }?.toSet() ?: emptySet()
        val academy = w.orgs.getOrNull(w.userOrg)?.facilities?.getOrElse(2) { 3 } ?: 3
        val found = ArrayList<String>()
        for (id in w.titles) {
            val t = Catalogue.get(id)
            val pop = Catalogue.POPULARITY[id]!!
            val regs = w.leagues.filter { it.titleId == id }.flatMap { GameData.expand(it.region) }.ifEmpty { listOf("NA") }
            val n = w.leagues.count { it.titleId == id } * 4
            val made = (0 until n).map { PlayerGen.generate(ctx, id, null, GameData.pickNationality(rng, regs, t.isMoba, pop), rng.int(15, 17), clamp(rng.normal(47.0, 6.0), 30.0, 66.0), rng.normal(4.0 + academy * 0.5, 3.0)) }
            if (id in userTitles) found.addAll(made.sortedByDescending { it.pa }.take(2).map { "${it.handle} (${t.short} ${t.role(it.mainRole).short}, ${it.nat}, age ${Dates.ageAt(it.birth, w.day)}, potential ${it.pa.roundToInt()})" })
        }
        if (found.isNotEmpty()) w.inbox.add(Message(w.day, "Academy", "Youth intake ${w.season}", "Your academy staff recommend:\n" + found.joinToString("\n") { "• $it" } + "\nThey are free agents: sign them from the Market."))
    }
}

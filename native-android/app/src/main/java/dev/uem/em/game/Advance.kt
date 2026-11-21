package dev.uem.em.game

import dev.uem.em.comp.Progress
import dev.uem.em.comp.Season
import dev.uem.em.core.Dates
import dev.uem.em.core.clamp
import dev.uem.em.model.Attrs
import dev.uem.em.model.AttrGroup
import dev.uem.em.model.CareerEntry
import dev.uem.em.model.CompStats
import dev.uem.em.model.Fixture
import dev.uem.em.model.News
import dev.uem.em.model.SeasonLine
import dev.uem.em.model.TeamSeason
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.sim.Ratings
import dev.uem.em.sim.SeriesOutcome
import dev.uem.em.sim.SeriesSim
import dev.uem.em.titles.Catalogue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Time advance. Each day: competitions start when their entrants are known, then all of the day's
 * series are simulated in parallel on every CPU core and their results applied in fixture order;
 * daily recovery, monthly development, weekly box-score paging and the season rollover follow.
 */
object Advance {
    private val cores = maxOf(1, Runtime.getRuntime().availableProcessors())
    private val pool: ExecutorService = Executors.newFixedThreadPool(cores) { r -> Thread(r, "sim").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }

    class Result(val days: Int, val stoppedForUser: Boolean)

    /** Advance up to `days` days (or until the user's team plays, when `stopForUser`). */
    fun run(w: World, days: Int, stopForUser: Boolean = false, onDay: (Int) -> Unit = {}, cancelled: () -> Boolean = { false }): Result {
        var done = 0
        while (done < days && !cancelled()) {
            if (stopForUser && done > 0 && userPlaysOn(w, w.day + 1)) return Result(done, true)
            day(w)
            done++
            onDay(done)
        }
        return Result(done, false)
    }

    /** The user's next unplayed series due today or tomorrow. */
    fun userFixtureDue(w: World) = w.fixtures.asReversed().firstOrNull { !it.done && it.day <= w.day + 1 && (w.isUserTeam(it.a) || w.isUserTeam(it.b)) }

    fun userPlaysOn(w: World, day: Int): Boolean {
        val ids = w.schedule[day] ?: return false
        if (w.userOrg >= 0) return ids.any { fid -> w.fixtures[fid].let { w.isUserTeam(it.a) || w.isUserTeam(it.b) } }
        // Spectator career: stop before a followed team plays.
        return w.stopForFollowed && w.followed.isNotEmpty() && ids.any { fid -> w.fixtures[fid].let { !it.done && (it.a in w.followed || it.b in w.followed) } }
    }

    /** Whether a background series keeps its 2D replay (Settings → Replays). */
    fun shouldRecord(w: World, f: Fixture): Boolean {
        val mine = w.isUserTeam(f.a) || w.isUserTeam(f.b) || f.a in w.followed || f.b in w.followed
        return when (w.replays) {
            "off" -> false
            "all" -> true
            "mine" -> mine
            else -> {
                val c = w.comps[f.compId]
                val final = f.nodeId.isNotEmpty() && c.stages.getOrNull(f.stageIdx)?.bracket?.firstOrNull { it.id == f.nodeId }?.isFinal == true
                mine || c.tier == dev.uem.em.model.CompTier.WORLDS || c.tier == dev.uem.em.model.CompTier.INTERNATIONAL || (final && c.tier == dev.uem.em.model.CompTier.LEAGUE)
            }
        }
    }

    /** Time spent per part of the day (nanoseconds), for profiling. */
    val timing = LongArray(6)

    private inline fun timed(i: Int, f: () -> Unit) { val t = System.nanoTime(); f(); timing[i] += System.nanoTime() - t }

    fun day(w: World) {
        w.day++
        val d = w.day
        timed(0) { Progress.startDue(w, d); dev.uem.em.mgmt.Patches.daily(w) }
        timed(1) { playFixtures(w, d) }
        timed(2) { daily(w) }
        timed(3) {
            if (Dates.dom(d) == 1) {
                dev.uem.em.mgmt.Development.monthly(w)
                dev.uem.em.mgmt.Economy.monthTick(w)
                if (Dates.month(d) == 9) dev.uem.em.mgmt.Market.contractReminders(w)
            dev.uem.em.mgmt.Deals.monthly(w)
            dev.uem.em.mgmt.Supporters.monthly(w)
            }
            if (Dates.dow(d) == 1) {
                BoxStore.pageOut(w)
                dev.uem.em.mgmt.Development.weekly(w)
                dev.uem.em.mgmt.Extras.weeklyBreaks(w)
                dev.uem.em.mgmt.Extras.weeklyScouting(w)
                dev.uem.em.mgmt.Deals.weeklyScrims(w)
            }
        }
        timed(4) { dev.uem.em.mgmt.Market.daily(w); dev.uem.em.mgmt.Deals.delegated(w) }
        timed(5) { if (d == Season.endDay(w.season)) rollover(w) }
    }

    private fun playFixtures(w: World, d: Int) {
        // Brackets can schedule follow-up series for the same day; loop until none are left.
        var guard = 0
        while (guard++ < 8) {
            val ids = (w.schedule[d] ?: return).filter { !w.fixtures[it].done }
            if (ids.isEmpty()) return
            val fixtures = ids.map { w.fixtures[it] }
            // Lineups are settled first (this writes to teams), then series run in parallel read-only.
            val lineups = fixtures.associate { f ->
                val c = w.comps[f.compId]
                f.id to arrayOf(dev.uem.em.mgmt.Supporters.lineupFor(w, c, w.teams[f.a], Lineups.ensure(w, w.teams[f.a]).toList()), dev.uem.em.mgmt.Supporters.lineupFor(w, c, w.teams[f.b], Lineups.ensure(w, w.teams[f.b]).toList()))
            }
            // A series the user started live and left unfinished continues from its saved games.
            val resume = w.live?.takeIf { lv -> fixtures.any { it.id == lv.fixtureId } }
            fun sim(f: Fixture) = SeriesSim.run(w, f, lineups[f.id]!!, record = shouldRecord(w, f), resume = resume?.takeIf { it.fixtureId == f.id })
            val outcomes: List<SeriesOutcome> = if (fixtures.size == 1 || cores == 1) fixtures.map { sim(it) }
            else pool.invokeAll(fixtures.map { f -> Callable { sim(f) } }).map { it.get() }
            for (o in outcomes) apply(w, o)
            if (resume != null) w.live = null
        }
    }

    /** Apply one series: fixture result, player and competition stats, learning, Elo, competition progress. */
    /** Apply a series result (also used for a series you played live). */
    fun apply(w: World, o: SeriesOutcome) {
        val f = o.fixture
        // A result is applied once: stats, prize money, Elo and progress are never counted twice.
        if (f.done) return
        val t = Catalogue.get(f.titleId)
        val c = w.comps[f.compId]
        val teamIds = intArrayOf(f.a, f.b)
        f.done = true
        f.winner = teamIds[o.winner]
        f.scoreA = o.wins[0]
        f.scoreB = o.wins[1]
        f.mvp = o.mvp
        val nStats = t.stats.size
        for (g in o.record.games) for (l in g.lines) {
            val p = w.players[l.playerId]
            val tid = teamIds[l.team]
            val won = l.team == g.winner
            val sl = p.stats.lastOrNull { it.season == w.season && it.titleId == t.id && it.teamId == tid } ?: SeasonLine(w.season, t.id, tid, s = DoubleArray(nStats)).also { p.stats.add(it) }
            sl.gp++
            if (won) sl.w++ else sl.l++
            sl.ratingSum += l.rating
            for (i in 0 until nStats) sl.s[i] += l.s[i]
            val cs = c.pstats.getOrPut(p.id) { CompStats(tid, v = DoubleArray(nStats)) }
            cs.gp++
            if (won) cs.w++
            cs.r += l.rating
            cs.teamId = tid
            for (i in 0 until nStats) cs.v[i] += l.s[i]
            // Learning by playing.
            val learn = 0.5 + p.attrs[Attrs.LEARNING] / 100
            p.profTitle[t.id] = clamp((p.profTitle[t.id] ?: 0.0) + 0.12 * learn * (1 - (p.profTitle[t.id] ?: 0.0) / 105), 0.0, 100.0)
            val rk = "${t.id}:${l.roleId}"
            p.profRole[rk] = clamp((p.profRole[rk] ?: 0.0) + 0.15 * learn * (1 - (p.profRole[rk] ?: 0.0) / 105), 0.0, 100.0)
            if (l.charId.isNotEmpty()) p.profChar[l.charId] = clamp((p.profChar[l.charId] ?: 10.0) + 0.6 * learn * (1 - (p.profChar[l.charId] ?: 10.0) / 105), 0.0, 100.0)
            if (t.maps.size > 1) p.profMap[g.mapId] = clamp((p.profMap[g.mapId] ?: 20.0) + 0.4 * learn * (1 - (p.profMap[g.mapId] ?: 20.0) / 105), 0.0, 100.0)
            p.form = clamp(p.form * 0.85 + (l.rating - 1) * 6, -10.0, 10.0)
            p.fatigue = clamp(p.fatigue + (4 - p.attrs[Attrs.STAMINA] / 50) * (if (g.duration > 2400) 1.4 else 1.0), 0.0, 100.0)
        }
        if (o.mvp >= 0) {
            w.players[o.mvp].stats.lastOrNull { it.season == w.season && it.titleId == t.id }?.let { it.mvps++ }
            c.pstats[o.mvp]?.let { it.m++ }
        }
        // Elo.
        val ta = w.teams[f.a]
        val tb = w.teams[f.b]
        val ea = 1 / (1 + 10.0.pow((tb.elo - ta.elo) / 400))
        val margin = kotlin.math.abs(o.wins[0] - o.wins[1]).toDouble() / maxOf(1, o.wins[0] + o.wins[1])
        val dlt = 24 * (1 + margin * 0.3) * ((if (o.winner == 0) 1.0 else 0.0) - ea)
        ta.elo = (ta.elo + dlt).roundToInt().toDouble()
        tb.elo = (tb.elo - dlt).roundToInt().toDouble()
        BoxStore.put(w, o.record)
        dev.uem.em.mgmt.Extras.afterSeries(w, f.id, o.record.games.flatMap { g -> g.lines.map { it.playerId } }.toSet())
        dev.uem.em.mgmt.Deals.afterSeries(w, f)
        Progress.onFixtureDone(w, f)
    }

    private fun daily(w: World) {
        for (p in w.players) {
            if (p.retired) continue
            if (p.fatigue > 0) p.fatigue = maxOf(0.0, p.fatigue - (2.5 + p.attrs[Attrs.idxRecovery] / 40))
            p.form *= 0.98
        }
    }

    /** End of season: history, contracts, roster upkeep, next season's calendar. */
    private fun rollover(w: World) {
        for (team in w.teams) {
            if (!team.active || team.leagueId < 0) continue
            val split3 = w.comps.lastOrNull { it.season == w.season && it.leagueId == team.leagueId }
            val pos = split3?.placements?.indexOf(team.id)?.plus(1) ?: 0
            val lines = w.comps.filter { it.season == w.season && it.leagueId == team.leagueId }.flatMap { it.stages.flatMap { s -> s.standings.filter { r -> r.teamId == team.id } } }
            team.history.add(TeamSeason(w.season, team.leagueId, pos, lines.sumOf { it.w }, lines.sumOf { it.l }, team.elo))
        }
        val rng = dev.uem.em.core.Rng.derive(w.seed, "rollover", w.season)
        dev.uem.em.mgmt.Career.awards(w)
        dev.uem.em.mgmt.Extras.seasonReview(w)
        dev.uem.em.mgmt.Career.seasonEnd(w)
        for (p in w.players) if (!p.retired) p.caHistory.add(p.ca.toFloat())
        BoxStore.pruneReplays(w, w.day - 700)
        w.season++
        dev.uem.em.mgmt.Deals.returnLoans(w)
        dev.uem.em.mgmt.Deals.executePreContracts(w)
        dev.uem.em.mgmt.Market.seasonEnd(w, rng)
        for (p in w.players) if (!p.retired && p.teamId < 0 && Dates.ageAt(p.birth, w.day) >= 33 && rng.chance(0.4)) p.retiredSeason = w.season
        dev.uem.em.mgmt.Career.youthIntake(w)
        fillRosters(w)
        Season.plan(w, w.season)
        if (!w.sacked) dev.uem.em.mgmt.Career.setupBoard(w)
        dev.uem.em.mgmt.Extras.seasonPreview(w)
        w.news.add(News(w.day, "Season ${w.season} begins", "The ${w.season} calendar is out: three splits, First Stand, MSI and Worlds."))
        w.rebuildSchedule()
    }

    /** Teams short of players sign the best available free agents of their title (stage-1 market). */
    fun fillRosters(w: World) {
        val free = w.players.filter { !it.retired && it.teamId < 0 }.groupBy { it.titleId }.mapValues { (_, v) -> v.sortedByDescending { it.ca }.toMutableList() }
        for (team in w.teams) {
            if (!team.active || w.isUserTeam(team.id) || team.nationalOf.isNotEmpty()) continue
            val t = Catalogue.get(team.titleId)
            val pool = free[team.titleId] ?: continue
            while (team.roster.size < t.teamSize + 1 && pool.isNotEmpty()) {
                val have = team.roster.map { w.players[it].roles[0] }.toSet()
                val need = t.lineup.firstOrNull { it !in have }
                val pick = pool.firstOrNull { need == null || it.roles[0] == need } ?: pool[0]
                pool.remove(pick)
                pick.teamId = team.id
                pick.contractEnd = w.season + 1
                pick.salary = (t.baseSalary * Math.pow(maxOf(20.0, pick.ca) / 70, 4.0) / 100).roundToInt() * 100.0
                team.roster.add(pick.id)
                pick.career.add(CareerEntry(w.season, w.day, team.id, "signed"))
            }
        }
    }
}

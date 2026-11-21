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

    fun userPlaysOn(w: World, day: Int): Boolean = w.userOrg >= 0 && (w.schedule[day] ?: emptyList<Int>()).any { fid -> w.fixtures[fid].let { w.isUserTeam(it.a) || w.isUserTeam(it.b) } }

    fun day(w: World) {
        w.day++
        val d = w.day
        Progress.startDue(w, d)
        playFixtures(w, d)
        daily(w)
        if (Dates.dom(d) == 1) monthly(w)
        if (Dates.dow(d) == 1) BoxStore.pageOut(w)
        if (d == Season.endDay(w.season)) rollover(w)
    }

    private fun playFixtures(w: World, d: Int) {
        // Brackets can schedule follow-up series for the same day; loop until none are left.
        var guard = 0
        while (guard++ < 8) {
            val ids = (w.schedule[d] ?: return).filter { !w.fixtures[it].done }
            if (ids.isEmpty()) return
            val fixtures = ids.map { w.fixtures[it] }
            // Lineups are settled first (this writes to teams), then series run in parallel read-only.
            val lineups = fixtures.associate { f -> f.id to arrayOf(Lineups.ensure(w, w.teams[f.a]).toList(), Lineups.ensure(w, w.teams[f.b]).toList()) }
            val outcomes: List<SeriesOutcome> = if (fixtures.size == 1 || cores == 1) fixtures.map { SeriesSim.run(w, it, lineups[it.id]!!) }
            else pool.invokeAll(fixtures.map { f -> Callable { SeriesSim.run(w, f, lineups[f.id]!!) } }).map { it.get() }
            for (o in outcomes) apply(w, o)
        }
    }

    /** Apply one series: fixture result, player and competition stats, learning, Elo, competition progress. */
    private fun apply(w: World, o: SeriesOutcome) {
        val f = o.fixture
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
        Progress.onFixtureDone(w, f)
    }

    private fun daily(w: World) {
        for (p in w.players) {
            if (p.retired) continue
            if (p.fatigue > 0) p.fatigue = maxOf(0.0, p.fatigue - (2.5 + p.attrs[Attrs.idxRecovery] / 40))
            p.form *= 0.98
        }
    }

    /** Monthly development: growth toward potential before the peak age, decline after it. */
    private fun monthly(w: World) {
        val rng = dev.uem.em.core.Rng.derive(w.seed, "dev", w.day)
        for (p in w.players) {
            if (p.retired) continue
            val t = Catalogue.get(p.titleId)
            val age = Dates.ageFloat(p.birth, w.day)
            var delta = 0.0
            if (age < p.dev.peakAge) delta = maxOf(0.0, p.pa - p.ca) * 0.05 * p.dev.growthRate * (0.6 + p.attrs[Attrs.LEARNING] / 125) * (if (p.teamId >= 0) 1.0 else 0.6)
            else if (age > p.dev.declineAge) delta = -0.22 * p.dev.declineRate * (1 + (age - p.dev.declineAge) * 0.25)
            delta += rng.normal(0.0, 0.15)
            if (kotlin.math.abs(delta) < 0.05) continue
            val step = delta / 0.66
            for (i in 0 until Attrs.COUNT) if (Attrs.ALL[i].group != AttrGroup.Development) p.attrs[i] = clamp(p.attrs[i] + step, 1.0, 99.0)
            p.attrVersion++
            p.ca = Ratings.computeCA(p, t)
            if (p.ca > p.pa) p.pa = p.ca
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
        w.season++
        for (p in w.players) {
            if (p.retired || p.teamId < 0 || p.contractEnd >= w.season) continue
            val age = Dates.ageAt(p.birth, w.day)
            if (rng.chance(if (p.ca >= 60) 0.75 else 0.5) && age < 31) {
                p.contractEnd = w.season + rng.int(0, 2)
            } else {
                val team = w.teams[p.teamId]
                team.roster.remove(p.id)
                team.lineup.replaceAll { if (it == p.id) -1 else it }
                p.career.add(CareerEntry(w.season, w.day, p.teamId, "released"))
                p.teamId = -1
            }
        }
        for (p in w.players) if (!p.retired && p.teamId < 0 && Dates.ageAt(p.birth, w.day) >= 33 && rng.chance(0.4)) p.retiredSeason = w.season
        fillRosters(w)
        Season.plan(w, w.season)
        w.news.add(News(w.day, "Season ${w.season} begins", "The ${w.season} calendar is out: three splits, First Stand, MSI and Worlds."))
        w.rebuildSchedule()
    }

    /** Teams short of players sign the best available free agents of their title (stage-1 market). */
    fun fillRosters(w: World) {
        val free = w.players.filter { !it.retired && it.teamId < 0 }.groupBy { it.titleId }.mapValues { (_, v) -> v.sortedByDescending { it.ca }.toMutableList() }
        for (team in w.teams) {
            if (!team.active) continue
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

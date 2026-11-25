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
    private val allCores = maxOf(1, Runtime.getRuntime().availableProcessors())
    /** Simulation threads: "full" every core, "balanced" (default) half, "cool" two (cooler phone, longer battery). */
    @Volatile var speedMode: String = "balanced"
        set(v) { field = v; threadsFor(v).let { if (it != cores) { val old = pool; cores = it; pool = newPool(it); old.shutdown() } } }
    private fun threadsFor(mode: String) = when (mode) { "full" -> allCores; "cool" -> minOf(2, allCores); "single" -> 1; else -> maxOf(1, (allCores + 1) / 2) }
    @Volatile private var cores = threadsFor("balanced")
    private fun newPool(n: Int): ExecutorService = Executors.newFixedThreadPool(n) { r -> Thread(r, "sim").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    @Volatile private var pool: ExecutorService = newPool(cores)

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

    /** The games of your teams and of the teams you follow (empty: none, every game counts as yours). */
    fun myTitles(w: World): Set<String> {
        val out = HashSet<String>()
        if (w.userOrg >= 0) for (tid in w.orgs.getOrNull(w.userOrg)?.teams.orEmpty()) w.teams.getOrNull(tid)?.let { out.add(it.titleId) }
        for (tid in w.followed) w.teams.getOrNull(tid)?.let { out.add(it.titleId) }
        return out
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
                mine || c.tier == dev.uem.em.model.CompTier.WORLDS || (c.tier == dev.uem.em.model.CompTier.INTERNATIONAL && (final || !dev.uem.em.comp.Season.isEncQualifier(c))) || (final && c.tier == dev.uem.em.model.CompTier.LEAGUE)
            }
        }
    }

    /** Time spent per part of the day (nanoseconds), for profiling. */
    val timing = LongArray(6)

    private inline fun timed(i: Int, f: () -> Unit) { val t = System.nanoTime(); f(); timing[i] += System.nanoTime() - t }

    fun day(w: World) {
        w.day++
        val d = w.day
        timed(0) { dev.uem.em.mgmt.Circuits.ensure(w); dev.uem.em.mgmt.Circuits.daily(w); dev.uem.em.comp.Ecosystem.daily(w); dev.uem.em.mgmt.Nations.daily(w); Progress.startDue(w, d); dev.uem.em.mgmt.Patches.daily(w) }
        timed(1) { playFixtures(w, d) }
        timed(2) { daily(w) }
        timed(3) {
            if (Dates.dom(d) == 1) {
                dev.uem.em.mgmt.Development.monthly(w)
                dev.uem.em.mgmt.Economy.monthTick(w)
                dev.uem.em.mgmt.Staffing.monthly(w)
                if (Dates.month(d) == 9) { dev.uem.em.mgmt.Market.contractReminders(w); dev.uem.em.mgmt.Staffing.reminders(w) }
            dev.uem.em.mgmt.Deals.monthly(w)
            dev.uem.em.mgmt.Supporters.monthly(w)
            dev.uem.em.mgmt.SoloQueue.monthlyAmateurs(w)
            }
            if (Dates.dow(d) == 1) {
                BoxStore.pageOut(w)
                dev.uem.em.mgmt.Development.weekly(w)
                dev.uem.em.mgmt.Mentoring.weekly(w)
                dev.uem.em.mgmt.Ambitions.ensure(w)
                dev.uem.em.mgmt.Ambitions.weekly(w)
                dev.uem.em.mgmt.Extras.weeklyBreaks(w)
                dev.uem.em.mgmt.Extras.weeklyScouting(w)
                dev.uem.em.mgmt.Deals.weeklyScrims(w)
                dev.uem.em.mgmt.SoloQueue.weekly(w)
                dev.uem.em.mgmt.Commerce.weekly(w)
            }
        }
        timed(4) { dev.uem.em.mgmt.Market.daily(w); dev.uem.em.mgmt.Roles.daily(w); dev.uem.em.mgmt.Mentoring.trials(w) }
        timed(5) { if (d == Season.endDay(w.season)) rollover(w) }
    }

    private fun playFixtures(w: World, d: Int) {
        // Brackets can schedule follow-up series for the same day; loop until none are left.
        var guard = 0
        // A player plays one series a day: club fixtures first, then national teams, then development clubs
        // (a free agent on an amateur roster called up by his country plays for the country).
        val busy = HashSet<Int>()
        while (guard++ < 8) {
            val ids = (w.schedule[d] ?: return).filter { !w.fixtures[it].done }
            if (ids.isEmpty()) return
            val fixtures = ids.map { w.fixtures[it] }
            // Lineups are settled first (this writes to teams), then series run in parallel read-only.
            fun prio(f: Fixture): Int { val a = w.teams[f.a]; val b = w.teams[f.b]; return when { a.kind.isNotEmpty() || b.kind.isNotEmpty() || w.comps[f.compId].circuit.isNotBlank() -> 2; a.nationalOf.isNotEmpty() || b.nationalOf.isNotEmpty() -> 1; else -> 0 } }
            val lineups = HashMap<Int, Array<List<Int>>>()
            for (f in fixtures.sortedBy { prio(it) }) {
                val c = w.comps[f.compId]
                val la = withoutBusy(w, w.teams[f.a], dev.uem.em.mgmt.Supporters.lineupFor(w, c, w.teams[f.a], Lineups.ensure(w, w.teams[f.a]).toList()), busy)
                busy.addAll(la.filter { it >= 0 })
                val lb = withoutBusy(w, w.teams[f.b], dev.uem.em.mgmt.Supporters.lineupFor(w, c, w.teams[f.b], Lineups.ensure(w, w.teams[f.b]).toList()), busy)
                busy.addAll(lb.filter { it >= 0 })
                lineups[f.id] = arrayOf(la, lb)
                // Both sides' match preparation is locked now (AI coaches plan theirs); a resumed live series was locked when it began.
                dev.uem.em.mgmt.Prep.lock(w, f)
            }
            // A series the user started live and left unfinished continues from its saved games.
            val resume = w.live?.takeIf { lv -> fixtures.any { it.id == lv.fixtureId } }
            // Nations Cup qualifiers are always simulated in fast mode (hundreds of nations), unless you follow a side.
            // Balanced (the default): second-tier leagues (Challengers, academies, LJL / PCS / VCS...) and the regular
            // leagues of games you neither manage nor follow use the quick model; your games' top leagues, every
            // international event and every match of your or a followed team play on the full engines.
            val mine = myTitles(w)
            fun quick(f: Fixture) = (w.simDetail == "fast" || dev.uem.em.comp.Season.isEncQualifier(w.comps[f.compId]) ||
                (w.simDetail == "balanced" && w.comps[f.compId].tier.let { tier -> tier == dev.uem.em.model.CompTier.CHALLENGER ||
                    (tier == dev.uem.em.model.CompTier.LEAGUE && mine.isNotEmpty() && f.titleId !in mine) })) &&
                !w.isUserTeam(f.a) && !w.isUserTeam(f.b) && f.a !in w.followed && f.b !in w.followed
            fun sim(f: Fixture) = SeriesSim.run(w, f, lineups[f.id]!!, record = shouldRecord(w, f), resume = resume?.takeIf { it.fixtureId == f.id }, quick = quick(f))
            val outcomes: List<SeriesOutcome> = if (fixtures.size == 1 || cores == 1) fixtures.map { sim(it) }
            else pool.invokeAll(fixtures.map { f -> Callable { sim(f) } }).map { it.get() }
            for (o in outcomes) apply(w, o)
            if (resume != null) w.live = null
        }
    }

    /** A lineup without players already playing another series today: each is replaced by the best free roster player for the position. */
    private fun withoutBusy(w: World, team: dev.uem.em.model.Team, lineup: List<Int>, busy: Set<Int>): List<Int> {
        if (lineup.none { it in busy }) return lineup
        val t = Catalogue.get(team.titleId)
        val out = lineup.toMutableList()
        for (i in out.indices) {
            if (out[i] !in busy) continue
            val role = t.lineupRoles.getOrNull(i) ?: continue
            // Someone who plays the position (main, then second) first; only then anyone else on the roster.
            out[i] = team.roster.filter { it !in busy && it !in out && Lineups.available(w, w.players[it]) }
                .minWithOrNull(compareBy<Int>({ posTier(w.players[it], role.id) }, { -dev.uem.em.sim.Ratings.effectiveness(w.players[it], t, role) }))
                ?: standIn(w, team, role, busy, out) ?: -1
        }
        return out
    }

    private fun posTier(p: dev.uem.em.model.Player, role: String) = if (p.roles.firstOrNull() == role) 0 else if (p.roles.getOrNull(1) == role) 1 else 2

    /**
     * No free roster player: an available unsigned player stands in for the day, one who plays the position
     * first. A national team only ever takes a player of its nationality (first or second).
     */
    private fun standIn(w: World, team: dev.uem.em.model.Team, role: dev.uem.em.titles.RoleDef, busy: Set<Int>, taken: List<Int>): Int? {
        val t = Catalogue.get(team.titleId)
        val nat = team.nationalOf
        val pool = w.players.filter { it.titleId == team.titleId && it.teamId < 0 && !it.retired && it.id !in busy && it.id !in taken && Lineups.available(w, it) &&
            (nat.isEmpty() || it.nat == nat || it.nat2 == nat) }
        return pool.minWithOrNull(compareBy<dev.uem.em.model.Player>({ posTier(it, role.id) }, { -dev.uem.em.sim.Ratings.effectiveness(it, t, role) }))?.id
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
        // Character usage this season (tier lists): games, wins, bans; per map for shooters.
        for (g in o.record.games) {
            for (l in g.lines) if (l.charId.isNotEmpty()) {
                for (key in listOfNotNull("${w.season}|${l.charId}", if (!t.isMoba) "${w.season}|${g.mapId}|${l.charId}" else null)) {
                    val a = w.charStats.getOrPut(key) { IntArray(3) }
                    a[0]++
                    if (l.team == g.winner) a[1]++
                }
            }
            for (b in g.bans) w.charStats.getOrPut("${w.season}|$b") { IntArray(3) }[2]++
        }
        // Ratings: every position's average stays level through meta changes.
        dev.uem.em.sim.StatBook.calibrate(w, t, o.record.games)
        // What each team showed: the next opponents' drafts read it (MOBAs with a ban / pick draft).
        if (t.isMoba && t.draft.mode == "sequential") for (s in 0..1) if (teamIds[s] >= 0) dev.uem.em.sim.SeriesIntel.remember(w.teams[teamIds[s]].shown, o.record.games, s)
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
        // Performance coaches: faster recovery (up to about +0.8 a day).
        val extra = DoubleArray(w.teams.size)
        for (team in w.teams) if (team.active && team.staffIds.isNotEmpty()) extra[team.id] = 0.012 * (dev.uem.em.mgmt.Staffing.fitness(w, team) - 30).coerceAtLeast(0.0)
        for (p in w.players) {
            if (p.retired) continue
            if (p.fatigue > 0) p.fatigue = maxOf(0.0, p.fatigue - (2.5 + p.attrs[Attrs.idxRecovery] / 40 + (if (p.teamId >= 0) extra[p.teamId] else 0.0)))
            p.form *= 0.98
        }
    }

    /** End of season: history, contracts, roster upkeep, next season's calendar. */
    private fun rollover(w: World) {
        for (team in w.teams) {
            if (!team.active) continue
            // Every active team keeps its end-of-season roster (team page → Past rosters).
            if (team.leagueId < 0) { team.history.add(TeamSeason(w.season, -1, 0, 0, 0, team.elo, team.roster.toIntArray())); continue }
            val split3 = w.comps.lastOrNull { it.season == w.season && it.leagueId == team.leagueId }
            val pos = split3?.placements?.indexOf(team.id)?.plus(1) ?: 0
            val lines = w.comps.filter { it.season == w.season && it.leagueId == team.leagueId }.flatMap { it.stages.flatMap { s -> s.standings.filter { r -> r.teamId == team.id } } }
            team.history.add(TeamSeason(w.season, team.leagueId, pos, lines.sumOf { it.w }, lines.sumOf { it.l }, team.elo, team.roster.toIntArray()))
        }
        val rng = dev.uem.em.core.Rng.derive(w.seed, "rollover", w.season)
        Season.applyPromotions(w)
        dev.uem.em.mgmt.Circuits.seasonEnd(w)
        dev.uem.em.mgmt.Extras.residency(w)
        dev.uem.em.mgmt.Career.awards(w)
        dev.uem.em.mgmt.Extras.seasonReview(w)
        dev.uem.em.mgmt.Career.seasonEnd(w)
        // Expensive rosters that failed may be blown up (by each organisation's strategy).
        dev.uem.em.mgmt.Ambitions.ensure(w)
        dev.uem.em.mgmt.Ambitions.seasonReview(w, rng)
        for (p in w.players) if (!p.retired) p.caHistory.add(p.ca.toFloat())
        BoxStore.pruneReplays(w, w.day - 700)
        w.season++
        dev.uem.em.mgmt.SoloQueue.rollover(w)
        dev.uem.em.mgmt.Deals.returnLoans(w)
        dev.uem.em.mgmt.Deals.executePreContracts(w)
        dev.uem.em.mgmt.Market.seasonEnd(w, rng)
        for (p in w.players) if (!p.retired && p.teamId < 0 && Dates.ageAt(p.birth, w.day) >= 33 && rng.chance(0.4)) p.retiredSeason = w.season
        // Staff: growth, reputation, contracts, the coaching carousel; retired players may join the staff.
        dev.uem.em.mgmt.Staffing.seasonEnd(w, rng)
        dev.uem.em.mgmt.Career.youthIntake(w)
        dev.uem.em.mgmt.Academies.aiFound(w)
        fillRosters(w)
        dev.uem.em.mgmt.Mentoring.aiSuccession(w)
        Season.plan(w, w.season)
        dev.uem.em.mgmt.Circuits.plan(w, w.season)
        dev.uem.em.comp.CustomComps.rollover(w)
        if (!w.sacked) dev.uem.em.mgmt.Career.setupBoard(w)
        dev.uem.em.mgmt.Extras.seasonPreview(w)
        w.news.add(News(w.day, "Season ${w.season} begins", "The ${w.season} calendar is out: three splits, First Stand, MSI and Worlds."))
        w.rebuildSchedule()
    }

    /** Teams short of players sign the best available free agents of their title (stage-1 market). */
    fun fillRosters(w: World) {
        val free = w.players.filter { !it.retired && it.teamId < 0 }.groupBy { it.titleId }.mapValues { (_, v) -> v.sortedByDescending { it.ca }.toMutableList() }
        for (team in w.teams) {
            if (!team.active || w.isUserTeam(team.id) || team.nationalOf.isNotEmpty() || team.kind.isNotEmpty()) continue
            val t = Catalogue.get(team.titleId)
            val pool = free[team.titleId] ?: continue
            // Every position covered by a player whose main role it is first, then one substitute: a backup at the
            // thinnest position who is content to be one (never a star signed to sit behind a weaker starter).
            while (pool.isNotEmpty()) {
                val have = team.roster.map { w.players[it].roles[0] }.toSet()
                val need = t.lineup.firstOrNull { it !in have }
                if (need == null && team.roster.size >= t.teamSize + 1) break
                if (need != null && team.roster.size >= t.teamSize + 4) break
                if (need != null && dev.uem.em.mgmt.Academies.aiPromote(w, team)) continue
                val pick = if (need != null) pool.firstOrNull { it.roles[0] == need } ?: pool.firstOrNull { it.roles.getOrNull(1) == need } ?: pool[0]
                else {
                    val depth = t.lineup.minByOrNull { r -> team.roster.count { w.players[it].roles[0] == r } * 100 + (team.lineup.getOrNull(t.lineup.indexOf(r))?.let { w.players.getOrNull(it)?.ca } ?: 0.0) } ?: t.lineup[0]
                    val starter = team.lineup.getOrNull(t.lineup.indexOf(depth))?.let { w.players.getOrNull(it) }
                    pool.firstOrNull { it.roles[0] == depth && (starter == null || it.ca <= starter.ca + 1) && dev.uem.em.mgmt.Ambitions.benchRefusal(w, it, team) == null }
                        ?: pool.firstOrNull { (it.roles[0] == depth || it.roles.getOrNull(1) == depth) && dev.uem.em.mgmt.Ambitions.benchRefusal(w, it, team) == null } ?: break
                }
                pool.remove(pick)
                pick.teamId = team.id
                dev.uem.em.mgmt.Imports.onJoin(w, pick, team)
                pick.contractEnd = w.season + 1
                pick.salary = (t.baseSalary * Math.pow(maxOf(20.0, pick.ca) / 70, 4.0) / 100).roundToInt() * 100.0
                team.roster.add(pick.id)
                pick.career.add(CareerEntry(w.season, w.day, team.id, "signed"))
                w.transfers.add(dev.uem.em.model.Transfer(w.day, w.season, pick.id, -1, team.id, 0.0, "free agent"))
            }
        }
    }
}

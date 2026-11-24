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

    /** National teams of a title (old saves' Nations Cup rule): the strongest nations' squads. */
    fun nationalTeams(w: World, titleId: String, count: Int): List<Int> {
        val t = Catalogue.get(titleId)
        val byNat = w.players.filter { !it.retired && it.titleId == titleId && it.injuryUntil <= w.day }.groupBy { it.nat }
        val strong = byNat.filter { (_, ps) -> ps.size >= t.teamSize }.map { (nat, ps) -> nat to ps.sortedByDescending { it.ca }.take(t.teamSize).sumOf { it.ca } }
            .sortedByDescending { it.second }.take(count).map { it.first }
        return strong.map { nat -> callUp(w, nationalTeam(w, titleId, nat), byNat[nat].orEmpty()).id }
    }

    /** Places with no permanent population: no national team. */
    val UNINHABITED = setOf("AQ", "BV", "HM", "GS", "TF", "UM", "IO")

    /** Countries and territories of a continent that enter its Nations Cup qualifier. */
    fun nationsOf(continent: String): List<String> =
        GameData.countries.values.filter { it.continent == continent && it.code !in UNINHABITED }.map { it.code }.sorted()

    /** A nation's team in a game (created once; one organisation per nation for all games). */
    fun nationalTeam(w: World, titleId: String, nat: String): Team {
        w.teams.firstOrNull { it.nationalOf == nat && it.titleId == titleId }?.let { return it }
        val country = GameData.countries[nat]
        val org = w.orgs.firstOrNull { o -> o.teams.isNotEmpty() && o.teams.all { w.teams[it].nationalOf == nat } }
            ?: Org(w.orgs.size, "${country?.name ?: nat} national team", nat, nat, country?.region ?: "NA", rep = 40.0 + (country?.let { maxOf(it.moba, it.shooter) } ?: 20) * 0.3).also { w.orgs.add(it) }
        val team = Team(w.teams.size, org.id, titleId, country?.name ?: nat, nat, country?.region ?: "NA", nationalOf = nat)
        team.active = false
        team.dataNote = "National team: the country's best available players of the game, called up for each Nations Cup event; amateurs are called up when there are not enough professionals."
        w.teams.add(team)
        org.teams.add(team.id)
        team.elo = 1500.0
        return team
    }

    /**
     * How strongly selectors rate a player for his country: this season's form first (average match
     * rating over his games, more trusted the more he played, a little more for a strong club), then
     * his ability. A player with few games this season is judged on last season's form too.
     */
    fun selectionScore(w: World, p: Player): Double {
        var gp = 0.0; var perf = 0.0; var lvl = 0.0
        for (l in p.stats) {
            if (l.titleId != p.titleId || l.gp == 0) continue
            val wt = when (l.season) { w.season -> 1.0; w.season - 1 -> 0.4; else -> 0.0 }
            if (wt == 0.0) continue
            val team = w.teams.getOrNull(l.teamId)?.takeIf { it.nationalOf.isEmpty() }
            // Form in a second division, an academy league or a community circuit says less (weaker opposition).
            val (gap, trust) = levelOf(w, team)
            gp += l.gp * wt
            perf += (l.ratingSum / l.gp - 1.0) * 30.0 * trust * l.gp * wt
            lvl += gap * l.gp * wt
        }
        val club = levelOf(w, w.teams.getOrNull(p.teamId)?.takeIf { it.nationalOf.isEmpty() }).first
        if (gp <= 0.0) return p.ca - 3.0 + club
        val conf = gp / (gp + 8.0)
        return p.ca + conf * (perf / gp) + 0.5 * (lvl / gp) + 0.5 * club
    }

    /** Level gap (in ability points) and how much form counts, by the club's division. */
    private fun levelOf(w: World, team: dev.uem.em.model.Team?): Pair<Double, Double> {
        if (team == null) return -4.0 to 0.6
        val tier = w.leagues.getOrNull(team.leagueId)?.tier ?: 3
        val base = when { team.kind.isNotEmpty() -> -12.0 to 0.3; team.parentId >= 0 -> -7.0 to 0.5; tier == 1 -> 0.0 to 1.0; tier == 2 -> -6.0 to 0.5; else -> -9.0 to 0.4 }
        return (base.first + (team.elo - 1500.0) / 45.0 * 0.6) to base.second
    }

    /** Whether a player may be called up by a nation: his main nationality, or his second one when his first country would not pick him. */
    private fun eligible(w: World, p: Player, nat: String, pools: Map<String, List<Player>>?): Boolean {
        if (p.nat == nat) return true
        if (p.nat2 != nat) return false
        val home = pools?.get(p.nat) ?: w.players.filter { it.titleId == p.titleId && !it.retired && it.nat == p.nat }
        val sc = selectionScore(w, p)
        // A starter or the substitute at home stays with his first country.
        return home.count { it.id != p.id && it.mainRole == p.mainRole && !it.retired && selectionScore(w, it) > sc } >= 2
    }

    /**
     * Call up a nation's squad: for every position the player of the nationality in the best form
     * this season (any club, free agents too), then the best remaining players off-role, plus a
     * substitute. A nation without enough players calls up amateurs (created once and kept, so they
     * can be scouted and signed). Dual nationals play for their second country only when their first
     * country would not select them.
     */
    fun callUp(w: World, team: Team, pool0: List<Player>? = null, pools: Map<String, List<Player>>? = null): Team {
        val t = Catalogue.get(team.titleId)
        val nat = team.nationalOf
        val base = pool0 ?: w.players.filter { (it.nat == nat || it.nat2 == nat) && it.titleId == team.titleId && !it.retired }
        val pool = base.filter { !it.retired && it.injuryUntil <= w.day && eligible(w, it, nat, pools) }
        val score = HashMap<Int, Double>()
        fun sc(p: Player) = score.getOrPut(p.id) { selectionScore(w, p) }
        val picked = ArrayList<Player>()
        for (role in t.lineup) pool.filter { it.mainRole == role && it !in picked }.maxByOrNull { sc(it) }?.let { picked.add(it) }
        for (role in t.lineup) {
            if (picked.size >= t.teamSize) break
            if (picked.any { it.mainRole == role }) continue
            pool.filter { it !in picked }.maxByOrNull { sc(it) }?.let { picked.add(it) }
        }
        if (picked.size < t.teamSize) {
            val country = GameData.countries[nat]
            val scene = (if (t.isMoba) country?.moba else country?.shooter) ?: 15
            val missing = t.lineup.filter { r -> picked.none { it.mainRole == r } }.take(t.teamSize - picked.size)
            for ((k, role) in missing.withIndex()) {
                val rng = Rng.derive(w.seed, "national", team.titleId, nat, role, k, w.season)
                val ctx = dev.uem.em.gen.GenCtx(w, rng)
                val p = dev.uem.em.gen.PlayerGen.generate(ctx, team.titleId, role, nat, rng.int(17, 26), clamp(20.0 + scene * 0.36 + rng.normal(0.0, 3.5), 18.0, 58.0), rng.float(0.0, 8.0))
                // Not a ladder amateur (keeps the solo queue's weekly work and amateur scouting as they were).
                p.career.add(dev.uem.em.model.CareerEntry(w.season, w.day, -1, "amateur (national call-up)"))
                picked.add(p)
            }
        }
        val starters = picked.take(t.teamSize)
        pool.filter { it !in picked }.maxByOrNull { sc(it) }?.let { picked.add(it) }
        val before = team.roster.toSet()
        team.roster.clear()
        team.roster.addAll(picked.map { it.id })
        team.active = true
        team.manualLineup = false
        team.lineup = Lineups.auto(w, team)
        val squadElo = 1500 + (starters.map { it.ca }.average() - 65) * 18
        team.elo = if (team.elo == 1500.0 && team.trophies.isEmpty()) squadElo else team.elo * 0.5 + squadElo * 0.5
        lastChanges = (picked.map { it.id }.filter { it !in before }) to before.filter { it !in team.roster }
        return team
    }

    /** Who came in and who dropped out at the last call-up (for the squad news). */
    @Volatile var lastChanges: Pair<List<Int>, List<Int>> = emptyList<Int>() to emptyList()

    /** Every nation of a continent with its squad called up, strongest first (the qualifier's seeding). */
    fun continentTeams(w: World, titleId: String, continent: String): List<Int> {
        val all = w.players.filter { it.titleId == titleId && !it.retired }
        val byNat = all.groupBy { it.nat }
        val dual = all.filter { it.nat2.isNotEmpty() }.groupBy { it.nat2 }
        return nationsOf(continent).map { nat -> callUp(w, nationalTeam(w, titleId, nat), byNat[nat].orEmpty() + dual[nat].orEmpty(), byNat) }.sortedByDescending { it.elo }.map { it.id }
    }

    /**
     * Season end: a player who spent three straight seasons at clubs of one country (not his own)
     * takes its nationality as his second one: no longer an import there, and eligible for its
     * national team.
     */
    fun residency(w: World) {
        for (p in w.players) {
            if (p.retired || p.teamId < 0) { if (p.teamId < 0) { p.resCountry = ""; p.resYears = 0 }; continue }
            val team = w.teams[p.teamId]
            if (team.nationalOf.isNotEmpty()) continue
            val c = w.orgs[team.orgId].country
            if (c.isBlank() || c !in GameData.countries) continue
            if (c == p.resCountry) p.resYears++ else { p.resCountry = c; p.resYears = 1 }
            if (p.resYears >= 3 && c != p.nat && p.nat2.isEmpty()) {
                p.nat2 = c
                val country = GameData.countries[c]?.name ?: c
                if (w.isUserTeam(team.id) || team.id in w.followed) w.news.add(News(w.day, "${p.handle} gains ${country} nationality",
                    "After three straight seasons in ${country}, ${p.handle} (${GameData.countries[p.nat]?.name ?: p.nat}) also holds ${country} nationality: no longer an import there, and eligible for ${country}'s national team.", listOf(team.id)))
            }
        }
    }

    /**
     * After an international event: the standout players gain reputation and experience, and the
     * unsigned ones (amateurs called up by their country, free agents) get noticed by scouts.
     */
    fun showcase(w: World, c: dev.uem.em.model.Competition) {
        if (c.tier != CompTier.INTERNATIONAL && c.tier != CompTier.WORLDS) return
        val rows = c.pstats.entries.filter { it.value.gp >= 3 }.map { (pid, s) -> Triple(w.players[pid], s.r / s.gp, s.gp) }
        if (rows.isEmpty()) return
        val mean = rows.map { it.second }.average()
        val stand = rows.filter { it.second >= mean + 0.18 }.sortedByDescending { it.second }.take(12)
        val noticed = ArrayList<String>()
        for ((p, avg, gp) in stand) {
            p.rep = clamp(p.rep + (avg - mean) * 25 * (if (c.tier == CompTier.WORLDS) 1.4 else 1.0), 0.0, 100.0)
            p.morale = clamp(p.morale + 4, 0.0, 100.0)
            if (p.teamId < 0 || w.teams.getOrNull(p.teamId)?.let { it.leagueId < 0 || w.leagues.getOrNull(it.leagueId)?.tier != 1 } == true) {
                // Unsigned or outside a top league: scouts take note; a young talent shows he can handle the stage.
                if (p.soloMmr <= 0.0) { p.amateur = p.teamId < 0 && p.career.none { it.teamId >= 0 } }
                if (Dates.ageAt(p.birth, w.day) <= 22) p.pa = clamp(p.pa + 1.5, p.ca, 99.0)
                noticed.add("${p.handle} (${GameData.countries[p.nat]?.name ?: p.nat}, ${if (p.teamId < 0) "unsigned" else w.teams[p.teamId].name}, rating %.2f in $gp games)".format(avg))
            }
        }
        if (noticed.isEmpty()) return
        val mine = w.orgs.getOrNull(w.userOrg)?.teams?.map { w.teams[it].titleId }?.toSet().orEmpty()
        if (c.titleId in mine || w.spectator) w.inbox.add(Message(w.day, "Scouting", "Standouts at ${c.name}", "Players who caught the eye and could be signed: ${noticed.take(6).joinToString("; ")}."))
    }

    fun isNational(t: Team) = t.nationalOf.isNotEmpty()

    /** National teams step down when their event ends (they are called up again for the next one). */
    fun onCompetitionFinished(w: World, c: dev.uem.em.model.Competition) {
        showcase(w, c)
        for (tid in c.teamIds) if (w.teams[tid].nationalOf.isNotEmpty()) w.teams[tid].active = false
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

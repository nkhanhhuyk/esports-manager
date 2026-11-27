package dev.uem.em.mgmt

import dev.uem.em.comp.Season
import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.data.GameData
import dev.uem.em.gen.GenCtx
import dev.uem.em.gen.PlayerGen
import dev.uem.em.model.CareerEntry
import dev.uem.em.model.CompTier
import dev.uem.em.model.Competition
import dev.uem.em.model.News
import dev.uem.em.model.Org
import dev.uem.em.model.Player
import dev.uem.em.model.Stage
import dev.uem.em.model.StageType
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.TitleDef

/**
 * Amateur, collegiate and specialized circuits for the seven games.
 *
 * * **Amateur clubs** (team kind "amateur"): community teams in every top league's region, rostered by
 *   unsigned players (the solo-queue amateurs, free agents, generated newcomers). A roster place is a
 *   registration, not a contract: players stay free agents and any professional team can sign them.
 *   Community Cups each quarter, a regional Amateur Championship in November; its champion is promoted
 *   into the region's independent lower league (where one exists) and the bottom independent team drops
 *   to amateur status. Amateur clubs also enter open qualifiers (VALORANT 2027, CS2 Majors, Dota 2 TI).
 * * **University League** (fictional preset): enrolled students aged 25 or under without a professional
 *   contract (LoL, VALORANT, CS2 in the Americas and Europe).
 * * **VALORANT Game Changers**: Riot's circuit for women and marginalized genders. The game records
 *   eligibility only for its own fictional Game Changers players (never inferred for real people).
 *
 * Every event's eligibility is checked per player when the event registers its rosters and when a
 * team is fielded; players keep one identity (the same Player record) from the ladder to the pros.
 */
object Circuits {
    const val AMATEUR = "amateur"; const val COLLEGIATE = "collegiate"; const val GC = "gc"
    private val CLUB_A = listOf("Northside", "Riverside", "Harbor", "Summit", "Ironwood", "Old Town", "Lakeside", "Eastgate", "Westfield", "Southbank", "Highland", "Midtown", "Bayview", "Crossroads", "Redhill", "Greenway")
    private val CLUB_B = listOf("Esports", "Gaming", "Collective", "Squad", "Club", "Academy", "Crew", "Union", "Five", "Society")
    private val UNIS = listOf("Northbridge University", "Eastvale University", "Westmoor College", "Ashford Institute", "Lakehurst University", "Crestfield University", "Ravenport College", "Silverbrook University")

    /** Which development circuits a game runs in a top league's region. */
    fun collegiateRegion(titleId: String, league: dev.uem.em.model.League): Boolean =
        titleId in setOf("lol", "valorant", "cs2") && (league.region.startsWith("AMER") || league.region == "NA" || league.region.startsWith("EMEA"))
    fun gcRegion(titleId: String, league: dev.uem.em.model.League): Boolean = titleId == "valorant" && league.region != "CN"

    fun isDevelopment(team: Team) = team.kind.isNotEmpty()

    // ------------------------------------------------------------------ eligibility

    /** Whether a player may play an event with this eligibility rule, and why not. */
    fun eligible(w: World, c: Competition, p: Player): String? {
        if (c.elig.isBlank()) return null
        for (part in c.elig.split(';')) {
            val k = part.trim()
            when {
                k == "amateur" -> if (p.teamId >= 0 && !isDevelopment(w.teams[p.teamId])) return "under a professional contract"
                k == "enrolled" -> if (p.enrolled.isBlank()) return "not enrolled at a university"
                k.startsWith("age<=") -> if (Dates.ageAt(p.birth, w.day) > k.removePrefix("age<=").toInt()) return "older than ${k.removePrefix("age<=")}"
                k.startsWith("circuit:") -> if (k.removePrefix("circuit:") !in p.circuits.split(',')) return "not registered as eligible for this circuit"
            }
        }
        return null
    }

    fun eligibilityText(c: Competition): String = c.elig.split(';').filter { it.isNotBlank() }.joinToString(", ") {
        when {
            it == "amateur" -> "no professional contract"
            it == "enrolled" -> "enrolled at a university"
            it.startsWith("age<=") -> "aged ${it.removePrefix("age<=")} or under"
            it == "circuit:gc" -> "registered Game Changers player (women and marginalized genders; Riot policy)"
            else -> it
        }
    }.ifBlank { "open to every team" }

    // ------------------------------------------------------------------ setup

    /** Create the development teams of a world that has none (new worlds and older saves alike). */
    fun ensure(w: World) {
        if (w.teams.any { it.kind.isNotEmpty() }) return
        val rng = Rng.derive(w.seed, "circuits", w.season)
        val ctx = GenCtx(w, rng)
        for (p in w.players) ctx.handles.add(p.handle.lowercase())
        for (id in w.titles) {
            val t = Catalogue.get(id)
            for (l in w.leagues.filter { it.titleId == id && it.tier == 1 }) {
                repeat(if (dev.uem.em.comp.Season.leagueStrength(l) >= 60) 6 else 4) { newTeam(w, ctx, t, l, AMATEUR, rng) }
                if (collegiateRegion(id, l)) repeat(4) { newTeam(w, ctx, t, l, COLLEGIATE, rng) }
                if (gcRegion(id, l)) repeat(4) { newTeam(w, ctx, t, l, GC, rng) }
            }
        }
        // This season's remaining events (an older save gets the rest of the current calendar).
        plan(w, w.season, w.day + 7)
    }

    private fun newTeam(w: World, ctx: GenCtx, t: TitleDef, l: dev.uem.em.model.League, kind: String, rng: Rng): Team {
        val regs = GameData.expand(l.region)
        val country = GameData.countries.values.filter { it.region in regs }.let { if (it.isEmpty()) "US" else rng.pick(it).code }
        var name: String
        var guard = 0
        do {
            name = when (kind) {
                COLLEGIATE -> rng.pick(UNIS) + " " + t.short
                GC -> "${rng.pick(CLUB_A)} ${rng.pick(listOf("Valkyries", "Sirens", "Aurora", "Phoenix", "Nova", "Lumen"))}"
                else -> "${rng.pick(CLUB_A)} ${rng.pick(CLUB_B)}"
            }
        } while (w.teams.any { it.titleId == t.id && it.name == name } && guard++ < 30)
        val short = name.split(' ').filter { it.isNotBlank() }.joinToString("") { it.take(1) }.uppercase().take(4) + (if (kind == COLLEGIATE) "U" else "")
        val org = Org(w.orgs.size, name.removeSuffix(" ${t.short}"), short, country, regs.firstOrNull() ?: l.region, rep = 8.0, fans = rng.float(300.0, 6000.0), balance = rng.float(15_000.0, 60_000.0))
        w.orgs.add(org)
        val team = Team(w.teams.size, org.id, t.id, name, short, l.region, style = rng.pick(t.styles), aggression = rng.float(35.0, 70.0), chemistry = 45.0,
            elo = 1500.0 - rng.float(260.0, 420.0), metaAdapt = 50.0, coach = rng.float(25.0, 45.0), real = false,
            dataNote = when (kind) { COLLEGIATE -> "Fictional university team (generated students)"; GC -> "Fictional VALORANT Game Changers team (generated players)"; else -> "Fictional amateur community club (unsigned players)" }, kind = kind)
        w.teams.add(team); org.teams.add(team.id)
        fill(w, ctx, team, rng)
        return team
    }

    /** Fill a development roster: unsigned players of the region first, generated newcomers after. */
    private fun fill(w: World, ctx: GenCtx, team: Team, rng: Rng) {
        val t = Catalogue.get(team.titleId)
        val regs = GameData.expand(team.region).toSet()
        val taken = w.teams.filter { it.kind.isNotEmpty() && it.active }.flatMap { it.roster }.toHashSet()
        for (role in t.lineup) {
            if (team.roster.any { w.players[it].roles.firstOrNull() == role }) continue
            val pool = if (team.kind == AMATEUR) w.players.filter { it.titleId == team.titleId && it.teamId < 0 && !it.retired && it.id !in taken && it.roles.firstOrNull() == role && GameData.regionOf(it.nat) in regs && it.ca < 64 && it.enrolled.isBlank() && it.circuits.isBlank() }
                else emptyList()
            val p = pool.maxByOrNull { it.ca + (if (it.amateur) 4 else 0) + rng.next() * 6 } ?: newcomer(w, ctx, team, role, rng)
            team.roster.add(p.id); taken.add(p.id)
            p.career.add(CareerEntry(w.season, w.day, team.id, team.kind))
        }
        team.lineup = Lineups.auto(w, team)
    }

    private fun newcomer(w: World, ctx: GenCtx, team: Team, role: String, rng: Rng): Player {
        val t = Catalogue.get(team.titleId)
        val nat = GameData.pickNationality(rng, GameData.expand(team.region), t.isMoba, Catalogue.POPULARITY[t.id]!!)
        val age = when (team.kind) { COLLEGIATE -> rng.int(18, 22); else -> rng.int(16, 23) }
        val p = PlayerGen.generate(ctx, t.id, role, nat, age, clamp(rng.normal(if (team.kind == AMATEUR) 50.0 else 47.0, 5.0), 35.0, 62.0), rng.float(3.0, 10.0))
        p.amateur = true
        p.salary = 0.0; p.contractEnd = 0
        when (team.kind) {
            COLLEGIATE -> p.enrolled = team.name.removeSuffix(" ${t.short}")
            GC -> p.circuits = GC
        }
        p.realSrc = "generated ${team.kind} player"
        return p
    }

    // ------------------------------------------------------------------ calendar

    /** A season's development events from [from] on: Community Cups, Amateur Championship, University League, Game Changers. */
    fun plan(w: World, season: Int, from: Int = 0) {
        if (w.teams.none { it.kind.isNotEmpty() }) return
        for (id in w.titles) {
            val t = Catalogue.get(id)
            for ((li, l) in w.leagues.filter { it.titleId == id && it.tier == 1 }.withIndex()) {
                val off = (w.titles.indexOf(id) + li) % 3
                val base = l.short.removePrefix("VCT ").trim()
                fun day(m: Int, d: Int) = Dates.day(season, m, d) + off
                // Community Cups: midweek single elimination, best of three, the region's eight strongest clubs.
                listOf(Triple(2, 16, "Winter"), Triple(4, 27, "Spring"), Triple(8, 18, "Summer"), Triple(10, 19, "Autumn")).forEachIndexed { qi, (m, d, label) ->
                    if (day(m, d) < from) return@forEachIndexed
                    event(w, t, season, l, "$base $label Community Cup $season", "$base ${label.take(2)} Cup", AMATEUR, "amateur:${t.id}:L${l.id}:8", "amateur",
                        listOf(stage("Bracket", StageType.SINGLE_ELIM, day(m, d), 3, listOf(2, 3, 4), daysBetween = 1)), 6_000.0, 8, "${t.id}:L${l.id}:cup${qi + 1}")
                }
                // Regional Amateur Championship: the eight best clubs on Community Cup results; the champion can move up.
                if (day(11, 3) >= from) event(w, t, season, l, "$base Amateur Championship $season", "$base Am Champ", AMATEUR, "amateurpts:${t.id}:L${l.id}:8", "amateur",
                    listOf(stage("Groups", StageType.GROUPS, day(11, 3), 3, listOf(2, 3, 4), groups = 2, advance = 2, daysBetween = 1), stage("Final Four", StageType.SINGLE_ELIM, day(11, 3) + 7, 3, listOf(2, 3, 4), daysBetween = 1)),
                    15_000.0, 12, "${t.id}:L${l.id}:amchamp")
                if (collegiateRegion(id, l) && day(2, 4) >= from) {
                    val ul = event(w, t, season, l, "$base University League $season", "$base Uni", COLLEGIATE, "kind:${t.id}:L${l.id}:$COLLEGIATE", "enrolled;age<=25;amateur",
                        listOf(Stage("Season", StageType.RR, day(2, 4), 3, listOf(3), advance = 4, daysBetween = 7), stage("Finals", StageType.SINGLE_ELIM, day(5, 5), 3, listOf(3), daysBetween = 7)), 10_000.0, 10, "${t.id}:L${l.id}:uni")
                    ul.realName = false
                }
                if (gcRegion(id, l) && day(5, 12) >= from) event(w, t, season, l, "VCT Game Changers $base $season", "GC $base", GC, "kind:${t.id}:L${l.id}:$GC", "circuit:gc",
                    listOf(Stage("Group Stage", StageType.RR, day(5, 12), 3, listOf(2, 4), advance = 4, daysBetween = 2), stage("Playoffs", StageType.SINGLE_ELIM, day(6, 16), 3, listOf(2, 4), daysBetween = 2)), 20_000.0, 14, "${t.id}:L${l.id}:gc")
            }
            // Game Changers Championship: the two best of each region.
            if (id == "valorant") {
                val regional = w.comps.filter { it.season == season && it.titleId == id && it.circuit == GC && it.leagueId >= 0 }
                if (regional.isNotEmpty() && Dates.day(season, 11, 10) >= from) event(w, Catalogue.get(id), season, null, "VCT Game Changers Championship $season", "GC Champs", GC,
                    regional.map { "top:${it.id}:2" }, "circuit:gc", listOf(stage("Bracket", StageType.DOUBLE_ELIM, Dates.day(season, 11, 10), 3, listOf(1, 2, 3, 4, 5), daysBetween = 1)), 60_000.0, 30, "valorant:gc:champs")
            }
        }
    }

    private fun stage(name: String, type: StageType, start: Int, bestOf: Int, dows: List<Int>, groups: Int = 0, advance: Int = 0, daysBetween: Int = 2) = Stage(name, type, start, bestOf, dows, groups, advance, daysBetween)

    private fun event(w: World, t: TitleDef, season: Int, l: dev.uem.em.model.League?, name: String, short: String, circuit: String, rule: Any, elig: String,
                      stages: List<Stage>, prize: Double, prestige: Int, key: String): Competition {
        @Suppress("UNCHECKED_CAST") val rules = if (rule is String) listOf(rule) else rule as List<String>
        val c = Season.mk(w, t, season, name, short, CompTier.CHALLENGER, null, rules, stages, prize, prestige, emptyList(), key = key)
        c.circuit = circuit; c.elig = elig; c.realName = true
        c.rules = when (circuit) { COLLEGIATE -> "collegiate-preset"; GC -> "gc-preset"; else -> "amateur-preset" }
        if (l != null) c.entrants = rules // region kept in the rules; leagueId stays -1 (not a league split)
        return c
    }

    // ------------------------------------------------------------------ entrants, points, daily upkeep

    /** Amateur points: placings in this season's Community Cups of a region. */
    fun amateurPoints(w: World, season: Int, titleId: String): Map<Int, Double> {
        val pts = HashMap<Int, Double>()
        for (c in w.comps) {
            if (c.season != season || c.titleId != titleId || c.circuit != AMATEUR || !c.done || c.defKey.endsWith("amchamp")) continue
            c.placements.forEachIndexed { i, tid -> pts.merge(tid, listOf(100.0, 70.0, 45.0, 45.0, 20.0, 20.0, 20.0, 20.0).getOrElse(i) { 5.0 }, Double::plus) }
        }
        return pts
    }

    /** Teams of a development kind in a league's region (for the entrant rules). */
    fun teamsOf(w: World, titleId: String, league: dev.uem.em.model.League?, kind: String): List<Team> {
        // A development club belongs to one top league's circuit: the first live top league covering its region
        // (exactly, or as one of a merged / multi-region league's sub-regions). Never two at once.
        val owner = HashMap<String, Int>()
        fun ownerOf(region: String) = owner.getOrPut(region) {
            val live = w.leagues.filter { it.titleId == titleId && it.tier == 1 && it.until == 0 }
            (live.firstOrNull { it.region == region } ?: live.firstOrNull { region in dev.uem.em.data.GameData.expand(it.region) })?.id ?: -1
        }
        return w.teams.filter { it.titleId == titleId && it.active && it.kind == kind && (league == null || ownerOf(it.region) == league.id) }
    }

    /** Each day: signed or retired players leave development rosters, which refill from the region's unsigned players. */
    fun daily(w: World) {
        var dirty = false
        for (team in w.teams) {
            if (!team.active || team.kind.isEmpty()) continue
            val gone = team.roster.filter { pid -> val p = w.players[pid]; p.retired || (p.teamId >= 0 && p.teamId != team.id) || (team.kind == COLLEGIATE && (p.enrolled.isBlank() || Dates.ageAt(p.birth, w.day) > 25)) }
            if (gone.isNotEmpty()) {
                team.roster.removeAll(gone.toSet())
                for (pid in gone) dropRegistrations(w, team, pid)
                team.lineup = Lineups.auto(w, team); dirty = true
            }
        }
        // Clubs left short (a player signed by a professional team, graduated, too old) refill the next day.
        if (!dirty && Dates.dow(w.day) != 1 && w.teams.none { it.active && it.kind.isNotEmpty() && it.roster.size < Catalogue.get(it.titleId).teamSize }) return
        val rng = Rng.derive(w.seed, "circuit-fill", w.day)
        val ctx = GenCtx(w, rng)
        for (team in w.teams) if (team.active && team.kind.isNotEmpty() && team.roster.size < Catalogue.get(team.titleId).teamSize) {
            if (ctx.handles.isEmpty()) for (p in w.players) ctx.handles.add(p.handle.lowercase())
            fill(w, ctx, team, rng)
        }
    }

    /** A player leaving a development club (signed, graduated, too old) leaves its event registrations too. */
    private fun dropRegistrations(w: World, team: Team, pid: Int) {
        for (c in w.comps) if (c.status != 2 && c.circuit.isNotBlank()) c.registrations[team.id]?.players?.remove(pid)
    }

    /** A player joining any club leaves the development clubs of the old registration, that same day. */
    fun leaveClubs(w: World, p: Player, joining: Team) {
        for (team in w.teams) {
            if (team.kind.isEmpty() || team.id == joining.id || p.id !in team.roster) continue
            team.roster.remove(p.id)
            team.lineup.replaceAll { if (it == p.id) -1 else it }
            dropRegistrations(w, team, p.id)
        }
    }

    /**
     * Season end: each regional Amateur Championship winner joins the region's independent lower league
     * (the lowest-placed independent team of that league drops to amateur status); university graduates
     * leave college; struggling amateur clubs fold and new ones form.
     */
    fun seasonEnd(w: World) {
        val rng = Rng.derive(w.seed, "circuit-season", w.season)
        for (c in w.comps) {
            if (c.season != w.season || c.circuit != AMATEUR || !c.defKey.endsWith("amchamp") || !c.done) continue
            val champ = w.teams.getOrNull(c.placements.firstOrNull() ?: -1) ?: continue
            val upper = w.leagues.firstOrNull { it.id.toString() == c.defKey.split(':').getOrNull(1)?.removePrefix("L") } ?: continue
            // Several lower leagues under one top league (LCP: LJL, PCS, VCS): the champion goes up into its own region's.
            val lowers = w.leagues.filter { it.upperId == upper.id && it.tier == 2 && upper.lowerId != it.id && !it.academy && it.until == 0 }
            val lower = lowers.firstOrNull { inRegion(champ, it) } ?: lowers.firstOrNull() ?: continue
            val last = w.comps.lastOrNull { it.season == w.season && it.leagueId == lower.id && it.done }?.placements?.lastOrNull { w.teams[it].parentId < 0 && w.teams[it].kind.isEmpty() && !w.isUserTeam(it) && w.teams[it].leagueId == lower.id } ?: continue
            val down = w.teams[last]
            promote(w, champ, lower, "Fictional club promoted from the amateur circuit (${c.name})")
            relegate(w, down, lower)
            c.entries.add(dev.uem.em.model.QualEntry(champ.id, "promotion:${lower.id}", c.id, 0, "promoted to ${lower.short}", "${down.short} relegated to the amateur circuit", "promoted", w.day))
            w.news.add(News(w.day, "${champ.name} promoted to ${lower.short}", "${champ.name} won ${c.name} and join ${lower.name}; ${down.name} drop to the amateur circuit.", listOf(champ.id, down.id)))
        }
        for (p in w.players) if (p.enrolled.isNotBlank() && Dates.ageAt(p.birth, w.day) >= 24 && rng.chance(0.5)) p.enrolled = ""
        // Amateur clubs out of money fold; a new club forms in their region.
        val ctx = GenCtx(w, rng)
        for (team in w.teams.toList()) {
            if (!team.active || team.kind != AMATEUR) continue
            val org = w.orgs[team.orgId]
            if (org.balance >= 0 && !(team.elo < 1050 && rng.chance(0.15))) continue
            team.active = false
            team.roster.clear(); team.lineup = Lineups.auto(w, team)
            val league = w.leagues.firstOrNull { it.titleId == team.titleId && it.tier == 1 && it.region == team.region } ?: continue
            if (ctx.handles.isEmpty()) for (p in w.players) ctx.handles.add(p.handle.lowercase())
            val nt = newTeam(w, ctx, Catalogue.get(team.titleId), league, AMATEUR, rng)
            w.news.add(News(w.day, "${team.name} fold", "${team.name} leave the amateur circuit; ${nt.name} take their place.", listOf(team.id, nt.id)))
        }
    }

    /** A club of the league's region: the same region, one of its sub-regions, or a multi-region circuit that covers it (APN-PCS.JP.VN covers PCS). */
    private fun inRegion(t: Team, l: dev.uem.em.model.League) = t.region == l.region || t.region in dev.uem.em.data.GameData.expand(l.region) || l.region in dev.uem.em.data.GameData.expand(t.region)

    /** An amateur club goes up: it turns semi-professional (its players now sign contracts with it). */
    private fun promote(w: World, champ: Team, lower: dev.uem.em.model.League, note: String) {
        // Retired players (retirements are decided at the season's end, before seats are filled) are not signed.
        champ.roster.removeAll { w.players[it].retired }
        champ.lineup = Lineups.auto(w, champ)
        champ.kind = ""
        champ.leagueId = lower.id; lower.teamIds.add(champ.id)
        champ.dataNote = note
        for (pid in champ.roster) { val p = w.players[pid]; if (p.teamId < 0) { p.teamId = champ.id; p.contractEnd = w.season + 2; p.salary = Economy.expectedSalary(p, region = champ.region) * 0.4; p.joinedDay = w.day; p.career.add(CareerEntry(w.season + 1, w.day, champ.id, "signed (promoted club)")) } }
    }

    /** A lower-league club drops to the amateur circuit: its players' contracts end. */
    private fun relegate(w: World, down: Team, lower: dev.uem.em.model.League) {
        lower.teamIds.remove(down.id); down.leagueId = -1; down.kind = AMATEUR
        down.dataNote = "Relegated to the amateur circuit (${lower.short} ${w.season})"
        for (pid in down.roster.toList()) { val p = w.players[pid]; if (p.teamId == down.id) { p.teamId = -1; p.contractEnd = 0; p.career.add(CareerEntry(w.season + 1, w.day, -1, "released (club relegated)")) } }
    }

    /**
     * Seats: every independent lower league starts the new season with as many clubs as it had (unless a reform
     * changes that when the calendar is drawn). Promotion to a top league fed by several lower leagues sends the
     * relegated club home, and second teams follow their parents, so a lower league can come up short or over:
     * short, the best amateur clubs of its own region (amateur points, then Elo) go up; over, its last clubs drop
     * to the amateur circuit. [before]: league sizes when the season ended.
     */
    fun rebalance(w: World, before: Map<Int, Int>) {
        val pts = HashMap<String, Map<Int, Double>>()
        for (l in w.leagues) {
            if (l.tier < 2 || l.academy || l.until > 0) continue
            val want = before[l.id] ?: continue
            var guard = 0
            while (l.teamIds.size < want && guard++ < 6) {
                val p = pts.getOrPut(l.titleId) { amateurPoints(w, w.season, l.titleId) }
                val size = Catalogue.get(l.titleId).teamSize
                val pick = w.teams.filter { it.active && it.titleId == l.titleId && it.kind == AMATEUR && it.parentId < 0 && !w.isUserTeam(it.id) && inRegion(it, l) && it.roster.count { pid -> !w.players[pid].retired } >= size }
                    .maxWithOrNull(compareBy<Team>({ p[it.id] ?: 0.0 }, { it.elo })) ?: break
                promote(w, pick, l, "Fictional club promoted from the amateur circuit (a free place in ${l.short}, ${w.season})")
                w.news.add(News(w.day, "${pick.name} promoted to ${l.short}", "${pick.name} take a free place in ${l.name} (a club left the league at the end of the season).", listOf(pick.id)))
            }
            guard = 0
            while (l.teamIds.size > want && guard++ < 6) {
                val last = w.comps.lastOrNull { it.season == w.season && it.leagueId == l.id && it.done }?.placements?.lastOrNull { it in l.teamIds && w.teams[it].parentId < 0 && w.teams[it].kind.isEmpty() && !w.isUserTeam(it) }
                    ?: l.teamIds.filter { w.teams[it].parentId < 0 && !w.isUserTeam(it) }.minByOrNull { w.teams[it].elo } ?: break
                val down = w.teams[last]
                relegate(w, down, l)
                w.news.add(News(w.day, "${down.name} drop to the amateur circuit", "${l.name} keeps $want clubs: ${down.name} go down.", listOf(down.id)))
            }
        }
    }

    // ------------------------------------------------------------------ scouting evidence

    class Evidence(val games: Int, val rating: Double, val oppElo: Double, val events: Int, val confidence: String)

    /** What a player's development-circuit matches show (sample size and level of opposition, not hidden ratings). */
    fun evidence(w: World, p: Player): Evidence? {
        var games = 0; var r = 0.0; var opp = 0.0; val evs = HashSet<Int>()
        for (c in w.comps) {
            if (c.circuit.isBlank() || p.id !in c.pstats) continue
            val s = c.pstats[p.id]!!
            games += s.gp; r += s.r; evs.add(c.id)
            opp += c.teamIds.map { w.teams[it].elo }.average() * s.gp
        }
        if (games == 0) return null
        val conf = when { games >= 25 && evs.size >= 4 -> "fair"; games >= 10 -> "low"; else -> "very low" }
        return Evidence(games, r / games, opp / games, evs.size, conf)
    }
}

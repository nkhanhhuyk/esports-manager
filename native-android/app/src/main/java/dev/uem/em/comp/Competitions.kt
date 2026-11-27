package dev.uem.em.comp

import dev.uem.em.core.Dates
import dev.uem.em.data.GameData
import dev.uem.em.model.BracketNode
import dev.uem.em.model.CompTier
import dev.uem.em.model.Competition
import dev.uem.em.model.Fixture
import dev.uem.em.model.QualEntry
import dev.uem.em.model.League
import dev.uem.em.model.Stage
import dev.uem.em.model.StageType
import dev.uem.em.model.Standing
import dev.uem.em.model.Team
import dev.uem.em.model.Trophy
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.TitleDef
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

/**
 * The season calendar (ported from the web version): three league splits per tier-1 / tier-2 league,
 * then three internationals — First Stand after split 1 (hard Fearless in MOBAs), the Mid-Season
 * Invitational after split 2 (double-elimination bracket) and the World Championship after split 3
 * (circuit points decide the seeds).
 */
object Season {
    private class Split(val n: Int, val m: Int, val d: Int, val dows: List<Int>, val double: Boolean)

    private val SPLITS = listOf(Split(1, 1, 9, listOf(5, 6, 0), false), Split(2, 3, 27, listOf(5, 6), true), Split(3, 7, 24, listOf(4, 5, 6, 0), true))
    private val SPLIT_POINTS = listOf(90, 70, 50, 40, 30, 30, 10, 10, 5, 5, 5, 5)
    private val FS_POINTS = listOf(70, 50, 40, 40, 20, 20, 20, 20)
    private val MSI_POINTS = listOf(100, 80, 60, 60, 40, 40, 40, 40, 20, 20, 20, 20, 10, 10, 10, 10)

    fun splitWeight(split: Int) = when (split) { 3 -> 1.5; 2 -> 1.25; else -> 1.0 }
    fun endDay(season: Int) = Dates.day(season, 11, 22)

    val REAL_EVENTS: Map<String, Map<String, Pair<String, String>>> = mapOf(
        "lol" to mapOf("fs" to ("First Stand" to "FST"), "msi" to ("Mid-Season Invitational" to "MSI"), "worlds" to ("League of Legends World Championship" to "Worlds")),
        "mlbb" to mapOf("msi" to ("MLBB Mid Season Cup" to "MSC"), "worlds" to ("MLBB M-Series World Championship" to "M-Series")),
        "valorant" to mapOf("fs" to ("VALORANT Masters (spring)" to "Masters I"), "msi" to ("VALORANT Masters (summer)" to "Masters II"), "worlds" to ("VALORANT Champions" to "Champions")),
        "cs2" to mapOf("msi" to ("Counter-Strike Major (first half)" to "Major I"), "worlds" to ("Counter-Strike Major (second half)" to "Major II")),
        "dota2" to mapOf("worlds" to ("The International" to "TI")),
        "hok" to mapOf("msi" to ("Honor of Kings World Cup" to "KWC"), "worlds" to ("Honor of Kings International Championship" to "KIC")),
    )

    private fun stage(name: String, type: StageType, start: Int, bestOf: Int, dows: List<Int> = listOf(5, 6), groups: Int = 0, advance: Int = 0, daysBetween: Int = 2) =
        Stage(name, type, start, bestOf, dows, groups, advance, daysBetween)

    fun leagueStrength(l: League): Double {
        val pop = Catalogue.POPULARITY[l.titleId]!!
        pop[l.region]?.let { return it.toDouble() }
        val parts = l.region.substringAfter('-', "").split('.').filter { it.isNotEmpty() }
        return if (parts.isEmpty()) 30.0 else parts.maxOf { pop[it] ?: 0 } * 0.85
    }

    internal fun allocate(strengths: List<Double>, total: Int, maxPer: Int = 4): IntArray {
        val n = strengths.size
        val slots = IntArray(n) { 1 }
        var left = total - n
        val w = strengths.map { maxOf(1.0, it).pow(1.5) }
        while (left > 0) {
            var best = -1
            var bs = -1.0
            for (i in 0 until n) {
                if (slots[i] >= maxPer) continue
                val s = w[i] / (slots[i] + 0.5)
                if (s > bs) { bs = s; best = i }
            }
            if (best < 0) break
            slots[best]++
            left--
        }
        return slots
    }

    internal fun mk(w: World, t: TitleDef, season: Int, name: String, short: String, tier: CompTier, league: League?, entrants: List<String>, stages: List<Stage>, prize: Double, prestige: Int, points: List<Int>, split: Int = 0, key: String = ""): Competition {
        val defKey = key.ifBlank { if (league != null) "${t.id}:L${league.id}:split$split" else "" }
        // An event family's prestige evolves (comp.Prestige); each edition keeps the value it starts with.
        val c = Competition(w.comps.size, name, short, t.id, season, tier, league?.id ?: -1, split, league?.region ?: "", entrants, stages.toMutableList(),
            startDay = stages[0].startDay, circuitPoints = points, prizePool = (prize / 1000).toInt() * 1000.0, prestige = if (defKey.isEmpty()) prestige else Prestige.of(w, defKey, prestige),
            defKey = defKey, rules = Rulesets.of(t.id, season).id)
        w.comps.add(c)
        Fearless.assign(w, t, c)
        return c
    }

    private fun matchDays(from: Int, count: Int, dows: List<Int>): List<Int> {
        val out = ArrayList<Int>()
        var d = from
        while (out.size < count) {
            if (Dates.dow(d) in dows) out.add(d)
            d++
        }
        return out
    }

    private fun international(t: TitleDef, n: Int, start: Int): List<Stage> {
        val (std, po, _) = t.formats
        return when {
            n >= 16 -> listOf(Stage("Swiss Stage", StageType.SWISS, start, std.n, listOf(0, 1, 2, 3, 4, 5, 6), advance = 8, daysBetween = 1), stage("Knockout Stage", StageType.SINGLE_ELIM, start + 9, po.n, daysBetween = 3))
            n >= 7 -> listOf(stage("Group Stage", StageType.GROUPS, start, std.n, listOf(0, 1, 2, 3, 4, 5, 6), groups = maxOf(2, Math.round(n / 4.0).toInt()), advance = 2), stage("Knockout Stage", StageType.SINGLE_ELIM, start + 6, po.n, daysBetween = 3))
            else -> listOf(stage("Knockout Stage", StageType.SINGLE_ELIM, start, po.n, daysBetween = 3))
        }
    }

    /** Esports World Cup club championship points by placing in each game's event. */
    val EWC_CLUB_POINTS = listOf(1000, 600, 350, 350, 150, 150, 150, 150)
    const val EWC = "Esports World Cup"
    const val ENC = "Esports Nations Cup"
    private val EWC_CLUB_PRIZE = listOf(3_500_000.0, 2_000_000.0, 1_000_000.0, 600_000.0, 500_000.0, 400_000.0, 300_000.0, 250_000.0)

    /** Esports World Cup club championship: organisations by points over every game's event of the season. */
    fun ewcClubStandings(w: World, season: Int): List<Triple<Int, Int, Int>> {
        val pts = HashMap<Int, IntArray>() // org → points, events
        for (c in w.comps) {
            if (c.season != season || !c.name.startsWith(EWC) || !c.done) continue
            c.placements.forEachIndexed { i, tid -> val org = w.teams[tid].orgId; val a = pts.getOrPut(org) { IntArray(2) }; a[0] += EWC_CLUB_POINTS.getOrElse(i) { 50 }; a[1]++ }
        }
        return pts.entries.sortedByDescending { it.value[0] }.map { Triple(it.key, it.value[0], it.value[1]) }
    }

    /** When the last game's Esports World Cup ends: the club championship prizes. */
    fun ewcClubPayout(w: World, season: Int) {
        if (w.comps.any { it.season == season && it.name.startsWith(EWC) && !it.done }) return
        if (w.news.any { it.title == "$EWC $season Club Championship" }) return
        val table = ewcClubStandings(w, season)
        table.take(EWC_CLUB_PRIZE.size).forEachIndexed { i, (org, p, _) -> dev.uem.em.mgmt.Economy.ledger(w, w.orgs[org], "prize", EWC_CLUB_PRIZE[i], "$EWC Club Championship: ${dev.uem.em.core.ordinal(i + 1)} ($p points)") }
        table.firstOrNull()?.let { (org, p, n) -> w.news.add(dev.uem.em.model.News(w.day, "$EWC $season Club Championship", "${w.orgs[org].name} win the Club Championship with $p points from $n events.")) }
    }

    /** Short names of the continents for qualifier names. */
    private val CONTINENT_SHORT = mapOf("Asia" to "AS", "Europe" to "EU", "North America" to "NA", "South America" to "SA", "Africa" to "AF", "Oceania" to "OC")

    /** Whether a competition is an Esports Nations Cup qualifier. */
    fun isEncQualifier(c: Competition) = c.entrants.any { it.startsWith("continent:") }

    /**
     * A continent's Nations Cup qualifier: every nation, in small best-of-one groups (Monday to
     * Wednesday), then a best-of-three bracket the week after. The two finalists qualify.
     */
    private fun encQualifier(w: World, t: TitleDef, season: Int, continent: String, ti: Int, ci: Int): Competition {
        val n = dev.uem.em.mgmt.Extras.nationsOf(continent).size
        var start = Dates.day(season, 4, 6) + 7 * ((ti + ci) % 4)
        while (Dates.dow(start) != 1) start++
        val groups = when { n >= 40 -> (n + 3) / 4; n >= 24 -> 8; n >= 10 -> 4; n >= 5 -> 2; else -> 0 }
        val advance = if (groups >= 9) 1 else 2
        val stages = ArrayList<Stage>()
        if (groups > 0) stages.add(stage("Group Stage", StageType.GROUPS, start, 1, listOf(1, 2, 3), groups = groups, advance = advance, daysBetween = 1))
        stages.add(stage("Playoffs", StageType.SINGLE_ELIM, if (groups > 0) start + 7 else start, 3, listOf(1, 2, 3, 4), daysBetween = 1))
        val c = mk(w, t, season, "$ENC $season ${continent} Qualifier — ${t.name}", "ENC ${CONTINENT_SHORT[continent] ?: continent} Q ${t.short}", CompTier.INTERNATIONAL, null,
            listOf("continent:${t.id}:$continent"), stages, t.prizeScale * 40_000, 30, emptyList())
        c.finalBo = 3
        c.realName = true
        return c
    }

    /** The lower league a team plays in when it leaves [upper]: the one covering its organisation's country. */
    fun homeLower(w: World, upper: League, team: dev.uem.em.model.Team): League? {
        val lowers = w.leagues.filter { it.upperId == upper.id && it.tier == 2 && !it.academy }
        val org = w.orgs[team.orgId]
        return lowers.firstOrNull { org.region in GameData.expand(it.region) || org.country in GameData.expand(it.region) } ?: lowers.maxByOrNull { leagueStrength(it) }
    }

    /** Move a team to another league (its academy steps out of a league its parent now plays in). */
    fun moveTeam(w: World, team: dev.uem.em.model.Team, to: League) {
        w.leagues.getOrNull(team.leagueId)?.teamIds?.remove(team.id)
        team.leagueId = to.id
        if (team.id !in to.teamIds) to.teamIds.add(team.id)
        for (a in w.teams) if (a.parentId == team.id && a.leagueId == to.id) {
            to.teamIds.remove(a.id); a.leagueId = -1
            w.news.add(dev.uem.em.model.News(w.day, "${a.name} leave ${to.short}", "With ${team.name} in ${to.name}, their academy cannot play there and continues as a development squad.", listOf(a.id)))
        }
    }

    /**
     * End of season: the top two of each promotion tournament play the tier-1 league next season,
     * the tier-1 teams that missed out go down to their home lower league.
     */
    fun applyPromotions(w: World) {
        for (c in w.comps) {
            if (c.season != w.season || c.promoLeague < 0 || !c.done) continue
            val upper = w.leagues[c.promoLeague]
            val wasUp = c.teamIds.filter { w.teams[it].leagueId == upper.id }
            val slots = wasUp.size
            val winners = c.placements.take(slots)
            val up = winners.filter { it !in wasUp && w.teams[it].active }
            val down = wasUp.filter { it !in winners }
            // Survival series: the last place goes down and the best lower team comes up without playing.
            val bn = c.entrants.firstOrNull { it.startsWith("bottomnth:") }?.split(':')?.getOrNull(1)?.toIntOrNull()?.let { w.comps[it] }
            val pn = c.entrants.firstOrNull { it.startsWith("promonth:") }?.split(':')?.getOrNull(1)?.toIntOrNull()?.let { w.comps[it] }
            if (bn != null && pn != null) {
                val directDown = bn.placements.lastOrNull { w.teams[it].leagueId == upper.id && w.teams[it].active && it !in c.teamIds }
                val directUp = pn.placements.firstOrNull { w.teams[it].active && w.teams[it].parentId < 0 && w.teams[it].leagueId == pn.leagueId && it !in c.teamIds }
                if (directDown != null && directUp != null) {
                    homeLower(w, upper, w.teams[directDown])?.let { to ->
                        moveTeam(w, w.teams[directDown], to)
                        w.news.add(dev.uem.em.model.News(w.day, "${w.teams[directDown].name} relegated to ${to.short}", "${w.teams[directDown].name} finished last in ${upper.name} and go down directly.", listOf(directDown)))
                        moveTeam(w, w.teams[directUp], upper)
                        w.news.add(dev.uem.em.model.News(w.day, "${w.teams[directUp].name} promoted to ${upper.short}", "${w.teams[directUp].name} won ${pn.short} and go up directly.", listOf(directUp)))
                    }
                }
            }
            if (up.size != down.size) continue
            for (tid in down) {
                val team = w.teams[tid]
                val to = homeLower(w, upper, team) ?: continue
                moveTeam(w, team, to)
                w.news.add(dev.uem.em.model.News(w.day, "${team.name} relegated to ${to.short}", "${team.name} finished outside the top $slots of ${c.name} and play ${to.name} next season.", listOf(tid)))
            }
            for (tid in up) {
                val team = w.teams[tid]
                val from = w.leagues.getOrNull(team.leagueId)
                moveTeam(w, team, upper)
                w.news.add(dev.uem.em.model.News(w.day, "${team.name} promoted to ${upper.short}", "${team.name}${from?.let { " (${it.short})" } ?: ""} earned a place in ${upper.name} through ${c.name}.", listOf(tid)))
            }
        }
    }

    fun plan(w: World, season: Int) {
        val first = w.comps.size
        planTitles(w, season)
        // Host cities for this season's international events (earliest first, so no city hosts two at once).
        for (c in w.comps.drop(first).sortedBy { it.startDay }) {
            if ((c.tier != CompTier.INTERNATIONAL && c.tier != CompTier.WORLDS) || isEncQualifier(c) || c.defKey.isEmpty()) continue
            if (c.name.startsWith(EWC)) { c.host = "Riyadh, SA"; c.hostNote = "The Esports World Cup is held in Riyadh."; continue }
            Hosts.choose(w, c)
        }
    }

    private fun planTitles(w: World, season: Int) {
        w.titles.forEachIndexed { ti, titleId ->
            val t = Catalogue.get(titleId)
            val off = ti % 3
            fun D(m: Int, d: Int) = Dates.day(season, m, d) + off
            // Reforms announced for this season take effect first (a new format, leagues expanded, merged or split).
            if (Formats.applies(titleId)) Formats.applyPending(w, titleId, season)
            val tier1 = w.leagues.filter { it.titleId == titleId && it.tier == 1 && it.until == 0 }.sortedByDescending { leagueStrength(it) }
            val tier2 = w.leagues.filter { it.titleId == titleId && it.tier == 2 && it.until == 0 }
            if (titleId == "valorant" && Rulesets.vctOpen(season) && tier1.isNotEmpty()) {
                planVctOpen(w, t, season, tier1, ::D)
                nationsCup(w, t, season, ti)
                return@forEachIndexed
            }
            val splitComps = HashMap<Int, MutableList<Competition>>()
            for (l in tier1 + tier2) {
                val size = l.teamIds.size
                val strength = leagueStrength(l)
                val prizeBase = t.prizeScale * 1_000_000 * (if (l.tier == 1) 0.08 else 0.012) * (0.4 + strength / 100)
                val list = ArrayList<Competition>()
                for (sp in SPLITS) {
                    val dbl = sp.double && size <= 10
                    val rounds = (if (size % 2 == 0) size - 1 else size) * (if (dbl) 2 else 1)
                    val days = matchDays(D(sp.m, sp.d), rounds, sp.dows)
                    val (poType, take) = when {
                        l.tier == 2 || size < 8 -> StageType.SINGLE_ELIM to 4
                        !t.isMoba -> StageType.DOUBLE_ELIM to 8
                        else -> StageType.SINGLE_ELIM to 6
                    }
                    val regAdv = Stage("Regular Season", if (dbl) StageType.DRR else StageType.RR, D(sp.m, sp.d), t.formats.first.n, sp.dows, advance = minOf(take, size))
                    val po = stage("Playoffs", poType, days.last() + 4, t.formats.second.n, daysBetween = if (poType == StageType.DOUBLE_ELIM) 2 else 3)
                    list.add(mk(w, t, season, "${l.name} $season Split ${sp.n}", "${l.short} S${sp.n}", if (l.tier == 1) CompTier.LEAGUE else CompTier.CHALLENGER, l,
                        listOf("league:${l.id}"), listOf(regAdv, po), prizeBase * (if (sp.n == 1) 0.7 else 1.0), if (l.tier == 1) (25 + strength * 0.3).toInt() else 12,
                        if (l.tier == 1) SPLIT_POINTS else emptyList(), sp.n))
                }
                splitComps[l.id] = list
            }
            if (tier1.isEmpty()) return@forEachIndexed
            // Promotion tournaments: a tier-1 league with its own lower leagues (LCP: LJL, PCS, VCS) puts its
            // last two teams against the lower leagues' champions (the strongest lower league sends two); the
            // top two play the tier-1 league next season. Midweek in October, after the last splits.
            for (upper in tier1) {
                // Independent lower leagues only (not an academy league; the upper league's own Challengers division only when
                // a major patch opened the league up — see Formats.proRel). A franchised league has none.
                val lowers = Formats.promotionLowers(w, upper)
                if (lowers.isEmpty() || upper.teamIds.size < 6) continue
                val last = splitComps[upper.id]?.lastOrNull() ?: continue
                val fmt = w.formats[titleId]?.promo?.get(upper.id) ?: "rr"
                val playoff = fmt == "playoff"
                val survival = fmt == "survival"
                // Playoffs: the bottom two against the lower leagues' best two, cross-seeded (9th vs the best, 10th vs the second);
                // the two semi-final winners take the places (the final decides nothing but the title).
                // Survival series: the last place goes down and the best lower team comes up directly; the second-last plays
                // the lower leagues' second-best in one series for the last place.
                val lowSplit = splitComps[lowers[0].id]!!.last().id
                val rules = when {
                    survival -> listOf("bottomnth:${last.id}:2", "promonth:$lowSplit:2")
                    playoff -> listOf("bottom:${last.id}:2") + (if (lowers.size == 1) listOf("promo:$lowSplit:2") else lowers.take(2).map { "promo:${splitComps[it.id]!!.last().id}:1" })
                    else -> listOf("bottom:${last.id}:2") + lowers.mapIndexed { i, l -> "promo:${splitComps[l.id]!!.last().id}:${if (i == 0) 2 else 1}" }
                }
                val pc = mk(w, t, season, when { survival -> "${upper.name} Survival Series $season"; playoff -> "${upper.name} Promotion Playoffs $season"; else -> "${upper.name} Promotion $season" }, "${upper.short} PRO", CompTier.CHALLENGER, null, rules,
                    listOf(when {
                        survival -> stage("Survival Series", StageType.SINGLE_ELIM, D(10, 13), t.formats.third.n, listOf(1, 2, 3), daysBetween = 1)
                        playoff -> stage("Promotion Playoffs", StageType.SINGLE_ELIM, D(10, 13), t.formats.second.n, listOf(1, 2, 3), daysBetween = 1)
                        else -> stage("Promotion", StageType.RR, D(10, 13), t.formats.second.n, listOf(1, 2, 3), daysBetween = 1)
                    }), t.prizeScale * 60_000, 20, emptyList())
                pc.promoLeague = upper.id
                pc.realName = true
            }
            val real = REAL_EVENTS[titleId]
            fun name(k: String, generic: String, short: String): Triple<String, String, Boolean> =
                real?.get(k)?.let { Triple("${it.first} $season", it.second, true) } ?: Triple("${t.short} $generic $season", "${t.short} $short", false)
            // LoL, MLBB, HoK, Wild Rift: internationals with formats that evolve at major patches (comp.Formats).
            if (Formats.applies(titleId) && tier1.size >= 2) {
                planEvolving(w, t, season, tier1, splitComps, ::name, ::D)
                nationsCup(w, t, season, ti)
                return@forEachIndexed
            }
            fun topOf(split: Int, two: (Int) -> Boolean) = tier1.mapIndexed { i, l -> "top:${splitComps[l.id]!![split - 1].id}:${if (two(i)) 2 else 1}" }
            fun count(rules: List<String>) = rules.sumOf { it.split(':')[2].toInt() }
            val lol = titleId == "lol"
            val fsE = (if (lol) topOf(1) { i -> tier1[i].short in setOf("LCK", "LPL") } else topOf(1) { i -> tier1.size <= 3 || i < 2 }).take(8)
            val fsN = name("fs", "First Stand", "FST")
            val fs = mk(w, t, season, fsN.first, fsN.second, CompTier.INTERNATIONAL, null, fsE, international(t, minOf(8, count(fsE)), D(3, 6)), t.prizeScale * 250_000, 60, FS_POINTS, key = "$titleId:fs")
            fs.realName = fsN.third
            Fearless.assign(w, t, fs)
            val msiN = name("msi", "Mid-Season Invitational", "MSI")
            // Counter-Strike Majors: Valve-style regional-standings invitations plus open qualifiers per region.
            val cs = titleId == "cs2"
            val csInvites = mapOf(0 to 6, 1 to 3, 2 to 3)
            fun csEntrants(): List<String> = tier1.mapIndexed { i, l -> "invite:cs2:L${l.id}:${csInvites[i] ?: 2}" }
            val msiE = when {
                cs -> csEntrants()
                lol -> topOf(2) { i -> tier1[i].short != "CBLOL" }
                else -> topOf(2) { i -> tier1.size <= 2 || i < 2 || tier1.size <= 4 }
            }
            val n = if (cs) 16 else count(msiE)
            val msiStages = if (n > 8) listOf(stage("Play-In Groups", StageType.GROUPS, D(6, 26), t.formats.first.n, listOf(0, 1, 2, 3, 4, 5, 6), groups = 2, advance = 4), stage("Bracket Stage", StageType.DOUBLE_ELIM, D(6, 26) + 6, t.formats.second.n))
            else listOf(stage("Bracket Stage", StageType.DOUBLE_ELIM, D(6, 26), t.formats.second.n))
            val msi = mk(w, t, season, msiN.first, msiN.second, CompTier.INTERNATIONAL, null, msiE, if (cs) international(t, 16, D(6, 26)) else msiStages, t.prizeScale * 400_000, 70, MSI_POINTS, key = "$titleId:msi")
            msi.realName = msiN.third
            if (cs) openQualifiers(w, t, season, msi, tier1, csInvites)
            // Esports World Cup (after the mid-season event): its top four and the strongest other teams; club championship across all games.
            ewcFor(w, t, season, msi, D(7, 14))
            val target = maxOf(4, minOf(16, tier1.size * 4 / 4 * 4))
            // LoL (2026 handbook): the MSI winner qualifies directly and the MSI runner-up's region gets an extra seed — two of the 16 places.
            val slots = allocate(tier1.map { leagueStrength(it) }, if (lol && target >= 8) target - 2 else target, 4)
            val dota = titleId == "dota2"
            val wE = when {
                cs -> csEntrants()
                // The International: invitations on results, then two places from each region's qualifier.
                dota -> listOf("invite:dota2:all:${maxOf(4, 16 - 2 * tier1.size)}")
                lol && target >= 8 -> listOf("msiwin:${msi.id}") + tier1.mapIndexed { i, l -> "circuit:${l.id}:${minOf(slots[i], l.teamIds.size)}" } + "msiseed:${msi.id}"
                else -> tier1.mapIndexed { i, l -> "circuit:${l.id}:${minOf(slots[i], l.teamIds.size)}" }
            }
            val wN = name("worlds", "World Championship", "Worlds")
            val worldsN = when { cs || dota -> 16; lol && target >= 8 -> target; else -> count(wE) }
            val worlds = mk(w, t, season, wN.first, wN.second, CompTier.WORLDS, null, wE, international(t, worldsN, D(9, 24)), t.prizeScale * 1_000_000, 100, emptyList(), key = "$titleId:worlds")
            worlds.realName = wN.third
            if (cs) openQualifiers(w, t, season, worlds, tier1, csInvites)
            if (dota) { worlds.entrants = worlds.entrants + tier1.map { "qual:${regionalQualifier(w, t, season, worlds, it).id}:2" } }
            nationsCup(w, t, season, ti)
        }
    }

    /**
     * First Stand, MSI, the Esports World Cup, Worlds with its regional finals, and the best-of-the-rest
     * invitational, each with the game's current format (seeds per league, play-in, stages, best-of).
     * With the formats never changed this is the 2026 calendar exactly.
     */
    private fun planEvolving(w: World, t: TitleDef, season: Int, tier1: List<League>, splitComps: Map<Int, List<Competition>>,
                             name: (String, String, String) -> Triple<String, String, Boolean>, D: (Int, Int) -> Int) {
        val tf = Formats.of(w, t.id)
        val rank = Formats.rankIdx(w, t.id, tier1)
        fun split(l: League, n: Int) = splitComps[l.id]!![n - 1]
        fun topRule(n: Int) = { l: League, _: Int, from: Int, count: Int -> "top:${split(l, n).id}:$count" + if (from > 0) ":$from" else "" }
        fun topPi(n: Int) = { _: Int, l: League, from: Int, s: Int -> "top:${split(l, n).id}:${s - from}:$from" }
        val fs = planEvent(w, t, season, "fs", name("fs", "First Stand", "FST"), CompTier.INTERNATIONAL, D(3, 6), tier1, rank, Formats.seeds(w, t.id, "fs", tier1), tf.events["fs"],
            emptyList(), null, topRule(1), topPi(1), { n, st -> international(t, minOf(8, n), st) }, t.prizeScale * 250_000, 60, FS_POINTS)
        Fearless.assign(w, t, fs)
        val msi = planEvent(w, t, season, "msi", name("msi", "Mid-Season Invitational", "MSI"), CompTier.INTERNATIONAL, D(6, 26), tier1, rank, Formats.seeds(w, t.id, "msi", tier1), tf.events["msi"],
            emptyList(), null, topRule(2), topPi(2), { n, st ->
                if (n > 8) listOf(stage("Play-In Groups", StageType.GROUPS, st, t.formats.first.n, listOf(0, 1, 2, 3, 4, 5, 6), groups = 2, advance = 4), stage("Bracket Stage", StageType.DOUBLE_ELIM, st + 6, t.formats.second.n))
                else listOf(stage("Bracket Stage", StageType.DOUBLE_ELIM, st, t.formats.second.n)) }, t.prizeScale * 400_000, 70, MSI_POINTS)
        ewcFor(w, t, season, msi, D(7, 14))
        val wef = tf.events["worlds"]
        val mode = if (wef?.qual == "points") "points" else "placings"
        val wSeeds = Formats.seeds(w, t.id, "worlds", tier1)
        val circuitRule = { l: League, s: Int, from: Int, count: Int -> if (from == 0 && count == s && mode == "placings") "circuit:${l.id}:$s" else "circuit:${l.id}:$s:$from:$count:$mode" }
        val worlds = planEvent(w, t, season, "worlds", name("worlds", "World Championship", "Worlds"), CompTier.WORLDS, D(9, 24), tier1, rank, wSeeds, wef,
            if (Formats.winnerSpot(t.id, wef)) listOf("msiwin:${msi.id}") else emptyList(), if (Formats.runnerSeed(t.id, wef)) "msiseed:${msi.id}" else null,
            circuitRule, { mainId, l, from, s -> "pseed:$mainId:${l.id}:$from:$s:$mode" }, { n, st -> international(t, n, st) }, t.prizeScale * 1_000_000, 100, emptyList())
        // Regional finals (LPL-style last chance) for the places the placings do not decide.
        if (tf.lastChance) tier1.forEachIndexed { i, l -> regionalFinals(w, t, season, worlds, l, wSeeds[i], wSeeds[i], mode) }
        if (tf.invitational) invitational(w, t, season, worlds, tier1, rank)
        if (tf.academyCup) academyCup(w, t, season, splitComps, D)
        // Rivalry cup of the two best-ranked leagues, after the Nations Cup.
        if (tier1.size >= 2) {
            val a = tier1[rank[0]]; val b = tier1[rank[1]]
            val c = mk(w, t, season, "${a.short}–${b.short} Rivalry Cup $season", "${a.short}v${b.short}", CompTier.CHALLENGER, null, listOf("rival:${a.id}:${b.id}:4"),
                listOf(stage("Knockout Stage", StageType.SINGLE_ELIM, D(11, 14), t.formats.second.n, listOf(0, 1, 2, 3, 4, 5, 6), daysBetween = 1)), t.prizeScale * 80_000, 40, emptyList(), key = "${t.id}:rivalry")
            c.realName = true
            if (tf.allStar) {
                val sh = mk(w, t, season, "${a.short} vs ${b.short} All-Star Showdown $season", "${a.short}★${b.short}", CompTier.CHALLENGER, null, listOf("allstar:${a.id}", "allstar:${b.id}"),
                    listOf(stage("Showdown", StageType.SINGLE_ELIM, D(11, 19), t.formats.third.n, listOf(0, 1, 2, 3, 4, 5, 6), daysBetween = 1)), t.prizeScale * 30_000, 30, emptyList(), key = "${t.id}:allstar")
                sh.realName = true
            }
        }
    }

    /**
     * Continental Academy Champions League: the champion and runner-up of each academy league's last split, after
     * the senior season (late October): groups of four (two groups up to 8 teams, four groups beyond), the top
     * two of each group to a knockout. Academy teams only; none of it counts for senior seeds or circuit points.
     */
    private fun academyCup(w: World, t: TitleDef, season: Int, splitComps: Map<Int, List<Competition>>, D: (Int, Int) -> Int) {
        val als = w.leagues.filter { it.titleId == t.id && it.academy && splitComps[it.id] != null && it.teamIds.size >= 4 }.sortedByDescending { leagueStrength(it) }.take(8)
        if (als.size < 2) return
        val n = als.size * 2
        val groups = if (n <= 8) 2 else 4
        val start = D(10, 20)
        val all = listOf(0, 1, 2, 3, 4, 5, 6)
        val stages = listOf(stage("Group Stage", StageType.GROUPS, start, t.formats.first.n, all, groups = groups, advance = 2, daysBetween = 1),
            stage("Knockout Stage", StageType.SINGLE_ELIM, start + ceil(n.toDouble() / groups).toInt() + 2, t.formats.second.n, all, daysBetween = 1))
        val c = mk(w, t, season, "${t.short} Continental Academy Champions League $season", "${t.short} CACL", CompTier.CHALLENGER, null,
            als.map { "top:${splitComps[it.id]!!.last().id}:2" }, stages, t.prizeScale * 40_000, 28, emptyList(), key = "${t.id}:cacl")
        c.realName = true
    }

    /**
     * An international under its format. Without a play-in every seed goes straight in. With one, the main
     * event keeps [EventFormat.size] places: the best-ranked leagues' top seeds go straight in (every league's
     * first seed, then second seeds...), the rest play the play-in (its own competition, before the main
     * event), whose top [EventFormat.playInAdvance] join the main event.
     */
    private fun planEvent(w: World, t: TitleDef, season: Int, key: String, nm: Triple<String, String, Boolean>, tier: CompTier, start: Int, tier1: List<League>, rank: List<Int>,
                          seeds: IntArray, ef: dev.uem.em.model.EventFormat?, before: List<String>, runner: String?,
                          rule: (League, Int, Int, Int) -> String, piRule: (Int, League, Int, Int) -> String, default: (Int, Int) -> List<Stage>,
                          prize: Double, prestige: Int, points: List<Int>): Competition {
        val extra = if (runner != null) 1 else 0
        val adv = ef?.playInAdvance ?: 0
        val size = ef?.size ?: 0
        val direct = (size - adv - before.size).coerceIn(0, seeds.sum())
        val mainS = Formats.mainSeeds(seeds, rank, direct)
        val piN = seeds.sum() - mainS.sum() + extra
        val withPi = ef != null && ef.playIn.isNotEmpty() && adv > 0 && size > 0 && piN >= 2 && adv < piN
        if (!withPi) {
            val n = before.size + seeds.sum() + extra
            val rules = before + tier1.indices.filter { seeds[it] > 0 }.map { rule(tier1[it], seeds[it], 0, seeds[it]) } + listOfNotNull(runner)
            val c = mk(w, t, season, nm.first, nm.second, tier, null, rules, Formats.mainStages(t, key, ef, n, start, default(n, start)), prize, prestige, points, key = "${t.id}:$key")
            c.realName = nm.third
            return c
        }
        val mainStart = start + Formats.playInDays(ef!!.playIn, piN)
        val mainN = before.size + mainS.sum() + adv
        val rules = before + tier1.indices.filter { mainS[it] > 0 }.map { rule(tier1[it], seeds[it], 0, mainS[it]) } + listOfNotNull(runner)
        val main = mk(w, t, season, nm.first, nm.second, tier, null, rules, Formats.mainStages(t, key, ef, mainN, mainStart, default(mainN, mainStart)), prize, prestige, points, key = "${t.id}:$key")
        main.realName = nm.third
        val piRules = tier1.indices.filter { seeds[it] > mainS[it] || runner != null }.map { piRule(main.id, tier1[it], mainS[it], seeds[it]) }
        val pi = mk(w, t, season, "${nm.first} Play-In", "${nm.second} PI", CompTier.INTERNATIONAL, null, piRules, Formats.playInStages(t, ef.playIn, piN, adv, start),
            prize * 0.08, maxOf(30, prestige - 25), if (points.isEmpty()) emptyList() else List(piN) { if (it < adv) 0 else 10 }, key = "${t.id}:$key:playin")
        pi.realName = nm.third
        pi.finalBo = if (ef.playIn == "decider") 5 else t.formats.second.n
        main.entrants = main.entrants + "qual:${pi.id}:$adv"
        return main
    }

    /**
     * LPL-style regional finals: when a league has more Worlds places than its playoffs decide (three places:
     * the finalists go, the third is played for), the next six teams on circuit points play two groups of
     * three, then semi-finals and a final; the winner (the top two for two open places) goes to Worlds.
     * After MSI an extra seed can add one (see [afterFinish]).
     */
    internal fun regionalFinals(w: World, t: TitleDef, season: Int, worlds: Competition, l: League, places: Int, base: Int, mode: String): Competition? {
        if (w.comps.any { it.season == season && it.defKey == "${t.id}:rf:L${l.id}" }) return null
        if (places - Formats.direct(places) <= 0) return null
        val n = minOf(6, l.teamIds.count { w.teams[it].active } - places - 1)
        if (n < 3) return null
        val first = w.comps.filter { it.season == season && (it.id == worlds.id || it.defKey == worlds.defKey + ":playin") }.minOf { it.startDay }
        val start = first - 12
        val all = listOf(0, 1, 2, 3, 4, 5, 6)
        val stages = if (n >= 6) listOf(stage("Group Stage", StageType.GROUPS, start, t.formats.second.n, all, groups = 2, advance = 2, daysBetween = 1), stage("Knockout Stage", StageType.SINGLE_ELIM, start + 4, t.formats.second.n, all, daysBetween = 1))
            else listOf(stage("Knockout Stage", StageType.SINGLE_ELIM, start, t.formats.second.n, all, daysBetween = 1))
        val c = mk(w, t, season, "${l.name} Regional Finals $season", "${l.short} RF", CompTier.CHALLENGER, null, listOf("lcq:${worlds.id}:${l.id}:$n:$base:$mode"), stages,
            t.prizeScale * 50_000, 35, emptyList(), key = "${t.id}:rf:L${l.id}")
        c.realName = true
        return c
    }

    /**
     * The best of the rest (like LoL's Demacia Cup Global Invitational): during Worlds, two to four teams per
     * league that missed it (the best-ranked leagues send four), chosen on circuit points within their league.
     */
    private fun invitational(w: World, t: TitleDef, season: Int, worlds: Competition, tier1: List<League>, rank: List<Int>) {
        val per = IntArray(tier1.size)
        rank.forEachIndexed { r, i -> per[i] = when { r < (tier1.size + 2) / 3 -> 4; r < (2 * tier1.size + 2) / 3 -> 3; else -> 2 } }
        val n = per.sum()
        val start = worlds.startDay + 2
        val all = listOf(0, 1, 2, 3, 4, 5, 6)
        val stages = when {
            n >= 16 && n % 2 == 0 -> listOf(Stage("Swiss Stage", StageType.SWISS, start, t.formats.first.n, all, advance = 8, daysBetween = 1), stage("Knockout Stage", StageType.SINGLE_ELIM, start + 7, t.formats.second.n, all, daysBetween = 1))
            n >= 8 -> listOf(stage("Group Stage", StageType.GROUPS, start, t.formats.first.n, all, groups = if (n >= 12) 4 else 2, advance = 2, daysBetween = 1), stage("Knockout Stage", StageType.SINGLE_ELIM, start + 6, t.formats.second.n, all, daysBetween = 1))
            else -> listOf(stage("Knockout Stage", StageType.SINGLE_ELIM, start, t.formats.second.n, all, daysBetween = 1))
        }
        val (nm, short) = if (t.id == "lol") "Demacia Cup Global Invitational $season" to "DCGI" else "${t.short} Global Invitational $season" to "${t.short} GI"
        val c = mk(w, t, season, nm, short, CompTier.INTERNATIONAL, null, tier1.indices.map { "rest:${worlds.id}:${tier1[it].id}:${per[it]}" }, stages,
            t.prizeScale * 200_000, 45, emptyList(), key = "${t.id}:dcgi")
        c.realName = t.id == "lol"
    }

    /**
     * After MSI: its runner-up's region has an extra Worlds seed, which can call for regional finals (two
     * places become three) or make planned ones unnecessary (three become four: the semi-finalists).
     */
    internal fun afterFinish(w: World, c: Competition) {
        if (!Formats.applies(c.titleId) || c.defKey != "${c.titleId}:msi") return
        val worlds = w.comps.firstOrNull { it.season == c.season && it.defKey == "${c.titleId}:worlds" && it.status == 0 && "msiseed:${c.id}" in it.entrants } ?: return
        val runner = c.placements.getOrNull(1) ?: return
        val league = w.leagues.getOrNull(w.teams[runner].leagueId)?.takeIf { it.tier == 1 } ?: return
        val pi = w.comps.firstOrNull { it.season == c.season && it.defKey == worlds.defKey + ":playin" }
        val rule = worlds.entrants.firstOrNull { it.startsWith("circuit:${league.id}:") }?.split(':')
        val pr = pi?.entrants?.firstOrNull { it.startsWith("pseed:${worlds.id}:${league.id}:") }?.split(':')
        val base = rule?.getOrNull(2)?.toIntOrNull() ?: pr?.getOrNull(4)?.toIntOrNull() ?: return
        val mode = rule?.getOrNull(5) ?: pr?.getOrNull(5) ?: "placings"
        val places = base + 1
        val rf = w.comps.firstOrNull { it.season == c.season && it.defKey == "${c.titleId}:rf:L${league.id}" }
        if (rf != null && rf.status == 0 && places - Formats.direct(places) <= 0) {
            rf.status = 2
            rf.name = "${rf.name} (cancelled)"
            w.news.add(dev.uem.em.model.News(w.day, "${rf.short} cancelled", "${league.short} gets an extra Worlds seed as the region of the ${c.short} runner-up: its $places places go to the playoff semi-finalists, so the regional finals are not needed."))
            return
        }
        if (rf == null && w.formats[c.titleId]?.lastChance != false) regionalFinals(w, Catalogue.get(c.titleId), c.season, worlds, league, places, base, mode)?.let {
            w.news.add(dev.uem.em.model.News(w.day, "${it.short} added", "${league.short} gets an extra Worlds seed as the region of the ${c.short} runner-up: the finalists go, and the next six on circuit points play ${it.name} for the third place."))
        }
    }

    /** Esports World Cup (after the mid-season event): its top four and the strongest other teams; club championship across all games. */
    private fun ewcFor(w: World, t: TitleDef, season: Int, src: Competition, start: Int) {
        val ewc = mk(w, t, season, "$EWC $season — ${t.name}", "EWC ${t.short}", CompTier.INTERNATIONAL, null, listOf("top:${src.id}:4", "elo:${t.id}:4"),
            listOf(stage("Group Stage", StageType.GROUPS, start, t.formats.first.n, listOf(0, 1, 2, 3, 4, 5, 6), groups = 2, advance = 2, daysBetween = 1),
                stage("Playoffs", StageType.SINGLE_ELIM, start + 4, t.formats.second.n, listOf(0, 1, 2, 3, 4, 5, 6), daysBetween = 1)),
            t.prizeScale * 700_000, 80, emptyList(), key = "${t.id}:ewc")
        ewc.realName = true
    }

    /**
     * Esports Nations Cup: continental qualifiers after First Stand (every nation of the continent;
     * midweek so they never clash with league days), then the two finalists of each continent at the
     * Nations Cup after the World Championship.
     */
    private fun nationsCup(w: World, t: TitleDef, season: Int, ti: Int) {
        val quals = GameData.CONTINENTS.mapIndexed { ci, continent -> encQualifier(w, t, season, continent, ti, ci) }
        val encStart = Dates.day(season, 11, 2) + ti % 3
        mk(w, t, season, "$ENC $season — ${t.name}", "ENC ${t.short}", CompTier.INTERNATIONAL, null, quals.map { "qual:${it.id}:2" } + "fill:${t.id}:12",
            listOf(stage("Group Stage", StageType.GROUPS, encStart, t.formats.first.n, listOf(0, 1, 2, 3, 4, 5, 6), groups = 2, advance = 4, daysBetween = 1),
                stage("Playoffs", StageType.SINGLE_ELIM, encStart + 6, t.formats.second.n, listOf(0, 1, 2, 3, 4, 5, 6), daysBetween = 1)),
            t.prizeScale * 300_000, 75, emptyList(), key = "${t.id}:enc").realName = true
    }

    /** A region's open qualifier for an invitation event (CS2 Majors): every team of the region not invited, single elimination. */
    private fun openQualifiers(w: World, t: TitleDef, season: Int, main: Competition, tier1: List<League>, invites: Map<Int, Int>) {
        val add = ArrayList<String>()
        tier1.forEachIndexed { i, l ->
            val q = mk(w, t, season, "${main.name} — ${l.short} Open Qualifier", "${main.short} ${l.short} OQ", CompTier.CHALLENGER, null,
                listOf("open:${t.id}:L${l.id}:16:${main.id}:all"), listOf(stage("Open Qualifier", StageType.SINGLE_ELIM, main.startDay - 16, t.formats.second.n, listOf(0, 1, 2, 3, 4, 5, 6), daysBetween = 1)),
                t.prizeScale * 30_000, 18, emptyList(), key = "${t.id}:${main.defKey.substringAfter(':')}:oq:L${l.id}")
            q.realName = true
            add.add("qual:${q.id}:${if (i == 0) 2 else 1}")
        }
        main.entrants = main.entrants + add
    }

    /** A region's closed qualifier for The International: the region's teams that were not invited. */
    private fun regionalQualifier(w: World, t: TitleDef, season: Int, main: Competition, l: League): Competition {
        val q = mk(w, t, season, "${main.name} — ${l.short} Regional Qualifier", "${main.short} ${l.short} RQ", CompTier.CHALLENGER, null,
            listOf("open:${t.id}:L${l.id}:12:${main.id}:all"), listOf(stage("Regional Qualifier", StageType.SINGLE_ELIM, main.startDay - 16, t.formats.second.n, listOf(0, 1, 2, 3, 4, 5, 6), daysBetween = 1)),
            t.prizeScale * 40_000, 25, emptyList(), key = "${t.id}:worlds:rq:L${l.id}")
        q.realName = true
        return q
    }

    /**
     * VALORANT from 2027 (official announcement, 8 Apr 2026): one tier. Each of three stages has an open
     * qualifier per territory (any team without a partner place), a territory event (Kickoff, Cup 1, Cup 2:
     * partner teams seeded in, two qualifiers join) and a global event: Masters Seoul and Masters Valencia
     * take the top three of each territory event; Champions takes Cup 2's top two per territory and the
     * two best on championship points. Partners have no automatic international place.
     */
    private fun planVctOpen(w: World, t: TitleDef, season: Int, territories: List<League>, D: (Int, Int) -> Int) {
        val named = season == 2027
        val stagesDef = listOf(Triple("Kickoff", D(1, 2), D(1, 16)), Triple("Cup 1", D(3, 22), D(4, 5)), Triple("Cup 2", D(7, 27), D(8, 6)))
        val events = HashMap<Int, MutableList<Competition>>()
        for ((si, def) in stagesDef.withIndex()) {
            val (label, oqDay, evDay) = def
            for (l in territories) {
                val base = l.short.removePrefix("VCT ").trim()
                val oq = mk(w, t, season, "VCT $base $label Open Qualifier $season", "$base ${label.replace(" ", "")} OQ", CompTier.CHALLENGER, null,
                    listOf("open:${t.id}:L${l.id}:16"), listOf(stage("Open Qualifier", StageType.SINGLE_ELIM, oqDay, t.formats.second.n, listOf(0, 1, 2, 3, 4, 5, 6), daysBetween = 1)),
                    t.prizeScale * 25_000, 15, emptyList(), key = "${t.id}:L${l.id}:${label.lowercase().replace(" ", "")}:oq")
                oq.realName = true
                val n = l.teamIds.size + 2
                val ev = mk(w, t, season, "VCT $base $label $season", "$base ${label.replace(" ", "")}", CompTier.LEAGUE, l,
                    listOf("league:${l.id}", "top:${oq.id}:2"), international(t, n, evDay), t.prizeScale * 1_000_000 * 0.08 * (0.4 + leagueStrength(l) / 100),
                    (25 + leagueStrength(l) * 0.3).toInt(), SPLIT_POINTS, si + 1)
                ev.realName = true
                events.getOrPut(l.id) { ArrayList() }.add(ev)
            }
        }
        fun global(si: Int, name: String, short: String, start: Int, prize: Double, prestige: Int, points: List<Int>, entr: List<String>, n: Int, key: String, tier: CompTier = CompTier.INTERNATIONAL): Competition {
            val c = mk(w, t, season, "$name $season", short, tier, null, entr, international(t, n, start), t.prizeScale * prize, prestige, points, key = key)
            c.realName = true
            return c
        }
        val m1 = global(0, if (named) "VALORANT Masters Seoul" else "VALORANT Masters (spring)", "Masters I", D(3, 1), 250_000.0, 60, FS_POINTS,
            territories.map { "top:${events[it.id]!![0].id}:3" }, territories.size * 3, "${t.id}:fs")
        val m2 = global(1, if (named) "VALORANT Masters Valencia" else "VALORANT Masters (summer)", "Masters II", D(6, 7), 400_000.0, 70, MSI_POINTS,
            territories.map { "top:${events[it.id]!![1].id}:3" }, territories.size * 3, "${t.id}:msi")
        ewcFor(w, t, season, m2, D(7, 14))
        global(2, if (named) "VALORANT Champions Austin" else "VALORANT Champions", "Champions", D(9, 24), 1_000_000.0, 100, emptyList(),
            territories.map { "top:${events[it.id]!![2].id}:2" } + territories.map { "points:L${it.id}:2" }, territories.size * 4, "${t.id}:worlds", CompTier.WORLDS)
        @Suppress("UNUSED_VARIABLE") val unused = m1
    }
}

/** Stage scheduling, brackets and progression. */
object Stages {
    private fun addFixture(w: World, c: Competition, stageIdx: Int, day: Int, a: Int, b: Int, bestOf: Int, round: Int = 0, group: Int = 0, nodeId: String = ""): Fixture {
        val f = Fixture(w.fixtures.size, c.id, stageIdx, c.titleId, day, a, b, bestOf, round, group, nodeId)
        w.fixtures.add(f)
        w.schedule.getOrPut(day) { ArrayList() }.add(f.id)
        c.stages[stageIdx].fixtureIds.add(f.id)
        return f
    }

    private fun matchDays(from: Int, count: Int, dows: List<Int>): List<Int> {
        val out = ArrayList<Int>()
        var d = from
        while (out.size < count) {
            if (Dates.dow(d) in dows) out.add(d)
            d++
        }
        return out
    }

    fun roundRobin(teams: List<Int>): List<List<Pair<Int, Int>>> {
        val list = teams.toMutableList()
        if (list.size % 2 == 1) list.add(-1)
        val n = list.size
        val rounds = ArrayList<List<Pair<Int, Int>>>()
        val arr = list.toMutableList()
        for (r in 0 until n - 1) {
            val pairs = ArrayList<Pair<Int, Int>>()
            for (i in 0 until n / 2) {
                val a = arr[i]
                val b = arr[n - 1 - i]
                if (a >= 0 && b >= 0) pairs.add(if (r % 2 == 0) a to b else b to a)
            }
            rounds.add(pairs)
            arr.add(1, arr.removeAt(arr.size - 1))
        }
        return rounds
    }

    private fun seeding(size: Int): List<Int> {
        var order = listOf(1, 2)
        while (order.size < size) {
            val n = order.size * 2
            order = order.flatMap { listOf(it, n + 1 - it) }
        }
        return order
    }

    private fun singleElim(n: Int, bestOf: Int, finalBo: Int): MutableList<BracketNode> {
        val size = maxOf(2, 2.0.pow(ceil(ln(maxOf(2, n).toDouble()) / ln(2.0))).toInt())
        val rounds = (ln(size.toDouble()) / ln(2.0)).toInt()
        val seeds = seeding(size)
        fun label(r: Int): String = when (size / 2.0.pow(r).toInt()) { 2 -> "Grand Final"; 4 -> "Semifinal"; 8 -> "Quarterfinal"; else -> "Round of ${size / 2.0.pow(r).toInt()}" }
        val nodes = ArrayList<BracketNode>()
        for (i in 0 until size / 2) nodes.add(BracketNode("r0m$i", 0, label(0), "s${seeds[2 * i]}", "s${seeds[2 * i + 1]}", size / 2 + 1, rounds == 1, if (rounds == 1) finalBo else bestOf))
        for (r in 1 until rounds) {
            val count = size / 2.0.pow(r + 1).toInt()
            for (i in 0 until count) {
                val fin = r == rounds - 1
                nodes.add(BracketNode("r${r}m$i", r, label(r), "wr${r - 1}m${2 * i}", "wr${r - 1}m${2 * i + 1}", count + 1, fin, if (fin) finalBo else bestOf))
            }
        }
        return nodes
    }

    private fun doubleElim(n: Int, bo: Int, fin: Int): MutableList<BracketNode> {
        fun nd(id: String, r: Int, label: String, a: String, b: String, place: Int, isFinal: Boolean = false) = BracketNode(id, r, label, a, b, place, isFinal, if (isFinal) fin else bo)
        if (n <= 4) return mutableListOf(
            nd("u1", 0, "Upper Semifinal", "s1", "s4", 0), nd("u2", 0, "Upper Semifinal", "s2", "s3", 0), nd("uf", 1, "Upper Final", "wu1", "wu2", 0),
            nd("l1", 1, "Lower Round 1", "lu1", "lu2", 4), nd("lf", 2, "Lower Final", "wl1", "luf", 3), nd("gf", 3, "Grand Final", "wuf", "wlf", 2, true),
        )
        return mutableListOf(
            nd("u1", 0, "Upper Quarterfinal", "s1", "s8", 0), nd("u2", 0, "Upper Quarterfinal", "s4", "s5", 0), nd("u3", 0, "Upper Quarterfinal", "s2", "s7", 0), nd("u4", 0, "Upper Quarterfinal", "s3", "s6", 0),
            nd("u5", 1, "Upper Semifinal", "wu1", "wu2", 0), nd("u6", 1, "Upper Semifinal", "wu3", "wu4", 0), nd("l1", 1, "Lower Round 1", "lu1", "lu2", 7), nd("l2", 1, "Lower Round 1", "lu3", "lu4", 7),
            nd("u7", 2, "Upper Final", "wu5", "wu6", 0), nd("l3", 2, "Lower Round 2", "wl1", "lu6", 5), nd("l4", 2, "Lower Round 2", "wl2", "lu5", 5),
            nd("l5", 3, "Lower Round 3", "wl3", "wl4", 4), nd("l6", 4, "Lower Final", "wl5", "lu7", 3), nd("gf", 5, "Grand Final", "wu7", "wl6", 2, true),
        )
    }

    /** -2 = not decided yet, -1 = bye. */
    private fun resolve(stage: Stage, src: String): Int {
        if (src[0] == 's') return stage.teamIds.getOrNull(src.substring(1).toInt() - 1) ?: -1
        val ref = stage.bracket.firstOrNull { it.id == src.substring(1) } ?: return -1
        val decided = ref.winner != -2 || ref.loser != -2 || (ref.teamA == -1 && ref.teamB == -1)
        if (!decided) return -2
        return if (src[0] == 'w') ref.winner.coerceAtLeast(-1) else ref.loser.coerceAtLeast(-1)
    }

    fun progressBracket(w: World, c: Competition, si: Int) {
        val stage = c.stages[si]
        var changed = true
        while (changed) {
            changed = false
            for (nd in stage.bracket) {
                if (nd.fixtureId >= 0 || nd.winner != -2 || nd.loser != -2 || (nd.teamA == -1 && nd.teamB == -1)) continue
                val a = resolve(stage, nd.a)
                val b = resolve(stage, nd.b)
                if (a == -2 || b == -2) continue
                nd.teamA = a
                nd.teamB = b
                if (a == -1 || b == -1) {
                    nd.winner = if (a >= 0) a else if (b >= 0) b else -1
                    nd.loser = -1
                    changed = true
                    continue
                }
                val day = maxOf(stage.startDay + nd.round * stage.daysBetween, w.day + 1)
                nd.fixtureId = addFixture(w, c, si, day, a, b, nd.bestOf, nd.round, nodeId = nd.id).id
                changed = true
            }
        }
    }

    /** A pairing of the whole pool without a rematch (strongest first, lowest-ranked opponent first), or null if there is none. */
    private fun noRematch(pool: List<Int>, played: Set<Long>, key: (Int, Int) -> Long, depth: Int = 0): List<Pair<Int, Int>>? {
        if (pool.isEmpty()) return emptyList()
        if (pool.size % 2 == 1 || depth > 12) return null
        val a = pool[0]
        for (j in pool.size - 1 downTo 1) {
            if (key(a, pool[j]) in played) continue
            val rest = pool.filterIndexed { i, _ -> i != 0 && i != j }
            val sub = noRematch(rest, played, key, depth + 1) ?: continue
            return listOf(a to pool[j]) + sub
        }
        return null
    }

    private fun swissRound(w: World, c: Competition, si: Int): Boolean {
        val stage = c.stages[si]
        val active = stage.standings.filter { it.w < stage.swissWins && it.l < stage.swissLosses }
        if (active.size < 2) return false
        val played = HashSet<Long>()
        fun key(a: Int, b: Int) = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
        for (fid in stage.fixtureIds) w.fixtures[fid].let { played.add(key(it.a, it.b)) }
        val pools = active.groupBy { it.w * 100 - it.l }.toSortedMap(compareByDescending { it })
        val pairs = ArrayList<Pair<Int, Int>>()
        var carry: List<Standing> = emptyList()
        for ((_, rows) in pools) {
            val order = (carry + rows).sortedWith(compareByDescending<Standing> { it.gw - it.gl }.thenBy { stage.teamIds.indexOf(it.teamId) }).map { it.teamId }
            carry = emptyList()
            // The usual draw: the strongest against the lowest-ranked team it has not met.
            val pool = order.toMutableList()
            val greedy = ArrayList<Pair<Int, Int>>()
            while (pool.size >= 2) {
                val a = pool.removeAt(0)
                var j = pool.size - 1
                while (j > 0 && key(a, pool[j]) in played) j--
                greedy.add(a to pool.removeAt(j))
            }
            var left = pool.firstOrNull()
            var chosen: List<Pair<Int, Int>> = greedy
            // That draw forced a rematch: look for one without (the team left over may change); a rematch stays only when none exists.
            if (greedy.any { key(it.first, it.second) in played }) {
                val alt = if (order.size % 2 == 0) noRematch(order, played, ::key)?.let { it to null }
                else order.indices.reversed().firstNotNullOfOrNull { k -> noRematch(order.filterIndexed { i, _ -> i != k }, played, ::key)?.let { it to order[k] } }
                if (alt != null) { chosen = alt.first; left = alt.second }
            }
            pairs.addAll(chosen)
            if (left != null) carry = stage.standings.filter { it.teamId == left }
        }
        if (pairs.isEmpty()) return false
        val day = maxOf(stage.startDay + stage.swissRound * stage.daysBetween, w.day + 1)
        for ((a, b) in pairs) addFixture(w, c, si, day, a, b, stage.bestOf, stage.swissRound)
        stage.swissRound++
        return true
    }

    fun start(w: World, c: Competition, si: Int, teamIds: List<Int>) {
        val stage = c.stages[si]
        stage.teamIds = teamIds.toMutableList()
        stage.status = 1
        stage.startDay = maxOf(stage.startDay, w.day + 1)
        stage.standings = teamIds.map { Standing(it) }.toMutableList()
        stage.fixtureIds = ArrayList()
        stage.bracket = ArrayList()
        stage.swissRound = 0
        val fin = if (c.finalBo > 0) c.finalBo else Catalogue.get(c.titleId).formats.third.n
        when (stage.type) {
            StageType.RR, StageType.DRR -> {
                var rounds = roundRobin(stage.teamIds)
                if (stage.type == StageType.DRR) rounds = rounds + rounds.map { r -> r.map { (a, b) -> b to a } }
                val days = matchDays(stage.startDay, rounds.size, stage.dows)
                rounds.forEachIndexed { ri, pairs -> for ((a, b) in pairs) addFixture(w, c, si, days[ri], a, b, stage.bestOf, ri) }
            }
            StageType.GROUPS -> {
                val g = maxOf(1, stage.groups)
                val groups = List(g) { ArrayList<Int>() }
                stage.teamIds.forEachIndexed { i, tid -> val cycle = i / g; groups[if (cycle % 2 == 0) i % g else g - 1 - i % g].add(tid) }
                stage.standings = groups.flatMapIndexed { gi, ts -> ts.map { Standing(it, group = gi) } }.toMutableList()
                groups.forEachIndexed { gi, ts ->
                    val rounds = roundRobin(ts)
                    val days = matchDays(stage.startDay, rounds.size, stage.dows)
                    rounds.forEachIndexed { ri, pairs -> for ((a, b) in pairs) addFixture(w, c, si, days[ri], a, b, stage.bestOf, ri, gi) }
                }
            }
            StageType.SWISS -> swissRound(w, c, si)
            StageType.SINGLE_ELIM -> { stage.bracket = singleElim(teamIds.size, stage.bestOf, fin); progressBracket(w, c, si) }
            StageType.DOUBLE_ELIM -> { stage.bracket = doubleElim(teamIds.size, stage.bestOf, fin); progressBracket(w, c, si) }
        }
    }

    private val elimTypes = setOf(StageType.SINGLE_ELIM, StageType.DOUBLE_ELIM)

    fun sortStandings(w: World, rows: List<Standing>): List<Standing> = rows.sortedWith(
        compareByDescending<Standing> { it.w }.thenByDescending { it.gw - it.gl }.thenByDescending { it.gw }.thenBy { it.l }.thenByDescending { w.teams[it.teamId].elo }.thenBy { it.teamId },
    )

    fun apply(w: World, c: Competition, f: Fixture) {
        val stage = c.stages[f.stageIdx]
        if (stage.type !in elimTypes) {
            val ra = stage.standings.firstOrNull { it.teamId == f.a }
            val rb = stage.standings.firstOrNull { it.teamId == f.b }
            if (ra != null && rb != null) {
                ra.gw += f.scoreA; ra.gl += f.scoreB; rb.gw += f.scoreB; rb.gl += f.scoreA
                if (f.winner == f.a) { ra.w++; rb.l++ } else { rb.w++; ra.l++ }
            }
        }
        if (f.nodeId.isNotEmpty()) stage.bracket.firstOrNull { it.id == f.nodeId }?.let { it.winner = f.winner; it.loser = if (f.winner == f.a) f.b else f.a }
    }

    fun stageDone(w: World, c: Competition, si: Int): Boolean {
        val stage = c.stages[si]
        val pending = stage.fixtureIds.any { !w.fixtures[it].done }
        if (stage.type in elimTypes) {
            progressBracket(w, c, si)
            val fin = stage.bracket.firstOrNull { it.isFinal } ?: return false
            return (fin.winner != -2 || (fin.teamA == -1 && fin.teamB == -1)) && stage.fixtureIds.all { w.fixtures[it].done }
        }
        if (pending) return false
        if (stage.type == StageType.SWISS) return !swissRound(w, c, si)
        return true
    }

    fun placements(w: World, stage: Stage): List<Int> {
        if (stage.type in elimTypes) {
            val place = HashMap<Int, Int>()
            for (nd in stage.bracket) {
                if (nd.loser >= 0) place[nd.loser] = if (nd.elimPlace > 0) nd.elimPlace else 99
                if (nd.isFinal && nd.winner >= 0) place[nd.winner] = 1
            }
            return stage.teamIds.sortedWith(compareBy<Int> { place[it] ?: 99 }.thenBy { stage.teamIds.indexOf(it) })
        }
        if (stage.type == StageType.GROUPS) {
            val groups = stage.standings.groupBy { it.group }.values.map { sortStandings(w, it) }
            val out = ArrayList<Int>()
            // Across groups (which can differ in size): win rate, then game difference per series, then seed strength.
            val across = compareByDescending<dev.uem.em.model.Standing> { it.w.toDouble() / maxOf(1, it.w + it.l) }
                .thenByDescending { (it.gw - it.gl).toDouble() / maxOf(1, it.w + it.l) }.thenByDescending { w.teams[it.teamId].elo }.thenBy { it.teamId }
            for (rank in 0 until groups.maxOf { it.size }) out.addAll(groups.mapNotNull { it.getOrNull(rank) }.sortedWith(across).map { it.teamId })
            return out
        }
        if (stage.type == StageType.SWISS) return stage.standings.sortedWith(compareByDescending<Standing> { it.w }.thenBy { it.l }.thenByDescending { it.gw - it.gl }.thenBy { stage.teamIds.indexOf(it.teamId) }).map { it.teamId }
        return sortStandings(w, stage.standings).map { it.teamId }
    }
}

/** Starting competitions when their entrants are known, and moving them stage to stage. */
object Progress {
    fun circuitPoints(w: World, season: Int, titleId: String): Map<Int, Double> {
        val pts = HashMap<Int, Double>()
        for (c in w.comps) {
            if (c.season != season || c.titleId != titleId || !c.done || c.circuitPoints.isEmpty()) continue
            c.placements.forEachIndexed { i, tid -> pts[tid] = (pts[tid] ?: 0.0) + (c.circuitPoints.getOrNull(i) ?: 0) * Season.splitWeight(c.split) }
        }
        return pts
    }

    /**
     * Who enters a competition and why: each entry rule in order (a team already in is skipped and the
     * place passes down the same source), every place recorded with its route, source and placing.
     * Null while a source competition has not finished. The same rules apply to the user's teams and
     * the AI's; inactive teams are recorded as withdrawn and their place passes on.
     */
    internal fun entrants(w: World, c: Competition): List<QualEntry>? {
        val out = ArrayList<QualEntry>()
        val ids = HashSet<Int>()
        fun has(id: Int) = id in ids
        fun add(id: Int, rule: String, how: String, src: Int = -1, place: Int = -1, note: String = ""): Boolean {
            if (id in ids) return false
            if (!w.teams[id].active) { out.add(QualEntry(id, rule, src, place, how, "team no longer active — place passed on", "withdrawn", w.day)); return false }
            // A team that declined this event's invitation does not come in by another route either (e.g. its open qualifier).
            if (w.invites.any { it.compId == c.id && it.teamId == id && it.status == "declined" }) {
                if (out.none { it.teamId == id && it.status == "declined" } && c.entries.none { it.teamId == id && it.status == "declined" }) out.add(QualEntry(id, rule, src, place, how, "declined the invitation — place passed on", "declined", w.day))
                return false
            }
            ids.add(id); out.add(QualEntry(id, rule, src, place, how, note, "in", w.day)); return true
        }
        for (rule in c.entrants) {
            val p = rule.split(':')
            when (p[0]) {
                "league" -> { val l = w.leagues[p[1].toInt()]; l.teamIds.forEach { add(it, rule, "member of ${l.short}") } }
                "ids" -> p[1].split(',').mapNotNull { it.toIntOrNull() }.filter { it in w.teams.indices }.forEach { add(it, rule, "invited") }
                "nations" -> dev.uem.em.mgmt.Extras.nationalTeams(w, p[1], p[2].toInt(), c.id).forEach { add(it, rule, "one of the strongest nations") }
                // Every nation of a continent (Nations Cup qualifier), squads called up now.
                "continent" -> dev.uem.em.mgmt.Extras.continentTeams(w, p[1], p.drop(2).joinToString(":"), c.id).forEach { add(it, rule, "nation of ${p.drop(2).joinToString(":")}") }
                // The best of a finished qualifier (national squads are called up again on this season's form).
                "qual" -> {
                    val src = w.comps[p[1].toInt()]
                    if (!src.done) return null
                    var taken = 0
                    for ((i, tid) in src.placements.withIndex()) {
                        if (taken >= p[2].toInt()) break
                        if (has(tid)) continue
                        // Squads are normally named in the selection window; a nation not named yet is called up now.
                        if (w.teams[tid].nationalOf.isNotEmpty() && w.teams[tid].squadFor != c.id) {
                            val tm = dev.uem.em.mgmt.Extras.callUp(w, w.teams[tid], compId = c.id)
                            val (inn, outt) = dev.uem.em.mgmt.Extras.lastChanges
                            if (inn.isNotEmpty() || outt.isNotEmpty()) w.news.add(dev.uem.em.model.News(w.day, "${tm.name} squad for ${c.short}",
                                "Changes from the qualifier — in: ${inn.joinToString { w.players[it].handle }.ifEmpty { "none" }}; out: ${outt.joinToString { w.players[it].handle }.ifEmpty { "none" }}.", listOf(tm.id)))
                        }
                        if (add(tid, rule, if (src.defKey.endsWith(":playin")) "through the play-in" else "qualifier", src.id, i)) taken++
                    }
                }
                // Up to a number of nations: the strongest national teams not already in (a qualifier without enough nations).
                "fill" -> {
                    val need = p[2].toInt() - ids.size
                    if (need > 0) w.teams.filter { it.titleId == p[1] && it.nationalOf.isNotEmpty() && !has(it.id) }.sortedByDescending { it.elo }.take(need)
                        .forEach { dev.uem.em.mgmt.Extras.callUp(w, it, compId = c.id); add(it.id, rule, "filled an empty place (strongest remaining nation)") }
                }
                // The strongest tier-1 teams of a game not already in (by Elo).
                "elo" -> { val need = p[2].toInt(); w.teams.filter { it.titleId == p[1] && it.active && it.nationalOf.isEmpty() && w.leagues.getOrNull(it.leagueId)?.tier == 1 && !has(it.id) }.sortedByDescending { it.elo }.take(need).forEach { add(it.id, rule, "invited on strength (Elo)") } }
                // The last teams of a finished league split that are still in its league (promotion tournaments).
                "bottom" -> {
                    val src = w.comps[p[1].toInt()]
                    if (!src.done) return null
                    val n = src.placements.size
                    src.placements.withIndex().reversed().filter { (_, it) -> w.teams[it].active && w.teams[it].leagueId == src.leagueId && !has(it) }.take(p[2].toInt())
                        .forEach { (i, it) -> add(it, rule, "defending its place (bottom ${n - i} of ${src.short})", src.id, i) }
                }
                // Survival series: the k-th from the bottom of a split (still in its league) / the k-th best lower team that may go up.
                "bottomnth" -> {
                    val src = w.comps[p[1].toInt()]
                    if (!src.done) return null
                    src.placements.withIndex().reversed().filter { (_, it) -> w.teams[it].active && w.teams[it].leagueId == src.leagueId }.getOrNull(p[2].toInt() - 1)
                        ?.let { (i, it) -> add(it, rule, "survival series (${dev.uem.em.core.ordinal(p[2].toInt())} from the bottom of ${src.short})", src.id, i) }
                }
                "promonth" -> {
                    val src = w.comps[p[1].toInt()]
                    if (!src.done) return null
                    src.placements.withIndex().filter { (_, it) -> w.teams[it].active && w.teams[it].parentId < 0 && w.teams[it].leagueId == src.leagueId }.getOrNull(p[2].toInt() - 1)
                        ?.let { (i, it) -> add(it, rule, "survival series challenger", src.id, i) }
                }
                // The best teams of a finished lower-league split that may go up: no academies (their parent's league).
                "promo" -> {
                    val src = w.comps[p[1].toInt()]
                    if (!src.done) return null
                    src.placements.withIndex().filter { (_, it) -> w.teams[it].active && w.teams[it].parentId < 0 && w.teams[it].leagueId == src.leagueId && !has(it) }.take(p[2].toInt())
                        .forEach { (i, it) -> add(it, rule, "promotion candidate", src.id, i) }
                }
                "top" -> {
                    val src = w.comps[p[1].toInt()]
                    if (!src.done) return null
                    // Optional 4th part: places already taken by the main event (a play-in starts below them).
                    var skip = p.getOrNull(3)?.toIntOrNull() ?: 0
                    var taken = 0
                    var passed = ""
                    for ((i, tid) in src.placements.withIndex()) {
                        if (taken >= p[2].toInt()) break
                        if (has(tid)) { passed = "${w.teams[tid].short} already qualified — place passed down"; continue }
                        if (skip > 0) { if (w.teams[tid].active) skip--; continue }
                        if (add(tid, rule, if (p.size > 3) "placing (play-in seed)" else "placing", src.id, i, passed)) { taken++; passed = "" }
                    }
                }
                // A league's play-in seeds for the world championship: its qualifiers below the main-event seeds.
                "pseed" -> {
                    val main = w.comps[p[1].toInt()]
                    val league = w.leagues[p[2].toInt()]
                    val list = leagueOrder(w, main, league, "circuit:${league.id}:${p[4]}:0:99:${p.getOrElse(5) { "placings" }}") ?: return null
                    for (e in list.drop(p[3].toInt())) add(e.teamId, rule, e.how + " — play-in seed", e.src, e.place, e.note)
                }
                // Regional finals: the next teams on circuit points after the league's direct qualifiers.
                "lcq" -> {
                    val main = w.comps[p[1].toInt()]
                    val league = w.leagues[p[2].toInt()]
                    val direct = leagueOrder(w, main, league, "circuit:${league.id}:${p[4]}:0:99:${p.getOrElse(5) { "placings" }}", directOnly = true) ?: return null
                    val winner = main.entrants.firstOrNull { it.startsWith("msiwin:") }?.let { w.comps[it.split(':')[1].toInt()].placements.firstOrNull() }
                    val pts = circuitPoints(w, c.season, c.titleId)
                    league.teamIds.filter { t -> w.teams[t].active && t != winner && direct.none { it.teamId == t } }
                        .sortedWith(compareByDescending<Int> { pts[it] ?: 0.0 }.thenByDescending { w.teams[it].elo }).take(p[3].toInt())
                        .forEach { add(it, rule, "circuit points (next after the direct places)", note = "${(pts[it] ?: 0.0).toInt()} points in ${league.short}") }
                }
                // All-star showdown: a league's representative squad (its best players by position), named now.
                "allstar" -> {
                    val league = w.leagues[p[1].toInt()]
                    add(AllStars.select(w, league, c.id).id, rule, "the best of ${league.short} by position (this season's form)")
                }
                // Rivalry cup: the two leagues' top teams on circuit points, alternately (so the bracket pairs them across).
                "rival" -> {
                    val la = w.leagues[p[1].toInt()]; val lb = w.leagues[p[2].toInt()]
                    val pts = circuitPoints(w, c.season, c.titleId)
                    fun top(l: League) = l.teamIds.filter { w.teams[it].active && w.teams[it].parentId < 0 }.sortedWith(compareByDescending<Int> { pts[it] ?: 0.0 }.thenByDescending { w.teams[it].elo }).take(p[3].toInt())
                    val ta = top(la); val tb = top(lb)
                    for (i in 0 until maxOf(ta.size, tb.size)) {
                        ta.getOrNull(i)?.let { add(it, rule, "${la.short} — ${dev.uem.em.core.ordinal(i + 1)} on circuit points", note = "L${la.id}") }
                        tb.getOrNull(i)?.let { add(it, rule, "${lb.short} — ${dev.uem.em.core.ordinal(i + 1)} on circuit points", note = "L${lb.id}") }
                    }
                }
                // Best of the rest: a league's best teams on circuit points that are not at the world championship.
                "rest" -> {
                    val main = w.comps[p[1].toInt()]
                    val league = w.leagues[p[2].toInt()]
                    val pis = w.comps.filter { it.season == main.season && it.defKey == main.defKey + ":playin" }
                    if (main.status == 0 || pis.any { it.status == 0 }) return null
                    val busy = HashSet<Int>(main.teamIds)
                    for (pi in pis) busy.addAll(pi.teamIds)
                    // Teams defending their place in a promotion tournament stay home.
                    for (pc in w.comps) if (pc.season == c.season && pc.promoLeague == league.id) for (r in pc.entrants.filter { it.startsWith("bottom:") }) {
                        val src = w.comps[r.split(':')[1].toInt()]
                        busy.addAll(src.placements.filter { w.teams[it].leagueId == league.id }.takeLast(r.split(':')[2].toInt()))
                    }
                    val pts = circuitPoints(w, c.season, c.titleId)
                    league.teamIds.filter { w.teams[it].active && it !in busy && !has(it) && w.teams[it].parentId < 0 }
                        .sortedWith(compareByDescending<Int> { pts[it] ?: 0.0 }.thenByDescending { w.teams[it].elo }).take(p[3].toInt())
                        .forEach { add(it, rule, "circuit points (missed ${main.short})", note = "${(pts[it] ?: 0.0).toInt()} points in ${league.short}") }
                }
                // A league's places at the world championship (see leagueOrder); with a play-in, the main event takes the
                // first [from, from + count) of them and the play-in the rest.
                "circuit" -> {
                    val league = w.leagues[p[1].toInt()]
                    val list = leagueOrder(w, c, league, rule) ?: return null
                    val from = p.getOrNull(3)?.toIntOrNull() ?: 0
                    val count = p.getOrNull(4)?.toIntOrNull() ?: Int.MAX_VALUE
                    for (e in list.drop(from).take(count)) add(e.teamId, rule, e.how, e.src, e.place, e.note)
                }
                // Championship points earned in a territory's events this season (VCT 2027: top two per territory).
                "points" -> {
                    val league = w.leagues[p[1].removePrefix("L").toInt()]
                    val own = w.comps.filter { it.season == c.season && it.leagueId == league.id && it.startDay < c.startDay }
                    if (own.any { !it.done }) return null
                    val pts = circuitPoints(w, c.season, c.titleId)
                    var chosen = 0
                    for (t in w.teams.filter { it.titleId == c.titleId && it.active && Ecosystem.inRegion(w, it, league) && (pts[it.id] ?: 0.0) > 0 }.sortedByDescending { pts[it.id] ?: 0.0 }) {
                        if (chosen >= p[2].toInt()) break
                        if (add(t.id, rule, "championship points", note = "${(pts[t.id] ?: 0.0).toInt()} points (${league.short} territory)")) chosen++
                    }
                }
                // Ranking invitations accepted by the start (see Ecosystem.daily).
                "invite" -> {
                    if (w.invites.none { it.compId == c.id && it.route == rule }) Ecosystem.issue(w, c, rule)
                    for (inv in w.invites.filter { it.compId == c.id && it.route == rule }) {
                        if (inv.status == "pending") Ecosystem.respond(w, inv, true, "accepted by the staff at the deadline")
                        if (inv.status == "accepted") add(inv.teamId, rule, "invitation (regional standings)", place = inv.rank - 1, note = "${dev.uem.em.core.ordinal(inv.rank)} in the standings")
                    }
                }
                // Open qualifier: every team of the region without a league place in it or an invitation to the main event (strongest first, capped).
                "open" -> {
                    val league = Ecosystem.leagueOf(w, p[2])
                    val cap = p[3].toInt()
                    val main = p.getOrNull(4)?.toIntOrNull()
                    // "all": league teams play it too (CS2, Dota 2); otherwise the league's teams are seeded past it (VALORANT partners).
                    val all = p.getOrNull(5) == "all"
                    val invited = if (main != null) w.invites.filter { it.compId == main && it.status != "declined" }.map { it.teamId }.toSet() else emptySet()
                    // One regional open qualifier per event: a team already entered in another region's qualifier for the same event stays there.
                    val elsewhere = w.comps.filter { o -> o.id != c.id && o.season == c.season && o.titleId == c.titleId && o.status == 1 && o.entrants.any { r -> r.startsWith("open:") && r.split(':').getOrNull(4) == p.getOrNull(4) } }.flatMap { it.teamIds }.toHashSet()
                    // Teams still playing another event over these days (a league's playoffs) do not enter: no team plays twice on a day.
                    for (o in w.comps) if (o.id != c.id && o.status == 1 && o.titleId == c.titleId && o.circuit.isBlank() &&
                        maxOf(dev.uem.em.mgmt.Rankings.endDay(w, o), (o.stages.lastOrNull()?.startDay ?: o.startDay) + 10) >= c.startDay) elsewhere.addAll(o.teamIds)
                    w.teams.filter { it.titleId == p[1] && it.active && it.nationalOf.isEmpty() && it.parentId < 0 && (it.kind.isEmpty() || it.kind == "amateur") && it.id !in invited && it.id !in elsewhere && (league == null || all || it.leagueId != league.id) && Ecosystem.inRegion(w, it, league) && it.roster.size >= Catalogue.get(it.titleId).teamSize }
                        .sortedByDescending { it.elo }.take(cap).forEach { add(it.id, rule, "open qualifier entry") }
                }
                // Development circuits: a region's amateur clubs (strongest first), the best on amateur points, or every team of a kind.
                "amateur", "amateurpts", "kind" -> {
                    val league = Ecosystem.leagueOf(w, p[2])
                    val kind = if (p[0] == "kind") p[3] else "amateur"
                    val full = dev.uem.em.mgmt.Circuits.teamsOf(w, p[1], league, kind).filter { tm -> tm.roster.count { dev.uem.em.mgmt.Circuits.eligible(w, c, w.players[it]) == null && !w.players[it].retired } >= Catalogue.get(tm.titleId).teamSize }
                    val pts = if (p[0] == "amateurpts") dev.uem.em.mgmt.Circuits.amateurPoints(w, c.season, p[1]) else emptyMap()
                    if (p[0] == "amateurpts" && w.comps.any { it.season == c.season && it.titleId == p[1] && it.circuit == "amateur" && it.startDay < c.startDay && !it.done && it.entrants.firstOrNull()?.split(':')?.getOrNull(2) == p[2] }) return null
                    val order = full.sortedWith(compareByDescending<Team> { pts[it.id] ?: 0.0 }.thenByDescending { it.elo })
                    val cap = if (p[0] == "kind") Int.MAX_VALUE else p[3].toInt()
                    order.take(cap).forEach { tm -> add(tm.id, rule, when (p[0]) { "amateurpts" -> "amateur points"; "kind" -> "${kind} team of the region"; else -> "amateur club of the region" },
                        note = if (p[0] == "amateurpts") "${(pts[tm.id] ?: 0.0).toInt()} points" else "") }
                }
                // The winner of an earlier international qualifies directly (LoL 2026: the MSI winner goes to Worlds).
                "msiwin" -> {
                    val src = w.comps[p[1].toInt()]
                    if (!src.done) return null
                    src.placements.firstOrNull()?.let { add(it, rule, "won ${src.short}", src.id, 0) }
                }
                // An extra place for the league of an earlier international's runner-up, filled on circuit points.
                "msiseed" -> {
                    val src = w.comps[p[1].toInt()]
                    if (!src.done) return null
                    val runner = src.placements.getOrNull(1) ?: continue
                    val league = w.leagues.getOrNull(w.teams[runner].leagueId) ?: continue
                    // The league's own rule already counted this seed (its playoff order fills it), or the play-in's does.
                    if (c.entrants.any { it.startsWith("circuit:${league.id}:") } || w.comps.any { it.season == c.season && it.defKey == c.defKey + ":playin" }) continue
                    val pts = circuitPoints(w, c.season, c.titleId)
                    league.teamIds.filter { !has(it) }.sortedWith(compareByDescending<Int> { pts[it] ?: 0.0 }.thenByDescending { w.teams[it].elo }).firstOrNull()
                        ?.let { add(it, rule, "extra ${league.short} seed (${src.short} runner-up region)", note = "${(pts[it] ?: 0.0).toInt()} circuit points") }
                }
            }
        }
        if (c.tier == CompTier.INTERNATIONAL || c.tier == CompTier.WORLDS) {
            // Seeding by strength; the ledger keeps the order it was earned in.
            val order = ids.sortedByDescending { w.teams[it].elo }
            return out.filter { it.status != "in" } + order.map { id -> out.first { it.teamId == id && it.status == "in" } }
        }
        return out
    }

    /**
     * A league's world-championship qualifiers in seed order ([rule] = "circuit:<league>:<places>[:from:count:mode]"),
     * or null while a source is still running. Places come from the last split's playoffs (upsets count): one place
     * the champion, two the finalists, three the finalists then one more, four the semi-finalists, more the
     * semi-finalists then the rest; in "points" mode the same numbers go to the best on circuit points instead. The
     * places beyond those go to the league's regional finals when it plays them, otherwise on circuit points. A
     * place taken by a team qualified another way (the MSI winner) passes down; the region of the MSI runner-up
     * counts its extra seed in the total.
     */
    internal fun leagueOrder(w: World, main: Competition, league: League, rule: String, directOnly: Boolean = false): List<QualEntry>? {
        val p = rule.split(':')
        val splits = w.comps.filter { it.season == main.season && it.leagueId == league.id }
        if (splits.any { !it.done }) return null
        if (w.comps.any { it.season == main.season && it.titleId == main.titleId && it.tier == CompTier.INTERNATIONAL && !it.done && it.startDay < main.startDay && !Season.isEncQualifier(it) && it.promoLeague < 0 && !it.defKey.startsWith(main.defKey + ":") }) return null
        val pts = circuitPoints(w, main.season, main.titleId)
        val last = splits.maxByOrNull { it.split }
        val taken = HashSet<Int>()
        main.entrants.firstOrNull { it.startsWith("msiwin:") }?.let { r -> val src = w.comps[r.split(':')[1].toInt()]; if (!src.done) return null; src.placements.firstOrNull()?.let { taken.add(it) } }
        val extra = main.entrants.firstOrNull { it.startsWith("msiseed:") }?.let { r -> w.comps.getOrNull(r.split(':')[1].toInt()) }
            ?.placements?.getOrNull(1)?.let { w.teams[it].leagueId == league.id } == true
        val places = p[2].toInt() + (if (extra) 1 else 0)
        val byPoints = p.getOrNull(5) == "points"
        val direct = Formats.direct(places)
        val label = when (direct) { 1 -> "champion"; 2 -> "finalist"; else -> "semi-finalist" }
        val out = ArrayList<QualEntry>()
        fun inOut(id: Int) = out.any { it.teamId == id }
        val pointsOrder = league.teamIds.sortedWith(compareByDescending<Int> { pts[it] ?: 0.0 }.thenByDescending { w.teams[it].elo })
        var passed = ""
        if (!byPoints) {
            val order = last?.placements?.withIndex()?.filter { (_, it) -> w.teams[it].leagueId == league.id }.orEmpty()
            for ((i, tid) in order) {
                if (out.size >= direct) break
                if (tid in taken) { passed = "${w.teams[tid].short} already qualified — place passed down"; continue }
                if (!w.teams[tid].active) continue
                val what = if (i == 0) "${league.short} champion" else if (i == 1) "${league.short} finalist" else "${league.short} ${dev.uem.em.core.ordinal(i + 1)} in the playoffs"
                out.add(QualEntry(tid, rule, last!!.id, i, "$what (last split, $label places)", passed, "in", w.day)); passed = ""
            }
        } else for ((i, tid) in pointsOrder.withIndex()) {
            if (out.size >= direct) break
            if (tid in taken) { passed = "${w.teams[tid].short} already qualified — place passed down"; continue }
            if (!w.teams[tid].active) continue
            out.add(QualEntry(tid, rule, -1, -1, "${dev.uem.em.core.ordinal(i + 1)} on circuit points in ${league.short}", "${(pts[tid] ?: 0.0).toInt()} points" + if (passed.isNotEmpty()) " — $passed" else "", "in", w.day)); passed = ""
        }
        if (directOnly) return out
        val rf = w.comps.firstOrNull { it.season == main.season && it.defKey == "${main.titleId}:rf:L${league.id}" && !it.name.endsWith("(cancelled)") }
        if (rf != null && out.size < places) {
            if (!rf.done) return null
            for ((i, tid) in rf.placements.withIndex()) {
                if (out.size >= places) break
                if (tid in taken || inOut(tid) || !w.teams[tid].active) continue
                out.add(QualEntry(tid, rule, rf.id, i, if (i == 0) "won ${rf.short}" else "${dev.uem.em.core.ordinal(i + 1)} in ${rf.short}", "", "in", w.day))
            }
        }
        for (tid in pointsOrder) {
            if (out.size >= places) break
            if (tid in taken || inOut(tid) || !w.teams[tid].active) continue
            out.add(QualEntry(tid, rule, -1, -1, "circuit points", "${(pts[tid] ?: 0.0).toInt()} points in ${league.short}", "in", w.day))
        }
        return out
    }

    fun startDue(w: World, day: Int) {
        for (c in w.comps) {
            if (c.status != 0 || c.startDay > day + 3) continue
            val entries = entrants(w, c) ?: continue
            // The ledger is written once, when the edition starts (declined invitations recorded earlier stay).
            c.entries.addAll(entries)
            val teams = entries.filter { it.status == "in" }.map { it.teamId }
            if (teams.size < 2) {
                c.status = 2
                c.placements = teams.toMutableList()
                continue
            }
            c.teamIds = teams.toMutableList()
            c.status = 1
            dev.uem.em.mgmt.Supporters.register(w, c)
            c.current = 0
            Stages.start(w, c, 0, teams)
            settle(w, c, 0)
        }
    }

    /**
     * A stage with nothing to play (a group of one, a bracket of byes) is complete as soon as it starts; without this
     * it would wait for a series that never comes (seen with an amateur championship of two clubs in two groups).
     */
    fun settle(w: World, c: Competition, si: Int) {
        var i = si
        var guard = 0
        while (guard++ < c.stages.size + 1) {
            val stage = c.stages.getOrNull(i) ?: return
            if (stage.status != 1 || stage.fixtureIds.any { !w.fixtures[it].done } || !Stages.stageDone(w, c, i)) return
            stage.status = 2
            stage.placements = Stages.placements(w, stage).toMutableList()
            val next = c.stages.getOrNull(i + 1)
            if (next == null) { finish(w, c); return }
            val n = if (stage.type == StageType.GROUPS) stage.advance * maxOf(1, stage.groups) else if (stage.advance > 0) stage.advance else stage.placements.size
            c.current = i + 1
            Stages.start(w, c, i + 1, stage.placements.take(minOf(n, stage.placements.size)))
            i++
        }
    }

    /** After a series: standings / bracket, then the next stage or the final placements. */
    fun onFixtureDone(w: World, f: Fixture) {
        val c = w.comps[f.compId]
        Stages.apply(w, c, f)
        val stage = c.stages[f.stageIdx]
        if (stage.status != 1) return
        if (!Stages.stageDone(w, c, f.stageIdx)) return
        stage.status = 2
        stage.placements = Stages.placements(w, stage).toMutableList()
        val next = c.stages.getOrNull(f.stageIdx + 1)
        if (next != null) {
            val n = if (stage.type == StageType.GROUPS) stage.advance * maxOf(1, stage.groups) else if (stage.advance > 0) stage.advance else stage.placements.size
            c.current = f.stageIdx + 1
            Stages.start(w, c, f.stageIdx + 1, stage.placements.take(minOf(n, stage.placements.size)))
            settle(w, c, f.stageIdx + 1)
            return
        }
        finish(w, c)
    }

    /**
     * Competition MVP, as event awards go: a player of a team that went deep (the top four of the final
     * standings, the top two in events with few teams), who played most of his team's games, by average
     * rating with a bonus for the placing — never a player whose team went out early.
     */
    fun mvpOf(c: Competition): Int {
        if (c.pstats.isEmpty()) return -1
        val order = c.placements
        val deep = if (order.size >= 6) 4 else 2
        val rank = HashMap<Int, Int>().also { m -> order.forEachIndexed { i, t -> m.putIfAbsent(t, i) } }
        val teamGp = HashMap<Int, Int>().also { m -> for (st in c.pstats.values) m[st.teamId] = maxOf(m[st.teamId] ?: 0, st.gp) }
        fun bonus(r: Int) = when (r) { 0 -> 0.12; 1 -> 0.07; 2, 3 -> 0.03; else -> 0.0 }
        val pool = c.pstats.entries.filter { (_, st) -> st.gp > 0 && (rank[st.teamId] ?: 99) < deep && st.gp * 10 >= (teamGp[st.teamId] ?: 0) * 6 }
            .ifEmpty { c.pstats.entries.filter { it.value.gp > 0 && (rank[it.value.teamId] ?: 99) < deep } }
            .ifEmpty { c.pstats.entries.filter { it.value.gp > 0 } }
        return pool.maxByOrNull { (_, st) -> st.r / st.gp + bonus(rank[st.teamId] ?: 99) }?.key ?: -1
    }

    fun finish(w: World, c: Competition) {
        if (c.done) return
        val order = ArrayList<Int>()
        for (i in c.stages.indices.reversed()) for (t in c.stages[i].placements) if (t !in order) order.add(t)
        for (t in c.teamIds) if (t !in order) order.add(t)
        c.placements = order
        c.status = 2
        order.firstOrNull()?.let { champ ->
            w.teams[champ].trophies.add(Trophy(c.season, c.id, c.name))
            if (c.tier != CompTier.CHALLENGER) w.news.add(dev.uem.em.model.News(w.day, "${w.teams[champ].name} win ${c.name}", "${w.teams[champ].name} are champions of ${c.name}.", listOf(champ)))
        }
        c.mvp = mvpOf(c)
        dev.uem.em.mgmt.Economy.onCompetitionFinished(w, c)
        if (c.name.startsWith(Season.EWC)) Season.ewcClubPayout(w, c.season)
        dev.uem.em.mgmt.Career.onCompetitionFinished(w, c)
        dev.uem.em.mgmt.Extras.onCompetitionFinished(w, c)
        dev.uem.em.mgmt.Supporters.onCompetitionFinished(w, c)
        Season.afterFinish(w, c)
    }
}

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
import dev.uem.em.titles.Catalogue
import kotlin.math.pow

/**
 * What players want from a team, and how organisations build theirs.
 *
 * Every player weighs a few ambitions (summing to 100%): winning, money, loyalty, legacy, prestige,
 * a starting place, an uncontested position, development and playing in his home region. A team (with
 * a salary) is scored on each, and the weighted sum is how well it fits him. That fit sets his asking
 * salary, whether he signs or renews, where he goes as a free agent, and — at his current club — his
 * mood: an unhappy player asks to leave (his club lists him), a loyal one stays for years.
 *
 * Organisations have a strategy (win now, contend, develop, moneyball, brand, patient) that sets how
 * much they spend, whom they target and how likely they are to blow up an expensive roster that failed.
 * The same rules apply to the user's players and AI clubs.
 */
object Ambitions {
    val KEYS = listOf("winning", "money", "loyalty", "legacy", "prestige", "playtime", "ownrole", "growth", "home")
    val LABEL = mapOf(
        "winning" to "Winning", "money" to "Money", "loyalty" to "Loyalty", "legacy" to "Legacy", "prestige" to "Prestige",
        "playtime" to "Starting place", "ownrole" to "Own position", "growth" to "Development", "home" to "Home region",
    )
    val HINT = mapOf(
        "winning" to "a team that wins now", "money" to "the salary", "loyalty" to "staying with the same organisation",
        "legacy" to "an organisation with trophies to build a legacy at", "prestige" to "a big, high-reputation organisation in a top league",
        "playtime" to "a starting place, not the bench", "ownrole" to "no rival of the same level at the position", "growth" to "coaching and development",
        "home" to "playing in the home region",
    )
    val STRATEGIES = listOf("win-now", "contender", "development", "moneyball", "brand", "patient")
    val STRATEGY_LABEL = mapOf(
        "win-now" to "Win now (spends big on proven players, quick to rebuild)", "contender" to "Contender (steady upgrades)",
        "development" to "Development (young players and the academy)", "moneyball" to "Moneyball (undervalued players, sells high)",
        "brand" to "Brand (star names and reputation)", "patient" to "Patient (keeps a core together)",
    )

    // ------------------------------------------------------------------ setup

    fun of(w: World, p: Player): IntArray { if (p.ambitions.size != KEYS.size) p.ambitions = generate(w, p); return p.ambitions }
    fun weight(w: World, p: Player, key: String) = of(w, p)[KEYS.indexOf(key)]

    /** Ambitions from personality (ambitious, loyal, ego, money-motivated), age and chance; two or three usually dominate. */
    fun generate(w: World, p: Player): IntArray {
        val rng = Rng.derive(w.seed, "ambitions", p.id)
        val age = Dates.ageAt(p.birth, w.day)
        val x = p.pers
        fun px(i: Int) = x.getOrElse(i) { 50 }.toDouble()
        val raw = doubleArrayOf(
            10 + px(0) * 0.3 + rng.float(0.0, 22.0),
            5 + px(4) * 0.4 + rng.float(0.0, 22.0),
            1 + px(1) * 0.42 + rng.float(0.0, 16.0),
            2 + (px(0) + px(1)) * 0.07 + (if (age >= 25) 8.0 else 0.0) + rng.float(0.0, 14.0),
            3 + px(3) * 0.18 + rng.float(0.0, 18.0),
            7 + rng.float(0.0, 24.0),
            px(3) * 0.17 + rng.float(0.0, 12.0),
            (if (age <= 20) 22.0 else if (age <= 23) 12.0 else 2.0) + rng.float(0.0, 10.0),
            rng.float(0.0, 20.0),
        )
        return percent(raw.map { it.coerceAtLeast(0.5).pow(1.7) })
    }

    private fun percent(v: List<Double>): IntArray {
        val sum = v.sum()
        val exact = v.map { it / sum * 100 }
        val out = IntArray(v.size) { exact[it].toInt() }
        var left = 100 - out.sum()
        for (i in exact.indices.sortedByDescending { exact[it] - out[it] }) { if (left <= 0) break; out[i]++; left-- }
        return out
    }

    /** Players without ambitions (older saves, new players) get them; organisations get a strategy. */
    fun ensure(w: World) {
        for (p in w.players) if (p.ambitions.size != KEYS.size) p.ambitions = generate(w, p)
        for (o in w.orgs) if (o.strategy.isBlank()) o.strategy = strategyFor(w, o)
    }

    fun strategyFor(w: World, o: Org): String {
        val rng = Rng.derive(w.seed, "strategy", o.id)
        val rich = o.balance > 3_000_000 || o.rep >= 65
        val poor = o.balance < 600_000 && o.rep < 45
        return rng.weighted(STRATEGIES) { s ->
            when (s) {
                "win-now" -> if (rich) 30.0 else 6.0
                "contender" -> 26.0
                "development" -> if (poor) 26.0 else 14.0
                "moneyball" -> if (poor) 22.0 else 8.0
                "brand" -> if (o.rep >= 60) 18.0 else 4.0
                else -> 12.0
            }
        }
    }

    // ------------------------------------------------------------------ fit

    class Fit(val total: Double, val parts: DoubleArray)

    private fun tierOf(w: World, team: Team): Int = when {
        team.kind.isNotEmpty() -> 4
        team.parentId >= 0 -> 3
        else -> w.leagues.getOrNull(team.leagueId)?.tier ?: 3
    }

    /** Years the player has been with this organisation without a break (0 if not there). */
    fun tenureYears(w: World, p: Player, orgId: Int): Double {
        if (p.teamId < 0 || w.teams[p.teamId].orgId != orgId) return 0.0
        var since = p.joinedDay
        for (c in p.career.asReversed()) {
            val t = w.teams.getOrNull(c.teamId) ?: break
            if (t.orgId != orgId) break
            since = minOf(since, c.day)
        }
        return (w.day - since) / 365.0
    }

    private fun trophiesOf(w: World, orgId: Int, titleId: String): Double {
        var n = 0.0
        for (tid in w.orgs[orgId].teams) {
            val tm = w.teams[tid]
            if (tm.titleId != titleId) continue
            for (tr in tm.trophies) {
                if (tr.season < w.season - 5) continue
                n += when (w.comps.getOrNull(tr.compId)?.tier) { CompTier.WORLDS -> 3.0; CompTier.INTERNATIONAL -> 2.0; CompTier.LEAGUE -> 1.0; else -> 0.3 }
            }
        }
        return n
    }

    /** How well a team and salary fit what the player wants (0..100), with the score of each ambition. */
    fun fit(w: World, p: Player, team: Team, salary: Double): Fit {
        val a = of(w, p)
        val org = w.orgs[team.orgId]
        val tier = tierOf(w, team)
        val here = p.teamId == team.id
        val parts = DoubleArray(KEYS.size)
        parts[0] = clamp((team.elo - 1350.0) / 5.0, 0.0, 100.0) * (if (tier >= 3) 0.6 else 1.0)
        val exp = Economy.expectedSalary(p, team.titleId, team.region).coerceAtLeast(1.0)
        parts[1] = clamp((salary / exp - 0.75) / 0.55 * 100, 0.0, 100.0)
        val years = tenureYears(w, p, team.orgId)
        parts[2] = when {
            years > 0 -> clamp(55 + 12 * years, 0.0, 100.0)
            p.career.any { w.teams.getOrNull(it.teamId)?.orgId == team.orgId } -> 50.0
            else -> 20.0
        }
        parts[3] = clamp(15 + 9 * trophiesOf(w, team.orgId, team.titleId), 0.0, 100.0)
        parts[4] = clamp(org.rep * when (tier) { 1 -> 1.0; 2 -> 0.55; 3 -> 0.5; else -> 0.2 }, 0.0, 100.0)
        val starts = if (here) p.id in team.lineup else Market.wouldStart(w, team, p)
        parts[5] = if (starts) 100.0 else if (tier >= 3 && Dates.ageAt(p.birth, w.day) <= 20) 40.0 else 10.0
        val rivals = team.roster.count { id -> id != p.id && w.players[id].let { !it.retired && it.mainRole == p.mainRole && it.ca >= p.ca - 6 } }
        parts[6] = when (rivals) { 0 -> 100.0; 1 -> 35.0; else -> 10.0 }
        parts[7] = clamp(Economy.coachQuality(w, team) * 0.8 + (if (tier == 1) 15.0 else 0.0), 0.0, 100.0)
        val home = GameData.regionOf(p.nat)
        parts[8] = when {
            team.region == home || p.nat in GameData.expand(team.region) -> 100.0
            p.nat2.isNotEmpty() && (team.region == GameData.regionOf(p.nat2) || p.nat2 in GameData.expand(team.region)) -> 75.0
            else -> 20.0
        }
        var total = 0.0
        for (i in parts.indices) total += a[i] * parts[i] / 100
        return Fit(total, parts)
    }

    /** Fit without the salary (what the team itself offers him), on the same 0..100 scale. */
    private fun fitBesidesMoney(w: World, p: Player, team: Team): Double {
        val f = fit(w, p, team, 0.0)
        val a = of(w, p)
        val rest = 100 - a[1]
        if (rest <= 0) return 50.0
        return (f.total - a[1] * f.parts[1] / 100) * 100 / rest
    }

    /** Asking salary relative to the market rate: money-minded players ask more; a team that gives them what they want gets a discount. */
    fun askFactor(w: World, p: Player, team: Team): Double {
        val money = of(w, p)[1] / 100.0
        val other = fitBesidesMoney(w, p, team) / 100.0
        return clamp(0.82 + 0.5 * money + 0.3 * (1 - other), 0.8, 1.45)
    }

    /** Mood at the current club (0..100); null for a free agent. */
    fun mood(w: World, p: Player): Fit? {
        val team = w.teams.getOrNull(p.teamId) ?: return null
        return fit(w, p, team, p.salary)
    }

    /** What pleases and what bothers the player at his club, strongest first (weight × how far from what he wants). */
    fun reasons(w: World, p: Player): Pair<List<String>, List<String>> {
        val m = mood(w, p) ?: return emptyList<String>() to emptyList()
        val a = of(w, p)
        val good = KEYS.indices.filter { a[it] >= 8 && m.parts[it] >= 65 }.sortedByDescending { a[it] * m.parts[it] }.map { LABEL[KEYS[it]]!! }
        val bad = KEYS.indices.filter { a[it] >= 8 && m.parts[it] < 40 }.sortedByDescending { a[it] * (100 - m.parts[it]) }.map { LABEL[KEYS[it]]!! }
        return good to bad
    }

    /**
     * Whether the player agrees to these terms. A renewal needs him to be reasonably happy where he is;
     * a move needs the new team to suit him (a much better salary can make up for the rest).
     */
    fun refusal(w: World, p: Player, team: Team, salary: Double, renewal: Boolean): String? {
        if (!renewal) benchRefusal(w, p, team)?.let { return it }
        val f = fit(w, p, team, salary)
        val need = if (renewal) 38.0 - weight(w, p, "loyalty") * 0.25 else 30.0
        if (f.total >= need) return null
        val a = of(w, p)
        val worst = KEYS.indices.maxByOrNull { a[it] * (100 - f.parts[it]) } ?: 0
        return "${p.handle} is not interested${if (renewal) " in staying" else ""}: what matters most here is ${HINT[KEYS[worst]]} (${a[worst]}% of the weighting)."
    }

    /**
     * A good player does not join as a substitute behind a better player at his position: only a young
     * player joining to develop, a fringe player, or one for whom a starting place hardly matters does.
     */
    fun benchRefusal(w: World, p: Player, team: Team): String? {
        if (p.teamId == team.id || Market.wouldStart(w, team, p)) return null
        val age = Dates.ageAt(p.birth, w.day)
        val wantsToPlay = weight(w, p, "playtime") + weight(w, p, "ownrole") * 0.5
        if (age <= 20 && (team.parentId >= 0 || tierOf(w, team) >= 2 || weight(w, p, "growth") >= 20)) return null
        if (p.ca < 56 && wantsToPlay < 25) return null
        if (wantsToPlay < 8) return null
        val t = dev.uem.em.titles.Catalogue.get(team.titleId)
        val cur = team.lineup.getOrNull(t.lineup.indexOf(p.mainRole))?.let { w.players.getOrNull(it) }
        return "${p.handle} would not start at ${team.name}${cur?.let { " (${it.handle} plays the position)" } ?: ""} and wants to play."
    }

    /** An AI club's offer: does the free agent take it, or wait for a better fit? Patience runs out the longer he is unsigned. */
    fun acceptsOffer(w: World, p: Player, team: Team, salary: Double): Boolean {
        if (benchRefusal(w, p, team) != null) return false
        val f = fit(w, p, team, salary).total
        val unsigned = (w.day - (p.career.lastOrNull()?.day ?: w.day)).coerceAtLeast(0)
        return f >= 40 - unsigned / 4.0
    }

    // ------------------------------------------------------------------ weekly mood

    /** Weekly: unhappiness follows how well the club fits; an unhappy player asks to leave (AI clubs list him). */
    fun weekly(w: World) {
        for (p in w.players) {
            if (p.retired || p.teamId < 0 || p.loanFrom >= 0) continue
            val team = w.teams[p.teamId]
            if (team.kind.isNotEmpty() || team.nationalOf.isNotEmpty()) continue
            val m = fit(w, p, team, p.salary)
            val target = clamp((52 - m.total) * 2.2, 0.0, 100.0)
            p.unhappy = clamp(p.unhappy + (target - p.unhappy) * 0.2, 0.0, 100.0)
            if (p.unhappy > 50) p.morale = clamp(p.morale - (p.unhappy - 50) * 0.02, 5.0, 100.0)
            val settledIn = w.day - p.joinedDay > 120
            if (p.wantsOutDay == 0 && p.unhappy >= 55 && settledIn) {
                p.wantsOutDay = w.day
                val why = reasons(w, p).second.take(2).joinToString(" and ").lowercase().ifBlank { "his situation" }
                if (w.isUserTeam(team.id)) w.inbox.add(Message(w.day, p.handle, "${p.handle} wants to leave", "${p.handle} is unhappy at ${team.name} (${why}) and asks to be sold or released. List the player on the market, or change what is causing it."))
                else {
                    p.listed = true
                    if (p.rep >= 55) w.news.add(News(w.day, "${p.handle} wants out of ${team.name}", "${p.handle} has asked to leave ${team.name}, unhappy with ${why}.", listOf(team.id)))
                }
            } else if (p.wantsOutDay > 0 && p.unhappy < 30) {
                p.wantsOutDay = 0
                if (!w.isUserTeam(team.id)) p.listed = false
            }
        }
    }

    // ------------------------------------------------------------------ renewals and strategy

    fun strategy(w: World, team: Team): String = w.orgs[team.orgId].strategy.ifBlank { "contender" }
    fun rebuilding(w: World, team: Team) = team.rebuildUntil > w.day

    /** Share of the club's money it spends on a signing. */
    fun budgetShare(w: World, team: Team) = when (strategy(w, team)) { "win-now" -> 0.5; "brand" -> 0.45; "contender" -> 0.35; "patient" -> 0.3; else -> 0.25 } * (if (rebuilding(w, team)) 1.3 else 1.0)

    /** Daily chance an AI club looks to upgrade during a window. */
    fun marketChance(w: World, team: Team) = if (rebuilding(w, team)) 0.35 else when (strategy(w, team)) { "win-now" -> 1.0 / 12; "brand" -> 1.0 / 15; "contender" -> 1.0 / 20; "moneyball" -> 1.0 / 18; "development" -> 1.0 / 25; else -> 1.0 / 35 }

    /** How a club ranks transfer targets of a position. */
    fun targetScore(w: World, team: Team, p: Player): Double {
        val age = Dates.ageAt(p.birth, w.day)
        return when (strategy(w, team)) {
            "development" -> p.ca * 0.6 + p.pa * 0.5 - maxOf(0, age - 22) * 2.0
            "moneyball" -> p.ca - Economy.marketValue(w, p) / 40_000.0
            "brand" -> p.ca + p.rep * 0.15
            else -> p.ca
        }
    }

    /** Does the club want to keep an expiring player? */
    private fun clubWants(w: World, team: Team, p: Player): Boolean {
        val age = Dates.ageAt(p.birth, w.day)
        if (age >= 32) return false
        val same = team.roster.map { w.players[it] }.filter { it.id != p.id && !it.retired && it.mainRole == p.mainRole }
        val better = same.count { it.ca > p.ca + 3 }
        val starter = p.id in team.lineup
        if (rebuilding(w, team)) return age <= 22 && p.pa >= p.ca + 6
        return when (strategy(w, team)) {
            "development" -> age <= 24 || starter
            "win-now" -> starter && (age <= 28 || p.ca >= 70)
            "moneyball" -> starter && p.salary <= Economy.expectedSalary(p, team.titleId, team.region) * 1.2
            else -> starter || better == 0
        }
    }

    /**
     * End of season (AI clubs): an expiring player renews only if the club wants him and he is happy enough
     * to stay. Returns the new contract length in seasons, 0 when he leaves.
     */
    fun renewal(w: World, p: Player, team: Team, rng: Rng): Int {
        if (!clubWants(w, team, p)) return 0
        val ask = Market.asking(w, team, p)
        if (refusal(w, p, team, ask, true) != null) return 0
        val loyal = weight(w, p, "loyalty")
        // Loyal players sign long deals; players chasing money or a move keep their options open.
        val chance = clamp(0.35 + (fit(w, p, team, ask).total - 45) / 40 + loyal / 120.0, 0.05, 0.97)
        if (!rng.chance(chance)) return 0
        p.salary = ask
        return if (loyal >= 25) rng.int(2, 3) else if (weight(w, p, "money") >= 25) 1 else rng.int(1, 2)
    }

    /**
     * End of season: a club that paid for a contender and finished in the bottom half (or missed the world
     * championship with a top-three payroll) may blow up the roster — how likely depends on its strategy.
     * The roster is listed for sale and the club rebuilds through the next window.
     */
    fun seasonReview(w: World, rng: Rng) {
        val windowEnd = Dates.day(w.season + 1, 1, 12)
        for (league in w.leagues) {
            if (league.tier != 1) continue
            val teams = league.teamIds.map { w.teams[it] }.filter { it.active && !w.isUserTeam(it.id) && it.kind.isEmpty() }
            if (teams.size < 4) continue
            val pay = teams.associate { tm -> tm.id to tm.roster.sumOf { id -> w.players[id].takeIf { it.teamId == tm.id }?.salary ?: 0.0 } }
            val byPay = teams.sortedByDescending { pay[it.id] ?: 0.0 }
            val n = teams.size
            for ((pr, tm) in byPay.withIndex()) {
                val pos = tm.history.lastOrNull { it.season == w.season }?.position ?: continue
                if (pos <= 0) continue
                val worlds = w.comps.any { it.season == w.season && it.titleId == tm.titleId && it.tier == CompTier.WORLDS && tm.id in it.teamIds }
                val failed = (pr < n / 2 && pos > (n + 1) / 2) || (pr <= 2 && !worlds && pos > 2)
                if (!failed) continue
                val chance = when (strategy(w, tm)) { "win-now" -> 0.45; "brand" -> 0.35; "contender" -> 0.22; "moneyball" -> 0.2; "development" -> 0.08; else -> 0.04 }
                if (!rng.chance(chance)) continue
                tm.rebuildUntil = windowEnd
                tm.rebuildNote = "finished ${ordinal(pos)} of $n with the league's ${ordinal(pr + 1)}-highest payroll${if (!worlds) " and missed the world championship" else ""}"
                val starters = tm.lineup.filter { it >= 0 }.map { w.players[it] }
                // The young core stays; the expensive starters are put on the market.
                val sell = starters.filter { !(Dates.ageAt(it.birth, w.day) <= 21 && it.pa >= it.ca + 6) }.sortedByDescending { it.salary }.take(3)
                for (p in sell) p.listed = true
                w.news.add(News(w.day, "${tm.name} blow up the roster", "${tm.name} ${tm.rebuildNote}: ${sell.joinToString { it.handle }} are available as the club starts over.", listOf(tm.id)))
            }
        }
    }

    private fun ordinal(n: Int) = "$n" + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }

    /** For the player page: ambitions in order of weight. */
    fun ranked(w: World, p: Player): List<Pair<String, Int>> = of(w, p).let { a -> KEYS.indices.sortedByDescending { a[it] }.map { KEYS[it] to a[it] } }

    fun titleOf(p: Player) = Catalogue.get(p.titleId)
}

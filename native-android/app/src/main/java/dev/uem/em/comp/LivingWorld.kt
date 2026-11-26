package dev.uem.em.comp

import dev.uem.em.core.Rng
import dev.uem.em.data.GameData
import dev.uem.em.model.CompTier
import dev.uem.em.model.Competition
import dev.uem.em.model.News
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Host cities for international events: candidate cities in the game's regions bid; the organiser scores
 * venue capacity, infrastructure, audience for the game, cost and continental rotation (published policy),
 * and no city hosts two events at once. Hosting affects nothing in the matches.
 */
object Hosts {
    class City(val name: String, val country: String, val capacity: Int, val infra: Double, val cost: Double, val appeal: Double)

    val CITIES = listOf(
        City("Seoul", "KR", 18, 0.95, 0.7, 0.95), City("Busan", "KR", 10, 0.8, 0.5, 0.7), City("Shanghai", "CN", 18, 0.95, 0.7, 0.9), City("Chengdu", "CN", 12, 0.85, 0.5, 0.8),
        City("Shenzhen", "CN", 14, 0.9, 0.6, 0.8), City("Tokyo", "JP", 15, 0.95, 0.9, 0.85), City("Taipei", "TW", 10, 0.85, 0.5, 0.75), City("Bangkok", "TH", 12, 0.8, 0.4, 0.8),
        City("Ho Chi Minh City", "VN", 10, 0.75, 0.35, 0.8), City("Jakarta", "ID", 14, 0.75, 0.35, 0.9), City("Manila", "PH", 16, 0.75, 0.35, 0.9), City("Kuala Lumpur", "MY", 12, 0.8, 0.4, 0.8),
        City("Singapore", "SG", 10, 0.95, 0.8, 0.8), City("Riyadh", "SA", 18, 0.9, 0.6, 0.7), City("Berlin", "DE", 15, 0.9, 0.7, 0.85), City("Paris", "FR", 16, 0.9, 0.8, 0.9),
        City("London", "GB", 18, 0.95, 0.9, 0.85), City("Madrid", "ES", 14, 0.85, 0.7, 0.8), City("Copenhagen", "DK", 12, 0.9, 0.85, 0.7), City("Istanbul", "TR", 14, 0.8, 0.5, 0.8),
        City("Los Angeles", "US", 18, 0.95, 0.9, 0.85), City("New York", "US", 18, 0.95, 0.95, 0.85), City("Toronto", "CA", 14, 0.9, 0.8, 0.7), City("Mexico City", "MX", 15, 0.8, 0.5, 0.8),
        City("São Paulo", "BR", 16, 0.8, 0.5, 0.9), City("Santiago", "CL", 10, 0.75, 0.45, 0.7), City("Sydney", "AU", 12, 0.9, 0.85, 0.7),
    )

    private fun continent(cc: String) = GameData.continentOf(cc).ifEmpty { "Asia" }

    /** Cities the game has an audience in (its leagues' regions). */
    fun candidates(w: World, titleId: String): List<City> {
        val regs = w.leagues.filter { it.titleId == titleId && it.tier == 1 && it.until == 0 }.flatMap { GameData.expand(it.region).ifEmpty { listOf(it.region) } }.toSet()
        return CITIES.filter { c -> val r = GameData.countries[c.country]?.region; r != null && (r in regs || c.country in regs) }.ifEmpty { CITIES }
    }

    /**
     * Choose the host of [c] (an international event of the game's calendar): the best-scoring bid, rotation
     * across continents from the event's last two editions, never a city already hosting an event on those days.
     */
    fun choose(w: World, c: Competition) {
        if (c.host.isNotEmpty() || c.defKey.isEmpty()) return
        val rng = Rng.derive(w.seed, "host", c.defKey, c.season)
        val pop = Catalogue.POPULARITY[c.titleId] ?: emptyMap()
        val past = w.comps.filter { it.defKey == c.defKey && it.season < c.season && it.host.isNotEmpty() }.sortedByDescending { it.season }.take(2)
        val pastContinents = past.map { continent(it.host.substringAfterLast(", ")) }
        val start = c.startDay; val end = c.startDay + 21
        val busy = w.comps.filter { o -> o.id != c.id && o.host.isNotEmpty() && o.startDay < end && o.startDay + 21 > start }.map { it.host.substringBefore(',') }.toSet()
        val bids = candidates(w, c.titleId).filter { it.name !in busy }.map { city ->
            val audience = ((pop[GameData.countries[city.country]?.region ?: ""] ?: 30) / 100.0).coerceIn(0.0, 1.0)
            val rotation = if (continent(city.country) in pastContinents) 0.0 else 1.0
            val size = (city.capacity / 18.0).coerceAtMost(1.0) * (c.prestige / 100.0) + (1 - c.prestige / 100.0) * 0.6
            city to (0.25 * city.infra + 0.2 * audience + 0.15 * size + 0.15 * city.appeal + 0.2 * rotation - 0.1 * city.cost + rng.float(0.0, 0.05))
        }.sortedByDescending { it.second }.take(4)
        val win = bids.firstOrNull() ?: return
        c.host = "${win.first.name}, ${win.first.country}"
        c.hostNote = "Bids: " + bids.joinToString { "${it.first.name} (${(it.second * 100).roundToInt()})" } + ". Scored on infrastructure 25%, audience 20%, continental rotation 20%, venue 15%, appeal 15%, cost −10%."
        if (c.tier == CompTier.WORLDS || c.prestige >= 60) w.news.add(News(w.day, "${c.name}: host city ${win.first.name}", "${win.first.name} wins the bid to host ${c.name}${if (bids.size > 1) " ahead of ${bids.drop(1).joinToString { it.first.name }}" else ""}."))
    }
}

/**
 * Prestige of each event family, smoothed over seasons: it follows the quality of its field and its age, a
 * few points a season at most; secondary events stay below the world championship. Each edition keeps the
 * prestige it was created with.
 */
object Prestige {
    fun update(w: World, season: Int) {
        val byKey = w.comps.filter { it.season == season && it.done && it.defKey.isNotEmpty() && it.customId < 0 && it.teamIds.size >= 2 }.groupBy { it.defKey }
        for ((key, list) in byKey) {
            val c = list.first()
            if (c.tier == CompTier.WORLDS) { w.prestige[key] = 100.0; continue }
            val top = w.teams.filter { it.titleId == c.titleId && it.active && it.parentId < 0 }.maxOfOrNull { it.elo } ?: 1600.0
            val field = c.teamIds.map { w.teams[it].elo }.sortedDescending().take(8).average()
            val quality = ((field - (top - 600)) / 600).coerceIn(0.0, 1.0)            // 0 weak field … 1 the best teams
            val editions = w.comps.count { it.defKey == key && it.done }
            val base = c.prestige.toDouble()
            val target = (base * (0.85 + 0.3 * quality) + minOf(8, editions)).coerceAtMost(if (c.tier == CompTier.INTERNATIONAL) 90.0 else 80.0)
            val old = w.prestige[key] ?: base
            val next = old + (target - old).coerceIn(-3.0, 3.0) * 0.5
            w.prestige[key] = next.coerceIn(5.0, 95.0)
        }
    }

    /** The prestige a new edition is created with ([fallback] for a family without a history). */
    fun of(w: World, key: String, fallback: Int): Int = w.prestige[key]?.roundToInt() ?: fallback
}

/**
 * Rivalry cups between the two best-ranked leagues of a game (after the Nations Cup): each league's top four on
 * circuit points, a straight knockout seeded across the leagues. Prestige and pride only — they never count for
 * seeds or circuit points.
 */
object Rivalry {
    /** Series won by each league in an edition: league id → wins. */
    fun score(w: World, c: Competition): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        val leagueOf = c.entries.filter { it.status == "in" }.associate { it.teamId to (it.note.removePrefix("L").toIntOrNull() ?: -1) }
        for (st in c.stages) for (fid in st.fixtureIds) { val f = w.fixtures[fid]; if (f.done && f.winner >= 0) leagueOf[f.winner]?.let { out.merge(it, 1, Int::plus) } }
        return out
    }
}

/**
 * Organisations across the seven games: points from every finished event of their teams (world championship,
 * internationals scaled by prestige, league titles), in a window. "Total" sums every department, "per title"
 * averages over the games the organisation competes in (so a seven-game organisation does not win on volume
 * alone), and the development index counts academy and second-tier results apart.
 */
object OrgRankings {
    class Row(val orgId: Int, val total: Double, val perTitle: Double, val development: Double, val titles: Set<String>, val byTitle: Map<String, Double>, val best: List<Int>)

    private fun points(c: Competition, place: Int): Double {
        val share = when (place) { 0 -> 1.0; 1 -> 0.6; 2, 3 -> 0.35; in 4..7 -> 0.15; else -> 0.0 }
        return share * when (c.tier) { CompTier.WORLDS -> 100.0; CompTier.INTERNATIONAL -> c.prestige * 0.6; CompTier.LEAGUE -> 25.0; else -> 0.0 }
    }

    fun table(w: World, from: Int, to: Int): List<Row> {
        val tot = HashMap<Int, Double>(); val dev = HashMap<Int, Double>(); val by = HashMap<Int, HashMap<String, Double>>(); val best = HashMap<Int, MutableList<Pair<Int, Double>>>()
        for (c in w.comps) {
            if (!c.done || c.season !in from..to || c.customId >= 0 || c.placements.isEmpty() || Season.isEncQualifier(c)) continue
            if (c.teamIds.any { w.teams[it].nationalOf.isNotEmpty() }) continue
            for ((i, tid) in c.placements.withIndex()) {
                val team = w.teams[tid]
                val org = team.orgId
                if (team.parentId >= 0 || c.tier == CompTier.CHALLENGER) {
                    val d = when (i) { 0 -> 5.0; 1 -> 2.0; else -> 0.0 }
                    if (d > 0) dev.merge(org, d, Double::plus)
                    continue
                }
                val p = points(c, i)
                if (p <= 0) continue
                tot.merge(org, p, Double::plus)
                by.getOrPut(org) { HashMap() }.merge(team.titleId, p, Double::plus)
                best.getOrPut(org) { ArrayList() }.add(c.id to p)
            }
        }
        val orgs = (tot.keys + dev.keys).distinct()
        return orgs.map { o ->
            val titles = w.orgs[o].teams.mapNotNull { w.teams.getOrNull(it) }.filter { it.active && it.parentId < 0 && it.nationalOf.isEmpty() }.map { it.titleId }.toSet()
            val t = tot[o] ?: 0.0
            Row(o, t, t / maxOf(1, titles.size), dev[o] ?: 0.0, titles, by[o] ?: emptyMap(), best[o].orEmpty().sortedByDescending { it.second }.take(3).map { it.first })
        }
    }

    /** "6 May 2027": a stable tie-break on the organisation id keeps the order reproducible. */
    fun sorted(rows: List<Row>, mode: Int): List<Row> = rows.sortedWith(compareByDescending<Row> { when (mode) { 1 -> it.perTitle; 2 -> it.development; else -> it.total } }.thenBy { it.orgId })

    @Suppress("unused") private fun close(a: Double, b: Double) = abs(a - b) < 1e-9
}

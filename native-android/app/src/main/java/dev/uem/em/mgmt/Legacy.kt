package dev.uem.em.mgmt

import dev.uem.em.model.CompTier
import dev.uem.em.model.Player
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue

/**
 * Honours and the legacy ("GOAT") score, as in the web version: prestige-weighted achievements,
 * peak ability, longevity and volume. Achievements stay with the title they were won in.
 */
object Legacy {
    class Honour(val season: Int, val titleId: String, val label: String, val compId: Int, val teamId: Int, val pts: Double, val trophy: Boolean)
    class Score(val achievements: Double, val peak: Double, val longevity: Double, val volume: Double, val total: Double, val byTitle: Map<String, Double>)

    private fun trophyPts(tier: CompTier, prestige: Int) = when (tier) {
        CompTier.WORLDS -> 12.0
        CompTier.INTERNATIONAL -> 5.0 + prestige / 20.0
        CompTier.LEAGUE -> 2.0 + prestige / 40.0
        CompTier.CHALLENGER -> 0.8
    }

    private fun awardPts(name: String) = when {
        name.contains("Season MVP") -> 6.0
        name.contains("MVP") -> 5.0
        name.contains("All-Pro") -> 2.5
        name.contains("Rookie") -> 2.0
        else -> 1.0
    }

    /** Competitions won (with the player in the winning team's games) and individual awards, newest first. */
    fun honours(w: World, p: Player): List<Honour> {
        val out = ArrayList<Honour>()
        for (c in w.comps) {
            if (!c.done || c.placements.isEmpty()) continue
            val s = c.pstats[p.id] ?: continue
            if (s.gp > 0 && s.teamId == c.placements[0]) out.add(Honour(c.season, c.titleId, c.name, c.id, s.teamId, trophyPts(c.tier, c.prestige), true))
        }
        for (a in w.awards) if (a.playerId == p.id) out.add(Honour(a.season, a.titleId, a.name, a.compId, a.teamId, awardPts(a.name), false))
        // Team trophies from before the save began (real history) are on the team; credit long-serving players lightly.
        return out.sortedWith(compareByDescending<Honour> { it.season }.thenByDescending { it.pts })
    }

    fun of(w: World, p: Player, honours: List<Honour> = honours(w, p)): Score {
        val ach = honours.sumOf { it.pts }
        val peakCa = (p.caHistory.maxOrNull()?.toDouble() ?: 0.0).coerceAtLeast(p.ca)
        val peak = ((peakCa - 55).coerceAtLeast(0.0)) * 0.6
        val seasons = p.stats.map { it.season }.distinct().size
        val longevity = seasons * 1.5
        val games = p.stats.sumOf { it.gp }
        val volume = games / 40.0
        val by = honours.groupBy { it.titleId }.mapValues { (_, v) -> v.sumOf { it.pts } }
        return Score(ach, peak, longevity, volume, ach + peak + longevity + volume, by)
    }

    private val CLOSE = listOf(Triple("lol", "wildrift", 0.9), Triple("mlbb", "hok", 0.82), Triple("mlbb", "wildrift", 0.8), Triple("hok", "wildrift", 0.8), Triple("lol", "dota2", 0.72), Triple("cs2", "valorant", 0.72))
    private val SUB = mapOf("mlbb" to "mobile-moba", "hok" to "mobile-moba", "wildrift" to "mobile-moba", "lol" to "pc-moba", "dota2" to "pc-moba", "valorant" to "tactical-fps", "cs2" to "tactical-fps")

    /** How closely two titles are related (familiarity transfers by this). */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        CLOSE.firstOrNull { (x, y, _) -> (x == a && y == b) || (x == b && y == a) }?.let { return it.third }
        if (SUB[a] == SUB[b]) return 0.75
        val ma = Catalogue.get(a).isMoba
        val mb = Catalogue.get(b).isMoba
        return if (ma == mb) 0.68 else 0.2
    }

    fun similarityLabel(s: Double) = when {
        s >= 1 -> "Same title"
        s >= 0.8 -> "Very closely related"
        s >= 0.65 -> "Same genre, similar subgenre"
        s >= 0.35 -> "Same genre, different subgenre"
        else -> "Different genres"
    }
}

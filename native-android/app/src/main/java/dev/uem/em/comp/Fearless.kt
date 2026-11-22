package dev.uem.em.comp

import dev.uem.em.core.Rng
import dev.uem.em.model.CompTier
import dev.uem.em.model.Competition
import dev.uem.em.model.World
import dev.uem.em.titles.TitleDef

/**
 * Fearless draft rules across the games of a series (MOBAs with a ban / pick draft):
 * - none: a normal draft every game;
 * - soft: a team cannot play a champion it played earlier in the series (its opponent may);
 * - hard: nobody can play a champion either team played earlier in the series;
 * - insane: as hard, and champions banned earlier stay banned too.
 * Each game sets its own rules per event type; a major patch can change them.
 */
object Fearless {
    val MODES = listOf("none", "soft", "hard", "insane")
    val LABEL = mapOf("none" to "Normal draft", "soft" to "Soft Fearless", "hard" to "Fearless", "insane" to "Insane Fearless")
    val DESC = mapOf(
        "none" to "every game is drafted from the full pool",
        "soft" to "a team cannot play the champions it played in earlier games; its opponent still can",
        "hard" to "champions picked by either team in earlier games cannot be played again in the series",
        "insane" to "champions picked or banned in earlier games cannot be played again in the series",
    )
    val SCOPES = listOf("league", "firststand", "msi", "worlds", "nations")
    val SCOPE_LABEL = mapOf("league" to "Leagues", "firststand" to "First Stand", "msi" to "Mid-Season Invitational", "worlds" to "World Championship", "nations" to "Nations Cup")

    fun default(scope: String) = when (scope) { "firststand" -> "hard"; "msi" -> "insane"; else -> "none" }

    /**
     * Fearless needs a deep pool (long best-of-sevens use up many characters): Soft Fearless from
     * 100 characters, Fearless from 120, Insane Fearless from 150. Below 100 a game plays normal
     * drafts; new releases can lift it over a line.
     */
    const val MIN_POOL = 100
    val POOL_FOR = mapOf("soft" to 100, "hard" to 120, "insane" to 150)

    fun applies(t: TitleDef) = t.draft.mode == "sequential" && t.characters.size >= MIN_POOL

    /** Whether the pool is deep enough for this rule. */
    fun allows(t: TitleDef, mode: String) = mode == "none" || (t.draft.mode == "sequential" && t.characters.size >= (POOL_FOR[mode] ?: Int.MAX_VALUE))

    /** The rule actually played: a rule the pool is too small for steps down to the strongest one it allows. */
    fun effective(t: TitleDef, mode: String): String {
        var m = mode
        while (m != "none" && !allows(t, m)) m = when (m) { "insane" -> "hard"; "hard" -> "soft"; else -> "none" }
        return m
    }

    /** The modes this game's pool allows. */
    fun modesFor(t: TitleDef) = MODES.filter { allows(t, it) }

    fun rule(w: World, t: TitleDef, scope: String): String = if (!applies(t)) "none" else effective(t, w.fearlessRules["${t.id}:$scope"] ?: default(scope))

    fun scopeOf(c: Competition): String = when {
        c.tier == CompTier.WORLDS -> "worlds"
        c.entrants.any { it.startsWith("nations:") || it.startsWith("qual:") || it.startsWith("continent:") } -> "nations"
        c.tier == CompTier.INTERNATIONAL && (c.short.contains("FST") || c.name.contains("First Stand")) -> "firststand"
        c.tier == CompTier.INTERNATIONAL -> "msi"
        else -> "league"
    }

    /** The rule a competition's series are actually drafted with in this game. */
    fun played(t: TitleDef, c: Competition?): String = if (c == null || !applies(t)) "none" else effective(t, modeOf(c))

    /** The rule a competition plays with (old saves: the boolean flag means hard Fearless). */
    fun modeOf(c: Competition): String = c.fearlessMode.ifBlank { if (c.fearless) "hard" else "none" }

    /** Set a new competition's rule from the game's current rules. */
    fun assign(w: World, t: TitleDef, c: Competition) {
        c.fearlessMode = rule(w, t, scopeOf(c))
        c.fearless = c.fearlessMode != "none"
    }

    /** Major patch: each event type may switch to another rule (games evolve independently). Returns patch notes. */
    fun evolve(w: World, t: TitleDef, rng: Rng): List<String> {
        if (!applies(t)) return emptyList()
        val notes = ArrayList<String>()
        for (scope in SCOPES) {
            if (!rng.chance(0.3)) continue
            val old = rule(w, t, scope)
            val opts = modesFor(t).filter { it != old }
            if (opts.isEmpty()) continue
            val next = rng.weighted(opts) { when (it) { "none" -> 3.0; "soft" -> 2.5; "hard" -> 3.0; else -> 1.5 } }
            w.fearlessRules["${t.id}:$scope"] = next
            notes.add("Draft rules — ${SCOPE_LABEL[scope]}: ${LABEL[next]} (was ${LABEL[old]}): ${DESC[next]}.")
        }
        return notes
    }
}

package dev.uem.em.titles

/**
 * Which agents a map favours in VALORANT (the agents professional teams build their compositions around
 * there) and the role a team's flex player usually fills on it (a second controller on Abyss, a second
 * initiator on Haven...). Compiled from the professional meta of the real maps; maps and agents without an
 * entry (new or fictional ones) get a stable lean of their own, so every map still plays differently.
 */
object MapMeta {
    private val STAPLES: Map<String, Set<String>> = mapOf(
        "bind" to setOf("raze", "brimstone", "viper", "skye", "fade", "cypher", "gekko"),
        "haven" to setOf("jett", "omen", "sova", "breach", "killjoy", "kay-o", "vyse", "tejo"),
        "lotus" to setOf("raze", "neon", "omen", "viper", "fade", "killjoy", "vyse"),
        "sunset" to setOf("neon", "raze", "omen", "cypher", "breach", "fade", "viper"),
        "abyss" to setOf("jett", "omen", "astra", "sova", "cypher", "vyse", "viper"),
        "ascent" to setOf("jett", "omen", "sova", "kay-o", "killjoy", "vyse"),
        "split" to setOf("raze", "jett", "viper", "omen", "breach", "skye", "cypher"),
        "icebox" to setOf("viper", "jett", "sova", "killjoy", "gekko"),
        "breeze" to setOf("viper", "jett", "sova", "kay-o", "cypher", "harbor"),
        "pearl" to setOf("astra", "harbor", "viper", "fade", "neon", "killjoy"),
        "fracture" to setOf("raze", "neon", "brimstone", "breach", "fade", "cypher"),
        // Corrode (2025): estimated from its first professional season.
        "corrode" to setOf("viper", "omen", "sova", "fade", "vyse", "neon", "waylay"),
    )
    /** The class a flex player usually fills on a map (a double controller, initiator or duelist). */
    private val FLEX: Map<String, String> = mapOf(
        "bind" to "Initiator", "haven" to "Initiator", "lotus" to "Controller", "sunset" to "Initiator", "abyss" to "Controller",
        "ascent" to "Initiator", "split" to "Duelist", "icebox" to "Controller", "breeze" to "Controller", "pearl" to "Controller",
        "fracture" to "Initiator", "corrode" to "Initiator",
    )
    private val CLASSES = listOf("Duelist", "Initiator", "Controller", "Sentinel")

    private fun key(mapId: String) = mapId.substringAfter(':').lowercase()
    private fun short(charId: String) = charId.substringAfter('-')

    /** Whether the agent is one a map's compositions are built around. */
    fun staple(mapId: String, charId: String) = STAPLES[key(mapId)]?.contains(short(charId)) == true

    /** How much a map suits an agent (-0.1..0.35): its staples, and a stable lean for every other pairing. */
    fun fit(mapId: String, charId: String): Double {
        val k = key(mapId)
        val lean = (Math.floorMod((k + "|" + charId).hashCode(), 2001) - 1000) / 10_000.0
        return (if (staple(mapId, charId)) 0.25 else 0.0) + lean
    }

    /** The class a flex player fills on this map. */
    fun flexClass(mapId: String): String = FLEX[key(mapId)] ?: CLASSES[Math.floorMod(key(mapId).hashCode(), CLASSES.size)]
}

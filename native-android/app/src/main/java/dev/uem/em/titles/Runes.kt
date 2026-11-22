package dev.uem.em.titles

import dev.uem.em.core.Rng
import dev.uem.em.data.DataSource
import dev.uem.em.model.World
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One rune (LoL, Wild Rift), emblem set or talent (MLBB), arcana (HoK), or hero talent / facet
 * (Dota 2). [stats] use the objective stat keys (titles.Neutrals) plus hp / pen, on the engine's
 * scale (see scripts/data/runes_build.py).
 */
class RuneDef(val id: String, val titleId: String, val name: String, val group: String, val slot: String, val desc: String,
              val stats: Map<String, Double>, val img: String? = null, val approx: Boolean = false, val custom: Boolean = false)

/** What a hero takes into a game: its page and a short label for the box score. */
class RunePage(val entries: List<RuneDef>, val label: String) {
    /** Summed stats (an HoK arcana counts ten times: a page holds ten of each). */
    fun stats(w: World?): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (e in entries) { val n = if (e.slot == "arcana") 10.0 else 1.0; val m = Runes.mod(w, e.id); for ((k, v) in e.stats) out[k] = (out[k] ?: 0.0) + v * n * m }
        return out
    }
}

/**
 * Runes, emblems, arcana and talents of the five MOBAs (assets/runes.json) plus those a world's
 * patches added; patch changes scale an entry's stats ([World.runeMods]).
 */
object Runes {
    private val base = HashMap<String, List<RuneDef>>()
    private val byId = HashMap<String, RuneDef>()
    @Volatile private var loaded = false
    val KIND = mapOf("lol" to "Runes", "wildrift" to "Runes", "mlbb" to "Emblems", "hok" to "Arcana", "dota2" to "Talents")

    @Synchronized
    fun init(src: DataSource) {
        if (loaded) return
        val j = runCatching { Json.parseToJsonElement(src.read("runes.json")).jsonObject["entries"]!!.jsonArray }.getOrNull()
        if (j != null) {
            val all = j.map { e ->
                val o = e.jsonObject
                RuneDef(o["id"]!!.jsonPrimitive.content, o["title"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, o["group"]?.jsonPrimitive?.content ?: "",
                    o["slot"]?.jsonPrimitive?.content ?: "", o["desc"]?.jsonPrimitive?.content ?: "", o["stats"]?.jsonObject?.mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 } ?: emptyMap(),
                    o["img"]?.jsonPrimitive?.content?.takeIf { it != "null" }, o["approx"]?.jsonPrimitive?.booleanOrNull ?: false)
            }
            for ((t, list) in all.groupBy { it.titleId }) base[t] = list
            for (r in all) byId.putIfAbsent(r.id, r)
        }
        loaded = true
    }

    fun of(w: World?, titleId: String): List<RuneDef> = base[titleId].orEmpty() + (w?.customRunes?.filter { it.titleId == titleId }?.map { c ->
        RuneDef(c.id, c.titleId, c.name, c.group, c.slot, c.desc, c.stats, null, false, true) } ?: emptyList())

    fun get(w: World?, id: String): RuneDef? = byId[id] ?: w?.customRunes?.firstOrNull { it.id == id }?.let { c -> RuneDef(c.id, c.titleId, c.name, c.group, c.slot, c.desc, c.stats, null, false, true) }

    fun mod(w: World?, id: String): Double = w?.runeMods?.get(id) ?: 1.0

    private fun pick(list: List<RuneDef>, rng: Rng, prefer: List<String> = emptyList()): RuneDef? {
        if (list.isEmpty()) return null
        val pref = prefer.mapNotNull { n -> list.firstOrNull { it.name == n } }
        // Mostly the class's usual choice (in order), sometimes another one (or a new fictional one).
        val newOnes = list.filter { it.custom }
        if (newOnes.isNotEmpty() && rng.chance(0.35)) return newOnes[rng.int(0, newOnes.size - 1)]
        if (pref.isNotEmpty() && rng.chance(0.8)) return pref[minOf(pref.size - 1, (rng.next() * rng.next() * pref.size).toInt())]
        return list[rng.int(0, list.size - 1)]
    }

    /** How much a class wants a stat (picks minor runes, arcana and talents). */
    private fun want(cls: String, support: Boolean, magic: Boolean): Map<String, Double> = when {
        support -> mapOf("heal" to 3.0, "haste" to 0.3, "ms" to 2.5, "hp" to 2.0, "armor" to 1.5, "mr" to 1.5, "dr" to 2.0, "regen" to 2.0)
        cls.contains("tank") -> mapOf("hp" to 3.0, "armor" to 3.0, "mr" to 3.0, "dr" to 3.0, "regen" to 2.0, "tenacity" to 2.0, "haste" to 0.15)
        cls.contains("marksman") || cls.contains("carry") -> mapOf("as" to 3.0, "ad" to 3.0, "dmg" to 2.5, "pen" to 2.0, "heal" to 1.0, "ms" to 1.0)
        magic || cls.contains("mage") || cls.contains("nuker") -> mapOf("ap" to 3.0, "pen" to 2.5, "dmg" to 2.5, "haste" to 0.25, "ms" to 0.8)
        cls.contains("assassin") -> mapOf("ad" to 2.5, "pen" to 3.0, "dmg" to 3.0, "ms" to 2.0, "execute" to 2.0)
        else -> mapOf("ad" to 2.0, "heal" to 2.0, "hp" to 2.0, "armor" to 1.5, "mr" to 1.5, "dmg" to 2.0, "as" to 1.0, "regen" to 1.5, "haste" to 0.15)
    }

    private fun score(r: RuneDef, w: Map<String, Double>) = r.stats.entries.sumOf { (k, v) -> (w[k] ?: 0.1) * (if (k == "haste") v / 100 else v) }

    private fun best(list: List<RuneDef>, w: Map<String, Double>, rng: Rng): RuneDef? {
        if (list.isEmpty()) return null
        val sorted = list.sortedByDescending { score(it, w) + rng.next() * 0.01 }
        return sorted[minOf(sorted.size - 1, (rng.next() * rng.next() * 3).toInt())]
    }

    private val TREE = mapOf("marksman" to "Precision", "mage" to "Sorcery", "assassin" to "Domination", "fighter" to "Precision", "tank" to "Resolve", "support" to "Sorcery")
    private val KEY_LOL = mapOf("marksman" to listOf("Lethal Tempo", "Press the Attack", "Fleet Footwork"), "mage" to listOf("Arcane Comet", "Electrocute", "Summon Aery", "Phase Rush"),
        "assassin" to listOf("Electrocute", "Dark Harvest", "Hail of Blades", "First Strike"), "fighter" to listOf("Conqueror", "Grasp of the Undying", "Lethal Tempo", "Phase Rush"),
        "tank" to listOf("Aftershock", "Grasp of the Undying", "Guardian"), "support" to listOf("Summon Aery", "Guardian", "Glacial Augment", "Aftershock"))
    private val KEY_WR = mapOf("marksman" to listOf("Lethal Tempo", "Fleet Footwork", "Kraken Slayer", "Conqueror"), "mage" to listOf("Electrocute", "Arcane Comet", "Phase Rush", "Summon Aery"),
        "assassin" to listOf("Electrocute", "Dark Harvest", "Hail of Blades", "First Strike"), "fighter" to listOf("Conqueror", "Grasp of the Undying", "Fleet Footwork"),
        "tank" to listOf("Aftershock", "Grasp of the Undying", "Glacial Augment"), "support" to listOf("Summon Aery", "Glacial Augment", "Aftershock"))
    private val SET_ML = mapOf("tank" to "Custom Tank", "assassin" to "Custom Assassin", "mage" to "Custom Mage", "fighter" to "Custom Fighter", "support" to "Custom Support", "marksman" to "Custom Marksman")

    private fun kind(cls: String, support: Boolean, magic: Boolean): String = when {
        support && !cls.contains("tank") -> "support"
        cls.contains("tank") -> "tank"
        cls.contains("marksman") || cls.contains("carry") -> "marksman"
        cls.contains("assassin") -> "assassin"
        magic || cls.contains("mage") || cls.contains("nuker") -> "mage"
        else -> "fighter"
    }

    /** The page a hero takes into a game, chosen from its class (and its own talent tree in Dota 2). */
    fun page(w: World?, titleId: String, charId: String, cls0: String, support: Boolean, magicShare: Double, rng: Rng): RunePage? {
        val all = of(w, titleId)
        if (all.isEmpty()) return null
        val cls = cls0.lowercase()
        val magic = magicShare >= 0.5
        val k = kind(cls, support, magic)
        val want = want(cls, support, magic)
        return when (titleId) {
            "lol", "wildrift" -> {
                val keys = all.filter { it.slot == "keystone" }
                val key = pick(keys, rng, (if (titleId == "lol") KEY_LOL else KEY_WR)[k].orEmpty()) ?: return null
                val tree = key.group
                val second = all.filter { it.group != tree && it.slot != "keystone" }.groupBy { it.group }.keys.toList().let { g ->
                    val pref = TREE[if (k == "support") "tank" else if (k == "tank") "fighter" else "support"]
                    if (pref != null && pref != tree && pref in g) pref else g.getOrNull(rng.int(0, maxOf(0, g.size - 1)))
                }
                val rows = all.filter { it.group == tree && it.slot.startsWith("row") }.groupBy { it.slot }.toSortedMap().values.mapNotNull { best(it, want, rng) }
                val sec = all.filter { it.group == second && it.slot.startsWith("row") }.groupBy { it.slot }.toSortedMap().values.mapNotNull { best(it, want, rng) }.take(2)
                RunePage(listOf(key) + rows + sec, "${key.name} (${tree}${second?.let { " / $it" } ?: ""})")
            }
            "mlbb" -> {
                val sets = all.filter { it.slot == "set" }
                val set = sets.firstOrNull { it.name == SET_ML[k] } ?: sets.firstOrNull() ?: return null
                val common = "Basic Common"
                fun from(slot: String) = all.filter { it.slot == slot && (it.group == set.name || it.group == common || it.custom) }
                val t1 = best(from("talent1"), want, rng); val t2 = best(from("talent2"), want, rng)
                val core = from("core").let { c -> pick(c, rng, c.filter { it.group == set.name }.map { it.name }) }
                val page = listOfNotNull(set, t1, t2, core)
                RunePage(page, "${set.name}${core?.let { ": ${it.name}" } ?: ""}")
            }
            "hok" -> {
                val arc = all.filter { it.slot == "arcana" }
                val chosen = ArrayList<RuneDef>()
                repeat(3) { best(arc.filter { it !in chosen }, want, rng)?.let { chosen.add(it) } }
                RunePage(chosen, chosen.joinToString(" · ") { "${it.name} ×10" })
            }
            "dota2" -> {
                val mine = all.filter { it.group == charId }
                val talents = listOf("level10", "level15", "level20", "level25").mapNotNull { lv -> best(mine.filter { it.slot == lv }, want, rng) }
                val facet = mine.filter { it.slot == "facet" }.let { f -> if (f.isEmpty()) null else f[rng.int(0, f.size - 1)] }
                if (talents.isEmpty() && facet == null) return null
                RunePage(listOfNotNull(facet) + talents, facet?.let { "Facet: ${it.name}" } ?: "${talents.size} talents")
            }
            else -> null
        }
    }
}

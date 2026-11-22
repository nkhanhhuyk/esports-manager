package dev.uem.em.titles

import dev.uem.em.core.Rng
import dev.uem.em.data.DataSource
import dev.uem.em.model.World
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A MOBA item (equipment) with its real name, price, type and stats. `type` is one of physical,
 * magical, defense, movement (boots), support (roaming / support items) and jungle.
 */
class ItemDef(val id: String, val titleId: String, val name: String, val cost: Int, val type: String, val stats: Map<String, Double>, val img: String?, val custom: Boolean = false) {
    /** Normalised units (1 = a typical item of its kind in this game), set by [Items]. */
    var offP = 0.0; var offM = 0.0; var def = 0.0; var arm = 0.0; var mr = 0.0; var asU = 0.0; var crit = 0.0; var haste = 0.0; var ms = 0.0; var ls = 0.0; var hp = 0.0
}

/**
 * Item catalogues of the five MOBAs (assets/items.json, see scripts/data/items_build.py) plus items a
 * world added in its patches; patch changes scale an item's stats ([World.itemMods]).
 */
object Items {
    private val base = HashMap<String, List<ItemDef>>()
    private val byId = HashMap<String, ItemDef>()
    @Volatile private var loaded = false
    /** Median raw value of each stat per game (the unit of 1.0). */
    private val med = HashMap<String, Map<String, Double>>()

    @Synchronized
    fun init(src: DataSource) {
        if (loaded) return
        val j = runCatching { Json.parseToJsonElement(src.read("items.json")).jsonObject["items"]!!.jsonObject }.getOrNull()
        if (j != null) for ((titleId, arr) in j) {
            val list = arr.jsonArray.map { e ->
                val o = e.jsonObject
                ItemDef(o["id"]!!.jsonPrimitive.content, titleId, o["name"]!!.jsonPrimitive.content, o["cost"]!!.jsonPrimitive.content.toDouble().toInt(),
                    o["type"]!!.jsonPrimitive.content, o["stats"]?.jsonObject?.mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 } ?: emptyMap(),
                    o["img"]?.jsonPrimitive?.content?.takeIf { it != "null" })
            }
            base[titleId] = list
            normalise(titleId, list)
            for (it in list) byId[it.id] = it
        }
        loaded = true
    }

    private fun median(v: List<Double>) = if (v.isEmpty()) 1.0 else v.sorted()[v.size / 2].coerceAtLeast(0.01)

    private fun normalise(titleId: String, list: List<ItemDef>) {
        val full = list.filter { it.type != "movement" }
        val keys = listOf("ad", "ap", "hp", "armor", "mr", "as", "crit", "haste", "pen", "ls")
        val m = keys.associateWith { k -> median(full.mapNotNull { it.stats[k]?.takeIf { v -> v > 0 } }) } +
            mapOf("ms" to median(list.filter { it.type == "movement" }.mapNotNull { it.stats["ms"] ?: it.stats["mspct"]?.times(4) }))
        med[titleId] = m
        for (it in list) unitsOf(it, m)
        // Offence and defence units so that a typical damage / defence item of this game is 1.0.
        val p = list.filter { it.type == "physical" }.map { it.offP }.average().takeIf { it > 0 } ?: 1.0
        val g = list.filter { it.type == "magical" }.map { it.offM }.average().takeIf { it > 0 } ?: 1.0
        val d = list.filter { it.type == "defense" }.map { it.def }.average().takeIf { it > 0 } ?: 1.0
        div[titleId] = doubleArrayOf(p, g, d)
        for (it in list) scale(it, titleId)
    }

    private val div = HashMap<String, DoubleArray>()
    private fun scale(it: ItemDef, titleId: String) {
        val (p, g, d) = div[titleId]?.toList() ?: listOf(1.0, 1.0, 1.0)
        it.offP /= p; it.offM /= g; it.def /= d; it.arm /= d; it.mr /= d
    }

    private fun unitsOf(it: ItemDef, m: Map<String, Double>) {
        fun n(k: String) = (it.stats[k] ?: 0.0) / (m[k] ?: 1.0)
        it.crit = n("crit"); it.asU = n("as"); it.haste = n("haste"); it.ls = n("ls"); it.hp = n("hp")
        it.ms = ((it.stats["ms"] ?: 0.0) + (it.stats["mspct"] ?: 0.0) * 4) / (m["ms"] ?: 1.0)
        val pen = n("pen")
        it.offP = n("ad") + it.crit * 0.6 + it.asU * 0.45 + (if (n("ap") < n("ad")) pen * 0.4 else 0.0)
        it.offM = n("ap") + (if (n("ap") >= n("ad")) pen * 0.4 else 0.0)
        it.arm = n("armor") + it.hp * 0.45
        it.mr = n("mr") + it.hp * 0.45
        it.def = it.hp + n("armor") + n("mr")
    }

    /** The items of a game in this world: the catalogue (with patch changes) and items added by patches. */
    fun of(w: World?, titleId: String): List<ItemDef> {
        val b = base[titleId].orEmpty()
        val extra = w?.customItems?.filter { it.titleId == titleId }?.map { custom(it) }.orEmpty()
        return b + extra
    }

    private val customCache = java.util.concurrent.ConcurrentHashMap<String, ItemDef>()
    private fun custom(c: dev.uem.em.model.CustomItem): ItemDef = customCache.getOrPut("${c.id}|${c.cost}|${c.stats.hashCode()}") {
        ItemDef(c.id, c.titleId, c.name, c.cost, c.type, c.stats, null, custom = true).also { unitsOf(it, med[c.titleId] ?: emptyMap()); scale(it, c.titleId) }
    }

    fun get(w: World?, id: String): ItemDef? = byId[id] ?: w?.customItems?.firstOrNull { it.id == id }?.let { custom(it) }

    /** Patch scale of an item's stats (1 = as released). */
    fun mod(w: World?, id: String) = w?.itemMods?.get(id) ?: 1.0

    fun hasItems(titleId: String) = base[titleId].orEmpty().isNotEmpty()

    // ------------------------------------------------------------------ builds

    /** What a hero builds toward: offence of its damage type, defence, utility. */
    class Profile(val magic: Boolean, val off: Double, val def: Double, val util: Double, val critAs: Boolean, val label: String)

    /**
     * Pick a build for a hero: marksmen crit / attack speed, mages magic power, assassins burst,
     * fighters bruiser or full damage, tanks defence, supports support items. Heroes whose kit mixes
     * damage types can go either way; a team short of a front line pushes a fighter to a tank build.
     */
    fun profile(cls: String, isSupport: Boolean, magicShare: Double, teamNeedsTank: Boolean, rng: Rng): Profile {
        val c = cls.lowercase()
        val hybrid = magicShare in 0.3..0.7
        val magic = if (hybrid) rng.chance(magicShare) else magicShare > 0.5
        return when {
            isSupport -> if (Regex("tank|guardian").containsMatchIn(c) || rng.chance(0.35)) Profile(magic, 0.15, 1.0, 0.8, false, "Tank support") else Profile(true, 0.45, 0.35, 1.0, false, "Enchanter")
            Regex("marksman|carry|sniper").containsMatchIn(c) -> Profile(magic && hybrid, 1.0, 0.12, 0.2, !magic, if (magic && hybrid) "Magic damage" else "Crit / attack speed")
            Regex("mage|nuker").containsMatchIn(c) -> if (rng.chance(0.2)) Profile(true, 0.75, 0.4, 0.5, false, "Battle mage") else Profile(true, 1.0, 0.15, 0.35, false, "Burst mage")
            Regex("assassin").containsMatchIn(c) -> Profile(magic, 1.0, 0.15, 0.25, false, if (magic) "Magic burst" else "Lethality")
            Regex("tank|guardian").containsMatchIn(c) -> if (!teamNeedsTank && rng.chance(0.15)) Profile(magic, 0.55, 0.6, 0.3, false, "Bruiser") else Profile(magic, 0.15, 1.0, 0.3, false, "Full tank")
            else -> when { // fighters
                teamNeedsTank && rng.chance(0.6) -> Profile(magic, 0.2, 1.0, 0.25, false, "Tank build")
                rng.chance(0.3) -> Profile(magic, 1.0, 0.15, 0.2, false, "Full damage")
                else -> Profile(magic, 0.6, 0.6, 0.25, false, "Bruiser")
            }
        }
    }

    /**
     * The next item to buy: what the build wants, adjusted to the game (defence when dying or behind,
     * armour against a physical enemy team, magic resistance against a magic one), boots early.
     */
    fun next(w: World?, titleId: String, owned: List<ItemDef>, p: Profile, enemyMagic: Double, behind: Double, rng: Rng, jungler: Boolean): ItemDef? {
        val all = of(w, titleId)
        if (all.isEmpty()) return null
        val ownedIds = owned.map { it.id }.toHashSet()
        val hasBoots = owned.any { it.type == "movement" }
        val hasSupport = owned.any { it.type == "support" }
        val hasJungle = owned.any { it.type == "jungle" }
        // Behind → more defence; ahead → more damage (a tank build stays a tank build).
        val b = if (p.def >= 0.9) behind.coerceAtLeast(0.0) else behind
        val defW = (p.def + b * 0.6).coerceAtMost(2.0)
        val offW = (p.off - b * 0.25).coerceAtLeast(0.1)
        var best: ItemDef? = null; var bv = -9.0
        for (it in all) {
            if (it.id in ownedIds) continue
            if (it.type == "movement" && (hasBoots || owned.isEmpty() && titleId != "dota2")) continue
            if (it.type == "support" && (hasSupport && p.util < 0.8 || p.util < 0.5)) continue
            if (it.type == "jungle" && (!jungler || hasJungle)) continue
            val m = mod(w, it.id)
            val off = (if (p.magic) it.offM else it.offP) * m
            val wrongOff = (if (p.magic) it.offP else it.offM) * m
            var v = offW * off - wrongOff * 0.5 + defW * (it.arm * (1 - enemyMagic) + it.mr * enemyMagic) * m + p.util * (it.haste * 0.3 + it.ms * 0.1)
            if (p.critAs) v += (it.crit + it.asU) * 0.35 * m
            if (it.type == "movement") v = if (owned.size >= 1) 3.0 else -1.0
            if (it.type == "support") v += if (!hasSupport && owned.isEmpty()) 4.0 else 0.5
            if (it.type == "jungle") v += if (owned.isEmpty()) 4.0 else 0.0
            v += rng.normal(0.0, 0.12)
            if (v > bv) { bv = v; best = it }
        }
        return best
    }

    /** A plausible finished build for a game simulated in fast mode (no item-by-item decisions). */
    fun quickBuild(w: World?, titleId: String, n: Int, p: Profile, enemyMagic: Double, rng: Rng, jungler: Boolean): List<ItemDef> {
        val out = ArrayList<ItemDef>()
        repeat(n.coerceIn(0, 6)) { next(w, titleId, out, p, enemyMagic, 0.0, rng, jungler)?.let { out.add(it) } }
        return out
    }
}

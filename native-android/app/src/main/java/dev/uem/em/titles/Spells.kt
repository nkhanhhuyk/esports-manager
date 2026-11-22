package dev.uem.em.titles

import dev.uem.em.core.Rng
import dev.uem.em.data.DataSource
import dev.uem.em.model.World
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A summoner spell (LoL, Wild Rift), battle spell (MLBB) or common skill (HoK). [effect] is what it
 * does in the match engine: blink, teleport, ignite, execute, smite, heal, shield, weaken, haste,
 * cleanse, stun, poke, frenzy, reflect, disrupt.
 */
class SpellDef(val id: String, val titleId: String, val name: String, val cd: Double, val desc: String, val effect: String, val img: String? = null,
               val approx: Boolean = false, val custom: Boolean = false)

/**
 * Spells of the four MOBAs that have them (assets/spells.json, see scripts/data/spells_build.py):
 * two chosen in League of Legends and Wild Rift, one in MLBB (Recall and Healing are fixed) and HoK
 * (Recovery is fixed); Dota 2 has none. Patches change cooldowns and strength ([World.spellMods]),
 * remove spells ([World.removedSpells]) and add fictional ones ([World.customSpells]). Also holds
 * the real pictures of the neutral objectives.
 */
object Spells {
    private val base = HashMap<String, List<SpellDef>>()
    private val slots = HashMap<String, Int>()
    private val fixedBy = HashMap<String, List<String>>()
    private val objImg = HashMap<String, String>()
    @Volatile private var loaded = false

    @Synchronized
    fun init(src: DataSource) {
        if (loaded) return
        val j = runCatching { Json.parseToJsonElement(src.read("spells.json")).jsonObject }.getOrNull()
        if (j != null) {
            val all = j["spells"]!!.jsonArray.map { e ->
                val o = e.jsonObject
                SpellDef(o["id"]!!.jsonPrimitive.content, o["title"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, o["cd"]?.jsonPrimitive?.doubleOrNull ?: 120.0,
                    o["desc"]?.jsonPrimitive?.content ?: "", o["effect"]?.jsonPrimitive?.content ?: "blink", o["img"]?.jsonPrimitive?.content?.takeIf { it != "null" },
                    o["approx"]?.jsonPrimitive?.booleanOrNull ?: false)
            }
            for ((t, list) in all.groupBy { it.titleId }) base[t] = list
            j["slots"]?.jsonObject?.forEach { (k, v) -> slots[k] = v.jsonPrimitive.intOrNull ?: 0 }
            j["fixed"]?.jsonObject?.forEach { (k, v) -> fixedBy[k] = v.jsonArray.map { it.jsonPrimitive.content } }
            j["objectiveImages"]?.jsonObject?.forEach { (k, v) -> objImg[k] = v.jsonPrimitive.content }
        }
        loaded = true
    }

    /** Spells a player of this game can choose now (removed ones left out, new ones added). */
    fun of(w: World?, titleId: String): List<SpellDef> = all(w, titleId).filter { w == null || it.id !in w.removedSpells }

    /** Every spell ever in the game in this world (for replays and the library). */
    fun all(w: World?, titleId: String): List<SpellDef> = base[titleId].orEmpty() + (w?.customSpells?.filter { it.titleId == titleId }?.map { c ->
        SpellDef(c.id, c.titleId, c.name, c.cd, c.desc, c.effect, null, false, true) } ?: emptyList())

    fun get(w: World?, id: String): SpellDef? = base.values.asSequence().flatten().firstOrNull { it.id == id }
        ?: w?.customSpells?.firstOrNull { it.id == id }?.let { c -> SpellDef(c.id, c.titleId, c.name, c.cd, c.desc, c.effect, null, false, true) }

    fun slots(titleId: String) = slots[titleId] ?: 0
    fun fixed(titleId: String) = fixedBy[titleId].orEmpty()
    /** Patch change of a spell: its cooldown is divided and its strength multiplied by this (1 = as released). */
    fun mod(w: World?, id: String): Double = w?.spellMods?.get(id) ?: 1.0
    fun cooldown(w: World?, s: SpellDef) = s.cd / mod(w, s.id)

    /** The real picture of a neutral objective kind ("lol-infernal"), a soul ("soul:lol-infernal") or an objective without kinds ("obj:dota2-roshan"). */
    fun objImage(key: String): String? = objImg[key]

    /**
     * The spells a player takes into a game: junglers always the jungle spell (Smite, Retribution);
     * League and Wild Rift players Flash and a second spell that fits the position; MLBB and HoK
     * players one spell that fits the position and class.
     */
    fun choose(w: World?, titleId: String, jungler: Boolean, laneIdx: Int, support: Boolean, carry: Boolean, cls: String, rng: Rng): List<SpellDef> {
        val n = slots(titleId)
        val pool = of(w, titleId)
        if (n == 0 || pool.isEmpty()) return emptyList()
        fun pick(vararg prefer: Pair<String, Double>): SpellDef? {
            val opts = prefer.mapNotNull { (e, wt) -> pool.filter { it.effect == e }.takeIf { it.isNotEmpty() }?.let { it to wt } }
            val news = pool.filter { it.custom }
            if (news.isNotEmpty() && rng.chance(0.25)) return news[rng.int(0, news.size - 1)]
            if (opts.isEmpty()) return pool[rng.int(0, pool.size - 1)]
            val (list, _) = rng.weighted(opts) { it.second }
            return list[rng.int(0, list.size - 1)]
        }
        val c = cls.lowercase()
        val out = ArrayList<SpellDef>()
        if (jungler) pool.firstOrNull { it.effect == "smite" }?.let { out.add(it) }
        if (n == 2) {
            pool.firstOrNull { it.effect == "blink" }?.let { if (it !in out) out.add(it) }
            while (out.size < 2) {
                val s = when {
                    support -> pick("ignite" to 3.0, "weaken" to 2.5, "heal" to 1.0, "shield" to 0.5)
                    carry -> pick("heal" to 4.0, "shield" to 1.5, "cleanse" to 1.5, "ignite" to 0.5)
                    laneIdx == 0 -> pick("teleport" to 5.0, "ignite" to 2.0, "haste" to 1.0)
                    else -> pick("teleport" to 3.0, "ignite" to 2.5, "shield" to 1.0, "cleanse" to 0.7, "haste" to 0.5)
                } ?: break
                if (s !in out) out.add(s) else if (pool.size <= out.size) break
            }
        } else if (out.isEmpty()) {
            val s = when {
                support -> pick("blink" to 2.0, "weaken" to 1.5, "reflect" to 1.0, "shield" to 1.0, "heal" to 1.0, "stun" to 1.0)
                carry -> pick("blink" to 3.0, "frenzy" to 1.5, "shield" to 1.0, "cleanse" to 1.0, "execute" to 1.0)
                laneIdx == 0 -> pick("blink" to 1.0, "reflect" to 2.0, "stun" to 1.5, "execute" to 1.0, "frenzy" to 1.0, "teleport" to 0.5)
                c.contains("mage") -> pick("blink" to 3.0, "poke" to 1.5, "cleanse" to 1.0, "frenzy" to 0.5)
                else -> pick("blink" to 2.0, "execute" to 1.0, "cleanse" to 1.0, "frenzy" to 1.0)
            }
            s?.let { out.add(it) }
        }
        return out.take(n)
    }
}

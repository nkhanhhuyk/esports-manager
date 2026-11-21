package dev.uem.em.sim

import dev.uem.em.data.DataSource
import dev.uem.em.titles.CharDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * The simulation ability model (assets/abilities.json, built from public game data): every
 * ability has a slot, targeting, range (map units), cooldowns by rank, cost and a list of typed
 * effects. The MOBA engine casts these: damage, crowd control, heals, shields, dashes, stealth,
 * immunity, buffs and so on change the fight. Characters without a sourced kit (some Honor of
 * Kings heroes, fictional patch releases) get a kit from their class template, marked approximate.
 */
object Abilities {
    // Effect codes.
    const val DMG = 0; const val DOT = 1; const val STUN = 2; const val KNOCKUP = 3; const val KNOCKBACK = 4; const val SUPPRESS = 5
    const val CHARM = 6; const val TAUNT = 7; const val FEAR = 8; const val ROOT = 9; const val SILENCE = 10; const val SLOW = 11; const val BLIND = 12
    const val HEAL = 13; const val SHIELD = 14; const val DASH = 15; const val STEALTH = 16; const val IMMUNE = 17; const val BUFF_AS = 18; const val BUFF_MS = 19
    const val DMG_REDUCE = 20; const val SUMMON = 21; const val REVEAL = 22; const val EXECUTE = 23; const val TERRAIN = 24; const val GLOBAL = 25; const val REVIVE = 26
    val FX_NAMES = listOf("dmg", "dot", "stun", "knockup", "knockback", "suppress", "charm", "taunt", "fear", "root", "silence", "slow", "blind",
        "heal", "shield", "dash", "stealth", "immune", "buff_as", "buff_ms", "dmg_reduce", "summon", "reveal", "execute", "terrain", "global", "revive")
    val FX_LABEL = listOf("Damage", "Damage over time", "Stun", "Knock-up", "Knock-back", "Suppression", "Charm", "Taunt", "Fear", "Root", "Silence", "Slow", "Blind",
        "Heal", "Shield", "Dash", "Stealth", "Immunity", "Attack speed", "Move speed", "Damage reduction", "Summon", "Reveal", "Execute", "Terrain", "Global", "Revive")
    private val FX_CODE = FX_NAMES.withIndex().associate { it.value to it.index }
    /** Effects that stop a unit acting (stun-like). */
    fun hard(k: Int) = k in STUN..FEAR

    // Targeting.
    const val T_SELF = 0; const val T_SELF_AOE = 1; const val T_UNIT = 2; const val T_LINE = 3; const val T_AREA = 4; const val T_CONE = 5; const val T_ALLY = 6; const val T_GLOBAL = 7
    val TARGETS = listOf("self", "self_aoe", "unit", "line", "area", "cone", "ally", "global")
    val TARGET_LABEL = listOf("Self", "Around self", "Targeted", "Skillshot (line)", "Area", "Cone", "Ally", "Global")

    // AI usage.
    val AI = listOf("poke", "burst", "finish", "control", "engage", "protect", "survive", "mobility", "buff", "zone", "utility")
    const val AI_POKE = 0; const val AI_BURST = 1; const val AI_FINISH = 2; const val AI_CONTROL = 3; const val AI_ENGAGE = 4; const val AI_PROTECT = 5
    const val AI_SURVIVE = 6; const val AI_MOBILITY = 7; const val AI_BUFF = 8; const val AI_ZONE = 9; const val AI_UTILITY = 10

    class Fx(val k: Int, val dmgType: Int, val p: Float, val v: Float, val t: Float, val ally: Boolean)
    class Ability(
        val id: String, val slot: String, val name: String, val passive: Boolean, val ult: Boolean, val target: Int, val range: Float,
        val cd: FloatArray, val cost: FloatArray?, val fx: List<Fx>, val ai: Int, val vfx: String, val approx: List<String>,
    ) {
        val hardCc = fx.any { hard(it.k) }
        val cc = fx.any { hard(it.k) || it.k == ROOT || it.k == SILENCE || it.k == SLOW }
        val damage = fx.firstOrNull { it.k == DMG }
        fun cdAt(rank: Int) = if (cd.isEmpty()) 10f else cd[(rank - 1).coerceIn(0, cd.size - 1)]
        fun costAt(rank: Int) = cost?.let { if (it.isEmpty()) 0f else it[(rank - 1).coerceIn(0, it.size - 1)] } ?: 0f
    }

    class Kit(val charId: String, val src: String, val basicRange: Float, val ranged: Boolean, val abilities: List<Ability>, val template: Boolean) {
        val passive = abilities.firstOrNull { it.passive && !it.ult }
        /** Castable abilities: basic slots then the ultimate (at most 4). */
        val actives = abilities.filter { !it.passive || it.ult }.sortedBy { if (it.ult) 9 else "QWED".indexOf(it.slot.first()) }.take(4)
    }

    /** Valorant agent ability (round utility). */
    class ValAbility(val slot: String, val name: String, val kinds: List<String>, val cost: Int, val ultPoints: Int, val charges: Int)

    @Volatile private var root: JsonObject? = null
    private val kits = ConcurrentHashMap<String, Kit>()
    private val valKits = ConcurrentHashMap<String, List<ValAbility>>()
    var sources: Map<String, String> = emptyMap(); private set
    var method: String = ""; private set

    fun init(src: DataSource) {
        if (root != null) return
        val text = try { src.read("abilities.json") } catch (e: Exception) { return }
        val j = Json.parseToJsonElement(text).jsonObject
        sources = j["sources"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        method = j["method"]?.jsonPrimitive?.contentOrNull ?: ""
        root = j["chars"]!!.jsonObject
    }

    fun hasSourced(charId: String) = root?.containsKey(charId) == true

    private fun floats(o: JsonObject, k: String): FloatArray? = o[k]?.let { e -> runCatching { e.jsonArray.map { it.jsonPrimitive.floatOrNull ?: 0f }.toFloatArray() }.getOrNull() }

    private fun parse(charId: String, o: JsonObject): Kit {
        val basic = o["basic"]?.jsonObject
        val abil = o["abilities"]!!.jsonArray.map { a0 ->
            val a = a0.jsonObject
            val fx = a["fx"]?.jsonArray?.mapNotNull { f0 ->
                val f = f0.jsonObject
                val k = FX_CODE[f["k"]!!.jsonPrimitive.content] ?: return@mapNotNull null
                val d = when (f["d"]?.jsonPrimitive?.contentOrNull) { "magic" -> 1; "true" -> 2; else -> 0 }
                Fx(k, d, f["p"]?.jsonPrimitive?.floatOrNull ?: 1f, f["v"]?.jsonPrimitive?.floatOrNull ?: 0f, f["t"]?.jsonPrimitive?.floatOrNull ?: 0f, f["ally"]?.jsonPrimitive?.booleanOrNull ?: false)
            } ?: emptyList()
            val type = a["type"]?.jsonPrimitive?.contentOrNull ?: "active"
            Ability(a["id"]!!.jsonPrimitive.content, a["slot"]!!.jsonPrimitive.content, a["name"]!!.jsonPrimitive.content, type == "passive", type == "ultimate",
                TARGETS.indexOf(a["target"]?.jsonPrimitive?.contentOrNull ?: "unit").coerceAtLeast(0), a["range"]?.jsonPrimitive?.floatOrNull ?: 40f,
                floats(a, "cd") ?: floatArrayOf(10f), floats(a, "cost"), fx, AI.indexOf(a["ai"]?.jsonPrimitive?.contentOrNull ?: "utility").coerceAtLeast(0),
                a["vfx"]?.jsonPrimitive?.contentOrNull ?: "beam", a["approx"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
        }
        return Kit(charId, o["src"]?.jsonPrimitive?.contentOrNull ?: "", basic?.get("range")?.jsonPrimitive?.floatOrNull ?: 20f, basic?.get("ranged")?.jsonPrimitive?.booleanOrNull ?: false, abil, false)
    }

    /** The kit a character plays with (sourced, else its class template). */
    fun kit(charId: String?, char: CharDef?): Kit {
        val id = charId ?: "none"
        return kits.getOrPut(id) {
            val o = root?.get(id)?.jsonObject
            val k = o?.let { runCatching { parse(id, it) }.getOrNull() }
            normalise(if (k != null && k.actives.size >= 2) k else template(id, char), char)
        }
    }

    fun valorant(charId: String?): List<ValAbility> {
        val id = charId ?: return emptyList()
        return valKits.getOrPut(id) {
            val o = root?.get(id)?.jsonObject ?: return@getOrPut emptyList()
            o["abilities"]!!.jsonArray.map { a0 -> val a = a0.jsonObject
                ValAbility(a["slot"]!!.jsonPrimitive.content, a["name"]!!.jsonPrimitive.content, a["kinds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                    a["cost"]?.jsonPrimitive?.intOrNull ?: 0, a["ultPoints"]?.jsonPrimitive?.intOrNull ?: 0, a["charges"]?.jsonPrimitive?.intOrNull ?: 1) }
        }
    }

    /**
     * Keep every kit within a power band for its class: damage-dealing abilities the text parse
     * found no damage in get a modest damage effect (marked approximate), and each kit's sustained
     * ability damage is scaled part of the way toward its class target. Relative differences
     * between characters stay; outliers from parsing do not decide matches.
     */
    private fun normalise(k: Kit, char: CharDef?): Kit {
        val c = (char?.cls ?: "").lowercase()
        val dmgClass = Regex("marksman|mage|assassin|fighter|warrior|carry|nuker|sniper|bruiser").containsMatchIn(c)
        val target = 0.43f * when {
            Regex("mage|nuker").containsMatchIn(c) -> 1.4f
            Regex("assassin").containsMatchIn(c) -> 1.3f
            Regex("tank|guardian").containsMatchIn(c) -> 0.7f
            Regex("support|enchanter|healer").containsMatchIn(c) -> 0.65f
            Regex("marksman|sniper|carry").containsMatchIn(c) -> 0.95f
            else -> 1.1f
        }
        val offensive = setOf(AI_POKE, AI_BURST, AI_FINISH, AI_ENGAGE, AI_CONTROL, AI_ZONE)
        val filled = k.abilities.map { a ->
            val empty = a.fx.isEmpty() || a.ai == AI_UTILITY && a.fx.none { it.k == DMG || hard(it.k) || it.k == HEAL || it.k == SHIELD }
            if ((a.passive && !a.ult) || a.damage != null || !(dmgClass || empty) || (a.ai !in offensive && !empty)) a
            else Ability(a.id, a.slot, a.name, a.passive, a.ult, a.target, a.range, a.cd, a.cost, a.fx + Fx(DMG, 0, if (empty) 0.8f else 0.6f, 0f, 0f, false),
                if (empty) (if (a.ult) AI_BURST else AI_POKE) else a.ai, a.vfx, a.approx + "damage added (description named none)")
        }
        val acts = filled.filter { !it.passive || it.ult }
        var d = 0f
        for (a in acts) { val dm = a.damage ?: continue; d += dm.p * (if (a.ult) 3.5f else 1f) / a.cd.average().toFloat().coerceAtLeast(3f) }
        if (d <= 0f) return Kit(k.charId, k.src, k.basicRange, k.ranged, filled, k.template)
        val f = Math.pow((target / d).toDouble(), 0.7).toFloat().coerceIn(0.5f, 2f)
        val scaled = filled.map { a ->
            if (a.damage == null) a
            else Ability(a.id, a.slot, a.name, a.passive, a.ult, a.target, a.range, a.cd, a.cost, a.fx.map { x -> if (x.k == DMG) Fx(x.k, x.dmgType, x.p * f, x.v, x.t, x.ally) else x }, a.ai, a.vfx, a.approx)
        }
        return Kit(k.charId, k.src, k.basicRange, k.ranged, scaled, k.template)
    }

    private fun fx(k: Int, d: Int = 0, p: Float = 1f, v: Float = 0f, t: Float = 0f, ally: Boolean = false) = Fx(k, d, p, v, t, ally)
    private fun ab(cid: String, slot: String, name: String, target: Int, range: Float, cd: Float, ai: Int, vararg f: Fx) =
        Ability("$cid-${slot.lowercase()}", slot, name, false, slot == "R", target, range, floatArrayOf(cd), null, f.toList(), ai, if (target == T_LINE) "projectile" else if (target == T_AREA || target == T_SELF_AOE) "area" else "beam", listOf("class template (no sourced kit)"))

    /** A class template kit (approximate). */
    fun template(cid: String, char: CharDef?): Kit {
        val c = (char?.cls ?: "").lowercase()
        val ranged = Regex("marksman|mage|sniper|nuker|support|enchanter").containsMatchIn(c)
        val list = when {
            Regex("tank|guardian").containsMatchIn(c) -> listOf(
                ab(cid, "Q", "Charge", T_LINE, 45f, 10f, AI_ENGAGE, fx(DASH, v = 35f), fx(DMG, p = 0.7f), fx(KNOCKUP, t = 0.8f)),
                ab(cid, "W", "Bulwark", T_SELF, 0f, 14f, AI_SURVIVE, fx(SHIELD, p = 1.2f), fx(DMG_REDUCE, v = 0.25f, t = 3f)),
                ab(cid, "E", "Shockwave", T_SELF_AOE, 24f, 11f, AI_CONTROL, fx(DMG, p = 0.6f), fx(SLOW, v = 0.35f, t = 2f)),
                ab(cid, "R", "Earthshaker", T_SELF_AOE, 28f, 60f, AI_ENGAGE, fx(DMG, p = 1.2f), fx(STUN, t = 1.5f)))
            Regex("marksman|sniper|carry").containsMatchIn(c) -> listOf(
                ab(cid, "Q", "Piercing Shot", T_LINE, 60f, 7f, AI_POKE, fx(DMG, p = 1.0f)),
                ab(cid, "W", "Rapid Fire", T_SELF, 0f, 12f, AI_BUFF, fx(BUFF_AS, v = 0.4f, t = 4f)),
                ab(cid, "E", "Tumble", T_LINE, 30f, 10f, AI_MOBILITY, fx(DASH, v = 30f)),
                ab(cid, "R", "Barrage", T_LINE, 90f, 50f, AI_BURST, fx(DMG, p = 2.2f), fx(SLOW, v = 0.3f, t = 1.5f)))
            Regex("mage|nuker").containsMatchIn(c) -> listOf(
                ab(cid, "Q", "Arcane Bolt", T_LINE, 60f, 6f, AI_POKE, fx(DMG, 1, p = 1.0f)),
                ab(cid, "W", "Binding Sigil", T_AREA, 55f, 12f, AI_CONTROL, fx(DMG, 1, p = 0.8f), fx(ROOT, t = 1.4f)),
                ab(cid, "E", "Flux Field", T_AREA, 55f, 10f, AI_ZONE, fx(DMG, 1, p = 0.7f), fx(SLOW, v = 0.4f, t = 2f)),
                ab(cid, "R", "Cataclysm", T_AREA, 60f, 55f, AI_BURST, fx(DMG, 1, p = 2.6f)))
            Regex("assassin").containsMatchIn(c) -> listOf(
                ab(cid, "Q", "Lunge", T_UNIT, 40f, 8f, AI_ENGAGE, fx(DASH, v = 35f), fx(DMG, p = 1.0f)),
                ab(cid, "W", "Shadow Step", T_SELF, 0f, 16f, AI_MOBILITY, fx(STEALTH, t = 2.5f), fx(BUFF_MS, v = 0.3f, t = 2f)),
                ab(cid, "E", "Rend", T_UNIT, 20f, 7f, AI_POKE, fx(DMG, p = 1.1f)),
                ab(cid, "R", "Execution", T_UNIT, 35f, 45f, AI_FINISH, fx(DMG, p = 2.4f), fx(EXECUTE)))
            Regex("support|enchanter|healer").containsMatchIn(c) -> listOf(
                ab(cid, "Q", "Mend", T_ALLY, 50f, 9f, AI_PROTECT, fx(HEAL, p = 1.0f, ally = true)),
                ab(cid, "W", "Ward of Light", T_ALLY, 50f, 12f, AI_PROTECT, fx(SHIELD, p = 1.0f, ally = true)),
                ab(cid, "E", "Snare", T_LINE, 55f, 12f, AI_CONTROL, fx(DMG, 1, p = 0.6f), fx(STUN, t = 1.2f)),
                ab(cid, "R", "Sanctuary", T_SELF_AOE, 35f, 70f, AI_PROTECT, fx(HEAL, p = 2.0f, ally = true), fx(DMG_REDUCE, v = 0.3f, t = 3f)))
            else -> listOf(
                ab(cid, "Q", "Cleave", T_CONE, 25f, 6f, AI_POKE, fx(DMG, p = 1.0f)),
                ab(cid, "W", "Second Wind", T_SELF, 0f, 14f, AI_SURVIVE, fx(HEAL, p = 1.0f)),
                ab(cid, "E", "Leap", T_UNIT, 40f, 11f, AI_ENGAGE, fx(DASH, v = 35f), fx(SLOW, v = 0.3f, t = 1.5f)),
                ab(cid, "R", "Uplift", T_SELF_AOE, 26f, 60f, AI_ENGAGE, fx(DMG, p = 1.6f), fx(KNOCKUP, t = 1f)))
        }
        return Kit(cid, "class template (approximate)", if (ranged) 37f else 12f, ranged, list, true)
    }
}

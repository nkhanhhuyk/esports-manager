package dev.uem.em.data

import dev.uem.em.core.Rng
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads bundled data files (Android assets in the app, the repository files in JVM tests). */
fun interface DataSource {
    fun read(name: String): String
}

class Region(val id: String, val name: String, val short: String, val macro: String, val economy: Double)
class Country(val code: String, val name: String, val region: String, val culture: String, val langs: List<String>, val moba: Int, val shooter: Int, val weight: Int)
class Culture(val first: List<String>, val last: List<String>, val eastern: Boolean)

/** Static tables exported from the web version (assets/gamedata.json, scripts/native-export.ts). */
object GameData {
    lateinit var regions: Map<String, Region>
        private set
    lateinit var countries: Map<String, Country>
        private set
    lateinit var cultures: Map<String, Culture>
        private set
    private lateinit var macros: Map<String, String>
    private lateinit var handleRoots: List<String>
    private lateinit var handlePrefix: List<String>
    private lateinit var handleSuffix: List<String>
    private lateinit var orgA: List<String>
    private lateinit var orgB: List<String>
    private lateinit var orgSuffix: List<String>
    @Volatile private var loaded = false

    @Synchronized
    fun init(src: DataSource) {
        if (loaded) return
        val j = Json.parseToJsonElement(src.read("gamedata.json")).jsonObject
        fun strs(a: JsonArray) = a.map { it.jsonPrimitive.content }
        regions = j["regions"]!!.jsonArray.map { it.jsonObject }.associate { o ->
            val id = o.s("id")
            id to Region(id, o.s("name"), o.s("short"), o.s("macro"), o["economy"]!!.jsonPrimitive.double)
        }
        macros = j["macros"]!!.jsonObject.mapValues { it.value.jsonObject.s("name") }
        countries = j["countries"]!!.jsonArray.map { it.jsonObject }.associate { o ->
            val code = o.s("code")
            code to Country(code, o.s("name"), o.s("region"), o.s("culture"), strs(o["langs"]!!.jsonArray), o["moba"]!!.jsonPrimitive.int, o["shooter"]!!.jsonPrimitive.int, o["weight"]!!.jsonPrimitive.int)
        }
        cultures = j["cultures"]!!.jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            Culture(strs(o["first"]!!.jsonArray), strs(o["last"]!!.jsonArray), o["eastern"]?.jsonPrimitive?.booleanOrNull ?: false)
        }
        val h = j["handles"]!!.jsonObject
        handleRoots = strs(h["roots"]!!.jsonArray)
        handlePrefix = strs(h["prefix"]!!.jsonArray)
        handleSuffix = strs(h["suffix"]!!.jsonArray)
        val ow = j["orgWords"]!!.jsonObject
        orgA = strs(ow["a"]!!.jsonArray)
        orgB = strs(ow["b"]!!.jsonArray)
        orgSuffix = strs(ow["suffix"]!!.jsonArray)
        loaded = true
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    /** A league region id (single region, or macro with optional ".”-separated member list). */
    fun expand(id: String): List<String> {
        if (regions.containsKey(id)) return listOf(id)
        val macro = id.substringBefore('-')
        if (!macros.containsKey(macro)) return emptyList()
        return if ('-' in id) id.substringAfter('-').split('.') else regions.values.filter { it.macro == macro }.map { it.id }
    }

    fun regionShort(id: String): String = regions[id]?.short ?: id.substringBefore('-')
    fun regionName(id: String): String = regions[id]?.name ?: macros[id.substringBefore('-')] ?: id
    fun economy(id: String): Double = expand(id).let { r -> if (r.isEmpty()) 0.5 else r.sumOf { regions[it]!!.economy } / r.size }
    fun regionOf(nat: String) = countries[nat]?.region ?: "NA"

    fun personName(rng: Rng, nat: String): Pair<String, String> {
        val c = cultures[countries[nat]?.culture ?: "english"] ?: cultures["english"]!!
        return rng.pick(c.first) to rng.pick(c.last)
    }

    fun isEastern(nat: String) = cultures[countries[nat]?.culture ?: ""]?.eastern ?: false

    private val LEET = mapOf('a' to '4', 'e' to '3', 'i' to '1', 'o' to '0', 's' to '5')

    fun makeHandle(rng: Rng, used: MutableSet<String>): String {
        repeat(40) {
            val root = rng.pick(handleRoots)
            val r = rng.next()
            var h = when {
                r < 0.4 -> root
                r < 0.52 -> root + rng.int(1, 99)
                r < 0.62 -> rng.pick(handlePrefix) + root
                r < 0.72 -> root + rng.pick(handleSuffix)
                r < 0.8 -> root.lowercase()
                r < 0.86 -> Regex("[aeios]").find(root)?.let { m -> root.replaceRange(m.range, (LEET[m.value[0]] ?: m.value[0]).toString()) } ?: root
                r < 0.93 -> {
                    val b = rng.pick(handleRoots)
                    root.take(maxOf(2, (root.length + 1) / 2)) + b.substring(b.length / 2).lowercase()
                }
                else -> root.uppercase()
            }
            if (h.length > 14) h = h.take(14)
            if (used.add(h.lowercase())) return h
        }
        val base = rng.pick(handleRoots)
        var n = 2
        while (!used.add("$base$n".lowercase())) n++
        return "$base$n"
    }

    fun makeOrgName(rng: Rng, used: MutableSet<String>): Pair<String, String> {
        repeat(60) {
            val a = rng.pick(orgA)
            val b = rng.pick(orgB)
            val r = rng.next()
            val name = when {
                r < 0.55 -> "$a $b"
                r < 0.75 -> "Team $a"
                r < 0.9 -> "$b ${rng.pick(orgSuffix)}"
                else -> a + b.lowercase()
            }
            if (!used.add(name.lowercase())) return@repeat
            val words = name.removePrefix("Team ").split(" ")
            var short = if (words.size > 1) words.joinToString("") { it.take(1) }.uppercase() else name.removePrefix("Team ").take(3).uppercase()
            if (short.length < 2) short = name.take(3).uppercase()
            return name to short.take(4)
        }
        val n = "Squad ${rng.int(100, 999)}"
        used.add(n.lowercase())
        return n to "SQD"
    }

    /** Weighted nationality for a generated player of a title family in some regions. */
    fun pickNationality(rng: Rng, regionIds: List<String>, moba: Boolean, pop: Map<String, Int>): String {
        val c = countries.values.filter { it.region in regionIds }
        if (c.isEmpty()) return "US"
        return rng.weighted(c) { x -> x.weight * Math.pow((if (moba) x.moba else x.shooter) / 60.0, 2.0) * (0.3 + (pop[x.region] ?: 30) / 100.0) }.code
    }

    fun pickCountry(rng: Rng, region: String): String {
        val regs = expand(region)
        val list = countries.values.filter { it.region in regs }
        if (list.isEmpty()) return "US"
        if (regs.size == 1) return list.maxBy { it.weight }.code
        return rng.weighted(list) { it.weight.toDouble() }.code
    }
}

package dev.uem.em.mgmt

import dev.uem.em.data.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Real rosters from before the save (pastRosters.json), by year, for the teams of the real-data snapshot:
 * League of Legends from Games of Legends season pages and Leaguepedia tenures, Dota 2 from OpenDota pro
 * matches, VALORANT from vlr.gg transactions, CS2 from Valve Regional Standings and Major rosters, MLBB
 * from the MPL sites, Wild Rift from the 2021 wiki. Read once, on first use.
 */
object PastRosters {
    /** One person in one year: position or staff role, flag, months when known, and where it comes from. */
    class Entry(val handle: String, val role: String, val nat: String, val from: String, val to: String, val sources: List<Int>) {
        /** "Mar – Nov", "from Mar", "until Nov" or "" (the whole year, or months unknown). */
        fun months(): String {
            fun m(s: String) = if (s.length >= 7) MONTHS.getOrElse(s.substring(5, 7).toInt() - 1) { "" } else ""
            val a = m(from); val b = m(to)
            return when {
                a.isNotEmpty() && b.isNotEmpty() -> if (a == b) a else "$a – $b"
                a.isNotEmpty() -> "from $a"
                b.isNotEmpty() -> "until $b"
                else -> ""
            }
        }
    }
    class Year(val year: Int, val players: List<Entry>, val staff: List<Entry>, val names: List<String>)

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    @Volatile private var src: DataSource? = null
    @Volatile private var root: JsonObject? = null
    var sources: List<String> = emptyList(); private set

    fun init(s: DataSource) { src = s; root = null }

    private fun teams(): JsonObject {
        root?.let { return it }
        synchronized(this) {
            root?.let { return it }
            val text = runCatching { src?.read("pastRosters.json") }.getOrNull()
            val r = text?.let { t -> runCatching { Json.parseToJsonElement(t).jsonObject }.getOrNull() }
            sources = r?.get("sources")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val out = r?.get("teams")?.jsonObject ?: JsonObject(emptyMap())
            root = out
            return out
        }
    }

    /** The real rosters of a team (by its name in the snapshot), newest year first; empty when none are known. */
    fun of(titleId: String, teamName: String): List<Year> {
        val t = teams()["$titleId|${Careers.norm(teamName)}"]?.jsonObject ?: return emptyList()
        val ys = t["y"]?.jsonObject ?: return emptyList()
        fun entries(o: JsonObject, k: String) = o[k]?.jsonArray?.map { e ->
            val a = e.jsonArray
            fun s(i: Int) = a.getOrNull(i)?.jsonPrimitive?.content ?: ""
            Entry(s(0), s(1), s(2), s(3), s(4), a.getOrNull(5)?.jsonArray?.map { it.jsonPrimitive.content.toInt() } ?: emptyList())
        } ?: emptyList()
        return ys.entries.mapNotNull { (y, v) ->
            val o = v.jsonObject
            y.toIntOrNull()?.let { Year(it, entries(o, "p"), entries(o, "s"), o["as"]?.jsonArray?.map { n -> n.jsonPrimitive.content } ?: emptyList()) }
        }.sortedByDescending { it.year }
    }

    fun sourceName(i: Int): String { teams(); return sources.getOrElse(i) { "" } }

    /** Staff roles as the data names them. */
    fun staffLabel(role: String): String = when (role) {
        "head-coach" -> "head coach"; "assistant", "assistant-coach" -> "assistant coach"; "strategic-coach" -> "draft coach"
        "positional-coach" -> "positional coach"; "performance" -> "performance coach"; "" -> "staff"; else -> role.replace('-', ' ')
    }
}

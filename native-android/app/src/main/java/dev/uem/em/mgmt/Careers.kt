package dev.uem.em.mgmt

import dev.uem.em.data.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer

/**
 * Real career movements from before the save (careers.json): every team spell of the real players and
 * coaches, with months and roles where the wikis record them (League of Legends: Leaguepedia), otherwise
 * a team and the years the snapshot saw them there (current roster, last team, title rosters).
 * Read once, on first use.
 */
object Careers {
    class Spell(val team: String, val from: String, val to: String, val role: String, val source: String) {
        /** "Feb 2013 – Nov 2013", "2018 – 2020", "Dec 2019 – now". */
        fun period(): String {
            fun d(s: String) = if (s.length >= 7) MONTHS.getOrElse(s.substring(5, 7).toInt() - 1) { "" } + " " + s.substring(0, 4) else s
            val a = d(from)
            val b = if (to.isEmpty()) (if (source == "wiki") "now" else "") else d(to)
            return when {
                a.isEmpty() -> b
                b.isEmpty() || a == b -> a
                else -> "$a – $b"
            }
        }
        val exact get() = source == "wiki"
        val isStaff get() = role in STAFF
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val STAFF = setOf("head-coach", "coach", "assistant", "strategic-coach", "analyst", "positional-coach", "manager", "owner", "performance")

    @Volatile private var src: DataSource? = null
    @Volatile private var map: Map<String, List<Spell>>? = null
    var sources: Map<String, String> = emptyMap(); private set

    fun init(s: DataSource) { src = s; map = null }

    /** As the data script writes keys: accents dropped, lower case, letters and digits only. */
    fun norm(h: String): String = Normalizer.normalize(h, Normalizer.Form.NFKD).lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    private fun load(): Map<String, List<Spell>> {
        map?.let { return it }
        synchronized(this) {
            map?.let { return it }
            val text = runCatching { src?.read("careers.json") }.getOrNull()
            val out = HashMap<String, List<Spell>>()
            if (text != null) runCatching {
                val root = Json.parseToJsonElement(text).jsonObject
                sources = root["sources"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                for ((k, v) in root["careers"]!!.jsonObject) out[k] = v.jsonArray.map { e ->
                    val a = e.jsonArray
                    fun s(i: Int) = a.getOrNull(i)?.jsonPrimitive?.content?.takeIf { it != "null" } ?: ""
                    Spell(s(0), s(1), s(2), s(3), s(4))
                }
            }
            map = out
            return out
        }
    }

    /** A real person's spells before the save, oldest first (empty for fictional people). */
    fun of(titleId: String, handle: String): List<Spell> = if (handle.isBlank()) emptyList() else load()["$titleId|${norm(handle)}"] ?: emptyList()

    fun sourceNote(spells: List<Spell>): String = when {
        spells.isEmpty() -> ""
        spells.all { it.exact } -> "Leaguepedia roster records (months and roles)."
        spells.any { it.exact } -> "Leaguepedia roster records and the real-data snapshot."
        else -> "From the real-data snapshot: the teams this person was seen with (current roster, last team, title rosters), by year — not a complete record."
    }
}

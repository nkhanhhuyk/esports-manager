package dev.uem.em.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.uem.em.data.DataSource
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Base stats and skills of characters and weapons (assets/charinfo.json, from official data). */
object GameInfo {
    class Info(val source: String, val stats: List<Pair<String, String>>, val skills: List<Triple<String, String, String>>)
    class Weapon(val id: String, val titleId: String, val name: String, val category: String, val cost: Int, val stats: List<Pair<String, String>>, val dps: Double, val headshot: Double, val range: Double, val source: String)

    private var chars: Map<String, Info> = emptyMap()
    private var weapons: Map<String, Weapon> = emptyMap()

    @Synchronized
    fun init(src: DataSource) {
        if (chars.isNotEmpty()) return
        val j = runCatching { Json.parseToJsonElement(src.read("charinfo.json")).jsonObject }.getOrNull() ?: return
        fun rows(a: JsonArray?) = a?.map { r -> r.jsonArray.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.content } } ?: emptyList()
        chars = j["chars"]!!.jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            Info(o["source"]?.jsonPrimitive?.contentOrNull ?: "", rows(o["stats"]?.jsonArray), o["skills"]?.jsonArray?.map { r -> r.jsonArray.let { Triple(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content, it[2].jsonPrimitive.content) } } ?: emptyList())
        }
        weapons = j["weapons"]!!.jsonObject.mapValues { (k, v) ->
            val o = v.jsonObject
            Weapon(k, k.substringBefore('|'), o["name"]!!.jsonPrimitive.content, o["category"]!!.jsonPrimitive.content, o["cost"]?.jsonPrimitive?.intOrNull ?: 0, rows(o["stats"]?.jsonArray),
                o["dps"]?.jsonPrimitive?.doubleOrNull ?: 0.0, o["headshot"]?.jsonPrimitive?.doubleOrNull ?: 0.0, o["range"]?.jsonPrimitive?.doubleOrNull ?: 0.0, o["source"]?.jsonPrimitive?.contentOrNull ?: "")
        }
    }

    fun char(id: String) = chars[id]
    fun weapon(id: String) = weapons[id]
    fun weapons(titleId: String) = weapons.values.filter { it.titleId == titleId && it.category != "Melee" }
}

/** Which character / weapon sheet is open (a tap on any portrait opens it). */
object Sheets {
    var open by mutableStateOf<Pair<String, String>?>(null)
    var world: World? = null

    fun openChar(id: String) { open = "char" to id }
    fun openWeapon(id: String) { open = "weapon" to id }
    fun titleOf(charId: String) = Catalogue.IDS.firstOrNull { charId.startsWith("$it-") } ?: Catalogue.IDS.firstOrNull { Catalogue.get(it).charIndex.containsKey(charId) }
    fun charClass(id: String) = titleOf(id)?.let { Catalogue.get(it).charIndex[id]?.cls }
}

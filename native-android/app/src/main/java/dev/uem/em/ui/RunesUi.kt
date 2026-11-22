package dev.uem.em.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.World
import dev.uem.em.titles.Neutrals
import dev.uem.em.titles.RuneDef
import dev.uem.em.titles.Runes
import dev.uem.em.titles.TitleDef

/** The box-score label of a rune page: keystone (LoL, Wild Rift), emblem set and core talent (MLBB), arcana (HoK), facet (Dota 2). */
fun runeLabel(w: World, t: TitleDef, ids: List<String>): String {
    val rs = ids.mapNotNull { Runes.get(w, it) }
    if (rs.isEmpty()) return ""
    return when (t.id) {
        "lol", "wildrift" -> rs.firstOrNull { it.slot == "keystone" }?.name ?: rs.first().name
        "mlbb" -> listOfNotNull(rs.firstOrNull { it.slot == "set" }?.name?.removePrefix("Custom "), rs.firstOrNull { it.slot == "core" }?.name).joinToString(" · ")
        "hok" -> rs.filter { it.slot == "arcana" }.joinToString(" · ") { it.name }
        "dota2" -> rs.firstOrNull { it.slot == "facet" }?.name ?: "${rs.size} talents"
        else -> rs.first().name
    }
}

/** Every rune / emblem / arcana of a game with what it does in a match (patch changes included). */
@Composable
fun RunesCard(w: World, t: TitleDef) {
    val all = Runes.of(w, t.id)
    if (all.isEmpty()) return
    val kind = Runes.KIND[t.id] ?: "Runes"
    Card("$kind (${t.short})") {
        when (t.id) {
            "dota2" -> {
                Muted("Every hero has its own talent tree (a choice at levels 10, 15, 20 and 25) and facets: ${all.count { it.slot.startsWith("level") }} talents and ${all.count { it.slot == "facet" }} facets from Valve's game files (dotaconstants). Players pick talents that fit their position; the box score shows each hero's facet.", 11)
                for (r in all.filter { it.custom }) RuneRow(w, r)
            }
            else -> {
                Muted(when (t.id) {
                    "mlbb" -> "Each hero takes an emblem set (its attributes), two standard talents and one core talent."
                    "hok" -> "Each hero's arcana page holds ten each of three arcana; the numbers are for one arcana at level 5."
                    else -> "Each champion takes a keystone and minor runes from a primary tree, plus two minor runes from a second tree."
                } + " Stats are what the match engine applies; patches change them and can add new ones.", 11)
                for ((g, list) in all.groupBy { it.group }) Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(g, color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    for (r in list.sortedBy { it.slot }) RuneRow(w, r)
                }
            }
        }
    }
}

@Composable
private fun RuneRow(w: World, r: RuneDef) {
    val mod = Runes.mod(w, r.id)
    Column(Modifier.padding(top = 3.dp)) {
        Text(r.name + (if (r.custom) "  NEW" else "") + "  · " + slotName(r.slot), color = if (r.custom) Ui.gold else Ui.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        if (r.desc.isNotBlank()) Muted(r.desc + (if (r.approx) " (summary)" else ""), 10)
        Text("In the sim: " + Neutrals.statText(r.stats, mod) + (if (mod != 1.0) "  (patched ${if (mod > 1) "+" else ""}${((mod - 1) * 100).toInt()}%)" else ""), color = Ui.text, fontSize = 10.sp)
    }
}

private fun slotName(s: String) = when {
    s == "keystone" -> "keystone"; s.startsWith("row") -> "minor rune"; s == "set" -> "emblem set"; s == "core" -> "core talent"
    s.startsWith("talent") -> "standard talent"; s == "arcana" -> "arcana"; s.startsWith("level") -> "talent (level ${s.removePrefix("level")})"; s == "facet" -> "facet"; else -> s
}

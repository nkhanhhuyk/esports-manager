package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.World
import dev.uem.em.sim.Ev
import dev.uem.em.titles.Neutrals
import dev.uem.em.titles.TitleDef

/**
 * Neutral objectives each team took, by kind: every elemental drake, Dragon Souls, Elder, Baron,
 * Herald, Voidgrubs (League, Wild Rift), Turtles and Lords (MLBB), Tyrants and Overlords (HoK),
 * Roshan, Tormentors and Wisdom Runes (Dota 2). Codes: see GameRecord.objs.
 */
object ObjTally {
    class Row(val name: String, val counts: IntArray, val order: Int)

    /** Rows from (code, team) pairs, in the game's objective order (drakes by element, then the soul). */
    fun rows(w: World?, t: TitleDef, takes: List<Pair<Int, Int>>): List<Row> {
        val cfg = t.moba ?: return emptyList()
        val kinds = Neutrals.of(w, t.id)
        val by = LinkedHashMap<String, Row>()
        for ((code, team) in takes) {
            if (team !in 0..1) continue
            val (name, order, n) = when {
                code >= 1000 -> kinds.getOrNull(code - 1001)?.let { Triple(it.soulName.ifBlank { "Soul" }, objOrder(cfg, it.objId) * 100 + 99, 1) } ?: continue
                code >= 500 -> cfg.objectives.getOrNull(code - 500)?.let { Triple(it.name.removeSuffix(" (Aegis)"), objOrder(cfg, it.id) * 100, 1) } ?: continue
                else -> kinds.getOrNull(code - 1)?.let { k -> Triple(k.name, objOrder(cfg, k.objId) * 100 + kinds.indexOf(k) % 90, if (k.objId == "grubs") 3 else 1) } ?: continue
            }
            by.getOrPut(name) { Row(name, IntArray(2), order) }.counts[team] += n
        }
        return by.values.sortedBy { it.order }
    }

    private fun objOrder(cfg: dev.uem.em.titles.MobaConfig, id: String) = cfg.objectives.indexOfFirst { it.id == id }.let { if (it < 0) 50 else it }

    fun fromRecord(w: World?, t: TitleDef, objs: IntArray) = rows(w, t, objs.map { (it shr 1) to (it and 1) })

    /** From a replay's events up to a moment (the live viewer). */
    fun fromEvents(w: World?, t: TitleDef, ev: List<Ev>, upTo: Int, mapSide: (Int) -> Int = { it }): List<Row> = rows(w, t, ev.filter { it.t <= upTo && (it.kind == "obj" || it.kind == "soul") && it.team >= 0 }.map { e ->
        val code = when { e.kind == "soul" -> 1000 + e.m; e.m > 0 -> e.m; else -> 500 + e.x }
        code to mapSide(e.team)
    })
}

/** After a game: the full table, team A against team B. */
@Composable
fun ObjTallyCard(w: World, t: TitleDef, names: List<String>, rows: List<ObjTally.Row>) {
    if (rows.isEmpty()) return
    Card("Neutral objectives") {
        Row(Modifier.fillMaxWidth()) {
            Text("", modifier = Modifier.weight(1f))
            Text(names.getOrElse(0) { "" }, color = Ui.blue, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
            Text(names.getOrElse(1) { "" }, color = Ui.red, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
        }
        for (r in rows) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(r.name, color = Ui.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
            for (s in 0..1) Text(r.counts[s].toString(), color = if (r.counts[s] > r.counts[1 - s]) Ui.text else Ui.muted, fontSize = 12.sp,
                fontWeight = if (r.counts[s] > r.counts[1 - s]) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
        }
        if (rows.any { it.name == "Voidgrubs" }) Muted("Voidgrubs counted one by one (three per camp).", 10)
    }
}

/** During the game: a compact strip per team (blue first). */
@Composable
fun ObjTallyStrip(rows: List<ObjTally.Row>, teams: List<String>) {
    if (rows.isEmpty()) return
    for (s in 0..1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(6.dp).padding(end = 2.dp).background(if (s == 0) Ui.blue else Ui.red, RoundedCornerShape(3.dp))) { Text(" ", fontSize = 9.sp) }
        Text(teams.getOrElse(s) { "" } + " ", color = if (s == 0) Ui.blue else Ui.red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        val mine = rows.filter { it.counts[s] > 0 }
        if (mine.isEmpty()) Muted("—", 10)
        for (r in mine) Text("${short(r.name)} ${r.counts[s]}  ", color = Ui.text, fontSize = 10.sp)
    }
}

private fun short(n: String) = n.replace(" Dragon Soul", " Soul").replace("Elemental ", "").replace(" Drake", "").replace("Baron Nashor", "Baron").replace("Rift Herald", "Herald")
    .replace("Elder Dragon", "Elder").replace("Roshan (Aegis)", "Roshan").replace("Shadow Overlord", "Sh. Overlord").replace("Shadow Tyrant", "Sh. Tyrant").replace("Tempest Dragon", "Tempest")

package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.comp.Stages
import dev.uem.em.core.Dates
import dev.uem.em.core.formatMoney
import dev.uem.em.data.GameData
import dev.uem.em.game.BoxStore
import dev.uem.em.model.CompTier
import dev.uem.em.model.Competition
import dev.uem.em.model.Fixture
import dev.uem.em.model.Line
import dev.uem.em.model.StageType
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.TitleDef

internal fun titleIdx0(id: String?) = if (id == null) 0 else Catalogue.IDS.indexOf(id) + 1

/** Stat groups for the player tables (as in the web version's dropdowns). */
internal class StatCol(val title: String, val v: (gp: Int, w: Int, r: Double, m: Int, s: DoubleArray, idx: (String) -> Int) -> Double?, val fmt: (Double) -> String)

internal fun groups(t: TitleDef): List<Pair<String, List<StatCol>>> {
    fun per(k: String) = StatCol(k, { gp, _, _, _, s, idx -> idx(k).takeIf { it >= 0 }?.let { s[it] / maxOf(1, gp) } }, { "%.1f".format(it) })
    fun tot(k: String, label: String) = StatCol(label, { _, _, _, _, s, idx -> idx(k).takeIf { it >= 0 }?.let { s[it] } }, { "%.0f".format(it) })
    val gp = StatCol("GP", { g, _, _, _, _, _ -> g.toDouble() }, { "%.0f".format(it) })
    val rating = StatCol("Rating", { g, _, r, _, _, _ -> r / maxOf(1, g) }, { "%.2f".format(it) })
    val wr = StatCol("Win%", { g, wn, _, _, _, _ -> 100.0 * wn / maxOf(1, g) }, { "%.0f".format(it) })
    val kda = StatCol("KDA", { _, _, _, _, s, idx -> (s[idx("k")] + s[idx("a")]) / maxOf(1.0, s[idx("d")]) }, { "%.2f".format(it) })
    val mvp = StatCol("MVP", { _, _, _, m, _, _ -> m.toDouble() }, { "%.0f".format(it) })
    return if (t.isMoba) listOf(
        "Overview" to listOf(gp, wr, rating, kda, per("k").let { StatCol("K/g", it.v, it.fmt) }, per("d").let { StatCol("D/g", it.v, it.fmt) }, per("a").let { StatCol("A/g", it.v, it.fmt) }, mvp),
        "Combat" to listOf(gp, StatCol("Dmg/g", per("dmg").v) { "%.0f".format(it) }, StatCol("Taken/g", per("taken").v) { "%.0f".format(it) }, StatCol("Heal/g", per("heal").v) { "%.0f".format(it) }, tot("k", "Kills"), tot("d", "Deaths"), tot("a", "Assists")),
        "Economy & map" to listOf(gp, StatCol("Gold/g", per("gold").v) { "%.0f".format(it) }, StatCol("CS/g", per("cs").v) { "%.0f".format(it) }, StatCol("Vision/g", per("vision").v) { "%.1f".format(it) }, tot("obj", "Obj"), tot("towers", "Struct")),
    ) else listOf(
        "Overview" to listOf(gp, wr, rating, kda, per("k").let { StatCol("K/map", it.v, it.fmt) }, per("d").let { StatCol("D/map", it.v, it.fmt) }, per("a").let { StatCol("A/map", it.v, it.fmt) }, mvp),
        "Duels" to listOf(gp, tot("fk", "FK"), tot("fd", "FD"), StatCol("HS%", { _, _, _, _, s, idx -> 100 * s[idx("hs")] / maxOf(1.0, s[idx("k")]) }) { "%.0f".format(it) }, tot("clutch", "Clutch"), tot("mk", "Multi"), StatCol("Dmg/map", per("dmg").v) { "%.0f".format(it) }),
        "Utility & site" to listOf(gp, tot("util", "Util"), tot("plant", "Plants"), tot("defuse", "Defuses"), tot("kast", "KAST rds")),
    )
}

@Composable
fun TeamsScreen(vm: GameVm, w: World) {
    var title by remember { mutableStateOf<String?>(w.teams.firstOrNull { w.isUserTeam(it.id) }?.titleId) }
    val leagues = w.leagues.filter { title == null || it.titleId == title }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Tabs(listOf("All") + Catalogue.IDS.map { Catalogue.get(it).short }, titleIdx0(title)) { title = if (it == 0) null else Catalogue.IDS[it - 1] } }
        for (l in leagues) item(key = "l${l.id}") {
            Card(l.name, right = { Pic(Pics.league(l), l.short, 22.dp) }) {
                for (tid in l.teamIds.sortedByDescending { w.teams[it].elo }) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { TeamChip(w, tid, vm) }
                    Muted("Elo ${w.teams[tid].elo.toInt()}")
                }
            }
        }
    }
}

@Composable
fun PlayersScreen(vm: GameVm, w: World) {
    var q by remember { mutableStateOf("") }
    var title by remember { mutableStateOf<String?>(w.teams.firstOrNull { w.isUserTeam(it.id) }?.titleId) }
    var freeOnly by remember { mutableStateOf(false) }
    val list = remember(q, title, freeOnly, vm.version) {
        w.players.asSequence().filter { !it.retired && (title == null || it.titleId == title) && (!freeOnly || it.teamId < 0) && (q.isBlank() || it.handle.contains(q, true) || "${it.first} ${it.last}".contains(q, true)) }
            .sortedByDescending { it.ca }.take(200).toList()
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(q, { q = it }, label = { Text("Search players") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        Tabs(listOf("All") + Catalogue.IDS.map { Catalogue.get(it).short }, titleIdx0(title)) { title = if (it == 0) null else Catalogue.IDS[it - 1] }
        Tabs(listOf("All players", "Free agents"), if (freeOnly) 1 else 0) { freeOnly = it == 1 }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Player(p.id)) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pic(Pics.player(p), p.handle, 34.dp, round = true)
                    HGap(10)
                    Column(Modifier.weight(1f)) {
                        Text(p.handle, color = Ui.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted("${Catalogue.get(p.titleId).short} ${Catalogue.get(p.titleId).role(p.roles[0]).short} · ${p.nat} · ${Dates.ageAt(p.birth, w.day)} · ${p.teamId.takeIf { it >= 0 }?.let { w.teams[it].short } ?: "free agent"}")
                    }
                    Num("%.1f".format(p.ca), bold = true)
                }
            }
        }
    }
}

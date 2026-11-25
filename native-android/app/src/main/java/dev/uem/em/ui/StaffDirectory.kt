package dev.uem.em.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.formatMoney
import dev.uem.em.mgmt.Staffing
import dev.uem.em.model.Staff
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import kotlin.math.roundToInt

private val DIR_STATUS = listOf("Everyone active", "At a club", "Free agents", "Real people", "Retired")

/** One row of the staff directory: the person, their titles (before and during the save) and the club now. */
private class StaffRow(val s: Staff, val titles: Int)

/**
 * World → Staff: every coach, analyst, scout, performance and organisation staff member of every game,
 * searchable and sortable like the players directory.
 */
@Composable
fun StaffDirectoryScreen(vm: GameVm, w: World) {
    var q by remember { mutableStateOf("") }
    var title by remember { mutableStateOf<String?>(w.teams.firstOrNull { w.isUserTeam(it.id) }?.titleId) }
    var roleIdx by remember { mutableIntStateOf(0) }
    var status by remember { mutableIntStateOf(0) }
    val table = rememberTableState(5)
    val roles = listOf<String?>(null) + Staffing.ROLES.map { it.id }
    val role = roles.getOrNull(roleIdx)
    val titles = listOf<String?>(null) + Catalogue.IDS
    val rows = remember(q, title, roleIdx, status, vm.version) {
        val idx = Staffing.titles(w)
        w.staff.asSequence().filter { s ->
            (title == null || s.titleId == title) && (role == null || s.role == role) &&
                when (status) { 1 -> !s.retired && s.teamId >= 0; 2 -> !s.retired && s.teamId < 0; 3 -> s.real && !s.retired; 4 -> s.retired; else -> !s.retired } &&
                (q.isBlank() || s.name().contains(q, true) || s.fullName().contains(q, true) || s.nat.equals(q.trim(), true))
        }.sortedByDescending { it.quality }.take(400)
            .map { s -> StaffRow(s, Staffing.honours(w, s, idx).count { it.trophy }) }.toList()
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Muted("${rows.size}${if (rows.size == 400) "+" else ""} staff match · head coaches, assistants, draft and positional coaches, analysts, scouts, performance and organisation staff", 12)
            }
        }
        item { OutlinedTextField(q, { q = it.take(40) }, label = { Text("Search name, real name or country code") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) }
        item { Tabs(titles.map { it?.let { id -> Catalogue.get(id).short } ?: "All" }, titles.indexOf(title).coerceAtLeast(0)) { title = titles[it] } }
        item {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Select("Role", roles.map { r -> r?.let { Staffing.roleLabel(it, title ?: "lol") } ?: "All roles" }, roleIdx) { roleIdx = it }
                Spacer(Modifier.width(6.dp))
                Select("Show", DIR_STATUS, status) { status = it }
            }
        }
        if (rows.isEmpty()) item { Card { Muted("Nobody matches.") } }
        lazySortTable(table, rows, listOf(
            SCol<StaffRow>("Name", 130.dp, false) { r -> Row(verticalAlignment = Alignment.CenterVertically) { flag(r.s.nat).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp); Spacer(Modifier.width(3.dp)) }; StaffChip(r.s, vm) } },
            SCol("Title", 52.dp, false) { r -> TitleTag(r.s.titleId) },
            SCol("Role", 110.dp, false) { r -> Muted(Staffing.label(r.s), 11) },
            SCol("Age", 34.dp, key = { Staffing.age(w, it.s).toDouble() }) { r -> Num("${Staffing.age(w, r.s)}") },
            SCol("Club", 60.dp, false) { r -> Muted(if (r.s.retired) "retired" else r.s.teamId.takeIf { it >= 0 }?.let { w.teams[it].short } ?: "free", 11) },
            SCol("Ability", 46.dp, key = { it.s.quality }) { r -> Rating(r.s.quality) },
            SCol("Rep", 38.dp, key = { it.s.rep }) { r -> Num("${r.s.rep.roundToInt()}") },
            SCol("Titles", 42.dp, key = { it.titles.toDouble() }) { r -> Num(if (r.titles > 0) "${r.titles}" else "—", Ui.gold) },
            SCol("Clubs", 40.dp, key = { r -> r.s.spells.map { it.teamId }.distinct().size.toDouble() }) { r -> Num("${r.s.spells.map { it.teamId }.distinct().size}", Ui.muted) },
            SCol("Salary", 64.dp, key = { if (it.s.teamId >= 0) it.s.salary else 0.0 }) { r -> Num(if (r.s.teamId >= 0 && !r.s.retired) formatMoney(r.s.salary) else "—", Ui.muted) },
            SCol("Until", 42.dp, key = { it.s.contractEnd.toDouble() }) { r -> Num(if (r.s.teamId >= 0 && !r.s.retired) "${r.s.contractEnd}" else "—", Ui.muted) },
        ), key = { it.s.id }, onRow = { vm.go(Screen.Staff(it.s.id)) }, highlight = { w.isUserTeam(it.s.teamId) })
    }
}

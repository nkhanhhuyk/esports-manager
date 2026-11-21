package dev.uem.em.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.Dates
import dev.uem.em.core.clamp
import dev.uem.em.mgmt.Extras
import dev.uem.em.model.AttrGroup
import dev.uem.em.model.Attrs
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Ratings
import dev.uem.em.titles.Catalogue
import kotlin.math.roundToInt

@Composable
fun ScheduleScreen(vm: GameVm, w: World) {
    val mine = w.teams.filter { it.active && (w.isUserTeam(it.id) || it.id in w.followed) }.map { it.id }.toSet()
    val upcoming = w.fixtures.filter { !it.done && (it.a in mine || it.b in mine) }.sortedBy { it.day }.take(40)
    val recent = w.fixtures.filter { it.done && (it.a in mine || it.b in mine) }.sortedByDescending { it.day }.take(30)
    LazyColumn(Modifier.fillMaxSize()) {
        item { Card("Upcoming") { if (upcoming.isEmpty()) Muted("Nothing scheduled yet: competitions add their matches as they start.") } }
        items(upcoming, key = { "u${it.id}" }) { FixtureRow(vm, w, it) }
        item { Card("Recent results") { if (recent.isEmpty()) Muted("No results yet.") } }
        items(recent, key = { "r${it.id}" }) { FixtureRow(vm, w, it) }
    }
}

@Composable
fun StatsScreen(vm: GameVm, w: World) {
    var title by remember { mutableStateOf(w.orgs.getOrNull(w.userOrg)?.teams?.firstOrNull()?.let { w.teams[it].titleId } ?: "lol") }
    var stat by remember { mutableIntStateOf(0) }
    val t = Catalogue.get(title)
    val idx: (String) -> Int = { t.statIndex[it] ?: -1 }
    class Row3(val p: Player, val gp: Int, val rating: Double, val s: DoubleArray)
    val rows = remember(title, vm.version) {
        w.players.mapNotNull { p ->
            val lines = p.stats.filter { it.season == w.season && it.titleId == title }
            val gp = lines.sumOf { it.gp }
            if (gp < 4) return@mapNotNull null
            val s = DoubleArray(t.stats.size)
            for (l in lines) for (i in s.indices) s[i] += l.s.getOrElse(i) { 0.0 }
            Row3(p, gp, lines.sumOf { it.ratingSum } / gp, s)
        }
    }
    val boards = listOf<Pair<String, (Row3) -> Double>>(
        "Rating" to { it.rating },
        "KDA" to { r -> (r.s[idx("k")] + r.s[idx("a")]) / maxOf(1.0, r.s[idx("d")]) },
        "Kills / game" to { r -> r.s[idx("k")] / r.gp },
        "Assists / game" to { r -> r.s[idx("a")] / r.gp },
        "Damage / game" to { r -> r.s[idx("dmg")] / r.gp },
        "Games" to { r -> r.gp.toDouble() },
    )
    val sorted = rows.sortedByDescending { boards[stat].second(it) }.take(50)
    val teams = w.teams.filter { it.active && it.titleId == title && it.leagueId >= 0 }.sortedByDescending { it.elo }.take(20)
    LazyColumn(Modifier.fillMaxSize()) {
        item { Tabs(Catalogue.IDS.map { Catalogue.get(it).short }, Catalogue.IDS.indexOf(title)) { title = Catalogue.IDS[it] } }
        item { Tabs(boards.map { it.first }, stat) { stat = it } }
        item {
            Card("Season ${w.season} leaders (4+ games)") {
                if (sorted.isEmpty()) Muted("Not enough games yet.")
                sorted.forEachIndexed { i, r ->
                    Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Player(r.p.id)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(26.dp))
                        Column(Modifier.weight(1f)) { PlayerChip(r.p, vm, 20.dp); Muted("${r.p.teamId.takeIf { it >= 0 }?.let { w.teams[it].short } ?: "—"} · ${r.gp} games", 10) }
                        Num("%.2f".format(boards[stat].second(r)), bold = true)
                    }
                }
            }
        }
        item {
            Card("Power ranking (Elo)") {
                teams.forEachIndexed { i, team -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) { Text("${i + 1}", color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(26.dp)); Column(Modifier.weight(1f)) { TeamChip(w, team.id, vm, 18.dp) }; Num("${team.elo.roundToInt()}") } }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: GameVm, w: World) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card("Difficulty") {
                Tabs(listOf("Easy", "Normal", "Hard"), listOf("easy", "normal", "hard").indexOf(w.difficulty).coerceAtLeast(0)) { w.difficulty = listOf("easy", "normal", "hard")[it]; vm.edit() }
                Muted("Easy: 25% more income and a more patient board. Hard: 15% less income and a stricter board.")
            }
        }
        item {
            Card("Autosave") {
                val opts = listOf("advance" to "After every advance", "week" to "Every week (recommended)", "month" to "Every month", "off" to "Off")
                Tabs(opts.map { it.second }, opts.indexOfFirst { it.first == w.autosave }.coerceAtLeast(1)) { w.autosave = opts[it].first; vm.edit() }
                Muted("The game also saves when you leave the app.")
            }
        }
        item {
            Card("Delegation") {
                Tabs(listOf("You answer bids", "Director answers bids"), if (w.delegateBids) 1 else 0) { w.delegateBids = it == 1; vm.edit() }
                Tabs(listOf("You handle renewals", "Director renews key players"), if (w.delegateRenewals) 1 else 0) { w.delegateRenewals = it == 1; vm.edit() }
                Muted("The director accepts bids well above a player's value and renews players of 60+ ability in October.")
            }
        }
        item {
            Card("Pictures") {
                Tabs(listOf("Online pictures on", "Off (badges only)"), if (Pics.online) 0 else 1) { Pics.online = it == 0; vm.setPicturesOnline(it == 0); vm.touch() }
                Muted("Pictures load from their owners' servers; without a connection the game shows its own badges.")
            }
        }
        item {
            Card("Manager") {
                var name by remember { mutableStateOf(w.managerName) }
                OutlinedTextField(name, { name = it.take(30); w.managerName = name }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Follow / unfollow a team (followed teams' matches appear on Home and in the Schedule). */
@Composable
fun FollowButton(vm: GameVm, w: World, team: Team) {
    if (w.isUserTeam(team.id)) return
    val on = team.id in w.followed
    TextButton(onClick = { if (on) w.followed.remove(team.id) else w.followed.add(team.id); vm.edit() }) { Text(if (on) "★ Following" else "☆ Follow", color = if (on) Ui.gold else Ui.accent) }
}

/** Scouting line on a player's page. */
@Composable
fun ScoutLine(vm: GameVm, w: World, p: Player) {
    if (w.userOrg < 0 || w.isUserTeam(p.teamId)) return
    val k = Extras.knowledge(w, p)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Muted("Scouting knowledge $k% · potential ${Extras.potentialText(w, p)}", 12)
        if (k < 90) TextButton(onClick = { Extras.scout(w, p.id); vm.edit(); vm.toast = "Added to the scouting list" }) { Text(if (p.id in w.scoutQueue) "Being scouted" else "Scout", color = Ui.accent, fontSize = 12.sp) }
    }
}

/** Database editor: rename a player or team, change a player's ability and position. */
@Composable
fun EditPlayerDialog(vm: GameVm, w: World, p: Player, onClose: () -> Unit) {
    val t = Catalogue.get(p.titleId)
    var handle by remember { mutableStateOf(p.handle) }
    var nat by remember { mutableStateOf(p.nat) }
    var role by remember { mutableIntStateOf(t.lineup.indexOf(p.mainRole).coerceAtLeast(0)) }
    var ca by remember { mutableFloatStateOf(p.ca.toFloat()) }
    var pa by remember { mutableFloatStateOf(p.pa.toFloat()) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Edit ${p.handle}") }, text = {
        Column {
            OutlinedTextField(handle, { handle = it.take(16) }, label = { Text("Handle") }, singleLine = true)
            OutlinedTextField(nat, { nat = it.uppercase().take(2) }, label = { Text("Nationality (code)") }, singleLine = true)
            Tabs(t.lineupRoles.map { it.short }, role) { role = it }
            Text("Current ability %.0f".format(ca), color = Ui.text)
            Slider(value = ca, onValueChange = { ca = it }, valueRange = 30f..95f)
            Text("Potential %.0f".format(maxOf(pa, ca)), color = Ui.text)
            Slider(value = pa, onValueChange = { pa = it }, valueRange = 30f..99f)
        }
    }, confirmButton = {
        TextButton(onClick = {
            p.handle = handle.ifBlank { p.handle }
            if (dev.uem.em.data.GameData.countries.containsKey(nat)) p.nat = nat
            val newRole = t.lineup[role]
            if (newRole != p.mainRole) p.roles = listOf(newRole, p.mainRole)
            val delta = (ca - p.ca) / 0.66
            for (i in 0 until Attrs.COUNT) if (Attrs.ALL[i].group != AttrGroup.Development) p.attrs[i] = clamp(p.attrs[i] + delta, 1.0, 99.0)
            p.attrVersion++
            p.ca = Ratings.computeCA(p, t)
            p.pa = maxOf(p.ca, pa.toDouble())
            vm.edit()
            onClose()
        }) { Text("Save", color = Ui.accent) }
    }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = Ui.muted) } })
}

@Composable
fun EditTeamDialog(vm: GameVm, w: World, team: Team, onClose: () -> Unit) {
    var name by remember { mutableStateOf(team.name) }
    var short by remember { mutableStateOf(team.short) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Edit ${team.name}") }, text = {
        Column {
            OutlinedTextField(name, { name = it.take(30) }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(short, { short = it.uppercase().take(6) }, label = { Text("Short name") }, singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = { team.name = name.ifBlank { team.name }; team.short = short.ifBlank { team.short }; vm.edit(); onClose() }) { Text("Save", color = Ui.accent) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = Ui.muted) } })
}

/** Followed teams on Home. */
@Composable
fun FollowedCard(vm: GameVm, w: World) {
    if (w.followed.isEmpty()) return
    Card("Following") {
        for (tid in w.followed) {
            val last = w.fixtures.lastOrNull { it.done && (it.a == tid || it.b == tid) }
            val next = w.fixtures.filter { !it.done && (it.a == tid || it.b == tid) }.minByOrNull { it.day }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    TeamChip(w, tid, vm, 18.dp)
                    Muted(listOfNotNull(last?.let { "last: ${if (it.winner == tid) "W" else "L"} ${it.scoreA}-${it.scoreB} vs ${w.teams[if (it.a == tid) it.b else it.a].short}" }, next?.let { "next: ${Dates.short(it.day)} vs ${w.teams[if (it.a == tid) it.b else it.a].short}" }).joinToString(" · "), 11)
                }
            }
        }
    }
}

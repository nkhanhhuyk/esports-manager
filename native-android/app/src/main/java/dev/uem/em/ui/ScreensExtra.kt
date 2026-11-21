package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
    var scope by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf(w.teams.firstOrNull { w.isUserTeam(it.id) }?.titleId ?: "lol") }
    val ids: Set<Int> = when (scope) {
        0 -> w.teams.filter { w.isUserTeam(it.id) }.map { it.id }.toSet()
        1 -> w.followed.toSet()
        else -> w.teams.filter { it.titleId == title }.map { it.id }.toSet()
    }
    val upcoming = w.fixtures.filter { !it.done && (it.a in ids || it.b in ids) }.sortedBy { it.day }.take(80)
    val recent = w.fixtures.filter { it.done && (it.a in ids || it.b in ids) }.sortedByDescending { it.day }.take(40)
    var monthOffset by remember { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("Schedule", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Muted("${upcoming.size} upcoming · ${recent.size} recent", 12)
            }
        }
        item { Tabs(listOf("My teams", "Followed", "All teams of a game"), scope) { scope = it } }
        if (scope == 2) item { Tabs(Catalogue.IDS.map { Catalogue.get(it).short }, Catalogue.IDS.indexOf(title)) { title = Catalogue.IDS[it] } }
        item {
            // Month calendar with match days marked.
            val today = Dates.day(Dates.year(w.day), Dates.month(w.day), 1)
            var y = Dates.year(today)
            var m = Dates.month(today) + monthOffset
            while (m > 12) { m -= 12; y++ }
            while (m < 1) { m += 12; y-- }
            val first = Dates.day(y, m, 1)
            val days = (1..31).takeWhile { d -> d == 1 || Dates.month(first + d - 1) == m }.size
            val matchDays = w.fixtures.filter { (it.a in ids || it.b in ids) && it.day in first until first + days }.groupBy { it.day }
            Card("${listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")[m - 1]} $y", right = {
                Row { Pill("‹") { monthOffset-- }; Pill("Today") { monthOffset = 0 }; Pill("›") { monthOffset++ } }
            }) {
                Row { for (d in listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")) Text(d, color = Ui.muted, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                val lead = (Dates.dow(first) + 6) % 7
                val cells = lead + days
                for (row in 0 until (cells + 6) / 7) Row {
                    for (col in 0..6) {
                        val idx = row * 7 + col - lead
                        val day = first + idx
                        Column(Modifier.weight(1f).padding(1.dp).background(if (idx in 0 until days && day == w.day) Ui.accent.copy(alpha = 0.35f) else Ui.card2.copy(alpha = if (idx in 0 until days) 1f else 0f), RoundedCornerShape(4.dp)).padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (idx in 0 until days) {
                                Text("${idx + 1}", color = Ui.text, fontSize = 11.sp)
                                val fx = matchDays[day]
                                Text(if (fx == null) " " else fx.joinToString("") { f -> if (!f.done) "•" else if (f.winner in ids) "W" else "L" }.take(3), color = if (fx?.all { it.done } == true) Ui.gold else Ui.accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            } else Text(" ", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        if (upcoming.isEmpty()) item { Card { Muted("Nothing scheduled yet: competitions add their matches as they start.") } }
        for ((day, list) in upcoming.groupBy { it.day }) {
            item(key = "ud$day") { Text(when (day - w.day) { 0 -> "Today"; 1 -> "Tomorrow"; else -> Dates.format(day, true) }, color = Ui.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 8.dp)) }
            items(list, key = { "u${it.id}" }) { FixtureRow(vm, w, it) }
        }
        item { Text("Recent results", color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)) }
        if (recent.isEmpty()) item { Card { Muted("No results yet.") } }
        items(recent, key = { "r${it.id}" }) { FixtureRow(vm, w, it) }
        item { Gap(16) }
    }
}

@Composable
fun SettingsScreen(vm: GameVm, w: World) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card("Career") {
                KV("Save", w.name)
                KV("Seed", w.seed)
                KV("Season", "${w.season} · ${Dates.format(w.day)}")
                KV("World", "${w.players.size} players · ${w.teams.count { it.active }} active teams · ${w.leagues.size} leagues")
                KV("Competitions", "${w.comps.size} (${w.comps.count { it.done }} finished)")
                KV("Transfers", "${w.transfers.size}")
                KV("Games", w.titles.joinToString { Catalogue.get(it).short })
            }
        }
        item {
            val exporter = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportSave(uri) }
            Card("Back up this career") {
                Muted("Exports the save and its box scores / replays as one file (with checksums) that you can keep or move to another phone; import it from the title screen. Useful before reinstalling or updating the app.", 11)
                TextButton(onClick = { exporter.launch("${w.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}-S${w.season}.uemsave.zip") }) { Text("Export save…", color = Ui.accent) }
            }
        }
        item {
            Card("Replays") {
                val opts = listOf("important" to "Important (recommended)", "mine" to "My and followed teams", "all" to "Every match", "off" to "Off")
                Tabs(opts.map { it.second }, opts.indexOfFirst { it.first == w.replays }.coerceAtLeast(0)) { w.replays = opts[it].first; vm.edit() }
                Muted("Which background matches keep a 2D replay (matches you watch always do). Important = your and followed teams, internationals, Worlds and league finals. Replays older than about two seasons are removed at the season change; box scores are always kept.", 11)
                if (w.userOrg < 0) {
                    Gap(6)
                    Tabs(listOf("Stop before followed teams play", "Do not stop"), if (w.stopForFollowed) 0 else 1) { w.stopForFollowed = it == 0; vm.edit() }
                }
            }
        }
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
            Card("Pictures offline") {
                Muted("Pictures you have seen stay on the phone (up to 400 MB). Download them all now to see them without internet:", 11)
                val mine = w.orgs.getOrNull(w.userOrg)?.teams?.map { w.teams[it].titleId }?.toSet() ?: w.followed.mapNotNull { w.teams.getOrNull(it)?.titleId }.toSet()
                Row {
                    if (mine.isNotEmpty()) androidx.compose.material3.TextButton(onClick = { vm.downloadPictures(mine) }) { Text("My games", color = Ui.accent) }
                    androidx.compose.material3.TextButton(onClick = { vm.downloadPictures(w.titles.toSet()) }) { Text("All seven games", color = Ui.accent) }
                }
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

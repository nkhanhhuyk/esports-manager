package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.comp.Stages
import dev.uem.em.core.Dates
import dev.uem.em.core.formatMoney
import dev.uem.em.model.StageType
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.titles.Catalogue
import java.text.DateFormat
import java.util.Date

@Composable
fun StartScreen(vm: GameVm) {
    var name by remember { mutableStateOf("Career") }
    var saves by remember { mutableStateOf(vm.saves()) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Column(Modifier.padding(20.dp)) {
                Text("ESPORTS MANAGER", color = Ui.accent, fontWeight = FontWeight.Black, fontSize = 26.sp)
                Muted("Native Android edition · seven games · real teams and players", 13)
            }
        }
        item {
            Card("New career") {
                OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, label = { Text("Save name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Gap()
                Muted("Real leagues, teams and players from the November 2026 snapshot (LoL, Valorant, CS2, Dota 2, MLBB, Wild Rift; Honor of Kings teams are fictional).")
                Gap()
                Button(onClick = { vm.newGame(name) }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) { Text("Generate world") }
            }
        }
        if (saves.isNotEmpty()) item { Card("Continue") { Muted("Saves on this phone") } }
        items(saves) { s ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, color = Ui.text, fontWeight = FontWeight.SemiBold)
                        Muted("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.modified))} · ${s.bytes / 1024} KB")
                    }
                    TextButton(onClick = { vm.store.delete(s.slot); saves = vm.saves() }) { Text("Delete", color = Ui.loss) }
                    Button(onClick = { vm.load(s.slot) }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text("Load") }
                }
            }
        }
    }
}

@Composable
fun PickOrgScreen(vm: GameVm, w: World) {
    var title by remember { mutableStateOf<String?>("lol") }
    val orgs = remember(title) {
        w.orgs.filter { o -> o.teams.any { w.teams[it].active && (title == null || w.teams[it].titleId == title) } }
            .sortedWith(compareByDescending<dev.uem.em.model.Org> { it.real }.thenByDescending { it.rep })
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(16.dp)) {
            Text("Choose your organisation", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Muted("Real organisations first, by reputation")
            Gap(8)
            Row(Modifier.fillMaxWidth().background(Ui.card2, RoundedCornerShape(12.dp)).clickable { vm.chooseSpectator() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Spectator mode", color = Ui.text, fontWeight = FontWeight.Bold)
                    Muted("No club to manage: follow any teams, watch any match live on the 2D map, browse the whole world.", 12)
                }
                Text("›", color = Ui.accent, fontSize = 22.sp)
            }
        }
        Tabs(listOf("All") + Catalogue.IDS.map { Catalogue.get(it).short }, if (title == null) 0 else Catalogue.IDS.indexOf(title) + 1) { i -> title = if (i == 0) null else Catalogue.IDS[i - 1] }
        LazyColumn(Modifier.fillMaxSize()) {
            items(orgs, key = { it.id }) { o ->
                val teams = o.teams.map { w.teams[it] }.filter { it.active }
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pic(teams.firstNotNullOfOrNull { Pics.team(it) }, o.short, 40.dp)
                        HGap(10)
                        Column(Modifier.weight(1f)) {
                            Text(o.name, color = Ui.text, fontWeight = FontWeight.SemiBold)
                            Muted(teams.joinToString(" · ") { t -> "${Catalogue.get(t.titleId).short} ${t.leagueId.takeIf { it >= 0 }?.let { w.leagues[it].short } ?: ""}".trim() })
                            Muted("Reputation ${o.rep.toInt()} · balance ${formatMoney(o.balance)}")
                        }
                        Button(onClick = { vm.chooseOrg(o.id) }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text("Manage") }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(vm: GameVm, w: World) {
    if (w.userOrg < 0) { SpectatorHome(vm, w); return }
    val org = w.orgs[w.userOrg]
    val teams = org.teams.map { w.teams[it] }.filter { it.active }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Pic(teams.firstNotNullOfOrNull { Pics.team(it) }, org.short, 44.dp)
                HGap(12)
                Column {
                    Text(org.name, color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Muted("Reputation ${org.rep.toInt()} · balance ${formatMoney(org.balance)} · ${(org.fans / 1000).toInt()}K fans")
                }
            }
        }
        item(key = "tiles") {
            val fin = dev.uem.em.mgmt.Economy.monthly(w, org)
            val net = fin.income - fin.costs
            val unread = w.inbox.count { !it.read }
            val pending = w.offers.count { it.status == "pending" && w.isUserTeam(w.players[it.playerId].teamId) }
            val b = w.board
            Tiles(listOfNotNull(
                Tile("Balance", formatMoney(org.balance), "${if (net >= 0) "+" else ""}${formatMoney(net)} / month", if (org.balance >= 0) Ui.text else Ui.loss),
                Tile("Reputation", "${org.rep.toInt()}", "%,.0f fans".format(org.fans), Rate.color(org.rep)),
                Tile("Divisions", "${teams.size}", teams.joinToString { Catalogue.get(it.titleId).short }),
                Tile("Trophies", "${teams.sumOf { it.trophies.size }}", "prestige ${org.prestige.toInt()}", Ui.gold),
                Tile("Unread inbox", "$unread", if (pending > 0) "$pending offer(s) for your players" else null),
                if (b != null) Tile("Board confidence", "${b.confidence.toInt()}/100", if (b.contractEnd > 0) "your contract: end of ${b.contractEnd}" else null, Rate.color(b.confidence)) else null,
            ))
        }
        item(key = "resume") { ResumeLiveCard(vm, w) }
        item(key = "upcoming") {
            val next = w.fixtures.filter { !it.done && it.day >= w.day && (w.isUserTeam(it.a) || w.isUserTeam(it.b)) }.sortedBy { it.day }.take(6)
            if (next.isNotEmpty()) Card("Upcoming matches", right = { TextButton(onClick = { vm.go(Screen.Schedule) }) { Text("Schedule ›", color = Ui.accent) } }) {
                for (f in next) {
                    val mine = if (w.isUserTeam(f.a)) f.a else f.b
                    val opp = if (mine == f.a) f.b else f.a
                    Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Match(f.id)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.padding(end = 8.dp)) { Text(Dates.short(f.day), color = Ui.text, fontSize = 12.sp); Muted(w.comps[f.compId].short, 9) }
                        TitleTag(w.teams[mine].titleId)
                        Text("vs ", color = Ui.muted, fontSize = 12.sp)
                        Column(Modifier.weight(1f)) { if (opp >= 0) TeamChip(w, opp, vm, 18.dp) else Muted("TBD") }
                        Muted("Bo${f.bestOf}", 11)
                    }
                }
            }
        }
        dev.uem.em.game.Advance.userFixtureDue(w)?.let { f ->
            item(key = "matchday") {
                val opp = if (w.isUserTeam(f.a)) f.b else f.a
                Card("Match day ${if (f.day <= w.day) "today" else "tomorrow"}") {
                    Row(verticalAlignment = Alignment.CenterVertically) { Muted("${w.comps[f.compId].short} · Bo${f.bestOf} vs ", 13); TeamChip(w, opp, vm, 20.dp) }
                    Gap()
                    Row {
                        Button(onClick = { vm.startLive(f.id) }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text("Play live") }
                        HGap()
                        TextButton(onClick = { vm.advance(GameVm.Span.DAY) }) { Text("Let the staff simulate it", color = Ui.accent) }
                    }
                    Muted("Live: you draft (or veto maps) and watch each game on the 2D map.", 11)
                }
            }
        }
        for (team in teams) {
            val t = Catalogue.get(team.titleId)
            item(key = "t${team.id}") {
                Card("${t.short} · ${team.name}", right = { TextButton(onClick = { vm.go(Screen.Team(team.id)) }) { Text("Team ›", color = Ui.accent) } }) {
                    val upcoming = w.fixtures.filter { !it.done && (it.a == team.id || it.b == team.id) }.minByOrNull { it.day }
                    val recent = w.fixtures.filter { it.done && (it.a == team.id || it.b == team.id) }.sortedByDescending { it.day }.take(5)
                    Muted("Strength ${"%.1f".format(Lineups.strength(w, team))} · Elo ${team.elo.toInt()}")
                    Gap()
                    if (upcoming != null) {
                        val opp = if (upcoming.a == team.id) upcoming.b else upcoming.a
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.go(Screen.Comp(upcoming.compId)) }) {
                            Muted("Next: ${Dates.format(upcoming.day, true)} · ${w.comps[upcoming.compId].short} · Bo${upcoming.bestOf} vs ", 13)
                            TeamChip(w, opp, vm, 18.dp)
                        }
                    } else {
                        val next = w.comps.filter { it.status == 0 && it.titleId == team.titleId && (it.leagueId == team.leagueId || it.leagueId < 0) }.minByOrNull { it.startDay }
                        Muted(if (next != null) "No match scheduled · next event: ${next.name} (${Dates.short(next.startDay)})" else "No match scheduled.")
                    }
                    Gap()
                    for (f in recent) {
                        val won = f.winner == team.id
                        val opp = if (f.a == team.id) f.b else f.a
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vm.go(Screen.Match(f.id)) }.padding(vertical = 3.dp)) {
                            Text(if (won) "W" else "L", color = if (won) Ui.win else Ui.loss, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                            Text(if (f.a == team.id) "${f.scoreA}-${f.scoreB}" else "${f.scoreB}-${f.scoreA}", color = Ui.text, modifier = Modifier.padding(end = 8.dp))
                            TeamChip(w, opp, vm, 18.dp)
                            Muted("  ${Dates.short(f.day)}")
                        }
                    }
                    // Current league standings.
                    val comp = w.comps.lastOrNull { it.leagueId == team.leagueId && it.status == 1 } ?: w.comps.lastOrNull { it.leagueId == team.leagueId && it.done && it.season == w.season }
                    val stage = comp?.stages?.firstOrNull { it.type == StageType.RR || it.type == StageType.DRR }
                    if (comp != null && stage != null && stage.standings.isNotEmpty()) {
                        Gap()
                        Text(comp.name, color = Ui.muted, fontSize = 12.sp, modifier = Modifier.clickable { vm.go(Screen.Comp(comp.id)) })
                        StandingsTable(vm, w, Stages.sortStandings(w, stage.standings))
                    }
                }
            }
        }
        w.board?.let { b ->
            item(key = "board") {
                Card("Board ${b.season}", right = { TextButton(onClick = { vm.go(Screen.Board) }) { Text("Board ›", color = Ui.accent) } }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Confidence", color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(80.dp))
                        Bar(b.confidence, color = Rate.color(b.confidence), modifier = Modifier.weight(1f))
                        Text(" ${b.confidence.toInt()}", color = Ui.text, fontSize = 12.sp)
                    }
                    Gap(4)
                    for (o in b.objectives) Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when (o.status) { "met" -> "✔"; "failed" -> "✖"; else -> "•" }, color = when (o.status) { "met" -> Ui.win; "failed" -> Ui.loss; else -> Ui.muted }, modifier = Modifier.width(18.dp))
                        Text(o.text, color = Ui.text, fontSize = 13.sp)
                    }
                }
            }
        }
        item(key = "inbox") {
            val msgs = w.inbox.filter { !it.read }.takeLast(4).reversed().ifEmpty { w.inbox.takeLast(3).reversed() }
            if (msgs.isNotEmpty()) Card("Inbox", right = { TextButton(onClick = { vm.go(Screen.Inbox) }) { Text("Open ›", color = Ui.accent) } }) {
                for (m in msgs) Column(Modifier.fillMaxWidth().clickable { vm.go(Screen.Inbox) }.padding(vertical = 4.dp)) {
                    Row { if (!m.read) Text("● ", color = Ui.accent, fontSize = 12.sp); Text(m.subject, color = Ui.text, fontSize = 13.sp, fontWeight = if (m.read) FontWeight.Normal else FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    Muted("${m.from} · ${Dates.short(m.day)}", 10)
                }
            }
        }
        item { FollowedCard(vm, w) }
        item {
            Card("News") {
                for (n in w.news.takeLast(8).reversed()) {
                    Text(n.title, color = Ui.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (n.body.isNotBlank() && n.body != n.title) Text(n.body, color = Ui.muted, fontSize = 12.sp, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Muted(Dates.format(n.day), 10)
                    Gap(6)
                }
                if (w.news.isEmpty()) Muted("Nothing yet.")
            }
        }
    }
}

@Composable
fun MoreScreen(vm: GameVm, w: World) {
    val unread = w.inbox.count { !it.read }
    val menu = listOf(
        Triple("Match centre", "Watch any match live on the 2D map, recorded replays", Screen.Watch),
        Triple("Inbox", if (unread > 0) "$unread unread" else "Messages, offers, reports", Screen.Inbox),
        Triple("Finances", "Balance, income and expenses, sponsors, facilities", Screen.Finances),
        Triple("Board", "Objectives and confidence", Screen.Board),
        Triple("History & awards", "Champions, GOAT rankings, Hall of Fame, dynasties", Screen.History),
        Triple("Patch notes", "Every balance patch of every game", Screen.Patches),
        Triple("Schedule", "Your and followed teams' matches", Screen.Schedule),
        Triple("Stats hub", "Leaderboards, records, head-to-head, season preview & review", Screen.Stats),
        Triple("Teams", "All leagues and teams, team directory", Screen.Teams),
        Triple("Players", "Search, filter and compare every player", Screen.Players),
        Triple("World", "Games, national scenes, global rankings, organisations", Screen.World),
        Triple("Settings", "Difficulty, autosave, pictures, your name", Screen.Settings),
    )
    LazyColumn(Modifier.fillMaxSize()) {
        items(menu) { (title, sub, screen) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(Ui.card, RoundedCornerShape(10.dp)).clickable { vm.go(screen) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(title, color = Ui.text, fontWeight = FontWeight.SemiBold); Muted(sub) }
                Text("›", color = Ui.muted, fontSize = 20.sp)
            }
        }
        item {
            Card("Save") {
                Muted("Saves are written in the background and the game saves itself every simulated week and when you leave the app.")
                Gap()
                Button(onClick = { vm.save() }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text("Save now") }
                TextButton(onClick = { vm.save(silent = true); vm.tab(Screen.Start) }) { Text("Back to title screen", color = Ui.accent) }
            }
        }
        item { Card("Career") { Muted("${w.name} · ${w.players.size} players · ${w.teams.count { it.active }} active teams · ${w.comps.size} competitions · ${w.transfers.size} transfers") } }
    }
}

@Composable
fun StandingsTable(vm: GameVm, w: World, rows: List<dev.uem.em.model.Standing>) {
    Table(listOf(Col("#", 26.dp), Col("Team", 170.dp, false), Col("W", 30.dp), Col("L", 30.dp), Col("Games", 56.dp)), rows.size, onRow = { vm.go(Screen.Team(rows[it].teamId)) }, highlight = { w.isUserTeam(rows[it].teamId) }) { r, c ->
        val row = rows[r]
        when (c) {
            0 -> Num("${r + 1}", Ui.muted)
            1 -> TeamChip(w, row.teamId, vm, 18.dp)
            2 -> Num("${row.w}", bold = true)
            3 -> Num("${row.l}")
            else -> Num("${row.gw}-${row.gl}", Ui.muted)
        }
    }
}

@Composable
fun rememberInt(v: Int) = remember { mutableIntStateOf(v) }

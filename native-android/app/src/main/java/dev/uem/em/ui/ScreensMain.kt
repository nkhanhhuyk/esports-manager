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
    var fictional by remember { mutableStateOf(false) }
    var saves by remember(vm.slot, vm.unsaved, vm.busy) { mutableStateOf(vm.saves()) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
        item {
            Column(Modifier.padding(20.dp)) {
                Text("ESPORTS MANAGER", color = Ui.accent, fontWeight = FontWeight.Black, fontSize = 26.sp)
                Muted("Native Android edition · seven games · real teams and players", 13)
            }
        }
        vm.world?.let { cur ->
            item {
                Card("Current game") {
                    Muted("${cur.name} · season ${cur.season} · ${Dates.format(cur.day)}${if (vm.unsaved) " · unsaved changes" else ""}", 12)
                    Row {
                        Button(onClick = { vm.tab(Screen.Home) }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text("Back to the game") }
                        HGap(8)
                        TextButton(onClick = { vm.save(); saves = vm.saves() }) { Text("Save", color = Ui.accent) }
                        TextButton(onClick = { vm.saveAsOpen = true }) { Text("Save as new file…", color = Ui.accent) }
                    }
                }
            }
        }
        item {
            Card("New career") {
                OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, label = { Text("Save name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Gap()
                Tabs(listOf("Real world", "Fictional world"), if (fictional) 1 else 0) { fictional = it == 1 }
                Gap(6)
                if (!fictional) Muted("Real leagues, teams and players from the 20 November 2026 snapshot for LoL, Valorant, CS2, Dota 2 and MLBB, with academy teams in the LCK Challengers League and MDL. Wild Rift uses the last available roster snapshot (early 2021, marked historical). Honor of Kings teams and players are fictional: no current verified source could be used. Team and player pages show what is verified, estimated or generated.")
                else Muted("Every league, organisation, team, player and coach is generated, with generated crests and faces. The seven games, their characters, maps, patches and the competition calendar stay the same.")
                Gap()
                Button(onClick = { vm.newGame(name, fictional) }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) { Text("Generate world") }
            }
        }
        item {
            var online by remember { mutableStateOf(Pics.online) }
            val stored = remember(vm.busy) { PictureStore.count() to PictureStore.bytes() }
            Card("Settings") {
                Text("Pictures", color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Muted("Download every picture the game knows — characters, weapons, team and league logos, player photos, all country flags and your database-editor links — into the app for good. They show without internet. A full download can take a few GB.", 11)
                Muted("Stored now: ${stored.first} pictures (${stored.second / (1024 * 1024)} MB)", 11)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.downloadAllPictures() }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text("Download all pictures") }
                    HGap(8)
                    TextButton(onClick = { PictureStore.clear(); vm.toast = "Stored pictures deleted" }) { Text("Delete stored", color = Ui.muted) }
                }
                Tabs(listOf("Load pictures online", "Game art only"), if (online) 0 else 1) { online = it == 0; Pics.online = online; vm.setPicturesOnline(online) }
                Gap(8)
                SpeedSetting(vm)
            }
        }
        item {
            val importer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importSave(uri) { saves = vm.saves() } }
            Card("Saves") {
                Muted("Saves on this phone are below. You can also import a save exported from this or another phone (checked before it is added; nothing is overwritten).", 12)
                TextButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text("Import a save…", color = Ui.accent) }
            }
        }
        items(saves, key = { it.slot }) { s ->
            var renaming by remember { mutableStateOf(false) }
            var deleting by remember { mutableStateOf(false) }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name + if (s.slot == vm.slot && vm.world != null) "  (open)" else "", color = Ui.text, fontWeight = FontWeight.SemiBold)
                        if (s.career.isNotBlank() || s.club.isNotBlank()) Text(listOf(s.club, s.career).filter { it.isNotBlank() }.distinct().joinToString(" · "), color = Ui.muted, fontSize = 12.sp)
                        Muted((if (s.season > 0) "Season ${s.season} · ${Dates.format(s.day)} · " else "") + "saved ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.modified))} · ${s.bytes / 1024} KB", 11)
                    }
                    Button(onClick = { vm.load(s.slot) }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text("Load") }
                }
                Row {
                    TextButton(onClick = { renaming = true }) { Text("Rename", color = Ui.muted) }
                    TextButton(onClick = { deleting = true }) { Text("Delete", color = Ui.loss) }
                }
            }
            if (renaming) {
                var label by remember { mutableStateOf(s.name) }
                androidx.compose.material3.AlertDialog(onDismissRequest = { renaming = false },
                    confirmButton = { TextButton(onClick = { vm.renameSave(s.slot, label.ifBlank { s.name }); renaming = false; saves = vm.saves() }) { Text("Rename") } },
                    dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
                    title = { Text("Rename save") },
                    text = { OutlinedTextField(value = label, onValueChange = { label = it.take(40) }, singleLine = true) })
            }
            if (deleting) androidx.compose.material3.AlertDialog(onDismissRequest = { deleting = false },
                confirmButton = { TextButton(onClick = { vm.store.delete(s.slot); deleting = false; saves = vm.saves() }) { Text("Delete", color = Ui.loss) } },
                dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
                title = { Text("Delete ${s.name}?") },
                text = { Text("This save file is removed from the phone. Other save files of the same career are not affected.") })
        }
    }
}

@Composable
fun PickOrgScreen(vm: GameVm, w: World) {
    var title by remember { mutableStateOf<String?>("lol") }
    var role by remember { mutableStateOf("coach-gm") }
    val orgs = remember(title) {
        w.orgs.filter { o -> o.teams.any { w.teams[it].active && (title == null || w.teams[it].titleId == title) } }
            .sortedWith(compareByDescending<dev.uem.em.model.Org> { it.real }.thenByDescending { it.rep })
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(16.dp)) {
            Text("Choose your job", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Gap(4)
        }
        val ids = dev.uem.em.mgmt.Roles.IDS
        Tabs(listOf("Coach & GM", "Head coach", "General manager", "Sporting director", "Owner", "Commissioner"), ids.indexOf(role)) { role = ids[it] }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(dev.uem.em.mgmt.Roles.LABEL[role]!!, color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Muted(dev.uem.em.mgmt.Roles.DESC[role]!! + if (!dev.uem.em.mgmt.Roles.hasBoard(role)) "" else " The board judges your results.", 12)
            Gap(10)
            Text("Choose your organisation", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Muted(if (w.fictional) "By reputation" else "Real organisations first, by reputation")
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
                        if (teams.isNotEmpty()) TeamPic(teams[0], 40.dp) else Pic(null, o.short, 40.dp)
                        HGap(10)
                        Column(Modifier.weight(1f)) {
                            Text(o.name, color = Ui.text, fontWeight = FontWeight.SemiBold)
                            Muted(teams.joinToString(" · ") { t -> "${Catalogue.get(t.titleId).short} ${t.leagueId.takeIf { it >= 0 }?.let { w.leagues[it].short } ?: "development"}${if (t.parentId >= 0) " (academy)" else ""}".trim() })
                            Muted("Reputation ${o.rep.toInt()} · balance ${formatMoney(o.balance)}")
                        }
                        Button(onClick = { vm.chooseOrg(o.id, role) }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text("Manage") }
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
                        val coach = dev.uem.em.mgmt.Roles.can(w, "tactics")
                        Button(onClick = { vm.startLive(f.id) }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text(if (coach) "Play live" else "Watch live") }
                        HGap()
                        TextButton(onClick = { vm.advance(GameVm.Span.DAY) }) { Text("Let the staff simulate it", color = Ui.accent) }
                    }
                    Muted(if (dev.uem.em.mgmt.Roles.can(w, "tactics")) "Live: you draft (or veto maps) and watch each game on the 2D map." else "Your head coach drafts and plays; you watch from the box on the 2D map.", 11)
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
                    LinkedText(n.title, Ui.text, 14.sp, FontWeight.SemiBold)
                    if (n.body.isNotBlank() && n.body != n.title) LinkedText(n.body, Ui.muted, 12.sp, maxLines = 3)
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
        Triple("Social media", "Fans, analysts and teams on Agora, Chirp, Lens and Fanbase: reactions, ratings, debates, hot takes", Screen.Social),
        Triple("Inbox", if (unread > 0) "$unread unread" else "Messages, offers, reports", Screen.Inbox),
        Triple("Finances", "Balance, income and expenses, sponsors, facilities", Screen.Finances),
        Triple("Board", "Objectives and confidence", Screen.Board),
        Triple("History & awards", "Champions, GOAT rankings, Hall of Fame, dynasties", Screen.History),
        Triple("Tier lists", "S–D tiers per game: champions / heroes / agents by role and map, guns and loadouts", Screen.Tiers),
        Triple("Create a competition", "Your own events for any of the seven games: formats, teams, dates, prizes, every season", Screen.CompEditor),
        Triple("Patch notes", "Every balance patch of every game, new releases and draft rules", Screen.Patches),
        Triple("Items", "Every item of each MOBA by type: physical, magic, defence, support, boots, jungle", Screen.Library("items")),
        Triple("Runes, emblems & arcana", "Runes (LoL, Wild Rift), emblems (MLBB), arcana (HoK), facets and talents (Dota 2)", Screen.Library("runes")),
        Triple("Spells", "Summoner spells, battle spells and common skills", Screen.Library("spells")),
        Triple("Neutral objectives", "Drakes, Baron, Turtles, Lords, Tyrants, Roshan... and what they give", Screen.Library("objectives")),
        Triple("Schedule", "Your and followed teams' matches", Screen.Schedule),
        Triple("Stats hub", "Leaderboards, records, head-to-head, season preview & review", Screen.Stats),
        Triple("Teams", "All leagues and teams, team directory", Screen.Teams),
        Triple("Players", "Search, filter and compare every player", Screen.Players),
        Triple("World rankings", "Club and organisation rankings, countries and territories, from results", Screen.Rankings),
        Triple("Solo queue ladders", "Every server's ranked ladder: pros, streamers and unsigned talent", Screen.SoloQ),
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


/** Simulation speed vs heat: how many CPU cores the simulation may use. */
@Composable
fun SpeedSetting(vm: GameVm) {
    var mode by remember { mutableStateOf(vm.speedMode()) }
    Text("Simulation speed", color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Muted("Full speed uses every CPU core (fastest, the phone gets warm on long simulations). Balanced uses half of them; Cool uses two (slowest, coolest, easiest on the battery).", 11)
    val modes = listOf("full", "balanced", "cool")
    Tabs(listOf("Full speed", "Balanced", "Cool"), modes.indexOf(mode).coerceAtLeast(0)) { mode = modes[it]; vm.setSpeedMode(mode) }
}

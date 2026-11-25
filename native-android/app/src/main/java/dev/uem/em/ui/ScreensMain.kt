package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.comp.Stages
import dev.uem.em.core.Dates
import dev.uem.em.core.formatMoney
import dev.uem.em.model.Fixture
import dev.uem.em.model.StageType
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.titles.Catalogue
import java.text.DateFormat
import java.util.Date

/** Primary action button (filled accent). */
@Composable
fun PrimaryButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, container: Color = Ui.accent, content: Color = Color.White, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier, colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        if (icon != null) { Icon(icon, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

/** Secondary action button (outlined). */
@Composable
fun SecondaryButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, color: Color = Ui.text, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f)), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun StartScreen(vm: GameVm) {
    var name by remember { mutableStateOf("Career") }
    var fictional by remember { mutableStateOf(false) }
    var saves by remember(vm.slot, vm.unsaved, vm.busy) { mutableStateOf(vm.saves()) }
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(20.dp)).background(Ui.hero).padding(horizontal = 20.dp, vertical = 24.dp)) {
                Text("ESPORTS", color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 4.sp)
                Text("MANAGER", color = Color.White, fontWeight = FontWeight.Black, fontSize = 32.sp, letterSpacing = 2.sp)
                Gap(6)
                Text("Seven games · real teams and players · live 2D matches", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                Gap(12)
                Row(Modifier.horizontalScroll(rememberScrollState())) { for (id in Catalogue.IDS) TitleTag(id) }
            }
        }
        // The last crash, if the app closed on an error: copy it to report it.
        CrashLog.read()?.let { crash ->
            item(key = "crash") {
                var shown by remember { mutableStateOf(true) }
                if (shown) Card("The app closed unexpectedly last time") {
                    Muted("If it happens again, copy this and send it so it can be fixed.", 12)
                    Gap(6)
                    CopyableError(crash)
                    TextButton(onClick = { CrashLog.clear(); shown = false }) { Text("Dismiss", color = Ui.muted) }
                }
            }
        }
        vm.world?.let { cur ->
            item {
                Card("Continue your career") {
                    Text(cur.name, color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Muted("Season ${cur.season} · ${Dates.format(cur.day)}${if (vm.unsaved) " · unsaved changes" else ""}", 12)
                    Gap(10)
                    PrimaryButton("Back to the game", Icons.Rounded.PlayArrow, Modifier.fillMaxWidth()) { vm.tab(Screen.Home) }
                    Gap(6)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Save", Icons.Rounded.Done, Modifier.weight(1f)) { vm.save(); saves = vm.saves() }
                        SecondaryButton("Save as…", Icons.Rounded.Add, Modifier.weight(1f)) { vm.saveAsOpen = true }
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
                Gap(10)
                PrimaryButton("Generate world", Icons.Rounded.Add, Modifier.fillMaxWidth()) { vm.newGame(name, fictional) }
            }
        }
        item {
            val importer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importSave(uri) { saves = vm.saves() } }
            Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Saves on this phone (${saves.size})", Modifier.weight(1f))
                CardLink("Import a save") { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
            }
            Text("Import checks a save exported from this or another phone before adding it; nothing is overwritten.", color = Ui.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 18.dp))
            Gap(4)
        }
        if (saves.isEmpty()) item { Card { EmptyNote("No saves yet: generate a world above.") } }
        items(saves, key = { it.slot }) { s ->
            var renaming by remember { mutableStateOf(false) }
            var deleting by remember { mutableStateOf(false) }
            val open = s.slot == vm.slot && vm.world != null
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(if (open) Ui.accentSoft else Ui.card2, CircleShape), contentAlignment = Alignment.Center) {
                        Text(s.name.take(1).uppercase(), color = if (open) Ui.accent else Ui.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    HGap(12)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, color = Ui.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (open) { HGap(6); Tag("OPEN", Ui.accentSoft, Ui.accent) }
                        }
                        if (s.career.isNotBlank() || s.club.isNotBlank()) Text(listOf(s.club, s.career).filter { it.isNotBlank() }.distinct().joinToString(" · "), color = Ui.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted((if (s.season > 0) "Season ${s.season} · ${Dates.format(s.day)} · " else "") + "saved ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.modified))} · ${s.bytes / 1024} KB", 11)
                    }
                    HGap(8)
                    PrimaryButton("Load") { vm.load(s.slot) }
                }
                Row {
                    TextButton(onClick = { renaming = true }) { Text("Rename", color = Ui.muted) }
                    TextButton(onClick = { deleting = true }) { Text("Delete", color = Ui.loss) }
                }
            }
            if (renaming) {
                var label by remember { mutableStateOf(s.name) }
                androidx.compose.material3.AlertDialog(onDismissRequest = { renaming = false }, containerColor = Ui.card2,
                    confirmButton = { TextButton(onClick = { vm.renameSave(s.slot, label.ifBlank { s.name }); renaming = false; saves = vm.saves() }) { Text("Rename", color = Ui.accent) } },
                    dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel", color = Ui.muted) } },
                    title = { Text("Rename save") },
                    text = { OutlinedTextField(value = label, onValueChange = { label = it.take(40) }, singleLine = true) })
            }
            if (deleting) androidx.compose.material3.AlertDialog(onDismissRequest = { deleting = false }, containerColor = Ui.card2,
                confirmButton = { TextButton(onClick = { vm.store.delete(s.slot); deleting = false; saves = vm.saves() }) { Text("Delete", color = Ui.loss) } },
                dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel", color = Ui.muted) } },
                title = { Text("Delete ${s.name}?") },
                text = { Text("This save file is removed from the phone. Other save files of the same career are not affected.") })
        }
        item {
            var online by remember { mutableStateOf(Pics.online) }
            val stored = remember(vm.busy) { PictureStore.count() to PictureStore.bytes() }
            SectionLabel("Settings")
            Card("Pictures") {
                Muted("Download every picture the game knows — characters, weapons, team and league logos, player photos, all country flags and your database-editor links — into the app for good. They show without internet. A full download can take a few GB.", 11)
                Gap(4)
                Muted("Stored now: ${stored.first} pictures (${stored.second / (1024 * 1024)} MB)", 11)
                Gap(8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrimaryButton("Download all pictures", Icons.Rounded.Refresh) { vm.downloadAllPictures() }
                    HGap(8)
                    TextButton(onClick = { PictureStore.clear(); vm.toast = "Stored pictures deleted" }) { Text("Delete stored", color = Ui.muted) }
                }
                Tabs(listOf("Load pictures online", "Game art only"), if (online) 0 else 1) { online = it == 0; Pics.online = online; vm.setPicturesOnline(online) }
            }
            Card("Performance") { SpeedSetting(vm) }
            AppearanceCard(vm)
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
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp)) {
            Text("STEP 1 OF 2", color = Ui.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Choose your job", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
        val ids = dev.uem.em.mgmt.Roles.IDS
        Tabs(listOf("Coach & GM", "Head coach", "General manager", "Sporting director", "Owner", "Commissioner"), ids.indexOf(role)) { role = ids[it] }
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text(dev.uem.em.mgmt.Roles.LABEL[role]!!, color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Muted(dev.uem.em.mgmt.Roles.DESC[role]!! + if (!dev.uem.em.mgmt.Roles.hasBoard(role)) "" else " The board judges your results.", 12)
            Gap(14)
            Text("STEP 2 OF 2", color = Ui.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Choose your organisation", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Muted(if (w.fictional) "By reputation" else "Real organisations first, by reputation")
            Gap(8)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ui.hero).clickable { vm.chooseSpectator() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.PlayArrow, Color.White, 40.dp)
                HGap(12)
                Column(Modifier.weight(1f)) {
                    Text("Spectator mode", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("No club to manage: follow any teams, watch any match live on the 2D map, browse the whole world.", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Color.White)
            }
        }
        Gap(4)
        Tabs(listOf("All") + Catalogue.IDS.map { Catalogue.get(it).short }, if (title == null) 0 else Catalogue.IDS.indexOf(title) + 1) { i -> title = if (i == 0) null else Catalogue.IDS[i - 1] }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(orgs, key = { it.id }) { o ->
                val teams = o.teams.map { w.teams[it] }.filter { it.active }
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (teams.isNotEmpty()) TeamPic(teams[0], 44.dp) else Pic(null, o.short, 44.dp)
                        HGap(12)
                        Column(Modifier.weight(1f)) {
                            Text(o.name, color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Gap(2)
                            Row(Modifier.horizontalScroll(rememberScrollState())) { for (t in teams.distinctBy { it.titleId }) TitleTag(t.titleId) }
                            Gap(2)
                            Muted(teams.joinToString(" · ") { t -> "${t.leagueId.takeIf { it >= 0 }?.let { w.leagues[it].short } ?: "development"}${if (t.parentId >= 0) " (academy)" else ""}".trim() }, 11)
                            Muted("Reputation ${o.rep.toInt()} · balance ${formatMoney(o.balance)}", 11)
                        }
                        HGap(8)
                        PrimaryButton("Manage") { vm.chooseOrg(o.id, role) }
                    }
                }
            }
        }
    }
}

/** The next fixture of one of your teams (today first), or null. */
private fun nextUserFixture(w: World): Fixture? =
    dev.uem.em.game.Advance.userFixtureDue(w) ?: w.fixtures.filter { !it.done && it.day >= w.day && (w.isUserTeam(it.a) || w.isUserTeam(it.b)) }.minByOrNull { it.day }

private fun whenText(w: World, day: Int): String {
    val d = day - w.day
    return when { d <= 0 -> "Today"; d == 1 -> "Tomorrow"; else -> "In $d days · ${Dates.short(day)}" }
}

/** Big next-match card: both teams, when, and what to do (play live, prepare, simulate). */
@Composable
private fun NextMatchHero(vm: GameVm, w: World) {
    val f = nextUserFixture(w)
    val due = f != null && f.day <= w.day + 1
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(if (due) Ui.heroGold else Ui.hero)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        if (f == null) {
            Text("NO MATCH SCHEDULED", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Gap(4)
            Text("Your teams have no fixture yet. Continue the calendar to the next event, or check the road to qualify.", color = Color.White, fontSize = 13.sp)
            Gap(10)
            PrimaryButton("Continue", Icons.Rounded.PlayArrow, container = Color.White, content = Color(0xFF111827)) { vm.advance(GameVm.Span.NEXT_MATCH) }
            return@Column
        }
        val mine = if (w.isUserTeam(f.a)) f.a else f.b
        val comp = w.comps[f.compId]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (due) (if (f.day <= w.day) "MATCH DAY · TODAY" else "MATCH DAY · TOMORROW") else "NEXT MATCH", color = if (due) Ui.gold else Color.White.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            TitleTag(w.teams[mine].titleId)
        }
        Text(comp.name, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { vm.go(Screen.Comp(comp.id)) })
        Gap(12)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HeroTeam(vm, w, f.a, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                Text("VS", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text("Bo${f.bestOf}", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            }
            HeroTeam(vm, w, f.b, Modifier.weight(1f))
        }
        Gap(10)
        Text(whenText(w, f.day), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Gap(12)
        val coach = dev.uem.em.mgmt.Roles.can(w, "tactics")
        val pp = dev.uem.em.mgmt.Prep.planFor(w, f.id, mine)
        if (due) {
            PrimaryButton(if (coach) "Play live" else "Watch live", Icons.Rounded.PlayArrow, Modifier.fillMaxWidth(), container = Color.White, content = Color(0xFF111827)) { vm.startLive(f.id) }
            Gap(6)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Prepare", Icons.Rounded.Create, Modifier.weight(1f), Color.White) { vm.go(Screen.Prepare(f.id)) }
                SecondaryButton("Simulate", Icons.Rounded.Refresh, Modifier.weight(1f), Color.White) { vm.advance(GameVm.Span.DAY) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Go to match day", Icons.Rounded.PlayArrow, Modifier.weight(1f), container = Color.White, content = Color(0xFF111827)) { vm.advance(GameVm.Span.NEXT_MATCH) }
                SecondaryButton("Prepare", Icons.Rounded.Create, Modifier.weight(1f), Color.White) { vm.go(Screen.Prepare(f.id)) }
            }
        }
        Gap(8)
        Text(
            "Plan: " + (pp?.let { dev.uem.em.mgmt.Prep.LABEL[it.emphasis] } ?: "none yet") + " · " +
                (if (coach) "Live: you draft (or veto maps) and watch each game on the 2D map." else "Your head coach drafts and plays; you watch from the box on the 2D map."),
            color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
        )
    }
}

@Composable
private fun HeroTeam(vm: GameVm, w: World, id: Int, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = id >= 0) { vm.go(Screen.Team(id)) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val t = w.teams.getOrNull(id)
        if (t != null) TeamPic(t, 56.dp) else Box(Modifier.size(56.dp).background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text("?", color = Color.White, fontSize = 22.sp) }
        Gap(6)
        Text(t?.name ?: "TBD", color = if (t != null && w.isUserTeam(id)) Ui.gold else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (t != null) Text("Elo ${t.elo.toInt()}", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
    }
}

private class Shortcut(val label: String, val icon: ImageVector, val screen: Screen, val badge: Int = 0, val tint: Color = Ui.accent)

/** One row of the screens a manager opens most. */
@Composable
internal fun QuickActions(vm: GameVm, w: World, spectator: Boolean = false) {
    val unread = w.inbox.count { !it.read }
    val items = if (spectator) listOf(
        Shortcut("Watch", Icons.Rounded.PlayArrow, Screen.Watch, tint = Ui.red), Shortcut("Events", Icons.Rounded.Star, Screen.Comps, tint = Ui.gold),
        Shortcut("Teams", Icons.Rounded.AccountCircle, Screen.Teams), Shortcut("Players", Icons.Rounded.Face, Screen.Players, tint = Ui.accent2),
        Shortcut("Schedule", Icons.Rounded.DateRange, Screen.Schedule, tint = Ui.gold), Shortcut("Stats", Icons.AutoMirrored.Rounded.List, Screen.Stats, tint = Ui.win),
        Shortcut("Social", Icons.Rounded.ThumbUp, Screen.Social, tint = Ui.accent2), Shortcut("Rankings", Icons.Rounded.Star, Screen.Rankings, tint = Ui.gold),
    ) else listOf(
        Shortcut("Squad", Icons.Rounded.Person, Screen.Squad), Shortcut("Market", Icons.Rounded.ShoppingCart, Screen.Market, tint = Ui.win),
        Shortcut("Inbox", Icons.Rounded.Email, Screen.Inbox, unread, Ui.accent2), Shortcut("Schedule", Icons.Rounded.DateRange, Screen.Schedule, tint = Ui.gold),
        Shortcut("Finances", Icons.Rounded.Build, Screen.Finances, tint = Ui.win), Shortcut("Matches", Icons.Rounded.PlayArrow, Screen.Watch, tint = Ui.red),
        Shortcut("Social", Icons.Rounded.ThumbUp, Screen.Social, tint = Ui.accent2), Shortcut("Stats", Icons.AutoMirrored.Rounded.List, Screen.Stats, tint = Ui.gold),
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    // Wide screens: the shortcuts share the whole width; phones: a row that scrolls sideways.
    val fill = maxWidth >= (items.size * 84 + 24).dp
    Row(Modifier.fillMaxWidth().let { if (fill) it else it.horizontalScroll(rememberScrollState()) }.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in items) Column(
            (if (fill) Modifier.weight(1f) else Modifier.width(76.dp)).clip(RoundedCornerShape(14.dp)).background(Ui.card).border(1.dp, Ui.line.copy(alpha = 0.7f), RoundedCornerShape(14.dp)).clickable { vm.go(s.screen) }.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                IconBadge(s.icon, s.tint, 38.dp)
                if (s.badge > 0) Box(Modifier.align(Alignment.TopEnd)) { CountBubble(s.badge) }
            }
            Gap(6)
            Text(s.label, color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
    }
}

@Composable
private fun ClubHeader(w: World, org: dev.uem.em.model.Org, teams: List<Team>) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Pic(teams.firstNotNullOfOrNull { Pics.team(it) }, org.short, 48.dp)
        HGap(12)
        Column(Modifier.weight(1f)) {
            Text(org.name, color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${dev.uem.em.mgmt.Roles.LABEL[w.role] ?: "Manager"} · season ${w.season}", color = Ui.muted, fontSize = 12.sp)
            Gap(3)
            Row(Modifier.horizontalScroll(rememberScrollState())) { for (t in teams.distinctBy { it.titleId }) TitleTag(t.titleId) }
        }
    }
}

@Composable
fun HomeScreen(vm: GameVm, w: World) {
    if (w.userOrg < 0) { SpectatorHome(vm, w); return }
    val org = w.orgs[w.userOrg]
    val teams = org.teams.map { w.teams[it] }.filter { it.active }
    // One column on phones; on wide screens (unfolded, tablets, sideways) the cards flow into two or more.
    LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Adaptive(380.dp), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "header", span = StaggeredGridItemSpan.FullLine) { ClubHeader(w, org, teams) }
        item(key = "resume", span = StaggeredGridItemSpan.FullLine) { ResumeLiveCard(vm, w) }
        item(key = "tips", span = StaggeredGridItemSpan.FullLine) { TipCard(vm, w) }
        item(key = "next", span = StaggeredGridItemSpan.FullLine) { NextMatchHero(vm, w) }
        item(key = "actions", span = StaggeredGridItemSpan.FullLine) { QuickActions(vm, w) }
        item(key = "tiles", span = StaggeredGridItemSpan.FullLine) {
            val fin = dev.uem.em.mgmt.Economy.monthly(w, org)
            val net = fin.income - fin.costs
            val unread = w.inbox.count { !it.read }
            val pending = w.offers.count { it.status == "pending" && w.isUserTeam(w.players[it.playerId].teamId) }
            val b = w.board
            SectionLabel("Club at a glance")
            Tiles(listOfNotNull(
                Tile("Balance", formatMoney(org.balance), "${if (net >= 0) "+" else ""}${formatMoney(net)} / month", if (org.balance >= 0) Ui.text else Ui.loss),
                Tile("Reputation", "${org.rep.toInt()}", "%,.0f fans".format(org.fans), Rate.color(org.rep)),
                Tile("Divisions", "${teams.size}", teams.joinToString { Catalogue.get(it.titleId).short }),
                Tile("Trophies", "${teams.sumOf { it.trophies.size }}", "prestige ${org.prestige.toInt()}", Ui.gold),
                Tile("Unread inbox", "$unread", if (pending > 0) "$pending offer(s) for your players" else null, if (unread > 0) Ui.accent else Ui.text),
                if (b != null) Tile("Board confidence", "${b.confidence.toInt()}/100", if (b.contractEnd > 0) "your contract: end of ${b.contractEnd}" else null, Rate.color(b.confidence)) else null,
            ))
        }
        item(key = "road") {
            // Qualification: invitations waiting for an answer, and each team's road to the next events.
            val inv = dev.uem.em.comp.Ecosystem.pending(w)
            Card("Road to the next events", right = { if (inv.isNotEmpty()) Tag("${inv.size} INVITATION(S)", Ui.goldSoft, Ui.gold) }) {
                for (tm in teams.filter { it.nationalOf.isEmpty() }) {
                    val road = dev.uem.em.comp.Ecosystem.road(w, tm).firstOrNull { !it.comp.done && it.comp.tier != dev.uem.em.model.CompTier.LEAGUE }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.go(Screen.Road(tm.id)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TeamPic(tm, 24.dp); HGap(10)
                        Column(Modifier.weight(1f)) {
                            Text(road?.comp?.name ?: "No upcoming event in reach", color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted(road?.let { "${it.status}: ${it.detail.lineSequence().first()}" } ?: tm.name, 11)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ui.muted)
                    }
                }
            }
        }
        item(key = "upcoming") {
            val next = w.fixtures.filter { !it.done && it.day >= w.day && (w.isUserTeam(it.a) || w.isUserTeam(it.b)) }.sortedBy { it.day }.take(6)
            if (next.isNotEmpty()) Card("Upcoming matches", right = { CardLink("Schedule") { vm.go(Screen.Schedule) } }) {
                for (f in next) {
                    val mine = if (w.isUserTeam(f.a)) f.a else f.b
                    val opp = if (mine == f.a) f.b else f.a
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.go(Screen.Match(f.id)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(54.dp)) { Text(Dates.short(f.day), color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Muted(w.comps[f.compId].short, 9) }
                        TitleTag(w.teams[mine].titleId)
                        Text("vs ", color = Ui.muted, fontSize = 12.sp)
                        Column(Modifier.weight(1f)) { if (opp >= 0) TeamChip(w, opp, vm, 20.dp) else Muted("TBD") }
                        Tag("Bo${f.bestOf}")
                    }
                }
            }
        }
        for (team in teams) {
            val t = Catalogue.get(team.titleId)
            item(key = "t${team.id}") {
                Card(null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.go(Screen.Team(team.id)) }) {
                        TeamPic(team, 36.dp)
                        HGap(10)
                        Column(Modifier.weight(1f)) {
                            Text(team.name, color = Ui.gold, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) { TitleTag(team.titleId); Muted("Strength ${"%.1f".format(Lineups.strength(w, team))} · Elo ${team.elo.toInt()}", 11) }
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ui.muted)
                    }
                    val upcoming = w.fixtures.filter { !it.done && (it.a == team.id || it.b == team.id) }.minByOrNull { it.day }
                    val recent = w.fixtures.filter { it.done && (it.a == team.id || it.b == team.id) }.sortedByDescending { it.day }.take(5)
                    Gap(10)
                    if (upcoming != null) {
                        val opp = if (upcoming.a == team.id) upcoming.b else upcoming.a
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().background(Ui.card2, RoundedCornerShape(10.dp)).clickable { vm.go(Screen.Comp(upcoming.compId)) }.padding(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Muted("NEXT · ${Dates.format(upcoming.day, true)} · ${w.comps[upcoming.compId].short} · Bo${upcoming.bestOf}", 10)
                                Gap(2)
                                TeamChip(w, opp, vm, 20.dp, bold = true)
                            }
                        }
                    } else {
                        val next = w.comps.filter { it.status == 0 && it.titleId == team.titleId && (it.leagueId == team.leagueId || it.leagueId < 0) }.minByOrNull { it.startDay }
                        Muted(if (next != null) "No match scheduled · next event: ${next.name} (${Dates.short(next.startDay)})" else "No match scheduled.")
                    }
                    if (recent.isNotEmpty()) {
                        Gap(10)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Form", color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(46.dp))
                            FormStrip(recent.reversed().map { it.winner == team.id })
                        }
                        Gap(4)
                        for (f in recent) {
                            val won = f.winner == team.id
                            val opp = if (f.a == team.id) f.b else f.a
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { vm.go(Screen.Match(f.id)) }.padding(vertical = 4.dp)) {
                                Text(if (won) "W" else "L", color = if (won) Ui.win else Ui.loss, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                                Text(if (f.a == team.id) "${f.scoreA}-${f.scoreB}" else "${f.scoreB}-${f.scoreA}", color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.width(34.dp))
                                Box(Modifier.weight(1f)) { TeamChip(w, opp, vm, 18.dp) }
                                Muted(Dates.short(f.day), 11)
                            }
                        }
                    }
                    // Current league standings.
                    val comp = w.comps.lastOrNull { it.leagueId == team.leagueId && it.status == 1 } ?: w.comps.lastOrNull { it.leagueId == team.leagueId && it.done && it.season == w.season }
                    val stage = comp?.stages?.firstOrNull { it.type == StageType.RR || it.type == StageType.DRR }
                    if (comp != null && stage != null && stage.standings.isNotEmpty()) {
                        Gap(10)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(comp.name, color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            CardLink("Event") { vm.go(Screen.Comp(comp.id)) }
                        }
                        StandingsTable(vm, w, Stages.sortStandings(w, stage.standings))
                    }
                    if (t.isMoba || !t.isMoba) Unit
                }
            }
        }
        w.board?.let { b ->
            item(key = "board") {
                Card("Board ${b.season}", right = { CardLink("Board") { vm.go(Screen.Board) } }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Confidence", color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(84.dp))
                        Bar(b.confidence, color = Rate.color(b.confidence), modifier = Modifier.weight(1f), h = 8.dp)
                        Text(" ${b.confidence.toInt()}", color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Gap(6)
                    for (o in b.objectives) Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when (o.status) { "met" -> "✔"; "failed" -> "✖"; else -> "•" }, color = when (o.status) { "met" -> Ui.win; "failed" -> Ui.loss; else -> Ui.muted }, modifier = Modifier.width(20.dp))
                        Text(o.text, color = Ui.text, fontSize = 13.sp)
                    }
                }
            }
        }
        item(key = "inbox") {
            val msgs = w.inbox.filter { !it.read }.takeLast(4).reversed().ifEmpty { w.inbox.takeLast(3).reversed() }
            if (msgs.isNotEmpty()) Card("Inbox", right = { CardLink("Open") { vm.go(Screen.Inbox) } }) {
                for (m in msgs) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { vm.go(Screen.Inbox) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(if (m.read) Color.Transparent else Ui.accent, CircleShape))
                    HGap(10)
                    Column(Modifier.weight(1f)) {
                        Text(m.subject, color = Ui.text, fontSize = 13.sp, fontWeight = if (m.read) FontWeight.Normal else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted("${m.from} · ${Dates.short(m.day)}", 10)
                    }
                }
            }
        }
        item(key = "followed") { FollowedCard(vm, w) }
        item(key = "news") {
            Card("News") {
                val news = w.news.takeLast(8).reversed()
                news.forEachIndexed { i, n ->
                    LinkedText(n.title, Ui.text, 14.sp, FontWeight.SemiBold)
                    if (n.body.isNotBlank() && n.body != n.title) LinkedText(n.body, Ui.muted, 12.sp, maxLines = 3)
                    Muted(Dates.format(n.day), 10)
                    if (i < news.size - 1) Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(0.5.dp).background(Ui.line))
                }
                if (w.news.isEmpty()) EmptyNote("Nothing yet.")
            }
        }
    }
}

private class MoreEntry(val title: String, val sub: String, val icon: ImageVector, val screen: Screen, val tint: Color = Ui.accent, val badge: Int = 0)

@Composable
fun MoreScreen(vm: GameVm, w: World) {
    val unread = w.inbox.count { !it.read }
    val sections = listOf(
        "Your club" to listOf(
            MoreEntry("Inbox", if (unread > 0) "$unread unread" else "Messages, offers, reports", Icons.Rounded.Email, Screen.Inbox, Ui.accent2, unread),
            MoreEntry("Finances", "Balance, income and expenses, sponsors, facilities", Icons.Rounded.Build, Screen.Finances, Ui.win),
            MoreEntry("Business", "Sponsor deals, content creators, your venue and fan events", Icons.Rounded.ShoppingCart, Screen.Business, Ui.gold),
            MoreEntry("Board", "Objectives and confidence", Icons.Rounded.Warning, Screen.Board, Ui.gold),
            MoreEntry("Settings", "Difficulty, autosave, pictures, your name", Icons.Rounded.Settings, Screen.Settings, Ui.muted),
        ),
        "Matches & events" to listOf(
            MoreEntry("Match centre", "Watch any match live on the 2D map, recorded replays", Icons.Rounded.PlayArrow, Screen.Watch, Ui.red),
            MoreEntry("Schedule", "Your and followed teams' matches", Icons.Rounded.DateRange, Screen.Schedule, Ui.gold),
            MoreEntry("Create a competition", "Your own events for any of the seven games: formats, teams, dates, prizes, every season", Icons.Rounded.Add, Screen.CompEditor),
            MoreEntry("Amateur & development circuits", "Community clubs, Community Cups, Amateur Championship, University League, Game Changers; prospects", Icons.Rounded.Favorite, Screen.Circuits, Ui.win),
        ),
        "World" to listOf(
            MoreEntry("Teams", "All leagues and teams, team directory", Icons.Rounded.AccountCircle, Screen.Teams),
            MoreEntry("Players", "Search, filter and compare every player", Icons.Rounded.Face, Screen.Players, Ui.accent2),
            MoreEntry("Staff", "Every coach, analyst, scout and support staff member: clubs, ability, titles", Icons.Rounded.Person, Screen.StaffDir, Ui.win),
            MoreEntry("Stats hub", "Leaderboards, records, head-to-head, season preview & review", Icons.AutoMirrored.Rounded.List, Screen.Stats, Ui.gold),
            MoreEntry("World rankings", "Club and organisation rankings, countries and territories, from results", Icons.Rounded.Star, Screen.Rankings, Ui.gold),
            MoreEntry("World", "Games, national scenes, global rankings, organisations", Icons.Rounded.Place, Screen.World, Ui.win),
            MoreEntry("Solo queue ladders", "Every server's ranked ladder: pros, streamers and unsigned talent", Icons.Rounded.Search, Screen.SoloQ),
            MoreEntry("Social media", "Fans, analysts and teams on Agora, Chirp, Lens and Fanbase: reactions, ratings, debates, hot takes", Icons.Rounded.ThumbUp, Screen.Social, Ui.accent2),
            MoreEntry("History & awards", "Champions, GOAT rankings, Hall of Fame, dynasties", Icons.Rounded.Star, Screen.History, Ui.gold),
        ),
        "Game knowledge" to listOf(
            MoreEntry("Tier lists", "S–D tiers per game: champions / heroes / agents by role and map, guns and loadouts", Icons.Rounded.Star, Screen.Tiers, Ui.red),
            MoreEntry("Patch notes", "Every balance patch of every game, new releases and draft rules", Icons.Rounded.Info, Screen.Patches),
            MoreEntry("Items", "Every item of each MOBA by type: physical, magic, defence, support, boots, jungle", Icons.Rounded.ShoppingCart, Screen.Library("items"), Ui.gold),
            MoreEntry("Runes, emblems & arcana", "Runes (LoL, Wild Rift), emblems (MLBB), arcana (HoK), facets and talents (Dota 2)", Icons.Rounded.Lock, Screen.Library("runes"), Ui.accent2),
            MoreEntry("Spells", "Summoner spells, battle spells and common skills", Icons.Rounded.Refresh, Screen.Library("spells"), Ui.win),
            MoreEntry("Neutral objectives", "Drakes, Baron, Turtles, Lords, Tyrants, Roshan... and what they give", Icons.Rounded.LocationOn, Screen.Library("objectives"), Ui.red),
        ),
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Two tiles a row on phones, more on wide screens.
    val perRow = (maxWidth / 210.dp).toInt().coerceIn(2, 5)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        for ((head, entries) in sections) {
            item(key = "h$head") { SectionLabel(head) }
            for (pair in entries.chunked(perRow)) item(key = "e${pair[0].title}") {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 8.dp)) {
                    for (e in pair) Column(
                        Modifier.weight(1f).fillMaxHeight().padding(4.dp).clip(RoundedCornerShape(16.dp)).background(Ui.card).border(1.dp, Ui.line.copy(alpha = 0.7f), RoundedCornerShape(16.dp)).clickable { vm.go(e.screen) }.padding(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(e.icon, e.tint, 34.dp)
                            Spacer(Modifier.weight(1f))
                            CountBubble(e.badge, Ui.accent)
                        }
                        Gap(8)
                        Text(e.title, color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Gap(2)
                        Text(e.sub, color = Ui.muted, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
                    }
                    repeat(perRow - pair.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        item {
            SectionLabel("Game")
            Card("Save") {
                Muted("Saves are written in the background and the game saves itself every simulated week and when you leave the app.")
                Gap(10)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Save now", Icons.Rounded.Done, Modifier.weight(1f)) { vm.save() }
                    SecondaryButton("Title screen", Icons.AutoMirrored.Rounded.ExitToApp, Modifier.weight(1f)) { vm.save(silent = true); vm.tab(Screen.Start) }
                }
            }
        }
        item { Card("Career") { Muted("${w.name} · ${w.players.size} players · ${w.teams.count { it.active }} active teams · ${w.comps.size} competitions · ${w.transfers.size} transfers") } }
    }
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

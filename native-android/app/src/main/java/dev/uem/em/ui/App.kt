package dev.uem.em.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.Dates
import dev.uem.em.model.World
import kotlinx.coroutines.delay

@Composable
fun AppRoot(vm: GameVm, exit: () -> Unit) {
    // Theme: dark, light or the phone's setting; the status / navigation bar icons follow it.
    val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
    val want = if (vm.themeMode == "light" || (vm.themeMode == "system" && !sysDark)) Ui.LIGHT else Ui.DARK
    if (Ui.palette !== want) androidx.compose.runtime.SideEffect { Ui.palette = want }
    val activity = androidx.compose.ui.platform.LocalContext.current as? dev.uem.em.MainActivity
    LaunchedEffect(want.dark) { activity?.edgeToEdge(want.dark) }
    MaterialTheme(colorScheme = Ui.scheme, typography = Ui.typography, shapes = Ui.shapes) {
        BackHandler { if (!vm.back()) exit() }
        Box(Modifier.fillMaxSize().background(Ui.bg)) {
            val w = vm.world
            Sheets.world = w
            Links.go = if (vm.live == null && busy0(vm)) { s -> vm.go(s) } else null
            val busy = vm.busy
            // While the simulation runs on other threads the world is not read by any screen.
            if (busy != null) BusyScreen(vm, busy)
            else if (w == null || vm.screen == Screen.Start) StartScreen(vm)
            else if (vm.screen == Screen.PickOrg || (w.userOrg < 0 && !w.spectator)) PickOrgScreen(vm, w)
            else if (w.sacked) Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { JobOffersScreen(vm, w) }
            else if (vm.live != null) Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) { LiveScreen(vm, w, vm.live!!) }
            else SideMenu(vm, w) { openMenu -> Shell(vm, w, openMenu) {
                    @Suppress("UNUSED_EXPRESSION") vm.version
                    when (val s = vm.screen) {
                        Screen.Home -> HomeScreen(vm, w)
                        Screen.Comps -> CompsScreen(vm, w)
                        Screen.Teams -> TeamsScreen(vm, w)
                        Screen.Players -> PlayersScreen(vm, w)
                        Screen.More -> MoreScreen(vm, w)
                        Screen.Squad -> SquadScreen(vm, w)
                        Screen.Market -> MarketScreen(vm, w)
                        Screen.Inbox -> InboxScreen(vm, w)
                        Screen.Finances -> FinancesScreen(vm, w)
                        Screen.Board -> BoardScreen(vm, w)
                        Screen.History -> HistoryScreen(vm, w)
                        Screen.Patches -> PatchesScreen(vm, w)
                        Screen.Schedule -> ScheduleScreen(vm, w)
                        Screen.Stats -> StatsScreen(vm, w)
                        Screen.Settings -> SettingsScreen(vm, w)
                        is Screen.Comp -> CompScreen(vm, w, s.id)
                        is Screen.Team -> TeamScreen(vm, w, s.id)
                        is Screen.Org -> OrgScreen(vm, w, s.id)
                        is Screen.Player -> PlayerScreen(vm, w, s.id)
                        is Screen.Match -> MatchScreen(vm, w, s.id)
                        is Screen.Compare -> CompareScreen(vm, w, s.a, s.b)
                        Screen.World -> WorldScreen(vm, w)
                        Screen.Watch -> WatchScreen(vm, w)
                        Screen.Tiers -> TierScreen(vm, w)
                        Screen.CompEditor -> CompEditorScreen(vm, w)
                        Screen.SoloQ -> SoloQueueScreen(vm, w)
                        Screen.Rankings -> RankingsScreen(vm, w)
                        is Screen.Library -> LibraryScreen(vm, w, s.kind)
                        Screen.Social -> SocialScreen(vm, w)
                        is Screen.Road -> RoadScreen(vm, w, s.teamId)
                        is Screen.Prepare -> PrepareScreen(vm, w, s.fixtureId)
                        Screen.Circuits -> CircuitsScreen(vm, w)
                        is Screen.Replay -> ReplayScreen(vm, w, s.fixtureId, s.game)
                        Screen.Search -> SearchScreen(vm, w)
                        Screen.Business -> BusinessScreen(vm, w)
                        else -> HomeScreen(vm, w)
                    }
            } }
            if (vm.saveAsOpen && w != null && busy == null) SaveAsDialog(vm, w)
            val sheet = Sheets.open
            if (sheet != null && w != null && busy == null) InfoSheet(w, sheet.first, sheet.second) { Sheets.open = null }
            vm.toast?.let { msg ->
                LaunchedEffect(msg) { delay(3500); if (vm.toast == msg) vm.toast = null }
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp, start = 20.dp, end = 20.dp).widthIn(max = 520.dp)
                        .background(Ui.card3, RoundedCornerShape(14.dp)).border(1.dp, Ui.line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Info, null, tint = Ui.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(msg, color = Ui.text, fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * The frame around every screen. Tall screens: top bar, content, bottom bar. Wide screens (tablets,
 * the inner screen of a foldable, any phone held sideways): a navigation rail on the left instead of
 * the bottom bar, so the content keeps its height. Bars are drawn under the system bars and cutouts
 * (their colour reaches the screen edges) and the content is padded by those insets.
 */
@Composable
private fun Shell(vm: GameVm, w: World, openMenu: () -> Unit, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rail = maxWidth >= 600.dp || maxWidth > maxHeight
        val tall = maxHeight >= 560.dp
        if (rail) Row(Modifier.fillMaxSize()) {
            NavRail(vm, tall)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                TopBar(vm, w, openMenu, WindowInsetsSides.Top + WindowInsetsSides.End)
                Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Bottom))) { content() }
            }
        } else Column(Modifier.fillMaxSize()) {
            TopBar(vm, w, openMenu, WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) { content() }
            BottomBar(vm)
        }
    }
}

@Composable
private fun BusyScreen(vm: GameVm, b: Busy) {
    Box(Modifier.fillMaxSize().background(Ui.bg).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 520.dp).background(Ui.card, Ui.shape).border(1.dp, Ui.line, Ui.shape).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBadge(Icons.Rounded.PlayArrow, Ui.accent, 52.dp)
            Gap(14)
            Text(b.label, color = Ui.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Gap(16)
            if (b.total > 0) {
                LinearProgressIndicator(progress = { b.done.toFloat() / b.total }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = Ui.accent, trackColor = Ui.card2, drawStopIndicator = {})
                Gap(10)
                Muted(if (b.label.contains("picture", true)) "${b.done} / ${b.total} pictures" else "${b.done} / ${b.total} days", 13)
                Gap(14)
                TextButton(onClick = { vm.stop() }) { Text("Stop here", color = Ui.muted) }
            } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = Ui.accent, trackColor = Ui.card2)
        }
    }
}

/** The name shown in the top bar for each screen (where you are at a glance). */
fun screenTitle(w: World, s: Screen): String = when (s) {
    Screen.Home -> if (w.userOrg >= 0) "Home" else "Spectator"
    Screen.Comps -> "Events"
    Screen.Teams -> "Teams"
    Screen.Players -> "Players"
    Screen.More -> "More"
    Screen.Squad -> "Squad"
    Screen.Market -> "Transfer market"
    Screen.Inbox -> "Inbox"
    Screen.Finances -> "Finances"
    Screen.Board -> "Board"
    Screen.History -> "History & awards"
    Screen.Patches -> "Patch notes"
    Screen.Schedule -> "Schedule"
    Screen.Stats -> "Stats hub"
    Screen.Settings -> "Settings"
    is Screen.Comp -> w.comps.getOrNull(s.id)?.name ?: "Event"
    is Screen.Team -> w.teams.getOrNull(s.id)?.name ?: "Team"
    is Screen.Org -> w.orgs.getOrNull(s.id)?.name ?: "Organisation"
    is Screen.Player -> w.players.getOrNull(s.id)?.handle ?: "Player"
    is Screen.Match -> w.fixtures.getOrNull(s.id)?.let { f -> "${w.teams.getOrNull(f.a)?.short ?: "TBD"} vs ${w.teams.getOrNull(f.b)?.short ?: "TBD"}" } ?: "Match"
    is Screen.Compare -> "Compare players"
    Screen.World -> "World & nations"
    Screen.Watch -> "Match centre"
    Screen.Tiers -> "Tier lists"
    Screen.CompEditor -> "Create a competition"
    Screen.SoloQ -> "Solo queue ladders"
    Screen.Rankings -> "World rankings"
    is Screen.Library -> when (s.kind) { "items" -> "Items"; "runes" -> "Runes, emblems & arcana"; "spells" -> "Spells"; else -> "Neutral objectives" }
    Screen.Social -> "Social media"
    is Screen.Road -> "Road · ${w.teams.getOrNull(s.teamId)?.short ?: ""}"
    is Screen.Prepare -> "Prepare for match"
    Screen.Circuits -> "Development circuits"
    is Screen.Replay -> "Replay"
    Screen.Search -> "Search"
    Screen.Business -> "Business"
    Screen.Start -> "Esports Manager"
    Screen.PickOrg -> "Choose your job"
}

@Composable
private fun TopBar(vm: GameVm, w: World, openMenu: () -> Unit = {}, sides: WindowInsetsSides = WindowInsetsSides.Top + WindowInsetsSides.Horizontal) {
    var menu by remember { mutableStateOf(false) }
    var years by remember { mutableStateOf<GameVm.Span?>(null) }
    val deep = vm.stack.size > 1
    Column(Modifier.fillMaxWidth().background(Ui.card).windowInsetsPadding(WindowInsets.safeDrawing.only(sides))) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = openMenu) { Icon(Icons.Rounded.Menu, contentDescription = "Menu", tint = Ui.text) }
            IconButton(onClick = { vm.go(Screen.Search) }) { Icon(Icons.Rounded.Search, contentDescription = "Search", tint = Ui.text) }
            if (deep) IconButton(onClick = { vm.back() }, modifier = Modifier.padding(start = 0.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Ui.text) }
            Column(Modifier.weight(1f).padding(start = 2.dp, end = 6.dp)) {
                Text(screenTitle(w, vm.screen), color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Dates.format(w.day, true), color = Ui.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    val due = dev.uem.em.game.Advance.userFixtureDue(w)
                    if (due != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(if (due.day <= w.day) "MATCH DAY" else "MATCH TOMORROW", color = Ui.gold, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1,
                            modifier = Modifier.background(Ui.goldSoft, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                    } else if (vm.unsaved) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(6.dp).background(Ui.gold, CircleShape))
                        Text(" unsaved", color = Ui.gold, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            Button(
                onClick = { vm.advance(GameVm.Span.NEXT_MATCH) }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Continue", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Simulate further and save", tint = Ui.text) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Ui.card2) {
                    Text("SIMULATE", color = Ui.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    for ((label, span) in listOf("1 day" to GameVm.Span.DAY, "1 week" to GameVm.Span.WEEK, "1 month" to GameVm.Span.MONTH, "To World Championships" to GameVm.Span.WORLDS, "To end of season" to GameVm.Span.SEASON_END)) {
                        DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; vm.advance(span) })
                    }
                    for (span in listOf(GameVm.Span.YEARS_2, GameVm.Span.YEARS_5, GameVm.Span.YEARS_10)) {
                        DropdownMenuItem(text = { Text("${span.years} seasons…") }, onClick = { menu = false; years = span })
                    }
                    HorizontalDivider(color = Ui.line)
                    DropdownMenuItem(text = { Text("Save now") }, onClick = { menu = false; vm.save() })
                    DropdownMenuItem(text = { Text("Save as new file…") }, onClick = { menu = false; vm.saveAsOpen = true })
                }
            }
        }
        HorizontalDivider(color = Ui.line, thickness = 0.5.dp)
    }
    years?.let { span -> YearsDialog(vm, w, span) { years = null } }
}

/** Confirmation before simulating several seasons (top bar menu and sidebar). */
@Composable
fun YearsDialog(vm: GameVm, w: World, span: GameVm.Span, close: () -> Unit) {
    val last = w.season + span.years - 1
    AlertDialog(onDismissRequest = close, containerColor = Ui.card2,
        confirmButton = { TextButton(onClick = { close(); vm.advance(span) }) { Text("Simulate", color = Ui.accent) } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel", color = Ui.muted) } },
        title = { Text("Simulate ${span.years} seasons?") },
        text = { Text("Plays every match to the end of season $last. " + (if (w.userOrg >= 0) "Your staff pick lineups, train the team, sign and renew players, hire staff and upgrade facilities meanwhile. " else "") + "The game is saved after each season and you can stop at any time.") })
}

private class NavItem(val label: String, val icon: ImageVector, val screen: Screen, val badge: Int = 0)

private fun navItems(vm: GameVm): List<NavItem> {
    val unread = vm.world?.inbox?.count { !it.read } ?: 0
    val spectator = (vm.world?.userOrg ?: 0) < 0
    return if (spectator) listOf(NavItem("Home", Icons.Rounded.Home, Screen.Home), NavItem("Watch", Icons.Rounded.PlayArrow, Screen.Watch), NavItem("Events", Icons.Rounded.Star, Screen.Comps), NavItem("Teams", Icons.Rounded.AccountCircle, Screen.Teams), NavItem("More", Icons.AutoMirrored.Rounded.List, Screen.More))
    else listOf(NavItem("Home", Icons.Rounded.Home, Screen.Home), NavItem("Events", Icons.Rounded.Star, Screen.Comps), NavItem("Squad", Icons.Rounded.Person, Screen.Squad), NavItem("Market", Icons.Rounded.ShoppingCart, Screen.Market), NavItem("More", Icons.AutoMirrored.Rounded.List, Screen.More, unread))
}

/** Side navigation for wide screens: the same tabs as the bottom bar, plus shortcuts when there is room. */
@Composable
private fun NavRail(vm: GameVm, tall: Boolean) {
    val base = navItems(vm)
    val extra = if (!tall) emptyList() else listOf(NavItem("Watch", Icons.Rounded.PlayArrow, Screen.Watch), NavItem("Schedule", Icons.Rounded.DateRange, Screen.Schedule), NavItem("Inbox", Icons.Rounded.Email, Screen.Inbox, vm.world?.inbox?.count { !it.read } ?: 0))
        .filter { e -> base.none { it.screen == e.screen } && (e.screen != Screen.Inbox || (vm.world?.userOrg ?: -1) >= 0) }
    val root = vm.stack.first()
    Row(Modifier.fillMaxHeight().background(Ui.card)) {
        Column(
            Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)).width(80.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (it in base + extra) {
                val on = root == it.screen
                Column(Modifier.width(72.dp).clip(RoundedCornerShape(14.dp)).clickable { vm.tab(it.screen) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 52.dp, height = 30.dp).background(if (on) Ui.accentSoft else Color.Transparent, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                            Icon(it.icon, contentDescription = it.label, tint = if (on) Ui.accent else Ui.muted, modifier = Modifier.size(22.dp))
                        }
                        if (it.badge > 0) Box(Modifier.align(Alignment.TopEnd)) { CountBubble(it.badge) }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(it.label, color = if (on) Ui.text else Ui.muted, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
        Box(Modifier.fillMaxHeight().width(0.5.dp).background(Ui.line))
    }
}

@Composable
private fun BottomBar(vm: GameVm) {
    val items = navItems(vm)
    Column(Modifier.fillMaxWidth().background(Ui.card).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
        HorizontalDivider(color = Ui.line, thickness = 0.5.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            val root = vm.stack.first()
            for (it in items) {
                val on = root == it.screen
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { vm.tab(it.screen) }.padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 54.dp, height = 28.dp).background(if (on) Ui.accentSoft else Color.Transparent, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                            Icon(it.icon, contentDescription = it.label, tint = if (on) Ui.accent else Ui.muted, modifier = Modifier.size(22.dp))
                        }
                        if (it.badge > 0) Box(Modifier.align(Alignment.TopEnd).padding(end = 6.dp)) { CountBubble(it.badge) }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(it.label, color = if (on) Ui.text else Ui.muted, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

@Composable
fun HGap(wd: Int = 8) = Spacer(Modifier.width(wd.dp))

private fun busy0(vm: GameVm) = vm.busy == null

/** Save to a new file with a name (the current file stays as it is). */
@Composable
fun SaveAsDialog(vm: GameVm, w: World) {
    var label by remember { mutableStateOf(vm.defaultSaveLabel(w)) }
    AlertDialog(onDismissRequest = { vm.saveAsOpen = false }, containerColor = Ui.card2,
        confirmButton = { TextButton(onClick = { vm.saveAsOpen = false; vm.saveAs(label) }) { Text("Save", color = Ui.accent) } },
        dismissButton = { TextButton(onClick = { vm.saveAsOpen = false }) { Text("Cancel", color = Ui.muted) } },
        title = { Text("Save as a new file") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(value = label, onValueChange = { label = it.take(40) }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text("Your current file is kept. From now on, Save and autosave write to this new file. You can keep as many files as you like.", fontSize = 12.sp)
            }
        })
}

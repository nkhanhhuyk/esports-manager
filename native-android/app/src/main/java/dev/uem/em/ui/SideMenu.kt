package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

/** Sidebar with every screen and the simulation / save actions, opened from the menu icon in the top bar. */
@Composable
fun SideMenu(vm: GameVm, w: World, content: @Composable (open: () -> Unit) -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var years by remember { mutableStateOf<GameVm.Span?>(null) }
    fun close() { scope.launch { drawer.close() } }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, scrimColor = Color(0x99000000), drawerContent = {
        ModalDrawerSheet(drawerContainerColor = Ui.bg, drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp), modifier = Modifier.width(310.dp)) {
            Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                // Header: who you are and when.
                val org = w.orgs.getOrNull(w.userOrg)
                Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Ui.hero).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val team = org?.teams?.map { w.teams[it] }?.firstOrNull { it.active }
                        if (team != null) TeamPic(team, 40.dp) else IconBadge(Icons.Rounded.PlayArrow, Color.White, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(org?.name ?: if (w.spectator) "Spectator" else "Esports Manager", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (org != null) (dev.uem.em.mgmt.Roles.LABEL[w.role] ?: "Manager") else "Following ${w.followed.size} team(s)", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.size(10.dp))
                    Text("${Dates.format(w.day, true)} · season ${w.season}", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                }
                val root = vm.stack.firstOrNull()
                fun go(s: Screen) { vm.tab(s); close() }
                @Composable fun head(t: String) = Text(t, color = Ui.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 14.dp, bottom = 4.dp))
                @Composable fun item(t: String, icon: ImageVector, sub: String? = null, screen: Screen? = null, badge: Int = 0, onClick: () -> Unit) {
                    val on = screen != null && root == screen
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 1.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Ui.accentSoft else Color.Transparent).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, tint = if (on) Ui.accent else Ui.muted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t, color = if (on) Ui.text else Ui.text.copy(alpha = 0.92f), fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                            if (sub != null) Muted(sub, 11)
                        }
                        CountBubble(badge, Ui.accent)
                    }
                }
                head("SIMULATE")
                item("Continue to the next match", Icons.Rounded.PlayArrow) { close(); vm.advance(GameVm.Span.NEXT_MATCH) }
                item("1 day", Icons.Rounded.Refresh) { close(); vm.advance(GameVm.Span.DAY) }
                item("1 week", Icons.Rounded.DateRange) { close(); vm.advance(GameVm.Span.WEEK) }
                item("1 month", Icons.Rounded.DateRange) { close(); vm.advance(GameVm.Span.MONTH) }
                item("To the World Championships", Icons.Rounded.Star, vm.daysToWorlds(w)?.let { "$it days" } ?: "under way or finished this season") { close(); vm.advance(GameVm.Span.WORLDS) }
                item("To the end of the season", Icons.Rounded.Done) { close(); vm.advance(GameVm.Span.SEASON_END) }
                item("2 / 5 / 10 seasons", Icons.Rounded.Refresh, "your staff run the club meanwhile") { close(); years = GameVm.Span.YEARS_2 }
                head("MY CLUB")
                if (w.userOrg >= 0) {
                    item("Home", Icons.Rounded.Home, screen = Screen.Home) { go(Screen.Home) }
                    item("Squad, tactics, training, staff", Icons.Rounded.Person, screen = Screen.Squad) { go(Screen.Squad) }
                    item("Market", Icons.Rounded.ShoppingCart, screen = Screen.Market) { go(Screen.Market) }
                    item("Inbox", Icons.Rounded.Email, screen = Screen.Inbox, badge = w.inbox.count { !it.read }) { go(Screen.Inbox) }
                    item("Finances & facilities", Icons.Rounded.Build, screen = Screen.Finances) { go(Screen.Finances) }
                    item("Board", Icons.Rounded.Warning, screen = Screen.Board) { go(Screen.Board) }
                } else item("Home", Icons.Rounded.Home, screen = Screen.Home) { go(Screen.Home) }
                head("MATCHES")
                item("Match centre", Icons.Rounded.PlayArrow, "watch any match live, recent replays", Screen.Watch) { go(Screen.Watch) }
                item("Events", Icons.Rounded.Star, "standings, brackets, results", Screen.Comps) { go(Screen.Comps) }
                item("Schedule", Icons.Rounded.DateRange, screen = Screen.Schedule) { go(Screen.Schedule) }
                item("Create a competition", Icons.Rounded.Add, screen = Screen.CompEditor) { go(Screen.CompEditor) }
                head("WORLD")
                item("Teams", Icons.Rounded.AccountCircle, screen = Screen.Teams) { go(Screen.Teams) }
                item("Players", Icons.Rounded.Face, screen = Screen.Players) { go(Screen.Players) }
                item("Stats hub", Icons.AutoMirrored.Rounded.List, screen = Screen.Stats) { go(Screen.Stats) }
                item("Tier lists", Icons.Rounded.Star, screen = Screen.Tiers) { go(Screen.Tiers) }
                item("Solo queue ladders", Icons.Rounded.Search, "ranks on every server; unsigned talent", Screen.SoloQ) { go(Screen.SoloQ) }
                item("Social media", Icons.Rounded.ThumbUp, screen = Screen.Social) { go(Screen.Social) }
                item("Patch notes", Icons.Rounded.Info, screen = Screen.Patches) { go(Screen.Patches) }
                item("History & awards", Icons.Rounded.Star, screen = Screen.History) { go(Screen.History) }
                item("World, nations & rankings", Icons.Rounded.Search, screen = Screen.World) { go(Screen.World) }
                head("GAME")
                item("Save now", Icons.Rounded.Done, "overwrites the current file") { close(); vm.save() }
                item("Save as new file…", Icons.Rounded.Add, "keeps the current file") { close(); vm.saveAsOpen = true }
                item("Load or manage saves", Icons.AutoMirrored.Rounded.ExitToApp, "all save files: load, rename, delete, import") { close(); vm.tab(Screen.Start) }
                item("Settings", Icons.Rounded.Settings, "job, delegation, back-up/export, replays, pictures", Screen.Settings) { go(Screen.Settings) }
                item("Everything else…", Icons.AutoMirrored.Rounded.List, "libraries, circuits, rankings", Screen.More) { go(Screen.More) }
                Box(Modifier.size(8.dp))
            }
        }
    }) { content { scope.launch { drawer.open() } } }
    years?.let { YearsPicker(vm, w) { years = null } }
}

/** Pick 2, 5 or 10 seasons, then confirm. */
@Composable
private fun YearsPicker(vm: GameVm, w: World, close: () -> Unit) {
    var span by remember { mutableStateOf<GameVm.Span?>(null) }
    val s = span
    if (s != null) { YearsDialog(vm, w, s, close); return }
    androidx.compose.material3.AlertDialog(onDismissRequest = close, containerColor = Ui.card2,
        confirmButton = {}, dismissButton = { androidx.compose.material3.TextButton(onClick = close) { Text("Cancel", color = Ui.muted) } },
        title = { Text("Simulate several seasons") },
        text = {
            Column {
                for (o in listOf(GameVm.Span.YEARS_2, GameVm.Span.YEARS_5, GameVm.Span.YEARS_10)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(Ui.card3).clickable { span = o }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${o.years} seasons", color = Ui.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Muted("to the end of ${w.season + o.years - 1}", 12)
                    }
                }
            }
        })
}

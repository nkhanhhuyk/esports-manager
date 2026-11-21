package dev.uem.em.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.Dates
import kotlinx.coroutines.delay

@Composable
fun AppRoot(vm: GameVm, exit: () -> Unit) {
    MaterialTheme(colorScheme = Ui.scheme) {
        BackHandler { if (!vm.back()) exit() }
        Box(Modifier.fillMaxSize().background(Ui.bg)) {
            val w = vm.world
            Sheets.world = w
            val busy = vm.busy
            // While the simulation runs on other threads the world is not read by any screen.
            if (busy != null) BusyScreen(vm, busy)
            else if (w == null || vm.screen == Screen.Start) StartScreen(vm)
            else if (vm.screen == Screen.PickOrg || (w.userOrg < 0 && !w.spectator)) PickOrgScreen(vm, w)
            else if (w.sacked) Column(Modifier.fillMaxSize().statusBarsPadding()) { JobOffersScreen(vm, w) }
            else if (vm.live != null) Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { LiveScreen(vm, w, vm.live!!) }
            else Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TopBar(vm, w)
                Box(Modifier.weight(1f)) {
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
                        is Screen.Player -> PlayerScreen(vm, w, s.id)
                        is Screen.Match -> MatchScreen(vm, w, s.id)
                        is Screen.Compare -> CompareScreen(vm, w, s.a, s.b)
                        Screen.World -> WorldScreen(vm, w)
                        Screen.Watch -> WatchScreen(vm, w)
                        Screen.Tiers -> TierScreen(vm, w)
                        is Screen.Replay -> ReplayScreen(vm, w, s.fixtureId, s.game)
                        else -> HomeScreen(vm, w)
                    }
                }
                BottomBar(vm)
            }
            val sheet = Sheets.open
            if (sheet != null && w != null && busy == null) InfoSheet(w, sheet.first, sheet.second) { Sheets.open = null }
            vm.toast?.let { msg ->
                LaunchedEffect(msg) { delay(3500); if (vm.toast == msg) vm.toast = null }
                Text(msg, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp, start = 24.dp, end = 24.dp).background(Color(0xEE222B40), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun BusyScreen(vm: GameVm, b: Busy) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(b.label, color = Ui.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        if (b.total > 0) {
            LinearProgressIndicator(progress = { b.done.toFloat() / b.total }, modifier = Modifier.fillMaxWidth(), color = Ui.accent, trackColor = Ui.card2)
            Spacer(Modifier.height(8.dp))
            Muted("${b.done} / ${b.total} days")
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { vm.stop() }) { Text("Stop here", color = Ui.muted) }
        } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Ui.accent, trackColor = Ui.card2)
    }
}

@Composable
private fun TopBar(vm: GameVm, w: dev.uem.em.model.World) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Ui.card).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (vm.stack.size > 1) TextButton(onClick = { vm.back() }) { Text("←", color = Ui.text, fontSize = 18.sp) }
        Column(Modifier.weight(1f)) {
            Text(Dates.format(w.day, true), color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Muted("Season ${w.season}${if (vm.unsaved) " · unsaved" else ""}", 11)
        }
        Button(onClick = { vm.advance(GameVm.Span.NEXT_MATCH) }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent), shape = RoundedCornerShape(10.dp)) { Text("Continue ▸") }
        Box {
            TextButton(onClick = { menu = true }) { Text("▾", color = Ui.text, fontSize = 18.sp) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for ((label, span) in listOf("1 day" to GameVm.Span.DAY, "1 week" to GameVm.Span.WEEK, "1 month" to GameVm.Span.MONTH, "To end of season" to GameVm.Span.SEASON_END)) {
                    DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; vm.advance(span) })
                }
                DropdownMenuItem(text = { Text("Save now") }, onClick = { menu = false; vm.save() })
            }
        }
    }
}

@Composable
private fun BottomBar(vm: GameVm) {
    val unread = vm.world?.inbox?.count { !it.read } ?: 0
    val spectator = (vm.world?.userOrg ?: 0) < 0
    val items = if (spectator) listOf("Home" to Screen.Home, "Watch" to Screen.Watch, "Events" to Screen.Comps, "Teams" to Screen.Teams, "More" to Screen.More)
    else listOf("Home" to Screen.Home, "Events" to Screen.Comps, "Squad" to Screen.Squad, "Market" to Screen.Market, (if (unread > 0) "More ($unread)" else "More") to Screen.More)
    Row(Modifier.fillMaxWidth().background(Ui.card).padding(vertical = 4.dp)) {
        val root = vm.stack.first()
        for ((label, s) in items) {
            TextButton(onClick = { vm.tab(s) }, modifier = Modifier.weight(1f)) {
                Text(label, color = if (root == s) Ui.accent else Ui.muted, fontSize = 12.sp, fontWeight = if (root == s) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

@Composable
fun HGap(wd: Int = 8) = Spacer(Modifier.width(wd.dp))

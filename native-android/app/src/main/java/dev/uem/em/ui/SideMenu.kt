package dev.uem.em.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.Dates
import dev.uem.em.model.World
import kotlinx.coroutines.launch

/** Sidebar with every screen and the simulation / save actions, opened from ☰ in the top bar. */
@Composable
fun SideMenu(vm: GameVm, w: World, content: @Composable (open: () -> Unit) -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun close() { scope.launch { drawer.close() } }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
        ModalDrawerSheet(drawerContainerColor = Ui.card, modifier = Modifier.width(300.dp)) {
            Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                Text(w.orgs.getOrNull(w.userOrg)?.name ?: if (w.spectator) "Spectator" else "Esports Manager", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(16.dp, 8.dp))
                Muted("  ${Dates.format(w.day, true)} · season ${w.season}", 12)
                fun go(s: Screen) { vm.tab(s); close() }
                @Composable fun head(t: String) = Text(t, color = Ui.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 4.dp))
                @Composable fun item(t: String, sub: String? = null, onClick: () -> Unit) = Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(t, color = Ui.text, fontSize = 14.sp); if (sub != null) Muted(sub, 11)
                }
                head("SIMULATE")
                item("Continue to the next match") { close(); vm.advance(GameVm.Span.NEXT_MATCH) }
                item("1 day") { close(); vm.advance(GameVm.Span.DAY) }
                item("1 week") { close(); vm.advance(GameVm.Span.WEEK) }
                item("1 month") { close(); vm.advance(GameVm.Span.MONTH) }
                item("To the World Championships", vm.daysToWorlds(w)?.let { "$it days" } ?: "under way or finished this season") { close(); vm.advance(GameVm.Span.WORLDS) }
                item("To the end of the season") { close(); vm.advance(GameVm.Span.SEASON_END) }
                item("2 / 5 / 10 seasons", "from the ▾ menu next to Continue (staff run your club meanwhile)") { close() }
                head("MY CLUB")
                if (w.userOrg >= 0) {
                    item("Home") { go(Screen.Home) }
                    item("Squad, tactics, training, staff") { go(Screen.Squad) }
                    item("Market") { go(Screen.Market) }
                    item("Inbox (${w.inbox.count { !it.read }} unread)") { go(Screen.Inbox) }
                    item("Finances & facilities") { go(Screen.Finances) }
                    item("Board") { go(Screen.Board) }
                } else item("Home") { go(Screen.Home) }
                head("MATCHES")
                item("Match centre", "watch any match live, recent replays") { go(Screen.Watch) }
                item("Events", "standings, brackets, results") { go(Screen.Comps) }
                item("Schedule") { go(Screen.Schedule) }
                item("Create a competition") { go(Screen.CompEditor) }
                head("WORLD")
                item("Teams") { go(Screen.Teams) }
                item("Players") { go(Screen.Players) }
                item("Stats hub") { go(Screen.Stats) }
                item("Tier lists") { go(Screen.Tiers) }
                item("Patch notes") { go(Screen.Patches) }
                item("History & awards") { go(Screen.History) }
                item("World, nations & rankings") { go(Screen.World) }
                head("GAME")
                item("Save now", "overwrites the current file") { close(); vm.save() }
                item("Save as new file…", "keeps the current file") { close(); vm.saveAsOpen = true }
                item("Load or manage saves", "all save files: load, rename, delete, import") { close(); vm.tab(Screen.Start) }
                item("Settings", "job, delegation, back-up/export, replays, pictures") { go(Screen.Settings) }
                item("More…") { go(Screen.More) }
            }
        }
    }) { content { scope.launch { drawer.open() } } }
}

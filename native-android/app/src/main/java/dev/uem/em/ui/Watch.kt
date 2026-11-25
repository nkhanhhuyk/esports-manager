package dev.uem.em.ui

import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.model.CompTier
import dev.uem.em.model.Fixture
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue

/** Importance for ordering the match centre: internationals first, then top leagues, then the rest. */
private fun weight(w: World, f: Fixture): Int {
    val c = w.comps[f.compId]
    var v = when (c.tier) { CompTier.WORLDS -> 400; CompTier.INTERNATIONAL -> 300; CompTier.LEAGUE -> 200; else -> 100 }
    if (f.nodeId.isNotEmpty()) v += 40
    if (f.a in w.followed || f.b in w.followed) v += 500
    if (w.isUserTeam(f.a) || w.isUserTeam(f.b)) v += 1000
    return v + ((w.teams.getOrNull(f.a)?.elo ?: 1500.0) + (w.teams.getOrNull(f.b)?.elo ?: 1500.0)).toInt() / 100
}

/** "Watch live" / "Play live" / "Go to match day" for one fixture. */
@Composable
fun WatchButton(vm: GameVm, w: World, f: Fixture) {
    if (f.done || f.a < 0 || f.b < 0) return
    val mine = (w.isUserTeam(f.a) || w.isUserTeam(f.b)) && dev.uem.em.mgmt.Roles.can(w, "tactics")
    if (f.day <= w.day + 1) Button(onClick = { vm.startLive(f.id, spectate = !mine) }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text(if (mine) "Play live" else "Watch live", fontSize = 12.sp) }
    else TextButton(onClick = { vm.advanceAndWatch(f.id) }) { Text("Go to match day & ${if (mine) "play" else "watch"}", color = Ui.accent, fontSize = 12.sp) }
}

@Composable
private fun WatchRow(vm: GameVm, w: World, f: Fixture) {
    val c = w.comps[f.compId]
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).background(if (w.isUserTeam(f.a) || w.isUserTeam(f.b)) Ui.card2 else Ui.card, RoundedCornerShape(10.dp)).clickable { vm.go(Screen.Match(f.id)) }.padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TitleTag(f.titleId)
            Text(c.name, color = if (c.tier == CompTier.WORLDS) Ui.gold else Ui.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Muted("${Dates.short(f.day)} · Bo${f.bestOf}", 11)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Column(Modifier.weight(1f)) {
                for ((tid, sc) in listOf(f.a to f.scoreA, f.b to f.scoreB)) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { if (tid >= 0) TeamChip(w, tid, vm, 18.dp, bold = f.winner == tid) else Muted("TBD") }
                    if (f.done) Num("$sc", if (f.winner == tid) Ui.win else Ui.muted, bold = f.winner == tid)
                }
            }
            HGap(8)
            if (!f.done) WatchButton(vm, w, f) else TextButton(onClick = { vm.go(Screen.Match(f.id)) }) { Text("Report & replay", color = Ui.accent, fontSize = 12.sp) }
        }
    }
}

/** Match centre: watch any match live on the 2D map, or open recorded replays. */
@Composable
fun WatchScreen(vm: GameVm, w: World) {
    var tab by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf<String?>(null) }
    val list = remember(tab, title, vm.version) {
        when (tab) {
            0 -> w.fixtures.filter { !it.done && it.day <= w.day + 1 && it.a >= 0 && it.b >= 0 }
            1 -> w.fixtures.filter { !it.done && it.day in (w.day + 2)..(w.day + 10) && it.a >= 0 && it.b >= 0 }
            else -> w.fixtures.filter { it.done && it.day >= w.day - 30 && Advance.shouldRecord(w, it) }
        }.filter { title == null || it.titleId == title }.sortedWith(compareByDescending<Fixture> { weight(w, it) }.thenBy { it.day }).take(120)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Muted("Watch any match live: both coaches draft (shown step by step), then every game plays on the 2D map. The result counts — it is the same simulation as when you do not watch.", 12)
            }
        }
        item { ResumeLiveCard(vm, w) }
        item { Tabs(listOf("Today & tomorrow", "Next 10 days", "Recent replays"), tab) { tab = it } }
        item { Tabs(listOf("All") + Catalogue.IDS.map { Catalogue.get(it).short }, if (title == null) 0 else Catalogue.IDS.indexOf(title) + 1) { title = if (it == 0) null else Catalogue.IDS[it - 1] } }
        if (list.isEmpty()) item { Card { Muted(when (tab) { 0 -> "No matches today or tomorrow. Try the next days, or continue the calendar."; 1 -> "Nothing scheduled in the next ten days yet."; else -> "No recorded replays in the last 30 days (Settings → Replays chooses which matches keep one)." }) } }
        items(list, key = { it.id }) { f -> WatchRow(vm, w, f) }
        item { Gap(16) }
    }
}

/** An unfinished live series (kept across app restarts). */
@Composable
fun ResumeLiveCard(vm: GameVm, w: World) {
    val st = w.live ?: return
    val f = w.fixtures.getOrNull(st.fixtureId) ?: return
    if (f.done) return
    val wins = IntArray(2).also { a -> st.games.forEach { a[it.winner]++ } }
    Card("Unfinished live series") {
        Text("${w.teams[f.a].name} ${wins[0]} – ${wins[1]} ${w.teams[f.b].name}", color = Ui.text, fontWeight = FontWeight.Bold)
        Muted("${w.comps[f.compId].name} · Bo${f.bestOf} · ${st.games.size} game(s) played. Resume where you stopped; if you continue the calendar instead, the coaches finish it from this score.", 12)
        Gap(4)
        Button(onClick = { vm.startLive(f.id, st.spectator) }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text("Resume") }
    }
}

/** A recorded game from a match report, on the 2D map. */
@Composable
fun ReplayScreen(vm: GameVm, w: World, fixtureId: Int, game: Int) {
    val f = w.fixtures[fixtureId]
    val rec = remember(fixtureId) { BoxStore.get(w, fixtureId) }
    val g = rec?.games?.getOrNull(game)
    val t = Catalogue.get(f.titleId)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Ui.card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Replay · ${if (t.isMoba) "Game" else "Map"} ${game + 1}", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Muted("${w.teams[f.a].short} ${f.scoreA} – ${f.scoreB} ${w.teams[f.b].short} · ${w.comps[f.compId].name}", 12)
            }
            if (rec != null && game + 1 < rec.games.size && rec.games[game + 1].replay != null) TextButton(onClick = { vm.back(); vm.go(Screen.Replay(fixtureId, game + 1)) }) { Text("Next game ›", color = Ui.accent) }
        }
        if (g == null) Card { Muted("This game is not available.") }
        else if (t.isMoba) MobaViewer(w, f, g) else TacticalViewer(w, f, g)
    }
}

/** Home for a spectator career: followed teams, matches to watch, results. */
@Composable
fun SpectatorHome(vm: GameVm, w: World) {
    val followed = w.followed.mapNotNull { w.teams.getOrNull(it) }
    val today = w.fixtures.filter { !it.done && it.day <= w.day + 1 && it.a >= 0 && it.b >= 0 }.sortedByDescending { weight(w, it) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(androidx.compose.material.icons.Icons.Rounded.PlayArrow, Ui.accent, 48.dp)
                HGap(12)
                Column {
                    Text("Spectator", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Muted("Season ${w.season} · ${Dates.format(w.day)} · following ${followed.size} team(s)")
                }
            }
        }
        item { QuickActions(vm, w, spectator = true) }
        item { TipCard(vm, w) }
        item {
            Tiles(listOf(
                Tile("Matches today & tomorrow", "${today.size}", "watch any of them live"),
                Tile("Followed teams", "${followed.size}", if (w.stopForFollowed) "the calendar stops before they play" else "calendar does not stop"),
                Tile("Competitions live", "${w.comps.count { it.status == 1 }}", "${w.comps.count { it.done && it.season == w.season }} finished this season"),
            ))
        }
        item { ResumeLiveCard(vm, w) }
        if (today.isNotEmpty()) item {
            Card("Watch now", right = { CardLink("Match centre") { vm.go(Screen.Watch) } }) {
                for (f in today.take(6)) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Match(f.id)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TitleTag(f.titleId)
                    Column(Modifier.weight(1f)) {
                        Text("${w.teams[f.a].short} vs ${w.teams[f.b].short}", color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Muted("${w.comps[f.compId].short} · Bo${f.bestOf} · ${if (f.day <= w.day) "today" else "tomorrow"}", 10)
                    }
                    WatchButton(vm, w, f)
                }
            }
        }
        if (followed.isEmpty()) item { Card("Follow teams") { Muted("Open any team and tap Follow: the calendar stops before they play so you can watch them.") } }
        for (team in followed) item(key = "f${team.id}") {
            val next = w.fixtures.filter { !it.done && (it.a == team.id || it.b == team.id) }.minByOrNull { it.day }
            val last = w.fixtures.filter { it.done && (it.a == team.id || it.b == team.id) }.maxByOrNull { it.day }
            Card(null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TitleTag(team.titleId)
                    Column(Modifier.weight(1f)) { TeamChip(w, team.id, vm, 22.dp, bold = true) }
                    Muted("Elo ${team.elo.toInt()}", 11)
                }
                if (last != null) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Match(last.id)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    val won = last.winner == team.id
                    Text(if (won) "W" else "L", color = if (won) Ui.win else Ui.loss, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                    Text("${if (last.a == team.id) "${last.scoreA}-${last.scoreB}" else "${last.scoreB}-${last.scoreA}"} vs ${w.teams[if (last.a == team.id) last.b else last.a].short}", color = Ui.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Muted(Dates.short(last.day), 11)
                }
                if (next != null) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Muted("Next: ${Dates.short(next.day)} vs ${w.teams.getOrNull(if (next.a == team.id) next.b else next.a)?.short ?: "TBD"} · ${w.comps[next.compId].short}", 12)
                    Column(Modifier.weight(1f)) {}
                    WatchButton(vm, w, next)
                }
            }
        }
        item {
            val big = w.comps.filter { it.done && it.season == w.season && it.tier != CompTier.CHALLENGER && it.placements.isNotEmpty() }.sortedByDescending { it.startDay }.take(8)
            if (big.isNotEmpty()) Card("Latest champions") {
                for (c in big) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Comp(c.id)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    TitleTag(c.titleId)
                    Text(c.short, color = Ui.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TeamChip(w, c.placements[0], vm, 16.dp, bold = true)
                }
            }
        }
        item {
            Card("News") {
                for (n in w.news.takeLast(8).reversed()) {
                    LinkedText(n.title, Ui.text, 14.sp, FontWeight.SemiBold)
                    Muted(Dates.format(n.day), 10)
                    Gap(6)
                }
            }
        }
    }
}

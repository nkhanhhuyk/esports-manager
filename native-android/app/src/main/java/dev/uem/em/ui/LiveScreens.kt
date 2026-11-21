package dev.uem.em.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.GameRecord
import dev.uem.em.model.World
import dev.uem.em.sim.Ev
import dev.uem.em.titles.TitleDef
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Viewer defaults (tests start paused part-way through). */
object LiveDefaults {
    var autoplay = true
    var startFraction = 0f
}

@Composable
fun LiveScreen(vm: GameVm, w: World, live: LiveMatch) {
    @Suppress("UNUSED_EXPRESSION") live.rev
    val s = live.series
    // Spectating: the veto and draft play out one step at a time.
    var auto by remember(live) { mutableStateOf(LiveDefaults.autoplay) }
    LaunchedEffect(live, live.stage, auto, live.rev) {
        if (live.spectator && auto && (live.stage == "veto" || live.stage == "draft")) {
            delay(650)
            live.aiTick()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Ui.card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${s.teams[0].short} ${s.wins[0]} - ${s.wins[1]} ${s.teams[1].short}", color = Ui.text, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Muted("${s.comp.name} · Bo${s.f.bestOf}${if (s.comp.fearless) " · hard Fearless" else ""}${if (live.spectator) " · watching" else ""}${if (live.resumed) " · resumed" else ""}")
            }
            if (live.stage != "result") TextButton(onClick = { live.simRest(); vm.edit() }) { Text(if (live.spectator) "Skip to result" else "Sim rest", color = Ui.muted) }
        }
        if (live.spectator && (live.stage == "veto" || live.stage == "draft")) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { auto = !auto }) { Text(if (auto) "Pause" else "Play", color = Ui.accent) }
            TextButton(onClick = { live.aiTick() }) { Text("Next step", color = Ui.accent) }
            TextButton(onClick = { live.aiFinish() }) { Text("Skip ${if (live.stage == "veto") "veto" else "draft"}", color = Ui.muted) }
        }
        when (live.stage) {
            "veto" -> VetoView(live)
            "draft" -> DraftView(live)
            "watch" -> {
                val g = live.game
                val done: @Composable () -> Unit = { WatchDone(live) }
                if (g != null) { if (s.t.isMoba) MobaViewer(w, s.f, g, done) else TacticalViewer(w, s.f, g, done) }
            }
            else -> ResultView(vm, w, live)
        }
    }
}

@Composable
private fun VetoView(live: LiveMatch) {
    val v = live.veto ?: return
    val s = live.series
    val cur = v.current()
    LazyColumn(Modifier.fillMaxSize()) {
        item { Card("Map veto") { Muted(if (live.spectator) (cur?.let { "${s.teams[it.first].short} to ${if (it.second) "ban" else "pick"}…" } ?: "Veto complete") else if (cur?.first == live.me) "Your turn: ${if (cur.second) "BAN" else "PICK"} a map" else "Waiting…"); for (l in v.log) Muted(l, 12) } }
        items(v.pool.size) { i ->
            val m = v.pool[i]
            val name = s.t.maps.first { it.id == m }.name
            val prof = s.lineups[if (live.me >= 0) live.me else 0].filter { it >= 0 }.map { s.w.players[it].profMap[m] ?: 40.0 }.average()
            val prof2 = s.lineups[if (live.me >= 0) 1 - live.me else 1].filter { it >= 0 }.map { s.w.players[it].profMap[m] ?: 40.0 }.average()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).background(Ui.card, RoundedCornerShape(10.dp)).clickable(enabled = cur?.first == live.me) { live.vetoAct(m) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = Ui.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Muted(if (live.spectator) "${s.teams[0].short} ${prof.toInt()} · ${s.teams[1].short} ${prof2.toInt()}" else "your map comfort ${prof.toInt()} · theirs ${prof2.toInt()}")
            }
        }
    }
}

@Composable
private fun DraftView(live: LiveMatch) {
    val d = live.draft ?: return
    val s = live.series
    val t = s.t
    val cur = d.current()
    val myTurn = cur?.first == live.me
    var role by remember { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            val st = live.setup
            if (st != null && t.isMoba) {
                val sideA = if (t.id == "dota2") "Radiant" else "Blue"
                Muted("${s.teams[st.blue].short} on $sideA · ${s.teams[st.firstPick].short} first pick · ${s.teams[st.chooser].short} ${if (st.byLoser) "lost the last game" else "won the coin toss"} and chose ${if (st.choice == 0) "the side" else "the pick order"}", 12)
            }
            for (side in 0..1) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(s.teams[side].short, color = if (side == live.me) Ui.gold else Ui.text, fontSize = 12.sp, modifier = Modifier.width(48.dp))
                for (id in d.picks[side]) { Pic(Pics.char(id), t.charIndex[id]?.name ?: id, 26.dp); HGap(2) }
            }
            if (d.bans.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Muted("Bans ", 11); for (b in d.bans) { Box(Modifier.alpha(0.5f)) { Pic(Pics.char(b), t.charIndex[b]?.name ?: b, 20.dp) }; HGap(2) } }
            Gap(4)
            Text(when {
                d.done || !d.sequential -> "Draft complete"
                myTurn -> "Your turn: ${if (cur!!.second) "BAN" else "PICK"}"
                live.spectator && cur != null -> "${s.teams[cur.first].short} ${if (cur.second) "ban" else "pick"}…"
                else -> "Opponent…"
            }, color = if (myTurn) Ui.accent else Ui.muted, fontWeight = FontWeight.Bold)
            Row {
                if (!d.done && d.sequential && !live.spectator) TextButton(onClick = { live.autoDraft() }) { Text("Coach finishes the draft", color = Ui.accent) }
                if (d.done || !d.sequential) Button(onClick = { live.startGame() }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text("Start game ${s.gi + 1}") }
            }
        }
        if (d.sequential && !d.done && !live.spectator) {
            Tabs(listOf("All") + t.lineupRoles.map { it.short }, role + 1) { role = it - 1 }
            val chars = t.characters.filter { role < 0 || it.fits(t.lineupRoles[role]) }.sortedByDescending { s.patch.charMeta[it.id] ?: 0.0 }
            LazyVerticalGrid(columns = GridCells.Adaptive(68.dp), modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(chars, key = { it.id }) { c ->
                    val ok = myTurn && d.legal(live.me, cur!!.second, c.id)
                    Column(Modifier.padding(3.dp).alpha(if (ok || !myTurn) 1f else 0.3f).clickable(enabled = ok) { live.draftAct(c.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Pic(Pics.char(c.id), c.name, 48.dp)
                        Text(c.name, color = Ui.text, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val m = s.patch.charMeta[c.id] ?: 0.0
                        Text(if (m >= 0) "▲%.1f".format(m) else "▼%.1f".format(-m), color = if (m >= 0) Ui.win else Ui.loss, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun WatchDone(live: LiveMatch) {
    val g = live.game ?: return
    val s = live.series
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${s.teams[g.winner].name} win game ${s.gi}", color = Ui.gold, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Button(onClick = { live.afterGame() }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text(if (s.done) "Result" else "Next game") }
    }
}

@Composable
private fun ResultView(vm: GameVm, w: World, live: LiveMatch) {
    val s = live.series
    val f = s.f
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card("Final") {
                Text("${s.teams[0].name} ${s.wins[0]} - ${s.wins[1]} ${s.teams[1].name}", color = Ui.text, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Gap()
                Row {
                    Button(onClick = { vm.endLive(); vm.go(Screen.Match(f.id)) }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text("Match report") }
                    HGap()
                    TextButton(onClick = { vm.endLive() }) { Text("Continue", color = Ui.accent) }
                }
            }
        }
    }
}

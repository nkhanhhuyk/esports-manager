package dev.uem.em.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
    // The game being watched does not count in the series score until its replay has played out.
    var revealed by remember(live.game) { mutableStateOf(false) }
    val shown = s.wins.copyOf().also { wn -> val g = live.game; if (live.stage == "watch" && !revealed && g != null && wn[g.winner] > 0) wn[g.winner]-- }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Ui.card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${s.teams[0].short} ${shown[0]} - ${shown[1]} ${s.teams[1].short}", color = Ui.text, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Muted("${s.comp.name} · Bo${s.f.bestOf}${if (s.fearlessMode != "none") " · " + dev.uem.em.comp.Fearless.LABEL[s.fearlessMode] else ""}${if (live.spectator) " · watching" else ""}${if (live.resumed) " · resumed" else ""}")
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
                // Your shooter maps: tactical timeouts from the viewer.
                LiveHooks.onTimeout = if (live.me >= 0 && !s.t.isMoba) { r, sec -> live.callTimeout(r, sec) } else null
                // Your MOBA games: coach calls from the viewer.
                LiveHooks.onCall = if (live.me >= 0 && s.t.isMoba) { c, sec -> live.callMoba(c, sec) } else null
                LiveHooks.calls = live.myCalls.toList(); LiveHooks.callsLeft = live.callsPerGame - live.myCalls.size
                LiveHooks.timeoutsLeft = live.timeoutsLeft()
                LiveHooks.seriesGames = live.series.games.toList()
                val done: @Composable () -> Unit = { LaunchedEffect(g) { revealed = true }; WatchDone(live) }
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
            val name = s.t.mapName(m)
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
                Muted("Game ${s.gi + 1}: " + selectionText(t, s.teams.map { it.short }, st.chooser, st.choice, st.byLoser, st.blue, st.firstPick), 12)
            }
            // Fearless: champions that cannot be played this game (from the earlier games of the series).
            val locked = s.locked()
            if (s.fearlessMode != "none" && (locked[0].isNotEmpty() || locked[1].isNotEmpty())) {
                Text("${dev.uem.em.comp.Fearless.LABEL[s.fearlessMode]} — unavailable this game", color = Ui.gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                val rows = if (s.fearlessMode == "soft") listOf(s.teams[0].short to locked[0], s.teams[1].short to locked[1]) else listOf("Both" to locked[0])
                for ((label, set) in rows) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = Ui.muted, fontSize = 10.sp, modifier = Modifier.width(40.dp))
                    for (id in set.sortedBy { t.charIndex[it]?.name ?: it }) { Box(Modifier.alpha(0.55f)) { CharPic(id, t.charIndex[id]?.name ?: id, 22.dp) }; HGap(2) }
                }
            }
            for (side in 0..1) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(s.teams[side].short, color = if (side == live.me) Ui.gold else Ui.text, fontSize = 12.sp, modifier = Modifier.width(48.dp))
                for (id in d.picks[side]) { CharPic(id, t.charIndex[id]?.name ?: id, 26.dp); HGap(2) }
            }
            if (d.bans.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Muted("Bans ", 11); for (b in d.bans) { Box(Modifier.alpha(0.5f)) { CharPic(b, t.charIndex[b]?.name ?: b, 20.dp) }; HGap(2) } }
            // The last step, when the earlier games shaped it: how the other side is adapting.
            val lastStep = d.order.size - 1
            d.order.lastOrNull()?.split('|')?.takeIf { it.size >= 3 }?.let { p ->
                val tm = p[1].toIntOrNull() ?: return@let
                val why = dev.uem.em.sim.DraftNotes.forStep(t, d.notes, lastStep, s.teams[1 - tm].short)
                if (why.isNotEmpty()) Text("↻ ${s.teams[tm].short} ${if (p[0] == "b") "banned" else "picked"} ${t.charIndex[p[2]]?.name ?: p[2]}: ${why.joinToString(" · ")}", color = Ui.gold, fontSize = 11.sp)
            }
            // From game 2: what each side has shown so far in the series.
            if (s.games.isNotEmpty() && t.isMoba) {
                val intel = remember(s.gi) { s.intel() }
                for (side in 0..1) {
                    val won = intel.won[side].entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(4)
                    if (won.isNotEmpty()) Muted("${s.teams[side].short} won with " + won.joinToString { "${t.charIndex[it.key]?.name ?: it.key}${if (it.value > 1) " ×${it.value}" else ""}" }, 10)
                }
            }
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
        // Draft assistant: what the analysts would ban or pick now, and why (tap to take it).
        if (d.sequential && !d.done && !live.spectator && myTurn && cur != null) {
            val tips = remember(d.step, cur.second) { d.suggest(live.me, cur.second) }
            if (tips.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(Ui.accentSoft, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(10.dp)) {
                Text("Draft assistant — suggested ${if (cur.second) "bans" else "picks"}", color = Ui.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                for ((c, why) in tips) Row(Modifier.fillMaxWidth().clickable(enabled = d.legal(live.me, cur.second, c.id)) { live.draftAct(c.id) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    CharPic(c.id, c.name, 30.dp, tap = false); HGap(8)
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Muted(why.joinToString(" · "), 11)
                    }
                    Text(if (cur.second) "Ban" else "Pick", color = Ui.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (d.sequential && !d.done && !live.spectator) {
            Tabs(listOf("All") + t.lineupRoles.map { it.short }, role + 1) { role = it - 1 }
            val chars = t.characters.filter { role < 0 || it.fits(t.lineupRoles[role]) }.sortedByDescending { s.patch.charMeta[it.id] ?: 0.0 }
            LazyVerticalGrid(columns = GridCells.Adaptive(68.dp), modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(chars, key = { it.id }) { c ->
                    val ok = myTurn && d.legal(live.me, cur!!.second, c.id)
                    Column(Modifier.padding(3.dp).alpha(if (ok || !myTurn) 1f else 0.3f).clickable(enabled = ok) { live.draftAct(c.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        CharPic(c.id, c.name, 48.dp, tap = false)
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
    @Suppress("UNUSED_EXPRESSION") live.rev
    val g = live.game ?: return
    val s = live.series
    var subs by remember(live) { mutableStateOf(false) }
    val canSub = live.me >= 0 && !s.done && s.bench[live.me].isNotEmpty()
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${s.teams[g.winner].name} win game ${s.gi}", color = Ui.gold, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (canSub) TextButton(onClick = { subs = true }) { Text("Substitutions", color = Ui.accent) }
            Button(onClick = { live.afterGame() }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text(if (s.done) "Result" else "Next game") }
        }
        // Changes made for the next game (yours), shown until it starts.
        for (n in s.pending().filter { it.side == live.me }) Muted("Next game: " + dev.uem.em.sim.Subs.describe(s.w, s.t, n), 11)
    }
    if (subs && canSub) SubsDialog(live) { subs = false }
}

/**
 * Between games of your series: swap a starter for a bench player who plays the position (main or
 * second), for the next game. Your coach's suggestion is on top (a bad game, the next map, the champion
 * pool left under Fearless); tapping a player back in undoes a change.
 */
@Composable
private fun SubsDialog(live: LiveMatch, onClose: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") live.rev
    val s = live.series
    val w = s.w
    val t = s.t
    val me = live.me
    val last = s.games.lastOrNull()
    val map = s.nextMap()
    androidx.compose.material3.AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("Done", color = Ui.accent) } },
        title = { Text("Substitutions · game ${s.gi + 1}${if (!t.isMoba && t.maps.size > 1) " · " + (t.mapName(map)) else ""}") }, text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                live.suggestion()?.let { sg ->
                    val inP = w.players[sg.inPid]
                    val outP = s.lineups[me].getOrNull(sg.slot)?.let { w.players.getOrNull(it) }
                    Row(Modifier.fillMaxWidth().background(Ui.card2, RoundedCornerShape(8.dp)).clickable { live.substitute(sg.slot, sg.inPid) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Coach suggests: ${inP.handle} for ${outP?.handle ?: "?"}", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Muted("${t.lineupRoles[sg.slot].name} · ${dev.uem.em.sim.Subs.reasonText(sg.reason)}", 11)
                        }
                        Text("Make it", color = Ui.accent, fontSize = 12.sp)
                    }
                    Gap()
                } ?: Muted("Your coach would keep this lineup.", 11)
                for ((slot, role) in t.lineupRoles.withIndex()) {
                    val cur = s.lineups[me].getOrNull(slot)?.takeIf { it >= 0 }?.let { w.players[it] }
                    val r = last?.lines?.firstOrNull { it.team == me && it.playerId == cur?.id }?.rating
                    Gap(6)
                    Text("${role.name}: ${cur?.handle ?: "—"}${if (r != null) " · last game %.2f".format(r) else ""}", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    val opts = s.bench[me].map { w.players[it] }.filter { dev.uem.em.sim.Subs.plays(it, role.id) }
                        .sortedWith(compareBy({ dev.uem.em.sim.Subs.tier(it, role.id) }, { -dev.uem.em.sim.Ratings.effectiveness(it, t, role) }))
                    if (opts.isEmpty()) Muted("Nobody on the bench plays ${role.name}.", 11)
                    for (q in opts) {
                        val allowed = dev.uem.em.sim.Subs.importsAllow(w, s.teams[me], s.lineups[me], slot, q)
                        Row(Modifier.fillMaxWidth().alpha(if (allowed) 1f else 0.4f).clickable(enabled = allowed) { live.substitute(slot, q.id) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            PlayerPic(q, 26.dp, round = true); HGap(8)
                            Column(Modifier.weight(1f)) {
                                Text(q.handle, color = Ui.text, fontSize = 13.sp)
                                val extra = if (!t.isMoba && t.maps.size > 1) " · map %.0f vs %.0f".format(q.profMap[map] ?: 20.0, cur?.profMap?.get(map) ?: 20.0) else ""
                                Muted("${when (dev.uem.em.sim.Subs.tier(q, role.id)) { 0 -> "main position"; 1 -> "second position"; else -> "flex" }} · fit %.1f vs %.1f$extra${if (!allowed) " · import limit" else ""}".format(
                                    dev.uem.em.sim.Ratings.effectiveness(q, t, role), cur?.let { dev.uem.em.sim.Ratings.effectiveness(it, t, role) } ?: 0.0), 11)
                            }
                            Text("Sub in", color = Ui.accent, fontSize = 12.sp)
                        }
                    }
                }
            }
        })
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


/** Hooks from the live screen into the replay viewer (tactical timeouts in your shooter maps). */
object LiveHooks {
    @Volatile var onTimeout: ((beforeRound: Int, atSeconds: Int) -> Unit)? = null
    @Volatile var timeoutsLeft: Int = 0
    /** Coach calls in a live MOBA game (null: not your game). */
    @Volatile var onCall: ((call: String, atSeconds: Int) -> Unit)? = null
    @Volatile var calls: List<Pair<Int, String>> = emptyList()
    @Volatile var callsLeft: Int = 0
    /** Games of the series being watched live (for the broadcast series bar). */
    @Volatile var seriesGames: List<dev.uem.em.model.GameRecord>? = null
}

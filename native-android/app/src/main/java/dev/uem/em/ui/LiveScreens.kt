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
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Ui.card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${s.teams[0].short} ${s.wins[0]} - ${s.wins[1]} ${s.teams[1].short}", color = Ui.text, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Muted("${s.comp.name} · Bo${s.f.bestOf}${if (s.comp.fearless) " · hard Fearless" else ""}")
            }
            if (live.stage != "result") TextButton(onClick = { live.simRest(); vm.edit() }) { Text("Sim rest", color = Ui.muted) }
        }
        when (live.stage) {
            "veto" -> VetoView(live)
            "draft" -> DraftView(live)
            "watch" -> if (s.t.isMoba) MobaViewer(w, live) else TacticalViewer(w, live)
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
        item { Card("Map veto") { Muted(if (cur?.first == live.me) "Your turn: ${if (cur.second) "BAN" else "PICK"} a map" else "Waiting…"); for (l in v.log) Muted(l, 12) } }
        items(v.pool.size) { i ->
            val m = v.pool[i]
            val name = s.t.maps.first { it.id == m }.name
            val prof = s.lineups[live.me].filter { it >= 0 }.map { s.w.players[it].profMap[m] ?: 40.0 }.average()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).background(Ui.card, RoundedCornerShape(10.dp)).clickable(enabled = cur?.first == live.me) { live.vetoAct(m) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = Ui.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Muted("your map comfort ${prof.toInt()}")
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
                else -> "Opponent…"
            }, color = if (myTurn) Ui.accent else Ui.muted, fontWeight = FontWeight.Bold)
            Row {
                if (!d.done && d.sequential) TextButton(onClick = { live.autoDraft() }) { Text("Coach finishes the draft", color = Ui.accent) }
                if (d.done || !d.sequential) Button(onClick = { live.startGame() }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text("Start game ${s.gi + 1}") }
            }
        }
        if (d.sequential && !d.done) {
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

// --------------------------------------------------------------------------------- MOBA 2D view

/** Lane ids on the drawn map for an engine lane index (Dota 2 safe lanes are on opposite sides). */
private fun laneId(t: TitleDef, engineLane: Int, side: Int): String = when {
    engineLane == 1 -> "mid"
    t.id == "dota2" -> if ((engineLane == 0) == (side == 0)) "bot" else "top"
    engineLane == 0 -> "top"
    else -> "bot"
}

@Composable
private fun MobaViewer(w: World, live: LiveMatch) {
    val g = live.game ?: return
    val s = live.series
    val t = s.t
    val map = Maps.moba(t.id) ?: return
    val ev = live.events
    val blue = g.blue
    fun mapSide(seriesTeam: Int) = if (seriesTeam == blue) 0 else 1
    var time by remember(g) { mutableFloatStateOf(g.duration * LiveDefaults.startFraction) }
    var playing by remember(g) { mutableStateOf(LiveDefaults.autoplay) }
    var speed by remember { mutableIntStateOf(30) }
    val dur = g.duration.toFloat().coerceAtLeast(60f)
    LaunchedEffect(playing, speed, g) {
        while (playing && time < dur) { delay(50); time = (time + speed * 0.05f).coerceAtMost(dur) }
    }
    val T = time.toInt()
    val lines = g.lines
    val laneEnd = t.moba!!.laneEnd * 60
    // Structures lost per side and lane (by tier), objectives and kills so far.
    val down = Array(2) { HashMap<String, Int>() }
    val kills = IntArray(2)
    val objs = IntArray(2)
    var baseDown = IntArray(2)
    for (e in ev) {
        if (e.t > T) break
        val side = if (e.team >= 0) mapSide(e.team) else -1
        when (e.kind) {
            "tower" -> { val def = 1 - side; val lane = laneId(t, e.x, def); down[def][lane] = maxOf(down[def][lane] ?: 0, e.a) }
            "base" -> baseDown[1 - side] = maxOf(baseDown[1 - side], e.a)
            "kill" -> kills[side]++
            "obj" -> objs[side]++
        }
    }
    val towers = map.structures.filter { it.kind != "core" && it.lane != null }
    fun alive(st: Maps.Structure) = st.tier > (down[st.team][st.lane] ?: 0)
    // Lane front: between the two teams' outermost standing towers.
    fun front(lane: String): Float {
        val line = map.lanes[lane] ?: return 0.5f
        val mine = towers.filter { it.lane == lane && it.team == 0 && alive(it) }.maxOfOrNull { line.fractionOf(it.pos) } ?: 0.1f
        val theirs = towers.filter { it.lane == lane && it.team == 1 && alive(it) }.minOfOrNull { line.fractionOf(it.pos) } ?: 0.9f
        return (mine + theirs) / 2
    }
    fun lanePoint(lane: String, f: Float) = map.lanes[lane]?.at(f) ?: Offset(0.5f, 0.5f)
    fun respawn(kt: Int) = 8 + kt / 60f * 1.6f
    fun dead(pid: Int) = ev.lastOrNull { it.kind == "kill" && it.b == pid && it.t <= T }?.let { k -> k.t + respawn(k.t) > T } ?: false
    fun objPoint(i: Int): Offset {
        val id = t.moba.objectives.getOrNull(i)?.id ?: return Offset(0.5f, 0.5f)
        return map.pits.firstOrNull { id in it.objectives }?.pos ?: map.pits.firstOrNull()?.pos ?: Offset(0.5f, 0.5f)
    }
    fun jungle(side: Int, k: Int): Offset {
        val camps = map.camps.filter { it.team == side }.ifEmpty { map.camps }
        return if (camps.isEmpty()) Offset(0.5f, 0.5f) else camps[k % camps.size].pos
    }
    fun locPoint(loc: Int, side: Int): Offset = when {
        loc >= 10 -> objPoint(loc - 10)
        loc == 3 -> jungle(side, 1)
        else -> laneId(t, loc, side).let { lanePoint(it, front(it)) }
    }
    fun target(pid: Int, side: Int, roleIdx: Int, roleId: String, at: Int): Offset {
        val near = ev.firstOrNull { abs(it.t - at) < 18 && ((it.kind == "kill" && (it.a == pid || it.b == pid)) || it.kind == "fight") }
        if (near != null) return locPoint(near.x, side) + Offset(((pid % 5) - 2) * 0.012f, ((pid % 3) - 1) * 0.012f)
        val lane = t.moba.lanes.indexOfFirst { roleId in it.second }
        val jungler = roleId in t.moba.junglers && lane < 0
        if (at < laneEnd) {
            if (jungler || lane < 0) return jungle(side, at / 40)
            val id = laneId(t, lane, side)
            return lanePoint(id, front(id) + (if (side == 0) -0.035f else 0.035f) + (roleIdx % 2) * 0.012f)
        }
        // Later: the solo laner holds a side lane, the others group around mid.
        if (roleIdx == 0) { val id = if (side == 0) "top" else "bot"; return lanePoint(id, front(id) + (if (side == 0) -0.03f else 0.03f)) }
        return lanePoint("mid", front("mid") + (if (side == 0) -0.05f else 0.05f)) + Offset(((roleIdx % 3) - 1) * 0.02f, (roleIdx % 2) * 0.02f)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${s.teams[blue].short} ${kills[0]}", color = Ui.blue, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text("  %d:%02d  ".format(T / 60, T % 60), color = Ui.text, fontSize = 16.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("${kills[1]} ${s.teams[1 - blue].short}", color = Ui.red, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        Muted("  ${map.name} · towers ${down[1].values.sum()} - ${down[0].values.sum()} · objectives ${objs[0]} - ${objs[1]}", 12)
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)) {
            val size = maxWidth
            Canvas(Modifier.fillMaxSize()) {
                val W = this.size.width
                fun p(o: Offset) = Offset(o.x * W, o.y * W)
                drawRect(Color(0xFF1C3524))
                for (r in map.walls) drawRect(Color(0xFF10201A), Offset(r[0] * W, r[1] * W), Size(r[2] * W, r[3] * W))
                if (map.river.size > 1) for (i in 1 until map.river.size) drawLine(Color(0xFF2E6B8F), p(map.river[i - 1]), p(map.river[i]), strokeWidth = W * 0.035f)
                for ((_, line) in map.lanes) for (i in 1 until line.pts.size) drawLine(Color(0xFF8A7A55), p(line.pts[i - 1]), p(line.pts[i]), strokeWidth = W * 0.016f)
                for (pit in map.pits) drawCircle(Color(0xFF6E5A9B), W * 0.03f, p(pit.pos), style = Stroke(3f))
                for (c in map.camps) drawCircle(Color(0x553DDC97), W * 0.01f, p(c.pos))
                for (st in map.structures) {
                    val c = if (st.team == 0) Ui.blue else Ui.red
                    val pt = p(st.pos)
                    if (st.kind == "core") { drawRect(c, pt - Offset(W * 0.035f, W * 0.035f), Size(W * 0.07f, W * 0.07f)); continue }
                    val up = if (st.lane != null) alive(st) else st.tier > baseDown[st.team]
                    if (up) drawRect(c, pt - Offset(W * 0.013f, W * 0.013f), Size(W * 0.026f, W * 0.026f)) else drawRect(c.copy(alpha = 0.3f), pt - Offset(W * 0.011f, W * 0.011f), Size(W * 0.022f, W * 0.022f), style = Stroke(2f))
                }
                for (e in ev) if (e.kind == "fight" && e.t in (T - 12)..T) drawCircle(Color(0x55FF5A6E), W * 0.07f, p(locPoint(e.x, 0)))
            }
            for (l in lines) {
                val side = mapSide(l.team)
                val roleIdx = t.lineup.indexOf(l.roleId)
                val isDead = dead(l.playerId)
                val pos = if (isDead) map.fountains.getOrElse(side) { Offset(0.5f, 0.5f) } else {
                    val a = target(l.playerId, side, roleIdx, l.roleId, T)
                    val b = target(l.playerId, side, roleIdx, l.roleId, T - 6)
                    val c = target(l.playerId, side, roleIdx, l.roleId, T - 12)
                    Offset((a.x + b.x + c.x) / 3, (a.y + b.y + c.y) / 3)
                }
                val icon = 26.dp
                Box(Modifier.offset(x = size * pos.x - icon / 2, y = size * pos.y - icon / 2).size(icon).alpha(if (isDead) 0.35f else 1f).border(2.dp, if (side == 0) Ui.blue else Ui.red, CircleShape)) {
                    Pic(Pics.char(l.charId), t.charIndex[l.charId]?.name ?: s.w.players[l.playerId].handle, icon, round = true)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { playing = !playing }) { Text(if (playing) "Pause" else "Play", color = Ui.accent) }
            for (sp in listOf(15, 30, 60, 120)) TextButton(onClick = { speed = sp }) { Text("${sp}x", color = if (speed == sp) Ui.accent else Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { time = dur }) { Text("End", color = Ui.muted) }
        }
        Slider(value = time, onValueChange = { time = it }, valueRange = 0f..dur, modifier = Modifier.padding(horizontal = 12.dp))
        val feed = ev.filter { it.t <= T && (it.kind == "kill" || it.kind == "obj" || it.kind == "tower" || it.kind == "base") }.takeLast(5).reversed()
        Column(Modifier.padding(horizontal = 12.dp)) {
            for (e in feed) {
                val side = mapSide(e.team)
                val txt = when (e.kind) {
                    "kill" -> "${s.w.players[e.a].handle} killed ${s.w.players[e.b].handle}"
                    "obj" -> "${s.teams[e.team].short} take ${e.text}"
                    "tower" -> "${s.teams[e.team].short} destroy a ${laneId(t, e.x, 1 - side)} tower"
                    else -> "${s.teams[e.team].short} break into the base"
                }
                Text("%d:%02d  %s".format(e.t / 60, e.t % 60, txt), color = if (side == 0) Ui.blue else Ui.red, fontSize = 12.sp)
            }
        }
        if (time >= dur) WatchDone(live)
    }
}

@Composable
private fun WatchDone(live: LiveMatch) {
    val g = live.game ?: return
    val s = live.series
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${s.teams[g.winner].name} win game ${s.gi}", color = Ui.gold, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Button(onClick = { live.afterGame() }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text(if (s.done) "Result" else "Next game") }
    }
}

// ------------------------------------------------------------------------------- shooter 2D view

@Composable
private fun TacticalViewer(w: World, live: LiveMatch) {
    val g = live.game ?: return
    val s = live.series
    val t = s.t
    val map = Maps.shooter(g.mapId)
    val ev = live.events
    val rounds = remember(g) { ev.groupBy { it.t }.toSortedMap() }
    val nRounds = rounds.size.coerceAtLeast(1)
    var pos by remember(g) { mutableFloatStateOf(nRounds * LiveDefaults.startFraction) }
    var playing by remember(g) { mutableStateOf(LiveDefaults.autoplay) }
    var speed by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(playing, speed, g) { while (playing && pos < nRounds) { delay(50); pos = (pos + speed * 0.05f / 3.5f).coerceAtMost(nRounds.toFloat()) } }
    val r = pos.toInt().coerceAtMost(nRounds - 1)
    val phase = (pos - r).coerceIn(0f, 1f)
    val rev = rounds.values.toList()[r]
    val start = rev.firstOrNull { it.kind == "round" }
    val attack = start?.team ?: 0
    val seq = rev.filter { it.kind == "kill" || it.kind == "plant" || it.kind == "defuse" }
    val shown = seq.filterIndexed { i, _ -> phase >= 0.2f + 0.65f * (i + 1) / (seq.size + 1) }
    val deadIds = shown.filter { it.kind == "kill" }.map { it.b }.toSet()
    val planted = shown.any { it.kind == "plant" }
    val end = rev.firstOrNull { it.kind == "roundEnd" }
    val scoreBefore = rounds.values.toList().getOrNull(r - 1)?.firstOrNull { it.kind == "roundEnd" }?.let { intArrayOf(it.a, it.b) } ?: intArrayOf(0, 0)
    val score = if (phase >= 0.97f && end != null) intArrayOf(end.a, end.b) else scoreBefore
    val sites = map?.sites ?: emptyList()
    val site = if (sites.isEmpty()) null else sites[(start?.x ?: 0) % sites.size]
    // Attackers walk the shortest route from their spawn to the site; defenders hold sites and rotate after a plant.
    val atkRoute = remember(g, r) { if (map != null && site != null) Maps.Line(map.route(map.atk, site.node)) else null }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${s.teams[0].short} ${score[0]}", color = Ui.blue, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text("Round ${r + 1} · ${map?.name ?: t.maps.firstOrNull { it.id == g.mapId }?.name ?: ""}", color = Ui.text, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("${score[1]} ${s.teams[1].short}", color = Ui.red, fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        Muted("  ${s.teams[attack].short} attack ${site?.id ?: ""} · buys: ${start?.text?.replace("|", " vs ") ?: ""}", 12)
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)) {
            val size = maxWidth
            Canvas(Modifier.fillMaxSize()) {
                val W = this.size.width
                fun p(o: Offset) = Offset(o.x * W, o.y * W)
                drawRect(Color(0xFF1E2230))
                if (map != null) {
                    for (e in map.edges) drawLine(Color(0xFF3A4258), p(map.nodes[e.first].pos), p(map.nodes[e.second].pos), strokeWidth = W * 0.05f)
                    for (rw in map.walls) drawRect(Color(0xFF151822), Offset(rw[0] * W, rw[1] * W), Size(rw[2] * W, rw[3] * W))
                    for (e in map.edges) drawLine(Color(0xFF4A5470), p(map.nodes[e.first].pos), p(map.nodes[e.second].pos), strokeWidth = W * 0.012f)
                    for (st in map.sites) drawCircle(if (planted && st === site) Color(0x88FF5A6E) else Color(0x553B82F6), st.r * W, p(st.pos))
                    drawCircle(Color(0x66FF5A6E), W * 0.025f, p(map.nodes[map.atk].pos))
                    drawCircle(Color(0x664F8CFF), W * 0.025f, p(map.nodes[map.def].pos))
                }
            }
            if (map != null) {
                for (st in map.sites) Text(st.id, color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp, modifier = Modifier.offset(x = size * st.pos.x - 5.dp, y = size * st.pos.y - 10.dp))
                for (side in 0..1) {
                    val roster = g.lines.filter { it.team == side }
                    roster.forEachIndexed { i, l ->
                        val attacking = side == attack
                        val p2: Offset = if (attacking) {
                            val k = (phase / 0.8f).coerceIn(0f, 1f)
                            (atkRoute?.at(k) ?: Offset(0.5f, 0.5f)) + Offset((i - 2) * 0.018f, (i % 2) * 0.018f)
                        } else {
                            val home = map.sites[i % map.sites.size].pos + Offset(((i / map.sites.size) - 1) * 0.03f, 0.03f)
                            val k = if (planted && site != null) ((phase - 0.6f) / 0.4f).coerceIn(0f, 1f) else 0f
                            home + ((site?.pos ?: home) - home) * k
                        }
                        val isDead = l.playerId in deadIds
                        val icon = 22.dp
                        Box(Modifier.offset(x = size * p2.x - icon / 2, y = size * p2.y - icon / 2).size(icon).alpha(if (isDead) 0.25f else 1f).border(2.dp, if (side == 0) Ui.blue else Ui.red, CircleShape)) {
                            Pic(if (l.charId.isNotEmpty()) Pics.char(l.charId) else Pics.player(s.w.players[l.playerId]), s.w.players[l.playerId].handle, icon, round = true)
                        }
                    }
                }
            }
            if (phase >= 0.97f && end != null) Text("${s.teams[end.team].short} win the round", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(10.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { playing = !playing }) { Text(if (playing) "Pause" else "Play", color = Ui.accent) }
            for (sp in listOf(1f, 2f, 4f, 8f)) TextButton(onClick = { speed = sp }) { Text("${sp.toInt()}x", color = if (speed == sp) Ui.accent else Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { pos = nRounds.toFloat() }) { Text("End", color = Ui.muted) }
        }
        Slider(value = pos, onValueChange = { pos = it }, valueRange = 0f..nRounds.toFloat(), modifier = Modifier.padding(horizontal = 12.dp))
        Column(Modifier.padding(horizontal = 12.dp)) {
            for (e in shown.takeLast(4).reversed()) Text(when (e.kind) {
                "kill" -> "${s.w.players[e.a].handle} → ${s.w.players[e.b].handle}"
                "plant" -> "${t.tactical!!.objectiveName} planted on ${site?.id ?: ""}"
                else -> "${t.tactical!!.objectiveName} defused"
            }, color = if (e.team == 0) Ui.blue else Ui.red, fontSize = 12.sp)
        }
        if (pos >= nRounds) WatchDone(live)
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

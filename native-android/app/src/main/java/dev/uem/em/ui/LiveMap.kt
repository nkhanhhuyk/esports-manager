package dev.uem.em.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.Line
import dev.uem.em.model.World
import dev.uem.em.sim.Ev
import dev.uem.em.titles.TitleDef
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

// ------------------------------------------------------------------------------ shared map pieces

/** Camera for the 2D maps: zoom 1..3 around a centre (map units 0..1), optionally following a player. */
class MapCam {
    var zoom by mutableFloatStateOf(1f)
    var cx by mutableFloatStateOf(0.5f)
    var cy by mutableFloatStateOf(0.5f)
    var follow by mutableIntStateOf(-1)

    /** Where the followed player is this frame (set while composing; not state). */
    var pinned: Offset? = null

    fun clampCenter() {
        val h = 0.5f / zoom
        cx = cx.coerceIn(h, 1 - h)
        cy = cy.coerceIn(h, 1 - h)
    }

    fun center(): Offset {
        val p = pinned ?: return Offset(cx, cy)
        val h = 0.5f / zoom
        return Offset(p.x.coerceIn(h, 1 - h), p.y.coerceIn(h, 1 - h))
    }

    /** Map point (0..1) to view point (0..1). */
    fun view(o: Offset): Offset { val c = center(); return Offset((o.x - c.x) * zoom + 0.5f, (o.y - c.y) * zoom + 0.5f) }
    fun reset() { zoom = 1f; cx = 0.5f; cy = 0.5f; follow = -1; pinned = null }
    fun toggleFollow(pid: Int) { if (follow == pid) reset() else { follow = pid; if (zoom < 1.8f) zoom = 2f } }

    /** Stop following, keeping the current view. */
    fun unpin() { val c = center(); pinned = null; follow = -1; cx = c.x; cy = c.y }
}

/** A square map with pinch-zoom and drag-pan; `draw` paints the map in 0..W map space. */
@Composable
private fun MapFrame(cam: MapCam, draw: DrawScope.(W: Float) -> Unit, overlay: @Composable androidx.compose.foundation.layout.BoxScope.(side: Dp) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp).clip(RoundedCornerShape(10.dp))
            .pointerInput(cam) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    if (cam.follow >= 0) cam.unpin()
                    cam.zoom = (cam.zoom * zoomChange).coerceIn(1f, 3f)
                    cam.cx -= pan.x / (size.width * cam.zoom)
                    cam.cy -= pan.y / (size.height * cam.zoom)
                    cam.clampCenter()
                }
            },
    ) {
        val side = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val W = size.width
            val c = cam.center()
            withTransform({
                translate(W / 2, W / 2)
                scale(cam.zoom, cam.zoom, pivot = Offset.Zero)
                translate(-c.x * W, -c.y * W)
            }) { draw(W) }
        }
        overlay(side)
    }
}

/**
 * A unit on the map: the character (or player) portrait inside a circle in the team's colour, with a
 * health bar above, a level badge and the player's name below. `pos` is in view units (0..1).
 */
@Composable
private fun Token(
    side: Dp, pos: Offset, url: String?, label: String, name: String, ring: Color,
    hp: Float?, level: Int?, dead: Boolean, followed: Boolean, showName: Boolean, size: Dp = 34.dp, deadText: String? = null,
) {
    if (pos.x < -0.05f || pos.y < -0.05f || pos.x > 1.05f || pos.y > 1.05f) return
    val colW = 78.dp
    val barH = 5.dp
    Column(
        Modifier.offset(x = side * pos.x - colW / 2, y = side * pos.y - barH - 2.dp - size / 2).width(colW).alpha(if (dead) 0.45f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (hp != null && !dead) {
            Box(Modifier.width(size).height(barH).background(Color(0xCC000000), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(hp.coerceIn(0.03f, 1f)).height(barH).background(if (hp > 0.5f) Color(0xFF3CCF7A) else if (hp > 0.25f) Color(0xFFFFD166) else Color(0xFFFF5D5D), RoundedCornerShape(2.dp)))
            }
        } else Spacer(Modifier.height(barH))
        Spacer(Modifier.height(2.dp))
        Box(Modifier.size(size)) {
            Box(Modifier.size(size).background(ring, CircleShape).then(if (followed) Modifier.border(3.dp, Color.White, CircleShape) else Modifier).padding(3.dp)) {
                Pic(url, label, size - 6.dp, round = true, color = ring.copy(alpha = 0.6f))
            }
            if (dead && deadText != null) Text(deadText, color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center).background(Color(0xAA000000), CircleShape).padding(horizontal = 4.dp))
            if (level != null && !dead) Text(
                "$level", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp).size(15.dp).background(Color(0xFF0B0F14), CircleShape).border(1.dp, ring, CircleShape),
            )
        }
        if (showName) Text(
            name, color = Color.White, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp).background(Color(0x99000000), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
        )
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Text((if (on) "☑ " else "☐ ") + label, color = if (on) Ui.text else Ui.muted, fontSize = 12.sp, modifier = Modifier.clickable { set(!on) }.padding(horizontal = 6.dp, vertical = 4.dp))
}

/** Spot i of n on a small ring (spreads a group so the portraits don't sit on top of each other). */
private fun ring(i: Int, n: Int, r: Float): Offset {
    val a = (2 * Math.PI * i / maxOf(1, n) - Math.PI / 2).toFloat()
    return Offset(kotlin.math.cos(a) * r, kotlin.math.sin(a) * r)
}

private fun k1(v: Double) = if (v >= 1000) "%.1fk".format(v / 1000) else "%.0f".format(v)
private fun clock(s: Int) = "%d:%02d".format(s / 60, s % 60)

// --------------------------------------------------------------------------------- MOBA 2D view

/** Lane ids on the drawn map for an engine lane index (Dota 2 safe lanes are on opposite sides). */
internal fun laneId(t: TitleDef, engineLane: Int, side: Int): String = when {
    engineLane == 1 -> "mid"
    t.id == "dota2" -> if ((engineLane == 0) == (side == 0)) "bot" else "top"
    engineLane == 0 -> "top"
    else -> "bot"
}

private val LEVEL_CAP = mapOf("lol" to 18, "dota2" to 30)
private val CAP_MINUTES = mapOf("lol" to 36f, "dota2" to 55f)

@Composable
fun MobaViewer(w: World, live: LiveMatch) {
    val g = live.game ?: return
    val s = live.series
    val t = s.t
    val map = Maps.moba(t.id) ?: return
    val ev = live.events
    val blue = g.blue
    fun mapSide(seriesTeam: Int) = if (seriesTeam == blue) 0 else 1
    val teamCol = arrayOf(Ui.blue, Ui.red)
    val sideNames = if (t.id == "dota2") "Radiant" to "Dire" else "Blue" to "Red"
    var time by remember(g) { mutableFloatStateOf(g.duration * LiveDefaults.startFraction) }
    var playing by remember(g) { mutableStateOf(LiveDefaults.autoplay) }
    var speed by remember { mutableIntStateOf(30) }
    var names by remember { mutableStateOf(true) }
    var health by remember { mutableStateOf(true) }
    val cam = remember(g) { MapCam() }
    val dur = g.duration.toFloat().coerceAtLeast(60f)
    LaunchedEffect(playing, speed, g) {
        while (playing && time < dur) { delay(50); time = (time + speed * 0.05f).coerceAtMost(dur) }
        if (time >= dur) playing = false
    }
    val T = time.toInt()
    val laneEnd = t.moba!!.laneEnd * 60
    val stat = { k: String -> t.statIndex[k] ?: -1 }
    fun fin(l: Line, k: String) = stat(k).let { if (it >= 0) l.s[it] else 0.0 }

    // State at time T: structures, kills, deaths, objectives.
    val down = Array(2) { HashMap<String, Int>() }
    val baseDown = IntArray(2)
    val kills = IntArray(2)
    val objs = IntArray(2)
    val kNow = HashMap<Int, Int>()
    val dNow = HashMap<Int, Int>()
    val teamKillsTotal = IntArray(2)
    for (e in ev) if (e.kind == "kill") teamKillsTotal[e.team]++
    for (e in ev) {
        if (e.t > T) break
        val side = if (e.team >= 0) mapSide(e.team) else -1
        when (e.kind) {
            "tower" -> { val def = 1 - side; val lane = laneId(t, e.x, def); down[def][lane] = maxOf(down[def][lane] ?: 0, e.a) }
            "base" -> baseDown[1 - side] = maxOf(baseDown[1 - side], e.a)
            "kill" -> { kills[side]++; kNow.merge(e.a, 1, Int::plus); dNow.merge(e.b, 1, Int::plus) }
            "obj" -> objs[side]++
        }
    }
    val towers = map.structures.filter { it.kind != "core" && it.lane != null }
    fun alive(st: Maps.Structure) = st.tier > (down[st.team][st.lane] ?: 0)
    fun front(lane: String): Float {
        val line = map.lanes[lane] ?: return 0.5f
        val mine = towers.filter { it.lane == lane && it.team == 0 && alive(it) }.maxOfOrNull { line.fractionOf(it.pos) } ?: 0.1f
        val theirs = towers.filter { it.lane == lane && it.team == 1 && alive(it) }.minOfOrNull { line.fractionOf(it.pos) } ?: 0.9f
        return (mine + theirs) / 2
    }
    fun lanePoint(lane: String, f: Float) = map.lanes[lane]?.at(f) ?: Offset(0.5f, 0.5f)
    fun respawn(kt: Int) = (8 + kt / 60f * 1.6f).toInt()
    fun lastDeath(pid: Int) = ev.lastOrNull { it.kind == "kill" && it.b == pid && it.t <= T }
    fun respawnLeft(pid: Int) = lastDeath(pid)?.let { k -> k.t + respawn(k.t) - T }?.takeIf { it > 0 }
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
        if (near != null) return locPoint(near.x, side) + Offset(if (side == 0) -0.035f else 0.035f, if (side == 0) 0.02f else -0.02f) + ring(roleIdx, 5, 0.045f)
        val lane = t.moba.lanes.indexOfFirst { roleId in it.second }
        val jungler = roleId in t.moba.junglers && lane < 0
        if (at < laneEnd) {
            if (jungler || lane < 0) return jungle(side, at / 40)
            val id = laneId(t, lane, side)
            val duo = t.moba.lanes[lane].second.size > 1 && t.moba.lanes[lane].second.indexOf(roleId) == 1
            return lanePoint(id, front(id) + (if (side == 0) -0.04f else 0.04f)) + (if (duo) Offset(0.035f, 0.035f) * (if (side == 0) -1f else 1f) else Offset.Zero)
        }
        if (roleIdx == 0) { val id = if (side == 0) "top" else "bot"; return lanePoint(id, front(id) + (if (side == 0) -0.03f else 0.03f)) }
        return lanePoint("mid", front("mid") + (if (side == 0) -0.06f else 0.06f)) + ring(roleIdx - 1, 4, 0.045f)
    }
    fun posAt(l: Line, at: Int): Offset {
        val side = mapSide(l.team)
        val roleIdx = t.lineup.indexOf(l.roleId)
        val a = target(l.playerId, side, roleIdx, l.roleId, at)
        val b = target(l.playerId, side, roleIdx, l.roleId, at - 6)
        val c = target(l.playerId, side, roleIdx, l.roleId, at - 12)
        return Offset((a.x + b.x + c.x) / 3, (a.y + b.y + c.y) / 3)
    }
    // Estimates for the live HUD (the box score has the real totals at the end).
    val cap = LEVEL_CAP[t.id] ?: 15
    val capMin = CAP_MINUTES[t.id] ?: 17f
    fun assists(l: Line): Int {
        val tk = ev.count { it.kind == "kill" && it.team == l.team && it.t <= T }
        return (fin(l, "a") * tk / maxOf(1, teamKillsTotal[l.team])).roundToInt()
    }
    val avgCs = g.lines.map { fin(it, "cs") }.average().coerceAtLeast(1.0)
    fun level(l: Line): Int {
        val base = 1 + (cap - 1) * (T / (capMin * 60f)).coerceIn(0f, 1f).pow(0.72f)
        val farm = ((fin(l, "cs") / avgCs - 1) * 1.6).toFloat().coerceIn(-2f, 1.5f) * (T / dur).coerceAtMost(1f).pow(0.5f)
        return (base + farm + 0.25f * (kNow[l.playerId] ?: 0) + 0.12f * assists(l)).toInt().coerceIn(1, cap)
    }
    fun goldNow(l: Line): Double = fin(l, "gold").let { gf -> if (gf <= 0) 0.0 else 500 + (gf - 500).coerceAtLeast(0.0) * ((T + 30.0) / (dur + 30.0)).pow(1.12) }
    fun csNow(l: Line): Int = (fin(l, "cs") * (T / dur.toDouble()).pow(1.05)).toInt()
    fun lastHit(pid: Int, side: Int): Int? = ev.lastOrNull { it.t <= T && it.t > T - 16 && ((it.kind == "kill" && it.a == pid) || (it.kind == "fight")) }?.t
    fun hp(l: Line): Float {
        val h = lastHit(l.playerId, mapSide(l.team)) ?: return 1f
        val low = 0.22f + ((l.playerId * 37 + h) % 45) / 100f
        return (low + (T - h) * 0.05f).coerceAtMost(1f)
    }
    fun status(l: Line): String {
        respawnLeft(l.playerId)?.let { return "respawning ${it}s" }
        val pid = l.playerId
        if (ev.any { it.kind == "fight" && abs(it.t - T) < 12 }) return "teamfight"
        if (ev.any { it.kind == "kill" && (it.a == pid) && T - it.t in 0..8 }) return "fighting"
        ev.firstOrNull { it.kind == "obj" && it.team == l.team && it.t - T in -4..15 }?.let { return "at ${it.text}" }
        val lane = t.moba.lanes.indexOfFirst { l.roleId in it.second }
        if (T < laneEnd) return if (lane < 0) "jungling" else "laning ${laneId(t, lane, mapSide(l.team))}"
        return if (t.lineup.indexOf(l.roleId) == 0) "side lane" else "grouped mid"
    }
    val teamGold = DoubleArray(2).also { a -> g.lines.forEach { a[mapSide(it.team)] += goldNow(it) } }
    // Follow the selected player.
    val followed = g.lines.firstOrNull { it.playerId == cam.follow }
    cam.pinned = followed?.let { fl -> if (respawnLeft(fl.playerId) != null) map.fountains.getOrElse(mapSide(fl.team)) { Offset(0.5f, 0.5f) } else posAt(fl, T) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Pic(Pics.team(s.teams[blue]), s.teams[blue].short, 26.dp)
            Text(" ${s.teams[blue].short}", color = Ui.blue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("${kills[0]} – ${kills[1]}", color = Ui.text, fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("${s.teams[1 - blue].short} ", color = Ui.red, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Pic(Pics.team(s.teams[1 - blue]), s.teams[1 - blue].short, 26.dp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(k1(teamGold[0]), color = Ui.gold, fontSize = 12.sp)
            Text(clock(T), color = Ui.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text(k1(teamGold[1]), color = Ui.gold, fontSize = 12.sp)
        }
        Text("${map.name} · ${sideNames.first} ${down[1].values.sum() + baseDown[1]} – ${down[0].values.sum() + baseDown[0]} ${sideNames.second} structures · objectives ${objs[0]}–${objs[1]} · gold lead ${k1(abs(teamGold[0] - teamGold[1]))}", color = Ui.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
        MapFrame(cam, draw = { W ->
            fun p(o: Offset) = Offset(o.x * W, o.y * W)
            drawRect(Color(0xFF1C3524), Offset.Zero, Size(W, W))
            for (r in map.walls) drawRect(Color(0xFF10201A), Offset(r[0] * W, r[1] * W), Size(r[2] * W, r[3] * W))
            if (map.river.size > 1) for (i in 1 until map.river.size) drawLine(Color(0xFF2E6B8F), p(map.river[i - 1]), p(map.river[i]), strokeWidth = W * 0.035f)
            for ((_, line) in map.lanes) for (i in 1 until line.pts.size) drawLine(Color(0xFF8A7A55), p(line.pts[i - 1]), p(line.pts[i]), strokeWidth = W * 0.016f)
            for (f in map.fountains.indices) drawCircle(teamCol[f].copy(alpha = 0.25f), W * 0.05f, p(map.fountains[f]))
            for (pit in map.pits) {
                val taken = ev.any { it.kind == "obj" && it.t in (T - 8)..T && objPoint(it.x) == pit.pos }
                drawCircle(if (taken) Color(0xAAFFD166) else Color(0xFF6E5A9B), W * 0.03f, p(pit.pos), style = Stroke(if (taken) 6f else 3f))
            }
            for (c in map.camps) drawCircle(Color(0x553DDC97), W * 0.01f, p(c.pos))
            for (st in map.structures) {
                val c = teamCol[st.team]
                val pt = p(st.pos)
                if (st.kind == "core") { drawRect(c, pt - Offset(W * 0.035f, W * 0.035f), Size(W * 0.07f, W * 0.07f)); drawRect(Color.White.copy(alpha = 0.5f), pt - Offset(W * 0.035f, W * 0.035f), Size(W * 0.07f, W * 0.07f), style = Stroke(2f)); continue }
                val up = if (st.lane != null) alive(st) else st.tier > baseDown[st.team]
                if (up) {
                    drawRect(Color(0xFF0B0F14), pt - Offset(W * 0.015f, W * 0.015f), Size(W * 0.03f, W * 0.03f))
                    drawRect(c, pt - Offset(W * 0.012f, W * 0.012f), Size(W * 0.024f, W * 0.024f))
                } else {
                    drawRect(c.copy(alpha = 0.3f), pt - Offset(W * 0.011f, W * 0.011f), Size(W * 0.022f, W * 0.022f), style = Stroke(2f))
                }
            }
            for (e in ev) {
                if (e.t > T) break
                if (e.kind == "fight" && e.t in (T - 12)..T) drawCircle(Color(0x44FF5A6E), W * 0.075f, p(locPoint(e.x, 0)))
                if (e.kind == "kill" && T - e.t in 0..5) {
                    val vic = g.lines.firstOrNull { it.playerId == e.b } ?: continue
                    val at = p(posAt(vic, e.t))
                    val a = 1f - (T - e.t) / 6f
                    val d = W * 0.018f
                    drawLine(Color.White.copy(alpha = a), at - Offset(d, d), at + Offset(d, d), strokeWidth = 4f)
                    drawLine(Color.White.copy(alpha = a), at + Offset(d, -d), at - Offset(d, -d), strokeWidth = 4f)
                }
            }
        }) { side ->
            for (l in g.lines) {
                val ms = mapSide(l.team)
                val left = respawnLeft(l.playerId)
                val pos = if (left != null) map.fountains.getOrElse(ms) { Offset(0.5f, 0.5f) } else posAt(l, T)
                val p = s.w.players[l.playerId]
                Token(
                    side, cam.view(pos), Pics.char(l.charId), t.charIndex[l.charId]?.name ?: p.handle, p.handle, teamCol[ms],
                    if (health) hp(l) else null, level(l), left != null, cam.follow == l.playerId, names, deadText = left?.toString(),
                )
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (time >= dur) { time = 0f; playing = true } else playing = !playing }) { Text(if (time >= dur) "Replay" else if (playing) "Pause" else "Play", color = Ui.accent) }
            for (sp in listOf(15, 30, 60, 120)) TextButton(onClick = { speed = sp }) { Text("${sp}x", color = if (speed == sp) Ui.accent else Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { time = dur }) { Text("Instant finish", color = Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { cam.reset() }) { Text("Fit map", color = Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { cam.zoom = (cam.zoom * 1.3f).coerceAtMost(3f); cam.clampCenter() }) { Text("+", color = Ui.muted) }
            TextButton(onClick = { cam.zoom = (cam.zoom / 1.3f).coerceAtLeast(1f); cam.clampCenter() }) { Text("−", color = Ui.muted) }
        }
        Row(Modifier.padding(horizontal = 8.dp)) { Toggle("names", names) { names = it }; Toggle("health", health) { health = it } }
        Slider(value = time, onValueChange = { time = it }, valueRange = 0f..dur, modifier = Modifier.padding(horizontal = 12.dp))
        if (time >= dur) WatchDone(live)
        // Live scoreboard: tap a player to follow them on the map.
        Card("Scoreboard", right = { Muted("tap a player to follow") }) {
            for (team in intArrayOf(blue, 1 - blue)) {
                val ms = mapSide(team)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
                    Box(Modifier.size(10.dp).background(teamCol[ms], CircleShape)); HGap(6)
                    Text("${if (ms == 0) sideNames.first else sideNames.second} · ${s.teams[team].name}", color = teamCol[ms], fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Muted("Lv", 10); Spacer(Modifier.width(22.dp)); Muted("K/D/A", 10); Spacer(Modifier.width(28.dp)); Muted("Gold", 10); Spacer(Modifier.width(6.dp))
                }
                val rows = g.lines.filter { it.team == team }.sortedBy { t.lineup.indexOf(it.roleId).let { i -> if (i < 0) 9 else i } }
                for (l in rows) {
                    val p = s.w.players[l.playerId]
                    val left = respawnLeft(l.playerId)
                    Row(
                        Modifier.fillMaxWidth().background(if (cam.follow == l.playerId) Ui.card2 else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable { cam.toggleFollow(l.playerId) }.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.alpha(if (left != null) 0.4f else 1f)) { Pic(Pics.char(l.charId), t.charIndex[l.charId]?.name ?: l.charId, 30.dp, round = true, color = teamCol[ms].copy(alpha = 0.5f)) }
                        HGap(6)
                        Column(Modifier.weight(1f)) {
                            Text(p.handle, color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted("${t.role(l.roleId).short} · ${t.charIndex[l.charId]?.name ?: "—"} · ${status(l)}", 10)
                        }
                        Text("${level(l)}", color = Ui.text, fontSize = 12.sp, modifier = Modifier.width(26.dp), textAlign = TextAlign.Center)
                        Text("${kNow[l.playerId] ?: 0}/${dNow[l.playerId] ?: 0}/${assists(l)}", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(58.dp), textAlign = TextAlign.Center)
                        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.End) {
                            Text(k1(goldNow(l)), color = Ui.gold, fontSize = 12.sp)
                            Muted("${csNow(l)} cs", 9)
                        }
                    }
                }
            }
        }
        val feed = ev.filter { it.t <= T && (it.kind == "kill" || it.kind == "obj" || it.kind == "tower" || it.kind == "base" || it.kind == "fight") }.takeLast(8).reversed()
        Card("Play-by-play") {
            if (feed.isEmpty()) Muted("Minions are on their way…")
            for (e in feed) {
                val ms = if (e.team >= 0) mapSide(e.team) else 0
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(clock(e.t.coerceAtLeast(0)), color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(38.dp))
                    when (e.kind) {
                        "kill" -> {
                            val ka = g.lines.firstOrNull { it.playerId == e.a }
                            val kb = g.lines.firstOrNull { it.playerId == e.b }
                            Pic(Pics.char(ka?.charId), ka?.charId ?: "", 20.dp, round = true); HGap(4)
                            Text(s.w.players[e.a].handle, color = teamCol[ms], fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("  killed  ", color = Ui.muted, fontSize = 11.sp)
                            Pic(Pics.char(kb?.charId), kb?.charId ?: "", 20.dp, round = true); HGap(4)
                            Text(s.w.players[e.b].handle, color = teamCol[1 - ms], fontSize = 12.sp)
                        }
                        "obj" -> Text("${s.teams[e.team].short} take ${e.text}", color = teamCol[ms], fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        "tower" -> Text("${s.teams[e.team].short} destroy the ${t.moba.structures.tierNames.getOrElse(e.a - 1) { "tower" }} in ${laneId(t, e.x, 1 - ms)}", color = teamCol[ms], fontSize = 12.sp)
                        "fight" -> Text("Teamfight: ${s.teams[e.team].short} win it ${e.a} for ${e.b}", color = teamCol[ms], fontSize = 12.sp)
                        else -> Text("${s.teams[e.team].short} break into the base (${t.moba.structures.baseName})", color = teamCol[ms], fontSize = 12.sp)
                    }
                }
            }
        }
        Muted("  ${map.name}. Pinch to zoom, drag to pan, tap a player to follow. Levels, gold and health are a live read of the simulation; the box score has the final numbers.", 10)
        Spacer(Modifier.height(12.dp))
    }
}

// ------------------------------------------------------------------------------- shooter 2D view

@Composable
fun TacticalViewer(w: World, live: LiveMatch) {
    val g = live.game ?: return
    val s = live.series
    val t = s.t
    val map = Maps.shooter(g.mapId)
    val ev = live.events
    val teamCol = arrayOf(Ui.blue, Ui.red)
    val rounds = remember(g) { ev.groupBy { it.t }.toSortedMap().values.toList() }
    val nRounds = rounds.size.coerceAtLeast(1)
    var pos by remember(g) { mutableFloatStateOf(nRounds * LiveDefaults.startFraction) }
    var playing by remember(g) { mutableStateOf(LiveDefaults.autoplay) }
    var speed by remember { mutableFloatStateOf(1f) }
    var names by remember { mutableStateOf(true) }
    val cam = remember(g) { MapCam() }
    LaunchedEffect(playing, speed, g) {
        while (playing && pos < nRounds) { delay(50); pos = (pos + speed * 0.05f / 4f).coerceAtMost(nRounds.toFloat()) }
        if (pos >= nRounds) playing = false
    }
    val r = pos.toInt().coerceAtMost(nRounds - 1)
    val phase = if (pos >= nRounds) 1f else (pos - r).coerceIn(0f, 1f)
    val rev = rounds.getOrElse(r) { emptyList() }
    val start = rev.firstOrNull { it.kind == "round" }
    val attack = start?.team ?: 0
    val seq = rev.filter { it.kind == "kill" || it.kind == "plant" || it.kind == "defuse" }
    fun atPhase(i: Int) = 0.2f + 0.65f * (i + 1) / (seq.size + 1)
    val shownIdx = seq.indices.filter { phase >= atPhase(it) }
    val shown = shownIdx.map { seq[it] }
    val deathPhase = HashMap<Int, Float>().also { m -> seq.forEachIndexed { i, e -> if (e.kind == "kill") m[e.b] = atPhase(i) } }
    val planted = shown.any { it.kind == "plant" }
    val plantPhase = seq.indexOfFirst { it.kind == "plant" }.let { if (it < 0) 2f else atPhase(it) }
    val end = rev.firstOrNull { it.kind == "roundEnd" }
    val scoreBefore = rounds.getOrNull(r - 1)?.firstOrNull { it.kind == "roundEnd" }?.let { intArrayOf(it.a, it.b) } ?: intArrayOf(0, 0)
    val score = if (phase >= 0.97f && end != null) intArrayOf(end.a, end.b) else scoreBefore
    val sites = map?.sites ?: emptyList()
    val site = if (sites.isEmpty()) null else sites[(start?.x ?: 0) % sites.size]
    val atkRoute = remember(g, r) { if (map != null && site != null) Maps.Line(map.route(map.atk, site.node)) else null }
    // Kills and deaths on this map so far.
    val kMap = HashMap<Int, Int>()
    val dMap = HashMap<Int, Int>()
    for (ri in 0 until r) for (e in rounds[ri]) if (e.kind == "kill") { kMap.merge(e.a, 1, Int::plus); dMap.merge(e.b, 1, Int::plus) }
    for (e in shown) if (e.kind == "kill") { kMap.merge(e.a, 1, Int::plus); dMap.merge(e.b, 1, Int::plus) }
    val deadIds = shown.filter { it.kind == "kill" }.map { it.b }.toSet()
    fun posOf(side: Int, i: Int, ph: Float): Offset {
        if (map == null) return Offset(0.5f, 0.5f)
        return if (side == attack) {
            val k = (ph / 0.8f).coerceIn(0f, 1f)
            (atkRoute?.at(k) ?: Offset(0.5f, 0.5f)) + ring(i, 5, 0.03f + 0.03f * k)
        } else {
            val n = map.sites.size
            val home = if (n == 0) map.nodes[map.def].pos else map.sites[i % n].pos + ring(i / n, (5 + n - 1 - i % n) / n, 0.045f)
            val k = if (site != null && ph > plantPhase) ((ph - plantPhase) / 0.3f).coerceIn(0f, 1f) else 0f
            home + ((site?.pos ?: home) - home) * k
        }
    }
    fun hpOf(pid: Int): Float {
        if (pid in deadIds) return 0f
        val involved = shown.lastOrNull { it.kind == "kill" && it.a == pid } ?: return 1f
        return 0.25f + ((pid * 31 + involved.b) % 60) / 100f
    }
    val roster = Array(2) { side -> g.lines.filter { it.team == side } }
    val followedLine = g.lines.firstOrNull { it.playerId == cam.follow }
    cam.pinned = followedLine?.let { fl -> posOf(fl.team, roster[fl.team].indexOf(fl), deathPhase[fl.playerId]?.takeIf { fl.playerId in deadIds } ?: phase) }
    val sideName = { side: Int -> if (side == attack) t.tactical!!.sides.first else t.tactical!!.sides.second }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Pic(Pics.team(s.teams[0]), s.teams[0].short, 26.dp)
            Text(" ${s.teams[0].short}", color = Ui.blue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("${score[0]} – ${score[1]}", color = Ui.text, fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("${s.teams[1].short} ", color = Ui.red, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Pic(Pics.team(s.teams[1]), s.teams[1].short, 26.dp)
        }
        Text("Round ${r + 1} · ${map?.name ?: t.maps.firstOrNull { it.id == g.mapId }?.name ?: ""}", color = Ui.text, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        val buys = start?.text?.split("|") ?: emptyList()
        Text("${s.teams[attack].short} ${sideName(attack).lowercase()} (${buys.getOrElse(0) { "?" }} buy) on ${site?.id ?: "?"} · ${s.teams[1 - attack].short} ${sideName(1 - attack).lowercase()} (${buys.getOrElse(1) { "?" }} buy)", color = Ui.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
        // Round history.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (ri in 0 until nRounds) {
                val re = rounds[ri].firstOrNull { it.kind == "roundEnd" }
                val done = ri < r || (ri == r && phase >= 0.97f)
                Box(
                    Modifier.size(width = 12.dp, height = 16.dp).background(if (done && re != null) teamCol[re.team] else Ui.card2, RoundedCornerShape(2.dp))
                        .then(if (ri == r) Modifier.border(1.dp, Color.White, RoundedCornerShape(2.dp)) else Modifier).clickable { pos = ri.toFloat() },
                )
            }
        }
        MapFrame(cam, draw = { W ->
            fun p(o: Offset) = Offset(o.x * W, o.y * W)
            drawRect(Color(0xFF1E2230), Offset.Zero, Size(W, W))
            if (map != null) {
                for (e in map.edges) drawLine(Color(0xFF3A4258), p(map.nodes[e.first].pos), p(map.nodes[e.second].pos), strokeWidth = W * 0.05f)
                for (rw in map.walls) drawRect(Color(0xFF151822), Offset(rw[0] * W, rw[1] * W), Size(rw[2] * W, rw[3] * W))
                for (e in map.edges) drawLine(Color(0xFF4A5470), p(map.nodes[e.first].pos), p(map.nodes[e.second].pos), strokeWidth = W * 0.012f)
                for (st in map.sites) drawCircle(if (planted && st === site) Color(0x88FF5A6E) else Color(0x553B82F6), st.r * W, p(st.pos))
                drawCircle(Color(0x66FF5A6E), W * 0.025f, p(map.nodes[map.atk].pos))
                drawCircle(Color(0x664F8CFF), W * 0.025f, p(map.nodes[map.def].pos))
                if (planted && site != null) drawCircle(Color(0xFFFF5A6E), W * 0.012f, p(site.pos))
                atkRoute?.let { rt -> for (i in 1 until rt.pts.size) drawLine(teamCol[attack].copy(alpha = 0.25f), p(rt.pts[i - 1]), p(rt.pts[i]), strokeWidth = 3f) }
                // Death markers.
                for ((pid, dp) in deathPhase) if (pid in deadIds) {
                    val l = g.lines.firstOrNull { it.playerId == pid } ?: continue
                    val at = p(posOf(l.team, roster[l.team].indexOf(l), dp))
                    val d = W * 0.014f
                    drawLine(teamCol[l.team].copy(alpha = 0.8f), at - Offset(d, d), at + Offset(d, d), strokeWidth = 4f)
                    drawLine(teamCol[l.team].copy(alpha = 0.8f), at + Offset(d, -d), at - Offset(d, -d), strokeWidth = 4f)
                }
            }
        }) { side ->
            if (map != null) {
                for (st in map.sites) {
                    val v = cam.view(st.pos)
                    Text(st.id, color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.offset(x = side * v.x - 5.dp, y = side * v.y - 10.dp))
                }
                for (sd in 0..1) roster[sd].forEachIndexed { i, l ->
                    val pid = l.playerId
                    if (pid in deadIds) return@forEachIndexed
                    val p = s.w.players[pid]
                    Token(
                        side, cam.view(posOf(sd, i, phase)), if (l.charId.isNotEmpty()) Pics.char(l.charId) else Pics.player(p), p.handle, p.handle, teamCol[sd],
                        hpOf(pid), null, false, cam.follow == pid, names, size = 30.dp,
                    )
                }
            }
            if (phase >= 0.97f && end != null) Text(
                "${s.teams[end.team].short} win the round", color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(10.dp),
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (pos >= nRounds) { pos = 0f; playing = true } else playing = !playing }) { Text(if (pos >= nRounds) "Replay" else if (playing) "Pause" else "Play", color = Ui.accent) }
            for (sp in listOf(1f, 2f, 4f, 8f)) TextButton(onClick = { speed = sp }) { Text("${sp.toInt()}x", color = if (speed == sp) Ui.accent else Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { pos = (r + 1).toFloat().coerceAtMost(nRounds.toFloat()) }) { Text("Next round", color = Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { pos = nRounds.toFloat() }) { Text("Instant finish", color = Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { cam.reset() }) { Text("Fit map", color = Ui.muted, fontSize = 12.sp) }
        }
        Row(Modifier.padding(horizontal = 8.dp)) { Toggle("names", names) { names = it } }
        Slider(value = pos, onValueChange = { pos = it }, valueRange = 0f..nRounds.toFloat(), modifier = Modifier.padding(horizontal = 12.dp))
        if (pos >= nRounds) WatchDone(live)
        Card("Scoreboard", right = { Muted("tap a player to follow") }) {
            for (sd in 0..1) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
                    Box(Modifier.size(10.dp).background(teamCol[sd], CircleShape)); HGap(6)
                    Text("${s.teams[sd].name} · ${sideName(sd)}", color = teamCol[sd], fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Muted("K / D", 10); Spacer(Modifier.width(50.dp))
                }
                for (l in roster[sd]) {
                    val p = s.w.players[l.playerId]
                    val dead = l.playerId in deadIds
                    Row(
                        Modifier.fillMaxWidth().background(if (cam.follow == l.playerId) Ui.card2 else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable { cam.toggleFollow(l.playerId) }.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.alpha(if (dead) 0.4f else 1f)) { Pic(if (l.charId.isNotEmpty()) Pics.char(l.charId) else Pics.player(p), p.handle, 30.dp, round = true, color = teamCol[sd].copy(alpha = 0.5f)) }
                        HGap(6)
                        Column(Modifier.weight(1f)) {
                            Text(p.handle, color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted(listOfNotNull(t.role(l.roleId).short, t.charIndex[l.charId]?.name).joinToString(" · "), 10)
                        }
                        Text("${kMap[l.playerId] ?: 0} / ${dMap[l.playerId] ?: 0}", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(50.dp), textAlign = TextAlign.Center)
                        Text(if (dead) "dead" else "${(hpOf(l.playerId) * 100).toInt()} hp", color = if (dead) Ui.loss else Ui.win, fontSize = 11.sp, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                    }
                }
            }
        }
        Card("Round feed") {
            if (shown.isEmpty()) Muted(if (phase < 0.2f) "Buy phase…" else "Taking positions…")
            for (e in shown.reversed()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                when (e.kind) {
                    "kill" -> {
                        val ka = g.lines.firstOrNull { it.playerId == e.a }
                        val kb = g.lines.firstOrNull { it.playerId == e.b }
                        Pic(ka?.charId?.takeIf { it.isNotEmpty() }?.let { Pics.char(it) } ?: Pics.player(s.w.players[e.a]), s.w.players[e.a].handle, 20.dp, round = true); HGap(4)
                        Text(s.w.players[e.a].handle, color = teamCol[e.team], fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("  ▸  ", color = Ui.muted, fontSize = 11.sp)
                        Pic(kb?.charId?.takeIf { it.isNotEmpty() }?.let { Pics.char(it) } ?: Pics.player(s.w.players[e.b]), s.w.players[e.b].handle, 20.dp, round = true); HGap(4)
                        Text(s.w.players[e.b].handle, color = teamCol[1 - e.team], fontSize = 12.sp)
                    }
                    "plant" -> Text("${t.tactical!!.objectiveName} planted on ${site?.id ?: ""}", color = teamCol[e.team], fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    else -> Text("${t.tactical!!.objectiveName} defused", color = teamCol[e.team], fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Muted("  Pinch to zoom, drag to pan, tap a round square to jump to it.", 10)
        Spacer(Modifier.height(12.dp))
    }
}

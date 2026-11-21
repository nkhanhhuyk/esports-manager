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
import dev.uem.em.model.Fixture
import dev.uem.em.model.GameRecord
import dev.uem.em.model.Line
import dev.uem.em.model.World
import dev.uem.em.sim.Ev
import dev.uem.em.sim.ReplayCodec
import dev.uem.em.titles.Catalogue
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
internal fun MapFrame(cam: MapCam, draw: DrawScope.(W: Float) -> Unit, overlay: @Composable androidx.compose.foundation.layout.BoxScope.(side: Dp) -> Unit) {
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
internal fun Token(
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
internal fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Text((if (on) "☑ " else "☐ ") + label, color = if (on) Ui.text else Ui.muted, fontSize = 12.sp, modifier = Modifier.clickable { set(!on) }.padding(horizontal = 6.dp, vertical = 4.dp))
}

/** Spot i of n on a small ring (spreads a group so the portraits don't sit on top of each other). */
private fun ring(i: Int, n: Int, r: Float): Offset {
    val a = (2 * Math.PI * i / maxOf(1, n) - Math.PI / 2).toFloat()
    return Offset(kotlin.math.cos(a) * r, kotlin.math.sin(a) * r)
}

internal fun k1(v: Double) = if (v >= 1000) "%.1fk".format(v / 1000) else "%.0f".format(v)
internal fun clock(s: Int) = "%d:%02d".format(s / 60, s % 60)

// --------------------------------------------------------------------------------- MOBA 2D view

/** Lane ids on the drawn map for an engine lane index (Dota 2 safe lanes are on opposite sides). */
internal fun laneId(t: TitleDef, engineLane: Int, side: Int): String = when {
    engineLane == 1 -> "mid"
    t.id == "dota2" -> if ((engineLane == 0) == (side == 0)) "bot" else "top"
    engineLane == 0 -> "top"
    else -> "bot"
}

/**
 * A MOBA game on its real map, read entirely from the game's recorded replay: kills with assists,
 * deaths and respawn times, structures, objectives, gold, CS and levels are what the engine
 * played. Player movement between those events is drawn by the viewer.
 */
@Composable
fun MobaViewer(w: World, f: Fixture, g: GameRecord, after: (@Composable () -> Unit)? = null) {
    val t = Catalogue.get(f.titleId)
    val map = Maps.moba(t.id) ?: return
    val rp = g.replay ?: run { Card { Muted("No 2D replay was recorded for this game.") }; return }
    // Positional replays (v2) play in the full viewer; older replays keep this event view.
    if (rp.step > 0) { MobaReplayViewer(w, f, g, after); return }
    val ev = remember(g) { ReplayCodec.events(rp, t) }
    val teams = arrayOf(w.teams[f.a], w.teams[f.b])
    val blue = g.blue
    fun mapSide(seriesTeam: Int) = if (seriesTeam == blue) 0 else 1
    val teamCol = arrayOf(Ui.blue, Ui.red)
    val sideNames = if (t.id == "dota2") "Radiant" to "Dire" else "Blue" to "Red"
    var time by remember(g) { mutableFloatStateOf(g.duration * LiveDefaults.startFraction) }
    var playing by remember(g) { mutableStateOf(LiveDefaults.autoplay) }
    var speed by remember { mutableIntStateOf(30) }
    var names by remember { mutableStateOf(true) }
    var health by remember { mutableStateOf(true) }
    var minions by remember { mutableStateOf(true) }
    var showSide by remember { mutableIntStateOf(-1) }
    val cam = remember(g) { MapCam() }
    val dur = g.duration.toFloat().coerceAtLeast(60f)
    LaunchedEffect(playing, speed, g) {
        while (playing && time < dur) { delay(50); time = (time + speed * 0.05f).coerceAtMost(dur) }
        if (time >= dur) playing = false
    }
    val T = time.toInt()
    val laneEnd = t.moba!!.laneEnd * 60
    val half = rp.pids.size / 2

    // State at time T, all from the recorded game.
    val down = Array(2) { HashMap<String, Int>() }
    val baseDown = IntArray(2)
    val kills = IntArray(2)
    val objs = IntArray(2)
    val kNow = HashMap<Int, Int>()
    val dNow = HashMap<Int, Int>()
    val aNow = HashMap<Int, Int>()
    for (e in ev) {
        if (e.t > T) break
        if (e.team < 0 && e.kind == "kill") continue
        val side = if (e.team >= 0) mapSide(e.team) else -1
        when (e.kind) {
            "tower" -> { val def = 1 - side; val lane = laneId(t, e.x, def); down[def][lane] = maxOf(down[def][lane] ?: 0, e.a) }
            "base" -> baseDown[1 - side] = maxOf(baseDown[1 - side], e.a)
            "kill" -> {
                kills[side]++
                kNow.merge(e.a, 1, Int::plus)
                dNow.merge(e.b, 1, Int::plus)
                for (i in 0 until half) if (e.m and (1 shl i) != 0) rp.pids.getOrNull(side * half + i)?.let { aNow.merge(it, 1, Int::plus) }
            }
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
    fun lanePoint(lane: String, f0: Float) = map.lanes[lane]?.at(f0) ?: Offset(0.5f, 0.5f)
    /** Seconds until the player respawns (null when alive): the engine's recorded respawn time. */
    fun respawnLeft(pid: Int): Int? {
        val k = ev.lastOrNull { it.kind == "kill" && it.b == pid && it.t <= T } ?: return null
        if (ev.any { it.kind == "buyback" && it.a == pid && it.t in k.t..T }) return null
        return (k.n - T).takeIf { it > 0 }
    }
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
    /** Where a player is heading at time `at`: the place of his next / last recorded play, else his role's spot. */
    fun target(pid: Int, side: Int, roleIdx: Int, roleId: String, at: Int): Offset {
        val near = ev.firstOrNull { abs(it.t - at) < 18 && ((it.kind == "kill" && (it.a == pid || it.b == pid)) || (it.kind == "fight") || (it.kind == "obj" && mapSide(it.team) == side)) }
        if (near != null) {
            val spot = if (near.kind == "obj") objPoint(near.x) else locPoint(near.x, side)
            return spot + Offset(if (side == 0) -0.035f else 0.035f, if (side == 0) 0.02f else -0.02f) + ring(roleIdx, 5, 0.045f)
        }
        // Sieging: the team that takes a structure stands at it shortly before.
        val siege = ev.firstOrNull { it.kind == "tower" && mapSide(it.team) == side && it.t - at in 0..20 }
        if (siege != null) {
            val defSide = 1 - side
            val lane = laneId(t, siege.x, defSide)
            val tw = towers.filter { it.lane == lane && it.team == defSide && alive(it) }.minByOrNull { it.tier }
            if (tw != null) return tw.pos + ring(roleIdx, 5, 0.05f)
        }
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
    // Gold, CS and levels from the engine's snapshots (interpolated between ticks).
    val si = ReplayCodec.snapAt(rp, T)
    val sj = (si + 1).coerceAtMost(rp.snapT.size - 1)
    val frac = if (si >= 0 && sj > si) ((T - rp.snapT[si]).toFloat() / (rp.snapT[sj] - rp.snapT[si])).coerceIn(0f, 1f) else 0f
    fun interp(pid: Int, field: Int) = ReplayCodec.value(rp, si, pid, field) * (1 - frac) + ReplayCodec.value(rp, sj, pid, field) * frac
    fun assists(l: Line): Int = aNow[l.playerId] ?: 0
    fun level(l: Line): Int = ReplayCodec.value(rp, si, l.playerId, 2).coerceAtLeast(1)
    fun goldNow(l: Line): Double = interp(l.playerId, 0).toDouble()
    fun csNow(l: Line): Int = interp(l.playerId, 1).toInt()
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
            TeamPic(teams[blue], 26.dp)
            Text(" ${teams[blue].short}", color = Ui.blue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("${kills[0]} – ${kills[1]}", color = Ui.text, fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("${teams[1 - blue].short} ", color = Ui.red, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            TeamPic(teams[1 - blue], 26.dp)
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
            // Minion waves (drawn): a wave leaves each base every 30 s and walks to the lane front.
            if (minions && T >= 65) for ((laneName, line) in map.lanes) {
                val fr = front(laneName)
                for (side in 0..1) {
                    var spawn = 65
                    while (spawn <= T) {
                        val age = T - spawn
                        val walk = (age / 40f).coerceAtMost(1f)
                        if (age < 70) {
                            val pos = if (side == 0) fr * walk else 1f - (1f - fr) * walk
                            val pt = p(line.at(pos.coerceIn(0f, 1f)))
                            for (k in 0..2) drawRect(teamCol[side].copy(alpha = 0.8f), pt + Offset((k - 1) * W * 0.008f, (k % 2) * W * 0.006f) - Offset(W * 0.003f, W * 0.003f), Size(W * 0.006f, W * 0.006f))
                        }
                        spawn += 30
                    }
                }
            }
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
                if (showSide >= 0 && ms != showSide) continue
                val left = respawnLeft(l.playerId)
                val pos = if (left != null) map.fountains.getOrElse(ms) { Offset(0.5f, 0.5f) } else posAt(l, T)
                val p = w.players[l.playerId]
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
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            fun jump(kinds: Set<String>, forward: Boolean) {
                val e = if (forward) ev.firstOrNull { it.kind in kinds && it.t > T + 1 } else ev.lastOrNull { it.kind in kinds && it.t < T - 3 }
                if (e != null) { time = (e.t - 4).coerceIn(0, dur.toInt()).toFloat(); playing = false }
            }
            TextButton(onClick = { jump(setOf("kill"), false) }) { Text("◀ kill", color = Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { jump(setOf("kill"), true) }) { Text("kill ▶", color = Ui.accent, fontSize = 12.sp) }
            TextButton(onClick = { jump(setOf("fight"), true) }) { Text("fight ▶", color = Ui.accent, fontSize = 12.sp) }
            TextButton(onClick = { jump(setOf("obj"), true) }) { Text("objective ▶", color = Ui.accent, fontSize = 12.sp) }
            TextButton(onClick = { jump(setOf("tower", "base", "core"), true) }) { Text("structure ▶", color = Ui.accent, fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            Toggle("names", names) { names = it }; Toggle("health", health) { health = it }; Toggle("minions", minions) { minions = it }
            Toggle("both teams", showSide < 0) { showSide = -1 }; Toggle(teams[blue].short, showSide == 0) { showSide = if (it) 0 else -1 }; Toggle(teams[1 - blue].short, showSide == 1) { showSide = if (it) 1 else -1 }
        }
        Slider(value = time, onValueChange = { time = it }, valueRange = 0f..dur, modifier = Modifier.padding(horizontal = 12.dp))
        // Timeline: kills (team colours), objectives (gold), structures (white).
        Canvas(Modifier.fillMaxWidth().height(14.dp).padding(horizontal = 22.dp)) {
            val W = size.width
            for (e in ev) {
                val x = W * (e.t / dur).coerceIn(0f, 1f)
                when (e.kind) {
                    "kill" -> drawLine(teamCol[mapSide(e.team)], Offset(x, 0f), Offset(x, size.height * 0.5f), strokeWidth = 2f)
                    "obj" -> drawCircle(Ui.gold, 4f, Offset(x, size.height * 0.75f))
                    "tower", "base" -> drawRect(Color.White.copy(alpha = 0.7f), Offset(x - 2f, size.height * 0.6f), Size(4f, 4f))
                }
            }
            drawLine(Color.White, Offset(W * (T / dur), 0f), Offset(W * (T / dur), size.height), strokeWidth = 2f)
        }
        if (time >= dur) after?.invoke()
        // Live scoreboard: tap a player to follow them on the map.
        Card("Scoreboard", right = { Muted("tap a player to follow") }) {
            for (team in intArrayOf(blue, 1 - blue)) {
                val ms = mapSide(team)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
                    Box(Modifier.size(10.dp).background(teamCol[ms], CircleShape)); HGap(6)
                    Text("${if (ms == 0) sideNames.first else sideNames.second} · ${teams[team].name}", color = teamCol[ms], fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Muted("Lv", 10); Spacer(Modifier.width(22.dp)); Muted("K/D/A", 10); Spacer(Modifier.width(28.dp)); Muted("Gold", 10); Spacer(Modifier.width(6.dp))
                }
                val rows = g.lines.filter { it.team == team }.sortedBy { t.lineup.indexOf(it.roleId).let { i -> if (i < 0) 9 else i } }
                for (l in rows) {
                    val p = w.players[l.playerId]
                    val left = respawnLeft(l.playerId)
                    Row(
                        Modifier.fillMaxWidth().background(if (cam.follow == l.playerId) Ui.card2 else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable { cam.toggleFollow(l.playerId) }.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.alpha(if (left != null) 0.4f else 1f)) { CharPic(l.charId, t.charIndex[l.charId]?.name ?: l.charId, 30.dp, round = true, color = teamCol[ms].copy(alpha = 0.5f)) }
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
                            CharPic(ka?.charId, ka?.charId ?: "", 20.dp, round = true); HGap(4)
                            Text(w.players[e.a].handle, color = teamCol[ms], fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("  killed  ", color = Ui.muted, fontSize = 11.sp)
                            CharPic(kb?.charId, kb?.charId ?: "", 20.dp, round = true); HGap(4)
                            Text(w.players[e.b].handle, color = teamCol[1 - ms], fontSize = 12.sp)
                        }
                        "obj" -> Text("${teams[e.team].short} take ${e.text}", color = teamCol[ms], fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        "tower" -> Text("${teams[e.team].short} destroy the ${t.moba.structures.tierNames.getOrElse(e.a - 1) { "tower" }} in ${laneId(t, e.x, 1 - ms)}", color = teamCol[ms], fontSize = 12.sp)
                        "fight" -> Text("Teamfight: ${teams[e.team].short} win it ${e.a} for ${e.b}", color = teamCol[ms], fontSize = 12.sp)
                        else -> Text("${teams[e.team].short} break into the base (${t.moba.structures.baseName})", color = teamCol[ms], fontSize = 12.sp)
                    }
                }
            }
        }
        Muted("  ${map.name}. Pinch to zoom, drag to pan, tap a player to follow. Kills, assists, deaths and respawns, structures, objectives, gold, CS and levels are the recorded game; movement, minion waves and health bars are drawn between those events.", 10)
        Spacer(Modifier.height(12.dp))
    }
}

// ------------------------------------------------------------------------------- shooter 2D view

/**
 * A shooter map round by round from the recorded replay: buys and credits, kills (with where they
 * happened and who assisted), plants, defuses and the score are the engine's; the walking between
 * them is drawn by the viewer along the map's routes.
 */
@Composable
fun TacticalViewer(w: World, f: Fixture, g: GameRecord, after: (@Composable () -> Unit)? = null) {
    val t = Catalogue.get(f.titleId)
    val map = Maps.shooter(g.mapId)
    val rp = g.replay ?: run { Card { Muted("No 2D replay was recorded for this map.") }; return }
    // Positional rounds (recorded per second) play in the full viewer.
    if (rp.step > 0) { ShooterReplayViewer(w, f, g, after); return }
    val ev = remember(g) { ReplayCodec.events(rp, t) }
    val teams = arrayOf(w.teams[f.a], w.teams[f.b])
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
    val aMap = HashMap<Int, Int>()
    fun count(e: Ev) { kMap.merge(e.a, 1, Int::plus); dMap.merge(e.b, 1, Int::plus); if (e.n >= 0) aMap.merge(e.n, 1, Int::plus) }
    for (ri in 0 until r) for (e in rounds[ri]) if (e.kind == "kill") count(e)
    for (e in shown) if (e.kind == "kill") count(e)
    // Economy at the start of this round (credits after the buy, loadout) from the recorded snapshots.
    val snapIdx = ReplayCodec.snapAt(rp, start?.t ?: r)
    fun credits(side: Int) = g.lines.filter { it.team == side }.sumOf { ReplayCodec.value(rp, snapIdx, it.playerId, 0) }
    fun loadout(side: Int) = g.lines.firstOrNull { it.team == side }?.let { ReplayCodec.value(rp, snapIdx, it.playerId, 1) } ?: 0
    val deadIds = shown.filter { it.kind == "kill" }.map { it.b }.toSet()
    fun posOf(side: Int, i: Int, ph: Float): Offset {
        if (map == null) return Offset(0.5f, 0.5f)
        return if (side == attack) {
            val k = (ph / 0.8f).coerceIn(0f, 1f)
            (atkRoute?.at(k) ?: Offset(0.5f, 0.5f)) + ring(i, 5, 0.03f + 0.03f * k)
        } else {
            val n = map.sites.size
            val anchor = if (n == 0) null else map.sites[i % n]
            val home = if (anchor == null) map.nodes[map.def].pos else anchor.pos + ring(i / n, (5 + n - 1 - i % n) / n, 0.045f)
            val k = if (site != null && ph > plantPhase) ((ph - plantPhase) / 0.3f).coerceIn(0f, 1f) else 0f
            if (k <= 0f || anchor == null || site == null || anchor === site) home + ((site?.pos ?: home) - home) * k
            else Maps.Line(map.route(anchor.node, site.node)).at(k) + ring(i, 5, 0.03f)
        }
    }
    fun deathPos(e: Ev, i: Int): Offset {
        val victimSide = 1 - e.team
        val siteP = site?.pos ?: Offset(0.5f, 0.5f)
        return when (e.m) {
            ReplayCodec.LOC_APPROACH -> (atkRoute?.at(if (victimSide == attack) 0.45f else 0.7f) ?: siteP) + ring(i, 5, 0.035f)
            else -> siteP + ring(i, 5, 0.05f)
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
            TeamPic(teams[0], 26.dp)
            Text(" ${teams[0].short}", color = Ui.blue, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("${score[0]} – ${score[1]}", color = Ui.text, fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("${teams[1].short} ", color = Ui.red, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            TeamPic(teams[1], 26.dp)
        }
        Text("Round ${r + 1} · ${map?.name ?: t.maps.firstOrNull { it.id == g.mapId }?.name ?: ""}", color = Ui.text, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        val buys = start?.text?.split("|") ?: emptyList()
        Text("${teams[attack].short} ${sideName(attack).lowercase()} (${buys.getOrElse(0) { "?" }} buy) on ${site?.id ?: "?"} · ${teams[1 - attack].short} ${sideName(1 - attack).lowercase()} (${buys.getOrElse(1) { "?" }} buy)", color = Ui.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text("${if (t.id == "cs2") "$" else "¤"}${credits(0)} left · loadout ${loadout(0)}%", color = Ui.blue, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("${if (t.id == "cs2") "$" else "¤"}${credits(1)} left · loadout ${loadout(1)}%", color = Ui.red, fontSize = 11.sp)
        }
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
                // Corridors (walkable links), shaded by level; drops and teleporters drawn as arrows.
                for (l in map.links) if (l.type != "teleport") {
                    val lv = (map.nodes[l.from].level + map.nodes[l.to].level) / 2
                    drawLine(Color(0xFF3A4258).copy(alpha = (0.55f + lv * 0.2f).coerceIn(0.4f, 1f)), p(map.nodes[l.from].pos), p(map.nodes[l.to].pos), strokeWidth = W * 0.05f)
                }
                for (rw in map.walls) drawRect(Color(0xFF151822), Offset(rw[0] * W, rw[1] * W), Size(rw[2] * W, rw[3] * W))
                for (l in map.links) {
                    val a = p(map.nodes[l.from].pos)
                    val b = p(map.nodes[l.to].pos)
                    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                    when (l.type) {
                        "teleport" -> drawLine(Color(0xFFB06CFF), a, b, strokeWidth = 3f, pathEffect = dash)
                        "vent" -> drawLine(Color(0xFF9AA3B8), a, b, strokeWidth = W * 0.008f, pathEffect = dash)
                        "stairs", "ladder" -> drawLine(Color(0xFF7C8AA8), a, b, strokeWidth = W * 0.012f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                        "breakable" -> drawLine(Color(0xFFE0A050), a, b, strokeWidth = W * 0.01f, pathEffect = dash)
                        else -> drawLine(Color(0xFF4A5470), a, b, strokeWidth = W * 0.012f)
                    }
                    if (l.oneWay || l.type == "door") {
                        // Arrow head (one-way) or a bar (door) at the middle of the link.
                        val mid = (a + b) / 2f
                        val dir = (b - a).let { v -> v / maxOf(1f, v.getDistance()) }
                        val nrm = Offset(-dir.y, dir.x)
                        val c = if (l.type == "teleport") Color(0xFFB06CFF) else if (l.type == "door") Color(0xFFE0C070) else Color(0xFFDDE3F0)
                        if (l.type == "door") drawLine(c, mid - nrm * W * 0.02f, mid + nrm * W * 0.02f, strokeWidth = 5f)
                        else { drawLine(c, mid, mid - dir * W * 0.025f + nrm * W * 0.015f, strokeWidth = 4f); drawLine(c, mid, mid - dir * W * 0.025f - nrm * W * 0.015f, strokeWidth = 4f) }
                    }
                }
                for (nd in map.nodes) if (nd.level != 0f) drawCircle(if (nd.level > 0.75f) Color(0x553DDC97) else Color(0x55FF9F43), W * 0.012f, p(nd.pos))
                for (st in map.sites) drawCircle(if (planted && st === site) Color(0x88FF5A6E) else Color(0x553B82F6), st.r * W, p(st.pos))
                drawCircle(Color(0x66FF5A6E), W * 0.025f, p(map.nodes[map.atk].pos))
                drawCircle(Color(0x664F8CFF), W * 0.025f, p(map.nodes[map.def].pos))
                if (planted && site != null) drawCircle(Color(0xFFFF5A6E), W * 0.012f, p(site.pos))
                atkRoute?.let { rt -> for (i in 1 until rt.pts.size) drawLine(teamCol[attack].copy(alpha = 0.25f), p(rt.pts[i - 1]), p(rt.pts[i]), strokeWidth = 3f) }
                // Death markers where the engine says the kill happened: on the approach, on the site, or after the plant.
                for (e in shown) if (e.kind == "kill") {
                    val l = g.lines.firstOrNull { it.playerId == e.b } ?: continue
                    val at = p(deathPos(e, roster[l.team].indexOf(l)))
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
                    val p = w.players[pid]
                    Token(
                        side, cam.view(posOf(sd, i, phase)), if (l.charId.isNotEmpty()) Pics.char(l.charId) else Pics.player(p), p.handle, p.handle, teamCol[sd],
                        hpOf(pid), null, false, cam.follow == pid, names, size = 30.dp,
                    )
                }
            }
            if (phase >= 0.97f && end != null) Text(
                "${teams[end.team].short} win the round", color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(10.dp),
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (pos >= nRounds) { pos = 0f; playing = true } else playing = !playing }) { Text(if (pos >= nRounds) "Replay" else if (playing) "Pause" else "Play", color = Ui.accent) }
            for (sp in listOf(1f, 2f, 4f, 8f)) TextButton(onClick = { speed = sp }) { Text("${sp.toInt()}x", color = if (speed == sp) Ui.accent else Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { pos = (r - 1).coerceAtLeast(0).toFloat(); playing = false }) { Text("◀ round", color = Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { pos = (r + 1).toFloat().coerceAtMost(nRounds.toFloat()) }) { Text("round ▶", color = Ui.accent, fontSize = 12.sp) }
            TextButton(onClick = {
                // Next round with a clutch, an ace or a defuse.
                val next = (r + 1 until nRounds).firstOrNull { ri -> rounds[ri].any { it.kind == "defuse" } || rounds[ri].filter { it.kind == "kill" }.groupBy { it.a }.any { it.value.size >= 4 } }
                if (next != null) { pos = next.toFloat(); playing = false }
            }) { Text("highlight ▶", color = Ui.accent, fontSize = 12.sp) }
            TextButton(onClick = { pos = nRounds.toFloat() }) { Text("Instant finish", color = Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { cam.reset() }) { Text("Fit map", color = Ui.muted, fontSize = 12.sp) }
        }
        Row(Modifier.padding(horizontal = 8.dp)) { Toggle("names", names) { names = it } }
        Slider(value = pos, onValueChange = { pos = it }, valueRange = 0f..nRounds.toFloat(), modifier = Modifier.padding(horizontal = 12.dp))
        if (pos >= nRounds) after?.invoke()
        Card("Scoreboard", right = { Muted("tap a player to follow") }) {
            for (sd in 0..1) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
                    Box(Modifier.size(10.dp).background(teamCol[sd], CircleShape)); HGap(6)
                    Text("${teams[sd].name} · ${sideName(sd)}", color = teamCol[sd], fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Muted("K / D / A", 10); Spacer(Modifier.width(44.dp))
                }
                for (l in roster[sd]) {
                    val p = w.players[l.playerId]
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
                        Text("${kMap[l.playerId] ?: 0}/${dMap[l.playerId] ?: 0}/${aMap[l.playerId] ?: 0}", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
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
                        Pic(ka?.charId?.takeIf { it.isNotEmpty() }?.let { Pics.char(it) } ?: Pics.player(w.players[e.a]), w.players[e.a].handle, 20.dp, round = true); HGap(4)
                        Text(w.players[e.a].handle, color = teamCol[e.team], fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("  ▸  ", color = Ui.muted, fontSize = 11.sp)
                        Pic(kb?.charId?.takeIf { it.isNotEmpty() }?.let { Pics.char(it) } ?: Pics.player(w.players[e.b]), w.players[e.b].handle, 20.dp, round = true); HGap(4)
                        Text(w.players[e.b].handle, color = teamCol[1 - e.team], fontSize = 12.sp)
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

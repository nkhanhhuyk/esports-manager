package dev.uem.em.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.Fixture
import dev.uem.em.model.GameRecord
import dev.uem.em.model.Line
import dev.uem.em.model.World
import dev.uem.em.sim.Abilities
import dev.uem.em.sim.Ev
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.MobaMap
import dev.uem.em.sim.ReplayCodec
import dev.uem.em.sim.ReplayPos
import dev.uem.em.titles.Catalogue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Where a replay should open (set by the match report's analysis links). */
object ReplayJump { var seconds: Int = -1 }

/**
 * The positional MOBA replay (replay v2): every hero's recorded position, health and state every
 * two seconds; ability casts with their targets and effects; minion waves; structure health;
 * objectives; wards; kills and teamfights. Playback time is separate from simulation time, so
 * speed, pausing and seeking never change the game.
 */
@Composable
fun MobaReplayViewer(w: World, f: Fixture, g: GameRecord, after: (@Composable () -> Unit)? = null) {
    val t = Catalogue.get(f.titleId)
    val rp = g.replay ?: return
    val map = remember(t.id) { MobaMap.of(t.id) }
    val ev = remember(g) { ReplayCodec.events(rp, t, w) }
    val positional = remember(g) { ev.any { it.kind == "spawn" && it.n >= 0 } }
    val teams = arrayOf(w.teams[f.a], w.teams[f.b])
    val blue = g.blue
    fun mapSide(seriesTeam: Int) = if (seriesTeam == blue) 0 else 1
    val teamCol = arrayOf(Ui.blue, Ui.red)
    val sideNames = if (t.id == "dota2") "Radiant" to "Dire" else "Blue" to "Red"
    val dur = g.duration.toFloat().coerceAtLeast(60f)
    val start = remember(g) { ReplayJump.seconds.takeIf { it >= 0 }?.toFloat()?.coerceIn(0f, dur).also { ReplayJump.seconds = -1 } ?: (dur * LiveDefaults.startFraction) }
    var time by remember(g) { mutableFloatStateOf(start) }
    var playing by remember(g) { mutableStateOf(LiveDefaults.autoplay) }
    var speed by remember { mutableIntStateOf(8) }
    var names by remember { mutableStateOf(true) }
    var health by remember { mutableStateOf(true) }
    var effects by remember { mutableStateOf(true) }
    var waves by remember { mutableStateOf(true) }
    var trails by remember { mutableStateOf(false) }
    var vision by remember { mutableIntStateOf(-1) } // -1 spectator, 0/1 that side's (approximate) vision
    var auto by remember { mutableStateOf(true) }
    val cam = remember(g) { MapCam() }
    // Animation clock (UI time) drives playback; the replay data is never recomputed.
    LaunchedEffect(playing, speed, g) {
        var last = 0L
        while (playing && time < dur) {
            // The last 20 seconds (the final push on the core) always play slowly enough to see.
            withFrameNanos { now -> if (last != 0L) time = (time + (now - last) / 1e9f * (if (time > dur - 20f) minOf(speed, 2) else speed)).coerceAtMost(dur); last = now }
        }
        if (time >= dur) playing = false
    }
    val T = time
    val Ti = T.toInt()
    val n = rp.pids.size
    val samples = ReplayPos.samples(rp)
    val step = rp.step.coerceAtLeast(1)
    val s0 = (T / step).toInt().coerceIn(0, max(0, samples - 1))
    val s1 = (s0 + 1).coerceAtMost(max(0, samples - 1))
    val fr = ((T - s0 * step) / step).coerceIn(0f, 1f)
    val lineOfPid = remember(g) { rp.pids.map { pid -> g.lines.firstOrNull { it.playerId == pid } } }
    val sideOfIdx = IntArray(n) { i -> lineOfPid[i]?.let { mapSide(it.team) } ?: (if (i < n / 2) 0 else 1) }
    fun unit(i: Int): FloatArray {
        val a = ReplayPos.unit(rp, s0, i); val b = ReplayPos.unit(rp, s1, i)
        val ax = MobaEngine.unitX(a); val ay = MobaEngine.unitY(a); val bx = MobaEngine.unitX(b); val by = MobaEngine.unitY(b)
        val jump = abs(ax - bx) + abs(ay - by) > 160f || (MobaEngine.unitFlags(a) and 1) != (MobaEngine.unitFlags(b) and 1)
        val k = if (jump) (if (fr < 0.5f) 0f else 1f) else fr
        val src = if (k < 0.5f) a else b
        return floatArrayOf(ax + (bx - ax) * k, ay + (by - ay) * k, MobaEngine.unitHp(a) + (MobaEngine.unitHp(b) - MobaEngine.unitHp(a)) * k, MobaEngine.unitFlags(src).toFloat())
    }
    val units = Array(n) { unit(it) }
    // Score, objectives and structures from the events up to now.
    val kills = IntArray(2); val objs = IntArray(2)
    val kNow = HashMap<Int, Int>(); val dNow = HashMap<Int, Int>(); val aNow = HashMap<Int, Int>()
    val structHp = IntArray(map.structs.size) { 100 }
    val objAlive = BooleanArray(t.moba!!.objectives.size)
    /** Objectives alive by pit position (newer replays record where each one spawned). */
    val aliveAt = HashSet<Int>()
    val wards = ArrayList<Triple<Float, Float, Int>>()
    /** Kind code of the objective alive at each pit position (which drake element, Turtle, Lord...). */
    val kindAt = HashMap<Int, Int>()
    for (e in ev) {
        if (e.t > Ti) break
        when (e.kind) {
            "kill" -> if (e.team >= 0) {
                val side = mapSide(e.team); kills[side]++
                if (e.a >= 0) kNow.merge(e.a, 1, Int::plus)
                for (i in 0 until 5) if (e.m and (1 shl i) != 0) rp.pids.getOrNull(side * (n / 2) + i)?.let { aNow.merge(it, 1, Int::plus) }
                dNow.merge(e.b, 1, Int::plus)
            } else dNow.merge(e.b, 1, Int::plus)
            "obj" -> { objs[mapSide(e.team)]++; objAlive.getOrNull(e.x)?.let { objAlive[e.x] = false }; if (e.n >= 0) aliveAt.remove(e.n) }
            "spawn" -> { if (e.x in objAlive.indices) objAlive[e.x] = true; if (e.n >= 0) { aliveAt.add(e.n); kindAt[e.n] = e.m } }
            "shp" -> if (e.x in structHp.indices) structHp[e.x] = min(structHp[e.x], e.a)
            "tower", "base", "core" -> if (e.n in structHp.indices) structHp[e.n] = 0
        }
    }
    if (MobaEngine.hasWards(t.id)) for (e in ev) if (e.kind == "ward" && e.t <= Ti && Ti < e.t + e.m) wards.add(Triple(MobaEngine.unpackX(e.n), MobaEngine.unpackY(e.n), mapSide(e.team)))
    for (e in ev) if (e.kind == "unward" && e.t <= Ti) wards.removeAll { abs(it.first - MobaEngine.unpackX(e.n)) < 2 && abs(it.second - MobaEngine.unpackY(e.n)) < 2 }
    // Approximate team vision (heroes, wards, towers) for the fog-of-war view.
    fun seenBy(side: Int, x: Float, y: Float): Boolean {
        for (i in 0 until n) if (sideOfIdx[i] == side && (units[i][3].toInt() and 1) == 0 && dist(units[i][0], units[i][1], x, y) < 85f && map.los(units[i][0], units[i][1], x, y)) return true
        for (wd in wards) if (wd.third == side && dist(wd.first, wd.second, x, y) < 75f) return true
        for ((si, s) in map.structs.withIndex()) if (s.team == side && structHp[si] > 0 && dist(s.x, s.y, x, y) < 95f) return true
        return false
    }
    // Items bought so far (in order), spells cast (for cooldowns), deaths (buffs lost on death).
    val buys = remember(g) { HashMap<Int, ArrayList<Pair<Int, String>>>().also { m -> for (e in ev) if (e.kind == "item" && e.text.isNotEmpty()) m.getOrPut(e.a) { ArrayList() }.add(e.t to e.text) } }
    val casts = remember(g) { HashMap<Int, ArrayList<Ev>>().also { m -> for (e in ev) if (e.kind == "spell" && e.text.isNotEmpty()) m.getOrPut(e.a) { ArrayList() }.add(e) } }
    val deathsOf = remember(g) { HashMap<Int, ArrayList<Int>>().also { m -> for (e in ev) if (e.kind == "kill" && e.b >= 0) m.getOrPut(e.b) { ArrayList() }.add(e.t) } }
    // Objectives that empower a side's minions for a while (Baron, Herald, Lord, Overlords, Tempest Dragon).
    val pushes = remember(g) {
        val kinds = dev.uem.em.titles.Neutrals.of(w, t.id)
        ev.filter { it.kind == "obj" && it.m > 0 && it.team >= 0 }.mapNotNull { e ->
            val k = kinds.getOrNull(e.m - 1) ?: return@mapNotNull null
            val secs = k.stats["push"] ?: return@mapNotNull null
            Push(mapSide(e.team), k, e.t, e.t + secs.toInt())
        }
    }
    val pushNow = pushes.filter { Ti in it.from until it.until }
    val si = ReplayCodec.snapAt(rp, Ti)
    fun snap(pid: Int, field: Int) = ReplayCodec.value(rp, si, pid, field)
    val teamGold = DoubleArray(2).also { a -> for ((i, pid) in rp.pids.withIndex()) a[sideOfIdx[i]] += snap(pid, 0).toDouble() }
    // Camera: follow a player, else the action (recent casts / fights) when auto is on.
    val followIdx = rp.pids.indexOf(cam.follow)
    val coreEv = remember(g) { ev.lastOrNull { it.kind == "core" } }
    val lostCore = coreEv?.let { map.structs.getOrNull(it.n) }
    cam.pinned = when {
        followIdx >= 0 -> Offset(units[followIdx][0] / 1000f, units[followIdx][1] / 1000f)
        auto && lostCore != null && T > dur - 20f -> Offset(lostCore.x / 1000f, lostCore.y / 1000f)
        auto && cam.zoom > 1.05f -> {
            val recent = ev.filter { it.kind == "cast" && it.t in (Ti - 4)..Ti }
            if (recent.isEmpty()) null else Offset(recent.map { MobaEngine.unpackX(it.n) }.average().toFloat() / 1000f, recent.map { MobaEngine.unpackY(it.n) }.average().toFloat() / 1000f)
        }
        else -> null
    }

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
            Text(clock(Ti), color = Ui.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text(k1(teamGold[1]), color = Ui.gold, fontSize = 12.sp)
        }
        val down = IntArray(2).also { d -> for ((i, s) in map.structs.withIndex()) if (structHp[i] == 0) d[s.team]++ }
        Text("${map.name} · structures destroyed ${down[1]}–${down[0]} · objectives ${objs[0]}–${objs[1]} · gold lead ${k1(abs(teamGold[0] - teamGold[1]))}", color = Ui.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
        // Every kind of neutral objective each team has taken so far (drakes by element, Baron, Turtles, Lords...).
        Column(Modifier.padding(horizontal = 12.dp)) { ObjTallyStrip(ObjTally.fromEvents(w, t, ev, Ti, ::mapSide), listOf(teams[blue].short, teams[1 - blue].short)) }
        MapFrame(cam, draw = { W ->
            val u = W / 1000f
            fun p(x: Float, y: Float) = Offset(x * u, y * u)
            drawRect(Color(0xFF1C3524), Offset.Zero, Size(W, W))
            for (r in map.walls) drawRect(Color(0xFF10201A), Offset(r[0] * u, r[1] * u), Size(r[2] * u, r[3] * u))
            for (l in map.lanes.values) { var f0 = 0f; var prev = p(l.x(0f), l.y(0f)); while (f0 < 1f) { f0 += 0.02f; val q = p(l.x(f0), l.y(f0)); drawLine(Color(0xFF8A7A55), prev, q, strokeWidth = W * 0.014f); prev = q } }
            for (s in 0..1) drawCircle(teamCol[s].copy(alpha = 0.22f), W * 0.05f, p(map.fountains[s][0], map.fountains[s][1]))
            for (c in map.camps) drawCircle(Color(0x553DDC97), W * 0.009f, p(c.x, c.y))
            // Objectives: bright when alive, dim when taken.
            if (positional) for (pit in map.pits) {
                val on = dev.uem.em.sim.MobaEngine.packPos(pit.x, pit.y) in aliveAt
                drawCircle(if (on) Color(0xFFB98CFF) else Color(0x556E5A9B), W * 0.028f, p(pit.x, pit.y), style = Stroke(if (on) 5f else 2f))
            } else for ((oi, o) in t.moba.objectives.withIndex()) {
                val pit = map.pits.firstOrNull { o.id in it.objectives } ?: continue
                drawCircle(if (objAlive[oi]) Color(0xFFB98CFF) else Color(0x556E5A9B), W * 0.028f, p(pit.x, pit.y), style = Stroke(if (objAlive[oi]) 5f else 2f))
            }
            // Heroes holding a buff that ends on death (Baron Nashor): a purple aura.
            for (pu in pushNow) if (pu.kind.endsOnDeath) for (i in 0 until n) {
                if (sideOfIdx[i] != pu.side || (units[i][3].toInt() and 1) != 0) continue
                val pid = rp.pids[i]
                if (deathsOf[pid]?.any { it in (pu.from - 1)..Ti } == true) continue
                val atTake = ReplayPos.unit(rp, (pu.from / step).coerceIn(0, max(0, samples - 1)), i)
                if ((MobaEngine.unitFlags(atTake) and 1) != 0) continue
                drawCircle(EMPOWERED.copy(alpha = 0.35f), W * 0.034f, p(units[i][0], units[i][1]))
            }
            // Wards.
            for (wd in wards) if (vision < 0 || wd.third == vision) { drawCircle(teamCol[wd.third].copy(alpha = 0.12f), 75f * u, p(wd.first, wd.second)); drawCircle(Color(0xFFFFD166), W * 0.006f, p(wd.first, wd.second)) }
            // Structures with health.
            for ((i, s) in map.structs.withIndex()) {
                val c = teamCol[s.team]; val pt = p(s.x, s.y)
                val big = s.kind == "core"
                val half = W * (if (big) 0.032f else 0.013f)
                if (structHp[i] <= 0) { drawRect(c.copy(alpha = 0.3f), pt - Offset(half, half), Size(half * 2, half * 2), style = Stroke(2f)); continue }
                drawRect(Color(0xFF0B0F14), pt - Offset(half + 2f, half + 2f), Size(half * 2 + 4f, half * 2 + 4f))
                drawRect(c, pt - Offset(half, half), Size(half * 2, half * 2))
                if (health && structHp[i] < 100) {
                    drawRect(Color(0xCC000000), pt + Offset(-half, half + 3f), Size(half * 2, 4f))
                    drawRect(if (structHp[i] > 50) Color(0xFF3CCF7A) else Color(0xFFFF5D5D), pt + Offset(-half, half + 3f), Size(half * 2 * structHp[i] / 100f, 4f))
                }
            }
            // Minion waves: the front of each side's minions in each lane, sized by count.
            if (waves && samples > 0) for (li in 0..2) {
                val lane = map.lanes[map.laneIds[li]] ?: continue
                for (s in 0..1) {
                    val wv = ReplayPos.wave(rp, s0, li, s)
                    val cnt = (wv shr 10) and 63
                    if (cnt <= 0) continue
                    val f0 = (wv and 1023) / 1000f
                    val sup = (wv shr 16) and 1 == 1
                    val dir = if (s == 0) -1f else 1f
                    // Minions empowered by Baron / Lord / an Overlord are purple.
                    val buffed = sup && pushNow.any { it.side == s }
                    for (k in 0 until min(cnt, 8)) {
                        val ff = (f0 + dir * k * 0.006f).coerceIn(0f, 1f)
                        val pt = p(lane.x(ff), lane.y(ff)) + Offset(((k % 2) * 2 - 1) * W * 0.004f, 0f)
                        val sz = W * (if (sup) 0.008f else 0.006f)
                        if (buffed) drawCircle(EMPOWERED.copy(alpha = 0.35f), sz * 1.1f, pt + Offset(sz / 2 - W * 0.003f, sz / 2 - W * 0.003f))
                        drawRect(if (buffed) EMPOWERED else teamCol[s].copy(alpha = 0.85f), pt - Offset(W * 0.003f, W * 0.003f), Size(sz, sz))
                    }
                }
            }
            // Movement trails (last 12 s).
            if (trails) for (i in 0 until n) {
                if (followIdx >= 0 && i != followIdx) continue
                var prev: Offset? = null
                for (s in max(0, s0 - 6)..s0) { val v = ReplayPos.unit(rp, s, i); val q = p(MobaEngine.unitX(v), MobaEngine.unitY(v)); prev?.let { pr -> if ((pr - q).getDistance() < 160f * u) drawLine(teamCol[sideOfIdx[i]].copy(alpha = 0.5f), pr, q, strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))) }; prev = q }
            }
            // Fights.
            for (e in ev) { if (e.t > Ti) break; if (e.kind == "fight" && Ti in e.t..(e.t + e.m + 3)) drawCircle(Color(0x33FF5A6E), W * 0.08f, p(MobaEngine.unpackX(e.n), MobaEngine.unpackY(e.n))) }
            // Ability casts: projectiles, areas, beams, dashes, heals and shields as they were cast.
            if (effects) for (e in ev) {
                if (e.t > Ti) break
                if (e.kind != "cast" || T - e.t > 1.4f || T < e.t) continue
                val ci = rp.pids.indexOf(e.a); if (ci < 0) continue
                val side = sideOfIdx[ci]
                if (vision >= 0 && side != vision && !seenBy(vision, units[ci][0], units[ci][1])) continue
                val age = ((T - e.t) / 1.4f).coerceIn(0f, 1f)
                val from = p(units[ci][0], units[ci][1])
                val to = p(MobaEngine.unpackX(e.n), MobaEngine.unpackY(e.n))
                val cc = (e.m and ((1 shl Abilities.STUN) or (1 shl Abilities.KNOCKUP) or (1 shl Abilities.ROOT) or (1 shl Abilities.SUPPRESS) or (1 shl Abilities.CHARM) or (1 shl Abilities.TAUNT) or (1 shl Abilities.FEAR) or (1 shl Abilities.SILENCE))) != 0
                val heal = (e.m and ((1 shl Abilities.HEAL) or (1 shl Abilities.SHIELD))) != 0
                val col = when { cc -> Color(0xFFFFD166); heal -> Color(0xFF3DDC97); else -> teamCol[side] }.copy(alpha = 1f - age * 0.8f)
                val line = lineOfPid[ci]
                val kit = line?.let { Abilities.kit(it.charId, t.charIndex[it.charId]) }
                val vfx = if (e.x in 0..3) kit?.actives?.getOrNull(e.x)?.vfx ?: "beam" else "dash"
                when (vfx) {
                    "projectile" -> { val tip = from + (to - from) * min(1f, age * 2.2f); drawLine(col, from + (to - from) * max(0f, age * 2.2f - 0.35f), tip, strokeWidth = W * 0.008f); drawCircle(col, W * 0.008f, tip) }
                    "area", "cone" -> { drawCircle(col.copy(alpha = 0.25f * (1f - age)), W * 0.03f, to); drawCircle(col, W * 0.03f, to, style = Stroke(3f)) }
                    "aura" -> drawCircle(col, W * (0.012f + 0.02f * age), to, style = Stroke(4f))
                    "dash" -> drawLine(col, from, to, strokeWidth = W * 0.006f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
                    else -> drawLine(col, from, to, strokeWidth = W * 0.005f)
                }
                if (cc) drawCircle(Color(0xFFFFD166).copy(alpha = 0.7f * (1f - age)), W * 0.014f, to, style = Stroke(3f))
            }
            // The core falling.
            if (lostCore != null && coreEv != null && Ti >= coreEv.t) {
                val c = p(lostCore.x, lostCore.y)
                for (k in 0..2) drawCircle(Color(0xFFFFD166).copy(alpha = 0.5f - k * 0.15f), W * (0.04f + k * 0.03f), c, style = Stroke(6f))
            }
            // Kill markers.
            for (e in ev) {
                if (e.t > Ti) break
                if (e.kind == "kill" && Ti - e.t in 0..4) {
                    val vi = rp.pids.indexOf(e.b); if (vi < 0) continue
                    val vs = ReplayPos.unit(rp, (e.t / step).coerceAtMost(samples - 1), vi)
                    val at = p(MobaEngine.unitX(vs), MobaEngine.unitY(vs)); val a = 1f - (Ti - e.t) / 5f; val d = W * 0.016f
                    drawLine(Color.White.copy(alpha = a), at - Offset(d, d), at + Offset(d, d), strokeWidth = 4f)
                    drawLine(Color.White.copy(alpha = a), at + Offset(d, -d), at - Offset(d, -d), strokeWidth = 4f)
                }
            }
        }) { side ->
            val kindsAll = dev.uem.em.titles.Neutrals.of(w, t.id)
            @Composable fun pinAt(x: Float, y: Float, sz: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
                val q = cam.view(Offset(x / 1000f, y / 1000f))
                if (q.x < -0.05f || q.y < -0.05f || q.x > 1.05f || q.y > 1.05f) return
                Box(Modifier.offset(x = side * q.x - sz / 2, y = side * q.y - sz / 2)) { content() }
            }
            val pitSz = (side.value * 0.05f * cam.zoom.coerceAtMost(1.6f)).dp
            if (positional) for (pit in map.pits) {
                val key = MobaEngine.packPos(pit.x, pit.y)
                if (key !in aliveAt) continue
                val kind = kindAt[key]?.let { kindsAll.getOrNull(it - 1) }
                val obj = t.moba.objectives.firstOrNull { o -> kind?.objId == o.id } ?: t.moba.objectives.firstOrNull { it.id in pit.objectives }
                pinAt(pit.x, pit.y, pitSz) { ObjPic(objKey(t, kind?.id, obj?.id), kind?.name ?: obj?.name ?: "", pitSz) }
            } else for ((oi, o) in t.moba.objectives.withIndex()) {
                if (!objAlive[oi]) continue
                val pit = map.pits.firstOrNull { o.id in it.objectives } ?: continue
                pinAt(pit.x, pit.y, pitSz) { ObjPic(objKey(t, null, o.id), o.name, pitSz) }
            }
            // A summoned objective pushing with its minions: MLBB's Lord, the Rift Herald, HoK's Overlord vanguards.
            for (pu in pushNow) {
                if (pu.kind.objId !in PUSHERS) continue
                val fronts = (0..2).mapNotNull { li ->
                    val lane = map.lanes[map.laneIds[li]] ?: return@mapNotNull null
                    val wv = ReplayPos.wave(rp, s0, li, pu.side)
                    if ((wv shr 10) and 63 <= 0) return@mapNotNull null
                    val f0 = (wv and 1023) / 1000f
                    Triple(if (pu.side == 0) f0 else 1f - f0, lane.x(f0), lane.y(f0))
                }
                val shown = if (pu.kind.objId in setOf("overlord", "shadow", "tempest")) fronts else listOfNotNull(fronts.maxByOrNull { it.first })
                val sz = (pitSz.value * 0.9f).dp
                for (fr in shown) pinAt(fr.second, fr.third, sz) {
                    Box(Modifier.size(sz).background(EMPOWERED, CircleShape).padding(2.dp)) { ObjPic(objKey(t, pu.kind.id, pu.kind.objId), pu.kind.name, sz - 4.dp) }
                }
            }
            if (coreEv != null && Ti >= coreEv.t) Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
                Text("${teams[coreEv.team.coerceIn(0, 1)].short} destroy the ${t.moba.structures.coreName} — victory", color = Ui.gold, fontWeight = FontWeight.Black, fontSize = 16.sp,
                    modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
            }
            for (i in 0 until n) {
                val l = lineOfPid[i] ?: continue
                val ms = sideOfIdx[i]
                val v = units[i]
                val flags = v[3].toInt()
                val dead = flags and 1 != 0
                if (vision >= 0 && ms != vision && (dead || !seenBy(vision, v[0], v[1]))) continue
                val p = w.players[l.playerId]
                val respawn = if (dead) (snap(l.playerId, 3) - Ti).takeIf { it > 0 } else null
                Box(Modifier.alpha(if (flags and 8 != 0) 0.45f else 1f)) {
                    Token(side, cam.view(Offset(v[0] / 1000f, v[1] / 1000f)), Pics.char(l.charId), t.charIndex[l.charId]?.name ?: p.handle, p.handle + (if (flags and 2 != 0) " ✦" else "") + (if (flags and 4 != 0) " ⟲" else ""),
                        teamCol[ms], if (health) v[2] / 100f else null, snap(l.playerId, 2).coerceAtLeast(1), dead, cam.follow == l.playerId, names, deadText = respawn?.toString())
                }
            }
        }
        // Playback controls.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (time >= dur) { time = 0f; playing = true } else playing = !playing }) { Text(if (time >= dur) "Replay" else if (playing) "Pause" else "Play", color = Ui.accent) }
            for (sp in listOf(1, 2, 4, 8, 16, 32)) TextButton(onClick = { speed = sp }) { Text("${sp}×", color = if (speed == sp) Ui.accent else Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { time = dur; playing = false }) { Text("Skip to end", color = Ui.muted, fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            fun jump(kinds: Set<String>, forward: Boolean) {
                val e = if (forward) ev.firstOrNull { it.kind in kinds && it.t > Ti + 1 } else ev.lastOrNull { it.kind in kinds && it.t < Ti - 3 }
                if (e != null) { time = (e.t - 5).coerceIn(0, dur.toInt()).toFloat(); playing = false }
            }
            TextButton(onClick = { jump(setOf("kill", "fight", "obj", "tower", "base", "core"), false) }) { Text("◀ event", color = Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { jump(setOf("kill", "fight", "obj", "tower", "base", "core"), true) }) { Text("event ▶", color = Ui.accent, fontSize = 12.sp) }
            TextButton(onClick = { jump(setOf("kill"), true) }) { Text("kill ▶", color = Ui.accent, fontSize = 12.sp) }
            TextButton(onClick = { jump(setOf("fight"), true) }) { Text("teamfight ▶", color = Ui.accent, fontSize = 12.sp) }
            TextButton(onClick = { jump(setOf("obj", "spawn"), true) }) { Text("objective ▶", color = Ui.accent, fontSize = 12.sp) }
            TextButton(onClick = { jump(setOf("tower", "base", "core"), true) }) { Text("structure ▶", color = Ui.accent, fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            Toggle("names", names) { names = it }; Toggle("health", health) { health = it }; Toggle("abilities", effects) { effects = it }; Toggle("minions", waves) { waves = it }; Toggle("paths", trails) { trails = it }
            Toggle("auto camera", auto) { auto = it }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            Toggle("spectator view", vision < 0) { vision = -1 }
            Toggle("${teams[blue].short} vision", vision == 0) { vision = if (it) 0 else -1 }
            Toggle("${teams[1 - blue].short} vision", vision == 1) { vision = if (it) 1 else -1 }
            TextButton(onClick = { cam.reset() }) { Text("Reset view", color = Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { cam.zoom = (cam.zoom * 1.3f).coerceAtMost(3f); cam.clampCenter() }) { Text("+", color = Ui.muted) }
            TextButton(onClick = { cam.zoom = (cam.zoom / 1.3f).coerceAtLeast(1f); cam.clampCenter() }) { Text("−", color = Ui.muted) }
        }
        Slider(value = time, onValueChange = { time = it; playing = false }, valueRange = 0f..dur, modifier = Modifier.padding(horizontal = 12.dp))
        Canvas(Modifier.fillMaxWidth().height(16.dp).padding(horizontal = 22.dp)) {
            val Wd = size.width
            for (e in ev) {
                val x = Wd * (e.t / dur).coerceIn(0f, 1f)
                when (e.kind) {
                    "kill" -> if (e.team >= 0) drawLine(teamCol[mapSide(e.team)], Offset(x, 0f), Offset(x, size.height * 0.45f), strokeWidth = 2f)
                    "fight" -> drawRect(Color(0x88FF5A6E), Offset(x, size.height * 0.45f), Size(max(2f, Wd * e.m / dur), 4f))
                    "obj" -> drawCircle(Ui.gold, 4f, Offset(x, size.height * 0.75f))
                    "tower", "base", "core" -> drawRect(Color.White.copy(alpha = 0.7f), Offset(x - 2f, size.height * 0.62f), Size(4f, 4f))
                }
            }
            drawLine(Color.White, Offset(Wd * (T / dur), 0f), Offset(Wd * (T / dur), size.height), strokeWidth = 2f)
        }
        if (time >= dur) after?.invoke()
        // Scoreboard.
        Card("Scoreboard", right = { Muted("tap a player to follow") }) {
            for (team in intArrayOf(blue, 1 - blue)) {
                val ms = mapSide(team)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
                    Box(Modifier.size(10.dp).background(teamCol[ms], CircleShape)); HGap(6)
                    Text("${if (ms == 0) sideNames.first else sideNames.second} · ${teams[team].name}", color = teamCol[ms], fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Muted("Lv", 10); Spacer(Modifier.width(18.dp)); Muted("K/D/A", 10); Spacer(Modifier.width(24.dp)); Muted("Gold", 10); Spacer(Modifier.width(6.dp))
                }
                for (l in g.lines.filter { it.team == team }.sortedBy { t.lineup.indexOf(it.roleId).let { i -> if (i < 0) 9 else i } }) {
                    val p = w.players[l.playerId]
                    val i = rp.pids.indexOf(l.playerId)
                    val v = if (i >= 0) units[i] else floatArrayOf(0f, 0f, 100f, 0f)
                    val dead = v[3].toInt() and 1 != 0
                    val ult = snap(l.playerId, 5)
                    Row(Modifier.fillMaxWidth().background(if (cam.follow == l.playerId) Ui.card2 else Color.Transparent, RoundedCornerShape(6.dp)).clickable { cam.toggleFollow(l.playerId) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.alpha(if (dead) 0.4f else 1f)) { CharPic(l.charId, t.charIndex[l.charId]?.name ?: l.charId, 30.dp, round = true, color = teamCol[ms].copy(alpha = 0.5f)) }
                        HGap(6)
                        Column(Modifier.weight(1f)) {
                            Text(p.handle, color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted("${t.role(l.roleId).short} · ${(v[2]).toInt()}% HP · ult ${if (ult > 0) "${ult}s" else "ready"}${if (dead) " · dead" else ""}", 10)
                        }
                        Text("${snap(l.playerId, 2).coerceAtLeast(1)}", color = Ui.text, fontSize = 12.sp, modifier = Modifier.width(22.dp), textAlign = TextAlign.Center)
                        Text("${kNow[l.playerId] ?: 0}/${dNow[l.playerId] ?: 0}/${aNow[l.playerId] ?: 0}", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
                        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.End) { Text(k1(snap(l.playerId, 0).toDouble()), color = Ui.gold, fontSize = 12.sp); Muted("${snap(l.playerId, 1)} cs", 9) }
                    }
                    // What the hero has right now: the items bought so far, its spells (dark with the seconds left
                    // while on cooldown) and its rune / emblem / arcana with the name under the picture.
                    val owned = buys[l.playerId]?.filter { it.first <= Ti }?.map { it.second }.orEmpty()
                    Row(Modifier.fillMaxWidth().padding(start = 36.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (k in 0 until 6) {
                            val id = owned.getOrNull(k)
                            if (id != null) ItemPic(w, id, 20.dp) else Box(Modifier.size(20.dp).background(Ui.card2, RoundedCornerShape(4.dp)))
                            HGap(2)
                        }
                        if (l.spells.isNotEmpty()) {
                            HGap(6)
                            for (sp in l.spells) {
                                val last = casts[l.playerId]?.lastOrNull { it.text == sp && it.t <= Ti }
                                SpellPic(w, sp, 20.dp, last?.let { (it.t + it.m - Ti).coerceAtLeast(0) } ?: 0)
                                HGap(2)
                            }
                        }
                        val rr = mainRunes(w, t, l.runes).firstOrNull()
                        if (rr != null) {
                            HGap(6)
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(58.dp)) {
                                RunePic(w, rr.id, 18.dp)
                                Text(rr.name.removePrefix("Custom "), color = Ui.muted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
        val talk = remember(g) { Commentary.moba(w, t, g, ev, teams) }
        CommentaryCard(talk, Ti)
        PlayFeed(w, t, ev, g, Ti, teams, ::mapSide, teamCol)
        Muted("  ${map.name} (walkable grid from the map's terrain; approximate layout). Everything shown — movement, health, abilities, minions, structures, wards, objectives — is the recorded simulation; playback speed and seeking never change it.", 10)
        Spacer(Modifier.height(12.dp))
    }
}

private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float { val dx = ax - bx; val dy = ay - by; return sqrt(dx * dx + dy * dy) }

@Composable
private fun PlayFeed(w: World, t: dev.uem.em.titles.TitleDef, ev: List<Ev>, g: GameRecord, Ti: Int, teams: Array<dev.uem.em.model.Team>, mapSide: (Int) -> Int, teamCol: Array<Color>) {
    val feed = ev.filter { it.t <= Ti && it.kind in setOf("kill", "obj", "tower", "base", "core", "fight", "steal", "aegis", "buyback") }.takeLast(10).reversed()
    Card("Play-by-play") {
        if (feed.isEmpty()) Muted("Minions are on their way…")
        for (e in feed) {
            val ms = if (e.team >= 0) mapSide(e.team) else 0
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(clock(e.t.coerceAtLeast(0)), color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(38.dp))
                val who = { pid: Int -> if (pid >= 0) w.players[pid].handle else "" }
                when (e.kind) {
                    "kill" -> if (e.team < 0) Text("${who(e.b)} was executed", color = Ui.muted, fontSize = 12.sp) else {
                        val ka = g.lines.firstOrNull { it.playerId == e.a }; val kb = g.lines.firstOrNull { it.playerId == e.b }
                        CharPic(ka?.charId, ka?.charId ?: "", 20.dp, round = true); HGap(4)
                        Text(who(e.a), color = teamCol[ms], fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("  killed  ", color = Ui.muted, fontSize = 11.sp)
                        CharPic(kb?.charId, kb?.charId ?: "", 20.dp, round = true); HGap(4)
                        Text(who(e.b), color = teamCol[1 - ms], fontSize = 12.sp)
                    }
                    "obj" -> Text("${teams[e.team].short} take ${e.text}", color = teamCol[ms], fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    "steal" -> Text("${who(e.a)} steals ${e.text}!", color = Ui.gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    "tower" -> Text("${teams[e.team].short} destroy ${MobaMap.of(t.id).structs.getOrNull(e.n)?.name ?: "a tower"}", color = teamCol[ms], fontSize = 12.sp)
                    "base" -> Text("${teams[e.team].short} destroy ${MobaMap.of(t.id).structs.getOrNull(e.n)?.name ?: "a base structure"}", color = teamCol[ms], fontSize = 12.sp)
                    "core" -> Text("${teams[e.team].short} destroy the ${t.moba!!.structures.coreName} — victory", color = teamCol[ms], fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    "fight" -> Text("Teamfight: ${teams[e.team].short} win it ${e.a} for ${e.b} (${e.m}s)", color = teamCol[ms], fontSize = 12.sp)
                    "aegis" -> Text("${who(e.a)} comes back with the Aegis", color = Ui.gold, fontSize = 12.sp)
                    "buyback" -> Text("${who(e.a)} buys back", color = teamCol[ms], fontSize = 12.sp)
                }
            }
        }
    }
}

/** An objective's buff on a side's minions, from the moment it was slain. */
private class Push(val side: Int, val kind: dev.uem.em.titles.NeutralDef, val from: Int, val until: Int)
/** Empowered minions and Baron-buffed heroes. */
private val EMPOWERED = Color(0xFFB46CFF)
/** Objectives that walk with the minion wave (MLBB Lord, Rift Herald, HoK Overlord / Tempest vanguards). */
private val PUSHERS = setOf("lord", "herald", "overlord", "shadow", "tempest")

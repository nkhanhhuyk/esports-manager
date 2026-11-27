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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.Fixture
import dev.uem.em.model.GameRecord
import dev.uem.em.model.World
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.ReplayCodec
import dev.uem.em.sim.Weapons
import dev.uem.em.titles.Catalogue
import kotlin.math.max
import kotlin.math.min

/**
 * Positional shooter replay: every player's recorded position, health and state each second,
 * utility (smokes, mollies, flashes, recon, walls, traps), kills with weapons, plants and defuses,
 * round by round on the map's real layout and routes (teleporters, drops, levels).
 */
@Composable
fun ShooterReplayViewer(w: World, f: Fixture, g: GameRecord, after: (@Composable () -> Unit)? = null) {
    val t = Catalogue.get(f.titleId)
    val map = Maps.shooter(g.mapId) ?: run { Card { Muted("No map data for ${g.mapId}.") }; return }
    val rp = g.replay ?: return
    val ev = remember(g) { ReplayCodec.events(rp, t) }
    val teams = arrayOf(w.teams[f.a], w.teams[f.b])
    val teamCol = arrayOf(Ui.blue, Ui.red)
    val rounds = remember(g) { ev.filter { it.kind == "round" } }
    // Round endings, buys and loadouts (box score); older box scores have no endings or ultimates.
    val econ = remember(g) { dev.uem.em.sim.ShooterEconomy.EconLog.parse(g.econ) }
    val cs = t.id == "cs2"
    val labelPaint = remember { android.graphics.Paint().apply { color = android.graphics.Color.WHITE; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true } }
    val ends = remember(g) { ev.filter { it.kind == "roundEnd" } }
    val dur = max(1, g.duration).toFloat()
    val startAt = remember(g) { ReplayJump.seconds.takeIf { it >= 0 }?.toFloat()?.also { ReplayJump.seconds = -1 } ?: (rounds.getOrNull((rounds.size * LiveDefaults.startFraction).toInt())?.t?.toFloat() ?: 0f) }
    var time by remember(g) { mutableFloatStateOf(startAt) }
    var playing by remember(g) { mutableStateOf(LiveDefaults.autoplay) }
    var speed by remember { mutableIntStateOf(2) }
    var names by remember { mutableStateOf(true) }
    var utilOn by remember { mutableStateOf(true) }
    var follow by remember(g) { mutableIntStateOf(-1) }
    LaunchedEffect(playing, speed, g) {
        var last = 0L
        while (playing && time < dur) { withFrameNanos { now -> if (last != 0L) time = (time + (now - last) / 1e9f * speed).coerceAtMost(dur); last = now } }
        if (time >= dur) playing = false
    }
    val T = time
    val Ti = T.toInt()
    val ri = rounds.indexOfLast { it.t <= Ti }.coerceAtLeast(0)
    val round = rounds.getOrNull(ri)
    val roundEnd = ends.firstOrNull { it.t >= (round?.t ?: 0) }
    val atkSide = round?.team ?: 0
    // Position samples carry their game second (posT); interpolate between neighbours of the same round.
    val ns = if (rp.posN > 0) rp.pos.size / rp.posN else 0
    var s0 = 0
    run { var lo = 0; var hi = ns - 1; while (lo <= hi) { val m = (lo + hi) / 2; if (rp.posT.getOrElse(m) { 0 } <= Ti) { s0 = m; lo = m + 1 } else hi = m - 1 } }
    val s1 = (s0 + 1).coerceAtMost(max(0, ns - 1))
    val t0 = rp.posT.getOrElse(s0) { 0 }; val t1 = rp.posT.getOrElse(s1) { 0 }
    val fr = if (t1 > t0 && t1 - t0 <= 2) ((T - t0) / (t1 - t0)).coerceIn(0f, 1f) else 0f
    val n = rp.pids.size
    fun unit(i: Int): FloatArray {
        val a = rp.pos.getOrElse(s0 * rp.posN + i) { 0 }; val b = rp.pos.getOrElse(s1 * rp.posN + i) { 0 }
        // Between two samples the player moved along the graph; when the straight line between them is not open
        // (a teleporter, a corner), the marker jumps instead of sliding through a wall.
        val open = dev.uem.em.sim.ShooterGrid.clear(map, MobaEngine.unitX(a) / 1000f, MobaEngine.unitY(a) / 1000f, MobaEngine.unitX(b) / 1000f, MobaEngine.unitY(b) / 1000f)
        val k = if (open) fr else if (fr < 0.5f) 0f else 1f
        return floatArrayOf((MobaEngine.unitX(a) + (MobaEngine.unitX(b) - MobaEngine.unitX(a)) * k) / 1000f, (MobaEngine.unitY(a) + (MobaEngine.unitY(b) - MobaEngine.unitY(a)) * k) / 1000f,
            MobaEngine.unitHp(if (k < 0.5f) a else b).toFloat(), MobaEngine.unitFlags(if (k < 0.5f) a else b).toFloat())
    }
    val units = Array(n) { unit(it) }
    val bomb = rp.pos.getOrElse(s0 * rp.posN + n) { 0 }
    val score = IntArray(2)
    for (e in ends) if (e.t <= Ti) { score[0] = e.a; score[1] = e.b }
    val inRound = ev.filter { round != null && it.t >= round.t && it.t <= (roundEnd?.t ?: Int.MAX_VALUE) && it.t <= Ti }
    val dead = HashSet<Int>().also { d -> inRound.filter { it.kind == "kill" }.forEach { d.add(it.b) } }
    val si = ReplayCodec.snapAt(rp, Ti)
    val sideOfPid = HashMap<Int, Int>().also { m -> g.lines.forEach { m[it.playerId] = it.team } }
    val followPos = rp.pids.indexOf(follow).takeIf { it >= 0 }?.let { Offset(units[it][0], units[it][1]) }
    val cam = remember(g) { MapCam() }
    // Broadcast presentation from the recorded rounds: HUD, kill feed, key moments and a director camera.
    var bc by remember { mutableStateOf(true) }
    var director by remember { mutableStateOf(true) }
    var slate by remember(g) { mutableStateOf(true) }
    val need = t.tactical?.roundsToWin ?: 13
    val feedAll = remember(g) { dev.uem.em.sim.Broadcast.shooterFeed(g, ev) }
    val moments = remember(g) { dev.uem.em.sim.Broadcast.shooterMoments(t, g, ev, need) }
    val shots = remember(g) { dev.uem.em.sim.Broadcast.director(moments, cooldown = 6, hold = 5) }
    val shot = if (bc && director && follow < 0) dev.uem.em.sim.Broadcast.shotAt(shots, Ti) else null
    val shotPos = shot?.moment?.player?.let { pid -> rp.pids.indexOf(pid).takeIf { it >= 0 }?.let { Offset(units[it][0], units[it][1]) } }
    LaunchedEffect(shot?.from) { if (shot != null && shotPos != null && cam.zoom < 1.5f) cam.zoom = 1.6f }
    cam.pinned = followPos ?: shotPos

    ViewerLayout(header = {
        if (bc) BroadcastUi.SeriesBar(w, f, g)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamPic(teams[0], 24.dp); Text(" ${teams[0].short}", color = teamCol[0], fontWeight = FontWeight.Bold)
            Text("${score[0]} – ${score[1]}", color = Ui.text, fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("${teams[1].short} ", color = teamCol[1], fontWeight = FontWeight.Bold); TeamPic(teams[1], 24.dp)
        }
        val rt = Ti - (round?.t ?: 0)
        val bombLeft = inRound.lastOrNull { it.kind == "plant" }?.let { (if (t.id == "cs2") 40 else 45) - (Ti - it.t) }
        Text("${map.name} · round ${ri + 1} · ${teams[atkSide].short} attacking (${round?.text?.split("|")?.getOrNull(0) ?: ""} vs ${round?.text?.split("|")?.getOrNull(1) ?: ""}) · ${clock(rt.coerceAtLeast(0))}" + (bombLeft?.let { " · ${if (t.id == "cs2") "bomb" else "spike"} ${max(0, it)}s" } ?: ""),
            color = Ui.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
        if (bc) dev.uem.em.sim.Broadcast.shooterRound(t, g, ev, Ti, need)?.let { BroadcastUi.RoundHud(w, t, f, it) { s -> teamCol[s.coerceIn(0, 1)] } }
        }, map = {
        MapFrame(cam, draw = { W ->
            fun p(o: Offset) = Offset(o.x * W, o.y * W)
            drawRect(Color(0xFF20262F), Offset.Zero, Size(W, W))
            for (r in map.walls) drawRect(Color(0xFF12161C), Offset(r[0] * W, r[1] * W), Size(r[2] * W, r[3] * W))
            for (l in map.links) {
                val a = p(map.nodes[l.from].pos); val b = p(map.nodes[l.to].pos)
                when (l.type) {
                    "teleport" -> drawLine(Color(0xAAB98CFF), a, b, strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                    "drop" -> drawLine(Color(0x88FFD166), a, b, strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 4f)))
                    "vent", "ladder", "stairs" -> drawLine(Color(0x8866D9EF), a, b, strokeWidth = 3f)
                    else -> drawLine(Color(0x33FFFFFF), a, b, strokeWidth = W * 0.012f)
                }
            }
            for (s in map.sites) drawCircle(Color(0x333B82F6), s.r * W, p(s.pos))
            // Site letters and spawns.
            drawContext.canvas.nativeCanvas.let { nc ->
                labelPaint.textSize = W * 0.055f; labelPaint.alpha = 230
                for (s in map.sites) nc.drawText(s.id, s.pos.x * W, s.pos.y * W + labelPaint.textSize * 0.35f, labelPaint)
                labelPaint.textSize = W * 0.026f; labelPaint.alpha = 170
                val sides = t.tactical!!.sides
                nc.drawText(if (cs) "T spawn" else "${sides.first} spawn", map.nodes[map.atk].pos.x * W, map.nodes[map.atk].pos.y * W - W * 0.025f, labelPaint)
                nc.drawText(if (cs) "CT spawn" else "${sides.second} spawn", map.nodes[map.def].pos.x * W, map.nodes[map.def].pos.y * W + W * 0.04f, labelPaint)
            }
            // Utility active now.
            if (utilOn) for (e in inRound) if (e.kind == "util" && Ti - e.t < (if (e.x == 0) e.m else 3)) {
                val at = Offset(MobaEngine.unpackX(e.n) / 1000f * W, MobaEngine.unpackY(e.n) / 1000f * W)
                val c = teamCol[e.team.coerceIn(0, 1)]
                when (e.x) {
                    0 -> { drawCircle(Color(0xCCB0B8C4), 0.035f * W, at); drawCircle(c, 0.035f * W, at, style = Stroke(2f)) }
                    2, 15 -> drawCircle(Color(0xAAFF7A33), 0.03f * W, at)
                    4 -> drawLine(Color(0xCC66D9EF), at - Offset(0.03f * W, 0f), at + Offset(0.03f * W, 0f), strokeWidth = 6f)
                    1, 6, 11, 12 -> drawCircle(Color.White.copy(alpha = 0.6f * (1f - (Ti - e.t) / 3f)), 0.06f * W, at, style = Stroke(4f))
                    3, 5 -> drawCircle(Color(0x8866D9EF), 0.12f * W, at, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                    else -> drawCircle(c.copy(alpha = 0.4f), 0.03f * W, at, style = Stroke(3f))
                }
            }
            if (bomb != 0) { val at = Offset(MobaEngine.unpackX(bomb and ((1 shl 20) - 1)) / 1000f * W, MobaEngine.unpackY(bomb) / 1000f * W); drawCircle(Color(0xFFFF5A6E), 0.012f * W, at); drawCircle(Color(0xFFFF5A6E), 0.025f * W, at, style = Stroke(3f)) }
            // Kills this round (last 4 s highlighted with a line from killer to victim).
            for (e in inRound) if (e.kind == "kill" && Ti - e.t in 0..4) {
                val ki = rp.pids.indexOf(e.a); val vi = rp.pids.indexOf(e.b)
                if (ki >= 0 && vi >= 0) drawLine(teamCol[(sideOfPid[e.a] ?: 0).coerceIn(0, 1)].copy(alpha = 0.8f), p(Offset(units[ki][0], units[ki][1])), p(Offset(units[vi][0], units[vi][1])), strokeWidth = 3f)
            }
        }) { side ->
            // Round result banner (first seconds after the round ends).
            ends.lastOrNull { it.t <= Ti && Ti - it.t <= 4 }?.let { e ->
                val ri2 = ends.indexOf(e)
                val rd = econ?.rounds?.getOrNull(ri2)
                val how = if (rd != null && rd.how >= 0) "${RoundIcons.icon(rd.how)} ${RoundIcons.label(cs, rd.how).lowercase()}"
                    else ev.filter { it.t > (rounds.getOrNull(ri2)?.t ?: 0) && it.t <= e.t }.let { r -> when { r.any { it.kind == "defuse" } -> "defuse"; r.any { it.kind == "plant" } && e.team == rounds.getOrNull(ri2)?.team -> "detonation"; else -> "elimination / time" } }
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.TopCenter) {
                    Text("${teams[e.team.coerceIn(0, 1)].short} win round ${ri2 + 1} · $how · ${e.a}–${e.b}", color = Ui.gold, fontWeight = FontWeight.Black, fontSize = 15.sp,
                        modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            if (bc) with(BroadcastUi) {
                val roundStart = round?.t ?: 0
                KillFeed(w, t, g, dev.uem.em.sim.Broadcast.feedAt(feedAll.filter { it.t >= roundStart }, Ti, keep = 20), { s -> teamCol[s.coerceIn(0, 1)] }) { k -> Weapons.of(t.id).getOrNull(k.x)?.name ?: "" }
                MomentBanner(shot?.moment, Ti, { teams[it.coerceIn(0, 1)].short }, { teamCol[it.coerceIn(0, 1)] })
                val cardPid = if (follow >= 0) follow else shot?.moment?.player ?: -1
                if (cardPid >= 0) {
                    val k = ev.count { it.kind == "kill" && it.a == cardPid && it.t <= Ti }; val d = ev.count { it.kind == "kill" && it.b == cardPid && it.t <= Ti }
                    PlayerCard(w, t, g, cardPid, "$k/$d · ${Weapons.of(t.id).getOrNull(ReplayCodec.value(rp, si, cardPid, 2))?.name ?: ""} · $${ReplayCodec.value(rp, si, cardPid, 0)}", teamCol[(sideOfPid[cardPid] ?: 0).coerceIn(0, 1)])
                }
                if (slate && T - startAt < 5f) TitleSlate(w, t, f, g, map.name) { slate = false }
            }
            for (i in 0 until n) {
                val pid = rp.pids[i]
                val l = g.lines.firstOrNull { it.playerId == pid } ?: continue
                val v = units[i]
                val flags = v[3].toInt()
                val isDead = flags and 1 != 0 || pid in dead
                val p = w.players[pid]
                Token(side, cam.view(Offset(v[0], v[1])), if (t.characters.isNotEmpty()) Pics.char(l.charId) else Pics.player(p), if (t.characters.isNotEmpty()) t.charIndex[l.charId]?.name ?: p.handle else p.handle,
                    p.handle + (if (flags and 2 != 0) " ✺" else "") + (if (flags and 4 != 0) " ⏳" else ""), teamCol[l.team.coerceIn(0, 1)], v[2] / 100f, null, isDead, follow == pid, names, size = 28.dp)
            }
        }
        }, rest = {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (time >= dur) { time = 0f; playing = true } else playing = !playing }) { Text(if (time >= dur) "Replay" else if (playing) "Pause" else "Play", color = Ui.accent) }
            for (sp in listOf(1, 2, 4, 8, 16)) TextButton(onClick = { speed = sp }) { Text("${sp}×", color = if (speed == sp) Ui.accent else Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { time = dur; playing = false }) { Text("Skip to end", color = Ui.muted, fontSize = 12.sp) }
            LiveHooks.onTimeout?.let { call ->
                val left = LiveHooks.timeoutsLeft
                val next = (round?.n?.takeIf { it >= 0 } ?: ri) + 1
                TextButton(enabled = left > 0 && time < dur && next < rounds.size, onClick = { playing = false; call(next, Ti) }) {
                    Text("Tactical timeout ($left left)", color = if (left > 0) Ui.gold else Ui.muted, fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { rounds.getOrNull(ri - 1)?.let { time = it.t.toFloat() } }) { Text("◀ round", color = Ui.muted, fontSize = 12.sp) }
            TextButton(onClick = { rounds.getOrNull(ri + 1)?.let { time = it.t.toFloat() } }) { Text("round ▶", color = Ui.accent, fontSize = 12.sp) }
            fun jump(k: Set<String>) { ev.firstOrNull { it.kind in k && it.t > Ti }?.let { time = (it.t - 3).toFloat().coerceAtLeast(0f); playing = false } }
            TextButton(onClick = { jump(setOf("kill")) }) { Text("kill ▶", color = Ui.accent, fontSize = 12.sp) }
            TextButton(onClick = { jump(setOf("util")) }) { Text("utility ▶", color = Ui.accent, fontSize = 12.sp) }
            TextButton(onClick = { jump(setOf("plant", "defuse")) }) { Text("plant / defuse ▶", color = Ui.accent, fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            Toggle("names", names) { names = it }; Toggle("utility", utilOn) { utilOn = it }
            Toggle("broadcast overlay", bc) { bc = it }; Toggle("director", director) { director = it }
            TextButton(onClick = { follow = -1; cam.reset() }) { Text("Reset view", color = Ui.muted, fontSize = 12.sp) }
        }
        Slider(value = time, onValueChange = { time = it; playing = false }, valueRange = 0f..dur, modifier = Modifier.padding(horizontal = 12.dp))
        Canvas(Modifier.fillMaxWidth().height(14.dp).padding(horizontal = 22.dp)) {
            val Wd = size.width
            for (e in ends) drawRect(teamCol[e.team.coerceIn(0, 1)], Offset(Wd * e.t / dur - 2f, 0f), Size(4f, size.height))
            drawLine(Color.White, Offset(Wd * T / dur, 0f), Offset(Wd * T / dur, size.height), strokeWidth = 2f)
        }
        if (bc) BroadcastUi.Bookmarks(moments, Ti, { teams[it.coerceIn(0, 1)].short }, { teamCol[it.coerceIn(0, 1)] }) { s -> time = (s - 3).coerceAtLeast(0).toFloat(); playing = false; slate = false }
        if (econ != null) Column(Modifier.padding(horizontal = 12.dp)) { RoundStrip(econ, cs, t.tactical?.half ?: 12, ri) { r -> rounds.getOrNull(r)?.let { time = it.t.toFloat(); playing = false } } }
        if (utilOn) Muted("  Utility on the map: grey disc smoke · white ring flash / concussion / blind · orange disc molly, incendiary or HE · blue line wall · dashed ring recon / trap", 9)
        if (time >= dur) after?.invoke()
        Card("Players", right = { Muted("tap to follow") }) {
            val rdNow = econ?.rounds?.getOrNull(ri)
            for (team in 0..1) for ((li, l) in g.lines.filter { it.team == team }.withIndex()) {
                val pid = l.playerId
                val kills = ev.count { it.kind == "kill" && it.a == pid && it.t <= Ti }
                val deaths = ev.count { it.kind == "kill" && it.b == pid && it.t <= Ti }
                val i = rp.pids.indexOf(pid)
                val wid = ReplayCodec.value(rp, si, pid, 2)
                Row(Modifier.fillMaxWidth().clickable { follow = if (follow == pid) -1 else pid; if (cam.zoom < 1.8f) cam.zoom = 2f }.background(if (follow == pid) Ui.card2 else Color.Transparent, RoundedCornerShape(6.dp)).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(teamCol[team], CircleShape)); HGap(6)
                    Text(w.players[pid].handle, color = if (pid in dead) Ui.muted else Ui.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Muted("${Weapons.of(t.id).getOrNull(wid)?.name ?: ""} · ${if (i >= 0) units[i][2].toInt() else 0} HP · $${ReplayCodec.value(rp, si, pid, 0)}", 11)
                    Text("  $kills/$deaths", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                }
                // This round's loadout: gun, shield / armour, abilities and ultimate or grenades and kit (used ones hollow), money left.
                val pe = rdNow?.players?.getOrNull(team)?.getOrNull(li)
                if (pe != null) {
                    val used = HashMap<String, Int>()
                    for (u in inRound) if (u.kind == "util" && u.a == pid) {
                        val kind = UTIL_KINDS.getOrElse(u.x) { "" }
                        val key = if (cs) kind else dev.uem.em.sim.TacticalEngine.valorantSlotOf(l.charId, kind) ?: continue
                        used.merge(key, 1, Int::plus)
                    }
                    val gun = Weapons.of(t.id).getOrNull(pe.weapon)?.name ?: ""
                    val kit = if (cs) csKit(pe.bits, used) else if (pe.ult >= 0) valorantKit(l.charId, pe.bits, pe.ult, used) else dev.uem.em.sim.ShooterEconomy.valUtilText(l.charId, pe.bits)
                    Muted("    $gun · ${dev.uem.em.sim.ShooterEconomy.armorName(cs, pe.armor)} · $kit · ${pe.money} left after buying", 10)
                }
            }
        }
        val talk = remember(g) { Commentary.shooter(w, t, ev, teams) }
        CommentaryCard(talk, Ti)
        Card("This round") {
            val feed = inRound.filter { it.kind in setOf("kill", "plant", "defuse", "util", "timeout") }.takeLast(12).reversed()
            if (feed.isEmpty()) Muted("Buy phase…")
            for (e in feed) Row(Modifier.padding(vertical = 1.dp)) {
                Text(clock((e.t - (round?.t ?: 0)).coerceAtLeast(0)), color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(40.dp))
                val c = teamCol[e.team.coerceIn(0, 1)]
                when (e.kind) {
                    "kill" -> Text("${w.players[e.a].handle} [${Weapons.of(t.id).getOrNull(e.x)?.name ?: ""}] ${w.players[e.b].handle}" + (if (e.n >= 0) " (assist ${w.players[e.n].handle})" else ""), color = c, fontSize = 12.sp)
                    "plant" -> Text("${w.players[e.a].handle} plants on ${map.sites.getOrNull(e.x)?.id ?: ""}", color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    "defuse" -> Text("${w.players[e.a].handle} defuses", color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    "util" -> Text("${w.players[e.a].handle}: ${UTIL_NAMES.getOrElse(e.x) { "utility" }}", color = c.copy(alpha = 0.8f), fontSize = 11.sp)
                    "timeout" -> Text("${teams[e.team.coerceIn(0, 1)].short} call a tactical timeout (${e.a} left)", color = Ui.gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Muted("  ${map.name} (node graph and walls: approximate layout). Positions, health, utility, kills, plants and defuses are the recorded round; replay speed never changes it.", 10)
        Spacer(Modifier.height(12.dp))
    })
}

/** Utility kinds in event order (sim.ShooterRound.KIND_CODES). */
private val UTIL_KINDS = listOf("smoke", "flash", "molly", "recon", "wall", "trap", "concuss", "slow", "heal", "mobility", "decoy", "nearsight", "suppress", "revive", "weapon", "he")
private val UTIL_NAMES = listOf("smoke", "flash", "molly", "recon", "wall", "trap", "concuss", "slow", "heal", "mobility", "decoy", "nearsight", "suppress", "revive", "weapon", "HE grenade")

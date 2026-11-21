package dev.uem.em.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.Fixture
import dev.uem.em.model.GameRecord
import dev.uem.em.model.World
import dev.uem.em.sim.Abilities
import dev.uem.em.sim.Ev
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.MobaMap
import dev.uem.em.sim.ReplayCodec
import dev.uem.em.sim.ReplayPos
import dev.uem.em.titles.TitleDef
import kotlin.math.abs
import kotlin.math.max

/**
 * Post-game analysis built only from the recorded game (replay v2): gold-lead timeline, key moments,
 * laning, teamfights, players' damage / healing / crowd control / vision, heatmaps and an
 * explanation separating what happened from the analyst's reading. Every moment opens the replay
 * at that second.
 */
object MobaAnalysis {
    class Moment(val t: Int, val text: String, val side: Int, val weight: Double)

    class Report(
        val times: IntArray, val lead: IntArray, val moments: List<Moment>, val lanes: List<String>, val fights: List<Ev>,
        val observed: List<Pair<String, Int>>, val opinion: List<String>, val cc: Map<Int, Int>, val wards: Map<Int, Int>, val casts: Map<Int, Int>,
    )

    fun report(w: World, f: Fixture, t: TitleDef, g: GameRecord): Report? {
        val rp = g.replay ?: return null
        if (rp.step <= 0) return null
        val ev = ReplayCodec.events(rp, t)
        val teams = intArrayOf(f.a, f.b)
        fun mapSide(seriesTeam: Int) = if (seriesTeam == g.blue) 0 else 1
        val names = arrayOf(w.teams[teams[g.blue]].short, w.teams[teams[1 - g.blue]].short)
        val n = rp.pids.size
        val sideOf = IntArray(n) { i -> g.lines.firstOrNull { it.playerId == rp.pids[i] }?.let { mapSide(it.team) } ?: (if (i < n / 2) 0 else 1) }
        // Gold lead (blue minus red) at every snapshot.
        val times = rp.snapT
        val lead = IntArray(times.size) { s -> var d = 0; for (i in 0 until n) d += ReplayCodec.value(rp, s, rp.pids[i], 0) * (if (sideOf[i] == 0) 1 else -1); d }
        val moments = ArrayList<Moment>()
        // Biggest 90-second gold swings.
        val win = max(1, 90 / max(1, if (times.size > 1) times[1] - times[0] else 10))
        val swings = ArrayList<Moment>()
        for (s in win until times.size) { val d = lead[s] - lead[s - win]; if (abs(d) > 1500) swings.add(Moment(times[s - win], "${names[if (d > 0) 0 else 1]} gain ${"%.1f".format(abs(d) / 1000.0)}k gold in 90 s", if (d > 0) 0 else 1, abs(d) / 1000.0)) }
        swings.sortByDescending { it.weight }
        val taken = ArrayList<Moment>()
        for (m in swings) if (taken.none { abs(it.t - m.t) < 120 }) taken.add(m)
        moments.addAll(taken.take(3))
        val fights = ev.filter { it.kind == "fight" }
        for (e in fights) moments.add(Moment(e.t, "Teamfight: ${names[mapSide(e.team)]} win it ${e.a}–${e.b}", mapSide(e.team), 2.0 + e.a + e.b))
        for (e in ev) when (e.kind) {
            "obj" -> moments.add(Moment(e.t, "${names[mapSide(e.team)]} take ${e.text}", mapSide(e.team), if (t.moba!!.objectives.getOrNull(e.x)?.major == true) 4.0 else 1.5))
            "steal" -> moments.add(Moment(e.t, "${w.players[e.a].handle} steals ${e.text}", mapSide(e.team), 5.0))
            "base" -> moments.add(Moment(e.t, "${names[mapSide(e.team)]} break into the base (${MobaMap.of(t.id).structs.getOrNull(e.n)?.name ?: "base structure"})", mapSide(e.team), 3.0))
            "core" -> moments.add(Moment(e.t, "${names[mapSide(e.team)]} destroy the ${t.moba!!.structures.coreName}", mapSide(e.team), 6.0))
        }
        // First blood.
        ev.firstOrNull { it.kind == "kill" && it.team >= 0 }?.let { moments.add(Moment(it.t, "First blood: ${w.players[it.a].handle} on ${w.players[it.b].handle}", mapSide(it.team), 1.0)) }
        moments.sortBy { it.t }
        // Laning: gold / CS / level difference between opposing players of each lane at the end of laning.
        val laneEnd = (t.moba!!.laneEnd * 60).toInt()
        val si = ReplayCodec.snapAt(rp, laneEnd)
        val lanes = ArrayList<String>()
        for ((name, roles) in t.moba.lanes) {
            val mine = g.lines.filter { mapSide(it.team) == 0 && it.roleId in roles }
            val theirs = g.lines.filter { mapSide(it.team) == 1 && it.roleId in roles }
            if (mine.isEmpty() || theirs.isEmpty()) continue
            val gd = mine.sumOf { ReplayCodec.value(rp, si, it.playerId, 0) } - theirs.sumOf { ReplayCodec.value(rp, si, it.playerId, 0) }
            val cd = mine.sumOf { ReplayCodec.value(rp, si, it.playerId, 1) } - theirs.sumOf { ReplayCodec.value(rp, si, it.playerId, 1) }
            val who = if (gd >= 0) names[0] else names[1]
            lanes.add("$name at ${laneEnd / 60}:00 — $who ahead by ${abs(gd)} gold, CS ${if (cd >= 0) "+" else ""}$cd for ${names[0]} (${mine.joinToString("/") { w.players[it.playerId].handle }} vs ${theirs.joinToString("/") { w.players[it.playerId].handle }})")
        }
        // Per-player crowd control applied, wards placed, abilities cast.
        val ccMask = (1 shl Abilities.STUN) or (1 shl Abilities.KNOCKUP) or (1 shl Abilities.KNOCKBACK) or (1 shl Abilities.SUPPRESS) or (1 shl Abilities.CHARM) or (1 shl Abilities.TAUNT) or (1 shl Abilities.FEAR) or (1 shl Abilities.ROOT) or (1 shl Abilities.SILENCE)
        val cc = HashMap<Int, Int>(); val wards = HashMap<Int, Int>(); val casts = HashMap<Int, Int>()
        for (e in ev) when (e.kind) {
            "cast" -> { casts.merge(e.a, 1, Int::plus); if (e.m and ccMask != 0 && e.b >= 0) cc.merge(e.a, 1, Int::plus) }
            "ward" -> wards.merge(e.a, 1, Int::plus)
        }
        // What happened (observed) and the analyst's reading (opinion).
        val winSide = mapSide(g.winner)
        val observed = ArrayList<Pair<String, Int>>()
        if (si >= 0) { val l = lead.getOrElse(si) { 0 }; observed.add("Gold at ${laneEnd / 60}:00: ${names[if (l >= 0) 0 else 1]} +${abs(l)}" to laneEnd) }
        val objs = IntArray(2).also { a -> ev.filter { it.kind == "obj" }.forEach { a[mapSide(it.team)]++ } }
        observed.add("Objectives ${names[0]} ${objs[0]} – ${objs[1]} ${names[1]}" to (ev.firstOrNull { it.kind == "obj" }?.t ?: 0))
        val fw = IntArray(2).also { a -> fights.forEach { a[mapSide(it.team)]++ } }
        if (fights.isNotEmpty()) observed.add("Teamfights won ${names[0]} ${fw[0]} – ${fw[1]} ${names[1]}" to fights.first().t)
        val structs = IntArray(2).also { a -> ev.filter { it.kind == "tower" || it.kind == "base" }.forEach { a[mapSide(it.team)]++ } }
        observed.add("Structures destroyed ${names[0]} ${structs[0]} – ${structs[1]} ${names[1]}" to (ev.firstOrNull { it.kind == "tower" }?.t ?: 0))
        val wardCount = IntArray(2).also { a -> for ((pid, c) in wards) { val i = rp.pids.indexOf(pid); if (i >= 0) a[sideOf[i]] += c } }
        observed.add("Wards placed ${names[0]} ${wardCount[0]} – ${wardCount[1]} ${names[1]}" to 0)
        val earlyKills = IntArray(2).also { a -> ev.filter { it.kind == "kill" && it.team >= 0 && it.t < laneEnd }.forEach { a[mapSide(it.team)]++ } }
        observed.add("Kills before ${laneEnd / 60}:00 ${names[0]} ${earlyKills[0]} – ${earlyKills[1]} ${names[1]}" to (ev.firstOrNull { it.kind == "kill" }?.t ?: 0))
        val ccSide = IntArray(2).also { a -> for ((pid, c) in cc) { val i = rp.pids.indexOf(pid); if (i >= 0) a[sideOf[i]] += c } }
        observed.add("Crowd control landed ${names[0]} ${ccSide[0]} – ${ccSide[1]} ${names[1]}" to 0)
        val opinion = ArrayList<String>()
        val wn = names[winSide]; val ln = names[1 - winSide]
        val leadAtEnd = lead.lastOrNull() ?: 0
        val maxDeficit = (if (winSide == 0) lead.minOrNull() else lead.maxOrNull()?.let { -it })?.let { -it } ?: 0
        if (maxDeficit > 3000) opinion.add("$wn came back from ${"%.1f".format(maxDeficit / 1000.0)}k gold down: look at the moments after the largest deficit.")
        if (objs[winSide] > objs[1 - winSide] + 1) opinion.add("Objective control decided it: $wn converted their fights into ${objs[winSide]} objectives.")
        if (fights.isNotEmpty() && fw[winSide] > fw[1 - winSide]) opinion.add("$wn were the better teamfighting side (${fw[winSide]} of ${fights.size} teamfights).")
        if (ccSide[winSide] > ccSide[1 - winSide] * 1.4) opinion.add("$wn's crowd control set up their fights (${ccSide[winSide]} landed vs ${ccSide[1 - winSide]}).")
        if (wardCount[1 - winSide] < wardCount[winSide] * 0.7) opinion.add("$ln had less vision (${wardCount[1 - winSide]} wards vs ${wardCount[winSide]}): ganks and objective setups went unseen.")
        if (earlyKills[1 - winSide] > earlyKills[winSide] + 2) opinion.add("$ln won the early game but could not turn it into structures — a macro problem to train.")
        if (opinion.isEmpty()) opinion.add("A close game: $wn finished ${"%.1f".format(abs(leadAtEnd) / 1000.0)}k gold ${if ((leadAtEnd >= 0) == (winSide == 0)) "ahead" else "behind"}; review the late teamfights.")
        return Report(times, lead, moments, lanes, fights, observed, opinion, cc, wards, casts)
    }
}

/** Analysis section of a game in the match report. */
@Composable
fun MobaAnalysisCard(vm: GameVm, w: World, f: Fixture, t: TitleDef, g: GameRecord, gi: Int) {
    val r = remember(g) { MobaAnalysis.report(w, f, t, g) }
    if (r == null) {
        Card("Analysis") { Muted(if (g.replay == null) "No replay was kept for this game, so there is no positional analysis." else "This game was recorded before positions were stored: timelines and heatmaps are unavailable.", 11) }
        return
    }
    val rp = g.replay!!
    fun watch(at: Int) { ReplayJump.seconds = max(0, at - 5); vm.go(Screen.Replay(f.id, gi)) }
    val blueName = w.teams[if (g.blue == 0) f.a else f.b].short
    val redName = w.teams[if (g.blue == 0) f.b else f.a].short
    Card("Gold lead", right = { Muted("tap the chart to watch that moment") }) {
        val maxAbs = max(1000, r.lead.maxOf { abs(it) })
        val dur = max(60, g.duration)
        Canvas(Modifier.fillMaxWidth().height(110.dp).pointerInput(g) { detectTapGestures { o -> watch((o.x / size.width * dur).toInt()) } }) {
            val W = size.width; val H = size.height
            drawLine(Color(0x55FFFFFF), Offset(0f, H / 2), Offset(W, H / 2))
            val path = Path()
            r.times.forEachIndexed { i, tt -> val x = W * tt / dur; val y = H / 2 - H / 2 * r.lead[i] / maxAbs.toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            drawPath(path, Ui.gold, style = Stroke(3f))
            for (m in r.moments) { val x = W * m.t / dur; drawCircle(if (m.side == 0) Ui.blue else Ui.red, 4f, Offset(x, if (m.side == 0) 6f else H - 6f)) }
        }
        Row { Muted("$blueName ahead ↑", 10); Box(Modifier.weight(1f)); Muted("↓ $redName ahead · max ${"%.1f".format(maxOf(1000, r.lead.maxOf { abs(it) }) / 1000.0)}k", 10) }
    }
    Card("Key moments") {
        for (m in r.moments.sortedByDescending { it.weight }.take(10).sortedBy { it.t }) Row(Modifier.fillMaxWidth().clickable { watch(m.t) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(clock(m.t), color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(40.dp))
            LinkedText(m.text, if (m.side == 0) Ui.blue else Ui.red, 12.sp, modifier = Modifier.weight(1f))
            Text("Watch ▶", color = Ui.accent, fontSize = 11.sp)
        }
    }
    Card("Why the game went this way") {
        Text("Observed", color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        for ((s, at) in r.observed) Row(Modifier.fillMaxWidth().clickable { watch(at) }.padding(vertical = 2.dp)) { Muted("• $s", 12); Box(Modifier.weight(1f)); if (at > 0) Text("▶", color = Ui.accent, fontSize = 11.sp) }
        Gap(4)
        Text("Analyst's reading (opinion)", color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        for (s in r.opinion) Muted("• $s", 12)
    }
    if (r.lanes.isNotEmpty()) Card("Laning") { for (s in r.lanes) Muted("• $s", 12) }
    if (r.fights.isNotEmpty()) Card("Teamfights") {
        for (e in r.fights) Row(Modifier.fillMaxWidth().clickable { watch(e.t) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(clock(e.t), color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(40.dp))
            val winner = if (e.team == g.blue) blueName else redName
            Text("$winner win ${e.a}–${e.b} · ${e.m}s · ${locName(t, e.x)}", color = Ui.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("Watch ▶", color = Ui.accent, fontSize = 11.sp)
        }
    }
    Card("Players: impact") {
        val dmgK = t.statIndex["dmg"]; val healK = t.statIndex["heal"]
        for (l in g.lines.sortedWith(compareBy({ it.team != g.blue }, { t.lineup.indexOf(it.roleId) }))) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            CharPic(l.charId, l.charId, 22.dp, round = true); HGap(6)
            Text(w.players[l.playerId].handle, color = if (l.team == g.blue) Ui.blue else Ui.red, fontSize = 12.sp, modifier = Modifier.width(90.dp))
            Muted("dmg ${k1(dmgK?.let { l.s[it] } ?: 0.0)} · heal ${k1(healK?.let { l.s[it] } ?: 0.0)} · CC ${r.cc[l.playerId] ?: 0} · casts ${r.casts[l.playerId] ?: 0} · wards ${r.wards[l.playerId] ?: 0}", 11)
        }
    }
    HeatmapCard(w, t, g, f)
}

private fun locName(t: TitleDef, code: Int) = when {
    code >= 10 -> t.moba!!.objectives.getOrNull(code - 10)?.name?.let { "at $it" } ?: "at an objective"
    code == 3 -> "jungle"
    else -> listOf("top lane", "mid lane", "bottom lane").getOrElse(code) { "map" }
}

/** Heatmaps from recorded positions / events: where a player or team spent time, died, warded, fought. */
@Composable
private fun HeatmapCard(w: World, t: TitleDef, g: GameRecord, f: Fixture) {
    val rp = g.replay ?: return
    var who by remember(g) { mutableIntStateOf(-1) } // -1 blue team, -2 red team, else player index
    var mode by remember(g) { mutableIntStateOf(0) }
    val map = remember(t.id) { MobaMap.of(t.id) }
    val ev = remember(g) { ReplayCodec.events(rp, t) }
    val n = rp.pids.size
    val sideOf = IntArray(n) { i -> g.lines.firstOrNull { it.playerId == rp.pids[i] }?.let { if (it.team == g.blue) 0 else 1 } ?: (if (i < n / 2) 0 else 1) }
    fun include(i: Int) = if (who >= 0) i == who else sideOf[i] == (if (who == -1) 0 else 1)
    val G = 25
    val grid = remember(g, who, mode) {
        val h = FloatArray(G * G)
        fun add(x: Float, y: Float, v: Float = 1f) { val cx = (x / 1000f * G).toInt().coerceIn(0, G - 1); val cy = (y / 1000f * G).toInt().coerceIn(0, G - 1); h[cy * G + cx] += v }
        when (mode) {
            0 -> for (s in 0 until ReplayPos.samples(rp)) for (i in 0 until n) if (include(i)) { val v = ReplayPos.unit(rp, s, i); if (MobaEngine.unitFlags(v) and 1 == 0) add(MobaEngine.unitX(v), MobaEngine.unitY(v)) }
            1 -> for (e in ev) if (e.kind == "kill") { val i = rp.pids.indexOf(e.b); if (i >= 0 && include(i)) { val v = ReplayPos.unit(rp, (e.t / rp.step).coerceAtMost(ReplayPos.samples(rp) - 1), i); add(MobaEngine.unitX(v), MobaEngine.unitY(v)) } }
            2 -> for (e in ev) if (e.kind == "ward") { val i = rp.pids.indexOf(e.a); if (i >= 0 && include(i)) add(MobaEngine.unpackX(e.n), MobaEngine.unpackY(e.n)) }
            else -> for (e in ev) if (e.kind == "cast") { val i = rp.pids.indexOf(e.a); if (i >= 0 && include(i)) add(MobaEngine.unpackX(e.n), MobaEngine.unpackY(e.n)) }
        }
        h
    }
    Card("Heatmap") {
        Tabs(listOf("Positions", "Deaths", "Wards", "Abilities used"), mode) { mode = it }
        val names = listOf(w.teams[if (g.blue == 0) f.a else f.b].short + " team", w.teams[if (g.blue == 0) f.b else f.a].short + " team") + rp.pids.map { w.players[it].handle }
        Tabs(names, if (who == -1) 0 else if (who == -2) 1 else who + 2) { who = if (it == 0) -1 else if (it == 1) -2 else it - 2 }
        val mx = grid.maxOrNull() ?: 0f
        if (mx <= 0f) Muted("Nothing recorded for this selection.", 11)
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(4.dp)) {
            val W = size.width; val u = W / 1000f
            drawRect(Color(0xFF1C3524), Offset.Zero, Size(W, W))
            for (r in map.walls) drawRect(Color(0xFF10201A), Offset(r[0] * u, r[1] * u), Size(r[2] * u, r[3] * u))
            for (l in map.lanes.values) { var f0 = 0f; while (f0 < 1f) { drawLine(Color(0x668A7A55), Offset(l.x(f0) * u, l.y(f0) * u), Offset(l.x(f0 + 0.02f) * u, l.y(f0 + 0.02f) * u), strokeWidth = W * 0.01f); f0 += 0.02f } }
            val c = W / G
            if (mx > 0f) for (i in 0 until G * G) { val v = grid[i] / mx; if (v <= 0.01f) continue
                drawRect(Color(1f, (1f - v).coerceIn(0f, 1f) * 0.8f, 0.1f, 0.15f + 0.6f * v), Offset((i % G) * c, (i / G) * c), Size(c, c)) }
        }
        Muted("From the recorded positions (every ${rp.step} s) and events of this game.", 10)
    }
}

/** Shooter analysis from the recorded rounds (positional replays). */
@Composable
fun ShooterAnalysisCard(vm: GameVm, w: World, f: Fixture, t: TitleDef, g: GameRecord, gi: Int) {
    val rp = g.replay
    if (rp == null || rp.step <= 0) { Card("Analysis") { Muted(if (rp == null) "No replay was kept for this map, so there is no round analysis." else "This map was recorded before positions were stored: round analysis is unavailable.", 11) }; return }
    val ev = remember(g) { ReplayCodec.events(rp, t) }
    val names = arrayOf(w.teams[f.a].short, w.teams[f.b].short)
    val rounds = ev.filter { it.kind == "round" }
    val ends = ev.filter { it.kind == "roundEnd" }
    fun watch(at: Int) { ReplayJump.seconds = max(0, at); vm.go(Screen.Replay(f.id, gi)) }
    val sideOf = HashMap<Int, Int>().also { m -> g.lines.forEach { m[it.playerId] = it.team } }
    data class R(val i: Int, val start: Int, val atk: Int, val buys: String, val winner: Int, val how: String, val first: dev.uem.em.sim.Ev?, val site: Int)
    val rows = rounds.mapIndexed { i, r ->
        val end = ends.getOrNull(i)
        val inR = ev.filter { it.t >= r.t && it.t <= (end?.t ?: Int.MAX_VALUE) }
        val planted = inR.any { it.kind == "plant" }; val defused = inR.any { it.kind == "defuse" }
        val win = end?.team ?: -1
        val how = when { defused -> "defuse"; planted && win == r.team -> "detonation / elimination"; win == r.team -> "elimination"; planted -> "retake"; inR.count { it.kind == "kill" && it.team == r.team } >= 5 -> "elimination"; else -> "held / time" }
        R(i, r.t, r.team, r.text, win, how, inR.firstOrNull { it.kind == "kill" }, inR.firstOrNull { it.kind == "plant" }?.x ?: -1)
    }
    Card("Rounds", right = { Muted("tap a round to watch it") }) {
        for (r in rows) Row(Modifier.fillMaxWidth().clickable { watch(r.start) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${r.i + 1}", color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(22.dp))
            Box(Modifier.width(8.dp).height(8.dp).padding(0.dp)) { Canvas(Modifier.fillMaxWidth().height(8.dp)) { drawCircle(if (r.winner == 0) Ui.blue else Ui.red, size.minDimension / 2) } }
            HGap(6)
            Text("${names[r.atk.coerceIn(0, 1)]} attack · ${r.buys.replace("|", " vs ")} · ${names.getOrElse(r.winner) { "?" }} by ${r.how}" + (r.first?.let { " · opening ${w.players[it.a].handle}" } ?: ""), color = Ui.text, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("▶", color = Ui.accent, fontSize = 11.sp)
        }
    }
    // Opening duels, trades, side and site results.
    val fk = HashMap<Int, Int>(); val fd = HashMap<Int, Int>()
    for (r in rows) r.first?.let { fk.merge(it.a, 1, Int::plus); fd.merge(it.b, 1, Int::plus) }
    var trades = IntArray(2)
    val kills = ev.filter { it.kind == "kill" }
    for ((k, e) in kills.withIndex()) {
        val prev = kills.subList(maxOf(0, k - 3), k).lastOrNull { it.b != e.b && it.a == e.b && e.t - it.t <= 5 }
        if (prev != null) trades[(sideOf[e.a] ?: 0).coerceIn(0, 1)]++
    }
    val atkWins = IntArray(2); val atkRounds = IntArray(2); val defWins = IntArray(2); val defRounds = IntArray(2)
    for (r in rows) { val a = r.atk.coerceIn(0, 1); atkRounds[a]++; defRounds[1 - a]++; if (r.winner == a) atkWins[a]++ else if (r.winner == 1 - a) defWins[1 - a]++ }
    Card("Duels, trades and sides") {
        for (team in 0..1) Muted("${names[team]}: attack ${atkWins[team]}/${atkRounds[team]} · defence ${defWins[team]}/${defRounds[team]} · opening kills ${g.lines.filter { it.team == team }.sumOf { fk[it.playerId] ?: 0 }} · traded kills ${trades[team]}", 12)
        Gap(4)
        for (l in g.lines.sortedBy { it.team }) Muted("${w.players[l.playerId].handle} (${names[l.team]}): opening ${fk[l.playerId] ?: 0}–${fd[l.playerId] ?: 0}", 11)
        val sites = Maps.shooter(g.mapId)?.sites ?: emptyList()
        if (sites.isNotEmpty()) { Gap(4); for ((i, s) in sites.withIndex()) { val pl = rows.filter { it.site == i }; if (pl.isNotEmpty()) Muted("Site ${s.id}: ${pl.size} plants, ${pl.count { it.winner == it.atk }} converted", 11) } }
    }
    Card("Why the map went this way") {
        val win = g.winner
        Text("Observed", color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Muted("• Pistol rounds: " + rows.filter { it.buys.startsWith("pistol") }.joinToString { "${names.getOrElse(it.winner) { "?" }} (round ${it.i + 1})" }, 12)
        Muted("• Opening kills ${names[0]} ${g.lines.filter { it.team == 0 }.sumOf { fk[it.playerId] ?: 0 }} – ${g.lines.filter { it.team == 1 }.sumOf { fk[it.playerId] ?: 0 }} ${names[1]}", 12)
        Muted("• Eco / force rounds won: " + (0..1).joinToString { s -> "${names[s]} ${rows.count { r -> r.winner == s && (r.buys.split("|").getOrNull(if (r.atk == s) 0 else 1) in setOf("eco", "force")) }}" }, 12)
        Gap(4)
        Text("Analyst's reading (opinion)", color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        val op = ArrayList<String>()
        val fkw = g.lines.filter { it.team == win }.sumOf { fk[it.playerId] ?: 0 }; val fkl = g.lines.filter { it.team == 1 - win }.sumOf { fk[it.playerId] ?: 0 }
        if (fkw > fkl + 2) op.add("${names[win]} won the opening duels (${fkw}–${fkl}) and played most rounds with a player advantage.")
        if (trades[win] > trades[1 - win] + 2) op.add("${names[win]} traded better (${trades[win]} vs ${trades[1 - win]}): their entries were followed up.")
        if (atkWins[win] * defRounds[win] > defWins[win] * atkRounds[win]) op.add("${names[win]}'s attack carried the map.") else op.add("${names[win]}'s defence carried the map.")
        for (s in op) Muted("• $s", 12)
    }
}

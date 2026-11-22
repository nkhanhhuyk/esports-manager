package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.Fixture
import dev.uem.em.model.GameRecord
import dev.uem.em.model.World
import dev.uem.em.sim.Broadcast
import dev.uem.em.titles.TitleDef

/**
 * Broadcast presentation pieces for the 2D viewers. Everything shown is read from the game's
 * recorded events (see [Broadcast]); none of it changes the match.
 */
object BroadcastUi {
    private val SCRIM = Color(0xCC0B0F14)

    /** The series so far for this game: (game number, wins of the series' first and second team before it), or null if unknown. */
    fun seriesContext(w: World, f: Fixture, g: GameRecord): Pair<Int, IntArray>? {
        val games = dev.uem.em.game.BoxStore.get(w, f.id)?.games?.takeIf { gs -> gs.any { it === g } } ?: LiveHooks.seriesGames?.takeIf { gs -> gs.any { it === g } }
        val list = games ?: return null
        val idx = list.indexOfFirst { it === g }
        val wins = IntArray(2)
        for (k in 0 until idx) list[k].winner.takeIf { it in 0..1 }?.let { wins[it]++ }
        return idx + 1 to wins
    }

    /** Top bar: competition, game number and the series score (before this game). */
    @Composable
    fun SeriesBar(w: World, f: Fixture, g: GameRecord) {
        val ctx = seriesContext(w, f, g)
        val c = w.comps[f.compId]
        val stage = c.stages.getOrNull(f.stageIdx)?.name.orEmpty()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).background(Ui.card2, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.short.ifBlank { c.name }, color = Ui.gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (stage.isNotBlank()) Text(" · $stage", color = Ui.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Box(Modifier.weight(1f))
            if (ctx != null) {
                val need = f.bestOf / 2 + 1
                val point = (0..1).filter { ctx.second[it] == need - 1 }
                Text("Game ${ctx.first} · Bo${f.bestOf}  ", color = Ui.text, fontSize = 11.sp)
                Text("${w.teams[f.a].short} ${ctx.second[0]}–${ctx.second[1]} ${w.teams[f.b].short}", color = Ui.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (point.isNotEmpty() && f.bestOf > 1) Text(if (point.size == 2) "  decider" else "  series point ${w.teams[if (point[0] == 0) f.a else f.b].short}", color = Ui.loss, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            } else Text("Bo${f.bestOf}", color = Ui.muted, fontSize = 11.sp)
        }
    }

    /** The opening slate over the map: event, teams, game, patch and map. */
    @Composable
    fun BoxScope.TitleSlate(w: World, t: TitleDef, f: Fixture, g: GameRecord, mapName: String, onClose: () -> Unit) {
        val ctx = seriesContext(w, f, g)
        val c = w.comps[f.compId]
        val patch = w.patches.lastOrNull { it.titleId == t.id && it.day <= f.day }?.version
        Column(Modifier.align(Alignment.Center).padding(16.dp).background(SCRIM, RoundedCornerShape(12.dp)).clickable { onClose() }.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(c.name, color = Ui.gold, fontWeight = FontWeight.Bold, fontSize = 13.sp, textAlign = TextAlign.Center)
            c.stages.getOrNull(f.stageIdx)?.name?.takeIf { it.isNotBlank() }?.let { Text(it, color = Ui.muted, fontSize = 11.sp) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                TeamPic(w.teams[f.a], 34.dp); Text("  ${w.teams[f.a].short}", color = Ui.text, fontWeight = FontWeight.Black, fontSize = 18.sp)
                Text("  vs  ", color = Ui.muted, fontSize = 13.sp)
                Text("${w.teams[f.b].short}  ", color = Ui.text, fontWeight = FontWeight.Black, fontSize = 18.sp); TeamPic(w.teams[f.b], 34.dp)
            }
            Text(listOfNotNull(ctx?.let { "Game ${it.first} of Bo${f.bestOf} · series ${it.second[0]}–${it.second[1]}" } ?: "Bo${f.bestOf}", mapName, patch?.let { "patch $it" }).joinToString(" · "), color = Ui.text, fontSize = 11.sp, textAlign = TextAlign.Center)
            Text("tap to close", color = Ui.muted, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }

    /** Kill feed in the map's top-right corner. [colOf] gives the colour of a series team. */
    @Composable
    fun BoxScope.KillFeed(w: World, t: TitleDef, g: GameRecord, items: List<Broadcast.Feed>, colOf: (Int) -> Color, weapon: ((Broadcast.Feed) -> String)? = null) {
        if (items.isEmpty()) return
        Column(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalAlignment = Alignment.End) {
            for (k in items) Row(Modifier.padding(vertical = 1.dp).background(SCRIM, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                val kl = g.lines.firstOrNull { it.playerId == k.killer }; val vl = g.lines.firstOrNull { it.playerId == k.victim }
                if (k.killer >= 0) {
                    if (t.characters.isNotEmpty() && kl != null) { CharPic(kl.charId, kl.charId, 14.dp, round = true, tap = false); HGap(2) }
                    Text(w.players[k.killer].handle, color = colOf(k.team), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                } else Text("executed", color = Ui.muted, fontSize = 9.sp)
                Text(weapon?.invoke(k)?.let { " [$it] " } ?: (if (k.assists > 0) " +${k.assists} › " else " › "), color = Ui.muted, fontSize = 9.sp)
                if (t.characters.isNotEmpty() && vl != null) { CharPic(vl.charId, vl.charId, 14.dp, round = true, tap = false); HGap(2) }
                Text(if (k.victim >= 0) w.players[k.victim].handle else "", color = colOf(1 - k.team.coerceIn(0, 1)).copy(alpha = 0.85f), fontSize = 9.sp, maxLines = 1)
                if (k.tag.isNotEmpty() && k.tag != "executed") Text(" ${k.tag}", color = Ui.gold, fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
        }
    }

    /** Objective timers in the bottom-left corner. */
    @Composable
    fun BoxScope.Timers(timers: List<Broadcast.Timer>, ti: Int) {
        if (timers.isEmpty()) return
        Column(Modifier.align(Alignment.BottomStart).padding(4.dp).background(SCRIM, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp)) {
            for (tm in timers) Text("${tm.label}  ${if (tm.alive) "up" else clock((tm.nextAt - ti).coerceAtLeast(0))}", color = if (tm.alive) Color(0xFFB98CFF) else Ui.text, fontSize = 9.sp, fontWeight = if (tm.alive) FontWeight.Bold else FontWeight.Normal)
        }
    }

    /** A big moment's banner (first seconds after it). */
    @Composable
    fun BoxScope.MomentBanner(m: Broadcast.Moment?, ti: Int, teamName: (Int) -> String, colOf: (Int) -> Color) {
        if (m == null || ti - m.t !in 0..3 || m.priority < 5) return
        Text("${if (m.team >= 0) teamName(m.team) + " · " else ""}${m.text}", color = if (m.team >= 0) colOf(m.team) else Ui.gold, fontWeight = FontWeight.Black, fontSize = 15.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp).background(SCRIM, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
    }

    /** A player card at the bottom of the map: the player in the shot or the one followed. */
    @Composable
    fun BoxScope.PlayerCard(w: World, t: TitleDef, g: GameRecord, pid: Int, line: String, col: Color) {
        val l = g.lines.firstOrNull { it.playerId == pid } ?: return
        val p = w.players[pid]
        Row(Modifier.align(Alignment.BottomEnd).padding(4.dp).background(SCRIM, RoundedCornerShape(8.dp)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (t.characters.isNotEmpty()) CharPic(l.charId, l.charId, 26.dp, round = true, tap = false) else PlayerPic(p, 26.dp)
            HGap(5)
            Column {
                Text(p.handle, color = col, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("${t.role(l.roleId).short}${if (t.characters.isNotEmpty()) " · " + (t.charIndex[l.charId]?.name ?: l.charId) else ""}", color = Ui.muted, fontSize = 9.sp)
                Text(line, color = Ui.text, fontSize = 9.sp)
            }
        }
    }

    /** Key moments of the game as bookmarks; tapping one seeks the replay a few seconds before it. */
    @Composable
    fun Bookmarks(moments: List<Broadcast.Moment>, ti: Int, teamName: (Int) -> String, colOf: (Int) -> Color, seek: (Int) -> Unit) {
        val shown = moments.filter { it.priority >= 4 }
        if (shown.isEmpty()) return
        Card("Key moments", right = { Muted("${shown.size} · tap to jump", 10) }) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (m in shown) Column(Modifier.background(if (m.t <= ti) Ui.card2 else Ui.card, RoundedCornerShape(6.dp)).clickable { seek(m.t) }.padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Text(clock(m.t.coerceAtLeast(0)), color = Ui.muted, fontSize = 9.sp)
                    Text(m.text, color = if (m.team >= 0) colOf(m.team) else Ui.gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (m.team >= 0) Text(teamName(m.team), color = Ui.muted, fontSize = 9.sp)
                }
            }
        }
    }

    /** Shooter round HUD: round, sides, buys, players alive, economy, phase and the bomb / spike timer. */
    @Composable
    fun RoundHud(w: World, t: TitleDef, f: Fixture, r: Broadcast.Round, colOf: (Int) -> Color) {
        val teams = arrayOf(w.teams[f.a], w.teams[f.b])
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).background(Ui.card2, RoundedCornerShape(6.dp)).padding(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (s in 0..1) {
                    Column(Modifier.weight(1f), horizontalAlignment = if (s == 0) Alignment.Start else Alignment.End) {
                        Text("${teams[s].short} · ${if (s == r.attack) "attack" else "defence"}", color = colOf(s), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row { for (k in 0 until 5) Box(Modifier.padding(1.dp).size(9.dp).background(if (k < r.alive[s]) colOf(s) else Ui.card, CircleShape)) }
                        Text("${(if (s == r.attack) r.buys.first else r.buys.second).ifBlank { "—" }} · $${r.credits[s]} bank · $${r.loadout[s]} loadout", color = Ui.muted, fontSize = 9.sp)
                    }
                    if (s == 0) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                        Text("Round ${r.index + 1}", color = Ui.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("${r.score[0]} – ${r.score[1]}", color = Ui.text, fontSize = 16.sp, fontWeight = FontWeight.Black)
                        val phase = when (r.phase) { "buy" -> "BUY PHASE"; "post-plant" -> if (r.defused) "DEFUSED" else "${if (t.id == "cs2") "BOMB" else "SPIKE"} ${r.bombLeft}s"; "ended" -> "round over"; else -> clock(r.clock) }
                        Text(phase, color = if (r.phase == "post-plant") Ui.loss else if (r.phase == "buy") Ui.gold else Ui.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        if (r.matchPoint >= 0) Text(if (r.matchPoint == 2) "MATCH POINT ×2" else "MATCH POINT ${teams[r.matchPoint].short}", color = Ui.loss, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }

    @Composable
    fun Shell(content: @Composable BoxScope.() -> Unit) = Box(Modifier.fillMaxSize(), content = content)
}

package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.Dates
import dev.uem.em.game.BoxStore
import dev.uem.em.model.Fixture
import dev.uem.em.model.GameRecord
import dev.uem.em.model.Line
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.TitleDef
import kotlin.math.pow

/** Where a fixture sits in its competition (stage and round / group). */
internal fun fixtureLabel(w: World, f: Fixture): String {
    val c = w.comps[f.compId]
    val st = c.stages.getOrNull(f.stageIdx)
    val node = st?.bracket?.firstOrNull { it.fixtureId == f.id }
    return listOfNotNull(st?.name, node?.label ?: if (f.group >= 0 && (st?.standings?.map { it.group }?.distinct()?.size ?: 0) > 1) "Group ${'A' + f.group}" else null, if (node == null && f.round >= 0) "round ${f.round + 1}" else null).joinToString(" · ")
}

private fun lastResults(w: World, teamId: Int, beforeDay: Int, n: Int = 5) =
    w.fixtures.filter { it.done && it.day < beforeDay && (it.a == teamId || it.b == teamId) }.sortedByDescending { it.day }.take(n)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MatchScreen(vm: GameVm, w: World, id: Int) {
    val f = w.fixtures[id]
    val t = Catalogue.get(f.titleId)
    val c = w.comps[f.compId]
    val rec = remember(id, vm.version) { BoxStore.get(w, id) }
    var gi by remember(id) { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.go(Screen.Comp(c.id)) }) {
                    TitleTag(t.id)
                    Text(c.name, color = Ui.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                val fm = if (dev.uem.em.comp.Fearless.applies(t)) dev.uem.em.comp.Fearless.effective(t, dev.uem.em.comp.Fearless.modeOf(c)) else "none"
                Muted("${fixtureLabel(w, f)} · ${Dates.format(f.day, true)} · Bo${f.bestOf}${if (fm != "none") " · " + dev.uem.em.comp.Fearless.LABEL[fm] else ""}", 12)
                Gap(10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (side in 0..1) {
                        val tid = if (side == 0) f.a else f.b
                        if (side == 1) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                            if (f.done) Text("${f.scoreA} – ${f.scoreB}", color = Ui.text, fontWeight = FontWeight.Black, fontSize = 28.sp)
                            else Text("vs", color = Ui.muted, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                            Muted(if (f.done) "final" else Dates.short(f.day), 11)
                        }
                        Column(Modifier.weight(1f).clickable(enabled = tid >= 0) { vm.go(Screen.Team(tid)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            if (tid >= 0) {
                                val tm = w.teams[tid]
                                TeamPic(tm, 48.dp)
                                Text(tm.name, color = if (f.done && f.winner == tid) Ui.gold else if (w.isUserTeam(tid)) Ui.accent else Ui.text, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
                                Muted("str %.1f · Elo ${tm.elo.toInt()}".format(Lineups.strength(w, tm)), 10)
                            } else Muted("TBD")
                        }
                    }
                }
                if (f.mvp >= 0) {
                    Gap(8)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Series MVP  ", color = Ui.gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        PlayerChip(w.players[f.mvp], vm, 22.dp)
                    }
                }
            }
        }
        if (!f.done) item { MatchPreview(vm, w, f, t) }
        else if (rec == null) item { Card { Muted("Box scores are not available for this match (they are kept for every match from now on; very old weekly pages may have been trimmed).") } }
        else {
            item {
                Tabs(rec.games.mapIndexed { i, g -> "${if (t.isMoba) "Game" else "Map"} ${i + 1} · ${w.teams[if (g.winner == 0) f.a else f.b].short}" } + "Series", gi) { gi = it }
            }
            if (gi < rec.games.size) item { GameCard(vm, w, f, t, rec.games[gi], gi) }
            else item { SeriesTotals(vm, w, f, t, rec.games) }
        }
        item { Gap(16) }
    }
}

@Composable
private fun MatchPreview(vm: GameVm, w: World, f: Fixture, t: TitleDef) {
    if (f.a < 0 || f.b < 0) { Card("Preview") { Muted("Teams not decided yet.") }; return }
    val ta = w.teams[f.a]
    val tb = w.teams[f.b]
    val pa = 1 / (1 + 10.0.pow((tb.elo - ta.elo) / 400))
    val mine = w.isUserTeam(f.a) || w.isUserTeam(f.b)
    Card("Preview") {
        WatchButton(vm, w, f)
        Muted(if (mine) "Your match: you make the veto / draft calls, then each game plays on the 2D map." else "Watch it live: both coaches draft (shown step by step), then each game plays on the 2D map. Watching does not change the result.", 11)
        Gap(6)
        Text("Win chance (Elo)", color = Ui.muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().height(16.dp).padding(vertical = 3.dp)) {
            Box(Modifier.weight(pa.toFloat().coerceIn(0.02f, 0.98f)).height(10.dp).background(Ui.blue, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)))
            Box(Modifier.weight((1 - pa).toFloat().coerceIn(0.02f, 0.98f)).height(10.dp).background(Ui.red, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
        }
        Row { Text("${ta.short} ${(pa * 100).toInt()}%", color = Ui.blue, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("${(100 - pa * 100).toInt()}% ${tb.short}", color = Ui.red, fontSize = 12.sp) }
        Gap(6)
        val h2h = w.fixtures.filter { it.done && ((it.a == f.a && it.b == f.b) || (it.a == f.b && it.b == f.a)) }
        KV("Head to head", if (h2h.isEmpty()) "first meeting" else "${ta.short} ${h2h.count { it.winner == f.a }} – ${h2h.count { it.winner == f.b }} ${tb.short}")
        for (tid in listOf(f.a, f.b)) KV("Form ${w.teams[tid].short}") {
            Row { for (r in lastResults(w, tid, f.day).reversed()) Text(if (r.winner == tid) "W" else "L", color = if (r.winner == tid) Ui.win else Ui.loss, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp)) }
        }
        dev.uem.em.mgmt.Deals.rivalsOf(w, f.a).firstOrNull { it.first == f.b }?.let { KV("Rivalry", "intensity ${it.second.toInt()}", Ui.loss) }
    }
    ScoutingCard(vm, w, f, t)
    Card("Expected lineups") {
        Row {
            for (tm in listOf(ta, tb)) Column(Modifier.weight(1f).padding(end = 6.dp)) {
                Text(tm.short, color = if (tm.id == f.a) Ui.blue else Ui.red, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                tm.lineup.forEachIndexed { i, pid ->
                    if (pid < 0) return@forEachIndexed
                    val p = w.players[pid]
                    Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Player(pid)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(t.lineupRoles.getOrNull(i)?.short ?: "", color = Ui.muted, fontSize = 10.sp, modifier = Modifier.width(30.dp))
                        PlayerPic(p, 20.dp, round = true); HGap(4)
                        Text(p.handle, color = Ui.text, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Rating(p.ca, size = 11)
                    }
                }
            }
        }
    }
}

private fun stat(t: TitleDef, l: Line, k: String) = t.statIndex[k]?.let { l.s.getOrElse(it) { 0.0 } } ?: 0.0

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameCard(vm: GameVm, w: World, f: Fixture, t: TitleDef, g: GameRecord, gi: Int) {
    val teams = intArrayOf(f.a, f.b)
    val sideNames = if (t.id == "dota2") "Radiant" to "Dire" else if (t.isMoba) "Blue" to "Red" else t.tactical!!.sides
    val order = if (t.isMoba && g.blue == 1) intArrayOf(1, 0) else intArrayOf(0, 1)
    val mvp = g.lines.filter { it.team == g.winner }.maxByOrNull { it.rating }
    val kills = IntArray(2).also { a -> g.lines.forEach { a[it.team] += stat(t, it, "k").toInt() } }
    Card("${if (t.isMoba) "Game" else "Map"} ${gi + 1}${if (!t.isMoba) " — ${t.maps.firstOrNull { it.id == g.mapId }?.name ?: g.mapId}" else ""}", right = { Muted(if (t.isMoba) "%d:%02d".format(g.duration / 60, g.duration % 60) else "${g.score.getOrElse(0) { 0 }}–${g.score.getOrElse(1) { 0 }} rounds") }) {
        Text(
            if (t.isMoba) "${w.teams[teams[g.winner]].name} win in %d:%02d, kills ${kills[g.winner]}–${kills[1 - g.winner]}".format(g.duration / 60, g.duration % 60)
            else "${w.teams[teams[g.winner]].name} take ${t.maps.firstOrNull { it.id == g.mapId }?.name ?: "the map"} ${maxOf(g.score.getOrElse(0) { 0 }, g.score.getOrElse(1) { 0 })}–${minOf(g.score.getOrElse(0) { 0 }, g.score.getOrElse(1) { 0 })}",
            color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        )
        if (mvp != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.go(Screen.Player(mvp.playerId)) }.padding(top = 4.dp)) {
            Text("Game MVP  ", color = Ui.gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (mvp.charId.isNotEmpty()) { CharPic(mvp.charId, mvp.charId, 20.dp, round = true); HGap(4) }
            Text("${w.players[mvp.playerId].handle}${t.charIndex[mvp.charId]?.let { " (${it.name})" } ?: ""} · %.2f".format(mvp.rating), color = Ui.text, fontSize = 12.sp)
        }
        Gap(4)
        if (g.replay != null) Button(onClick = { vm.go(Screen.Replay(f.id, gi)) }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text("Watch 2D replay") }
        else Muted("No 2D replay was kept for this game (Settings → Replays chooses which matches keep one).", 11)
        if (t.isMoba && g.sel.size >= 3) {
            val firstPick = g.sel.getOrElse(3) { if (t.id in dev.uem.em.sim.LiveSeries.SPLIT_SELECTION) -1 else g.blue }
            Gap(4)
            Muted(selectionText(t, listOf(w.teams[f.a].short, w.teams[f.b].short), g.sel[0], g.sel[1], g.sel[2] == 1, g.blue, firstPick), 12)
        }
    }
    if (t.characters.isNotEmpty() && (g.bans.isNotEmpty() || g.lines.any { it.charId.isNotEmpty() })) Card("Draft") {
        val fm = dev.uem.em.comp.Fearless.played(t, w.comps[f.compId])
        val earlier = BoxStore.get(w, f.id)?.games?.take(gi) ?: emptyList()
        if (fm != "none" && earlier.isNotEmpty()) {
            val played = Array(2) { side -> earlier.flatMap { e -> e.lines.filter { it.team == side }.map { it.charId } }.filter { it.isNotEmpty() }.toSet() }
            val banned = earlier.flatMap { it.bans }.toSet()
            val rows = when (fm) {
                "soft" -> listOf(w.teams[f.a].short to played[0], w.teams[f.b].short to played[1])
                "insane" -> listOf("Both" to (played[0] + played[1] + banned))
                else -> listOf("Both" to (played[0] + played[1]))
            }
            Text("${dev.uem.em.comp.Fearless.LABEL[fm]} — unavailable in this game", color = Ui.gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            for ((label, set) in rows) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Ui.muted, fontSize = 10.sp, modifier = Modifier.width(40.dp))
                for (id in set) { Box(Modifier.alpha(0.55f)) { CharPic(id, t.charIndex[id]?.name ?: id, 22.dp) }; HGap(2) }
            }
            Gap(4)
        }
        if (g.bans.isNotEmpty()) {
            Muted("Bans", 11)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                for (b in g.bans) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp).alpha(0.75f)) {
                    Box { CharPic(b, t.charIndex[b]?.name ?: b, 32.dp); Text("✕", color = Ui.loss, fontWeight = FontWeight.Black, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center)) }
                    Text(t.charIndex[b]?.name ?: b, color = Ui.muted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        for (side in order) {
            Gap(4)
            Text("${w.teams[teams[side]].short}${if (t.isMoba) " · " + (if (side == g.blue) sideNames.first else sideNames.second) else ""}", color = if (order[0] == side) Ui.blue else Ui.red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                for (l in g.lines.filter { it.team == side }.sortedBy { t.lineup.indexOf(it.roleId).let { i -> if (i < 0) 9 else i } }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp).clickable { vm.go(Screen.Player(l.playerId)) }) {
                        CharPic(l.charId, t.charIndex[l.charId]?.name ?: l.charId, 44.dp, round = true)
                        Text(t.charIndex[l.charId]?.name ?: "—", color = Ui.text, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(w.players[l.playerId].handle, color = Ui.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (g.draftOrder.isNotEmpty() && t.draft.mode == "sequential") DraftRecap(vm, w, f, t, g, order)
    }
    Card("Box score") {
        for (side in order) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Box(Modifier.size(10.dp).background(if (order[0] == side) Ui.blue else Ui.red, RoundedCornerShape(5.dp))); HGap(6)
                if (t.isMoba) Text(if (side == g.blue) sideNames.first else sideNames.second, color = Ui.muted, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
                TeamChip(w, teams[side], vm, 16.dp, bold = side == g.winner)
                if (side == g.winner) Text(" 🏆", fontSize = 12.sp)
            }
            FullBoxScore(vm, w, t, g.lines.filter { it.team == side }.sortedBy { t.lineup.indexOf(it.roleId).let { i -> if (i < 0) 9 else i } }, mvp?.playerId ?: -1)
            Gap(6)
        }
    }
    if (!t.isMoba) EconomyCard(vm, w, t, f, g)
    TeamCompare(w, t, f, g.lines, order)
    // Analysis from the recorded game comes after the draft and the box score.
    if (t.isMoba) MobaAnalysisCard(vm, w, f, t, g, gi) else ShooterAnalysisCard(vm, w, f, t, g, gi)
}

@Composable
private fun FullBoxScore(vm: GameVm, w: World, t: TitleDef, lines: List<Line>, mvp: Int) {
    val hasKda = t.statIndex.containsKey("k") && t.statIndex.containsKey("d")
    val hasItems = lines.any { it.items.isNotEmpty() }
    val cols = listOf(Col("Player", 150.dp, false), Col("Role", 40.dp, false)) + (if (hasItems) listOf(Col("Build", 128.dp, false)) else emptyList()) +
        t.stats.map { Col(it.label, if (it.key in setOf("gold", "dmg", "taken", "heal")) 58.dp else 44.dp) } + (if (hasKda) listOf(Col("KDA", 46.dp)) else emptyList()) + Col("Rtg", 46.dp)
    val off = if (hasItems) 1 else 0
    Table(cols, lines.size, onRow = { vm.go(Screen.Player(lines[it].playerId)) }, highlight = { lines[it].playerId == mvp }) { r, c0 ->
        val l = lines[r]
        if (hasItems && c0 == 2) { ItemRow(w, l.items, 19.dp); return@Table }
        val c = if (c0 > 2) c0 - off else c0
        when {
            c == 0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (l.charId.isNotEmpty()) { CharPic(l.charId, t.charIndex[l.charId]?.name ?: l.charId, 24.dp, round = true); HGap(4) }
                else { PlayerPic(w.players[l.playerId], 24.dp, round = true); HGap(4) }
                Column {
                    Text(w.players[l.playerId].handle + if (l.playerId == mvp) " ★" else "", color = if (l.playerId == mvp) Ui.gold else Ui.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    t.charIndex[l.charId]?.let { Muted(it.name, 10) }
                }
            }
            c == 1 -> Muted(t.role(l.roleId).short, 11)
            c < 2 + t.stats.size -> {
                val v = l.s.getOrElse(c - 2) { 0.0 }
                val key = t.stats[c - 2].key
                Num(if (v >= 10000 || (key == "gold" && v >= 1000)) "%.1fk".format(v / 1000) else "%.0f".format(v))
            }
            hasKda && c == 2 + t.stats.size -> Num("%.2f".format((stat(t, l, "k") + stat(t, l, "a")) / maxOf(1.0, stat(t, l, "d"))))
            else -> Num("%.2f".format(l.rating), if (l.rating >= 1.2) Ui.win else if (l.rating < 0.85) Ui.loss else Ui.text, bold = true)
        }
    }
}

/** Team totals side by side as bars. */
@Composable
private fun TeamCompare(w: World, t: TitleDef, f: Fixture, lines: List<Line>, order: IntArray) {
    val keys = (if (t.isMoba) listOf("k", "gold", "cs", "dmg", "taken", "heal", "vision", "towers", "obj") else listOf("k", "a", "fk", "hs", "dmg", "util", "plant", "defuse", "clutch", "mk")).filter { t.statIndex.containsKey(it) }
    if (keys.isEmpty() || lines.isEmpty()) return
    val teams = intArrayOf(f.a, f.b)
    Card("Team comparison") {
        Row { Text(w.teams[teams[order[0]]].short, color = Ui.blue, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(w.teams[teams[order[1]]].short, color = Ui.red, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        for (k in keys) {
            val a = lines.filter { it.team == order[0] }.sumOf { stat(t, it, k) }
            val b = lines.filter { it.team == order[1] }.sumOf { stat(t, it, k) }
            val tot = (a + b).coerceAtLeast(1.0)
            fun fmt(v: Double) = if (v >= 10000) "%.1fk".format(v / 1000) else "%.0f".format(v)
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(a), color = if (a >= b) Ui.text else Ui.muted, fontSize = 12.sp, fontWeight = if (a >= b) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(48.dp))
                Row(Modifier.weight(1f).height(8.dp)) {
                    Box(Modifier.weight((a / tot).toFloat().coerceIn(0.01f, 0.99f)).height(8.dp).background(Ui.blue, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)))
                    Box(Modifier.weight((b / tot).toFloat().coerceIn(0.01f, 0.99f)).height(8.dp).background(Ui.red, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
                }
                Text(fmt(b), color = if (b >= a) Ui.text else Ui.muted, fontSize = 12.sp, fontWeight = if (b >= a) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
            }
            Text(t.stats[t.statIndex[k]!!].label, color = Ui.muted, fontSize = 9.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesTotals(vm: GameVm, w: World, f: Fixture, t: TitleDef, games: List<GameRecord>) {
    val by = games.flatMap { it.lines }.groupBy { it.playerId }
    val k = t.statIndex["k"]
    val d = t.statIndex["d"]
    val a = t.statIndex["a"]
    Card("Series totals") {
        Muted("${games.size} ${if (t.isMoba) "games" else "maps"} · average game rating per player", 11)
        SortTable(by.entries.toList(), listOf<SCol<Map.Entry<Int, List<Line>>>>(
            SCol("Player", 140.dp, false) { e -> Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(if (e.value[0].team == 0) Ui.blue else Ui.red, RoundedCornerShape(4.dp))); HGap(4); PlayerChip(w.players[e.key], vm, 18.dp) } },
            SCol("Games", 50.dp, key = { it.value.size.toDouble() }) { Num("${it.value.size}") },
            SCol("K/D/A", 80.dp, key = { e -> k?.let { i -> e.value.sumOf { it.s[i] } } }) { e -> Num(if (k != null && d != null && a != null) "%.0f/%.0f/%.0f".format(e.value.sumOf { it.s[k] }, e.value.sumOf { it.s[d] }, e.value.sumOf { it.s[a] }) else "—") },
            SCol("KDA", 46.dp, key = { e -> if (k != null && d != null && a != null) (e.value.sumOf { it.s[k] } + e.value.sumOf { it.s[a] }) / maxOf(1.0, e.value.sumOf { it.s[d] }) else null }) { e ->
                Num(if (k != null && d != null && a != null) "%.2f".format((e.value.sumOf { it.s[k] } + e.value.sumOf { it.s[a] }) / maxOf(1.0, e.value.sumOf { it.s[d] })) else "—")
            },
            SCol("Rating", 54.dp, key = { e -> e.value.map { it.rating }.average() }) { e -> Num("%.2f".format(e.value.map { it.rating }.average()), bold = true) },
            SCol(t.charLabel + "s", 140.dp, false) { e ->
                Row { for (l in e.value) if (l.charId.isNotEmpty()) { CharPic(l.charId, l.charId, 20.dp, round = true); HGap(2) } }
            },
        ), initialSort = 4, onRow = { vm.go(Screen.Player(it.key)) }, highlight = { it.key == f.mvp })
    }
    if (!t.isMoba) Card("Maps") {
        FlowRow { games.forEachIndexed { i, g -> Pill("${i + 1}. ${t.maps.firstOrNull { it.id == g.mapId }?.name ?: g.mapId}: ${g.score.getOrElse(0) { 0 }}-${g.score.getOrElse(1) { 0 }}", if (g.winner == 0) Ui.blue.copy(alpha = 0.35f) else Ui.red.copy(alpha = 0.35f)) } }
    }
}

/** The draft step by step: who banned and picked what, in order, and for whom. */
@Composable
private fun DraftRecap(vm: GameVm, w: World, f: Fixture, t: TitleDef, g: GameRecord, order: IntArray) {
    val teams = intArrayOf(f.a, f.b)
    Gap(8)
    Text("Draft recap", color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Muted("${w.teams[teams[order[0]]].short} on the left, ${w.teams[teams[order[1]]].short} on the right", 10)
    var lastBan: Boolean? = null
    var phase = 0
    g.draftOrder.forEachIndexed { i, e ->
        val p = e.split('|')
        if (p.size < 3) return@forEachIndexed
        val ban = p[0] == "b"
        val team = p[1].toIntOrNull() ?: 0
        val cid = p[2]
        if (ban != lastBan) { phase++; lastBan = ban; Text("${if (ban) "Ban" else "Pick"} phase ${(phase + 1) / 2}", color = Ui.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) }
        val left = team == order[0]
        val pid = p.getOrNull(3)?.toIntOrNull() ?: -1
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}", color = Ui.muted, fontSize = 10.sp, modifier = Modifier.width(20.dp))
            @Composable fun cell(mod: Modifier) = Row(mod.clickable(enabled = pid >= 0) { vm.go(Screen.Player(pid)) }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.alpha(if (ban) 0.7f else 1f)) {
                    CharPic(cid, t.charIndex[cid]?.name ?: cid, 26.dp, round = !ban)
                    if (ban) Text("✕", color = Ui.loss, fontWeight = FontWeight.Black, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
                }
                HGap(4)
                Column {
                    Text(t.charIndex[cid]?.name ?: cid, color = if (ban) Ui.muted else Ui.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (ban) "ban · ${w.teams[teams[team]].short}" else (w.players.getOrNull(pid)?.handle ?: w.teams[teams[team]].short), color = if (left) Ui.blue else Ui.red, fontSize = 9.sp, maxLines = 1)
                }
            }
            if (left) { cell(Modifier.weight(1f)); Box(Modifier.weight(1f)) }
            else { Box(Modifier.weight(1f)); cell(Modifier.weight(1f)) }
        }
    }
}

/** Scouting report before a series: the opponent first when it is your match. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScoutingCard(vm: GameVm, w: World, f: Fixture, t: TitleDef) {
    val mineA = w.isUserTeam(f.a); val mineB = w.isUserTeam(f.b)
    val order = if (mineA) listOf(f.b, f.a) else listOf(f.a, f.b)
    var sel by remember(f.id) { mutableIntStateOf(0) }
    val teamId = order[sel]
    val r = remember(f.id, teamId, vm.version) { dev.uem.em.mgmt.Scouting.report(w, teamId, order[1 - sel]) }
    Card("Scouting report") {
        Tabs(order.map { w.teams[it].short + if (w.isUserTeam(it)) " (you)" else "" }, sel) { sel = it }
        if (r.series == 0) { Muted("No recorded matches yet: the report fills in as they play.", 11); return@Card }
        Muted("Last ${r.series} series · ${r.games} ${if (t.isMoba) "games" else "maps"} · won ${r.wins} · form ${r.form}" + (if (t.isMoba && r.avgLength > 0) " · avg %d:%02d · %.1f kills".format(r.avgLength / 60, r.avgLength % 60, r.avgKills) else ""), 11)
        if (r.banApproach.isNotEmpty()) KV("Analysts' read", "They usually ${r.banApproach}")
        if (r.sideGames.sum() > 0) {
            val names = if (t.isMoba) (if (t.id == "dota2") "Radiant" to "Dire" else "Blue" to "Red") else "Attack first" to "Defence first"
            KV("By side", "${names.first} ${r.sideWins[0]}-${r.sideGames[0] - r.sideWins[0]} · ${names.second} ${r.sideWins[1]}-${r.sideGames[1] - r.sideWins[1]}")
        }
        @Composable fun charRow(title: String, list: List<Pair<String, Int>>) {
            if (list.isEmpty()) return
            Gap(4); Text(title, color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 3.dp)) {
                for ((cid, n) in list) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(54.dp)) {
                    CharPic(cid, t.charIndex[cid]?.name ?: cid, 30.dp, round = true)
                    Text(t.charIndex[cid]?.name ?: cid, color = Ui.text, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("×$n", color = Ui.muted, fontSize = 9.sp)
                }
            }
        }
        charRow("Their bans", r.bans)
        charRow("Their first picks", r.firstPicks)
        charRow("Banned against them", r.bansAgainst)
        if (r.maps.isNotEmpty()) { Gap(4); Text("Maps", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); FlowRow { for ((m, g, wn) in r.maps) Pill("${t.maps.firstOrNull { it.id == m }?.name ?: m} $wn-${g - wn}") } }
        if (t.characters.isNotEmpty()) {
            Gap(4); Text("Players: most played · most comfortable", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            for (n in r.players) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Player(n.playerId)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerPic(w.players[n.playerId], 22.dp, round = true); HGap(4)
                Text(w.players[n.playerId].handle, color = Ui.text, fontSize = 12.sp, modifier = Modifier.width(80.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    for ((cid, c) in n.played) { CharPic(cid, t.charIndex[cid]?.name ?: cid, 22.dp, round = true); Text("$c ", color = Ui.muted, fontSize = 9.sp) }
                    if (n.played.isNotEmpty()) Text("· ", color = Ui.muted, fontSize = 11.sp)
                    for ((cid, _) in n.comfort) { Box(Modifier.alpha(0.75f)) { CharPic(cid, t.charIndex[cid]?.name ?: cid, 22.dp) }; HGap(2) }
                }
            }
        }
        if (r.ideas.isNotEmpty()) {
            Gap(6); Text("Ideas", color = Ui.gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            for (i in r.ideas) Muted("• $i", 11)
        }
    }
}

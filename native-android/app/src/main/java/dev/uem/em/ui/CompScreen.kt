package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.comp.Stages
import dev.uem.em.core.Dates
import dev.uem.em.core.formatMoney
import dev.uem.em.core.ordinal
import dev.uem.em.data.GameData
import dev.uem.em.mgmt.Economy
import dev.uem.em.model.CompStats
import dev.uem.em.model.CompTier
import dev.uem.em.model.Competition
import dev.uem.em.model.Fixture
import dev.uem.em.model.Stage
import dev.uem.em.model.StageType
import dev.uem.em.model.Standing
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.titles.Catalogue

private fun titleIdx(id: String?) = if (id == null) 0 else Catalogue.IDS.indexOf(id) + 1
private val TITLE_TABS get() = listOf("All") + Catalogue.IDS.map { Catalogue.get(it).short }

/** Editions of the same league split or event across seasons share this key. */
internal fun seriesKey(c: Competition) = if (c.leagueId >= 0) "L${c.leagueId}-${c.split}-${c.tier}" else c.titleId + ":" + c.name.replace(Regex("\\b(19|20)\\d{2}\\b"), "").trim()

private fun seriesName(c: Competition) = c.name.replace(Regex("\\s*\\b(19|20)\\d{2}\\b"), "").trim()
private fun tierLabel(t: CompTier) = when (t) { CompTier.WORLDS -> "World championship"; CompTier.INTERNATIONAL -> "International"; CompTier.LEAGUE -> "League"; CompTier.CHALLENGER -> "Challenger"; CompTier.CUSTOM -> "Custom (user-created)" }
private fun stageType(t: StageType) = when (t) { StageType.RR -> "round robin"; StageType.DRR -> "double round robin"; StageType.GROUPS -> "groups"; StageType.SWISS -> "Swiss"; StageType.SINGLE_ELIM -> "single elimination"; StageType.DOUBLE_ELIM -> "double elimination" }
private fun statusLabel(s: Int) = when (s) { 0 -> "upcoming"; 1 -> "in progress"; else -> "finished" }

@Composable
fun CompsScreen(vm: GameVm, w: World) {
    var title by remember { mutableStateOf<String?>(w.teams.firstOrNull { w.isUserTeam(it.id) }?.titleId) }
    var mode by remember { mutableIntStateOf(0) }
    var season by remember { mutableIntStateOf(w.season) }
    val seasons = w.comps.map { it.season }.distinct().sortedDescending()
    Column(Modifier.fillMaxSize()) {
        Tabs(TITLE_TABS, titleIdx(title)) { title = if (it == 0) null else Catalogue.IDS[it - 1] }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Tabs(listOf("Season", "All competitions"), mode) { mode = it }
            androidx.compose.material3.TextButton(onClick = { vm.go(Screen.CompEditor) }) { Text("+ Create", color = Ui.accent, fontSize = 12.sp) }
        }
        if (mode == 0) {
            Row(Modifier.padding(horizontal = 12.dp)) { Select("Season", seasons.map { "$it" }, seasons.indexOf(season).coerceAtLeast(0)) { season = seasons[it] } }
            val comps = w.comps.filter { it.season == season && (title == null || it.titleId == title) }.sortedWith(compareBy<Competition> { it.startDay }.thenBy { it.tier.ordinal })
            LazyColumn(Modifier.fillMaxSize()) {
                items(comps, key = { it.id }) { c -> CompRow(vm, w, c) }
            }
        } else {
            val series = w.comps.filter { title == null || it.titleId == title }.groupBy { seriesKey(it) }.values
                .sortedWith(compareByDescending<List<Competition>> { it.first().tier.ordinal }.thenBy { it.first().titleId }.thenBy { seriesName(it.first()) })
            LazyColumn(Modifier.fillMaxSize()) {
                items(series.toList(), key = { seriesKey(it.first()) }) { eds ->
                    val latest = eds.maxByOrNull { it.season * 1000 + it.startDay }!!
                    val holder = eds.filter { it.done && it.placements.isNotEmpty() }.maxByOrNull { it.season * 1000 + it.startDay }?.placements?.get(0)
                    val most = eds.filter { it.done && it.placements.isNotEmpty() }.groupingBy { it.placements[0] }.eachCount().maxByOrNull { it.value }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).background(Ui.card, RoundedCornerShape(10.dp)).clickable { vm.go(Screen.Comp(latest.id)) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pic(latest.leagueId.takeIf { it >= 0 }?.let { Pics.league(w.leagues[it]) }, latest.short, 32.dp)
                        HGap(10)
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { TitleTag(latest.titleId); Text(seriesName(latest), color = if (latest.tier == CompTier.WORLDS) Ui.gold else Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Muted("${tierLabel(latest.tier)} · ${if (latest.region.isNotEmpty()) GameData.regionName(latest.region) else "international"} · ${eds.size} edition${if (eds.size > 1) "s" else ""} · now ${latest.season}: ${statusLabel(latest.status)}", 11)
                            if (most != null) Muted("Most titles: ${w.teams[most.key].short} (${most.value})", 11)
                        }
                        if (holder != null) Column(horizontalAlignment = Alignment.End) { Muted("Holder", 10); Text(w.teams[holder].short, color = Ui.gold, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompRow(vm: GameVm, w: World, c: Competition) {
    val mine = c.teamIds.firstOrNull { w.isUserTeam(it) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).background(Ui.card, RoundedCornerShape(10.dp)).clickable { vm.go(Screen.Comp(c.id)) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Pic(c.leagueId.takeIf { it >= 0 }?.let { Pics.league(w.leagues[it]) }, c.short, 34.dp)
        HGap(10)
        Column(Modifier.weight(1f)) {
            Text(c.name, color = if (c.tier == CompTier.WORLDS) Ui.gold else Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TitleTag(c.titleId)
                Muted("${Dates.short(c.startDay)} · ${c.teamIds.size.takeIf { it > 0 }?.let { "$it teams · " } ?: ""}${when (c.status) { 0 -> "upcoming"; 1 -> c.stages.getOrNull(c.current)?.name ?: "live"; else -> "finished" }}", 11)
            }
            if (c.prizePool > 0) Muted("Prize pool ${formatMoney(c.prizePool)}${dev.uem.em.comp.Fearless.modeOf(c).takeIf { it != "none" }?.let { " · " + dev.uem.em.comp.Fearless.LABEL[it] } ?: ""}", 11)
            if (mine != null) Text("${w.teams[mine].short}: ${c.placements.indexOf(mine).takeIf { it >= 0 }?.let { ordinal(it + 1) } ?: if (c.done) "—" else "competing"}", color = Ui.accent, fontSize = 11.sp)
        }
        if (c.done && c.placements.isNotEmpty()) Column(horizontalAlignment = Alignment.End) {
            Muted("Champion", 10)
            TeamPic(w.teams[c.placements[0]], 24.dp)
            Text(w.teams[c.placements[0]].short, color = Ui.gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

private val COMP_TABS = listOf("Standings", "Matches", "Player stats", "Placements & prizes", "Teams")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompScreen(vm: GameVm, w: World, id: Int) {
    val c = w.comps[id]
    val t = Catalogue.get(c.titleId)
    var tab by remember(id) { mutableIntStateOf(UiDefaults.tab.coerceAtMost(COMP_TABS.size - 1)) }
    var si by remember(id) { mutableIntStateOf(c.current.coerceIn(0, maxOf(0, c.stages.size - 1))) }
    val editions = w.comps.filter { seriesKey(it) == seriesKey(c) }.sortedByDescending { it.season * 1000 + it.startDay }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Pic(c.leagueId.takeIf { it >= 0 }?.let { Pics.league(w.leagues[it]) }, c.short, 52.dp)
                HGap(12)
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = if (c.tier == CompTier.WORLDS) Ui.gold else Ui.text, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                    FlowRow { TitleTag(t.id); Text("${tierLabel(c.tier)} · ${if (c.region.isNotEmpty()) GameData.regionName(c.region) else "international"} · ${statusLabel(c.status)}", color = Ui.muted, fontSize = 12.sp) }
                    Muted("${Dates.format(c.startDay)} – ${c.stages.lastOrNull()?.let { st -> w.fixtures.getOrNull(st.fixtureIds.lastOrNull() ?: -1)?.day?.let { Dates.format(it) } ?: "TBD" } ?: ""}", 11)
                }
            }
            if (editions.size > 1) Row(Modifier.padding(horizontal = 12.dp)) {
                Select("Edition", editions.map { "${it.season}${if (it.split > 0) " S${it.split}" else ""}" }, editions.indexOf(c)) { vm.go(Screen.Comp(editions[it].id)) }
            }
        }
        item {
            val champ = c.placements.firstOrNull()?.takeIf { c.done }
            Tiles(listOfNotNull(
                Tile("Prize pool", formatMoney(c.prizePool)),
                Tile("Teams", "${c.teamIds.size}", c.entrants.joinToString { it.substringBefore(':') }),
                Tile("Format", c.stages.joinToString(" / ") { "Bo${it.bestOf}" }, dev.uem.em.comp.Fearless.modeOf(c).takeIf { it != "none" }?.let { dev.uem.em.comp.Fearless.LABEL[it] } ?: c.stages.joinToString(" → ") { stageType(it.type) }),
                if (champ != null) Tile("Champion", w.teams[champ].short, w.teams[champ].name, Ui.gold) else Tile("Stage", c.stages.getOrNull(c.current)?.name ?: "—", statusLabel(c.status)),
                if (c.mvp >= 0) Tile("MVP", w.players[c.mvp].handle, w.teams.getOrNull(w.players[c.mvp].teamId)?.short, Ui.gold) else null,
                Tile("Matches", "${c.stages.sumOf { s -> s.fixtureIds.count { w.fixtures[it].done } }} / ${c.stages.sumOf { it.fixtureIds.size }}", "played"),
            ))
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
                c.stages.forEachIndexed { i, s ->
                    Column(
                        Modifier.padding(end = 6.dp).background(if (i == si) Ui.accent else Ui.card2, RoundedCornerShape(10.dp)).clickable { si = i }.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(s.name, color = if (i == si) Color.White else Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("${stageType(s.type)} · ${statusLabel(s.status)}", color = if (i == si) Color.White.copy(alpha = 0.8f) else Ui.muted, fontSize = 10.sp)
                    }
                }
            }
        }
        item { Tabs(COMP_TABS.mapIndexed { i, l -> if (i == 0 && c.stages.getOrNull(si)?.bracket?.isNotEmpty() == true) "Bracket" else l }, tab) { tab = it } }
        val st = c.stages.getOrNull(si)
        when (tab) {
            0 -> item { if (st != null) StageCard(vm, w, c, st) }
            1 -> {
                val fx = (st?.fixtureIds ?: emptyList()).map { w.fixtures[it] }.sortedBy { it.day }
                val byDay = fx.groupBy { it.day }
                if (fx.isEmpty()) item { Card { Muted("No matches scheduled yet.") } }
                for ((day, list) in byDay) {
                    item(key = "d$day") { Text(Dates.format(day, true), color = Ui.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)) }
                    items(list, key = { it.id }) { f -> FixtureRow(vm, w, f) }
                }
            }
            2 -> item { CompPlayerStats(vm, w, c) }
            3 -> item { Placements(vm, w, c) }
            else -> item { Entrants(vm, w, c) }
        }
        item { Gap(16) }
    }
}

@Composable
private fun StageCard(vm: GameVm, w: World, c: Competition, st: Stage) {
    Card(st.name, right = { Muted("${stageType(st.type)} · Bo${st.bestOf} · ${statusLabel(st.status)}", 11) }) {
        if (st.status == 0 && st.standings.isEmpty() && st.bracket.all { it.teamA < 0 && it.teamB < 0 }) {
            Muted("Starts ${Dates.format(st.startDay)}. Entrants: ${c.entrants.joinToString { e -> when (e.substringBefore(':')) { "league" -> "league teams"; "top" -> "top finishers of an earlier event"; "circuit" -> "circuit points"; "nations" -> "national teams"; else -> e } }}.")
            return@Card
        }
        when (st.type) {
            StageType.SINGLE_ELIM, StageType.DOUBLE_ELIM -> BracketView(vm, w, st)
            StageType.GROUPS -> for (g in st.standings.map { it.group }.distinct().sorted()) {
                Text("Group ${'A' + g}", color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                StandingsFull(vm, w, c, st, Stages.sortStandings(w, st.standings.filter { it.group == g }))
            }
            else -> StandingsFull(vm, w, c, st, if (st.type == StageType.SWISS) st.standings.sortedWith(compareByDescending<Standing> { it.w }.thenBy { it.l }.thenByDescending { it.gw - it.gl }) else Stages.sortStandings(w, st.standings))
        }
        if (st.type == StageType.SWISS) Muted("Swiss: ${st.swissWins} wins advance, ${st.swissLosses} losses are eliminated.", 11)
        if (st.advance > 0 && st.type != StageType.SWISS) Muted("Top ${st.advance} ${if (st.type == StageType.GROUPS) "of each group " else ""}advance (highlighted).", 11)
    }
}

@Composable
private fun StandingsFull(vm: GameVm, w: World, c: Competition, st: Stage, rows: List<Standing>) {
    val fx = st.fixtureIds.map { w.fixtures[it] }.filter { it.done }
    fun form(tid: Int) = fx.filter { it.a == tid || it.b == tid }.sortedByDescending { it.day }.take(5).reversed()
    Table(listOf(Col("#", 24.dp), Col("Team", 150.dp, false), Col("W", 28.dp), Col("L", 28.dp), Col(if (Catalogue.get(c.titleId).isMoba) "Games" else "Maps", 50.dp), Col("Diff", 38.dp), Col("Win%", 42.dp), Col("Form", 90.dp, false)), rows.size,
        onRow = { vm.go(Screen.Team(rows[it].teamId)) }, highlight = { w.isUserTeam(rows[it].teamId) }) { r, col ->
        val row = rows[r]
        val adv = st.advance > 0 && r < st.advance
        when (col) {
            0 -> Row(verticalAlignment = Alignment.CenterVertically) { if (adv) Box(Modifier.size(width = 3.dp, height = 14.dp).background(Ui.win)); Num(" ${r + 1}", if (adv) Ui.win else Ui.muted) }
            1 -> TeamChip(w, row.teamId, vm, 18.dp)
            2 -> Num("${row.w}", bold = true)
            3 -> Num("${row.l}")
            4 -> Num("${row.gw}-${row.gl}", Ui.muted)
            5 -> Num("%+d".format(row.gw - row.gl), if (row.gw >= row.gl) Ui.win else Ui.loss)
            6 -> Num("${100 * row.w / maxOf(1, row.w + row.l)}%")
            else -> Row { for (f in form(row.teamId)) Text(if (f.winner == row.teamId) "W" else "L", color = if (f.winner == row.teamId) Ui.win else Ui.loss, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 3.dp)) }
        }
    }
}

/** The bracket as columns of rounds, scrolling sideways. */
@Composable
private fun BracketView(vm: GameVm, w: World, st: Stage) {
    val rounds = st.bracket.map { it.round }.distinct().sorted()
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        for (r in rounds) {
            Column(Modifier.width(170.dp).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val nodes = st.bracket.filter { it.round == r }
                Text(if (st.type == StageType.DOUBLE_ELIM) "Round ${r + 1}" else nodes.first().label.substringBefore(" ").let { nodes.first().label }, color = Ui.muted, fontSize = 10.sp, maxLines = 1)
                for (nd in nodes) {
                    val f = nd.fixtureId.takeIf { it >= 0 }?.let { w.fixtures[it] }
                    Column(
                        Modifier.fillMaxWidth().background(Ui.card2, RoundedCornerShape(8.dp)).then(if (nd.isFinal) Modifier.border(1.dp, Ui.gold, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable(enabled = f != null) { f?.let { vm.go(Screen.Match(it.id)) } }.padding(8.dp),
                    ) {
                        Text(nd.label, color = if (nd.isFinal) Ui.gold else Ui.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        for ((tid, score) in listOf(nd.teamA to f?.takeIf { it.done }?.scoreA, nd.teamB to f?.takeIf { it.done }?.scoreB)) {
                            Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (tid >= 0) {
                                    TeamPic(w.teams[tid], 16.dp); HGap(4)
                                    Text(w.teams[tid].short, color = if (nd.winner == tid) Ui.text else if (nd.winner >= 0) Ui.muted else Ui.text, fontWeight = if (nd.winner == tid) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                                } else Text(if (tid == -1) "bye" else "TBD", color = Ui.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                if (score != null) Text("$score", color = if (nd.winner == tid) Ui.win else Ui.muted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                        if (f != null && !f.done) Muted("${Dates.short(f.day)} · Bo${f.bestOf}", 9)
                    }
                }
            }
        }
    }
}

@Composable
fun FixtureRow(vm: GameVm, w: World, f: Fixture) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).background(if (w.isUserTeam(f.a) || w.isUserTeam(f.b)) Ui.card2 else Ui.card, RoundedCornerShape(8.dp))
            .clickable { vm.go(Screen.Match(f.id)) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(66.dp)) { Muted(Dates.short(f.day), 11); Muted(fixtureLabel(w, f).substringAfter(" · ", fixtureLabel(w, f)).take(14), 9); Muted("Bo${f.bestOf}", 9) }
        Column(Modifier.weight(1f)) {
            for ((tid, sc) in listOf(f.a to f.scoreA, f.b to f.scoreB)) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { if (tid >= 0) TeamChip(w, tid, vm, 18.dp, bold = f.winner == tid) else Muted("TBD") }
                if (f.done) Num("$sc", if (f.winner == tid) Ui.win else Ui.muted, bold = f.winner == tid)
            }
        }
    }
}

@Composable
private fun CompPlayerStats(vm: GameVm, w: World, c: Competition) {
    val t = Catalogue.get(c.titleId)
    var g by remember { mutableIntStateOf(0) }
    var team by remember { mutableIntStateOf(-1) }
    val gs = groups(t)
    val cols = gs[g.coerceIn(0, gs.size - 1)].second
    val idx: (String) -> Int = { t.statIndex[it] ?: -1 }
    val teams = c.pstats.values.map { it.teamId }.distinct().filter { it >= 0 }.sortedBy { w.teams[it].name }
    val rows = c.pstats.entries.filter { it.value.gp > 0 && (team < 0 || it.value.teamId == team) }.toList()
    Card("Player stats", right = { Muted("${rows.size} players", 11) }) {
        Row {
            Select("Stats", gs.map { it.first }, g) { g = it }
            Select("Team", listOf("All teams") + teams.map { w.teams[it].short }, if (team < 0) 0 else teams.indexOf(team) + 1) { team = if (it == 0) -1 else teams[it - 1] }
        }
        if (rows.isEmpty()) Muted("No games played yet.")
        else SortTable(rows, listOf<SCol<Map.Entry<Int, CompStats>>>(
            SCol("Player", 140.dp, false) { e -> Column { PlayerChip(w.players[e.key], vm, 18.dp); Muted("${w.teams.getOrNull(e.value.teamId)?.short ?: "—"} · ${Catalogue.get(c.titleId).role(w.players[e.key].roles[0]).short}", 10) } },
        ) + cols.map { col -> SCol<Map.Entry<Int, CompStats>>(col.title, 58.dp, key = { e -> col.v(e.value.gp, e.value.w, e.value.r, e.value.m, e.value.v, idx) }) { e -> Num(col.v(e.value.gp, e.value.w, e.value.r, e.value.m, e.value.v, idx)?.let { col.fmt(it) } ?: "—") } },
            initialSort = 3.coerceAtMost(cols.size), limit = 200, onRow = { vm.go(Screen.Player(it.key)) }, highlight = { w.isUserTeam(it.value.teamId) })
    }
}

@Composable
private fun Placements(vm: GameVm, w: World, c: Competition) {
    Card(if (c.done) "Final placements" else "Prize distribution") {
        if (!c.done) {
            Muted("Prize money by placing (paid when the competition ends):", 12)
            Economy.PRIZE_SPLIT.forEachIndexed { i, sh -> KV(ordinal(i + 1), "${formatMoney(c.prizePool * sh)}${c.circuitPoints.getOrNull(i)?.takeIf { it > 0 }?.let { " · $it pts" } ?: ""}") }
            return@Card
        }
        c.placements.forEachIndexed { i, tid ->
            Row(Modifier.fillMaxWidth().background(if (w.isUserTeam(tid)) Ui.card2 else Color.Transparent).clickable { vm.go(Screen.Team(tid)) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (i == 0) "🏆" else ordinal(i + 1), color = if (i < 3) Ui.gold else Ui.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
                Column(Modifier.weight(1f)) { TeamChip(w, tid, vm, 18.dp, bold = i == 0) }
                Column(horizontalAlignment = Alignment.End) {
                    Economy.PRIZE_SPLIT.getOrNull(i)?.let { Text(formatMoney(c.prizePool * it), color = Ui.text, fontSize = 12.sp) }
                    c.circuitPoints.getOrNull(i)?.takeIf { it > 0 }?.let { Muted("$it circuit pts", 10) }
                }
            }
        }
    }
}

@Composable
private fun Entrants(vm: GameVm, w: World, c: Competition) {
    Card("Teams (${c.teamIds.size})") {
        if (c.teamIds.isEmpty()) Muted("Entrants are decided when the competition starts.")
        val rows = c.teamIds.map { w.teams[it] }
        SortTable(rows, listOf(
            SCol("Team", 150.dp, false) { tm -> TeamChip(w, tm.id, vm, 18.dp) },
            SCol("Region", 70.dp, false) { tm -> Muted(GameData.regionName(tm.region), 11) },
            SCol("Elo", 46.dp, key = { it.elo }) { tm -> Num("${tm.elo.toInt()}") },
            SCol("Str", 44.dp, key = { Lineups.strength(w, it) }) { tm -> Rating(Lineups.strength(w, tm), 1) },
            SCol("Reg.", 40.dp, key = { (c.registrations[it.id]?.players?.size ?: 0).toDouble() }) { tm -> Num("${c.registrations[tm.id]?.players?.size ?: "—"}", Ui.muted) },
        ), initialSort = 2, onRow = { vm.go(Screen.Team(it.id)) }, highlight = { w.isUserTeam(it.id) })
    }
}

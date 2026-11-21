package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
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
import dev.uem.em.data.GameData
import dev.uem.em.game.BoxStore
import dev.uem.em.model.CompTier
import dev.uem.em.model.Competition
import dev.uem.em.model.Fixture
import dev.uem.em.model.Line
import dev.uem.em.model.StageType
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.TitleDef

private fun titleIdx(id: String?) = if (id == null) 0 else Catalogue.IDS.indexOf(id) + 1
private val TITLE_TABS get() = listOf("All") + Catalogue.IDS.map { Catalogue.get(it).short }

@Composable
fun CompsScreen(vm: GameVm, w: World) {
    var title by remember { mutableStateOf<String?>(w.teams.firstOrNull { w.isUserTeam(it.id) }?.titleId) }
    var season by remember { mutableIntStateOf(w.season) }
    val seasons = w.comps.map { it.season }.distinct().sortedDescending()
    val comps = w.comps.filter { it.season == season && (title == null || it.titleId == title) }.sortedWith(compareBy<Competition> { it.startDay })
    Column(Modifier.fillMaxSize()) {
        Tabs(TITLE_TABS, titleIdx(title)) { title = if (it == 0) null else Catalogue.IDS[it - 1] }
        Tabs(seasons.map { "$it" }, seasons.indexOf(season)) { season = seasons[it] }
        LazyColumn(Modifier.fillMaxSize()) {
            items(comps, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(Ui.card, RoundedCornerShape(10.dp)).clickable { vm.go(Screen.Comp(c.id)) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pic(c.leagueId.takeIf { it >= 0 }?.let { Pics.league(w.leagues[it]) }, c.short, 32.dp)
                    HGap(10)
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = if (c.tier == CompTier.WORLDS) Ui.gold else Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2)
                        Muted("${Catalogue.get(c.titleId).short} · ${Dates.short(c.startDay)} · ${when (c.status) { 0 -> "upcoming"; 1 -> c.stages[c.current].name; else -> "finished" }}")
                    }
                    if (c.done && c.placements.isNotEmpty()) Column(horizontalAlignment = Alignment.End) {
                        Muted("Champion", 10)
                        Text(w.teams[c.placements[0]].short, color = Ui.gold, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CompScreen(vm: GameVm, w: World, id: Int) {
    val c = w.comps[id]
    val t = Catalogue.get(c.titleId)
    var tab by remember(id) { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text(c.name, color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Muted("${t.name} · ${c.teamIds.size.takeIf { it > 0 } ?: "?"} teams · prize ${formatMoney(c.prizePool)}${if (c.fearless) " · hard Fearless draft" else ""}")
                if (c.done && c.placements.isNotEmpty()) {
                    Gap(6)
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("🏆 ", fontSize = 16.sp); TeamChip(w, c.placements[0], vm, 22.dp, bold = true) }
                    if (c.mvp >= 0) Muted("MVP: ${w.players[c.mvp].handle}")
                }
            }
        }
        item { Tabs(c.stages.map { it.name } + listOf("Matches", "Player stats"), tab) { tab = it } }
        when {
            tab < c.stages.size -> item { StageView(vm, w, c, tab) }
            tab == c.stages.size -> {
                val fx = c.stages.flatMap { it.fixtureIds }.map { w.fixtures[it] }.sortedWith(compareByDescending<Fixture> { it.done }.thenBy { if (it.done) -it.day else it.day })
                items(fx, key = { it.id }) { f -> FixtureRow(vm, w, f) }
            }
            else -> item { CompStatsView(vm, w, c, t) }
        }
    }
}

@Composable
private fun StageView(vm: GameVm, w: World, c: Competition, si: Int) {
    val st = c.stages[si]
    Card(st.name, right = { Muted(when (st.status) { 0 -> "not started"; 1 -> "in progress"; else -> "finished" }) }) {
        if (st.status == 0) { Muted("Starts ${Dates.format(st.startDay)}"); return@Card }
        when (st.type) {
            StageType.SINGLE_ELIM, StageType.DOUBLE_ELIM -> {
                for (r in st.bracket.map { it.round }.distinct().sorted()) {
                    Muted(st.bracket.first { it.round == r }.label.let { l -> if (st.type == StageType.DOUBLE_ELIM) "Round ${r + 1}" else l })
                    for (nd in st.bracket.filter { it.round == r }) {
                        val f = nd.fixtureId.takeIf { it >= 0 }?.let { w.fixtures[it] }
                        Row(Modifier.fillMaxWidth().clickable(enabled = f?.done == true) { f?.let { vm.go(Screen.Match(it.id)) } }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Muted(nd.label.take(18), 11)
                            HGap(6)
                            Column(Modifier.weight(1f)) {
                                if (nd.teamA >= 0) Row { TeamChip(w, nd.teamA, vm, 16.dp, bold = nd.winner == nd.teamA); f?.takeIf { it.done }?.let { Muted("  ${it.scoreA}") } } else Muted("TBD")
                                if (nd.teamB >= 0) Row { TeamChip(w, nd.teamB, vm, 16.dp, bold = nd.winner == nd.teamB); f?.takeIf { it.done }?.let { Muted("  ${it.scoreB}") } } else Muted("TBD")
                            }
                        }
                    }
                    Gap(6)
                }
            }
            StageType.GROUPS -> for (g in st.standings.map { it.group }.distinct().sorted()) {
                Muted("Group ${'A' + g}")
                StandingsTable(vm, w, Stages.sortStandings(w, st.standings.filter { it.group == g }))
                Gap()
            }
            else -> StandingsTable(vm, w, if (st.type == StageType.SWISS) st.standings.sortedWith(compareByDescending<dev.uem.em.model.Standing> { it.w }.thenBy { it.l }) else Stages.sortStandings(w, st.standings))
        }
    }
}

@Composable
fun FixtureRow(vm: GameVm, w: World, f: Fixture) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).background(Ui.card, RoundedCornerShape(8.dp)).clickable(enabled = f.done) { vm.go(Screen.Match(f.id)) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(54.dp)) { Muted(Dates.short(f.day), 11); Muted("Bo${f.bestOf}", 10) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { TeamChip(w, f.a, vm, 18.dp, bold = f.winner == f.a) }; if (f.done) Num("${f.scoreA}", bold = f.winner == f.a) }
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { TeamChip(w, f.b, vm, 18.dp, bold = f.winner == f.b) }; if (f.done) Num("${f.scoreB}", bold = f.winner == f.b) }
        }
    }
}

/** Stat groups for the player tables (as in the web version's dropdowns). */
private class StatCol(val title: String, val v: (gp: Int, w: Int, r: Double, m: Int, s: DoubleArray, idx: (String) -> Int) -> Double?, val fmt: (Double) -> String)

private fun groups(t: TitleDef): List<Pair<String, List<StatCol>>> {
    fun per(k: String) = StatCol(k, { gp, _, _, _, s, idx -> idx(k).takeIf { it >= 0 }?.let { s[it] / maxOf(1, gp) } }, { "%.1f".format(it) })
    fun tot(k: String, label: String) = StatCol(label, { _, _, _, _, s, idx -> idx(k).takeIf { it >= 0 }?.let { s[it] } }, { "%.0f".format(it) })
    val gp = StatCol("GP", { g, _, _, _, _, _ -> g.toDouble() }, { "%.0f".format(it) })
    val rating = StatCol("Rating", { g, _, r, _, _, _ -> r / maxOf(1, g) }, { "%.2f".format(it) })
    val wr = StatCol("Win%", { g, wn, _, _, _, _ -> 100.0 * wn / maxOf(1, g) }, { "%.0f".format(it) })
    val kda = StatCol("KDA", { _, _, _, _, s, idx -> (s[idx("k")] + s[idx("a")]) / maxOf(1.0, s[idx("d")]) }, { "%.2f".format(it) })
    val mvp = StatCol("MVP", { _, _, _, m, _, _ -> m.toDouble() }, { "%.0f".format(it) })
    return if (t.isMoba) listOf(
        "Overview" to listOf(gp, wr, rating, kda, per("k").let { StatCol("K/g", it.v, it.fmt) }, per("d").let { StatCol("D/g", it.v, it.fmt) }, per("a").let { StatCol("A/g", it.v, it.fmt) }, mvp),
        "Combat" to listOf(gp, StatCol("Dmg/g", per("dmg").v) { "%.0f".format(it) }, StatCol("Taken/g", per("taken").v) { "%.0f".format(it) }, StatCol("Heal/g", per("heal").v) { "%.0f".format(it) }, tot("k", "Kills"), tot("d", "Deaths"), tot("a", "Assists")),
        "Economy & map" to listOf(gp, StatCol("Gold/g", per("gold").v) { "%.0f".format(it) }, StatCol("CS/g", per("cs").v) { "%.0f".format(it) }, StatCol("Vision/g", per("vision").v) { "%.1f".format(it) }, tot("obj", "Obj"), tot("towers", "Struct")),
    ) else listOf(
        "Overview" to listOf(gp, wr, rating, kda, per("k").let { StatCol("K/map", it.v, it.fmt) }, per("d").let { StatCol("D/map", it.v, it.fmt) }, per("a").let { StatCol("A/map", it.v, it.fmt) }, mvp),
        "Duels" to listOf(gp, tot("fk", "FK"), tot("fd", "FD"), StatCol("HS%", { _, _, _, _, s, idx -> 100 * s[idx("hs")] / maxOf(1.0, s[idx("k")]) }) { "%.0f".format(it) }, tot("clutch", "Clutch"), tot("mk", "Multi"), StatCol("Dmg/map", per("dmg").v) { "%.0f".format(it) }),
        "Utility & site" to listOf(gp, tot("util", "Util"), tot("plant", "Plants"), tot("defuse", "Defuses"), tot("kast", "KAST rds")),
    )
}

@Composable
private fun CompStatsView(vm: GameVm, w: World, c: Competition, t: TitleDef) {
    var g by remember { mutableIntStateOf(0) }
    var sortCol by remember { mutableIntStateOf(2) }
    val gs = groups(t)
    val cols = gs[g].second
    val idx: (String) -> Int = { t.statIndex[it] ?: -1 }
    val rows = c.pstats.entries.filter { it.value.gp > 0 }.map { (pid, s) -> Triple(pid, s, cols.map { col -> col.v(s.gp, s.w, s.r, s.m, s.v, idx) }) }
        .sortedByDescending { it.third.getOrNull(minOf(sortCol, cols.size - 1)) ?: 0.0 }
    Card("Player stats") {
        Tabs(gs.map { it.first }, g) { g = it; sortCol = minOf(sortCol, gs[it].second.size - 1) }
        Muted("Tap a column title to sort")
        val scroll = rememberScrollState()
        Column(Modifier.horizontalScroll(scroll)) {
            Row {
                Text("Player", color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(150.dp))
                cols.forEachIndexed { i, col -> Text(col.title + if (i == sortCol) " ▾" else "", color = if (i == sortCol) Ui.accent else Ui.muted, fontSize = 11.sp, modifier = Modifier.width(62.dp).clickable { sortCol = i }) }
            }
            for ((pid, s, vals) in rows.take(150)) {
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(150.dp)) { PlayerChip(w.players[pid], vm, 20.dp); Muted(w.teams[s.teamId].short, 10) }
                    vals.forEachIndexed { i, v -> Text(v?.let { cols[i].fmt(it) } ?: "—", color = if (i == sortCol) Ui.text else Ui.muted, fontSize = 13.sp, modifier = Modifier.width(62.dp)) }
                }
            }
        }
    }
}

@Composable
fun TeamsScreen(vm: GameVm, w: World) {
    var title by remember { mutableStateOf<String?>(w.teams.firstOrNull { w.isUserTeam(it.id) }?.titleId) }
    val leagues = w.leagues.filter { title == null || it.titleId == title }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Tabs(TITLE_TABS, titleIdx(title)) { title = if (it == 0) null else Catalogue.IDS[it - 1] } }
        for (l in leagues) item(key = "l${l.id}") {
            Card(l.name, right = { Pic(Pics.league(l), l.short, 22.dp) }) {
                for (tid in l.teamIds.sortedByDescending { w.teams[it].elo }) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { TeamChip(w, tid, vm) }
                    Muted("Elo ${w.teams[tid].elo.toInt()}")
                }
            }
        }
    }
}

@Composable
fun TeamScreen(vm: GameVm, w: World, id: Int) {
    val team = w.teams[id]
    val t = Catalogue.get(team.titleId)
    val org = w.orgs[team.orgId]
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Pic(Pics.team(team), team.short, 56.dp)
                HGap(12)
                Column {
                    Text(team.name, color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Muted("${t.name} · ${team.leagueId.takeIf { it >= 0 }?.let { w.leagues[it].name } ?: if (team.active) "no league" else "inactive (no league place)"}")
                    Muted("${org.name} · Elo ${team.elo.toInt()} · strength ${"%.1f".format(Lineups.strength(w, team))}${if (team.real) " · real team" else ""}")
                }
            }
        }
        item {
            Card("Roster") {
                val roster = team.roster.map { w.players[it] }.sortedBy { p -> t.lineup.indexOf(p.roles[0]).let { if (it < 0) 9 else it } }
                Table(listOf(Col("Player", 150.dp, false), Col("Pos", 48.dp), Col("Age", 40.dp), Col("Nat", 40.dp), Col("CA", 48.dp), Col("Start", 48.dp)), roster.size, onRow = { vm.go(Screen.Player(roster[it].id)) }) { r, c ->
                    val p = roster[r]
                    when (c) {
                        0 -> PlayerChip(p, vm, 22.dp)
                        1 -> Num(t.role(p.roles[0]).short, Ui.muted)
                        2 -> Num("${Dates.ageAt(p.birth, w.day)}")
                        3 -> Num(p.nat, Ui.muted)
                        4 -> Num("%.1f".format(p.ca), bold = true)
                        else -> Num(if (p.id in team.lineup) "✓" else "", Ui.win)
                    }
                }
            }
        }
        item {
            Card("Results") {
                val fx = w.fixtures.filter { it.done && (it.a == id || it.b == id) }.sortedByDescending { it.day }.take(20)
                if (fx.isEmpty()) Muted("No matches played yet.")
                for (f in fx) {
                    val won = f.winner == id
                    val opp = if (f.a == id) f.b else f.a
                    Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Match(f.id)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (won) "W" else "L", color = if (won) Ui.win else Ui.loss, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                        Text(if (f.a == id) "${f.scoreA}-${f.scoreB}" else "${f.scoreB}-${f.scoreA}", color = Ui.text, modifier = Modifier.width(36.dp))
                        Column(Modifier.weight(1f)) { TeamChip(w, opp, vm, 18.dp) }
                        Muted(w.comps[f.compId].short, 11)
                    }
                }
            }
        }
        if (team.trophies.isNotEmpty()) item { Card("Trophies") { for (tr in team.trophies.reversed()) Text("🏆 ${tr.name}", color = Ui.gold, fontSize = 13.sp) } }
        if (team.history.isNotEmpty()) item { Card("Seasons") { for (h in team.history.reversed()) Muted("${h.season}: ${h.w}-${h.l}, final split ${if (h.position > 0) "#${h.position}" else "—"}, Elo ${h.elo.toInt()}", 13) } }
    }
}

@Composable
fun PlayersScreen(vm: GameVm, w: World) {
    var q by remember { mutableStateOf("") }
    var title by remember { mutableStateOf<String?>(w.teams.firstOrNull { w.isUserTeam(it.id) }?.titleId) }
    var freeOnly by remember { mutableStateOf(false) }
    val list = remember(q, title, freeOnly, vm.version) {
        w.players.asSequence().filter { !it.retired && (title == null || it.titleId == title) && (!freeOnly || it.teamId < 0) && (q.isBlank() || it.handle.contains(q, true) || "${it.first} ${it.last}".contains(q, true)) }
            .sortedByDescending { it.ca }.take(200).toList()
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(q, { q = it }, label = { Text("Search players") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        Tabs(TITLE_TABS, titleIdx(title)) { title = if (it == 0) null else Catalogue.IDS[it - 1] }
        Tabs(listOf("All players", "Free agents"), if (freeOnly) 1 else 0) { freeOnly = it == 1 }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Player(p.id)) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pic(Pics.player(p), p.handle, 34.dp, round = true)
                    HGap(10)
                    Column(Modifier.weight(1f)) {
                        Text(p.handle, color = Ui.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted("${Catalogue.get(p.titleId).short} ${Catalogue.get(p.titleId).role(p.roles[0]).short} · ${p.nat} · ${Dates.ageAt(p.birth, w.day)} · ${p.teamId.takeIf { it >= 0 }?.let { w.teams[it].short } ?: "free agent"}")
                    }
                    Num("%.1f".format(p.ca), bold = true)
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(vm: GameVm, w: World, id: Int) {
    val p = w.players[id]
    val t = Catalogue.get(p.titleId)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Pic(Pics.player(p), p.handle, 72.dp, round = true)
                HGap(14)
                Column {
                    Text(p.handle, color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    if (p.first.isNotBlank() || p.last.isNotBlank()) Muted(if (GameData.isEastern(p.nat)) "${p.last} ${p.first}" else "${p.first} ${p.last}", 13)
                    Muted("${t.short} ${t.role(p.roles[0]).name} · ${GameData.countries[p.nat]?.name ?: p.nat} · age ${Dates.ageAt(p.birth, w.day)} (born ${Dates.format(p.birth)})", 12)
                    Row(verticalAlignment = Alignment.CenterVertically) { if (p.teamId >= 0) TeamChip(w, p.teamId, vm, 18.dp) else Muted("Free agent") }
                }
            }
        }
        item { PlayerActions(vm, w, p) }
        item {
            Card("Ability") {
                Row { Text("CA %.1f".format(p.ca), color = Ui.text, fontWeight = FontWeight.Bold); HGap(16); Muted("potential up to %.0f".format(p.pa), 13); HGap(16); Muted("form %+.1f".format(p.form), 13) }
                Gap()
                for ((label, v) in dev.uem.em.model.Attrs.summaries(p.attrs).filter { (k, _) -> if (t.isMoba) k != "Shooter" else k != "MOBA" }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(label, color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                        Row(Modifier.weight(1f).background(Ui.card2, RoundedCornerShape(4.dp))) { Row(Modifier.fillMaxWidth((v / 100).toFloat().coerceIn(0.02f, 1f)).background(Ui.accent, RoundedCornerShape(4.dp)).padding(vertical = 4.dp)) {} }
                        Text("%.0f".format(v), color = Ui.text, fontSize = 12.sp, modifier = Modifier.width(32.dp).padding(start = 6.dp))
                    }
                }
                if (p.real) { Gap(6); Muted("Real player (snapshot). Estimated by the game: ${p.realEst.joinToString().ifBlank { "nothing" }}. Ratings are game simulation.", 11) }
            }
        }
        item {
            Card("By competition") {
                val rows = w.comps.filter { p.id in it.pstats }.sortedByDescending { it.startDay }
                if (rows.isEmpty()) Muted("No games yet.")
                val k = t.statIndex["k"] ?: 0
                val d = t.statIndex["d"] ?: 1
                val a = t.statIndex["a"] ?: 2
                Table(listOf(Col("Competition", 150.dp, false), Col("GP", 36.dp), Col("W%", 40.dp), Col("Rating", 52.dp), Col("K/D/A", 92.dp), Col("KDA", 48.dp), Col("MVP", 38.dp)), rows.size, onRow = { vm.go(Screen.Comp(rows[it].id)) }) { r, c ->
                    val s = rows[r].pstats[p.id]!!
                    when (c) {
                        0 -> Text(rows[r].short, color = Ui.text, fontSize = 13.sp, maxLines = 1)
                        1 -> Num("${s.gp}")
                        2 -> Num("${100 * s.w / maxOf(1, s.gp)}")
                        3 -> Num("%.2f".format(s.r / maxOf(1, s.gp)), bold = true)
                        4 -> Num("%.1f/%.1f/%.1f".format(s.v[k] / s.gp, s.v[d] / s.gp, s.v[a] / s.gp))
                        5 -> Num("%.2f".format((s.v[k] + s.v[a]) / maxOf(1.0, s.v[d])))
                        else -> Num("${s.m}")
                    }
                }
            }
        }
        item {
            Card("Career") {
                for (e in p.career.reversed()) Muted("${e.season}: ${e.type} · ${e.teamId.takeIf { it >= 0 }?.let { w.teams[it].name } ?: "—"}", 13)
                if (p.career.isEmpty()) Muted("—")
            }
        }
    }
}

@Composable
fun MatchScreen(vm: GameVm, w: World, id: Int) {
    val f = w.fixtures[id]
    val t = Catalogue.get(f.titleId)
    val rec = remember(id, vm.version) { BoxStore.get(w, id) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card {
                Muted("${w.comps[f.compId].name} · ${Dates.format(f.day, true)} · Bo${f.bestOf}")
                Gap()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { TeamChip(w, f.a, vm, 28.dp, bold = f.winner == f.a) }
                    Text("${f.scoreA} - ${f.scoreB}", color = Ui.text, fontWeight = FontWeight.Black, fontSize = 22.sp)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) { TeamChip(w, f.b, vm, 28.dp, bold = f.winner == f.b) }
                }
                if (f.mvp >= 0) { Gap(6); Muted("Series MVP: ${w.players[f.mvp].handle}") }
            }
        }
        if (rec == null) item { Card { Muted(if (f.done) "Box scores are not available for this match." else "Not played yet.") } }
        rec?.games?.forEachIndexed { gi, g ->
            item(key = "g$gi") {
                val teams = intArrayOf(f.a, f.b)
                val sideNames = if (t.id == "dota2") "Radiant" to "Dire" else if (t.isMoba) "Blue" to "Red" else t.tactical!!.sides
                Card("Game ${gi + 1}${if (!t.isMoba) " · ${t.maps.firstOrNull { it.id == g.mapId }?.name ?: g.mapId}" else ""} · ${w.teams[teams[g.winner]].short} win", right = { Muted(if (t.isMoba) "%d:%02d".format(g.duration / 60, g.duration % 60) else "${g.score[0]}-${g.score[1]}") }) {
                    if (t.isMoba && g.sel.size == 3) {
                        val chooser = w.teams[teams[g.sel[0]]].short
                        val how = if (g.sel[2] == 0) "won the coin toss" else "lost the previous game"
                        val choice = if (g.sel[1] == 0) "chose ${sideNames.first} side" else "chose pick order"
                        Muted("$chooser ($how) $choice · ${w.teams[teams[g.blue]].short} on ${sideNames.first}", 12)
                    }
                    if (g.bans.isNotEmpty()) {
                        Gap(4)
                        Muted("Bans", 11)
                        Row(Modifier.horizontalScroll(rememberScrollState())) { for (b in g.bans) { Pic(Pics.char(b), t.charIndex[b]?.name ?: b, 24.dp); HGap(3) } }
                    }
                    Gap(6)
                    // Box score: the blue / first side's team on top.
                    for (side in if (g.blue == 0) intArrayOf(0, 1) else intArrayOf(1, 0)) {
                        val lines = g.lines.filter { it.team == side }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (t.isMoba) (if (side == g.blue) sideNames.first else sideNames.second) else "", color = if (side == g.blue) Ui.blue else Ui.red, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))
                            TeamChip(w, teams[side], vm, 16.dp, bold = side == g.winner)
                        }
                        BoxScore(vm, w, t, lines)
                        Gap(8)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScore(vm: GameVm, w: World, t: TitleDef, lines: List<Line>) {
    val keys = if (t.isMoba) listOf("k", "d", "a", "gold", "cs", "dmg", "vision") else listOf("k", "d", "a", "fk", "hs", "kast", "dmg")
    val cols = listOf(Col("Player", 150.dp, false)) + keys.map { k -> Col(t.stats[t.statIndex[k]!!].label, if (k == "gold" || k == "dmg") 60.dp else 40.dp) } + Col("Rtg", 44.dp)
    Table(cols, lines.size, onRow = { vm.go(Screen.Player(lines[it].playerId)) }) { r, c ->
        val l = lines[r]
        when {
            c == 0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.characters.isNotEmpty() && l.charId.isNotEmpty()) { Pic(Pics.char(l.charId), t.charIndex[l.charId]?.name ?: l.charId, 22.dp); HGap(4) }
                Column {
                    Text(w.players[l.playerId].handle, color = Ui.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted(listOfNotNull(t.role(l.roleId).short, t.charIndex[l.charId]?.name).joinToString(" · "), 10)
                }
            }
            c <= keys.size -> {
                val v = l.s[t.statIndex[keys[c - 1]]!!]
                Num(if (keys[c - 1] == "gold" && v >= 1000) "%.1fk".format(v / 1000) else if (keys[c - 1] == "dmg" && v >= 10000) "%.1fk".format(v / 1000) else "%.0f".format(v))
            }
            else -> Num("%.2f".format(l.rating), if (l.rating >= 1.2) Ui.win else Ui.text, bold = true)
        }
    }
}

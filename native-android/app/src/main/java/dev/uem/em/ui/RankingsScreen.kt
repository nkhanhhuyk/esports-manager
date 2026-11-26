package dev.uem.em.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.ordinal
import dev.uem.em.data.GameData
import dev.uem.em.mgmt.Rankings
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue

private val RANK_TABS = listOf("Clubs", "Countries & territories", "Organisations")

/** World rankings: clubs (per game, organisations over all games) and countries / territories. */
@Composable
fun RankingsScreen(vm: GameVm, w: World) {
    var tab by remember { mutableIntStateOf(0) }
    val titles = w.titles
    var game by remember { mutableIntStateOf((titles.indexOf(w.teams.firstOrNull { w.isUserTeam(it.id) }?.titleId) + 1).coerceAtLeast(0)) }
    var all by remember { mutableStateOf(false) }
    val titleId = titles.getOrNull(game - 1)
    val day = w.day
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Muted("Points from results: every finished event scores by placing, scaled by its prestige.", 12)
            }
        }
        item { Tabs(RANK_TABS, tab) { tab = it } }
        item { Row(Modifier.padding(horizontal = 12.dp)) { Select("Game", listOf("All games") + titles.map { Catalogue.get(it).name }, game) { game = it } } }
        val limit = if (all) Int.MAX_VALUE else 50
        when {
            tab == 0 && titleId != null -> {
                item {
                    val rows = remember(day, titleId) { Rankings.clubs(w, titleId) }
                    Card("Club ranking · ${Catalogue.get(titleId).name}") {
                        if (rows.all { it.points == 0.0 }) Muted("No results in the last 12 months yet: ordered by strength (Elo) until the first events finish.", 11)
                        Table(listOf(Col("#", 30.dp), Col("Team", 170.dp, false), Col("League", 70.dp, false), Col("Points", 60.dp), Col("Best results", 220.dp, false)), minOf(limit, rows.size),
                            onRow = { vm.go(Screen.Team(rows[it].teamId)) }, highlight = { w.isUserTeam(rows[it].teamId) }) { r, c ->
                            val row = rows[r]
                            val tm = w.teams[row.teamId]
                            when (c) {
                                0 -> Text("${r + 1}", color = if (r < 3) Ui.gold else Ui.muted, fontSize = 12.sp)
                                1 -> TeamChip(w, row.teamId, vm, 18.dp)
                                2 -> Muted(tm.leagueId.takeIf { it >= 0 }?.let { w.leagues[it].short } ?: GameData.regionShort(tm.region), 11)
                                3 -> Num("%,.0f".format(row.points), bold = true)
                                else -> Muted(row.best.joinToString(" · ") { (cid, _) -> val cc = w.comps[cid]; "${ordinal(cc.placements.indexOf(row.teamId) + 1)} ${cc.short}" }.ifEmpty { "—" }, 11)
                            }
                        }
                        if (!all && rows.size > limit) TextButton(onClick = { all = true }) { Text("Show all ${rows.size}", color = Ui.accent) }
                    }
                }
            }
            tab == 0 -> {
                item {
                    val rows = remember(day) { Rankings.orgs(w, titles) }
                    Card("Organisations · all games") {
                        if (rows.all { it.points == 0.0 }) Muted("No results in the last 12 months yet: ordered by their strongest team until the first events finish.", 11)
                        Table(listOf(Col("#", 30.dp), Col("Organisation", 180.dp, false), Col("Points", 64.dp), Col("Games", 150.dp, false)), minOf(limit, rows.size),
                            onRow = { vm.go(Screen.Org(rows[it].orgId)) }, highlight = { rows[it].orgId == w.userOrg }) { r, c ->
                            val row = rows[r]
                            when (c) {
                                0 -> Text("${r + 1}", color = if (r < 3) Ui.gold else Ui.muted, fontSize = 12.sp)
                                1 -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    row.teams.maxByOrNull { w.teams[it].elo }?.let { TeamPic(w.teams[it], 18.dp) }
                                    Spacer(Modifier.width(6.dp))
                                    OrgLink(w, row.orgId, size = 13.sp)
                                }
                                2 -> Num("%,.0f".format(row.points), bold = true)
                                else -> Muted(row.teams.map { Catalogue.get(w.teams[it].titleId).short }.distinct().joinToString(" · "), 11)
                            }
                        }
                        if (!all && rows.size > limit) TextButton(onClick = { all = true }) { Text("Show all ${rows.size}", color = Ui.accent) }
                    }
                }
            }
            tab == 2 -> item { OrgRankingCard(vm, w) }
            else -> {
                item {
                    val rows = remember(day, titleId) { if (titleId != null) Rankings.nations(w, titleId) else Rankings.nationsAll(w, titles) }
                    Card("Countries & territories · ${titleId?.let { Catalogue.get(it).name } ?: "all games"}") {
                        if (rows.all { it.points == 0.0 }) Muted("No results yet: ordered by the strength of each country's best players until the first events finish.", 11)
                        Table(listOf(Col("#", 30.dp), Col("Country / territory", 170.dp, false), Col("Points", 60.dp), Col("National", 64.dp), Col("Club", 56.dp), Col("Pros", 42.dp), Col("Squad", 50.dp)), minOf(limit, rows.size),
                            onRow = { rows[it].teamId.takeIf { id -> id >= 0 }?.let { id -> vm.go(Screen.Team(id)) } }) { r, c ->
                            val row = rows[r]
                            when (c) {
                                0 -> Text("${r + 1}", color = if (r < 3) Ui.gold else Ui.muted, fontSize = 12.sp)
                                1 -> Text("${flag(row.nat)} ${GameData.countries[row.nat]?.name ?: row.nat}", color = Ui.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = if (row.teamId >= 0) Modifier.clickable { vm.go(Screen.Team(row.teamId)) } else Modifier)
                                2 -> Num("%,.0f".format(row.points), bold = true)
                                3 -> Num("%,.0f".format(row.national), Ui.muted)
                                4 -> Num("%,.0f".format(row.players), Ui.muted)
                                5 -> Num("${row.pros}", Ui.muted)
                                else -> if (row.strength > 0) Rating(row.strength, 0, 12) else Muted("—", 11)
                            }
                        }
                        if (!all && rows.size > limit) TextButton(onClick = { all = true }) { Text("Show all ${rows.size}", color = Ui.accent) }
                        Muted("National = the national team's Esports Nations Cup and qualifier results (two years). Club = a quarter of the club ranking points earned by the country's players. Pros = players at a club. Squad = the best five players' rating. Tap a country for its national team (after its first qualifier).", 10)
                    }
                }
            }
        }
        item {
            Card("How the points work") {
                Muted("Each finished competition gives prestige × 10 points to the winner, 70 % to the runner-up, 50 % to 3rd–4th, 30 % to 5th–8th, 15 % to 9th–16th and 5 % to the rest. Prestige: World Championship 100, Esports World Cup 80, Nations Cup 75, mid-season event 70, First Stand 60, top leagues 25–55 per split, Nations Cup qualifiers 30, second divisions 12.", 11)
                Muted("Clubs: the last 12 months, results older than six months at half value. Countries and territories: two years, the older year at half value.", 11)
            }
        }
    }
}

/**
 * Organisations across the seven games: a window (this season, last season, the last three, all time) and a
 * mode (total achievement, per-title average, development index), with each organisation's departments,
 * points per game and best results.
 */
@Composable
private fun OrgRankingCard(vm: GameVm, w: World) {
    val windows = listOf("This season", "Last season", "Last 3 seasons", "All time")
    val modes = listOf("Total achievement", "Per-title average", "Development index")
    var wi by remember { mutableIntStateOf(2) }
    var mi by remember { mutableIntStateOf(0) }
    val (from, to) = when (wi) { 0 -> w.season to w.season; 1 -> (w.season - 1) to (w.season - 1); 2 -> (w.season - 2) to w.season; else -> 0 to w.season }
    val rows = remember(wi, mi, w.day) { dev.uem.em.comp.OrgRankings.sorted(dev.uem.em.comp.OrgRankings.table(w, from, to), mi).filter { it.total + it.development > 0 }.take(60) }
    Card("Organisation ranking") {
        Row { Select("", windows, wi) { wi = it }; Select("", modes, mi) { mi = it } }
        Muted("World championship 100 points for a title (60 final, 35 semi-final), internationals by prestige, league titles 25; academy and second-tier results only in the development index. Per-title average divides by the games the organisation plays, so size alone does not win.", 10)
        if (rows.isEmpty()) Muted("No finished events in this window yet.", 11)
        rows.forEachIndexed { i, r ->
            Row(Modifier.clickable { vm.go(Screen.Org(r.orgId)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = if (i < 3) Ui.gold else Ui.muted, fontSize = 12.sp, modifier = Modifier.width(26.dp))
                Column(Modifier.weight(1f)) {
                    Text(w.orgs[r.orgId].name, color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Muted(r.byTitle.entries.sortedByDescending { it.value }.joinToString(" · ") { "${Catalogue.get(it.key).short} ${it.value.toInt()}" }.ifEmpty { "${r.titles.size} games" } +
                        (if (r.best.isNotEmpty()) " · best: " + r.best.joinToString { w.comps[it].short } else ""), 10)
                }
                Text("${(when (mi) { 1 -> r.perTitle; 2 -> r.development; else -> r.total }).toInt()}", color = Ui.gold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}


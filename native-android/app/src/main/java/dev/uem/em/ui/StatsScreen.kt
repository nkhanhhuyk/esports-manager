package dev.uem.em.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
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
import dev.uem.em.core.Dates
import dev.uem.em.core.formatMoney
import dev.uem.em.mgmt.Legacy
import dev.uem.em.model.CompTier
import dev.uem.em.model.Player
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import kotlin.math.pow

private val STATS_TABS = listOf("Leaderboards", "Records", "Head-to-head", "Season")

@Composable
fun StatsScreen(vm: GameVm, w: World) {
    var title by remember { mutableStateOf(w.orgs.getOrNull(w.userOrg)?.teams?.firstOrNull()?.let { w.teams[it].titleId } ?: "lol") }
    var tab by remember { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Tabs(Catalogue.IDS.map { Catalogue.get(it).short }, Catalogue.IDS.indexOf(title)) { title = Catalogue.IDS[it] } }
        item { Tabs(STATS_TABS, tab) { tab = it } }
        item {
            when (tab) {
                0 -> Leaderboards(vm, w, title)
                1 -> Records(vm, w, title)
                2 -> HeadToHead(vm, w, title)
                else -> SeasonView(vm, w, title)
            }
            Gap(16)
        }
    }
}

private class Agg(val p: Player, val gp: Int, val wins: Int, val rating: Double, val mvps: Int, val s: DoubleArray, val teamId: Int)

@Composable
private fun Leaderboards(vm: GameVm, w: World, title: String) {
    val t = Catalogue.get(title)
    val seasons = (w.players.flatMap { p -> p.stats.filter { it.titleId == title }.map { it.season } } + w.season).distinct().sortedDescending()
    var si by remember(title) { mutableIntStateOf(0) }
    var role by remember(title) { mutableIntStateOf(-1) }
    var minGp by remember { mutableIntStateOf(1) }
    val season = seasons.getOrElse(si) { w.season }
    val rows = remember(title, season, vm.version) {
        w.players.mapNotNull { p ->
            val lines = p.stats.filter { it.season == season && it.titleId == title }
            val gp = lines.sumOf { it.gp }
            if (gp == 0) return@mapNotNull null
            val s = DoubleArray(t.stats.size)
            for (l in lines) for (i in s.indices) s[i] += l.s.getOrElse(i) { 0.0 }
            Agg(p, gp, lines.sumOf { it.w }, lines.sumOf { it.ratingSum } / gp, lines.sumOf { it.mvps }, s, lines.maxByOrNull { it.gp }!!.teamId)
        }
    }
    val maxGp = rows.maxOfOrNull { it.gp } ?: 0
    val minNeeded = listOf(1, (maxGp * 0.25).toInt(), (maxGp * 0.5).toInt())[minGp.coerceIn(0, 2)].coerceAtLeast(1)
    val shown = rows.filter { it.gp >= minNeeded && (role < 0 || it.p.roles[0] == t.lineup[role]) }
    val k = t.statIndex["k"]
    val d = t.statIndex["d"]
    val a = t.statIndex["a"]
    Card("Leaderboards · ${t.short} $season", right = { Muted("${shown.size} players", 11) }) {
        Row {
            Select("Season", seasons.map { "$it" }, si) { si = it }
            Select("Min. games", listOf("any", "$minNeeded+ (25%)", "50%"), minGp) { minGp = it }
        }
        Tabs(listOf("All") + t.lineup.map { t.role(it).short }, role + 1) { role = it - 1 }
        SortTable(shown, listOf<SCol<Agg>>(
            SCol("Player", 130.dp, false) { r -> Column { PlayerChip(r.p, vm, 18.dp); Muted("${w.teams.getOrNull(r.teamId)?.short ?: "—"} · ${t.role(r.p.roles[0]).short}", 10) } },
            SCol("GP", 34.dp, key = { it.gp.toDouble() }) { Num("${it.gp}") },
            SCol("Win%", 44.dp, key = { it.wins.toDouble() / it.gp }) { Num("${100 * it.wins / it.gp}") },
            SCol("Rating", 50.dp, key = { it.rating }) { Num("%.2f".format(it.rating), bold = true) },
        ) + (if (k != null && d != null && a != null) listOf(SCol<Agg>("KDA", 44.dp, key = { (it.s[k] + it.s[a]) / maxOf(1.0, it.s[d]) }) { Num("%.2f".format((it.s[k] + it.s[a]) / maxOf(1.0, it.s[d]))) }) else emptyList()) +
            t.stats.mapIndexed { i, st -> SCol<Agg>(st.label + "/g", 56.dp, key = { it.s[i] / it.gp }) { r -> Num(if (r.s[i] / r.gp >= 1000) "%.1fk".format(r.s[i] / r.gp / 1000) else "%.1f".format(r.s[i] / r.gp)) } } +
            SCol("MVP", 38.dp, key = { it.mvps.toDouble() }) { Num("${it.mvps}") },
            initialSort = 3, limit = 150, onRow = { vm.go(Screen.Player(it.p.id)) }, highlight = { w.isUserTeam(it.p.teamId) })
    }
    val teams = w.teams.filter { it.active && it.titleId == title && it.leagueId >= 0 }.sortedByDescending { it.elo }
    Card("Power ranking (Elo)") {
        teams.take(25).forEachIndexed { i, team ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(26.dp))
                Column(Modifier.weight(1f)) { TeamChip(w, team.id, vm, 18.dp) }
                Muted(w.leagues[team.leagueId].short + "  ", 10)
                Num("${team.elo.toInt()}")
            }
        }
    }
}

@Composable
private fun <T> RecordList(title: String, rows: List<T>, label: @Composable (T) -> Unit, value: (T) -> String) {
    Card(title) {
        if (rows.isEmpty()) Muted("No entries yet.")
        rows.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = if (i == 0) Ui.gold else Ui.muted, fontSize = 12.sp, modifier = Modifier.width(24.dp))
                Column(Modifier.weight(1f)) { label(r) }
                Text(value(r), color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Records(vm: GameVm, w: World, title: String) {
    val data = remember(title, vm.version) {
        val comps = w.comps.filter { it.titleId == title && it.done && it.placements.isNotEmpty() }
        val worlds = comps.filter { it.tier == CompTier.WORLDS }.groupingBy { it.placements[0] }.eachCount().entries.sortedByDescending { it.value }.take(10)
        val trophies = w.teams.filter { it.titleId == title && it.trophies.isNotEmpty() }.sortedByDescending { it.trophies.size }.take(10)
        val mvps = w.awards.filter { it.titleId == title && it.name.contains("MVP") && it.playerId >= 0 }.groupingBy { it.playerId }.eachCount().entries.sortedByDescending { it.value }.take(10)
        val players = w.players.filter { p -> p.stats.any { it.titleId == title } }
        val best = players.flatMap { p -> p.stats.filter { it.titleId == title && it.gp >= 20 }.map { p to it } }.sortedByDescending { it.second.ratingSum / it.second.gp }.take(10)
        val games = players.map { p -> Triple(p, p.stats.filter { it.titleId == title }.sumOf { it.gp }, p.stats.filter { it.titleId == title }.sumOf { it.w }) }.sortedByDescending { it.second }.take(10)
        val fees = w.transfers.filter { it.fee > 0 && w.players[it.playerId].titleId == title }.sortedByDescending { it.fee }.take(10)
        val legacy = players.map { it to Legacy.of(w, it).total }.sortedByDescending { it.second }.take(10)
        val orgs = comps.filter { it.tier == CompTier.WORLDS }.groupingBy { w.teams[it.placements[0]].orgId }.eachCount().entries.sortedByDescending { it.value }.take(10)
        listOf(worlds, trophies, mvps, best, games, fees, legacy, orgs)
    }
    @Suppress("UNCHECKED_CAST")
    run {
        RecordList("Most world titles (team)", data[0] as List<Map.Entry<Int, Int>>, { TeamChip(w, it.key, vm, 18.dp) }) { "${it.value}" }
        RecordList("Most world titles (organisation)", data[7] as List<Map.Entry<Int, Int>>, { Text(w.orgs[it.key].name, color = Ui.text, fontSize = 13.sp) }) { "${it.value}" }
        RecordList("Most trophies (team)", data[1] as List<dev.uem.em.model.Team>, { TeamChip(w, it.id, vm, 18.dp) }) { "${it.trophies.size}" }
        RecordList("Most MVP awards", data[2] as List<Map.Entry<Int, Int>>, { PlayerChip(w.players[it.key], vm, 18.dp) }) { "${it.value}" }
        RecordList("Best season rating (min. 20 games)", data[3] as List<Pair<Player, dev.uem.em.model.SeasonLine>>, { (p, l) -> Row(verticalAlignment = Alignment.CenterVertically) { PlayerChip(p, vm, 18.dp); Muted("  ${l.season} · ${w.teams.getOrNull(l.teamId)?.short ?: ""}", 10) } }) { "%.2f".format(it.second.ratingSum / it.second.gp) }
        RecordList("Most games (since the save began)", data[4] as List<Triple<Player, Int, Int>>, { PlayerChip(it.first, vm, 18.dp) }) { "${it.second} (${100 * it.third / maxOf(1, it.second)}% won)" }
        RecordList("Biggest transfer fees", data[5] as List<dev.uem.em.model.Transfer>, { x -> Row(verticalAlignment = Alignment.CenterVertically) { PlayerChip(w.players[x.playerId], vm, 18.dp); Muted("  ${x.season} · ${w.teams.getOrNull(x.fromTeam)?.short ?: "—"} → ${w.teams.getOrNull(x.toTeam)?.short ?: "—"}", 10) } }) { formatMoney(it.fee) }
        RecordList("Greatest legacies", data[6] as List<Pair<Player, Double>>, { PlayerChip(it.first, vm, 18.dp) }) { "%.0f".format(it.second) }
    }
}

@Composable
private fun HeadToHead(vm: GameVm, w: World, title: String) {
    val teams = w.teams.filter { it.titleId == title && (it.active || it.trophies.isNotEmpty()) }.sortedByDescending { it.elo }
    if (teams.size < 2) { Card { Muted("Not enough teams.") }; return }
    val mine = teams.indexOfFirst { w.isUserTeam(it.id) }.takeIf { it >= 0 } ?: 0
    var ai by remember(title) { mutableIntStateOf(mine) }
    var bi by remember(title) { mutableIntStateOf(if (mine == 0) 1 else 0) }
    val ta = teams[ai.coerceIn(0, teams.size - 1)]
    val tb = teams[bi.coerceIn(0, teams.size - 1)]
    val fx = w.fixtures.filter { it.done && ((it.a == ta.id && it.b == tb.id) || (it.a == tb.id && it.b == ta.id)) }.sortedByDescending { it.day }
    val aw = fx.count { it.winner == ta.id }
    val ga = fx.sumOf { if (it.a == ta.id) it.scoreA else it.scoreB }
    val gb = fx.sumOf { if (it.a == ta.id) it.scoreB else it.scoreA }
    Card("Head-to-head") {
        Row { Select("", teams.map { it.name }, ai) { ai = it }; Text("vs", color = Ui.muted, modifier = Modifier.padding(top = 6.dp, end = 6.dp)); Select("", teams.map { it.name }, bi) { bi = it } }
        Gap(6)
        Tiles(listOf(
            Tile("Series", "$aw – ${fx.size - aw}", "${ta.short} vs ${tb.short}"),
            Tile(if (Catalogue.get(title).isMoba) "Games" else "Maps", "$ga – $gb"),
            Tile("Elo", "${ta.elo.toInt()} / ${tb.elo.toInt()}", "win chance ${(100 / (1 + 10.0.pow((tb.elo - ta.elo) / 400))).toInt()}%"),
        ))
        if (fx.isEmpty()) Muted("They have not met since the save began.")
        for (f in fx) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Match(f.id)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(78.dp)) { Text(Dates.short(f.day), color = Ui.text, fontSize = 12.sp); Muted("${w.comps[f.compId].season}", 10) }
            Text(w.comps[f.compId].short, color = Ui.muted, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(w.teams[f.winner].short, color = if (f.winner == ta.id) Ui.win else Ui.loss, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("  ${maxOf(f.scoreA, f.scoreB)}-${minOf(f.scoreA, f.scoreB)}", color = Ui.text, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SeasonView(vm: GameVm, w: World, title: String) {
    val t = Catalogue.get(title)
    var review by remember { mutableIntStateOf(0) }
    val seasons = w.comps.map { it.season }.distinct().sortedDescending()
    var si by remember { mutableIntStateOf(0) }
    val season = seasons.getOrElse(si) { w.season }
    Row(Modifier.padding(horizontal = 12.dp)) { Select("Season", seasons.map { "$it" }, si) { si = it }; Tabs(listOf("Preview", "Review"), review) { review = it } }
    if (review == 0) {
        val teams = w.teams.filter { it.active && it.titleId == title && it.leagueId >= 0 }
        Card("World championship favourites") {
            val top = teams.sortedByDescending { it.elo }.take(8)
            val sum = top.sumOf { 10.0.pow(it.elo / 400) }
            top.forEachIndexed { i, tm ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(22.dp))
                    Column(Modifier.weight(1f)) { TeamChip(w, tm.id, vm, 18.dp) }
                    Text("${(100 * 10.0.pow(tm.elo / 400) / sum).toInt()}%", color = Ui.gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Muted("Share of title chances among the top eight by Elo.", 10)
        }
        Card("Players to watch · breakout candidates") {
            val young = w.players.filter { !it.retired && it.titleId == title && it.teamId >= 0 && Dates.ageAt(it.birth, w.day) <= 21 }.sortedByDescending { it.ca + (it.pa - it.ca) * 0.5 }.take(10)
            for (p in young) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { PlayerChip(p, vm, 18.dp); Muted("${w.teams[p.teamId].short} · ${t.role(p.roles[0]).short} · age ${Dates.ageAt(p.birth, w.day)}", 10) }
                Rating(p.ca); Muted(" → ${if (w.userOrg >= 0) dev.uem.em.mgmt.Extras.potentialText(w, p) else "%.0f".format(p.pa)}", 11)
            }
        }
        Card("Biggest moves") {
            val moves = w.transfers.filter { it.season == season && w.players[it.playerId].titleId == title }.sortedByDescending { it.fee + w.players[it.playerId].ca * 1000 }.take(10)
            if (moves.isEmpty()) Muted("No moves yet this season.")
            for (x in moves) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { PlayerChip(w.players[x.playerId], vm, 18.dp); Muted("${w.teams.getOrNull(x.fromTeam)?.short ?: "free"} → ${w.teams.getOrNull(x.toTeam)?.short ?: "released"} · ${x.kind}", 10) }
                if (x.fee > 0) Text(formatMoney(x.fee), color = Ui.gold, fontSize = 12.sp)
            }
        }
        for (lg in w.leagues.filter { it.titleId == title && it.tier == 1 && it.teamIds.isNotEmpty() }) Card("Power rankings — ${lg.name}") {
            lg.teamIds.map { w.teams[it] }.sortedByDescending { dev.uem.em.sim.Lineups.strength(w, it) }.forEachIndexed { i, tm ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(22.dp))
                    Column(Modifier.weight(1f)) { TeamChip(w, tm.id, vm, 16.dp) }
                    Rating(dev.uem.em.sim.Lineups.strength(w, tm), 1)
                }
            }
        }
    } else {
        val comps = w.comps.filter { it.season == season && it.titleId == title && it.done && it.placements.isNotEmpty() }.sortedByDescending { it.tier.ordinal * 1000 + it.startDay / 1000 }
        Card("Champions") {
            if (comps.isEmpty()) Muted("No competition finished yet this season.")
            for (c in comps) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Comp(c.id)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, color = if (c.tier == CompTier.WORLDS) Ui.gold else Ui.text, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TeamChip(w, c.placements[0], vm, 16.dp, bold = true)
            }
        }
        Card("Season awards") {
            val aw = w.awards.filter { it.season == season && it.titleId == title }
            if (aw.isEmpty()) Muted("Awards are given at the end of the season.")
            for (a in aw) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, color = Ui.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (a.playerId >= 0) PlayerChip(w.players[a.playerId], vm, 18.dp)
            }
        }
        Card("Biggest risers") {
            val risers = w.players.filter { !it.retired && it.titleId == title && it.caHistory.size >= 1 }.map { it to (it.ca - it.caHistory.last()) }.sortedByDescending { it.second }.take(8)
            for ((p, d) in risers) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { PlayerChip(p, vm, 18.dp) }
                Text("%+.1f".format(d), color = if (d >= 0) Ui.win else Ui.loss, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

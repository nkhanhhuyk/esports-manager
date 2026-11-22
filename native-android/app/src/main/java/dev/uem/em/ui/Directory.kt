package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import dev.uem.em.core.Dates
import dev.uem.em.core.formatMoney
import dev.uem.em.data.GameData
import dev.uem.em.mgmt.Economy
import dev.uem.em.mgmt.Extras
import dev.uem.em.mgmt.Legacy
import dev.uem.em.model.AttrGroup
import dev.uem.em.model.Attrs
import dev.uem.em.model.CompTier
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.titles.Catalogue

private val ALL_TITLES get() = listOf("All") + Catalogue.IDS.map { Catalogue.get(it).short }
private fun titleAt(i: Int) = if (i == 0) null else Catalogue.IDS[i - 1]
private fun idxOf(id: String?) = if (id == null) 0 else Catalogue.IDS.indexOf(id) + 1

// ------------------------------------------------------------------------------------------ teams

@Composable
fun TeamsScreen(vm: GameVm, w: World) {
    var title by remember { mutableStateOf<String?>(w.teams.firstOrNull { w.isUserTeam(it.id) }?.titleId) }
    var mode by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("Teams", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Muted("Your divisions and every active team", 12)
            }
        }
        item { Tabs(ALL_TITLES, idxOf(title)) { title = titleAt(it) } }
        item { Tabs(listOf("By league", "Directory"), mode) { mode = it } }
        item {
            // Follow every team shown (the game filter above) in one tap.
            val shown = w.teams.filter { it.active && !w.isUserTeam(it.id) && it.nationalOf.isEmpty() && (title == null || it.titleId == title) }
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("☆ Follow all (${shown.size})", Ui.accent.copy(alpha = 0.3f)) { for (tm in shown) if (tm.id !in w.followed) w.followed.add(tm.id); vm.edit() }
                Pill("Unfollow all") { w.followed.removeAll(shown.map { it.id }.toSet()); vm.edit() }
                Muted("following ${w.followed.size}", 11)
            }
        }
        if (mode == 0) {
            val mine = w.teams.filter { w.isUserTeam(it.id) && (title == null || it.titleId == title) }
            if (mine.isNotEmpty()) item {
                Card("Your divisions") {
                    for (tm in mine) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Team(tm.id)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TitleTag(tm.titleId)
                        Column(Modifier.weight(1f)) { TeamChip(w, tm.id, vm, 18.dp); Muted("${tm.leagueId.takeIf { it >= 0 }?.let { w.leagues[it].name } ?: "no league"} · Elo ${tm.elo.toInt()}", 10) }
                        Rating(Lineups.strength(w, tm), 1)
                    }
                }
            }
            for (l in w.leagues.filter { title == null || it.titleId == title }.sortedWith(compareBy({ it.titleId }, { it.tier }))) item(key = "l${l.id}") {
                Card(l.name, right = { Row(verticalAlignment = Alignment.CenterVertically) { TitleTag(l.titleId); w.comps.lastOrNull { it.leagueId == l.id }?.let { CompPic(w, it, 22.dp) } ?: Pic(Pics.league(l), l.short, 22.dp) } }) {
                    Muted("${GameData.regionName(l.region)} · tier ${l.tier} · ${l.teamIds.size} teams${if (l.real) " · real league" else ""}", 11)
                    for (tid in l.teamIds.sortedByDescending { w.teams[it].elo }) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { TeamChip(w, tid, vm) }
                        Muted("str ", 10); Rating(Lineups.strength(w, w.teams[tid]), 1, 12); Muted("  Elo ${w.teams[tid].elo.toInt()}", 11)
                    }
                }
            }
        } else {
            item {
                OutlinedTextField(q, { q = it }, label = { Text("Search teams") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            }
            item {
                val rows = w.teams.filter { (it.active || it.trophies.isNotEmpty()) && (title == null || it.titleId == title) && (q.isBlank() || it.name.contains(q, true) || it.short.contains(q, true)) }
                Card("${rows.size} teams") {
                    SortTable(rows, listOf(
                        SCol<Team>("Team", 140.dp, false) { tm -> TeamChip(w, tm.id, vm, 18.dp) },
                        SCol("Title", 56.dp, false) { tm -> TitleTag(tm.titleId) },
                        SCol("Region", 70.dp, false) { tm -> Muted(GameData.regionName(tm.region), 11) },
                        SCol("League", 64.dp, false) { tm -> Muted(tm.leagueId.takeIf { it >= 0 }?.let { w.leagues[it].short } ?: if (tm.active) "—" else "dormant", 11) },
                        SCol("Str", 44.dp, key = { Lineups.strength(w, it) }) { tm -> Rating(Lineups.strength(w, tm), 1) },
                        SCol("Elo", 46.dp, key = { it.elo }) { tm -> Num("${tm.elo.toInt()}") },
                        SCol("Trophies", 58.dp, key = { it.trophies.size.toDouble() }) { tm -> Num("${tm.trophies.size}", Ui.gold) },
                        SCol("Payroll", 70.dp, key = { tm -> tm.roster.sumOf { w.players[it].salary } }) { tm -> Num(formatMoney(tm.roster.sumOf { w.players[it].salary }), Ui.muted) },
                    ), initialSort = 5, limit = 300, onRow = { vm.go(Screen.Team(it.id)) }, highlight = { w.isUserTeam(it.id) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- players

@Composable
fun PlayersScreen(vm: GameVm, w: World) {
    var q by remember { mutableStateOf("") }
    var title by remember { mutableStateOf<String?>(w.teams.firstOrNull { w.isUserTeam(it.id) }?.titleId) }
    var status by remember { mutableIntStateOf(0) }
    var role by remember { mutableIntStateOf(-1) }
    var maxAge by remember { mutableIntStateOf(0) }
    val sel = remember { mutableStateListOf<Int>() }
    val table = rememberTableState(6)
    val ages = listOf(99, 19, 21, 23, 26, 30)
    val t = title?.let { Catalogue.get(it) }
    val list = remember(q, title, status, role, maxAge, vm.version) {
        w.players.asSequence().filter {
            !it.retired && (title == null || it.titleId == title) && (role < 0 || t == null || it.mainRole == t.lineup[role]) &&
                Dates.ageAt(it.birth, w.day) <= ages[maxAge] &&
                when (status) { 1 -> it.teamId < 0; 2 -> it.teamId >= 0 && it.contractEnd <= w.season; 3 -> it.real; else -> true } &&
                (q.isBlank() || it.handle.contains(q, true) || "${it.first} ${it.last}".contains(q, true))
        }.sortedByDescending { it.ca }.take(400).toList()
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Players", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Muted("${list.size}${if (list.size == 400) "+" else ""} players match · tick two to compare", 12)
                }
                if (sel.size >= 2) Pill("Compare ${sel.size}", Ui.accent, Color.White) { vm.go(Screen.Compare(sel[0], sel[1])) }
            }
        }
        item { OutlinedTextField(q, { q = it }, label = { Text("Search name or real name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) }
        item { Tabs(ALL_TITLES, idxOf(title)) { title = titleAt(it); role = -1 } }
        if (t != null) item { Tabs(listOf("All roles") + t.lineup.map { t.role(it).short }, role + 1) { role = it - 1 } }
        item {
            Row(Modifier.padding(horizontal = 12.dp)) {
                Select("Show", listOf("Everyone", "Free agents", "Expiring contracts", "Real players"), status) { status = it }
                Select("Age", listOf("any", "≤19", "≤21", "≤23", "≤26", "≤30"), maxAge) { maxAge = it }
            }
        }
        lazySortTable(table, list, listOf(
                    SCol<Player>("✓", 30.dp, false) { p -> Text(if (p.id in sel) "☑" else "☐", color = if (p.id in sel) Ui.accent else Ui.muted, fontSize = 15.sp, modifier = Modifier.clickable { if (p.id in sel) sel.remove(p.id) else { sel.add(p.id); if (sel.size > 2) sel.removeAt(0) } }) },
                    SCol("Player", 130.dp, false) { p -> Row(verticalAlignment = Alignment.CenterVertically) { p.nats().joinToString("") { flag(it) }.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp); HGap(3) }; PlayerChip(p, vm, 20.dp) } },
                    SCol("Age", 34.dp, key = { Dates.ageAt(it.birth, w.day).toDouble() }) { p -> Num("${Dates.ageAt(p.birth, w.day)}") },
                    SCol("Title", 52.dp, false) { p -> TitleTag(p.titleId) },
                    SCol("Role", 40.dp, false) { p -> Muted(Catalogue.get(p.titleId).role(p.mainRole).short, 11) },
                    SCol("Team", 56.dp, false) { p -> Muted(p.teamId.takeIf { it >= 0 }?.let { w.teams[it].short } ?: "FA", 11) },
                    SCol("CA", 38.dp, key = { it.ca }) { p -> Rating(p.ca) },
                    SCol("PA", 54.dp, key = { it.pa }) { p -> Num(if (w.userOrg >= 0) Extras.potentialText(w, p) else "%.0f".format(p.pa), Ui.muted) },
                    SCol("Value", 64.dp, key = { Economy.marketValue(w, it) }) { p -> Num(formatMoney(Economy.marketValue(w, p))) },
                    SCol("Salary", 64.dp, key = { it.salary }) { p -> Num(if (p.teamId >= 0) formatMoney(p.salary) else "—", Ui.muted) },
                    SCol("Until", 42.dp, key = { it.contractEnd.toDouble() }) { p -> Num(if (p.teamId >= 0) "${p.contractEnd}" else "FA", Ui.muted) },
                    SCol("Fame", 42.dp, key = { it.rep }) { p -> Num("${p.rep.toInt()}", Ui.muted) },
                    SCol("Season", 56.dp, key = { p -> p.stats.filter { it.season == w.season }.let { l -> if (l.sumOf { it.gp } > 0) l.sumOf { it.ratingSum } / l.sumOf { it.gp } else null } }) { p ->
                        val l = p.stats.filter { it.season == w.season }
                        Num(if (l.sumOf { it.gp } > 0) "%.2f".format(l.sumOf { it.ratingSum } / l.sumOf { it.gp }) else "—")
                    },
                ), key = { it.id }, onRow = { vm.go(Screen.Player(it.id)) }, highlight = { it.id in sel })
    }
}

@Composable
fun CompareScreen(vm: GameVm, w: World, a: Int, b: Int) {
    val ps = listOf(w.players[a], w.players[b])
    val cols = listOf(Ui.blue, Ui.red)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((i, p) in ps.withIndex()) Column(Modifier.weight(1f).clickable { vm.go(Screen.Player(p.id)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    PlayerPic(p, 60.dp, round = true)
                    Text(p.handle, color = cols[i], fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Muted("${Catalogue.get(p.titleId).short} ${Catalogue.get(p.titleId).role(p.mainRole).short} · ${p.teamId.takeIf { it >= 0 }?.let { w.teams[it].short } ?: "FA"} · ${Dates.ageAt(p.birth, w.day)}", 11)
                }
            }
        }
        item {
            Card("Development (current ability by season)") {
                LineChart(ps.mapIndexed { i, p ->
                    val start = w.season - p.caHistory.size
                    Series(p.handle, cols[i], p.caHistory.mapIndexed { k, v -> (start + k).toFloat() to v } + (w.season.toFloat() to p.ca.toFloat()))
                }, 30f, 100f)
            }
        }
        item {
            Card("Summary") {
                @Composable fun row(label: String, f: (Player) -> String) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(f(ps[0]), color = Ui.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(label, color = Ui.muted, fontSize = 12.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(f(ps[1]), color = Ui.text, fontSize = 13.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
                row("Current ability") { "%.1f".format(it.ca) }
                row("Potential") { if (w.userOrg >= 0) Extras.potentialText(w, it) else "%.0f".format(it.pa) }
                row("Value") { formatMoney(Economy.marketValue(w, it)) }
                row("Salary") { if (it.teamId >= 0) formatMoney(it.salary) else "—" }
                row("Fame") { "${it.rep.toInt()}" }
                row("Career games") { "${it.stats.sumOf { s -> s.gp }}" }
                row("Career rating") { p -> p.stats.sumOf { it.gp }.let { g -> if (g > 0) "%.2f".format(p.stats.sumOf { it.ratingSum } / g) else "—" } }
                row("Legacy") { "%.0f".format(Legacy.of(w, it).total) }
                for ((k, _) in Attrs.summaries(ps[0].attrs)) row(k) { p -> "%.0f".format(Attrs.summaries(p.attrs).first { it.first == k }.second) }
            }
        }
        item {
            Card("All 86 attributes") {
                for (g in AttrGroup.entries) {
                    Text(g.name, color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    for (i in Attrs.group(g)) {
                        val va = ps[0].attrs[i]
                        val vb = ps[1].attrs[i]
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("%.0f".format(va), color = if (va > vb) Ui.win else Ui.muted, fontWeight = if (va > vb) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp, modifier = Modifier.width(36.dp))
                            Text(Attrs.ALL[i].name, color = Ui.muted, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("%.0f".format(vb), color = if (vb > va) Ui.win else Ui.muted, fontWeight = if (vb > va) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- history

private val HISTORY_TABS = listOf("Champions by season", "GOAT rankings", "Hall of Fame", "Dynasties", "Retired archive")
private const val HOF_THRESHOLD = 60.0

@Composable
fun HistoryScreen(vm: GameVm, w: World) {
    var title by remember { mutableStateOf<String?>(w.teams.firstOrNull { w.isUserTeam(it.id) }?.titleId) }
    var tab by remember { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("History", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Muted("${w.comps.map { it.season }.distinct().size} seasons · ${w.comps.count { it.done }} competitions finished", 12)
            }
        }
        item { Tabs(ALL_TITLES, idxOf(title)) { title = titleAt(it) } }
        item { Tabs(HISTORY_TABS, tab) { tab = it } }
        when (tab) {
            0 -> {
                val seasons = w.comps.filter { (title == null || it.titleId == title) && it.done }.map { it.season }.distinct().sortedDescending()
                if (seasons.isEmpty()) item { Card { Muted("No finished competitions yet.") } }
                for (s in seasons) item(key = "s$s") {
                    Card("Season $s") {
                        val comps = w.comps.filter { it.season == s && (title == null || it.titleId == title) && it.done && it.placements.isNotEmpty() && it.tier != CompTier.CHALLENGER }
                            .sortedWith(compareByDescending<dev.uem.em.model.Competition> { it.tier.ordinal }.thenBy { it.titleId }.thenBy { it.startDay })
                        for (c in comps) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Comp(c.id)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (title == null) TitleTag(c.titleId)
                            Text(c.short, color = if (c.tier == CompTier.WORLDS) Ui.gold else Ui.muted, fontSize = 12.sp, modifier = Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Column(Modifier.weight(1f)) { TeamChip(w, c.placements[0], vm, 18.dp) }
                            if (c.placements.size > 1) Muted("def. ${w.teams[c.placements[1]].short}", 10)
                        }
                        val aw = w.awards.filter { it.season == s && (title == null || it.titleId == title) }
                        if (aw.isNotEmpty()) {
                            Gap(6)
                            Text("Awards", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            for (a in aw) Row(Modifier.fillMaxWidth().clickable(enabled = a.playerId >= 0) { vm.go(Screen.Player(a.playerId)) }.padding(vertical = 2.dp)) {
                                Text(a.name, color = Ui.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(w.players.getOrNull(a.playerId)?.handle ?: "—", color = Ui.text, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            1, 2 -> item {
                val rows = remember(title, vm.version, tab) {
                    w.players.filter { p -> (title == null || p.titleId == title || p.stats.any { it.titleId == title }) && (p.stats.isNotEmpty() || p.awards.isNotEmpty()) }
                        .map { it to Legacy.of(w, it) }.filter { tab == 1 || (it.first.retired && it.second.total >= HOF_THRESHOLD) }.sortedByDescending { it.second.total }.take(150)
                }
                Card(if (tab == 1) (title?.let { "${Catalogue.get(it).name} all-time greats" } ?: "Greatest of all time (prestige-weighted)") else "Hall of Fame (retired, legacy ≥ ${HOF_THRESHOLD.toInt()})") {
                    if (rows.isEmpty()) Muted(if (tab == 2) "Nobody has been inducted yet: players enter when they retire with a legacy of ${HOF_THRESHOLD.toInt()}+." else "No players yet.")
                    else SortTable(rows, listOf(
                        SCol<Pair<Player, Legacy.Score>>("Player", 130.dp, false) { (p, _) -> Row(verticalAlignment = Alignment.CenterVertically) { p.nats().joinToString("") { flag(it) }.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp); HGap(3) }; PlayerChip(p, vm, 18.dp) } },
                        SCol("Title", 52.dp, false) { (p, _) -> TitleTag(p.titleId) },
                        SCol("Trophies", 56.dp, key = { (p, _) -> Legacy.honours(w, p).count { it.trophy }.toDouble() }) { (p, _) -> Num("${Legacy.honours(w, p).count { it.trophy }}", Ui.gold) },
                        SCol("Awards", 50.dp, key = { (p, _) -> w.awards.count { it.playerId == p.id }.toDouble() }) { (p, _) -> Num("${w.awards.count { it.playerId == p.id }}") },
                        SCol("Achieve.", 58.dp, key = { it.second.achievements }) { Num("%.1f".format(it.second.achievements)) },
                        SCol("Peak", 44.dp, key = { it.second.peak }) { Num("%.1f".format(it.second.peak)) },
                        SCol("Longev.", 52.dp, key = { it.second.longevity }) { Num("%.1f".format(it.second.longevity)) },
                        SCol("Legacy", 52.dp, key = { it.second.total }) { Num("%.1f".format(it.second.total), Ui.gold, bold = true) },
                        SCol("Status", 80.dp, false) { (p, _) -> Muted(if (p.retired) "retired ${p.retiredSeason}" else p.teamId.takeIf { it >= 0 }?.let { w.teams[it].short } ?: "free agent", 11) },
                    ), initialSort = 7, onRow = { vm.go(Screen.Player(it.first.id)) })
                }
            }
            3 -> item {
                Card("Most decorated organisations (worlds ×3, internationals ×2, leagues ×1)") {
                    val pts = HashMap<Int, IntArray>()
                    for (c in w.comps) if (c.done && c.placements.isNotEmpty() && (title == null || c.titleId == title)) {
                        val org = w.teams[c.placements[0]].orgId
                        val a = pts.getOrPut(org) { IntArray(3) }
                        when (c.tier) { CompTier.WORLDS -> a[0]++; CompTier.INTERNATIONAL -> a[1]++; CompTier.LEAGUE -> a[2]++; else -> {} }
                    }
                    val rows = pts.entries.map { (o, a) -> Triple(o, a, a[0] * 3 + a[1] * 2 + a[2]) }.filter { it.third > 0 }.sortedByDescending { it.third }.take(30)
                    if (rows.isEmpty()) Muted("No titles won yet.")
                    rows.forEachIndexed { i, (o, a, total) ->
                        val org = w.orgs[o]
                        Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Org(o)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", color = if (i == 0) Ui.gold else Ui.muted, fontSize = 12.sp, modifier = Modifier.width(24.dp))
                            Pic(org.teams.firstNotNullOfOrNull { Pics.team(w.teams[it]) }, org.short, 20.dp); HGap(6)
                            Column(Modifier.weight(1f)) { Text(org.name, color = if (o == w.userOrg) Ui.gold else Ui.text, fontSize = 13.sp); Muted("${a[0]} worlds · ${a[1]} internationals · ${a[2]} leagues", 10) }
                            Text("$total", color = Ui.gold, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
            else -> item {
                val rows = w.players.filter { it.retired && (title == null || it.titleId == title) }.sortedByDescending { it.retiredSeason * 1000 + it.stats.sumOf { s -> s.gp } }.take(300)
                Card("Retired players (${rows.size})") {
                    if (rows.isEmpty()) Muted("Nobody has retired since the save began.")
                    else SortTable(rows, listOf(
                        SCol<Player>("Player", 130.dp, false) { p -> PlayerChip(p, vm, 18.dp) },
                        SCol("Name", 130.dp, false) { p -> Muted(realName(p), 11) },
                        SCol("Title", 52.dp, false) { p -> TitleTag(p.titleId) },
                        SCol("Retired", 52.dp, key = { it.retiredSeason.toDouble() }) { p -> Num("${p.retiredSeason}") },
                        SCol("Games", 50.dp, key = { p -> p.stats.sumOf { it.gp }.toDouble() }) { p -> Num("${p.stats.sumOf { it.gp }}") },
                        SCol("Legacy", 52.dp, key = { Legacy.of(w, it).total }) { p -> Num("%.0f".format(Legacy.of(w, p).total), Ui.gold) },
                    ), initialSort = 3, onRow = { vm.go(Screen.Player(it.id)) })
                }
            }
        }
        item { Gap(16) }
    }
}

// ------------------------------------------------------------------------------------------ world

private val WORLD_TABS = listOf("Titles", "Countries & scenes", "Global rankings", "Organisations")

@Composable
fun WorldScreen(vm: GameVm, w: World) {
    var tab by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf(w.teams.firstOrNull { w.isUserTeam(it.id) }?.titleId ?: "lol") }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("World", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Muted("Games, national scenes, rankings and organisations", 12)
            }
        }
        item { Tabs(WORLD_TABS, tab) { tab = it } }
        when (tab) {
            0 -> item {
                Card("Games") {
                    SortTable(w.titles.map { Catalogue.get(it) }, listOf(
                        SCol<dev.uem.em.titles.TitleDef>("Title", 120.dp, false) { t -> Text(t.name, color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                        SCol("Type", 70.dp, false) { t -> Muted(if (t.isMoba) "MOBA" else "Tactical FPS", 11) },
                        SCol("Input", 90.dp, false) { t -> Muted(if (t.inputMkb) "Mouse & keyboard" else "Touch", 11) },
                        SCol("Team", 40.dp, key = { it.teamSize.toDouble() }) { t -> Num("${t.teamSize}") },
                        SCol("Leagues", 54.dp, key = { t -> w.leagues.count { it.titleId == t.id }.toDouble() }) { t -> Num("${w.leagues.count { it.titleId == t.id }}") },
                        SCol("Teams", 48.dp, key = { t -> w.teams.count { it.titleId == t.id && it.active }.toDouble() }) { t -> Num("${w.teams.count { it.titleId == t.id && it.active }}") },
                        SCol("Players", 54.dp, key = { t -> w.players.count { it.titleId == t.id && !it.retired }.toDouble() }) { t -> Num("${w.players.count { it.titleId == t.id && !it.retired }}") },
                        SCol("Patch", 60.dp, false) { t -> Muted(w.patches.lastOrNull { it.titleId == t.id }?.version ?: "—", 11) },
                        SCol("Formats", 100.dp, false) { t -> Muted("${t.formats.first.label} / ${t.formats.second.label} / ${t.formats.third.label}", 11) },
                        SCol("Roster", 60.dp, key = { it.characters.size.toDouble() }) { t -> Num("${t.characters.size}", Ui.muted) },
                    ), onRow = { title = it.id; tab = 2 })
                    Muted("Roster = number of champions / heroes / agents.", 10)
                }
            }
            1 -> item {
                val byCountry = w.players.filter { !it.retired }.groupBy { it.nat }
                val rows = GameData.countries.values.filter { byCountry.containsKey(it.code) }
                Card("Countries & scenes (${rows.size})") {
                    SortTable(rows, listOf(
                        SCol<dev.uem.em.data.Country>("Country", 140.dp, false) { c -> Text("${flag(c.code)} ${c.name}", color = Ui.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        SCol("Region", 80.dp, false) { c -> Muted(GameData.regionName(c.region), 11) },
                        SCol("MOBA", 46.dp, key = { it.moba.toDouble() }) { c -> Rating(c.moba.toDouble()) },
                        SCol("FPS", 40.dp, key = { it.shooter.toDouble() }) { c -> Rating(c.shooter.toDouble()) },
                        SCol("Players", 54.dp, key = { (byCountry[it.code]?.size ?: 0).toDouble() }) { c -> Num("${byCountry[c.code]?.size ?: 0}") },
                        SCol("Best", 100.dp, false) { c -> byCountry[c.code]?.maxByOrNull { it.ca }?.let { PlayerChip(it, vm, 16.dp) } },
                        SCol("Avg CA", 52.dp, key = { c -> byCountry[c.code]?.sortedByDescending { it.ca }?.take(10)?.map { it.ca }?.average() }) { c -> Num("%.1f".format(byCountry[c.code]?.sortedByDescending { it.ca }?.take(10)?.map { it.ca }?.average() ?: 0.0)) },
                    ), initialSort = 4)
                    Muted("Avg CA = the country's ten best players. MOBA / FPS = how strong the scene is.", 10)
                }
            }
            2 -> {
                item { Tabs(Catalogue.IDS.map { Catalogue.get(it).short }, Catalogue.IDS.indexOf(title)) { title = Catalogue.IDS[it] } }
                item {
                    val rows = w.teams.filter { it.active && it.titleId == title }.sortedByDescending { it.elo }.take(60)
                    Card("Global ranking · ${Catalogue.get(title).name}") {
                        rows.forEachIndexed { i, tm ->
                            Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Team(tm.id)) }.background(if (w.isUserTeam(tm.id)) Ui.card2 else Color.Transparent).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", color = if (i < 3) Ui.gold else Ui.muted, fontSize = 12.sp, modifier = Modifier.width(26.dp))
                                Column(Modifier.weight(1f)) { TeamChip(w, tm.id, vm, 18.dp); Muted("${GameData.regionName(tm.region)} · ${tm.leagueId.takeIf { it >= 0 }?.let { w.leagues[it].short } ?: "—"}", 10) }
                                Rating(Lineups.strength(w, tm), 1, 12)
                                Text("  ${tm.elo.toInt()}", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                item {
                    val rows = w.players.filter { !it.retired && it.titleId == title }.sortedByDescending { it.ca }.take(30)
                    Card("Top players · ${Catalogue.get(title).short}") {
                        rows.forEachIndexed { i, p ->
                            Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Player(p.id)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", color = if (i < 3) Ui.gold else Ui.muted, fontSize = 12.sp, modifier = Modifier.width(26.dp))
                                Column(Modifier.weight(1f)) { PlayerChip(p, vm, 18.dp); Muted("${Catalogue.get(title).role(p.mainRole).short} · ${p.teamId.takeIf { it >= 0 }?.let { w.teams[it].short } ?: "FA"}", 10) }
                                Rating(p.ca, 1)
                            }
                        }
                    }
                }
            }
            else -> item {
                val rows = w.orgs.filter { o -> o.teams.any { w.teams[it].active } }
                Card("Organisations (${rows.size})") {
                    SortTable(rows, listOf(
                        SCol<dev.uem.em.model.Org>("Organisation", 140.dp, false) { o ->
                            Row(verticalAlignment = Alignment.CenterVertically) { Pic(o.teams.firstNotNullOfOrNull { Pics.team(w.teams[it]) }, o.short, 18.dp); HGap(4); Text(o.name, color = if (o.id == w.userOrg) Ui.gold else Ui.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                        SCol("Country", 56.dp, false) { o -> Text(flag(o.country).ifEmpty { o.country }, fontSize = 13.sp) },
                        SCol("Divisions", 80.dp, false) { o -> Row { for (tid in o.teams.filter { w.teams[it].active }) TitleTag(w.teams[tid].titleId) } },
                        SCol("Rep", 40.dp, key = { it.rep }) { o -> Rating(o.rep) },
                        SCol("Fans", 64.dp, key = { it.fans }) { o -> Num("%,.0f".format(o.fans), Ui.muted) },
                        SCol("Trophies", 56.dp, key = { o -> o.teams.sumOf { w.teams[it].trophies.size }.toDouble() }) { o -> Num("${o.teams.sumOf { w.teams[it].trophies.size }}", Ui.gold) },
                    ), initialSort = 3, limit = 300, onRow = { o -> o.teams.firstOrNull { w.teams[it].active }?.let { vm.go(Screen.Team(it)) } }, highlight = { it.id == w.userOrg })
                }
            }
        }
        item { Gap(16) }
    }
}

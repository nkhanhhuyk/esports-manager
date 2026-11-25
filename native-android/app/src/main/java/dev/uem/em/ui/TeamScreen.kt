package dev.uem.em.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import dev.uem.em.data.GameData
import dev.uem.em.mgmt.Economy
import dev.uem.em.mgmt.Extras
import dev.uem.em.model.CompTier
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.sim.Ratings
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.TitleDef

private val TEAM_TABS = listOf("Roster", "Schedule & Results", "Statistics", "Staff", "Training & scrims", "History", "Past rosters", "Transfer history", "Organisation", "Registration", "Development")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamScreen(vm: GameVm, w: World, id: Int) {
    val team = w.teams[id]
    val t = Catalogue.get(team.titleId)
    val org = w.orgs[team.orgId]
    var tab by remember(id) { mutableIntStateOf(UiDefaults.tab) }
    val staff = team.staffIds.map { w.staff[it] }
    val playerPay = team.roster.sumOf { w.players[it].salary }
    val staffPay = staff.sumOf { it.salary }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(if (w.isUserTeam(id)) Ui.heroGoldSoft else Ui.heroSoft).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TeamPic(team, 60.dp)
                HGap(12)
                Column(Modifier.weight(1f)) {
                    Text(team.name, color = if (w.isUserTeam(id)) Ui.gold else Ui.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    FlowRow {
                        TitleTag(t.id)
                        OrgLink(w, org.id, color = Ui.accent, size = 12.sp)
                        Text(" · ${GameData.regionName(team.region)}", color = Ui.muted, fontSize = 12.sp)
                    }
                    Muted(listOfNotNull(
                        team.leagueId.takeIf { it >= 0 }?.let { w.leagues[it].name } ?: if (team.active) "no league" else "dormant (no league place)",
                        if (team.nationalOf.isNotEmpty()) "national team of ${GameData.countries[team.nationalOf]?.name ?: team.nationalOf}" else null,
                        if (team.real) "real team" else null,
                    ).joinToString(" · "), 12)
                    if (team.dataNote.isNotBlank()) Text(team.dataNote, color = if (team.dataNote.startsWith("Fictional") || team.dataNote.contains("historical") || team.dataNote.startsWith("Academy")) Ui.gold else Ui.muted, fontSize = 11.sp)
                    if (team.kind.isEmpty() && team.nationalOf.isEmpty()) Muted("Organisation strategy: ${dev.uem.em.mgmt.Ambitions.STRATEGY_LABEL[dev.uem.em.mgmt.Ambitions.strategy(w, team)]}", 11)
                    if (dev.uem.em.mgmt.Ambitions.rebuilding(w, team)) Text("Rebuilding the roster: ${team.rebuildNote}", color = Ui.loss, fontSize = 11.sp)
                    dev.uem.em.mgmt.Academies.parent(w, team)?.let { par -> Row(verticalAlignment = Alignment.CenterVertically) { Muted("Academy of ", 12); TeamChip(w, par.id, vm, 16.dp) } }
                    dev.uem.em.mgmt.Academies.of(w, team)?.let { ac -> Row(verticalAlignment = Alignment.CenterVertically) { Muted("Academy: ", 12); TeamChip(w, ac.id, vm, 16.dp) } }
                }
            }
            var editing by remember { mutableStateOf(false) }
            FlowRow(Modifier.padding(horizontal = 12.dp)) {
                FollowButton(vm, w, team)
                Pill("Edit") { editing = true }
                if (w.isUserTeam(id)) Pill("Squad & tactics") { vm.tab(Screen.Squad) }
                if (team.nationalOf.isEmpty()) Pill("Road to the next events") { vm.go(Screen.Road(id)) }
                team.leagueId.takeIf { it >= 0 }?.let { lid -> w.comps.lastOrNull { it.leagueId == lid && it.season == w.season && it.status > 0 }?.let { c -> Pill("League table") { vm.go(Screen.Comp(c.id)) } } }
            }
            if (editing) EditTeamDialog(vm, w, team) { editing = false }
        }
        item {
            Tiles(listOf(
                Tile("Strength", "%.1f".format(Lineups.strength(w, team)), color = Rate.color(Lineups.strength(w, team))),
                Tile("Elo", "${team.elo.toInt()}", "rank ${w.teams.filter { it.titleId == team.titleId && it.active }.sortedByDescending { it.elo }.indexOf(team) + 1} in ${t.short}"),
                Tile("Chemistry", "%.0f".format(dev.uem.em.mgmt.Chemistry.lineup(w, team, team.lineup)), "cohesion %.0f".format(team.chemistry), color = Rate.color(dev.uem.em.mgmt.Chemistry.lineup(w, team, team.lineup))),
                Tile("Meta adaptation", "%.0f".format(team.metaAdapt), color = Rate.color(team.metaAdapt)),
                Tile("Payroll", formatMoney(playerPay), "${team.roster.size} players"),
                Tile("Staff cost", formatMoney(staffPay), "${staff.size} staff"),
                Tile("Style", team.style.ifBlank { "balanced" }, "aggression ${team.aggression.toInt()}"),
                Tile("Coaching", "%.0f".format(Economy.coachQuality(w, team)), "training ${team.focus}"),
                Tile("Trophies", "${team.trophies.size}", team.trophies.lastOrNull()?.let { "last: ${it.season}" }),
            ))
        }
        item { Tabs(TEAM_TABS, tab) { tab = it } }
        item {
            when (tab) {
                0 -> { TeamRoster(vm, w, team, t); TeamChemistryCard(vm, w, team) }
                1 -> TeamSchedule(vm, w, team)
                2 -> TeamStats(vm, w, team, t)
                3 -> TeamStaff(vm, w, team)
                4 -> TeamScrims(vm, w, team)
                5 -> TeamHistory(vm, w, team)
                6 -> TeamPastRosters(vm, w, team, t)
                7 -> TeamTransfers(vm, w, team)
                8 -> TeamOrg(vm, w, team)
                9 -> TeamRegistration(vm, w, team, t)
                else -> TeamDevelopment(vm, w, team)
            }
            Gap(16)
        }
    }
}

@Composable
private fun TeamRoster(vm: GameVm, w: World, team: Team, t: TitleDef) {
    val roster = team.roster.map { w.players[it] }
    fun slot(p: Player) = team.lineup.indexOf(p.id)
    val ip = inputOf(t)
    Card("Roster (${roster.size}) — ${t.teamSize} starters", right = { Muted("tap a column to sort", 10) }) {
        SortTable(roster, listOf(
            SCol("Pos", 46.dp, false, { p -> slot(p).let { if (it < 0) 99.0 else it.toDouble() } }) { p -> val s = slot(p); if (s >= 0) Text(t.lineupRoles[s].short, color = Ui.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp) else Muted("sub", 11) },
            SCol("Player", 140.dp, false) { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    p.nats().joinToString("") { flag(it) }.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp); HGap(3) }
                    PlayerChip(p, vm, 20.dp)
                }
            },
            SCol("Role", 44.dp, false) { p -> Muted(t.role(p.roles[0]).short, 12) },
            SCol("Age", 36.dp, key = { p -> Dates.ageAt(p.birth, w.day).toDouble() }) { p -> Num("${Dates.ageAt(p.birth, w.day)}") },
            SCol("E (pos)", 52.dp, key = { p -> Ratings.effectiveness(p, t, if (slot(p) >= 0) t.lineupRoles[slot(p)] else t.role(p.roles[0])) }) { p ->
                Rating(Ratings.effectiveness(p, t, if (slot(p) >= 0) t.lineupRoles[slot(p)] else t.role(p.roles[0])), 1)
            },
            SCol("CA", 40.dp, key = { it.ca }) { p -> Rating(p.ca) },
            SCol("PA", 56.dp, key = { it.pa }) { p -> Num(if (w.userOrg >= 0) Extras.potentialText(w, p) else "%.0f".format(p.pa), Ui.muted) },
            SCol("Input", 42.dp, key = { it.input[ip] }) { p -> Rating(p.input[ip], bold = false) },
            SCol("Form", 42.dp, key = { it.form }) { p -> Num("%+.1f".format(p.form), if (p.form >= 0) Ui.win else Ui.loss) },
            SCol("Morale", 50.dp, key = { it.morale }) { p -> Rating(p.morale, bold = false) },
            SCol("Fatigue", 54.dp, key = { it.fatigue }) { p -> Num("%.0f".format(p.fatigue) + if (p.injuryUntil > w.day) " ✚" else "", if (p.fatigue > 60 || p.injuryUntil > w.day) Ui.loss else Ui.text) },
            SCol("Salary", 66.dp, key = { it.salary }) { p -> Num(formatMoney(p.salary)) },
            SCol("Until", 52.dp, key = { it.contractEnd.toDouble() }) { p -> Num("${p.contractEnd}${if (p.loanFrom >= 0) " L" else ""}") },
            SCol("Value", 66.dp, key = { Economy.marketValue(w, it) }) { p -> Num(formatMoney(Economy.marketValue(w, p)), Ui.muted) },
        ), initialSort = 0, initialDesc = false, onRow = { vm.go(Screen.Player(it.id)) }, highlight = { it.id in team.lineup })
        Gap(4)
        Muted("✚ injured · L on loan · E = effectiveness in the position the player fills (or their main role).", 10)
    }
    val avgAge = roster.map { Dates.ageAt(it.birth, w.day) }.average()
    Card("Squad profile") {
        KV("Average age", if (roster.isEmpty()) "—" else "%.1f".format(avgAge))
        KV("Nationalities", roster.map { it.nat }.distinct().joinToString(" ") { flag(it).ifEmpty { it } })
        KV("Starting five average CA", team.lineup.filter { it >= 0 }.map { w.players[it].ca }.average().takeIf { !it.isNaN() }?.let { "%.1f".format(it) } ?: "—")
        KV("Bench", "${roster.count { it.id !in team.lineup }} players")
        KV("Injured", "${roster.count { it.injuryUntil > w.day }}")
        KV("Contracts ending this season", "${roster.count { it.contractEnd <= w.season }}")
        KV("Scrims per week", "${team.scrims}")
        KV("Training", "${team.focus} · intensity ${team.intensity}")
    }
}

@Composable
private fun TeamSchedule(vm: GameVm, w: World, team: Team) {
    var season by remember { mutableIntStateOf(w.season) }
    val seasons = w.comps.filter { team.id in it.teamIds }.map { it.season }.distinct().sortedDescending().ifEmpty { listOf(w.season) }
    val fx = w.fixtures.filter { (it.a == team.id || it.b == team.id) && w.comps[it.compId].season == season }.sortedBy { it.day }
    val done = fx.filter { it.done }
    val sw = done.count { it.winner == team.id }
    val gw = done.sumOf { if (it.a == team.id) it.scoreA else it.scoreB }
    val gl = done.sumOf { if (it.a == team.id) it.scoreB else it.scoreA }
    Card("Fixtures", right = { Select("", seasons.map { "$it" }, seasons.indexOf(season).coerceAtLeast(0)) { season = seasons[it] } }) {
        Muted("Series ${sw}-${done.size - sw} · games $gw-$gl · ${fx.size - done.size} to play", 12)
        Gap(4)
        if (fx.isEmpty()) Muted("No fixtures this season.")
        for (f in fx) {
            val opp = if (f.a == team.id) f.b else f.a
            val won = f.winner == team.id
            Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Match(f.id)) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(62.dp)) { Text(Dates.short(f.day), color = Ui.text, fontSize = 12.sp); Muted(w.comps[f.compId].short, 9) }
                Column(Modifier.weight(1f)) { if (opp >= 0) TeamChip(w, opp, vm, 18.dp) else Muted("TBD") }
                if (f.done) {
                    Text(if (won) "W" else "L", color = if (won) Ui.win else Ui.loss, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                    Text(if (f.a == team.id) "${f.scoreA}-${f.scoreB}" else "${f.scoreB}-${f.scoreA}", color = Ui.text, fontSize = 13.sp)
                } else Muted("Bo${f.bestOf}", 12)
            }
        }
    }
}

@Composable
private fun TeamStats(vm: GameVm, w: World, team: Team, t: TitleDef) {
    var season by remember { mutableIntStateOf(w.season) }
    val lines = w.players.flatMap { p -> p.stats.filter { it.teamId == team.id && it.season == season }.map { p to it } }
    val seasons = (w.players.flatMap { p -> p.stats.filter { it.teamId == team.id }.map { it.season } } + w.season).distinct().sortedDescending()
    val k = t.statIndex["k"] ?: 0
    val d = t.statIndex["d"] ?: 1
    val a = t.statIndex["a"] ?: 2
    Card("$season season statistics", right = { Select("", seasons.map { "$it" }, seasons.indexOf(season).coerceAtLeast(0)) { season = seasons[it] } }) {
        if (lines.isEmpty()) Muted("No games this season.")
        else SortTable(lines, listOf<SCol<Pair<Player, dev.uem.em.model.SeasonLine>>>(
            SCol("Player", 120.dp, false) { (p, _) -> PlayerChip(p, vm, 18.dp) },
            SCol("GP", 34.dp, key = { it.second.gp.toDouble() }) { Num("${it.second.gp}") },
            SCol("Win%", 44.dp, key = { it.second.w.toDouble() / maxOf(1, it.second.gp) }) { Num("${100 * it.second.w / maxOf(1, it.second.gp)}%") },
            SCol("Rating", 50.dp, key = { it.second.ratingSum / maxOf(1, it.second.gp) }) { Num("%.2f".format(it.second.ratingSum / maxOf(1, it.second.gp)), bold = true) },
            SCol("KDA", 44.dp, key = { (it.second.s[k] + it.second.s[a]) / maxOf(1.0, it.second.s[d]) }) { Num("%.2f".format((it.second.s[k] + it.second.s[a]) / maxOf(1.0, it.second.s[d]))) },
        ) + t.stats.mapIndexed { i, st -> SCol<Pair<Player, dev.uem.em.model.SeasonLine>>(st.label + "/g", 56.dp, key = { it.second.s.getOrElse(i) { 0.0 } / maxOf(1, it.second.gp) }) { Num("%.1f".format(it.second.s.getOrElse(i) { 0.0 } / maxOf(1, it.second.gp))) } } +
            SCol("MVP", 38.dp, key = { it.second.mvps.toDouble() }) { Num("${it.second.mvps}") },
            initialSort = 3, onRow = { vm.go(Screen.Player(it.first.id)) })
    }
    val comps = w.comps.filter { it.season == season && team.id in it.teamIds }.sortedBy { it.startDay }
    if (comps.isNotEmpty()) Card("By competition") {
        for (c in comps) {
            val fx = c.stages.flatMap { it.fixtureIds }.map { w.fixtures[it] }.filter { it.done && (it.a == team.id || it.b == team.id) }
            val sw = fx.count { it.winner == team.id }
            val place = c.placements.indexOf(team.id).takeIf { it >= 0 }?.let { "#${it + 1}" } ?: if (c.done) "—" else c.stages.getOrNull(c.current)?.name ?: "upcoming"
            Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Comp(c.id)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, color = if (c.tier == CompTier.WORLDS) Ui.gold else Ui.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted("$sw-${fx.size - sw}  ", 12)
                Text(place, color = if (place == "#1") Ui.gold else Ui.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TeamStaff(vm: GameVm, w: World, team: Team) {
    Card("Staff") { StaffList(vm, w, team, manage = false) }
    Card("What the staff add") { StaffEffects(w, team) }
}

@Composable
private fun TeamHistory(vm: GameVm, w: World, team: Team) {
    val rivals = dev.uem.em.mgmt.Deals.rivalsOf(w, team.id)
    if (rivals.isNotEmpty()) Card("Rivalries") {
        for ((rid, v) in rivals) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
            Column(Modifier.weight(1f)) { TeamChip(w, rid, vm, 18.dp) }
            val h2h = w.fixtures.filter { it.done && ((it.a == team.id && it.b == rid) || (it.b == team.id && it.a == rid)) }
            Muted("${h2h.count { it.winner == team.id }}-${h2h.count { it.winner == rid }} h2h · ", 11)
            Bar(v, modifier = Modifier.width(50.dp), color = Ui.loss)
            Muted(" ${v.toInt()}", 11)
        }
    }
    Card("Season records") {
        if (team.history.isEmpty()) Muted("First season in progress.")
        else Table(listOf(Col("Season", 52.dp, false), Col("League", 110.dp, false), Col("W-L", 50.dp), Col("Rank", 40.dp), Col("Elo", 46.dp), Col("Best results", 220.dp, false)), team.history.size) { r, c ->
            val h = team.history.reversed()[r]
            when (c) {
                0 -> Num("${h.season}")
                1 -> Text(w.leagues.getOrNull(h.leagueId)?.short ?: "—", color = Ui.text, fontSize = 12.sp, maxLines = 1)
                2 -> Num("${h.w}-${h.l}")
                3 -> Num(if (h.position > 0) "#${h.position}" else "—", bold = true)
                4 -> Num("${h.elo.toInt()}", Ui.muted)
                else -> {
                    val best = w.comps.filter { it.season == h.season && it.done && team.id in it.placements.take(4) }.sortedByDescending { it.tier.ordinal }
                    Text(best.joinToString(", ") { "${it.short}: #${it.placements.indexOf(team.id) + 1}" }.ifEmpty { "—" }, color = Ui.muted, fontSize = 11.sp, maxLines = 2)
                }
            }
        }
    }
    Card("Trophies") {
        if (team.trophies.isEmpty()) Muted("No trophies yet.")
        for (tr in team.trophies.reversed()) Row(Modifier.fillMaxWidth().clickable(enabled = tr.compId >= 0 && tr.compId < w.comps.size) { vm.go(Screen.Comp(tr.compId)) }.padding(vertical = 3.dp)) {
            Text("🏆 ", fontSize = 13.sp); Text("${tr.season}", color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(40.dp)); Text(tr.name, color = Ui.gold, fontSize = 13.sp)
        }
    }
    val alumni = w.players.filter { p -> p.teamId != team.id && p.stats.any { it.teamId == team.id } }.sortedByDescending { p -> p.stats.filter { it.teamId == team.id }.sumOf { it.gp } }.take(12)
    if (alumni.isNotEmpty()) Card("Former players") {
        for (p in alumni) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { PlayerChip(p, vm, 18.dp) }
            Muted("${p.stats.filter { it.teamId == team.id }.sumOf { it.gp }} games · now ${p.teamId.takeIf { it >= 0 }?.let { w.teams[it].short } ?: if (p.retired) "retired" else "free agent"}", 11)
        }
    }
}

@Composable
private fun TeamOrg(vm: GameVm, w: World, team: Team) = OrgDetails(vm, w, team.orgId, team.id)

/** An organisation's page: all its teams across the games, supporters, trophies. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrgScreen(vm: GameVm, w: World, id: Int) {
    val org = w.orgs[id]
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val first = org.teams.firstOrNull { w.teams[it].active } ?: org.teams.firstOrNull()
                if (first != null) TeamPic(w.teams[first], 60.dp) else GenTeamLogo(id * 17 + org.name.hashCode(), 60.dp, org.name, org.short, org.colors.getOrNull(0)?.let { GenArt.parse(it) }, org.colors.getOrNull(1)?.let { GenArt.parse(it) })
                HGap(12)
                Column(Modifier.weight(1f)) {
                    Text(org.name, color = if (id == w.userOrg) Ui.gold else Ui.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Muted("${flag(org.country)} ${GameData.countries[org.country]?.name ?: org.country} · ${GameData.regionName(org.region)} · ${org.teams.count { w.teams[it].active }} active teams", 12)
                    FlowRow { for (tid in org.teams) if (w.teams[tid].active) TitleTag(w.teams[tid].titleId) }
                }
            }
        }
        item { OrgDetails(vm, w, id, -1); Gap(16) }
    }
}

@Composable
fun OrgDetails(vm: GameVm, w: World, orgId: Int, currentTeam: Int) {
    val org = w.orgs[orgId]
    val mine = orgId == w.userOrg
    Card(org.name, right = { if (currentTeam >= 0) Pill("Organisation page") { vm.go(Screen.Org(orgId)) } }) {
        KV("Country", "${flag(org.country)} ${GameData.countries[org.country]?.name ?: org.country}")
        KV("Region", GameData.regionName(org.region))
        KV("Reputation") { Rating(org.rep) }
        KV("Fans", "%,.0f".format(org.fans))
        if (mine) KV("Balance", formatMoney(org.balance), if (org.balance >= 0) Ui.win else Ui.loss)
        KV("Facilities", "training ${org.facilities.getOrElse(0) { 0 }} · performance ${org.facilities.getOrElse(1) { 0 }} · academy ${org.facilities.getOrElse(2) { 0 }}")
        if (org.sponsors.isNotEmpty()) KV("Sponsors", org.sponsors.joinToString { it.name })
        KV("Data", if (org.real) "Real organisation" else "Generated")
    }
    Card("Divisions") {
        for (tid in org.teams) {
            val x = w.teams[tid]
            Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Team(tid)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TitleTag(x.titleId)
                TeamPic(x, 22.dp); HGap(6)
                Column(Modifier.weight(1f)) {
                    Text(x.name, color = if (tid == currentTeam) Ui.accent else Ui.text, fontSize = 13.sp)
                    Muted("${x.leagueId.takeIf { it >= 0 }?.let { w.leagues[it].short } ?: if (x.active) "no league" else "dormant"} · Elo ${x.elo.toInt()} · ${x.roster.size} players", 10)
                }
                Rating(Lineups.strength(w, x), 1)
            }
        }
    }
    if (org.fanSeg.size == 4) Card("Supporters") {
        val labels = listOf("Core", "Casual", "Local", "International")
        val total = org.fanSeg.sum().coerceAtLeast(1.0)
        for (i in 0..3) Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(labels[i], color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(96.dp))
            Bar(org.fanSeg[i], total, Ui.accent, Modifier.weight(1f))
            Text("%,.0f".format(org.fanSeg[i]), color = Ui.text, fontSize = 12.sp, modifier = Modifier.width(70.dp).padding(start = 6.dp))
        }
    }
    val trophies = org.teams.flatMap { tid -> w.teams[tid].trophies.map { tid to it } }.sortedByDescending { it.second.season }
    if (trophies.isNotEmpty()) Card("Organisation trophy cabinet (${trophies.size})") {
        for ((tid, tr) in trophies.take(30)) Row(Modifier.fillMaxWidth().clickable { if (tr.compId in w.comps.indices) vm.go(Screen.Comp(tr.compId)) else vm.go(Screen.Team(tid)) }.padding(vertical = 2.dp)) { TitleTag(w.teams[tid].titleId); Text("${tr.season} · ${tr.name}", color = Ui.gold, fontSize = 12.sp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeamRegistration(vm: GameVm, w: World, team: Team, t: TitleDef) {
    val comps = w.comps.filter { it.season == w.season && team.id in it.teamIds && !it.done }.sortedBy { it.startDay }
    Card("Competition registration", right = { Muted("${t.teamSize + 4} players · 2 changes", 10) }) {
        Muted("Rosters are registered when a competition starts; only registered players can play. Up to two amendments per competition.", 11)
        if (comps.isEmpty()) Muted("No current or upcoming competitions.")
        for (c in comps) {
            Gap(6)
            Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Comp(c.id)) }, verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Muted(if (c.status == 0) "starts ${Dates.short(c.startDay)}" else "in progress", 11)
            }
            val reg = c.registrations[team.id]
            if (reg == null) Muted("Not registered yet (registers at the start: the ${t.teamSize + 4} best players).", 11)
            else {
                Muted("Amendments used: ${reg.amendments} of 2", 11)
                FlowRow { for (pid in reg.players) Pill(w.players[pid].handle + if (pid !in team.roster) " (left)" else "") { vm.go(Screen.Player(pid)) } }
                val missing = team.roster.filter { it !in reg.players }
                if (missing.isNotEmpty()) Muted("Not registered: ${missing.joinToString { w.players[it].handle }}", 11)
            }
        }
    }
}


/** Lineup chemistry (pairs and drilled cohesion) and the league's import rule. */
@Composable
private fun TeamChemistryCard(vm: GameVm, w: World, team: Team) {
    val pairs = remember(team.id, vm.version) { dev.uem.em.mgmt.Chemistry.pairs(w, team) }
    Card("Chemistry", right = { Muted("lineup %.0f".format(dev.uem.em.mgmt.Chemistry.lineup(w, team, team.lineup)), 11) }) {
        Muted("Time together, a shared language, personalities and settled duos build chemistry; scrims and a settled roster build cohesion (%.0f). Better chemistry means better teamfights, rotations and trades.".format(team.chemistry), 11)
        @Composable fun line(pr: dev.uem.em.mgmt.Chemistry.Pair) = Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerLink(w, pr.a, size = 12.sp); Text(" & ", color = Ui.muted, fontSize = 12.sp); PlayerLink(w, pr.b, size = 12.sp)
            Text("  ${pr.value.toInt()}", color = Rate.color(pr.value), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("  " + pr.reasons.joinToString(", "), color = Ui.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (pairs.isNotEmpty()) {
            Gap(4); Text("Strongest pairs", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            for (pr in pairs.take(3)) line(pr)
            Text("Weakest pairs", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            for (pr in pairs.takeLast(3).reversed()) line(pr)
        }
        if (team.leagueId >= 0) {
            Gap(6)
            val imports = team.roster.filter { dev.uem.em.mgmt.Imports.isImport(w, w.players[it], team) }
            KV("Imports", "${imports.size} on the roster · league rule: ${dev.uem.em.mgmt.Imports.ruleText(w, team)}")
            val waiting = team.roster.filter { w.players[it].visaUntil > w.day }
            if (waiting.isNotEmpty()) Muted("Waiting for a work visa: " + waiting.joinToString { "${w.players[it].handle} (until ${Dates.short(w.players[it].visaUntil)})" }, 11)
            if (w.role == "commissioner") Row(verticalAlignment = Alignment.CenterVertically) {
                Muted("Commissioner: imports allowed in this league ", 11)
                for (v in listOf(-1, 1, 2, 3, 5)) Pill(if (v < 0) "no limit" else "$v") { w.importRules[team.leagueId] = v; vm.edit() }
            }
        }
    }
}


/** The roster of every season: the current one, then each season's roster at its end (and who left during it). */
@Composable
private fun TeamPastRosters(vm: GameVm, w: World, team: Team, t: TitleDef) {
    @Composable fun roster(title: String, ids: List<Int>, sub: String?, leftDuring: List<Int>) = Card(title, right = { if (sub != null) Muted(sub, 11) }) {
        if (ids.isEmpty()) Muted("No players recorded.", 11)
        for (pid in ids.sortedBy { t.lineup.indexOf(w.players[it].roles.firstOrNull()).let { i -> if (i < 0) 9 else i } }) {
            val p = w.players.getOrNull(pid) ?: continue
            Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Player(pid)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.role(p.roles.firstOrNull() ?: "").short, color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(40.dp))
                PlayerPic(p, 22.dp, round = true); HGap(6)
                Column(Modifier.weight(1f)) {
                    Text(p.handle, color = Ui.text, fontSize = 13.sp)
                    Muted("${flag(p.nat)} ${p.name()}", 10)
                }
                val line = p.stats.filter { it.teamId == team.id }.lastOrNull()
                if (line != null) Muted("${line.gp} games", 10)
            }
        }
        if (leftDuring.isNotEmpty()) { Gap(4); Muted("Left during the season: " + leftDuring.joinToString { w.players[it].handle }, 11) }
    }
    val seasonsOut = w.transfers.filter { it.fromTeam == team.id }.groupBy { it.season }
    roster("Season ${w.season} (current)", team.roster.toList(), team.leagueId.takeIf { it >= 0 }?.let { w.leagues[it].short }, seasonsOut[w.season].orEmpty().map { it.playerId }.distinct().filter { it !in team.roster })
    // Earlier seasons: the end-of-season snapshot, or (older saves) the players who played for the team that season.
    val seasons = (team.history.map { it.season } + w.players.flatMap { p -> p.stats.filter { it.teamId == team.id }.map { it.season } }).filter { it < w.season }.distinct().sortedDescending()
    if (seasons.isEmpty()) Card { Muted("Past rosters appear here from the end of the first season.") }
    for (s in seasons) {
        val h = team.history.lastOrNull { it.season == s }
        val ids = h?.roster?.toList()?.takeIf { it.isNotEmpty() } ?: w.players.filter { p -> p.stats.any { it.teamId == team.id && it.season == s && it.gp > 0 } }.map { it.id }
        val left = seasonsOut[s].orEmpty().map { it.playerId }.distinct().filter { it !in ids }
        roster("Season $s", ids, h?.let { hh -> listOfNotNull(w.leagues.getOrNull(hh.leagueId)?.short, if (hh.position > 0) "#${hh.position}" else null).joinToString(" · ").ifBlank { null } }, left)
    }
}

/** Transfers in and out of the team, by season. */
@Composable
private fun TeamTransfers(vm: GameVm, w: World, team: Team) {
    val all = w.transfers.filter { it.toTeam == team.id || it.fromTeam == team.id }
    val seasons = (all.map { it.season } + w.season).distinct().sortedDescending()
    var season by remember(team.id) { mutableIntStateOf(w.season) }
    Card("Transfer history") {
        Select("Season", seasons.map { "$it" }, seasons.indexOf(season).coerceAtLeast(0)) { season = seasons[it] }
        val list = all.filter { it.season == season }.sortedBy { it.day }
        @Composable fun line(tr: dev.uem.em.model.Transfer, incoming: Boolean) {
            val p = w.players.getOrNull(tr.playerId) ?: return
            val other = if (incoming) tr.fromTeam else tr.toTeam
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerPic(p, 22.dp, round = true); HGap(6)
                Column(Modifier.weight(1f)) {
                    PlayerLink(w, p.id, weight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Muted(if (incoming) "from " else "to ", 11)
                        if (other >= 0) TeamLink(w, other, size = 11.sp, color = Ui.accent) else Muted(when (tr.kind) { "promoted", "demoted" -> "academy"; "youth" -> "youth intake"; else -> "free agency" }, 11)
                        Muted(" · ${tr.kind} · ${Dates.short(tr.day)}", 11)
                    }
                }
                Text(if (tr.fee > 0) formatMoney(tr.fee) else "free", color = if (tr.fee > 0) Ui.gold else Ui.muted, fontSize = 12.sp)
            }
        }
        val ins = list.filter { it.toTeam == team.id }
        val outs = list.filter { it.fromTeam == team.id }
        Gap(4); Text("In (${ins.size})", color = Ui.win, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (ins.isEmpty()) Muted("No arrivals.", 11)
        for (tr in ins) line(tr, true)
        Gap(6); Text("Out (${outs.size})", color = Ui.loss, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (outs.isEmpty()) Muted("No departures.", 11)
        for (tr in outs) line(tr, false)
    }
}

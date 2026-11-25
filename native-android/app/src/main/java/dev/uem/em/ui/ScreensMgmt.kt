package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.Dates
import dev.uem.em.core.formatMoney
import dev.uem.em.mgmt.Career
import dev.uem.em.mgmt.Development
import dev.uem.em.mgmt.Economy
import dev.uem.em.mgmt.Market
import dev.uem.em.mgmt.Roles
import dev.uem.em.mgmt.Academies
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import kotlin.math.roundToInt

private fun userTeams(w: World) = w.orgs.getOrNull(w.userOrg)?.teams?.map { w.teams[it] }?.filter { it.active } ?: emptyList()

@Composable
private fun Btn(text: String, onClick: () -> Unit) = Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent), shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(end = 6.dp)) { Text(text, fontSize = 13.sp) }

@Composable
private fun Ghost(text: String, color: androidx.compose.ui.graphics.Color = Ui.accent, onClick: () -> Unit) = TextButton(onClick = onClick) { Text(text, color = color, fontSize = 13.sp) }

// ----------------------------------------------------------------------------------------- squad

private val SQUAD_TABS = listOf("Lineup", "Tactics", "Training", "Staff", "Contracts")

@Composable
fun SquadScreen(vm: GameVm, w: World) {
    val teams = userTeams(w)
    if (teams.isEmpty()) { Card { Muted("Your organisation has no active team.") }; return }
    var ti by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(UiDefaults.tab.coerceAtMost(SQUAD_TABS.size - 1)) }
    val team = teams[ti.coerceIn(0, teams.size - 1)]
    val t = Catalogue.get(team.titleId)
    var pickSlot by remember { mutableIntStateOf(-1) }
    var hireRole by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        if (teams.size > 1) item { Tabs(teams.map { Catalogue.get(it.titleId).short + " · " + it.short }, ti) { ti = it } }
        item {
            Tiles(listOf(
                Tile("Strength", "%.1f".format(dev.uem.em.sim.Lineups.strength(w, team)), color = Rate.color(dev.uem.em.sim.Lineups.strength(w, team))),
                Tile("Chemistry", "%.0f".format(team.chemistry), color = Rate.color(team.chemistry)),
                Tile("Meta adaptation", "%.0f".format(team.metaAdapt), color = Rate.color(team.metaAdapt)),
            ))
        }
        item { AcademyCard(vm, w, team) }
        item { Tabs(SQUAD_TABS, tab) { tab = it } }
        item {
            val coach = Roles.can(w, "tactics")
            if (!coach && (tab == 1 || tab == 2)) Card { Muted(Roles.denyReason(w, "tactics") + " You can see the plan but not change it.", 12) }
            if (tab == 3 && !Roles.can(w, "staff")) Card { Muted(Roles.denyReason(w, "staff"), 12) }
            when (tab) {
                0 -> SquadLineup(vm, w, team, t) { if (coach && Roles.handles(w, "lineups")) pickSlot = it else vm.toast = if (!coach) Roles.denyReason(w, "tactics") else "Lineups are delegated to your head coach (Settings → Delegation)" }
                1 -> Box(if (coach) Modifier else Modifier.alpha(0.6f)) { SquadTactics(vm, w, team, t); if (!coach) Box(Modifier.matchParentSize().clickable(enabled = true) {}) }
                2 -> Box(if (coach) Modifier else Modifier.alpha(0.6f)) { SquadTraining(vm, w, team, t); if (!coach) Box(Modifier.matchParentSize().clickable(enabled = true) {}) }
                3 -> SquadStaffPanel(vm, w, team)
                else -> SquadContracts(vm, w, team)
            }
            Gap(16)
        }
    }
    if (pickSlot >= 0) {
        val role = t.lineupRoles[pickSlot]
        val cands = team.roster.map { w.players[it] }.sortedByDescending { dev.uem.em.sim.Ratings.effectiveness(it, t, role) }
        AlertDialog(onDismissRequest = { pickSlot = -1 }, confirmButton = { Ghost("Close") { pickSlot = -1 } }, title = { Text(role.name) }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for (p in cands) Row(Modifier.fillMaxWidth().clickable {
                    val lu = team.lineup.toMutableList()
                    while (lu.size < t.teamSize) lu.add(-1)
                    val prev = lu.indexOf(p.id)
                    if (prev >= 0) lu[prev] = lu[pickSlot]
                    lu[pickSlot] = p.id
                    team.lineup = lu
                    team.manualLineup = true
                    pickSlot = -1
                    vm.edit()
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerPic(p, 28.dp, round = true); HGap(8)
                    Column(Modifier.weight(1f)) { Text(p.handle, color = Ui.text); Muted("${t.role(p.mainRole).short} · fit here %.1f · CA %.1f · fatigue %.0f${if (p.injuryUntil > w.day) " · injured" else ""}".format(dev.uem.em.sim.Ratings.effectiveness(p, t, role), p.ca, p.fatigue)) }
                    if (p.id == team.lineup.getOrNull(pickSlot)) Text("✓", color = Ui.win)
                }
            }
        })
    }
    hireRole?.let { role ->
        val pool = w.staff.filter { it.teamId < 0 && !it.retired && it.role == role }.sortedByDescending { it.quality + if (it.titleId == team.titleId) 5 else 0 }.take(30)
        AlertDialog(onDismissRequest = { hireRole = null }, confirmButton = { Ghost("Close") { hireRole = null } }, title = { Text("Hire: ${Economy.ROLE_LABEL[role]}") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (pool.isEmpty()) Muted("Nobody available.")
                for (s in pool) Row(Modifier.fillMaxWidth().clickable {
                    Economy.staffOf(w, team, role).takeIf { role == "head-coach" }?.forEach { Economy.fire(w, it) }
                    Economy.hire(w, s, team, 2)
                    hireRole = null
                    vm.toast = "${s.name()} hired"
                    vm.edit()
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(s.name(), color = Ui.text); Muted("${Catalogue.BY_ID[s.titleId]?.short ?: ""} · age ${Dates.ageAt(s.birth, w.day)} · asks ${formatMoney(Economy.staffSalary(s, team.region))}") }
                    Rating(s.quality)
                }
            }
        })
    }
}

@Composable
private fun SquadLineup(vm: GameVm, w: World, team: Team, t: dev.uem.em.titles.TitleDef, pick: (Int) -> Unit) {
    Card("Starting lineup · ${team.name}", right = { Ghost(if (team.manualLineup) "Auto-pick" else "Coach picks") { team.manualLineup = false; team.lineupSig = 0; dev.uem.em.sim.Lineups.ensure(w, team); vm.edit() } }) {
        Muted(if (team.manualLineup) "Your lineup (kept while everyone is available)." else "The coach picks the strongest available lineup. Tap a position to choose yourself.", 11)
        Gap(6)
        Row(Modifier.padding(bottom = 2.dp)) {
            Text("Pos", color = Ui.muted, fontSize = 10.sp, modifier = Modifier.width(40.dp)); Text("Player", color = Ui.muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            for (h in listOf("E", "CA", "Form", "Fat.")) Text(h, color = Ui.muted, fontSize = 10.sp, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        t.lineupRoles.forEachIndexed { i, role ->
            val pid = team.lineup.getOrNull(i) ?: -1
            Row(Modifier.fillMaxWidth().clickable { pick(i) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(role.short, color = Ui.accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                if (pid >= 0) {
                    val p = w.players[pid]
                    Column(Modifier.weight(1f)) { PlayerChip(p, vm, 24.dp); if (p.mainRole != role.id) Muted("plays ${t.role(p.mainRole).short} usually", 9) }
                    Column(Modifier.width(40.dp), horizontalAlignment = Alignment.End) { Rating(dev.uem.em.sim.Ratings.effectiveness(p, t, role), 1, 12) }
                    Column(Modifier.width(40.dp), horizontalAlignment = Alignment.End) { Rating(p.ca, size = 12, bold = false) }
                    Text("%+.1f".format(p.form), color = if (p.form >= 0) Ui.win else Ui.loss, fontSize = 12.sp, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text("%.0f".format(p.fatigue) + if (p.injuryUntil > w.day) "✚" else "", color = if (p.fatigue > 60 || p.injuryUntil > w.day) Ui.loss else Ui.text, fontSize = 12.sp, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                } else Text("— empty —", color = Ui.loss, modifier = Modifier.weight(1f))
            }
        }
    }
    val bench = team.roster.filter { it !in team.lineup }.map { w.players[it] }
    Card("Bench (${bench.size})") {
        if (bench.isEmpty()) Muted("No substitutes — sign one in the market.")
        for (p in bench) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Player(p.id)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t.role(p.mainRole).short, color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(40.dp))
            Column(Modifier.weight(1f)) { PlayerChip(p, vm, 22.dp); Muted("age ${Dates.ageAt(p.birth, w.day)} · potential ${dev.uem.em.mgmt.Extras.potentialText(w, p)}${if (p.injuryUntil > w.day) " · injured" else ""}", 10) }
            Rating(p.ca)
        }
    }
}

@Composable
private fun SquadTactics(vm: GameVm, w: World, team: Team, t: dev.uem.em.titles.TitleDef) {
    Card("Team preferences (pre-match)") {
        Muted("Playstyle")
        Row(Modifier.fillMaxWidth()) { Tabs(t.styles, t.styles.indexOf(team.style).coerceAtLeast(0)) { team.style = t.styles[it]; vm.edit() } }
        val styleMeta = dev.uem.em.sim.Patch.of(w, t).styles[team.style] ?: 0.0
        Muted("Current patch rates ${team.style} at %+.1f.".format(styleMeta), 11)
        Gap(4)
        Muted("Aggression ${team.aggression.roundToInt()} — higher fights more often: more kills and objectives, more risk", 12)
        Slider(value = team.aggression.toFloat(), onValueChange = { team.aggression = it.toDouble(); vm.edit() }, valueRange = 10f..90f)
    }
    if (t.characters.isNotEmpty()) {
        val patch = dev.uem.em.sim.Patch.of(w, t)
        Card("${t.charLabel} pools & ban priorities (current meta order)") {
            team.lineup.forEachIndexed { i, pid ->
                if (pid < 0) return@forEachIndexed
                val p = w.players[pid]
                val role = t.lineupRoles[i]
                val pool = t.characters.filter { it.fits(role) }.sortedByDescending { (p.profChar[it.id] ?: 0.0) * 0.6 + (patch.charMeta[it.id] ?: 0.0) * 4 }.take(5)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Column(Modifier.width(70.dp)) { Text(p.handle, color = Ui.text, fontSize = 12.sp, maxLines = 1); Muted(role.short, 10) }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        for (c in pool) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
                            CharPic(c.id, c.name, 32.dp, round = true)
                            Text("${(p.profChar[c.id] ?: 0.0).toInt()}", color = Rate.color(p.profChar[c.id] ?: 0.0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Gap(6)
            Muted("Ban priorities (strongest in the meta)", 11)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
                for (c in t.characters.sortedByDescending { patch.charMeta[it.id] ?: 0.0 }.take(10)) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
                    CharPic(c.id, c.name, 34.dp)
                    Text(c.name, color = Ui.text, fontSize = 8.sp, maxLines = 1)
                    Text("%+.1f".format(patch.charMeta[c.id] ?: 0.0), color = Ui.win, fontSize = 9.sp)
                }
            }
        }
    }
    if (t.maps.size > 1 && !t.isMoba) Card("Map preferences (used in vetoes)") {
        val players = team.lineup.filter { it >= 0 }.map { w.players[it] }
        val maps = t.maps.map { m -> m to players.map { it.profMap[m.id] ?: 40.0 }.average() }.sortedByDescending { it.second }
        maps.forEachIndexed { i, (m, v) ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, color = Ui.text, fontSize = 12.sp, modifier = Modifier.width(90.dp))
                Bar(v, color = Rate.color(v), modifier = Modifier.weight(1f))
                Text(" %.0f ".format(v), color = Ui.text, fontSize = 12.sp)
                Text(if (i < 2) "pick" else if (i >= maps.size - 2) "ban" else "", color = if (i < 2) Ui.win else Ui.loss, fontSize = 11.sp, modifier = Modifier.width(32.dp))
            }
        }
    }
}

@Composable
private fun SquadTraining(vm: GameVm, w: World, team: Team, t: dev.uem.em.titles.TitleDef) {
    Card("Development objective & sessions") {
        Muted("Focus")
        Tabs(Development.FOCUS.map { Development.FOCUS_LABEL[it]!! }, Development.FOCUS.indexOf(team.focus).coerceAtLeast(0)) { team.focus = Development.FOCUS[it]; vm.edit() }
        Muted("Intensity ${team.intensity} — higher grows players faster but tires them", 12)
        Slider(value = team.intensity.toFloat(), onValueChange = { team.intensity = it.roundToInt(); vm.edit() }, valueRange = 30f..95f)
        Muted("Scrims per week: ${team.scrims} — sharper and better drilled, but more tired", 12)
        Slider(value = team.scrims.toFloat(), onValueChange = { team.scrims = it.roundToInt(); vm.edit() }, valueRange = 0f..6f, steps = 5)
        Muted("Solo queue practice — more queue time sharpens mechanics and widens character pools, but tires players", 12)
        Tabs(listOf("Light", "Normal", "Heavy"), team.soloFocus.coerceIn(0, 2)) { team.soloFocus = it; vm.edit() }
        KV("Coaching quality", "%.0f".format(Economy.coachQuality(w, team)))
        KV("Training facilities", "level ${w.orgs[team.orgId].facilities[0]} (Finances)")
    }
    val roster = team.roster.map { w.players[it] }
    Card("Team condition") {
        KV("Average fatigue", "%.0f".format(roster.map { it.fatigue }.average()), if (roster.map { it.fatigue }.average() > 50) Ui.loss else Ui.text)
        KV("Average morale") { Rating(roster.map { it.morale }.average()) }
        KV("Chemistry") { Rating(team.chemistry) }
        KV("Meta adaptation") { Rating(team.metaAdapt) }
        KV("Injured", "${roster.count { it.injuryUntil > w.day }}")
    }
    TeamScrims(vm, w, team, showTraining = false)
    Card("Individual development") {
        val ip = inputOf(t)
        SortTable(roster, listOf(
            SCol("Player", 120.dp, false) { p -> PlayerChip(p, vm, 18.dp) },
            SCol("Role", 40.dp, false) { p -> Muted(t.role(p.mainRole).short, 11) },
            SCol("Age", 36.dp, key = { Dates.ageAt(it.birth, w.day).toDouble() }) { p -> Num("${Dates.ageAt(p.birth, w.day)}") },
            SCol("CA", 40.dp, key = { it.ca }) { p -> Rating(p.ca) },
            SCol("PA", 54.dp, key = { it.pa }) { p -> Num(dev.uem.em.mgmt.Extras.potentialText(w, p), Ui.muted) },
            SCol("Last season", 70.dp, key = { p -> p.caHistory.lastOrNull()?.let { p.ca - it } }) { p -> p.caHistory.lastOrNull()?.let { val d = p.ca - it; Num("%+.1f".format(d), if (d >= 0) Ui.win else Ui.loss) } ?: Muted("—") },
            SCol("Fatigue", 52.dp, key = { it.fatigue }) { p -> Num("%.0f".format(p.fatigue), if (p.fatigue > 60) Ui.loss else Ui.text) },
            SCol("Morale", 50.dp, key = { it.morale }) { p -> Rating(p.morale, bold = false) },
            SCol(listOf("Touch", "M&K", "Pad")[ip], 46.dp, key = { it.input[ip] }) { p -> Rating(p.input[ip], bold = false) },
            SCol("Learning", 58.dp, key = { it.attrs[dev.uem.em.model.Attrs.LEARNING] }) { p -> Rating(p.attrs[dev.uem.em.model.Attrs.LEARNING], bold = false) },
        ), initialSort = 3, onRow = { vm.go(Screen.Player(it.id)) })
        Muted("Players develop monthly by the team focus, intensity, coaching, facilities, age, work ethic and learning speed.", 10)
    }
}

@Composable
private fun SquadStaff(vm: GameVm, w: World, team: Team, hire: (String) -> Unit) {
    Card("Staff · ${team.name}") {
        if (team.staffIds.isEmpty()) Muted("No staff.")
        for (sid in team.staffIds.toList()) {
            val s = w.staff[sid]
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(flag(s.nat).ifEmpty { "·" }, fontSize = 14.sp, modifier = Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name(), color = Ui.text, fontSize = 14.sp)
                    Muted("${Economy.ROLE_LABEL[s.role]} · age ${Dates.ageAt(s.birth, w.day)} · ${formatMoney(s.salary)}/season to ${s.contractEnd}", 11)
                }
                Rating(s.quality)
                if (Roles.can(w, "staff")) Ghost("Release", Ui.loss) { Economy.fire(w, s); vm.edit() }
            }
        }
        Gap(4)
        KV("Coaching quality", "%.0f".format(Economy.coachQuality(w, team)))
        KV("Match preparation", "×%.2f".format(Economy.prepFactor(w, team)))
        KV("Staff payroll", formatMoney(team.staffIds.sumOf { w.staff[it].salary }))
    }
    Card("Hire") {
        for (role in Economy.STAFF_ROLES) Row(Modifier.fillMaxWidth().clickable { hire(role) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Economy.ROLE_LABEL[role]!!, color = Ui.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Muted("${Economy.staffOf(w, team, role).size} employed · ${w.staff.count { it.teamId < 0 && !it.retired && it.role == role }} available ›", 11)
        }
    }
}

@Composable
private fun SquadContracts(vm: GameVm, w: World, team: Team) {
    Card("Contracts") {
        val roster = team.roster.map { w.players[it] }
        SortTable(roster, listOf(
            SCol("Player", 130.dp, false) { p -> PlayerChip(p, vm, 20.dp) },
            SCol("Salary", 70.dp, key = { it.salary }) { p -> Num(formatMoney(p.salary)) },
            SCol("Until", 46.dp, key = { it.contractEnd.toDouble() }) { p -> Num("${p.contractEnd}${if (p.loanFrom >= 0) " L" else ""}", if (p.contractEnd <= w.season) Ui.loss else Ui.text) },
            SCol("Value", 70.dp, key = { Economy.marketValue(w, it) }) { p -> Num(formatMoney(Economy.marketValue(w, p)), Ui.muted) },
            SCol("Market", 70.dp, key = { Economy.expectedSalary(it, it.titleId, team.region) }) { p -> Num(formatMoney(Economy.expectedSalary(p, p.titleId, team.region)), Ui.muted) },
            SCol("Morale", 52.dp, key = { it.morale }) { p -> Num("${p.morale.roundToInt()}", if (p.morale < 40) Ui.loss else Ui.text) },
            SCol("Listed", 46.dp, key = { if (it.listed) 1.0 else 0.0 }) { p -> Num(if (p.listed) "yes" else "", Ui.gold) },
        ), initialSort = 1, onRow = { vm.go(Screen.Player(it.id)) })
        Gap(4)
        KV("Player payroll", formatMoney(roster.sumOf { it.salary }) + " / season")
        KV("Contracts ending this season", "${roster.count { it.contractEnd <= w.season }}")
        Muted("Market = what the player would expect on the open market. Renew from the player's page.", 10)
    }
}

// ----------------------------------------------------------------------------------------- market

private val MARKET_TABS = listOf("Free agents", "Transfer list", "Shortlist", "Player search", "Offers", "Transfer history")

/** The market page last open (players or staff), kept while the app runs. */
object MarketMode { var mode = 0 }

@Composable
fun MarketScreen(vm: GameVm, w: World) {
    val teams = userTeams(w)
    var title by remember { mutableStateOf(teams.firstOrNull()?.titleId ?: "lol") }
    var tab by remember { mutableIntStateOf(UiDefaults.tab.coerceAtMost(MARKET_TABS.size - 1)) }
    var role by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var maxAge by remember { mutableIntStateOf(0) }
    val t = Catalogue.get(title)
    val ages = listOf(99, 19, 21, 23, 26)
    val list = remember(title, tab, role, q, maxAge, vm.version) {
        w.players.asSequence().filter { p ->
            !p.retired && (tab == 2 || p.titleId == title) && (role == null || p.mainRole == role) && (q.isBlank() || p.handle.contains(q, true) || "${p.first} ${p.last}".contains(q, true)) &&
                Dates.ageAt(p.birth, w.day) <= ages[maxAge] &&
                when (tab) { 0 -> p.teamId < 0; 1 -> p.listed && !w.isUserTeam(p.teamId); 2 -> p.id in w.shortlist; else -> !w.isUserTeam(p.teamId) }
        }.sortedByDescending { it.ca }.take(300).toList()
    }
    val pendingIn = w.offers.count { it.status == "pending" && w.isUserTeam(it.toTeam) && !w.isUserTeam(it.fromTeam) }
    val table = rememberTableState(4)
    // Players or staff: the same market screen, two pages.
    var mode by remember { mutableIntStateOf(MarketMode.mode) }
    val staffTable = rememberTableState(4)
    var staffRole by remember { mutableIntStateOf(0) }
    var staffStatus by remember { mutableIntStateOf(0) }
    var staffQ by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Tabs(listOf("Players", "Staff"), mode) { mode = it; MarketMode.mode = it } }
        if (mode == 1) {
            item { Tabs(Catalogue.IDS.map { Catalogue.get(it).short }, Catalogue.IDS.indexOf(title)) { title = Catalogue.IDS[it] } }
            item { Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Muted("Free agents sign at once; staff under contract need their club to accept compensation. Former and retired coaches are free agents. Coaching roles are for one game; support and organisation roles work in any.", 11) } }
            staffMarket(vm, w, title, staffTable, staffRole, staffStatus, staffQ, { staffRole = it }, { staffStatus = it }, { staffQ = it })
            return@LazyColumn
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Muted(Market.windowLabel(w) + " (23 Nov – 12 Jan and 25 May – 20 Jun) · free agents can be signed any time", 12)
                Pill("Scout the solo queue ladders (unsigned talent)") { vm.go(Screen.SoloQ) }
            }
        }
        item {
            val myTeam = teams.firstOrNull { it.titleId == title }
            Tiles(listOfNotNull(
                Tile("Window", if (Market.inWindow(w)) "Open" else "Closed", Market.windowLabel(w), if (Market.inWindow(w)) Ui.win else Ui.muted),
                Tile("Balance", formatMoney(w.orgs.getOrNull(w.userOrg)?.balance ?: 0.0)),
                myTeam?.let { Tile("Squad", "${it.roster.size}", "payroll ${formatMoney(it.roster.sumOf { id -> w.players[id].salary })}") },
                Tile("Offers", "$pendingIn", "for your players"),
            ), perRow = 4)
        }
        item { Tabs(Catalogue.IDS.map { Catalogue.get(it).short }, Catalogue.IDS.indexOf(title)) { title = Catalogue.IDS[it]; role = null } }
        item { Tabs(MARKET_TABS.mapIndexed { i, l -> when (i) { 2 -> "$l (${w.shortlist.size})"; 4 -> "$l ($pendingIn)"; else -> l } }, tab) { tab = it } }
        if (tab == 4) { item { OffersList(vm, w) }; return@LazyColumn }
        if (tab == 5) { item { TransferHistory(vm, w, title) }; return@LazyColumn }
        item { Tabs(listOf("All roles") + t.lineupRoles.map { it.short }, if (role == null) 0 else t.lineup.indexOf(role) + 1) { role = if (it == 0) null else t.lineup[it - 1] } }
        item {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(q, { q = it }, label = { Text("Search name") }, singleLine = true, modifier = Modifier.weight(1f))
                HGap(6)
                Select("Age", listOf("any", "≤19", "≤21", "≤23", "≤26"), maxAge) { maxAge = it }
            }
        }
        item {
            Text("${MARKET_TABS[tab]} · ${list.size} players", color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            if (list.isEmpty()) Card { Muted(if (tab == 2) "Shortlist players from their pages (☆ Shortlist)." else "Nobody matches.") }
        }
        lazySortTable(table, list, listOf(
                    SCol<Player>("Player", 130.dp, false) { p -> Row(verticalAlignment = Alignment.CenterVertically) { p.nats().joinToString("") { flag(it) }.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp); HGap(3) }; PlayerChip(p, vm, 20.dp) } },
                    SCol("Role", 40.dp, false) { p -> Muted(Catalogue.get(p.titleId).role(p.mainRole).short, 11) },
                    SCol("Age", 34.dp, key = { Dates.ageAt(it.birth, w.day).toDouble() }) { p -> Num("${Dates.ageAt(p.birth, w.day)}") },
                    SCol("Club", 56.dp, false) { p -> Muted(p.teamId.takeIf { it >= 0 }?.let { w.teams[it].short } ?: "free", 11) },
                    SCol("CA", 38.dp, key = { it.ca }) { p -> Rating(p.ca) },
                    SCol("PA", 54.dp, key = { it.pa }) { p -> Num(dev.uem.em.mgmt.Extras.potentialText(w, p), Ui.muted) },
                    SCol("Value", 64.dp, key = { Economy.marketValue(w, it) }) { p -> Num(formatMoney(Economy.marketValue(w, p))) },
                    SCol("Salary", 64.dp, key = { it.salary }) { p -> Num(if (p.teamId >= 0) formatMoney(p.salary) else "—", Ui.muted) },
                    SCol("Until", 42.dp, key = { it.contractEnd.toDouble() }) { p -> Num(if (p.teamId >= 0) "${p.contractEnd}" else "—", Ui.muted) },
                    SCol("Status", 80.dp, false) { p ->
                        val bid = w.offers.lastOrNull { it.playerId == p.id && w.isUserTeam(it.fromTeam) }
                        Text(when { bid != null -> "bid ${bid.status}"; p.listed -> "listed"; p.teamId < 0 -> "free agent"; p.contractEnd <= w.season -> "expiring"; else -> "" }, color = if (bid != null) Ui.accent else Ui.muted, fontSize = 11.sp)
                    },
                ), key = { it.id }, onRow = { vm.go(Screen.Player(it.id)) })
    }
}

@Composable
private fun TransferHistory(vm: GameVm, w: World, title: String) {
    val list = w.transfers.filter { w.players[it.playerId].titleId == title }.reversed().take(300)
    Card("Recent transfers · ${Catalogue.get(title).short}") {
        if (list.isEmpty()) Muted("No transfers yet.")
        else SortTable(list, listOf(
            SCol<dev.uem.em.model.Transfer>("Date", 64.dp, false, key = { it.day.toDouble() }) { x -> Muted(Dates.short(x.day), 11) },
            SCol("Player", 120.dp, false) { x -> PlayerChip(w.players[x.playerId], vm, 18.dp) },
            SCol("Type", 70.dp, false) { x -> Muted(x.kind, 11) },
            SCol("From", 64.dp, false) { x -> Muted(w.teams.getOrNull(x.fromTeam)?.short ?: "—", 11) },
            SCol("To", 64.dp, false) { x -> Muted(w.teams.getOrNull(x.toTeam)?.short ?: "—", 11) },
            SCol("Fee", 66.dp, key = { it.fee }) { x -> Num(if (x.fee > 0) formatMoney(x.fee) else "", Ui.gold) },
        ), initialSort = 0, onRow = { vm.go(Screen.Player(it.playerId)) }, highlight = { w.isUserTeam(it.toTeam) || w.isUserTeam(it.fromTeam) })
    }
}

@Composable
private fun OffersList(vm: GameVm, w: World) {
    val mine = w.offers.filter { w.isUserTeam(it.fromTeam) || w.isUserTeam(it.toTeam) }.sortedByDescending { it.day }.take(60)
    if (mine.isEmpty()) { Card { Muted("No offers yet. Bid for a player from his page.") }; return }
    for ((label, group) in listOf("Offers for your players" to mine.filter { w.isUserTeam(it.toTeam) && !w.isUserTeam(it.fromTeam) }, "Your bids and offers" to mine.filter { w.isUserTeam(it.fromTeam) })) {
        if (group.isEmpty()) continue
        Card(label) {
            for (o in group) {
                val p = w.players[o.playerId]
                val incoming = w.isUserTeam(o.toTeam) && !w.isUserTeam(o.fromTeam)
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerPic(p, 30.dp, round = true); HGap(8)
                    Column(Modifier.weight(1f).clickable { vm.go(Screen.Player(p.id)) }) {
                        Text("${if (incoming) "${w.teams[o.fromTeam].name} want " else "You → "}${p.handle}", color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Muted("${Dates.short(o.day)} · fee ${formatMoney(o.fee)} · value ${formatMoney(Economy.marketValue(w, p))} · ${o.status}", 11)
                    }
                    if (incoming && o.status == "pending" && Roles.can(w, "transfers")) {
                        Ghost("Accept", Ui.win) { vm.toast = Market.answerBid(w, o.id, true).text; vm.edit() }
                        Ghost("Reject", Ui.loss) { vm.toast = Market.answerBid(w, o.id, false).text; vm.edit() }
                    }
                    if (!incoming && o.status == "accepted") Ghost("Agree terms") { vm.go(Screen.Player(o.playerId)) }
                }
            }
        }
    }
}

/** Actions on a player's page for the user's organisation. */
@Composable
fun PlayerActions(vm: GameVm, w: World, p: Player) {
    if (w.userOrg < 0 || p.retired) return
    val ofTitle = userTeams(w).filter { it.titleId == p.titleId }
    val own = w.isUserTeam(p.teamId)
    val myTeam = (if (own) w.teams[p.teamId] else ofTitle.firstOrNull { it.parentId < 0 } ?: ofTitle.firstOrNull()) ?: return
    var dialog by remember { mutableStateOf<String?>(null) }
    val canT = Roles.can(w, "transfers")
    val canC = Roles.can(w, "contracts")
    val accepted = w.offers.lastOrNull { it.playerId == p.id && w.isUserTeam(it.fromTeam) && it.status == "accepted" }
    Card("Actions") {
        if (own) {
            Muted("Contract: ${formatMoney(p.salary)} per season until ${p.contractEnd} · value ${formatMoney(Economy.marketValue(w, p))}${if (p.listed) " · transfer listed" else ""}")
            Row {
                if (canC) Btn("Renew") { dialog = "renew" }
                if (canT) Ghost(if (p.listed) "Unlist" else "Transfer list") { p.listed = !p.listed; vm.edit() }
                if (canC) Ghost("Release", Ui.loss) { dialog = "release" }
            }
            if (canT && Dates.ageAt(p.birth, w.day) <= 22) Ghost("Loan out until season end") { vm.toast = dev.uem.em.mgmt.Deals.loanOut(w, p.id).text; vm.edit() }
            val other = if (Academies.isAcademy(myTeam)) Academies.parent(w, myTeam) else Academies.of(w, myTeam)
            if (other != null && (canT || Roles.can(w, "tactics"))) Ghost(if (Academies.isAcademy(myTeam)) "Promote to ${other.short}" else "Move to ${other.short}") { vm.toast = Academies.move(w, p).text; vm.edit() }
            if (!canT && !canC) Muted(Roles.denyReason(w, "transfers"), 11)
        } else if (!canT) {
            Muted(Roles.denyReason(w, "transfers"), 12)
        } else if (p.teamId < 0) {
            Muted("Free agent · asks about ${formatMoney(Market.asking(w, myTeam, p))} per season from ${myTeam.name}")
            Btn("Offer contract") { dialog = "sign" }
        } else if (accepted != null) {
            Muted("${w.teams[p.teamId].name} accepted ${formatMoney(accepted.fee)}. Agree personal terms (asks about ${formatMoney(Market.asking(w, myTeam, p))}).")
            Btn("Agree terms") { dialog = "terms" }
        } else {
            Muted("${w.teams[p.teamId].name} · value ${formatMoney(Economy.marketValue(w, p))} · ${Market.windowLabel(w)}${if (p.loanFrom >= 0) " · on loan from ${w.teams[p.loanFrom].short}" else ""}")
            Row {
                Btn("Make a bid") { dialog = "bid" }
                Ghost("Propose trade") { dialog = "trade" }
                if (p.contractEnd == w.season && Dates.month(w.day) >= 9) Ghost("Pre-contract") { dialog = "pre" }
            }
        }
    }
    when (dialog) {
        "sign", "renew", "terms" -> MoneyDialog(
            title = if (dialog == "terms") "Personal terms: ${p.handle}" else if (dialog == "renew") "Extend ${p.handle}" else "Sign ${p.handle}",
            label = "Salary per season", start = Market.asking(w, myTeam, p), withYears = true,
            onCancel = { dialog = null },
        ) { money, years ->
            val r = when (dialog) {
                "terms" -> Market.completeTransfer(w, accepted!!.id, money, years)
                else -> Market.offerContract(w, p.id, myTeam.id, money, years)
            }
            vm.toast = r.text
            dialog = null
            vm.edit()
        }
        "bid" -> MoneyDialog("Bid for ${p.handle}", "Transfer fee", Economy.marketValue(w, p), false, { dialog = null }) { money, _ ->
            vm.toast = Market.bid(w, p.id, myTeam.id, money).text
            dialog = null
            vm.edit()
        }
        "pre" -> MoneyDialog("Pre-contract: ${p.handle}", "Salary from next season", Market.asking(w, myTeam, p) * 1.05, false, { dialog = null }) { money, _ ->
            vm.toast = dev.uem.em.mgmt.Deals.preContract(w, p.id, myTeam.id, money).text
            dialog = null
            vm.edit()
        }
        "trade" -> {
            val mine = myTeam.roster.map { w.players[it] }.filter { it.loanFrom < 0 }
            var pick by remember { mutableIntStateOf(mine.firstOrNull()?.id ?: -1) }
            var cash by remember { mutableDoubleStateOf(0.0) }
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Trade for ${p.handle}") }, text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Muted("Offer one of your players (value) plus cash:")
                    for (m in mine) Row(Modifier.fillMaxWidth().clickable { pick = m.id }.padding(vertical = 4.dp)) {
                        Text((if (pick == m.id) "● " else "○ ") + m.handle, color = Ui.text)
                        Muted("  ${Catalogue.get(m.titleId).role(m.mainRole).short} · %.0f · ${formatMoney(Economy.marketValue(w, m))}".format(m.ca))
                    }
                    Text("Cash: ${formatMoney(cash)}", color = Ui.text)
                    Slider(value = cash.toFloat(), onValueChange = { cash = (it / 10000).roundToInt() * 10000.0 }, valueRange = 0f..(Economy.marketValue(w, p) * 1.5).toFloat().coerceAtLeast(10000f))
                }
            }, confirmButton = { Ghost("Propose") { if (pick >= 0) vm.toast = dev.uem.em.mgmt.Deals.trade(w, pick, p.id, cash).text; dialog = null; vm.edit() } }, dismissButton = { Ghost("Cancel") { dialog = null } })
        }
        "release" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Release ${p.handle}?") },
            text = { Text("You pay half of the remaining contract (${formatMoney(p.salary * (maxOf(0, p.contractEnd - w.season) + 0.5) * 0.5)}).") },
            confirmButton = { Ghost("Release", Ui.loss) { vm.toast = Market.release(w, p.id).text; dialog = null; vm.edit() } }, dismissButton = { Ghost("Cancel") { dialog = null } })
    }
}

@Composable
private fun MoneyDialog(title: String, label: String, start: Double, withYears: Boolean, onCancel: () -> Unit, onOk: (Double, Int) -> Unit) {
    var factor by remember { mutableDoubleStateOf(1.0) }
    var years by remember { mutableIntStateOf(2) }
    val money = (start * factor / 1000).roundToInt() * 1000.0
    AlertDialog(onDismissRequest = onCancel, title = { Text(title) }, text = {
        Column {
            Text("$label: ${formatMoney(money)}", color = Ui.text)
            Slider(value = factor.toFloat(), onValueChange = { factor = it.toDouble() }, valueRange = 0.6f..1.8f)
            if (withYears) {
                Text("Length: $years season${if (years > 1) "s" else ""}", color = Ui.text)
                Slider(value = years.toFloat(), onValueChange = { years = it.roundToInt() }, valueRange = 1f..4f, steps = 2)
            }
        }
    }, confirmButton = { Ghost("Offer") { onOk(money, years) } }, dismissButton = { Ghost("Cancel") { onCancel() } })
}

// ----------------------------------------------------------------------------------------- office

@Composable
fun InboxScreen(vm: GameVm, w: World) {
    var filter by remember { mutableIntStateOf(0) }
    val all = w.inbox.asReversed()
    val msgs = all.filter { m ->
        when (filter) { 1 -> !m.read; 2 -> m.offerId >= 0; 3 -> m.offerId < 0 && m.from != "Board"; 4 -> m.from == "Board"; else -> true }
    }.take(200)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Muted("${all.count { !it.read }} unread · ${all.size} messages", 12)
                }
                Pill("Mark all read") { w.inbox.forEach { it.read = true }; vm.edit() }
            }
        }
        item { Tabs(listOf("All", "Unread", "Offers", "Reports", "Board"), filter) { filter = it } }
        if (msgs.isEmpty()) item { Card { Muted("No messages.") } }
        items(msgs.size) { i ->
            val m = msgs[i]
            var open by remember(m) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).background(if (m.read) Ui.card else Ui.card2, RoundedCornerShape(10.dp)).clickable { open = !open; if (!m.read) { m.read = true; vm.touch() } }.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!m.read) Text("● ", color = Ui.accent, fontSize = 12.sp)
                    LinkedText(m.subject, Ui.text, 14.sp, if (m.read) FontWeight.Normal else FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (m.offerId >= 0) Tag("offer", Ui.accent.copy(alpha = 0.3f))
                }
                Muted("${m.from} · ${Dates.format(m.day)}", 11)
                if (open) {
                    Gap(6)
                    LinkedText(m.body, Ui.text, 13.sp)
                    val o = w.offers.getOrNull(m.offerId)
                    if (o != null) {
                        val p = w.players[o.playerId]
                        Gap(6)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlayerChip(p, vm, 22.dp)
                            Muted("  fee ${formatMoney(o.fee)} · value ${formatMoney(Economy.marketValue(w, p))} · CA %.0f · ${o.status}".format(p.ca), 11)
                        }
                        if (o.status == "pending" && w.isUserTeam(o.toTeam) && !w.isUserTeam(o.fromTeam) && Roles.handles(w, "sales")) Row {
                            Ghost("Accept", Ui.win) { vm.toast = Market.answerBid(w, o.id, true).text; vm.edit() }
                            Ghost("Reject", Ui.loss) { vm.toast = Market.answerBid(w, o.id, false).text; vm.edit() }
                        }
                        if (o.status == "accepted" && w.isUserTeam(o.fromTeam)) Ghost("Agree personal terms") { vm.go(Screen.Player(o.playerId)) }
                    }
                }
            }
        }
    }
}

@Composable
fun FinancesScreen(vm: GameVm, w: World) {
    if (w.userOrg < 0) { Card { Muted("Spectator mode: no club finances.") }; return }
    val org = w.orgs[w.userOrg]
    val b = Economy.monthly(w, org)
    val prizes = org.ledger.filter { it.kind == "prize" }.sumOf { it.amount }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Muted("${org.name} — organisation-wide accounts", 12)
            }
            Tiles(listOf(
                Tile("Balance", formatMoney(org.balance), color = if (org.balance >= 0) Ui.win else Ui.loss),
                Tile("Monthly revenue", formatMoney(b.income), "projected", Ui.win),
                Tile("Monthly costs", formatMoney(b.costs), "projected", Ui.loss),
                Tile("Net / month", (if (b.income - b.costs >= 0) "+" else "") + formatMoney(b.income - b.costs), color = if (b.income >= b.costs) Ui.win else Ui.loss),
                Tile("Prize money", formatMoney(prizes), "in the ledger"),
                Tile("Months in the red", "${org.months.count { it.balance < 0 }}", "of ${org.months.size}"),
            ))
        }
        item { CommercialCard(vm, w, org) }
        item {
            for ((label, map, col) in listOf(Triple("Revenue breakdown (projected month)", b.revenue, Ui.win), Triple("Expenditure breakdown (projected month)", b.expenses, Ui.loss))) {
                val total = map.values.sum().coerceAtLeast(1.0)
                Card(label, right = { Text(formatMoney(map.values.sum()), color = col, fontSize = 13.sp, fontWeight = FontWeight.Bold) }) {
                    for ((k, v) in map.entries.sortedByDescending { it.value }) Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(k.replaceFirstChar { it.uppercase() }, color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(110.dp))
                        Bar(v, total, col, Modifier.weight(1f))
                        Text(formatMoney(v), color = Ui.text, fontSize = 12.sp, modifier = Modifier.width(72.dp).padding(start = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
            }
        }
        item {
            Card("Balance history") {
                val pts = org.months.map { (it.month.toFloat()) to (it.balance / 1000).toFloat() } + ((org.months.lastOrNull()?.month?.plus(1) ?: 0).toFloat() to (org.balance / 1000).toFloat())
                val mn = pts.minOf { it.second }
                val mx = pts.maxOf { it.second }
                val pad = ((mx - mn) * 0.15f).coerceAtLeast(kotlin.math.abs(mx) * 0.05f + 1f)
                val lo = mn - pad
                val hi = mx + pad
                LineChart(listOf(Series("Balance (thousands)", Ui.accent, pts)), lo, hi, { m -> "${(m.toInt() - 1) % 12 + 1}/${(m.toInt() - 1) / 12}" }, 160.dp)
            }
        }
        item {
            Card("Cash forecast (next 12 months)") {
                var bal = org.balance
                val net = b.income - b.costs
                var shortfall: Int? = null
                val startMonth = Dates.month(w.day)
                for (i in 1..12) {
                    val m = (startMonth - 1 + i) % 12 + 1
                    val seasonOf = w.season + (startMonth - 1 + i) / 12
                    val lost = org.sponsors.filter { it.untilSeason < seasonOf }.sumOf { it.perMonth }
                    bal += net - lost
                    if (bal < 0 && shortfall == null) shortfall = i
                    if (i % 3 == 0 || (shortfall == i)) KV("Month $m/${seasonOf}", formatMoney(bal), if (bal >= 0) Ui.text else Ui.loss)
                }
                Muted(if (shortfall != null) "Shortfall projected in $shortfall month(s): cut wages or sell players." else "No shortfall projected (sponsors that expire are removed from the forecast).", 11)
            }
        }
        item {
            Card("Divisions") {
                for (tid in org.teams) {
                    val tm = w.teams[tid]
                    val pay = tm.roster.sumOf { w.players[it].salary }
                    val st = tm.staffIds.sumOf { w.staff[it].salary }
                    Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Team(tid)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TitleTag(tm.titleId)
                        Column(Modifier.weight(1f)) { Text(tm.name, color = Ui.text, fontSize = 13.sp); Muted("players ${formatMoney(pay)} · staff ${formatMoney(st)} per season", 10) }
                        Text(formatMoney((pay + st) / 12), color = Ui.loss, fontSize = 12.sp)
                        Muted(" /mo", 10)
                    }
                }
            }
        }
        item {
            Card("Supporters") {
                dev.uem.em.mgmt.Supporters.ensure(org)
                Text("${(org.fans / 1000).roundToInt()}K fans", color = Ui.text, fontWeight = FontWeight.Bold)
                dev.uem.em.mgmt.Supporters.SEGMENTS.forEachIndexed { i, n -> Muted("$n: ${(org.fanSeg[i] / 1000).roundToInt()}K", 13) }
                Muted("Core fans buy merchandise; casual fans follow results; local fans follow the league; international fans follow internationals.", 11)
            }
        }
        item {
            Card("Sponsors") {
                for (s in org.sponsors) Muted("${s.name}: ${formatMoney(s.perMonth)}/month until ${s.untilSeason}", 13)
                if (org.sponsors.isEmpty()) Muted("Sponsors sign at the start of each month.")
            }
        }
        item {
            Card("Facilities") {
                val names = listOf("Training centre (player growth)", "Analytics (match preparation)", "Academy (youth intake)")
                names.forEachIndexed { i, n ->
                    val lvl = org.facilities[i]
                    val cost = (40000 * Math.pow(lvl + 1.0, 1.7) * (0.4 + dev.uem.em.data.GameData.economy(org.region)) / 1000).roundToInt() * 1000.0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(n, color = Ui.text, fontSize = 13.sp); Muted("Level $lvl / 10") }
                        if (lvl < 10 && Roles.can(w, "facilities")) Ghost("Upgrade ${formatMoney(cost)}") {
                            if (org.balance < cost) vm.toast = "Not enough money" else { Economy.ledger(w, org, "facility", -cost, "$n level ${lvl + 1}"); org.facilities[i]++; vm.edit() }
                        }
                    }
                }
            }
        }
        item {
            Card("Months") {
                val rows = org.months.asReversed()
                Table(listOf(Col("Month", 70.dp, false), Col("Income", 80.dp), Col("Expenses", 80.dp), Col("Balance", 86.dp)), rows.size) { r, c ->
                    val m = rows[r]
                    when (c) {
                        0 -> Num("${(m.month - 1) % 12 + 1}/${(m.month - 1) / 12}")
                        1 -> Num(formatMoney(m.income), Ui.win)
                        2 -> Num(formatMoney(m.expenses), Ui.loss)
                        else -> Num(formatMoney(m.balance), bold = true)
                    }
                }
            }
        }
        item {
            Card("Ledger") {
                SortTable(org.ledger.asReversed().take(200), listOf(
                    SCol<dev.uem.em.model.LedgerLine>("Date", 64.dp, false, key = { it.day.toDouble() }) { l -> Muted(Dates.short(l.day), 11) },
                    SCol("Type", 70.dp, false) { l -> Muted(l.kind, 11) },
                    SCol("Amount", 76.dp, key = { it.amount }) { l -> Num(formatMoney(l.amount), if (l.amount >= 0) Ui.win else Ui.loss) },
                    SCol("Note", 200.dp, false) { l -> Text(l.note, color = Ui.text, fontSize = 11.sp, maxLines = 2) },
                ), initialSort = 0)
            }
        }
    }
}

@Composable
fun BoardScreen(vm: GameVm, w: World) {
    val b = w.board
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card("Board confidence") {
                if (b == null) { Muted("No board yet."); return@Card }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${b.confidence.roundToInt()}", color = if (b.confidence >= 50) Ui.win else if (b.confidence >= 25) Ui.gold else Ui.loss, fontWeight = FontWeight.Black, fontSize = 30.sp)
                    Text(" / 100", color = Ui.muted, fontSize = 14.sp)
                    HGap(12)
                    Bar(b.confidence, color = if (b.confidence >= 50) Ui.win else if (b.confidence >= 25) Ui.gold else Ui.loss, modifier = Modifier.weight(1f), h = 10.dp)
                }
                Gap(4)
                Text(when { b.confidence >= 70 -> "The board is delighted with your work."; b.confidence >= 50 -> "The board is satisfied."; b.confidence >= 35 -> "The board expects improvement."; b.confidence >= 20 -> "Your job is under threat."; else -> "The board is ready to dismiss you." }, color = Ui.text, fontSize = 13.sp)
                KV("Your contract", "until the end of ${b.contractEnd}")
                KV("Renewal", "offered at confidence 40+ when the contract ends")
                KV("Dismissal", "below 20 at the end of a season")
                KV("Difficulty", w.difficulty)
            }
        }
        if (b != null) {
            item {
                Card("Objectives ${b.season}") {
                    for (o in b.objectives) {
                        val progress = when (o.kind) {
                            "league" -> {
                                val team = w.teams.getOrNull(o.teamId)
                                val comp = team?.let { tm -> w.comps.lastOrNull { it.leagueId == tm.leagueId && it.season == w.season && it.status > 0 } }
                                val st = comp?.stages?.firstOrNull { it.type == dev.uem.em.model.StageType.RR || it.type == dev.uem.em.model.StageType.DRR }
                                val pos = st?.let { s2 -> dev.uem.em.comp.Stages.sortStandings(w, s2.standings).indexOfFirst { it.teamId == o.teamId } + 1 }
                                if (pos != null && pos > 0) "now ${dev.uem.em.core.ordinal(pos)} in ${comp.short} (target: top ${o.target})" else "target: top ${o.target}"
                            }
                            "balance" -> "balance ${formatMoney(w.orgs[w.userOrg].balance)} (floor ${formatMoney(o.target.toDouble())})"
                            "worlds" -> "Worlds qualification decided by circuit points"
                            else -> ""
                        }
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                            Text(when (o.status) { "met" -> "✔"; "missed", "failed" -> "✖"; else -> "•" }, color = when (o.status) { "met" -> Ui.win; "missed", "failed" -> Ui.loss; else -> Ui.muted }, fontSize = 15.sp, modifier = Modifier.width(22.dp))
                            Column(Modifier.weight(1f)) {
                                Text(o.text, color = Ui.text, fontSize = 13.sp)
                                if (progress.isNotEmpty()) Muted(progress, 11)
                            }
                            Tag(o.status, when (o.status) { "met" -> Ui.win.copy(alpha = 0.3f); "missed", "failed" -> Ui.loss.copy(alpha = 0.3f); else -> Ui.card2 })
                        }
                    }
                }
            }
            item { Card("Board reactions") { for (n in b.notes.asReversed()) Row(Modifier.padding(vertical = 2.dp)) { Text("• ", color = Ui.muted); Text(n, color = Ui.text, fontSize = 12.sp) }; if (b.notes.isEmpty()) Muted("None yet.") } }
        }
    }
}

@Composable
fun JobOffersScreen(vm: GameVm, w: World) {
    val offers = Career.jobOffers(w)
    LazyColumn(Modifier.fillMaxSize()) {
        item { Card("You have been dismissed") { Muted("These organisations would hire you:") } }
        items(offers) { oid ->
            val o = w.orgs[oid]
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(o.name, color = Ui.text, fontWeight = FontWeight.SemiBold)
                        Muted(o.teams.map { w.teams[it] }.filter { it.active }.joinToString { "${Catalogue.get(it.titleId).short} ${it.leagueId.takeIf { l -> l >= 0 }?.let { l -> w.leagues[l].short } ?: ""}" } + " · reputation ${o.rep.roundToInt()}")
                    }
                    Btn("Accept") { Career.takeJob(w, oid); vm.tab(Screen.Home); vm.edit() }
                }
            }
        }
    }
}

@Composable
fun PatchesScreen(vm: GameVm, w: World) {
    var title by remember { mutableStateOf(userTeams(w).firstOrNull()?.titleId ?: "lol") }
    val list = w.patches.filter { it.titleId == title }.asReversed()
    LazyColumn(Modifier.fillMaxSize()) {
        item { Tabs(Catalogue.IDS.map { Catalogue.get(it).short }, Catalogue.IDS.indexOf(title)) { title = Catalogue.IDS[it] } }
        item {
            val t = Catalogue.get(title)
            val patch = dev.uem.em.sim.Patch.of(w, t)
            Card("Current meta · ${list.firstOrNull()?.version ?: "launch"}") {
                if (t.characters.isNotEmpty()) {
                    val sorted = t.characters.sortedByDescending { patch.charMeta[it.id] ?: 0.0 }
                    for ((label, group) in listOf("Strongest" to sorted.take(10), "Weakest" to sorted.takeLast(6).reversed())) {
                        Muted(label, 11)
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                            for (c in group) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(54.dp)) {
                                CharPic(c.id, c.name, 36.dp, round = true)
                                Text(c.name, color = Ui.text, fontSize = 8.sp, maxLines = 1)
                                val m = patch.charMeta[c.id] ?: 0.0
                                Text("%+.1f".format(m), color = if (m >= 0) Ui.win else Ui.loss, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (patch.styles.isNotEmpty()) {
                    Muted("Playstyles", 11)
                    for ((st, v) in patch.styles.entries.sortedByDescending { it.value }) Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(st, color = Ui.text, fontSize = 12.sp, modifier = Modifier.width(100.dp))
                        Text("%+.1f".format(v), color = if (v >= 0) Ui.win else Ui.loss, fontSize = 12.sp)
                    }
                }
                if (patch.obj.isNotEmpty()) {
                    Muted("Objectives", 11)
                    for ((o, v) in patch.obj) KV(t.moba?.objectives?.firstOrNull { it.id == o }?.name ?: o, "×%.2f".format(v))
                }
                // Role metas: what each position does on this patch (the match engine plays them).
                val metas = dev.uem.em.titles.RoleMeta.current(w, t.id)
                if (metas.isNotEmpty()) {
                    Muted("Role metas", 11)
                    for ((i, id) in metas.withIndex()) Column(Modifier.padding(vertical = 3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(dev.uem.em.titles.RoleMeta.GROUP_LABEL[dev.uem.em.titles.RoleMeta.GROUPS[i]] ?: "", color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(64.dp))
                            Text(dev.uem.em.titles.RoleMeta.label(t.id, id), color = Ui.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Muted(dev.uem.em.titles.RoleMeta.desc(id), 10)
                    }
                    val prio = dev.uem.em.titles.RoleMeta.killPriority(w, t.id).entries.sortedByDescending { it.value }
                    Muted("Kill share expected: " + prio.joinToString(" > ") { dev.uem.em.titles.RoleMeta.posName(t.id, it.key) }, 10)
                }
            }
        }
        if (list.isEmpty()) item { Card { Muted("No patches yet. Minor patches come every few weeks, a bigger one before each international event, and a major patch after Worlds.") } }
        items(list.size) { i ->
            val p = list[i]
            Card("${p.version} · ${p.kind} patch", right = { Muted(Dates.format(p.day)) }) { for (n in p.notes) Text("• $n", color = Ui.text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

@Composable
fun TeamTitleTabs(w: World, teams: List<Team>, selected: Int, onSelect: (Int) -> Unit) = Tabs(teams.map { Catalogue.get(it.titleId).short }, selected, onSelect)


/** The team's academy (or its parent team): players, league, founding a new one. */
@Composable
private fun AcademyCard(vm: GameVm, w: World, team: Team) {
    if (team.nationalOf.isNotEmpty()) return
    val parent = Academies.parent(w, team)
    val academy = Academies.of(w, team)
    val league = { tm: Team -> w.leagues.getOrNull(tm.leagueId)?.name ?: "development squad (no league)" }
    if (parent != null) {
        Card("Academy of ${parent.name}") {
            Muted("Plays in ${league(team)}. Players move up to ${parent.short} from their page (no fee, same contract). Academy players train with the academy facility (level ${w.orgs[team.orgId].facilities.getOrElse(2) { 3 }}) and develop faster.", 12)
            Row { TextButton(onClick = { vm.go(Screen.Team(parent.id)) }) { Text("Open ${parent.short}", color = Ui.accent, fontSize = 13.sp) } }
        }
    } else if (academy != null) {
        Card("Academy · ${academy.name}") {
            val best = Academies.prospects(w, academy, null).take(3)
            Muted("${league(academy)} · ${academy.roster.size} players · best prospects: " + best.joinToString { "${it.handle} (${Catalogue.get(it.titleId).role(it.mainRole).short}, %.0f, age ${Dates.ageAt(it.birth, w.day)})".format(it.ca) }, 12)
            Row { TextButton(onClick = { vm.go(Screen.Team(academy.id)) }) { Text("Open academy", color = Ui.accent, fontSize = 13.sp) } }
        }
    } else {
        Card("Academy") {
            val cost = Academies.cost(w, team)
            val where = Academies.leagueFor(w, team)?.let { "It would play in ${it.name}." } ?: "Your region has no Challengers league, so it would train and scrim as a development squad."
            Muted("Found an academy team of young prospects for ${team.name}. $where Each year it signs the best of the region's youth intake.", 12)
            if (Roles.can(w, "expansion")) TextButton(onClick = { val r = Academies.found(w, team); vm.toast = r.text; if (r.ok) vm.edit() }) { Text("Found academy · ${formatMoney(cost)}", color = Ui.accent, fontSize = 13.sp) }
            else Muted("As ${Roles.LABEL[w.role]} you cannot found academies — the owner or sporting director decides.", 11)
        }
    }
}

/** Sponsorship offers (accept, decline, negotiate), current deals, streaming and merchandise. */
@Composable
fun CommercialCard(vm: GameVm, w: World, org: dev.uem.em.model.Org) {
    var note by remember { mutableStateOf<String?>(null) }
    Card("Commercial") {
        val offers = w.sponsorOffers.filter { it.orgId == org.id }
        if (offers.isEmpty()) Muted(if (Roles.delegated(w, "sponsors")) "Your commercial director signs sponsorship offers as they come." else "No sponsorship offers right now. New offers arrive at the start of a month when a slot is free.", 11)
        for (o in offers) {
            Gap(4)
            Text("${o.name} · ${o.industry}", color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Muted("${formatMoney(o.perMonth)} per month · ${o.seasons} season(s) · ${formatMoney(o.bonus)} per trophy · answer by ${dev.uem.em.core.Dates.short(o.expires)}${if (o.note.isNotEmpty()) " · " + o.note else ""}", 11)
            androidx.compose.foundation.layout.FlowRow {
                Pill("Accept", Ui.win.copy(alpha = 0.35f)) { note = dev.uem.em.mgmt.Commerce.accept(w, o.id); vm.edit() }
                Pill("Ask +10%") { note = dev.uem.em.mgmt.Commerce.negotiate(w, o.id, 0.10, false); vm.edit() }
                Pill("Ask +25%") { note = dev.uem.em.mgmt.Commerce.negotiate(w, o.id, 0.25, false); vm.edit() }
                Pill("Longer deal") { note = dev.uem.em.mgmt.Commerce.negotiate(w, o.id, 0.0, true); vm.edit() }
                Pill("Performance clauses") { note = dev.uem.em.mgmt.Commerce.negotiateBonus(w, o.id); vm.edit() }
                Pill("Decline", Ui.loss.copy(alpha = 0.3f)) { dev.uem.em.mgmt.Commerce.decline(w, o.id); note = "Declined ${o.name}"; vm.edit() }
            }
        }
        note?.let { Text(it, color = Ui.gold, fontSize = 12.sp) }
        val current = org.sponsors.filter { it.untilSeason >= w.season }
        if (current.isNotEmpty()) {
            Gap(6); Text("Current sponsors", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            for (s in current) Muted("${s.name}${if (s.industry.isNotEmpty()) " (${s.industry})" else ""}: ${formatMoney(s.perMonth)}/month until ${s.untilSeason}${if (s.bonus > 0) " · ${formatMoney(s.bonus)} per trophy" else ""}", 11)
        }
        Gap(8)
        Text("Players' streaming", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Muted("The club takes a share of streaming income and gains fans; heavy streaming tires players, and some players dislike a ban. Expected: ${formatMoney(dev.uem.em.mgmt.Commerce.streaming(w, org))} per month.", 11)
        Tabs(dev.uem.em.mgmt.Commerce.STREAM_LABEL, org.streamPolicy.coerceIn(0, 2)) { org.streamPolicy = it; vm.edit() }
        Text("Merchandise range", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Muted("Bigger ranges sell more (jerseys sell best after trophies and with star players) but cost ${formatMoney(dev.uem.em.mgmt.Commerce.merchCost(org))} per month to run at this level.", 11)
        Tabs(dev.uem.em.mgmt.Commerce.MERCH_LABEL, org.merchLevel.coerceIn(0, 3)) { org.merchLevel = it; vm.edit() }
    }
}

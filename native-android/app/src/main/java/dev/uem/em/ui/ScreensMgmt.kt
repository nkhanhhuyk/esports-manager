package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

@Composable
fun SquadScreen(vm: GameVm, w: World) {
    val teams = userTeams(w)
    if (teams.isEmpty()) { Card { Muted("Your organisation has no active team.") }; return }
    var ti by remember { mutableIntStateOf(0) }
    val team = teams[ti.coerceIn(0, teams.size - 1)]
    val t = Catalogue.get(team.titleId)
    var pickSlot by remember { mutableIntStateOf(-1) }
    var hireRole by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        if (teams.size > 1) item { Tabs(teams.map { Catalogue.get(it.titleId).short }, ti) { ti = it } }
        item {
            Card("Lineup · ${team.name}", right = { Ghost(if (team.manualLineup) "Auto-pick" else "Coach picks") { team.manualLineup = false; team.lineupSig = 0; dev.uem.em.sim.Lineups.ensure(w, team); vm.edit() } }) {
                Muted(if (team.manualLineup) "Your lineup (kept while everyone is available)." else "The coach picks the strongest available lineup. Tap a position to choose yourself.")
                Gap(6)
                t.lineupRoles.forEachIndexed { i, role ->
                    val pid = team.lineup.getOrNull(i) ?: -1
                    Row(Modifier.fillMaxWidth().clickable { pickSlot = i }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(role.short, color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(44.dp))
                        if (pid >= 0) {
                            val p = w.players[pid]
                            Column(Modifier.weight(1f)) { PlayerChip(p, vm, 24.dp) }
                            Num("%.1f".format(dev.uem.em.sim.Ratings.effectiveness(p, t, role)), bold = true)
                        } else Text("— empty —", color = Ui.loss, modifier = Modifier.weight(1f))
                    }
                }
                val bench = team.roster.filter { it !in team.lineup }.map { w.players[it] }
                if (bench.isNotEmpty()) { Gap(6); Muted("Bench: " + bench.joinToString { "${it.handle} (${t.role(it.mainRole).short} ${"%.0f".format(it.ca)})" }) }
            }
        }
        item {
            Card("Tactics") {
                Muted("Playstyle")
                Row(Modifier.fillMaxWidth()) { Tabs(t.styles, t.styles.indexOf(team.style).coerceAtLeast(0)) { team.style = t.styles[it]; vm.edit() } }
                Muted("Aggression ${team.aggression.roundToInt()}")
                Slider(value = team.aggression.toFloat(), onValueChange = { team.aggression = it.toDouble(); vm.edit() }, valueRange = 10f..90f)
            }
        }
        item {
            Card("Training") {
                Muted("Focus")
                Tabs(Development.FOCUS.map { Development.FOCUS_LABEL[it]!! }, Development.FOCUS.indexOf(team.focus).coerceAtLeast(0)) { team.focus = Development.FOCUS[it]; vm.edit() }
                Muted("Intensity ${team.intensity} — higher grows players faster but tires them")
                Slider(value = team.intensity.toFloat(), onValueChange = { team.intensity = it.roundToInt(); vm.edit() }, valueRange = 30f..95f)
                Muted("Scrims per week: ${team.scrims} — sharper and better drilled, but more tired")
                Slider(value = team.scrims.toFloat(), onValueChange = { team.scrims = it.roundToInt(); vm.edit() }, valueRange = 0f..6f, steps = 5)
                Muted("Facilities: training level ${w.orgs[team.orgId].facilities[0]} (Finances)")
            }
        }
        item {
            Card("Staff") {
                for (sid in team.staffIds.toList()) {
                    val s = w.staff[sid]
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name(), color = Ui.text, fontSize = 14.sp)
                            Muted("${Economy.ROLE_LABEL[s.role]} · quality ${s.quality.roundToInt()} · ${formatMoney(s.salary)}/season to ${s.contractEnd}")
                        }
                        Ghost("Release", Ui.loss) { Economy.fire(w, s); vm.edit() }
                    }
                }
                Gap(4)
                Row(Modifier.fillMaxWidth()) { Tabs(Economy.STAFF_ROLES.map { "Hire ${Economy.ROLE_LABEL[it]!!.lowercase()}" }, -1) { hireRole = Economy.STAFF_ROLES[it] } }
            }
        }
        item {
            Card("Contracts") {
                val roster = team.roster.map { w.players[it] }.sortedByDescending { it.salary }
                Table(listOf(Col("Player", 140.dp, false), Col("Salary", 70.dp), Col("Until", 46.dp), Col("Value", 70.dp), Col("Morale", 52.dp)), roster.size, onRow = { vm.go(Screen.Player(roster[it].id)) }) { r, c ->
                    val p = roster[r]
                    when (c) {
                        0 -> PlayerChip(p, vm, 20.dp)
                        1 -> Num(formatMoney(p.salary))
                        2 -> Num("${p.contractEnd}", if (p.contractEnd <= w.season) Ui.loss else Ui.text)
                        3 -> Num(formatMoney(Economy.marketValue(w, p)), Ui.muted)
                        else -> Num("${p.morale.roundToInt()}", if (p.morale < 40) Ui.loss else Ui.text)
                    }
                }
                Muted("Payroll ${formatMoney(Economy.payroll(w, team))} per season")
            }
        }
    }
    if (pickSlot >= 0) {
        val role = t.lineupRoles[pickSlot]
        val cands = team.roster.map { w.players[it] }.sortedByDescending { dev.uem.em.sim.Ratings.effectiveness(it, t, role) }
        AlertDialog(onDismissRequest = { pickSlot = -1 }, confirmButton = { Ghost("Close") { pickSlot = -1 } }, title = { Text("${role.name}") }, text = {
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
                    Column(Modifier.weight(1f)) { Text(p.handle, color = Ui.text); Muted("${t.role(p.mainRole).short} · fit here %.1f · CA %.1f${if (p.injuryUntil > w.day) " · injured" else ""}".format(dev.uem.em.sim.Ratings.effectiveness(p, t, role), p.ca)) }
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
                }.padding(vertical = 6.dp)) {
                    Column(Modifier.weight(1f)) { Text(s.name(), color = Ui.text); Muted("quality ${s.quality.roundToInt()} · ${Catalogue.BY_ID[s.titleId]?.short ?: ""} · asks ${formatMoney(Economy.staffSalary(s, team.region))}") }
                }
            }
        })
    }
}

// ----------------------------------------------------------------------------------------- market

@Composable
fun MarketScreen(vm: GameVm, w: World) {
    val teams = userTeams(w)
    var title by remember { mutableStateOf(teams.firstOrNull()?.titleId ?: "lol") }
    var tab by remember { mutableIntStateOf(0) }
    var role by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    val t = Catalogue.get(title)
    val list = remember(title, tab, role, q, vm.version) {
        w.players.asSequence().filter { p ->
            !p.retired && p.titleId == title && (role == null || p.mainRole == role) && (q.isBlank() || p.handle.contains(q, true)) &&
                when (tab) { 0 -> p.teamId < 0; 1 -> p.listed && !w.isUserTeam(p.teamId); else -> !w.isUserTeam(p.teamId) }
        }.sortedByDescending { it.ca }.take(150).toList()
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) { Muted(Market.windowLabel(w) + " · free agents can be signed any time", 12) }
        Tabs(Catalogue.IDS.map { Catalogue.get(it).short }, Catalogue.IDS.indexOf(title)) { title = Catalogue.IDS[it]; role = null }
        Tabs(listOf("Free agents", "Transfer list", "All players", "My offers"), tab) { tab = it }
        if (tab == 3) { OffersList(vm, w); return@Column }
        Tabs(listOf("All") + t.lineupRoles.map { it.short }, if (role == null) 0 else t.lineup.indexOf(role) + 1) { role = if (it == 0) null else t.lineup[it - 1] }
        androidx.compose.material3.OutlinedTextField(q, { q = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Player(p.id)) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pic(Pics.player(p), p.handle, 34.dp, round = true)
                    HGap(10)
                    Column(Modifier.weight(1f)) {
                        Text(p.handle, color = Ui.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted("${t.role(p.mainRole).short} · ${p.nat} · ${Dates.ageAt(p.birth, w.day)} · ${p.teamId.takeIf { it >= 0 }?.let { w.teams[it].short } ?: "free agent"} · value ${formatMoney(Economy.marketValue(w, p))}")
                    }
                    Column(horizontalAlignment = Alignment.End) { Num("%.1f".format(p.ca), bold = true); Muted("pot %.0f".format(p.pa), 10) }
                }
            }
        }
    }
}

@Composable
private fun OffersList(vm: GameVm, w: World) {
    val mine = w.offers.filter { w.isUserTeam(it.fromTeam) || w.isUserTeam(it.toTeam) }.sortedByDescending { it.day }.take(60)
    LazyColumn(Modifier.fillMaxSize()) {
        if (mine.isEmpty()) item { Card { Muted("No offers yet. Bid for a player from his page.") } }
        items(mine, key = { it.id }) { o ->
            val p = w.players[o.playerId]
            val incoming = w.isUserTeam(o.toTeam) && !w.isUserTeam(o.fromTeam)
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${if (incoming) "${w.teams[o.fromTeam].name} → " else "You → "}${p.handle}", color = Ui.text, fontWeight = FontWeight.SemiBold)
                        Muted("${Dates.short(o.day)} · fee ${formatMoney(o.fee)} · ${o.status}")
                    }
                    if (incoming && o.status == "pending") {
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
    val myTeam = userTeams(w).firstOrNull { it.titleId == p.titleId } ?: return
    var dialog by remember { mutableStateOf<String?>(null) }
    val own = p.teamId == myTeam.id
    val accepted = w.offers.lastOrNull { it.playerId == p.id && w.isUserTeam(it.fromTeam) && it.status == "accepted" }
    Card("Actions") {
        if (own) {
            Muted("Contract: ${formatMoney(p.salary)} per season until ${p.contractEnd} · value ${formatMoney(Economy.marketValue(w, p))}${if (p.listed) " · transfer listed" else ""}")
            Row { Btn("Renew") { dialog = "renew" }; Ghost(if (p.listed) "Unlist" else "Transfer list") { p.listed = !p.listed; vm.edit() }; Ghost("Release", Ui.loss) { dialog = "release" } }
            if (Dates.ageAt(p.birth, w.day) <= 22) Ghost("Loan out until season end") { vm.toast = dev.uem.em.mgmt.Deals.loanOut(w, p.id).text; vm.edit() }
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
    val msgs = w.inbox.asReversed().take(150)
    LazyColumn(Modifier.fillMaxSize()) {
        if (msgs.isEmpty()) item { Card { Muted("No messages.") } }
        items(msgs.size) { i ->
            val m = msgs[i]
            var open by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).background(Ui.card, RoundedCornerShape(10.dp)).clickable { open = !open; m.read = true }.padding(12.dp)) {
                Text(m.subject, color = Ui.text, fontWeight = if (m.read) FontWeight.Normal else FontWeight.Bold)
                Muted("${m.from} · ${Dates.format(m.day)}")
                if (open) {
                    Gap(6)
                    Text(m.body, color = Ui.text, fontSize = 13.sp)
                    val o = w.offers.getOrNull(m.offerId)
                    if (o != null && o.status == "pending" && w.isUserTeam(o.toTeam) && !w.isUserTeam(o.fromTeam)) Row {
                        Ghost("Accept", Ui.win) { vm.toast = Market.answerBid(w, o.id, true).text; vm.edit() }
                        Ghost("Reject", Ui.loss) { vm.toast = Market.answerBid(w, o.id, false).text; vm.edit() }
                    }
                    if (o != null && o.status == "accepted" && w.isUserTeam(o.fromTeam)) Ghost("Agree personal terms") { vm.go(Screen.Player(o.playerId)) }
                }
            }
        }
    }
}

@Composable
fun FinancesScreen(vm: GameVm, w: World) {
    val org = w.orgs[w.userOrg]
    val b = Economy.monthly(w, org)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card("Balance") {
                Text(formatMoney(org.balance), color = if (org.balance >= 0) Ui.win else Ui.loss, fontWeight = FontWeight.Black, fontSize = 24.sp)
                Muted("Expected this month: income ${formatMoney(b.income)}, expenses ${formatMoney(b.costs)}")
                Gap(6)
                for ((k, v) in b.revenue) Row { Text(k, color = Ui.muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Num("+" + formatMoney(v), Ui.win) }
                for ((k, v) in b.expenses) Row { Text(k, color = Ui.muted, fontSize = 13.sp, modifier = Modifier.weight(1f)); Num("-" + formatMoney(v), Ui.loss) }
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
                        if (lvl < 10) Ghost("Upgrade ${formatMoney(cost)}") {
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
        item { Card("Ledger") { for (l in org.ledger.asReversed().take(40)) Row { Muted("${Dates.short(l.day)}  ${l.note.ifBlank { l.kind }}", 12); Text("  " + formatMoney(l.amount), color = if (l.amount >= 0) Ui.win else Ui.loss, fontSize = 12.sp) } } }
    }
}

@Composable
fun BoardScreen(vm: GameVm, w: World) {
    val b = w.board
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card("Board confidence") {
                if (b == null) { Muted("No board yet."); return@Card }
                Text("${b.confidence.roundToInt()} / 100", color = if (b.confidence >= 50) Ui.win else if (b.confidence >= 25) Ui.gold else Ui.loss, fontWeight = FontWeight.Black, fontSize = 24.sp)
                Muted("Your contract runs until the end of ${b.contractEnd}. Below 20 at the end of a season the board dismisses you.")
            }
        }
        if (b != null) {
            item { Card("Objectives ${b.season}") { for (o in b.objectives) Row { Text(if (o.status == "met") "✓ " else if (o.status == "missed") "✗ " else "• ", color = if (o.status == "met") Ui.win else if (o.status == "missed") Ui.loss else Ui.muted); Text(o.text, color = Ui.text, fontSize = 13.sp) } } }
            item { Card("Reactions") { for (n in b.notes.asReversed()) Muted(n, 12); if (b.notes.isEmpty()) Muted("None yet.") } }
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
fun HistoryScreen(vm: GameVm, w: World) {
    var title by remember { mutableStateOf(userTeams(w).firstOrNull()?.titleId ?: "lol") }
    val seasons = w.comps.filter { it.titleId == title && it.done }.map { it.season }.distinct().sortedDescending()
    LazyColumn(Modifier.fillMaxSize()) {
        item { Tabs(Catalogue.IDS.map { Catalogue.get(it).short }, Catalogue.IDS.indexOf(title)) { title = Catalogue.IDS[it] } }
        if (seasons.isEmpty()) item { Card { Muted("No finished competitions yet.") } }
        for (s in seasons) item(key = "s$s") {
            Card("Season $s") {
                val comps = w.comps.filter { it.season == s && it.titleId == title && it.done && it.placements.isNotEmpty() && it.tier != dev.uem.em.model.CompTier.CHALLENGER }.sortedBy { it.startDay }
                for (c in comps) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Comp(c.id)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.short, color = if (c.tier == dev.uem.em.model.CompTier.WORLDS) Ui.gold else Ui.muted, fontSize = 12.sp, modifier = Modifier.width(100.dp), maxLines = 1)
                    TeamChip(w, c.placements[0], vm, 18.dp)
                }
                val aw = w.awards.filter { it.season == s && it.titleId == title }
                if (aw.isNotEmpty()) { Gap(6); for (a in aw) Muted("${a.name}: ${w.players[a.playerId].handle}", 12) }
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
        if (list.isEmpty()) item { Card { Muted("No patches yet. Minor patches come every few weeks, a bigger one before each international event, and a major patch after Worlds.") } }
        items(list.size) { i ->
            val p = list[i]
            Card("${p.version} · ${p.kind} patch", right = { Muted(Dates.format(p.day)) }) { for (n in p.notes) Text("• $n", color = Ui.text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

@Composable
fun TeamTitleTabs(w: World, teams: List<Team>, selected: Int, onSelect: (Int) -> Unit) = Tabs(teams.map { Catalogue.get(it.titleId).short }, selected, onSelect)

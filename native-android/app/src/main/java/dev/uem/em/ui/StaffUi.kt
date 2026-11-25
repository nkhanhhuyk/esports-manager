package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.Dates
import dev.uem.em.core.formatMoney
import dev.uem.em.data.GameData
import dev.uem.em.mgmt.Legacy
import dev.uem.em.mgmt.Roles
import dev.uem.em.mgmt.Staffing
import dev.uem.em.model.Staff
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import kotlin.math.roundToInt

/** A staff member's name, tappable. */
@Composable
fun StaffChip(s: Staff, vm: GameVm, bold: Boolean = false) =
    Text(s.name(), color = Ui.text, fontSize = 13.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable { vm.go(Screen.Staff(s.id)) })

private fun canManage(w: World) = w.userOrg >= 0 && Roles.can(w, "staff")

/** The user's teams this person could work for (same game for coaching roles). */
private fun hirers(w: World, s: Staff, role: String): List<Team> = w.orgs.getOrNull(w.userOrg)?.teams?.map { w.teams[it] }?.filter { it.active && (!Staffing.def(role).titleBound || it.titleId == s.titleId) } ?: emptyList()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StaffScreen(vm: GameVm, w: World, id: Int) {
    val s = w.staff.getOrNull(id) ?: run { Card { Muted("Unknown staff member.") }; return }
    val team = w.teams.getOrNull(s.teamId)
    val own = team != null && w.isUserTeam(team.id)
    val hs = remember(id, vm.version) { Staffing.honours(w, s) }
    val lg = Staffing.legacy(w, s, hs)
    var offer by remember(id) { mutableStateOf(false) }
    var renew by remember(id) { mutableStateOf(false) }
    var roleDlg by remember(id) { mutableStateOf(false) }
    var confirmRelease by remember(id) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(20.dp)).background(if (own) Ui.heroGoldSoft else Ui.heroSoft).padding(16.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        flag(s.nat).takeIf { it.isNotEmpty() }?.let { Text("$it ", fontSize = 18.sp) }
                        Text(s.name(), color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (s.handle.isNotEmpty() && s.fullName().isNotEmpty()) Muted(s.fullName(), 13)
                    FlowRow { TitleTag(s.titleId); Text(Staffing.label(s), color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    Gap(2)
                    if (team != null) TeamChip(w, team.id, vm, 18.dp) else Muted(if (s.retired) "Retired ${s.retiredSeason}" else "Free agent", 12)
                    Muted("Age ${Staffing.age(w, s)} · ${GameData.countries[s.nat]?.name ?: s.nat} · reputation ${s.rep.roundToInt()}" + (if (s.real) " · real person" else ""), 12)
                    if (s.playerId >= 0) w.players.getOrNull(s.playerId)?.let { p -> Text("Former player: ${p.handle} ›", color = Ui.accent, fontSize = 12.sp, modifier = Modifier.clickable { vm.go(Screen.Player(p.id)) }) }
                }
            }
            if (canManage(w)) FlowRow(Modifier.padding(horizontal = 12.dp)) {
                if (own) {
                    Pill("Extend contract") { renew = true }
                    Pill("Change role") { roleDlg = true }
                    Pill("Release", Ui.loss.copy(alpha = 0.25f)) { confirmRelease = true }
                } else if (!s.retired) Pill(if (s.teamId >= 0) "Approach (compensation)" else "Offer a job", Ui.accent, androidx.compose.ui.graphics.Color.White) { offer = true }
            }
        }
        item {
            Tiles(listOf(
                Tile("Ability as ${Staffing.label(s).lowercase()}", "%.0f".format(s.quality), Rate.band(s.quality), Rate.color(s.quality)),
                Tile("Reputation", "%.0f".format(s.rep)),
                if (s.teamId >= 0) Tile("Salary", formatMoney(s.salary), "until end of ${s.contractEnd}") else Tile("Asks about", formatMoney(Staffing.demand(w, s, w.orgs.getOrNull(w.userOrg)?.region ?: "NA")), "per season"),
                Tile("Coaching legacy", "%.1f".format(lg.total), "${hs.count { it.trophy }} titles", Ui.gold),
            ))
        }
        item {
            Card("Attributes") {
                val key = Staffing.def(s.role).weights.map { it.first }.toSet()
                Muted("Highlighted: what matters in the current role. ${Staffing.def(s.role).desc}", 11)
                Gap(4)
                Staffing.ATTRS.forEachIndexed { i, name ->
                    val v = Staffing.attr(s, i)
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = if (i in key) Ui.text else Ui.muted, fontSize = 12.sp, fontWeight = if (i in key) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.width(100.dp))
                        Bar(v, modifier = Modifier.weight(1f), color = if (i in key) Rate.color(v) else Ui.muted)
                        Text("%.0f".format(v), color = Rate.color(v), fontSize = 12.sp, modifier = Modifier.width(30.dp).padding(start = 6.dp))
                    }
                }
                Gap(4)
                Muted(Staffing.ATTRS.indices.filter { it in key }.joinToString(" · ") { "${Staffing.ATTRS[it]}: ${Staffing.ATTR_DESC[it].lowercase()}" }, 10)
            }
        }
        item {
            Card("Best roles") {
                val fits = Staffing.ROLES.filter { !it.titleBound || true }.map { it to Staffing.qualityOf(it.id, s.attrs.takeIf { a -> a.size == Staffing.ATTRS.size } ?: IntArray(Staffing.ATTRS.size) { s.quality.toInt() }) }.sortedByDescending { it.second }.take(5)
                for ((d, q) in fits) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Staffing.roleLabel(d.id, s.titleId), color = if (d.id == s.role) Ui.accent else Ui.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Rating(q)
                }
            }
        }
        item {
            Card("Honours") {
                if (hs.isEmpty()) Muted("No titles yet.")
                for (h in hs.take(40)) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("${h.season}", color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(44.dp))
                    Text(h.label, color = if (h.trophy) Ui.gold else Ui.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Muted(h.team.ifEmpty { w.teams.getOrNull(h.teamId)?.short ?: "" }, 11)
                }
                Gap(4)
                KV("Legacy", "%.1f (titles %.1f · real career %.1f · years %.1f · peak %.1f)".format(lg.total, lg.achievements, lg.pastPts, lg.longevity, lg.peak))
                if (s.past.isNotEmpty()) Muted("Titles before the save: " + if (s.realSrc == "compiled") "compiled from public records." else "Leaguepedia tournament rosters.", 10)
            }
        }
        item {
            Card("Career") {
                if (s.spells.isEmpty()) Muted(if (s.past.isNotEmpty()) "No club in the save yet." else "No clubs yet.")
                for (sp in s.spells.reversed()) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        TeamChip(w, sp.teamId, vm, 16.dp)
                        Muted("${Staffing.roleLabel(sp.role, s.titleId, if (sp.role == "position-coach") s.spec else "")} · ${Dates.short(sp.from)} – ${if (sp.to < 0) "now" else Dates.short(sp.to)}" + (if (sp.why.isNotEmpty()) " · ${sp.why}" else ""), 11)
                    }
                }
            }
            Gap(16)
        }
    }
    if (offer) OfferDialog(vm, w, s) { offer = false }
    if (renew && team != null) RenewDialog(vm, w, s, team) { renew = false }
    if (roleDlg && team != null) RoleDialog(vm, w, s, team) { roleDlg = false }
    if (confirmRelease && team != null) AlertDialog(onDismissRequest = { confirmRelease = false },
        confirmButton = { TextButton(onClick = { Staffing.release(w, s, "released"); confirmRelease = false; vm.toast = "${s.name()} released"; vm.edit() }) { Text("Release", color = Ui.loss) } },
        dismissButton = { TextButton(onClick = { confirmRelease = false }) { Text("Keep") } },
        title = { Text("Release ${s.name()}?") },
        text = { Text("Severance: ${formatMoney(s.salary * ((s.contractEnd - w.season).coerceAtLeast(0) + 0.5) * 0.5)} (half of the remaining contract).", color = Ui.text) })
}

@Composable
private fun MoneyField(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember { mutableStateOf(value.roundToInt().toString()) }
    OutlinedTextField(text, { t -> text = t.filter { it.isDigit() }; text.toDoubleOrNull()?.let(onChange) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun OfferDialog(vm: GameVm, w: World, s: Staff, close: () -> Unit) {
    var role by remember { mutableStateOf(s.role) }
    val teams = hirers(w, s, role)
    var ti by remember { mutableIntStateOf(0) }
    val team = teams.getOrNull(ti.coerceIn(0, (teams.size - 1).coerceAtLeast(0)))
    val t = team?.let { Catalogue.get(it.titleId) }
    var spec by remember { mutableStateOf(s.spec.ifEmpty { t?.lineup?.firstOrNull() ?: "" }) }
    val ask = team?.let { Staffing.demand(w, s, it.region, role) * Staffing.wageFactor(w, it) } ?: 0.0
    var wage by remember(role, ti) { mutableStateOf(ask) }
    var years by remember { mutableIntStateOf(2) }
    val fee = Staffing.compensation(w, s)
    var feeOffer by remember { mutableStateOf(fee) }
    val current = team?.let { tm -> if (role == "head-coach") Staffing.members(w, tm).firstOrNull { it.role == "head-coach" && it.id != s.id } else null }
    var replace by remember { mutableStateOf(false) }
    val roles = Staffing.ROLES.filter { !it.titleBound || s.titleId in (w.orgs.getOrNull(w.userOrg)?.teams?.map { tid -> w.teams[tid].titleId } ?: emptyList()) }
    AlertDialog(onDismissRequest = close, title = { Text(if (s.teamId >= 0) "Approach ${s.name()}" else "Offer ${s.name()} a job") },
        confirmButton = {
            TextButton(enabled = team != null, onClick = {
                val r = Staffing.offer(w, s, team!!, role, wage, years, spec, feeOffer, replace)
                vm.toast = r.text
                if (r.ok) { close(); vm.edit() }
            }) { Text("Send offer") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (teams.isEmpty()) { Muted("None of your teams plays ${Catalogue.get(s.titleId).short}: coaching roles are for one game."); return@Column }
                if (teams.size > 1) Select("Team", teams.map { it.name }, ti) { ti = it }
                Select("Role", roles.map { Staffing.roleLabel(it.id, s.titleId) }, roles.indexOfFirst { it.id == role }.coerceAtLeast(0)) { role = roles[it].id }
                if (role == "position-coach" && t != null) Select("Position", t.lineupRoles.map { it.name }, t.lineup.indexOf(spec).coerceAtLeast(0)) { spec = t.lineup[it] }
                Muted("In this role: ability %.0f. Asks about %s per season.".format(if (s.attrs.size == Staffing.ATTRS.size) Staffing.qualityOf(role, s.attrs) else s.quality, formatMoney(ask)), 12)
                MoneyField("Salary per season", wage) { wage = it }
                Select("Years", listOf("1", "2", "3"), years - 1) { years = it + 1 }
                if (s.teamId >= 0) {
                    Gap(4)
                    Muted("${w.teams[s.teamId].name} want about ${formatMoney(fee)} compensation (remaining contract and reputation).", 12)
                    MoneyField("Compensation offered", feeOffer) { feeOffer = it }
                }
                if (current != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { replace = !replace }) {
                    androidx.compose.material3.Checkbox(replace, { replace = it })
                    Text("Replace ${current.name()} (released, severance paid)", color = Ui.text, fontSize = 12.sp)
                }
                Staffing.refusal(w, s, team ?: return@Column, role)?.let { Text(it, color = Ui.loss, fontSize = 12.sp) }
            }
        })
}

@Composable
private fun RenewDialog(vm: GameVm, w: World, s: Staff, team: Team, close: () -> Unit) {
    val ask = Staffing.demand(w, s, team.region) * Staffing.wageFactor(w, team)
    var wage by remember { mutableStateOf(maxOf(ask, s.salary)) }
    var years by remember { mutableIntStateOf(2) }
    AlertDialog(onDismissRequest = close, title = { Text("Extend ${s.name()}") },
        confirmButton = { TextButton(onClick = { val r = Staffing.renew(w, s, wage, years); vm.toast = r.text; if (r.ok) { close(); vm.edit() } }) { Text("Offer") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        text = {
            Column {
                Muted("Current: ${formatMoney(s.salary)} until ${s.contractEnd}. Asks about ${formatMoney(ask)}.", 12)
                MoneyField("Salary per season", wage) { wage = it }
                Select("Years", listOf("1", "2", "3"), years - 1) { years = it + 1 }
            }
        })
}

@Composable
private fun RoleDialog(vm: GameVm, w: World, s: Staff, team: Team, close: () -> Unit) {
    val t = Catalogue.get(team.titleId)
    AlertDialog(onDismissRequest = close, confirmButton = { TextButton(onClick = close) { Text("Close") } }, title = { Text("New role for ${s.name()}") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            for (d in Staffing.ROLES) {
                val specs = if (d.id == "position-coach") t.lineup else listOf("")
                for (sp in specs) Row(Modifier.fillMaxWidth().clickable {
                    val r = Staffing.changeRole(w, s, d.id, sp); vm.toast = r.text; if (r.ok) { close(); vm.edit() }
                }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Staffing.roleLabel(d.id, team.titleId, sp), color = if (d.id == s.role && sp == s.spec) Ui.accent else Ui.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Rating(if (s.attrs.size == Staffing.ATTRS.size) Staffing.qualityOf(d.id, s.attrs) else s.quality)
                }
            }
        }
    })
}

/** A team's staff by group (organisation roles of the whole organisation shown on every team). */
@Composable
fun StaffList(vm: GameVm, w: World, team: Team, manage: Boolean) {
    val mine = Staffing.members(w, team)
    val orgRoles = w.orgs[team.orgId].teams.filter { it != team.id }.flatMap { Staffing.members(w, w.teams[it]) }.filter { Staffing.def(it.role).org }
    val all = (mine + orgRoles).distinctBy { it.id }
    if (all.isEmpty()) Muted("No staff (coaching level ${team.coach.toInt()}).")
    for ((group, list) in all.groupBy { Staffing.def(it.role).group }.toList().sortedBy { (g, _) -> listOf("Coaching", "Performance", "Recruitment", "Operations", "Organisation").indexOf(g) }) {
        Text(group, color = Ui.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
        for (s in list.sortedBy { Staffing.ROLES.indexOfFirst { d -> d.id == it.role } }) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Staff(s.id)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(flag(s.nat).ifEmpty { "·" }, fontSize = 14.sp, modifier = Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name(), color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted("${Staffing.label(s)} · age ${Staffing.age(w, s)} · until ${s.contractEnd}" + (if (s.teamId != team.id) " · ${w.teams[s.teamId].short}" else "") + (if (s.real) " · real" else ""), 11)
            }
            Column(horizontalAlignment = Alignment.End) { Rating(s.quality); Muted(formatMoney(s.salary), 10) }
            if (manage && s.teamId == team.id) TextButton(onClick = { Staffing.release(w, s, "released"); vm.toast = "${s.name()} released"; vm.edit() }) { Text("Release", color = Ui.loss, fontSize = 12.sp) }
        }
    }
}

/** What the staff add, in numbers (squad and team pages). */
@Composable
fun StaffEffects(w: World, team: Team) {
    for ((k, v) in Staffing.summary(w, team)) KV(k, v)
    KV("Staff payroll", formatMoney(Staffing.members(w, team).sumOf { it.salary }))
}

/** Squad → Staff: approaches from other clubs, the staff, what they add, and hiring by role. */
@Composable
fun SquadStaffPanel(vm: GameVm, w: World, team: Team) {
    var hireRole by remember { mutableStateOf<String?>(null) }
    val bids = w.staffBids.filter { it.status == "pending" && it.toTeam == team.id }
    if (bids.isNotEmpty()) Card("Approaches for your staff") {
        for (b in bids) {
            val s = w.staff[b.staffId]
            Text("${w.teams[b.fromTeam].name} want ${s.name()} as ${Staffing.roleLabel(b.role, team.titleId).lowercase()} · compensation ${formatMoney(b.fee)}", color = Ui.text, fontSize = 13.sp)
            Row {
                TextButton(onClick = { vm.toast = Staffing.answerBid(w, b.id, true).text; vm.edit() }) { Text("Accept", color = Ui.win) }
                TextButton(onClick = { vm.toast = Staffing.answerBid(w, b.id, false).text; vm.edit() }) { Text("Reject", color = Ui.loss) }
            }
        }
    }
    Card("Staff · ${team.name}") { StaffList(vm, w, team, manage = canManage(w)) }
    Card("What your staff add") { StaffEffects(w, team) }
    Card("Hire") {
        Muted("Open roles in a club this size are filled by AI clubs from the same market. Tap a role to see who is available (free agents and staff you could buy out).", 11)
        val have = Staffing.members(w, team).groupingBy { it.role }.eachCount()
        for (d in Staffing.ROLES) Row(Modifier.fillMaxWidth().clickable { if (canManage(w)) hireRole = d.id else vm.toast = Roles.denyReason(w, "staff") }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Staffing.roleLabel(d.id, team.titleId), color = Ui.text, fontSize = 13.sp)
                Muted(d.desc, 10)
            }
            Muted("${have[d.id] ?: 0} ›", 12)
        }
    }
    hireRole?.let { role ->
        val pool = remember(role, vm.version) {
            w.staff.asSequence().filter { !it.retired && it.role == role && it.teamId != team.id && (!Staffing.def(role).titleBound || it.titleId == team.titleId) && (it.teamId < 0 || !w.isUserTeam(it.teamId)) }
                .sortedWith(compareBy<Staff> { it.teamId >= 0 }.thenByDescending { it.quality }).take(40).toList()
        }
        AlertDialog(onDismissRequest = { hireRole = null }, confirmButton = { TextButton(onClick = { hireRole = null }) { Text("Close") } }, title = { Text(Staffing.roleLabel(role, team.titleId)) }, text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (pool.isEmpty()) Muted("Nobody available.")
                for (s in pool) Row(Modifier.fillMaxWidth().clickable { hireRole = null; vm.go(Screen.Staff(s.id)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name() + if (s.real) " ·" else "", color = Ui.text)
                        Muted("${s.teamId.takeIf { it >= 0 }?.let { "at ${w.teams[it].short}" } ?: "free"} · age ${Staffing.age(w, s)} · rep ${s.rep.roundToInt()} · asks ${formatMoney(Staffing.demand(w, s, team.region, role))}", 11)
                    }
                    Rating(s.quality)
                }
            }
        })
    }
}

private val STAFF_STATUS = listOf("Available", "All", "Under contract", "Real people")

/** Market → Staff: everyone in the staff market for a game (organisation and support roles from every game). */
fun LazyListScope.staffMarket(vm: GameVm, w: World, title: String, state: TableState, roleIdx: Int, status: Int, q: String, setRole: (Int) -> Unit, setStatus: (Int) -> Unit, setQ: (String) -> Unit) {
    val roles = listOf<String?>(null) + Staffing.ROLES.map { it.id }
    item {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Select("Role", roles.map { r -> r?.let { Staffing.roleLabel(it, title) } ?: "All roles" }, roleIdx) { setRole(it) }
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                Select("Status", STAFF_STATUS, status) { setStatus(it) }
            }
            OutlinedTextField(q, { setQ(it) }, label = { Text("Search name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
    val role = roles.getOrNull(roleIdx)
    val list = w.staff.asSequence().filter { s ->
        !s.retired && (s.titleId == title || !Staffing.def(s.role).titleBound) && (role == null || s.role == role) &&
            (q.isBlank() || s.name().contains(q, true) || s.fullName().contains(q, true)) &&
            when (status) { 0 -> s.teamId < 0; 2 -> s.teamId >= 0; 3 -> s.real; else -> true }
    }.sortedByDescending { it.quality }.take(400).toList()
    item { Text("Staff · ${list.size}", color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp)) }
    lazySortTable(state, list, listOf(
        SCol<Staff>("Name", 130.dp, false) { s -> Row(verticalAlignment = Alignment.CenterVertically) { flag(s.nat).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp); androidx.compose.foundation.layout.Spacer(Modifier.width(3.dp)) }; Text(s.name(), color = Ui.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        SCol("Role", 110.dp, false) { s -> Muted(Staffing.label(s), 11) },
        SCol("Age", 34.dp, key = { Staffing.age(w, it).toDouble() }) { s -> Num("${Staffing.age(w, s)}") },
        SCol("Club", 56.dp, false) { s -> Muted(s.teamId.takeIf { it >= 0 }?.let { w.teams[it].short } ?: "free", 11) },
        SCol("Ability", 46.dp, key = { it.quality }) { s -> Rating(s.quality) },
        SCol("Rep", 38.dp, key = { it.rep }) { s -> Num("${s.rep.roundToInt()}") },
        SCol("Titles", 40.dp, key = { s -> s.past.count { it.split('|').getOrNull(5) == "1" }.toDouble() }) { s -> Num("${s.past.count { it.split('|').getOrNull(5) == "1" }}", Ui.gold) },
        SCol("Salary / asks", 74.dp, key = { if (it.teamId >= 0) it.salary else Staffing.demand(w, it, "NA") }) { s -> Num(formatMoney(if (s.teamId >= 0) s.salary else Staffing.demand(w, s, w.orgs.getOrNull(w.userOrg)?.region ?: "NA")), Ui.muted) },
        SCol("Until", 42.dp, key = { it.contractEnd.toDouble() }) { s -> Num(if (s.teamId >= 0) "${s.contractEnd}" else "—", Ui.muted) },
    ), key = { it.id }, onRow = { vm.go(Screen.Staff(it.id)) })
}

/** Coaches ranked by legacy (GOAT) or inducted (Hall of Fame: retired, or a real career with no club in the save). */
@Composable
fun CoachLegacy(vm: GameVm, w: World, title: String?, hof: Boolean) {
    val rows = remember(title, vm.version, hof) {
        val idx = Staffing.titles(w)
        Staffing.coaches(w, title).map { s -> val hs = Staffing.honours(w, s, idx); Triple(s, Staffing.legacy(w, s, hs), hs.count { it.trophy }) }
            .filter { (s, sc, _) -> !hof || (sc.total >= COACH_HOF && (s.retired || (s.teamId < 0 && s.spells.isEmpty() && s.past.isNotEmpty()))) }
            .sortedByDescending { it.second.total }.take(200)
    }
    Card(if (hof) "Coaching Hall of Fame (legacy ≥ ${COACH_HOF.toInt()})" else (title?.let { "${Catalogue.get(it).name}: greatest coaches" } ?: "Greatest coaches of all time")) {
        Muted("Titles as head coach count in full, as an assistant, draft or positional coach 40%. Real careers before the save count (League of Legends tournament rosters since 2011; compiled CS Majors, The International and VCT titles).", 11)
        Gap(4)
        if (rows.isEmpty()) Muted(if (hof) "Nobody yet: coaches enter when they retire with a legacy of ${COACH_HOF.toInt()}+." else "No coaches yet.")
        else SortTable(rows, listOf(
            SCol<Triple<Staff, Legacy.Score, Int>>("Coach", 120.dp, false) { (s, _, _) -> Row(verticalAlignment = Alignment.CenterVertically) { flag(s.nat).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp); androidx.compose.foundation.layout.Spacer(Modifier.width(3.dp)) }; StaffChip(s, vm) } },
            SCol("Title", 52.dp, false) { (s, _, _) -> TitleTag(s.titleId) },
            SCol("Titles", 46.dp, key = { it.third.toDouble() }) { Num("${it.third}", Ui.gold) },
            SCol("Achieve.", 58.dp, key = { it.second.achievements }) { Num("%.1f".format(it.second.achievements)) },
            SCol("Real past", 58.dp, key = { it.second.pastPts }) { Num(if (it.second.pastPts > 0) "%.1f".format(it.second.pastPts) else "—", Ui.muted) },
            SCol("Longev.", 52.dp, key = { it.second.longevity }) { Num("%.1f".format(it.second.longevity)) },
            SCol("Legacy", 52.dp, key = { it.second.total }) { Num("%.1f".format(it.second.total), Ui.gold, bold = true) },
            SCol("Now", 110.dp, false) { (s, _, _) -> Muted(if (s.retired) "retired ${s.retiredSeason}" else s.teamId.takeIf { it >= 0 }?.let { "${Staffing.label(s)}, ${w.teams[it].short}" } ?: "free agent", 11) },
        ), initialSort = 6, onRow = { vm.go(Screen.Staff(it.first.id)) })
    }
}

const val COACH_HOF = 45.0

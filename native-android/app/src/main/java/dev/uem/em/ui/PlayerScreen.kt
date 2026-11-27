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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
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
import dev.uem.em.mgmt.Market
import dev.uem.em.model.AttrGroup
import dev.uem.em.model.Attrs
import dev.uem.em.model.Player
import dev.uem.em.model.World
import dev.uem.em.sim.Patch
import dev.uem.em.sim.Ratings
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.TitleDef

private val PLAYER_TABS = listOf("Overview", "Attributes", "Proficiencies", "Career", "Statistics", "Contract", "Development", "History", "Awards")
private val INPUT_LABEL = listOf("Touch", "Mouse & keyboard", "Controller")

internal fun inputOf(t: TitleDef) = if (t.inputMkb) 1 else 0

private fun personality(p: Player, knowledge: Int): String {
    if (knowledge < 40) return "Unknown (scout to reveal)"
    val x = p.pers
    val traits = ArrayList<String>()
    if (x[0] > 70) traits.add("ambitious")
    if (x[1] > 70) traits.add("loyal")
    if (x[2] < 30) traits.add("volatile")
    if (x[3] > 75) traits.add("big ego")
    if (x[4] > 75) traits.add("money-motivated")
    if (x[6] > 72) traits.add("big-game player")
    if (x[6] < 28) traits.add("nervous under pressure")
    if (x[5] > 65) traits.add("controversial")
    return if (traits.isEmpty()) "balanced" else traits.joinToString(", ")
}

/** Real name in the player's naming order. */
internal fun realName(p: Player) = if (p.first.isBlank() && p.last.isBlank()) "" else if (GameData.isEastern(p.nat)) "${p.last} ${p.first}".trim() else "${p.first} ${p.last}".trim()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(vm: GameVm, w: World, id: Int) {
    val p = w.players[id]
    val t = Catalogue.get(p.titleId)
    var tab by remember(id) { mutableIntStateOf(UiDefaults.tab) }
    val honours = remember(id, vm.version, w.season) { Legacy.honours(w, p) }
    val lg = Legacy.of(w, p, honours)
    val know = if (w.userOrg >= 0) Extras.knowledge(w, p) else 100
    val own = w.isUserTeam(p.teamId)
    val seasonLines = p.stats.filter { it.season == w.season }
    val sgp = seasonLines.sumOf { it.gp }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(if (own) Ui.heroGoldSoft else Ui.heroSoft).padding(16.dp), verticalAlignment = Alignment.Top) {
                PlayerPic(p, 76.dp, round = true)
                HGap(14)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val f = p.nats().joinToString("") { flag(it) }
                        if (f.isNotEmpty()) Text("$f ", fontSize = 18.sp)
                        Text(p.handle, color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    realName(p).takeIf { it.isNotEmpty() }?.let { Muted(it, 13) }
                    FlowRow(verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                        TitleTag(t.id)
                        Text("${t.role(p.roles[0]).name}${p.roles.getOrNull(1)?.let { " / " + t.role(it).name } ?: ""}", color = Ui.text, fontSize = 12.sp)
                    }
                    Gap(2)
                    if (p.teamId >= 0) TeamChip(w, p.teamId, vm, 18.dp) else Muted(if (p.retired) "Retired ${p.retiredSeason}" else "Free agent")
                    // A retired player who moved into staff work.
                    w.staff.firstOrNull { it.playerId == p.id }?.let { s -> Text("Now: ${dev.uem.em.mgmt.Staffing.label(s).lowercase()}${w.teams.getOrNull(s.teamId)?.let { " at ${it.name}" } ?: " (free agent)"} ›", color = Ui.accent, fontSize = 12.sp, modifier = Modifier.clickable { vm.go(Screen.Staff(s.id)) }) }
                    Muted("Age ${Dates.ageAt(p.birth, w.day)} (born ${Dates.format(p.birth)}) · ${p.nats().joinToString(" / ") { GameData.countries[it]?.name ?: it }}", 12)
                    if (p.nat2.isEmpty() && p.resYears in 1..2 && p.resCountry.isNotEmpty() && p.resCountry != p.nat) Muted("${p.resYears} of 3 seasons in ${GameData.countries[p.resCountry]?.name ?: p.resCountry} toward a second nationality", 11)
                    val notes = buildList {
                        if (p.injuryUntil > w.day) add("Injured (${p.injury}) until ${Dates.format(p.injuryUntil)}")
                        if (p.loanFrom >= 0) add("On loan from ${w.teams[p.loanFrom].name} until the end of ${p.loanUntil}")
                        if (p.preContractTeam >= 0) add("Pre-contract: joins ${w.teams[p.preContractTeam].name} next season")
                        if (p.listed) add("Transfer listed")
                        if (p.unhappy > 60) add("Unhappy at the club")
                    }
                    for (n in notes) Text(n, color = Ui.gold, fontSize = 12.sp)
                }
            }
            var editing by remember { mutableStateOf(false) }
            FlowRow(Modifier.padding(horizontal = 12.dp)) {
                if (w.userOrg >= 0 && !own) Pill("Scout") { Extras.scout(w, p.id); vm.toast = "${p.handle} added to scouting focus."; vm.edit() }
                Pill(if (p.id in w.shortlist) "★ Shortlisted" else "☆ Shortlist", if (p.id in w.shortlist) Ui.gold else Ui.card2, if (p.id in w.shortlist) Color.Black else Ui.text) {
                    if (p.id in w.shortlist) w.shortlist.remove(p.id) else w.shortlist.add(p.id); vm.edit()
                }
                Pill("Edit") { editing = true }
                if (p.teamId >= 0) Pill("Team page") { vm.go(Screen.Team(p.teamId)) }
            }
            if (editing) EditPlayerDialog(vm, w, p) { editing = false }
            Row(Modifier.padding(horizontal = 12.dp)) { Column(Modifier.weight(1f)) { ScoutLine(vm, w, p) } }
        }
        item {
            Tiles(listOf(
                Tile("Current ability", "%.1f".format(p.ca), Rate.band(p.ca), Rate.color(p.ca)),
                Tile("Potential (scouted)", if (w.userOrg >= 0) Extras.potentialText(w, p) else "%.0f".format(p.pa), "knowledge $know%"),
                Tile("Market value", formatMoney(Economy.marketValue(w, p))),
                Tile("Salary", if (p.teamId >= 0) formatMoney(p.salary) else "Free agent", if (p.teamId >= 0) "until end of ${p.contractEnd}" else null),
                Tile("Form / Morale", "%+.1f / %.0f".format(p.form, p.morale), "fatigue %.0f".format(p.fatigue)),
                Tile("${w.season} season", if (sgp > 0) "%.2f".format(seasonLines.sumOf { it.ratingSum } / sgp) else "—", "$sgp games, ${seasonLines.sumOf { it.mvps }} MVPs"),
                Tile("Legacy", "%.0f".format(lg.total), "${honours.size} honours"),
                Tile("Career", "${p.stats.sumOf { it.gp }} games", "${p.stats.map { it.season }.distinct().size} seasons · ${p.stats.sumOf { it.mvps }} MVPs"),
                Tile("Trophies", "${honours.count { it.trophy }}", honours.firstOrNull { it.trophy }?.let { "last: ${it.season}" } ?: "none yet"),
            ))
        }
        item { Tabs(PLAYER_TABS, tab) { tab = it } }
        item {
            when (tab) {
                0 -> {
                    if (p.visaUntil > w.day) Card { Text("Waiting for a work visa: can play from ${dev.uem.em.core.Dates.format(p.visaUntil)}", color = Ui.gold, fontSize = 12.sp) }
                    PlayerOverview(vm, w, p, t, know); AmbitionsCard(w, p, know); PlayerDevelopmentCard(vm, w, p); SoloQueueCard(vm, w, p); CircuitEvidenceCard(w, p)
                }
                1 -> PlayerAttributes(p, t)
                2 -> PlayerProficiency(w, p, t)
                3 -> PlayerCareer(vm, w, p)
                4 -> PlayerStatistics(vm, w, p)
                5 -> PlayerContract(vm, w, p, own)
                6 -> PlayerDevelopment(w, p, know)
                7 -> PlayerHistory(vm, w, p)
                else -> PlayerAwards(vm, w, p, honours, lg)
            }
            Gap(16)
        }
    }
}

@Composable
private fun PlayerOverview(vm: GameVm, w: World, p: Player, t: TitleDef, know: Int) {
    Card("Profile") {
        KV("Position", t.role(p.roles[0]).name + (p.roles.getOrNull(1)?.let { " (also ${t.role(it).name})" } ?: ""))
        KV("Preferred input", INPUT_LABEL[p.input.indices.maxByOrNull { p.input[it] } ?: 0])
        KV("Fame") { Rating(p.rep) }
        KV("Personality", personality(p, know))
        KV("Development profile", if (know >= 70) "${p.dev.trajectory} developer" else "Scout further to assess")
        KV("Morale") { Rating(p.morale) }
        KV("Form", "%+.1f".format(p.form), if (p.form >= 0) Ui.win else Ui.loss)
        KV("Fatigue", "%.0f".format(p.fatigue), if (p.fatigue > 60) Ui.loss else Ui.text)
        KV("Unhappiness", "%.0f".format(p.unhappy), if (p.unhappy > 50) Ui.loss else Ui.text)
        KV("Transfer listed", if (p.listed) "Yes" else "No")
        if (p.teamId >= 0 && p.joinedDay > 0) KV("At the club since", Dates.format(p.joinedDay))
    }
    ProvenanceCard(w, p)
    PlayerActions(vm, w, p)
    Card("Derived summaries") {
        for ((label, v) in Attrs.summaries(p.attrs).filter { (k, _) -> if (t.isMoba) k != "Shooter" else k != "MOBA" }) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Text(label, color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(92.dp))
                Bar(v, color = Rate.color(v), modifier = Modifier.weight(1f))
                Column(Modifier.width(40.dp), horizontalAlignment = Alignment.End) { Rating(v) }
            }
        }
    }
    val comps = w.comps.filter { it.season == w.season && (it.pstats[p.id]?.gp ?: 0) > 0 }.sortedBy { it.startDay }
    if (comps.isNotEmpty()) Card("This season") {
        val k = t.statIndex["k"] ?: 0
        val d = t.statIndex["d"] ?: 1
        val a = t.statIndex["a"] ?: 2
        Table(listOf(Col("Competition", 140.dp, false), Col("GP", 34.dp), Col("W-L", 48.dp), Col("Rating", 50.dp), Col("KDA", 44.dp), Col("MVP", 36.dp)), comps.size, onRow = { vm.go(Screen.Comp(comps[it].id)) }) { r, c ->
            val s = comps[r].pstats[p.id]!!
            when (c) {
                0 -> Text(comps[r].short, color = Ui.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                1 -> Num("${s.gp}")
                2 -> Num("${s.w}-${s.gp - s.w}")
                3 -> Num("%.2f".format(s.r / maxOf(1, s.gp)), bold = true)
                4 -> Num("%.2f".format((s.v[k] + s.v[a]) / maxOf(1.0, s.v[d])))
                else -> Num("${s.m}")
            }
        }
    }
}

@Composable
private fun PlayerAttributes(p: Player, t: TitleDef) {
    var adjusted by remember { mutableStateOf(false) }
    val ip = Ratings.inputProf(p, t)
    fun rel(i: Int): Double {
        val a = Attrs.ALL[i]
        if (a.group != AttrGroup.MOBA && a.group != AttrGroup.Shooter) return 1.0
        return t.specRelevance[a.key] ?: if (t.isMoba == (a.group == AttrGroup.MOBA)) 1.0 else 0.0
    }
    Column(Modifier.padding(horizontal = 12.dp)) {
        Text((if (adjusted) "☑ " else "☐ ") + "Show input-adjusted values for ${t.short} (${INPUT_LABEL[inputOf(t)]} proficiency ${ip.toInt()})", color = Ui.text, fontSize = 13.sp, modifier = Modifier.clickable { adjusted = !adjusted }.padding(vertical = 6.dp))
        Muted("Faded specialist attributes have little or no relevance in ${t.short}.", 11)
    }
    Tiles(Attrs.summaries(p.attrs).map { (k, v) -> Tile(k, "%.0f".format(v), Rate.band(v), Rate.color(v)) })
    val groups = AttrGroup.entries.toList()
    for (pair in groups.chunked(2)) Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        for (g in pair) Column(Modifier.weight(1f).padding(4.dp).background(Ui.card, RoundedCornerShape(10.dp)).padding(8.dp)) {
            Text(g.name, color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Gap(4)
            for (i in Attrs.group(g)) {
                val v = if (adjusted) Attrs.effective(p.attrs, i, ip) else p.attrs[i]
                Row(Modifier.fillMaxWidth().alpha(if (rel(i) < 0.3) 0.4f else 1f).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Attrs.ALL[i].name, color = Ui.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Rating(v, size = 12)
                }
            }
        }
        if (pair.size == 1) Column(Modifier.weight(1f)) {}
    }
}

@Composable
private fun PlayerProficiency(w: World, p: Player, t: TitleDef) {
    Card("Input proficiencies (separate from attributes)") {
        for (i in 0..2) KV(INPUT_LABEL[i] + if (inputOf(t) == i) "  · required by ${t.short}" else "") { Rating(p.input[i]) }
        Gap(4)
        Muted("Effective mechanics = M × (0.65 + 0.35 × P/100). Mental attributes are not penalised.", 11)
    }
    Card("Title familiarity") {
        if (p.profTitle.isEmpty()) Muted("—")
        for ((tid, v) in p.profTitle.entries.sortedByDescending { it.value }) KV(Catalogue.get(tid).name) { Rating(v) }
    }
    Card("Role proficiency & effectiveness (${t.short})") {
        Table(listOf(Col("Role", 110.dp, false), Col("Class", 120.dp, false), Col("Prof.", 46.dp), Col("E", 46.dp)), t.roles.size, highlight = { t.roles[it].id == p.roles[0] }) { r, c ->
            val role = t.roles[r]
            when (c) {
                0 -> Text(role.name, color = Ui.text, fontSize = 13.sp, maxLines = 1)
                1 -> Text(role.fclass.joinToString(", "), color = Ui.muted, fontSize = 11.sp, maxLines = 2)
                2 -> Rating(p.profRole["${t.id}:${role.id}"] ?: 0.0)
                else -> Rating(Ratings.effectiveness(p, t, role), 1)
            }
        }
        Muted("E = 0.4 core + 0.3 specialist + 0.15 title + 0.1 role + 0.05 ${t.charLabel.lowercase()} / weapon proficiency.", 11)
    }
    if (t.characters.isNotEmpty()) {
        val patch = Patch.of(w, t)
        val chars = p.profChar.entries.sortedByDescending { it.value }.take(20)
        Card("${t.charLabel} proficiency") {
            if (chars.isEmpty()) Muted("No comfort picks yet.")
            for ((cid, v) in chars) {
                val c = t.charIndex[cid]
                val meta = patch.charMeta[cid] ?: 0.0
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    CharPic(cid, c?.name ?: cid, 28.dp, round = true)
                    HGap(8)
                    Column(Modifier.weight(1f)) {
                        Text(c?.name ?: cid, color = Ui.text, fontSize = 13.sp)
                        Muted(c?.cls ?: "", 10)
                    }
                    Text("meta %+.1f".format(meta), color = if (meta >= 0) Ui.win else Ui.loss, fontSize = 11.sp, modifier = Modifier.width(70.dp))
                    Column(Modifier.width(32.dp), horizontalAlignment = Alignment.End) { Rating(v) }
                }
            }
        }
    }
    if (p.profWeapon.isNotEmpty()) Card("Weapons & equipment") {
        for ((k, v) in p.profWeapon.entries.sortedByDescending { it.value }) KV(k) { Rating(v) }
    }
    if (p.profMap.isNotEmpty()) Card("Maps & modes") {
        for ((k, v) in p.profMap.entries.sortedByDescending { it.value }) KV(t.mapName(k)) { Rating(v) }
    }
    Card("Cross-title projection (if switched today)") {
        val rows = w.titles.filter { it != t.id }.map { x ->
            val tx = Catalogue.get(x)
            val sim = Legacy.similarity(t.id, x)
            val famT = p.profTitle[x] ?: ((p.profTitle[t.id] ?: 0.0) * sim)
            val roleProf = (p.profRole["${t.id}:${p.roles[0]}"] ?: 0.0) * sim
            val charP = Ratings.charProf(p, t, t.role(p.roles[0])) * sim * 0.8
            val best = tx.roles.maxByOrNull { r -> Ratings.base(p, tx, r).let { b -> Ratings.WC * b.c + Ratings.WS * b.s } }!!
            val b = Ratings.base(p, tx, best)
            Triple(tx, sim, Ratings.WC * b.c + Ratings.WS * b.s + Ratings.WT * famT + Ratings.WR * roleProf + Ratings.WP * charP) to best.name
        }.sortedByDescending { it.first.third }
        for ((r, role) in rows) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TitleTag(r.first.id)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(role, color = Ui.text, fontSize = 12.sp)
                Muted("${(r.second * 100).toInt()}% similar · ${Legacy.similarityLabel(r.second)}", 10)
            }
            Muted("E ", 11); Rating(r.third, 1)
        }
        Gap(4)
        Muted("Projection keeps all permanent attributes; title, role and ${t.charLabel.lowercase()} familiarity transfer by title similarity and must be rebuilt through training and play.", 11)
    }
}

@Composable
private fun PlayerCareer(vm: GameVm, w: World, p: Player) {
    // Before the save: the real career (team spells with months and positions where the wikis record them).
    val before = remember(p.id) { if (p.real) dev.uem.em.mgmt.Careers.of(p.titleId, p.handle) else emptyList() }
    if (before.isNotEmpty()) Card("Career before the save") {
        CareerSpells(vm, w, p.titleId, before)
    }
    // A long career: one season at a time (or all of it).
    val seasons = listOf(0) + p.career.map { it.season }.distinct().sortedDescending()
    var si by remember(p.id) { mutableIntStateOf(0) }
    Card(if (before.isNotEmpty()) "Moves in the save" else "Career timeline", right = { if (seasons.size > 2) Select("", seasons.map { if (it == 0) "All seasons" else "$it" }, si) { si = it } }) {
        if (p.career.isEmpty()) Muted("No moves recorded.")
        for (e in p.career.reversed().filter { si == 0 || it.season == seasons[si] }) {
            val fee = w.transfers.firstOrNull { it.playerId == p.id && it.day == e.day && it.toTeam == e.teamId }?.fee ?: 0.0
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(78.dp)) { Text(Dates.short(e.day), color = Ui.text, fontSize = 12.sp); Muted("season ${e.season}", 10) }
                e.teamId.takeIf { it >= 0 }?.let { TitleTag(w.teams[it].titleId) }
                Tag(e.type)
                Column(Modifier.weight(1f)) { if (e.teamId >= 0) TeamChip(w, e.teamId, vm, 16.dp) else Muted("—") }
                if (fee > 0) Text(formatMoney(fee), color = Ui.gold, fontSize = 12.sp)
            }
        }
    }
    val teams = p.stats.groupBy { it.teamId }
    if (teams.isNotEmpty()) Card("Clubs") {
        Table(listOf(Col("Team", 150.dp, false), Col("Seasons", 70.dp), Col("GP", 40.dp), Col("W-L", 60.dp), Col("Rating", 50.dp)), teams.size) { r, c ->
            val (tid, lines) = teams.entries.toList()[r]
            when (c) {
                0 -> if (tid >= 0) TeamChip(w, tid, vm, 16.dp) else Muted("—")
                1 -> Num(lines.map { it.season }.distinct().sorted().let { if (it.size == 1) "${it[0]}" else "${it.first()}–${it.last()}" })
                2 -> Num("${lines.sumOf { it.gp }}")
                3 -> Num("${lines.sumOf { it.w }}-${lines.sumOf { it.l }}")
                else -> Num("%.2f".format(lines.sumOf { it.ratingSum } / maxOf(1, lines.sumOf { it.gp })), bold = true)
            }
        }
    }
}

@Composable
private fun PlayerStatistics(vm: GameVm, w: World, p: Player) {
    val all = w.comps.filter { (it.pstats[p.id]?.gp ?: 0) > 0 }.sortedWith(compareByDescending<dev.uem.em.model.Competition> { it.season }.thenBy { it.startDay })
    if (all.isNotEmpty()) {
        val seasons = all.map { it.season }.distinct()
        var si by remember(p.id) { mutableIntStateOf(0) }
        var gi by remember(p.id) { mutableIntStateOf(0) }
        val rows = all.filter { it.season == seasons[si.coerceIn(0, seasons.size - 1)] }
        val tt = Catalogue.get(rows.firstOrNull()?.titleId ?: p.titleId)
        val gs = groups(tt)
        val cols = gs[gi.coerceIn(0, gs.size - 1)].second
        val idx: (String) -> Int = { tt.statIndex[it] ?: -1 }
        Card("By competition") {
            Row { Select("Season", seasons.map { "$it" }, si) { si = it }; Select("Stats", gs.map { it.first }, gi) { gi = it } }
            Table(listOf(Col("Competition", 130.dp, false), Col("Team", 48.dp, false)) + cols.map { Col(it.title, 56.dp) }, rows.size, onRow = { vm.go(Screen.Comp(rows[it].id)) }) { r, c ->
                val s = rows[r].pstats[p.id]!!
                when (c) {
                    0 -> Text(rows[r].short, color = Ui.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    1 -> Muted(w.teams.getOrNull(s.teamId)?.short ?: "—", 12)
                    else -> { val col = cols[c - 2]; Num(col.v(s.gp, s.w, s.r, s.m, s.v, idx)?.let { col.fmt(it) } ?: "—") }
                }
            }
        }
    }
    val titles = p.stats.map { it.titleId }.distinct()
    for (tid in titles) {
        val tt = Catalogue.get(tid)
        val lines = p.stats.filter { it.titleId == tid }.sortedBy { it.season }
        val gp = lines.sumOf { it.gp }
        val tot = DoubleArray(tt.stats.size).also { a -> for (l in lines) for (i in a.indices) a[i] += l.s.getOrElse(i) { 0.0 } }
        fun kda(s: DoubleArray) = (s.getOrElse(tt.statIndex["k"] ?: 0) { 0.0 } + s.getOrElse(tt.statIndex["a"] ?: 2) { 0.0 }) / maxOf(1.0, s.getOrElse(tt.statIndex["d"] ?: 1) { 0.0 })
        Card("${tt.name} by season") {
            Muted("Achievements stay with the title they were earned in.", 11)
            val cols = listOf(Col("Season", 52.dp, false), Col("Team", 48.dp, false), Col("GP", 34.dp), Col("W-L", 52.dp), Col("Rating", 48.dp), Col("KDA", 44.dp)) + tt.stats.map { Col(it.label + "/g", 58.dp) } + Col("MVP", 38.dp)
            Table(cols, lines.size + 1, highlight = { it == lines.size }) { r, c ->
                val total = r == lines.size
                val l = lines.getOrNull(r)
                val g = if (total) gp else l!!.gp
                val s = if (total) tot else l!!.s
                when {
                    c == 0 -> Num(if (total) "Career" else "${l!!.season}", bold = total)
                    c == 1 -> if (total) Muted("") else Muted(w.teams.getOrNull(l!!.teamId)?.short ?: "—", 12)
                    c == 2 -> Num("$g", bold = total)
                    c == 3 -> Num(if (total) "${lines.sumOf { it.w }}-${lines.sumOf { it.l }}" else "${l!!.w}-${l.l}", bold = total)
                    c == 4 -> Num("%.2f".format((if (total) lines.sumOf { it.ratingSum } else l!!.ratingSum) / maxOf(1, g)), bold = true)
                    c == 5 -> Num("%.2f".format(kda(s)), bold = total)
                    c < cols.size - 1 -> Num("%.1f".format(s.getOrElse(c - 6) { 0.0 } / maxOf(1, g)), bold = total)
                    else -> Num("${if (total) lines.sumOf { it.mvps } else l!!.mvps}", bold = total)
                }
            }
        }
    }
    if (titles.isEmpty() && all.isEmpty()) Card { Muted("No competitive games played yet.") }
}

@Composable
private fun PlayerContract(vm: GameVm, w: World, p: Player, own: Boolean) {
    Card("Current contract") {
        if (p.teamId < 0) Muted("Free agent.") else {
            KV("Club") { TeamChip(w, p.teamId, vm, 16.dp) }
            KV("Salary", formatMoney(p.salary) + " / season")
            KV("Expires", "end of ${p.contractEnd}")
            KV("Seasons left", "${maxOf(0, p.contractEnd - w.season)}")
            KV("Market value", formatMoney(Economy.marketValue(w, p)))
            KV("Expected salary (market)", formatMoney(Economy.expectedSalary(p, p.titleId, w.teams[p.teamId].region)))
            if (own) KV("Release cost", formatMoney(p.salary * (maxOf(0, p.contractEnd - w.season) + 0.5) * 0.5), Ui.loss)
            if (p.loanFrom >= 0) KV("On loan from") { TeamChip(w, p.loanFrom, vm, 16.dp) }
            if (p.loanFrom >= 0) KV("Loan ends", "end of ${p.loanUntil}")
            if (p.preContractTeam >= 0) KV("Pre-contract", "${w.teams[p.preContractTeam].name}, ${formatMoney(p.preContractSalary)} from next season")
            KV("Transfer listed", if (p.listed) "Yes" else "No")
            KV("Transfer window", Market.windowLabel(w))
        }
    }
    PlayerActions(vm, w, p)
}

@Composable
private fun PlayerDevelopment(w: World, p: Player, know: Int) {
    Card("Current Ability by season") {
        val start = w.season - p.caHistory.size
        val pts = p.caHistory.mapIndexed { i, v -> (start + i).toFloat() to v } + (w.season.toFloat() to p.ca.toFloat())
        val series = buildList {
            add(Series("Current ability", Ui.accent, pts))
            if (know >= 80) add(Series("Potential", Ui.gold.copy(alpha = 0.6f), listOf(pts.first().first to p.pa.toFloat(), pts.last().first to p.pa.toFloat())))
        }
        LineChart(series, 30f, 100f, { "%.0f".format(it) })
        Gap(6)
        Muted("Training focus, coaching, facilities, work ethic, learning speed, age and the hidden trajectory all shape development. Proficiencies grow faster than permanent attributes.", 11)
    }
    Card("Development profile") {
        val age = Dates.ageAt(p.birth, w.day)
        KV("Age", "$age")
        KV("Trajectory", if (know >= 70) p.dev.trajectory else "Scout further to assess")
        KV("Expected peak", if (know >= 70) "around ${p.dev.peakAge}" else "?")
        KV("Decline from", if (know >= 70) "around ${p.dev.declineAge}" else "?")
        KV("Potential", if (w.userOrg >= 0) Extras.potentialText(w, p) else "%.0f".format(p.pa))
        KV("Room to grow", if (know >= 80) "%.1f".format((p.pa - p.ca).coerceAtLeast(0.0)) else "?")
        for (i in Attrs.group(AttrGroup.Development)) KV(Attrs.ALL[i].name) { Rating(p.attrs[i]) }
    }
}

@Composable
private fun PlayerHistory(vm: GameVm, w: World, p: Player) {
    Card("Transfer history") {
        val list = w.transfers.filter { it.playerId == p.id }.reversed()
        if (list.isEmpty()) Muted("No transfers since the save began.")
        for (x in list) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(70.dp)) { Text(Dates.short(x.day), color = Ui.text, fontSize = 12.sp); Muted("${x.season}", 10) }
            Tag(x.kind)
            Column(Modifier.weight(1f)) {
                if (x.fromTeam >= 0) TeamChip(w, x.fromTeam, vm, 14.dp) else Muted("—")
                Row(verticalAlignment = Alignment.CenterVertically) { Muted("→ ", 12); if (x.toTeam >= 0) TeamChip(w, x.toTeam, vm, 14.dp) else Muted("released") }
            }
            if (x.fee > 0) Text(formatMoney(x.fee), color = Ui.gold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlayerAwards(vm: GameVm, w: World, p: Player, honours: List<Legacy.Honour>, lg: Legacy.Score) {
    Card("Honours") {
        if (honours.isEmpty()) Muted("No honours yet.")
        val inGame = honours.filter { !it.past }; val past = honours.filter { it.past }
        @Composable fun row(h: Legacy.Honour) = Row(Modifier.fillMaxWidth().clickable(enabled = h.compId >= 0) { vm.go(Screen.Comp(h.compId)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (h.trophy) "🏆" else if (h.label.contains("runner-up")) "🥈" else "🎖", fontSize = 15.sp, modifier = Modifier.width(24.dp))
            Text("${h.season}", color = Ui.muted, fontSize = 12.sp, modifier = Modifier.width(40.dp))
            TitleTag(h.titleId)
            Column(Modifier.weight(1f)) {
                Text(h.label, color = if (h.trophy) Ui.gold else Ui.text, fontSize = 13.sp, maxLines = 2)
                if (h.team.isNotBlank()) Muted(h.team, 11)
            }
            Muted("+%.1f".format(h.pts), 11)
        }
        for (h in inGame) row(h)
        if (past.isNotEmpty()) {
            if (inGame.isNotEmpty()) Gap(8)
            Text("Before the save (real career): ${past.count { it.trophy }} titles, ${past.count { it.label.contains("runner-up") }} other finals", color = Ui.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            for (h in past.take(60)) row(h)
            if (past.size > 60) Muted("… and ${past.size - 60} more", 11)
        }
    }
    Card("Legacy (GOAT score)") {
        KV("Achievements (prestige-weighted)", "%.1f".format(lg.achievements))
        KV("Peak ability", "%.1f".format(lg.peak))
        KV("Longevity", "%.1f".format(lg.longevity))
        KV("Volume", "%.1f".format(lg.volume))
        if (lg.pastPts > 0) KV("of which before the save (real career)", "%.1f".format(lg.pastPts))
        KV("Total", "%.1f".format(lg.total), Ui.gold)
        for ((tid, v) in lg.byTitle) KV(Catalogue.get(tid).name, "%.1f achievement pts".format(v))
    }
    if (p.teamId >= 0 && w.teams[p.teamId].trophies.isNotEmpty()) Card("${w.teams[p.teamId].name} trophy cabinet") {
        for (tr in w.teams[p.teamId].trophies.reversed().take(12)) Text("🏆 ${tr.name}", color = Ui.gold, fontSize = 12.sp)
    }
}

/** Which facts about a player are verified, estimated or generated (never presenting ratings as measured). */
@Composable
private fun ProvenanceCard(w: World, p: Player) {
    val est = p.realEst.joinToString(" ").lowercase()
    val verified = Ui.win
    val estimated = Ui.gold
    Card("Data provenance") {
        if (!p.real) {
            KV("Identity", p.realEst.firstOrNull { it.startsWith("generated") } ?: "Fictional player (generated by the game)", estimated)
            KV("Ratings & attributes", "Game simulation", Ui.muted)
            return@Card
        }
        val host = Regex("https?://([^/]+)").find(p.realSrc)?.groupValues?.get(1) ?: "snapshot"
        KV("Identity (handle, real name)", "Verified · $host", verified)
        KV("Nationality", when { "nationality (from the real name)" in est -> "Estimated from the real name"; "nationality" in est -> "Estimated by the game"; else -> "Verified" }, if ("nationality" in est) estimated else verified)
        KV("Birth date", when { "birthday (year known)" in est -> "Year verified, day estimated"; "age" in est -> "Estimated by the game"; else -> "Verified" }, if ("age" in est || "year known" in est) estimated else verified)
        KV("Position", if ("role" in est) "Estimated (game assignment)" else "Verified", if ("role" in est) estimated else verified)
        KV("Team at the start", if ("free agent" in est) "Free agent (historical record)" else w.teams.getOrNull(p.career.firstOrNull()?.teamId ?: -1)?.dataNote?.ifBlank { null } ?: "Snapshot", Ui.text)
        KV("Ratings & attributes", "Game estimates, not real measurements", Ui.muted)
        if (p.realSrc.isNotBlank()) Muted("Source: ${p.realSrc}", 10)
    }
}


/** Real team spells before the save, newest first; a team in this world opens its page. */
@Composable
fun CareerSpells(vm: GameVm, w: World, titleId: String, spells: List<dev.uem.em.mgmt.Careers.Spell>) {
    val t = Catalogue.get(titleId)
    for (sp in spells.reversed()) {
        val team = remember(sp.team, titleId) { dev.uem.em.mgmt.HistoryBook.norm(sp.team).let { n -> w.teams.firstOrNull { it.titleId == titleId && (dev.uem.em.mgmt.HistoryBook.norm(it.name) == n || dev.uem.em.mgmt.HistoryBook.norm(it.short) == n) } } }
        Row(Modifier.fillMaxWidth().then(if (team != null) Modifier.clickable { vm.go(Screen.Team(team.id)) } else Modifier).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(sp.period(), color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(128.dp))
            Column(Modifier.weight(1f)) {
                Text(sp.team, color = if (team != null) Ui.accent else Ui.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val role = when {
                    sp.role.isEmpty() -> ""
                    sp.isStaff -> dev.uem.em.mgmt.Staffing.roleLabel(if (sp.role == "coach") "assistant-coach" else if (sp.role == "positional-coach") "position-coach" else if (sp.role == "manager") "team-manager" else sp.role, titleId).let { if (sp.role == "coach") "Coach" else it }
                    sp.role == "sub" -> "Substitute"
                    else -> t.lineupRoles.firstOrNull { it.id == sp.role || dev.uem.em.titles.RoleMeta.pos(it.id) == sp.role }?.name ?: sp.role.replaceFirstChar { it.uppercase() }
                }
                if (role.isNotEmpty()) Muted(role, 10)
            }
        }
    }
    Gap(4)
    Muted(dev.uem.em.mgmt.Careers.sourceNote(spells), 10)
}

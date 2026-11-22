package dev.uem.em.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.Dates
import dev.uem.em.mgmt.Prep
import dev.uem.em.model.PrepPlan
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue

/**
 * Prepare for Match: what is known about the opponent (and how reliable it is), the lineup's
 * readiness, the staff's recommendation, the user's plan (locked when the match starts) and the
 * strategy laboratory, a sandbox that replays the series on the real engines without recording it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrepareScreen(vm: GameVm, w: World, fixtureId: Int) {
    val f = w.fixtures[fixtureId]
    val teamId = if (w.isUserTeam(f.a)) f.a else if (w.isUserTeam(f.b)) f.b else -1
    if (teamId < 0 || f.a < 0 || f.b < 0) { Card { Muted("Only your own scheduled matches can be prepared.") }; return }
    val team = w.teams[teamId]
    val oppId = if (teamId == f.a) f.b else f.a
    val t = Catalogue.get(team.titleId)
    val c = w.comps[f.compId]
    val v = vm.version
    val plan = Prep.planFor(w, f.id, teamId)
    val locked = plan?.locked == true || f.done
    val report = remember(f.id, v) { Prep.opponentReport(w, f, teamId) }
    val ready = remember(f.id, v) { Prep.readiness(w, team) }
    val rec = remember(f.id, v) { Prep.recommend(w, f, team) }
    var labN by remember { mutableIntStateOf(1) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("Prepare for match", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically) { TeamChip(w, teamId, vm, 18.dp); Muted("  vs  ", 12); TeamChip(w, oppId, vm, 18.dp) }
                Muted("${c.name} · Bo${f.bestOf} · ${Dates.format(f.day)}${if (f.day <= w.day) " (today)" else " (in ${f.day - w.day} days)"}", 11)
                Muted(if (locked) "The plan is locked: it was fixed when the match started." else "The plan locks when the match starts (playing it live or simulating the day). Changes after that do not count.", 10)
            }
        }
        item {
            Card("Opponent report · confidence ${report.confidence}") {
                Muted("${report.games} recorded ${if (t.isMoba) "games" else "maps"}${if (report.newest < 999) ", newest ${report.newest} days ago" else ""} · lead analyst %.0f. Labels: observed = from recorded matches; assumed = from the team's profile; estimated = the analysts' reading.".format(report.analyst), 10)
                for ((k, value) in report.lines) KV(k, value)
                if (report.games < 4) Muted("Small sample: treat every tendency here as a guess.", 10)
            }
        }
        item {
            Card("Lineup readiness") {
                for (r in ready) {
                    val p = w.players[r.playerId]
                    Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Player(p.id)) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayerPic(p, 20.dp); HGap(6)
                        Text(p.handle, color = Ui.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Muted("fatigue %.0f · form %+.1f · role %.0f%s%s".format(r.fatigue, r.form, r.roleKnow, if (t.characters.isNotEmpty()) " · ${r.pool} comfort picks" else "", if (r.injured) " · INJURED" else ""), 10)
                    }
                }
                val warn = ready.filter { it.injured || it.fatigue > 60 }
                if (warn.isNotEmpty()) Text("⚠ ${warn.joinToString { w.players[it.playerId].handle }}: ${if (warn.any { it.injured }) "injury" else "high fatigue"} — consider resting or rotating (Team → Lineup).", color = Ui.gold, fontSize = 11.sp)
            }
        }
        item {
            Card("Staff recommendation") {
                KV("Plan", Prep.LABEL[rec.first.emphasis] ?: rec.first.emphasis)
                if (rec.first.map.isNotBlank()) KV("Map", t.maps.firstOrNull { it.id == rec.first.map }?.name ?: rec.first.map)
                Muted(rec.second, 11)
                if (!locked) TextButton(onClick = {
                    Prep.edit(w, f.id, teamId)?.let { p -> p.emphasis = rec.first.emphasis; p.map = rec.first.map; p.style = ""; p.by = "staff recommendation"; p.note = rec.first.note }
                    vm.edit()
                }) { Text("Use the staff's plan", color = Ui.accent) }
            }
        }
        item {
            Card("Your plan") {
                val cur = plan ?: PrepPlan(f.id, teamId)
                val share = Prep.expectedEffect(w, team, cur.emphasis)
                KV("Emphasis", Prep.LABEL[cur.emphasis] ?: cur.emphasis)
                if (t.isMoba) KV("Composition identity", cur.style.ifBlank { "the team's usual (${team.style.ifBlank { "balanced" }})" })
                if (!t.isMoba && cur.emphasis == "map") KV("Map", t.maps.firstOrNull { it.id == cur.map }?.name ?: "—")
                KV("Deliverable this week", "%.0f%% (analysts and coaches%s)".format(share * 100, if (share < 1) "; other prepared matches this week share their time" else ""))
                Muted(Prep.explain(cur.emphasis), 10)
                if (!locked) {
                    Gap(4)
                    FlowRow { for (e in Prep.EMPHASES) Pill(Prep.LABEL[e] ?: e, if (cur.emphasis == e) Ui.accent else Ui.card2) { Prep.edit(w, f.id, teamId)?.let { it.emphasis = e; it.by = "user" }; vm.edit() } }
                    if (t.isMoba && t.styles.isNotEmpty()) {
                        Gap(4); Muted("Composition identity", 11)
                        FlowRow {
                            Pill("Usual", if (cur.style.isBlank()) Ui.accent else Ui.card2) { Prep.edit(w, f.id, teamId)?.style = ""; vm.edit() }
                            for (s in t.styles) Pill(s, if (cur.style == s) Ui.accent else Ui.card2) { Prep.edit(w, f.id, teamId)?.style = s; vm.edit() }
                        }
                    }
                    if (!t.isMoba && cur.emphasis == "map" && t.maps.size > 1) {
                        Gap(4); Muted("Map to keep in the veto", 11)
                        FlowRow { for (m in t.maps) Pill(m.name, if (cur.map == m.id) Ui.accent else Ui.card2) { Prep.edit(w, f.id, teamId)?.map = m.id; vm.edit() } }
                    }
                    if (plan != null) TextButton(onClick = { w.preps.remove(plan); vm.edit() }) { Text("Clear plan (general practice)", color = Ui.muted) }
                } else if (plan != null) Muted("Locked ${Dates.short(plan.lockedDay)} · counted at %.0f%% · by ${plan.by}".format(plan.effect * 100), 10)
            }
        }
        item {
            Card("Strategy laboratory · sandbox") {
                Muted("Replays this series many times on the real match engines with each plan (the opponent as your analysts expect it). Nothing here is recorded, paid or applied; results are estimates with an 80% range, not a forecast of the real match.", 10)
                val sizes = listOf(10, 20, 50)
                Gap(4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted("Runs per plan ", 11)
                    for ((i, n) in sizes.withIndex()) Pill("$n", if (labN == i) Ui.accent else Ui.card2) { labN = i }
                }
                Gap(4)
                Button(enabled = vm.busy == null && !f.done, onClick = {
                    val options = ArrayList<Pair<String, PrepPlan>>()
                    plan?.let { options.add("Your plan (${Prep.LABEL[it.emphasis]})" to it) }
                    for (e in Prep.EMPHASES) {
                        val p = PrepPlan(f.id, teamId, e)
                        if (e == "map") { if (t.isMoba) continue; p.map = rec.first.map.ifBlank { Prep.bestMap(w, team) ?: "" } }
                        options.add((Prep.LABEL[e] ?: e) + (if (e == "map" && p.map.isNotBlank()) " (${t.maps.firstOrNull { it.id == p.map }?.name})" else "") to p)
                    }
                    vm.runLab(f.id, teamId, options, sizes[labN])
                }, colors = ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text("Run the laboratory") }
                val res = vm.labResult?.takeIf { it.first == f.id }?.second
                if (res != null) {
                    Gap(6)
                    for (r in res.sortedByDescending { it.wins.toDouble() / maxOf(1, it.n) }) Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Ui.card2, RoundedCornerShape(6.dp)).padding(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.label, color = Ui.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text("${r.wins}/${r.n}", color = Ui.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                        }
                        Muted("series win range %.0f–%.0f%%".format(r.lo * 100, r.hi * 100), 10)
                        Bar(r.wins.toDouble() / maxOf(1, r.n), 1.0)
                    }
                    val overlap = res.size > 1 && res.maxOf { it.lo } < res.minOf { it.hi }
                    if (overlap) Muted("The ranges overlap: the laboratory cannot tell these plans apart with this many runs.", 10)
                    if (res.any { it.n < sizes[labN] }) Muted("Stopped early: fewer runs than asked.", 10)
                }
            }
        }
    }
}

package dev.uem.em.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.comp.CustomComps
import dev.uem.em.core.Dates
import dev.uem.em.core.formatMoney
import dev.uem.em.model.CustomComp
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import kotlin.math.roundToInt

/** Create, edit, publish and cancel user competitions (seven games; formats the generators support). */
@Composable
fun CompEditorScreen(vm: GameVm, w: World) {
    var editing by remember { mutableIntStateOf(-1) }
    var rev by remember { mutableIntStateOf(0) }
    val c = w.customComps.firstOrNull { it.id == editing }
    if (c == null) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Card("Custom competitions") {
                    Muted("Create a fictional event for any of the seven games: invite teams or take qualifiers from leagues and finished competitions, pick a format, match lengths, dates and prize money. Every draft is checked before it can be published; published editions are played and paid like official events and recurring ones return every season.", 12)
                    TextButton(onClick = {
                        val id = (w.customComps.maxOfOrNull { it.id } ?: -1) + 1
                        val title = w.orgs.getOrNull(w.userOrg)?.teams?.map { w.teams[it].titleId }?.firstOrNull() ?: w.titles.first()
                        w.customComps.add(CustomComp(id, "New Invitational", "NEWI", title, boFinal = CustomComps.legalBo(title).last().coerceAtMost(5)))
                        editing = id; vm.edit()
                    }) { Text("+ New competition", color = Ui.accent) }
                }
            }
            for (cc in w.customComps.sortedByDescending { it.id }) item(key = cc.id) {
                Card {
                    Row(Modifier.fillMaxWidth().clickable { editing = cc.id }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cc.name, color = Ui.text, fontWeight = FontWeight.SemiBold)
                            Muted("${Catalogue.get(cc.titleId).short} · ${CustomComps.FORMAT_LABEL[cc.format]} · ${if (cc.cancelled) "cancelled" else if (cc.published) "published" + (if (cc.recurring) ", every season" else "") else "draft"} · ${cc.editions.size} edition(s)", 11)
                        }
                        Text("Edit ›", color = Ui.accent)
                    }
                    for (id in cc.editions) w.comps.getOrNull(id)?.let { comp -> Row(Modifier.clickable { vm.go(Screen.Comp(comp.id)) }.padding(vertical = 2.dp)) { Muted("• ${comp.name}: " + (if (comp.done) comp.placements.firstOrNull()?.let { "won by ${w.teams[it].name}" } ?: "ended" else if (comp.status == 1) "in progress" else "starts ${Dates.format(comp.startDay)}"), 11) } }
                }
            }
        }
        return
    }
    rev.let { }
    val chk = CustomComps.validate(w, c)
    fun changed() { rev++; vm.edit() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card(if (c.published) "Edit ${c.name} (published)" else "New competition (draft)", right = { TextButton(onClick = { editing = -1 }) { Text("Done", color = Ui.accent) } }) {
                OutlinedTextField(c.name, { c.name = it.take(40); changed() }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(c.short, { c.short = it.take(8); changed() }, label = { Text("Short name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!c.published) { Muted("Game", 11); Tabs(w.titles.map { Catalogue.get(it).short }, w.titles.indexOf(c.titleId).coerceAtLeast(0)) { c.titleId = w.titles[it]; c.teams.clear(); c.sources.clear(); changed() } }
                Muted("Format", 11)
                Tabs(CustomComps.FORMATS.map { CustomComps.FORMAT_LABEL[it]!! }, CustomComps.FORMATS.indexOf(c.format)) { c.format = CustomComps.FORMATS[it]; changed() }
                if (c.format.startsWith("groups")) {
                    Muted("Groups: ${c.groups} · advancing per group: ${c.advance}", 12)
                    Slider(c.groups.toFloat(), { c.groups = it.roundToInt(); changed() }, valueRange = 1f..8f, steps = 6)
                    Slider(c.advance.toFloat(), { c.advance = it.roundToInt(); changed() }, valueRange = 1f..4f, steps = 2)
                }
                val bos = CustomComps.legalBo(c.titleId)
                Muted("Best of — groups / league: ${c.boGroup}, playoffs: ${c.boPlayoff}, final: ${c.boFinal}", 12)
                Tabs(bos.map { "Bo$it" }, bos.indexOf(c.boGroup).coerceAtLeast(0)) { c.boGroup = bos[it]; changed() }
                Tabs(bos.map { "Bo$it" }, bos.indexOf(c.boPlayoff).coerceAtLeast(0)) { c.boPlayoff = bos[it]; changed() }
                Tabs(bos.map { "Final Bo$it" }, bos.indexOf(c.boFinal).coerceAtLeast(0)) { c.boFinal = bos[it]; changed() }
                if (Catalogue.get(c.titleId).isMoba) { Muted("Draft rule", 11); val f = listOf("none", "soft", "hard", "insane"); Tabs(listOf("Normal", "Soft Fearless", "Fearless", "Insane Fearless"), f.indexOf(c.fearless).coerceAtLeast(0)) { c.fearless = f[it]; changed() } }
                if (Catalogue.get(c.titleId).isMoba) { Muted("Coach comms in fights (3 × 45 s per game)", 11); Tabs(listOf("Game's rule", "Allowed", "Not allowed"), when (c.coachComms) { 1 -> 1; 0 -> 2; else -> 0 }) { c.coachComms = when (it) { 1 -> 1; 2 -> 0; else -> -1 }; changed() } }
                else { Muted("Tactical timeouts per team per map", 11); val opts = listOf(-1, 0, 1, 2, 3, 4); Tabs(listOf("Default") + opts.drop(1).map { "$it" }, opts.indexOf(c.timeouts).coerceAtLeast(0)) { c.timeouts = opts[it]; changed() } }
            }
        }
        item {
            Card("Dates, money and venue") {
                Muted("Starts ${Dates.format(Dates.day(w.season, c.startMonth, c.startDom))} (next season if that has passed) · ${c.daysBetween} day(s) between knockout rounds", 12)
                Slider(c.startMonth.toFloat(), { c.startMonth = it.roundToInt(); changed() }, valueRange = 1f..11f, steps = 9)
                Slider(c.startDom.toFloat(), { c.startDom = it.roundToInt(); changed() }, valueRange = 1f..28f, steps = 26)
                Slider(c.daysBetween.toFloat(), { c.daysBetween = it.roundToInt(); changed() }, valueRange = 1f..4f, steps = 2)
                Muted("Prize money ${formatMoney(c.prize)} · entry fee per invited team ${formatMoney(c.entryFee)}", 12)
                Slider((c.prize / 25000).toFloat(), { c.prize = it.roundToInt() * 25000.0; changed() }, valueRange = 0f..80f)
                Slider((c.entryFee / 5000).toFloat(), { c.entryFee = it.roundToInt() * 5000.0; changed() }, valueRange = 0f..20f)
                Tabs(listOf("LAN", "Online"), if (c.lan) 0 else 1) { c.lan = it == 0; changed() }
                Tabs(listOf("Every season", "One-off"), if (c.recurring) 0 else 1) { c.recurring = it == 0; changed() }
            }
        }
        item {
            Card("Teams (${chk.teams.size})") {
                val pool = w.teams.filter { it.titleId == c.titleId && it.active && it.nationalOf.isEmpty() }.sortedByDescending { it.elo }
                Row {
                    TextButton(onClick = { for (tm in pool.take(8)) if (tm.id !in c.teams) c.teams.add(tm.id); changed() }) { Text("+ Top 8 by Elo", color = Ui.accent, fontSize = 12.sp) }
                    TextButton(onClick = { for (tm in pool.take(16)) if (tm.id !in c.teams) c.teams.add(tm.id); changed() }) { Text("+ Top 16", color = Ui.accent, fontSize = 12.sp) }
                    TextButton(onClick = { c.teams.clear(); c.sources.clear(); changed() }) { Text("Clear", color = Ui.loss, fontSize = 12.sp) }
                }
                Muted("Qualifiers from leagues:", 11)
                Row { for (l in w.leagues.filter { it.titleId == c.titleId && it.tier == 1 }.take(6)) TextButton(onClick = { val s = "league:${l.id}"; if (s !in c.sources) c.sources.add(s); changed() }) { Text(l.short, color = Ui.accent, fontSize = 11.sp) } }
                for (src in c.sources) Row(verticalAlignment = Alignment.CenterVertically) { Muted("• " + (if (src.startsWith("league")) "All of ${w.leagues[src.split(':')[1].toInt()].name}" else "Top ${src.split(':')[2]} of ${w.comps[src.split(':')[1].toInt()].name}"), 11); TextButton(onClick = { c.sources.remove(src); changed() }) { Text("remove", color = Ui.loss, fontSize = 11.sp) } }
                for (tm in pool.take(40)) Row(Modifier.fillMaxWidth().clickable { if (tm.id in c.teams) c.teams.remove(tm.id) else c.teams.add(tm.id); changed() }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (tm.id in c.teams) "☑" else "☐", color = Ui.accent); HGap(6); TeamPic(tm, 20.dp); HGap(6)
                    Text(tm.name, color = Ui.text, fontSize = 13.sp, modifier = Modifier.weight(1f)); Muted("Elo ${tm.elo.toInt()}", 11)
                }
            }
        }
        item {
            Card("Check & preview") {
                for (e in chk.errors) Text("✕ $e", color = Ui.loss, fontSize = 12.sp)
                for (wn in chk.warnings) Text("! $wn", color = Ui.gold, fontSize = 12.sp)
                if (chk.errors.isEmpty()) Text("✓ Ready to publish", color = Ui.win, fontSize = 12.sp)
                for (p in chk.preview) Muted("• $p", 12)
                Row {
                    if (!c.cancelled) Btn(if (c.published) "Publish this season's edition" else "Publish") { val err = CustomComps.publish(w, c); vm.toast = err ?: "${c.name} published"; changed() }
                    if (!c.published) TextButton(onClick = { w.customComps.remove(c); editing = -1; vm.edit() }) { Text("Delete draft", color = Ui.loss) }
                    else if (!c.cancelled) TextButton(onClick = { CustomComps.cancel(w, c); vm.toast = "Future editions cancelled"; changed() }) { Text("Cancel future editions", color = Ui.loss) }
                }
                Muted("User-created events are marked as custom and are kept apart from the official calendar and history.", 10)
            }
        }
    }
}

@Composable
private fun Btn(text: String, onClick: () -> Unit) = androidx.compose.material3.Button(onClick = onClick, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Ui.accent)) { Text(text, fontSize = 13.sp) }

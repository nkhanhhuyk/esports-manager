package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.uem.em.comp.Fearless
import dev.uem.em.mgmt.Release
import dev.uem.em.mgmt.TierList
import dev.uem.em.model.World
import dev.uem.em.sim.Patch
import dev.uem.em.titles.Catalogue

private val TIER_COLORS = mapOf("S" to Color(0xFFFF7F7F), "A" to Color(0xFFFFBF7F), "B" to Color(0xFFFFDF7F), "C" to Color(0xFFBFFF7F), "D" to Color(0xFF7FBFFF))

/** A weapon icon (official picture or a generated emblem); tap for its sheet. */
@Composable
fun WeaponPic(id: String, name: String, category: String, size: androidx.compose.ui.unit.Dp = 32.dp, tap: Boolean = true) {
    Box(if (tap) Modifier.clickable { Sheets.openWeapon(id) } else Modifier) {
        Pic(Pics.weapon(id), name, size, false, Color(0xFF1D2539), fallback = { GenWeapon(id.hashCode(), size, category) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TierRows(rows: List<Pair<String, List<TierList.Entry>>>, icon: @Composable (TierList.Entry) -> Unit) {
    for ((tier, list) in rows) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Ui.card2, RoundedCornerShape(8.dp))) {
        Box(Modifier.width(40.dp).heightIn(min = 64.dp).background(TIER_COLORS[tier]!!, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)), contentAlignment = Alignment.Center) {
            Text(tier, color = Color.Black, fontWeight = FontWeight.Black, fontSize = 22.sp)
        }
        FlowRow(Modifier.weight(1f).padding(6.dp)) {
            if (list.isEmpty()) Muted("—", 12)
            for (e in list) Column(Modifier.width(62.dp).padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                icon(e)
                Text(e.name, color = Ui.text, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (e.note.isNotEmpty()) Text(e.note, color = Ui.muted, fontSize = 8.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Tier lists for every game: characters by role (and by map), weapons by position / map / type, CS2 loadouts. */
@Composable
fun TierScreen(vm: GameVm, w: World) {
    var title by remember { mutableStateOf(w.orgs.getOrNull(w.userOrg)?.teams?.firstOrNull()?.let { w.teams[it].titleId } ?: "lol") }
    var view by remember(title) { mutableIntStateOf(0) }
    var pick by remember(title, view) { mutableIntStateOf(0) }
    val t = Catalogue.get(title)
    val views = when (title) {
        "valorant" -> listOf("Agents by role", "Agents by map", "Guns by role", "Guns by map", "Guns by type")
        "cs2" -> listOf("Overall", "Guns by role", "Loadouts by role", "Guns by map", "Guns by type")
        else -> listOf("By role", "Overall")
    }
    val cats = Release.weaponsOf(w, title).map { it.category }.distinct().sorted()
    val options: List<String> = when (views.getOrNull(view)) {
        "By role", "Agents by role", "Guns by role", "Loadouts by role" -> t.lineupRoles.map { it.name }
        "Agents by map", "Guns by map" -> t.maps.map { it.name }
        "Guns by type" -> cats
        else -> emptyList()
    }
    val sel = pick.coerceIn(0, maxOf(0, options.size - 1))
    val rows = remember(title, view, sel, vm.version) {
        val role = t.lineupRoles.getOrNull(sel)
        val map = t.maps.getOrNull(sel)?.id
        when (views[view]) {
            "By role", "Agents by role" -> TierList.characters(w, t, role)
            "Overall" -> if (title == "cs2") TierList.weapons(w, t) else TierList.characters(w, t, null)
            "Agents by map" -> TierList.characters(w, t, null, map)
            "Guns by role" -> TierList.weapons(w, t, role)
            "Guns by map" -> TierList.weapons(w, t, mapId = map)
            "Guns by type" -> TierList.weapons(w, t, category = cats.getOrNull(sel))
            "Loadouts by role" -> TierList.loadouts(w, t, role!!)
            else -> emptyList()
        }
    }
    val patch = w.patches.lastOrNull { it.titleId == title }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("Tier lists", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Muted("${t.name} · patch ${patch?.version ?: "launch"} · from the patch's balance (what the matches use) and this season's results. Tap any icon for its stats and skills.", 11)
            }
        }
        item { Tabs(Catalogue.IDS.map { Catalogue.get(it).short }, Catalogue.IDS.indexOf(title)) { title = Catalogue.IDS[it] } }
        item { Tabs(views, view) { view = it; pick = 0 } }
        if (options.isNotEmpty()) item { Tabs(options, sel) { pick = it } }
        item {
            Card {
                val isWeapon = views[view].startsWith("Guns") || (title == "cs2" && views[view] == "Overall")
                val isLoadout = views[view] == "Loadouts by role"
                TierRows(rows) { e ->
                    when {
                        isLoadout -> Row { val ids = e.id.split('+'); for (id in ids.take(2)) { val wp = Release.weaponsOf(w, title).firstOrNull { it.id == id }; if (wp != null) WeaponPic(wp.id, wp.name, wp.category, 26.dp) } }
                        isWeapon -> { val wp = Release.weaponsOf(w, title).firstOrNull { it.id == e.id }; WeaponPic(e.id, e.name, wp?.category ?: "", 40.dp) }
                        else -> CharPic(e.id, e.name, 44.dp, round = true)
                    }
                }
            }
        }
        if (t.isMoba) item { ItemsCard(w, title) }
        if (t.isMoba) item { NeutralsCard(w, t) }
        if (t.characters.isNotEmpty() && t.draft.mode == "sequential" && !Fearless.applies(t)) item {
            Card("Draft rules (${t.short})") { Muted("Normal drafts only: Soft Fearless needs at least ${Fearless.POOL_FOR["soft"]} ${t.charLabel.lowercase()}s, Fearless ${Fearless.POOL_FOR["hard"]}, Insane Fearless ${Fearless.POOL_FOR["insane"]}; ${t.short} has ${t.characters.size}. New releases can lift it over.", 11) }
        }
        if (Fearless.applies(t)) item {
            Card("Draft rules (${t.short})") {
                for (scope in Fearless.SCOPES) KV(Fearless.SCOPE_LABEL[scope]!!, Fearless.LABEL[Fearless.rule(w, t, scope)]!!)
                Muted("${t.short} has ${t.characters.size} ${t.charLabel.lowercase()}s. Soft Fearless needs ${Fearless.POOL_FOR["soft"]}, Fearless ${Fearless.POOL_FOR["hard"]}, Insane Fearless ${Fearless.POOL_FOR["insane"]}; a rule the pool is too small for steps down. Major patches can change these rules; each game evolves on its own.", 11)
            }
        }
        item { Gap(16) }
    }
}

/** The sheet for a character or weapon (opened by tapping its icon anywhere). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InfoSheet(w: World, kind: String, id: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.94f).heightIn(max = 640.dp).background(Ui.card, RoundedCornerShape(14.dp)).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (kind == "char") CharSheetBody(w, id) else WeaponSheetBody(w, id)
            Gap(8)
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Close", color = Ui.accent) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharSheetBody(w: World, id: String) {
    val titleId = Sheets.titleOf(id) ?: run { Muted("Unknown character"); return }
    val t = Catalogue.get(titleId)
    val c = t.charIndex[id] ?: run { Muted("Unknown character"); return }
    val custom = w.customChars.firstOrNull { it.id == id }
    val patch = Patch.of(w, t)
    Row(verticalAlignment = Alignment.CenterVertically) {
        CharPic(id, c.name, 72.dp, round = true, tap = false)
        HGap(12)
        Column(Modifier.weight(1f)) {
            Text(c.name, color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            if (custom?.epithet?.isNotEmpty() == true) Muted(custom.epithet, 12)
            FlowRow { TitleTag(t.id); Tag(c.cls); for (r in t.lineupRoles.filter { c.fits(it) }) Tag(r.short, Ui.accent.copy(alpha = 0.3f)) }
            if (custom != null) Text("Fictional ${t.charLabel.lowercase()} released in season ${custom.season}", color = Ui.gold, fontSize = 11.sp)
        }
    }
    Gap(8)
    val meta = patch.charMeta[id] ?: 0.0
    KV("Current patch", "%+.1f meta".format(meta), if (meta >= 0) Ui.win else Ui.loss)
    for (role in t.lineupRoles.filter { c.fits(it) }) {
        val tier = TierList.characters(w, t, role).firstOrNull { (_, l) -> l.any { it.id == id } }?.first
        if (tier != null) KV("Tier as ${role.name}", tier, TIER_COLORS[tier] ?: Ui.text)
    }
    val u = w.charStats["${w.season}|$id"]
    if (u != null) KV("This season", "${u[0]} games · ${if (u[0] > 0) "${100 * u[1] / u[0]}% wins" else "—"} · ${u[2]} bans")
    val info = GameInfo.char(id)
    val stats = info?.stats ?: custom?.let { cc -> listOf("Difficulty" to "${cc.kit.getOrElse(0) { 5 }} / 10", "Offense" to "${cc.kit.getOrElse(1) { 5 }} / 10", "Durability" to "${cc.kit.getOrElse(2) { 5 }} / 10", "Control" to "${cc.kit.getOrElse(3) { 5 }} / 10") } ?: emptyList()
    if (stats.isNotEmpty()) {
        Gap(6)
        Text("Base stats", color = Ui.text, fontWeight = FontWeight.SemiBold)
        for ((k, v) in stats) KV(k, v)
    }
    // Counters and synergies on the current patch (they shift a little with every patch).
    val (countered, strong) = remember(id, patch) { dev.uem.em.sim.Counters.countersOf(t, patch, id, 6) }
    val partners = remember(id, patch) { t.characters.filter { it.id != id }.map { it.id to dev.uem.em.sim.Counters.synergy(t, id, it.id) }.sortedByDescending { it.second }.take(6) }
    @Composable fun row(title: String, list: List<Pair<String, Double>>, color: Color) {
        if (list.isEmpty()) return
        Gap(6)
        Text(title, color = Ui.text, fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            for ((cid, v) in list) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp)) {
                CharPic(cid, t.charIndex[cid]?.name ?: cid, 36.dp, round = true)
                Text(t.charIndex[cid]?.name ?: cid, color = Ui.text, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("%+.0f".format(v * 100), color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (t.characters.size > 1) {
        row("Countered by (this patch)", countered, Ui.loss)
        row("Strong against", strong, Ui.win)
        if (t.isMoba) row("Works well with", partners, Ui.accent)
        val p = dev.uem.em.sim.Counters.profile(t, id)
        if (p != null && t.isMoba) Muted("Kit: control ${(p.hardCc * 100).toInt()} · mobility ${(p.mob * 100).toInt()} · range ${(p.range * 100).toInt()} · sustain ${(p.sustain * 100).toInt()} · burst ${(p.burst * 100).toInt()} · durability ${(p.tanky * 100).toInt()} (percentiles in ${t.short}); matchup scores change with patches.", 10)
    }
    val skills = info?.skills ?: custom?.skills?.map { it.split('|').let { p -> Triple(p[0], p[1], p.getOrElse(2) { "" }) } } ?: emptyList()
    Gap(6)
    Text("Skills", color = Ui.text, fontWeight = FontWeight.SemiBold)
    if (skills.isEmpty()) Muted("Skill details for ${t.name} are not in the offline data snapshot (only classes, lanes and kit ratings).", 11)
    for ((slot, name, desc) in skills) Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Tag(slot, Ui.accent.copy(alpha = 0.35f)); Text(name, color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
        if (desc.isNotEmpty()) Text(desc, color = Ui.muted, fontSize = 12.sp)
    }
    val best = w.players.filter { !it.retired && it.titleId == t.id && (it.profChar[id] ?: 0.0) > 0 }.sortedByDescending { it.profChar[id] }.take(6)
    if (best.isNotEmpty()) {
        Gap(6)
        Text("Best players on ${c.name}", color = Ui.text, fontWeight = FontWeight.SemiBold)
        for (p in best) Row(Modifier.fillMaxWidth().clickable { Sheets.open = null; Links.go?.invoke(Screen.Player(p.id)) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerPic(p, 22.dp); HGap(6)
            Text(p.handle, color = Ui.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Rating(p.profChar[id] ?: 0.0, size = 12)
        }
    }
    if (info != null) Muted("Source: ${info.source}", 10)
}

@Composable
private fun WeaponSheetBody(w: World, id: String) {
    val titleId = id.substringBefore('|')
    val wp = Release.weaponsOf(w, titleId).firstOrNull { it.id == id } ?: run { Muted("Unknown weapon"); return }
    val t = Catalogue.get(titleId)
    Row(verticalAlignment = Alignment.CenterVertically) {
        WeaponPic(id, wp.name, wp.category, 72.dp, tap = false)
        HGap(12)
        Column {
            Text(wp.name, color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Row { TitleTag(titleId); Tag(wp.category) }
        }
    }
    Gap(8)
    val meta = Patch.of(w, t).charMeta["w:$id"] ?: 0.0
    if (meta != 0.0) KV("Patch changes", "%+.1f".format(meta), if (meta >= 0) Ui.win else Ui.loss)
    val overall = TierList.weapons(w, t).firstOrNull { (_, l) -> l.any { it.id == id } }?.first
    if (overall != null) KV("Overall tier", overall, TIER_COLORS[overall] ?: Ui.text)
    for (role in t.lineupRoles) {
        val tier = TierList.weapons(w, t, role).firstOrNull { (_, l) -> l.any { it.id == id } }?.first ?: continue
        KV("Tier for ${role.name}", tier, TIER_COLORS[tier] ?: Ui.text)
    }
    Gap(6)
    Text("Stats", color = Ui.text, fontWeight = FontWeight.SemiBold)
    for ((k, v) in wp.stats) KV(k, v)
    Muted("Source: ${wp.source}", 10)
}

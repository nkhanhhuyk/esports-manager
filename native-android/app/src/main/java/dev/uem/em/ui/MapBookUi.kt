package dev.uem.em.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.mgmt.MapPools
import dev.uem.em.model.World
import dev.uem.em.sim.MapForge
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.MapMeta
import dev.uem.em.titles.TitleDef
import dev.uem.em.mgmt.TierList
import kotlin.math.roundToInt

/**
 * The map library (More → Maps): every VALORANT and CS2 map, in the competitive pool or not, real or released by
 * a patch, with its layout (sites A-D, spawns, callouts, special routes), how it plays in this world (sides, round
 * endings, sites hit), the agents or guns it favours, the teams that win there and the players who know it best.
 */
@Composable
fun MapBookScreen(vm: GameVm, w: World, mapId: String) {
    if (mapId.isBlank()) MapList(vm, w) else MapDetail(vm, w, mapId)
}

private val SHOOTERS = listOf("valorant", "cs2")

private class MapRow(val id: String, val name: String, val status: String, val color: Color)

private fun rows(w: World, t: TitleDef): List<Pair<String, List<MapRow>>> {
    val pool = t.maps.map { it.id }
    val pending = w.mapPoolLog.filter { it.titleId == t.id && !it.applied }
    val custom = w.customMaps.filter { it.titleId == t.id }.associateBy { it.id }
    fun status(id: String): Pair<String, Color> {
        pending.firstOrNull { it.added == id }?.let { return "Joins the pool in ${it.effective}" to Ui.win }
        pending.firstOrNull { it.removed == id }?.let { return "Leaves the pool in ${it.effective}" to Ui.red }
        return if (id in pool) "In the pool" to Ui.accent else if (custom[id] != null) "Fictional · not in the pool" to Ui.muted else "Not in the pool" to Ui.muted
    }
    fun row(id: String) = status(id).let { (s, c) -> MapRow(id, t.mapName(id), s + (custom[id]?.let { m -> " · new in ${m.season}" } ?: ""), c) }
    val real = t.allMaps.map { it.id }.filter { dev.uem.em.ui.Maps.isReal(it) }
    return listOf(
        "Competitive pool · season ${w.season}" to pool.map(::row),
        "Real maps outside the pool" to real.filter { it !in pool }.map(::row),
        "Fictional maps (released by major patches)" to custom.keys.filter { it !in pool }.map(::row),
    ).filter { it.second.isNotEmpty() }
}

@Composable
private fun MapList(vm: GameVm, w: World) {
    val mine = w.orgs.getOrNull(w.userOrg)?.teams?.firstOrNull()?.let { w.teams[it].titleId }
    var title by remember { mutableStateOf(if (mine in SHOOTERS) mine!! else SHOOTERS.first()) }
    val t = Catalogue.get(title)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("Maps", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Muted("Every map of the game: the competitive pool, the real maps outside it and the fictional maps this world's major patches released. A major patch can rotate the pool (in force the next season). Tap a map for its layout and how it plays.", 11)
            }
        }
        item { Tabs(SHOOTERS.map { Catalogue.get(it).short }, SHOOTERS.indexOf(title)) { title = SHOOTERS[it] } }
        for ((head, list) in rows(w, t)) {
            item { Text(head, color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)) }
            items(list.size) { i ->
                val r = list[i]
                val m = dev.uem.em.ui.Maps.shooter(r.id)
                Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.MapBook(r.id)) }.padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (m != null) Box(Modifier.size(58.dp).background(Color(0xFF20262F), RoundedCornerShape(6.dp))) { MapLayout(t, m, Modifier.fillMaxSize(), callouts = false, small = true) }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(r.name, color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(r.status, color = r.color, fontSize = 11.sp)
                        if (m != null) Text("${m.sites.size} sites (${m.sites.joinToString(", ") { it.id }})" + specials(m).let { if (it.isEmpty()) "" else " · " + it.joinToString(", ") }, color = Ui.muted, fontSize = 11.sp)
                    }
                }
            }
        }
        item { Gap(16) }
    }
}

/** Special routes of a map, by link type. */
private fun specials(m: Maps.Shooter): List<String> = m.links.map { it.type }.toSet().mapNotNull {
    when (it) { "teleport" -> "teleporters"; "drop" -> "drops"; "door" -> "doors"; "breakable" -> "breakable wall"; "vent" -> "vents"; "stairs" -> "floors"; else -> null }
}

/** The layout: walls, corridors (special routes styled), sites with their letters, spawns, callouts. */
@Composable
fun MapLayout(t: TitleDef, m: Maps.Shooter, modifier: Modifier, callouts: Boolean, small: Boolean = false) {
    val cs = t.id == "cs2"
    val paint = remember { android.graphics.Paint().apply { color = android.graphics.Color.WHITE; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true } }
    val small2 = remember { android.graphics.Paint().apply { color = android.graphics.Color.rgb(200, 208, 220); textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true } }
    Canvas(modifier) {
        val W = minOf(size.width, size.height)
        fun p(o: Offset) = Offset(o.x * W, o.y * W)
        drawRect(Color(0xFF20262F), Offset.Zero, Size(W, W))
        for (r in m.walls) drawRect(Color(0xFF12161C), Offset(r[0] * W, r[1] * W), Size(r[2] * W, r[3] * W))
        if (!small) for (l in m.links) {
            val a = p(m.nodes[l.from].pos); val b = p(m.nodes[l.to].pos)
            when (l.type) {
                "teleport" -> drawLine(Color(0xCCB98CFF), a, b, strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 7f)))
                "drop" -> drawLine(Color(0xCCFFD166), a, b, strokeWidth = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 5f)))
                "door" -> drawLine(Color(0xCCF97316), a, b, strokeWidth = 4f)
                "breakable" -> drawLine(Color(0xCCEF4444), a, b, strokeWidth = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                "vent", "ladder", "stairs" -> drawLine(Color(0xAA66D9EF), a, b, strokeWidth = 4f)
                else -> drawLine(Color(0x22FFFFFF), a, b, strokeWidth = W * 0.01f)
            }
        }
        for (s in m.sites) drawCircle(Color(0x553B82F6), s.r * W, p(s.pos))
        drawCircle(Color(0xFFEF4444), W * (if (small) 0.035f else 0.018f), p(m.nodes[m.atk].pos))
        drawCircle(Color(0xFF3B82F6), W * (if (small) 0.035f else 0.018f), p(m.nodes[m.def].pos))
        drawContext.canvas.nativeCanvas.let { nc ->
            paint.textSize = W * (if (small) 0.16f else 0.06f)
            for (s in m.sites) nc.drawText(s.id, s.pos.x * W, s.pos.y * W + paint.textSize * 0.36f, paint)
            if (!small) {
                val sides = t.tactical!!.sides
                small2.textSize = W * 0.028f
                nc.drawText(if (cs) "T spawn" else "${sides.first} spawn", m.nodes[m.atk].pos.x * W, m.nodes[m.atk].pos.y * W - W * 0.028f, small2)
                nc.drawText(if (cs) "CT spawn" else "${sides.second} spawn", m.nodes[m.def].pos.x * W, m.nodes[m.def].pos.y * W + W * 0.045f, small2)
                if (callouts) {
                    small2.textSize = W * 0.022f
                    for ((i, nd) in m.nodes.withIndex()) {
                        if (nd.kind != "area" || i == m.atk || i == m.def) continue
                        val label = if (nd.sup.length <= 1) "${nd.sup} ${nd.name}" else nd.name
                        nc.drawText(label, nd.pos.x * W, nd.pos.y * W - W * 0.012f, small2)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapDetail(vm: GameVm, w: World, id: String) {
    val titleId = id.substringBefore(':')
    val t = Catalogue.get(titleId)
    val m = dev.uem.em.ui.Maps.shooter(id)
    if (m == null) { Muted("No layout for this map."); return }
    val cs = titleId == "cs2"
    val cm = w.customMaps.firstOrNull { it.id == id }
    val sides = t.tactical!!.sides
    val atkName = if (cs) "T" else sides.first
    val defName = if (cs) "CT" else sides.second
    val seasons = remember(id, vm.version) { w.mapStats.keys.filter { it.count { c -> c == '|' } == 1 && it.substringAfter('|') == id }.mapNotNull { it.substringBefore('|').toIntOrNull() }.distinct().sortedDescending() }
    var pick by remember(id) { mutableIntStateOf(0) } // 0 = all seasons, then each season
    val season = if (pick == 0) null else seasons.getOrNull(pick - 1)
    var showCallouts by remember { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(t.mapName(id), color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                val inPool = t.maps.any { it.id == id }
                val pending = w.mapPoolLog.firstOrNull { it.titleId == titleId && !it.applied && (it.added == id || it.removed == id) }
                Text("${t.short} · " + (if (inPool) "in the competitive pool" else "not in the competitive pool") + (pending?.let { if (it.added == id) " · joins in ${it.effective}" else " · leaves in ${it.effective}" } ?: "") +
                    (if (cm != null) " · fictional (patch ${cm.version})" else ""), color = if (inPool) Ui.accent else Ui.muted, fontSize = 12.sp)
                if (cm != null) { Gap(4); Muted(cm.blurb + (if (cm.features.isNotEmpty()) " " + cm.features.joinToString("; ") + "." else ""), 12) }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                MapLayout(t, m, Modifier.widthIn(max = 520.dp).fillMaxWidth().aspectRatio(1f), callouts = showCallouts)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(if (showCallouts) "Hide callouts" else "Show callouts", Ui.card2) { showCallouts = !showCallouts }
            }
            Muted("  Red dot: ${atkName} spawn · blue dot: ${defName} spawn · blue circles: sites. " + legend(m), 10)
        }
        item { Facts(t, m, cm != null) }
        if (seasons.isNotEmpty()) item {
            Column(Modifier.padding(horizontal = 12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("All seasons", if (pick == 0) Ui.accent else Ui.card2) { pick = 0 }
                    seasons.forEachIndexed { i, s -> Pill("$s", if (pick == i + 1) Ui.accent else Ui.card2) { pick = i + 1 } }
                }
            }
        }
        item { Profile(w, t, m, id, season, atkName, defName) }
        if (!cs) item { Agents(vm, w, t, m, id, season, cm != null) } else item { Guns(w, t, id) }
        item { BestTeams(w, t, id, season ?: w.season) }
        item { Specialists(w, t, id) }
        val hist = MapPools.history(w, id)
        if (hist.isNotEmpty()) item {
            Card("Pool history") {
                for (c in hist) Muted("${c.season} · patch ${c.version}: " + if (c.added == id) "added to the pool from ${c.effective}, replacing ${t.mapName(c.removed)}" else "left the pool from ${c.effective}, replaced by ${t.mapName(c.added)}", 12)
            }
        }
        item { Gap(16) }
    }
}

private fun legend(m: Maps.Shooter): String = specials(m).mapNotNull {
    when (it) { "teleporters" -> "purple dashes: one-way teleporters"; "drops" -> "yellow dashes: one-way drops"; "doors" -> "orange: doors"; "breakable wall" -> "red dashes: breakable wall"; "vents" -> "cyan: vents"; "floors" -> "cyan: stairs between floors"; else -> null }
}.joinToString(" · ")

/** Seconds to walk a route at running speed (sim.ShooterRound.SPEED, map widths per second). */
private fun secs(len: Float) = (len / 0.042f).roundToInt()

@Composable
private fun Facts(t: TitleDef, m: Maps.Shooter, fictional: Boolean) {
    val tr = remember(m) { MapForge.traits(m) }
    Card("Layout") {
        KV("Sites", "${m.sites.size} (${m.sites.joinToString(", ") { it.id }})")
        KV("Callouts", "${m.nodes.size}")
        val sp = specials(m)
        KV("Special routes", if (sp.isEmpty()) "none (corridors only)" else sp.joinToString(", "))
        for (s in m.sites) KV("To ${s.id}", "attackers ~${secs(MapForge.pathLen(m, m.atk, s.node))} s, defenders ~${secs(MapForge.pathLen(m, m.def, s.node))} s")
        if (m.sites.size > 1) KV("Rotation between sites", "~${secs(tr.rotation)} s on average")
        KV("Longest open sightline", "${(tr.longest * 100).roundToInt()}% of the map's width")
        Muted(if (fictional) "A fictional layout generated for this world (graph of callouts; walls from the corridors)." else "Approximate layout of the real map (graph of callouts; walls from the corridors).", 10)
    }
}

@Composable
private fun Profile(w: World, t: TitleDef, m: Maps.Shooter, id: String, season: Int?, atkName: String, defName: String) {
    val a = MapPools.totals(w, id, season)
    val cs = t.id == "cs2"
    Card("How it plays" + (season?.let { " · $it" } ?: " · all seasons")) {
        if (a[MapPools.F_ROUNDS] == 0) { Muted("Not played yet in this world.", 12); return@Card }
        fun pct(x: Int, n: Int) = if (n == 0) "—" else "${(x * 100.0 / n).roundToInt()}%"
        val r = a[MapPools.F_ROUNDS]; val atk = a[MapPools.F_ATK]
        KV("Games / rounds", "${a[MapPools.F_GAMES]} / $r")
        val ap = atk * 100.0 / r
        KV("Round wins", "$atkName ${pct(atk, r)} · $defName ${pct(r - atk, r)}", if (ap < 46) Ui.accent else if (ap > 54) Ui.red else Ui.text)
        Muted(if (ap < 46) "$defName-sided" else if (ap > 54) "$atkName-sided" else "Balanced", 11)
        KV("Pistol rounds won by the ${atkName.lowercase()} side", pct(a[MapPools.F_PISTOL_ATK], a[MapPools.F_PISTOL]))
        Gap(4)
        Text("Round endings", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        for (h in 0..3) KV("${RoundIcons.icon(h)} ${RoundIcons.label(cs, h)}", pct(a[MapPools.F_HOW + h], r))
        Gap(4)
        Text("Sites", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        val hits = (0 until m.sites.size).sumOf { a[MapPools.F_SITE + it] }
        for ((i, s) in m.sites.withIndex()) {
            val n = a[MapPools.F_SITE + i]
            KV("${s.id} site", "${pct(n, hits)} of attacks · ${atkName.lowercase()} win ${pct(a[MapPools.F_SITEWIN + i], n)} there")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Agents(vm: GameVm, w: World, t: TitleDef, m: Maps.Shooter, id: String, season: Int?, fictional: Boolean) {
    Card("Agents") {
        val name = { s: String -> t.characters.firstOrNull { it.id == "valorant-$s" }?.name ?: s.replaceFirstChar { it.uppercase() } }
        @Composable fun Faces(list: Collection<String>) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in list) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
                    CharPic("valorant-$s", name(s), 40.dp)
                    Text(name(s), color = Ui.text, fontSize = 10.sp)
                }
            }
        }
        val src = if (fictional) "from the layout" else if (MapMeta.known(id)) "professional meta (estimate)" else "from the layout"
        Text("Good here · $src", color = Ui.win, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Faces(MapMeta.staples(id))
        Text("Bad here · $src", color = Ui.red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Faces(MapMeta.weakOnes(id))
        KV("Flex player usually plays", MapMeta.flexClass(id))
        // This world's results on the map.
        val rows = remember(id, season, vm.version) {
            val agg = HashMap<String, IntArray>()
            for ((k, v) in w.charStats) {
                val parts = k.split('|'); if (parts.size != 3 || parts[1] != id) continue
                if (season != null && parts[0].toIntOrNull() != season) continue
                agg.getOrPut(parts[2]) { IntArray(2) }.let { it[0] += v[0]; it[1] += v[1] }
            }
            agg
        }
        val games = MapPools.totals(w, id, season)[MapPools.F_GAMES]
        if (rows.isNotEmpty() && games > 0) {
            Gap(6)
            Text("Picked in this world" + (season?.let { " · $it" } ?: ""), color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            for ((c, v) in rows.entries.sortedByDescending { it.value[0] }.take(8))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    CharPic(c, c, 24.dp)
                    Text(t.characters.firstOrNull { it.id == c }?.name ?: c, color = Ui.text, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 6.dp))
                    Text("picked ${(v[0] * 100.0 / (games * 2)).roundToInt()}% · wins ${(v[1] * 100.0 / v[0]).roundToInt()}%", color = Ui.muted, fontSize = 11.sp)
                }
            val solid = rows.entries.filter { it.value[0] >= 8 }
            if (solid.size >= 4) {
                val best = solid.maxByOrNull { it.value[1].toDouble() / it.value[0] }!!
                val worst = solid.minByOrNull { it.value[1].toDouble() / it.value[0] }!!
                KV("Best win rate (8+ games)", "${t.characters.firstOrNull { it.id == best.key }?.name ?: best.key} ${(best.value[1] * 100.0 / best.value[0]).roundToInt()}%")
                KV("Worst win rate (8+ games)", "${t.characters.firstOrNull { it.id == worst.key }?.name ?: worst.key} ${(worst.value[1] * 100.0 / worst.value[0]).roundToInt()}%")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Guns(w: World, t: TitleDef, id: String) {
    val tiers = remember(id) { TierList.weapons(w, t, mapId = id) }
    Card("Guns on this map") {
        Muted("Open maps with long sightlines favour rifles and the AWP; tight corners favour SMGs and shotguns (tier list for this map, this patch).", 11)
        for ((tier, list) in tiers) if (list.isNotEmpty() && (tier == "S" || tier == "A" || tier == "D")) {
            Text(if (tier == "D") "Weaker here" else "Tier $tier", color = if (tier == "D") Ui.red else Ui.win, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (e in list.take(8)) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
                    WeaponPic(e.id, e.name, e.note.substringBefore(" ·"), 34.dp)
                    Text(e.name, color = Ui.text, fontSize = 10.sp)
                }
            }
        }
        Muted("Utility that matters most here: smokes for the site entries, flashes for the corners on each site, a molotov for the closest choke (see the 2D replays).", 10)
    }
}

@Composable
private fun BestTeams(w: World, t: TitleDef, id: String, season: Int) {
    val recs = MapPools.teams(w, id, season).filter { it.value[0] >= 3 && it.key in w.teams.indices }
    Card("Best teams here · $season") {
        if (recs.isEmpty()) { Muted("No team has three games here this season yet.", 12); return@Card }
        for ((tid, v) in recs.entries.sortedWith(compareByDescending<Map.Entry<Int, IntArray>> { it.value[1].toDouble() / it.value[0] }.thenByDescending { it.value[0] }).take(8))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                TeamPic(w.teams[tid], 22.dp)
                TeamLink(w, tid, modifier = Modifier.weight(1f).padding(start = 6.dp))
                Text("${v[1]}-${v[0] - v[1]} · rounds ${v[2]}-${v[3]}", color = Ui.muted, fontSize = 11.sp)
            }
    }
}

@Composable
private fun Specialists(w: World, t: TitleDef, id: String) {
    val top = remember(id) { w.players.filter { !it.retired && it.titleId == t.id && it.teamId >= 0 && it.profMap.containsKey(id) }.sortedByDescending { it.profMap[id] ?: 0.0 }.take(8) }
    Card("Specialists (map experience)") {
        if (top.isEmpty()) { Muted("Nobody has played it yet.", 12); return@Card }
        for (p in top) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            PlayerLink(w, p.id, modifier = Modifier.weight(1f))
            Text(w.teams.getOrNull(p.teamId)?.short ?: "", color = Ui.muted, fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
            Rating(p.profMap[id] ?: 0.0)
        }
    }
}

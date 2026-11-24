package dev.uem.em.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.titles.Catalogue

/** Initial tab of the detail screens (tests render each tab). */
object UiDefaults { var tab = 0 }

/** Rating colours and bands, as in the web version (0..99). */
object Rate {
    private val COLORS = listOf(Color(0xFFBB3333), Color(0xFFE8743B), Color(0xFFD4B13A), Color(0xFF7FC65A), Color(0xFF2FC4A0), Color(0xFF3FA7FF), Color(0xFFC77DFF))
    fun tier(v: Double) = if (v >= 95) 6 else if (v >= 85) 5 else if (v >= 75) 4 else if (v >= 60) 3 else if (v >= 40) 2 else if (v >= 20) 1 else 0
    fun color(v: Double) = COLORS[tier(v)]
    fun band(v: Double) = listOf("Extremely poor", "Below average", "Average", "Good", "Excellent", "Elite", "World-class")[tier(v)]
}

@Composable
fun Rating(v: Double, digits: Int = 0, size: Int = 13, bold: Boolean = true) =
    Text("%.${digits}f".format(v), color = Rate.color(v), fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1)

/** A label / value row with a hairline under it (the web version's attr-row). */
@Composable
fun KV(label: String, value: String, color: Color = Ui.text, faded: Boolean = false) = KV(label, faded) {
    LinkedText(value, color, 13.sp, FontWeight.SemiBold, maxLines = 3, align = TextAlign.End, modifier = Modifier.widthIn(max = 230.dp))
}

@Composable
fun KV(label: String, faded: Boolean = false, value: @Composable () -> Unit) {
    Column(Modifier.alpha(if (faded) 0.45f else 1f)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Ui.muted, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            value()
        }
        HorizontalDivider(color = Ui.line.copy(alpha = 0.6f), thickness = 0.5.dp)
    }
}

/** A bar 0..max. */
@Composable
fun Bar(v: Double, max: Double = 100.0, color: Color = Ui.accent, modifier: Modifier = Modifier, h: Dp = 6.dp) {
    Box(modifier.height(h).background(Ui.card3, RoundedCornerShape(50))) {
        Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0.01f, 1f)).height(h).background(color, RoundedCornerShape(50)))
    }
}

class Tile(val label: String, val value: String, val sub: String? = null, val color: Color = Ui.text)

/** Headline numbers in tiles (the web version's stat strip). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Tiles(tiles: List<Tile>, perRow: Int = 3) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), maxItemsInEachRow = perRow) {
        for (t in tiles) Column(
            Modifier.weight(1f).padding(4.dp).background(Ui.card, RoundedCornerShape(14.dp)).border(1.dp, Ui.line.copy(alpha = 0.7f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(t.label.uppercase(), color = Ui.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = 0.6.sp)
            Text(t.value, color = t.color, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (t.sub != null) Text(t.sub, color = Ui.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Tag(text: String, color: Color = Ui.card2, fg: Color = Ui.text) =
    Text(text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp, maxLines = 1, modifier = Modifier.padding(end = 5.dp).background(color, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp))

private val TITLE_COLORS = mapOf("mlbb" to Color(0xFF3B6FD8), "wildrift" to Color(0xFF1FA3A3), "hok" to Color(0xFFC9932E), "lol" to Color(0xFF1E8FB8), "dota2" to Color(0xFFB03A2E), "valorant" to Color(0xFFE0485A), "cs2" to Color(0xFFD9822B))

@Composable
fun TitleTag(id: String) = Tag(Catalogue.get(id).short, TITLE_COLORS[id] ?: Ui.card2, Color.White)

/** A small select: shows the current choice; tap to pick another. */
@Composable
fun Select(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(end = 6.dp, bottom = 6.dp)) {
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Ui.card2).border(1.dp, Ui.line, RoundedCornerShape(10.dp)).clickable { open = true }.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (label.isNotEmpty()) Text("$label  ", color = Ui.muted, fontSize = 12.sp)
            Text(options.getOrElse(selected) { "—" }, color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.KeyboardArrowDown, null, tint = Ui.muted, modifier = Modifier.padding(start = 2.dp).size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Ui.card2) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal) }, onClick = { open = false; onSelect(i) }) }
        }
    }
}

/** Country flag emoji from a two-letter code (blank when unknown). */
fun flag(cc: String): String {
    val c = when (cc.uppercase()) { "UK" -> "GB"; else -> cc.uppercase() }
    if (c.length != 2 || !c.all { it in 'A'..'Z' }) return ""
    return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
}

class Series(val name: String, val color: Color, val points: List<Pair<Float, Float>>)

/** A simple line chart with grid lines and labelled axes. */
@Composable
fun LineChart(series: List<Series>, yMin: Float, yMax: Float, xLabel: (Float) -> String = { "%.0f".format(it) }, height: Dp = 180.dp) {
    val all = series.flatMap { it.points }
    if (all.isEmpty()) { Muted("No data yet."); return }
    val x0 = all.minOf { it.first }
    val x1 = all.maxOf { it.first }.let { if (it == x0) x0 + 1 else it }
    Column {
        Row {
            Column(Modifier.width(28.dp).height(height), verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                for (k in 4 downTo 0) Text("%.0f".format(yMin + (yMax - yMin) * k / 4), color = Ui.muted, fontSize = 9.sp)
            }
            Canvas(Modifier.weight(1f).height(height)) {
                val W = size.width
                val H = size.height
                for (k in 0..4) { val y = H * k / 4; drawLine(Ui.line, Offset(0f, y), Offset(W, y), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
                for (s in series) {
                    if (s.points.isEmpty()) continue
                    val path = Path()
                    s.points.forEachIndexed { i, (x, y) ->
                        val px = (x - x0) / (x1 - x0) * W
                        val py = H - (y - yMin) / (yMax - yMin) * H
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    drawPath(path, s.color, style = Stroke(4f))
                    for ((x, y) in s.points) drawCircle(s.color, 5f, Offset((x - x0) / (x1 - x0) * W, H - (y - yMin) / (yMax - yMin) * H))
                }
            }
        }
        Row(Modifier.padding(start = 28.dp)) {
            Text(xLabel(x0), color = Ui.muted, fontSize = 9.sp, modifier = Modifier.weight(1f))
            Text(xLabel(x1), color = Ui.muted, fontSize = 9.sp)
        }
        if (series.size > 1) Row { for (s in series) { Text("■ ", color = s.color, fontSize = 11.sp); Text(s.name + "   ", color = Ui.muted, fontSize = 11.sp) } }
    }
}

/** A sortable column: `key` gives the sort value (null sorts last), `cell` draws it. */
class SCol<T>(val title: String, val width: Dp, val alignEnd: Boolean = true, val key: ((T) -> Double?)? = null, val cell: @Composable (T) -> Unit)

/** A table whose column titles sort it (tap again to reverse); scrolls sideways when wide. */
@Composable
fun <T> SortTable(rows: List<T>, cols: List<SCol<T>>, initialSort: Int = -1, limit: Int = 400, onRow: ((T) -> Unit)? = null, highlight: (T) -> Boolean = { false }, initialDesc: Boolean = true) {
    var sort by remember { mutableStateOf(initialSort) }
    var desc by remember { mutableStateOf(initialDesc) }
    val sorted = remember(rows, sort, desc) {
        val k = cols.getOrNull(sort)?.key
        if (k == null) rows else {
            val withVal = rows.map { it to k(it) }
            val (has, none) = withVal.partition { it.second != null }
            (if (desc) has.sortedByDescending { it.second!! } else has.sortedBy { it.second!! }).map { it.first } + none.map { it.first }
        }
    }
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column {
            Row(Modifier.background(Ui.card2, RoundedCornerShape(8.dp)).padding(vertical = 6.dp)) {
                cols.forEachIndexed { i, c ->
                    Text(
                        c.title.uppercase() + if (i == sort) (if (desc) " ▾" else " ▴") else "", color = if (i == sort) Ui.accent else Ui.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1,
                        textAlign = if (c.alignEnd) TextAlign.End else TextAlign.Start,
                        modifier = Modifier.width(c.width).padding(horizontal = 4.dp).let { m -> if (c.key != null) m.clickable { if (sort == i) desc = !desc else { sort = i; desc = true } } else m },
                    )
                }
            }
            for (r in sorted.take(limit)) {
                Row(
                    Modifier.background(if (highlight(r)) Ui.rowHi else Color.Transparent, RoundedCornerShape(8.dp)).let { if (onRow != null) it.clickable { onRow(r) } else it }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (c in cols) Box(Modifier.width(c.width).padding(horizontal = 4.dp), contentAlignment = if (c.alignEnd) Alignment.CenterEnd else Alignment.CenterStart) { c.cell(r) }
                }
                Box(Modifier.height(0.5.dp).width(cols.fold(0.dp) { s, c -> s + c.width }).background(Ui.line.copy(alpha = 0.5f)))
            }
            if (sorted.size > limit) Muted("… and ${sorted.size - limit} more", 11)
        }
    }
}

/**
 * Who chose what before a MOBA game: in LoL / Dota 2 the chooser takes a side or the pick order
 * and the other team gets the other choice; elsewhere the chooser takes a side (blue picks first).
 */
internal fun selectionText(t: dev.uem.em.titles.TitleDef, names: List<String>, chooser: Int, choice: Int, byLoser: Boolean, blue: Int, firstPick: Int): String {
    val sides = if (t.id == "dota2") "Radiant" to "Dire" else "Blue" to "Red"
    val other = 1 - chooser
    val how = if (byLoser) "lost the previous game" else "won the coin toss"
    fun side(team: Int) = if (team == blue) sides.first else sides.second
    fun order(team: Int) = if (firstPick < 0) "the other choice" else if (team == firstPick) "first pick" else "second pick"
    return if (t.id in dev.uem.em.sim.LiveSeries.SPLIT_SELECTION) {
        if (choice == 0) "${names[chooser]} ($how) chose the ${side(chooser)} side; ${names[other]} then took ${order(other)}"
        else "${names[chooser]} ($how) chose ${order(chooser)}; ${names[other]} then took the ${side(other)} side"
    } else "${names[chooser]} ($how) chose the ${side(chooser)} side (${order(chooser)})"
}

/** Sort state and shared sideways scroll of a [lazySortTable]. */
class TableState(sort: Int, desc: Boolean) {
    var sort by mutableStateOf(sort)
    var desc by mutableStateOf(desc)
    val scroll = androidx.compose.foundation.ScrollState(0)
    internal var cacheKey: Any? = null
    internal var cache: List<Any?> = emptyList()
}

@Composable
fun rememberTableState(sort: Int = -1, desc: Boolean = true) = remember { TableState(sort, desc) }

/**
 * A sortable table inside a LazyColumn: only the rows on screen are composed (big lists stay
 * smooth); every row scrolls sideways together with the header.
 */
@Suppress("UNCHECKED_CAST")
fun <T> androidx.compose.foundation.lazy.LazyListScope.lazySortTable(state: TableState, rows: List<T>, cols: List<SCol<T>>, key: ((T) -> Any)? = null, onRow: ((T) -> Unit)? = null, highlight: (T) -> Boolean = { false }) {
    val ck = Triple(System.identityHashCode(rows), state.sort, state.desc)
    if (state.cacheKey != ck) {
        val k = cols.getOrNull(state.sort)?.key
        state.cache = if (k == null) rows else {
            val withVal = rows.map { it to k(it) }
            val (has, none) = withVal.partition { it.second != null }
            (if (state.desc) has.sortedByDescending { it.second!! } else has.sortedBy { it.second!! }).map { it.first } + none.map { it.first }
        }
        state.cacheKey = ck
    }
    val sorted = state.cache as List<T>
    item(key = "table-header") {
        Row(Modifier.fillMaxWidth().background(Ui.card2).horizontalScroll(state.scroll).padding(horizontal = 8.dp, vertical = 8.dp)) {
            cols.forEachIndexed { i, c ->
                Text(
                    c.title.uppercase() + if (i == state.sort) (if (state.desc) " ▾" else " ▴") else "", color = if (i == state.sort) Ui.accent else Ui.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1,
                    textAlign = if (c.alignEnd) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.width(c.width).padding(horizontal = 4.dp).let { m -> if (c.key != null) m.clickable { if (state.sort == i) state.desc = !state.desc else { state.sort = i; state.desc = true } } else m },
                )
            }
        }
    }
    if (key != null) items(sorted.size, key = { key(sorted[it]) }) { i -> TableRow(state, sorted[i], cols, onRow, highlight) }
    else items(sorted.size) { i -> TableRow(state, sorted[i], cols, onRow, highlight) }
}

@Composable
private fun <T> TableRow(state: TableState, r: T, cols: List<SCol<T>>, onRow: ((T) -> Unit)?, highlight: (T) -> Boolean) {
    Column {
        Row(
            Modifier.fillMaxWidth().background(if (highlight(r)) Ui.rowHi else Color.Transparent).let { if (onRow != null) it.clickable { onRow(r) } else it }.horizontalScroll(state.scroll).padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (c in cols) Box(Modifier.width(c.width).padding(horizontal = 4.dp), contentAlignment = if (c.alignEnd) Alignment.CenterEnd else Alignment.CenterStart) { c.cell(r) }
        }
        HorizontalDivider(color = Ui.line.copy(alpha = 0.45f), thickness = 0.5.dp)
    }
}

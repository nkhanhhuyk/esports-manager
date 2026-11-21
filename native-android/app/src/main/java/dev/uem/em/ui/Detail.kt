package dev.uem.em.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
    Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 230.dp))
}

@Composable
fun KV(label: String, faded: Boolean = false, value: @Composable () -> Unit) {
    Column(Modifier.alpha(if (faded) 0.45f else 1f)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Ui.muted, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            value()
        }
        HorizontalDivider(color = Ui.line, thickness = 0.5.dp)
    }
}

/** A bar 0..max. */
@Composable
fun Bar(v: Double, max: Double = 100.0, color: Color = Ui.accent, modifier: Modifier = Modifier, h: Dp = 6.dp) {
    Box(modifier.height(h).background(Ui.card2, RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0.01f, 1f)).height(h).background(color, RoundedCornerShape(3.dp)))
    }
}

class Tile(val label: String, val value: String, val sub: String? = null, val color: Color = Ui.text)

/** Headline numbers in tiles (the web version's stat strip). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Tiles(tiles: List<Tile>, perRow: Int = 3) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), maxItemsInEachRow = perRow) {
        for (t in tiles) Column(Modifier.weight(1f).padding(4.dp).background(Ui.card, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(t.label.uppercase(), color = Ui.muted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = 0.5.sp)
            Text(t.value, color = t.color, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.sub != null) Text(t.sub, color = Ui.muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Tag(text: String, color: Color = Ui.card2, fg: Color = Ui.text) =
    Text(text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 4.dp).background(color, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))

private val TITLE_COLORS = mapOf("mlbb" to Color(0xFF3B6FD8), "wildrift" to Color(0xFF1FA3A3), "hok" to Color(0xFFC9932E), "lol" to Color(0xFF1E8FB8), "dota2" to Color(0xFFB03A2E), "valorant" to Color(0xFFE0485A), "cs2" to Color(0xFFD9822B))

@Composable
fun TitleTag(id: String) = Tag(Catalogue.get(id).short, TITLE_COLORS[id] ?: Ui.card2, Color.White)

/** A small select: shows the current choice; tap to pick another. */
@Composable
fun Select(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(end = 6.dp, bottom = 6.dp)) {
        Row(Modifier.background(Ui.card2, RoundedCornerShape(8.dp)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (label.isNotEmpty()) Text("$label: ", color = Ui.muted, fontSize = 12.sp)
            Text(options.getOrElse(selected) { "—" }, color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(" ▾", color = Ui.muted, fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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
            Row(Modifier.padding(vertical = 4.dp)) {
                cols.forEachIndexed { i, c ->
                    Text(
                        c.title + if (i == sort) (if (desc) " ▾" else " ▴") else "", color = if (i == sort) Ui.accent else Ui.muted, fontSize = 11.sp, maxLines = 1,
                        textAlign = if (c.alignEnd) TextAlign.End else TextAlign.Start,
                        modifier = Modifier.width(c.width).padding(horizontal = 4.dp).let { m -> if (c.key != null) m.clickable { if (sort == i) desc = !desc else { sort = i; desc = true } } else m },
                    )
                }
            }
            for (r in sorted.take(limit)) {
                Row(
                    Modifier.background(if (highlight(r)) Ui.card2 else Color.Transparent).let { if (onRow != null) it.clickable { onRow(r) } else it }.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (c in cols) Box(Modifier.width(c.width).padding(horizontal = 4.dp), contentAlignment = if (c.alignEnd) Alignment.CenterEnd else Alignment.CenterStart) { c.cell(r) }
                }
            }
            if (sorted.size > limit) Muted("… and ${sorted.size - limit} more", 11)
        }
    }
}

package dev.uem.em.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.World
import dev.uem.em.titles.ItemDef
import dev.uem.em.titles.Items
import kotlin.math.roundToInt

/** An item's picture: the real icon, or generated art for items added by patches. */
@Composable
fun ItemPic(w: World?, id: String, size: Dp = 20.dp) {
    val it = Items.get(w, id)
    Pic(it?.img, it?.name ?: id, size, fallback = { GenItemArt(id, it?.type ?: "physical", size) })
}

/** Generated item icon: a glyph for its kind (blade, orb, shield, boot, charm, claw) in colours from its id. */
@Composable
fun GenItemArt(id: String, type: String, size: Dp) {
    val seed = id.hashCode()
    val c1 = GenArt.hue(seed, 0.55f, 0.55f)
    val c2 = GenArt.hue(seed * 7 + 3, 0.6f, 0.85f)
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        drawRect(Brush.linearGradient(listOf(c1, Color(0xFF141A2B)), Offset.Zero, Offset(s, s)))
        val m = s * 0.18f
        when (type) {
            "physical" -> { // a blade
                val p = Path().apply { moveTo(m, s - m); lineTo(s - m * 1.2f, m); lineTo(s - m, m * 1.4f); lineTo(m * 1.4f, s - m * 0.8f); close() }
                drawPath(p, c2); drawLine(Color.White.copy(alpha = 0.8f), Offset(m * 0.8f, s * 0.6f), Offset(s * 0.42f, s - m * 0.6f), strokeWidth = s * 0.07f)
            }
            "magical" -> { drawCircle(c2, s * 0.28f, Offset(s / 2, s / 2)); drawCircle(Color.White.copy(alpha = 0.6f), s * 0.1f, Offset(s * 0.42f, s * 0.42f)); drawCircle(c2.copy(alpha = 0.5f), s * 0.38f, Offset(s / 2, s / 2), style = Stroke(s * 0.04f)) }
            "defense" -> {
                val p = Path().apply { moveTo(s / 2, m); lineTo(s - m, s * 0.3f); lineTo(s - m * 1.3f, s * 0.68f); lineTo(s / 2, s - m * 0.7f); lineTo(m * 1.3f, s * 0.68f); lineTo(m, s * 0.3f); close() }
                drawPath(p, c2); drawLine(Color.White.copy(alpha = 0.5f), Offset(s / 2, m * 1.6f), Offset(s / 2, s - m * 1.4f), strokeWidth = s * 0.05f)
            }
            "movement" -> { drawRect(c2, Offset(s * 0.32f, m), Size(s * 0.22f, s * 0.5f)); drawRect(c2, Offset(s * 0.32f, s * 0.58f), Size(s * 0.46f, s * 0.2f)) }
            "jungle" -> for (k in 0..2) drawLine(c2, Offset(s * (0.3f + 0.17f * k), m), Offset(s * (0.22f + 0.17f * k), s - m), strokeWidth = s * 0.07f)
            else -> { // support: a charm
                drawCircle(c2, s * 0.16f, Offset(s * 0.38f, s * 0.42f)); drawCircle(c2, s * 0.16f, Offset(s * 0.62f, s * 0.42f))
                val p = Path().apply { moveTo(s * 0.24f, s * 0.48f); lineTo(s / 2, s * 0.8f); lineTo(s * 0.76f, s * 0.48f); close() }; drawPath(p, c2)
            }
        }
    }
}

/** A build: the items in order of purchase. */
@Composable
fun ItemRow(w: World?, ids: List<String>, size: Dp = 18.dp) {
    Row(verticalAlignment = Alignment.CenterVertically) { for (id in ids) { ItemPic(w, id, size); HGap(2) } }
}

private val TYPES = listOf("physical", "magical", "defense", "movement", "support", "jungle")
private val TYPE_LABEL = mapOf("physical" to "Physical", "magical" to "Magic", "defense" to "Defence", "movement" to "Boots", "support" to "Support", "jungle" to "Jungle")
private val STAT_SHORT = mapOf("ad" to "Phys", "ap" to "Magic", "hp" to "HP", "armor" to "Armour", "mr" to "MR", "as" to "AS%", "crit" to "Crit%", "haste" to "Haste", "pen" to "Pen",
    "ls" to "Steal%", "ms" to "MS", "mspct" to "MS%", "mana" to "Mana")

/** Every item of a game: icon, price, stats, its patch state (buffed / nerfed), new items marked. */
@Composable
fun ItemsCard(w: World, titleId: String) {
    val all = Items.of(w, titleId)
    if (all.isEmpty()) return
    var type by remember(titleId) { mutableIntStateOf(0) }
    val types = TYPES.filter { t -> all.any { it.type == t } }
    Card("Items (${all.size})") {
        Muted("The real items of the game; patches buff and nerf them and new ones are released. Heroes build them during the match.", 11)
        Tabs(types.map { TYPE_LABEL[it] ?: it }, type.coerceAtMost(types.size - 1)) { type = it }
        val shown = all.filter { it.type == types.getOrNull(type) }.sortedByDescending { it.cost }
        for (it in shown) ItemLine(w, it)
    }
}

@Composable
private fun ItemLine(w: World, it: ItemDef) {
    val m = Items.mod(w, it.id)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        ItemPic(w, it.id, 28.dp); HGap(8)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it.name, color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (it.custom) { HGap(4); Text("NEW", color = Ui.gold, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                if (m > 1.005) { HGap(4); Text("buffed +${((m - 1) * 100).roundToInt()}%", color = Ui.win, fontSize = 10.sp) }
                if (m < 0.995) { HGap(4); Text("nerfed ${((m - 1) * 100).roundToInt()}%", color = Ui.loss, fontSize = 10.sp) }
            }
            Muted(it.stats.entries.joinToString(" · ") { (k, v) -> "${STAT_SHORT[k] ?: k} ${(v * m).roundToInt()}" }.ifEmpty { "—" }, 10)
        }
        Text("${it.cost}", color = Ui.gold, fontSize = 12.sp, modifier = Modifier.width(46.dp))
    }
}

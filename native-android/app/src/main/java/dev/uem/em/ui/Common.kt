package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import dev.uem.em.model.World

object Ui {
    val bg = Color(0xFF0D111C)
    val card = Color(0xFF161C2C)
    val card2 = Color(0xFF1D2539)
    val line = Color(0xFF263049)
    val text = Color(0xFFE6EAF3)
    val muted = Color(0xFF8A94AD)
    val accent = Color(0xFF4F8CFF)
    val gold = Color(0xFFF4C14E)
    val win = Color(0xFF3DDC97)
    val loss = Color(0xFFFF6B6B)
    val blue = Color(0xFF4F8CFF)
    val red = Color(0xFFFF5A6E)
    val scheme = darkColorScheme(primary = accent, background = bg, surface = card, onSurface = text, onBackground = text, secondary = gold)
}

@Composable
fun Card(title: String? = null, modifier: Modifier = Modifier, right: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).background(Ui.card, RoundedCornerShape(12.dp)).padding(12.dp)) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(title, color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                right?.invoke()
            }
        }
        content()
    }
}

@Composable
fun Muted(text: String, size: Int = 12) = LinkedText(text, Ui.muted, size.sp)

@Composable
fun TeamChip(w: World, teamId: Int, vm: GameVm, size: Dp = 22.dp, bold: Boolean = false) {
    if (teamId < 0) { Muted("—"); return }
    val t = w.teams[teamId]
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.go(Screen.Team(teamId)) }) {
        TeamPic(t, size)
        Spacer(Modifier.width(6.dp))
        Text(t.name, color = if (w.isUserTeam(teamId)) Ui.gold else Ui.text, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlayerChip(p: Player, vm: GameVm, size: Dp = 26.dp) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { vm.go(Screen.Player(p.id)) }) {
        PlayerPic(p, size, round = true)
        Spacer(Modifier.width(6.dp))
        Text(p.handle, color = Ui.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A table that scrolls sideways when its columns are wider than the screen (no clipped text). */
class Col(val title: String, val width: Dp, val alignEnd: Boolean = true)

@Composable
fun Table(cols: List<Col>, rows: Int, onRow: ((Int) -> Unit)? = null, highlight: (Int) -> Boolean = { false }, cell: @Composable (row: Int, col: Int) -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Column {
            Row(Modifier.padding(vertical = 4.dp)) {
                for (c in cols) Text(c.title, color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(c.width).padding(horizontal = 4.dp), textAlign = if (c.alignEnd) TextAlign.End else TextAlign.Start, maxLines = 1)
            }
            for (r in 0 until rows) {
                Row(
                    Modifier.background(if (highlight(r)) Ui.card2 else Color.Transparent).let { if (onRow != null) it.clickable { onRow(r) } else it }.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    cols.forEachIndexed { ci, c -> Box(Modifier.width(c.width).padding(horizontal = 4.dp), contentAlignment = if (c.alignEnd) Alignment.CenterEnd else Alignment.CenterStart) { cell(r, ci) } }
                }
            }
        }
    }
}

@Composable
fun Num(v: String, color: Color = Ui.text, bold: Boolean = false) = Text(v, color = color, fontSize = 13.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1)

@Composable
fun Pill(text: String, color: Color = Ui.card2, fg: Color = Ui.text, onClick: (() -> Unit)? = null) {
    Text(text, color = fg, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp, bottom = 6.dp).background(color, RoundedCornerShape(50)).let { if (onClick != null) it.clickable { onClick() } else it }.padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
fun Tabs(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        items.forEachIndexed { i, s -> Pill(s, if (i == selected) Ui.accent else Ui.card2, if (i == selected) Color.White else Ui.text) { onSelect(i) } }
    }
}

package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.Player
import dev.uem.em.model.World

/**
 * Design tokens. Dark "broadcast" palette: a near-black navy canvas, two raised surface levels with
 * hairline borders, one blue accent (actions, selection), violet for highlights, gold for your club,
 * green / red for results. Every screen draws with these, so the whole app follows them.
 */
object Ui {
    val bg = Color(0xFF090D16)
    val card = Color(0xFF121827)
    val card2 = Color(0xFF1B2335)
    val card3 = Color(0xFF232D43)
    val line = Color(0xFF253049)
    val text = Color(0xFFE9EDF5)
    val muted = Color(0xFF8C96AD)
    val faint = Color(0xFF5E6880)
    val accent = Color(0xFF4F8CFF)
    val accent2 = Color(0xFF8B6CFF)
    val accentSoft = Color(0x294F8CFF)
    /** Highlighted table rows (your team, your players). */
    val rowHi = Color(0x174F8CFF)
    val gold = Color(0xFFF5C451)
    val goldSoft = Color(0x24F5C451)
    val win = Color(0xFF34D399)
    val loss = Color(0xFFFF6B6B)
    val blue = Color(0xFF4F8CFF)
    val red = Color(0xFFFF5A6E)

    val radius = 16.dp
    val shape = RoundedCornerShape(radius)
    val hero = Brush.linearGradient(listOf(Color(0xFF1C3B7A), Color(0xFF3A2A7A), Color(0xFF151C2E)))
    val heroGold = Brush.linearGradient(listOf(Color(0xFF5A4316), Color(0xFF2A2240), Color(0xFF151C2E)))

    val scheme = darkColorScheme(
        primary = accent, onPrimary = Color.White, primaryContainer = Color(0xFF1E3A70), onPrimaryContainer = Color(0xFFD6E3FF),
        secondary = gold, onSecondary = Color(0xFF231A00), tertiary = accent2,
        background = bg, onBackground = text, surface = card, onSurface = text, surfaceVariant = card2, onSurfaceVariant = muted,
        surfaceContainer = card, surfaceContainerHigh = card2, surfaceContainerHighest = card3, surfaceContainerLow = card, surfaceContainerLowest = bg,
        outline = line, outlineVariant = line, error = loss, onError = Color.White,
    )
    val typography = Typography(
        titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    )
    val shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(24.dp))
}

/** A content card: raised surface, hairline border, optional title with an accent mark and a right-hand action. */
@Composable
fun Card(title: String? = null, modifier: Modifier = Modifier, right: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clip(Ui.shape).background(Ui.card)
            .border(1.dp, Ui.line.copy(alpha = 0.7f), Ui.shape).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Box(Modifier.size(width = 3.dp, height = 15.dp).background(Ui.accent, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
                Text(title, color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                right?.invoke()
            }
        }
        content()
    }
}

/** A small uppercase section label between cards. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), color = Ui.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp))

/** "Open ›" style link used in card headers. */
@Composable
fun CardLink(text: String, onClick: () -> Unit) =
    Text("$text ›", color = Ui.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(horizontal = 6.dp, vertical = 4.dp))

/** A round icon badge (menus, quick actions). */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = Ui.accent, size: Dp = 36.dp) {
    Box(Modifier.size(size).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** A count bubble (unread messages, pending offers). */
@Composable
fun CountBubble(n: Int, color: Color = Ui.loss) {
    if (n <= 0) return
    Text(if (n > 99) "99+" else "$n", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp))
}

/** Results as coloured squares, oldest first (W green, L red). */
@Composable
fun FormStrip(results: List<Boolean>, size: Dp = 18.dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (won in results) Box(Modifier.size(size).background(if (won) Ui.win.copy(alpha = 0.22f) else Ui.loss.copy(alpha = 0.22f), RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            Text(if (won) "W" else "L", color = if (won) Ui.win else Ui.loss, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Nothing to show yet: a short sentence in the muted colour, centred. */
@Composable
fun EmptyNote(text: String) = Text(text, color = Ui.muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))

@Composable
fun Muted(text: String, size: Int = 12) = LinkedText(text, Ui.muted, size.sp)

@Composable
fun TeamChip(w: World, teamId: Int, vm: GameVm, size: Dp = 22.dp, bold: Boolean = false) {
    if (teamId < 0) { Muted("—"); return }
    val t = w.teams[teamId]
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { vm.go(Screen.Team(teamId)) }) {
        TeamPic(t, size)
        Spacer(Modifier.width(7.dp))
        Text(t.name, color = if (w.isUserTeam(teamId)) Ui.gold else Ui.text, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlayerChip(p: Player, vm: GameVm, size: Dp = 26.dp) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { vm.go(Screen.Player(p.id)) }) {
        PlayerPic(p, size, round = true)
        Spacer(Modifier.width(7.dp))
        Text(p.handle, color = Ui.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A table that scrolls sideways when its columns are wider than the screen (no clipped text). */
class Col(val title: String, val width: Dp, val alignEnd: Boolean = true)

@Composable
fun Table(cols: List<Col>, rows: Int, onRow: ((Int) -> Unit)? = null, highlight: (Int) -> Boolean = { false }, cell: @Composable (row: Int, col: Int) -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Column {
            Row(Modifier.background(Ui.card2, RoundedCornerShape(8.dp)).padding(vertical = 5.dp)) {
                for (c in cols) Text(c.title.uppercase(), color = Ui.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, modifier = Modifier.width(c.width).padding(horizontal = 4.dp), textAlign = if (c.alignEnd) TextAlign.End else TextAlign.Start, maxLines = 1)
            }
            for (r in 0 until rows) {
                Row(
                    Modifier.background(if (highlight(r)) Ui.rowHi else Color.Transparent, RoundedCornerShape(8.dp)).let { if (onRow != null) it.clickable { onRow(r) } else it }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    cols.forEachIndexed { ci, c -> Box(Modifier.width(c.width).padding(horizontal = 4.dp), contentAlignment = if (c.alignEnd) Alignment.CenterEnd else Alignment.CenterStart) { cell(r, ci) } }
                }
                if (r < rows - 1) Box(Modifier.height(0.5.dp).width(cols.fold(0.dp) { s, c -> s + c.width }).background(Ui.line.copy(alpha = 0.6f)))
            }
        }
    }
}

@Composable
fun Num(v: String, color: Color = Ui.text, bold: Boolean = false) = Text(v, color = color, fontSize = 13.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1)

/** A rounded chip: tappable when [onClick] is set. */
@Composable
fun Pill(text: String, color: Color = Ui.card2, fg: Color = Ui.text, onClick: (() -> Unit)? = null) {
    Text(
        text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(50)).background(color).let { if (onClick != null) it.clickable { onClick() } else it }.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Segmented choice (screen tabs, filters): one row of chips that scrolls sideways when long. */
@Composable
fun Tabs(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { i, s ->
            val on = i == selected
            Text(
                s, color = if (on) Color.White else Ui.muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Ui.accent else Ui.card).border(1.dp, if (on) Ui.accent else Ui.line, RoundedCornerShape(50))
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

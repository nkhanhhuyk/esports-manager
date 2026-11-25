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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** One colour set of the interface (dark or light). */
class Palette(
    val dark: Boolean, val bg: Color, val card: Color, val card2: Color, val card3: Color, val line: Color, val text: Color, val muted: Color, val faint: Color,
    val accent: Color, val accent2: Color, val gold: Color, val win: Color, val loss: Color, val red: Color,
)

/**
 * Design tokens. Dark "broadcast" palette (or a light one): a near-black navy canvas, two raised surface levels with
 * hairline borders, one blue accent (actions, selection), violet for highlights, gold for your club,
 * green / red for results. Every screen draws with these, so the whole app follows them.
 */
object Ui {
    val DARK = Palette(true, Color(0xFF090D16), Color(0xFF121827), Color(0xFF1B2335), Color(0xFF232D43), Color(0xFF253049), Color(0xFFE9EDF5), Color(0xFF8C96AD), Color(0xFF5E6880),
        Color(0xFF4F8CFF), Color(0xFF8B6CFF), Color(0xFFF5C451), Color(0xFF34D399), Color(0xFFFF6B6B), Color(0xFFFF5A6E))
    val LIGHT = Palette(false, Color(0xFFF2F4F9), Color(0xFFFFFFFF), Color(0xFFEEF1F7), Color(0xFFE2E7F0), Color(0xFFD7DDE8), Color(0xFF131A2A), Color(0xFF5A6478), Color(0xFF98A1B3),
        Color(0xFF2F6FEB), Color(0xFF6E52D9), Color(0xFFB07D12), Color(0xFF12985F), Color(0xFFD93F3F), Color(0xFFD9364F))

    /** The colours in use; screens read them while composing, so switching redraws the whole app. */
    var palette by mutableStateOf(DARK)

    val bg: Color get() = palette.bg
    val card: Color get() = palette.card
    val card2: Color get() = palette.card2
    val card3: Color get() = palette.card3
    val line: Color get() = palette.line
    val text: Color get() = palette.text
    val muted: Color get() = palette.muted
    val faint: Color get() = palette.faint
    val accent: Color get() = palette.accent
    val accent2: Color get() = palette.accent2
    val accentSoft: Color get() = palette.accent.copy(alpha = if (palette.dark) 0.16f else 0.12f)
    /** Highlighted table rows (your team, your players). */
    val rowHi: Color get() = palette.accent.copy(alpha = if (palette.dark) 0.09f else 0.07f)
    val gold: Color get() = palette.gold
    val goldSoft: Color get() = palette.gold.copy(alpha = 0.15f)
    val win: Color get() = palette.win
    val loss: Color get() = palette.loss
    val blue: Color get() = palette.accent
    val red: Color get() = palette.red

    val radius = 16.dp
    val shape = RoundedCornerShape(radius)
    /** Banner cards with white text (the same in both themes). */
    val hero = Brush.linearGradient(listOf(Color(0xFF1C3B7A), Color(0xFF3A2A7A), Color(0xFF151C2E)))
    val heroGold = Brush.linearGradient(listOf(Color(0xFF5A4316), Color(0xFF2A2240), Color(0xFF151C2E)))
    /** Page headers drawn with the theme's text colours. */
    val heroSoft: Brush get() = if (palette.dark) hero else Brush.linearGradient(listOf(Color(0xFFDCE6FF), Color(0xFFE8E0FF), Color(0xFFF7F8FC)))
    val heroGoldSoft: Brush get() = if (palette.dark) heroGold else Brush.linearGradient(listOf(Color(0xFFFFEBC2), Color(0xFFEDE6FF), Color(0xFFF7F8FC)))

    private val darkScheme = darkColorScheme(
        primary = DARK.accent, onPrimary = Color.White, primaryContainer = Color(0xFF1E3A70), onPrimaryContainer = Color(0xFFD6E3FF),
        secondary = DARK.gold, onSecondary = Color(0xFF231A00), tertiary = DARK.accent2,
        background = DARK.bg, onBackground = DARK.text, surface = DARK.card, onSurface = DARK.text, surfaceVariant = DARK.card2, onSurfaceVariant = DARK.muted,
        surfaceContainer = DARK.card, surfaceContainerHigh = DARK.card2, surfaceContainerHighest = DARK.card3, surfaceContainerLow = DARK.card, surfaceContainerLowest = DARK.bg,
        outline = DARK.line, outlineVariant = DARK.line, error = DARK.loss, onError = Color.White,
    )
    private val lightScheme = lightColorScheme(
        primary = LIGHT.accent, onPrimary = Color.White, primaryContainer = Color(0xFFD9E4FF), onPrimaryContainer = Color(0xFF0B2A66),
        secondary = LIGHT.gold, onSecondary = Color.White, tertiary = LIGHT.accent2,
        background = LIGHT.bg, onBackground = LIGHT.text, surface = LIGHT.card, onSurface = LIGHT.text, surfaceVariant = LIGHT.card2, onSurfaceVariant = LIGHT.muted,
        surfaceContainer = LIGHT.card, surfaceContainerHigh = LIGHT.card2, surfaceContainerHighest = LIGHT.card3, surfaceContainerLow = LIGHT.card, surfaceContainerLowest = LIGHT.bg,
        outline = LIGHT.line, outlineVariant = LIGHT.line, error = LIGHT.loss, onError = Color.White,
    )
    val scheme: androidx.compose.material3.ColorScheme get() = if (palette.dark) darkScheme else lightScheme
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

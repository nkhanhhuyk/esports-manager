package dev.uem.em.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp

/**
 * Generated portraits for fictional characters (champions, heroes, agents released by patches) and
 * generated weapon art for fictional guns. Drawn on the phone from a seed, so the same character or
 * weapon always looks the same; class / category decide the outfit, gear and silhouette.
 */
private enum class Arche { TANK, MAGE, MARKSMAN, ASSASSIN, SUPPORT, FIGHTER }

private fun archeOf(cls: String): Arche {
    val l = cls.lowercase()
    return when {
        Regex("tank|guardian|vanguard|sentinel|defender|front|str").containsMatchIn(l) -> Arche.TANK
        Regex("mage|controller|nuker|caster|int|artillery").containsMatchIn(l) -> Arche.MAGE
        Regex("marksman|sniper|hunter|sharpshooter|carry|duelist|archer|shooter").containsMatchIn(l) -> Arche.MARKSMAN
        Regex("assassin|agi|flank|speedster").containsMatchIn(l) -> Arche.ASSASSIN
        Regex("support|initiator|healer|enchanter|strategist").containsMatchIn(l) -> Arche.SUPPORT
        else -> Arche.FIGHTER
    }
}

private val SKINS = listOf(Color(0xFFF1C9A5), Color(0xFFE0AC84), Color(0xFFC68B62), Color(0xFF9C6644), Color(0xFF6E4630), Color(0xFFB8C7E0), Color(0xFF9FD1B8), Color(0xFFD9B6E8))
private val HAIRS = listOf(Color(0xFF1B1B1F), Color(0xFF3B2A20), Color(0xFFB0874F), Color(0xFFD8D2C4), Color(0xFF7B2E2E), Color(0xFF2E4A7B), Color(0xFFE8E8F0), Color(0xFF5B3C88))

/** A fictional character's portrait: bust with class outfit and gear, glowing eyes, aura. */
@Composable
fun GenChampion(seed: Int, size: Dp, cls: String) {
    val arche = archeOf(cls)
    val main = GenArt.hue(seed, 0.65f, 0.78f)
    val second = GenArt.hue(seed + 101, 0.45f, 0.92f)
    val glow = GenArt.hue(seed + 57, 0.8f, 1f)
    val skin = SKINS[GenArt.mix(seed, 11) % SKINS.size]
    val hair = HAIRS[GenArt.mix(seed, 12) % HAIRS.size]
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        // Backdrop: class-coloured radial light and a few sparks.
        drawRect(Brush.radialGradient(listOf(main.copy(alpha = 0.95f), Color(0xFF0E1220)), center = Offset(w * 0.5f, w * 0.38f), radius = w * 0.8f))
        for (k in 0 until 7) {
            val x = (GenArt.mix(seed, 20 + k) % 1000) / 1000f * w
            val y = (GenArt.mix(seed, 40 + k) % 1000) / 1000f * w * 0.7f
            drawCircle(glow.copy(alpha = 0.35f), w * (0.008f + (k % 3) * 0.006f), Offset(x, y))
        }
        // Gear behind the bust.
        behind(arche, w, second, glow, seed)
        // Shoulders / outfit.
        val torso = Path().apply {
            moveTo(w * 0.08f, w); quadraticTo(w * 0.12f, w * 0.7f, w * 0.36f, w * 0.66f)
            lineTo(w * 0.64f, w * 0.66f); quadraticTo(w * 0.88f, w * 0.7f, w * 0.92f, w); close()
        }
        drawPath(torso, Brush.verticalGradient(listOf(main, main.copy(red = main.red * 0.5f, green = main.green * 0.5f, blue = main.blue * 0.5f)), startY = w * 0.66f, endY = w))
        drawPath(Path().apply { moveTo(w * 0.42f, w * 0.66f); lineTo(w * 0.5f, w * 0.82f); lineTo(w * 0.58f, w * 0.66f); close() }, second.copy(alpha = 0.85f))
        if (arche == Arche.TANK || arche == Arche.FIGHTER) { // pauldrons
            drawOval(second, Offset(w * 0.06f, w * 0.64f), Size(w * 0.3f, w * 0.18f))
            drawOval(second, Offset(w * 0.64f, w * 0.64f), Size(w * 0.3f, w * 0.18f))
            drawOval(main.copy(alpha = 0.6f), Offset(w * 0.1f, w * 0.67f), Size(w * 0.22f, w * 0.1f))
            drawOval(main.copy(alpha = 0.6f), Offset(w * 0.68f, w * 0.67f), Size(w * 0.22f, w * 0.1f))
        }
        // Neck and head.
        drawRect(skin, Offset(w * 0.44f, w * 0.56f), Size(w * 0.12f, w * 0.12f))
        drawOval(skin, Offset(w * 0.33f, w * 0.26f), Size(w * 0.34f, w * 0.38f))
        // Hair (under headgear).
        if (arche != Arche.TANK) drawArc(hair, 180f, 180f, true, Offset(w * 0.31f, w * 0.22f), Size(w * 0.38f, w * 0.26f))
        if (GenArt.mix(seed, 13) % 3 == 0 && arche != Arche.TANK && arche != Arche.ASSASSIN) { // long hair
            drawRect(hair, Offset(w * 0.31f, w * 0.34f), Size(w * 0.06f, w * 0.28f)); drawRect(hair, Offset(w * 0.63f, w * 0.34f), Size(w * 0.06f, w * 0.28f))
        }
        // Eyes: a glow for every class.
        drawCircle(glow, w * 0.028f, Offset(w * 0.44f, w * 0.45f)); drawCircle(glow, w * 0.028f, Offset(w * 0.56f, w * 0.45f))
        drawCircle(Color.White.copy(alpha = 0.8f), w * 0.01f, Offset(w * 0.445f, w * 0.445f)); drawCircle(Color.White.copy(alpha = 0.8f), w * 0.01f, Offset(w * 0.565f, w * 0.445f))
        headgear(arche, w, main, second, glow, seed, skin)
        // Frame.
        drawRect(glow.copy(alpha = 0.5f), style = Stroke(w * 0.025f))
    }
}

private fun DrawScope.behind(a: Arche, w: Float, c: Color, glow: Color, seed: Int) {
    when (a) {
        Arche.FIGHTER -> rotate(-35f, Offset(w * 0.5f, w * 0.5f)) { // greatsword
            drawRoundRect(Color(0xFFCAD3E0), Offset(w * 0.46f, w * 0.02f), Size(w * 0.08f, w * 0.72f), CornerRadius(w * 0.02f))
            drawRect(c, Offset(w * 0.36f, w * 0.7f), Size(w * 0.28f, w * 0.05f))
        }
        Arche.ASSASSIN -> for (ang in listOf(-30f, 30f)) rotate(ang, Offset(w * 0.5f, w * 0.55f)) {
            drawPath(Path().apply { moveTo(w * 0.5f, w * 0.02f); lineTo(w * 0.54f, w * 0.4f); lineTo(w * 0.46f, w * 0.4f); close() }, Color(0xFFDDE3F0))
        }
        Arche.MARKSMAN -> drawArc(c, 120f, 120f, false, Offset(w * 0.06f, w * 0.1f), Size(w * 0.5f, w * 0.85f), style = Stroke(w * 0.035f))
        Arche.MAGE -> { drawCircle(glow.copy(alpha = 0.25f), w * 0.3f, Offset(w * 0.5f, w * 0.42f)); drawCircle(glow.copy(alpha = 0.5f), w * 0.3f, Offset(w * 0.5f, w * 0.42f), style = Stroke(w * 0.012f)) }
        Arche.SUPPORT -> { drawLine(Color(0xFFE8D7A8), Offset(w * 0.82f, w * 0.98f), Offset(w * 0.82f, w * 0.18f), strokeWidth = w * 0.03f); drawCircle(glow, w * 0.06f, Offset(w * 0.82f, w * 0.15f)) }
        Arche.TANK -> drawRoundRect(c.copy(alpha = 0.9f), Offset(w * 0.02f, w * 0.38f), Size(w * 0.26f, w * 0.46f), CornerRadius(w * 0.06f)) // shield
    }
}

private fun DrawScope.headgear(a: Arche, w: Float, main: Color, second: Color, glow: Color, seed: Int, skin: Color) {
    when (a) {
        Arche.TANK -> { // full helmet with visor slit and crest
            drawArc(second, 180f, 180f, true, Offset(w * 0.3f, w * 0.2f), Size(w * 0.4f, w * 0.34f))
            drawRect(second, Offset(w * 0.3f, w * 0.36f), Size(w * 0.4f, w * 0.14f))
            drawRect(Color(0xFF10131C), Offset(w * 0.36f, w * 0.42f), Size(w * 0.28f, w * 0.05f))
            drawCircle(glow, w * 0.022f, Offset(w * 0.44f, w * 0.445f)); drawCircle(glow, w * 0.022f, Offset(w * 0.56f, w * 0.445f))
            drawPath(Path().apply { moveTo(w * 0.47f, w * 0.2f); lineTo(w * 0.5f, w * 0.08f); lineTo(w * 0.53f, w * 0.2f); close() }, main)
        }
        Arche.MAGE -> { // pointed hood
            drawPath(Path().apply { moveTo(w * 0.27f, w * 0.52f); quadraticTo(w * 0.3f, w * 0.2f, w * 0.5f, w * 0.06f); quadraticTo(w * 0.7f, w * 0.2f, w * 0.73f, w * 0.52f); lineTo(w * 0.66f, w * 0.52f); quadraticTo(w * 0.62f, w * 0.3f, w * 0.5f, w * 0.27f); quadraticTo(w * 0.38f, w * 0.3f, w * 0.34f, w * 0.52f); close() }, main)
            drawCircle(glow, w * 0.03f, Offset(w * 0.5f, w * 0.31f))
        }
        Arche.MARKSMAN -> { // visor / cap
            drawRoundRect(second, Offset(w * 0.32f, w * 0.21f), Size(w * 0.36f, w * 0.1f), CornerRadius(w * 0.04f))
            drawRoundRect(glow.copy(alpha = 0.75f), Offset(w * 0.36f, w * 0.41f), Size(w * 0.28f, w * 0.07f), CornerRadius(w * 0.03f))
        }
        Arche.ASSASSIN -> { // hood and mask
            drawPath(Path().apply { moveTo(w * 0.28f, w * 0.56f); quadraticTo(w * 0.26f, w * 0.18f, w * 0.5f, w * 0.16f); quadraticTo(w * 0.74f, w * 0.18f, w * 0.72f, w * 0.56f); lineTo(w * 0.66f, w * 0.56f); quadraticTo(w * 0.66f, w * 0.3f, w * 0.5f, w * 0.29f); quadraticTo(w * 0.34f, w * 0.3f, w * 0.34f, w * 0.56f); close() }, Color(0xFF20232E))
            drawRect(Color(0xFF20232E), Offset(w * 0.35f, w * 0.49f), Size(w * 0.3f, w * 0.14f))
            drawLine(main, Offset(w * 0.35f, w * 0.5f), Offset(w * 0.65f, w * 0.5f), strokeWidth = w * 0.012f)
        }
        Arche.SUPPORT -> { // circlet with gem / halo
            if (GenArt.mix(seed, 14) % 2 == 0) drawOval(glow.copy(alpha = 0.8f), Offset(w * 0.32f, w * 0.1f), Size(w * 0.36f, w * 0.08f), style = Stroke(w * 0.02f))
            drawArc(second, 200f, 140f, false, Offset(w * 0.32f, w * 0.27f), Size(w * 0.36f, w * 0.12f), style = Stroke(w * 0.025f))
            drawCircle(glow, w * 0.025f, Offset(w * 0.5f, w * 0.28f))
        }
        Arche.FIGHTER -> { // horned helm or headband
            if (GenArt.mix(seed, 15) % 2 == 0) {
                drawArc(second, 180f, 180f, true, Offset(w * 0.31f, w * 0.21f), Size(w * 0.38f, w * 0.22f))
                drawPath(Path().apply { moveTo(w * 0.33f, w * 0.28f); quadraticTo(w * 0.2f, w * 0.2f, w * 0.22f, w * 0.06f); quadraticTo(w * 0.3f, w * 0.18f, w * 0.38f, w * 0.24f); close() }, Color(0xFFE8E0CC))
                drawPath(Path().apply { moveTo(w * 0.67f, w * 0.28f); quadraticTo(w * 0.8f, w * 0.2f, w * 0.78f, w * 0.06f); quadraticTo(w * 0.7f, w * 0.18f, w * 0.62f, w * 0.24f); close() }, Color(0xFFE8E0CC))
            } else drawRect(main, Offset(w * 0.32f, w * 0.31f), Size(w * 0.36f, w * 0.05f))
            drawLine(Color(0xFF7A3B32), Offset(w * 0.46f, w * 0.55f), Offset(w * 0.54f, w * 0.55f), strokeWidth = w * 0.015f)
        }
    }
}

/** A fictional weapon: silhouette by category, metal finish and a seeded accent / camo. */
@Composable
fun GenWeapon(seed: Int, size: Dp, category: String) {
    val accent = GenArt.hue(seed, 0.6f, 0.85f)
    val metal = listOf(Color(0xFF3A404C), Color(0xFF4B5160), Color(0xFF2B2F38), Color(0xFF5A4E3C))[GenArt.mix(seed, 3) % 4]
    val c = category.lowercase()
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color(0xFF12161F)), center = Offset(w / 2, w / 2), radius = w * 0.75f))
        val y = w * 0.42f
        fun body(x: Float, yy: Float, ww: Float, hh: Float, col: Color = metal) = drawRoundRect(col, Offset(w * x, w * yy), Size(w * ww, w * hh), CornerRadius(w * 0.015f))
        when {
            c.contains("sniper") -> {
                body(0.08f, 0.44f, 0.84f, 0.06f); body(0.05f, 0.42f, 0.22f, 0.12f) // barrel, stock
                body(0.36f, 0.33f, 0.28f, 0.07f, Color(0xFF1C1F26)); drawCircle(accent, w * 0.03f, Offset(w * 0.38f, w * 0.365f)) // scope
                body(0.48f, 0.5f, 0.05f, 0.14f); drawLine(metal, Offset(w * 0.78f, w * 0.5f), Offset(w * 0.72f, w * 0.68f), strokeWidth = w * 0.015f); drawLine(metal, Offset(w * 0.78f, w * 0.5f), Offset(w * 0.84f, w * 0.68f), strokeWidth = w * 0.015f)
            }
            c.contains("shotgun") -> {
                body(0.1f, 0.42f, 0.8f, 0.09f); body(0.05f, 0.42f, 0.2f, 0.14f); body(0.5f, 0.52f, 0.22f, 0.07f, accent.copy(alpha = 0.9f)) // pump
                body(0.32f, 0.5f, 0.06f, 0.16f)
            }
            c.contains("smg") -> {
                body(0.18f, 0.4f, 0.56f, 0.12f); body(0.74f, 0.44f, 0.14f, 0.04f); body(0.4f, 0.52f, 0.07f, 0.24f, Color(0xFF1C1F26)); body(0.26f, 0.52f, 0.06f, 0.14f)
                body(0.08f, 0.43f, 0.1f, 0.05f)
            }
            c.contains("heavy") || c.contains("machine") || c.contains("lmg") -> {
                body(0.1f, 0.4f, 0.76f, 0.11f); body(0.86f, 0.43f, 0.08f, 0.05f); body(0.38f, 0.51f, 0.2f, 0.16f, Color(0xFF1C1F26)) // box mag
                body(0.05f, 0.4f, 0.14f, 0.15f); for (k in 0..4) drawLine(Color(0xFF15181F), Offset(w * (0.6f + k * 0.04f), w * 0.4f), Offset(w * (0.6f + k * 0.04f), w * 0.51f), strokeWidth = w * 0.008f)
            }
            c.contains("pistol") || c.contains("sidearm") -> {
                body(0.25f, 0.38f, 0.5f, 0.1f); body(0.32f, 0.48f, 0.12f, 0.24f); drawRoundRect(accent, Offset(w * 0.25f, w * 0.38f), Size(w * 0.5f, w * 0.025f), CornerRadius(w * 0.01f))
            }
            c.contains("melee") || c.contains("knife") -> rotate(-35f, Offset(w / 2, w / 2)) {
                drawPath(Path().apply { moveTo(w * 0.5f, w * 0.08f); lineTo(w * 0.56f, w * 0.6f); lineTo(w * 0.44f, w * 0.6f); close() }, Color(0xFFCAD3E0)); body(0.46f, 0.6f, 0.08f, 0.26f, accent)
            }
            else -> { // rifle
                body(0.12f, 0.4f, 0.7f, 0.1f); body(0.82f, 0.43f, 0.12f, 0.04f); body(0.04f, 0.41f, 0.16f, 0.14f)
                drawPath(Path().apply { moveTo(w * 0.44f, w * 0.5f); lineTo(w * 0.52f, w * 0.5f); lineTo(w * 0.56f, w * 0.7f); lineTo(w * 0.48f, w * 0.72f); close() }, Color(0xFF1C1F26)) // curved mag
                body(0.3f, 0.5f, 0.06f, 0.14f); body(0.4f, 0.34f, 0.16f, 0.05f, Color(0xFF1C1F26))
            }
        }
        // Seeded finish: accent stripe or camo spots.
        if (GenArt.mix(seed, 5) % 2 == 0) drawLine(accent, Offset(w * 0.2f, y + w * 0.01f), Offset(w * 0.7f, y + w * 0.01f), strokeWidth = w * 0.012f)
        else for (k in 0 until 6) drawCircle(accent.copy(alpha = 0.35f), w * 0.02f, Offset(w * (0.2f + (GenArt.mix(seed, 60 + k) % 50) / 100f), y + w * 0.03f))
    }
}

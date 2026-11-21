package dev.uem.em.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/**
 * Generated artwork (drawn on the phone, nothing downloaded) for everything without a real
 * picture: fictional players get a portrait, fictional teams and organisations a crest, new
 * fictional champions / heroes / agents an emblem. The same seed always gives the same picture.
 */
object GenArt {
    private val SKIN = listOf(Color(0xFFF1C9A5), Color(0xFFE0AC84), Color(0xFFC68B62), Color(0xFF9C6644), Color(0xFF6E4630), Color(0xFFF6D7BD))
    private val HAIR = listOf(Color(0xFF1B1B1F), Color(0xFF3B2A20), Color(0xFF6B4A2E), Color(0xFFB0874F), Color(0xFFD8D2C4), Color(0xFF7B2E2E), Color(0xFF2E4A7B))
    private val BG = listOf(Color(0xFF2B3A67), Color(0xFF3D2B56), Color(0xFF1F4D4A), Color(0xFF5A2D2D), Color(0xFF2D4A2B), Color(0xFF4A3B1F), Color(0xFF233046))

    fun mix(seed: Int, k: Int): Int { var x = seed * 0x9E3779B1.toInt() + k * 0x85EBCA6B.toInt(); x = x xor (x ushr 15); x *= 0x2C1B3C6D; x = x xor (x ushr 12); return x and 0x7fffffff }
    fun hue(seed: Int, s: Float = 0.55f, v: Float = 0.75f) = Color.hsv((mix(seed, 1) % 360).toFloat(), s, v)

    fun parse(hex: String?): Color? = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
}

/** A generated player portrait: face, hair, eyes and a jersey in the team's colour. */
@Composable
fun GenFace(seed: Int, size: Dp, jersey: Color = GenArt.hue(seed)) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawRect(Brush.verticalGradient(listOf(GenArt.BG_of(seed), GenArt.BG_of(seed).copy(alpha = 0.6f))))
        val skin = GenArt.skin(seed)
        val hair = GenArt.hair(seed)
        // Shoulders / jersey.
        drawOval(jersey, Offset(w * 0.12f, w * 0.72f), Size(w * 0.76f, w * 0.5f))
        drawRect(jersey.copy(alpha = 0.6f), Offset(w * 0.45f, w * 0.74f), Size(w * 0.1f, w * 0.26f))
        // Neck and head.
        drawRect(skin, Offset(w * 0.42f, w * 0.58f), Size(w * 0.16f, w * 0.16f))
        drawOval(skin, Offset(w * 0.3f, w * 0.24f), Size(w * 0.4f, w * 0.44f))
        // Hair style.
        when (GenArt.mix(seed, 3) % 5) {
            0 -> drawArc(hair, 180f, 180f, true, Offset(w * 0.28f, w * 0.18f), Size(w * 0.44f, w * 0.3f))
            1 -> { drawArc(hair, 160f, 220f, true, Offset(w * 0.27f, w * 0.17f), Size(w * 0.46f, w * 0.36f)); drawRect(hair, Offset(w * 0.27f, w * 0.32f), Size(w * 0.06f, w * 0.18f)); drawRect(hair, Offset(w * 0.67f, w * 0.32f), Size(w * 0.06f, w * 0.18f)) }
            2 -> drawRect(hair, Offset(w * 0.32f, w * 0.22f), Size(w * 0.36f, w * 0.08f))
            3 -> { drawArc(hair, 180f, 180f, true, Offset(w * 0.29f, w * 0.2f), Size(w * 0.42f, w * 0.24f)); drawCircle(hair, w * 0.07f, Offset(w * 0.5f, w * 0.17f)) }
            else -> drawArc(hair, 200f, 140f, true, Offset(w * 0.3f, w * 0.21f), Size(w * 0.4f, w * 0.22f))
        }
        // Eyes, brows, mouth.
        val eye = Color(0xFF1E1E24)
        drawCircle(eye, w * 0.025f, Offset(w * 0.43f, w * 0.45f))
        drawCircle(eye, w * 0.025f, Offset(w * 0.57f, w * 0.45f))
        drawLine(GenArt.hair(seed).copy(alpha = 0.9f), Offset(w * 0.39f, w * 0.4f), Offset(w * 0.47f, w * 0.39f), strokeWidth = w * 0.02f)
        drawLine(GenArt.hair(seed).copy(alpha = 0.9f), Offset(w * 0.53f, w * 0.39f), Offset(w * 0.61f, w * 0.4f), strokeWidth = w * 0.02f)
        drawArc(Color(0xFF7A3B32), 20f, 140f, false, Offset(w * 0.44f, w * 0.5f), Size(w * 0.12f, w * 0.07f), style = Stroke(w * 0.018f))
        if (GenArt.mix(seed, 9) % 4 == 0) { // headset
            drawArc(Color(0xFF15151A), 180f, 180f, false, Offset(w * 0.26f, w * 0.2f), Size(w * 0.48f, w * 0.44f), style = Stroke(w * 0.035f))
            drawRoundRect(Color(0xFF15151A), Offset(w * 0.24f, w * 0.4f), Size(w * 0.07f, w * 0.12f), CornerRadius(w * 0.02f))
            drawRoundRect(Color(0xFF15151A), Offset(w * 0.69f, w * 0.4f), Size(w * 0.07f, w * 0.12f), CornerRadius(w * 0.02f))
        }
    }
}

/** A generated crest for a team or organisation: shape, pattern and colours from its seed. */
@Composable
fun GenCrest(seed: Int, size: Dp, label: String, c1: Color? = null, c2: Color? = null) {
    val a = c1 ?: GenArt.hue(seed, 0.6f, 0.7f)
    val b = c2 ?: GenArt.hue(seed + 7, 0.35f, 0.95f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val shape = when (GenArt.mix(seed, 2) % 4) {
                0 -> Path().apply { moveTo(w * 0.1f, w * 0.1f); lineTo(w * 0.9f, w * 0.1f); lineTo(w * 0.9f, w * 0.55f); quadraticTo(w * 0.9f, w * 0.85f, w * 0.5f, w * 0.97f); quadraticTo(w * 0.1f, w * 0.85f, w * 0.1f, w * 0.55f); close() }
                1 -> Path().apply { for (k in 0 until 6) { val ang = Math.toRadians(60.0 * k - 90); val x = w / 2 + w * 0.46f * Math.cos(ang).toFloat(); val y = w / 2 + w * 0.46f * Math.sin(ang).toFloat(); if (k == 0) moveTo(x, y) else lineTo(x, y) }; close() }
                2 -> Path().apply { addOval(androidx.compose.ui.geometry.Rect(w * 0.04f, w * 0.04f, w * 0.96f, w * 0.96f)) }
                else -> Path().apply { moveTo(w * 0.5f, w * 0.03f); lineTo(w * 0.97f, w * 0.5f); lineTo(w * 0.5f, w * 0.97f); lineTo(w * 0.03f, w * 0.5f); close() }
            }
            drawPath(shape, a)
            drawPath(shape, b, style = Stroke(w * 0.06f))
            clipPathPattern(shape) {
                when (GenArt.mix(seed, 4) % 4) {
                    0 -> for (k in -2..6) drawLine(b.copy(alpha = 0.35f), Offset(k * w * 0.18f, 0f), Offset(k * w * 0.18f + w * 0.5f, w), strokeWidth = w * 0.06f)
                    1 -> { val p = Path().apply { moveTo(w * 0.15f, w * 0.35f); lineTo(w * 0.5f, w * 0.6f); lineTo(w * 0.85f, w * 0.35f) }; drawPath(p, b.copy(alpha = 0.45f), style = Stroke(w * 0.1f)) }
                    2 -> drawCircle(b.copy(alpha = 0.3f), w * 0.28f, Offset(w / 2, w / 2), style = Stroke(w * 0.08f))
                    else -> drawRect(b.copy(alpha = 0.3f), Offset(0f, w * 0.38f), Size(w, w * 0.18f))
                }
            }
        }
        Text(label.filter { it.isLetterOrDigit() }.take(3).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = (size.value * 0.26f).sp)
    }
}

private fun DrawScope.clipPathPattern(p: Path, block: DrawScope.() -> Unit) { clipPath(p) { block() } }

/** A generated emblem for a character without official art (e.g. one added by a patch). */
@Composable
fun GenEmblem(seed: Int, size: Dp, cls: String, label: String) {
    val c = GenArt.hue(seed, 0.65f, 0.8f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            drawRect(Brush.radialGradient(listOf(c, Color(0xFF141824)), center = Offset(w / 2, w / 2), radius = w * 0.75f))
            val g = Color.White.copy(alpha = 0.85f)
            val l = cls.lowercase()
            when {
                l.contains("mage") || l.contains("controller") || l.contains("int") -> { drawLine(g, Offset(w * 0.3f, w * 0.85f), Offset(w * 0.62f, w * 0.25f), strokeWidth = w * 0.06f); drawCircle(g, w * 0.1f, Offset(w * 0.66f, w * 0.2f)) }
                l.contains("marksman") || l.contains("sniper") || l.contains("carry") || l.contains("duelist") -> { drawArc(g, 300f, 120f, false, Offset(w * 0.2f, w * 0.15f), Size(w * 0.5f, w * 0.7f), style = Stroke(w * 0.05f)); drawLine(g, Offset(w * 0.25f, w * 0.5f), Offset(w * 0.85f, w * 0.5f), strokeWidth = w * 0.04f) }
                l.contains("tank") || l.contains("sentinel") || l.contains("guardian") || l.contains("str") -> { val p = Path().apply { moveTo(w * 0.25f, w * 0.2f); lineTo(w * 0.75f, w * 0.2f); lineTo(w * 0.75f, w * 0.55f); lineTo(w * 0.5f, w * 0.82f); lineTo(w * 0.25f, w * 0.55f); close() }; drawPath(p, g, style = Stroke(w * 0.06f)) }
                l.contains("support") || l.contains("initiator") || l.contains("healer") -> { drawRect(g, Offset(w * 0.44f, w * 0.22f), Size(w * 0.12f, w * 0.56f)); drawRect(g, Offset(w * 0.22f, w * 0.44f), Size(w * 0.56f, w * 0.12f)) }
                l.contains("assassin") || l.contains("agi") -> { drawLine(g, Offset(w * 0.25f, w * 0.78f), Offset(w * 0.72f, w * 0.22f), strokeWidth = w * 0.05f); drawLine(g, Offset(w * 0.28f, w * 0.22f), Offset(w * 0.75f, w * 0.78f), strokeWidth = w * 0.05f) }
                else -> { drawLine(g, Offset(w * 0.5f, w * 0.15f), Offset(w * 0.5f, w * 0.75f), strokeWidth = w * 0.06f); drawLine(g, Offset(w * 0.3f, w * 0.62f), Offset(w * 0.7f, w * 0.62f), strokeWidth = w * 0.05f) }
            }
        }
        Text(label.take(1).uppercase(), color = Color.White.copy(alpha = 0.25f), fontWeight = FontWeight.Black, fontSize = (size.value * 0.5f).sp)
    }
}

private fun GenArt.BG_of(seed: Int) = listOf(Color(0xFF2B3A67), Color(0xFF3D2B56), Color(0xFF1F4D4A), Color(0xFF5A2D2D), Color(0xFF2D4A2B), Color(0xFF4A3B1F), Color(0xFF233046))[mix(seed, 5) % 7]
private fun GenArt.skin(seed: Int) = listOf(Color(0xFFF1C9A5), Color(0xFFE0AC84), Color(0xFFC68B62), Color(0xFF9C6644), Color(0xFF6E4630), Color(0xFFF6D7BD))[mix(seed, 6) % 6]
private fun GenArt.hair(seed: Int) = listOf(Color(0xFF1B1B1F), Color(0xFF3B2A20), Color(0xFF6B4A2E), Color(0xFFB0874F), Color(0xFFD8D2C4), Color(0xFF7B2E2E), Color(0xFF2E4A7B))[mix(seed, 7) % 7]

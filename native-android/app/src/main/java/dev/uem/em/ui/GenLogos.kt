package dev.uem.em.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.CompTier
import dev.uem.em.model.Competition

/**
 * Generated logos for teams and competitions without a real one (fictional teams, teams whose logo
 * could not be found, fictional and custom competitions). A team logo takes a motif from its name
 * (wolves, dragons, tigers, eagles, crowns, flames, bolts...) or a monogram, in the organisation's
 * colours; a competition logo follows its tier (trophy, globe, shield, banner).
 */
private enum class Motif { WOLF, DRAGON, CAT, BIRD, FOX, BEAR, SNAKE, CROWN, STAR, FLAME, BOLT, ICE, GHOST, SWORD, MOUNTAIN, WAVE, ROCKET, SKULL, NONE }

private fun motifOf(name: String): Motif {
    val n = name.lowercase()
    fun has(vararg w: String) = w.any { n.contains(it) }
    return when {
        has("wolf", "wolves", "lobo") -> Motif.WOLF
        has("dragon", "drake", "wyvern", "wyrm", "long ") -> Motif.DRAGON
        has("tiger", "lion", "panther", "jaguar", "leopard", "cat", "lynx", "puma") -> Motif.CAT
        has("eagle", "hawk", "falcon", "phoenix", "raven", "owl", "bird", "condor", "griffin", "wing") -> Motif.BIRD
        has("fox", "kitsune") -> Motif.FOX
        has("bear", "panda") -> Motif.BEAR
        has("snake", "viper", "cobra", "serpent", "python") -> Motif.SNAKE
        has("king", "queen", "crown", "royal", "regal", "prince", "monarch", "empire", "emperor") -> Motif.CROWN
        has("star", "nova", "galaxy", "cosmos", "astral", "stellar", "comet") -> Motif.STAR
        has("fire", "flame", "blaze", "inferno", "burn", "heat", "ember", "phoenix") -> Motif.FLAME
        has("thunder", "storm", "bolt", "volt", "lightning", "spark", "flash", "electric") -> Motif.BOLT
        has("ice", "frost", "snow", "glacier", "polar", "arctic") -> Motif.ICE
        has("ghost", "phantom", "shadow", "spirit", "shade", "soul", "wraith") -> Motif.GHOST
        has("sword", "blade", "knight", "samurai", "ninja", "warrior", "legion", "titan", "spartan") -> Motif.SWORD
        has("mountain", "rock", "stone", "titan", "giant", "peak") -> Motif.MOUNTAIN
        has("wave", "ocean", "sea", "tide", "shark", "whale", "aqua") -> Motif.WAVE
        has("rocket", "orbit", "space", "launch", "jet") -> Motif.ROCKET
        has("skull", "reaper", "death", "bone", "pirate") -> Motif.SKULL
        else -> Motif.NONE
    }
}

private fun DrawScope.motif(m: Motif, w: Float, c: Color) {
    val cx = w / 2; val cy = w / 2
    when (m) {
        Motif.WOLF -> { drawPath(Path().apply { moveTo(cx - w * 0.26f, cy - w * 0.22f); lineTo(cx - w * 0.14f, cy - w * 0.02f); lineTo(cx + w * 0.14f, cy - w * 0.02f); lineTo(cx + w * 0.26f, cy - w * 0.22f); lineTo(cx + w * 0.2f, cy + w * 0.12f); lineTo(cx, cy + w * 0.3f); lineTo(cx - w * 0.2f, cy + w * 0.12f); close() }, c)
            drawCircle(Color.Black.copy(alpha = 0.6f), w * 0.03f, Offset(cx - w * 0.08f, cy + w * 0.06f)); drawCircle(Color.Black.copy(alpha = 0.6f), w * 0.03f, Offset(cx + w * 0.08f, cy + w * 0.06f)) }
        Motif.DRAGON -> { drawPath(Path().apply { moveTo(cx - w * 0.3f, cy + w * 0.2f); quadraticTo(cx - w * 0.2f, cy - w * 0.3f, cx + w * 0.05f, cy - w * 0.28f); lineTo(cx - w * 0.02f, cy - w * 0.12f); quadraticTo(cx + w * 0.2f, cy - w * 0.2f, cx + w * 0.3f, cy - w * 0.05f); lineTo(cx + w * 0.08f, cy + w * 0.02f); quadraticTo(cx + w * 0.1f, cy + w * 0.22f, cx - w * 0.3f, cy + w * 0.2f); close() }, c)
            drawCircle(Color.Black.copy(alpha = 0.6f), w * 0.025f, Offset(cx + w * 0.12f, cy - w * 0.1f)) }
        Motif.CAT -> { drawCircle(c, w * 0.22f, Offset(cx, cy + w * 0.04f)); drawPath(Path().apply { moveTo(cx - w * 0.2f, cy - w * 0.08f); lineTo(cx - w * 0.16f, cy - w * 0.3f); lineTo(cx - w * 0.04f, cy - w * 0.16f); close(); moveTo(cx + w * 0.2f, cy - w * 0.08f); lineTo(cx + w * 0.16f, cy - w * 0.3f); lineTo(cx + w * 0.04f, cy - w * 0.16f); close() }, c)
            for (s in listOf(-1f, 1f)) { drawLine(Color.Black.copy(alpha = 0.5f), Offset(cx + s * w * 0.06f, cy + w * 0.1f), Offset(cx + s * w * 0.26f, cy + w * 0.06f), strokeWidth = w * 0.012f); drawLine(Color.Black.copy(alpha = 0.5f), Offset(cx + s * w * 0.06f, cy + w * 0.12f), Offset(cx + s * w * 0.26f, cy + w * 0.16f), strokeWidth = w * 0.012f) } }
        Motif.BIRD -> { drawPath(Path().apply { moveTo(cx, cy + w * 0.18f); quadraticTo(cx - w * 0.12f, cy - w * 0.05f, cx - w * 0.34f, cy - w * 0.16f); quadraticTo(cx - w * 0.16f, cy + w * 0.02f, cx - w * 0.06f, cy + w * 0.06f); lineTo(cx, cy - w * 0.12f); lineTo(cx + w * 0.06f, cy + w * 0.06f); quadraticTo(cx + w * 0.16f, cy + w * 0.02f, cx + w * 0.34f, cy - w * 0.16f); quadraticTo(cx + w * 0.12f, cy - w * 0.05f, cx, cy + w * 0.18f); close() }, c) }
        Motif.FOX -> { drawPath(Path().apply { moveTo(cx - w * 0.28f, cy - w * 0.24f); lineTo(cx - w * 0.08f, cy - w * 0.08f); lineTo(cx + w * 0.08f, cy - w * 0.08f); lineTo(cx + w * 0.28f, cy - w * 0.24f); lineTo(cx + w * 0.22f, cy + w * 0.02f); lineTo(cx, cy + w * 0.28f); lineTo(cx - w * 0.22f, cy + w * 0.02f); close() }, c)
            drawPath(Path().apply { moveTo(cx - w * 0.12f, cy + w * 0.06f); lineTo(cx, cy + w * 0.28f); lineTo(cx + w * 0.12f, cy + w * 0.06f); close() }, Color.White.copy(alpha = 0.7f)) }
        Motif.BEAR -> { drawCircle(c, w * 0.22f, Offset(cx, cy + w * 0.04f)); drawCircle(c, w * 0.08f, Offset(cx - w * 0.17f, cy - w * 0.16f)); drawCircle(c, w * 0.08f, Offset(cx + w * 0.17f, cy - w * 0.16f)); drawOval(Color.White.copy(alpha = 0.5f), Offset(cx - w * 0.08f, cy + w * 0.06f), Size(w * 0.16f, w * 0.11f)) }
        Motif.SNAKE -> drawPath(Path().apply { moveTo(cx - w * 0.2f, cy - w * 0.22f); quadraticTo(cx + w * 0.3f, cy - w * 0.2f, cx, cy); quadraticTo(cx - w * 0.3f, cy + w * 0.2f, cx + w * 0.2f, cy + w * 0.24f) }, c, style = Stroke(w * 0.08f))
        Motif.CROWN -> drawPath(Path().apply { moveTo(cx - w * 0.26f, cy + w * 0.16f); lineTo(cx - w * 0.28f, cy - w * 0.16f); lineTo(cx - w * 0.12f, cy - w * 0.02f); lineTo(cx, cy - w * 0.24f); lineTo(cx + w * 0.12f, cy - w * 0.02f); lineTo(cx + w * 0.28f, cy - w * 0.16f); lineTo(cx + w * 0.26f, cy + w * 0.16f); close() }, c)
        Motif.STAR -> drawPath(Path().apply { for (k in 0 until 10) { val r = if (k % 2 == 0) w * 0.3f else w * 0.13f; val a = Math.toRadians(-90.0 + k * 36); val x = cx + r * Math.cos(a).toFloat(); val y = cy + r * Math.sin(a).toFloat(); if (k == 0) moveTo(x, y) else lineTo(x, y) }; close() }, c)
        Motif.FLAME -> drawPath(Path().apply { moveTo(cx, cy - w * 0.32f); quadraticTo(cx + w * 0.26f, cy - w * 0.02f, cx + w * 0.14f, cy + w * 0.24f); quadraticTo(cx, cy + w * 0.32f, cx - w * 0.14f, cy + w * 0.24f); quadraticTo(cx - w * 0.24f, cy, cx - w * 0.06f, cy - w * 0.1f); quadraticTo(cx - w * 0.04f, cy + w * 0.04f, cx + w * 0.02f, cy + w * 0.06f); quadraticTo(cx + w * 0.06f, cy - w * 0.12f, cx, cy - w * 0.32f); close() }, c)
        Motif.BOLT -> drawPath(Path().apply { moveTo(cx + w * 0.06f, cy - w * 0.32f); lineTo(cx - w * 0.18f, cy + w * 0.04f); lineTo(cx - w * 0.01f, cy + w * 0.04f); lineTo(cx - w * 0.08f, cy + w * 0.32f); lineTo(cx + w * 0.18f, cy - w * 0.06f); lineTo(cx + w * 0.01f, cy - w * 0.06f); close() }, c)
        Motif.ICE -> for (k in 0 until 3) rotate(k * 60f, Offset(cx, cy)) { drawLine(c, Offset(cx, cy - w * 0.3f), Offset(cx, cy + w * 0.3f), strokeWidth = w * 0.05f); drawLine(c, Offset(cx - w * 0.08f, cy - w * 0.22f), Offset(cx, cy - w * 0.14f), strokeWidth = w * 0.035f); drawLine(c, Offset(cx + w * 0.08f, cy - w * 0.22f), Offset(cx, cy - w * 0.14f), strokeWidth = w * 0.035f) }
        Motif.GHOST -> { drawPath(Path().apply { moveTo(cx - w * 0.2f, cy + w * 0.26f); lineTo(cx - w * 0.2f, cy - w * 0.04f); quadraticTo(cx - w * 0.2f, cy - w * 0.28f, cx, cy - w * 0.28f); quadraticTo(cx + w * 0.2f, cy - w * 0.28f, cx + w * 0.2f, cy - w * 0.04f); lineTo(cx + w * 0.2f, cy + w * 0.26f); lineTo(cx + w * 0.1f, cy + w * 0.18f); lineTo(cx, cy + w * 0.26f); lineTo(cx - w * 0.1f, cy + w * 0.18f); close() }, c)
            drawCircle(Color.Black.copy(alpha = 0.7f), w * 0.035f, Offset(cx - w * 0.07f, cy - w * 0.06f)); drawCircle(Color.Black.copy(alpha = 0.7f), w * 0.035f, Offset(cx + w * 0.07f, cy - w * 0.06f)) }
        Motif.SWORD -> rotate(45f, Offset(cx, cy)) { drawRect(c, Offset(cx - w * 0.03f, cy - w * 0.34f), Size(w * 0.06f, w * 0.48f)); drawRect(c, Offset(cx - w * 0.14f, cy + w * 0.12f), Size(w * 0.28f, w * 0.05f)); drawRect(c, Offset(cx - w * 0.025f, cy + w * 0.17f), Size(w * 0.05f, w * 0.14f)) }
        Motif.MOUNTAIN -> drawPath(Path().apply { moveTo(cx - w * 0.32f, cy + w * 0.22f); lineTo(cx - w * 0.1f, cy - w * 0.16f); lineTo(cx, cy - w * 0.02f); lineTo(cx + w * 0.12f, cy - w * 0.26f); lineTo(cx + w * 0.32f, cy + w * 0.22f); close() }, c)
        Motif.WAVE -> for (k in 0..2) drawPath(Path().apply { val y = cy - w * 0.14f + k * w * 0.14f; moveTo(cx - w * 0.3f, y); quadraticTo(cx - w * 0.15f, y - w * 0.1f, cx, y); quadraticTo(cx + w * 0.15f, y + w * 0.1f, cx + w * 0.3f, y) }, c, style = Stroke(w * 0.05f))
        Motif.ROCKET -> rotate(35f, Offset(cx, cy)) { drawPath(Path().apply { moveTo(cx, cy - w * 0.32f); quadraticTo(cx + w * 0.12f, cy - w * 0.1f, cx + w * 0.09f, cy + w * 0.16f); lineTo(cx - w * 0.09f, cy + w * 0.16f); quadraticTo(cx - w * 0.12f, cy - w * 0.1f, cx, cy - w * 0.32f); close() }, c); drawCircle(Color.Black.copy(alpha = 0.5f), w * 0.04f, Offset(cx, cy - w * 0.08f)) }
        Motif.SKULL -> { drawCircle(c, w * 0.2f, Offset(cx, cy - w * 0.04f)); drawRect(c, Offset(cx - w * 0.12f, cy + w * 0.08f), Size(w * 0.24f, w * 0.14f)); drawCircle(Color.Black.copy(alpha = 0.7f), w * 0.055f, Offset(cx - w * 0.08f, cy - w * 0.04f)); drawCircle(Color.Black.copy(alpha = 0.7f), w * 0.055f, Offset(cx + w * 0.08f, cy - w * 0.04f)) }
        Motif.NONE -> {}
    }
}

/** A generated team / organisation logo (motif from the name, or a monogram) in its colours. */
@Composable
fun GenTeamLogo(seed: Int, size: Dp, name: String, short: String, c1: Color?, c2: Color?) {
    val m = motifOf(name)
    val a = c1 ?: GenArt.hue(seed, 0.6f, 0.7f)
    val b = c2 ?: GenArt.hue(seed + 7, 0.35f, 0.95f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val shape = when (GenArt.mix(seed, 2) % 4) {
                0 -> Path().apply { moveTo(w * 0.08f, w * 0.08f); lineTo(w * 0.92f, w * 0.08f); lineTo(w * 0.92f, w * 0.55f); quadraticTo(w * 0.92f, w * 0.86f, w * 0.5f, w * 0.98f); quadraticTo(w * 0.08f, w * 0.86f, w * 0.08f, w * 0.55f); close() }
                1 -> Path().apply { for (k in 0 until 6) { val ang = Math.toRadians(60.0 * k - 90); val x = w / 2 + w * 0.47f * Math.cos(ang).toFloat(); val y = w / 2 + w * 0.47f * Math.sin(ang).toFloat(); if (k == 0) moveTo(x, y) else lineTo(x, y) }; close() }
                2 -> Path().apply { addOval(androidx.compose.ui.geometry.Rect(w * 0.03f, w * 0.03f, w * 0.97f, w * 0.97f)) }
                else -> Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(w * 0.06f, w * 0.06f, w * 0.94f, w * 0.94f, androidx.compose.ui.geometry.CornerRadius(w * 0.22f))) }
            }
            drawPath(shape, Brush.linearGradient(listOf(a, a.copy(red = a.red * 0.6f, green = a.green * 0.6f, blue = a.blue * 0.6f)), Offset(0f, 0f), Offset(w, w)))
            clipPath(shape) { if (GenArt.mix(seed, 4) % 2 == 0) for (k in -2..6) drawLine(b.copy(alpha = 0.12f), Offset(k * w * 0.18f, 0f), Offset(k * w * 0.18f + w * 0.5f, w), strokeWidth = w * 0.05f) }
            drawPath(shape, b, style = Stroke(w * 0.055f))
            motif(m, w, b)
        }
        if (m == Motif.NONE) Text(short.filter { it.isLetterOrDigit() }.take(3).uppercase(), color = b, fontWeight = FontWeight.Black, fontSize = (size.value * (if (short.length <= 2) 0.36f else 0.27f)).sp)
    }
}

/** A generated competition logo by tier: trophy (world championship), globe (international), shield (league), banner (other). */
@Composable
fun GenCompLogo(c: Competition, size: Dp) {
    val seed = c.short.hashCode() * 31 + c.titleId.hashCode()
    val gold = Color(0xFFF4C14E)
    val main = when (c.tier) { CompTier.WORLDS -> gold; CompTier.INTERNATIONAL -> Color(0xFF6FC3FF); else -> GenArt.hue(seed, 0.55f, 0.8f) }
    val ewc = c.name.contains("Esports World Cup")
    val enc = c.name.contains("Nations Cup")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width; val cx = w / 2; val cy = w / 2
            drawRect(Brush.radialGradient(listOf(main.copy(alpha = 0.35f), Color(0xFF121726)), Offset(cx, cy), w * 0.75f))
            when {
                ewc -> { drawCircle(Color(0xFF7CF0B0), w * 0.36f, Offset(cx, cy), style = Stroke(w * 0.05f)); for (k in 0 until 8) rotate(k * 45f, Offset(cx, cy)) { drawLine(Color(0xFF7CF0B0), Offset(cx, cy - w * 0.36f), Offset(cx, cy - w * 0.24f), strokeWidth = w * 0.04f) }; drawCircle(Color(0xFF7CF0B0), w * 0.12f, Offset(cx, cy)) }
                enc -> { for (k in 0 until 3) drawRect(listOf(Color(0xFFE84855), Color.White, Color(0xFF2E86DE))[k], Offset(w * 0.18f, w * (0.24f + k * 0.17f)), Size(w * 0.64f, w * 0.17f)); drawRect(gold, Offset(w * 0.18f, w * 0.24f), Size(w * 0.64f, w * 0.51f), style = Stroke(w * 0.035f)) }
                c.tier == CompTier.WORLDS -> { // trophy cup
                    drawPath(Path().apply { moveTo(cx - w * 0.22f, cy - w * 0.28f); lineTo(cx + w * 0.22f, cy - w * 0.28f); quadraticTo(cx + w * 0.22f, cy + w * 0.08f, cx, cy + w * 0.1f); quadraticTo(cx - w * 0.22f, cy + w * 0.08f, cx - w * 0.22f, cy - w * 0.28f); close() }, gold)
                    drawArc(gold, 90f, 180f, false, Offset(cx - w * 0.34f, cy - w * 0.24f), Size(w * 0.2f, w * 0.2f), style = Stroke(w * 0.04f))
                    drawArc(gold, -90f, 180f, false, Offset(cx + w * 0.14f, cy - w * 0.24f), Size(w * 0.2f, w * 0.2f), style = Stroke(w * 0.04f))
                    drawRect(gold, Offset(cx - w * 0.04f, cy + w * 0.08f), Size(w * 0.08f, w * 0.14f)); drawRect(gold, Offset(cx - w * 0.16f, cy + w * 0.22f), Size(w * 0.32f, w * 0.07f))
                }
                c.tier == CompTier.INTERNATIONAL -> { drawCircle(main, w * 0.3f, Offset(cx, cy), style = Stroke(w * 0.045f)); drawOval(main, Offset(cx - w * 0.14f, cy - w * 0.3f), Size(w * 0.28f, w * 0.6f), style = Stroke(w * 0.035f)); drawLine(main, Offset(cx - w * 0.3f, cy), Offset(cx + w * 0.3f, cy), strokeWidth = w * 0.035f)
                    for (k in 0 until 5) { val a = Math.toRadians(-90.0 + k * 72); drawCircle(gold, w * 0.035f, Offset(cx + w * 0.42f * Math.cos(a).toFloat(), cy + w * 0.42f * Math.sin(a).toFloat())) } }
                c.tier == CompTier.CUSTOM -> drawPath(Path().apply { moveTo(w * 0.14f, w * 0.26f); lineTo(w * 0.86f, w * 0.26f); lineTo(w * 0.86f, w * 0.7f); lineTo(cx, w * 0.6f); lineTo(w * 0.14f, w * 0.7f); close() }, main)
                else -> { drawPath(Path().apply { moveTo(w * 0.18f, w * 0.14f); lineTo(w * 0.82f, w * 0.14f); lineTo(w * 0.82f, w * 0.55f); quadraticTo(w * 0.82f, w * 0.84f, cx, w * 0.94f); quadraticTo(w * 0.18f, w * 0.84f, w * 0.18f, w * 0.55f); close() }, main)
                    for (k in 0 until 3) drawCircle(Color.White.copy(alpha = 0.8f), w * 0.03f, Offset(cx + (k - 1) * w * 0.12f, w * 0.24f)) }
            }
        }
        if (c.tier == CompTier.LEAGUE || c.tier == CompTier.CHALLENGER || c.tier == CompTier.CUSTOM) Text(c.short.filter { it.isLetterOrDigit() }.take(4).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = (size.value * 0.2f).sp)
    }
}

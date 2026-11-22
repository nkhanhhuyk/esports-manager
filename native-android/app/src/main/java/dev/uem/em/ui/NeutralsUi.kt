package dev.uem.em.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.World
import dev.uem.em.titles.Neutrals
import dev.uem.em.titles.TitleDef

/** The game's neutral objectives: when they spawn, every kind and what slaying it gives (with patch changes). */
@Composable
fun NeutralsCard(w: World, t: TitleDef) {
    val cfg = t.moba ?: return
    val kinds = Neutrals.of(w, t.id)
    if (kinds.isEmpty()) return
    Card("Neutral objectives (${t.short})") {
        Muted("What each objective gives the team that slays it; patches change these and can add new kinds.", 11)
        for (o in cfg.objectives) {
            val mine = kinds.filter { it.objId == o.id }
            if (mine.isEmpty()) continue
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(o.name, color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Muted("Spawns at ${fmtMin(o.spawn)}, respawns every ${fmtMin(o.respawn)}${o.until?.let { " until ${fmtMin(it)}" } ?: ""}${if (mine.size > 1 && o.id == "drake") " · first two drakes of different elements, the third's element stays for the rest of the game" else ""}", 10)
                for (k in mine) {
                    val mod = Neutrals.mod(w, k.id)
                    Row(Modifier.padding(top = 3.dp)) {
                        Text(k.name + (if (k.custom) "  NEW" else "") + (if (k.killerOnly) "  (last hit)" else "") + (if (k.fromKill > 1) "  (from kill ${k.fromKill})" else ""),
                            color = if (k.custom) Ui.gold else Ui.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(Neutrals.statText(k.stats, mod) + (if (k.duration > 0) " for ${k.duration} s" else if (k.stacking) " per stack, rest of the game" else "") +
                        (if (k.endsOnDeath) " (lost on death)" else ""), color = Ui.text, fontSize = 11.sp)
                    if (mod != 1.0) Muted("Patched: ${if (mod > 1) "+" else ""}${((mod - 1) * 100).toInt()}% since release", 10)
                    if (k.soulAt > 0 && k.soul.isNotEmpty()) Muted("${k.soulName} (${k.soulAt} drakes): ${Neutrals.statText(k.soul, mod)}", 10)
                    if (k.text.isNotBlank()) Muted(k.text + (if (k.approx) " (values approximate)" else ""), 10)
                }
            }
        }
    }
}

private fun fmtMin(m: Double): String { val s = (m * 60).toInt(); return "%d:%02d".format(s / 60, s % 60) }

package dev.uem.em.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.Dates
import dev.uem.em.mgmt.Development
import dev.uem.em.mgmt.Economy
import dev.uem.em.mgmt.Scrims
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Patch
import dev.uem.em.titles.Catalogue

/**
 * Training and scrims of any team (your own, or any team as a spectator): its practice settings and
 * condition, recent scrim blocks with results (and the picks, for your and followed teams), what has
 * worked for it on this patch, and what its scrim partners like to pick.
 */
@Composable
fun TeamScrims(vm: GameVm, w: World, team: Team, showTraining: Boolean = true) {
    val t = Catalogue.get(team.titleId)
    val roster = team.roster.map { w.players[it] }
    if (showTraining) Card("Training") {
        KV("Focus", Development.FOCUS_LABEL[team.focus] ?: team.focus)
        KV("Intensity", "${team.intensity}")
        KV("Scrims per week", "${team.scrims} block${if (team.scrims == 1) "" else "s"}")
        KV("Solo queue practice", listOf("Light", "Normal", "Heavy")[team.soloFocus.coerceIn(0, 2)])
        KV("Coaching quality", "%.0f".format(Economy.coachQuality(w, team)))
        if (roster.isNotEmpty()) {
            KV("Average fatigue", "%.0f".format(roster.map { it.fatigue }.average()), if (roster.map { it.fatigue }.average() > 50) Ui.loss else Ui.text)
            KV("Chemistry") { Rating(team.chemistry) }
            KV("Meta adaptation") { Rating(team.metaAdapt) }
        }
    }
    val log = team.scrimLog.asReversed()
    Card("Scrim results", right = { if (log.isNotEmpty()) Muted("${log.sumOf { it.won }}–${log.sumOf { it.lost }} in games", 11) }) {
        if (log.isEmpty()) { Muted("No scrims yet: blocks are played every week (a few after the season starts).", 11); return@Card }
        Muted("Practice blocks against partners from the same servers and of a similar level (${if (t.isMoba) "three games" else "two maps"} a block). Picks are kept for your and followed teams.", 10)
        for (b in log) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Dates.format(b.day), color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(78.dp))
                    Text("${b.won}–${b.lost}", color = if (b.won > b.lost) Ui.win else if (b.won < b.lost) Ui.loss else Ui.text, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(40.dp))
                    Text("vs ", color = Ui.muted, fontSize = 12.sp)
                    TeamChip(w, b.opp, vm, 16.dp)
                }
                val games = maxOf(b.picks.size, b.maps.size)
                for (g in 0 until games) Row(Modifier.padding(start = 78.dp, top = 2.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    val won = b.results.getOrNull(g) == 'W'
                    Text(if (won) "W" else "L", color = if (won) Ui.win else Ui.loss, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
                    b.maps.getOrNull(g)?.let { m -> Text(t.maps.firstOrNull { it.id == m }?.name ?: m, color = Ui.muted, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp)) }
                    for (c in b.picks.getOrNull(g)?.split(',').orEmpty()) if (c.isNotBlank()) { CharPic(c, t.charIndex[c]?.name ?: c, 18.dp); HGap(1) }
                    if (b.oppPicks.getOrNull(g)?.isNotBlank() == true) { Text(" vs ", color = Ui.muted, fontSize = 9.sp); for (c in b.oppPicks[g].split(',')) if (c.isNotBlank()) { CharPic(c, t.charIndex[c]?.name ?: c, 18.dp); HGap(1) } }
                }
            }
        }
    }
    val patch = Patch.of(w, t)
    val current = team.scrimPatch == patch.n
    val rows = team.scrimStats.entries.filter { current && it.value.g > 0 }
    val chars = rows.filter { !it.key.startsWith("map:") }.sortedWith(compareByDescending<Map.Entry<String, dev.uem.em.model.ScrimStat>> { Scrims.bonusOf(it.value) }.thenByDescending { it.value.g })
    val maps = rows.filter { it.key.startsWith("map:") }.sortedByDescending { Scrims.bonusOf(it.value) }
    Card("What works on this patch (scrims)") {
        if (chars.isEmpty() && maps.isEmpty()) { Muted("Nothing tried on this patch yet.", 11); return@Card }
        Muted("The team's scrim record by ${if (t.characters.isEmpty()) "map" else if (t.id == "valorant") "agent and map" else "champion / hero"}: picks that win in scrims move up its draft priorities, the ones that fail move down. A new patch halves the old record.", 10)
        for ((k, st) in chars.take(14)) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            CharPic(k, t.charIndex[k]?.name ?: k, 22.dp); HGap(6)
            Text(t.charIndex[k]?.name ?: k, color = Ui.text, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${st.w}–${st.g - st.w}", color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(44.dp))
            val b = Scrims.bonusOf(st)
            Text(if (b > 0.08) "works" else if (b < -0.08) "struggles" else "testing", color = if (b > 0.08) Ui.win else if (b < -0.08) Ui.loss else Ui.muted, fontSize = 11.sp, modifier = Modifier.width(62.dp))
        }
        for ((k, st) in maps) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val id = k.removePrefix("map:")
            Text(t.maps.firstOrNull { it.id == id }?.name ?: id, color = Ui.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${st.w}–${st.g - st.w}", color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(44.dp))
            val b = Scrims.bonusOf(st)
            Text(if (b > 0.08) "strong" else if (b < -0.08) "weak" else "even", color = if (b > 0.08) Ui.win else if (b < -0.08) Ui.loss else Ui.muted, fontSize = 11.sp, modifier = Modifier.width(62.dp))
        }
    }
    val seen = Scrims.seen(w, team)
    if (seen.isNotEmpty()) Card("Seen from scrim partners") {
        Muted("What the teams this one scrimmed liked to pick against it (useful before you face them).", 10)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
            for ((c, n) in seen.take(16)) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
                CharPic(c, t.charIndex[c]?.name ?: c, 30.dp)
                Text("×$n", color = Ui.muted, fontSize = 10.sp)
            }
        }
    }
}

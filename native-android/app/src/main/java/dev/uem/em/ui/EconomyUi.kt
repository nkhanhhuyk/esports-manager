package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.Fixture
import dev.uem.em.model.GameRecord
import dev.uem.em.model.World
import dev.uem.em.sim.ShooterEconomy
import dev.uem.em.sim.Weapons
import dev.uem.em.titles.TitleDef

private val BUY_LABEL = mapOf("pistol" to "Pistol", "full" to "Full buy", "force" to "Force", "eco" to "Eco")

/** Symbols for how a round ended (elimination, detonation, defuse, time). */
object RoundIcons {
    val ICON = listOf("☠", "💥", "🔧", "⏱")
    fun icon(how: Int) = ICON.getOrElse(how) { "•" }
    fun label(cs: Boolean, how: Int) = when (how) { 0 -> "Elimination"; 1 -> if (cs) "Bomb exploded" else "Spike detonated"; 2 -> if (cs) "Bomb defused" else "Spike defused"; 3 -> "Time ran out"; else -> "" }
    fun legend(cs: Boolean) = (0..3).joinToString("   ") { "${icon(it)} ${label(cs, it)}" }
}

/** A Valorant player's ability charges and ultimate this round, e.g. "Cloudburst ×2 · Updraft · Ult 6/7". */
fun valorantKit(charId: String?, bits: Int, ult: Int, used: Map<String, Int> = emptyMap()): String {
    val ab = dev.uem.em.sim.Abilities.valorant(charId)
    fun name(slot: String) = ab.firstOrNull { it.slot == slot }?.name ?: slot
    val c = bits and 3; val q = (bits shr 2) and 3; val e = (bits shr 4) and 3
    val parts = ArrayList<String>()
    for ((slot, n) in listOf("C" to c, "Q" to q, "E" to e)) { val left = (n - (used[slot] ?: 0)).coerceAtLeast(0); if (n > 0) parts.add(name(slot) + " " + "●".repeat(left) + "○".repeat(n - left)) }
    val max = ab.firstOrNull { it.slot == "X" }?.ultPoints?.let { maxOf(6, it) } ?: 7
    parts.add(if (bits and 64 != 0) "${name("X")} READY" + (if ((used["X"] ?: 0) > 0) " (used)" else "") else "Ult ${ult.coerceAtLeast(0)}/$max")
    return parts.joinToString(" · ")
}

/** A CS2 player's grenades and kit this round (thrown ones greyed out), e.g. "Smoke · Flash ×2 · Molotov · Kit". */
fun csKit(bits: Int, used: Map<String, Int> = emptyMap()): String {
    val parts = ArrayList<String>()
    fun g(label: String, n: Int, key: String) { if (n <= 0) return; val left = (n - (used[key] ?: 0)).coerceAtLeast(0); parts.add(label + " " + "●".repeat(left) + "○".repeat(n - left)) }
    g("Smoke", bits and 1, "smoke"); g("Flash", (bits shr 1) and 3, "flash"); g("HE", (bits shr 3) and 1, "he"); g("Molotov", (bits shr 4) and 1, "molly"); g("Decoy", (bits shr 5) and 1, "decoy")
    if (bits and 64 != 0) parts.add("Defuse kit"); if (bits and 128 != 0) parts.add("Zeus")
    return parts.joinToString(" · ").ifEmpty { "no utility" }
}

/** A row of round results: the winner's colour and how the round ended; tap a round to open it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoundStrip(log: ShooterEconomy.EconLog, cs: Boolean, half: Int, selected: Int = -1, onTap: ((Int) -> Unit)? = null) {
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        for ((r, rd) in log.rounds.withIndex()) {
            if (r == half && r > 0) HGap(6)
            val col = if (rd.winner == 0) Ui.blue else Ui.red
            Box(Modifier.padding(1.dp).size(width = 22.dp, height = 26.dp).background(if (selected == r) Ui.card2 else col.copy(alpha = 0.28f), RoundedCornerShape(4.dp))
                .then(if (onTap != null) Modifier.clickable { onTap(r) } else Modifier), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (rd.how >= 0) RoundIcons.icon(rd.how) else "•", fontSize = 10.sp, color = col)
                    Text("${r + 1}", fontSize = 8.sp, color = Ui.muted)
                }
            }
        }
    }
    if (log.version >= 2) Muted(RoundIcons.legend(cs), 9)
}

/**
 * Shooter economy of a map, round by round: how each round ended, each team's buy and spend, and (tap a
 * round) every player's gun, shield / armour, abilities and ultimate or grenades and kit, what they spent and
 * what they had left.
 */
@Composable
fun EconomyCard(vm: GameVm, w: World, t: TitleDef, f: Fixture, g: GameRecord) {
    val log = remember(g) { ShooterEconomy.EconLog.parse(g.econ) } ?: return
    val rounds = log.rounds.size; val n = log.n
    val cs = t.id == "cs2"
    val teams = listOf(f.a, f.b)
    // Player of (team, index): the box score lists each team's players in lineup order.
    val pid = Array(2) { s -> g.lines.filter { it.team == s }.map { it.playerId } }
    val charOf = g.lines.associate { it.playerId to it.charId }
    var open by remember(g) { mutableIntStateOf(-1) }
    val weapons = Weapons.of(t.id)
    val map = Maps.shooter(g.mapId)
    // Totals per player: spent, average loadout value (gun + armour).
    val spent = Array(2) { IntArray(n) }; val value = Array(2) { DoubleArray(n) }
    for (rd in log.rounds) for (s in 0..1) for (i in 0 until n) { val pe = rd.players[s][i]; spent[s][i] += pe.spent; value[s][i] += (weapons.getOrNull(pe.weapon)?.cost ?: 0) + (if (pe.armor > 0) (if (cs) 650 else 400) * pe.armor else 0) }
    Card(if (cs) "Rounds, economy & loadouts" else "Rounds, economy, shields & abilities") {
        RoundStrip(log, cs, t.tactical?.half ?: 12, open) { open = if (open == it) -1 else it }
        Muted("Every round's buys with the real prices. Tap a round for each player's loadout and what was left after buying.", 11)
        for (s in 0..1) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { TeamChip(w, teams[s], vm, 16.dp) }
            for (i in 0 until minOf(n, pid[s].size)) Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(w.players[pid[s][i]].handle, color = Ui.text, fontSize = 12.sp, modifier = Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted("spent ${spent[s][i]}", 11); HGap(10)
                Muted("avg loadout ${(value[s][i] / rounds).toInt()}", 11)
            }
        }
        Gap(6)
        for ((r, rd) in log.rounds.withIndex()) {
            val buyA = ShooterEconomy.Bank.BUYS.getOrElse(rd.buyA) { "" }; val buyB = ShooterEconomy.Bank.BUYS.getOrElse(rd.buyB) { "" }; val win = rd.winner
            val teamSpend = IntArray(2) { s -> (0 until n).sumOf { i -> rd.players[s][i].spent } }
            Row(Modifier.fillMaxWidth().clickable { open = if (open == r) -1 else r }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("R${r + 1}", color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(30.dp))
                Box(Modifier.size(8.dp).background(if (win == 0) Ui.win else Ui.loss, RoundedCornerShape(4.dp))); HGap(4)
                Text("${BUY_LABEL[buyA] ?: buyA} (${teamSpend[0]})", color = if (win == 0) Ui.text else Ui.muted, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Text("${BUY_LABEL[buyB] ?: buyB} (${teamSpend[1]})", color = if (win == 1) Ui.text else Ui.muted, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Box(Modifier.size(8.dp).background(if (win == 1) Ui.win else Ui.loss, RoundedCornerShape(4.dp)))
                Text(if (rd.attack == 0) "  ${t.tactical!!.sides.first.take(3)}" else "  ${t.tactical!!.sides.second.take(3)}", color = Ui.muted, fontSize = 9.sp)
                if (rd.how >= 0) Text("  ${RoundIcons.icon(rd.how)}" + (map?.sites?.getOrNull(rd.site)?.id?.let { " $it" } ?: ""), color = Ui.text, fontSize = 10.sp)
            }
            if (open == r) Column(Modifier.padding(start = 30.dp, bottom = 6.dp)) {
                if (rd.how >= 0) Muted("${RoundIcons.icon(rd.how)} ${RoundIcons.label(cs, rd.how)}" + (map?.sites?.getOrNull(rd.site)?.id?.let { " · site $it" } ?: "") + " · ${w.teams[teams[win.coerceIn(0, 1)]].short} win", 10)
                for (s in 0..1) for (i in 0 until minOf(n, pid[s].size)) {
                    val pe = rd.players[s][i]
                    val wp = weapons.getOrNull(pe.weapon)
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(if (s == 0) Ui.blue else Ui.red, RoundedCornerShape(3.dp))); HGap(4)
                        Text(w.players[pid[s][i]].handle, color = Ui.text, fontSize = 11.sp, modifier = Modifier.width(86.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (wp != null) WeaponPic("${t.id}|${wp.id}", wp.name, if (wp.sniper) "Sniper" else "", 20.dp, tap = false)
                        HGap(4)
                        Column(Modifier.weight(1f)) {
                            Text(listOfNotNull(wp?.name, ShooterEconomy.armorName(cs, pe.armor)).joinToString(" · "), color = Ui.text, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val util = if (cs) csKit(pe.bits) else if (pe.ult >= 0) valorantKit(charOf[pid[s][i]], pe.bits, pe.ult) else ShooterEconomy.valUtilText(charOf[pid[s][i]], pe.bits)
                            if (util.isNotEmpty()) Muted(util, 9)
                        }
                        Text("−${pe.spent}", color = Ui.loss, fontSize = 10.sp, modifier = Modifier.width(44.dp))
                        Text("${pe.money}", color = Ui.gold, fontSize = 10.sp, modifier = Modifier.width(40.dp))
                    }
                }
            }
        }
    }
}

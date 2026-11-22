package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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

/**
 * Shooter economy of a map, round by round: each team's buy and spend, and (tap a round) every
 * player's gun, shield / armour, abilities or grenades, what they spent and what they had left.
 */
@Composable
fun EconomyCard(vm: GameVm, w: World, t: TitleDef, f: Fixture, g: GameRecord) {
    val e = g.econ
    if (e.size < 3 || e[0] != 1) return
    val rounds = e[1]; val n = e[2]
    val per = 4 + 2 * n * 5
    if (rounds <= 0 || e.size < 3 + rounds * per) return
    val cs = t.id == "cs2"
    val teams = listOf(f.a, f.b)
    // Player of (team, index): the box score lists each team's players in lineup order.
    val pid = Array(2) { s -> g.lines.filter { it.team == s }.map { it.playerId } }
    val charOf = g.lines.associate { it.playerId to it.charId }
    var open by remember(g) { mutableIntStateOf(-1) }
    val weapons = Weapons.of(t.id)
    // Totals per player: spent, average loadout value (gun + armour).
    val spent = Array(2) { IntArray(n) }; val value = Array(2) { DoubleArray(n) }
    for (r in 0 until rounds) { val b = 3 + r * per + 4; for (s in 0..1) for (i in 0 until n) { val o = b + (s * n + i) * 5; spent[s][i] += e[o + 3]; value[s][i] += (weapons.getOrNull(e[o])?.cost ?: 0) + (if (e[o + 1] > 0) (if (cs) 650 else 400) * e[o + 1] else 0) } }
    Card(if (cs) "Economy & loadouts" else "Economy (credits, guns, shields, abilities)") {
        Muted("Every round's buys with the real prices. Tap a round for each player's loadout.", 11)
        for (s in 0..1) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { TeamChip(w, teams[s], vm, 16.dp) }
            for (i in 0 until minOf(n, pid[s].size)) Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(w.players[pid[s][i]].handle, color = Ui.text, fontSize = 12.sp, modifier = Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted("spent ${spent[s][i]}", 11); HGap(10)
                Muted("avg loadout ${(value[s][i] / rounds).toInt()}", 11)
            }
        }
        Gap(6)
        for (r in 0 until rounds) {
            val b = 3 + r * per
            val attack = e[b]; val buyA = ShooterEconomy.Bank.BUYS.getOrElse(e[b + 1]) { "" }; val buyB = ShooterEconomy.Bank.BUYS.getOrElse(e[b + 2]) { "" }; val win = e[b + 3]
            val teamSpend = IntArray(2) { s -> (0 until n).sumOf { i -> e[b + 4 + (s * n + i) * 5 + 3] } }
            Row(Modifier.fillMaxWidth().clickable { open = if (open == r) -1 else r }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("R${r + 1}", color = Ui.muted, fontSize = 11.sp, modifier = Modifier.width(30.dp))
                Box(Modifier.size(8.dp).background(if (win == 0) Ui.win else Ui.loss, RoundedCornerShape(4.dp))); HGap(4)
                Text("${BUY_LABEL[buyA] ?: buyA} (${teamSpend[0]})", color = if (win == 0) Ui.text else Ui.muted, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Text("${BUY_LABEL[buyB] ?: buyB} (${teamSpend[1]})", color = if (win == 1) Ui.text else Ui.muted, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1)
                Box(Modifier.size(8.dp).background(if (win == 1) Ui.win else Ui.loss, RoundedCornerShape(4.dp)))
                Text(if (attack == 0) "  ${t.tactical!!.sides.first.take(3)}" else "  ${t.tactical!!.sides.second.take(3)}", color = Ui.muted, fontSize = 9.sp)
            }
            if (open == r) Column(Modifier.padding(start = 30.dp, bottom = 6.dp)) {
                for (s in 0..1) for (i in 0 until minOf(n, pid[s].size)) {
                    val o = b + 4 + (s * n + i) * 5
                    val wp = weapons.getOrNull(e[o])
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(if (s == 0) Ui.blue else Ui.red, RoundedCornerShape(3.dp))); HGap(4)
                        Text(w.players[pid[s][i]].handle, color = Ui.text, fontSize = 11.sp, modifier = Modifier.width(86.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (wp != null) WeaponPic("${t.id}|${wp.id}", wp.name, if (wp.sniper) "Sniper" else "", 20.dp, tap = false)
                        HGap(4)
                        Column(Modifier.weight(1f)) {
                            Text(listOfNotNull(wp?.name, ShooterEconomy.armorName(cs, e[o + 1])).joinToString(" · "), color = Ui.text, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val util = if (cs) ShooterEconomy.csUtilText(e[o + 2]) else ShooterEconomy.valUtilText(charOf[pid[s][i]], e[o + 2])
                            if (util.isNotEmpty()) Muted(util, 9)
                        }
                        Text("−${e[o + 3]}", color = Ui.loss, fontSize = 10.sp, modifier = Modifier.width(44.dp))
                        Text("${e[o + 4]}", color = Ui.gold, fontSize = 10.sp, modifier = Modifier.width(40.dp))
                    }
                }
            }
        }
    }
}

package dev.uem.em.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.GameRecord
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Ev
import dev.uem.em.sim.MobaMap
import dev.uem.em.titles.TitleDef

/**
 * Written commentary from the recorded game, like a caster's lines: first blood, streaks and
 * multi-kills, shutdowns, teamfights, objectives and steals, structures, coach comms and the gold
 * lead (MOBAs); buys, opening duels, multi-kills, clutches, plants, defuses and how each round ends
 * (shooters). Built once per game; the viewer shows the lines up to the current second.
 */
object Commentary {
    class Line(val t: Int, val text: String, val big: Boolean = false)

    private fun multiName(titleId: String, n: Int) = when (titleId) {
        "mlbb" -> listOf("", "", "Double Kill", "Triple Kill", "Mega Kill", "MANIAC", "SAVAGE")
        "dota2" -> listOf("", "", "Double Kill", "Triple Kill", "Ultra Kill", "RAMPAGE", "RAMPAGE")
        else -> listOf("", "", "Double Kill", "Triple Kill", "Quadra Kill", "PENTAKILL", "PENTAKILL")
    }.getOrElse(n.coerceAtMost(6)) { "" }

    private fun place(t: TitleDef, code: Int): String = when {
        code >= 10 -> "at " + (t.moba?.objectives?.getOrNull(code - 10)?.name ?: "the objective")
        code == 3 -> "in the jungle"
        code in 0..2 -> "in the ${listOf("top lane", "mid lane", "bottom lane")[code]}"
        else -> ""
    }

    fun moba(w: World, t: TitleDef, g: GameRecord, ev: List<Ev>, teams: Array<Team>): List<Line> {
        val out = ArrayList<Line>()
        fun who(pid: Int) = w.players.getOrNull(pid)?.handle ?: "?"
        fun ch(pid: Int) = g.lines.firstOrNull { it.playerId == pid }?.charId?.let { t.charIndex[it]?.name } ?: ""
        val streak = HashMap<Int, Int>()
        val lastKill = HashMap<Int, Pair<Int, Int>>() // killer → (time, count in the chain)
        var firstBlood = true
        val towers = IntArray(2)
        for (e in ev) {
            when (e.kind) {
                "kill" -> {
                    if (e.team < 0) { out.add(Line(e.t, "${who(e.b)} is executed")); streak[e.b] = 0; continue }
                    val victimStreak = streak[e.b] ?: 0
                    streak[e.b] = 0
                    val s = (streak[e.a] ?: 0) + 1; streak[e.a] = s
                    val chain = lastKill[e.a]?.let { (tt, n) -> if (e.t - tt <= 10) n + 1 else 1 } ?: 1
                    lastKill[e.a] = e.t to chain
                    val text = when {
                        firstBlood -> "FIRST BLOOD! ${who(e.a)} (${ch(e.a)}) draws first blood on ${who(e.b)} (${ch(e.b)}) for ${teams[e.team].short}"
                        chain >= 2 -> "${multiName(t.id, chain)}! ${who(e.a)} on ${ch(e.a)} is tearing through ${teams[1 - e.team].short}"
                        victimStreak >= 3 -> "SHUTDOWN — ${who(e.a)} ends ${who(e.b)}'s ${victimStreak}-kill run"
                        s == 3 -> "${who(e.a)} is heating up: three kills without dying"
                        s == 5 -> "${who(e.a)} is on a rampage — five in a row!"
                        s >= 7 && s % 2 == 1 -> "Can anyone stop ${who(e.a)}? That's $s straight"
                        else -> "${who(e.a)} (${ch(e.a)}) takes down ${who(e.b)} (${ch(e.b)})"
                    }
                    out.add(Line(e.t, text, firstBlood || chain >= 3))
                    firstBlood = false
                }
                "fight" -> {
                    val pl = place(t, e.x)
                    out.add(Line(e.t + e.m, when {
                        e.a >= e.b + 3 -> "Huge teamfight $pl and ${teams[e.team].short} win it ${e.a} for ${e.b}!"
                        e.a == e.b -> "Even trade $pl, ${e.a} for ${e.b}"
                        else -> "${teams[e.team].short} edge the fight $pl, ${e.a} for ${e.b}"
                    }, e.a >= e.b + 3))
                }
                "obj" -> out.add(Line(e.t, "${teams[e.team].short} secure ${e.text}", e.text.contains("Baron") || e.text.contains("Lord") || e.text.contains("Roshan") || e.text.contains("Elder")))
                "soul" -> out.add(Line(e.t, "${teams[e.team].short} claim the ${e.text}!", true))
                "steal" -> out.add(Line(e.t, "STOLEN! ${who(e.a)} snatches ${e.text} away from ${teams[1 - e.team].short}!", true))
                "tower" -> { towers[e.team]++; out.add(Line(e.t, "${teams[e.team].short} take ${MobaMap.of(t.id).structs.getOrNull(e.n)?.name ?: "a tower"} — ${towers[e.team]} structure${if (towers[e.team] > 1) "s" else ""} down for them")) }
                "base" -> out.add(Line(e.t, "${teams[e.team].short} break into the base: ${MobaMap.of(t.id).structs.getOrNull(e.n)?.name ?: "a base structure"} falls", true))
                "core" -> out.add(Line(e.t, "And that's the game! ${teams[e.team].short} destroy the ${t.moba?.structures?.coreName ?: "core"} at ${clock(e.t)}", true))
                "comms" -> out.add(Line(e.t, "${teams[e.team].short}'s coach jumps on comms for this fight (${e.a} call${if (e.a == 1) "" else "s"} left)"))
                "aegis" -> out.add(Line(e.t, "${who(e.a)} cheats death with the Aegis!", true))
                "buyback" -> out.add(Line(e.t, "${who(e.a)} buys back to defend"))
            }
        }
        // Gold lead every five minutes (from the state snapshots).
        val rp = g.replay
        if (rp != null && rp.per > 0 && rp.pids.isNotEmpty()) {
            val n = rp.pids.size
            for ((si, st) in rp.snapT.withIndex()) {
                if (st == 0 || st % 300 != 0) continue
                var lead = 0
                for (k in 0 until n) { val side = g.lines.firstOrNull { it.playerId == rp.pids[k] }?.team ?: 0; lead += rp.snap[(si * n + k) * rp.per] * (if (side == 0) 1 else -1) }
                val ahead = if (lead >= 0) 0 else 1
                val amt = kotlin.math.abs(lead)
                out.add(Line(st, if (amt < 1000) "${st / 60} minutes in and it's level on gold" else "${st / 60} minutes: ${teams[ahead].short} lead by %.1fk gold".format(amt / 1000.0)))
            }
        }
        return out.sortedBy { it.t }
    }

    fun shooter(w: World, t: TitleDef, ev: List<Ev>, teams: Array<Team>): List<Line> {
        val out = ArrayList<Line>()
        fun who(pid: Int) = w.players.getOrNull(pid)?.handle ?: "?"
        var roundNo = 0
        var roundKills = HashMap<Int, Int>()
        var first = true
        val alive = intArrayOf(t.teamSize, t.teamSize)
        var clutch = -1; var clutchVs = 0
        for (e in ev) {
            when (e.kind) {
                "round" -> {
                    roundNo++; roundKills = HashMap(); first = true; alive[0] = t.teamSize; alive[1] = t.teamSize; clutch = -1
                    val buys = e.text.split('|')
                    val atk = e.team.coerceIn(0, 1)
                    out.add(Line(e.t, "Round $roundNo (${e.a}-${e.b}): ${teams[atk].short} on attack with a ${buys.getOrElse(0) { "" }} buy, ${teams[1 - atk].short} ${buys.getOrElse(1) { "" }}"))
                }
                "kill" -> {
                    val ks = e.team.coerceIn(0, 1)
                    alive[1 - ks] = (alive[1 - ks] - 1).coerceAtLeast(0)
                    val k = (roundKills[e.a] ?: 0) + 1; roundKills[e.a] = k
                    when {
                        first -> out.add(Line(e.t, "${who(e.a)} opens the round on ${who(e.b)}"))
                        k == 5 -> out.add(Line(e.t, "ACE! ${who(e.a)} takes all five!", true))
                        k >= 3 -> out.add(Line(e.t, "${who(e.a)} has ${k} this round!", k >= 4))
                    }
                    first = false
                    for (s in 0..1) if (clutch < 0 && alive[s] == 1 && alive[1 - s] >= 2) { clutch = s; clutchVs = alive[1 - s]; out.add(Line(e.t, "${teams[s].short} down to their last player against $clutchVs")) }
                }
                "plant" -> out.add(Line(e.t, "${if (e.a >= 0) who(e.a) else teams[e.team.coerceIn(0, 1)].short} plants on ${if (e.x == 0) "A" else if (e.x == 1) "B" else "C"}"))
                "defuse" -> out.add(Line(e.t, "Defused${if (e.a >= 0) " by ${who(e.a)}" else ""}!", true))
                "timeout" -> out.add(Line(e.t, "${teams[e.team.coerceIn(0, 1)].short} call a tactical timeout to reset (${e.a} left)"))
                "roundEnd" -> {
                    val wnr = e.team.coerceIn(0, 1)
                    if (clutch == wnr) out.add(Line(e.t, "CLUTCH! ${teams[wnr].short} win the 1v$clutchVs!", true))
                    out.add(Line(e.t, "${teams[wnr].short} take round $roundNo — ${e.a}-${e.b}"))
                }
            }
        }
        return out
    }

    private fun clock(s: Int) = "%d:%02d".format(s / 60, s % 60)
}

/** The latest commentary lines up to second `ti` (newest first). */
@Composable
fun CommentaryCard(lines: List<Commentary.Line>, ti: Int, n: Int = 5) {
    val shown = lines.filter { it.t <= ti }.takeLast(n).reversed()
    Card("Commentary") {
        if (shown.isEmpty()) Muted("Welcome to the broadcast…")
        for ((i, l) in shown.withIndex()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Muted("%d:%02d".format(l.t / 60, l.t % 60), 11)
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
            LinkedText(l.text, if (i == 0) Ui.text else Ui.muted, if (l.big && i == 0) 14.sp else 12.sp, if (l.big) FontWeight.Bold else null)
        }
    }
}

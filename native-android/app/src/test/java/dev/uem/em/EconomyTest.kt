package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.ShooterEconomy
import dev.uem.em.sim.TacticalEngine
import dev.uem.em.sim.TeamContexts
import dev.uem.em.sim.Weapons
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Valorant and CS2 economy: real prices, sensible buys per round type, money within limits, logged for the box score. */
class EconomyTest {
    @Test
    fun buysFollowTheRealEconomy() {
        val w = MobaBalanceTest.world()
        val sb = StringBuilder()
        for (id in listOf("valorant", "cs2")) {
            val t = Catalogue.get(id)
            val cs = id == "cs2"
            val teams = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }
            val buyBySide = HashMap<String, Int>()
            var pistolOk = 0; var pistols = 0; var fullRifles = 0; var fullPlayers = 0; var fullArmor = 0; var util = 0; var rounds = 0
            repeat(6) { i ->
                val rng = Rng.of("econ|$id|$i")
                val a = teams[i % teams.size]; val b = teams[(i * 3 + 1) % teams.size].let { if (it == a) teams[(i + 1) % teams.size] else it }
                val ctxs = listOf(a, b).mapIndexed { side, team ->
                    val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                    val chars = HashMap<Int, String>(); val used = HashSet<String>()
                    if (t.characters.isNotEmpty()) lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.id !in used }; val c = opts[(i * 7 + k * 3 + side) % opts.size]; used.add(c.id); chars[pid] = c.id } }
                    TeamContexts.build(w, team, lineup, side, chars, t.maps[i % t.maps.size].id, 0.0, rng)
                }
                val o = TacticalEngine.simulate(t, ctxs, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), 0, rng, null, null, t.maps[i % t.maps.size].id)
                val e = o.econ
                val n = e[2]; val per = 4 + 2 * n * 5
                assertTrue("$id: every round logged", e[1] == o.score[0] + o.score[1])
                for (r in 0 until e[1]) {
                    rounds++
                    val base = 3 + r * per
                    run { val at = e[base]; val k = "${ShooterEconomy.Bank.BUYS[e[base + 1 + at]]}>${ShooterEconomy.Bank.BUYS[e[base + 2 - at]]}"; buyBySide["M $k"] = (buyBySide["M $k"] ?: 0) + 1; if (e[base + 3] == at) buyBySide["M $k W"] = (buyBySide["M $k W"] ?: 0) + 1 }
                    for (s in 0..1) {
                        val buy = ShooterEconomy.Bank.BUYS[e[base + 1 + s]]
                        val side = if (s == e[base]) "atk" else "def"
                        buyBySide["$side-$buy"] = (buyBySide["$side-$buy"] ?: 0) + 1
                        if (buy == "full" || buy == "force") buyBySide["$side-$buy-won"] = (buyBySide["$side-$buy-won"] ?: 0) + (if (e[base + 3] == s) 1 else 0)
                        for (k in 0 until n) {
                            val p = base + 4 + (s * n + k) * 5
                            val wp = Weapons.of(id).getOrNull(e[p])
                            val spent = e[p + 3]; val left = e[p + 4]
                            assertTrue("$id: money within limits ($left)", left >= 0 && left <= t.tactical!!.maxMoney)
                            if (buy == "pistol") { pistols++; if (spent <= 800) pistolOk++ }
                            if (buy == "full") { fullPlayers++; if ((wp?.cost ?: 0) >= 2700 || wp?.sniper == true) fullRifles++; if (e[p + 1] >= (if (cs) 1 else 2)) fullArmor++ }
                            if (e[p + 2] != 0) util++
                        }
                    }
                    if (i == 0 && r < 4) sb.appendLine("  $id R${r + 1}: " + (0..1).joinToString(" | ") { s -> ShooterEconomy.Bank.BUYS[e[base + 1 + s]] + ": " + (0 until n).joinToString(", ") { k -> val p = base + 4 + (s * n + k) * 5; "${Weapons.of(id).getOrNull(e[p])?.name}/${ShooterEconomy.armorName(cs, e[p + 1])}/${if (cs) ShooterEconomy.csUtilText(e[p + 2]) else "util ${e[p + 2]}"} -${e[p + 3]}" } })
                }
            }
            sb.appendLine("$id buys by side: " + buyBySide.toSortedMap())
            sb.appendLine("$id: $rounds rounds; pistol rounds ≤ 800: $pistolOk/$pistols; full buys with a rifle or sniper $fullRifles/$fullPlayers, with heavy armour $fullArmor/$fullPlayers; players with utility ${util}")
            assertTrue("$id pistol rounds cost at most the start money", pistolOk == pistols)
            assertTrue("$id full buys are rifles", fullRifles >= fullPlayers * 0.85)
            assertTrue("$id full buys have armour", fullArmor >= fullPlayers * 0.8)
        }
        println(sb); java.io.File("build/economy.txt").writeText(sb.toString())
    }
}

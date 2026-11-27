package dev.uem.em.sim

import dev.uem.em.core.Rng
import dev.uem.em.titles.TacticalConfig
import dev.uem.em.titles.TitleDef

/**
 * Shooter economy, player by player (Valorant credits, CS2 money) with the real prices: every gun,
 * shields / armour, ability charges (Valorant) or grenades and defuse kits (CS2). The team picks a
 * buy (pistol, eco, force, full); each player then buys by role and money (an Operator / AWP for the
 * team's sniper, utility for support roles), rich players drop rifles to poor ones on full buys, and
 * survivors keep their gun and armour. Rewards: round wins, the loss streak, kills (CS2 by weapon),
 * plants and defuses. Every round's buys are kept for the box score ([econ]).
 */
object ShooterEconomy {
    /** Armour: Valorant 1 light shield (25, 400), 2 heavy (50, 1000), 3 regen (25+, 650); CS2 1 kevlar (650), 2 kevlar + helmet (1000). */
    fun armorValue(cs: Boolean, code: Int) = if (cs) (if (code == 0) 0 else 100) else when (code) { 1 -> 25; 2 -> 50; 3 -> 35; else -> 0 }
    fun armorName(cs: Boolean, code: Int) = if (cs) when (code) { 1 -> "Kevlar"; 2 -> "Kevlar + helmet"; else -> "No armour" } else when (code) { 1 -> "Light shield"; 2 -> "Heavy shield"; 3 -> "Regen shield"; else -> "No shield" }
    private fun armorCost(cs: Boolean, code: Int) = if (cs) when (code) { 1 -> 650; 2 -> 1000; else -> 0 } else when (code) { 1 -> 400; 2 -> 1000; 3 -> 650; else -> 0 }

    /** CS2 grenades (price) and their utility kind in the round engine. */
    val CS_NADES = listOf("smoke" to 300, "flash" to 200, "he" to 300, "molly" to 400, "decoy" to 50)
    /** Utility bits: CS2 smoke | flash ×2 | HE | molotov / incendiary | decoy | kit | zeus; Valorant C ×3 | Q ×3 | E ×3 | ult. */
    fun csUtilText(bits: Int): String = buildList {
        if (bits and 1 != 0) add("Smoke"); val f = (bits shr 1) and 3; if (f > 0) add("Flash" + if (f > 1) " ×$f" else "")
        if (bits and 8 != 0) add("HE"); if (bits and 16 != 0) add("Molotov"); if (bits and 32 != 0) add("Decoy"); if (bits and 64 != 0) add("Kit"); if (bits and 128 != 0) add("Zeus")
    }.joinToString(", ")

    fun valUtilText(charId: String?, bits: Int): String {
        val ab = Abilities.valorant(charId)
        fun name(slot: String) = ab.firstOrNull { it.slot == slot }?.name ?: slot
        return buildList {
            val c = bits and 3; val q = (bits shr 2) and 3; val e = (bits shr 4) and 3
            if (c > 0) add(name("C") + if (c > 1) " ×$c" else ""); if (q > 0) add(name("Q") + if (q > 1) " ×$q" else ""); if (e > 0) add(name("E") + if (e > 1) " ×$e" else "")
            if (bits and 64 != 0) add(name("X") + " (ult)")
        }.joinToString(", ")
    }

    /** CS2 kill rewards by weapon (Valorant: 200 for every kill). */
    fun killReward(t: TitleDef, weapon: String?): Int {
        if (t.id != "cs2") return t.tactical!!.killReward
        return when (weapon) {
            "awp" -> 100; "p90" -> 300; "cz75" -> 100
            "mac10", "mp9", "mp7", "ump45", "mp5sd", "bizon" -> 600
            "nova", "mag7", "sawedoff" -> 900; "xm1014" -> 600
            else -> 300
        }
    }

    class Buy(val weapon: Weapon, val armor: Int, val util: MutableList<String>, val kit: Boolean, val bits: Int, val spent: Int)

    /**
     * Money and inventories of both teams for one map. Index = series team (0 / 1), player index =
     * position in the team's lineup.
     */
    class Bank(val t: TitleDef, val n: Int, seedKey: String) {
        val cfg: TacticalConfig = t.tactical!!
        val cs = t.id == "cs2"
        val money = Array(2) { DoubleArray(n) { cfg.startMoney.toDouble() } }
        /** What survivors carry into the next round (gun id, armour code). */
        val gun = Array(2) { arrayOfNulls<String>(n) }
        val armor = Array(2) { IntArray(n) }
        val streak = IntArray(2)
        /** Winner of the last round (-1 at the start of a half). */
        var lastWinner = -1
        private val key = seedKey
        /**
         * Box score (version 2): per round [attack, buyA, buyD, winner, how it ended, site] then per player [weapon index,
         * armour, utility bits, spent, money left, ultimate points]. Version 1 (older saves) has no ending, site or ultimate.
         */
        private val log = ArrayList<Int>()
        var rounds = 0; private set
        val weapons = Weapons.of(t.id)
        private val per = EconLog.ROUND + 2 * n * EconLog.PLAYER

        fun econ(): IntArray = (intArrayOf(2, rounds, n) + log.toIntArray())

        fun resetHalf() { lastWinner = -1; for (s in 0..1) { money[s].fill(cfg.startMoney.toDouble()); gun[s].fill(null); armor[s].fill(0); streak[s] = 0 } }
        fun fill(v: Double) { for (s in 0..1) money[s].fill(v) }

        private fun w(id: String) = weapons.first { it.id == id }
        private fun price(id: String?) = id?.let { x -> weapons.firstOrNull { it.id == x }?.cost } ?: 0

        /** The team's buy this round (pistol / eco / force / full), as the in-game IGL would call it. */
        fun teamBuy(s: Int, pistol: Boolean, smart: Double, aggression: Double, fullBuy0: Double, rng: Rng, attacking: Boolean = true): Pair<Double, String> {
            if (pistol) return 0.72 to "pistol"
            // A complete CS2 CT buy (M4, kit, incendiary) costs more than a T buy: CTs wait until they have it.
            val fullBuy = if (cs && !attacking) fullBuy0 * 1.12 else fullBuy0
            val m = money[s]
            val carriedValue = (0 until n).sumOf { i -> price(gun[s][i]) * 0.6 + armorCost(cs, armor[s][i]) * 0.5 }
            val avg = m.average() + carriedValue / n
            val afford = (0 until n).count { i -> m[i] + price(gun[s][i]) >= fullBuy * 0.9 }
            if (avg >= fullBuy && afford >= n - 1) return 1.0 to "full"
            // After a won round (the pistol, typically) the winners buy SMGs and armour against the eco.
            if (lastWinner == s && avg >= fullBuy * 0.55) return 0.84 to "force"
            // A force when the next round's money would not be much better, or the half / map is on the line.
            val force = if (avg >= fullBuy * 0.55) 0.35 + aggression * 0.3 - smart * 0.2 else 0.08 * (1 - smart)
            return if (rng.chance(force.coerceIn(0.02, 0.8))) 0.84 to "force" else 0.58 to "eco"
        }

        /** Every player's purchases for this round (and the round's log entry). */
        fun buyRound(round: Int, attack: Int, buys: Array<String>, teams: List<TeamCtx>, ult: Array<IntArray>, rng0: Rng, winnerLater: Int = -1): Array<Array<Buy>> {
            val out = Array(2) { s -> Array(teams[s].players.size) { i -> buyOne(s, i, teams[s].players[i], buys[s], s == attack, ult[s], Rng.derive(key, "buy", round, s, i)) } }
            // Drops on full buys: a player who could not afford a rifle gets one from the richest teammate.
            for (s in 0..1) if (buys[s] == "full") for (i in out[s].indices) {
                val b = out[s][i]
                if (b.weapon.cost >= 1800 || i >= n) continue
                val rifle = w(if (cs) (if (s == attack) "ak47" else "m4a1s") else "vandal")
                val donor = (0 until minOf(n, out[s].size)).filter { it != i && money[s][it] >= rifle.cost }.maxByOrNull { money[s][it] } ?: continue
                money[s][donor] -= rifle.cost
                out[s][i] = Buy(rifle, b.armor, b.util, b.kit, b.bits, b.spent)
                gun[s][i] = rifle.id
            }
            log.add(attack); log.add(BUYS.indexOf(buys[0]).coerceAtLeast(0)); log.add(BUYS.indexOf(buys[1]).coerceAtLeast(0)); log.add(-1); log.add(-1); log.add(-1)
            for (s in 0..1) for (i in 0 until n) {
                val b = out[s].getOrNull(i)
                log.add(if (b != null) weapons.indexOf(b.weapon) else -1); log.add(b?.armor ?: 0); log.add(b?.bits ?: 0); log.add(b?.spent ?: 0); log.add(money[s][i].toInt())
                log.add(if (cs) 0 else ult[s].getOrElse(i) { 0 })
            }
            rounds++
            return out
        }

        private fun spend(s: Int, i: Int, c: Int): Boolean { if (money[s][i] < c) return false; money[s][i] -= c; return true }

        private fun buyOne(s: Int, i: Int, pc: PlayerCtx, buy: String, attacking: Boolean, ult: IntArray, rng: Rng): Buy {
            if (i >= n) return Buy(weapons[0], 0, ArrayList(), false, 0, 0)
            val before = money[s][i]
            val role = pc.roleId
            val sniper = role == "awper" || (!cs && (role == "sentinel" || role == "duelist") && i % 5 == 1)
            val support = pc.role.isSupport || role in setOf("support", "controller", "initiator", "igl")
            val kept = gun[s][i]?.let { id -> weapons.firstOrNull { it.id == id } }
            var weapon: Weapon = kept ?: w(if (cs) (if (attacking) "glock" else "usp") else "classic")
            var arm = armor[s][i]
            val util = ArrayList<String>()
            var bits = 0
            var kit = false
            fun want(id: String): Boolean {
                val g = w(id)
                if (weapon.cost >= g.cost && (weapon.sniper == g.sniper || !g.sniper)) return true // already better or equal
                if (!spend(s, i, g.cost)) return false
                weapon = g; return true
            }
            fun armour(code: Int) { if (arm >= code && !(cs && code == 2 && arm == 1)) return; val c = if (cs && code == 2 && arm == 1) 350 else armorCost(cs, code); if (spend(s, i, c)) arm = code }
            when (buy) {
                "pistol" -> if (cs) {
                    when { support && rng.chance(0.5) -> { if (spend(s, i, 300)) { util.add("smoke"); bits = bits or 1 }; if (spend(s, i, 200)) { util.add("flash"); bits = bits or 2 } }
                        rng.chance(0.55) -> armour(1)
                        else -> want(if (attacking) (if (rng.chance(0.5)) "tec9" else "p250") else (if (rng.chance(0.5)) "fiveseven" else "p250")) }
                    if (!attacking && money[s][i] >= 400 && rng.chance(0.4)) { if (spend(s, i, 400)) { kit = true; bits = bits or 64 } }
                } else {
                    when { rng.chance(0.4) -> armour(1); rng.chance(0.5) -> want("ghost"); role == "duelist" && rng.chance(0.4) -> want("sheriff"); else -> want("frenzy") }
                }
                "eco" -> {
                    if (cs) { if (money[s][i] >= 2400 && rng.chance(0.35)) want(if (rng.chance(0.5)) "deagle" else "p250"); if (support && money[s][i] >= 1800 && spend(s, i, 200)) { util.add("flash"); bits = bits or 2 } }
                    else if (money[s][i] >= 2500 && rng.chance(0.4)) want(if (rng.chance(0.6)) "sheriff" else "ghost")
                }
                "force" -> {
                    if (cs) {
                        if (!(want(if (rng.chance(0.45)) (if (attacking) "galil" else "famas") else (if (attacking) "mac10" else "mp9")))) want("p250")
                        armour(if (money[s][i] >= 1300) 2 else 1)
                        if (spend(s, i, 200)) { util.add("flash"); bits = bits or 2 }
                    } else {
                        val g = when { sniper && rng.chance(0.5) -> "marshal"; rng.chance(0.4) -> "spectre"; rng.chance(0.5) -> "bulldog"; else -> "stinger" }
                        if (!want(g)) want("sheriff")
                        armour(1)
                    }
                }
                else -> { // full
                    if (cs) {
                        if (sniper) { if (!want("awp")) want(if (attacking) "ak47" else "m4a1s") }
                        else want(if (attacking) "ak47" else "m4a1s")
                        armour(2)
                    } else {
                        if (sniper && money[s][i] >= 5700) want("operator") else want(if (rng.chance(0.6)) "vandal" else "phantom")
                        armour(2)
                    }
                }
            }
            // CS2 defenders buy defuse kits before grenades (most of a full-buying CT side carries one).
            if (cs && !attacking && (buy == "full" || buy == "force") && (support || rng.chance(0.45)) && spend(s, i, 400)) { kit = true; bits = bits or 64 }
            // Utility: CS2 grenades (up to four), Valorant ability charges with their credit cost.
            if (cs) {
                if (buy == "full" || (buy == "force" && money[s][i] >= 900)) {
                    if (bits and 1 == 0 && spend(s, i, 300)) { util.add("smoke"); bits = bits or 1 }
                    if ((bits shr 1) and 3 == 0 && spend(s, i, 200)) { util.add("flash"); bits = bits or 2 }
                    if (support && (bits shr 1) and 3 == 1 && spend(s, i, 200)) { util.add("flash"); bits = (bits and 6.inv()) or 4 }
                    if (spend(s, i, if (attacking) 400 else 500)) { util.add("molly"); bits = bits or 16 }
                    if (support && spend(s, i, 300)) { util.add("he"); bits = bits or 8 }
                }
                if (!attacking && (buy == "full" || buy == "force") && !kit && (support || rng.chance(0.45)) && spend(s, i, 400)) { kit = true; bits = bits or 64 }
            } else {
                var c = 0; var q = 0; var e = 0
                for (a in Abilities.valorant(pc.charId)) {
                    val kind = a.kinds.firstOrNull() ?: continue
                    when (a.slot) {
                        "E" -> { e = maxOf(1, a.charges).coerceAtMost(3); repeat(e) { util.add(kind) } }
                        "C", "Q" -> {
                            val maxN = maxOf(1, a.charges).coerceAtMost(3)
                            val wantN = when (buy) { "full" -> maxN; "force" -> minOf(maxN, 1 + (if (support) 1 else 0)); "pistol" -> if (rng.chance(0.6)) 1 else 0; else -> if (support && money[s][i] >= 2000) 1 else 0 }
                            var got = 0
                            repeat(wantN) { if (spend(s, i, a.cost)) { got++; util.add(kind) } }
                            if (a.slot == "C") c = got else q = got
                        }
                        "X" -> if (i in ult.indices && ult[i] >= maxOf(6, a.ultPoints)) { util.add(kind); ult[i] = 0; bits = bits or 64 }
                    }
                }
                bits = bits or c or (q shl 2) or (e shl 4)
            }
            gun[s][i] = weapon.id
            armor[s][i] = arm
            return Buy(weapon, arm, util, kit, bits, (before - money[s][i]).toInt())
        }

        fun onKill(s: Int, i: Int, weapon: String?) { if (i in 0 until n) money[s][i] += killReward(t, weapon).toDouble() }

        /** The planter's bonus (CS2 300) or every attacker's (Valorant 300). */
        fun onPlant(s: Int, i: Int) { if (cs) { if (i in 0 until n) money[s][i] += 300.0 } else for (k in 0 until n) money[s][k] += cfg.plantReward.toDouble() }

        /** Round result: win / loss rewards, the loss streak, what the survivors keep. */
        /** [how]: 0 elimination, 1 the bomb / spike exploded, 2 defused, 3 time ran out; [site]: the site played (-1 none). */
        fun endRound(rw: Int, attack: Int, aliveIdx: Array<Set<Int>>, planted: Boolean, bombWin: Boolean, how: Int = -1, site: Int = -1) {
            val lr = 1 - rw
            lastWinner = rw
            log[log.size - per + 3] = rw; log[log.size - per + 4] = how; log[log.size - per + 5] = site
            for (i in 0 until n) {
                money[rw][i] += if (cs && bombWin) 3500.0 else cfg.winReward.toDouble()
                money[lr][i] += cfg.lossRewards[minOf(streak[lr], cfg.lossRewards.size - 1)].toDouble()
                if (cs && planted && lr == attack) money[lr][i] += 800.0
            }
            // CS2: a win lowers the loss bonus by one step; Valorant resets it.
            streak[rw] = if (cs) maxOf(0, streak[rw] - 1) else 0
            streak[lr] = minOf(streak[lr] + 1, cfg.lossRewards.size - 1)
            for (s in 0..1) for (i in 0 until n) {
                if (i !in aliveIdx[s]) { gun[s][i] = null; armor[s][i] = 0 }
                money[s][i] = minOf(cfg.maxMoney.toDouble(), money[s][i])
            }
        }

        companion object { val BUYS = listOf("pistol", "full", "force", "eco") }
    }

    /** The per-round economy and loadouts of a map's box score, either version (see [Bank]). */
    class EconLog(val rounds: List<Round>, val n: Int, val version: Int) {
        class Player(val weapon: Int, val armor: Int, val bits: Int, val spent: Int, val money: Int, val ult: Int)
        class Round(val attack: Int, val buyA: Int, val buyB: Int, val winner: Int, val how: Int, val site: Int, val players: Array<Array<Player>>)
        companion object {
            const val ROUND = 6
            const val PLAYER = 6
            /** Endings: 0 elimination, 1 detonation, 2 defuse, 3 time. */
            val HOW = listOf("Elimination", "Detonation", "Defused", "Time")
            fun parse(e: IntArray): EconLog? {
                if (e.size < 3 || e[0] !in 1..2) return null
                val v = e[0]; val rounds = e[1]; val n = e[2]
                val rf = if (v == 1) 4 else ROUND; val pf = if (v == 1) 5 else PLAYER
                val per = rf + 2 * n * pf
                if (rounds <= 0 || e.size < 3 + rounds * per) return null
                val out = ArrayList<Round>()
                for (r in 0 until rounds) {
                    val b = 3 + r * per
                    val pl = Array(2) { s -> Array(n) { i -> val o = b + rf + (s * n + i) * pf; Player(e[o], e[o + 1], e[o + 2], e[o + 3], e[o + 4], if (pf > 5) e[o + 5] else -1) } }
                    out.add(Round(e[b], e[b + 1], e[b + 2], e[b + 3], if (v == 1) -1 else e[b + 4], if (v == 1) -1 else e[b + 5], pl))
                }
                return EconLog(out, n, v)
            }
        }
    }
}

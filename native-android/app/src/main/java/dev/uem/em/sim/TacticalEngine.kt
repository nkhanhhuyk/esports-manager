package dev.uem.em.sim

import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.core.logistic
import dev.uem.em.titles.Phase
import dev.uem.em.titles.TitleDef
import kotlin.math.abs

/**
 * Fast round-by-round shooter engine (Valorant, CS2), ported from the web version: buys and economy,
 * duels by aim / entry / clutch, trades, plants and defuses, halves and overtime. Team 0 / 1 are the
 * series teams; `firstAttack` is the team attacking (T side) in the first half.
 */
object TacticalEngine {
    private const val DUEL_SCALE = 26.0
    private const val COORDINATION = 44
    private const val CROSSHAIR = 74

    fun simulate(t: TitleDef, teams: List<TeamCtx>, comp: Array<CompEval>, firstAttack: Int, rng: Rng, patch: Patch? = null, events: MutableList<Ev>? = null): GameOutcome {
        // Events for the 2D view: t = round number; x = site (0 A, 1 B).
        val cfg = t.tactical!!
        val defEdge = cfg.defenderEdge + (patch?.obj?.get("defender") ?: 0.0)
        val fullBuy = cfg.fullBuy * (patch?.obj?.get("fullbuy") ?: 1.0)
        val book = StatBook(teams, t)
        val K = book.idx("k"); val D = book.idx("d"); val A = book.idx("a"); val DMG = book.idx("dmg"); val FK = book.idx("fk"); val FD = book.idx("fd")
        val CLUTCH = book.idx("clutch"); val PLANT = book.idx("plant"); val DEFUSE = book.idx("defuse"); val KAST = book.idx("kast"); val HS = book.idx("hs"); val MK = book.idx("mk"); val UTIL = book.idx("util")
        val AIM = Phase.AIM.ordinal; val ENTRY = Phase.ENTRY.ordinal; val CL = Phase.CLUTCH.ordinal; val MOV = Phase.MOVEMENT.ordinal; val UT = Phase.UTILITY.ordinal; val VI = Phase.VISION.ordinal
        val n = t.teamSize
        val money = Array(2) { DoubleArray(n) { cfg.startMoney.toDouble() } }
        val carried = Array(2) { DoubleArray(n) }
        val streak = IntArray(2)
        val score = IntArray(2)
        var attack = firstAttack
        var round = 0
        var ot = false
        var otStart = IntArray(2)
        var otRound = 0
        val teamUtil = DoubleArray(2) { teams[it].phase[UT] * cfg.utilityWeight }
        val teamInfo = DoubleArray(2) { teams[it].phase[VI] }
        val coord = DoubleArray(2) { i -> teams[i].players.sumOf { it.p.attrs[COORDINATION] } / maxOf(1, teams[i].players.size) }
        val igl = DoubleArray(2) { teams[it].phase[Phase.IGL.ordinal] }
        val econ = DoubleArray(2) { teams[it].phase[Phase.ECONOMY.ordinal] }

        fun buyFor(ti: Int, pistol: Boolean): Pair<Double, String> {
            if (pistol) return 0.72 to "pistol"
            val avg = money[ti].average()
            if (avg + carried[ti].average() * 0.6 >= fullBuy) return 1.0 to "full"
            val smart = econ[ti] / 100
            val force = if (avg >= fullBuy * 0.55) 0.35 + (teams[ti].aggression / 100) * 0.3 - smart * 0.2 else 0.08 * (1 - smart)
            return if (rng.chance(clamp(force, 0.02, 0.8))) 0.84 to "force" else 0.58 to "eco"
        }
        fun spend(ti: Int, buy: String) {
            val cost = when (buy) { "full" -> fullBuy.toDouble(); "force" -> fullBuy * 0.6; "pistol" -> minOf(cfg.startMoney, 800).toDouble(); else -> 300.0 }
            for (i in 0 until n) money[ti][i] = maxOf(0.0, money[ti][i] - maxOf(0.0, cost - carried[ti][i]))
        }
        fun duel(pc: PlayerCtx, initiating: Boolean) = pc.phase[AIM] * 0.68 + (if (initiating) pc.phase[ENTRY] else pc.phase[CL]) * 0.2 + pc.phase[MOV] * 0.12

        while (true) {
            val def = 1 - attack
            val pistol = (round == 0 || round == cfg.half) && !ot
            val bA = buyFor(attack, pistol)
            val bD = buyFor(def, pistol)
            val qA = if (ot) 1.0 else bA.first
            val qD = if (ot) 1.0 else bD.first
            spend(attack, bA.second)
            spend(def, bD.second)
            val alive = arrayOf(teams[0].players.toMutableList(), teams[1].players.toMutableList())
            val roundKills = HashMap<Int, Int>()
            val assisted = HashSet<Int>()
            val traded = HashSet<Int>()
            var planted = false
            var first = true
            var duels = 0
            var clutchSide = -1
            var clutchPlayer: PlayerCtx? = null
            val baseEdge = -defEdge + (qA - qD) * 1.7 + (teamUtil[attack] - teamUtil[def]) / 60 + (teamInfo[attack] - teamInfo[def]) / 80 + (igl[attack] - igl[def]) / 80 + (comp[attack].total - comp[def].total) * 0.4
            val swing = rng.normal(0.0, 0.32)
            val site = (round * 7 + score[0] * 3 + score[1]) % 2
            events?.add(Ev(round, "round", attack, x = site, a = score[0], b = score[1], text = bA.second + "|" + bD.second))
            fun kill(ks: Int, killer: PlayerCtx, victim: PlayerCtx) {
                val vs = 1 - ks
                alive[vs].remove(victim)
                events?.add(Ev(round, "kill", ks, killer.p.id, victim.p.id, if (planted) 1 else 0))
                book.add(killer.p.id, K, 1.0)
                book.add(victim.p.id, D, 1.0)
                book.add(killer.p.id, DMG, 100.0 + rng.int(0, 60))
                if (rng.chance(clamp(0.25 + killer.p.attrs[CROSSHAIR] / 100 * 0.45, 0.2, 0.75))) book.add(killer.p.id, HS, 1.0)
                roundKills[killer.p.id] = (roundKills[killer.p.id] ?: 0) + 1
                if (first) { book.add(killer.p.id, FK, 1.0); book.add(victim.p.id, FD, 1.0); first = false }
                val mates = alive[ks].filter { it !== killer }
                if (mates.isNotEmpty() && rng.chance(0.42)) {
                    val asst = rng.pickCtx(mates) { it.phase[UT] }
                    book.add(asst.p.id, A, 1.0)
                    assisted.add(asst.p.id)
                    if (rng.chance(0.4)) book.add(asst.p.id, UTIL, 1.0)
                }
                val ki = teams[ks].players.indexOf(killer)
                if (ki >= 0) money[ks][ki] += cfg.killReward.toDouble()
                for (s in 0..1) if (alive[s].size == 1 && alive[1 - s].size >= 2 && clutchSide < 0) { clutchSide = s; clutchPlayer = alive[s][0] }
            }
            while (alive[0].isNotEmpty() && alive[1].isNotEmpty() && duels < 14) {
                duels++
                val av = alive[attack]
                val dv = alive[def]
                val atk = if (planted) rng.pickCtx(av) { it.phase[CL] + it.phase[AIM] } else rng.pickCtx(av) { it.role.phaseArr[ENTRY].let { e -> if (e > 0) e else 0.05 } * 3 + it.phase[ENTRY] / 100 }
                val dfn = if (planted) rng.pickCtx(dv) { it.phase[ENTRY] + it.phase[AIM] } else rng.pickCtx(dv) { (if (it.role.isAnchor) 2.0 else 1.0) + it.phase[VI] / 100 }
                val man = (av.size - dv.size) * 0.17
                val logit = (duel(atk, !planted) - duel(dfn, planted)) / DUEL_SCALE + baseEdge + swing + man + (if (planted) 0.3 else 0.0) + rng.normal(0.0, 0.12)
                val atkWins = rng.chance(logistic(logit))
                val ks = if (atkWins) attack else def
                val killer = if (atkWins) atk else dfn
                val victim = if (atkWins) dfn else atk
                kill(ks, killer, victim)
                val vs = 1 - ks
                if (alive[vs].isNotEmpty() && rng.chance(0.22 + coord[vs] / 100 * 0.28)) {
                    val trader = rng.pickCtx(alive[vs]) { it.phase[AIM] }
                    if (rng.chance(logistic((duel(trader, true) - duel(killer, false)) / DUEL_SCALE + 0.45))) { kill(vs, trader, killer); traded.add(victim.p.id) }
                }
                if (!planted && alive[attack].isNotEmpty() && alive[def].isNotEmpty() && (alive[attack].size >= alive[def].size || rng.chance(0.3)) && rng.chance(0.55)) {
                    planted = true
                    events?.add(Ev(round, "plant", attack, x = site))
                    book.add(rng.pickCtx(alive[attack]) { if (it.role.isSupport) 2.0 else 1.0 }.p.id, PLANT, 1.0)
                    for (i in 0 until n) money[attack][i] += cfg.plantReward / (if (t.id == "cs2") n.toDouble() else 1.0)
                }
            }
            val rw = when {
                alive[def].isEmpty() -> attack
                alive[attack].isEmpty() -> if (planted) {
                    if (rng.chance(clamp(0.9 - (if (alive[def].size == 1) 0.12 else 0.0), 0.5, 0.97))) { book.add(rng.pickCtx(alive[def]) { it.phase[CL] }.p.id, DEFUSE, 1.0); events?.add(Ev(round, "defuse", def)); def } else attack
                } else def
                else -> if (planted) attack else def
            }
            score[rw]++
            events?.add(Ev(round, "roundEnd", rw, a = score[0], b = score[1]))
            if (clutchSide == rw) clutchPlayer?.let { book.add(it.p.id, CLUTCH, 1.0) }
            for (s in 0..1) for (pc in teams[s].players) {
                val k = roundKills[pc.p.id] ?: 0
                if (k > 0 || pc.p.id in assisted || pc in alive[s] || pc.p.id in traded) book.add(pc.p.id, KAST, 1.0)
                if (k >= 3) book.add(pc.p.id, MK, 1.0)
            }
            val lr = 1 - rw
            for (i in 0 until n) {
                money[rw][i] += cfg.winReward
                money[lr][i] += cfg.lossRewards[minOf(streak[lr], cfg.lossRewards.size - 1)]
            }
            streak[rw] = 0
            streak[lr]++
            for (s in 0..1) teams[s].players.forEachIndexed { i, pc ->
                carried[s][i] = if (pc in alive[s]) (if (s == attack) qA else qD) * fullBuy * 0.8 else 0.0
                money[s][i] = minOf(cfg.maxMoney.toDouble(), money[s][i])
            }
            round++
            val target = cfg.roundsToWin
            if (!ot) {
                if (score[0] >= target || score[1] >= target) break
                if (score[0] == target - 1 && score[1] == target - 1) {
                    ot = true
                    otStart = score.copyOf()
                    otRound = 0
                    val m = if (t.id == "cs2") 10000.0 else 5000.0
                    for (s in 0..1) money[s].fill(m)
                }
                if (round == cfg.half) {
                    attack = 1 - attack
                    for (s in 0..1) { money[s].fill(cfg.startMoney.toDouble()); carried[s].fill(0.0); streak[s] = 0 }
                }
            } else {
                otRound++
                if (cfg.otWinByTwo) {
                    attack = 1 - attack
                    if (abs(score[0] - score[1]) >= 2 || otRound > 40) break
                } else {
                    val half = cfg.otRounds / 2
                    if (score[0] - otStart[0] >= half + 1 || score[1] - otStart[1] >= half + 1) break
                    if (otRound % half == 0) { attack = 1 - attack; for (s in 0..1) money[s].fill(10000.0) }
                    if (otRound % cfg.otRounds == 0) otStart = score.copyOf()
                    if (otRound > 60) break
                }
            }
        }
        val winner = if (score[0] > score[1]) 0 else if (score[1] > score[0]) 1 else if (rng.chance(0.5)) 0 else 1
        val rounds = score[0] + score[1]
        book.finalise()
        book.rate(winner) { l ->
            0.4 + book.get(l, K) - book.get(l, D) * 0.55 + book.get(l, A) * 0.3 + book.get(l, KAST) / maxOf(1, rounds) * 2.2 + book.get(l, FK) * 0.4 - book.get(l, FD) * 0.2 + book.get(l, CLUTCH) * 0.9 + book.get(l, MK) * 0.5 + book.get(l, DMG) / 1200
        }
        return GameOutcome(winner, rounds * 105, score, book)
    }
}

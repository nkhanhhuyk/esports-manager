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
/**
 * Tactical timeouts for a map: how many each team may call, which teams' coaches call them on their
 * own (after losing streaks, at map point), and timeouts the user called before given rounds.
 */
class TimeoutPlan(val allowed: Int, val auto: BooleanArray = booleanArrayOf(true, true), val manual: List<Pair<Int, Int>> = emptyList(), val coachQ: DoubleArray = doubleArrayOf(50.0, 50.0))

object TacticalEngine {
    /** Timeouts per team per map by default (CS2 3, Valorant 2). */
    fun defaultTimeouts(titleId: String) = if (titleId == "cs2") 3 else 2
    private const val DUEL_SCALE = 26.0
    private const val COORDINATION = 44
    private const val CROSSHAIR = 74

    /** Weapon and armour for a player this round (from the team's buy; AWPers / Operators buy snipers on full buys). */
    fun gear(t: TitleDef, pc: PlayerCtx, buy: String, attacking: Boolean, i: Int, rng: Rng): Pair<Weapon, Int> {
        val cs = t.id == "cs2"
        val w = Weapons.of(t.id)
        fun id(x: String) = w.first { it.id == x }
        return when (buy) {
            "pistol" -> (if (cs) id(if (attacking) "glock" else "usp") else if (rng.chance(0.4)) id("ghost") else id("classic")) to (if (rng.chance(0.4)) (if (cs) 100 else 25) else 0)
            "eco" -> (if (rng.chance(0.4)) id(if (cs) "deagle" else "sheriff") else id(if (cs) (if (attacking) "glock" else "usp") else "classic")) to 0
            "force" -> (if (cs) id(if (rng.chance(0.5)) "mp9" else if (attacking) "galil" else "famas") else id(if (rng.chance(0.5)) "spectre" else "bulldog")) to (if (cs) 100 else 25)
            else -> {
                val sniper = pc.roleId == "awper" || (!cs && (pc.roleId == "sentinel" || pc.roleId == "duelist") && i % 5 == 1 && rng.chance(0.35))
                (if (sniper) id(if (cs) "awp" else "operator") else if (cs) id(if (attacking) "ak47" else "m4a1s") else id(if (rng.chance(0.55)) "vandal" else "phantom")) to (if (cs) 100 else 50)
            }
        }
    }

    private val VAL_KIND = mapOf("smoke" to "smoke", "flash" to "flash", "molly" to "molly", "recon" to "recon", "wall" to "wall", "trap" to "trap", "concuss" to "concuss",
        "slow" to "slow", "heal" to "heal", "mobility" to "mobility", "decoy" to "decoy", "nearsight" to "nearsight", "suppress" to "suppress", "revive" to "heal", "weapon" to "mobility")

    /** Which of an agent's ability slots (C, Q, E, X) a used utility kind came from (the first with that kind; null if none). */
    fun valorantSlotOf(charId: String?, kind: String): String? =
        Abilities.valorant(charId).sortedBy { listOf("C", "Q", "E", "X").indexOf(it.slot) }.firstOrNull { a -> a.kinds.any { VAL_KIND[it] == kind } }?.slot

    /** Utility a player carries this round: the agent's kit (charges bought, signature free, ultimate when charged) or CS2 grenades. */
    fun utilityFor(t: TitleDef, pc: PlayerCtx, buy: String, attacking: Boolean, ult: IntArray, i: Int, rng: Rng): MutableList<String> {
        val out = ArrayList<String>()
        if (t.id == "cs2") {
            when (buy) {
                "full" -> { out.add("smoke"); out.add("flash"); out.add("molly"); if (pc.role.isSupport || pc.roleId == "support") { out.add("flash"); out.add("he") } }
                "force" -> out.add("flash")
                "pistol" -> if (rng.chance(0.3)) out.add(if (rng.chance(0.5)) "flash" else "smoke")
            }
            return out
        }
        for (a in Abilities.valorant(pc.charId)) {
            val k = a.kinds.firstNotNullOfOrNull { VAL_KIND[it] } ?: continue
            when (a.slot) {
                "E" -> out.add(k)
                "C", "Q" -> if (buy == "full" || buy == "force" || (buy == "pistol" && rng.chance(0.5))) repeat(maxOf(1, a.charges)) { out.add(k) }
                "X" -> if (i in ult.indices && ult[i] >= maxOf(6, a.ultPoints)) { out.add(k); ult[i] = 0 }
            }
        }
        return out
    }

    fun simulate(t: TitleDef, teams: List<TeamCtx>, comp: Array<CompEval>, firstAttack: Int, rng0: Rng, patch: Patch? = null, rec: Recorder? = null, mapId: String? = null, plan: TimeoutPlan? = null, world: dev.uem.em.model.World? = null): GameOutcome {
        // Each round draws from its own stream (map seed + round), so a timeout called before a round
        // replays every earlier round exactly as it was.
        val seedKey = "${rng0.next()}"
        var rng = rng0
        val tp = plan ?: TimeoutPlan(defaultTimeouts(t.id))
        val usedTo = IntArray(2)
        val boostUntil = IntArray(2) { -1 }
        val winStreak = IntArray(2)
        // Momentum and timeouts scale the players' ratings for a round (originals kept here).
        val basePhase = Array(2) { s -> teams[s].players.map { it.phase.copyOf() } }
        val smap = (mapId ?: t.maps.firstOrNull()?.id)?.let { dev.uem.em.ui.Maps.shooter(it) }
        if (rec != null && smap != null) { rec.step = 1; rec.posN = teams[0].players.size + teams[1].players.size + 1 }
        var clock = 0
        val ultPts = Array(2) { IntArray(t.teamSize) }
        val events = rec?.events
        // Events for the 2D view: t = round number; x = site (0 A, 1 B).
        val cfg = t.tactical!!
        val defEdge = cfg.defenderEdge + (patch?.obj?.get("defender") ?: 0.0)
        val fullBuy = cfg.fullBuy * (patch?.obj?.get("fullbuy") ?: 1.0)
        val book = StatBook(teams, t)
        val K = book.idx("k"); val D = book.idx("d"); val A = book.idx("a"); val DMG = book.idx("dmg"); val FK = book.idx("fk"); val FD = book.idx("fd")
        val CLUTCH = book.idx("clutch"); val PLANT = book.idx("plant"); val DEFUSE = book.idx("defuse"); val KAST = book.idx("kast"); val HS = book.idx("hs"); val MK = book.idx("mk"); val UTIL = book.idx("util")
        val AIM = Phase.AIM.ordinal; val ENTRY = Phase.ENTRY.ordinal; val CL = Phase.CLUTCH.ordinal; val MOV = Phase.MOVEMENT.ordinal; val UT = Phase.UTILITY.ordinal; val VI = Phase.VISION.ordinal
        val n = t.teamSize
        // Money and inventories player by player (real prices, rewards, kept guns): see ShooterEconomy.
        val bank = ShooterEconomy.Bank(t, n, seedKey)
        val money = bank.money
        /** Consecutive rounds lost (coaches call timeouts on streaks). */
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

        fun buyFor(ti: Int, pistol: Boolean): Pair<Double, String> = bank.teamBuy(ti, pistol, econ[ti] / 100, teams[ti].aggression / 100, fullBuy, rng, ti == attack)
        rec?.pids = (teams[0].players + teams[1].players).map { it.p.id }.toIntArray()
        fun duel(pc: PlayerCtx, initiating: Boolean) = pc.phase[AIM] * 0.68 + (if (initiating) pc.phase[ENTRY] else pc.phase[CL]) * 0.2 + pc.phase[MOV] * 0.12

        while (true) {
            rng = Rng.derive(seedKey, round)
            val def = 1 - attack
            // Tactical timeouts: before this round, by the user's call or the coach's judgement.
            for (s0 in 0..1) {
                if (usedTo[s0] >= tp.allowed || round == 0) continue
                val manual = tp.manual.any { it.first == s0 && it.second == round }
                val mapPoint = !ot && score[1 - s0] == cfg.roundsToWin - 1 && score[s0] < cfg.roundsToWin - 1
                val auto = tp.auto[s0] && ((streak[s0] >= 3 && rng.chance(0.5 + tp.coachQ[s0] / 300)) || (mapPoint && rng.chance(0.3)))
                if (manual || auto) {
                    usedTo[s0]++
                    boostUntil[s0] = round + 1
                    winStreak[1 - s0] = 0 // breaks the opponents' momentum
                    events?.add(Ev(if (smap != null) clock else round, "timeout", s0, a = tp.allowed - usedTo[s0], n = round))
                }
            }
            for (s0 in 0..1) {
                val f = 1.0 + (if (winStreak[s0] >= 3) 0.015 else 0.0) + (if (boostUntil[s0] >= round) 0.012 + 0.02 * tp.coachQ[s0] / 100 else 0.0)
                teams[s0].players.forEachIndexed { i, pc -> val b = basePhase[s0][i]; for (k in b.indices) pc.phase[k] = b[k] * f }
            }
            val pistol = (round == 0 || round == cfg.half) && !ot
            val bA = buyFor(attack, pistol)
            val bD = buyFor(def, pistol)
            val qA = if (ot) 1.0 else bA.first
            val qD = if (ot) 1.0 else bD.first
            // Every player's purchases (weapon, armour, utility, kit), logged for the box score.
            val purchases = bank.buyRound(round, attack, Array(2) { s -> if (s == attack) bA.second else bD.second }, teams, ultPts, rng)
            var defusedNow = false
            val alive = arrayOf(teams[0].players.toMutableList(), teams[1].players.toMutableList())
            val roundKills = HashMap<Int, Int>()
            val assisted = HashSet<Int>()
            val traded = HashSet<Int>()
            var planted = false
            var first = true
            var clutchSide = -1
            var clutchPlayer: PlayerCtx? = null
            var rw: Int
            var how = 0; var siteNow = -1
            if (smap != null) {
                // Positional round on the real map.
                val loadout = Array(2) { s -> Array(teams[s].players.size) { i -> purchases[s][i].weapon to ShooterEconomy.armorValue(t.id == "cs2", purchases[s][i].armor) } }
                val utility = Array(2) { s -> Array(teams[s].players.size) { i -> purchases[s][i].util } }
                val armor = Array(2) { s -> IntArray(teams[s].players.size) { i -> loadout[s][i].second } }
                val kit = Array(2) { s -> BooleanArray(teams[s].players.size) { i -> s != attack && (purchases[s][i].kit || (t.id != "cs2" && (if (s == attack) bA.second else bD.second) == "full")) } }
                events?.add(Ev(clock, "round", attack, x = 0, a = score[0], b = score[1], text = bA.second + "|" + bD.second, m = ReplayCodec.BUYS.indexOf(bA.second) * 4 + ReplayCodec.BUYS.indexOf(bD.second), n = round))
                if (rec != null) {
                    val v = IntArray(rec.pids.size * ReplayCodec.PER_PLAYER)
                    var k = 0
                    for (s in 0..1) for (i in 0 until teams[s].players.size) { v[k++] = money[s].getOrElse(i) { 0.0 }.toInt(); v[k++] = ((if (s == attack) qA else qD) * 100).toInt(); v[k++] = Weapons.of(t.id).indexOf(loadout[s][i].first); v[k++] = armor[s][i] }
                    rec.snapshot(clock, v)
                }
                val host = object : ShooterRound.Host {
                    override fun kill(ks: Int, killer: PlayerCtx, victim: PlayerCtx, assister: PlayerCtx?, headshot: Boolean, where: Int, damage: Double) {
                        val vs = 1 - ks
                        alive[vs].remove(victim)
                        book.add(killer.p.id, K, 1.0); book.add(victim.p.id, D, 1.0)
                        if (headshot) book.add(killer.p.id, HS, 1.0)
                        roundKills[killer.p.id] = (roundKills[killer.p.id] ?: 0) + 1
                        if (first) { book.add(killer.p.id, FK, 1.0); book.add(victim.p.id, FD, 1.0); first = false }
                        if (assister != null) { book.add(assister.p.id, A, 1.0); assisted.add(assister.p.id) }
                        // A kill by a teammate of someone the victim just killed is a trade.
                        if ((roundKills[victim.p.id] ?: 0) > 0) for (pc in teams[ks].players) if (pc !in alive[ks]) traded.add(pc.p.id)
                        val ki = teams[ks].players.indexOf(killer)
                        if (ki >= 0) { bank.onKill(ks, ki, purchases[ks].getOrNull(ki)?.weapon?.id); ultPts[ks][ki]++ }
                        val vi = teams[vs].players.indexOf(victim)
                        if (vi >= 0) ultPts[vs][vi]++
                        for (s in 0..1) if (alive[s].size == 1 && alive[1 - s].size >= 2 && clutchSide < 0) { clutchSide = s; clutchPlayer = alive[s][0] }
                    }
                    override fun plant(p: PlayerCtx) { book.add(p.p.id, PLANT, 1.0); val pi = teams[attack].players.indexOf(p); bank.onPlant(attack, pi); if (pi >= 0) ultPts[attack][pi]++ }
                    override fun defuse(p: PlayerCtx) { book.add(p.p.id, DEFUSE, 1.0); teams[def].players.indexOf(p).let { if (it >= 0) ultPts[def][it]++ } }
                    override fun utility(p: PlayerCtx) { book.add(p.p.id, UTIL, 1.0) }
                    override fun damage(p: PlayerCtx, d: Double) { if (d > 0) book.add(p.p.id, DMG, d) }
                }
                val r = ShooterRound(t, smap, teams, attack, rng, Array(2) { s -> Array(teams[s].players.size) { i -> loadout[s][i].first } }, armor, utility, kit, clock, rec, host).play()
                planted = r.planted
                rw = r.winner
                defusedNow = r.defused
                how = r.how; siteNow = r.site
                events?.let { e -> val k = e.indexOfLast { it.kind == "round" }; if (k >= 0) { val o = e[k]; e[k] = Ev(o.t, o.kind, o.team, o.a, o.b, r.site, o.text, o.m, o.n) } }
                if (r.defused) events?.let { } // recorded by the round
                clock += r.seconds + 6
            } else {
            events?.add(Ev(round, "round", attack, x = (round * 7 + score[0] * 3 + score[1]) % 2, a = score[0], b = score[1], text = bA.second + "|" + bD.second, m = ReplayCodec.BUYS.indexOf(bA.second) * 4 + ReplayCodec.BUYS.indexOf(bD.second)))
            var duels = 0
            val baseEdge = -defEdge + (qA - qD) * 1.7 + (teamUtil[attack] - teamUtil[def]) / 60 + (teamInfo[attack] - teamInfo[def]) / 80 + (igl[attack] - igl[def]) / 80 + (comp[attack].total - comp[def].total) * 0.4
            val swing = rng.normal(0.0, 0.32)
            val site = (round * 7 + score[0] * 3 + score[1]) % 2
            if (rec != null) {
                // Credits after the buy and loadout quality, per player.
                val v = IntArray(rec.pids.size * ReplayCodec.PER_PLAYER)
                var k = 0
                for (s in 0..1) for (i in 0 until teams[s].players.size) {
                    v[k++] = money[s].getOrElse(i) { 0.0 }.toInt()
                    v[k++] = ((if (s == attack) qA else qD) * 100).toInt()
                    v[k++] = 0
                    v[k++] = 0
                }
                rec.snapshot(round, v)
            }
            fun kill(ks: Int, killer: PlayerCtx, victim: PlayerCtx) {
                val vs = 1 - ks
                alive[vs].remove(victim)
                val where = if (planted) ReplayCodec.LOC_POSTPLANT else if (duels <= 1) ReplayCodec.LOC_APPROACH else ReplayCodec.LOC_SITE
                var assister = -1
                book.add(killer.p.id, K, 1.0)
                book.add(victim.p.id, D, 1.0)
                book.add(killer.p.id, DMG, 100.0 + rng.int(0, 60))
                if (rng.chance(clamp(0.25 + killer.p.attrs[CROSSHAIR] / 100 * 0.45, 0.2, 0.75))) book.add(killer.p.id, HS, 1.0)
                roundKills[killer.p.id] = (roundKills[killer.p.id] ?: 0) + 1
                if (first) { book.add(killer.p.id, FK, 1.0); book.add(victim.p.id, FD, 1.0); first = false }
                val mates = alive[ks].filter { it !== killer }
                if (mates.isNotEmpty() && rng.chance(0.42)) {
                    val asst = rng.pickCtx(mates) { it.phase[UT] }
                    assister = asst.p.id
                    book.add(asst.p.id, A, 1.0)
                    assisted.add(asst.p.id)
                    if (rng.chance(0.4)) book.add(asst.p.id, UTIL, 1.0)
                }
                events?.add(Ev(round, "kill", ks, killer.p.id, victim.p.id, if (planted) 1 else 0, m = where, n = assister))
                val ki = teams[ks].players.indexOf(killer)
                if (ki >= 0) bank.onKill(ks, ki, purchases[ks].getOrNull(ki)?.weapon?.id)
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
                    val planter = rng.pickCtx(alive[attack]) { if (it.role.isSupport) 2.0 else 1.0 }
                    book.add(planter.p.id, PLANT, 1.0)
                    bank.onPlant(attack, teams[attack].players.indexOf(planter))
                }
            }
            rw = when {
                alive[def].isEmpty() -> attack
                alive[attack].isEmpty() -> if (planted) {
                    if (rng.chance(clamp(0.9 - (if (alive[def].size == 1) 0.12 else 0.0), 0.5, 0.97))) { book.add(rng.pickCtx(alive[def]) { it.phase[CL] }.p.id, DEFUSE, 1.0); events?.add(Ev(round, "defuse", def)); defusedNow = true; def } else attack
                } else def
                else -> if (planted) attack else def
            }
            how = when { alive[def].isEmpty() -> 0; defusedNow -> 2; planted -> 1; alive[attack].isEmpty() -> 0; else -> 3 }
            siteNow = site
            }
            score[rw]++
            events?.add(Ev(if (smap != null) clock - 6 else round, "roundEnd", rw, a = score[0], b = score[1]))
            if (clutchSide == rw) clutchPlayer?.let { book.add(it.p.id, CLUTCH, 1.0) }
            for (s in 0..1) for (pc in teams[s].players) {
                val k = roundKills[pc.p.id] ?: 0
                if (k > 0 || pc.p.id in assisted || pc in alive[s] || pc.p.id in traded) book.add(pc.p.id, KAST, 1.0)
                if (k >= 3) book.add(pc.p.id, MK, 1.0)
            }
            val lr = 1 - rw
            // Rewards (win, bomb / defuse, loss streak, plant bonus) and what the survivors keep.
            val bombWin = (rw == attack && planted && alive[def].isNotEmpty()) || (rw == def && defusedNow)
            bank.endRound(rw, attack, Array(2) { s -> teams[s].players.indices.filter { teams[s].players[it] in alive[s] }.toSet() }, planted, bombWin, how, siteNow)
            winStreak[rw]++; winStreak[lr] = 0
            streak[lr]++; streak[rw] = 0
            round++
            val target = cfg.roundsToWin
            if (!ot) {
                if (score[0] >= target || score[1] >= target) break
                if (score[0] == target - 1 && score[1] == target - 1) {
                    ot = true
                    otStart = score.copyOf()
                    otRound = 0
                    bank.fill(if (t.id == "cs2") 12500.0 else 5000.0)
                }
                if (round == cfg.half) {
                    attack = 1 - attack
                    bank.resetHalf(); for (s in 0..1) { winStreak[s] = 0; streak[s] = 0 }
                }
            } else {
                otRound++
                if (cfg.otWinByTwo) {
                    attack = 1 - attack
                    if (abs(score[0] - score[1]) >= 2 || otRound > 40) break
                } else {
                    val half = cfg.otRounds / 2
                    if (score[0] - otStart[0] >= half + 1 || score[1] - otStart[1] >= half + 1) break
                    if (otRound % half == 0) { attack = 1 - attack; bank.fill(12500.0) }
                    if (otRound % cfg.otRounds == 0) otStart = score.copyOf()
                    if (otRound > 60) break
                }
            }
        }
        for (s0 in 0..1) teams[s0].players.forEachIndexed { i, pc -> basePhase[s0][i].copyInto(pc.phase) }
        val winner = if (score[0] > score[1]) 0 else if (score[1] > score[0]) 1 else if (rng.chance(0.5)) 0 else 1
        val rounds = score[0] + score[1]
        book.finalise()
        // Each role's ratings are calibrated like the MOBAs' (see StatBook.calibrate).
        book.metaWorld = world
        book.rate(winner) { l ->
            0.4 + book.get(l, K) - book.get(l, D) * 0.55 + book.get(l, A) * 0.3 + book.get(l, KAST) / maxOf(1, rounds) * 2.2 + book.get(l, FK) * 0.4 - book.get(l, FD) * 0.2 + book.get(l, CLUTCH) * 0.9 + book.get(l, MK) * 0.5 + book.get(l, DMG) / 1200
        }
        return GameOutcome(winner, if (smap != null) clock else rounds * 105, score, book, bank.econ())
    }
}

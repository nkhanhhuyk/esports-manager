package dev.uem.em.sim

import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.core.logistic
import dev.uem.em.titles.Objective
import dev.uem.em.titles.Phase
import dev.uem.em.titles.TitleDef
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tanh

/** One simulated game's outcome. Team index 0/1 is the series' team order. */
class GameOutcome(val winner: Int, val duration: Int, val score: IntArray, val book: StatBook)

/**
 * Fast MOBA engine, ported from the web version (same tuning as its kill-realism pass): laning gold
 * swings, jungle ganks, objective contests and fights, structures and the core, comeback bounties,
 * Aegis and buyback (Dota 2). Teams are given in map-side order (0 = blue / Radiant).
 */
object MobaEngine {
    private class Tune(val kill: Double, val pace: Double)
    private val TUNE = mapOf("mlbb" to Tune(1.35, 1.0), "wildrift" to Tune(1.3, 1.0), "hok" to Tune(1.6, 0.55), "lol" to Tune(0.7, 1.15), "dota2" to Tune(0.9, 1.8))
    private const val CS_VALUE = 22.0

    private class Side(val tc: TeamCtx, lanes: Int) {
        val gold = DoubleArray(tc.players.size) { 500.0 }
        var kills = 0
        val laneDown = IntArray(lanes)
        var baseDown = 0
        val stacks = HashMap<String, Int>()
        var soul = 0.0
        var pushUntil = -1.0
        var pushValue = 0.0
        var executeUntil = -1.0
        var executeValue = 0.0
        var aegis = false
        var buff = 0.0
        val bbReadyAt = DoubleArray(tc.players.size)
        var objectives = 0
        var structures = 0
        var coreDamage = 0
        fun goldSum() = gold.sum()
    }

    fun simulate(t: TitleDef, teams: List<TeamCtx>, comp: Array<CompEval>, rng: Rng, patch: Patch? = null): GameOutcome {
        val cfg = t.moba!!
        val tune = TUNE[t.id] ?: Tune(1.0, 1.0)
        val book = StatBook(teams, t)
        val K = book.idx("k"); val D = book.idx("d"); val A = book.idx("a"); val GOLD = book.idx("gold"); val CS = book.idx("cs"); val DMG = book.idx("dmg")
        val TAKEN = book.idx("taken"); val HEAL = book.idx("heal"); val VISION = book.idx("vision"); val OBJ = book.idx("obj"); val TOWERS = book.idx("towers"); val DN = book.idx("dn"); val BB = book.idx("bb")
        val st = cfg.structures
        val sides = arrayOf(Side(teams[0], st.lanes), Side(teams[1], st.lanes))
        val TF = Phase.TEAMFIGHT.ordinal; val SK = Phase.SKIRMISH.ordinal; val CL = Phase.CLUTCH.ordinal; val MA = Phase.MACRO.ordinal
        val JG = Phase.JUNGLE.ordinal; val VI = Phase.VISION.ordinal; val LA = Phase.LANING.ordinal; val EC = Phase.ECONOMY.ordinal
        var winner = -1
        var endT = 0

        fun goldLead(i: Int): Double { val a = sides[i].goldSum(); val b = sides[1 - i].goldSum(); return (a - b) / maxOf(1.0, a + b) }

        fun fightPower(s: Side, minute: Double): Double {
            val tc = s.tc
            val base = tc.phase[TF] * 0.6 + tc.phase[SK] * 0.2 + tc.phase[CL] * 0.1 + tc.phase[MA] * 0.1
            val c = comp[tc.idx]
            val phaseT = clamp(minute / cfg.avgLength, 0.0, 1.5)
            val early = c.prof("early") - 5
            val late = c.prof("scaling", "farm") - 5
            val compMod = 1 + c.total * 0.07 + (early * (1 - phaseT) + late * phaseT) * 0.008 + (c.prof("teamfight", "fight") - 5) * 0.004
            var stack = s.soul
            for ((id, n) in s.stacks) cfg.objectives.firstOrNull { it.id == id }?.let { stack += n * it.value }
            val exec = if (minute <= s.executeUntil) s.executeValue else 0.0
            return base * compMod * (1 + stack + exec + s.buff)
        }

        fun awardKill(killerSide: Int, victim: PlayerCtx, minute: Double, participants: List<PlayerCtx>) {
            val ks = sides[killerSide]
            val killer = rng.pickCtx(participants) { it.dmgW * it.phase[TF] }
            book.add(killer.p.id, K, 1.0)
            for (pc in participants) if (pc !== killer && rng.chance(0.65)) book.add(pc.p.id, A, 1.0)
            book.add(victim.p.id, D, 1.0)
            ks.kills++
            val lead = goldLead(killerSide)
            val bounty = 300 * (1 + maxOf(0.0, -lead) * cfg.comeback * 4) * (1 - maxOf(0.0, lead) * cfg.comeback)
            ks.gold[ks.tc.players.indexOf(killer)] += bounty * 0.6
            for (pc in participants) ks.gold[ks.tc.players.indexOf(pc)] += bounty * 0.4 / participants.size
        }

        fun pushStructures(side: Int, minute: Double, n: Int) {
            val att = sides[side]
            val def = sides[1 - side]
            var k = 0
            while (k < n && winner < 0) {
                k++
                val open = (0 until st.lanes).filter { def.laneDown[it] < st.perLane }
                val anyDown = def.laneDown.any { it >= st.perLane }
                if (open.isNotEmpty() && !(anyDown && rng.chance(0.45))) {
                    val lane = rng.weighted(open) { 1 + def.laneDown[it] * 1.5 }
                    def.laneDown[lane]++
                    att.structures++
                    book.add(rng.pickCtx(att.tc.players) { it.base.phaseS[MA] }.p.id, TOWERS, 1.0)
                    continue
                }
                if (anyDown) {
                    if (def.baseDown < st.base) { def.baseDown++; att.structures++ }
                    else {
                        def.coreDamage++
                        if (def.coreDamage >= 2 + (if (t.id == "dota2") 1 else 0)) { winner = side; endT = (minute * 60).toInt() }
                    }
                }
            }
        }

        fun teamfight(minute: Double): Int {
            val pa = fightPower(sides[0], minute) * (1 + goldLead(0) * (1.1 - cfg.comeback * 0.6))
            val pb = fightPower(sides[1], minute) * (1 + goldLead(1) * (1.1 - cfg.comeback * 0.6))
            val aggr = (sides[0].tc.aggression - sides[1].tc.aggression) / 400
            val p = logistic(ln(pa / pb) * 4.5 + aggr)
            val win = if (rng.chance(p)) 0 else 1
            val dom = abs(p - 0.5) * 2
            val w = sides[win]
            val l = sides[1 - win]
            var wKills = (if (rng.chance(minOf(1.0, tune.kill))) 1 else 0) + (0 until 3).count { rng.chance(minOf(0.95, (0.25 + 0.35 * dom) * tune.kill)) }
            var lKills = (0 until 2).count { rng.chance(minOf(0.95, 0.3 * (1 - dom * 0.6) * tune.kill)) }
            wKills = minOf(wKills, l.tc.players.size)
            lKills = minOf(lKills, w.tc.players.size - 1)
            if (l.aegis && wKills > 0) { wKills--; l.aegis = false }
            val wp = w.tc.players
            val lp = l.tc.players
            val victimsL = rng.sample(lp, wKills)
            for (v in victimsL) awardKill(win, v, minute, rng.sample(wp, minOf(wp.size, rng.int(2, wp.size))))
            for (v in rng.sample(wp, lKills)) awardKill(1 - win, v, minute, rng.sample(lp, minOf(lp.size, rng.int(1, lp.size))))
            for ((s, mult) in listOf(w to 1.1, l to 0.9)) for (pc in s.tc.players) {
                val base = 2200 * (0.6 + minute / cfg.avgLength)
                book.add(pc.p.id, DMG, base * pc.dmgW * mult * rng.float(0.7, 1.3))
                if (TAKEN >= 0) book.add(pc.p.id, TAKEN, base * 0.75 * pc.tankW * rng.float(0.7, 1.3))
                book.add(pc.p.id, HEAL, base * 0.8 * pc.healW * rng.float(0.6, 1.4))
            }
            var window = wKills - lKills + dom * 2 + (minute / cfg.avgLength) * cfg.respawnGrowth * 1.5 - 1
            if (cfg.buyback && window > 1.5 && minute > cfg.laneEnd) {
                val threatened = l.laneDown.any { it >= st.perLane - 1 } || minute > cfg.avgLength * 0.75
                if (threatened) for (v in victimsL) {
                    val vi = l.tc.players.indexOf(v)
                    val cost = 200 + 60 * minute
                    if (vi < 0 || l.bbReadyAt[vi] > minute || l.gold[vi] < cost + 1500) continue
                    if (!rng.chance(0.35 + v.base.phaseC[CL] / 250)) continue
                    l.gold[vi] -= cost
                    l.bbReadyAt[vi] = minute + 8
                    book.add(v.p.id, BB, 1.0)
                    window -= 0.9
                }
            }
            if (window > 1.5 && minute > cfg.laneEnd * 0.8) pushStructures(win, minute, (window * 0.4 * tune.pace + (if (minute <= w.pushUntil) 1 else 0)).roundToInt())
            return win
        }

        fun takeObjective(o0: Objective, side: Int, minute: Double) {
            val mod = patch?.objValue(o0.id) ?: 1.0
            val o = if (mod == 1.0) o0 else Objective(o0.id, o0.name, o0.spawn, o0.respawn, o0.until, o0.kind, if (o0.kind == "aegis") o0.value else o0.value * mod, o0.maxStacks, o0.soul * mod, o0.major)
            val s = sides[side]
            s.objectives++
            val jg = s.tc.players.firstOrNull { it.roleId in cfg.junglers } ?: s.tc.players[0]
            book.add(jg.p.id, OBJ, 1.0)
            when (o.kind) {
                "gold" -> for (i in s.gold.indices) s.gold[i] += o.value
                "stack" -> { val n = (s.stacks[o.id] ?: 0) + 1; s.stacks[o.id] = n; if (o.maxStacks > 0 && n == o.maxStacks && o.soul > 0) s.soul += o.soul }
                "push", "siege" -> {
                    s.pushUntil = minute + (if (o.kind == "push") 3.0 else 1.5)
                    s.pushValue = o.value
                    s.buff = maxOf(s.buff, if (o.kind == "push") o.value * 0.15 else 0.0)
                    pushStructures(side, minute, if (o.kind == "push") 1 + (o.value * 4 * rng.float(0.5, 1.3)).roundToInt() else if (minute > cfg.laneEnd && rng.chance(o.value * 1.6)) 1 else 0)
                }
                "aegis" -> s.aegis = true
                "execute" -> { s.executeUntil = minute + 3; s.executeValue = o.value }
                "xp" -> for (i in s.gold.indices) s.gold[i] += 120.0
                "buff" -> s.buff += o.value * 0.5
            }
        }

        fun contest(o: Objective, minute: Double) {
            fun ctrl(s: Side) = s.tc.phase[JG] * 0.32 + s.tc.phase[MA] * 0.25 + s.tc.phase[VI] * 0.2 + s.tc.phase[SK] * 0.23
            val diff = (ctrl(sides[0]) - ctrl(sides[1])) / 10 + goldLead(0) * 2.2 + (comp[0].total - comp[1].total) * 0.5
            val fightChance = (0.2 + (sides[0].tc.aggression + sides[1].tc.aggression) / 500 + (if (o.major) 0.25 else 0.0) - abs(diff) * 0.08) * minOf(1.0, tune.kill)
            if (rng.chance(clamp(fightChance, 0.1, 0.85))) {
                val w = teamfight(minute)
                if (winner >= 0) return
                takeObjective(o, if (rng.chance(0.88)) w else 1 - w, minute)
            } else takeObjective(o, if (rng.chance(logistic(diff))) 0 else 1, minute)
        }

        val tickSec = if (cfg.avgLength > 25) 60 else 30
        val tickMin = tickSec / 60.0
        val nextSpawn = HashMap<String, Double>().apply { for (o in cfg.objectives) put(o.id, o.spawn) }
        val laneRoles = cfg.lanes.map { it.second }
        var tt = tickSec
        while (winner < 0) {
            val minute = tt / 60.0
            endT = tt
            for (i in 0..1) {
                val s = sides[i]
                s.tc.players.forEachIndexed { pi, pc ->
                    val eff = 0.85 + 0.3 * (pc.base.phaseC[EC] / 100)
                    val share = if (pc.role.isCarry) 1.25 else if (pc.role.isSupport) 0.6 else 1.0
                    val inc = cfg.gpm * tickMin * eff * share
                    s.gold[pi] += inc
                    book.add(pc.p.id, CS, inc * 0.65 / CS_VALUE)
                    book.add(pc.p.id, VISION, tickMin * (pc.role.phaseArr[VI].takeIf { it > 0 } ?: 0.05) * 6 * (pc.phase[VI] / 70))
                }
            }
            if (minute <= cfg.laneEnd) {
                for (roles in laneRoles) {
                    val la = sides[0].tc.players.filter { it.roleId in roles }
                    val lb = sides[1].tc.players.filter { it.roleId in roles }
                    if (la.isEmpty() || lb.isEmpty()) continue
                    val ra = la.sumOf { it.phase[LA] } / la.size
                    val rb = lb.sumOf { it.phase[LA] } / lb.size
                    val d = (ra - rb) / 9 + (sides[0].tc.phase[JG] - sides[1].tc.phase[JG]) / 40 + (comp[0].counter - comp[1].counter) * 0.25
                    val shift = tanh(d) * 0.14
                    for (pc in la) {
                        sides[0].gold[sides[0].tc.players.indexOf(pc)] += cfg.gpm * tickMin * shift
                        if (cfg.denies && rng.chance(0.5 + shift)) book.add(pc.p.id, DN, rng.int(0, 2).toDouble())
                    }
                    for (pc in lb) {
                        sides[1].gold[sides[1].tc.players.indexOf(pc)] -= cfg.gpm * tickMin * shift
                        if (cfg.denies && rng.chance(0.5 - shift)) book.add(pc.p.id, DN, rng.int(0, 2).toDouble())
                    }
                    val aggr = (sides[0].tc.aggression + sides[1].tc.aggression) / 100
                    if (rng.chance(0.018 * tickMin * 2 * (1 + abs(d) * 1.5) * aggr * tune.kill)) {
                        val ws = if (rng.chance(logistic(d * 1.4))) 0 else 1
                        awardKill(ws, rng.pick(if (ws == 0) lb else la), minute, if (ws == 0) la else lb)
                    }
                }
                if (minute >= 2) for (i in 0..1) {
                    val s = sides[i]
                    val o = sides[1 - i]
                    val jg = s.tc.players.firstOrNull { it.roleId in cfg.junglers } ?: continue
                    val rate = 0.08 * tickMin * 2 * (0.6 + jg.base.phaseS[JG] / 120) * (0.7 + s.tc.aggression / 160) * tune.kill
                    if (!rng.chance(rate)) continue
                    val targets = o.tc.players.filter { it.roleId !in cfg.junglers }
                    if (targets.isEmpty()) continue
                    val victim = rng.pick(targets)
                    val laner = s.tc.players.firstOrNull { pc -> cfg.lanes.any { pc.roleId in it.second && victim.roleId in it.second } } ?: jg
                    val succ = logistic((jg.phase[JG] + laner.phase[LA] - victim.phase[VI] * 0.6 - o.tc.phase[VI] * 0.4 - victim.phase[LA] * 0.6) / 9 + comp[i].total * 0.4 + 0.15)
                    if (rng.chance(succ)) awardKill(i, victim, minute, listOf(jg, laner).distinct())
                    else if (rng.chance(0.3)) awardKill(1 - i, jg, minute, listOf(victim))
                }
            }
            for (o in cfg.objectives) {
                if (winner >= 0) break
                if (o.until != null && minute > o.until) continue
                if (minute < nextSpawn[o.id]!!) continue
                if (!rng.chance(0.55)) continue
                contest(o, minute)
                nextSpawn[o.id] = minute + o.respawn * (patch?.objRespawn(o.id) ?: 1.0)
            }
            if (winner >= 0) break
            if (minute > cfg.laneEnd) {
                val aggr = (sides[0].tc.aggression + sides[1].tc.aggression) / 100
                if (rng.chance(0.17 * tickMin * 2 * aggr * tune.kill)) {
                    val sa = sides[0].tc.phase[SK] * (1 + comp[0].total * 0.1) * (1 + (comp[0].prof("pick") - 5) * 0.01)
                    val sb = sides[1].tc.phase[SK] * (1 + comp[1].total * 0.1) * (1 + (comp[1].prof("pick") - 5) * 0.01)
                    val w = if (rng.chance(logistic((sa - sb) / 9 + goldLead(0) * 1.6))) 0 else 1
                    awardKill(w, rng.pick(sides[1 - w].tc.players), minute, rng.sample(sides[w].tc.players, 2))
                    if (rng.chance(0.25)) pushStructures(w, minute, 1)
                }
                if (winner < 0 && rng.chance(0.14 * tickMin * 2 * aggr * (1 + minute / cfg.avgLength) * tune.kill)) teamfight(minute)
                for (i in 0..1) {
                    if (winner >= 0) break
                    val s = sides[i]
                    val pressure = (s.tc.phase[MA] - sides[1 - i].tc.phase[MA]) / 25 + goldLead(i) * 2.5 + (comp[i].prof("split") - 5) * 0.05 + (if (minute <= s.pushUntil) s.pushValue * 2 else 0.0)
                    if (rng.chance(clamp((0.42 * tickMin / cfg.avgLength) * (1 + pressure) * tune.pace, 0.0, 0.6))) pushStructures(i, minute, 1)
                }
            }
            if (minute >= cfg.maxLength && winner < 0) {
                val w = teamfight(minute)
                if (winner < 0) pushStructures(w, minute, 6)
                if (winner < 0) { winner = w; endT = tt }
            }
            tt += tickSec
        }
        for (s in sides) s.tc.players.forEachIndexed { pi, pc -> book.add(pc.p.id, GOLD, s.gold[pi]) }
        book.finalise()
        book.rate(winner) { l ->
            val k = book.get(l, K); val d = book.get(l, D); val a = book.get(l, A)
            (k * 3 + a * 1.5 + 1) / (d * 2 + 2) + book.get(l, GOLD) / 1000 * 0.25 + book.get(l, DMG) / 15000 + book.get(l, HEAL) / 20000 + book.get(l, OBJ) * 0.4 + book.get(l, TOWERS) * 0.3 + book.get(l, VISION) / 40
        }
        return GameOutcome(winner, endT, intArrayOf(sides[0].kills, sides[1].kills), book)
    }
}

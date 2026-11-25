package dev.uem.em.sim

import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.core.logistic
import dev.uem.em.titles.Phase
import dev.uem.em.titles.TitleDef
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fast simulation (Settings → Simulation detail → Fast): matches no one is watching and that keep
 * no replay are played by a quick statistical model instead of the 2D engines. The winner comes
 * from the teams' strength, draft and form; kills, gold, damage, rounds and the other box-score
 * numbers are drawn to match each game's typical values from the full engines. No replay exists for
 * these games. Your matches, followed teams and everything you watch always use the full engines.
 */
object QuickSim {
    /** Share of a hero's ability damage that is magic (its kit). */
    fun magicShareOf(pc: PlayerCtx): Double {
        var mg = 0.0; var all = 0.0
        for (a in Abilities.kit(pc.charId, pc.char).actives) for (f in a.fx) if (f.k == Abilities.DMG && f.dmgType != 2) { all += f.p.toDouble(); if (f.dmgType == 1) mg += f.p.toDouble() }
        val cls = (pc.char?.cls ?: "").lowercase()
        if (Regex("marksman|carry|sniper").containsMatchIn(cls) || pc.role.isCarry) all += maxOf(1.0, all * 0.6)
        return if (all <= 0.0) (if (Regex("mage|nuker").containsMatchIn(cls)) 1.0 else 0.0) else mg / all
    }

    /** Typical kills per game in the full engines (both teams). */
    private val KILLS = mapOf("mlbb" to 28.0, "wildrift" to 30.0, "hok" to 27.0, "lol" to 30.0, "dota2" to 42.0)

    /**
     * Which neutral objectives a fast-simulated game's teams took (the same codes as the full engine):
     * spawned ones only, weighted by how often each is taken, League's drake rule and souls.
     */
    fun quickObjectives(t: TitleDef, world: dev.uem.em.model.World?, count: IntArray, dur: Int, rng: Rng): IntArray {
        val cfg = t.moba ?: return IntArray(0)
        val kinds = dev.uem.em.titles.Neutrals.of(world, t.id)
        val minutes = dur / 60.0
        val avail = cfg.objectives.withIndex().filter { (_, o) -> o.spawn < minutes }
        if (avail.isEmpty()) return IntArray(0)
        fun weight(id: String) = when (id) { "drake", "turtle", "tyrant", "wisdom" -> 3.0; "baron", "lord", "overlord", "shadow", "shadowtyrant", "tormentor" -> 1.5; "roshan" -> 1.2; "elder", "tempest" -> 0.35; else -> 1.0 }
        val drakeKinds = kinds.filter { it.objId == "drake" && !it.killerOnly }
        val seq = ArrayList<dev.uem.em.titles.NeutralDef>()
        if (drakeKinds.isNotEmpty()) {
            val pool = drakeKinds.toMutableList()
            repeat(minOf(3, pool.size)) { seq.add(pool.removeAt(rng.int(0, pool.size - 1))) }
        }
        var drakesTaken = 0
        val drakesBy = IntArray(2); val souls = BooleanArray(2)
        val out = ArrayList<Int>()
        for (side in 0..1) repeat(count[side]) {
            val (idx, o) = rng.weighted(avail) { weight(it.value.id) }
            val kind = when {
                o.id == "drake" && seq.isNotEmpty() -> seq[minOf(drakesTaken++, seq.size - 1)]
                else -> kinds.firstOrNull { it.objId == o.id && !it.killerOnly && it.fromKill <= 1 }
            }
            out.add((if (kind != null) kinds.indexOf(kind) + 1 else 500 + idx) * 2 + side)
            if (o.id == "drake") {
                drakesBy[side]++
                val soulDef = seq.lastOrNull()
                if (soulDef != null && soulDef.soulAt > 0 && drakesBy[side] >= soulDef.soulAt && !souls[side] && !souls[1 - side]) { souls[side] = true; out.add((1000 + kinds.indexOf(soulDef) + 1) * 2 + side) }
            }
        }
        return out.toIntArray()
    }

    fun moba(t: TitleDef, teams: List<TeamCtx>, comp: Array<CompEval>, rng: Rng, world: dev.uem.em.model.World? = null): GameOutcome {
        val cfg = t.moba!!
        val book = StatBook(teams, t)
        fun idx(k: String) = book.idx(k)
        val K = idx("k"); val D = idx("d"); val A = idx("a"); val GOLD = idx("gold"); val CS = idx("cs"); val DMG = idx("dmg"); val TAKEN = idx("taken")
        val HEAL = idx("heal"); val VISION = idx("vision"); val OBJ = idx("obj"); val TOWERS = idx("towers"); val DN = idx("dn")
        val diff = (teams[0].overall - teams[1].overall) + (comp[0].total - comp[1].total) * 8 + rng.normal(0.0, 3.5)
        val p0 = logistic(diff / 5.0)
        val winner = if (rng.chance(p0)) 0 else 1
        val dominance = abs(p0 - 0.5) * 2
        val dur = (cfg.avgLength * 60 * clamp(rng.normal(1.0, 0.15) * (1 - dominance * 0.12), 0.6, 1.6)).roundToInt()
        val total = max(6, ((KILLS[t.id] ?: 28.0) * rng.normal(1.0, 0.22)).roundToInt())
        val share = clamp(0.62 + dominance * 0.12 + rng.normal(0.0, 0.06), 0.5, 0.9)
        val kills = IntArray(2)
        kills[winner] = (total * share).roundToInt(); kills[1 - winner] = total - kills[winner]
        val sk = Phase.SKIRMISH.ordinal; val tf = Phase.TEAMFIGHT.ordinal; val sv = Phase.SURVIVAL.ordinal
        fun jungler(pc: PlayerCtx) = pc.roleId in cfg.junglers
        // Kill share by position from the patch's role meta (the carry first, the support last).
        val prio = dev.uem.em.titles.RoleMeta.killPriority(world, t.id)
        fun killW(pc: PlayerCtx) = (0.25 + 1.3 * (prio[dev.uem.em.titles.RoleMeta.pos(pc.roleId)] ?: 0.6)) * (pc.phase[sk] / 70).let { it * it }
        fun deathW(pc: PlayerCtx) = (if (pc.role.isSupport) 1.3 else 1.0) * (70 / pc.phase[sv].coerceAtLeast(20.0))
        for (side in 0..1) {
            val mine = teams[side].players; val theirs = teams[1 - side].players
            if (mine.isEmpty() || theirs.isEmpty()) continue
            repeat(kills[side]) {
                val killer = rng.pickCtx(mine) { killW(it) }
                val victim = rng.pickCtx(theirs) { deathW(it) }
                book.add(killer.p.id, K, 1.0); book.add(victim.p.id, D, 1.0)
                for (m in mine) if (m !== killer && rng.chance(clamp(0.35 + m.phase[tf] / 250 + (if (m.role.isSupport) 0.15 else 0.0), 0.2, 0.85))) book.add(m.p.id, A, 1.0)
            }
        }
        val minutes = dur / 60.0
        for (side in 0..1) for (pc in teams[side].players) {
            val win = side == winner
            val csPerMin = if (pc.role.isSupport) 1.4 else if (jungler(pc)) (if (t.id == "lol" || t.id == "dota2") 5.6 else 2.2) else if (pc.role.isCarry) 8.6 else 7.6
            val cs = csPerMin * minutes * (0.85 + pc.phase[Phase.LANING.ordinal] / 450) * (if (win) 1.05 else 0.93) * rng.normal(1.0, 0.07)
            book.add(pc.p.id, CS, cs)
            val gold = cfg.gpm * minutes * (if (pc.role.isSupport) 0.62 else if (pc.role.isCarry) 1.2 else 1.0) * (if (win) 1.1 else 0.9) + book.value(pc.p.id, K) * 280 + book.value(pc.p.id, A) * 110
            book.add(pc.p.id, GOLD, gold)
            book.add(pc.p.id, DMG, gold * (if (pc.role.isSupport) 0.55 else if (pc.role.isCarry) 1.25 else 1.0) * rng.normal(1.0, 0.12) * 1.4)
            book.add(pc.p.id, TAKEN, gold * (if (pc.role.isSupport) 0.9 else 1.1) * rng.normal(1.0, 0.15) * 1.2)
            if (HEAL >= 0 && pc.role.isSupport) book.add(pc.p.id, HEAL, minutes * 180 * rng.normal(1.0, 0.3))
            if (MobaEngine.hasWards(t.id)) book.add(pc.p.id, VISION, minutes * (if (pc.role.isSupport) 2.2 else if (jungler(pc)) 1.2 else 0.6))
            if (DN >= 0 && !pc.role.isSupport && !jungler(pc)) book.add(pc.p.id, DN, minutes * 0.4 * rng.normal(1.0, 0.3))
        }
        // Structures and objectives: the winner takes most.
        val objCount = IntArray(2)
        for (side in 0..1) {
            val ps = teams[side].players; if (ps.isEmpty()) continue
            val towers = (if (side == winner) rng.int(6, 10) else rng.int(0, 4)).toDouble()
            repeat(towers.toInt()) { book.add(rng.pickCtx(ps) { if (it.role.isSupport) 0.5 else 1.0 }.p.id, TOWERS, 1.0) }
            val objs = if (side == winner) rng.int(2, 5) else rng.int(0, 2)
            repeat(objs) { book.add((ps.firstOrNull { jungler(it) } ?: ps[0]).p.id, OBJ, 1.0) }
            objCount[side] = objs
        }
        val objTakes = quickObjectives(t, world, objCount, dur, Rng.derive("qobj", t.id, dur, winner, teams[0].players.firstOrNull()?.p?.id ?: 0))
        // Item builds as the game's gold allows (fast mode: no item-by-item decisions, same build logic).
        if (dev.uem.em.titles.Items.hasItems(t.id)) {
            val magic = DoubleArray(2) { s -> teams[s].players.map { magicShareOf(it) }.average().takeIf { v -> !v.isNaN() } ?: 0.3 }
            for ((side, tc) in teams.withIndex()) for (pc in tc.players) {
                val brng = Rng.derive("qbuild", t.id, pc.p.id, pc.charId ?: "", dur)
                val cls = pc.char?.cls ?: ""
                val prof = dev.uem.em.titles.Items.profile(cls, pc.role.isSupport, magicShareOf(pc), tc.players.none { Regex("tank|guardian").containsMatchIn((it.char?.cls ?: "").lowercase()) }, brng)
                val n = (book.value(pc.p.id, GOLD) / (cfg.gpm * 4.2)).toInt().coerceIn(1, 6)
                val items = dev.uem.em.titles.Items.quickBuild(world, t.id, n, prof, magic[1 - side], brng, jungler(pc))
                book.lines.firstOrNull { it.playerId == pc.p.id }?.items?.addAll(items.map { it.id })
            }
        }
        // Rune pages (runes, emblems, arcana, talents) and spells for the box score; the same choices as the full engine makes.
        for (tc in teams) for (pc in tc.players) {
            val line = book.lines.firstOrNull { it.playerId == pc.p.id } ?: continue
            val pg = dev.uem.em.titles.Runes.page(world, t.id, pc.charId ?: "", pc.char?.cls ?: "", pc.role.isSupport, magicShareOf(pc), Rng.derive("qrunes", t.id, pc.p.id, pc.charId ?: ""))
            if (pg != null) line.runes.addAll(pg.entries.map { it.id })
            val laneIdx = if (t.lineup.indexOf(pc.role.id) == 0) 0 else 1
            line.spells.addAll(dev.uem.em.titles.Spells.choose(world, t.id, jungler(pc), laneIdx, pc.role.isSupport, pc.role.isCarry, pc.char?.cls ?: "", Rng.derive("qspells", t.id, pc.p.id, pc.charId ?: "")).map { it.id })
        }
        book.finalise()
        book.metaWorld = world
        book.rate(winner) { l ->
            val kk = book.get(l, K); val d = book.get(l, D); val a = book.get(l, A)
            (kk * 3 + a * 1.5 + 1) / (d * 2 + 2) + book.get(l, GOLD) / 1000 * 0.25 + book.get(l, DMG) / 15000 + book.get(l, HEAL) / 20000 + book.get(l, OBJ) * 0.4 + book.get(l, TOWERS) * 0.3 + book.get(l, VISION) / 40
        }
        return GameOutcome(winner, dur, kills, book, objs = objTakes)
    }

    fun shooter(t: TitleDef, teams: List<TeamCtx>, comp: Array<CompEval>, firstAttack: Int, rng: Rng, world: dev.uem.em.model.World? = null): GameOutcome {
        val cfg = t.tactical!!
        val book = StatBook(teams, t)
        fun idx(k: String) = book.idx(k)
        val K = idx("k"); val D = idx("d"); val A = idx("a"); val DMG = idx("dmg"); val FK = idx("fk"); val FD = idx("fd"); val CLUTCH = idx("clutch")
        val PLANT = idx("plant"); val DEFUSE = idx("defuse"); val KAST = idx("kast"); val HS = idx("hs"); val MK = idx("mk"); val UTIL = idx("util")
        val str = DoubleArray(2) { teams[it].overall + comp[it].total * 6 + rng.normal(0.0, 2.0) }
        val aim = Phase.AIM.ordinal; val entry = Phase.ENTRY.ordinal; val ut = Phase.UTILITY.ordinal
        val score = IntArray(2)
        var attack = firstAttack
        var round = 0
        var ot = false
        val defEdge = if (t.id == "cs2") 0.08 else 0.0
        // The same economy as the full engine: who bought what each round (for the box score).
        val bank = ShooterEconomy.Bank(t, t.teamSize, "quick|${rng.next()}")
        val ult = Array(2) { IntArray(t.teamSize) }
        while (true) {
            val def = 1 - attack
            val pistol = (round == 0 || round == cfg.half) && !ot
            val buys = Array(2) { s -> bank.teamBuy(s, pistol, teams[s].phase[Phase.ECONOMY.ordinal] / 100, teams[s].aggression / 100, cfg.fullBuy.toDouble(), rng, s == attack).second }
            val purchases = bank.buyRound(round, attack, buys, teams, ult, rng)
            val pAtk = logistic((str[attack] - str[def]) / 6.0 - defEdge + rng.normal(0.0, 0.25))
            val rw = if (rng.chance(pAtk)) attack else def
            val rl = 1 - rw
            val alive = arrayOf(teams[0].players.toMutableList(), teams[1].players.toMutableList())
            val roundKills = HashMap<Int, Int>()
            var first = true
            // Kills until the loser is wiped (or time / plant leaves a few alive).
            val loserKills = rng.int(0, 4).coerceAtMost(alive[rl].size - 1).coerceAtLeast(0)
            val winnerKills = if (rng.chance(0.78)) alive[rl].size else rng.int(2, alive[rl].size)
            var lk = 0; var wk = 0
            while ((wk < winnerKills || lk < loserKills) && alive[0].isNotEmpty() && alive[1].isNotEmpty()) {
                val ks = if (lk >= loserKills) rw else if (wk >= winnerKills) rl else if (rng.chance(winnerKills.toDouble() / (winnerKills + loserKills))) rw else rl
                val killer = rng.pickCtx(alive[ks]) { (it.phase[aim] / 70).let { a -> a * a } * (if (first && it.role.phaseArr[entry] > 0) 1.6 else 1.0) }
                val victim = rng.pickCtx(alive[1 - ks]) { 1.0 }
                alive[1 - ks].remove(victim)
                teams[ks].players.indexOf(killer).let { ki -> if (ki >= 0) { bank.onKill(ks, ki, purchases[ks].getOrNull(ki)?.weapon?.id); ult[ks][ki]++ } }
                book.add(killer.p.id, K, 1.0); book.add(victim.p.id, D, 1.0); book.add(killer.p.id, DMG, 100.0 + rng.int(0, 60))
                if (rng.chance(0.45)) book.add(killer.p.id, HS, 1.0)
                if (first) { book.add(killer.p.id, FK, 1.0); book.add(victim.p.id, FD, 1.0); first = false }
                alive[ks].filter { it !== killer }.takeIf { it.isNotEmpty() && rng.chance(0.42) }?.let { m -> val a = rng.pickCtx(m) { it.phase[ut] }; book.add(a.p.id, A, 1.0); book.add(a.p.id, KAST, 0.0) }
                roundKills[killer.p.id] = (roundKills[killer.p.id] ?: 0) + 1
                if (ks == rw) wk++ else lk++
            }
            val planted = (rw == attack && rng.chance(0.55)) || (rw == def && rng.chance(0.25))
            val defused = rw == def && planted
            if (planted) { val pl = rng.pickCtx(teams[attack].players) { 1.0 }; book.add(pl.p.id, PLANT, 1.0); bank.onPlant(attack, teams[attack].players.indexOf(pl)) }
            if (defused) book.add(rng.pickCtx(teams[def].players) { 1.0 }.p.id, DEFUSE, 1.0)
            bank.endRound(rw, attack, Array(2) { s -> teams[s].players.indices.filter { teams[s].players[it] in alive[s] }.toSet() }, planted, (rw == attack && planted) || defused)
            if (alive[rw].size == 1 && loserKills >= 3) book.add(alive[rw][0].p.id, CLUTCH, 1.0)
            for (s in 0..1) for (pc in teams[s].players) {
                val k = roundKills[pc.p.id] ?: 0
                if (k > 0 || pc in alive[s] || rng.chance(0.25)) book.add(pc.p.id, KAST, 1.0)
                if (k >= 3) book.add(pc.p.id, MK, 1.0)
                if (rng.chance(if (t.id == "cs2") 0.6 else 0.35)) book.add(pc.p.id, UTIL, 1.0)
            }
            score[rw]++
            round++
            val target = cfg.roundsToWin
            if (!ot) {
                if (score[0] >= target || score[1] >= target) break
                if (score[0] == target - 1 && score[1] == target - 1) { ot = true; bank.fill(if (t.id == "cs2") 12500.0 else 5000.0) }
                if (round == cfg.half) { attack = 1 - attack; bank.resetHalf() }
            } else {
                attack = 1 - attack
                bank.fill(if (t.id == "cs2") 12500.0 else 5000.0)
                if (abs(score[0] - score[1]) >= 2 || round > 80) break
            }
        }
        val winner = if (score[0] > score[1]) 0 else 1
        val rounds = score[0] + score[1]
        book.finalise()
        book.metaWorld = world
        book.rate(winner) { l ->
            0.4 + book.get(l, K) - book.get(l, D) * 0.55 + book.get(l, A) * 0.3 + book.get(l, KAST) / maxOf(1, rounds) * 2.2 + book.get(l, FK) * 0.4 - book.get(l, FD) * 0.2 + book.get(l, CLUTCH) * 0.9 + book.get(l, MK) * 0.5 + book.get(l, DMG) / 1200
        }
        return GameOutcome(winner, rounds * 105, score, book, bank.econ())
    }
}

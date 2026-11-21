package dev.uem.em.comp

import dev.uem.em.core.Dates
import dev.uem.em.model.BracketNode
import dev.uem.em.model.CompTier
import dev.uem.em.model.Competition
import dev.uem.em.model.Fixture
import dev.uem.em.model.League
import dev.uem.em.model.Stage
import dev.uem.em.model.StageType
import dev.uem.em.model.Standing
import dev.uem.em.model.Trophy
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.TitleDef
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

/**
 * The season calendar (ported from the web version): three league splits per tier-1 / tier-2 league,
 * then three internationals — First Stand after split 1 (hard Fearless in MOBAs), the Mid-Season
 * Invitational after split 2 (double-elimination bracket) and the World Championship after split 3
 * (circuit points decide the seeds).
 */
object Season {
    private class Split(val n: Int, val m: Int, val d: Int, val dows: List<Int>, val double: Boolean)

    private val SPLITS = listOf(Split(1, 1, 9, listOf(5, 6, 0), false), Split(2, 3, 27, listOf(5, 6), true), Split(3, 7, 24, listOf(4, 5, 6, 0), true))
    private val SPLIT_POINTS = listOf(90, 70, 50, 40, 30, 30, 10, 10, 5, 5, 5, 5)
    private val FS_POINTS = listOf(70, 50, 40, 40, 20, 20, 20, 20)
    private val MSI_POINTS = listOf(100, 80, 60, 60, 40, 40, 40, 40, 20, 20, 20, 20, 10, 10, 10, 10)

    fun splitWeight(split: Int) = when (split) { 3 -> 1.5; 2 -> 1.25; else -> 1.0 }
    fun endDay(season: Int) = Dates.day(season, 11, 22)

    val REAL_EVENTS: Map<String, Map<String, Pair<String, String>>> = mapOf(
        "lol" to mapOf("fs" to ("First Stand" to "FST"), "msi" to ("Mid-Season Invitational" to "MSI"), "worlds" to ("League of Legends World Championship" to "Worlds")),
        "mlbb" to mapOf("msi" to ("MLBB Mid Season Cup" to "MSC"), "worlds" to ("MLBB M-Series World Championship" to "M-Series")),
        "valorant" to mapOf("fs" to ("VALORANT Masters (spring)" to "Masters I"), "msi" to ("VALORANT Masters (summer)" to "Masters II"), "worlds" to ("VALORANT Champions" to "Champions")),
        "cs2" to mapOf("msi" to ("Counter-Strike Major (first half)" to "Major I"), "worlds" to ("Counter-Strike Major (second half)" to "Major II")),
        "dota2" to mapOf("worlds" to ("The International" to "TI")),
        "hok" to mapOf("msi" to ("Honor of Kings World Cup" to "KWC"), "worlds" to ("Honor of Kings International Championship" to "KIC")),
    )

    private fun stage(name: String, type: StageType, start: Int, bestOf: Int, dows: List<Int> = listOf(5, 6), groups: Int = 0, advance: Int = 0, daysBetween: Int = 2) =
        Stage(name, type, start, bestOf, dows, groups, advance, daysBetween)

    fun leagueStrength(l: League): Double {
        val pop = Catalogue.POPULARITY[l.titleId]!!
        pop[l.region]?.let { return it.toDouble() }
        val parts = l.region.substringAfter('-', "").split('.').filter { it.isNotEmpty() }
        return if (parts.isEmpty()) 30.0 else parts.maxOf { pop[it] ?: 0 } * 0.85
    }

    private fun allocate(strengths: List<Double>, total: Int, maxPer: Int = 4): IntArray {
        val n = strengths.size
        val slots = IntArray(n) { 1 }
        var left = total - n
        val w = strengths.map { maxOf(1.0, it).pow(1.5) }
        while (left > 0) {
            var best = -1
            var bs = -1.0
            for (i in 0 until n) {
                if (slots[i] >= maxPer) continue
                val s = w[i] / (slots[i] + 0.5)
                if (s > bs) { bs = s; best = i }
            }
            if (best < 0) break
            slots[best]++
            left--
        }
        return slots
    }

    private fun mk(w: World, t: TitleDef, season: Int, name: String, short: String, tier: CompTier, league: League?, entrants: List<String>, stages: List<Stage>, prize: Double, prestige: Int, points: List<Int>, split: Int = 0): Competition {
        val c = Competition(w.comps.size, name, short, t.id, season, tier, league?.id ?: -1, split, league?.region ?: "", entrants, stages.toMutableList(),
            startDay = stages[0].startDay, circuitPoints = points, prizePool = (prize / 1000).toInt() * 1000.0, prestige = prestige)
        w.comps.add(c)
        return c
    }

    private fun matchDays(from: Int, count: Int, dows: List<Int>): List<Int> {
        val out = ArrayList<Int>()
        var d = from
        while (out.size < count) {
            if (Dates.dow(d) in dows) out.add(d)
            d++
        }
        return out
    }

    private fun international(t: TitleDef, n: Int, start: Int): List<Stage> {
        val (std, po, _) = t.formats
        return when {
            n >= 16 -> listOf(Stage("Swiss Stage", StageType.SWISS, start, std.n, listOf(0, 1, 2, 3, 4, 5, 6), advance = 8, daysBetween = 1), stage("Knockout Stage", StageType.SINGLE_ELIM, start + 9, po.n, daysBetween = 3))
            n >= 7 -> listOf(stage("Group Stage", StageType.GROUPS, start, std.n, listOf(0, 1, 2, 3, 4, 5, 6), groups = maxOf(2, Math.round(n / 4.0).toInt()), advance = 2), stage("Knockout Stage", StageType.SINGLE_ELIM, start + 6, po.n, daysBetween = 3))
            else -> listOf(stage("Knockout Stage", StageType.SINGLE_ELIM, start, po.n, daysBetween = 3))
        }
    }

    fun plan(w: World, season: Int) {
        w.titles.forEachIndexed { ti, titleId ->
            val t = Catalogue.get(titleId)
            val off = ti % 3
            fun D(m: Int, d: Int) = Dates.day(season, m, d) + off
            val tier1 = w.leagues.filter { it.titleId == titleId && it.tier == 1 }.sortedByDescending { leagueStrength(it) }
            val tier2 = w.leagues.filter { it.titleId == titleId && it.tier == 2 }
            val splitComps = HashMap<Int, MutableList<Competition>>()
            for (l in tier1 + tier2) {
                val size = l.teamIds.size
                val strength = leagueStrength(l)
                val prizeBase = t.prizeScale * 1_000_000 * (if (l.tier == 1) 0.08 else 0.012) * (0.4 + strength / 100)
                val list = ArrayList<Competition>()
                for (sp in SPLITS) {
                    val dbl = sp.double && size <= 10
                    val rounds = (if (size % 2 == 0) size - 1 else size) * (if (dbl) 2 else 1)
                    val days = matchDays(D(sp.m, sp.d), rounds, sp.dows)
                    val (poType, take) = when {
                        l.tier == 2 || size < 8 -> StageType.SINGLE_ELIM to 4
                        !t.isMoba -> StageType.DOUBLE_ELIM to 8
                        else -> StageType.SINGLE_ELIM to 6
                    }
                    val regAdv = Stage("Regular Season", if (dbl) StageType.DRR else StageType.RR, D(sp.m, sp.d), t.formats.first.n, sp.dows, advance = minOf(take, size))
                    val po = stage("Playoffs", poType, days.last() + 4, t.formats.second.n, daysBetween = if (poType == StageType.DOUBLE_ELIM) 2 else 3)
                    list.add(mk(w, t, season, "${l.name} $season Split ${sp.n}", "${l.short} S${sp.n}", if (l.tier == 1) CompTier.LEAGUE else CompTier.CHALLENGER, l,
                        listOf("league:${l.id}"), listOf(regAdv, po), prizeBase * (if (sp.n == 1) 0.7 else 1.0), if (l.tier == 1) (25 + strength * 0.3).toInt() else 12,
                        if (l.tier == 1) SPLIT_POINTS else emptyList(), sp.n))
                }
                splitComps[l.id] = list
            }
            if (tier1.isEmpty()) return@forEachIndexed
            val real = REAL_EVENTS[titleId]
            fun name(k: String, generic: String, short: String): Triple<String, String, Boolean> =
                real?.get(k)?.let { Triple("${it.first} $season", it.second, true) } ?: Triple("${t.short} $generic $season", "${t.short} $short", false)
            fun topOf(split: Int, two: (Int) -> Boolean) = tier1.mapIndexed { i, l -> "top:${splitComps[l.id]!![split - 1].id}:${if (two(i)) 2 else 1}" }
            fun count(rules: List<String>) = rules.sumOf { it.split(':')[2].toInt() }
            val fsE = topOf(1) { i -> tier1.size <= 3 || i < 2 }.take(8)
            val fsN = name("fs", "First Stand", "FST")
            val fs = mk(w, t, season, fsN.first, fsN.second, CompTier.INTERNATIONAL, null, fsE, international(t, minOf(8, count(fsE)), D(3, 6)), t.prizeScale * 250_000, 60, FS_POINTS)
            fs.realName = fsN.third
            fs.fearless = t.draft.mode == "sequential"
            val msiE = topOf(2) { i -> tier1.size <= 2 || i < 2 || tier1.size <= 4 }
            val msiN = name("msi", "Mid-Season Invitational", "MSI")
            val n = count(msiE)
            val msiStages = if (n > 8) listOf(stage("Play-In Groups", StageType.GROUPS, D(6, 26), t.formats.first.n, listOf(0, 1, 2, 3, 4, 5, 6), groups = 2, advance = 4), stage("Bracket Stage", StageType.DOUBLE_ELIM, D(6, 26) + 6, t.formats.second.n))
            else listOf(stage("Bracket Stage", StageType.DOUBLE_ELIM, D(6, 26), t.formats.second.n))
            mk(w, t, season, msiN.first, msiN.second, CompTier.INTERNATIONAL, null, msiE, msiStages, t.prizeScale * 400_000, 70, MSI_POINTS).realName = msiN.third
            val target = maxOf(4, minOf(16, tier1.size * 4 / 4 * 4))
            val slots = allocate(tier1.map { leagueStrength(it) }, target, 4)
            val wE = tier1.mapIndexed { i, l -> "circuit:${l.id}:${minOf(slots[i], l.teamIds.size)}" }
            val wN = name("worlds", "World Championship", "Worlds")
            mk(w, t, season, wN.first, wN.second, CompTier.WORLDS, null, wE, international(t, count(wE), D(9, 24)), t.prizeScale * 1_000_000, 100, emptyList()).realName = wN.third
        }
    }
}

/** Stage scheduling, brackets and progression. */
object Stages {
    private fun addFixture(w: World, c: Competition, stageIdx: Int, day: Int, a: Int, b: Int, bestOf: Int, round: Int = 0, group: Int = 0, nodeId: String = ""): Fixture {
        val f = Fixture(w.fixtures.size, c.id, stageIdx, c.titleId, day, a, b, bestOf, round, group, nodeId)
        w.fixtures.add(f)
        w.schedule.getOrPut(day) { ArrayList() }.add(f.id)
        c.stages[stageIdx].fixtureIds.add(f.id)
        return f
    }

    private fun matchDays(from: Int, count: Int, dows: List<Int>): List<Int> {
        val out = ArrayList<Int>()
        var d = from
        while (out.size < count) {
            if (Dates.dow(d) in dows) out.add(d)
            d++
        }
        return out
    }

    fun roundRobin(teams: List<Int>): List<List<Pair<Int, Int>>> {
        val list = teams.toMutableList()
        if (list.size % 2 == 1) list.add(-1)
        val n = list.size
        val rounds = ArrayList<List<Pair<Int, Int>>>()
        val arr = list.toMutableList()
        for (r in 0 until n - 1) {
            val pairs = ArrayList<Pair<Int, Int>>()
            for (i in 0 until n / 2) {
                val a = arr[i]
                val b = arr[n - 1 - i]
                if (a >= 0 && b >= 0) pairs.add(if (r % 2 == 0) a to b else b to a)
            }
            rounds.add(pairs)
            arr.add(1, arr.removeAt(arr.size - 1))
        }
        return rounds
    }

    private fun seeding(size: Int): List<Int> {
        var order = listOf(1, 2)
        while (order.size < size) {
            val n = order.size * 2
            order = order.flatMap { listOf(it, n + 1 - it) }
        }
        return order
    }

    private fun singleElim(n: Int, bestOf: Int, finalBo: Int): MutableList<BracketNode> {
        val size = maxOf(2, 2.0.pow(ceil(ln(maxOf(2, n).toDouble()) / ln(2.0))).toInt())
        val rounds = (ln(size.toDouble()) / ln(2.0)).toInt()
        val seeds = seeding(size)
        fun label(r: Int): String = when (size / 2.0.pow(r).toInt()) { 2 -> "Grand Final"; 4 -> "Semifinal"; 8 -> "Quarterfinal"; else -> "Round of ${size / 2.0.pow(r).toInt()}" }
        val nodes = ArrayList<BracketNode>()
        for (i in 0 until size / 2) nodes.add(BracketNode("r0m$i", 0, label(0), "s${seeds[2 * i]}", "s${seeds[2 * i + 1]}", size / 2 + 1, rounds == 1, if (rounds == 1) finalBo else bestOf))
        for (r in 1 until rounds) {
            val count = size / 2.0.pow(r + 1).toInt()
            for (i in 0 until count) {
                val fin = r == rounds - 1
                nodes.add(BracketNode("r${r}m$i", r, label(r), "wr${r - 1}m${2 * i}", "wr${r - 1}m${2 * i + 1}", count + 1, fin, if (fin) finalBo else bestOf))
            }
        }
        return nodes
    }

    private fun doubleElim(n: Int, bo: Int, fin: Int): MutableList<BracketNode> {
        fun nd(id: String, r: Int, label: String, a: String, b: String, place: Int, isFinal: Boolean = false) = BracketNode(id, r, label, a, b, place, isFinal, if (isFinal) fin else bo)
        if (n <= 4) return mutableListOf(
            nd("u1", 0, "Upper Semifinal", "s1", "s4", 0), nd("u2", 0, "Upper Semifinal", "s2", "s3", 0), nd("uf", 1, "Upper Final", "wu1", "wu2", 0),
            nd("l1", 1, "Lower Round 1", "lu1", "lu2", 4), nd("lf", 2, "Lower Final", "wl1", "luf", 3), nd("gf", 3, "Grand Final", "wuf", "wlf", 2, true),
        )
        return mutableListOf(
            nd("u1", 0, "Upper Quarterfinal", "s1", "s8", 0), nd("u2", 0, "Upper Quarterfinal", "s4", "s5", 0), nd("u3", 0, "Upper Quarterfinal", "s2", "s7", 0), nd("u4", 0, "Upper Quarterfinal", "s3", "s6", 0),
            nd("u5", 1, "Upper Semifinal", "wu1", "wu2", 0), nd("u6", 1, "Upper Semifinal", "wu3", "wu4", 0), nd("l1", 1, "Lower Round 1", "lu1", "lu2", 7), nd("l2", 1, "Lower Round 1", "lu3", "lu4", 7),
            nd("u7", 2, "Upper Final", "wu5", "wu6", 0), nd("l3", 2, "Lower Round 2", "wl1", "lu6", 5), nd("l4", 2, "Lower Round 2", "wl2", "lu5", 5),
            nd("l5", 3, "Lower Round 3", "wl3", "wl4", 4), nd("l6", 4, "Lower Final", "wl5", "lu7", 3), nd("gf", 5, "Grand Final", "wu7", "wl6", 2, true),
        )
    }

    /** -2 = not decided yet, -1 = bye. */
    private fun resolve(stage: Stage, src: String): Int {
        if (src[0] == 's') return stage.teamIds.getOrNull(src.substring(1).toInt() - 1) ?: -1
        val ref = stage.bracket.firstOrNull { it.id == src.substring(1) } ?: return -1
        val decided = ref.winner != -2 || ref.loser != -2 || (ref.teamA == -1 && ref.teamB == -1)
        if (!decided) return -2
        return if (src[0] == 'w') ref.winner.coerceAtLeast(-1) else ref.loser.coerceAtLeast(-1)
    }

    fun progressBracket(w: World, c: Competition, si: Int) {
        val stage = c.stages[si]
        var changed = true
        while (changed) {
            changed = false
            for (nd in stage.bracket) {
                if (nd.fixtureId >= 0 || nd.winner != -2 || nd.loser != -2 || (nd.teamA == -1 && nd.teamB == -1)) continue
                val a = resolve(stage, nd.a)
                val b = resolve(stage, nd.b)
                if (a == -2 || b == -2) continue
                nd.teamA = a
                nd.teamB = b
                if (a == -1 || b == -1) {
                    nd.winner = if (a >= 0) a else if (b >= 0) b else -1
                    nd.loser = -1
                    changed = true
                    continue
                }
                val day = maxOf(stage.startDay + nd.round * stage.daysBetween, w.day + 1)
                nd.fixtureId = addFixture(w, c, si, day, a, b, nd.bestOf, nd.round, nodeId = nd.id).id
                changed = true
            }
        }
    }

    private fun swissRound(w: World, c: Competition, si: Int): Boolean {
        val stage = c.stages[si]
        val active = stage.standings.filter { it.w < stage.swissWins && it.l < stage.swissLosses }
        if (active.size < 2) return false
        val played = HashSet<Long>()
        fun key(a: Int, b: Int) = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
        for (fid in stage.fixtureIds) w.fixtures[fid].let { played.add(key(it.a, it.b)) }
        val pools = active.groupBy { it.w * 100 - it.l }.toSortedMap(compareByDescending { it })
        val pairs = ArrayList<Pair<Int, Int>>()
        var carry: List<Standing> = emptyList()
        for ((_, rows) in pools) {
            val pool = (carry + rows).sortedWith(compareByDescending<Standing> { it.gw - it.gl }.thenBy { stage.teamIds.indexOf(it.teamId) }).map { it.teamId }.toMutableList()
            carry = emptyList()
            while (pool.size >= 2) {
                val a = pool.removeAt(0)
                var j = pool.size - 1
                while (j > 0 && key(a, pool[j]) in played) j--
                pairs.add(a to pool.removeAt(j))
            }
            if (pool.isNotEmpty()) carry = stage.standings.filter { it.teamId == pool[0] }
        }
        if (pairs.isEmpty()) return false
        val day = maxOf(stage.startDay + stage.swissRound * stage.daysBetween, w.day + 1)
        for ((a, b) in pairs) addFixture(w, c, si, day, a, b, stage.bestOf, stage.swissRound)
        stage.swissRound++
        return true
    }

    fun start(w: World, c: Competition, si: Int, teamIds: List<Int>) {
        val stage = c.stages[si]
        stage.teamIds = teamIds.toMutableList()
        stage.status = 1
        stage.startDay = maxOf(stage.startDay, w.day + 1)
        stage.standings = teamIds.map { Standing(it) }.toMutableList()
        stage.fixtureIds = ArrayList()
        stage.bracket = ArrayList()
        stage.swissRound = 0
        val fin = Catalogue.get(c.titleId).formats.third.n
        when (stage.type) {
            StageType.RR, StageType.DRR -> {
                var rounds = roundRobin(stage.teamIds)
                if (stage.type == StageType.DRR) rounds = rounds + rounds.map { r -> r.map { (a, b) -> b to a } }
                val days = matchDays(stage.startDay, rounds.size, stage.dows)
                rounds.forEachIndexed { ri, pairs -> for ((a, b) in pairs) addFixture(w, c, si, days[ri], a, b, stage.bestOf, ri) }
            }
            StageType.GROUPS -> {
                val g = maxOf(1, stage.groups)
                val groups = List(g) { ArrayList<Int>() }
                stage.teamIds.forEachIndexed { i, tid -> val cycle = i / g; groups[if (cycle % 2 == 0) i % g else g - 1 - i % g].add(tid) }
                stage.standings = groups.flatMapIndexed { gi, ts -> ts.map { Standing(it, group = gi) } }.toMutableList()
                groups.forEachIndexed { gi, ts ->
                    val rounds = roundRobin(ts)
                    val days = matchDays(stage.startDay, rounds.size, stage.dows)
                    rounds.forEachIndexed { ri, pairs -> for ((a, b) in pairs) addFixture(w, c, si, days[ri], a, b, stage.bestOf, ri, gi) }
                }
            }
            StageType.SWISS -> swissRound(w, c, si)
            StageType.SINGLE_ELIM -> { stage.bracket = singleElim(teamIds.size, stage.bestOf, fin); progressBracket(w, c, si) }
            StageType.DOUBLE_ELIM -> { stage.bracket = doubleElim(teamIds.size, stage.bestOf, fin); progressBracket(w, c, si) }
        }
    }

    private val elimTypes = setOf(StageType.SINGLE_ELIM, StageType.DOUBLE_ELIM)

    fun sortStandings(w: World, rows: List<Standing>): List<Standing> = rows.sortedWith(
        compareByDescending<Standing> { it.w }.thenByDescending { it.gw - it.gl }.thenByDescending { it.gw }.thenBy { it.l }.thenByDescending { w.teams[it.teamId].elo }.thenBy { it.teamId },
    )

    fun apply(w: World, c: Competition, f: Fixture) {
        val stage = c.stages[f.stageIdx]
        if (stage.type !in elimTypes) {
            val ra = stage.standings.firstOrNull { it.teamId == f.a }
            val rb = stage.standings.firstOrNull { it.teamId == f.b }
            if (ra != null && rb != null) {
                ra.gw += f.scoreA; ra.gl += f.scoreB; rb.gw += f.scoreB; rb.gl += f.scoreA
                if (f.winner == f.a) { ra.w++; rb.l++ } else { rb.w++; ra.l++ }
            }
        }
        if (f.nodeId.isNotEmpty()) stage.bracket.firstOrNull { it.id == f.nodeId }?.let { it.winner = f.winner; it.loser = if (f.winner == f.a) f.b else f.a }
    }

    fun stageDone(w: World, c: Competition, si: Int): Boolean {
        val stage = c.stages[si]
        val pending = stage.fixtureIds.any { !w.fixtures[it].done }
        if (stage.type in elimTypes) {
            progressBracket(w, c, si)
            val fin = stage.bracket.firstOrNull { it.isFinal } ?: return false
            return (fin.winner != -2 || (fin.teamA == -1 && fin.teamB == -1)) && stage.fixtureIds.all { w.fixtures[it].done }
        }
        if (pending) return false
        if (stage.type == StageType.SWISS) return !swissRound(w, c, si)
        return true
    }

    fun placements(w: World, stage: Stage): List<Int> {
        if (stage.type in elimTypes) {
            val place = HashMap<Int, Int>()
            for (nd in stage.bracket) {
                if (nd.loser >= 0) place[nd.loser] = if (nd.elimPlace > 0) nd.elimPlace else 99
                if (nd.isFinal && nd.winner >= 0) place[nd.winner] = 1
            }
            return stage.teamIds.sortedWith(compareBy<Int> { place[it] ?: 99 }.thenBy { stage.teamIds.indexOf(it) })
        }
        if (stage.type == StageType.GROUPS) {
            val groups = stage.standings.groupBy { it.group }.values.map { sortStandings(w, it) }
            val out = ArrayList<Int>()
            for (rank in 0 until groups.maxOf { it.size }) out.addAll(sortStandings(w, groups.mapNotNull { it.getOrNull(rank) }).map { it.teamId })
            return out
        }
        if (stage.type == StageType.SWISS) return stage.standings.sortedWith(compareByDescending<Standing> { it.w }.thenBy { it.l }.thenByDescending { it.gw - it.gl }.thenBy { stage.teamIds.indexOf(it.teamId) }).map { it.teamId }
        return sortStandings(w, stage.standings).map { it.teamId }
    }
}

/** Starting competitions when their entrants are known, and moving them stage to stage. */
object Progress {
    fun circuitPoints(w: World, season: Int, titleId: String): Map<Int, Double> {
        val pts = HashMap<Int, Double>()
        for (c in w.comps) {
            if (c.season != season || c.titleId != titleId || !c.done || c.circuitPoints.isEmpty()) continue
            c.placements.forEachIndexed { i, tid -> pts[tid] = (pts[tid] ?: 0.0) + (c.circuitPoints.getOrNull(i) ?: 0) * Season.splitWeight(c.split) }
        }
        return pts
    }

    private fun entrants(w: World, c: Competition): List<Int>? {
        val out = ArrayList<Int>()
        fun add(id: Int) { if (w.teams[id].active && id !in out) out.add(id) }
        for (rule in c.entrants) {
            val p = rule.split(':')
            when (p[0]) {
                "league" -> w.leagues[p[1].toInt()].teamIds.forEach(::add)
                "top" -> {
                    val src = w.comps[p[1].toInt()]
                    if (!src.done) return null
                    var taken = 0
                    for (tid in src.placements) {
                        if (taken >= p[2].toInt()) break
                        if (tid !in out && w.teams[tid].active) { add(tid); taken++ }
                    }
                }
                "circuit" -> {
                    val league = w.leagues[p[1].toInt()]
                    val splits = w.comps.filter { it.season == c.season && it.leagueId == league.id }
                    if (splits.any { !it.done }) return null
                    if (w.comps.any { it.season == c.season && it.titleId == c.titleId && it.tier == CompTier.INTERNATIONAL && !it.done }) return null
                    val pts = circuitPoints(w, c.season, c.titleId)
                    val last = splits.maxByOrNull { it.split }
                    val chosen = ArrayList<Int>()
                    last?.placements?.firstOrNull()?.let { if (w.teams[it].leagueId == league.id) chosen.add(it) }
                    for (t in league.teamIds.filter { it !in chosen }.sortedWith(compareByDescending<Int> { pts[it] ?: 0.0 }.thenByDescending { w.teams[it].elo })) {
                        if (chosen.size >= p[2].toInt()) break
                        chosen.add(t)
                    }
                    chosen.forEach(::add)
                }
            }
        }
        if (c.tier == CompTier.INTERNATIONAL || c.tier == CompTier.WORLDS) out.sortByDescending { w.teams[it].elo }
        return out
    }

    fun startDue(w: World, day: Int) {
        for (c in w.comps) {
            if (c.status != 0 || c.startDay > day + 3) continue
            val teams = entrants(w, c) ?: continue
            if (teams.size < 2) {
                c.status = 2
                c.placements = teams.toMutableList()
                continue
            }
            c.teamIds = teams.toMutableList()
            c.status = 1
            c.current = 0
            Stages.start(w, c, 0, teams)
        }
    }

    /** After a series: standings / bracket, then the next stage or the final placements. */
    fun onFixtureDone(w: World, f: Fixture) {
        val c = w.comps[f.compId]
        Stages.apply(w, c, f)
        val stage = c.stages[f.stageIdx]
        if (stage.status != 1) return
        if (!Stages.stageDone(w, c, f.stageIdx)) return
        stage.status = 2
        stage.placements = Stages.placements(w, stage).toMutableList()
        val next = c.stages.getOrNull(f.stageIdx + 1)
        if (next != null) {
            val n = if (stage.type == StageType.GROUPS) stage.advance * maxOf(1, stage.groups) else if (stage.advance > 0) stage.advance else stage.placements.size
            c.current = f.stageIdx + 1
            Stages.start(w, c, f.stageIdx + 1, stage.placements.take(minOf(n, stage.placements.size)))
            return
        }
        finish(w, c)
    }

    fun finish(w: World, c: Competition) {
        if (c.done) return
        val order = ArrayList<Int>()
        for (i in c.stages.indices.reversed()) for (t in c.stages[i].placements) if (t !in order) order.add(t)
        for (t in c.teamIds) if (t !in order) order.add(t)
        c.placements = order
        c.status = 2
        order.firstOrNull()?.let { champ ->
            w.teams[champ].trophies.add(Trophy(c.season, c.id, c.name))
            if (c.tier != CompTier.CHALLENGER) w.news.add(dev.uem.em.model.News(w.day, "${w.teams[champ].name} win ${c.name}", "${w.teams[champ].name} are champions of ${c.name}.", listOf(champ)))
        }
        // Competition MVP: best average rating with at least half the games of the most active player.
        val maxGp = c.pstats.values.maxOfOrNull { it.gp } ?: 0
        c.pstats.entries.filter { it.value.gp * 2 >= maxGp && it.value.gp > 0 }.maxByOrNull { it.value.r / it.value.gp }?.let { c.mvp = it.key }
        dev.uem.em.mgmt.Economy.onCompetitionFinished(w, c)
        dev.uem.em.mgmt.Career.onCompetitionFinished(w, c)
    }
}

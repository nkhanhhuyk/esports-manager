package dev.uem.em.sim

import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.model.Player
import dev.uem.em.titles.CharDef
import dev.uem.em.titles.TitleDef

/**
 * Drafts (ported from the web version's quick draft): each player has a short preference list for
 * the patch (comfort, meta, team style, role fit); teams ban the opponents' favourites and pick
 * through the title's real step order. Same rules as a full draft: bans, uniqueness, Fearless.
 * Valorant agents are chosen per team (no bans); CS2 has no characters.
 */
object Draft {
    /** `order`: the draft step by step ("b|team|character" or "p|team|character|player"), team = series team. */
    class Result(val picks: Array<ArrayList<String>>, val bans: List<String>, val assign: Array<HashMap<Int, String>>, val firstPick: Int, val order: List<String> = emptyList())

    private val FRONT = Regex("tank|guardian|vanguard|front|defender|fighter|warrior|bruiser|captain")
    private val DAMAGE = Regex("marksman|mage|assassin|sniper|hunter|sharpshooter|damage|duelist|attacker|carry|nuker|caster|energy")
    private val SUPPORT = Regex("support|enchanter|healer|supporter|strategist|captain")

    fun evaluate(t: TitleDef, patch: Patch, chars: List<CharDef>, enemy: List<CharDef>, players: List<Player>, teamStyle: String): CompEval {
        if (chars.isEmpty()) {
            val profile = t.styles.associateWith { if (it == teamStyle) 7.0 else 5.0 }
            val style = ((patch.styles[teamStyle] ?: 1.0) - 1) * 0.8
            return CompEval(style * 0.4, 0.0, profile)
        }
        val meta = chars.sumOf { patch.charMeta[it.id] ?: 0.0 } / chars.size / 10
        val profile = t.styles.associateWith { s -> chars.sumOf { it.styles[s] ?: 5.0 } / chars.size }
        var ws = 0.0
        var ss = 0.0
        for (s in t.styles) { val w = patch.styles[s] ?: 1.0; ws += w; ss += profile[s]!! * w }
        val style = clamp((ss / maxOf(0.01, ws) - 5) / 3, -1.0, 1.0) * 0.6 + clamp(((profile[teamStyle] ?: 5.0) - 5) / 5, -1.0, 1.0) * 0.4
        val hasFront = chars.any { FRONT.containsMatchIn(it.cls.lowercase()) }
        val dmg = chars.count { DAMAGE.containsMatchIn(it.cls.lowercase()) }
        val sup = chars.any { SUPPORT.containsMatchIn(it.cls.lowercase()) }
        val coverage = (if (hasFront) 0.25 else -0.35) + (if (dmg >= minOf(2, chars.size - 1)) 0.2 else -0.3) + (if (sup) 0.1 else -0.15)
        // Matchups on this patch (lane opponents count double), synergy and kit strength.
        val cv = Counters.view(t, patch)
        var counter = 0.0
        if (enemy.isNotEmpty()) {
            var wsum = 0.0
            for ((i, c) in chars.withIndex()) for ((j, e) in enemy.withIndex()) { val wt = if (i == j) 2.0 else 1.0; counter += cv.score(c.id, e.id) * wt; wsum += wt }
            counter = clamp(counter / maxOf(1.0, wsum) * 2.2, -1.0, 1.0)
        }
        var syn = 0.0
        if (chars.size > 1) { var n = 0; for (i in chars.indices) for (j in i + 1 until chars.size) { syn += cv.synergy(chars[i].id, chars[j].id); n++ }; syn /= maxOf(1, n) }
        val power = chars.sumOf { cv.power(it.id) } / chars.size
        val comfort = if (players.isEmpty()) 0.5 else players.indices.sumOf { i -> chars.getOrNull(i)?.let { (players[i].profChar[it.id] ?: 25.0) / 100 } ?: 0.0 } / players.size
        val total = 0.30 * meta + 0.18 * style + 0.16 * coverage + 0.16 * counter + 0.10 * (comfort - 0.6) * 2 + 0.06 * (syn - 0.3) * 2 + 0.04 * (power - 0.5) * 2
        return CompEval(total, counter, profile)
    }

    internal fun comfort(t: TitleDef, p: Player): (CharDef) -> Double {
        val byClass = HashMap<String, Double>()
        for ((cid, v) in p.profChar) t.charIndex[cid]?.let { c -> if (v > (byClass[c.cls] ?: 0.0)) byClass[c.cls] = v }
        return { c -> p.profChar[c.id] ?: (byClass[c.cls] ?: 0.0) * 0.45 }
    }

    /**
     * Preference lists cached per patch. The key holds everything the list depends on (the player's
     * character comfort, the roster of characters), so clearing the cache, saving and loading, or
     * playing another world in the same process never changes a draft.
     */
    private val prefCache = java.util.concurrent.ConcurrentHashMap<Patch, java.util.concurrent.ConcurrentHashMap<String, List<CharDef>>>()

    private fun preferred(t: TitleDef, patch: Patch, p: Player, roleIdx: Int, style: String): List<CharDef> {
        if (prefCache.size > 64) prefCache.clear()
        val m = prefCache.getOrPut(patch) { java.util.concurrent.ConcurrentHashMap() }
        if (m.size > 40_000) m.clear()
        return m.getOrPut("${p.id}|$roleIdx|$style|${p.profChar.hashCode()}|${t.characters.size}") { computePreferred(t, patch, p, roleIdx, style) }
    }

    /** A player's preferred characters for the patch (top 14). */
    private fun computePreferred(t: TitleDef, patch: Patch, p: Player, roleIdx: Int, style: String): List<CharDef> {
        val role = t.lineupRoles[roleIdx]
        val cf = comfort(t, p)
        val fitAny = t.characters.any { it.lanes.isNotEmpty() || it.classes.isNotEmpty() }
        return t.characters.asSequence().filter { !fitAny || it.fits(role) }
            .map { it to ((patch.charMeta[it.id] ?: 0.0) / 10 * 0.8 + cf(it) / 100 * 1.1 + ((it.styles[style] ?: 5.0) - 5) / 10) }
            .sortedByDescending { it.second }.take(14).map { it.first }.toList()
    }

    fun run(t: TitleDef, patch: Patch, lineups: Array<List<Player>>, styles: Array<String>, firstPick: Int, locked: Array<Set<String>>, rng: Rng, know: DoubleArray = doubleArrayOf(0.7, 0.7), banPref: IntArray? = null): Result =
        DraftSession(t, patch, lineups, styles, firstPick, locked, rng, know, banPref).runAll()

    /** How well a team reads the patch: meta adaptation and the head coach. */
    fun knowledge(w: dev.uem.em.model.World, team: dev.uem.em.model.Team) = clamp((team.metaAdapt * 0.6 + team.coach * 0.4) / 100.0, 0.3, 1.0)

    /** A team's usual ban approach this season (meta threats, comfort targeting, balanced, handshake): scouts can read it. */
    fun banTendency(w: dev.uem.em.model.World, team: dev.uem.em.model.Team) = Math.floorMod(team.id * 7 + w.season * 3 + team.style.length, 4)
    val BAN_TENDENCY = listOf("bans the meta threats", "targets the opponents' comfort picks", "mixes meta and comfort bans", "leaves power picks open to trade them (handshake)")

    internal fun prefs(t: TitleDef, patch: Patch, p: Player, roleIdx: Int, style: String) = preferred(t, patch, p, roleIdx, style)
}

/**
 * A draft played step by step (the title's real ban/pick order). The AI acts for a team with
 * [aiStep]; the user acts with [ban] / [pick]. Same rules for both: bans, uniqueness, Fearless.
 * Valorant agents are chosen per team without bans; CS2 has no characters.
 */
class DraftSession(
    val t: TitleDef, val patch: Patch, val lineups: Array<List<Player>>, val styles: Array<String>, val firstPick: Int, val locked: Array<Set<String>>, val rng: Rng,
    /** How well each team reads the patch (coaches and analysts, 0.3..1): weight of counters, synergy and kit strength. */
    val know: DoubleArray = doubleArrayOf(0.7, 0.7),
    /** Each team's usual ban approach this season (-1: none), see [banStyle]. */
    banPref: IntArray? = null,
    /** What each team learned in scrims on this patch (character → -0.5 failed .. +0.5 works). */
    val scrimBonus: Array<Map<String, Double>>? = null,
) {
    val picks = arrayOf(ArrayList<String>(), ArrayList<String>())
    val assign = arrayOf(HashMap<Int, String>(), HashMap<Int, String>())
    val bans = ArrayList<String>()
    /** Every ban and pick in order (see [Draft.Result.order]). */
    val order = ArrayList<String>()
    val banned = HashSet<String>()
    val picked = HashSet<String>()
    val open = arrayOf(lineups[0].indices.toMutableList(), lineups[1].indices.toMutableList())
    var step = 0
        private set
    private val prefs = Array(2) { s ->
        val bonus = scrimBonus?.getOrNull(s)?.takeIf { it.isNotEmpty() }
        lineups[s].mapIndexed { i, p ->
            val base = Draft.prefs(t, patch, p, i, styles[s])
            // Scrims move a team's priorities toward what worked for it on this patch.
            if (bonus == null) base else base.withIndex().sortedByDescending { (k, c) -> (base.size - k).toDouble() / base.size + (bonus[c.id] ?: 0.0) * 1.2 }.map { it.value }
        }
    }
    val sequential = t.draft.mode == "sequential" && t.characters.isNotEmpty()
    val none = t.characters.isEmpty() || t.draft.mode == "none"

    /** Series team acting now and whether it bans (null when the draft is over). */
    fun current(): Pair<Int, Boolean>? {
        if (!sequential) return null
        val st = t.draft.steps.getOrNull(step) ?: return null
        return (if (st.team == 0) firstPick else 1 - firstPick) to st.ban
    }

    val done get() = none || (sequential && current() == null) || (!sequential && picks[0].size >= lineups[0].size && picks[1].size >= lineups[1].size)

    // ------------------------------------------------------------------ ban planning
    /** How a team bans in this draft: 0 meta threats, 1 target the opponents' comfort picks, 2 balanced, 3 handshake (leave power picks to trade). */
    val banStyle = IntArray(2) { s -> val pref = banPref?.getOrNull(s) ?: -1; if (pref >= 0 && rng.chance(0.65)) pref else rng.int(0, 3) }
    val BAN_STYLE_NAMES = listOf("meta threats", "target comfort picks", "balanced", "handshake")
    /** Patch strength as standard scores: only characters clearly above the field count as overpowered (0 to several per patch). */
    private val metaZ: Map<String, Double> by lazy {
        val vals = t.characters.map { patch.charMeta[it.id] ?: 0.0 }
        val mean = vals.average(); val sd = kotlin.math.sqrt(vals.sumOf { (it - mean) * (it - mean) } / maxOf(1, vals.size)).coerceAtLeast(0.5)
        t.characters.associate { it.id to ((patch.charMeta[it.id] ?: 0.0) - mean) / sd }
    }
    private val comfortOf = Array(2) { s -> lineups[s].map { Draft.comfort(t, it) } }
    /** The patch's ten strongest characters (ban candidates), sorted once per draft. */
    private val topMeta: List<CharDef> by lazy { t.characters.sortedByDescending { patch.charMeta[it.id] ?: 0.0 }.take(10) }
    private val cv = Counters.view(t, patch)

    private fun nextPickIsMine(team: Int): Boolean {
        var k = step + 1
        while (true) {
            val st = t.draft.steps.getOrNull(k) ?: return false
            if (!st.ban) return (if (st.team == 0) firstPick else 1 - firstPick) == team
            k++
        }
    }

    /**
     * Ban choice: overpowered characters (only those well above the patch), the opponents' comfort
     * picks (weighted by the player's level), what the opponents already showed in earlier games
     * through `locked`, minus "handshake" picks this team wants to take itself next.
     */
    private fun chooseBan(team: Int): CharDef? {
        val enemy = 1 - team
        // Deep in a long Fearless series the pool runs low: bans stop while both teams still need picks from it.
        val need = open[0].size + open[1].size + 4
        if ((locked[0].isNotEmpty() || locked[1].isNotEmpty() || t.characters.size < 60) &&
            minOf(t.characters.count { legal(0, false, it.id) }, t.characters.count { legal(1, false, it.id) }) <= need) return null
        val style = banStyle[team]
        val wMeta = when (style) { 0 -> 1.6; 1 -> 0.5; else -> 1.0 }
        val wTarget = when (style) { 1 -> 1.7; 0 -> 0.5; else -> 1.0 }
        val cand = LinkedHashSet<CharDef>()
        // Each slot's preference lists, taken once (this runs for every ban of every draft of a season).
        val enemySlots = open[enemy].ifEmpty { lineups[enemy].indices.toList() }
        val ePref6 = enemySlots.map { prefs[enemy][it].take(6) }
        val mySlots = open[team]
        val mPref4 = mySlots.map { prefs[team][it].take(4) }
        val mPref8 = mySlots.map { prefs[team][it].take(8) }
        for (l in ePref6) cand.addAll(l)
        cand.addAll(topMeta)
        if (know[team] > 0.5) for (i in open[team]) prefs[team][i].firstOrNull()?.let { mp -> cv.countersTo(mp.id, 2).forEach { cid -> t.charIndex[cid]?.let { cand.add(it) } } }
        val mineNext = nextPickIsMine(team)
        var best: CharDef? = null; var bv = -9.0
        for (c in cand) {
            if (!legal(team, true, c.id)) continue
            // Overpowered by the patch's absolute strength (meta above +4.5 of ±10): a balanced patch has
            // none, a broken one several — the count changes with every patch.
            val op = (((patch.charMeta[c.id] ?: 0.0) - 4.5) / 2.5).coerceAtLeast(0.0)
            var target = 0.0
            for ((k, i) in enemySlots.withIndex()) {
                if (c !in ePref6[k]) continue
                val pl = lineups[enemy][i]
                target = maxOf(target, comfortOf[enemy][i](c) / 100 * (0.6 + pl.ca / 200))
            }
            var mine = 0.0
            for ((k, i) in mySlots.withIndex()) if (c in mPref4[k]) mine = maxOf(mine, comfortOf[team][i](c) / 100)
            // A counter we are comfortable on: the overpowered pick can stay open (we answer it).
            var answer = 0.0
            if (op > 0.0) for ((k, i) in mySlots.withIndex()) for (x in mPref8[k]) if (legal(team, false, x.id) && x.id != c.id) answer = maxOf(answer, cv.score(x.id, c.id) * comfortOf[team][i](x) / 100)
            // It counters what we want to play: ban it to protect our comfort picks.
            var threat = 0.0
            for (i in open[team]) prefs[team][i].firstOrNull { legal(team, false, it.id) && it.id != c.id }?.let { mp -> threat = maxOf(threat, cv.score(c.id, mp.id) * comfortOf[team][i](mp) / 100) }
            var v = wMeta * op + wTarget * target * 1.4 + know[team] * (threat * 0.9 - answer * 0.8 * minOf(1.0, op)) + rng.normal(0.0, 0.3)
            // Handshake: a power pick we can take with our next pick stays open.
            if (mineNext && (style == 3 || style == 2) && mine > 0.45 && op > 0.3) v -= 1.2 + mine
            if (v > bv) { bv = v; best = c }
        }
        return best ?: t.characters.firstOrNull { legal(team, true, it.id) }
    }

    /**
     * A ban only counts on a champion the opponent could still pick: under Fearless rules the
     * champions locked for the opponent are already out, so banning them would waste the ban.
     */
    fun legal(team: Int, ban: Boolean, id: String) = id !in banned && id !in picked && (if (ban) id !in locked[1 - team] else id !in locked[team])

    fun ban(id: String): Boolean {
        val cur = current() ?: return false
        if (!cur.second || !legal(cur.first, true, id)) return false
        banned.add(id); bans.add(id); order.add("b|${cur.first}|$id"); step++
        return true
    }

    /** Pick for the acting team; the character goes to the open player it suits best (or `playerIdx`). */
    fun pick(id: String, playerIdx: Int = -1): Boolean {
        val cur = current() ?: return false
        val team = cur.first
        if (cur.second || !legal(team, false, id) || open[team].isEmpty()) return false
        val c = t.charIndex[id] ?: return false
        val idx = if (playerIdx in open[team]) playerIdx else open[team].maxBy { i -> (if (c.fits(t.lineupRoles[i])) 100.0 else 0.0) + (lineups[team][i].profChar[id] ?: 0.0) }
        place(team, idx, id)
        step++
        return true
    }

    private fun place(team: Int, idx: Int, id: String) {
        picked.add(id); picks[team].add(id); assign[team][lineups[team][idx].id] = id; open[team].remove(idx)
        order.add("p|$team|$id|${lineups[team][idx].id}")
    }

    /** The AI acts for whichever team's turn it is. */
    fun aiStep() {
        val cur = current() ?: return
        val team = cur.first
        if (cur.second) {
            val c = chooseBan(team)
            if (c != null) { banned.add(c.id); bans.add(c.id); order.add("b|$team|${c.id}") }
            step++
            return
        }
        if (open[team].isEmpty()) { step++; return }
        var best = -99.0
        var bc: CharDef? = null
        var bi = -1
        val enemy = 1 - team
        val k = know[team]
        // Lane opponents already shown by the enemy (their character by lineup slot).
        val enemyBySlot = HashMap<Int, String>()
        for ((pid, cid) in assign[enemy]) { val slot = lineups[enemy].indexOfFirst { it.id == pid }; if (slot >= 0) enemyBySlot[slot] = cid }
        for (i in open[team]) {
            val list = prefs[team][i]
            var legalSeen = 0
            for ((idx, c) in list.withIndex()) {
                if (!legal(team, false, c.id)) continue
                if (++legalSeen > 6) break
                var v = (14 - idx) / 14.0 * 3.0
                // Counter-pick: the lane opponent first, then the rest of their picks.
                enemyBySlot[i]?.let { v += k * 1.6 * cv.score(c.id, it) }
                if (picks[enemy].isNotEmpty()) v += k * 0.9 * picks[enemy].sumOf { cv.score(c.id, it) } / picks[enemy].size
                if (picks[team].isNotEmpty()) v += k * 0.7 * picks[team].sumOf { cv.synergy(c.id, it) } / picks[team].size
                v += k * 0.5 * (cv.power(c.id) - 0.5)
                v += rng.next() * 0.45
                if (v > best) { best = v; bc = c; bi = i }
            }
        }
        if (bc == null) {
            bi = open[team][0]
            bc = t.characters.filter { legal(team, false, it.id) }.maxByOrNull { patch.charMeta[it.id] ?: 0.0 } ?: exhausted(team)
        }
        if (bc != null) place(team, bi, bc.id)
        step++
    }

    /** Per-team agent choice (Valorant): each player takes his best available preferred agent. */
    fun autoTeam(s: Int) {
        val taken = HashSet(picks[s])
        lineups[s].indices.filter { it in open[s] }.sortedBy { (it * 7919 + s * 31 + step) % 11 }.forEach { i ->
            val c = prefs[s][i].firstOrNull { it.id !in taken && it.id !in locked[s] } ?: t.characters.firstOrNull { it.id !in taken && it.id !in locked[s] } ?: t.characters.first { it.id !in taken }
            taken.add(c.id)
            place(s, i, c.id)
        }
    }

    /** Valorant: set one player's agent (unique within the team). */
    fun setAgent(s: Int, idx: Int, id: String): Boolean {
        if (picks[s].contains(id)) return false
        lineups[s][idx].id.let { pid -> assign[s][pid]?.let { old -> picks[s].remove(old); picked.remove(old) } }
        picks[s].add(id); assign[s][lineups[s][idx].id] = id; open[s].remove(idx)
        return true
    }

    fun runAll(): Draft.Result {
        if (none) return result()
        if (!sequential) { autoTeam(0); autoTeam(1); return result() }
        var guard = 0
        while (!done && guard++ < 200) aiStep()
        // Anyone left without a pick (short drafts) takes the best legal option.
        for (s in 0..1) for (i in open[s].toList()) {
            val c = prefs[s][i].firstOrNull { legal(s, false, it.id) } ?: t.characters.firstOrNull { legal(s, false, it.id) } ?: exhausted(s) ?: continue
            place(s, i, c.id)
        }
        return result()
    }

    /** The Fearless pool is used up (a very long series): champions locked by earlier games come back, best meta first. */
    private fun exhausted(team: Int): CharDef? =
        t.characters.filter { it.id !in picked && it.id !in banned }.maxByOrNull { patch.charMeta[it.id] ?: 0.0 }

    fun result() = Draft.Result(picks, bans, assign, firstPick, order)
}

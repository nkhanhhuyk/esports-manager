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
    class Result(val picks: Array<ArrayList<String>>, val bans: List<String>, val assign: Array<HashMap<Int, String>>, val firstPick: Int)

    private val COUNTERS = listOf(
        Triple(Regex("assassin|flank|speedster|skirmisher"), Regex("marksman|mage|sniper|sharpshooter|hunter|carry|artillery|support|healer|strategist|enchanter"), 0.5),
        Triple(Regex("tank|guardian|vanguard|front|defender|brawler"), Regex("assassin|flank|speedster|fighter|warrior"), 0.4),
        Triple(Regex("marksman|hunter|sharpshooter|carry|damage|duelist"), Regex("tank|guardian|vanguard|front|defender"), 0.45),
        Triple(Regex("mage|nuker|caster|controller|artillery"), Regex("fighter|warrior|bruiser|all-rounder|tank"), 0.35),
        Triple(Regex("fighter|warrior|bruiser|all-rounder"), Regex("marksman|mage|support|enchanter"), 0.3),
        Triple(Regex("initiator|intel|recon"), Regex("sentinel|anchor|trapper|controller"), 0.3),
        Triple(Regex("sentinel|anchor|trapper"), Regex("duelist|entry"), 0.35),
        Triple(Regex("duelist|entry"), Regex("initiator|controller|intel"), 0.25),
    )
    private val counterCache = java.util.concurrent.ConcurrentHashMap<String, Double>()

    fun counter(a: CharDef, b: CharDef): Double = counterCache.getOrPut("${a.cls}|${b.cls}") {
        val ca = a.cls.lowercase()
        val cb = b.cls.lowercase()
        var s = 0.0
        for ((x, y, v) in COUNTERS) {
            if (x.containsMatchIn(ca) && y.containsMatchIn(cb)) s += v
            if (x.containsMatchIn(cb) && y.containsMatchIn(ca)) s -= v
        }
        s
    }

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
        var counter = 0.0
        if (enemy.isNotEmpty()) {
            for (c in chars) for (e in enemy) counter += counter(c, e)
            counter = clamp(counter / (chars.size * enemy.size) * 3, -1.0, 1.0)
        }
        val comfort = if (players.isEmpty()) 0.5 else players.indices.sumOf { i -> chars.getOrNull(i)?.let { (players[i].profChar[it.id] ?: 25.0) / 100 } ?: 0.0 } / players.size
        val total = 0.35 * meta + 0.22 * style + 0.18 * coverage + 0.13 * counter + 0.12 * (comfort - 0.6) * 2
        return CompEval(total, counter, profile)
    }

    private fun comfort(t: TitleDef, p: Player): (CharDef) -> Double {
        val byClass = HashMap<String, Double>()
        for ((cid, v) in p.profChar) t.charIndex[cid]?.let { c -> if (v > (byClass[c.cls] ?: 0.0)) byClass[c.cls] = v }
        return { c -> p.profChar[c.id] ?: (byClass[c.cls] ?: 0.0) * 0.45 }
    }

    /** Preference lists cached per patch (as in the web version); a new patch starts a new cache. */
    private val prefCache = java.util.concurrent.ConcurrentHashMap<Patch, java.util.concurrent.ConcurrentHashMap<String, List<CharDef>>>()

    private fun preferred(t: TitleDef, patch: Patch, p: Player, roleIdx: Int, style: String): List<CharDef> {
        if (prefCache.size > 64) prefCache.clear()
        val m = prefCache.getOrPut(patch) { java.util.concurrent.ConcurrentHashMap() }
        return m.getOrPut("${p.id}|$roleIdx|$style") { computePreferred(t, patch, p, roleIdx, style) }
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

    fun run(t: TitleDef, patch: Patch, lineups: Array<List<Player>>, styles: Array<String>, firstPick: Int, locked: Array<Set<String>>, rng: Rng): Result =
        DraftSession(t, patch, lineups, styles, firstPick, locked, rng).runAll()

    internal fun prefs(t: TitleDef, patch: Patch, p: Player, roleIdx: Int, style: String) = preferred(t, patch, p, roleIdx, style)
}

/**
 * A draft played step by step (the title's real ban/pick order). The AI acts for a team with
 * [aiStep]; the user acts with [ban] / [pick]. Same rules for both: bans, uniqueness, Fearless.
 * Valorant agents are chosen per team without bans; CS2 has no characters.
 */
class DraftSession(val t: TitleDef, val patch: Patch, val lineups: Array<List<Player>>, val styles: Array<String>, val firstPick: Int, val locked: Array<Set<String>>, val rng: Rng) {
    val picks = arrayOf(ArrayList<String>(), ArrayList<String>())
    val assign = arrayOf(HashMap<Int, String>(), HashMap<Int, String>())
    val bans = ArrayList<String>()
    val banned = HashSet<String>()
    val picked = HashSet<String>()
    val open = arrayOf(lineups[0].indices.toMutableList(), lineups[1].indices.toMutableList())
    var step = 0
        private set
    private val prefs = Array(2) { s -> lineups[s].mapIndexed { i, p -> Draft.prefs(t, patch, p, i, styles[s]) } }
    val sequential = t.draft.mode == "sequential" && t.characters.isNotEmpty()
    val none = t.characters.isEmpty() || t.draft.mode == "none"

    /** Series team acting now and whether it bans (null when the draft is over). */
    fun current(): Pair<Int, Boolean>? {
        if (!sequential) return null
        val st = t.draft.steps.getOrNull(step) ?: return null
        return (if (st.team == 0) firstPick else 1 - firstPick) to st.ban
    }

    val done get() = none || (sequential && current() == null) || (!sequential && picks[0].size >= lineups[0].size && picks[1].size >= lineups[1].size)

    fun legal(team: Int, ban: Boolean, id: String) = id !in banned && (if (ban) id !in picked else id !in picked && id !in locked[team])

    fun ban(id: String): Boolean {
        val cur = current() ?: return false
        if (!cur.second || !legal(cur.first, true, id)) return false
        banned.add(id); bans.add(id); step++
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
    }

    /** The AI acts for whichever team's turn it is. */
    fun aiStep() {
        val cur = current() ?: return
        val team = cur.first
        if (cur.second) {
            val enemy = 1 - team
            var first: CharDef? = null
            var second: CharDef? = null
            for (i in open[enemy].ifEmpty { lineups[enemy].indices.toList() }) for (c in prefs[enemy][i].filter { it.id !in locked[enemy] }.take(4)) {
                if (!legal(team, true, c.id) || c === first || c === second) continue
                val v = patch.charMeta[c.id] ?: 0.0
                if (first == null || v > (patch.charMeta[first.id] ?: 0.0)) { second = first; first = c } else if (second == null || v > (patch.charMeta[second.id] ?: 0.0)) second = c
            }
            val c = (if (rng.int(0, 1) == 1 && second != null) second else first) ?: t.characters.filter { legal(team, true, it.id) }.maxByOrNull { patch.charMeta[it.id] ?: 0.0 }
            if (c != null) { banned.add(c.id); bans.add(c.id) }
            step++
            return
        }
        if (open[team].isEmpty()) { step++; return }
        var best = -1.0
        var bc: CharDef? = null
        var bi = -1
        for (i in open[team]) {
            val list = prefs[team][i]
            val idx = list.indexOfFirst { legal(team, false, it.id) }
            if (idx < 0) continue
            val v = 14 - idx + rng.next() * 1.5
            if (v > best) { best = v; bc = list[idx]; bi = i }
        }
        if (bc == null) {
            bi = open[team][0]
            bc = t.characters.filter { legal(team, false, it.id) }.maxByOrNull { patch.charMeta[it.id] ?: 0.0 }
        }
        if (bc != null) place(team, bi, bc.id)
        step++
    }

    /** Per-team agent choice (Valorant): each player takes his best available preferred agent. */
    fun autoTeam(s: Int) {
        val taken = HashSet(picks[s])
        lineups[s].indices.filter { it in open[s] }.sortedBy { (it * 7919 + s * 31 + step) % 11 }.forEach { i ->
            val c = prefs[s][i].firstOrNull { it.id !in taken && it.id !in locked[s] } ?: t.characters.first { it.id !in taken }
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
            val c = prefs[s][i].firstOrNull { legal(s, false, it.id) } ?: t.characters.first { legal(s, false, it.id) }
            place(s, i, c.id)
        }
        return result()
    }

    fun result() = Draft.Result(picks, bans, assign, firstPick)
}

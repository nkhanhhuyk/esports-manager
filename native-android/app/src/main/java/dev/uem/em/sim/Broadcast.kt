package dev.uem.em.sim

import dev.uem.em.model.GameRecord
import dev.uem.em.model.World
import dev.uem.em.titles.TitleDef

/**
 * Broadcast presentation, derived only from a game's recorded event stream (the same events the 2D
 * viewer and the box score use): kill feed, key moments with bookmarks, objective timers, a camera
 * director with a cooldown between cuts, and for shooters the round state (buy phase, economy, players
 * alive, bomb / spike timer). Nothing here simulates or changes anything.
 */
object Broadcast {
    /** A key moment: game second, kind, series team (-1 none), headline, priority (higher matters more), map point (0..1000) when known. */
    class Moment(val t: Int, val kind: String, val team: Int, val text: String, val priority: Int, val x: Float = -1f, val y: Float = -1f, val player: Int = -1)

    /** One kill-feed entry: killer and victim player ids, killer's series team, assisters, the broadcast tag ("Double Kill"…). */
    class Feed(val t: Int, val killer: Int, val victim: Int, val team: Int, val assists: Int, val tag: String, val x: Int = -1)

    /** A neutral objective timer: alive now, or the second its next spawn is recorded. */
    class Timer(val label: String, val objIndex: Int, val alive: Boolean, val nextAt: Int)

    /** A camera shot chosen by the director: from second [from] it looks at a moment. */
    class Shot(val from: Int, val until: Int, val moment: Moment)

    /** Multi-kill names as each game's announcer calls them (window in seconds between kills). */
    fun multiNames(titleId: String): Pair<List<String>, Int> = when (titleId) {
        "mlbb" -> listOf("Double Kill", "Triple Kill", "Maniac", "Savage") to 10
        "dota2" -> listOf("Double Kill", "Triple Kill", "Ultra Kill", "Rampage") to 18
        else -> listOf("Double Kill", "Triple Kill", "Quadra Kill", "Penta Kill") to 10
    }

    private fun wipeName(titleId: String) = if (titleId == "lol" || titleId == "wildrift") "Ace" else "Team wipe"

    /** Every kill of a MOBA game with its broadcast tag (multi-kill, first blood, shutdown), in order. */
    fun mobaFeed(t: TitleDef, ev: List<Ev>): List<Feed> {
        val (names, window) = multiNames(t.id)
        val out = ArrayList<Feed>()
        val lastKill = HashMap<Int, Int>(); val chain = HashMap<Int, Int>(); val streak = HashMap<Int, Int>()
        var first = true
        for (e in ev) {
            if (e.kind != "kill") continue
            if (e.team < 0) { out.add(Feed(e.t, -1, e.b, -1, 0, "executed")); streak[e.b] = 0; continue }
            val c = if (lastKill[e.a]?.let { e.t - it <= window } == true) (chain[e.a] ?: 1) + 1 else 1
            chain[e.a] = c; lastKill[e.a] = e.t
            val shutdown = (streak[e.b] ?: 0) >= 3
            streak[e.a] = (streak[e.a] ?: 0) + 1; streak[e.b] = 0; chain[e.b] = 0
            val tag = when {
                first -> "First Blood"
                c >= 2 -> names[minOf(c, 5) - 2]
                shutdown -> "Shutdown"
                else -> ""
            }
            first = false
            out.add(Feed(e.t, e.a, e.b, e.team, Integer.bitCount(e.m), tag))
        }
        return out
    }

    /** The latest kills up to second [ti] (most recent first), as a broadcast kill feed shows them. */
    fun feedAt(feed: List<Feed>, ti: Int, keep: Int = 12, max: Int = 5): List<Feed> = feed.filter { it.t <= ti && ti - it.t <= keep }.takeLast(max).reversed()

    /** Key moments of a MOBA game, in time order (bookmarks for the replay). */
    fun mobaMoments(w: World?, t: TitleDef, g: GameRecord, ev: List<Ev>): List<Moment> {
        val out = ArrayList<Moment>()
        val feed = mobaFeed(t, ev)
        val (names, _) = multiNames(t.id)
        val half = g.lines.size / 2
        // Deaths with their respawn second, to detect a whole team dead at once.
        val deadUntil = HashMap<Int, Int>()
        val teamOf = HashMap<Int, Int>().also { m -> for (l in g.lines) m[l.playerId] = l.team }
        val topObj = t.moba?.objectives?.maxByOrNull { it.value }?.id
        var firstTower = true
        for (e in ev) {
            when (e.kind) {
                "kill" -> {
                    if (e.b >= 0) deadUntil[e.b] = if (e.n > e.t) e.n else e.t + 10
                    val vt = teamOf[e.b] ?: continue
                    if (half > 0 && teamOf.filterValues { it == vt }.keys.all { (deadUntil[it] ?: -1) > e.t }) out.add(Moment(e.t, "wipe", 1 - vt, wipeName(t.id), 8))
                }
                "obj" -> {
                    val major = t.moba?.objectives?.getOrNull(e.x)?.id == topObj
                    out.add(Moment(e.t, "objective", e.team, e.text.ifBlank { "Objective" }, if (major) 6 else 3, if (e.n >= 0) MobaEngine.unpackX(e.n) else -1f, if (e.n >= 0) MobaEngine.unpackY(e.n) else -1f))
                }
                "steal" -> out.add(Moment(e.t, "steal", e.team, "${e.text.ifBlank { "Objective" }} stolen", 7, player = e.a))
                "tower" -> { if (firstTower) out.add(Moment(e.t, "tower", e.team, "First tower", 3)); firstTower = false }
                "base" -> out.add(Moment(e.t, "base", e.team, "Base structure down", 4))
                "core" -> out.add(Moment(e.t, "core", e.team, "${t.moba?.structures?.coreName ?: "Core"} destroyed", 10))
                "fight" -> if (e.a + e.b >= 4) out.add(Moment(e.t, "fight", e.team, "Teamfight won ${e.a} for ${e.b}", if (e.a >= 4 && e.b <= 1) 6 else 5, MobaEngine.unpackX(e.n), MobaEngine.unpackY(e.n)))
            }
        }
        for (f in feed) {
            if (f.tag.isEmpty() || f.tag == "executed") continue
            val p = when (f.tag) { "First Blood" -> 4; "Shutdown" -> 3; names[0] -> 3; names[1] -> 5; names[2] -> 7; else -> 9 }
            out.add(Moment(f.t, if (f.tag == "First Blood") "firstblood" else if (f.tag == "Shutdown") "shutdown" else "multikill", f.team, f.tag, p, player = f.killer))
        }
        return out.sortedWith(compareBy<Moment> { it.t }.thenByDescending { it.priority })
    }

    /**
     * Objective timers at second [ti]: each objective alive now, or the second of its next recorded
     * spawn (the stream knows it exactly; a broadcast shows the same respawn timers the game shows).
     */
    fun mobaTimers(t: TitleDef, ev: List<Ev>, ti: Int): List<Timer> {
        val objs = t.moba?.objectives ?: return emptyList()
        return objs.mapIndexedNotNull { i, o ->
            var alive = false
            for (e in ev) { if (e.t > ti) break; if (e.kind == "spawn" && e.x == i) alive = true; if (e.kind == "obj" && e.x == i) alive = false }
            val next = if (alive) ti else ev.firstOrNull { it.t > ti && it.kind == "spawn" && it.x == i }?.t ?: return@mapIndexedNotNull null
            Timer(o.name, i, alive, next)
        }
    }

    /**
     * The director's shots: a cut to each moment worth showing, but never sooner than [cooldown]
     * seconds after the last cut unless the new moment matters clearly more (priority +3).
     */
    fun director(moments: List<Moment>, cooldown: Int = 8, hold: Int = 7, minPriority: Int = 3): List<Shot> {
        val out = ArrayList<Shot>()
        for (m in moments) {
            if (m.priority < minPriority) continue
            val cur = out.lastOrNull()
            if (cur != null && m.t - cur.from < cooldown && m.priority < cur.moment.priority + 3) continue
            if (cur != null && cur.until > m.t) out[out.size - 1] = Shot(cur.from, m.t, cur.moment)
            out.add(Shot(m.t, m.t + hold, m))
        }
        return out
    }

    fun shotAt(shots: List<Shot>, ti: Int): Shot? = shots.lastOrNull { it.from <= ti && ti < it.until }

    // ------------------------------------------------------------------ shooters

    /** A shooter round as the broadcast shows it at one second. */
    class Round(
        val index: Int, val attack: Int, val score: IntArray, val buys: Pair<String, String>, val phase: String,
        val alive: IntArray, val clock: Int, val plantedAt: Int, val bombLeft: Int, val defused: Boolean, val site: Int,
        val credits: IntArray, val loadout: IntArray, val matchPoint: Int,
    )

    /** Seconds from plant to detonation. */
    fun bombTime(titleId: String) = if (titleId == "cs2") 40 else 45

    /** Buy-phase length before a round's action (seconds of the recorded round clock). */
    private const val BUY = 4

    fun shooterRound(t: TitleDef, g: GameRecord, ev: List<Ev>, ti: Int, winsNeeded: Int = 13): Round? {
        val rounds = ev.filter { it.kind == "round" }
        if (rounds.isEmpty()) return null
        val ri = rounds.indexOfLast { it.t <= ti }.coerceAtLeast(0)
        val r = rounds[ri]
        val end = ev.firstOrNull { it.kind == "roundEnd" && it.t >= r.t }
        val inRound = ev.filter { it.t >= r.t && it.t <= ti && (end == null || it.t <= end.t) }
        val score = IntArray(2)
        for (e in ev) { if (e.t > ti) break; if (e.kind == "roundEnd") { score[0] = e.a; score[1] = e.b } }
        val teamOf = HashMap<Int, Int>().also { m -> for (l in g.lines) m[l.playerId] = l.team }
        val alive = IntArray(2) { s -> g.lines.count { it.team == s } }
        for (e in inRound) if (e.kind == "kill") teamOf[e.b]?.let { alive[it] = (alive[it] - 1).coerceAtLeast(0) }
        val plant = inRound.lastOrNull { it.kind == "plant" }
        val defused = inRound.any { it.kind == "defuse" }
        val ended = end != null && end.t <= ti
        val phase = when { ended -> "ended"; ti - r.t < BUY && inRound.none { it.kind == "kill" } -> "buy"; plant != null -> "post-plant"; else -> "live" }
        val buys = r.text.split("|").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        // Economy per team at the round start (the recorded per-player credits and loadout value).
        val credits = IntArray(2); val loadout = IntArray(2)
        g.replay?.let { rp ->
            val si = ReplayCodec.snapAt(rp, r.t)
            for ((pid, s) in teamOf) { if (s !in 0..1) continue; credits[s] += ReplayCodec.value(rp, si, pid, 0); loadout[s] += ReplayCodec.value(rp, si, pid, 1) }
        }
        val mp = when { score[0] == winsNeeded - 1 && score[1] < winsNeeded - 1 -> 0; score[1] == winsNeeded - 1 && score[0] < winsNeeded - 1 -> 1; score[0] == winsNeeded - 1 && score[1] == winsNeeded - 1 -> 2; else -> -1 }
        return Round(ri, r.team, score, buys, phase, alive, (ti - r.t).coerceAtLeast(0), plant?.t ?: -1,
            plant?.let { (bombTime(t.id) - (ti - it.t)).coerceAtLeast(0) } ?: -1, defused, plant?.x ?: -1, credits, loadout, mp)
    }

    /** Kills of a shooter map with round multi-kill tags (weapon id in [Feed.x]). */
    fun shooterFeed(g: GameRecord, ev: List<Ev>): List<Feed> {
        val teamOf = HashMap<Int, Int>().also { m -> for (l in g.lines) m[l.playerId] = l.team }
        val out = ArrayList<Feed>()
        var inRound = HashMap<Int, Int>()
        var first = false
        for (e in ev) {
            if (e.kind == "round") { inRound = HashMap(); first = true; continue }
            if (e.kind != "kill") continue
            val k = (inRound[e.a] ?: 0) + 1; inRound[e.a] = k
            val tag = when { k >= 5 -> "ACE"; k >= 3 -> "${k}K"; first -> "opening"; else -> "" }
            first = false
            out.add(Feed(e.t, e.a, e.b, teamOf[e.a] ?: 0, if (e.n >= 0) 1 else 0, tag, e.x))
        }
        return out
    }

    /** Key moments of a shooter map: opening kills of decisive rounds, multi-kills, aces, clutches, plants and defuses, eco wins, match points. */
    fun shooterMoments(t: TitleDef, g: GameRecord, ev: List<Ev>, winsNeeded: Int = 13): List<Moment> {
        val out = ArrayList<Moment>()
        val teamOf = HashMap<Int, Int>().also { m -> for (l in g.lines) m[l.playerId] = l.team }
        val rounds = ev.filter { it.kind == "round" }
        for ((ri, r) in rounds.withIndex()) {
            val end = ev.firstOrNull { it.kind == "roundEnd" && it.t >= r.t } ?: continue
            val inRound = ev.filter { it.t >= r.t && it.t <= end.t }
            val kills = inRound.filter { it.kind == "kill" }
            val byPlayer = kills.groupingBy { it.a }.eachCount()
            for ((pid, k) in byPlayer) if (k >= 3) out.add(Moment(kills.last { it.a == pid }.t, if (k >= 5) "ace" else "multikill", teamOf[pid] ?: -1, if (k >= 5) "ACE" else "${k}K", if (k >= 5) 9 else 4 + k - 3, player = pid))
            // Clutch: the winner's last player alive against two or more.
            val alive = IntArray(2) { s -> g.lines.count { it.team == s } }
            var clutch: Pair<Int, Int>? = null
            for (e in kills) {
                teamOf[e.b]?.let { alive[it]-- }
                val win = end.team.coerceIn(0, 1)
                if (alive[win] == 1 && alive[1 - win] >= 2 && clutch == null) clutch = alive[1 - win] to e.t
            }
            val win = end.team.coerceIn(0, 1)
            if (clutch != null && alive[win] >= 1) {
                val survivor = g.lines.filter { it.team == win }.map { it.playerId }.firstOrNull { pid -> kills.none { it.b == pid } }
                out.add(Moment(end.t, "clutch", win, "1v${clutch.first} clutch", 8, player = survivor ?: -1))
            }
            inRound.firstOrNull { it.kind == "plant" }?.let { out.add(Moment(it.t, "plant", it.team, "${if (t.id == "cs2") "Bomb" else "Spike"} planted", 3, player = it.a)) }
            inRound.firstOrNull { it.kind == "defuse" }?.let { out.add(Moment(it.t, "defuse", it.team, "Defused", 6, player = it.a)) }
            val buys = r.text.split("|")
            val winnerBuy = buys.getOrNull(if (win == r.team) 0 else 1) ?: ""
            val loserBuy = buys.getOrNull(if (win == r.team) 1 else 0) ?: ""
            if (winnerBuy in setOf("eco", "save") && loserBuy in setOf("full", "full buy", "buy")) out.add(Moment(end.t, "eco", win, "Eco round win", 6))
            if (end.a == winsNeeded - 1 || end.b == winsNeeded - 1) if (end.a != end.b || end.a == winsNeeded - 1) {
                if ((end.a == winsNeeded - 1) != (end.b == winsNeeded - 1)) out.add(Moment(end.t, "matchpoint", if (end.a == winsNeeded - 1) 0 else 1, "Match point", 5))
            }
            kills.firstOrNull()?.let { if (ri == 0 || ri == 12) out.add(Moment(it.t, "opening", teamOf[it.a] ?: -1, "Opening kill of the half", 2, player = it.a)) }
        }
        return out.sortedWith(compareBy<Moment> { it.t }.thenByDescending { it.priority })
    }
}

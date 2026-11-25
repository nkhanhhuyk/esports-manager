package dev.uem.em.sim

import dev.uem.em.core.Rng
import dev.uem.em.model.CompTier
import dev.uem.em.model.Fixture
import dev.uem.em.model.GameRecord
import dev.uem.em.model.Line
import dev.uem.em.model.MatchRecord
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.Engine

/**
 * A series: simulated whole for background matches (from a frozen view of the world, so many run in
 * parallel on all CPU cores and are applied afterwards in a fixed order), or played game by game
 * when you play a match live (your draft or map veto, then each game watched on the 2D map).
 */
class SeriesOutcome(val fixture: Fixture, val winner: Int, val wins: IntArray, val record: MatchRecord, val mvp: Int)

/**
 * A match event (stored compactly in [dev.uem.em.model.Replay]). Teams are series team indexes once
 * a game is recorded. MOBA kill: a killer, b victim, x location, m assist bits (killer side's player
 * order), n respawn second. Shooter kill: x 1 after the plant, m location code, n assister id.
 */
class Ev(val t: Int, val kind: String, val team: Int, val a: Int = -1, val b: Int = -1, val x: Int = 0, val text: String = "", val m: Int = 0, val n: Int = -1)

class LiveSeries(val w: World, val f: Fixture, val lineups: Array<List<Int>>, prepPlans: Array<dev.uem.em.model.PrepPlan?>? = null) {
    val t = Catalogue.get(f.titleId)
    /** Each side's locked match preparation (Prepare for Match), read by the draft and the veto. */
    val prep: Array<dev.uem.em.model.PrepPlan?> = prepPlans ?: Array(2) { dev.uem.em.mgmt.Prep.effective(w, f, it) }
    val comp = w.comps[f.compId]
    val teams = arrayOf(w.teams[f.a], w.teams[f.b])
    val patch = Patch.of(w, t)
    private val isFinal = f.nodeId.isNotEmpty() && comp.stages[f.stageIdx].bracket.firstOrNull { it.id == f.nodeId }?.isFinal == true
    val pressure = if (isFinal) 1.0 else if (f.nodeId.isNotEmpty()) 0.5 else if (comp.tier == CompTier.WORLDS || comp.tier == CompTier.INTERNATIONAL) 0.4 else 0.0
    val need = f.bestOf / 2 + 1
    val wins = IntArray(2)
    val games = ArrayList<GameRecord>()
    val fearless = HashSet<String>()
    /** Fearless rule of this series and what each team played / what was banned in earlier games. */
    val fearlessMode = if (dev.uem.em.comp.Fearless.applies(t)) dev.uem.em.comp.Fearless.effective(t, dev.uem.em.comp.Fearless.modeOf(comp)) else "none"
    val playedBy = arrayOf(HashSet<String>(), HashSet<String>())
    val bannedBefore = HashSet<String>()
    val players = Array(2) { s -> lineups[s].filter { it >= 0 }.map { w.players[it] } }
    var maps: List<String> = emptyList()
    var lastLoser = -1
    var gi = 0
    var lastEvents: List<Ev> = emptyList()
        private set

    val done get() = wins[0] >= need || wins[1] >= need || gi >= 9

    class Setup(val chooser: Int, val blue: Int, val firstPick: Int, val choice: Int, val byLoser: Boolean, val mapId: String, val rng: Rng)

    fun rngFor(game: Int) = Rng.derive(w.seed, f.id, "g", game)

    /** Sides for the next game: coin toss first, then the previous game's loser chooses. */
    fun setup(): Setup {
        val rng = rngFor(gi)
        val mapId = if (maps.isNotEmpty()) maps[minOf(gi, maps.size - 1)] else t.maps[0].id
        val byLoser = lastLoser >= 0
        val chooser = if (byLoser) lastLoser else if (rng.chance(0.5)) 0 else 1
        var blue = chooser
        var firstPick = chooser
        var choice = 0
        if (t.isMoba && t.id in SPLIT_SELECTION) {
            if (rng.chance(0.6)) { blue = chooser; firstPick = if (rng.chance(0.7)) 1 - chooser else chooser }
            else { choice = 1; firstPick = chooser; blue = if (rng.chance(0.7)) 1 - chooser else chooser }
        }
        return Setup(chooser, blue, firstPick, choice, byLoser, mapId, rng)
    }

    /** Champions each team may not pick in the next game under the series' Fearless rule. */
    fun locked(): Array<Set<String>> = when (fearlessMode) {
        "soft" -> arrayOf(HashSet(playedBy[0]), HashSet(playedBy[1]))
        "hard" -> (playedBy[0] + playedBy[1]).let { arrayOf(it, it) }
        "insane" -> (playedBy[0] + playedBy[1] + bannedBefore).let { arrayOf(it, it) }
        else -> arrayOf(emptySet(), emptySet())
    }

    private fun remember(g: GameRecord) {
        for (l in g.lines) if (l.charId.isNotEmpty()) { playedBy[l.team].add(l.charId); fearless.add(l.charId) }
        bannedBefore.addAll(g.bans)
    }

    /** The composition identity each side drafts toward: the prepared style, else the team's own. */
    fun styleOf(i: Int) = prep[i]?.style?.takeIf { it.isNotBlank() } ?: teams[i].style

    fun draftSession(s: Setup) = DraftSession(t, patch, players, arrayOf(styleOf(0), styleOf(1)), s.firstPick, locked(), s.rng,
        DoubleArray(2) { i -> (Draft.knowledge(w, teams[i]) + dev.uem.em.mgmt.Prep.knowledgeBonus(prep[i])).coerceAtMost(1.0) }, IntArray(2) { i -> Draft.banTendency(w, teams[i]) },
        Array(2) { i -> dev.uem.em.mgmt.Prep.comfort(prep[i], players[i], dev.uem.em.mgmt.Scrims.bonus(w, teams[i])) }, dev.uem.em.titles.RoleMeta.bot(w, t.id), intel(), dev.uem.em.titles.RoleMeta.top(w, t.id))

    /** What the earlier games of this series, and each team's recent matches, showed (the draft adapts to it). */
    fun intel() = SeriesIntel.of(games, arrayOf(teams[0].shown, teams[1].shown))

    /** Simulate one game with a finished draft; `record` keeps events for the 2D view. */
    /** Tactical timeouts allowed per team per map in this series' competition. */
    val timeoutsAllowed: Int get() = comp.timeouts.takeIf { it >= 0 } ?: TacticalEngine.defaultTimeouts(t.id)
    /** Coach comms in MOBA fights under this competition's rules. */
    val coachComms: Boolean get() = t.isMoba && (comp.coachComms == 1 || (comp.coachComms == -1 && t.id in w.coachCommsTitles))
    fun coachQ(i: Int) = dev.uem.em.mgmt.Staffing.inGame(w, teams[i])
    private var lastLoserBefore = -1
    /** Fast simulation: games that keep no replay use the quick model. */
    var quick = false

    /** Undo the last game (to replay it, e.g. with a timeout the user called). */
    fun undoLast() {
        val g = games.removeLastOrNull() ?: return
        wins[g.winner]--
        gi--
        lastLoser = lastLoserBefore
    }

    fun play(s: Setup, draft: Draft.Result, record: Boolean = false, timeouts: TimeoutPlan? = null, mobaCalls: List<Triple<Int, Int, String>> = emptyList()): GameRecord {
        lastLoserBefore = lastLoser
        // The game's own random stream: the same game can be replayed exactly (with a different timeout).
        val grng = Rng.derive(w.seed, f.id, "play", gi)
        val rec = if (record) Recorder() else null
        if (players[0].isEmpty() || players[1].isEmpty()) {
            val wnr = if (players[0].isEmpty()) 1 else 0
            wins[wnr] = need
            return GameRecord(wnr, 0, s.mapId, lines = emptyList()).also { games.add(it); gi++ }
        }
        val chars = draft.assign
        val evals = Array(2) { si ->
            val mine = lineups[si].filter { it >= 0 }.mapNotNull { pid -> chars[si][pid]?.let { t.charIndex[it] } }
            Draft.evaluate(t, patch, mine, draft.picks[1 - si].mapNotNull { t.charIndex[it] }, players[si], styleOf(si))
        }
        val ctx = Array(2) { si -> TeamContexts.build(w, teams[si], lineups[si], si, chars[si], s.mapId, pressure, grng) }
        val g: GameRecord
        val winnerSeries: Int
        if (t.isMoba) {
            // Map-side order: the blue team is simulated as side 0.
            val order = if (s.blue == 0) intArrayOf(0, 1) else intArrayOf(1, 0)
            val sideCtx = order.mapIndexed { si, sx -> TeamCtx(ctx[sx].team, si, ctx[sx].players, ctx[sx].phase, ctx[sx].overall, ctx[sx].aggression) }
            val out = if (quick && rec == null) QuickSim.moba(t, sideCtx, arrayOf(evals[order[0]], evals[order[1]]), grng, w)
                else MobaEngine.simulate(t, sideCtx, arrayOf(evals[order[0]], evals[order[1]]), grng, patch, rec,
                MobaEngine.Opts(coachComms, doubleArrayOf(coachQ(order[0]), coachQ(order[1])), w, mobaCalls.map { (sec, team, call) -> Triple(sec, order.indexOf(team), call) }))
            winnerSeries = order[out.winner]
            // Recorded events use series team indexes.
            if (rec != null) {
                val mapped = rec.events.map { e -> Ev(e.t, e.kind, if (e.team >= 0) order[e.team] else -1, e.a, e.b, e.x, e.text, e.m, e.n) }
                rec.events.clear(); rec.events.addAll(mapped)
            }
            val lines = out.book.lines.map { l -> Line(l.playerId, order[l.team], l.roleId, l.charId, l.rating, l.s, l.items, l.runes, l.spells) }
            // Neutral objectives taken (code * 2 + side), in series team order.
            val objs = IntArray(out.objs.size) { i -> (out.objs[i] shr 1) * 2 + order[out.objs[i] and 1] }
            val score = if (s.blue == 0) out.score else intArrayOf(out.score[1], out.score[0])
            g = GameRecord(winnerSeries, out.duration, s.mapId, s.blue, intArrayOf(s.chooser, s.choice, if (s.byLoser) 1 else 0, s.firstPick), draft.bans, draft.picks[0] + draft.picks[1], score, lines, draftOrder = draft.order, objs = objs, draftNotes = draft.notes)
        } else {
            val firstAttack = if (grng.chance(0.5)) 0 else 1
            val out = if (quick && rec == null) QuickSim.shooter(t, ctx.toList(), evals, firstAttack, grng)
                else TacticalEngine.simulate(t, ctx.toList(), evals, firstAttack, grng, patch, rec, s.mapId, timeouts ?: TimeoutPlan(timeoutsAllowed, booleanArrayOf(true, true), emptyList(), doubleArrayOf(coachQ(0), coachQ(1))))
            winnerSeries = out.winner
            g = GameRecord(winnerSeries, out.duration, s.mapId, firstAttack, IntArray(0), emptyList(), draft.picks[0] + draft.picks[1], out.score, out.book.lines, econ = out.econ)
        }
        if (rec != null) g.replay = ReplayCodec.encode(rec)
        lastEvents = rec?.events ?: emptyList()
        games.add(g)
        remember(g)
        wins[winnerSeries]++
        lastLoser = 1 - winnerSeries
        gi++
        return g
    }

    /** Continue a series from a saved live state (games already played, maps, Fearless pool). */
    fun restore(st: dev.uem.em.model.LiveState) {
        maps = st.maps
        fearless.addAll(st.fearless)
        for (g in st.games) {
            games.add(g)
            remember(g)
            wins[g.winner]++
            lastLoser = 1 - g.winner
            gi++
        }
    }

    fun liveState(spectator: Boolean) = dev.uem.em.model.LiveState(f.id, games.toMutableList(), maps, fearless.toList(), spectator)

    fun outcome(): SeriesOutcome {
        val winner = if (wins[0] >= wins[1]) 0 else 1
        // Series MVP: best average rating on the winning team, measured against how that position usually rates
        // in wins (a carry's good game is not automatically better than a support's great one).
        val sums = HashMap<Int, Pair<Double, Int>>()
        val slotOf = HashMap<Int, String>()
        for (g in games) for (l in g.lines) if (l.team == winner) { sums[l.playerId] = (sums[l.playerId]?.let { it.first + l.rating to it.second + 1 } ?: (l.rating to 1)); slotOf[l.playerId] = l.roleId }
        val tdef = dev.uem.em.titles.Catalogue.get(f.titleId)
        val mvp = sums.maxByOrNull { (pid, v) -> dev.uem.em.sim.StatBook.mvpScore(w, tdef, slotOf[pid], v.first / v.second) }?.key ?: -1
        return SeriesOutcome(f, winner, wins, MatchRecord(f.id, f.day, games), mvp)
    }

    companion object {
        /** Titles where the side chooser picks either a side or the pick order. */
        val SPLIT_SELECTION = setOf("lol", "dota2")
    }
}

object SeriesSim {
    /**
     * A whole series with AI decisions on both sides. It takes exactly the same path as a series
     * watched live (the same map veto, draft session and engine), so watching a match never changes
     * its result; `record` keeps every game's replay.
     */
    fun run(w: World, f: Fixture, lineups: Array<List<Int>>, record: Boolean = false, resume: dev.uem.em.model.LiveState? = null, quick: Boolean = false, prep: Array<dev.uem.em.model.PrepPlan?>? = null): SeriesOutcome {
        val s = LiveSeries(w, f, lineups, prep)
        s.quick = quick && !record
        if (resume != null) s.restore(resume)
        else if (s.t.engine == Engine.TACTICAL) { val v = VetoSession(s); while (!v.done) v.aiAct() }
        while (!s.done) {
            val setup = s.setup()
            s.play(setup, s.draftSession(setup).runAll(), record)
        }
        return s.outcome()
    }
}

/**
 * Shooter map veto played step by step. Team 0 is the series' first team. Bo1: bans until one map
 * is left; Bo3: ban, ban, pick, pick, ban, ban, decider; Bo5: ban, ban, then picks, decider.
 */
class VetoSession(val s: LiveSeries) {
    val pool = s.t.maps.map { it.id }.toMutableList()
    val picks = ArrayList<String>()
    val log = ArrayList<String>()
    private val steps: List<Pair<Int, Boolean>> = when (s.f.bestOf) {
        1 -> List(pool.size - 1) { (it % 2) to true }
        3 -> listOf(0 to true, 1 to true, 0 to false, 1 to false, 0 to true, 1 to true)
        else -> listOf(0 to true, 1 to true, 0 to false, 1 to false, 0 to false, 1 to false)
    }
    var step = 0
        private set

    fun current(): Pair<Int, Boolean>? = steps.getOrNull(step)?.takeIf { pool.size > 1 }
    val done get() = current() == null

    fun act(map: String): Boolean {
        val cur = current() ?: return false
        if (map !in pool) return false
        pool.remove(map)
        if (!cur.second) picks.add(map)
        log.add("${s.teams[cur.first].short} ${if (cur.second) "ban" else "pick"} ${s.t.maps.first { it.id == map }.name}")
        step++
        if (done) finish()
        return true
    }

    fun aiAct() {
        val cur = current() ?: return
        val me = cur.first
        val plan = s.prep[me]
        val oppBest = if (plan?.emphasis == "counter") s.lineups[1 - me].filter { it >= 0 }.let { opp -> pool.maxByOrNull { m -> opp.sumOf { s.w.players[it].profMap[m] ?: 40.0 } } } else null
        val pref = pool.associateWith { m ->
            var v = s.lineups[me].filter { it >= 0 }.sumOf { s.w.players[it].profMap[m] ?: 40.0 } + ((m.hashCode() xor s.f.id) % 17)
            // Prepared map: kept (picked early, never banned); counter study: the opponent's best map goes.
            if (plan?.emphasis == "map" && plan.map == m) v += 120 * plan.effect
            if (oppBest == m) v -= 90 * plan!!.effect
            v
        }
        act(if (cur.second) pool.minBy { pref[it]!! } else pool.maxBy { pref[it]!! })
    }

    private fun finish() {
        if (pool.isNotEmpty() && picks.size < s.f.bestOf) { picks.add(pool[0]); log.add("Decider: ${s.t.maps.first { it.id == pool[0] }.name}") }
        s.maps = picks.toList()
    }
}

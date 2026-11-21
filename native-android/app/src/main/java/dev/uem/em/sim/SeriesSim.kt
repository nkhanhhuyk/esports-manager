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

class LiveSeries(val w: World, val f: Fixture, val lineups: Array<List<Int>>) {
    val t = Catalogue.get(f.titleId)
    val comp = w.comps[f.compId]
    val teams = arrayOf(w.teams[f.a], w.teams[f.b])
    val patch = Patch.of(w, t)
    private val isFinal = f.nodeId.isNotEmpty() && comp.stages[f.stageIdx].bracket.firstOrNull { it.id == f.nodeId }?.isFinal == true
    val pressure = if (isFinal) 1.0 else if (f.nodeId.isNotEmpty()) 0.5 else if (comp.tier == CompTier.WORLDS || comp.tier == CompTier.INTERNATIONAL) 0.4 else 0.0
    val need = f.bestOf / 2 + 1
    val wins = IntArray(2)
    val games = ArrayList<GameRecord>()
    val fearless = HashSet<String>()
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

    fun locked(): Array<Set<String>> = if (comp.fearless) arrayOf(fearless, fearless) else arrayOf(emptySet(), emptySet())

    fun draftSession(s: Setup) = DraftSession(t, patch, players, arrayOf(teams[0].style, teams[1].style), s.firstPick, locked(), s.rng)

    /** Simulate one game with a finished draft; `record` keeps events for the 2D view. */
    fun play(s: Setup, draft: Draft.Result, record: Boolean = false): GameRecord {
        val rec = if (record) Recorder() else null
        if (players[0].isEmpty() || players[1].isEmpty()) {
            val wnr = if (players[0].isEmpty()) 1 else 0
            wins[wnr] = need
            return GameRecord(wnr, 0, s.mapId, lines = emptyList()).also { games.add(it); gi++ }
        }
        val chars = draft.assign
        val evals = Array(2) { si ->
            val mine = lineups[si].filter { it >= 0 }.mapNotNull { pid -> chars[si][pid]?.let { t.charIndex[it] } }
            Draft.evaluate(t, patch, mine, draft.picks[1 - si].mapNotNull { t.charIndex[it] }, players[si], teams[si].style)
        }
        val ctx = Array(2) { si -> TeamContexts.build(w, teams[si], lineups[si], si, chars[si], s.mapId, pressure, s.rng) }
        val g: GameRecord
        val winnerSeries: Int
        if (t.isMoba) {
            // Map-side order: the blue team is simulated as side 0.
            val order = if (s.blue == 0) intArrayOf(0, 1) else intArrayOf(1, 0)
            val sideCtx = order.mapIndexed { si, sx -> TeamCtx(ctx[sx].team, si, ctx[sx].players, ctx[sx].phase, ctx[sx].overall, ctx[sx].aggression) }
            val out = MobaEngine.simulate(t, sideCtx, arrayOf(evals[order[0]], evals[order[1]]), s.rng, patch, rec)
            winnerSeries = order[out.winner]
            // Recorded events use series team indexes.
            if (rec != null) {
                val mapped = rec.events.map { e -> Ev(e.t, e.kind, if (e.team >= 0) order[e.team] else -1, e.a, e.b, e.x, e.text, e.m, e.n) }
                rec.events.clear(); rec.events.addAll(mapped)
            }
            val lines = out.book.lines.map { l -> Line(l.playerId, order[l.team], l.roleId, l.charId, l.rating, l.s) }
            val score = if (s.blue == 0) out.score else intArrayOf(out.score[1], out.score[0])
            g = GameRecord(winnerSeries, out.duration, s.mapId, s.blue, intArrayOf(s.chooser, s.choice, if (s.byLoser) 1 else 0), draft.bans, draft.picks[0] + draft.picks[1], score, lines)
        } else {
            val firstAttack = if (s.rng.chance(0.5)) 0 else 1
            val out = TacticalEngine.simulate(t, ctx.toList(), evals, firstAttack, s.rng, patch, rec)
            winnerSeries = out.winner
            g = GameRecord(winnerSeries, out.duration, s.mapId, firstAttack, IntArray(0), emptyList(), draft.picks[0] + draft.picks[1], out.score, out.book.lines)
        }
        if (rec != null) g.replay = ReplayCodec.encode(rec)
        lastEvents = rec?.events ?: emptyList()
        games.add(g)
        fearless.addAll(draft.picks[0])
        fearless.addAll(draft.picks[1])
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
            wins[g.winner]++
            lastLoser = 1 - g.winner
            gi++
        }
    }

    fun liveState(spectator: Boolean) = dev.uem.em.model.LiveState(f.id, games.toMutableList(), maps, fearless.toList(), spectator)

    fun outcome(): SeriesOutcome {
        val winner = if (wins[0] >= wins[1]) 0 else 1
        // Series MVP: best average rating on the winning team.
        val sums = HashMap<Int, Pair<Double, Int>>()
        for (g in games) for (l in g.lines) if (l.team == winner) sums[l.playerId] = (sums[l.playerId]?.let { it.first + l.rating to it.second + 1 } ?: (l.rating to 1))
        val mvp = sums.maxByOrNull { it.value.first / it.value.second }?.key ?: -1
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
    fun run(w: World, f: Fixture, lineups: Array<List<Int>>, record: Boolean = false, resume: dev.uem.em.model.LiveState? = null): SeriesOutcome {
        val s = LiveSeries(w, f, lineups)
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
        val pref = pool.associateWith { m -> s.lineups[cur.first].filter { it >= 0 }.sumOf { s.w.players[it].profMap[m] ?: 40.0 } + ((m.hashCode() xor s.f.id) % 17) }
        act(if (cur.second) pool.minBy { pref[it]!! } else pool.maxBy { pref[it]!! })
    }

    private fun finish() {
        if (pool.isNotEmpty() && picks.size < s.f.bestOf) { picks.add(pool[0]); log.add("Decider: ${s.t.maps.first { it.id == pool[0] }.name}") }
        s.maps = picks.toList()
    }
}

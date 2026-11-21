package dev.uem.em.sim

import dev.uem.em.core.Rng
import dev.uem.em.model.CompTier
import dev.uem.em.model.Fixture
import dev.uem.em.model.GameRecord
import dev.uem.em.model.MatchRecord
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.Engine

/**
 * Simulates a whole series from a frozen view of the world: it only reads players and teams, so
 * many series of the same day run in parallel on all CPU cores; results are applied afterwards in
 * a fixed order (deterministic whichever thread ran which series).
 */
class SeriesOutcome(val fixture: Fixture, val winner: Int, val wins: IntArray, val record: MatchRecord, val mvp: Int)

object SeriesSim {
    /** Titles where the side chooser picks either a side or the pick order (the other team gets the other choice). */
    private val SPLIT_SELECTION = setOf("lol", "dota2")

    fun run(w: World, f: Fixture, lineups: Array<List<Int>>): SeriesOutcome {
        val t = Catalogue.get(f.titleId)
        val c = w.comps[f.compId]
        val teams = arrayOf(w.teams[f.a], w.teams[f.b])
        val patch = Patch.of(w, t)
        val stage = c.stages[f.stageIdx]
        val isFinal = f.nodeId.isNotEmpty() && stage.bracket.firstOrNull { it.id == f.nodeId }?.isFinal == true
        val pressure = if (isFinal) 1.0 else if (f.nodeId.isNotEmpty()) 0.5 else if (c.tier == CompTier.WORLDS || c.tier == CompTier.INTERNATIONAL) 0.4 else 0.0
        val need = f.bestOf / 2 + 1
        val wins = IntArray(2)
        val games = ArrayList<GameRecord>()
        val fearless = HashSet<String>()
        val players = Array(2) { s -> lineups[s].filter { it >= 0 }.map { w.players[it] } }
        val maps = if (t.engine == Engine.TACTICAL) veto(w, f, t, lineups) else emptyList()
        var lastLoser = -1
        var gi = 0
        while (wins[0] < need && wins[1] < need && gi < 9) {
            val rng = Rng.derive(w.seed, f.id, "g", gi)
            val mapId = if (maps.isNotEmpty()) maps[minOf(gi, maps.size - 1)] else t.maps[0].id
            if (players[0].isEmpty() || players[1].isEmpty()) {
                val wnr = if (players[0].isEmpty()) 1 else 0
                wins[wnr] = need
                games.add(GameRecord(wnr, 0, mapId, lines = emptyList()))
                break
            }
            // Sides: coin toss for game 1, then the previous game's loser chooses.
            val byLoser = lastLoser >= 0
            val chooser = if (byLoser) lastLoser else if (rng.chance(0.5)) 0 else 1
            var blue = chooser
            var firstPick = chooser
            var choice = 0
            if (t.isMoba && t.id in SPLIT_SELECTION) {
                if (rng.chance(0.6)) { blue = chooser; firstPick = if (rng.chance(0.7)) 1 - chooser else chooser }
                else { choice = 1; firstPick = chooser; blue = if (rng.chance(0.7)) 1 - chooser else chooser }
            }
            val locked: Array<Set<String>> = if (c.fearless) arrayOf(fearless, fearless) else arrayOf(emptySet(), emptySet())
            val draft = Draft.run(t, patch, players, arrayOf(teams[0].style, teams[1].style), firstPick, locked, rng)
            val chars = draft.assign
            val evals = Array(2) { s ->
                val mine = lineups[s].filter { it >= 0 }.mapNotNull { pid -> chars[s][pid]?.let { t.charIndex[it] } }
                val enemy = draft.picks[1 - s].mapNotNull { t.charIndex[it] }
                Draft.evaluate(t, patch, mine, enemy, players[s], teams[s].style)
            }
            val ctx = Array(2) { s -> TeamContexts.build(w, teams[s], lineups[s], s, chars[s], mapId, pressure, rng) }
            val outcome: GameOutcome
            val winnerSeries: Int
            if (t.isMoba) {
                // Map-side order: the blue team is simulated as side 0.
                val order = if (blue == 0) intArrayOf(0, 1) else intArrayOf(1, 0)
                val sideCtx = order.mapIndexed { si, s -> TeamCtx(ctx[s].team, si, ctx[s].players, ctx[s].phase, ctx[s].overall, ctx[s].aggression) }
                outcome = MobaEngine.simulate(t, sideCtx, arrayOf(evals[order[0]], evals[order[1]]), rng, patch)
                winnerSeries = order[outcome.winner]
                // Box-score lines back to series team order.
                val lines = outcome.book.lines.map { l -> dev.uem.em.model.Line(l.playerId, order[l.team], l.roleId, l.charId, l.rating, l.s) }
                val score = if (blue == 0) outcome.score else intArrayOf(outcome.score[1], outcome.score[0])
                games.add(GameRecord(winnerSeries, outcome.duration, mapId, blue, intArrayOf(chooser, choice, if (byLoser) 1 else 0), draft.bans, draft.picks[0] + draft.picks[1], score, lines))
            } else {
                val firstAttack = if (rng.chance(0.5)) 0 else 1
                outcome = TacticalEngine.simulate(t, ctx.toList(), evals, firstAttack, rng, patch)
                winnerSeries = outcome.winner
                games.add(GameRecord(winnerSeries, outcome.duration, mapId, firstAttack, IntArray(0), emptyList(), draft.picks[0] + draft.picks[1], outcome.score, outcome.book.lines))
            }
            fearless.addAll(draft.picks[0])
            fearless.addAll(draft.picks[1])
            wins[winnerSeries]++
            lastLoser = 1 - winnerSeries
            gi++
        }
        val winner = if (wins[0] >= wins[1]) 0 else 1
        // Series MVP: best average rating on the winning team.
        val sums = HashMap<Int, Pair<Double, Int>>()
        for (g in games) for (l in g.lines) if (l.team == winner) sums[l.playerId] = (sums[l.playerId]?.let { it.first + l.rating to it.second + 1 } ?: (l.rating to 1))
        val mvp = sums.maxByOrNull { it.value.first / it.value.second }?.key ?: -1
        return SeriesOutcome(f, winner, wins, MatchRecord(f.id, f.day, games), mvp)
    }

    /** Shooter map veto: teams ban their weakest and pick their strongest maps; the rest is the decider. */
    private fun veto(w: World, f: Fixture, t: dev.uem.em.titles.TitleDef, lineups: Array<List<Int>>): List<String> {
        val rng = Rng.derive(w.seed, f.id, "veto")
        val pool = t.maps.map { it.id }.toMutableList()
        fun pref(s: Int, m: String) = lineups[s].filter { it >= 0 }.sumOf { w.players[it].profMap[m] ?: 40.0 } + rng.normal(0.0, 15.0)
        val picks = ArrayList<String>()
        val n = f.bestOf
        var turn = 0
        while (pool.size > 1 && picks.size < n - 1) {
            val s = turn % 2
            val banPhase = picks.size == 0 && turn < 2 || (n == 1)
            if (banPhase || pool.size - (n - 1 - picks.size) > 1 && turn >= 2 && picks.size >= 2) {
                pool.remove(pool.minBy { pref(s, it) })
            } else {
                val m = pool.maxBy { pref(s, it) }
                picks.add(m)
                pool.remove(m)
            }
            turn++
        }
        while (picks.size < n && pool.isNotEmpty()) { val m = pool[rng.int(0, pool.size - 1)]; picks.add(m); pool.remove(m) }
        return picks
    }
}

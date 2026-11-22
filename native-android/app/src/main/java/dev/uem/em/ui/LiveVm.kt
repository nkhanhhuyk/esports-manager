package dev.uem.em.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.uem.em.game.Advance
import dev.uem.em.model.GameRecord
import dev.uem.em.model.World
import dev.uem.em.sim.DraftSession
import dev.uem.em.sim.Lineups
import dev.uem.em.sim.LiveSeries
import dev.uem.em.sim.VetoSession

/**
 * A series played or watched live: map veto (shooters) or draft (MOBAs) for each game, then the
 * game on the 2D map from its recorded replay. With `spectator` both teams are run by their coaches
 * (the same AI as for any other match, so watching never changes the result) and every veto and
 * draft step is shown one at a time. Progress is kept in `World.live` after each game, so an
 * unfinished series resumes where it stopped (also after the app is closed). `rev` changes whenever
 * the screen should redraw.
 */
class LiveMatch(val w: World, fixtureId: Int, spectatorWanted: Boolean = false, private val onProgress: () -> Unit = {}) {
    val f = w.fixtures[fixtureId]
    // Both sides' preparation is fixed when the series starts (no-op when resuming a series).
    init { dev.uem.em.mgmt.Prep.lock(w, f) }
    val series = LiveSeries(w, f, arrayOf(
        dev.uem.em.mgmt.Supporters.lineupFor(w, w.comps[f.compId], w.teams[f.a], Lineups.ensure(w, w.teams[f.a]).toList()),
        dev.uem.em.mgmt.Supporters.lineupFor(w, w.comps[f.compId], w.teams[f.b], Lineups.ensure(w, w.teams[f.b]).toList()),
    ))
    /** Watching only: neither team is yours (or you chose to watch). */
    val spectator = spectatorWanted || (!w.isUserTeam(f.a) && !w.isUserTeam(f.b))
    /** The user's series team (0 or 1), or -1 when spectating. */
    val me = if (spectator) -1 else if (w.isUserTeam(f.a)) 0 else 1
    var stage = if (series.t.isMoba) "draft" else "veto"
    var veto: VetoSession? = null
    var setup: LiveSeries.Setup? = null
    var draft: DraftSession? = null
    var game: GameRecord? = null
    var rev by mutableIntStateOf(0)
    /** Set when this series continues from a saved state. */
    val resumed: Boolean

    init {
        val saved = w.live?.takeIf { it.fixtureId == f.id }
        resumed = saved != null && saved.games.isNotEmpty()
        if (saved != null && (saved.games.isNotEmpty() || saved.maps.isNotEmpty())) {
            series.restore(saved)
            if (series.done) finish() else nextGame()
        } else if (!series.t.isMoba) {
            veto = VetoSession(series)
            stage = "veto"
            runAiVeto()
        } else nextGame()
        persist()
    }

    private fun bump() { rev++ }

    private fun persist() {
        if (stage != "result") w.live = series.liveState(spectator)
        onProgress()
    }

    /** One AI step of the veto or draft (spectator autoplay); false when there is nothing to do. */
    fun aiTick(): Boolean {
        when (stage) {
            "veto" -> {
                val v = veto ?: return false
                if (v.done) return false
                v.aiAct()
                if (v.done) { persist(); nextGame() }
                bump()
                return true
            }
            "draft" -> {
                val d = draft ?: return false
                if (d.done) return false
                if (!d.sequential) { d.autoTeam(0); d.autoTeam(1) } else d.aiStep()
                bump()
                return true
            }
        }
        return false
    }

    /** Finish the veto / draft at once (spectator "skip"). */
    fun aiFinish() {
        while (aiTick()) Unit
    }

    private fun runAiVeto() {
        if (spectator) return
        val v = veto ?: return
        while (!v.done && v.current()?.first != me) v.aiAct()
        if (v.done) nextGame()
        bump()
    }

    fun vetoAct(map: String) {
        if (spectator) return
        veto?.act(map)
        runAiVeto()
    }

    fun nextGame() {
        if (series.done) { finish(); return }
        val s = series.setup()
        setup = s
        draft = series.draftSession(s)
        stage = "draft"
        val d = draft!!
        if (d.none) startGame()
        else if (!spectator) { if (!d.sequential) { d.autoTeam(0); d.autoTeam(1) } else runAiDraft() }
        bump()
    }

    private fun runAiDraft() {
        val d = draft ?: return
        while (!d.done && d.current()?.first != me) d.aiStep()
        bump()
    }

    fun draftAct(charId: String) {
        if (spectator) return
        val d = draft ?: return
        val cur = d.current() ?: return
        if (cur.first != me) return
        val ok = if (cur.second) d.ban(charId) else d.pick(charId)
        if (ok) runAiDraft()
    }

    /** Let the coach finish your draft. */
    fun autoDraft() {
        val d = draft ?: return
        while (!d.done) d.aiStep()
        bump()
    }

    /** Rounds before which you called a tactical timeout in the current map (shooters, your matches). */
    val myTimeouts = ArrayList<Int>()

    /** Your team's timeouts are yours to call in a live map; the other coach calls his own. */
    private fun timeoutPlan(): dev.uem.em.sim.TimeoutPlan? = if (series.t.isMoba || me < 0) null
        else dev.uem.em.sim.TimeoutPlan(series.timeoutsAllowed, BooleanArray(2) { it != me }, myTimeouts.map { me to it }, doubleArrayOf(series.coachQ(0), series.coachQ(1)))

    fun timeoutsLeft(): Int {
        val g = game ?: return 0
        val used = g.replay?.let { rp -> dev.uem.em.sim.ReplayCodec.events(rp, series.t).count { it.kind == "timeout" && it.team == me } } ?: 0
        return (series.timeoutsAllowed - used).coerceAtLeast(0)
    }

    /**
     * Call a tactical timeout before round `beforeRound` of the map being watched: the map is played
     * again from there (every earlier round stays exactly as it was) and the view keeps its place.
     */
    fun callTimeout(beforeRound: Int, atSeconds: Int) {
        if (me < 0 || stage != "watch" || series.t.isMoba || timeoutsLeft() <= 0) return
        val s = setup ?: return
        val d = draft ?: return
        myTimeouts.add(beforeRound)
        series.undoLast()
        ReplayJump.seconds = atSeconds
        game = series.play(s, d.runAll(), record = true, timeouts = timeoutPlan())
        persist()
        bump()
    }

    fun startGame() {
        val s = setup ?: return
        val d = draft ?: return
        myTimeouts.clear()
        game = series.play(s, d.runAll(), record = true, timeouts = timeoutPlan())
        stage = "watch"
        persist()
        bump()
    }

    fun afterGame() {
        if (series.done) finish() else nextGame()
    }

    /** Simulate the remaining games without watching (same AI and engine). */
    fun simRest() {
        if (stage == "veto") aiFinish()
        while (!series.done) {
            val s = series.setup()
            series.play(s, series.draftSession(s).runAll(), record = true)
        }
        finish()
    }

    private fun finish() {
        if (stage != "result" && !f.done) Advance.apply(w, series.outcome())
        w.live = null
        stage = "result"
        onProgress()
        bump()
    }
}

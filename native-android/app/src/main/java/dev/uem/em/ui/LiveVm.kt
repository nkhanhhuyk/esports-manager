package dev.uem.em.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.uem.em.game.Advance
import dev.uem.em.model.GameRecord
import dev.uem.em.model.World
import dev.uem.em.sim.DraftSession
import dev.uem.em.sim.Ev
import dev.uem.em.sim.Lineups
import dev.uem.em.sim.LiveSeries
import dev.uem.em.sim.VetoSession

/**
 * A series you play live: map veto (shooters) or draft (MOBAs) for each game, then the game on the
 * 2D map. `rev` changes whenever the screen should redraw.
 */
class LiveMatch(val w: World, fixtureId: Int) {
    val f = w.fixtures[fixtureId]
    val series = LiveSeries(w, f, arrayOf(Lineups.ensure(w, w.teams[f.a]).toList(), Lineups.ensure(w, w.teams[f.b]).toList()))
    /** The user's series team (0 or 1). */
    val me = if (w.isUserTeam(f.a)) 0 else 1
    var stage = if (series.t.isMoba) "draft" else "veto"
    var veto: VetoSession? = if (series.t.isMoba) null else VetoSession(series)
    var setup: LiveSeries.Setup? = null
    var draft: DraftSession? = null
    var game: GameRecord? = null
    var events: List<Ev> = emptyList()
    var rev by mutableIntStateOf(0)

    init {
        if (stage == "veto") runAiVeto() else nextGame()
    }

    private fun bump() { rev++ }

    private fun runAiVeto() {
        val v = veto ?: return
        while (!v.done && v.current()?.first != me) v.aiAct()
        if (v.done) nextGame()
        bump()
    }

    fun vetoAct(map: String) {
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
        if (d.none) startGame() else if (!d.sequential) { d.autoTeam(0); d.autoTeam(1) } else runAiDraft()
        bump()
    }

    private fun runAiDraft() {
        val d = draft ?: return
        while (!d.done && d.current()?.first != me) d.aiStep()
        bump()
    }

    fun draftAct(charId: String) {
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

    fun startGame() {
        val s = setup ?: return
        val d = draft ?: return
        game = series.play(s, d.runAll(), record = true)
        events = series.lastEvents
        stage = "watch"
        bump()
    }

    fun afterGame() {
        if (series.done) finish() else nextGame()
    }

    /** Simulate the remaining games without watching. */
    fun simRest() {
        while (!series.done) {
            val s = series.setup()
            series.play(s, series.draftSession(s).runAll())
        }
        finish()
    }

    private fun finish() {
        if (stage != "result") Advance.apply(w, series.outcome())
        stage = "result"
        bump()
    }
}

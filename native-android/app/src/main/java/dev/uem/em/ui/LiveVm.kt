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
    private val standIns = ArrayList<String>()
    val series = LiveSeries(w, f, Array(2) { si ->
        val team = w.teams[if (si == 0) f.a else f.b]
        val c = w.comps[f.compId]
        dev.uem.em.sim.Subs.standIns(w, c, team, dev.uem.em.mgmt.Supporters.lineupFor(w, c, team, Lineups.ensure(w, team).toList()), emptySet(), si).let { standIns.addAll(it.second); it.first }
    }, notes0 = standIns)
    /** Watching only: neither team is yours (or you chose to watch). */
    val spectator = spectatorWanted || (!w.isUserTeam(f.a) && !w.isUserTeam(f.b))
    /** The user's series team (0 or 1), or -1 when spectating. */
    val me = if (spectator) -1 else if (w.isUserTeam(f.a)) 0 else 1
    // Your substitutions are yours to make; the other coach makes theirs.
    init { if (me >= 0) series.aiSides[me] = false }
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

    /** Your coach calls in the MOBA game being watched: (second, call). */
    val myCalls = ArrayList<Pair<Int, String>>()
    val callsPerGame = 3

    /**
     * A coach call in a live MOBA game (play safe, force a fight, objectives, split-push, go for the end):
     * the game is played again from this second with the call in effect — everything before stays as it was —
     * and the view keeps its place.
     */
    fun callMoba(call: String, atSeconds: Int) {
        if (me < 0 || stage != "watch" || !series.t.isMoba || myCalls.size >= callsPerGame) return
        val s = setup ?: return
        val d = draft ?: return
        myCalls.add(atSeconds to call)
        series.undoLast()
        ReplayJump.seconds = atSeconds
        game = series.play(s, d.runAll(), record = true, mobaCalls = myCalls.map { Triple(it.first, me, it.second) })
        persist()
        bump()
    }

    fun startGame() {
        val s = setup ?: return
        val d = draft ?: return
        myTimeouts.clear()
        myCalls.clear()
        game = series.play(s, d.runAll(), record = true, timeouts = timeoutPlan())
        stage = "watch"
        persist()
        bump()
    }

    fun afterGame() {
        if (series.done) finish() else nextGame()
    }

    /** Between games: bring a bench player into a position for the next game (they must play it). */
    fun substitute(slot: Int, inPid: Int): Boolean {
        if (me < 0 || series.done || stage != "watch") return false
        val ok = series.substitute(me, slot, inPid, "coach")
        if (ok) { persist(); bump() }
        return ok
    }

    /** What your coach would change before the next game (null: keep the lineup). */
    fun suggestion(): dev.uem.em.sim.Subs.Pick? = if (me < 0 || series.done) null else dev.uem.em.sim.Subs.suggest(series, me, series.nextMap())

    /** Simulate the remaining games without watching (same AI and engine). */
    fun simRest() {
        // Your coach takes over the rest of the series, substitutions included.
        if (me >= 0) series.aiSides[me] = true
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

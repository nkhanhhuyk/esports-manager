package dev.uem.em.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.uem.em.comp.Season
import dev.uem.em.core.Dates
import dev.uem.em.data.DataSource
import dev.uem.em.data.GameData
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.World
import dev.uem.em.persist.SaveStore
import dev.uem.em.titles.Catalogue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Navigation targets. */
sealed class Screen {
    data object Start : Screen()
    data object PickOrg : Screen()
    data object Home : Screen()
    data object Comps : Screen()
    data object Teams : Screen()
    data object Players : Screen()
    data object More : Screen()
    data object Squad : Screen()
    data object Market : Screen()
    data object Inbox : Screen()
    data object Finances : Screen()
    data object Board : Screen()
    data object History : Screen()
    data object Patches : Screen()
    data object Schedule : Screen()
    data object Stats : Screen()
    data object Settings : Screen()
    data class Comp(val id: Int) : Screen()
    data class Team(val id: Int) : Screen()
    data class Player(val id: Int) : Screen()
    data class Match(val id: Int) : Screen()
    data class Compare(val a: Int, val b: Int) : Screen()
    data object World : Screen()
    data object Watch : Screen()
    data class Replay(val fixtureId: Int, val game: Int) : Screen()
}

class Busy(val label: String, val done: Int, val total: Int)

/**
 * App state: the world (mutated only by the simulation thread while busy), a version counter the
 * screens observe, busy/progress state and the navigation stack. Saves run on the IO dispatcher.
 */
class GameVm(app: Application) : AndroidViewModel(app) {
    val src = DataSource { name -> app.assets.open(name).bufferedReader().use { it.readText() } }
    val store = SaveStore(File(app.filesDir, "saves"))
    var world by mutableStateOf<World?>(null)
        private set
    var version by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf<Busy?>(null)
        private set
    var toast by mutableStateOf<String?>(null)
    var slot by mutableStateOf("career")
        private set
    var unsaved by mutableStateOf(false)
        private set
    val stack = mutableStateListOf<Screen>(Screen.Start)
    private val cancel = AtomicBoolean(false)
    private val saveLock = Mutex()
    private var simJob: Job? = null
    private var lastAutosaveDay = 0

    private val prefs = app.getSharedPreferences("uem", android.content.Context.MODE_PRIVATE)
    fun setPicturesOnline(on: Boolean) { prefs.edit().putBoolean("pictures", on).apply() }

    init {
        Pics.online = prefs.getBoolean("pictures", true)
        BoxStore.store = store.pages()
        viewModelScope.launch(Dispatchers.Default) {
            GameData.init(src)
            Catalogue.init(src)
            Pics.init(src)
            Maps.init(src)
        }
    }

    val screen get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.size - 1); true } else false
    fun tab(s: Screen) { stack.clear(); stack.add(s) }
    fun touch() { version++ }

    /** A change made from a screen (tactics, signings…): marks the game unsaved. */
    fun edit() { unsaved = true; version++ }
    fun saves() = store.list()

    fun newGame(name: String) {
        if (busy != null) return
        busy = Busy("Building the world…", 0, 0)
        viewModelScope.launch {
            val w = withContext(Dispatchers.Default) {
                BoxStore.clear()
                WorldGen.create(src, "${System.currentTimeMillis()}", name.ifBlank { "Career" }, Dates.today())
            }
            slot = name.ifBlank { "career" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
            world = w
            lastAutosaveDay = w.day
            busy = null
            touch()
            tab(Screen.PickOrg)
        }
    }

    fun chooseOrg(orgId: Int) {
        val w = world ?: return
        w.userOrg = orgId
        dev.uem.em.mgmt.Career.setupBoard(w)
        touch()
        tab(Screen.Home)
        save(silent = true)
    }

    /** Spectator career: no club; follow teams and watch any match. */
    fun chooseSpectator() {
        val w = world ?: return
        w.userOrg = -1
        w.board = null
        // Start by following the strongest team of each game.
        if (w.followed.isEmpty()) for (id in w.titles) w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 }.maxByOrNull { it.elo }?.let { w.followed.add(it.id) }
        touch()
        tab(Screen.Home)
        save(silent = true)
    }

    fun load(slotName: String) {
        if (busy != null) return
        busy = Busy("Loading…", 0, 0)
        viewModelScope.launch {
            try {
                val w = withContext(Dispatchers.IO) {
                    store.load(slotName).also { lw ->
                        // Saves from the first native version: add staff, sponsors, salaries and a board.
                        dev.uem.em.mgmt.Economy.ensureSetup(lw)
                        if (lw.userOrg >= 0 && lw.board == null) dev.uem.em.mgmt.Career.setupBoard(lw)
                    }
                }
                slot = slotName
                world = w
                lastAutosaveDay = w.day
                unsaved = false
                touch()
                tab(if (w.userOrg >= 0 || w.board == null && w.followed.isNotEmpty()) Screen.Home else Screen.PickOrg)
            } catch (e: Exception) {
                toast = "Could not load: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    /** Save on a background thread; never two at once. */
    fun save(silent: Boolean = false) {
        val w = world ?: return
        viewModelScope.launch {
            saveLock.withLock {
                try {
                    val t0 = System.currentTimeMillis()
                    withContext(Dispatchers.IO) { store.save(slot, w) }
                    unsaved = false
                    lastAutosaveDay = w.day
                    if (!silent) toast = "Saved (${System.currentTimeMillis() - t0} ms)"
                } catch (e: Exception) {
                    toast = "Save failed: ${e.message}"
                }
            }
        }
    }

    enum class Span { DAY, NEXT_MATCH, WEEK, MONTH, SEASON_END }

    fun advance(span: Span) {
        val w = world ?: return
        if (busy != null) return
        val days = when (span) {
            Span.DAY -> 1
            Span.WEEK -> 7
            Span.MONTH -> 30
            Span.NEXT_MATCH -> 60
            Span.SEASON_END -> maxOf(1, Season.endDay(w.season) - w.day)
        }
        cancel.set(false)
        busy = Busy("Simulating…", 0, days)
        simJob = viewModelScope.launch {
            val res = withContext(Dispatchers.Default) {
                Advance.run(w, days, stopForUser = span == Span.NEXT_MATCH, onDay = { d -> if (d % 2 == 0 || d == days) busy = Busy("Simulating…", d, days) }, cancelled = { cancel.get() })
            }
            busy = null
            unsaved = true
            touch()
            if (res.stoppedForUser) toast = "Match day: your team plays tomorrow"
            val every = when (w.autosave) { "advance" -> 0; "month" -> 30; "off" -> Int.MAX_VALUE; else -> 7 }
            if (w.day - lastAutosaveDay >= every) save(silent = true)
        }
    }

    fun stop() = cancel.set(true)

    /** A series being played live (draft / veto and the 2D view). */
    var live by mutableStateOf<LiveMatch?>(null)
        private set

    /**
     * Play (your match) or watch (any match) a series live. Only one series can be live at a time:
     * an unfinished one is completed by the coaches first, so no watched game is ever lost.
     */
    fun startLive(fixtureId: Int, spectate: Boolean = false) {
        val w = world ?: return
        if (busy != null) return
        val pending = w.live
        if (pending != null && pending.fixtureId != fixtureId && !w.fixtures[pending.fixtureId].done) {
            val old = w.fixtures[pending.fixtureId]
            LiveMatch(w, pending.fixtureId).simRest()
            toast = "${w.teams[old.a].short} vs ${w.teams[old.b].short} was finished by the coaches"
        }
        live = LiveMatch(w, fixtureId, spectate) { unsaved = true; save(silent = true) }
    }

    /** Simulate up to the day before a fixture, then watch (or play) it live. */
    fun advanceAndWatch(fixtureId: Int) {
        val w = world ?: return
        if (busy != null) return
        val f = w.fixtures[fixtureId]
        val days = f.day - 1 - w.day
        if (days <= 0) { startLive(fixtureId, spectate = !w.isUserTeam(f.a) && !w.isUserTeam(f.b)); return }
        cancel.set(false)
        busy = Busy("Simulating to match day…", 0, days)
        simJob = viewModelScope.launch {
            val res = withContext(Dispatchers.Default) {
                Advance.run(w, days, stopForUser = true, onDay = { d -> busy = Busy("Simulating to match day…", d, days) }, cancelled = { cancel.get() })
            }
            busy = null
            unsaved = true
            touch()
            if (!f.done && f.day <= w.day + 1) startLive(fixtureId, spectate = !w.isUserTeam(f.a) && !w.isUserTeam(f.b))
            else if (res.stoppedForUser) toast = "Stopped: your team plays tomorrow"
        }
    }

    fun endLive() {
        live = null
        unsaved = true
        touch()
    }

    /** Tests only: show an already built world. */
    fun setWorldForTest(w: World) { world = w; touch() }
}

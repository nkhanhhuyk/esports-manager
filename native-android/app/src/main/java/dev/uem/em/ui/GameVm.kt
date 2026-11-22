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
import kotlinx.coroutines.async
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
    data class Org(val id: Int) : Screen()
    data class Player(val id: Int) : Screen()
    data class Match(val id: Int) : Screen()
    data class Compare(val a: Int, val b: Int) : Screen()
    data object World : Screen()
    data object Watch : Screen()
    data object Tiers : Screen()
    data object CompEditor : Screen()
    data object SoloQ : Screen()
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
        PictureStore.dir = File(app.filesDir, "pictures")
        Pics.online = prefs.getBoolean("pictures", true)
        BoxStore.store = store.pages()
        viewModelScope.launch(Dispatchers.Default) {
            GameData.init(src)
            Catalogue.init(src)
            Pics.init(src)
            Maps.init(src)
            GameInfo.init(src)
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

    fun newGame(name: String, fictional: Boolean = false) {
        if (busy != null) return
        busy = Busy("Building the world…", 0, 0)
        viewModelScope.launch {
            val w = withContext(Dispatchers.Default) {
                BoxStore.clear()
                WorldGen.create(src, "${System.currentTimeMillis()}", name.ifBlank { "Career" }, Dates.today(), fictional = fictional).also { nw ->
                    // Your database edits apply to every new real-world game (matched by name).
                    if (!fictional && dbFile.exists()) runCatching { dev.uem.em.mgmt.Database.import(nw, dev.uem.em.data.Xlsx.read(dbFile.inputStream()), byKey = true) }.onSuccess { dbReport = it }
                }
            }
            // A new file: never overwrite an existing save with the same name.
            slot = store.freeSlot(name.ifBlank { "Career" })
            world = w
            lastAutosaveDay = w.day
            busy = null
            touch()
            tab(Screen.PickOrg)
        }
    }

    fun chooseOrg(orgId: Int, role: String = "coach-gm") {
        val w = world ?: return
        w.userOrg = orgId
        w.role = role
        w.spectator = false
        dev.uem.em.mgmt.Career.setupBoard(w)
        touch()
        tab(Screen.Home)
        save(silent = true)
    }

    /** Save, then write this career to a file the user picked (zip with checksums). */
    fun exportSave(uri: android.net.Uri) {
        val w = world ?: return
        viewModelScope.launch {
            saveLock.withLock {
                try {
                    withContext(Dispatchers.IO) {
                        store.save(slot, w)
                        getApplication<Application>().contentResolver.openOutputStream(uri)?.use { store.export(slot, it) } ?: error("Cannot write the file")
                    }
                    unsaved = false
                    toast = "Exported ${w.name}"
                } catch (e: Exception) {
                    toast = "Export failed: ${e.message}"
                }
            }
        }
    }

    /** Import an exported career into a new save slot (checked before anything is written). */
    fun importSave(uri: android.net.Uri, done: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)?.use { store.import(it) } ?: error("Cannot read the file") }
                toast = "Imported as \"${r.name}\"" + if (r.renamed) " (renamed: a save with that name exists)" else ""
            } catch (e: Exception) {
                toast = "Import failed: ${e.message}"
            }
            done()
        }
    }

    // ------------------------------------------------------------------ database editor

    /** The edited spreadsheet applied to every new real-world game (kept on the phone). */
    private val dbFile = File(app.filesDir, "database-edits.xlsx")
    fun hasDatabaseEdits() = dbFile.exists()
    fun clearDatabaseEdits() { dbFile.delete(); toast = "New games use the original database again" }

    /** Write the world's database to a spreadsheet the user picked (.xlsx for Google Sheets / Excel). */
    fun exportDatabase(uri: android.net.Uri) {
        val w = world ?: return
        if (busy != null) return
        busy = Busy("Writing the spreadsheet…", 0, 0)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openOutputStream(uri)?.use { dev.uem.em.mgmt.Database.export(w, it) } ?: error("Cannot write the file") }
                toast = "Database exported: open it in Google Sheets or Excel, edit, and import it back"
            } catch (e: Exception) { toast = "Export failed: ${e.message}" }
            busy = null
        }
    }

    /** Last import's report (shown in Settings). */
    var dbReport by mutableStateOf<dev.uem.em.mgmt.Database.Report?>(null)

    /** Read an edited spreadsheet (.xlsx or a .csv sheet) into this save, and optionally keep it for new games. */
    fun importDatabase(uri: android.net.Uri, forNewGames: Boolean) {
        val w = world ?: return
        if (busy != null) return
        busy = Busy("Reading the spreadsheet…", 0, 0)
        viewModelScope.launch {
            try {
                val app = getApplication<Application>()
                val bytes = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read the file") }
                val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
                val book = withContext(Dispatchers.Default) { if (isZip) dev.uem.em.data.Xlsx.read(bytes.inputStream()) else linkedMapOf("Sheet" to dev.uem.em.data.Xlsx.readCsv(String(bytes))) }
                val rep = withContext(Dispatchers.Default) { dev.uem.em.mgmt.Database.import(w, book, byKey = false) }
                if (forNewGames && isZip) withContext(Dispatchers.IO) { dbFile.writeBytes(bytes) }
                dbReport = rep
                unsaved = true
                touch()
                toast = "Imported: " + rep.text() + if (forNewGames && isZip) " Kept for new games." else ""
            } catch (e: Exception) { toast = "Import failed: ${e.message}" }
            busy = null
        }
    }

    /**
     * Download every picture the game knows (all games, flags, your picture links) into the app's own
     * storage, permanently: they show without internet. Works from the start menu (no career needed).
     */
    fun downloadAllPictures() {
        if (busy != null) return
        cancel.set(false)
        viewModelScope.launch {
            busy = Busy("Preparing the picture list…", 0, 0)
            val urls = withContext(Dispatchers.Default) { GameData.init(src); Pics.init(src); Pics.everything(world) }
            busy = Busy("Downloading pictures…", 0, urls.size)
            val (ok, fail) = withContext(Dispatchers.IO) { PictureStore.download(urls, { d, n -> busy = Busy("Downloading pictures… (stored for good)", d, n) }, cancel) }
            busy = null
            toast = "Pictures stored on the phone: ${PictureStore.count()} (${PictureStore.bytes() / (1024 * 1024)} MB). New now: $ok" + if (fail > 0) "; $fail could not be downloaded (host refused or unreachable)" else ""
        }
    }

    /** Download pictures into the phone's picture cache so they show offline. */
    fun downloadPictures(titles: Set<String>) {
        val w = world ?: return
        if (busy != null) return
        val urls = Pics.allFor(w, titles) + w.images.values
        if (urls.isEmpty()) { toast = "No pictures to download"; return }
        busy = Busy("Downloading pictures…", 0, urls.size)
        cancel.set(false)
        viewModelScope.launch {
            val (ok, fail) = withContext(Dispatchers.IO) { PictureStore.download(urls, { d, n -> busy = Busy("Downloading pictures… (stored for good)", d, n) }, cancel) }
            busy = null
            toast = "Pictures saved for offline use: $ok new" + if (fail > 0) " ($fail hosts refused or were unreachable; the game's own art is shown instead)" else ""
        }
    }

    /** Spectator career: no club; follow teams and watch any match. */
    fun chooseSpectator() {
        val w = world ?: return
        w.userOrg = -1
        w.spectator = true
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
                        Catalogue.applyCustom(lw)
                        dev.uem.em.mgmt.Economy.ensureSetup(lw)
                        if (lw.userOrg >= 0 && lw.board == null) dev.uem.em.mgmt.Career.setupBoard(lw)
                    }
                }
                slot = slotName
                world = w
                lastAutosaveDay = w.day
                unsaved = false
                touch()
                tab(if (w.userOrg >= 0 || w.spectator) Screen.Home else Screen.PickOrg)
            } catch (e: Exception) {
                toast = "Could not load: ${e.message}"
            } finally {
                busy = null
            }
        }
    }

    /** "Save as new file" dialog open (from the top bar, the sidebar or Settings). */
    var saveAsOpen by mutableStateOf(false)

    /** Default label for a new save file: club, season and in-game date. */
    fun defaultSaveLabel(w: World) = "${w.orgs.getOrNull(w.userOrg)?.short ?: w.name} S${w.season} ${Dates.short(w.day)}"

    /** Save to a new file (the current file is kept as it is); later saves go to the new file. */
    fun saveAs(label: String) {
        val w = world ?: return
        if (busy != null) return
        viewModelScope.launch {
            saveLock.withLock {
                try {
                    val s = withContext(Dispatchers.IO) { store.saveAs(label.ifBlank { defaultSaveLabel(w) }, w) }
                    slot = s
                    unsaved = false
                    lastAutosaveDay = w.day
                    toast = "Saved as a new file: ${label.ifBlank { defaultSaveLabel(w) }}"
                } catch (e: Exception) {
                    toast = "Save failed: ${e.message}"
                }
            }
        }
    }

    fun renameSave(slotName: String, label: String) { runCatching { store.rename(slotName, label) } }

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

    enum class Span(val years: Int = 0) { DAY, NEXT_MATCH, WEEK, MONTH, SEASON_END, YEARS_2(2), YEARS_5(5), YEARS_10(10), WORLDS }

    /** Days until the day before this season's (next) World Championship: your game's first, else the earliest. */
    fun daysToWorlds(w: World): Int? {
        val mine = w.orgs.getOrNull(w.userOrg)?.teams?.map { w.teams[it].titleId }?.toSet() ?: w.followed.mapNotNull { w.teams.getOrNull(it)?.titleId }.toSet()
        val next = w.comps.filter { it.season == w.season && it.tier == dev.uem.em.model.CompTier.WORLDS && it.status == 0 && it.startDay > w.day + 1 }
        val pick = next.filter { it.titleId in mine }.minByOrNull { it.startDay } ?: next.minByOrNull { it.startDay } ?: return null
        return pick.startDay - w.day - 1
    }

    /**
     * Simulate several seasons. Your staff run every area meanwhile (lineups, signings, renewals…) and
     * the game is saved at the end of each season, so a long run can be stopped without losing it.
     */
    private fun advanceYears(w: World, years: Int) {
        val endDay = Season.endDay(w.season + years - 1)
        val total = maxOf(1, endDay - w.day)
        val start = w.day
        val startSeason = w.season
        cancel.set(false)
        busy = Busy("Simulating $years seasons…", 0, total)
        simJob = viewModelScope.launch {
            w.autopilot = true
            try {
                while (w.day < endDay && !cancel.get()) {
                    val chunk = maxOf(1, minOf(endDay, Season.endDay(w.season)) - w.day)
                    val label = "Simulating season ${w.season} (${w.season - startSeason + 1} of $years)…"
                    withContext(Dispatchers.Default) {
                        Advance.run(w, chunk, stopForUser = false, onDay = { d -> if (d % 3 == 0) busy = Busy(label, w.day - start, total) }, cancelled = { cancel.get() })
                    }
                    busy = Busy("Saving…", w.day - start, total)
                    saveLock.withLock { withContext(Dispatchers.IO) { store.save(slot, w) } }
                    lastAutosaveDay = w.day
                }
            } catch (e: Exception) {
                toast = "Simulation stopped: ${e.message}"
            } finally {
                w.autopilot = false
            }
            busy = null
            unsaved = false
            touch()
            toast = if (cancel.get()) "Stopped in season ${w.season}" else "Simulated to the end of season ${w.season - 1} — welcome to ${w.season}"
        }
    }

    fun advance(span: Span) {
        val w = world ?: return
        if (busy != null) return
        if (span.years > 0) { advanceYears(w, span.years); return }
        val days = when (span) {
            Span.DAY -> 1
            Span.WEEK -> 7
            Span.MONTH -> 30
            Span.NEXT_MATCH -> 60
            Span.SEASON_END -> maxOf(1, Season.endDay(w.season) - w.day)
            Span.WORLDS -> daysToWorlds(w) ?: run { toast = "This season's World Championships are under way or finished"; return }
            else -> 1
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
        // Without control of tactics the head coach drafts and plays; you watch.
        live = LiveMatch(w, fixtureId, spectate || !dev.uem.em.mgmt.Roles.can(w, "tactics")) { unsaved = true; save(silent = true) }
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
    fun setWorldForTest(w: World) { Catalogue.applyCustom(w); world = w; touch() }
}

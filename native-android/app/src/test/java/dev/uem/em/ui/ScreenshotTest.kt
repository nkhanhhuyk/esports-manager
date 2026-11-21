package dev.uem.em.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import dev.uem.em.TestData
import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the real screens on the JVM (no device) and writes PNGs to build/screens for review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h1300dp-xhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(out: File, name: String) {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun screens() {
        TestData.init()
        Pics.init(TestData.source)
        Pics.online = false
        Maps.init(TestData.source)
        BoxStore.clear()
        val w = WorldGen.create(TestData.source, "shots", "Shots", Dates.day(2026, 11, 21))
        val t1 = w.teams.first { it.name == "T1" }
        w.userOrg = t1.orgId
        dev.uem.em.mgmt.Career.setupBoard(w)
        Advance.run(w, 75)
        val vm = GameVm(ApplicationProvider.getApplicationContext())
        vm.setWorldForTest(w)
        val out = File("build/screens").apply { mkdirs() }
        val comp = w.comps.first { it.leagueId == t1.leagueId && it.season == w.season }
        val match = w.fixtures.last { it.done && it.titleId == "lol" }
        val faker = w.players.first { it.handle == "Faker" }
        val shots = listOf("home" to Screen.Home, "comp" to Screen.Comp(comp.id), "team" to Screen.Team(t1.id), "player" to Screen.Player(faker.id), "match" to Screen.Match(match.id), "comps" to Screen.Comps, "squad" to Screen.Squad, "market" to Screen.Market, "finances" to Screen.Finances, "inbox" to Screen.Inbox, "board" to Screen.Board, "patches" to Screen.Patches, "more" to Screen.More, "stats" to Screen.Stats, "schedule" to Screen.Schedule, "settings" to Screen.Settings,
            "history" to Screen.History, "world" to Screen.World, "teams" to Screen.Teams, "players" to Screen.Players,
            "compare" to Screen.Compare(faker.id, w.players.first { it.handle == "Chovy" }.id))
        vm.tab(Screen.Home)
        rule.setContent { AppRoot(vm) {} }
        rule.waitForIdle()
        // Live match: draft, then the 2D view part-way through game 1 (MOBA and shooter).
        LiveDefaults.autoplay = false
        LiveDefaults.startFraction = 0.45f
        val lolFx = w.fixtures.firstOrNull { !it.done && it.titleId == "lol" && (it.a == t1.id || it.b == t1.id) } ?: w.fixtures.first { !it.done && it.titleId == "lol" }
        rule.runOnIdle { vm.startLive(lolFx.id) }
        shot(out, "live-draft")
        rule.runOnIdle { vm.live!!.draftAct(vm.live!!.draft!!.t.characters.first { vm.live!!.draft!!.legal(vm.live!!.me, vm.live!!.draft!!.current()?.second ?: true, it.id) }.id); vm.live!!.autoDraft() }
        println("after auto draft: done=${vm.live!!.draft!!.done} picks=${vm.live!!.draft!!.picks.map { it.size }} bans=${vm.live!!.draft!!.bans.size} stage=${vm.live!!.stage} rev=${vm.live!!.rev}")
        shot(out, "live-draft-done")
        rule.runOnIdle { vm.live!!.startGame() }
        shot(out, "live-moba")
        println("moba events: " + vm.live!!.events.groupBy { it.kind }.mapValues { it.value.size } + " score " + vm.live!!.game!!.score.joinToString("-"))
        rule.runOnIdle { vm.live!!.simRest() }
        shot(out, "live-result")
        rule.runOnIdle { vm.endLive() }
        val valTeam = w.teams.first { it.orgId == t1.orgId && it.titleId == "valorant" }
        val valFx = w.fixtures.firstOrNull { !it.done && it.titleId == "valorant" && (it.a == valTeam.id || it.b == valTeam.id) }
            ?: w.fixtures.first { !it.done && it.titleId == "valorant" }
        rule.runOnIdle { vm.startLive(valFx.id) }
        shot(out, "live-veto")
        rule.runOnIdle { val l = vm.live!!; while (l.stage == "veto") l.vetoAct(l.veto!!.pool.first()); l.startGame() }
        shot(out, "live-shooter")
        println("shooter events: " + vm.live!!.events.groupBy { it.kind }.mapValues { it.value.size } + " score " + vm.live!!.game!!.score.joinToString("-"))
        rule.runOnIdle { vm.live!!.simRest(); vm.endLive() }
        // Every tab of the player and team pages.
        for (i in 0..8) {
            UiDefaults.tab = i
            rule.runOnIdle { vm.tab(Screen.Home) }; rule.waitForIdle(); rule.runOnIdle { vm.go(Screen.Player(faker.id)) }
            shot(out, "player-tab$i")
        }
        for (i in 0..6) {
            UiDefaults.tab = i
            rule.runOnIdle { vm.tab(Screen.Home) }; rule.waitForIdle(); rule.runOnIdle { vm.go(Screen.Team(t1.id)) }
            shot(out, "team-tab$i")
        }
        for (i in 0..4) {
            UiDefaults.tab = i
            rule.runOnIdle { vm.tab(Screen.Home) }; rule.waitForIdle(); rule.runOnIdle { vm.tab(Screen.Squad) }
            shot(out, "squad-tab$i")
        }
        UiDefaults.tab = 0
        for ((name, s) in shots) {
            rule.runOnIdle { vm.tab(s) }
            rule.waitForIdle()
            val v = rule.activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

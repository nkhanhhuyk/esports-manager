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
            "compare" to Screen.Compare(faker.id, w.players.first { it.handle == "Chovy" }.id),
            "academy" to Screen.Team(dev.uem.em.mgmt.Academies.of(w, t1)!!.id), "social" to Screen.Social, "library-runes" to Screen.Library("runes"))
        vm.tab(Screen.Home)
        rule.setContent { AppRoot(vm) {} }
        rule.waitForIdle()
        // Live match: draft, then the 2D view part-way through game 1 (MOBA and shooter).
        LiveDefaults.autoplay = false
        LiveDefaults.startFraction = 0.45f
        // T1 may be out of the current event by now: then the user takes charge of a team that still plays.
        val lolFx = w.fixtures.firstOrNull { !it.done && it.titleId == "lol" && (it.a == t1.id || it.b == t1.id) }
            ?: w.fixtures.first { !it.done && it.titleId == "lol" && it.a >= 0 && it.b >= 0 }.also { fx -> w.userOrg = w.teams[fx.a].orgId }
        rule.runOnIdle { vm.startLive(lolFx.id) }
        shot(out, "live-draft")
        rule.runOnIdle { vm.live!!.draftAct(vm.live!!.draft!!.t.characters.first { vm.live!!.draft!!.legal(vm.live!!.me, vm.live!!.draft!!.current()?.second ?: true, it.id) }.id); vm.live!!.autoDraft() }
        println("after auto draft: done=${vm.live!!.draft!!.done} picks=${vm.live!!.draft!!.picks.map { it.size }} bans=${vm.live!!.draft!!.bans.size} stage=${vm.live!!.stage} rev=${vm.live!!.rev}")
        shot(out, "live-draft-done")
        rule.runOnIdle { vm.live!!.startGame() }
        shot(out, "live-moba")
        println("moba events: " + vm.live!!.game!!.replay!!.ev.size / 9 + " score " + vm.live!!.game!!.score.joinToString("-"))
        rule.runOnIdle { vm.live!!.simRest() }
        shot(out, "live-result")
        rule.runOnIdle { vm.endLive() }
        val valTeam = w.teams.first { it.orgId == t1.orgId && it.titleId == "valorant" }
        val valFx = w.fixtures.firstOrNull { !it.done && it.titleId == "valorant" && (it.a == valTeam.id || it.b == valTeam.id) }
            ?: w.fixtures.first { !it.done && it.titleId == "valorant" }
        rule.runOnIdle { vm.startLive(valFx.id) }
        shot(out, "live-veto")
        rule.runOnIdle { val l = vm.live!!; var guard = 0; while (l.stage == "veto" && guard++ < 50) { if (l.spectator) l.aiFinish() else l.vetoAct(l.veto!!.pool.first()) }; if (l.stage == "draft") { l.aiFinish(); l.startGame() } }
        shot(out, "live-shooter")
        println("shooter events: " + vm.live!!.game!!.replay!!.ev.size / 9 + " score " + vm.live!!.game!!.score.joinToString("-"))
        rule.runOnIdle { vm.live!!.simRest(); vm.endLive() }
        // Spectating another team's series: the draft plays out step by step.
        val other = w.fixtures.first { !it.done && it.titleId == "lol" && it.a >= 0 && it.b >= 0 && !w.isUserTeam(it.a) && !w.isUserTeam(it.b) && it.day <= w.day + 1 }
        rule.runOnIdle { vm.startLive(other.id, spectate = true); repeat(7) { vm.live!!.aiTick() } }
        shot(out, "spectate-draft")
        rule.runOnIdle { vm.live!!.aiFinish(); vm.live!!.startGame() }
        shot(out, "spectate-game")
        rule.runOnIdle { vm.live!!.simRest(); vm.endLive() }
        // A stored replay from the match report.
        rule.runOnIdle { vm.tab(Screen.Home) }; rule.waitForIdle(); rule.runOnIdle { vm.go(Screen.Replay(lolFx.id, 0)) }
        shot(out, "replay")
        rule.runOnIdle { vm.tab(Screen.Home) }; rule.waitForIdle(); rule.runOnIdle { vm.go(Screen.Match(lolFx.id)) }
        shot(out, "lol-match")
        rule.runOnIdle { vm.tab(Screen.Watch) }
        shot(out, "watch")
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
        rule.runOnIdle { vm.tab(Screen.Start) }
        shot(out, "start")
        rule.runOnIdle { w.userOrg = -1; vm.tab(Screen.PickOrg) }
        shot(out, "pickorg")
        rule.runOnIdle { w.userOrg = t1.orgId; w.role = "gm" }
        rule.runOnIdle { vm.tab(Screen.Settings) }
        shot(out, "settings-gm")
        rule.runOnIdle { w.role = "coach-gm" }
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

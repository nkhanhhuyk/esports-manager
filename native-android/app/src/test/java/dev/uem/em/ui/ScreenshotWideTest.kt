package dev.uem.em.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.uem.em.TestData
import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.World
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The screens at the shapes of a book-style foldable (Oppo Find N5): the cover screen held sideways
 * (about 20.7:9) and the nearly square inner screen (about 9.9:9) both ways up, plus the light theme.
 * Writes PNGs to build/screens-wide and checks that the app's frame covers the whole window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotWideTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    companion object {
        private var world: World? = null
        fun world(): World = world ?: run {
            TestData.init(); Pics.init(TestData.source); Pics.online = false; Maps.init(TestData.source); BoxStore.clear()
            val w = WorldGen.create(TestData.source, "wide", "Wide", Dates.day(2026, 11, 21))
            w.userOrg = w.teams.first { it.name == "T1" }.orgId
            dev.uem.em.mgmt.Career.setupBoard(w)
            Advance.run(w, 75)
            world = w; w
        }
    }

    private fun shoot(prefix: String, light: Boolean = false) {
        val w = world()
        val vm = GameVm(ApplicationProvider.getApplicationContext())
        vm.setWorldForTest(w)
        vm.setTheme(if (light) "light" else "dark")
        val out = File("build/screens-wide").apply { mkdirs() }
        val t1 = w.teams.first { it.name == "T1" }
        val faker = w.players.first { it.handle == "Faker" }
        val comp = w.comps.first { it.leagueId == t1.leagueId && it.season == w.season }
        vm.tab(Screen.Home)
        rule.setContent { AppRoot(vm) {} }
        fun shot(name: String) {
            rule.waitForIdle()
            val v = rule.activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(out, "$prefix-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // The frame paints the whole window: no corner is left unpainted (transparent).
            for ((x, y) in listOf(1 to 1, v.width - 2 to 1, 1 to v.height - 2, v.width - 2 to v.height - 2, v.width / 2 to v.height / 2))
                assertTrue("$prefix-$name: unpainted pixel at $x,$y", (bmp.getPixel(x, y) ushr 24) == 0xFF)
        }
        val screens = if (light) listOf("home" to Screen.Home, "team" to Screen.Team(t1.id))
        else listOf("home" to Screen.Home, "more" to Screen.More, "team" to Screen.Team(t1.id), "player" to Screen.Player(faker.id), "comp" to Screen.Comp(comp.id), "market" to Screen.Market, "search" to Screen.Search, "business" to Screen.Business)
        for ((name, s) in screens) { rule.runOnIdle { vm.tab(s) }; shot(name) }
        if (!light) {
            LiveDefaults.autoplay = false
            LiveDefaults.startFraction = 0.45f
            // The world is shared by the tests and each one plays a match: move to the next match day when today's are used up.
            fun next() = w.fixtures.firstOrNull { !it.done && it.titleId == "lol" && it.a >= 0 && it.b >= 0 && !w.isUserTeam(it.a) && !w.isUserTeam(it.b) && it.day <= w.day + 1 }
            var tries = 0
            while (next() == null && tries++ < 10) rule.runOnIdle { Advance.run(w, 1) }
            val fx = next()!!
            rule.runOnIdle { vm.startLive(fx.id, spectate = true); vm.live!!.aiFinish(); vm.live!!.startGame() }
            shot("live-moba")
            rule.runOnIdle { vm.live!!.simRest(); vm.endLive() }
        }
        rule.runOnIdle { vm.setTheme("dark"); Ui.palette = Ui.DARK }
    }

    @Test @Config(qualifiers = "w950dp-h446dp-land-xhdpi") fun coverLandscape() = shoot("cover-land")
    @Test @Config(qualifiers = "w856dp-h945dp-port-xhdpi") fun innerPortrait() = shoot("inner-port")
    @Test @Config(qualifiers = "w945dp-h856dp-land-xhdpi") fun innerLandscape() = shoot("inner-land")
    @Test @Config(qualifiers = "w856dp-h945dp-port-xhdpi") fun innerLight() = shoot("inner-light", light = true)
}

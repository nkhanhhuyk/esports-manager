package dev.uem.em.ui

import android.content.res.Configuration
import androidx.test.core.app.ActivityScenario
import dev.uem.em.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Opens the real app the way the launcher does (MainActivity with its own start-up: edge-to-edge,
 * assets, the view model, the first screen) on phone, foldable and tablet shapes, both orientations,
 * then folds / rotates it: the app must come up without crashing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaunchTest {
    private fun launchAndTurn() {
        val sc = ActivityScenario.launch(MainActivity::class.java)
        repeat(20) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
        var ok = false
        sc.onActivity { a -> ok = !a.isFinishing; a.window.decorView.let { v -> assertTrue("content laid out", v.width > 0) } }
        assertTrue("activity alive after start-up", ok)
        // Rotate / unfold: the activity handles the change itself.
        RuntimeEnvironment.setQualifiers("+land")
        sc.onActivity { a -> a.onConfigurationChanged(Configuration(a.resources.configuration)) }
        repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(30) }
        sc.close()
    }

    @Test @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi") fun phone() = launchAndTurn()
    @Test @Config(sdk = [35], qualifiers = "w856dp-h945dp-port-xhdpi") fun foldInner() = launchAndTurn()
    @Test @Config(sdk = [35], qualifiers = "w950dp-h446dp-land-xhdpi") fun foldCoverSideways() = launchAndTurn()
    /**
     * A phone that has just started the app: the game data is not loaded and takes a while. The first frames
     * are drawn before it is in. Before the fix the title screen read the game list on its first frame and the
     * app crashed (UninitializedPropertyAccessException); now a loading screen shows until the data is ready.
     */
    @Test @Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
    fun slowStartUp() {
        dev.uem.em.titles.Catalogue.unloadForTest()
        GameVm.initDelayMs = 3000
        try {
            val sc = ActivityScenario.launch(MainActivity::class.java)
            var guard = 0; var ready = false
            while (!ready && guard++ < 400) { ShadowLooper.idleMainLooper(); Thread.sleep(50); sc.onActivity { a -> ready = androidx.lifecycle.ViewModelProvider(a)[GameVm::class.java].ready } }
            assertTrue("data loaded, title screen shown", ready)
            repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(30) }
            sc.onActivity { a -> assertTrue(!a.isFinishing) }
            assertTrue(dev.uem.em.titles.Catalogue.get("lol").id == "lol")
            sc.close()
        } finally { GameVm.initDelayMs = 0 }
    }
}

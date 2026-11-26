package dev.uem.em

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.uem.em.ui.AppRoot
import dev.uem.em.ui.GameVm

class MainActivity : ComponentActivity() {
    private val vm: GameVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(dark = true)
        setContent { AppRoot(vm) { finish() } }
    }

    /**
     * Draw under the status and navigation bars and into display cutouts in every orientation, so the
     * game fills the whole screen (the bars are transparent; the UI pads its content by the insets).
     */
    fun edgeToEdge(dark: Boolean) {
        val bar = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
        if (Build.VERSION.SDK_INT >= 29) { window.isNavigationBarContrastEnforced = false; window.isStatusBarContrastEnforced = false }
        if (Build.VERSION.SDK_INT >= 30) window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        else if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (screen off, another app, Home): remember the open save so the game reopens it if Android
        // ends the app in the background, and save progress (right away, or when a running simulation ends).
        if (vm.world != null) {
            vm.rememberSession()
            if (vm.busy == null) { if (vm.unsaved) vm.save(silent = true) } else vm.saveWhenIdle = true
        }
    }
}

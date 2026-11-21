package dev.uem.em

import android.os.Bundle
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
        enableEdgeToEdge()
        setContent { AppRoot(vm) { finish() } }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app: save progress in the background (never while the simulation runs).
        if (vm.world != null && vm.unsaved && vm.busy == null) vm.save(silent = true)
    }
}

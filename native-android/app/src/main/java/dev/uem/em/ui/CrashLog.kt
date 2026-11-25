package dev.uem.em.ui

import java.io.File

/**
 * The last crash, kept on the phone: written when the app closes on an error (or start-up fails) and
 * shown on the title screen at the next launch with a Copy button, so it can be reported.
 */
object CrashLog {
    private fun file(): File? = runCatching { File(dev.uem.em.App.instance.filesDir, "last-crash.txt") }.getOrNull()

    fun write(e: Throwable, where: String) {
        runCatching {
            val f = file() ?: return
            f.writeText("Where: $where\nWhen: ${java.util.Date()}\nApp: ${runCatching { dev.uem.em.App.instance.packageManager.getPackageInfo(dev.uem.em.App.instance.packageName, 0).versionName }.getOrNull()}\nAndroid: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n\n" + e.stackTraceToString().take(12000))
        }
    }

    fun read(): String? = runCatching { file()?.takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear() { runCatching { file()?.delete() } }
}

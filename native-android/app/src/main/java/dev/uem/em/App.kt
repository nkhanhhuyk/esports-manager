package dev.uem.em

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /**
     * Pictures are kept on the phone once shown (or downloaded from Settings), so they still show
     * offline. Cache headers are ignored: many image hosts say "no-cache" for files that never change.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("pictures")).maxSizeBytes(400L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .crossfade(false)
        .build()

    companion object {
        lateinit var instance: App
            private set
    }
}

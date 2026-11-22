package dev.uem.em.ui

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Permanent offline pictures: downloaded once into the app's own storage (not a cache, so the
 * phone never clears them) and shown from there with or without internet. Covers characters,
 * weapons, team and league logos, player photos, flags and every picture link set in the database
 * editor.
 */
object PictureStore {
    @Volatile var dir: File? = null
    private const val UA = "EsportsManagerOfflineSnapshot/1.0 (personal non-commercial game; offline data snapshot)"

    private fun key(url: String): String = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    /** The stored copy of a picture link, if it has been downloaded. */
    fun local(url: String?): File? {
        if (url == null) return null
        val d = dir ?: return null
        return File(d, key(url)).takeIf { it.isFile && it.length() > 0 }
    }

    fun count(): Int = dir?.listFiles()?.count { it.isFile } ?: 0
    fun bytes(): Long = dir?.listFiles()?.sumOf { it.length() } ?: 0L
    fun clear() { dir?.listFiles()?.forEach { it.delete() } }

    private fun fetch(url: String, target: File): Boolean {
        var u = URL(url)
        repeat(4) {
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
            }
            try {
                val code = c.responseCode
                if (code in 300..399) { val loc = c.getHeaderField("Location") ?: return false; u = URL(u, loc); return@repeat }
                if (code != 200) return false
                val tmp = File(target.path + ".part")
                c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                if (tmp.length() < 64) { tmp.delete(); return false }
                return tmp.renameTo(target)
            } catch (e: Exception) { return false } finally { c.disconnect() }
        }
        return false
    }

    /**
     * Download every link not stored yet (8 at a time). Returns (downloaded now, failed). The
     * picture's original link is the key; hotlink-protected hosts are fetched through the image proxy.
     */
    fun download(urls: Collection<String>, progress: (done: Int, total: Int) -> Unit, cancelled: AtomicBoolean): Pair<Int, Int> {
        val d = dir ?: return 0 to 0
        d.mkdirs()
        val todo = urls.distinct().filter { local(it) == null }
        val ok = AtomicInteger(); val fail = AtomicInteger(); val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = todo.map { url ->
                pool.submit {
                    if (cancelled.get()) return@submit
                    val target = File(d, key(url))
                    val okNow = fetch(Pics.display(url), target) || (Pics.display(url) == url && fetch(Pics.proxied(url), target))
                    if (okNow) ok.incrementAndGet() else fail.incrementAndGet()
                    val n = done.incrementAndGet()
                    if (n % 10 == 0 || n == todo.size) progress(n, todo.size)
                }
            }
            for (f in futures) runCatching { f.get() }
        } finally { pool.shutdownNow() }
        return ok.get() to fail.get()
    }
}

package dev.uem.em.persist

import dev.uem.em.game.BoxStore
import dev.uem.em.model.MatchRecord
import dev.uem.em.model.World
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Native saves: the world as compact binary (protobuf), gzip-compressed (its CRC is checked on every
 * load), written to `<slot>.tmp`, flushed to the storage device, then moved into place; the save it
 * replaces is moved to `<slot>.bak` (atomic renames, nothing large is copied). A crash or a full disk at
 * any point leaves at least one complete save: loading tries the save, then an unfinished save that was
 * fully written, then the backup, and reports which one it used ([lastLoad]). Box-score pages live in
 * `<slot>.pages/`.
 */
@OptIn(ExperimentalSerializationApi::class)
class SaveStore(private val root: File) {
    companion object {
        const val FORMAT = "esports-manager-native-save"
        const val FORMAT_VERSION = 1
    }

    @Serializable
    class SaveFile(val world: World, val records: List<MatchRecord>)

    /** A save file as listed: its label (renameable), career, club, date in the game and on the phone. */
    class Meta(val slot: String, val name: String, val day: Int, val season: Int, val modified: Long, val bytes: Long, val career: String = "", val club: String = "", val pagesKey: String = "")

    init {
        root.mkdirs()
    }

    private fun file(slot: String) = File(root, "$slot.uem")
    fun pagesDir(name: String) = File(root, "${name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.pages").also { it.mkdirs() }

    /** How the last load went: which copy was opened, what could not be read, and integrity problems found (nothing is changed). */
    class LoadReport(val slot: String, val source: String, val failed: List<String>, val problems: List<String>) {
        val recovered get() = source != "save"
    }
    @Volatile var lastLoad: LoadReport? = null

    /** Write a file and flush it to the storage device before it is renamed into place. */
    private fun writeDurably(f: File, write: (java.io.OutputStream) -> Unit) {
        java.io.FileOutputStream(f).use { fos ->
            val buf = java.io.BufferedOutputStream(fos, 1 shl 16)
            write(buf)
            buf.flush()
            runCatching { fos.fd.sync() }
        }
    }

    private fun move(from: File, to: File) {
        try {
            java.nio.file.Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            if (!from.renameTo(to)) { from.copyTo(to, overwrite = true); from.delete() }
        }
    }

    /** Page store for box scores (attach with BoxStore.store = saveStore.pages()). */
    fun pages() = object : BoxStore.PageStore {
        override fun write(saveName: String, page: Int, records: List<MatchRecord>) {
            // Written to a temporary file and renamed, so a page is never half-written.
            val f = File(pagesDir(saveName), "$page.bin")
            val tmp = File(pagesDir(saveName), "$page.tmp")
            val bytes = ProtoBuf.encodeToByteArray(ListSerializer(MatchRecord.serializer()), records)
            writeDurably(tmp) { out -> GZIPOutputStream(out).let { it.write(bytes); it.finish() } }
            move(tmp, f)
        }

        override fun read(saveName: String, page: Int): List<MatchRecord>? {
            val f = File(pagesDir(saveName), "$page.bin")
            if (!f.exists()) return null
            return GZIPInputStream(f.inputStream().buffered()).use { ProtoBuf.decodeFromByteArray(ListSerializer(MatchRecord.serializer()), it.readBytes()) }
        }
    }

    private fun metaFile(slot: String) = File(root, "$slot.meta.json")

    fun save(slot: String, w: World, label: String? = null) {
        val bytes = ProtoBuf.encodeToByteArray(SaveFile(w, BoxStore.unpaged()))
        val tmp = File(root, "$slot.tmp")
        try {
            writeDurably(tmp) { out -> GZIPOutputStream(out).let { it.write(bytes); it.finish() } }
        } catch (e: Exception) {
            // A full disk or a write error: the existing save and its backup are untouched.
            runCatching { tmp.delete() }
            throw java.io.IOException("Could not write the save (${e.message ?: e.javaClass.simpleName}); your previous save is unchanged", e)
        }
        val target = file(slot)
        if (target.exists()) move(target, File(root, "$slot.bak"))
        move(tmp, target)
        writeMeta(slot, w, label ?: readMeta(slot)?.get("label") ?: slot)
    }

    private fun writeMeta(slot: String, w: World, label: String) {
        val club = w.orgs.getOrNull(w.userOrg)?.name ?: if (w.spectator) "Spectator" else ""
        val j = kotlinx.serialization.json.buildJsonObject {
            put("label", kotlinx.serialization.json.JsonPrimitive(label))
            put("career", kotlinx.serialization.json.JsonPrimitive(w.name))
            put("club", kotlinx.serialization.json.JsonPrimitive(club))
            put("season", kotlinx.serialization.json.JsonPrimitive(w.season))
            put("day", kotlinx.serialization.json.JsonPrimitive(w.day))
            put("pages", kotlinx.serialization.json.JsonPrimitive(w.pagesName()))
        }
        val tmp = File(root, "$slot.meta.tmp")
        tmp.writeText(j.toString())
        move(tmp, metaFile(slot))
    }

    private fun readMeta(slot: String): Map<String, String>? = runCatching {
        (kotlinx.serialization.json.Json.parseToJsonElement(metaFile(slot).readText()) as kotlinx.serialization.json.JsonObject).mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content }
    }.getOrNull()

    /** A file name nobody uses yet, from a label. */
    fun freeSlot(label: String): String {
        val base = label.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "save" }
        var slot = base; var k = 2
        while (file(slot).exists() || metaFile(slot).exists()) slot = "${base}_${k++}"
        return slot
    }

    /**
     * Save the world as a new file (the current one is kept). The new file gets its own box-score
     * folder: the pages are hard-linked (no extra space; a page rewritten later is replaced in one
     * folder only), or copied where links are not supported.
     */
    fun saveAs(label: String, w: World): String {
        val slot = freeSlot(label)
        val oldDir = pagesDir(w.pagesName())
        val key = "${slot}_${System.currentTimeMillis() % 1_000_000}"
        val newDir = pagesDir(key)
        for (f in oldDir.listFiles { x -> x.name.endsWith(".bin") }.orEmpty()) {
            val dst = File(newDir, f.name)
            try { java.nio.file.Files.createLink(dst.toPath(), f.toPath()) } catch (e: Exception) { f.copyTo(dst, overwrite = true) }
        }
        val old = w.pagesKey
        w.pagesKey = key
        try { save(slot, w, label) } catch (e: Exception) { w.pagesKey = old; newDir.deleteRecursively(); throw e }
        return slot
    }

    fun rename(slot: String, label: String) {
        val m = readMeta(slot)?.toMutableMap() ?: mutableMapOf()
        m["label"] = label
        metaFile(slot).writeText(kotlinx.serialization.json.JsonObject(m.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString())
    }

    fun load(slot: String): World {
        // The save; else a save that was completely written but not yet moved into place (a crash between the two
        // renames); else the backup (the save before). Each is checked whole (gzip CRC, decoding) before use.
        val failed = ArrayList<String>()
        var sf: SaveFile? = null
        var source = ""
        for ((name, f) in listOf("save" to file(slot), "unfinished save" to File(root, "$slot.tmp"), "backup" to File(root, "$slot.bak"))) {
            if (!f.exists()) continue
            try { sf = read(f); source = name; break } catch (e: Throwable) { failed.add("$name (${f.name}): ${e.message ?: e.javaClass.simpleName}") }
        }
        if (sf == null) throw java.io.IOException("No readable copy of $slot" + (if (failed.isEmpty()) " (no file)" else ": " + failed.joinToString("; ")) + ". The files are kept as they are.")
        val w = sf.world
        fixUp(w)
        w.rebuildSchedule()
        BoxStore.clear()
        BoxStore.restore(sf.records)
        val problems = runCatching { dev.uem.em.comp.Invariants.world(w).violations.toList() }.getOrElse { listOf("integrity check failed: ${it.message}") }
        lastLoad = LoadReport(slot, source, failed, problems)
        if (source != "save") w.inbox.add(dev.uem.em.model.Message(w.day, "Save files", "Save recovered",
            "The latest save of this career could not be read (${failed.joinToString("; ").ifEmpty { "missing" }}). The game opened the $source instead (${dev.uem.em.core.Dates.format(w.day)}). Nothing was reset; save again to replace the damaged file."))
        return w
    }

    /** Older saves: Nations Cup events not started yet take the Nations Cup draft rule (they used the mid-season event's). */
    private fun fixUp(w: World) {
        for (c in w.comps) if (c.status == 0 && dev.uem.em.comp.Fearless.scopeOf(c) == "nations") dev.uem.em.comp.Fearless.assign(w, dev.uem.em.titles.Catalogue.get(c.titleId), c)
        // Event MVPs of older saves were sometimes players whose team went out early: chosen again by the current rule.
        for (c in w.comps) if (c.status == 2 && c.pstats.isNotEmpty() && c.placements.isNotEmpty()) c.mvp = dev.uem.em.comp.Progress.mvpOf(c)
        dev.uem.em.mgmt.Ambitions.ensure(w)
        // Saves made before empty stages completed on their own: a running event stuck on a stage with nothing to play
        // (an amateur championship of two clubs in two groups) moves on now; no result is changed.
        for (c in w.comps.toList()) if (c.status == 1) dev.uem.em.comp.Progress.settle(w, c, c.current)
    }

    private fun read(f: File): SaveFile = GZIPInputStream(f.inputStream().buffered(1 shl 16)).use { ProtoBuf.decodeFromByteArray<SaveFile>(it.readBytes()) }

    /** Every save, including one whose latest file is missing but whose backup survives (it opens from the backup). */
    fun list(): List<Meta> = root.listFiles { f -> f.name.endsWith(".uem") || (f.name.endsWith(".bak") && !File(root, f.name.removeSuffix(".bak") + ".uem").exists()) }.orEmpty().mapNotNull { f ->
        val slot = f.name.removeSuffix(".uem").removeSuffix(".bak")
        val m = readMeta(slot)
        Meta(slot, m?.get("label") ?: slot, m?.get("day")?.toIntOrNull() ?: 0, m?.get("season")?.toIntOrNull() ?: 0, f.lastModified(), f.length(), m?.get("career") ?: "", m?.get("club") ?: "", m?.get("pages") ?: "")
    }.sortedByDescending { it.modified }

    // ------------------------------------------------------------------ export / import

    class ImportResult(val slot: String, val name: String, val renamed: Boolean)

    private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /**
     * Export a save as a zip: manifest.json (format, versions, every file's size and SHA-256), the
     * world (world.uem) and its box-score pages (pages/n.bin). Import checks all of it first.
     */
    fun export(slot: String, out: java.io.OutputStream) {
        val f = file(slot).takeIf { it.exists() } ?: throw IllegalStateException("No save called $slot")
        val world = read(f).world
        val files = ArrayList<Pair<String, ByteArray>>()
        files.add("world.uem" to f.readBytes())
        pagesDir(world.pagesName()).listFiles { x -> x.name.endsWith(".bin") }.orEmpty().sortedBy { it.name }.forEach { files.add("pages/${it.name}" to it.readBytes()) }
        val manifest = kotlinx.serialization.json.buildJsonObject {
            put("format", kotlinx.serialization.json.JsonPrimitive(FORMAT))
            put("formatVersion", kotlinx.serialization.json.JsonPrimitive(FORMAT_VERSION))
            put("worldVersion", kotlinx.serialization.json.JsonPrimitive(world.version))
            put("name", kotlinx.serialization.json.JsonPrimitive(world.name))
            put("season", kotlinx.serialization.json.JsonPrimitive(world.season))
            put("day", kotlinx.serialization.json.JsonPrimitive(world.day))
            put("exported", kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis()))
            put("files", kotlinx.serialization.json.JsonArray(files.map { (n, b) ->
                kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive(n)); put("size", kotlinx.serialization.json.JsonPrimitive(b.size)); put("sha256", kotlinx.serialization.json.JsonPrimitive(sha256(b))) }
            }))
        }
        java.util.zip.ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            for ((n, b) in files) { zip.putNextEntry(java.util.zip.ZipEntry(n)); zip.write(b); zip.closeEntry() }
        }
    }

    /**
     * Import an exported save into a new slot (never overwriting one). Every file is checked against
     * the manifest's checksums and the world is decoded before anything is written. When another save
     * already uses the same world name, the imported world is renamed so their box-score pages stay apart.
     */
    fun import(input: java.io.InputStream): ImportResult {
        val entries = HashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(input.buffered()).use { zip ->
            var e = zip.nextEntry
            var total = 0L
            while (e != null) {
                val name = e.name
                if (name.contains("..") || name.startsWith("/")) throw IllegalArgumentException("Bad file name in the archive: $name")
                val b = zip.readBytes()
                total += b.size
                if (total > 1_500_000_000L) throw IllegalArgumentException("Archive too large")
                entries[name] = b
                e = zip.nextEntry
            }
        }
        val manifest = kotlinx.serialization.json.Json.parseToJsonElement(String(entries["manifest.json"] ?: throw IllegalArgumentException("Not an Esports Manager save (no manifest)"))).let { it as kotlinx.serialization.json.JsonObject }
        fun str(k: String) = (manifest[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (str("format") != FORMAT) throw IllegalArgumentException("Not an Esports Manager save")
        if ((str("formatVersion")?.toIntOrNull() ?: 0) > FORMAT_VERSION) throw IllegalArgumentException("This save comes from a newer version of the game")
        val listed = (manifest["files"] as kotlinx.serialization.json.JsonArray).map { it as kotlinx.serialization.json.JsonObject }
        for (fe in listed) {
            val n = (fe["name"] as kotlinx.serialization.json.JsonPrimitive).content
            val b = entries[n] ?: throw IllegalArgumentException("Missing file $n")
            if (sha256(b) != (fe["sha256"] as kotlinx.serialization.json.JsonPrimitive).content) throw IllegalArgumentException("Checksum mismatch in $n: the file is damaged")
        }
        val worldBytes = entries["world.uem"] ?: throw IllegalArgumentException("Missing world")
        val sf = GZIPInputStream(worldBytes.inputStream()).use { ProtoBuf.decodeFromByteArray<SaveFile>(it.readBytes()) }
        // A slot and a world name nobody else uses.
        val usedNames = list().mapNotNull { m -> runCatching { read(file(m.slot)).world.name }.getOrNull() }.toSet()
        var name = sf.world.name
        var renamed = false
        if (name in usedNames || pagesDir(name).listFiles().orEmpty().isNotEmpty()) {
            var k = 1
            while ("$name (imported $k)" in usedNames || File(root, "${"$name (imported $k)".replace(Regex("[^A-Za-z0-9_-]"), "_")}.pages").exists()) k++
            name = "$name (imported $k)"
            renamed = true
        }
        var slot = name.replace(Regex("[^A-Za-z0-9_-]"), "_")
        var k = 2
        while (file(slot).exists()) slot = name.replace(Regex("[^A-Za-z0-9_-]"), "_") + "_$k".also { k++ }
        sf.world.name = name
        sf.world.pagesKey = ""
        // Pages first, then the world (renamed into place).
        val dir = pagesDir(name)
        for ((n, b) in entries) if (n.startsWith("pages/") && n.endsWith(".bin")) File(dir, n.removePrefix("pages/")).writeBytes(b)
        val tmp = File(root, "$slot.tmp")
        GZIPOutputStream(tmp.outputStream().buffered(1 shl 16)).use { it.write(ProtoBuf.encodeToByteArray(sf)) }
        if (!tmp.renameTo(file(slot))) { tmp.copyTo(file(slot), overwrite = true); tmp.delete() }
        writeMeta(slot, sf.world, name)
        return ImportResult(slot, name, renamed)
    }

    /** Delete a save file and its own box-score folder (other saves of the same career keep theirs). */
    fun delete(slot: String) {
        val key = readMeta(slot)?.get("pages")
        file(slot).delete()
        File(root, "$slot.bak").delete()
        File(root, "$slot.tmp").delete()
        metaFile(slot).delete()
        val inUse = list().map { it.pagesKey.ifBlank { it.slot } }.toSet()
        if (key != null && key !in inUse) pagesDir(key).deleteRecursively()
        if (slot !in inUse) File(root, "$slot.pages").takeIf { it.exists() && (key == null || key == slot) }?.deleteRecursively()
    }
}

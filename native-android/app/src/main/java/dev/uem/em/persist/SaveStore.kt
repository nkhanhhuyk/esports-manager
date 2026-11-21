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
 * Native saves: the world as compact binary (protobuf), gzip-compressed, written to a temporary
 * file and renamed into place (a crash mid-save never damages the previous save). The previous
 * save is kept as `<slot>.bak`. Box-score pages live in `<slot>.pages/`.
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

    /** Page store for box scores (attach with BoxStore.store = saveStore.pages()). */
    fun pages() = object : BoxStore.PageStore {
        override fun write(saveName: String, page: Int, records: List<MatchRecord>) {
            // Written to a temporary file and renamed, so a page is never half-written.
            val f = File(pagesDir(saveName), "$page.bin")
            val tmp = File(pagesDir(saveName), "$page.tmp")
            GZIPOutputStream(tmp.outputStream().buffered()).use { it.write(ProtoBuf.encodeToByteArray(ListSerializer(MatchRecord.serializer()), records)) }
            if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
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
        GZIPOutputStream(tmp.outputStream().buffered(1 shl 16)).use { it.write(bytes) }
        val target = file(slot)
        if (target.exists()) target.copyTo(File(root, "$slot.bak"), overwrite = true)
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
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
        metaFile(slot).writeText(j.toString())
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
        val f = file(slot).takeIf { it.exists() } ?: File(root, "$slot.bak")
        val sf = try { read(f) } catch (e: Exception) { read(File(root, "$slot.bak")) }
        val w = sf.world
        w.rebuildSchedule()
        BoxStore.clear()
        BoxStore.restore(sf.records)
        return w
    }

    private fun read(f: File): SaveFile = GZIPInputStream(f.inputStream().buffered(1 shl 16)).use { ProtoBuf.decodeFromByteArray<SaveFile>(it.readBytes()) }

    fun list(): List<Meta> = root.listFiles { f -> f.name.endsWith(".uem") }.orEmpty().mapNotNull { f ->
        val slot = f.nameWithoutExtension
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
        metaFile(slot).delete()
        val inUse = list().map { it.pagesKey.ifBlank { it.slot } }.toSet()
        if (key != null && key !in inUse) pagesDir(key).deleteRecursively()
        if (slot !in inUse) File(root, "$slot.pages").takeIf { it.exists() && (key == null || key == slot) }?.deleteRecursively()
    }
}

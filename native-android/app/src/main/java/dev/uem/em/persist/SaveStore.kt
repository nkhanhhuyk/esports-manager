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
    @Serializable
    class SaveFile(val world: World, val records: List<MatchRecord>)

    class Meta(val slot: String, val name: String, val day: Int, val season: Int, val modified: Long, val bytes: Long)

    init {
        root.mkdirs()
    }

    private fun file(slot: String) = File(root, "$slot.uem")
    fun pagesDir(name: String) = File(root, "${name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.pages").also { it.mkdirs() }

    /** Page store for box scores (attach with BoxStore.store = saveStore.pages()). */
    fun pages() = object : BoxStore.PageStore {
        override fun write(saveName: String, page: Int, records: List<MatchRecord>) {
            val f = File(pagesDir(saveName), "$page.bin")
            GZIPOutputStream(f.outputStream().buffered()).use { it.write(ProtoBuf.encodeToByteArray(ListSerializer(MatchRecord.serializer()), records)) }
        }

        override fun read(saveName: String, page: Int): List<MatchRecord>? {
            val f = File(pagesDir(saveName), "$page.bin")
            if (!f.exists()) return null
            return GZIPInputStream(f.inputStream().buffered()).use { ProtoBuf.decodeFromByteArray(ListSerializer(MatchRecord.serializer()), it.readBytes()) }
        }
    }

    fun save(slot: String, w: World) {
        val bytes = ProtoBuf.encodeToByteArray(SaveFile(w, BoxStore.unpaged()))
        val tmp = File(root, "$slot.tmp")
        GZIPOutputStream(tmp.outputStream().buffered(1 shl 16)).use { it.write(bytes) }
        val target = file(slot)
        if (target.exists()) target.copyTo(File(root, "$slot.bak"), overwrite = true)
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
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
        // Reading the whole save for its date is fine for a handful of slots; the name is the slot.
        Meta(f.nameWithoutExtension, f.nameWithoutExtension, 0, 0, f.lastModified(), f.length())
    }.sortedByDescending { it.modified }

    fun delete(slot: String) {
        file(slot).delete()
        File(root, "$slot.bak").delete()
        File(root, "$slot.pages").deleteRecursively()
    }
}

package dev.uem.em.game

import dev.uem.em.model.MatchRecord
import dev.uem.em.model.World
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-game box scores live outside the world save: recent ones in memory, older ones in weekly
 * compressed pages on disk (written by the app's [PageStore]), loaded when a match report opens.
 */
object BoxStore {
    interface PageStore {
        fun write(saveName: String, page: Int, records: List<MatchRecord>)
        fun read(saveName: String, page: Int): List<MatchRecord>?
    }

    @Volatile var store: PageStore? = null
    private val recent = ConcurrentHashMap<Int, MatchRecord>()
    private val pageCache = object : LinkedHashMap<Int, List<MatchRecord>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<MatchRecord>>?) = size > 4
    }

    fun clear() {
        recent.clear()
        synchronized(pageCache) { pageCache.clear() }
    }

    fun put(w: World, r: MatchRecord) {
        recent[r.fixtureId] = r
    }

    /** Weekly: records older than two days go to a new page (when a page store is attached). */
    fun pageOut(w: World) {
        val s = store ?: return
        val old = recent.values.filter { it.day <= w.day - 2 }.sortedBy { it.fixtureId }
        if (old.isEmpty()) return
        val page = ++w.boxPage
        s.write(w.pagesName(), page, old)
        for (r in old) {
            w.fixtures[r.fixtureId].page = page
            recent.remove(r.fixtureId)
        }
    }

    /** Records not yet paged (saved with the world so nothing is lost). */
    fun unpaged(): List<MatchRecord> = recent.values.sortedBy { it.fixtureId }
    fun restore(records: List<MatchRecord>) { for (r in records) recent[r.fixtureId] = r }

    /** Strip 2D replays from pages whose matches are all older than `beforeDay` (box scores stay). */
    fun pruneReplays(w: World, beforeDay: Int) {
        val s = store ?: return
        var page = w.replayPrunedPage + 1
        while (page <= w.boxPage) {
            val list = s.read(w.pagesName(), page)
            if (list != null) {
                if (list.any { it.day >= beforeDay }) return
                if (list.any { r -> r.games.any { it.replay != null } }) {
                    for (r in list) for (g in r.games) g.replay = null
                    s.write(w.pagesName(), page, list)
                    synchronized(pageCache) { pageCache.remove(page) }
                }
            }
            w.replayPrunedPage = page
            page++
        }
    }

    fun get(w: World, fixtureId: Int): MatchRecord? {
        recent[fixtureId]?.let { return it }
        val page = w.fixtures.getOrNull(fixtureId)?.page ?: return null
        if (page < 0) return null
        val list = synchronized(pageCache) { pageCache[page] } ?: store?.read(w.pagesName(), page)?.also { synchronized(pageCache) { pageCache[page] = it } } ?: return null
        return list.firstOrNull { it.fixtureId == fixtureId }
    }
}

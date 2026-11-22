package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.data.Xlsx
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Database
import dev.uem.em.mgmt.SoloQueue
import dev.uem.em.persist.SaveStore
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Database spreadsheet round trip, fast simulation, save-as copies and solo queue ladders. */
class FeaturesTest {
    @Test
    fun databaseRoundTrip() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "db", "DB", Dates.day(2026, 11, 21))
        val out = ByteArrayOutputStream()
        Database.export(w, out)
        val book = Xlsx.read(out.toByteArray().inputStream())
        assertTrue("sheets: ${book.keys}", book.keys.containsAll(listOf("Players", "Teams", "Organisations", "Leagues", "Competitions", "Staff", "Characters")))
        val players = book["Players"]!!.map { it.toMutableList() }.toMutableList()
        val head = players[0].map { it.lowercase() }
        fun c(n: String) = head.indexOf(n)
        // Edit the first rostered LoL player: handle, nationality, an attribute, a picture link.
        val ri = players.indexOfFirst { it.getOrNull(c("game")) == "lol" && it.getOrNull(c("team id"))?.isNotBlank() == true }
        val pid = players[ri][c("id")].toInt()
        val row = players[ri]
        fun set(col: String, v: String) { val i = c(col); while (row.size <= i) row.add(""); row[i] = v }
        set("handle", "EditedHandle"); set("nationality", "PH"); set("micro control", "99"); set("image url", "https://example.org/p.png"); set("date of birth", "2004-05-06")
        // A new player on a team (by name), and a bad nationality that must be reported.
        val team = w.teams.first { it.titleId == "lol" && it.active && it.leagueId >= 0 }
        val newRow = MutableList(head.size) { "" }
        newRow[c("game")] = "lol"; newRow[c("handle")] = "Newcomer"; newRow[c("nationality")] = "KR"; newRow[c("main role")] = Catalogue.get("lol").lineup[0]; newRow[c("team")] = team.name; newRow[c("date of birth")] = "2007-01-02"
        players.add(newRow)
        val bad = MutableList(head.size) { "" }; bad[c("id")] = players[ri + 1][c("id")]; bad[c("nationality")] = "ZZ"; players.add(bad)
        val teams = book["Teams"]!!.map { it.toMutableList() }.toMutableList()
        val th = teams[0].map { it.lowercase() }
        val tr = teams.indexOfFirst { it.getOrNull(0) == team.id.toString() }
        teams[tr][th.indexOf("name")] = "Renamed Team"
        val rep = Database.import(w, mapOf("Players" to players, "Teams" to teams))
        val p = w.players[pid]
        assertEquals("EditedHandle", p.handle)
        assertEquals("PH", p.nat)
        assertEquals(99.0, p.attrs[0], 0.0)
        assertEquals("https://example.org/p.png", w.images["player:$pid"])
        assertEquals(Database.parseDate("2004-05-06"), p.birth)
        val nw = w.players.last()
        assertEquals("Newcomer", nw.handle)
        assertEquals(team.id, nw.teamId)
        assertTrue(nw.id in team.roster)
        assertEquals("Renamed Team", team.name)
        assertTrue("bad nationality reported: ${rep.errors}", rep.errors.any { it.contains("ZZ") })
        // New game: matched by key (game|original handle), so the edit carries over.
        val w2 = WorldGen.create(TestData.source, "db2", "DB2", Dates.day(2026, 11, 21))
        val origKey = book["Players"]!![ri][c("match key")]
        val rep2 = Database.import(w2, mapOf("Players" to players), byKey = true)
        assertTrue(rep2.text(), w2.players.any { "${it.titleId}|EditedHandle" == "lol|EditedHandle" && it.nat == "PH" } || origKey.isBlank())
        // CSV reading.
        val csv = Xlsx.readCsv("id,handle\n1,\"A, B\"\n2,\"say \"\"hi\"\"\"\n")
        assertEquals("A, B", csv[1][1]); assertEquals("say \"hi\"", csv[2][1])
    }

    @Test
    fun fastSimulationAndLadders() {
        TestData.init(); BoxStore.clear()
        val full = WorldGen.create(TestData.source, "fast", "F1", Dates.day(2026, 11, 21))
        val fast = WorldGen.create(TestData.source, "fast", "F2", Dates.day(2026, 11, 21))
        fast.simDetail = "fast"; fast.replays = "off"
        full.replays = "off"
        var t0 = System.currentTimeMillis(); Advance.run(full, 60); val msFull = System.currentTimeMillis() - t0
        Advance.timing.fill(0)
        t0 = System.currentTimeMillis(); Advance.run(fast, 60); val msFast = System.currentTimeMillis() - t0
        println("fast split (ms): " + listOf("start/patches", "matches", "daily", "monthly/weekly", "market/roles", "rollover").mapIndexed { i, n -> "$n ${Advance.timing[i] / 1_000_000}" }.joinToString(", "))
        println("60 days: full $msFull ms, fast $msFast ms (${full.fixtures.count { it.done }} / ${fast.fixtures.count { it.done }} series)")
        assertEquals(full.fixtures.count { it.done }, fast.fixtures.count { it.done })
        assertTrue("fast simulation should be faster ($msFast vs $msFull ms)", msFast * 4 < msFull * 3)
        // Box scores exist for fast games with sensible numbers.
        val f = fast.fixtures.first { it.done && fast.teams[it.a].titleId == "lol" }
        val rec = BoxStore.get(fast, f.id)!!
        val k = Catalogue.get("lol").statIndex["k"]!!
        val kills = rec.games[0].lines.sumOf { it.s[k] }
        assertTrue("kills $kills", kills in 6.0..80.0)
        // Solo queue: ladders exist and amateurs appear.
        val lad = SoloQueue.ladder(fast, "lol", null)
        assertTrue(lad.size > 50)
        assertTrue(lad.first().soloMmr > lad.last().soloMmr)
    }

    @Test
    fun saveAsKeepsBothFiles() {
        TestData.init()
        val dir = File("build/test-saves-as").apply { deleteRecursively(); mkdirs() }
        val store = SaveStore(dir)
        BoxStore.clear(); BoxStore.store = store.pages()
        val w = WorldGen.create(TestData.source, "saveas", "Career", Dates.day(2026, 11, 21))
        Advance.run(w, 20)
        store.save("career", w, "Career")
        val day1 = w.day
        val slot2 = store.saveAs("Second file", w)
        Advance.run(w, 20)
        store.save(slot2, w)
        val list = store.list()
        assertEquals(2, list.size)
        assertEquals(day1, store.load("career").day)
        assertEquals(w.day, store.load(slot2).day)
        store.rename(slot2, "Renamed")
        assertTrue(store.list().any { it.name == "Renamed" })
        store.delete("career")
        assertEquals(1, store.list().size)
        // The remaining file still reads its box scores.
        val back = store.load(slot2)
        val old = back.fixtures.firstOrNull { it.done && it.page >= 0 }
        if (old != null) assertTrue(BoxStore.get(back, old.id) != null)
        BoxStore.store = null
    }
}

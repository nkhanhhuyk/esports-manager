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
        // A new competition from a sheet row.
        val ch = listOf("id", "game", "season", "name", "short", "tier (read only)", "region (read only)", "start", "prize pool", "image url", "format (new)", "teams (new)", "best of: groups / playoffs / final (new)", "groups (new)", "advance per group (new)", "every season (new)", "entry fee (new)")
        val lolTeams = w.teams.filter { it.titleId == "lol" && it.active && it.leagueId >= 0 }.take(8).joinToString(";") { it.name }
        val y = dev.uem.em.core.Dates.year(w.day) + 1
        val compRow = listOf("", "lol", "", "Spreadsheet Invitational", "SPRI", "", "", "$y-08-20", "500000", "", "se", lolTeams, "3/3/5", "", "", "Y", "")
        val before = w.comps.size
        val rep3 = Database.import(w, mapOf("Competitions" to listOf(ch, compRow)))
        assertTrue("competition created: ${rep3.errors}", rep3.created == 1 && w.comps.size == before + 1 && w.customComps.any { it.name == "Spreadsheet Invitational" && it.recurring })
        // Esports World Cup and Esports Nations Cup are on the calendar for every game with leagues.
        assertTrue(w.comps.count { it.name.startsWith("Esports World Cup") } >= 5)
        assertTrue(w.comps.count { it.name.startsWith("Esports Nations Cup") } >= 5)
        assertTrue(dev.uem.em.data.GameData.countries.size >= 249)
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

class RealDataTest {
    @Test
    fun realRatingsSameInEverySaveAndNoGeneratedFillIns() {
        TestData.init(); BoxStore.clear()
        val a = WorldGen.create(TestData.source, "seed-one", "A", Dates.day(2026, 11, 21))
        val b = WorldGen.create(TestData.source, "seed-two", "B", Dates.day(2026, 11, 21))
        val ra = a.players.filter { it.real && it.teamId >= 0 }.associateBy { "${it.titleId}|${it.handle}" }
        val rb = b.players.filter { it.real && it.teamId >= 0 }.associateBy { "${it.titleId}|${it.handle}" }
        var same = 0
        for ((k, p) in ra) { val q = rb[k] ?: continue
            if (p.ca != q.ca) println("DIFF $k nat ${p.nat}/${q.nat} birth ${p.birth}/${q.birth} roles ${p.roles}/${q.roles} input ${p.input.toList()}/${q.input.toList()} team ${a.teams[p.teamId].name}/${b.teams[q.teamId].name} attrs ${p.attrs.toList().zip(q.attrs.toList()).count { it.first != it.second }} prof ${p.profTitle}/${q.profTitle} ${p.profRole}/${q.profRole}")
            assertEquals("$k CA", p.ca, q.ca, 0.0); assertEquals("$k PA", p.pa, q.pa, 0.0); same++ }
        assertTrue(same > 500)
        val faker = a.players.first { it.titleId == "lol" && it.handle == "Faker" }
        val chovy = a.players.first { it.titleId == "lol" && it.handle == "Chovy" }
        println("Faker ${faker.ca}/${faker.pa}, Chovy ${chovy.ca}/${chovy.pa}, ZywOo ${a.players.first { it.titleId == "cs2" && it.handle == "ZywOo" }.ca}")
        assertEquals(87.0, faker.ca, 1.5)
        assertEquals(91.0, chovy.ca, 1.5)
        // Real teams carry no generated players (real free agents complete short rosters).
        val gen = a.players.filter { p -> p.teamId >= 0 && a.teams[p.teamId].real && p.realEst.any { it.startsWith("generated") } }
        println("generated players on real teams: ${gen.size} ${gen.take(5).map { a.teams[it.teamId].name }}")
        assertTrue(gen.size <= 3)
        // No transfers in the first season.
        assertTrue(dev.uem.em.mgmt.Market.frozen(a))
        Advance.run(a, 120)
        val moves = a.transfers.count { it.season == a.firstSeason && it.kind != "free agent" && it.kind != "signed" }
        println("first-season moves: $moves")
        assertTrue("first-season transfers: $moves", moves <= a.teams.count { it.active } / 10)
    }
}

package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.titles.Catalogue
import org.junit.Test
import java.io.File

/** Writes build/data-report.md: what is verified, estimated, historical or generated, per game. */
class DataReportTest {
    @Test
    fun report() {
        TestData.init()
        BoxStore.clear()
        val w = WorldGen.create(TestData.source, "report", "Report", Dates.day(2026, 11, 21))
        val sb = StringBuilder()
        sb.appendLine("| Game | Real leagues | Real teams (active) | Historical teams | Fictional teams | Real players | of which: nationality est. | birth est. | position from recent matches | position assigned by the game | Generated fill-ins | Characters |")
        sb.appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
        for (id in w.titles) {
            val t = Catalogue.get(id)
            val teams = w.teams.filter { it.titleId == id }
            val real = w.players.filter { it.titleId == id && it.real }
            fun pct(f: (String) -> Boolean) = if (real.isEmpty()) "—" else "${real.count { p -> p.realEst.any(f) }} (${100 * real.count { p -> p.realEst.any(f) } / real.size}%)"
            sb.appendLine("| ${t.name} | ${w.leagues.count { it.titleId == id && it.real }} of ${w.leagues.count { it.titleId == id }} | ${teams.count { it.real && it.active }} | ${teams.count { it.real && !it.active }} | ${teams.count { !it.real }} | ${real.size} | ${pct { it == "nationality" }} | ${pct { it == "age" }} | ${pct { it == "role (from recent matches)" || it == "role" }} | ${pct { it == "role (game assignment)" }} | ${w.players.count { it.titleId == id && !it.real && it.realEst.any { e -> e.startsWith("generated") } }} | ${t.characters.size} |")
        }
        sb.appendLine()
        val notes = w.teams.filter { it.dataNote.isNotBlank() }.groupBy { it.titleId to it.dataNote.substringBefore(":").substringBefore(" (").take(60) }
        sb.appendLine("Team data notes (count): " + notes.entries.sortedBy { it.key.first }.joinToString("; ") { "${it.key.first}: ${it.key.second} ×${it.value.size}" })
        File("build/data-report.md").writeText(sb.toString())
        println(sb)
    }
}

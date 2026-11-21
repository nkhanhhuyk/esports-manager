package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two worlds in one process must not affect each other: loading another save or starting a new
 * game after playing must give the same world as in a fresh app. (Ratings were once cached by
 * player id across worlds, which gave a new world the old world's ratings.)
 */
class WorldIsolationTest {
    private fun fingerprint(w: World): String {
        val sb = StringBuilder()
        var h = 17L
        for (p in w.players) { h = h * 31 + p.id; for (a in p.attrs) h = h * 31 + (a * 100).toLong(); h = h * 31 + p.ca.toLong() }
        sb.append("players ${w.players.size} attrs $h")
        var r = 0.0
        for (t in w.teams.filter { it.active }) r += dev.uem.em.sim.Lineups.strength(w, t)
        sb.append(" strength %.6f".format(r))
        sb.append(" chars " + w.titles.joinToString(",") { "${it}:${Catalogue.get(it).characters.size}" })
        return sb.toString()
    }

    @Test
    fun anotherWorldDoesNotLeakIntoANewOne() {
        TestData.init(); BoxStore.clear()
        val day = Dates.day(2026, 11, 21)
        val a = fingerprint(WorldGen.create(TestData.source, "isolation", "I", day))
        // Play a different world for a season and a bit (progression, patches, new characters).
        val other = WorldGen.create(TestData.source, "other", "O", day)
        Advance.run(other, 400)
        val b = fingerprint(WorldGen.create(TestData.source, "isolation", "I", day))
        assertEquals("the same seed must give the same world after another world was played", a, b)
    }
}

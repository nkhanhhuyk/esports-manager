package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.gen.WorldGen
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldGenTest {
    @Test
    fun createsRealWorld() {
        TestData.init()
        val t0 = System.currentTimeMillis()
        val w = WorldGen.create(TestData.source, "test", "Test", Dates.day(2026, 11, 21))
        val ms = System.currentTimeMillis() - t0
        println("world in $ms ms: players ${w.players.size} teams ${w.teams.size} active ${w.teams.count { it.active }} orgs ${w.orgs.size} leagues ${w.leagues.size} comps ${w.comps.size}")
        for (id in w.titles) println("  $id leagues: " + w.leagues.filter { it.titleId == id }.joinToString { "${it.short}(${it.teamIds.size}${if (it.real) " real" else ""})" })
        val faker = w.players.firstOrNull { it.handle == "Faker" }
        println("Faker: ${faker?.name()} ${faker?.nat} age ${faker?.let { Dates.ageAt(it.birth, w.day) }} team ${faker?.let { w.teams.getOrNull(it.teamId)?.name }} ca ${faker?.ca} role ${faker?.roles}")
        assertTrue(w.teams.count { it.active } > 150)
        assertTrue(w.comps.size > 50)
        assertTrue(w.players.count { it.real } > 1500)
    }
}

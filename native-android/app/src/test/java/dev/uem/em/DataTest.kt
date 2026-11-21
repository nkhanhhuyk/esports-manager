package dev.uem.em

import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataTest {
    @Test
    fun loadsTitlesAndData() {
        TestData.init()
        assertEquals(7, Catalogue.ALL.size)
        for (t in Catalogue.ALL) if (t.id != "cs2") assertTrue("${t.id} characters", t.characters.size > 20)
        assertEquals(7, Catalogue.get("valorant").maps.size)
        assertTrue(dev.uem.em.data.GameData.countries.size > 90)
    }
}

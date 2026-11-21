package dev.uem.em

import dev.uem.em.data.DataSource
import dev.uem.em.data.GameData
import dev.uem.em.titles.Catalogue
import java.io.File

/** Test data source: the repository's real-data snapshot and the native assets. */
object TestData {
    private val roots = listOf(File("../../src/data/real"), File("src/main/assets"))
    val source = DataSource { name -> roots.map { File(it, name) }.first { it.exists() }.readText() }

    fun init() {
        GameData.init(source)
        Catalogue.init(source)
    }
}

package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.BoxStore
import dev.uem.em.persist.SaveStore
import org.junit.Assume
import org.junit.Test
import java.io.File

/** Look inside a harness save (UEM_INSPECT=directory, UEM_INSPECT_NAME=part of a competition name). */
class InspectSaveTest {
    @Test
    fun inspect() {
        val dir = System.getenv("UEM_INSPECT"); Assume.assumeTrue(dir != null)
        TestData.init()
        val store = SaveStore(File(dir!!)); BoxStore.clear(); BoxStore.store = store.pages()
        val w = store.load("slot1")
        val pat = System.getenv("UEM_INSPECT_NAME") ?: "LCP Amateur Championship"
        println("world day ${Dates.format(w.day)} season ${w.season}")
        for (c in w.comps.filter { it.name.contains(pat) }.takeLast(6)) {
            println("${c.name} #${c.id} status ${c.status} start ${Dates.format(c.startDay)} teams ${c.teamIds.size} entrants ${c.entrants} key ${c.defKey}")
            for ((i, st) in c.stages.withIndex()) println("   stage $i ${st.name} ${st.type} status ${st.status} teams ${st.teamIds.size} fixtures ${st.fixtureIds.size} done ${st.fixtureIds.count { w.fixtures[it].done }} pending days ${st.fixtureIds.filter { !w.fixtures[it].done }.map { Dates.format(w.fixtures[it].day) }.distinct().take(4)}")
            val p = c.entrants.firstOrNull()?.split(':')
            if (p != null && p[0] == "amateurpts") {
                val waiting = w.comps.filter { it.season == c.season && it.titleId == p[1] && it.circuit == "amateur" && it.startDay < c.startDay && !it.done && it.entrants.firstOrNull()?.split(':')?.getOrNull(2) == p[2] }
                println("   waiting for: ${waiting.map { "${it.name} #${it.id} status ${it.status} start ${Dates.format(it.startDay)} entrants ${it.entrants}" }}")
                for (o in waiting.take(2)) for ((i, st) in o.stages.withIndex()) println("      ${o.name} stage $i ${st.type} status ${st.status} teams ${st.teamIds.size} fixtures ${st.fixtureIds.size}/${st.fixtureIds.count { w.fixtures[it].done }} bracket ${st.bracket.map { "${it.id}:${it.teamA}v${it.teamB}->${it.winner} f${it.fixtureId}" }.take(8)}")
            }
        }
        val l = w.leagues.firstOrNull { it.short == "LCP" && it.titleId == "lol" }
        if (l != null) println("LCP amateur clubs: ${dev.uem.em.mgmt.Circuits.teamsOf(w, "lol", l, "amateur").map { "${it.short}(${it.roster.size})" }}")
    }
}

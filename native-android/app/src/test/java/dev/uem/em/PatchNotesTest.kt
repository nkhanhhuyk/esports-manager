package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Patches
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Patch notes say what they did: a buff shows larger numbers (shorter cooldowns), a nerf smaller
 * ones, for champions, items, runes / emblems / arcana / facets, spells and neutral objectives.
 * (Rune and objective nerfs once read "nerfed (+2.7% armour)" — the new value, with its plus sign.)
 */
class PatchNotesTest {
    private val num = Regex("""(-?\d+(?:\.\d+)?)%?(?: s)? → (-?\d+(?:\.\d+)?)""")

    @Test
    fun buffsGoUpAndNerfsGoDown() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "notes", "N", Dates.day(2026, 11, 21))
        val bad = ArrayList<String>()
        var checked = 0
        val sample = StringBuilder()
        for (id in w.titles) {
            val t = Catalogue.get(id)
            for (i in 0 until 40) {
                val kind = listOf("minor", "medium", "major")[i % 3]
                Patches.make(w, t, kind, Rng.of("notes|$id|$i"))
                for (n in w.patches.last().notes) {
                    val buff = Regex("""\bbuffed\b""").containsMatchIn(n); val nerf = Regex("""\bnerfed\b""").containsMatchIn(n)
                    if (!buff && !nerf) continue
                    if (buff && nerf) { bad.add("both: $n"); continue }
                    checked++
                    if (i < 3 && sample.length < 4000) sample.appendLine(n)
                    // Every "a → b" in the line: up for a buff, down for a nerf; cooldowns the other way.
                    for (m in num.findAll(n)) {
                        val a = m.groupValues[1].toDouble(); val b = m.groupValues[2].toDouble()
                        val cooldown = n.substring(maxOf(0, m.range.first - 10), m.range.first).contains("cooldown")
                        val up = if (cooldown) b <= a else b >= a
                        if (a != b && up != buff) bad.add(n)
                    }
                    // The signed figure in brackets (meta, % stats, strength) agrees too.
                    Regex("""\(([+-]\d+(?:\.\d+)?)(?: meta|% stats)""").find(n)?.let { if ((it.groupValues[1].toDouble() > 0) != buff) bad.add(n) }
                    Regex("""strength ([+-]?\d+)%""").find(n)?.let { if (it.groupValues[1].toInt() != 0 && (it.groupValues[1].toInt() > 0) != buff) bad.add(n) }
                    // No unexplained changes: an item, rune, spell or objective note shows its numbers.
                    if (n.startsWith("Item —") || n.startsWith("Rune —") || n.startsWith("Emblem —") || n.startsWith("Arcana —") || n.startsWith("Talent —") || n.startsWith("Objective —") || n.startsWith("Spell —"))
                        if (!num.containsMatchIn(n)) bad.add("no numbers: $n")
                }
            }
        }
        println(sample); java.io.File("build/patch-notes.txt").writeText(sample.toString() + "\nchecked $checked lines, wrong ${bad.size}\n" + bad.take(20).joinToString("\n"))
        assertTrue("checked only $checked lines", checked > 500)
        assertTrue("patch notes that contradict themselves: ${bad.take(8)}", bad.isEmpty())
    }
}

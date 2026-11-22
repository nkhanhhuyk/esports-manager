package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.Items
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real items: catalogues load, heroes build items fitting their class during a match, the box score keeps the build. */
class ItemsTest {
    @Test
    fun buildsFollowTheClass() {
        val w = MobaBalanceTest.world()
        val sb = StringBuilder()
        val fails = ArrayList<String>()
        for (id in listOf("lol", "wildrift", "mlbb", "hok", "dota2")) {
            val t = Catalogue.get(id)
            assertTrue("$id has items", Items.of(w, id).size >= 40)
            val teams = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }
            var physMarks = 0; var marks = 0; var magMages = 0; var mages = 0; var defTanks = 0; var tanks = 0; var itemsTotal = 0; var lines = 0
            repeat(12) { i ->
                val rng = Rng.of("items|$id|$i")
                val a = teams[i % teams.size]; val b = teams[(i * 5 + 1) % teams.size].let { if (it == a) teams[(i + 1) % teams.size] else it }
                val ctxs = listOf(a, b).mapIndexed { side, team ->
                    val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                    val chars = HashMap<Int, String>(); val used = HashSet<String>()
                    lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }; val c = opts[(i * 11 + k * 3 + side) % opts.size]; used.add(c.id); chars[pid] = c.id } }
                    TeamContexts.build(w, team, lineup, side, chars, null, 0.0, rng)
                }
                val o = MobaEngine.simulate(t, ctxs, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), rng, Patch.of(w, t), null, MobaEngine.Opts(world = w))
                for (l in o.book.lines) {
                    lines++; itemsTotal += l.items.size
                    val cls = (t.charIndex[l.charId]?.cls ?: "").lowercase()
                    val types = l.items.mapNotNull { Items.get(w, it)?.type }
                    if (Regex("marksman").containsMatchIn(cls)) { marks++; if (types.count { it == "physical" } >= types.count { it == "magical" }) physMarks++ }
                    if (Regex("^mage|mage$").containsMatchIn(cls)) { mages++; if (types.count { it == "magical" } >= types.count { it == "physical" }) magMages++ else if (System.getenv("UEM_ITEMDBG") != null) sb.appendLine("  MAGE PHYS ${t.short} ${t.charIndex[l.charId]?.name} [${t.role(l.roleId).short}]: " + l.items.mapNotNull { Items.get(w, it)?.let { x -> x.name + "/" + x.type } }.joinToString(", ")) }
                    if (Regex("tank").containsMatchIn(cls)) { tanks++; if (types.count { it == "defense" || it == "support" } >= types.count { it == "physical" || it == "magical" }) defTanks++ else if (System.getenv("UEM_ITEMDBG") != null) sb.appendLine("  TANK DMG ${t.short} ${t.charIndex[l.charId]?.name} [${t.role(l.roleId).short}]: " + l.items.mapNotNull { Items.get(w, it)?.let { x -> x.name + "/" + x.type + x.stats } }.joinToString(", ")) }
                    if (i < 2 && l.items.isNotEmpty()) sb.appendLine("  ${t.short} ${t.charIndex[l.charId]?.name} (${cls}): " + l.items.mapNotNull { Items.get(w, it)?.name }.joinToString(", "))
                }
            }
            sb.appendLine("${t.short}: ${"%.1f".format(itemsTotal.toDouble() / lines)} items per player; marksmen physical $physMarks/$marks, mages magic $magMages/$mages, tanks defensive $defTanks/$tanks")
            if (itemsTotal.toDouble() / lines < 3.0) fails.add("$id: players finish games with items")
            if (marks > 3 && physMarks < marks * 0.7) fails.add("$id marksmen build damage")
            if (mages > 3 && magMages < mages * 0.7) fails.add("$id mages build magic")
            if (tanks > 3 && defTanks < tanks * 0.55) fails.add("$id tanks build defence")
        }
        // A tank far ahead may buy damage, but never more than two damage items, and only after its core defence.
        run {
            val t = Catalogue.get("lol")
            val prof = Items.profile("Tank", false, 0.3, false, Rng.of("tank"))
            var snow = 0; var maxDmg = 0
            repeat(40) { i ->
                val owned = ArrayList<dev.uem.em.titles.ItemDef>()
                repeat(6) { k -> Items.next(w, t.id, owned, prof, 0.4, -0.5, Rng.of("tank|$i|$k"), false)?.let { owned.add(it) } }
                val dmg = owned.count { it.type == "physical" || it.type == "magical" }
                if (dmg > 0) snow++; maxDmg = maxOf(maxDmg, dmg)
            }
            sb.appendLine("LoL tanks far ahead: $snow/40 builds with a damage item, at most $maxDmg")
            if (snow == 0) fails.add("snowballing tanks never buy damage")
            if (maxDmg > 2) fails.add("tanks buy more than two damage items")
        }
        println(sb); java.io.File("build/items.txt").writeText(sb.toString())
        assertTrue(fails.joinToString(), fails.isEmpty())
    }
}

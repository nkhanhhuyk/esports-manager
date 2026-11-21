package dev.uem.em

import dev.uem.em.titles.Catalogue
import dev.uem.em.ui.Maps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Map validation: reachability, one-way links, levels, three-site maps, MOBA geometry. */
class MapTest {
    private fun init() { TestData.init(); Maps.init(TestData.source) }

    @Test
    fun shooterMapsAreNavigable() {
        init()
        for (title in listOf("valorant", "cs2")) for (md in Catalogue.get(title).maps) {
            val m = Maps.shooter("$title:${md.id}") ?: Maps.shooter(md.id) ?: error("no geometry for $title ${md.id}")
            val n = m.nodes.size
            for (l in m.links) { assertTrue("${m.name}: link out of range", l.from in 0 until n && l.to in 0 until n); assertTrue("${m.name}: self link", l.from != l.to) }
            for (nd in m.nodes) assertTrue("${m.name}: node off the map", nd.pos.x in 0f..1f && nd.pos.y in 0f..1f)
            assertTrue("${m.name}: spawns", m.atk != m.def)
            for (s in m.sites) {
                assertTrue("${m.name}: attackers cannot reach ${s.id}", m.path(m.atk, s.node).isNotEmpty())
                assertTrue("${m.name}: defenders cannot reach ${s.id}", m.path(m.def, s.node).isNotEmpty())
                // Every site connects to every other site (rotations).
                for (o in m.sites) assertTrue("${m.name}: no rotation ${s.id}→${o.id}", m.path(s.node, o.node).isNotEmpty())
            }
            // Ignoring direction, the whole callout network is one piece.
            val seen = HashSet<Int>()
            val stack = ArrayDeque(listOf(0))
            while (stack.isNotEmpty()) { val u = stack.removeLast(); if (!seen.add(u)) continue; for (l in m.links) { if (l.from == u) stack.add(l.to); if (l.to == u) stack.add(l.from) } }
            assertEquals("${m.name}: disconnected callouts", n, seen.size)
        }
    }

    @Test
    fun specialTraversal() {
        init()
        val bind = Maps.shooter("valorant:bind")!!
        val tele = bind.links.filter { it.type == "teleport" }
        assertEquals(2, tele.size)
        for (l in tele) {
            assertTrue(l.oneWay)
            assertNull("teleporter usable backwards", bind.linkBetween(l.to, l.from)?.takeIf { it.type == "teleport" })
            assertEquals(listOf(l.from, l.to), bind.path(l.from, l.to))
        }
        assertTrue("A Short teleporter leads to B Short", tele.any { bind.nodes[it.from].name.contains("Teleporter") && bind.nodes[it.from].sup == "A" && bind.nodes[it.to].name == "Short" && bind.nodes[it.to].sup == "B" })
        assertTrue("B Long teleporter leads to A Lobby", tele.any { bind.nodes[it.from].sup == "B" && bind.nodes[it.to].name == "Lobby" && bind.nodes[it.to].sup == "A" })
        val nuke = Maps.shooter("cs2:nuke")!!
        assertTrue("Nuke has levels", nuke.nodes.map { it.level }.distinct().size >= 3)
        val upper = nuke.sites.first { it.id == "A" }.node
        val lower = nuke.sites.first { it.id == "B" }.node
        assertTrue(nuke.nodes[upper].level > nuke.nodes[lower].level)
        assertTrue("Nuke vents", nuke.links.count { it.type == "vent" } >= 2)
        assertTrue(nuke.path(upper, lower).isNotEmpty() && nuke.path(lower, upper).isNotEmpty())
        val lotus = Maps.shooter("valorant:lotus")!!
        val drop = lotus.links.first { it.type == "drop" }
        assertNull("A Drop usable upwards", lotus.linkBetween(drop.to, drop.from))
        assertTrue(lotus.links.count { it.type == "door" } >= 2)
        for (id in listOf("valorant:haven", "valorant:lotus")) assertEquals("$id three sites", 3, Maps.shooter(id)!!.sites.size)
    }

    @Test
    fun mobaMapsAreComplete() {
        init()
        for (id in Catalogue.IDS.filter { Catalogue.get(it).isMoba }) {
            val m = Maps.moba(id) ?: error("no MOBA map for $id")
            val t = Catalogue.get(id)
            assertEquals("$id lanes", t.moba!!.structures.lanes, m.lanes.size)
            for ((name, line) in m.lanes) { assertTrue("$id $name", line.pts.size >= 2); for (p in line.pts) assertTrue(p.x in 0f..1f && p.y in 0f..1f) }
            assertEquals(2, m.fountains.size)
            assertEquals("$id cores", 2, m.structures.count { it.kind == "core" })
            for (team in 0..1) assertTrue("$id towers", m.structures.count { it.team == team && it.lane != null } >= t.moba.structures.lanes * t.moba.structures.perLane)
            val inPits = m.pits.flatMap { it.objectives }.toSet()
            val missing = t.moba.objectives.map { it.id }.filter { it !in inPits }
            println("$id: ${m.name}, ${m.structures.size} structures, ${m.camps.size} camps, ${m.pits.size} pits; objectives without a pit: $missing")
            assertTrue("$id pits", m.pits.isNotEmpty())
        }
    }
}

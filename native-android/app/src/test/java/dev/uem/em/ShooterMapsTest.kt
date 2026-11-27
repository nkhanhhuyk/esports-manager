package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.PlayerCtx
import dev.uem.em.sim.Recorder
import dev.uem.em.sim.ShooterGrid
import dev.uem.em.sim.ShooterRound
import dev.uem.em.sim.TacticalEngine
import dev.uem.em.sim.TeamContexts
import dev.uem.em.sim.Weapons
import dev.uem.em.titles.Catalogue
import dev.uem.em.ui.Maps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every VALORANT and CS2 map of the pool, map by map: reachability (no dead ends, every site from every node and
 * both spawns), no walkable link through a wall, special links only where the map really has them, pathfinding
 * around closed links, sight through the exact wall raster (thin walls included), floors on Nuke, the Lotus
 * breakable wall and doors, and seeded full games whose positions, plants and defuses are all legal. Results are
 * written to build/shooter-maps.txt.
 */
class ShooterMapsTest {
    private val ids = listOf("valorant", "cs2")
    private fun maps(): List<Pair<String, Maps.Shooter>> { TestData.init(); Maps.init(TestData.source); for (t in ids) for (md in Catalogue.get(t).maps) assertNotNull("no geometry for ${md.id}", Maps.shooter(md.id)); return Maps.shooterIds().map { it to Maps.shooter(it)!! } }
    private fun walkable(type: String) = type == "walk" || type == "stairs" || type == "door"

    @Test
    fun graphs() {
        val out = StringBuilder()
        for ((id, m) in maps()) {
            val n = m.nodes.size
            // Every node can reach every site and both spawns (directed: drops and teleporters are one-way), and every
            // node is reachable from a spawn: no dead ends, no traps after a drop or a teleport.
            for (u in 0 until n) {
                for (s in m.sites) assertTrue("$id: ${m.nodes[u].name} cannot reach site ${s.id}", m.path(u, s.node).isNotEmpty())
                assertTrue("$id: ${m.nodes[u].name} cannot get back to the attackers' spawn", m.path(u, m.atk).isNotEmpty())
                assertTrue("$id: ${m.nodes[u].name} cannot get back to the defenders' spawn", m.path(u, m.def).isNotEmpty())
                assertTrue("$id: ${m.nodes[u].name} unreachable from both spawns", m.path(m.atk, u).isNotEmpty() || m.path(m.def, u).isNotEmpty())
            }
            // Nothing stands in a wall: nodes, sites, spawns; no walkable link passes through one.
            for (nd in m.nodes) assertFalse("$id: node ${nd.sup} ${nd.name} inside a wall", ShooterGrid.blocked(m, nd.pos.x, nd.pos.y))
            for (s in m.sites) { assertFalse("$id: site ${s.id} inside a wall", ShooterGrid.blocked(m, s.pos.x, s.pos.y)); assertTrue("$id: site ${s.id}'s node is not on the site", (m.nodes[s.node].pos - s.pos).getDistance() <= s.r) }
            var crossing = 0
            for (l in m.links) {
                val a = m.nodes[l.from].pos; val b = m.nodes[l.to].pos
                val through = (1 until 200).any { k -> val f = k / 200f; ShooterGrid.blocked(m, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f) }
                if (walkable(l.type)) assertFalse("$id: ${l.type} ${m.nodes[l.from].name} → ${m.nodes[l.to].name} goes through a wall", through)
                else if (through) crossing++
                assertTrue("$id: link from a node to itself", l.from != l.to)
            }
            // Special traversal only where the map has it (assets/maps_topology.json, with its sources).
            val types = m.links.map { it.type }.toSet()
            val special = types - setOf("walk")
            val expected = when (id) {
                "valorant:bind" -> setOf("teleport")
                "valorant:lotus" -> setOf("drop", "door", "breakable")
                "cs2:nuke" -> setOf("stairs", "vent")
                else -> emptySet()
            }
            assertEquals("$id special links", expected, special)
            assertEquals("$id levels", id == "cs2:nuke", m.nodes.any { it.level != 0f })
            for (l in m.links.filter { it.oneWay }) assertTrue("$id: one-way ${l.type} usable backwards", m.out(l.to).none { (v, x) -> v == l.from && x === l })
            out.appendLine("%-16s %2d nodes %2d links %d sites; special: %s; links crossing the wall raster by design (vents, drops, teleports, the breakable wall): %d".format(id, n, m.links.size, m.sites.size, special.ifEmpty { setOf("none") }, crossing))
        }
        File("build/shooter-maps.txt").writeText(out.toString())
        println(out)
    }

    @Test
    fun closedLinksAndRecovery() {
        for ((id, m) in maps()) for (l in m.links) {
            // With one link closed, routes avoid it; when a route needs it there is none (no crash, no endless loop).
            for (s in m.sites) {
                val p = m.path(m.atk, s.node) { it === l }
                for (k in 1 until p.size) assertFalse("$id: closed link used", m.linkBetween(p[k - 1], p[k]) === l && m.links.count { x -> (x.from == p[k - 1] && x.to == p[k]) || (x.from == p[k] && x.to == p[k - 1]) } == 1)
                // Re-opened (the map state changed back): the route exists again.
                assertTrue("$id: no route after re-opening", m.path(m.atk, s.node).isNotEmpty())
            }
        }
    }

    @Test
    fun sight() {
        for ((id, m) in maps()) {
            // Every wall cell blocks a line through it between two open neighbours (the old 80-cell grid let thin walls through).
            val N = ShooterGrid.N
            var tested = 0
            for (y in 1 until N - 1) for (x in 1 until N - 1) {
                val c = { cx: Int, cy: Int -> ShooterGrid.blocked(m, (cx + 0.5f) / N, (cy + 0.5f) / N) }
                if (!c(x, y)) continue
                if (!c(x - 1, y) && !c(x + 1, y)) { assertFalse("$id: wall cell $x,$y see-through (horizontal)", ShooterGrid.clear(m, (x - 0.5f) / N, (y + 0.5f) / N, (x + 1.5f) / N, (y + 0.5f) / N)); tested++ }
                if (!c(x, y - 1) && !c(x, y + 1)) { assertFalse("$id: wall cell $x,$y see-through (vertical)", ShooterGrid.clear(m, (x + 0.5f) / N, (y - 0.5f) / N, (x + 0.5f) / N, (y + 1.5f) / N)); tested++ }
            }
            // Along a walkable link, the corridor is open.
            for (l in m.links.filter { walkable(it.type) }) { val a = m.nodes[l.from].pos; val b = m.nodes[l.to].pos; assertTrue("$id: no sight along ${m.nodes[l.from].name} → ${m.nodes[l.to].name}", ShooterGrid.clear(m, a.x, a.y, b.x, b.y)) }
            assertTrue("$id: thin walls tested", tested > 0)
        }
        // Floors: Nuke's A site (upper) and B site (lower) never see each other; Heaven overlooks A by its stairs.
        val nuke = Maps.shooter("cs2:nuke")!!
        val a = nuke.sites.first { it.id == "A" }.node; val b = nuke.sites.first { it.id == "B" }.node
        assertFalse(ShooterGrid.levelsSee(nuke, a, nuke.nodes[a].level, b, nuke.nodes[b].level))
        val heaven = nuke.nodes.indexOfFirst { it.name == "Heaven" }
        assertTrue(ShooterGrid.levelsSee(nuke, heaven, nuke.nodes[heaven].level, a, nuke.nodes[a].level))
        val bind = Maps.shooter("valorant:bind")!!
        assertTrue("one floor: sight decided by walls only", ShooterGrid.levelsSee(bind, 0, 0f, 5, 0f))
    }

    private fun round(t: dev.uem.em.titles.TitleDef, m: Maps.Shooter, seed: String): ShooterRound {
        val w = MobaBalanceTest.world()
        val rng = Rng.of(seed)
        val teams = w.teams.filter { it.titleId == t.id && it.active && it.leagueId >= 0 && it.parentId < 0 }.take(2)
        val ctx = teams.mapIndexed { s, team -> TeamContexts.build(w, team, dev.uem.em.sim.Lineups.auto(w, team), s, null, null, 0.0, rng) }
        val wpn = Weapons.of(t.id).last { !it.sniper }
        val host = object : ShooterRound.Host {
            override fun kill(ks: Int, killer: PlayerCtx, victim: PlayerCtx, assister: PlayerCtx?, headshot: Boolean, where: Int, damage: Double) {}
            override fun plant(p: PlayerCtx) {}
            override fun defuse(p: PlayerCtx) {}
            override fun utility(p: PlayerCtx) {}
            override fun damage(p: PlayerCtx, d: Double) {}
        }
        return ShooterRound(t, m, ctx, 0, rng, Array(2) { s -> Array(ctx[s].players.size) { wpn } }, Array(2) { IntArray(5) }, Array(2) { Array<MutableList<String>>(5) { ArrayList() } }, Array(2) { BooleanArray(5) }, 0, null, host)
    }

    @Test
    fun lotusMechanisms() {
        TestData.init(); Maps.init(TestData.source)
        val t = Catalogue.get("valorant")
        val m = Maps.shooter("valorant:lotus")!!
        val wall = m.links.first { it.type == "breakable" }
        val r = round(t, m, "lotus")
        val u = r.units[0]
        u.node = wall.from; u.x = m.nodes[wall.from].pos.x; u.y = m.nodes[wall.from].pos.y
        u.path = listOf(wall.from, wall.to); u.pathIdx = 0
        // The wall holds for BREAK_TIME, then it is open for the rest of the round.
        var ticks = 0
        while (u.node != wall.to && ticks < 100) { r.now += r.DT; r.step(u); ticks++ }
        assertTrue("went through", u.node == wall.to)
        assertTrue("broken first (${ticks * r.DT}s)", ticks * r.DT >= r.BREAK_TIME && wall in r.broken)
        // A route avoids an intact wall when there is another way.
        val r2 = round(t, m, "lotus2")
        assertTrue(r2.closed(wall))
        assertFalse(r2.closed(m.links.first { it.type == "walk" }))
        // Doors: the first unit operates the door, then it stays open for a while for everyone.
        val door = m.links.first { it.type == "door" }
        val v = r2.units[1]
        v.node = door.from; v.x = m.nodes[door.from].pos.x; v.y = m.nodes[door.from].pos.y; v.path = listOf(door.from, door.to); v.pathIdx = 0
        var k = 0
        while (v.pathIdx == 0 && k < 100 && v.x == m.nodes[door.from].pos.x && v.y == m.nodes[door.from].pos.y) { r2.now += r2.DT; r2.step(v); k++ }
        assertTrue("door operated (${k * r2.DT}s)", k * r2.DT >= r2.DOOR_TIME)
        assertTrue((r2.doorOpen[r2.doorNode(door)] ?: 0f) > r2.now)
    }

    @Test
    fun midLinkRerouteStaysOnTheGraph() {
        TestData.init(); Maps.init(TestData.source)
        for ((id, m) in maps()) {
            val t = Catalogue.get(id.substringBefore(':'))
            val r = round(t, m, "reroute|$id")
            val u = r.units[0]
            val l = m.links.first { it.type == "walk" }
            u.node = l.from; u.x = m.nodes[l.from].pos.x; u.y = m.nodes[l.from].pos.y; u.path = listOf(l.from, l.to); u.pathIdx = 0
            r.step(u) // part-way along the link
            for (target in m.sites.map { it.node } + m.def) {
                r.go(u, target)
                var guard = 0
                while (!r.atEnd(u) && guard++ < 400) {
                    r.now += r.DT; r.step(u)
                    // After every move the player is on the link being travelled (never off the graph, never across a wall).
                    if (!r.atEnd(u)) {
                        val a = m.nodes[u.path[u.pathIdx]].pos; val b = m.nodes[u.path[u.pathIdx + 1]].pos
                        assertTrue("$id: off the graph", r.segDist(u.x, u.y, a.x, a.y, b.x, b.y) < 1e-3f)
                    }
                }
                assertTrue("$id: never arrived", r.atEnd(u))
                assertEquals(target, u.node)
            }
        }
    }

    @Test
    fun fullGamesOnEveryMap() {
        val w = MobaBalanceTest.world()
        val out = StringBuilder()
        for (id in ids) {
            val t = Catalogue.get(id)
            val all = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { dev.uem.em.sim.Lineups.strength(w, it) }
            for ((mi, md) in t.maps.withIndex()) {
                val m = Maps.shooter(md.id)!!
                var plants = 0; var defuses = 0; var rounds = 0; var samples = 0; var cut = 0
                for (g in 0 until 2) {
                    fun game(rec: Recorder?): dev.uem.em.sim.GameOutcome {
                        val rng = Rng.of("maps|${md.id}|$g")
                        val pair = listOf(all[(mi + g) % 6], all[(mi + g + 3) % 6])
                        val ctx = pair.mapIndexed { s, team ->
                            val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                            val chars = HashMap<Int, String>(); val used = HashSet<String>()
                            if (t.characters.isNotEmpty()) lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }.ifEmpty { t.characters.filter { it.id !in used } }; val c = opts[rng.int(0, opts.size - 1)]; used.add(c.id); chars[pid] = c.id } }
                            TeamContexts.build(w, team, lineup, s, chars, md.id, 0.0, rng)
                        }
                        return TacticalEngine.simulate(t, ctx, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), g % 2, rng, Patch.of(w, t), rec, md.id)
                    }
                    val rec = Recorder()
                    val o = game(rec)
                    // Recording does not change the game (watched / background / replayed are the same result).
                    val plain = game(null)
                    assertEquals("${md.id}: recording changed the result", o.score.toList(), plain.score.toList())
                    assertEquals("${md.id}: recording changed the box score", o.book.lines.map { it.s.toList() }, plain.book.lines.map { it.s.toList() })
                    rounds += o.score.sum()
                    assertEquals(o.score.sum(), rec.events.count { it.kind == "roundEnd" })
                    for (e in rec.events) when (e.kind) {
                        "plant" -> { plants++; val x = MobaEngine.unpackX(e.n) / 1000f; val y = MobaEngine.unpackY(e.n) / 1000f; val s = m.sites[e.x]; assertTrue("${md.id}: plant off site ${s.id}", kotlin.math.hypot(x - s.pos.x, y - s.pos.y) <= s.r + 0.002f) }
                        "defuse" -> { defuses++; val x = MobaEngine.unpackX(e.n) / 1000f; val y = MobaEngine.unpackY(e.n) / 1000f; val s = m.sites[e.x]; assertTrue("${md.id}: defuse off site ${s.id}", kotlin.math.hypot(x - s.pos.x, y - s.pos.y) <= s.r + 0.002f) }
                    }
                    // Every position sample: on the map, in no wall; between two samples a second apart the viewer's straight
                    // line is counted when it crosses a wall corner (the viewer follows the graph for those, see ShooterView2).
                    val np = rec.pids.size
                    val stride = np + 1
                    val ns = rec.pos.size / stride
                    for (k in 0 until ns) for (i in 0 until np) {
                        val v = rec.pos[k * stride + i]
                        val x = MobaEngine.unitX(v) / 1000f; val y = MobaEngine.unitY(v) / 1000f
                        samples++
                        assertTrue("${md.id}: position off the map", x in 0f..1f && y in 0f..1f)
                        if (MobaEngine.unitFlags(v) and 1 == 0) assertFalse("${md.id}: a player inside a wall at ${x},${y}", ShooterGrid.blocked(m, x, y))
                        if (k + 1 < ns && rec.posT[k + 1] - rec.posT[k] <= 2) {
                            val v2 = rec.pos[(k + 1) * stride + i]
                            if (!ShooterGrid.clear(m, x, y, MobaEngine.unitX(v2) / 1000f, MobaEngine.unitY(v2) / 1000f)) cut++
                        }
                    }
                }
                out.appendLine("%-16s rounds %3d  plants %3d  defuses %2d  samples %6d  straight-line moves crossing a corner %d (%.2f%%)".format(md.id, rounds, plants, defuses, samples, cut, cut * 100.0 / samples))
            }
        }
        File("build/shooter-games.txt").writeText(out.toString())
        println(out)
    }
}

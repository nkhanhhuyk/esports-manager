package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.MapForge
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.Recorder
import dev.uem.em.sim.ShooterGrid
import dev.uem.em.sim.TacticalEngine
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue
import dev.uem.em.ui.Maps
import dev.uem.em.game.BoxStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Fictional maps (released by major patches): every generated layout passes the checks the real maps pass
 * (ShooterMapsTest), is the same for the same seed, and plays: plants and defuses on the sites, nobody inside a
 * wall, and neither side winning almost every round.
 */
class MapForgeTest {
    private val allowed = setOf("walk", "door", "breakable", "drop", "teleport", "vent")

    private fun forge(titleId: String, k: Int) = MapForge.generate(titleId, Rng.of("forge|$titleId|$k"), emptySet(), 2027, "test")

    @Test
    fun layoutsPassTheMapChecks() {
        TestData.init(); Maps.init(TestData.source)
        val out = StringBuilder()
        for (titleId in listOf("valorant", "cs2")) {
            val sites = IntArray(5); val feats = HashMap<String, Int>()
            for (k in 0 until 40) {
                val cm = forge(titleId, k)
                assertNotNull("$titleId #$k: no layout (${MapForge.rejects})", cm); cm!!
                val again = forge(titleId, k)!!
                assertEquals("same seed, same map", cm.links, again.links)
                assertEquals(cm.nodes.map { "${it.x},${it.y}" }, again.nodes.map { "${it.x},${it.y}" })
                val m = MapForge.compile(cm)
                assertTrue("${cm.name}: invalid", MapForge.valid(m))
                assertTrue("${cm.name}: 2-4 sites", m.sites.size in 2..4)
                assertEquals("${cm.name}: sites lettered A..", "ABCD".take(m.sites.size).map { it.toString() }, m.sites.map { it.id })
                for (l in m.links) assertTrue("${cm.name}: link type ${l.type}", l.type in allowed)
                for (u in m.nodes.indices) {
                    for (s in m.sites) assertTrue(m.path(u, s.node).isNotEmpty())
                    assertTrue(m.path(u, m.atk).isNotEmpty() && m.path(u, m.def).isNotEmpty())
                }
                for (s in m.sites) assertFalse(ShooterGrid.blocked(m, s.pos.x, s.pos.y))
                for (l in m.links.filter { it.oneWay }) assertTrue(m.out(l.to).none { (v, x) -> v == l.from && x === l })
                if (titleId == "valorant") { assertTrue("${cm.name}: staples", cm.staples.size >= 4); assertTrue("${cm.name}: weak agents", cm.weak.size >= 3); assertTrue(cm.staples.intersect(cm.weak.toSet()).isEmpty()) }
                sites[m.sites.size]++
                for (f in m.links.map { it.type }.toSet() - "walk") feats[f] = (feats[f] ?: 0) + 1
                if (k < 3) out.appendLine("${cm.id}: ${cm.blurb} ${cm.features.joinToString("; ")} | staples ${cm.staples} weak ${cm.weak} flex ${cm.flex}")
            }
            out.appendLine("$titleId: sites 2/3/4 = ${sites[2]}/${sites[3]}/${sites[4]}; features $feats")
        }
        out.appendLine("rejected layouts: ${MapForge.rejects}")
        File("build/map-forge.txt").writeText(out.toString())
        println(out)
    }

    @Test
    fun fictionalMapsPlay() {
        val w = MobaBalanceTest.world()
        val out = StringBuilder()
        var atkWins = 0; var allRounds = 0
        val perTitle = LinkedHashMap<String, IntArray>()
        for (titleId in listOf("valorant", "cs2")) {
            val t = Catalogue.get(titleId)
            val all = w.teams.filter { it.titleId == titleId && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { dev.uem.em.sim.Lineups.strength(w, it) }
            val maps = (0 until 10).map { forge(titleId, it)!! }
            Maps.setCustom(maps.associate { it.id to MapForge.compile(it) })
            for ((mi, cm) in maps.withIndex()) {
                val m = Maps.shooter(cm.id)!!
                var rounds = 0; var atk = 0; var plants = 0
                for (g in 0 until 3) {
                    val rng = Rng.of("forgeplay|${cm.id}|$g")
                    val pair = listOf(all[(mi + g) % 8], all[(mi + g + 4) % 8])
                    val ctx = pair.mapIndexed { s, team ->
                        val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                        val chars = HashMap<Int, String>(); val used = HashSet<String>()
                        if (t.characters.isNotEmpty()) lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }.ifEmpty { t.characters.filter { it.id !in used } }; val c = opts[rng.int(0, opts.size - 1)]; used.add(c.id); chars[pid] = c.id } }
                        TeamContexts.build(w, team, lineup, s, chars, cm.id, 0.0, rng)
                    }
                    val rec = Recorder()
                    val o = TacticalEngine.simulate(t, ctx, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), g % 2, rng, Patch.of(w, t), rec, cm.id)
                    rounds += o.score.sum()
                    val log = dev.uem.em.sim.ShooterEconomy.EconLog.parse(o.econ)!!
                    for (r in log.rounds) if (r.winner >= 0 && r.winner == r.attack) atk++
                    for (e in rec.events) if (e.kind == "plant" || e.kind == "defuse") {
                        if (e.kind == "plant") plants++
                        val x = MobaEngine.unpackX(e.n) / 1000f; val y = MobaEngine.unpackY(e.n) / 1000f; val s = m.sites[e.x]
                        assertTrue("${cm.name}: ${e.kind} off site ${s.id}", kotlin.math.hypot(x - s.pos.x, y - s.pos.y) <= s.r + 0.002f)
                    }
                    val np = rec.pids.size; val stride = np + 1
                    for (k in 0 until rec.pos.size / stride) for (i in 0 until np) {
                        val v = rec.pos[k * stride + i]
                        if (MobaEngine.unitFlags(v) and 1 == 0) assertFalse("${cm.name}: a player inside a wall", ShooterGrid.blocked(m, MobaEngine.unitX(v) / 1000f, MobaEngine.unitY(v) / 1000f))
                    }
                }
                atkWins += atk; allRounds += rounds
                perTitle.getOrPut(titleId) { IntArray(2) }.let { it[0] += atk; it[1] += rounds }
                out.appendLine("%-26s sites %d  rounds %3d  attackers win %4.1f%%  plants %3d  %s".format(cm.id, m.sites.size, rounds, atk * 100.0 / rounds, plants, (m.links.map { it.type }.toSet() - "walk").joinToString(",")))
                assertTrue("${cm.name}: attackers win ${atk * 100 / rounds}% of rounds", atk * 100 / rounds in 15..85)
            }
        }
        out.appendLine("all fictional maps: attackers win %.1f%% of %d rounds".format(atkWins * 100.0 / allRounds, allRounds))
        for ((tid, v) in perTitle) out.appendLine("$tid: attackers win %.1f%% of %d rounds".format(v[0] * 100.0 / v[1], v[1]))
        File("build/map-forge-games.txt").writeText(out.toString())
        println(out)
        Maps.setCustom(emptyMap())
        for ((tid, v) in perTitle) assertTrue("$tid: attackers win ${v[0] * 100 / v[1]}% on fictional maps", v[0] * 100 / v[1] in 38..62)
    }
}

/** Map pools across many major patches: size kept, every map resolvable and playable, the log consistent, saves keep it. */
class MapPoolsTest {
    @Test
    fun poolsRotateOverManySeasons() {
        TestData.init(); BoxStore.clear()
        val w = dev.uem.em.gen.WorldGen.create(TestData.source, "pools", "P", dev.uem.em.core.Dates.day(2026, 11, 21))
        Catalogue.applyCustom(w)
        val sizes = listOf("valorant", "cs2").associateWith { Catalogue.get(it).maps.size }
        val notes = ArrayList<String>()
        for (k in 0 until 14) {
            for (tid in listOf("valorant", "cs2")) notes.addAll(dev.uem.em.mgmt.MapPools.patch(w, Catalogue.get(tid), "${Catalogue.get(tid).short} ${w.season % 100}.0", Rng.of("pooltest|$tid|$k")))
            w.season++
            dev.uem.em.mgmt.MapPools.rollover(w)
            for (tid in listOf("valorant", "cs2")) {
                val t = Catalogue.get(tid)
                assertEquals("$tid pool size in ${w.season}", sizes[tid], t.maps.size)
                assertEquals("$tid: no map twice", t.maps.size, t.maps.map { it.id }.toSet().size)
                for (m in t.maps) { assertNotNull("$tid: ${m.id} has no layout", Maps.shooter(m.id)); assertFalse("$tid: ${m.id} has no name", m.name.contains(':')) }
            }
        }
        assertTrue("no map ever rotated", w.mapPoolLog.isNotEmpty())
        assertTrue("no fictional map released", w.customMaps.isNotEmpty())
        assertTrue("every change applied", w.mapPoolLog.all { it.applied })
        assertEquals("names unique", w.customMaps.size, w.customMaps.map { it.name }.toSet().size)
        for (cm in w.customMaps) assertTrue("${cm.name} invalid", MapForge.valid(MapForge.compile(cm)))
        // A save keeps the fictional maps and the pools exactly.
        val bytes = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(dev.uem.em.model.World.serializer(), w)
        val back = kotlinx.serialization.protobuf.ProtoBuf.decodeFromByteArray(dev.uem.em.model.World.serializer(), bytes)
        assertEquals(w.mapPools, back.mapPools)
        assertEquals(w.customMaps.map { it.id + it.links.joinToString() }, back.customMaps.map { it.id + it.links.joinToString() })
        Catalogue.applyCustom(back)
        assertEquals(Catalogue.get("valorant").maps.map { it.id }, w.mapPools["valorant"]!!.split(','))
        File("build/map-pools.txt").writeText(notes.joinToString("\n") + "\n\n" + w.mapPoolLog.joinToString("\n") { "${it.titleId} ${it.season}: +${it.added} -${it.removed} (from ${it.effective})" })
        // Leave the shared catalogue as other tests expect it.
        Catalogue.applyCustom(MobaBalanceTest.world())
    }
}

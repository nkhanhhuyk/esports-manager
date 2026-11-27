package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.MapForge
import dev.uem.em.ui.Maps
import org.junit.Assume.assumeTrue
import org.junit.Test
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.Patch
import dev.uem.em.sim.TacticalEngine
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue

/** Geometry of real and generated maps side by side (opt-in: UEM_FORGEPROBE=1). */
class MapForgeProbeTest {
    @Test
    fun compare() {
        assumeTrue(System.getenv("UEM_FORGEPROBE") == "1")
        TestData.init(); Maps.init(TestData.source)
        fun row(id: String, m: Maps.Shooter): String {
            val tr = MapForge.traits(m)
            val ratios = m.sites.map { s -> MapForge.pathLen(m, m.def, s.node) / MapForge.pathLen(m, m.atk, s.node) }
            val entries = m.sites.map { s -> m.links.count { it.from == s.node || it.to == s.node } }
            return "%-24s sites %d  rd/ra %s  atk %.2f def %.2f  rot %.2f  long %.2f  site links %s".format(id, m.sites.size, ratios.joinToString(" ") { "%.2f".format(it) }, tr.atkToSite, tr.defToSite, tr.rotation, tr.longest, entries)
        }
        for (id in Maps.shooterIds()) println(row(id, Maps.shooter(id)!!))
        for (t in listOf("valorant", "cs2")) for (k in 0 until 10) { val cm = MapForge.generate(t, Rng.of("forge|$t|$k"), emptySet(), 2027, "x")!!; println(row(cm.id, MapForge.compile(cm))) }
    }

    @Test
    fun balance() {
        assumeTrue(System.getenv("UEM_FORGEPROBE") == "2")
        val w = MobaBalanceTest.world()
        val out = StringBuilder("title,id,sites,ratio,atk,def,rot,long,siteLinks,specials,rounds,atkWins\n")
        val n = (System.getenv("UEM_FORGEN") ?: "40").toInt()
        for (titleId in listOf("valorant", "cs2")) {
            val t = Catalogue.get(titleId)
            val all = w.teams.filter { it.titleId == titleId && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { dev.uem.em.sim.Lineups.strength(w, it) }
            val maps = (0 until n).map { MapForge.generate(titleId, Rng.of("fp|$titleId|$it"), emptySet(), 2027, "x")!! } 
            Maps.setCustom(maps.associate { it.id + "#" + maps.indexOf(it) to MapForge.compile(it) })
            for ((mi, cm) in maps.withIndex()) {
                val id = cm.id + "#" + mi
                val m = Maps.shooter(id)!!
                var rounds = 0; var atk = 0
                for (g in 0 until 4) {
                    val rng = Rng.of("fpp|$id|$g")
                    val pair = listOf(all[(mi + g) % 8], all[(mi + g + 4) % 8])
                    val ctx = pair.mapIndexed { s, team ->
                        val lineup = dev.uem.em.sim.Lineups.auto(w, team)
                        val chars = HashMap<Int, String>(); val used = HashSet<String>()
                        if (t.characters.isNotEmpty()) lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }.ifEmpty { t.characters.filter { it.id !in used } }; val c = opts[rng.int(0, opts.size - 1)]; used.add(c.id); chars[pid] = c.id } }
                        TeamContexts.build(w, team, lineup, s, chars, id, 0.0, rng)
                    }
                    val o = TacticalEngine.simulate(t, ctx, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), g % 2, rng, Patch.of(w, t), null, id)
                    val log = dev.uem.em.sim.ShooterEconomy.EconLog.parse(o.econ)!!
                    for (r in log.rounds) if (r.winner >= 0) { rounds++; if (r.winner == r.attack) atk++ }
                }
                val tr = MapForge.traits(m)
                val ratio = m.sites.map { s -> MapForge.pathLen(m, m.def, s.node) / MapForge.pathLen(m, m.atk, s.node) }.average()
                val sl = m.sites.map { s -> m.links.count { it.from == s.node || it.to == s.node } }.average()
                out.append("$titleId,${cm.id},${m.sites.size},%.3f,%.3f,%.3f,%.3f,%.3f,%.2f,${tr.specials.sorted().joinToString("+")},$rounds,$atk\n".format(ratio, tr.atkToSite, tr.defToSite, tr.rotation, tr.longest, sl))
            }
        }
        java.io.File("build/forge-balance.csv").writeText(out.toString())
        Maps.setCustom(emptyMap())
    }
}

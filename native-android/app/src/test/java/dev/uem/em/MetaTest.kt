package dev.uem.em

import dev.uem.em.core.Rng
import dev.uem.em.sim.CompEval
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.Recorder
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.RoleMeta
import dev.uem.em.titles.Spells
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Role metas (patch-driven: who the carry is and how positions play), no wards in MLBB / HoK, spell patches. */
class MetaTest {
    private fun play(titleId: String, i: Int, rec: Recorder?): List<dev.uem.em.sim.TeamCtx> {
        val w = MobaBalanceTest.world()
        val t = Catalogue.get(titleId)
        val teams = w.teams.filter { it.titleId == titleId && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }
        val rng = Rng.of("meta|$titleId|$i")
        val a = teams[i % teams.size]; val b = teams[(i * 3 + 1) % teams.size].let { if (it == a) teams[(i + 2) % teams.size] else it }
        val ctxs = listOf(a, b).mapIndexed { side, team ->
            val lineup = dev.uem.em.sim.Lineups.auto(w, team)
            val chars = HashMap<Int, String>(); val used = HashSet<String>()
            lineup.forEachIndexed { k, pid -> if (pid >= 0) { val opts = t.characters.filter { it.fits(t.lineupRoles[k]) && it.id !in used }; val c = opts[(i * 7 + k * 3 + side) % opts.size]; used.add(c.id); chars[pid] = c.id } }
            TeamContexts.build(w, team, lineup, side, chars, null, 0.0, rng)
        }
        MobaEngine.simulate(t, ctxs, arrayOf(CompEval.NEUTRAL, CompEval.NEUTRAL), rng, Patch.of(w, t), rec, MobaEngine.Opts(world = w))
        return ctxs
    }

    @Test
    fun metasShiftAndDecideWhoTakesTheKills() {
        val w = MobaBalanceTest.world()
        val old = w.roleMetas["lol"]
        try {
            w.roleMetas.remove("lol")
            assertEquals(6, RoleMeta.current(w, "lol").size)
            assertEquals("bot-carry", RoleMeta.current(w, "lol")[0])
            assertEquals("Gold lane carry meta", RoleMeta.label("mlbb", "bot-carry"))
            assertEquals("Farm lane carry meta", RoleMeta.label("hok", "bot-carry"))
            // Kill priority: the carry first, the support last; a mid-carry meta puts mid first.
            val base = RoleMeta.killPriority(w, "lol")
            assertTrue(base[RoleMeta.BOT]!! > base[RoleMeta.MID]!! && base[RoleMeta.MID]!! > base[RoleMeta.SUPPORT]!!)
            // Patches shift the meta and write it in the notes.
            var notes = emptyList<String>(); var k = 0
            while (notes.isEmpty() && k < 40) notes = RoleMeta.shift(w, Catalogue.get("lol"), "major", Rng.of("shift${k++}"))
            assertTrue("a major patch moves the meta", notes.isNotEmpty() && notes.all { it.startsWith("Meta — ") })
            assertTrue(RoleMeta.current(w, "lol") != RoleMeta.current(null, "lol"))

            // In the match engine: kills by position in a bot-carry meta, then in a mid-carry meta.
            fun killsByPos(meta: String?): Map<String, Double> {
                if (meta == null) w.roleMetas.remove("lol") else w.roleMetas["lol"] = meta
                val out = HashMap<String, Double>()
                repeat(30) { i ->
                    val rec = Recorder()
                    val ctxs = play("lol", i, rec)
                    val roleOf = ctxs.flatMap { c -> c.players.map { it.p.id to it.roleId } }.toMap()
                    for (e in rec.events) if (e.kind == "kill" && e.a >= 0) roleOf[e.a]?.let { out.merge(RoleMeta.pos(it), 1.0, Double::plus) }
                }
                return out
            }
            val botMeta = killsByPos(null)
            val midMeta = killsByPos("mid-carry,jungle-control,support-engage,mid-control,top-tank,objective-control")
            val sb = "bot-carry meta: $botMeta\nmid-carry meta: $midMeta"
            println(sb); java.io.File("build/meta.txt").writeText(sb)
            val r0 = botMeta[RoleMeta.MID]!! / botMeta[RoleMeta.BOT]!!
            val r1 = midMeta[RoleMeta.MID]!! / midMeta[RoleMeta.BOT]!!
            assertTrue("a mid-carry meta gives mid more of the kills ($r0 → $r1)", r1 > r0 * 1.15)
            assertTrue("supports take the fewest kills", botMeta.values.minOrNull() == botMeta[RoleMeta.SUPPORT])
        } finally {
            if (old == null) w.roleMetas.remove("lol") else w.roleMetas["lol"] = old
        }
    }

    @Test
    fun noWardsInMlbbAndHok() {
        for ((id, wards) in listOf("mlbb" to false, "hok" to false, "lol" to true, "dota2" to true)) {
            val rec = Recorder()
            play(id, 1, rec)
            val n = rec.events.count { it.kind == "ward" }
            assertTrue("$id wards: $n", if (wards) n > 0 else n == 0)
        }
    }

    @Test
    fun spellsArePatched() {
        val w = MobaBalanceTest.world()
        val t = Catalogue.get("mlbb")
        val before = Spells.all(w, "mlbb").size
        val note = dev.uem.em.mgmt.Release.spell(w, t, Rng.of("new-spell"))
        assertTrue(note != null && note.startsWith("New spell"))
        assertEquals(before + 1, Spells.all(w, "mlbb").size)
        val added = Spells.all(w, "mlbb").last()
        assertTrue(added.custom && added.cd > 0)
        // MLBB and HoK players choose one spell (Recall and Healing / Recovery are fixed), LoL and Wild Rift two.
        assertEquals(1, Spells.slots("mlbb")); assertEquals(1, Spells.slots("hok")); assertEquals(2, Spells.slots("lol")); assertEquals(2, Spells.slots("wildrift"))
        // Junglers take the jungle spell; a League laner takes Flash.
        val rng = Rng.of("choose")
        assertTrue(Spells.choose(w, "lol", true, -1, false, false, "fighter", rng).any { it.effect == "smite" })
        assertTrue(Spells.choose(w, "lol", false, 1, false, false, "mage", rng).any { it.effect == "blink" })
        assertTrue(Spells.choose(w, "mlbb", true, -1, false, false, "assassin", rng).single().effect == "smite")
        // A removed spell is no longer chosen.
        w.removedSpells.add("lol-ignite")
        try { repeat(40) { i -> assertTrue(Spells.choose(w, "lol", false, 0, false, false, "fighter", Rng.of("rm$i")).none { it.id == "lol-ignite" }) } }
        finally { w.removedSpells.remove("lol-ignite"); w.customSpells.removeIf { it.titleId == "mlbb" && it.id == added.id } }
    }
}

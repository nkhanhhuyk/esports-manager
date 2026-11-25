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
            assertEquals(7, RoleMeta.current(w, "lol").size)
            assertEquals("bot-marksman", RoleMeta.bot(w, "lol"))
            assertEquals("Bot lane mage APC meta", RoleMeta.label("lol", "bot-mage"))
            assertEquals("Gold lane mage APC meta", RoleMeta.label("mlbb", "bot-mage"))
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

    /** The bot-lane meta decides what the bot-side carry drafts: marksmen normally, mages in a mage APC meta. */
    @Test
    fun botLaneMetaDrivesTheDraft() {
        val w = MobaBalanceTest.world()
        val sb = StringBuilder()
        for (id in listOf("lol", "mlbb", "wildrift", "hok")) {
            val t = Catalogue.get(id)
            val patch = Patch.of(w, t)
            val botIdx = t.lineupRoles.indexOfFirst { RoleMeta.pos(it.id) == RoleMeta.BOT }
            val teams = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }.take(12)
            fun share(meta: String, rx: Regex): Double {
                var n = 0; var hit = 0
                for ((i, a) in teams.withIndex()) {
                    val b = teams[(i + 5) % teams.size]
                    val lu = arrayOf(dev.uem.em.sim.Lineups.ensure(w, a).map { w.players[it] }, dev.uem.em.sim.Lineups.ensure(w, b).map { w.players[it] })
                    val r = dev.uem.em.sim.DraftSession(t, patch, lu, arrayOf(a.style, b.style), 0, arrayOf(emptySet(), emptySet()), Rng.of("bot|$id|$meta|$i"), botMeta = meta).runAll()
                    for (side in 0..1) {
                        val c = r.assign[side][lu[side][botIdx].id]?.let { t.charIndex[it] } ?: continue
                        // A mage that is not also a marksman (Ezreal, Varus and the like count as marksmen).
                        n++; if (rx.containsMatchIn(c.cls) && !(c.cls + c.classes.joinToString()).contains("marksman", ignoreCase = true)) hit++
                    }
                }
                return hit.toDouble() / n.coerceAtLeast(1)
            }
            val mage = Regex("mage", RegexOption.IGNORE_CASE)
            val normal = share("bot-marksman", mage); val apc = share("bot-mage", mage)
            sb.appendLine("$id: mages in the bot slot ${"%.0f".format(normal * 100)}% (marksman meta) → ${"%.0f".format(apc * 100)}% (mage APC meta)")
            // Not every bot laner switches: comfort, bans and counters keep some marksmen.
            assertTrue("$id: a mage APC meta drafts mages bot ($normal → $apc)", apc >= 0.3 && apc > normal + 0.2 && apc <= 0.9)
        }
        println(sb); java.io.File("build/bot-meta.txt").writeText(sb.toString())
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

package dev.uem.em.mgmt

import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.data.GameData
import dev.uem.em.model.ScrimBlock
import dev.uem.em.model.ScrimStat
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Draft
import dev.uem.em.sim.Lineups
import dev.uem.em.sim.Patch
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.TitleDef
import kotlin.math.exp

/**
 * Scrims: every week each team plays its scrim blocks (Team.scrims, 0 to 6) against partners of its
 * region and level, with a quick practice model (no replays). Teams try their comfort and meta
 * picks and test new ones; the results build a record per character (or map) for the current
 * patch, so a team works out what works for it. That record moves its real drafts toward what
 * worked (and away from what failed), players get practice on what they played, and both sides
 * see what the other likes to pick. A new patch halves the old record.
 */
object Scrims {
    private const val KEEP = 12

    /** Scrim partners on the same servers: the macro region of a team's league region. */
    private fun server(team: Team): String = GameData.expand(team.region).firstOrNull()?.let { GameData.regions[it]?.macro } ?: team.region

    fun weekly(w: World) {
        for (id in w.titles) {
            val t = Catalogue.get(id)
            val patch = Patch.of(w, t)
            val teams = w.teams.filter { it.active && it.titleId == id && it.nationalOf.isEmpty() && it.scrims > 0 && it.lineup.count { p -> p >= 0 } >= t.teamSize }
            if (teams.size < 2) continue
            val rng = Rng.derive(w.seed, "scrims", id, w.day)
            val strength = HashMap<Int, Double>()
            for ((_, pool) in teams.groupBy { server(it) }) {
                val rounds = pool.maxOf { it.scrims.coerceIn(0, 6) }
                for (k in 0 until rounds) {
                    // Random keys drawn once per team (a comparator must not change between comparisons).
                    val want = pool.filter { it.scrims > k }.map { it to it.elo + rng.normal(0.0, 70.0) }.sortedBy { it.second }.map { it.first }.toMutableList()
                    while (want.size >= 2) {
                        val a = want.removeAt(0)
                        val b = want.removeAt(rng.int(0, minOf(2, want.size - 1)))
                        block(w, t, patch, a, b, rng, strength)
                    }
                }
            }
        }
    }

    /** Draft weight of a character (or map) from a team's scrims on this patch: -0.5 (failed) .. +0.5 (works), by how sure it is. */
    fun bonusOf(st: ScrimStat?): Double {
        if (st == null || st.g == 0) return 0.0
        val rate = (st.w + 1.0) / (st.g + 2.0) - 0.5
        return rate * st.g / (st.g + 4.0)
    }

    /** What a team's scrims say about every character it tried on this patch (for its drafts). */
    fun bonus(w: World, team: Team): Map<String, Double> {
        val t = Catalogue.get(team.titleId)
        if (team.scrimPatch != Patch.of(w, t).n || team.scrimStats.isEmpty()) return emptyMap()
        return team.scrimStats.mapValues { bonusOf(it.value) }
    }

    private fun stat(team: Team, key: String) = team.scrimStats.getOrPut(key) { ScrimStat() }

    private fun rollPatch(team: Team, n: Int) {
        if (team.scrimPatch == n) return
        // A new patch: what worked before is only half known now.
        val it = team.scrimStats.entries.iterator()
        while (it.hasNext()) { val e = it.next(); e.value.g /= 2; e.value.w /= 2; if (e.value.g == 0) it.remove() }
        team.scrimPatch = n
    }

    /** Characters a team plays in one scrim game: comfort and meta, what worked in scrims, and a test pick now and then. */
    private fun picks(w: World, t: TitleDef, patch: Patch, team: Team, rng: Rng, taken: Set<String>): List<String> {
        if (t.characters.isEmpty()) return emptyList()
        val used = HashSet(taken)
        val out = ArrayList<String>()
        team.lineup.forEachIndexed { i, pid ->
            if (pid < 0 || i >= t.lineupRoles.size) return@forEachIndexed
            val p = w.players[pid]
            val cands = Draft.prefs(t, patch, p, i, team.style).filter { it.id !in used }.take(7)
            if (cands.isEmpty()) return@forEachIndexed
            val c = if (rng.chance(0.22)) rng.pick(cands) // trying something new
            else cands.withIndex().maxBy { (k, c) -> (7 - k) / 7.0 + bonusOf(team.scrimStats[c.id]) * 2.5 + rng.next() * 0.35 }.value
            used.add(c.id); out.add(c.id)
        }
        return out
    }

    /** A side's strength in one scrim game: its players, what they picked and how well they know it. */
    private fun power(w: World, t: TitleDef, patch: Patch, team: Team, picks: List<String>, map: String, base: Double): Double {
        var s = base
        val ids = team.lineup.filter { it >= 0 }
        if (picks.isNotEmpty()) {
            var q = 0.0
            picks.forEachIndexed { i, c ->
                val p = ids.getOrNull(i)?.let { w.players[it] }
                q += (patch.charMeta[c] ?: 0.0) * 0.22 + (((p?.profChar?.get(c) ?: 30.0) - 60.0) / 10.0) * 0.8
            }
            s += q / picks.size
        }
        if (map.isNotEmpty()) s += (ids.map { w.players[it].profMap[map] ?: 40.0 }.average() - 50.0) * 0.08
        return s + (team.chemistry - 60.0) * 0.03
    }

    private fun block(w: World, t: TitleDef, patch: Patch, a: Team, b: Team, rng: Rng, strength: HashMap<Int, Double>) {
        rollPatch(a, patch.n); rollPatch(b, patch.n)
        val sa = strength.getOrPut(a.id) { Lineups.strength(w, a) }
        val sb = strength.getOrPut(b.id) { Lineups.strength(w, b) }
        val games = if (t.isMoba) 3 else 2
        var wa = 0; var wb = 0
        val keep = w.isUserTeam(a.id) || w.isUserTeam(b.id) || a.id in w.followed || b.id in w.followed
        val pa = ArrayList<String>(); val pb = ArrayList<String>(); val maps = ArrayList<String>(); val res = StringBuilder()
        repeat(games) {
            val ca = picks(w, t, patch, a, rng, emptySet())
            val cb = picks(w, t, patch, b, rng, if (t.draft.mode == "sequential") ca.toSet() else emptySet())
            val map = if (!t.isMoba && t.maps.isNotEmpty()) rng.pick(t.maps).id else ""
            val d = power(w, t, patch, a, ca, map, sa) - power(w, t, patch, b, cb, map, sb)
            val aWins = rng.chance(1.0 / (1.0 + exp(-d / 3.2)))
            if (aWins) wa++ else wb++
            res.append(if (aWins) 'W' else 'L')
            for (c in ca) stat(a, c).let { it.g++; if (aWins) it.w++ }
            for (c in cb) stat(b, c).let { it.g++; if (!aWins) it.w++ }
            if (map.isNotEmpty()) { stat(a, "map:$map").let { it.g++; if (aWins) it.w++ }; stat(b, "map:$map").let { it.g++; if (!aWins) it.w++ } }
            // Practice on what they played.
            for ((team, cs) in listOf(a to ca, b to cb)) team.lineup.filter { it >= 0 }.forEachIndexed { i, pid ->
                val p = w.players[pid]
                cs.getOrNull(i)?.let { c -> p.profChar[c] = clamp((p.profChar[c] ?: 30.0) + 0.12, 0.0, 95.0) }
                if (map.isNotEmpty()) p.profMap[map] = clamp((p.profMap[map] ?: 40.0) + 0.1, 0.0, 95.0)
            }
            if (keep) { pa.add(ca.joinToString(",")); pb.add(cb.joinToString(",")); if (map.isNotEmpty()) maps.add(map) }
        }
        val r = res.toString()
        a.scrimLog.add(ScrimBlock(w.day, b.id, wa, wb, pa, pb, maps, r))
        b.scrimLog.add(ScrimBlock(w.day, a.id, wb, wa, pb, pa, maps, r.map { if (it == 'W') 'L' else 'W' }.joinToString("")))
        while (a.scrimLog.size > KEEP) a.scrimLog.removeAt(0)
        while (b.scrimLog.size > KEEP) b.scrimLog.removeAt(0)
    }

    /** What a team saw its scrim partners pick (blocks with picks kept: your and followed teams). */
    fun seen(w: World, team: Team): List<Pair<String, Int>> {
        val n = HashMap<String, Int>()
        for (bl in team.scrimLog) for (g in bl.oppPicks) for (c in g.split(',')) if (c.isNotBlank()) n[c] = (n[c] ?: 0) + 1
        return n.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}

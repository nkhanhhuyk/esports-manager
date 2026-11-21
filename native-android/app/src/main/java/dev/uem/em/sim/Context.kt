package dev.uem.em.sim

import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.model.Line
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.titles.CharDef
import dev.uem.em.titles.Phase
import dev.uem.em.titles.RoleDef
import dev.uem.em.titles.TitleDef
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** A player in one game: ratings by phase after condition, form and day-to-day noise. */
class PlayerCtx(val p: Player, val role: RoleDef, val charId: String?, val char: CharDef?, val base: Ratings.Base, val mult: Double, val phase: DoubleArray) {
    val roleId get() = role.id
    // Fight weightings depend only on position and character: worked out once per game.
    val dmgW: Double
    val tankW: Double
    val healW: Double

    init {
        var d = 1.0
        if (role.isCarry) d *= 1.45
        if (role.isSupport) d *= 0.6
        if (role.isAnchor) d *= 0.95
        if (char?.dmgClass == true) d *= 1.2
        if (char?.tankyClass == true) d *= 0.7
        dmgW = d
        var t = 1.0
        if (role.isAnchor || role.isEntry) t *= 1.35
        if (role.isCarry) t *= 0.85
        if (char?.frontClass == true) t *= 1.6
        tankW = t
        healW = if (char?.healClass == true) 1.0 else if (role.isSupport) 0.35 else 0.03
    }

    fun ph(p: Phase) = phase[p.ordinal]
}

class TeamCtx(val team: Team, val idx: Int, val players: List<PlayerCtx>, val phase: DoubleArray, val overall: Double, val aggression: Double) {
    fun ph(p: Phase) = phase[p.ordinal]
}

/** Draft composition quality (as in the web version). */
class CompEval(val total: Double, val counter: Double, val profile: Map<String, Double>) {
    fun prof(k: String, alt: String? = null) = profile[k] ?: alt?.let { profile[it] } ?: 5.0

    companion object {
        val NEUTRAL = CompEval(0.0, 0.0, emptyMap())
    }
}

/**
 * The balance patch of a title in a season: character meta strength (−10..10) and style weights,
 * derived from the world seed (stage 1 of the native app: one patch per title per season).
 */
class Patch(val charMeta: Map<String, Double>, val styles: Map<String, Double>, val obj: Map<String, Double> = emptyMap(), val seedKey: String = "", val n: Int = 0) {
    fun objValue(id: String) = obj[id] ?: 1.0
    fun objRespawn(id: String) = obj["$id@respawn"] ?: 1.0

    companion object {
        private val cache = ConcurrentHashMap<String, Patch>()

        /** The title's current balance state: base meta plus every patch so far (see mgmt.Patches). */
        fun of(w: World, t: TitleDef): Patch {
            val n = w.patches.count { it.titleId == t.id }
            if (cache.size > 256) cache.clear()
            return cache.getOrPut("${System.identityHashCode(w)}|${w.seed}|${t.id}|$n") {
                val styles = HashMap<String, Double>()
                for (s in t.styles) styles[s] = 1.0
                val obj = HashMap<String, Double>()
                for (p in w.patches) if (p.titleId == t.id) {
                    for ((k, v) in p.styleDelta) styles[k] = clamp((styles[k] ?: 1.0) + v, 0.7, 1.3)
                    for ((k, v) in p.objMods) obj[k] = if (k == "defender") (obj[k] ?: 0.0) + v else clamp((obj[k] ?: 1.0) * v, 0.5, 2.0)
                }
                Patch(dev.uem.em.mgmt.PatchState.metaOf(w, t), styles, obj, w.seed, n)
            }
        }
    }
}

object TeamContexts {
    private const val TEAM_FORM_SD = 0.03
    private val TEAM_PHASES = setOf(Phase.TEAMFIGHT, Phase.UTILITY, Phase.SUPPORT, Phase.OBJECTIVE, Phase.IGL, Phase.MACRO)

    fun player(w: World, t: TitleDef, playerId: Int, role: RoleDef, charId: String?, mapId: String?, pressure: Double, rng: Rng?): PlayerCtx {
        val p = w.players[playerId]
        val base = Ratings.base(p, t, role)
        val (tt, r) = Ratings.familiarity(p, t, role)
        val pp = Ratings.charProf(p, t, role, charId)
        var mult = Ratings.conditionMultiplier(p, w.day)
        if (mapId != null && t.maps.size > 1) mult *= 0.965 + 0.06 * ((p.profMap[mapId] ?: 40.0) / 100)
        if (pressure > 0) mult *= 1 + pressure * ((p.attrs[dev.uem.em.model.Attrs.COMPOSURE] + p.pers[6]) / 200.0 - 0.55) * 0.05
        if (rng != null) mult *= 1 + rng.normal(0.0, Ratings.consistencySd(p))
        val phase = DoubleArray(Phase.entries.size) { Ratings.phaseRating(base, it, tt, r, pp) * mult }
        return PlayerCtx(p, role, charId, charId?.let { t.charIndex[it] }, base, mult, phase)
    }

    fun build(w: World, team: Team, lineup: List<Int>, idx: Int, chars: Map<Int, String>?, mapId: String?, pressure: Double, rng: Rng?): TeamCtx {
        val t = dev.uem.em.titles.Catalogue.get(team.titleId)
        val players = lineup.mapIndexedNotNull { i, pid -> if (pid >= 0) player(w, t, pid, t.lineupRoles[i], chars?.get(pid), mapId, pressure, rng) else null }
        val coach = (0.985 + 0.03 * (dev.uem.em.mgmt.Economy.coachQuality(w, team) / 100)) * dev.uem.em.mgmt.Economy.prepFactor(w, team) * (1 + (team.scrims.coerceIn(0, 6) - 2) * 0.002)
        val chem = 0.93 + 0.14 * (dev.uem.em.mgmt.Chemistry.lineup(w, team, lineup) / 100)
        val meta = 0.97 + 0.04 * (team.metaAdapt / 100)
        val form = if (rng != null) 1 + rng.normal(0.0, TEAM_FORM_SD) else 1.0
        val n = Phase.entries.size
        val phase = DoubleArray(n)
        for (pi in 0 until n) {
            var num = 0.0
            var den = 0.0
            for (pc in players) {
                val inv = pc.role.phaseArr[pi]
                num += inv * pc.phase[pi]
                den += inv
            }
            var v = if (den > 0) num / den else players.sumOf { it.phase[pi] } / maxOf(1, players.size)
            if (Phase.entries[pi] == Phase.IGL && players.isNotEmpty()) v = 0.6 * players.maxOf { it.phase[pi] } + 0.4 * players.sumOf { it.phase[pi] } / players.size
            var m = coach * meta * form
            if (Phase.entries[pi] in TEAM_PHASES) m *= chem
            phase[pi] = v * m
        }
        var overall = 0.0
        var ws = 0.0
        for ((ph, wt) in t.phaseWeights) { overall += phase[ph.ordinal] * wt; ws += wt }
        overall = if (ws > 0) overall / ws else 0.0
        if (players.size < t.teamSize) overall *= players.size.toDouble() / t.teamSize
        return TeamCtx(team, idx, players, phase, overall, team.aggression)
    }
}

/** Box-score book for one game: per-player stat values in the title's stat order. */
class StatBook(teams: List<TeamCtx>, val t: TitleDef) {
    val lines = ArrayList<Line>()
    private val byPlayer = HashMap<Int, Line>()

    init {
        teams.forEachIndexed { ti, tc ->
            for (pc in tc.players) {
                val l = Line(pc.p.id, ti, pc.roleId, pc.charId ?: "", 1.0, DoubleArray(t.stats.size))
                lines.add(l)
                byPlayer[pc.p.id] = l
            }
        }
    }

    fun idx(key: String) = t.statIndex[key] ?: -1
    fun add(pid: Int, k: Int, v: Double) { if (k >= 0) byPlayer[pid]?.let { it.s[k] += v } }
    fun get(l: Line, k: Int) = if (k >= 0) l.s[k] else 0.0
    fun value(pid: Int, k: Int) = if (k >= 0) byPlayer[pid]?.s?.get(k) ?: 0.0 else 0.0

    fun finalise() { for (l in lines) for (i in l.s.indices) l.s[i] = l.s[i].roundToInt().toDouble() }

    fun rate(winner: Int, impact: (Line) -> Double) {
        val vals = lines.map { maxOf(0.01, impact(it)) }
        val avg = vals.sum() / maxOf(1, vals.size)
        lines.forEachIndexed { i, l ->
            val bonus = if (winner >= 0) (if (l.team == winner) 0.06 else -0.06) else 0.0
            l.rating = (clamp(0.35 + 0.65 * (vals[i] / avg) + bonus, 0.15, 2.8) * 100).roundToInt() / 100.0
        }
    }
}

fun Rng.pickCtx(list: List<PlayerCtx>, w: (PlayerCtx) -> Double): PlayerCtx = weighted(list) { maxOf(0.001, w(it)) }

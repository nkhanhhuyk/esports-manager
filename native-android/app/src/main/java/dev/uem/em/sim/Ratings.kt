package dev.uem.em.sim

import dev.uem.em.model.Attrs
import dev.uem.em.model.Player
import dev.uem.em.titles.Phase
import dev.uem.em.titles.RoleDef
import dev.uem.em.titles.TitleDef

/**
 * Effectiveness model (as in the web version): E = 0.4·C (core) + 0.3·S (title specialist)
 * + 0.15·T (title familiarity) + 0.1·R (role familiarity) + 0.05·P (character / weapon proficiency).
 * Mechanical attributes are discounted by unfamiliarity with the title's input.
 */
object Ratings {
    const val WC = 0.4
    const val WS = 0.3
    const val WT = 0.15
    const val WR = 0.1
    const val WP = 0.05

    fun inputProf(p: Player, t: TitleDef): Double = p.input[if (t.inputMkb) 1 else 0]

    fun weighted(attrs: DoubleArray, idx: IntArray, w: DoubleArray, ip: Double): Double {
        if (idx.isEmpty()) return 0.0
        val pen = Attrs.INPUT_SPAN * (1 - ip / 100)
        var s = 0.0
        for (k in idx.indices) {
            val i = idx[k]
            s += w[k] * attrs[i] * (1 - Attrs.SENS[i] * pen)
        }
        return s
    }

    /** Core and specialist ratings of a player in a role (cached per attribute version). */
    class Base(val c: Double, val s: Double, val phaseC: DoubleArray, val phaseS: DoubleArray)

    fun base(p: Player, t: TitleDef, role: RoleDef): Base {
        val ip = inputProf(p, t)
        // Cached on the player object (not by player id: another world in the same process, such as a
        // different save or a new game, has other players with the same ids).
        // Key: attribute version, title × role slot, input proficiency.
        val cache = p.ratingCache ?: java.util.concurrent.ConcurrentHashMap<Long, Any>().also { p.ratingCache = it }
        val slot = dev.uem.em.titles.Catalogue.IDS.indexOf(t.id) * 8 + t.roles.indexOf(role)
        val key = ((p.attrVersion.toLong() and 0xffffffffL) shl 16) or ((slot.toLong() and 0xff) shl 8) or (ip.toLong() and 0xff)
        (cache[key] as? Base)?.let { return it }
        val rw = t.roleWeights[role.id]!!
        val c = weighted(p.attrs, rw.overallCore, rw.overallCoreW, ip)
        val s = if (rw.overallSpec.isNotEmpty()) weighted(p.attrs, rw.overallSpec, rw.overallSpecW, ip) else c
        val n = Phase.entries.size
        val pc = DoubleArray(n)
        val ps = DoubleArray(n)
        for (i in 0 until n) {
            val (ci, cw) = rw.phaseCore[i]
            pc[i] = weighted(p.attrs, ci, cw, ip)
            val sp = rw.phaseSpec[i]
            ps[i] = if (sp != null) weighted(p.attrs, sp.first, sp.second, ip) else pc[i]
        }
        val b = Base(c, s, pc, ps)
        if (cache.size > 48) cache.clear() // old attribute versions
        cache[key] = b
        return b
    }

    /** Character proficiency: the pick's own value, else transfer from same-class experience; with no pick the best three comfort picks. */
    fun charProf(p: Player, t: TitleDef, role: RoleDef, charId: String? = null): Double {
        if (t.characters.isNotEmpty()) {
            val m = p.profChar
            if (charId != null) {
                m[charId]?.let { return it }
                val cls = t.charIndex[charId]?.cls
                var best = 0.0
                for ((cid, v) in m) if (v > best && t.charIndex[cid]?.cls == cls) best = v
                return best * 0.45
            }
            var a = -1.0
            var b = -1.0
            var c = -1.0
            var n = 0
            for (v in m.values) {
                n++
                if (v > a) { c = b; b = a; a = v } else if (v > b) { c = b; b = v } else if (v > c) c = v
            }
            if (n == 0) return 0.0
            val k = minOf(3, n)
            return ((if (a > 0) a else 0.0) + (if (k > 1) b else 0.0) + (if (k > 2) c else 0.0)) / k
        }
        val ws = role.weapons ?: t.weaponClasses.take(2)
        if (ws.isEmpty()) return p.profTitle[t.id] ?: 0.0
        return ws.sumOf { p.profWeapon[it] ?: 0.0 } / ws.size
    }

    fun familiarity(p: Player, t: TitleDef, role: RoleDef): Pair<Double, Double> = (p.profTitle[t.id] ?: 0.0) to (p.profRole["${t.id}:${role.id}"] ?: 0.0)

    /** Headline effectiveness. */
    fun effectiveness(p: Player, t: TitleDef, role: RoleDef, charId: String? = null, pOverride: Double? = null): Double {
        val b = base(p, t, role)
        val (tt, r) = familiarity(p, t, role)
        val pp = pOverride ?: charProf(p, t, role, charId)
        return WC * b.c + WS * b.s + WT * tt + WR * r + WP * pp
    }

    fun phaseRating(b: Base, ph: Int, tt: Double, r: Double, pp: Double): Double = WC * b.phaseC[ph] + WS * b.phaseS[ph] + WT * tt + WR * r + WP * pp

    /** Current ability: main title, main role, comfort picks. */
    fun computeCA(p: Player, t: TitleDef): Double = Math.round(effectiveness(p, t, t.role(p.mainRole)) * 10) / 10.0

    fun conditionMultiplier(p: Player, day: Int): Double {
        val fat = 1 - 0.12 * Math.pow(maxOf(0.0, p.fatigue) / 100, 1.5)
        val form = 1 + p.form * 0.003
        val morale = 0.97 + 0.06 * (p.morale / 100)
        val injury = if (p.injuryUntil > day) 0.88 else 1.0
        return fat * form * morale * injury
    }

    fun consistencySd(p: Player): Double {
        val base = 0.035 * (1.4 - p.attrs[Attrs.CONSISTENCY] / 100)
        return if (p.dev.trajectory == "volatile") base * 1.7 else base
    }
}

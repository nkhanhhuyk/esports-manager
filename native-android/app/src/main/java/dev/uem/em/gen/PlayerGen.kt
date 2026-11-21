package dev.uem.em.gen

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.data.GameData
import dev.uem.em.model.AttrGroup
import dev.uem.em.model.Attrs
import dev.uem.em.model.Curve
import dev.uem.em.model.Dev
import dev.uem.em.model.Player
import dev.uem.em.model.World
import dev.uem.em.sim.Ratings
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.TitleDef
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Generation context shared by the generators of one new world. */
class GenCtx(val world: World, val rng: Rng) {
    val handles = HashSet<String>()
    val orgNames = HashSet<String>()
}

/** Player generation, ported from the web version (attributes tuned to a target current ability). */
object PlayerGen {
    private val MOBILE_FIRST = setOf("ID", "PH", "MYSG", "VN", "TH", "SA", "MENA", "LATN", "AFR")

    private fun ageFactor(age: Int, curve: Curve): Double = when (curve) {
        Curve.MECH -> if (age < 20) 2.0 else if (age > 27) -(age - 27) * 1.6 else 0.0
        Curve.KNOWLEDGE, Curve.MIND -> if (age < 22) -(22 - age) * 1.4 else minOf(4.0, (age - 22) * 0.6)
        else -> 0.0
    }

    private fun attributes(rng: Rng, t: TitleDef, roleId: String, age: Int, mean: Double): DoubleArray {
        val role = t.role(roleId)
        val offset = HashMap<AttrGroup, Double>()
        for (g in listOf(AttrGroup.Mechanical, AttrGroup.Combat, AttrGroup.Tactical, AttrGroup.Mental, AttrGroup.Teamwork, AttrGroup.Knowledge)) offset[g] = rng.normal(0.0, 5.0)
        val gap = rng.normal(24.0, 8.0)
        val out = DoubleArray(Attrs.COUNT)
        for (a in Attrs.ALL) {
            var v: Double
            if (a.group == AttrGroup.Development) v = rng.normal(52 + (mean - 60) * 0.25, 15.0)
            else {
                v = mean + (offset[a.group] ?: 0.0) + ageFactor(age, a.curve) + rng.normal(0.0, 6.5)
                role.emphasis[a.key]?.let { v += (it - 1) * 7 }
                if (a.group == AttrGroup.MOBA && !t.isMoba) v -= gap
                if (a.group == AttrGroup.Shooter && t.isMoba) v -= gap * (1 - minOf(0.7, t.specRelevance[a.key] ?: 0.0))
            }
            out[a.index] = clamp(v, 1.0, 99.0).roundToInt().toDouble()
        }
        return out
    }

    private fun dev(rng: Rng): Dev {
        val traj = rng.weighted(listOf("steady", "early", "late", "volatile", "resurgent")) { when (it) { "steady" -> 45.0; "early" -> 18.0; "late" -> 17.0; "volatile" -> 12.0; else -> 8.0 } }
        val (peak, growth) = when (traj) {
            "early" -> rng.int(19, 22) to rng.float(1.2, 1.5)
            "late" -> rng.int(25, 28) to rng.float(0.65, 0.9)
            "volatile" -> rng.int(21, 26) to rng.float(0.8, 1.3)
            "resurgent" -> rng.int(22, 25) to rng.float(0.9, 1.1)
            else -> rng.int(22, 25) to rng.float(0.85, 1.15)
        }
        return Dev(traj, peak, peak + rng.int(1, if (traj == "late") 5 else 4), rng.float(0.7, 1.4), growth)
    }

    fun generate(ctx: GenCtx, titleId: String, roleId0: String?, nat: String, age: Int, targetCA: Double, potentialBoost: Double = 0.0): Player {
        val rng = ctx.rng
        val w = ctx.world
        val t = Catalogue.get(titleId)
        val roleId = roleId0 ?: rng.pick(t.lineup)
        val birth = w.day - (age * 365.25).toInt() - rng.int(0, 364)
        val (first, last) = GameData.personName(rng, nat)
        val handle = GameData.makeHandle(rng, ctx.handles)
        val experience = clamp((age - 14) / 9.0, 0.0, 1.0) * clamp(0.55 + (targetCA - 50) / 60, 0.3, 1.0)
        val region = GameData.regionOf(nat)
        val mobile = region in MOBILE_FIRST
        val input = doubleArrayOf(clamp(rng.normal(if (mobile) 55.0 else 35.0, 18.0), 5.0, 90.0), clamp(rng.normal(if (mobile) 35.0 else 50.0, 18.0), 5.0, 90.0), clamp(rng.normal(38.0, 18.0), 5.0, 90.0))
        input[if (t.inputMkb) 1 else 0] = clamp(62 + 33 * experience + rng.normal(0.0, 5.0), 30.0, 99.0)
        for (i in 0..2) input[i] = input[i].roundToInt().toDouble()
        val T = clamp(40 + 58 * experience.pow(0.6) + rng.normal(0.0, 4.0), 15.0, 99.0).roundToInt().toDouble()
        val second = rng.pick(t.roles.filter { it.id != roleId }).id
        val profRole = HashMap<String, Double>()
        for (r in t.roles) {
            val v = when (r.id) { roleId -> T - 4 + rng.normal(0.0, 5.0); second -> T * 0.65 + rng.normal(0.0, 8.0); else -> T * 0.3 + rng.normal(0.0, 8.0) }
            profRole["${t.id}:${r.id}"] = clamp(v, 5.0, 99.0).roundToInt().toDouble()
        }
        val profChar = HashMap<String, Double>()
        if (t.characters.isNotEmpty()) {
            val role = t.role(roleId)
            val pool = t.characters.filter { it.fits(role) }
            val others = t.characters.filter { !it.fits(role) }
            val n = rng.int(5, 11)
            val chosen = rng.sample(pool.ifEmpty { t.characters }, minOf(n, if (pool.isEmpty()) n else pool.size))
            chosen.forEachIndexed { i, c -> profChar[c.id] = clamp(T + 2 - i * 3.5 + rng.normal(0.0, 5.0), 10.0, 99.0).roundToInt().toDouble() }
            for (c in rng.sample(others, rng.int(1, 4))) profChar[c.id] = clamp(T * 0.55 + rng.normal(0.0, 10.0), 5.0, 85.0).roundToInt().toDouble()
        }
        val profMap = HashMap<String, Double>()
        if (t.maps.size > 1) for (m in t.maps) profMap[m.id] = clamp(T - 10 + rng.normal(0.0, 10.0), 5.0, 99.0).roundToInt().toDouble()
        val profWeapon = HashMap<String, Double>()
        if (t.weaponClasses.isNotEmpty()) {
            val role = t.role(roleId)
            for (wc in t.weaponClasses) profWeapon[wc] = clamp((if (role.weapons?.contains(wc) == true) T else T * 0.6) + rng.normal(0.0, 7.0), 5.0, 99.0).roundToInt().toDouble()
        }
        val r = { clamp(rng.normal(55.0, 18.0), 1.0, 99.0).roundToInt() }
        val p = Player(
            id = w.players.size, first = first, last = last, handle = handle, birth = birth, nat = nat, titleId = t.id, roles = listOf(roleId, second),
            attrs = DoubleArray(Attrs.COUNT), input = input, profTitle = hashMapOf(t.id to T), profRole = profRole, profChar = profChar, profMap = profMap, profWeapon = profWeapon,
            form = rng.normal(0.0, 2.0).roundToInt().toDouble(), morale = clamp(rng.normal(65.0, 10.0), 30.0, 95.0).roundToInt().toDouble(), fatigue = rng.float(0.0, 15.0).roundToInt().toDouble(),
            dev = dev(rng), pers = intArrayOf(r(), r(), r(), r(), r(), clamp(rng.normal(30.0, 18.0), 1.0, 99.0).roundToInt(), r()), joinedDay = w.day,
        )
        var mean = targetCA - 4
        p.attrs = attributes(rng, t, roleId, age, mean)
        val role = t.role(roleId)
        for (it in 0 until 3) {
            p.attrVersion++
            val e = Ratings.effectiveness(p, t, role)
            val delta = (targetCA - e) / 0.66
            if (abs(delta) < 0.6) break
            for (i in 0 until Attrs.COUNT) if (Attrs.ALL[i].group != AttrGroup.Development) p.attrs[i] = clamp(p.attrs[i] + delta, 1.0, 99.0).roundToInt().toDouble()
            mean += delta
        }
        p.attrVersion++
        p.ca = Ratings.computeCA(p, t)
        val ageGap = when {
            age <= 17 -> rng.normal(13.0, 7.0)
            age <= 20 -> rng.normal(6.0, 5.0)
            age <= 23 -> rng.normal(2.5, 3.0)
            else -> rng.normal(0.8, 1.5)
        }
        p.pa = (clamp(p.ca + maxOf(0.0, ageGap + potentialBoost), p.ca, 99.0) * 10).roundToInt() / 10.0
        p.rep = clamp((p.ca - 50) * 2 + rng.normal(0.0, 6.0), 1.0, 99.0).roundToInt().toDouble()
        p.caHistory.add(p.ca.toFloat())
        w.players.add(p)
        return p
    }

    fun age(p: Player, day: Int) = Dates.ageAt(p.birth, day)
}

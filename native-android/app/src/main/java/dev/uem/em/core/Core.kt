package dev.uem.em.core

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Seeded pseudo-random numbers (sfc32, seeded by a cyrb128 string hash), as in the web version.
 * The simulation never uses an unseeded source: every decision draws from an RNG derived from the
 * world seed plus a stable purpose key, so a world reproduces the same results whichever thread
 * simulates a match.
 */
class Rng private constructor(private var a: Int, private var b: Int, private var c: Int, private var d: Int) {
    private var spare = Double.NaN

    init {
        if ((a or b or c or d) == 0) d = 1
        repeat(12) { next() }
    }

    /** Uniform in [0, 1). */
    fun next(): Double {
        val t = a + b + d
        d += 1
        a = b xor (b ushr 9)
        b = c + (c shl 3)
        c = ((c shl 21) or (c ushr 11)) + t
        return (t.toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }

    fun float(min: Double = 0.0, max: Double = 1.0) = min + (max - min) * next()

    /** Integer in [min, max] inclusive. */
    fun int(min: Int, max: Int): Int = floor(min + (max - min + 1) * next()).toInt()

    fun chance(p: Double) = next() < p

    fun normal(mean: Double = 0.0, sd: Double = 1.0): Double {
        if (!spare.isNaN()) {
            val z = spare
            spare = Double.NaN
            return mean + sd * z
        }
        var u = 0.0
        while (u == 0.0) u = next()
        val v = next()
        val m = sqrt(-2 * ln(u))
        spare = m * sin(2 * PI * v)
        return mean + sd * m * cos(2 * PI * v)
    }

    fun normalClamped(mean: Double, sd: Double, min: Double, max: Double) = normal(mean, sd).coerceIn(min, max)

    fun <T> pick(list: List<T>): T = list[(next() * list.size).toInt()]

    /** Weighted choice; weights <= 0 are never chosen unless all are. */
    inline fun <T> weighted(items: List<T>, weight: (T) -> Double): T {
        val n = items.size
        val ws = DoubleArray(n)
        var total = 0.0
        for (i in 0 until n) {
            val w = weight(items[i]).coerceAtLeast(0.0)
            ws[i] = w
            total += w
        }
        if (total <= 0) return pick(items)
        var r = next() * total
        for (i in 0 until n) {
            r -= ws[i]
            if (r <= 0) return items[i]
        }
        return items[n - 1]
    }

    fun weightedIndex(weights: DoubleArray): Int {
        var total = 0.0
        for (w in weights) total += w.coerceAtLeast(0.0)
        if (total <= 0) return (next() * weights.size).toInt()
        var r = next() * total
        for (i in weights.indices) {
            r -= weights[i].coerceAtLeast(0.0)
            if (r <= 0) return i
        }
        return weights.size - 1
    }

    fun <T> shuffle(list: MutableList<T>): MutableList<T> {
        for (i in list.size - 1 downTo 1) {
            val j = (next() * (i + 1)).toInt()
            val t = list[i]
            list[i] = list[j]
            list[j] = t
        }
        return list
    }

    fun <T> sample(list: List<T>, k: Int): List<T> = shuffle(list.toMutableList()).take(minOf(k, list.size))

    companion object {
        fun of(seed: String): Rng {
            val h = hash128(seed)
            return Rng(h[0], h[1], h[2], h[3])
        }

        fun derive(vararg parts: Any): Rng = of(parts.joinToString("|"))

        /** cyrb128 string hash → four 32-bit seeds. */
        fun hash128(str: String): IntArray {
            var h1 = 1779033703
            var h2 = -1150833019 // 3144134277
            var h3 = 1013904242
            var h4 = -1521486534 // 2773480762
            for (ch in str) {
                val k = ch.code
                h1 = h2 xor ((h1 xor k) * 597399067)
                h2 = h3 xor ((h2 xor k) * -1425107063) // 2869860233
                h3 = h4 xor ((h3 xor k) * 951274213)
                h4 = h1 xor ((h4 xor k) * -1578923117) // 2716044179
            }
            h1 = (h3 xor (h1 ushr 18)) * 597399067
            h2 = (h4 xor (h2 ushr 22)) * -1425107063
            h3 = (h1 xor (h3 ushr 17)) * 951274213
            h4 = (h2 xor (h4 ushr 19)) * -1578923117
            return intArrayOf(h1 xor h2 xor h3 xor h4, h2 xor h1, h3 xor h1, h4 xor h1)
        }

        /** 32-bit FNV-1a. */
        fun hash32(str: String): Int {
            var h = -2128831035
            for (ch in str) {
                h = h xor ch.code
                h *= 16777619
            }
            return h
        }
    }
}

/** Game calendar: dates are day numbers since 1970-01-01. */
object Dates {
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val DOW = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun day(y: Int, m: Int, d: Int): Int = LocalDate.of(y, m, d).toEpochDay().toInt()
    fun date(day: Int): LocalDate = LocalDate.ofEpochDay(day.toLong())
    fun year(day: Int) = date(day).year
    fun month(day: Int) = date(day).monthValue
    fun dom(day: Int) = date(day).dayOfMonth

    /** 0 = Sunday … 6 = Saturday (as in the web version). */
    fun dow(day: Int) = date(day).dayOfWeek.value % 7

    fun format(day: Int, withDow: Boolean = false): String {
        val d = date(day)
        return (if (withDow) DOW[d.dayOfWeek.value - 1] + " " else "") + "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} ${d.year}"
    }

    fun short(day: Int): String {
        val d = date(day)
        return "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
    }

    fun ageAt(birth: Int, day: Int): Int {
        val b = date(birth)
        val n = date(day)
        var age = n.year - b.year
        if (n.monthValue < b.monthValue || (n.monthValue == b.monthValue && n.dayOfMonth < b.dayOfMonth)) age--
        return age
    }

    fun ageFloat(birth: Int, day: Int) = (day - birth) / 365.25

    fun nextWeekday(from: Int, dow: Int): Int = from + ((dow - dow(from) + 7) % 7)

    fun today(): Int = LocalDate.now().toEpochDay().toInt()
}

fun clamp(x: Double, lo: Double, hi: Double) = if (x < lo) lo else if (x > hi) hi else x
fun clamp100(x: Double) = clamp(x, 0.0, 100.0)
fun logistic(x: Double) = 1.0 / (1.0 + exp(-x))
fun round1(x: Double) = (x * 10).roundToInt() / 10.0
fun round2(x: Double) = (x * 100).roundToInt() / 100.0

fun formatMoney(x: Double): String {
    val sign = if (x < 0) "-" else ""
    val a = abs(x)
    return when {
        a >= 1e9 -> "$sign$" + "%.2fB".format(a / 1e9)
        a >= 1e6 -> "$sign$" + "%.2fM".format(a / 1e6)
        a >= 1e3 -> "$sign$" + "%.0fK".format(a / 1e3)
        else -> "$sign$" + "%.0f".format(a)
    }
}

fun ordinal(n: Int): String {
    val v = n % 100
    val suffix = if (v in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    return "$n$suffix"
}

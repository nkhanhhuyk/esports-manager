package dev.uem.em.sim

import dev.uem.em.data.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A MOBA battlefield for the simulation (map units 0..1000, same data as the 2D view):
 * lanes, structures, camps and objective pits, plus a walkability grid built from the map's wall
 * rectangles. Movement follows distance fields over that grid, so units walk around terrain;
 * line of sight is traced through the grid. The grid is an approximate layout of the real map
 * (20-unit cells), not the game's navigation mesh.
 */
class MobaMap(val id: String, val name: String) {
    class Struct(val idx: Int, val team: Int, val kind: String, val lane: String?, val tier: Int, val name: String, val x: Float, val y: Float)
    class Camp(val idx: Int, val team: Int, val kind: String, val name: String, val x: Float, val y: Float)
    class Pit(val idx: Int, val name: String, val x: Float, val y: Float, val objectives: List<String>)

    class Lane(val id: String, val xs: FloatArray, val ys: FloatArray) {
        private val cum = FloatArray(xs.size).also { c -> for (i in 1 until xs.size) c[i] = c[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]) }
        val length = cum.last()

        /** Point at fraction f along the lane (0 = team 0's base end). */
        fun x(f: Float) = at(f, true)
        fun y(f: Float) = at(f, false)
        private fun at(f: Float, isX: Boolean): Float {
            val d = f.coerceIn(0f, 1f) * length
            var i = 1
            while (i < xs.size - 1 && cum[i] < d) i++
            val seg = (cum[i] - cum[i - 1]).coerceAtLeast(1e-3f)
            val k = (d - cum[i - 1]) / seg
            return if (isX) xs[i - 1] + (xs[i] - xs[i - 1]) * k else ys[i - 1] + (ys[i] - ys[i - 1]) * k
        }

        /** Fraction of the lane closest to a point. */
        fun fractionOf(px: Float, py: Float): Float {
            var best = 0f
            var bd = Float.MAX_VALUE
            for (k in 0..100) { val f = k / 100f; val dx = x(f) - px; val dy = y(f) - py; val dd = dx * dx + dy * dy; if (dd < bd) { bd = dd; best = f } }
            return best
        }
    }

    lateinit var lanes: Map<String, Lane>
    val laneIds = listOf("top", "mid", "bot")
    lateinit var cores: Array<FloatArray>
    lateinit var fountains: Array<FloatArray>
    lateinit var structs: List<Struct>
    lateinit var camps: List<Camp>
    lateinit var pits: List<Pit>
    lateinit var walls: List<FloatArray>

    /** Walkable cells (N x N grid of CELL units). */
    val walk = BooleanArray(N * N)
    private val fields = arrayOfNulls<ShortArray>(N * N)

    fun cellOf(x: Float, y: Float): Int {
        val cx = (x / CELL).toInt().coerceIn(0, N - 1)
        val cy = (y / CELL).toInt().coerceIn(0, N - 1)
        return cy * N + cx
    }
    fun cx(c: Int) = (c % N) * CELL + CELL / 2
    fun cy(c: Int) = (c / N) * CELL + CELL / 2

    /** Grid distance (in 10ths of a cell, 8-connected) from every cell to `target`; unreachable = Short.MAX_VALUE. */
    fun field(target: Int): ShortArray = fields[target] ?: synchronized(fields) { fields[target] ?: computeField(target).also { fields[target] = it } }

    private fun computeField(target: Int): ShortArray {
        val d = ShortArray(N * N) { Short.MAX_VALUE }
        val dist = IntArray(N * N) { Int.MAX_VALUE }
        // Dial's algorithm (costs 10 / 14).
        val buckets = Array(16) { IntArray(N * N) }
        val sizes = IntArray(16)
        dist[target] = 0
        buckets[0][sizes[0]++] = target
        var cur = 0
        var pending = 1
        while (pending > 0) {
            val b = cur % 16
            if (sizes[b] == 0) { cur++; continue }
            val c = buckets[b][--sizes[b]]
            pending--
            if (dist[c] != cur) continue
            val x = c % N; val y = c / N
            for (k in 0 until 8) {
                val nx = x + DX[k]; val ny = y + DY[k]
                if (nx < 0 || ny < 0 || nx >= N || ny >= N) continue
                val n = ny * N + nx
                if (!walk[n]) continue
                if (k >= 4 && (!walk[y * N + nx] || !walk[ny * N + x])) continue // no corner cutting
                val nd = cur + (if (k < 4) 10 else 14)
                if (nd < dist[n]) { dist[n] = nd; val nb = nd % 16; buckets[nb][sizes[nb]++] = n; pending++ }
            }
        }
        for (i in d.indices) if (dist[i] != Int.MAX_VALUE) d[i] = minOf(dist[i], Short.MAX_VALUE - 1).toShort()
        return d
    }

    /** Walking distance between two points (map units; straight line when the field says unreachable). */
    fun pathDist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val f = field(cellOf(x1, y1))
        val v = f[cellOf(x0, y0)]
        return if (v == Short.MAX_VALUE) hypot(x1 - x0, y1 - y0) else v * CELL / 10f
    }

    /**
     * Move from (x, y) toward (tx, ty) by `step` units along walkable cells. Writes the new position
     * into out[0], out[1]; returns true when the target was reached.
     */
    fun moveToward(x: Float, y: Float, tx: Float, ty: Float, step: Float, out: FloatArray): Boolean {
        var px = x; var py = y
        var left = step
        val tc = cellOf(tx, ty)
        var guard = 0
        while (left > 0.01f && guard++ < 6) {
            val c = cellOf(px, py)
            val dd = hypot(tx - px, ty - py)
            if (c == tc || (dd <= CELL * 2.2f && los(px, py, tx, ty))) {
                if (dd <= left) { out[0] = tx; out[1] = ty; return true }
                px += (tx - px) / dd * left; py += (ty - py) / dd * left
                left = 0f
                break
            }
            val f = field(tc)
            val cxp = c % N; val cyp = c / N
            var best = -1
            var bv = f[c].toInt()
            for (k in 0 until 8) {
                val nx = cxp + DX[k]; val ny = cyp + DY[k]
                if (nx < 0 || ny < 0 || nx >= N || ny >= N) continue
                val n = ny * N + nx
                if (!walk[n]) continue
                if (k >= 4 && (!walk[cyp * N + nx] || !walk[ny * N + cxp])) continue
                val v = f[n].toInt()
                if (v < bv) { bv = v; best = n }
            }
            if (best < 0) { // off the grid / boxed in: walk straight
                if (dd <= left) { out[0] = tx; out[1] = ty; return true }
                px += (tx - px) / dd * left; py += (ty - py) / dd * left
                break
            }
            val ax = cx(best); val ay = cy(best)
            val da = hypot(ax - px, ay - py)
            if (da <= left) { px = ax; py = ay; left -= da } else { px += (ax - px) / da * left; py += (ay - py) / da * left; left = 0f }
        }
        out[0] = px; out[1] = py
        return false
    }

    /** Line of sight through the walkable grid (walls block). */
    fun los(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val dx = x1 - x0; val dy = y1 - y0
        val len = max(abs(dx), abs(dy))
        val steps = (len / (CELL * 0.5f)).toInt()
        if (steps <= 1) return true
        for (i in 1 until steps) {
            val f = i.toFloat() / steps
            if (!walk[cellOf(x0 + dx * f, y0 + dy * f)]) return false
        }
        return true
    }

    fun nearestWalkable(x: Float, y: Float): Int {
        val c = cellOf(x, y)
        if (walk[c]) return c
        var best = c; var bd = Float.MAX_VALUE
        for (i in walk.indices) if (walk[i]) { val d = hypot(cx(i) - x, cy(i) - y); if (d < bd) { bd = d; best = i } }
        return best
    }

    companion object {
        const val N = 50
        const val CELL = 20f
        private val DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        private val DY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
        private val maps = ConcurrentHashMap<String, MobaMap>()
        @Volatile private var json: kotlinx.serialization.json.JsonObject? = null

        fun init(src: DataSource) {
            if (json != null) return
            json = Json.parseToJsonElement(src.read("maps.json")).jsonObject["moba"]!!.jsonObject
        }

        fun of(titleId: String): MobaMap = maps.getOrPut(titleId) { build(titleId) }

        private fun pt(a: JsonArray) = floatArrayOf(a[0].jsonPrimitive.float, a[1].jsonPrimitive.float)

        private fun build(titleId: String): MobaMap {
            val o = json?.get(titleId)?.jsonObject ?: error("No MOBA map for $titleId (MobaMap.init not called)")
            val m = MobaMap(titleId, o["name"]!!.jsonPrimitive.content)
            m.lanes = o["lanes"]!!.jsonObject.mapValues { (k, v) -> val ps = v.jsonArray.map { pt(it.jsonArray) }; Lane(k, FloatArray(ps.size) { ps[it][0] }, FloatArray(ps.size) { ps[it][1] }) }
            m.cores = o["cores"]!!.jsonArray.map { pt(it.jsonArray) }.toTypedArray()
            m.fountains = o["fountains"]!!.jsonArray.map { pt(it.jsonArray) }.toTypedArray()
            m.structs = o["structures"]!!.jsonArray.mapIndexed { i, e -> val s = e.jsonObject; val p = pt(s["pos"]!!.jsonArray)
                Struct(i, s["team"]!!.jsonPrimitive.int, s["kind"]!!.jsonPrimitive.content, s["lane"]?.jsonPrimitive?.contentOrNull, s["tier"]?.jsonPrimitive?.int ?: 0, s["name"]?.jsonPrimitive?.contentOrNull ?: "", p[0], p[1]) }
            m.camps = o["camps"]!!.jsonArray.mapIndexed { i, e -> val s = e.jsonObject; val p = pt(s["pos"]!!.jsonArray)
                Camp(i, s["team"]!!.jsonPrimitive.int, s["kind"]?.jsonPrimitive?.contentOrNull ?: "camp", s["name"]?.jsonPrimitive?.contentOrNull ?: "Camp", p[0], p[1]) }
            m.pits = o["pits"]!!.jsonArray.mapIndexed { i, e -> val s = e.jsonObject; val p = pt(s["pos"]!!.jsonArray)
                Pit(i, s["name"]!!.jsonPrimitive.content, p[0], p[1], s["objectives"]!!.jsonArray.map { it.jsonPrimitive.content }) }
            m.walls = o["walls"]!!.jsonArray.map { a -> FloatArray(4) { a.jsonArray[it].jsonPrimitive.float } }
            // Cells whose centre lies in a wall are blocked.
            for (c in 0 until N * N) {
                val x = m.cx(c); val y = m.cy(c)
                m.walk[c] = m.walls.none { r -> x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3] }
            }
            // Lanes, bases, camps, pits and structures are always walkable (the drawn map is coarse).
            fun open(x: Float, y: Float, r: Int) { val c = m.cellOf(x, y); val cx = c % N; val cy = c / N
                for (dy in -r..r) for (dx in -r..r) { val nx = cx + dx; val ny = cy + dy; if (nx in 0 until N && ny in 0 until N) m.walk[ny * N + nx] = true } }
            for (l in m.lanes.values) { var f = 0f; while (f <= 1f) { open(l.x(f), l.y(f), 1); f += 0.01f } }
            for (p in m.fountains + m.cores) open(p[0], p[1], 2)
            for (s in m.structs) open(s.x, s.y, 1)
            for (c in m.camps) open(c.x, c.y, 1)
            for (p in m.pits) open(p.x, p.y, 2)
            // The maps are point-symmetric (structures, camps and pits mirror exactly), but the wall
            // rectangles are a coarse drawing that does not: make walking fair for both sides by
            // opening a cell when its mirror cell is open.
            for (c in 0 until N * N) { val mc = (N - 1 - c / N) * N + (N - 1 - c % N); if (m.walk[mc]) m.walk[c] = true }
            // Everything important must be reachable from both fountains: carve straight corridors to any isolated point.
            val keys = m.structs.map { it.x to it.y } + m.camps.map { it.x to it.y } + m.pits.map { it.x to it.y }
            for (f in m.fountains) {
                val fd = m.field(m.cellOf(f[0], f[1]))
                for ((kx, ky) in keys) if (fd[m.cellOf(kx, ky)] == Short.MAX_VALUE) {
                    var f0 = 0f; while (f0 <= 1f) { open(kx + (f[0] - kx) * f0, ky + (f[1] - ky) * f0, 0); f0 += 0.01f }
                    java.util.Arrays.fill(m.fields, null)
                }
            }
            java.util.Arrays.fill(m.fields, null)
            return m
        }
    }
}

private operator fun Array<FloatArray>.plus(o: Array<FloatArray>): List<FloatArray> = this.toList() + o.toList()

@Suppress("unused")
private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))

/** Fast distance (Math.hypot is overflow-safe and slow; map values are small). */
@Suppress("NOTHING_TO_INLINE")
private inline fun hypot(x: Float, y: Float): Float = sqrt(x * x + y * y)

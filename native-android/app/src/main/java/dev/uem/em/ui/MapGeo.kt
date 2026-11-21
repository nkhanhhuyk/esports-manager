package dev.uem.em.ui

import androidx.compose.ui.geometry.Offset
import dev.uem.em.data.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.hypot

/**
 * Real map geometry for the 2D match view, exported from the web version's map definitions
 * (assets/maps.json, scripts/native-export.ts). Coordinates are 0..1000 (shown as 0..1).
 */
object Maps {
    class Line(val pts: List<Offset>) {
        private val cum = FloatArray(pts.size).also { c -> for (i in 1 until pts.size) c[i] = c[i - 1] + (pts[i] - pts[i - 1]).getDistance() }
        val length = cum.last()

        /** Point at fraction f (0 = start, 1 = end) along the line. */
        fun at(f: Float): Offset {
            if (pts.size < 2) return pts.firstOrNull() ?: Offset(0.5f, 0.5f)
            val d = f.coerceIn(0f, 1f) * length
            var i = 1
            while (i < pts.size - 1 && cum[i] < d) i++
            val seg = (cum[i] - cum[i - 1]).coerceAtLeast(1e-4f)
            return pts[i - 1] + (pts[i] - pts[i - 1]) * ((d - cum[i - 1]) / seg)
        }

        /** Fraction of the line closest to p. */
        fun fractionOf(p: Offset): Float {
            var best = 0f
            var bd = Float.MAX_VALUE
            for (k in 0..100) { val q = at(k / 100f); val dd = (q - p).getDistanceSquared(); if (dd < bd) { bd = dd; best = k / 100f } }
            return best
        }
    }

    class Structure(val team: Int, val kind: String, val lane: String?, val tier: Int, val name: String, val pos: Offset)
    class Camp(val team: Int, val kind: String, val pos: Offset)
    class Pit(val name: String, val pos: Offset, val objectives: List<String>)
    class Moba(val name: String, val lanes: Map<String, Line>, val cores: List<Offset>, val fountains: List<Offset>, val structures: List<Structure>, val camps: List<Camp>, val pits: List<Pit>, val river: List<Offset>, val walls: List<FloatArray>)

    class Node(val name: String, val sup: String, val pos: Offset, val kind: String)
    class Site(val id: String, val node: Int, val pos: Offset, val r: Float)
    class Shooter(val name: String, val nodes: List<Node>, val edges: List<Pair<Int, Int>>, val sites: List<Site>, val atk: Int, val def: Int, val walls: List<FloatArray>) {
        private val adj = Array(nodes.size) { ArrayList<Int>() }.also { a -> for ((x, y) in edges) { a[x].add(y); a[y].add(x) } }

        /** Shortest route between two nodes (by distance). */
        fun route(from: Int, to: Int): List<Offset> {
            val n = nodes.size
            val dist = FloatArray(n) { Float.MAX_VALUE }
            val prev = IntArray(n) { -1 }
            val done = BooleanArray(n)
            dist[from] = 0f
            repeat(n) {
                var u = -1
                for (i in 0 until n) if (!done[i] && (u < 0 || dist[i] < dist[u])) u = i
                if (u < 0 || dist[u] == Float.MAX_VALUE) return@repeat
                done[u] = true
                for (v in adj[u]) { val nd = dist[u] + (nodes[u].pos - nodes[v].pos).getDistance(); if (nd < dist[v]) { dist[v] = nd; prev[v] = u } }
            }
            val path = ArrayList<Offset>()
            var c = to
            while (c >= 0) { path.add(0, nodes[c].pos); if (c == from) break; c = prev[c] }
            return if (path.size >= 2) path else listOf(nodes[from].pos, nodes[to].pos)
        }
    }

    private var moba: Map<String, Moba> = emptyMap()
    private var shooter: Map<String, Shooter> = emptyMap()

    private fun p(a: JsonArray) = Offset(a[0].jsonPrimitive.int / 1000f, a[1].jsonPrimitive.int / 1000f)
    private fun rect(a: JsonArray) = FloatArray(4) { a[it].jsonPrimitive.int / 1000f }

    @Synchronized
    fun init(src: DataSource) {
        if (moba.isNotEmpty()) return
        val j = Json.parseToJsonElement(src.read("maps.json")).jsonObject
        moba = j["moba"]!!.jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            Moba(
                o.s("name"),
                o["lanes"]!!.jsonObject.mapValues { (_, l) -> Line(l.jsonArray.map { p(it.jsonArray) }) },
                o["cores"]!!.jsonArray.map { p(it.jsonArray) },
                o["fountains"]!!.jsonArray.map { p(it.jsonArray) },
                o["structures"]!!.jsonArray.map { it.jsonObject }.map { s -> Structure(s["team"]!!.jsonPrimitive.int, s.s("kind"), s["lane"]?.jsonPrimitive?.contentOrNull, s["tier"]!!.jsonPrimitive.int, s.s("name"), p(s["pos"]!!.jsonArray)) },
                o["camps"]!!.jsonArray.map { it.jsonObject }.map { c -> Camp(c["team"]!!.jsonPrimitive.intOrNull ?: -1, c.s("kind"), p(c["pos"]!!.jsonArray)) },
                o["pits"]!!.jsonArray.map { it.jsonObject }.map { pt -> Pit(pt.s("name"), p(pt["pos"]!!.jsonArray), pt["objectives"]!!.jsonArray.map { it.jsonPrimitive.content }) },
                o["river"]!!.jsonArray.map { p(it.jsonArray) },
                o["walls"]!!.jsonArray.map { rect(it.jsonArray) },
            )
        }
        shooter = j["shooter"]!!.jsonObject.filterValues { it is JsonObject }.mapValues { (_, v) ->
            val o = v.jsonObject
            Shooter(
                o.s("name"),
                o["nodes"]!!.jsonArray.map { it.jsonObject }.map { n -> Node(n.s("name"), n.s("sup"), Offset(n["x"]!!.jsonPrimitive.int / 1000f, n["y"]!!.jsonPrimitive.int / 1000f), n.s("kind")) },
                o["edges"]!!.jsonArray.map { e -> e.jsonArray[0].jsonPrimitive.int to e.jsonArray[1].jsonPrimitive.int },
                o["sites"]!!.jsonArray.map { it.jsonObject }.map { s -> Site(s.s("id"), s["node"]!!.jsonPrimitive.int, Offset(s["x"]!!.jsonPrimitive.int / 1000f, s["y"]!!.jsonPrimitive.int / 1000f), s["r"]!!.jsonPrimitive.int / 1000f) },
                o["atk"]!!.jsonPrimitive.int,
                o["def"]!!.jsonPrimitive.int,
                o["walls"]!!.jsonArray.map { rect(it.jsonArray) },
            )
        }
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull ?: ""

    fun moba(titleId: String): Moba? = moba[titleId]
    fun shooter(mapId: String): Shooter? = shooter[mapId]

    @Suppress("unused")
    private fun dist(a: Offset, b: Offset) = hypot(a.x - b.x, a.y - b.y)
}

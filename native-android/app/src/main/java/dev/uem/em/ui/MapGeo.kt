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

    class Node(val name: String, val sup: String, val pos: Offset, val kind: String, var level: Float = 0f)
    class Site(val id: String, val node: Int, val pos: Offset, val r: Float)

    /** A traversal link: walk, stairs, ladder, door, vent, breakable (two-way); drop, teleport (one-way). */
    class Link(val from: Int, val to: Int, var type: String) {
        val oneWay get() = type == "drop" || type == "teleport"
    }

    class Shooter(val name: String, val nodes: List<Node>, val links: List<Link>, val sites: List<Site>, val atk: Int, val def: Int, val walls: List<FloatArray>, val source: String = "") {
        val edges get() = links.map { it.from to it.to }
        private val adj = Array(nodes.size) { ArrayList<Pair<Int, Link>>() }.also { a -> for (l in links) { a[l.from].add(l.to to l); if (!l.oneWay) a[l.to].add(l.from to l) } }

        /** Travel cost of a link (map units; a teleport is instant, doors and vents are slow). */
        fun cost(u: Int, v: Int, l: Link): Float {
            val d = (nodes[u].pos - nodes[v].pos).getDistance()
            return when (l.type) {
                "teleport" -> 0.02f
                "drop" -> d * 0.6f
                "stairs" -> d * 1.25f + 0.01f
                "ladder" -> d * 1.6f + 0.03f
                "vent" -> d * 1.6f + 0.03f
                "door", "breakable" -> d + 0.06f
                else -> d
            }
        }

        /** Links leaving a node in the direction they can be travelled. */
        fun out(u: Int): List<Pair<Int, Link>> = adj[u]

        /**
         * Shortest directed path (node indexes), respecting one-way drops and teleporters; empty if unreachable.
         * [closed]: links that cannot be used right now (an intact breakable wall, a utility wall standing on it).
         */
        fun path(from: Int, to: Int, closed: ((Link) -> Boolean)? = null): List<Int> {
            val n = nodes.size
            if (from !in 0 until n || to !in 0 until n) return emptyList()
            val dist = FloatArray(n) { Float.MAX_VALUE }
            val prev = IntArray(n) { -1 }
            val done = BooleanArray(n)
            dist[from] = 0f
            repeat(n) {
                var u = -1
                for (i in 0 until n) if (!done[i] && (u < 0 || dist[i] < dist[u])) u = i
                if (u < 0 || dist[u] == Float.MAX_VALUE) return@repeat
                done[u] = true
                for ((v, l) in adj[u]) { if (closed != null && closed(l)) continue; val nd = dist[u] + cost(u, v, l); if (nd < dist[v]) { dist[v] = nd; prev[v] = u } }
            }
            if (dist[to] == Float.MAX_VALUE) return emptyList()
            val path = ArrayList<Int>()
            var c = to
            while (c >= 0) { path.add(0, c); if (c == from) break; c = prev[c] }
            return path
        }

        fun linkBetween(a: Int, b: Int) = links.firstOrNull { (it.from == a && it.to == b) || (!it.oneWay && it.from == b && it.to == a) }

        /**
         * Shortest route between two nodes as points. A teleport is drawn as a jump: the route jumps
         * from the entrance to the exit (the line between them is not walked).
         */
        fun route(from: Int, to: Int): List<Offset> {
            val p = path(from, to)
            return if (p.size >= 2) p.map { nodes[it].pos } else listOf(nodes[from].pos, nodes[to].pos)
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
                o["edges"]!!.jsonArray.map { e -> Link(e.jsonArray[0].jsonPrimitive.int, e.jsonArray[1].jsonPrimitive.int, "walk") }.toMutableList(),
                o["sites"]!!.jsonArray.map { it.jsonObject }.map { s -> Site(s.s("id"), s["node"]!!.jsonPrimitive.int, Offset(s["x"]!!.jsonPrimitive.int / 1000f, s["y"]!!.jsonPrimitive.int / 1000f), s["r"]!!.jsonPrimitive.int / 1000f) },
                o["atk"]!!.jsonPrimitive.int,
                o["def"]!!.jsonPrimitive.int,
                o["walls"]!!.jsonArray.map { rect(it.jsonArray) },
            )
        }
        applyTopology(src)
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull ?: ""

    /** Apply the curated topology (assets/maps_topology.json): levels, retyped and added links. */
    private fun applyTopology(src: DataSource) {
        val text = runCatching { src.read("maps_topology.json") }.getOrNull() ?: return
        val maps = Json.parseToJsonElement(text).jsonObject["maps"]?.jsonObject ?: return
        val out = HashMap(shooter)
        for ((id, v) in maps) {
            val m = shooter[id] ?: continue
            val o = v.jsonObject
            o["levels"]?.jsonObject?.forEach { (k, lv) -> m.nodes.getOrNull(k.toInt())?.level = lv.jsonPrimitive.content.toFloat() }
            val links = m.links.toMutableList()
            o["retype"]?.jsonArray?.forEach { e ->
                val a = e.jsonArray[0].jsonPrimitive.int; val b = e.jsonArray[1].jsonPrimitive.int; val type = e.jsonArray[2].jsonPrimitive.content
                val l = links.firstOrNull { (it.from == a && it.to == b) || (it.from == b && it.to == a) }
                if (l != null) { links.remove(l); links.add(Link(a, b, type)) }
            }
            o["add"]?.jsonArray?.forEach { e -> links.add(Link(e.jsonArray[0].jsonPrimitive.int, e.jsonArray[1].jsonPrimitive.int, e.jsonArray[2].jsonPrimitive.content)) }
            val carve = o["carve"]?.jsonArray?.map { e -> Triple(e.jsonArray[0].jsonPrimitive.int, e.jsonArray[1].jsonPrimitive.int, e.jsonArray[2].jsonPrimitive.int / 1000f) }.orEmpty()
            out[id] = Shooter(m.name, m.nodes, links, m.sites, m.atk, m.def, if (carve.isEmpty()) m.walls else carved(m, carve), o.s("source"))
        }
        shooter = out
    }

    /**
     * Walls with corridors opened along links (layout corrections): the walls are a 100 x 100 raster drawn as row
     * runs of 10 map units; cells within the half-width of a link are cleared and the rows written back as runs.
     */
    private fun carved(m: Shooter, corridors: List<Triple<Int, Int, Float>>): List<FloatArray> {
        val n = 100
        val g = BooleanArray(n * n)
        for (c in 0 until n * n) { val cx = ((c % n) + 0.5f) / n; val cy = ((c / n) + 0.5f) / n; g[c] = m.walls.any { r -> cx >= r[0] && cx < r[0] + r[2] && cy >= r[1] && cy < r[1] + r[3] } }
        for ((a, b, hw) in corridors) {
            val p = m.nodes[a].pos; val q = m.nodes[b].pos
            val dx = q.x - p.x; val dy = q.y - p.y; val l2 = dx * dx + dy * dy
            for (c in 0 until n * n) {
                val cx = ((c % n) + 0.5f) / n; val cy = ((c / n) + 0.5f) / n
                val t = if (l2 < 1e-9f) 0f else (((cx - p.x) * dx + (cy - p.y) * dy) / l2).coerceIn(0f, 1f)
                val ex = p.x + t * dx - cx; val ey = p.y + t * dy - cy
                if (ex * ex + ey * ey <= hw * hw) g[c] = false
            }
        }
        val out = ArrayList<FloatArray>()
        for (y in 0 until n) {
            var x = 0
            while (x < n) {
                if (!g[y * n + x]) { x++; continue }
                val x0 = x
                while (x < n && g[y * n + x]) x++
                out.add(floatArrayOf(x0 / 100f, y / 100f, (x - x0) / 100f, 0.01f))
            }
        }
        return out
    }

    fun moba(titleId: String): Moba? = moba[titleId]
    fun shooter(mapId: String): Shooter? = shooter[mapId]
    /** Every shooter map with geometry (real maps from the asset, plus registered fictional ones). */
    fun shooterIds(): List<String> = shooter.keys.sorted()

    @Suppress("unused")
    private fun dist(a: Offset, b: Offset) = hypot(a.x - b.x, a.y - b.y)
}

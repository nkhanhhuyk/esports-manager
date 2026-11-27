package dev.uem.em.sim

import androidx.compose.ui.geometry.Offset
import dev.uem.em.core.Rng
import dev.uem.em.model.CustomMap
import dev.uem.em.model.CustomMapNode
import dev.uem.em.ui.Maps
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Fictional shooter maps, released by major patches: a layout generator (two to four bomb sites, A to D,
 * themed callouts, and now and then a teleporter, a drop, a door, a breakable wall or a vent) and the compiler
 * that turns a layout into the same graph and wall raster as the real maps (the web version's
 * compileShooterMap: a cell is open when it lies within reach of a callout or a corridor, otherwise wall), so
 * the round engine, line of sight, the 2D viewer and the map tests treat them like the real ones.
 */
object MapForge {
    class Theme(val key: String, val setting: String, val callouts: List<String>, val teleport: String, val drop: String, val door: String, val breakable: String, val vent: String)

    val THEMES = listOf(
        Theme("aquarium", "a flooded aquarium", listOf("Shark Tunnel", "Jellyfish", "Gift Shop", "Penguins", "Kelp", "Feeding Deck", "Turtle Bay", "Tickets", "Pump Room", "Glass Arch", "Coral", "Seal Stage", "Octopus", "Filters", "Eel Pit", "Splash Zone"), "pneumatic fish tubes", "the whale slide", "a revolving airlock", "the shark tank glass", "the filter ducts"),
        Theme("funfair", "a haunted funfair", listOf("Carousel", "Ghost Train", "Mirror Maze", "Candy Floss", "Big Top", "Dodgems", "Ferris Wheel", "Ring Toss", "Clown Gate", "Helter Skelter", "Ticket Hut", "Hall of Faces", "Popcorn", "Fortune Teller", "Coconut Shy", "Tunnel of Love"), "human cannons", "the helter skelter", "squeaky turnstiles", "a funhouse mirror", "the ghost train ducts"),
        Theme("ramen", "a ramen district on a cliff edge", listOf("Noodle Bar", "Broth Vats", "Lantern Row", "Steam Room", "Kitchen", "Rooftops", "Chopsticks", "Big Bowl", "Delivery Lift", "Dumplings", "Pantry", "Back Alley", "Tea House", "Fish Market", "Bao Stand", "Soy Tanks"), "delivery chutes", "the noodle slide", "kitchen swing doors", "a paper screen wall", "the extractor fans"),
        Theme("laundromat", "a cathedral-sized laundromat", listOf("Spin Cycle", "Dryers", "Lint Trap", "Detergent", "Folding Tables", "Change Machine", "Sock Pile", "Boiler", "Rinse", "Baskets", "Mangle", "Soap Gallery", "Bleach", "Lost Property", "Ironing", "Bubble Hall"), "laundry chutes", "the hamper drop", "porthole doors", "a wall of washing machines", "the dryer vents"),
        Theme("cruise", "a cruise ship that is very slowly sinking", listOf("Lido", "Pool Deck", "Engine Room", "Buffet", "Casino", "Lifeboats", "Bridge", "Ballroom", "Galley", "Promenade", "Hull Breach", "Cabins", "Karaoke", "Waterslide", "Cargo", "Captain's Table"), "dumbwaiters", "the waterslide", "bulkhead doors", "a cracked porthole wall", "the air ducts"),
        Theme("volcano", "an observatory on an active volcano", listOf("Telescope", "Magma Pool", "Ash Garden", "Dome", "Seismograph", "Caldera", "Lava Tube", "Cooling Tower", "Control Room", "Ridge", "Basalt", "Crater", "Sulphur Vents", "Obsidian", "Geyser", "Heat Shield"), "magma rails", "the lava tube", "blast doors", "an obsidian crust", "the steam vents"),
        Theme("moonbase", "a greenhouse on the Moon", listOf("Hydroponics", "Airlock", "Solar Array", "Rover Bay", "Low Grav", "Dome", "Seed Vault", "Comms", "Oxygen", "Regolith", "Lab", "Docking", "Potato Farm", "Observation", "Cryo", "Launch Pad"), "mass drivers", "the low-gravity hop", "pressure doors", "a glass dome panel", "the air scrubbers"),
        Theme("cheese", "a medieval cheese market", listOf("Cheese Wheels", "Cellar", "Tannery", "Bell Tower", "Windmill", "Stables", "Well", "Ramparts", "Guildhall", "Smokehouse", "Dairy", "Hay Cart", "Goat Pen", "Brie Hall", "Chapel", "Moat"), "cheese catapults", "the hay drop", "oak doors", "a wall of stacked wheels", "the cellar drains"),
        Theme("mall", "an abandoned shopping mall", listOf("Food Court", "Escalators", "Arcade", "Fountain", "Cinema", "Parking", "Kiosk", "Atrium", "Toy Store", "Loading Dock", "Skylight", "Mannequins", "Ball Pit", "Pet Shop", "Photo Booth", "Elevator"), "pneumatic cash tubes", "the dead escalator", "shutter doors", "a shop window", "the service ducts"),
        Theme("trains", "a desert train graveyard", listOf("Boxcar", "Turntable", "Water Tower", "Signal Box", "Coal", "Platform", "Caboose", "Roundhouse", "Switchyard", "Depot", "Tracks", "Crane", "Cactus", "Tanker", "Mail Car", "Bridge"), "runaway mine carts", "the boxcar roof", "sliding car doors", "a rusted car wall", "the culverts"),
        Theme("kitchen", "a kitchen table the size of a city", listOf("Fork", "Teapot", "Breadbox", "Spoon", "Sugar Bowl", "Plate", "Napkin", "Saltshaker", "Crumbs", "Butter", "Cutlery", "Toast Rack", "Cereal", "Jam Jar", "Coaster", "Fruit Bowl"), "spring-loaded toasters", "the cereal box drop", "a cupboard door", "a gingerbread wall", "the drinking straws"),
        Theme("ski", "a snowed-in ski resort", listOf("Chairlift", "Lodge", "Sauna", "Halfpipe", "Gondola", "Ski Rental", "Hot Tub", "Avalanche", "Pistes", "Cabin", "Igloo", "Snowcat", "Fondue", "Ice Rink", "Summit Bar", "Moguls"), "ski jumps", "the black run", "lodge doors", "an ice wall", "the snow tunnels"),
        Theme("library", "a library where the books bite", listOf("Stacks", "Reading Room", "Archives", "Atlas", "Card Index", "Rare Books", "Quiet Zone", "Ladders", "Globes", "Returns", "Scriptorium", "Map Room", "Periodicals", "Vault", "Catalogue", "Dome"), "book lifts", "the ladder slide", "a hidden bookcase door", "a wall of encyclopaedias", "the heating ducts"),
        Theme("bathhouse", "a neon bathhouse", listOf("Cold Plunge", "Steam", "Lockers", "Massage", "Koi Pond", "Towels", "Mosaic", "Jacuzzi", "Boiler", "Tea Bar", "Bamboo", "Lanterns", "Salt Room", "Gong", "Rubber Ducks", "Waterfall"), "bubble tubes", "the waterfall", "paper doors", "a frosted glass wall", "the steam pipes"),
    )
    private val VAL_NAMES = listOf("Kelpwood", "Hollowtide", "Saltmoon", "Gloamstead", "Pinwheel", "Marrow", "Velvet", "Cinderhook", "Brineworks", "Tumbleweed", "Quillon", "Ferrous", "Moonquarry", "Gumdrop", "Lanternfall", "Driftwood",
        "Obsidia", "Carnivale", "Oxbow", "Zenith", "Noodlehaus", "Spindle", "Halcyon", "Fjordheim", "Murmur", "Bramble", "Coppice", "Mirrorfall", "Sorbet", "Tidewater", "Kiln", "Vesper")
    private val CS_NAMES = listOf("Cannery", "Sawmill", "Lighthouse", "Aqueduct", "Bazaar", "Observatory", "Monastery", "Funicular", "Laundromat", "Greenhouse", "Tollbooth", "Planetarium", "Salt Flats", "Brewery",
        "Waterpark", "Shipyard", "Archive", "Gondola", "Foundry", "Quarantine", "Distillery", "Carousel", "Pagoda", "Sewerworks", "Junkyard", "Hatchery", "Bathhouse", "Cheesemongers", "Mall", "Ferry")

    /** Names that already say where the map is. */
    private val NAME_THEME = mapOf("Laundromat" to "laundromat", "Bathhouse" to "bathhouse", "Mall" to "mall", "Cheesemongers" to "cheese", "Carousel" to "funfair",
        "Ferry" to "cruise", "Observatory" to "volcano", "Archive" to "library", "Waterpark" to "aquarium", "Noodlehaus" to "ramen", "Carnivale" to "funfair", "Gondola" to "ski")

    private const val SITE_R = 62
    private const val CORRIDOR = 26

    // ---------------------------------------------------------------------------------- generation

    private class B(rng: Rng, val theme: Theme) {
        val nodes = ArrayList<CustomMapNode>()
        val links = ArrayList<Triple<Int, Int, String>>()
        /** What each callout is in the layout (lobby, main, site, back, mid...), for diagnostics. */
        val roles = ArrayList<String>()
        var role = ""
        private val names = rng.shuffle(theme.callouts.toMutableList())
        private var ni = 0
        fun callout() = if (ni < names.size) names[ni++] else "Corner ${++ni}"
        fun node(name: String, sup: String, x: Int, y: Int, kind: String = "area", site: String = ""): Int { nodes.add(CustomMapNode(name, sup, x.coerceIn(40, 960), y.coerceIn(40, 960), kind, site)); roles.add(role); return nodes.size - 1 }
        fun link(a: Int, b: Int, type: String = "walk") { if (a != b && links.none { (it.first == a && it.second == b) || (it.first == b && it.second == a) }) links.add(Triple(a, b, type)) }
        fun pos(i: Int) = nodes[i].x to nodes[i].y
    }

    /** A new map for a shooter (null when no layout passed the checks, which practically never happens). */
    fun generate(titleId: String, rng: Rng, takenNames: Set<String>, season: Int, version: String): CustomMap? {
        val cs = titleId == "cs2"
        val name = rng.shuffle((if (cs) CS_NAMES else VAL_NAMES).toMutableList()).firstOrNull { it.lowercase() !in takenNames } ?: return null
        val theme = NAME_THEME[name]?.let { k -> THEMES.firstOrNull { it.key == k } } ?: rng.pick(THEMES)
        val sites = rng.weighted(listOf(2, 3, 4)) { when (it) { 2 -> 0.45; 3 -> 0.38; else -> 0.17 } }
        val id = "$titleId:f-${name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}"
        for (attempt in 0 until 80) {
            val features = ArrayList<String>()
            val b = build(rng, theme, cs, sites, features)
            if (b == null) { rejects.merge("could not join up", 1, Int::plus); continue }
            val links = b.links.map { "${it.first},${it.second},${it.third}" }
            val cm = CustomMap(titleId, id, name, theme.key, season, version, b.nodes.toList(), links, "", features)
            val why = plausible(b) ?: runCatching { compile(cm) }.getOrNull()?.let { m -> reject(m) } ?: "compile"
            if (why != "ok") { rejects.merge(why, 1, Int::plus); continue }
            val m = compile(cm)
            val letters = m.sites.map { it.id }.sorted()
            val blurb = "${count(letters.size)} sites (${letters.joinToString(", ")}) in ${theme.setting}."
            val agents = if (cs) null else rateAgents(m)
            return CustomMap(titleId, id, name, theme.key, season, version, cm.nodes, links, blurb, features, agents?.first.orEmpty(), agents?.second.orEmpty(), agents?.third.orEmpty())
        }
        return null
    }

    private fun count(n: Int) = when (n) { 2 -> "Two"; 3 -> "Three"; 4 -> "Four"; else -> "$n" }

    private class Mid(val g: Int, val low: Int, val mid: Int, val top: Int, val conn: Int, val toward: Int, val other: Int)

    /**
     * One layout: callouts first (spawns at the ends, sites in a row, each with a lobby, a main and a defenders'
     * back route; mid lanes between some sites; teleporter pads and ledges for the features), then spread apart,
     * then the corridors, each only where it passes no other callout and crosses no other corridor (required
     * routes have fallbacks; a layout that cannot be joined up is dropped).
     */
    private fun build(rng: Rng, theme: Theme, cs: Boolean, k: Int, features: MutableList<String>): B? {
        val b = B(rng, theme)
        val atkSup = if (cs) "T Side" else "Attacker Side"
        val defSup = if (cs) "CT Side" else "Defender Side"
        b.role = "spawn"
        val atk = b.node("Spawn", atkSup, 500 + rng.int(-70, 70), 930 + rng.int(-10, 10), "atk-spawn")
        val def = b.node("Spawn", defSup, 500 + rng.int(-70, 70), 85 + rng.int(-15, 15), "def-spawn")
        val xs = when (k) { 2 -> listOf(235, 765); 3 -> listOf(165, 500, 835); else -> listOf(115, 372, 628, 885) }.map { it + rng.int(-30, 30) }
        val letters = "ABCD".take(k).let { if (rng.chance(0.5)) it else it.reversed() }
        val site = IntArray(k); val main = IntArray(k); val lobby = IntArray(k); val back = IntArray(k)
        for (i in 0 until k) {
            val l = letters[i].toString()
            val sx = xs[i]
            val inner = k == 4 && (i == 1 || i == 2)
            val sy = (if (inner) 245 else if (k == 3 && i == 1) 255 else 285) + rng.int(-30, 35)
            b.role = "site"; site[i] = b.node("Site", l, sx, sy, "site", l)
            b.role = "main"; main[i] = b.node(if (rng.chance(0.45)) "Main" else b.callout(), l, sx + rng.int(-35, 35), sy + rng.int(175, 215))
            b.role = "lobby"; lobby[i] = b.node(if (rng.chance(0.35)) "Lobby" else b.callout(), l, (sx * 0.8 + 500 * 0.2).roundToInt() + rng.int(-30, 30), 740 + rng.int(-25, 20))
            val central = kotlin.math.abs(sx - 500) < 200
            val bx = if (central) sx + (if (sx < 500 || (sx == 500 && rng.chance(0.5))) -1 else 1) * rng.int(110, 150) else sx + (if (sx < 500) 1 else -1) * rng.int(30, 80)
            val by = if (central) sy - rng.int(55, 85) else sy - rng.int(115, 145)
            b.role = "back"; back[i] = b.node(if (rng.chance(0.4)) rng.pick(listOf("Heaven", "Back Site", "Link", "Rafters")) else b.callout(), l, bx, by)
        }
        val gaps = (0 until k - 1).toList()
        val midGaps = when (k) { 2 -> listOf(0); 3 -> rng.sample(gaps, rng.int(1, 2)); else -> listOf(1) + (if (rng.chance(0.4)) listOf(rng.pick(listOf(0, 2))) else emptyList()) }
        val mids = ArrayList<Mid>()
        for (g in midGaps.sorted()) {
            val x = (xs[g] + xs[g + 1]) / 2 + rng.int(-25, 25)
            b.role = "midlow"; val mb = b.node(b.callout(), "Mid", x + rng.int(-20, 20), 640 + rng.int(-20, 25))
            b.role = "mid"; val m = b.node(if (rng.chance(0.4)) "Mid" else b.callout(), "Mid", x + rng.int(-25, 25), 475 + rng.int(-25, 25))
            b.role = "midtop"; val mt = b.node(b.callout(), "Mid", x + rng.int(-25, 25), 290 + rng.int(-25, 20))
            val (toward, other) = if (rng.chance(0.5)) g to g + 1 else g + 1 to g
            val sp = b.pos(site[toward]); val mp = b.pos(m)
            b.role = "conn"; val c = b.node(if (rng.chance(0.4)) "Connector" else b.callout(), letters[toward].toString(), (sp.first + mp.first) / 2 + rng.int(-15, 15), (sp.second + mp.second) / 2 + 20 + rng.int(-15, 15))
            mids.add(Mid(g, mb, m, mt, c, toward, other))
        }
        // Features: which ones, and the callouts they need.
        val kinds = ArrayList<String>()
        if (k >= 2 && rng.chance(if (cs) 0.12 else 0.4)) kinds.add("teleport")
        if (rng.chance(if (cs) 0.35 else 0.45)) kinds.add("drop")
        if (rng.chance(0.35)) kinds.add("door")
        if (rng.chance(0.3)) kinds.add("breakable")
        if (rng.chance(if (cs) 0.4 else 0.15)) kinds.add("vent")
        if (kinds.isEmpty()) kinds.add(rng.pick(listOf("drop", "door", "breakable")))
        val chosen = rng.shuffle(kinds).take(3)
        val tele = if ("teleport" in chosen) rng.sample((0 until k).toList(), 2) else emptyList()
        val pads = tele.map { i -> val p = b.pos(lobby[i]); b.role = "pad"; b.node("Teleporter", letters[i].toString(), p.first + (if (p.first < 500) -95 else 95), p.second + 20) }
        val dropSite = if ("drop" in chosen) rng.int(0, k - 1) else -1
        val ledge = if (dropSite >= 0) { val sp = b.pos(site[dropSite]); val mp = b.pos(main[dropSite]); b.role = "ledge"; b.node(if (rng.chance(0.5)) "Ledge" else b.callout(), letters[dropSite].toString(), sp.first + (if (sp.first < 500) -95 else 95), (sp.second + mp.second) / 2 + rng.int(-10, 15)) } else -1

        relax(b)

        // Corridors.
        fun ok(u: Int, v: Int): Boolean {
            if (u == v || d(b, u, v) > 430) return false
            for (w in b.nodes.indices) if (w != u && w != v && segDist(b.nodes[w], b.nodes[u], b.nodes[v]) < 50) return false
            for (l in b.links) {
                if (!carved(l.third) || l.first == u || l.first == v || l.second == u || l.second == v) continue
                if (cross(b.nodes[u], b.nodes[v], b.nodes[l.first], b.nodes[l.second])) return false
            }
            return true
        }
        fun tryLink(u: Int, v: Int, type: String = "walk"): Boolean { if (type != "teleport" && !ok(u, v)) return false; b.link(u, v, type); return true }
        fun need(vararg options: Pair<Int, Int>): Boolean = options.any { (u, v) -> tryLink(u, v) }
        for (i in 0 until k) if (!need(lobby[i] to main[i]) || !need(main[i] to site[i]) || !need(site[i] to back[i])) return null
        // Attackers' spawn to every lobby (or through the neighbouring lobby).
        for (i in (0 until k).sortedBy { d(b, atk, lobby[it]) }) {
            val near = (0 until k).filter { it != i && b.links.any { l -> (l.first == atk && l.second == lobby[it]) } }.sortedBy { d(b, lobby[i], lobby[it]) }
            if (!need(atk to lobby[i], *near.map { lobby[i] to lobby[it] }.toTypedArray())) return null
        }
        for (md in mids) {
            if (!need(md.low to md.mid) || !need(md.mid to md.top)) return null
            if (!need(lobby[md.g] to md.low, lobby[md.g + 1] to md.low, atk to md.low)) return null
            tryLink(md.low, if (b.links.any { it.second == md.low && it.first == lobby[md.g] }) lobby[md.g + 1] else lobby[md.g])
            // Mid opens onto the site itself or onto its main (fewer ways straight onto a site favour the defence).
            val onto = if (rng.chance(0.5)) arrayOf(md.conn to site[md.toward], md.conn to main[md.toward]) else arrayOf(md.conn to main[md.toward], md.conn to site[md.toward])
            if (!need(md.mid to md.conn) || !need(*onto)) return null
        }
        // Defenders' spawn to every back route and mid top (or through a neighbour already joined to it).
        val toDef = (back.toList() + mids.map { it.top }).sortedBy { d(b, def, it) }
        for (u in toDef) {
            val joined = toDef.filter { it != u && b.links.any { l -> l.first == it && l.second == def || l.second == it && l.first == def } }.sortedBy { d(b, u, it) }
            if (!need(def to u, *joined.map { u to it }.toTypedArray())) return null
        }
        for (md in mids) tryLink(md.top, back[md.other]) || tryLink(md.top, site[md.other])
        for (g in gaps) if (mids.none { it.g == g }) {
            if (rng.chance(0.7)) tryLink(lobby[g], lobby[g + 1])
            if (rng.chance(0.5)) tryLink(back[g], back[g + 1])
        }
        // Features.
        val t = b.theme
        if (tele.size == 2) {
            if (!need(lobby[tele[0]] to pads[0]) || !need(lobby[tele[1]] to pads[1])) return null
            tryLink(pads[0], main[tele[1]], "teleport"); tryLink(pads[1], main[tele[0]], "teleport")
            features.add("One-way ${t.teleport} between ${letters[tele[0]]} and ${letters[tele[1]]}")
        }
        if (ledge >= 0) {
            if (!need(main[dropSite] to ledge)) return null
            if (!tryLink(ledge, site[dropSite], "drop")) return null
            features.add("A one-way drop (${t.drop}) into ${letters[dropSite]} Site")
        }
        if ("door" in chosen) {
            val cand = b.links.withIndex().filter { (_, l) -> l.third == "walk" && b.nodes[l.first].kind == "area" && b.nodes[l.second].kind == "area" && (b.nodes[l.first].sup != b.nodes[l.second].sup || b.nodes[l.first].sup == "Mid") }
            if (cand.isNotEmpty()) {
                val (i, l) = rng.pick(cand)
                b.links[i] = Triple(l.first, l.second, "door")
                features.add("${t.door.replaceFirstChar { it.uppercase() }} between ${label(b, l.first)} and ${label(b, l.second)} (they take a moment to open)")
            }
        }
        if ("breakable" in chosen) {
            val pairs = (mids.flatMap { md -> (0 until k).map { md.mid to main[it] } } + (0 until k - 1).map { lobby[it] to lobby[it + 1] } + (0 until k - 1).map { main[it] to main[it + 1] })
                .filter { (u, v) -> b.links.none { (it.first == u && it.second == v) || (it.first == v && it.second == u) } && d(b, u, v) < 330 }
            rng.shuffle(pairs.toMutableList()).firstOrNull { (u, v) -> tryLink(u, v, "breakable") }?.let { (u, v) ->
                features.add("${t.breakable.replaceFirstChar { it.uppercase() }} between ${label(b, u)} and ${label(b, v)} breaks open")
            }
        }
        if ("vent" in chosen) {
            val order = (0 until k).sortedBy { b.nodes[site[it]].x }
            rng.shuffle(order.zipWithNext().toMutableList()).firstOrNull { (i, j) -> tryLink(site[i], site[j], "vent") }?.let { (i, j) ->
                features.add("Crawl through ${t.vent} between ${letters[i]} Site and ${letters[j]} Site")
            }
        }
        return b
    }

    /** Callouts that ended up too close are pushed apart (spawns stay; sites move least). */
    private fun relax(b: B) {
        val n = b.nodes.size
        val x = DoubleArray(n) { b.nodes[it].x.toDouble() }; val y = DoubleArray(n) { b.nodes[it].y.toDouble() }
        fun weight(i: Int) = when (b.nodes[i].kind) { "atk-spawn", "def-spawn" -> 0.0; "site" -> 0.4; else -> 1.0 }
        for (iter in 0 until 40) {
            var moved = false
            for (u in 0 until n) for (v in u + 1 until n) {
                val dx = x[v] - x[u]; val dy = y[v] - y[u]; val dd = hypot(dx, dy)
                if (dd >= 90) continue
                val wu = weight(u); val wv = weight(v); if (wu + wv == 0.0) continue
                val push = (90 - dd) / (wu + wv); val ux = if (dd < 1e-6) 1.0 else dx / dd; val uy = if (dd < 1e-6) 0.0 else dy / dd
                x[u] -= ux * push * wu; y[u] -= uy * push * wu; x[v] += ux * push * wv; y[v] += uy * push * wv
                moved = true
            }
            for (i in 0 until n) { x[i] = x[i].coerceIn(45.0, 955.0); y[i] = y[i].coerceIn(45.0, 955.0) }
            if (!moved) break
        }
        for (i in 0 until n) b.nodes[i] = b.nodes[i].let { CustomMapNode(it.name, it.sup, x[i].roundToInt(), y[i].roundToInt(), it.kind, it.site) }
    }

    private fun label(b: B, i: Int) = b.nodes[i].let { if (it.sup == "Mid" && it.name == "Mid") "Mid" else "${it.sup} ${it.name}" }
    private fun d(b: B, u: Int, v: Int) = hypot((b.nodes[u].x - b.nodes[v].x).toDouble(), (b.nodes[u].y - b.nodes[v].y).toDouble())

    // Vents are crawlspaces with grates (carved narrow so nobody stands in a wall); teleporters are not walked.
    private fun carved(type: String) = type != "teleport"

    /** Layout sanity before compiling: callouts apart, corridors clear of other callouts, no crossing corridors. */
    /** Why generated layouts were rejected (diagnostics for the tests). */
    val rejects = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Null when the layout is plausible, else the reason it is not. */
    private fun plausible(b: B): String? {
        val n = b.nodes.size
        for (u in 0 until n) for (v in u + 1 until n) if (d(b, u, v) < 72) return "callouts too close"
        val walk = b.links.filter { carved(it.third) }
        for (l in walk) {
            if (d(b, l.first, l.second) > 430) return "corridor too long ${b.roles[l.first]}-${b.roles[l.second]}"
            for (u in 0 until n) if (u != l.first && u != l.second && segDist(b.nodes[u], b.nodes[l.first], b.nodes[l.second]) < 46) return "corridor ${b.roles[l.first]}-${b.roles[l.second]} through ${b.roles[u]}"
        }
        for (i in walk.indices) for (j in i + 1 until walk.size) {
            val a = walk[i]; val c = walk[j]
            if (a.first == c.first || a.first == c.second || a.second == c.first || a.second == c.second) continue
            if (cross(b.nodes[a.first], b.nodes[a.second], b.nodes[c.first], b.nodes[c.second])) return "corridors cross"
        }
        return null
    }

    private fun segDist(p: CustomMapNode, a: CustomMapNode, b: CustomMapNode): Double {
        val dx = (b.x - a.x).toDouble(); val dy = (b.y - a.y).toDouble(); val l = dx * dx + dy * dy
        val t = if (l > 0) (((p.x - a.x) * dx + (p.y - a.y) * dy) / l).coerceIn(0.0, 1.0) else 0.0
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    private fun cross(p1: CustomMapNode, p2: CustomMapNode, p3: CustomMapNode, p4: CustomMapNode): Boolean {
        fun o(a: CustomMapNode, b: CustomMapNode, c: CustomMapNode) = Math.signum(((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)).toDouble())
        return o(p1, p2, p3) * o(p1, p2, p4) < 0 && o(p3, p4, p1) * o(p3, p4, p2) < 0
    }

    /**
     * The checks every map passes (as ShooterMapsTest asserts for the real ones): every callout reaches every site
     * and both spawns, nothing stands in a wall, corridors are open, and neither side is far too fast to the sites.
     */
    fun valid(m: Maps.Shooter): Boolean = reject(m) == "ok"

    /** "ok", or the first check the map fails. */
    fun reject(m: Maps.Shooter): String {
        val n = m.nodes.size
        if (m.sites.size !in 2..4) return "sites"
        for (u in 0 until n) {
            for (s in m.sites) if (m.path(u, s.node).isEmpty()) return "site unreachable"
            if (m.path(u, m.atk).isEmpty() || m.path(u, m.def).isEmpty()) return "spawn unreachable"
            if (m.path(m.atk, u).isEmpty() && m.path(m.def, u).isEmpty()) return "callout unreachable"
        }
        for (nd in m.nodes) if (ShooterGrid.blocked(m, nd.pos.x, nd.pos.y)) return "callout in a wall"
        for (l in m.links) if (l.type == "walk" || l.type == "door") {
            val a = m.nodes[l.from].pos; val b = m.nodes[l.to].pos
            if ((1 until 200).any { k -> val f = k / 200f; ShooterGrid.blocked(m, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f) }) return "corridor in a wall"
        }
        for (s in m.sites) {
            val ra = pathLen(m, m.atk, s.node); val rd = pathLen(m, m.def, s.node)
            val q = rd / ra
            if (q < 0.38f) return "defenders too close to ${s.id}"
            if (q > 0.92f) return "attackers too close to ${s.id}"
        }
        return "ok"
    }

    fun pathLen(m: Maps.Shooter, a: Int, b: Int): Float { val p = m.path(a, b); var l = 0f; for (k in 1 until p.size) { val l2 = m.linkBetween(p[k - 1], p[k]); if (l2?.type != "teleport") l += (m.nodes[p[k]].pos - m.nodes[p[k - 1]].pos).getDistance() }; return if (p.isEmpty()) 9f else l }

    // ---------------------------------------------------------------------------------- compiling

    private val compiled = java.util.concurrent.ConcurrentHashMap<String, Maps.Shooter>()

    /** The engine's map (graph, sites, spawns, wall raster) for a fictional layout; cached by content. */
    fun compile(cm: CustomMap): Maps.Shooter {
        val key = cm.id + "|" + cm.links.joinToString(";") + "|" + cm.nodes.joinToString(";") { "${it.x},${it.y},${it.kind}" }
        compiled[key]?.let { return it }
        val nodes = cm.nodes.map { Maps.Node(it.name, it.sup, Offset(it.x / 1000f, it.y / 1000f), it.kind) }
        val links = cm.links.map { s -> val p = s.split(','); Maps.Link(p[0].toInt(), p[1].toInt(), p.getOrElse(2) { "walk" }) }
        val n = 100
        val open = BooleanArray(n * n)
        for (gy in 0 until n) for (gx in 0 until n) {
            val px = gx * 10 + 5.0; val py = gy * 10 + 5.0
            var ok = cm.nodes.any { nd -> val r = when (nd.kind) { "site" -> SITE_R; "area" -> CORRIDOR + 14; else -> 55 }; (px - nd.x) * (px - nd.x) + (py - nd.y) * (py - nd.y) <= r * r }
            if (!ok) ok = links.any { l -> carved(l.type) && segDist(px, py, cm.nodes[l.from], cm.nodes[l.to]) <= (if (l.type == "vent") 14 else CORRIDOR) }
            open[gy * n + gx] = ok
        }
        val walls = ArrayList<FloatArray>()
        for (y in 0 until n) {
            var x = 0
            while (x < n) {
                if (open[y * n + x]) { x++; continue }
                val x0 = x
                while (x < n && !open[y * n + x]) x++
                walls.add(floatArrayOf(x0 / 100f, y / 100f, (x - x0) / 100f, 0.01f))
            }
        }
        val sites = cm.nodes.withIndex().filter { it.value.kind == "site" }.map { (i, nd) -> Maps.Site(nd.site.ifBlank { nd.sup }, i, Offset(nd.x / 1000f, nd.y / 1000f), SITE_R / 1000f) }.sortedBy { it.id }
        val atk = cm.nodes.indexOfFirst { it.kind == "atk-spawn" }.coerceAtLeast(0)
        val def = cm.nodes.indexOfFirst { it.kind == "def-spawn" }.coerceAtLeast(0)
        val m = Maps.Shooter(cm.name, nodes, links, sites, atk, def, walls, "Fictional map (${cm.version})", holdScale(cm.titleId, sites.size))
        compiled[key] = m
        return m
    }

    /** Calibration of fictional maps (MapForgeTest.fictionalMapsPlay): a holding defender's edge by game and number of sites. */
    internal fun holdScale(titleId: String, sites: Int): Float {
        System.getenv("UEM_HS")?.toFloatOrNull()?.let { return it }
        // Measured on 24 generated maps per game (4 games each) at scales 1, 2 and 3.5 and interpolated to about half
        // the rounds for the attackers (CS2 a little CT-sided, as the real pool; four sites a little attack-sided).
        return if (titleId == "cs2") when (sites) { 2 -> 1.3f; 3 -> 1.6f; else -> 3.0f } else when (sites) { 2 -> 2.9f; 3 -> 3.0f; else -> 4.0f }
    }

    private fun segDist(px: Double, py: Double, a: CustomMapNode, b: CustomMapNode): Double {
        val dx = (b.x - a.x).toDouble(); val dy = (b.y - a.y).toDouble(); val l = dx * dx + dy * dy
        val t = if (l > 0) (((px - a.x) * dx + (py - a.y) * dy) / l).coerceIn(0.0, 1.0) else 0.0
        return hypot(px - (a.x + t * dx), py - (a.y + t * dy))
    }

    // ---------------------------------------------------------------------------------- what a layout favours

    /** Layout traits: sites, longest open sightline between callouts, defenders' rotation between sites, special links. */
    class Traits(val sites: Int, val longest: Float, val rotation: Float, val atkToSite: Float, val defToSite: Float, val specials: Set<String>)

    private val traitCache = java.util.concurrent.ConcurrentHashMap<Maps.Shooter, Traits>()
    fun traits(m: Maps.Shooter): Traits = traitCache.getOrPut(m) {
        var longest = 0f
        for (u in m.nodes.indices) for (v in u + 1 until m.nodes.size) {
            val a = m.nodes[u]; val b = m.nodes[v]
            if (a.level != b.level) continue
            val dd = (a.pos - b.pos).getDistance()
            if (dd > longest && ShooterGrid.clear(m, a.pos.x, a.pos.y, b.pos.x, b.pos.y)) longest = dd
        }
        val pairs = m.sites.indices.flatMap { i -> (i + 1 until m.sites.size).map { i to it } }
        val rot = if (pairs.isEmpty()) 0f else pairs.map { (i, j) -> pathLen(m, m.sites[i].node, m.sites[j].node) }.average().toFloat()
        Traits(m.sites.size, longest, rot, m.sites.map { pathLen(m, m.atk, it.node) }.average().toFloat(), m.sites.map { pathLen(m, m.def, it.node) }.average().toFloat(), m.links.map { it.type }.toSet() - "walk")
    }

    /** How much an agent's kit likes: long sightlines, tight corners, many sites / long rotations, drops, teleporters, doors and breakables, wide sites. */
    private val AFF: Map<String, DoubleArray> = mapOf(
        "jett" to d(1.0, 0.2, 0.3, 1.0, 0.2, 0.0, 0.6), "raze" to d(0.1, 1.0, 0.3, 0.6, 0.8, 0.7, 0.1), "neon" to d(0.1, 0.6, 1.0, 0.1, 0.3, 0.2, 0.3), "phoenix" to d(0.1, 0.9, 0.2, 0.1, 0.3, 0.4, 0.1),
        "reyna" to d(0.4, 0.4, 0.2, 0.1, 0.1, 0.1, 0.3), "yoru" to d(0.2, 0.5, 0.6, 0.2, 1.0, 0.3, 0.2), "iso" to d(0.5, 0.6, 0.2, 0.0, 0.1, 0.2, 0.4), "waylay" to d(0.3, 0.6, 0.6, 0.8, 0.3, 0.3, 0.3), "hush" to d(0.3, 0.7, 0.4, 0.2, 0.3, 0.3, 0.2),
        "sova" to d(1.0, 0.1, 0.6, 0.2, 0.1, 0.1, 1.0), "fade" to d(0.4, 0.7, 0.6, 0.3, 0.4, 0.5, 0.4), "breach" to d(0.1, 1.0, 0.2, 0.1, 0.4, 1.0, 0.1), "skye" to d(0.3, 0.7, 0.5, 0.2, 0.8, 0.4, 0.3),
        "kay-o" to d(0.7, 0.5, 0.4, 0.2, 0.3, 0.6, 0.6), "gekko" to d(0.3, 0.7, 0.4, 0.2, 0.5, 0.5, 0.4), "tejo" to d(0.5, 0.5, 0.5, 0.2, 0.3, 0.4, 0.7),
        "omen" to d(0.3, 0.5, 0.9, 0.8, 0.6, 0.3, 0.3), "viper" to d(0.7, 0.4, 0.3, 0.2, 0.5, 0.3, 1.0), "astra" to d(0.4, 0.3, 1.0, 0.1, 0.2, 0.2, 0.6), "brimstone" to d(0.1, 1.0, 0.1, 0.0, 0.8, 0.4, 0.1),
        "harbor" to d(0.6, 0.3, 0.3, 0.0, 0.1, 0.1, 1.0), "clove" to d(0.3, 0.6, 0.6, 0.3, 0.3, 0.3, 0.3), "miks" to d(0.4, 0.5, 0.5, 0.2, 0.3, 0.3, 0.5),
        "killjoy" to d(0.6, 0.4, 0.7, 0.1, 0.2, 0.3, 0.7), "cypher" to d(0.3, 0.7, 0.8, 0.2, 0.6, 0.4, 0.3), "chamber" to d(1.0, 0.1, 0.4, 0.3, 0.3, 0.1, 0.7), "sage" to d(0.2, 0.7, 0.3, 0.1, 0.2, 0.5, 0.2),
        "deadlock" to d(0.3, 0.8, 0.2, 0.1, 0.2, 0.5, 0.2), "vyse" to d(0.4, 0.6, 0.7, 0.2, 0.3, 0.6, 0.5), "veto" to d(0.4, 0.5, 0.5, 0.2, 0.3, 0.4, 0.4),
    )
    private fun d(vararg x: Double) = x

    private fun features(tr: Traits): DoubleArray {
        val long = ((tr.longest - 0.30f) / 0.30f).coerceIn(0f, 1f).toDouble()
        val many = (((tr.sites - 2) / 2.0) * 0.6 + ((tr.rotation - 0.35f) / 0.4f).coerceIn(0f, 1f) * 0.4).coerceIn(0.0, 1.0)
        return doubleArrayOf(long, 1 - long, many, if ("drop" in tr.specials) 1.0 else 0.0, if ("teleport" in tr.specials) 1.0 else 0.0,
            if ("door" in tr.specials || "breakable" in tr.specials) 1.0 else if ("vent" in tr.specials) 0.5 else 0.0, long * 0.7 + (if (tr.sites >= 3) 0.3 else 0.0))
    }

    /** VALORANT: the agents (short ids) a layout favours (staples) and punishes, and the class a flex player fills there. */
    fun rateAgents(m: Maps.Shooter): Triple<List<String>, List<String>, String> {
        val f = features(traits(m))
        val agents = runCatching { dev.uem.em.titles.Catalogue.get("valorant").characters }.getOrNull().orEmpty()
        val scored = agents.mapNotNull { c -> val s = c.id.substringAfter('-'); AFF[s]?.let { a -> Triple(s, c.cls, a.indices.sumOf { a[it] * f[it] }) } }
        val byCls = scored.groupBy { it.second }.mapValues { (_, v) -> v.sortedByDescending { it.third } }
        val top = byCls.values.flatMap { it.take(2) }.sortedByDescending { it.third }
        val staples = (byCls.values.mapNotNull { it.firstOrNull() } + top).distinct().take(7).map { it.first }
        val weak = (byCls.values.mapNotNull { it.lastOrNull() } + scored.sortedBy { it.third }).distinct().filter { it.first !in staples }.take(5).map { it.first }
        val flex = byCls.maxByOrNull { (_, v) -> v.getOrNull(1)?.third ?: 0.0 }?.key ?: "Initiator"
        return Triple(staples, weak, flex)
    }
}

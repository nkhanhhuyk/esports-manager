package dev.uem.em.sim

import dev.uem.em.core.Rng
import dev.uem.em.titles.Phase
import dev.uem.em.titles.TitleDef
import dev.uem.em.ui.Maps
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Weapons (public values, approximate; patches can change them): damage per body / head hit, rounds per second. */
class Weapon(val id: String, val name: String, val cost: Int, val rof: Float, val body: Float, val head: Float, val range: Float, val armorPen: Float, val spread: Float, val sniper: Boolean = false)

object Weapons {
    val VAL = listOf(
        Weapon("classic", "Classic", 0, 6.75f, 26f, 78f, 0.30f, 1f, 0.55f), Weapon("ghost", "Ghost", 500, 6.75f, 30f, 105f, 0.32f, 1f, 0.6f),
        Weapon("sheriff", "Sheriff", 800, 4f, 55f, 159f, 0.36f, 1f, 0.55f), Weapon("spectre", "Spectre", 1600, 13.3f, 26f, 78f, 0.30f, 1f, 0.6f),
        Weapon("bulldog", "Bulldog", 2050, 10f, 35f, 116f, 0.40f, 1f, 0.62f), Weapon("vandal", "Vandal", 2900, 9.75f, 40f, 160f, 0.50f, 1f, 0.66f),
        Weapon("phantom", "Phantom", 2900, 11f, 39f, 156f, 0.42f, 1f, 0.68f), Weapon("operator", "Operator", 4700, 0.6f, 150f, 255f, 0.65f, 1f, 0.86f, true),
    )
    val CS2 = listOf(
        Weapon("glock", "Glock-18", 200, 6.6f, 30f, 116f, 0.28f, 0.47f, 0.5f), Weapon("usp", "USP-S", 200, 5.9f, 35f, 140f, 0.30f, 0.505f, 0.58f),
        Weapon("deagle", "Desert Eagle", 700, 3.7f, 53f, 212f, 0.38f, 0.93f, 0.5f), Weapon("mp9", "MP9", 1250, 14.3f, 26f, 104f, 0.28f, 0.6f, 0.58f),
        Weapon("galil", "Galil AR", 1800, 11.1f, 30f, 120f, 0.40f, 0.775f, 0.6f), Weapon("famas", "FAMAS", 1950, 11.1f, 30f, 120f, 0.40f, 0.7f, 0.6f),
        Weapon("ak47", "AK-47", 2700, 10f, 36f, 143f, 0.52f, 0.775f, 0.65f), Weapon("m4a1s", "M4A1-S", 2900, 10f, 38f, 152f, 0.50f, 0.7f, 0.68f),
        Weapon("awp", "AWP", 4750, 0.68f, 115f, 459f, 0.70f, 0.975f, 0.88f, true),
    )
    fun of(t: String) = if (t == "cs2") CS2 else VAL
    fun byId(t: String, id: String) = of(t).first { it.id == id }
}

/**
 * One shooter round on the map's node graph (assets/maps.json + maps_topology.json): players walk
 * legal paths (one-way drops, teleporters, vents), see each other only with line of sight through
 * the map's walls (smokes and walls block it), and shoot with their weapon's fire rate, damage,
 * range and accuracy. Utility changes the round: smokes and walls block sight, flashes blind,
 * recon reveals, mollies burn, slows/stuns/traps slow and reveal. Attackers pick a site and a plan
 * (execute, split, default, lurk) from their IGL; defenders hold, rotate on information and
 * retake. The spike / bomb is planted, ticks and is defused in real time.
 */
class ShooterRound(
    val t: TitleDef, val map: Maps.Shooter, val teams: List<TeamCtx>, val attack: Int, val rng: Rng,
    val weapons: Array<Array<Weapon>>, val armor: Array<IntArray>, val utility: Array<Array<MutableList<String>>>, val kit: Array<BooleanArray>,
    val timeBase: Int, val rec: Recorder?, val host: Host,
) {
    interface Host {
        fun kill(ks: Int, killer: PlayerCtx, victim: PlayerCtx, assister: PlayerCtx?, headshot: Boolean, where: Int, damage: Double)
        fun plant(p: PlayerCtx)
        fun defuse(p: PlayerCtx)
        fun utility(p: PlayerCtx)
        fun damage(p: PlayerCtx, d: Double)
    }

    class Result(val winner: Int, val planted: Boolean, val defused: Boolean, val seconds: Int, val site: Int)

    val cs = t.id == "cs2"
    val ROUND = if (cs) 115f else 100f
    val BOMB = if (cs) 40f else 45f
    val PLANT = if (cs) 3.2f else 4f
    val DT = 0.5f
    val SPEED = 0.042f
    val def = 1 - attack

    inner class U(val side: Int, val i: Int, val pc: PlayerCtx) {
        val gi = side * 5 + i
        var x = 0f; var y = 0f
        var node = 0
        var path: List<Int> = emptyList(); var pathIdx = 0
        var hp = 100f; var arm = armor[side].getOrElse(i) { 0 }.toFloat()
        var alive = true
        var wait = 0f
        var blindUntil = -1f; var slowUntil = -1f; var revealedUntil = -1f
        var holding = side != attack
        var seenEnemyAt = -9f
        var target = -1
        var channel = 0f // planting / defusing progress
        val w get() = weapons[side][i]
        val util get() = utility[side][i]
        fun z(p: Phase) = ((pc.phase[p.ordinal] - 68.0) / 14.0).toFloat().coerceIn(-2.5f, 2.5f)
        val aim = 0.5f + 0.08f * z(Phase.AIM)
        val hsSkill = ((pc.p.attrs.getOrElse(74) { 60.0 } - 60.0) / 100.0).toFloat()
        val move = 0.04f * z(Phase.MOVEMENT)
        val utilSkill = 1f + 0.1f * z(Phase.UTILITY)
        val entry = 0.05f * z(Phase.ENTRY)
        val clutch = 0.05f * z(Phase.CLUTCH)
    }

    val units = (0..1).flatMap { s -> teams[s].players.mapIndexed { i, pc -> U(s, i, pc) } }
    val bySide = arrayOf(units.filter { it.side == 0 }, units.filter { it.side == 1 })
    var now = 0f
    var planted = false; var plantAt = 0f; var plantX = 0f; var plantY = 0f
    var defused = false
    var site = 0
    val siteNode get() = map.sites[site].node
    val dmgBy = HashMap<Int, HashMap<Int, Float>>() // victim gi -> attacker gi -> damage
    var firstKill = true

    /** Areas that block sight (smokes, walls) and hurt (mollies): x, y, r, until, kind(0 smoke 1 molly 2 wall), side. */
    val areas = ArrayList<FloatArray>()

    fun ev(e: Ev) { rec?.events?.add(e) }
    fun T() = timeBase + now.toInt()
    fun pos(n: Int) = map.nodes[n].pos
    fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float { val dx = ax - bx; val dy = ay - by; return sqrt(dx * dx + dy * dy) }
    fun packed(x: Float, y: Float) = MobaEngine.packPos(x * 1000f, y * 1000f)

    // Line of sight through the map's walls (grid sampling) and sight-blocking utility.
    fun los(a: U, b: U): Boolean {
        val d = dist(a.x, a.y, b.x, b.y)
        if (d < 0.035f) return true
        for (ar in areas) if (ar[3] > now && (ar[4] == 0f || ar[4] == 2f)) {
            if (segDist(ar[0], ar[1], a.x, a.y, b.x, b.y) < ar[2]) return false
        }
        val steps = (d / 0.012f).toInt().coerceAtLeast(2)
        for (k in 1 until steps) {
            val f = k.toFloat() / steps
            if (ShooterGrid.blocked(map, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)) return false
        }
        return true
    }
    fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        if (l2 < 1e-6f) return dist(px, py, ax, ay)
        val tt = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return dist(px, py, ax + tt * dx, ay + tt * dy)
    }

    // ---------------------------------------------------------------------------------- setup
    fun setup() {
        val igl = teams[attack].phase[Phase.IGL.ordinal]
        // Site choice: weaker defended sites slightly favoured; better IGLs vary more.
        site = rng.int(0, map.sites.size - 1)
        val plan = rng.weighted(listOf(0, 1, 2)) { when (it) { 0 -> 0.45; 1 -> 0.3 + (igl - 60) / 200; else -> 0.25 } } // execute, split, default
        val atkSpawn = map.atk; val defSpawn = map.def
        for (u in bySide[attack]) { u.node = atkSpawn; val p = pos(atkSpawn); u.x = p.x + (u.i - 2) * 0.018f; u.y = p.y + (u.i % 2) * 0.018f }
        for (u in bySide[def]) { u.node = defSpawn; val p = pos(defSpawn); u.x = p.x + (u.i - 2) * 0.018f; u.y = p.y + (u.i % 2) * 0.018f }
        // Defenders: anchors on each site (site node and its approach), the rest mid / rotating.
        val holds = ArrayList<Int>()
        for (s in map.sites) { holds.add(s.node); map.links.filter { it.from == s.node || it.to == s.node }.map { if (it.from == s.node) it.to else it.from }.minByOrNull { pathLen(it, atkSpawn) }?.let { holds.add(it) } }
        val mid = map.nodes.indices.filter { map.nodes[it].sup.equals("Mid", true) }.minByOrNull { pathLen(it, atkSpawn) + pathLen(it, defSpawn) }
        if (mid != null) holds.add(mid)
        bySide[def].forEachIndexed { k, u -> go(u, holds[(k * 2 + (if (k >= 3) 1 else 0)) % holds.size]) }
        // Attackers: by plan.
        val siteN = siteNode
        val staging = staging(siteN)
        val other = (site + 1) % map.sites.size
        bySide[attack].forEachIndexed { k, u ->
            val lurk = (u.pc.roleId == "lurker" || u.pc.roleId == "sentinel") && map.sites.size > 1 && rng.chance(0.5)
            when {
                lurk -> go(u, staging(map.sites[other].node))
                plan == 1 && k >= 3 && mid != null -> { go(u, mid); u.wait = 0f }
                plan == 2 -> go(u, if (k % 2 == 0) staging else staging(map.sites[other].node))
                else -> go(u, staging)
            }
            u.holding = false
        }
    }

    fun pathLen(a: Int, b: Int): Float { val p = map.path(a, b); var l = 0f; for (k in 1 until p.size) l += dist(pos(p[k - 1]).x, pos(p[k - 1]).y, pos(p[k]).x, pos(p[k]).y); return if (p.isEmpty()) 9f else l }
    /** The node just before the site on the attackers' path (where they gather and use utility). */
    fun staging(siteN: Int): Int { val p = map.path(map.atk, siteN); return if (p.size >= 3) p[p.size - 2] else siteN }
    fun go(u: U, target: Int) { u.path = map.path(u.node, target).ifEmpty { listOf(u.node, target) }; u.pathIdx = 0 }
    fun atEnd(u: U) = u.pathIdx >= u.path.size - 1

    fun step(u: U) {
        if (u.path.isEmpty() || atEnd(u)) return
        var left = SPEED * DT * (if (u.slowUntil > now) 0.5f else 1f) * (1f + u.move)
        while (left > 0f && !atEnd(u)) {
            val a = u.path[u.pathIdx]; val b = u.path[u.pathIdx + 1]
            val link = map.linkBetween(a, b)
            if (link?.type == "teleport") { val p = pos(b); u.x = p.x; u.y = p.y; u.node = b; u.pathIdx++; continue }
            val pb = pos(b)
            val d = dist(u.x, u.y, pb.x, pb.y)
            val slow = when (link?.type) { "ladder", "vent" -> 0.6f; "stairs" -> 0.85f; else -> 1f }
            if (d <= left * slow) { u.x = pb.x; u.y = pb.y; u.node = b; u.pathIdx++; left -= d / slow }
            else { u.x += (pb.x - u.x) / d * left * slow; u.y += (pb.y - u.y) / d * left * slow; left = 0f }
        }
    }

    // -------------------------------------------------------------------------------- utility
    val KIND_CODES = listOf("smoke", "flash", "molly", "recon", "wall", "trap", "concuss", "slow", "heal", "mobility", "decoy", "nearsight", "suppress", "revive", "weapon", "he")
    fun useUtil(u: U, kind: String, x: Float, y: Float) {
        if (!u.util.remove(kind)) return
        host.utility(u.pc)
        val q = u.utilSkill
        when (kind) {
            "smoke" -> areas.add(floatArrayOf(x, y, 0.035f, now + (if (cs) 18f else 15f), 0f, u.side.toFloat()))
            "wall" -> areas.add(floatArrayOf(x, y, 0.03f, now + 10f, 2f, u.side.toFloat()))
            "molly", "he" -> areas.add(floatArrayOf(x, y, 0.03f, now + (if (kind == "he") 0.6f else 6f), 1f, u.side.toFloat()))
            "flash", "concuss", "nearsight", "suppress" -> for (e in bySide[1 - u.side]) if (e.alive && dist(e.x, e.y, x, y) < 0.06f && rng.chance((0.55 * q).toDouble().coerceIn(0.2, 0.9))) {
                e.blindUntil = max(e.blindUntil, now + (if (kind == "flash") 1.8f else 1.4f)); e.revealedUntil = now + 2f; credit(u, e, 0f)
            }
            "recon", "trap" -> for (e in bySide[1 - u.side]) if (e.alive && dist(e.x, e.y, x, y) < (if (kind == "recon") 0.12f else 0.05f)) { e.revealedUntil = now + 3.5f; if (kind == "trap") e.slowUntil = now + 2f; credit(u, e, 0f) }
            "slow" -> for (e in bySide[1 - u.side]) if (e.alive && dist(e.x, e.y, x, y) < 0.05f) e.slowUntil = now + 3f
            "heal" -> bySide[u.side].filter { it.alive && it.hp < 70f }.minByOrNull { it.hp }?.let { it.hp = min(100f, it.hp + 50f) }
            "mobility", "decoy" -> u.seenEnemyAt = now // the next duel starts with the initiative
        }
        ev(Ev(T(), "util", u.side, u.pc.p.id, x = KIND_CODES.indexOf(kind).coerceAtLeast(0), m = (if (kind == "smoke") 15 else 3), n = packed(x, y)))
    }

    fun credit(by: U, victim: U, d: Float) { dmgBy.getOrPut(victim.gi) { HashMap() }.merge(by.gi, max(d, 0.1f)) { a, b -> a + b } }

    // ------------------------------------------------------------------------------- the round
    fun play(): Result {
        setup()
        var executeAt = if (rng.chance(0.4)) 20f else 32f + rng.int(0, 18)
        var sampleAt = 0f
        sample()
        while (true) {
            now += DT
            val aAlive = bySide[attack].count { it.alive }; val dAlive = bySide[def].count { it.alive }
            if (aAlive == 0 && !planted) return end(def)
            if (dAlive == 0) return end(attack)
            if (aAlive == 0 && planted) { // defenders go and defuse
                if (now - plantAt >= BOMB) return end(attack, explode = true)
                val d = bySide[def].filter { it.alive }.minByOrNull { dist(it.x, it.y, plantX, plantY) }!!
                if (d.node != siteNode) { if (d.path.lastOrNull() != siteNode) go(d, siteNode); step(d) } else if (defuseTick(d)) return end(def, defuse = true)
                tickSample(); continue
            }
            if (!planted && now >= ROUND) return end(def)
            if (planted && now - plantAt >= BOMB) return end(attack, explode = true)
            // Attackers: gather at the staging node, use utility, then go onto the site.
            val staged = bySide[attack].filter { it.alive && atEnd(it) }
            if (!planted && now >= executeAt && staged.isNotEmpty()) {
                val stg = staged.first()
                val sp = pos(siteNode)
                for (u in bySide[attack]) if (u.alive) {
                    // Utility on the site before entering.
                    if ("smoke" in u.util && rng.chance(0.7)) useUtil(u, "smoke", sp.x + (rng.float(-1.0, 1.0) * 0.02).toFloat(), sp.y + (rng.float(-1.0, 1.0) * 0.02).toFloat())
                    for (k in listOf("recon", "flash", "concuss", "he", "molly")) if (k in u.util && rng.chance(0.6)) { useUtil(u, k, sp.x, sp.y); break }
                    if (u.path.lastOrNull() != siteNode) go(u, siteNode)
                }
                executeAt = Float.MAX_VALUE
                ev(Ev(T(), "move", attack, x = site, n = packed(stg.x, stg.y)))
            }
            // Defenders rotate on information (seen attackers near a site).
            for (s in map.sites.indices) {
                val sp = pos(map.sites[s].node)
                val seen = bySide[attack].count { it.alive && dist(it.x, it.y, sp.x, sp.y) < 0.14f && (it.revealedUntil > now || bySide[def].any { d -> d.alive && dist(d.x, d.y, it.x, it.y) < 0.3f && los(d, it) }) }
                if (seen >= 2 || planted) {
                    val delay = 2f - teams[def].phase[Phase.IGL.ordinal].toFloat() / 60f
                    for (d in bySide[def]) if (d.alive && d.path.lastOrNull() != map.sites[s].node && d.wait <= 0f && rng.chance((0.35 / max(0.5f, delay)).toDouble().coerceIn(0.05, 0.9))) {
                        if (planted) { if ("smoke" in d.util && rng.chance(0.5)) useUtil(d, "smoke", sp.x, sp.y); if ("flash" in d.util) useUtil(d, "flash", sp.x, sp.y) }
                        else if (dist(d.x, d.y, sp.x, sp.y) > 0.1f) { for (k in listOf("slow", "molly", "wall", "trap")) if (k in d.util && rng.chance(0.5)) { useUtil(d, k, sp.x, sp.y); break } }
                        go(d, map.sites[s].node); d.holding = false
                    }
                }
            }
            for (u in units) if (u.alive) step(u)
            combat()
            // Molly / HE damage.
            for (ar in areas) if (ar[4] == 1f && ar[3] > now) for (u in bySide[1 - ar[5].toInt()]) if (u.alive && dist(u.x, u.y, ar[0], ar[1]) < ar[2]) {
                u.hp -= 12f * DT; host.damage(u.pc, 0.0)
                if (u.hp <= 0f) { val by = bySide[ar[5].toInt()].firstOrNull { it.alive } ?: bySide[ar[5].toInt()].first(); die(u, by, false) }
            }
            // Plant.
            if (!planted) for (u in bySide[attack]) if (u.alive && u.node == siteNode && atEnd(u)) {
                val contested = bySide[def].any { it.alive && dist(it.x, it.y, u.x, u.y) < 0.2f && los(it, u) }
                if (contested) { u.channel = 0f; continue }
                u.channel += DT
                if (u.channel >= PLANT) {
                    planted = true; plantAt = now; plantX = u.x; plantY = u.y; host.plant(u.pc)
                    ev(Ev(T(), "plant", attack, u.pc.p.id, x = site, n = packed(u.x, u.y)))
                    // Attackers take post-plant positions around the site.
                    for (a in bySide[attack]) if (a.alive && a !== u) { val nb = map.links.filter { it.from == siteNode || it.to == siteNode }.map { if (it.from == siteNode) it.to else it.from }; if (nb.isNotEmpty()) go(a, nb[rng.int(0, nb.size - 1)]) }
                    for (a in bySide[attack]) if (a.alive && "molly" in a.util && rng.chance(0.4)) useUtil(a, "molly", plantX, plantY)
                }
                break
            }
            // Defuse when planted and the site is clear.
            if (planted) for (d in bySide[def]) if (d.alive && d.node == siteNode && atEnd(d)) {
                if (bySide[attack].any { it.alive && dist(it.x, it.y, d.x, d.y) < 0.25f && los(it, d) }) { d.channel = 0f; continue }
                if (defuseTick(d)) return end(def, defuse = true)
                break
            }
            tickSample()
        }
    }

    fun defuseTick(d: U): Boolean {
        d.channel += DT
        val need = if (cs) (if (kit[def].getOrElse(d.i) { false }) 5f else 10f) else 7f
        if (now - plantAt + (need - d.channel) > BOMB) return false
        if (d.channel >= need) { defused = true; host.defuse(d.pc); ev(Ev(T(), "defuse", def, d.pc.p.id, x = site, n = packed(d.x, d.y))); return true }
        return false
    }

    fun tickSample() { if (rec != null && now - lastSample >= 1f) { sample(); lastSample = now } }
    var lastSample = 0f

    fun sample() {
        val r = rec ?: return
        for (u in units) {
            val flags = (if (!u.alive) 1 else 0) or (if (u.blindUntil > now) 2 else 0) or (if (u.channel > 0f) 4 else 0) or (if (u.revealedUntil > now) 8 else 0)
            r.pos.add(MobaEngine.packUnit(u.x * 1000f, u.y * 1000f, u.hp.toInt().coerceIn(0, 100), flags))
        }
        r.pos.add(if (planted) packed(plantX, plantY) or (1 shl 21) else 0)
        r.posT.add(T())
    }

    fun combat() {
        // Everyone picks a visible enemy in range; damage this half-second is applied simultaneously.
        val pending = FloatArray(units.size)
        val shooterOf = IntArray(units.size) { -1 }
        val bestShot = FloatArray(units.size)
        val head = BooleanArray(units.size)
        for (u in units) {
            if (!u.alive || u.channel > 0f && rng.chance(0.5)) continue
            var tgt: U? = null; var td = 9f
            for (e in bySide[1 - u.side]) {
                if (!e.alive) continue
                val d = dist(u.x, u.y, e.x, e.y)
                if (d > u.w.range || d >= td) continue
                if (!los(u, e)) continue
                tgt = e; td = d
            }
            if (tgt == null) { u.target = -1; continue }
            val fresh = u.target != tgt.gi
            u.target = tgt.gi
            // Initiative: holding an angle, information (recon/flash) or an entry ability wins the first shot.
            var acc = u.w.spread * (u.aim + (if (u.holding && fresh) 0.32f else if (u.holding) 0.1f else 0f) + (if (tgt.revealedUntil > now) 0.1f else 0f) + (if (now - u.seenEnemyAt < 2f) 0.12f else 0f) +
                (if (u.side == attack && !planted) u.entry else 0f) + (if (bySide[u.side].count { it.alive } == 1) u.clutch else 0f) - tgt.move)
            if (u.blindUntil > now) acc *= 0.12f
            if (!u.holding && !atEnd(u)) acc *= 0.72f // shooting on the move
            acc *= (1f - td / (u.w.range * 1.6f))
            acc = acc.coerceIn(0.02f, 0.92f)
            val shots = max(1, (u.w.rof * DT + rng.float(0.0, 0.99).toFloat()).toInt())
            var dmg = 0f
            var hs = false
            for (k in 0 until shots) {
                if (!rng.chance(acc.toDouble())) continue
                val headshot = rng.chance((0.18 + u.hsSkill * 0.3 + (if (u.w.sniper) 0.1 else 0.0)).coerceIn(0.05, 0.6))
                var d = if (headshot) u.w.head else u.w.body
                if (cs && tgt.arm > 0f) d *= u.w.armorPen
                dmg += d; if (headshot) hs = true
            }
            if (dmg > 0f) { pending[tgt.gi] += dmg; credit(u, tgt, dmg); host.damage(u.pc, dmg.toDouble()); if (dmg > bestShot[tgt.gi]) { bestShot[tgt.gi] = dmg; shooterOf[tgt.gi] = u.gi; head[tgt.gi] = hs } }
            tgt.seenEnemyAt = max(tgt.seenEnemyAt, now - 1f) // the target knows now
        }
        for (v in units) {
            if (!v.alive || pending[v.gi] <= 0f) continue
            var d = pending[v.gi]
            if (!cs && v.arm > 0f) { val a = min(v.arm, d * 0.66f); v.arm -= a; d -= a } // Valorant shields absorb
            v.hp -= d
            v.channel = 0f
            if (v.hp <= 0f) die(v, units[shooterOf[v.gi]], head[v.gi])
        }
    }

    fun die(v: U, killer: U, hs: Boolean) {
        if (!v.alive) return
        v.alive = false; v.hp = 0f
        val ks = killer.side
        val assist = dmgBy[v.gi]?.entries?.filter { it.key != killer.gi && units[it.key].side == ks }?.maxByOrNull { it.value }?.key?.let { units[it] }
        val where = if (planted) ReplayCodec.LOC_POSTPLANT else if (dist(v.x, v.y, pos(siteNode).x, pos(siteNode).y) < 0.12f) ReplayCodec.LOC_SITE else ReplayCodec.LOC_APPROACH
        host.kill(ks, killer.pc, v.pc, assist?.pc, hs, where, (dmgBy[v.gi]?.get(killer.gi) ?: 0f).toDouble())
        ev(Ev(T(), "kill", ks, killer.pc.p.id, v.pc.p.id, Weapons.of(t.id).indexOf(killer.w).coerceAtLeast(0), m = where, n = assist?.pc?.p?.id ?: -1))
        firstKill = false
        // Trade: the victim's teammates now know where the killer is.
        killer.revealedUntil = now + 1.5f
    }

    fun end(winner: Int, explode: Boolean = false, defuse: Boolean = false): Result {
        if (rec != null) sample()
        return Result(winner, planted, defuse, now.toInt(), site)
    }
}

/** Walls of a shooter map rasterised to a grid for line-of-sight checks (approximate layout). */
object ShooterGrid {
    private const val N = 80
    private val grids = java.util.concurrent.ConcurrentHashMap<Maps.Shooter, BooleanArray>()
    fun blocked(m: Maps.Shooter, x: Float, y: Float): Boolean {
        val g = grids.getOrPut(m) {
            val b = BooleanArray(N * N)
            for (c in 0 until N * N) { val cx = ((c % N) + 0.5f) / N; val cy = ((c / N) + 0.5f) / N; b[c] = m.walls.any { r -> cx >= r[0] && cx < r[0] + r[2] && cy >= r[1] && cy < r[1] + r[3] } }
            // Nodes and the straight links between them are open (the drawn walls are coarse).
            for (l in m.links) { if (l.type == "teleport") continue; val a = m.nodes[l.from].pos; val bb = m.nodes[l.to].pos
                var f = 0f; while (f <= 1f) { val x0 = a.x + (bb.x - a.x) * f; val y0 = a.y + (bb.y - a.y) * f; b[((y0 * N).toInt().coerceIn(0, N - 1)) * N + (x0 * N).toInt().coerceIn(0, N - 1)] = false; f += 0.02f } }
            b
        }
        return g[((y * N).toInt().coerceIn(0, N - 1)) * N + (x * N).toInt().coerceIn(0, N - 1)]
    }
}

package dev.uem.em.mgmt

import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.model.GameRecord
import dev.uem.em.model.MapPoolChange
import dev.uem.em.model.News
import dev.uem.em.model.World
import dev.uem.em.sim.MapForge
import dev.uem.em.sim.ShooterEconomy
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.TitleDef

/**
 * Shooter map pools. A major patch may rotate one or two maps: a map that has been in the pool a while leaves,
 * and a real map from outside the pool returns or a brand-new fictional map is released (sim.MapForge). The
 * change is announced in the patch notes and comes into force when the next season starts, so no running
 * competition changes its maps. Also keeps the round results by map for the map library.
 */
object MapPools {
    // mapStats "season|mapId": games, rounds, rounds won by the attackers, endings (elimination, detonation,
    // defuse, time), rounds aimed at each site A-D, attacker wins at each site, pistol rounds, attacker pistol wins.
    const val F_GAMES = 0
    const val F_ROUNDS = 1
    const val F_ATK = 2
    const val F_HOW = 3
    const val F_SITE = 7
    const val F_SITEWIN = 11
    const val F_PISTOL = 15
    const val F_PISTOL_ATK = 16
    const val SIZE = 17
    /** Fictional maps per game at most (older ones stay in the library). */
    private const val MAX_CUSTOM = 16

    fun poolIds(w: World, titleId: String): List<String> =
        w.mapPools[titleId]?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: Catalogue.startingPool(titleId).map { it.id }

    /** A major patch: perhaps a pool change (announced now, in force next season). Patch-note lines. */
    fun patch(w: World, t: TitleDef, version: String, rng: Rng): List<String> {
        if (t.isMoba || t.tactical == null) return emptyList()
        if (w.mapPoolLog.any { it.titleId == t.id && !it.applied }) return emptyList()
        val changes = if (rng.chance(0.72)) (if (rng.chance(0.25)) 2 else 1) else 0
        if (changes == 0) return emptyList()
        val pool = poolIds(w, t.id).toMutableList()
        val notes = ArrayList<String>()
        repeat(changes) {
            // Maps added in the last two seasons stay; the leaving map is one of the longest-serving.
            val removable = pool.filter { id -> w.mapPoolLog.none { it.titleId == t.id && it.added == id && it.effective > w.season - 2 } }
            if (removable.isEmpty()) return@repeat
            val out = rng.pick(removable.take(3))
            val outside = t.allMaps.map { it.id }.filter { id -> id !in pool && id != out && w.mapPoolLog.none { it.titleId == t.id && it.removed == id && it.effective >= w.season } }
            val customs = w.customMaps.count { it.titleId == t.id }
            val fresh = customs < MAX_CUSTOM && rng.chance(if (outside.isEmpty()) 1.0 else 0.5)
            val inId = if (fresh) {
                val taken = (Catalogue.ALL.flatMap { it.allMaps }.map { it.name.lowercase() } + w.customMaps.map { it.name.lowercase() }).toSet()
                val cm = MapForge.generate(t.id, Rng.derive(w.seed, "map", t.id, w.season, it, w.customMaps.size), taken, w.season + 1, version) ?: return@repeat
                w.customMaps.add(cm)
                Catalogue.applyMaps(w)
                notes.add("New map — ${cm.name}: ${cm.blurb}" + (if (cm.features.isNotEmpty()) " " + cm.features.joinToString("; ") + "." else "") +
                    (if (cm.staples.isNotEmpty()) " Early read: ${cm.staples.take(3).joinToString { s -> t.characters.firstOrNull { c -> c.id == "${t.id}-$s" }?.name ?: s }} look strong here." else ""))
                cm.id
            } else {
                if (outside.isEmpty()) return@repeat
                rng.pick(outside)
            }
            pool.remove(out); pool.add(inId)
            w.mapPoolLog.add(MapPoolChange(t.id, w.season, w.day, version, inId, out, w.season + 1))
            notes.add("Map pool: ${t.mapName(inId)} ${if (fresh) "joins" else "returns to"} the competitive pool from season ${w.season + 1}, replacing ${t.mapName(out)}.")
        }
        return notes
    }

    /** A new season: announced pool changes come into force; players get a starting feel for maps new to them. */
    fun rollover(w: World) {
        val due = w.mapPoolLog.filter { !it.applied && it.effective <= w.season }
        if (due.isEmpty()) return
        for ((titleId, list) in due.groupBy { it.titleId }) {
            val t = Catalogue.get(titleId)
            val pool = poolIds(w, titleId).toMutableList()
            for (c in list) {
                if (c.removed in pool) pool.remove(c.removed) else if (pool.size >= Catalogue.startingPool(titleId).size) pool.removeAt(0)
                if (c.added !in pool) pool.add(c.added)
                c.applied = true
            }
            w.mapPools[titleId] = pool.joinToString(",")
            Catalogue.applyMaps(w)
            // Map experience: a map players have never played starts below their usual level.
            val rng = Rng.derive(w.seed, "mapfeel", titleId, w.season)
            for (p in w.players) {
                if (p.retired || p.titleId != titleId) continue
                val known = p.profMap.values
                val base = if (known.isEmpty()) 40.0 else known.average()
                for (id in pool) if (id !in p.profMap) p.profMap[id] = clamp(base - 14 + rng.normal(0.0, 6.0), 5.0, 90.0).toInt().toDouble()
            }
            w.news.add(News(w.day, "${t.short} map pool for ${w.season}", t.maps.joinToString { it.name } + " — " + list.joinToString("; ") { "${t.mapName(it.added)} in, ${t.mapName(it.removed)} out" }))
        }
    }

    /** Round results of a series' games by map (map library), and the teams' records on each map. */
    fun record(w: World, t: TitleDef, games: List<GameRecord>, teamIds: IntArray) {
        if (t.isMoba) return
        val half = t.tactical?.half ?: 12
        for (g in games) {
            val a = w.mapStats.getOrPut("${w.season}|${g.mapId}") { IntArray(SIZE) }
            a[F_GAMES]++
            val log = ShooterEconomy.EconLog.parse(g.econ)
            if (log != null) for ((ri, r) in log.rounds.withIndex()) {
                if (r.winner < 0) continue
                a[F_ROUNDS]++
                val atkWon = r.winner == r.attack
                if (atkWon) a[F_ATK]++
                if (r.how in 0..3) a[F_HOW + r.how]++
                if (r.site in 0..3) { a[F_SITE + r.site]++; if (atkWon) a[F_SITEWIN + r.site]++ }
                if (ri == 0 || ri == half) { a[F_PISTOL]++; if (atkWon) a[F_PISTOL_ATK]++ }
            }
            for (side in 0..1) {
                val tm = w.mapStats.getOrPut("${w.season}|${g.mapId}|${teamIds[side]}") { IntArray(4) }
                tm[0]++
                if (g.winner == side) tm[1]++
                tm[2] += g.score.getOrElse(side) { 0 }
                tm[3] += g.score.getOrElse(1 - side) { 0 }
            }
        }
    }

    /** Season end: team records by map older than last season are dropped (the map totals stay). */
    fun prune(w: World) {
        w.mapStats.keys.removeAll { k -> k.count { it == '|' } == 2 && (k.substringBefore('|').toIntOrNull() ?: 0) < w.season - 1 }
    }

    /** A map's totals over the given seasons (all seasons when null). */
    fun totals(w: World, mapId: String, season: Int?): IntArray {
        val out = IntArray(SIZE)
        for ((k, v) in w.mapStats) {
            if (k.count { it == '|' } != 1 || k.substringAfter('|') != mapId) continue
            if (season != null && k.substringBefore('|').toIntOrNull() != season) continue
            for (i in 0 until minOf(SIZE, v.size)) out[i] += v[i]
        }
        return out
    }

    /** Teams' records on a map in a season: team id → games, wins, rounds won, rounds lost. */
    fun teams(w: World, mapId: String, season: Int): Map<Int, IntArray> =
        w.mapStats.entries.filter { (k, _) -> k.count { it == '|' } == 2 && k.startsWith("$season|$mapId|") }.associate { (k, v) -> k.substringAfterLast('|').toInt() to v }

    /** When a map entered and left the pool (patch log), newest first. */
    fun history(w: World, mapId: String): List<MapPoolChange> = w.mapPoolLog.filter { it.added == mapId || it.removed == mapId }.sortedByDescending { it.day }
}

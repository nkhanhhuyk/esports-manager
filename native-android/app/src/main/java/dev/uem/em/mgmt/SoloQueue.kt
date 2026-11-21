package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.data.GameData
import dev.uem.em.gen.GenCtx
import dev.uem.em.gen.PlayerGen
import dev.uem.em.model.AttrGroup
import dev.uem.em.model.Attrs
import dev.uem.em.model.Message
import dev.uem.em.model.Player
import dev.uem.em.model.World
import dev.uem.em.sim.Ratings
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.Phase
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Solo queue: every player of the seven games has a ladder rating on his server. Solo queue rewards
 * individual skill (mechanics, laning, aim, clutch) and the hours put in; professional play also needs
 * teamwork, macro and shotcalling. So a ladder star is not always a good professional and a strong
 * professional who rarely queues can sit lower on the ladder. Amateurs who have never played
 * professionally appear at the top of the ladder: scouts find them there. Players' queue time is
 * practice (mechanics, character pool) at the cost of fatigue; teams set how much they ask for.
 */
object SoloQueue {
    /** Rating scale (internal): ~2750 is the top tier; displayed per game below. */
    private const val TOP = 2750.0

    private val TIERS = mapOf(
        "lol" to listOf("Iron" to 0, "Bronze" to 400, "Silver" to 800, "Gold" to 1150, "Platinum" to 1450, "Emerald" to 1700, "Diamond" to 1950, "Master" to 2250, "Grandmaster" to 2500, "Challenger" to 2700),
        "wildrift" to listOf("Iron" to 0, "Bronze" to 400, "Silver" to 800, "Gold" to 1150, "Platinum" to 1450, "Emerald" to 1700, "Diamond" to 1950, "Master" to 2250, "Grandmaster" to 2500, "Challenger" to 2700),
        "mlbb" to listOf("Warrior" to 0, "Elite" to 300, "Master" to 600, "Grandmaster" to 900, "Epic" to 1250, "Legend" to 1600, "Mythic" to 1950, "Mythical Honor" to 2250, "Mythical Glory" to 2500, "Mythical Immortal" to 2700),
        "hok" to listOf("Bronze" to 0, "Silver" to 400, "Gold" to 800, "Platinum" to 1150, "Diamond" to 1500, "Master" to 1850, "Grandmaster" to 2150, "Mythic" to 2450, "King" to 2700),
        "dota2" to listOf("Herald" to 0, "Guardian" to 450, "Crusader" to 850, "Archon" to 1200, "Legend" to 1550, "Ancient" to 1850, "Divine" to 2150, "Immortal" to 2450),
        "valorant" to listOf("Iron" to 0, "Bronze" to 350, "Silver" to 700, "Gold" to 1050, "Platinum" to 1400, "Diamond" to 1750, "Ascendant" to 2050, "Immortal" to 2350, "Radiant" to 2700),
        "cs2" to listOf("Grey" to 0, "Light blue" to 700, "Blue" to 1200, "Purple" to 1650, "Pink" to 2050, "Red" to 2400, "Gold" to 2650),
    )

    /** The player's server (where his team plays, else his home region). */
    fun server(w: World, p: Player): String {
        val team = w.teams.getOrNull(p.teamId)
        val r = team?.region?.takeIf { it.isNotBlank() }?.let { GameData.expand(it).firstOrNull() } ?: GameData.regionOf(p.nat)
        return r
    }

    /** Individual skill for the ladder (0..100 scale like ratings). */
    fun soloSkill(w: World, p: Player): Double {
        val t = Catalogue.get(p.titleId)
        val b = Ratings.base(p, t, t.role(p.mainRole))
        fun ph(x: Phase) = b.phaseC[x.ordinal]
        val core = if (t.isMoba) 0.32 * ph(Phase.SKIRMISH) + 0.26 * ph(Phase.LANING) + 0.14 * ph(Phase.CLUTCH) + 0.12 * (if (p.mainRole.contains("jung")) ph(Phase.JUNGLE) else ph(Phase.MOVEMENT)) + 0.16 * ph(Phase.SURVIVAL)
        else 0.42 * ph(Phase.AIM) + 0.2 * ph(Phase.ENTRY) + 0.16 * ph(Phase.CLUTCH) + 0.12 * ph(Phase.MOVEMENT) + 0.1 * ph(Phase.SURVIVAL)
        // A personal knack for solo play (carry mentality, comfort playing alone): some are better there than their ratings say.
        val knack = Rng.derive(w.seed, "soloknack", p.id).normal(0.0, 3.0)
        return core + knack
    }

    /** Where a player's rating settles with his skill and effort. */
    fun target(w: World, p: Player, hours: Double): Double {
        val effort = clamp(hours / 12.0, 0.2, 1.4)
        return TOP + (soloSkill(w, p) - 70) * 42 - (1.2 - effort) * 160
    }

    private fun place(w: World, p: Player) {
        val rng = Rng.derive(w.seed, "soloplace", p.id)
        p.soloGrind = clamp(rng.normal(0.0, 0.45) + (if (p.pers.getOrElse(0) { 50 } > 70) 0.2 else 0.0), -1.0, 1.0)
        p.soloMmr = target(w, p, 12.0) + rng.normal(0.0, 60.0)
        p.soloPeak = p.soloMmr
    }

    private fun hoursOf(w: World, p: Player, busy: Boolean): Double {
        if (p.injuryUntil > w.day) return 0.0
        var h = 11.0 + 7.0 * p.soloGrind
        val team = w.teams.getOrNull(p.teamId)
        if (team != null) h *= listOf(0.55, 1.0, 1.55)[team.soloFocus.coerceIn(0, 2)] * (if (busy) 0.7 else 1.0)
        else h *= if (p.amateur) 1.6 else 1.2
        if (p.fatigue > 70) h *= 0.5
        team?.let { if (w.orgs[it.orgId].streamPolicy >= 2) h *= 1.25 } // streamers queue on stream
        return h.coerceAtLeast(0.0)
    }

    /** Weekly: everyone queues; ratings move toward skill and effort; practice and fatigue follow. */
    fun weekly(w: World) {
        val busyTeams = HashSet<Int>()
        for (f in w.fixtures) if (f.day in w.day - 3..w.day + 7) { busyTeams.add(f.a); busyTeams.add(f.b) }
        for (p in w.players) {
            if (p.amateur && p.teamId >= 0) p.amateur = false // signed: a professional now
            if (p.retired || (p.teamId < 0 && !p.amateur && p.ca < 45)) continue
            if (p.soloMmr <= 0.0) place(w, p)
            val rng = Rng.derive(w.seed, "solo", p.id, w.day)
            val hours = hoursOf(w, p, p.teamId in busyTeams)
            val games = (hours * 1.3 * rng.float(0.7, 1.3)).roundToInt()
            val before = p.soloMmr
            if (games == 0) {
                if (p.soloMmr > 2250) p.soloMmr -= 12 // inactivity decay at the top
            } else {
                val tgt = target(w, p, hours)
                val k = minOf(1.0, games / 40.0) * 0.35
                p.soloMmr += (tgt - p.soloMmr) * k + rng.normal(0.0, 22.0 * sqrt(games / 20.0))
                val net = ((p.soloMmr - before) / 20.0).roundToInt()
                val wins = ((games + net) / 2).coerceIn(0, games)
                p.soloGames += games; p.soloWins += wins
                // Practice: character pool and a little mechanics; long sessions tire.
                if (p.teamId >= 0) p.fatigue = clamp(p.fatigue + games * 0.12, 0.0, 100.0)
                val t = Catalogue.get(p.titleId)
                if (t.characters.isNotEmpty() && p.profChar.isNotEmpty()) {
                    val top = p.profChar.entries.sortedByDescending { it.value }.take(3)
                    val e = top[rng.int(0, top.size - 1)]
                    if (p.profChar[e.key]!! < 99) p.profChar[e.key] = minOf(99.0, e.value + games / 30.0)
                }
                if (games >= 25 && p.ca < p.pa && rng.chance(0.12)) {
                    for (i in 0 until Attrs.COUNT) if (Attrs.ALL[i].group == AttrGroup.Mechanical && rng.chance(0.3)) p.attrs[i] = minOf(99.0, p.attrs[i] + 1)
                    p.attrVersion++
                }
            }
            p.soloMmr = p.soloMmr.coerceIn(100.0, 3600.0)
            p.soloDelta = p.soloMmr - before
            if (p.soloMmr > p.soloPeak) p.soloPeak = p.soloMmr
        }
    }

    /** Season change: history line and a soft reset toward the middle of the top tiers. */
    fun rollover(w: World) {
        for (p in w.players) {
            if (p.soloMmr <= 0.0) continue
            if (p.soloGames > 0) p.soloHistory.add("${w.season - 1}|${p.soloMmr.roundToInt()}|${p.soloGames}|${p.soloWins}|${p.soloPeak.roundToInt()}")
            if (p.soloHistory.size > 12) p.soloHistory.removeAt(0)
            p.soloMmr = p.soloMmr * 0.8 + 2000 * 0.2
            p.soloGames = 0; p.soloWins = 0; p.soloPeak = p.soloMmr; p.soloDelta = 0.0
        }
    }

    /**
     * Monthly: amateurs break through on the ladder (strong mechanics, raw teamwork). A few per game
     * and region, never more than the scene can hold; your scouts tell you about the best ones.
     */
    fun monthlyAmateurs(w: World) {
        val rng = Rng.derive(w.seed, "amateurs", w.day)
        val ctx = GenCtx(w, rng)
        for (id in w.titles) {
            val have = w.players.count { it.amateur && it.teamId < 0 && !it.retired && it.titleId == id }
            if (have >= 36) continue
            val regions = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 }.map { it.region }.filter { it.isNotBlank() }.distinct()
            if (regions.isEmpty()) continue
            repeat(rng.int(1, 3)) {
                val region = rng.pick(regions)
                val nat = GameData.countries.values.filter { c -> GameData.expand(region).contains(c.region) }.let { cs -> if (cs.isEmpty()) "KR" else rng.pick(cs).code }
                val p = PlayerGen.generate(ctx, id, null, nat, rng.int(16, 20), rng.float(44.0, 60.0), rng.float(4.0, 12.0))
                // Ladder profile: mechanics ahead of teamwork and game sense.
                for (i in 0 until Attrs.COUNT) when (Attrs.ALL[i].group) {
                    AttrGroup.Mechanical, AttrGroup.Combat -> p.attrs[i] = minOf(99.0, p.attrs[i] + rng.float(6.0, 14.0))
                    AttrGroup.Teamwork, AttrGroup.Tactical -> p.attrs[i] = maxOf(1.0, p.attrs[i] - rng.float(4.0, 12.0))
                    else -> {}
                }
                p.attrVersion++
                p.ca = Ratings.computeCA(p, Catalogue.get(id))
                p.pa = maxOf(p.pa, p.ca + 4)
                p.amateur = true
                p.soloGrind = rng.float(0.4, 1.0)
                p.soloMmr = target(w, p, 20.0) + rng.normal(0.0, 50.0)
                p.soloPeak = p.soloMmr
                p.career.add(dev.uem.em.model.CareerEntry(w.season, w.day, -1, "amateur"))
            }
        }
        // The best new amateur near the top of a ladder of your games reaches your inbox.
        val mine = w.orgs.getOrNull(w.userOrg)?.teams?.map { w.teams[it].titleId }?.toSet() ?: return
        val star = w.players.filter { it.amateur && it.teamId < 0 && !it.retired && it.titleId in mine && w.day - it.career.lastOrNull()?.day.let { d -> d ?: 0 } < 31 }.maxByOrNull { it.soloMmr } ?: return
        val place = ladder(w, star.titleId, server(w, star)).indexOf(star) + 1
        if (place in 1..50) w.inbox.add(Message(w.day, "Scouting", "Ladder talent: ${star.handle}", "${star.handle} (${Catalogue.get(star.titleId).short}, age ${Dates.ageAt(star.birth, w.day)}, ${GameData.countries[star.nat]?.name ?: star.nat}) is #$place on the ${GameData.regionName(server(w, star))} ladder (${rank(star.titleId, star.soloMmr, place)}) and has never played professionally. Solo queue skill does not always carry over: scout him before signing."))
    }

    /** A server's ladder, best first (all servers when `server` is null). */
    fun ladder(w: World, titleId: String, server: String?): List<Player> =
        w.players.filter { !it.retired && it.titleId == titleId && it.soloMmr > 0 && (server == null || server(w, it) == server) }.sortedByDescending { it.soloMmr }

    /** The rank shown for a rating (top tier by ladder place where the game does so). */
    fun rank(titleId: String, mmr: Double, place: Int = 0): String {
        val tiers = TIERS[titleId] ?: TIERS["lol"]!!
        val tier = tiers.lastOrNull { mmr >= it.second } ?: tiers.first()
        val idx = tiers.indexOf(tier)
        val top = idx >= tiers.size - 2
        return when (titleId) {
            "dota2" -> "${tier.first}${if (tier.first == "Immortal" && place > 0) " #$place" else ""} · ${(mmr * 4.3).roundToInt()} MMR"
            "cs2" -> "Premier ${"%,d".format((mmr * 11).roundToInt())}${if (place in 1..100) " · top 100 (#$place)" else ""}"
            "mlbb" -> if (mmr >= 1950) "${tier.first} · ${((mmr - 1950) / 9).roundToInt()} stars" else tier.first
            "hok" -> if (mmr >= 2150) "${tier.first} · ${((mmr - 2150) / 10).roundToInt()} stars" else tier.first
            "valorant" -> if (top) "${tier.first} · ${((mmr - tiers[tiers.size - 2].second) / 2).roundToInt()} RR${if (place > 0) " (#$place)" else ""}" else "${tier.first} ${3 - ((mmr - tier.second) / ((tiers.getOrNull(idx + 1)?.second ?: (tier.second + 350)) - tier.second) * 3).toInt().coerceIn(0, 2)}"
            else -> if (mmr >= 2250) "${tier.first} · ${((mmr - 2250) * 0.9).roundToInt()} LP" else "${tier.first} ${4 - ((mmr - tier.second) / ((tiers.getOrNull(idx + 1)?.second ?: (tier.second + 300)) - tier.second) * 4).toInt().coerceIn(0, 3)}"
        }
    }

    /** How the ladder compares with professional level: "solo queue star", "plays below his level" or "in line". */
    fun comparison(w: World, p: Player): String {
        if (p.soloMmr <= 0) return ""
        val expected = TOP + (p.ca - 70) * 42
        val gap = p.soloMmr - expected
        return when {
            gap > 180 -> "Solo queue star: ranks well above his professional level (mechanics ahead of teamwork and game sense)"
            gap < -180 -> "Ranks below his professional level (queues little, or his strength is teamwork and shotcalling)"
            else -> "Ladder rank in line with his professional level"
        }
    }
}

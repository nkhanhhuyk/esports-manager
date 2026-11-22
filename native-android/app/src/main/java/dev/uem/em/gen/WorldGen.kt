package dev.uem.em.gen

import dev.uem.em.comp.Season
import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.data.DataSource
import dev.uem.em.data.GameData
import dev.uem.em.model.CareerEntry
import dev.uem.em.model.League
import dev.uem.em.model.Org
import dev.uem.em.model.Player
import dev.uem.em.model.Attrs
import dev.uem.em.sim.Ratings
import kotlinx.serialization.json.double
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.titles.Catalogue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * New world: the real leagues, teams and players of the snapshot (src/data/real), fictional leagues
 * where no real data covers a popular region, real teams without a league place (dormant) and their
 * former players as free agents, a free-agent pool and prospects. Ported from the web version.
 */
object WorldGen {
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(Regex("[^a-z0-9]"), "")

    private val AMER = "AMER-NA.LATN.LATS.BR"
    private val EMEA = "EMEA-EUW.EUE.CIS.MENA.AFR"
    private val LEAGUE_REGION = mapOf(
        "mpl-id" to "ID", "mpl-ph" to "PH", "lol-lck" to "KR", "lol-lpl" to "CN", "lol-lec" to "EMEA-EUW.EUE.CIS.MENA", "lol-lcs" to "NA", "lol-cblol" to "BR", "lol-lcp" to "APN-PCS.JP.VN",
        "vct-americas" to AMER, "vct-emea" to EMEA, "vct-pacific" to "APN-KR.JP.PCS.VN.TH.ID.PH.MYSG.SA.OCE", "vct-china" to "CN",
        "cs2-vrs-europe" to EMEA, "cs2-vrs-americas" to AMER, "cs2-vrs-asia" to "APS-CN.KR.JP.PCS.MYSG.OCE.SA.ID.PH.TH.VN",
        "wr-sea-pi" to "APS-PH.ID", "wr-sea-m" to "APS-MYSG.TH.VN.OCE.SA", "wr-emea" to EMEA,
    )
    private val DOTA_GROUP = mapOf(
        "EU" to Triple("EMEA-EUW.EUE.CIS.MENA", "Dota 2 Europe & CIS League", "D2 EU"),
        "CN" to Triple("CN", "Dota 2 China League", "D2 CN"),
        "SEA" to Triple("APS-MYSG.PH.ID.TH.VN.OCE.SA", "Dota 2 Southeast Asia League", "D2 SEA"),
        "AM" to Triple(AMER, "Dota 2 Americas League", "D2 AM"),
    )
    private val CS2_NAME = mapOf("cs2-vrs-europe" to ("CS2 Europe League" to "CS EU"), "cs2-vrs-americas" to ("CS2 Americas League" to "CS AM"), "cs2-vrs-asia" to ("CS2 Asia-Pacific League" to "CS AP"))
    private val ORG_ALIAS = mapOf("navi" to "natusvincere", "rrqhoshi" to "rrq", "rexregumqeon" to "rrq", "teamliquidph" to "liquid", "teamliquidid" to "liquid")
    private val ORG_NOISE = Regex("\\b(team|esports?|e-sports|gaming|club|pro|by vit|alienware|kia|movistar|beijing|shenzhen|suzhou|xi'an|shanghai|hangzhou|chengdu|wuhan|guangzhou|ph|id|indonesia|philippines)\\b")

    fun orgKey(name: String): String {
        val k = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "").replace(ORG_NOISE, " ").replace(Regex("[^a-z0-9]"), "")
        return ORG_ALIAS[k] ?: k.ifEmpty { name.lowercase() }
    }

    private fun cleanOrg(s: String) = s.replace(Regex("^(Beijing|Shenzhen|Suzhou|Xi'an|Shanghai|Hangzhou|Chengdu|Wuhan|Guangzhou|Chongqing|Shaanxi)\\s+", RegexOption.IGNORE_CASE), "").trim()
    private fun shortOf(name: String, fallback: String?) = (fallback?.ifBlank { null } ?: name).replace(Regex("\\s+"), "").take(6).uppercase()
    private fun valid(cc: String?): String? = cc?.uppercase()?.takeIf { GameData.countries.containsKey(it) }

    private val NAT_ALIAS = mapOf("thai" to "TH", "england" to "GB", "uk" to "GB", "laos" to "LA", "myanmar" to "MM", "india" to "IN", "korea" to "KR", "vietnam" to "VN", "philippines" to "PH", "usa" to "US",
        "south korea" to "KR", "united states" to "US", "united kingdom" to "GB", "czech republic" to "CZ", "czechia" to "CZ", "hong kong" to "HK", "taiwan" to "TW", "russia" to "RU", "turkey" to "TR", "united arab emirates" to "AE", "palestine" to "PS", "dominican republic" to "DO")

    fun countryCode(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val s = name.split(',')[0].trim()
        if (s.length == 2) valid(s)?.let { return it }
        val lc = s.lowercase()
        val code = NAT_ALIAS[lc] ?: GameData.countries.values.firstOrNull { it.name.lowercase() == lc }?.code
        return valid(code)
    }

    private class Facts(val name: String?, val cc: String?, val birth: String?, val birthYear: Int?, val role: String?, val src: String)

    private lateinit var facts: Map<String, Facts>

    private class Build(val ctx: GenCtx, val src: DataSource) {
        val world get() = ctx.world
        val rng get() = ctx.rng
        val realOrgs = HashMap<String, Org>()
        val nameRank = HashMap<Int, Int>()
        /** Real teams the snapshot listed fewer than a full lineup for (filled from real free agents). */
        val short = ArrayList<Team>()
        /** Curated ratings (assets/ratings.json): team strength and star players, the same in every save. */
        val teamRating = HashMap<String, Double>()
        val playerRating = HashMap<String, DoubleArray>()
    }

    private fun loadRatings(b: Build) {
        val j = runCatching { Json.parseToJsonElement(b.src.read("ratings.json")).jsonObject }.getOrNull() ?: return
        j["teams"]?.jsonObject?.forEach { (title, m) -> m.jsonObject.forEach { (k, v) -> b.teamRating["$title|${k.lowercase()}"] = v.jsonPrimitive.double } }
        j["players"]?.jsonObject?.forEach { (title, m) -> m.jsonObject.forEach { (k, v) -> b.playerRating["$title|${norm(k)}"] = v.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray() } }
    }

    /** A real player's own random stream: the same player gets the same attributes in every save. */
    private fun realCtx(b: Build, titleId: String, handle: String) = GenCtx(b.world, Rng.of("real|$titleId|${norm(handle)}"))

    /** Curated rating override: current ability (and potential) for a known player. */
    private fun curate(b: Build, p: Player) {
        val r = b.playerRating["${p.titleId}|${norm(p.handle)}"] ?: return
        val t = Catalogue.get(p.titleId)
        val role = t.role(p.mainRole)
        // Shift every non-development attribute until the rating matches the curated value.
        for (it in 0 until 8) {
            val e = Ratings.effectiveness(p, t, role)
            val delta = r[0] - e
            if (kotlin.math.abs(delta) < 0.3) break
            // Attributes and the game / role / character or weapon proficiencies all move toward the target.
            for (i in 0 until Attrs.COUNT) if (Attrs.ALL[i].group != dev.uem.em.model.AttrGroup.Development) p.attrs[i] = clamp(p.attrs[i] + delta / 0.66, 1.0, 99.0).roundToInt().toDouble()
            p.profTitle[t.id] = clamp((p.profTitle[t.id] ?: 60.0) + delta, 1.0, 99.0).roundToInt().toDouble()
            val rk = "${t.id}:${role.id}"
            p.profRole[rk] = clamp((p.profRole[rk] ?: 60.0) + delta, 1.0, 99.0).roundToInt().toDouble()
            for (k in p.profChar.keys.toList().sortedByDescending { p.profChar[it] }.take(5)) p.profChar[k] = clamp(p.profChar[k]!! + delta, 1.0, 99.0).roundToInt().toDouble()
            for (k in p.profWeapon.keys.toList()) p.profWeapon[k] = clamp(p.profWeapon[k]!! + delta, 1.0, 99.0).roundToInt().toDouble()
            p.attrVersion++
        }
        p.ca = Ratings.computeCA(p, t)
        p.pa = maxOf(p.ca, r.getOrElse(1) { p.pa })
        p.rep = clamp((p.ca - 50) * 2 + 8, 1.0, 99.0).roundToInt().toDouble()
    }

    fun create(src: DataSource, seed: String, name: String, startDay: Int = Dates.today(), titles: List<String> = Catalogue.IDS, fictional: Boolean = false): World {
        Catalogue.init(src)
        GameData.init(src)
        val y = Dates.year(startDay)
        val season = if (Dates.month(startDay) >= 11) y + 1 else y
        val world = World(seed = seed, name = name, day = startDay, season = season, titles = titles, fictional = fictional)
        world.firstSeason = season
        Catalogue.applyCustom(world)
        val ctx = GenCtx(world, Rng.of("$seed|world"))
        val b = Build(ctx, src)
        loadFacts(src)
        loadRatings(b)
        val rosters = Json.parseToJsonElement(src.read("rosters.json")).jsonObject
        val wild = Json.parseToJsonElement(src.read("wildrift.json")).jsonObject
        val snapLeagues = (rosters["leagues"]!!.jsonArray + wild["leagues"]!!.jsonArray).map { it.jsonObject }
        val wrTeams = wild["teams"]!!.jsonArray.map { it.jsonObject }
        val snapTeams = rosters["teams"]!!.jsonArray.map { it.jsonObject } + wrTeams.filter { it.str("league_id") != null }
        for (id in titles) {
            val t = Catalogue.get(id)
            val pop = Catalogue.POPULARITY[id]!!
            var regions = planLeagueRegions(pop)
            val mine = if (fictional) emptyList() else snapTeams.filter { it.str("title_id") == id }
            if (mine.isNotEmpty()) {
                val covered = buildRealTitle(b, id, snapLeagues, mine)
                regions = regions.filter { lr -> GameData.expand(lr.first).none { it in covered } }.take(2)
            }
            regions.forEachIndexed { idx, (region, strength0) -> fictionalLeague(b, id, region, strength0, idx == 0 || (fictional && idx == 1)) }
            if (t.characters.isEmpty() && !t.isMoba) Unit
        }
        if (!fictional) addHistory(b, wrTeams.filter { it.str("league_id") == null })
        fillShortRosters(b)
        freeAgents(b)
        for (team in world.teams) {
            team.lineup = Lineups.auto(world, team)
            team.elo = (1500 + (Lineups.strength(world, team) - 65) * 18).roundToInt().toDouble()
        }
        dev.uem.em.mgmt.Economy.ensureSetup(world)
        seedAcademies(world)
        for (id in titles) world.poolTarget[id] = world.players.count { !it.retired && it.titleId == id }
        Season.plan(world, season)
        world.rebuildSchedule()
        return world
    }

    private fun loadFacts(src: DataSource) {
        if (::facts.isInitialized) return
        val j = Json.parseToJsonElement(src.read("playerFacts.json")).jsonObject["players"]!!.jsonObject
        facts = j.mapValues { (_, v) ->
            val o = v.jsonObject
            Facts(o.str("name"), o.str("countryCode"), o.str("birth"), o["birthYear"]?.jsonPrimitive?.intOrNull, o.str("role"), o.str("src") ?: "").let { f ->
                if (f.cc == null) Facts(f.name, countryCode(o.str("country")), f.birth, f.birthYear, f.role, f.src) else f
            }
        }
    }

    private val EASTERN = setOf("KR", "CN", "TW", "HK", "VN", "JP", "MO")
    /** Romanisation variants of common Korean and Chinese surnames. */
    private val SURNAME_ALIAS = mapOf(
        "jung" to "jeong", "chung" to "jeong", "yi" to "lee", "rhee" to "lee", "ri" to "lee", "bak" to "park", "pak" to "park", "gim" to "kim",
        "choe" to "choi", "kang" to "gang", "cho" to "jo", "yoon" to "yun", "lim" to "im", "suh" to "seo", "sin" to "shin", "hwang" to "hwang",
        "noh" to "no", "roh" to "no", "ahn" to "an", "koo" to "gu", "ku" to "gu", "kwon" to "gwon", "ko" to "go", "goh" to "go", "woo" to "u",
    )
    private val LOL_ROLE = mapOf("top" to "top", "jungle" to "jungle", "mid" to "mid", "bot" to "bot", "adc" to "bot", "support" to "support")

    /** Real birthdays, nationalities, names and (LoL) positions from the facts snapshot. */
    private fun applyFacts(p: Player) {
        val f = facts["${p.titleId}|${norm(p.handle)}"] ?: return
        // Same handle, different person (e.g. two players called "Peyz"): the real names must share a part.
        if (f.name != null && (p.first.isNotBlank() || p.last.isNotBlank())) {
            fun parts(s: String) = s.lowercase().replace("-", "").split(Regex("[^\\p{L}0-9]+")).filter { it.length >= 2 }.map { norm(it).let { x -> SURNAME_ALIAS[x] ?: x } }.toSet()
            val mine = parts("${p.first} ${p.last}")
            val theirs = parts(f.name)
            val sameName = mine.any { a -> theirs.any { b -> a == b || (a.length >= 3 && b.startsWith(a)) || (b.length >= 3 && a.startsWith(b)) } }
            // Different spelling of the same person is fine when country and position agree.
            val roleOk = p.titleId != "lol" || f.role == null || LOL_ROLE[f.role.lowercase()] == p.roles[0]
            if (mine.isNotEmpty() && theirs.isNotEmpty() && !sameName && !(valid(f.cc) == p.nat && roleOk)) return
        }
        val est = p.realEst.toMutableSet()
        if (f.birth != null && Regex("\\d{4}-\\d{2}-\\d{2}").matches(f.birth)) {
            val (yy, mm, dd) = f.birth.split('-').map { it.toInt() }
            p.birth = Dates.day(yy, mm, dd)
            est.remove("age")
        } else if (f.birthYear != null) {
            p.birth = Dates.day(f.birthYear, 7, 1)
            est.remove("age")
            est.add("birthday (year known)")
        }
        valid(f.cc)?.let { p.nat = it; est.remove("nationality") }
        if (f.name != null && p.first.isBlank() && p.last.isBlank()) {
            val parts = f.name.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.size >= 2) {
                if (p.nat in EASTERN) { p.last = parts[0]; p.first = parts.drop(1).joinToString(" ") } else { p.first = parts.dropLast(1).joinToString(" "); p.last = parts.last() }
            }
        }
        if (p.titleId == "lol" && f.role != null) LOL_ROLE[f.role.lowercase()]?.let { r -> if (p.roles[0] != r) { p.roles = listOf(r, p.roles[0]); est.remove("role") } }
        p.realEst = est.toList()
        p.realSrc = p.realSrc.ifBlank { f.src }
    }

    private fun planLeagueRegions(pop: Map<String, Int>): List<Pair<String, Double>> {
        val sorted = pop.entries.sortedByDescending { it.value }
        val singles = sorted.filter { it.value >= 50 }.take(3).ifEmpty { listOf(sorted[0]) }
        val used = singles.map { it.key }.toSet()
        val out = singles.map { it.key to it.value.toDouble() }.toMutableList()
        val macroCands = ArrayList<Pair<String, Int>>()
        for (m in listOf("AMER", "EMEA", "APN", "APS")) {
            val members = GameData.regions.values.filter { it.macro == m && it.id !in used && (pop[it.id] ?: 0) >= 15 }.map { it.id }
            if (members.isEmpty()) continue
            val score = members.maxOf { pop[it] ?: 0 }
            if (score < 20) continue
            macroCands.add("$m-${members.joinToString(".")}" to score)
        }
        macroCands.sortByDescending { it.second }
        for ((id, score) in macroCands.take(2)) out.add(id to score * 0.85)
        return out
    }

    private fun createOrg(b: Build, region: String): Org {
        val (name, short) = GameData.makeOrgName(b.rng, b.ctx.orgNames)
        val country = GameData.countries.values.filter { it.region == region }.let { if (it.isEmpty()) "US" else b.rng.weighted(it) { c -> c.weight.toDouble() }.code }
        val rep = clamp(b.rng.normal(40.0, 15.0), 5.0, 90.0)
        val econ = GameData.economy(region)
        val org = Org(b.world.orgs.size, name, short, country, region, rep = rep, fans = (rep.pow(2.2) * 40 * econ + b.rng.int(1000, 20000)))
        org.balance = (rep * 30000 * econ + b.rng.int(200_000, 1_500_000))
        b.world.orgs.add(org)
        return org
    }

    private fun createTeam(b: Build, org: Org, titleId: String, region: String, league: League?, name: String = org.name, short: String = org.short): Team {
        val t = Catalogue.get(titleId)
        val team = Team(b.world.teams.size, org.id, titleId, name, short.take(6), region, leagueId = league?.id ?: -1, style = b.rng.pick(t.styles), aggression = clamp(b.rng.normal(50.0, 15.0), 10.0, 90.0).roundToInt().toDouble(),
            chemistry = clamp(b.rng.normal(62.0, 10.0), 30.0, 90.0).roundToInt().toDouble(), metaAdapt = clamp(b.rng.normal(72.0, 8.0), 45.0, 95.0).roundToInt().toDouble())
        b.world.teams.add(team)
        org.teams.add(team.id)
        league?.teamIds?.add(team.id)
        return team
    }

    private fun sign(b: Build, p: Player, team: Team) {
        val t = Catalogue.get(team.titleId)
        p.teamId = team.id
        p.salary = (t.baseSalary * GameData.economy(team.region) * Math.pow(maxOf(20.0, p.ca) / 70, 4.0) * clamp(b.rng.normal(1.0, 0.1), 0.75, 1.3) / 100).roundToInt() * 100.0
        p.contractEnd = b.world.season + b.rng.int(0, 2)
        p.joinedDay = b.world.day - b.rng.int(0, 500)
        team.roster.add(p.id)
        p.career.add(CareerEntry(b.world.season, b.world.day, team.id, "start"))
    }

    private fun populate(b: Build, team: Team, mean: Double, leagueRegion: String, importRate: Double) {
        val t = Catalogue.get(team.titleId)
        val pop = Catalogue.POPULARITY[t.id]!!
        val regs = GameData.expand(leagueRegion)
        val hubs = if (t.isMoba) listOf("KR", "CN", "VN", "PH", "ID", "EUW") else listOf("EUE", "CIS", "BR", "NA", "EUW", "KR")
        fun nat(): String {
            if (b.rng.chance(importRate)) {
                val h = hubs.filter { it !in regs }
                if (h.isNotEmpty()) return GameData.pickNationality(b.rng, listOf(b.rng.weighted(h) { (pop[it] ?: 30).toDouble() }), t.isMoba, pop)
            }
            return GameData.pickNationality(b.rng, regs, t.isMoba, pop)
        }
        for (roleId in t.lineup) sign(b, PlayerGen.generate(b.ctx, t.id, roleId, nat(), clamp(b.rng.normal(22.0, 2.6), 16.0, 31.0).roundToInt(), clamp(b.rng.normal(mean, 2.8), 30.0, 95.0)), team)
        repeat(2) { sign(b, PlayerGen.generate(b.ctx, t.id, b.rng.pick(t.lineup), nat(), clamp(b.rng.normal(19.5, 2.4), 15.0, 30.0).roundToInt(), clamp(b.rng.normal(mean - 6, 3.5), 30.0, 90.0)), team) }
        team.coach = clamp(b.rng.normal(45 + (mean - 57) * 1.2, 7.0), 25.0, 92.0)
    }

    private fun sceneStrength(t: dev.uem.em.titles.TitleDef, region: String, popStrength: Double): Double {
        val cs = GameData.expand(region).flatMap { r -> GameData.countries.values.filter { it.region == r } }
        val scene = if (cs.isEmpty()) 50.0 else cs.sumOf { (if (t.isMoba) it.moba else it.shooter).toDouble() } / cs.size
        return 0.6 * popStrength + 0.4 * scene
    }

    private fun popStrength(pop: Map<String, Int>, region: String): Double {
        pop[region]?.let { return it.toDouble() }
        val parts = GameData.expand(region)
        return if (parts.isEmpty()) 30.0 else parts.maxOf { pop[it] ?: 0 } * 0.85
    }

    private val LEAGUE_WORDS = listOf("Pro League", "Premier League", "Championship", "Masters League", "Elite Series", "Major League")

    private fun fictionalLeague(b: Build, titleId: String, region: String, strength0: Double, allowTier2: Boolean) {
        val t = Catalogue.get(titleId)
        val pop = Catalogue.POPULARITY[titleId]!!
        val rs = GameData.regionShort(region)
        val word = b.rng.pick(LEAGUE_WORDS)
        val league = League(b.world.leagues.size, titleId, region, 1, "${t.short} $rs $word", rs + word.split(" ").joinToString("") { it.take(1) })
        b.world.leagues.add(league)
        val strength = sceneStrength(t, region, strength0)
        val mean = 57 + 19 * (strength / 100)
        val single = GameData.regions.containsKey(region)
        val importRate = if (single) 0.08 else 0.15
        val members = GameData.expand(region)
        repeat(8) {
            val orgRegion = b.rng.weighted(members) { (pop[it] ?: 10) + 5.0 }
            val org = createOrg(b, orgRegion)
            val team = createTeam(b, org, titleId, region, league)
            populate(b, team, mean + b.rng.normal(0.0, 2.4) + (org.rep - 40) * 0.04, region, importRate)
            team.dataNote = fictionalNote(titleId)
        }
        if (allowTier2 && single && strength0 >= 70) {
            val l2 = League(b.world.leagues.size, titleId, region, 2, "${t.short} $rs Challengers", "${rs}C", upperId = league.id)
            league.lowerId = l2.id
            b.world.leagues.add(l2)
            repeat(8) {
                val org = createOrg(b, b.rng.weighted(members) { (pop[it] ?: 10) + 5.0 })
                val team = createTeam(b, org, titleId, region, l2)
                populate(b, team, mean - 7 + b.rng.normal(0.0, 3.0), region, importRate * 0.5)
                team.dataNote = fictionalNote(titleId)
            }
        }
    }

    /** Real academy leagues (rosters generated) under these tier-1 leagues: tier-1 short name → name, short. */
    private val ACADEMY_LEAGUES = mapOf("lol|KR" to ("LCK Challengers League" to "LCK CL"), "mlbb|ID" to ("MDL Indonesia" to "MDL ID"), "mlbb|PH" to ("MDL Philippines" to "MDL PH"))

    /**
     * Academy teams: every LCK and MPL ID/PH team has one in its academy league (as in real life; the
     * rosters are generated), and the strongest teams of leagues with a Challengers division field one there.
     */
    private fun seedAcademies(w: World) {
        val rng = Rng.of("${w.seed}|academies")
        for (league in w.leagues.toList()) {
            if (league.tier != 1) continue
            val names = ACADEMY_LEAGUES["${league.titleId}|${league.region}"]
            if (names != null && league.lowerId < 0 && !w.fictional) {
                val l2 = League(w.leagues.size, league.titleId, league.region, 2, names.first, names.second, upperId = league.id)
                league.lowerId = l2.id
                w.leagues.add(l2)
                for (tid in league.teamIds.toList()) dev.uem.em.mgmt.Academies.found(w, w.teams[tid], false, rng)
            } else if (league.lowerId >= 0) {
                val parents = league.teamIds.map { w.teams[it] }.sortedByDescending { it.elo }.take(if (w.fictional) 4 else 3)
                for (t in parents) dev.uem.em.mgmt.Academies.found(w, t, false, rng)
            }
        }
    }

    private fun fictionalNote(titleId: String) = if (titleId == "hok") "Fictional team: no verified current Honor of Kings roster source could be used, so the league, teams and players are generated" else "Fictional team (generated league, players and staff)"

    private fun signal(st: JsonObject): Double {
        st["vrs_points"]?.jsonPrimitive?.doubleOrNull?.let { return it }
        st["rating"]?.jsonPrimitive?.doubleOrNull?.let { return it }
        val rec = st["record"]?.jsonObject
        if (rec != null) {
            val w = rec["w"]!!.jsonPrimitive.doubleOrNull ?: 0.0
            val l = rec["l"]!!.jsonPrimitive.doubleOrNull ?: 0.0
            return (w + 1) / (w + l + 2)
        }
        return 0.5
    }

    /** Real leagues and teams of one title; returns the regions they cover. */
    private fun buildRealTitle(b: Build, titleId: String, snapLeagues: List<JsonObject>, mine: List<JsonObject>): Set<String> {
        val t = Catalogue.get(titleId)
        val pop = Catalogue.POPULARITY[titleId]!!
        val covered = HashSet<String>()
        class Group(val region: String, val name: String, val short: String, val src: String, val teams: List<JsonObject>, val real: Boolean)
        val groups = ArrayList<Group>()
        if (titleId == "dota2") {
            val by = LinkedHashMap<String, MutableList<JsonObject>>()
            for (st in mine) {
                val r = st.str("region")
                val g = if (r == "NA" || r == "SA") "AM" else if (r != null && DOTA_GROUP.containsKey(r)) r else "EU"
                by.getOrPut(g) { ArrayList() }.add(st)
            }
            for ((g, list) in by) DOTA_GROUP[g]!!.let { groups.add(Group(it.first, it.second, it.third, "https://api.opendota.com/api/teams", list, false)) }
        } else {
            for (l in snapLeagues.filter { it.str("title_id") == titleId }) {
                val lid = l.str("league_id")!!
                val list = mine.filter { it.str("league_id") == lid }
                val region = LEAGUE_REGION[lid] ?: continue
                if (list.isEmpty()) continue
                val cs = CS2_NAME[lid]
                groups.add(Group(region, cs?.first ?: l.str("name")!!, cs?.second ?: l.str("short")!!, l.str("source_url") ?: "", list, cs == null))
            }
        }
        for (g in groups) {
            covered.addAll(GameData.expand(g.region))
            val strength = sceneStrength(t, g.region, popStrength(pop, g.region))
            val leagueMean = 57 + 19 * (strength / 100)
            val ordered = g.teams.sortedByDescending { signal(it) }
            val sigs = ordered.map { signal(it) }
            val mean = sigs.average()
            val sd = sqrt(sigs.sumOf { (it - mean) * (it - mean) } / sigs.size).let { if (it == 0.0) 1.0 else it }
            val cut = if (ordered.size > 16) 12 else ordered.size
            val tiers = mutableListOf(1 to ordered.take(cut))
            if (cut < ordered.size) tiers.add(2 to ordered.drop(cut))
            var upper: League? = null
            for ((tier, list) in tiers) {
                val league = League(b.world.leagues.size, titleId, g.region, tier,
                    if (tier == 1) g.name else if (g.name.endsWith("League")) g.name.removeSuffix("League") + "Challengers" else "${g.name} — Challengers",
                    if (tier == 1) g.short else "${g.short} C", real = g.real, realSrc = g.src, upperId = upper?.id ?: -1)
                upper?.lowerId = league.id
                b.world.leagues.add(league)
                for (st in list) {
                    val z = (signal(st) - mean) / sd
                    // Curated strength, or (CS2) the Valve Regional Standings points, or the snapshot signal; never random per save.
                    val curated = b.teamRating["$titleId|${st.str("name")!!.lowercase()}"]
                    val vrs = st["vrs_points"]?.jsonPrimitive?.doubleOrNull
                    val teamMean = curated ?: vrs?.let { clamp(66 + (it - 1000) * 0.021, 60.0, 88.0) }
                        ?: (leagueMean + (if (tier == 2) -5 else 0) + clamp(z, -2.2, 2.2) * 1.8 + Rng.of("realteam|$titleId|${norm(st.str("name")!!)}").normal(0.0, 0.8))
                    createRealTeam(b, st, league, teamMean)
                }
                while (league.teamIds.size < 6) {
                    val members = GameData.expand(g.region)
                    val org = createOrg(b, members[league.teamIds.size % members.size])
                    val team = createTeam(b, org, titleId, g.region, league)
                    populate(b, team, leagueMean - 3 + b.rng.normal(0.0, 2.0), g.region, 0.1)
                    team.dataNote = "Fictional team added to complete a real league (generated players)"
                }
                upper = league
            }
        }
        return covered
    }

    private val NAME_RANK = mapOf("valorant" to 0, "dota2" to 1, "lol" to 2, "mlbb" to 3, "cs2" to 4)
    private fun isCaps(s: String) = s == s.uppercase() && Regex("[A-Z]{3}").containsMatchIn(s)

    private fun createRealTeam(b: Build, st: JsonObject, league: League, teamMean: Double) {
        val titleId = st.str("title_id")!!
        val t = Catalogue.get(titleId)
        val roster = st["roster"]!!.jsonArray.map { it.jsonObject }
        val players = roster.filter { it.str("status") == "player" }
        fun natOf(o: JsonObject) = valid(o.str("nationality")) ?: countryCode(o.str("nationality_name"))
        val nats = players.mapNotNull { natOf(it) }
        val top = nats.groupingBy { it }.eachCount().maxByOrNull { it.value }
        val orgCountry = valid(st.str("country")) ?: countryCode(st.str("country_name")) ?: (if (top != null && top.value >= 3) top.key else null)
        val orgName = st.str("org")?.ifBlank { null } ?: st.str("name")!!
        val key = orgKey(orgName)
        var org = b.realOrgs[key]
        if (org != null && org.teams.any { b.world.teams[it].titleId == titleId }) org = null
        val name = cleanOrg(orgName)
        if (org == null) {
            val country = orgCountry ?: GameData.pickCountry(Rng.of("realorg|$titleId|${norm(name)}"), league.region)
            org = createOrg(b, GameData.countries[country]?.region ?: GameData.expand(league.region)[0])
            org.name = name
            org.short = shortOf(name, st.str("short"))
            org.country = country
            org.real = true
            b.realOrgs.putIfAbsent(key, org)
            b.nameRank[org.id] = (if (isCaps(name)) 10 else 0) + (NAME_RANK[titleId] ?: 5)
        } else {
            val rank = (if (isCaps(name)) 10 else 0) + (NAME_RANK[titleId] ?: 5)
            if (rank < (b.nameRank[org.id] ?: 99) && orgKey(name) == orgKey(org.name)) {
                b.nameRank[org.id] = rank
                org.name = name
            }
        }
        val team = createTeam(b, org, titleId, league.region, league, st.str("name")!!, st.str("short") ?: st.str("name")!!)
        team.real = true
        team.realSrc = st.str("source_url") ?: ""
        org.rep = maxOf(org.rep, clamp(42 + (teamMean - 64) * 3 + b.rng.normal(0.0, 4.0), 15.0, 95.0)).roundToInt().toDouble()
        org.fans = (org.rep.pow(2.2) * 40 * GameData.economy(org.region) + b.rng.int(1000, 20000))
        org.prestige = (org.rep * 0.5).roundToInt().toDouble()
        val usable = players.take(t.teamSize + 2 + 4)
        val known = usable.map { o -> o.str("role")?.takeIf { r -> t.roles.any { it.id == r } } }
        val missing = t.lineup.filter { r -> known.withIndex().none { (i, k) -> k == r && usable[i].str("starter") != "false" } }.toMutableList()
        val flagged = usable.any { it["starter"]?.jsonPrimitive?.contentOrNull == "true" }
        val firstOfRole = HashSet<Int>()
        run { val seen = HashSet<String>(); known.forEachIndexed { i, k -> if (k != null && seen.add(k)) firstOfRole.add(i) } }
        usable.forEachIndexed { i, sp ->
            val est = ArrayList<String>()
            if (sp.str("role_source") != null) est.add("role")
            val roleId = known[i] ?: (missing.removeFirstOrNull() ?: Rng.of("realrole|$titleId|${norm(sp.str("handle") ?: "")}").pick(t.lineup)).also { est.add("role (game assignment)") }
            val nat = natOf(sp) ?: (valid(sp.str("nationality_estimate")) ?: org.country).also { est.add("nationality") }
            est.add("age")
            val starter = roleId in t.lineup && (if (flagged) sp["starter"]?.jsonPrimitive?.contentOrNull == "true" else i in firstOfRole || known[i] == null)
            val pctx = realCtx(b, titleId, sp.str("handle")!!)
            val p = PlayerGen.generate(pctx, titleId, roleId, nat, clamp(pctx.rng.normal(22.0, 2.4), 17.0, 30.0).roundToInt(),
                clamp(teamMean + (if (starter) 1 else -4) + pctx.rng.normal(0.0, if (starter) 1.6 else 2.2), 35.0, 95.0))
            p.handle = sp.str("handle")!!
            val first = sp.str("first_name")
            val realName = sp.str("real_name")
            if (first != null) { p.first = first; p.last = sp.str("last_name") ?: "" }
            else if (realName != null) { val parts = realName.split(' '); p.first = parts.dropLast(1).joinToString(" "); p.last = parts.last() }
            else { p.first = ""; p.last = "" }
            p.real = true
            p.realSrc = team.realSrc
            p.realEst = est
            applyFacts(p)
            curate(b, p)
            b.ctx.handles.add(p.handle.lowercase())
            sign(b, p, team)
        }
        // Short rosters are completed later from real free agents (never generated players).
        if (team.roster.size < t.teamSize) b.short.add(team)
        val listed = team.roster.size
        val src = team.realSrc
        val host = Regex("https?://([^/]+)").find(src)?.groupValues?.get(1) ?: "the snapshot"
        team.dataNote = if (src.contains("wildrift-esports.fandom")) "Real team, roster from the Wild Rift Esports Wiki as of early 2021 — a historical roster: no current Wild Rift source could be used"
        else "Real team, roster snapshot of 20 Nov 2026 ($host): $listed player(s) listed"
        team.coach = clamp(Rng.of("realcoach|$titleId|${norm(team.name)}").normal(55 + (teamMean - 64) * 1.2, 7.0), 30.0, 92.0)
    }

    private val HIST_REGION = mapOf("AMER" to AMER, "EMEA" to EMEA, "APN" to "APN-KR.JP.PCS.VN.TH.ID.PH.MYSG.SA.OCE")

    /** Real teams without a league place stay dormant; their former players are free agents. */
    private fun addHistory(b: Build, wrDormant: List<JsonObject>) {
        val h = Json.parseToJsonElement(b.src.read("history.json")).jsonObject
        val w = b.world
        val titles = w.titles.toSet()
        val histTeams = h["teams"]!!.jsonArray.map { it.jsonObject }.map { Triple(it, it.str("title_id")!!, it.str("name")!!) } +
            wrDormant.map { Triple(it, "wildrift", it.str("name")!!) }
        data class HP(val title: String, val handle: String, val first: String?, val last: String?, val nat: String?, val role: String?, val lastTeam: String?, val retired: Boolean, val src: String)
        val histPlayers = h["players"]!!.jsonArray.map { it.jsonObject }.map { HP(it.str("title_id")!!, it.str("handle")!!, it.str("first_name"), it.str("last_name"), valid(it.str("nationality")), it.str("role"), it.str("last_team"), it.str("status") == "retired", it.str("source_url") ?: "") } +
            wrDormant.flatMap { tm -> tm["roster"]!!.jsonArray.map { it.jsonObject }.filter { it.str("status") == "player" }.map { p ->
                val parts = (p.str("real_name") ?: "").split(Regex("\\s+")).filter { it.isNotBlank() }
                HP("wildrift", p.str("handle")!!, if (parts.size > 1) parts.dropLast(1).joinToString(" ") else null, if (parts.size > 1) parts.last() else null, countryCode(p.str("nationality_name")), p.str("role"), tm.str("name"), false, tm.str("source_url") ?: "")
            } }
        val liveTeamNames = w.teams.map { "${it.titleId}|${it.name.lowercase()}" }.toHashSet()
        val dormant = HashMap<String, Team>()
        for ((ht, titleId, name) in histTeams) {
            if (titleId !in titles || "$titleId|${name.lowercase()}" in liveTeamNames) continue
            val r = ht.str("region")
            val region = if (r != null) HIST_REGION[r] ?: r else if (titleId == "cs2" || titleId == "dota2") EMEA else "NA"
            val key = orgKey(name)
            var org = b.realOrgs[key]?.takeIf { o -> o.teams.none { w.teams[it].titleId == titleId } }
            if (org == null) {
                val country = GameData.pickCountry(b.rng, region)
                org = createOrg(b, GameData.countries[country]?.region ?: GameData.expand(region).firstOrNull() ?: "NA")
                org.name = cleanOrg(name)
                org.short = shortOf(name, ht.str("short"))
                org.country = country
                org.real = true
                org.rep = clamp(b.rng.normal(32.0, 8.0), 10.0, 60.0).roundToInt().toDouble()
                b.realOrgs.putIfAbsent(key, org)
            }
            val team = createTeam(b, org, titleId, region, null, name, ht.str("short") ?: name)
            team.active = false
            team.real = true
            team.realSrc = ht.str("source_url") ?: ""
            team.elo = 1450.0
            team.dataNote = if (titleId == "wildrift") "Real team from the Wild Rift Esports Wiki (early 2021), without a league place now" else "Real team without a current league place (historical record); its former players are free agents"
            dormant["$titleId|${name.lowercase()}"] = team
        }
        val live = HashMap<String, HashSet<String>>()
        for (p in w.players) if (p.real) live.getOrPut(p.titleId) { HashSet() }.add(p.handle.lowercase())
        // Real players only (fictional leagues differ per save; real ratings must not).
        val avg = titles.associateWith { id -> w.players.filter { it.titleId == id && it.teamId >= 0 && it.real }.map { it.ca }.ifEmpty { listOf(62.0) }.average() }
        for (hp in histPlayers) {
            if (hp.title !in titles) continue
            val set = live.getOrPut(hp.title) { HashSet() }
            if (!set.add(hp.handle.lowercase())) continue
            val t = Catalogue.get(hp.title)
            val roleId = hp.role?.takeIf { r -> t.roles.any { it.id == r } }
            val team = hp.lastTeam?.let { dormant["${hp.title}|${it.lowercase()}"] }
            val pctx = realCtx(b, hp.title, hp.handle)
            val nat = hp.nat ?: team?.let { GameData.pickCountry(pctx.rng, it.region) } ?: "US"
            val p = PlayerGen.generate(pctx, t.id, roleId ?: pctx.rng.pick(t.lineup), nat, clamp(pctx.rng.normal(if (hp.retired) 27.0 else 24.0, 2.5), 18.0, 33.0).roundToInt(),
                clamp((avg[t.id] ?: 62.0) - (if (hp.retired) 8 else 5) + pctx.rng.normal(0.0, 3.0), 35.0, 90.0))
            p.handle = hp.handle
            p.first = hp.first ?: ""
            p.last = hp.last ?: ""
            p.real = true
            p.realSrc = hp.src
            p.realEst = listOfNotNull("free agent", "age", if (roleId == null) "role (game assignment)" else null, if (hp.nat == null) "nationality" else null)
            applyFacts(p)
            curate(b, p)
            if (team != null) p.career.add(CareerEntry(w.season - 1, w.day, team.id, "released"))
        }
    }

    /**
     * Real teams the snapshot listed fewer than a full lineup for are completed with real free agents
     * (players of the same game without a team, the needed position first, the same region preferred).
     * Generated players are used only when no real free agent is left.
     */
    private fun fillShortRosters(b: Build) {
        val w = b.world
        for (team in b.short) {
            val t = Catalogue.get(team.titleId)
            val regions = GameData.expand(team.region).toSet()
            while (team.roster.size < t.teamSize) {
                val have = team.roster.map { w.players[it].roles[0] }.toSet()
                val need = t.lineup.firstOrNull { it !in have }
                val pool = w.players.filter { it.real && !it.retired && it.teamId < 0 && it.titleId == team.titleId }
                val pick = pool.filter { need == null || it.roles[0] == need || it.roles.getOrNull(1) == need }
                    .maxByOrNull { it.ca + (if (GameData.regionOf(it.nat) in regions) 8 else 0) + (if (it.roles[0] == need) 4 else 0) }
                    ?: pool.maxByOrNull { it.ca + (if (GameData.regionOf(it.nat) in regions) 8 else 0) }
                if (pick != null) {
                    if (need != null && pick.roles[0] != need) pick.roles = listOf(need) + pick.roles.filter { it != need }
                    sign(b, pick, team)
                    pick.career.add(CareerEntry(w.season, w.day, team.id, "signed"))
                    team.dataNote += "; ${pick.handle} (real free agent) completes the lineup"
                } else {
                    val roleId = need ?: t.lineup[0]
                    val gp = PlayerGen.generate(GenCtx(w, Rng.of("fill|${team.titleId}|${norm(team.name)}|${team.roster.size}")), team.titleId, roleId, w.orgs[team.orgId].country, 20, 60.0)
                    gp.realEst = listOf("generated: no real free agent left to complete ${team.name}")
                    sign(b, gp, team)
                    team.dataNote += "; 1 generated player (no real free agent left)"
                }
            }
        }
    }

    /** Free agents and prospects in each title (about 22% / 12% of the rostered players). */
    private fun freeAgents(b: Build) {
        val w = b.world
        for (id in w.titles) {
            val t = Catalogue.get(id)
            val pop = Catalogue.POPULARITY[id]!!
            val rostered = w.teams.count { it.titleId == id && it.active } * (t.teamSize + 2)
            val regs = w.leagues.filter { it.titleId == id }.flatMap { GameData.expand(it.region) }.ifEmpty { listOf("NA") }
            repeat((rostered * 0.22).roundToInt()) {
                PlayerGen.generate(b.ctx, id, null, GameData.pickNationality(b.rng, regs, t.isMoba, pop), clamp(b.rng.normal(22.0, 3.5), 16.0, 33.0).roundToInt(), clamp(b.rng.normal(58.0, 6.0), 35.0, 85.0))
            }
            repeat((rostered * 0.12).roundToInt()) {
                PlayerGen.generate(b.ctx, id, null, GameData.pickNationality(b.rng, regs, t.isMoba, pop), b.rng.int(14, 17), clamp(b.rng.normal(47.0, 6.0), 30.0, 68.0), b.rng.normal(4.0, 3.0))
            }
        }
    }
}

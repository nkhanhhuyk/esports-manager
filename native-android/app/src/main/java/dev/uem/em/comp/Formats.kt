package dev.uem.em.comp

import dev.uem.em.core.Rng
import dev.uem.em.model.Competition
import dev.uem.em.model.EventFormat
import dev.uem.em.model.FormatChange
import dev.uem.em.model.League
import dev.uem.em.model.Message
import dev.uem.em.model.News
import dev.uem.em.model.Stage
import dev.uem.em.model.StageType
import dev.uem.em.model.TitleFormat
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.TitleDef
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Competition formats that evolve (LoL, MLBB, HoK, Wild Rift): how many seeds each league has at First
 * Stand, MSI and Worlds (reviewed on the regions' international results), play-ins before the main
 * events (a two-team decider, a four-team group, eight teams in groups then a knockout, a sixteen-team
 * Swiss, a straight knockout), main-stage formats (Swiss or groups, single or double elimination),
 * best-of lengths, Worlds places by playoff placings or by circuit points, the mid-season champion's
 * direct place, regional finals, the best-of-the-rest invitational, and promotion / relegation versus
 * franchising. Nothing changes mid-season: changes come only with a major patch (after the world
 * championship), at most once every two seasons and not every time, and apply from the next season.
 * Every change is announced in the patch notes and the news, and discussed on social media.
 */
object Formats {
    val TITLES = setOf("lol", "mlbb", "hok", "wildrift")
    val KEYS = listOf("fs", "msi", "worlds")

    fun applies(titleId: String) = titleId in TITLES
    fun of(w: World, titleId: String): TitleFormat = w.formats.getOrPut(titleId) { TitleFormat(titleId) }
    fun event(w: World, titleId: String, key: String): EventFormat? = w.formats[titleId]?.events?.get(key)

    fun tier1(w: World, titleId: String): List<League> = w.leagues.filter { it.titleId == titleId && it.tier == 1 && it.until == 0 }.sortedByDescending { Season.leagueStrength(it) }

    /** An event's short name in a game (MSC, M-Series, KIC...). */
    fun eventName(titleId: String, key: String): String = Season.REAL_EVENTS[titleId]?.get(key)?.second ?: when (key) { "fs" -> "First Stand"; "msi" -> "MSI"; else -> "Worlds" }

    // ------------------------------------------------------------------ seeds

    /** The calendar's allocation before any change: First Stand split 1 winners (and runners-up), MSI split 2, Worlds by league strength. */
    fun defaultSeeds(titleId: String, key: String, tier1: List<League>): IntArray {
        val lol = titleId == "lol"
        return when (key) {
            "fs" -> IntArray(tier1.size) { i -> if (i >= 8) 0 else if (lol) (if (tier1[i].short in setOf("LCK", "LPL")) 2 else 1) else if (tier1.size <= 3 || i < 2) 2 else 1 }
            "msi" -> IntArray(tier1.size) { i -> if (lol) (if (tier1[i].short != "CBLOL") 2 else 1) else if (tier1.size <= 2 || i < 2 || tier1.size <= 4) 2 else 1 }
            else -> {
                val target = maxOf(4, minOf(16, tier1.size * 4))
                val s = Season.allocate(tier1.map { Season.leagueStrength(it) }, if (lol && target >= 8) target - 2 else target, 4)
                IntArray(tier1.size) { minOf(s[it], tier1[it].teamIds.size) }
            }
        }
    }

    /** Seeds per league (in [tier1] order) at an event this season. */
    fun seeds(w: World, titleId: String, key: String, tier1: List<League>, format: TitleFormat? = w.formats[titleId]): IntArray {
        val def = defaultSeeds(titleId, key, tier1)
        val tf = format ?: return def
        return IntArray(tier1.size) { i -> (tf.seeds["$key:${tier1[i].id}"] ?: def[i]).coerceIn(0, tier1[i].teamIds.size) }
    }

    /** Most seeds a league can hold at an event. */
    fun cap(key: String, l: League): Int = when (key) { "fs" -> 2; "msi" -> minOf(3, maxOf(1, l.teamIds.size / 3)); else -> minOf(6, maxOf(1, l.teamIds.size / 2)) }

    /** Indices of [tier1] by recent international results, best first (leagues without results by strength). */
    fun rankIdx(w: World, titleId: String, tier1: List<League>): List<Int> {
        val order = w.formats[titleId]?.rank.orEmpty()
        return tier1.indices.sortedWith(compareBy<Int> { order.indexOf(tier1[it].id).let { r -> if (r < 0) 99 else r } }.thenBy { it })
    }

    /** Main-event seeds when [direct] places go straight in: every league's first seed (by rank), then second seeds, and so on. */
    fun mainSeeds(seeds: IntArray, rank: List<Int>, direct: Int): IntArray {
        val main = IntArray(seeds.size)
        var left = direct
        var level = 0
        while (left > 0 && level < 12) {
            for (i in rank) { if (left == 0) break; if (seeds[i] > level) { main[i]++; left-- } }
            level++
        }
        return main
    }

    /** World championship places decided directly (by playoff placings or the points table): 1 → 1, 2–3 → 2, 4+ → 4. */
    fun direct(places: Int) = when { places <= 1 -> places; places <= 3 -> 2; else -> 4 }

    /** The mid-season champion qualifies directly for Worlds / its runner-up's region gets an extra seed. */
    fun winnerSpot(titleId: String, ef: EventFormat?) = ef?.winner?.takeIf { it >= 0 }?.let { it == 1 } ?: (titleId == "lol")
    fun runnerSeed(titleId: String, ef: EventFormat?) = ef?.runner?.takeIf { it >= 0 }?.let { it == 1 } ?: (titleId == "lol")

    /** Promotion and relegation for a top league: null = the calendar's default (only independent lower leagues other than its own division). */
    fun proRel(w: World, upper: League): Boolean? = w.formats[upper.titleId]?.proRel?.get(upper.id)

    /** Lower leagues that could send teams up: independent (not academy), at least four clubs. */
    fun independentLowers(w: World, upper: League): List<League> =
        w.leagues.filter { l -> l.tier == 2 && l.upperId == upper.id && !l.academy && l.teamIds.count { w.teams[it].parentId < 0 } >= 4 }.sortedByDescending { Season.leagueStrength(it) }

    /** Whether a league currently plays promotion / relegation. */
    fun hasProRel(w: World, upper: League): Boolean = when (proRel(w, upper)) {
        true -> independentLowers(w, upper).isNotEmpty()
        false -> false
        null -> independentLowers(w, upper).any { it.id != upper.lowerId }
    }

    /** The lower leagues in this season's promotion tournament. */
    fun promotionLowers(w: World, upper: League): List<League> = when (proRel(w, upper)) {
        true -> independentLowers(w, upper)
        false -> emptyList()
        null -> independentLowers(w, upper).filter { it.id != upper.lowerId }
    }

    // ------------------------------------------------------------------ stages

    private val ALL = listOf(0, 1, 2, 3, 4, 5, 6)

    private fun bracketDefault(key: String) = if (key == "msi") "double" else "single"

    /** The first stage an event plays by default with [n] teams. */
    private fun groupDefault(key: String, n: Int) = when (key) {
        "msi" -> if (n > 8) "groups" else "none"
        else -> if (n >= 16) "swiss" else if (n >= 7) "groups" else "none"
    }

    fun effGroup(key: String, ef: EventFormat?, n: Int) = ef?.group?.takeIf { it.isNotEmpty() } ?: groupDefault(key, n)
    fun effBracket(key: String, ef: EventFormat?) = ef?.bracket?.takeIf { it.isNotEmpty() } ?: bracketDefault(key)
    fun effBo(t: TitleDef, ef: EventFormat?) = ef?.bo?.takeIf { it > 0 } ?: t.formats.second.n
    fun effGroupBo(t: TitleDef, ef: EventFormat?) = ef?.groupBo?.takeIf { it > 0 } ?: t.formats.first.n

    /** A main event's stages under its format ([default] when the format was never changed). */
    fun mainStages(t: TitleDef, key: String, ef: EventFormat?, n: Int, start: Int, default: List<Stage>): List<Stage> {
        if (ef == null || (ef.group.isEmpty() && ef.bracket.isEmpty() && ef.bo == 0 && ef.groupBo == 0)) return default
        val gbo = effGroupBo(t, ef)
        val bo = effBo(t, ef)
        var group = effGroup(key, ef, n)
        if (group == "swiss" && (n < 8 || n % 2 == 1)) group = if (n >= 7) "groups" else "none"
        if (group == "groups" && n < 6) group = "none"
        var bracket = effBracket(key, ef)
        val out = ArrayList<Stage>()
        var ko = start
        var koN = n
        when (group) {
            "swiss" -> {
                val wins = if (n >= 16) 3 else 2
                val adv = if (n >= 16) 8 else 4
                out.add(Stage("Swiss Stage", StageType.SWISS, start, gbo, ALL, advance = adv, daysBetween = 1, swissWins = wins, swissLosses = wins))
                ko = start + 2 * wins + 3; koN = adv
            }
            "groups" -> {
                var g = maxOf(2, (n / 4.0).roundToInt())
                if (bracket == "double") g = minOf(g, 4)
                val per = ceil(n.toDouble() / g).toInt()
                val adv = if (bracket == "double") maxOf(1, 8 / g) else 2
                out.add(Stage("Group Stage", StageType.GROUPS, start, gbo, ALL, groups = g, advance = adv, daysBetween = 1))
                ko = start + per + 2; koN = adv * g
            }
        }
        if (bracket == "double" && koN > 8) bracket = "single"
        out.add(if (bracket == "double") Stage("Bracket Stage", StageType.DOUBLE_ELIM, ko, bo, listOf(5, 6), daysBetween = 2)
            else Stage("Knockout Stage", StageType.SINGLE_ELIM, ko, bo, listOf(5, 6), daysBetween = 3))
        return out
    }

    /** A play-in's stages: [n] teams, the top [adv] go to the main event. */
    fun playInStages(t: TitleDef, kind: String, n: Int, adv: Int, start: Int): List<Stage> {
        val std = t.formats.first.n
        val po = t.formats.second.n
        val k = when { kind == "decider" && n > 2 -> "knockout"; kind == "swiss16" && (n < 10 || n % 2 == 1) -> "group8"; kind == "group8" && n < 6 -> "group4"; else -> kind }
        return when (k) {
            "decider" -> listOf(Stage("Play-In Decider", StageType.SINGLE_ELIM, start, 5, ALL, daysBetween = 1))
            "group4" -> listOf(Stage("Play-In Group", StageType.GROUPS, start, std, ALL, groups = 1, advance = adv, daysBetween = 1))
            "group8" -> listOf(Stage("Play-In Groups", StageType.GROUPS, start, std, ALL, groups = 2, advance = 2, daysBetween = 1),
                Stage("Play-In Knockout", StageType.SINGLE_ELIM, start + ceil(n / 2.0).toInt() + 1, po, ALL, daysBetween = 1))
            "swiss16" -> listOf(Stage("Play-In Swiss", StageType.SWISS, start, std, ALL, advance = adv, daysBetween = 1))
            else -> listOf(Stage("Play-In Knockout", StageType.SINGLE_ELIM, start, po, ALL, daysBetween = 1))
        }
    }

    /** Days a play-in takes before the main event starts. */
    fun playInDays(kind: String, n: Int): Int = when (kind) {
        "decider" -> if (n > 2) ceil(ln(n.toDouble()) / ln(2.0)).toInt() + 2 else 2
        "group4" -> n + 1
        "group8" -> ceil(n / 2.0).toInt() + 4
        "swiss16" -> 7
        else -> ceil(ln(maxOf(2, n).toDouble()) / ln(2.0)).toInt() + 2
    }

    /** Teams and places of a play-in kind: (teams, advance) options. */
    private fun playInShapes(kind: String): List<Pair<Int, Int>> = when (kind) {
        "decider" -> listOf(2 to 1)
        "group4" -> listOf(4 to 1, 4 to 2)
        "group8" -> listOf(8 to 2)
        "swiss16" -> listOf(16 to 8)
        else -> listOf(4 to 1, 8 to 2, 8 to 4)
    }

    private val PLAYIN_TEXT = mapOf("decider" to "a two-team decider for the last place", "group4" to "a four-team group", "group8" to "eight teams in two groups, then a knockout",
        "swiss16" to "a sixteen-team Swiss stage", "knockout" to "a straight knockout")

    fun playInText(kind: String, n: Int, adv: Int): String = "${PLAYIN_TEXT[kind] ?: kind} ($n teams, ${if (adv == 1) "the winner goes" else "the top $adv go"} to the main event)"

    /** A competition's stages in words: "Swiss Stage (Bo3, 3 wins) → Knockout Stage (single elimination, Bo5)". */
    fun stagesText(c: Competition): String = c.stages.joinToString(" → ") { s ->
        val bo = "Bo${s.bestOf}"
        when (s.type) {
            StageType.SWISS -> "${s.name} ($bo, ${s.swissWins} wins to advance)"
            StageType.GROUPS -> "${s.name} (${if (s.groups <= 1) "one group" else "${s.groups} groups"}, $bo, top ${s.advance}${if (s.groups > 1) " of each" else ""})"
            StageType.SINGLE_ELIM -> "${s.name} (single elimination, $bo)"
            StageType.DOUBLE_ELIM -> "${s.name} (double elimination, $bo)"
            StageType.RR -> "${s.name} (round robin, $bo)"
            StageType.DRR -> "${s.name} (double round robin, $bo)"
        }
    }

    // ------------------------------------------------------------------ the current structure in words

    /** Next season's international structure of a game, one line per event and rule. */
    fun summary(w: World, titleId: String): List<String> {
        if (!applies(titleId)) return emptyList()
        val tier1 = tier1(w, titleId)
        if (tier1.isEmpty()) return emptyList()
        val tf = w.formats[titleId]
        val t = Catalogue.get(titleId)
        val out = ArrayList<String>()
        for (key in KEYS) {
            val s = seeds(w, titleId, key, tier1)
            val ef = tf?.events?.get(key)
            val win = key == "worlds" && winnerSpot(titleId, ef)
            val run = key == "worlds" && runnerSeed(titleId, ef)
            val total = s.sum() + (if (win) 1 else 0) + (if (run) 1 else 0)
            val pi = ef?.playIn.orEmpty()
            val head = "${eventName(titleId, key)}: " + tier1.indices.filter { s[it] > 0 }.joinToString { "${tier1[it].short} ${s[it]}" } +
                (if (win) ", MSI winner" else "") + (if (run) ", +1 for the MSI runner-up's region" else "")
            val size = if (pi.isNotEmpty() && ef != null && ef.size > 0) "main event ${ef.size} teams after a play-in — ${playInText(pi, total - ef.size + ef.playInAdvance, ef.playInAdvance)}" else "$total teams"
            val stage = "${effGroup(key, ef, if (pi.isNotEmpty() && ef != null && ef.size > 0) ef.size else total).let { if (it == "none") "straight to the bracket" else if (it == "swiss") "Swiss stage" else "group stage" }} (Bo${effGroupBo(t, ef)}), " +
                "${effBracket(key, ef)} elimination (Bo${effBo(t, ef)})"
            out.add("$head · $size · $stage")
        }
        val wef = tf?.events?.get("worlds")
        out.add("Worlds places: " + (if (wef?.qual == "points") "the best of each league on circuit points" else "the last split's playoffs (champion, finalists, semi-finalists)") +
            (if (tf?.lastChance != false) "; other places through regional finals (the next six on circuit points)" else "; other places on circuit points"))
        out.add(if (tf?.invitational != false) "Best-of-the-rest invitational for teams that miss Worlds (2–4 per league on circuit points)" else "No best-of-the-rest invitational")
        for (l in tier1) out.add("${l.short}: " + if (hasProRel(w, l)) "promotion and relegation with ${promotionLowers(w, l).joinToString { it.short }}" else "franchised (no relegation)")
        return out
    }

    // ------------------------------------------------------------------ impact preview (read-only)

    /** About how many series a stage set plays with [n] teams (estimate for previews). */
    private fun estMatches(key: String, ef: EventFormat?, n: Int): Int {
        if (n < 2) return 0
        val g = effGroup(key, ef, n); val b = effBracket(key, ef)
        var m = 0; var ko = n
        when (g) {
            "swiss" -> { m += (n * (if (n >= 16) 33 else 10)) / (if (n >= 16) 16 else 8); ko = if (n >= 16) 8 else 4 }
            "groups" -> { val gs = maxOf(2, (n / 4.0).roundToInt()); val per = ceil(n.toDouble() / gs).toInt(); m += gs * per * (per - 1) / 2; ko = gs * 2 }
        }
        m += if (b == "double") 2 * ko - 1 else ko - 1
        return m
    }

    /** Teams at an event under a format: (main event, play-in). */
    private fun sizes(w: World, titleId: String, key: String, tier1: List<League>, tf: TitleFormat?): Pair<Int, Int> {
        val s = seeds(w, titleId, key, tier1, tf).sum()
        val ef = tf?.events?.get(key)
        val win = if (key == "worlds" && winnerSpot(titleId, ef)) 1 else 0
        val run = if (key == "worlds" && runnerSeed(titleId, ef)) 1 else 0
        if (ef != null && ef.playIn.isNotEmpty() && ef.size > 0) return ef.size to (s - (ef.size - ef.playInAdvance - win) + run)
        return (s + win + run) to 0
    }

    /**
     * Old vs proposed for an announced format, and — illustrative only — which teams today's points table would
     * move in or out of the Worlds places. Pure: nothing in the world changes.
     */
    fun preview(w: World, titleId: String, next: TitleFormat): List<String> {
        val cur = w.formats[titleId]
        val tier1 = tier1(w, titleId)
        val out = ArrayList<String>()
        for (key in KEYS) {
            val ev = eventName(titleId, key)
            val (ma, pa) = sizes(w, titleId, key, tier1, cur); val (mb, pb) = sizes(w, titleId, key, tier1, next)
            val ea = cur?.events?.get(key); val eb = next.events[key]
            val sa = seeds(w, titleId, key, tier1, cur); val sb = seeds(w, titleId, key, tier1, next)
            val lines = ArrayList<String>()
            if (ma != mb || pa != pb) lines.add("teams ${ma + pa} → ${mb + pb} (main event $ma → $mb, play-in $pa → $pb)")
            val moved = tier1.indices.filter { sa[it] != sb[it] }
            if (moved.isNotEmpty()) lines.add("seeds " + moved.joinToString { "${tier1[it].short} ${sa[it]} → ${sb[it]}" })
            if (effGroup(key, ea, ma) != effGroup(key, eb, mb) || effBracket(key, ea) != effBracket(key, eb)) lines.add("format ${effGroup(key, ea, ma)} + ${effBracket(key, ea)} → ${effGroup(key, eb, mb)} + ${effBracket(key, eb)}")
            if ((ea?.bo ?: 0) != (eb?.bo ?: 0) || (ea?.groupBo ?: 0) != (eb?.groupBo ?: 0)) lines.add("best-of: first stage Bo${effGroupBo(Catalogue.get(titleId), ea)} → Bo${effGroupBo(Catalogue.get(titleId), eb)}, bracket Bo${effBo(Catalogue.get(titleId), ea)} → Bo${effBo(Catalogue.get(titleId), eb)}")
            if ((ea?.playIn ?: "") != (eb?.playIn ?: "")) lines.add("play-in: ${ea?.playIn?.ifEmpty { null } ?: "none"} → ${eb?.playIn?.ifEmpty { null } ?: "none"}")
            if (key == "worlds" && (ea?.qual ?: "") != (eb?.qual ?: "")) lines.add("league places by ${if (ea?.qual == "points") "circuit points" else "playoff placings"} → by ${if (eb?.qual == "points") "circuit points" else "playoff placings"}")
            if (key == "worlds" && (winnerSpot(titleId, ea) != winnerSpot(titleId, eb) || runnerSeed(titleId, ea) != runnerSeed(titleId, eb))) lines.add("MSI winner's direct place ${if (winnerSpot(titleId, ea)) "yes" else "no"} → ${if (winnerSpot(titleId, eb)) "yes" else "no"}, runner-up region seed ${if (runnerSeed(titleId, ea)) "yes" else "no"} → ${if (runnerSeed(titleId, eb)) "yes" else "no"}")
            if (lines.isNotEmpty()) out.add("$ev: " + lines.joinToString("; ") + " · about ${estMatches(key, ea, ma) + (if (pa > 1) pa else 0)} → ${estMatches(key, eb, mb) + (if (pb > 1) pb else 0)} series")
            if (key == "worlds" && moved.isNotEmpty()) {
                val pts = Progress.circuitPoints(w, w.season, titleId)
                for (i in moved) {
                    val order = tier1[i].teamIds.sortedByDescending { pts[it] ?: 0.0 }.map { w.teams[it].short }
                    val (lo, hi) = minOf(sa[i], sb[i]) to maxOf(sa[i], sb[i])
                    val who = order.subList(minOf(lo, order.size), minOf(hi, order.size))
                    if (who.isNotEmpty()) out.add("Illustrative (today's points table as if final): ${tier1[i].short} — ${who.joinToString()} would ${if (sb[i] > sa[i]) "gain" else "lose"} a Worlds place")
                }
            }
        }
        if (cur?.lastChance != next.lastChance) out.add("Regional finals: ${if (next.lastChance) "added" else "removed"}")
        if (cur?.invitational != next.invitational) out.add("Best-of-the-rest invitational: ${if (next.invitational) "added" else "removed"}")
        if (cur?.academyCup != next.academyCup) out.add("Continental Academy Champions League: ${if (next.academyCup) "added" else "removed"}")
        return out.ifEmpty { listOf("No structural difference from the current format.") }
    }

    // ------------------------------------------------------------------ results by region

    /**
     * How well each league's teams did at the internationals of [from]..[to]: the average of their finishing
     * positions (1 for a title, 0 for last; a team out in a play-in counts as last), weighted by event (First
     * Stand 0.7, MSI 1, Worlds 1.4). League id → (score, entries).
     */
    fun performance(w: World, titleId: String, from: Int, to: Int): Map<Int, Pair<Double, Int>> {
        val sum = HashMap<Int, Double>(); val wt = HashMap<Int, Double>(); val cnt = HashMap<Int, Int>()
        fun add(team: Int, score: Double, weight: Double) {
            val l = w.leagues.getOrNull(w.teams[team].leagueId)?.takeIf { it.tier == 1 && it.titleId == titleId } ?: return
            sum.merge(l.id, score * weight, Double::plus); wt.merge(l.id, weight, Double::plus); cnt.merge(l.id, 1, Int::plus)
        }
        for (c in w.comps) {
            if (c.titleId != titleId || c.season !in from..to || !c.done || c.placements.isEmpty()) continue
            val base = c.defKey.removePrefix("$titleId:")
            val key = base.removeSuffix(":playin")
            val weight = when (key) { "fs" -> 0.7; "msi" -> 1.0; "worlds" -> 1.4; else -> continue }
            if (base.endsWith(":playin")) {
                val adv = w.comps.firstOrNull { it.season == c.season && it.defKey == "$titleId:$key" }?.entrants?.firstOrNull { it.startsWith("qual:${c.id}:") }?.substringAfterLast(':')?.toIntOrNull() ?: 1
                for (tid in c.placements.drop(adv)) add(tid, 0.0, weight)
                continue
            }
            val n = c.placements.size
            c.placements.forEachIndexed { i, tid -> add(tid, 1.0 - i.toDouble() / maxOf(1, n - 1), weight) }
        }
        return sum.keys.associateWith { (sum[it]!! / wt[it]!!) to (cnt[it] ?: 0) }
    }

    private fun pct(score: Double) = "${((1 - score) * 100).roundToInt().coerceIn(1, 100)}%"

    // ------------------------------------------------------------------ evolution (major patches)

    private class Ctx(val w: World, val t: TitleDef, val tf: TitleFormat, val tier1: List<League>, val rng: Rng, val perf: Map<Int, Pair<Double, Int>>) {
        val rank: List<Int> get() = rankIdx(w, t.id, tier1)
        fun seedsOf(key: String) = seeds(w, t.id, key, tier1)
        fun set(key: String, s: IntArray) { tier1.forEachIndexed { i, l -> tf.seeds["$key:${l.id}"] = s[i] } }
        fun ef(key: String) = tf.events.getOrPut(key) { EventFormat() }
        fun win(key: String) = key == "worlds" && winnerSpot(t.id, tf.events[key])
        fun run(key: String) = key == "worlds" && runnerSeed(t.id, tf.events[key])
        /** Teams in the main event. */
        fun mainSize(key: String): Int { val ef = tf.events[key]; return if (ef != null && ef.playIn.isNotEmpty() && ef.size > 0) ef.size else seedsOf(key).sum() + (if (win(key)) 1 else 0) + (if (run(key)) 1 else 0) }
        /** Play-in teams (0: none). */
        fun playInSize(key: String): Int { val ef = tf.events[key] ?: return 0; if (ef.playIn.isEmpty() || ef.size <= 0) return 0
            val direct = ef.size - ef.playInAdvance - (if (win(key)) 1 else 0); return seedsOf(key).sum() - direct + (if (run(key)) 1 else 0) }
        /** Add (delta > 0, best-ranked first) or remove (worst first) seeds; null if the leagues cannot take it. */
        fun adjust(key: String, delta: Int): IntArray? {
            val s = seedsOf(key).copyOf()
            var left = kotlin.math.abs(delta)
            var guard = 0
            while (left > 0 && guard++ < 40) {
                var moved = false
                for (i in if (delta > 0) rank else rank.reversed()) {
                    if (left == 0) break
                    if (delta > 0 && s[i] < cap(key, tier1[i])) { s[i]++; left--; moved = true }
                    else if (delta < 0 && s[i] > 1) { s[i]--; left--; moved = true }
                }
                if (!moved) break
            }
            return if (left == 0) s else null
        }
        fun diff(key: String, before: IntArray, after: IntArray): String = tier1.indices.filter { before[it] != after[it] }.joinToString { "${tier1[it].short} ${before[it]} → ${after[it]}" }
        fun changed(before: IntArray, after: IntArray, up: Boolean) = tier1.indices.filter { if (up) after[it] > before[it] else after[it] < before[it] }.map { tier1[it].id }
    }

    /**
     * Major patch: a review of the game's competitions, at most once every two seasons and only some of the
     * time. One to three changes for next season, written to the log (news, inbox, social media read it).
     * [force] > 0 makes that many changes now (tests, the commissioner).
     */
    fun evolve(w: World, t: TitleDef, rng: Rng, force: Int = 0, ahead: Boolean? = null): List<String> {
        if (!applies(t.id)) return emptyList()
        val tier1 = tier1(w, t.id)
        if (tier1.size < 2) return emptyList()
        val tf = of(w, t.id)
        if (force <= 0) {
            if (tf.lastReview == 0) { tf.lastReview = if (w.firstSeason > 0) w.firstSeason else w.season; if (w.season - tf.lastReview < 2) return emptyList() }
            if (w.season - tf.lastReview < 2) return emptyList()
            if (!rng.chance(0.5)) return emptyList()
        }
        tf.lastReview = w.season
        val x = review(w, t, tf, tier1, rng)
        val kinds = arrayListOf<Pair<String, Double>>(
            "seeds:worlds" to 3.0, "seeds:msi" to 2.0, "size:worlds" to 1.1, "size:msi" to 0.9, "size:fs" to 0.5,
            "playin:worlds" to 1.6, "playin:msi" to 1.0, "stage:worlds" to 1.2, "stage:msi" to 0.9, "stage:fs" to 0.6,
            "bo:worlds" to 0.8, "bo:msi" to 0.6, "qual" to 0.8, "winner" to 0.4, "runner" to 0.4, "lcq" to 0.5, "dcgi" to 0.35, "prorel" to 1.0,
            "promofmt" to 0.5, "cacl" to 0.25,
        )
        // League reforms (expansion, merger, split) are rarer still: at most one every five seasons.
        if (tf.log.none { it.kind in LEAGUE_KINDS && w.season - it.season < 5 }) { kinds.add("expandleague" to 0.5); kinds.add("merge" to 0.35); kinds.add("split" to 0.35); kinds.add("recognize" to 0.3); kinds.add("associate" to 0.2) }
        kinds.add("hostpolicy" to 0.3); kinds.add("allstar" to 0.2)
        val want = if (force > 0) force else rng.int(1, 3)
        // Most reforms apply from next season; some are announced a season further ahead (time to plan rosters and budgets).
        val longNotice = ahead ?: (force <= 0 && rng.chance(0.3))
        val effective = w.season + if (longNotice) 2 else 1
        val before = if (longNotice) snapshot(tf) else ""
        val made = ArrayList<FormatChange>()
        var guard = 0
        while (made.size < want && kinds.isNotEmpty() && guard++ < 40) {
            val k = rng.weighted(kinds) { it.second }
            kinds.remove(k)
            change(x, k.first, effective)?.let { made.add(it) }
        }
        if (made.isEmpty()) return emptyList()
        val n0 = tf.log.count { it.season == w.season }
        val ids = made.mapIndexed { i, _ -> "${t.id}-${w.season}-${n0 + i + 1}" }
        val logged = made.mapIndexed { i, c -> FormatChange(c.season, c.day, c.kind, c.event, (if (longNotice) "From $effective: " else "") + c.text, c.why, c.leagues, c.gain, ids[i], effective, "announced") }
        tf.log.addAll(logged)
        for (c in logged) if (c.kind in LEAGUE_KINDS) tf.pending.lastOrNull { it.kind == c.kind && it.ids.isEmpty() }?.ids = listOf(c.id)
        // Advance notice: the new format is stored and the current one restored until its season (league reforms are already pending).
        if (longNotice) {
            val formatIds = logged.filter { it.kind !in LEAGUE_KINDS }.map { it.id }
            if (formatIds.isNotEmpty()) {
                val after = snapshot(tf)
                restore(tf, before)
                tf.pending.add(dev.uem.em.model.PendingChange("format", effective, w.day, logged.filter { it.id in formatIds }.joinToString("; ") { it.text.removePrefix("From $effective: ") }, "", state = after, ids = formatIds))
            }
        }
        val head = "${t.short} competition changes for $effective"
        w.news.add(News(w.day, head, (if (longNotice) "Announced now, in effect from the $effective season: " else "In effect from next season: ") + made.joinToString(" ") { it.text + "." }))
        if (w.userOrg >= 0 && w.orgs[w.userOrg].teams.any { w.teams[it].titleId == t.id }) w.inbox.add(Message(w.day, "Competition office", head, logged.joinToString("\n\n") { "[${it.id}] ${it.text}. ${it.why}" }))
        return logged.map { "Competitions [${it.id}] — ${it.text}" }
    }

    val LEAGUE_KINDS = setOf("expandleague", "merge", "split", "recognize", "associate")

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The structural part of a game's format as JSON (log, announcements and review dates left out). */
    private fun snapshot(tf: TitleFormat): String = json.encodeToString(TitleFormat.serializer(),
        TitleFormat(tf.titleId, HashMap(tf.events), HashMap(tf.seeds), tf.rank, HashMap(tf.proRel), tf.lastChance, tf.invitational, 0, ArrayList(), HashMap(tf.promo), tf.academyCup, ArrayList(), HashMap(tf.hosts), tf.allStar))

    private fun restore(tf: TitleFormat, state: String) {
        val s = json.decodeFromString(TitleFormat.serializer(), state)
        tf.events.clear(); tf.events.putAll(s.events)
        tf.seeds.clear(); tf.seeds.putAll(s.seeds)
        tf.rank = s.rank
        tf.proRel.clear(); tf.proRel.putAll(s.proRel)
        tf.lastChance = s.lastChance; tf.invitational = s.invitational
        tf.promo.clear(); tf.promo.putAll(s.promo)
        tf.academyCup = s.academyCup
        tf.hosts.clear(); tf.hosts.putAll(s.hosts)
        tf.allStar = s.allStar
    }

    /** The game's format as an announcement will make it (for the preview), or null. */
    fun pendingFormat(p: dev.uem.em.model.PendingChange): TitleFormat? = if (p.kind == "format" && p.state.isNotEmpty()) json.decodeFromString(TitleFormat.serializer(), p.state) else null

    /**
     * Before a season's calendar is drawn up: announcements for this season take effect (a new format, or a league
     * expanded, merged or split), and the season's log entries become effective. A league reform that can no
     * longer be carried out (a league too small or gone) is rejected, with the reason in the news.
     */
    fun applyPending(w: World, titleId: String, season: Int) {
        val tf = w.formats[titleId] ?: return
        // A new format first, then league reforms (so seeds a merger or split moves are not overwritten).
        for (p in tf.pending.sortedBy { if (it.kind == "format") 0 else 1 }) {
            if (p.status != "announced" || p.effective > season) continue
            val ok = when (p.kind) {
                "format" -> { restore(tf, p.state); true }
                else -> Leagues.apply(w, tf, p, season)
            }
            p.status = if (ok) "effective" else "rejected"
            for (c in tf.log) if (c.id in p.ids) c.status = p.status
            if (!ok) w.news.add(News(w.day, "${Catalogue.get(titleId).short} reform not carried out", "${p.text} — rejected: the leagues no longer meet the conditions it was announced under."))
            else if (p.effective > p.announced.let { dev.uem.em.core.Dates.year(it) }) w.news.add(News(w.day, "${Catalogue.get(titleId).short}: now in effect", p.text))
        }
        for (c in tf.log) if (c.status == "announced" && c.effective in 1..season && tf.pending.none { c.id in it.ids }) c.status = "effective"
    }

    /** Before changes: seeds written out (the calendar's allocation becomes explicit) and leagues ranked on recent results. */
    private fun review(w: World, t: TitleDef, tf: TitleFormat, tier1: List<League>, rng: Rng): Ctx {
        for (key in KEYS) { val s = seeds(w, t.id, key, tier1); tier1.forEachIndexed { i, l -> tf.seeds.putIfAbsent("$key:${l.id}", s[i]) } }
        val perf = performance(w, t.id, w.season - 1, w.season)
        tf.rank = tier1.sortedByDescending { l -> (perf[l.id]?.takeIf { it.second >= 2 }?.first ?: 0.35) + Season.leagueStrength(l) / 700.0 }.map { it.id }
        return Ctx(w, t, tf, tier1, rng, perf)
    }

    /**
     * One change of [kind] now ("playin:worlds", "stage:msi", "qual", "prorel", ...; see [evolve]), logged like
     * a major patch's. For tests and the commissioner; null when that change is not possible.
     */
    fun force(w: World, t: TitleDef, kind: String, rng: Rng): FormatChange? {
        if (!applies(t.id)) return null
        val tier1 = tier1(w, t.id)
        if (tier1.size < 2) return null
        val tf = of(w, t.id)
        val c = change(review(w, t, tf, tier1, rng), kind) ?: return null
        val ch = FormatChange(c.season, c.day, c.kind, c.event, c.text, c.why, c.leagues, c.gain, "${t.id}-${w.season}-${tf.log.count { it.season == w.season } + 1}", w.season + 1, "announced")
        tf.log.add(ch)
        if (ch.kind in LEAGUE_KINDS) tf.pending.lastOrNull { it.kind == ch.kind && it.ids.isEmpty() }?.ids = listOf(ch.id)
        return ch
    }

    private fun change(x: Ctx, kind: String, effective: Int = x.w.season + 1): FormatChange? {
        val w = x.w; val rng = x.rng
        val key = kind.substringAfter(':', "worlds")
        val ev = eventName(x.t.id, key)
        fun fc(k: String, text: String, why: String, leagues: List<Int> = emptyList(), gain: List<Int> = emptyList()) = FormatChange(w.season, w.day, k, key, text, why, leagues, gain)
        when (kind.substringBefore(':')) {
            // Seeds follow results: a league that clearly did better at the internationals than one with as many or more seeds takes a seed from it.
            "seeds" -> {
                val s = x.seedsOf(key)
                var best: Pair<Int, Int>? = null
                var gap = 0.0
                for (a in x.tier1.indices) for (b in x.tier1.indices) {
                    if (a == b) continue
                    val pa = x.perf[x.tier1[a].id]?.takeIf { it.second >= 2 }?.first ?: continue
                    val pb = x.perf[x.tier1[b].id]?.takeIf { it.second >= 2 }?.first ?: continue
                    if (pa - pb > 0.12 && s[a] <= s[b] && s[a] < cap(key, x.tier1[a]) && s[b] > 1 && pa - pb > gap) { gap = pa - pb; best = a to b }
                }
                val (a, b) = best ?: return null
                val after = s.copyOf().also { it[a]++; it[b]-- }
                x.set(key, after)
                val la = x.tier1[a]; val lb = x.tier1[b]
                return fc("seeds", "$ev seeds: ${la.short} ${s[a]} → ${after[a]}, ${lb.short} ${s[b]} → ${after[b]}",
                    "Over the last two seasons' internationals ${la.short} teams finished in the top ${pct(x.perf[la.id]!!.first)} on average, ${lb.short} teams in the top ${pct(x.perf[lb.id]!!.first)}.",
                    listOf(la.id, lb.id), listOf(la.id))
            }
            // Expansion or reduction of an event.
            "size" -> {
                val n = x.mainSize(key)
                val (lo, hi) = when (key) { "fs" -> 4 to 10; "msi" -> 6 to 16; else -> 8 to 24 }
                val step = if (key == "fs") 2 else rng.pick(listOf(2, 4))
                val grow = when { n + step > hi -> false; n - step < lo -> true; else -> rng.chance(0.55) }
                val before = x.seedsOf(key)
                val after = x.adjust(key, if (grow) step else -step) ?: return null
                x.set(key, after)
                x.tf.events[key]?.let { if (it.playIn.isNotEmpty() && it.size > 0) it.size += if (grow) step else -step }
                val m = x.mainSize(key)
                return fc(if (grow) "expand" else "shrink", "$ev ${if (grow) "expands" else "shrinks"} from $n to $m teams (${x.diff(key, before, after)})",
                    if (grow) "More regions' contenders get a stage; the extra places go to the leagues with the best recent results." else "Fewer, stronger teams: the places come off the leagues with the weakest recent results.",
                    x.changed(before, after, grow), if (grow) x.changed(before, after, true) else emptyList())
            }
            // Play-ins: added, removed or reshaped. The main event keeps its size; its lowest seeds move to the play-in.
            "playin" -> {
                val ef = x.ef(key)
                val win = if (x.win(key)) 1 else 0; val run = if (x.run(key)) 1 else 0
                val before = x.seedsOf(key)
                if (ef.playIn.isEmpty()) {
                    val n = x.mainSize(key)
                    val kinds = listOf("decider", "group4", "group8", "swiss16", "knockout").filter { key != "fs" || it in setOf("decider", "group4") }.filter { it != "swiss16" || n >= 16 }
                    for (k in rng.shuffle(kinds.toMutableList())) {
                        val (p, a) = rng.pick(playInShapes(k))
                        if (a >= n - win - 1) continue
                        val after = x.adjust(key, p - a) ?: continue
                        x.set(key, after)
                        ef.playIn = k; ef.playInAdvance = a; ef.size = n
                        return fc("playin+", "$ev adds a play-in: ${playInText(k, p, a)}; the main event keeps $n places" + (if (p - a > 0) " (new seeds: ${x.diff(key, before, after)})" else ""),
                            "The lowest seeds now have to earn their place in the main event${if (p - a > 0) ", and more teams get to travel" else ""}.", x.changed(before, after, true), x.changed(before, after, true))
                    }
                    return null
                }
                val p0 = x.playInSize(key)
                if (rng.chance(0.4)) {
                    val n = ef.size
                    val after = x.adjust(key, (n - win - run) - before.sum()) ?: return null
                    x.set(key, after)
                    val old = ef.playIn
                    ef.playIn = ""; ef.playInAdvance = 0; ef.size = 0
                    return fc("playin-", "$ev drops its play-in (${PLAYIN_TEXT[old] ?: old}); every seed goes straight to the $n-team main event" + x.diff(key, before, after).let { if (it.isEmpty()) "" else " ($it)" },
                        "Every qualified team plays the main event; the play-in's extra places are gone.", x.changed(before, after, false))
                }
                val n = ef.size
                for (k in rng.shuffle(listOf("decider", "group4", "group8", "swiss16", "knockout").filter { it != ef.playIn && (key != "fs" || it in setOf("decider", "group4")) && (it != "swiss16" || n >= 16) }.toMutableList())) {
                    val (p, a) = rng.pick(playInShapes(k))
                    if (a >= n - win - 1) continue
                    val delta = (p + n - a - win - run) - before.sum()
                    val after = if (delta == 0) before else x.adjust(key, delta) ?: continue
                    x.set(key, after)
                    val old = ef.playIn
                    ef.playIn = k; ef.playInAdvance = a
                    return fc("playin~", "$ev play-in: ${PLAYIN_TEXT[old] ?: old} ($p0 teams) becomes ${playInText(k, p, a)}" + x.diff(key, before, after).let { if (it.isEmpty()) "" else " ($it)" },
                        "A new route into the main event for the lower seeds.", x.changed(before, after, delta > 0) + x.changed(before, after, delta < 0), x.changed(before, after, true))
                }
                return null
            }
            // Main-stage format: Swiss ↔ groups, single ↔ double elimination.
            "stage" -> {
                val ef = x.ef(key)
                val n = x.mainSize(key)
                val g = effGroup(key, ef, n); val b = effBracket(key, ef)
                val opts = ArrayList<String>()
                if (n >= 8 && n % 2 == 0 && g != "swiss") opts.add("swiss")
                if (n >= 7 && g != "groups") opts.add("groups")
                if (n <= 8 && g != "none") opts.add("none")
                opts.add(if (b == "single") "double" else "single")
                val pick = rng.pick(opts)
                fun gText(v: String) = when (v) { "swiss" -> "a Swiss stage"; "groups" -> "a group stage"; else -> "no first stage" }
                return if (pick == "single" || pick == "double") {
                    ef.bracket = pick; if (ef.group.isEmpty()) ef.group = g
                    fc("bracket", "$ev bracket: ${b} → $pick elimination", if (pick == "double") "One bad series no longer ends a team's run before the final rounds." else "Every bracket series is win-or-go-home.")
                } else {
                    ef.group = pick; if (ef.bracket.isEmpty()) ef.bracket = b
                    fc("group", "$ev main stage: ${gText(g)} → ${gText(pick)}", when (pick) { "swiss" -> "Teams meet opponents with the same record; three wins to advance."; "groups" -> "Fixed groups, everyone plays everyone in their group."; else -> "Straight into the bracket." })
                }
            }
            // Series lengths.
            "bo" -> {
                val ef = x.ef(key)
                val t = x.t
                return if (rng.chance(0.5)) {
                    val old = effGroupBo(t, ef); val nb = if (old <= 1) 3 else 1
                    ef.groupBo = nb; if (ef.bracket.isEmpty()) ef.bracket = effBracket(key, ef)
                    fc("bo", "$ev first stage series: Bo$old → Bo$nb", if (nb == 1) "Faster, more chaotic group days." else "Longer series in the first stage: fewer upsets, more drafts.")
                } else {
                    val old = effBo(t, ef); val nb = if (old >= 5) 3 else 5
                    ef.bo = nb; if (ef.bracket.isEmpty()) ef.bracket = effBracket(key, ef)
                    fc("bo", "$ev bracket series: Bo$old → Bo$nb", if (nb == 5) "Every knockout series is a best of five." else "Shorter knockout series (the final keeps its length).")
                }
            }
            "qual" -> {
                val ef = x.ef("worlds")
                val next = if (ef.qual == "points") "placings" else "points"
                ef.qual = next
                return fc("qual", if (next == "points") "Worlds places from the leagues go to the best on circuit points over the whole season" else "Worlds places from the leagues go back to the last split's playoff placings",
                    if (next == "points") "A whole year of results counts, not one playoff run." else "Playoff runs and upsets decide who goes.")
            }
            "winner", "runner" -> {
                val ef = x.ef("worlds")
                val on = if (kind == "winner") x.win("worlds") else x.run("worlds")
                val before = x.seedsOf("worlds")
                val after = x.adjust("worlds", if (on) 1 else -1) ?: return null
                x.set("worlds", after)
                if (kind == "winner") ef.winner = if (on) 0 else 1 else ef.runner = if (on) 0 else 1
                val msi = eventName(x.t.id, "msi")
                return if (kind == "winner") fc("winner", if (on) "The $msi champion no longer qualifies directly for Worlds (${x.diff("worlds", before, after)})" else "The $msi champion now qualifies directly for Worlds (${x.diff("worlds", before, after)})",
                    if (on) "Every Worlds place is earned in the leagues." else "Winning $msi now means a ticket to Worlds.")
                else fc("runner", if (on) "No more extra Worlds seed for the $msi runner-up's region (${x.diff("worlds", before, after)})" else "The $msi runner-up's region gets an extra Worlds seed (${x.diff("worlds", before, after)})",
                    if (on) "Seeds come from the leagues only." else "A deep $msi run earns the whole region a place.")
            }
            "lcq" -> {
                x.tf.lastChance = !x.tf.lastChance
                return fc("lcq", if (x.tf.lastChance) "Regional finals return: the Worlds places not decided by placings go to a last-chance qualifier of the next six teams on circuit points" else "Regional finals are scrapped: the remaining Worlds places go straight to the best on circuit points",
                    if (x.tf.lastChance) "One more chance for the teams that fell short in the playoffs." else "Fewer matches at the end of a long season.")
            }
            "dcgi" -> {
                x.tf.invitational = !x.tf.invitational
                val name = if (x.t.id == "lol") "the Demacia Cup Global Invitational" else "the ${x.t.short} Global Invitational"
                return fc("dcgi", if (x.tf.invitational) "Back next season: $name for the teams that miss Worlds" else "$name is cancelled", if (x.tf.invitational) "Teams that miss Worlds get an international stage." else "The calendar after Worlds gets lighter.")
            }
            "promofmt" -> {
                val l = x.tier1.filter { hasProRel(w, it) }.let { if (it.isEmpty()) null else rng.pick(it) } ?: return null
                val now = x.tf.promo[l.id] ?: "rr"
                val next = rng.pick(listOf("rr", "playoff", "survival").filter { it != now })
                x.tf.promo[l.id] = next
                return fc("promofmt", when (next) {
                    "playoff" -> "${l.short} promotion becomes playoffs: its bottom two against the lower leagues' best two, cross-seeded; the semi-final winners take the places"
                    "survival" -> "${l.short} promotion becomes a survival series: the last place goes down and the lower leagues' best comes up directly; the second-last plays the next-best in one series for the last place"
                    else -> "${l.short} promotion goes back to a round robin of the bottom two and the lower leagues' best" },
                    when (next) { "playoff" -> "Win-or-go-home series for a top-flight place."; "survival" -> "A clear rule at both ends of the table, and one series that decides everything."; else -> "Every candidate plays every other: fewer upsets." }, listOf(l.id))
            }
            "cacl" -> {
                x.tf.academyCup = !x.tf.academyCup
                return fc("cacl", if (x.tf.academyCup) "The Continental Academy Champions League returns: each academy league's top two after the season" else "The Continental Academy Champions League is discontinued",
                    if (x.tf.academyCup) "Academy prospects get international games against other regions." else "The academy calendar ends with the domestic leagues.")
            }
            "expandleague", "merge", "split", "recognize", "associate" -> return Leagues.propose(x.w, x.t, x.tf, x.tier1, kind, rng, effective)?.let { (text, why, leagues) -> fc(kind, text, why, leagues, if (kind == "associate") leagues.drop(1) else leagues.take(1)) }
            "hostpolicy" -> {
                val ev = rng.pick(listOf("worlds", "msi"))
                val now = x.tf.hosts[ev] ?: "rotation"
                val next = rng.pick(listOf("rotation", "champion", "fixed").filter { it != now })
                x.tf.hosts[ev] = next
                val name = eventName(x.t.id, ev)
                return FormatChange(w.season, w.day, "hostpolicy", ev, when (next) {
                    "champion" -> "$name hosting: the region of the last champion hosts the next edition"
                    "fixed" -> "$name hosting: a permanent home — the next host city keeps the event"
                    else -> "$name hosting: back to rotation across continents" },
                    when (next) { "champion" -> "Winning brings the event home: fans of the champion's region get the next one."; "fixed" -> "One home every year: lower costs and a venue built around the event."; else -> "Every continent gets its turn." })
            }
            "allstar" -> {
                x.tf.allStar = !x.tf.allStar
                return FormatChange(w.season, w.day, "allstar", "", if (x.tf.allStar) "The all-star showdown returns: the two best-ranked leagues' best players by position, one series" else "The all-star showdown is discontinued",
                    if (x.tf.allStar) "Regional pride with the best players of each league side by side." else "Players get their off-season back.")
            }
            // A league switches between promotion / relegation and franchising (rarely: not within three seasons of its last switch).
            "prorel" -> {
                val cands = x.tier1.filter { l -> independentLowers(w, l).isNotEmpty() && x.tf.log.none { it.kind.startsWith("prorel") && l.id in it.leagues && w.season - it.season < 3 } }
                val l = cands.randomOrNull(rng) ?: return null
                val open = hasProRel(w, l)
                if (!rng.chance(if (open) 0.6 else 0.5)) return null
                x.tf.proRel[l.id] = !open
                val lows = independentLowers(w, l)
                return if (open) fc("prorel-", "${l.short} becomes a franchised league: no more promotion or relegation with ${lows.joinToString { it.short }} from ${w.season + 1}",
                    "Clubs get a permanent place to invest in; this season's promotion tournament is the last.", listOf(l.id))
                else fc("prorel+", "${l.short} opens up: promotion and relegation with ${lows.joinToString { it.short }} from ${w.season + 1} (its bottom two against the lower leagues' best)",
                    "Every place has to be defended, and the lower leagues have something to play for.", listOf(l.id) + lows.map { it.id }, lows.map { it.id })
            }
        }
        return null
    }

    private fun <T> List<T>.randomOrNull(rng: Rng): T? = if (isEmpty()) null else rng.pick(this)
}

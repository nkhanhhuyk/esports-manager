package dev.uem.em.comp

import dev.uem.em.core.Dates
import dev.uem.em.core.formatMoney
import dev.uem.em.model.CompTier
import dev.uem.em.model.Competition
import dev.uem.em.model.CustomComp
import dev.uem.em.model.Stage
import dev.uem.em.model.StageType
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

/**
 * User-created competitions built from the same stage generators, brackets, scheduling and prize
 * payments as the official calendar. A draft is validated live; publishing creates an edition (a
 * normal competition, tier CUSTOM) and recurring events get a new edition every season. Editing a
 * draft never touches published editions, so finished results stay as they were.
 */
object CustomComps {
    val FORMATS = listOf("league", "double-league", "groups-se", "groups-de", "se", "de", "swiss-se")
    val FORMAT_LABEL = mapOf(
        "league" to "League (round robin)", "double-league" to "League (double round robin)", "groups-se" to "Groups + single elimination",
        "groups-de" to "Groups + double elimination", "se" to "Single elimination", "de" to "Double elimination", "swiss-se" to "Swiss + single elimination",
    )
    val ALL_DAYS = listOf(0, 1, 2, 3, 4, 5, 6)

    class Check(val errors: List<String>, val warnings: List<String>, val matches: Int, val days: Int, val preview: List<String>, val teams: List<Int>, val startDay: Int, val season: Int)

    fun legalBo(titleId: String): List<Int> = if (Catalogue.get(titleId).isMoba) listOf(1, 3, 5, 7) else listOf(1, 3, 5)

    /** Teams the draft would start with (invited teams plus qualifier sources that can be resolved now). */
    fun entrants(w: World, c: CustomComp): Pair<List<Int>, List<String>> {
        val out = ArrayList<Int>()
        val notes = ArrayList<String>()
        for (id in c.teams) if (id in w.teams.indices && w.teams[id].active && w.teams[id].titleId == c.titleId && id !in out) out.add(id)
        for (src in c.sources) {
            val p = src.split(':')
            when (p[0]) {
                "league" -> w.leagues.getOrNull(p[1].toInt())?.let { l -> for (id in l.teamIds) if (w.teams[id].active && id !in out) out.add(id) else if (id in out) notes.add("${w.teams[id].short} appears twice (counted once)") }
                "top" -> {
                    val comp = w.comps.getOrNull(p[1].toInt()) ?: continue
                    if (!comp.done) { notes.add("Top ${p[2]} of ${comp.name}: known when it finishes"); repeat(p[2].toInt()) { out.add(-1) }; continue }
                    var taken = 0
                    for (id in comp.placements) { if (taken >= p[2].toInt()) break; if (id !in out && w.teams[id].active) { out.add(id); taken++ } }
                }
            }
        }
        return out to notes
    }

    private fun nextPow2(n: Int) = 2.0.pow(ceil(ln(maxOf(2, n).toDouble()) / ln(2.0))).toInt()

    fun schedule(w: World, c: CustomComp): Pair<Int, Int> {
        var season = w.season
        var day = Dates.day(season, c.startMonth, c.startDom)
        if (day < w.day + 3) { season++; day = Dates.day(season, c.startMonth, c.startDom) }
        return season to day
    }

    /** Stages for n teams (null with a reason when the format cannot be built for n). */
    fun stages(c: CustomComp, n: Int, start: Int): Pair<List<Stage>?, String?> {
        val gap = c.daysBetween.coerceIn(1, 7)
        fun ko(type: StageType, at: Int) = Stage("Playoffs", type, at, c.boPlayoff, ALL_DAYS, daysBetween = gap)
        return when (c.format) {
            "league" -> listOf(Stage("League", StageType.RR, start, c.boGroup, ALL_DAYS, daysBetween = gap)) to null
            "double-league" -> listOf(Stage("League", StageType.DRR, start, c.boGroup, ALL_DAYS, daysBetween = gap)) to null
            "se" -> listOf(ko(StageType.SINGLE_ELIM, start)) to null
            "de" -> if (n < 4) null to "Double elimination needs at least 4 teams" else listOf(ko(StageType.DOUBLE_ELIM, start)) to null
            "groups-se", "groups-de" -> {
                val g = c.groups.coerceAtLeast(1)
                if (n < g * 2) return null to "Each group needs at least 2 teams ($g groups need ${g * 2}+)"
                val per = n / g
                if (c.advance < 1 || c.advance > per) return null to "Advancing teams per group must be between 1 and $per"
                val groupDays = (per + (per % 2) - 1)
                val q = g * c.advance
                if (c.format == "groups-de" && q < 4) return null to "Double elimination needs at least 4 qualified teams"
                listOf(Stage("Group Stage", StageType.GROUPS, start, c.boGroup, ALL_DAYS, groups = g, advance = c.advance, daysBetween = 1), ko(if (c.format == "groups-de") StageType.DOUBLE_ELIM else StageType.SINGLE_ELIM, start + groupDays + 2)) to null
            }
            "swiss-se" -> {
                if (n !in setOf(8, 16)) return null to "Swiss needs 8 or 16 teams (it is generated for those sizes)"
                listOf(Stage("Swiss Stage", StageType.SWISS, start, c.boGroup, ALL_DAYS, advance = n / 2, daysBetween = 1, swissWins = if (n == 8) 2 else 3, swissLosses = if (n == 8) 2 else 3), ko(StageType.SINGLE_ELIM, start + (if (n == 8) 4 else 6) + 2)) to null
            }
            else -> null to "Unknown format"
        }
    }

    /** Live validation and a preview of the draft. */
    fun validate(w: World, c: CustomComp): Check {
        val errors = ArrayList<String>(); val warnings = ArrayList<String>()
        if (c.name.isBlank()) errors.add("Give the competition a name")
        if (c.titleId !in w.titles) errors.add("This game is not part of this world")
        val (teams, notes) = entrants(w, c)
        warnings.addAll(notes)
        val n = teams.size
        if (n < 2) errors.add("At least 2 teams are needed (now $n)")
        if (n > 32) errors.add("At most 32 teams (now $n)")
        val bos = legalBo(c.titleId)
        if (c.boGroup !in bos || c.boPlayoff !in bos || c.boFinal !in bos) errors.add("${Catalogue.get(c.titleId).short} matches are best of ${bos.joinToString("/")}")
        if (c.prize < 0 || c.entryFee < 0) errors.add("Prize money and entry fees cannot be negative")
        val (season, start) = schedule(w, c)
        val (st, why) = if (n >= 2) stages(c, n, start) else null to null
        if (why != null) errors.add(why)
        var matches = 0; var days = 0
        val preview = ArrayList<String>()
        if (st != null && n >= 2) {
            for (s in st) when (s.type) {
                StageType.RR, StageType.DRR -> { val r = (if (n % 2 == 0) n - 1 else n) * (if (s.type == StageType.DRR) 2 else 1); matches += n * (n - 1) / 2 * (if (s.type == StageType.DRR) 2 else 1); days += r; preview.add("${s.name}: $n teams, ${if (s.type == StageType.DRR) "double " else ""}round robin, Bo${s.bestOf}, $r match days") }
                StageType.GROUPS -> { val per = n / s.groups; matches += s.groups * per * (per - 1) / 2 + (n % s.groups) * per; days += per + 1; preview.add("${s.name}: ${s.groups} groups of $per${if (n % s.groups > 0) "–${per + 1}" else ""}, Bo${s.bestOf}, top ${s.advance} advance") }
                StageType.SWISS -> { matches += n / 2 * (if (n == 8) 4 else 5); days += if (n == 8) 4 else 5; preview.add("${s.name}: $n teams, ${s.swissWins} wins to advance, Bo${s.bestOf}") }
                StageType.SINGLE_ELIM, StageType.DOUBLE_ELIM -> {
                    val q = if (st.size > 1) (if (st[0].type == StageType.SWISS) n / 2 else st[0].groups * st[0].advance) else n
                    val size = nextPow2(q)
                    val rounds = (ln(size.toDouble()) / ln(2.0)).toInt()
                    matches += if (s.type == StageType.DOUBLE_ELIM) 2 * q - 1 else q - 1
                    days += (if (s.type == StageType.DOUBLE_ELIM) rounds * 2 + 1 else rounds) * s.daysBetween
                    preview.add("${if (s.type == StageType.DOUBLE_ELIM) "Double" else "Single"} elimination: $q teams${if (size > q) " (${size - q} byes)" else ""}, Bo${s.bestOf}, final Bo${c.boFinal}")
                }
            }
            if (start + days + 10 > Season.endDay(season)) errors.add("It would not finish before the season ends (${Dates.format(Season.endDay(season))}): start earlier or use fewer rounds")
            preview.add("About $matches matches over about $days days, from ${Dates.format(start)} (season $season)")
            preview.add("Prize pool ${formatMoney(prizePool(c, teams))}" + if (c.entryFee > 0) " (includes ${formatMoney(c.entryFee * teams.count { it >= 0 })} of entry fees)" else "")
        }
        if (teams.count { it < 0 } > 0) warnings.add("Some places are filled by qualifiers that are not decided yet")
        if (c.published && !c.recurring) warnings.add("Published: changes apply only to editions not created yet")
        return Check(errors, warnings, matches, days, preview, teams, start, season)
    }

    fun prizePool(c: CustomComp, teams: List<Int>) = c.prize + c.entryFee * teams.count { it >= 0 }

    /** Create the draft's edition (nothing is created when validation fails). Returns an error or null. */
    fun publish(w: World, c: CustomComp): String? {
        val chk = validate(w, c)
        if (chk.errors.isNotEmpty()) return chk.errors.first()
        createEdition(w, c, chk.season, chk.startDay) ?: return "This season already has an edition"
        c.published = true
        return null
    }

    private fun createEdition(w: World, c: CustomComp, season: Int, start: Int): Competition? {
        if (c.editions.any { w.comps.getOrNull(it)?.season == season }) return null
        val t = Catalogue.get(c.titleId)
        val (teams, _) = entrants(w, c)
        val (st, _) = stages(c, teams.size, start)
        st ?: return null
        val rules = ArrayList<String>()
        val ids = teams.filter { it >= 0 }
        if (ids.isNotEmpty()) rules.add("ids:" + ids.joinToString(","))
        rules.addAll(c.sources.filter { it.startsWith("top:") && w.comps.getOrNull(it.split(':')[1].toInt())?.done == false })
        val comp = Season.mk(w, t, season, "${c.name} $season", c.short.ifBlank { c.name.take(6) }, CompTier.CUSTOM, null, rules, st, prizePool(c, teams), 40, emptyList())
        comp.finalBo = c.boFinal
        comp.customId = c.id
        comp.coachComms = c.coachComms
        comp.timeouts = c.timeouts
        if (t.isMoba && c.fearless != "none") { comp.fearlessMode = c.fearless; comp.fearless = c.fearless != "none" }
        // Entry fees are paid by the invited organisations.
        if (c.entryFee > 0) for (id in ids) dev.uem.em.mgmt.Economy.ledger(w, w.orgs[w.teams[id].orgId], "entry", -c.entryFee, "Entry fee: ${comp.name}")
        c.editions.add(comp.id)
        w.rebuildSchedule()
        return comp
    }

    /** Season change: recurring events get their next edition (finished editions are never changed). */
    fun rollover(w: World) {
        for (c in w.customComps) {
            if (!c.published || !c.recurring || c.cancelled) continue
            val day = Dates.day(w.season, c.startMonth, c.startDom).coerceAtLeast(w.day + 3)
            val chk = validate(w, c)
            if (chk.errors.isEmpty()) createEdition(w, c, w.season, day)
            else w.inbox.add(dev.uem.em.model.Message(w.day, "Competitions", "${c.name}: no edition this season", chk.errors.first()))
        }
    }

    /** Cancel future editions (one that has started keeps going; finished ones keep their results). */
    fun cancel(w: World, c: CustomComp) {
        c.cancelled = true
        c.recurring = false
        for (id in c.editions) { val comp = w.comps.getOrNull(id) ?: continue; if (comp.status == 0) { comp.status = 2; comp.name += " (cancelled)" } }
    }
}

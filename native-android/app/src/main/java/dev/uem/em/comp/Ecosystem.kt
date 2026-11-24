package dev.uem.em.comp

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.data.GameData
import dev.uem.em.model.Competition
import dev.uem.em.model.CompTier
import dev.uem.em.model.Invite
import dev.uem.em.model.Message
import dev.uem.em.model.News
import dev.uem.em.model.QualEntry
import dev.uem.em.model.StageType
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue

/**
 * Competition rulesets by game and season. Each says where its structure comes from: an official
 * publication (with what it does and does not cover), or a fictional preset modelled on the public
 * structure. A competition edition records the ruleset it was created with ([Competition.rules]);
 * later seasons can use another ruleset without touching finished editions.
 */
object Rulesets {
    class Ruleset(val id: String, val titleId: String, val label: String, val status: String, val source: String = "", val notes: List<String> = emptyList())

    private val LOL_2026 = Ruleset("lol-2026", "lol", "LoL 2026 global season: three splits, First Stand, MSI, Worlds",
        "Official in part (LoL Esports 2026 handbook): First Stand = each region's Split 1 winner plus the LCK and LPL runners-up; MSI = CBLOL's top team and the top two of every other region; the MSI winner qualifies directly for Worlds and the MSI runner-up's region gets an extra Worlds seed. Worlds slots per region are the game's allocation (the handbook gives none); split formats are the game's.",
        "https://lolesports.com/en-SG/season/115547545029543948/handbook")
    private val LOL_CARRIED = Ruleset("lol-2026-carried", "lol", "LoL: the 2026 rules carried forward",
        "Fictional evolution: Riot has not published rules for this season in the game's data, so the 2026 handbook's qualification rules continue.", LOL_2026.source)
    private val VCT_2026 = Ruleset("vct-2026", "valorant", "VCT 2026-style: partner leagues by split, Masters from league stages, Champions by points",
        "Fictional preset modelled on the 2026 public structure (the 2027 announcement only describes it briefly: non-partner teams promoted through Ascension).")
    private val VCT_2027 = Ruleset("vct-2027", "valorant", "VCT 2027: one tier — open qualifiers, Kickoff and two Cups per territory, Masters Seoul, Masters Valencia, Champions Austin",
        "Official announcement (Riot, 8 Apr 2026): open qualifiers for any team feed Cups that replace league play and qualify teams directly for Masters and Champions; partner teams are seeded into later qualifier rounds, with no automatic international place; Champions takes the top two of each territory on championship points and the top two of Cup 2. Slot counts for the Masters events (three per territory) are the game's reading of a page that does not map them all.",
        "https://valorantesports.com/en-US/news/the-new-valorant-champions-tour")
    private val VCT_EVOLVED = Ruleset("vct-2027-evolved", "valorant", "VCT: the 2027 one-tier system continued",
        "Fictional evolution of the 2027 structure (generic Masters names; no later official rules in the game's data).", VCT_2027.source)
    private val CS2_VRS = Ruleset("cs2-vrs", "cs2", "Counter-Strike: Majors by regional-standings invitations and open qualifiers",
        "Method from Valve's published Regional Standings (standings invite teams to the later qualification stages; every other team goes through open qualifiers). The standings formula here is the game's approximation (results and beaten opponents over 12 months); slot counts and the regional leagues are the game's.",
        "https://github.com/ValveSoftware/counter-strike_regional_standings")
    private val DOTA_TI = Ruleset("dota2-ti-preset", "dota2", "Dota 2: The International by invitation and regional qualifiers",
        "Fictional preset modelled on the public structure (invitations by results, regional closed qualifiers). No official Valve ruleset was used.")
    private val MLBB = Ruleset("mlbb-preset", "mlbb", "MLBB: MPL and regional leagues → Mid Season Cup and M-Series by league results",
        "Fictional preset modelled on the public structure (MPL leagues qualify teams for MSC and the M-Series); official rulebooks were not verified here.")
    private val HOK = Ruleset("hok-preset", "hok", "Honor of Kings: KPL-style premier league and regional leagues → world events",
        "Fictional preset: the game's leagues are fictional (no verified roster source) and so is this structure.")
    private val WR = Ruleset("wr-preset", "wildrift", "Wild Rift: regional Icon Series where they exist → international events",
        "Fictional preset for a sparse scene: only the regions with leagues in the game's data play; no universal franchised league.")
    private val GENERIC = Ruleset("generic", "", "Game calendar", "The game's own competition format.")

    val ALL = listOf(LOL_2026, LOL_CARRIED, VCT_2026, VCT_2027, VCT_EVOLVED, CS2_VRS, DOTA_TI, MLBB, HOK, WR, GENERIC)

    /** The ruleset a title uses in a season. */
    fun of(titleId: String, season: Int): Ruleset = when (titleId) {
        "lol" -> if (season <= 2026) LOL_2026 else LOL_CARRIED
        "valorant" -> when { season <= 2026 -> VCT_2026; season == 2027 -> VCT_2027; else -> VCT_EVOLVED }
        "cs2" -> CS2_VRS
        "dota2" -> DOTA_TI
        "mlbb" -> MLBB
        "hok" -> HOK
        "wildrift" -> WR
        else -> GENERIC
    }

    fun get(id: String): Ruleset? = ALL.firstOrNull { it.id == id }

    /** VALORANT's one-tier open system (2027 on). */
    fun vctOpen(season: Int) = season >= 2027
}

/**
 * Qualification across competitions: entrant ledgers (why each team is in), invitations with
 * deadlines and replacements, open-qualifier pools, regional standings, and each team's road to
 * the next events. The same rules apply to every team, the user's and the AI's.
 */
object Ecosystem {
    /** Days before an event that ranking invitations go out, and the deadline to answer. */
    const val INVITE_LEAD = 24
    const val INVITE_ANSWER = 5

    /** The league / territory a rule key names ("L<id>"), or null for every region. */
    fun leagueOf(w: World, key: String): dev.uem.em.model.League? = key.removePrefix("L").toIntOrNull()?.let { w.leagues.getOrNull(it) }

    /** Whether a team belongs to a league's region (a member, a lower-league team, or an unaffiliated team based there). */
    fun inRegion(w: World, team: Team, league: dev.uem.em.model.League?): Boolean {
        if (league == null) return true
        if (team.leagueId == league.id) return true
        val own = w.leagues.getOrNull(team.leagueId)
        if (own != null && own.upperId == league.id) return true
        if (own != null && own.tier == 1) return false
        val org = w.orgs[team.orgId]
        val regs = GameData.expand(league.region)
        return org.region in regs || org.country in regs || team.region in regs
    }

    /**
     * Regional standings (Valve-style approximation): results over the last 12 months by placing and
     * prestige, plus a share of the standings points of the teams beaten in that time.
     */
    fun standings(w: World, titleId: String, league: dev.uem.em.model.League?): List<Pair<Int, Double>> {
        val base = dev.uem.em.mgmt.Rankings.clubs(w, titleId).associate { it.teamId to it.points }
        val beaten = HashMap<Int, Double>()
        for (f in w.fixtures) {
            if (!f.done || f.titleId != titleId || f.day < w.day - 365 || f.winner < 0) continue
            val loser = if (f.winner == f.a) f.b else f.a
            beaten.merge(f.winner, (base[loser] ?: 0.0) * 0.04, Double::plus)
        }
        return w.teams.filter { it.titleId == titleId && it.active && it.nationalOf.isEmpty() && it.parentId < 0 && inRegion(w, it, league) }
            .map { it.id to (base[it.id] ?: 0.0) + (beaten[it.id] ?: 0.0) + it.elo / 1000.0 }
            .sortedByDescending { it.second }
    }

    // ------------------------------------------------------------------ invitations

    /** Each day: ranking invitations go out before their events; AI teams answer; unanswered ones of the user lapse into acceptance. */
    fun daily(w: World) {
        // Answered invitations of long-finished events are dropped (each event's ledger keeps who entered and why).
        if (w.invites.size > 600) w.invites.removeAll { it.status != "pending" && it.day < w.day - 400 }
        for (c in w.comps) {
            if (c.status != 0 || c.season < w.season - 1) continue
            for (rule in c.entrants) if (rule.startsWith("invite:") && w.day >= c.startDay - INVITE_LEAD && w.invites.none { it.compId == c.id && it.route == rule }) issue(w, c, rule)
        }
        for (inv in w.invites) {
            if (inv.status != "pending") continue
            val c = w.comps[inv.compId]
            if (c.status != 0) { inv.status = "expired"; continue }
            if (w.day >= inv.deadline) {
                // The user did not answer: the staff accept (an unanswered invitation is not a withdrawal).
                respond(w, inv, true, "accepted by the staff at the deadline")
            }
        }
    }

    private fun parse(rule: String): Triple<String, String, Int> { val p = rule.split(':'); return Triple(p[1], p[2], p[3].toInt()) }

    /** Issue a rule's invitations to the best-ranked eligible teams not already in. */
    fun issue(w: World, c: Competition, rule: String) {
        val (titleId, key, n) = parse(rule)
        val league = leagueOf(w, key)
        val ranked = standings(w, titleId, league)
        val taken = w.invites.filter { it.compId == c.id && it.status != "declined" }.map { it.teamId }.toMutableSet()
        var issued = 0
        for ((i, row) in ranked.withIndex()) {
            if (issued >= n) break
            if (row.first in taken) continue
            invite(w, c, rule, row.first, i + 1)
            taken.add(row.first); issued++
        }
    }

    private fun invite(w: World, c: Competition, rule: String, teamId: Int, rank: Int) {
        val inv = Invite(c.id, teamId, rule, rank, w.day, maxOf(w.day + 1, minOf(c.startDay - 5, w.day + INVITE_ANSWER)))
        w.invites.add(inv)
        val team = w.teams[teamId]
        if (w.isUserTeam(teamId) && !dev.uem.em.mgmt.Roles.delegated(w, "competitions")) {
            w.inbox.add(Message(w.day, c.short, "Invitation: ${c.name}", "${team.name} are invited to ${c.name} (ranked ${dev.uem.em.core.ordinal(rank)} in the regional standings). Accept or decline by ${Dates.format(inv.deadline)} on the team's Road to page; an unanswered invitation is accepted by the staff."))
        } else respond(w, inv, aiAccepts(w, c, team), null)
    }

    /** AI (and delegated) answer: decline only when the team cannot field a roster or is already booked for overlapping dates. */
    fun aiAccepts(w: World, c: Competition, team: Team): Boolean {
        val t = Catalogue.get(team.titleId)
        if (!team.active || team.roster.count { !w.players[it].retired } < t.teamSize) return false
        val end = c.startDay + 21
        val clash = w.comps.any { o -> o.id != c.id && o.titleId == c.titleId && o.status != 2 && team.id in o.teamIds && o.tier != CompTier.LEAGUE && o.startDay in (c.startDay - 7)..end }
        return !clash
    }

    /** Accept or decline; a declined place goes to the next-ranked team that has not been invited. */
    fun respond(w: World, inv: Invite, accept: Boolean, why: String?) {
        if (inv.status != "pending") return
        inv.status = if (accept) "accepted" else "declined"
        val c = w.comps[inv.compId]
        val team = w.teams[inv.teamId]
        if (accept) return
        c.entries.add(QualEntry(inv.teamId, inv.route, -1, inv.rank - 1, "invitation (regional standings)", "declined${why?.let { " — $it" } ?: ""}", "declined", w.day))
        if (c.tier != CompTier.CHALLENGER) w.news.add(News(w.day, "${team.short} decline ${c.short} invitation", "${team.name} declined their invitation to ${c.name}; the next team in the standings is invited.", listOf(team.id)))
        val (titleId, key, _) = parse(inv.route)
        val ranked = standings(w, titleId, leagueOf(w, key))
        val asked = w.invites.filter { it.compId == c.id }.map { it.teamId }.toSet()
        ranked.withIndex().firstOrNull { it.value.first !in asked }?.let { (i, row) -> invite(w, c, inv.route, row.first, i + 1) }
    }

    // ------------------------------------------------------------------ describing routes

    /** A competition's entry rule in words. */
    fun describe(w: World, c: Competition, rule: String): String {
        val p = rule.split(':')
        fun comp(id: String) = w.comps.getOrNull(id.toIntOrNull() ?: -1)?.name ?: "?"
        fun league(id: String) = w.leagues.getOrNull(id.removePrefix("L").toIntOrNull() ?: -1)
        return when (p[0]) {
            "league" -> "Every team of ${league(p[1])?.name ?: "the league"}"
            "top" -> "Top ${p[2]} of ${comp(p[1])}"
            "circuit" -> "${p[2]} from ${league(p[1])?.name}: the last split's champion, then circuit points"
            "bottom" -> "The bottom ${p[2]} of ${comp(p[1])} (defending their places)"
            "promo" -> "The best ${p[2]} independent team(s) of ${comp(p[1])}"
            "qual" -> "Top ${p[2]} of ${comp(p[1])}"
            "elo" -> "${p[2]} invitations to the strongest top-league teams not already in"
            "fill" -> "Empty places filled by the strongest remaining nations (up to ${p[2]})"
            "continent" -> "Every nation of ${p.drop(2).joinToString(":")}"
            "nations" -> "The ${p[2]} strongest nations"
            "invite" -> "${p[3]} invitations by regional standings${league(p[2])?.let { " (${it.name} region)" } ?: ""}; declined places pass down the standings"
            "open" -> if (p.getOrNull(5) == "all") "Open qualifier: every team of ${league(p[2])?.name ?: "the region"}'s region not invited to the main event (up to ${p[3]}, strongest first)"
                else "Open qualifier: teams of ${league(p[2])?.name ?: "the region"}'s region without a partner place (up to ${p[3]}, strongest first)"
            "points" -> "Top ${p[2]} on championship points from ${league(p[1])?.name}"
            "msiwin" -> "The winner of ${comp(p[1])}"
            "msiseed" -> "An extra place for the region of ${comp(p[1])}'s runner-up"
            "ids" -> "Invited teams"
            "amateur" -> "The ${p[3]} strongest amateur clubs of ${league(p[2])?.name ?: "the region"}'s region"
            "amateurpts" -> "The ${p[3]} best amateur clubs of ${league(p[2])?.name ?: "the region"}'s region on Community Cup points"
            "kind" -> "Every ${p[3]} team of ${league(p[2])?.name ?: "the region"}'s region"
            else -> rule
        }
    }

    // ------------------------------------------------------------------ a team's road

    class Road(val comp: Competition, val status: String, val detail: String, val good: Boolean?)

    /** Where a team stands for every competition of its game this season: entered, possible, missed. */
    fun road(w: World, team: Team): List<Road> {
        val out = ArrayList<Road>()
        val comps = w.comps.filter { it.titleId == team.titleId && it.season == w.season && it.customId < 0 && !Season.isEncQualifier(it) }.sortedBy { it.startDay }
        for (c in comps) {
            val entry = c.entries.lastOrNull { it.teamId == team.id && it.status != "declined" && it.status != "withdrawn" }
            when {
                team.id in c.teamIds -> out.add(Road(c, if (c.done) "Played — ${placeText(c, team.id)}" else "Entered", entry?.let { whyText(w, it) } ?: "Entered", true))
                c.status != 0 -> if (relevant(w, c, team)) out.add(Road(c, "Did not qualify", missedText(w, c, team), false))
                else -> chance(w, c, team)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun placeText(c: Competition, teamId: Int): String { val i = c.placements.indexOf(teamId); return if (i < 0) "out" else dev.uem.em.core.ordinal(i + 1) }

    /** Why a team is in: its route, source placing and any replacement. */
    fun whyText(w: World, e: QualEntry): String {
        val src = w.comps.getOrNull(e.src)
        val place = if (e.place >= 0) " (${dev.uem.em.core.ordinal(e.place + 1)}${src?.let { " in ${it.short}" } ?: ""})" else src?.let { " (${it.short})" } ?: ""
        return e.how + place + (if (e.note.isNotBlank()) " — ${e.note}" else "")
    }

    private fun relevant(w: World, c: Competition, team: Team): Boolean = c.entrants.any { rule -> ruleConcerns(w, rule, team) }

    private fun ruleConcerns(w: World, rule: String, team: Team): Boolean {
        val p = rule.split(':')
        return when (p[0]) {
            "league" -> team.leagueId == p[1].toIntOrNull()
            "top", "qual", "bottom", "promo" -> w.comps.getOrNull(p[1].toInt())?.let { team.id in it.teamIds || (it.status == 0 && it.entrants.any { r -> ruleConcerns(w, r, team) }) } == true
            "circuit", "points" -> team.leagueId == p[1].removePrefix("L").toIntOrNull()
            "invite", "open" -> team.titleId == p[1] && inRegion(w, team, leagueOf(w, p[2])) && (p[0] == "invite" || team.kind.isEmpty() || team.kind == "amateur")
            "amateur", "amateurpts" -> team.titleId == p[1] && team.kind == "amateur" && (leagueOf(w, p[2])?.region ?: team.region) == team.region
            "kind" -> team.titleId == p[1] && team.kind == p[3] && (leagueOf(w, p[2])?.region ?: team.region) == team.region
            "msiwin", "msiseed" -> w.comps.getOrNull(p[1].toInt())?.let { team.id in it.teamIds } == true
            "elo" -> team.titleId == p[1] && w.leagues.getOrNull(team.leagueId)?.tier == 1
            else -> false
        }
    }

    private fun missedText(w: World, c: Competition, team: Team): String =
        c.entrants.filter { ruleConcerns(w, it, team) }.joinToString("; ") { describe(w, c, it) }.ifBlank { "Not eligible" }

    /** A competition not started yet: the team's chance through each route that concerns it. */
    private fun chance(w: World, c: Competition, team: Team): Road? {
        val lines = ArrayList<String>()
        var good: Boolean? = null
        for (rule in c.entrants) {
            if (!ruleConcerns(w, rule, team)) continue
            val p = rule.split(':')
            val text = describe(w, c, rule)
            when (p[0]) {
                "league" -> { lines.add("$text — a member"); good = true }
                "top", "qual", "promo" -> {
                    val src = w.comps[p[1].toInt()]
                    val n = p[2].toInt()
                    if (src.done) {
                        val i = src.placements.indexOf(team.id)
                        if (i in 0 until n) { lines.add("$text — finished ${dev.uem.em.core.ordinal(i + 1)}: in when the event starts"); good = true }
                        else lines.add("$text — finished ${if (i >= 0) dev.uem.em.core.ordinal(i + 1) else "out"}")
                    } else lines.add("$text — ${scenario(w, src, team, n)}").also { if (good == null) good = null }
                }
                "circuit", "points" -> {
                    val pts = Progress.circuitPoints(w, c.season, c.titleId)
                    val league = w.leagues[p[1].removePrefix("L").toInt()]
                    val order = league.teamIds.sortedByDescending { pts[it] ?: 0.0 }
                    lines.add("$text — ${dev.uem.em.core.ordinal(order.indexOf(team.id) + 1)} on points (${(pts[team.id] ?: 0.0).toInt()})")
                }
                "invite" -> {
                    val inv = w.invites.lastOrNull { it.compId == c.id && it.teamId == team.id }
                    if (inv != null) { lines.add("$text — invitation ${inv.status}"); good = inv.status == "accepted" || inv.status == "pending" }
                    else {
                        val st = standings(w, p[1], leagueOf(w, p[2]))
                        lines.add("$text — ${dev.uem.em.core.ordinal(st.indexOfFirst { it.first == team.id } + 1)} in the standings now; invitations go out ${Dates.format(c.startDay - INVITE_LEAD)}")
                    }
                }
                "open" -> lines.add("$text — eligible if not invited")
                else -> lines.add(text)
            }
        }
        if (lines.isEmpty()) return null
        return Road(c, if (good == true) "On course" else "Can qualify", lines.joinToString("\n"), good)
    }

    /**
     * Where a team stands in a source event that is still running: through, out, or what it needs
     * (league stage: mathematically clinched or eliminated from the playoff places on wins).
     */
    fun scenario(w: World, src: Competition, team: Team, n: Int): String {
        if (src.status == 0) return if (src.entrants.any { ruleConcerns(w, it, team) }) "needs a top-$n finish there" else "not entered there"
        if (team.id !in src.teamIds) return "not in it"
        val si = src.current
        val stage = src.stages.getOrNull(si) ?: return "in progress"
        if (stage.type == StageType.RR || stage.type == StageType.DRR) {
            val rows = Stages.sortStandings(w, stage.standings.toList())
            val left = HashMap<Int, Int>()
            for (fid in stage.fixtureIds) { val f = w.fixtures[fid]; if (!f.done) { left.merge(f.a, 1, Int::plus); left.merge(f.b, 1, Int::plus) } }
            val adv = if (stage.advance > 0) stage.advance else rows.size
            val me = rows.firstOrNull { it.teamId == team.id } ?: return "in progress"
            val myMax = me.w + (left[team.id] ?: 0)
            val others = rows.filter { it.teamId != team.id }
            val above = others.count { it.w > myMax }
            val below = others.count { it.w + (left[it.teamId] ?: 0) < me.w }
            return when {
                above >= adv -> "eliminated from the playoff places (cannot reach the top $adv)"
                below >= rows.size - adv -> "clinched a playoff place; needs a top-$n finish in the playoffs"
                else -> "${me.w}–${me.l}, in contention for the top $adv (${left[team.id] ?: 0} series left)"
            }
        }
        val alive = stage.fixtureIds.map { w.fixtures[it] }.none { it.done && it.winner >= 0 && (it.a == team.id || it.b == team.id) && it.winner != team.id && stage.type == StageType.SINGLE_ELIM }
        return if (alive) "still in ${stage.name}" else "knocked out in ${stage.name}"
    }

    /** Invitations waiting for the user's answer. */
    fun pending(w: World): List<Invite> = w.invites.filter { it.status == "pending" && w.isUserTeam(it.teamId) }

    /** Definition key of an edition (for older editions without one). */
    fun defKey(c: Competition): String = c.defKey.ifBlank {
        when {
            c.leagueId >= 0 -> "${c.titleId}:L${c.leagueId}:split${c.split}"
            else -> "${c.titleId}:" + c.name.replace(Regex("\\b20\\d\\d\\b"), "").replace(Regex("\\s+"), " ").trim().lowercase()
        }
    }

    /** Every edition of the same competition (history across seasons). */
    fun editions(w: World, c: Competition): List<Competition> { val k = defKey(c); return w.comps.filter { defKey(it) == k }.sortedBy { it.season } }

    @Suppress("unused") private fun rngFor(w: World, vararg k: Any) = Rng.derive(w.seed, "ecosystem", *k)
}

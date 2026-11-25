package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.core.formatMoney
import dev.uem.em.data.DataSource
import dev.uem.em.data.GameData
import dev.uem.em.model.CompTier
import dev.uem.em.model.Message
import dev.uem.em.model.News
import dev.uem.em.model.Player
import dev.uem.em.model.Staff
import dev.uem.em.model.StaffBid
import dev.uem.em.model.StaffSpell
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Staff: coaching staff (head coach, assistants, the draft / tactical coach, positional coaches,
 * analysts) and the people around the team (psychologist, performance coach, physio, scouts, team
 * manager) and the organisation (general manager, academy director, media and partnerships). Each
 * has attributes; the role decides which ones matter. What they change in the game:
 *
 * * matches: the head coach's calls and timeouts, preparation (analysts, assistant, draft coach) and
 *   how well the team reads the patch in drafts and vetoes; how fast it adapts after a patch;
 * * players: training and character pools (head coach, assistant, the positional coach of the
 *   player's position, the academy director for academy teams), morale, unhappiness, breaks,
 *   fatigue recovery and injuries;
 * * the club: scouting, wages and fees (general manager), sponsor deals, streaming and fans.
 *
 * Staff have contracts: clubs hire, sack, renew and pay compensation to take staff under contract
 * elsewhere; staff decide whether to join (role, club standing, wage) and may leave when a contract
 * ends. Retiring players can become staff. Real coaches come from staff.json; coaches without a
 * club in the snapshot (former and retired coaches) start as free agents.
 */
object Staffing {
    // --------------------------------------------------------------------------------- attributes
    const val TACTICS = 0
    const val DRAFTING = 1
    const val TEACHING = 2
    const val MOTIVATION = 3
    const val DISCIPLINE = 4
    const val ANALYSIS = 5
    const val JUDGEMENT = 6
    const val FITNESS = 7
    const val MEDICINE = 8
    const val PSYCHOLOGY = 9
    const val NEGOTIATION = 10
    const val COMMERCIAL = 11
    const val MEDIA = 12
    const val ADAPTABILITY = 13
    val ATTRS = listOf("Tactics", "Drafting", "Teaching", "Motivation", "Discipline", "Analysis", "Judgement", "Fitness", "Medicine", "Psychology", "Negotiation", "Commercial", "Media", "Adaptability")
    val ATTR_DESC = listOf(
        "In-game strategy, calls and timeouts", "Drafts, vetoes and reading the patch", "Improving players in training", "Morale and man management",
        "Professionalism, keeping the dressing room calm", "Studying opponents and data", "Judging ability and potential", "Physical conditioning and recovery",
        "Preventing and treating injuries", "Mental health, pressure and confidence", "Contracts, wages and fees", "Sponsors and partners", "Content, streaming and fans",
        "Keeping up with patches and new metas",
    )

    class RoleDef(val id: String, val label: String, val group: String, val weights: List<Pair<Int, Double>>, val desc: String, val wage: Double, val org: Boolean = false, val titleBound: Boolean = true)

    val ROLES = listOf(
        RoleDef("head-coach", "Head coach", "Coaching", listOf(TACTICS to .25, DRAFTING to .2, MOTIVATION to .2, TEACHING to .1, DISCIPLINE to .1, ANALYSIS to .1, ADAPTABILITY to .05),
            "Runs the team: in-game calls and timeouts, the draft or veto with the draft coach, morale, part of training.", 1.0),
        RoleDef("assistant-coach", "Assistant coach", "Coaching", listOf(TEACHING to .3, TACTICS to .2, MOTIVATION to .15, ANALYSIS to .15, DRAFTING to .1, DISCIPLINE to .1),
            "Training and match preparation; steps in when there is no head coach.", 0.55),
        RoleDef("strategic-coach", "Draft coach", "Coaching", listOf(DRAFTING to .4, ANALYSIS to .25, ADAPTABILITY to .2, TACTICS to .15),
            "Drafts and vetoes, reads each patch and the opponents' tendencies; helps the team adapt after a patch.", 0.6),
        RoleDef("position-coach", "Positional coach", "Coaching", listOf(TEACHING to .45, TACTICS to .2, MOTIVATION to .15, ANALYSIS to .1, ADAPTABILITY to .1),
            "Trains the players of one position: faster development and a wider character pool there.", 0.45),
        RoleDef("analyst", "Analyst", "Coaching", listOf(ANALYSIS to .5, DRAFTING to .25, ADAPTABILITY to .15, JUDGEMENT to .1),
            "Studies opponents and the patch: match preparation, the draft read, adapting to new metas.", 0.42),
        RoleDef("psychologist", "Sports psychologist", "Performance", listOf(PSYCHOLOGY to .6, MOTIVATION to .3, DISCIPLINE to .1),
            "Morale, confidence under pressure, fewer breaks.", 0.42, titleBound = false),
        RoleDef("performance-coach", "Performance coach", "Performance", listOf(FITNESS to .6, MEDICINE to .2, DISCIPLINE to .2),
            "Physical conditioning: players recover from fatigue faster.", 0.38, titleBound = false),
        RoleDef("physio", "Physiotherapist", "Performance", listOf(MEDICINE to .7, FITNESS to .3),
            "Fewer injuries, and players come back sooner.", 0.34, titleBound = false),
        RoleDef("scout", "Scout", "Recruitment", listOf(JUDGEMENT to .7, ANALYSIS to .2, NEGOTIATION to .1),
            "Finds and judges players: scouting reports and potential estimates.", 0.34),
        RoleDef("team-manager", "Team manager", "Operations", listOf(DISCIPLINE to .35, MOTIVATION to .3, NEGOTIATION to .2, PSYCHOLOGY to .15),
            "Logistics and the dressing room: less unhappiness, players settle in.", 0.38, titleBound = false),
        RoleDef("academy-director", "Academy director", "Organisation", listOf(TEACHING to .45, JUDGEMENT to .4, MOTIVATION to .15),
            "The organisation's academy: its players develop faster.", 0.5, org = true, titleBound = false),
        RoleDef("general-manager", "General manager", "Organisation", listOf(NEGOTIATION to .45, JUDGEMENT to .35, COMMERCIAL to .2),
            "Transfers and contracts: lower wages and fees when buying, more when selling.", 0.85, org = true, titleBound = false),
        RoleDef("media-manager", "Media & content manager", "Organisation", listOf(MEDIA to .7, COMMERCIAL to .3),
            "Content and streaming: more streaming income and faster fan growth.", 0.38, org = true, titleBound = false),
        RoleDef("partnerships-manager", "Partnerships manager", "Organisation", listOf(COMMERCIAL to .7, NEGOTIATION to .3),
            "Sponsors: better offers and deals.", 0.48, org = true, titleBound = false),
    )
    val BY_ID = ROLES.associateBy { it.id }
    val COACHING = setOf("head-coach", "assistant-coach", "strategic-coach", "position-coach", "analyst")
    fun def(role: String) = BY_ID[role] ?: BY_ID.getValue("analyst")

    /** Role as shown: "Draft coach" in MOBAs, "Tactical coach" in shooters, "Jungle coach" for a positional coach. */
    fun roleLabel(role: String, titleId: String, spec: String = ""): String = when (role) {
        "strategic-coach" -> if (Catalogue.BY_ID[titleId]?.isMoba != false) "Draft coach" else "Tactical coach"
        "position-coach" -> Catalogue.BY_ID[titleId]?.let { t -> t.roles.firstOrNull { it.id == spec }?.let { "${it.name} coach" } } ?: "Positional coach"
        else -> def(role).label
    }
    fun label(s: Staff) = roleLabel(s.role, s.titleId, s.spec)

    fun attr(s: Staff, i: Int): Double = (s.attrs.getOrNull(i) ?: s.quality.roundToInt()).toDouble()

    fun qualityOf(role: String, a: IntArray): Double {
        val d = def(role)
        var num = 0.0; var den = 0.0
        for ((i, wt) in d.weights) { num += a.getOrElse(i) { 40 } * wt; den += wt }
        return if (den > 0) num / den else 40.0
    }

    /** Recompute the role ability from the attributes (after a role change, growth or editing). */
    fun refresh(s: Staff) { if (s.attrs.size == ATTRS.size) s.quality = (qualityOf(s.role, s.attrs) * 10).roundToInt() / 10.0 }

    /** Attributes for a role at a level: the role's attributes around it, the others lower. */
    fun attrsFor(role: String, q: Double, rng: Rng): IntArray {
        val d = def(role)
        val wmap = d.weights.toMap()
        val a = IntArray(ATTRS.size) { i ->
            val wt = wmap[i] ?: 0.0
            val v = when { wt >= 0.15 -> q + rng.normal(0.0, 6.0); wt > 0 -> q - 4 + rng.normal(0.0, 8.0); else -> q - 16 + rng.normal(0.0, 12.0) }
            v.roundToInt().coerceIn(8, 97)
        }
        val shift = q - qualityOf(role, a)
        for ((i, _) in d.weights) a[i] = (a[i] + shift).roundToInt().coerceIn(8, 97)
        return a
    }

    // --------------------------------------------------------------------------------- who is where

    /** A club's staff; a national team's staff are named per event and keep their club jobs. */
    fun members(w: World, team: Team): List<Staff> = team.staffIds.mapNotNull { w.staff.getOrNull(it) }.filter { !it.retired && (it.teamId == team.id || team.nationalOf.isNotEmpty()) }

    /** Best value of [attr] (or the role ability) among the team's staff in [role]. */
    fun best(w: World, team: Team, role: String, attr: Int = -1, spec: String? = null): Double? {
        var b: Double? = null
        for (sid in team.staffIds) {
            val s = w.staff.getOrNull(sid) ?: continue
            if (s.retired || s.role != role || (s.teamId != team.id && team.nationalOf.isEmpty()) || (spec != null && s.spec != spec)) continue
            val v = if (attr < 0) s.quality else attr(s, attr)
            if (b == null || v > b) b = v
        }
        return b
    }

    /** Organisation roles work for every team of the organisation, whichever team lists them. */
    fun orgBest(w: World, orgId: Int, role: String, attr: Int = -1): Double? {
        val org = w.orgs.getOrNull(orgId) ?: return null
        var b: Double? = null
        for (tid in org.teams) { val v = best(w, w.teams[tid], role, attr) ?: continue; if (b == null || v > b) b = v }
        return b
    }

    // --------------------------------------------------------------------------------- effects

    /** Head coach level (an assistant covers a vacancy at 80%); teams that never had staff keep their old rating. */
    fun coachLevel(w: World, team: Team): Double {
        best(w, team, "head-coach")?.let { return it }
        if (team.staffIds.isEmpty()) return team.coach
        return max(30.0, (best(w, team, "assistant-coach") ?: 0.0) * 0.8)
    }

    /** In-game calls and timeouts (MOBA coach comms, shooter timeouts): the head coach's tactics. */
    fun inGame(w: World, team: Team): Double {
        val t = best(w, team, "head-coach", TACTICS) ?: return coachLevel(w, team)
        return 0.6 * t + 0.4 * coachLevel(w, team)
    }

    /** Match preparation: analysts, the assistant, the draft coach and the analytics facility (about 1.00-1.02). */
    fun prepFactor(w: World, team: Team): Double {
        val a = best(w, team, "analyst", ANALYSIS) ?: 30.0
        val ac = best(w, team, "assistant-coach") ?: 30.0
        val st = best(w, team, "strategic-coach") ?: 0.0
        val fac = w.orgs[team.orgId].facilities.getOrElse(1) { 3 }
        return 1 + 0.008 * (a / 100) + 0.004 * (ac / 100) + 0.005 * (st / 100) + 0.004 * (fac / 10.0)
    }

    /** Positional coaches across the lineup (1.000-1.006): small sharpness in matches; their main effect is training. */
    fun positionFactor(w: World, team: Team): Double {
        val t = Catalogue.get(team.titleId)
        if (t.lineup.isEmpty()) return 1.0
        var sum = 0.0
        for (r in t.lineup) sum += (best(w, team, "position-coach", TEACHING, r) ?: 0.0) / 100
        return 1 + 0.006 * sum / t.lineup.size
    }

    /**
     * The staff's level for matches (0-100): the head coach 45%, preparation 35% (analysts, the assistant,
     * the draft coach), positional coaches across the lineup 20%. Teams that never had staff keep their old rating.
     */
    fun staffScore(w: World, team: Team): Double {
        if (team.staffIds.isEmpty()) return team.coach
        val prep = 0.5 * (best(w, team, "analyst", ANALYSIS) ?: 30.0) + 0.25 * (best(w, team, "assistant-coach") ?: 30.0) + 0.25 * (best(w, team, "strategic-coach", DRAFTING) ?: 30.0)
        val lineup = Catalogue.get(team.titleId).lineup
        val pos = if (lineup.isEmpty()) 30.0 else lineup.sumOf { best(w, team, "position-coach", TEACHING, it) ?: 30.0 } / lineup.size
        return 0.45 * coachLevel(w, team) + 0.35 * prep + 0.2 * pos
    }

    /** Everything the staff add in a match, through every player (about 0.97-1.06; 1.0 for a typical staff of 55). */
    fun matchFactor(w: World, team: Team) = 1 + 0.07 * (staffScore(w, team) - 55) / 50

    /** How well the team reads the patch in drafts and vetoes (0-100): the head coach or draft coach, analysts. */
    fun draftRead(w: World, team: Team): Double {
        if (team.staffIds.isEmpty()) return team.coach
        val hc = best(w, team, "head-coach", DRAFTING) ?: 30.0
        val st = best(w, team, "strategic-coach", DRAFTING) ?: 0.0
        val an = best(w, team, "analyst", ANALYSIS) ?: 30.0
        val tac = best(w, team, "head-coach", TACTICS) ?: 30.0
        return 0.6 * max(hc, st) + 0.25 * an + 0.15 * tac
    }

    /** How fast the team adapts after a patch (0-100): analysts, the draft coach, the head coach. */
    fun adaptLevel(w: World, team: Team): Double =
        maxOf(best(w, team, "analyst", ADAPTABILITY) ?: 30.0, best(w, team, "strategic-coach", ADAPTABILITY) ?: 30.0, (best(w, team, "head-coach", ADAPTABILITY) ?: 30.0) * 0.85)

    /** Training for a player of [roleId]: head coach, assistant and that position's coach (teaching). */
    fun teaching(w: World, team: Team, roleId: String): Triple<Double, Double, Double> = Triple(
        best(w, team, "head-coach", TEACHING) ?: coachLevel(w, team),
        best(w, team, "assistant-coach", TEACHING) ?: 30.0,
        best(w, team, "position-coach", TEACHING, roleId) ?: 0.0,
    )

    /** Morale care: the psychologist, the head coach's man management, the team manager. */
    fun moraleCare(w: World, team: Team): Double =
        0.5 * (best(w, team, "psychologist", PSYCHOLOGY) ?: 30.0) + 0.3 * (best(w, team, "head-coach", MOTIVATION) ?: 30.0) + 0.2 * (best(w, team, "team-manager", MOTIVATION) ?: 30.0)

    fun psychology(w: World, team: Team): Double = best(w, team, "psychologist", PSYCHOLOGY) ?: 30.0
    fun discipline(w: World, team: Team): Double = best(w, team, "team-manager", DISCIPLINE) ?: 30.0
    fun fitness(w: World, team: Team): Double = best(w, team, "performance-coach", FITNESS) ?: 30.0
    fun medicine(w: World, team: Team): Double = max(best(w, team, "physio", MEDICINE) ?: 30.0, (best(w, team, "performance-coach", MEDICINE) ?: 30.0) * 0.8)
    fun negotiation(w: World, team: Team?): Double = team?.let { orgBest(w, it.orgId, "general-manager", NEGOTIATION) } ?: 30.0
    fun commercial(w: World, orgId: Int): Double = orgBest(w, orgId, "partnerships-manager", COMMERCIAL) ?: 25.0
    fun media(w: World, orgId: Int): Double = orgBest(w, orgId, "media-manager", MEDIA) ?: 25.0
    fun academy(w: World, orgId: Int): Double = orgBest(w, orgId, "academy-director") ?: 25.0
    fun scouting(w: World, orgId: Int): Double = orgBest(w, orgId, "scout", JUDGEMENT)?.let { max(it, (orgBest(w, orgId, "general-manager", JUDGEMENT) ?: 0.0) * 0.8) } ?: 25.0

    /** Wages asked of a team: its general manager talks them down (up to 8%). */
    fun wageFactor(w: World, team: Team?) = 1 - 0.08 * ((negotiation(w, team) - 30) / 70).coerceIn(0.0, 1.0)
    /** Fee a seller needs: its general manager asks more, the buyer's talks it down (±6%). */
    fun feeFactor(w: World, seller: Team?, buyer: Team?) =
        (1 + 0.06 * ((negotiation(w, seller) - 30) / 70).coerceIn(0.0, 1.0)) * (1 - 0.06 * ((negotiation(w, buyer) - 30) / 70).coerceIn(0.0, 1.0))
    fun sponsorFactor(w: World, orgId: Int) = 1 + 0.15 * ((commercial(w, orgId) - 25) / 75).coerceIn(0.0, 1.0)
    fun mediaFactor(w: World, orgId: Int) = 1 + 0.12 * ((media(w, orgId) - 25) / 75).coerceIn(0.0, 1.0)

    /** A short list of what the staff add to a team, for the squad page. */
    fun summary(w: World, team: Team): List<Pair<String, String>> {
        val t = Catalogue.get(team.titleId)
        val out = ArrayList<Pair<String, String>>()
        out.add("Match performance" to "×%.3f (staff level %.0f: coach, preparation, positions)".format(matchFactor(w, team), staffScore(w, team)))
        out.add("Draft read" to "%.0f / 100".format(draftRead(w, team)))
        out.add("Patch adaptation" to "%.0f / 100".format(adaptLevel(w, team)))
        out.add("In-game calls" to "%.0f / 100".format(inGame(w, team)))
        val covered = t.lineup.filter { best(w, team, "position-coach", spec = it) != null }
        out.add("Positional coaching" to if (covered.isEmpty()) "none" else covered.joinToString { t.role(it).short })
        out.add("Morale care" to "%.0f".format(moraleCare(w, team)))
        out.add("Recovery / injuries" to "%.0f / %.0f".format(fitness(w, team), medicine(w, team)))
        out.add("Negotiation (wages ×%.2f)".format(wageFactor(w, team)) to "%.0f".format(negotiation(w, team)))
        out.add("Commercial / media" to "sponsors ×%.2f · streaming ×%.2f".format(sponsorFactor(w, team.orgId), mediaFactor(w, team.orgId)))
        return out
    }

    // --------------------------------------------------------------------------------- money

    fun age(w: World, s: Staff) = Dates.ageAt(s.birth, w.day)

    /** What a person wants per season in a role at a club in this region. */
    fun demand(w: World, s: Staff, region: String, role: String = s.role): Double {
        val base = Catalogue.BY_ID[s.titleId]?.baseSalary ?: 150000.0
        val q = if (role == s.role || s.attrs.size != ATTRS.size) s.quality else qualityOf(role, s.attrs)
        val v = base * 0.11 * (0.4 + GameData.economy(region) * 0.6) * exp((q - 55) / 15) * def(role).wage * (0.85 + s.rep / 300)
        return (max(4000.0, v) / 500).roundToInt() * 500.0
    }

    /** Compensation a club wants to release staff under contract: most of the remaining wages, more for a big name. */
    fun compensation(w: World, s: Staff): Double {
        if (s.teamId < 0) return 0.0
        val left = max(0, s.contractEnd - w.season) + 0.6
        return (s.salary * left * 0.8 * (1 + s.rep / 150) / 1000).roundToInt() * 1000.0
    }

    // --------------------------------------------------------------------------------- moves

    @Volatile var epoch = 0

    private fun spellEnd(w: World, s: Staff, why: String) {
        s.spells.lastOrNull { it.to < 0 }?.let { it.to = w.day; it.why = why }
    }

    /** Join [team] in [role] (positional coaches: [spec]); [fee] is paid to the previous club. */
    fun hire(w: World, s: Staff, team: Team, role: String, years: Int, salary: Double, fee: Double = 0.0, spec: String = s.spec, quiet: Boolean = false) {
        val prev = w.teams.getOrNull(s.teamId)
        if (prev != null) {
            prev.staffIds.remove(s.id)
            spellEnd(w, s, if (prev.orgId == team.orgId) "moved within the organisation" else if (fee > 0) "bought out" else "left")
            if (fee > 0) {
                Economy.ledger(w, w.orgs[prev.orgId], "staff fee", fee, "Compensation for ${s.name()}")
                Economy.ledger(w, w.orgs[team.orgId], "staff fee", -fee, "Compensation for ${s.name()}")
            }
        }
        s.role = role
        s.spec = if (role == "position-coach") spec.ifEmpty { Catalogue.get(team.titleId).lineup.firstOrNull() ?: "" } else ""
        refresh(s)
        s.teamId = team.id
        s.orgId = team.orgId
        s.salary = salary
        s.contractEnd = w.season + years - 1
        s.joinedDay = w.day
        s.retired = false
        if (def(role).titleBound) s.titleId = team.titleId
        if (s.id !in team.staffIds) team.staffIds.add(s.id)
        s.spells.add(StaffSpell(team.id, role, w.day))
        epoch++
        if (!quiet && (s.real || s.rep >= 55 || w.isUserTeam(team.id)) && role in setOf("head-coach", "general-manager"))
            w.news.add(News(w.day, "${team.name} appoint ${s.name()}", "${s.name()} becomes ${roleLabel(role, team.titleId, s.spec).lowercase()} of ${team.name}" + (prev?.let { if (it.id != team.id) " (from ${it.name})" else "" } ?: "") + "."))
    }

    /** Leave the club: sacked ([severance]: half of the remaining contract), resigned or contract ended. */
    fun release(w: World, s: Staff, why: String, severance: Boolean = true) {
        val team = w.teams.getOrNull(s.teamId) ?: return
        if (severance) {
            val remaining = max(0, s.contractEnd - w.season) + 0.5
            Economy.ledger(w, w.orgs[team.orgId], "severance", -s.salary * remaining * 0.5, "Released ${s.name()}")
        }
        team.staffIds.remove(s.id)
        spellEnd(w, s, why)
        s.teamId = -1; s.orgId = -1; s.salary = 0.0
        epoch++
        if (s.role == "head-coach" && (s.real || s.rep >= 55) && why != "contract ended") w.news.add(News(w.day, "${team.name} part ways with ${s.name()}", "${s.name()} leaves ${team.name} (${why})."))
    }

    class Reply(val ok: Boolean, val text: String)

    /** Why [s] would not take [role] at [team] (null: open to it). */
    // ------------------------------------------------------------------ ambitions (like players')

    val AMB_KEYS = listOf("winning", "money", "loyalty", "legacy", "prestige", "role", "growth", "home")
    val AMB_LABEL = mapOf("winning" to "Winning", "money" to "Money", "loyalty" to "Loyalty", "legacy" to "Legacy", "prestige" to "Prestige",
        "role" to "A bigger role", "growth" to "Developing players", "home" to "Home region")
    val AMB_HINT = mapOf("winning" to "a team that can win now", "money" to "the salary", "loyalty" to "staying with the same club",
        "legacy" to "a club with trophies to add to", "prestige" to "a big club in a top league", "role" to "a step up: head coach, or a bigger job",
        "growth" to "young players, an academy, a project", "home" to "working in the home region")
    private val RANK = mapOf("head-coach" to 5, "general-manager" to 5, "strategic-coach" to 4, "assistant-coach" to 3, "academy-director" to 3, "position-coach" to 2, "analyst" to 2)
    private fun rank(role: String) = RANK[role] ?: 1

    /** What this person wants from a job (weights summing to about 100), from ambition, loyalty, age and role. */
    fun ambitions(w: World, s: Staff): IntArray {
        if (s.ambitions.size == AMB_KEYS.size) return s.ambitions
        val rng = Rng.derive(w.seed, "staff-ambitions", s.id)
        val age = age(w, s)
        val raw = doubleArrayOf(
            8 + s.ambition * 0.25 + rng.float(0.0, 20.0),
            5 + rng.float(0.0, 24.0),
            1 + s.loyalty * 0.4 + rng.float(0.0, 14.0),
            2 + (if (age >= 38) 10.0 else 0.0) + rng.float(0.0, 14.0),
            3 + s.ambition * 0.18 + rng.float(0.0, 16.0),
            if (s.role == "head-coach" || s.role == "general-manager") 1 + rng.float(0.0, 5.0) else 4 + s.ambition * 0.22 + rng.float(0.0, 16.0),
            (if (s.role in setOf("academy-director", "position-coach", "assistant-coach")) 10.0 else 2.0) + rng.float(0.0, 16.0),
            rng.float(0.0, 20.0),
        ).map { max(0.5, it).let { v -> Math.pow(v, 1.7) } }
        val sum = raw.sum()
        s.ambitions = IntArray(raw.size) { (raw[it] * 100 / sum).roundToInt() }
        return s.ambitions
    }

    fun amb(w: World, s: Staff, key: String) = ambitions(w, s)[AMB_KEYS.indexOf(key)]
    fun topAmbitions(w: World, s: Staff, n: Int = 3): List<Pair<String, Int>> = ambitions(w, s).let { a -> AMB_KEYS.indices.sortedByDescending { a[it] }.take(n).map { AMB_KEYS[it] to a[it] } }

    /** A move that goes against what the person wants most (only a strong pull in that direction says no). */
    private fun ambitionRefusal(w: World, s: Staff, team: Team, role: String): String? {
        if (team.nationalOf.isNotEmpty() || s.teamId == team.id) return null
        val org = w.orgs[team.orgId]
        val cur = w.teams.getOrNull(s.teamId)
        val curOrg = cur?.let { w.orgs[it.orgId] }
        val promotion = rank(role) > rank(s.role)
        if (amb(w, s, "role") >= 28 && cur != null && rank(role) < rank(s.role)) return "${s.name()} wants a bigger role, not a smaller one."
        if (amb(w, s, "home") >= 28 && !promotion && GameData.regionOf(s.nat) != team.region && (cur == null || cur.region == GameData.regionOf(s.nat)))
            return "${s.name()} wants to work in ${GameData.regionName(GameData.regionOf(s.nat))}."
        if (amb(w, s, "winning") >= 28 && cur != null && !promotion && team.elo < cur.elo - 60) return "${s.name()} wants a team that can win now: ${team.name} are well behind ${cur.name}."
        if (amb(w, s, "prestige") >= 28 && curOrg != null && !promotion && org.rep < curOrg.rep - 8) return "${s.name()} would only move to a bigger club than ${cur.name}."
        if (amb(w, s, "loyalty") >= 30 && cur != null && !promotion && (s.spells.lastOrNull { it.to < 0 }?.let { w.day - it.from } ?: 0) < 700) return "${s.name()} is loyal to ${cur.name} and is not looking to move."
        return null
    }

    fun refusal(w: World, s: Staff, team: Team, role: String): String? {
        val org = w.orgs[team.orgId]
        if (s.retired && age(w, s) >= 62) return "${s.name()} has retired."
        if (def(role).titleBound && s.titleId != team.titleId && def(s.role).titleBound) return "${s.name()} works in ${Catalogue.get(s.titleId).short}."
        if (s.teamId == team.id && s.role == role && (role != "position-coach")) return null
        if (s.role == "head-coach" && role != "head-coach" && s.rep >= 55 && s.teamId != team.id) return "${s.name()} only takes head coach jobs."
        if (role in COACHING && s.rep > org.rep + 28 && s.teamId != team.id) return "${s.name()} is not interested: ${org.name} is not ambitious enough for them (reputation ${org.rep.roundToInt()} vs ${s.rep.roundToInt()})."
        return ambitionRefusal(w, s, team, role)
    }

    /** A contract offer: free agents decide at once; staff under contract need their club to accept compensation. */
    fun offer(w: World, s: Staff, team: Team, role: String, salary: Double, years: Int, spec: String = "", fee: Double = compensation(w, s), replace: Boolean = false): Reply {
        refusal(w, s, team, role)?.let { return Reply(false, it) }
        val org = w.orgs[team.orgId]
        val current = if (role == "head-coach") members(w, team).firstOrNull { it.role == "head-coach" && it.id != s.id } else null
        if (current != null && !replace) return Reply(false, "${team.name} already have a head coach (${current.name()}): replace them or offer another role.")
        if (team.staffIds.size >= 20 && s.teamId != team.id) return Reply(false, "${team.name} already employ 20 staff.")
        val ask = demand(w, s, team.region, role) * wageFactor(w, team)
        if (org.balance < salary * 0.25 + (if (s.teamId >= 0 && s.teamId != team.id) fee else 0.0)) return Reply(false, "Not enough money for this deal.")
        val rng = Rng.derive(w.seed, "staffoffer", s.id, team.id, w.day, salary.toInt())
        // Under contract elsewhere: the club wants compensation, the person wants a step up or more money.
        if (s.teamId >= 0 && s.teamId != team.id) {
            val cur = w.teams[s.teamId]
            val need = compensation(w, s) * feeFactor(w, cur, team) * (if (s.role == "head-coach" && role == "head-coach") 1.2 else 1.0)
            val promotion = (role == "head-coach" && s.role != "head-coach") || (role in COACHING && s.role !in COACHING)
            val step = promotion || org.rep >= w.orgs[cur.orgId].rep + 5 || salary >= s.salary * 1.25
            if (!step) return Reply(false, "${s.name()} is happy at ${cur.name}: offer a bigger role, a bigger club or clearly more money.")
            if (fee < need && !(promotion && fee >= need * 0.6)) return Reply(false, "${cur.name} want about ${formatMoney(need)} compensation for ${s.name()}.")
        }
        val ratio = salary / ask
        if (!(ratio >= 1.0 || (ratio >= 0.85 && rng.chance((ratio - 0.85) / 0.15 * 0.8)))) return Reply(false, "${s.name()} turns it down (asking about ${formatMoney(ask)} per season).")
        val payFee = if (s.teamId >= 0 && s.teamId != team.id) fee else 0.0
        val renewal = s.teamId == team.id
        if (current != null) release(w, current, "replaced")
        hire(w, s, team, role, years, salary, payFee, spec)
        return Reply(true, if (renewal) "${s.name()} signs until the end of ${s.contractEnd}." else "${s.name()} joins ${team.name} as ${label(s).lowercase()} until the end of ${s.contractEnd}" + (if (payFee > 0) " (compensation ${formatMoney(payFee)})." else "."))
    }

    /** Renewal: staff who are doing well want more; ambitious staff at a smaller club may want to move on. */
    fun renew(w: World, s: Staff, salary: Double, years: Int): Reply {
        val team = w.teams.getOrNull(s.teamId) ?: return Reply(false, "Not at a club.")
        val org = w.orgs[team.orgId]
        val ask = demand(w, s, team.region) * wageFactor(w, team) * (1 + max(0.0, s.rep - org.rep) / 200) * (1 + (amb(w, s, "money") - amb(w, s, "loyalty")) / 250.0)
        if (s.ambition >= 75 && s.rep > org.rep + 15) return Reply(false, "${s.name()} wants a bigger challenge and will not extend.")
        if (amb(w, s, "role") >= 30 && rank(s.role) < 5 && s.quality >= 64) return Reply(false, "${s.name()} wants to take the next step (a head coach job or a bigger role) and will not extend.")
        if (salary < ask * 0.92) return Reply(false, "${s.name()} wants about ${formatMoney(ask)} per season to extend.")
        s.salary = salary
        s.contractEnd = w.season + years - 1
        epoch++
        return Reply(true, "${s.name()} extends until the end of ${s.contractEnd}.")
    }

    /** A new role within the same club (a demoted head coach may refuse). */
    fun changeRole(w: World, s: Staff, role: String, spec: String = ""): Reply {
        val team = w.teams.getOrNull(s.teamId) ?: return Reply(false, "Not at a club.")
        if (s.role == "head-coach" && role != "head-coach" && s.rep >= 45) return Reply(false, "${s.name()} would rather leave than step down from head coach.")
        if (role == "head-coach") best(w, team, "head-coach")?.let { _ -> members(w, team).firstOrNull { it.role == "head-coach" && it.id != s.id }?.let { return Reply(false, "${team.name} already have a head coach (${it.name()}).") } }
        s.role = role
        s.spec = if (role == "position-coach") spec.ifEmpty { Catalogue.get(team.titleId).lineup.first() } else ""
        refresh(s)
        s.spells.lastOrNull { it.to < 0 }?.let { it.to = w.day; it.why = "new role" }
        s.spells.add(StaffSpell(team.id, role, w.day))
        epoch++
        return Reply(true, "${s.name()} is now ${label(s).lowercase()}.")
    }

    /** The user's answer to a club that wants one of their staff. */
    fun answerBid(w: World, bidId: Int, accept: Boolean): Reply {
        val b = w.staffBids.firstOrNull { it.id == bidId && it.status == "pending" } ?: return Reply(false, "This approach is no longer open.")
        val s = w.staff[b.staffId]
        if (s.teamId != b.toTeam) { b.status = "withdrawn"; return Reply(false, "${s.name()} is no longer at your club.") }
        if (!accept) { b.status = "rejected"; s.ambition = (s.ambition + 3).coerceAtMost(100); return Reply(true, "You keep ${s.name()}.") }
        b.status = "accepted"
        hire(w, s, w.teams[b.fromTeam], b.role, b.years, b.salary, b.fee)
        return Reply(true, "${s.name()} leaves for ${w.teams[b.fromTeam].name} (compensation ${formatMoney(b.fee)}).")
    }

    // --------------------------------------------------------------------------------- creating staff

    fun create(w: World, rng: Rng, role: String, nat: String, titleId: String, q: Double, spec: String = "", ageMin: Int = 26, ageMax: Int = 52): Staff {
        val (first, last) = GameData.personName(rng, nat)
        val s = Staff(w.staff.size, first, last, nat, w.day - (rng.int(ageMin, ageMax) * 365.25 + rng.int(0, 364)).toInt(), role, 0.0, titleId)
        s.attrs = attrsFor(role, clamp(q, 20.0, 92.0), rng)
        s.spec = spec
        refresh(s)
        s.rep = clamp((s.quality - 38) * 1.1 + rng.normal(0.0, 7.0), 3.0, 75.0).roundToInt().toDouble()
        s.ambition = rng.int(20, 90); s.loyalty = rng.int(20, 90)
        w.staff.add(s)
        epoch++
        return s
    }

    /** A retiring player may stay in the game as staff: tactical minds coach or analyse, others scout or manage. */
    fun fromPlayer(w: World, p: Player, rng: Rng): Staff? {
        fun a(key: String) = dev.uem.em.model.Attrs.ALL.firstOrNull { it.key == key }?.let { p.attrs[it.index] } ?: 50.0
        val tact = (a("shotcalling") + a("decisionMaking") + a("winConditionRecognition") + a("tempoControl")) / 4
        val know = (a("draftUnderstanding") + a("metaUnderstanding") + a("matchupKnowledge") + a("preparation")) / 4
        val people = (a("leadership") + a("communication") + a("teamwork")) / 3
        val chance = (0.18 + (know - 55) / 120 + (people - 55) / 160 + (if (p.real) 0.1 else 0.0)).coerceIn(0.08, 0.6)
        if (!rng.chance(chance)) return null
        val role = when {
            know >= tact && know >= 60 -> if (rng.chance(0.5)) "analyst" else "strategic-coach"
            tact >= 62 && people >= 58 -> if (rng.chance(0.35)) "head-coach" else "assistant-coach"
            people >= 60 -> "position-coach"
            p.pers.getOrElse(5) { 30 } >= 70 -> "media-manager"
            else -> if (rng.chance(0.5)) "scout" else "position-coach"
        }
        val s = Staff(w.staff.size, p.first, p.last, p.nat, p.birth, role, 0.0, p.titleId, handle = p.handle, spec = if (role == "position-coach") p.mainRole else "", real = p.real, realSrc = p.realSrc, playerId = p.id)
        val ar = IntArray(ATTRS.size) { 30 }
        ar[TACTICS] = tact.roundToInt(); ar[DRAFTING] = know.roundToInt(); ar[TEACHING] = (people * 0.7 + know * 0.3 - 6).roundToInt()
        ar[MOTIVATION] = (people - 4).roundToInt(); ar[DISCIPLINE] = a("professionalism").roundToInt(); ar[ANALYSIS] = (know - 4).roundToInt()
        ar[JUDGEMENT] = ((know + tact) / 2 - 6).roundToInt(); ar[FITNESS] = (a("stamina") * 0.6).roundToInt(); ar[MEDICINE] = 22; ar[PSYCHOLOGY] = (a("mentalResilience") * 0.7).roundToInt()
        ar[NEGOTIATION] = 30; ar[COMMERCIAL] = 25 + (p.rep / 4).roundToInt(); ar[MEDIA] = 25 + (p.pers.getOrElse(5) { 30 } / 3); ar[ADAPTABILITY] = a("patchAdaptation").roundToInt()
        // New to the job: a few points below what the playing career suggests.
        for (i in ar.indices) ar[i] = (ar[i] - 5 + rng.normal(0.0, 4.0)).roundToInt().coerceIn(8, 90)
        s.attrs = ar
        refresh(s)
        s.rep = clamp(p.rep * 0.7, 5.0, 85.0).roundToInt().toDouble()
        s.ambition = rng.int(30, 90); s.loyalty = rng.int(30, 90)
        w.staff.add(s)
        epoch++
        return s
    }

    /** The staff a club runs with: more and more specialised the bigger the club. */
    fun plan(w: World, team: Team): List<Pair<String, String>> {
        val t = Catalogue.get(team.titleId)
        val org = w.orgs[team.orgId]
        val out = ArrayList<Pair<String, String>>()
        fun add(r: String, spec: String = "") = out.add(r to spec)
        // National teams: coaches are named with each squad (Nations), never hired.
        if (team.nationalOf.isNotEmpty()) return out
        if (team.kind.isNotEmpty()) { add("head-coach"); return out }
        if (team.parentId >= 0) { add("head-coach"); add("assistant-coach"); add("analyst"); return out }
        val tier = when { org.rep >= 62 || team.elo >= 1640 -> 2; org.rep >= 40 || team.elo >= 1500 -> 1; else -> 0 }
        val rot = Math.floorMod(team.id * 31 + 7, maxOf(1, t.lineup.size))
        add("head-coach"); add("analyst")
        if (tier >= 1) { add("assistant-coach"); add("scout"); add("team-manager"); if (t.lineup.isNotEmpty()) add("position-coach", t.lineup[rot]) }
        if (tier >= 2) {
            add("strategic-coach"); add("analyst"); add("psychologist"); add("performance-coach"); add("physio")
            if (t.lineup.size > 1) add("position-coach", t.lineup[(rot + 2) % t.lineup.size])
        }
        // Organisation roles sit with the organisation's main team.
        if (mainTeam(w, team.orgId) == team.id) {
            if (tier >= 1) add("general-manager")
            if (tier >= 2) { add("media-manager"); add("partnerships-manager") }
            if (org.teams.any { w.teams[it].parentId >= 0 && w.teams[it].active }) add("academy-director")
        }
        return out
    }

    fun mainTeam(w: World, orgId: Int): Int = w.orgs.getOrNull(orgId)?.teams?.filter { w.teams[it].active && w.teams[it].parentId < 0 && w.teams[it].nationalOf.isEmpty() }
        ?.maxByOrNull { w.teams[it].elo } ?: -1

    /** Fill a team's empty roles (from the free pool first; new people when nobody suitable is free). */
    fun fill(w: World, team: Team, rng: Rng, pool: MutableList<Staff>? = null, news: Boolean = false): Int {
        var n = 0
        val have = members(w, team).map { it.role to it.spec }.toMutableList()
        val level = clamp(42 + (team.elo - 1500) / 14, 25.0, 82.0)
        for ((role, spec) in plan(w, team)) {
            val i = have.indexOfFirst { it.first == role && (role != "position-coach" || it.second == spec) }
            if (i >= 0) { have.removeAt(i); continue }
            val cand = (pool ?: w.staff).asSequence().filter { it.teamId < 0 && !it.retired && (it.role == role || (role == "assistant-coach" && it.role == "head-coach" && it.rep < 40)) &&
                    (!def(role).titleBound || it.titleId == team.titleId) && refusal(w, it, team, role) == null && it.quality <= level + 12 }
                .maxByOrNull { it.quality + (if (it.role == role) 3 else 0) + (if (role == "position-coach" && it.spec == spec) 4 else 0) }
            val org = w.orgs[team.orgId]
            val s = if (cand != null && cand.quality >= level - 14) cand else create(w, rng, role, org.country, team.titleId, rng.normal(level - (if (role == "head-coach") 0.0 else 4.0), 6.0), spec)
            pool?.remove(s)
            hire(w, s, team, role, rng.int(1, 3), demand(w, s, team.region, role) * rng.float(0.9, 1.1), spec = spec, quiet = !news)
            n++
        }
        return n
    }

    // --------------------------------------------------------------------------------- setup

    private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD).lowercase().filter { it.isLetterOrDigit() }

    private fun pastPts(p: List<String>): Double = p.sumOf { e ->
        val f = e.split('|')
        val pts = HistoryBook.points(f.getOrElse(3) { "league" }, f.getOrNull(4)?.toDoubleOrNull() ?: 1.0, f.getOrNull(5) == "1")
        pts * (if (f.getOrNull(6) == "1") 1.0 else 0.4)
    }

    /** Real staff from staff.json: placed at their teams, the rest (former and retired coaches) free. */
    fun loadReal(w: World, src: DataSource, onlyAi: Boolean = false): Int {
        if (w.fictional) return 0
        val text = runCatching { src.read("staff.json") }.getOrNull() ?: return 0
        val root = Json.parseToJsonElement(text).jsonObject
        val list = root["staff"]?.jsonArray ?: return 0
        fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull
        val seen = HashSet<String>()
        for (s in w.staff) if (s.real) seen.add("${s.titleId}|${norm(s.handle)}")
        var n = 0
        for (e in list) {
            val o = e.jsonObject
            val title = o.s("title") ?: continue
            if (title !in w.titles) continue
            val handle = o.s("handle") ?: continue
            if (!seen.add("$title|${norm(handle)}")) continue
            val teamName = o.s("team").orEmpty()
            val team = if (teamName.isEmpty()) null else w.teams.firstOrNull { it.titleId == title && it.real && norm(it.name) == norm(teamName) }
            val rng = Rng.of("realstaff|$title|${norm(handle)}")
            val past = o["past"]?.jsonArray?.map { pe -> val p = pe.jsonObject
                listOf(p["y"]!!.jsonPrimitive.int, p.s("label"), p.s("team"), p.s("tier"), p["pr"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
                    if (p["won"]?.jsonPrimitive?.booleanOrNull == true) 1 else 0, if (p["head"]?.jsonPrimitive?.booleanOrNull == true) 1 else 0).joinToString("|")
            } ?: emptyList()
            // Titles counted as the game counts them; compiled records cover only the biggest events (×2, as for players).
            val pts = pastPts(past) * (if (o.s("src") == "compiled") 2.0 else 1.0)
            var role = o.s("role") ?: "assistant-coach"
            if (role !in BY_ID) role = "assistant-coach"
            if (team != null && role == "head-coach" && members(w, team).any { it.role == "head-coach" && it.real }) role = "assistant-coach"
            val teamBonus = team?.let { (it.elo - 1500) / 14 } ?: 0.0
            val q = clamp(47 + teamBonus * 0.8 + kotlin.math.min(24.0, sqrt(pts) * 2.7) + rng.normal(0.0, 3.5) - (if (role == "head-coach") 0 else 3), 35.0, 90.0)
            val nat = o.s("nat")?.takeIf { it.length == 2 } ?: team?.let { w.orgs[it.orgId].country } ?: GameData.pickCountry(rng, w.leagues.firstOrNull { it.titleId == title }?.region ?: "NA")
            val birth = o.s("birth")?.let { b -> runCatching { java.time.LocalDate.parse(b.take(10)).toEpochDay().toInt() }.getOrNull() }
                ?: (w.day - ((30 + rng.int(-3, 9) + (past.size.coerceAtMost(8))) * 365.25).toInt())
            val name = o.s("name").orEmpty().trim()
            val parts = name.split(' ').filter { it.isNotBlank() }
            val st = Staff(w.staff.size, parts.dropLast(1).joinToString(" "), parts.lastOrNull() ?: "", nat, birth, role, 0.0, title, handle = handle,
                spec = o.s("spec").orEmpty(), real = true, realSrc = o.s("src").orEmpty())
            st.attrs = attrsFor(role, q, rng)
            refresh(st)
            st.past.addAll(past)
            st.rep = clamp(22 + sqrt(pts) * 6.5 + teamBonus * 0.6 + rng.normal(0.0, 4.0), 8.0, 99.0).roundToInt().toDouble()
            st.ambition = rng.int(25, 90); st.loyalty = rng.int(25, 90)
            w.staff.add(st)
            if (team != null && !(onlyAi && w.isUserTeam(team.id))) {
                if (onlyAi) members(w, team).filter { !it.real && it.role == role && (role != "position-coach" || it.spec == st.spec) }.forEach { release(w, it, "replaced", severance = false) }
                hire(w, st, team, role, rng.int(1, 3), demand(w, st, team.region) * rng.float(0.95, 1.1), quiet = true)
            }
            n++
        }
        epoch++
        return n
    }

    /** New worlds: real staff where known, then every team's plan filled, then a free pool. */
    fun setup(w: World, src: DataSource?) {
        if (src != null) loadReal(w, src)
        val rng = Rng.derive(w.seed, "staff-setup2")
        for (team in w.teams) if (team.active) fill(w, team, rng)
        pool(w, rng)
        w.staffVersion = 2
    }

    /** Free agents in every role and game, so clubs (and the user) always have a choice. */
    fun pool(w: World, rng: Rng) {
        val free = w.staff.count { it.teamId < 0 && !it.retired }
        val target = maxOf(60, w.teams.count { it.active } / 3)
        repeat((target - free).coerceAtLeast(0)) {
            val nat = rng.pick(GameData.countries.keys.toList())
            val role = rng.pick(ROLES).id
            val t = rng.pick(w.titles)
            create(w, rng, role, nat, t, rng.normal(48.0, 11.0), if (role == "position-coach") rng.pick(Catalogue.get(t).lineup.ifEmpty { listOf("") }) else "")
        }
    }

    /** Older saves: attributes for the first staff, real staff added (AI clubs), every AI club's plan filled. */
    fun migrate(w: World, src: DataSource?) {
        if (w.staffVersion >= 2) return
        for (s in w.staff) {
            if (s.attrs.size != ATTRS.size) {
                val rng = Rng.derive(w.seed, "staff-migrate", s.id)
                s.attrs = attrsFor(s.role, s.quality, rng)
                refresh(s)
                s.rep = clamp((s.quality - 38) * 1.1 + rng.normal(0.0, 7.0), 3.0, 75.0).roundToInt().toDouble()
                s.ambition = rng.int(20, 90); s.loyalty = rng.int(20, 90)
            }
            if (s.teamId >= 0 && s.spells.isEmpty()) s.spells.add(StaffSpell(s.teamId, s.role, w.day))
        }
        if (src != null) loadReal(w, src, onlyAi = true)
        val rng = Rng.derive(w.seed, "staff-migrate-fill")
        for (team in w.teams) if (team.active && !w.isUserTeam(team.id)) fill(w, team, rng)
        pool(w, rng)
        w.staffVersion = 2
        if (w.userOrg >= 0) w.inbox.add(Message(w.day, "Director", "New: a full staff", "Clubs now run with head coaches, assistants, draft and positional coaches, analysts, psychologists, performance coaches, physios, scouts, team managers and organisation roles. Real coaches have joined their clubs or the staff market (Market → Staff). Your own staff were kept."))
    }

    // --------------------------------------------------------------------------------- the year

    /** Monthly: AI clubs fill vacancies; struggling clubs may sack the head coach; clubs approach the user's staff. */
    fun monthly(w: World) {
        val rng = Rng.derive(w.seed, "staff-month", w.day)
        // Results of the last two months per team.
        val won = IntArray(w.teams.size); val lost = IntArray(w.teams.size)
        for (f in w.fixtures) if (f.done && f.day > w.day - 60 && f.a >= 0 && f.b >= 0) { if (f.winner == f.a) { won[f.a]++; lost[f.b]++ } else { won[f.b]++; lost[f.a]++ } }
        for (team in w.teams) {
            if (!team.active || w.isUserTeam(team.id) || team.nationalOf.isNotEmpty()) continue
            var hc = members(w, team).firstOrNull { it.role == "head-coach" }
            val g = won[team.id] + lost[team.id]
            if (hc != null && g >= 6 && won[team.id] < g * 0.3 && w.day - hc.joinedDay > 120 && team.elo >= 1520 && rng.chance(0.2)) {
                release(w, hc, "sacked after poor results")
                hc.rep = (hc.rep - 4).coerceAtLeast(1.0)
                hc = null
            }
            if (Dates.month(w.day) % 3 == 1 || hc == null) fill(w, team, rng, news = true)
        }
        // A bigger club without a head coach (or with a weaker one) asks for the user's.
        if (w.userOrg >= 0) for (tid in w.orgs[w.userOrg].teams) {
            val mine = w.teams[tid]
            for (s in members(w, mine)) {
                if (s.role !in setOf("head-coach", "strategic-coach", "assistant-coach") || s.quality < 62 || !rng.chance(0.03)) continue
                if (w.staffBids.any { it.staffId == s.id && it.status == "pending" }) continue
                val club = w.teams.filter { it.active && it.titleId == mine.titleId && !w.isUserTeam(it.id) && it.parentId < 0 && it.kind.isEmpty() && w.orgs[it.orgId].rep > w.orgs[mine.orgId].rep + 5 &&
                    (best(w, it, "head-coach") ?: 0.0) < s.quality - 4 }.maxByOrNull { w.orgs[it.orgId].rep } ?: continue
                val fee = (compensation(w, s) * rng.float(0.9, 1.3) / 1000).roundToInt() * 1000.0
                val b = StaffBid(w.nextStaffBid++, s.id, club.id, mine.id, fee, "head-coach", w.day, salary = demand(w, s, club.region, "head-coach") * 1.15, years = 2)
                w.staffBids.add(b)
                w.inbox.add(Message(w.day, club.name, "${club.name} want ${s.name()}", "${club.name} offer ${formatMoney(fee)} compensation to make ${s.name()} their head coach. Answer in Squad → Staff."))
            }
        }
        w.staffBids.removeAll { it.status != "pending" && it.day < w.day - 120 }
        for (b in w.staffBids) if (b.status == "pending" && w.day - b.day > 21) b.status = "expired"
    }

    /** Season end: growth and decline, reputation from titles, contracts, the coaching carousel, retirements. */
    fun seasonEnd(w: World, rng: Rng) {
        val champions = HashMap<Int, Double>()
        for (c in w.comps) if (c.done && c.season == w.season - 1 && c.placements.isNotEmpty()) {
            val pts = when (c.tier) { CompTier.WORLDS -> 12.0; CompTier.INTERNATIONAL -> 6.0; CompTier.LEAGUE -> 3.0; else -> 0.5 }
            champions.merge(c.placements[0], pts, Double::plus)
        }
        for (s in w.staff) {
            if (s.retired) continue
            val age = age(w, s)
            // Experience: younger staff grow in their role's attributes, older staff slowly lose some.
            if (s.attrs.size == ATTRS.size) {
                val grow = if (age < 35) 1.2 else if (age < 50) 0.5 else if (age < 58) 0.0 else -0.8
                for ((i, _) in def(s.role).weights) s.attrs[i] = (s.attrs[i] + grow + rng.normal(0.0, 0.8) * (if (s.teamId >= 0) 1.0 else 0.6)).roundToInt().coerceIn(8, 97)
                refresh(s)
            }
            if (s.teamId >= 0 && s.role in COACHING) champions[s.teamId]?.let { s.rep = clamp(s.rep + it * (if (s.role == "head-coach") 0.8 else 0.35), 1.0, 99.0) }
            // Retirement: older staff, long out of work.
            if ((age >= 63 && rng.chance(0.35)) || (s.teamId < 0 && age >= 55 && rng.chance(0.12))) {
                if (s.teamId >= 0) release(w, s, "retired", severance = false)
                s.retired = true; s.retiredSeason = w.season
            }
        }
        // Contracts ending: AI clubs keep staff they rate and who want to stay; the user's leave unless renewed.
        for (s in w.staff) if (!s.retired && s.teamId >= 0 && s.contractEnd < w.season) {
            val team = w.teams[s.teamId]
            val org = w.orgs[team.orgId]
            val stepUp = amb(w, s, "role") >= 30 && rank(s.role) < 5 && s.quality >= 64 && rng.chance(0.5)
            val wantsOut = (s.ambition >= 70 && s.rep > org.rep + 12 && rng.chance(0.6)) || stepUp
            val loyal = amb(w, s, "loyalty") >= 28
            if (w.isUserTeam(team.id) || wantsOut || s.quality < 35 || (!loyal && rng.chance(0.2))) release(w, s, if (stepUp) "left to look for a bigger role" else if (wantsOut) "left for a bigger challenge" else "contract ended", severance = false)
            else s.contractEnd = w.season + rng.int(0, 2)
        }
        // The coaching carousel: big clubs replace a weaker head coach with a clearly better one.
        val clubs = w.teams.filter { it.active && !w.isUserTeam(it.id) && it.parentId < 0 && it.kind.isEmpty() && it.nationalOf.isEmpty() && w.orgs[it.orgId].rep >= 55 }.sortedByDescending { w.orgs[it.orgId].rep }
        for (team in clubs.take(60)) {
            val cur = members(w, team).firstOrNull { it.role == "head-coach" }
            val curQ = cur?.quality ?: 0.0
            val target = w.staff.asSequence().filter { s -> !s.retired && s.titleId == team.titleId && (s.role == "head-coach" || (s.role in setOf("assistant-coach", "strategic-coach") && s.quality >= 66)) && s.teamId != team.id &&
                    (s.teamId < 0 || (!w.isUserTeam(s.teamId) && w.orgs[w.teams[s.teamId].orgId].rep < w.orgs[team.orgId].rep)) && s.quality >= curQ + 6 && refusal(w, s, team, "head-coach") == null }
                .maxByOrNull { it.quality + it.rep * 0.1 } ?: continue
            if (!rng.chance(0.55)) continue
            if (cur != null) release(w, cur, "replaced")
            hire(w, target, team, "head-coach", rng.int(2, 3), demand(w, target, team.region, "head-coach") * rng.float(1.0, 1.15), compensation(w, target))
        }
        // Players who retired this season may join the staff pool.
        for (p in w.players) if (p.retired && p.retiredSeason == w.season && w.staff.none { it.playerId == p.id }) fromPlayer(w, p, Rng.derive(w.seed, "player-to-staff", p.id))
        for (team in w.teams) if (team.active && !w.isUserTeam(team.id)) fill(w, team, rng)
        pool(w, rng)
    }

    /** September: the user's staff whose contracts end this season. */
    fun reminders(w: World) {
        if (w.userOrg < 0) return
        for (tid in w.orgs[w.userOrg].teams) for (s in members(w, w.teams[tid])) if (s.contractEnd == w.season)
            w.inbox.add(Message(w.day, "Contracts", "${s.name()}'s contract ends this season", "${s.name()} (${label(s).lowercase()}) leaves when the season ends unless you extend the contract from their page."))
    }

    // --------------------------------------------------------------------------------- legacy

    /** Champions of finished competitions by team, with the competition's last match day (one pass over the fixtures). */
    class Titles(val byTeam: Map<Int, List<Pair<dev.uem.em.model.Competition, Int>>>)

    fun titles(w: World): Titles {
        val end = IntArray(w.comps.size)
        for (f in w.fixtures) if (f.compId in end.indices && f.day > end[f.compId]) end[f.compId] = f.day
        val by = HashMap<Int, ArrayList<Pair<dev.uem.em.model.Competition, Int>>>()
        for (c in w.comps) if (c.done && c.placements.isNotEmpty() && c.tier != CompTier.CHALLENGER) by.getOrPut(c.placements[0]) { ArrayList() }.add(c to maxOf(end[c.id], c.startDay))
        return Titles(by)
    }

    /** Titles won in the save with a coaching role at the champion (head coach full points, other coaches 40%), and real titles before it. */
    fun honours(w: World, s: Staff, idx: Titles = titles(w)): List<Legacy.Honour> {
        val out = ArrayList<Legacy.Honour>()
        for (spell in s.spells) {
            if (spell.role !in COACHING) continue
            for ((c, day) in idx.byTeam[spell.teamId] ?: continue) {
            if (spell.from > day || (spell.to >= 0 && spell.to < day)) continue
            val sp = spell
            val pts = trophyPts(c.tier, c.prestige) * (if (sp.role == "head-coach") 1.0 else 0.4)
            out.add(Legacy.Honour(c.season, c.titleId, c.name + if (sp.role == "head-coach") "" else " (${roleLabel(sp.role, c.titleId).lowercase()})", c.id, spell.teamId, pts, true))
            }
        }
        val start = w.firstSeason
        for (e in s.past) {
            val f = e.split('|')
            val y = f[0].toIntOrNull() ?: continue
            if (y >= start) continue
            val won = f.getOrNull(5) == "1"; val head = f.getOrNull(6) == "1"
            val pts = HistoryBook.points(f.getOrElse(3) { "league" }, f.getOrNull(4)?.toDoubleOrNull() ?: 1.0, won) * (if (head) 1.0 else 0.4) * (if (s.realSrc == "compiled") 2.0 else 1.0)
            out.add(Legacy.Honour(y, s.titleId, f.getOrElse(1) { "" } + (if (!won) " (runner-up)" else "") + (if (!head) " (coaching staff)" else ""), -1, -1, pts, won, f.getOrElse(2) { "" }, past = true))
        }
        return out.sortedWith(compareByDescending<Legacy.Honour> { it.season }.thenByDescending { it.pts })
    }

    private fun trophyPts(tier: CompTier, prestige: Int) = when (tier) {
        CompTier.WORLDS -> 12.0
        CompTier.INTERNATIONAL -> 5.0 + prestige / 20.0
        CompTier.LEAGUE -> 2.0 + prestige / 40.0
        CompTier.CHALLENGER -> 0.8
        CompTier.CUSTOM -> 0.5 + prestige / 80.0
    }

    /** A coach's legacy: titles (head coach full, assistants 40%), years coaching (in the save and before it), peak ability. */
    fun legacy(w: World, s: Staff, hs: List<Legacy.Honour> = honours(w, s)): Legacy.Score {
        val ach = hs.sumOf { it.pts }
        val seasons = s.spells.filter { it.role in COACHING }.flatMap { sp -> (Dates.year(sp.from)..Dates.year(if (sp.to < 0) w.day else sp.to)).toList() }.distinct().size
        val past = hs.filter { it.past }
        val pastYears = if (past.isEmpty()) 0 else (past.maxOf { it.season } - past.minOf { it.season } + 1).coerceAtMost(15)
        val longevity = (seasons + pastYears) * 1.2
        val peak = ((s.quality - 60).coerceAtLeast(0.0)) * 0.4
        return Legacy.Score(ach, peak, longevity, 0.0, ach + peak + longevity, hs.groupBy { it.titleId }.mapValues { (_, v) -> v.sumOf { it.pts } }, past.sumOf { it.pts })
    }

    /** Coaching staff who appear in the legacy rankings (anyone who coached in the save or has real titles). */
    fun coaches(w: World, titleId: String?): List<Staff> = w.staff.filter { s ->
        (titleId == null || s.titleId == titleId) && (s.role in COACHING || s.spells.any { it.role in COACHING } || s.past.isNotEmpty()) && (s.past.isNotEmpty() || s.spells.isNotEmpty())
    }
}

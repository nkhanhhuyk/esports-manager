package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.model.Attrs
import dev.uem.em.model.CareerEntry
import dev.uem.em.model.DevPlan
import dev.uem.em.model.Mentorship
import dev.uem.em.model.Message
import dev.uem.em.model.Player
import dev.uem.em.model.Staff
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.sim.Ratings
import dev.uem.em.titles.Catalogue
import kotlin.math.roundToInt

/**
 * Mentorship and the academy pathway.
 *
 * A mentor — an experienced player of the same organisation, or a coach / psychologist on its staff —
 * teaches a mentee toward one goal. Teaching ability comes from communication, leadership,
 * professionalism and patience (staff: their quality), not from reputation or current ability; a
 * mentor takes a limited number of mentees; a weekly session happens only when both are available
 * (not injured, on a break or on another team) and the mentor has capacity. Each completed session
 * moves the goal's attributes a little (less near the top, scaled by the mentee's coachability and the
 * relationship), capped per relationship; title-specific goals need a mentor who knows the game. Every
 * change is logged as evidence. AI organisations mentor and promote with the same rules.
 */
object Mentoring {
    class Goal(val id: String, val label: String, val attrs: List<String>, val titleSpecific: Boolean, val roleSpecific: Boolean = false)

    val GOALS = listOf(
        Goal("communication", "Communication", listOf("communication", "coordination", "teamwork"), false),
        Goal("shotcalling", "Shotcalling", listOf("shotcalling", "leadership", "decisionMaking"), true),
        Goal("stage", "Stage readiness", listOf("composure", "mentalResilience", "concentration"), false),
        Goal("discipline", "Professional discipline", listOf("discipline", "professionalism", "workEthic"), false),
        Goal("draft", "Draft understanding", listOf("draftUnderstanding", "metaUnderstanding", "counterplay"), true),
        Goal("adaptation", "Adaptation", listOf("adaptability", "patchAdaptation", "learningSpeed"), false),
        Goal("role", "Role familiarity", listOf("matchupKnowledge", "preparation"), true, roleSpecific = true),
    )
    fun goal(id: String) = GOALS.firstOrNull { it.id == id } ?: GOALS[0]

    /** Most attribute points one relationship can add to each goal attribute. */
    const val CAP_PER_ATTR = 4.0
    const val WEEKS = 16

    private fun a(p: Player, key: String) = p.attrs[Attrs.idx(key)]

    /** How well a player teaches (0-100): communication, leadership, professionalism, patience. */
    fun teaching(p: Player): Double = (a(p, "communication") * 0.35 + a(p, "leadership") * 0.25 + a(p, "professionalism") * 0.2 + a(p, "patience") * 0.2)
    fun teaching(s: Staff): Double = s.quality * (if (s.role == "assistant-coach" || s.role == "psychologist") 1.05 else 0.95)

    /** Mentees a mentor can take at once. */
    fun capacity(p: Player): Int = (1 + (a(p, "leadership") + a(p, "communication")) / 85).toInt().coerceIn(1, 3)
    fun capacity(s: Staff): Int = (2 + s.quality / 40).toInt().coerceIn(2, 4)

    fun active(w: World) = w.mentorships.filter { it.active }
    fun of(w: World, menteeId: Int) = w.mentorships.lastOrNull { it.active && it.menteeId == menteeId }
    fun load(w: World, mentorId: Int, staff: Boolean) = w.mentorships.count { it.active && it.mentorId == mentorId && it.staffMentor == staff }

    private fun orgOfPlayer(w: World, p: Player) = w.teams.getOrNull(p.teamId)?.orgId ?: -1

    /** Why a mentor cannot take this mentee (null: possible). */
    fun cannot(w: World, mentee: Player, mentorPlayer: Player?, mentorStaff: Staff?, goal: Goal): String? {
        val org = orgOfPlayer(w, mentee)
        if (org < 0) return "${mentee.handle} is not under contract with an organisation"
        if (of(w, mentee.id) != null) return "${mentee.handle} already has a mentor"
        if (mentorPlayer != null) {
            if (mentorPlayer.id == mentee.id) return "a player cannot mentor themselves"
            if (orgOfPlayer(w, mentorPlayer) != org) return "${mentorPlayer.handle} plays for another organisation"
            if (load(w, mentorPlayer.id, false) >= capacity(mentorPlayer)) return "${mentorPlayer.handle} has no capacity left (${capacity(mentorPlayer)} mentees)"
            if (goal.titleSpecific && mentorPlayer.titleId != mentee.titleId) return "${mentorPlayer.handle} does not know ${Catalogue.get(mentee.titleId).short}"
            if (goal.roleSpecific && mentorPlayer.roles.firstOrNull() != mentee.roles.firstOrNull()) return "${mentorPlayer.handle} plays another role"
        }
        if (mentorStaff != null) {
            if (mentorStaff.orgId != org) return "${mentorStaff.name()} works for another organisation"
            if (load(w, mentorStaff.id, true) >= capacity(mentorStaff)) return "${mentorStaff.name()} has no capacity left (${capacity(mentorStaff)} mentees)"
            if (goal.titleSpecific && mentorStaff.titleId != mentee.titleId) return "${mentorStaff.name()} works in ${Catalogue.get(mentorStaff.titleId).short}"
        }
        return null
    }

    /** Candidate mentors for a mentee and goal, best teachers first (players and staff of the organisation). */
    fun candidates(w: World, mentee: Player, goal: Goal): List<Pair<String, Int>> {
        val org = orgOfPlayer(w, mentee)
        if (org < 0) return emptyList()
        val players = w.orgs[org].teams.flatMap { w.teams[it].roster }.map { w.players[it] }.filter { !it.retired && cannot(w, mentee, it, null, goal) == null }
            .sortedByDescending { teaching(it) }.map { "p" to it.id }
        val staff = w.staff.filter { it.orgId == org && !it.retired && it.role in setOf("head-coach", "assistant-coach", "psychologist", "analyst") && cannot(w, mentee, null, it, goal) == null }
            .sortedByDescending { teaching(it) }.map { "s" to it.id }
        return (players + staff).take(10)
    }

    fun start(w: World, mentee: Player, mentorId: Int, staff: Boolean, goalId: String): String? {
        val g = goal(goalId)
        val why = cannot(w, mentee, if (staff) null else w.players[mentorId], if (staff) w.staff[mentorId] else null, g)
        if (why != null) return why
        val m = Mentorship((w.mentorships.maxOfOrNull { it.id } ?: -1) + 1, mentorId, staff, mentee.id, g.id, w.day, w.day + WEEKS * 7)
        val compat = compatibility(w, m)
        m.relation = clamp(45 + compat * 20, 20.0, 80.0)
        m.log.add("${Dates.short(w.day)}: started (${g.label}); first impression ${if (compat > 0.3) "good" else if (compat < -0.3) "uneasy" else "neutral"}")
        w.mentorships.add(m)
        return null
    }

    fun end(w: World, m: Mentorship, why: String) {
        if (!m.active) return
        m.active = false; m.endedDay = w.day
        m.log.add("${Dates.short(w.day)}: ended — $why")
    }

    fun mentorName(w: World, m: Mentorship) = if (m.staffMentor) w.staff[m.mentorId].name() else w.players[m.mentorId].handle

    /** Personality fit (-1..1): two big egos or hot tempers clash; a calm, loyal pair works well. */
    private fun compatibility(w: World, m: Mentorship): Double {
        val e = w.players[m.menteeId].pers
        if (m.staffMentor) return clamp((60 - e[3]) / 80.0 + (60 - e[2]) / 120.0, -1.0, 1.0)
        val r = w.players[m.mentorId].pers
        return clamp(((120 - r[3] - e[3]) / 100.0) + ((100 - kotlin.math.abs(r[2] - e[2])) / 200.0) - 0.4, -1.0, 1.0)
    }

    private fun available(w: World, p: Player) = !p.retired && p.injuryUntil <= w.day && p.visaUntil <= w.day

    /** One week: a session for each relationship that can meet; reviews and AI mentoring. */
    fun weekly(w: World) {
        val rng = Rng.derive(w.seed, "mentoring", w.day)
        val sessionsBy = HashMap<String, Int>()
        for (m in w.mentorships) {
            if (!m.active) continue
            val mentee = w.players[m.menteeId]
            val g = goal(m.goal)
            val menteeOrg = orgOfPlayer(w, mentee)
            val mentorOk = if (m.staffMentor) w.staff[m.mentorId].let { !it.retired && it.orgId == menteeOrg } else w.players[m.mentorId].let { available(w, it) && orgOfPlayer(w, it) == menteeOrg }
            if (menteeOrg < 0 || mentee.retired || (if (m.staffMentor) w.staff[m.mentorId].orgId != menteeOrg else orgOfPlayer(w, w.players[m.mentorId]) != menteeOrg)) { end(w, m, "no longer at the same organisation"); continue }
            val key = (if (m.staffMentor) "s" else "p") + m.mentorId
            val cap = if (m.staffMentor) capacity(w.staff[m.mentorId]) else capacity(w.players[m.mentorId])
            val used = sessionsBy[key] ?: 0
            // A packed schedule (tired mentor or mentee) also costs sessions.
            val tired = mentee.fatigue > 70 || (!m.staffMentor && w.players[m.mentorId].fatigue > 75)
            if (!mentorOk || !available(w, mentee) || used >= cap || (tired && rng.chance(0.5))) {
                m.missed++
                if (m.missed % 3 == 1) m.log.add("${Dates.short(w.day)}: session missed (${when { !available(w, mentee) -> "${mentee.handle} unavailable"; !mentorOk -> "mentor unavailable"; used >= cap -> "mentor fully booked"; else -> "too much fatigue" }})")
                m.relation = clamp(m.relation - 0.5, 0.0, 100.0)
                continue
            }
            sessionsBy[key] = used + 1
            m.sessions++
            val compat = compatibility(w, m)
            // Clashes happen (more with poor compatibility) and cool the relationship.
            if (rng.chance(0.06 - compat * 0.04)) { m.relation = clamp(m.relation - 6, 0.0, 100.0); m.log.add("${Dates.short(w.day)}: disagreement in a session"); continue }
            m.relation = clamp(m.relation + 1.2 + compat, 0.0, 100.0)
            val teach = if (m.staffMentor) teaching(w.staff[m.mentorId]) else teaching(w.players[m.mentorId])
            val coach = mentee.attrs[Attrs.idx("coachability")]
            val per = 0.32 * (0.4 + teach / 100) * (0.5 + coach / 100) * (0.6 + m.relation / 125)
            var gainedNow = 0.0
            for (key2 in g.attrs) {
                val i = Attrs.idx(key2)
                val room = (CAP_PER_ATTR - m.gained / g.attrs.size).coerceAtLeast(0.0)
                val v = mentee.attrs[i]
                val d = (per * (1 - v / 110) * rng.float(0.6, 1.2)).coerceAtMost(room).coerceAtLeast(0.0)
                if (d <= 0) continue
                mentee.attrs[i] = clamp(v + d, 1.0, 99.0)
                gainedNow += d
            }
            if (g.roleSpecific) { val rk = "${mentee.titleId}:${mentee.roles[0]}"; mentee.profRole[rk] = clamp((mentee.profRole[rk] ?: 0.0) + per * 1.5, 0.0, 100.0) }
            if (gainedNow > 0) {
                m.gained += gainedNow
                mentee.attrVersion++
                val t = Catalogue.get(mentee.titleId)
                mentee.ca = Ratings.computeCA(mentee, t)
                if (mentee.ca > mentee.pa) mentee.pa = mentee.ca
                if (m.sessions % 4 == 0) m.log.add("${Dates.short(w.day)}: session ${m.sessions} — ${g.label.lowercase()} +%.1f so far".format(m.gained))
            }
        }
        // Reviews.
        for (m in w.mentorships) if (m.active && w.day >= m.reviewDay) {
            val mentee = w.players[m.menteeId]
            val g = goal(m.goal)
            val text = "${mentee.handle} with ${mentorName(w, m)} (${g.label}): ${m.sessions} sessions held, ${m.missed} missed; " +
                "+%.1f on %s; relationship %s.".format(m.gained, g.attrs.joinToString("/"), relationText(m.relation))
            val user = w.isUserTeam(mentee.teamId)
            if (user) w.inbox.add(Message(w.day, "Development", "Mentorship review: ${mentee.handle}", text + " The relationship continues for another ${WEEKS} weeks unless you end it."))
            m.log.add("${Dates.short(w.day)}: review — " + text)
            if (!user && (m.gained >= CAP_PER_ATTR * g.attrs.size * 0.9 || m.relation < 25)) end(w, m, "review: ${if (m.relation < 25) "relationship broke down" else "goal reached"}")
            else m.reviewDay = w.day + WEEKS * 7
        }
        aiMentoring(w)
        // Ended relationships are kept a year (yours three), and each log keeps its latest entries.
        if (w.mentorships.size > 1500) w.mentorships.removeAll { m -> !m.active && m.endedDay < w.day - (if (w.isUserTeam(w.players[m.menteeId].teamId)) 3 * 365 else 365) }
        for (m in w.mentorships) if (m.log.size > 40) m.log.subList(0, m.log.size - 40).clear()
    }

    fun relationText(r: Double) = when { r >= 75 -> "close"; r >= 55 -> "good"; r >= 35 -> "workable"; else -> "strained" }

    /** AI organisations (and delegated user teams): young players with room to grow get a suitable mentor. */
    private fun aiMentoring(w: World) {
        val rng = Rng.derive(w.seed, "ai-mentors", w.day)
        for (team in w.teams) {
            if (!team.active || team.kind.isNotEmpty() || team.nationalOf.isNotEmpty()) continue
            if (w.isUserTeam(team.id) && !Roles.delegated(w, "development")) continue
            if (rng.chance(0.5)) continue
            val young = team.roster.map { w.players[it] }.filter { !it.retired && Dates.ageAt(it.birth, w.day) <= 21 && it.pa - it.ca >= 6 && of(w, it.id) == null }.maxByOrNull { it.pa - it.ca } ?: continue
            val g = GOALS.minByOrNull { gg -> gg.attrs.sumOf { young.attrs[Attrs.idx(it)] } / gg.attrs.size } ?: continue
            val c = candidates(w, young, g).firstOrNull() ?: continue
            start(w, young, c.second, c.first == "s", g.id)
        }
    }

    // ------------------------------------------------------------------ pathway

    /** A player's pathway stage now: free agent / amateur / trial / academy / reserve / first team / on loan. */
    fun stage(w: World, p: Player): String {
        val plan = w.devPlans.firstOrNull { it.playerId == p.id }
        if (plan != null && plan.trialUntil >= w.day && plan.trialTeam >= 0) return "on trial at ${w.teams[plan.trialTeam].short}"
        val team = w.teams.getOrNull(p.teamId)
        return when {
            p.retired -> "retired"
            team == null -> if (w.teams.any { it.kind.isNotEmpty() && p.id in it.roster }) "amateur / circuit player (unsigned)" else if (p.amateur) "ranked prospect (unsigned)" else "free agent"
            p.loanFrom >= 0 -> "on loan"
            team.parentId >= 0 -> "academy"
            p.id in team.lineup -> "first team"
            else -> "reserve"
        }
    }

    fun plan(w: World, p: Player): DevPlan = w.devPlans.firstOrNull { it.playerId == p.id } ?: DevPlan(p.id).also { w.devPlans.add(it) }

    /** Games played in the last [days] days (box scores of the player's series). */
    fun gamesPlayed(w: World, p: Player, days: Int): Int {
        var n = 0
        for (s in p.stats) if (s.season >= w.season - 1) n += 0
        for (c in w.comps) { if (c.season < w.season - 1) continue; val st = c.pstats[p.id] ?: continue; if (Rankings.endDay(w, c) >= w.day - days) n += st.gp }
        return n
    }

    /** Ready players who do not play: academy or reserve players as good as the starter in their role who played little lately. */
    fun opportunityGaps(w: World, team: Team): List<String> {
        val t = Catalogue.get(team.titleId)
        val out = ArrayList<String>()
        val pool = (team.roster + (Academies.of(w, team)?.roster ?: emptyList())).map { w.players[it] }.filter { !it.retired && it.id !in team.lineup }
        for (p in pool) {
            val ri = t.lineup.indexOf(p.roles[0])
            val starter = team.lineup.getOrNull(ri)?.takeIf { it >= 0 }?.let { w.players[it] } ?: continue
            if (p.ca >= starter.ca - 2 && gamesPlayed(w, p, 60) < 3) out.add("${p.handle} (${t.role(p.roles[0]).short}, ${stage(w, p)}) is as good as ${starter.handle} (%.0f vs %.0f) but has played %d games in 60 days".format(p.ca, starter.ca, gamesPlayed(w, p, 60)))
        }
        return out
    }

    class Succession(val role: String, val starter: Player?, val risk: String, val next: Player?, val readiness: String)

    /** Per position: the starter's risks (age, contract) and the best successor (reserve or academy) with readiness. */
    fun succession(w: World, team: Team): List<Succession> {
        val t = Catalogue.get(team.titleId)
        val academy = Academies.of(w, team)
        return t.lineup.mapIndexed { i, role ->
            val starter = team.lineup.getOrNull(i)?.takeIf { it >= 0 }?.let { w.players[it] }
            val risks = ArrayList<String>()
            if (starter != null) {
                val age = Dates.ageAt(starter.birth, w.day)
                if (starter.contractEnd <= w.season) risks.add("contract ends ${starter.contractEnd}")
                if (age >= starter.dev.declineAge) risks.add("age $age (past peak)")
                if (starter.listed) risks.add("transfer-listed")
            } else risks.add("no starter")
            val cands = (team.roster + (academy?.roster ?: emptyList())).map { w.players[it] }.filter { !it.retired && it.id != starter?.id && (it.roles[0] == role || it.roles.getOrNull(1) == role) }
            val next = cands.maxByOrNull { it.ca + (it.pa - it.ca) * 0.3 }
            val ready = when {
                next == null -> "nobody — sign or develop one"
                starter == null || next.ca >= starter.ca - 2 -> "ready now"
                next.ca >= starter.ca - 8 -> "about a season away (%.0f vs %.0f)".format(next.ca, starter.ca)
                else -> "not close (%.0f vs %.0f; potential %.0f)".format(next.ca, starter.ca, next.pa)
            }
            Succession(t.role(role).name, starter, risks.joinToString(", ").ifBlank { "no risk now" }, next, ready)
        }
    }

    /** Invite an unsigned player to a two-week trial at an academy (or team); at its end, sign or release. */
    fun trial(w: World, p: Player, team: Team): String? {
        if (p.teamId >= 0 && !w.teams[p.teamId].kind.isNotEmpty()) return "${p.handle} is under contract"
        val pl = plan(w, p)
        if (pl.trialUntil >= w.day) return "${p.handle} is already on trial"
        pl.trialTeam = team.id; pl.trialUntil = w.day + 14; pl.stage = "trial"
        p.career.add(CareerEntry(w.season, w.day, team.id, "trial"))
        return null
    }

    /** AI academies promote a ready prospect over an older, weaker starter at season start (not every season, not everyone). */
    fun aiSuccession(w: World) {
        val rng = Rng.derive(w.seed, "ai-succession", w.season)
        for (team in w.teams) {
            if (!team.active || team.kind.isNotEmpty() || team.parentId >= 0 || w.isUserTeam(team.id)) continue
            val academy = Academies.of(w, team) ?: continue
            val t = Catalogue.get(team.titleId)
            for ((i, role) in t.lineup.withIndex()) {
                val starter = team.lineup.getOrNull(i)?.takeIf { it >= 0 }?.let { w.players[it] } ?: continue
                val pick = Academies.prospects(w, academy, role).firstOrNull() ?: continue
                if (pick.ca < starter.ca + 1.5 || Dates.ageAt(starter.birth, w.day) < 24 || !rng.chance(0.6)) continue
                if (Academies.move(w, pick).ok) w.news.add(dev.uem.em.model.News(w.day, "${pick.handle} promoted to ${team.short}", "${team.name} promote ${pick.handle} from their academy to take over from ${starter.handle}.", listOf(team.id)))
            }
        }
    }

    /** End of the trial period: AI teams sign a trialist who outperforms their weakest player; others are released. */
    fun trials(w: World) {
        for (pl in w.devPlans) {
            if (pl.trialTeam < 0 || pl.trialUntil != w.day) continue
            val p = w.players[pl.playerId]
            val team = w.teams[pl.trialTeam]
            if (w.isUserTeam(team.id)) {
                w.inbox.add(Message(w.day, "Development", "Trial ended: ${p.handle}", "${p.handle}'s trial at ${team.name} is over. Sign them from the Market (an academy contract costs little) or let them go."))
            } else {
                val weakest = team.roster.map { w.players[it] }.filter { it.roles[0] == p.roles[0] }.minByOrNull { it.ca }
                if (p.teamId < 0 && (weakest == null || p.ca > weakest.ca + 1) && team.roster.size < Catalogue.get(team.titleId).teamSize + 4) {
                    p.teamId = team.id; team.roster.add(p.id); p.contractEnd = w.season + 2; p.salary = Economy.expectedSalary(p, region = team.region) * 0.5; p.joinedDay = w.day
                    p.career.add(CareerEntry(w.season, w.day, team.id, "signed after trial"))
                    w.transfers.add(dev.uem.em.model.Transfer(w.day, w.season, p.id, -1, team.id, 0.0, "trial"))
                    team.lineup = Lineups.auto(w, team)
                }
            }
            pl.stage = ""; pl.trialTeam = -1
        }
    }

    @Suppress("unused") private fun r(x: Double) = (x * 10).roundToInt() / 10.0
}

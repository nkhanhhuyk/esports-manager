package dev.uem.em.mgmt

import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.core.formatMoney
import dev.uem.em.data.GameData
import dev.uem.em.gen.GenCtx
import dev.uem.em.gen.PlayerGen
import dev.uem.em.model.CareerEntry
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.titles.Catalogue
import kotlin.math.roundToInt

/**
 * Academy (development) teams: a second squad of an organisation that grows young players for its
 * main team. An academy plays in its region's Challengers league when there is one (never in its
 * parent's league), otherwise it trains and scrims as a development squad. Players move between the
 * academy and the main team at no cost; their contracts stay the same.
 */
object Academies {
    fun of(w: World, team: Team): Team? = if (team.parentId >= 0) null else w.teams.firstOrNull { it.parentId == team.id && it.active }
    fun parent(w: World, team: Team): Team? = team.parentId.takeIf { it >= 0 }?.let { w.teams[it] }
    fun isAcademy(team: Team) = team.parentId >= 0

    fun cost(w: World, parent: Team): Double = (60000 * (0.4 + GameData.economy(w.orgs[parent.orgId].region)) / 1000).roundToInt() * 1000.0

    /** The Challengers league an academy of this team would play in, if the region has one. */
    fun leagueFor(w: World, parent: Team) = w.leagues.getOrNull(parent.leagueId)?.let { l -> w.leagues.getOrNull(l.lowerId) }
        // A league with several lower leagues (LCP: LJL, PCS, VCS): the one of the organisation's country.
        ?: w.leagues.getOrNull(parent.leagueId)?.let { l -> w.leagues.firstOrNull { it.upperId == l.id && it.tier == 2 && w.orgs[parent.orgId].region in GameData.expand(it.region) } }
        ?: w.leagues.firstOrNull { it.titleId == parent.titleId && it.tier == 2 && it.region == parent.region }

    class Result(val ok: Boolean, val text: String, val team: Team? = null)

    /** Found an academy for [parent] (paid by its organisation). */
    fun found(w: World, parent: Team, charge: Boolean = true, rng: Rng = Rng.derive(w.seed, "academy", parent.id, w.day)): Result {
        if (isAcademy(parent)) return Result(false, "An academy cannot have its own academy.")
        if (parent.nationalOf.isNotEmpty()) return Result(false, "National teams have no academy.")
        if (of(w, parent) != null) return Result(false, "${parent.name} already has an academy.")
        val org = w.orgs[parent.orgId]
        val cost = cost(w, parent)
        if (charge && org.balance < cost) return Result(false, "Founding an academy costs ${formatMoney(cost)}.")
        if (charge) Economy.ledger(w, org, "academy", -cost, "${parent.name} Academy founded")
        val t = Catalogue.get(parent.titleId)
        val league = leagueFor(w, parent)
        val team = Team(w.teams.size, org.id, parent.titleId, "${parent.name} Academy", (parent.short.take(4) + ".A"), parent.region, leagueId = league?.id ?: -1,
            style = parent.style, aggression = parent.aggression, chemistry = 55.0, elo = parent.elo - 180, metaAdapt = parent.metaAdapt - 4, coach = clamp(parent.coach - 10, 25.0, 85.0),
            real = false, dataNote = if (parent.real) "Academy of a real organisation: generated roster (no verified academy roster source)" else "Fictional academy team (generated roster)", parentId = parent.id)
        w.teams.add(team)
        org.teams.add(team.id)
        league?.teamIds?.add(team.id)
        val ctx = GenCtx(w, rng)
        for (p in w.players) ctx.handles.add(p.handle.lowercase())
        val pop = Catalogue.POPULARITY[t.id]!!
        val regs = GameData.expand(parent.region)
        val mean = clamp(Lineups.strength(w, parent) - 13, 38.0, 68.0)
        val roles = t.lineup + t.lineup.take(2)
        for (roleId in roles) {
            val p = PlayerGen.generate(ctx, t.id, roleId, GameData.pickNationality(rng, regs, t.isMoba, pop), rng.int(16, 19), clamp(rng.normal(mean, 3.0), 30.0, 80.0), rng.normal(5.0, 3.0))
            join(w, p, team, "academy")
            p.contractEnd = w.season + rng.int(1, 2)
            p.salary = Economy.expectedSalary(p, region = team.region) * 0.6
            if (w.isUserTeam(team.id)) w.scouting[p.id] = 100
        }
        Staffing.fill(w, team, rng, news = false)
        team.lineup = Lineups.auto(w, team)
        val where = if (league != null) "It joins ${league.name} (Challengers) — fixtures start with the next split." else "Your region has no Challengers league, so it trains and scrims as a development squad."
        return Result(true, "${team.name} founded. $where", team)
    }

    /**
     * Bring an academy player up to the main team. `permanent`: promoted full-time (like [move]). Otherwise a
     * call-up for the main team's matches while `forPid` is out: they go back to the academy when that player
     * is available again (or when sent back); the contract stays the same either way.
     */
    fun callUp(w: World, p: Player, team: Team, forPid: Int, permanent: Boolean): Result {
        val academy = w.teams.getOrNull(p.teamId)?.takeIf { it.parentId == team.id } ?: return Result(false, "${p.handle} is not in ${team.name}'s academy.")
        if (p.loanFrom >= 0) return Result(false, "Loaned players cannot be called up.")
        academy.roster.remove(p.id)
        academy.lineup = academy.lineup.map { if (it == p.id) -1 else it }.toMutableList()
        academy.lineupSig = 0
        if (permanent) {
            join(w, p, team, "promoted")
            p.callUpFrom = -1; p.callUpFor = -1
        } else {
            p.teamId = team.id
            team.roster.add(p.id)
            p.callUpFrom = academy.id; p.callUpFor = forPid
            p.career.add(CareerEntry(w.season, w.day, team.id, "called up"))
        }
        team.lineupSig = 0
        val cover = w.players.getOrNull(forPid)?.handle
        return Result(true, if (permanent) "${p.handle} is promoted from ${academy.name} to ${team.name}."
            else "${p.handle} is called up from ${academy.name}${if (cover != null) " while $cover is out" else ""}.")
    }

    /** A called-up player goes back to the academy they came from. */
    fun sendBack(w: World, p: Player): Result {
        val academy = w.teams.getOrNull(p.callUpFrom)?.takeIf { it.active } ?: run { p.callUpFrom = -1; p.callUpFor = -1; return Result(false, "${p.handle} has no academy to go back to.") }
        val team = w.teams.getOrNull(p.teamId)
        if (team != null) {
            team.roster.remove(p.id)
            team.lineup = team.lineup.map { if (it == p.id) -1 else it }.toMutableList()
            team.lineupSig = 0
            if (team.lineup.any { it < 0 }) { team.manualLineup = false }
        }
        p.teamId = academy.id
        academy.roster.add(p.id)
        academy.lineupSig = 0
        p.callUpFrom = -1; p.callUpFor = -1
        p.career.add(CareerEntry(w.season, w.day, academy.id, "back to the academy"))
        return Result(true, "${p.handle} goes back to ${academy.name}.")
    }

    /** A called-up player stays on the main team full-time (a promotion). */
    fun keep(w: World, p: Player): Result {
        val academy = w.teams.getOrNull(p.callUpFrom) ?: return Result(false, "${p.handle} was not called up.")
        val team = w.teams.getOrNull(p.teamId) ?: return Result(false, "${p.handle} has no team.")
        w.transfers.add(dev.uem.em.model.Transfer(w.day, w.season, p.id, academy.id, team.id, 0.0, "promoted"))
        p.career.add(CareerEntry(w.season, w.day, team.id, "promoted"))
        p.callUpFrom = -1; p.callUpFor = -1
        return Result(true, "${p.handle} stays with ${team.name} full-time.")
    }

    /**
     * Each day, before the matches: called-up players go back once the player they cover is available again.
     * A call-up whose team or academy changed ends (the player stays where they are).
     */
    fun callUpsDaily(w: World) {
        for (p in w.players) {
            if (p.callUpFrom < 0) continue
            val academy = w.teams.getOrNull(p.callUpFrom)
            val team = w.teams.getOrNull(p.teamId)
            if (academy == null || !academy.active || team == null || academy.parentId != team.id || p.retired) { p.callUpFrom = -1; p.callUpFor = -1; continue }
            val cover = w.players.getOrNull(p.callUpFor) ?: continue
            when {
                // The covered player left: AI teams keep the call-up for good; yours stays called up until you decide.
                cover.teamId != team.id -> if (!w.isUserTeam(team.id)) keep(w, p) else {
                    p.callUpFor = -1
                    w.inbox.add(dev.uem.em.model.Message(w.day, "Head coach", "${p.handle} stays called up", "${cover.handle} has left ${team.name}, so ${p.handle} stays with the main team until you keep them full-time or send them back (their page)."))
                }
                Lineups.available(w, cover) -> {
                    val r = sendBack(w, p)
                    if (r.ok && w.isUserTeam(team.id)) w.inbox.add(dev.uem.em.model.Message(w.day, "Head coach", "${p.handle} back to the academy",
                        "${cover.handle} is available again, so ${p.handle} returns to ${academy.name}. Call them up again or promote them from their page."))
                }
            }
        }
    }

    /** Move a player between a main team and its academy (no fee, same contract). */
    fun move(w: World, p: Player): Result {
        // A called-up player moving down simply goes back.
        if (p.callUpFrom >= 0 && w.teams.getOrNull(p.teamId)?.let { isAcademy(it) } == false) return sendBack(w, p)
        val from = w.teams.getOrNull(p.teamId) ?: return Result(false, "Free agents cannot be moved.")
        val to = (if (isAcademy(from)) parent(w, from) else of(w, from)) ?: return Result(false, "${from.name} has no academy.")
        if (p.loanFrom >= 0) return Result(false, "Loaned players cannot be moved.")
        val t = Catalogue.get(from.titleId)
        if (!isAcademy(from) && from.roster.size <= t.teamSize) return Result(false, "${from.name} would not have enough players.")
        from.roster.remove(p.id)
        from.lineup = from.lineup.map { if (it == p.id) -1 else it }.toMutableList()
        join(w, p, to, if (isAcademy(to)) "demoted" else "promoted")
        from.lineup = Lineups.auto(w, from)
        to.lineup = Lineups.auto(w, to)
        from.manualLineup = false; to.manualLineup = false
        return Result(true, "${p.handle} ${if (isAcademy(to)) "moves down to" else "is promoted to"} ${to.name}.")
    }

    private fun join(w: World, p: Player, team: Team, kind: String) {
        if (p.teamId >= 0 && p.teamId != team.id) w.transfers.add(dev.uem.em.model.Transfer(w.day, w.season, p.id, p.teamId, team.id, 0.0, kind))
        p.teamId = team.id
        team.roster.add(p.id)
        p.career.add(CareerEntry(w.season, w.day, team.id, kind))
    }

    /** Players of an academy ready for the main team in a role (best first). */
    fun prospects(w: World, academy: Team, roleId: String?): List<Player> =
        academy.roster.map { w.players[it] }.filter { Lineups.available(w, it) && (roleId == null || it.roles[0] == roleId) }.sortedByDescending { it.ca }

    /** AI main teams promote a ready academy player into an uncovered position before signing anyone. */
    fun aiPromote(w: World, team: Team): Boolean {
        val academy = of(w, team) ?: return false
        val t = Catalogue.get(team.titleId)
        val have = team.roster.map { w.players[it].roles[0] }.toSet()
        val need = t.lineup.firstOrNull { it !in have } ?: return false
        val pick = prospects(w, academy, need).firstOrNull() ?: return false
        if (pick.ca < Lineups.strength(w, team) - 20) return false
        academy.roster.remove(pick.id)
        academy.lineup = academy.lineup.map { if (it == pick.id) -1 else it }.toMutableList()
        join(w, pick, team, "promoted")
        team.lineup = Lineups.auto(w, team)
        academy.lineup = Lineups.auto(w, academy)
        return true
    }

    /** Season start: some well-off AI organisations found academies where a Challengers league exists. */
    fun aiFound(w: World) {
        val rng = Rng.derive(w.seed, "ai-academies", w.season)
        for (team in w.teams.toList()) {
            if (!team.active || isAcademy(team) || team.nationalOf.isNotEmpty() || w.isUserTeam(team.id) || of(w, team) != null) continue
            val league = w.leagues.getOrNull(team.leagueId) ?: continue
            if (league.tier != 1 || leagueFor(w, team) == null) continue
            val org = w.orgs[team.orgId]
            if (org.balance < cost(w, team) * 8 || !rng.chance(0.12)) continue
            val r = found(w, team, true, rng)
            if (r.ok) w.news.add(dev.uem.em.model.News(w.day, "${org.name} found an academy", r.text, listOf(team.id)))
        }
    }

    /** Youth intake: academies sign their region's best prospects (the user's academy keeps two). */
    fun intake(w: World, made: List<Player>) {
        val byTitle = made.filter { it.teamId < 0 }.groupBy { it.titleId }
        for (academy in w.teams) {
            if (!academy.active || !isAcademy(academy)) continue
            val t = Catalogue.get(academy.titleId)
            if (academy.roster.size >= t.teamSize + 4) continue
            val regs = GameData.expand(academy.region).toSet()
            val org = w.orgs[academy.orgId]
            val n = if (org.facilities.getOrElse(2) { 3 } >= 6) 2 else 1
            val pick = byTitle[academy.titleId].orEmpty().filter { it.teamId < 0 && GameData.regionOf(it.nat) in regs }.sortedByDescending { it.pa }.take(n)
            for (p in pick) {
                join(w, p, academy, "youth")
                p.contractEnd = w.season + 2
                p.salary = Economy.expectedSalary(p, region = academy.region) * 0.6
                if (w.isUserTeam(academy.id)) w.scouting[p.id] = 100
            }
        }
    }
}

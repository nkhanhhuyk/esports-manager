package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.core.formatMoney
import dev.uem.em.model.CareerEntry
import dev.uem.em.model.Message
import dev.uem.em.model.News
import dev.uem.em.model.Offer
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import dev.uem.em.model.Transfer
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.titles.Catalogue
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Transfer market (ported in spirit from the web version's transfers and negotiations):
 * windows (off-season 23 Nov – 12 Jan, mid-season 25 May – 20 Jun), free-agent signings any time,
 * club bids for contracted players then personal terms, releases, renewals, AI clubs improving
 * their rosters and bidding for the user's players (answered from the inbox).
 */
object Market {
    /** The first season keeps every roster as the real snapshot had it: no window, no AI signings. */
    fun frozen(w: World) = w.firstSeason > 0 && w.season == w.firstSeason
    const val FROZEN_TEXT = "No transfers in the first season: every roster stays as it started (an emergency signing is allowed only when a team cannot field a lineup). The first window opens after the season."

    fun inWindow(w: World): Boolean {
        if (frozen(w)) return false
        val m = Dates.month(w.day)
        val d = Dates.dom(w.day)
        return (m == 11 && d >= 23) || m == 12 || (m == 1 && d <= 12) || (m == 5 && d >= 25) || (m == 6 && d <= 20)
    }

    fun windowLabel(w: World): String = if (frozen(w)) "No transfers in the first season" else if (inWindow(w)) "Transfer window open" else if (Dates.month(w.day) in 1..5) "Window opens 25 May" else "Window opens 23 Nov"

    /** Would this player start for the team (at his main position)? */
    fun wouldStart(w: World, team: Team, p: Player): Boolean {
        val t = Catalogue.get(team.titleId)
        val slot = t.lineup.indexOf(p.mainRole).takeIf { it >= 0 } ?: return false
        val cur = team.lineup.getOrNull(slot)?.takeIf { it >= 0 && it != p.id } ?: return true
        return p.ca >= w.players[cur].ca - 1
    }

    /**
     * Salary the player asks from this team: the market rate, more for a money-minded player, less from a
     * team that gives him what else he wants (winning, a starting place, prestige... see [Ambitions]).
     */
    fun asking(w: World, team: Team, p: Player): Double {
        val base = Economy.expectedSalary(p, team.titleId, team.region)
        // The club's general manager talks wages down (Staffing.wageFactor).
        return (base * Ambitions.askFactor(w, p, team) * Staffing.wageFactor(w, team) / 500).roundToInt() * 500.0
    }

    /** A player who joined a club recently is not sold on again (no flipping within the same months). */
    const val SETTLE_DAYS = 150
    fun settled(w: World, p: Player) = w.day - p.joinedDay >= SETTLE_DAYS || (p.listed && w.day - p.joinedDay >= 60)

    class Reply(val ok: Boolean, val text: String)

    /** Contract offer to a free agent or own player (renewal). Decided at once. */
    fun offerContract(w: World, playerId: Int, teamId: Int, salary: Double, years: Int): Reply {
        val p = w.players[playerId]
        val team = w.teams[teamId]
        val org = w.orgs[team.orgId]
        if (p.teamId >= 0 && p.teamId != teamId) return Reply(false, "${p.handle} is under contract with ${w.teams[p.teamId].name}: make a bid to their club first.")
        if (p.titleId != team.titleId) return Reply(false, "${p.handle} plays ${Catalogue.get(p.titleId).short}.")
        val renewal = p.teamId == teamId
        if (!renewal && team.roster.size >= Deals.maxRoster(team.titleId)) return Reply(false, "${team.name} already have ${team.roster.size} players (maximum ${Deals.maxRoster(team.titleId)}).")
        if (!renewal && frozen(w) && team.roster.count { !w.players[it].retired } >= Catalogue.get(team.titleId).teamSize) return Reply(false, FROZEN_TEXT)
        if (!renewal && !inWindow(w) && team.roster.size >= Catalogue.get(team.titleId).teamSize + 2) return Reply(false, "Outside a transfer window you can only sign free agents when your roster is short.")
        if (!renewal && org.balance < salary * 0.25) return Reply(false, "Not enough money for the signing.")
        val ask = asking(w, team, p) * (if (renewal) (1.0 + p.unhappy / 300) else 1.0)
        Ambitions.refusal(w, p, team, salary, renewal)?.let { return Reply(false, it) }
        val rng = Rng.derive(w.seed, "contract", playerId, teamId, w.day, salary.toInt())
        val ratio = salary / ask
        val accept = ratio >= 1.0 || (ratio >= 0.85 && rng.chance((ratio - 0.85) / 0.15 * 0.8))
        if (!accept) return Reply(false, "${p.handle} turns the offer down (asking about ${formatMoney(ask)} per season).")
        sign(w, p, team, salary, years, 0.0, if (renewal) "renewed" else "signed")
        return Reply(true, if (renewal) "${p.handle} extends until the end of ${p.contractEnd}." else "${p.handle} joins ${team.name} until the end of ${p.contractEnd}.")
    }

    /** Bid for a player under contract; the club answers the next day (inbox). */
    fun bid(w: World, playerId: Int, teamId: Int, fee: Double): Reply {
        val p = w.players[playerId]
        if (p.teamId < 0) return Reply(false, "${p.handle} is a free agent: offer a contract instead.")
        if (frozen(w)) return Reply(false, FROZEN_TEXT)
        if (!inWindow(w)) return Reply(false, "Bids are only possible while a transfer window is open.")
        if (w.orgs[w.teams[teamId].orgId].balance < fee) return Reply(false, "Not enough money for that fee.")
        w.offers.add(Offer(w.offers.size, w.day, playerId, teamId, p.teamId, "bid", fee, 0.0, 0, expiresDay = w.day + 1))
        return Reply(true, "Bid of ${formatMoney(fee)} sent to ${w.teams[p.teamId].name}. They will answer tomorrow.")
    }

    /** Personal terms after an accepted bid. */
    fun completeTransfer(w: World, offerId: Int, salary: Double, years: Int): Reply {
        val o = w.offers[offerId]
        if (o.status != "accepted") return Reply(false, "This deal is no longer open.")
        val p = w.players[o.playerId]
        val team = w.teams[o.fromTeam]
        val ask = asking(w, team, p)
        Ambitions.refusal(w, p, team, salary, false)?.let { return Reply(false, it) }
        if (salary < ask * 0.92) return Reply(false, "${p.handle} wants about ${formatMoney(ask)} per season.")
        transfer(w, p, team, o.fee, salary, years)
        o.status = "done"
        return Reply(true, "${p.handle} signs for ${team.name} (${formatMoney(o.fee)}).")
    }

    fun release(w: World, playerId: Int): Reply {
        val p = w.players[playerId]
        val team = w.teams.getOrNull(p.teamId) ?: return Reply(false, "Not on a team.")
        val remaining = max(0, p.contractEnd - w.season) + 0.5
        Economy.ledger(w, w.orgs[team.orgId], "release", -p.salary * remaining * 0.5, "Released ${p.handle}")
        leave(w, p, "released")
        return Reply(true, "${p.handle} is released (paid ${formatMoney(p.salary * remaining * 0.5)}).")
    }

    /** User answers an AI bid for one of his players. */
    fun answerBid(w: World, offerId: Int, accept: Boolean): Reply {
        val o = w.offers[offerId]
        if (o.status != "pending") return Reply(false, "This offer has expired.")
        val p = w.players[o.playerId]
        if (!accept) { o.status = "rejected"; return Reply(true, "Offer for ${p.handle} rejected.") }
        val buyer = w.teams[o.fromTeam]
        transfer(w, p, buyer, o.fee, Economy.expectedSalary(p, buyer.titleId, buyer.region), 2)
        o.status = "done"
        return Reply(true, "${p.handle} sold to ${buyer.name} for ${formatMoney(o.fee)}.")
    }

    // ------------------------------------------------------------------------ mechanics

    private fun leave(w: World, p: Player, type: String) {
        val team = w.teams.getOrNull(p.teamId) ?: return
        team.roster.remove(p.id)
        team.lineup.replaceAll { if (it == p.id) -1 else it }
        p.career.add(CareerEntry(w.season, w.day, team.id, type))
        // Departures to free agency are part of the transfer history (moves to clubs are recorded by the signing).
        if (type != "transferred") w.transfers.add(Transfer(w.day, w.season, p.id, team.id, -1, 0.0, type))
        p.teamId = -1
        p.salary = 0.0
        p.listed = false
    }

    private fun sign(w: World, p: Player, team: Team, salary: Double, years: Int, fee: Double, type: String) {
        if (p.teamId != team.id) {
            val fromTeam = p.teamId
            if (p.teamId >= 0) leave(w, p, "transferred")
            p.teamId = team.id
            dev.uem.em.mgmt.Imports.onJoin(w, p, team)
            team.roster.add(p.id)
            p.joinedDay = w.day
            p.career.add(CareerEntry(w.season, w.day, team.id, type))
            w.transfers.add(Transfer(w.day, w.season, p.id, fromTeam, team.id, fee, type))
        }
        p.salary = salary
        p.contractEnd = w.season + years - 1
        p.unhappy = 0.0
        p.wantsOutDay = 0
        p.listed = false
        Economy.ledger(w, w.orgs[team.orgId], "signing", -salary * 0.05, "Signing bonus: ${p.handle}")
        if (w.isUserTeam(team.id) || p.rep >= 60) w.news.add(News(w.day, "${p.handle} ${if (type == "renewed") "renews with" else "joins"} ${team.name}", "${p.handle} (${Catalogue.get(p.titleId).short}) ${if (type == "renewed") "extends" else "signs"} until the end of ${p.contractEnd}.", listOf(team.id)))
    }

    private fun transfer(w: World, p: Player, buyer: Team, fee: Double, salary: Double, years: Int) {
        val seller = w.teams.getOrNull(p.teamId)
        if (seller != null) {
            Economy.ledger(w, w.orgs[seller.orgId], "transfer-in", fee, "Sold ${p.handle} to ${buyer.name}")
            leave(w, p, "transferred")
        }
        Economy.ledger(w, w.orgs[buyer.orgId], "transfer-out", -fee, "Bought ${p.handle}${seller?.let { " from ${it.name}" } ?: ""}")
        p.teamId = buyer.id
        dev.uem.em.mgmt.Imports.onJoin(w, p, buyer)
        buyer.roster.add(p.id)
        p.joinedDay = w.day
        p.salary = salary
        p.contractEnd = w.season + years - 1
        p.unhappy = 0.0
        p.wantsOutDay = 0
        p.listed = false
        p.career.add(CareerEntry(w.season, w.day, buyer.id, "transferred"))
        w.transfers.add(Transfer(w.day, w.season, p.id, seller?.id ?: -1, buyer.id, fee, "transfer"))
        w.news.add(News(w.day, "${p.handle} moves to ${buyer.name}", "${buyer.name} sign ${p.handle}${seller?.let { " from ${it.name}" } ?: ""} for ${formatMoney(fee)}.", listOfNotNull(buyer.id, seller?.id)))
        if (seller != null && w.isUserTeam(seller.id)) seller.manualLineup = false
    }

    /** Daily: answers to the user's bids, AI clubs' market activity, expiring offers. */
    /** Positions nobody on the roster plays as his main role (filled before anything else). */
    fun missingRoles(w: World, team: Team): List<String> {
        val t = Catalogue.get(team.titleId)
        val mains = team.roster.map { w.players[it] }.filter { !it.retired }.map { it.mainRole }
        return t.lineup.filter { it !in mains }
    }

    /** Best available free agent for a position: main role first, then players who list it second. */
    fun bestForRole(w: World, titleId: String, role: String, exclude: Set<Int> = emptySet()): Player? {
        val pool = w.players.asSequence().filter { it.teamId < 0 && !it.retired && it.titleId == titleId && it.id !in exclude }
        return pool.filter { it.mainRole == role }.maxByOrNull { it.ca } ?: pool.filter { it.roles.getOrNull(1) == role }.maxByOrNull { it.ca }
    }

    fun daily(w: World) {
        val rng = Rng.derive(w.seed, "market", w.day)
        for (o in w.offers) {
            if (o.status != "pending") continue
            if (o.kind == "bid" && w.isUserTeam(o.fromTeam) && w.day >= o.expiresDay) {
                // AI club answers the user's bid.
                val p = w.players[o.playerId]
                val value = Economy.marketValue(w, p)
                val seller = w.teams.getOrNull(p.teamId)
                if (seller == null || seller.id != o.toTeam) { o.status = "withdrawn"; continue }
                val key = p.id in seller.lineup && Lineups.strength(w, seller) > 0
                val need = value * (if (key) 1.25 else 0.95) * (if (p.listed) 0.8 else 1.0) * Staffing.feeFactor(w, seller, w.teams.getOrNull(o.fromTeam))
                if (o.fee >= need || (o.fee >= need * 0.9 && rng.chance(0.4))) {
                    o.status = "accepted"
                    w.inbox.add(Message(w.day, seller.name, "Bid accepted: ${p.handle}", "${seller.name} accept ${formatMoney(o.fee)} for ${p.handle}. Agree personal terms from the player's page.", offerId = o.id))
                } else {
                    o.status = "rejected"
                    w.inbox.add(Message(w.day, seller.name, "Bid rejected: ${p.handle}", "${seller.name} turn down ${formatMoney(o.fee)} for ${p.handle}. They value him at about ${formatMoney(need)}."))
                }
            } else if (o.expiresDay < w.day && o.status == "pending") o.status = "withdrawn"
        }
        if (w.offers.size > 3000) w.offers.removeAll { it.status != "pending" && it.status != "accepted" && it.day < w.day - 120 }
        val window = inWindow(w)
        for (team in w.teams) {
            // Development clubs (amateur, university, Game Changers) do not sign contracts: their rosters refill from unsigned players.
            if (!team.active || w.isUserTeam(team.id) || team.nationalOf.isNotEmpty() || team.kind.isNotEmpty()) continue
            val t = Catalogue.get(team.titleId)
            // Academies (second teams) have no transfer window: they replace a player who moved up or left at
            // any time, position for position, and may sign a better young free agent whenever they like.
            val academy = Academies.isAcademy(team)
            val missing = missingRoles(w, team).isNotEmpty()
            // First season: only a team that cannot field a lineup signs (an emergency); an academy also fills a position left empty.
            if (frozen(w) && !(academy && missing) && team.roster.count { !w.players[it].retired && w.players[it].injuryUntil <= w.day } >= t.teamSize) continue
            val short = team.roster.count { !w.players[it].retired } < t.teamSize + 1
            val browse = if (window) Ambitions.marketChance(w, team) else if (academy && !frozen(w)) Ambitions.marketChance(w, team) * 0.25 else 0.0
            if (!short && !missing && !rng.chance(browse)) continue
            if (missing && Academies.aiPromote(w, team)) continue
            aiTeamMarket(w, team, rng, short || missing)
        }
        // The user's coach covers an unplayable roster with emergency signings.
        for (team in w.teams) {
            if (!team.active || !w.isUserTeam(team.id)) continue
            val t = Catalogue.get(team.titleId)
            // Your academy: a position left empty (a player called up or signed away) is filled by the academy staff, from young free agents first.
            if (Academies.isAcademy(team)) for (need in missingRoles(w, team)) {
                val young = w.players.asSequence().filter { it.teamId < 0 && !it.retired && it.titleId == team.titleId && it.mainRole == need && Dates.ageAt(it.birth, w.day) <= 21 }.maxByOrNull { it.ca }
                val p = young ?: bestForRole(w, team.titleId, need) ?: continue
                sign(w, p, team, asking(w, team, p), 1, 0.0, "signed")
                w.inbox.add(Message(w.day, "Academy staff", "Academy signing: ${p.handle}", "${team.name} had no ${t.role(need).name}, so the academy staff signed ${p.handle} (${Dates.ageAt(p.birth, w.day)}) on a one-season deal."))
            }
            while (team.roster.count { !w.players[it].retired } < t.teamSize) {
                val need = missingRoles(w, team).firstOrNull()
                if (need != null && Academies.aiPromote(w, team)) {
                    w.inbox.add(Message(w.day, "Coaching staff", "Academy call-up", "${team.name} were missing a ${t.role(need).name}, so the coaching staff promoted one from the academy."))
                    continue
                }
                val p = (need?.let { bestForRole(w, team.titleId, it) }) ?: w.players.asSequence().filter { it.teamId < 0 && !it.retired && it.titleId == team.titleId }.maxByOrNull { it.ca } ?: break
                sign(w, p, team, asking(w, team, p), 1, 0.0, "signed")
                w.inbox.add(Message(w.day, "Coaching staff", "Emergency signing: ${p.handle}", "${team.name} could not field a full lineup, so the coaching staff signed ${p.handle} on a one-season deal."))
            }
        }
        if (window && w.userOrg >= 0 && rng.chance(0.06)) aiBidForUserPlayer(w, rng)
    }

    fun aiTeamMarket(w: World, team: Team, rng: Rng, short: Boolean) {
        val t = Catalogue.get(team.titleId)
        val org = w.orgs[team.orgId]
        val lineup = team.lineup.ifEmpty { Lineups.auto(w, team) }
        // Players are judged on ability and recent seasons (form, games, the level they played at): see Extras.selectionScore.
        val judged = HashMap<Int, Double>()
        fun judge(p: dev.uem.em.model.Player) = judged.getOrPut(p.id) { Extras.selectionScore(w, p) }
        // Weakest position (a starter out of position counts as a hole there).
        var weakSlot = -1
        var weakCa = 999.0
        t.lineup.forEachIndexed { i, r ->
            val pid = lineup.getOrNull(i) ?: -1
            val pl = w.players.getOrNull(pid)
            val ca = if (pl != null && (pl.mainRole == r || pl.roles.getOrNull(1) == r)) judge(pl) else 0.0
            if (ca < weakCa) { weakCa = ca; weakSlot = i }
        }
        // A position nobody plays as his main role comes first; otherwise the weakest starter's.
        val missing = missingRoles(w, team)
        val role = missing.firstOrNull() ?: if (weakSlot >= 0) t.lineup[weakSlot] else rng.pick(t.lineup)
        if (missing.isNotEmpty()) weakCa = 0.0
        val budget = org.balance * Ambitions.budgetShare(w, team)
        // Free agents first.
        val academy = Academies.isAcademy(team)
        val fa = w.players.asSequence().filter { it.teamId < 0 && !it.retired && it.titleId == team.titleId && it.mainRole == role && (!academy || dev.uem.em.core.Dates.ageAt(it.birth, w.day) <= 21) }
            .filter { judge(it) > weakCa + (if (short) -15.0 else 3.5) }.sortedByDescending { judge(it) }.take(5).toList()
        for (p in fa) {
            val sal = asking(w, team, p)
            if (sal * 1.2 > budget && !short) continue
            // A free agent waits for a team that suits him (less and less choosy the longer he is unsigned).
            if (!short && !Ambitions.acceptsOffer(w, p, team, sal)) continue
            sign(w, p, team, sal, rng.int(1, 3), 0.0, "signed")
            if (team.roster.size > t.teamSize + 3) releaseWorst(w, team)
            return
        }
        if (short) {
            // A player of the needed position (main role, else second role); only then anyone.
            val any = bestForRole(w, team.titleId, role) ?: w.players.asSequence().filter { it.teamId < 0 && !it.retired && it.titleId == team.titleId }.maxByOrNull { it.ca } ?: return
            sign(w, any, team, asking(w, team, any), 1, 0.0, "signed")
            if (team.roster.size > t.teamSize + 3) releaseWorst(w, team)
            return
        }
        if (!inWindow(w) || academy) return
        // Bid for a better player at a weaker club (never a club of the same organisation).
        val myElo = team.elo
        // A club that keeps winning its league no longer buys other clubs' players (they will not sell to it unless
        // a player is listed; it still signs free agents and promotes from its academy); other clubs, at home and
        // abroad, may tempt its players who want money, a new challenge or more playing time.
        val dom = HashMap<Int, Int>()
        fun dominance(tm: Team) = dom.getOrPut(tm.id) { leagueDominance(w, tm) }
        val dominant = dominance(team) >= 2
        fun tempted(p: Player) = Ambitions.weight(w, p, "money") >= 20 || Ambitions.weight(w, p, "growth") >= 15 || Ambitions.weight(w, p, "playtime") >= 15
        // Second divisions and academies included (their best players move up); a listed player even from a
        // stronger club; nobody who joined his club only recently.
        val targets = w.players.asSequence().filter { p -> p.teamId >= 0 && p.loanFrom < 0 && w.teams[p.teamId].orgId != team.orgId && w.teams[p.teamId].kind.isEmpty() && w.teams[p.teamId].nationalOf.isEmpty() &&
                !(w.isUserTeam(team.id) && w.isUserTeam(p.teamId)) && p.titleId == team.titleId && p.mainRole == role && settled(w, p) &&
                !(dominant && !p.listed) &&
                (w.teams[p.teamId].elo < myElo + 40 || p.listed || (tempted(p) && dominance(w.teams[p.teamId]) >= 2)) }
            .filter { judge(it) > weakCa + 4.5 }.sortedByDescending { Ambitions.targetScore(w, team, it) + (judge(it) - it.ca) }.take(4).toList()
        for (p in targets) {
            val value = Economy.marketValue(w, p)
            if (value > budget) continue
            val seller = w.teams[p.teamId]
            val salary = asking(w, team, p)
            // He must want the move: the new team suits him, and better than his club unless he asked to leave.
            if (Ambitions.refusal(w, p, team, salary, false) != null) continue
            if (p.wantsOutDay == 0 && !p.listed && Ambitions.fit(w, p, team, salary).total < (Ambitions.mood(w, p)?.total ?: 0.0) - 3) continue
            val fee = (value * rng.float(0.95, 1.2) / 1000).roundToInt() * 1000.0
            if (w.isUserTeam(seller.id)) {
                w.offers.add(Offer(w.offers.size, w.day, p.id, team.id, seller.id, "bid", fee, 0.0, 2, expiresDay = w.day + 5))
                w.inbox.add(Message(w.day, team.name, "Offer for ${p.handle}", "${team.name} offer ${formatMoney(fee)} for ${p.handle} (value about ${formatMoney(value)}). Answer within 5 days.", offerId = w.offers.size - 1))
                return
            }
            val starter = p.id in seller.lineup
            val keen = (if (p.listed) 0.8 else 1.0) * (if (Ambitions.rebuilding(w, seller)) 0.85 else 1.0) * (if (Ambitions.strategy(w, seller) == "moneyball") 0.9 else 1.0)
            if (fee >= value * (if (starter) 1.15 else 0.9) * keen || rng.chance(if (p.listed) 0.35 else 0.12)) {
                transfer(w, p, team, fee, salary, if (Ambitions.weight(w, p, "loyalty") >= 25) rng.int(2, 3) else rng.int(1, 2))
                if (team.roster.size > t.teamSize + 3) releaseWorst(w, team)
                return
            }
        }
    }

    /** League titles a club won in its league's last three completed splits. */
    fun leagueDominance(w: World, team: Team): Int {
        if (team.leagueId < 0) return 0
        var n = 0; var seen = 0
        for (c in w.comps.asReversed()) {
            if (c.leagueId != team.leagueId || !c.done || c.placements.isEmpty()) continue
            if (c.placements.first() == team.id) n++
            if (++seen == 3) break
        }
        return n
    }

    private fun releaseWorst(w: World, team: Team) {
        // Never the only player of a position.
        val mains = team.roster.map { w.players[it].mainRole }
        val worst = team.roster.map { w.players[it] }.filter { it.id !in team.lineup && it.loanFrom < 0 && mains.count { r -> r == it.mainRole } > 1 }.minByOrNull { it.ca } ?: return
        leave(w, worst, "released")
    }

    private fun aiBidForUserPlayer(w: World, rng: Rng) {
        val userTeams = w.teams.filter { it.active && w.isUserTeam(it.id) }
        if (userTeams.isEmpty()) return
        val mine = userTeams.flatMap { t -> t.roster.map { w.players[it] } }.filter { it.ca >= 60 && it.loanFrom < 0 }
        if (mine.isEmpty()) return
        val p = rng.weighted(mine) { it.ca - 50 + (if (it.listed) 30.0 else 0.0) + (if (it.wantsOutDay > 0) 25.0 else 0.0) }
        val buyers = w.teams.filter { it.active && !w.isUserTeam(it.id) && it.titleId == p.titleId && w.orgs[it.orgId].balance > Economy.marketValue(w, p) }
        if (buyers.isEmpty()) return
        val buyer = rng.pick(buyers)
        val value = Economy.marketValue(w, p)
        val fee = (value * rng.float(0.8, 1.3) / 1000).roundToInt() * 1000.0
        val seller = w.teams[p.teamId]
        w.offers.add(Offer(w.offers.size, w.day, p.id, buyer.id, seller.id, "bid", fee, 0.0, 2, expiresDay = w.day + 5))
        w.inbox.add(Message(w.day, buyer.name, "Offer for ${p.handle}", "${buyer.name} offer ${formatMoney(fee)} for ${p.handle} (value about ${formatMoney(value)}). Answer within 5 days.", offerId = w.offers.size - 1))
    }

    /** End of season: expiring contracts are renewed or players leave; AI clubs refill. */
    fun seasonEnd(w: World, rng: Rng) {
        for (p in w.players) {
            if (p.retired || p.teamId < 0 || p.contractEnd >= w.season) continue
            val team = w.teams[p.teamId]
            if (w.isUserTeam(team.id)) {
                // The user's expiring players leave unless renewed (inbox warned them in September).
                leave(w, p, "contract ended")
                continue
            }
            // The club must want him and he must want to stay (ambitions); loyal players sign longer deals.
            val years = Ambitions.renewal(w, p, team, rng)
            if (years > 0) {
                p.contractEnd = w.season + years - 1
                p.career.add(CareerEntry(w.season, w.day, team.id, "renewed"))
            } else leave(w, p, "contract ended")
        }
        // Staff contracts are handled with the rest of the staff year (Staffing.seasonEnd).
    }

    /** September: warn the user about expiring contracts. */
    fun contractReminders(w: World) {
        for (team in w.teams) if (team.active && w.isUserTeam(team.id)) for (pid in team.roster) {
            val p = w.players[pid]
            if (p.contractEnd == w.season) w.inbox.add(Message(w.day, "Contracts", "${p.handle}'s contract ends this season", "Offer ${p.handle} an extension from his page, or he leaves as a free agent when the season ends."))
        }
    }
}

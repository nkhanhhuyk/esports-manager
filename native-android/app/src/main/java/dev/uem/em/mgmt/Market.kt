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
    fun inWindow(w: World): Boolean {
        val m = Dates.month(w.day)
        val d = Dates.dom(w.day)
        return (m == 11 && d >= 23) || m == 12 || (m == 1 && d <= 12) || (m == 5 && d >= 25) || (m == 6 && d <= 20)
    }

    fun windowLabel(w: World): String = if (inWindow(w)) "Transfer window open" else if (Dates.month(w.day) in 1..5) "Window opens 25 May" else "Window opens 23 Nov"

    private fun teamRep(w: World, team: Team) = w.orgs[team.orgId].rep

    /** Would this player start for the team (at his main position)? */
    fun wouldStart(w: World, team: Team, p: Player): Boolean {
        val t = Catalogue.get(team.titleId)
        val slot = t.lineup.indexOf(p.mainRole).takeIf { it >= 0 } ?: return false
        val cur = team.lineup.getOrNull(slot)?.takeIf { it >= 0 && it != p.id } ?: return true
        return p.ca >= w.players[cur].ca - 1
    }

    /** Salary the player asks from this team (higher from small clubs, lower for a starting place at a big one). */
    fun asking(w: World, team: Team, p: Player): Double {
        val base = Economy.expectedSalary(p, team.titleId, team.region)
        val repGap = (p.rep - teamRep(w, team)) / 100
        val playing = if (wouldStart(w, team, p)) 0.95 else 1.15
        return (base * (1 + repGap * 0.4) * playing / 500).roundToInt() * 500.0
    }

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
        if (!renewal && !inWindow(w) && team.roster.size >= Catalogue.get(team.titleId).teamSize + 2) return Reply(false, "Outside a transfer window you can only sign free agents when your roster is short.")
        if (!renewal && org.balance < salary * 0.25) return Reply(false, "Not enough money for the signing.")
        val ask = asking(w, team, p) * (if (renewal) (1.0 + p.unhappy / 300) else 1.0)
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
        p.teamId = -1
        p.salary = 0.0
        p.listed = false
    }

    private fun sign(w: World, p: Player, team: Team, salary: Double, years: Int, fee: Double, type: String) {
        if (p.teamId != team.id) {
            if (p.teamId >= 0) leave(w, p, "transferred")
            p.teamId = team.id
            team.roster.add(p.id)
            p.joinedDay = w.day
            p.career.add(CareerEntry(w.season, w.day, team.id, type))
            w.transfers.add(Transfer(w.day, w.season, p.id, -1, team.id, fee, type))
        }
        p.salary = salary
        p.contractEnd = w.season + years - 1
        p.unhappy = 0.0
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
        buyer.roster.add(p.id)
        p.joinedDay = w.day
        p.salary = salary
        p.contractEnd = w.season + years - 1
        p.unhappy = 0.0
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
                val need = value * (if (key) 1.25 else 0.95) * (if (p.listed) 0.8 else 1.0)
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
            if (!team.active || w.isUserTeam(team.id) || team.nationalOf.isNotEmpty()) continue
            val t = Catalogue.get(team.titleId)
            val short = team.roster.count { !w.players[it].retired } < t.teamSize + 1
            val missing = missingRoles(w, team).isNotEmpty()
            if (!short && !missing && !(window && rng.chance(1.0 / 40))) continue
            aiTeamMarket(w, team, rng, short || missing)
        }
        // The user's coach covers an unplayable roster with emergency signings.
        for (team in w.teams) {
            if (!team.active || !w.isUserTeam(team.id)) continue
            val t = Catalogue.get(team.titleId)
            while (team.roster.count { !w.players[it].retired } < t.teamSize) {
                val need = missingRoles(w, team).firstOrNull()
                val p = (need?.let { bestForRole(w, team.titleId, it) }) ?: w.players.asSequence().filter { it.teamId < 0 && !it.retired && it.titleId == team.titleId }.maxByOrNull { it.ca } ?: break
                sign(w, p, team, asking(w, team, p), 1, 0.0, "signed")
                w.inbox.add(Message(w.day, "Coaching staff", "Emergency signing: ${p.handle}", "${team.name} could not field a full lineup, so the coaching staff signed ${p.handle} on a one-season deal."))
            }
        }
        if (window && w.userOrg >= 0 && rng.chance(0.06)) aiBidForUserPlayer(w, rng)
    }

    private fun aiTeamMarket(w: World, team: Team, rng: Rng, short: Boolean) {
        val t = Catalogue.get(team.titleId)
        val org = w.orgs[team.orgId]
        val lineup = team.lineup.ifEmpty { Lineups.auto(w, team) }
        // Weakest position.
        var weakSlot = -1
        var weakCa = 999.0
        t.lineup.forEachIndexed { i, _ ->
            val pid = lineup.getOrNull(i) ?: -1
            val ca = if (pid >= 0) w.players[pid].ca else 0.0
            if (ca < weakCa) { weakCa = ca; weakSlot = i }
        }
        // A position nobody plays as his main role comes first; otherwise the weakest starter's.
        val missing = missingRoles(w, team)
        val role = missing.firstOrNull() ?: if (weakSlot >= 0) t.lineup[weakSlot] else rng.pick(t.lineup)
        if (missing.isNotEmpty()) weakCa = 0.0
        val budget = org.balance * 0.35
        // Free agents first.
        val fa = w.players.asSequence().filter { it.teamId < 0 && !it.retired && it.titleId == team.titleId && it.mainRole == role && it.ca > weakCa + (if (short) -15.0 else 3.5) }
            .sortedByDescending { it.ca }.take(5).toList()
        for (p in fa) {
            val sal = asking(w, team, p)
            if (sal * 1.2 > budget && !short) continue
            if (p.rep > org.rep + 25 && rng.chance(0.6)) continue
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
        if (!inWindow(w)) return
        // Bid for a better player at a weaker club.
        val myElo = team.elo
        val targets = w.players.asSequence().filter { p -> p.teamId >= 0 && p.loanFrom < 0 && p.teamId != team.id && p.titleId == team.titleId && p.mainRole == role && p.ca > weakCa + 4.5 && w.teams[p.teamId].elo < myElo + 40 }
            .sortedByDescending { it.ca }.take(4).toList()
        for (p in targets) {
            val value = Economy.marketValue(w, p)
            if (value > budget) continue
            val seller = w.teams[p.teamId]
            val fee = (value * rng.float(0.95, 1.2) / 1000).roundToInt() * 1000.0
            if (w.isUserTeam(seller.id)) {
                w.offers.add(Offer(w.offers.size, w.day, p.id, team.id, seller.id, "bid", fee, 0.0, 2, expiresDay = w.day + 5))
                w.inbox.add(Message(w.day, team.name, "Offer for ${p.handle}", "${team.name} offer ${formatMoney(fee)} for ${p.handle} (value about ${formatMoney(value)}). Answer within 5 days.", offerId = w.offers.size - 1))
                return
            }
            val starter = p.id in seller.lineup
            if (fee >= value * (if (starter) 1.15 else 0.9) || rng.chance(0.15)) {
                transfer(w, p, team, fee, asking(w, team, p), rng.int(1, 3))
                if (team.roster.size > t.teamSize + 3) releaseWorst(w, team)
                return
            }
        }
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
        val p = rng.weighted(mine) { it.ca - 50 + (if (it.listed) 30.0 else 0.0) }
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
            val age = Dates.ageAt(p.birth, w.day)
            if (rng.chance(if (p.ca >= 60) 0.75 else 0.5) && age < 31) p.contractEnd = w.season + rng.int(0, 2)
            else leave(w, p, "contract ended")
        }
        for (s in w.staff) if (s.teamId >= 0 && s.contractEnd < w.season) {
            val team = w.teams[s.teamId]
            if (w.isUserTeam(team.id) || rng.chance(0.3)) { team.staffIds.remove(s.id); s.teamId = -1; s.orgId = -1 } else s.contractEnd = w.season + rng.int(0, 2)
        }
        // AI clubs without a head coach or analyst hire from the pool.
        for (team in w.teams) {
            if (!team.active || w.isUserTeam(team.id)) continue
            for (role in listOf("head-coach", "analyst")) {
                if (Economy.staffOf(w, team, role).isNotEmpty()) continue
                val cand = w.staff.filter { it.teamId < 0 && !it.retired && it.role == role }.maxByOrNull { it.quality + if (it.titleId == team.titleId) 5 else 0 } ?: continue
                Economy.hire(w, cand, team, rng.int(1, 3))
            }
        }
    }

    /** September: warn the user about expiring contracts. */
    fun contractReminders(w: World) {
        for (team in w.teams) if (team.active && w.isUserTeam(team.id)) for (pid in team.roster) {
            val p = w.players[pid]
            if (p.contractEnd == w.season) w.inbox.add(Message(w.day, "Contracts", "${p.handle}'s contract ends this season", "Offer ${p.handle} an extension from his page, or he leaves as a free agent when the season ends."))
        }
    }
}

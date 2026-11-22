package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.core.formatMoney
import dev.uem.em.model.CareerEntry
import dev.uem.em.model.Fixture
import dev.uem.em.model.Message
import dev.uem.em.model.News
import dev.uem.em.model.Transfer
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import kotlin.math.abs
import kotlin.math.max

/**
 * More deals and team life (ported in spirit from the web version): loans, player-for-player trades,
 * pre-contracts, roster limits, delegated decisions, fans and rivalries, weekly scrims.
 */
object Deals {
    fun maxRoster(titleId: String) = Catalogue.get(titleId).teamSize + 5

    // ------------------------------------------------------------------ loans

    /** Loan a young bench player to a club in a lower league (or without a league place) until season end. */
    fun loanOut(w: World, playerId: Int): Market.Reply {
        val p = w.players[playerId]
        val from = w.teams.getOrNull(p.teamId) ?: return Market.Reply(false, "Not on a team.")
        if (dev.uem.em.core.Dates.ageAt(p.birth, w.day) > 22) return Market.Reply(false, "Only players aged 22 or younger can be loaned out.")
        if (p.id in from.lineup && !from.manualLineup && from.lineup.count { it >= 0 } <= Catalogue.get(from.titleId).teamSize) return Market.Reply(false, "${p.handle} is a starter.")
        val league = w.leagues.getOrNull(from.leagueId)
        val dest = w.teams.filter { it.active && it.titleId == p.titleId && it.id != from.id && !w.isUserTeam(it.id) && it.nationalOf.isEmpty() && it.roster.size < maxRoster(it.titleId) && (league == null || it.leagueId != from.leagueId) }
            .minByOrNull { abs(it.elo - from.elo + 150) } ?: return Market.Reply(false, "No club can take him on loan.")
        from.roster.remove(p.id)
        from.lineup.replaceAll { if (it == p.id) -1 else it }
        p.loanFrom = from.id
        p.loanUntil = w.season
        p.teamId = dest.id
        dev.uem.em.mgmt.Imports.onJoin(w, p, dest)
        dest.roster.add(p.id)
        p.career.add(CareerEntry(w.season, w.day, dest.id, "loan"))
        w.transfers.add(Transfer(w.day, w.season, p.id, from.id, dest.id, 0.0, "loan"))
        return Market.Reply(true, "${p.handle} joins ${dest.name} on loan until the end of ${w.season} (wages split 50/50).")
    }

    /** Season end: loaned players return home. */
    fun returnLoans(w: World) {
        for (p in w.players) {
            if (p.loanFrom < 0 || p.loanUntil >= w.season) continue
            val home = w.teams[p.loanFrom]
            w.teams.getOrNull(p.teamId)?.let { it.roster.remove(p.id); it.lineup.replaceAll { x -> if (x == p.id) -1 else x } }
            val loanTeam = p.teamId
            p.teamId = home.id
            home.roster.add(p.id)
            p.career.add(CareerEntry(w.season, w.day, home.id, "loan return"))
            w.transfers.add(dev.uem.em.model.Transfer(w.day, w.season, p.id, loanTeam, home.id, 0.0, "loan return"))
            if (w.isUserTeam(home.id)) w.inbox.add(Message(w.day, "Loans", "${p.handle} returns from loan", "${p.handle} is back (CA %.0f, potential %.0f).".format(p.ca, p.pa)))
            p.loanFrom = -1
        }
    }

    // ------------------------------------------------------------------ trades

    /** Swap one of your players (plus cash either way) for another club's player. Decided at once. */
    fun trade(w: World, myPlayer: Int, theirPlayer: Int, cash: Double): Market.Reply {
        val mine = w.players[myPlayer]
        val theirs = w.players[theirPlayer]
        val myTeam = w.teams.getOrNull(mine.teamId) ?: return Market.Reply(false, "Not on your team.")
        val other = w.teams.getOrNull(theirs.teamId) ?: return Market.Reply(false, "${theirs.handle} is a free agent.")
        if (Market.frozen(w)) return Market.Reply(false, Market.FROZEN_TEXT)
        if (!Market.inWindow(w)) return Market.Reply(false, "Trades are only possible while a transfer window is open.")
        if (mine.titleId != theirs.titleId) return Market.Reply(false, "Both players must play the same game.")
        if (cash > 0 && w.orgs[myTeam.orgId].balance < cash) return Market.Reply(false, "Not enough money.")
        val give = Economy.marketValue(w, mine) + cash
        val get = Economy.marketValue(w, theirs) * (if (theirs.id in other.lineup) 1.1 else 0.95)
        val needs = other.roster.count { w.players[it].mainRole == mine.mainRole } <= 1 || mine.ca >= theirs.ca - 2
        if (give < get || !needs) return Market.Reply(false, "${other.name} decline: they value ${theirs.handle} at about ${formatMoney(get)} and want a fair exchange.")
        // Swap.
        myTeam.roster.remove(mine.id); other.roster.remove(theirs.id)
        myTeam.lineup.replaceAll { if (it == mine.id) -1 else it }; other.lineup.replaceAll { if (it == theirs.id) -1 else it }
        mine.teamId = other.id; theirs.teamId = myTeam.id
        dev.uem.em.mgmt.Imports.onJoin(w, mine, other); dev.uem.em.mgmt.Imports.onJoin(w, theirs, myTeam)
        other.roster.add(mine.id); myTeam.roster.add(theirs.id)
        mine.joinedDay = w.day; theirs.joinedDay = w.day
        mine.career.add(CareerEntry(w.season, w.day, other.id, "traded")); theirs.career.add(CareerEntry(w.season, w.day, myTeam.id, "traded"))
        if (cash != 0.0) { Economy.ledger(w, w.orgs[myTeam.orgId], "trade", -cash, "Trade: ${mine.handle} for ${theirs.handle}"); Economy.ledger(w, w.orgs[other.orgId], "trade", cash, "Trade: ${theirs.handle} for ${mine.handle}") }
        w.transfers.add(Transfer(w.day, w.season, mine.id, myTeam.id, other.id, cash, "trade"))
        w.transfers.add(Transfer(w.day, w.season, theirs.id, other.id, myTeam.id, 0.0, "trade"))
        w.news.add(News(w.day, "Trade: ${mine.handle} for ${theirs.handle}", "${myTeam.name} and ${other.name} swap ${mine.handle} and ${theirs.handle}${if (cash > 0) " (+${formatMoney(cash)})" else ""}."))
        myTeam.manualLineup = false
        return Market.Reply(true, "Trade done: ${theirs.handle} joins ${myTeam.name}.")
    }

    // ------------------------------------------------------------------ pre-contracts

    /** A player in his last contract year agrees to join next season (from September). */
    fun preContract(w: World, playerId: Int, teamId: Int, salary: Double): Market.Reply {
        val p = w.players[playerId]
        val team = w.teams[teamId]
        if (p.teamId < 0) return Market.Reply(false, "${p.handle} is a free agent: offer a contract instead.")
        if (p.contractEnd != w.season || Dates.month(w.day) < 9) return Market.Reply(false, "Pre-contracts are possible from September with players whose contract ends this season.")
        val ask = Market.asking(w, team, p) * 1.05
        if (salary < ask) return Market.Reply(false, "${p.handle} wants about ${formatMoney(ask)} to commit now.")
        p.preContractTeam = teamId
        p.preContractSalary = salary
        return Market.Reply(true, "${p.handle} agrees to join ${team.name} when the season ends.")
    }

    fun executePreContracts(w: World) {
        for (p in w.players) {
            if (p.preContractTeam < 0) continue
            val team = w.teams[p.preContractTeam]
            w.teams.getOrNull(p.teamId)?.let { it.roster.remove(p.id); it.lineup.replaceAll { x -> if (x == p.id) -1 else x } }
            p.teamId = team.id
            dev.uem.em.mgmt.Imports.onJoin(w, p, team)
            team.roster.add(p.id)
            p.salary = p.preContractSalary
            p.contractEnd = w.season + 1
            p.joinedDay = w.day
            p.career.add(CareerEntry(w.season, w.day, team.id, "pre-contract"))
            w.transfers.add(Transfer(w.day, w.season, p.id, -1, team.id, 0.0, "pre-contract"))
            w.news.add(News(w.day, "${p.handle} joins ${team.name}", "${p.handle} arrives on a pre-contract."))
            p.preContractTeam = -1
        }
    }

    // ------------------------------------------------------------------ delegation

    /** Staff decisions the user delegated: answering bids and renewing key players. */
    // ------------------------------------------------------------------ fans and rivalries

    private fun key(a: Int, b: Int) = if (a < b) "$a|$b" else "$b|$a"
    fun rivalry(w: World, a: Int, b: Int) = w.rivalries[key(a, b)] ?: 0.0

    /** After a series: rivalries grow with close and important meetings; fans follow results. */
    fun afterSeries(w: World, f: Fixture) {
        val close = abs(f.scoreA - f.scoreB) <= 1 && f.bestOf > 1
        val playoff = f.nodeId.isNotEmpty()
        val k = key(f.a, f.b)
        w.rivalries[k] = clamp((w.rivalries[k] ?: 0.0) + 2 + (if (close) 3 else 0) + (if (playoff) 5 else 0), 0.0, 100.0)
        val winOrg = w.orgs[w.teams[f.winner].orgId]
        val loseOrg = w.orgs[w.teams[if (f.winner == f.a) f.b else f.a].orgId]
        val heat = 1 + (w.rivalries[k] ?: 0.0) / 50
        winOrg.fans *= 1 + 0.0015 * heat * (if (playoff) 2 else 1)
        loseOrg.fans *= 1 - 0.0006 * heat
        if ((w.rivalries[k] ?: 0.0) >= 60 && playoff && (w.isUserTeam(f.a) || w.isUserTeam(f.b))) w.news.add(News(w.day, "Rivalry: ${w.teams[f.a].short} vs ${w.teams[f.b].short}", "${w.teams[f.winner].name} win the latest chapter of the rivalry ${f.scoreA}-${f.scoreB}."))
    }

    /** Monthly: rivalries cool down. */
    fun monthly(w: World) {
        val it = w.rivalries.entries.iterator()
        while (it.hasNext()) { val e = it.next(); e.setValue(e.value * 0.93); if (e.value < 3) it.remove() }
    }

    fun rivalsOf(w: World, teamId: Int, n: Int = 3): List<Pair<Int, Double>> = w.rivalries.entries.mapNotNull { (k, v) ->
        val (a, b) = k.split('|').map { it.toInt() }
        when (teamId) { a -> b to v; b -> a to v; else -> null }
    }.sortedByDescending { it.second }.take(n)

    // ------------------------------------------------------------------ scrims

    /** Weekly: scrims build chemistry and sharpness but tire players. */
    fun weeklyScrims(w: World) {
        for (team in w.teams) {
            if (!team.active || team.nationalOf.isNotEmpty()) continue
            val n = team.scrims.coerceIn(0, 6)
            team.chemistry = clamp(team.chemistry + n * 0.12, 20.0, 99.0)
            team.metaAdapt = clamp(team.metaAdapt + (n - 2) * 0.4, 30.0, 99.0)
            for (pid in team.lineup) if (pid >= 0) w.players[pid].fatigue = clamp(w.players[pid].fatigue + n * 1.4, 0.0, 100.0)
        }
        // The blocks themselves: picks, results, what works on this patch.
        Scrims.weekly(w)
    }
}

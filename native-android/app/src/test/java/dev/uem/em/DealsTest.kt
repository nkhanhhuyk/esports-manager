package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Career
import dev.uem.em.mgmt.Deals
import dev.uem.em.mgmt.Economy
import org.junit.Assert.assertTrue
import org.junit.Test

class DealsTest {
    @Test
    fun loansTradesPreContractsRivalries() {
        TestData.init()
        BoxStore.clear()
        val w = WorldGen.create(TestData.source, "deals", "Deals", Dates.day(2026, 11, 21))
        val t1 = w.teams.first { it.name == "T1" }
        w.userOrg = t1.orgId
        Career.setupBoard(w)
        Advance.run(w, 3) // into the off-season window
        // Loan: give T1 a young bench player first.
        val young = w.players.filter { it.teamId < 0 && it.titleId == "lol" && Dates.ageAt(it.birth, w.day) <= 20 }.maxBy { it.ca }
        dev.uem.em.mgmt.Market.offerContract(w, young.id, t1.id, dev.uem.em.mgmt.Market.asking(w, t1, young) * 1.1, 2)
        val loan = Deals.loanOut(w, young.id)
        println("loan: ${loan.text}")
        // Trade: a bench player plus cash for a similar player at a weaker club.
        val mine = t1.roster.map { w.players[it] }.filter { it.id !in t1.lineup && it.loanFrom < 0 }.maxBy { it.ca }
        val theirs = w.players.filter { it.titleId == "lol" && it.teamId >= 0 && it.teamId != t1.id && it.mainRole == mine.mainRole && it.loanFrom < 0 }.minBy { kotlin.math.abs(Economy.marketValue(w, it) - Economy.marketValue(w, mine)) }
        val tr = Deals.trade(w, mine.id, theirs.id, Economy.marketValue(w, theirs) * 0.3)
        println("trade: ${tr.text}")
        Advance.run(w, 300) // to September
        val expiring = w.players.firstOrNull { it.titleId == "lol" && it.teamId >= 0 && !w.isUserTeam(it.teamId) && it.contractEnd == w.season && it.loanFrom < 0 }
        val pre = expiring?.let { Deals.preContract(w, it.id, t1.id, dev.uem.em.mgmt.Market.asking(w, t1, it) * 1.2) }
        println("pre-contract (${Dates.format(w.day)}): ${pre?.text}")
        Advance.run(w, 100)
        println("after rollover: loaned player team ${w.teams[w.players[young.id].teamId].name} loanFrom ${w.players[young.id].loanFrom}; pre-contract player team ${expiring?.let { w.teams.getOrNull(w.players[it.id].teamId)?.name }}")
        println("T1 rivals: ${Deals.rivalsOf(w, t1.id).map { w.teams[it.first].name + " " + it.second.toInt() }}")
        assertTrue(loan.ok)
        assertTrue(w.players[young.id].loanFrom < 0 && w.players[young.id].teamId == t1.id)
        if (pre?.ok == true) assertTrue(w.players[expiring.id].teamId == t1.id)
        assertTrue(Deals.rivalsOf(w, t1.id).isNotEmpty())
    }
}

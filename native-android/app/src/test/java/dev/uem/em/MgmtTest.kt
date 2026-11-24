package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.core.formatMoney
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Career
import dev.uem.em.mgmt.Economy
import dev.uem.em.mgmt.Market
import org.junit.Assert.assertTrue
import org.junit.Test

class MgmtTest {
    @Test
    fun seasonWithManagement() {
        TestData.init()
        BoxStore.clear()
        val w = WorldGen.create(TestData.source, "mgmt", "Mgmt", Dates.day(2026, 11, 21))
        val t1 = w.teams.first { it.name == "T1" }
        w.userOrg = t1.orgId
        Career.setupBoard(w)
        val org = w.orgs[t1.orgId]
        val bal0 = org.balance
        // Sign a free agent during the off-season window.
        val fa = w.players.filter { it.teamId < 0 && it.titleId == "lol" && !it.retired }.maxBy { it.ca }
        val ask = Market.asking(w, t1, fa)
        val r1 = Market.offerContract(w, fa.id, t1.id, ask * 1.05, 2)
        println("sign ${fa.handle} (CA ${"%.1f".format(fa.ca)}) ask ${formatMoney(ask)}: ${r1.text}")
        val t0 = System.currentTimeMillis()
        Advance.run(w, 400)
        println("400 days with management in ${System.currentTimeMillis() - t0} ms; parts (start+patch, matches, daily, weekly/monthly, market, rollover) ms: ${Advance.timing.map { it / 1_000_000 }}")
        println("T1 org balance ${formatMoney(bal0)} -> ${formatMoney(org.balance)}; months ${org.months.size}; sponsors ${org.sponsors.size}; ledger ${org.ledger.size}")
        println("staff ${w.staff.size} (T1: ${t1.staffIds.map { w.staff[it].role + " " + w.staff[it].quality.toInt() }})")
        println("transfers ${w.transfers.size} (fees ${w.transfers.count { it.fee > 0 }}), offers ${w.offers.size}, inbox ${w.inbox.size}")
        val byKind = w.patches.groupBy { it.kind }.mapValues { it.value.size }
        println("patches $byKind; sample: ${w.patches.firstOrNull { it.kind == "major" }?.notes?.take(3)}")
        println("awards ${w.awards.size}: ${w.awards.filter { it.titleId == "lol" }.joinToString { it.name + "=" + w.players[it.playerId].handle }}")
        println("board season ${w.board?.season} confidence ${w.board?.confidence?.toInt()} sacked ${w.sacked}; objectives ${w.board?.objectives?.map { it.text }}")
        val nc = w.comps.filter { it.name.startsWith(dev.uem.em.comp.Season.ENC) && !dev.uem.em.comp.Season.isEncQualifier(it) }
        println("nations cups ${nc.size}: " + nc.joinToString { "${it.short}=${it.placements.firstOrNull()?.let { tid -> w.teams[tid].name } ?: "?"}" })
        println("injured now ${w.players.count { it.injuryUntil > w.day && !it.injury.startsWith("Break") }}, on break ${w.players.count { it.injuryUntil > w.day && it.injury.startsWith("Break") }}, injuries in the season (career) n/a")
        val ncFirst = nc.filter { it.season == w.firstSeason }
        assertTrue(ncFirst.size >= 5 && ncFirst.all { it.done })
        // World rankings from the season's results.
        val clubs = dev.uem.em.mgmt.Rankings.clubs(w, "lol")
        val nations = dev.uem.em.mgmt.Rankings.nations(w, "lol")
        val orgs = dev.uem.em.mgmt.Rankings.orgs(w, w.titles)
        println("club ranking LoL: " + clubs.take(5).joinToString { "${w.teams[it.teamId].name} ${it.points.toInt()}" })
        println("nation ranking LoL: " + nations.take(6).joinToString { "${it.nat} ${it.points.toInt()} (${it.national.toInt()}+${it.players.toInt()})" } + " of ${nations.size}")
        println("organisations: " + orgs.take(5).joinToString { "${w.orgs[it.orgId].name} ${it.points.toInt()}" })
        assertTrue(clubs.first().points > 0 && clubs.zipWithNext().all { (a, b) -> a.points >= b.points })
        assertTrue(nations.size >= 240 && nations.take(10).any { it.nat == "KR" })
        assertTrue(orgs.isNotEmpty() && orgs.none { o -> w.orgs[o.orgId].teams.all { w.teams[it].nationalOf.isNotEmpty() } })
        assertTrue(w.teams.none { it.nationalOf.isNotEmpty() && it.active })
        assertTrue(org.months.size >= 12)
        assertTrue(w.transfers.isNotEmpty())
        assertTrue(byKind["minor"] ?: 0 > 3)
        assertTrue((byKind["medium"] ?: 0) >= 7)
        assertTrue(w.awards.isNotEmpty())
        // Professional teams always field a roster; a development club left short by a signing refills the next day.
        val short = w.teams.filter { it.active && it.kind.isEmpty() && it.roster.size < 5 }
        assertTrue("development clubs short for more than a day", w.teams.count { it.active && it.kind.isNotEmpty() && it.roster.size < 5 } <= 3)
        assertTrue("short rosters: ${short.take(4).map { "${it.name} (${it.kind.ifBlank { "pro" }}) ${it.roster.size}" }}", short.isEmpty())
        assertTrue(Economy.payroll(w, t1) > 0)
    }
}

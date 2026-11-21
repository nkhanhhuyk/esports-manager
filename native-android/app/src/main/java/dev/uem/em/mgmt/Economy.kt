package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.data.GameData
import dev.uem.em.model.CompTier
import dev.uem.em.model.Competition
import dev.uem.em.model.LedgerLine
import dev.uem.em.model.Message
import dev.uem.em.model.MonthFin
import dev.uem.em.model.Org
import dev.uem.em.model.Player
import dev.uem.em.model.Sponsor
import dev.uem.em.model.Staff
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Money: salaries and market values (same formulas as the web version), monthly finances
 * (sponsorship, media, merchandise vs salaries, staff, facilities, operations), prize money, and
 * the ledger. Also staff (generation, salaries, their effect on teams).
 */
object Economy {
    fun pop(titleId: String, region: String): Double {
        val p = Catalogue.POPULARITY[titleId]!!
        p[region]?.let { return it.toDouble() }
        val members = GameData.expand(region)
        return if (members.isEmpty()) 40.0 else members.map { p[it] ?: 30 }.average()
    }

    fun refCA(titleId: String, region: String): Double {
        val t = Catalogue.get(titleId)
        val cs = GameData.expand(region).flatMap { r -> GameData.countries.values.filter { it.region == r } }
        val scene = if (cs.isEmpty()) 40.0 else cs.sumOf { (if (t.isMoba) it.moba else it.shooter).toDouble() } / cs.size
        return 57 + 19 * ((0.6 * pop(titleId, region) + 0.4 * scene) / 100)
    }

    fun expectedSalary(p: Player, titleId: String = p.titleId, region: String): Double {
        val t = Catalogue.get(titleId)
        val econ = GameData.economy(region)
        val caF = exp((p.ca - refCA(titleId, region) - 1) / 12)
        val raw = t.baseSalary * (0.4 + 0.6 * pop(titleId, region) / 100) * econ * caF * (0.9 + p.rep / 100 * 0.25) * (0.9 + p.pers[4] / 100.0 * 0.25)
        return (max(4000 * econ + 2000, raw) / 500).roundToInt() * 500.0
    }

    fun regionOfPlayer(w: World, p: Player) = if (p.teamId >= 0) w.teams[p.teamId].region else GameData.regionOf(p.nat)

    fun marketValue(w: World, p: Player): Double {
        if (p.retired) return 0.0
        val age = Dates.ageAt(p.birth, w.day)
        val sal = expectedSalary(p, region = regionOfPlayer(w, p))
        val head = max(0.0, p.pa - p.ca)
        val youth = if (age <= 21) 1 + head / 12 else if (age <= 25) 1 + head / 25 else max(0.35, 1 - (age - 25) * 0.08)
        val contract = if (p.teamId >= 0) 0.6 + minOf(1.4, (max(0, p.contractEnd - w.season) + 0.5) * 0.45) else 0.25
        return (sal * 1.4 * youth * contract / 1000).roundToInt() * 1000.0
    }

    fun staffSalary(s: Staff, region: String): Double {
        val base = Catalogue.BY_ID[s.titleId]?.baseSalary ?: 150000.0
        return (max(5000.0, base * 0.11 * (0.4 + GameData.economy(region) * 0.6) * exp((s.quality - 55) / 15)) / 500).roundToInt() * 500.0
    }

    fun ledger(w: World, org: Org, kind: String, amount: Double, note: String = "") {
        if (!amount.isFinite()) return
        org.balance += amount
        org.ledger.add(LedgerLine(w.day, kind, amount.roundToInt().toDouble(), note))
        val max = if (org.id == w.userOrg) 300 else 30
        if (org.ledger.size > max + 20) org.ledger.subList(0, org.ledger.size - max).clear()
    }

    fun divisionValue(w: World, team: Team): Double {
        if (!team.active) return 0.0
        val t = Catalogue.get(team.titleId)
        return t.baseSalary * (t.teamSize + 2) * GameData.economy(team.region) * (0.4 + 0.6 * pop(team.titleId, team.region) / 100)
    }

    fun payroll(w: World, team: Team) = team.roster.sumOf { w.players[it].salary } + team.staffIds.sumOf { w.staff[it].salary }

    class Breakdown(val revenue: Map<String, Double>, val expenses: Map<String, Double>) {
        val income get() = revenue.values.sum()
        val costs get() = expenses.values.sum()
    }

    fun monthly(w: World, org: Org): Breakdown {
        val econ = GameData.economy(org.region)
        var media = 0.0
        var salaries = 0.0
        var staff = 0.0
        var ops = 0.0
        for (tid in org.teams) {
            val team = w.teams[tid]
            if (!team.active) continue
            val v = divisionValue(w, team)
            media += v * 0.75 * (0.5 + org.rep / 100) / 12
            for (pid in team.roster) salaries += w.players[pid].salary / 12
            for (sid in team.staffIds) staff += w.staff[sid].salary / 12
            ops += v * 0.06 / 12 + 1500 * (0.4 + GameData.economy(team.region))
        }
        val sponsor = org.sponsors.filter { it.untilSeason >= w.season }.sumOf { it.perMonth }
        val merch = org.fans * 0.012 * (0.4 + econ) * (0.6 + org.rep / 250)
        val facilities = org.facilities.sumOf { Math.pow(it.toDouble(), 1.5) * 350 } * (0.4 + econ)
        return Breakdown(mapOf("Sponsorship" to sponsor, "Media" to media, "Merchandise" to merch), mapOf("Player salaries" to salaries, "Staff salaries" to staff, "Facilities" to facilities, "Operations" to ops))
    }

    /** First day of each month: every organisation's finances. */
    fun monthTick(w: World) {
        val mk = Dates.year(w.day) * 12 + Dates.month(w.day)
        if (w.lastMonth == mk) return
        w.lastMonth = mk
        val rng = Rng.derive(w.seed, "finance", mk)
        for (org in w.orgs) {
            if (org.teams.none { w.teams[it].active }) continue
            renewSponsors(w, org, rng)
            val b = monthly(w, org)
            val user = org.id == w.userOrg
            if (user) {
                for ((k, v) in b.revenue) if (v > 0) ledger(w, org, k.lowercase(), v, k)
                for ((k, v) in b.expenses) if (v > 0) ledger(w, org, k.lowercase(), -v, k)
                org.months.add(MonthFin(mk, b.income, b.costs, org.balance, b.revenue + b.expenses.mapValues { -it.value }))
                if (org.months.size > 36) org.months.removeAt(0)
            } else org.balance += b.income - b.costs
            // AI owners keep clubs afloat (stage 2: no AI bankruptcy).
            if (!user && org.balance < 0) org.balance += -org.balance + 200_000 * GameData.economy(org.region)
        }
    }

    private fun renewSponsors(w: World, org: Org, rng: Rng) {
        org.sponsors.removeAll { it.untilSeason < w.season }
        val target = if (org.rep >= 70) 4 else if (org.rep >= 45) 3 else 2
        val value = org.teams.sumOf { divisionValue(w, w.teams[it]) } * 0.065 * (0.5 + org.rep / 100)
        while (org.sponsors.size < target) {
            val per = (value / target * rng.float(0.8, 1.2) / 100).roundToInt() * 100.0
            val name = "${rng.pick(COMPANY_A)} ${rng.pick(COMPANY_B)}"
            org.sponsors.add(Sponsor(name, per, w.season + rng.int(0, 2)))
            if (org.id == w.userOrg) w.inbox.add(Message(w.day, "Commercial", "New sponsor: $name", "$name signs a deal worth ${dev.uem.em.core.formatMoney(per)} per month until the end of ${org.sponsors.last().untilSeason}."))
        }
    }

    private val COMPANY_A = listOf("Nexa", "Volta", "Orion", "Helix", "Kairo", "Vanta", "Lumen", "Strata", "Quanta", "Zentra", "Arcus", "Polaris", "Meridian", "Atlas", "Cobalt", "Vertex", "Northwind", "Pinnacle", "Summit", "Bluewave")
    private val COMPANY_B = listOf("Energy", "Telecom", "Bank", "Motors", "Mobile", "Cloud", "Foods", "Drinks", "Apparel", "Hardware", "Peripherals", "Airlines", "Insurance", "Labs", "Games", "Media", "Pay")

    /** Prize money and reputation when a competition ends. */
    fun onCompetitionFinished(w: World, c: Competition) {
        if (c.prizePool <= 0) return
        val split = listOf(0.35, 0.2, 0.12, 0.08, 0.06, 0.05, 0.04, 0.03, 0.02, 0.02, 0.015, 0.015)
        c.placements.forEachIndexed { i, tid ->
            val share = split.getOrNull(i) ?: return@forEachIndexed
            val team = w.teams[tid]
            val org = w.orgs[team.orgId]
            ledger(w, org, "prize", c.prizePool * share, "${c.short}: ${dev.uem.em.core.ordinal(i + 1)} place")
            val repGain = when (c.tier) { CompTier.WORLDS -> 6.0; CompTier.INTERNATIONAL -> 3.5; CompTier.LEAGUE -> 1.5; else -> 0.5 } * (if (i == 0) 1.0 else if (i < 4) 0.4 else 0.1)
            org.rep = clamp(org.rep + repGain, 1.0, 99.0)
            org.fans *= 1 + repGain / 60
            if (i == 0) org.prestige += c.prestige / 10.0
        }
    }

    // ------------------------------------------------------------------------- staff

    val STAFF_ROLES = listOf("head-coach", "assistant-coach", "analyst", "psychologist", "performance-coach", "scout")
    val ROLE_LABEL = mapOf("head-coach" to "Head coach", "assistant-coach" to "Assistant coach", "analyst" to "Analyst", "psychologist" to "Sports psychologist", "performance-coach" to "Performance coach", "scout" to "Scout")

    fun newStaff(w: World, rng: Rng, role: String, nat: String, titleId: String, quality: Double): Staff {
        val (first, last) = GameData.personName(rng, nat)
        val s = Staff(w.staff.size, first, last, nat, w.day - ((rng.int(28, 55)) * 365.25).toInt(), role, clamp(quality, 20.0, 95.0).roundToInt().toDouble(), titleId)
        w.staff.add(s)
        return s
    }

    fun hire(w: World, s: Staff, team: Team, years: Int = 2, salary: Double = staffSalary(s, team.region)) {
        s.teamId = team.id
        s.orgId = team.orgId
        s.salary = salary
        s.contractEnd = w.season + years - 1
        if (s.id !in team.staffIds) team.staffIds.add(s.id)
    }

    fun fire(w: World, s: Staff) {
        val team = w.teams.getOrNull(s.teamId) ?: return
        val org = w.orgs[team.orgId]
        val remaining = max(0, s.contractEnd - w.season) + 0.5
        ledger(w, org, "severance", -s.salary * remaining * 0.5, "Released ${s.name()}")
        team.staffIds.remove(s.id)
        s.teamId = -1
        s.orgId = -1
        s.salary = 0.0
    }

    fun staffOf(w: World, team: Team, role: String) = team.staffIds.map { w.staff[it] }.filter { it.role == role }

    /** Head coach factor for team contexts (0.985..1.015, as in the web version). */
    fun coachQuality(w: World, team: Team): Double = staffOf(w, team, "head-coach").maxOfOrNull { it.quality } ?: (team.coach.takeIf { team.staffIds.isEmpty() } ?: 35.0)

    /** Preparation: analyst + assistant coach + analytics facility. */
    fun prepFactor(w: World, team: Team): Double {
        val a = staffOf(w, team, "analyst").maxOfOrNull { it.quality } ?: 30.0
        val ac = staffOf(w, team, "assistant-coach").maxOfOrNull { it.quality } ?: 30.0
        val fac = w.orgs[team.orgId].facilities.getOrElse(1) { 3 }
        return 1 + 0.008 * (a / 100) + 0.004 * (ac / 100) + 0.004 * (fac / 10.0)
    }

    /** Older saves and new worlds: staff, sponsors and salaries for every team. */
    fun ensureSetup(w: World) {
        if (w.staff.isNotEmpty()) return
        val rng = Rng.derive(w.seed, "staff-setup")
        for (team in w.teams) {
            if (!team.active) continue
            val org = w.orgs[team.orgId]
            hire(w, newStaff(w, rng, "head-coach", org.country, team.titleId, team.coach), team, rng.int(1, 3))
            hire(w, newStaff(w, rng, "analyst", org.country, team.titleId, rng.normal(team.coach - 4, 8.0)), team, rng.int(1, 3))
            if (rng.chance(0.5)) hire(w, newStaff(w, rng, "assistant-coach", org.country, team.titleId, rng.normal(team.coach - 6, 8.0)), team, rng.int(1, 2))
            for (pid in team.roster) { val p = w.players[pid]; if (p.salary <= 0) p.salary = expectedSalary(p, region = team.region) }
        }
        // Free staff pool.
        repeat(maxOf(40, w.teams.count { it.active } / 3)) {
            val nat = rng.pick(GameData.countries.keys.toList())
            newStaff(w, rng, rng.pick(STAFF_ROLES), nat, rng.pick(w.titles), rng.normal(48.0, 11.0))
        }
        for (p in w.players) if (p.teamId >= 0) p.salary = expectedSalary(p, region = w.teams[p.teamId].region) * rng.float(0.85, 1.15)
    }
}

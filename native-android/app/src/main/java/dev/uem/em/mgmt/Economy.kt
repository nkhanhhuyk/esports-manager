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

    fun staffSalary(s: Staff, region: String, role: String = s.role): Double {
        val base = Catalogue.BY_ID[s.titleId]?.baseSalary ?: 150000.0
        val q = if (role == s.role || s.attrs.size != Staffing.ATTRS.size) s.quality else Staffing.qualityOf(role, s.attrs)
        return (max(4000.0, base * 0.11 * (0.4 + GameData.economy(region) * 0.6) * exp((q - 55) / 15) * Staffing.def(role).wage * (0.85 + s.rep / 300)) / 500).roundToInt() * 500.0
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

    /** Wages of contracted players (development clubs' unsigned players are not paid) and staff. */
    fun payroll(w: World, team: Team) = team.roster.sumOf { val p = w.players[it]; if (p.teamId == team.id) p.salary else 0.0 } + team.staffIds.sumOf { w.staff[it].salary }

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
            for (pid in team.roster) salaries += w.players[pid].salary / 12 * (if (w.players[pid].loanFrom >= 0) 0.5 else 1.0)
            for (p in w.players) if (p.loanFrom == tid) salaries += p.salary / 12 * 0.5
            for (sid in team.staffIds) staff += w.staff[sid].salary / 12
            ops += v * 0.06 / 12 + 1500 * (0.4 + GameData.economy(team.region))
        }
        val sponsor = org.sponsors.filter { it.untilSeason >= w.season }.sumOf { it.perMonth }
        val merch = Supporters.merchandise(org, econ) * Commerce.merchFactor(org)
        val streaming = Commerce.streaming(w, org)
        val facilities = org.facilities.sumOf { Math.pow(it.toDouble(), 1.5) * 350 } * (0.4 + econ)
        val rev = linkedMapOf("Sponsorship" to sponsor, "Media" to media, "Merchandise" to merch, "Streaming" to streaming)
        val exp = linkedMapOf("Player salaries" to salaries, "Staff salaries" to staff, "Facilities" to facilities, "Operations" to ops, "Merchandise operations" to Commerce.merchCost(org))
        // Content creators and the venue (see Business).
        if (org.creators.isNotEmpty()) { rev["Content creators"] = Business.creatorRevenue(org); exp["Content creators"] = Business.creatorCost(org) }
        if (org.venue > 0) { rev["Venue (rentals, hospitality)"] = Business.VENUES[org.venue].capacity * 6.0 * (0.4 + econ); exp["Venue upkeep"] = Business.venueUpkeep(org) }
        return Breakdown(rev, exp)
    }

    /** First day of each month: every organisation's finances. */
    fun monthTick(w: World) {
        val mk = Dates.year(w.day) * 12 + Dates.month(w.day)
        if (w.lastMonth == mk) return
        w.lastMonth = mk
        val rng = Rng.derive(w.seed, "finance", mk)
        Business.monthTick(w, Rng.derive(w.seed, "business", mk))
        for (org in w.orgs) {
            if (org.teams.none { w.teams[it].active && w.teams[it].nationalOf.isEmpty() }) continue
            renewSponsors(w, org, rng)
            val b = monthly(w, org)
            val user = org.id == w.userOrg
            if (user) {
                val inc = Extras.incomeFactor(w)
                for ((k, v) in b.revenue) if (v > 0) ledger(w, org, k.lowercase(), v * inc, k)
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
        // Your organisation negotiates its own deals (unless commercial work is delegated).
        if (org.id == w.userOrg && !Roles.delegated(w, "sponsors")) { Commerce.offers(w, org, rng); return }
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

    /** Share of the prize pool by final placing. */
    val PRIZE_SPLIT = listOf(0.35, 0.2, 0.12, 0.08, 0.06, 0.05, 0.04, 0.03, 0.02, 0.02, 0.015, 0.015)

    /** Prize money and reputation when a competition ends. */
    fun onCompetitionFinished(w: World, c: Competition) {
        if (c.prizePool <= 0) return
        c.placements.forEachIndexed { i, tid ->
            val share = PRIZE_SPLIT.getOrNull(i) ?: return@forEachIndexed
            val team = w.teams[tid]
            val org = w.orgs[team.orgId]
            ledger(w, org, "prize", c.prizePool * share, "${dev.uem.em.titles.Catalogue.get(c.titleId).short} ${c.short}: ${dev.uem.em.core.ordinal(i + 1)} place (${team.short})")
            val repGain = when (c.tier) { CompTier.WORLDS -> 6.0; CompTier.INTERNATIONAL -> 3.5; CompTier.LEAGUE -> 1.5; else -> 0.5 } * (if (i == 0) 1.0 else if (i < 4) 0.4 else 0.1)
            org.rep = clamp(org.rep + repGain, 1.0, 99.0)
            org.fans *= 1 + repGain / 60
            if (i == 0) { org.prestige += c.prestige / 10.0; Commerce.trophyBonus(w, org, c.name) }
        }
    }

    // ------------------------------------------------------------------------- staff

    val STAFF_ROLES get() = Staffing.ROLES.map { it.id }
    val ROLE_LABEL get() = Staffing.ROLES.associate { it.id to it.label }

    fun newStaff(w: World, rng: Rng, role: String, nat: String, titleId: String, quality: Double): Staff = Staffing.create(w, rng, role, nat, titleId, quality)

    fun hire(w: World, s: Staff, team: Team, years: Int = 2, salary: Double = staffSalary(s, team.region)) = Staffing.hire(w, s, team, s.role, years, salary, quiet = true)

    fun fire(w: World, s: Staff) = Staffing.release(w, s, "released")

    fun staffOf(w: World, team: Team, role: String) = Staffing.members(w, team).filter { it.role == role }

    /** Head coach level for team contexts (see Staffing.coachLevel). */
    fun coachQuality(w: World, team: Team): Double = Staffing.coachLevel(w, team)

    /** Preparation: analysts, assistant, draft coach and the analytics facility. */
    fun prepFactor(w: World, team: Team): Double = Staffing.prepFactor(w, team)

    /** Older saves and new worlds: staff (real where known), sponsors and salaries for every team. */
    fun ensureSetup(w: World, src: dev.uem.em.data.DataSource? = null) {
        Nations.migrate(w)
        if (w.staff.isNotEmpty()) { Staffing.migrate(w, src); return }
        val rng = Rng.derive(w.seed, "staff-setup")
        Staffing.setup(w, src)
        for (team in w.teams) {
            if (!team.active) continue
            for (pid in team.roster) { val p = w.players[pid]; if (p.salary <= 0) p.salary = expectedSalary(p, region = team.region) }
        }
        for (p in w.players) if (p.teamId >= 0) p.salary = expectedSalary(p, region = w.teams[p.teamId].region) * rng.float(0.85, 1.15)
    }
}

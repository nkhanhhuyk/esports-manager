package dev.uem.em.mgmt

import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.core.formatMoney
import dev.uem.em.data.GameData
import dev.uem.em.model.Message
import dev.uem.em.model.Org
import dev.uem.em.model.Player
import dev.uem.em.model.Sponsor
import dev.uem.em.model.SponsorOffer
import dev.uem.em.model.World
import kotlin.math.roundToInt

/**
 * Commercial side of your organisation: sponsorship offers you accept, decline or negotiate
 * (asking for more can win a better deal, a halfway offer, or lose the sponsor), trophy bonuses,
 * players' streaming (revenue share and fans, at the cost of practice and rest) and the
 * merchandise range (costs to run, sells more). AI organisations sign sponsors automatically.
 */
object Commerce {
    val STREAM_LABEL = listOf("No streaming", "Normal", "Heavy")
    val MERCH_LABEL = listOf("Basic", "Standard", "Premium", "Global")
    private val MERCH_SALES = doubleArrayOf(0.7, 1.0, 1.35, 1.7)
    private val MERCH_COST = doubleArrayOf(0.0, 4000.0, 15000.0, 40000.0)
    private val INDUSTRIES = listOf("Energy drinks", "Telecom", "Banking", "Automotive", "Mobile phones", "Cloud", "Food delivery", "Apparel", "PC hardware", "Peripherals", "Airline", "Insurance", "Gaming chairs", "Crypto-free fintech", "Snacks")
    private val A = listOf("Nexa", "Volta", "Orion", "Helix", "Kairo", "Vanta", "Lumen", "Strata", "Quanta", "Zentra", "Arcus", "Polaris", "Meridian", "Atlas", "Cobalt", "Vertex", "Northwind", "Pinnacle", "Summit", "Bluewave")

    /** A sponsor's monthly value for this organisation (reputation, divisions, fans). */
    fun value(w: World, org: Org): Double = org.teams.sumOf { Economy.divisionValue(w, w.teams[it]) } * 0.065 * (0.5 + org.rep / 100) / 3 * Staffing.sponsorFactor(w, org.id)

    /** Monthly: the user's organisation receives offers instead of automatic deals (unless delegated). */
    fun offers(w: World, org: Org, rng: Rng) {
        w.sponsorOffers.removeAll { it.expires < w.day }
        val target = if (org.rep >= 70) 4 else if (org.rep >= 45) 3 else 2
        val pending = w.sponsorOffers.count { it.orgId == org.id }
        var need = target - org.sponsors.count { it.untilSeason >= w.season } - pending
        if (need <= 0 && rng.chance(0.25) && pending < 2) need = 1 // an extra suitor now and then
        repeat(need.coerceAtMost(2)) {
            val ind = rng.pick(INDUSTRIES)
            val per = (value(w, org) * rng.float(0.75, 1.15) / 100).roundToInt() * 100.0
            val o = SponsorOffer(w.nextOfferId++, org.id, "${rng.pick(A)} ${ind.substringBefore(' ')}", per, rng.int(1, 3), (per * rng.float(0.5, 1.5) / 100).roundToInt() * 100.0, w.day + 21, industry = ind)
            w.sponsorOffers.add(o)
            w.inbox.add(Message(w.day, "Commercial", "Sponsorship offer: ${o.name}", "${o.name} (${o.industry}) offers ${formatMoney(o.perMonth)} per month for ${o.seasons} season(s), plus ${formatMoney(o.bonus)} for every trophy. Answer in Finances → Commercial within three weeks."))
        }
    }

    fun accept(w: World, id: Int): String {
        val o = w.sponsorOffers.firstOrNull { it.id == id } ?: return "That offer is gone"
        val org = w.orgs[o.orgId]
        org.sponsors.add(Sponsor(o.name, o.perMonth, w.season + o.seasons - 1, o.bonus, o.industry))
        w.sponsorOffers.remove(o)
        return "${o.name} signed: ${formatMoney(o.perMonth)} per month until the end of ${w.season + o.seasons - 1}"
    }

    fun decline(w: World, id: Int) { w.sponsorOffers.removeAll { it.id == id } }

    /**
     * Ask for more (askPct of the monthly fee) or a longer deal. A well-known organisation can push
     * harder; each round of haggling makes the sponsor less patient.
     */
    fun negotiate(w: World, id: Int, askPct: Double, longer: Boolean): String {
        val o = w.sponsorOffers.firstOrNull { it.id == id } ?: return "That offer is gone"
        val org = w.orgs[o.orgId]
        val rng = Rng.derive(w.seed, "sponsor", o.id, o.round, askPct, longer)
        o.round++
        if (longer) {
            val ok = rng.chance(clamp(0.7 + (org.rep - 50) / 150 - o.round * 0.1, 0.2, 0.9))
            return if (ok) { o.seasons++; o.perMonth = (o.perMonth * 0.97 / 100).roundToInt() * 100.0; o.note = "Agreed to a longer deal"; "${o.name} agree to ${o.seasons} seasons at ${formatMoney(o.perMonth)} per month" }
            else { o.note = "Prefers the original length"; "${o.name} want to keep it at ${o.seasons} season(s)" }
        }
        val pAccept = clamp(0.85 - askPct * 2.4 + (org.rep - 50) / 120 - (o.round - 1) * 0.15, 0.05, 0.92)
        val pWalk = clamp(askPct * 1.2 + (o.round - 1) * 0.12 - (org.rep - 50) / 200, 0.02, 0.7)
        val r = rng.next()
        return when {
            r < pAccept -> { o.perMonth = (o.perMonth * (1 + askPct) / 100).roundToInt() * 100.0; o.bonus = (o.bonus * (1 + askPct / 2) / 100).roundToInt() * 100.0; o.note = "Accepted your terms"; "${o.name} accept: ${formatMoney(o.perMonth)} per month" }
            r < pAccept + (1 - pAccept - pWalk).coerceAtLeast(0.0) -> { o.perMonth = (o.perMonth * (1 + askPct / 2) / 100).roundToInt() * 100.0; o.note = "Met you halfway"; "${o.name} meet you halfway: ${formatMoney(o.perMonth)} per month" }
            else -> { w.sponsorOffers.remove(o); "${o.name} walked away from the talks" }
        }
    }

    /**
     * Trade part of the monthly fee for performance clauses: a lower base, a much bigger trophy bonus.
     * Sponsors like it more from organisations that win; each round still tests their patience.
     */
    fun negotiateBonus(w: World, id: Int): String {
        val o = w.sponsorOffers.firstOrNull { it.id == id } ?: return "That offer is gone"
        val org = w.orgs[o.orgId]
        val rng = Rng.derive(w.seed, "sponsor-bonus", o.id, o.round)
        o.round++
        val ok = rng.chance(clamp(0.75 + org.prestige / 400 - (o.round - 1) * 0.12, 0.2, 0.95))
        if (!ok) { o.note = "Wants a fixed fee"; return "${o.name} prefer a fixed fee without performance clauses" }
        o.perMonth = (o.perMonth * 0.85 / 100).roundToInt() * 100.0
        o.bonus = (maxOf(o.bonus * 2.5, o.perMonth * 3) / 100).roundToInt() * 100.0
        o.note = "Performance clauses agreed"
        return "${o.name}: ${formatMoney(o.perMonth)} per month and ${formatMoney(o.bonus)} for every trophy"
    }

    /** Trophy bonuses from sponsors when one of the organisation's teams wins a competition. */
    fun trophyBonus(w: World, org: Org, compName: String) {
        val total = org.sponsors.filter { it.untilSeason >= w.season }.sumOf { it.bonus }
        if (total > 0) Economy.ledger(w, org, "sponsorship", total, "Sponsor trophy bonuses: $compName")
    }

    /** A player's streaming value per month (audience from reputation, ladder rank and personality). */
    fun streamValue(w: World, p: Player, econ: Double): Double {
        val rep = (p.rep / 100).coerceIn(0.0, 1.0)
        val ladder = if (p.soloMmr > 2650) 1.5 else if (p.soloMmr > 2400) 1.2 else 1.0
        val showman = 0.7 + p.pers.getOrElse(5) { 30 } / 100.0 * 0.6 + p.pers.getOrElse(3) { 50 } / 100.0 * 0.3
        return rep * rep * 30000 * (0.4 + econ) * ladder * showman
    }

    /** Monthly streaming revenue share and merchandise for an organisation. */
    fun streaming(w: World, org: Org): Double {
        if (org.streamPolicy <= 0) return 0.0
        val econ = GameData.economy(org.region)
        val share = 0.3 * (if (org.streamPolicy >= 2) 1.8 else 1.0)
        return org.teams.filter { w.teams[it].active }.sumOf { tid -> w.teams[tid].roster.sumOf { streamValue(w, w.players[it], econ) } } * share * Staffing.mediaFactor(w, org.id)
    }

    fun merchFactor(org: Org) = MERCH_SALES[org.merchLevel.coerceIn(0, 3)]
    fun merchCost(org: Org) = MERCH_COST[org.merchLevel.coerceIn(0, 3)] * (0.4 + GameData.economy(org.region))

    /** Weekly: heavy streaming tires players; players who want to stream dislike a ban; audiences grow fans. */
    fun weekly(w: World) {
        for (org in w.orgs) {
            if (org.teams.none { w.teams[it].active }) continue
            for (tid in org.teams) for (pid in w.teams[tid].roster) {
                val p = w.players[pid]
                when (org.streamPolicy) {
                    2 -> p.fatigue = clamp(p.fatigue + 2.0, 0.0, 100.0)
                    0 -> if (p.pers.getOrElse(4) { 50 } > 70) p.unhappy = clamp(p.unhappy + 1.0, 0.0, 100.0)
                }
            }
            if (org.streamPolicy > 0) org.fans *= 1 + 0.0008 * org.streamPolicy * Staffing.mediaFactor(w, org.id)
        }
    }
}

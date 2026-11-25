package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.core.formatMoney
import dev.uem.em.data.GameData
import dev.uem.em.model.CreatorDeal
import dev.uem.em.model.FanEvent
import dev.uem.em.model.Message
import dev.uem.em.model.Org
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import kotlin.math.roundToInt

/**
 * The business side beyond sponsors and merchandise: content creators signed to the organisation
 * (audience, fan growth and revenue for a monthly fee), a venue (studio, arena, stadium: built once,
 * upkeep every month) and fan events held there or on tour (ticketed events, festivals, charity
 * streams). Every organisation runs by the same rules; AI owners sign creators, hold events and build
 * venues when they can afford them. All names are fictional.
 */
object Business {
    class Venue(val name: String, val cost: Double, val upkeep: Double, val capacity: Int, val desc: String)
    val VENUES = listOf(
        Venue("No venue", 0.0, 0.0, 0, "Events are held in rented halls (smaller, less profitable)."),
        Venue("Studio & content house", 350_000.0, 9_000.0, 400, "A studio for content, watch parties and small meet-ups."),
        Venue("Esports arena", 2_600_000.0, 48_000.0, 3_500, "A home arena: ticketed watch parties, fan festivals, hospitality."),
        Venue("Stadium", 9_500_000.0, 150_000.0, 15_000, "A stadium for the biggest fan festivals and showcase events."),
    )

    private fun econ(org: Org) = 0.4 + GameData.economy(org.region)
    fun venueCost(org: Org, level: Int) = VENUES[level].cost * econ(org)
    fun venueUpkeep(org: Org) = VENUES[org.venue.coerceIn(0, 3)].upkeep * econ(org)

    /** Build or upgrade the venue (pays the difference to the next level). */
    fun buildVenue(w: World, org: Org, level: Int): String {
        if (level <= org.venue || level > 3) return "Choose a bigger venue than the current one"
        val cost = venueCost(org, level) - venueCost(org, org.venue) * 0.6
        if (org.balance < cost) return "Not enough money: ${formatMoney(cost)} needed"
        Economy.ledger(w, org, "venue", -cost, "Built: ${VENUES[level].name}")
        org.venue = level
        org.rep = clamp(org.rep + level * 1.5, 1.0, 99.0)
        return "${VENUES[level].name} built for ${formatMoney(cost)}"
    }

    // ------------------------------------------------------------------ content creators

    class CreatorOffer(val key: String, val name: String, val platform: String, val followers: Double, val fee: Double, val titleId: String, val style: String)

    private val FIRST = listOf("Nova", "Pixel", "Echo", "Rift", "Blaze", "Kira", "Lumi", "Zed", "Maple", "Juno", "Vex", "Orbit", "Sable", "Tempo", "Quill", "Rook", "Nyx", "Comet", "Mochi", "Atlas", "Fable", "Indigo", "Kestrel", "Pico")
    private val LAST = listOf("Plays", "TV", "GG", "Live", "Cast", "Talks", "Clips", "Lab", "Zone", "Hub", "Studio", "Den", "", "Official", "Watch")
    val STYLES = mapOf(
        "variety" to "Variety streamer: a broad audience, steady growth",
        "analyst" to "Analyst: breakdowns and VOD reviews; core fans and reputation",
        "comedy" to "Comedy: clips that travel; casual and international fans",
        "former-pro" to "Former pro: credibility with core fans, coaching streams",
        "cosplay" to "Cosplay & art: merchandise and photo content",
        "co-stream" to "Co-streamer: watch parties of every match, peaks on match days",
    )
    private fun platforms(region: String) = when (region.lowercase()) { "cn", "china" -> listOf("Huya", "Douyu", "Bilibili"); "kr", "korea" -> listOf("SOOP", "CHZZK", "YouTube"); else -> listOf("Twitch", "YouTube", "Kick", "TikTok") }

    /** Creators open to signing this month (the same list all month; seeded by organisation and month). */
    fun market(w: World, org: Org): List<CreatorOffer> {
        val mk = Dates.year(w.day) * 12 + Dates.month(w.day)
        val rng = Rng.derive(w.seed, "creators", org.id, mk)
        val titles = org.teams.map { w.teams[it].titleId }.distinct().ifEmpty { Catalogue.IDS }
        val reach = 0.5 + org.rep / 100.0
        return (0 until 8).map { i ->
            val followers = (Math.exp(rng.float(9.5, 14.2)) * reach / 1000).roundToInt() * 1000.0
            val style = rng.pick(STYLES.keys.toList())
            val fee = (followers * 0.09 * econ(org) * rng.float(0.8, 1.25) / 500).roundToInt() * 500.0 + 1500
            val name = rng.pick(FIRST) + rng.pick(LAST)
            CreatorOffer("$mk-$i-$name", name, rng.pick(platforms(org.region)), followers, fee, rng.pick(titles), style)
        }.filter { o -> org.creators.none { it.name == o.name } }
    }

    fun maxCreators(org: Org) = 2 + org.venue

    fun sign(w: World, org: Org, o: CreatorOffer, seasons: Int): String {
        if (org.creators.size >= maxCreators(org)) return "No room: ${maxCreators(org)} creators at most with your venue"
        org.creators.add(CreatorDeal(o.name, o.platform, o.followers, o.fee, w.season + seasons - 1, o.titleId, o.style, w.day))
        org.fans += o.followers * 0.01
        return "${o.name} joins as a content creator until the end of ${w.season + seasons - 1}"
    }

    fun release(w: World, org: Org, name: String): String {
        val c = org.creators.firstOrNull { it.name == name } ?: return "Not signed"
        org.creators.remove(c)
        Economy.ledger(w, org, "creators", -c.fee * 2, "Release fee: ${c.name}")
        return "${c.name} released (two months' fee paid)"
    }

    /** Monthly revenue from creators: ad share, sponsored segments and merchandise they push. */
    fun creatorRevenue(org: Org): Double = org.creators.sumOf { c -> c.followers * (if (c.style == "cosplay") 0.11 else if (c.style == "co-stream") 0.095 else 0.08) } * econ(org)
    fun creatorCost(org: Org): Double = org.creators.sumOf { it.fee }

    // ------------------------------------------------------------------ fan events

    class EventKind(val id: String, val name: String, val desc: String, val minVenue: Int, val baseCost: Double, val ticket: Double, val fanShare: Double, val rep: Double, val fatigue: Double)
    val EVENTS = listOf(
        EventKind("meet", "Meet & greet", "Players sign and take photos with fans. Small, cheap, loved by core fans.", 0, 25_000.0, 15.0, 0.004, 0.3, 2.0),
        EventKind("watch", "Ticketed watch party", "Fans watch a match together at your venue with the content team.", 1, 40_000.0, 25.0, 0.006, 0.4, 0.0),
        EventKind("charity", "Charity stream", "A 12-hour charity stream with players and creators. Reputation over revenue.", 0, 30_000.0, 0.0, 0.005, 1.6, 3.0),
        EventKind("tour", "Fan tour abroad", "A roadshow in another region: international fans and sponsor exposure.", 0, 180_000.0, 30.0, 0.012, 0.8, 6.0),
        EventKind("fest", "Fan festival", "A weekend festival: show matches, stage shows, merchandise. Needs an arena.", 2, 450_000.0, 45.0, 0.02, 1.4, 5.0),
        EventKind("showcase", "Stadium showcase", "A stadium night against a famous rival: the biggest gate of the year.", 3, 1_300_000.0, 60.0, 0.03, 2.2, 6.0),
    )

    /** Why the event cannot be held now (null when it can). */
    fun blocked(w: World, org: Org, k: EventKind): String? = when {
        org.venue < k.minVenue -> "Needs ${VENUES[k.minVenue].name.lowercase()} or bigger"
        org.fanEvents.any { it.day > w.day - 14 } -> "One event every two weeks (last: ${Dates.format(org.fanEvents.last().day)})"
        org.balance < k.baseCost * econ(org) -> "Not enough money: ${formatMoney(k.baseCost * econ(org))} needed"
        else -> null
    }

    /** What the event should bring, before it is held (expected values; the real gate varies ±20%). */
    fun forecast(org: Org, k: EventKind): Triple<Double, Double, Double> {
        val e = econ(org)
        val cap = maxOf(VENUES[org.venue].capacity, if (k.minVenue == 0) 600 else 0) * (if (k.id == "tour") 3 else 1)
        val demand = org.fans * (0.002 + org.rep / 20000)
        val attendance = minOf(cap.toDouble(), demand)
        val revenue = attendance * k.ticket * e + (if (k.id == "fest" || k.id == "showcase") org.fans * 0.03 * e else 0.0)
        return Triple(k.baseCost * e, revenue, org.fans * k.fanShare)
    }

    fun hold(w: World, org: Org, kindId: String): String {
        val k = EVENTS.first { it.id == kindId }
        blocked(w, org, k)?.let { return it }
        val rng = Rng.derive(w.seed, "fan-event", org.id, w.day, kindId)
        val (cost, revenue0, fans0) = forecast(org, k)
        val revenue = revenue0 * rng.float(0.8, 1.2); val fans = fans0 * rng.float(0.8, 1.2)
        Economy.ledger(w, org, "events", -cost, k.name)
        if (revenue > 0) Economy.ledger(w, org, "events", revenue, "${k.name}: tickets and sales")
        Supporters.ensure(org)
        if (org.fanSeg.size == 4) {
            if (k.id == "tour") org.fanSeg[3] += fans else { org.fanSeg[0] += fans * 0.45; org.fanSeg[2] += fans * 0.35; org.fanSeg[1] += fans * 0.2 }
            org.fans = org.fanSeg.sum()
        } else org.fans += fans
        org.rep = clamp(org.rep + k.rep, 1.0, 99.0)
        // Players attend: a little tiring, good for morale.
        for (tid in org.teams) for (pid in w.teams[tid].roster) { val p = w.players[pid]; p.fatigue = clamp(p.fatigue + k.fatigue, 0.0, 100.0); p.morale = clamp(p.morale + 1.5, 0.0, 100.0) }
        org.fanEvents.add(FanEvent(w.day, k.id, cost, revenue, fans, k.rep, k.name))
        org.fanEvents.removeAll { it.day < w.day - 730 }
        return "${k.name}: ${formatMoney(revenue)} in, ${formatMoney(cost)} spent, +${(fans / 1000).roundToInt()}K fans"
    }

    // ------------------------------------------------------------------ month

    /** First day of each month: creators' audiences move, deals end, AI organisations make business moves. */
    fun monthTick(w: World, rng: Rng) {
        for (org in w.orgs) {
            if (org.teams.none { w.teams[it].active && w.teams[it].nationalOf.isEmpty() }) continue
            val ended = org.creators.filter { it.untilSeason < w.season }
            org.creators.removeAll(ended)
            if (org.id == w.userOrg) for (c in ended) w.inbox.add(Message(w.day, "Commercial", "Creator deal ended: ${c.name}", "${c.name}'s deal ran out. Sign new creators from Business."))
            // Audiences grow with the team's success and the creator's style.
            Supporters.ensure(org)
            for (c in org.creators) {
                val growth = 1.0 + rng.float(-0.03, 0.06) + org.rep / 4000 + (if (c.style == "comedy") 0.01 else 0.0)
                c.followers = (c.followers * growth).coerceAtLeast(1000.0)
                val gain = c.followers * (if (c.style == "former-pro" || c.style == "analyst") 0.0012 else 0.0018)
                if (org.fanSeg.size == 4) { org.fanSeg[1] += gain * 0.6; org.fanSeg[3] += gain * 0.4 } else org.fans += gain
                if (c.style == "analyst" || c.style == "former-pro") org.rep = clamp(org.rep + 0.05, 1.0, 99.0)
            }
            if (org.fanSeg.size == 4) org.fans = org.fanSeg.sum()
            if (org.id == w.userOrg && !Roles.delegated(w, "sponsors")) continue
            // AI (and delegated) business: rich owners build, most sign a creator or two and hold events.
            if (org.venue < 2 && org.balance > venueCost(org, org.venue + 1) * 4 && rng.chance(0.08)) buildVenue(w, org, org.venue + 1)
            if (org.creators.size < maxCreators(org) - 1 && org.balance > 400_000 && rng.chance(0.25)) market(w, org).maxByOrNull { it.followers / it.fee }?.let { sign(w, org, it, rng.int(1, 2)) }
            if (rng.chance(0.3)) EVENTS.filter { blocked(w, org, it) == null }.maxByOrNull { forecast(org, it).let { f -> f.second - f.first + f.third * 2 } }?.let { hold(w, org, it.id) }
        }
    }
}

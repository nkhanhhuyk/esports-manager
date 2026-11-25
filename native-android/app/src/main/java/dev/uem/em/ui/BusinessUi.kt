package dev.uem.em.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.Dates
import dev.uem.em.core.formatMoney
import dev.uem.em.mgmt.Business
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import kotlin.math.roundToInt

/** Sponsors, content creators, the venue and fan events of your organisation. */
@Composable
fun BusinessScreen(vm: GameVm, w: World) {
    val org = w.orgs.getOrNull(w.userOrg)
    if (org == null) { Card("Business") { EmptyNote("Spectator careers have no organisation to run.") }; return }
    var note by remember { mutableStateOf<String?>(null) }
    @Suppress("UNUSED_EXPRESSION") vm.version
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            val b = dev.uem.em.mgmt.Economy.monthly(w, org)
            Tiles(listOf(
                Tile("Balance", formatMoney(org.balance), color = if (org.balance >= 0) Ui.text else Ui.loss),
                Tile("Fans", "${(org.fans / 1000).roundToInt()}K", "reputation ${org.rep.roundToInt()}", Ui.accent),
                Tile("Commercial / month", formatMoney((b.revenue["Sponsorship"] ?: 0.0) + (b.revenue["Merchandise"] ?: 0.0) + (b.revenue["Streaming"] ?: 0.0) + (b.revenue["Content creators"] ?: 0.0) + (b.revenue["Venue (rentals, hospitality)"] ?: 0.0)), "sponsors, merch, streams, creators, venue", Ui.win),
                Tile("Venue", Business.VENUES[org.venue].name, if (org.venue > 0) "upkeep ${formatMoney(Business.venueUpkeep(org))}/month" else "events in rented halls"),
                Tile("Creators", "${org.creators.size}/${Business.maxCreators(org)}", formatMoney(Business.creatorCost(org)) + "/month"),
                Tile("Fan events", "${org.fanEvents.count { it.day > w.day - 365 }}", "in the last year"),
            ))
            note?.let { Text(it, color = Ui.gold, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) }
        }
        item { CommercialCard(vm, w, org) }
        // Content creators.
        item {
            Card("Content creators", right = { Muted("${org.creators.size} of ${Business.maxCreators(org)}", 11) }) {
                Muted("Creators bring fans, reputation and revenue (ad share, sponsored segments, merchandise) for a monthly fee. Their audiences grow with your results. A bigger venue makes room for more.", 11)
                if (org.creators.isEmpty()) EmptyNote("No creators signed yet.")
                for (c in org.creators) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { TitleTag(c.titleId); Text(c.name, color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
                        Muted("${c.platform} · ${(c.followers / 1000).roundToInt()}K followers · ${Business.STYLES[c.style]?.substringBefore(':') ?: c.style} · ${formatMoney(c.fee)}/month until ${c.untilSeason}", 11)
                    }
                    Pill("Release", Ui.loss.copy(alpha = 0.25f)) { note = Business.release(w, org, c.name); vm.edit() }
                }
                Gap(6)
                Text("Available this month", color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                for (o in Business.market(w, org).sortedByDescending { it.followers }) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { TitleTag(o.titleId); Text(o.name, color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                        Muted("${o.platform} · ${(o.followers / 1000).roundToInt()}K followers · ${Business.STYLES[o.style]} · ${formatMoney(o.fee)}/month", 11)
                    }
                    Pill("Sign 1 season", Ui.accentSoft, Ui.accent) { note = Business.sign(w, org, o, 1); vm.edit() }
                    Pill("2", Ui.accentSoft, Ui.accent) { note = Business.sign(w, org, o, 2); vm.edit() }
                }
            }
        }
        // Venue.
        item {
            Card("Venue") {
                val v = Business.VENUES[org.venue]
                Text(v.name, color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Muted(v.desc + if (org.venue > 0) " Capacity ${v.capacity}." else "", 11)
                for (lvl in (org.venue + 1)..3) {
                    val nv = Business.VENUES[lvl]
                    val cost = Business.venueCost(org, lvl) - Business.venueCost(org, org.venue) * 0.6
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(nv.name, color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Muted("${nv.desc} Capacity ${nv.capacity} · build ${formatMoney(cost)} · upkeep ${formatMoney(nv.upkeep * (0.4 + dev.uem.em.data.GameData.economy(org.region)))}/month", 11)
                        }
                        Pill("Build", if (org.balance >= cost) Ui.accentSoft else Ui.card2, if (org.balance >= cost) Ui.accent else Ui.muted) { note = Business.buildVenue(w, org, lvl); vm.edit() }
                    }
                }
            }
        }
        // Fan events.
        item {
            Card("Fan events") {
                Muted("One event every two weeks. Players attend: a little tiring, good for morale. Revenue depends on your fans, reputation and the venue's capacity.", 11)
                for (k in Business.EVENTS) {
                    val why = Business.blocked(w, org, k)
                    val (cost, rev, fans) = Business.forecast(org, k)
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(k.name, color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Muted(k.desc, 11)
                            Muted("Expected: costs ${formatMoney(cost)}, brings ${formatMoney(rev)}, +${(fans / 1000).roundToInt()}K fans, reputation +${"%.1f".format(k.rep)}" + (why?.let { " · $it" } ?: ""), 10)
                        }
                        Pill("Hold", if (why == null) Ui.accentSoft else Ui.card2, if (why == null) Ui.accent else Ui.muted) { note = if (why == null) Business.hold(w, org, k.id) else why; vm.edit() }
                    }
                }
                val past = org.fanEvents.takeLast(6).reversed()
                if (past.isNotEmpty()) {
                    Gap(6); Text("Recent events", color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    for (e in past) Muted("${Dates.short(e.day)} · ${e.note}: ${formatMoney(e.revenue - e.cost)} net, +${(e.fans / 1000).roundToInt()}K fans", 11)
                }
            }
        }
    }
}

package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.comp.Ecosystem
import dev.uem.em.comp.Rulesets
import dev.uem.em.core.Dates
import dev.uem.em.model.Competition
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue

/**
 * A team's road through its game's competitions this season: what it is in, what it can still reach
 * and how (each route with the team's standing on it), pending invitations to answer, and why it got
 * every place it had — with the ruleset each edition used.
 */
@Composable
fun RoadScreen(vm: GameVm, w: World, teamId: Int) {
    val team = w.teams[teamId]
    val t = Catalogue.get(team.titleId)
    val rules = Rulesets.of(team.titleId, w.season)
    val road = Ecosystem.road(w, team)
    val invites = w.invites.filter { it.teamId == teamId && w.comps[it.compId].season >= w.season - 1 }.sortedByDescending { it.day }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                TeamPic(team, 44.dp); HGap(10)
                Column {
                    Text("Road to the next events", color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Muted("${team.name} · ${w.leagues.getOrNull(team.leagueId)?.let { "${it.name} (tier ${it.tier})" } ?: "no league place"} · season ${w.season}", 12)
                }
            }
        }
        item {
            Card("Ruleset ${w.season}: ${rules.label}") {
                Muted(rules.status, 11)
                if (rules.source.isNotBlank()) Muted("Source: ${rules.source}", 10)
            }
        }
        if (dev.uem.em.comp.Formats.applies(team.titleId)) item { FormatCard(w, team.titleId) }
        val pending = invites.filter { it.status == "pending" }
        if (pending.isNotEmpty()) item {
            Card("Invitations to answer") {
                for (inv in pending) {
                    val c = w.comps[inv.compId]
                    Text(c.name, color = Ui.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { vm.go(Screen.Comp(c.id)) })
                    Muted("Invited as ${dev.uem.em.core.ordinal(inv.rank)} in the regional standings. Answer by ${Dates.format(inv.deadline)} (unanswered: the staff accept). Starts ${Dates.format(c.startDay)}. Declining passes the place to the next team in the standings and cannot be undone.", 11)
                    if (w.isUserTeam(teamId)) Row {
                        TextButton(onClick = { Ecosystem.respond(w, inv, true, null); vm.edit() }) { Text("Accept", color = Ui.win) }
                        TextButton(onClick = { Ecosystem.respond(w, inv, false, "declined by ${team.short}"); vm.edit() }) { Text("Decline", color = Ui.loss) }
                    }
                }
            }
        }
        item {
            Card("This season") {
                if (road.isEmpty()) Muted("No competition of ${t.short} this season concerns ${team.short} (no league place, no ranking position, no open qualifier in its region).", 11)
                for (r in road) RoadRow(vm, w, r)
            }
        }
        item {
            val past = w.comps.filter { c -> c.titleId == team.titleId && c.season < w.season && c.entries.any { it.teamId == teamId } }.sortedByDescending { it.startDay }.take(20)
            if (past.isNotEmpty()) Card("Earlier seasons — why ${team.short} were in") {
                for (c in past) {
                    val e = c.entries.lastOrNull { it.teamId == teamId } ?: continue
                    Text("${c.name}", color = Ui.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { vm.go(Screen.Comp(c.id)) })
                    Muted(Ecosystem.whyText(w, e) + (if (e.status != "in") " (${e.status})" else "") + " · ruleset ${Rulesets.get(c.rules)?.label ?: "game calendar"}", 10)
                }
            }
        }
    }
}

@Composable
private fun RoadRow(vm: GameVm, w: World, r: Ecosystem.Road) {
    val col = when (r.good) { true -> Ui.win; false -> Ui.loss; null -> Ui.gold }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Ui.card2, RoundedCornerShape(8.dp)).clickable { vm.go(Screen.Comp(r.comp.id)) }.padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.comp.name, color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(r.status, color = col, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Muted("${Dates.short(r.comp.startDay)} · ${tierName(r.comp)}", 10)
        for (line in r.detail.split("\n")) Text(line, color = Ui.text, fontSize = 11.sp)
    }
}

private fun tierName(c: Competition) = when (c.tier) {
    dev.uem.em.model.CompTier.WORLDS -> "world championship"; dev.uem.em.model.CompTier.INTERNATIONAL -> "international"
    dev.uem.em.model.CompTier.LEAGUE -> "top league"; dev.uem.em.model.CompTier.CHALLENGER -> "qualifier / lower tier"; else -> "custom"
}

/** A competition's entry routes (before it starts) and its qualification ledger (after): who got in, how, from which result. */
@Composable
fun QualificationCard(vm: GameVm, w: World, c: Competition) {
    Card("How teams get in") {
        Rulesets.get(c.rules)?.let { Muted("Ruleset: ${it.label}. ${it.status}", 10) }
        Muted("Format: ${dev.uem.em.comp.Formats.stagesText(c)}", 10)
        for (rule in c.entrants) Text("• " + Ecosystem.describe(w, c, rule), color = Ui.text, fontSize = 12.sp)
        if (c.entries.isNotEmpty()) {
            Gap(6)
            Text("Qualification ledger", color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            for (e in c.entries) Row(Modifier.padding(vertical = 2.dp).clickable { vm.go(Screen.Team(e.teamId)) }, verticalAlignment = Alignment.CenterVertically) {
                TeamPic(w.teams[e.teamId], 18.dp); HGap(6)
                Column(Modifier.weight(1f)) {
                    Text(w.teams[e.teamId].name + (if (e.status != "in") "  (${e.status})" else ""), color = if (e.status == "in") Ui.text else Ui.muted, fontSize = 12.sp)
                    Muted(Ecosystem.whyText(w, e), 10)
                }
            }
        }
        val inv = w.invites.filter { it.compId == c.id }
        if (inv.isNotEmpty()) {
            Gap(6)
            Text("Invitations", color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            for (i in inv.sortedBy { it.rank }) Muted("${dev.uem.em.core.ordinal(i.rank)} ${w.teams[i.teamId].name}: ${i.status} (${Dates.short(i.day)})", 11)
        }
        // Where this event's places lead.
        val next = w.comps.filter { o -> o.season == c.season && o.entrants.any { r -> r.split(':').getOrNull(1) == c.id.toString() && r.split(':')[0] in setOf("top", "qual", "promo", "bottom", "msiwin", "msiseed") } }
        if (next.isNotEmpty()) {
            Gap(6)
            Text("Places lead to", color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            for (o in next) for (r in o.entrants.filter { it.split(':').getOrNull(1) == c.id.toString() }) Text("• ${o.name}: ${Ecosystem.describe(w, o, r)}", color = Ui.accent, fontSize = 12.sp, modifier = Modifier.clickable { vm.go(Screen.Comp(o.id)) })
        }
    }
}

/** A game's international structure (as the latest major patch left it) and the competition changes so far. */
@Composable
fun FormatCard(w: World, titleId: String) {
    val lines = dev.uem.em.comp.Formats.summary(w, titleId)
    if (lines.isEmpty()) return
    Card("Competition structure — ${Catalogue.get(titleId).short}") {
        Muted("Next season's calendar, as the latest major patch left it. Formats change only with a major patch after the world championship, at most every two seasons.", 10)
        for (l in lines) Text("• $l", color = Ui.text, fontSize = 11.sp)
        val log = w.formats[titleId]?.log.orEmpty()
        if (log.isNotEmpty()) {
            Gap(6)
            Text("Changes so far", color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            for (ch in log.asReversed().take(15)) {
                Text("${ch.season + 1}: ${ch.text}", color = Ui.accent, fontSize = 11.sp)
                if (ch.why.isNotBlank()) Muted(ch.why, 10)
            }
        }
    }
}

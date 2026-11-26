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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/**
 * A game's competition structure (as the latest major patch left it), the reforms announced for later seasons with a
 * read-only preview of their effect, the archive of every change (by year and type, with ids and status) and the
 * leagues' lineage (mergers, splits).
 */
@Composable
fun FormatCard(w: World, titleId: String) {
    val lines = dev.uem.em.comp.Formats.summary(w, titleId)
    if (lines.isEmpty()) return
    val tf = w.formats[titleId]
    Card("Competition structure — ${Catalogue.get(titleId).short}") {
        Muted("Next season's calendar, as the latest major patch left it. Formats change only with a major patch after the world championship, at most every two seasons — announced for next season or, sometimes, a season further ahead. Fictional future decisions, not real announcements.", 10)
        for (l in lines) Text("• $l", color = Ui.text, fontSize = 11.sp)
        // Announced for later seasons: what will change, and a preview.
        val pend = tf?.pending?.filter { it.status == "announced" }.orEmpty()
        if (pend.isNotEmpty()) {
            Gap(6)
            Text("Announced", color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            for (p in pend) {
                Text("From ${p.effective}${if (p.ids.isNotEmpty()) " [${p.ids.joinToString()}]" else ""}: ${p.text}", color = Ui.accent, fontSize = 11.sp)
                val next = dev.uem.em.comp.Formats.pendingFormat(p)
                if (next != null) for (d in dev.uem.em.comp.Formats.preview(w, titleId, next)) Muted("   · $d", 10)
                else Muted("   · Carried out before the ${p.effective} season starts.", 10)
            }
        }
        val log = tf?.log.orEmpty()
        if (log.isNotEmpty()) {
            Gap(6)
            Text("Reform archive", color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            val years = listOf("All years") + log.map { it.season }.distinct().sortedDescending().map { "$it" }
            val types = listOf("All changes", "Seeds", "Event size", "Play-ins", "Stage format", "Best-of", "Qualification", "Leagues", "Promotion / franchising", "Other")
            fun typeOf(k: String) = when (k) { "seeds" -> 1; "expand", "shrink" -> 2; "playin+", "playin-", "playin~" -> 3; "group", "bracket" -> 4; "bo" -> 5; "qual", "winner", "runner", "lcq" -> 6
                "expandleague", "merge", "split" -> 7; "prorel+", "prorel-", "promofmt" -> 8; else -> 9 }
            var yi by remember(titleId) { mutableIntStateOf(0) }
            var ti by remember(titleId) { mutableIntStateOf(0) }
            Row { Select("", years, yi) { yi = it }; Select("", types, ti) { ti = it } }
            val shown = log.asReversed().filter { (yi == 0 || "${it.season}" == years[yi]) && (ti == 0 || typeOf(it.kind) == ti) }
            if (shown.isEmpty()) Muted("No change of that kind.", 10)
            for (ch in shown.take(30)) {
                Text("${ch.season} · ${if (ch.id.isNotEmpty()) "[${ch.id}] " else ""}${ch.text}", color = Ui.accent, fontSize = 11.sp)
                Muted("Effective ${if (ch.effective > 0) ch.effective else ch.season + 1} · ${ch.status.ifEmpty { "effective" }}${if (ch.why.isNotBlank()) " · ${ch.why}" else ""}", 10)
            }
        }
        // Leagues that ended in a merger or a split: their seasons stay in their own history.
        val former = w.leagues.filter { it.titleId == titleId && it.until > 0 && !it.academy }
        if (former.isNotEmpty()) {
            Gap(6)
            Text("Former leagues", color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            for (l in former) Muted("${l.name}: until ${l.until} → ${l.successors.mapNotNull { w.leagues.getOrNull(it)?.short }.joinToString().ifEmpty { "—" }}", 10)
        }
    }
}

/**
 * Qualification race toward the world championship for a league (the Worlds page: pick the league): each team's
 * circuit points and status — locked, a berth, the regional finals, the best of the rest, still alive or out.
 * Projections are labelled; nothing here changes the save.
 */
@Composable
fun RaceCard(vm: GameVm, w: World, c: Competition) {
    if (!dev.uem.em.comp.Formats.applies(c.titleId)) return
    val leagues = if (c.leagueId >= 0) listOfNotNull(w.leagues.getOrNull(c.leagueId)?.takeIf { it.tier == 1 }) else dev.uem.em.comp.Formats.tier1(w, c.titleId)
    if (leagues.isEmpty() || (c.tier != dev.uem.em.model.CompTier.WORLDS && c.leagueId < 0)) return
    var li by androidx.compose.runtime.saveable.rememberSaveable(c.id) { androidx.compose.runtime.mutableIntStateOf(0) }
    val league = leagues[li.coerceIn(0, leagues.size - 1)]
    // What-if (in memory only): a team's result in the next points event.
    val next = androidx.compose.runtime.remember(league.id, c.season, vm.version) { dev.uem.em.comp.Race.nextEvent(w, league, c.season) }
    val cands = league.teamIds.filter { w.teams[it].active }
    var who by androidx.compose.runtime.remember(league.id) { mutableIntStateOf(0) }
    var what by androidx.compose.runtime.remember(league.id) { mutableIntStateOf(0) }
    val bonus = if (next == null || what == 0 || who == 0) emptyMap() else mapOf(cands[who - 1] to if (what == 1) next.second else next.third)
    val rows = androidx.compose.runtime.remember(league.id, c.season, vm.version, bonus) { dev.uem.em.comp.Race.league(w, league, c.season, bonus) }
    val worlds = dev.uem.em.comp.Race.worldsOf(w, c.titleId, c.season)
    Card("Qualification race — ${worlds?.short ?: "Worlds"} ${c.season}") {
        if (leagues.size > 1) Select("League", leagues.map { it.short }, li) { li = it }
        if (next != null) Row {
            Select("What if", listOf("—") + cands.map { w.teams[it].short }, who.coerceIn(0, cands.size)) { who = it }
            Select("", listOf("no scenario", "wins ${next.first.short}", "is runner-up in ${next.first.short}"), what) { what = it }
        }
        if (bonus.isNotEmpty()) Text("What-if preview (not saved): ${w.teams[bonus.keys.first()].short} +${bonus.values.first().toInt()} points from ${next!!.first.short}. Results already decided do not change.", color = Ui.gold, fontSize = 11.sp)
        Muted("Circuit points this season and each team's route. \"Projected\" and \"Conditional\" lines follow today's table and can change; \"Locked\" and \"Eliminated\" are settled.", 10)
        for (r in rows) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Team(r.teamId)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamPic(w.teams[r.teamId], 18.dp); HGap(6)
            Column(Modifier.weight(1f)) {
                Text(w.teams[r.teamId].short + "  ·  ${r.points} pts", color = Ui.text, fontSize = 12.sp)
                Muted(r.detail, 10)
            }
            Text(r.status, color = when (r.good) { true -> Ui.win; false -> Ui.loss; null -> Ui.gold }, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}


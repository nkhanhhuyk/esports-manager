package dev.uem.em.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.Dates
import dev.uem.em.mgmt.Circuits
import dev.uem.em.model.Player
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue

/**
 * Development circuits (More tab): by game and region — amateur clubs, Community Cups and the Amateur
 * Championship (and who moves up), University League and Game Changers where they exist, eligibility,
 * and the best prospects with how much their results say.
 */
@Composable
fun CircuitsScreen(vm: GameVm, w: World) {
    val games = w.titles
    var g by remember { mutableIntStateOf(maxOf(0, games.indexOf(w.orgs.getOrNull(w.userOrg)?.teams?.firstOrNull()?.let { w.teams[it].titleId }))) }
    val titleId = games[g]
    val t = Catalogue.get(titleId)
    val regions = w.leagues.filter { it.titleId == titleId && it.tier == 1 }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Muted("Community clubs of unsigned players, university teams and VALORANT Game Changers, with their own events. A roster place there is not a contract: professional teams can sign any of these players. Results are scouting evidence (with how much they say), not hidden ratings.", 11)
            }
        }
        item { Tabs(games.map { Catalogue.get(it).short }, g) { g = it } }
        if (w.teams.none { it.kind.isNotEmpty() }) item { Card { Muted("The circuits start on the next day of the calendar.") } }
        item {
            val mine = w.orgs.getOrNull(w.userOrg)?.teams?.map { w.teams[it] }?.filter { it.titleId == titleId }.orEmpty()
            if (mine.isNotEmpty()) Card("Your eligibility") {
                for (tm in mine) Muted("${tm.name}: " + when (tm.kind) {
                    "" -> "a professional team — not eligible for amateur, university or Game Changers events (contracted players). It can enter open qualifiers where the ruleset has them; sign circuit players from the Market or develop them in the academy."
                    else -> "a ${tm.kind} team — enters its circuit's events; its champion can move up (see below)."
                }, 11)
            }
        }
        items(regions) { l ->
            val clubs = w.teams.filter { it.titleId == titleId && it.active && it.kind.isNotEmpty() && it.region == l.region }
            val events = w.comps.filter { c -> c.season == w.season && c.titleId == titleId && c.circuit.isNotBlank() && c.entrants.any { it.split(':').getOrNull(2) == "L${l.id}" } }.sortedBy { it.startDay }
            val pts = Circuits.amateurPoints(w, w.season, titleId)
            val lower = w.leagues.firstOrNull { it.upperId == l.id && it.tier == 2 && l.lowerId != it.id }
            Card("${l.short} region") {
                Muted(if (lower != null) "Amateur Championship winner moves up to ${lower.name}; its lowest independent team drops to the amateur circuit." else "No independent lower league here: amateur clubs move up through open qualifiers where the game has them.", 11)
                Text("Events ${w.season}", color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                for (c in events) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Comp(c.id)) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = Ui.accent, fontSize = 12.sp)
                        Muted("${Dates.short(c.startDay)} · ${Circuits.eligibilityText(c)}", 10)
                    }
                    Muted(if (c.done) "won by ${c.placements.firstOrNull()?.let { w.teams[it].short } ?: "—"}" else if (c.status == 1) "in progress" else "upcoming", 10)
                }
                Text("Clubs (Community Cup points)", color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                for (tm in clubs.sortedWith(compareByDescending<dev.uem.em.model.Team> { pts[it.id] ?: 0.0 }.thenByDescending { it.elo })) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Team(tm.id)) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    TeamPic(tm, 18.dp); HGap(6)
                    Text(tm.name, color = Ui.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Muted(tm.kind, 10); HGap(8)
                    Text("${(pts[tm.id] ?: 0.0).toInt()}", color = Ui.gold, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                }
            }
        }
        item {
            val prospects = w.players.filter { it.titleId == titleId && !it.retired }.mapNotNull { p -> Circuits.evidence(w, p)?.takeIf { it.games >= 3 }?.let { p to it } }
                .sortedByDescending { it.second.rating }.take(12)
            Card("Prospects from circuit results") {
                if (prospects.isEmpty()) Muted("No circuit results yet this season.", 11)
                for ((p, e) in prospects) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Player(p.id)) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerPic(p, 20.dp); HGap(6)
                    Column(Modifier.weight(1f)) {
                        Text(p.handle + (if (p.teamId >= 0 && w.teams[p.teamId].kind.isEmpty()) " (signed: ${w.teams[p.teamId].short})" else ""), color = Ui.text, fontSize = 12.sp)
                        Muted("${t.role(p.mainRole).short} · age ${Dates.ageAt(p.birth, w.day)} · ${e.games} games in ${e.events} events · avg rating %.2f vs opposition Elo %.0f · confidence ${e.confidence}".format(e.rating, e.oppElo), 10)
                    }
                }
            }
        }
    }
}

/** On a player's page: what development-circuit results show. */
@Composable
fun CircuitEvidenceCard(w: World, p: Player) {
    val e = Circuits.evidence(w, p) ?: return
    Card("Circuit evidence") {
        KV("Games", "${e.games} in ${e.events} events")
        KV("Average rating", "%.2f".format(e.rating))
        KV("Opposition level", "Elo %.0f (professional top leagues are about 1500-1900)".format(e.oppElo))
        KV("How much it says", e.confidence)
        Muted("Amateur results show form against amateur opposition; they say little about stage nerves, team communication or play at the top level.", 10)
    }
}

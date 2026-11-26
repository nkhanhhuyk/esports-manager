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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.Dates
import dev.uem.em.data.GameData
import dev.uem.em.mgmt.SoloQueue
import dev.uem.em.model.Player
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import kotlin.math.roundToInt

/** Solo queue ladders: every server of every game, with unsigned players for scouting. */
@Composable
fun SoloQueueScreen(vm: GameVm, w: World) {
    val mine = w.orgs.getOrNull(w.userOrg)?.teams?.map { w.teams[it].titleId }?.firstOrNull()
    var title by remember { mutableStateOf(mine ?: w.titles.first()) }
    var server by remember { mutableStateOf<String?>(null) }
    var unsigned by remember { mutableStateOf(false) }
    // Two views: the live ladder (now), and the weekly history (a season, then one of its weeks: stored snapshots,
    // nothing is simulated again).
    var history by remember { mutableStateOf(false) }
    val snaps = w.soloWeeks.filter { it.titleId == title }
    val seasons = (snaps.map { it.season } + w.season).distinct().sortedDescending()
    var season by remember(title) { mutableIntStateOf(w.season) }
    val weeks = snaps.filter { it.season == season }.sortedByDescending { it.week }
    var weekPick by remember(title, season) { mutableIntStateOf(0) }
    val sel = weekPick.coerceIn(0, maxOf(0, weeks.size - 1))
    val snap = if (history) weeks.getOrNull(sel) else null
    val rows: List<Ladder> = remember(title, history, snap, vm.version) {
        if (!history) SoloQueue.ladder(w, title, null).map { Ladder(it, it.soloMmr, it.soloGames, it.soloWins, it.soloDelta, it.teamId, SoloQueue.server(w, it), true) }
        else if (snap == null) emptyList()
        else snap.ids.indices.map { i -> Ladder(w.players[snap.ids[i]], snap.mmr[i].toDouble(), snap.games[i], snap.wins[i], snap.delta[i].toDouble(), snap.teams[i], snap.server(i), false) }
    }
    val servers = remember(rows) { rows.groupingBy { it.server }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    val list = remember(rows, server, unsigned) {
        val bySrv = if (server == null) rows else rows.filter { it.server == server }
        bySrv.withIndex().filter { !unsigned || it.value.team < 0 }.take(150)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card("Solo queue") {
                Muted("Ranked ladders of each game's servers. The ladder rewards mechanics and time spent queueing; teamwork, macro and shotcalling count for much more in professional play, so ladder stars are not always good professionals (and some strong professionals rank lower). Unsigned players near the top are worth scouting.", 11)
            }
            Tabs(w.titles.map { Catalogue.get(it).short }, w.titles.indexOf(title)) { title = w.titles[it]; server = null }
            Tabs(listOf("Live ladder", "Weekly history"), if (history) 1 else 0) { history = it == 1; server = null }
            if (history) {
                Row(Modifier.padding(horizontal = 12.dp)) {
                    Select("Year", seasons.map { "$it" }, seasons.indexOf(season).coerceAtLeast(0)) { season = seasons[it]; server = null }
                    if (weeks.isNotEmpty()) Select("Week", weeks.map { "Week ${it.week} · ${Dates.short(it.day)}" }, sel) { weekPick = it; server = null }
                }
                if (snap != null) Muted("  Week ${snap.week} of season ${snap.season} (${Dates.format(snap.day)}): the ladder as it stood that week — the top 60 and each server's top 10${if (snap.season < w.season - 1) " (older seasons keep their top 30)" else ""}. Teams as they were then.", 10)
                else Muted(if (season == w.season) "  No week played yet this season: the first weekly ladder is kept on the first Monday." else "  No weekly ladders were kept for $season (older save).", 10)
            } else Muted("  The ladder now: ratings, this season's games and the change since last week.", 10)
            Tabs(listOf("All servers") + servers.take(12).map { GameData.regionShort(it) }, if (server == null) 0 else servers.indexOf(server) + 1) { server = if (it == 0) null else servers[it - 1] }
            Tabs(listOf("Everyone", "Unsigned only"), if (unsigned) 1 else 0) { unsigned = it == 1 }
        }
        items(list, key = { it.value.p.id }) { (place, r) -> LadderRow(vm, w, r, place + 1) }
        if (list.isEmpty()) item { Card { Muted(if (history) "Nobody on this ladder that week." else "Nobody on this ladder yet: ratings appear after the first week of play.") } }
        item { Gap(16) }
    }
}

/** One ladder line: live (the player now) or from a weekly snapshot. */
private class Ladder(val p: Player, val mmr: Double, val games: Int, val wins: Int, val delta: Double, val team: Int, val server: String, val live: Boolean)

@Composable
private fun LadderRow(vm: GameVm, w: World, r: Ladder, place: Int) {
    val p = r.p
    val t = Catalogue.get(p.titleId)
    Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Player(p.id)) }.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$place", color = if (place <= 3) Ui.gold else Ui.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
        PlayerPic(p, 26.dp, round = true); HGap(6)
        Column(Modifier.weight(1f)) {
            Text(p.handle, color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val team = w.teams.getOrNull(r.team)
            Text(listOfNotNull(team?.short ?: if (p.amateur) "amateur · unsigned" else "unsigned", t.role(p.mainRole).short, "age ${Dates.ageAt(p.birth, w.day)}", GameData.countries[p.nat]?.name).joinToString(" · "),
                color = if (team == null) Ui.gold else Ui.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(SoloQueue.rank(p.titleId, r.mmr, place), color = Ui.text, fontSize = 11.sp, maxLines = 1)
            val wr = if (r.games > 0) " · ${100 * r.wins / r.games}%" else ""
            val kda = if (r.live) SoloQueue.kdaText(p, short = true).let { if (it.isEmpty()) "" else " · $it" } else ""
            Text("${r.games} games$wr$kda ${if (r.delta >= 1) "▲" else if (r.delta <= -1) "▼" else ""}", color = if (r.delta >= 1) Ui.win else if (r.delta <= -1) Ui.loss else Ui.muted, fontSize = 10.sp)
        }
    }
}

/** A player's solo queue: rank, this season, history and how it compares with his professional level. */
@Composable
fun SoloQueueCard(vm: GameVm, w: World, p: Player) {
    if (p.soloMmr <= 0.0) return
    val server = SoloQueue.server(w, p)
    val place = remember(p.id, vm.version) { SoloQueue.ladder(w, p.titleId, server).indexOf(p) + 1 }
    Card("Solo queue", right = { Pill("Ladder") { vm.go(Screen.SoloQ) } }) {
        KV("Rank", SoloQueue.rank(p.titleId, p.soloMmr, place))
        KV("Server", "${GameData.regionName(server)} · #$place")
        KV("This season", "${p.soloGames} games · ${if (p.soloGames > 0) "${100 * p.soloWins / p.soloGames}% wins" else "—"} · peak ${SoloQueue.rank(p.titleId, p.soloPeak)}")
        SoloQueue.kdaText(p).takeIf { it.isNotEmpty() }?.let { KV("K / D / A per game", it) }
        KV("Last week", "%+.0f".format(p.soloDelta), if (p.soloDelta >= 0) Ui.win else Ui.loss)
        KV("Queue habits", when { p.soloGrind > 0.4 -> "grinds a lot"; p.soloGrind < -0.4 -> "rarely queues"; else -> "regular" })
        Muted(SoloQueue.comparison(w, p), 11)
        if (p.amateur) Text("Amateur: has never played professionally.", color = Ui.gold, fontSize = 11.sp)
        if (p.soloHistory.isNotEmpty()) {
            Gap(4)
            for (h in p.soloHistory.takeLast(5).reversed()) { val x = h.split('|'); if (x.size >= 5) Muted("Season ${x[0]}: ${SoloQueue.rank(p.titleId, x[1].toDouble())} · ${x[2]} games · ${if (x[2].toInt() > 0) 100 * x[3].toInt() / x[2].toInt() else 0}% · peak ${SoloQueue.rank(p.titleId, x[4].toDouble())}" +
                (if (x.size >= 8 && x[2].toInt() > 0 && x[6].toInt() + x[5].toInt() > 0) " · KDA %.2f".format((x[5].toDouble() + x[7].toDouble()) / maxOf(1.0, x[6].toDouble())) else ""), 10) }
        }
    }
}

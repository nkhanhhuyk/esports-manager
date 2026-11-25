package dev.uem.em.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import java.text.Normalizer

/** One search hit: what it is, its name, a detail line and where it leads. */
class Hit(val kind: String, val name: String, val detail: String, val score: Int, val open: () -> Unit, val pic: (@Composable () -> Unit)? = null)

object Search {
    /** Lower case without accents, so "faker" finds "Faker" and "jose" finds "José". */
    fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    private fun score(name: String, q: String): Int {
        val n = norm(name)
        return when { n == q -> 100; n.startsWith(q) -> 80; n.split(' ', '-', '_', '.').any { it.startsWith(q) } -> 60; q in n -> 40; else -> 0 }
    }

    /** Screens and menu destinations by name and keywords. */
    val SCREENS = listOf(
        Triple("Home", "home dashboard next match", Screen.Home), Triple("Squad, tactics, training, staff", "squad lineup tactics training staff contracts", Screen.Squad),
        Triple("Market (players and staff)", "market transfer free agents sign buy sell scout staff coach hire", Screen.Market), Triple("Inbox", "inbox messages mail offers", Screen.Inbox),
        Triple("Finances & facilities", "finances money budget facilities sponsors", Screen.Finances), Triple("Business", "business sponsors sponsorship creators streaming venue arena fan events merch", Screen.Business),
        Triple("Board", "board objectives confidence job", Screen.Board), Triple("Events", "events competitions leagues standings brackets", Screen.Comps),
        Triple("Match centre", "watch live match replays", Screen.Watch), Triple("Schedule", "schedule calendar fixtures", Screen.Schedule),
        Triple("Teams", "teams directory", Screen.Teams), Triple("Players", "players search compare", Screen.Players), Triple("Staff", "staff coaches analysts scouts directory", Screen.StaffDir),Triple("Stats hub", "stats leaderboards records", Screen.Stats),
        Triple("Tier lists", "tier list meta champions heroes agents", Screen.Tiers), Triple("Patch notes", "patch notes meta balance", Screen.Patches),
        Triple("History & awards", "history awards hall of fame goat champions", Screen.History), Triple("World rankings", "rankings world ranking", Screen.Rankings),
        Triple("World & nations", "world nations countries", Screen.World), Triple("Solo queue ladders", "solo queue ranked ladder", Screen.SoloQ),
        Triple("Social media", "social fans posts threads", Screen.Social), Triple("Development circuits", "amateur collegiate university game changers circuits", Screen.Circuits),
        Triple("Create a competition", "create custom competition editor", Screen.CompEditor), Triple("Items", "items builds", Screen.Library("items")),
        Triple("Runes, emblems & arcana", "runes emblems arcana talents", Screen.Library("runes")), Triple("Spells", "spells summoner battle", Screen.Library("spells")),
        Triple("Neutral objectives", "objectives dragon baron lord turtle roshan", Screen.Library("objectives")), Triple("Settings", "settings theme light dark delegation difficulty save export", Screen.Settings),
    )

    fun find(vm: GameVm, w: World, raw: String, limit: Int = 60): List<Hit> {
        val q = norm(raw.trim())
        if (q.length < 2) return emptyList()
        val out = ArrayList<Hit>()
        for ((name, keys, s) in SCREENS) {
            val sc = maxOf(score(name, q), if (keys.split(' ').any { it.startsWith(q) }) 55 else 0)
            if (sc > 0) out.add(Hit("Screen", name, "Open", sc + 15, { vm.go(s) }))
        }
        for (p in w.players) {
            val sc = maxOf(score(p.handle, q), score("${p.first} ${p.last}", q) - 10)
            if (sc <= 0) continue
            val team = w.teams.getOrNull(p.teamId)
            val boost = (if (p.retired) 0 else 6) + (p.ca / 20).toInt()
            out.add(Hit("Player", p.handle, listOfNotNull(Catalogue.get(p.titleId).short, team?.name ?: if (p.retired) "retired" else "free agent", realName(p).takeIf { it.isNotBlank() }).joinToString(" · "), sc + boost, { vm.go(Screen.Player(p.id)) }) { PlayerPic(p, 30.dp) })
        }
        for (s in w.staff) {
            if (s.retired && s.past.isEmpty()) continue
            val sc = maxOf(score(s.name(), q), score(s.fullName(), q) - 10)
            if (sc <= 0) continue
            out.add(Hit("Staff", s.name(), listOfNotNull(Catalogue.get(s.titleId).short, dev.uem.em.mgmt.Staffing.label(s), w.teams.getOrNull(s.teamId)?.name ?: if (s.retired) "retired" else "free agent").joinToString(" · "), sc + (if (s.real) 6 else 0) + (s.rep / 25).toInt(), { vm.go(Screen.Staff(s.id)) }))
        }
        for (t in w.teams) {
            val sc = maxOf(score(t.name, q), score(t.short, q))
            if (sc <= 0) continue
            out.add(Hit("Team", t.name, listOfNotNull(Catalogue.get(t.titleId).short, t.leagueId.takeIf { it >= 0 }?.let { w.leagues[it].short }, if (!t.active) "inactive" else null).joinToString(" · "), sc + (if (t.active) 8 else 0), { vm.go(Screen.Team(t.id)) }) { TeamPic(t, 30.dp) })
        }
        for (o in w.orgs) {
            val sc = maxOf(score(o.name, q), score(o.short, q))
            if (sc > 0) out.add(Hit("Organisation", o.name, "${o.teams.size} team(s) · reputation ${o.rep.toInt()}", sc - 5, { vm.go(Screen.Org(o.id)) }))
        }
        // Competitions: the newest edition of each name first.
        for (c in w.comps.sortedByDescending { it.season }.distinctBy { it.name }) {
            val sc = maxOf(score(c.name, q), score(c.short, q))
            if (sc > 0) out.add(Hit("Competition", c.name, "${Catalogue.get(c.titleId).short} · ${c.season}${if (c.done) " · finished" else if (c.status == 1) " · in progress" else ""}", sc - 2, { vm.go(Screen.Comp(c.id)) }))
        }
        for (id in Catalogue.IDS) for (ch in Catalogue.get(id).characters) {
            val sc = score(ch.name, q)
            if (sc > 0) out.add(Hit("Character", ch.name, "${Catalogue.get(id).short} · ${ch.cls}", sc - 4, { Sheets.openChar(ch.id) }) { CharPic(ch.id, ch.name, 30.dp, round = true, tap = false) })
        }
        return out.sortedByDescending { it.score }.take(limit)
    }
}

/** Search everything from the top bar. */
@Composable
fun SearchScreen(vm: GameVm, w: World) {
    var q by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val hits = remember(q, vm.version) { Search.find(vm, w, q) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            OutlinedTextField(
                value = q, onValueChange = { q = it.take(40) }, singleLine = true, placeholder = { Text("Players, teams, events, characters, screens…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Ui.muted) },
                modifier = Modifier.fillMaxWidth().padding(12.dp).focusRequester(focus), shape = RoundedCornerShape(14.dp),
            )
        }
        if (q.trim().length < 2) item { EmptyNote("Type at least two letters. Accents and case do not matter.") }
        else if (hits.isEmpty()) item { EmptyNote("Nothing found for “${q.trim()}”.") }
        for ((kind, group) in hits.groupBy { it.kind }) {
            item(key = "h$kind") { SectionLabel("$kind (${group.size})") }
            items(group, key = { "$kind|${it.name}|${it.detail}" }) { h ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp)).clickable { h.open() }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val pic = h.pic
                    if (pic != null) { pic(); HGap(10) }
                    Column(Modifier.weight(1f)) {
                        Text(h.name, color = Ui.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(h.detail, color = Ui.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

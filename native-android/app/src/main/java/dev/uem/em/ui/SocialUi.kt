package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.mgmt.Social
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue

/**
 * Social media (More tab): fan culture on four fictional networks, about what happened, is happening
 * and is coming up in this world — post-match threads, player ratings, polls, hot takes, overreactions,
 * appreciation, criticism, memes, transfer and patch talk, streaks and titles.
 */
@Composable
fun SocialScreen(vm: GameVm, w: World) {
    var platform by remember { mutableIntStateOf(0) }
    val mineTeams = remember(vm.version) {
        (w.orgs.getOrNull(w.userOrg)?.teams.orEmpty() + w.followed).toSet()
    }
    val games = listOf<String?>(null) + w.titles
    var game by remember { mutableIntStateOf(0) }
    var mine by remember { mutableStateOf(false) }
    var tone by remember { mutableIntStateOf(0) }
    val feed = remember(w.day, vm.version, game, mine) { Social.feed(w, games[game], if (mine) mineTeams else emptySet()) }
    val pf = Social.PLATFORMS[platform]
    val shown = feed.filter { it.platform == pf && (tone == 0 || Social.toneLabel(it.tone) == Social.TONES[tone]) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Muted("What fans, analysts, the press and team accounts are saying about the last ten days and the matches coming up. Fictional networks and fans; it changes with every result, transfer and patch.", 11)
            }
        }
        item { Tabs(Social.PLATFORMS.map { Social.PLATFORM_NAME[it]!! }, platform) { platform = it } }
        item { Muted("  ${Social.PLATFORM_NAME[pf]} — ${Social.PLATFORM_DESC[pf]}", 10) }
        item { Tabs(games.map { it?.let { id -> Catalogue.get(id).short } ?: "All games" }, game) { game = it } }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (mineTeams.isNotEmpty()) Toggle("my & followed teams only", mine) { mine = it }
            }
        }
        item { Tabs(Social.TONES, tone) { tone = it } }
        if (mineTeams.isNotEmpty() && (pf == Social.FANBASE)) item { FanMood(w, mineTeams) }
        if (shown.isEmpty()) item { Card { Muted("Quiet here. Play some matches (or pick another network or filter) and the fans will have plenty to say.") } }
        items(shown, key = { it.key + it.platform }) { p ->
            when (p.platform) {
                Social.AGORA -> AgoraPost(w, p)
                Social.CHIRP -> ChirpPost(w, p)
                Social.LENS -> LensPost(w, p)
                else -> FanbasePost(w, p)
            }
        }
        item { Gap(16) }
    }
}

@Composable
private fun FanMood(w: World, teams: Set<Int>) {
    Card("Fan mood") {
        for (id in teams) {
            val t = w.teams.getOrNull(id) ?: continue
            val m = Social.mood(w, id)
            val (face, label) = when { m > 0.5 -> "🤩" to "Buzzing"; m > 0.15 -> "🙂" to "Happy"; m > -0.15 -> "😐" to "Nervous"; m > -0.5 -> "😟" to "Frustrated"; else -> "😡" to "Furious" }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                TeamPic(t, 22.dp); HGap(6)
                Text(t.name, color = Ui.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$face $label", color = if (m >= 0) Ui.win else Ui.loss, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Muted("From recent results; Fanbase groups get louder (and angrier) with losing streaks.", 10)
    }
}

// ------------------------------------------------------------------------------- pieces

@Composable
private fun Avatar(w: World, a: Social.Author, size: Int = 34) {
    val team = w.teams.getOrNull(a.teamId)
    when {
        a.kind == "team" && team != null -> TeamPic(team, size.dp, round = true)
        else -> {
            val col = GenArt.hue(a.handle.hashCode(), 0.5f, if (a.kind == "fan") 0.55f else 0.75f)
            Box(Modifier.size(size.dp).background(col, CircleShape), contentAlignment = Alignment.Center) {
                Text(a.name.filter { it.isLetterOrDigit() }.take(2).uppercase(), color = Color.White, fontSize = (size * 0.36).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun badge(a: Social.Author) = when (a.kind) { "team" -> " ✔"; "analyst" -> " · analyst"; "press" -> " · press"; "meme" -> " · memes"; else -> "" }

@Composable
private fun Comments(w: World, list: List<Social.Comment>, unit: String, depth: Int = 0) {
    for (c in list) {
        Row(Modifier.padding(start = (depth * 14).dp, top = 6.dp)) {
            if (depth > 0) Box(Modifier.width(2.dp).height(28.dp).background(Ui.card2)); if (depth > 0) HGap(6)
            Column(Modifier.weight(1f)) {
                Text("${c.author.handle}${badge(c.author)}", color = Ui.muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                LinkedText(c.text, Ui.text, 12.sp)
                Muted("${Social.count(c.score)} $unit", 9)
            }
        }
        if (c.replies.isNotEmpty()) Comments(w, c.replies, unit, depth + 1)
    }
}

private fun commentCount(list: List<Social.Comment>): Int = list.sumOf { 1 + commentCount(it.replies) }

@Composable
private fun AgoraPost(w: World, p: Social.Post) {
    var open by remember(p.key) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(Ui.card, RoundedCornerShape(10.dp)).clickable { open = !open }.padding(10.dp)) {
        Column(Modifier.width(42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("▲", color = Color(0xFFFF8B3D), fontSize = 14.sp)
            Text(Social.count(p.score), color = Ui.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("▼", color = Ui.muted, fontSize = 14.sp)
        }
        Column(Modifier.weight(1f)) {
            Muted("${p.place} · ${p.author.handle}${badge(p.author)} · ${Social.ago(w, p.day)}", 10)
            p.title?.let { LinkedText(it, Ui.text, 14.sp, FontWeight.Bold) }
            Gap(2)
            LinkedText(p.text, Ui.text, 12.sp)
            if (p.ratings.isNotEmpty()) RatingsTable(w, p)
            if (p.poll.isNotEmpty()) Poll(p.poll)
            Gap(4)
            Text("💬 ${commentCount(p.comments)} comments · ${Social.toneLabel(p.tone)}${if (open) " ▲" else " ▼"}", color = Ui.accent, fontSize = 11.sp)
            if (open) Comments(w, p.comments, "points")
        }
    }
}

@Composable
private fun RatingsTable(w: World, p: Social.Post) {
    Column(Modifier.padding(top = 6.dp).fillMaxWidth().background(Ui.card2, RoundedCornerShape(8.dp)).padding(6.dp)) {
        for ((pid, r) in p.ratings) {
            val pl = w.players.getOrNull(pid) ?: continue
            val team = w.teams.firstOrNull { pid in it.roster }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                PlayerPic(pl, 18.dp); HGap(6)
                PlayerLink(w, pid, size = 12.sp, modifier = Modifier.weight(1f))
                team?.let { Muted(it.short, 10); HGap(8) }
                Box(Modifier.width(80.dp).height(6.dp).background(Color(0x33000000), RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth((r / 10.0).toFloat().coerceIn(0.05f, 1f)).height(6.dp).background(if (r >= 7) Ui.win else if (r >= 5) Ui.gold else Ui.loss, RoundedCornerShape(3.dp)))
                }
                Text(" %.1f".format(r), color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun Poll(poll: List<Pair<String, Int>>) {
    val tot = poll.sumOf { it.second }.coerceAtLeast(1)
    Column(Modifier.padding(top = 6.dp)) {
        for ((label, v) in poll) {
            val f = v.toFloat() / tot
            Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(24.dp).background(Ui.card2, RoundedCornerShape(6.dp))) {
                Box(Modifier.fillMaxWidth(f.coerceIn(0.02f, 1f)).height(24.dp).background(Ui.accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp)))
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${(f * 100).toInt()}%", color = Ui.text, fontSize = 12.sp)
                }
            }
        }
        Muted("${Social.count(tot)} votes", 9)
    }
}

@Composable
private fun ChirpPost(w: World, p: Social.Post) {
    var open by remember(p.key) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).background(Ui.card, RoundedCornerShape(10.dp)).clickable { open = !open }.padding(10.dp)) {
        Avatar(w, p.author); HGap(8)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.author.name + badge(p.author), color = Ui.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                HGap(4); Muted("@${p.author.handle} · ${Social.ago(w, p.day)}", 10)
            }
            LinkedText(p.text, Ui.text, 13.sp)
            Gap(4)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Muted("💬 ${commentCount(p.comments)}", 11); Muted("🔁 ${Social.count(p.reposts)}", 11); Muted("♥ ${Social.count(p.score)}", 11)
            }
            if (open) Comments(w, p.comments, "likes")
        }
    }
}

@Composable
private fun LensPost(w: World, p: Social.Post) {
    var open by remember(p.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).background(Ui.card, RoundedCornerShape(12.dp)).clickable { open = !open }) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(w, p.author, 28); HGap(8)
            Text(p.author.handle + badge(p.author), color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Muted(Social.ago(w, p.day), 10)
        }
        p.image?.let { LensImage(w, it) }
        Column(Modifier.padding(10.dp)) {
            Text("♥ ${Social.count(p.score)} likes", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            LinkedText("${p.author.handle}  ${p.text}", Ui.text, 12.sp)
            if (p.comments.isNotEmpty()) Muted(if (open) "Comments" else "View all ${commentCount(p.comments)} comments", 11)
            if (open) Comments(w, p.comments, "likes")
        }
    }
}

@Composable
private fun LensImage(w: World, img: Social.Image) {
    val a = w.teams.getOrNull(img.teamA); val b = w.teams.getOrNull(img.teamB)
    val col = a?.let { w.orgs.getOrNull(it.orgId)?.colors?.firstOrNull() }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Ui.accent
    Box(Modifier.fillMaxWidth().aspectRatio(1.25f).background(Brush.linearGradient(listOf(col.copy(alpha = 0.85f), Color(0xFF0B0F14)))), contentAlignment = Alignment.Center) {
        when (img.kind) {
            "score" -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (a != null) TeamPic(a, 72.dp)
                Text("  ${img.caption}  ", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                if (b != null) TeamPic(b, 72.dp)
            }
            "player" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                w.players.getOrNull(img.playerId)?.let { PlayerPic(it, 120.dp) }
                Gap(6)
                Text(w.players.getOrNull(img.playerId)?.handle ?: "", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                if (a != null) Text(a.name, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (a != null) TeamPic(a, 110.dp)
                Gap(6)
                Text("🏆 ${img.caption}", color = Ui.gold, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun FanbasePost(w: World, p: Social.Post) {
    var open by remember(p.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(Ui.card, RoundedCornerShape(10.dp)).clickable { open = !open }.padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(w, p.author, 30); HGap(8)
            Column(Modifier.weight(1f)) {
                Text(p.place.ifBlank { "Fanbase" }, color = Ui.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted("${p.author.name}${badge(p.author)} · ${Social.ago(w, p.day)}", 10)
            }
        }
        Gap(4)
        LinkedText(p.text, Ui.text, 13.sp)
        Gap(6)
        val total = p.reactions.sum()
        Row(verticalAlignment = Alignment.CenterVertically) {
            val top = p.reactions.indices.sortedByDescending { p.reactions[it] }.take(3).filter { p.reactions[it] > 0 }
            Text(top.joinToString("") { Social.REACTIONS[it] }, fontSize = 14.sp)
            HGap(4); Muted(Social.count(total), 11)
            Box(Modifier.weight(1f))
            Muted("${commentCount(p.comments)} comments", 11)
        }
        if (open) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (i in Social.REACTIONS.indices) if (p.reactions[i] > 0) Text("${Social.REACTIONS[i]} ${Social.count(p.reactions[i])}", color = Ui.muted, fontSize = 11.sp)
            }
            Comments(w, p.comments, "likes")
        }
    }
}

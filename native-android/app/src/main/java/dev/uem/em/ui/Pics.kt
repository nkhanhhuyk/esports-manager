package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.uem.em.data.DataSource
import dev.uem.em.model.League
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Picture links from the shared snapshot (src/data/real/images.json): loaded from the owners'
 * servers when online; hotlink-protected hosts go through the wsrv.nl image proxy; a picture that
 * fails is retried once through the proxy, then the game's own badge is shown.
 */
object Pics {
    private var chars: Map<String, String> = emptyMap()
    private var players: Map<String, String> = emptyMap()
    private var teams: Map<String, String> = emptyMap()
    private var leagues: Map<String, String> = emptyMap()
    private var weapons: Map<String, String> = emptyMap()
    /** Hotlink-protected hosts, and hosts whose pictures are very large (Valorant agent icons are ~450 KB). */
    private val PROXIED = setOf("owcdn.net", "media.valorant-api.com")

    /** Online pictures on/off (off: the game's own badges only). */
    @Volatile var online = true

    fun init(src: DataSource) {
        if (chars.isNotEmpty()) return
        val j = Json.parseToJsonElement(src.read("images.json")).jsonObject
        fun m(k: String) = j[k]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        chars = m("characters"); players = m("players"); teams = m("teams"); leagues = m("leagues"); weapons = m("weapons")
    }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(Regex("[^a-z0-9]"), "")
    /** A picture link set in the database editor (overrides the snapshot's). */
    private fun ov(key: String): String? = Sheets.world?.images?.get(key)
    fun char(id: String?) = id?.let { ov("char:$it") ?: chars[it] }
    fun weapon(id: String) = weapons[id]
    fun player(p: Player) = ov("player:${p.id}") ?: if (p.real) players["${p.titleId}|${norm(p.handle)}"] else null
    /** Team logo; national teams show their country's flag (flagcdn.com). */
    fun team(t: Team) = ov("team:${t.id}") ?: Sheets.world?.let { w -> w.orgs.getOrNull(t.orgId)?.let { ov("org:${it.id}") } } ?: if (t.nationalOf.isNotEmpty()) flagUrl(t.nationalOf) else if (t.real) teams["${t.titleId}|${norm(t.name)}"] else null
    fun flagUrl(cc: String) = "https://flagcdn.com/w160/${cc.lowercase().let { if (it == "uk") "gb" else it }}.png"
    fun league(l: League) = ov("league:${l.id}") ?: if (l.real) leagues["${l.titleId}|${norm(l.name)}"] ?: leagues["${l.titleId}|${norm(l.short)}"] else null
    fun comp(c: dev.uem.em.model.Competition) = ov("comp:${c.id}")

    /** Every picture link the game knows (all games, flags, your database-editor links): for the full offline download. */
    fun everything(w: dev.uem.em.model.World?): List<String> {
        val out = LinkedHashSet<String>()
        out.addAll(chars.values); out.addAll(weapons.values); out.addAll(teams.values); out.addAll(leagues.values); out.addAll(players.values)
        for (cc in dev.uem.em.data.GameData.countries.keys) out.add(flagUrl(cc))
        if (w != null) out.addAll(w.images.values)
        return out.toList()
    }

    /** Every picture URL for the given games (characters, team and league logos, real players). */
    fun allFor(w: dev.uem.em.model.World, titles: Set<String>): List<String> {
        val out = LinkedHashSet<String>()
        for ((k, v) in chars) if (k.substringBefore('-') in titles) out.add(display(v))
        for (l in w.leagues) if (l.titleId in titles) league(l)?.let { out.add(display(it)) }
        for (t in w.teams) if (t.titleId in titles) team(t)?.let { out.add(display(it)) }
        for (p in w.players) if (!p.retired && p.titleId in titles) player(p)?.let { out.add(display(it)) }
        return out.toList()
    }

    fun proxied(url: String) = "https://wsrv.nl/?url=${URLEncoder.encode(url, "UTF-8")}&w=128&h=128&fit=contain&output=webp"
    fun display(url: String) = if (PROXIED.any { url.contains(it) }) proxied(url) else url
}

/** A picture with generated art (or the game's own badge: initials on a colour) as fallback. */
@Composable
fun Pic(url: String?, label: String, size: Dp = 28.dp, round: Boolean = false, color: Color = Color(0xFF2B3550), fallback: (@Composable () -> Unit)? = null) {
    val shape = if (round) CircleShape else RoundedCornerShape(6.dp)
    var attempt by remember(url) { mutableStateOf(0) }
    val stored = remember(url) { PictureStore.local(url) }
    Box(Modifier.size(size).clip(shape).background(color), contentAlignment = Alignment.Center) {
        if (stored != null && attempt == 0) {
            // Downloaded for good (Settings → Pictures): shown with or without internet.
            AsyncImage(model = stored, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.size(size), onError = { attempt = 1 })
        } else if (url == null || attempt > 1 || !Pics.online) {
            if (fallback != null) fallback() else
            Text(label.filter { it.isLetterOrDigit() }.take(2).uppercase(), color = Color(0xFFDDE3F0), fontSize = (size.value * 0.38).sp)
        } else {
            // First try: the usual address (the proxy for hotlink-protected or very large pictures); second: the other one.
            val first = Pics.display(url)
            val shown = if (attempt == 0) first else if (first == url) Pics.proxied(url) else url
            AsyncImage(model = shown, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.size(size), onError = { attempt++ })
        }
    }
}

/** A player's photo, or a generated portrait in his team's colours. */
@Composable
fun PlayerPic(p: Player, size: Dp = 28.dp, round: Boolean = true) {
    val team = Sheets.world?.teams?.getOrNull(p.teamId)
    val jersey = team?.let { Sheets.world?.orgs?.getOrNull(it.orgId)?.colors?.firstOrNull() }?.let { GenArt.parse(it) } ?: GenArt.hue(p.id)
    Pic(Pics.player(p), p.handle, size, round, fallback = { GenFace(p.id * 31 + p.handle.hashCode(), size, jersey) })
}

/** A team's logo (a flag for national teams), or a generated crest in the organisation's colours. */
@Composable
fun TeamPic(t: Team, size: Dp = 28.dp, round: Boolean = false) {
    val org = Sheets.world?.orgs?.getOrNull(t.orgId)
    Pic(Pics.team(t), t.short, size, round, fallback = { GenTeamLogo(t.orgId * 17 + t.name.hashCode(), size, t.name, t.short, org?.colors?.getOrNull(0)?.let { GenArt.parse(it) }, org?.colors?.getOrNull(1)?.let { GenArt.parse(it) }) })
}

/** A competition's logo: your picture link, the league's real logo, or a generated one by tier. */
@Composable
fun CompPic(w: dev.uem.em.model.World, c: dev.uem.em.model.Competition, size: Dp = 28.dp) {
    Pic(Pics.comp(c) ?: c.leagueId.takeIf { it >= 0 }?.let { w.leagues.getOrNull(it) }?.let { Pics.league(it) }, c.short, size, fallback = { GenCompLogo(c, size) })
}

/** A champion / hero / agent portrait (generated emblem when there is no official art); tap for its sheet. */
@Composable
fun CharPic(id: String?, label: String, size: Dp = 28.dp, round: Boolean = false, color: Color = Color(0xFF2B3550), tap: Boolean = true) {
    val cls = id?.let { Sheets.charClass(it) } ?: ""
    Box(if (tap && id != null) Modifier.clickable { Sheets.openChar(id) } else Modifier) {
        Pic(Pics.char(id), label, size, round, color, fallback = { GenChampion((id ?: label).hashCode(), size, cls) })
    }
}

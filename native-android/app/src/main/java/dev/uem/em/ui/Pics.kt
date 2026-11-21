package dev.uem.em.ui

import androidx.compose.foundation.background
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
    private val PROXIED = setOf("owcdn.net")

    /** Online pictures on/off (off: the game's own badges only). */
    @Volatile var online = true

    fun init(src: DataSource) {
        if (chars.isNotEmpty()) return
        val j = Json.parseToJsonElement(src.read("images.json")).jsonObject
        fun m(k: String) = j[k]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        chars = m("characters"); players = m("players"); teams = m("teams"); leagues = m("leagues")
    }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(Regex("[^a-z0-9]"), "")
    fun char(id: String?) = id?.let { chars[it] }
    fun player(p: Player) = if (p.real) players["${p.titleId}|${norm(p.handle)}"] else null
    fun team(t: Team) = if (t.real) teams["${t.titleId}|${norm(t.name)}"] else null
    fun league(l: League) = if (l.real) leagues["${l.titleId}|${norm(l.name)}"] ?: leagues["${l.titleId}|${norm(l.short)}"] else null

    fun proxied(url: String) = "https://wsrv.nl/?url=${URLEncoder.encode(url, "UTF-8")}&w=128&h=128&fit=contain&output=webp"
    fun display(url: String) = if (PROXIED.any { url.contains(it) }) proxied(url) else url
}

/** A picture with the game's own badge (initials on a colour) as fallback. */
@Composable
fun Pic(url: String?, label: String, size: Dp = 28.dp, round: Boolean = false, color: Color = Color(0xFF2B3550)) {
    val shape = if (round) CircleShape else RoundedCornerShape(6.dp)
    var attempt by remember(url) { mutableStateOf(0) }
    Box(Modifier.size(size).clip(shape).background(color), contentAlignment = Alignment.Center) {
        if (url == null || attempt > 1 || !Pics.online) {
            Text(label.filter { it.isLetterOrDigit() }.take(2).uppercase(), color = Color(0xFFDDE3F0), fontSize = (size.value * 0.38).sp)
        } else {
            val shown = if (attempt == 0) Pics.display(url) else Pics.proxied(url)
            AsyncImage(model = shown, contentDescription = label, contentScale = ContentScale.Crop, modifier = Modifier.size(size), onError = { attempt = if (shown == Pics.proxied(url)) 2 else attempt + 1 })
        }
    }
}

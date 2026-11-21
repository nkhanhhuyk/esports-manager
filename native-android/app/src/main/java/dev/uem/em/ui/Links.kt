package dev.uem.em.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.uem.em.model.World

/**
 * Tappable names everywhere: any text that mentions a team, organisation, competition or player of
 * the world links to its page. Explicit links ([PlayerLink], [TeamLink], [OrgLink], [CompLink]) are
 * used where the id is known; free text (news, inbox, history lines, descriptions, info rows) is
 * scanned against an index of the world's names.
 */
object Links {
    /** Navigation (set by the app shell; off while a live match is on screen). */
    @Volatile var go: ((Screen) -> Unit)? = null

    sealed class Target { abstract val screen: Screen }
    class T(val id: Int) : Target() { override val screen get() = Screen.Team(id) }
    class O(val id: Int) : Target() { override val screen get() = Screen.Org(id) }
    class C(val id: Int) : Target() { override val screen get() = Screen.Comp(id) }
    class P(val id: Int) : Target() { override val screen get() = Screen.Player(id) }

    /** Words that are also player handles but read as ordinary words in sentences. */
    private val STOP = (
        "the and for you are but not all any can had her was one our out day get has him his how man new now old see two way who boy did its let put say she too use win won lost loss game games team teams map maps round " +
            "match season split final finals group stage week month year years gold blue red top mid bot jungle support carry tank mage best good great bad high low first last next more most less " +
            "king queen lord star stars fire ice storm ghost shadow light dark night sun moon wolf fox bear lion tiger eagle hawk dragon snake viper phoenix titan giant hero heroes legend legends " +
            "ace max pro zero one chief boss master captain coach player players fan fans home away world worlds league cup open major minor patch patches buff nerf buffs nerfs meta pick picks ban bans " +
            "draft side sides kill kills death deaths assist assists rank ranked solo queue rookie veteran prodigy talent free agent agents trade trades loan loans deal deals offer offers sign signs " +
            "signed contract contracts board money cash prize prizes title titles trophy trophies award awards mvp all-pro lead leads chance form peak rising young old new news report reports"
        ).split(' ').filter { it.isNotBlank() }.toHashSet()

    private class Index(val key: Long, val byFirst: HashMap<String, MutableList<Pair<String, Target>>>)
    @Volatile private var index: Index? = null

    private fun keyOf(w: World) = (System.identityHashCode(w).toLong() shl 32) xor (w.players.size.toLong() * 31 + w.teams.size * 7 + w.comps.size * 13 + w.orgs.size)

    private fun build(w: World): Index {
        val names = HashMap<String, Target>()
        val weight = HashMap<String, Double>()
        fun put(name: String, t: Target, wt: Double) {
            val n = name.trim()
            if (n.length < 2 || n.all { it.isDigit() }) return
            val prev = weight[n]
            if (prev == null || wt > prev) { names[n] = t; weight[n] = wt }
        }
        // Players: active and real (or famous) handles; ambiguous handles go to the best known player.
        for (p in w.players) {
            if (p.handle.length < 3 || p.handle.lowercase() in STOP) continue
            if (p.retired && !p.real && p.awards.isEmpty()) continue
            put(p.handle, P(p.id), p.ca + (if (p.retired) -40.0 else 0.0) + (if (p.real) 10.0 else 0.0))
        }
        // Competitions (unique names), organisations, teams (a name shared by an organisation's teams in several games opens the organisation).
        for (c in w.comps) put(c.name, C(c.id), 1000.0 + c.season)
        val teamsByName = w.teams.filter { it.active || it.trophies.isNotEmpty() }.groupBy { it.name }
        for ((name, ts) in teamsByName) {
            val orgs = ts.map { it.orgId }.distinct()
            if (ts.size == 1) put(name, T(ts[0].id), 2000.0) else if (orgs.size == 1) put(name, O(orgs[0]), 2000.0) else put(name, T(ts.maxBy { it.elo }.id), 2000.0)
        }
        for (t in w.teams) if (t.active && t.short.length >= 3 && t.short.lowercase() !in STOP && t.short != t.name) {
            val same = w.teams.count { it.active && it.short == t.short }
            if (same == 1) put(t.short, T(t.id), 1500.0)
        }
        // Leagues open their current (or latest) competition.
        for (l in w.leagues) {
            val c = w.comps.filter { it.leagueId == l.id }.maxWithOrNull(compareBy({ it.season }, { if (it.status > 0) 1 else 0 }, { it.startDay })) ?: continue
            put(l.name, C(c.id), 900.0); if (l.short.length >= 3) put(l.short, C(c.id), 800.0)
        }
        for (o in w.orgs) if (o.name.isNotBlank()) {
            val active = o.teams.filter { w.teams[it].active }
            put(o.name, if (active.size == 1 && w.teams[active[0]].name == o.name) T(active[0]) else O(o.id), 2500.0)
        }
        val byFirst = HashMap<String, MutableList<Pair<String, Target>>>()
        for ((n, t) in names) byFirst.getOrPut(n.substringBefore(' ')) { ArrayList() }.add(n to t)
        for (l in byFirst.values) l.sortByDescending { it.first.length }
        return Index(keyOf(w), byFirst)
    }

    private fun indexFor(w: World): Index {
        val k = keyOf(w)
        index?.let { if (it.key == k) return it }
        return build(w).also { index = it }
    }

    /** Spans of the text that name something: start, end, target. */
    fun find(w: World, text: String): List<Triple<Int, Int, Target>> {
        if (text.length < 2) return emptyList()
        val idx = indexFor(w)
        val out = ArrayList<Triple<Int, Int, Target>>()
        var i = 0
        val n = text.length
        while (i < n) {
            if (!text[i].isLetterOrDigit() || (i > 0 && text[i - 1].isLetterOrDigit())) { i++; continue }
            var j = i
            while (j < n && !text[j].isWhitespace()) j++
            var word = text.substring(i, j)
            var cands = idx.byFirst[word]
            if (cands == null) {
                word = word.removeSuffix("'s").removeSuffix("’s").trimEnd(',', '.', ';', ':', '!', '?', ')', '"', '\'', '’', '”')
                cands = idx.byFirst[word]
            }
            var matched = false
            if (cands != null) for ((name, t) in cands) {
                if (!text.startsWith(name, i)) continue
                val end = i + name.length
                if (end < n && text[end].isLetterOrDigit()) continue
                out.add(Triple(i, end, t)); i = end; matched = true; break
            }
            if (!matched) i = j
        }
        return out
    }
}

private val LINK_STYLE = TextLinkStyles(SpanStyle(color = Color(0xFF8DB4FF)))

/** Text whose names of teams, organisations, competitions and players are tappable. */
@Composable
fun LinkedText(text: String, color: Color = Ui.text, size: TextUnit = 13.sp, weight: FontWeight? = null, maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null, modifier: Modifier = Modifier) {
    val w = Sheets.world
    val go = Links.go
    val spans = remember(text, w) { if (w == null || go == null) emptyList() else Links.find(w, text) }
    if (spans.isEmpty()) { Text(text, color = color, fontSize = size, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align, modifier = modifier); return }
    val s: AnnotatedString = buildAnnotatedString {
        var at = 0
        for ((a, b, t) in spans) {
            if (a > at) append(text.substring(at, a))
            withLink(LinkAnnotation.Clickable("l$a", LINK_STYLE) { Links.go?.invoke(t.screen) }) { append(text.substring(a, b)) }
            at = b
        }
        if (at < text.length) append(text.substring(at))
    }
    Text(s, color = color, fontSize = size, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align, modifier = modifier)
}

@Composable
private fun LinkName(label: String, screen: Screen?, color: Color, size: TextUnit, weight: FontWeight?, maxLines: Int, modifier: Modifier) {
    Text(label, color = color, fontSize = size, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        modifier = if (screen != null) modifier.clickable { Links.go?.invoke(screen) } else modifier)
}

@Composable
fun PlayerLink(w: World, id: Int, label: String? = null, color: Color = Ui.text, size: TextUnit = 13.sp, weight: FontWeight? = null, maxLines: Int = 1, modifier: Modifier = Modifier) {
    val p = w.players.getOrNull(id)
    LinkName(label ?: p?.handle ?: "—", if (p != null) Screen.Player(id) else null, color, size, weight, maxLines, modifier)
}

@Composable
fun TeamLink(w: World, id: Int, short: Boolean = false, label: String? = null, color: Color = Ui.text, size: TextUnit = 13.sp, weight: FontWeight? = null, maxLines: Int = 1, modifier: Modifier = Modifier) {
    val t = w.teams.getOrNull(id)
    LinkName(label ?: (if (short) t?.short else t?.name) ?: "TBD", if (t != null) Screen.Team(id) else null, color, size, weight, maxLines, modifier)
}

@Composable
fun OrgLink(w: World, id: Int, label: String? = null, color: Color = Ui.text, size: TextUnit = 13.sp, weight: FontWeight? = null, maxLines: Int = 1, modifier: Modifier = Modifier) {
    val o = w.orgs.getOrNull(id)
    LinkName(label ?: o?.name ?: "—", if (o != null) Screen.Org(id) else null, color, size, weight, maxLines, modifier)
}

@Composable
fun CompLink(w: World, id: Int, label: String? = null, color: Color = Ui.text, size: TextUnit = 13.sp, weight: FontWeight? = null, maxLines: Int = 1, modifier: Modifier = Modifier) {
    val c = w.comps.getOrNull(id)
    LinkName(label ?: c?.name ?: "—", if (c != null) Screen.Comp(id) else null, color, size, weight, maxLines, modifier)
}

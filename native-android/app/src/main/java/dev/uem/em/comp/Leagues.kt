package dev.uem.em.comp

import dev.uem.em.core.Rng
import dev.uem.em.data.GameData
import dev.uem.em.model.League
import dev.uem.em.model.News
import dev.uem.em.model.PendingChange
import dev.uem.em.model.Team
import dev.uem.em.model.TitleFormat
import dev.uem.em.model.World
import dev.uem.em.titles.TitleDef

/**
 * League reforms (rare, with a major patch, carried out before the season they are announced for):
 * an expansion (two more clubs, from below the league), a merger of two small leagues into one, and a
 * split of a large multi-region league into two by the clubs' home regions. Organisations and teams stay
 * the same (they change league membership, never get copied); old leagues end with their history and
 * point to their successors; seeds move with the clubs (a merged league keeps both leagues' seeds up to
 * its cap, split seeds are shared out). Academy leagues follow (Academies.ensureLeagues).
 */
object Leagues {
    private fun clubs(w: World, l: League) = l.teamIds.map { w.teams[it] }.filter { it.active && it.parentId < 0 }

    /** Clubs that could join a league: the best of its independent lower leagues, then unaffiliated clubs of its region. */
    fun candidates(w: World, l: League, n: Int): List<Team> {
        val below = Formats.independentLowers(w, l).flatMap { clubs(w, it) }.sortedByDescending { it.elo }
        val free = w.teams.filter { it.titleId == l.titleId && it.active && it.parentId < 0 && it.leagueId < 0 && it.nationalOf.isEmpty() && it.kind.isEmpty() &&
            it.roster.size >= dev.uem.em.titles.Catalogue.get(it.titleId).teamSize && Ecosystem.inRegion(w, it, l) }.sortedByDescending { it.elo }
        return (below + free).distinct().take(n)
    }

    private fun macroOf(region: String) = if ('-' in region) region.substringBefore('-') else GameData.regions[region]?.macro ?: region

    /** A club's home sub-region within a league's region list (by its organisation's region or country). */
    private fun home(w: World, t: Team, subs: List<String>): String? {
        val org = w.orgs[t.orgId]
        return subs.firstOrNull { s -> org.region == s || org.country == s || org.region in GameData.expand(s) || org.country in GameData.expand(s) || t.region == s }
    }

    /** A split of a league by home regions into two parts of at least six clubs each, or null. */
    private fun splitPlan(w: World, l: League): Pair<List<String>, List<String>>? {
        if ('-' !in l.region) return null
        val subs = l.region.substringAfter('-').split('.').filter { it.isNotBlank() }
        if (subs.size < 2) return null
        val cs = clubs(w, l)
        val count = subs.associateWith { s -> cs.count { home(w, it, subs) == s } }
        // Greedy: the largest sub-regions alternately, then the rest to the smaller side.
        val a = ArrayList<String>(); val b = ArrayList<String>(); var na = 0; var nb = 0
        for (s in subs.sortedByDescending { count[it] ?: 0 }) if (na <= nb) { a.add(s); na += count[s] ?: 0 } else { b.add(s); nb += count[s] ?: 0 }
        val unknown = cs.count { home(w, it, subs) == null }
        if (minOf(na, nb) + (if (na < nb) unknown else 0) < 6 || maxOf(na, nb) < 6) return null
        return a to b
    }

    /** A proposal for [kind] ("expandleague", "merge", "split"): its text, reasons and leagues; the reform is stored as pending. */
    fun propose(w: World, t: TitleDef, tf: TitleFormat, tier1: List<League>, kind: String, rng: Rng, effective: Int): Triple<String, String, List<Int>>? {
        when (kind) {
            "expandleague" -> {
                val l = tier1.filter { clubs(w, it).size in 6..11 && candidates(w, it, 2).size >= 2 }.let { if (it.isEmpty()) return null else rng.pick(it) }
                tf.pending.add(PendingChange("expandleague", effective, w.day, "${l.short} expands to ${clubs(w, l).size + 2} teams", state = "l=${l.id}"))
                return Triple("${l.short} expands from ${clubs(w, l).size} to ${clubs(w, l).size + 2} teams from $effective",
                    "The region has grown: two more clubs get a top-flight place (the strongest below the league when the season starts). Its Worlds places stay as they are.", listOf(l.id))
            }
            "merge" -> {
                if (tier1.size < 3) return null
                val small = tier1.filter { clubs(w, it).size in 4..8 }.sortedBy { Season.leagueStrength(it) }
                val pair = small.flatMap { a -> small.filter { it.id != a.id && macroOf(it.region) == macroOf(a.region) }.map { a to it } }.firstOrNull()
                    ?: small.take(2).takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return null
                val (a, b) = pair
                if (clubs(w, a).size + clubs(w, b).size > 16) return null
                tf.pending.add(PendingChange("merge", effective, w.day, "${a.short} and ${b.short} merge into one league", state = "a=${a.id};b=${b.id}"))
                return Triple("${a.short} and ${b.short} merge into one league from $effective (${clubs(w, a).size + clubs(w, b).size} teams)",
                    "Neither scene can sustain a full league on its own; together they make one stronger competition. Both leagues' histories are kept; the merged league carries their Worlds seeds (up to its cap).", listOf(a.id, b.id))
            }
            "split" -> {
                val l = tier1.filter { clubs(w, it).size >= 14 && splitPlan(w, it) != null }.let { if (it.isEmpty()) return null else rng.pick(it) }
                val (sa, sb) = splitPlan(w, l)!!
                tf.pending.add(PendingChange("split", effective, w.day, "${l.short} splits into two leagues", state = "l=${l.id};a=${sa.joinToString(".")};b=${sb.joinToString(".")}"))
                return Triple("${l.short} splits into two leagues from $effective: ${sa.joinToString("/") { GameData.regionShort(it) }} and ${sb.joinToString("/") { GameData.regionShort(it) }}",
                    "A large multi-region circuit becomes two regional leagues; every club plays where its home region is. The league's history is kept and its Worlds seeds are shared out.", listOf(l.id))
            }
        }
        return null
    }

    private fun param(p: PendingChange, k: String) = p.state.split(';').firstOrNull { it.startsWith("$k=") }?.substringAfter('=')

    private fun live(l: League?) = l != null && l.tier == 1 && l.until == 0

    /** Carry out a pending league reform before [season]'s calendar; false when it can no longer be done. */
    fun apply(w: World, tf: TitleFormat, p: PendingChange, season: Int): Boolean {
        val t = dev.uem.em.titles.Catalogue.get(tf.titleId)
        // Seeds written out first, so the other leagues keep theirs when the number of leagues changes.
        val t1 = Formats.tier1(w, tf.titleId)
        for (key in Formats.KEYS) { val s = Formats.seeds(w, tf.titleId, key, t1); t1.forEachIndexed { i, l -> tf.seeds.putIfAbsent("$key:${l.id}", s[i]) } }
        when (p.kind) {
            "expandleague" -> {
                val l = w.leagues.getOrNull(param(p, "l")?.toIntOrNull() ?: -1)?.takeIf { live(it) } ?: return false
                val joining = candidates(w, l, 2)
                if (joining.size < 2) return false
                for (team in joining) {
                    val from = w.leagues.getOrNull(team.leagueId)
                    Season.moveTeam(w, team, l)
                    w.news.add(News(w.day, "${team.name} join ${l.short}", "${l.short} expands for $season: ${team.name}${from?.let { " (from ${it.short})" } ?: ""} take one of the two new places.", listOf(team.id)))
                }
            }
            "merge" -> {
                val a = w.leagues.getOrNull(param(p, "a")?.toIntOrNull() ?: -1)?.takeIf { live(it) } ?: return false
                val b = w.leagues.getOrNull(param(p, "b")?.toIntOrNull() ?: -1)?.takeIf { live(it) } ?: return false
                val subs = (GameData.expand(a.region).ifEmpty { listOf(a.region) } + GameData.expand(b.region).ifEmpty { listOf(b.region) }).distinct()
                val c = League(w.leagues.size, a.titleId, "${macroOf(a.region)}-${subs.joinToString(".")}", 1, "${a.name} & ${b.name}", "${a.short}+${b.short}", since = season)
                c.predecessors.add(a.id); c.predecessors.add(b.id)
                c.lowerId = a.lowerId.takeIf { it >= 0 } ?: b.lowerId
                w.leagues.add(c)
                for (team in clubs(w, a) + clubs(w, b)) Season.moveTeam(w, team, c)
                for (l in listOf(a, b)) {
                    for (low in w.leagues.filter { it.upperId == l.id }) low.upperId = c.id
                    l.until = season - 1; l.successors.add(c.id)
                }
                // One academy league: the second one's teams join the first.
                val acads = w.leagues.filter { it.academy && it.upperId == c.id && it.until == 0 }
                for (extra in acads.drop(1)) { for (tid in extra.teamIds.toList()) { w.teams[tid].leagueId = acads[0].id; acads[0].teamIds.add(tid) }; extra.teamIds.clear(); extra.until = season - 1; extra.successors.add(acads[0].id) }
                for (key in Formats.KEYS) {
                    val s = (tf.seeds["$key:${a.id}"] ?: 0) + (tf.seeds["$key:${b.id}"] ?: 0)
                    if (s > 0) tf.seeds["$key:${c.id}"] = minOf(s, Formats.cap(key, c))
                }
                tf.rank = tf.rank.map { if (it == a.id) c.id else it }.filter { it != b.id }
                tf.proRel[a.id]?.let { tf.proRel[c.id] = it }; tf.promo[a.id]?.let { tf.promo[c.id] = it }
                w.news.add(News(w.day, "${c.short} begins", "${a.name} and ${b.name} play as one league from $season: ${c.teamIds.size} teams. Their past seasons stay in each league's history."))
            }
            "split" -> {
                val l = w.leagues.getOrNull(param(p, "l")?.toIntOrNull() ?: -1)?.takeIf { live(it) } ?: return false
                val sa = param(p, "a")?.split('.') ?: return false
                val sb = param(p, "b")?.split('.') ?: return false
                val subs = sa + sb
                val parts = listOf(sa, sb).map { part -> League(w.leagues.size, l.titleId, "${macroOf(l.region)}-${part.joinToString(".")}", 1,
                    "${l.name} ${part.joinToString("/") { GameData.regionShort(it) }}", "${l.short} ${GameData.regionShort(part.first())}", since = season).also { it.predecessors.add(l.id); w.leagues.add(it) } }
                // Each club to the part of its home region (clubs without one: the smaller part).
                for (team in clubs(w, l)) {
                    val h = home(w, team, subs)
                    val to = if (h != null && h in sa) parts[0] else if (h != null) parts[1] else parts.minBy { it.teamIds.size }
                    Season.moveTeam(w, team, to)
                }
                if (parts.any { it.teamIds.size < 4 }) {
                    // Not enough clubs on one side any more: undo (clubs back to the league).
                    for (pt in parts) { for (tid in pt.teamIds.toList()) Season.moveTeam(w, w.teams[tid], l); pt.until = season - 1 }
                    return false
                }
                for (low in w.leagues.filter { it.upperId == l.id }) low.upperId = parts[0].id
                parts[0].lowerId = l.lowerId
                l.until = season - 1; l.successors.addAll(parts.map { it.id })
                for (key in Formats.KEYS) {
                    val s = tf.seeds["$key:${l.id}"] ?: continue
                    tf.seeds["$key:${parts[0].id}"] = maxOf(1, (s + 1) / 2); tf.seeds["$key:${parts[1].id}"] = maxOf(1, s / 2)
                }
                tf.rank = tf.rank.flatMap { if (it == l.id) parts.map { p2 -> p2.id } else listOf(it) }
                w.news.add(News(w.day, "${l.short} splits", "From $season, ${parts[0].name} (${parts[0].teamIds.size} teams) and ${parts[1].name} (${parts[1].teamIds.size} teams). Past seasons stay in ${l.short}'s history."))
            }
            else -> return false
        }
        dev.uem.em.mgmt.Academies.ensureLeagues(w)
        @Suppress("UNUSED_VARIABLE") val unused = t
        return true
    }
}

package dev.uem.em.mgmt

import dev.uem.em.game.BoxStore
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Counters
import dev.uem.em.sim.Draft
import dev.uem.em.sim.Patch
import dev.uem.em.titles.Catalogue

/**
 * Opponent scouting report from what a team has shown: its bans and first picks (recorded drafts),
 * each player's most played and most comfortable characters, side and map results, form, and the
 * analysts' reading of its ban approach. Suggestions follow from the current patch's counters.
 */
object Scouting {
    class PlayerNote(val playerId: Int, val played: List<Pair<String, Int>>, val comfort: List<Pair<String, Double>>)
    class Report(
        val team: Int, val series: Int, val games: Int, val wins: Int,
        val bans: List<Pair<String, Int>>, val firstPicks: List<Pair<String, Int>>, val bansAgainst: List<Pair<String, Int>>,
        val players: List<PlayerNote>, val sideWins: IntArray, val sideGames: IntArray, val maps: List<Triple<String, Int, Int>>,
        val avgLength: Int, val avgKills: Double, val form: String, val banApproach: String, val ideas: List<String>,
    )

    fun report(w: World, teamId: Int, opponent: Int = -1, n: Int = 10): Report {
        val team = w.teams[teamId]
        val t = Catalogue.get(team.titleId)
        val fixtures = w.fixtures.filter { it.done && (it.a == teamId || it.b == teamId) }.sortedByDescending { it.day }.take(n)
        val bans = HashMap<String, Int>(); val first = HashMap<String, Int>(); val against = HashMap<String, Int>()
        val played = HashMap<Int, HashMap<String, Int>>()
        val sideW = IntArray(2); val sideG = IntArray(2)
        val maps = HashMap<String, IntArray>()
        var games = 0; var wins = 0; var len = 0L; var kills = 0.0
        val k = t.statIndex["k"]
        for (f in fixtures) {
            val rec = BoxStore.get(w, f.id) ?: continue
            val me = if (f.a == teamId) 0 else 1
            for (g in rec.games) {
                if (g.lines.isEmpty()) continue
                games++
                if (g.winner == me) wins++
                len += g.duration
                if (k != null) kills += g.lines.filter { it.team == me }.sumOf { it.s.getOrElse(k) { 0.0 } }
                val side = if (t.isMoba) (if (g.blue == me) 0 else 1) else (if (g.blue == me) 0 else 1)
                sideG[side]++; if (g.winner == me) sideW[side]++
                if (!t.isMoba) maps.getOrPut(g.mapId) { IntArray(2) }.let { it[0]++; if (g.winner == me) it[1]++ }
                var firstSeen = false
                for (e in g.draftOrder) {
                    val p = e.split('|'); if (p.size < 3) continue
                    val tm = p[1].toIntOrNull() ?: continue
                    if (p[0] == "b") { if (tm == me) bans.merge(p[2], 1, Int::plus) else against.merge(p[2], 1, Int::plus) }
                    else if (tm == me && !firstSeen) { first.merge(p[2], 1, Int::plus); firstSeen = true }
                }
                for (l in g.lines) if (l.team == me && l.charId.isNotEmpty()) played.getOrPut(l.playerId) { HashMap() }.merge(l.charId, 1, Int::plus)
            }
        }
        val lineup = team.lineup.filter { it >= 0 }.ifEmpty { dev.uem.em.sim.Lineups.auto(w, team).filter { it >= 0 } }
        val notes = lineup.map { pid ->
            val p = w.players[pid]
            PlayerNote(pid, played[pid]?.entries?.sortedByDescending { it.value }?.take(4)?.map { it.key to it.value } ?: emptyList(),
                p.profChar.entries.sortedByDescending { it.value }.take(3).map { it.key to it.value })
        }
        val last = fixtures.take(5).joinToString("") { if (it.winner == teamId) "W" else "L" }
        // Ideas from the current patch: ban the star's comfort, counter their most played characters.
        val ideas = ArrayList<String>()
        if (t.characters.isNotEmpty()) {
            val patch = Patch.of(w, t)
            val star = notes.maxByOrNull { w.players[it.playerId].ca }
            star?.comfort?.firstOrNull()?.let { (cid, v) -> ideas.add("${w.players[star.playerId].handle} is their best player: ${t.charIndex[cid]?.name ?: cid} (mastery ${v.toInt()}) is a target ban.") }
            val most = notes.flatMap { it.played }.sortedByDescending { it.second }.take(3)
            for ((cid, _) in most) {
                val ctr = Counters.countersOf(t, patch, cid, 2).first
                if (ctr.isNotEmpty()) ideas.add("Against their ${t.charIndex[cid]?.name ?: cid}: ${ctr.joinToString(" or ") { t.charIndex[it.first]?.name ?: it.first }} counter it on this patch.")
            }
            bans.entries.maxByOrNull { it.value }?.let { (cid, c) -> if (c >= 2) ideas.add("They have banned ${t.charIndex[cid]?.name ?: cid} $c times: have a second option ready.") }
        } else if (maps.isNotEmpty()) {
            maps.entries.maxByOrNull { it.value[1] * 10 + it.value[0] }?.let { ideas.add("Their best map lately: ${t.maps.firstOrNull { m -> m.id == it.key }?.name ?: it.key} (${it.value[1]}-${it.value[0] - it.value[1]}); consider banning it.") }
            maps.entries.filter { it.value[0] >= 2 && it.value[1] * 2 < it.value[0] }.minByOrNull { it.value[1].toDouble() / it.value[0] }?.let { ideas.add("They struggle on ${t.maps.firstOrNull { m -> m.id == it.key }?.name ?: it.key} (${it.value[1]}-${it.value[0] - it.value[1]}).") }
        }
        val sideNames = if (t.isMoba) (if (t.id == "dota2") "Radiant" to "Dire" else "blue" to "red") else "attack-first" to "defence-first"
        if (sideG[0] >= 3 && sideG[1] >= 3) {
            val a = sideW[0].toDouble() / sideG[0]; val b = sideW[1].toDouble() / sideG[1]
            if (kotlin.math.abs(a - b) > 0.2) ideas.add("They do better on ${if (a > b) sideNames.first else sideNames.second} side (${(maxOf(a, b) * 100).toInt()}% vs ${(minOf(a, b) * 100).toInt()}%).")
        }
        return Report(teamId, fixtures.size, games, wins,
            bans.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value },
            first.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            against.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            notes, sideW, sideG, maps.entries.sortedByDescending { it.value[0] }.map { Triple(it.key, it.value[0], it.value[1]) },
            if (games > 0) (len / games).toInt() else 0, if (games > 0) kills / games else 0.0, last,
            if (t.characters.isNotEmpty() && t.draft.mode == "sequential") Draft.BAN_TENDENCY[Draft.banTendency(w, team)] else "", ideas)
    }
}

package dev.uem.em.sim

import dev.uem.em.model.GameRecord
import dev.uem.em.model.ShownStat

/**
 * What the two teams know about each other going into a draft (series team indexes): what each side
 * played, won with and how well its players did on it in the earlier games of this series, each
 * side's own bans and whether they helped, and — more faintly — what each team has shown in its
 * recent official matches. The draft adapts to it: bans go to what is beating you, bans that did not
 * help are dropped, winners keep what works and losers move off what lost.
 */
class SeriesIntel(
    /** Earlier games of this series. */
    val games: Int,
    /** Per side: character → games played with it in this series. */
    val played: Array<Map<String, Int>>,
    /** Per side: character → games won with it in this series. */
    val won: Array<Map<String, Int>>,
    /** Per side: character → mean game rating of the player on it in this series. */
    val rating: Array<Map<String, Double>>,
    /** Per side: character → games this side banned it in this series. */
    val bannedBy: Array<Map<String, Int>>,
    /** Per side: character → games this side banned it and still lost (the ban did not help). */
    val bannedLost: Array<Map<String, Int>>,
    /** Series wins so far. */
    val wins: IntArray,
    /** Winner of the last game (-1: none yet). */
    val lastWinner: Int,
    /** Per side: player id → character played in the last game. */
    val lastChar: Array<Map<Int, String>>,
    /** Per side: recent official matches before this series (decayed games, wins by character). */
    val recent: Array<Map<String, ShownStat>>,
    /** Per side: its bans in the last game. */
    val lastBans: Array<List<String>> = arrayOf(emptyList(), emptyList()),
) {
    val any get() = games > 0 || recent[0].isNotEmpty() || recent[1].isNotEmpty()

    /**
     * How strongly [side]'s draft is shaped by what its opponent showed with [c]: games played and
     * won with it (wins count most), how well its player did on it, a bonus when it came back game
     * after game, and a quarter-weight share of the recent official matches. 0 when never shown.
     */
    fun threat(side: Int, c: String): Double {
        val e = 1 - side
        var v = 0.0
        val pl = played[e][c] ?: 0
        if (pl > 0) {
            val wn = won[e][c] ?: 0
            val rf = (rating[e][c] ?: 1.0).coerceIn(0.5, 1.8)
            v = (0.3 * pl + 0.6 * wn) * rf + (if (pl >= 2 && wn >= 1) 0.4 else 0.0) - (if (wn == 0) 0.2 * pl else 0.0)
        }
        recent[e][c]?.let { r -> if (r.g >= 1f) v += 0.25 * (0.3 * r.g + 0.9 * r.w) / (1.0 + 0.15 * r.g) }
        return v.coerceIn(-0.4, 2.6)
    }

    /** What [side] itself did with [c]: (games played, games won, mean rating) in this series. */
    fun own(side: Int, c: String) = Triple(played[side][c] ?: 0, won[side][c] ?: 0, rating[side][c] ?: 1.0)

    companion object {
        private val EMPTY = arrayOf<Map<String, ShownStat>>(emptyMap(), emptyMap())
        val NONE = of(emptyList())

        fun of(games: List<GameRecord>, recent: Array<Map<String, ShownStat>> = EMPTY): SeriesIntel {
            val played = Array(2) { HashMap<String, Int>() }
            val won = Array(2) { HashMap<String, Int>() }
            val rSum = Array(2) { HashMap<String, Double>() }
            val bannedBy = Array(2) { HashMap<String, Int>() }
            val bannedLost = Array(2) { HashMap<String, Int>() }
            val wins = IntArray(2)
            for (g in games) {
                if (g.lines.isEmpty()) continue
                wins[g.winner]++
                for (l in g.lines) {
                    if (l.charId.isEmpty() || l.team !in 0..1) continue
                    played[l.team].merge(l.charId, 1, Int::plus)
                    if (l.team == g.winner) won[l.team].merge(l.charId, 1, Int::plus)
                    rSum[l.team].merge(l.charId, l.rating, Double::plus)
                }
                for (e in g.draftOrder) {
                    if (!e.startsWith("b|")) continue
                    val p = e.split('|'); if (p.size < 3) continue
                    val tm = p[1].toIntOrNull()?.takeIf { it in 0..1 } ?: continue
                    bannedBy[tm].merge(p[2], 1, Int::plus)
                    if (g.winner != tm) bannedLost[tm].merge(p[2], 1, Int::plus)
                }
            }
            val last = games.lastOrNull { it.lines.isNotEmpty() }
            val lastChar = Array(2) { s -> last?.lines?.filter { it.team == s && it.charId.isNotEmpty() }?.associate { it.playerId to it.charId } ?: emptyMap() }
            val rating = Array(2) { s -> rSum[s].mapValues { (c, v) -> v / (played[s][c] ?: 1) } }
            val lastBans = Array(2) { s -> last?.draftOrder?.mapNotNull { e -> val p = e.split('|'); if (p.size >= 3 && p[0] == "b" && p[1] == "$s") p[2] else null } ?: emptyList() }
            return SeriesIntel(games.count { it.lines.isNotEmpty() }, arrayOf(played[0], played[1]), arrayOf(won[0], won[1]), rating, arrayOf(bannedBy[0], bannedBy[1]),
                arrayOf(bannedLost[0], bannedLost[1]), wins, last?.winner ?: -1, lastChar, recent, lastBans)
        }

        /**
         * After an official series: a team's record of what it showed fades (×0.7 per series, so about
         * the last three or four series count) and this series' characters are added, games and wins.
         */
        fun remember(shown: MutableMap<String, ShownStat>, games: List<GameRecord>, side: Int) {
            if (games.none { g -> g.lines.any { it.team == side && it.charId.isNotEmpty() } }) return
            val it = shown.entries.iterator()
            while (it.hasNext()) { val e = it.next(); e.value.g *= 0.7f; e.value.w *= 0.7f; if (e.value.g < 0.3f) it.remove() }
            for (g in games) for (l in g.lines) if (l.team == side && l.charId.isNotEmpty()) {
                val st = shown.getOrPut(l.charId) { ShownStat() }
                st.g += 1f; if (g.winner == side) st.w += 1f
            }
            // Keep the record small: the twelve most shown characters.
            if (shown.size > 12) shown.entries.sortedWith(compareBy({ it.value.g }, { it.key })).take(shown.size - 12).map { it.key }.forEach { shown.remove(it) }
        }
    }
}

/**
 * Why a draft step happened, when the series (or the teams' recent matches) shaped it. Stored with
 * the game as "step|kind|n|character" ([GameRecord.draftNotes]); several notes can share a step.
 */
object DraftNotes {
    class Note(val step: Int, val kind: String, val n: Int, val ref: String)

    fun parse(s: String): Note? {
        val p = s.split('|'); if (p.size < 2) return null
        return Note(p[0].toIntOrNull() ?: return null, p[1], p.getOrNull(2)?.toIntOrNull() ?: 0, p.getOrElse(3) { "" })
    }

    fun encode(step: Int, kind: String, n: Int = 0, ref: String = "") = "$step|$kind|$n|$ref"

    private fun games(n: Int) = if (n == 1) "game" else "games"

    /** The note as a short line; [opp] is the acting team's opponent. */
    fun text(t: dev.uem.em.titles.TitleDef, kind: String, n: Int, ref: String, opp: String): String {
        val name = t.charIndex[ref]?.name ?: ref
        return when (kind) {
            "bk" -> "the heart of $opp's comp: won $n ${games(n)} with it"
            "bw" -> "$opp won $n ${games(n)} with it this series"
            "bp" -> "$opp played it in $n ${games(n)}"
            "br" -> "$opp has been winning with it lately"
            "bd" -> "replaces last game's $name ban"
            "pk" -> "kept: won $n ${games(n)} with it"
            "ps" -> "switched off $name after the loss"
            "pd" -> "taken away from $opp (won $n ${games(n)} with it)"
            "po" -> "$opp banned it before and left it open"
            "pa" -> "answers $name, which $opp played in $n ${games(n)}"
            else -> ""
        }
    }

    /** A note as advice to the team about to act ("kind|n|character", no step): for the draft assistant. */
    fun advice(t: dev.uem.em.titles.TitleDef, code: String): String? {
        val p = code.split('|')
        val n = p.getOrNull(1)?.toIntOrNull() ?: 0
        val name = t.charIndex[p.getOrElse(2) { "" }]?.name ?: p.getOrElse(2) { "" }
        return when (p[0]) {
            "bk" -> "the heart of their comp: won $n ${games(n)} with it"
            "bw" -> "they won $n ${games(n)} with it this series"
            "bp" -> "they played it in $n ${games(n)}"
            "br" -> "they have been winning with it lately"
            "pk" -> "you won $n ${games(n)} with it"
            "pd" -> "they won $n ${games(n)} with it: take it away"
            "po" -> "they banned it before and left it open"
            "pa" -> "answers their $name"
            else -> null
        }
    }

    /** All notes of one step as text. */
    fun forStep(t: dev.uem.em.titles.TitleDef, notes: List<String>, step: Int, opp: String): List<String> =
        notes.mapNotNull { parse(it) }.filter { it.step == step }.map { text(t, it.kind, it.n, it.ref, opp) }.filter { it.isNotEmpty() }
}

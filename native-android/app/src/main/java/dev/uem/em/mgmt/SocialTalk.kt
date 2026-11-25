package dev.uem.em.mgmt

import dev.uem.em.core.Rng
import dev.uem.em.game.BoxStore
import dev.uem.em.model.CompTier
import dev.uem.em.model.Fixture
import dev.uem.em.model.GameRecord
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.titles.Neutrals
import dev.uem.em.titles.TitleDef
import kotlin.math.roundToInt

/**
 * What fans actually talk about after a series, built from the series itself: each player's line with
 * the character played, game lengths and kill scores, map scores and overtimes, objectives taken,
 * reverse sweeps and deciders, the standings and the next match, the head-to-head, a player beating their
 * old team, streaks and upsets. Each fact is turned into a comment by who is speaking (a winning fan, a
 * losing fan, a neutral, an analyst, a troll) with many phrasings; a thread never uses the same phrasing
 * twice, so two series never read the same.
 */
internal object SocialTalk {
    /** A fact about the series: who it is about (+1 winner, -1 loser, 0 both), good or bad for them, a short phrase, its subject. */
    class Fact(val about: Int, val good: Boolean, val core: String, val subject: String, val weight: Double, val kind: String)

    class Ctx(val w: World, val t: TitleDef, val f: Fixture, val games: List<GameRecord>, val win: Team, val lose: Team, val winIdx: Int, val rng: Rng)

    fun ctx(w: World, t: TitleDef, f: Fixture, win: Team, lose: Team, rng: Rng): Ctx {
        val games = BoxStore.get(w, f.id)?.games.orEmpty()
        return Ctx(w, t, f, games, win, lose, if (f.winner == f.a) 0 else 1, rng)
    }

    private fun charName(c: Ctx, id: String) = c.t.charIndex[id]?.name ?: ""
    private fun h(c: Ctx, pid: Int) = c.w.players.getOrNull(pid)?.handle ?: "?"
    private fun mins(s: Int) = "${s / 60}:%02d".format(s % 60)
    private fun gameWord(c: Ctx) = if (c.t.isMoba) "game" else "map"

    /** Every fact the series offers. */
    fun facts(c: Ctx): List<Fact> {
        val out = ArrayList<Fact>()
        val t = c.t
        val k = t.statIndex["k"]; val d = t.statIndex["d"]; val a = t.statIndex["a"]
        fun st(l: dev.uem.em.model.Line, i: Int?) = i?.let { l.s.getOrNull(it) } ?: 0.0
        fun side(team: Int) = if (team == c.winIdx) 1 else -1
        val ws = c.games.count { it.winner == c.winIdx }; val ls = c.games.size - ws
        // Series shape.
        if (c.games.size >= 3) {
            val firstTwoLost = c.games.take(2).all { it.winner != c.winIdx }
            val firstLost = c.games.firstOrNull()?.winner != c.winIdx
            if (firstTwoLost && c.f.bestOf >= 5) out.add(Fact(1, true, "the reverse sweep from 0–2 down", c.win.short, 3.0, "shape"))
            else if (firstLost) out.add(Fact(1, true, "coming back after dropping ${gameWord(c)} 1", c.win.short, 1.6, "shape"))
            if (ls == ws - 1) out.add(Fact(0, true, "a full ${c.games.size}-${gameWord(c)} series", "", 1.4, "shape"))
        } else if (c.games.size >= 2 && ls == 0) out.add(Fact(1, true, "a clean ${ws}–0", c.win.short, 1.4, "shape"))
        // Per game.
        for ((gi, g) in c.games.withIndex()) {
            val gw = if (g.winner == c.winIdx) c.win else c.lose
            if (t.isMoba) {
                if (g.duration in 1 until 24 * 60) out.add(Fact(side(g.winner), true, "${gw.short} closing ${gameWord(c)} ${gi + 1} in ${mins(g.duration)}", gw.short, 1.5, "game"))
                if (g.duration > 38 * 60) out.add(Fact(0, true, "${gameWord(c)} ${gi + 1} going ${mins(g.duration)}", "", 1.3, "game"))
                if (g.score.size == 2) {
                    val kw = g.score[g.winner.coerceIn(0, 1)]; val kl = g.score[1 - g.winner.coerceIn(0, 1)]
                    if (kl > kw) out.add(Fact(side(g.winner), true, "${gw.short} winning ${gameWord(c)} ${gi + 1} despite losing the kills $kw–$kl", gw.short, 1.8, "game"))
                    else if (kw >= kl + 15) out.add(Fact(side(g.winner), true, "a $kw–$kl stomp in ${gameWord(c)} ${gi + 1}", gw.short, 1.3, "game"))
                }
                // Objectives taken (drakes, Lords, Roshan...).
                val cnt = HashMap<String, IntArray>()
                val kinds = Neutrals.of(c.w, t.id)
                for (code in g.objs) {
                    val c2 = code shr 1; val team = code and 1
                    val name = when { c2 >= 1000 -> null; c2 >= 500 -> t.moba?.objectives?.getOrNull(c2 - 500)?.name; else -> kinds.getOrNull(c2 - 1)?.name } ?: continue
                    cnt.getOrPut(name) { IntArray(2) }[team]++
                }
                for ((name, n) in cnt) for (s in 0..1) if (n[s] >= 3) {
                    val tm = if (s == c.winIdx) c.win else c.lose
                    out.add(Fact(side(s), true, "${tm.short} taking ${n[s]} ${name}s in ${gameWord(c)} ${gi + 1}", tm.short, 1.1, "objective"))
                }
            } else {
                val map = t.maps.firstOrNull { it.id == g.mapId }?.name ?: g.mapId
                if (g.score.size == 2) {
                    val hi = maxOf(g.score[0], g.score[1]); val lo = minOf(g.score[0], g.score[1])
                    val need = t.tactical?.roundsToWin ?: 13
                    when {
                        hi > need -> out.add(Fact(side(g.winner), true, "${gw.short} edging $map $hi–$lo in overtime", gw.short, 2.0, "game"))
                        lo <= 4 -> out.add(Fact(side(g.winner), true, "${gw.short} running $map $hi–$lo", gw.short, 1.4, "game"))
                        hi - lo <= 2 -> out.add(Fact(0, true, "$map going $hi–$lo", "", 1.2, "game"))
                    }
                }
            }
            // The best and the worst line of each game, with the character played.
            val best = g.lines.maxByOrNull { it.rating }
            if (best != null) {
                val ch = charName(c, best.charId)
                out.add(Fact(side(best.team), true, "${h(c, best.playerId)}${if (ch.isNotEmpty()) " on $ch" else ""} going ${st(best, k).roundToInt()}/${st(best, d).roundToInt()}/${st(best, a).roundToInt()} in ${gameWord(c)} ${gi + 1}", h(c, best.playerId), 2.0, "player"))
            }
            val worst = g.lines.filter { it.team != g.winner }.maxByOrNull { st(it, d) - st(it, k) * 0.5 }
            if (worst != null && st(worst, d) >= (if (t.isMoba) 6.0 else 18.0)) {
                val ch = charName(c, worst.charId)
                out.add(Fact(side(worst.team), false, "${h(c, worst.playerId)}${if (ch.isNotEmpty()) " on $ch" else ""} dying ${st(worst, d).roundToInt()} times in ${gameWord(c)} ${gi + 1}", h(c, worst.playerId), 1.6, "player"))
            }
        }
        // Over the series: deathless players, the loser's carry, signature picks.
        val per = HashMap<Int, DoubleArray>()
        val chars = HashMap<Int, MutableList<String>>()
        for (g in c.games) for (l in g.lines) {
            per.getOrPut(l.playerId) { DoubleArray(5) }.let { it[0] += st(l, k); it[1] += st(l, d); it[2] += st(l, a); it[3] += l.rating; it[4] = l.team.toDouble() }
            if (l.charId.isNotEmpty()) chars.getOrPut(l.playerId) { ArrayList() }.add(l.charId)
        }
        for ((pid, s) in per) {
            val team = s[4].toInt()
            if (s[1] == 0.0 && s[0] + s[2] >= 6 && c.games.size >= 2) out.add(Fact(side(team), true, "${h(c, pid)} not dying once in ${c.games.size} ${gameWord(c)}s", h(c, pid), 2.2, "player"))
            val picks = chars[pid].orEmpty()
            val again = picks.groupingBy { it }.eachCount().maxByOrNull { it.value }
            if (again != null && again.value >= 2 && charName(c, again.key).isNotEmpty()) out.add(Fact(side(team), team == c.winIdx, "${h(c, pid)} going back to ${charName(c, again.key)} ${again.value} times", h(c, pid), 1.0, "player"))
        }
        per.filter { it.value[4].toInt() != c.winIdx }.maxByOrNull { it.value[3] }?.let { (pid, s) ->
            if (s[3] / c.games.size.coerceAtLeast(1) > 1.08) out.add(Fact(-1, true, "${h(c, pid)} putting up ${s[0].roundToInt()}/${s[1].roundToInt()}/${s[2].roundToInt()} in a losing effort", h(c, pid), 1.8, "player"))
        }
        // A player against his former organisation.
        for ((pid, s) in per) {
            val p = c.w.players.getOrNull(pid) ?: continue
            val team = s[4].toInt()
            val opp = if (team == c.winIdx) c.lose else c.win
            if (p.career.any { c.w.teams.getOrNull(it.teamId)?.orgId == opp.orgId } && p.teamId >= 0 && c.w.teams[p.teamId].orgId != opp.orgId)
                out.add(Fact(side(team), team == c.winIdx, "${p.handle} ${if (team == c.winIdx) "beating" else "losing to"} former team ${opp.short}", p.handle, 2.0, "story"))
        }
        // Standings, the head-to-head, streaks, an upset.
        val comp = c.w.comps[c.f.compId]
        val stage = comp.stages.getOrNull(c.f.stageIdx)
        stage?.standings?.firstOrNull { it.teamId == c.win.id }?.let { r -> if (r.w + r.l >= 3) out.add(Fact(1, true, "${c.win.short} moving to ${r.w}–${r.l}", c.win.short, 1.0, "table")) }
        stage?.standings?.firstOrNull { it.teamId == c.lose.id }?.let { r -> if (r.w + r.l >= 3) out.add(Fact(-1, false, "${c.lose.short} sitting at ${r.w}–${r.l}", c.lose.short, 1.0, "table")) }
        val h2h = c.w.fixtures.filter { it.done && it.id != c.f.id && it.day <= c.f.day && ((it.a == c.win.id && it.b == c.lose.id) || (it.a == c.lose.id && it.b == c.win.id)) }
        if (h2h.size >= 2) {
            val wW = h2h.count { it.winner == c.win.id }
            if (wW == 0) out.add(Fact(1, true, "${c.win.short} finally beating ${c.lose.short} after ${h2h.size} straight losses to them", c.win.short, 2.4, "story"))
            else out.add(Fact(0, true, "the head-to-head now ${wW + 1}–${h2h.size - wW} for ${c.win.short}", c.win.short, 1.0, "story"))
        }
        fun streak(team: Team, won: Boolean): Int {
            var n = 0
            for (fx in c.w.fixtures.asReversed()) {
                if (!fx.done || fx.day > c.f.day || (fx.a != team.id && fx.b != team.id)) continue
                if ((fx.winner == team.id) == won) n++ else break
                if (n > 12) break
            }
            return n
        }
        streak(c.win, true).takeIf { it >= 4 }?.let { out.add(Fact(1, true, "${c.win.short} making it $it wins in a row", c.win.short, 1.6, "form")) }
        streak(c.lose, false).takeIf { it >= 3 }?.let { out.add(Fact(-1, false, "${c.lose.short} losing $it in a row now", c.lose.short, 1.7, "form")) }
        if (c.lose.elo > c.win.elo + 70) out.add(Fact(1, true, "${c.win.short} upsetting ${c.lose.short}", c.win.short, 2.4, "upset"))
        val next = c.w.fixtures.filter { !it.done && it.day > c.f.day && (it.a == c.win.id || it.b == c.win.id) && it.a >= 0 && it.b >= 0 }.minByOrNull { it.day }
        if (next != null) { val o = c.w.teams[if (next.a == c.win.id) next.b else next.a]; out.add(Fact(1, true, "${c.win.short} getting ${o.short} next", o.short, 0.7, "next")) }
        if (comp.tier == CompTier.WORLDS || comp.tier == CompTier.INTERNATIONAL) out.add(Fact(1, true, "${c.win.short} staying alive at ${comp.short.ifBlank { comp.name }}", c.win.short, 0.9, "stakes"))
        return out
    }

    // ------------------------------------------------------------------ phrasing

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    /** Templates by speaker and by whether the fact is good for the speaker's side. {c} = the fact, {s} = its subject. */
    private val WIN_GOOD = listOf("{C}. Absolute cinema.", "Did everyone see {c}? Unreal.", "{C} — I'm not okay.", "Remember when people doubted us? {C}.", "{C}. Book the parade.",
        "Not even surprised anymore. {C}.", "Rewatching {c} on loop tonight.", "{C} and the chat still had excuses lol", "Peak {s}. {C}.", "I've supported this team for years and {c} is why.", "{C}. Tell your friends.", "{C} — screenshot it, frame it.", "{C}. This is our year.", "I yelled so loud at {c} the neighbours knocked.", "{C}. Haters quiet today.", "Watching {c} live is why I wake up early.", "{C}. Chills.", "No notes. {C}.", "{C}, and they made it look easy.", "{C}. Coaching staff deserve a raise.", "{C}. Season ticket worth it already.", "That {c} clip is going on my wall.", "{C}. Tell me who stops this team.", "Grown adults crying over {c}. Me. I'm grown adults.", "{C}. Unreal from start to finish.")
    private val WIN_BAD = listOf("We won, but {c} has to be cleaned up.", "Happy with the W, less happy about {c}.", "{C}. We got away with one there.", "Not gonna lie, {c} scared me.", "{C} — let's not pretend that was clean.", "Won, but {c} would have cost us against a top team.", "{C}. Fix it before playoffs.", "Good win, though {c} is a warning sign.", "{C} needs a long vod review.", "Relieved more than happy. {C}.", "{C}. Not every win is a good one.", "We survived {c}. Barely.")
    private val LOSE_BAD = listOf("{C}. Can't keep happening.", "Be honest: {c} lost us this.", "{C}... I need a break from this team.", "I'm tired. {C}.", "{C} and the staff will say 'we learned a lot'.",
        "Every week it's something. This time {c}.", "{C}. Somebody explain that to me.", "Screenshot {c} and send it to the coaches.", "{C}. Unacceptable at this level.", "Look at {c} and tell me the plan is working.", "{C}. I'm out of patience.", "{C} and somehow nobody is accountable.", "{C}. Same story, different week.", "Rewatching {c} hurts more the second time.", "{C}. We deserve better.", "{C}. Fix this or change it.", "If {c} doesn't change, the season is over.", "Can't defend {c}. Sorry.", "{C}. Pain.", "{C}. Back to the drawing board.")
    private val LOSE_GOOD = listOf("Only positive today: {c}.", "{C} deserved better teammates.", "At least {c}. Build around that.", "{C}. Not everyone gave up.", "Hard loss, but {c} gives me hope.", "{C} — the bright spot of a dark day.", "Give {s} the ball more often. {C}.", "{C}. Keep that, change the rest.", "{C}, so it isn't all doom.", "One thing to take away: {c}.", "{C}. Proud of that at least.", "Heads up, {c} shows there's something here.", "{C}. Build the team around that.")
    private val NEUTRAL_GOOD = listOf("{C} is the moment of the day.", "This is why we watch: {c}.", "{C}. What a series to tune into.", "Whatever team you support, {c} was special.",
        "Clip of the week: {c}.", "{C}. Scene is healthy.", "Neutral fan here: {c} was class.", "{C}. Great advert for the game.", "Even as a rival fan, {c} was special.", "Recommend the vod for {c} alone.", "{C}. Top-tier entertainment.", "Highlight package will open with {c}.", "{C}. Pure quality.", "Nobody watching this region is bored. {C}.", "{C} might be the play of the split.", "{C}. Respect from a neutral.")
    private val NEUTRAL_BAD = listOf("Not to pile on, but {c}.", "{C}. That's not a top-level performance.", "Gonna get downvoted but {c} is the real story.", "Brutal: {c}.", "Not the prettiest stat: {c}.", "{C} won't make the highlight reel.", "Gotta be honest, {c} was rough to watch.", "{C}. Somebody had a bad day.", "Hard to watch {c}.", "{C}. Not their finest.", "As a neutral: {c} stood out, and not in a good way.", "{C}. Expected more.")
    private val ANALYST = listOf("Stat of the series: {c}.", "Worth noting: {c}.", "The numbers say it plainly — {c}.", "Underrated detail: {c}.", "If you only remember one thing: {c}.", "Key number from the series: {c}.", "Data point to keep in mind: {c}.", "Context matters, but {c} stands out.", "From the stat sheet: {c}.", "Quietly decisive: {c}.", "One for the season review: {c}.", "Tracking this trend: {c}.", "Not on the highlight reel, but {c} decided a lot.", "If you're building a narrative, start with {c}.", "{C} — that's the margin at this level.", "Film-room note: {c}.")
    private val TROLL = listOf("{C}? Wait until they play someone good.", "{C} lmao", "imagine {c} 💀", "{C}. Hall of shame material.", "{C}, and people call this a top team?", "{C}. Tier list says no.", "{C}. Uninstall speedrun.", "{C}. Bronze moment.", "Imagine paying salaries for {c}.", "{C}. Ok and? Still mid.", "{C} 🤡", "{C}. Bookmarking for the roast thread.", "{C}. Bot game energy.", "Did they practise {c}? Asking for a friend.")

    private val REPLY_AGREE = listOf("This. {s} was the difference.", "Exactly what I was thinking about {s}.", "Agreed, and nobody talks about it enough.", "Real.", "Couldn't have said it better.", "Underrated point about {s}.", "100%. {s} decided it.", "Finally someone said it.", "Took the words out of my mouth.", "Clip this comment.", "Hard agree on {s}.", "This should be pinned.", "Saying this since last split.", "Bang on.")
    private val REPLY_DISAGREE = listOf("Hard disagree, {s} wasn't the problem.", "One series. Relax.", "You say this every week.", "Lazy take, watch the games again.", "Overreaction of the year.", "Meh, {s} was fine. Look elsewhere.", "Small sample size.", "The vod says otherwise.", "Recency bias at its finest.", "Let's see after playoffs.", "Nope, the draft was the issue.", "Not buying it.", "You'd say the opposite if they won.", "Context: the other side was just better.")
    private val REPLY_JOKE = listOf("{s} fans are so loud when they win lol", "Somebody check on the {s} subforum", "Bookmarking this for the next loss.", "The copium in this thread is incredible.", "{s} fans after one good series: 📈📈📈", "The {s} subreddit is on fire rn", "Imagine being a {s} fan in this economy", "{s} hype train has no brakes", "Every thread becomes a {s} thread lol", "Not the {s} slander again 😭")

    /**
     * Up to [n] comments for a thread: facts drawn by weight (never the same fact or phrasing twice),
     * spoken by fans of either side, neutrals, analysts and the odd troll, some with replies.
     */
    fun comments(c: Ctx, facts: List<Fact>, n: Int, fan: (Team?) -> Social.Author, analyst: () -> Social.Author, troll: () -> Social.Author, score: (Double) -> Int): List<Social.Comment> {
        // About half the thread reacts to the series' facts; the other half brings something of its own
        // (a matchup, a record, the coach, the ladder, a fee, a prediction), so no two comments say the same thing.
        val own = angles(c).shuffled(java.util.Random(c.rng.int(0, Int.MAX_VALUE - 1).toLong()))
        val nOwn = minOf(own.size, (n + 1) / 2)
        val base = comments(c.rng, c.win, c.lose, facts, n - nOwn, fan, analyst, troll, score).toMutableList()
        for ((i, a) in own.take(nOwn).withIndex()) {
            val author = when (a.who) { 2 -> analyst(); 1 -> fan(c.win); -1 -> fan(c.lose); else -> fan(null) }
            val at = minOf(base.size, 1 + i * 2)
            base.add(at, Social.Comment(author, a.text, score(a.weight)))
        }
        return base
    }

    /** A comment with content of its own: [who] 1 winner's fan, -1 loser's fan, 0 neutral, 2 analyst. */
    class Angle(val who: Int, val text: String, val weight: Double)

    /**
     * Comments that each add a different piece of information: the closest lane matchup, a character's
     * record this season, a player's season numbers and age, the head coach, a solo queue rank, a transfer
     * fee, the home scene, a prediction from the table, a lineup suggestion. One of each kind at most.
     */
    fun angles(c: Ctx): List<Angle> {
        val out = ArrayList<Angle>()
        val w = c.w; val t = c.t; val rng = c.rng
        if (c.games.isEmpty()) return out
        val ki = t.statIndex["k"]; val di = t.statIndex["d"]; val ai = t.statIndex["a"]
        fun st(l: dev.uem.em.model.Line, i: Int?) = i?.let { l.s.getOrNull(it) } ?: 0.0
        class Tot(val pid: Int, val team: Int, val role: String) { var k = 0.0; var d = 0.0; var a = 0.0; var r = 0.0; var g = 0; val chars = ArrayList<String>() }
        val tot = LinkedHashMap<Int, Tot>()
        for (g in c.games) for (l in g.lines) tot.getOrPut(l.playerId) { Tot(l.playerId, l.team, l.roleId) }.let { x -> x.k += st(l, ki); x.d += st(l, di); x.a += st(l, ai); x.r += l.rating; x.g++; if (l.charId.isNotEmpty()) x.chars.add(l.charId) }
        fun side(team: Int) = if (team == c.winIdx) 1 else -1
        fun kda(x: Tot) = "${x.k.roundToInt()}/${x.d.roundToInt()}/${x.a.roundToInt()}"
        fun pl(pid: Int) = w.players.getOrNull(pid)
        val teamOf = { side: Int -> if (side == 1) c.win else c.lose }
        // 1. The lane matchup with the widest gap (or the closest one).
        val pairs = t.lineup.mapNotNull { r -> val a = tot.values.firstOrNull { it.role == r && it.team == c.winIdx }; val b = tot.values.firstOrNull { it.role == r && it.team != c.winIdx }; if (a == null || b == null) null else Triple(r, a, b) }
        pairs.maxByOrNull { (_, a, b) -> a.r / a.g - b.r / b.g }?.let { (r, a, b) ->
            val role = t.role(r).name.lowercase()
            out.add(Angle(0, rng.pick(listOf(
                "The $role matchup decided more than the scoreboard shows: ${pl(a.pid)?.handle} ${kda(a)} against ${pl(b.pid)?.handle} ${kda(b)}.",
                "Watch the $role lane on the vod. ${pl(a.pid)?.handle} had ${pl(b.pid)?.handle} under pressure all series (${kda(a)} vs ${kda(b)}).",
                "${pl(b.pid)?.handle} lost the $role duel to ${pl(a.pid)?.handle} and the series went with it.")), 1.4))
        }
        pairs.minByOrNull { (_, a, b) -> kotlin.math.abs(a.r / a.g - b.r / b.g) }?.takeIf { pairs.size >= 3 }?.let { (r, a, b) ->
            out.add(Angle(2, "Even matchup of the series: ${pl(a.pid)?.handle} and ${pl(b.pid)?.handle} at ${t.role(r).short} were level all the way (${kda(a)} and ${kda(b)}).", 1.0))
        }
        // 2. A character's record this season.
        tot.values.flatMap { x -> x.chars.map { it to x } }.groupBy { it.first }.maxByOrNull { it.value.size }?.let { (cid, uses) ->
            val name = t.charIndex[cid]?.name ?: return@let
            val u = w.charStats["${w.season}|$cid"]
            if (u != null && u[0] >= 5) {
                val wr = 100 * u[1] / maxOf(1, u[0])
                out.add(Angle(2, rng.pick(listOf(
                    "$name this season: ${u[0]} games, $wr% wins, ${u[2]} bans. ${if (wr >= 55) "Still nobody has an answer." else if (wr <= 45) "Picked a lot, wins little." else "A coin flip, but a popular one."}",
                    "Funny how $name keeps showing up (${uses.size} times here). League-wide it's at $wr% over ${u[0]} games.")), 1.1))
            }
        }
        // 3. Season numbers and age of the series' best player.
        tot.values.maxByOrNull { it.r / it.g }?.let { x ->
            val p = pl(x.pid) ?: return@let
            val age = dev.uem.em.core.Dates.ageAt(p.birth, w.day)
            val line = p.stats.lastOrNull { it.season == w.season && it.titleId == t.id && it.teamId == p.teamId }
            val tm = teamOf(side(x.team))
            if (line != null && line.gp >= 6) {
                val avg = line.ratingSum / line.gp
                out.add(Angle(side(x.team), rng.pick(listOf(
                    "${p.handle} is on %.2f rating over ${line.gp} games this season. That is not a hot streak any more, that's the level.".format(avg),
                    "People still sleep on ${p.handle}: ${line.w}–${line.l} in games this season and %.2f average rating.".format(avg))), 1.3))
            }
            if (age <= 19) out.add(Angle(side(x.team), "${p.handle} is $age. Let that sink in.", 1.0))
            else if (age >= 27) out.add(Angle(0, "${p.handle} at $age still doing this. Longevity is underrated.", 0.9))
            // 4. The home scene.
            val country = dev.uem.em.data.GameData.countries[p.nat]?.name
            if (country != null && tm.region != dev.uem.em.data.GameData.regionOf(p.nat))
                out.add(Angle(0, "Big night for $country: ${p.handle} carrying ${tm.short} abroad.", 0.9))
        }
        // 5. The head coach of each side.
        for (sd in listOf(1, -1)) {
            val tm = teamOf(sd)
            val hc = Staffing.members(w, tm).firstOrNull { it.role == "head-coach" } ?: continue
            out.add(Angle(sd, if (sd == 1) rng.pick(listOf("Credit to ${hc.name()}. The adjustments between ${if (t.isMoba) "games" else "maps"} were clear.", "${hc.name()} has ${tm.short} prepared. You can see the scrim work."))
                else rng.pick(listOf("Questions for ${hc.name()} after that one. Same plan every ${if (t.isMoba) "game" else "map"}.", "${hc.name()} needs answers before the next match. Nothing changed when it went wrong.")), 1.0))
            break
        }
        // 6. A solo queue rank.
        tot.values.mapNotNull { x -> pl(x.pid)?.takeIf { it.soloMmr > 0 }?.let { it to x } }.maxByOrNull { it.first.soloMmr }?.let { (p, x) ->
            out.add(Angle(side(x.team), "${p.handle} is ${SoloQueue.rank(p.titleId, p.soloMmr)} on the ladder right now. The hours show.", 0.8))
        }
        // 7. A fee that looks good or bad now.
        for (x in tot.values.sortedByDescending { kotlin.math.abs(it.r / it.g - 1.0) }) {
            val p = pl(x.pid) ?: continue
            val tr = w.transfers.lastOrNull { it.playerId == p.id && it.toTeam == p.teamId && it.fee > 0 } ?: continue
            val good = x.r / x.g >= 1.0
            out.add(Angle(side(x.team), if (good) "${dev.uem.em.core.formatMoney(tr.fee)} for ${p.handle} looks cheap today." else "${dev.uem.em.core.formatMoney(tr.fee)} for ${p.handle}, and that was the return?", 1.1))
            break
        }
        // 8. A prediction from the table, and a lineup suggestion for the losing side.
        val stage = w.comps[c.f.compId].stages.getOrNull(c.f.stageIdx)
        val rows = stage?.standings?.sortedByDescending { it.w * 2 - it.l }
        val pos = rows?.indexOfFirst { it.teamId == c.win.id } ?: -1
        if (rows != null && pos >= 0 && rows.size >= 6) out.add(Angle(1, if (pos < 3) "Calling it now: ${c.win.short} finish top two. Screenshot this." else "${c.win.short} are ${pos + 1}th and climbing. Playoffs are realistic.", 0.9))
        val worst = tot.values.filter { it.team != c.winIdx }.minByOrNull { it.r / it.g }
        if (worst != null) {
            val sub = c.lose.roster.mapNotNull { pl(it) }.firstOrNull { it.id !in tot.keys && it.roles.firstOrNull() == worst.role && !it.retired }
            if (sub != null) out.add(Angle(-1, "Not saying bench ${pl(worst.pid)?.handle}, but ${sub.handle} is sitting right there.", 1.0))
        }
        // 9. The patch: what this series' drafts kept going back to.
        val picks = c.games.flatMap { g -> g.lines.map { it.charId } }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().maxByOrNull { it.value }
        val pa = w.patches.lastOrNull { it.titleId == t.id }
        if (picks != null && picks.value >= 2 && pa != null) t.charIndex[picks.key]?.name?.let { name ->
            out.add(Angle(0, "Patch ${pa.version} in one sentence: $name in ${picks.value} of ${c.games.size} ${if (t.isMoba) "games" else "maps"} here.", 0.7))
        }
        return out
    }

    /** The same for any pair of sides: [win] is the side +1 facts are about, [lose] the side of -1 facts. */
    fun comments(rng: Rng, win: Team, lose: Team, facts: List<Fact>, n: Int, fan: (Team?) -> Social.Author, analyst: () -> Social.Author, troll: () -> Social.Author, score: (Double) -> Int): List<Social.Comment> {
        class Sides(val win: Team, val lose: Team)
        val c = Sides(win, lose)
        val left = facts.toMutableList()
        val used = HashSet<String>()
        val out = ArrayList<Social.Comment>()
        fun pickTemplate(list: List<String>): String = SocialVoice.template(rng, list, used)
        fun fill(tpl: String, f: Fact) = tpl.replace("{C}", cap(f.core)).replace("{c}", f.core).replace("{s}", f.subject.ifBlank { "that" })
        while (out.size < n && left.isNotEmpty()) {
            val f = rng.weighted(left) { it.weight }
            left.remove(f)
            val speaker = rng.float()
            val (author, tpl) = when {
                speaker < 0.12 -> analyst() to pickTemplate(ANALYST)
                speaker < 0.2 && f.good && f.about != 0 -> troll() to pickTemplate(TROLL)
                f.about == 1 -> fan(c.win) to pickTemplate(if (f.good) WIN_GOOD else WIN_BAD)
                f.about == -1 && speaker < 0.75 -> fan(c.lose) to pickTemplate(if (f.good) LOSE_GOOD else LOSE_BAD)
                else -> fan(null) to pickTemplate(if (f.good) NEUTRAL_GOOD else NEUTRAL_BAD)
            }
            val text = fill(tpl, f)
            val replies = ArrayList<Social.Comment>()
            if (rng.chance(0.45)) {
                val (rAuthor, list) = when (rng.int(0, 2)) {
                    0 -> fan(if (f.about == -1) c.lose else c.win) to REPLY_AGREE
                    1 -> fan(if (f.about == 1) c.lose else c.win) to REPLY_DISAGREE
                    else -> fan(null) to REPLY_JOKE
                }
                val subj = if (list === REPLY_JOKE) (if (f.about == -1) c.lose.short else c.win.short) else f.subject.ifBlank { if (f.about == -1) c.lose.short else c.win.short }
                replies.add(Social.Comment(rAuthor, pickTemplate(list).replace("{s}", subj), score(0.4)))
            }
            out.add(Social.Comment(author, text, score(f.weight), replies))
        }
        return out
    }

    /** Facts about a signing, for the transfer posts (+1: the buying team, -1: the selling team). */
    fun transferFacts(w: World, t: TitleDef, p: dev.uem.em.model.Player, to: Team, from: Team?, fee: Double): List<Fact> {
        val out = ArrayList<Fact>()
        val k = t.statIndex["k"]; val d = t.statIndex["d"]; val a = t.statIndex["a"]
        val age = dev.uem.em.core.Dates.ageAt(p.birth, w.day)
        if (fee > 0) {
            val value = Economy.marketValue(w, p)
            out.add(Fact(1, fee <= value * 1.1, "${to.short} paying ${dev.uem.em.core.formatMoney(fee)} for ${p.handle}${if (fee > value * 1.3) " (way over market value)" else ""}", p.handle, 1.5, "fee"))
            if (from != null) out.add(Fact(-1, true, "${from.short} cashing in ${dev.uem.em.core.formatMoney(fee)} on ${p.handle}", from.short, 1.0, "fee"))
        }
        p.stats.filter { it.titleId == t.id && it.gp >= 6 && it.season >= w.season - 1 }.maxByOrNull { it.season * 1000 + it.gp }?.let { l ->
            val per = { i: Int? -> if (i == null) 0.0 else l.s.getOrElse(i) { 0.0 } / l.gp }
            out.add(Fact(1, l.ratingSum / l.gp >= 1.0, "${p.handle} coming off a %.2f-rating season (%.1f/%.1f/%.1f a ${if (t.isMoba) "game" else "map"})".format(l.ratingSum / l.gp, per(k), per(d), per(a)), p.handle, 1.6, "form"))
        }
        val fromTier = from?.let { f -> if (f.parentId >= 0) 3 else w.leagues.getOrNull(f.leagueId)?.tier ?: 3 } ?: 3
        val toTier = w.leagues.getOrNull(to.leagueId)?.tier ?: 3
        if (fromTier > toTier && toTier == 1) out.add(Fact(1, true, "${p.handle} finally getting a shot in ${w.leagues[to.leagueId].short}", p.handle, 2.2, "step"))
        if (age <= 19) out.add(Fact(1, true, "a $age-year-old ${t.role(p.mainRole).name.lowercase()} joining ${to.short}", p.handle, 1.2, "age"))
        if (age >= 28) out.add(Fact(1, false, "${to.short} betting on a $age-year-old", p.handle, 1.0, "age"))
        val benched = to.lineup.filter { it >= 0 && it != p.id }.map { w.players[it] }.firstOrNull { it.mainRole == p.mainRole }
        if (benched != null) out.add(Fact(1, false, "${benched.handle} probably heading to the bench", benched.handle, 1.4, "bench"))
        if (from != null) {
            val years = p.career.filter { w.teams.getOrNull(it.teamId)?.orgId == from.orgId }.minOfOrNull { it.day }?.let { (w.day - it) / 365 } ?: 0
            if (years >= 2) out.add(Fact(-1, false, "${p.handle} leaving ${from.short} after $years years", p.handle, 1.8, "tenure"))
            if (from.rebuildUntil > w.day) out.add(Fact(-1, false, "${from.short} tearing the roster down", from.short, 1.6, "rebuild"))
        }
        if (p.career.dropLast(1).any { w.teams.getOrNull(it.teamId)?.orgId == to.orgId }) out.add(Fact(1, true, "${p.handle} coming back to ${to.short}", p.handle, 2.0, "reunion"))
        val top = Ambitions.ranked(w, p).firstOrNull()
        if (top != null && top.second >= 25) out.add(Fact(0, true, "${p.handle} choosing ${Ambitions.HINT[top.first]}", p.handle, 1.3, "ambition"))
        return out
    }

    /** The post-match thread's body: result, every game or map, the MVP, where it leaves both teams. */
    fun pmtBody(c: Ctx, mvp: Int): String {
        val t = c.t
        val k = t.statIndex["k"]; val d = t.statIndex["d"]; val a = t.statIndex["a"]
        val sb = StringBuilder()
        val sw = c.games.count { it.winner == c.winIdx }
        sb.append("${c.win.name} ${sw}–${c.games.size - sw} ${c.lose.name}\n")
        for ((gi, g) in c.games.withIndex()) {
            val gw = if (g.winner == c.winIdx) c.win else c.lose
            val best = g.lines.filter { it.team == g.winner }.maxByOrNull { it.rating }
            val line = best?.let { l -> " · ${h(c, l.playerId)}${t.charIndex[l.charId]?.name?.let { " ($it)" } ?: ""} ${k?.let { l.s[it].roundToInt() } ?: 0}/${d?.let { l.s[it].roundToInt() } ?: 0}/${a?.let { l.s[it].roundToInt() } ?: 0}" } ?: ""
            val detail = if (t.isMoba) "${mins(g.duration)}${if (g.score.size == 2) ", kills ${g.score[0]}–${g.score[1]}" else ""}" else "${t.maps.firstOrNull { it.id == g.mapId }?.name ?: g.mapId} ${g.score.joinToString("–")}"
            sb.append("${cap(gameWord(c))} ${gi + 1}: ${gw.short} — $detail$line\n")
        }
        if (mvp >= 0) sb.append("Series MVP: ${h(c, mvp)}\n")
        val stage = c.w.comps[c.f.compId].stages.getOrNull(c.f.stageIdx)
        for (tm in listOf(c.win, c.lose)) stage?.standings?.firstOrNull { it.teamId == tm.id }?.let { sb.append("${tm.short}: ${it.w}–${it.l} in ${stage.name}\n") }
        return sb.toString().trimEnd()
    }
}

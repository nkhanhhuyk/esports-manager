package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.game.BoxStore
import dev.uem.em.model.CompTier
import dev.uem.em.model.Fixture
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.TitleDef
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Fan culture: posts on four fictional social networks — Agora (forum threads with up-votes,
 * post-match threads, player ratings, polls), Chirp (short posts, reposts), Lens (picture posts) and
 * Fanbase (supporters' groups with reactions) — written by fans, analysts, the press and team
 * accounts about what happened (results, upsets, stomps, MVPs, poor games, titles), what is happening
 * (streaks, form, transfers, patches and metas) and what is coming (matchdays, rivalries,
 * predictions). Generated from the world on demand (nothing is saved), the same every time for the
 * same events. Fans praise, overreact, argue, mock and criticise performances — never people's
 * private lives — and team accounts only announce results and signings.
 */
object Social {
    const val AGORA = "agora"; const val CHIRP = "chirp"; const val LENS = "lens"; const val FANBASE = "fanbase"
    val PLATFORMS = listOf(AGORA, CHIRP, LENS, FANBASE)
    val PLATFORM_NAME = mapOf(AGORA to "Agora", CHIRP to "Chirp", LENS to "Lens", FANBASE to "Fanbase")
    val PLATFORM_DESC = mapOf(AGORA to "forum threads, ratings, polls", CHIRP to "quick takes", LENS to "pictures", FANBASE to "supporters' groups")
    /** Reaction kinds on Fanbase. */
    val REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "😡")

    class Author(val name: String, val handle: String, val kind: String, val teamId: Int = -1, val playerId: Int = -1)
    class Comment(val author: Author, val text: String, val score: Int, val replies: List<Comment> = emptyList())
    /** A picture: a team's crest, a scoreline between two crests, a player, a champion / hero / agent. */
    class Image(val kind: String, val teamA: Int = -1, val teamB: Int = -1, val playerId: Int = -1, val charId: String? = null, val caption: String = "")
    class Post(
        val key: String, val platform: String, val day: Int, val order: Double,
        val author: Author, val title: String?, val text: String, val tone: String, val titleId: String,
        val teams: List<Int>, val players: List<Int>,
        val score: Int, val reposts: Int, val reactions: IntArray, val comments: List<Comment>,
        val image: Image? = null, val ratings: List<Pair<Int, Double>> = emptyList(), val poll: List<Pair<String, Int>> = emptyList(),
        /** Where it was posted: a forum board, a fan group. */
        val place: String = "",
    )

    /** Tones (for the filter): what the post is. */
    val TONES = listOf("All", "Reactions", "Ratings", "Debates", "Appreciation", "Criticism", "Overreactions", "Predictions", "Memes", "News")
    private val TONE_OF = mapOf("reaction" to "Reactions", "rating" to "Ratings", "debate" to "Debates", "appreciation" to "Appreciation", "criticism" to "Criticism",
        "overreaction" to "Overreactions", "prediction" to "Predictions", "meme" to "Memes", "news" to "News", "hype" to "Predictions")
    fun toneLabel(tone: String) = TONE_OF[tone] ?: "Reactions"

    // ------------------------------------------------------------------ people
    private val ANALYSTS = listOf(Author("Nova Stats", "novastats", "analyst"), Author("Draft Diaries", "draftdiaries", "analyst"), Author("The Meta Report", "metareport", "analyst"),
        Author("Gold Diff Weekly", "golddiff", "analyst"), Author("Clutch Factor", "clutchfactor", "analyst"))
    private val PRESS = listOf(Author("Esports Wire", "esportswire", "press"), Author("Scene Report", "scenereport", "press"), Author("Transfer Window", "transferwindow", "press"))
    private val MEMES = listOf(Author("Esports Memes Daily", "memesdaily", "meme"), Author("Copium Central", "copiumcentral", "meme"), Author("Ranked Tears", "rankedtears", "meme"))

    private fun fan(rng: Rng, t: TitleDef, team: Team?): Author {
        val tag = team?.short?.filter { it.isLetterOrDigit() }?.lowercase() ?: t.short.lowercase().filter { it.isLetterOrDigit() }
        val n = rng.int(1, 99)
        val moba = t.isMoba
        val opts = listOf("${tag}_believer", "${tag}army$n", "copium_dealer$n", "silent_lurker$n", "analyst_wannabe", "${tag}tilidie", "certified_${tag}_hater",
            "kda_merchant", "late_game_enjoyer", "${tag}_fan_since_${2014 + rng.int(0, 10)}", "touch_grass_$n", "hot_take_machine", "${tag}copium", "neutral_viewer$n",
            if (moba) "baron_throw_survivor" else "eco_round_enjoyer", if (moba) "midgap_specialist" else "awp_or_nothing", if (moba) "draft_diff_$n" else "util_dump_$n", "${tag}_til_the_end")
        val h = rng.pick(opts)
        return Author(h.replace('_', ' ').replaceFirstChar { it.uppercase() }, h, "fan", team?.id ?: -1)
    }
    private fun teamAccount(team: Team) = Author(team.name, team.short.lowercase().filter { it.isLetterOrDigit() }, "team", team.id)

    // ------------------------------------------------------------------ helpers
    private class SeriesView(val f: Fixture, val t: TitleDef, val win: Team, val lose: Team, val sw: Int, val sl: Int, val comp: String, val tier: CompTier,
                             val mvp: Int, val best: Map<Int, DoubleArray>, val games: Int)

    /** Per player in a series: average rating, kills, deaths, assists, team (0 = fixture's a). */
    private fun lines(w: World, f: Fixture, t: TitleDef): Map<Int, DoubleArray> {
        val rec = BoxStore.get(w, f.id) ?: return emptyMap()
        val k = t.statIndex["k"]; val d = t.statIndex["d"]; val a = t.statIndex["a"]
        val out = HashMap<Int, DoubleArray>()
        for (g in rec.games) for (l in g.lines) {
            val s = out.getOrPut(l.playerId) { DoubleArray(6) }
            s[0] += l.rating; s[1] += k?.let { l.s.getOrNull(it) } ?: 0.0; s[2] += d?.let { l.s.getOrNull(it) } ?: 0.0; s[3] += a?.let { l.s.getOrNull(it) } ?: 0.0; s[4] = l.team.toDouble(); s[5] += 1.0
        }
        for (s in out.values) s[0] /= maxOf(1.0, s[5])
        return out
    }

    private fun kda(s: DoubleArray) = "${s[1].roundToInt()}/${s[2].roundToInt()}/${s[3].roundToInt()}"
    private fun tenScale(r: Double) = ((6.0 + (r - 1.0) * 5.0).coerceIn(1.0, 10.0) * 2).roundToInt() / 2.0
    private fun fansOf(w: World, team: Team) = w.orgs.getOrNull(team.orgId)?.fans ?: 10000.0
    private fun tierW(tier: CompTier) = when (tier) { CompTier.WORLDS -> 5.0; CompTier.INTERNATIONAL -> 3.5; CompTier.LEAGUE -> 2.0; CompTier.CUSTOM -> 1.5; CompTier.CHALLENGER -> 1.0 }
    private fun engagement(w: World, rng: Rng, weight: Double, vararg teams: Team): Int =
        (sqrt(teams.sumOf { fansOf(w, it) }.coerceAtLeast(2000.0)) * weight * rng.float(0.45, 1.6)).roundToInt().coerceAtLeast(3)
    private fun role(w: World, t: TitleDef, pid: Int): String = w.players.getOrNull(pid)?.roles?.firstOrNull()?.let { r -> t.lineupRoles.firstOrNull { it.id == r }?.name ?: t.role(r).name } ?: "player"
    private fun handle(w: World, pid: Int) = w.players.getOrNull(pid)?.handle ?: "?"
    private fun reactions(rng: Rng, total: Int, mood: String): IntArray {
        val wts = when (mood) {
            "joy" -> doubleArrayOf(3.0, 4.0, 0.6, 1.2, 0.1, 0.1)
            "sad" -> doubleArrayOf(1.2, 0.8, 0.4, 0.6, 3.0, 2.0)
            "angry" -> doubleArrayOf(0.8, 0.3, 1.0, 0.8, 1.5, 3.5)
            "funny" -> doubleArrayOf(1.0, 0.5, 4.0, 0.8, 0.2, 0.3)
            "wow" -> doubleArrayOf(1.5, 1.5, 0.6, 3.5, 0.3, 0.3)
            else -> doubleArrayOf(3.0, 1.0, 0.5, 0.8, 0.3, 0.3)
        }
        val sum = wts.sum()
        return IntArray(6) { (total * wts[it] / sum * rng.float(0.6, 1.4)).roundToInt() }
    }

    /** Fan mood of a team from its last results: -1 furious … +1 buzzing. */
    fun mood(w: World, teamId: Int): Double {
        val last = w.fixtures.asReversed().asSequence().filter { it.done && (it.a == teamId || it.b == teamId) }.take(6).toList()
        if (last.isEmpty()) return 0.0
        var m = 0.0; var wgt = 1.0
        for (f in last) { m += (if (f.winner == teamId) 1.0 else -1.0) * wgt; wgt *= 0.75 }
        return (m / 2.8).coerceIn(-1.0, 1.0)
    }

    // ------------------------------------------------------------------ the feed
    /**
     * Posts about the last ten days and the next five, newest first. [titleId] null = every game;
     * [teams] non-empty = only posts about those teams.
     */
    fun feed(w: World, titleId: String?, teams: Set<Int> = emptySet()): List<Post> {
        SocialVoice.begin()
        try { return SocialVoice.finish(build(w, titleId, teams)) } finally { SocialVoice.end() }
    }

    private fun build(w: World, titleId: String?, teams: Set<Int>): List<Post> {
        val out = ArrayList<Post>()
        val from = w.day - 10; val to = w.day + 5
        val recent = ArrayList<Pair<Fixture, Double>>(); val coming = ArrayList<Pair<Fixture, Double>>()
        for (i in w.fixtures.indices.reversed()) {
            val f = w.fixtures[i]
            if (f.day < from || f.day > to) continue
            if (titleId != null && f.titleId != titleId) continue
            if (teams.isNotEmpty() && f.a !in teams && f.b !in teams) continue
            if (f.a < 0 || f.b < 0) continue
            val c = w.comps.getOrNull(f.compId) ?: continue
            val ta = w.teams[f.a]; val tb = w.teams[f.b]
            val imp = tierW(c.tier) * (1.0 + sqrt((fansOf(w, ta) + fansOf(w, tb)) / 2e6)) * (if (w.isUserTeam(f.a) || w.isUserTeam(f.b) || f.a in w.followed || f.b in w.followed) 2.5 else 1.0)
            if (f.done && f.day <= w.day) recent.add(f to imp) else if (!f.done && f.day > w.day - 1) coming.add(f to imp)
        }
        for ((f, imp) in recent.sortedByDescending { it.second }.take(if (teams.isEmpty()) 28 else 40)) series(w, f, imp, out)
        for ((f, imp) in coming.sortedByDescending { it.second }.take(if (teams.isEmpty()) 10 else 14)) upcoming(w, f, imp, out)
        transfers(w, titleId, teams, out)
        patches(w, titleId, teams, out)
        streaks(w, titleId, teams, out)
        titlesWon(w, titleId, teams, out)
        form(w, titleId, teams, out)
        // With a team filter, only posts about those teams (a series' other supporters' group stays out).
        val kept = if (teams.isEmpty()) out else out.filter { p -> p.teams.isEmpty() || p.teams.any { it in teams } }
        return kept.sortedWith(compareByDescending<Post> { it.day }.thenByDescending { it.order })
    }

    // ------------------------------------------------------------------ finished series
    private fun series(w: World, f: Fixture, imp: Double, out: MutableList<Post>) {
        val t = Catalogue.get(f.titleId)
        val c = w.comps[f.compId]
        val aWon = f.winner == f.a
        val win = w.teams[f.winner.coerceAtLeast(0)]; val lose = w.teams[if (aWon) f.b else f.a]
        if (f.winner < 0) return
        val sw = maxOf(f.scoreA, f.scoreB); val sl = minOf(f.scoreA, f.scoreB)
        val rng = Rng.derive(w.seed, "social", f.id)
        val ln = lines(w, f, t)
        val winSide = if (aWon) 0.0 else 1.0
        val winners = ln.filter { it.value[4] == winSide }; val losers = ln.filter { it.value[4] != winSide }
        val mvp = f.mvp.takeIf { it >= 0 } ?: winners.maxByOrNull { it.value[0] }?.key ?: -1
        val star = winners.entries.maxByOrNull { it.value[0] }
        val goat = (ln.entries.maxByOrNull { it.value[0] })
        val flop = losers.entries.minByOrNull { it.value[0] }
        val loserStar = losers.entries.maxByOrNull { it.value[0] }
        val upset = lose.elo > win.elo + 70
        val sweep = sl == 0 && f.bestOf >= 3
        val close = sw - sl == 1 && f.bestOf >= 3
        val score = "$sw–$sl"
        val stage = c.name
        val mvpName = if (mvp >= 0) handle(w, mvp) else null
        val mvpStats = ln[mvp]?.let { " (${kda(it)}${if (it[5] > 1) " over ${it[5].roundToInt()} ${if (t.isMoba) "games" else "maps"}" else ""})" } ?: ""
        val base = f.day * 10.0 + imp
        val moba = t.isMoba
        val board = "a/${t.short.lowercase().filter { it.isLetterOrDigit() }}"

        // What this series offers to talk about (stat lines, characters, game lengths, map scores, storylines).
        val talk = SocialTalk.ctx(w, t, f, win, lose, rng)
        val facts = SocialTalk.facts(talk)
        fun talkAbout(fs: List<SocialTalk.Fact>, n: Int, wgt: Double) = SocialTalk.comments(talk, fs, n, { tm -> fan(rng, t, tm) }, { rng.pick(ANALYSTS) }, { rng.pick(MEMES) }) { x -> engagement(w, rng, wgt * x * imp, win, lose) }

        // Agora: the post-match thread.
        val cm = ArrayList<Comment>()
        fun cmt(a: Author, text: String, weight: Double, replies: List<Comment> = emptyList()) = Comment(a, text, (engagement(w, rng, 0.08 * weight * imp, win, lose)), replies)
        if (mvpName != null) cm.add(cmt(fan(rng, t, win), SocialVoice.say(rng, "s1", listOf("$mvpName was on another level today$mvpStats.", "Give $mvpName the MVP and the trophy already.", "$mvpName played like the patch was made for them$mvpStats.", "Someone please get $mvpName a bigger stage. Unreal$mvpStats.", "The $mvpName show, again.", "Watched $mvpName all series and forgot the other nine existed.", "$mvpName woke up and chose violence today.", "Put $mvpName's series on the highlight reel and leave it there.", "$mvpName diff. That's it, that's the comment.",
            "Every time $mvpName touches the game something happens. Incredible series.")), 2.0))
        if (upset) cm.add(cmt(fan(rng, t, null), SocialVoice.say(rng, "s2", listOf("NOBODY had ${win.short} winning this. What a series.", "My prediction thread aged like milk. ${win.short} deserved it though.", "Brackets ruined, group chat in shambles. ${win.short} just did that.", "${lose.short} losing to ${win.short}? I need to sit down.", "This is the upset people will bring up for years.", "Elo said ${lose.short}. ${win.short} said no.",
            "${lose.short} fans, it's okay to not be okay right now.")), 2.2))
        if (sweep) cm.add(cmt(fan(rng, t, win), SocialVoice.say(rng, "s3", listOf("Clean $score. ${win.short} didn't even look stressed.", "That wasn't a series, that was a training session for ${win.short}.", "${win.short} never looked in danger. $score and done.", "No contest. ${win.short} were a level above.", "Fastest $score I have seen in a while. ${lose.short} had no answers.", "${win.short} on cruise control, ${lose.short} on fumes.")), 1.6))
        if (close) cm.add(cmt(fan(rng, t, null), SocialVoice.say(rng, "s4", listOf("Went the distance and every game felt different. This is why we watch.", "Full series, full drama. My nerves are gone.", "Decider energy was unreal. Both teams deserved better than a loss.", "Back and forth all series. ${win.short} found one more gear at the end.", "That decider is going to live rent free in my head.", "My heart can't take another game ${if (f.bestOf >= 5) "5" else "3"} like that.")), 1.8))
        // Overreactions both ways.
        cm.add(cmt(fan(rng, t, win), SocialVoice.say(rng, "s5", listOf("${win.short} are winning it all this year, I said what I said.", "Best roster ${win.short} have ever had. No debate.", "${win.short} are on a different trajectory from everyone else right now.", "Book ${win.short} for the international events now.", "If ${win.short} keep this up, the season is over by playoffs.", "Nobody in the league wants to draw ${win.short} right now.", "${win.short} look like a team that actually scrims together.",
            "${star?.key?.let { handle(w, it) } ?: win.short} is the best ${star?.key?.let { role(w, t, it).lowercase() } ?: "player"} in the world right now.")), 1.4))
        cm.add(cmt(fan(rng, t, lose), SocialVoice.say(rng, "s6", listOf("It's over for ${lose.short}. Disband and rebuild.", "${lose.short} are the most frustrating team to support, every single split.", "${lose.short} need a full reset. Coaching, draft, everything.", "${lose.short} have the talent and none of the plan.", "Same mistakes as last split from ${lose.short}. Nothing learned.", "${lose.short} looked like five strangers in the same lobby.", "I want answers from ${lose.short} management after that.",
            "I can't watch ${lose.short} play like this anymore. Something has to change.")), 1.1))
        // Criticism of a poor series, measured.
        if (flop != null && flop.value[0] < 0.92) cm.add(cmt(fan(rng, t, null), SocialVoice.say(rng, "s7", listOf(
            "${handle(w, flop.key)} went ${kda(flop.value)}. Rough series, needs a reset before next week.",
            "Not trying to flame but ${handle(w, flop.key)} looked lost today.", "${handle(w, flop.key)} was a step behind all series. Happens, but not in matches like this.", "Rough one for ${handle(w, flop.key)}. Every fight started from behind.", "${handle(w, flop.key)} needs a reset. That was not the usual level.",
            "${handle(w, flop.key)} has to step up. ${lose.short} can't win with their ${role(w, t, flop.key).lowercase()} playing like that.")), 1.3,
            listOf(Comment(fan(rng, t, lose), SocialVoice.say(rng, "s8", listOf("One bad series and people forget everything ${handle(w, flop.key)} did last split.", "They were set up to fail by the draft, honestly.", "Hard to look good when the whole team is behind.", "Check the earlier series before calling for a bench.", "Bad series, not a bad player.", "Blame the setup, not the one person in the spotlight.")),
                engagement(w, rng, 0.05 * imp, lose)))))
        // A debate with replies.
        val take = if (moba) SocialVoice.say(rng, "s9", listOf("Draft lost ${lose.short} that series.", "${lose.short} keep throwing leads at objectives.", "${lose.short} had the lead and gave it away at every objective.", "${win.short} simply played the map better. Every rotation was a step ahead.", "Vision control decided this one more than the fights did.", "${lose.short} drafted for late game and never got there.", "${win.short}'s jungle-mid duo is the real reason they win."))
            else SocialVoice.say(rng, "s10", listOf("${lose.short} lost it on the map veto.", "${lose.short}'s mid-round calls cost them two maps.", "${win.short} won the utility battle every single round.", "${lose.short} kept forcing on eco and paid for it.", "Retakes decided it. ${win.short} were calm, ${lose.short} were not.", "${lose.short} had no plan for the second half of each map.", "${win.short}'s entry fragging carried that series."))
        cm.add(cmt(fan(rng, t, null), take, 1.2, listOf(
            Comment(fan(rng, t, lose), SocialVoice.say(rng, "s11", listOf("Nah, that's lazy analysis. They got outplayed, simple as that.", "Hard disagree, the execution was the problem, not the plan.", "Watch the vod again, it was individual mistakes.", "The plan was fine. The hands were shaky.", "This is hindsight analysis.", "Easy to say after the fact.", "Nah, they were just outclassed in every lane.")), engagement(w, rng, 0.04 * imp, lose),
                listOf(Comment(fan(rng, t, win), SocialVoice.say(rng, "s12", listOf("Both can be true.", "This thread is the reason analysts exist.", "Can we not do this every series?", "Both of you are half right.", "Bookmarking this argument for the rematch.", "Somebody make a tier list of hot takes in here.", "Agree to disagree, ${win.short} were better either way.")), engagement(w, rng, 0.03 * imp, win)))))))
        if (loserStar != null && loserStar.value[0] > 1.1) cm.add(cmt(fan(rng, t, lose), "Credit to ${handle(w, loserStar.key)} (${kda(loserStar.value)}), carried as hard as anyone could in a loss.", 1.0))
        cm.add(cmt(rng.pick(MEMES), if (moba) SocialVoice.say(rng, "s13", listOf("${lose.short} objective calls continue to be a comedy show 💀", "Me watching ${lose.short} fight 4v5 for the third time today: 🍿",
            "Patch notes: ${win.short} buffed, ${lose.short} nerfed.", "${lose.short} draft room after that series: 🤡", "${lose.short} fans opening the app after that: 🙈", "${win.short} speedrunning the season any%", "${lose.short} fights look like a 5-stack of strangers 😭")) else SocialVoice.say(rng, "s14", listOf("${lose.short} eco rounds again 💀", "${lose.short} saving 4 rifles to lose them all next round 🍿", "Somebody tell ${lose.short} the bomb has to be planted to count.", "${lose.short} trying to win every round with a pistol 💀", "${lose.short} utility usage: yes", "${win.short} reading ${lose.short} like a book 📖", "${lose.short} retakes = donations 🎁")), 0.9))
        // With the series' own facts the thread talks about this series; the generic lines are kept only when there is little to go on.
        val pmt = if (facts.size >= 4) talkAbout(facts, 9 + rng.int(0, 5), 0.08) else cm
        out.add(Post("pm${f.id}", AGORA, f.day, base + 0.9, fan(rng, t, null), "[PMT] ${win.short} $score ${lose.short} | $stage",
            (if (talk.games.isNotEmpty()) SocialTalk.pmtBody(talk, mvp) else "${win.name} beat ${lose.name} $score.${if (mvpName != null) " Series MVP: $mvpName$mvpStats." else ""}") + (if (upset) "\nUpset of the week." else ""), "reaction", t.id,
            listOf(win.id, lose.id), listOfNotNull(mvp.takeIf { it >= 0 }), engagement(w, rng, 1.0 * imp, win, lose), 0, IntArray(6), pmt, place = board))

        // Agora: player ratings.
        if (ln.isNotEmpty() && imp >= 2.5) {
            val ratings = (winners.entries.sortedByDescending { it.value[0] } + losers.entries.sortedByDescending { it.value[0] }).map { it.key to tenScale(it.value[0]) }
            val rc = ArrayList<Comment>()
            star?.let { val r = tenScale(it.value[0]); val hn = handle(w, it.key); rc.add(Comment(fan(rng, t, win), SocialVoice.say(rng, "rt-star", listOf("$r for $hn is too low, that was a ${(r + 1).coerceAtMost(10.0)}.", "$hn only a $r? Rewatch game 1.", "Fair $r for $hn, maybe half a point more.", "$hn deserved the highest score here, and got it.", "$r feels right for $hn. Clean series.", "Bump $hn up, the impact was bigger than the stats.")), engagement(w, rng, 0.05 * imp, win))) }
            flop?.let { val r = tenScale(it.value[0]); val hn = handle(w, it.key); rc.add(Comment(fan(rng, t, lose), SocialVoice.say(rng, "rt-flop", listOf("$hn a $r? Generous.", "$r for $hn is about right sadly.", "Harsh on $hn, the whole team struggled.", "$hn at $r... yeah, rough series.", "I\'d go lower than $r for $hn.", "Give $hn a pass, one bad series.")), engagement(w, rng, 0.04 * imp, lose),
                listOf(Comment(fan(rng, t, lose), SocialVoice.say(rng, "s15", listOf("Ratings threads are just vibes, chill.", "Numbers are numbers.", "Fair rating honestly.", "Too harsh.", "That's about right.", "Not that low, come on.")), engagement(w, rng, 0.02 * imp, lose))))) }
            out.add(Post("rt${f.id}", AGORA, f.day, base + 0.7, rng.pick(ANALYSTS), "[Ratings] ${win.short} vs ${lose.short} — every player out of 10",
                "Player ratings from the series ($score). Highest: ${star?.let { "${handle(w, it.key)} ${tenScale(it.value[0])}" } ?: "-"}; lowest: ${flop?.let { "${handle(w, it.key)} ${tenScale(it.value[0])}" } ?: "-"}.",
                "rating", t.id, listOf(win.id, lose.id), ratings.map { it.first }, engagement(w, rng, 0.6 * imp, win, lose), 0, IntArray(6), rc, ratings = ratings, place = board))
        }

        // Chirp: team accounts, fans, analysts.
        out.add(Post("cw${f.id}", CHIRP, f.day, base + 0.8, teamAccount(win), null,
            SocialVoice.say(rng, "s16", listOf("VICTORY. $score over ${lose.short}.${if (mvpName != null) " MVP: $mvpName." else ""}", "GG ${lose.short}. We take it $score! 💪", "Another one. $score vs ${lose.short} in $stage.", "$score. Thank you ${lose.short} for the series.", "Done and dusted, $score over ${lose.short}.", "Series secured. $score.", "$score. The work continues.", "Big series, big win. $score vs ${lose.short}.")),
            "news", t.id, listOf(win.id, lose.id), listOfNotNull(mvp.takeIf { it >= 0 }), engagement(w, rng, 1.2 * imp, win), engagement(w, rng, 0.2 * imp, win), IntArray(6), emptyList()))
        out.add(Post("cl${f.id}", CHIRP, f.day, base + 0.5, teamAccount(lose), null,
            SocialVoice.say(rng, "s17", listOf("Not our day. GG ${win.short}, we'll be back stronger.", "Tough $sl–$sw loss to ${win.short}. Heads up, back to work.", "GG ${win.short}. Thank you for the support today.", "Lost $sl–$sw to ${win.short}. We review, we improve, we come back.", "GG ${win.short}. Not the result we wanted.", "$sl–$sw today. Thank you for sticking with us.", "Credit to ${win.short}. We go again.")),
            "news", t.id, listOf(lose.id, win.id), emptyList(), engagement(w, rng, 0.6 * imp, lose), engagement(w, rng, 0.08 * imp, lose), IntArray(6),
            talkAbout(facts.filter { it.about == -1 }, 2, 0.05).ifEmpty { listOf(Comment(fan(rng, t, lose), SocialVoice.say(rng, "s18", listOf("We believe 🙏", "Changes needed. Now.", "Same message every week...", "We go again 💪", "Proud of the effort, not the result.", "Fix it before the next one please.", "The fans deserve more than this.", "Long season. Stay together.")), engagement(w, rng, 0.05 * imp, lose))) }))
        if (mvpName != null && imp >= 2.0) out.add(Post("ca${f.id}", CHIRP, f.day, base + 0.6, rng.pick(ANALYSTS), null,
            "$mvpName in ${win.short} $score ${lose.short}: ${ln[mvp]?.let { kda(it) } ?: ""} K/D/A, ${ln[mvp]?.let { "%.2f".format(it[0]) } ?: ""} average rating. " +
                SocialVoice.say(rng, "s19", listOf("Best performance of the week so far.", "That's how you close out a series.", "Numbers don't lie.", "Series-defining numbers.", "A clinic, statistically.", "Top of the week's charts.", "Hard to argue with that line.", "The kind of stat line that wins awards.")),
            "appreciation", t.id, listOf(win.id), listOf(mvp), engagement(w, rng, 0.7 * imp, win), engagement(w, rng, 0.15 * imp, win), IntArray(6), emptyList()))
        val hot = SocialVoice.say(rng, "s20", listOf(
            Triple("overreaction", fan(rng, t, win), "${win.short} just ended ${lose.short}'s whole season and it's only ${Dates.short(f.day)}."),
            Triple("overreaction", fan(rng, t, lose), "${lose.short} fans we need to talk. This roster is not it."),
            Triple("appreciation", fan(rng, t, null), "Whatever you think of ${win.short}, that was beautiful to watch."),
            Triple("criticism", fan(rng, t, null), "${lose.short} looked unprepared for everything ${win.short} did. Where was the plan?")))
        out.add(Post("ch${f.id}", CHIRP, f.day, base + 0.4, hot.second, null, hot.third, hot.first, t.id, listOf(win.id, lose.id), emptyList(),
            engagement(w, rng, 0.35 * imp, win, lose), engagement(w, rng, 0.05 * imp, win, lose), IntArray(6),
            listOf(Comment(fan(rng, t, null), SocialVoice.say(rng, "s21", listOf("Ratio.", "Calm down, it's one series.", "Screenshotting this for later.", "Real.", "Agree, strongly.", "Too early to say.", "Hot take but fair.", "Bold.", "Respectfully, no.", "Clip this.", "Remind me in a month.", "The timeline is cooked.")), engagement(w, rng, 0.04 * imp, win, lose)))))

        // Lens: the scoreline and the MVP.
        if (imp >= 1.8) {
            out.add(Post("lw${f.id}", LENS, f.day, base + 0.7, teamAccount(win), null, SocialVoice.say(rng, "s22", listOf("$score. On to the next one.", "WIN 🏆 $score vs ${lose.short}", "That feeling. $score.", "Matchday done right. $score.", "$score — that's the tweet.", "Another W for the collection. $score.", "$score. Smiles all round.")),
                "news", t.id, listOf(win.id, lose.id), emptyList(), engagement(w, rng, 2.0 * imp, win), 0, IntArray(6),
                talkAbout(facts.filter { it.about == 1 && it.good }, 2, 0.08) + listOf(Comment(fan(rng, t, lose), SocialVoice.say(rng, "s23", listOf("Enjoy it while it lasts.", "Rematch in playoffs, see you there.", "Wait until ${lose.short} get their lineup sorted.", "Lucky draft, that's all.", "Next time.", "We'll see in playoffs.", "Respect, well played.", "Enjoy the win, the rematch is coming.", "GG, deserved.")), engagement(w, rng, 0.03 * imp, lose))),
                image = Image("score", win.id, lose.id, caption = score)))
            if (mvp >= 0) out.add(Post("lm${f.id}", LENS, f.day, base + 0.3, Author("${win.short} Fanclub", "${win.short.lowercase().filter { it.isLetterOrDigit() }}.fanclub", "fan", win.id), null,
                "$mvpName tonight$mvpStats. Frame it.", "appreciation", t.id, listOf(win.id), listOf(mvp), engagement(w, rng, 1.0 * imp, win), 0, IntArray(6),
                listOf(Comment(fan(rng, t, win), SocialVoice.say(rng, "s24", listOf("Generational.", "Frame it twice.", "What a player.", "Poster material.", "Best in the world on this form.", "Unreal series.")), engagement(w, rng, 0.05 * imp, win))), image = Image("player", playerId = mvp, teamA = win.id)))
        }

        // Fanbase: both supporters' groups.
        val joyTotal = engagement(w, rng, 1.0 * imp, win)
        out.add(Post("fw${f.id}", FANBASE, f.day, base + 0.6, fan(rng, t, win), null,
            SocialVoice.say(rng, "s25", listOf("WE WON 🎉 $score against ${lose.short}! Who else was screaming at the screen?", "What a night to be a ${win.short} fan. $score!", "Never doubted them for a second (I doubted them several times).", "$score!! I'm losing my voice!", "Proud of this team. $score vs ${lose.short}.", "Best feeling in the world. $score.", "Whole fanbase eating good tonight.")),
            "reaction", t.id, listOf(win.id), emptyList(), 0, 0, reactions(rng, joyTotal, "joy"),
            talkAbout(facts.filter { it.about >= 0 }, 3, 0.06).ifEmpty { listOf(Comment(fan(rng, t, win), SocialVoice.say(rng, "s26", listOf("Best team in the league, don't @ me", "${mvpName ?: "Everyone"} deserves a statue", "Group chat is going crazy", "Screaming at my phone rn", "Tickets for the next one bought", "Celebrations all week", "Somebody frame this win")), engagement(w, rng, 0.06 * imp, win))) },
            place = "${win.name} Fans"))
        val sad = engagement(w, rng, 0.8 * imp, lose)
        out.add(Post("fl${f.id}", FANBASE, f.day, base + 0.2, fan(rng, t, lose), null,
            SocialVoice.say(rng, "s27", listOf("$sl–$sw. I don't even know what to say anymore.", "Another loss. Let's stay together, it's a long season.", "Who's to blame for today? Honest answers only.", "Down $sl–$sw. We need changes.", "I believe in this roster but today hurt.", "$sl–$sw. Rough watch, back next week.", "How do we lose that one?")),
            if (rng.chance(0.5)) "criticism" else "reaction", t.id, listOf(lose.id), emptyList(), 0, 0, reactions(rng, sad, if (rng.chance(0.5)) "sad" else "angry"),
            talkAbout(facts.filter { it.about <= 0 }, 3, 0.05).ifEmpty { listOf(Comment(fan(rng, t, lose), SocialVoice.say(rng, "s28", listOf("The coaching staff, every time.", "Still my team. Always.", "Changes needed but I'm not leaving.", "Win or lose, still here.", "Rough day. Tomorrow's another one.", "We've been here before.", "${flop?.let { handle(w, it.key) } ?: "The draft"}, sorry not sorry.")), engagement(w, rng, 0.05 * imp, lose),
                listOf(Comment(fan(rng, t, lose), SocialVoice.say(rng, "s29", listOf("Blaming one person is easy. It's the whole team.", "Five players lost that, not one.", "Look at the whole team, not one name.", "Draft and calls first, players second.")), engagement(w, rng, 0.02 * imp, lose))))) },
            place = "${lose.name} Fans"))
    }

    // ------------------------------------------------------------------ coming up
    private fun upcoming(w: World, f: Fixture, imp: Double, out: MutableList<Post>) {
        val t = Catalogue.get(f.titleId)
        val c = w.comps[f.compId]
        val a = w.teams[f.a]; val b = w.teams[f.b]
        val rng = Rng.derive(w.seed, "social-pre", f.id)
        val rival = (w.rivalries["${minOf(a.id, b.id)}|${maxOf(a.id, b.id)}"] ?: 0.0) > 0.5
        val fav = if (a.elo >= b.elo) a else b; val dog = if (fav === a) b else a
        val pa = 1 / (1 + Math.pow(10.0, (b.elo - a.elo) / 400))
        val votesA = (engagement(w, rng, 1.0 * imp, a) * (0.4 + pa)).roundToInt(); val votesB = (engagement(w, rng, 1.0 * imp, b) * (1.4 - pa)).roundToInt()
        val when_ = when (f.day) { w.day -> "today"; w.day + 1 -> "tomorrow"; else -> "on " + Dates.format(f.day, true).substringBefore(" ") }
        val board = "a/${t.short.lowercase().filter { it.isLetterOrDigit() }}"
        val order = w.day * 10.0 + imp * 0.5
        out.add(Post("pp${f.id}", AGORA, w.day, order, fan(rng, t, null), "[Prediction] ${a.short} vs ${b.short} — ${c.name}, $when_",
            if (rival) "Rivalry match. Form, picks and history say ${fav.short}, but rivalries never go to plan. Who takes it?" else "${fav.short} are favourites on paper. Can ${dog.short} pull it off?",
            "prediction", t.id, listOf(a.id, b.id), emptyList(), engagement(w, rng, 0.5 * imp, a, b), 0, IntArray(6),
            listOf(Comment(fan(rng, t, fav), "${fav.short} ${if (f.bestOf >= 3) "${(f.bestOf + 1) / 2}–${rng.int(0, f.bestOf / 2)}" else "win"}, easy.", engagement(w, rng, 0.05 * imp, fav),
                listOf(Comment(fan(rng, t, dog), SocialVoice.say(rng, "s30", listOf("Saving this comment for when ${dog.short} win.", "${dog.short} will surprise people.", "Underdogs bite.", "Not so fast.", "${dog.short} in three, mark it.")), engagement(w, rng, 0.04 * imp, dog)))),
                Comment(rng.pick(ANALYSTS), "Key matchup: ${a.lineup.firstOrNull { it >= 0 }?.let { handle(w, it) } ?: a.short} vs ${b.lineup.firstOrNull { it >= 0 }?.let { handle(w, it) } ?: b.short}. Whoever wins that side wins the series.", engagement(w, rng, 0.04 * imp, a, b))),
            poll = listOf(a.short to votesA, b.short to votesB), place = board))
        out.add(Post("pc${f.id}", CHIRP, w.day, order - 0.1, if (rival) rng.pick(MEMES) else fan(rng, t, null), null,
            if (rival) "${a.short} vs ${b.short} $when_. Clear your schedule. 🔥" else SocialVoice.say(rng, "s31", listOf("Calling it now: ${dog.short} upset ${fav.short} $when_.", "${a.short} vs ${b.short} $when_ — ${fav.short} in ${if (f.bestOf >= 3) "${(f.bestOf + 1) / 2}" else "one"}.", "Matchday $when_: ${a.short} vs ${b.short}. Who are you backing?", "${a.short} or ${b.short} $when_? Need opinions.", "Big one $when_: ${a.short} vs ${b.short}.", "Predictions for ${a.short} vs ${b.short} $when_?", "${dog.short} have a real shot $when_, don't sleep on it.")),
            if (rival) "hype" else "prediction", t.id, listOf(a.id, b.id), emptyList(), engagement(w, rng, 0.4 * imp, a, b), engagement(w, rng, 0.06 * imp, a, b), IntArray(6), emptyList()))
        if (imp >= 2.5) out.add(Post("pf${f.id}", FANBASE, w.day, order - 0.2, teamAccount(a), null, "Matchday $when_! ${a.name} vs ${b.name} in ${c.name}. Drop your predictions below 👇",
            "hype", t.id, listOf(a.id, b.id), emptyList(), 0, 0, reactions(rng, engagement(w, rng, 0.8 * imp, a), "joy"),
            listOf(Comment(fan(rng, t, a), "${a.short} ${(f.bestOf + 1) / 2}–${rng.int(0, f.bestOf / 2)}, let's go", engagement(w, rng, 0.04 * imp, a))), place = "${a.name} Fans"))
    }

    // ------------------------------------------------------------------ transfers
    private fun transfers(w: World, titleId: String?, teams: Set<Int>, out: MutableList<Post>) {
        var n = 0
        for (tr in w.transfers.asReversed()) {
            if (tr.day < w.day - 14) break
            if (n >= 12) break
            if (tr.toTeam < 0 || tr.kind == "release" || tr.kind == "retire") continue
            val to = w.teams.getOrNull(tr.toTeam) ?: continue
            if (titleId != null && to.titleId != titleId) continue
            if (teams.isNotEmpty() && tr.toTeam !in teams && tr.fromTeam !in teams) continue
            val p = w.players.getOrNull(tr.playerId) ?: continue
            if (p.ca < 55 && !w.isUserTeam(to.id) && to.id !in w.followed) continue
            val t = Catalogue.get(to.titleId)
            val from = w.teams.getOrNull(tr.fromTeam)
            val rng = Rng.derive(w.seed, "social-tr", tr.day, tr.playerId, tr.toTeam)
            val fee = if (tr.fee > 0) " for ${dev.uem.em.core.formatMoney(tr.fee)}" else if (from == null) " as a free agent" else ""
            val big = p.ca >= 75
            val imp = (if (big) 3.0 else 1.6) * (1 + sqrt(fansOf(w, to) / 2e6))
            val tf = SocialTalk.transferFacts(w, t, p, to, from, tr.fee)
            fun talkT(n: Int, wgt: Double) = SocialTalk.comments(rng, to, from ?: to, tf, n, { tm -> fan(rng, t, tm) }, { rng.pick(ANALYSTS) }, { rng.pick(MEMES) }) { x -> engagement(w, rng, wgt * x * imp, to) }
            out.add(Post("tw${tr.day}-${tr.playerId}", CHIRP, tr.day, tr.day * 10.0 + imp, rng.pick(PRESS), null,
                "${if (big) "BREAKING: " else ""}${p.handle} joins ${to.name}${from?.let { " from ${it.name}" } ?: ""}$fee.", "news", t.id, listOfNotNull(to.id, from?.id), listOf(p.id),
                engagement(w, rng, 0.8 * imp, to), engagement(w, rng, 0.2 * imp, to), IntArray(6),
                talkT(2, 0.05).ifEmpty { listOf(Comment(fan(rng, t, to), SocialVoice.say(rng, "s32", listOf("HUGE pickup.", "Didn't see that coming.", "Huge if it works out.", "Love this move.", "Not sure about the fit.", "Was not on my bingo card.", "Good business.")), engagement(w, rng, 0.05 * imp, to))) }))
            out.add(Post("ta${tr.day}-${tr.playerId}", AGORA, tr.day, tr.day * 10.0 + imp - 0.1, fan(rng, t, null), "[Transfer] ${p.handle} → ${to.short}${from?.let { " (from ${it.short})" } ?: ""}",
                "${role(w, t, p.id)}. ${if (big) "One of the biggest moves of the window." else "Thoughts?"}", "debate", t.id, listOfNotNull(to.id, from?.id), listOf(p.id),
                engagement(w, rng, 0.5 * imp, to), 0, IntArray(6),
                talkT(4 + rng.int(0, 3), 0.05).ifEmpty { listOf(Comment(fan(rng, t, to), "Exactly what ${to.short} needed.", engagement(w, rng, 0.05 * imp, to))) },
                place = "a/${t.short.lowercase().filter { it.isLetterOrDigit() }}"))
            if (big || w.isUserTeam(to.id)) out.add(Post("tl${tr.day}-${tr.playerId}", LENS, tr.day, tr.day * 10.0 + imp - 0.2, teamAccount(to), null, "Welcome to the family, ${p.handle}. ✍️",
                "news", t.id, listOfNotNull(to.id, from?.id), listOf(p.id), engagement(w, rng, 1.6 * imp, to), 0, IntArray(6),
                listOf(Comment(fan(rng, t, to), "Let's gooo", engagement(w, rng, 0.05 * imp, to))), image = Image("player", playerId = p.id, teamA = to.id)))
            n++
        }
    }

    // ------------------------------------------------------------------ patches and metas
    private fun patches(w: World, titleId: String?, teams: Set<Int>, out: MutableList<Post>) {
        if (teams.isNotEmpty()) return
        for (p in w.patches.asReversed()) {
            if (p.day < w.day - 12) break
            if (titleId != null && p.titleId != titleId) continue
            val t = Catalogue.get(p.titleId)
            val rng = Rng.derive(w.seed, "social-patch", p.titleId, p.day)
            val buffs = p.notes.filter { "buffed" in it }; val nerfs = p.notes.filter { "nerfed" in it }
            val meta = p.notes.filter { it.startsWith("Meta — ") }
            val imp = when (p.kind) { "major" -> 4.0; "medium" -> 2.5; else -> 1.5 }
            fun subject(n: String) = n.substringBefore(":").removePrefix("Item — ").removePrefix("Rune — ").removePrefix("Spell — ").removePrefix("Objective — ").substringAfter(" — ")
            val cm = ArrayList<Comment>()
            nerfs.firstOrNull()?.let { cm.add(Comment(fan(rng, t, null), SocialVoice.say(rng, "s33", listOf("${subject(it)} nerf was overdue.", "They nerfed ${subject(it)} and not the actual problem? Classic.")), (40 * imp * rng.float(0.5, 1.5)).roundToInt())) }
            buffs.firstOrNull()?.let { cm.add(Comment(fan(rng, t, null), SocialVoice.say(rng, "s34", listOf("${subject(it)} buff is going to break pro play, mark my words.", "${subject(it)} players eating good this patch.")), (30 * imp * rng.float(0.5, 1.5)).roundToInt(),
                listOf(Comment(fan(rng, t, null), "It's a tiny change, relax.", (12 * imp * rng.float(0.5, 1.5)).roundToInt())))) }
            meta.firstOrNull()?.let { cm.add(Comment(rng.pick(ANALYSTS), "The meta line is the real headline: ${it.removePrefix("Meta — ").substringBefore(":")}. Expect drafts to look very different.", (50 * imp * rng.float(0.5, 1.5)).roundToInt())) }
            cm.add(Comment(rng.pick(MEMES), SocialVoice.say(rng, "s35", listOf("Game is dead. See you next patch.", "Devs when a character has a 51% win rate: 🔨", "Every patch: 'this is the worst patch ever'.")), (35 * imp * rng.float(0.5, 1.5)).roundToInt()))
            out.add(Post("pt${p.titleId}${p.day}", AGORA, p.day, p.day * 10.0 + imp, rng.pick(ANALYSTS), "[Patch ${p.version}] discussion — ${p.kind} patch",
                "${buffs.size} buffs, ${nerfs.size} nerfs${if (meta.isNotEmpty()) ", and a meta shift" else ""}. Biggest changes: " + (meta + nerfs + buffs).take(3).joinToString(" · ") { it.substringBefore(" (") },
                "debate", t.id, emptyList(), emptyList(), (400 * imp * rng.float(0.6, 1.4)).roundToInt(), 0, IntArray(6), cm, place = "a/${t.short.lowercase().filter { it.isLetterOrDigit() }}"))
            out.add(Post("pc${p.titleId}${p.day}", CHIRP, p.day, p.day * 10.0 + imp - 0.1, rng.pick(ANALYSTS), null,
                "Patch ${p.version}: ${(meta.firstOrNull()?.removePrefix("Meta — ")?.substringBefore(" (") ?: nerfs.firstOrNull()?.substringBefore(" (") ?: buffs.firstOrNull()?.substringBefore(" (") ?: "small changes")}. Winners and losers thread below 👇",
                "news", t.id, emptyList(), emptyList(), (300 * imp * rng.float(0.6, 1.4)).roundToInt(), (40 * imp * rng.float(0.6, 1.4)).roundToInt(), IntArray(6), emptyList()))
        }
    }

    // ------------------------------------------------------------------ streaks, titles, form
    private fun streaks(w: World, titleId: String?, teams: Set<Int>, out: MutableList<Post>) {
        val cand = if (teams.isNotEmpty()) teams.mapNotNull { w.teams.getOrNull(it) } else w.teams.filter { it.active && it.leagueId >= 0 && it.parentId < 0 && (titleId == null || it.titleId == titleId) && fansOf(w, it) > 150_000 }
        for (team in cand.take(80)) {
            val last = w.fixtures.asReversed().asSequence().filter { it.done && (it.a == team.id || it.b == team.id) && it.day >= w.day - 40 }.take(8).toList()
            if (last.size < 3) continue
            val won = last[0].winner == team.id
            var n = 0; for (f in last) if ((f.winner == team.id) == won) n++ else break
            if ((won && n < 4) || (!won && n < 3)) continue
            val t = Catalogue.get(team.titleId)
            val rng = Rng.derive(w.seed, "social-streak", team.id, last[0].id)
            val imp = 2.0 + sqrt(fansOf(w, team) / 1e6)
            val day = last[0].day
            if (won) out.add(Post("sw${team.id}-${last[0].id}", CHIRP, day, day * 10.0 + imp - 0.3, fan(rng, t, null), null,
                SocialVoice.say(rng, "s36", listOf("${team.short} have won $n in a row. Can anyone stop them?", "$n straight for ${team.short}. This is getting scary.", "${team.short} on a $n-series streak and nobody is talking about it enough.")),
                "appreciation", t.id, listOf(team.id), emptyList(), engagement(w, rng, 0.5 * imp, team), engagement(w, rng, 0.1 * imp, team), IntArray(6),
                listOf(Comment(fan(rng, t, null), SocialVoice.say(rng, "s37", listOf("Peak too early, watch them choke in playoffs.", "Inevitable.", "Streaks end. This one will too.", "Running hot.", "Keep it going.", "Respect the grind.")), engagement(w, rng, 0.04 * imp, team)))))
            else {
                out.add(Post("sl${team.id}-${last[0].id}", FANBASE, day, day * 10.0 + imp - 0.4, fan(rng, t, team), null,
                    SocialVoice.say(rng, "s38", listOf("$n losses in a row. Time for changes? Coach, roster, anything?", "I love this team but $n straight losses is unacceptable.", "Bench someone. Anyone. $n losses in a row.")),
                    "overreaction", t.id, listOf(team.id), emptyList(), 0, 0, reactions(rng, engagement(w, rng, 0.6 * imp, team), "angry"),
                    listOf(Comment(fan(rng, t, team), "Fire the coach, start the academy kids, I don't care anymore", engagement(w, rng, 0.05 * imp, team),
                        listOf(Comment(fan(rng, t, team), "Panic posting after a bad week is how you end up with a worse roster.", engagement(w, rng, 0.04 * imp, team))))),
                    place = "${team.name} Fans"))
            }
        }
    }

    private fun titlesWon(w: World, titleId: String?, teams: Set<Int>, out: MutableList<Post>) {
        for (c in w.comps) {
            if (c.status != 2 || c.placements.isEmpty()) continue
            if (titleId != null && c.titleId != titleId) continue
            val champ = w.teams.getOrNull(c.placements[0]) ?: continue
            if (teams.isNotEmpty() && champ.id !in teams) continue
            val end = c.stages.flatMap { it.fixtureIds }.maxOfOrNull { w.fixtures[it].day } ?: continue
            if (end < w.day - 12) continue
            val t = Catalogue.get(c.titleId)
            val rng = Rng.derive(w.seed, "social-title", c.id)
            val imp = tierW(c.tier) * 2
            val runner = c.placements.getOrNull(1)?.let { w.teams.getOrNull(it) }
            out.add(Post("cc${c.id}", LENS, end, end * 10.0 + imp + 1, teamAccount(champ), null, "CHAMPIONS OF ${c.name.uppercase()} 🏆", "news", t.id, listOf(champ.id), emptyList(),
                engagement(w, rng, 4.0 * imp, champ), 0, IntArray(6), listOf(Comment(fan(rng, t, champ), "History. 🏆", engagement(w, rng, 0.1 * imp, champ))), image = Image("team", champ.id, caption = c.short)))
            out.add(Post("ca${c.id}", AGORA, end, end * 10.0 + imp + 1, rng.pick(PRESS), "${champ.name} win ${c.name}${runner?.let { ", beating ${it.short} in the final" } ?: ""}",
                "Congratulations to ${champ.name}. Where does this run rank among their best?", "appreciation", t.id, listOfNotNull(champ.id, runner?.id), emptyList(),
                engagement(w, rng, 1.5 * imp, champ), 0, IntArray(6),
                listOf(Comment(fan(rng, t, champ), "Best team ${champ.short} have ever had, the stats back it up.", engagement(w, rng, 0.08 * imp, champ),
                    listOf(Comment(fan(rng, t, runner ?: champ), "Recency bias. Their older rosters were better.", engagement(w, rng, 0.05 * imp, champ)))),
                    Comment(fan(rng, t, runner ?: champ), if (runner != null) "${runner.short} will be back. What a run anyway." else "Deserved.", engagement(w, rng, 0.04 * imp, champ))),
                place = "a/${t.short.lowercase().filter { it.isLetterOrDigit() }}"))
            out.add(Post("cf${c.id}", FANBASE, end, end * 10.0 + imp, fan(rng, t, champ), null, "WE ARE ${c.short.uppercase()} CHAMPIONS!!! 🏆🏆🏆", "reaction", t.id, listOf(champ.id), emptyList(), 0, 0,
                reactions(rng, engagement(w, rng, 2.0 * imp, champ), "joy"), emptyList(), place = "${champ.name} Fans"))
        }
    }

    private fun form(w: World, titleId: String?, teams: Set<Int>, out: MutableList<Post>) {
        val pool = (if (teams.isNotEmpty()) teams.flatMap { w.teams.getOrNull(it)?.roster.orEmpty() } else w.teams.filter { it.active && it.leagueId >= 0 && it.parentId < 0 && (titleId == null || it.titleId == titleId) }.flatMap { it.lineup.filter { id -> id >= 0 } })
            .mapNotNull { w.players.getOrNull(it) }
        val hot = pool.filter { it.form >= 6 }.sortedByDescending { it.form * it.ca }.take(4)
        val cold = pool.filter { it.form <= -6 }.sortedBy { it.form }.take(3)
        val week = w.day - Dates.dow(w.day)
        for (p in hot) {
            val team = w.teams.firstOrNull { p.id in it.roster } ?: continue
            val t = Catalogue.get(team.titleId); val rng = Rng.derive(w.seed, "social-hot", p.id, week)
            out.add(Post("fh${p.id}-$week", CHIRP, week, week * 10.0 + 1.0, fan(rng, t, team), null,
                SocialVoice.say(rng, "s39", listOf("${p.handle} is in the form of their life right now.", "Appreciation post for ${p.handle}. Carrying ${team.short} week after week.", "Is ${p.handle} the best ${role(w, t, p.id).lowercase()} in the world right now? Genuine question.")),
                "appreciation", t.id, listOf(team.id), listOf(p.id), engagement(w, rng, 0.6 * (1 + p.ca / 50), team), engagement(w, rng, 0.1, team), IntArray(6),
                listOf(Comment(fan(rng, t, null), SocialVoice.say(rng, "s40", listOf("Not yet. Ask me after playoffs.", "Easily top 3.", "Best in the role right now.", "Underrated still.", "Give it time.", "Consistency is the real story.")), engagement(w, rng, 0.04, team)))))
        }
        for (p in cold) {
            val team = w.teams.firstOrNull { p.id in it.roster } ?: continue
            val t = Catalogue.get(team.titleId); val rng = Rng.derive(w.seed, "social-cold", p.id, week)
            out.add(Post("fc${p.id}-$week", AGORA, week, week * 10.0 + 0.5, fan(rng, t, team), "What happened to ${p.handle}?",
                "${p.handle} has looked off for weeks. Slump, meta, or something else? Discuss (respectfully).", "criticism", t.id, listOf(team.id), listOf(p.id),
                engagement(w, rng, 0.3 * (1 + p.ca / 50), team), 0, IntArray(6),
                listOf(Comment(fan(rng, t, null), "Every player has bad stretches. Give it time.", engagement(w, rng, 0.04, team)),
                    Comment(fan(rng, t, team), "Bench them for a week, let them reset. No shame in that.", engagement(w, rng, 0.03, team))),
                place = "a/${t.short.lowercase().filter { it.isLetterOrDigit() }}"))
        }
    }

    /** "3d ago", "today", "in 2 days". */
    fun ago(w: World, day: Int): String = when {
        day > w.day -> if (day - w.day == 1) "tomorrow" else "in ${day - w.day} days"
        day == w.day -> "today"
        w.day - day == 1 -> "yesterday"
        else -> "${w.day - day}d ago"
    }

    fun count(n: Int): String = when { n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 10_000 -> "${n / 1000}k"; n >= 1000 -> "%.1fk".format(n / 1000.0); else -> n.toString() }

    @Suppress("unused") private fun absDiff(a: Double, b: Double) = abs(a - b)
}

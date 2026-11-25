package dev.uem.em.mgmt

import dev.uem.em.core.Rng

/**
 * Keeps a social feed from repeating itself. While a feed is built it remembers every phrasing used
 * (so each call site takes its least-used option next, thread after thread), each commenter writes in
 * a style of their own (casual lower case, emoji, measured, all-caps hype...), and when the feed is
 * finished no line appears twice: a repeat is reworded in its writer's style or dropped.
 */
internal object SocialVoice {
    private class Memory { val use = HashMap<String, Int>() }
    private val mem = ThreadLocal<Memory?>()

    fun begin() = mem.set(Memory())
    fun end() = mem.remove()

    /** One of [options]: the least used so far in this feed for this call site ([key]). */
    fun <T> say(rng: Rng, key: String, options: List<T>): T {
        val m = mem.get() ?: return rng.pick(options)
        var best = Int.MAX_VALUE
        val cand = ArrayList<Int>()
        for (i in options.indices) {
            val u = m.use["$key#$i"] ?: 0
            if (u < best) { best = u; cand.clear() }
            if (u == best) cand.add(i)
        }
        val i = cand[rng.int(0, cand.size - 1)]
        m.use.merge("$key#$i", 1, Int::plus)
        return options[i]
    }

    /** A fixed template from [list] (the template is its own key), least used in this feed first. */
    fun template(rng: Rng, list: List<String>, local: MutableSet<String>): String {
        val m = mem.get()
        val free = list.filter { it !in local }.ifEmpty { list }
        val pick = if (m == null) rng.pick(free) else {
            val min = free.minOf { m.use[it] ?: 0 }
            rng.pick(free.filter { (m.use[it] ?: 0) == min })
        }
        local.add(pick)
        m?.use?.merge(pick, 1, Int::plus)
        return pick
    }

    // ------------------------------------------------------------------ writers' styles

    private val CASUAL_OPEN = listOf("ngl ", "honestly ", "lowkey ", "bro ", "ok but ", "not gonna lie ", "idk man ", "fr ", "yo ", "listen ")
    private val CASUAL_CLOSE = listOf("", " fr", " tbh", " lol", " smh", " no cap", " ngl", " imo")
    private val EMOJI = listOf("🔥", "😭", "💀", "😤", "🙏", "👀", "🤯", "😮‍💨", "📈", "📉", "🫡", "🍿", "💯", "🥶")
    private val MEASURED_OPEN = listOf("For what it's worth: ", "Fair to say ", "In fairness, ", "Calmly: ", "Watching it back, ", "Objectively, ", "Small point, but ")
    private val HYPE_CLOSE = listOf("!!!", "!!", " LET'S GO", " WHAT A TEAM", "!! 🔥🔥")
    private val TAIL = listOf("", "", " Thoughts?", " Change my mind.", " Discuss.", " That's the post.", " Anyway.", " Moving on.")

    private fun hash(s: String) = s.fold(7) { h, c -> h * 31 + c.code }

    /** [text] as the account [handle] writes it ([salt] picks among that writer's habits). */
    fun style(handle: String, kind: String, text: String, salt: Int): String {
        if (kind == "team" || kind == "press" || text.isBlank()) return text
        val h = hash(handle).let { if (it < 0) -it else it }
        val r = (h / 7 + salt * 13).let { if (it < 0) -it else it }
        fun lower(s: String) = s.replaceFirstChar { it.lowercase() }
        fun strip(s: String) = s.trimEnd('.', '!')
        return when (h % 7) {
            0 -> text // plain
            1 -> CASUAL_OPEN[r % CASUAL_OPEN.size] + lower(strip(text)).lowercase() + CASUAL_CLOSE[(r / 3) % CASUAL_CLOSE.size]
            2 -> text + " " + EMOJI[r % EMOJI.size] + (if (r % 3 == 0) EMOJI[(r / 5) % EMOJI.size] else "")
            3 -> MEASURED_OPEN[r % MEASURED_OPEN.size] + lower(text)
            4 -> if (text.length < 70) strip(text).uppercase() + HYPE_CLOSE[r % HYPE_CLOSE.size] else strip(text) + HYPE_CLOSE[r % HYPE_CLOSE.size]
            5 -> text + TAIL[r % TAIL.size]
            else -> if (r % 2 == 0) lower(strip(text)) + "." else text
        }
    }

    /** Every comment in its writer's style; a line already said elsewhere in the feed is reworded or dropped. */
    fun finish(posts: List<Social.Post>): List<Social.Post> {
        val seen = HashSet<String>()
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        fun fix(cs: List<Social.Comment>, depth: Int): List<Social.Comment> {
            val out = ArrayList<Social.Comment>()
            for ((i, c) in cs.withIndex()) {
                var text = style(c.author.handle, c.author.kind, c.text, i + depth * 5)
                var salt = 1
                while (norm(text) in seen && salt < 6) { text = style(c.author.handle + "#$salt", c.author.kind, c.text, i + salt * 11); salt++ }
                if (norm(text) in seen) continue
                seen.add(norm(text))
                out.add(Social.Comment(c.author, text, c.score, fix(c.replies, depth + 1)))
            }
            return out
        }
        return posts.map { p ->
            Social.Post(p.key, p.platform, p.day, p.order, p.author, p.title, p.text, p.tone, p.titleId, p.teams, p.players, p.score, p.reposts, p.reactions,
                fix(p.comments, 0), p.image, p.ratings, p.poll, p.place)
        }
    }
}

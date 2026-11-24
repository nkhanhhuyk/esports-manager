package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.game.Advance
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Social
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fan social media: every network has posts about this world's results, matches to come, transfers and patches; the same each time. */
class SocialTest {
    @Test
    fun fansTalkAboutWhatHappens() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "social", "S", Dates.day(2026, 11, 21))
        Advance.run(w, 62)
        val t0 = System.nanoTime()
        val feed = Social.feed(w, null)
        val ms = (System.nanoTime() - t0) / 1e6
        val sb = StringBuilder("feed: ${feed.size} posts in %.0f ms\n".format(ms))
        for (pf in Social.PLATFORMS) {
            val mine = feed.filter { it.platform == pf }
            sb.appendLine("${Social.PLATFORM_NAME[pf]}: ${mine.size} posts, tones ${mine.groupingBy { Social.toneLabel(it.tone) }.eachCount()}")
            for (p in mine.take(3)) sb.appendLine("  [${p.author.handle}] ${p.title ?: ""} ${p.text}  (${p.comments.size} comments)")
            assertTrue("$pf has posts", mine.isNotEmpty())
        }
        val tones = feed.map { Social.toneLabel(it.tone) }.toSet()
        for (needed in listOf("Reactions", "Ratings", "Debates", "Appreciation", "Overreactions", "Predictions", "News")) assertTrue("tone $needed in $tones", needed in tones)
        assertTrue(feed.any { it.ratings.isNotEmpty() } && feed.any { it.poll.isNotEmpty() })
        // Comment threads argue: some comments have replies.
        assertTrue(feed.any { p -> p.comments.any { it.replies.isNotEmpty() } })
        // Posts are about real events: post-match threads name the series' teams.
        val pm = feed.first { it.key.startsWith("pm") }
        assertTrue(pm.teams.all { id -> pm.title!!.contains(w.teams[id].short) })
        // The same world gives the same feed.
        val again = Social.feed(w, null)
        assertEquals(feed.map { it.key + it.text + it.score }, again.map { it.key + it.text + it.score })
        // No guesses about anyone's gender, no unfilled templates.
        val all = feed.flatMap { p -> listOf(p.title ?: "", p.text) + p.comments.flatMap { c -> listOf(c.text) + c.replies.map { it.text } } }
        val bad = all.filter { Regex("""\b(he|his|him|she|her)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) || it.contains("null") || it.contains("\${") }
        assertTrue("bad texts: ${bad.take(5)}", bad.isEmpty())
        // Different series are talked about differently: few comments repeat word for word across post-match threads.
        val pmtComments = feed.filter { it.key.startsWith("pm") }.flatMap { p -> p.comments.map { it.text } }
        val repeats = pmtComments.size - pmtComments.toSet().size
        println("post-match threads: ${feed.count { it.key.startsWith("pm") }}, comments ${pmtComments.size}, repeated word for word $repeats")
        assertTrue("comments repeat across threads: $repeats of ${pmtComments.size}", pmtComments.isNotEmpty() && repeats * 10 <= pmtComments.size)
        // A game filter and a team filter narrow the feed.
        val lol = Social.feed(w, "lol")
        assertTrue(lol.isNotEmpty() && lol.all { it.titleId == "lol" })
        // A team that played recently (from a post-match thread).
        val team = w.teams[pm.teams[0]]
        val mine = Social.feed(w, null, setOf(team.id))
        val off = mine.filter { team.id !in it.teams && it.teams.isNotEmpty() }
        assertTrue("team feed: ${mine.size} posts, not about ${team.short}: ${off.take(3).map { it.key + " " + it.text }}", mine.isNotEmpty() && off.isEmpty())
        println(sb); java.io.File("build/social.txt").writeText(sb.toString())
        assertTrue("feed took $ms ms", ms < 3000)
    }
}

package dev.uem.em

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.game.BoxStore
import dev.uem.em.gen.WorldGen
import dev.uem.em.mgmt.Economy
import dev.uem.em.mgmt.Staffing
import dev.uem.em.model.CompTier
import dev.uem.em.model.Staff
import dev.uem.em.model.StaffBid
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Draft
import dev.uem.em.sim.DraftSession
import dev.uem.em.sim.Lineups
import dev.uem.em.sim.MobaEngine
import dev.uem.em.sim.Patch
import dev.uem.em.sim.TeamContexts
import dev.uem.em.titles.Catalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Staff: real coaches at their clubs and former coaches in the market; staff change matches,
 * drafts, training, recovery and injuries; hiring, buy-outs, renewals, releases and AI moves; retiring
 * players becoming staff; the coaching legacy; saves and older saves.
 */
class StaffTest {
    companion object {
        private lateinit var shared: World
        fun world(): World {
            if (!::shared.isInitialized) { TestData.init(); BoxStore.clear(); shared = WorldGen.create(TestData.source, "staff", "S", Dates.day(2026, 11, 21)) }
            Catalogue.applyCustom(shared)
            return shared
        }
    }

    @Test
    fun realStaffAtTheirClubs() {
        val w = world()
        println("staff ${w.staff.size} (employed ${w.staff.count { it.teamId >= 0 }}, free ${w.staff.count { it.teamId < 0 }}, real ${w.staff.count { it.real }})")
        println("by role: " + w.staff.groupingBy { it.role }.eachCount().toSortedMap())
        for (name in listOf("T1", "Gen.G Esports", "BILIBILI GAMING", "Vitality", "Team Spirit", "NRG", "ONIC")) {
            val team = w.teams.firstOrNull { it.name == name } ?: continue
            println("$name: " + Staffing.members(w, team).joinToString { "${it.name()} (${Staffing.label(it)} ${it.quality.toInt()}${if (it.real) " real" else ""})" })
        }
        val t1 = w.teams.first { it.name == "T1" && it.titleId == "lol" }
        val hc = Staffing.members(w, t1).first { it.role == "head-coach" }
        assertTrue("T1's real head coach", hc.real && hc.handle == "Tom")
        assertEquals("Vitality (CS2): XTQZZZ", "XTQZZZ", Staffing.members(w, w.teams.first { it.name == "Vitality" && it.titleId == "cs2" }).first { it.role == "head-coach" }.handle)
        assertEquals("Spirit (Dota 2): Silent", "Silent", Staffing.members(w, w.teams.first { it.name == "Team Spirit" && it.titleId == "dota2" }).first { it.role == "head-coach" }.handle)
        // Former and retired coaches are free agents in the market, with their real titles.
        val koma = w.staff.first { it.handle == "kkOma" }
        assertTrue("kkOma free with five Worlds as head coach", koma.teamId < 0 && !koma.retired && koma.past.count { it.contains("|worlds|") && it.endsWith("|1|1") } == 5)
        assertTrue("many former LoL coaches free", w.staff.count { it.teamId < 0 && it.real && it.titleId == "lol" } > 150)
        // Every active club has a head coach; bigger clubs run a bigger staff.
        val active = w.teams.filter { it.active }
        assertTrue("head coach everywhere", active.all { t -> Staffing.members(w, t).any { it.role == "head-coach" } })
        val big = active.filter { w.orgs[it.orgId].rep >= 62 }.map { Staffing.members(w, it).size }.average()
        val small = active.filter { w.orgs[it.orgId].rep < 40 && it.kind.isEmpty() && it.parentId < 0 }.map { Staffing.members(w, it).size }.average()
        println("staff per club: big %.1f, small %.1f".format(big, small))
        assertTrue("big clubs employ more staff", big >= small + 5)
    }

    /** Swap in a whole staff of one level (returns the staff list to restore). */
    private fun staffAt(w: World, team: Team, q: Double, tag: String): List<Int> {
        val keep = team.staffIds.toList()
        team.staffIds.clear()
        val t = Catalogue.get(team.titleId)
        val rng = Rng.of("staffat|$tag|${team.id}|$q")
        val roles = listOf("head-coach", "assistant-coach", "strategic-coach", "analyst", "analyst", "psychologist", "performance-coach", "physio", "team-manager").map { it to "" } + t.lineup.map { "position-coach" to it }
        for ((role, spec) in roles) {
            val s = Staffing.create(w, rng, role, "KR", team.titleId, q, spec)
            s.teamId = team.id; s.orgId = team.orgId; team.staffIds.add(s.id)
        }
        return keep
    }

    private fun restore(w: World, team: Team, keep: List<Int>) {
        for (sid in team.staffIds) if (sid !in keep) w.staff[sid].let { it.teamId = -1; it.retired = true }
        team.staffIds.clear(); team.staffIds.addAll(keep)
    }

    @Test
    fun staffWinMatches() {
        val w = world()
        val sb = StringBuilder()
        for (id in listOf("mlbb", "lol")) {
            val t = Catalogue.get(id)
            val patch = Patch.of(w, t)
            val teams = w.teams.filter { it.titleId == id && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }
            val a = teams[2]; val b = teams[3]
            var eliteWins = 0; var games = 0
            val ka = a.staffIds.toList(); val kb = b.staffIds.toList()
            val sets = mapOf("a90" to staffAt(w, a, 88.0, "e").let { a.staffIds.toList() }.also { restore(w, a, ka) }, "a30" to staffAt(w, a, 30.0, "p").let { a.staffIds.toList() }.also { restore(w, a, ka) },
                "b90" to staffAt(w, b, 88.0, "e").let { b.staffIds.toList() }.also { restore(w, b, kb) }, "b30" to staffAt(w, b, 30.0, "p").let { b.staffIds.toList() }.also { restore(w, b, kb) })
            for (sid in sets.values.flatten()) w.staff[sid].retired = false
            fun use(team: Team, ids: List<Int>) { team.staffIds.clear(); team.staffIds.addAll(ids); for (sid in ids) w.staff[sid].teamId = team.id }
            val lu = arrayOf(Lineups.auto(w, a).filter { it >= 0 }, Lineups.auto(w, b).filter { it >= 0 })
            val players = Array(2) { s -> lu[s].map { w.players[it] } }
            var know = DoubleArray(2)
            for (g in 0 until 160) {
                val eliteA = g % 2 == 0
                use(a, if (eliteA) sets["a90"]!! else sets["a30"]!!); use(b, if (eliteA) sets["b30"]!! else sets["b90"]!!)
                val tm = arrayOf(a, b)
                know = DoubleArray(2) { Draft.knowledge(w, tm[it]) }
                val d = DraftSession(t, patch, players, arrayOf(a.style, b.style), g % 2, arrayOf(emptySet(), emptySet()), Rng.of("sdraft|$id|$g"), know).runAll()
                val rng = Rng.of("sgame|$id|$g")
                val evals = Array(2) { si -> Draft.evaluate(t, patch, lu[si].mapNotNull { pid -> d.assign[si][pid]?.let { t.charIndex[it] } }, d.picks[1 - si].mapNotNull { t.charIndex[it] }, players[si], tm[si].style) }
                val ctx = List(2) { si -> TeamContexts.build(w, tm[si], lu[si], si, d.assign[si], t.maps[0].id, 0.0, rng) }
                val out = MobaEngine.simulate(t, ctx, evals, rng, patch, null, MobaEngine.Opts(false, doubleArrayOf(Staffing.inGame(w, a), Staffing.inGame(w, b)), w))
                games++
                if ((out.winner == 0) == eliteA) eliteWins++
            }
            use(a, sets["a90"]!!); val mfE = Staffing.matchFactor(w, a); val kE = Draft.knowledge(w, a)
            use(a, sets["a30"]!!); val mfP = Staffing.matchFactor(w, a); val kP = Draft.knowledge(w, a)
            val line = "$id: elite staff (88) beat the same roster with a poor staff (30) in ${eliteWins * 100 / games} pct of $games games; match factor " + "%.3f vs %.3f, draft read %.2f vs %.2f".format(mfE, mfP, kE, kP)
            println(line); sb.appendLine(line)
            restore(w, a, ka); restore(w, b, kb)
            for (sid in ka) w.staff[sid].let { it.teamId = a.id; it.retired = false }
            for (sid in kb) w.staff[sid].let { it.teamId = b.id; it.retired = false }
            assertTrue("$id: staff decide some games: $line", eliteWins * 100 / games in 56..80)
        }
        java.io.File("build/staff-matches.txt").writeText(sb.toString())
    }

    @Test
    fun staffDevelopPlayers() {
        val w = world()
        val p = w.players.filter { it.titleId == "lol" && it.teamId >= 0 && !it.retired && Dates.ageAt(it.birth, w.day) <= 21 && it.pa > it.ca + 8 && w.teams[it.teamId].parentId < 0 }.maxBy { it.pa - it.ca }
        val team = w.teams[p.teamId]
        val attrs0 = p.attrs.copyOf(); val ca0 = p.ca; val pa0 = p.pa; val chars0 = HashMap(p.profChar)
        fun run(q: Double): Pair<Double, Double> {
            p.attrs = attrs0.copyOf(); p.ca = ca0; p.pa = pa0; p.profChar.clear(); p.profChar.putAll(chars0)
            val keep = staffAt(w, team, q, "dev")
            val top0 = p.profChar.values.sortedDescending().take(4).sum()
            for (m in 0 until 6) dev.uem.em.mgmt.Development.monthly(w)
            val r = (p.ca - ca0) to (p.profChar.values.sortedDescending().take(4).sum() - top0)
            restore(w, team, keep)
            return r
        }
        val (eliteCa, eliteChars) = run(88.0)
        val (poorCa, poorChars) = run(30.0)
        p.attrs = attrs0; p.ca = ca0; p.pa = pa0; p.profChar.clear(); p.profChar.putAll(chars0)
        println("six months of training for ${p.handle}: CA %+.2f with an elite staff, %+.2f with a poor one; character pool %+.1f vs %+.1f".format(eliteCa, poorCa, eliteChars, poorChars))
        assertTrue("better coaches develop players faster", eliteCa > poorCa + 0.15 * kotlin.math.abs(poorCa).coerceAtLeast(0.5))
        assertTrue("a positional coach deepens the character pool", eliteChars > poorChars)
        // Recovery and injuries: performance coach and physio.
        val keep = staffAt(w, team, 88.0, "med")
        val fit = Staffing.fitness(w, team); val med = Staffing.medicine(w, team)
        restore(w, team, keep)
        assertTrue("an elite performance coach and physio help recovery and injuries", fit > 80 && med > 80)
    }

    @Test
    fun hiringBuyoutsAndReleases() {
        val w = world()
        val team = w.teams.filter { it.titleId == "lol" && it.active && it.leagueId >= 0 && it.parentId < 0 }.sortedByDescending { it.elo }[6]
        val org = w.orgs[team.orgId]
        w.userOrg = org.id
        org.balance = 50_000_000.0
        try {
            // A free agent: a low offer is refused, the asking wage is accepted.
            val free = Staffing.create(w, Rng.of("freeanalyst"), "analyst", "KR", "lol", 60.0)
            val ask = Staffing.demand(w, free, team.region) * Staffing.wageFactor(w, team)
            assertTrue("a low offer is refused", !Staffing.offer(w, free, team, "analyst", ask * 0.6, 2).ok)
            val r = Staffing.offer(w, free, team, "analyst", ask * 1.02, 2)
            assertTrue(r.text, r.ok && free.teamId == team.id && free.spells.last().teamId == team.id)
            // A head coach role needs the current one replaced.
            val hcFree = w.staff.filter { it.teamId < 0 && !it.retired && it.titleId == "lol" && it.role == "head-coach" && Staffing.refusal(w, it, team, "head-coach") == null }.maxBy { it.quality }
            val old = Staffing.members(w, team).first { it.role == "head-coach" }
            assertTrue("one head coach at a time", !Staffing.offer(w, hcFree, team, "head-coach", Staffing.demand(w, hcFree, team.region) * 1.1, 2).ok)
            val r2 = Staffing.offer(w, hcFree, team, "head-coach", Staffing.demand(w, hcFree, team.region) * 1.1, 2, replace = true)
            assertTrue(r2.text, r2.ok && old.teamId < 0 && Staffing.members(w, team).single { it.role == "head-coach" }.id == hcFree.id)
            // Staff under contract elsewhere: the club wants compensation, the person wants a step up.
            val target = w.staff.filter { it.teamId >= 0 && !w.isUserTeam(it.teamId) && it.titleId == "lol" && it.role == "analyst" && w.orgs[w.teams[it.teamId].orgId].rep < org.rep && Staffing.refusal(w, it, team, "analyst") == null }.maxBy { it.quality }
            val seller = w.orgs[w.teams[target.teamId].orgId]
            val fee = Staffing.compensation(w, target)
            assertTrue("no compensation, no deal", !Staffing.offer(w, target, team, "analyst", target.salary * 1.4, 2, fee = 0.0).ok)
            val before = seller.balance
            val r3 = Staffing.offer(w, target, team, "analyst", maxOf(target.salary * 1.4, Staffing.demand(w, target, team.region) * 1.1), 2, fee = fee * 1.2)
            assertTrue(r3.text, r3.ok && target.teamId == team.id && seller.balance > before && target.spells.dropLast(1).last().why == "bought out")
            // Renewal and release.
            assertTrue(Staffing.renew(w, free, Staffing.demand(w, free, team.region) * 1.3, 3).ok && free.contractEnd == w.season + 2)
            val bal = org.balance
            Staffing.release(w, free, "released")
            assertTrue("release costs severance", free.teamId < 0 && org.balance < bal)
            // A bigger club's approach for the user's head coach.
            val buyer = w.teams.first { it.titleId == "lol" && it.active && !w.isUserTeam(it.id) && it.parentId < 0 && it.leagueId >= 0 }
            val b = StaffBid(w.nextStaffBid++, hcFree.id, buyer.id, team.id, 500_000.0, "head-coach", w.day, salary = 400_000.0)
            w.staffBids.add(b)
            assertTrue(Staffing.answerBid(w, b.id, true).ok && hcFree.teamId == buyer.id)
        } finally { w.userOrg = -1 }
    }

    @Test
    fun seasonCarouselAndPlayersBecomingStaff() {
        TestData.init(); BoxStore.clear()
        val w = WorldGen.create(TestData.source, "staff-season", "S2", Dates.day(2026, 11, 21))
        val before = w.staff.filter { it.teamId >= 0 && it.role == "head-coach" }.associate { it.id to it.teamId }
        // Some old players retire: a share of them become coaches, analysts, scouts...
        val retiring = w.players.filter { it.teamId < 0 && !it.retired && it.real }.take(80)
        w.season++
        for (p in retiring) p.retiredSeason = w.season
        Staffing.seasonEnd(w, Rng.of("carousel"))
        val moved = w.staff.count { it.role == "head-coach" && before[it.id] != null && before[it.id] != it.teamId }
        val fromPlayers = w.staff.filter { it.playerId >= 0 }
        println("season end: $moved head coaches changed club; ${fromPlayers.size} of ${retiring.size} retired players joined the staff pool: " + fromPlayers.take(8).joinToString { "${it.name()} → ${Staffing.label(it)}" })
        assertTrue("the coaching carousel turns", moved > 0)
        assertTrue("retired players become staff", fromPlayers.size in 5..60 && fromPlayers.all { it.handle == w.players[it.playerId].handle })
        assertTrue("every AI club has a head coach after the summer", w.teams.filter { it.active && !w.isUserTeam(it.id) }.all { t -> Staffing.members(w, t).any { it.role == "head-coach" } })
    }

    @Test
    fun coachLegacy() {
        val w = world()
        val idx = Staffing.titles(w)
        val ranked = Staffing.coaches(w, "lol").map { it to Staffing.legacy(w, it, Staffing.honours(w, it, idx)).total }.sortedByDescending { it.second }
        println("LoL coach GOAT: " + ranked.take(6).joinToString { "${it.first.name()} %.1f".format(it.second) })
        assertEquals("kkOma is the greatest LoL coach", "kkOma", ranked.first().first.handle)
        // A title won in the save counts for the head coach (assistants 40%).
        val t1 = w.teams.first { it.name == "T1" && it.titleId == "lol" }
        val c = w.comps.first { c -> c.titleId == "lol" && c.tier == CompTier.LEAGUE && !c.done && c.startDay > w.day }
        val hc = Staffing.members(w, t1).first { it.role == "head-coach" }
        val asst = Staffing.members(w, t1).first { it.role == "assistant-coach" }
        val h0 = Staffing.honours(w, hc).size
        val st0 = c.status
        c.status = 2; c.placements = mutableListOf(t1.id)
        try {
            val hh = Staffing.honours(w, hc); val ha = Staffing.honours(w, asst)
            assertTrue("the head coach's title", hh.size == h0 + 1 && hh.first { it.compId == c.id }.pts > ha.first { it.compId == c.id }.pts)
        } finally { c.status = st0; c.placements = ArrayList() }
    }

    @Test
    fun savesAndOlderSaves() {
        val w = world()
        val dir = java.nio.file.Files.createTempDirectory("staffsave").toFile()
        val store = dev.uem.em.persist.SaveStore(dir)
        store.save("s", w)
        val back = store.load("s")
        assertEquals(w.staff.size, back.staff.size)
        val a = w.staff.first { it.handle == "kkOma" }; val b = back.staff[a.id]
        assertTrue("attributes, titles and career survive a save", a.attrs.contentEquals(b.attrs) && a.past == b.past && b.staffVersionOk(back))
        // An older save: the first staff model (no attributes), no real coaches.
        TestData.init(); BoxStore.clear()
        val old = WorldGen.create(TestData.source, "staff-old", "Old", Dates.day(2026, 11, 21))
        old.staff.clear(); for (t in old.teams) t.staffIds.clear()
        for (t in old.teams) if (t.active) {
            val s = Staff(old.staff.size, "Old", "Coach", "KR", old.day - 40 * 365, "head-coach", 55.0, t.titleId, t.id, t.orgId, 100000.0, old.season + 1)
            old.staff.add(s); t.staffIds.add(s.id)
        }
        old.staffVersion = 0
        Economy.ensureSetup(old, TestData.source)
        val t1 = old.teams.first { it.name == "T1" && it.titleId == "lol" }
        assertTrue("older saves: attributes for every staff member", old.staff.all { it.attrs.size == Staffing.ATTRS.size } && old.staffVersion == 2)
        assertTrue("older saves: real coaches join their AI clubs", Staffing.members(old, t1).any { it.real && it.role == "head-coach" })
        assertTrue("older saves: former coaches in the market", old.staff.count { it.real && it.teamId < 0 } > 100)
    }

    private fun Staff.staffVersionOk(w: World) = w.staffVersion == 2 && spells.size == w.staff[id].spells.size
}

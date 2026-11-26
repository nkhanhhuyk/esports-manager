package dev.uem.em.sim

import dev.uem.em.core.Dates
import dev.uem.em.model.Competition
import dev.uem.em.model.Message
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.RoleDef
import kotlin.math.roundToInt

/**
 * Substitutes: who sits on the bench for a series, emergency stand-ins when nobody available plays a
 * position, and the coach's substitutions between the games of a series (a bad game, the next map, the
 * champion pool left under Fearless). A substitute always plays one of their own positions (main or second).
 *
 * Notes kept on a game (GameRecord.subs): "side|slot|out|in|reason", out = -1 for an empty seat; reasons
 * "form", "map", "pool", "back", "coach" (your call), and before the series "call-up" / "promoted" (from the
 * academy) or "stand-in" (an unsigned player, when the academy has nobody for the position).
 */
object Subs {
    /** Reasons of changes made before a series starts (not a coach's substitution between games). */
    val BEFORE = setOf("stand-in", "call-up", "promoted", "signed")

    /** 0: main position, 1: second position, 2: out of position. */
    fun tier(p: Player, roleId: String) = if (p.roles.firstOrNull() == roleId) 0 else if (p.roles.getOrNull(1) == roleId) 1 else 2
    /** Whether a player plays a position: main or second, and any player can take a "flex" slot (Valorant's flex is the role that switches agents). */
    fun plays(p: Player, roleId: String) = roleId == "flex" || tier(p, roleId) <= 1

    /** Whether a player may play this competition for this team today (registration, eligibility, age, availability). */
    fun canPlay(w: World, c: Competition?, team: Team, p: Player): Boolean {
        val t = Catalogue.get(team.titleId)
        if (!Lineups.available(w, p) || Dates.ageAt(p.birth, w.day) < t.minAge) return false
        if (c == null) return true
        val reg = c.registrations[team.id]
        if (reg != null && p.id !in reg.players) return false
        return dev.uem.em.mgmt.Circuits.eligible(w, c, p) == null
    }

    /** The bench for a series: roster players outside the lineup who could come in (none of them busy elsewhere today). */
    fun bench(w: World, c: Competition?, team: Team, lineup: List<Int>, busy: Set<Int> = emptySet()): List<Int> =
        team.roster.filter { it !in lineup && it !in busy && canPlay(w, c, team, w.players[it]) }

    private fun importsOk(w: World, team: Team, lineup: List<Int>, slot: Int, p: Player): Boolean {
        val cap = dev.uem.em.mgmt.Imports.cap(w, team)
        if (cap < 0 || !dev.uem.em.mgmt.Imports.isImport(w, p, team)) return true
        val others = lineup.withIndex().count { (i, id) -> i != slot && id >= 0 && dev.uem.em.mgmt.Imports.isImport(w, w.players[id], team) }
        return others < cap
    }

    /** Whether `p` may take `slot` without breaking the league's import limit. */
    fun importsAllow(w: World, team: Team, lineup: List<Int>, slot: Int, p: Player) = importsOk(w, team, lineup, slot, p)

    /** Days out from which a club signs cover instead of calling an academy player up. */
    const val LONG_ABSENCE = 28

    /**
     * A seat that is empty or held by a player out of position goes to someone who plays it:
     *  1. a roster player who plays it;
     *  2. the regular is out for a short time (four weeks or less): an academy player is called up, and goes
     *     back when the regular is available again ([dev.uem.em.mgmt.Academies.callUp]);
     *  3. the regular is out for long, or nobody plays the position full-time: an AI club promotes its own
     *     prospect or signs a free agent who plays it (an emergency signing until the season ends), whichever
     *     suits the club better (a strong academy and a tight budget lean to the prospect, a clearly better free
     *     agent is signed). Your club gets the academy call-up for the match and a list of free agents to sign,
     *     and decides;
     *  4. nobody in the academy either: an unsigned player stands in for the day (of the nation for a national team).
     * Returns the lineup and the notes (reasons "call-up", "promoted", "signed", "stand-in").
     */
    fun standIns(w: World, c: Competition?, team: Team, lineup: List<Int>, busy: Set<Int>, side: Int = 0): Pair<List<Int>, List<String>> {
        val t = Catalogue.get(team.titleId)
        if (lineup.size != t.lineupRoles.size) return lineup to emptyList()
        // Your own lineup, when you pick it yourself, is yours.
        if (w.isUserTeam(team.id) && team.manualLineup) return lineup to emptyList()
        val out = lineup.toMutableList()
        val notes = ArrayList<String>()
        val user = w.isUserTeam(team.id)
        val club = team.nationalOf.isEmpty() && team.kind.isEmpty() && team.parentId < 0
        for (i in out.indices) {
            val role = t.lineupRoles[i]
            // An injured or unavailable player in the lineup (a short roster's last resort) counts as an empty seat.
            val cur = out[i].takeIf { it >= 0 }?.let { w.players[it] }?.takeIf { Lineups.available(w, it) }
            if (cur != null && plays(cur, role.id)) continue
            val curEff = cur?.let { Ratings.effectiveness(it, t, role) } ?: 0.0
            // 1. Somebody on the roster who plays it (the lineup may have come from a registration or busy swap).
            val fromRoster = team.roster.asSequence().map { w.players[it] }
                .filter { it.id !in out && it.id !in busy && plays(it, role.id) && canPlay(w, c, team, it) && importsOk(w, team, out, i, it) }
                .minWithOrNull(compareBy<Player>({ tier(it, role.id) }, { -Ratings.effectiveness(it, t, role) }))
            if (fromRoster != null) { out[i] = fromRoster.id; continue }
            // The regular who would normally play here (injured, on a break, waiting for a visa), and for how long.
            val absentP = team.roster.map { w.players[it] }.filter { it.roles.firstOrNull() == role.id && !Lineups.available(w, it) }.minByOrNull { maxOf(it.injuryUntil, it.visaUntil) }
            val back = absentP?.let { maxOf(it.injuryUntil, it.visaUntil) }
            val long = absentP == null || back!! - w.day > LONG_ABSENCE
            val academy = if (club) dev.uem.em.mgmt.Academies.of(w, team) else null
            val kid = academy?.roster?.asSequence()?.map { w.players[it] }
                ?.filter { it.id !in busy && it.id !in out && it.loanFrom < 0 && plays(it, role.id) && Lineups.available(w, it) && Dates.ageAt(it.birth, w.day) >= t.minAge &&
                    (c == null || dev.uem.em.mgmt.Circuits.eligible(w, c, it) == null) && importsOk(w, team, out, i, it) }
                ?.minWithOrNull(compareBy<Player>({ tier(it, role.id) }, { -Ratings.effectiveness(it, t, role) }))
            val nat = team.nationalOf
            fun freeAgents() = w.players.asSequence().filter { it.titleId == team.titleId && it.teamId < 0 && !it.retired && it.id !in busy && it.id !in out &&
                plays(it, role.id) && Lineups.available(w, it) && Dates.ageAt(it.birth, w.day) >= t.minAge && (nat.isEmpty() || it.nat == nat || it.nat2 == nat) &&
                (c == null || dev.uem.em.mgmt.Circuits.eligible(w, c, it) == null) && importsOk(w, team, out, i, it) }
            fun eff(p: Player) = Ratings.effectiveness(p, t, role) - 4 * tier(p, role.id)
            // 3. An AI club's choice for a long absence or a position nobody plays: sign cover, or promote its prospect.
            if (club && !user && long) {
                val org = w.orgs[team.orgId]
                // A free agent who can play today (no visa wait) and whom the club can pay.
                val fa = freeAgents().filter { !dev.uem.em.mgmt.Imports.isImport(w, it, team) }
                    .filter { salaryFor(t, it) <= org.balance * 0.5 }.maxByOrNull { eff(it) }
                // Promote or sign, whichever suits the club: a strong academy and a tight budget make the prospect the
                // better choice; a clearly better free agent is signed.
                val lean = (org.facilities.getOrElse(2) { 3 } - 3) * 1.5 + (if (fa != null && org.balance < salaryFor(t, fa) * 4) 4.0 else 0.0)
                val promote = kid != null && (fa == null || eff(kid) + lean >= eff(fa) - 3)
                if (!promote && fa != null) {
                    signCover(w, team, fa, absentP)
                    c?.registrations?.get(team.id)?.players?.let { if (fa.id !in it) it.add(fa.id) }
                    out[i] = fa.id
                    notes.add("$side|$i|${absentP?.id ?: -1}|${fa.id}|signed")
                    continue
                }
                if (kid != null) {
                    // A long absence: promoted to the main team while the regular is out (kept or sent back when the regular
                    // returns, see Academies.callUpsDaily); nobody at the position: promoted for good.
                    val permanent = absentP == null
                    dev.uem.em.mgmt.Academies.callUp(w, kid, team, absentP?.id ?: -1, permanent)
                    c?.registrations?.get(team.id)?.players?.let { if (kid.id !in it) it.add(kid.id) }
                    out[i] = kid.id
                    notes.add("$side|$i|${absentP?.id ?: -1}|${kid.id}|${if (permanent) "promoted" else "call-up"}")
                    continue
                }
            }
            // 2. A short absence (and your club, whatever the absence): an academy player is called up.
            if (kid != null) {
                dev.uem.em.mgmt.Academies.callUp(w, kid, team, absentP?.id ?: -1, false)
                // An emergency registration for the competition, when it keeps a list.
                c?.registrations?.get(team.id)?.players?.let { if (kid.id !in it) it.add(kid.id) }
                out[i] = kid.id
                notes.add("$side|$i|${absentP?.id ?: -1}|${kid.id}|call-up")
                if (user) w.inbox.add(Message(w.day, "Head coach", "Called up: ${kid.handle}",
                    "${kid.handle} comes up from ${academy.name} to play ${role.name}${if (c != null) " in ${c.short}" else ""}" +
                        (if (absentP != null) " while ${absentP.handle} is out (until ${Dates.format(back!!)}). They go back to the academy when ${absentP.handle} is available again" else ": nobody else on the roster plays the position") +
                        ". Keep them full-time or send them back from their page." + (if (long) suggestions(w, t, role, freeAgents().toList(), absentP) else "")))
                continue
            }
            // 4. Nobody in the academy: an unsigned player stands in for the day.
            val fa = freeAgents().maxByOrNull { eff(it) } ?: continue
            if (cur != null && Ratings.effectiveness(fa, t, role) < curEff - 5) continue
            val absent = absentP?.id ?: -1
            out[i] = fa.id
            notes.add("$side|$i|$absent|${fa.id}|stand-in")
            if (user && c != null) w.inbox.add(Message(w.day, "Head coach", "Stand-in: ${fa.handle}",
                "${fa.handle} (free agent) stands in at ${role.name} for ${c.short} today${if (absent >= 0) " while ${w.players[absent].handle} is out" else ""}: nobody available on the roster${if (club) " or in the academy" else ""} plays the position." +
                    (if (club && long) suggestions(w, t, role, freeAgents().toList() + fa, absentP) else "")))
        }
        return out to notes
    }

    /** A free agent's salary for an emergency contract (as AI clubs pay free agents). */
    private fun salaryFor(t: dev.uem.em.titles.TitleDef, p: Player) = (t.baseSalary * Math.pow(maxOf(20.0, p.ca) / 70, 4.0) / 100).roundToInt() * 100.0

    /** An AI club signs a free agent as cover until the end of the season. */
    private fun signCover(w: World, team: Team, p: Player, forP: Player?) {
        val t = Catalogue.get(team.titleId)
        p.teamId = team.id
        dev.uem.em.mgmt.Imports.onJoin(w, p, team)
        p.contractEnd = w.season
        p.salary = salaryFor(t, p)
        team.roster.add(p.id)
        team.lineupSig = 0
        p.career.add(dev.uem.em.model.CareerEntry(w.season, w.day, team.id, "signed"))
        w.transfers.add(dev.uem.em.model.Transfer(w.day, w.season, p.id, -1, team.id, 0.0, "free agent"))
        w.news.add(dev.uem.em.model.News(w.day, "${team.name} sign ${p.handle}", "${team.name} sign ${p.handle} (${t.role(p.mainRole).name}) until the end of the season" +
            (if (forP != null) " while ${forP.handle} is out." else ", as nobody on the roster plays the position."), listOf(team.id)))
    }

    /** Free agents your coach would sign for a long absence or an empty position (best three who play it). */
    private fun suggestions(w: World, t: dev.uem.em.titles.TitleDef, role: RoleDef, pool: List<Player>, absentP: Player?): String {
        val best = pool.distinctBy { it.id }.sortedByDescending { Ratings.effectiveness(it, t, role) }.take(3)
        if (best.isEmpty()) return ""
        return "\n\n" + (if (absentP != null) "${absentP.handle} is out for a long time" else "Nobody on the roster plays ${role.name}") +
            ": you can also sign a free agent who plays it (Market). Best available: " +
            best.joinToString { "${it.handle} (%.0f, ${it.nat})".format(Ratings.effectiveness(it, t, role)) } + "."
    }

    /** A substitution the coach would make before the next game, with its reason. */
    class Pick(val slot: Int, val inPid: Int, val reason: String, val score: Double)

    /**
     * The coach's best substitution for one side before the next game of a series (null: keep the lineup).
     * Reasons: a starter's bad game in a loss ("form"), a bench player much better on the next map ("map"),
     * a deeper champion pool for what is left under Fearless ("pool"), or the original starter back ("back").
     */
    fun suggest(s: LiveSeries, side: Int, mapId: String): Pick? {
        val w = s.w
        val t = s.t
        val team = s.teams[side]
        val last = s.games.lastOrNull() ?: return null
        val lost = last.winner != side
        val locked = s.locked()[side]
        var best: Pick? = null
        for (slot in t.lineupRoles.indices) {
            val role = t.lineupRoles[slot]
            val cur = s.lineups[side].getOrNull(slot)?.takeIf { it >= 0 } ?: continue
            val p = w.players[cur]
            val effP = Ratings.effectiveness(p, t, role)
            val rating = last.lines.firstOrNull { it.playerId == cur && it.team == side }?.rating ?: 1.0
            for (c in s.bench[side]) {
                val q = w.players[c]
                if (!plays(q, role.id) || !importsOk(w, team, s.lineups[side], slot, q)) continue
                val gap = effP - Ratings.effectiveness(q, t, role) + 2.0 * tier(q, role.id)
                var score = 0.0
                var why = ""
                fun offer(v: Double, r: String) { if (v > score) { score = v; why = r } }
                // The series' starter comes back once their replacement has had a poor game.
                if (s.starting[side].getOrNull(slot) == c && cur !in s.starting[side] && (lost || rating < 0.85)) offer(0.9 + (0.85 - rating).coerceAtLeast(0.0), "back")
                // A bad game in a loss: the coach freshens the position with someone not much weaker.
                if (lost && rating < 0.72 && gap < 6) offer((0.72 - rating) * 2.5 + (6 - gap) / 10, "form")
                // Shooters: the next map is the bench player's (and not the starter's).
                if (!t.isMoba && t.maps.size > 1) {
                    val edge = (q.profMap[mapId] ?: 20.0) - (p.profMap[mapId] ?: 20.0)
                    if (edge >= 14 && gap < 5) offer((edge - 14) / 20 + (5 - gap) / 12 + 0.3, "map")
                }
                // MOBAs under Fearless: what is left of each player's champion pool.
                if (t.isMoba && locked.isNotEmpty()) {
                    val edge = pool(q, locked) - pool(p, locked)
                    if (edge >= 9 && gap < 5) offer((edge - 9) / 15 + (5 - gap) / 12 + 0.25, "pool")
                }
                if (score > 0 && (best == null || score > best.score)) best = Pick(slot, c, why, score)
            }
        }
        return best
    }

    /** The best three champions a player has left (average proficiency), the locked ones excluded. */
    private fun pool(p: Player, locked: Set<String>): Double {
        val v = p.profChar.entries.filter { it.key !in locked }.map { it.value }.sortedDescending().take(3)
        return if (v.isEmpty()) 0.0 else v.average()
    }

    /** How often a suggested substitution is actually made (most coaches keep their five). */
    fun chance(p: Pick) = when (p.reason) {
        "back" -> 0.5
        "map" -> 0.3
        "pool" -> 0.25
        else -> (0.05 + 0.12 * p.score).coerceAtMost(0.22)
    }

    fun reasonText(r: String) = when (r) {
        "form" -> "after a difficult game"
        "map" -> "for the map"
        "pool" -> "for the champion pool left"
        "back" -> "the starter returns"
        "coach" -> "your call"
        "stand-in" -> "stand-in"
        "call-up" -> "called up from the academy"
        "promoted" -> "promoted from the academy"
        "signed" -> "signed as cover"
        else -> r
    }

    /** One note parsed: side, slot, player out (-1 none), player in, reason. */
    class Note(val side: Int, val slot: Int, val out: Int, val inPid: Int, val reason: String)
    fun parse(s: String): Note? {
        val a = s.split('|')
        if (a.size < 5) return null
        return Note(a[0].toIntOrNull() ?: return null, a[1].toIntOrNull() ?: return null, a[2].toIntOrNull() ?: -1, a[3].toIntOrNull() ?: return null, a[4])
    }

    /** "Game 2: Rekkles in for X at Support (after a difficult game)". */
    fun describe(w: World, t: dev.uem.em.titles.TitleDef, n: Note): String {
        val inn = w.players.getOrNull(n.inPid)?.handle ?: "?"
        val out = w.players.getOrNull(n.out)?.handle
        val role: RoleDef? = t.lineupRoles.getOrNull(n.slot)
        return if (n.reason == "stand-in") "$inn stands in at ${role?.name ?: "?"}${if (out != null) " for $out" else ""}"
        else if (n.reason == "call-up" || n.reason == "promoted") "$inn ${if (n.reason == "promoted") "promoted" else "called up"} from the academy at ${role?.name ?: "?"}${if (out != null) " for $out" else ""}"
        else if (n.reason == "signed") "$inn signed (free agent) at ${role?.name ?: "?"}${if (out != null) " for $out" else ""}"
        else "$inn in for ${out ?: "?"} at ${role?.name ?: "?"} (${reasonText(n.reason)})"
    }
}

package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.data.GameData
import dev.uem.em.model.Competition
import dev.uem.em.model.Message
import dev.uem.em.model.News
import dev.uem.em.model.Player
import dev.uem.em.model.SquadPick
import dev.uem.em.model.Staff
import dev.uem.em.model.Team
import dev.uem.em.model.TeamSeason
import dev.uem.em.model.World
import dev.uem.em.sim.Lineups
import dev.uem.em.titles.Catalogue
import kotlin.math.roundToInt

/**
 * National teams. A nation has no squad between events: selectors name one [SELECT_DAYS] days before
 * each event its team plays (an Esports Nations Cup qualifier, the Nations Cup), it plays the event,
 * and it steps down when the event ends (the squad is kept in the team's history).
 *
 * Selection: only players of the nationality (a second nationality counts only for a dual national
 * his first country would not pick; years at clubs abroad never make a player eligible). Every
 * position goes to the player in the best form there — this season's match ratings, trusted more the
 * more he played and the stronger his league, then ability — a player whose second position it is when
 * nobody plays it as his main one, an amateur of the position when the nation has nobody; two
 * substitutes who play a position. The head coach is the nation's coach with the best record and
 * results this season (a foreign coach when the nation has none), with an analyst. Each pick comes
 * with the selectors' reason, in the news, the inbox (your players and staff) and on social media.
 */
object Nations {
    const val SELECT_DAYS = 10

    // --------------------------------------------------------------------------------- eligibility

    /** Older saves: a nationality earned by residency becomes residency (import status only); empty squads between events. */
    fun migrate(w: World) {
        if (w.natFix >= 1) return
        for (p in w.players) if (p.nat2.isNotEmpty() && p.nat2 == p.resCountry && p.resYears >= 3) { p.residency = p.nat2; p.nat2 = "" }
        for (team in w.teams) if (team.nationalOf.isNotEmpty() && !team.active) stepDown(w, team, -1)
        // National teams no longer keep staff of their own: coaches are named with each squad.
        for (s in w.staff) w.teams.getOrNull(s.teamId)?.takeIf { it.nationalOf.isNotEmpty() }?.let { Staffing.release(w, s, "national team staff named per event", severance = false) }
        w.natFix = 1
    }

    /** Whether a player may be called up: his nationality, or his second one when his first country would not pick him. */
    fun eligible(w: World, p: Player, nat: String, pools: Map<String, List<Player>>?): Boolean {
        if (p.nat == nat) return true
        if (p.nat2 != nat) return false
        val home = pools?.get(p.nat) ?: w.players.filter { it.titleId == p.titleId && !it.retired && it.nat == p.nat }
        val sc = Extras.selectionScore(w, p)
        // A starter or the substitute at home stays with his first country.
        return home.count { it.id != p.id && it.mainRole == p.mainRole && !it.retired && Extras.selectionScore(w, it) > sc } >= 2
    }

    // --------------------------------------------------------------------------------- selection

    private fun form(w: World, p: Player): Pair<Int, Double> {
        var gp = 0; var sum = 0.0
        for (l in p.stats) if (l.season == w.season && l.titleId == p.titleId && w.teams.getOrNull(l.teamId)?.nationalOf.isNullOrEmpty()) { gp += l.gp; sum += l.ratingSum }
        return gp to (if (gp > 0) sum / gp else 0.0)
    }

    private fun why(w: World, p: Player, roleName: String, second: Boolean, sub: Boolean, best: Boolean): String {
        val (gp, r) = form(w, p)
        val club = w.teams.getOrNull(p.teamId)?.takeIf { it.nationalOf.isEmpty() }?.name ?: "free agent"
        val parts = ArrayList<String>()
        parts.add(if (sub) "substitute ($roleName)" else roleName + if (second) " — a second position: nobody plays it as a main one" else "")
        parts.add(club)
        parts.add(if (gp > 0) "rating %.2f over %d games this season".format(r, gp) else "no games this season (ability %.0f)".format(p.ca))
        if (best && gp >= 8) parts.add("the best form at the position")
        if (p.career.lastOrNull()?.type?.startsWith("amateur") == true) parts.add("an amateur: nobody else plays the position")
        return parts.joinToString(" · ")
    }

    /** A squad named: everything the announcement needs. */
    class Result(val team: Team, val compId: Int, val picked: List<Player>, val roleOf: Map<Int, String>, val second: Set<Int>, val starters: Int,
                 val coaches: List<Pair<Staff, String>>, val newIn: List<Int>, val dropped: List<Int>, val snubs: List<Int>)

    /** Name a nation's squad (and coaches) for a competition; [announce] records it and tells the news, inbox and fans. */
    fun select(w: World, team: Team, compId: Int, pool0: List<Player>? = null, pools: Map<String, List<Player>>? = null, announce: Boolean = true): Result {
        val t = Catalogue.get(team.titleId)
        val nat = team.nationalOf
        val base = pool0 ?: w.players.filter { (it.nat == nat || it.nat2 == nat) && it.titleId == team.titleId && !it.retired }
        val pool = base.filter { !it.retired && it.injuryUntil <= w.day && (it.nat == nat || it.nat2 == nat) && eligible(w, it, nat, pools) }
        val score = HashMap<Int, Double>()
        fun sc(p: Player) = score.getOrPut(p.id) { Extras.selectionScore(w, p) }
        val picked = ArrayList<Player>()
        val roleOf = HashMap<Int, String>()
        val second = HashSet<Int>()
        // Every position: the main-position player in the best form; then a second-position player.
        for (role in t.lineup) pool.filter { it.mainRole == role && it !in picked }.maxByOrNull { sc(it) }?.let { picked.add(it); roleOf[it.id] = role }
        for (role in t.lineup) if (roleOf.values.none { it == role }) pool.filter { it !in picked && it.roles.getOrNull(1) == role }.maxByOrNull { sc(it) }?.let { picked.add(it); roleOf[it.id] = role; second.add(it.id) }
        // Nobody plays the position: an amateur of the position (created once and kept, so he can be scouted and signed).
        val country = GameData.countries[nat]
        val scene = (if (t.isMoba) country?.moba else country?.shooter) ?: 15
        for ((k, role) in t.lineup.filter { r -> roleOf.values.none { it == r } }.withIndex()) {
            val existing = w.players.firstOrNull { it.nat == nat && it.titleId == t.id && !it.retired && it.mainRole == role && it.career.any { c -> c.type == "amateur (national call-up)" } && it !in picked }
            val p = existing ?: run {
                val rng = Rng.derive(w.seed, "national", team.titleId, nat, role, k, w.season)
                val ctx = dev.uem.em.gen.GenCtx(w, rng)
                dev.uem.em.gen.PlayerGen.generate(ctx, team.titleId, role, nat, rng.int(17, 26), clamp(20.0 + scene * 0.36 + rng.normal(0.0, 3.5), 18.0, 58.0), rng.float(0.0, 8.0)).also {
                    it.career.add(dev.uem.em.model.CareerEntry(w.season, w.day, -1, "amateur (national call-up)"))
                }
            }
            picked.add(p); roleOf[p.id] = role
        }
        val starters = picked.toList()
        // Two substitutes who play a position (main or second), the weakest starter's position first.
        val weakest = starters.minByOrNull { sc(it) }?.let { roleOf[it.id] }
        repeat(2) {
            pool.filter { it !in picked && (it.mainRole in t.lineup || it.roles.getOrNull(1) in t.lineup) }
                .maxByOrNull { sc(it) + (if (it.mainRole == weakest || it.roles.getOrNull(1) == weakest) 3.0 else 0.0) }?.let { picked.add(it); roleOf[it.id] = it.mainRole.takeIf { r -> r in t.lineup } ?: it.roles[1] }
        }
        val before = team.roster.toSet()
        // Compared with the last squad (announced, or the last one to play an event).
        val prevSquad = w.squads.lastOrNull { it.teamId == team.id }?.players?.toSet() ?: team.history.lastOrNull { it.compId >= 0 }?.roster?.toSet() ?: before
        team.roster.clear()
        team.roster.addAll(picked.map { it.id })
        team.active = true
        team.manualLineup = false
        team.squadFor = compId
        team.lineup = Lineups.auto(w, team)
        val squadElo = 1500 + (starters.map { it.ca }.average() - 65) * 18
        team.elo = if (team.elo == 1500.0 && team.trophies.isEmpty()) squadElo else team.elo * 0.5 + squadElo * 0.5
        val coaches = appointCoaches(w, team)
        val newIn = picked.map { it.id }.filter { it !in prevSquad }
        val dropped = prevSquad.filter { it !in team.roster }
        // Notable players of the nationality left out (in the conversation and the news).
        val snubs = pool.filter { it !in picked && it.teamId >= 0 && w.teams[it.teamId].nationalOf.isEmpty() }.sortedByDescending { it.ca }.take(2).map { it.id }
        val res = Result(team, compId, picked, roleOf, second, starters.size, coaches, newIn, dropped, snubs)
        if (announce) record(w, res)
        Extras.lastChanges = newIn to dropped
        return res
    }

    /** The head coach (best record and results this season among the nation's coaches) and an analyst; they keep their clubs. */
    private fun appointCoaches(w: World, team: Team): List<Pair<Staff, String>> {
        val nat = team.nationalOf
        val idx = Staffing.titles(w)
        val season = HashMap<Int, IntArray>()
        for (f in w.fixtures) if (f.done && f.titleId == team.titleId && f.day > w.day - 200 && f.a >= 0 && f.b >= 0) {
            season.getOrPut(f.a) { IntArray(2) }[if (f.winner == f.a) 0 else 1]++
            season.getOrPut(f.b) { IntArray(2) }[if (f.winner == f.b) 0 else 1]++
        }
        fun winRate(s: Staff) = season[s.teamId]?.let { if (it.sum() >= 4) it[0].toDouble() / it.sum() else null }
        fun coachScore(s: Staff): Double {
            val titles = Staffing.honours(w, s, idx).filter { !it.past && it.season >= w.season - 1 }.sumOf { it.pts }
            return s.quality + s.rep * 0.25 + (winRate(s)?.let { (it - 0.5) * 30 } ?: 0.0) + titles * 0.8
        }
        val out = ArrayList<Pair<Staff, String>>()
        // One national job at a time: coaches already named by another nation for an upcoming or current event are out.
        val busy = w.teams.filter { it.nationalOf.isNotEmpty() && it.id != team.id && it.squadFor >= 0 }.flatMap { it.staffIds }.toSet()
        val hcs = w.staff.filter { !it.retired && it.titleId == team.titleId && it.role == "head-coach" && it.id !in busy }
        // The nation's own head coaches; else a national-team coach of the country (created once, kept between events).
        val hc = hcs.filter { it.nat == nat }.maxByOrNull { coachScore(it) }
            ?: w.staff.firstOrNull { it.realSrc == "national:$nat:${team.titleId}" && !it.retired }
            ?: run {
                val country = GameData.countries[nat]
                val scene = (if (Catalogue.get(team.titleId).isMoba) country?.moba else country?.shooter) ?: 15
                Staffing.create(w, Rng.derive(w.seed, "national-coach", nat, team.titleId), "head-coach", nat, team.titleId, clamp(28.0 + scene * 0.4, 25.0, 62.0)).also { it.realSrc = "national:$nat:${team.titleId}" }
            }
        run {
            val wr = winRate(hc)
            val note = listOfNotNull(
                w.teams.getOrNull(hc.teamId)?.let { "head coach of ${it.name}" } ?: if (hc.realSrc.startsWith("national:")) "the federation's own coach" else "free agent",
                wr?.let { "won %d%% of series this season".format((it * 100).roundToInt()) },
                hc.past.count { it.split('|').getOrNull(5) == "1" && it.split('|').getOrNull(6) == "1" }.takeIf { it > 0 }?.let { "$it titles as head coach before the save" },
                "reputation ${hc.rep.roundToInt()}",
            ).joinToString(" · ")
            out.add(hc to note)
        }
        w.staff.filter { !it.retired && it.titleId == team.titleId && it.nat == nat && it.role in setOf("analyst", "strategic-coach") && it.id != hc.id && it.id !in busy }.maxByOrNull { it.quality + it.rep * 0.2 }
            ?.let { a -> out.add(a to "${Staffing.label(a).lowercase()}${w.teams.getOrNull(a.teamId)?.let { " at ${it.name}" } ?: ""} · ability ${a.quality.roundToInt()}") }
        team.staffIds.clear()
        team.staffIds.addAll(out.map { it.first.id })
        Staffing.epoch++
        return out
    }

    fun record(w: World, r: Result) {
        val team = r.team; val compId = r.compId; val picked = r.picked; val roleOf = r.roleOf; val second = r.second; val starters = r.starters
        val coaches = r.coaches; val newIn = r.newIn; val dropped = r.dropped; val snubs = r.snubs
        val t = Catalogue.get(team.titleId)
        val c = w.comps.getOrNull(compId)
        val bestByRole = HashMap<String, Int>()
        val notes = picked.mapIndexed { i, p ->
            val role = roleOf[p.id] ?: p.mainRole
            val isBest = i < starters && bestByRole.putIfAbsent(role, p.id) == null
            why(w, p, t.role(role).name, p.id in second, i >= starters, isBest)
        }
        // Squads worth keeping (and announcing): the stronger nations of each event, yours and followed ones.
        val rank = c?.let { cc -> w.teams.filter { it.titleId == team.titleId && it.nationalOf.isNotEmpty() && it.squadFor == cc.id }.sortedByDescending { it.elo }.indexOf(team) } ?: 0
        val mine = picked.any { w.isUserTeam(it.teamId) } || coaches.any { w.isUserTeam(it.first.teamId) } || team.id in w.followed
        val notable = rank in 0 until 12 || mine || (c != null && c.entrants.none { it.startsWith("continent:") })
        if (!notable) return
        w.squads.add(SquadPick(w.day, team.id, compId, team.titleId, picked.map { it.id }, picked.map { roleOf[it.id] ?: it.mainRole }, notes,
            coaches.map { it.first.id }, coaches.map { it.second }, newIn, dropped, snubs))
        w.squads.removeAll { it.day < w.day - 730 }
        val event = c?.short ?: "the next event"
        val lines = picked.mapIndexed { i, p -> "${p.handle}: ${notes[i]}" } + coaches.map { (s, n) -> "${Staffing.label(s)} ${s.name()}: $n" }
        val changes = if (newIn.isEmpty() && dropped.isEmpty()) "Unchanged from the last squad." else
            "In: ${newIn.joinToString { w.players[it].handle }.ifEmpty { "none" }}; out: ${dropped.joinToString { w.players[it].handle }.ifEmpty { "none" }}."
        w.news.add(News(w.day, "${team.name} name their ${t.short} squad for $event", "$changes\n" + lines.joinToString("\n"), listOf(team.id)))
        // Your players and staff hear it first.
        for ((i, p) in picked.withIndex()) if (w.isUserTeam(p.teamId))
            w.inbox.add(Message(w.day, "${team.name} selectors", "${p.handle} called up by ${team.name}", "${p.handle} joins ${team.name} for $event: ${notes[i]}. Back with you when the event ends."))
        for ((s, n) in coaches) if (w.isUserTeam(s.teamId))
            w.inbox.add(Message(w.day, "${team.name} selectors", "${s.name()} named ${Staffing.label(s).lowercase()} of ${team.name}", "${s.name()} works with ${team.name} for $event ($n) and keeps the club job."))
    }

    /** The event is over: the squad steps down (kept in the history) and the team is empty until the next one. */
    fun stepDown(w: World, team: Team, compId: Int) {
        if (team.roster.isNotEmpty() && compId >= 0) {
            val c = w.comps[compId]
            val place = c.placements.indexOf(team.id).let { if (it >= 0) it + 1 else 0 }
            var wn = 0; var ls = 0
            for (f in w.fixtures) if (f.compId == compId && f.done && (f.a == team.id || f.b == team.id)) { if (f.winner == team.id) wn++ else ls++ }
            team.history.add(TeamSeason(c.season, -1, place, wn, ls, team.elo, team.roster.toIntArray(), compId))
        }
        team.roster.clear()
        team.lineup = ArrayList()
        team.staffIds.clear()
        team.squadFor = -1
        team.active = false
        Staffing.epoch++
    }

    // --------------------------------------------------------------------------------- the window

    private fun national(c: Competition) = c.entrants.any { it.startsWith("continent:") || it.startsWith("nations:") || it.startsWith("qual:") }

    /** The nations an upcoming event will have (null while a qualifier is still being played). */
    private fun nationsFor(w: World, c: Competition): List<Team>? {
        val out = LinkedHashSet<Team>()
        for (rule in c.entrants) {
            val p = rule.split(':')
            when (p[0]) {
                "continent" -> Extras.nationsOf(p.drop(2).joinToString(":")).forEach { out.add(Extras.nationalTeam(w, p[1], it)) }
                "nations" -> Extras.strongestNations(w, p[1], p[2].toInt()).forEach { out.add(Extras.nationalTeam(w, p[1], it)) }
                "qual" -> {
                    val src = w.comps.getOrNull(p[1].toIntOrNull() ?: -1) ?: continue
                    if (!src.done) return null
                    src.placements.map { w.teams[it] }.filter { it.nationalOf.isNotEmpty() }.take(p[2].toInt()).forEach { out.add(it) }
                }
            }
        }
        return out.toList()
    }

    /** Daily: selectors name squads for national events starting within [SELECT_DAYS] days. */
    fun daily(w: World) {
        for (c in w.comps) {
            if (c.done || !national(c) || (c.status == 0 && c.startDay - w.day !in 0..SELECT_DAYS)) continue
            val teams = nationsFor(w, c) ?: continue
            // An event already under way (an older save, a late entrant) names any squad it is missing at once.
            val todo = teams.filter { it.squadFor != c.id && (c.status == 0 || it.roster.isEmpty()) }
            if (todo.isEmpty()) continue
            val all = w.players.filter { it.titleId == c.titleId && !it.retired }
            val byNat = all.groupBy { it.nat }
            val dual = all.filter { it.nat2.isNotEmpty() }.groupBy { it.nat2 }
            // Select every squad first (so the strength order is known), then record and announce them.
            val res = todo.map { team -> select(w, team, c.id, byNat[team.nationalOf].orEmpty() + dual[team.nationalOf].orEmpty(), byNat, announce = false) }
            for (r in res.sortedByDescending { it.team.elo }) record(w, r)
        }
    }
}

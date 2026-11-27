package dev.uem.em.comp

import dev.uem.em.game.BoxStore
import dev.uem.em.model.Competition
import dev.uem.em.model.CompTier
import dev.uem.em.model.StageType
import dev.uem.em.model.World

/**
 * Read-only consistency checks of the competition ecosystem (nothing is changed): fixtures, results,
 * standings, brackets, Swiss pairings, the qualification ledger, last-chance and best-of-the-rest
 * eligibility, academies, rosters and leagues. Used by the long-run harness every season and by the
 * save loader (a damaged save is reported, never silently "repaired").
 *
 * A violation is a broken rule; a warning is an allowed but noteworthy case (an unavoidable Swiss rematch).
 */
object Invariants {
    class Report {
        val violations = ArrayList<String>()
        val warnings = ArrayList<String>()
        val counts = LinkedHashMap<String, Int>()
        fun bad(s: String) { if (violations.size < 200) violations.add(s) }
        fun warn(s: String) { if (warnings.size < 200) warnings.add(s) }
        fun count(k: String, n: Int = 1) { counts.merge(k, n, Int::plus) }
        val ok get() = violations.isEmpty()
    }

    private val ELIM = setOf(StageType.SINGLE_ELIM, StageType.DOUBLE_ELIM)

    /** What kind of event a competition is, for the per-kind counts ("tier-1 league", "academy league", "international"...). */
    fun kindOf(w: World, c: Competition): String {
        val league = w.leagues.getOrNull(c.leagueId)
        return when {
            c.customId >= 0 -> "custom"
            c.circuit.isNotBlank() -> "development (${c.circuit})"
            c.promoLeague >= 0 -> "promotion / relegation"
            c.defKey.contains(":rf:") -> "regional final (LCQ)"
            c.defKey.endsWith(":playin") -> "play-in"
            c.defKey.contains(":oq:") || c.defKey.contains(":rq:") || c.entrants.any { it.startsWith("open:") } -> "open / regional qualifier"
            c.defKey.endsWith(":cacl") -> "academy champions league"
            c.defKey.endsWith(":dcgi") -> "best of the rest"
            c.defKey.endsWith(":rivalry") || c.defKey.endsWith(":allstar") -> "rivalry cup / all-star"
            Season.isEncQualifier(c) || c.defKey.endsWith(":enc") -> "nations"
            c.tier == CompTier.WORLDS -> "world championship"
            c.tier == CompTier.INTERNATIONAL -> "international"
            league?.academy == true -> "academy league"
            league != null && league.tier == 1 -> "tier-1 league"
            league != null -> "tier-${league.tier} league"
            else -> "other"
        }
    }

    /** Every check for the competitions of one season ([boxScores]: athletes in one series a day, from the stored box scores). */
    fun season(w: World, season: Int, boxScores: Boolean = false, r: Report = Report()): Report {
        val comps = w.comps.filter { it.season == season }
        for (c in comps) competition(w, c, r)
        if (boxScores) athletes(w, comps, r)
        // Teams: one series a day.
        val byDay = HashMap<Long, Int>()
        for (c in comps) for (st in c.stages) for (fid in st.fixtureIds) {
            val f = w.fixtures[fid]
            if (!f.done) continue
            for (tm in intArrayOf(f.a, f.b)) if (byDay.merge(tm.toLong() * 100000 + f.day, 1, Int::plus) == 2) r.bad("${w.teams[tm].name} plays twice on day ${f.day} (season $season)")
        }
        return r
    }

    fun competition(w: World, c: Competition, r: Report) {
        val name = "${c.name} (${c.season}, #${c.id})"
        r.count("competitions")
        r.count("kind: ${kindOf(w, c)}")
        if (c.status >= 1) {
            // Entrants: the ledger's "in" entries are exactly the field, each team once.
            if (c.teamIds.size != c.teamIds.toSet().size) r.bad("$name: a team entered twice")
            val ins = c.entries.filter { it.status == "in" }
            if (ins.map { it.teamId }.toSet() != c.teamIds.toSet() && c.entries.isNotEmpty()) r.bad("$name: ledger and field differ (${ins.size} in the ledger, ${c.teamIds.size} entered)")
            if (ins.size != ins.map { it.teamId }.toSet().size) r.bad("$name: a team holds two places")
            val berth = ins.filter { it.src >= 0 && it.place >= 0 }.groupBy { "${it.route}|${it.src}|${it.place}" }
            for ((k, v) in berth) if (v.size > 1) r.bad("$name: one berth given twice ($k)")
            for (e in ins) {
                val tm = w.teams.getOrNull(e.teamId)
                if (tm == null) { r.bad("$name: unknown entrant ${e.teamId}"); continue }
                if (tm.titleId != c.titleId) r.bad("$name: ${tm.name} is from another game")
                // Traceable: a placing-based route names the source edition and the placing the team really had.
                if (e.src >= 0 && e.place >= 0) {
                    val src = w.comps.getOrNull(e.src)
                    when {
                        src == null -> r.bad("$name: ${tm.name}'s source edition ${e.src} does not exist")
                        !src.done -> r.bad("$name: ${tm.name} qualified from ${src.name}, which has not finished")
                        src.placements.getOrNull(e.place) != e.teamId -> r.bad("$name: ${tm.name} qualified as #${e.place + 1} of ${src.name}, but finished #${src.placements.indexOf(e.teamId) + 1}")
                    }
                }
            }
            for (tid in c.teamIds) if (w.teams.getOrNull(tid)?.titleId != c.titleId) r.bad("$name: entrant $tid is not a ${c.titleId} team")
            // Academies: never in their parent's league tournaments' promotion series, and only in events open to them.
            for (tid in c.teamIds) {
                val tm = w.teams[tid]
                if (tm.parentId < 0) continue
                if (c.promoLeague >= 0 && w.teams[tm.parentId].leagueId == c.promoLeague) r.bad("$name: academy ${tm.name} in its parent's promotion series")
                if ((c.tier == CompTier.WORLDS || c.tier == CompTier.INTERNATIONAL) && !c.defKey.endsWith(":cacl") && c.circuit.isBlank() && c.customId < 0) r.bad("$name: academy ${tm.name} at a senior international event")
            }
            entryRules(w, c, name, r)
        }
        val fixtures = c.stages.flatMap { it.fixtureIds }
        if (fixtures.size != fixtures.toSet().size) r.bad("$name: a series listed twice")
        var lastDay = Int.MIN_VALUE
        for ((si, st) in c.stages.withIndex()) {
            val fs = st.fixtureIds.mapNotNull { w.fixtures.getOrNull(it) }
            if (fs.size != st.fixtureIds.size) { r.bad("$name ${st.name}: series that do not exist"); continue }
            if (fs.isNotEmpty() && lastDay != Int.MIN_VALUE && fs.minOf { it.day } < lastDay) r.bad("$name: ${st.name} starts (day ${fs.minOf { it.day }}) before the previous stage ended (day $lastDay)")
            if (fs.isNotEmpty()) lastDay = fs.maxOf { it.day }
            for (f in fs) {
                r.count("series")
                if (f.compId != c.id || f.stageIdx != si || f.titleId != c.titleId) { r.bad("$name ${st.name}: series ${f.id} points elsewhere"); continue }
                if (f.a == f.b || f.a !in w.teams.indices || f.b !in w.teams.indices) { r.bad("$name ${st.name}: invalid pairing ${f.a} v ${f.b}"); continue }
                if (f.a !in st.teamIds || f.b !in st.teamIds) r.bad("$name ${st.name}: ${w.teams[f.a].short} v ${w.teams[f.b].short} — a team not in the stage")
                if (f.bestOf < 1) r.bad("$name: series ${f.id} best of ${f.bestOf}")
                if (f.done) {
                    r.count("series played"); r.count("games", f.scoreA + f.scoreB)
                    val ws = if (f.winner == f.a) f.scoreA else f.scoreB
                    val ls = if (f.winner == f.a) f.scoreB else f.scoreA
                    when {
                        f.winner != f.a && f.winner != f.b -> r.bad("$name: series ${f.id} winner ${f.winner} played neither side")
                        ws < ls -> r.bad("$name: series ${f.id} won ${ws}-${ls} by the side with fewer games")
                        f.scoreA + f.scoreB > f.bestOf -> r.bad("$name: series ${f.id} ${f.scoreA}-${f.scoreB} in a best of ${f.bestOf}")
                        f.bestOf % 2 == 1 && ws != f.bestOf / 2 + 1 -> r.bad("$name: series ${f.id} best of ${f.bestOf} ended ${f.scoreA}-${f.scoreB}")
                    }
                } else if (c.done) r.bad("$name: finished with series ${f.id} unplayed")
            }
            if (st.type !in ELIM && st.status >= 1) standings(w, name, st, fs, r)
            when (st.type) {
                StageType.RR, StageType.DRR, StageType.GROUPS -> roundRobin(w, name, st, fs, r)
                StageType.SWISS -> swiss(w, name, st, fs, r)
                else -> bracket(w, name, st, fs, r)
            }
            if (c.done && st.status != 2 && c.teamIds.size >= 2) r.bad("$name: finished with ${st.name} not finished")
        }
        if (c.done) {
            if (c.placements.size != c.placements.toSet().size) r.bad("$name: a team placed twice")
            if (!c.teamIds.containsAll(c.placements)) r.bad("$name: placed a team that did not enter")
            val champ = c.placements.firstOrNull()
            if (champ != null && c.teamIds.size >= 2) {
                val n = w.teams[champ].trophies.count { it.compId == c.id }
                if (n != 1) r.bad("$name: the champion holds $n trophies for it")
                val fin = c.stages.lastOrNull()?.bracket?.firstOrNull { it.isFinal }
                if (fin != null && fin.winner >= 0 && fin.winner != champ) r.bad("$name: champion ${w.teams[champ].short} did not win the final")
            }
        }
    }

    private fun standings(w: World, name: String, st: dev.uem.em.model.Stage, fs: List<dev.uem.em.model.Fixture>, r: Report) {
        val win = HashMap<Int, IntArray>()
        for (f in fs) if (f.done) {
            val a = win.getOrPut(f.a) { IntArray(4) }; val b = win.getOrPut(f.b) { IntArray(4) }
            if (f.winner == f.a) { a[0]++; b[1]++ } else { b[0]++; a[1]++ }
            a[2] += f.scoreA; a[3] += f.scoreB; b[2] += f.scoreB; b[3] += f.scoreA
        }
        for (row in st.standings) {
            val x = win[row.teamId] ?: IntArray(4)
            if (row.w != x[0] || row.l != x[1] || row.gw != x[2] || row.gl != x[3]) {
                r.bad("$name ${st.name}: ${w.teams[row.teamId].short} table ${row.w}-${row.l} (${row.gw}-${row.gl}) but results ${x[0]}-${x[1]} (${x[2]}-${x[3]})"); return
            }
        }
        if (st.standings.map { it.teamId }.toSet() != st.teamIds.toSet()) r.bad("$name ${st.name}: table and teams differ")
    }

    private fun roundRobin(w: World, name: String, st: dev.uem.em.model.Stage, fs: List<dev.uem.em.model.Fixture>, r: Report) {
        if (st.status != 2) return
        val times = if (st.type == StageType.DRR) 2 else 1
        val group = st.standings.associate { it.teamId to it.group }
        val meet = HashMap<Long, Int>()
        for (f in fs) {
            if (st.type == StageType.GROUPS && (group[f.a] != f.group || group[f.b] != f.group)) r.bad("$name ${st.name}: ${w.teams[f.a].short} v ${w.teams[f.b].short} across groups")
            meet.merge((minOf(f.a, f.b).toLong() shl 32) or maxOf(f.a, f.b).toLong(), 1, Int::plus)
        }
        val ids = st.teamIds
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            if (st.type == StageType.GROUPS && group[ids[i]] != group[ids[j]]) continue
            val n = meet[(minOf(ids[i], ids[j]).toLong() shl 32) or maxOf(ids[i], ids[j]).toLong()] ?: 0
            if (n != times) { r.bad("$name ${st.name}: ${w.teams[ids[i]].short} and ${w.teams[ids[j]].short} met $n times (round robin x$times)"); return }
        }
    }

    /** Swiss: each round pairs teams on the same record before the match (one cross-record pairing per odd-sized record group). */
    private fun swiss(w: World, name: String, st: dev.uem.em.model.Stage, fs: List<dev.uem.em.model.Fixture>, r: Report) {
        val rec = HashMap<Int, IntArray>()
        for (tid in st.teamIds) rec[tid] = IntArray(2)
        val seen = HashSet<Long>()
        for ((round, games) in fs.groupBy { it.round }.toSortedMap()) {
            val groups = games.flatMap { listOf(it.a, it.b) }.groupBy { rec[it]!!.let { x -> x[0] * 100 + x[1] } }
            val odd = groups.values.count { it.size % 2 == 1 }
            var cross = 0
            for (f in games) {
                val ra = rec[f.a]!!; val rb = rec[f.b]!!
                if (ra[0] >= st.swissWins || ra[1] >= st.swissLosses || rb[0] >= st.swissWins || rb[1] >= st.swissLosses) r.bad("$name ${st.name} round ${round + 1}: a team that had already advanced or gone out played again")
                if (ra[0] != rb[0] || ra[1] != rb[1]) cross++
                val k = (minOf(f.a, f.b).toLong() shl 32) or maxOf(f.a, f.b).toLong()
                if (!seen.add(k)) r.warn("$name ${st.name} round ${round + 1}: ${w.teams[f.a].short} v ${w.teams[f.b].short} again (no other pairing left)")
            }
            if (cross > odd) r.bad("$name ${st.name} round ${round + 1}: $cross pairings across records, only $odd allowed")
            r.count("swiss rounds")
            if (games.any { !it.done }) break
            for (f in games) { val wi = f.winner; val lo = if (wi == f.a) f.b else f.a; rec[wi]!![0]++; rec[lo]!![1]++ }
        }
        if (st.status == 2) for ((tid, x) in rec) {
            if (x[0] < st.swissWins && x[1] < st.swissLosses && st.teamIds.size > 2) {
                // Allowed only when the last round could not be paired (fewer than two teams left on a live record).
                val live = rec.values.count { it[0] < st.swissWins && it[1] < st.swissLosses }
                if (live >= 2) r.bad("$name ${st.name}: ${w.teams[tid].short} stopped at ${x[0]}-${x[1]} with $live teams still live")
            }
        }
    }

    private fun bracket(w: World, name: String, st: dev.uem.em.model.Stage, fs: List<dev.uem.em.model.Fixture>, r: Report) {
        val losses = HashMap<Int, Int>()
        for (nd in st.bracket) {
            if (nd.fixtureId >= 0) {
                val f = w.fixtures[nd.fixtureId]
                if (f.nodeId != nd.id) r.bad("$name ${st.name}: node ${nd.id} and series ${f.id} disagree")
                if (f.done && (nd.winner != f.winner)) r.bad("$name ${st.name}: ${nd.label} winner ${nd.winner} but series won by ${f.winner}")
                if (f.done) losses.merge(if (f.winner == f.a) f.b else f.a, 1, Int::plus)
            }
            if (st.status == 2 && nd.winner == -2 && !(nd.teamA == -1 && nd.teamB == -1)) r.bad("$name ${st.name}: ${nd.label} (${nd.id}) never resolved")
        }
        if (st.type == StageType.SINGLE_ELIM) for ((t, n) in losses) if (n > 1) r.bad("$name ${st.name}: ${w.teams[t].short} lost $n times in a single-elimination bracket")
        if (st.type == StageType.DOUBLE_ELIM) for ((t, n) in losses) if (n > 2) r.bad("$name ${st.name}: ${w.teams[t].short} lost $n times in a double-elimination bracket")
        if (fs.any { it.nodeId.isEmpty() }) r.bad("$name ${st.name}: a bracket series without a node")
    }

    /** Last-chance qualifiers and best-of-the-rest events: nobody with a direct place / nobody at the world championship. */
    private fun entryRules(w: World, c: Competition, name: String, r: Report) {
        for (rule in c.entrants) {
            val p = rule.split(':')
            when (p[0]) {
                "lcq" -> {
                    val main = w.comps.getOrNull(p[1].toIntOrNull() ?: -1) ?: continue
                    val events = listOf(main) + w.comps.filter { it.season == main.season && it.defKey == main.defKey + ":playin" }
                    for (ev in events) for (e in ev.entries) if (e.status == "in" && e.teamId in c.teamIds && e.src != c.id && e.route.startsWith("circuit:")) r.bad("$name: ${w.teams[e.teamId].short} played the last-chance qualifier with a direct place at ${ev.name}")
                }
                "rest" -> {
                    val main = w.comps.getOrNull(p[1].toIntOrNull() ?: -1) ?: continue
                    val at = HashSet(main.teamIds)
                    for (pi in w.comps.filter { it.season == main.season && it.defKey == main.defKey + ":playin" }) at.addAll(pi.teamIds)
                    for (tid in c.teamIds) if (tid in at) r.bad("$name: ${w.teams[tid].short} is also at ${main.name}")
                }
            }
        }
    }

    /** Athletes: nobody plays two series on one day (stored box scores; academy call-ups count once). */
    private fun athletes(w: World, comps: List<Competition>, r: Report) {
        val seen = HashMap<Long, Int>()
        for (c in comps) for (st in c.stages) for (fid in st.fixtureIds) {
            val f = w.fixtures[fid]
            if (!f.done) continue
            val rec = BoxStore.get(w, f.id) ?: continue
            r.count("box scores read")
            val pids = rec.games.flatMap { g -> g.lines.map { it.playerId } }.toSet()
            for (pid in pids) {
                val k = pid.toLong() * 100000 + f.day
                val other = seen.put(k, f.id)
                if (other != null && other != f.id) r.bad("${w.players[pid].handle} played two series on day ${f.day}: ${w.comps[w.fixtures[other].compId].name} and ${c.name}")
            }
        }
    }

    /** The world as it stands: rosters, league membership, academies, transfers, awards and reform records. */
    fun world(w: World, r: Report = Report()): Report {
        val onRoster = HashMap<Int, Int>()
        val onDev = HashMap<Int, Int>()
        for (tm in w.teams) {
            if (!tm.active) continue
            if (tm.nationalOf.isNotEmpty()) continue // national squads and all-star squads borrow club players
            if (tm.kind.isNotEmpty()) {
                // Development clubs (amateur, collegiate, Game Changers) field unsigned players: one such club each.
                for (pid in tm.roster) {
                    val p = w.players.getOrNull(pid) ?: run { r.bad("${tm.name}: unknown player $pid"); null } ?: continue
                    onDev.put(pid, tm.id)?.let { other -> r.bad("${p.handle} is on two development rosters: ${w.teams[other].name} and ${tm.name}") }
                    if (p.titleId != tm.titleId) r.bad("${p.handle} (${p.titleId}) on a ${tm.titleId} roster")
                    if (p.teamId >= 0 && p.teamId != tm.id) r.warn("${p.handle} signed by ${w.teams[p.teamId].name} and still on ${tm.name}'s roster")
                }
                continue
            }
            for (pid in tm.roster) {
                val p = w.players.getOrNull(pid)
                if (p == null) { r.bad("${tm.name}: unknown player $pid"); continue }
                onRoster.put(pid, tm.id)?.let { other -> r.bad("${p.handle} is on two rosters: ${w.teams[other].name} and ${tm.name}") }
                if (p.teamId != tm.id) r.bad("${p.handle} on ${tm.name}'s roster but registered with team ${p.teamId}")
                if (p.titleId != tm.titleId) r.bad("${p.handle} (${p.titleId}) on a ${tm.titleId} roster")
                if (p.retired) r.bad("${p.handle} is retired but on ${tm.name}'s roster")
            }
        }
        for (p in w.players) if (p.teamId >= 0 && !p.retired) {
            val tm = w.teams.getOrNull(p.teamId)
            if (tm == null || (tm.active && tm.nationalOf.isEmpty() && tm.kind.isEmpty() && p.id !in tm.roster)) r.bad("${p.handle} belongs to team ${p.teamId} but is not on its roster")
        }
        val inLeague = HashMap<Int, Int>()
        for (l in w.leagues) {
            if (l.until > 0 && l.teamIds.isNotEmpty()) r.bad("${l.name} ended in ${l.until} but still has clubs")
            for (tid in l.teamIds) {
                inLeague.put(tid, l.id)?.let { other -> r.bad("${w.teams[tid].name} is in ${w.leagues[other].short} and ${l.short}") }
                val tm = w.teams[tid]
                if (tm.leagueId != l.id) r.bad("${tm.name} listed in ${l.short} but belongs to league ${tm.leagueId}")
                if (tm.titleId != l.titleId) r.bad("${tm.name} (${tm.titleId}) in ${l.short} (${l.titleId})")
                if (tm.parentId >= 0 && w.teams[tm.parentId].leagueId == l.id) r.bad("academy ${tm.name} in its parent's league ${l.short}")
            }
        }
        for (tm in w.teams) if (tm.active && tm.leagueId >= 0 && inLeague[tm.id] != tm.leagueId) r.bad("${tm.name} belongs to league ${tm.leagueId} but is not listed there")
        // An academy league holds the second teams of the league above it.
        for (l in w.leagues) if (l.academy && l.until == 0) for (tid in l.teamIds) {
            val tm = w.teams[tid]
            if (tm.parentId < 0) r.bad("${l.short}: ${tm.name} is not a second team")
            else if (l.upperId >= 0 && w.teams[tm.parentId].leagueId != l.upperId) r.bad("${l.short}: ${tm.name}'s club plays in ${w.leagues.getOrNull(w.teams[tm.parentId].leagueId)?.short ?: "no league"}, not ${w.leagues[l.upperId].short}")
        }
        for (tm in w.teams) if (tm.parentId >= 0) {
            val parent = w.teams.getOrNull(tm.parentId)
            if (parent == null || parent.titleId != tm.titleId || parent.orgId != tm.orgId) r.bad("academy ${tm.name}: parent ${tm.parentId} is not its organisation's ${tm.titleId} team")
        }
        // Organisations: a team belongs to the organisation that lists it, in one game per team.
        val ownedBy = HashMap<Int, Int>()
        for (o in w.orgs) for (tid in o.teams) {
            ownedBy.put(tid, o.id)?.let { other -> r.bad("${w.teams[tid].name} listed by two organisations (${w.orgs[other].short}, ${o.short})") }
            if (w.teams[tid].orgId != o.id) r.bad("${w.teams[tid].name} listed by ${o.short} but owned by ${w.teams[tid].orgId}")
            if (!o.balance.isFinite()) r.bad("${o.name}: balance is not a number")
        }
        val tr = HashSet<String>()
        for (t in w.transfers) if (!tr.add("${t.day}|${t.playerId}|${t.fromTeam}|${t.toTeam}|${t.kind}")) r.bad("transfer recorded twice: ${w.players[t.playerId].handle} on day ${t.day} (${t.kind})")
        val aw = HashSet<String>()
        for (a in w.awards) if (!aw.add("${a.season}|${a.titleId}|${a.name}|${a.playerId}|${a.teamId}|${a.compId}")) r.bad("award given twice: ${a.season} ${a.titleId} ${a.name}")
        for ((id, tf) in w.formats) {
            val ids = tf.log.map { it.id }.filter { it.isNotEmpty() }
            if (ids.size != ids.toSet().size) r.bad("$id: two reforms share an id")
            for (pc in tf.pending) if (pc.status !in setOf("announced", "effective", "rejected", "deferred")) r.bad("$id: pending change with status ${pc.status}")
        }
        return r
    }
}

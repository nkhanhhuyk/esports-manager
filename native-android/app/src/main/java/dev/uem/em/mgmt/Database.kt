package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.data.GameData
import dev.uem.em.data.Xlsx
import dev.uem.em.gen.GenCtx
import dev.uem.em.gen.PlayerGen
import dev.uem.em.model.Attrs
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import dev.uem.em.model.World
import dev.uem.em.sim.Ratings
import dev.uem.em.titles.Catalogue
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * The database editor: the world's players, teams, organisations, leagues, competitions, staff and
 * character pictures as a spreadsheet (Google Sheets, Excel), and the edited sheet read back. Rows
 * are matched by id in this save; for new games they are matched by "match key" (game + original
 * name), since a new world has new ids. Every value is checked; problems are listed, never guessed.
 */
object Database {
    class Report(var updated: Int = 0, var created: Int = 0, val errors: ArrayList<String> = ArrayList(), val notes: ArrayList<String> = ArrayList()) {
        fun text() = "$updated rows updated, $created created" + (if (errors.isEmpty()) "" else ", ${errors.size} problems") + "."
    }

    private val ATTR_COLS = Attrs.ALL.map { it.name }

    private val PLAYER_HEAD = listOf("id", "match key", "game", "handle", "first name", "last name", "nationality", "date of birth", "team id", "team", "main role", "second role", "image url", "potential", "current ability (calculated)", "salary", "contract until (season)", "retired (Y/N)", "second nationality") + ATTR_COLS
    private val TEAM_HEAD = listOf("id", "match key", "game", "name", "short", "organisation id", "organisation", "league (read only)", "region", "image url", "style", "aggression", "players (read only)")
    private val ORG_HEAD = listOf("id", "match key", "name", "short", "country", "region", "colours", "image url", "reputation", "fans", "balance", "teams (read only)")
    private val LEAGUE_HEAD = listOf("id", "match key", "game", "name", "short", "region (read only)", "tier (read only)", "image url")
    private val COMP_HEAD = listOf("id", "game", "season", "name", "short", "tier (read only)", "region (read only)", "start", "prize pool", "image url",
        "format (new)", "teams (new)", "best of: groups / playoffs / final (new)", "groups (new)", "advance per group (new)", "every season (new)", "entry fee (new)")
    private val STAFF_HEAD = listOf("id", "match key", "game", "first name", "last name", "nationality", "date of birth", "role (read only)", "quality", "team id", "team")
    private val CHAR_HEAD = listOf("id", "game", "name (read only)", "class (read only)", "image url")

    private fun date(day: Int) = "%04d-%02d-%02d".format(Dates.year(day), Dates.month(day), Dates.dom(day))
    fun parseDate(s: String): Int? {
        val t = s.trim()
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(t)?.let { m -> return runCatching { Dates.day(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() }
        Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{4})").find(t)?.let { m -> return runCatching { Dates.day(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull() }
        // A spreadsheet date serial (days since 1899-12-30).
        t.toDoubleOrNull()?.let { v -> if (v in 1000.0..80000.0) return (java.time.LocalDate.of(1899, 12, 30).plusDays(v.toLong()).toEpochDay()).toInt() }
        return null
    }

    // ------------------------------------------------------------------------------- export

    fun export(w: World, out: OutputStream, includeRetired: Boolean = false) {
        val readme = Xlsx.SheetOut("README", listOf("How to use this file"), { emit ->
            for (l in listOf(
                "Edit any cell, then import the file in the game (Settings → Database editor). In Google Sheets: File → Import → Upload, edit, then File → Download → Microsoft Excel (.xlsx).",
                "Rows are matched by id in this save. Applied to new games they are matched by 'match key' (game|original name): do not change ids or match keys.",
                "Leave a cell empty to keep the current value. Columns marked (read only) are for reference.",
                "Players: a row with an empty id is a new player (game, handle, nationality and main role are needed). Team = team id, or the team's exact name in that game; write 'free agent' to release a player.",
                "Nationality: two-letter country code (KR, CN, US, PH, ...). Dates: YYYY-MM-DD. Roles: the game's role ids or names (see the Roles sheet). Attributes: 1 to 99.",
                "Image url: a direct link to a picture (png, jpg, webp). The game downloads it when online (Settings → Download all pictures keeps it for good).",
                "Competitions: a row with an empty id creates a new competition. Needed: game, name, start date, format (league, double-league, groups-se, groups-de, se, de, swiss-se), teams (team names separated by ';', or league:<league name>, or top:<competition id>:<count>). Optional: short name, prize pool, best of as 1/3/5, groups, advance per group, every season (Y/N), entry fee, image url. It is checked like the in-game competition editor.",
                "Current ability is calculated from the attributes and role after import.",
            )) emit(listOf(l))
        })
        val roles = Xlsx.SheetOut("Roles", listOf("game", "role id", "role name"), { emit -> for (id in w.titles) for (r in Catalogue.get(id).roles) emit(listOf(id, r.id, r.name)) })
        val players = Xlsx.SheetOut("Players", PLAYER_HEAD, { emit ->
            for (p in w.players) {
                if (p.retired && !includeRetired) continue
                val t = Catalogue.get(p.titleId)
                emit(listOf(p.id, "${p.titleId}|${p.handle}", p.titleId, p.handle, p.first, p.last, p.nat, date(p.birth), if (p.teamId >= 0) p.teamId else null, w.teams.getOrNull(p.teamId)?.name ?: "",
                    p.roles.getOrNull(0) ?: "", p.roles.getOrNull(1) ?: "", w.images["player:${p.id}"] ?: "", p.pa, p.ca, p.salary.roundToInt(), p.contractEnd, if (p.retired) "Y" else "N", p.nat2) + p.attrs.map { it.roundToInt() })
            }
        })
        val teams = Xlsx.SheetOut("Teams", TEAM_HEAD, { emit ->
            for (tm in w.teams) emit(listOf(tm.id, "${tm.titleId}|${tm.name}", tm.titleId, tm.name, tm.short, tm.orgId, w.orgs.getOrNull(tm.orgId)?.name ?: "", w.leagues.getOrNull(tm.leagueId)?.name ?: "",
                tm.region, w.images["team:${tm.id}"] ?: "", tm.style, tm.aggression.roundToInt(), tm.roster.joinToString(", ") { w.players[it].handle }))
        })
        val orgs = Xlsx.SheetOut("Organisations", ORG_HEAD, { emit ->
            for (o in w.orgs) emit(listOf(o.id, o.name, o.name, o.short, o.country, o.region, o.colors.joinToString(", "), w.images["org:${o.id}"] ?: "", o.rep.roundToInt(), o.fans.roundToInt(), o.balance.roundToInt(),
                o.teams.joinToString(", ") { "${w.teams[it].titleId}: ${w.teams[it].name}" }))
        })
        val leagues = Xlsx.SheetOut("Leagues", LEAGUE_HEAD, { emit -> for (l in w.leagues) emit(listOf(l.id, "${l.titleId}|${l.name}", l.titleId, l.name, l.short, l.region, l.tier, w.images["league:${l.id}"] ?: "")) })
        val comps = Xlsx.SheetOut("Competitions", COMP_HEAD, { emit ->
            for (c in w.comps) if (c.season >= w.season) emit(listOf(c.id, c.titleId, c.season, c.name, c.short, c.tier.name, c.region, date(c.startDay), c.prizePool.roundToInt(), w.images["comp:${c.id}"] ?: ""))
        })
        val staff = Xlsx.SheetOut("Staff", STAFF_HEAD, { emit ->
            for (s in w.staff) if (!s.retired) emit(listOf(s.id, "${s.titleId}|${s.first} ${s.last}", s.titleId, s.first, s.last, s.nat, date(s.birth), s.role, s.quality.roundToInt(), if (s.teamId >= 0) s.teamId else null, w.teams.getOrNull(s.teamId)?.name ?: ""))
        })
        val chars = Xlsx.SheetOut("Characters", CHAR_HEAD, { emit -> for (id in w.titles) for (c in Catalogue.get(id).characters) emit(listOf(c.id, id, c.name, c.cls, w.images["char:${c.id}"] ?: "")) })
        Xlsx.write(listOf(readme, players, teams, orgs, leagues, comps, staff, chars, roles), out)
    }

    // ------------------------------------------------------------------------------- import

    /** Which kind of sheet a table is (by name or, for CSV files, by its header). */
    fun kindOf(name: String, header: List<String>): String? {
        val n = name.lowercase()
        val h = header.map { it.trim().lowercase() }.toSet()
        return when {
            n.startsWith("player") || "handle" in h -> "players"
            n.startsWith("team") || ("organisation id" in h && "style" in h) -> "teams"
            n.startsWith("organi") || ("colours" in h) -> "orgs"
            n.startsWith("comp") || "prize pool" in h -> "comps"
            n.startsWith("league") || ("tier (read only)" in h && "region (read only)" in h) -> "leagues"
            n.startsWith("staff") || ("quality" in h && "role (read only)" in h) -> "staff"
            n.startsWith("char") || ("class (read only)" in h) -> "chars"
            else -> null
        }
    }

    /**
     * Apply a workbook (sheet name → rows) to a world. `byKey`: match rows by "match key" (a new
     * game) instead of id (this save).
     */
    fun import(w: World, book: Map<String, List<List<String>>>, byKey: Boolean = false): Report {
        val r = Report()
        for ((name, rows) in book) {
            if (rows.isEmpty()) continue
            val header = rows[0].map { it.trim().lowercase() }
            val kind = kindOf(name, rows[0]) ?: continue
            val col = header.withIndex().associate { it.value to it.index }
            for ((ri, row) in rows.drop(1).withIndex()) {
                fun cell(k: String): String = col[k]?.let { row.getOrNull(it) }?.trim() ?: ""
                if (row.all { it.isBlank() }) continue
                val where = "$name row ${ri + 2}"
                try {
                    when (kind) {
                        "players" -> importPlayer(w, ::cell, where, byKey, r)
                        "teams" -> importTeam(w, ::cell, where, byKey, r)
                        "orgs" -> importOrg(w, ::cell, where, byKey, r)
                        "leagues" -> { val l = (if (byKey) w.leagues.firstOrNull { "${it.titleId}|${it.name}" == cell("match key") } else cell("id").toIntOrNull()?.let { w.leagues.getOrNull(it) }) ?: run { r.errors.add("$where: no such league"); null }
                            if (l != null) { var ch = false; cell("name").takeIf { it.isNotBlank() && it != l.name }?.let { l.name = it; ch = true }; cell("short").takeIf { it.isNotBlank() && it != l.short }?.let { l.short = it; ch = true }; if (setImage(w, "league:${l.id}", cell("image url"))) ch = true; if (ch) r.updated++ } }
                        "comps" -> if (cell("id").isBlank() && cell("name").isNotBlank()) createComp(w, ::cell, where, r) else { val c = if (byKey) null else cell("id").toIntOrNull()?.let { w.comps.getOrNull(it) }
                            if (c != null) { var ch = false; cell("name").takeIf { it.isNotBlank() && it != c.name }?.let { c.name = it; ch = true }; cell("short").takeIf { it.isNotBlank() && it != c.short }?.let { c.short = it; ch = true }
                                cell("prize pool").toDoubleOrNull()?.let { v -> if (c.status == 0 && v >= 0 && v != c.prizePool) { c.prizePool = v; ch = true } }; if (setImage(w, "comp:${c.id}", cell("image url"))) ch = true; if (ch) r.updated++ } }
                        "staff" -> importStaff(w, ::cell, where, byKey, r)
                        "chars" -> { val id = cell("id"); if (id.isNotBlank() && setImage(w, "char:$id", cell("image url"))) r.updated++ }
                    }
                } catch (e: Exception) { r.errors.add("$where: ${e.message}") }
            }
        }
        // Rosters and abilities after the moves.
        for (tm in w.teams) tm.lineupSig = 0
        return r
    }

    private fun setImage(w: World, key: String, url: String): Boolean {
        if (url.isBlank()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (w.images[key] == url) return false
        w.images[key] = url
        return true
    }

    private fun roleId(titleId: String, s: String): String? {
        if (s.isBlank()) return null
        val t = Catalogue.get(titleId)
        return t.roles.firstOrNull { it.id.equals(s, true) || it.name.equals(s, true) || it.short.equals(s, true) }?.id
    }

    private fun teamRef(w: World, titleId: String, id: String, name: String): Team? =
        id.toIntOrNull()?.let { w.teams.getOrNull(it) }?.takeIf { it.titleId == titleId } ?: name.takeIf { it.isNotBlank() }?.let { n -> w.teams.firstOrNull { it.titleId == titleId && it.name.equals(n, true) && it.active } ?: w.teams.firstOrNull { it.titleId == titleId && it.name.equals(n, true) } }

    private fun move(w: World, p: Player, to: Team?) {
        val from = w.teams.getOrNull(p.teamId)
        if (from?.id == to?.id) return
        from?.roster?.remove(p.id); from?.lineup?.let { l -> val i = l.indexOf(p.id); if (i >= 0) l[i] = -1 }
        p.teamId = to?.id ?: -1
        if (to != null) { to.roster.add(p.id); p.joinedDay = w.day; if (p.contractEnd < w.season) p.contractEnd = w.season + 1 }
    }

    private fun importPlayer(w: World, cell: (String) -> String, where: String, byKey: Boolean, r: Report) {
        val id = cell("id")
        var p: Player? = if (byKey) cell("match key").takeIf { it.isNotBlank() }?.let { k -> w.players.firstOrNull { "${it.titleId}|${it.handle}" == k } }
            else id.toIntOrNull()?.let { w.players.getOrNull(it) }
        if (p == null && byKey && cell("match key").isNotBlank()) { r.notes.add("$where: ${cell("match key")} is not in this world (skipped)"); return }
        if (p == null && id.isNotBlank() && !byKey) { r.errors.add("$where: no player with id $id"); return }
        val game = cell("game").ifBlank { p?.titleId ?: "" }
        if (game !in Catalogue.IDS) { r.errors.add("$where: unknown game '$game'"); return }
        val t = Catalogue.get(game)
        var changed = false
        if (p == null) {
            // A new player.
            val handle = cell("handle"); val nat = cell("nationality").uppercase(); val role = roleId(game, cell("main role"))
            if (handle.isBlank() || nat !in GameData.countries || role == null) { r.errors.add("$where: a new player needs game, handle, a known nationality code and main role"); return }
            val birth = parseDate(cell("date of birth"))
            val age = birth?.let { Dates.ageAt(it, w.day) } ?: 20
            val ctx = GenCtx(w, Rng.derive(w.seed, "dbnew", handle, w.players.size))
            p = PlayerGen.generate(ctx, game, role, nat, age.coerceIn(14, 40), cell("potential").toDoubleOrNull()?.minus(8) ?: 55.0)
            p.handle = handle
            if (birth != null) p.birth = birth
            r.created++
            changed = true
        }
        val pl = p!!
        cell("handle").takeIf { it.isNotBlank() && it != pl.handle }?.let { pl.handle = it; changed = true }
        cell("first name").takeIf { it != pl.first && cell("first name").isNotBlank() }?.let { pl.first = it; changed = true }
        cell("last name").takeIf { it != pl.last && cell("last name").isNotBlank() }?.let { pl.last = it; changed = true }
        cell("nationality").uppercase().takeIf { it.isNotBlank() && it != pl.nat }?.let { if (it in GameData.countries) { pl.nat = it; changed = true } else r.errors.add("$where: unknown nationality '$it'") }
        // Second nationality: a code sets it, "-" removes it, an empty cell (or an older sheet) keeps it.
        cell("second nationality").uppercase().let { v -> if (v.isNotBlank() && v != pl.nat2) { if (v == "-") { if (pl.nat2.isNotEmpty()) { pl.nat2 = ""; changed = true } } else if (v in GameData.countries) { pl.nat2 = if (v == pl.nat) "" else v; changed = true } else r.errors.add("$where: unknown second nationality '$v'") } }
        cell("date of birth").takeIf { it.isNotBlank() }?.let { s -> val d = parseDate(s); if (d == null) r.errors.add("$where: date of birth '$s' is not YYYY-MM-DD") else if (d != pl.birth) { pl.birth = d; changed = true } }
        val r1 = cell("main role").let { if (it.isBlank()) null else roleId(game, it) ?: run { r.errors.add("$where: unknown role '$it' for $game"); null } }
        val r2 = cell("second role").let { if (it.isBlank()) null else roleId(game, it) ?: run { r.errors.add("$where: unknown role '$it' for $game"); null } }
        if ((r1 != null && r1 != pl.roles.getOrNull(0)) || (r2 != null && r2 != pl.roles.getOrNull(1))) { pl.roles = listOfNotNull(r1 ?: pl.roles.getOrNull(0), r2 ?: pl.roles.getOrNull(1)).distinct(); changed = true }
        val tid = cell("team id"); val tname = cell("team")
        if (tname.lowercase() in setOf("free agent", "fa", "none", "-")) { if (pl.teamId >= 0) { move(w, pl, null); changed = true } }
        else if (tid.isNotBlank() || tname.isNotBlank()) {
            val to = teamRef(w, game, if (byKey) "" else tid, tname)
            if (to == null) r.errors.add("$where: no $game team '${tname.ifBlank { tid }}'") else if (to.id != pl.teamId) { move(w, pl, to); Imports.onJoin(w, pl, to); changed = true }
        }
        if (setImage(w, "player:${pl.id}", cell("image url"))) changed = true
        cell("potential").toDoubleOrNull()?.let { v -> val c = clamp(v, 1.0, 99.0); if (c != pl.pa) { pl.pa = c; changed = true } }
        cell("salary").toDoubleOrNull()?.let { v -> if (v >= 0 && v != pl.salary) { pl.salary = v; changed = true } }
        cell("contract until (season)").toIntOrNull()?.let { v -> if (v != pl.contractEnd) { pl.contractEnd = v; changed = true } }
        cell("retired (y/n)").uppercase().takeIf { it == "Y" || it == "N" }?.let { v -> val ret = v == "Y"; if (ret != pl.retired) { pl.retiredSeason = if (ret) w.season else 0; if (ret) move(w, pl, null); changed = true } }
        var attrs = false
        for ((i, a) in Attrs.ALL.withIndex()) {
            val v = cell(a.name.lowercase()).toDoubleOrNull() ?: continue
            if (v !in 1.0..99.0) { r.errors.add("$where: ${a.name} must be 1-99"); continue }
            if (v.roundToInt().toDouble() != pl.attrs[i]) { pl.attrs[i] = v.roundToInt().toDouble(); attrs = true }
        }
        if (attrs || changed) {
            pl.attrVersion++
            pl.ca = Ratings.computeCA(pl, t)
            if (pl.pa < pl.ca) pl.pa = pl.ca
            if (id.isNotBlank() || byKey) r.updated++
        }
    }

    private fun importTeam(w: World, cell: (String) -> String, where: String, byKey: Boolean, r: Report) {
        val tm = (if (byKey) w.teams.firstOrNull { "${it.titleId}|${it.name}" == cell("match key") } else cell("id").toIntOrNull()?.let { w.teams.getOrNull(it) })
            ?: run { if (!byKey) r.errors.add("$where: no team with id ${cell("id")} (new teams cannot be created from the sheet)"); return }
        var ch = false
        cell("name").takeIf { it.isNotBlank() && it != tm.name }?.let { tm.name = it; ch = true }
        cell("short").takeIf { it.isNotBlank() && it != tm.short }?.let { tm.short = it; ch = true }
        val t = Catalogue.get(tm.titleId)
        cell("style").takeIf { it.isNotBlank() && it != tm.style }?.let { if (it in t.styles) { tm.style = it; ch = true } else r.errors.add("$where: style must be one of ${t.styles.joinToString()}") }
        cell("aggression").toDoubleOrNull()?.let { v -> val c = clamp(v, 0.0, 100.0); if (c != tm.aggression) { tm.aggression = c; ch = true } }
        val orgCell = cell("organisation id").toIntOrNull()?.takeIf { !byKey }?.let { w.orgs.getOrNull(it) } ?: cell("organisation").takeIf { it.isNotBlank() }?.let { n -> w.orgs.firstOrNull { it.name.equals(n, true) } }
        if (orgCell != null && orgCell.id != tm.orgId) {
            w.orgs.getOrNull(tm.orgId)?.teams?.remove(tm.id); orgCell.teams.add(tm.id); tm.orgId = orgCell.id; ch = true
        }
        if (setImage(w, "team:${tm.id}", cell("image url"))) ch = true
        if (ch) r.updated++
    }

    private fun importOrg(w: World, cell: (String) -> String, where: String, byKey: Boolean, r: Report) {
        val o = (if (byKey) w.orgs.firstOrNull { it.name == cell("match key") } else cell("id").toIntOrNull()?.let { w.orgs.getOrNull(it) })
            ?: run { if (!byKey) r.errors.add("$where: no organisation with id ${cell("id")}"); return }
        var ch = false
        cell("name").takeIf { it.isNotBlank() && it != o.name }?.let { o.name = it; ch = true }
        cell("short").takeIf { it.isNotBlank() && it != o.short }?.let { o.short = it; ch = true }
        cell("country").uppercase().takeIf { it.isNotBlank() && it != o.country }?.let { if (it in GameData.countries) { o.country = it; ch = true } else r.errors.add("$where: unknown country '$it'") }
        cell("colours").takeIf { it.isNotBlank() }?.let { s -> val cs = s.split(',', ' ').map { it.trim() }.filter { Regex("^#[0-9a-fA-F]{6}$").matches(it) }; if (cs.isNotEmpty() && cs != o.colors) { o.colors = cs; ch = true } }
        cell("reputation").toDoubleOrNull()?.let { v -> val c = clamp(v, 1.0, 99.0); if (c != o.rep) { o.rep = c; ch = true } }
        cell("fans").toDoubleOrNull()?.let { v -> if (v >= 0 && v != o.fans) { o.fans = v; ch = true } }
        cell("balance").toDoubleOrNull()?.let { v -> if (v != o.balance && o.id != w.userOrg) { o.balance = v; ch = true } else if (v != o.balance) r.notes.add("$where: your own club's balance is not changed from the sheet") }
        if (setImage(w, "org:${o.id}", cell("image url"))) ch = true
        if (ch) r.updated++
    }

    /** A new competition from a sheet row (a published custom competition, checked like the in-game editor). */
    private fun createComp(w: World, cell: (String) -> String, where: String, r: Report) {
        val game = cell("game")
        if (game !in w.titles) { r.errors.add("$where: unknown game '$game'"); return }
        val start = parseDate(cell("start")) ?: run { r.errors.add("$where: start date needed (YYYY-MM-DD)"); return }
        val format = cell("format (new)").ifBlank { "groups-se" }.lowercase()
        if (format !in dev.uem.em.comp.CustomComps.FORMATS) { r.errors.add("$where: format must be one of ${dev.uem.em.comp.CustomComps.FORMATS.joinToString()}"); return }
        val id = (w.customComps.maxOfOrNull { it.id } ?: -1) + 1
        val c = dev.uem.em.model.CustomComp(id, cell("name"), cell("short").ifBlank { cell("name").filter { it.isLetterOrDigit() }.take(6).uppercase() }, game, format)
        c.startMonth = Dates.month(start); c.startDom = Dates.dom(start)
        cell("prize pool").toDoubleOrNull()?.let { c.prize = it.coerceAtLeast(0.0) }
        cell("entry fee (new)").toDoubleOrNull()?.let { c.entryFee = it.coerceAtLeast(0.0) }
        cell("groups (new)").toIntOrNull()?.let { c.groups = it.coerceIn(1, 8) }
        cell("advance per group (new)").toIntOrNull()?.let { c.advance = it.coerceIn(1, 8) }
        c.recurring = cell("every season (new)").uppercase() == "Y"
        cell("best of: groups / playoffs / final (new)").split('/', ',', ' ').mapNotNull { it.trim().toIntOrNull() }.let { b -> b.getOrNull(0)?.let { c.boGroup = it }; b.getOrNull(1)?.let { c.boPlayoff = it }; b.getOrNull(2)?.let { c.boFinal = it } }
        for (part in cell("teams (new)").split(';').map { it.trim() }.filter { it.isNotBlank() }) {
            when {
                part.startsWith("league:", true) -> { val n = part.substringAfter(':').trim(); val l = w.leagues.firstOrNull { it.titleId == game && (it.name.equals(n, true) || it.short.equals(n, true)) }; if (l != null) c.sources.add("league:${l.id}") else r.errors.add("$where: no $game league '$n'") }
                part.startsWith("top:", true) -> { val p = part.split(':'); if (p.size == 3 && p[1].toIntOrNull() != null && p[2].toIntOrNull() != null) c.sources.add("top:${p[1]}:${p[2]}") else r.errors.add("$where: '$part' should be top:<competition id>:<count>") }
                else -> { val tm = teamRef(w, game, "", part); if (tm != null) c.teams.add(tm.id) else r.errors.add("$where: no $game team '$part'") }
            }
        }
        w.customComps.add(c)
        val err = dev.uem.em.comp.CustomComps.publish(w, c)
        if (err != null) { r.errors.add("$where: ${c.name} kept as a draft in the competition editor — $err"); return }
        setImage(w, "comp:${c.editions.lastOrNull() ?: -1}", cell("image url"))
        r.created++
    }

    private fun importStaff(w: World, cell: (String) -> String, where: String, byKey: Boolean, r: Report) {
        val s = (if (byKey) w.staff.firstOrNull { "${it.titleId}|${it.first} ${it.last}" == cell("match key") } else cell("id").toIntOrNull()?.let { w.staff.getOrNull(it) })
            ?: run { if (!byKey) r.errors.add("$where: no staff member with id ${cell("id")}"); return }
        var ch = false
        cell("first name").takeIf { it.isNotBlank() && it != s.first }?.let { s.first = it; ch = true }
        cell("last name").takeIf { it.isNotBlank() && it != s.last }?.let { s.last = it; ch = true }
        cell("nationality").uppercase().takeIf { it.isNotBlank() && it != s.nat }?.let { if (it in GameData.countries) { s.nat = it; ch = true } else r.errors.add("$where: unknown nationality '$it'") }
        cell("date of birth").takeIf { it.isNotBlank() }?.let { v -> parseDate(v)?.let { d -> if (d != s.birth) { s.birth = d; ch = true } } ?: r.errors.add("$where: date '$v' is not YYYY-MM-DD") }
        cell("quality").toDoubleOrNull()?.let { v -> val c = clamp(v, 1.0, 99.0); if (c != s.quality) { s.quality = c; ch = true } }
        val tm = teamRef(w, s.titleId, if (byKey) "" else cell("team id"), cell("team"))
        if (tm != null && tm.id != s.teamId) { w.teams.getOrNull(s.teamId)?.staffIds?.remove(s.id); tm.staffIds.add(s.id); s.teamId = tm.id; s.orgId = tm.orgId; ch = true }
        if (ch) r.updated++
    }
}

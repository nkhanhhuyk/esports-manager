package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.model.Message
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue

/**
 * Your job (as in the web version) decides what you control; AI staff run the rest. Delegation
 * hands an area you do control to your staff.
 */
object Roles {
    val IDS = listOf("coach-gm", "head-coach", "gm", "director", "owner", "commissioner")
    val LABEL = mapOf(
        "coach-gm" to "Head Coach & General Manager", "head-coach" to "Head Coach", "gm" to "General Manager",
        "director" to "Sporting Director", "owner" to "Owner", "commissioner" to "Commissioner (sandbox)",
    )
    val DESC = mapOf(
        "coach-gm" to "Everything: lineups, drafts, live matches, training, transfers, contracts, staff and finances.",
        "head-coach" to "Lineups, drafts and live matches, tactics and training. The GM handles transfers and contracts.",
        "gm" to "Transfers, contracts, staff and budgets. The head coach picks lineups, drafts and trains the team.",
        "director" to "Transfers, contracts, staff, academies and new divisions. Coaches run the teams.",
        "owner" to "Finances, facilities, staff, academies and new divisions. No board can sack you.",
        "commissioner" to "A sandbox with full control and no board.",
    )
    val PERM_LABEL = mapOf(
        "tactics" to "Lineups, drafts, live matches & training", "transfers" to "Transfers, free agents, loans & trades", "contracts" to "Contract extensions & releases",
        "finance" to "Budgets & finances", "staff" to "Hiring & releasing staff", "expansion" to "Academies & new divisions", "facilities" to "Facility upgrades",
    )
    private val PERMS = mapOf(
        "coach-gm" to setOf("tactics", "transfers", "contracts", "staff", "finance", "facilities", "expansion"),
        "head-coach" to setOf("tactics"),
        "gm" to setOf("transfers", "contracts", "staff", "finance"),
        "director" to setOf("transfers", "contracts", "staff", "expansion"),
        "owner" to setOf("finance", "facilities", "expansion", "staff"),
        "commissioner" to setOf("tactics", "transfers", "contracts", "staff", "finance", "facilities", "expansion"),
    )

    /** Delegable areas: label, who does it, permission needed to do it yourself. */
    class Area(val id: String, val label: String, val staff: String, val perm: String, val desc: String)
    val AREAS = listOf(
        Area("lineups", "Lineups", "Head coach", "tactics", "Picks the strongest available lineup before every match."),
        Area("training", "Training plan", "Head coach", "tactics", "Sets the training focus and intensity each week from the team's weaknesses and fatigue."),
        Area("signings", "Signings", "General manager", "transfers", "Signs free agents for missing or weak positions and bids in the windows."),
        Area("sales", "Offers for your players", "General manager", "transfers", "Accepts or rejects bids for your players instead of asking you."),
        Area("renewals", "Contract renewals", "General manager", "contracts", "Renews key players whose contracts end (from October)."),
        Area("staff", "Staff hiring", "Director", "staff", "Replaces a missing head coach or analyst and adds a scout."),
        Area("scouting", "Scouting list", "Chief scout", "transfers", "Keeps the scouting list full of the best young prospects in your games."),
        Area("facilities", "Facility upgrades", "Owner", "facilities", "Upgrades facilities when the balance is healthy."),
        Area("sponsors", "Sponsorship deals", "Commercial director", "finance", "Signs sponsorship offers as they come instead of asking you."),
        Area("development", "Mentoring & youth", "Academy director", "tactics", "Pairs young players with mentors, answers competition invitations, promotes ready academy players."),
        Area("competitions", "Competition invitations", "General manager", "tactics", "Accepts ranking invitations when the roster and calendar allow it."),
    )

    fun can(w: World, perm: String): Boolean = w.userOrg < 0 || (PERMS[w.role] ?: PERMS["coach-gm"]!!).contains(perm)
    fun hasBoard(w: World) = hasBoard(w.role)
    fun hasBoard(role: String) = role != "owner" && role != "commissioner"
    fun delegated(w: World, area: String) = w.autopilot || w.delegation[area] == true || (area == "sales" && w.delegateBids) || (area == "renewals" && w.delegateRenewals)

    /** You personally handle an area: your job allows it and you have not delegated it. */
    fun handles(w: World, area: String): Boolean = can(w, AREAS.first { it.id == area }.perm) && !delegated(w, area)

    fun denyReason(w: World, perm: String): String {
        val who = when (perm) { "tactics" -> "the head coach"; "transfers", "contracts" -> "the general manager"; "finance", "facilities" -> "the owner"; else -> "the board" }
        return "As ${LABEL[w.role]} you don't control this — $who handles it."
    }

    /** Daily: staff do the work in areas you do not handle. */
    fun daily(w: World) {
        if (w.userOrg < 0) return
        val rng = Rng.derive(w.seed, "staff", w.day)
        val teams = w.orgs[w.userOrg].teams.map { w.teams[it] }.filter { it.active }
        for (team in teams) {
            if (!handles(w, "lineups") && team.manualLineup) { team.manualLineup = false; team.lineupSig = 0 }
            if (!handles(w, "signings")) {
                val t = Catalogue.get(team.titleId)
                val short = team.roster.size < t.teamSize + 1 || Market.missingRoles(w, team).isNotEmpty()
                if ((short && !Market.frozen(w)) || (Market.inWindow(w) && rng.chance(1.0 / 30))) Market.aiTeamMarket(w, team, rng, short)
            }
        }
        if (!handles(w, "sales")) for (o in w.offers.toList()) {
            if (o.status != "pending" || !w.isUserTeam(o.toTeam) || w.isUserTeam(o.fromTeam)) continue
            val p = w.players[o.playerId]
            val accept = o.fee >= Economy.marketValue(w, p) * (if (p.id in w.teams[o.toTeam].lineup) 1.4 else 1.1)
            val r = Market.answerBid(w, o.id, accept)
            w.inbox.add(Message(w.day, "General manager", "Bid ${if (accept) "accepted" else "declined"}: ${p.handle}", r.text))
        }
        if (!handles(w, "renewals") && Dates.month(w.day) == 10 && Dates.dom(w.day) == 1) {
            for (team in teams) for (pid in team.roster.toList()) {
                val p = w.players[pid]
                if (p.contractEnd != w.season || p.ca < 58 || p.loanFrom >= 0) continue
                val r = Market.offerContract(w, pid, team.id, Market.asking(w, team, p) * 1.05, 2)
                w.inbox.add(Message(w.day, "General manager", "Renewal: ${p.handle}", r.text))
            }
        }
        if (Dates.dow(w.day) == 1) {
            if (!handles(w, "training")) for (team in teams) {
                val roster = team.roster.map { w.players[it] }
                val sums = Attrs.summaries(roster)
                team.focus = when (sums) { "mech" -> "mechanics"; "macro" -> "macro"; "team" -> "teamwork"; "mental" -> "mental"; else -> "balanced" }
                val fatigue = roster.map { it.fatigue }.average()
                team.intensity = if (fatigue > 45) 45 else if (fatigue > 30) 60 else 75
            }
            if (!handles(w, "scouting")) {
                val titles = teams.map { it.titleId }.toSet()
                val targets = w.players.asSequence().filter { !it.retired && it.titleId in titles && !w.isUserTeam(it.teamId) && Dates.ageAt(it.birth, w.day) <= 20 && it.id !in w.scoutQueue }
                    .sortedByDescending { it.ca + (w.scouting[it.id] ?: 0) * -0.1 }.take(maxOf(0, 6 - w.scoutQueue.size)).toList()
                for (p in targets) w.scoutQueue.add(p.id)
            }
            if (!handles(w, "staff")) for (team in teams) for (role in listOf("head-coach", "analyst", "scout")) {
                if (Economy.staffOf(w, team, role).isNotEmpty()) continue
                val cand = w.staff.filter { it.teamId < 0 && !it.retired && it.role == role }.maxByOrNull { it.quality + if (it.titleId == team.titleId) 5 else 0 } ?: continue
                Economy.hire(w, cand, team, 2)
                w.inbox.add(Message(w.day, "Director", "Hired: ${cand.name()}", "${cand.name()} joins ${team.name} as ${Economy.ROLE_LABEL[role]}."))
            }
        }
        if (!handles(w, "facilities") && Dates.dom(w.day) == 15) {
            val org = w.orgs[w.userOrg]
            for (i in 0..2) {
                val lvl = org.facilities[i]
                if (lvl >= 10) continue
                val cost = (40000 * Math.pow(lvl + 1.0, 1.7) * (0.4 + dev.uem.em.data.GameData.economy(org.region)) / 1000).toInt() * 1000.0
                if (org.balance > cost * 4) {
                    Economy.ledger(w, org, "facility", -cost, "Facility level ${lvl + 1}")
                    org.facilities[i]++
                    w.inbox.add(Message(w.day, "Owner", "Facility upgraded", "Facility ${listOf("training centre", "analytics", "academy")[i]} upgraded to level ${lvl + 1} for ${dev.uem.em.core.formatMoney(cost)}."))
                    break
                }
            }
        }
    }

    /** Weakest area of a squad by the attribute summaries. */
    private object Attrs {
        fun summaries(roster: List<dev.uem.em.model.Player>): String {
            if (roster.isEmpty()) return "balanced"
            val avg = HashMap<String, Double>()
            for (p in roster) for ((k, v) in dev.uem.em.model.Attrs.summaries(p.attrs)) avg[k] = (avg[k] ?: 0.0) + v / roster.size
            val keys = mapOf("Mechanics" to "mech", "Macro" to "macro", "Teamplay" to "team", "Mentality" to "mental")
            return keys.minByOrNull { avg[it.key] ?: 99.0 }?.value ?: "balanced"
        }
    }
}

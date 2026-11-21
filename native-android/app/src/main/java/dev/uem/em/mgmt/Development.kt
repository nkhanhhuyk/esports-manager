package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.core.clamp
import dev.uem.em.model.AttrGroup
import dev.uem.em.model.Attrs
import dev.uem.em.model.World
import dev.uem.em.sim.Ratings
import dev.uem.em.titles.Catalogue
import kotlin.math.abs
import kotlin.math.max

/**
 * Player development and team life: monthly growth toward potential (before the peak age) or
 * decline (after it), shaped by the team's training focus and intensity, coaches and facilities;
 * weekly morale (playing time, results) and chemistry.
 */
object Development {
    val FOCUS = listOf("balanced", "mechanics", "macro", "teamwork", "mental", "characters")
    val FOCUS_LABEL = mapOf("balanced" to "Balanced", "mechanics" to "Mechanics & combat", "macro" to "Macro & game knowledge", "teamwork" to "Teamwork & communication", "mental" to "Mentality", "characters" to "Character pool")

    private fun groupWeight(focus: String, g: AttrGroup): Double = when (focus) {
        "mechanics" -> if (g == AttrGroup.Mechanical || g == AttrGroup.Combat || g == AttrGroup.MOBA || g == AttrGroup.Shooter) 1.6 else 0.7
        "macro" -> if (g == AttrGroup.Tactical || g == AttrGroup.Knowledge) 1.7 else 0.75
        "teamwork" -> if (g == AttrGroup.Teamwork) 1.9 else 0.85
        "mental" -> if (g == AttrGroup.Mental) 1.9 else 0.85
        "characters" -> 0.9
        else -> 1.0
    }

    fun monthly(w: World) {
        val rng = Rng.derive(w.seed, "dev", w.day)
        for (p in w.players) {
            if (p.retired) continue
            val t = Catalogue.get(p.titleId)
            val team = w.teams.getOrNull(p.teamId)
            val age = Dates.ageFloat(p.birth, w.day)
            val focus = team?.focus ?: "balanced"
            val intensity = team?.intensity ?: 45
            val coachQ = team?.let { Economy.coachQuality(w, it) } ?: 35.0
            val assist = team?.let { Economy.staffOf(w, it, "assistant-coach").maxOfOrNull { s -> s.quality } } ?: 30.0
            val facility = team?.let { w.orgs[it.orgId].facilities[0] } ?: 2
            val trainMult = (0.75 + intensity / 200.0) * (0.85 + coachQ / 300 + assist / 600 + facility / 50.0) * (if (team != null) 1.0 else 0.6)
            var delta = 0.0
            if (age < p.dev.peakAge) delta = max(0.0, p.pa - p.ca) * 0.05 * p.dev.growthRate * (0.6 + p.attrs[Attrs.LEARNING] / 125) * trainMult
            else if (age > p.dev.declineAge) delta = -0.22 * p.dev.declineRate * (1 + (age - p.dev.declineAge) * 0.25) * (1.15 - min1(trainMult) * 0.15)
            delta += rng.normal(0.0, 0.15)
            if (team != null) {
                p.fatigue = clamp(p.fatigue + (intensity - 60) * 0.15, 0.0, 100.0)
                if (focus == "characters") {
                    val top = p.profChar.entries.sortedByDescending { it.value }.take(6)
                    for (e in top) p.profChar[e.key] = clamp(e.value + 0.8 * trainMult, 0.0, 99.0)
                    if (t.characters.isNotEmpty() && rng.chance(0.25)) {
                        val role = t.role(p.mainRole)
                        val fresh = t.characters.filter { it.fits(role) && it.id !in p.profChar }.let { if (it.isEmpty()) null else rng.pick(it) }
                        if (fresh != null) p.profChar[fresh.id] = 30.0
                    }
                }
            }
            if (abs(delta) < 0.05) continue
            val step = delta / 0.66
            var wsum = 0.0
            var n = 0
            for (a in Attrs.ALL) if (a.group != AttrGroup.Development) { wsum += groupWeight(focus, a.group); n++ }
            val norm = n / wsum
            for (a in Attrs.ALL) if (a.group != AttrGroup.Development) p.attrs[a.index] = clamp(p.attrs[a.index] + step * groupWeight(focus, a.group) * norm * a.trainability.coerceAtLeast(0.3), 1.0, 99.0)
            p.attrVersion++
            p.ca = Ratings.computeCA(p, t)
            if (p.ca > p.pa) p.pa = p.ca
        }
    }

    private fun min1(x: Double) = if (x > 1) 1.0 else x

    /** Weekly: morale from playing time and recent results; chemistry grows with a settled roster. */
    fun weekly(w: World) {
        // Last week's results per team, in one pass over the fixtures.
        val wins = IntArray(w.teams.size)
        val losses = IntArray(w.teams.size)
        for (f in w.fixtures) {
            if (!f.done || f.day <= w.day - 7) continue
            if (f.winner == f.a) { wins[f.a]++; losses[f.b]++ } else { wins[f.b]++; losses[f.a]++ }
        }
        for (team in w.teams) {
            if (!team.active) continue
            val wn = wins[team.id]
            val ls = losses[team.id]
            val psych = Economy.staffOf(w, team, "psychologist").maxOfOrNull { it.quality } ?: 30.0
            for (pid in team.roster) {
                val p = w.players[pid]
                val starter = pid in team.lineup
                var d = (wn - ls) * 1.5 + (if (starter) 0.8 else -1.2 + (if (p.ca < 60) 0.8 else 0.0))
                d += (65 - p.morale) * 0.05 * (0.5 + psych / 100)
                p.morale = clamp(p.morale + d, 5.0, 100.0)
                p.unhappy = clamp(p.unhappy + (if (!starter && p.ca >= 65 && p.pers[0] > 60) 2.0 else -1.0), 0.0, 100.0)
            }
            val settled = team.roster.count { w.day - w.players[it].joinedDay > 120 }
            team.chemistry = clamp(team.chemistry + (settled - team.roster.size / 2.0) * 0.15 + 0.1, 20.0, 99.0)
        }
    }
}

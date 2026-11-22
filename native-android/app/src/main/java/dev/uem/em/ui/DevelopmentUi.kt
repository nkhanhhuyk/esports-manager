package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.core.Dates
import dev.uem.em.mgmt.Mentoring
import dev.uem.em.model.Player
import dev.uem.em.model.Team
import dev.uem.em.model.World

/** On a player's page: pathway stage, mentor (or mentees), progress with evidence, and the user's actions. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlayerDevelopmentCard(vm: GameVm, w: World, p: Player) {
    val mine = p.teamId >= 0 && w.isUserTeam(p.teamId)
    val m = Mentoring.of(w, p.id)
    val asMentor = w.mentorships.filter { it.active && !it.staffMentor && it.mentorId == p.id }
    var picking by remember(p.id) { mutableStateOf<String?>(null) }
    Card("Development & mentoring") {
        KV("Pathway", Mentoring.stage(w, p))
        if (m != null) {
            val g = Mentoring.goal(m.goal)
            KV("Mentor", "${Mentoring.mentorName(w, m)}${if (m.staffMentor) " (staff)" else ""}")
            KV("Goal", "${g.label} (${g.attrs.joinToString(", ")})")
            KV("Sessions", "${m.sessions} held · ${m.missed} missed (attendance ${if (m.sessions + m.missed > 0) 100 * m.sessions / (m.sessions + m.missed) else 0}%)")
            KV("Measured change", "+%.1f attribute points on the goal (cap %.0f)".format(m.gained, Mentoring.CAP_PER_ATTR * g.attrs.size))
            KV("Relationship", Mentoring.relationText(m.relation))
            KV("Next review", Dates.format(m.reviewDay))
            Muted("Confidence in the evaluation: ${if (m.sessions >= 8) "fair (many sessions)" else "low (few sessions)"}. Gains come only from sessions held.", 10)
            for (line in m.log.takeLast(4)) Muted("· $line", 10)
            if (mine) TextButton(onClick = { Mentoring.end(w, m, "ended by you"); vm.edit() }) { Text("End mentorship", color = Ui.loss) }
        } else if (mine) {
            Muted("No mentor. Pick a goal, then a mentor with capacity (teaching ability is not the same as being a star).", 11)
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 4.dp)) { for (g in Mentoring.GOALS) Pill(g.label) { picking = g.id } }
        }
        if (asMentor.isNotEmpty()) {
            Gap(4)
            Text("Mentoring", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            for (x in asMentor) Muted("${w.players[x.menteeId].handle} — ${Mentoring.goal(x.goal).label}, ${x.sessions} sessions, +%.1f".format(x.gained), 11)
            Muted("Capacity ${asMentor.size}/${Mentoring.capacity(p)} · teaching ability %.0f".format(Mentoring.teaching(p)), 10)
        }
        picking?.let { gid ->
            val g = Mentoring.goal(gid)
            Gap(4)
            Text("Mentors for ${g.label}", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            val cands = Mentoring.candidates(w, p, g)
            if (cands.isEmpty()) Muted("Nobody at the organisation can take this on now (capacity, game or role knowledge).", 11)
            for ((kind, id) in cands) {
                val label = if (kind == "s") "${w.staff[id].name()} (${w.staff[id].role}) · teaching %.0f · %d/%d".format(Mentoring.teaching(w.staff[id]), Mentoring.load(w, id, true), Mentoring.capacity(w.staff[id]))
                    else "${w.players[id].handle} · teaching %.0f · %d/%d mentees".format(Mentoring.teaching(w.players[id]), Mentoring.load(w, id, false), Mentoring.capacity(w.players[id]))
                Text(label, color = Ui.accent, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().clickable {
                    vm.toast = Mentoring.start(w, p, id, kind == "s", gid) ?: "Mentorship started: ${g.label}"
                    picking = null; vm.edit()
                }.padding(vertical = 3.dp))
            }
            TextButton(onClick = { picking = null }) { Text("Cancel", color = Ui.muted) }
        }
        // Unsigned players can be invited to a trial at the user's academy (or team).
        val userTeams = w.orgs.getOrNull(w.userOrg)?.teams?.map { w.teams[it] }?.filter { it.titleId == p.titleId && it.active }.orEmpty()
        if ((p.teamId < 0 || w.teams[p.teamId].kind.isNotEmpty()) && userTeams.isNotEmpty() && !p.retired) {
            val target = userTeams.firstOrNull { it.parentId >= 0 } ?: userTeams.first()
            TextButton(onClick = { vm.toast = Mentoring.trial(w, p, target) ?: "${p.handle} invited to a two-week trial at ${target.name}"; vm.edit() }) { Text("Invite to a trial at ${target.short}", color = Ui.accent) }
        }
    }
}

/** On a team's page: mentorships, succession by position and players who are ready but not playing. */
@Composable
fun TeamDevelopment(vm: GameVm, w: World, team: Team) {
    val org = team.orgId
    val ms = w.mentorships.filter { it.active && w.players[it.menteeId].teamId.let { t -> t >= 0 && w.teams[t].orgId == org } }
    Card("Mentorships (${ms.size})") {
        if (ms.isEmpty()) Muted("None. Open a young player's page to pair them with a mentor${if (w.isUserTeam(team.id)) ", or delegate it (Settings → Delegation → Mentoring & youth)" else ""}.", 11)
        for (m in ms) Row(Modifier.fillMaxWidth().clickable { vm.go(Screen.Player(m.menteeId)) }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerPic(w.players[m.menteeId], 20.dp); HGap(6)
            Column(Modifier.weight(1f)) {
                Text("${w.players[m.menteeId].handle} ← ${Mentoring.mentorName(w, m)}", color = Ui.text, fontSize = 12.sp)
                Muted("${Mentoring.goal(m.goal).label} · ${m.sessions} sessions (${m.missed} missed) · +%.1f · ${Mentoring.relationText(m.relation)} · review ${Dates.short(m.reviewDay)}".format(m.gained), 10)
            }
        }
    }
    Card("Succession plan") {
        for (s in Mentoring.succession(w, team)) Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Ui.card2, RoundedCornerShape(6.dp)).padding(6.dp)) {
            Text("${s.role}: ${s.starter?.handle ?: "—"}", color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Muted("Risk: ${s.risk}", 10)
            Muted("Next in line: ${s.next?.let { "${it.handle} (${Mentoring.stage(w, it)})" } ?: "—"} · ${s.readiness}", 10)
        }
    }
    val gaps = Mentoring.opportunityGaps(w, team)
    if (gaps.isNotEmpty()) Card("Opportunity gaps") { for (g in gaps) Text("⚠ $g", color = Ui.gold, fontSize = 11.sp) }
}

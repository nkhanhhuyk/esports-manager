package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.uem.em.model.World

/** First-career walkthrough: one short tip at a time on Home, with a link to the place it talks about. */
object Tips {
    class Tip(val id: String, val title: String, val text: String, val go: Screen?, val manager: Boolean = true)
    val ALL = listOf(
        Tip("continue", "Moving through the season", "Continue (top bar) plays the calendar up to your next match and stops there. The ⋮ menu next to it jumps a day, a week, a month, to the World Championships, to the end of the season or several seasons.", null, false),
        Tip("matchday", "Match day", "On match day the card on Home lets you play the series live (you draft and watch every game on the 2D map), prepare a plan first, or let your staff simulate it.", null),
        Tip("squad", "Your squad", "Squad holds the lineup, tactics, training, staff and contracts of each of your teams. Tap a position in the lineup to choose the player yourself.", Screen.Squad),
        Tip("market", "Signing players", "Free agents can be signed any time; other players only in the transfer windows. Shortlist and scout players first: their potential is uncertain until your scouts know them.", Screen.Market),
        Tip("search", "Find anything", "The magnifier in the top bar searches every player, team, organisation, event, character and screen.", Screen.Search, false),
        Tip("business", "Money", "Business is where sponsor deals are negotiated, content creators signed and fan events held at your venue. Finances shows where the money goes.", Screen.Business),
        Tip("watch", "Watching other matches", "The match centre lets you watch any match live, step by step from the draft, or open the replays of finished ones.", Screen.Watch, false),
        Tip("menu", "Everything else", "The menu (top left) lists every screen; More groups them with the game libraries (items, runes, spells, objectives) and the history pages. Settings has the theme, delegation and saves.", Screen.More, false),
    )

    fun next(vm: GameVm, w: World): Tip? = ALL.firstOrNull { it.id !in vm.tipsSeen && (!it.manager || w.userOrg >= 0) }
}

@Composable
fun TipCard(vm: GameVm, w: World) {
    val tip = Tips.next(vm, w) ?: return
    val n = Tips.ALL.indexOf(tip) + 1
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(Ui.accentSoft)
            .border(1.dp, Ui.accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Info, Ui.accent, 30.dp)
            HGap(10)
            Column(Modifier.weight(1f)) {
                Text("TIP $n OF ${Tips.ALL.size}", color = Ui.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(tip.title, color = Ui.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Gap(6)
        Text(tip.text, color = Ui.text, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { Tips.ALL.forEach { vm.dismissTip(it.id) } }) { Text("Hide tips", color = Ui.muted) }
            if (tip.go != null) TextButton(onClick = { vm.dismissTip(tip.id); vm.go(tip.go) }) { Text("Show me", color = Ui.accent) }
            TextButton(onClick = { vm.dismissTip(tip.id) }) { Text("Next tip", color = Ui.accent, fontWeight = FontWeight.SemiBold) }
        }
    }
}

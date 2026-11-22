package dev.uem.em.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.Items
import dev.uem.em.titles.Neutrals
import dev.uem.em.titles.RuneDef
import dev.uem.em.titles.Runes
import dev.uem.em.titles.SpellDef
import dev.uem.em.titles.Spells
import dev.uem.em.titles.TitleDef
import kotlin.math.roundToInt

// ------------------------------------------------------------------------------------- pictures

/** Generated badge: a gradient in colours from the id with a short label (talent level, initials). */
@Composable
private fun GenBadge(id: String, label: String, size: Dp, round: Boolean) {
    val seed = id.hashCode()
    Box(Modifier.size(size).background(Brush.linearGradient(listOf(GenArt.hue(seed, 0.55f, 0.55f), Color(0xFF141A2B))), if (round) CircleShape else RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp, maxLines = 1)
    }
}

private fun initials(name: String) = name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()

/** A rune, emblem, talent, arcana or facet: the game's own icon, or a generated badge (Dota 2 talents show their level). */
@Composable
fun RunePic(w: World?, id: String, size: Dp = 24.dp) {
    val r = Runes.get(w, id)
    val round = r?.slot == "keystone" || r?.slot?.startsWith("row") == true
    val label = r?.slot?.takeIf { it.startsWith("level") }?.removePrefix("level") ?: initials(r?.name ?: id)
    Pic(r?.img, r?.name ?: id, size, round = round, color = Color(0xFF1D2539), fallback = { GenBadge(id, label, size, round) })
}

/** A spell's icon; while it is on cooldown, darkened with the seconds left on it. */
@Composable
fun SpellPic(w: World?, id: String, size: Dp = 22.dp, cdLeft: Int = 0) {
    val s = Spells.get(w, id)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Pic(s?.img, s?.name ?: id, size, color = Color(0xFF1D2539), fallback = { GenBadge(id, initials(s?.name ?: id), size, false) })
        if (cdLeft > 0) {
            Box(Modifier.size(size).background(Color(0xB0000000), RoundedCornerShape(6.dp)))
            Text("$cdLeft", color = Color.White, fontWeight = FontWeight.Black, fontSize = (size.value * 0.42f).coerceAtMost(13f).sp)
        }
    }
}

/** A neutral objective's picture (a drake's element, Baron, Turtle, Lord, Tyrant, Roshan...). */
@Composable
fun ObjPic(key: String?, name: String, size: Dp = 22.dp) {
    Pic(key?.let { Spells.objImage(it) }, name, size, round = true, color = Color(0xFF2A2140), fallback = { GenBadge(key ?: name, initials(name), size, true) })
}

/** Picture key of a neutral kind, soul or objective (see Spells.objImage). */
fun objKey(t: TitleDef, kindId: String?, objId: String?, soul: Boolean = false): String? = when {
    soul && kindId != null -> "soul:$kindId".takeIf { Spells.objImage(it) != null } ?: kindId
    kindId != null && Spells.objImage(kindId) != null -> kindId
    objId != null && Spells.objImage("obj:${t.id}-$objId") != null -> "obj:${t.id}-$objId"
    objId != null -> Neutrals.of(null, t.id).firstOrNull { it.objId == objId && Spells.objImage(it.id) != null }?.id
    else -> kindId
}

/** A picture with its name underneath (libraries, box scores, scoreboards). */
@Composable
fun Tile(name: String, sub: String? = null, width: Dp = 70.dp, highlight: Boolean = false, onClick: (() -> Unit)? = null, pic: @Composable () -> Unit) {
    Column(Modifier.width(width).padding(3.dp).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier), horizontalAlignment = Alignment.CenterHorizontally) {
        pic()
        Text(name, color = if (highlight) Ui.gold else Ui.text, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 10.sp)
        if (sub != null) Text(sub, color = Ui.muted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** The rune shown for a hero: keystone (LoL, Wild Rift), emblem set and core talent (MLBB), arcana (HoK), facet (Dota 2). */
fun mainRunes(w: World?, t: TitleDef, ids: List<String>): List<RuneDef> {
    val rs = ids.mapNotNull { Runes.get(w, it) }
    return when (t.id) {
        "lol", "wildrift" -> listOfNotNull(rs.firstOrNull { it.slot == "keystone" } ?: rs.firstOrNull())
        "mlbb" -> listOfNotNull(rs.firstOrNull { it.slot == "set" }, rs.firstOrNull { it.slot == "core" })
        "hok" -> rs.filter { it.slot == "arcana" }
        "dota2" -> listOfNotNull(rs.firstOrNull { it.slot == "facet" } ?: rs.firstOrNull())
        else -> rs.take(1)
    }
}

/** Runes as pictures with their names under them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuneTiles(w: World?, t: TitleDef, ids: List<String>, size: Dp = 22.dp, width: Dp = 54.dp) {
    val main = mainRunes(w, t, ids)
    if (main.isEmpty()) return
    FlowRow { for (r in main) Tile(r.name.removePrefix("Custom "), width = width) { RunePic(w, r.id, size) } }
}

// ------------------------------------------------------------------------------------- libraries

private val ITEM_TYPES = listOf("physical", "magical", "defense", "support", "movement", "jungle")
private val ITEM_TYPE_LABEL = mapOf("physical" to "Physical", "magical" to "Magic", "defense" to "Defence", "movement" to "Boots & movement", "support" to "Support", "jungle" to "Jungle")
private val ITEM_STAT = mapOf("ad" to "Physical attack", "ap" to "Magic power", "hp" to "Health", "armor" to "Armour", "mr" to "Magic resist", "as" to "Attack speed %", "crit" to "Crit chance %",
    "haste" to "Ability haste / CDR", "pen" to "Penetration", "ls" to "Lifesteal %", "ms" to "Move speed", "mspct" to "Move speed %", "mana" to "Mana")

/** Which reference pages a game has data for. */
private fun hasData(w: World, kind: String, titleId: String) = when (kind) {
    "items" -> Items.of(w, titleId).isNotEmpty()
    "runes" -> Runes.of(w, titleId).isNotEmpty()
    "spells" -> Spells.all(w, titleId).isNotEmpty()
    else -> Neutrals.of(w, titleId).isNotEmpty()
}

/**
 * Game reference pages (More tab): every item by type, every rune / emblem / arcana / talent by
 * tree or slot, every spell, every neutral objective — each with its picture and name, per game.
 */
@Composable
fun LibraryScreen(vm: GameVm, w: World, kind: String) {
    val games = Catalogue.IDS.filter { Catalogue.get(it).isMoba && hasData(w, kind, it) }
    if (games.isEmpty()) { Muted("No data."); return }
    val mine = w.orgs.getOrNull(w.userOrg)?.teams?.firstOrNull()?.let { w.teams[it].titleId }
    var title by remember(kind) { mutableStateOf(if (mine in games) mine!! else games.first()) }
    val t = Catalogue.get(title)
    var open by remember(kind, title) { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val heading = when (kind) { "items" -> "Items"; "runes" -> "Runes, emblems, arcana & talents"; "spells" -> "Spells"; else -> "Neutral objectives" }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(heading, color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Muted("The real game's pictures and names; numbers are what the match engine uses, with this world's patch changes. Tap any icon for details.", 11)
            }
        }
        item { Tabs(games.map { Catalogue.get(it).short }, games.indexOf(title)) { title = games[it] } }
        when (kind) {
            "items" -> item { ItemsLibrary(w, title) { open = it } }
            "runes" -> item { RunesLibrary(w, t) { open = it } }
            "spells" -> item { SpellsLibrary(w, t) { open = it } }
            else -> item { NeutralsLibrary(w, t) { open = it } }
        }
    }
    open?.let { body ->
        Dialog(onDismissRequest = { open = null }) {
            Column(Modifier.fillMaxWidth().background(Ui.card, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState()).padding(16.dp)) {
                body()
                TextButton(onClick = { open = null }, modifier = Modifier.align(Alignment.End)) { Text("Close", color = Ui.accent) }
            }
        }
    }
}

@Composable
private fun DetailHead(name: String, sub: String, pic: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        pic(); HGap(10)
        Column { Text(name, color = Ui.text, fontWeight = FontWeight.Bold, fontSize = 16.sp); Muted(sub, 11) }
    }
    Gap(6)
}

private fun patchText(m: Double) = when { m > 1.005 -> "Buffed +${((m - 1) * 100).roundToInt()}% since release"; m < 0.995 -> "Nerfed ${((m - 1) * 100).roundToInt()}% since release"; else -> null }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemsLibrary(w: World, titleId: String, open: ((@Composable () -> Unit)) -> Unit) {
    val all = Items.of(w, titleId)
    for (type in ITEM_TYPES) {
        val list = all.filter { it.type == type }.sortedWith(compareByDescending<dev.uem.em.titles.ItemDef> { it.cost }.thenBy { it.name })
        if (list.isEmpty()) continue
        Card("${ITEM_TYPE_LABEL[type]} (${list.size})") {
            FlowRow {
                for (it in list) Tile(it.name, "${it.cost}", 72.dp, it.custom, onClick = {
                    open {
                        val m = Items.mod(w, it.id)
                        DetailHead(it.name + if (it.custom) "  NEW" else "", "${ITEM_TYPE_LABEL[it.type]} · ${it.cost} gold") { ItemPic(w, it.id, 48.dp) }
                        for ((k, v) in it.stats) KV(ITEM_STAT[k] ?: k, "%.0f".format(v * m))
                        patchText(m)?.let { s -> Gap(4); Muted(s, 11) }
                    }
                }) { ItemPic(w, it.id, 40.dp) }
            }
        }
    }
}

private val SLOT_ORDER = listOf("set", "keystone", "row1", "row2", "row3", "talent1", "talent2", "core", "arcana", "facet", "level10", "level15", "level20", "level25")
private fun slotLabel(t: TitleDef, s: String): String = when {
    s == "keystone" -> "Keystones"; s == "row1" -> "Row 1"; s == "row2" -> "Row 2"; s == "row3" -> "Row 3"; s == "set" -> "Emblem sets"
    s == "talent1" -> "Standard talents (tier 1)"; s == "talent2" -> "Standard talents (tier 2)"; s == "core" -> "Core talents"; s == "arcana" -> "Arcana"
    s == "facet" -> "Facets"; s.startsWith("level") -> "Level ${s.removePrefix("level")} talents"; else -> s
}

@Composable
private fun RuneDetail(w: World, t: TitleDef, r: RuneDef) {
    val m = Runes.mod(w, r.id)
    DetailHead(r.name + if (r.custom) "  NEW" else "", listOf(r.group.takeIf { it.isNotBlank() && t.id != "dota2" }, slotLabel(t, r.slot).removeSuffix("s")).filterNotNull().joinToString(" · ")) { RunePic(w, r.id, 48.dp) }
    if (r.desc.isNotBlank()) Text(r.desc + (if (r.approx) " (summary)" else ""), color = Ui.text, fontSize = 12.sp)
    Gap(4)
    Muted("In the match engine: " + Neutrals.statText(r.stats, m).ifBlank { "—" } + (if (r.slot == "arcana") " (per arcana; a page holds ten)" else ""), 11)
    patchText(m)?.let { Muted(it, 11) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunesLibrary(w: World, t: TitleDef, open: ((@Composable () -> Unit)) -> Unit) {
    val all = Runes.of(w, t.id)
    val kind = Runes.KIND[t.id] ?: "Runes"
    if (t.id == "dota2") {
        // Every hero has its own facets and talent tree: pick a hero.
        val heroes = all.map { it.group }.distinct().filter { t.charIndex[it] != null }.sortedBy { t.charIndex[it]?.name ?: it }
        var hero by remember { mutableIntStateOf(0) }
        val id = heroes.getOrNull(hero.coerceIn(0, maxOf(0, heroes.size - 1)))
        Card("Facets & talents") {
            Muted("Each Dota 2 hero has two or three facets (one chosen before the game) and a talent tree: one of two talents at levels 10, 15, 20 and 25. Tap a hero.", 11)
            FlowRow { for ((i, h) in heroes.withIndex()) Box(Modifier.padding(2.dp).clickable { hero = i }.background(if (i == hero) Ui.accent else Color.Transparent, CircleShape).padding(2.dp)) { CharPic(h, t.charIndex[h]?.name ?: h, 30.dp, round = true, tap = false) } }
        }
        if (id != null) {
            val mine = all.filter { it.group == id }
            Card(t.charIndex[id]?.name ?: id) {
                for (slot in SLOT_ORDER) {
                    val list = mine.filter { it.slot == slot }
                    if (list.isEmpty()) continue
                    Text(slotLabel(t, slot), color = Ui.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    FlowRow { for (r in list) Tile(r.name, width = 104.dp, highlight = r.custom, onClick = { open { RuneDetail(w, t, r) } }) { RunePic(w, r.id, 34.dp) } }
                }
            }
        }
        val custom = all.filter { it.custom }
        if (custom.isNotEmpty()) Card("Added by patches") { FlowRow { for (r in custom) Tile(r.name, width = 80.dp, highlight = true, onClick = { open { RuneDetail(w, t, r) } }) { RunePic(w, r.id, 36.dp) } } }
        return
    }
    Card(kind) {
        Muted(when (t.id) {
            "mlbb" -> "Each hero takes an emblem set (its attributes), two standard talents and one core talent."
            "hok" -> "An arcana page holds ten each of three arcana (red, blue and green in the game)."
            else -> "A keystone and three minor runes from a primary tree, two minor runes from a second tree."
        }, 11)
    }
    val groups = all.groupBy { it.group }
    for ((g, list) in groups) Card(g.ifBlank { kind }) {
        for (slot in SLOT_ORDER + list.map { it.slot }.distinct().filter { it !in SLOT_ORDER }) {
            val inSlot = list.filter { it.slot == slot }
            if (inSlot.isEmpty()) continue
            if (groups.size > 1 || slot != "arcana") Text(slotLabel(t, slot), color = Ui.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            FlowRow { for (r in inSlot) Tile(r.name.removePrefix("Custom "), width = 74.dp, highlight = r.custom, onClick = { open { RuneDetail(w, t, r) } }) { RunePic(w, r.id, 40.dp) } }
        }
    }
}

private val EFFECT_TEXT = mapOf("blink" to "Blink (repositions instantly)", "teleport" to "Teleport (travels across the map)", "ignite" to "Damage over time, cuts healing", "execute" to "Executes low-health enemies",
    "smite" to "Jungle: damages monsters, secures objectives", "heal" to "Heals", "shield" to "Shields", "weaken" to "Reduces an enemy's damage", "haste" to "Movement speed",
    "cleanse" to "Removes crowd control", "stun" to "Crowd control", "poke" to "Ranged damage", "frenzy" to "Attack speed burst", "reflect" to "Reflects damage", "disrupt" to "Disables a tower / structure")

@Composable
private fun SpellDetail(w: World, t: TitleDef, s: SpellDef) {
    val m = Spells.mod(w, s.id)
    val removed = s.id in w.removedSpells
    DetailHead(s.name + (if (s.custom) "  NEW" else "") + (if (removed) "  (removed)" else ""), "${Spells.cooldown(w, s).roundToInt()} s cooldown") { SpellPic(w, s.id, 48.dp) }
    if (s.desc.isNotBlank()) Text(s.desc + (if (s.approx) " (summary)" else ""), color = Ui.text, fontSize = 12.sp)
    Gap(4)
    Muted("In the match engine: " + (EFFECT_TEXT[s.effect] ?: s.effect), 11)
    patchText(m)?.let { Muted(it, 11) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpellsLibrary(w: World, t: TitleDef, open: ((@Composable () -> Unit)) -> Unit) {
    val all = Spells.all(w, t.id)
    val fixed = Spells.fixed(t.id)
    val n = Spells.slots(t.id)
    Card("Spells (${t.short})") {
        Muted(when (t.id) {
            "mlbb" -> "Every hero has Recall and Healing; players choose one battle spell. Junglers take Retribution."
            "hok" -> "Every hero has Recall and Recovery; players choose one common skill. Junglers take Smite."
            else -> "Players choose $n summoner spells; nearly everyone takes Flash, junglers take Smite."
        } + (if (fixed.isNotEmpty()) " Always there: ${fixed.joinToString(", ")}." else ""), 11)
        val shown = all.filter { it.id !in w.removedSpells }
        androidx.compose.foundation.layout.FlowRow { for (s in shown) Tile(s.name, "${Spells.cooldown(w, s).roundToInt()} s", 78.dp, s.custom, onClick = { open { SpellDetail(w, t, s) } }) { SpellPic(w, s.id, 44.dp) } }
        val gone = all.filter { it.id in w.removedSpells }
        if (gone.isNotEmpty()) {
            Text("Removed by patches", color = Ui.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            androidx.compose.foundation.layout.FlowRow { for (s in gone) Box(Modifier.padding(0.dp)) { Tile(s.name, width = 78.dp, onClick = { open { SpellDetail(w, t, s) } }) { Box(Modifier.size(44.dp)) { SpellPic(w, s.id, 44.dp); Box(Modifier.size(44.dp).background(Color(0x99000000), RoundedCornerShape(6.dp))) } } } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeutralsLibrary(w: World, t: TitleDef, open: ((@Composable () -> Unit)) -> Unit) {
    val cfg = t.moba ?: return
    val kinds = Neutrals.of(w, t.id)
    for (o in cfg.objectives) {
        val mine = kinds.filter { it.objId == o.id }
        if (mine.isEmpty()) continue
        Card(o.name.removeSuffix(" (Aegis)")) {
            Muted("Spawns at ${mmss(o.spawn)}, respawns every ${mmss(o.respawn)}${o.until?.let { " until ${mmss(it)}" } ?: ""}", 10)
            FlowRow {
                for (k in mine) Tile(k.name, width = 82.dp, highlight = k.custom, onClick = {
                    open {
                        val m = Neutrals.mod(w, k.id)
                        DetailHead(k.name + if (k.custom) "  NEW" else "", o.name) { ObjPic(objKey(t, k.id, k.objId), k.name, 48.dp) }
                        Text(Neutrals.statText(k.stats, m) + (if (k.duration > 0) " for ${k.duration} s" else if (k.stacking) " per stack, rest of the game" else "") + (if (k.endsOnDeath) " (lost on death)" else ""), color = Ui.text, fontSize = 12.sp)
                        if (k.killerOnly) Muted("Only for the hero who lands the last hit.", 11)
                        if (k.fromKill > 1) Muted("Dropped from Roshan kill ${k.fromKill} on.", 11)
                        if (k.soulAt > 0 && k.soul.isNotEmpty()) { Gap(4); Row(verticalAlignment = Alignment.CenterVertically) { ObjPic(objKey(t, k.id, k.objId, soul = true), k.soulName, 28.dp); HGap(6); Muted("${k.soulName} (${k.soulAt} drakes): ${Neutrals.statText(k.soul, m)}", 11) } }
                        if (k.text.isNotBlank()) { Gap(4); Muted(k.text + (if (k.approx) " (values approximate)" else ""), 11) }
                        patchText(m)?.let { Muted(it, 11) }
                    }
                }) { ObjPic(objKey(t, k.id, k.objId), k.name, 46.dp) }
                for (k in mine.filter { it.soulAt > 0 && it.soulName.isNotBlank() }) Tile(k.soulName, width = 82.dp) { ObjPic(objKey(t, k.id, k.objId, soul = true), k.soulName, 46.dp) }
            }
        }
    }
}

private fun mmss(m: Double): String { val s = (m * 60).toInt(); return "%d:%02d".format(s / 60, s % 60) }

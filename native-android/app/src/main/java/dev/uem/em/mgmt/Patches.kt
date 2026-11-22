package dev.uem.em.mgmt

import dev.uem.em.core.Dates
import dev.uem.em.core.Rng
import dev.uem.em.model.CompTier
import dev.uem.em.model.Message
import dev.uem.em.model.News
import dev.uem.em.model.PatchRec
import dev.uem.em.model.World
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.TitleDef
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Balance patches (as in the web version): minor patches every few weeks (a handful of buffs and
 * nerfs), medium patches ten days before First Stand, MSI and Worlds (meta-shifting), and a major
 * patch the day after Worlds that also changes the game itself (objectives, economy). Every patch
 * has written notes; the cumulative effect drives the draft meta and the match engines.
 */
object Patches {
    private fun count(w: World, t: String) = w.patches.count { it.titleId == t && it.version.substringAfterLast(' ').startsWith("${w.season % 100}.") }

    fun daily(w: World) {
        for (id in w.titles) {
            val t = Catalogue.get(id)
            val last = w.patches.lastOrNull { it.titleId == id }
            val rng = Rng.derive(w.seed, "patch", id, w.day)
            val major = w.comps.any { it.titleId == id && it.tier == CompTier.WORLDS && it.done && (it.season == w.season || it.season == w.season - 1) && it.stages.last().fixtureIds.maxOfOrNull { f -> w.fixtures[f].day }?.let { d -> d + 1 == w.day } == true }
            val medium = w.comps.any { it.titleId == id && it.season == w.season && (it.tier == CompTier.INTERNATIONAL || it.tier == CompTier.WORLDS) && !dev.uem.em.comp.Season.isEncQualifier(it) && it.startDay - 10 == w.day }
            val minor = !major && !medium && (last == null || w.day - last.day >= 24) && rng.chance(1.0 / 9)
            if (major) make(w, t, "major", rng) else if (medium) make(w, t, "medium", rng) else if (minor) make(w, t, "minor", rng)
        }
    }

    private fun make(w: World, t: TitleDef, kind: String, rng: Rng) {
        val n = count(w, t.id) + 1
        val version = "${w.season % 100}.$n"
        val notes = ArrayList<String>()
        val delta = HashMap<String, Double>()
        val style = HashMap<String, Double>()
        val obj = HashMap<String, Double>()
        val current = PatchState.metaOf(w, t)
        val (nChars, size) = when (kind) { "major" -> rng.int(14, 22) to 3.0; "medium" -> rng.int(9, 14) to 2.4; else -> rng.int(3, 7) to 1.4 }
        if (t.characters.isNotEmpty()) {
            // Strong picks tend to be nerfed, weak ones buffed (with surprises).
            val pool = t.characters.map { it to abs(current[it.id] ?: 0.0) + rng.next() * 4 }.sortedByDescending { it.second }.take(nChars).map { it.first }
            for (c in pool) {
                val m = current[c.id] ?: 0.0
                val dir = if (rng.chance(0.75)) (if (m > 0) -1 else 1) else (if (rng.chance(0.5)) 1 else -1)
                val d = dir * size * rng.float(0.5, 1.2)
                delta[c.id] = d
                notes.add("${c.name}: ${if (d > 0) "buffed" else "nerfed"} — ${detail(t, c.cls, d > 0, rng)} (${if (d > 0) "+" else ""}${"%.1f".format(d)} meta)")
            }
        } else {
            for (wc in rng.sample(t.weaponClasses, if (kind == "minor") 1 else 2)) notes.add("$wc: ${if (rng.chance(0.5)) "price lowered, damage falloff reduced" else "recoil increased, price raised"}")
        }
        // Weapon balance in shooters (the tier lists and gun picks read these).
        if (!t.isMoba) {
            val guns = Release.weaponsOf(w, t.id)
            if (guns.isNotEmpty()) for (g in rng.sample(guns, minOf(guns.size, if (kind == "minor") 1 else rng.int(2, 4)))) {
                val d = (if (rng.chance(0.5)) 1 else -1) * size * rng.float(0.3, 0.8)
                delta["w:${g.id}"] = d
                notes.add("${g.name}: ${if (d > 0) "buffed — " + rng.pick(listOf("damage falloff reduced", "price lowered", "recoil reduced", "fire rate up")) else "nerfed — " + rng.pick(listOf("price raised", "recoil increased", "damage reduced at range", "movement accuracy reduced"))}")
            }
        }
        // New fictional releases: characters (medium and major patches) and weapons (shooters).
        if (kind != "minor") {
            val newChars = if (kind == "major") 1 + (if (rng.chance(0.4)) 1 else 0) else if (rng.chance(0.3)) 1 else 0
            repeat(newChars) { Release.character(w, t, rng, delta)?.let { notes.add(0, it) } }
            if (!t.isMoba && rng.chance(if (kind == "major") 0.6 else 0.25)) Release.weapon(w, t, rng)?.let { notes.add(0, it) }
        }
        // Role metas: who the carry is, how junglers / supports / mids / tops play, the pace of games.
        if (t.isMoba) dev.uem.em.titles.RoleMeta.shift(w, t, kind, rng).asReversed().forEach { notes.add(0, it) }
        // Items: buffs and nerfs (scale of the item's stats), a new item now and then on a major patch.
        if (t.isMoba && dev.uem.em.titles.Items.hasItems(t.id)) {
            val items = dev.uem.em.titles.Items.of(w, t.id).filter { it.type != "movement" }
            val n = when (kind) { "major" -> rng.int(4, 8); "medium" -> rng.int(2, 4); else -> if (rng.chance(0.4)) 1 else 0 }
            for (it in rng.sample(items, minOf(n, items.size))) {
                val old = w.itemMods[it.id] ?: 1.0
                val d = rng.float(0.04, 0.12) * (if (old > 1.15) -1 else if (old < 0.85) 1 else if (rng.chance(0.5)) 1 else -1)
                w.itemMods[it.id] = (old * (1 + d)).coerceIn(0.7, 1.35)
                notes.add("Item — ${it.name}: ${if (d > 0) "buffed" else "nerfed"} (${if (d > 0) "+" else ""}${(d * 100).roundToInt()}% stats; ${itemStatText(it, if (d > 0) 1 + d else 1 + d)})")
            }
            if (kind == "major" && rng.chance(0.35)) Release.item(w, t, rng)?.let { notes.add(0, it) }
        }
        // Runes / emblems / arcana / talents: some raised or lowered, a new fictional one now and then.
        if (t.isMoba) {
            val rs = dev.uem.em.titles.Runes.of(w, t.id).filter { t.id != "dota2" || it.slot == "facet" || rng.chance(0.02) }
            val n = when (kind) { "major" -> rng.int(2, 5); "medium" -> rng.int(1, 3); else -> if (rng.chance(0.3)) 1 else 0 }
            for (r in rng.sample(rs, minOf(n, rs.size))) {
                val old = w.runeMods[r.id] ?: 1.0
                val d = rng.float(0.1, 0.3) * (if (old > 1.2) -1 else if (old < 0.8) 1 else if (rng.chance(0.5)) 1 else -1)
                val now = (old * (1 + d)).coerceIn(0.5, 1.6)
                w.runeMods[r.id] = now
                notes.add("${dev.uem.em.titles.Runes.KIND[t.id]?.removeSuffix("s") ?: "Rune"} — ${r.name}: ${if (d > 0) "buffed" else "nerfed"} (${dev.uem.em.titles.Neutrals.statText(r.stats, now)})")
            }
            if (kind == "major" && rng.chance(0.35)) Release.rune(w, t, rng)?.let { notes.add(0, it) }
        }
        // Spells: buffs and nerfs (cooldown and strength), a spell taken out of the game or brought back, a new fictional one.
        if (t.isMoba && dev.uem.em.titles.Spells.slots(t.id) > 0) {
            val S = dev.uem.em.titles.Spells
            val pool = S.of(w, t.id)
            val n = when (kind) { "major" -> rng.int(1, 3); "medium" -> if (rng.chance(0.6)) 1 else 0; else -> if (rng.chance(0.15)) 1 else 0 }
            for (sp in rng.sample(pool, minOf(n, pool.size))) {
                val old = w.spellMods[sp.id] ?: 1.0
                val d = rng.float(0.08, 0.2) * (if (old > 1.15) -1 else if (old < 0.85) 1 else if (rng.chance(0.5)) 1 else -1)
                val now = (old * (1 + d)).coerceIn(0.6, 1.5)
                w.spellMods[sp.id] = now
                notes.add("Spell — ${sp.name}: ${if (d > 0) "buffed" else "nerfed"} (cooldown ${(sp.cd / old).toInt()} s → ${(sp.cd / now).toInt()} s; effect ${if (d > 0) "+" else ""}${(d * 100).toInt()}%)")
            }
            if (kind == "major") {
                // Never Flash / Flicker or the jungle spell: the game would not be playable.
                val removable = pool.filter { it.effect != "blink" && it.effect != "smite" }
                if (removable.size > S.slots(t.id) + 2 && rng.chance(0.12)) rng.pick(removable).let { sp -> w.removedSpells.add(sp.id); notes.add("Spell — ${sp.name} removed from the game.") }
                else if (w.removedSpells.any { id -> S.all(w, t.id).any { it.id == id } } && rng.chance(0.4)) {
                    val back = w.removedSpells.first { id -> S.all(w, t.id).any { it.id == id } }
                    w.removedSpells.remove(back); notes.add("Spell — ${S.get(w, back)?.name ?: back} returns to the game.")
                }
                if (rng.chance(0.25)) Release.spell(w, t, rng)?.let { notes.add(0, it) }
            }
        }
        // Neutral objectives: a kind's buff raised or lowered (every number of it), a new fictional kind now and then.
        if (t.isMoba) {
            val kinds = dev.uem.em.titles.Neutrals.of(w, t.id)
            val n = when (kind) { "major" -> rng.int(1, 3); "medium" -> if (rng.chance(0.6)) 1 else 0; else -> 0 }
            for (k in rng.sample(kinds, minOf(n, kinds.size))) {
                val old = w.neutralMods[k.id] ?: 1.0
                val d = rng.float(0.1, 0.25) * (if (old > 1.2) -1 else if (old < 0.8) 1 else if (rng.chance(0.5)) 1 else -1)
                val now = (old * (1 + d)).coerceIn(0.6, 1.5)
                w.neutralMods[k.id] = now
                notes.add("Objective — ${k.name}: ${if (d > 0) "buffed" else "nerfed"} (${dev.uem.em.titles.Neutrals.statText(k.stats, now)})")
            }
            if (kind == "major" && rng.chance(0.3)) Release.neutral(w, t, rng)?.let { notes.add(0, it) }
        }
        if (kind != "minor") for (s in rng.sample(t.styles, if (kind == "major") 3 else 2)) {
            val d = rng.float(0.03, 0.12) * (if (rng.chance(0.5)) 1 else -1)
            style[s] = d
            notes.add("Meta: ${s} strategies ${if (d > 0) "stronger" else "weaker"} (${if (d > 0) "+" else ""}${(d * 100).roundToInt()}%)")
        }
        if (kind == "major") {
            if (t.moba != null) for (o in rng.sample(t.moba.objectives, rng.int(1, 3))) {
                val v = rng.float(0.75, 1.35)
                val r = rng.float(0.7, 1.3)
                obj[o.id] = v
                obj["${o.id}@respawn"] = r
                notes.add("${o.name}: reward ${if (v > 1) "increased" else "reduced"} ${(abs(v - 1) * 100).roundToInt()}%, respawn ${"%.1f".format(o.respawn)} → ${"%.1f".format(o.respawn * r)} min")
            }
            if (t.tactical != null) {
                val e = rng.float(-0.04, 0.04)
                obj["defender"] = e
                notes.add("Round balance: defenders ${if (e > 0) "gain" else "lose"} a small edge (${if (e > 0) "+" else ""}${"%.2f".format(e)})")
                val b = rng.float(0.9, 1.12)
                obj["fullbuy"] = b
                notes.add("Economy: full buy costs ${if (b > 1) "rise" else "fall"} ${(abs(b - 1) * 100).roundToInt()}%")
            }
        }
        if (kind == "major") notes.addAll(dev.uem.em.comp.Fearless.evolve(w, t, rng))
        // Competitive ruleset: coach comms in fights (MOBAs), introduced or withdrawn with a major patch.
        if (kind == "major" && t.isMoba) {
            if (t.id !in w.coachCommsTitles && rng.chance(0.4)) {
                w.coachCommsTitles.add(t.id)
                notes.add(0, "Competitive rules: coaches may now join team voice during fights — three times per game, 45 seconds each. Each competition decides whether to allow it.")
            } else if (t.id in w.coachCommsTitles && rng.chance(0.15)) {
                w.coachCommsTitles.remove(t.id)
                notes.add(0, "Competitive rules: coach comms during fights are withdrawn (competitions can still allow them).")
            }
        }
        w.patches.add(PatchRec(t.id, "${t.short} $version", w.day, kind, notes, delta, style, obj))
        val label = when (kind) { "major" -> "Major patch"; "medium" -> "Patch"; else -> "Minor patch" }
        w.news.add(News(w.day, "$label ${t.short} $version", notes.take(3).joinToString("; ") + if (notes.size > 3) "; …" else ""))
        if (w.userOrg >= 0 && w.orgs[w.userOrg].teams.any { w.teams[it].titleId == t.id }) w.inbox.add(Message(w.day, "${t.short} patch notes", "$label ${t.short} $version", notes.joinToString("\n")))
    }

    private val STAT_LABEL = mapOf("ad" to "physical attack", "ap" to "magic power", "hp" to "health", "armor" to "armour", "mr" to "magic resistance", "as" to "attack speed",
        "crit" to "critical chance", "haste" to "ability haste", "pen" to "penetration", "ls" to "life steal", "ms" to "move speed", "mspct" to "move speed", "mana" to "mana")

    /** The item's main stats after a change, e.g. "physical attack 75 → 81". */
    private fun itemStatText(it: dev.uem.em.titles.ItemDef, f: Double): String =
        it.stats.entries.sortedByDescending { e -> e.value }.take(2).joinToString(", ") { (k, v) -> "${STAT_LABEL[k] ?: k} ${v.roundToInt()} → ${(v * f).roundToInt()}" }

    private fun detail(t: TitleDef, cls: String, buff: Boolean, rng: Rng): String {
        val c = cls.lowercase()
        val opts = when {
            c.contains("mage") || c.contains("nuker") -> if (buff) listOf("ability damage up", "mana costs reduced", "cooldowns shortened") else listOf("ability damage down", "cooldowns lengthened", "scaling reduced")
            c.contains("marksman") || c.contains("carry") -> if (buff) listOf("attack speed up", "early damage up", "range increased") else listOf("attack damage down", "late-game scaling reduced", "mobility reduced")
            c.contains("tank") || c.contains("guardian") -> if (buff) listOf("base armour up", "crowd control longer", "health growth up") else listOf("crowd control shorter", "resistances down", "engage cooldown longer")
            c.contains("assassin") -> if (buff) listOf("burst damage up", "dash cooldown reduced") else listOf("burst damage down", "escape cooldown longer")
            c.contains("support") || c.contains("enchanter") || c.contains("healer") -> if (buff) listOf("heals and shields stronger", "utility cooldowns shortened") else listOf("shields weaker", "heal ratios reduced")
            else -> if (buff) listOf("base stats up", "core ability stronger") else listOf("base stats down", "core ability weaker")
        }
        return rng.pick(opts)
    }
}

/** Cumulative patch state of a title (what the draft and engines read). */
object PatchState {
    private val baseCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, Double>>()

    private fun base(w: World, t: TitleDef): Map<String, Double> = baseCache.getOrPut("${w.seed}|${t.id}") {
        val rng = Rng.derive(w.seed, "meta", t.id)
        t.characters.associate { it.id to (rng.normal(0.0, 2.6)).coerceIn(-10.0, 10.0) }
    }

    fun metaOf(w: World, t: TitleDef): Map<String, Double> {
        val m = HashMap(base(w, t))
        for (p in w.patches) if (p.titleId == t.id) for ((k, v) in p.charDelta) m[k] = ((m[k] ?: 0.0) * 0.97 + v).coerceIn(-10.0, 10.0)
        // How far apart characters are differs by patch: some patches are balanced (no must-bans),
        // others leave one or several characters clearly overpowered.
        val n = w.patches.count { it.titleId == t.id }
        val spread = 0.55 + 0.9 * Rng.derive(w.seed, "spread", t.id, n).next()
        for (k in m.keys.toList()) m[k] = (m[k]!! * spread).coerceIn(-10.0, 10.0)
        return m
    }

    fun current(w: World, t: TitleDef) = w.patches.lastOrNull { it.titleId == t.id }
}

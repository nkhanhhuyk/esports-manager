package dev.uem.em.mgmt

import dev.uem.em.core.Rng
import dev.uem.em.model.CustomChar
import dev.uem.em.model.CustomWeapon
import dev.uem.em.model.World
import dev.uem.em.sim.Patch
import dev.uem.em.titles.Catalogue
import dev.uem.em.titles.CharDef
import dev.uem.em.titles.RoleDef
import dev.uem.em.titles.TitleDef
import dev.uem.em.ui.GameInfo
import dev.uem.em.ui.Maps
import kotlin.math.ln
import kotlin.math.sqrt

/** New fictional characters and weapons released by medium and major patches. */
object Release {
    private val ITEM_A = mapOf(
        "physical" to listOf("Ember", "Storm", "Iron", "Raven", "Gilded", "Thorn", "Sunforged", "Shatter", "Duskwind", "Brass"),
        "magical" to listOf("Astral", "Arcane", "Void", "Starfire", "Moonlit", "Hexed", "Prism", "Rune", "Twilight", "Aether"),
        "defense" to listOf("Warden's", "Bulwark", "Granite", "Oathbound", "Frost", "Titan", "Sentinel", "Bastion", "Stoneheart", "Ironbark"),
        "support" to listOf("Shepherd's", "Beacon", "Harmony", "Guardian's", "Pilgrim's", "Lantern"))
    private val ITEM_B = mapOf(
        "physical" to listOf("Cleaver", "Edge", "Reaver", "Saber", "Halberd", "Bow", "Fang", "Talon"),
        "magical" to listOf("Codex", "Orb", "Scepter", "Grimoire", "Focus", "Crown", "Wand", "Lens"),
        "defense" to listOf("Aegis", "Plate", "Mantle", "Carapace", "Bulwark", "Cuirass", "Ward", "Shroud"),
        "support" to listOf("Censer", "Charm", "Banner", "Locket", "Chime", "Seal"))

    /**
     * A new item for a game (a major patch): a fresh name, a price and stats in the range of the
     * game's items of that kind, a generated icon. Returns the patch note.
     */
    fun item(w: World, t: TitleDef, rng: Rng): String? {
        val pool = dev.uem.em.titles.Items.of(w, t.id).filter { it.type in setOf("physical", "magical", "defense", "support") }
        if (pool.isEmpty()) return null
        val type = rng.pick(listOf("physical", "magical", "defense", "defense", "physical", "magical", "support"))
        val sample = pool.filter { it.type == type }.ifEmpty { pool }
        val a = rng.pick(sample); val b = rng.pick(sample)
        val keys = (a.stats.keys + b.stats.keys).distinct()
        val stats = keys.associateWith { k -> (((a.stats[k] ?: 0.0) + (b.stats[k] ?: 0.0)) / 2 * rng.float(0.9, 1.15)) }.filterValues { it > 0 }.mapValues { (k, v) -> if (k in setOf("as", "crit", "ls", "mspct")) Math.round(v).toDouble() else (Math.round(v / 5) * 5).toDouble().coerceAtLeast(1.0) }
        var name: String
        var guard = 0
        do { name = "${rng.pick(ITEM_A[type] ?: ITEM_A["physical"]!!)} ${rng.pick(ITEM_B[type] ?: ITEM_B["physical"]!!)}"; guard++ } while (guard < 20 && dev.uem.em.titles.Items.of(w, t.id).any { it.name == name })
        val cost = ((a.cost + b.cost) / 2 / 10) * 10
        val id = "${t.id}-new-${w.customItems.size + 1}"
        w.customItems.add(dev.uem.em.model.CustomItem(id, t.id, name, cost, type, stats, w.season))
        val txt = stats.entries.joinToString(", ") { (k, v) -> "+${v.toInt()}${if (k in setOf("as", "crit", "ls", "mspct")) "%" else ""} $k" }
        return "New item — $name ($type, ${cost} gold): $txt."
    }

    private val SPELL_NEW = listOf(
        Triple("Rift Step", "blink", "Dash a short distance and gain a brief shield."), Triple("Overcharge", "frenzy", "Gain attack speed and damage for a few seconds."),
        Triple("Hex Lock", "stun", "Root nearby enemies briefly."), Triple("Bulwark", "shield", "Gain a large shield that decays over 4 seconds."),
        Triple("Soul Brand", "ignite", "Burn an enemy hero for true damage and reduce their healing."), Triple("Windwalk", "haste", "Gain a burst of movement speed and pass through units."),
        Triple("Mend", "heal", "Restore health to you and the nearest ally over 3 seconds."), Triple("Drain", "weaken", "Reduce an enemy's damage and steal some of it as health."),
        Triple("Warp Gate", "teleport", "After a channel, travel to an allied structure."), Triple("Reprisal", "reflect", "Reflect part of the damage you take for 3 seconds."))

    /** A new fictional spell for a game that has spells (cooldown and effect drawn from the game's own spells). */
    fun spell(w: World, t: TitleDef, rng: Rng): String? {
        val pool = dev.uem.em.titles.Spells.all(w, t.id)
        if (pool.isEmpty()) return null
        val (name, effect, desc) = SPELL_NEW.filter { n -> pool.none { it.name == n.first } }.ifEmpty { return null }.let { rng.pick(it) }
        val like = pool.filter { it.effect == effect }.ifEmpty { pool }
        val cd = (like.map { it.cd }.average() * rng.float(0.85, 1.15) / 5).toInt() * 5.0
        val id = "${t.id}-spell-${w.customSpells.size + 1}"
        w.customSpells.add(dev.uem.em.model.CustomSpell(id, t.id, name, cd, desc, effect, w.season))
        return "New spell — $name ($desc, ${cd.toInt()} s cooldown)."
    }

    private val RUNE_A = listOf("Astral", "Molten", "Hollow", "Tempest", "Verdant", "Gilded", "Riven", "Lunar", "Thorned", "Ember", "Frost", "Echoing")
    private val RUNE_B = mapOf("keystone" to listOf("Covenant", "Onslaught", "Requiem", "Bastion", "Surge", "Pact"), "core" to listOf("Fury", "Resolve", "Overdrive", "Sanctum", "Hunt", "Momentum"),
        "arcana" to listOf("Sigil", "Omen", "Tide", "Crest", "Ward", "Fang"), "facet" to listOf("Aspect", "Path", "Form", "Way"))
    private val RUNE_EFFECT = listOf(
        "After 3 hits on a champion, gain bonus damage for a few seconds." to mapOf("dmg" to 0.05),
        "Taking heavy damage grants a decaying shield and damage reduction." to mapOf("dr" to 0.05, "hp" to 0.02),
        "Takedowns refund part of every cooldown." to mapOf("haste" to 8.0, "dmg" to 0.01),
        "Basic attacks grow faster in a long fight." to mapOf("as" to 0.1),
        "Damage heals for a share; more below half health." to mapOf("heal" to 0.06, "regen" to 0.004),
        "Bonus movement speed toward enemy champions." to mapOf("ms" to 0.05, "dmg" to 0.015),
        "Penetration that grows with level." to mapOf("pen" to 0.06))

    /** A new fictional keystone (LoL, Wild Rift), core talent (MLBB), arcana (HoK) or facet (Dota 2). */
    fun rune(w: World, t: TitleDef, rng: Rng): String? {
        val all = dev.uem.em.titles.Runes.of(w, t.id)
        if (all.isEmpty()) return null
        val slot = when (t.id) { "lol", "wildrift" -> "keystone"; "mlbb" -> "core"; "hok" -> "arcana"; else -> "facet" }
        val (desc, stats) = rng.pick(RUNE_EFFECT)
        var name: String; var guard = 0
        do { name = "${rng.pick(RUNE_A)} ${rng.pick(RUNE_B[slot]!!)}"; guard++ } while (guard < 20 && all.any { it.name == name })
        val group = when (t.id) {
            "lol", "wildrift" -> rng.pick(all.filter { it.slot == "keystone" }.map { it.group }.distinct())
            "mlbb" -> "Basic Common"
            "hok" -> "Arcana"
            else -> rng.pick(all.filter { it.slot == "facet" }.map { it.group }.distinct().ifEmpty { return null })
        }
        // Arcana are ten to a page: one arcana gives a tenth.
        val st = if (slot == "arcana") stats.mapValues { it.value / 10 } else stats
        val id = "${t.id}-rune-${w.customRunes.size + 1}"
        w.customRunes.add(dev.uem.em.model.CustomRune(id, t.id, name, group, slot, desc, st, w.season))
        val kind = when (slot) { "keystone" -> "keystone ($group)"; "core" -> "core talent"; "arcana" -> "arcana"; else -> "facet for ${dev.uem.em.titles.Catalogue.get(t.id).charIndex[group]?.name ?: group}" }
        return "New $kind — $name: $desc (${dev.uem.em.titles.Neutrals.statText(stats)})."
    }

    private val ELEMENT = listOf("Glacial" to mapOf("tenacity" to 0.05, "dr" to 0.02), "Storm" to mapOf("as" to 0.04, "ms" to 0.02), "Verdant" to mapOf("regen" to 0.015, "heal" to 0.04),
        "Shadow" to mapOf("dmg" to 0.025, "ms" to 0.015), "Solar" to mapOf("ap" to 0.035, "haste" to 3.0), "Iron" to mapOf("armor" to 0.04, "ad" to 0.015), "Void" to mapOf("struct" to 0.05, "dmg" to 0.015))
    private val SOUL = mapOf("Glacial" to mapOf("tenacity" to 0.15, "dr" to 0.05), "Storm" to mapOf("as" to 0.12, "ms" to 0.06), "Verdant" to mapOf("regen" to 0.04, "heal" to 0.1),
        "Shadow" to mapOf("dmg" to 0.06), "Solar" to mapOf("ap" to 0.08, "haste" to 10.0), "Iron" to mapOf("armor" to 0.1, "dr" to 0.03), "Void" to mapOf("struct" to 0.15, "dmg" to 0.03))
    private val BEAST = listOf("Crystal", "Thunder", "Abyssal", "Ember", "Frost", "Gilded")

    /**
     * A new fictional kind of neutral objective: a new elemental drake (League of Legends, Wild Rift)
     * with its own stacking buff and soul, or a new variant of another objective (it then spawns in
     * turn with the real one) with stats drawn from the game's own objectives.
     */
    fun neutral(w: World, t: TitleDef, rng: Rng): String? {
        val kinds = dev.uem.em.titles.Neutrals.of(w, t.id)
        if (kinds.isEmpty()) return null
        val id = "${t.id}-neutral-${w.customNeutrals.size + 1}"
        val drakes = kinds.filter { it.objId == "drake" }
        if (drakes.isNotEmpty()) {
            val free = ELEMENT.filter { (e, _) -> kinds.none { it.name.startsWith(e) } }.ifEmpty { return null }
            val (e, stats) = rng.pick(free)
            val soulAt = drakes.first().soulAt
            val c = dev.uem.em.model.CustomNeutral(id, t.id, "drake", "$e Drake", stats, 0, true, soulAt, if (soulAt > 0) "$e Dragon Soul" else "", SOUL[e] ?: emptyMap(),
                "New elemental drake: ${dev.uem.em.titles.Neutrals.statText(stats)} per stack.", w.season)
            w.customNeutrals.add(c)
            return "New objective — $e Drake joins the elemental drakes: ${dev.uem.em.titles.Neutrals.statText(stats)} per stack" + (if (soulAt > 0) "; $e Dragon Soul: ${dev.uem.em.titles.Neutrals.statText(SOUL[e] ?: emptyMap())}." else ".")
        }
        val base = rng.pick(kinds.filter { !it.killerOnly && it.fromKill <= 1 && kinds.count { k -> k.objId == it.objId } == 1 }.ifEmpty { return null })
        val name = "${rng.pick(BEAST.filter { b -> kinds.none { it.name.startsWith(b) } }.ifEmpty { BEAST })} ${base.name}"
        val extra = rng.pick(ELEMENT).second
        val stats = base.stats.mapValues { (k, v) -> if (k in setOf("gold", "xp", "push")) v else v * rng.float(0.85, 1.1) } + extra.filterKeys { it !in base.stats }
        val c = dev.uem.em.model.CustomNeutral(id, t.id, base.objId, name, stats, base.duration, base.stacking, 0, "", emptyMap(), "New variant of ${base.name}: ${dev.uem.em.titles.Neutrals.statText(stats)}.", w.season)
        w.customNeutrals.add(c)
        return "New objective — $name, a new form of the ${base.name} that spawns in turn with it: ${dev.uem.em.titles.Neutrals.statText(stats)}."
    }

    private val SYL_A = listOf("Ka", "Vor", "Zy", "Mor", "Sel", "Tha", "Ny", "Qui", "Bra", "Ez", "Lum", "Rho", "Iss", "Dra", "Fen", "Ori", "Xan", "Vel", "Ash", "Kir", "Ul", "Nae", "Sor", "Tal")
    private val SYL_B = listOf("el", "an", "ra", "dek", "ene", "rion", "x", "lla", "nn", "ora", "ia", "dax", "ith", "vyn", "eth", "ka", "is", "or", "une", "ael", "yra", "os")
    private val EPITHET = listOf("the Ember Warden", "the Tidecaller", "the Hollow Star", "the Iron Oath", "the Last Lantern", "the Storm Herald", "the Ashen Blade", "the Quiet Comet", "the Verdant Fang", "the Glass Sovereign", "the Dusk Courier", "the Thousand Mirrors")
    private val SKILL_WORDS = mapOf(
        "mage" to listOf("Arc Lance" to "hurls a lance of energy that damages enemies in a line", "Prism Field" to "creates a zone that slows and burns enemies", "Rift Step" to "blinks a short distance and leaves an echo that explodes", "Cataclysm" to "after a delay, devastates a large area"),
        "marksman" to listOf("Quickdraw" to "empowers the next attacks with bonus attack speed", "Pinning Shot" to "fires a bolt that roots the first enemy hit", "Roll Out" to "dashes and reloads", "Last Light" to "channels a long-range barrage"),
        "assassin" to listOf("Shadow Cut" to "dashes through an enemy, dealing damage", "Veil" to "becomes briefly invisible and faster", "Execution Mark" to "marks a target; damage to marked enemies is increased", "Deathblow" to "strikes a weakened enemy, resetting on kills"),
        "tank" to listOf("Shield Bash" to "charges forward and stuns the first enemy hit", "Bulwark" to "gains a large shield that grows with nearby enemies", "Taunting Roar" to "forces nearby enemies to attack him", "Earthsplitter" to "knocks up all enemies in a wide arc"),
        "support" to listOf("Mending Light" to "heals an ally and grants a short shield", "Binding Chain" to "tethers an enemy, slowing them", "Sanctuary" to "creates a zone where allies take less damage", "Rally" to "speeds up and heals the whole team"),
        "fighter" to listOf("Cleave" to "swings in an arc, healing for part of the damage", "Charge" to "rushes forward, knocking aside enemies", "Battle Trance" to "gains attack speed and tenacity", "Final Stand" to "cannot die for a few seconds"),
        "duelist" to listOf("Dash" to "dashes in the chosen direction", "Flare" to "throws a flash that blinds enemies who look at it", "Blaze Wall" to "creates a wall of fire that blocks vision", "Overdrive" to "equips a weapon that kills in one body hit"),
        "initiator" to listOf("Recon Pulse" to "reveals enemies in an area", "Concuss" to "dazes enemies in a cone", "Tracker" to "sends a drone that marks enemies", "Seismic Wave" to "stuns enemies along a line through walls"),
        "controller" to listOf("Smoke Orb" to "places a smoke that blocks vision", "Slow Field" to "slows everyone in an area", "Gravity Pull" to "pulls enemies toward the centre", "Eclipse" to "covers a large area in smokes"),
        "sentinel" to listOf("Tripwire" to "a wire that reveals and slows enemies who cross it", "Turret" to "places a turret that fires at enemies", "Barrier" to "raises a wall", "Lockdown" to "detains all enemies in a large area after a delay"),
    )
    private val GUN_NAMES = mapOf("Rifle" to listOf("Harrow", "Lancer", "Verdict", "Halcyon"), "SMG" to listOf("Wasp", "Ripple", "Hornet"), "Sniper" to listOf("Longbow", "Meridian", "Spire"), "Shotgun" to listOf("Breaker", "Hammer"), "Pistol" to listOf("Ember", "Quill", "Talon"), "Heavy" to listOf("Bastion", "Torrent"))

    private fun name(rng: Rng): String = rng.pick(SYL_A) + rng.pick(SYL_B) + (if (rng.chance(0.4)) rng.pick(SYL_B) else "")

    private fun skillSet(cls: String, rng: Rng, mobaSlots: Boolean): List<String> {
        val key = SKILL_WORDS.keys.firstOrNull { cls.lowercase().contains(it) } ?: if (cls.lowercase().contains("warrior") || cls.lowercase().contains("bruiser")) "fighter" else if (cls.lowercase().contains("enchanter") || cls.lowercase().contains("healer")) "support" else SKILL_WORDS.keys.elementAt(rng.int(0, SKILL_WORDS.size - 1))
        val slots = if (mobaSlots) listOf("Skill 1", "Skill 2", "Skill 3", "Ultimate") else listOf("C", "Q", "E", "X")
        return SKILL_WORDS[key]!!.mapIndexed { i, (n, d) -> "${slots[i]}|$n|${d.replaceFirstChar { it.uppercase() }}." }
    }

    /** Release a new character; returns the patch note. */
    fun character(w: World, t: TitleDef, rng: Rng, delta: MutableMap<String, Double>): String? {
        if (t.characters.isEmpty()) return null
        var nm = name(rng)
        while (t.characters.any { it.name.equals(nm, true) }) nm = name(rng)
        val cls = rng.pick(t.charClasses)
        val roles = t.lineupRoles.filter { cls in it.classes }.ifEmpty { listOf(rng.pick(t.lineupRoles)) }
        val lanes = if (t.isMoba) rng.sample(roles, minOf(roles.size, rng.int(1, 2))).map { it.id } else emptyList()
        val id = "${t.id}-new-${w.customChars.size + 1}"
        val c = CustomChar(t.id, id, nm, cls, listOf(cls), lanes, w.season, if (t.isMoba) rng.pick(EPITHET) else "", intArrayOf(rng.int(3, 9), rng.int(3, 9), rng.int(3, 9), rng.int(3, 9)), skillSet(cls, rng, t.isMoba))
        w.customChars.add(c)
        Catalogue.applyCustom(w)
        delta[id] = rng.float(1.5, 3.5) // new releases launch strong
        val label = if (t.isMoba) t.charLabel.lowercase() else "agent"
        return "New $label: $nm${if (c.epithet.isNotEmpty()) ", ${c.epithet}" else ""} ($cls${if (lanes.isNotEmpty()) ", " + lanes.joinToString("/") { l -> t.role(l).name } else ""}) — ${c.skills.joinToString("; ") { it.split('|').let { p -> "${p[0]}: ${p[1]}" } }}"
    }

    /** Release a new weapon in a shooter; returns the patch note. */
    fun weapon(w: World, t: TitleDef, rng: Rng): String? {
        if (t.isMoba) return null
        val cat = rng.pick(GUN_NAMES.keys.toList())
        var nm = rng.pick(GUN_NAMES[cat]!!)
        if (w.customWeapons.any { it.titleId == t.id && it.name == nm }) nm += " Mk ${w.customWeapons.size + 2}"
        val (cost, dmg, rate, mag) = when (cat) {
            "Rifle" -> listOf(rng.int(26, 32) * 100, rng.int(32, 40), rng.int(560, 700), rng.int(20, 35))
            "SMG" -> listOf(rng.int(10, 17) * 100, rng.int(24, 30), rng.int(700, 880), rng.int(25, 50))
            "Sniper" -> listOf(rng.int(17, 48) * 100, rng.int(85, 150), rng.int(40, 80), rng.int(5, 10))
            "Shotgun" -> listOf(rng.int(9, 20) * 100, rng.int(18, 30), rng.int(60, 180), rng.int(5, 8))
            "Heavy" -> listOf(rng.int(16, 52) * 100, rng.int(28, 36), rng.int(700, 800), rng.int(60, 120))
            else -> listOf(rng.int(2, 8) * 100, rng.int(28, 50), rng.int(250, 500), rng.int(7, 20))
        }
        val cw = CustomWeapon(t.id, "${t.id}|new${w.customWeapons.size + 1}", nm, cat, cost, dmg, rate, mag, w.season)
        w.customWeapons.add(cw)
        return "New weapon: $nm ($cat, ${if (t.id == "cs2") "$" else ""}$cost) — $dmg damage, $rate rounds/min, magazine $mag"
    }

    fun weaponsOf(w: World?, titleId: String): List<GameInfo.Weapon> = GameInfo.weapons(titleId) + (w?.customWeapons?.filter { it.titleId == titleId }?.map { c ->
        GameInfo.Weapon(c.id, c.titleId, c.name, c.category, c.cost, listOf("Cost" to "${c.cost}", "Damage" to "${c.damage}", "Fire rate" to "${c.fireRate} rounds/min", "Magazine" to "${c.magazine}", "Released" to "season ${c.season} (fictional)"),
            c.damage * c.fireRate / 60.0 * (if (c.category == "Shotgun") 6 else 1), c.damage * 4.0, when (c.category) { "Sniper" -> 100.0; "Rifle" -> 70.0; "SMG" -> 35.0; "Shotgun" -> 15.0; else -> 30.0 }, "fictional (released by a patch)")
    } ?: emptyList())
}

/**
 * Tier lists (S, A, B, C, D) per game: from the current patch's meta (what the engines use), this
 * season's results (pick / ban presence and win rate) and, for weapons, the weapons' stats.
 */
object TierList {
    val TIERS = listOf("S", "A", "B", "C", "D")
    class Entry(val id: String, val name: String, val score: Double, val note: String = "")

    /** Split a ranked list into tiers: top 10% S, next 22% A, 32% B, 22% C, the rest D. */
    fun tiers(entries: List<Entry>): List<Pair<String, List<Entry>>> {
        val sorted = entries.sortedByDescending { it.score }
        val n = sorted.size
        val cuts = listOf(0.10, 0.32, 0.64, 0.86, 1.0).map { maxOf(1, (it * n + 0.5).toInt()) }
        var from = 0
        return TIERS.mapIndexed { i, tier -> val to = if (i == TIERS.size - 1) n else minOf(n, cuts[i]); val part = sorted.subList(minOf(from, n), maxOf(minOf(from, n), to)); from = to; tier to part }
    }

    private fun usage(w: World, key: String): IntArray? = w.charStats["${w.season}|$key"]?.takeIf { it[0] + it[2] >= 3 } ?: w.charStats["${w.season - 1}|$key"]?.takeIf { it[0] + it[2] >= 3 }

    private fun usageNote(u: IntArray?) = if (u == null) "" else "${u[0]} games, ${if (u[0] > 0) "${100 * u[1] / u[0]}% wins" else "—"}${if (u[2] > 0) ", ${u[2]} bans" else ""}"

    fun charScore(w: World, t: TitleDef, patch: Patch, c: CharDef, mapId: String? = null): Pair<Double, String> {
        val meta = patch.charMeta[c.id] ?: 0.0
        val u = usage(w, if (mapId != null) "$mapId|${c.id}" else c.id)
        var score = meta
        if (u != null) {
            val wr = if (u[0] > 0) u[1].toDouble() / u[0] else 0.5
            val conf = minOf(1.0, (u[0] + u[2]) / 20.0)
            score += ((wr - 0.5) * 8 + ln(1.0 + u[0] + u[2]) * 0.6) * conf
        }
        if (mapId != null) score += mapAffinity(w, t, c.id, mapId)
        return score to usageNote(u)
    }

    /** The game's own view of how well an agent suits a map (changes with each patch of the game). */
    fun mapAffinity(w: World, t: TitleDef, charId: String, mapId: String): Double {
        val patches = w.patches.count { it.titleId == t.id }
        val h = (charId + "|" + mapId + "|" + patches / 3).hashCode()
        val sites = Maps.shooter("${t.id}:$mapId")?.sites?.size ?: 2
        val cls = t.charIndex[charId]?.cls?.lowercase() ?: ""
        val siteBonus = if (sites >= 3 && (cls.contains("initiator") || cls.contains("controller"))) 0.6 else if (sites == 2 && cls.contains("sentinel")) 0.3 else 0.0
        return ((h % 1000) / 1000.0) * 1.6 + siteBonus
    }

    /** A character's classes (a champion can be both, e.g. Fighter and Tank). */
    fun classesOf(c: dev.uem.em.titles.CharDef): List<String> = c.classes.ifEmpty { listOf(c.cls) }.filter { it.isNotBlank() }

    /** Classes among a pool, the most common first. */
    fun classList(t: TitleDef, role: RoleDef? = null): List<String> =
        t.characters.filter { role == null || it.fits(role) }.flatMap { classesOf(it) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    fun characters(w: World, t: TitleDef, role: RoleDef?, mapId: String? = null, cls: String? = null): List<Pair<String, List<Entry>>> {
        val patch = Patch.of(w, t)
        val pool = t.characters.filter { (role == null || it.fits(role)) && (cls == null || cls in classesOf(it)) }
        return tiers(pool.map { c -> val (s, n) = charScore(w, t, patch, c, mapId); Entry(c.id, c.name, s, n) })
    }

    /** Weapon strength from its stats within its category, plus patch changes. */
    fun weaponScore(w: World, t: TitleDef, wp: GameInfo.Weapon): Double {
        val patch = Patch.of(w, t)
        val costEff = if (wp.cost > 0) wp.dps / sqrt(wp.cost.toDouble()) else wp.dps / 10
        return ln(1 + wp.dps) * 2 + wp.headshot / 60 + wp.range / 40 + costEff * 0.6 + (patch.charMeta["w:${wp.id}"] ?: 0.0)
    }

    /** Valorant roles have no weapon list in the rules: the usual buys per role. */
    fun roleCategories(t: TitleDef, role: RoleDef): List<String> = role.weapons ?: when (role.id) {
        "duelist" -> listOf("Rifle", "SMG", "Shotgun")
        "sentinel" -> listOf("Rifle", "Sniper")
        "controller" -> listOf("Rifle", "Pistol")
        "initiator" -> listOf("Rifle", "SMG")
        else -> listOf("Rifle", "Sniper", "SMG")
    }

    fun weapons(w: World, t: TitleDef, role: RoleDef? = null, mapId: String? = null, category: String? = null): List<Pair<String, List<Entry>>> {
        val cats = role?.let { roleCategories(t, it) }
        val map = mapId?.let { Maps.shooter("${t.id}:$it") }
        val openness = map?.let { m -> m.links.map { (m.nodes[it.from].pos - m.nodes[it.to].pos).getDistance() }.average() } ?: 0.12
        val list = Release.weaponsOf(w, t.id).filter { (cats == null || it.category in cats) && (category == null || it.category == category) }
        return tiers(list.map { wp ->
            var s = weaponScore(w, t, wp)
            if (cats != null) s += (cats.size - cats.indexOf(wp.category)) * 0.8
            if (map != null) s += (openness - 0.12) * 20 * when (wp.category) { "Sniper" -> 1.0; "Rifle" -> 0.5; "SMG" -> -0.6; "Shotgun" -> -1.0; else -> 0.0 }
            Entry(wp.id, wp.name, s, "${wp.category} · ${if (t.id == "cs2") "$" else ""}${wp.cost}")
        })
    }

    /** CS2 loadouts per role: a primary, a pistol and the role's utility. */
    fun loadouts(w: World, t: TitleDef, role: RoleDef): List<Pair<String, List<Entry>>> {
        val all = Release.weaponsOf(w, t.id)
        val pistols = all.filter { it.category == "Pistol" }.sortedByDescending { weaponScore(w, t, it) }.take(4)
        val primaries = all.filter { it.category in roleCategories(t, role) && it.category != "Pistol" }
        val util = when (role.id) { "entry" -> "2 flashes, smoke, molotov"; "awper" -> "flash, smoke"; "support" -> "full utility: 2 flashes, smoke, molotov, HE"; "lurker" -> "flash, smoke, decoy"; else -> "flash, smoke, HE" }
        val out = ArrayList<Entry>()
        for (p in primaries) for (pi in pistols.take(2)) {
            val cost = p.cost + pi.cost + 1000
            out.add(Entry("${p.id}+${pi.id}", "${p.name} + ${pi.name}", weaponScore(w, t, p) + weaponScore(w, t, pi) * 0.3 - (if (cost > 6000) 1.0 else 0.0), "$$cost with $util"))
        }
        return tiers(out.sortedByDescending { it.score }.distinctBy { it.name.substringBefore(" +") }.take(24))
    }
}

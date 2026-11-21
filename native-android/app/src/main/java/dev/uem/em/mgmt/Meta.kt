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

    fun characters(w: World, t: TitleDef, role: RoleDef?, mapId: String? = null): List<Pair<String, List<Entry>>> {
        val patch = Patch.of(w, t)
        val pool = t.characters.filter { role == null || it.fits(role) }
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

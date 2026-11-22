package dev.uem.em.titles

import dev.uem.em.model.World

/**
 * What each neutral objective gives when it is slain: the real buffs of the five MOBAs as stats
 * the match engine applies to the slaying team (game wikis: League of Legends Wiki buff data,
 * Mobile Legends Wiki, Honor of Kings Wiki, Dota 2 Wiki, read November 2026). Wild Rift's wiki could
 * not be read, so its values follow League of Legends' and are marked approximate.
 *
 * An objective of the map (Catalogue objectives, e.g. "drake") can have several kinds (the six
 * elemental drakes); one kind is chosen at each spawn. Patches scale a kind's stats
 * ([World.neutralMods]) and can add new fictional kinds ([World.customNeutrals]).
 *
 * Stat keys (fractions unless noted): ad, ap (% attack damage / ability power), armor, mr
 * (% armour / magic resistance), regen (share of missing health restored every 5 s), ms (% movement
 * speed), slowres (% slow resistance), haste (ability haste, points), as (% attack speed), tenacity,
 * heal (% heal and shield power), dr (damage reduction), dmg (% damage), execute (health share
 * below which a hit kills), struct (% damage to structures), lowhp (damage and damage reduction while
 * below half health), push (seconds of empowered minions / summoned pusher), shield (one-time
 * shield, share of maximum health), gold (per hero), xp (minutes of experience per hero), aegis /
 * cheese / refresher / shard (Dota 2 drops: an extra life, a full heal at low health, a second cast
 * of the ultimate, +dmg for the carrier).
 */
class NeutralDef(
    val id: String,
    val titleId: String,
    /** The map objective it is a kind of (Catalogue objective id). */
    val objId: String,
    val name: String,
    val stats: Map<String, Double>,
    /** Seconds the buff lasts (0: the rest of the game). */
    val duration: Int = 0,
    /** Lost when a hero dies (Baron, Elder: "lost upon death"). */
    val endsOnDeath: Boolean = false,
    /** Stacks with every kill of this kind (elemental drakes). */
    val stacking: Boolean = false,
    /** Kills of this kind for a soul (0: none) and the soul's stats. */
    val soulAt: Int = 0,
    val soulName: String = "",
    val soul: Map<String, Double> = emptyMap(),
    /** The real game's wording, short. */
    val text: String = "",
    /** Dota 2 Roshan drops: from this Roshan kill on (1 = every kill). */
    val fromKill: Int = 1,
    /** Only for the last-hitting hero (Turtle's Blessing, Aegis, Tormentor shard). */
    val killerOnly: Boolean = false,
    val custom: Boolean = false,
    val approx: Boolean = false,
)

object Neutrals {
    private fun d(id: String, t: String, obj: String, name: String, stats: Map<String, Double>, duration: Int = 0, endsOnDeath: Boolean = false, stacking: Boolean = false,
                  soulAt: Int = 0, soulName: String = "", soul: Map<String, Double> = emptyMap(), text: String = "", fromKill: Int = 1, killerOnly: Boolean = false, approx: Boolean = false) =
        NeutralDef(id, t, obj, name, stats, duration, endsOnDeath, stacking, soulAt, soulName, soul, text, fromKill, killerOnly, false, approx)

    private fun lolDrakes(t: String, approx: Boolean, soulAt: Int, withHexChem: Boolean): List<NeutralDef> = listOfNotNull(
        d("$t-infernal", t, "drake", "Infernal Drake", mapOf("ad" to 0.03, "ap" to 0.03), stacking = true, soulAt = soulAt, soulName = "Infernal Dragon Soul", soul = mapOf("dmg" to 0.06),
            text = "Infernal Might: +3% attack damage and ability power per stack. Soul: hits explode for adaptive damage around the target.", approx = approx),
        d("$t-mountain", t, "drake", "Mountain Drake", mapOf("armor" to 0.05, "mr" to 0.05), stacking = true, soulAt = soulAt, soulName = "Mountain Dragon Soul", soul = mapOf("shield" to 0.1, "dr" to 0.04),
            text = "Mountainous Vigor: +5% armour and magic resistance per stack. Soul: a shield after 5 s without taking damage.", approx = approx),
        d("$t-ocean", t, "drake", "Ocean Drake", mapOf("regen" to 0.02), stacking = true, soulAt = soulAt, soulName = "Ocean Dragon Soul", soul = mapOf("regen" to 0.04, "heal" to 0.05),
            text = "Oceanic Will: restore 2% of missing health every 5 s per stack. Soul: damaging enemies heals over 4 s.", approx = approx),
        d("$t-cloud", t, "drake", "Cloud Drake", mapOf("ms" to 0.025, "slowres" to 0.05), stacking = true, soulAt = soulAt, soulName = "Cloud Dragon Soul", soul = mapOf("ms" to 0.2),
            text = "Cloudbringer's Grace: +5% slow resistance and out-of-combat movement speed per stack. Soul: +20% movement speed (60% after an ultimate).", approx = approx),
        if (withHexChem) d("$t-hextech", t, "drake", "Hextech Drake", mapOf("haste" to 5.0, "as" to 0.05), stacking = true, soulAt = soulAt, soulName = "Hextech Dragon Soul", soul = mapOf("dmg" to 0.04),
            text = "Hextech Prowess: +5 ability haste and +5% attack speed per stack. Soul: hits chain lightning (true damage and a slow).", approx = approx) else null,
        if (withHexChem) d("$t-chemtech", t, "drake", "Chemtech Drake", mapOf("tenacity" to 0.06, "heal" to 0.06), stacking = true, soulAt = soulAt, soulName = "Chemtech Dragon Soul", soul = mapOf("lowhp" to 0.11),
            text = "Chemtech Blight: +6% tenacity and heal and shield power per stack. Soul: below half health, +11% damage and damage reduction.", approx = approx) else null,
    )

    val BASE: Map<String, List<NeutralDef>> = mapOf(
        "lol" to lolDrakes("lol", false, 4, true) + listOf(
            d("lol-elder", "lol", "elder", "Elder Dragon", mapOf("execute" to 0.2, "dmg" to 0.06), duration = 150, endsOnDeath = true,
                text = "Aspect of the Dragon: hits burn for true damage; hitting a champion below 20% health executes them. Lost on death."),
            d("lol-baron", "lol", "baron", "Baron Nashor", mapOf("push" to 180.0, "gold" to 300.0), duration = 180, endsOnDeath = true,
                text = "Hand of Baron: empowered recall and greatly empowered nearby minions for 180 s. Lost on death."),
            d("lol-grubs", "lol", "grubs", "Voidgrubs", mapOf("struct" to 0.04), stacking = true,
                text = "Touch of the Void: bonus true damage to structures per grub; at 4+ grubs, Hunger of the Void summons voidmites against structures."),
            d("lol-herald", "lol", "herald", "Rift Herald", mapOf("push" to 90.0, "struct" to 0.15), duration = 90,
                text = "Eye of the Herald: summon the Herald to charge a turret (heavy structure damage and plates)."),
        ),
        "wildrift" to lolDrakes("wildrift", true, 0, false) + listOf(
            d("wildrift-elder", "wildrift", "elder", "Elder Dragon", mapOf("execute" to 0.2, "dmg" to 0.06), duration = 150, endsOnDeath = true,
                text = "Aspect of the Elder Dragon: burn on hits and an execute below 20% health. Lost on death.", approx = true),
            d("wildrift-baron", "wildrift", "baron", "Baron Nashor", mapOf("push" to 150.0, "ad" to 0.06, "ap" to 0.06, "gold" to 300.0), duration = 150, endsOnDeath = true,
                text = "Hand of Baron: bonus attack damage and ability power, empowered recall and minions.", approx = true),
            d("wildrift-herald", "wildrift", "herald", "Rift Herald", mapOf("push" to 90.0, "struct" to 0.15), duration = 90,
                text = "The Herald is summoned to charge a turret.", approx = true),
        ),
        "mlbb" to listOf(
            d("mlbb-turtle", "mlbb", "turtle", "Turtle", mapOf("gold" to 70.0, "xp" to 0.45, "shield" to 0.1), duration = 120,
                text = "60/70/80 gold and EXP to the team, a one-time 200 (+20 per level) shield for 120 s; the last hitter gets Turtle's Blessing (a self-repairing shield that adds physical attack and magic power)."),
            d("mlbb-turtle-blessing", "mlbb", "turtle", "Turtle's Blessing", mapOf("shield" to 0.2, "ad" to 0.05, "ap" to 0.05), duration = 120, killerOnly = true,
                text = "Killer only: a shield of 400 (+40 per level) that repairs after 5 s without damage; +20 (+2 per level) physical attack and +25 (+4 per level) magic power while it lasts."),
            d("mlbb-lord", "mlbb", "lord", "Lord", mapOf("push" to 150.0, "gold" to 100.0), duration = 150,
                text = "Greatly enhances the next minion wave and pushes the weakest enemy lane until slain; from 12:00 the Evolved Lord has damage reduction and lets nearby allies' attacks deal extra magic damage."),
        ),
        "hok" to listOf(
            d("hok-tyrant", "hok", "tyrant", "Tyrant", mapOf("dmg" to 0.03, "gold" to 80.0, "xp" to 0.3), duration = 90,
                text = "Tyrant's Arrival: attacks chain lightning for 20 (+10 per level) magic damage (3 s cooldown); EXP and gold to the team."),
            d("hok-overlord", "hok", "overlord", "Overlord", mapOf("push" to 90.0, "gold" to 80.0, "xp" to 0.3), duration = 90,
                text = "The next 2 minion waves on all lanes become Overlord Vanguards (50% damage reduction against towers); EXP and gold to the team."),
            d("hok-shadowtyrant", "hok", "shadowtyrant", "Shadow Tyrant", mapOf("dmg" to 0.06, "ms" to 0.1, "gold" to 100.0), duration = 90,
                text = "Shadow Tyrant's Arrival: chain lightning for 60 (+30 per level) magic damage and +10% movement speed (+5% out of combat)."),
            d("hok-shadow", "hok", "shadow", "Shadow Overlord", mapOf("push" to 120.0, "ad" to 0.04, "ap" to 0.04, "gold" to 120.0), duration = 120,
                text = "Shadow Vanguards replace the next waves and the team's attack is raised."),
            d("hok-tempest", "hok", "tempest", "Tempest Dragon", mapOf("push" to 150.0, "dmg" to 0.08, "struct" to 0.2, "execute" to 0.1), duration = 150,
                text = "Storm Awakening for the team; Tempest Vanguards (CC-immune) silence towers for 5 s."),
        ),
        "dota2" to listOf(
            d("dota2-aegis", "dota2", "roshan", "Aegis of the Immortal", mapOf("aegis" to 1.0, "gold" to 150.0), duration = 300, killerOnly = true,
                text = "Every Roshan kill: Reincarnation, one extra life within 5 minutes."),
            d("dota2-cheese", "dota2", "roshan", "Cheese", mapOf("cheese" to 1.0), killerOnly = true, fromKill = 2,
                text = "From Roshan's second death (Dire pit; Roshan's Banner in the Radiant pit): Fondue, an instant heal of health and mana."),
            d("dota2-refresher", "dota2", "roshan", "Refresher Shard", mapOf("refresher" to 1.0, "shard" to 0.03), killerOnly = true, fromKill = 3,
                text = "From Roshan's third death (Radiant pit; Aghanim's Blessing in the Dire pit): resets all cooldowns once; +20 attack damage."),
            d("dota2-tormentor", "dota2", "tormentor", "Tormentor", mapOf("shard" to 0.05, "gold" to 50.0), killerOnly = true,
                text = "Drops an Aghanim's Shard for the team (a new ability upgrade)."),
            d("dota2-wisdom", "dota2", "wisdom", "Wisdom Rune", mapOf("xp" to 0.9),
                text = "Grants 280 experience plus 280 every 7 minutes, to up to two heroes."),
        ),
    )

    /** Every kind for a title: the real ones and those a world's patches added. */
    fun of(w: World?, titleId: String): List<NeutralDef> {
        val base = BASE[titleId].orEmpty()
        val extra = w?.customNeutrals?.filter { it.titleId == titleId }?.map { c ->
            NeutralDef(c.id, c.titleId, c.objId, c.name, c.stats, c.duration, false, c.stacking, c.soulAt, c.soulName, c.soul, c.text, 1, false, true)
        }.orEmpty()
        return base + extra
    }

    fun forObj(w: World?, titleId: String, objId: String) = of(w, titleId).filter { it.objId == objId }

    fun get(w: World?, id: String): NeutralDef? = BASE.values.asSequence().flatten().firstOrNull { it.id == id }
        ?: w?.customNeutrals?.firstOrNull { it.id == id }?.let { c -> of(w, c.titleId).firstOrNull { it.id == id } }

    /** Patch multiplier of a kind's numbers (1 = unchanged). */
    fun mod(w: World?, id: String): Double = w?.neutralMods?.get(id) ?: 1.0

    private val LABEL = mapOf("ad" to "% attack damage", "ap" to "% ability power", "armor" to "% armour", "mr" to "% magic resistance", "regen" to "% missing health / 5 s",
        "ms" to "% movement speed", "slowres" to "% slow resistance", "haste" to " ability haste", "as" to "% attack speed", "tenacity" to "% tenacity",
        "heal" to "% heal & shield power", "dr" to "% damage reduction", "dmg" to "% damage", "execute" to "% health execute", "struct" to "% structure damage",
        "lowhp" to "% damage & reduction below half health", "push" to " s empowered minions", "shield" to "% max-health shield", "gold" to " gold each", "xp" to " min of experience",
        "aegis" to " extra life", "cheese" to " emergency heal", "refresher" to " cooldown reset", "shard" to "% damage (carrier)", "hp" to "% max health", "pen" to "% penetration")
    private val FLAT = setOf("haste", "push", "gold", "xp", "aegis", "cheese", "refresher")

    /** Items a patch cannot scale (one extra life, one emergency heal, one cooldown reset). */
    val FIXED = setOf("aegis", "cheese", "refresher")

    private fun num(k: String, x: Double): String =
        if (k in FLAT) (if (x % 1.0 == 0.0) x.toInt().toString() else "%.1f".format(x)) else "%.1f".format(x * 100).removeSuffix(".0")

    fun statText(stats: Map<String, Double>, mod: Double = 1.0): String = stats.entries.filter { it.value != 0.0 }.joinToString(", ") { (k, v) ->
        val x = if (k in FIXED) v else v * mod
        if (k in FLAT) "+${num(k, x)}${LABEL[k] ?: " $k"}" else "+${num(k, x)}${LABEL[k] ?: "% $k"}"
    }

    /** Whether a patch can change these stats at all. */
    fun scalable(stats: Map<String, Double>) = stats.any { (k, v) -> v != 0.0 && k !in FIXED }

    /**
     * What a patch changed, value before → after ("armour bonus 3% → 2.7%, gold each 300 → 255"):
     * a nerf always shows smaller numbers, a buff larger ones.
     */
    fun changeText(stats: Map<String, Double>, old: Double, now: Double): String = stats.entries.filter { it.value != 0.0 && it.key !in FIXED }.joinToString(", ") { (k, v) ->
        val label = (LABEL[k] ?: " $k").removePrefix("% ").removePrefix(" ")
        val unit = if (k in FLAT) (if (k == "push") " s" else "") else "%"
        "$label ${num(k, v * old)}$unit → ${num(k, v * now)}$unit"
    }
}

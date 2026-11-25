package dev.uem.em.titles

import dev.uem.em.core.Rng
import dev.uem.em.model.World

/**
 * Role metas: how each position plays on the current patch — who the team's carry is (bot / gold /
 * farm lane, mid, top / EXP / clash, or the jungle), how junglers, supports, mid and top laners
 * play, and the pace of games. A title always has one option of each group. Patches shift them
 * ([World.roleMetas]); the match engine reads them: kill priority (who the team lets take the last
 * hit), farm share, ganks and roams, peel and front lines.
 */
object RoleMeta {
    /** Positions: the five lineup slots of every MOBA, by what they do. */
    const val TOP = "top"; const val JUNGLE = "jungle"; const val MID = "mid"; const val BOT = "bot"; const val SUPPORT = "support"

    class Option(val id: String, val group: String, val desc: String)

    val OPTIONS = listOf(
        Option("bot-carry", "carry", "Resources go to the bot-side carry: it takes the farm and most of the kills, the team plays for it."),
        Option("mid-carry", "carry", "The mid laner is the main carry: farm and kills go mid, the bot side plays a lower-economy role."),
        Option("top-carry", "carry", "Top-side carries are strong: the top laner gets jungle attention, farm and kills."),
        Option("jungle-carry", "carry", "Carry junglers: the jungler takes camps, waves and kills and scales into the main threat."),
        Option("jungle-gank", "jungle", "Junglers path for early ganks: more lane kills and pressure, less farm for themselves."),
        Option("jungle-farm", "jungle", "Junglers farm their camps and scale; fewer early ganks."),
        Option("jungle-control", "jungle", "Junglers play around vision and the neutral objectives."),
        Option("support-roam", "support", "Supports leave lane to roam with the jungler and mid; more pressure elsewhere, a lonelier carry."),
        Option("support-babysit", "support", "Supports stay with their carry and peel for them; carries die less and farm more safely."),
        Option("support-engage", "support", "Supports are frontline engagers who start the fights."),
        Option("mid-roam", "mid", "Mid laners shove and roam to the side lanes for kills."),
        Option("mid-control", "mid", "Mid laners hold the lane and control the river."),
        Option("mid-support", "mid", "Mid laners pick utility and control heroes that set up their teammates."),
        Option("top-tank", "top", "Top laners play tanks that soak damage and protect the team; fewer kills, fewer deaths."),
        Option("top-bruiser", "top", "Top laners play bruisers who duel in lane and join fights."),
        Option("top-split", "top", "Top laners split-push side lanes while the team groups elsewhere."),
        Option("early-skirmish", "pace", "Teams fight early and often: more early kills, shorter games."),
        Option("scaling", "pace", "Teams farm and scale for late fights: fewer early kills, longer games."),
        Option("objective-control", "pace", "Teams trade around neutral objectives rather than chase kills."),
        // What the bot-side carry plays (Gold lane, Farm lane, Dragon lane, safe lane).
        Option("bot-marksman", "bot", "Classic marksmen hold the bot side: ranged physical damage that grows with items, protected by the support."),
        Option("bot-mage", "bot", "Mage APC: mages are played in the bot-side carry slot for burst, poke and wave clear; magic items, fewer auto-attacks, strong mid game."),
        Option("bot-hypercarry", "bot", "Hyper-carries: marksmen who are weak early and decide the late game; the team protects their farm and plays for later fights."),
        Option("bot-lethality", "bot", "Lethality / early-burst marksmen: bot lanes look for kills from the first levels and snowball before the enemy carry scales."),
        Option("bot-bruiser", "bot", "Bruisers in the bot lane: melee fighters and duelists take the carry slot; bloody lanes, more kills and more deaths."),
        Option("bot-utility", "bot", "Utility carries: marksmen chosen for crowd control, range and vision rather than damage; the kills spread over the team."),
    )
    private val byId = OPTIONS.associateBy { it.id }
    val GROUPS = listOf("carry", "jungle", "support", "mid", "top", "pace", "bot")
    val GROUP_LABEL = mapOf("carry" to "Carry", "jungle" to "Jungle", "support" to "Support", "mid" to "Mid", "top" to "Top side", "pace" to "Pace", "bot" to "Bot lane")

    /** The meta each game starts in (roughly its real competitive meta). */
    private val DEFAULTS = mapOf(
        "lol" to listOf("bot-carry", "jungle-control", "support-engage", "mid-control", "top-tank", "objective-control", "bot-marksman"),
        "wildrift" to listOf("bot-carry", "jungle-gank", "support-engage", "mid-control", "top-bruiser", "early-skirmish", "bot-marksman"),
        "mlbb" to listOf("bot-carry", "jungle-farm", "support-roam", "mid-control", "top-tank", "early-skirmish", "bot-marksman"),
        "hok" to listOf("bot-carry", "jungle-farm", "support-roam", "mid-control", "top-tank", "objective-control", "bot-marksman"),
        "dota2" to listOf("bot-carry", "jungle-gank", "support-babysit", "mid-control", "top-tank", "scaling", "bot-hypercarry"),
    )

    /** Which position a role plays (top / EXP / Baron / Clash / pos 3 are "top"; Dota's pos 4 is the roaming "jungle" slot). */
    fun pos(roleId: String): String = when (roleId) {
        "top", "baron", "exp", "clash", "pos3" -> TOP
        "jungle", "pos4" -> JUNGLE
        "mid", "pos2" -> MID
        "bot", "dragon", "gold", "farm", "pos1", "adc", "marksman" -> BOT
        else -> SUPPORT
    }

    /** The position's name in this game. */
    fun posName(titleId: String, pos: String): String = when (titleId) {
        "mlbb" -> mapOf(TOP to "EXP lane", JUNGLE to "Jungle", MID to "Mid lane", BOT to "Gold lane", SUPPORT to "Roam")
        "hok" -> mapOf(TOP to "Clash lane", JUNGLE to "Jungle", MID to "Mid lane", BOT to "Farm lane", SUPPORT to "Roaming")
        "wildrift" -> mapOf(TOP to "Baron lane", JUNGLE to "Jungle", MID to "Mid lane", BOT to "Dragon lane", SUPPORT to "Support")
        "dota2" -> mapOf(TOP to "Offlane (pos 3)", JUNGLE to "Soft support (pos 4)", MID to "Mid (pos 2)", BOT to "Safe-lane carry (pos 1)", SUPPORT to "Hard support (pos 5)")
        else -> mapOf(TOP to "Top lane", JUNGLE to "Jungle", MID to "Mid lane", BOT to "Bot lane (ADC)", SUPPORT to "Support")
    }[pos] ?: pos

    /** The bot-side carry lane's short name in this game. */
    fun botLane(titleId: String) = when (titleId) { "mlbb" -> "Gold lane"; "hok" -> "Farm lane"; "wildrift" -> "Dragon lane"; "dota2" -> "Safe lane (pos 1)"; else -> "Bot lane" }

    /** A meta's name in this game ("Gold lane carry meta", "Soft support (pos 4) gank meta"). */
    fun label(titleId: String, id: String): String {
        val (who, what) = id.split('-', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (id) {
            "bot-marksman" -> "${botLane(titleId)} marksman meta"
            "bot-mage" -> if (titleId == "dota2") "${botLane(titleId)} magic-damage carry meta" else "${botLane(titleId)} mage APC meta"
            "bot-hypercarry" -> "${botLane(titleId)} hyper-carry meta"
            "bot-lethality" -> "${botLane(titleId)} lethality / early-burst meta"
            "bot-bruiser" -> "${botLane(titleId)} bruiser meta"
            "bot-utility" -> "${botLane(titleId)} utility carry meta"
            "early-skirmish" -> "Early skirmish meta"
            "scaling" -> "Late-game scaling meta"
            "objective-control" -> "Objective control meta"
            else -> {
                val p = when (who) { "bot" -> BOT; "mid" -> MID; "top" -> TOP; "jungle" -> JUNGLE; else -> SUPPORT }
                val w = when (what) { "babysit" -> "babysit (lane protection)"; "split" -> "split-push"; else -> what }
                "${posName(titleId, p)} $w meta"
            }
        }
    }

    fun desc(id: String) = byId[id]?.desc ?: ""

    /** The metas in force for a game (one per group). */
    fun current(w: World?, titleId: String): List<String> {
        val base = DEFAULTS[titleId] ?: return emptyList()
        val set = w?.roleMetas?.get(titleId)?.split(',')?.filter { it.isNotBlank() }
        if (set.isNullOrEmpty()) return base
        return GROUPS.mapIndexed { i, g -> set.firstOrNull { byId[it]?.group == g } ?: base[i] }
    }

    fun has(w: World?, titleId: String, id: String) = id in current(w, titleId)

    /** What the bot-side carry plays under the current meta (marksman, mage, hyper-carry...). */
    fun bot(w: World?, titleId: String): String = current(w, titleId).getOrNull(6) ?: "bot-marksman"

    /** The top-lane metas in force: the top group's option, plus "top-carry" when the top side is the carry. */
    fun top(w: World?, titleId: String): String {
        val m = current(w, titleId)
        return listOfNotNull(m.getOrNull(4)?.takeIf { it.startsWith("top-") }, if (m.firstOrNull() == "top-carry") "top-carry" else null).joinToString(",")
    }

    private val TANK = Regex("tank|guardian|vanguard|defender", RegexOption.IGNORE_CASE)

    /** What the top laner drafts under the top metas: carries when the top side carries, tanks, bruisers or split-pushers by the top meta. */
    fun topBias(top: String, c: CharDef): Double {
        if (top.isEmpty()) return 0.0
        val tank = isA(c, TANK); val fighter = isA(c, FIGHTER); val carry = !tank && (fighter || isA(c, MARKSMAN) || isA(c, MAGE))
        var v = 0.0
        if ("top-carry" in top) v += if (carry) 0.22 else if (tank) -0.15 else 0.0
        else if ("top-tank" in top) v += if (tank) 0.12 else 0.0
        if ("top-bruiser" in top) v += if (fighter && !tank) 0.14 else 0.0
        if ("top-split" in top) v += if (fighter) 0.12 else 0.0
        return v
    }

    private val MAGE = Regex("mage|nuker|caster|spell", RegexOption.IGNORE_CASE)
    private val MARKSMAN = Regex("marksman|ranged|archer|shooter|hunter", RegexOption.IGNORE_CASE)
    private val FIGHTER = Regex("fighter|bruiser|warrior|duelist|assassin|juggernaut|slayer|diver", RegexOption.IGNORE_CASE)
    private fun isA(c: CharDef, rx: Regex) = rx.containsMatchIn(c.cls) || c.classes.any { rx.containsMatchIn(it) }

    /**
     * May [c] be drafted in the bot-side carry slot under [botMeta]? Mid-lane mages are in a mage APC meta,
     * top / mid fighters in a bruiser meta (on top of the characters the game lists for the lane).
     */
    fun botExtra(t: TitleDef, botMeta: String, c: CharDef): Boolean = when (botMeta) {
        "bot-mage" -> isA(c, MAGE) && t.lineupRoles.any { pos(it.id) == MID && c.fits(it) }
        "bot-bruiser" -> isA(c, FIGHTER) && t.lineupRoles.any { (pos(it.id) == TOP || pos(it.id) == MID) && c.fits(it) }
        else -> false
    }

    /** How much the bot-side meta favours [c] in the bot slot (added to the draft's preference score). */
    fun botBias(botMeta: String, c: CharDef): Double {
        val mm = isA(c, MARKSMAN); val mage = isA(c, MAGE); val fighter = isA(c, FIGHTER)
        fun st(k: String) = ((c.styles[k] ?: 5.0) - 5.0) / 10.0
        return when (botMeta) {
            "bot-marksman" -> 0.0 // the lanes the game lists for the bot slot are marksmen already
            "bot-mage" -> if (mage && !mm) 0.32 else if (mm) -0.05 else 0.0
            "bot-hypercarry" -> (if (mm) 0.1 else 0.0) + st("scaling") * 0.8
            "bot-lethality" -> (if (mm) 0.1 else 0.0) + (st("early") + st("poke")) * 0.5
            "bot-bruiser" -> if (fighter && !mm) 0.3 else if (mm) -0.05 else 0.0
            "bot-utility" -> (if (mm) 0.1 else 0.0) + (st("teamfight") + st("poke")) * 0.4
            else -> 0.0
        }
    }

    /** The carry position under the current meta. */
    fun carry(w: World?, titleId: String): String = when (current(w, titleId).firstOrNull()) { "mid-carry" -> MID; "top-carry" -> TOP; "jungle-carry" -> JUNGLE; else -> BOT }

    /**
     * How much a team lets each position take the kill when it helped (1 = the carry). The usual
     * order: the bot-side carry, then mid, then the jungler and top, the support last.
     */
    fun killPriority(w: World?, titleId: String): Map<String, Double> {
        val m = current(w, titleId)
        // Dota 2 mids reach high levels first and burst heroes alone: the safe-lane carry is still given the kills.
        val p = hashMapOf(BOT to 1.0, MID to (if (titleId == "dota2") 0.5 else 0.74), TOP to 0.58, JUNGLE to 0.56, SUPPORT to 0.1)
        val carry = carry(w, titleId)
        if (carry != BOT) { p[carry] = 1.0; p[BOT] = 0.7 }
        if ("jungle-farm" in m) p[JUNGLE] = p[JUNGLE]!! + 0.08
        if ("jungle-gank" in m) p[JUNGLE] = p[JUNGLE]!! - 0.06
        if ("support-roam" in m) p[SUPPORT] = 0.18
        if ("support-babysit" in m) p[SUPPORT] = 0.06
        if ("mid-support" in m) p[MID] = minOf(p[MID]!!, 0.45)
        if ("mid-roam" in m) p[MID] = p[MID]!! + 0.06
        if ("top-tank" in m && carry != TOP) p[TOP] = 0.42
        if ("top-split" in m) p[TOP] = p[TOP]!! + 0.1
        // What the bot-side carry plays.
        if ("bot-lethality" in m) p[BOT] = p[BOT]!! + 0.05
        if ("bot-bruiser" in m) p[BOT] = p[BOT]!! + 0.03
        if ("bot-utility" in m) { p[BOT] = p[BOT]!! * 0.88; p[SUPPORT] = p[SUPPORT]!! + 0.04 }
        return p
    }

    /** Income (farm share) per position under the meta. */
    fun farm(w: World?, titleId: String): Map<String, Float> {
        val m = current(w, titleId)
        val f = hashMapOf(BOT to 1f, MID to 1f, TOP to 1f, JUNGLE to 1f, SUPPORT to 1f)
        val carry = carry(w, titleId)
        f[carry] = f[carry]!! * 1.06f
        if (carry != BOT) f[BOT] = f[BOT]!! * 0.95f
        if ("jungle-farm" in m) f[JUNGLE] = f[JUNGLE]!! * 1.05f
        if ("jungle-gank" in m) f[JUNGLE] = f[JUNGLE]!! * 0.97f
        if ("mid-support" in m) f[MID] = f[MID]!! * 0.95f
        if ("top-split" in m) f[TOP] = f[TOP]!! * 1.04f
        if ("top-tank" in m) f[TOP] = f[TOP]!! * 0.97f
        if ("support-roam" in m) f[SUPPORT] = f[SUPPORT]!! * 1.03f
        if ("bot-hypercarry" in m) f[BOT] = f[BOT]!! * 1.05f
        if ("bot-mage" in m) f[BOT] = f[BOT]!! * 0.98f
        if ("bot-utility" in m) f[BOT] = f[BOT]!! * 0.97f
        return f
    }

    /**
     * A patch moves the meta: one or two groups change (one in a medium patch, sometimes none in a
     * minor one). Returns the patch-note lines.
     */
    fun shift(w: World, t: TitleDef, kind: String, rng: Rng): List<String> {
        if (!t.isMoba || DEFAULTS[t.id] == null) return emptyList()
        val n = when (kind) { "major" -> rng.int(1, 2); "medium" -> if (rng.chance(0.55)) 1 else 0; else -> if (rng.chance(0.12)) 1 else 0 }
        if (n == 0) return emptyList()
        val now = current(w, t.id).toMutableList()
        val out = ArrayList<String>()
        // The carry group changes least often: most metas keep the bot-side carry.
        val groups = rng.sample(GROUPS.filter { it != "carry" || rng.chance(0.35) }, n)
        for (g in groups) {
            val i = GROUPS.indexOf(g)
            val opts = OPTIONS.filter { it.group == g && it.id != now[i] }
            if (opts.isEmpty()) continue
            // Carry metas drift back toward the bot side.
            // Bot-lane metas drift back toward marksmen (mage APC, bruiser and other bot lanes come and go).
            val next = if (g == "carry" && now[i] != "bot-carry" && rng.chance(0.55)) byId["bot-carry"]!!
                else if (g == "bot" && now[i] != "bot-marksman" && t.id != "dota2" && rng.chance(0.4)) byId["bot-marksman"]!! else rng.pick(opts)
            out.add("Meta — ${label(t.id, next.id)} (was ${label(t.id, now[i]).replaceFirstChar { it.lowercase() }}): ${next.desc}")
            now[i] = next.id
        }
        w.roleMetas[t.id] = now.joinToString(",")
        return out
    }
}

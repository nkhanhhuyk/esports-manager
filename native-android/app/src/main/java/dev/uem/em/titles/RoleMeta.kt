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
    )
    private val byId = OPTIONS.associateBy { it.id }
    val GROUPS = listOf("carry", "jungle", "support", "mid", "top", "pace")
    val GROUP_LABEL = mapOf("carry" to "Carry", "jungle" to "Jungle", "support" to "Support", "mid" to "Mid", "top" to "Top side", "pace" to "Pace")

    /** The meta each game starts in (roughly its real competitive meta). */
    private val DEFAULTS = mapOf(
        "lol" to listOf("bot-carry", "jungle-control", "support-engage", "mid-control", "top-tank", "objective-control"),
        "wildrift" to listOf("bot-carry", "jungle-gank", "support-engage", "mid-control", "top-bruiser", "early-skirmish"),
        "mlbb" to listOf("bot-carry", "jungle-farm", "support-roam", "mid-control", "top-tank", "early-skirmish"),
        "hok" to listOf("bot-carry", "jungle-farm", "support-roam", "mid-control", "top-tank", "objective-control"),
        "dota2" to listOf("bot-carry", "jungle-gank", "support-babysit", "mid-control", "top-tank", "scaling"),
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

    /** A meta's name in this game ("Gold lane carry meta", "Soft support (pos 4) gank meta"). */
    fun label(titleId: String, id: String): String {
        val (who, what) = id.split('-', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (id) {
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

    /** The carry position under the current meta. */
    fun carry(w: World?, titleId: String): String = when (current(w, titleId).firstOrNull()) { "mid-carry" -> MID; "top-carry" -> TOP; "jungle-carry" -> JUNGLE; else -> BOT }

    /**
     * How much a team lets each position take the kill when it helped (1 = the carry). The usual
     * order: the bot-side carry, then mid, then the jungler and top, the support last.
     */
    fun killPriority(w: World?, titleId: String): Map<String, Double> {
        val m = current(w, titleId)
        // Dota 2 mids reach high levels first and burst heroes alone: the safe-lane carry is still given the kills.
        val p = hashMapOf(BOT to 1.0, MID to (if (titleId == "dota2") 0.6 else 0.74), TOP to 0.58, JUNGLE to 0.56, SUPPORT to 0.1)
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
            val next = if (g == "carry" && now[i] != "bot-carry" && rng.chance(0.55)) byId["bot-carry"]!! else rng.pick(opts)
            out.add("Meta — ${label(t.id, next.id)} (was ${label(t.id, now[i]).replaceFirstChar { it.lowercase() }}): ${next.desc}")
            now[i] = next.id
        }
        w.roleMetas[t.id] = now.joinToString(",")
        return out
    }
}

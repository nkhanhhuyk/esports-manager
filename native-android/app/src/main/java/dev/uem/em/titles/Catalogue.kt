package dev.uem.em.titles

import dev.uem.em.core.Rng
import dev.uem.em.data.DataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** The seven titles. Characters come from the real-data snapshot (characters.json). */
object Catalogue {
    private fun seq(code: String) = code.trim().split(Regex("\\s+")).map { DraftStep(if (it[1] == 'A') 0 else 1, it[0] == 'b') }
    private fun S(key: String, label: String, good: Boolean = true) = StatDef(key, label, good)

    private val MOBA_STATS = listOf(S("k", "K"), S("d", "D", false), S("a", "A"), S("gold", "Gold"), S("cs", "CS"), S("dmg", "Dmg"), S("taken", "Taken"), S("heal", "Heal"), S("vision", "Vision"), S("obj", "Obj"), S("towers", "Struct"))
    private val DOTA_STATS = listOf(S("k", "K"), S("d", "D", false), S("a", "A"), S("gold", "Net Worth"), S("cs", "LH"), S("dn", "DN"), S("dmg", "Hero Dmg"), S("heal", "Heal"), S("vision", "Wards"), S("obj", "Obj"), S("towers", "Struct"), S("bb", "Buybacks", false))
    private val TAC_STATS = listOf(S("k", "K"), S("d", "D", false), S("a", "A"), S("dmg", "Dmg"), S("fk", "FK"), S("fd", "FD", false), S("clutch", "Clutch"), S("plant", "Plants"), S("defuse", "Defuses"), S("kast", "KAST rds"), S("hs", "HS"), S("mk", "Multi"), S("util", "Util"))
    private val MOBA_STYLES = listOf("early", "scaling", "teamfight", "pick", "split", "poke")
    private val TAC_STYLES = listOf("aggressive", "methodical", "utility", "aim", "retake")
    private val MOBA_PHASES = mapOf(Phase.LANING to 0.18, Phase.JUNGLE to 0.12, Phase.SKIRMISH to 0.15, Phase.TEAMFIGHT to 0.25, Phase.MACRO to 0.18, Phase.VISION to 0.07, Phase.CLUTCH to 0.05)
    private val TAC_PHASES = mapOf(Phase.AIM to 0.32, Phase.ENTRY to 0.12, Phase.UTILITY to 0.14, Phase.IGL to 0.12, Phase.CLUTCH to 0.1, Phase.VISION to 0.1, Phase.ECONOMY to 0.05, Phase.SURVIVAL to 0.05)
    private val NO_SHOOTER = listOf("flickAim", "trackingAim", "crosshairPlacement", "recoilControl", "burstSprayControl", "projectileLeading", "peekMechanics", "movementShooting", "angleClearing", "utilityPlacement", "soundAwareness", "ammoDiscipline", "longRangePrecision", "weaponSwitching").associateWith { 0.0 }
    private val NO_MOBA = listOf("lastHitting", "waveManagement", "laneTrading", "junglePathing", "ganking", "creepControl", "splitPushing", "cooldownTracking", "structurePressure", "resetTiming").associateWith { 0.0 }

    private val DRAFT_20 = seq("bA bB bA bB bA bB pA pB pB pA pA pB bB bA bB bA pB pA pA pB")
    private val DRAFT_DOTA = seq("bA bB bA bB bA bB bA bB pA pB pB pA bA bB bA bB pA pB pB pA bB bA pA pB")
    private val DRAFT_HOK = seq("bA bB bA bB pA pB pB pA pA pB bB bA bB bA pB pA pA pB")

    private class Seed(val fclass: List<String>, val phases: Map<Phase, Double>, val emphasis: Map<String, Double>)

    private val SOLO = Seed(listOf("Controller/Anchor", "Flex"), mapOf(Phase.LANING to 0.35, Phase.SKIRMISH to 0.15, Phase.TEAMFIGHT to 0.2, Phase.MACRO to 0.2, Phase.VISION to 0.05, Phase.CLUTCH to 0.05),
        mapOf("dueling" to 1.8, "laneTrading" to 1.8, "splitPushing" to 2.0, "initiation" to 1.3, "survivability" to 1.4, "waveManagement" to 1.4, "mentalResilience" to 1.2, "patience" to 1.2, "junglePathing" to 0.2, "ganking" to 0.3))
    private val JUNGLE = Seed(listOf("Flanker/Lurker", "Shotcaller/IGL"), mapOf(Phase.JUNGLE to 0.4, Phase.SKIRMISH to 0.2, Phase.TEAMFIGHT to 0.15, Phase.MACRO to 0.15, Phase.VISION to 0.1),
        mapOf("junglePathing" to 2.5, "ganking" to 2.5, "creepControl" to 2.0, "tempoControl" to 1.8, "mapAwareness" to 1.6, "objectivePrioritisation" to 1.8, "objectiveExecution" to 1.6, "anticipation" to 1.4, "opponentTracking" to 1.6, "pressureCreation" to 1.5, "lastHitting" to 0.3, "laneTrading" to 0.3, "waveManagement" to 0.4))
    private val MID = Seed(listOf("Carry/Fragger", "Flex"), mapOf(Phase.LANING to 0.3, Phase.SKIRMISH to 0.2, Phase.TEAMFIGHT to 0.25, Phase.MACRO to 0.15, Phase.VISION to 0.05, Phase.CLUTCH to 0.05),
        mapOf("comboExecution" to 1.8, "microControl" to 1.5, "dueling" to 1.4, "damageOptimisation" to 1.5, "laneTrading" to 1.6, "lastHitting" to 1.4, "pressureCreation" to 1.4, "rotations" to 1.3, "cooldownTracking" to 1.4, "junglePathing" to 0.2))
    private val CARRY = Seed(listOf("Carry/Fragger"), mapOf(Phase.LANING to 0.3, Phase.TEAMFIGHT to 0.35, Phase.SKIRMISH to 0.1, Phase.MACRO to 0.1, Phase.CLUTCH to 0.1, Phase.VISION to 0.05),
        mapOf("lastHitting" to 2.2, "damageOptimisation" to 2.0, "spacing" to 2.0, "targeting" to 1.8, "survivability" to 1.4, "laneTrading" to 1.4, "accuracy" to 1.4, "buildOptimisation" to 1.4, "resetTiming" to 1.4, "junglePathing" to 0.2, "ganking" to 0.3))
    private val SUPPORT = Seed(listOf("Support/Utility", "Entry/Initiator"), mapOf(Phase.LANING to 0.15, Phase.TEAMFIGHT to 0.3, Phase.VISION to 0.3, Phase.SKIRMISH to 0.1, Phase.MACRO to 0.1, Phase.CLUTCH to 0.05),
        mapOf("peeling" to 2.0, "crowdControl" to 1.8, "initiation" to 1.6, "informationControl" to 2.0, "selflessness" to 2.0, "communication" to 1.6, "shotcalling" to 1.4, "mapAwareness" to 1.4, "lastHitting" to 0.2, "junglePathing" to 0.3, "damageOptimisation" to 0.6))
    private val ROAM = Seed(listOf("Support/Utility", "Entry/Initiator", "Shotcaller/IGL"), mapOf(Phase.TEAMFIGHT to 0.3, Phase.VISION to 0.3, Phase.SKIRMISH to 0.15, Phase.MACRO to 0.15, Phase.JUNGLE to 0.1),
        mapOf("initiation" to 2.0, "crowdControl" to 1.8, "informationControl" to 2.0, "peeling" to 1.6, "selflessness" to 1.8, "shotcalling" to 1.6, "mapAwareness" to 1.6, "rotations" to 1.5, "lastHitting" to 0.1, "ganking" to 1.2))
    private val S_ENTRY = Seed(listOf("Entry/Initiator", "Carry/Fragger"), mapOf(Phase.AIM to 0.35, Phase.ENTRY to 0.4, Phase.CLUTCH to 0.05, Phase.UTILITY to 0.1, Phase.VISION to 0.1),
        mapOf("flickAim" to 2.0, "peekMechanics" to 2.0, "initiation" to 2.0, "movement" to 1.6, "reactionSpeed" to 1.5, "dueling" to 1.8, "movementShooting" to 1.2, "patience" to 0.6))
    private val S_STAR = Seed(listOf("Carry/Fragger", "Flex"), mapOf(Phase.AIM to 0.5, Phase.ENTRY to 0.15, Phase.CLUTCH to 0.15, Phase.VISION to 0.1, Phase.UTILITY to 0.1),
        mapOf("flickAim" to 1.6, "trackingAim" to 1.5, "crosshairPlacement" to 1.8, "recoilControl" to 1.6, "burstSprayControl" to 1.6, "dueling" to 1.6, "damageOptimisation" to 1.4))
    private val S_SNIPER = Seed(listOf("Carry/Fragger", "Controller/Anchor"), mapOf(Phase.AIM to 0.55, Phase.CLUTCH to 0.15, Phase.VISION to 0.15, Phase.ENTRY to 0.05, Phase.UTILITY to 0.1),
        mapOf("longRangePrecision" to 2.5, "flickAim" to 2.0, "reactionSpeed" to 1.6, "patience" to 1.4, "spaceControl" to 1.6, "weaponSwitching" to 1.4, "recoilControl" to 0.5, "burstSprayControl" to 0.5))
    private val S_SUPPORT = Seed(listOf("Support/Utility", "Scout/Recon"), mapOf(Phase.UTILITY to 0.4, Phase.AIM to 0.25, Phase.VISION to 0.15, Phase.SUPPORT to 0.1, Phase.CLUTCH to 0.1),
        mapOf("utilityPlacement" to 2.2, "selflessness" to 1.8, "coordination" to 1.8, "followUp" to 1.6, "informationControl" to 1.5, "teamwork" to 1.5))
    private val S_LURKER = Seed(listOf("Flanker/Lurker"), mapOf(Phase.AIM to 0.35, Phase.VISION to 0.25, Phase.CLUTCH to 0.2, Phase.ENTRY to 0.1, Phase.UTILITY to 0.1),
        mapOf("soundAwareness" to 2.0, "patience" to 1.8, "anticipation" to 1.8, "opponentTracking" to 1.8, "creativity" to 1.4, "timing" to 1.6, "composure" to 1.4))
    private val S_ANCHOR = Seed(listOf("Controller/Anchor"), mapOf(Phase.AIM to 0.35, Phase.CLUTCH to 0.2, Phase.VISION to 0.2, Phase.UTILITY to 0.2, Phase.SUPPORT to 0.05),
        mapOf("angleClearing" to 1.6, "soundAwareness" to 1.6, "patience" to 1.8, "anticipation" to 1.6, "utilityPlacement" to 1.6, "composure" to 1.4, "crosshairPlacement" to 1.4, "spaceControl" to 1.5))
    private val S_CONTROLLER = Seed(listOf("Controller/Anchor", "Shotcaller/IGL"), mapOf(Phase.UTILITY to 0.4, Phase.IGL to 0.2, Phase.AIM to 0.2, Phase.VISION to 0.1, Phase.CLUTCH to 0.1),
        mapOf("utilityPlacement" to 2.2, "spaceControl" to 2.0, "tempoControl" to 1.6, "shotcalling" to 1.4, "patience" to 1.4, "decisionMaking" to 1.4, "timing" to 1.5))
    private val S_RECON = Seed(listOf("Scout/Recon", "Support/Utility"), mapOf(Phase.UTILITY to 0.35, Phase.VISION to 0.25, Phase.AIM to 0.25, Phase.ENTRY to 0.1, Phase.CLUTCH to 0.05),
        mapOf("informationControl" to 2.2, "utilityPlacement" to 2.0, "coordination" to 1.6, "followUp" to 1.6, "timing" to 1.4, "soundAwareness" to 1.2))
    private val S_FLEX = Seed(listOf("Flex"), mapOf(Phase.AIM to 0.35, Phase.UTILITY to 0.2, Phase.ENTRY to 0.15, Phase.CLUTCH to 0.15, Phase.VISION to 0.15),
        mapOf("adaptability" to 1.8, "creativity" to 1.4, "matchupKnowledge" to 1.4, "metaUnderstanding" to 1.4))

    private fun role(id: String, name: String, short: String, s: Seed, classes: List<String>, emphasis: Map<String, Double> = emptyMap(), weapons: List<String>? = null) =
        RoleDef(id, name, short, s.fclass, s.phases, s.emphasis + emphasis, classes, weapons)

    private fun obj(id: String, name: String, spawn: Double, respawn: Double, kind: String, value: Double, until: Double? = null, maxStacks: Int = 0, soul: Double = 0.0, major: Boolean = false) =
        Objective(id, name, spawn, respawn, until, kind, value, maxStacks, soul, major)

    lateinit var ALL: List<TitleDef>
        private set
    lateinit var BY_ID: Map<String, TitleDef>
        private set
    val IDS = listOf("mlbb", "wildrift", "hok", "lol", "dota2", "valorant", "cs2")

    fun get(id: String): TitleDef = BY_ID[id] ?: error("Unknown title $id")

    @Volatile private var loaded = false

    @Synchronized
    fun init(src: DataSource) {
        if (loaded) return
        val chars = Json.parseToJsonElement(src.read("characters.json")).jsonObject["titles"]!!.jsonObject
        val valMaps = Json.parseToJsonElement(src.read("valorantMaps.json")).jsonObject["maps"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        ALL = build(valMaps)
        for (t in ALL) {
            val raw = chars[t.id]?.jsonObject?.get("characters")?.jsonArray ?: continue
            t.characters = raw.map { realChar(t, it.jsonObject) }
        }
        BY_ID = ALL.associateBy { it.id }
        for (t in ALL) base[t.id] = t.characters
        loaded = true
    }

    private val base = HashMap<String, List<CharDef>>()

    /** The real roster of each game plus the fictional characters this world's patches released. */
    fun applyCustom(w: dev.uem.em.model.World) {
        for (t in ALL) {
            val custom = w.customChars.filter { it.titleId == t.id }.map { c ->
                val rng = Rng.of("custom|${c.id}")
                val aff = CLASS_STYLE.filter { (k, _) -> c.classes.any { it.lowercase().contains(k) } }.map { it.second }
                val styles = t.styles.associateWith { st -> var b = 3 + rng.float(0.0, 2.5); for (a in aff) a[st]?.let { b = maxOf(b, it + rng.float(-1.0, 1.0)) }; b }
                CharDef(c.id, c.name, c.cls, c.classes, c.lanes, styles, c.kit.getOrElse(0) { 5 })
            }
            t.characters = (base[t.id] ?: t.characters) + custom
        }
    }

    private val CLASS_STYLE: List<Pair<String, Map<String, Double>>> = listOf(
        "assassin" to mapOf("pick" to 8.0, "early" to 6.0, "dive" to 8.0, "aggro" to 7.0, "gank" to 7.0, "aggressive" to 7.0, "fight" to 7.0),
        "tank" to mapOf("teamfight" to 8.0, "early" to 4.0, "brawl" to 8.0, "control" to 6.0, "fight" to 6.0),
        "guardian" to mapOf("teamfight" to 8.0, "brawl" to 7.0, "control" to 6.0),
        "vanguard" to mapOf("brawl" to 8.0, "dive" to 6.0, "teamfight" to 7.0),
        "front" to mapOf("brawl" to 8.0, "teamfight" to 7.0),
        "marksman" to mapOf("scaling" to 8.0, "teamfight" to 6.0, "poke" to 6.0),
        "hunter" to mapOf("scaling" to 8.0, "poke" to 6.0, "teamfight" to 5.0),
        "carry" to mapOf("scaling" to 8.0, "farm" to 8.0, "teamfight" to 6.0),
        "sharpshooter" to mapOf("poke" to 8.0, "control" to 5.0),
        "sniper" to mapOf("poke" to 8.0, "scaling" to 6.0),
        "mage" to mapOf("poke" to 7.0, "teamfight" to 7.0, "early" to 5.0),
        "caster" to mapOf("poke" to 7.0, "teamfight" to 6.0),
        "nuker" to mapOf("poke" to 7.0, "pick" to 6.0, "early" to 6.0),
        "fighter" to mapOf("split" to 7.0, "early" to 6.0, "skirmish" to 7.0, "fight" to 6.0),
        "warrior" to mapOf("split" to 7.0, "early" to 6.0),
        "bruiser" to mapOf("split" to 7.0, "brawl" to 6.0, "fight" to 7.0),
        "all-rounder" to mapOf("split" to 6.0, "early" to 6.0),
        "speedster" to mapOf("pick" to 7.0, "early" to 7.0),
        "attacker" to mapOf("scaling" to 7.0, "poke" to 6.0),
        "defender" to mapOf("teamfight" to 7.0, "control" to 6.0),
        "support" to mapOf("teamfight" to 6.0, "scaling" to 5.0, "sustain" to 7.0, "retake" to 6.0),
        "healer" to mapOf("sustain" to 8.0, "teamfight" to 6.0),
        "enchanter" to mapOf("sustain" to 7.0, "scaling" to 6.0),
        "strategist" to mapOf("sustain" to 8.0, "brawl" to 5.0),
        "damage" to mapOf("poke" to 6.0, "dive" to 5.0, "aggro" to 5.0),
        "duelist" to mapOf("dive" to 6.0, "poke" to 6.0, "aggressive" to 7.0),
        "flank" to mapOf("dive" to 8.0, "pick" to 7.0),
        "controller" to mapOf("control" to 8.0, "methodical" to 7.0, "utility" to 6.0),
        "initiator" to mapOf("utility" to 7.0, "aggressive" to 6.0, "dive" to 5.0),
        "sentinel" to mapOf("methodical" to 7.0, "retake" to 7.0),
        "entry" to mapOf("aggressive" to 8.0),
        "breach" to mapOf("methodical" to 6.0, "aggressive" to 5.0, "utility" to 6.0),
        "anchor" to mapOf("methodical" to 8.0, "retake" to 6.0),
        "roamer" to mapOf("aggressive" to 6.0, "pick" to 7.0),
    )

    private fun realChar(t: TitleDef, o: JsonObject): CharDef {
        val id = o["character_id"]!!.jsonPrimitive.content
        val classes = o["classes"]!!.jsonArray.map { it.jsonPrimitive.content }.filter { it in t.charClasses }
        val cls = classes.firstOrNull() ?: t.charClasses[0]
        val lanes = o["lanes"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val kit = o["kit"]?.jsonObject
        fun kitV(k: String) = kit?.get(k)?.jsonPrimitive?.doubleOrNull
        val rng = Rng.of("real|$id")
        val aff = CLASS_STYLE.filter { (k, _) -> classes.any { it.lowercase().contains(k) } }.map { it.second }
        val styles = HashMap<String, Double>()
        for (s in t.styles) {
            var base = 3 + rng.float(0.0, 2.5)
            for (a in aff) a[s]?.let { base = maxOf(base, it + rng.float(-1.0, 1.0)) }
            kitV("control")?.let { if (Regex("control|teamfight|pick").containsMatchIn(s)) base += (it - 5) * 0.25 }
            kitV("durability")?.let { if (Regex("brawl|teamfight|front").containsMatchIn(s)) base += (it - 5) * 0.2 }
            kitV("offense")?.let { if (Regex("early|pick|dive|poke").containsMatchIn(s)) base += (it - 5) * 0.2 }
            styles[s] = (base.coerceIn(0.0, 10.0) * 10).roundToInt() / 10.0
        }
        val d = kitV("difficulty")
        val max = when (t.id) { "lol" -> 10.0; "wildrift" -> 9.0; "mlbb" -> 3.0; else -> 10.0 }
        val diff = if (d == null || d.isNaN()) 5 else ((d / max) * 10).roundToInt().coerceIn(1, 10)
        return CharDef(id, o["name"]!!.jsonPrimitive.contentOrNull ?: id, cls, classes, lanes, styles, diff)
    }

    private fun build(valMaps: List<String>): List<TitleDef> {
        val moba = { id: String, name: String, short: String, mkb: Boolean, roles: List<RoleDef>, charLabel: String, charClasses: List<String>, map: String, draft: DraftRules, formats: Triple<Int, Int, Int>, spec: Map<String, Double>, cfg: MobaConfig, stats: List<StatDef>, salary: Double, prize: Double ->
            TitleDef(id, name, short, Engine.MOBA, mkb, 5, 16, roles, roles.map { it.id }, charLabel, charClasses, emptyList(), listOf(MapDef("$id:battlefield", map)), draft,
                Triple(SeriesFormat(formats.first), SeriesFormat(formats.second), SeriesFormat(formats.third)), MOBA_STYLES, NO_SHOOTER + spec, emptyList(), MOBA_PHASES, cfg, null, stats, salary, prize)
        }
        val mlbb = moba("mlbb", "Mobile Legends: Bang Bang", "MLBB", false,
            listOf(role("exp", "EXP Lane", "EXP", SOLO, listOf("Fighter", "Tank")), role("jungle", "Jungler", "JNG", JUNGLE, listOf("Assassin", "Fighter", "Marksman")), role("mid", "Mid Lane", "MID", MID, listOf("Mage")), role("gold", "Gold Lane", "GLD", CARRY, listOf("Marksman")), role("roam", "Roamer", "ROM", ROAM, listOf("Tank", "Support"))),
            "Hero", listOf("Tank", "Fighter", "Assassin", "Mage", "Marksman", "Support"), "Land of Dawn", DraftRules("sequential", DRAFT_20, 5, true), Triple(3, 5, 7),
            mapOf("lastHitting" to 0.5, "ganking" to 1.0, "creepControl" to 1.1, "accuracy" to 0.0),
            MobaConfig(5.0, 15.0, 30.0, 700.0, 0.45, 1.2, listOf("EXP lane" to listOf("exp"), "Mid lane" to listOf("mid"), "Gold lane" to listOf("gold")), listOf("jungle"),
                listOf(obj("turtle", "Turtle", 2.0, 2.0, "gold", 160.0, until = 7.9), obj("lord", "Lord", 8.0, 3.0, "push", 0.32, major = true)),
                Structures(3, 3, 0, listOf("Outer Turret", "Inner Turret", "Base Turret"), "", "Base Crystal")), MOBA_STATS, 140000.0, 1.0)
        val wr = moba("wildrift", "League of Legends: Wild Rift", "Wild Rift", false,
            listOf(role("baron", "Baron Lane", "BAR", SOLO, listOf("Fighter", "Tank")), role("jungle", "Jungle", "JNG", JUNGLE, listOf("Fighter", "Assassin")), role("mid", "Mid", "MID", MID, listOf("Mage", "Assassin")), role("dragon", "Dragon Lane", "DRG", CARRY, listOf("Marksman")), role("support", "Support", "SUP", SUPPORT, listOf("Enchanter", "Tank"))),
            "Champion", listOf("Fighter", "Tank", "Mage", "Assassin", "Marksman", "Enchanter"), "Summoner's Rift (Wild Rift)", DraftRules("sequential", DRAFT_20, 5, true), Triple(3, 5, 7),
            mapOf("lastHitting" to 0.8),
            MobaConfig(7.0, 18.0, 35.0, 600.0, 0.35, 1.3, listOf("Baron lane" to listOf("baron"), "Mid lane" to listOf("mid"), "Dragon lane" to listOf("dragon", "support")), listOf("jungle"),
                listOf(obj("drake", "Elemental Dragon", 4.0, 4.0, "stack", 0.03, maxStacks = 3, soul = 0.07), obj("herald", "Rift Herald", 6.0, 99.0, "push", 0.15, until = 10.9), obj("baron", "Baron Nashor", 11.0, 3.5, "push", 0.3, major = true), obj("elder", "Elder Dragon", 19.0, 5.0, "execute", 0.22, major = true)),
                Structures(3, 3, 1, listOf("Outer Turret", "Inner Turret", "Inhibitor Turret"), "Nexus Turret", "Nexus")), MOBA_STATS, 100000.0, 0.8)
        val hok = moba("hok", "Honor of Kings", "HoK", false,
            listOf(role("clash", "Clash Lane", "CLS", SOLO, listOf("Warrior", "Tank")), role("jungle", "Jungler", "JNG", JUNGLE, listOf("Assassin", "Warrior")), role("mid", "Mid Lane", "MID", MID, listOf("Mage")), role("farm", "Farm Lane", "FRM", CARRY, listOf("Marksman")), role("roam", "Roaming", "ROM", ROAM, listOf("Support", "Tank"))),
            "Hero", listOf("Tank", "Warrior", "Assassin", "Mage", "Marksman", "Support"), "Kings' Canyon", DraftRules("sequential", DRAFT_HOK, 4, true), Triple(3, 5, 7),
            mapOf("lastHitting" to 0.5, "creepControl" to 1.1),
            MobaConfig(5.0, 17.0, 32.0, 750.0, 0.4, 1.25, listOf("Clash lane" to listOf("clash"), "Mid lane" to listOf("mid"), "Farm lane" to listOf("farm")), listOf("jungle"),
                listOf(obj("tyrant", "Tyrant", 4.0, 4.0, "gold", 150.0, until = 9.9), obj("overlord", "Overlord", 4.0, 4.0, "push", 0.2, until = 9.9), obj("shadowtyrant", "Shadow Tyrant", 10.0, 3.5, "buff", 0.06, until = 19.9), obj("shadow", "Shadow Overlord", 10.0, 3.5, "push", 0.32, until = 19.9, major = true), obj("tempest", "Tempest Dragon", 20.0, 3.0, "execute", 0.25, major = true)),
                Structures(3, 3, 0, listOf("Outer Tower", "Inner Tower", "High-ground Tower"), "", "Crystal")), MOBA_STATS, 260000.0, 2.0)
        val lol = moba("lol", "League of Legends", "LoL", true,
            listOf(role("top", "Top", "TOP", SOLO, listOf("Fighter", "Tank")), role("jungle", "Jungle", "JNG", JUNGLE, listOf("Fighter", "Assassin", "Tank")), role("mid", "Mid", "MID", MID, listOf("Mage", "Assassin")), role("bot", "Bot (ADC)", "ADC", CARRY, listOf("Marksman")), role("support", "Support", "SUP", SUPPORT, listOf("Enchanter", "Tank"))),
            "Champion", listOf("Fighter", "Tank", "Mage", "Assassin", "Marksman", "Enchanter"), "Summoner's Rift", DraftRules("sequential", DRAFT_20, 5, true), Triple(3, 5, 5),
            emptyMap(),
            MobaConfig(14.0, 31.0, 55.0, 400.0, 0.3, 1.4, listOf("Top lane" to listOf("top"), "Mid lane" to listOf("mid"), "Bot lane" to listOf("bot", "support")), listOf("jungle"),
                listOf(obj("drake", "Elemental Drake", 5.0, 5.0, "stack", 0.03, maxStacks = 4, soul = 0.08), obj("grubs", "Void Grubs", 8.0, 4.0, "siege", 0.05, until = 14.0), obj("herald", "Rift Herald", 15.0, 99.0, "push", 0.15, until = 19.9), obj("baron", "Baron Nashor", 20.0, 6.0, "push", 0.3, major = true), obj("elder", "Elder Dragon", 35.0, 6.0, "execute", 0.25, major = true)),
                Structures(3, 4, 2, listOf("Outer Turret", "Inner Turret", "Inhibitor Turret", "Inhibitor"), "Nexus Turret", "Nexus")), MOBA_STATS, 320000.0, 2.2)
        val dota = moba("dota2", "Dota 2", "Dota 2", true,
            listOf(role("pos1", "Safe Lane (Pos 1)", "P1", CARRY, listOf("Agility Carry", "Strength Core"), mapOf("lastHitting" to 2.6, "creepControl" to 1.8, "resourceDenial" to 1.6, "resourceEfficiency" to 1.8)),
                role("pos2", "Mid (Pos 2)", "P2", MID, listOf("Intelligence Nuker", "Agility Carry"), mapOf("resourceDenial" to 1.6)),
                role("pos3", "Offlane (Pos 3)", "P3", SOLO, listOf("Initiator", "Strength Core"), mapOf("initiation" to 2.0, "spaceControl" to 1.6)),
                role("pos4", "Soft Support (Pos 4)", "P4", ROAM, listOf("Initiator", "Support"), mapOf("ganking" to 1.8, "creepControl" to 1.2)),
                role("pos5", "Hard Support (Pos 5)", "P5", SUPPORT, listOf("Support"), mapOf("informationControl" to 2.4, "resourceDenial" to 1.2))),
            "Hero", listOf("Agility Carry", "Strength Core", "Intelligence Nuker", "Initiator", "Support", "Pusher"), "Dota 2 map (Radiant vs Dire)", DraftRules("sequential", DRAFT_DOTA, 7, true), Triple(3, 3, 5),
            mapOf("lastHitting" to 1.2, "creepControl" to 1.2),
            MobaConfig(12.0, 38.0, 70.0, 520.0, 0.55, 1.5, listOf("Safe lane" to listOf("pos1", "pos5"), "Mid lane" to listOf("pos2"), "Off lane" to listOf("pos3", "pos4")), listOf("pos4"),
                listOf(obj("wisdom", "Wisdom Runes", 7.0, 7.0, "xp", 0.03), obj("roshan", "Roshan (Aegis)", 15.0, 9.0, "aegis", 1.0, major = true), obj("tormentor", "Tormentor", 20.0, 10.0, "buff", 0.04)),
                Structures(3, 4, 2, listOf("Tier 1 Tower", "Tier 2 Tower", "Tier 3 Tower", "Barracks"), "Tier 4 Tower", "Ancient"), denies = true, buyback = true), DOTA_STATS, 220000.0, 3.0)
        val valorant = TitleDef("valorant", "Valorant", "Valorant", Engine.TACTICAL, true, 5, 16,
            listOf(role("duelist", "Duelist", "DUE", S_ENTRY, listOf("Duelist")), role("initiator", "Initiator", "INI", S_RECON, listOf("Initiator")), role("controller", "Controller", "CTL", S_CONTROLLER, listOf("Controller")), role("sentinel", "Sentinel", "SEN", S_ANCHOR, listOf("Sentinel")), role("flex", "Flex", "FLX", S_FLEX, listOf("Initiator", "Duelist", "Sentinel", "Controller"))),
            listOf("duelist", "initiator", "controller", "sentinel", "flex"), "Agent", listOf("Duelist", "Initiator", "Controller", "Sentinel"), emptyList(),
            valMaps.map { MapDef("valorant:${it.lowercase()}", it) }.filter { it.id.substringAfter(':') in VAL_POOL }, DraftRules("simultaneous", emptyList(), 0, false),
            Triple(SeriesFormat(3), SeriesFormat(3), SeriesFormat(5)), TAC_STYLES,
            NO_MOBA + mapOf("recoilControl" to 0.8, "burstSprayControl" to 0.8, "projectileLeading" to 0.2, "movementShooting" to 0.3, "cooldownTracking" to 0.4),
            listOf("Rifle", "Sniper", "SMG", "Sidearm", "Shotgun", "Machine Gun"), TAC_PHASES, null,
            TacticalConfig(13, 12, true, 2, 800, 3000, intArrayOf(1900, 2400, 2900), 200, 300, 9000, 3900, "Spike", "Attack" to "Defense", true, 0.9, 0.06), TAC_STATS, 260000.0, 2.25)
        val cs2 = TitleDef("cs2", "Counter-Strike 2", "CS2", Engine.TACTICAL, true, 5, 16,
            listOf(role("entry", "Entry Fragger", "ENT", S_ENTRY, emptyList(), weapons = listOf("Rifle", "SMG")), role("awper", "AWPer", "AWP", S_SNIPER, emptyList(), weapons = listOf("Sniper", "Pistol")), role("rifler", "Star Rifler", "RIF", S_STAR, emptyList(), weapons = listOf("Rifle")), role("support", "Support", "SUP", S_SUPPORT, emptyList(), weapons = listOf("Rifle", "Pistol")), role("lurker", "Lurker", "LRK", S_LURKER, emptyList(), weapons = listOf("Rifle", "SMG"))),
            listOf("entry", "awper", "rifler", "support", "lurker"), "Loadout", emptyList(), emptyList(),
            CS2_POOL.map { MapDef("cs2:${it.first}", it.second) }, DraftRules("none", emptyList(), 0, false),
            Triple(SeriesFormat(3), SeriesFormat(3), SeriesFormat(5)), TAC_STYLES,
            NO_MOBA + mapOf("recoilControl" to 1.3, "burstSprayControl" to 1.3, "projectileLeading" to 0.0, "movementShooting" to 0.2, "utilityPlacement" to 0.9),
            listOf("Rifle", "Sniper", "SMG", "Pistol", "Shotgun", "Heavy"), TAC_PHASES + mapOf(Phase.AIM to 0.36, Phase.UTILITY to 0.1), null,
            TacticalConfig(13, 12, false, 6, 800, 3250, intArrayOf(1400, 1900, 2400, 2900, 3400), 300, 800, 16000, 4700, "Bomb", "T" to "CT", false, 0.6, 0.08), TAC_STATS, 290000.0, 1.25)
        return listOf(mlbb, wr, hok, lol, dota, valorant, cs2)
    }

    private val VAL_POOL = setOf("summit", "abyss", "sunset", "lotus", "corrode", "haven", "bind")
    private val CS2_POOL = listOf("mirage" to "Mirage", "inferno" to "Inferno", "dust2" to "Dust II", "anubis" to "Anubis", "ancient" to "Ancient", "nuke" to "Nuke", "cache" to "Cache")

    /** Region popularity of each title (0-100), for fan bases and generation. */
    val POP_REGIONS = listOf("NA", "LATN", "LATS", "BR", "EUW", "EUE", "CIS", "MENA", "AFR", "KR", "CN", "PCS", "JP", "VN", "TH", "ID", "PH", "MYSG", "SA", "OCE")
    private val POP = mapOf(
        "mlbb" to "15 45 30 25 10 15 30 45 25 5 10 10 10 20 25 100 100 85 40 10",
        "wildrift" to "20 35 30 40 30 30 25 30 15 40 70 55 40 75 60 35 50 45 25 15",
        "hok" to "10 25 20 45 10 10 10 25 10 10 100 40 15 25 30 40 30 50 15 5",
        "lol" to "70 60 55 70 85 70 45 50 15 100 95 70 50 85 40 20 25 30 20 40",
        "dota2" to "40 45 60 40 70 85 100 55 25 10 85 20 10 20 35 40 70 60 35 20",
        "valorant" to "90 70 65 85 90 75 60 70 25 75 80 60 90 50 60 55 50 55 50 50",
        "cs2" to "70 45 60 95 90 100 100 65 45 15 50 20 20 25 25 30 20 30 40 55",
    )
    val POPULARITY: Map<String, Map<String, Int>> = POP.mapValues { (_, s) -> s.split(" ").map { it.toInt() }.let { v -> POP_REGIONS.withIndex().associate { it.value to v[it.index] } } }
}

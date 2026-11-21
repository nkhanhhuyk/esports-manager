package dev.uem.em.titles

import dev.uem.em.model.Attrs

/**
 * The seven games (rulesets), ported from the web version's title catalogue: roles and their phase
 * weightings, draft orders, objectives, structures, economy, series formats and stat columns.
 */
enum class Engine { MOBA, TACTICAL }

enum class Phase { LANING, JUNGLE, SKIRMISH, TEAMFIGHT, MACRO, VISION, AIM, ENTRY, UTILITY, IGL, CLUTCH, OBJECTIVE, SURVIVAL, SUPPORT, MOVEMENT, ECONOMY }

class RoleDef(
    val id: String,
    val name: String,
    val short: String,
    val fclass: List<String>,
    val phases: Map<Phase, Double>,
    val emphasis: Map<String, Double>,
    val classes: List<String>,
    val weapons: List<String>? = null,
) {
    /** Phase weights as an array indexed by Phase.ordinal (hot path). */
    val phaseArr = DoubleArray(Phase.entries.size) { phases[Phase.entries[it]] ?: 0.0 }
    val isCarry = "Carry/Fragger" in fclass
    val isSupport = "Support/Utility" in fclass
    val isAnchor = "Controller/Anchor" in fclass
    val isEntry = "Entry/Initiator" in fclass
}

class CharDef(
    val id: String,
    val name: String,
    val cls: String,
    val classes: List<String>,
    val lanes: List<String>,
    val styles: Map<String, Double>,
    val difficulty: Int,
) {
    fun fits(role: RoleDef): Boolean = when {
        lanes.isNotEmpty() -> role.id in lanes
        classes.isNotEmpty() -> classes.any { it in role.classes }
        else -> cls in role.classes
    }

    private val lc = cls.lowercase()
    val dmgClass = Regex("marksman|mage|sniper|hunter|sharpshooter|damage|duelist|attacker|carry|assassin|nuker").containsMatchIn(lc)
    val tankyClass = Regex("tank|guardian|vanguard|front|defender").containsMatchIn(lc)
    val frontClass = Regex("tank|guardian|vanguard|front|defender|warrior|fighter|bruiser|captain").containsMatchIn(lc)
    val healClass = Regex("support|enchanter|healer|supporter|strategist|captain").containsMatchIn(lc)
}

class Objective(
    val id: String,
    val name: String,
    val spawn: Double,
    val respawn: Double,
    val until: Double?,
    val kind: String,
    val value: Double,
    val maxStacks: Int = 0,
    val soul: Double = 0.0,
    val major: Boolean = false,
)

class Structures(val lanes: Int, val perLane: Int, val base: Int, val tierNames: List<String>, val baseName: String, val coreName: String)

class MobaConfig(
    val laneEnd: Double,
    val avgLength: Double,
    val maxLength: Double,
    val gpm: Double,
    val comeback: Double,
    val respawnGrowth: Double,
    val lanes: List<Pair<String, List<String>>>,
    val junglers: List<String>,
    val objectives: List<Objective>,
    val structures: Structures,
    val denies: Boolean = false,
    val buyback: Boolean = false,
)

class TacticalConfig(
    val roundsToWin: Int,
    val half: Int,
    val otWinByTwo: Boolean,
    val otRounds: Int,
    val startMoney: Int,
    val winReward: Int,
    val lossRewards: IntArray,
    val killReward: Int,
    val plantReward: Int,
    val maxMoney: Int,
    val fullBuy: Int,
    val objectiveName: String,
    val sides: Pair<String, String>,
    val abilities: Boolean,
    val utilityWeight: Double,
    val defenderEdge: Double,
)

class DraftStep(val team: Int, val ban: Boolean)
class DraftRules(val mode: String, val steps: List<DraftStep>, val bansPerTeam: Int, val uniqueAcross: Boolean)

class SeriesFormat(val n: Int) {
    val label get() = "Bo$n"
    val wins get() = n / 2 + 1
}

class StatDef(val key: String, val label: String, val good: Boolean = true)

class MapDef(val id: String, val name: String)

class TitleDef(
    val id: String,
    val name: String,
    val short: String,
    val engine: Engine,
    val inputMkb: Boolean,
    val teamSize: Int,
    val minAge: Int,
    val roles: List<RoleDef>,
    val lineup: List<String>,
    val charLabel: String,
    val charClasses: List<String>,
    var characters: List<CharDef>,
    val maps: List<MapDef>,
    val draft: DraftRules,
    val formats: Triple<SeriesFormat, SeriesFormat, SeriesFormat>,
    val styles: List<String>,
    val specRelevance: Map<String, Double>,
    val weaponClasses: List<String>,
    val phaseWeights: Map<Phase, Double>,
    val moba: MobaConfig?,
    val tactical: TacticalConfig?,
    val stats: List<StatDef>,
    val baseSalary: Double,
    val prizeScale: Double,
) {
    val isMoba get() = engine == Engine.MOBA
    fun role(id: String): RoleDef = roles.firstOrNull { it.id == id } ?: roles[0]
    val lineupRoles: List<RoleDef> by lazy { lineup.map { role(it) } }
    val charIndex: Map<String, CharDef> by lazy { characters.associateBy { it.id } }
    val statIndex: Map<String, Int> by lazy { stats.withIndex().associate { it.value.key to it.index } }
    val roleWeights: Map<String, RoleWeights> by lazy { roles.associate { it.id to RoleWeights.build(this, it) } }
}

/**
 * Attribute weightings of a role in a title, per phase and overall: core attributes and title
 * specialist attributes (MOBA / shooter), as in the web version's ratings model.
 */
class RoleWeights(val overallCore: IntArray, val overallCoreW: DoubleArray, val overallSpec: IntArray, val overallSpecW: DoubleArray, val phaseCore: Array<Pair<IntArray, DoubleArray>>, val phaseSpec: Array<Pair<IntArray, DoubleArray>?>) {
    companion object {
        private val CORE_GROUP_BASE = mapOf("Mechanical" to 1.0, "Combat" to 1.0, "Tactical" to 0.95, "Mental" to 0.9, "Teamwork" to 0.8, "Knowledge" to 0.8, "Development" to 0.0)

        private fun split(w: Map<Int, Double>): Pair<IntArray, DoubleArray> {
            val keys = w.filterValues { it > 0 }.keys.sorted()
            val sum = keys.sumOf { w[it]!! }
            return keys.toIntArray() to DoubleArray(keys.size) { w[keys[it]]!! / sum }
        }

        fun build(t: TitleDef, r: RoleDef): RoleWeights {
            val core = HashMap<Int, Double>()
            for (a in Attrs.ALL) if (a.index < Attrs.CORE_COUNT) {
                val base = CORE_GROUP_BASE[a.group.name] ?: 0.0
                if (base > 0) core[a.index] = base * (r.emphasis[a.key] ?: 1.0)
            }
            core[Attrs.CONSISTENCY] = 0.6
            core[Attrs.STAMINA] = 0.3
            val spec = HashMap<Int, Double>()
            for (a in Attrs.ALL) if (a.index >= Attrs.CORE_COUNT) {
                val rel = t.specRelevance[a.key] ?: if ((a.group.name == "MOBA") == t.isMoba) 1.0 else 0.0
                if (rel > 0) spec[a.index] = rel * (r.emphasis[a.key] ?: 1.0)
            }
            val phaseCore = Array(Phase.entries.size) { pi ->
                val pa = PHASE_ATTRS[Phase.entries[pi]]!!
                split(pa.first.mapKeys { Attrs.idx(it.key) }.mapValues { (k, v) -> v * kotlin.math.sqrt(r.emphasis[Attrs.ALL[k].key] ?: 1.0) })
            }
            val phaseSpec = Array(Phase.entries.size) { pi ->
                val pa = PHASE_ATTRS[Phase.entries[pi]]!!
                val m = pa.second.mapKeys { Attrs.idx(it.key) }.mapValues { (k, v) -> v * (t.specRelevance[Attrs.ALL[k].key] ?: if ((Attrs.ALL[k].group.name == "MOBA") == t.isMoba) 1.0 else 0.0) * kotlin.math.sqrt(r.emphasis[Attrs.ALL[k].key] ?: 1.0) }
                if (m.values.sum() > 0.5) split(m) else null
            }
            val (oc, ocw) = split(core)
            val (os, osw) = split(spec)
            return RoleWeights(oc, ocw, os, osw, phaseCore, phaseSpec)
        }

        val PHASE_ATTRS: Map<Phase, Pair<Map<String, Double>, Map<String, Double>>> = mapOf(
            Phase.LANING to (mapOf("microControl" to 1.0, "spacing" to 1.2, "timing" to 0.8, "dueling" to 1.2, "comboExecution" to 0.8, "evasion" to 0.6, "accuracy" to 0.5, "matchupKnowledge" to 1.0, "discipline" to 0.5, "resourceDenial" to 0.4) to
                mapOf("lastHitting" to 1.2, "waveManagement" to 1.0, "laneTrading" to 1.3, "cooldownTracking" to 0.8, "trackingAim" to 0.6, "projectileLeading" to 0.6)),
            Phase.JUNGLE to (mapOf("mapAwareness" to 1.0, "tempoControl" to 1.2, "objectivePrioritisation" to 0.8, "anticipation" to 1.0, "timing" to 0.8, "opponentTracking" to 1.0, "pressureCreation" to 0.8) to
                mapOf("junglePathing" to 1.4, "ganking" to 1.3, "creepControl" to 1.0)),
            Phase.SKIRMISH to (mapOf("skirmishing" to 1.4, "dueling" to 0.8, "comboExecution" to 1.0, "reactionSpeed" to 0.8, "followUp" to 0.8, "targeting" to 0.8, "decisionMaking" to 0.6, "evasion" to 0.5) to
                mapOf("cooldownTracking" to 0.6, "trackingAim" to 0.5, "movementShooting" to 0.4, "projectileLeading" to 0.4)),
            Phase.TEAMFIGHT to (mapOf("teamfighting" to 1.5, "initiation" to 0.8, "peeling" to 0.8, "followUp" to 0.8, "damageOptimisation" to 1.0, "survivability" to 1.0, "crowdControl" to 0.8, "spacing" to 1.0, "targeting" to 1.0, "coordination" to 0.8, "composure" to 0.5) to
                mapOf("cooldownTracking" to 0.6, "trackingAim" to 0.6, "projectileLeading" to 0.5, "burstSprayControl" to 0.3)),
            Phase.MACRO to (mapOf("rotations" to 1.2, "objectivePrioritisation" to 1.0, "objectiveExecution" to 0.8, "tempoControl" to 1.0, "winConditionRecognition" to 1.0, "decisionMaking" to 1.0, "mapAwareness" to 0.8, "shotcalling" to 0.6, "communication" to 0.6, "riskAssessment" to 0.6, "resourceEfficiency" to 0.8, "spaceControl" to 0.6) to
                mapOf("waveManagement" to 0.8, "splitPushing" to 0.8, "structurePressure" to 1.0, "resetTiming" to 1.0)),
            Phase.VISION to (mapOf("informationControl" to 1.5, "mapAwareness" to 1.0, "opponentTracking" to 1.0, "anticipation" to 0.8, "spaceControl" to 0.8, "selflessness" to 0.5) to mapOf("soundAwareness" to 0.8, "angleClearing" to 0.4)),
            Phase.AIM to (mapOf("reactionSpeed" to 1.0, "accuracy" to 1.0, "microControl" to 0.4, "targeting" to 0.6, "dueling" to 0.6) to
                mapOf("flickAim" to 1.2, "trackingAim" to 0.9, "crosshairPlacement" to 1.3, "recoilControl" to 1.0, "burstSprayControl" to 1.0, "peekMechanics" to 0.8, "longRangePrecision" to 0.6, "weaponSwitching" to 0.4, "movementShooting" to 0.5, "projectileLeading" to 0.5)),
            Phase.ENTRY to (mapOf("initiation" to 1.2, "dueling" to 1.0, "movement" to 0.8, "reactionSpeed" to 0.6, "riskAssessment" to 0.4, "spaceControl" to 0.6, "pressureCreation" to 0.6) to mapOf("peekMechanics" to 1.0, "flickAim" to 0.8, "angleClearing" to 1.2, "movementShooting" to 0.6)),
            Phase.UTILITY to (mapOf("coordination" to 1.0, "timing" to 0.8, "informationControl" to 0.8, "spaceControl" to 1.0, "crowdControl" to 0.6, "comboExecution" to 0.5) to mapOf("utilityPlacement" to 1.5, "soundAwareness" to 0.3)),
            Phase.IGL to (mapOf("shotcalling" to 1.5, "decisionMaking" to 1.0, "leadership" to 1.0, "communication" to 1.0, "anticipation" to 0.8, "winConditionRecognition" to 0.8, "tempoControl" to 0.6, "preparation" to 0.6, "counterplay" to 0.6, "draftUnderstanding" to 0.4) to emptyMap()),
            Phase.CLUTCH to (mapOf("composure" to 1.5, "decisionMaking" to 1.0, "patience" to 0.8, "concentration" to 0.8, "mentalResilience" to 0.6, "riskAssessment" to 0.5) to mapOf("soundAwareness" to 0.8, "ammoDiscipline" to 0.4)),
            Phase.OBJECTIVE to (mapOf("objectiveExecution" to 1.3, "objectivePrioritisation" to 1.0, "spaceControl" to 1.0, "coordination" to 0.8, "timing" to 0.8, "tempoControl" to 0.6) to mapOf("structurePressure" to 0.6, "creepControl" to 0.5, "utilityPlacement" to 0.4)),
            Phase.SURVIVAL to (mapOf("survivability" to 1.2, "disengaging" to 1.0, "evasion" to 0.8, "riskAssessment" to 1.0, "patience" to 0.8, "spacing" to 0.6, "teamwork" to 0.6) to mapOf("movementShooting" to 0.3, "ammoDiscipline" to 0.3)),
            Phase.SUPPORT to (mapOf("peeling" to 1.3, "selflessness" to 1.2, "survivability" to 0.8, "crowdControl" to 0.8, "teamwork" to 1.0, "coordination" to 0.8, "communication" to 0.6) to mapOf("utilityPlacement" to 0.5)),
            Phase.MOVEMENT to (mapOf("movement" to 1.5, "evasion" to 1.0, "multitasking" to 0.8, "creativity" to 0.6, "reactionSpeed" to 0.5) to mapOf("movementShooting" to 1.2, "weaponSwitching" to 0.5)),
            Phase.ECONOMY to (mapOf("resourceEfficiency" to 1.5, "discipline" to 0.8, "decisionMaking" to 0.8, "patience" to 0.4) to mapOf("ammoDiscipline" to 0.6)),
        )
    }
}

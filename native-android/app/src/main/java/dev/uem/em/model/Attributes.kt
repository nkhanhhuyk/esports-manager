package dev.uem.em.model

/** The 86 player attributes (62 core + 10 MOBA + 14 shooter), as in the web version. */
enum class AttrGroup { Mechanical, Combat, Tactical, Mental, Teamwork, Knowledge, Development, MOBA, Shooter }
enum class Curve { MECH, COMBAT, MIND, KNOWLEDGE, PHYSICAL }

class AttrDef(val index: Int, val key: String, val name: String, val group: AttrGroup, val inputSensitivity: Double, val trainability: Double, val curve: Curve, val short: String)

object Attrs {
    private val RAW: List<Array<Any>> = listOf(
        arrayOf("microControl", "Micro Control", AttrGroup.Mechanical, 1.0, 0.8, Curve.MECH, "MIC"),
        arrayOf("reactionSpeed", "Reaction Speed", AttrGroup.Mechanical, 0.5, 0.4, Curve.MECH, "REA"),
        arrayOf("accuracy", "Accuracy", AttrGroup.Mechanical, 1.0, 0.8, Curve.MECH, "ACC"),
        arrayOf("evasion", "Evasion", AttrGroup.Mechanical, 0.8, 0.8, Curve.MECH, "EVA"),
        arrayOf("comboExecution", "Combo Execution", AttrGroup.Mechanical, 1.0, 0.9, Curve.MECH, "CMB"),
        arrayOf("movement", "Movement", AttrGroup.Mechanical, 1.0, 0.8, Curve.MECH, "MOV"),
        arrayOf("spacing", "Spacing", AttrGroup.Mechanical, 0.5, 0.9, Curve.COMBAT, "SPC"),
        arrayOf("timing", "Timing", AttrGroup.Mechanical, 0.3, 0.9, Curve.COMBAT, "TIM"),
        arrayOf("targeting", "Targeting", AttrGroup.Mechanical, 0.6, 0.9, Curve.COMBAT, "TGT"),
        arrayOf("multitasking", "Multitasking", AttrGroup.Mechanical, 0.6, 0.7, Curve.MECH, "MLT"),
        arrayOf("dueling", "Dueling", AttrGroup.Combat, 0.3, 0.9, Curve.COMBAT, "DUE"),
        arrayOf("skirmishing", "Skirmishing", AttrGroup.Combat, 0.2, 0.9, Curve.COMBAT, "SKR"),
        arrayOf("teamfighting", "Teamfighting", AttrGroup.Combat, 0.15, 0.9, Curve.COMBAT, "TFT"),
        arrayOf("initiation", "Initiation", AttrGroup.Combat, 0.15, 0.9, Curve.COMBAT, "INI"),
        arrayOf("disengaging", "Disengaging", AttrGroup.Combat, 0.15, 0.9, Curve.COMBAT, "DIS"),
        arrayOf("followUp", "Follow-up", AttrGroup.Combat, 0.1, 0.9, Curve.COMBAT, "FUP"),
        arrayOf("peeling", "Peeling", AttrGroup.Combat, 0.1, 0.9, Curve.COMBAT, "PEE"),
        arrayOf("damageOptimisation", "Damage Optimisation", AttrGroup.Combat, 0.2, 0.9, Curve.COMBAT, "DMG"),
        arrayOf("survivability", "Survivability", AttrGroup.Combat, 0.2, 0.9, Curve.COMBAT, "SUR"),
        arrayOf("crowdControl", "Crowd Control", AttrGroup.Combat, 0.2, 0.9, Curve.COMBAT, "CC"),
        arrayOf("mapAwareness", "Map Awareness", AttrGroup.Tactical, 0.0, 1.0, Curve.MIND, "MAP"),
        arrayOf("rotations", "Rotations", AttrGroup.Tactical, 0.0, 1.0, Curve.MIND, "ROT"),
        arrayOf("objectivePrioritisation", "Objective Prioritisation", AttrGroup.Tactical, 0.0, 1.0, Curve.MIND, "OBP"),
        arrayOf("objectiveExecution", "Objective Execution", AttrGroup.Tactical, 0.1, 1.0, Curve.MIND, "OBX"),
        arrayOf("tempoControl", "Tempo Control", AttrGroup.Tactical, 0.0, 1.0, Curve.MIND, "TMP"),
        arrayOf("resourceEfficiency", "Resource Efficiency", AttrGroup.Tactical, 0.0, 1.0, Curve.MIND, "REF"),
        arrayOf("resourceDenial", "Resource Denial", AttrGroup.Tactical, 0.1, 1.0, Curve.MIND, "RDN"),
        arrayOf("spaceControl", "Space Control", AttrGroup.Tactical, 0.0, 1.0, Curve.MIND, "SPA"),
        arrayOf("pressureCreation", "Pressure Creation", AttrGroup.Tactical, 0.0, 1.0, Curve.MIND, "PRS"),
        arrayOf("informationControl", "Information Control", AttrGroup.Tactical, 0.0, 1.0, Curve.MIND, "INF"),
        arrayOf("winConditionRecognition", "Win Condition Recognition", AttrGroup.Tactical, 0.0, 1.0, Curve.MIND, "WCR"),
        arrayOf("opponentTracking", "Opponent Tracking", AttrGroup.Tactical, 0.0, 1.0, Curve.MIND, "OPT"),
        arrayOf("decisionMaking", "Decision Making", AttrGroup.Mental, 0.0, 0.8, Curve.MIND, "DEC"),
        arrayOf("anticipation", "Anticipation", AttrGroup.Mental, 0.0, 0.8, Curve.MIND, "ANT"),
        arrayOf("composure", "Composure", AttrGroup.Mental, 0.0, 0.6, Curve.MIND, "COM"),
        arrayOf("concentration", "Concentration", AttrGroup.Mental, 0.0, 0.7, Curve.MIND, "CON"),
        arrayOf("adaptability", "Adaptability", AttrGroup.Mental, 0.0, 0.6, Curve.MIND, "ADA"),
        arrayOf("discipline", "Discipline", AttrGroup.Mental, 0.0, 0.7, Curve.MIND, "DSC"),
        arrayOf("creativity", "Creativity", AttrGroup.Mental, 0.0, 0.5, Curve.MIND, "CRE"),
        arrayOf("riskAssessment", "Risk Assessment", AttrGroup.Mental, 0.0, 0.8, Curve.MIND, "RSK"),
        arrayOf("mentalResilience", "Mental Resilience", AttrGroup.Mental, 0.0, 0.6, Curve.MIND, "RES"),
        arrayOf("patience", "Patience", AttrGroup.Mental, 0.0, 0.6, Curve.MIND, "PAT"),
        arrayOf("communication", "Communication", AttrGroup.Teamwork, 0.0, 0.9, Curve.MIND, "CMM"),
        arrayOf("shotcalling", "Shotcalling", AttrGroup.Teamwork, 0.0, 0.8, Curve.MIND, "SHC"),
        arrayOf("coordination", "Coordination", AttrGroup.Teamwork, 0.0, 1.0, Curve.MIND, "CRD"),
        arrayOf("teamwork", "Teamwork", AttrGroup.Teamwork, 0.0, 0.8, Curve.MIND, "TWK"),
        arrayOf("leadership", "Leadership", AttrGroup.Teamwork, 0.0, 0.5, Curve.MIND, "LDR"),
        arrayOf("selflessness", "Selflessness", AttrGroup.Teamwork, 0.0, 0.5, Curve.MIND, "SEL"),
        arrayOf("matchupKnowledge", "Matchup Knowledge", AttrGroup.Knowledge, 0.0, 1.1, Curve.KNOWLEDGE, "MUK"),
        arrayOf("draftUnderstanding", "Draft Understanding", AttrGroup.Knowledge, 0.0, 1.0, Curve.KNOWLEDGE, "DRF"),
        arrayOf("counterplay", "Counterplay", AttrGroup.Knowledge, 0.0, 1.0, Curve.KNOWLEDGE, "CTP"),
        arrayOf("metaUnderstanding", "Meta Understanding", AttrGroup.Knowledge, 0.0, 1.1, Curve.KNOWLEDGE, "MET"),
        arrayOf("patchAdaptation", "Patch Adaptation", AttrGroup.Knowledge, 0.0, 0.8, Curve.KNOWLEDGE, "PAD"),
        arrayOf("buildOptimisation", "Build Optimisation", AttrGroup.Knowledge, 0.0, 1.1, Curve.KNOWLEDGE, "BLD"),
        arrayOf("preparation", "Preparation", AttrGroup.Knowledge, 0.0, 0.9, Curve.KNOWLEDGE, "PRP"),
        arrayOf("learningSpeed", "Learning Speed", AttrGroup.Development, 0.0, 0.2, Curve.PHYSICAL, "LRN"),
        arrayOf("workEthic", "Work Ethic", AttrGroup.Development, 0.0, 0.3, Curve.PHYSICAL, "WRK"),
        arrayOf("coachability", "Coachability", AttrGroup.Development, 0.0, 0.3, Curve.PHYSICAL, "CCH"),
        arrayOf("professionalism", "Professionalism", AttrGroup.Development, 0.0, 0.3, Curve.PHYSICAL, "PRO"),
        arrayOf("consistency", "Consistency", AttrGroup.Development, 0.0, 0.5, Curve.PHYSICAL, "CNS"),
        arrayOf("stamina", "Stamina", AttrGroup.Development, 0.0, 0.5, Curve.PHYSICAL, "STA"),
        arrayOf("recovery", "Recovery", AttrGroup.Development, 0.0, 0.4, Curve.PHYSICAL, "RCV"),
        arrayOf("lastHitting", "Last Hitting", AttrGroup.MOBA, 0.7, 1.0, Curve.MECH, "LH"),
        arrayOf("waveManagement", "Wave Management", AttrGroup.MOBA, 0.0, 1.0, Curve.MIND, "WAV"),
        arrayOf("laneTrading", "Lane Trading", AttrGroup.MOBA, 0.4, 1.0, Curve.COMBAT, "LTR"),
        arrayOf("junglePathing", "Jungle Pathing", AttrGroup.MOBA, 0.0, 1.0, Curve.MIND, "JGP"),
        arrayOf("ganking", "Ganking", AttrGroup.MOBA, 0.2, 1.0, Curve.COMBAT, "GNK"),
        arrayOf("creepControl", "Creep/Neutral Control", AttrGroup.MOBA, 0.3, 1.0, Curve.MIND, "NEU"),
        arrayOf("splitPushing", "Split Pushing", AttrGroup.MOBA, 0.1, 1.0, Curve.MIND, "SPL"),
        arrayOf("cooldownTracking", "Cooldown Tracking", AttrGroup.MOBA, 0.0, 1.0, Curve.MIND, "CDT"),
        arrayOf("structurePressure", "Structure Pressure", AttrGroup.MOBA, 0.0, 1.0, Curve.MIND, "STR"),
        arrayOf("resetTiming", "Reset Timing", AttrGroup.MOBA, 0.0, 1.0, Curve.MIND, "RST"),
        arrayOf("flickAim", "Flick Aim", AttrGroup.Shooter, 1.0, 0.7, Curve.MECH, "FLK"),
        arrayOf("trackingAim", "Tracking Aim", AttrGroup.Shooter, 1.0, 0.8, Curve.MECH, "TRK"),
        arrayOf("crosshairPlacement", "Crosshair Placement", AttrGroup.Shooter, 0.6, 1.0, Curve.COMBAT, "XHP"),
        arrayOf("recoilControl", "Recoil Control", AttrGroup.Shooter, 1.0, 1.0, Curve.MECH, "RCL"),
        arrayOf("burstSprayControl", "Burst/Spray Control", AttrGroup.Shooter, 0.8, 1.0, Curve.MECH, "SPR"),
        arrayOf("projectileLeading", "Projectile Leading", AttrGroup.Shooter, 0.8, 0.9, Curve.MECH, "PRJ"),
        arrayOf("peekMechanics", "Peek Mechanics", AttrGroup.Shooter, 0.8, 1.0, Curve.COMBAT, "PEK"),
        arrayOf("movementShooting", "Movement Shooting", AttrGroup.Shooter, 1.0, 0.9, Curve.MECH, "MVS"),
        arrayOf("angleClearing", "Angle Clearing", AttrGroup.Shooter, 0.4, 1.0, Curve.COMBAT, "ANG"),
        arrayOf("utilityPlacement", "Utility Placement", AttrGroup.Shooter, 0.3, 1.1, Curve.KNOWLEDGE, "UTL"),
        arrayOf("soundAwareness", "Sound Awareness", AttrGroup.Shooter, 0.0, 1.0, Curve.MIND, "SND"),
        arrayOf("ammoDiscipline", "Ammo Discipline", AttrGroup.Shooter, 0.0, 1.0, Curve.MIND, "AMM"),
        arrayOf("longRangePrecision", "Long-range Precision", AttrGroup.Shooter, 0.8, 0.9, Curve.MECH, "LRP"),
        arrayOf("weaponSwitching", "Weapon Switching", AttrGroup.Shooter, 0.9, 1.0, Curve.MECH, "WSW"),
    )

    val ALL: List<AttrDef> = RAW.mapIndexed { i, r -> AttrDef(i, r[0] as String, r[1] as String, r[2] as AttrGroup, r[3] as Double, r[4] as Double, r[5] as Curve, r[6] as String) }
    const val COUNT = 86
    const val CORE_COUNT = 62
    private val byKey = ALL.associate { it.key to it.index }

    fun idx(key: String): Int = byKey[key] ?: error("Unknown attribute $key")
    fun group(g: AttrGroup): IntArray = ALL.filter { it.group == g }.map { it.index }.toIntArray()

    val SENS = DoubleArray(COUNT) { ALL[it].inputSensitivity }
    val CONSISTENCY = idx("consistency")
    val COMPOSURE = idx("composure")
    val LEARNING = idx("learningSpeed")
    val STAMINA = idx("stamina")
    val idxRecovery = idx("recovery")

    const val INPUT_SPAN = 0.35

    /** Mechanical attributes lose value when a player is unfamiliar with the title's input. */
    fun effective(attrs: DoubleArray, i: Int, inputProf: Double): Double = attrs[i] * (1 - SENS[i] * INPUT_SPAN * (1 - inputProf / 100))

    init {
        check(ALL.size == COUNT) { "attribute table has ${ALL.size} entries" }
    }

    /** Summary ratings shown on player pages. */
    fun summaries(a: DoubleArray): List<Pair<String, Double>> {
        fun avg(idx: IntArray) = if (idx.isEmpty()) 0.0 else idx.sumOf { a[it] } / idx.size
        val sense = intArrayOf(idx("mapAwareness"), idx("anticipation"), idx("decisionMaking"), idx("opponentTracking"), idx("winConditionRecognition"), idx("riskAssessment"), idx("informationControl"))
        return listOf(
            "Mechanics" to avg(group(AttrGroup.Mechanical)),
            "Combat" to avg(group(AttrGroup.Combat)),
            "Macro" to avg(group(AttrGroup.Tactical)),
            "Game sense" to avg(sense),
            "Mentality" to avg(group(AttrGroup.Mental)),
            "Teamplay" to avg(group(AttrGroup.Teamwork)),
            "Strategy" to avg(group(AttrGroup.Knowledge)),
            "MOBA" to avg(group(AttrGroup.MOBA)),
            "Shooter" to avg(group(AttrGroup.Shooter)),
        )
    }
}

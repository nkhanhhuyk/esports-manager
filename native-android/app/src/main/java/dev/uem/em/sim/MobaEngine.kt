package dev.uem.em.sim

import dev.uem.em.core.Rng
import dev.uem.em.titles.Objective
import dev.uem.em.titles.Phase
import dev.uem.em.titles.TitleDef
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One simulated game's outcome. Team index 0/1 is the series' team order. */
class GameOutcome(val winner: Int, val duration: Int, val score: IntArray, val book: StatBook)

/**
 * Positional, ability-aware MOBA simulation on the title's real map (1-second ticks).
 *
 * Ten heroes move over the map's walkability grid; minion waves push along the lanes and clash;
 * towers, base structures and the core have hit points and shoot; jungle camps and the title's
 * objectives (Turtle/Lord, Drakes/Baron, Tyrant/Overlord, Roshan…) spawn, are fought over and give
 * their real kinds of reward. Every hero casts its own kit (assets/abilities.json): damage, crowd
 * control, heals, shields, dashes, stealth and so on change health, position and status, under
 * cooldowns and mana. Teams decide (laning, ganks, objectives, sieges, defending) from their macro
 * rating; individual execution comes from each player's phase ratings. The game ends only when a
 * core is destroyed. Teams are given in map-side order (0 = blue / Radiant).
 *
 * Recording (Recorder) never draws random numbers, so watched and unwatched games are identical.
 */
object MobaEngine {
    /** Tuning counters (tests only): ganks per lane 0..2, hunts per lane 3..5. */
    @JvmField @Volatile var dbg: java.util.concurrent.atomic.AtomicIntegerArray? = null
    /** Title rules that differ between the five MOBAs (approximate values where marked in docs). */
    class Rules(
        val ms: Float, val firstWave: Int, val waveSize: Int, val recall: Int, val cap: Int, val capMin: Double, val vision: Float,
        val towerHp: Float, val coreHp: Float, val towerDps: Float, val campRespawn: Int, val ultLevels: IntArray, val smite: Boolean,
        val courier: Boolean, val ultCdScale: Float, val killMul: Double, val paceMul: Double, val skill: Float = 1f,
        /** Extra power advantage a team wants before committing to a fight (LoL teams fight less often). */
        val caution: Float = 0f,
    )

    val RULES = mapOf(
        "mlbb" to Rules(27f, 25, 4, 6, 15, 16.0, 85f, 2600f, 4200f, 260f, 70, intArrayOf(4, 8, 12), true, false, 1f, 1.0, 1.35, 1f, 0.05f),
        "wildrift" to Rules(26f, 30, 5, 5, 15, 17.0, 80f, 2900f, 4400f, 270f, 90, intArrayOf(5, 9, 13), true, false, 1f, 1.0, 1.35, 1f, 0.05f),
        "hok" to Rules(27f, 30, 4, 6, 15, 16.0, 85f, 2600f, 4200f, 260f, 75, intArrayOf(4, 8, 12), true, false, 1f, 1.1, 1.55, 1f, 0.12f),
        "lol" to Rules(22f, 65, 6, 8, 18, 34.0, 80f, 4200f, 5400f, 300f, 120, intArrayOf(6, 11, 16), true, false, 1f, 0.7, 1.7, 1f, 0.55f),
        "dota2" to Rules(20f, 0, 4, 0, 30, 50.0, 100f, 4400f, 6000f, 290f, 60, intArrayOf(6, 12, 18), false, true, 1f, 1.4, 1.15, 2.3f),
    )

    /** Section timings for profiling (off unless a test turns it on; single-threaded use only). */
    @JvmField var profile = false
    val prof = LongArray(8)
    val PROF_NAMES = listOf("waves", "objectives spawn", "plan", "vision", "heroes", "structures", "objectives", "fights")

    /** Match rules for the engine: coach comms allowed, and each side's head coach (0..100). */
    class Opts(val coachComms: Boolean = false, val coachQ: DoubleArray = doubleArrayOf(50.0, 50.0))

    fun simulate(t: TitleDef, teams: List<TeamCtx>, comp: Array<CompEval>, rng: Rng, patch: Patch? = null, rec: Recorder? = null, opts: Opts = Opts()): GameOutcome =
        MobaSim(t, teams, comp, rng, patch, rec, opts).run()

    /** Map lane id for a title lane index (Dota 2 safe lanes are on opposite sides). */
    fun laneId(t: TitleDef, engineLane: Int, side: Int): String = when {
        engineLane == 1 -> "mid"
        t.id == "dota2" -> if ((engineLane == 0) == (side == 0)) "bot" else "top"
        engineLane == 0 -> "top"
        else -> "bot"
    }

    fun packPos(x: Float, y: Float) = (x.roundToInt().coerceIn(0, 1023) shl 10) or y.roundToInt().coerceIn(0, 1023)
    fun unpackX(p: Int) = ((p shr 10) and 1023).toFloat()
    fun unpackY(p: Int) = (p and 1023).toFloat()

    /** A position sample of one hero: x, y, HP %, and flags (1 dead, 2 crowd-controlled, 4 recalling, 8 stealthed). */
    fun packUnit(x: Float, y: Float, hpPct: Int, flags: Int) = (x.roundToInt().coerceIn(0, 1023)) or (y.roundToInt().coerceIn(0, 1023) shl 10) or (hpPct.coerceIn(0, 100) shl 20) or (flags shl 27)
    fun unitX(v: Int) = (v and 1023).toFloat()
    fun unitY(v: Int) = ((v shr 10) and 1023).toFloat()
    fun unitHp(v: Int) = (v shr 20) and 127
    fun unitFlags(v: Int) = (v shr 27) and 15
    /** A lane-wave sample: front (0..1000 along the lane from blue's base), minion count, super minions. */
    fun packWave(front: Float, count: Int, sup: Boolean) = (front * 1000).roundToInt().coerceIn(0, 1000) or (count.coerceIn(0, 63) shl 10) or (if (sup) 1 shl 16 else 0)
    const val SAMPLE_STEP = 2

    /** Tuning aid: called once a minute with a summary of every hero (null in the game). */
    @Volatile var debug: ((String) -> Unit)? = null
}

private class MobaSim(val t: TitleDef, val teams: List<TeamCtx>, val comp: Array<CompEval>, val rng: Rng, val patch: Patch?, val rec: Recorder?, val opts: MobaEngine.Opts = MobaEngine.Opts()) {
    /** Coach comms: three 45-second calls per game when the rules allow them. */
    val commsLeft = IntArray(2) { if (opts.coachComms) 3 else 0 }
    val commsUntil = IntArray(2)
    val commsMul = FloatArray(2) { 1.03f + 0.05f * (opts.coachQ.getOrElse(it) { 50.0 } / 100).toFloat() }
    val cfg = t.moba!!
    val R = MobaEngine.RULES[t.id] ?: MobaEngine.RULES["lol"]!!
    val map = MobaMap.of(t.id)
    val book = StatBook(teams, t)
    val K = book.idx("k"); val D = book.idx("d"); val A = book.idx("a"); val GOLD = book.idx("gold"); val CS = book.idx("cs"); val DMG = book.idx("dmg")
    val TAKEN = book.idx("taken"); val HEAL = book.idx("heal"); val VISION = book.idx("vision"); val OBJ = book.idx("obj"); val TOWERS = book.idx("towers"); val DN = book.idx("dn"); val BB = book.idx("bb")
    val ev = rec?.events
    /** Fight pacing: hero damage is scaled so that fights last several seconds, not one. */
    val DMG_PACE = 0.5f * R.killMul.toFloat()
    /** Simulation step in seconds (cooldowns, crowd control and timers stay in exact seconds). */
    val DT = 2
    val DTF = DT.toFloat()
    var tick = 0
    var nextWave = 0

    // ------------------------------------------------------------------------------------ heroes
    inner class Hero(val side: Int, val idx: Int, val pc: PlayerCtx) {
        val gi = side * 5 + idx
        val kit = Abilities.kit(pc.charId, pc.char)
        val acts = kit.actives
        val cdReady = FloatArray(4)
        val lane: Int = cfg.lanes.indexOfFirst { pc.roleId in it.second } // title lane index, -1 jungle/roam
        val jungler = pc.roleId in cfg.junglers && (lane < 0 || t.id == "dota2")
        val duoSecond = lane >= 0 && cfg.lanes[lane].second.size > 1 && cfg.lanes[lane].second.indexOf(pc.roleId) == 1
        val laneMap: String = if (lane >= 0) MobaEngine.laneId(t, lane, side) else "mid"
        val cls = (pc.char?.cls ?: "").lowercase()
        val tank = Regex("tank|guardian").containsMatchIn(cls)
        val marksman = Regex("marksman|sniper|carry").containsMatchIn(cls) || pc.role.isCarry
        val mage = Regex("mage|nuker").containsMatchIn(cls)
        val assassin = Regex("assassin").containsMatchIn(cls)
        val supportCls = Regex("support|enchanter|healer").containsMatchIn(cls) || pc.role.isSupport
        val range = kit.basicRange.coerceIn(10f, 50f)
        val meta = 1f + ((patch?.charMeta?.get(pc.charId ?: "") ?: 0.0) * 0.008).toFloat()
        fun ph(p: Phase) = pc.phase[p.ordinal].toFloat()
        fun z(p: Phase) = ((ph(p) - 68f) / 14f * R.skill).coerceIn(-3f, 3f)
        // Execution from the player's ratings (each mechanic reads one phase).
        val aim = 0.72f + 0.10f * z(Phase.SKIRMISH)
        val fightIq = 1f + 0.08f * z(Phase.TEAMFIGHT)
        var laning = 0.72f + 0.11f * z(Phase.LANING)
        /** Matchup edge against each enemy hero (index gi): damage multiplier from counters on this patch. */
        val vs = FloatArray(10) { 1f }
        /** Mechanical execution: damage actually landed from attacks and combos. */
        val execution = 1f + 0.07f * z(Phase.SKIRMISH) + 0.04f * z(Phase.TEAMFIGHT)
        val clutch = z(Phase.CLUTCH)
        val jungleSkill = 1f + 0.08f * z(Phase.JUNGLE)
        val visionSkill = 1f + 0.12f * z(Phase.VISION)
        val econ = 1f + 0.04f * z(Phase.ECONOMY)
        val survive = 0.04f * z(Phase.SURVIVAL)

        var x = 0f; var y = 0f
        var hp = 1f; var maxHp = 1f
        var mana = 1f; var maxMana = 1f
        var gold = 0f; var spent = 0f; var earned = 0f
        var items = 0
        var xp = 0.0
        var level = 1
        var deadUntil = 0
        var stunUntil = 0f; var rootUntil = 0f; var silenceUntil = 0f; var slowUntil = 0f; var slowPct = 0f
        var shield = 0f; var shieldUntil = 0f; var stealthUntil = 0f; var immuneUntil = 0f; var reduceUntil = 0f; var reducePct = 0f
        var asUntil = 0f; var asPct = 0f; var msUntil = 0f; var msPct = 0f; var revealUntil = 0f
        var dotDps = 0f; var dotUntil = 0f; var dotBy = -1
        var flashReady = 0f; var smiteReady = 0f; var bbReady = 0f; var bkbReady = 0f; var tpReady = 0f
        var recallUntil = 0; var lastCombat = -99; var lastHitBy = -1; var engagedAt = 0
        val dmgBy = IntArray(10) { -99 } // last time each enemy (gi) damaged or controlled this hero
        var targetX = 0f; var targetY = 0f
        var task = 0 // 0 lane, 1 jungle, 2 group, 3 objective, 4 recall, 5 roam/gank
        /** A collapse on an isolated enemy (after the laning phase): who and until when. */
        var huntTarget = -1; var huntUntil = 0
        var focus = -1
        var camp = -1
        var aegis = false
        var visible = false
        var kills = 0; var deaths = 0
        fun alive(now: Int) = deadUntil <= now
        fun cc(now: Float) = now < stunUntil
        fun hpPct() = hp / maxHp
        var atkV = 0f; var asV = 0f; var apV = 0f; var resV = 0f; var powV = 0f
        /** Recompute combat stats (once per tick). */
        /** Item count with diminishing returns (a gold lead helps, but less and less). */
        private var ieItems = -1
        private var ieV = 0f
        val itemsEff: Float get() { if (items != ieItems) { ieItems = items; ieV = Math.pow(items.toDouble(), 0.8).toFloat() }; return ieV }
        fun refresh() {
            resV = ((if (tank) 0.32f else if (assassin || mage || marksman) 0.14f else 0.22f) + 0.025f * items + 0.006f * level).coerceAtMost(0.62f)
            atkV = atkCalc(); asV = asCalc(); apV = apCalc()
            // How strong a hero looks in a fight: its damage, health and level; durability counts, but a
            // tank is not a damage threat twice its size (enemies kept away from tanky front-liners).
            powV = (atkV * asV * 1.4f + apV * 55f) * (0.35f + 0.65f * hpPct()) * (1f + 0.25f * level / R.cap) / (1f - resV * 0.55f)
        }
        fun power() = powV
        fun resist() = resV
        fun atk() = atkV
        fun attackSpeed() = asV
        fun apPower() = apV
        fun atkCalc(): Float = ((if (marksman) 62f else if (assassin) 60f else if (tank) 42f else if (mage || supportCls) 38f else 56f) * (1f + 0.065f * (level - 1)) +
            itemsEff * (if (marksman) 34f else if (assassin) 26f else if (tank) 8f else if (mage || supportCls) 5f else 22f)) * meta * buffDmg(side) * teamForm[side]
        fun asCalc(): Float = ((if (marksman) 0.85f else 0.72f) + items * (if (marksman) 0.07f else 0.025f)) * (1f + (if (asUntil > nowF) asPct else 0f))
        fun apCalc(): Float = (1f + 0.07f * (level - 1)) * (1f + itemsEff * (if (mage) 0.34f else if (assassin) 0.24f else if (supportCls) 0.14f else if (tank) 0.08f else if (marksman) 0.1f else 0.15f)) * meta * buffDmg(side) * teamForm[side]
        fun baseHp(): Float = ((if (tank) 1050f else if (assassin) 720f else if (mage) 660f else if (marksman) 650f else if (supportCls) 700f else 880f) * (1f + 0.09f * (level - 1)) +
            items * (if (tank) 440f else if (assassin) 150f else if (mage) 130f else if (marksman) 120f else if (supportCls) 230f else 300f)) * meta
        fun speed(): Float {
            var s = R.ms * (if (slowUntil > nowF) 1f - slowPct else 1f) * (if (msUntil > nowF) 1f + msPct else 1f)
            // Ganks and collapses come out of the fog: the hunters close the gap a little faster.
            if (task == 5) s *= 1.12f
            if (rootUntil > nowF || stunUntil > nowF) s = 0f
            return s
        }
        fun rank(slot: Int): Int {
            val a = acts.getOrNull(slot) ?: return 0
            if (a.ult) return R.ultLevels.count { level >= it }
            val maxR = a.cd.size.coerceIn(1, 6)
            return ((level + (2 - slot)) * maxR / (R.cap * 0.75f)).toInt().coerceIn(if (level >= slot + 1) 1 else 0, maxR)
        }
    }

    val heroes: List<Hero> = (0..1).flatMap { s -> teams[s].players.mapIndexed { i, pc -> Hero(s, i, pc) } }.also { hs ->
        // Counters on this patch: a small damage edge in every fight, a laning edge against the lane opponent.
        if (patch != null) for (h in hs) for (e in hs) if (e.side != h.side) {
            val sc = Counters.score(t, patch, h.pc.charId ?: "", e.pc.charId ?: "").toFloat()
            h.vs[e.gi] = 1f + 0.07f * sc
            if (h.lane >= 0 && e.lane >= 0 && e.laneMap == h.laneMap && e.duoSecond == h.duoSecond) h.laning *= 1f + 0.06f * sc // same physical lane
        }
    }
    /** Box-score line of each hero (index gi), for direct stat updates. */
    val lineOf = arrayOfNulls<DoubleArray>(10).also { a -> for (h in heroes) a[h.gi] = book.lines.first { it.playerId == h.pc.p.id }.s }
    fun stat(h: Hero, k: Int, v: Double) { if (k >= 0) lineOf[h.gi]!![k] += v }
    val laneArr = Array(3) { map.lanes[map.laneIds[it]]!! }
    val bySide = arrayOf(heroes.filter { it.side == 0 }, heroes.filter { it.side == 1 })
    var now = 0
    var nowF = 0f
    var winner = -1

    // --------------------------------------------------------------------------- team state / buffs
    val stacks = Array(2) { HashMap<String, Int>() }
    val soul = FloatArray(2)
    val buffUntil = IntArray(2); val buffVal = FloatArray(2)
    val pushUntil = IntArray(2); val siegeUntil = IntArray(2)
    val executeUntil = IntArray(2)
    val kills = IntArray(2)
    val teamGold = FloatArray(2)
    val sideBuff = floatArrayOf(1f, 1f)
    /** How sharp each team plays this minute (communication, focus): variance that lets games turn. */
    val teamForm = floatArrayOf(1f, 1f)
    fun buffDmg(side: Int) = sideBuff[side]
    fun computeBuff(side: Int): Float {
        var b = 1f + soul[side] + (if (buffUntil[side] > now) buffVal[side] else 0f) + (if (executeUntil[side] > now) 0.2f else 0f)
        for ((id, n) in stacks[side]) cfg.objectives.firstOrNull { it.id == id }?.let { b += (n * it.value).toFloat() }
        val c = comp[side]
        val phaseT = (now / 60.0 / cfg.avgLength).coerceIn(0.0, 1.5)
        b *= (1 + ((c.prof("early") - 5) * (1 - phaseT) + (c.prof("scaling", "farm") - 5) * phaseT) * 0.01).toFloat()
        return b
    }

    // ------------------------------------------------------------------------------ structures
    inner class Struct(val s: MobaMap.Struct) {
        val laneIdx = if (s.lane != null) map.laneIds.indexOf(s.lane) else -1
        val f: Float = if (s.lane != null) map.lanes[s.lane]!!.fractionOf(s.x, s.y) else -1f
        var maxHp = when (s.kind) { "core" -> R.coreHp; "inhibitor", "barracks" -> R.towerHp * 0.8f; "base-tower" -> R.towerHp * 1.05f; else -> R.towerHp * (0.9f + 0.1f * s.tier) }
        var hp = maxHp
        var dead = false
        var deadAt = -1
        var lastPct = 100
        // The Nexus (LoL, Wild Rift) and the Ancient (Dota 2) do not attack; MLBB's and HoK's crystals do.
        val shoots = s.kind == "tower" || s.kind == "base-tower" || (s.kind == "core" && (t.id == "mlbb" || t.id == "hok"))
    }
    val structs = map.structs.map { Struct(it) }
    val laneStructs = Array(2) { side -> Array(3) { li -> structs.filter { it.s.team == side && it.laneIdx == li }.sortedBy { it.s.tier } } }
    val baseStructs = Array(2) { side -> structs.filter { it.s.team == side && it.s.lane == null && it.s.kind != "core" } }
    val cores = Array(2) { side -> structs.first { it.s.team == side && it.s.kind == "core" } }
    fun frontStruct(side: Int, li: Int) = laneStructs[side][li].firstOrNull { !it.dead }
    fun laneBroken(side: Int, li: Int) = laneStructs[side][li].all { it.dead }
    /** Standing structures that can be attacked (and that shoot): recomputed when one falls. */
    var active: List<Struct> = emptyList()
    fun refreshActive() { active = structs.filter { !it.dead && exposed(it) } }
    fun exposed(st: Struct): Boolean {
        val side = st.s.team
        return when {
            st.s.kind == "core" -> !st.dead && baseStructs[side].all { it.dead } && (0..2).any { laneBroken(side, it) }
            st.s.lane == null -> (0..2).any { laneBroken(side, it) }
            else -> frontStruct(side, st.laneIdx) === st
        }
    }

    // --------------------------------------------------------------------------------- waves
    /** A minion packet walking a lane: position (0 = blue base, 1 = red base), minions, super minions. */
    class Packet(var f: Float, var n: Float, val sup: Boolean)
    val packets = Array(3) { Array(2) { ArrayList<Packet>() } }
    val minionHit = FloatArray(1) // reused
    val minionSpeed = 0.022f * (1000f / (map.lanes["mid"]?.length ?: 1000f)).coerceIn(0.6f, 1.6f) * (R.ms / 22f)
    /** Front packet per lane/side, cached once per tick after the waves move (index li * 2 + side). */
    val frontCache = arrayOfNulls<Packet>(6)
    fun cacheFronts() { for (li in 0..2) for (side in 0..1) frontCache[li * 2 + side] = findFront(li, side) }
    fun frontPacket(li: Int, side: Int): Packet? = frontCache[li * 2 + side]
    fun findFront(li: Int, side: Int): Packet? {
        val l = packets[li][side]
        var best: Packet? = null
        for (i in l.indices) { val p = l[i]; if (best == null || (if (side == 0) p.f > best.f else p.f < best.f)) best = p }
        return best
    }
    /** Where this side's lane presence is: its foremost minions, else its first standing tower. */
    fun laneFront(li: Int, side: Int): Float {
        frontPacket(li, side)?.let { return it.f }
        val st = frontStruct(side, li)
        return if (st != null) st.f + (if (side == 0) 0.04f else -0.04f) else if (side == 0) 0.06f else 0.94f
    }

    // ---------------------------------------------------------------------------- objectives
    /**
     * One objective instance at a pit. An objective with its own pits on each side (Dota 2 Wisdom
     * shrines, Tormentors) has one instance per pit; one that moves (Roshan between its two pits
     * with day and night) has one instance whose pit changes at each spawn.
     */
    inner class Obj(val o: Objective, val idx: Int, val k: Int, val pits: List<MobaMap.Pit>) {
        var pit = pits.firstOrNull() ?: map.pits.firstOrNull()
        var x = pit?.x ?: 500f; var y = pit?.y ?: 500f
        fun place() { if (pits.size > 1) { pit = pits[(now / 300) % pits.size]; x = pit!!.x; y = pit!!.y } }
        var alive = false
        var next = (o.spawn * 60 * (patch?.objRespawn(o.id)?.let { 1.0 } ?: 1.0)).toInt()
        val maxHp = when (o.kind) { "aegis" -> 9000f; "push" -> if (o.major) 11000f else 6000f; "execute" -> 12000f; "stack" -> 5200f; "siege" -> 3500f; "xp" -> 1f; else -> 4800f } * (1f + o.spawn.toFloat() / 60f)
        var hp = maxHp
        val dmgBy = FloatArray(2)
        val capture = o.kind == "xp"
    }
    val objs: List<Obj> = ArrayList<Obj>().also { list ->
        cfg.objectives.forEachIndexed { i, o ->
            val pits = map.pits.filter { o.id in it.objectives }
            val own = pits.filter { it.objectives.size == 1 }
            if (own.size >= 2) for (pt in own) list.add(Obj(o, i, list.size, listOf(pt)))
            else list.add(Obj(o, i, list.size, pits))
        }
    }

    // ---------------------------------------------------------------------------------- camps
    val campNext = IntArray(map.camps.size) { if (t.id == "dota2") 60 else 90 }
    val campHp = FloatArray(map.camps.size)
    val wards = ArrayList<FloatArray>() // x, y, side, until

    // ---------------------------------------------------------------------------- recording
    fun ev(e: Ev) { ev?.add(e) }
    fun locCode(x: Float, y: Float): Int {
        for (o in objs) if (hypot(o.x - x, o.y - y) < 70f) return 10 + o.idx
        var best = 3; var bd = 55f
        for ((i, lid) in map.laneIds.withIndex()) {
            val l = map.lanes[lid] ?: continue
            val f = l.fractionOf(x, y)
            val d = hypot(l.x(f) - x, l.y(f) - y)
            if (d < bd) { bd = d; best = i }
        }
        return best
    }
    fun sample() {
        val r = rec ?: return
        for (h in heroes) {
            val flags = (if (!h.alive(now)) 1 else 0) or (if (h.cc(nowF) || h.rootUntil > nowF) 2 else 0) or (if (h.recallUntil > now) 4 else 0) or (if (h.stealthUntil > nowF) 8 else 0)
            r.pos.add(MobaEngine.packUnit(h.x, h.y, (h.hpPct() * 100).roundToInt(), flags))
        }
        for (li in 0..2) for (side in 0..1) {
            val p = frontPacket(li, side)
            val n = packets[li][side].sumOf { it.n.toDouble() }.toInt()
            r.pos.add(MobaEngine.packWave(p?.f ?: (if (side == 0) 0f else 1f), n, packets[li][side].any { it.sup }))
        }
    }
    fun snapshot() {
        val r = rec ?: return
        val v = IntArray(heroes.size * 6)
        var k = 0
        for (h in heroes) {
            v[k++] = h.earned.roundToInt()
            v[k++] = book.value(h.pc.p.id, CS).roundToInt()
            v[k++] = h.level
            v[k++] = h.deadUntil
            v[k++] = (h.spent / 100).roundToInt()
            v[k++] = h.acts.indexOfFirst { it.ult }.let { if (it < 0) 0 else max(0f, h.cdReady[it] - nowF).roundToInt() }
        }
        r.snapshot(now, v)
    }

    // ------------------------------------------------------------------------------- helpers
    val tmp = FloatArray(2)
    fun dist(a: Hero, b: Hero) = hypot(a.x - b.x, a.y - b.y)
    fun fountain(side: Int) = map.fountains[side]
    fun laneXY(lane: String, f: Float, out: FloatArray) { val l = laneArr[laneIndex(lane)]; out[0] = l.x(f); out[1] = l.y(f) }
    fun laneIndex(lane: String) = when (lane) { "top" -> 0; "mid" -> 1; else -> 2 }
    fun gpmPerSec() = (cfg.gpm / 60.0).toFloat()
    /** Siege escalation in very long games (stalemates end): minions hit structures harder. */
    fun overtime(): Float = (1f + ((now / 60f - cfg.avgLength.toFloat() * 1.4f) / 5f).coerceAtLeast(0f)).coerceAtMost(4f)
    fun addGold(h: Hero, g: Float) { val v = g * h.econ; h.gold += v; h.earned += v }
    /** Average level per side (refreshed each step) for catch-up experience. */
    val avgLevel = DoubleArray(2) { 1.0 }
    fun addXp(h: Hero, minutes: Double) {
        if (!h.alive(now)) return
        h.xp += minutes * (1.0 + 0.15 * (avgLevel[1 - h.side] - h.level).coerceIn(0.0, 4.0))
        val nl = (1 + (R.cap - 1) * Math.pow(min(1.0, h.xp / R.capMin), 0.72)).toInt().coerceIn(1, R.cap)
        if (nl > h.level) {
            val frac = h.hpPct()
            h.level = nl
            h.maxHp = h.baseHp(); h.hp = h.maxHp * frac
            h.maxMana = 300f + 40f * h.level
        }
    }

    // --------------------------------------------------------------------------- damage & death
    fun damage(src: Hero?, srcSide: Int, target: Hero, amount0: Float, type: Int) {
        if (!target.alive(now) || target.immuneUntil > nowF && type != 2) return
        var amount = amount0
        if (src != null) { amount *= src.vs[target.gi]; if (commsUntil[src.side] > now) amount *= commsMul[src.side] }
        if (type != 2) amount *= 1f - target.resist()
        if (target.reduceUntil > nowF) amount *= 1f - target.reducePct
        if (src != null && executeUntil[srcSide] > now && target.hpPct() < 0.2f) amount = target.hp + target.shield + 1f
        if (target.shield > 0f && target.shieldUntil > nowF) {
            val absorbed = min(target.shield, amount)
            target.shield -= absorbed; amount -= absorbed
        }
        if (amount <= 0f) return
        target.hp -= amount
        target.lastCombat = now
        if (src != null) {
            // Attacking breaks stealth and reveals the attacker briefly.
            if (src.stealthUntil > nowF) src.stealthUntil = nowF
            src.revealUntil = max(src.revealUntil, nowF + 2f)
            if (!src.visible) src.visible = true
            target.dmgBy[src.gi] = now
            target.lastHitBy = src.gi
            src.lastCombat = now
            stat(src, DMG, amount.toDouble())
        } else target.lastHitBy = -1
        stat(target, TAKEN, amount.toDouble())
        if (target.hp <= 0f) die(target, src)
    }

    fun heal(src: Hero, target: Hero, amount: Float) {
        if (!target.alive(now)) return
        val v = min(amount, target.maxHp - target.hp)
        if (v <= 0f) return
        target.hp += v
        stat(src, HEAL, v.toDouble())
    }

    fun respawnTime(): Int {
        val m = now / 60.0
        return when (t.id) {
            "lol" -> min(75.0, 10 + m * 1.3)
            "dota2" -> min(100.0, 8 + m * 1.7)
            else -> min(55.0, 6 + m * 1.45)
        }.toInt()
    }

    fun die(v: Hero, src: Hero?) {
        if (v.aegis) {
            v.aegis = false
            v.hp = v.maxHp * 0.6f; v.shield = 0f
            ev(Ev(now, "aegis", v.side, v.pc.p.id))
            return
        }
        v.hp = 0f
        v.deaths++
        val respawn = respawnTime()
        v.deadUntil = now + respawn
        v.recallUntil = 0
        stat(v, D, 1.0)
        val ks = 1 - v.side
        // The last enemy hero to hurt the victim within 10 s gets the kill; otherwise it is an execution.
        val killer = src ?: heroes.firstOrNull { it.gi == v.lastHitBy && it.side == ks && v.dmgBy[it.gi] >= now - 10 }
        if (killer == null) {
            var anyHero = false
            for (h in bySide[ks]) if (v.dmgBy[h.gi] >= now - 10) anyHero = true
            if (!anyHero) {
                if (ev != null) ev(Ev(now, "kill", -1, -1, v.pc.p.id, locCode(v.x, v.y), n = v.deadUntil))
                return
            }
        }
        var mask = 0
        val assisters = ArrayList<Hero>()
        for (h in bySide[ks]) if (h !== killer && v.dmgBy[h.gi] >= now - 10) { assisters.add(h); mask = mask or (1 shl h.idx) }
        // Healers / shielders of the killer's team near the fight assist too.
        for (h in bySide[ks]) if (h !== killer && h !in assisters && h.alive(now) && h.supportCls && hypot(h.x - v.x, h.y - v.y) < 70f && h.lastCombat >= now - 6) { assisters.add(h); mask = mask or (1 shl h.idx) }
        kills[ks]++
        if (killer == null) { // finished by a tower or monster after enemy heroes hurt him: the best assister scores
            val credit = assisters.maxByOrNull { v.dmgBy[it.gi] }
            if (credit != null) { assisters.remove(credit); mask = mask and (1 shl credit.idx).inv(); dieCredit(v, credit, assisters, mask, ks); return }
        }
        val bounty = (gpmPerSec() * 60f * 0.42f) * (1f + (v.kills - v.deaths).coerceIn(0, 6) * 0.12f) * (if (teamGold[ks] < teamGold[v.side]) 1.2f else 1f)
        if (killer != null) {
            stat(killer, K, 1.0)
            killer.kills++
            addGold(killer, bounty)
            addXp(killer, 0.35)
        }
        for (h in assisters) { stat(h, A, 1.0); addGold(h, bounty * 0.35f / max(1, assisters.size)); addXp(h, 0.18) }
        if (ev != null) ev(Ev(now, "kill", ks, killer?.pc?.p?.id ?: -1, v.pc.p.id, locCode(v.x, v.y), m = mask, n = v.deadUntil))
        fightKill(ks, v.x, v.y)
    }

    private fun dieCredit(v: Hero, killer: Hero, assisters: List<Hero>, mask: Int, ks: Int) {
        val bounty = (gpmPerSec() * 60f * 0.42f) * (1f + (v.kills - v.deaths).coerceIn(0, 6) * 0.12f)
        stat(killer, K, 1.0); killer.kills++; addGold(killer, bounty); addXp(killer, 0.35)
        for (h in assisters) { stat(h, A, 1.0); addGold(h, bounty * 0.35f / max(1, assisters.size)); addXp(h, 0.18) }
        if (ev != null) ev(Ev(now, "kill", ks, killer.pc.p.id, v.pc.p.id, locCode(v.x, v.y), m = mask, n = v.deadUntil))
        fightKill(ks, v.x, v.y)
    }

    // ------------------------------------------------------------------------------ abilities
    fun ready(h: Hero, slot: Int): Boolean {
        val a = h.acts.getOrNull(slot) ?: return false
        if (h.rank(slot) <= 0) return false
        if (h.cdReady[slot] > nowF) return false
        if (h.silenceUntil > nowF || h.cc(nowF)) return false
        if (a.costAt(h.rank(slot)) * 0.6f > h.mana) return false
        return true
    }

    /** Use an ability on a target (enemy, ally or self). Returns true if cast. */
    fun cast(h: Hero, slot: Int, target: Hero?, allyTarget: Hero? = null): Boolean {
        val a = h.acts.getOrNull(slot) ?: return false
        if (!ready(h, slot)) return false
        val rank = h.rank(slot)
        val tgt = target ?: allyTarget
        val reach = if (a.target == Abilities.T_GLOBAL) 2000f else a.range.coerceIn(15f, 140f) + h.range * 0.2f
        if (tgt != null && a.target != Abilities.T_SELF && hypot(tgt.x - h.x, tgt.y - h.y) > reach + 6f) return false
        h.cdReady[slot] = nowF + a.cdAt(rank) * (if (a.ult) R.ultCdScale else 1f) * cdMult(h)
        h.mana -= a.costAt(rank) * 0.6f
        var mask = 0
        val power = h.apPower()
        val std = (if (a.ult) 110f + 60f * rank else 38f + 12f * rank) * DMG_PACE
        var hitAny = false
        // Who the ability lands on.
        val victims = ArrayList<Hero>(5)
        val enemyFx = a.fx.any { !it.ally && (it.k == Abilities.DMG || it.k == Abilities.DOT || Abilities.hard(it.k) || it.k == Abilities.ROOT || it.k == Abilities.SLOW || it.k == Abilities.SILENCE || it.k == Abilities.EXECUTE || it.k == Abilities.BLIND || it.k == Abilities.TERRAIN) }
        if (enemyFx && target != null) {
            when (a.target) {
                Abilities.T_UNIT, Abilities.T_GLOBAL -> victims.add(target)
                Abilities.T_LINE -> {
                    // Skillshot: aim against the target's dodging; blocked by walls.
                    val chance = (h.aim + (if (target.cc(nowF) || target.rootUntil > nowF) 0.35f else 0f) - 0.05f * ((target.pc.phase[Phase.MOVEMENT.ordinal].toFloat() - 68f) / 14f) - target.survive).coerceIn(0.25f, 0.97f)
                    if (map.los(h.x, h.y, target.x, target.y) && rng.chance(chance.toDouble())) victims.add(target)
                    for (o in bySide[1 - h.side]) if (o !== target && o.alive(now) && victims.size < 3 && distToSeg(o.x, o.y, h.x, h.y, target.x, target.y) < 12f && rng.chance(0.5)) victims.add(o)
                }
                else -> {
                    val cx = if (a.target == Abilities.T_SELF_AOE) h.x else target.x
                    val cy = if (a.target == Abilities.T_SELF_AOE) h.y else target.y
                    val rad = if (a.target == Abilities.T_SELF_AOE) a.range.coerceIn(18f, 40f) else if (a.target == Abilities.T_CONE) 26f else 24f
                    val landChance = (h.aim + 0.12f).coerceIn(0.4f, 0.98f)
                    if (rng.chance(landChance.toDouble())) for (o in bySide[1 - h.side]) if (o.alive(now) && hypot(o.x - cx, o.y - cy) < rad && (o.visible || o === target)) victims.add(o)
                }
            }
        }
        val ally = allyTarget ?: lowestAlly(h, 60f)
        for (f in a.fx) {
            when (f.k) {
                Abilities.DMG -> for (v in victims) {
                    var d = std * f.p * power * h.fightIqFor(victims.size) * h.execution
                    if (a.fx.any { it.k == Abilities.EXECUTE }) d *= 1f + (1f - v.hpPct()) * 0.8f
                    damage(h, h.side, v, d, f.dmgType); hitAny = true
                }
                Abilities.DOT -> for (v in victims) { v.dotDps = std * 0.25f * power / max(1f, f.t); v.dotUntil = nowF + f.t.coerceIn(1f, 5f); v.dotBy = h.gi; hitAny = true }
                Abilities.STUN, Abilities.KNOCKUP, Abilities.SUPPRESS, Abilities.CHARM, Abilities.TAUNT, Abilities.FEAR, Abilities.KNOCKBACK -> for (v in victims) {
                    if (v.immuneUntil > nowF) continue
                    v.stunUntil = max(v.stunUntil, nowF + f.t.coerceIn(0.3f, 3f)); v.recallUntil = 0; v.dmgBy[h.gi] = now; hitAny = true
                    if (f.k == Abilities.KNOCKBACK) { val dx = v.x - h.x; val dy = v.y - h.y; val d = max(1f, hypot(dx, dy)); push(v, v.x + dx / d * 18f, v.y + dy / d * 18f) }
                }
                Abilities.ROOT, Abilities.TERRAIN -> for (v in victims) { if (v.immuneUntil > nowF) continue; v.rootUntil = max(v.rootUntil, nowF + f.t.coerceIn(0.5f, 2.5f)); v.dmgBy[h.gi] = now; hitAny = true }
                Abilities.SILENCE, Abilities.BLIND -> for (v in victims) { v.silenceUntil = max(v.silenceUntil, nowF + f.t.coerceIn(0.5f, 2.5f)); v.dmgBy[h.gi] = now; hitAny = true }
                Abilities.SLOW -> for (v in victims) { v.slowUntil = nowF + f.t.coerceIn(0.5f, 4f); v.slowPct = f.v.coerceIn(0.1f, 0.8f); v.dmgBy[h.gi] = now; hitAny = true }
                Abilities.HEAL -> { val tg = if (f.ally && ally != null) ally else h; heal(h, tg, (70f + 22f * rank) * f.p * power * (if (a.ult) 2.2f else 1f) * (1f + 0.05f * h.z(Phase.SUPPORT))); hitAny = true }
                Abilities.SHIELD -> { val tg = if (f.ally && ally != null) ally else h; val s = (90f + 28f * rank) * f.p * power * (if (a.ult) 2f else 1f); tg.shield = s; tg.shieldUntil = nowF + 3f; stat(h, HEAL, s * 0.5); hitAny = true }
                Abilities.DASH -> {
                    val tg = target
                    if (tg != null && (a.ai == Abilities.AI_ENGAGE || a.ai == Abilities.AI_POKE || a.ai == Abilities.AI_BURST || a.ai == Abilities.AI_FINISH || h.hpPct() > 0.45f)) push(h, tg.x + (h.x - tg.x) * 0.15f, tg.y + (h.y - tg.y) * 0.15f, f.v.coerceIn(20f, 120f))
                    else { val fo = fountain(h.side); push(h, fo[0], fo[1], f.v.coerceIn(20f, 120f)) }
                    hitAny = true
                }
                Abilities.STEALTH -> { h.stealthUntil = nowF + f.t.coerceIn(1f, 5f); hitAny = true }
                Abilities.IMMUNE -> { h.immuneUntil = nowF + f.t.coerceIn(0.5f, 2.5f); hitAny = true }
                Abilities.BUFF_AS -> { h.asUntil = nowF + f.t.coerceIn(1f, 6f); h.asPct = f.v.coerceIn(0.1f, 0.8f); hitAny = true }
                Abilities.BUFF_MS -> { h.msUntil = nowF + f.t.coerceIn(1f, 4f); h.msPct = f.v.coerceIn(0.1f, 0.6f); hitAny = true }
                Abilities.DMG_REDUCE -> { h.reduceUntil = nowF + f.t.coerceIn(1f, 5f); h.reducePct = f.v.coerceIn(0.1f, 0.5f); hitAny = true }
                Abilities.SUMMON -> for (v in victims.take(1)) { damage(h, h.side, v, std * 0.45f * power, 1); hitAny = true }
                Abilities.REVEAL -> { for (o in bySide[1 - h.side]) if (hypot(o.x - h.x, o.y - h.y) < 120f) o.revealUntil = nowF + f.t.coerceIn(2f, 6f); hitAny = true }
                Abilities.GLOBAL, Abilities.EXECUTE, Abilities.REVIVE -> Unit
            }
            if (hitAny) mask = mask or (1 shl f.k)
        }
        if (rec != null) {
            val tp = tgt ?: h
            ev(Ev(now, "cast", h.side, h.pc.p.id, victims.firstOrNull()?.pc?.p?.id ?: (allyTarget?.pc?.p?.id ?: -1), slot, m = mask, n = MobaEngine.packPos(tp.x, tp.y)))
        }
        h.lastCombat = now
        return true
    }

    fun Hero.fightIqFor(n: Int) = if (n >= 2) fightIq else 1f
    fun readyCount(h: Hero): Int { var n = 0; for (i in h.acts.indices) if (ready(h, i) && h.acts[i].damage != null) n++; return n }
    fun cdMult(h: Hero) = (1f - 0.012f * h.items).coerceAtLeast(0.7f)
    fun lowestAlly(h: Hero, r: Float): Hero? {
        var best: Hero? = null
        for (a in bySide[h.side]) if (a !== h && a.alive(now) && hypot(a.x - h.x, a.y - h.y) < r && (best == null || a.hpPct() < best.hpPct())) best = a
        return best
    }
    fun push(h: Hero, tx: Float, ty: Float, maxD: Float = 25f) {
        val dx = tx - h.x; val dy = ty - h.y
        val d = hypot(dx, dy)
        if (d < 0.5f) return
        val step = min(d, maxD)
        val nx = h.x + dx / d * step; val ny = h.y + dy / d * step
        if (map.walk[map.cellOf(nx, ny)]) { h.x = nx; h.y = ny } else { map.moveToward(h.x, h.y, nx, ny, step, tmp); h.x = tmp[0]; h.y = tmp[1] }
    }
    fun distToSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-3f) return hypot(px - ax, py - ay)
        val tt = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + tt * dx), py - (ay + tt * dy))
    }

    // -------------------------------------------------------------------------- fights (records)
    var fightStart = -1; var fightLast = -1; var fightX = 0f; var fightY = 0f; val fightKills = IntArray(2); var fightN = 0
    fun fightKill(side: Int, x: Float, y: Float) { if (fightStart >= 0) fightKills[side]++ }
    fun trackFights() {
        // A teamfight: at least six heroes (both teams) in combat within a small area.
        var n = 0; var sx = 0f; var sy = 0f; var s0 = 0; var s1 = 0
        for (h in heroes) if (h.alive(now) && h.lastCombat >= now - 2) { n++; sx += h.x; sy += h.y; if (h.side == 0) s0++ else s1++ }
        val cx = if (n > 0) sx / n else 0f; val cy = if (n > 0) sy / n else 0f
        var close = 0
        if (n > 0) for (h in heroes) if (h.alive(now) && h.lastCombat >= now - 2 && hypot(h.x - cx, h.y - cy) < 110f) close++
        if (close >= 6 && s0 >= 2 && s1 >= 2) {
            if (fightStart < 0) {
                fightStart = now; fightKills[0] = 0; fightKills[1] = 0; fightN = 0
                // Coaches may jump on comms for the big fights (objectives, late game, five against five).
                for (sd in 0..1) if (commsLeft[sd] > 0 && commsUntil[sd] <= now) {
                    val big = locCode(cx, cy) >= 10 || now / 60.0 > cfg.laneEnd * 1.5 || close >= 8
                    val q = opts.coachQ.getOrElse(sd) { 50.0 }
                    if (big && rng.chance((0.35 + q / 200) * (if (now / 60.0 > cfg.avgLength * 0.8) 1.3 else 1.0))) {
                        commsLeft[sd]--; commsUntil[sd] = now + 45
                        ev(Ev(now, "comms", sd, a = commsLeft[sd], n = MobaEngine.packPos(cx, cy)))
                    }
                }
            }
            fightLast = now; fightX = cx; fightY = cy; fightN = max(fightN, close)
        } else if (fightStart >= 0 && now - fightLast > 5) {
            if (fightKills[0] + fightKills[1] > 0) {
                val w = if (fightKills[0] >= fightKills[1]) 0 else 1
                if (ev != null) ev(Ev(fightStart, "fight", w, x = locCode(fightX, fightY), a = fightKills[w], b = fightKills[1 - w], m = fightLast - fightStart, n = MobaEngine.packPos(fightX, fightY)))
            }
            fightStart = -1
        }
    }

    // --------------------------------------------------------------------------------- vision
    fun updateVision() {
        for (side in 0..1) for (e in bySide[1 - side]) {
            var vis = e.revealUntil > nowF
            if (!vis && e.alive(now) && e.stealthUntil <= nowF) {
                for (h in bySide[side]) if (h.alive(now) && abs(h.x - e.x) < R.vision && abs(h.y - e.y) < R.vision && hypot(h.x - e.x, h.y - e.y) < R.vision && map.los(h.x, h.y, e.x, e.y)) { vis = true; break }
                if (!vis) for (w in wards) if (w[2].toInt() == side && w[3] > nowF && hypot(w[0] - e.x, w[1] - e.y) < 75f) { vis = true; break }
                if (!vis) for (st in active) if (st.s.team == side && !st.dead && st.shoots && abs(st.s.x - e.x) < 95f && abs(st.s.y - e.y) < 95f) { vis = true; break }
                if (!vis && e.lane >= 0) { // near own minions
                    val lane = map.lanes[e.laneMap]!!
                    val f = laneFront(map.laneIds.indexOf(e.laneMap), side)
                    if (hypot(lane.x(f) - e.x, lane.y(f) - e.y) < 60f) vis = true
                }
            }
            e.visible = vis
        }
    }

    // ------------------------------------------------------------------------------- the game
    fun run(): GameOutcome {
        rec?.pids = heroes.map { it.pc.p.id }.toIntArray()
        rec?.per = 6
        rec?.step = MobaEngine.SAMPLE_STEP
        rec?.posN = heroes.size + 6
        for (h in heroes) {
            val f = fountain(h.side)
            h.x = f[0] + (h.idx - 2) * 6f; h.y = f[1] + (h.idx - 2) * -6f
            h.maxHp = h.baseHp(); h.hp = h.maxHp
            h.maxMana = 300f + 40f * h.level; h.mana = h.maxMana
            h.gold = (if (t.id == "lol") 500f else if (t.id == "dota2") 600f else 300f)
        }
        for (o in objs) if (o.next <= 0) o.alive = true
        for (h in heroes) h.refresh()
        refreshActive()
        cacheFronts()
        snapshot(); sample()
        val hardCap = ((cfg.maxLength + 15) * 60).toInt()
        while (winner < 0) {
            now += DT
            tick++
            nowF = now.toFloat()
            sideBuff[0] = computeBuff(0); sideBuff[1] = computeBuff(1)
            if (now % 60 < DT) for (sd in 0..1) teamForm[sd] = (1.0 + rng.normal(0.0, 0.09) * (1.2 - (teams[sd].phase[Phase.CLUTCH.ordinal] - 60) / 100)).toFloat().coerceIn(0.8f, 1.2f)
            for (sd in 0..1) { avgLevel[sd] = bySide[sd].sumOf { it.level }.toDouble() / max(1, bySide[sd].size); var gsum = 0f; for (h in bySide[sd]) gsum += h.earned; teamGold[sd] = gsum }
            for (h in heroes) h.refresh()
            val pf = MobaEngine.profile
            var t0 = if (pf) System.nanoTime() else 0L
            fun lap(i: Int) { if (pf) { val t1 = System.nanoTime(); MobaEngine.prof[i] += t1 - t0; t0 = t1 } }
            tickWaves(); lap(0)
            tickObjectivesSpawn(); lap(1)
            if (tick % 2 == 0) planTeams()
            lap(2)
            updateVision(); lap(3)
            // Alternate which team acts first each second (no first-strike bias).
            // Heroes act interleaved (blue, red, blue, …) with a seeded coin deciding who starts,
            // so neither side gets a systematic first strike within a step.
            val first = if (rng.chance(0.5)) 0 else 1
            val a = bySide[first]; val b = bySide[1 - first]
            for (k in 0 until max(a.size, b.size)) { if (k < a.size) tickHero(a[k]); if (k < b.size) tickHero(b[k]) }
            lap(4)
            tickStructures(); lap(5)
            tickObjectives(); lap(6)
            trackFights(); lap(7)
            if (now % MobaEngine.SAMPLE_STEP == 0) sample()
            MobaEngine.debug?.let { d -> if (now % 60 == 0) d("${now / 60}' plan ${plan.toList()} lane ${planLane.toList()} obj ${planObj.toList()} | " + heroes.joinToString(" ") { h ->
                "${h.side}${h.pc.roleId.take(3)}@${h.x.toInt()},${h.y.toInt()} t${h.task} hp${(h.hpPct() * 100).toInt()} L${h.level} i${h.items} m${h.mana.toInt()}${if (!h.alive(now)) " DEAD" else ""}" }) }
            if (now % 10 == 0) snapshot()
            if (now >= hardCap && winner < 0) {
                // Sudden death: the side with more structures standing wins (never reached in normal play).
                val s0 = structs.count { it.s.team == 0 && !it.dead }; val s1 = structs.count { it.s.team == 1 && !it.dead }
                destroy(cores[if (s0 >= s1) 1 else 0], if (s0 >= s1) 0 else 1, null)
            }
        }
        for (h in heroes) stat(h, GOLD, h.earned.toDouble())
        if (rec != null && rec.snapT.lastOrNull() != now) snapshot()
        if (rec != null && now % MobaEngine.SAMPLE_STEP != 0) sample()
        book.finalise()
        book.rate(winner) { l ->
            val k = book.get(l, K); val d = book.get(l, D); val a = book.get(l, A)
            (k * 3 + a * 1.5 + 1) / (d * 2 + 2) + book.get(l, GOLD) / 1000 * 0.25 + book.get(l, DMG) / 15000 + book.get(l, HEAL) / 20000 + book.get(l, OBJ) * 0.4 + book.get(l, TOWERS) * 0.3 + book.get(l, VISION) / 40
        }
        ev(Ev(now, "end", winner))
        ev?.sortBy { it.t }
        return GameOutcome(winner, now, intArrayOf(kills[0], kills[1]), book)
    }

    // ------------------------------------------------------------------------------ minion waves
    fun tickWaves() {
        if (now >= R.firstWave && now >= nextWave) {
            nextWave = (if (nextWave == 0) R.firstWave else nextWave) + 30
            for (li in 0..2) for (side in 0..1) {
                val enemy = 1 - side
                val sup = laneBroken(enemy, li) || pushUntil[side] > now
                val late = ((now / 60.0 - cfg.laneEnd) / (cfg.avgLength * 0.3)).toInt().coerceIn(0, 4)
                val size = R.waveSize + late + (if (sup) 2 else 0)
                packets[li][side].add(Packet(if (side == 0) 0.04f else 0.96f, size.toFloat(), sup))
            }
        }
        val step = minionSpeed * DTF
        for (li in 0..2) {
            val lane = map.laneIds[li]
            // Both sides' waves move at the same time: approaching fronts meet where their free
            // movement this step brings them (midway when both walk), not where the side that
            // happened to move first left its wave.
            val towerLimit0 = (frontStruct(1, li)?.f ?: 0.97f) - 0.025f
            val towerLimit1 = (frontStruct(0, li)?.f ?: 0.03f) + 0.025f
            val fa = findFront(li, 0); val fb = findFront(li, 1)
            var meet = Float.NaN
            if (fa != null && fb != null && fb.f > fa.f) {
                val ma = (towerLimit0 - fa.f).coerceIn(0f, step)
                val mb = (fb.f - towerLimit1).coerceIn(0f, step)
                val gap = fb.f - fa.f
                meet = if (ma + mb <= 0f) (fa.f + fb.f) / 2f else fa.f + gap * ma / (ma + mb)
            }
            for (side in 0..1) {
                val dir = if (side == 0) 1f else -1f
                val enemyFront = if (side == 0) fb else fa
                val list = packets[li][side]
                for (p in list) {
                    var limit = if (side == 0) towerLimit0 else towerLimit1
                    if (enemyFront != null && (enemyFront.f - p.f) * dir > 0) {
                        val stop = if (meet.isNaN()) enemyFront.f - dir * 0.01f else meet - dir * 0.01f
                        limit = if (side == 0) min(limit, stop) else max(limit, stop)
                    }
                    val nf = p.f + dir * step
                    // Never walk backwards because of a limit.
                    p.f = if (side == 0) max(p.f, min(nf, limit)) else min(p.f, max(nf, limit))
                }
                // Waves that catch up with the one ahead join it.
                var i = 0
                while (i < list.size) {
                    var j = i + 1
                    while (j < list.size) {
                        if (abs(list[i].f - list[j].f) < 0.015f) {
                            val a = list[i]; val b = list[j]
                            list[i] = Packet(if (side == 0) max(a.f, b.f) else min(a.f, b.f), a.n + b.n, a.sup || b.sup)
                            list.removeAt(j)
                        } else j++
                    }
                    i++
                }
            }
            // Clash: the two front packets fight (and heroes near the front clear enemy minions).
            val a = findFront(li, 0); val b = findFront(li, 1)
            if (a != null && b != null && abs(a.f - b.f) < 0.03f) {
                val da = b.n * (if (b.sup) 0.16f else 0.09f) * DTF
                val db = a.n * (if (a.sup) 0.16f else 0.09f) * DTF
                minionDeaths(li, 0, a, min(a.n, da))
                minionDeaths(li, 1, b, min(b.n, db))
            }
            for (side in 0..1) {
                val p = findFront(li, 1 - side) ?: continue
                laneXY(lane, p.f, tmp)
                val fx = tmp[0]; val fy = tmp[1]
                for (h in bySide[side]) if (h.alive(now) && h.recallUntil <= now && abs(h.x - fx) < 45f && abs(h.y - fy) < 45f) {
                    val clear = (0.22f + 0.09f * h.items + 0.02f * h.level) * h.laning * (if (h.supportCls) 0.4f else 1f) * DTF
                    minionDeaths(li, 1 - side, p, min(p.n, clear), h)
                }
            }
            for (side in 0..1) packets[li][side].removeAll { it.n <= 0.05f }
        }
        cacheFronts()
    }

    /** `n` minions of `side` die in lane li; the other side's nearby heroes farm them. */
    fun minionDeaths(li: Int, side: Int, p: Packet, n: Float, killer: Hero? = null) {
        if (n <= 0f) return
        p.n -= n
        val lane = map.laneIds[li]
        laneXY(lane, p.f, tmp)
        val fx = tmp[0]; val fy = tmp[1]
        val farmer = 1 - side
        val csGold = gpmPerSec() * 60f * 0.55f / (R.waveSize * 2f * 0.9f) * (if (p.sup) 1.2f else 1f)
        var share = 0
        for (h in bySide[farmer]) if (h.alive(now) && abs(h.x - fx) < 60f && abs(h.y - fy) < 60f) share++
        for (h in bySide[farmer]) {
            if (!h.alive(now) || abs(h.x - fx) >= 60f || abs(h.y - fy) >= 60f) continue
            addXp(h, n / (R.waveSize * 2.0) * (if (share > 1) 0.75 else 1.0) * 0.95)
            // Last hits: the main farmer of the lane takes most, by laning skill; supports leave them.
            val lastHit = (if (h.supportCls && share > 1) 0.12f else h.laning) * (if (h === killer) 1.1f else 0.92f) / (if (share > 1 && !h.supportCls) 1.15f else 1f)
            val cs = n * lastHit.coerceIn(0.05f, 1f)
            stat(h, CS, cs.toDouble())
            addGold(h, cs * csGold)
        }
        if (DN >= 0) for (h in bySide[side]) if (h.alive(now) && abs(h.x - fx) < 45f && abs(h.y - fy) < 45f && !h.supportCls) stat(h, DN, (n * 0.12f * (h.laning - 0.6f).coerceAtLeast(0f) * 3f).toDouble())
    }

    // ------------------------------------------------------------------------------ objectives
    fun tickObjectivesSpawn() {
        val m = now / 60.0
        for (o in objs) {
            if (o.alive) { if (o.o.until != null && m > o.o.until + 1) o.alive = false; continue }
            if (o.o.until != null && m > o.o.until) continue
            if (now >= o.next && o.next >= 0) {
                o.place()
                o.alive = true; o.hp = o.maxHp * (1f + (m / 30.0).toFloat() * 0.6f); o.dmgBy[0] = 0f; o.dmgBy[1] = 0f
                ev(Ev(now, "spawn", -1, x = o.idx, n = MobaEngine.packPos(o.x, o.y)))
            }
        }
    }

    fun tickObjectives() {
        for (o in objs) {
            if (!o.alive) continue
            var at0 = 0; var at1 = 0
            var tank: Hero? = null
            for (h in heroes) if (h.alive(now) && h.task == 3 && h.recallUntil == 0 && h.hpPct() > 0.3f && hypot(h.x - o.x, h.y - o.y) < 40f) {
                if (h.side == 0) at0++ else at1++
                if (o.capture) continue
                if (tank == null || h.hp > tank.hp) tank = h
                // Only uncontested heroes hit it (contested ones fight each other).
                val enemyNear = bySide[1 - h.side].any { it.alive(now) && it.visible && dist(it, h) < 50f }
                if (enemyNear && h.focus >= 0) continue
                val dps = (h.atk() * h.attackSpeed() + h.apPower() * 20f) * (1f + 0.04f * h.z(Phase.OBJECTIVE))
                o.hp -= dps * DTF; o.dmgBy[h.side] += dps * DTF
                h.lastCombat = now - 3
            }
            // The monster hits the healthiest attacker.
            tank?.let { tk -> tk.hp -= (60f + now / 60f * 5f) * (1f - tk.resist()) * DMG_PACE * DTF; if (tk.hp <= 0f) die(tk, null) }
            if (o.capture && (at0 > 0) != (at1 > 0)) { o.hp = 0f; o.dmgBy[if (at0 > 0) 0 else 1] = 1f }
            if (o.hp <= 0f) takeObjective(o)
        }
    }

    fun takeObjective(o: Obj) {
        var side = if (o.dmgBy[0] >= o.dmgBy[1]) 0 else 1
        // Smite / Retribution contest: the other jungler can steal it.
        if (R.smite) {
            val other = bySide[1 - side].firstOrNull { it.jungler && it.alive(now) && hypot(it.x - o.x, it.y - o.y) < 60f && it.smiteReady <= nowF }
            val mine = bySide[side].firstOrNull { it.jungler && it.alive(now) && hypot(it.x - o.x, it.y - o.y) < 60f }
            if (other != null) {
                val steal = 0.18f + 0.06f * other.clutch - 0.05f * (mine?.clutch ?: -1f) - (if (mine != null && mine.smiteReady <= nowF) 0.1f else 0f)
                other.smiteReady = nowF + 60f
                if (rng.chance(steal.coerceIn(0.03f, 0.6f).toDouble())) { side = 1 - side; ev(Ev(now, "steal", side, other.pc.p.id, x = o.idx)) }
            }
        }
        o.alive = false
        o.next = now + (o.o.respawn * 60 * (patch?.objRespawn(o.o.id) ?: 1.0)).toInt()
        val taker = bySide[side].filter { it.alive(now) }.minByOrNull { hypot(it.x - o.x, it.y - o.y) + (if (it.jungler) -30f else 0f) }
        if (taker != null) stat(taker, OBJ, 1.0)
        ev(Ev(now, "obj", side, taker?.pc?.p?.id ?: -1, x = o.idx, n = MobaEngine.packPos(o.x, o.y)))
        val mod = (patch?.objValue(o.o.id) ?: 1.0).toFloat()
        val v = (o.o.value * mod).toFloat()
        val team = bySide[side]
        when (o.o.kind) {
            "gold" -> for (h in team) addGold(h, v)
            "stack" -> { val n = (stacks[side][o.o.id] ?: 0) + 1; stacks[side][o.o.id] = n; if (o.o.maxStacks > 0 && n == o.o.maxStacks && o.o.soul > 0) soul[side] += (o.o.soul * mod).toFloat() }
            "push" -> { pushUntil[side] = now + (if (o.o.major) 150 else 90); buffUntil[side] = pushUntil[side]; buffVal[side] = max(buffVal[side], v * 0.25f); for (h in team) addGold(h, gpmPerSec() * 40f) }
            "siege" -> siegeUntil[side] = now + 120
            "aegis" -> (team.filter { it.alive(now) }.maxByOrNull { it.earned } ?: team.firstOrNull())?.aegis = true
            "execute" -> executeUntil[side] = now + 150
            "xp" -> for (h in team) addXp(h, 0.9)
            "buff" -> { buffUntil[side] = now + 150; buffVal[side] = max(buffVal[side], v * 0.6f) }
        }
    }

    // ---------------------------------------------------------------------------- structures
    fun tickStructures() {
        for (st in active) {
            if (st.dead || !st.shoots) continue
            val side = st.s.team
            val rng0 = 45f
            // Minions in range first, then heroes who are attacking under it.
            var target: Hero? = null
            val li = st.laneIdx
            val enemyPacket = if (li >= 0) frontPacket(li, 1 - side)?.takeIf { abs(it.f - st.f) < 0.05f } else null
            for (h in bySide[1 - side]) {
                if (!h.alive(now) || abs(h.x - st.s.x) > rng0 || abs(h.y - st.s.y) > rng0) continue
                if (hypot(h.x - st.s.x, h.y - st.s.y) > rng0) continue
                val diving = h.lastCombat >= now - 2 && bySide[side].any { it.alive(now) && it.dmgBy[h.gi] >= now - 2 && hypot(it.x - st.s.x, it.y - st.s.y) < rng0 + 10f }
                if (enemyPacket == null || diving) { target = h; break }
            }
            if (target != null) damage(null, side, target, R.towerDps * (1f + now / 1800f) * 0.75f * DTF, 2)
            else if (enemyPacket != null) enemyPacket.n -= 0.7f * DTF
        }
        // Minions and heroes damage exposed structures.
        for (li in 0..2) for (side in 0..1) {
            val st = frontStruct(1 - side, li)
            val p = frontPacket(li, side)
            if (st != null && p != null && abs(p.f - st.f) < 0.05f) hitStruct(st, side, p.n * (if (p.sup) 30f else 14f) * (if (siegeUntil[side] > now) 1.5f else 1f) * (1f + (now / 60f / cfg.avgLength.toFloat()) * 0.8f) * R.paceMul.toFloat() * 1.3f * overtime() * DTF, null)
        }
        for (h in heroes) {
            if (!h.alive(now) || h.task == 4) continue
            for (st in active) {
                if (st.dead || st.s.team == h.side || abs(h.x - st.s.x) > 40f || abs(h.y - st.s.y) > 40f) continue
                // Heroes only hit a structure when nobody is fighting them.
                if (h.lastCombat >= now - DT && h.focus >= 0) continue
                hitStruct(st, h.side, h.atk() * h.attackSpeed() * (if (siegeUntil[h.side] > now) 1.4f else 1f) * (1f + 0.03f * h.z(Phase.OBJECTIVE)) * 1.25f * R.paceMul.toFloat() * DTF, h)
                break
            }
        }
    }

    fun hitStruct(st: Struct, side: Int, dmg: Float, h: Hero?) {
        if (st.dead || dmg <= 0f) return
        st.hp -= dmg
        val pct = (st.hp / st.maxHp * 100).roundToInt().coerceIn(0, 100)
        if (pct <= st.lastPct - 10 || st.hp <= 0f) { st.lastPct = pct; ev(Ev(now, "shp", side, x = st.s.idx, a = pct)) }
        if (st.hp <= 0f) destroy(st, side, h)
    }

    fun destroy(st: Struct, side: Int, h: Hero?) {
        if (st.dead) return
        st.dead = true; st.deadAt = now; st.hp = 0f
        refreshActive()
        val near = bySide[side].filter { it.alive(now) && hypot(it.x - st.s.x, it.y - st.s.y) < 80f }
        // The structure counts for one player: the last hero to hit it, else the closest.
        if (st.s.kind == "tower") (h ?: near.minByOrNull { hypot(it.x - st.s.x, it.y - st.s.y) } ?: bySide[side].firstOrNull())?.let { stat(it, TOWERS, 1.0) }
        for (x in bySide[side]) addGold(x, gpmPerSec() * 60f * 0.18f)
        for (x in near) addGold(x, gpmPerSec() * 60f * 0.25f)
        when {
            st.s.kind == "core" -> { winner = side; ev(Ev(now, "core", side, a = 1, n = st.s.idx)) }
            st.s.lane == null || st.s.kind == "inhibitor" || st.s.kind == "barracks" -> ev(Ev(now, "base", side, a = structs.count { it.s.team == st.s.team && it.dead && (it.s.lane == null || it.s.kind != "tower") }, n = st.s.idx))
            else -> ev(Ev(now, "tower", side, x = st.laneIdx, a = laneStructs[st.s.team][st.laneIdx].count { it.dead }, n = st.s.idx))
        }
    }

    // --------------------------------------------------------------------------- team planning
    val plan = IntArray(2) // 0 laning/farm, 1 group+push, 2 objective, 3 defend
    val planLane = IntArray(2)
    val planObj = IntArray(2) { -1 }

    fun teamPower(side: Int): Float { var p = 0f; for (h in bySide[side]) if (h.alive(now)) p += h.power(); return p }

    val planSince = IntArray(2)
    val pow = FloatArray(2)
    /** Ganks per lane so far (junglers spread their pressure) and each jungler's favoured side this game. */
    val laneGanks = Array(2) { IntArray(3) }
    val gankSide = IntArray(2) { s -> if ((teams[s].team.id * 31 + teams[1 - s].team.id + s) % 2 == 0) 0 else 2 }

    /**
     * After the laning phase: an enemy farming a side lane alone, deep toward our half, gets
     * collapsed on by our nearest healthy heroes (two or three). This is how split-pushers and
     * side-laners die in real games; without it the top / EXP lane was almost never caught.
     */
    fun huntCheck(side: Int) {
        if (now / 60.0 < cfg.laneEnd) return
        if (bySide[side].any { it.huntUntil > now }) return
        val aggression = teams[side].aggression / 100.0
        val macro = ((teams[side].phase[Phase.MACRO.ordinal] - 68) / 14).coerceIn(-2.5, 2.5)
        for (e in bySide[1 - side]) {
            if (!e.alive(now) || !e.visible || e.task != 0 || e.recallUntil > 0) continue
            val li = map.laneIds.indexOf(e.laneMap)
            if (li < 0 || li == 1) continue
            if (bySide[1 - side].any { it !== e && it.alive(now) && hypot(it.x - e.x, it.y - e.y) < 200f }) continue
            val deep = if (side == 0) 1f - laneFront(li, 1 - side) else laneFront(li, 1 - side)
            if (deep < 0.36f) continue
            // A split-pusher drawing pressure is the usual target: teams send people to catch him.
            if (!rng.chance((0.22 + aggression * 0.18 + macro * 0.03).coerceIn(0.08, 0.5))) continue
            val hunters = bySide[side].filter { it.alive(now) && it.hpPct() > 0.55f && it.task != 3 && it.task != 4 && it.recallUntil == 0 && it.lastCombat < now - 3 }
                .sortedBy { hypot(it.x - e.x, it.y - e.y) }.take(if (e.power() > teamPower(1 - side) / 5f) 3 else 2)
            if (hunters.size < 2 || hypot(hunters[0].x - e.x, hunters[0].y - e.y) > 650f) continue
            for (x in hunters) { x.huntTarget = e.gi; x.huntUntil = now + 25 }
            MobaEngine.dbg?.incrementAndGet(3 + li)
            return
        }
    }

    fun planTeams() {
        val m = now / 60.0
        pow[0] = teamPower(0); pow[1] = teamPower(1)
        for (side in 0..1) {
            val tc = teams[side]
            val macro = ((tc.phase[Phase.MACRO.ordinal] - 68) / 14).coerceIn(-2.5, 2.5)
            val alive = bySide[side].count { it.alive(now) }
            val enemyAlive = bySide[1 - side].count { it.alive(now) }
            val ratio = pow[side] / max(1f, pow[1 - side])
            // Defend a structure under siege.
            var threatened = -1
            for (st in active) {
                if (st.s.team != side) continue
                var n = 0
                for (e in bySide[1 - side]) if (e.alive(now) && abs(e.x - st.s.x) < 85f && abs(e.y - st.s.y) < 85f) n++
                if (n >= 2) threatened = if (st.laneIdx >= 0) st.laneIdx else 1
            }
            // Objective worth taking now (better macro teams start earlier and more precisely).
            var bestObj = -1
            var bestVal = 0.0
            for (o in objs) {
                if (!o.alive && !(o.next - now in 0..20)) continue
                val value = (if (o.o.major) 2.0 else 1.0) * (if (m < cfg.laneEnd) 0.75 else 1.0)
                val adv = (alive - enemyAlive) * 0.8 + (ratio - 1) * 2.2 + macro * 0.2 + (tc.aggression - 50) / 150.0
                // Nearer objectives first (each side's own Wisdom shrine and Tormentor).
                val near = hypot(o.x - fountain(side)[0], o.y - fountain(side)[1]) / 1000f
                val score = value * (0.55 + adv) - (if (objs.count { it.idx == o.idx } > 1) near * 0.6 else 0.0)
                if (score > bestVal) { bestVal = score; bestObj = o.k }
            }
            val old = plan[side]
            if (threatened >= 0 && m > 2.5 && ratio > 0.45) { plan[side] = 3; planLane[side] = threatened }
            else if (bestObj >= 0 && bestVal > 0.85 - macro * 0.06 && alive >= 3 && (m >= cfg.laneEnd * 0.4)) { plan[side] = 2; planObj[side] = bestObj }
            else if (m >= cfg.laneEnd && alive >= 3 && (ratio > 1.0 - macro * 0.03 || alive > enemyAlive + 1 || pushUntil[side] > now || m > cfg.avgLength * 1.1)) {
                // Group and push: keep the lane until its front structure falls (or a minute passes).
                val cur = planLane[side]
                val curDone = old != 1 || now - planSince[side] > 60 || frontStruct(1 - side, cur) == null && baseStructs[1 - side].all { it.dead }
                if (curDone) {
                    var best = 1; var bv = Float.MAX_VALUE
                    for (li in 0..2) {
                        val st = frontStruct(1 - side, li) ?: (baseStructs[1 - side].firstOrNull { !it.dead } ?: cores[1 - side])
                        // An open base comes first: go for the base towers / core through the broken lane.
                        val v = if (st.s.lane == null) -1f + st.hp / st.maxHp else st.hp / st.maxHp * 2000f + abs(laneFront(li, side) - (if (st.f >= 0) st.f else if (side == 0) 0.95f else 0.05f)) * 2500f
                        if (v < bv) { bv = v; best = li }
                    }
                    planLane[side] = best; planSince[side] = now
                }
                plan[side] = 1
            } else plan[side] = 0
            if (plan[side] != old) planSince[side] = now
            if (plan[side] <= 1) huntCheck(side)
        }
    }

    // ------------------------------------------------------------------------------- hero ticks
    fun tickHero(h: Hero) {
        if (!h.alive(now)) {
            if (now == h.deadUntil - 1) Unit
            // Dota 2 buyback when the base is under siege.
            if (cfg.buyback && BB >= 0 && h.bbReady <= nowF && h.deadUntil - now > 20) {
                val cost = 200f + 60f * now / 60f
                val siege = bySide[1 - h.side].count { it.alive(now) && hypot(it.x - fountain(h.side)[0], it.y - fountain(h.side)[1]) < 380f } >= 3
                if (siege && h.gold > cost + 400f && rng.chance((0.35 + h.clutch * 0.08).coerceIn(0.1, 0.8))) {
                    h.gold -= cost; h.bbReady = nowF + 480f; stat(h, BB, 1.0)
                    h.deadUntil = now; ev(Ev(now, "buyback", h.side, h.pc.p.id))
                }
            }
            if (h.alive(now)) respawn(h)
            return
        }
        if (h.hp <= 0f) { if (h.deaths > 0) respawn(h) else return }
        // Regeneration, damage over time, mana.
        if (h.dotUntil > nowF) damage(heroes.getOrNull(h.dotBy), 1 - h.side, h, h.dotDps * DTF, 1)
        if (!h.alive(now)) return
        h.mana = min(h.maxMana, h.mana + (2f + 0.4f * h.level) * DTF)
        val inBase = hypot(h.x - fountain(h.side)[0], h.y - fountain(h.side)[1]) < 60f
        if (inBase) { h.hp = min(h.maxHp, h.hp + h.maxHp * 0.12f * DTF); h.mana = h.maxMana; shop(h) }
        else if (h.lastCombat < now - 6) h.hp = min(h.maxHp, h.hp + h.maxHp * 0.004f * DTF)
        if (h.shieldUntil <= nowF) h.shield = 0f
        // Passive gold (and the Dota courier).
        addGold(h, gpmPerSec() * 0.25f * DTF)
        if (R.courier && now % 60 == 0) shop(h)
        if (now % 60 == 0) addXp(h, if (h.supportCls) 0.25 else 0.15)
        if (h.cc(nowF)) return
        // Recalling.
        if (h.recallUntil > 0) {
            if (h.lastCombat >= now - DT) h.recallUntil = 0
            else if (now >= h.recallUntil) { val f = fountain(h.side); h.x = f[0]; h.y = f[1]; h.recallUntil = 0; ev(Ev(now, "recall", h.side, h.pc.p.id)) }
            return
        }
        // Fight or flight.
        if (fight(h)) return
        // Go home when low (or rich in the early game).
        if (!inBase && R.recall > 0 && (h.hpPct() < 0.3f || (h.mana < h.maxMana * 0.15f && h.hpPct() < 0.6f) || (h.gold > gpmPerSec() * 60f * 4.5f && plan[h.side] == 0 && h.lastCombat < now - 8))) {
            if (nearestEnemyDist(h) > 70f) { h.recallUntil = now + R.recall; return }
        }
        if (!inBase && R.recall == 0 && h.hpPct() < 0.25f) { h.task = 4; val f = fountain(h.side); moveTo(h, f[0], f[1]); return }
        if (h.lastCombat >= now - 3 || (tick + h.gi) % 2 == 0) decide(h)
        if (h.task == 4) { val f = fountain(h.side); moveTo(h, f[0], f[1]) } else moveTo(h, h.targetX, h.targetY)
        // Supports and junglers ward key spots.
        ward(h)
    }

    fun respawn(h: Hero) {
        val f = fountain(h.side)
        h.x = f[0]; h.y = f[1]; h.maxHp = h.baseHp(); h.hp = h.maxHp; h.mana = h.maxMana
        h.stunUntil = 0f; h.rootUntil = 0f; h.slowUntil = 0f; h.silenceUntil = 0f; h.dotUntil = 0f; h.shield = 0f
        shop(h)
    }

    fun shop(h: Hero) {
        val itemCost = gpmPerSec() * 60f * 4.2f
        while (h.gold >= itemCost && h.items < 6) { h.gold -= itemCost; h.spent += itemCost; h.items++; val frac = h.hpPct(); h.maxHp = h.baseHp(); h.hp = h.maxHp * frac
            ev(Ev(now, "item", h.side, h.pc.p.id, x = h.items, n = h.spent.roundToInt())) }
        if (h.items >= 6 && h.gold >= itemCost * 1.5f) { h.gold -= itemCost; h.spent += itemCost } // upgrades / consumables
    }

    fun nearestEnemyDist(h: Hero): Float { var d = Float.MAX_VALUE; for (e in bySide[1 - h.side]) if (e.alive(now) && e.visible) d = min(d, dist(h, e)); return d }

    fun moveTo(h: Hero, tx: Float, ty: Float) {
        val sp = h.speed() * DTF
        if (sp <= 0f) return
        map.moveToward(h.x, h.y, tx, ty, sp, tmp)
        h.x = tmp[0]; h.y = tmp[1]
    }

    /** Where to be when not fighting. */
    fun decide(h: Hero) {
        val side = h.side
        val m = now / 60.0
        val laning = m < cfg.laneEnd
        val p = plan[side]
        if (h.huntUntil > now) {
            val tg = heroes.getOrNull(h.huntTarget)
            if (tg != null && tg.alive(now) && plan[side] <= 1) { h.task = 5; h.targetX = tg.x; h.targetY = tg.y; return }
            h.huntUntil = 0; h.huntTarget = -1
        }
        when {
            p == 3 -> { val st = frontStruct(side, planLane[side]) ?: cores[side]; h.task = 2; h.targetX = st.s.x + (fountain(side)[0] - st.s.x) * 0.05f; h.targetY = st.s.y + (fountain(side)[1] - st.s.y) * 0.05f }
            p == 2 && h.hpPct() > 0.35f && (!laning || h.jungler || h.supportCls || h.lane == 1 || (h.lane == 0 && topJoins(h, objs[planObj[side]])) || rng.chance(0.02)) -> {
                val o = objs[planObj[side]]
                h.task = 3; h.targetX = o.x + (fountain(side)[0] - o.x) * 0.03f; h.targetY = o.y + (fountain(side)[1] - o.y) * 0.03f
            }
            p == 1 && !(h.lane == 0 && h.idx == 0 && comp[side].prof("split") > 6 && bySide[side].count { it.alive(now) } >= 4) -> {
                val li = planLane[side]
                val lane = map.laneIds[li]
                val target = frontStruct(1 - side, li) ?: baseStructs[1 - side].firstOrNull { !it.dead } ?: cores[1 - side]
                val f = laneFront(li, side)
                h.task = 2
                val own = frontPacket(li, side)
                val minionsThere = own != null && target.f >= 0 && abs(own.f - target.f) < 0.06f
                val lateSiege = target.s.lane == null || target.hp < target.maxHp * 0.35f || bySide[1 - side].count { it.alive(now) } <= 2
                if (target.s.lane == null || ((minionsThere || lateSiege) && exposed(target))) { h.targetX = target.s.x + (fountain(side)[0] - target.s.x) * 0.04f + (h.idx - 2) * 3f; h.targetY = target.s.y + (fountain(side)[1] - target.s.y) * 0.04f }
                else {
                    // Formation: the front line (tanks, the top / EXP laner) a step ahead, carries and mages behind.
                    val fo = (if (h.tank || h.lane == 0) 0.018f else if (h.marksman || h.mage) -0.018f else 0f) * (if (side == 0) 1f else -1f)
                    laneXY(lane, (if (side == 0) min(f, target.f - 0.06f) else max(f, target.f + 0.06f)) + fo, tmp); h.targetX = tmp[0] + (h.idx - 2) * 4f; h.targetY = tmp[1] - (h.idx - 2) * 4f
                }
            }
            h.jungler && (laning || h.lane < 0) -> jungle(h)
            h.lane >= 0 -> {
                // Lane: stand just behind the own minion front (supports beside their carry).
                val li = map.laneIds.indexOf(h.laneMap)
                val f = laneFront(li, side)
                val back = (if (side == 0) -1f else 1f) * (0.02f + (if (h.hpPct() < 0.5f) 0.03f else 0f) + (if (h.duoSecond) 0.01f else 0f))
                laneXY(h.laneMap, (f + back).coerceIn(0.02f, 0.98f), tmp)
                h.task = 0; h.targetX = tmp[0]; h.targetY = tmp[1]
                // After laning: roam to mid / farm the side lane. The top / EXP laner splits its time:
                // side-lane farming, then a spell with the team (and its fights) in the middle.
                if (!laning && !h.marksman && (h.lane != 0 || (now / 40 + h.gi) % 5 < 4)) {
                    laneXY("mid", (laneFront(1, side) + back).coerceIn(0.05f, 0.95f), tmp); h.targetX = tmp[0]; h.targetY = tmp[1]
                }
            }
            else -> jungle(h)
        }
        // Ganks in the laning phase.
        if (h.jungler && laning && now % 6 == 0) gankCheck(h)
        if (h.task == 5 && h.focus < 0) { val tg = heroes.getOrNull(h.camp); if (tg != null && tg.alive(now)) { h.targetX = tg.x; h.targetY = tg.y } }
    }

    /** The top / EXP / Baron laner rotates to an objective near him, or (teleport-style) in some fights elsewhere. */
    fun topJoins(h: Hero, o: Obj): Boolean = hypot(o.x - h.x, o.y - h.y) < 480f || (now / 30 + h.gi * 3) % 10 < 3

    fun jungle(h: Hero) {
        if (h.task == 5) return
        h.task = 1
        val side = h.side
        if (h.camp < 0 || campNext[h.camp] > now || map.camps[h.camp].let { hypot(it.x - h.x, it.y - h.y) } > 600f) {
            var best = -1; var bd = Float.MAX_VALUE
            for ((i, c) in map.camps.withIndex()) {
                if (campNext[i] > now) continue
                val own = c.team == side || c.team < 0
                val d = hypot(c.x - h.x, c.y - h.y) + (if (own) 0f else 260f - 60f * h.jungleSkill) + (if (c.kind == "buff") -60f else 0f)
                if (d < bd) { bd = d; best = i }
            }
            h.camp = best
        }
        val c = map.camps.getOrNull(h.camp) ?: run { laneXY("mid", 0.5f, tmp); h.targetX = tmp[0]; h.targetY = tmp[1]; return }
        h.targetX = c.x; h.targetY = c.y
        if (hypot(c.x - h.x, c.y - h.y) < 22f) {
            if (campHp[h.camp] <= 0f) campHp[h.camp] = (if (c.kind == "buff") 2200f else 1400f) * (1f + now / 900f)
            val dps = (h.atk() * h.attackSpeed() + h.apPower() * 25f) * h.jungleSkill * (if (t.id == "dota2") 1f else 1.15f)
            campHp[h.camp] -= dps * DTF
            h.hp -= (18f + now / 60f) * (1f - h.resist()) * (if (h.tank) 0.7f else 1f) * DTF
            if (h.hp <= h.maxHp * 0.05f) h.hp = h.maxHp * 0.05f
            if (campHp[h.camp] <= 0f) {
                val gold = gpmPerSec() * 60f * 0.55f / 1.6f * (if (c.kind == "buff") 1.3f else 1f) * (if (t.id == "dota2") 0.9f else 1f)
                addGold(h, gold)
                stat(h, CS, if (t.id == "lol" || t.id == "dota2") 4.0 else 1.0)
                addXp(h, if (c.kind == "buff") 0.75 else 0.55)
                if (c.kind == "buff") { h.asUntil = nowF + 90f; h.asPct = 0.1f }
                campNext[h.camp] = now + R.campRespawn
                h.camp = -1
            }
        }
    }

    fun gankCheck(h: Hero) {
        val side = h.side
        // No gank on a low health bar (jungle camps wear the jungler down): clear, heal, then go.
        if (h.hpPct() < 0.6f) { if (h.task == 5) { h.task = 1; h.camp = -1 }; return }
        var best: Hero? = null; var bv = 0f; var bestLi = -1
        for (e in bySide[1 - side]) {
            if (!e.alive(now) || e.lane < 0 || e.jungler) continue
            val li = map.laneIds.indexOf(e.laneMap)
            val pushed = if (side == 0) 1f - laneFront(li, 1 - side) else laneFront(li, 1 - side) // how far the enemy pushed toward our side
            val ally = bySide[side].any { it.alive(now) && it.laneMap == e.laneMap && !it.jungler }
            val warded = wards.any { it[2].toInt() == 1 - side && it[3] > nowF && hypot(it[0] - e.x, it[1] - e.y) < 150f }
            // Junglers spread their ganks (each lane ganked counts against it) and play to a favoured side.
            val current = h.task == 5 && h.camp == e.gi
            val spread = if (li in 0..2) (laneGanks[side][li] - (if (current) 1 else 0)).coerceAtLeast(0) * 0.3f else 0f
            val pref = if (li == gankSide[side]) 0.3f else 0f
            val v = (1f - e.hpPct()) * 1.4f + pushed * 1.6f + (if (ally) 0.5f else 0f) - (if (warded) 0.9f else 0f) - dist(h, e) / 1100f - spread + pref
            if (v > bv) { bv = v; best = e; bestLi = li }
        }
        val aggression = (teams[side].aggression / 100.0).toFloat()
        if (best != null && bv > 1.15f - aggression * 0.4f - 0.1f * h.z(Phase.JUNGLE)) {
            if ((h.task != 5 || h.camp != best.gi) && bestLi in 0..2) { laneGanks[side][bestLi]++; MobaEngine.dbg?.incrementAndGet(bestLi) }
            h.task = 5; h.camp = best.gi; h.targetX = best.x; h.targetY = best.y
        }
        else if (h.task == 5) { h.task = 1; h.camp = -1 }
    }

    fun ward(h: Hero) {
        if (!(h.supportCls || h.jungler || h.pc.role.isSupport)) return
        val every = (90f / h.visionSkill).toInt().coerceIn(40, 180)
        if ((tick + h.gi * 7) % (every / DT).coerceAtLeast(1) != 0) return
        // At the nearest objective pit, else the river point closest to the hero.
        val spot = objs.minByOrNull { hypot(it.x - h.x, it.y - h.y) } ?: return
        val wx = (h.x + spot.x) / 2f; val wy = (h.y + spot.y) / 2f
        val dur = if (t.id == "dota2") 360f else 90f
        wards.add(floatArrayOf(wx, wy, h.side.toFloat(), nowF + dur))
        if (wards.size > 24) wards.removeAll { it[3] <= nowF }
        stat(h, VISION, 1.0)
        ev(Ev(now, "ward", h.side, h.pc.p.id, m = dur.toInt(), n = MobaEngine.packPos(wx, wy)))
        // Sweep: enemy wards near the hero are cleared.
        for (w in wards) if (w[2].toInt() != h.side && w[3] > nowF && hypot(w[0] - h.x, w[1] - h.y) < 60f) {
            w[3] = nowF; stat(h, VISION, 1.0); ev(Ev(now, "unward", h.side, h.pc.p.id, n = MobaEngine.packPos(w[0], w[1])))
        }
    }

    /**
     * Combat for one hero this second: choose to engage, keep fighting or escape from the local
     * balance of power; cast abilities by their AI tags; basic attack in range; chase or flee.
     */
    fun fight(h: Hero): Boolean {
        val side = h.side
        val enemies = bySide[1 - side]
        var near = 0; var nearPow = 0f
        var best: Hero? = null; var bestScore = -1f
        for (e in enemies) {
            if (!e.alive(now) || !e.visible) continue
            val dx = e.x - h.x; val dy = e.y - h.y
            if (abs(dx) > 95f || abs(dy) > 95f) continue
            val d = sqrt(dx * dx + dy * dy)
            if (d > 95f) continue
            near++; nearPow += e.power()
            // Target choice: close, low health, damage dealers (better teamfighters pick better).
            // Whoever is in front gets hit (the front line soaks the fight); smart players still reach the carries.
            val prox = (120f - d) / 120f
            val score = (1.4f - e.hpPct()) * (if (e.marksman || e.mage) 1f + 0.12f * h.fightIq else 1f) * prox * prox + (if (e.gi == h.focus) 0.35f else 0f)
            if (score > bestScore) { bestScore = score; best = e }
        }
        if (best == null) { h.focus = -1; return false }
        if (now / 60.0 < cfg.laneEnd && h.lane >= 0 && best.lane == h.lane) MobaEngine.dbg?.let { d -> if (h.lane in 0..2) d.incrementAndGet(9 + h.lane) }
        var allyPow = 0f
        for (a in bySide[side]) if (a.alive(now) && abs(a.x - h.x) < 95f && abs(a.y - h.y) < 95f) allyPow += a.power()
        // Towers on either side change the balance.
        for (st in active) if (!st.dead && st.shoots && abs(st.s.x - h.x) < 55f && abs(st.s.y - h.y) < 55f) { if (st.s.team == side) allyPow += R.towerDps * 2.2f else nearPow += R.towerDps * 2.6f }
        val ratio = allyPow / max(1f, nearPow)
        val laningNow = now / 60.0 < cfg.laneEnd
        val aggression = (teams[side].aggression / 100.0).toFloat()
        fun burstOf(x: Hero) = x.atk() * x.attackSpeed() * 3f + x.apPower() * 95f * readyCount(x)
        // Kill pressure counts every ally close to the target (a gank or a 2v2 all-in), not one hero alone.
        var burst = burstOf(h)
        if (now / 60.0 < cfg.laneEnd) for (a in bySide[side]) if (a !== h && a.alive(now) && abs(a.x - best.x) < 60f && abs(a.y - best.y) < 60f) burst += burstOf(a) * 0.8f
        val killable = best.hp + best.shield < burst
        val committed = h.focus >= 0 && h.lastCombat >= now - 3
        if (!committed) h.engagedAt = now
        // Our jungler is ganking this enemy (or our team is collapsing on him): the laner joins in.
        val gankOn = laningNow && bySide[side].any { it !== h && it.alive(now) && it.task == 5 && (it.camp == best.gi || it.huntTarget == best.gi) && dist(it, best) < 70f }
        if (laningNow && h.jungler && h.task == 5 && h.camp == best.gi && !committed) MobaEngine.dbg?.let { d -> map.laneIds.indexOf(best.laneMap).takeIf { it in 0..2 }?.let { d.incrementAndGet(6 + it) } }
        val engage = when {
            // A gank in the laning phase goes in with the numbers (the jungler waits for its laner); later collapses as before.
            h.task == 5 && (h.camp == best.gi || h.huntTarget == best.gi) -> ratio > (if (laningNow) 1.15f else 0.75f) || (killable && ratio > 0.8f)
            gankOn -> ratio > 0.85f
            laningNow && !committed -> (killable && ratio > 1.0f) || (ratio > 1.9f && best.hpPct() < 0.45f)
            h.task == 2 || h.task == 3 -> ratio > 0.92f + R.caution - aggression * 0.12f || (committed && ratio > 0.75f) || killable && ratio > 0.8f + R.caution
            else -> ratio > 1.3f + R.caution - aggression * 0.2f || (killable && ratio > 0.95f + R.caution) || (committed && ratio > 0.85f)
        }
        val flee = (ratio < 0.72f - h.clutch * 0.04f && !killable) || (h.hpPct() < 0.25f && !killable) || (laningNow && h.hpPct() < 0.4f && ratio < 1.1f) ||
            (committed && now - h.engagedAt > 7 && dist(h, best) > h.range + 25f)
        if (flee) {
            h.focus = -1
            for (i in h.acts.indices) { val a = h.acts[i]; if ((a.ai == Abilities.AI_MOBILITY || a.ai == Abilities.AI_SURVIVE) && ready(h, i) && dist(h, best) < 45f) { cast(h, i, best); break } }
            if (h.hpPct() < 0.22f && h.flashReady <= nowF && dist(h, best) < 30f && t.id != "dota2") {
                val f = fountain(side); push(h, f[0], f[1], 30f); h.flashReady = nowF + 150f
                ev(Ev(now, "cast", side, h.pc.p.id, -1, 4, m = 1 shl Abilities.DASH, n = MobaEngine.packPos(h.x, h.y)))
            }
            val f = fountain(side); moveTo(h, f[0], f[1])
            return true
        }
        if (!engage) {
            if (laningNow && h.lane >= 0 && dist(h, best) < 75f && h.hpPct() > 0.5f) {
                for (i in h.acts.indices) { val a = h.acts[i]; if (!a.ult && a.ai == Abilities.AI_POKE && ready(h, i) && rng.chance((0.3 * h.laning + comp[side].counter * 0.05).coerceIn(0.05, 0.8))) { if (cast(h, i, best)) break } }
                // Short trades: the laner ahead on health trades basic attacks (solo lanes see more of them).
                if (best.lane == h.lane && h.hpPct() > best.hpPct() + 0.08f && dist(h, best) <= h.range + 6f && rng.chance(((if (h.lane == 1 || cfg.lanes.getOrNull(h.lane)?.second?.size == 1) 0.35 else 0.2) * h.laning).coerceIn(0.05, 0.6))) {
                    damage(h, side, best, h.atk() * h.attackSpeed() * h.execution * DMG_PACE * DTF, 0)
                }
            }
            return false
        }
        h.focus = best.gi
        h.lastCombat = now
        if (h.recallUntil > 0) h.recallUntil = 0
        // Abilities: by AI tag and situation (one cast per second of the step).
        val n = near
        var castsThisTick = 0
        for (i in h.acts.indices) {
            val a = h.acts[i]
            if (!ready(h, i)) continue
            val use = when (a.ai) {
                Abilities.AI_ENGAGE, Abilities.AI_CONTROL -> !best.cc(nowF) && (!a.ult || n >= 2 || killable || best.marksman || best.mage)
                Abilities.AI_POKE, Abilities.AI_ZONE -> true
                Abilities.AI_BURST -> !a.ult || killable || n >= 2 || best.hpPct() < 0.6f
                Abilities.AI_FINISH -> best.hpPct() < 0.45f
                Abilities.AI_PROTECT -> lowestAlly(h, 60f)?.let { it.hpPct() < 0.65f } ?: (h.hpPct() < 0.6f)
                Abilities.AI_SURVIVE -> h.hpPct() < 0.55f
                Abilities.AI_MOBILITY -> dist(h, best) > h.range + 8f
                Abilities.AI_BUFF -> true
                else -> n >= 1
            }
            if (!use) continue
            if (a.target == Abilities.T_ALLY || (a.ai == Abilities.AI_PROTECT && a.fx.any { it.ally })) { if (cast(h, i, best, lowestAlly(h, 60f) ?: h)) break }
            else if (cast(h, i, best)) { castsThisTick++; if (castsThisTick >= DT) break }
        }
        // Dota 2 Black King Bar for cores in big fights.
        if (t.id == "dota2" && h.items >= 3 && !h.supportCls && n >= 3 && h.bkbReady <= nowF) { h.immuneUntil = nowF + 5f; h.bkbReady = nowF + 75f; ev(Ev(now, "cast", side, h.pc.p.id, -1, 4, m = 1 shl Abilities.IMMUNE, n = MobaEngine.packPos(h.x, h.y))) }
        // Basic attack in range, else close in.
        val d = dist(h, best)
        if (d <= h.range + 2f) {
            damage(h, side, best, h.atk() * h.attackSpeed() * h.execution * DMG_PACE * DTF, 0)
            // Kiting ranged carries step back a little.
            if (h.range > 25f && d < h.range * 0.6f) { val dx = h.x - best.x; val dy = h.y - best.y; val l = max(1f, hypot(dx, dy)); push(h, h.x + dx / l * 6f * DTF, h.y + dy / l * 6f * DTF, 6f * DTF) }
        } else moveTo(h, best.x, best.y)
        return true
    }
}

/** Fast distance (Math.hypot is overflow-safe and slow; map values are small). */
@Suppress("NOTHING_TO_INLINE")
private inline fun hypot(x: Float, y: Float): Float = sqrt(x * x + y * y)

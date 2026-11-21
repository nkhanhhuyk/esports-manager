package dev.uem.em.sim

import dev.uem.em.core.Rng
import dev.uem.em.titles.CharDef
import dev.uem.em.titles.TitleDef
import java.util.concurrent.ConcurrentHashMap

/**
 * Counters, synergies and kit strength between characters, from their kits (crowd control,
 * mobility, range, sustain, burst, stealth / reveal, area effects, durability) and classes:
 * lockdown beats mobile divers, divers beat immobile carries, range kites short-range immobile
 * characters, sustain outlasts poke, reveal answers stealth, burst punishes fragile targets.
 * Each patch shifts matchups a little (balance changes reach some interactions more than others),
 * so counters change from patch to patch. Used by the draft AI, the match engine (lane matchups and
 * fights), the character pages and the scouting report.
 */
object Counters {
    /** Kit features of a character as percentiles within its game (0..1). */
    class Profile(
        val hardCc: Float, val cc: Float, val mob: Float, val range: Float, val sustain: Float, val burst: Float,
        val tanky: Float, val stealth: Float, val reveal: Float, val aoe: Float, val engage: Float, val power: Float,
    ) {
        val squishy get() = 1f - tanky
        val poke get() = range * (1f - mob * 0.5f)
    }

    private class Table(val ids: List<String>, val index: Map<String, Int>, val profiles: Array<Profile>)
    private val tables = ConcurrentHashMap<String, Table>()
    private val matrices = ConcurrentHashMap<Patch, FloatArray>()

    private val TANKY = Regex("tank|guardian|vanguard|sentinel|defender|front|brawler|fighter|warrior|bruiser|str")
    private val CLASS_RULES = listOf(
        Triple(Regex("assassin|flank|speedster|skirmisher"), Regex("marksman|mage|sniper|sharpshooter|hunter|carry|artillery|support|healer|strategist|enchanter"), 0.5f),
        Triple(Regex("tank|guardian|vanguard|front|defender|brawler"), Regex("assassin|flank|speedster|fighter|warrior"), 0.4f),
        Triple(Regex("marksman|hunter|sharpshooter|carry|damage|duelist"), Regex("tank|guardian|vanguard|front|defender"), 0.45f),
        Triple(Regex("mage|nuker|caster|controller|artillery"), Regex("fighter|warrior|bruiser|all-rounder|tank"), 0.35f),
        Triple(Regex("fighter|warrior|bruiser|all-rounder"), Regex("marksman|mage|support|enchanter"), 0.3f),
        Triple(Regex("initiator|intel|recon"), Regex("sentinel|anchor|trapper|controller"), 0.3f),
        Triple(Regex("sentinel|anchor|trapper"), Regex("duelist|entry"), 0.35f),
        Triple(Regex("duelist|entry"), Regex("initiator|controller|intel"), 0.25f),
    )

    private fun raw(t: TitleDef, c: CharDef): FloatArray {
        val f = FloatArray(11)
        val cls = c.cls.lowercase() + " " + c.classes.joinToString(" ").lowercase()
        if (t.isMoba) {
            val k = Abilities.kit(c.id, c)
            for (a in k.abilities) {
                val weight = if (a.ult) 1.4f else 1f
                for (x in a.fx) {
                    when {
                        Abilities.hard(x.k) -> { f[0] += weight * (0.6f + x.t.coerceIn(0f, 3f) * 0.4f); f[1] += weight }
                        x.k == Abilities.ROOT || x.k == Abilities.SILENCE -> { f[0] += weight * 0.5f; f[1] += weight }
                        x.k == Abilities.SLOW -> f[1] += weight * 0.5f
                        x.k == Abilities.DASH || x.k == Abilities.BUFF_MS -> f[2] += weight
                        x.k == Abilities.HEAL || x.k == Abilities.SHIELD -> f[4] += weight * (0.5f + x.p.coerceIn(0f, 2f) * 0.3f)
                        x.k == Abilities.DMG || x.k == Abilities.EXECUTE -> f[5] += weight * x.p.coerceIn(0.2f, 3f) / a.cdAt(1).coerceIn(3f, 120f) * 10f
                        x.k == Abilities.DOT -> f[5] += weight * 0.3f
                        x.k == Abilities.DMG_REDUCE || x.k == Abilities.IMMUNE -> f[6] += weight
                        x.k == Abilities.STEALTH -> f[7] += weight
                        x.k == Abilities.REVEAL -> f[8] += weight
                    }
                }
                if (a.target in setOf(Abilities.T_SELF_AOE, Abilities.T_AREA, Abilities.T_CONE) && (a.damage != null || a.cc)) f[9] += weight
                if (a.ai == Abilities.AI_ENGAGE || (a.hardCc && a.fx.any { it.k == Abilities.DASH })) f[10] += weight
                if (a.damage != null) f[3] = maxOf(f[3], a.range)
            }
            f[3] = maxOf(f[3] * 0.6f, if (k.ranged) k.basicRange * 1.2f else k.basicRange * 0.5f)
        } else {
            // Shooters (Valorant agents): class decides.
            if (Regex("initiator|controller").containsMatchIn(cls)) { f[0] += 1f; f[9] += 1f }
            if (Regex("duelist").containsMatchIn(cls)) { f[2] += 1.5f; f[5] += 1f }
            if (Regex("sentinel").containsMatchIn(cls)) { f[6] += 1f; f[8] += 1f; f[1] += 1f }
            if (Regex("initiator").containsMatchIn(cls)) f[8] += 1.5f
            f[3] = 1f
        }
        if (TANKY.containsMatchIn(cls)) f[6] += 1.5f
        if (Regex("tank|guardian|vanguard").containsMatchIn(cls)) f[6] += 1f
        return f
    }

    private fun table(t: TitleDef): Table {
        val key = t.id + "|" + t.characters.size + "|" + (t.characters.lastOrNull()?.id ?: "")
        return tables.getOrPut(key) {
            val chars = t.characters
            val raws = chars.map { raw(t, it) }
            // Percentiles within the game for each feature.
            val pct = Array(chars.size) { FloatArray(11) }
            for (k in 0 until 11) {
                val order = raws.indices.sortedBy { raws[it][k] }
                for ((r, i) in order.withIndex()) pct[i][k] = if (chars.size <= 1) 0.5f else r.toFloat() / (chars.size - 1)
                // Ties at zero count as zero (a kit without stealth has none, whatever the rank).
                for (i in raws.indices) if (raws[i][k] <= 0f) pct[i][k] = 0f
            }
            val profiles = Array(chars.size) { i ->
                val p = pct[i]
                val power = (p[5] * 1f + p[0] * 0.8f + p[4] * 0.6f + p[2] * 0.4f + p[9] * 0.5f + p[6] * 0.5f) / 3.8f
                Profile(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9], p[10], power)
            }
            Table(chars.map { it.id }, chars.withIndex().associate { it.value.id to it.index }, profiles)
        }
    }

    fun profile(t: TitleDef, id: String): Profile? = table(t).let { tb -> tb.index[id]?.let { tb.profiles[it] } }

    /** Kit strength in the engine (0..1 percentile within the game). */
    fun power(t: TitleDef, id: String): Double = profile(t, id)?.power?.toDouble() ?: 0.5

    private fun edge(a: Profile, b: Profile): Float {
        var s = 0f
        s += 0.9f * a.hardCc * b.mob * (0.5f + 0.5f * b.squishy)          // lockdown beats mobile divers
        s += 0.8f * a.mob * a.burst * (1f - b.mob) * (1f - b.hardCc * 0.7f) * b.squishy // dive immobile carries
        s += 0.6f * a.range * (1f - b.range) * (1f - b.mob)                 // kite short-range, immobile
        s += 0.5f * a.sustain * b.poke * (1f - b.burst * 0.5f)               // sustain outlasts poke
        s += 0.5f * a.burst * b.squishy * (1f - b.sustain * 0.6f)           // burst punishes fragile targets
        s += 0.6f * a.reveal * b.stealth                                     // reveal answers stealth
        s += 0.35f * a.tanky * b.burst * (1f - b.sustain)                    // durability absorbs burst
        return s
    }

    private fun classEdge(a: CharDef, b: CharDef): Float {
        val ca = a.cls.lowercase(); val cb = b.cls.lowercase()
        var s = 0f
        for ((x, y, v) in CLASS_RULES) { if (x.containsMatchIn(ca) && y.containsMatchIn(cb)) s += v; if (x.containsMatchIn(cb) && y.containsMatchIn(ca)) s -= v }
        return s
    }

    /** All matchups of a game for a patch: m[a * n + b] = how well a plays into b (−1..1). */
    private fun matrix(t: TitleDef, patch: Patch): Pair<Table, FloatArray> {
        val tb = table(t)
        val n = tb.ids.size
        val m = matrices[patch]?.takeIf { it.size == n * n } ?: run {
            if (matrices.size > 48) matrices.clear()
            val out = FloatArray(n * n)
            val chars = t.characters
            for (i in 0 until n) for (j in i + 1 until n) {
                val base = (edge(tb.profiles[i], tb.profiles[j]) - edge(tb.profiles[j], tb.profiles[i])) / 0.7f + classEdge(chars[i], chars[j]) * 0.5f
                // Patch drift: part changes every patch, part every few patches.
                val r1 = Rng.derive(patch.seedKey, "ctr", t.id, patch.n, tb.ids[i], tb.ids[j]).normal(0.0, 0.16).toFloat()
                val r2 = Rng.derive(patch.seedKey, "ctr3", t.id, patch.n / 3, tb.ids[i], tb.ids[j]).normal(0.0, 0.12).toFloat()
                val v = (base + r1 + r2).coerceIn(-1f, 1f)
                out[i * n + j] = v; out[j * n + i] = -v
            }
            matrices[patch] = out
            out
        }
        return tb to m
    }

    /** How well `a` plays into `b` on this patch (−1..1; 0 for unknown). */
    fun score(t: TitleDef, patch: Patch, a: String, b: String): Double {
        val (tb, m) = matrix(t, patch)
        val i = tb.index[a] ?: return 0.0
        val j = tb.index[b] ?: return 0.0
        return m[i * tb.ids.size + j].toDouble()
    }

    /** Characters that counter `id` (best first) and characters `id` counters. */
    fun countersOf(t: TitleDef, patch: Patch, id: String, n: Int = 6): Pair<List<Pair<String, Double>>, List<Pair<String, Double>>> {
        val (tb, m) = matrix(t, patch)
        val j = tb.index[id] ?: return emptyList<Pair<String, Double>>() to emptyList()
        val size = tb.ids.size
        val vs = tb.ids.indices.filter { it != j }.map { i -> tb.ids[i] to m[i * size + j].toDouble() }
        return vs.sortedByDescending { it.second }.take(n).filter { it.second > 0.15 } to vs.sortedBy { it.second }.take(n).filter { it.second < -0.15 }.map { it.first to -it.second }
    }

    /** How well two characters work together (0..1): engage + area follow-up, protection + carry, chained control. */
    fun synergy(t: TitleDef, a: String, b: String): Double {
        val pa = profile(t, a) ?: return 0.0
        val pb = profile(t, b) ?: return 0.0
        fun one(x: Profile, y: Profile) = 0.5f * x.engage * y.aoe + 0.45f * x.sustain * y.range * y.squishy + 0.35f * x.hardCc * y.burst + 0.25f * x.tanky * y.range
        return ((one(pa, pb) + one(pb, pa)) / 1.1f).coerceIn(0f, 1f).toDouble()
    }
}

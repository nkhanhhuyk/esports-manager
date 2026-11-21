package dev.uem.em.sim

import dev.uem.em.model.Replay
import dev.uem.em.titles.TitleDef

/**
 * The authoritative event stream of one game. The engines write every event that changes the game
 * (kills with assists and respawn times, fights, structures, objectives, buybacks; rounds with
 * buys, kills with location and assister, plants, defuses) plus state snapshots, while they
 * simulate. Recording never draws random numbers, so a recorded game is identical to an
 * unrecorded one; the 2D view and the box score both read the same game.
 */
class Recorder {
    val events = ArrayList<Ev>()
    val snapT = ArrayList<Int>()
    val snap = ArrayList<Int>()
    var pids: IntArray = IntArray(0)

    fun add(e: Ev) { events.add(e) }

    fun snapshot(t: Int, values: IntArray) {
        snapT.add(t)
        for (v in values) snap.add(v)
    }
}

object ReplayCodec {
    const val VERSION = 1

    /** Event kinds (index = code). */
    val KINDS = listOf("kill", "fight", "tower", "base", "core", "obj", "end", "round", "plant", "defuse", "roundEnd", "buyback")
    private val KIND_CODE = KINDS.withIndex().associate { it.value to it.index }

    /** Shooter buy types (index = code). */
    val BUYS = listOf("pistol", "full", "force", "eco")

    /** Kill location codes for shooters (event m): approach / entry, on the site, after the plant. */
    const val LOC_APPROACH = 0
    const val LOC_SITE = 1
    const val LOC_POSTPLANT = 2

    fun encode(rec: Recorder): Replay {
        val ev = IntArray(rec.events.size * 9)
        rec.events.forEachIndexed { i, e ->
            val o = i * 9
            ev[o] = e.t; ev[o + 1] = KIND_CODE[e.kind] ?: -1; ev[o + 2] = e.team; ev[o + 3] = e.a; ev[o + 4] = e.b; ev[o + 5] = e.x; ev[o + 6] = e.m; ev[o + 7] = e.n
        }
        return Replay(VERSION, ev, rec.pids, rec.snapT.toIntArray(), rec.snap.toIntArray())
    }

    /** Events with names restored (objective names, buy types). */
    fun events(r: Replay, t: TitleDef): List<Ev> {
        val n = r.ev.size / 9
        return List(n) { i ->
            val o = i * 9
            val kind = KINDS.getOrElse(r.ev[o + 1]) { "?" }
            val text = when (kind) {
                "obj" -> t.moba?.objectives?.getOrNull(r.ev[o + 5])?.name ?: ""
                "round" -> BUYS.getOrElse(r.ev[o + 6] / 4) { "" } + "|" + BUYS.getOrElse(r.ev[o + 6] % 4) { "" }
                else -> ""
            }
            Ev(r.ev[o], kind, r.ev[o + 2], r.ev[o + 3], r.ev[o + 4], r.ev[o + 5], text, r.ev[o + 6], r.ev[o + 7])
        }
    }

    /** Values per player per snapshot. */
    const val PER_PLAYER = 4

    /** Snapshot index at or before time t (MOBA: seconds; shooters: round). */
    fun snapAt(r: Replay, t: Int): Int {
        var lo = -1
        for (i in r.snapT.indices) if (r.snapT[i] <= t) lo = i else break
        return lo
    }

    fun value(r: Replay, snapIdx: Int, pid: Int, field: Int): Int {
        if (snapIdx < 0) return 0
        val pi = r.pids.indexOf(pid)
        if (pi < 0) return 0
        return r.snap.getOrElse((snapIdx * r.pids.size + pi) * PER_PLAYER + field) { 0 }
    }
}

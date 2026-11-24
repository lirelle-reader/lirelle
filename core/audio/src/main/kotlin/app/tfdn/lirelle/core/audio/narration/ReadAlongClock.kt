package app.tfdn.lirelle.core.audio.narration

import kotlin.math.roundToLong

private const val MillisPerSecond = 1000.0
private const val Percent = 100.0

/**
 * The server's narration clock against the map's timeline. The clock counts clip durations only, in reading
 * order (the server's narration `seconds`); the timeline counts whole segments, pauses between their clips
 * included. They agree wherever the clips leave no gap. Times out of the map are clamped to it.
 */
interface ReadAlongClock {
    /**
     * The server's narration seconds: the clip durations before the sentence plus the time into it.
     * The clock stands still between two clips of a segment.
     */
    fun clockSeconds(timeMs: Long): Double

    /** The time on the timeline of [seconds] on the clock: inside the clip that holds them. */
    fun timeOfClock(seconds: Double): Long

    /** How far into the narration [timeMs] is, from 0 to 100: the clock over the clock's total. */
    fun percentAt(timeMs: Long): Double
}

internal class ClipClock(private val clips: ReadAlongClips) : ReadAlongClock {

    override fun clockSeconds(timeMs: Long): Double = clockMs(timeMs) / MillisPerSecond

    override fun timeOfClock(seconds: Double): Long {
        if (clips.size == 0) return 0
        val clock = (seconds * MillisPerSecond).roundToLong().coerceIn(0, clips.clockTotalMs)
        val index = lastAtOrBefore(clips.clockStartMs, clock).coerceAtLeast(0)
        return clips.startMs[index] + (clock - clips.clockStartMs[index]).coerceAtMost(clips.durationMs[index])
    }

    override fun percentAt(timeMs: Long): Double =
        if (clips.clockTotalMs <= 0) 0.0 else clockMs(timeMs) * Percent / clips.clockTotalMs

    private fun clockMs(timeMs: Long): Long {
        if (clips.size == 0) return 0
        val time = timeMs.coerceIn(0, clips.totalMs)
        val index = lastAtOrBefore(clips.startMs, time).coerceAtLeast(0)
        return clips.clockStartMs[index] + (time - clips.startMs[index]).coerceIn(0, clips.durationMs[index])
    }
}

package app.tfdn.lirelle.core.audio.timer

private const val ShortRewindMs = 2_000L
private const val LongRewindMs = 10_000L
private const val ShortPauseMs = 5 * 60_000L
private const val LongPauseMs = 60 * 60_000L

/** How far back playback restarts after a pause: what was said just before is lost the longer it lasted. */
object ResumeRewind {
    /**
     * 2 s up to a 5 minute pause, growing linearly to 10 s at one hour, 10 s beyond. 0 when
     * [enabled] is false.
     */
    fun rewindMs(pausedForMs: Long, enabled: Boolean): Long = when {
        !enabled -> 0L
        pausedForMs <= ShortPauseMs -> ShortRewindMs
        pausedForMs >= LongPauseMs -> LongRewindMs
        else -> ShortRewindMs + (LongRewindMs - ShortRewindMs) * (pausedForMs - ShortPauseMs) /
            (LongPauseMs - ShortPauseMs)
    }

    /** Where to resume from [positionMs] (in the book), never before its start. */
    fun resumeAt(positionMs: Long, pausedForMs: Long, enabled: Boolean): Long =
        (positionMs - rewindMs(pausedForMs, enabled)).coerceAtLeast(0L)
}

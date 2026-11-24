package app.tfdn.lirelle.core.audio.session

import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.reader.sync.SessionClock
import java.time.Clock
import javax.inject.Inject

/** A pause this long closes the session, as [SessionClock] does. */
private const val IdleMillis = 5 * 60_000L

/** The fastest speed: no listening covers more of the book than this many times its duration. */
private const val MaxSpeed = 3L

private const val Percent = 100.0

/** While playing, the session so far is reported at every 5 minutes of listening. */
private const val CheckpointEverySeconds = 300

/**
 * Measures the listening sessions of one book, with the rules of the reading ones ([SessionClock]):
 * a pause of 5 minutes or more closes the session, a session of less than 10 active seconds is
 * not reported. Playing is the "reading", a pause is the "background". Call it from one thread.
 *
 * Its progress is the book's, in percentage points as the reading sessions send it: from the
 * percentage at the session's first tick where it is known (the book's length known, a place in it)
 * to the last one. The gain never goes beyond what the listening itself covered: the book time
 * played between two ticks, at most three times the time between them (a seek or a jump covers
 * nothing), over the book's length. Replaying the last seconds of a finished book is then a gain of
 * those seconds, not of the whole book, which the server would take for a new reading of it.
 *
 * While playing, the session so far is also reported at each 5 minutes of its listening ([due]), so that
 * a process ended while playing loses at most that much of it; a pause reports it as before.
 */
class ListeningSessionClock(
    private val monotonic: MonotonicClock,
    wall: Clock,
    newId: () -> String = Default,
) {
    private val clock = SessionClock(monotonic, wall, newId)
    private var paused = false
    private var pausedAt = 0L

    private var startPercent: Double? = null
    private var lastPercent: Double? = null
    private var totalMs: Long? = null
    private var coveredMs = 0L

    /** How many 5 minutes of the session's listening were reported by [due]. */
    private var reportedFives = 0

    /** The book time and the monotonic time of the last tick while playing; null after a pause. */
    private var lastTick: Pair<Long, Long>? = null

    /**
     * Playback is going on at [bookTimeMs] of a book of [totalMs] (null when its length is not known):
     * call it on every position tick. After a pause of 5 minutes or more, the session that ended is
     * returned, to be sent (null when it was too short); this call starts the next one.
     */
    fun onPlaying(bookTimeMs: Long, totalMs: Long?): ReadingSessionReport? {
        val now = monotonic.nowMillis()
        val closing = paused && now - pausedAt >= IdleMillis
        val closed = if (paused) clock.onForeground()?.let(::withProgress) else null
        if (closing) forgetProgress()
        paused = false
        lastTick?.let { (time, at) ->
            val step = bookTimeMs - time
            if (step > 0) coveredMs += minOf(step, (now - at) * MaxSpeed)
        }
        lastTick = bookTimeMs to now
        val percent = totalMs?.takeIf { it > 0 }?.let { bookTimeMs.coerceIn(0, it) * Percent / it }
        if (percent != null) {
            this.totalMs = totalMs
            if (startPercent == null) startPercent = percent
            lastPercent = percent
        }
        clock.onReading(lastPercent ?: 0.0)
        return closed
    }

    /** Playback stopped. Follow it with [checkpoint] to have the session kept. */
    fun onPaused() {
        paused = true
        pausedAt = monotonic.nowMillis()
        lastTick = null
        clock.onBackground()
    }

    /** The session so far, which goes on; null when there is none or it is still too short. */
    fun checkpoint(): ReadingSessionReport? = clock.checkpoint()?.let(::withProgress)

    /**
     * While playing, after a tick: the session so far each time its listening passes another 5 minutes (5,
     * 10, 15…, whatever pauses reported in between); null otherwise.
     */
    fun due(): ReadingSessionReport? {
        val report = checkpoint() ?: return null
        val fives = report.activeSeconds / CheckpointEverySeconds
        if (fives <= reportedFives) return null
        reportedFives = fives
        return report
    }

    /** Ends the session and returns its report; null when there is none or it was too short. */
    fun close(): ReadingSessionReport? = clock.close()?.let(::withProgress).also {
        forgetProgress()
        paused = false
    }

    /** [report] with the book's progress over the session, its gain within what was listened to. */
    private fun withProgress(report: ReadingSessionReport): ReadingSessionReport {
        val end = lastPercent
        val start = startPercent
        val total = totalMs
        val delta = if (end == null || start == null || total == null) {
            null
        } else {
            (end - start).coerceAtMost(coveredMs * Percent / total)
        }
        return report.copy(progressDelta = delta, endPercent = end)
    }

    private fun forgetProgress() {
        startPercent = null
        lastPercent = null
        totalMs = null
        coveredMs = 0
        lastTick = null
        reportedFives = 0
    }

    private companion object {
        val Default: () -> String = { java.util.UUID.randomUUID().toString() }
    }
}

/** Makes the [ListeningSessionClock] of one listening. */
class ListeningSessionClockFactory @Inject constructor(private val monotonic: MonotonicClock, private val wall: Clock) {
    fun create(): ListeningSessionClock = ListeningSessionClock(monotonic, wall)
}

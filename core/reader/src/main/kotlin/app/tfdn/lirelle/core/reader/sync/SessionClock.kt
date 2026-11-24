package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

private const val MillisPerSecond = 1000L
private const val IdleMillis = 5 * 60 * MillisPerSecond
private const val ShortestSessionMillis = 10 * MillisPerSecond

/**
 * Measures the reading sessions of one book on screen. Not thread-safe: call it from one thread.
 *
 * - A session starts at the first [onReading] and has an id of its own.
 * - Time counts between two signals less than 5 minutes apart; a longer silence is a device left
 *   open on a page, and is not counted.
 * - Time in the background never counts, and an [onReading] that arrives there only moves the
 *   place. Back within 5 minutes, the session goes on; later, it is closed where it stopped and
 *   the next [onReading] starts another.
 * - A session of less than 10 active seconds is not reported: the server would drop it.
 *
 * @param monotonic measures the durations.
 * @param wall dates the start and the end.
 */
class SessionClock(
    private val monotonic: MonotonicClock,
    private val wall: Clock,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private class Running(val id: String, val startedAt: Instant, val startPercent: Double, now: Long) {
        var percent = startPercent
        var activeMillis = 0L
        var endedAt = startedAt

        /** Up to where the time was counted. */
        var countedTo = now
        var lastSignal = now
        var backgroundSince: Long? = null
    }

    private var running: Running? = null

    /** A session runs: the next [onReading] goes on with it rather than starting one. */
    val started: Boolean get() = running != null

    /**
     * The user is reading at [percent] (0 to 100): the book has just appeared, a page was turned,
     * the text was scrolled. Call it for the place the book opens at too, so that the progress of
     * the session is measured from there.
     */
    fun onReading(percent: Double) {
        val now = monotonic.nowMillis()
        val session = running
        if (session == null) {
            running = Running(newId(), wall.instant(), percent, now)
            return
        }
        session.percent = percent
        // A page turn reported after the reader left the front says where, not that someone reads.
        if (session.backgroundSince != null) return
        count(session, now)
        session.lastSignal = now
    }

    /** The reader is no longer in front. Follow it with [checkpoint] to have the session kept. */
    fun onBackground() {
        val session = running?.takeIf { it.backgroundSince == null } ?: return
        val now = monotonic.nowMillis()
        count(session, now)
        session.backgroundSince = now
    }

    /**
     * The reader is in front again. After 5 minutes away or more the session is over: its report
     * is returned, to be sent (null when it was too short), and the next [onReading] starts a new
     * one. Otherwise the session goes on and null is returned.
     */
    fun onForeground(): ReadingSessionReport? {
        val session = running ?: return null
        val since = session.backgroundSince ?: return null
        val now = monotonic.nowMillis()
        if (now - since >= IdleMillis) {
            running = null
            return report(session)
        }
        session.backgroundSince = null
        session.countedTo = now
        session.lastSignal = now
        return null
    }

    /**
     * The session so far, which goes on: same id every time, a duration that only grows. Null
     * when there is no session or when it is still too short.
     */
    fun checkpoint(): ReadingSessionReport? = running?.let { session ->
        count(session, monotonic.nowMillis())
        report(session)
    }

    /** Ends the session and returns its report; null when there is none or when it was too short. */
    fun close(): ReadingSessionReport? = checkpoint().also { running = null }

    private fun count(session: Running, now: Long) {
        if (session.backgroundSince == null && now - session.lastSignal < IdleMillis) {
            session.activeMillis += now - session.countedTo
            session.endedAt = wall.instant()
        }
        session.countedTo = now
    }

    private fun report(session: Running): ReadingSessionReport? =
        if (session.activeMillis < ShortestSessionMillis) {
            null
        } else {
            ReadingSessionReport(
                sessionId = session.id,
                startedAt = session.startedAt,
                endedAt = session.endedAt,
                activeSeconds = (session.activeMillis / MillisPerSecond).toInt(),
                progressDelta = session.percent - session.startPercent,
                endPercent = session.percent,
            )
        }
}

/** Makes the [SessionClock] of one reading. */
class SessionClockFactory @Inject constructor(private val monotonic: MonotonicClock, private val wall: Clock) {
    fun create(): SessionClock = SessionClock(monotonic, wall)
}

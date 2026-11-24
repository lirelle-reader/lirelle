package app.tfdn.lirelle.core.library

import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * When the screens that show the reading are told ([ReadingChanges]) that the server took the places a
 * player writes while it plays (an audiobook's listening positions, a read-along book's narration): not at
 * every write, which comes every 5 s while playing and would have them reload as often.
 *
 * - A place that settles (pause, seek, chapter or section change, timer stop, end, leaving, background) is
 *   told once the outbox is drained after it ([settle], then [drained]), provided something was saved since:
 *   the settled place itself, or the tick it was the same as. A drain without any save (nothing could be
 *   sent) keeps the settling waiting for the next one.
 * - While playing, a saved write is told at most every [every], counted from the first one, whether a
 *   settling waits or not.
 * - A save already told is not told again at the drain.
 *
 * [settle] may be called from any thread; the rest from the one flush running.
 */
class ChangeSignal(
    private val changes: ReadingChanges,
    private val clock: MonotonicClock,
    private val every: Duration = 60.seconds,
) {
    private val asked = AtomicLong(0)
    private var told = 0L
    private var lastAt: Long? = null

    /** A save happened since the last settling was taken care of. */
    private var savedSince = false

    /** A save happened that the screens were not told of. */
    private var untold = false

    /** A place settled: to be told once the outbox is drained. */
    fun settle() {
        asked.incrementAndGet()
    }

    /** The settlings asked so far: read before the outbox is, and handed back to [drained]. */
    fun asked(): Long = asked.get()

    /** A write was saved: told at most every [every]. */
    fun saved() {
        savedSince = true
        val now = clock.nowMillis()
        val last = lastAt
        when {
            last == null -> {
                lastAt = now
                untold = true
            }
            now - last >= every.inWholeMilliseconds -> tell(now)
            else -> untold = true
        }
    }

    /** Nothing is left to send of what the outbox held once [upTo] settlings were asked. */
    fun drained(upTo: Long) {
        if (upTo <= told || !savedSince) return
        told = upTo
        savedSince = false
        if (untold) tell(clock.nowMillis())
    }

    private fun tell(now: Long) {
        lastAt = now
        untold = false
        changes.changed()
    }
}

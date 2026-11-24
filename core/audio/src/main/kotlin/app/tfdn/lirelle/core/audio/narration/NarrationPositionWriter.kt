package app.tfdn.lirelle.core.audio.narration

import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.reader.sync.NarrationStore
import app.tfdn.lirelle.core.reader.sync.Outbox
import app.tfdn.lirelle.core.reader.sync.OutboxFlusher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * How much earlier than [NarrationPositionWriter]'s period a tick may come and still be sent: as for the
 * listening positions, a tick just short of 5 s must not wait for the next.
 */
private val TickTolerance = 200.milliseconds

/**
 * Keeps the narration of a read-along book on the device and hands it to the reading outbox, beside the
 * text positions, for the player. Safe to call from several coroutines: calls are taken one after the
 * other. The same narration again is not handed over again once it was.
 *
 * A place written once the profile closed (a sign-out with playback not stopped yet) goes to that profile's own
 * stores, which drop it: it is never stored nor sent under the next account.
 *
 * @param clock measures the time between two sends.
 * @param every the shortest time between two sends while playing.
 */
class NarrationPositionWriter(
    private val store: NarrationStore,
    private val outbox: Outbox,
    private val flusher: OutboxFlusher,
    private val clock: MonotonicClock,
    private val every: Duration = 5.seconds,
) {
    private val lock = Mutex()
    private var lastSent: LocalNarration? = null
    private var lastSentAt = 0L

    /** Now, on the clock the five seconds are measured by: for the player's tick to stamp [onTick] with. */
    fun now(): Long = clock.nowMillis()

    /**
     * While playing: stored at once, sent at most every 5 s, timed by [tickedAt], when the player's tick
     * came ([now] read in it), not once this call got its turn. A narration of another file is sent at once.
     */
    suspend fun onTick(n: LocalNarration, tickedAt: Long = now()) = lock.withLock {
        store.put(n)
        val last = lastSent
        val due = last == null || last.fileId != n.fileId ||
            tickedAt - lastSentAt >= (every - TickTolerance).inWholeMilliseconds
        if (due) send(n, tickedAt)
    }

    /**
     * Pause, jump, section change, end, stop, leaving: stored and sent now, and the screens told once it is
     * ([OutboxFlusher.settleNarration]). It completes even when its caller is being cancelled.
     */
    suspend fun flushNow(n: LocalNarration) = withContext(NonCancellable) {
        lock.withLock {
            store.put(n)
            send(n, clock.nowMillis())
            flusher.settleNarration()
        }
    }

    private suspend fun send(n: LocalNarration, now: Long) {
        if (n == lastSent) return
        outbox.enqueueNarration(n)
        flusher.kick()
        lastSent = n
        lastSentAt = now
    }
}

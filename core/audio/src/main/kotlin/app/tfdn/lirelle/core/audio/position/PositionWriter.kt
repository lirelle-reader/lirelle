package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.library.MonotonicClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * How much earlier than [PositionWriter]'s period a tick may come and still be sent: ticks come every
 * 5 s give or take a little, and one just short of it must not wait for the next. Less than half of the
 * player's own half-second tick, so that a writer ticked that often still sends every 5 s.
 */
private val Tolerance = 200.milliseconds

/**
 * Keeps the listening position on the device and hands it to the outbox, for the player. Safe to
 * call from several coroutines: calls are taken one after the other.
 *
 * Each position is given its operation id here, a new one for each new position (what the caller
 * put in [LocalPlayback.operationId] is not used), and is made over the version known for its book
 * ([LocalPlayback.baseVersion]; none when none is known). The same position again (same book, file,
 * place and time) keeps its id, and is not handed over again once it was.
 *
 * A position made once the profile closed (a sign-out with playback not stopped yet) goes to that profile's own
 * stores, which drop it: it is never stored nor sent under the next account.
 *
 * @param clock measures the time between two sends.
 * @param every the shortest time between two sends while playing.
 */
class PositionWriter(
    private val store: PlaybackStore,
    private val outbox: PlaybackOutbox,
    private val flusher: PlaybackFlusher,
    private val clock: MonotonicClock,
    private val every: Duration = 5.seconds,
) {
    private val lock = Mutex()
    private var lastMade: LocalPlayback? = null
    private var lastSent: LocalPlayback? = null
    private var lastSentAt = 0L

    /** Now, on the clock the five seconds are measured by: for the player's tick to stamp [onTick] with. */
    fun now(): Long = clock.nowMillis()

    /**
     * While playing: stored at once, sent every 5 s, timed by [tickedAt], when the player's tick came ([now]
     * read in it), not once this call got its turn or the device's store answered, whose delays vary.
     */
    suspend fun onTick(position: LocalPlayback, tickedAt: Long = now()) = lock.withLock {
        val now = tickedAt
        val made = made(position)
        store.put(made)
        val last = lastSent
        val due = last == null || last.bookId != made.bookId ||
            now - lastSentAt >= (every - Tolerance).inWholeMilliseconds
        if (due) send(made, now)
        // Listening goes on: what counts towards keeping the device's position against a state from elsewhere.
        flusher.writes?.ticked(made.bookId, now)
    }

    /**
     * Pause, seek, chapter change, timer stop, end, leaving, background: stored and sent now, and the
     * screens told once it is ([PlaybackFlusher.settle]). It completes even when its caller is being
     * cancelled.
     */
    suspend fun flushNow(position: LocalPlayback) = withContext(NonCancellable) {
        lock.withLock {
            val now = clock.nowMillis()
            val made = made(position)
            store.put(made)
            send(made, now)
            // A pause, a seek, leaving: the listening counted from the ticks starts a new stretch.
            flusher.writes?.ticked(made.bookId, null)
            flusher.settle()
        }
    }

    private suspend fun made(position: LocalPlayback): LocalPlayback {
        val id = lastMade?.takeIf { it.isSamePosition(position) }?.operationId ?: newOperationId()
        val made = position.copy(operationId = id, baseVersion = store.knownVersion(position.bookId))
        lastMade = made
        return made
    }

    private suspend fun send(position: LocalPlayback, now: Long) {
        if (position.operationId == lastSent?.operationId) return
        outbox.enqueue(position)
        // A tick does not cut a wait after a failure short; a settling does ([PlaybackFlusher.settle]).
        flusher.kick(urgent = false)
        lastSent = position
        lastSentAt = now
    }
}

private fun LocalPlayback.isSamePosition(other: LocalPlayback) =
    bookId == other.bookId && assetId == other.assetId && positionMs == other.positionMs && at == other.at

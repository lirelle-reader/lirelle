package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.PlaybackState
import java.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Listening this long against the same state written elsewhere, without an answer, keeps this device's position. */
private const val KeepOursAfterMs = 120_000L

/**
 * The most one playing tick counts (they come every 5 s): a stretch is started again at each pause, seek or
 * leaving, so a longer gap is only a stalled player that went on playing, never a pause.
 */
private const val MaxTickGapMs = 30_000L

/** What the [PlaybackFlusher] tells of this device's writes, besides [PlaybackFlusher]'s own callbacks. */
interface PlaybackWriteEvents {
    /** The server took [position] ([acknowledged]), or may have: it was sent and no answer came. */
    suspend fun written(position: LocalPlayback, acknowledged: Boolean)

    /** [position] was dropped because [server] (null when it could not be read) was written elsewhere. */
    suspend fun dropped(position: LocalPlayback, server: PlaybackState?)

    /**
     * The version to send [position] over, from [base] (null: none known): the one the user settled its book on,
     * when [base] is an older one.
     */
    suspend fun baseFor(position: LocalPlayback, base: String?): String? = base

    /** Whether the positions of [bookId] are held back, not sent: a state written elsewhere stands, unanswered. */
    suspend fun holds(bookId: BookId): Boolean = false

    /**
     * [bookId] played on to [tickedAt] (milliseconds on a clock that only goes forward); null when playback
     * stopped or jumped (a pause, a seek, leaving): the next tick starts a new stretch of listening.
     */
    suspend fun ticked(bookId: BookId, tickedAt: Long?) = Unit
}

/**
 * The books whose position on the server was written elsewhere and stands against this device's
 * writes, which the flusher drops ([standing]): the player offers it (spec 8.3), and nothing of this
 * device overwrites it without the user's word. It lives as long as its profile is open, so that a conflict met
 * while no screen was open is offered when one opens. It also keeps the last write of each book the
 * server took or may have taken ([lastWritten]): "more recent than the player's last write" means that
 * one, not the position last stored on the device, which may be a dropped write.
 *
 * - "Ignore" ([keepOurs]): the server's version becomes the known one and the device's position is
 *   written at once over it, so that it wins, even while paused.
 * - "Go there" ([followServer]): the server's version becomes the known one; the player moves.
 * - **Not sent while it stands**: once the server's state is known, the book's positions are held back
 *   ([holds]): the latest waits in the outbox, nothing goes until an answer. Without that state (it could
 *   not be read) positions still go, so that the next dropped one reads it again.
 * - **Implicit ignore**: two minutes of listening while the offer waits keep the device's position as
 *   "Ignore" does. Listening is counted from the player's own ticks ([ticked], every 5 s while playing):
 *   the time from one to the next, a stretch started again at each pause, seek or leaving, so a pause
 *   adds nothing; whatever the network does meanwhile (a backoff, slow answers), it counts. Another state
 *   written elsewhere starts the count again. It needs no screen.
 * - **Settled**: a state in the book's known version (the one answered, or this device's own last write,
 *   which the known version remembers across restarts) is not written elsewhere. A state in any other version
 *   is: versions are compared for equality only, never ordered. A write
 *   dropped against it (made over an older base, or already on its way when the user answered) does not
 *   put the conflict back. After "Ignore", such a write is made again over the settled version, and any
 *   position made over an older base (one that is not the known version: this device's own writes since the
 *   answer keep the known version theirs) is sent over it ([baseFor]), so that "Ignore" is never lost to a
 *   write that was being made at that moment.
 *
 * Safe from any thread.
 *
 * @param kick asks the flusher to send what was just queued.
 */
class ListeningConflicts(
    private val store: PlaybackStore,
    private val outbox: PlaybackOutbox,
    private val clock: Clock,
    private val kick: () -> Unit,
) : PlaybackWriteEvents {
    /** The listening against the standing state in [version]. */
    private class Count(val version: String, var listenedMs: Long = 0)

    /** The version a book was settled on, and whether by keeping the device's position. */
    private data class Settled(val version: String, val keptOurs: Boolean)

    private val lock = Mutex()
    private val mutableStanding = MutableStateFlow<Map<BookId, PlaybackState?>>(emptyMap())
    private val counts = mutableMapOf<BookId, Count>()
    private val written = mutableMapOf<BookId, LocalPlayback>()

    /** By book, the last playing tick of the current stretch. */
    private val lastTicks = mutableMapOf<BookId, Long>()
    private val settled = mutableMapOf<BookId, Settled>()

    /** By book, the server's state that stands against this device's writes; null inside when not read. */
    val standing: StateFlow<Map<BookId, PlaybackState?>> = mutableStanding.asStateFlow()

    /**
     * This device's last writes of [bookId] the server took or may have taken, for [NewerListeningRule]: the
     * latest of this process, then the places of the last ones kept on the device ([PlaybackStore]) and a
     * write at no place with the time of the last one, so that after a restart its place and time still
     * tell this device's own state apart.
     */
    suspend fun ownWrites(bookId: BookId): List<LocalPlayback> {
        val latest = lock.withLock { written[bookId] }
        val at = store.lastWritten(bookId) ?: latest?.at ?: return listOfNotNull(latest)
        val places = store.writtenPlaces(bookId).map {
            LocalPlayback(bookId, it.assetId, it.positionMs, -1, at, "", null, "")
        }
        return listOfNotNull(latest) + places + LocalPlayback(bookId, "", -1, -1, at, "", null, "")
    }

    override suspend fun written(position: LocalPlayback, acknowledged: Boolean) = lock.withLock {
        val last = written[position.bookId]
        if (last == null || position.at.isAfter(last.at)) written[position.bookId] = position
        if (acknowledged) {
            counts -= position.bookId
            mutableStanding.update { it - position.bookId }
        }
    }

    override suspend fun dropped(position: LocalPlayback, server: PlaybackState?) = lock.withLock {
        val id = position.bookId
        val known = store.knownVersion(id)
        if (server != null && server.version == known) {
            // Not written elsewhere since: ours, or answered. After "Ignore", the device's position again.
            settled[id]?.takeIf { it.keptOurs }?.let { settleOn(id, it.version, keptOurs = true) }
            return@withLock
        }
        settled -= id
        mutableStanding.update { it + (id to server) }
    }

    override suspend fun holds(bookId: BookId): Boolean = lock.withLock { mutableStanding.value[bookId] != null }

    override suspend fun ticked(bookId: BookId, tickedAt: Long?) = lock.withLock {
        val last = lastTicks[bookId]
        if (tickedAt == null) {
            lastTicks -= bookId
            return@withLock
        }
        lastTicks[bookId] = tickedAt
        val server = mutableStanding.value[bookId] ?: return@withLock
        val count = counts[bookId]?.takeIf { it.version == server.version } ?: Count(server.version)
        counts[bookId] = count
        if (last != null) count.listenedMs += (tickedAt - last).coerceIn(0L, MaxTickGapMs)
        if (count.listenedMs >= KeepOursAfterMs) settleOn(bookId, server.version, keptOurs = true)
    }

    /**
     * After "Ignore", a [base] older than the settled version goes over the settled version. Older is not the
     * known version: the known version is the settled one, or the one this device's own writes got over it since,
     * which stay. (Before versions were opaque, this was "a base lower than the settled revision".)
     */
    override suspend fun baseFor(position: LocalPlayback, base: String?): String? = lock.withLock {
        val kept = settled[position.bookId]?.takeIf { it.keptOurs } ?: return@withLock base
        if (base == store.knownVersion(position.bookId)) base else kept.version
    }

    /**
     * The user answered: the book is settled on the server's [version] ([keepOurs] for "Ignore",
     * [followServer] for "Go there").
     */
    suspend fun settle(bookId: BookId, version: String, keptOurs: Boolean) =
        lock.withLock { settleOn(bookId, version, keptOurs) }

    /** The book is no longer the player's: what stands for it is read again when it is opened. */
    suspend fun forget(bookId: BookId) = lock.withLock {
        counts -= bookId
        mutableStanding.update { it - bookId }
        settled -= bookId
    }

    /**
     * The book is settled on the server's [version], which the next writes are made over. With [keptOurs], the
     * device is there now: its latest position is written at once, a new write made now, over that version.
     */
    private suspend fun settleOn(bookId: BookId, version: String, keptOurs: Boolean) {
        store.setKnownVersion(bookId, version)
        counts -= bookId
        mutableStanding.update { it - bookId }
        settled[bookId] = Settled(version, keptOurs)
        if (!keptOurs) return
        val latest = store.get(bookId) ?: return
        outbox.enqueue(latest.copy(operationId = newOperationId(), baseVersion = version, at = clock.instant()))
        kick()
    }
}

/** "Ignore": the device's position wins, written at once over the server's [version]. */
suspend fun ListeningConflicts.keepOurs(bookId: BookId, version: String) = settle(bookId, version, keptOurs = true)

/** "Go there": the next writes are made over the server's [version]. */
suspend fun ListeningConflicts.followServer(bookId: BookId, version: String) =
    settle(bookId, version, keptOurs = false)

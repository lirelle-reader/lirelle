package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.library.model.ReadingSessionReport
import app.tfdn.lirelle.core.reader.sync.Outbox
import app.tfdn.lirelle.core.reader.sync.OutboxFlusher
import app.tfdn.lirelle.core.reader.sync.PositionSync
import app.tfdn.lirelle.core.reader.sync.PositionSyncFactory
import app.tfdn.lirelle.core.reader.sync.RestoredPosition
import app.tfdn.lirelle.core.reader.sync.SessionClock
import app.tfdn.lirelle.core.reader.sync.SessionClockFactory
import javax.inject.Inject
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val Hundred = 100.0

/**
 * A scope on the main thread that lives as long as the profile: what is written when the reader is
 * left, or goes to the background, must not stop with the screen.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReaderLeaveScope

/**
 * The position and the session clock of one book on screen, used in the order the server needs:
 * a position before the session that ends on it. Call it from the main thread; every step waits
 * for the one before.
 *
 * @param lasting where the writes of [paused] and [close] run: it outlives the view model.
 */
class ReadingSession internal constructor(
    internal val fileId: FileId,
    private val positions: PositionSync,
    private val clock: SessionClock,
    internal val outbox: Outbox,
    private val kick: () -> Unit,
    private val lasting: CoroutineScope,
) {
    private val order = Mutex()
    private val parking = NarrationParking(clock)
    private var closed = false

    /** Where the book opens: at its position, or with [fromStart] at its beginning, to be read again. */
    suspend fun restore(fromStart: Boolean = false): RestoredPosition =
        order.withLock { positions.restore(fromStart) }

    /**
     * Every relocation of the page, the one the book opens at included. [fraction] goes from 0 to 1.
     * With [peek], the user is looking elsewhere and may come back: the place is not the position yet.
     *
     * Without [byUser] nobody is reading: the place is kept as it is moved (stored and sent only when
     * [moved]), and no reading time is counted; with no session yet, one starts there, as at the place
     * a book opens at. No reading time counts while the narration plays either; a move of the user's
     * takes up a session the narration left off, once it no longer plays.
     */
    suspend fun relocated(
        cfi: String,
        fraction: Double,
        moved: Boolean,
        peek: Boolean = false,
        byUser: Boolean = true,
    ) = order.withLock {
        if (closed) return@withLock
        if (byUser && moved) parking.wake()?.let {
            outbox.enqueueSession(fileId, it)
            kick()
        }
        positions.onRelocated(cfi, fraction, moved, peek)
        // A place nobody read to still starts the session, as the place a book opens at does: no time counts yet.
        if (!parking.parked && (byUser || !clock.started)) clock.onReading(fraction * Hundred)
    }

    /** The relocation that brings the user back from looking elsewhere, to where the position is. */
    suspend fun returned(cfi: String, fraction: Double) = order.withLock {
        if (closed) return@withLock
        parking.wake()?.let {
            outbox.enqueueSession(fileId, it)
            kick()
        }
        positions.onReturned(cfi, fraction)
        if (!parking.parked) clock.onReading(fraction * Hundred)
    }

    /**
     * Whether the book's narration plays. Once it does, the session is left as when the reader goes to
     * the background, and stays so until the user moves in the book with the narration no longer playing:
     * then it goes on, or another one starts, as after the background.
     */
    fun narration(playing: Boolean) {
        if (!closed && parking.playing(playing)) paused()
    }

    /**
     * The last move goes to the outbox now, without the quiet period, and the outbox is asked to
     * send at once what it holds: someone is waiting for it.
     */
    suspend fun flush() = order.withLock {
        positions.flushNow()
        kick()
    }

    /** The reader goes to the background: the position, then the session so far. */
    fun paused() {
        if (closed) return
        lasting.launch {
            order.withLock {
                positions.flushNow()
                clock.onBackground()
                clock.checkpoint()?.let { outbox.enqueueSession(fileId, it) }
                kick()
            }
        }
    }

    /**
     * The reader is in front again. The position to offer, if the server holds a newer one elsewhere; none
     * without [offer], and the server is not asked.
     */
    suspend fun resumed(offer: Boolean = true): ReadingPosition? = order.withLock {
        if (closed) return@withLock null
        // Left off for the narration, the session waits for the user to read.
        if (!parking.parked) clock.onForeground()?.let {
            outbox.enqueueSession(fileId, it)
            kick()
        }
        if (offer) positions.newerOnServer() else null
    }

    /** "Go there": the caller then moves the page. */
    suspend fun accept(offered: ReadingPosition) = order.withLock { positions.accept(offered) }

    fun ignore(offered: ReadingPosition) = positions.ignore(offered)

    /** The reader is left: the position, then the session, which ends. Only the first call does anything. */
    fun close() {
        if (closed) return
        closed = true
        lasting.launch {
            order.withLock {
                positions.flushNow()
                clock.close()?.let { outbox.enqueueSession(fileId, it) }
                kick()
            }
        }
    }
}

/** Whether every position of the file has reached the server: none is waiting to be sent. */
suspend fun ReadingSession.positionSent(): Boolean = outbox.pending().positions.none { it.fileId == fileId }

/**
 * The session clock around a narration of the book: left off as in the background while it plays, until
 * the user moves in the book once it no longer does.
 */
private class NarrationParking(private val clock: SessionClock) {
    /** The clock was left off for the narration and waits for the user. */
    var parked = false
        private set
    private var playing = false

    /** True when the clock is to be left off now. */
    fun playing(now: Boolean): Boolean {
        val leaves = now && !parked
        playing = now
        if (now) parked = true
        return leaves
    }

    /** The clock taken up again, unless the narration plays; the session that ended meanwhile, if any. */
    fun wake(): ReadingSessionReport? {
        if (!parked || playing) return null
        parked = false
        return clock.onForeground()
    }
}

class ReadingSessionFactory @Inject constructor(
    private val positions: PositionSyncFactory,
    private val clocks: SessionClockFactory,
    private val outbox: Outbox,
    private val flusher: OutboxFlusher,
    @param:ReaderLeaveScope private val lasting: CoroutineScope,
) {
    /** [scope] is the view model's: the quiet period before a position is sent is waited there. */
    fun create(fileId: FileId, scope: CoroutineScope): ReadingSession =
        ReadingSession(fileId, positions.create(fileId, scope), clocks.create(), outbox, flusher::kick, lasting)
}

package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.reader.sync.Outbox
import app.tfdn.lirelle.core.reader.sync.PositionSync
import app.tfdn.lirelle.core.reader.sync.RestoredPosition
import app.tfdn.lirelle.core.reader.sync.SessionClock
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val Hundred = 100.0

/**
 * A scope on the main thread that lives as long as the profile: what is written when the comics reader is
 * left, or goes to the background, must not stop with the screen.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ComicsLeaveScope

/**
 * The position and the session clock of one album on screen, used in the order the server needs: a
 * position before the session that ends on it. Call it from the main thread; every step waits for the
 * one before, and none waits for the server while the others queue behind it.
 *
 * @param lasting where the writes of [paused] and [close] run: it outlives the view model.
 */
internal class ComicSession(
    private val fileId: FileId,
    private val positions: PositionSync,
    private val clock: SessionClock,
    private val outbox: Outbox,
    private val kick: () -> Unit,
    private val lasting: CoroutineScope,
) {
    private val order = Mutex()
    private var closed = false

    /**
     * The places the reader went to that are not kept yet, oldest first. They are noted the moment the
     * reader is there, before anything waits: whoever writes next, [close] included, keeps them all, in
     * their order, so that the last page is the position even when the reader is left meanwhile.
     */
    private val asked = ArrayDeque<Relocation>()

    /** Where the album opens: at its position, or with [fromStart] at its beginning, to be read again. */
    suspend fun restore(fromStart: Boolean): RestoredPosition = order.withLock { positions.restore(fromStart) }

    /**
     * The reader shows the page the server numbers [page], [fraction] of the way through the album (0 to
     * 1). With [moved] the user went there: it is the position. Without, it is where the album opened, or
     * where a position of the server took it: nothing is written. Reading time counts either way.
     */
    suspend fun relocated(page: Int, fraction: Double, moved: Boolean) {
        if (closed) return
        asked += Relocation(page, fraction, moved)
        order.withLock { keepAsked() }
    }

    /**
     * The last move goes to the outbox now, without the quiet period, and the outbox is asked to send at once
     * what it holds: someone is waiting for it.
     */
    suspend fun flush() = order.withLock {
        keepAsked()
        positions.flushNow()
        kick()
    }

    /** Whether nothing of the album's position is left to send: the server has the last one. */
    suspend fun positionSent(): Boolean = outbox.pending().positions.none { it.fileId == fileId }

    /** The reader goes to the background: the position, then the session so far. */
    fun paused() {
        if (closed) return
        lasting.launch {
            order.withLock {
                keepAsked()
                positions.flushNow()
                clock.onBackground()
                clock.checkpoint()?.let { outbox.enqueueSession(fileId, it) }
                kick()
            }
        }
    }

    /**
     * The reader is in front again. The position to offer, if the server holds a newer one elsewhere. The
     * server is asked without holding the others back: pages turn meanwhile.
     */
    suspend fun resumed(): ReadingPosition? {
        order.withLock {
            if (closed) return null
            clock.onForeground()?.let {
                outbox.enqueueSession(fileId, it)
                kick()
            }
        }
        return positions.newerOnServer()
    }

    /** "Go there": the caller then shows the page. */
    suspend fun accept(offered: ReadingPosition) = order.withLock {
        keepAsked()
        positions.accept(offered)
    }

    fun ignore(offered: ReadingPosition) = positions.ignore(offered)

    /**
     * The reader is left: the page it was on, then the position, then the session, which ends. Only the
     * first call does anything.
     */
    fun close() {
        if (closed) return
        closed = true
        lasting.launch {
            order.withLock {
                keepAsked()
                positions.flushNow()
                clock.close()?.let { outbox.enqueueSession(fileId, it) }
                kick()
            }
        }
    }

    /**
     * Keeps the places the reader went to, in their order. Call it with [order] held. A place leaves the
     * list once it is kept: one whose writing was cancelled with the view model is written again by [close].
     */
    private suspend fun keepAsked() {
        while (true) {
            val next = asked.firstOrNull() ?: return
            positions.onRelocated(cfi = null, fraction = next.fraction, moved = next.moved, page = next.page)
            clock.onReading(next.fraction * Hundred)
            asked.removeFirst()
        }
    }
}

/** A place the reader went to: the page as the server numbers it, how far into the album, whether the user moved. */
private class Relocation(val page: Int, val fraction: Double, val moved: Boolean)

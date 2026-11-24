package app.tfdn.lirelle.core.reader.sync

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.network.ApiResult
import java.time.Clock
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val Hundred = 100.0

/**
 * Where a book opens.
 *
 * @property cfi null when only the percentage is known: open at [fraction] then.
 * @property percent from 0 to 100; 0 with a null [cfi] is the very start.
 * @property page the page of a file read by pages (a comic), as the server counts it; null for a book, and
 *   when no page is known.
 */
data class RestoredPosition(val cfi: String?, val percent: Double, val page: Int? = null) {
    /** From 0 to 1, as the page counts. */
    val fraction: Double get() = percent / Hundred
}

/** The place the page says it is at, [fraction] of the way through the file, dated now. */
private fun Clock.position(fileId: FileId, cfi: String?, fraction: Double, page: Int? = null) =
    LocalPosition(fileId, cfi, (fraction * Hundred).coerceIn(0.0, Hundred), instant(), page)

/**
 * Keeps the position of one file in step between the page, the device and the server, for one
 * reading. Call it from one thread (the view model's).
 *
 * @param scope where the quiet period before a send is waited; cancelling it drops that wait,
 * so call [flushNow] before leaving.
 * @param kick asks the [OutboxFlusher] for a flush.
 * @param quiet how long the page must stay still before its position is sent.
 */
class PositionSync(
    private val fileId: FileId,
    private val scope: CoroutineScope,
    private val repository: ReaderRepository,
    private val store: PositionStore,
    private val outbox: Outbox,
    private val kick: () -> Unit,
    private val wall: Clock,
    private val quiet: Duration = 2.seconds,
) {
    private val rule = NewerPositionRule()
    private val sending = Mutex()

    /** Where the user is, as far as this reading knows. */
    private var place: LocalPosition? = null

    /** A move not yet handed to the outbox. */
    private var unsent: LocalPosition? = null
    private var wait: Job? = null

    /** Where a look elsewhere has taken the user, while it is not the reading position. */
    private var peek: LocalPosition? = null

    /** A look elsewhere was sent as the position, because the reader was left on it. */
    private var peekSent = false

    /**
     * Where to open the book: the server's position; the device's when the server cannot be
     * reached, or when the device holds a newer one that was never sent (it is then sent). Sends
     * nothing by itself otherwise: opening a book is not reading it.
     *
     * With [fromStart] the book is read again: it opens at its beginning, whatever position there
     * is, and without the server too. Nothing is followed, stored or sent for that, as for any
     * opening; the first page turned is the new position. What the server and this device hold
     * now is the reading left behind: it is known from here on, and is not offered as a newer
     * position.
     */
    suspend fun restore(fromStart: Boolean = false): RestoredPosition {
        val own = ownPosition(fileId, store, outbox)
        val (stored, queued) = own
        val mine = own.latest
        val server = (repository.position(fileId) as? ApiResult.Success)?.value
        server?.let(rule::onRead)
        if (fromStart) {
            // Ours, sent before or about to be: the server may date them after this opening.
            listOfNotNull(stored, queued).forEach(rule::onOwnWrite)
            if (queued != null) kick()
            return RestoredPosition(cfi = null, percent = 0.0)
        }
        val serverAt = server?.updatedAt
        val useMine = server == null || (queued != null && (serverAt == null || queued.at.isAfter(serverAt)))
        if (!useMine) return follow(checkNotNull(server))
        place = mine
        // Ours, sent or about to be: only what the server gets after it is newer.
        mine?.let(rule::onOwnWrite)
        if (queued != null) kick()
        return RestoredPosition(mine?.cfi, mine?.percent ?: 0.0, mine?.page)
    }

    /**
     * The page says where it is. [fraction] goes from 0 to 1. With [moved] false (the place the
     * book opened at) nothing is stored or sent; with [moved] true the position is stored on the
     * device at once and handed to the outbox once the page has stayed still for the quiet period.
     *
     * With [peek] true the user is looking elsewhere and may come back (a jump, while "Go back" is
     * offered): the place is only remembered. It becomes the position with the next relocation that
     * is not a peek, or when the reader is left there ([flushNow]); [onReturned] forgets it. The
     * server takes a position as "reading there", which a look is not.
     *
     * [page] is the page of a file read by pages (a comic), as the server counts it; a book has none.
     */
    suspend fun onRelocated(
        cfi: String?,
        fraction: Double,
        moved: Boolean,
        peek: Boolean = false,
        page: Int? = null,
    ) {
        val position = wall.position(fileId, cfi, fraction, page)
        place = position
        if (!moved) return
        if (peek) {
            this.peek = position
            return
        }
        this.peek = null
        peekSent = false
        move(position)
    }

    /**
     * The user came back from looking elsewhere, to where the position already is: nothing is
     * stored or sent, unless the look was sent meanwhile, which the place come back to then replaces.
     */
    suspend fun onReturned(cfi: String?, fraction: Double) {
        val position = wall.position(fileId, cfi, fraction)
        place = position
        peek = null
        if (!peekSent) return
        peekSent = false
        move(position)
    }

    /**
     * Hands the last move to the outbox now, without the quiet period: when the reader is left or
     * goes to the background. A look elsewhere the user is still on is where they left: it is the
     * position from then on. It completes even when its caller is being cancelled.
     */
    suspend fun flushNow() = withContext(NonCancellable) {
        wait?.cancel()
        peek?.let { left ->
            peek = null
            peekSent = true
            rule.onOwnWrite(left)
            unsent = left
            store.put(left)
        }
        enqueue()
    }

    /**
     * Asks the server, when the reader comes back to the front: the position it holds if it is
     * newer than what this reading knew and somewhere else than where the user is; null otherwise,
     * or when the server cannot be reached. The same one is returned until it is answered with
     * [accept] or [ignore].
     */
    suspend fun newerOnServer(): ReadingPosition? =
        (repository.position(fileId) as? ApiResult.Success)?.value?.takeIf { rule.isOffered(it, place) }

    /**
     * "Go there": the caller moves the page to [offered]. What this device still had to send for
     * the file is dropped, since the user chose the other position.
     */
    suspend fun accept(offered: ReadingPosition) {
        rule.onRead(offered)
        follow(offered)
    }

    /** "Ignore": [offered] is not returned again; a still newer one will be. */
    fun ignore(offered: ReadingPosition) = rule.ignore(offered)

    /** The server's position is the one to be at: nothing older of ours may overwrite it later. */
    private suspend fun follow(server: ReadingPosition): RestoredPosition {
        wait?.cancel()
        unsent = null
        peek = null
        peekSent = false
        outbox.pending().positions.filter { it.fileId == fileId }.forEach { outbox.markSent(it) }
        val at = server.updatedAt
        if (at != null) {
            val position = LocalPosition(fileId, server.cfi, server.percent, at, server.page)
            store.put(position)
            place = position
        }
        return RestoredPosition(server.cfi, server.percent, server.page)
    }

    /** [position] is where the user reads now: kept at once, sent after the quiet period. */
    private suspend fun move(position: LocalPosition) {
        // Before anything that waits: a caller cancelled while storing has still moved, and
        // [flushNow] must find it.
        rule.onOwnWrite(position)
        unsent = position
        wait?.cancel()
        store.put(position)
        wait = scope.launch {
            delay(quiet)
            enqueue()
        }
    }

    private suspend fun enqueue() {
        val sent = sending.withLock {
            val position = unsent ?: return@withLock false
            outbox.enqueuePosition(position)
            if (unsent === position) unsent = null
            true
        }
        if (sent) kick()
    }
}

/** Makes the [PositionSync] of one reading. */
class PositionSyncFactory @Inject constructor(
    private val repository: ReaderRepository,
    private val store: PositionStore,
    private val outbox: Outbox,
    private val flusher: OutboxFlusher,
    private val wall: Clock,
) {
    /** [scope] is the view model's. */
    fun create(fileId: FileId, scope: CoroutineScope): PositionSync =
        PositionSync(fileId, scope, repository, store, outbox, flusher::kick, wall)
}

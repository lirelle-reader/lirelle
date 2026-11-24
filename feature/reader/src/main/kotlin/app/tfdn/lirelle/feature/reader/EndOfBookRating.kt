package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How many times the server is asked for the finished reading, and how long before each. */
const val FinishedReadingAttempts = 5
val FinishedReadingWait: Duration = 1.seconds

/** How many times the outbox is looked at for the final position to have left it, and how long between two looks. */
const val PositionSentAttempts = 20
val PositionSentWait: Duration = 500.milliseconds

/** How far into a book, from 0 to 1, its last page is: the page gives exactly this at the end, and less before. */
private const val TheEnd = 1.0

/**
 * Finds the reading a rating given at the end of the book is for, and rates it. The server
 * attaches a rating to a finished reading, and closes the reading when it processes a position at
 * the end of the book. So, in this order:
 *
 * 1. The newest finished reading from before must be known: on a reread it is the reading of last
 *    time, whose rating is not this one's to change. It is asked when the book opens, and again
 *    here when that gave nothing, before the final position leaves.
 * 2. The final position is sent, and has reached the server.
 * 3. The server is asked for a finished reading that is not the one from before.
 *
 * When no new one shows, none was closed: the book was finished already and was not read again
 * from far enough for the server to start another reading. The rating is then for the reading
 * there was. The same holds, without waiting, for a finished book opened at its end and left there.
 *
 * From how far into a book the server counts it as finished is a setting of the library, which is not
 * known here. So any place before the last page counts as one the book may still be finished from: at
 * worst a new reading is waited for that does not come, and the rating goes to the reading there was.
 * Stars changed once a reading is rated go to that same reading, at once.
 */
internal class EndOfBookRating(
    private val core: ReaderCore,
    private val repository: ReaderRepository,
    private val changes: ReadingChanges,
) {
    /**
     * The newest finished reading before this one, null inside when there was none. Null while it
     * is not known.
     */
    private var earlier: ApiResult.Success<ReadId?>? = null
    private var asked = false
    private var started = false

    /** The reading rated during this opening, null before any. */
    private var rated: ReadId? = null

    /** Whether the book was, during this opening, before its end: where the server may not count it as finished. */
    private var readBeforeTheEnd = false

    private val bookId: BookId get() = core.book?.detail?.id ?: core.bookId

    /** The page shows the book: the finished reading there is now is the one from before. */
    fun onOpened() {
        if (asked) return
        asked = true
        core.scope.launch {
            val answer = repository.finishedReadingId(bookId) as? ApiResult.Success ?: return@launch
            // An answer that comes this late may already name the reading that just finished.
            if (!started) earlier = answer
        }
    }

    /** The page shows another place of the book, [fraction] of the way through it. */
    fun onPosition(fraction: Double) {
        if (fraction < TheEnd) readBeforeTheEnd = true
    }

    /** False when the rating could not be given: asking again starts over, with what is known by then. */
    suspend fun rate(stars: Int): Boolean {
        started = true
        rated?.let { return send(it, stars) }
        val before = earlier
            ?: (repository.finishedReadingId(bookId) as? ApiResult.Success)?.also { earlier = it }
            ?: return false
        core.session?.flush()
        val known = before.value
        // Opened at its end and never left it: no reading is being closed, the one there is the book's.
        val reading = if (known != null && !readBeforeTheEnd) {
            known
        } else {
            when (val new = newReading(known)) {
                is ApiResult.Failure -> return false
                is ApiResult.Success -> new.value ?: known
            }
        }
        return reading != null && send(reading, stars)
    }

    private suspend fun send(reading: ReadId, stars: Int): Boolean {
        val done = repository.rate(reading, stars) is ApiResult.Success
        if (done) {
            rated = reading
            // The book page shows the rating.
            changes.changed()
        }
        return done
    }

    /**
     * The finished reading that is not [before], asked only once the final position has reached
     * the server. Null inside when none showed within the attempts; a failure when the position
     * could not be sent or the server not asked.
     */
    private suspend fun newReading(before: ReadId?): ApiResult<ReadId?> {
        repeat(FinishedReadingAttempts) {
            delay(FinishedReadingWait)
            if (!positionSent()) return ApiResult.Failure(ApiError.ServerUnreachable)
            when (val answer = repository.finishedReadingId(bookId)) {
                is ApiResult.Failure -> return answer
                // Still the reading before: this one is not closed yet.
                is ApiResult.Success -> if (answer.value != null && answer.value != before) return answer
            }
        }
        return ApiResult.Success(null)
    }

    /** Waits, for a bounded time, until nothing of the file is left to send. */
    private suspend fun positionSent(): Boolean {
        val session = core.session ?: return true
        repeat(PositionSentAttempts) {
            if (session.positionSent()) return true
            delay(PositionSentWait)
        }
        return session.positionSent()
    }
}

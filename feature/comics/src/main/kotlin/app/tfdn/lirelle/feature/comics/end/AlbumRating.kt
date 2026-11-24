package app.tfdn.lirelle.feature.comics.end

import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.feature.comics.ComicCore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How many times the server is asked for the finished reading, and how long before each: as for a book. */
internal const val FinishedReadingAttempts = 5
internal val FinishedReadingWait: Duration = 1.seconds

/**
 * How many times the outbox is looked at for the final position to have left it, and how long between two
 * looks: as for a book.
 */
internal const val PositionSentAttempts = 20
internal val PositionSentWait: Duration = 500.milliseconds

/** How far into an album, from 0 to 1, its last page is. */
private const val TheEnd = 1.0

/**
 * Finds the reading a rating given at the end of the album is for, and rates it, as the book reader does. The
 * server attaches a rating to a finished reading, and closes the reading when it processes a position at the
 * end of the album. So, in this order:
 *
 * 1. The newest finished reading from before must be known: on a reread it is the reading of last time, whose
 *    rating is not this one's to change. It is asked when the album opens, and again here when that gave
 *    nothing, before the final position leaves.
 * 2. The final position is sent, and has reached the server.
 * 3. The server is asked for a finished reading that is not the one from before.
 *
 * When no new one shows, none was closed: the album was finished already and was not read again from far enough
 * for the server to start another reading. The rating is then for the reading there was. The same holds,
 * without waiting, for a finished album opened at its end and left there. Stars changed once a reading is
 * rated go to that same reading, at once.
 */
internal class AlbumRating(
    private val core: ComicCore,
    private val repository: ReaderRepository,
    private val changes: ReadingChanges,
) {
    /** The newest finished reading before this one, null inside when there was none. Null while it is not known. */
    private var earlier: ApiResult.Success<ReadId?>? = null
    private var asked = false
    private var started = false

    /** The reading rated during this opening, null before any. */
    private var rated: ReadId? = null

    /** Whether the album was, during this opening, before its end: where the server may not count it as finished. */
    private var readBeforeTheEnd = false

    /**
     * The reader shows a place of the album, [fraction] of the way through it. At the first one, where the album
     * opens, the finished reading there is now is asked: it is the one from before.
     */
    fun onPosition(fraction: Double) {
        if (fraction < TheEnd) readBeforeTheEnd = true
        if (asked) return
        asked = true
        core.scope.launch {
            val answer = repository.finishedReadingId(core.bookId) as? ApiResult.Success ?: return@launch
            // An answer that comes this late may already name the reading that just finished.
            if (!started) earlier = answer
        }
    }

    /** False when the rating could not be given: asking again starts over, with what is known by then. */
    suspend fun rate(stars: Int): Boolean {
        started = true
        rated?.let { return send(it, stars) }
        val before = earlier
            ?: (repository.finishedReadingId(core.bookId) as? ApiResult.Success)?.also { earlier = it }
            ?: return false
        core.session?.flush()
        val known = before.value
        // Opened at its end and never left it: no reading is being closed, the one there is the album's.
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
     * The finished reading that is not [before], asked only once the final position has reached the server.
     * Null inside when none showed within the attempts; a failure when the position could not be sent or the
     * server not asked.
     */
    private suspend fun newReading(before: ReadId?): ApiResult<ReadId?> {
        repeat(FinishedReadingAttempts) {
            delay(FinishedReadingWait)
            if (!positionSent()) return ApiResult.Failure(ApiError.ServerUnreachable)
            when (val answer = repository.finishedReadingId(core.bookId)) {
                is ApiResult.Failure -> return answer
                // Still the reading before: this one is not closed yet.
                is ApiResult.Success -> if (answer.value != null && answer.value != before) return answer
            }
        }
        return ApiResult.Success(null)
    }

    /** Waits, for a bounded time, until nothing of the album's position is left to send. */
    private suspend fun positionSent(): Boolean {
        val session = core.session ?: return true
        repeat(PositionSentAttempts) {
            if (session.positionSent()) return true
            delay(PositionSentWait)
        }
        return session.positionSent()
    }
}

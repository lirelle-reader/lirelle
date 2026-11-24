package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.audio.position.PlaybackOutbox
import app.tfdn.lirelle.core.audio.position.ReadingBaselines
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.FinishedReading
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import java.time.Clock
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How many times the server is asked for the finished reading, and how long before each. */
private const val FinishedReadingAttempts = 5
private val FinishedReadingWait = 1.seconds

/** Within this, the newest finished reading is the one this listening continued, not an older one. */
private val SameReadingWithin = Duration.ofHours(24)

/** How many times the outbox is looked at for the final position to have left it, and how long between two looks. */
private const val PositionSentAttempts = 20
private val PositionSentWait = 500.milliseconds

/**
 * Finds the reading the stars given at the end of an audiobook are for, and rates it: the 2a logic of
 * the EPUB reader (`EndOfBookRating` in `feature:reader`), copied rather than shared so that the player
 * does not depend on the reader. The server attaches a rating to a finished reading, and closes the
 * reading when it processes a position at the end of the book. So:
 *
 * 1. The newest finished reading from before this listening. When the player saw this listening begin
 *    (it saw the book before its end), the one it asked then ([onOpened]): it belongs to this very
 *    listening. Otherwise (playback started with no screen, the app opened only at the end), the one
 *    the flusher captured before it sent the book's first position in this process
 *    ([ReadingBaselines]); when the player saw the beginning but could not ask, that one too.
 * 2. The final position, which the playback service sends at the end, must have left the device.
 * 3. The server is asked for a finished reading that is not the one from before: the one this
 *    listening closed, and the stars go to it, starting empty.
 * 4. When none shows, the newest finished reading is this listening's own if the server wrote it less
 *    than [SameReadingWithin] ago: the end reached again after replaying the last minutes of a book just
 *    finished, which the server does not count as a new reading. The stars go to it, and show the
 *    rating it already has.
 *
 * Unlike the reader's, it never falls back on an older reading: the final position may have been
 * dropped (written elsewhere meanwhile), and the stars would rate a reading that ended before this
 * listening began. Nor does it trust a reading from before asked only at the end, which may already be
 * this listening's or still the old one. When no reading is found by these rules, the stars cannot be
 * given: the panel says so and offers to try again. Stars changed once a reading is rated go to that
 * same reading.
 *
 * The book's flusher baseline belongs to one listening: it is cleared once the stars are given, and when
 * the listening is over ([forgetBaseline]), so that the next one captures a fresh one before its first
 * position.
 */
internal class ListeningRating(
    private val bookId: BookId,
    private val repository: ReaderRepository,
    private val baselines: ReadingBaselines,
    private val outbox: PlaybackOutbox,
    private val changes: ReadingChanges,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** The newest finished reading when this listening began, null inside when there was none; null while not known. */
    private var before: ApiResult.Success<ReadId?>? = null
    private var started = false

    /** Whether the player saw this listening before its end: [before] is then this listening's own. */
    private var seenBeforeEnd = false

    /** The reading the stars go to, once found. */
    private var target: FinishedReading? = null

    /** The search for [target] under way, shared by the panel opening and the stars. */
    private var finding: Deferred<FinishedReading?>? = null

    /**
     * The player sees the book, [beforeEnd] or already at its end. Before the end, the finished reading
     * there is now is the one from before this listening.
     */
    fun onOpened(beforeEnd: Boolean) {
        seenBeforeEnd = beforeEnd
        if (!beforeEnd) return
        scope.launch {
            val answer = repository.finishedReadingId(bookId) as? ApiResult.Success ?: return@launch
            if (!started) before = answer
        }
    }

    /**
     * The end is reached: the reading the stars will go to is looked for, and its rating returned for the
     * panel to show; null when it has none, or none was found (the stars look again).
     */
    suspend fun atEnd(): Int? {
        started = true
        return reading()?.rating
    }

    /** False when the stars could not be given: asking again tries again, with what is known by then. */
    suspend fun rate(stars: Int): Boolean {
        started = true
        val reading = reading() ?: return false
        return send(reading.id, stars)
    }

    /** This listening of the book is over: the next one captures its own baseline. */
    fun forgetBaseline() = baselines.clear(bookId)

    /** The reading the stars go to; a search that found none is made again when asked again. */
    private suspend fun reading(): FinishedReading? {
        target?.let { return it }
        val search = finding?.takeIf { it.isActive } ?: scope.async { find() }.also { finding = it }
        return search.await()?.also { target = it }
    }

    private suspend fun find(): FinishedReading? {
        val captured = baselines.of(bookId)?.let { ApiResult.Success(it.readingId) }
        val from = if (seenBeforeEnd) before ?: captured else captured
        if (from != null) {
            when (val found = newReading(from.value)) {
                is ApiResult.Failure -> return null
                is ApiResult.Success -> found.value?.let { return it }
            }
        } else if (!positionSent()) {
            return null
        }
        return recentReading()
    }

    /** The newest finished reading when the server wrote it less than [SameReadingWithin] ago; else null. */
    private suspend fun recentReading(): FinishedReading? {
        val newest = (repository.finishedReading(bookId) as? ApiResult.Success)?.value ?: return null
        val writtenAt = newest.lastChangedAt ?: return null
        return newest.takeIf { Duration.between(writtenAt, clock.instant()) < SameReadingWithin }
    }

    private suspend fun send(reading: ReadId, stars: Int): Boolean {
        val done = repository.rate(reading, stars) is ApiResult.Success
        if (done) {
            baselines.clear(bookId)
            // The book page shows the rating.
            changes.changed()
        }
        return done
    }

    /**
     * The finished reading that is not [before]: null inside when none showed, a failure when it could
     * not be asked.
     */
    private suspend fun newReading(before: ReadId?): ApiResult<FinishedReading?> {
        repeat(FinishedReadingAttempts) {
            delay(FinishedReadingWait)
            if (!positionSent()) return ApiResult.Failure(ApiError.ServerUnreachable)
            when (val answer = repository.finishedReading(bookId)) {
                is ApiResult.Failure -> return answer
                is ApiResult.Success -> if (answer.value != null && answer.value?.id != before) return answer
            }
        }
        return ApiResult.Success(null)
    }

    /** Waits, for a bounded time, until no position of the book is left to send. */
    private suspend fun positionSent(): Boolean {
        repeat(PositionSentAttempts) {
            if (outbox.pending().none { it.bookId == bookId }) return true
            delay(PositionSentWait)
        }
        return outbox.pending().none { it.bookId == bookId }
    }
}

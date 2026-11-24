package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** The newest finished reading of a book before this listening; [readingId] null when there was none. */
data class ReadingBaseline(val readingId: ReadId?)

/** How many times a failing readings route is asked before a book's position goes without a baseline. */
const val BaselineAttempts = 6

/**
 * The finished reading of each book from before this listening, so that stars given at its end go to the
 * reading the end closed, not to an older one. The [PlaybackFlusher] captures it before it sends the
 * first position of a book in this process: before any position of this listening, the final one
 * included, can close a reading on the server. That holds however playback started (a car's play
 * button, a headset, with no screen open) and whenever the app is opened.
 *
 * Without network the book's position waits for it, as it could not be sent either. A route that fails
 * otherwise is asked [BaselineAttempts] times at most (a failing or busy server), or once (any other
 * answer); then the book's positions go without one, and are not held again: the stars then rest on
 * the reading the player saw when this listening began, if it saw it before the end.
 *
 * In memory, for as long as its profile is open, and gone with it; [clear] when a book's listening is over
 * (its stars given, or it is listened to again), so that the next one captures a fresh baseline. Safe from any
 * thread.
 */
@ProfileScoped
class ReadingBaselines @Inject constructor(private val repository: ReaderRepository) {
    private val known = ConcurrentHashMap<BookId, ReadingBaseline>()

    /** By book, the failed attempts so far; [BaselineAttempts] once given up. */
    private val failures = ConcurrentHashMap<BookId, Int>()

    /** The baseline of [bookId]; null when it was not captured. */
    fun of(bookId: BookId): ReadingBaseline? = known[bookId]

    /**
     * Captures the baseline of [bookId] unless it is known or given up. False while the book's position is
     * to wait: no network, or a failing route not asked [BaselineAttempts] times yet.
     */
    suspend fun capture(bookId: BookId): Boolean {
        if (known.containsKey(bookId) || (failures[bookId] ?: 0) >= BaselineAttempts) return true
        val answer = repository.finishedReadingId(bookId)
        if (answer is ApiResult.Success) {
            known.putIfAbsent(bookId, ReadingBaseline(answer.value))
            failures -= bookId
            return true
        }
        val failed = (answer as ApiResult.Failure).error
        if (failed == ApiError.ServerUnreachable) return false
        val attempts = if (failed in Failing) (failures[bookId] ?: 0) + 1 else BaselineAttempts
        failures[bookId] = attempts
        return attempts >= BaselineAttempts
    }

    fun clear(bookId: BookId) {
        known -= bookId
        failures -= bookId
    }

    private companion object {
        val Failing = setOf(ApiError.ServerFailed, ApiError.TooManyAttempts)
    }
}

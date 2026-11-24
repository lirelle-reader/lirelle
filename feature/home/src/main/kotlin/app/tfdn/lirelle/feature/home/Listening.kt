package app.tfdn.lirelle.feature.home

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.ui.BookMedium
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private const val MillisPerSecond = 1_000L
private const val WholeBook = 100.0

/**
 * The server writes the date of a reading a moment after the device took the listening it may carry:
 * within this, the two are the same moment, and the book was listened to.
 */
private val SameMoment = Duration.ofMinutes(2)

/**
 * What Home knows of the listening of a book of the "Continue" row.
 *
 * @property timeLeftSeconds at normal speed; null when the length of the book is not known, and for a book
 *   the server says was listened to the end, wherever its position stopped.
 * @property listenedAt when the position was taken; null for a book never listened to.
 */
data class BookListening(val timeLeftSeconds: Long?, val listenedAt: Instant?)

/**
 * The listening of books, from their playback state and the length in their manifest. The length does
 * not change: it is asked once per book.
 */
internal class ListeningLookup(private val audio: AudioRepository) {
    private val lengths = mutableMapOf<BookId, Long>()

    /**
     * Null when the playback state could not be had. A state already had for the book in [known] (null
     * inside: never listened to) is not asked again.
     */
    suspend fun of(bookId: BookId, known: Map<BookId, PlaybackState?> = emptyMap()): BookListening? = coroutineScope {
        val state = async { if (bookId in known) ApiResult.Success(known[bookId]) else audio.playbackState(bookId) }
        val length = lengths[bookId] ?: (audio.manifest(bookId) as? ApiResult.Success)?.value?.totalDurationMs
            ?.also { lengths[bookId] = it }
        val playback = (state.await() as? ApiResult.Success ?: return@coroutineScope null).value
        val left = length?.takeUnless { playback?.completed == true }
            ?.let { (it * (WholeBook - (playback?.percentage ?: 0.0)) / WholeBook).toLong() }
        BookListening(left?.coerceAtLeast(0)?.div(MillisPerSecond), playback?.capturedAt)
    }
}

/**
 * The medium the user went on with last: the only one a book has, or for a book with both the more recent of
 * its reading ([BookSummary.lastActivityAt]) and its [listening]. Null while the listening of such a book is not
 * known. A read-along EPUB without an audio file was read: its narration is heard in the reader, and Home does
 * not know its listening.
 */
internal fun BookSummary.lastMedium(listening: BookListening?): BookMedium? {
    val listenedAt = listening?.listenedAt
    val readAt = lastActivityAt
    return when {
        !hasAudioFiles -> BookMedium.Read
        media == MediaProfile.AudioOnly -> BookMedium.Listen
        listening == null -> null
        listenedAt == null -> BookMedium.Read
        readAt == null || !listenedAt.plus(SameMoment).isBefore(readAt) -> BookMedium.Listen
        else -> BookMedium.Read
    }
}

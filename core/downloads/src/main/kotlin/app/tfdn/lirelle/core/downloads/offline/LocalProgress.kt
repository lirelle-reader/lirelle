package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.audio.position.PlaybackOutbox
import app.tfdn.lirelle.core.audio.position.PlaybackStore
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.reader.sync.NarrationStore
import app.tfdn.lirelle.core.reader.sync.Outbox
import app.tfdn.lirelle.core.reader.sync.PositionStore
import app.tfdn.lirelle.core.reader.sync.ownPosition
import java.time.Instant
import javax.inject.Inject
import kotlin.math.roundToInt

private const val FullPercent = 100

/** Where one format of a book is, from 0 to 100, and when the user got there. */
private data class Place(val percent: Double, val at: Instant)

/** The book's progress by the positions the device keeps, from 0 to 100, and when the user got there. */
data class OwnProgress(val percent: Int, val at: Instant)

/**
 * The book's progress as the device knows it, by the rule of the whole app: one progress per book, the most
 * recent position among its formats. The positions made on the device and not sent yet count.
 */
class LocalProgress @Inject constructor(
    private val positions: PositionStore,
    private val outbox: Outbox,
    private val narrations: NarrationStore,
    private val playback: PlaybackStore,
    private val playbackOutbox: PlaybackOutbox,
    private val kept: KeptBookData,
) {
    /**
     * From 0 to 100. Without a position on the device, what the server said the last time the book's page was
     * loaded with a network; null when the book was never started as far as the device knows.
     */
    suspend fun percentOf(book: DownloadedBook): Int? = ownOf(book)?.percent ?: kept.reading(book.bookId)?.percent

    /**
     * The most recent of the positions the device keeps of the book's formats: made on it, sent or not, or the
     * server's kept for the book to open there without it, with their dates. Null when it keeps none: what the
     * server said of the whole book the last time its page was loaded is not one.
     */
    suspend fun ownOf(book: DownloadedBook): OwnProgress? {
        val files = kept.detail(book.bookId)?.files.orEmpty()
        val places = files.mapNotNull { text(it) } + files.mapNotNull { narration(it) } + listOfNotNull(audio(book))
        val latest = places.maxByOrNull { it.at } ?: return null
        return OwnProgress(latest.percent.roundToInt().coerceIn(0, FullPercent), latest.at)
    }

    /** The reader keeps a position for the files it opens only: the others have none. */
    private suspend fun text(file: BookFile): Place? =
        file.takeIf { it.kind != BookFileKind.Audio }
            ?.let { ownPosition(it.id, positions, outbox).latest }
            ?.let { Place(it.percent, it.at) }

    private suspend fun narration(file: BookFile): Place? {
        if (!file.hasReadAlong) return null
        val queued = outbox.pending().narrations.lastOrNull { it.fileId == file.id }
        return listOfNotNull(narrations.get(file.id), queued).maxByOrNull { it.at }?.let { Place(it.percent, it.at) }
    }

    private suspend fun audio(book: DownloadedBook): Place? =
        listeningOf(book)?.let { state -> state.capturedAt?.let { Place(state.percentage, it) } }

    /**
     * Where the listening of the book is, as the device knows it: what the server would say, for a page built
     * without it. Null for a book never listened to as far as the device knows, or whose manifest is not kept:
     * a time of the book is a share of it only against its length, the kept manifest's.
     */
    suspend fun listeningOf(book: DownloadedBook): PlaybackState? {
        val length = kept.manifest(book.bookId)?.totalDurationMs?.takeIf { it > 0 } ?: return null
        val queued = playbackOutbox.pending().lastOrNull { it.bookId == book.bookId }
        val latest = listOfNotNull(playback.get(book.bookId), queued).maxByOrNull { it.at } ?: return null
        return PlaybackState(
            assetId = latest.assetId,
            positionMs = latest.positionMs,
            percentage = (latest.bookTimeMs * FullPercent.toDouble() / length).coerceIn(0.0, FullPercent.toDouble()),
            completed = false,
            capturedAt = latest.at,
            // The version it was made over: none, when the device never knew one.
            version = latest.baseVersion.orEmpty(),
            contentVersion = latest.contentVersion,
        )
    }
}

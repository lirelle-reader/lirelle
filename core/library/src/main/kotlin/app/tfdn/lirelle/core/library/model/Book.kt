package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import java.time.Instant

private const val PortraitWidthOverHeight = 2f / 3f

enum class CoverShape(val widthOverHeight: Float) {
    Portrait(PortraitWidthOverHeight),
    Square(1f),
}

/** Reading status, with the server's eight values grouped into the five the app shows. */
enum class ReadState { Unread, WantToRead, Reading, Read, Abandoned }

data class SeriesRef(val id: SeriesId?, val name: String, val index: String?)

/** One book in a list. [coverUrl] is absolute, null when the book has no cover. */
data class BookSummary(
    val id: BookId,
    val title: String,
    val authors: List<String>,
    val series: SeriesRef?,
    val coverUrl: String?,
    val coverShape: CoverShape,
    val media: MediaProfile,
    val readState: ReadState?,
    /** 0..100 for the primary file, null when the book was never opened. */
    val progressPercent: Int?,
    /**
     * When the book was last read, as far as known: the time of its latest reading progress
     * where that was asked for (the "Continue" row), otherwise the time its reading status
     * was last written. Null when the book has neither.
     */
    val lastActivityAt: Instant? = null,
    /** Listening time left, in seconds; null from the server's cards, which carry no listening progress. */
    val timeLeftSeconds: Long? = null,
    /**
     * Whether the book has an audio file to play, not only the narration inside a read-along EPUB. Unless
     * told, what [media] says for sure.
     */
    val hasAudioFiles: Boolean = media == MediaProfile.AudioOnly || media == MediaProfile.TextAndAudio,
    /** What the pages of the file to read are, for a book read in the page reader; null for any other. */
    val pagedKind: PagedKind? = null,
) {
    /** Whether the book is read in the page reader: its file to read is a PDF, a comic or a DjVu. */
    val isPaged: Boolean get() = pagedKind != null

    /** A read book carries a check mark. */
    val isRead: Boolean get() = readState == ReadState.Read

    /**
     * A progress bar is shown for a book that is started and not finished. A book marked as read
     * is finished, whatever is left of its progress: it has the check mark, never both.
     */
    val isInProgress: Boolean get() = !isRead && progressPercent != null && progressPercent in 1..99
}

package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import java.time.Instant

data class AuthorRef(val id: AuthorId, val name: String)

enum class BookFileKind { ReadAlongEpub, Text, Audio }

data class BookFile(
    val id: FileId,
    val kind: BookFileKind,
    /** Upper-case format name for display, for example "EPUB". */
    val format: String,
    val sizeBytes: Long?,
    val isPrimary: Boolean,
    /** Length of the narration: the overlay of a read-along EPUB, or the audio file itself. */
    val narrationSeconds: Long?,
    val chapterCount: Int?,
    /** Changes when the file is replaced on the server (and sometimes when it is not). */
    val contentVersion: String? = null,
    /**
     * Whether the file can be kept on the device. False when its server cannot serve it as it reads it (a Kavita
     * chapter of several files, sent as a zip of them): it is read online and offered nowhere to keep.
     */
    val keepable: Boolean = true,
) {
    /** An EPUB whose narration is synchronised with its text. */
    val hasReadAlong: Boolean get() = kind == BookFileKind.ReadAlongEpub

    /** Whether the file is an EPUB, a KEPUB included: what the EPUB's checks, narration and downloads handle. */
    val isEpub: Boolean get() = isEpubFormat(format)
}

/**
 * The cover of one medium of a book (the audiobook's, for now), absolute: [url] in full, [thumbnailUrl]
 * small, for a notification or a card.
 */
data class MediumCover(val url: String, val thumbnailUrl: String)

data class BookDetail(
    val id: BookId,
    val title: String,
    val subtitle: String?,
    val authors: List<AuthorRef>,
    val series: SeriesRef?,
    val coverUrl: String?,
    val coverShape: CoverShape,
    val media: MediaProfile,
    val description: String?,
    val genres: List<String>,
    val rating: Float?,
    val pageCount: Int?,
    val publishedYear: Int?,
    val publisher: String?,
    val language: String?,
    val isbn: String?,
    val libraryName: String,
    val addedAt: Instant?,
    val narrators: List<String>,
    val collections: List<String>,
    val files: List<BookFile>,
    val readState: ReadState?,
    val startedAt: Instant?,
    /** The audiobook's own cover (square, mostly); null when it has none and the book's cover stands for it. */
    val audioCover: MediumCover? = null,
    /**
     * The file the book reader opens in the place of [readerFile] for an account that may not download, as the
     * source chose it among [files]: its server may refuse such an account the usual file (a read-along EPUB's
     * text, say, with a plain EPUB read instead). Null when such an account reads [readerFile] as any other.
     */
    val readerFileWithoutDownload: BookFile? = null,
    /**
     * The way the server says the pages of the book's series turn, which the comics reader opens it with as long as
     * the user chose none for the series; null when the server says none (the reader's own default then).
     */
    val pageDirection: PageDirection? = null,
    /**
     * The server holds the book's file but cannot serve it for now, and [files] names none of it: Komga analyses
     * a file that changed (its media not ready), or could not analyse it. A copy the device keeps then stands for
     * it, and the book is neither gone nor updated. BookOrbit never says so.
     */
    val filesUnavailable: Boolean = false,
    /** What sets this edition apart, as written on its server; null when it has no name of its own. */
    val editionName: String? = null,
    /**
     * Every edition of the work, this one included, in the order to list them; empty when the book has no other
     * edition, or when its source has none (Komga).
     */
    val editions: List<BookEdition> = emptyList(),
) {
    /**
     * Whether the book has an audio file to play: an audiobook file of its own, not only the narration
     * inside a read-along EPUB (that one plays with its text, in the reader).
     */
    val hasAudioFiles: Boolean get() = files.any { it.kind == BookFileKind.Audio }

    /** The one file of the book the app reads, in one reader or the other; null when it reads none. */
    private val fileToRead: BookFile? get() = fileToRead(files, { it.format }, { it.isPrimary })

    /**
     * The file the book reader opens: the file to read when its format is one the reader lays out (EPUB,
     * KEPUB, MOBI, AZW3, AZW, FB2), the primary one before any other; null when the book has none, and for
     * a book read by pages ([pagedFile]), even one that has an EPUB beside: a book has one file to read.
     */
    val readerFile: BookFile? get() = fileToRead?.takeIf { isReflowableFormat(it.format) }

    /**
     * The file the book reader opens for an account that may download or not: [readerFile], unless the source
     * gave another one for an account that may not ([readerFileWithoutDownload]). Each file has its own positions
     * and marks. Null for a book read by pages.
     */
    fun readerFile(canDownload: Boolean): BookFile? =
        if (canDownload) readerFile else readerFileWithoutDownload ?: readerFile

    /**
     * The file the page reader opens: the file to read when it is a PDF, a comic (CBZ, CBR, CB7) or a DjVu.
     * Null when the book is read in the book reader ([readerFile]) or has nothing to read.
     */
    val pagedFile: BookFile? get() = fileToRead?.takeIf { pagedKindOf(it.format) != null }

    /** What the pages of [pagedFile] are; null for a book that is not read by pages. */
    val pagedKind: PagedKind? get() = pagedKindOf(pagedFile?.format)

    /** Whether the book is read in the page reader. */
    val isPaged: Boolean get() = pagedFile != null
}

/**
 * The user's reading of a book, from progress and session statistics.
 *
 * @property page the page the reading is at, as the server holds it, for a file read by pages (a comic);
 *   null otherwise.
 * @property positionAt when the reading position was last moved; [lastReadAt] also counts sessions, listening
 *   included, so it cannot tell reading from listening.
 */
data class BookReading(
    val progressPercent: Int?,
    val lastReadAt: Instant?,
    val totalSeconds: Long,
    val sessionCount: Int,
    val page: Int? = null,
    val positionAt: Instant? = null,
) {
    val hasAny: Boolean get() = progressPercent != null || sessionCount > 0
}

data class RelatedBook(
    val id: BookId,
    val title: String,
    val authors: List<String>,
    val coverUrl: String?,
    val coverShape: CoverShape,
    /** The volume number, as the server writes it ("3", "2.5"); only sent for the books of a series. */
    val seriesIndex: String? = null,
    val readState: ReadState? = null,
) {
    val isRead: Boolean get() = readState == ReadState.Read
}

/** Each list is empty when the server has nothing, or when its call failed. */
data class RelatedBooks(
    val inSeries: List<RelatedBook> = emptyList(),
    val sameAuthor: List<RelatedBook> = emptyList(),
    val similar: List<RelatedBook> = emptyList(),
)

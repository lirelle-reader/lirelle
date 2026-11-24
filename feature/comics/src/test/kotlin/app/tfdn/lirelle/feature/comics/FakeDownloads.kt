package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.downloads.AskRefusal
import app.tfdn.lirelle.core.downloads.ClosingDeletions
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.downloads.offline.KeptReading
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.BookDetail
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** What the downloads kept of the books: the test sets [details] and [maps], by book id. */
class FakeKeptBookData : KeptBookData {
    val details = mutableMapOf<BookId, BookDetail>()
    val maps = mutableMapOf<BookId, ReadAlongMap>()

    /** The books whose kept page was asked. */
    val asked = mutableListOf<BookId>()

    override suspend fun detail(bookId: BookId): BookDetail? {
        asked += bookId
        return details[bookId]
    }

    /** How many times a kept map was asked. */
    var mapsAsked = 0

    override suspend fun map(bookId: BookId, fileId: FileId): ReadAlongMap? {
        mapsAsked++
        return maps[bookId]?.takeIf { it.fileId == fileId }
    }

    override suspend fun keep(detail: BookDetail, readingPercent: Int?, readAt: Instant?) = Unit

    override suspend fun reading(bookId: BookId): KeptReading? = null

    override fun coverFile(bookId: BookId): File? = null

    override suspend fun keepManifest(manifest: AudioManifest) = Unit

    override suspend fun manifest(bookId: BookId): AudioManifest? = null

    override suspend fun keepMap(bookId: BookId, fileId: FileId, version: String?): Boolean = false

    override suspend fun remove(bookId: BookId, format: DownloadFormat?) = Unit
}

/** Notes the books opened; the test says which formats the device has of a book ([has]). */
class FakeDownloads : Downloads {
    val opened = mutableListOf<BookId>()
    private val kept = MutableStateFlow<Map<BookId, DownloadedBook>>(emptyMap())

    /** The book has [format] in [state]; [removing] while it is being deleted. */
    fun has(
        bookId: BookId,
        format: DownloadFormat,
        state: FormatState = FormatState.Complete(1_000),
        removing: Boolean = false,
    ) {
        val record = FormatRecord(format, state, emptyList(), null, null, Instant.EPOCH, removing = removing)
        val formats = kept.value[bookId]?.formats.orEmpty().filter { it.format != format } + record
        kept.update { it + (bookId to DownloadedBook(bookId, formats, Instant.EPOCH, lastOpenedAt = null)) }
    }

    /** The book goes when it closes ([deleteAtClose]), or stays after all; null takes it off the device. */
    fun closing(bookId: BookId, deleteAtClose: Boolean?) = kept.update { books ->
        val book = books[bookId]?.takeIf { deleteAtClose != null }
        if (book == null) books - bookId else books + (bookId to book.copy(deleteAtClose = deleteAtClose == true))
    }

    override val books: Flow<List<DownloadedBook>> = flowOf(emptyList())
    override val queue: Flow<QueueState> = flowOf(QueueState(null, emptyList(), null, null))

    override fun book(bookId: BookId): Flow<DownloadedBook?> = kept.map { it[bookId] }

    override suspend fun ask(bookId: BookId, formats: Set<DownloadFormat>): AskRefusal? = null

    override suspend fun cancel(bookId: BookId, format: DownloadFormat?) = Unit

    override suspend fun retry(bookId: BookId) = Unit

    override suspend fun allowAnyNetworkNow() = Unit

    override suspend fun markOpened(bookId: BookId) {
        opened += bookId
    }
}

/** The deletions asked from an open book, done on [downloads]: a book in [held] waits, another goes at once. */
class FakeClosingDeletions(private val downloads: FakeDownloads) : ClosingDeletions {
    val asked = mutableListOf<String>()
    val held = mutableSetOf<BookId>()

    override suspend fun deleteAtClose(bookId: BookId) {
        asked += "delete $bookId"
        downloads.closing(bookId, deleteAtClose = true.takeIf { bookId in held })
    }

    override suspend fun keepAfterAll(bookId: BookId) {
        asked += "keep $bookId"
        downloads.closing(bookId, deleteAtClose = false)
    }
}

package app.tfdn.lirelle.feature.player.service

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
import app.tfdn.lirelle.core.library.offline.BookHolds
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

private val Asked = Instant.parse("2026-11-10T10:00:00Z")

/**
 * What the downloads kept of the books: the test sets [details], [manifests], [maps], [covers] and [audioCovers],
 * by book id.
 */
class FakeKeptBookData : KeptBookData {
    val details = mutableMapOf<BookId, BookDetail>()
    val manifests = mutableMapOf<BookId, AudioManifest>()
    val maps = mutableMapOf<BookId, ReadAlongMap>()
    val covers = mutableMapOf<BookId, File>()
    val audioCovers = mutableMapOf<BookId, File>()

    override suspend fun detail(bookId: BookId): BookDetail? = details[bookId]

    override suspend fun manifest(bookId: BookId): AudioManifest? = manifests[bookId]

    override suspend fun map(bookId: BookId, fileId: FileId): ReadAlongMap? =
        maps[bookId]?.takeIf { it.fileId == fileId }

    override suspend fun keep(detail: BookDetail, readingPercent: Int?, readAt: Instant?) = Unit

    override suspend fun reading(bookId: BookId): KeptReading? = null

    override fun coverFile(bookId: BookId): File? = covers[bookId]

    override fun audioCoverFile(bookId: BookId): File? = audioCovers[bookId]

    override suspend fun keepManifest(manifest: AudioManifest) = Unit

    override suspend fun keepMap(bookId: BookId, fileId: FileId, version: String?): Boolean = false

    override suspend fun remove(bookId: BookId, format: DownloadFormat?) = Unit
}

/** The formats the device has of each book ([has]), and the books opened. */
class FakeDownloads : Downloads {
    private val kept = MutableStateFlow<Map<BookId, DownloadedBook>>(emptyMap())
    val opened = mutableListOf<BookId>()

    /** The book has [format] in [state]; [removing] while it is being deleted. */
    fun has(
        bookId: BookId,
        format: DownloadFormat,
        state: FormatState = FormatState.Complete(1_000),
        removing: Boolean = false,
    ) {
        val record = FormatRecord(format, state, emptyList(), null, null, Asked, removing = removing)
        val formats = kept.value[bookId]?.formats.orEmpty().filter { it.format != format } + record
        kept.update { it + (bookId to DownloadedBook(bookId, formats, Asked, lastOpenedAt = null)) }
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

/** Notes each hold and each release, in order: `+42`, `-42`. */
class RecordingHolds : BookHolds {
    val calls = mutableListOf<String>()

    /** The books held and not released, once for each hold left. */
    val held: List<BookId>
        get() = calls.fold(emptyList()) { held, call ->
            val id = BookId(call.drop(1))
            if (call.startsWith("+")) held + id else held - id
        }

    override fun hold(bookId: BookId) {
        calls += "+$bookId"
    }

    override fun release(bookId: BookId) {
        calls += "-$bookId"
    }
}

/** A device that keeps nothing. */
fun nothingKept() = KeptBooks(FakeDownloads(), FakeKeptBookData(), BookHolds.None)

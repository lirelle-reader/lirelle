package app.tfdn.lirelle.feature.library.series

import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.downloads.AskRefusal
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.downloads.offline.KeptReading
import app.tfdn.lirelle.core.downloads.store.DownloadSettings
import app.tfdn.lirelle.core.downloads.store.DownloadSettingsStore
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** The library, with a page for each book: [details] by book; a book without one is not found. */
class SeriesRepository(base: FakeLibraryRepository = FakeLibraryRepository()) : LibraryRepository by base {
    val details = mutableMapOf<BookId, ApiResult<BookDetail>>()

    /** The books whose page was asked, in order. */
    val asked = mutableListOf<BookId>()

    /** While set and not complete, every page waits for it. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun book(bookId: BookId): ApiResult<BookDetail> {
        asked += bookId
        gate?.await()
        return details[bookId] ?: ApiResult.Failure(ApiError.NotFound)
    }
}

/** The downloads, set by the test; what is asked of them is noted in [calls]. */
class FakeSeriesDownloads : Downloads {
    val kept = MutableStateFlow(emptyList<DownloadedBook>())
    val calls = mutableListOf<String>()

    /** The books whose asking is refused. */
    var refused = emptySet<BookId>()

    /** The sizes each download was asked with, in order. */
    val sizes = mutableListOf<Map<DownloadFormat, Long>>()

    /** What the text of each download was named by when it was asked, in order. */
    val names = mutableListOf<TextName>()

    override val books: Flow<List<DownloadedBook>> = kept
    override val queue = MutableStateFlow(QueueState(null, emptyList(), null, null))

    override fun book(bookId: BookId): Flow<DownloadedBook?> =
        kept.map { all -> all.firstOrNull { it.bookId == bookId } }

    override suspend fun ask(bookId: BookId, formats: Set<DownloadFormat>): AskRefusal? {
        calls += "ask $bookId ${formats.sorted().joinToString("+")}"
        return AskRefusal.NotAllowed.takeIf { bookId in refused }
    }

    override suspend fun ask(
        bookId: BookId,
        formats: Set<DownloadFormat>,
        sizes: Map<DownloadFormat, Long>,
    ): AskRefusal? {
        this.sizes += sizes
        return ask(bookId, formats)
    }

    override suspend fun ask(
        bookId: BookId,
        formats: Set<DownloadFormat>,
        sizes: Map<DownloadFormat, Long>,
        textName: TextName,
    ): AskRefusal? {
        names += textName
        return ask(bookId, formats, sizes)
    }

    override suspend fun cancel(bookId: BookId, format: DownloadFormat?) = Unit

    override suspend fun retry(bookId: BookId) = Unit

    override suspend fun allowAnyNetworkNow() = Unit

    override suspend fun markOpened(bookId: BookId) = Unit
}

class FakeSeriesSettings : DownloadSettingsStore {
    override val settings = MutableStateFlow(DownloadSettings())

    override suspend fun update(change: (DownloadSettings) -> DownloadSettings) = settings.update(change)
}

/** Notes the pages kept, in order, beside what the downloads were asked ([log]). */
class FakeKeptData(private val log: MutableList<String>) : KeptBookData {
    override suspend fun keep(detail: BookDetail, readingPercent: Int?, readAt: Instant?) {
        log += "keep ${detail.id}"
    }

    override suspend fun detail(bookId: BookId): BookDetail? = null

    override suspend fun reading(bookId: BookId): KeptReading? = null

    override fun coverFile(bookId: BookId): File? = null

    override suspend fun keepManifest(manifest: AudioManifest) = Unit

    override suspend fun manifest(bookId: BookId): AudioManifest? = null

    override suspend fun keepMap(bookId: BookId, fileId: FileId, version: String?): Boolean = false

    override suspend fun map(bookId: BookId, fileId: FileId): ReadAlongMap? = null

    override suspend fun remove(bookId: BookId, format: DownloadFormat?) = Unit
}

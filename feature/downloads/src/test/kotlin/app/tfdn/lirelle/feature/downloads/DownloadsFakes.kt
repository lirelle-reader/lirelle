package app.tfdn.lirelle.feature.downloads

import app.tfdn.lirelle.core.audio.narration.ReadAlongMap
import app.tfdn.lirelle.core.downloads.AskRefusal
import app.tfdn.lirelle.core.downloads.DeleteResult
import app.tfdn.lirelle.core.downloads.DownloadRemovals
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.downloads.offline.KeptReading
import app.tfdn.lirelle.core.downloads.store.DownloadSettings
import app.tfdn.lirelle.core.downloads.store.DownloadSettingsStore
import app.tfdn.lirelle.core.downloads.update.DownloadUpdates
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.BookDetail
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** What the device keeps, set by the test; what the screen asks of it, written down in [calls]. */
internal class FakeDownloads : Downloads {
    val kept = MutableStateFlow<List<DownloadedBook>>(emptyList())
    val queued = MutableStateFlow(IdleQueue)
    val calls = mutableListOf<String>()

    override val books: Flow<List<DownloadedBook>> = kept
    override val queue: Flow<QueueState> = queued

    override fun book(bookId: BookId): Flow<DownloadedBook?> =
        kept.map { all -> all.firstOrNull { it.bookId == bookId } }

    override suspend fun ask(bookId: BookId, formats: Set<DownloadFormat>): AskRefusal? = null

    override suspend fun cancel(bookId: BookId, format: DownloadFormat?) {
        calls += "cancel $bookId $format"
    }

    override suspend fun retry(bookId: BookId) {
        calls += "retry $bookId"
    }

    override suspend fun allowAnyNetworkNow() {
        calls += "allowAnyNetworkNow"
    }

    override suspend fun markOpened(bookId: BookId) = Unit
}

internal class FakeRemovals : DownloadRemovals {
    var result = DeleteResult.Deleted
    val calls = mutableListOf<String>()

    override suspend fun delete(bookId: BookId, formats: Set<DownloadFormat>): DeleteResult {
        calls += "delete $bookId ${formats.sortedBy { it.ordinal }}"
        return result
    }

    override suspend fun undoDelete(bookId: BookId) {
        calls += "undoDelete $bookId"
    }

    override suspend fun deleteAll() = Unit
}

/** The pages kept, by book. */
internal class FakeKeptBookData(private val details: MutableMap<BookId, BookDetail> = mutableMapOf()) : KeptBookData {
    val covers = mutableMapOf<BookId, File>()

    override suspend fun keep(detail: BookDetail, readingPercent: Int?, readAt: Instant?) {
        details[detail.id] = detail
    }

    override suspend fun detail(bookId: BookId): BookDetail? = details[bookId]

    override suspend fun reading(bookId: BookId): KeptReading? = null

    override fun coverFile(bookId: BookId): File? = covers[bookId]

    override suspend fun keepManifest(manifest: AudioManifest) = Unit

    override suspend fun manifest(bookId: BookId): AudioManifest? = null

    override suspend fun keepMap(bookId: BookId, fileId: FileId, version: String?): Boolean = false

    override suspend fun map(bookId: BookId, fileId: FileId): ReadAlongMap? = null

    override suspend fun remove(bookId: BookId, format: DownloadFormat?) = Unit
}

/** What the screen asks of the updates, written down in [calls]; a check lasts until [checked] is completed. */
internal class FakeDownloadUpdates : DownloadUpdates {
    val calls = mutableListOf<String>()
    var checked = CompletableDeferred(Unit)

    override suspend fun check(force: Boolean) {
        calls += "check $force"
        checked.await()
    }

    override suspend fun checkBook(bookId: BookId) = Unit

    override suspend fun update(bookId: BookId): AskRefusal? {
        calls += "update $bookId"
        return null
    }

    override suspend fun updateAll(): AskRefusal? {
        calls += "updateAll"
        return null
    }
}

internal class FakeDownloadSettings :DownloadSettingsStore {
    val current = MutableStateFlow(DownloadSettings())
    override val settings: Flow<DownloadSettings> = current

    override suspend fun update(change: (DownloadSettings) -> DownloadSettings) = current.update(change)
}

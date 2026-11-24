package app.tfdn.lirelle.shell

import app.tfdn.lirelle.core.downloads.AskRefusal
import app.tfdn.lirelle.core.downloads.DeleteResult
import app.tfdn.lirelle.core.downloads.DownloadRemovals
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.QueueState
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.store.DownloadSettings
import app.tfdn.lirelle.core.downloads.store.DownloadSettingsStore
import app.tfdn.lirelle.core.ids.BookId
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** A book with one format in [state]. */
internal fun downloaded(bookId: BookId, state: FormatState) = DownloadedBook(
    bookId = bookId,
    formats = listOf(FormatRecord(DownloadFormat.Audiobook, state, emptyList(), null, null, Instant.EPOCH)),
    downloadedAt = Instant.EPOCH,
    lastOpenedAt = null,
)

/** Only what the device keeps: nothing is asked or changed from the settings. */
internal class FakeDownloads : Downloads {
    override val books = MutableStateFlow(emptyList<DownloadedBook>())
    override val queue = MutableStateFlow(QueueState(null, emptyList(), null, null))

    override fun book(bookId: BookId): Flow<DownloadedBook?> =
        books.map { all -> all.firstOrNull { it.bookId == bookId } }

    override suspend fun ask(bookId: BookId, formats: Set<DownloadFormat>): AskRefusal? = null

    override suspend fun cancel(bookId: BookId, format: DownloadFormat?) = Unit

    override suspend fun retry(bookId: BookId) = Unit

    override suspend fun allowAnyNetworkNow() = Unit

    override suspend fun markOpened(bookId: BookId) = Unit
}

internal class FakeRemovals(private val downloads: FakeDownloads) : DownloadRemovals {
    var deletedAll = 0

    override suspend fun delete(bookId: BookId, formats: Set<DownloadFormat>): DeleteResult = DeleteResult.Deleted

    override suspend fun undoDelete(bookId: BookId) = Unit

    override suspend fun deleteAll() {
        deletedAll++
        downloads.books.value = emptyList()
    }
}

internal class MemoryDownloadSettings : DownloadSettingsStore {
    override val settings = MutableStateFlow(DownloadSettings())

    override suspend fun update(change: (DownloadSettings) -> DownloadSettings) = settings.update(change)
}

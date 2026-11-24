package app.tfdn.lirelle.core.downloads.job

import app.tfdn.lirelle.core.downloads.QueueEntry
import app.tfdn.lirelle.core.downloads.model.DownloadFailure
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.store.DownloadSettings
import app.tfdn.lirelle.core.downloads.store.DownloadSettingsStore
import app.tfdn.lirelle.core.ids.BookId
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal fun record(
    state: FormatState,
    format: DownloadFormat = DownloadFormat.Audiobook,
    anyNetwork: Boolean = false,
    removing: Boolean = false,
) = FormatRecord(format, state, emptyList(), fileId = null, version = null, Instant.EPOCH, anyNetwork, removing)

internal fun book(id: String, vararg records: FormatRecord) =
    DownloadedBook(BookId(id), records.toList(), Instant.EPOCH, null)

internal class MemorySettings : DownloadSettingsStore {
    override val settings = MutableStateFlow(DownloadSettings())

    override suspend fun update(change: (DownloadSettings) -> DownloadSettings) = settings.update(change)
}

internal fun entry(
    bookId: BookId = BookId("7"),
    formats: List<DownloadFormat> = listOf(DownloadFormat.Audiobook),
    receivedBytes: Long = 124_000_000,
    totalBytes: Long? = 412_000_000,
    failure: DownloadFailure? = null,
) = QueueEntry(bookId, formats, receivedBytes, totalBytes, failure)

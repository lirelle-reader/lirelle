package app.tfdn.lirelle.core.downloads.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.tfdn.lirelle.core.downloads.engine.narrationFile
import app.tfdn.lirelle.core.downloads.engine.textFile
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What the device keeps of each book, and where each download is, kept across restarts. Safe to
 * use from several coroutines at once.
 */
interface DownloadRegistry {
    /** Every record, in no particular order. */
    val books: Flow<List<DownloadedBook>>

    suspend fun book(bookId: BookId): DownloadedBook?

    /**
     * Applies [change] to the record of this book, null when it has none; null removes it. Changes
     * are applied one after the other. The record is kept under [bookId] whatever [change] returns.
     */
    suspend fun update(bookId: BookId, change: (DownloadedBook?) -> DownloadedBook?)

    /** Forgets every record: every download was deleted. */
    suspend fun clear()
}

/**
 * Whether the files [detail] gives the reader and the narration are the ones kept whole: without the server the
 * kept page is what says which file to open, and the downloads answer by the file's id. True for a book the device
 * keeps nothing of whole.
 */
internal suspend fun DownloadRegistry.namesKeptFiles(detail: BookDetail): Boolean {
    val whole = book(detail.id)?.formats.orEmpty().filter { !it.removing && it.state is FormatState.Complete }
    return whole.all { record ->
        when (record.format) {
            DownloadFormat.Text -> record.fileId == detail.textFile?.id
            DownloadFormat.Narration -> record.fileId == detail.narrationFile?.id
            DownloadFormat.Audiobook -> true
        }
    }
}

class DataStoreDownloadRegistry(dataStore: DataStore<Preferences>) : DownloadRegistry {
    private val value = JsonValue(dataStore, "downloads", StoredDownloads.serializer(), StoredDownloads())

    override val books: Flow<List<DownloadedBook>> = value.flow.map { stored -> stored.books.map { it.book() } }

    override suspend fun book(bookId: BookId): DownloadedBook? =
        value.read().books.firstOrNull { it.bookId == bookId }?.book()

    override suspend fun update(bookId: BookId, change: (DownloadedBook?) -> DownloadedBook?) = value.change { stored ->
        val kept = stored.books
        val changed = change(kept.firstOrNull { it.bookId == bookId }?.book())?.copy(bookId = bookId)
        StoredDownloads(kept.filterNot { it.bookId == bookId } + listOfNotNull(changed?.stored()))
    }

    override suspend fun clear() = value.change { StoredDownloads() }
}

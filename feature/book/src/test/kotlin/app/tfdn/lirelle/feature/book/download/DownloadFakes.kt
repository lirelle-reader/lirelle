package app.tfdn.lirelle.feature.book.download

import app.tfdn.lirelle.core.downloads.AskRefusal
import app.tfdn.lirelle.core.downloads.DeleteResult
import app.tfdn.lirelle.core.downloads.DownloadRemovals
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.store.DownloadSettings
import app.tfdn.lirelle.core.downloads.store.DownloadSettingsStore
import app.tfdn.lirelle.core.downloads.update.DownloadUpdates
import app.tfdn.lirelle.core.ids.BookId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** The downloads of one book, set by the test; what is asked of them is noted in [calls]. */
class FakeDownloads : Downloads {
    val kept = MutableStateFlow<DownloadedBook?>(null)
    val calls = mutableListOf<String>()
    var refusal: AskRefusal? = null

    /** The sizes each download was asked with, in order. */
    val sizes = mutableListOf<Map<DownloadFormat, Long>>()

    /** What the text of each download was named by when it was asked, in order. */
    val names = mutableListOf<TextName>()

    override val books: Flow<List<DownloadedBook>> = kept.map { listOfNotNull(it) }
    override val queue = MutableStateFlow(EmptyQueue)

    override fun book(bookId: BookId): Flow<DownloadedBook?> =
        kept.map { it?.takeIf { book -> book.bookId == bookId } }

    override suspend fun ask(bookId: BookId, formats: Set<DownloadFormat>): AskRefusal? {
        calls += "ask $bookId ${formats.sorted().joinToString("+")}"
        return refusal
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

    override suspend fun cancel(bookId: BookId, format: DownloadFormat?) {
        calls += "cancel $bookId${format?.let { " $it" }.orEmpty()}"
    }

    override suspend fun retry(bookId: BookId) {
        calls += "retry $bookId"
    }

    override suspend fun allowAnyNetworkNow() {
        calls += "allowAnyNetworkNow"
    }

    override suspend fun markOpened(bookId: BookId) = Unit
}

class FakeRemovals : DownloadRemovals {
    val calls = mutableListOf<String>()
    var result = DeleteResult.Deleted

    override suspend fun delete(bookId: BookId, formats: Set<DownloadFormat>): DeleteResult {
        calls += "delete $bookId ${formats.sorted().joinToString("+")}"
        return result
    }

    override suspend fun undoDelete(bookId: BookId) = Unit

    override suspend fun deleteAll() = Unit
}

class FakeDownloadSettings : DownloadSettingsStore {
    override val settings = MutableStateFlow(DownloadSettings())

    override suspend fun update(change: (DownloadSettings) -> DownloadSettings) = settings.update(change)
}


/** The updates; what is asked of them is noted in [calls]. */
class FakeDownloadUpdates : DownloadUpdates {
    val calls = mutableListOf<String>()

    override suspend fun check(force: Boolean) {
        calls += "check"
    }

    override suspend fun checkBook(bookId: BookId) {
        calls += "checkBook $bookId"
    }

    override suspend fun update(bookId: BookId): AskRefusal? {
        calls += "update $bookId"
        return null
    }

    override suspend fun updateAll(): AskRefusal? {
        calls += "updateAll"
        return null
    }
}

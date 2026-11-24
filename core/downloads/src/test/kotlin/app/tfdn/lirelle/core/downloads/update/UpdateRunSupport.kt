package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.engine.NoNetwork
import app.tfdn.lirelle.core.downloads.engine.QueueFixture
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.ids.BookId
import java.io.File

internal suspend fun QueueFixture.record(bookId: BookId, format: DownloadFormat): FormatRecord =
    checkNotNull(registry.book(bookId)).formats.first { it.format == format }

internal suspend fun QueueFixture.update(bookId: BookId, format: DownloadFormat): FormatUpdate? =
    record(bookId, format).update

/** Where a file of an update waits. */
internal fun QueueFixture.beside(bookId: BookId, format: DownloadFormat, name: String): File =
    files.updates.file(bookId, format, name)

/** The names of the files of [bookId] that wait beside the kept ones: whole, parts and their versions. */
internal fun QueueFixture.besideNames(bookId: BookId): List<String> =
    File(files.bookDir(bookId), "update").walkTopDown().filter { it.isFile }.map { it.name }.toList()

/** What the server changed of [bookId] is found, and asked. */
internal suspend fun QueueFixture.askUpdate(bookId: BookId) {
    updates.checkBook(bookId)
    updates.update(bookId)
}

/** [fresh] as another process over the same folder and what the registry said: no network, nothing held. */
internal suspend fun QueueFixture.restartedAs(fresh: QueueFixture): QueueFixture {
    registry.book(BookId("1"))?.let { book -> fresh.registry.update(book.bookId) { book } }
    fresh.network.value = NoNetwork
    return fresh
}

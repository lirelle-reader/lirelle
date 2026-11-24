package app.tfdn.lirelle.feature.book.download

import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.update.DownloadUpdates
import app.tfdn.lirelle.core.ids.BookId
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** The check the page of a book asks when the server gave it: whether what the device keeps of it changed. */
class KeptBookCheck @Inject constructor(private val downloads: Downloads, private val updates: DownloadUpdates) {

    /** Checks [bookId] if the device keeps or is fetching something of it; nothing is asked for another book. */
    suspend fun check(bookId: BookId) {
        if (downloads.book(bookId).first() != null) updates.checkBook(bookId)
    }
}

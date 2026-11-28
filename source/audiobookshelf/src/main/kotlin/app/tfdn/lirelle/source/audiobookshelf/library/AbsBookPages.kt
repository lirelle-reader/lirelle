package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.RelatedBook
import app.tfdn.lirelle.core.library.model.RelatedBooks
import app.tfdn.lirelle.core.network.ApiResult

/**
 * A book's page, asked of [server] (spec §5): the book's whole item (`GET api/items/:id?expanded=1`) in its library,
 * the account's reading of it from its [progress], and the other books of its series. The libraries, for the name
 * and the shape of the covers, come from what the profile keeps ([whole]). What the account may not see (403) is not
 * found, as what is not there.
 */
internal class AbsBookPages(
    private val server: AbsProfileServer,
    private val progress: AbsProgress,
    private val whole: AbsWholeLibrary,
) {

    /** The book's page. Its files are not listed until its books are listened to and read (#68, #69). */
    suspend fun book(bookId: BookId): ApiResult<BookDetail> {
        val id = bookId.absId ?: return NotFound
        return server.call { remote ->
            val item = remote.api.item(id)
            val readings = progress.readings()
            val libraries = whole.libraries(remote, refresh = null)
            val libraryName = libraries.firstOrNull { it.id == item.libraryId }?.name.orEmpty()
            remote.books.detail(item, readings, libraryName)
        }
    }

    /**
     * The account's reading of the book: how far it is, the furthest of its audio and its ebook, and when it last
     * moved. Audiobookshelf's one progress cannot tell reading from listening, and Lirelle reads no sessions of it.
     */
    suspend fun bookReading(bookId: BookId): ApiResult<BookReading> {
        val id = bookId.absId ?: return NotFound
        return server.call {
            val reading = progress.readings().of(id)
            BookReading(
                progressPercent = reading?.percent,
                lastReadAt = reading?.lastUpdate,
                totalSeconds = 0,
                sessionCount = 0,
            )
        }
    }

    /**
     * The other books of the book's first series, in their places, the book left out; none for a book of no series.
     * A failure leaves them out, and the page is shown without them.
     */
    suspend fun relatedBooks(bookId: BookId): RelatedBooks {
        val id = bookId.absId ?: return RelatedBooks()
        val inSeries = server.orNull { remote ->
            val item = remote.api.item(id)
            val series = item.media.metadata.series.firstOrNull()
            val libraryId = item.libraryId
            if (series == null || libraryId == null) return@orNull emptyList()
            val items = remote.api.groupItems(libraryId, seriesFilter(series.id), limit = 0, page = 0, BySequence)
                .results
            val readings = progress.readings()
            items.filter { it.id != id }.map { remote.books.summary(it, readings) }.map { book ->
                RelatedBook(
                    id = book.id,
                    title = book.title,
                    authors = book.authors,
                    coverUrl = book.coverUrl,
                    coverShape = book.coverShape,
                    seriesIndex = book.series?.index,
                    readState = book.readState,
                )
            }
        }
        return RelatedBooks(inSeries = inSeries.orEmpty())
    }
}

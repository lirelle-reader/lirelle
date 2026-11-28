package app.tfdn.lirelle.source.audiobookshelf.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import app.tfdn.lirelle.core.library.IndexPagingSource
import app.tfdn.lirelle.core.library.PagedList
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.Shelf
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.library.model.Shelves
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsPlaylistDto
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The profile's shelves, asked of [server] (spec §4), across the server and not narrowed by the Library's choice:
 * each book library's collections, and the account's playlists, a section of their own. A podcast's episode in a
 * playlist is no book: it is neither counted nor listed, and a podcast library's playlist is not shown. Audiobookshelf
 * has no smart lists.
 *
 * A shelf opens on its books, all asked in one call, narrowed ([AbsBookFilters]) and sorted on the device, then paged
 * there, [pageSize] at a time. A shelf's own order stands for "Recently added, newest first", the default, as no date
 * a book was put on it is told; turned around is the other way. The read states come from the account's [progress].
 */
internal class AbsShelves(
    private val server: AbsProfileServer,
    private val progress: AbsProgress,
    private val whole: AbsWholeLibrary,
    private val pageSize: Int = ShelfPageSize,
) {

    suspend fun shelves(): ApiResult<Shelves> = server.call { remote ->
        val libraries = whole.libraries(remote, refresh = null)
        val ofBooks = libraries.map { it.id }.toSet()
        val collections = libraries.flatMap { remote.api.collections(it.id).results }
        val playlists = remote.api.playlists().playlists.filter { it.libraryId == null || it.libraryId in ofBooks }
        Shelves(
            smartLists = emptyList(),
            collections = collections.map {
                Shelf(ShelfKind.Collection, it.id.asCollectionShelfId(), it.name, it.books.size)
            },
            playlists = playlists.map {
                Shelf(ShelfKind.Playlist, it.id.asPlaylistShelfId(), it.name, it.books().size)
            },
        )
    }

    /** A collection's or a playlist's books as [request] asks. Anything else has none, and nothing is asked. */
    fun books(source: BookListSource, request: BookListRequest): PagedList<BookSummary> {
        val shelf = (source as? BookListSource.Collection)?.id?.absShelf() ?: return emptyPages()
        val total = MutableStateFlow<Int?>(null)
        val pager = Pager(PagingConfig(pageSize, initialLoadSize = pageSize, enablePlaceholders = true)) {
            // The shelf is asked once per load of it (a refresh asks again), then paged here.
            var books: List<BookSummary>? = null
            IndexPagingSource(pageSize, onTotal = { total.value = it }) { page, size ->
                val all = books ?: server.loaded { remote -> arranged(remote, shelf, request) }.also { books = it }
                all.pageOf(page, size)
            }
        }
        return PagedList(pager.flow, total)
    }

    /** The books of [shelf] that [request] keeps, in its order. */
    private suspend fun arranged(remote: AbsRemote, shelf: AbsShelf, request: BookListRequest): List<BookSummary> {
        val items = when (shelf) {
            is AbsShelf.Collection -> remote.api.collection(shelf.id).books
            is AbsShelf.Playlist -> remote.api.playlist(shelf.id).books()
        }
        val readings = progress.readings()
        val filters = AbsBookFilters.of(request)
        val libraries = whole.libraries(remote, refresh = null)
        return ordered(items.filter { filters.keeps(it, readings) }, request)
            .map { remote.books.summary(it, readings, libraries.coverShapeOf(it.libraryId)) }
    }
}

/** The books of a playlist, in its order: a podcast's episode is none. */
private fun AbsPlaylistDto.books(): List<AbsItemDto> =
    items.filter { it.episodeId == null }.mapNotNull { it.libraryItem }

/**
 * A shelf's [items] in [request]'s order: its own for "Recently added, newest first" (and by series, which a shelf
 * mixes), turned around the other way; by title, author or date published as the Books list sorts them.
 */
private fun ordered(items: List<AbsItemDto>, request: BookListRequest): List<AbsItemDto> = when (request.sort) {
    BookSort.Added, BookSort.Series -> if (request.direction == SortDirection.Ascending) items.reversed() else items
    BookSort.Title, BookSort.Author, BookSort.Published -> AbsBookSorts.sorted(items, request.sort, request.direction)
}

/** How many books a page of a shelf holds, as on the other servers. */
private const val ShelfPageSize = 60

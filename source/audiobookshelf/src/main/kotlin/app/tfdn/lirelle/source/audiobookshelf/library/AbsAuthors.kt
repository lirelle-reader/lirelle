package app.tfdn.lirelle.source.audiobookshelf.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.library.IndexPagingSource
import app.tfdn.lirelle.core.library.PagedList
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorDetail
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsAuthorDetailDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsAuthorDto
import java.text.Collator
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The authors of the library the Library shows, and an author's page, asked of [server] (spec §4 and §5). A
 * library's authors come whole in one answer, then are sorted, narrowed and paged on the device, [pageSize] at a time;
 * a list given no library shows the first book library's ([whole] keeps them). An author is shown without a picture
 * for now. An author's page and books come from one answer, the author with the author's items and series, read as
 * the account's [progress] says.
 */
internal class AbsAuthors(
    private val server: AbsProfileServer,
    private val progress: AbsProgress,
    private val whole: AbsWholeLibrary,
    private val pageSize: Int = AuthorPageSize,
) {

    /** The authors of [library] in [sort]'s order, narrowed to the names holding [text] when given, by pages. */
    fun authors(sort: AuthorSort, direction: SortDirection, text: String?, library: ServerLibrary?): PagedList<Author> {
        val total = MutableStateFlow<Int?>(null)
        val loads = AtomicInteger()
        val matching = AbsText.of(text)
        val pager = Pager(PagingConfig(pageSize, initialLoadSize = pageSize, enablePlaceholders = true)) {
            val refresh = AbsWholeLibrary.Refresh().takeIf { loads.getAndIncrement() > 0 }
            // The authors are asked once per load of the list (a refresh asks again), then paged here.
            var listed: List<Author>? = null
            IndexPagingSource(pageSize, onTotal = { total.value = it }) { page, size ->
                val all = listed ?: server.loaded { remote ->
                    val shown = shownLibrary(whole.libraries(remote, refresh), library)
                    shown?.let { remote.api.authors(it.id).authors }.orEmpty()
                        .filter { matching == null || matching.isIn(it.name) }
                        .sortedWith(authorOrder(sort, direction))
                        .map { Author(it.id.asAuthorId(), it.name, it.numBooks ?: 0, portraitUrl = null) }
                }.also { listed = it }
                all.pageOf(page, size)
            }
        }
        return PagedList(pager.flow, total)
    }

    /** The author: the author's description is the biography, the author's items the books counted. */
    suspend fun author(authorId: AuthorId): ApiResult<AuthorDetail> {
        val id = authorId.absId ?: return NotFound
        return server.call { remote ->
            val author = remote.api.author(id)
            AuthorDetail(
                Author(author.id.asAuthorId(), author.name, author.libraryItems.size, portraitUrl = null),
                biography = author.description?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * The author's books, by series as a reader sorts their names, each in its places, then those of no series by
     * title. A book in one of the author's series is linked to it, with its place.
     */
    suspend fun authorBooks(authorId: AuthorId): ApiResult<List<BookSummary>> {
        val id = authorId.absId ?: return NotFound
        return server.call { remote ->
            val author = remote.api.author(id)
            val places = author.seriesPlaces()
            val readings = progress.readings()
            val shape = whole.coverShape(remote, author.libraryId)
            author.libraryItems
                .map { item ->
                    val book = remote.books.summary(item, readings, shape)
                    places[item.id]?.let { book.copy(series = it) } ?: book
                }
                .sortedWith(BySeriesThenTitle)
        }
    }
}

/** Where each of the author's items is in the author's series, by item id: the first series that has it. */
private fun AbsAuthorDetailDto.seriesPlaces(): Map<String, SeriesRef> {
    val places = LinkedHashMap<String, SeriesRef>()
    series.forEach { series ->
        series.items.forEach { item ->
            val place = item.media.metadata.series.firstOrNull { it.id == series.id }?.sequence
            places.putIfAbsent(item.id, SeriesRef(series.id.asSeriesId(), series.name, place))
        }
    }
    return places
}

/**
 * The order of a library's authors for [sort] and [direction]: by the name to sort by ("Adams, Douglas"), as a reader
 * sorts names; or by their number of books, the names breaking the ties in their own order whichever way the counts
 * go.
 */
private fun authorOrder(sort: AuthorSort, direction: SortDirection): Comparator<AbsAuthorDto> {
    val collator = readerCollator()
    val byName = compareBy<AbsAuthorDto, String>(collator) { it.lastFirst?.takeIf(String::isNotBlank) ?: it.name }
    val key = when (sort) {
        AuthorSort.Name -> byName
        AuthorSort.BookCount -> compareBy { it.numBooks ?: 0 }
    }
    val directed = if (direction == SortDirection.Descending) key.reversed() else key
    return if (sort == AuthorSort.Name) directed else directed.then(byName)
}

/** Books by series, each in its places (as numbers: "0.5" before "1"), then by title; those of no series last. */
private val BySeriesThenTitle: Comparator<BookSummary> = readerCollator().let { collator ->
    compareBy<BookSummary> { it.series == null }
        .thenBy(collator) { it.series?.name.orEmpty() }
        .thenBy(nullsLast()) { it.series?.index?.toDoubleOrNull() }
        .thenBy(collator) { it.title }
}

/** Titles and names compared as a reader does, case and accents aside. */
internal fun readerCollator(): Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

/** How many authors a page of the list holds, as on the other servers. */
private const val AuthorPageSize = 60

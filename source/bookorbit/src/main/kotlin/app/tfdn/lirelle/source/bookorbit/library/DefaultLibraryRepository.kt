package app.tfdn.lirelle.source.bookorbit.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.IndexPagingSource
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.PageResult
import app.tfdn.lirelle.core.library.PagedList
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorDetail
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.RelatedBook
import app.tfdn.lirelle.core.library.model.RelatedBooks
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.library.model.SearchSection
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SeriesBooks
import app.tfdn.lirelle.core.library.model.Shelves
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.bookorbit.library.dto.RelatedBookDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SearchSectionDto
import app.tfdn.lirelle.source.bookorbit.library.dto.SeriesInfoDto
import app.tfdn.lirelle.source.bookorbit.network.ApiProvider
import app.tfdn.lirelle.source.bookorbit.network.CallKind
import app.tfdn.lirelle.source.bookorbit.network.ErrorMapper
import app.tfdn.lirelle.source.bookorbit.network.ServerFeatures
import app.tfdn.lirelle.source.bookorbit.network.bookIdOf
import app.tfdn.lirelle.source.bookorbit.network.bookOrbit
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import retrofit2.HttpException

/**
 * @param serverUrl the server of the profile it is made for, read on every call. A profile always has one,
 *   closed or not; the type allows none, which is answered as an expired session.
 * @param audio where a book with audio was last listened to, for the "Continue" row.
 */
@Suppress("TooManyFunctions") // One function per repository operation, plus their helpers.
internal class DefaultLibraryRepository(
    private val api: ApiProvider<LibraryApi>,
    private val errors: ErrorMapper,
    private val features: ServerFeatures,
    private val serverUrl: () -> String?,
    private val audio: AudioRepository,
) : LibraryRepository {

    private class Remote(val api: LibraryApi, val mapper: LibraryMapper)

    private fun remote(): Remote? = serverUrl()?.let { Remote(api.forServer(it), LibraryMapper(it)) }

    private suspend fun <T> call(block: suspend (Remote) -> T): ApiResult<T> {
        val remote = remote() ?: return ApiResult.Failure(ApiError.SessionExpired)
        return errors.call(CallKind.Authenticated) { block(remote) }
    }

    override fun books(source: BookListSource, request: BookListRequest): PagedList<BookSummary> {
        val total = MutableStateFlow<Int?>(null)
        val pager = Pager(pagingConfig(BookPageSize)) {
            IndexPagingSource(BookPageSize, onTotal = { total.value = it }) { page, size ->
                pageOf {
                    val remote = remote() ?: throw IOException("Signed out")
                    val query = buildBookQuery(request, page, size)
                    val dto = when (source) {
                        BookListSource.AllBooks -> remote.api.books(query)
                        is BookListSource.Collection -> remote.api.collectionBooks(source.id.bookOrbit(), query)
                        is BookListSource.SmartList -> remote.api.smartListBooks(source.id.bookOrbit(), query)
                    }
                    PageResult(dto.items.map(remote.mapper::summary), dto.total)
                }
            }
        }
        return PagedList(pager.flow, total)
    }

    override fun authors(sort: AuthorSort, direction: SortDirection, text: String?): PagedList<Author> {
        val total = MutableStateFlow<Int?>(null)
        val order = if (direction == SortDirection.Ascending) "asc" else "desc"
        val pager = Pager(pagingConfig(BatchSize)) {
            IndexPagingSource(BatchSize, onTotal = { total.value = it }) { page, size ->
                pageOf {
                    val remote = remote() ?: throw IOException("Signed out")
                    val dto = remote.api.authors(page, size, sort.field, order, text)
                    PageResult(dto.items.map(remote.mapper::author), dto.total)
                }
            }
        }
        return PagedList(pager.flow, total)
    }

    /** A page fetched for the paging, which knows no HTTP: what the server refused comes out as its error. */
    private suspend fun <T> pageOf(fetch: suspend () -> PageResult<T>): PageResult<T> = try {
        fetch()
    } catch (exception: HttpException) {
        throw ApiErrorException(errors.map(exception, CallKind.Authenticated))
    }

    override suspend fun series(text: String?): ApiResult<List<Series>> = call { remote ->
        wholeList { page -> remote.api.series(page, BatchSize, text).let { PageResult(it.items, it.total) } }
            // A series added on the server between two batches shifts the list: one comes twice.
            .distinctBy { it.id }
            .map(remote.mapper::series)
    }

    override suspend fun seriesBooks(seriesId: SeriesId): ApiResult<SeriesBooks> = call { remote ->
        var info: SeriesInfoDto? = null
        val cards = wholeList { page ->
            val dto = remote.api.seriesBooks(seriesId.bookOrbit(), page, BatchSize)
            if (page == 0) info = dto.seriesInfo
            PageResult(dto.items, dto.total)
        }
        val books = cards.map(remote.mapper::summary)
        val covers = books.mapNotNull { it.coverUrl }.take(SeriesCovers)
        SeriesBooks(remote.mapper.series(checkNotNull(info), covers), books)
    }

    override suspend fun author(authorId: AuthorId): ApiResult<AuthorDetail> = call { remote ->
        val dto = remote.api.author(authorId.bookOrbit())
        AuthorDetail(remote.mapper.author(dto), dto.description?.takeIf { it.isNotBlank() })
    }

    override suspend fun authorBooks(authorId: AuthorId): ApiResult<List<BookSummary>> = call { remote ->
        val number = authorId.bookOrbit()
        // One card per work, where the server groups editions. The series page is never folded: see #29.
        val folded = if (features.has(ServerFeatures.BookEditions)) true else null
        wholeList { page ->
            remote.api.authorBooks(number, page, BatchSize, folded).let { PageResult(it.items, it.total) }
        }.map(remote.mapper::summary)
    }

    /**
     * Every page of a list, in batches of [BatchSize]. A batch shorter than what was asked
     * for is the last one, whatever total the server reports.
     */
    private suspend fun <T> wholeList(fetch: suspend (page: Int) -> PageResult<T>): List<T> {
        val all = mutableListOf<T>()
        var page = 0
        do {
            val batch = fetch(page)
            all += batch.items
            page++
        } while (all.size < batch.total && batch.items.size >= BatchSize && page < MaxBatches)
        return all
    }

    override suspend fun shelves(): ApiResult<Shelves> = call { remote ->
        val smartLists = remote.api.smartLists().map(remote.mapper::smartList)
        val collections = remote.api.collections().map(remote.mapper::collection)
        Shelves(smartLists, collections)
    }

    override suspend fun book(bookId: BookId): ApiResult<BookDetail> {
        val first = fetchBook(bookId)
        val moved = (first as? ApiResult.Failure)?.error as? ApiError.Moved ?: return first
        return fetchBook(moved.target)
    }

    private suspend fun fetchBook(bookId: BookId): ApiResult<BookDetail> = call { remote ->
        val dto = remote.api.book(bookId.bookOrbit())
        // Asked only for a book that has linked books: the others need nothing of it.
        val groupsEditions = dto.linkedBooks.isNotEmpty() && features.has(ServerFeatures.BookEditions)
        remote.mapper.detail(dto, dto.audioMetadata?.chapters?.size, groupsEditions)
    }

    override suspend fun bookReading(bookId: BookId): ApiResult<BookReading> = call { remote ->
        val number = bookId.bookOrbit()
        coroutineScope {
            val progress = async { remote.api.readingProgress(number) }
            val sessions = async { remote.api.sessions(number) }
            remote.mapper.reading(progress.await(), sessions.await().stats)
        }
    }

    override suspend fun relatedBooks(bookId: BookId): RelatedBooks {
        val remote = remote() ?: return RelatedBooks()
        val number = bookId.bookOrbit()
        return coroutineScope {
            val inSeries = async { related(remote, number) { remote.api.seriesBooksOf(number) } }
            val sameAuthor = async { related(remote, number) { remote.api.authorBooksOf(number) } }
            val similar = async { related(remote, number) { remote.api.recommendations(number) } }
            RelatedBooks(inSeries.await(), sameAuthor.await(), similar.await())
        }
    }

    /** The books [fetch] lists, but the one BookOrbit numbers [bookNumber]. */
    private suspend fun related(
        remote: Remote,
        bookNumber: Long,
        fetch: suspend () -> List<RelatedBookDto>,
    ): List<RelatedBook> {
        val result = errors.call(CallKind.Authenticated) { fetch() }
        return (result as? ApiResult.Success)?.value.orEmpty()
            .filter { it.id != bookNumber }
            .distinctBy { it.id }
            .map(remote.mapper::related)
    }

    override suspend fun homeRow(kind: HomeRowKind, extent: HomeRowExtent): ApiResult<HomeRow> =
        homeRow(kind, extent.limit)

    /** A row of [limit] books at most: the tests ask for small ones. */
    internal suspend fun homeRow(kind: HomeRowKind, limit: Int): ApiResult<HomeRow> = call { remote ->
        val parts = coroutineScope {
            kind.scrollers.map { type -> async { remote.api.scroller(type, limit) } }.awaitAll()
        }
        val merged = parts.flatMap { it.books }.distinctBy { it.id }.map(remote.mapper::summary)
        val dated = if (kind == HomeRowKind.Continue) {
            // The books being listened to: at most [limit], whatever the merge holds.
            val listened = kind.scrollers.indexOf(ContinueListening).takeIf { it >= 0 }
                ?.let { index -> parts[index].books.mapTo(mutableSetOf()) { bookIdOf(it.id) } }.orEmpty()
            datedByLastReading(remote, merged, listened)
        } else {
            Dated(merged)
        }
        val books = dated.books
        // One server row is already in order; a merge is ordered by last activity.
        val ordered = if (parts.size > 1) books.sortedByDescending { it.lastActivityAt ?: Instant.MIN } else books
        // A merge of several server rows can hold more books than were asked for.
        val kept = ordered.take(limit)
        val keptIds = kept.mapTo(mutableSetOf()) { it.id }
        HomeRow(
            kind = kind,
            books = kept,
            // "recently-added" reports a total that is not the number of books, so a full row also counts.
            hasMore = ordered.size > limit ||
                parts.any { it.books.size >= limit || (it.total ?: 0) > it.books.size },
            playback = dated.playback.filterKeys { it in keptIds },
        )
    }

    /**
     * A book card only carries when its reading status was last written, which a device syncing
     * overnight changes. When the book was read is in its progress, and for a book being listened to
     * (one of [listened], the server's continue-listening row, with an audio file), when it was listened
     * to is in its playback state: the later of the two dates it. A book the server does not list as being
     * listened to is not asked about listening, which keeps those calls within the row's limit. One call
     * per book for each, as the server has no route for several books. Calls that fail keep the status time.
     */
    private suspend fun datedByLastReading(remote: Remote, books: List<BookSummary>, listened: Set<BookId>): Dated =
        coroutineScope {
            val dated = books.map { book ->
                async {
                    val asked = book.hasAudioFiles && book.id in listened
                    val playback = async { if (asked) audio.playbackState(book.id) else null }
                    val number = book.id.bookOrbit()
                    val progress = errors.call(CallKind.Authenticated) { remote.api.readingProgress(number) }
                    // Files never opened are listed too, without a date: only a dated item is reading.
                    val lastReadAt = (progress as? ApiResult.Success)?.value?.items.orEmpty()
                        .mapNotNull { instant(it.updatedAt) }
                        .maxOrNull()
                    val state = playback.await() as? ApiResult.Success
                    val last = listOfNotNull(lastReadAt, state?.value?.capturedAt).maxOrNull()
                    (if (last == null) book else book.copy(lastActivityAt = last)) to state
                }
            }.awaitAll()
            val playback = dated.mapNotNull { (book, state) -> state?.let { book.id to it.value } }.toMap()
            Dated(dated.map { it.first }, playback)
        }

    /** Books dated by their last activity, and the playback states that were had on the way. */
    private class Dated(val books: List<BookSummary>, val playback: Map<BookId, PlaybackState?> = emptyMap())

    override suspend fun search(text: String): ApiResult<SearchResults> = call { remote ->
        val dto = remote.api.search(text, SearchLimit)
        SearchResults(
            text = text,
            books = dto.books.section(remote.mapper::summary),
            series = dto.series.section(remote.mapper::series),
            authors = dto.authors.section(remote.mapper::author),
        )
    }

    /** A total the server does not give is the number of items it sent. */
    private fun <D, T> SearchSectionDto<D>.section(map: (D) -> T): SearchSection<T> =
        SearchSection(items.map { map(it.item) }, total ?: items.size)

    override suspend fun genres(): ApiResult<List<Genre>> = call { remote ->
        remote.api.genres().items
            .mapNotNull { dto -> dto.genre?.takeIf { it.isNotBlank() && dto.count > 0 }?.let { Genre(it, dto.count) } }
    }

    override fun errorOf(throwable: Throwable): ApiError = errors.map(throwable, CallKind.Authenticated)

    private fun pagingConfig(pageSize: Int) =
        PagingConfig(pageSize = pageSize, initialLoadSize = pageSize, enablePlaceholders = true)

    private companion object {
        const val BookPageSize = 60
        const val BatchSize = 100

        /** Safety stop for whole-list loads: 5 000 items. */
        const val MaxBatches = 50
        const val SeriesCovers = 3

        /** How many results of each kind a search asks for. */
        const val SearchLimit = 10
    }
}

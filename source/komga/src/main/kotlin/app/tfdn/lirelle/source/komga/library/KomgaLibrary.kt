package app.tfdn.lirelle.source.komga.library

import androidx.paging.Pager
import androidx.paging.PagingConfig
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.IndexPagingSource
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.PageResult
import app.tfdn.lirelle.core.library.PagedList
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.RelatedBooks
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.library.model.SearchSection
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SeriesBooks
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.library.model.Shelves
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaCall
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import app.tfdn.lirelle.source.komga.network.dto.BookDto
import app.tfdn.lirelle.source.komga.network.dto.PageDto
import java.text.Collator
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import retrofit2.HttpException

/**
 * A Komga profile's library: its series, books and read lists (as collections), asked of its server with its key,
 * and searched. Komga has no authors nor smart lists: those are answered empty. Reads
 * the profile's server on every call, through [session].
 */
@Suppress("TooManyFunctions") // One function per repository operation, plus their helpers.
internal class KomgaLibrary(
    private val session: ProfileSession,
    private val apis: KomgaApis<KomgaLibraryApi>,
    private val errors: KomgaErrors,
) : LibraryRepository by EmptyKomgaLibrary() {

    private class Remote(val api: KomgaLibraryApi, val mapper: KomgaMapper)

    private fun remote(): Remote {
        val serverUrl = session.session.value.serverUrl
        return Remote(apis.forServer(serverUrl), KomgaMapper(serverUrl))
    }

    private suspend fun <T> call(block: suspend (Remote) -> T): ApiResult<T> =
        errors.call(KomgaCall.Content) { block(remote()) }

    override fun books(source: BookListSource, request: BookListRequest): PagedList<BookSummary> {
        val total = MutableStateFlow<Int?>(null)
        val pager = Pager(PagingConfig(BookPageSize, initialLoadSize = BookPageSize, enablePlaceholders = true)) {
            // The series of the genre asked, once per load of the list (a refresh asks again): Komga's books have
            // no genre of their own.
            var genreSeries: List<String>? = null
            IndexPagingSource(BookPageSize, onTotal = { total.value = it }) { page, size ->
                pageOf {
                    val remote = remote()
                    val dto: PageDto<BookDto> = when (source) {
                        BookListSource.AllBooks -> {
                            val seriesIds = request.genre?.let { genre ->
                                genreSeries ?: seriesOfGenre(remote, genre).also { genreSeries = it }
                            }
                            val sorts = bookSorts(request.sort, request.direction)
                            // A genre no series has: no book to ask for.
                            if (seriesIds?.isEmpty() == true) {
                                PageDto()
                            } else {
                                remote.api.books(bookSearch(request, seriesIds), page, size, sorts)
                            }
                        }
                        is BookListSource.Collection -> remote.api.books(
                            readListBookSearch(source.id.value, request),
                            page,
                            size,
                            readListSorts(request.sort, request.direction),
                        )
                        is BookListSource.SmartList -> PageDto()
                    }
                    PageResult(dto.content.map(remote.mapper::summary), dto.totalElements)
                }
            }
        }
        return PagedList(pager.flow, total)
    }

    private suspend fun seriesOfGenre(remote: Remote, genre: String): List<String> =
        wholeList { page -> remote.api.series(seriesSearch(text = null, genre), page, BatchSize, listOf(SeriesOrder)) }
            .map { it.id }

    /** A page fetched for the paging, which knows no HTTP: what the server refused comes out as its error. */
    private suspend fun <T> pageOf(fetch: suspend () -> PageResult<T>): PageResult<T> = try {
        fetch()
    } catch (exception: HttpException) {
        throw ApiErrorException(errors.map(exception, KomgaCall.Content))
    }

    override suspend fun series(text: String?, library: ServerLibrary?): ApiResult<List<Series>> = call { remote ->
        wholeList { page -> remote.api.series(seriesSearch(text), page, BatchSize, listOf(SeriesOrder)) }
            .filterNot { it.deleted }
            // A series added on the server between two batches shifts the list: one comes twice.
            .distinctBy { it.id }
            .map(remote.mapper::series)
    }

    override suspend fun seriesBooks(seriesId: SeriesId): ApiResult<SeriesBooks> = call { remote ->
        coroutineScope {
            val series = async { remote.api.oneSeries(seriesId.value) }
            val books = async {
                wholeList { page ->
                    remote.api.books(seriesBooksSearch(seriesId.value), page, BatchSize, listOf(VolumeOrder))
                }
            }
            val dto = series.await()
            if (dto.deleted) throw ApiErrorException(ApiError.NotFound)
            SeriesBooks(remote.mapper.series(dto), books.await().kept().map(remote.mapper::summary))
        }
    }

    override suspend fun shelves(): ApiResult<Shelves> = call { remote ->
        val readLists = wholeList { page -> remote.api.readLists(page, BatchSize) }
        Shelves(smartLists = emptyList(), collections = readLists.distinctBy { it.id }.map(remote.mapper::readList))
    }

    override suspend fun book(bookId: BookId): ApiResult<BookDetail> = call { remote ->
        val dto = remote.api.book(bookId.value)
        if (dto.deleted) throw ApiErrorException(ApiError.NotFound)
        // The series gives the book its genres and publisher; a book whose series cannot be read shows without.
        val series = dto.seriesId?.let { id -> errors.call(KomgaCall.Content) { remote.api.oneSeries(id) } }
        remote.mapper.detail(dto, (series as? ApiResult.Success)?.value)
    }

    override suspend fun bookReading(bookId: BookId): ApiResult<BookReading> = call { remote ->
        remote.mapper.reading(remote.api.book(bookId.value))
    }

    /**
     * The other books of the book's series, in volume order: Komga has no books "by the same author" nor similar
     * books. A one-shot has none. Never fails: a call that fails leaves the row empty.
     */
    override suspend fun relatedBooks(bookId: BookId): RelatedBooks {
        val inSeries = call { remote ->
            val book = remote.api.book(bookId.value)
            val seriesId = book.seriesId?.takeUnless { book.oneshot }
            if (seriesId == null) {
                emptyList()
            } else {
                wholeList { page ->
                    remote.api.books(seriesBooksSearch(seriesId), page, BatchSize, listOf(VolumeOrder))
                }.kept().filter { it.id != bookId.value }.map(remote.mapper::related)
            }
        }
        return RelatedBooks(inSeries = (inSeries as? ApiResult.Success)?.value.orEmpty())
    }

    /**
     * A row of Home, asked of the server each time (a refresh too, so a revoked key is noticed there): the books in
     * progress, the most recently read first; Komga's "on deck"; its latest books. Komga has no want-to-read list.
     * The server says whether it has more books than the row holds; the trash's books are never shown.
     */
    override suspend fun homeRow(kind: HomeRowKind, extent: HomeRowExtent): ApiResult<HomeRow> {
        if (kind == HomeRowKind.WantToRead) return ApiResult.Success(HomeRow(kind, emptyList(), hasMore = false))
        val size = extent.size
        return call { remote ->
            val dto = when (kind) {
                HomeRowKind.Continue -> remote.api.books(continueSearch(), page = 0, size, listOf(ContinueOrder))
                HomeRowKind.UpNext -> remote.api.onDeck(page = 0, size)
                HomeRowKind.RecentlyAdded -> remote.api.latest(page = 0, size)
                HomeRowKind.WantToRead -> PageDto()
            }
            HomeRow(
                kind = kind,
                books = dto.content.kept().map(remote.mapper::summary),
                hasMore = dto.totalElements > dto.content.size,
            )
        }
    }

    /**
     * The first series and the first books matching [text] (Komga's full-text search, unsorted so the best matches
     * come first), with how many there are in all; "See all" lists them through [series] and [books]. Komga has no
     * authors to find. A blank text finds nothing without asking.
     */
    override suspend fun search(text: String): ApiResult<SearchResults> {
        if (text.isBlank()) return ApiResult.Success(SearchResults(text))
        return call { remote ->
            coroutineScope {
                val series = async { remote.api.series(seriesSearch(text), page = 0, SearchSectionSize, emptyList()) }
                val books = async {
                    remote.api.books(bookSearch(BookListRequest(text = text)), page = 0, SearchSectionSize, emptyList())
                }
                val seriesDto = series.await()
                val booksDto = books.await()
                SearchResults(
                    text = text,
                    books = SearchSection(booksDto.content.kept().map(remote.mapper::summary), booksDto.totalElements),
                    series = SearchSection(
                        seriesDto.content.filterNot { it.deleted }.distinctBy { it.id }.map(remote.mapper::series),
                        seriesDto.totalElements,
                    ),
                )
            }
        }
    }

    /**
     * Komga gives the genres' names, not how many books each has: they come by name (the contract's "most books
     * first" cannot be had), without a number. A Komga before 1.26 has no v2 route (404): its v1 list is asked.
     */
    override suspend fun genres(): ApiResult<List<Genre>> = call { remote ->
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val names = try {
            remote.api.genres().content
        } catch (exception: HttpException) {
            if (exception.code() != HttpNotFound) throw exception
            remote.api.genresV1()
        }
        names
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedWith(collator::compare)
            .map { Genre(it, bookCount = null) }
    }

    override fun errorOf(throwable: Throwable): ApiError = errors.map(throwable, KomgaCall.Content)

    /**
     * Every page of a list, in batches of [BatchSize]. A batch shorter than what was asked for is the last one,
     * whatever total the server reports.
     */
    private suspend fun <T> wholeList(fetch: suspend (page: Int) -> PageDto<T>): List<T> {
        val all = mutableListOf<T>()
        var page = 0
        do {
            val batch = fetch(page)
            all += batch.content
            page++
        } while (all.size < batch.totalElements && batch.content.size >= BatchSize && page < MaxBatches)
        return all
    }

    /** The books still in the library, each once. */
    private fun List<BookDto>.kept(): List<BookDto> = filterNot { it.deleted }.distinctBy { it.id }

    private companion object {
        const val BookPageSize = 60
        const val BatchSize = 100
        const val HttpNotFound = 404

        /** Safety stop for whole-list loads: 5 000 items. */
        const val MaxBatches = 50
    }
}

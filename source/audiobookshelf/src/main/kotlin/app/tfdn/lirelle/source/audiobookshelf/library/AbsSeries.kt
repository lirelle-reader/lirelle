package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SeriesBooks
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsSeriesDto
import java.time.Instant
import java.util.Base64

/**
 * The series of the library the Library shows, and a series' page, asked of [server] (spec §4 and §5). The list is
 * whole, as every source's, sorted and narrowed on the device: the server is asked it by name, [pageSize] series at a
 * time until its total is reached, as it answers no series to `limit=0`. Each series comes with its books, in their
 * order, which are what it counts and what its tile shows; the read ones are those the account's [progress] says
 * are. A list given no library shows the first book library ([whole] keeps them), as the Library always shows one.
 */
internal class AbsSeries(
    private val server: AbsProfileServer,
    private val progress: AbsProgress,
    private val whole: AbsWholeLibrary,
    private val pageSize: Int = SeriesPageSize,
) {

    /** Every series of [library], narrowed on the device to the names holding [text] when given. */
    suspend fun series(text: String?, library: ServerLibrary?): ApiResult<List<Series>> = server.call { remote ->
        val shown = shownLibrary(whole.libraries(remote, refresh = null), library) ?: return@call emptyList()
        val matching = AbsText.of(text)
        val readings = progress.readings()
        allSeries(remote, shown.id)
            .filter { matching == null || matching.isIn(it.name) }
            .map { remote.series(it, it.books, readings) }
    }

    /**
     * A series and its books in their order. The series alone names its library, whose items of that series are
     * then asked in their places (`sort=sequence`), whole.
     */
    suspend fun seriesBooks(seriesId: SeriesId): ApiResult<SeriesBooks> {
        val id = seriesId.absId ?: return NotFound
        return server.call { remote ->
            val series = remote.api.oneSeries(id)
            val libraryId = series.libraryId ?: throw ApiErrorException(ApiError.NotFound)
            val items = remote.api.groupItems(libraryId, seriesFilter(id), limit = 0, page = 0, order = BySequence)
                .results
            val readings = progress.readings()
            val shape = whole.coverShape(remote, libraryId)
            SeriesBooks(remote.series(series, items, readings), items.map { remote.books.summary(it, readings, shape) })
        }
    }

    /** The library [libraryId]'s series, by name, page after page until the server's total. */
    private suspend fun allSeries(remote: AbsRemote, libraryId: String): List<AbsSeriesDto> {
        val all = mutableListOf<AbsSeriesDto>()
        var page = 0
        do {
            val answer = remote.api.series(libraryId, limit = pageSize, page = page++, sort = ByName)
            all += answer.results
        } while (answer.results.isNotEmpty() && all.size < answer.total)
        // A series added on the server between two pages shifts the list: one comes twice.
        return all.distinctBy { it.id }
    }
}

/**
 * The server's filter for the series [id]: `series.` and the id in base64, which Retrofit then escapes as any query
 * value.
 */
internal fun seriesFilter(id: String): String =
    "series." + Base64.getEncoder().encodeToString(id.toByteArray(Charsets.UTF_8))

/**
 * [series] as a tile shows it, made of its [books] in their order: their number, those read as [readings] say, the
 * covers of the first ones, their authors, and when the last was added.
 */
internal fun AbsRemote.series(
    series: AbsSeriesDto,
    books: List<AbsItemDto>,
    readings: AbsReadings,
): Series = Series(
    id = series.id.asSeriesId(),
    name = series.name,
    authors = books.flatMap { it.media.metadata.authorNames() }.distinct(),
    bookCount = books.size,
    readCount = books.count { readings.state(it.id) == ReadState.Read },
    coverUrls = books.mapNotNull { this.books.cover(it) }.take(TileCovers),
    lastAddedAt = (books.mapNotNull { it.addedAt }.maxOrNull() ?: series.addedAt)?.let(Instant::ofEpochMilli),
)

/** The order a series' books are asked in: their places in it. */
internal val BySequence = mapOf("sort" to "sequence")

/** The order the series are asked in, which the device sorts again anyway. */
private const val ByName = "name"

/** How many series a page asks for: never 0, which the server answers with none. */
private const val SeriesPageSize = 100

/** How many covers a series' tile stacks. */
private const val TileCovers = 3

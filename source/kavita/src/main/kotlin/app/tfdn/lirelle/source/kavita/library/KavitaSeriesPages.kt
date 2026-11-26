package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.Series
import app.tfdn.lirelle.core.library.model.SeriesBooks
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The libraries of the profile's server, its series and a series' page, asked of [server]. The list of series is
 * whole, sorted and narrowed on the device as every source's; a series' page brings Kavita's volumes and chapters
 * to the app's books.
 */
internal class KavitaSeriesPages(private val server: KavitaProfileServer) {

    /** The libraries the account sees, in the server's order: some may be empty for an account kept from some. */
    suspend fun libraries(): ApiResult<List<ServerLibrary>> = server.call { remote ->
        remote.api.libraries().map { ServerLibrary(LibraryId(it.id.toString()), it.name) }
    }

    /**
     * Every series, narrowed to [library] and to the names holding [text] when given, by batches. Kavita counts
     * chapters, not the app's books: each series counts none until its page is opened.
     */
    suspend fun series(text: String?, library: ServerLibrary?): ApiResult<List<Series>> = server.call { remote ->
        val filter = seriesFilter(
            listOfNotNull(
                library?.let { Statement(SeriesField.Libraries, Comparison.Equal, it.id.value) },
                text.orNullIfBlank()?.let { Statement(SeriesField.Name, Comparison.Matches, it) },
            ),
        )
        wholeList { page, size -> remote.api.series(filter, page, size) }
            // A series added on the server between two batches shifts the list: one comes twice.
            .distinctBy { it.id }
            .map(remote.mapper::series)
    }

    /**
     * A series and its books in order, which are what it counts. The books of a series that holds one special
     * and nothing else have no series line: that book is shown alone.
     */
    suspend fun seriesBooks(seriesId: SeriesId): ApiResult<SeriesBooks> {
        val id = seriesId.value.toIntOrNull() ?: return NotFound
        return server.call { remote ->
            coroutineScope {
                val series = async { remote.api.oneSeries(id) }
                val detail = async { remote.api.detail(id) }
                val dto = series.await()
                val content = detail.await()
                val shownIn = SeriesRefInfo(dto.id, dto.name).takeUnless { content.isLoneBook }
                val books = content.books().map { remote.mapper.summary(it, shownIn, dto.format) }
                SeriesBooks(remote.mapper.seriesWithCounts(dto, books), books)
            }
        }
    }
}

package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.library.dto.ChapterDto
import app.tfdn.lirelle.source.kavita.library.dto.RecentlyUpdatedDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesDto
import app.tfdn.lirelle.source.kavita.network.answer
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import retrofit2.Response

/** How many series a Home row asks for: 20 for the row, 50 for its "See all" screen. */
internal val HomeRowExtent.size: Int
    get() = when (this) {
        HomeRowExtent.Row -> RowSize
        HomeRowExtent.SeeAll -> SeeAllSize
    }

/**
 * Home's rows, asked of [server] (spec §3 and §14). Continue and Up next read the on-deck series and the book to
 * resume in each, its continue point, which Home asks for both rows at once: they are shared for a few seconds
 * ([KavitaOnDeck], whose time [clock] tells). Want to read reads the want-to-read series the same way, and keeps a
 * series read to the end with its last book. Recently added reads each group's series detail, Kavita naming no book
 * in a group. At most six calls are made at once ([KavitaCallLimit]), all rows together. A series or a volume gone
 * between two calls (404) leaves its book out; any other failure fails the row.
 */
internal class KavitaHome(private val server: KavitaProfileServer, clock: () -> Long) {

    private val calls = KavitaCallLimit()
    private val onDeck = KavitaOnDeck(clock)

    /** The [kind] row, as long as [extent] asks. */
    suspend fun homeRow(kind: HomeRowKind, extent: HomeRowExtent): ApiResult<HomeRow> = server.call { remote ->
        when (kind) {
            HomeRowKind.Continue -> onDeck(kind, remote, extent).row(kind, remote) { it.pagesRead in 1 until it.pages }
            HomeRowKind.UpNext -> onDeck(kind, remote, extent).row(kind, remote) { it.pagesRead == 0 }
            HomeRowKind.WantToRead -> wantToRead(remote, extent.size).row(kind, remote) { true }
            HomeRowKind.RecentlyAdded -> recentlyAdded(kind, remote, extent.size)
        }
    }

    /** The on-deck series and their continue points, shared between Continue and Up next. */
    private suspend fun onDeck(kind: HomeRowKind, remote: KavitaRemote, extent: HomeRowExtent): SeriesPoints =
        onDeck.points(kind, extent) {
            points(remote, extent.size) { remote.api.onDeck(FirstPage, extent.size) }
        }

    /** The series the account wants to read, and their continue points. */
    private suspend fun wantToRead(remote: KavitaRemote, size: Int): SeriesPoints =
        points(remote, size) { remote.api.wantToRead(seriesFilter(emptyList()), FirstPage, size) }

    /** The [size] series [fetch] gives, each with its continue point, in their order. */
    private suspend fun points(
        remote: KavitaRemote,
        size: Int,
        fetch: suspend () -> Response<List<SeriesDto>>,
    ): SeriesPoints {
        val response = calls.limited(fetch)
        val series = response.answer()
        val points = coroutineScope {
            series.map { one ->
                async { calls.orGone { remote.api.continuePoint(one.id) }?.let { SeriesPoint(one, it) } }
            }.awaitAll()
        }
        return SeriesPoints(points.filterNotNull(), response.holdsMoreThan(size))
    }

    /** The row of the books whose continue point [keep] takes, each read as its book. */
    private suspend fun SeriesPoints.row(
        kind: HomeRowKind,
        remote: KavitaRemote,
        keep: (ChapterDto) -> Boolean,
    ): HomeRow = coroutineScope {
        val books = points.filter { keep(it.point) }.map { async { book(remote, it) } }.awaitAll()
        HomeRow(kind, books.filterNotNull().distinctBy { it.id }, hasMore)
    }

    /**
     * The book of a series' continue point: a default chapter asks its volume, which is the book unless it is the
     * loose-leaf one.
     */
    private suspend fun book(remote: KavitaRemote, at: SeriesPoint): BookSummary? {
        val point = at.point
        val book = if (point.isDefault) {
            point.book(calls.orGone { remote.api.volume(point.volumeId) } ?: return null)
        } else {
            point.book(volumeIndex = null)
        }
        val series = SeriesRefInfo(at.series.id, at.series.name)
        return remote.mapper.summary(book, series, at.series.format)
    }

    /** The groups of the recently updated series, each its series' newest book. */
    private suspend fun recentlyAdded(kind: HomeRowKind, remote: KavitaRemote, size: Int): HomeRow {
        val response = calls.limited { remote.api.recentlyUpdated(FirstPage, size) }
        val groups = response.answer()
        val books = coroutineScope { groups.map { async { newest(remote, it) } }.awaitAll() }
        return HomeRow(kind, books.filterNotNull().distinctBy { it.id }, response.holdsMoreThan(size))
    }

    /**
     * The group's series' newest book, by when its chapter came, shown in the group's series; a lone book without
     * a series.
     */
    private suspend fun newest(remote: KavitaRemote, group: RecentlyUpdatedDto): BookSummary? {
        val detail = calls.orGone { remote.api.detail(group.seriesId) } ?: return null
        val book = detail.books().maxByOrNull { utcInstant(it.chapter.createdUtc) ?: Instant.MIN } ?: return null
        val series = SeriesRefInfo(group.seriesId, group.seriesName.orEmpty()).takeUnless { detail.isLoneBook }
        return remote.mapper.summary(book, series, group.format)
    }
}

/** A series of a Home row and its continue point: the chapter to resume, the first unread, or its last when read. */
internal data class SeriesPoint(val series: SeriesDto, val point: ChapterDto)

/** Series with their continue points, and whether the server holds more series than were asked. */
internal data class SeriesPoints(val points: List<SeriesPoint>, val hasMore: Boolean)

/** The server holds more than the [size] items asked, as the `Pagination` header says. */
private fun Response<*>.holdsMoreThan(size: Int): Boolean = (totalItems() ?: 0) > size

private const val RowSize = 20
private const val SeeAllSize = 50

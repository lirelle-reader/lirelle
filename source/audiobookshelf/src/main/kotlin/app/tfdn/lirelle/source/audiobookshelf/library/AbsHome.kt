package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowExtent
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsShelfDto
import app.tfdn.lirelle.source.audiobookshelf.network.AbsJson
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** How many books a Home row asks for: 20 for the row, 50 for its "See all" screen. */
internal val HomeRowExtent.size: Int
    get() = when (this) {
        HomeRowExtent.Row -> RowSize
        HomeRowExtent.SeeAll -> SeeAllSize
    }

/**
 * Home's rows, asked of [server] across its book libraries (spec §3). Continue is the items the account has started,
 * of every library, in the server's order (the latest first), a podcast's episode left out. Up next is each book
 * library's `continue-series` shelf, joined in the libraries' order: the server only makes it for a series with a
 * book finished and none in progress. Recently added is each book library's `recently-added` shelf, merged newest
 * first. Each row is cut at its size. The shelves of the libraries are asked at once, and shared between Up next and
 * Recently added for a few seconds ([AbsPersonalized], whose time [clock] tells). Audiobookshelf has no list of books
 * to read: Want to read is empty. The read states and progress come from the account's [progress]; a failure fails
 * the row.
 */
internal class AbsHome(
    private val server: AbsProfileServer,
    private val progress: AbsProgress,
    private val whole: AbsWholeLibrary,
    clock: () -> Long,
) {
    private val personalized = AbsPersonalized(clock)

    /** The [kind] row, as long as [extent] asks. */
    suspend fun homeRow(kind: HomeRowKind, extent: HomeRowExtent): ApiResult<HomeRow> = server.call { remote ->
        when (kind) {
            HomeRowKind.Continue -> inProgress(remote, extent.size)
            HomeRowKind.UpNext -> shelfRow(kind, remote, extent, ContinueSeries) { it }
            HomeRowKind.RecentlyAdded ->
                shelfRow(kind, remote, extent, RecentlyAdded) { items -> items.sortedByDescending { it.addedAt } }
            HomeRowKind.WantToRead -> HomeRow(kind, emptyList(), hasMore = false)
        }
    }

    /**
     * The [size] latest items the account has started, books alone: being in progress, each is being read, as
     * lately as the server says, whatever the account's progress, read a few seconds before, says of it. The server
     * holds more when it gave as many as were asked.
     */
    private suspend fun inProgress(remote: AbsRemote, size: Int): HomeRow = coroutineScope {
        val readings = async { progress.readings() }
        val libraries = async { whole.libraries(remote, refresh = null) }
        val items = remote.api.itemsInProgress(size).libraryItems
        val books = items.filter { it.mediaType != PodcastMedia }.map { item ->
            val book = remote.books.summary(item, readings.await(), libraries.await().coverShapeOf(item.libraryId))
            book.copy(
                readState = ReadState.Reading,
                lastActivityAt = item.progressLastUpdate?.let(Instant::ofEpochMilli) ?: book.lastActivityAt,
            )
        }
        HomeRow(HomeRowKind.Continue, books, hasMore = items.size >= size)
    }

    /**
     * The row of the shelf [shelfId] of each book library, its items as [arrange] puts them, as many as [extent]
     * asks. The server holds more when the shelves count more than the row shows.
     */
    private suspend fun shelfRow(
        kind: HomeRowKind,
        remote: AbsRemote,
        extent: HomeRowExtent,
        shelfId: String,
        arrange: (List<AbsItemDto>) -> List<AbsItemDto>,
    ): HomeRow {
        val all = shelves(kind, remote, extent)
        val found = all.mapNotNull { library -> library.shelves.firstOrNull { it.id == shelfId } }
        val items = arrange(found.flatMap { it.items() }).distinctBy { it.id }.take(extent.size)
        val readings = progress.readings()
        val libraries = all.map { it.library }
        val books = items.map { remote.books.summary(it, readings, libraries.coverShapeOf(it.libraryId)) }
        return HomeRow(kind, books, hasMore = found.sumOf { it.held() } > books.size)
    }

    /** The shelves of each book library, in their order, shared with the other row of shelves. */
    private suspend fun shelves(kind: HomeRowKind, remote: AbsRemote, extent: HomeRowExtent): List<LibraryShelves> =
        personalized.shelves(kind, extent) {
            val libraries = whole.libraries(remote, refresh = null)
            coroutineScope {
                libraries.map { async { LibraryShelves(it, remote.api.personalized(it.id, extent.size)) } }.awaitAll()
            }
        }
}

/** The items of a shelf of books; none for a shelf of series or authors. */
private fun AbsShelfDto.items(): List<AbsItemDto> =
    if (type == BookShelf) entities.map { AbsJson.decodeFromJsonElement(AbsItemDto.serializer(), it) } else emptyList()

/** How many the server holds for a shelf: its total, else what it gave. */
private fun AbsShelfDto.held(): Int = total ?: entities.size

/** The shelf of the next book of each series the account goes on with. */
private const val ContinueSeries = "continue-series"

/** The shelf of the books last added to a library. */
private const val RecentlyAdded = "recently-added"

/** The type of a shelf of library items, which in a book library are books. */
private const val BookShelf = "book"

/** The media type of an item of a podcast library. */
private const val PodcastMedia = "podcast"

private const val RowSize = 20
private const val SeeAllSize = 50

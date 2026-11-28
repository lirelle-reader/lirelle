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
 * of its book libraries, in the server's order (the latest first), a podcast's episode left out. Up next is each book
 * library's `continue-series` shelf, joined in the libraries' order: the server only makes it for a series with a
 * book finished and none in progress. Recently added is each book library's `recently-added` shelf, merged newest
 * first. Each row is cut at its size. The shelves of the libraries are asked at once, and shared between Up next and
 * Recently added for a few seconds ([AbsPersonalized], whose time [clock] tells); a library the account may not open
 * (403) or no longer has (404) is left out. Audiobookshelf has no list of books to read: Want to read is empty. The
 * read states and progress come from the account's [progress], read again for each load of Home's rows
 * ([AbsHomeLoads]): its first, a pull to refresh, Home shown again. Any other failure fails the row.
 */
internal class AbsHome(
    private val server: AbsProfileServer,
    private val progress: AbsProgress,
    private val whole: AbsWholeLibrary,
    clock: () -> Long,
) {
    private val personalized = AbsPersonalized(clock)
    private val loads = AbsHomeLoads(clock)

    /** The [kind] row, as long as [extent] asks. */
    suspend fun homeRow(kind: HomeRowKind, extent: HomeRowExtent): ApiResult<HomeRow> = server.call { remote ->
        val asked = RowAsked(kind, extent, loads.refreshFor(kind, extent))
        when (kind) {
            HomeRowKind.Continue -> inProgress(remote, extent.size, asked.refresh)
            HomeRowKind.UpNext -> shelfRow(asked, remote, ContinueSeries) { it }
            HomeRowKind.RecentlyAdded -> shelfRow(asked, remote, RecentlyAdded) { items ->
                items.sortedByDescending { it.addedAt }
            }
            HomeRowKind.WantToRead -> HomeRow(kind, emptyList(), hasMore = false)
        }
    }

    /**
     * The [size] latest items the account has started, books of its book libraries alone: 2.26 does not check the
     * account's access to their libraries, and a podcast library is none of them. Being in progress, each is being
     * read, as lately as the server says, whatever the account's progress, read a few seconds before, says of it.
     * The server holds more when it gave as many as were asked.
     */
    private suspend fun inProgress(remote: AbsRemote, size: Int, refresh: AbsWholeLibrary.Refresh): HomeRow =
        coroutineScope {
            val readings = async { progress.readings(refresh) }
            val libraries = async { whole.libraries(remote, refresh = null) }
            val items = remote.api.itemsInProgress(size).libraryItems
            val bookLibraries = libraries.await().map { it.id }.toSet()
            val books = items.filter { it.mediaType != PodcastMedia && it.libraryId in bookLibraries }.map { item ->
                val book = remote.books.summary(item, readings.await())
                book.copy(
                    readState = ReadState.Reading,
                    lastActivityAt = item.progressLastUpdate?.let(Instant::ofEpochMilli) ?: book.lastActivityAt,
                )
            }
            HomeRow(HomeRowKind.Continue, books, hasMore = items.size >= size)
        }

    /**
     * The row [asked] of the shelf [shelfId] of each book library, its items as [arrange] puts them, as many as its
     * extent asks. The server holds more when the shelves count more than the row shows.
     */
    private suspend fun shelfRow(
        asked: RowAsked,
        remote: AbsRemote,
        shelfId: String,
        arrange: (List<AbsItemDto>) -> List<AbsItemDto>,
    ): HomeRow {
        val all = shelves(asked.kind, remote, asked.extent)
        val found = all.mapNotNull { library -> library.shelves.firstOrNull { it.id == shelfId } }
        val items = arrange(found.flatMap { it.items() }).distinctBy { it.id }.take(asked.extent.size)
        val readings = progress.readings(asked.refresh)
        val books = items.map { remote.books.summary(it, readings) }
        return HomeRow(asked.kind, books, hasMore = found.sumOf { it.held() } > books.size)
    }

    /** The shelves of each book library, in their order, shared with the other row of shelves. */
    private suspend fun shelves(kind: HomeRowKind, remote: AbsRemote, extent: HomeRowExtent): List<LibraryShelves> =
        personalized.shelves(kind, extent) {
            val libraries = whole.libraries(remote, refresh = null)
            coroutineScope {
                libraries.map { library ->
                    async {
                        unlessLibraryGone { remote.api.personalized(library.id, extent.size) }
                            ?.let { LibraryShelves(library, it) }
                    }
                }.awaitAll().filterNotNull()
            }
        }
}

/** A row Home asks: its [kind], as long as its [extent], for the load whose [refresh] it is. */
private data class RowAsked(val kind: HomeRowKind, val extent: HomeRowExtent, val refresh: AbsWholeLibrary.Refresh)

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

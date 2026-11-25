package app.tfdn.lirelle.feature.player.car

import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiResult
import java.text.Collator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How many books of a kind (with an audio file, read aloud) the car lists at most: its Audiobooks and Series
 * tabs are made of the whole lists, which a car is not made to scroll further anyway.
 */
internal const val ListenableBound = 600

/**
 * How long the audio library is kept once read: the pages of one visit of the Audiobooks tab, and the Series tab
 * opened right after, are made of one reading of the server's lists, which takes up to twenty requests.
 */
internal const val ListenableFreshMillis = 2 * 60 * 1_000L

/** The size of the pages the lists are read by: the one the phone's lists ask, which every server answers. */
private const val BatchSize = 60

/**
 * The books of the server that can be listened to. Every answer is null when the server could not be had:
 * a list is whole or it is not shown.
 */
internal class ListenableBooks(
    private val library: LibraryRepository,
    private val capabilities: SourceCapabilities,
    private val clock: MonotonicClock,
) {
    /** The audio library as it was last read whole, and when by [clock]; a reading that failed is not kept. */
    private var last: Pair<Long, List<BookSummary>>? = null

    /** One reading at a time: a tab asked while another reads waits for it, and is made of the same. */
    private val reading = Mutex()

    /** Titles as a reader orders them: no case, no accent. */
    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    /**
     * All of them, by title, each once: read from the server, or as they were read less than
     * [ListenableFreshMillis] ago.
     */
    suspend fun all(): List<BookSummary>? = reading.withLock {
        last?.takeIf { (at, _) -> clock.nowMillis() - at < ListenableFreshMillis }?.second
            ?: read()?.also { last = clock.nowMillis() to it }
    }

    /**
     * Reads them from the server. Its filters cannot say "with an audio file or read aloud" in one request (its
     * rules are joined by "and"): each kind the source has is asked whole, and the two are merged here.
     */
    private suspend fun read(): List<BookSummary>? = coroutineScope {
        val kinds = listOfNotNull(
            BookFilters(formats = setOf(FormatFilter.Audio)).takeIf { capabilities.audiobooks },
            BookFilters(readAlongOnly = true).takeIf { capabilities.readAlong },
        )
        val lists = kinds.map { async { whole(it) } }.awaitAll()
        if (lists.any { it == null }) return@coroutineScope null
        lists.filterNotNull().flatten()
            // A read-aloud book with an audio file besides is in both lists.
            .distinctBy { it.id }
            .filter { listenable(it, capabilities) }
            .sortedWith { a, b -> collator.compare(a.title, b.title) }
    }

    /** The books of the series [all] has, as the car lists series: by name, with how many of them each has. */
    suspend fun series(): List<CarNode.Series>? = all()?.let { books ->
        books.mapNotNull { it.series }
            .groupBy { it.id }
            .mapNotNull { (id, refs) -> id?.let { CarNode.Series(it, refs.first().name, refs.size) } }
            .sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    /** Those of one series, in the series' order. */
    suspend fun ofSeries(id: SeriesId): List<BookSummary>? =
        (library.seriesBooks(id) as? ApiResult.Success)?.value?.books?.filter { listenable(it, capabilities) }

    /** Those among the first books the server finds for [text]. */
    suspend fun matching(text: String): List<BookSummary>? =
        (library.bookPage(BookListRequest(text = text), 0, BatchSize) as? ApiResult.Success)
            ?.value?.items?.filter { listenable(it, capabilities) }

    /** Every page of the books [filters] keep, by title, up to [ListenableBound] books. */
    private suspend fun whole(filters: BookFilters): List<BookSummary>? {
        val request = BookListRequest(sort = BookSort.Title, direction = SortDirection.Ascending, filters = filters)
        val all = mutableListOf<BookSummary>()
        var page = 0
        do {
            val batch = (library.bookPage(request, page, BatchSize) as? ApiResult.Success)?.value ?: return null
            all += batch.items
            page++
            // A page shorter than what was asked for is the last one, whatever the total says.
        } while (batch.items.size >= BatchSize && all.size < batch.total && all.size < ListenableBound)
        return all.take(ListenableBound)
    }
}

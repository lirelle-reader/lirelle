package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.source.kavita.library.dto.SeriesDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// Kavita lists series where the app shows books (a genre's books, an author's): each series is unfolded into its
// books by its detail, all of them together but no more at once than the call limit allows.

/**
 * The books of each of [series], in the order of the series, each series in its own order; only those [keep]
 * holds. Their details are asked together, in this limit's turns; a series gone between two calls (404) has none.
 */
internal suspend fun KavitaCallLimit.unfold(
    remote: KavitaRemote,
    series: List<SeriesDto>,
    keep: (KavitaBook) -> Boolean = { true },
): List<BookSummary> = coroutineScope {
    series.map { async { booksOf(remote, it, keep) } }.awaitAll().flatten()
}

/** A series' books that [keep] holds, in its order; none when the series is gone. A lone book has no series line. */
private suspend fun KavitaCallLimit.booksOf(
    remote: KavitaRemote,
    series: SeriesDto,
    keep: (KavitaBook) -> Boolean,
): List<BookSummary> {
    val detail = orGone { remote.api.detail(series.id) } ?: return emptyList()
    val shownIn = SeriesRefInfo(series.id, series.name).takeUnless { detail.isLoneBook }
    return detail.books().filter(keep).map { remote.mapper.summary(it, shownIn, series.format) }
}

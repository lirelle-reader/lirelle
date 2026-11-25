package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.SeriesId
import java.text.Collator
import java.time.Instant

data class Series(
    val id: SeriesId,
    val name: String,
    val authors: List<String>,
    /** What the series holds, as its server counts it: books, or volumes where it tells them from their editions. */
    val bookCount: Int,
    /** Those of [bookCount] that are read, counted the same way. */
    val readCount: Int,
    /** Up to three absolute cover URLs for the stacked tile. */
    val coverUrls: List<String>,
    val lastAddedAt: Instant?,
) {
    val unreadCount: Int get() = (bookCount - readCount).coerceAtLeast(0)
    val progress: Float get() = if (bookCount <= 0) 0f else (readCount.toFloat() / bookCount).coerceIn(0f, 1f)
}

data class SeriesBooks(val series: Series, val books: List<BookSummary>)

enum class SeriesSort { Name, BookCount, UnreadCount, RecentlyAdded, Progress }

enum class SeriesProgressFilter { NotStarted, InProgress, Complete }

/**
 * Sorts and filters on the device: the server has no sort by number of unread books, and
 * the whole list is loaded anyway. Ties are broken by name, so the order is stable.
 */
fun List<Series>.arranged(
    sort: SeriesSort,
    direction: SortDirection,
    filter: SeriesProgressFilter?,
): List<Series> {
    val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
    val byName = Comparator<Series> { a, b -> collator.compare(a.name, b.name) }
    val key = sort.comparator(byName)
    val ordered = if (direction == SortDirection.Descending) key.reversed() else key
    val kept = if (filter == null) this else filter(filter::matches)
    return kept.sortedWith(if (sort == SeriesSort.Name) ordered else ordered.then(byName))
}

private fun SeriesSort.comparator(byName: Comparator<Series>): Comparator<Series> = when (this) {
    SeriesSort.Name -> byName
    SeriesSort.BookCount -> compareBy { it.bookCount }
    SeriesSort.UnreadCount -> compareBy { it.unreadCount }
    SeriesSort.RecentlyAdded -> compareBy { it.lastAddedAt ?: Instant.MIN }
    SeriesSort.Progress -> compareBy { it.progress }
}

private fun SeriesProgressFilter.matches(series: Series): Boolean = when (this) {
    SeriesProgressFilter.NotStarted -> series.readCount == 0
    SeriesProgressFilter.InProgress -> series.readCount in 1 until series.bookCount
    SeriesProgressFilter.Complete -> series.bookCount > 0 && series.readCount >= series.bookCount
}

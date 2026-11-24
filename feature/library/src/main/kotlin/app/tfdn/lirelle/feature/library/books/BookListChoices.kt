package app.tfdn.lirelle.feature.library.books

import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.StatusFilter

/**
 * What a list of books offers to sort and filter by: what its source can do, in the order the menus show it (spec
 * 2c). What is missing is not shown at all.
 *
 * @property readAlong whether the read-along filter is offered.
 */
data class BookListChoices(
    val sorts: List<BookSort> = BookSort.entries,
    val formats: List<FormatFilter> = FormatFilter.entries,
    val statuses: List<StatusFilter> = StatusFilter.entries,
    val readAlong: Boolean = true,
) {
    companion object {
        fun of(capabilities: SourceCapabilities) = BookListChoices(
            sorts = BookSort.entries.filter { it in capabilities.bookSorts },
            formats = FormatFilter.entries.filter { it in capabilities.formatFilters },
            statuses = StatusFilter.entries.filter { it in capabilities.statusFilters },
            readAlong = capabilities.readAlong,
        )
    }
}

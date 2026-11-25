package app.tfdn.lirelle.core.library.model

import app.tfdn.lirelle.core.ids.ShelfId

enum class SortDirection { Ascending, Descending }

/**
 * The orders the user can choose for books; the source says each in its own query. The names are stored (a list's
 * chosen sort): they do not change.
 *
 * @property defaultDirection the direction a sort starts in when the user chooses it.
 */
enum class BookSort(val defaultDirection: SortDirection) {
    Added(SortDirection.Descending),
    Title(SortDirection.Ascending),
    Author(SortDirection.Ascending),

    /** By series, then by volume within a series. */
    Series(SortDirection.Ascending),
    Published(SortDirection.Descending),
}

/** The groups of formats the user can narrow books to; the source says which of its formats each stands for. */
enum class FormatFilter { Epub, Pdf, Comics, Audio }

/** The reading states the user can narrow books to; the source says which of its statuses each stands for. */
enum class StatusFilter { Unread, WantToRead, Reading, Read, Abandoned }

/** @property readAlongOnly only the books whose text is narrated in sync; the source says how it finds them. */
data class BookFilters(
    val formats: Set<FormatFilter> = emptySet(),
    val statuses: Set<StatusFilter> = emptySet(),
    val readAlongOnly: Boolean = false,
) {
    val isActive: Boolean get() = formats.isNotEmpty() || statuses.isNotEmpty() || readAlongOnly
}

data class BookListRequest(
    val sort: BookSort = BookSort.Added,
    val direction: SortDirection = BookSort.Added.defaultDirection,
    val filters: BookFilters = BookFilters(),
    /** Narrows the list to the books matching this text; blank means no text. */
    val text: String? = null,
    val genre: String? = null,
    /** Narrows the whole library's books to one library of the server; null for all. Ignored by a shelf's books. */
    val library: ServerLibrary? = null,
)

/** Where a paged list of books comes from. */
sealed interface BookListSource {
    data object AllBooks : BookListSource
    data class Collection(val id: ShelfId) : BookListSource
    data class SmartList(val id: ShelfId) : BookListSource
}

package app.tfdn.lirelle.core.library

import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.SearchKind
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.library.model.StatusFilter

/** What a source's library is first shown as: its books, or its series (a library of comics). */
enum class LibraryStart { Books, Series }

/**
 * What the open profile's source can do (spec 2c). Fixed per kind of server, declared by its source: what it cannot
 * do is hidden from the screens, not greyed.
 *
 * @property annotations highlights, notes and the reader's bookmarks.
 * @property readingSessions reading and listening sessions.
 * @property authors author pages, the Authors tab and author results.
 * @property history the History entry; what the history shows is the history's own capabilities.
 * @property libraryStart the tab the Library opens on, until the user leaves it on another.
 */
data class SourceCapabilities(
    val audiobooks: Boolean,
    val readAlong: Boolean,
    val annotations: Boolean,
    val audioBookmarks: Boolean,
    val ratings: Boolean,
    val readingSessions: Boolean,
    val genres: Boolean,
    val authors: Boolean,
    val history: Boolean,
    val homeRows: Set<HomeRowKind>,
    val shelfKinds: Set<ShelfKind>,
    val searchKinds: Set<SearchKind>,
    val bookSorts: Set<BookSort>,
    val statusFilters: Set<StatusFilter>,
    val formatFilters: Set<FormatFilter>,
    val libraryStart: LibraryStart,
) {
    companion object {
        /** A source that can do everything the app shows, whose Library opens on its books. */
        val Everything = SourceCapabilities(
            audiobooks = true, readAlong = true, annotations = true, audioBookmarks = true, ratings = true,
            readingSessions = true, genres = true, authors = true, history = true,
            homeRows = HomeRowKind.entries.toSet(), shelfKinds = ShelfKind.entries.toSet(),
            searchKinds = SearchKind.entries.toSet(), bookSorts = BookSort.entries.toSet(),
            statusFilters = StatusFilter.entries.toSet(), formatFilters = FormatFilter.entries.toSet(),
            libraryStart = LibraryStart.Books,
        )
    }
}

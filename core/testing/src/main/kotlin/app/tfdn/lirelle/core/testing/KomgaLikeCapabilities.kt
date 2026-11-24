package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.library.LibraryStart
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.SearchKind
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.library.model.StatusFilter

/**
 * What a Komga-like source can do, from Komga's real capabilities (spec 2c): no audio, no annotations, no ratings,
 * no reading sessions, authors only as metadata, three reading statuses, its own Home rows and a Library that opens
 * on its series. The reference of the tests that check what the screens hide.
 */
val KomgaLikeCapabilities = SourceCapabilities(
    audiobooks = false,
    readAlong = false,
    annotations = false,
    audioBookmarks = false,
    ratings = false,
    readingSessions = false,
    genres = true,
    authors = false,
    history = true,
    homeRows = setOf(HomeRowKind.Continue, HomeRowKind.UpNext, HomeRowKind.RecentlyAdded),
    shelfKinds = setOf(ShelfKind.Collection),
    searchKinds = setOf(SearchKind.Books, SearchKind.Series),
    bookSorts = BookSort.entries.toSet() - BookSort.Author,
    statusFilters = setOf(StatusFilter.Unread, StatusFilter.Reading, StatusFilter.Read),
    formatFilters = FormatFilter.entries.toSet() - FormatFilter.Audio,
    libraryStart = LibraryStart.Series,
)

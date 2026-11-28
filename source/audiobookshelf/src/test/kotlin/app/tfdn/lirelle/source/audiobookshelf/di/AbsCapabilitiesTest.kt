package app.tfdn.lirelle.source.audiobookshelf.di

import app.tfdn.lirelle.core.library.LibraryStart
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.library.model.SearchKind
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.library.model.StatusFilter
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What Audiobookshelf can do once its profile can be browsed (spec 067, §9): genres, authors, Home's rows, its
 * collections and playlists, Search, the Books list's sorts and filters, a Library that opens on its books and always
 * shows one library. Read-along and ratings stay off for good (Audiobookshelf has neither); audiobooks, their
 * bookmarks, the reading sessions and the History come with #68, annotations as #69 decides.
 */
class AbsCapabilitiesTest {

    @Test
    fun `Audiobookshelf declares what spec 067 turns on, and nothing of listening or reading`() {
        assertThat(AbsLibraryModule.capabilities()).isEqualTo(
            SourceCapabilities(
                audiobooks = false,
                readAlong = false,
                annotations = false,
                audioBookmarks = false,
                ratings = false,
                readingSessions = false,
                genres = true,
                authors = true,
                history = false,
                homeRows = setOf(HomeRowKind.Continue, HomeRowKind.UpNext, HomeRowKind.RecentlyAdded),
                shelfKinds = setOf(ShelfKind.Collection, ShelfKind.Playlist),
                searchKinds = setOf(SearchKind.Books, SearchKind.Series, SearchKind.Authors),
                bookSorts = setOf(BookSort.Added, BookSort.Title, BookSort.Author, BookSort.Published),
                statusFilters = setOf(StatusFilter.Unread, StatusFilter.Reading, StatusFilter.Read),
                formatFilters = setOf(FormatFilter.Audio, FormatFilter.Epub, FormatFilter.Pdf, FormatFilter.Comics),
                libraryStart = LibraryStart.Books,
                libraryBooks = true,
                seriesCounts = true,
                downloadToRead = false,
                libraryAlwaysChosen = true,
            ),
        )
    }
}

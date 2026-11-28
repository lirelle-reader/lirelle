package app.tfdn.lirelle.source.audiobookshelf.di

import app.tfdn.lirelle.core.library.LibraryStart
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookSort
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What Audiobookshelf can do once a profile is connected and nothing more (spec 066, §5): nothing yet. Read-along
 * and ratings stay off for good (Audiobookshelf has neither); the rest is turned on by the pull request that makes
 * it work, as the spec's table says — genres, authors, Home's rows, shelves, Search and the filters with #67;
 * audiobooks, their bookmarks and the History with #68; annotations as #69 decides.
 */
class AbsCapabilitiesTest {

    @Test
    fun `Audiobookshelf declares nothing yet, but the one sort its empty list of books is in`() {
        assertThat(AbsLibraryModule.capabilities()).isEqualTo(
            SourceCapabilities(
                audiobooks = false,
                readAlong = false,
                annotations = false,
                audioBookmarks = false,
                ratings = false,
                readingSessions = false,
                genres = false,
                authors = false,
                history = false,
                homeRows = emptySet(),
                shelfKinds = emptySet(),
                searchKinds = emptySet(),
                // The Books tab draws its sort whatever the source offers: a sort with nothing to choose from would
                // open an empty menu.
                bookSorts = setOf(BookSort.Added),
                statusFilters = emptySet(),
                formatFilters = emptySet(),
                libraryStart = LibraryStart.Books,
                // The Books tab is where the Library opens: it is there, empty.
                libraryBooks = true,
                seriesCounts = true,
                downloadToRead = false,
            ),
        )
    }

    @Test
    fun `an Audiobookshelf library opens on its books`() {
        assertThat(AbsLibraryModule.capabilities().libraryStart).isEqualTo(LibraryStart.Books)
    }
}

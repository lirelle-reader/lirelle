package app.tfdn.lirelle.source.kavita.di

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
 * What Kavita can do once a profile can be browsed (spec 050, §9 with §14): its library, without a Books tab and
 * without the numbers of a series' books. Reading, its history and its annotations come with #51.
 */
class KavitaCapabilitiesTest {

    @Test
    fun `a Kavita profile can be browsed, without a Books tab or series counts`() {
        assertThat(KavitaLibraryModule.capabilities()).isEqualTo(
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
                homeRows = setOf(
                    HomeRowKind.Continue,
                    HomeRowKind.UpNext,
                    HomeRowKind.WantToRead,
                    HomeRowKind.RecentlyAdded,
                ),
                shelfKinds = setOf(ShelfKind.Collection),
                searchKinds = setOf(SearchKind.Books, SearchKind.Series, SearchKind.Authors),
                bookSorts = setOf(BookSort.Added, BookSort.Title),
                statusFilters = setOf(StatusFilter.Unread, StatusFilter.Reading, StatusFilter.Read),
                formatFilters = setOf(FormatFilter.Epub, FormatFilter.Pdf, FormatFilter.Comics),
                libraryStart = LibraryStart.Series,
                libraryBooks = false,
                seriesCounts = false,
            ),
        )
    }

    @Test
    fun `a Kavita library opens on its series`() {
        assertThat(KavitaLibraryModule.capabilities().libraryStart).isEqualTo(LibraryStart.Series)
    }
}

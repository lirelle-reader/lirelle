package app.tfdn.lirelle.source.kavita.di

import app.tfdn.lirelle.core.library.LibraryStart
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookSort
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What Kavita can do once a profile is connected and nothing more (spec 049, §6): nothing yet. Each pull request
 * that makes a thing work turns it on here.
 */
class KavitaCapabilitiesTest {

    @Test
    fun `Kavita declares nothing yet, but the one sort its empty list of books is in`() {
        assertThat(KavitaLibraryModule.capabilities()).isEqualTo(
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
                bookSorts = setOf(BookSort.Added),
                statusFilters = emptySet(),
                formatFilters = emptySet(),
                libraryStart = LibraryStart.Series,
            ),
        )
    }

    @Test
    fun `a Kavita library opens on its series`() {
        assertThat(KavitaLibraryModule.capabilities().libraryStart).isEqualTo(LibraryStart.Series)
    }
}

package app.tfdn.lirelle.feature.library

import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.library.LibraryChoiceState
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryChoice
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.InMemoryLibraryPreferences
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.feature.library.authors.AuthorsViewModel
import app.tfdn.lirelle.feature.library.books.BookListScope
import app.tfdn.lirelle.feature.library.books.BookListViewModel
import app.tfdn.lirelle.feature.library.series.SeriesListViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * For a source whose Library always shows one library, the Books, Series and Authors tabs ask for the library shown:
 * the one chosen, else the first of the server's, never all of them. Other sources still ask for all of them.
 */
class LibraryAlwaysChosenListsTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val comics = ServerLibrary(LibraryId("1"), "Comics")
    private val manga = ServerLibrary(LibraryId("2"), "Manga")
    private val novels = ServerLibrary(LibraryId("3"), "Novels")
    private val alwaysOne = SourceCapabilities.Everything.copy(libraryAlwaysChosen = true)
    private val repository = FakeLibraryRepository().apply {
        seriesResult = ApiResult.Success(listOf(sampleSeries("1")))
    }
    private val preferences = InMemoryLibraryPreferences()

    /** The library each tab asks for, Books, Series and Authors, once [choice] is read. */
    private fun asked(capabilities: SourceCapabilities, choice: FakeLibraryChoice): List<ServerLibrary?> {
        BookListViewModel(BookListScope.library(), repository, preferences, capabilities, choice)
        SeriesListViewModel(capabilities, repository, preferences, choice)
        AuthorsViewModel(capabilities, repository, preferences, choice)
        return listOf(
            repository.bookRequests.last().second.library,
            repository.seriesLibraries.last(),
            repository.authorLibraries.last(),
        )
    }

    @Test
    fun `nothing chosen asks for the first library`() = runTest(mainRule.dispatcher) {
        val choice = FakeLibraryChoice(LibraryChoiceState(listOf(comics, manga)))

        assertThat(asked(alwaysOne, choice)).containsExactly(comics, comics, comics)
        assertThat(choice.state.value.chosen).isNull()
    }

    @Test
    fun `the chosen library is asked for`() = runTest(mainRule.dispatcher) {
        val choice = FakeLibraryChoice(LibraryChoiceState(listOf(comics, manga), chosen = manga))

        assertThat(asked(alwaysOne, choice)).containsExactly(manga, manga, manga)
    }

    @Test
    fun `a chosen library no longer in the list asks for the first`() = runTest(mainRule.dispatcher) {
        val choice = FakeLibraryChoice(LibraryChoiceState(listOf(comics, manga), chosen = novels))

        assertThat(asked(alwaysOne, choice)).containsExactly(comics, comics, comics)
        assertThat(choice.state.value.chosen).isEqualTo(novels)
    }

    @Test
    fun `a single library is asked for, not all of them`() = runTest(mainRule.dispatcher) {
        val choice = FakeLibraryChoice(LibraryChoiceState(listOf(novels)))

        assertThat(asked(alwaysOne, choice)).containsExactly(novels, novels, novels)
    }

    @Test
    fun `other sources still ask for all libraries when none is chosen`() = runTest(mainRule.dispatcher) {
        val choice = FakeLibraryChoice(LibraryChoiceState(listOf(comics, manga)))

        assertThat(asked(SourceCapabilities.Everything, choice)).containsExactly(null, null, null)
    }
}

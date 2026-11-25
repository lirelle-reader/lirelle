package app.tfdn.lirelle.feature.library.books

import androidx.paging.testing.asSnapshot
import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.ids.ShelfId
import app.tfdn.lirelle.core.library.LibraryChoiceState
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListSource
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.library.model.StatusFilter
import app.tfdn.lirelle.core.testing.FakeLibraryChoice
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.InMemoryLibraryPreferences
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleBook
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class BookListViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeLibraryRepository().apply { books = listOf(sampleBook("1"), sampleBook("2")) }
    private val preferences = InMemoryLibraryPreferences()
    private val comics = ServerLibrary(LibraryId("1"), "Comics")
    private val manga = ServerLibrary(LibraryId("2"), "Manga")
    private val choice = FakeLibraryChoice(LibraryChoiceState(listOf(comics, manga)))

    private fun viewModel(
        scope: BookListScope = BookListScope.library(),
        capabilities: SourceCapabilities = SourceCapabilities.Everything,
    ) = BookListViewModel(scope, repository, preferences, capabilities, choice)

    @Test
    fun `a source that can do everything offers every sort and filter`() = runTest(mainRule.dispatcher) {
        val choices = viewModel().state.value.choices

        assertThat(choices.sorts).containsExactlyElementsIn(BookSort.entries).inOrder()
        assertThat(choices.formats).containsExactlyElementsIn(FormatFilter.entries).inOrder()
        assertThat(choices.statuses).containsExactlyElementsIn(StatusFilter.entries).inOrder()
        assertThat(choices.readAlong).isTrue()
    }

    @Test
    fun `a Komga-like source offers its sorts, three statuses, no Audio and no read-along`() =
        runTest(mainRule.dispatcher) {
            val choices = viewModel(capabilities = KomgaLikeCapabilities).state.value.choices

            assertThat(choices.sorts)
                .containsExactly(BookSort.Added, BookSort.Title, BookSort.Series, BookSort.Published).inOrder()
            assertThat(choices.formats)
                .containsExactly(FormatFilter.Epub, FormatFilter.Pdf, FormatFilter.Comics).inOrder()
            assertThat(choices.statuses)
                .containsExactly(StatusFilter.Unread, StatusFilter.Reading, StatusFilter.Read).inOrder()
            assertThat(choices.readAlong).isFalse()
        }

    @Test
    fun `a stored sort the source does not offer reads back as the default, in its own direction`() =
        runTest(mainRule.dispatcher) {
            preferences.update("books") { it.copy(sort = "Author", descending = false) }

            val viewModel = viewModel(capabilities = KomgaLikeCapabilities)
            viewModel.books.asSnapshot()

            assertThat(viewModel.state.value.request.sort).isEqualTo(BookSort.Added)
            assertThat(viewModel.state.value.request.direction).isEqualTo(SortDirection.Descending)
            assertThat(repository.bookRequests.map { it.second.sort }.distinct()).containsExactly(BookSort.Added)
        }

    @Test
    fun `a filter the source does not offer is not set`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel(capabilities = KomgaLikeCapabilities)

        viewModel.onToggleFormat(FormatFilter.Audio)
        viewModel.onToggleStatus(StatusFilter.WantToRead)
        viewModel.onToggleStatus(StatusFilter.Abandoned)
        viewModel.onReadAlongOnly(true)
        viewModel.onSort(BookSort.Author)
        viewModel.books.asSnapshot()

        assertThat(viewModel.state.value.request.filters).isEqualTo(BookFilters())
        assertThat(viewModel.state.value.isFiltered).isFalse()
        assertThat(viewModel.state.value.request.sort).isEqualTo(BookSort.Added)
        assertThat(repository.bookRequests.map { it.second.filters }.distinct()).containsExactly(BookFilters())
    }

    @Test
    fun `starts with the default sort, a grid and no filter`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        assertThat(viewModel.books.asSnapshot()).hasSize(2)
        val state = viewModel.state.value
        assertThat(state.request.sort).isEqualTo(BookSort.Added)
        assertThat(state.request.direction).isEqualTo(SortDirection.Descending)
        assertThat(state.request.filters).isEqualTo(BookFilters())
        assertThat(state.presentation).isEqualTo(Presentation.Grid)
        assertThat(state.total).isEqualTo(2)
        assertThat(repository.bookRequests.last().first).isEqualTo(BookListSource.AllBooks)
    }

    @Test
    fun `choosing another sort uses its natural direction and is remembered`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onSort(BookSort.Title)
        viewModel.books.asSnapshot()

        assertThat(viewModel.state.value.request.sort).isEqualTo(BookSort.Title)
        assertThat(viewModel.state.value.request.direction).isEqualTo(SortDirection.Ascending)
        assertThat(repository.bookRequests.last().second.sort).isEqualTo(BookSort.Title)
        assertThat(preferences.current("books").sort).isEqualTo("Title")
        assertThat(preferences.current("books").descending).isFalse()
    }

    @Test
    fun `choosing the current sort again reverses it`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onSort(BookSort.Added)

        assertThat(viewModel.state.value.request.direction).isEqualTo(SortDirection.Ascending)
    }

    @Test
    fun `a stored sort is restored`() = runTest(mainRule.dispatcher) {
        preferences.update("books") { it.copy(presentation = Presentation.List, sort = "Series", descending = true) }

        val state = viewModel().state.value

        assertThat(state.request.sort).isEqualTo(BookSort.Series)
        assertThat(state.request.direction).isEqualTo(SortDirection.Descending)
        assertThat(state.presentation).isEqualTo(Presentation.List)
    }

    @Test
    fun `every sort name stored by the previous versions is restored as the same sort`() =
        runTest(mainRule.dispatcher) {
            val stored = mapOf(
                "Added" to BookSort.Added,
                "Title" to BookSort.Title,
                "Author" to BookSort.Author,
                "Series" to BookSort.Series,
                "Published" to BookSort.Published,
            )

            val restored = stored.mapValues { (name, _) ->
                preferences.update("books") { it.copy(sort = name) }
                viewModel().state.value.request.sort
            }

            assertThat(restored).isEqualTo(stored)
        }

    @Test
    fun `an unknown stored sort falls back to the default`() = runTest(mainRule.dispatcher) {
        preferences.update("books") { it.copy(sort = "Gone") }

        assertThat(viewModel().state.value.request.sort).isEqualTo(BookSort.Added)
    }

    @Test
    fun `filters toggle, combine and reach the repository`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onToggleFormat(FormatFilter.Audio)
        viewModel.onToggleFormat(FormatFilter.Pdf)
        viewModel.onToggleFormat(FormatFilter.Pdf)
        viewModel.onToggleStatus(StatusFilter.Unread)
        viewModel.onReadAlongOnly(true)
        viewModel.books.asSnapshot()

        val expected = BookFilters(setOf(FormatFilter.Audio), setOf(StatusFilter.Unread), readAlongOnly = true)
        assertThat(viewModel.state.value.request.filters).isEqualTo(expected)
        assertThat(repository.bookRequests.last().second.filters).isEqualTo(expected)
    }

    @Test
    fun `clearing filters keeps the sort`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onSort(BookSort.Author)
        viewModel.onReadAlongOnly(true)

        viewModel.onClearFilters()

        assertThat(viewModel.state.value.request.filters.isActive).isFalse()
        assertThat(viewModel.state.value.request.sort).isEqualTo(BookSort.Author)
    }

    @Test
    fun `filters are not remembered`() = runTest(mainRule.dispatcher) {
        viewModel().onReadAlongOnly(true)

        assertThat(viewModel().state.value.request.filters.isActive).isFalse()
    }

    @Test
    fun `the presentation is remembered`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onPresentation(Presentation.List)

        assertThat(viewModel.state.value.presentation).isEqualTo(Presentation.List)
        assertThat(preferences.current("books").presentation).isEqualTo(Presentation.List)
    }

    @Test
    fun `shelves keep their own preferences`() = runTest(mainRule.dispatcher) {
        val shelf = BookListScope.shelf(BookListSource.Collection(ShelfId("7")))
        viewModel(shelf).onPresentation(Presentation.List)

        assertThat(preferences.current("shelf").presentation).isEqualTo(Presentation.List)
        assertThat(preferences.current("books").presentation).isEqualTo(Presentation.Grid)
        assertThat(viewModel(shelf).state.value.presentation).isEqualTo(Presentation.List)
        assertThat(repository.bookRequests.last().first).isEqualTo(BookListSource.Collection(ShelfId("7")))
    }

    @Test
    fun `a genre scope asks the books of that genre`() = runTest(mainRule.dispatcher) {
        viewModel(BookListScope.genre("Fantasy"))

        assertThat(repository.bookRequests.last().second.genre).isEqualTo("Fantasy")
        assertThat(repository.bookRequests.last().first).isEqualTo(BookListSource.AllBooks)
    }

    @Test
    fun `a text scope asks the books matching that text`() = runTest(mainRule.dispatcher) {
        viewModel(BookListScope.search("mars"))

        assertThat(repository.bookRequests.last().second.text).isEqualTo("mars")
    }

    @Test
    fun `a status scope is always about that status, and clearing keeps it`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel(BookListScope.status(StatusFilter.WantToRead, "want-to-read"))
        assertThat(viewModel.state.value.request.filters.statuses).containsExactly(StatusFilter.WantToRead)
        assertThat(viewModel.state.value.isFiltered).isFalse()
        assertThat(viewModel.state.value.statusLocked).isTrue()

        viewModel.onToggleFormat(FormatFilter.Audio)
        assertThat(viewModel.state.value.isFiltered).isTrue()
        assertThat(viewModel.state.value.request.filters.statuses).containsExactly(StatusFilter.WantToRead)

        viewModel.onClearFilters()
        assertThat(viewModel.state.value.request.filters)
            .isEqualTo(BookFilters(statuses = setOf(StatusFilter.WantToRead)))
        assertThat(viewModel.state.value.isFiltered).isFalse()
    }

    @Test
    fun `the status of a status scope cannot be unticked nor added to`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel(BookListScope.status(StatusFilter.WantToRead, "want-to-read"))

        viewModel.onToggleStatus(StatusFilter.WantToRead)
        viewModel.onToggleStatus(StatusFilter.Read)

        assertThat(viewModel.state.value.request.filters.statuses).containsExactly(StatusFilter.WantToRead)
        assertThat(viewModel.state.value.isFiltered).isFalse()
        assertThat(repository.bookRequests.map { it.second.filters.statuses }.distinct())
            .containsExactly(setOf(StatusFilter.WantToRead))
    }

    @Test
    fun `a list that is not about a status offers the status filter`() = runTest(mainRule.dispatcher) {
        assertThat(viewModel().state.value.statusLocked).isFalse()
    }

    @Test
    fun `a list without a starting status is filtered as soon as a filter is set`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        assertThat(viewModel.state.value.isFiltered).isFalse()

        viewModel.onToggleStatus(StatusFilter.Unread)

        assertThat(viewModel.state.value.isFiltered).isTrue()
    }

    @Test
    fun `each scope remembers its own sort`() = runTest(mainRule.dispatcher) {
        viewModel(BookListScope.genre("Fantasy")).onSort(BookSort.Title)

        assertThat(viewModel(BookListScope.genre("Horror")).state.value.request.sort).isEqualTo(BookSort.Title)
        assertThat(viewModel(BookListScope.library()).state.value.request.sort).isEqualTo(BookSort.Added)
    }

    @Test
    fun `only the Library's own list of books follows the chosen library`() {
        assertThat(BookListScope.library().followsLibrary).isTrue()
        assertThat(otherScopes.map { it.followsLibrary }.distinct()).containsExactly(false)
    }

    @Test
    fun `the Books tab asks for every library when none is chosen`() = runTest(mainRule.dispatcher) {
        viewModel().books.asSnapshot()

        assertThat(repository.bookRequests.last().second.library).isNull()
    }

    @Test
    fun `the Books tab asks for the chosen library`() = runTest(mainRule.dispatcher) {
        choice.choose(comics)

        viewModel().books.asSnapshot()

        assertThat(repository.bookRequests.map { it.second.library }).containsExactly(comics)
    }

    @Test
    fun `another choice gives the Books tab a new list, its sort and filters kept`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onSort(BookSort.Title)
        viewModel.onToggleFormat(FormatFilter.Epub)
        viewModel.books.asSnapshot()
        val before = repository.bookRequests.last().second
        val asked = repository.bookRequests.size

        choice.choose(manga)
        viewModel.books.asSnapshot()

        assertThat(repository.bookRequests).hasSize(asked + 1)
        assertThat(repository.bookRequests.last().second).isEqualTo(before.copy(library = manga))
        assertThat(before.sort).isEqualTo(BookSort.Title)
        assertThat(before.filters.formats).containsExactly(FormatFilter.Epub)
        assertThat(viewModel.state.value.request.library).isEqualTo(manga)

        choice.choose(null)
        viewModel.books.asSnapshot()

        assertThat(repository.bookRequests.last().second).isEqualTo(before)
    }

    @Test
    fun `a chosen library renamed on the server gives the Books tab a new list under its new name`() =
        runTest(mainRule.dispatcher) {
            choice.choose(comics)
            val viewModel = viewModel()
            viewModel.books.asSnapshot()
            val asked = repository.bookRequests.size
            val renamed = comics.copy(name = "Bandes dessinées")

            choice.libraries(listOf(renamed, manga))
            viewModel.books.asSnapshot()

            assertThat(repository.bookRequests).hasSize(asked + 1)
            assertThat(repository.bookRequests.last().second.library).isEqualTo(renamed)
        }

    @Test
    fun `the libraries read again as they were ask the Books tab nothing more`() = runTest(mainRule.dispatcher) {
        choice.choose(comics)
        val viewModel = viewModel()
        viewModel.books.asSnapshot()
        val asked = repository.bookRequests.size

        choice.libraries(listOf(comics, manga, ServerLibrary(LibraryId("3"), "Novels")))
        viewModel.books.asSnapshot()

        assertThat(repository.bookRequests).hasSize(asked)
    }

    @Test
    fun `the other lists of books never ask for a library, whatever the choice`() = runTest(mainRule.dispatcher) {
        choice.choose(comics)

        otherScopes.forEach { scope ->
            val viewModel = viewModel(scope)
            viewModel.books.asSnapshot()
            val asked = repository.bookRequests.size

            choice.choose(manga)
            viewModel.books.asSnapshot()
            choice.choose(comics)

            assertWithMessage("requests of $scope").that(repository.bookRequests).hasSize(asked)
        }
        assertThat(repository.bookRequests.map { it.second.library }.distinct()).containsExactly(null)
    }

    private val otherScopes = listOf(
        BookListScope.genre("Fantasy"),
        BookListScope.search("lighthouse"),
        BookListScope.status(StatusFilter.Reading, "reading"),
        BookListScope.recentlyAdded(),
        BookListScope.shelf(BookListSource.Collection(ShelfId("7"))),
        BookListScope.shelf(BookListSource.SmartList(ShelfId("3"))),
    )

    @Test
    fun `a sort or a filter goes on in the same pages, another library has pages of its own`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            val first = viewModel.pages.value!!
            assertThat(first.library).isNull()

            viewModel.onSort(BookSort.Title)
            viewModel.onToggleFormat(FormatFilter.Epub)
            assertThat(viewModel.pages.value).isSameInstanceAs(first)

            choice.choose(manga)
            assertThat(viewModel.pages.value).isNotSameInstanceAs(first)
            assertThat(viewModel.pages.value!!.library).isEqualTo(manga)
            assertThat(viewModel.pages.value!!.items.asSnapshot()).hasSize(2)
            assertThat(repository.bookRequests.last().second.library).isEqualTo(manga)
        }

    @Test
    fun `a list that does not follow the library keeps its pages whatever is chosen`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel(BookListScope.genre("Fantasy"))
        val first = viewModel.pages.value!!

        choice.choose(manga)

        assertThat(viewModel.pages.value).isSameInstanceAs(first)
    }
}

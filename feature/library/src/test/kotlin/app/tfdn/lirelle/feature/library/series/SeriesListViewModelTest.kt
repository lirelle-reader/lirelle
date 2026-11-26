package app.tfdn.lirelle.feature.library.series

import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.library.LibraryChoiceState
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.SeriesProgressFilter
import app.tfdn.lirelle.core.library.model.SeriesSort
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryChoice
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.InMemoryLibraryPreferences
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.core.ui.Loadable
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SeriesListViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val small = sampleSeries("1", name = "Beta", books = 2, read = 0)
    private val large = sampleSeries("2", name = "Alpha", books = 9, read = 9)
    private val started = sampleSeries("3", name = "Gamma", books = 5, read = 2)
    private val repository = FakeLibraryRepository().apply {
        seriesResult = ApiResult.Success(listOf(small, large, started))
    }
    private val preferences = InMemoryLibraryPreferences()
    private val comics = ServerLibrary(LibraryId("1"), "Comics")
    private val manga = ServerLibrary(LibraryId("2"), "Manga")
    private val choice = FakeLibraryChoice(LibraryChoiceState(listOf(comics, manga)))

    private fun viewModel(capabilities: SourceCapabilities = SourceCapabilities.Everything) =
        SeriesListViewModel(capabilities, repository, preferences, choice)

    private val withoutCounts = SourceCapabilities.Everything.copy(seriesCounts = false)

    private fun SeriesListViewModel.names() = (state.value.series as Loadable.Ready).value.map { it.name }

    @Test
    fun `loads the series sorted by name`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        assertThat(viewModel.names()).containsExactly("Alpha", "Beta", "Gamma").inOrder()
        assertThat(viewModel.state.value.sort).isEqualTo(SeriesSort.Name)
        assertThat(viewModel.state.value.direction).isEqualTo(SortDirection.Ascending)
    }

    @Test
    fun `shows loading until the list arrives`() = runTest(mainRule.dispatcher) {
        repository.gate = CompletableDeferred()
        val viewModel = viewModel()

        assertThat(viewModel.state.value.series).isEqualTo(Loadable.Loading)
        repository.gate!!.complete(Unit)

        assertThat(viewModel.state.value.series).isInstanceOf(Loadable.Ready::class.java)
    }

    @Test
    fun `a failure can be retried`() = runTest(mainRule.dispatcher) {
        repository.seriesResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = viewModel()
        assertThat(viewModel.state.value.series).isEqualTo(Loadable.Failed(ApiError.ServerUnreachable))

        repository.seriesResult = ApiResult.Success(listOf(small))
        viewModel.load()

        assertThat(viewModel.names()).containsExactly("Beta")
    }

    @Test
    fun `sorting by unread books starts with the most unread and is remembered`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onSort(SeriesSort.UnreadCount)

        assertThat(viewModel.names()).containsExactly("Gamma", "Beta", "Alpha").inOrder()
        assertThat(preferences.current("series").sort).isEqualTo("UnreadCount")
        assertThat(preferences.current("series").descending).isTrue()
        assertThat(repository.calls.count { it == "series" }).isEqualTo(1)
    }

    @Test
    fun `choosing the current sort again reverses it`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onSort(SeriesSort.Name)

        assertThat(viewModel.names()).containsExactly("Gamma", "Beta", "Alpha").inOrder()
    }

    @Test
    fun `a stored sort is restored`() = runTest(mainRule.dispatcher) {
        preferences.update("series") { it.copy(sort = "BookCount", descending = true) }

        assertThat(viewModel().names()).containsExactly("Alpha", "Gamma", "Beta").inOrder()
    }

    @Test
    fun `a source with series counts shows them and offers every sort`() = runTest(mainRule.dispatcher) {
        val state = viewModel().state.value

        assertThat(state.showCounts).isTrue()
        assertThat(state.sorts).containsExactlyElementsIn(SeriesSort.entries).inOrder()
    }

    @Test
    fun `without series counts the sorts made of them are not offered`() = runTest(mainRule.dispatcher) {
        val state = viewModel(withoutCounts).state.value

        assertThat(state.showCounts).isFalse()
        assertThat(state.sorts).containsExactly(SeriesSort.Name, SeriesSort.RecentlyAdded).inOrder()
    }

    @Test
    fun `without series counts a stored count sort falls back to the name`() = runTest(mainRule.dispatcher) {
        preferences.update("series") { it.copy(sort = "BookCount", descending = true) }

        val viewModel = viewModel(withoutCounts)

        assertThat(viewModel.state.value.sort).isEqualTo(SeriesSort.Name)
        assertThat(viewModel.state.value.direction).isEqualTo(SortDirection.Ascending)
        assertThat(viewModel.names()).containsExactly("Alpha", "Beta", "Gamma").inOrder()
    }

    @Test
    fun `without series counts the progress filter is not applied`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel(withoutCounts)

        viewModel.onFilter(SeriesProgressFilter.InProgress)

        assertThat(viewModel.state.value.filter).isNull()
        assertThat(viewModel.names()).hasSize(3)
    }

    @Test
    fun `the progress filter narrows the list and is not remembered`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onFilter(SeriesProgressFilter.InProgress)
        assertThat(viewModel.names()).containsExactly("Gamma")

        viewModel.onFilter(null)
        assertThat(viewModel.names()).hasSize(3)
        assertThat(viewModel().state.value.filter).isNull()
    }

    @Test
    fun `refreshing keeps the list on screen and survives a failure`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        repository.gate = CompletableDeferred()

        viewModel.refresh()
        assertThat(viewModel.state.value.refreshing).isTrue()
        assertThat(viewModel.names()).hasSize(3)

        repository.seriesResult = ApiResult.Failure(ApiError.ServerUnreachable)
        repository.gate!!.complete(Unit)

        assertThat(viewModel.state.value.refreshing).isFalse()
        assertThat(viewModel.names()).hasSize(3)
    }

    @Test
    fun `asks for every library when none is chosen`() = runTest(mainRule.dispatcher) {
        viewModel()

        assertThat(repository.seriesLibraries).containsExactly(null)
    }

    @Test
    fun `asks for the chosen library at the first load, a retry and a refresh`() = runTest(mainRule.dispatcher) {
        choice.choose(comics)
        val viewModel = viewModel()
        assertThat(repository.seriesLibraries).containsExactly(comics)

        viewModel.load()
        viewModel.refresh()

        assertThat(repository.seriesLibraries).containsExactly(comics, comics, comics)
    }

    @Test
    fun `another choice loads the series again from the start, the sort and the filter kept`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            viewModel.onSort(SeriesSort.BookCount)
            viewModel.onFilter(SeriesProgressFilter.InProgress)
            repository.gate = CompletableDeferred()

            choice.choose(manga)

            // The list of the library left is not kept under the new one's name.
            assertThat(viewModel.state.value.series).isEqualTo(Loadable.Loading)
            repository.gate!!.complete(Unit)
            assertThat(repository.seriesLibraries).containsExactly(null, manga).inOrder()
            assertThat(viewModel.names()).containsExactly("Gamma")
            assertThat(viewModel.state.value.sort).isEqualTo(SeriesSort.BookCount)
            assertThat(viewModel.state.value.filter).isEqualTo(SeriesProgressFilter.InProgress)
        }

    @Test
    fun `a chosen library renamed on the server loads the series again under its new name`() =
        runTest(mainRule.dispatcher) {
            choice.choose(comics)
            viewModel()
            val renamed = comics.copy(name = "Bandes dessinées")

            choice.libraries(listOf(renamed, manga))

            assertThat(repository.seriesLibraries).containsExactly(comics, renamed).inOrder()
        }

    @Test
    fun `the libraries read again as they were load nothing more`() = runTest(mainRule.dispatcher) {
        choice.choose(comics)
        viewModel()

        choice.libraries(listOf(manga, comics))

        assertThat(repository.seriesLibraries).containsExactly(comics)
    }

    @Test
    fun `a refresh answered after another library was chosen does not show the library left`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            val refresh = CompletableDeferred<Unit>()
            repository.gate = refresh
            viewModel.refresh()
            repository.seriesResult = ApiResult.Success(listOf(small))
            repository.gate = null

            choice.choose(manga)
            assertThat(viewModel.names()).containsExactly("Beta")
            refresh.complete(Unit)

            assertThat(viewModel.names()).containsExactly("Beta")
            assertThat(viewModel.state.value.refreshing).isFalse()
        }

    @Test
    fun `a retry answered after another library was chosen does not show the library left`() =
        runTest(mainRule.dispatcher) {
            repository.seriesResult = ApiResult.Failure(ApiError.ServerUnreachable)
            val viewModel = viewModel()
            val retry = CompletableDeferred<Unit>()
            repository.gate = retry
            repository.seriesResult = ApiResult.Success(listOf(small))
            viewModel.load()
            repository.gate = null
            repository.seriesResult = ApiResult.Success(listOf(large))

            choice.choose(manga)
            assertThat(viewModel.names()).containsExactly("Alpha")
            retry.complete(Unit)

            assertThat(viewModel.names()).containsExactly("Alpha")
        }

    @Test
    fun `a retry asked twice shows the answer of the last one`() = runTest(mainRule.dispatcher) {
        repository.seriesResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = viewModel()
        val first = CompletableDeferred<Unit>()
        repository.gate = first
        repository.seriesResult = ApiResult.Success(listOf(small))
        viewModel.load()
        repository.gate = null
        repository.seriesResult = ApiResult.Success(listOf(large))

        viewModel.load()
        first.complete(Unit)

        assertThat(viewModel.names()).containsExactly("Alpha")
    }

    @Test
    fun `another library chosen during a refresh leaves no refresh shown over its load`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            val refresh = CompletableDeferred<Unit>()
            repository.gate = refresh
            viewModel.refresh()
            assertThat(viewModel.state.value.refreshing).isTrue()
            val load = CompletableDeferred<Unit>()
            repository.gate = load

            choice.choose(manga)

            assertThat(viewModel.state.value.refreshing).isFalse()
            assertThat(viewModel.state.value.series).isEqualTo(Loadable.Loading)
            refresh.complete(Unit)
            assertThat(viewModel.state.value.series).isEqualTo(Loadable.Loading)
            load.complete(Unit)
            assertThat(viewModel.names()).hasSize(3)
            assertThat(viewModel.state.value.refreshing).isFalse()
        }
}

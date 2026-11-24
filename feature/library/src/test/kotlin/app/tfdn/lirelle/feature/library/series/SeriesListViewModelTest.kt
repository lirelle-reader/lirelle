package app.tfdn.lirelle.feature.library.series

import app.tfdn.lirelle.core.library.model.SeriesProgressFilter
import app.tfdn.lirelle.core.library.model.SeriesSort
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
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

    private fun viewModel() = SeriesListViewModel(repository, preferences)

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
}

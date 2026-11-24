package app.tfdn.lirelle.feature.search

import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.core.ui.Loadable
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ResultListViewModelsTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private val fake = FakeLibraryRepository()

    @Test
    fun `the series list asks for the series matching the text`() = runTest(mainRule.dispatcher) {
        val found = listOf(sampleSeries("40", "A Saga"))
        fake.seriesResult = ApiResult.Success(found)

        val viewModel = SeriesResultsViewModel("mars", fake)

        assertThat(fake.calls).containsExactly("series mars")
        assertThat(viewModel.state.value).isEqualTo(Loadable.Ready(found))
    }

    @Test
    fun `a failed series list can be loaded again`() = runTest(mainRule.dispatcher) {
        fake.seriesResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = SeriesResultsViewModel("mars", fake)
        assertThat(viewModel.state.value).isEqualTo(Loadable.Failed(ApiError.ServerUnreachable))

        fake.seriesResult = ApiResult.Success(emptyList())
        viewModel.load()

        assertThat(viewModel.state.value).isEqualTo(Loadable.Ready(emptyList<Nothing>()))
        assertThat(fake.calls).containsExactly("series mars", "series mars")
    }

    @Test
    fun `the author list asks for the authors matching the text`() = runTest(mainRule.dispatcher) {
        AuthorResultsViewModel("writer", fake, SourceCapabilities.Everything)

        assertThat(fake.authorTexts).containsExactly("writer")
    }
}

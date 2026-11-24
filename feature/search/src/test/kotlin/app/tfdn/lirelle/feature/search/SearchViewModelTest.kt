package app.tfdn.lirelle.feature.search

import androidx.lifecycle.SavedStateHandle
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.InMemoryRecentSearches
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.ui.Loadable
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

// The rule installs an unconfined test dispatcher as Main, and `runTest` is given the same one:
// the delays of the view model run on the virtual clock these tests advance.
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    private var now = 0L
    private val fake = FakeLibraryRepository()
    private val recent = InMemoryRecentSearches(listOf("old"))

    private fun viewModel(saved: SavedStateHandle = SavedStateHandle(), repository: LibraryRepository = fake) =
        SearchViewModel(saved, repository, recent, MonotonicClock { now }, SourceCapabilities.Everything)

    private fun genreCalls() = fake.calls.count { it == "genres" }

    private fun searches() = fake.calls.filter { it.startsWith("search ") }

    private fun SearchViewModel.foundText() = (state.value.results as Loadable.Ready<SearchResults>).value.text

    @Test
    fun `nothing is searched before two characters`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onText(" m ")
        advanceTimeBy(1_000)

        assertThat(searches()).isEmpty()
        assertThat(viewModel.state.value.results).isNull()
    }

    @Test
    fun `the search runs once typing pauses, on the trimmed text`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onText("ma")
        advanceTimeBy(200)
        viewModel.onText("mars ")
        advanceTimeBy(299)
        assertThat(searches()).isEmpty()

        advanceTimeBy(2)
        assertThat(searches()).containsExactly("search mars")
        assertThat(viewModel.foundText()).isEqualTo("mars")
    }

    @Test
    fun `clearing the text drops the results`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onText("mars")
        advanceTimeBy(301)

        viewModel.onText("")
        advanceTimeBy(1)

        assertThat(viewModel.state.value.results).isNull()
    }

    @Test
    fun `earlier results stay while the next search runs`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onText("mars")
        advanceTimeBy(301)

        viewModel.onText("marsh")
        advanceTimeBy(100)

        assertThat(viewModel.foundText()).isEqualTo("mars")
    }

    @Test
    fun `a newer text cancels the search in flight`() = runTest(mainRule.dispatcher) {
        val slow = object : LibraryRepository by fake {
            override suspend fun search(text: String): ApiResult<SearchResults> {
                if (text == "mars") delay(1_000)
                return fake.search(text)
            }
        }
        val viewModel = viewModel(repository = slow)
        viewModel.onText("mars")
        advanceTimeBy(301)
        assertThat(viewModel.state.value.results).isEqualTo(Loadable.Loading)

        viewModel.onText("marsh")
        advanceTimeBy(301)
        assertThat(viewModel.foundText()).isEqualTo("marsh")

        advanceTimeBy(5_000)
        assertThat(viewModel.foundText()).isEqualTo("marsh")
        assertThat(searches()).containsExactly("search marsh")
    }

    @Test
    fun `a failure can be retried with the same text`() = runTest(mainRule.dispatcher) {
        fake.searchAnswer = { ApiResult.Failure(ApiError.ServerUnreachable) }
        val viewModel = viewModel()
        viewModel.onText("mars")
        advanceTimeBy(301)
        assertThat(viewModel.state.value.results).isEqualTo(Loadable.Failed(ApiError.ServerUnreachable))

        fake.searchAnswer = { ApiResult.Success(SearchResults(it)) }
        viewModel.onRetry()
        advanceTimeBy(301)

        assertThat(viewModel.state.value.results).isInstanceOf(Loadable.Ready::class.java)
        assertThat(viewModel.state.value.text).isEqualTo("mars")
    }

    @Test
    fun `opening a result records the text, typing does not`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onText("mars")
        advanceTimeBy(301)
        assertThat(viewModel.state.value.recent).containsExactly("old")

        viewModel.onResultOpened()

        assertThat(viewModel.state.value.recent).containsExactly("mars", "old").inOrder()
    }

    @Test
    fun `opening a result records the text the results are for, not what was typed since`() =
        runTest(mainRule.dispatcher) {
            val viewModel = viewModel()
            viewModel.onText("mars")
            advanceTimeBy(301)

            viewModel.onText("marsh")
            advanceTimeBy(100)
            viewModel.onResultOpened()

            assertThat(viewModel.state.value.recent).containsExactly("mars", "old").inOrder()
        }

    @Test
    fun `recent searches can be run, removed and cleared`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onText("old")
        advanceTimeBy(301)
        assertThat(searches()).containsExactly("search old")

        viewModel.onRemoveRecent("old")
        assertThat(viewModel.state.value.recent).isEmpty()

        recent.record("a")
        viewModel.onClearRecent()
        assertThat(viewModel.state.value.recent).isEmpty()
    }

    @Test
    fun `genres are loaded at the start, and a failure leaves none`() = runTest(mainRule.dispatcher) {
        fake.genresResult = ApiResult.Success(listOf(Genre("Fantasy", 3)))
        assertThat(viewModel().state.value.genres).containsExactly(Genre("Fantasy", 3))
        assertThat(fake.calls.filter { it == "genres" }).hasSize(1)

        fake.genresResult = ApiResult.Failure(ApiError.ServerUnreachable)
        assertThat(viewModel().state.value.genres).isEmpty()
    }

    @Test
    fun `genres that failed to load are asked again when the screen is shown`() = runTest(mainRule.dispatcher) {
        fake.genresResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = viewModel()
        fake.genresResult = ApiResult.Success(listOf(Genre("Fantasy", 3)))

        viewModel.onShown()

        assertThat(viewModel.state.value.genres).containsExactly(Genre("Fantasy", 3))
    }

    @Test
    fun `fresh genres are not asked again, old ones are`() = runTest(mainRule.dispatcher) {
        fake.genresResult = ApiResult.Success(listOf(Genre("Fantasy", 3)))
        val viewModel = viewModel()

        now = 119_000
        viewModel.onShown()
        assertThat(genreCalls()).isEqualTo(1)

        fake.genresResult = ApiResult.Success(listOf(Genre("Fantasy", 4)))
        now = 121_000
        viewModel.onShown()
        assertThat(genreCalls()).isEqualTo(2)
        assertThat(viewModel.state.value.genres).containsExactly(Genre("Fantasy", 4))
    }

    @Test
    fun `a library without genres is not asked again while that answer is fresh`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onShown()

        assertThat(genreCalls()).isEqualTo(1)
    }

    @Test
    fun `old genres stay when their reload fails`() = runTest(mainRule.dispatcher) {
        fake.genresResult = ApiResult.Success(listOf(Genre("Fantasy", 3)))
        val viewModel = viewModel()
        fake.genresResult = ApiResult.Failure(ApiError.ServerUnreachable)

        now = 121_000
        viewModel.onShown()

        assertThat(viewModel.state.value.genres).containsExactly(Genre("Fantasy", 3))
    }

    @Test
    fun `being shown while the genres load asks nothing more`() = runTest(mainRule.dispatcher) {
        val gate = CompletableDeferred<Unit>()
        fake.gate = gate
        val viewModel = viewModel()

        viewModel.onShown()

        assertThat(genreCalls()).isEqualTo(1)
        gate.complete(Unit)
    }

    @Test
    fun `the text survives the process, and its results come back`() = runTest(mainRule.dispatcher) {
        val saved = SavedStateHandle()
        viewModel(saved).onText("mars")

        val restored = viewModel(saved)
        assertThat(restored.state.value.text).isEqualTo("mars")

        advanceTimeBy(301)
        assertThat(restored.foundText()).isEqualTo("mars")
    }
}

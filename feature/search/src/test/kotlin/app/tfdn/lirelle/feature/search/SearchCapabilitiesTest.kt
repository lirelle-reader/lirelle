package app.tfdn.lirelle.feature.search

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.library.model.SearchKind
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.library.model.SearchSection
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.InMemoryRecentSearches
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.core.ui.Loadable
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Search shows only the results and suggestions its source offers, on a phone as on a tablet (spec 2c). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SearchCapabilitiesTest {

    @get:Rule(order = 0)
    val mainRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = FakeLibraryRepository().apply {
        genresResult = ApiResult.Success(listOf(Genre("Fantasy", 3)))
        searchAnswer = { text ->
            ApiResult.Success(
                SearchResults(
                    text = text,
                    books = SearchSection(listOf(sampleBook("1", "Book of Mars")), total = 1),
                    series = SearchSection(listOf(sampleSeries("40", "A Saga of Mars")), total = 1),
                    authors = SearchSection(listOf(Author(AuthorId("50"), "Marsha Writer", 4, null)), total = 1),
                ),
            )
        }
    }
    private val opened = mutableListOf<Any>()
    private var backs = 0

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun viewModel(capabilities: SourceCapabilities = KomgaLikeCapabilities) =
        SearchViewModel(SavedStateHandle(), repository, InMemoryRecentSearches(), MonotonicClock { 0 }, capabilities)

    private fun SearchViewModel.found() = (state.value.results as Loadable.Ready<SearchResults>).value

    private fun genreCalls() = repository.calls.count { it == "genres" }

    @Test
    fun `the results have no authors when the source has none`() {
        val viewModel = viewModel()

        viewModel.onText("mars")
        mainRule.dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.found().authors.items).isEmpty()
        assertThat(viewModel.found().books.items).hasSize(1)
        assertThat(viewModel.found().series.items).hasSize(1)
    }

    @Test
    fun `author results also need author pages`() {
        val capabilities = KomgaLikeCapabilities.copy(searchKinds = SearchKind.entries.toSet())
        val viewModel = viewModel(capabilities)

        viewModel.onText("mars")
        mainRule.dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.found().authors.items).isEmpty()
        assertThat(AuthorResultsViewModel("mars", repository, capabilities).offered).isFalse()
        assertThat(repository.authorTexts).isEmpty()
    }

    @Test
    fun `only authors found reads as nothing found`() {
        repository.searchAnswer = { text ->
            ApiResult.Success(
                SearchResults(text, authors = SearchSection(listOf(Author(AuthorId("50"), "Marsha", 4, null)), 1)),
            )
        }
        val viewModel = viewModel()

        viewModel.onText("mars")
        mainRule.dispatcher.scheduler.advanceUntilIdle()

        assertThat(viewModel.found().isEmpty).isTrue()
    }

    @Test
    fun `the genres are suggested when the source has them`() {
        val viewModel = viewModel()

        assertThat(viewModel.state.value.genres).containsExactly(Genre("Fantasy", 3))
    }

    @Test
    fun `without genres none is asked nor suggested, even when the screen comes back`() {
        val viewModel = viewModel(KomgaLikeCapabilities.copy(genres = false))

        viewModel.onShown()

        assertThat(genreCalls()).isEqualTo(0)
        assertThat(viewModel.state.value.genres).isEmpty()
    }

    private fun showRoute(width: WindowWidthClass, capabilities: SourceCapabilities = KomgaLikeCapabilities) {
        val viewModel = viewModel(capabilities)
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    SearchRoute(actions = actions(), viewModel = viewModel)
                }
            }
        }
        compose.onNodeWithTag(SearchFieldTag).performTextInput("mars")
        mainRule.dispatcher.scheduler.advanceUntilIdle()
        compose.waitForIdle()
    }

    private fun actions() = SearchActions(
        onOpenBook = { opened += it },
        onOpenSeries = { opened += it },
        onOpenAuthor = { opened += it },
        onOpenGenre = { opened += it },
        onSeeAllBooks = {},
        onSeeAllSeries = {},
        onSeeAllAuthors = {},
    )

    @Test
    fun `on a phone the results show books and series, and no author section`() {
        showRoute(WindowWidthClass.Compact)

        compose.onNodeWithText(text(R.string.search_books)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.search_series)).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.search_authors)).assertCountEquals(0)
        compose.onAllNodesWithText("Marsha Writer").assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a tablet the series stand beside the books, with no author section`() {
        showRoute(WindowWidthClass.Expanded)

        compose.onNode(hasText(text(R.string.search_series)) and hasAnyAncestor(hasTestTag(SearchSideTag)))
            .assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.search_authors)).assertCountEquals(0)
        compose.onAllNodesWithText("Marsha Writer").assertCountEquals(0)
    }

    private fun books(count: Int) = context.resources.getQuantityString(R.plurals.search_book_count, count, count)

    @Test
    fun `a series found shows its number of books when the source counts them`() {
        showRoute(WindowWidthClass.Compact)

        compose.onNodeWithTag("result-series-40").assert(hasText(books(3)))
    }

    @Test
    fun `without series counts a series found shows no number of books`() {
        showRoute(WindowWidthClass.Compact, KomgaLikeCapabilities.copy(seriesCounts = false))

        compose.onNodeWithText("A Saga of Mars").assertIsDisplayed()
        compose.onAllNodesWithText(books(3)).assertCountEquals(0)
    }

    @Test
    fun `the full list of authors found goes back without authors, asking nothing`() {
        val viewModel = AuthorResultsViewModel("writer", repository, KomgaLikeCapabilities)
        compose.setContent {
            LirelleTheme {
                AuthorResultsScreen(
                    text = "writer",
                    onBack = { backs++ },
                    onOpenAuthor = { opened += it },
                    viewModel = viewModel,
                )
            }
        }
        compose.waitForIdle()

        assertThat(backs).isEqualTo(1)
        assertThat(repository.authorTexts).isEmpty()
        compose.onAllNodesWithText(text(R.string.search_authors_title, "writer")).assertCountEquals(0)
    }
}

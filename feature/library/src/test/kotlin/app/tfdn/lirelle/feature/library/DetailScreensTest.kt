package app.tfdn.lirelle.feature.library

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.SourceCapabilities.Companion.Everything
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorDetail
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SeriesBooks
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.InMemoryLibraryPreferences
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.ReadMarkTag
import app.tfdn.lirelle.core.ui.SeriesIndexTag
import app.tfdn.lirelle.feature.library.authors.AuthorScreen
import app.tfdn.lirelle.feature.library.authors.AuthorViewModel
import app.tfdn.lirelle.feature.library.books.BookListDetailScreen
import app.tfdn.lirelle.feature.library.books.BookListScope
import app.tfdn.lirelle.feature.library.books.BookListScreen
import app.tfdn.lirelle.feature.library.books.BookListViewModel
import app.tfdn.lirelle.core.downloads.DownloadSurroundings
import app.tfdn.lirelle.core.library.net.NetworkState
import app.tfdn.lirelle.feature.library.series.FakeKeptData
import app.tfdn.lirelle.feature.library.series.FakeSeriesDownloads
import app.tfdn.lirelle.feature.library.series.FakeSeriesSettings
import app.tfdn.lirelle.feature.library.series.SeriesDownloadViewModel
import kotlinx.coroutines.flow.flowOf
import app.tfdn.lirelle.feature.library.series.SeriesScreen
import app.tfdn.lirelle.feature.library.series.SeriesViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val Online = NetworkState(online = true, unmetered = true)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class DetailScreensTest {

    @get:Rule(order = 0)
    val mainRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = FakeLibraryRepository()
    private val preferences = InMemoryLibraryPreferences()
    private val opened = mutableListOf<BookId>()
    private var backs = 0
    private val calls = mutableListOf<String>()

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun showSeries(): SeriesViewModel {
        val viewModel = SeriesViewModel("4", repository, preferences)
        val surroundings = DownloadSurroundings(flowOf(Online), flowOf(true), FakeSeriesSettings()) { 0 }
        val download = SeriesDownloadViewModel(repository, FakeSeriesDownloads(), surroundings, FakeKeptData(calls))
        compose.setContent {
            LirelleTheme {
                SeriesScreen(SeriesId("4"), { backs++ }, { opened += it }, viewModel = viewModel, downloads = download)
            }
        }
        return viewModel
    }

    private fun showAuthor(): AuthorViewModel {
        val viewModel = AuthorViewModel("9", repository, preferences, Everything)
        compose.setContent {
            LirelleTheme {
                AuthorScreen(AuthorId("9"), onBack = { backs++ }, onOpenBook = { opened += it }, viewModel = viewModel)
            }
        }
        return viewModel
    }

    private fun seriesWithBooks() {
        repository.seriesBooksResult = ApiResult.Success(
            SeriesBooks(
                sampleSeries("4", name = "Dawn", books = 6, read = 2),
                listOf(sampleBook("1"), sampleBook("2")),
            ),
        )
    }

    private fun authorWithBooks(biography: String? = "Born somewhere.") {
        val author = Author(AuthorId("9"), "Ursula Writer", 2, null)
        repository.authorResult = ApiResult.Success(AuthorDetail(author, biography))
        repository.authorBooksResult = ApiResult.Success(listOf(sampleBook("5"), sampleBook("6")))
    }

    @Test
    fun `the series screen shows the series, how much is read, and its books`() {
        seriesWithBooks()
        showSeries()

        compose.onNodeWithText("Dawn").assertIsDisplayed()
        compose.onNodeWithText("Author 4").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.series_progress, 2, 6)).assertIsDisplayed()
        compose.onNodeWithTag("book-2").performClick()

        assertThat(opened).containsExactly(BookId("2"))
        assertThat(repository.calls).contains("seriesBooks 4")
    }

    private fun volumes() = compose.onAllNodesWithTag(SeriesIndexTag, useUnmergedTree = true)

    private val volumeBooks = listOf(
        sampleBook("1", series = SeriesRef(SeriesId("4"), "Dawn", "1")),
        sampleBook("2", series = SeriesRef(SeriesId("4"), "Dawn", "2.5"), readState = ReadState.Read),
        sampleBook("3", series = SeriesRef(SeriesId("4"), "Dawn", null)),
    )

    @Test
    fun `the series screen shows the volume number of each book on its cover`() {
        repository.seriesBooksResult = ApiResult.Success(SeriesBooks(sampleSeries("4", name = "Dawn"), volumeBooks))
        showSeries()

        volumes().assertCountEquals(2)
        listOf("1", "2.5").forEach { volume ->
            compose.onNode(hasText(volume) and hasParent(hasTestTag(SeriesIndexTag)), useUnmergedTree = true)
                .assertIsDisplayed()
        }
        compose.onAllNodesWithTag(ReadMarkTag, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `as a list the series screen names the volume on the series line only`() {
        repository.seriesBooksResult = ApiResult.Success(SeriesBooks(sampleSeries("4", name = "Dawn"), volumeBooks))
        showSeries()

        compose.onNodeWithContentDescription(text(R.string.view_as_list)).performClick()

        volumes().assertCountEquals(0)
        compose.onNodeWithText("Dawn #2.5", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the author screen shows no volume number`() {
        authorWithBooks()
        repository.authorBooksResult = ApiResult.Success(volumeBooks)
        showAuthor()

        compose.onNodeWithTag("book-2").assertIsDisplayed()
        volumes().assertCountEquals(0)
        compose.onAllNodesWithTag(ReadMarkTag, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `a list of books, as in the Books tab or a shelf, shows no volume number`() {
        repository.books = volumeBooks
        val viewModel = BookListViewModel(BookListScope.library(), repository, preferences, Everything)
        compose.setContent {
            LirelleTheme {
                BookListScreen(BookListScope.library(), onOpenBook = {}, emptyText = "", viewModel = viewModel)
            }
        }

        compose.onNodeWithTag("book-2").assertIsDisplayed()
        volumes().assertCountEquals(0)
        compose.onAllNodesWithTag(ReadMarkTag, useUnmergedTree = true).assertCountEquals(1)
    }

    private fun showGenre(): BookListViewModel {
        val scope = BookListScope.genre("Fantasy")
        val viewModel = BookListViewModel(scope, repository, preferences, Everything)
        compose.setContent {
            LirelleTheme {
                BookListDetailScreen(
                    title = "Fantasy",
                    scope = scope,
                    onBack = { backs++ },
                    onOpenBook = { opened += it },
                    viewModel = viewModel,
                )
            }
        }
        return viewModel
    }

    @Test
    fun `the books of a genre are shown under its name`() {
        repository.books = listOf(sampleBook("1"), sampleBook("2"))
        showGenre()

        compose.onNodeWithText("Fantasy").assertIsDisplayed()
        compose.onNodeWithTag("book-1").assertIsDisplayed()
        compose.onNodeWithTag("book-2").performClick()
        compose.onNodeWithContentDescription(text(app.tfdn.lirelle.core.ui.R.string.navigate_back)).performClick()

        assertThat(opened).containsExactly(BookId("2"))
        assertThat(backs).isEqualTo(1)
        assertThat(repository.bookRequests.last().second.genre).isEqualTo("Fantasy")
    }

    @Test
    fun `a genre without a book says so, without offering to clear filters`() {
        showGenre()

        compose.onNodeWithText(text(R.string.books_empty_scope)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.books_clear_filters)).assertDoesNotExist()
    }

    @Test
    fun `a series that fails to load shows the error, and back still works`() {
        repository.seriesBooksResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val viewModel = showSeries()

        assertThat(viewModel.state.value.content).isEqualTo(Loadable.Failed(ApiError.ServerUnreachable))
        compose.onNodeWithText(text(app.tfdn.lirelle.core.ui.R.string.library_error_unreachable)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(app.tfdn.lirelle.core.ui.R.string.navigate_back)).performClick()
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `the presentation switch is shared by detail screens and remembered`() {
        seriesWithBooks()
        showSeries()

        compose.onNodeWithContentDescription(text(R.string.view_as_list)).performClick()

        assertThat(preferences.current("detail").presentation).isEqualTo(Presentation.List)
        compose.onNodeWithContentDescription(text(R.string.view_as_grid)).assertIsDisplayed()
    }

    @Test
    fun `the author screen shows the name, the biography and the books`() {
        authorWithBooks()
        showAuthor()

        compose.onNodeWithText("Ursula Writer").assertIsDisplayed()
        compose.onNodeWithText("Born somewhere.").assertIsDisplayed()
        compose.onNodeWithTag("book-5").performClick()

        assertThat(opened).containsExactly(BookId("5"))
        assertThat(repository.calls).containsAtLeast("author 9", "authorBooks 9")
    }

    @Test
    fun `an author whose books fail to load fails as a whole`() {
        authorWithBooks()
        repository.authorBooksResult = ApiResult.Failure(ApiError.Unexpected)

        assertThat(showAuthor().state.value.content).isEqualTo(Loadable.Failed(ApiError.Unexpected))
    }
}

package app.tfdn.lirelle.feature.library

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.paging.PagingData
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.library.LibraryChoiceState
import app.tfdn.lirelle.core.library.SourceCapabilities.Companion.Everything
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryChoice
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.InMemoryLibraryPreferences
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.pagesLoadedBy
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.core.ui.PagingErrorBarTag
import app.tfdn.lirelle.feature.library.authors.AuthorsTab
import app.tfdn.lirelle.feature.library.authors.AuthorsViewModel
import app.tfdn.lirelle.feature.library.books.BookListScope
import app.tfdn.lirelle.feature.library.books.BookListScreen
import app.tfdn.lirelle.feature.library.books.BookListViewModel
import app.tfdn.lirelle.feature.library.series.SeriesListViewModel
import app.tfdn.lirelle.feature.library.series.SeriesTab
import app.tfdn.lirelle.core.ui.R as UiR
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val Many = 60

/**
 * Another library chosen, the Books and Authors tabs show its own list from the top and nothing of the library
 * left, loading or failed; and a pull to refresh in a tab tells the Library, which reads its libraries again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class LibraryChangeListsTest {

    @get:Rule(order = 0)
    val mainRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manga = ServerLibrary(LibraryId("2"), "Manga")
    private val choice = FakeLibraryChoice(LibraryChoiceState(listOf(ServerLibrary(LibraryId("1"), "Comics"), manga)))
    private val everyBook = (1..Many).map { sampleBook("$it") }
    private val mangaBooks = (101..100 + Many).map { sampleBook("$it") }
    private val everyAuthor = (1..Many).map { Author(AuthorId("$it"), "Author $it", 1, null) }
    private val mangaAuthors = (101..100 + Many).map { Author(AuthorId("$it"), "Author $it", 1, null) }
    private val repository = FakeLibraryRepository().apply {
        books = everyBook
        authorsList = everyAuthor
        seriesResult = ApiResult.Success(listOf(sampleSeries("1")))
    }
    private var refreshes = 0

    private fun chooseManga() {
        runBlocking { choice.choose(manga) }
        compose.waitForIdle()
    }

    private fun showBooks() {
        val scope = BookListScope.library()
        val viewModel = BookListViewModel(scope, repository, InMemoryLibraryPreferences(), Everything, choice)
        compose.setContent {
            LirelleTheme {
                BookListScreen(
                    scope = scope,
                    onOpenBook = {},
                    emptyText = "",
                    onRefresh = { refreshes++ },
                    viewModel = viewModel,
                )
            }
        }
    }

    private fun showAuthors() {
        val viewModel = AuthorsViewModel(repository, InMemoryLibraryPreferences(), choice)
        compose.setContent {
            LirelleTheme { AuthorsTab(onOpenAuthor = {}, onRefresh = { refreshes++ }, viewModel = viewModel) }
        }
    }

    /** The list is scrolled until its first items are out of view. */
    private fun scrollAway(firstTag: String) {
        compose.onNodeWithTag(firstTag).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(ScrolledTo)
        compose.onAllNodesWithTag(firstTag).assertCountEquals(0)
    }

    private fun assertNoneShown(prefix: String, ids: IntRange) {
        ids.forEach { compose.onAllNodesWithTag("$prefix-$it").assertCountEquals(0) }
    }

    /** What the server answers for Manga only: held, or failed. */
    private fun <T : Any> forManga(library: ServerLibrary?, load: suspend () -> List<T>): Flow<PagingData<T>>? =
        if (library == manga) pagesLoadedBy(load) else null

    private fun pullDown() {
        compose.onRoot().performTouchInput { swipeDown(startY = height * PullFrom, endY = height * PullTo) }
        compose.waitForIdle()
    }

    @Test
    fun `another library shows its books from the top, whatever was scrolled to`() {
        showBooks()
        scrollAway("book-1")
        repository.books = mangaBooks

        chooseManga()

        compose.onNodeWithTag("book-101").assertIsDisplayed()
        assertNoneShown("book", 1..Many)
    }

    @Test
    fun `while another library loads, no book of the library left is shown`() {
        val answer = CompletableDeferred<Unit>()
        repository.bookPages = { request ->
            forManga(request.library) {
                answer.await()
                mangaBooks
            }
        }
        showBooks()
        compose.onNodeWithTag("book-1").assertIsDisplayed()

        chooseManga()

        assertNoneShown("book", 1..Many)
        assertNoneShown("book", 101..100 + Many)
        answer.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithTag("book-101").assertIsDisplayed()
    }

    @Test
    fun `another library that cannot be loaded shows the error, and no book of the library left`() {
        repository.bookPages = { request -> forManga(request.library) { throw IOException("unreachable") } }
        showBooks()
        scrollAway("book-1")

        chooseManga()

        compose.onNodeWithText(context.getString(UiR.string.state_retry)).assertIsDisplayed()
        compose.onAllNodesWithTag(PagingErrorBarTag).assertCountEquals(0)
        assertNoneShown("book", 1..Many)
    }

    @Test
    fun `another library shows its authors from the top, whatever was scrolled to`() {
        showAuthors()
        scrollAway("author-1")
        repository.authorsList = mangaAuthors

        chooseManga()

        compose.onNodeWithTag("author-101").assertIsDisplayed()
        assertNoneShown("author", 1..Many)
    }

    @Test
    fun `while another library loads, no author of the library left is shown`() {
        val answer = CompletableDeferred<Unit>()
        repository.authorPages = { library ->
            forManga(library) {
                answer.await()
                mangaAuthors
            }
        }
        showAuthors()
        compose.onNodeWithTag("author-1").assertIsDisplayed()

        chooseManga()

        assertNoneShown("author", 1..Many)
        assertNoneShown("author", 101..100 + Many)
        answer.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithTag("author-101").assertIsDisplayed()
    }

    @Test
    fun `another library that cannot be loaded shows the error, and no author of the library left`() {
        repository.authorPages = { library -> forManga(library) { throw IOException("unreachable") } }
        showAuthors()
        scrollAway("author-1")

        chooseManga()

        compose.onNodeWithText(context.getString(UiR.string.state_retry)).assertIsDisplayed()
        compose.onAllNodesWithTag(PagingErrorBarTag).assertCountEquals(0)
        assertNoneShown("author", 1..Many)
    }

    @Test
    fun `another library shows its series from the top, even answered before the loading was drawn`() {
        repository.seriesResult = ApiResult.Success((1..Many).map { sampleSeries("$it") })
        val viewModel = SeriesListViewModel(Everything, repository, InMemoryLibraryPreferences(), choice)
        compose.setContent { LirelleTheme { SeriesTab(onOpenSeries = {}, viewModel = viewModel) } }
        scrollAway("series-1")
        repository.seriesResult = ApiResult.Success((101..100 + Many).map { sampleSeries("$it") })

        chooseManga()

        compose.onNodeWithTag("series-101").assertIsDisplayed()
        assertNoneShown("series", 1..Many)
    }

    @Test
    fun `a pull to refresh of the books tells the Library`() {
        showBooks()

        pullDown()

        assertThat(refreshes).isEqualTo(1)
        compose.onNodeWithTag("book-1").assertIsDisplayed()
    }

    @Test
    fun `a pull to refresh of the authors tells the Library`() {
        showAuthors()

        pullDown()

        assertThat(refreshes).isEqualTo(1)
        compose.onNodeWithTag("author-1").assertIsDisplayed()
    }

    @Test
    fun `a pull to refresh of the series reads them again and tells the Library`() {
        val viewModel = SeriesListViewModel(Everything, repository, InMemoryLibraryPreferences(), choice)
        compose.setContent {
            LirelleTheme { SeriesTab(onOpenSeries = {}, onRefresh = { refreshes++ }, viewModel = viewModel) }
        }
        compose.onNodeWithTag("series-1").assertIsDisplayed()

        pullDown()

        assertThat(refreshes).isEqualTo(1)
        assertThat(repository.calls.count { it == "series" }).isEqualTo(2)
    }

    private companion object {
        const val ScrolledTo = 12
        const val PullFrom = 0.3f
        const val PullTo = 0.9f
    }
}

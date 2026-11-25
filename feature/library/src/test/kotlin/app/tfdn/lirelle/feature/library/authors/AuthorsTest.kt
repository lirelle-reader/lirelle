package app.tfdn.lirelle.feature.library.authors

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.testing.asSnapshot
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.LibraryId
import app.tfdn.lirelle.core.library.LibraryChoiceState
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorSort
import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.testing.FakeLibraryChoice
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.InMemoryLibraryPreferences
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.ui.LocalOpenDownloads
import app.tfdn.lirelle.core.ui.NoNetworkDownloadsTag
import app.tfdn.lirelle.core.ui.NoNetworkRetryTag
import app.tfdn.lirelle.core.ui.NoNetworkStateTag
import app.tfdn.lirelle.core.ui.PagingErrorBarTag
import app.tfdn.lirelle.feature.library.R
import app.tfdn.lirelle.core.ui.R as UiR
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class AuthorsTest {

    @get:Rule(order = 0)
    val mainRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = FakeLibraryRepository().apply {
        authorsList = listOf(Author(AuthorId("1"), "Ada Writer", 12, null), Author(AuthorId("2"), "Bo Author", 1, null))
    }
    private val preferences = InMemoryLibraryPreferences()
    private val comics = ServerLibrary(LibraryId("1"), "Comics")
    private val manga = ServerLibrary(LibraryId("2"), "Manga")
    private val choice = FakeLibraryChoice(LibraryChoiceState(listOf(comics, manga)))
    private val idle = LoadState.NotLoading(endOfPaginationReached = true)

    private fun viewModel() = AuthorsViewModel(repository, preferences, choice)

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun `authors are asked by name, ascending, by default`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        assertThat(viewModel.authors.asSnapshot()).hasSize(2)
        assertThat(repository.authorRequests.last()).isEqualTo(AuthorSort.Name to SortDirection.Ascending)
        assertThat(viewModel.state.value.total).isEqualTo(2)
    }

    @Test
    fun `sorting by number of books starts with the most and is remembered`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onSort(AuthorSort.BookCount)
        viewModel.authors.asSnapshot()

        assertThat(repository.authorRequests.last()).isEqualTo(AuthorSort.BookCount to SortDirection.Descending)
        assertThat(preferences.current("authors").sort).isEqualTo("BookCount")
    }

    @Test
    fun `every sort name stored by the previous versions is restored as the same sort`() =
        runTest(mainRule.dispatcher) {
            val stored = mapOf("Name" to AuthorSort.Name, "BookCount" to AuthorSort.BookCount)

            val restored = stored.mapValues { (name, _) ->
                preferences.update("authors") { it.copy(sort = name, descending = false) }
                viewModel().authors.asSnapshot()
                repository.authorRequests.last().first
            }

            assertThat(restored).isEqualTo(stored)
        }

    @Test
    fun `choosing the current sort again reverses it`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()

        viewModel.onSort(AuthorSort.Name)
        viewModel.authors.asSnapshot()

        assertThat(repository.authorRequests.last()).isEqualTo(AuthorSort.Name to SortDirection.Descending)
    }

    @Test
    fun `the tab shows each author with their number of books and opens them`() {
        val opened = mutableListOf<AuthorId>()
        compose.setContent { LirelleTheme { AuthorsTab(onOpenAuthor = { opened += it }, viewModel = viewModel()) } }

        compose.onNodeWithText("Ada Writer").assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.books_count, 12, 12)).assertIsDisplayed()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.authors_count, 2, 2)).assertIsDisplayed()
        compose.onNodeWithTag("author-2").performClick()

        assertThat(opened).containsExactly(AuthorId("2"))
    }

    @Test
    fun `the sort chip shows the current sort`() {
        compose.setContent { LirelleTheme { AuthorsTab(onOpenAuthor = {}, viewModel = viewModel()) } }

        compose.onNodeWithContentDescription(text(R.string.sort_ascending, text(R.string.sort_name)))
            .assertIsDisplayed()
    }

    @Test
    fun `an author the server sends twice is shown twice, not a crash`() {
        // Offset paging: an author added on the server between two pages shifts the list by one.
        val bo = Author(AuthorId("2"), "Bo Author", 1, null)
        repository.authorsList = listOf(Author(AuthorId("1"), "Ada Writer", 12, null), bo) + bo
        compose.setContent { LirelleTheme { AuthorsTab(onOpenAuthor = {}, viewModel = viewModel()) } }

        compose.onNodeWithText("Ada Writer").assertIsDisplayed()
        compose.onAllNodesWithTag("author-2").assertCountEquals(2)
    }

    @Test
    fun `a failed following page says so where the reader is`() {
        repository.authorsList = List(300) { Author(AuthorId("${it + 1}"), "Author ${it + 1}", 1, null) }
        repository.loadStates = LoadStates(refresh = idle, prepend = idle, append = LoadState.Error(IOException()))
        compose.setContent { LirelleTheme { AuthorsTab(onOpenAuthor = {}, viewModel = viewModel()) } }

        compose.onNodeWithText("Author 1").assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.list_page_error)).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.state_retry)).assertIsDisplayed()
    }

    @Test
    fun `a failed reload keeps the authors and says why`() {
        repository.loadStates = LoadStates(refresh = LoadState.Error(IOException()), prepend = idle, append = idle)
        compose.setContent { LirelleTheme { AuthorsTab(onOpenAuthor = {}, viewModel = viewModel()) } }

        compose.onNodeWithText("Ada Writer").assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.library_error_unreachable)).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.state_retry)).assertIsDisplayed()
    }

    @Test
    fun `a first load failed for lack of network leads to the downloads, under the sort`() {
        var downloadsOpened = 0
        repository.authorsList = emptyList()
        repository.loadStates = LoadStates(refresh = LoadState.Error(IOException()), prepend = idle, append = idle)
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalOpenDownloads provides { downloadsOpened++ }) {
                    AuthorsTab(onOpenAuthor = {}, viewModel = viewModel())
                }
            }
        }

        compose.onNodeWithContentDescription(text(R.string.sort_ascending, text(R.string.sort_name)))
            .assertIsDisplayed()
        compose.onNodeWithTag(NoNetworkStateTag).assertIsDisplayed()
        compose.onNodeWithTag(NoNetworkRetryTag).assertExists()
        compose.onNodeWithTag(NoNetworkDownloadsTag).performScrollTo().performClick()
        assertThat(downloadsOpened).isEqualTo(1)
    }

    @Test
    fun `a reload failed for lack of network keeps the authors, with the bar`() {
        repository.loadStates = LoadStates(refresh = LoadState.Error(IOException()), prepend = idle, append = idle)
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalOpenDownloads provides {}) {
                    AuthorsTab(onOpenAuthor = {}, viewModel = viewModel())
                }
            }
        }

        compose.onNodeWithText("Ada Writer").assertIsDisplayed()
        compose.onNodeWithTag(PagingErrorBarTag).assertIsDisplayed()
        compose.onNodeWithTag(NoNetworkStateTag).assertDoesNotExist()
    }

    @Test
    fun `a failed first load keeps the sort above the error`() {
        repository.authorsList = emptyList()
        repository.loadStates = LoadStates(refresh = LoadState.Error(IOException()), prepend = idle, append = idle)
        compose.setContent { LirelleTheme { AuthorsTab(onOpenAuthor = {}, viewModel = viewModel()) } }

        compose.onNodeWithContentDescription(text(R.string.sort_ascending, text(R.string.sort_name)))
            .assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.library_error_unreachable)).assertIsDisplayed()
        compose.onNodeWithText(text(UiR.string.state_retry)).assertIsDisplayed()
    }

    @Test
    fun `an empty library says so`() {
        repository.authorsList = emptyList()
        compose.setContent { LirelleTheme { AuthorsTab(onOpenAuthor = {}, viewModel = viewModel()) } }

        compose.onNodeWithText(text(R.string.authors_empty)).assertIsDisplayed()
    }

    @Test
    fun `authors are asked for every library when none is chosen`() = runTest(mainRule.dispatcher) {
        viewModel().authors.asSnapshot()

        assertThat(repository.authorLibraries).containsExactly(null)
    }

    @Test
    fun `authors are asked for the chosen library`() = runTest(mainRule.dispatcher) {
        choice.choose(comics)

        viewModel().authors.asSnapshot()

        assertThat(repository.authorLibraries).containsExactly(comics)
    }

    @Test
    fun `another choice gives a new list of authors, the sort kept`() = runTest(mainRule.dispatcher) {
        val viewModel = viewModel()
        viewModel.onSort(AuthorSort.BookCount)
        viewModel.authors.asSnapshot()
        val asked = repository.authorRequests.size

        choice.choose(manga)
        viewModel.authors.asSnapshot()

        assertThat(repository.authorRequests).hasSize(asked + 1)
        assertThat(repository.authorLibraries.last()).isEqualTo(manga)
        assertThat(repository.authorRequests.last()).isEqualTo(AuthorSort.BookCount to SortDirection.Descending)
        assertThat(viewModel.state.value.sort).isEqualTo(AuthorSort.BookCount)
    }

    @Test
    fun `a chosen library renamed on the server gives a new list of authors under its new name`() =
        runTest(mainRule.dispatcher) {
            choice.choose(comics)
            val viewModel = viewModel()
            viewModel.authors.asSnapshot()
            val asked = repository.authorRequests.size
            val renamed = comics.copy(name = "Bandes dessinées")

            choice.libraries(listOf(renamed, manga))
            viewModel.authors.asSnapshot()

            assertThat(repository.authorRequests).hasSize(asked + 1)
            assertThat(repository.authorLibraries.last()).isEqualTo(renamed)

            choice.libraries(listOf(manga, renamed))
            viewModel.authors.asSnapshot()

            assertThat(repository.authorRequests).hasSize(asked + 1)
        }
}

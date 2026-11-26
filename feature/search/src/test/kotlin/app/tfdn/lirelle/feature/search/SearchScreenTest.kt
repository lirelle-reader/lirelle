package app.tfdn.lirelle.feature.search

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.Genre
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.library.model.SearchSection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.LoadingTag
import app.tfdn.lirelle.core.ui.LocalOpenDownloads
import app.tfdn.lirelle.core.ui.NoNetworkDownloadsTag
import app.tfdn.lirelle.core.ui.NoNetworkRetryTag
import app.tfdn.lirelle.core.ui.NoNetworkStateTag
import app.tfdn.lirelle.core.ui.SeeAllTag
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.tfdn.lirelle.core.ui.R as CoreR

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SearchScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Everything the screen asked for, in order. */
    private val events = mutableListOf<String>()
    private val typed = mutableListOf<String>()

    private val results = SearchResults(
        text = "mars",
        books = SearchSection(listOf("1", "2", "3", "4", "5", "6").map { sampleBook(it, "Book $it") }, total = 21),
        series = SearchSection(listOf(sampleSeries("40", "A Saga", books = 3)), total = 1),
        authors = SearchSection(
            listOf(Author(AuthorId("50"), "Marsha Writer", bookCount = 4, portraitUrl = null)),
            total = 1,
        ),
    )
    private val found = SearchUiState(text = "mars", results = Loadable.Ready(results))

    private val actions = SearchActions(
        onOpenBook = { events += "book $it" },
        onOpenSeries = { events += "series $it" },
        onOpenAuthor = { events += "author $it" },
        onOpenGenre = { events += "genre $it" },
        onSeeAllBooks = { events += "all books $it" },
        onSeeAllSeries = { events += "all series $it" },
        onSeeAllAuthors = { events += "all authors $it" },
    )

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun books(count: Int) = context.resources.getQuantityString(R.plurals.search_book_count, count, count)

    /** @param inShell the downloaded books can be reached, as in the shell. */
    private fun show(
        state: SearchUiState,
        width: WindowWidthClass = WindowWidthClass.Compact,
        inShell: Boolean = false,
    ) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(
                    LocalWindowWidthClass provides width,
                    LocalOpenDownloads provides if (inShell) ({ events += "downloads" }) else null,
                ) {
                    SearchContent(
                        state = state,
                        actions = actions,
                        onText = { typed += it },
                        onRetry = { events += "retry" },
                        onResultOpened = { events += "recorded" },
                        onRemoveRecent = { events += "remove $it" },
                        onClearRecent = { events += "clear" },
                    )
                }
            }
        }
    }

    @Test
    fun `typing after running a recent search adds to the end of its text`() {
        var state by mutableStateOf(SearchUiState(recent = listOf("mars")))
        compose.setContent {
            LirelleTheme {
                SearchContent(
                    state = state,
                    actions = actions,
                    onText = { state = state.copy(text = it) },
                    onRetry = {},
                    onResultOpened = {},
                    onRemoveRecent = {},
                    onClearRecent = {},
                )
            }
        }

        compose.onNodeWithText("mars").performClick()
        compose.onNodeWithTag(SearchFieldTag).performTextInput("x")

        assertThat(state.text).isEqualTo("marsx")
    }

    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun `before typing, recent searches and genres are offered`() {
        show(SearchUiState(recent = listOf("mars", "king"), genres = listOf(Genre("Fantasy", 119), Genre("Horror", 1))))

        compose.onNodeWithText("mars").performClick()
        compose.onNodeWithTag("genre-Horror").performClick()
        compose.onNodeWithContentDescription(text(R.string.search_recent_remove, "king")).performClick()
        compose.onNodeWithContentDescription(text(R.string.search_recent_clear_description)).performClick()

        assertThat(typed).containsExactly("mars")
        // A genre has nothing to do with the text typed: nothing is recorded.
        assertThat(events).containsExactly("genre Horror", "remove king", "clear").inOrder()
        compose.onNodeWithText("119").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.search_recent)).assert(isHeading)
        compose.onNodeWithText(text(R.string.search_genres)).assert(isHeading)
    }

    @Test
    fun `a genre chip says its name and its number of books`() {
        show(SearchUiState(genres = listOf(Genre("Fantasy", 119), Genre("Horror", 1))))

        compose.onNodeWithTag("genre-Fantasy").assertContentDescriptionEquals("Fantasy, ${books(119)}")
        compose.onNodeWithTag("genre-Horror").assertContentDescriptionEquals("Horror, ${books(1)}")
    }

    @Test
    fun `a genre whose books the server does not count says its name alone`() {
        show(SearchUiState(genres = listOf(Genre("Fantasy", null))))

        compose.onNodeWithTag("genre-Fantasy").assertContentDescriptionEquals("Fantasy")
        compose.onNodeWithText("Fantasy").assertIsDisplayed()
        compose.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun `sections with nothing are not shown before typing`() {
        show(SearchUiState())

        compose.onNodeWithText(text(R.string.search_recent)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.search_genres)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.search_clear)).assertDoesNotExist()
    }

    @Test
    fun `typing in the field is passed on`() {
        show(SearchUiState())

        compose.onNodeWithTag("search-field").performTextInput("ma")

        assertThat(typed).containsExactly("ma")
    }

    @Test
    fun `results are grouped, five rows per section on a phone, with see all when there are more`() {
        show(found)

        compose.onNodeWithTag("result-book-5").assertExists()
        compose.onNodeWithTag("result-book-6").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.search_see_all_count, 21)).performScrollTo().performClick()
        compose.onNodeWithTag("result-series-40").performScrollTo().performClick()
        compose.onNodeWithTag("result-author-50").performScrollTo().performClick()

        // Each is recorded as a text worth searching before the screen is left.
        assertThat(events)
            .containsExactly("recorded", "all books mars", "recorded", "series 40", "recorded", "author 50")
            .inOrder()
        compose.onAllNodesWithTag(SeeAllTag).assertCountEquals(1)
        listOf(R.string.search_books, R.string.search_series, R.string.search_authors).forEach {
            compose.onNodeWithText(text(it)).assert(isHeading)
        }
    }

    /** Six books found, all of them come: what a source without a list of all books answers. */
    private val sixOfSix = found.copy(
        results = Loadable.Ready(results.copy(books = results.books.copy(total = 6))),
    )

    @Test
    fun `on a phone six books found of six still show five rows and see all where there is a list of all books`() {
        show(sixOfSix)

        compose.onNodeWithTag("result-book-6").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.search_see_all_count, 6)).performScrollTo().performClick()

        assertThat(events).containsExactly("recorded", "all books mars").inOrder()
    }

    @Test
    fun `on a phone every book found shows, with no see all, where there is no list of all books`() {
        show(sixOfSix.copy(seeAllBooks = false))

        compose.onNodeWithTag("result-book-6").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag(SeeAllTag).assertCountEquals(0)
    }

    @Test
    fun `a book found opens its page`() {
        show(found)

        compose.onNodeWithTag("result-book-2").performClick()

        assertThat(events).containsExactly("recorded", "book 2").inOrder()
    }

    @Test
    fun `series and authors found show their number of books`() {
        show(found)

        compose.onNodeWithTag("result-series-40").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(books(3), substring = true).assertExists()
        compose.onNodeWithText(books(4), substring = true).assertExists()
    }

    @Test
    fun `a section without result is not shown`() {
        show(found.copy(results = Loadable.Ready(results.copy(authors = SearchSection()))))

        compose.onNodeWithText(text(R.string.search_authors)).assertDoesNotExist()
    }

    @Test
    fun `nothing found names the text`() {
        show(SearchUiState(text = "zzz", results = Loadable.Ready(SearchResults("zzz"))))

        compose.onNodeWithText(text(R.string.search_nothing, "zzz")).assertIsDisplayed()
    }

    @Test
    fun `a search that runs with nothing to show yet says so`() {
        show(SearchUiState(text = "mars", results = Loadable.Loading))

        compose.onNodeWithTag(LoadingTag).assertIsDisplayed()
    }

    @Test
    fun `a failure keeps the text and offers to retry`() {
        show(SearchUiState(text = "mars", results = Loadable.Failed(ApiError.ServerUnreachable)))

        compose.onNodeWithText("mars").assertIsDisplayed()
        compose.onNodeWithText(text(CoreR.string.state_retry)).performClick()
        assertThat(events).containsExactly("retry")
    }

    @Test
    fun `a failure for lack of network keeps the text and leads to the downloads or tries again`() {
        show(SearchUiState(text = "mars", results = Loadable.Failed(ApiError.ServerUnreachable)), inShell = true)

        compose.onNodeWithText("mars").assertIsDisplayed()
        compose.onNodeWithTag(NoNetworkStateTag).assertIsDisplayed()
        compose.onNodeWithTag(NoNetworkDownloadsTag).performScrollTo().performClick()
        compose.onNodeWithTag(NoNetworkRetryTag).performScrollTo().performClick()

        assertThat(events).containsExactly("downloads", "retry").inOrder()
    }

    @Test
    fun `another failure is the error, in the shell too, and a lack of network outside it`() {
        show(SearchUiState(text = "mars", results = Loadable.Failed(ApiError.ServerFailed)), inShell = true)

        compose.onNodeWithTag(NoNetworkStateTag).assertDoesNotExist()
        compose.onNodeWithText(text(CoreR.string.library_error_server_failed)).assertIsDisplayed()
    }

    @Test
    fun `outside the shell a lack of network is the error`() {
        show(SearchUiState(text = "mars", results = Loadable.Failed(ApiError.ServerUnreachable)))

        compose.onNodeWithTag(NoNetworkStateTag).assertDoesNotExist()
        compose.onNodeWithText(text(CoreR.string.library_error_unreachable)).assertIsDisplayed()
    }

    @Test
    fun `the clear button empties the field`() {
        show(SearchUiState(text = "mars"))

        compose.onNodeWithContentDescription(text(R.string.search_clear)).performClick()

        assertThat(typed).containsExactly("")
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `a wide window shows books as a grid of up to ten, next to series and authors`() {
        val ten = results.copy(books = SearchSection((1..10).map { sampleBook("$it") }, total = 21))
        show(found.copy(results = Loadable.Ready(ten)), WindowWidthClass.Expanded)

        compose.onNodeWithTag("book-10").assertExists()
        val books = compose.onNodeWithTag("search-main").getUnclippedBoundsInRoot()
        val side = compose.onNodeWithTag("search-side").getUnclippedBoundsInRoot()
        assertThat(side.left).isAtLeast(books.right)
        assertThat(books.width.value).isWithin(2f).of(side.width.value * 2)
        compose.onNodeWithTag("result-series-40").assertIsDisplayed()
        compose.onNodeWithTag("result-author-50").assertIsDisplayed()

        compose.onNodeWithTag("book-3").performClick()
        compose.onNodeWithText(text(R.string.search_see_all_count, 21)).performClick()
        assertThat(events).containsExactly("recorded", "book 3", "recorded", "all books mars").inOrder()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `on a wide window a zone with nothing leaves its width to the other`() {
        val booksOnly = results.copy(series = SearchSection(), authors = SearchSection())
        show(found.copy(results = Loadable.Ready(booksOnly)), WindowWidthClass.Expanded)

        compose.onNodeWithTag("search-side").assertDoesNotExist()
        val root = compose.onNodeWithTag("search-field").getUnclippedBoundsInRoot()
        val books = compose.onNodeWithTag("search-main").getUnclippedBoundsInRoot()
        assertThat(books.width.value).isGreaterThan(root.width.value)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `a wide window puts genres next to the recent searches`() {
        show(SearchUiState(recent = listOf("mars"), genres = listOf(Genre("Fantasy", 119))), WindowWidthClass.Expanded)

        val genres = compose.onNodeWithTag("search-main").getUnclippedBoundsInRoot()
        val recent = compose.onNodeWithTag("search-side").getUnclippedBoundsInRoot()
        assertThat(recent.left).isAtLeast(genres.right)
    }

    @Test
    fun `a narrow window puts the recent searches above the genres`() {
        show(SearchUiState(recent = listOf("mars"), genres = listOf(Genre("Fantasy", 119))))

        val genres = compose.onNodeWithTag("search-main").getUnclippedBoundsInRoot()
        val recent = compose.onNodeWithTag("search-side").getUnclippedBoundsInRoot()
        assertThat(genres.top).isAtLeast(recent.bottom)
    }
}

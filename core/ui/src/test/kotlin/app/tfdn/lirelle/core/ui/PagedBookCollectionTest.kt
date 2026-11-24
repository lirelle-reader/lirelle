package app.tfdn.lirelle.core.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.IndexPagingSource
import app.tfdn.lirelle.core.library.PageResult
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.sampleBook
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class PagedBookCollectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val idle = LoadState.NotLoading(endOfPaginationReached = true)
    private var emptyActions = 0
    private var shown: LazyPagingItems<BookSummary>? = null

    private fun data(books: List<BookSummary>, refresh: LoadState = idle, append: LoadState = idle) =
        PagingData.from(books, LoadStates(refresh = refresh, prepend = idle, append = append))

    private fun show(books: List<BookSummary>, refresh: LoadState = idle, append: LoadState = idle) =
        show(flowOf(data(books, refresh, append)))

    /**
     * The list under a header that remembers whether it was opened, as a menu does.
     *
     * @param signIn the profile that waits for a sign-in, as the signed-in level provides it; null when none.
     */
    private fun show(pages: Flow<PagingData<BookSummary>>, signIn: SignInNeeded? = null) {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(
                    LocalSignInNeeded provides signIn,
                    LocalSignInAgain provides { signedInAgain += it },
                ) { Collection(pages) }
            }
        }
    }

    private val signedInAgain = mutableListOf<String>()

    @Composable
    private fun Collection(pages: Flow<PagingData<BookSummary>>) {
        PagedBookCollection(
            books = pages.collectAsLazyPagingItems().also { shown = it },
            presentation = Presentation.Grid,
            onOpenBook = {},
            errorOf = { ApiError.ServerUnreachable },
            emptyText = "Nothing here",
            emptyActionLabel = "Clear",
            onEmptyAction = { emptyActions++ },
            header = {
                item(key = "bar") {
                    var opened by remember { mutableStateOf(false) }
                    Column {
                        TextButton(onClick = { opened = true }) { Text("Open") }
                        if (opened) Text("Opened")
                    }
                }
            },
        )
    }

    @Test
    fun `a failed reload while the profile waits for a sign-in keeps the books, says so and signs in again`() {
        val camille = SignInNeeded("p0", "camille", ServerKind.BookOrbit)
        show(flowOf(data(listOf(sampleBook("1")), refresh = LoadState.Error(IOException("refused")))), camille)

        compose.onNodeWithTag("book-1").assertIsDisplayed()
        compose.onNodeWithTag(SignInNeededBarTag).assertIsDisplayed()
        compose.onNodeWithText("The session of camille · BookOrbit has expired.").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_error_unreachable)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.state_retry)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.sign_in_again)).performClick()

        assertThat(signedInAgain).containsExactly("p0")
    }

    @Test
    fun `a following page that failed while the profile waits for a sign-in says so too`() {
        val camille = SignInNeeded("p0", "camille", ServerKind.BookOrbit)
        show(flowOf(data(listOf(sampleBook("1")), append = LoadState.Error(IOException("refused")))), camille)

        compose.onNodeWithTag(SignInNeededBarTag).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.list_page_error)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.state_retry)).assertDoesNotExist()
    }

    /** A server with [total] books, in pages of [PageSize], whose pages can be made to fail. */
    private inner class Library(private val total: Int) {
        var failing: (page: Int) -> Boolean = { false }
        val asked = mutableListOf<Int>()

        val pages = Pager(PagingConfig(pageSize = PageSize, initialLoadSize = PageSize, enablePlaceholders = true)) {
            IndexPagingSource(PageSize) { page, size ->
                asked += page
                if (failing(page)) throw IOException("offline")
                val first = page * size
                PageResult((first until minOf(first + size, total)).map { sampleBook("${it + 1}") }, total)
            }
        }.flow
    }

    private fun waitForBook(id: Long) =
        compose.waitUntil { compose.onAllNodesWithTag("book-$id").fetchSemanticsNodes().isNotEmpty() }

    private fun waitForNoErrorBar() =
        compose.waitUntil { compose.onAllNodesWithTag(PagingErrorBarTag).fetchSemanticsNodes().isEmpty() }

    @Test
    fun `loaded books are shown`() {
        show(listOf(sampleBook("1"), sampleBook("2")))

        compose.onNodeWithTag("book-1").assertIsDisplayed()
        compose.onNodeWithTag("book-2").assertIsDisplayed()
    }

    @Test
    fun `the first load shows placeholders`() {
        show(emptyList(), refresh = LoadState.Loading)

        assertThat(compose.onAllNodesWithTag(BookPlaceholderTag).fetchSemanticsNodes()).isNotEmpty()
    }

    @Test
    fun `a failed first load shows the error with a retry`() {
        show(emptyList(), refresh = LoadState.Error(IOException("offline")))

        compose.onNodeWithText(context.getString(R.string.library_error_unreachable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.state_retry)).assertIsDisplayed()
    }

    @Test
    fun `an empty list shows its message and action`() {
        show(emptyList())

        compose.onNodeWithText("Nothing here").assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()

        assertThat(emptyActions).isEqualTo(1)
    }

    @Test
    fun `a failed following page keeps the books and offers a retry`() {
        show(listOf(sampleBook("1")), append = LoadState.Error(IOException("offline")))

        compose.onNodeWithTag("book-1").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.list_page_error)).assertIsDisplayed()
    }

    @Test
    fun `a failed second page of a long list says so where the reader is, and retries`() {
        val library = Library(total = 720)
        library.failing = { it > 0 }
        show(library.pages)
        waitForBook(1)

        // The list has 720 places and the reader is at the top of it.
        assertThat(checkNotNull(shown).itemCount).isEqualTo(720)
        compose.onNodeWithTag("book-1").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.list_page_error)).assertIsDisplayed()

        library.failing = { false }
        compose.onNodeWithText(context.getString(R.string.state_retry)).assertIsDisplayed().performClick()

        waitForNoErrorBar()
        assertThat(library.asked).containsExactly(0, 1, 1).inOrder()
        compose.onNodeWithTag("book-1").assertIsDisplayed()
    }

    @Test
    fun `a failed reload keeps the books, says why and retries`() {
        val library = Library(total = 3)
        show(library.pages)
        waitForBook(1)

        library.failing = { true }
        compose.runOnIdle { checkNotNull(shown).refresh() }

        compose.waitUntil { compose.onAllNodesWithTag(PagingErrorBarTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book-1").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_error_unreachable)).assertIsDisplayed()

        library.failing = { false }
        compose.onNodeWithText(context.getString(R.string.state_retry)).assertIsDisplayed().performClick()

        waitForNoErrorBar()
        assertThat(library.asked).containsExactly(0, 0, 0).inOrder()
        compose.onNodeWithTag("book-1").assertIsDisplayed()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a new sort or filter that cannot be loaded keeps the old books, says why and retries`() {
        val before = Library(total = 3)
        val after = Library(total = 1).apply { failing = { true } }
        val lists = MutableStateFlow(before.pages)
        show(lists.flatMapLatest { it })
        waitForBook(1)

        lists.value = after.pages

        compose.waitUntil { compose.onAllNodesWithTag(PagingErrorBarTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("book-3").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_error_unreachable)).assertIsDisplayed()

        after.failing = { false }
        compose.onNodeWithText(context.getString(R.string.state_retry)).performClick()

        waitForNoErrorBar()
        assertThat(after.asked).containsExactly(0, 0).inOrder()
        compose.onNodeWithTag("book-3").assertDoesNotExist()
    }

    @Test
    fun `a failed first load keeps the header above the error`() {
        show(emptyList(), refresh = LoadState.Error(IOException("offline")))

        compose.onNodeWithText("Open").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.library_error_unreachable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.state_retry)).assertIsDisplayed()
    }

    @Test
    fun `the header keeps what it remembers while the list reloads, empties and fails`() {
        val pages = MutableStateFlow(data(listOf(sampleBook("1"))))
        show(pages)
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Opened").assertIsDisplayed()

        val steps = listOf(
            data(emptyList(), refresh = LoadState.Loading),
            data(emptyList()),
            data(emptyList(), refresh = LoadState.Error(IOException("offline"))),
            data(listOf(sampleBook("2"))),
        )
        steps.forEach { step ->
            pages.value = step
            compose.waitForIdle()

            compose.onNodeWithText("Opened").assertIsDisplayed()
        }
        compose.onNodeWithTag("book-2").assertIsDisplayed()
    }

    private companion object {
        const val PageSize = 60
    }
}

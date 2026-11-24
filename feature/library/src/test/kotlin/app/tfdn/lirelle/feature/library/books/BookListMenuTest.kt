package app.tfdn.lirelle.feature.library.books

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.model.BookSummary
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.ui.PagedBookCollection
import app.tfdn.lirelle.feature.library.R
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Ticking a filter reloads the list; the menu the filter was ticked in must not close. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class BookListMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val idle = LoadState.NotLoading(endOfPaginationReached = true)
    private val toggled = mutableListOf<FormatFilter>()

    private fun data(books: List<BookSummary>, refresh: LoadState = idle) =
        PagingData.from(books, LoadStates(refresh = refresh, prepend = idle, append = idle))

    @Test
    fun `an open filter menu stays open while the list reloads and comes back empty`() {
        val pages = MutableStateFlow(data(listOf(sampleBook("1"))))
        compose.setContent {
            LirelleTheme {
                PagedBookCollection(
                    books = pages.collectAsLazyPagingItems(),
                    presentation = Presentation.Grid,
                    onOpenBook = {},
                    errorOf = { ApiError.ServerUnreachable },
                    emptyText = context.getString(R.string.books_empty_filtered),
                    header = {
                        item(key = "bar") {
                            SortFilterBar(
                                state = BookListUiState(),
                                onSort = {},
                                onToggleFormat = { toggled += it },
                                onToggleStatus = {},
                                onReadAlongOnly = {},
                                onPresentation = {},
                            )
                        }
                    },
                )
            }
        }
        compose.onNodeWithTag("book-1").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.filter_format)).performClick()
        compose.onNodeWithText(context.getString(R.string.format_comics)).performClick()

        // What ticking a filter does to the list: it reloads, and here nothing matches.
        pages.value = data(emptyList(), refresh = LoadState.Loading)
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.format_comics)).assertIsDisplayed()

        pages.value = data(emptyList())
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.books_empty_filtered)).assertIsDisplayed()

        // The menu is still there to tick a second format in.
        compose.onNodeWithText(context.getString(R.string.format_audio)).assertIsDisplayed().performClick()
        assertThat(toggled).containsExactly(FormatFilter.Comics, FormatFilter.Audio).inOrder()
    }
}
